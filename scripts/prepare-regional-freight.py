#!/usr/bin/env python3
"""Prepare additive background freight for the existing platform CSV upload.

Input: {source, referenceDate, periodDays, relations:[{id, from:[lon,lat],
to:[lon,lat], trips, vehicleClass?, beginHour?, endHour?}]}.
Trips describe periodDays (1 for daily OD). Original OD is retained in the report.
Outside OD is clipped geometrically, then mapped to the nearest class-permitted,
directionally reachable pair. This is an approximation, never observed routing.
No company overlap subtraction, volume uplift or missing-transit invention.
"""
import argparse
import csv
import datetime
import hashlib
import heapq
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
import xml.etree.ElementTree as ET

HEADER = 'id;vehicleClass;movement;fromLon;fromLat;toLon;toLat;trips;beginHour;endHour;source;referenceDate;fromEdge;toEdge'


def point(value):
    if not isinstance(value, (list, tuple)) or len(value) != 2 or any(type(x) not in (float, int) or not math.isfinite(x) for x in value) or abs(value[0]) > 180 or abs(value[1]) > 90:
        raise ValueError('finite WGS84 point required')
    return tuple(value)


def clip(a, b, box):
    # Liang-Barsky clipping. Tangencies have no traversed regional length.
    lo, hi = 0., 1.
    for origin, delta, low, high in zip(a, (b[0]-a[0], b[1]-a[1]), box[:2], box[2:]):
        if delta == 0:
            if not low <= origin <= high:
                return None
        else:
            u, v = sorted(((low-origin)/delta, (high-origin)/delta))
            lo, hi = max(lo, u), min(hi, v)
            if hi <= lo:
                return None
    return tuple(max(box[i], min(box[i+2], a[i]+lo*(b[i]-a[i]))) for i in range(2)), tuple(max(box[i], min(box[i+2], a[i]+hi*(b[i]-a[i]))) for i in range(2))


def inside(p, box):
    return box[0] <= p[0] <= box[2] and box[1] <= p[1] <= box[3]


def project(p, shape):
    best = (math.inf, None)
    scale = 111320*math.cos(math.radians(p[1]))
    for a, b in zip(shape, shape[1:]):
        ax, ay = (a[0]-p[0])*scale, (a[1]-p[1])*111320
        dx, dy = (b[0]-a[0])*scale, (b[1]-a[1])*111320
        t = max(0., min(1., -(ax*dx+ay*dy)/(dx*dx+dy*dy))) if dx*dx+dy*dy else 0.
        d = math.hypot(ax+t*dx, ay+t*dy)
        if d < best[0]:
            best = d, (a[0]+t*(b[0]-a[0]), a[1]+t*(b[1]-a[1]))
    return best


class Network:
    def __init__(self, native, geometry, box):
        self.permissions, self.links, self.shapes = {}, defaultdict(list), {}
        # Stream the large native graph; retain only routing permissions and links.
        for _, e in ET.iterparse(native, events=('end',)):
            if e.tag == 'edge':
                if not e.get('id', '').startswith(':'):
                    lanes = e.findall('lane')
                    self.permissions[e.get('id')] = {c for c in ('truck', 'trailer') if any(self.permitted(l, c) for l in lanes)}
                e.clear()
            elif e.tag == 'connection':
                if not e.get('from', '').startswith(':') and not e.get('to', '').startswith(':'):
                    # Exact turns remain duarouter's responsibility. Lane permission
                    # is checked below using the source and destination edge classes.
                    self.links[e.get('from')].append(e.get('to'))
                e.clear()
            elif e.tag not in ('lane', 'param', 'neigh'):
                e.clear()
        for f in geometry['features']:
            eid = f['properties']['edgeId']
            shape = [point(p) for p in f['geometry']['coordinates']]
            # Candidate geometry is clipped into the requested region; original
            # directed edge IDs remain the native router's pins.
            pieces = []
            for a, b in zip(shape, shape[1:]):
                segment = clip(a, b, box)
                if segment and segment[0] != segment[1]:
                    pieces.append(segment)
            if pieces and self.permissions.get(eid):
                self.shapes[eid] = pieces
        self.reach_cache = {}
        if not self.shapes:
            raise ValueError('no heavy-vehicle road intersects the region')

    @staticmethod
    def permitted(lane, clazz):
        allow, deny = lane.get('allow', '').split(), lane.get('disallow', '').split()
        return (not allow or 'all' in allow or clazz in allow) and 'all' not in deny and clazz not in deny

    def reachable(self, start, clazz):
        key = start, clazz
        if key not in self.reach_cache:
            found, todo = {start}, [start]
            while todo:
                for target in self.links.get(todo.pop(), ()):
                    if target not in found and clazz in self.permissions.get(target, ()):
                        found.add(target)
                        todo.append(target)
            # Bounded cache avoids retaining large reachability sets per relation.
            if len(self.reach_cache) >= 16:
                self.reach_cache.pop(next(iter(self.reach_cache)))
            self.reach_cache[key] = found
        return self.reach_cache[key]

    def snap_pair(self, a, b, clazz):
        origins, targets = [], []
        for eid, shape in self.shapes.items():
            if clazz in self.permissions[eid]:
                d, p = min(project(a, segment) for segment in shape); origins.append((d, eid, p))
                d, p = min(project(b, segment) for segment in shape); targets.append((d, eid, p))
        origins.sort(); targets.sort()
        if not origins:
            raise ValueError('no permitted road for '+clazz)
        # Best-first pair search gives the actual minimum sum of displacements,
        # not a fixed top-N shortcut. Connectivity is later verified natively.
        heap, seen = [(origins[0][0]+targets[0][0], 0, 0)], {(0, 0)}
        while heap:
            _, i, j = heapq.heappop(heap)
            f, t = origins[i], targets[j]
            if f[1] != t[1] and f[2] != t[2] and t[1] in self.reachable(f[1], clazz):
                return f, t
            for x, y in ((i+1, j), (i, j+1)):
                if x < len(origins) and y < len(targets) and (x, y) not in seen:
                    seen.add((x, y)); heapq.heappush(heap, (origins[x][0]+targets[y][0], x, y))
        raise ValueError('no directionally connected heavy-vehicle pair')


