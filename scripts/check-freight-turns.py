#!/usr/bin/env python3
"""Compare simple OSM via-node turn rules with native SUMO connections, by class.

No access certification: via-way, conditional, joined/unmapped and ambiguous
relations stay unresolved. A missing allowed target is never called verified.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
from functools import lru_cache
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def way_id(edge):
    m = re.fullmatch(r'-?(\d+)(?:#.*)?', edge)
    return m[1] if m else None


def permits(element, clazz):
    allow=set(element.get('allow','').split()); deny=set(element.get('disallow','').split())
    return (not allow or clazz in allow or 'all' in allow) and not ({clazz,'all'} & deny)


def inspect(osm, network, routes):
    edges={e.get('id'):e for e in network.findall('edge') if e.get('from') is not None}
    by_way=defaultdict(list)
    for key in edges: by_way[way_id(key)].append(key)
    connections=defaultdict(list)
    for c in network.findall('connection'): connections[c.get('from')].append(c)
    source_relations=osm.findall('relation')
    @lru_cache(maxsize=None)
    def only_targets(from_way,via,clazz):
        allowed=set()
        for rel in source_relations:
            ts={t.get('k'):t.get('v') for t in rel.findall('tag')}
            if not ts.get('type','').startswith('restriction'):continue
            ms=rel.findall('member')
            fs=[m.get('ref') for m in ms if m.get('role')=='from' and m.get('type')=='way']
            vs=[m.get('ref') for m in ms if m.get('role')=='via' and m.get('type')=='node']
            if fs!=[from_way] or vs!=[via]:continue
            if set(ts.get('except','').split(';')) & ({'vehicle','motor_vehicle','hgv','hgv_articulated'} if clazz=='trailer' else {'vehicle','motor_vehicle','hgv'}):continue
            rule=ts.get('restriction:hgv_articulated',ts.get('restriction:hgv',ts.get('restriction'))) if clazz=='trailer' else ts.get('restriction:hgv',ts.get('restriction'))
            if any('conditional' in k for k in ts):return None
            if rule and rule.startswith('only_'):
                allowed.update(m.get('ref') for m in ms if m.get('role')=='to' and m.get('type')=='way')
        return allowed
    results=[]
    for r in source_relations:
        tags={t.get('k'):t.get('v') for t in r.findall('tag')}
        if not tags.get('type','').startswith('restriction'):continue
        members=r.findall('member')
        get=lambda role,kind:[m.get('ref') for m in members if m.get('role')==role and m.get('type')==kind]
        f,t,v=get('from','way'),get('to','way'),get('via','node')
        for clazz in ('truck','trailer'):
            row={'relationId':r.get('id'),'vehicleClass':clazz,'tags':tags,'status':'unresolved','forbiddenConnections':[]}
            results.append(row)
            rule=tags.get('restriction:hgv_articulated',tags.get('restriction:hgv',tags.get('restriction'))) if clazz=='trailer' else tags.get('restriction:hgv',tags.get('restriction'))
            exceptions=set(tags.get('except','').split(';'))
            if exceptions & ({'vehicle','motor_vehicle','hgv','hgv_articulated'} if clazz=='trailer' else {'vehicle','motor_vehicle','hgv'}):
                row['status']='class-exempt';continue
            if rule is None and not any('conditional' in k for k in tags) and set(k for k in tags if k.startswith('restriction:')) <= {'restriction:bicycle'}:
                row['status']='other-mode-only';continue
            if (len(f)!=1 or len(t)!=1 or len(v)!=1 or len(members)!=3 or any('conditional' in k for k in tags)
                or rule not in ('no_left_turn','no_right_turn','no_straight_on','no_u_turn','only_left_turn','only_right_turn','only_straight_on')
                or exceptions-{'','bus','psv','taxi','bicycle','motorcar','motorcycle','emergency','foot'}):
                row['reason']='unsupported-rule-members-or-exception';continue
            targets=only_targets(f[0],v[0],clazz) if rule.startswith('only_') else {t[0]}
            if targets is None:row['reason']='conditional-only-group-unresolved';continue
            incoming=[e for e in by_way[f[0]] if edges[e].get('to')==v[0] and any(permits(l,clazz) for l in edges[e].findall('lane'))]
            target=[e for w in targets for e in by_way[w] if edges[e].get('from')==v[0] and any(permits(l,clazz) for l in edges[e].findall('lane'))]
            if not incoming or not target:
                row['reason']='from-to-or-via-not-mapped-for-class';continue
            if f==t and rule!='no_u_turn':row['reason']='ambiguous-same-way';continue
            checked=0; allowed_target=False
            for start in incoming:
                for c in connections[start]:
                    end=c.get('to')
                    if end not in edges or not permits(c,clazz):continue
                    fl=next((l for l in edges[start].findall('lane') if l.get('index')==c.get('fromLane')),None)
                    tl=next((l for l in edges[end].findall('lane') if l.get('index')==c.get('toLane')),None)
                    if fl is None or tl is None or not permits(fl,clazz) or not permits(tl,clazz):continue
                    checked+=1
                    matches=end in target
                    if rule=='no_u_turn' and f==t:matches=matches and start.startswith('-')!=end.startswith('-')
                    if end in target:allowed_target=True
                    violation=(not matches) if rule.startswith('only_') else matches
                    if violation:row['forbiddenConnections'].append([start,end])
            row['allowedTargetWays']=sorted(targets)
            row['checkedConnections']=checked
            row['status']='transfer-mismatch' if row['forbiddenConnections'] else 'verified-simple-transfer' if checked and (not rule.startswith('only_') or allowed_target) else 'unresolved'
            if row['status']=='unresolved':row['reason']='no-positive-connected-reference'
    indexes=defaultdict(list)
    for r in results:
        for pair in r['forbiddenConnections']: indexes[tuple([r['vehicleClass']]+pair)].append(r['relationId'])
    used=[]
    for vehicle in routes.findall('vehicle'):
        clazz=vehicle.get('type')
        if clazz not in ('truck','trailer'):continue
        route=vehicle.find('route')
        path=route.get('edges','').split() if route is not None else []
        violations=[{'from':a,'to':b,'relationIds':indexes[clazz,a,b]} for a,b in zip(path,path[1:]) if (clazz,a,b) in indexes]
        used.append({'id':vehicle.get('id'),'vehicleClass':clazz,'turnViolations':violations})
    return {'contract':'freight-turn-transfer-audit-v1','relationClassStatuses':dict(sorted(Counter(r['status'] for r in results).items())),
            'sourceRelations':len(results)//2,'routedVehiclesChecked':len(used),'routesWithTurnViolation':sum(bool(r['turnViolations']) for r in used),
            'accessCertified':False,'relations':results,'routes':used}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('osm','network','routes','output'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();result=inspect(*(ET.parse(getattr(a,n)).getroot() for n in ('osm','network','routes')))
    result['sha256']={n:hashlib.sha256(getattr(a,n).read_bytes()).hexdigest() for n in ('osm','network','routes')}
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ('relations','routes','sha256')}))


if __name__=='__main__':main()
