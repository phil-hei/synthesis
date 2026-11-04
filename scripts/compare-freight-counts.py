#!/usr/bin/env python3
"""Compare completed heavy-vehicle crossings in a partial SUMO pilot with SGV.

Requires explicit reviewed counter-edge mapping and compatible Mo-So observations.
Reports partial-scenario coverage, never a calibration score or scaling proposal.
"""
import argparse
import hashlib
import json
import math
import pathlib
import xml.etree.ElementTree as ET
from collections import Counter


def load_xml(path):
    data = pathlib.Path(path).read_bytes()
    if b'<!DOCTYPE' in data.upper():
        raise ValueError('DTD is not supported')
    return ET.fromstring(data), hashlib.sha256(data).hexdigest()


def compare(routes_path, network_path, tripinfo_path, observations_path, mapping_path, statistics_path, types_path=None):
    routes, routes_hash = load_xml(routes_path)
    network, network_hash = load_xml(network_path)
    tripinfo, tripinfo_hash = load_xml(tripinfo_path)
    statistics, statistics_hash = load_xml(statistics_path)
    teleports = statistics.find('teleports')
    if teleports is None or int(teleports.attrib['total']) != 0:
        raise ValueError('teleports prevent proof of executed station passages')
    observations_data = pathlib.Path(observations_path).read_bytes()
    mapping_data = pathlib.Path(mapping_path).read_bytes()
    observations = json.loads(observations_data)
    mapping = json.loads(mapping_data)
    if mapping.get('networkSha256') != network_hash or not mapping.get('assumption'):
        raise ValueError('mapping must reference the exact network and state its assumption')
    type_definitions, types_hash = load_xml(types_path or routes_path)
    types = {t.attrib['id']: t.attrib.get('vClass', 'passenger') for t in type_definitions.findall('vType')}
    edges = {e.attrib['id']: e for e in network.findall('edge') if e.attrib.get('function') != 'internal'}
    completed = {t.attrib['id'] for t in tripinfo.findall('tripinfo') if float(t.attrib['arrival']) >= 0}
    heavy_crossings, seen, boundary_edges = Counter(), set(), set()
    heavy_completed = 0
    for vehicle in routes.findall('vehicle'):
        vid = vehicle.attrib['id']
        if vid in seen:
            raise ValueError('duplicate vehicle id')
        seen.add(vid)
        if vid not in completed:
            continue
        if vehicle.attrib.get('type') not in types:
            raise ValueError('unknown vehicle type; explicit vClass definition required')
        if types[vehicle.attrib['type']] not in ('truck', 'trailer'):
            continue
        route = vehicle.find('route')
        if route is None or not route.attrib.get('edges'):
            raise ValueError('explicit executed route required')
        route_edges = route.attrib['edges'].split()
        if any(e not in edges for e in route_edges):
            raise ValueError('route edge absent from network')
        boundary_edges.update((route_edges[0], route_edges[-1]))
        heavy_crossings.update(route_edges)  # repeated station passages count repeatedly
        heavy_completed += 1
    if completed - seen:
        raise ValueError('completed vehicles missing from route file')
    mapped = {}
    for station in mapping['stations']:
        sid = station['station']
        if sid in mapped:
            raise ValueError('duplicate station mapping')
        mapped[sid] = station
    rows = []
    used_edges = set()
    for observation in observations['observations']:
        sid = observation['station']
        row = {'station': sid, 'observedSgvCalendarDay': observation['sgv'], 'month': observation['month']}
        station = mapped.get(sid)
        if station is None or station.get('status') != 'reviewed-geometric-match':
            row.update(status='not_comparable', reason=station.get('reason', 'no reviewed mapping') if station else 'no mapping')
            rows.append(row)
            continue
        if observation['month'] != mapping['month'] or observation['period'] != 'Mo-So' or observation['unit'] != 'vehicles/calendar-day':
            raise ValueError('incompatible period or unit')
        count = observation['sgv']
        if type(count) not in (int, float) or not math.isfinite(count) or count <= 0:
            raise ValueError('positive finite SGV observation required')
        if station.get('officialTypeCode') != '01':
            raise ValueError('automatic counter identity unresolved')
        selected = station['edges']
        if not selected or len({e['edgeId'] for e in selected}) != len(selected):
            raise ValueError('distinct directed counter edges required')
        for e in selected:
            if e['edgeId'] not in edges or e['edgeId'] in used_edges:
                raise ValueError('missing or multiply assigned station edge')
            if e['edgeId'] in boundary_edges:
                raise ValueError('counter edge is start or end of a completed heavy route; passage unknown')
            distance = e['distanceMetres']
            if type(distance) not in (int, float) or not math.isfinite(distance) or not 0 <= distance <= 15:
                raise ValueError('counter geometry mismatch')
            used_edges.add(e['edgeId'])
        partial = sum(heavy_crossings[e['edgeId']] for e in selected)
        row.update(status='partial_scenario_comparison', completedHeavyCrossings=partial,
                   partialToObservedRatio=partial / count, mapping=station,
                   interpretation='only modeled completed passages; residual includes missing OD/transit and model assumptions, not calibration error')
        rows.append(row)
    if set(mapped) - {x['station'] for x in observations['observations']}:
        raise ValueError('mapping references unknown station')
    return {'contract': 'partial-freight-count-comparison-v1', 'calibrated': False,
            'completedHeavyVehicles': heavy_completed, 'routesWithoutCompletedTripinfo': len(seen - completed),
            'scope': 'partial OD pilot; incomplete regional demand, modeled accesses/classes/departure profile',
            'assumption': mapping['assumption'], 'counterMapping': mapping,
            'hashes': {'routes': routes_hash, 'network': network_hash, 'tripinfo': tripinfo_hash, 'statistics': statistics_hash,
                       'vehicleTypes': types_hash, 'observations': hashlib.sha256(observations_data).hexdigest(), 'mapping': hashlib.sha256(mapping_data).hexdigest()},
            'observationsSource': observations.get('source'), 'stations': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('routes', 'network', 'tripinfo', 'observations', 'mapping', 'statistics', 'output'):
        parser.add_argument('--' + name, required=True, type=pathlib.Path)
    parser.add_argument('--types', type=pathlib.Path, help='vType definitions when SUMO executed routes omit them')
    args = parser.parse_args()
    result = compare(args.routes, args.network, args.tripinfo, args.observations, args.mapping, args.statistics, args.types)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps({'completedHeavyVehicles': result['completedHeavyVehicles'], 'stations': result['stations']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
