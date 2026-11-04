#!/usr/bin/env python3
"""Explicit operating profiles, dimension-aware pilot networks and route audits.

Only proven numeric violations are closed in the pilot network. Unknown rules
remain visible; a routable result never certifies access or a physical entrance.
"""
import argparse
from collections import Counter
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import xml.sax
from xml.sax.saxutils import XMLGenerator
from xml.sax.xmlreader import AttributesImpl
from zoneinfo import ZoneInfo

import freight_restrictions as rules


def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()


def load_osm(path):
    nodes,ways,relations={}, {}, []
    for _,element in ET.iterparse(path,events=('end',)):
        if element.tag not in ('node','way','relation'):continue
        tags={t.get('k'):t.get('v') for t in element.findall('tag')}
        if element.tag=='node' and tags:
            relevant={k:v for k,v in tags.items() if k.startswith(('max','hgv','access','vehicle','motor_vehicle','barrier'))}
            if relevant:nodes[element.get('id')]=relevant
        elif element.tag=='way':
            ways[element.get('id')]={'tags':tags,'nodes':[n.get('ref') for n in element.findall('nd') if n.get('ref') in nodes]}
        elif element.tag=='relation':
            relations.append({'id':element.get('id'),'tags':tags,
                              'ways':{m.get('ref') for m in element.findall('member') if m.get('type')=='way'}})
        element.clear()
    return nodes,ways,relations


def edge_identity(identifier):
    match=re.fullmatch(r'-?(\d+)(?:#.*)?',identifier)
    return (match.group(1),identifier.startswith('-')) if match else (None,False)


def edge_events(identifier,profile,source,window=None):
    nodes,ways,_=source
    way,backward=edge_identity(identifier)
    if way not in ways:
        return [{'status':'unknown','reason':'source-way-unmapped','key':'source','value':identifier}]
    result=[{**e,'sourceType':'way','sourceId':way} for e in rules.evaluate_tags(ways[way]['tags'],profile,backward,window)]
    for node in ways[way]['nodes']:
        if any('forward' in key.split(':') or 'backward' in key.split(':') for key in nodes[node]):
            result.append({'status':'unknown','reason':'node-direction-not-resolved','sourceType':'node','sourceId':node})
        else:
            result.extend({**e,'sourceType':'node','sourceId':node} for e in rules.evaluate_tags(nodes[node],profile,False,window))
        if nodes[node].get('barrier') not in (None,'no'):
            result.append({'status':'unknown','reason':'physical-node-control-unconfirmed','key':'barrier',
                           'value':nodes[node]['barrier'],'sourceType':'node','sourceId':node})
    return result


def block_lane(attributes,classes):
    attributes=dict(attributes)
    if 'allow' in attributes:
        allowed=set(attributes['allow'].split())
        if 'all' in allowed:raise ValueError('explicit allow=all unsupported')
        allowed-=classes
        if allowed:attributes['allow']=' '.join(sorted(allowed))
        else:attributes.pop('allow');attributes['disallow']='all'
    else:attributes['disallow']=' '.join(sorted(set(attributes.get('disallow','').split())|classes))
    return attributes


def profile_network(network,output,profiles,source):
    if output.exists():raise ValueError('fresh pilot network path required')
    changed={clazz:set() for clazz in profiles}; cache={};violations={}
    class Writer(XMLGenerator):
        def __init__(self,stream):
            super().__init__(stream,encoding='utf-8',short_empty_elements=True)
            self.edge=None;self.block=set()
        def startElement(self,name,attrs):
            if name=='edge':
                self.edge=attrs.get('id');way,backward=edge_identity(self.edge or '')
                self.block=set()
                if attrs.get('function')!='internal':
                    for clazz,profile in profiles.items():
                        key=(way,backward,clazz)
                        if key not in cache:
                            cache[key]=[e for e in edge_events(self.edge,profile,source) if e['status']=='violation']
                        if cache[key]:
                            self.block.add(clazz)
                            violations[self.edge+':'+clazz]=cache[key]
            if name=='lane' and self.block:
                attrs=AttributesImpl(block_lane(attrs,self.block))
                for clazz in self.block:changed[clazz].add(self.edge)
            super().startElement(name,attrs)
        def endElement(self,name):
            super().endElement(name)
            if name=='edge':self.edge=None;self.block=set()
    temporary=output.with_suffix('.part')
    parser=xml.sax.make_parser();parser.setFeature(xml.sax.handler.feature_external_ges,False)
    try:
        with temporary.open('wb') as stream:
            parser.setContentHandler(Writer(stream));parser.parse(str(network))
        temporary.replace(output)
    except Exception:
        temporary.unlink(missing_ok=True);raise
    return {'blockedSourceEdgesByClass':{c:sorted(v) for c,v in changed.items()},'evidence':violations,
            'conditionalClosuresApplied':False,'unknownRulesClosed':False}


def prepare_trips(path,output,profiles):
    root=ET.parse(path).getroot()
    for vehicle_type in root.findall('vType'):
        profile=profiles[vehicle_type.get('id')]
        for key,field in [('length','lengthM'),('width','widthM'),('height','heightM'),('mass','actualMassKg')]:
            vehicle_type.set(key,str(profile[field]))
        vehicle_type.set('maxSpeed',str(profile['maxSpeedKmh']/3.6))
    ET.ElementTree(root).write(output,encoding='utf-8',xml_declaration=True)


