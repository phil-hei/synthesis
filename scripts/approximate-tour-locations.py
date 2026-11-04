#!/usr/bin/env python3
"""Resolve missing local tour candidates with labelled approximations, no HTTP calls.

Read a local OSM PBF address-node catalogue. Choose nearest known house number
on the same street/postcode; if no street exists, choose the known postcode
address nearest its address-node centroid. The latter is a locality proxy, not
an assertion about where an unknown address really lies. Existing candidates
are preserved. Optional pyosmium runtime is the existing OSM address toolchain.
"""
import argparse
import importlib.util
import json
import math
import re
from pathlib import Path

spec=importlib.util.spec_from_file_location('nrw',Path(__file__).with_name('prepare-nrw-tour-addresses.py'))
nrw=importlib.util.module_from_spec(spec);spec.loader.exec_module(nrw)


def choose(location, addresses):
    candidates=[a for a in addresses if a['postcode']==location['postalCode']]
    if not candidates:
        return None
    match=re.fullmatch(r'(.*?)\s+(\d+).*',location['street'].strip())
    street=nrw.normal(match[1]) if match else nrw.normal(location['street'])
    same=[a for a in candidates if nrw.normal(a['street'])==street]
    if same and match:
        selected=min(same,key=lambda a:(abs(int(re.match(r'\d+',a['house'])[0])-int(match[2])) if re.match(r'\d+',a['house']) else math.inf,a['house'],a['id']))
        method='nearest-known-house-number-same-street-postcode'
    else:
        lon=sum(a['lon'] for a in candidates)/len(candidates);lat=sum(a['lat'] for a in candidates)/len(candidates)
        selected=min(candidates,key=lambda a:((a['lon']-lon)**2*math.cos(math.radians(lat))**2+(a['lat']-lat)**2,a['id']))
        method='nearest-known-address-to-postcode-address-centroid'
    return dict(lon=str(selected['lon']),lat=str(selected['lat']),display_name=f"{selected['street']} {selected['house']}, {selected['postcode']} (Näherung)",
                osm_type='node',osm_id=selected['id'],licence='OpenStreetMap contributors, ODbL-1.0; approximate-nearest: '+method+'; source address not verified',approximation=method)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--draft',required=True,type=Path);p.add_argument('--index',required=True,type=Path)
    p.add_argument('--osm',nargs='+',required=True,type=Path);p.add_argument('--output',required=True,type=Path)
    args=p.parse_args();draft=json.loads(args.draft.read_text());index=json.loads(args.index.read_text());missing={}
    for t in draft['tours']:
        for s in t['stops']:
            loc=s['location'];query=f"{loc['street']}, {loc['postalCode']} {loc['city']}"
            if not index.get(query):missing[query]=loc
    postcodes={l['postalCode'] for l in missing.values()};addresses=[]
    import osmium
    class Reader(osmium.SimpleHandler):
        def node(self,o):
            if o.tags.get('addr:postcode') in postcodes and o.tags.get('addr:street') and o.tags.get('addr:housenumber'):
                addresses.append(dict(postcode=o.tags['addr:postcode'],street=o.tags['addr:street'],house=o.tags['addr:housenumber'],lon=o.lon,lat=o.lat,id=str(o.id)))
    reader=Reader()
    for path in args.osm:reader.apply_file(str(path))
    decisions=[]
    for query,location in missing.items():
        selected=choose(location,addresses)
        if selected:index[query]=[selected];decisions.append(dict(query=query,candidate=selected))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(index,ensure_ascii=False,indent=2)+'\n')
    report=dict(contract='synthesis-approximate-tour-locations-v1',newlyLocated=len(decisions),remainingMissing=len(missing)-len(decisions),decisions=decisions,sourceFiles=[str(f) for f in args.osm],policy='Only missing points; no remote geocoding; provenance labels approximations',truckEntranceVerified=False)
    args.output.with_suffix('.report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({k:report[k] for k in ['newlyLocated','remainingMissing','truckEntranceVerified']}))

if __name__=='__main__':main()
