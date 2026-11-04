#!/usr/bin/env python3
"""Audit a regional Overture Places extract; never infer trips or activate demand.

Input is a JSON array of the projected Places records, including id, names,
taxonomy (or legacy categories), GeoJSON Point geometry, operating_status,
confidence and sources. Source bboxes can be rounded outwards and are never
substituted for exact geometry coordinates.
Category selection is an explicit modelling choice, not observed freight activity.
This offline inspector uses only the Python standard library.
"""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import math
from pathlib import Path


def validate_bbox(bbox):
    if (len(bbox) != 4 or any(type(v) not in (int, float) or not math.isfinite(v) for v in bbox)
            or not -180 <= bbox[0] < bbox[2] <= 180
            or not -90 <= bbox[1] < bbox[3] <= 90):
        raise ValueError('bbox must be west south east north in WGS84, without antimeridian crossing')


def inspect(records, bbox, selected_categories):
    validate_bbox(bbox)
    if not isinstance(records, list):
        raise ValueError('expected a JSON array of Places records')
    seen, sites = set(), []
    categories, statuses, rejected = Counter(), Counter(), Counter()
    for row in records:
        identifier = row.get('id')
        if not isinstance(identifier, str) or not identifier or identifier in seen:
            raise ValueError('every source record must have a unique, non-empty id')
        seen.add(identifier)
        geometry = row.get('geometry') or {}
        if geometry.get('type') != 'Point':
            rejected['notPointGeometry'] += 1
            continue
        values = geometry.get('coordinates')
        if (not isinstance(values, list) or len(values) != 2
                or any(type(v) not in (int, float) or not math.isfinite(v) for v in values)):
            rejected['invalidCoordinates'] += 1
            continue
        x, y = values
        if not (bbox[0] <= x <= bbox[2] and bbox[1] <= y <= bbox[3]):
            rejected['outsideBbox'] += 1
            continue
        taxonomy = row.get('taxonomy') or row.get('categories') or {}
        category = taxonomy.get('primary')
        if not isinstance(category, str) or not category:
            rejected['missingPrimaryCategory'] += 1
            continue
        categories[category] += 1
        status = row.get('operating_status') or 'unknown'
        statuses[status] += 1
        if category not in selected_categories:
            rejected['categoryNotSelected'] += 1
            continue
        # Unknown status remains visible as a review case; closed records are not candidates.
        if status in ('permanently_closed', 'temporarily_closed'):
            rejected['closed'] += 1
            continue
        sources = row.get('sources')
        if not isinstance(sources, list) or not sources:
            rejected['missingProvenance'] += 1
            continue
        sites.append({
            'id': 'overture:' + identifier,
            'sourceId': identifier,
            'name': (row.get('names') or {}).get('primary'),
            'category': category,
            'sourceTaxonomy': taxonomy,
            'point': [x, y],
            'operatingStatus': status,
            'confidence': row.get('confidence'),
            'sources': sources,
            'reviewRequired': True,
            'truckAccessVerified': False,
        })
    sites.sort(key=lambda s: s['id'])
    report = {
        'contract': 'freight-site-inventory-v1',
        'bbox': bbox,
        'inputRecords': len(records),
        'candidateSites': len(sites),
        'rejectedRecords': dict(sorted(rejected.items())),
        'primaryCategoryCounts': dict(sorted(categories.items())),
        'operatingStatusCounts': dict(sorted(statuses.items())),
        'selectedPrimaryCategories': sorted(selected_categories),
        'selectionMethod': 'explicit primary-category allowlist; no inferred hierarchy or keyword matches',
        'coordinateMethod': 'exact source Point geometry; bbox is used for source query only',
        'simulationReady': False,
        'freightActivityObserved': False,
        'osmComparisonPerformed': False,
        'limitations': ['bbox is not a municipality boundary',
                        'sites are source records, not deduplicated establishments',
                        'no truck access, shipment quantities or generation rates inferred',
                        'unknown operating status requires review',
                        'source confidence is not a freight probability'],
    }
    assert len(sites) + sum(rejected.values()) == len(records)
    return sites, report


def assign_municipalities(sites, collection):
    """Reuse the pilot's polygon/hole test; never assign ambiguous intersections."""
    spec = importlib.util.spec_from_file_location('access_mapping', Path(__file__).with_name('expand-toll-access-mapping.py'))
    mapping = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mapping)
    features = collection.get('features', [])
    polygons = {}
    for feature in features:
        ags = feature['properties']['ags_ziel']
        if not isinstance(ags, str) or len(ags) != 8 or not ags.isdigit() or ags in polygons:
            raise ValueError('unique 8-digit AGS municipality identifiers required')
        geometry = feature['geometry']
        if geometry['type'] not in ('Polygon', 'MultiPolygon'):
            raise ValueError('municipality polygons required')
        polygons[ags] = geometry
    if not polygons:
        raise ValueError('empty municipality collection')
    counts = Counter()
    for site in sites:
        matches = sorted(ags for ags, polygon in polygons.items() if mapping.in_geometry(site['point'], polygon))
        site['municipalityCandidates'] = matches
        site['municipalityAgs'] = matches[0] if len(matches) == 1 else None
        counts[matches[0] if len(matches) == 1 else 'ambiguous' if matches else 'unassigned'] += 1
    return dict(sorted(counts.items()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--release', required=True, help='pinned source release, not latest')
    parser.add_argument('--bbox', type=float, nargs=4, required=True)
    parser.add_argument('--categories', type=Path, required=True, help='JSON array of explicitly selected primary categories; [] inventories only')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--municipalities', type=Path, help='optional pilot municipality GeoJSON with ags_ziel; unassigned/ambiguous sites remain explicit')
    args = parser.parse_args()
    raw = args.input.read_bytes()
    category_bytes = args.categories.read_bytes()
    selected = json.loads(category_bytes)
    if not isinstance(selected, list) or any(not isinstance(c, str) or not c for c in selected):
        parser.error('categories must be a JSON array of non-empty strings')
    sites, report = inspect(json.loads(raw), args.bbox, set(selected))
    report['provenance'] = {
        'release': args.release,
        'sourceUrl': 'https://overturemaps-us-west-2.s3.us-west-2.amazonaws.com/release/' + args.release + '/theme=places/type=place/',
        'inputSha256': hashlib.sha256(raw).hexdigest(),
        'categoryPolicySha256': hashlib.sha256(category_bytes).hexdigest(),
        'inspectorSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'licensing': 'Per-record sources retained; review upstream attribution before redistribution',
    }
    if args.municipalities:
        municipalities = args.municipalities.read_bytes()
        report['municipalityAssignment'] = assign_municipalities(sites, json.loads(municipalities))
        report['provenance']['municipalitiesSha256'] = hashlib.sha256(municipalities).hexdigest()
        report['provenance']['polygonMatcherSha256'] = hashlib.sha256(Path(__file__).with_name('expand-toll-access-mapping.py').read_bytes()).hexdigest()
    geo = {'type': 'FeatureCollection', 'features': [
        {'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': s['point']},
         'properties': {k: v for k, v in s.items() if k != 'point'}} for s in sites]}
    args.output.mkdir(parents=True, exist_ok=True)
    for name, content in [('sites.geojson', geo), ('report.json', report)]:
        (args.output / name).write_text(json.dumps(content, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    print(json.dumps({'inputRecords': len(json.loads(raw)), 'candidateSites': len(sites), 'output': str(args.output)}))


if __name__ == '__main__':
    main()