def audit(routes,probes,profiles,source,departure):
    vehicles={v.get('id'):v for v in routes.findall('vehicle')}
    if len(vehicles)!=len(routes.findall('vehicle')) or set(vehicles)-{p['id'] for p in probes}:
        raise ValueError('duplicate or unknown routed probe')
    result=[]
    for probe in probes:
        row={**probe,'dimensionAccessCertified':False,'physicalEntranceVerified':False}
        vehicle=vehicles.get(probe['id'])
        if vehicle is None:
            result.append({**row,'status':'no-native-route','events':[]});continue
        route=vehicle.find('route');path=route.get('edges','').split() if route is not None else []
        if not path or path[0]!=probe['fromEdge'] or path[-1]!=probe['toEdge'] or vehicle.get('type')!=probe['vehicleClass']:
            raise ValueError('native route endpoints/type mismatch')
        exits=route.get('exitTimes','').split()
        if exits and len(exits)!=len(path):raise ValueError('exit time count mismatch')
        previous=float(vehicle.get('depart','0'));events=[]
        if not math_isfinite_nonnegative(previous):raise ValueError('invalid native departure')
        for index,edge in enumerate(path):
            window=None
            if exits:
                end=float(exits[index])
                if not math_isfinite_nonnegative(end) or end < previous:raise ValueError('invalid native exit times')
                window=(departure+dt.timedelta(seconds=previous),departure+dt.timedelta(seconds=end))
                previous=end
            events.extend({**e,'edgeId':edge} for e in edge_events(edge,profiles[probe['vehicleClass']],source,window))
        used_ways={edge_identity(e)[0] for e in path}
        relation_ids=[r['id'] for r in source[2] if r['ways'] & used_ways]
        # Native import warnings prevent certifying even simple source turn relations here.
        if relation_ids:
            events.append({'status':'unknown','reason':'turn-relation-transfer-not-certified','sourceRelationIds':relation_ids})
        counts=Counter(e['status'] for e in events)
        row.update(status='explicit-violation' if counts['violation'] else 'review-required' if counts['unknown'] or counts['review'] else 'no-explicit-violation-found',
                   events=events,eventCounts=dict(sorted(counts.items())),predictedEdgeTimesAvailable=bool(exits))
        result.append(row)
    return result


def math_isfinite_nonnegative(value):
    import math
    return math.isfinite(value) and value>=0


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=['prepare','audit'])
    for name in ('osm','profiles','catalog','output'):parser.add_argument('--'+name,required=True,type=Path)
    for name in ('network','trips','routes','probes'):parser.add_argument('--'+name,type=Path)
    parser.add_argument('--departure',default=None,help='explicit ISO timestamp with correct Europe/Berlin offset; audit only')
    args=parser.parse_args()
    profiles=rules.resolve_profiles(json.loads(args.profiles.read_text()),json.loads(args.catalog.read_text()))
    source=load_osm(args.osm)
    args.output.mkdir(parents=True,exist_ok=True)
    hashes={name:digest(getattr(args,name)) for name in ('osm','profiles','catalog')}
    hashes.update(script=digest(Path(__file__)),evaluator=digest(Path(__file__).with_name('freight_restrictions.py')))
    report={'contract':'freight-profile-audit-v1','profiles':profiles,'hashes':hashes,
            'operatingProfilesObserved':False,'simulationReady':False,'sourceRuleCoverageVerified':False}
    if args.mode=='prepare':
        if not args.network or not args.trips:parser.error('prepare requires network and trips')
        report['hashes'].update(network=digest(args.network),trips=digest(args.trips))
        closures=profile_network(args.network,args.output/'network-profile.net.xml',profiles,source)
        prepare_trips(args.trips,args.output/'profile-trips.xml',profiles)
        (args.output/'closures.json').write_text(json.dumps(closures,indent=2)+'\n')
        report['blockedSourceEdgeCounts']={c:len(v) for c,v in closures['blockedSourceEdgesByClass'].items()}
        report['hashes']['outputNetwork']=digest(args.output/'network-profile.net.xml')
    else:
        if not args.routes or not args.probes or not args.departure:parser.error('audit requires routes, probes and departure')
        departure=dt.datetime.fromisoformat(args.departure)
        if departure.tzinfo is None or departure.utcoffset()!=departure.astimezone(ZoneInfo('Europe/Berlin')).utcoffset():
            parser.error('explicit correct Berlin timezone offset required')
        departure=departure.astimezone(ZoneInfo('Europe/Berlin'))
        report['hashes'].update(routes=digest(args.routes),probes=digest(args.probes))
        report['departureAssumption']=departure.isoformat()
        rows=audit(ET.parse(args.routes).getroot(),json.loads(args.probes.read_text()),profiles,source,departure)
        (args.output/'route-audit.json').write_text(json.dumps(rows,indent=2)+'\n')
        report['routeStatuses']=dict(sorted(Counter(row['status'] for row in rows).items()))
        report['routeCount']=len(rows)
        report['routesWithNumericViolation']=sum(any(e.get('reason')=='explicit-numeric-comparison' and e['status']=='violation' for e in row['events']) for row in rows)
        report['unresolvedReasonsByEvent']=dict(sorted(Counter(e['reason'] for row in rows for e in row['events'] if e['status'] in ('unknown','review')).items()))
    (args.output/(args.mode+'-report.json')).write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('profiles','hashes')}))


if __name__=='__main__':main()
