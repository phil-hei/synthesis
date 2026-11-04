#!/usr/bin/env python3
"""Review candidates between freight-site inventories; never merge or infer trips."""
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import unicodedata


GROUPS = {
    'warehouse': 'logistics', 'freight_and_cargo_service': 'logistics',
    'freight_forwarding_agency': 'logistics', 'motor_freight_trucking': 'logistics',
    'distribution_service': 'logistics', 'manufacturer': 'production',
    'industrial_equipment_manufacturer': 'production', 'wholesaler': 'wholesale',
    'wholesale_grocer': 'wholesale', 'warehouse_club_store': 'wholesale',
    'grocery_store': 'food-retail', 'furniture_store': 'home-building-retail',
    'hardware_store': 'home-building-retail', 'building_supply_store': 'home-building-retail',
}


def normalized_name(name):
    return ''.join(c for c in unicodedata.normalize('NFKC', name or '').casefold() if c.isalnum())


def distance(a, b):
    lon1, lat1, lon2, lat2 = map(math.radians, [*a, *b])
    h = math.sin((lat2-lat1)/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin((lon2-lon1)/2)**2
    return 6371000 * 2 * math.asin(math.sqrt(min(1, h)))


def read_sites(collection):
    result, seen = [], set()
    for feature in collection['features']:
        geometry, properties = feature['geometry'], feature['properties']
        identifier = properties['id']
        if not isinstance(identifier, str) or not identifier or identifier in seen:
            raise ValueError('unique non-empty site IDs required')
        seen.add(identifier)
        point = geometry.get('coordinates')
        if (geometry.get('type') != 'Point' or not isinstance(point, list) or len(point) != 2
                or any(type(v) not in (int, float) or not math.isfinite(v) for v in point)
                or not -180 <= point[0] <= 180 or not -90 <= point[1] <= 90):
            raise ValueError('finite WGS84 Point geometry required')
        result.append({'id': identifier, 'name': properties.get('name'), 'point': point,
                       'category': properties.get('category'),
                       'group': GROUPS.get(properties.get('category')),
                       'normalizedName': normalized_name(properties.get('name'))})
    return sorted(result, key=lambda s: s['id'])


def nearby_pairs(left, right, metres, within=False):
    if not math.isfinite(metres) or not 0 < metres <= 500:
        raise ValueError('review distance must be positive and at most 500 m')
    if not left or not right:
        return []
    all_points = [s['point'] for s in left + right]
    if (any(abs(p[1]) > 75 for p in all_points)
            or max(p[0] for p in all_points)-min(p[0] for p in all_points) > 2
            or max(p[1] for p in all_points)-min(p[1] for p in all_points) > 2):
        raise ValueError('regional spatial index requires a bbox at most 2 degrees wide/high and latitudes up to 75 degrees')
    latitude = sum(s['point'][1] for s in left + right) / (len(left)+len(right))
    scale_x = 111320 * math.cos(math.radians(latitude))
    def cell(site):
        return (math.floor(site['point'][0]*scale_x/metres), math.floor(site['point'][1]*111320/metres))
    index = defaultdict(list)
    for site in right:
        index[cell(site)].append(site)
    pairs = []
    # Two cells also cover the small variation of longitude scale within this regional bbox.
    for a in left:
        cx, cy = cell(a)
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                for b in index.get((cx+dx, cy+dy), []):
                    if within and a['id'] >= b['id']:
                        continue
                    same_name = bool(a['normalizedName']) and a['normalizedName'] == b['normalizedName']
                    same_group = a['group'] is not None and a['group'] == b['group']
                    if not same_name and (within or not same_group):
                        continue
                    d = distance(a['point'], b['point'])
                    if d > metres or (not same_name and d > 25):
                        continue
                    pairs.append({'leftId': a['id'], 'rightId': b['id'], 'distanceMetres': round(d, 3),
                                  'sameNormalizedName': same_name, 'sameCategoryGroup': same_group,
                                  'classification': 'same-name-nearby' if same_name else 'proximity-only',
                                  'reviewRequired': True})
    return sorted(pairs, key=lambda p: (p['leftId'], p['rightId']))


def compare(left, right):
    cross = nearby_pairs(left, right, 75)
    duplicates = {'left': nearby_pairs(left, left, 50, True),
                  'right': nearby_pairs(right, right, 50, True)}
    report = {'contract': 'freight-site-review-v1', 'leftSites': len(left), 'rightSites': len(right),
              'crossSourcePairCounts': dict(sorted(Counter(p['classification'] for p in cross).items())),
              'leftSitesWithReviewCandidate': len({p['leftId'] for p in cross}),
              'rightSitesWithReviewCandidate': len({p['rightId'] for p in cross}),
              'withinSourcePossibleDuplicatePairs': {k: len(v) for k, v in duplicates.items()},
              'automaticMerges': 0, 'coverageImprovementProven': False, 'simulationReady': False,
              'policy': {'crossSourceSameNameMetres': 75, 'crossSourceProximityOnlyMetres': 25,
                         'withinSourceSameNameMetres': 50,
                         'nameNormalization': 'Unicode NFKC, casefold, alphanumeric characters only'},
              'limitations': ['review pairs are not confirmed establishment identities',
                              'different source dates and classification scopes',
                              'area representative points may differ from mapped entrances',
                              'unmatched records do not prove missing real-world establishments',
                              'no trip quantities, generation weights or truck accesses inferred']}
    return cross, duplicates, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--left', required=True, type=Path)
    parser.add_argument('--right', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--municipalities', type=Path, help='restrict both inventories to unique membership in the same pilot polygons')
    args = parser.parse_args()
    raw_left, raw_right = args.left.read_bytes(), args.right.read_bytes()
    collections = [json.loads(raw_left), json.loads(raw_right)]
    left, right = [read_sites(collection) for collection in collections]
    input_counts = {'left':len(left),'right':len(right)}
    scope = None
    if args.municipalities:
        spec = importlib.util.spec_from_file_location('site_inspector', Path(__file__).with_name('inspect-freight-sites.py'))
        inspector = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(inspector)
        polygons = json.loads(args.municipalities.read_text())
        scope = {name:inspector.assign_municipalities(sites,polygons) for name,sites in [('left',left),('right',right)]}
        left=[s for s in left if s['municipalityAgs'] is not None]
        right=[s for s in right if s['municipalityAgs'] is not None]
    cross, duplicates, report = compare(left, right)
    report['hashes'] = {'left': hashlib.sha256(raw_left).hexdigest(), 'right': hashlib.sha256(raw_right).hexdigest(),
                        'script': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    report['inputSitesBeforeSpatialScope'] = input_counts
    if scope is not None:
        report['municipalityAssignmentsBeforeFilter'] = scope
        report['hashes']['municipalities'] = hashlib.sha256(args.municipalities.read_bytes()).hexdigest()
        report['hashes']['scopeInspector'] = hashlib.sha256(Path(__file__).with_name('inspect-freight-sites.py').read_bytes()).hexdigest()
        report['hashes']['polygonMatcher'] = hashlib.sha256(Path(__file__).with_name('expand-toll-access-mapping.py').read_bytes()).hexdigest()
    args.output.mkdir(parents=True, exist_ok=True)
    for name, data in [('report.json', report), ('cross-source-pairs.json', cross), ('within-source-pairs.json', duplicates)]:
        (args.output/name).write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False)+'\n')
    for name,collection,sites in zip(('left','right'),collections,(left,right)):
        selected={s['id'] for s in sites}
        scoped={'type':'FeatureCollection','features':[f for f in collection['features'] if f['properties']['id'] in selected]}
        (args.output/(name+'-scoped.geojson')).write_text(json.dumps(scoped,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
