#!/usr/bin/env python3
"""Offline, explicitly assumed distribution of intermunicipal toll OD to multiple nodes.

First export native routing candidates; then consume duarouter output and choose
feasible directed edges. Published section counts are never used as entry weights.
Intramunicipal OD and OD outside the reviewed access catalogue stay unmapped.
"""
import argparse
import hashlib
import importlib.util
import itertools
import json
import math
import pathlib
import xml.etree.ElementTree as ET
from collections import defaultdict

spec = importlib.util.spec_from_file_location('toll_prepare', pathlib.Path(__file__).with_name('prepare-toll-freight.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


def digest(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def in_ring(point, ring):
    x, y = point
    inside = False
    for a, b in zip(ring, ring[1:]):
        if (a[1] > y) != (b[1] > y) and x < (b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0]:
            inside = not inside
    return inside


def in_geometry(point, geometry):
    if geometry['type'] not in ('Polygon', 'MultiPolygon'):
        raise ValueError('municipality polygon required')
    polygons = [geometry['coordinates']] if geometry['type'] == 'Polygon' else geometry['coordinates']
    return any(in_ring(point, p[0]) and not any(in_ring(point, h) for h in p[1:]) for p in polygons)


def distance_metres(point, shape):
    best = math.inf
    scale = 111320*math.cos(math.radians(point[1]))
    for a, b in zip(shape, shape[1:]):
        ax, ay = (a[0]-point[0])*scale, (a[1]-point[1])*111320
        bx, by = (b[0]-point[0])*scale, (b[1]-point[1])*111320
        dx, dy = bx-ax, by-ay
        t = max(0, min(1, -(ax*dx+ay*dy)/(dx*dx+dy*dy))) if dx*dx+dy*dy else 0
        best = min(best, math.hypot(ax+t*dx, ay+t*dy))
    return best


def permitted(edge, clazz):
    for lane in edge.findall('lane'):
        allow, deny = lane.attrib.get('allow', '').split(), lane.attrib.get('disallow', '').split()
        if (not allow or 'all' in allow or clazz in allow) and 'all' not in deny and clazz not in deny:
            return True
    return False


def build_candidates(records, catalog, network, geojson, municipalities):
    if not isinstance(catalog.get('assumption'), str) or not catalog['assumption'].strip():
        raise ValueError('explicit distribution assumption required')
    shares = catalog['classes']
    if not shares or set(shares)-{'truck', 'trailer'} or any(type(v) not in (int, float) or not math.isfinite(v) or v <= 0 for v in shares.values()) or abs(sum(shares.values())-1)>1e-9:
        raise ValueError('positive truck/trailer shares must sum to one')
    begin, end = catalog['departureHours']
    if not all(type(x) in (int, float) and math.isfinite(x) for x in (begin, end)) or not 0 <= begin < end <= 24:
        raise ValueError('invalid departure window')
    edges = {e.attrib['id']: e for e in network.findall('edge') if e.attrib.get('function') != 'internal'}
    shapes = {f['properties']['edgeId']: f['geometry']['coordinates'] for f in geojson['features']}
    polygons = {f['properties']['ags_ziel']: f['geometry'] for f in municipalities['features']}
    nodes, ids = catalog['municipalities'], set()
    for ags, group in sorted(nodes.items()):
        if ags not in polygons or not group:
            raise ValueError('municipality geometry/access group missing')
        for node in group:
            if node['id'] in ids:
                raise ValueError('duplicate node id')
            ids.add(node['id'])
            point, weight = node['point'], node['weight']
            if len(point) != 2 or any(type(x) not in (int, float) or not math.isfinite(x) for x in point) or abs(point[0])>180 or abs(point[1])>90:
                raise ValueError('invalid node coordinates')
            membership = [a for a, g in polygons.items() if in_geometry(point, g)]
            if membership != [ags]:
                raise ValueError('node does not belong uniquely to stated municipality')
            if type(weight) not in (int, float) or not math.isfinite(weight) or weight <= 0:
                raise ValueError('positive assumed node weight required')
            choices = node['edges']
            if not choices or len(choices)>4 or len({c['edgeId'] for c in choices})!=len(choices):
                raise ValueError('one to four distinct candidate edges required')
            for choice in choices:
                eid = choice['edgeId']
                if eid not in edges or eid not in shapes or distance_metres(point, shapes[eid])>75:
                    raise ValueError('access edge absent or farther than75m')
                if not all(permitted(edges[eid], c) for c in shares):
                    raise ValueError('access edge does not admit modeled classes')
    candidates = []
    for start, end in sorted(records):
        if start == end or start not in nodes or end not in nodes:
            continue
        for origin, destination, clazz in itertools.product(sorted(nodes[start], key=lambda n:n['id']), sorted(nodes[end], key=lambda n:n['id']), sorted(shares)):
            for a, b in itertools.product(sorted(origin['edges'], key=lambda e:e['edgeId']), sorted(destination['edges'], key=lambda e:e['edgeId'])):
                if a['edgeId'] == b['edgeId']:
                    continue
                candidates.append(dict(id=f'candidate_{len(candidates):05d}', ags_start=start, ags_ziel=end,
                    originNode=origin['id'], destinationNode=destination['id'], vehicleClass=clazz,
                    fromEdge=a['edgeId'], toEdge=b['edgeId'], fromLon=origin['point'][0], fromLat=origin['point'][1],
                    toLon=destination['point'][0], toLat=destination['point'][1], priorNodeWeight=origin['weight']*destination['weight']))
    if not candidates or len(candidates)>10000:
        raise ValueError('empty or oversized routing candidate set')
    return candidates


def distribute(records, catalog, candidates, routes, network, unsupported):
    lookup = {c['id']: c for c in candidates}
    edges = {e.attrib['id']: e for e in network.findall('edge') if e.attrib.get('function') != 'internal'}
    best, seen = {}, set()
    rejected_unsupported = 0
    for vehicle in routes.findall('vehicle'):
        vid = vehicle.attrib['id']
        if vid not in lookup or vid in seen:
            raise ValueError('unknown/duplicate routing candidate')
        seen.add(vid)
        c = lookup[vid]
        if vehicle.attrib.get('type') != c['vehicleClass']:
            raise ValueError('native routing candidate vehicle type mismatch')
        route = vehicle.find('route')
        if route is None or not route.attrib.get('edges'):
            raise ValueError('explicit native route required')
        path = route.attrib['edges'].split()
        if path[0]!=c['fromEdge'] or path[-1]!=c['toEdge'] or any(e not in edges or not permitted(edges[e], c['vehicleClass']) for e in path):
            raise ValueError('route endpoints or class permissions differ from candidate')
        if {e.lstrip('-').split('#')[0] for e in path} & unsupported:
            rejected_unsupported += 1
            continue
        # Direction choice uses a disclosed free-flow proxy, not an observed journey cost.
        cost = sum(min(float(l.attrib['length'])/min(22.22, float(l.attrib['speed'])) for l in edges[e].findall('lane') if float(l.attrib['speed'])>0 and lane_permitted(l,c['vehicleClass'])) for e in path)
        group = (c['ags_start'],c['ags_ziel'],c['vehicleClass'],c['originNode'],c['destinationNode'])
        option = (cost,vid,c)
        if group not in best or option[:2]<best[group][:2]:
            best[group] = option
    groups = defaultdict(list)
    for (start,end,clazz,_,_), (_,vid,c) in best.items():
        groups[start,end,clazz].append(c)
    selected_od = sorted({(c['ags_start'],c['ags_ziel']) for c in candidates})
    rows, missing_groups = [], []
    for start,end in selected_od:
        if any(not groups[start,end,clazz] for clazz in catalog['classes']):
            missing_groups.append([start,end]);continue
        for clazz, class_share in sorted(catalog['classes'].items()):
            options = groups[start,end,clazz]
            total = sum(c['priorNodeWeight'] for c in options)
            for c in options:
                row = {k:c[k] for k in ('ags_start','ags_ziel','vehicleClass','fromEdge','toEdge','fromLon','fromLat','toLon','toLat')}
                row.update(id='regional_'+c['id'],share=class_share*c['priorNodeWeight']/total,movement='internal',
                           beginHour=catalog['departureHours'][0],endHour=catalog['departureHours'][1],
                           originNode=c['originNode'],destinationNode=c['destinationNode'],nativeCandidateId=c['id'])
                rows.append(row)
    if not rows:
        raise ValueError('no complete OD-class group can be distributed')
    mapping = {'assumption':catalog['assumption']+' Direction selected by minimum free-flow proxy among returned native candidates; node-pair weights renormalized over feasible routes, excluding all reported unsupported HGV ways.',
               'relations':sorted(rows,key=lambda r:r['id']), 'accessCatalog':catalog}
    report = dict(candidateCount=len(candidates),routedCandidateCount=len(seen),unroutedCandidateCount=len(candidates)-len(seen),
                  routedCandidatesRejectedForUnsupportedHgv=rejected_unsupported,selectedObservedOd=len(selected_od),
                  mappedOd=len({(r['ags_start'],r['ags_ziel']) for r in rows}),unmappedOdMissingClassGroup=missing_groups,
                  mappedMonthlyEntries=sum(records[k]['anzahl_befahrungen'] for k in selected_od if list(k) not in missing_groups),
                  missingPublishedIntermunicipalCells=[[a,b] for a,b in itertools.permutations(sorted(catalog['municipalities']),2) if (a,b) not in records],
                  intramunicipalMonthlyEntriesExcluded=sum(a['anzahl_befahrungen'] for k,a in records.items() if k[0]==k[1] and k[0] in catalog['municipalities']),
                  sectionCountsUsedAsEntryWeights=False,jointNodeDistributionObserved=False,throughTrafficCompleted=False,calibrated=False)
    return mapping,report


def lane_permitted(lane, clazz):
    allow,deny=lane.attrib.get('allow','').split(),lane.attrib.get('disallow','').split()
    return (not allow or clazz in allow or 'all' in allow) and clazz not in deny and 'all' not in deny


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs',nargs='+',type=pathlib.Path)
    for name in ('month','catalog','network','network-geo','municipalities','permissions','output'):
        parser.add_argument('--'+name,required=True,type=str if name=='month' else pathlib.Path)
    parser.add_argument('--routes',type=pathlib.Path)
    args=parser.parse_args()
    catalog=json.loads(args.catalog.read_text())
    if catalog['month']!=args.month:raise ValueError('catalog month mismatch')
    records,observations=prepare.normalize(args.inputs,args.month)
    network=ET.parse(args.network).getroot()
    candidates=build_candidates(records,catalog,network,json.loads(args.network_geo.read_text()),json.loads(args.municipalities.read_text()))
    hashes={n:digest(p) for n,p in [('catalog',args.catalog),('network',args.network),('networkGeo',args.network_geo),('municipalities',args.municipalities),('permissions',args.permissions)]}
    args.output.mkdir(parents=True,exist_ok=True)
    if args.routes is None:
        # A new candidate phase invalidates prior derived mappings in this output directory.
        for name in ('mapping.json','distribution-report.json'):
            (args.output/name).unlink(missing_ok=True)
        root=ET.Element('routes')
        for clazz in sorted(catalog['classes']):ET.SubElement(root,'vType',id=clazz,vClass=clazz,maxSpeed='22.22')
        for c in candidates:ET.SubElement(root,'trip',id=c['id'],type=c['vehicleClass'],depart='21600',attrib={'from':c['fromEdge'],'to':c['toEdge']})
        ET.ElementTree(root).write(args.output/'routing-candidates.xml',encoding='utf-8',xml_declaration=True)
        (args.output/'candidate-report.json').write_text(json.dumps({'candidates':candidates,'hashes':hashes,'observations':observations},indent=2)+'\n')
        print('routing candidates',len(candidates));return
    unsupported={t.split(':')[0] for t in json.loads(args.permissions.read_text())['unsupportedHgvTags']}
    mapping,report=distribute(records,catalog,candidates,ET.parse(args.routes).getroot(),network,unsupported)
    report.update(hashes=hashes|{'candidateRoutes':digest(args.routes)},observationFiles=observations['files'])
    (args.output/'mapping.json').write_text(json.dumps(mapping,indent=2)+'\n')
    (args.output/'distribution-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('hashes','observationFiles')},indent=2))


if __name__=='__main__':main()
