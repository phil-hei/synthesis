#!/usr/bin/env python3
"""Convert complete Toll Collect GeoJSON pages to spatial OD for regional preparation.

Gemeinde bounding-box centers are explicit spatial model representatives, not
shipment locations. Published monthly quantities are conserved by the existing
normalizer; duplicate perspectives are never added. Intramunicipal same-point OD
is retained for reporting, not silently turned into a fictitious local route.
"""
import argparse
import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location('toll', Path(__file__).with_name('prepare-toll-freight.py'))
toll = importlib.util.module_from_spec(spec)
spec.loader.exec_module(toll)


def convert(paths, month):
    records, provenance = toll.normalize(paths, month)
    centers = {}
    for path in paths:
        for f in json.loads(Path(path).read_text())['features']:
            a = f.get('properties', f.get('attributes'))
            geometry = f.get('geometry')
            if geometry is None:
                continue
            if geometry['type'] not in ('Polygon','MultiPolygon'):
                raise ValueError('WGS84 municipality polygon required')
            ags = a['ags_ziel'] if a['richtung'] == 0 else a['ags_start']
            points = []
            def collect(part):
                if len(part) >= 2 and isinstance(part[0], (int,float)):
                    points.append(tuple(part[:2]))
                else:
                    for child in part: collect(child)
            collect(geometry['coordinates'])
            if not points or any(abs(p[0])>180 or abs(p[1])>90 for p in points):
                raise ValueError('geometry must be geographic WGS84')
            # Generalized polygons from different queries may differ slightly;
            # use deterministic first source rather than claiming exact centroids.
            centers.setdefault(ags, [(min(p[0] for p in points)+max(p[0] for p in points))/2,
                                     (min(p[1] for p in points)+max(p[1] for p in points))/2])
    relations, missing = [], []
    for (a,b), observation in sorted(records.items()):
        if a not in centers or b not in centers:
            missing.append(dict(origin=a,destination=b,monthlyTrips=observation['anzahl_befahrungen']))
            continue
        relations.append(dict(id='toll_'+a+'_'+b, **{'from':centers[a],'to':centers[b]},trips=observation['anzahl_befahrungen'],vehicleClass='truck'))
    source = dict(source='BALM-TollCollect-'+month+'-municipality-proxies-assumed-truck', referenceDate=month+'-01',periodDays=provenance['calendarDays'],relations=relations)
    provenance.update(spatialAssumption='municipality polygon bounding-box center; assumed truck class; all-day uniform; no weekday adjustment',
                      spatialRelations=len(relations), missingMunicipalGeometry=missing, completeRegionalCoverage=False,
                      throughCoverage='Only OD in the supplied municipality queries; trips with neither endpoint in queried municipalities may be absent')
    return source, provenance


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('inputs',nargs='+',type=Path);p.add_argument('--month',required=True);p.add_argument('--output',required=True,type=Path)
    args=p.parse_args()
    source,provenance=convert(args.inputs,args.month)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(source,ensure_ascii=False,indent=2)+'\n')
    args.output.with_suffix('.report.json').write_text(json.dumps(provenance,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:provenance[k] for k in ['uniqueRelations','monthlyObservedEntries','spatialRelations','completeRegionalCoverage']}))

if __name__=='__main__':main()
