#!/usr/bin/env python3
"""Nearby SUMO road-edge candidates and small native-routing probes, not entrances.

Class permissions are checked conservatively against source OSM tags. Dimension,
conditional and purpose-dependent restrictions remain unresolved, never cleared.
No trips from this research probe are activated as scenario demand.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def permits(edge, clazz):
    for lane in edge.findall('lane'):
        allowed = lane.get('allow','').split()
        denied = lane.get('disallow','').split()
        if (not allowed or 'all' in allowed or clazz in allowed) and 'all' not in denied and clazz not in denied:
            return True
    return False


def restriction(tags, backward=False):
    direction = ':backward' if backward else ':forward'
    if tags.get('hgv'+direction, tags.get('hgv')) == 'no':
        return 'hgv-prohibited'
    for key,value in tags.items():
        if key.startswith(('maxweight','maxheight','maxwidth','maxlength','maxaxleload')) or ':conditional' in key:
            return 'unresolved-dimension-or-conditional'
        if key.startswith('hgv') and (key not in ('hgv','hgv:forward','hgv:backward') or value not in ('yes','no','designated')):
            return 'unresolved-hgv-rule'
    if any(tags.get(k) in ('private','destination','delivery','customers','permit','agricultural','forestry','no')
           for k in ('access','vehicle','motor_vehicle')):
        return 'unresolved-purpose-or-access'
    if tags.get('layer','0') != '0' or tags.get('bridge') not in (None,'no') or tags.get('tunnel') not in (None,'no'):
        return 'unresolved-grade-separation'
    return None


def segment_distance(point, shape):
    best = math.inf
    for a,b in zip(shape,shape[1:]):
        dx,dy=b[0]-a[0],b[1]-a[1]
        t=max(0,min(1,((point[0]-a[0])*dx+(point[1]-a[1])*dy)/(dx*dx+dy*dy))) if dx*dx+dy*dy else 0
        best=min(best,math.hypot(point[0]-a[0]-t*dx,point[1]-a[1]-t*dy))
    return best


def inspect(collection, network, osm, probe_limit=20):
    from pyproj import Proj
    location=network.find('location')
    projection=Proj(location.get('projParameter'))
    offset=[float(x) for x in location.get('netOffset').split(',')]
    tags={w.get('id'):{t.get('k'):t.get('v') for t in w.findall('tag')} for w in osm.findall('way')}
    controlled_nodes=set()
    for node in osm.findall('node'):
        node_tags={t.get('k'):t.get('v') for t in node.findall('tag')}
        if node_tags.get('barrier') not in (None,'no') or restriction(node_tags):
            controlled_nodes.add(node.get('id'))
    controlled_ways={w.get('id') for w in osm.findall('way')
                     if any(n.get('ref') in controlled_nodes for n in w.findall('nd'))}
    unresolved_relation_ways=set()
    for relation in osm.findall('relation'):
        relation_tags={t.get('k'):t.get('v') for t in relation.findall('tag')}
        if (relation_tags.get('type') != 'restriction' or 'except' in relation_tags
                or any(k.startswith('restriction:') or ':conditional' in k for k in relation_tags)):
            unresolved_relation_ways.update(m.get('ref') for m in relation.findall('member') if m.get('type')=='way')
    edges, grid = {}, defaultdict(set)
    skipped=Counter()
    for edge in network.findall('edge'):
        if edge.get('function')=='internal':continue
        identifier=edge.get('id')
        match=re.fullmatch(r'-?(\d+)(?:#.*)?',identifier)
        way_tags=tags.get(match.group(1)) if match else None
        if way_tags is None:
            skipped['missing-source-way']+=1
            continue
        lanes=edge.findall('lane')
        if not lanes:continue
        shape=[tuple(map(float,p.split(',')[:2])) for p in lanes[0].get('shape','').split()]
        if len(shape)<2:continue
        reason=restriction(way_tags,identifier.startswith('-'))
        if not reason and match.group(1) in unresolved_relation_ways:
            reason='unresolved-relation-rule'
        if not reason and match.group(1) in controlled_ways:
            reason='unresolved-node-control'
        if way_tags.get('highway') in ('motorway','motorway_link','trunk','trunk_link'):
            site_reason='not-local-site-access-road'
        else:site_reason=reason
        edges[identifier]={'xml':edge,'shape':shape,'restriction':reason,'siteRestriction':site_reason,
                           'highway':way_tags.get('highway'),'length':float(lanes[0].get('length'))}
        for a,b in zip(shape,shape[1:]):
            for x in range(math.floor(min(a[0],b[0])/50), math.floor(max(a[0],b[0])/50)+1):
                for y in range(math.floor(min(a[1],b[1])/50), math.floor(max(a[1],b[1])/50)+1):
                    grid[x,y].add(identifier)
    rows=[]
    seen=set()
    for feature in sorted(collection['features'],key=lambda f:f['properties']['id']):
        identifier=feature['properties']['id']
        if identifier in seen:raise ValueError('duplicate site ID')
        seen.add(identifier)
        lon,lat=feature['geometry']['coordinates']
        x,y=projection(lon,lat);point=(x+offset[0],y+offset[1])
        nearby=set()
        for dx in (-1,0,1):
            for dy in (-1,0,1):nearby.update(grid.get((math.floor(point[0]/50)+dx,math.floor(point[1]/50)+dy),set()))
        distances=sorted((segment_distance(point,edges[e]['shape']),e) for e in nearby)
        distances=[(d,e) for d,e in distances if d<=50]
        classes={}
        for clazz in ('truck','trailer'):
            classes[clazz]=[{'edgeId':e,'distanceMetres':round(d,3)} for d,e in distances
                            if not edges[e]['siteRestriction'] and permits(edges[e]['xml'],clazz)][:3]
        rows.append({'siteId':identifier,'candidates':classes,
                     'nearbyExcludedReasons':dict(sorted(Counter(edges[e]['siteRestriction'] or 'class-permission'
                         for _,e in distances if edges[e]['siteRestriction'] or not any(permits(edges[e]['xml'],c) for c in ('truck','trailer'))).items())),
                     'truckAccessVerified':False,'physicalEntranceVerified':False})
    probes=[]; root=ET.Element('routes')
    references={}
    for clazz in ('truck','trailer'):
        ET.SubElement(root,'vType',id=clazz,vClass=clazz)
        choices=[e for e,v in edges.items() if v['highway']=='primary' and not v['restriction'] and permits(v['xml'],clazz)]
        if not choices:raise ValueError('no conservative reference edge for '+clazz)
        references[clazz]=sorted(choices,key=lambda e:(-edges[e]['length'],e))[0]
    # Evenly spaced positions in ID order; not a spatial or population-representative sample.
    sample=rows if len(rows)<=probe_limit else [rows[i*len(rows)//probe_limit] for i in range(probe_limit)]
    for index,row in enumerate(sample):
        for clazz in ('truck','trailer'):
            reference=references[clazz]
            candidates=[c for c in row['candidates'][clazz] if c['edgeId']!=reference]
            if not candidates:continue
            target=candidates[0]['edgeId']
            for direction,a,b in [('in',reference,target),('out',target,reference)]:
                identifier='probe_{}_{}_{}'.format(index,clazz,direction)
                ET.SubElement(root,'trip',id=identifier,type=clazz,depart='0',**{'from':a,'to':b})
                probes.append({'id':identifier,'siteId':row['siteId'],'vehicleClass':clazz,'direction':direction,'fromEdge':a,'toEdge':b})
    return rows,probes,root,edges,{'referenceEdges':references,'unmappedNetworkEdges':dict(skipped)}


def evaluate(probes, routes, edges):
    vehicles={v.get('id'):v for v in routes.findall('vehicle')}
    if len(vehicles)!=len(routes.findall('vehicle')):raise ValueError('duplicate routed probe')
    if set(vehicles)-{p['id'] for p in probes}:raise ValueError('unknown routed probe')
    results=[]
    for probe in probes:
        vehicle=vehicles.get(probe['id'])
        result={**probe,'status':'no-native-route','physicalEntranceVerified':False}
        if vehicle is not None:
            route=vehicle.find('route')
            path=route.get('edges','').split() if route is not None else []
            if not path or path[0]!=probe['fromEdge'] or path[-1]!=probe['toEdge'] or vehicle.get('type')!=probe['vehicleClass']:
                raise ValueError('native route endpoints/type do not match probe')
            if any(e not in edges or not permits(edges[e]['xml'],probe['vehicleClass']) for e in path):
                raise ValueError('native route uses unmapped/disallowed edge')
            unresolved=sorted({edges[e]['restriction'] for e in path if edges[e]['restriction']})
            result.update(status='route-has-unresolved-source-rules' if unresolved else 'native-class-route-found',
                          unresolvedSourceRules=unresolved,routeEdges=len(path))
        results.append(result)
    return results


def digest(path):
    checksum=hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''):checksum.update(block)
    return checksum.hexdigest()


def compact_network(path):
    root=ET.Element('net')
    for _,element in ET.iterparse(path,events=('end',)):
        if element.tag=='location':
            ET.SubElement(root,'location',**element.attrib)
        elif element.tag=='edge':
            if element.get('function')!='internal':
                edge=ET.SubElement(root,'edge',**element.attrib)
                for lane in element.findall('lane'):
                    ET.SubElement(edge,'lane',**lane.attrib)
            element.clear()
        elif element.tag in ('junction','connection','tlLogic','roundabout'):
            element.clear()
    return root


def compact_osm(path):
    root=ET.Element('osm');controls=set()
    for _,element in ET.iterparse(path,events=('end',)):
        if element.tag not in ('node','way','relation'):continue
        tags={t.get('k'):t.get('v') for t in element.findall('tag')}
        if element.tag=='node':
            if tags.get('barrier') not in (None,'no') or restriction(tags):
                controls.add(element.get('id'))
                node=ET.SubElement(root,'node',id=element.get('id'))
                for key,value in tags.items():ET.SubElement(node,'tag',k=key,v=value)
        elif element.tag=='way':
            way=ET.SubElement(root,'way',id=element.get('id'))
            for key,value in tags.items():ET.SubElement(way,'tag',k=key,v=value)
            for nd in element.findall('nd'):
                if nd.get('ref') in controls:ET.SubElement(way,'nd',ref=nd.get('ref'))
        elif element.tag=='relation':
            relation=ET.SubElement(root,'relation',id=element.get('id'))
            for key,value in tags.items():ET.SubElement(relation,'tag',k=key,v=value)
            for member in element.findall('member'):ET.SubElement(relation,'member',**member.attrib)
        element.clear()
    return root


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('sites','network','osm','output'):parser.add_argument('--'+name,required=True,type=Path)
    parser.add_argument('--routes',type=Path)
    parser.add_argument('--import-log',type=Path,help='netconvert log; warnings remain a source-coverage limitation')
    parser.add_argument('--probe-limit',type=int,default=20)
    args=parser.parse_args()
    if not 1<=args.probe_limit<=100:parser.error('probe-limit must be between 1 and 100')
    rows,probes,root,edges,extra=inspect(json.loads(args.sites.read_text()),compact_network(args.network),compact_osm(args.osm),args.probe_limit)
    report={'contract':'freight-access-review-v1','sites':len(rows),
            'sitesWithNearbyCandidate':{c:sum(bool(r['candidates'][c]) for r in rows) for c in ('truck','trailer')},
            'probeTrips':len(probes),'maximumCandidateDistanceMetres':50,
            'physicalEntranceVerified':False,'simulationReady':False,
            'limits':['nearby road geometry does not prove a physical connection, gate or correct carriageway',
                      'no vehicle dimensions, loading or manoeuvrability verified',
                      'reference-edge probes are technical tests, not observed freight routes'],
            'sourceRuleCoverageVerified':False,
            'hashes':{k:digest(getattr(args,k)) for k in ('sites','network','osm')},**extra}
    report['hashes']['script']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    if args.import_log:
        lines=args.import_log.read_text().splitlines()
        report['nativeImportWarningLines']=sum(line.startswith('Warning:') for line in lines)
        report['hashes']['nativeImportLog']=digest(args.import_log)
    args.output.mkdir(parents=True,exist_ok=True)
    if args.routes:
        routed=args.routes.read_bytes()
        results=evaluate(probes,ET.fromstring(routed),edges)
        report['nativeProbeStatuses']=dict(sorted(Counter(r['status'] for r in results).items()))
        report['hashes']['routes']=hashlib.sha256(routed).hexdigest()
        (args.output/'probe-results.json').write_text(json.dumps(results,indent=2)+'\n')
    else:ET.ElementTree(root).write(args.output/'probe-trips.xml',encoding='utf-8',xml_declaration=True)
    for name,data in [('candidates.json',rows),('probes.json',probes),('report.json',report)]:
        (args.output/name).write_text(json.dumps(data,indent=2)+'\n')
    print(json.dumps(report))


if __name__=='__main__':main()