def prepare(demand, network, box):
    if not isinstance(demand.get('source'), str) or not demand['source'].strip() or any(c in demand['source'] for c in ';\r\n') or len(demand['source']) > 450:
        raise ValueError('source attribution required (max450 characters, no CSV separators)')
    datetime.date.fromisoformat(demand['referenceDate'])
    days = demand.get('periodDays', 1)
    if type(days) not in (int, float) or not math.isfinite(days) or days <= 0:
        raise ValueError('positive periodDays required')
    import re
    ids, rows, audit, skipped = set(), [], [], []
    for r in sorted(demand['relations'], key=lambda x: x['id']):
        rid = r['id']
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,70}', rid) or rid in ids:
            raise ValueError('unique safe OD identifier required')
        ids.add(rid)
        a, b = point(r['from']), point(r['to'])
        value = r['trips']
        if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
            raise ValueError('positive finite trips required')
        clazz = r.get('vehicleClass', 'truck')
        begin, end = r.get('beginHour', 0), r.get('endHour', 24)
        if clazz not in ('truck', 'trailer') or not all(type(v) in (int, float) and math.isfinite(v) for v in (begin, end)) or not 0 <= begin < end <= 24:
            raise ValueError('invalid class or departure hours')
        section = clip(a, b, box) if a != b else None
        if section is None:
            skipped.append(dict(id=rid, periodTrips=value, reason='same-point-or-no-straight-line-regional-intersection'))
            continue
        movement = 'internal' if inside(a, box) and inside(b, box) else 'outbound' if inside(a, box) else 'inbound' if inside(b, box) else 'through'
        f, t = network.snap_pair(*section, clazz)
        rows.append(dict(id='bg_'+rid, vehicleClass=clazz, movement=movement, fromLon=f[2][0], fromLat=f[2][1], toLon=t[2][0], toLat=t[2][1],
                         trips=math.floor(value/days), beginHour=begin, endHour=end,
                         source=demand['source']+'-approximate', referenceDate=demand['referenceDate'], fromEdge=f[1], toEdge=t[1]))
        audit.append(dict(id=rid, originalFrom=a, originalTo=b, originalPeriodTrips=value, dailyExpected=value/days,
                          movement=movement, regionalFrom=section[0], regionalTo=section[1], fromEdge=f[1], toEdge=t[1],
                          fromDisplacementMetres=f[0], toDisplacementMetres=t[0], selectedFrom=f[2], selectedTo=t[2]))
    expected = sum(r['dailyExpected'] for r in audit)
    target = math.floor(expected+.5)
    for i in sorted(range(len(rows)), key=lambda i: (-(audit[i]['dailyExpected']-rows[i]['trips']), rows[i]['id']))[:target-sum(r['trips'] for r in rows)]:
        rows[i]['trips'] += 1
    if target > 200000 or len(rows) > 10000:
        raise ValueError('platform limits: max200000 daily trips and10000 relations')
    by_movement = Counter()
    for r in rows:
        by_movement[r['movement']] += r['trips']
    report = dict(contract='synthesis-regional-background-freight-v1', source=demand['source'], referenceDate=demand['referenceDate'], periodDays=days,
                  region=box, inputRelations=len(ids), dailyTrips=target, byMovement=dict(by_movement), assignments=audit, skipped=skipped,
                  calibrated=False, approximation='straight-line regional clipping; nearest reachable permitted edge pair; uniform input departure windows',
                  routeIntersectionObserved=False, completeRegionalCoverage=False, nativeRoutingVerified=False,
                  companyToursPolicy='additive; overlaps ignored; no subtraction',
                  unmappedPeriodTrips=sum(r['periodTrips'] for r in skipped), zeroRoundedRelations=sum(r['trips']==0 for r in rows))
    return [r for r in rows if r['trips'] > 0], report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('od', 'network', 'geometry', 'output'):
        parser.add_argument('--'+name, required=True, type=Path)
    parser.add_argument('--region', nargs=4, type=float, required=True, metavar=('WEST','SOUTH','EAST','NORTH'))
    args = parser.parse_args()
    box = args.region
    if not all(math.isfinite(v) for v in box) or not -180 <= box[0] < box[2] <= 180 or not -90 <= box[1] < box[3] <= 90:
        parser.error('valid WGS84 rectangle required')
    od = json.loads(args.od.read_text())
    print('Loading native freight network and regional geometry', flush=True)
    network = Network(args.network, json.loads(args.geometry.read_text()), box)
    print('Preparing '+str(len(od['relations']))+' OD relations against '+str(len(network.shapes))+' regional road candidates', flush=True)
    rows, report = prepare(od, network, box)
    report['hashes'] = {}
    for name, path in [('od',args.od),('network',args.network),('geometry',args.geometry)]:
        with path.open('rb') as stream:
            digest = hashlib.sha256()
            for block in iter(lambda: stream.read(1024*1024), b''):
                digest.update(block)
            report['hashes'][name] = digest.hexdigest()
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output/'freight-relations.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=HEADER.split(';'), delimiter=';')
        writer.writeheader(); writer.writerows(rows)
    (args.output/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    (args.output/'original-od.json').write_text(json.dumps(od, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k: report[k] for k in ('contract','dailyTrips','byMovement','completeRegionalCoverage','calibrated')}))


if __name__ == '__main__':
    main()
