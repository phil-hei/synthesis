#!/usr/bin/env python3
"""Bounded background-only count fitting with explicit period/class/network checks.

Consumes prepared freight-relations.csv and a native-route incidence JSON:
{networkSha256, period:{month,dayType,vehicleClass}, completeBackground,
 stations:[{id, observed, fixedCompanyCrossings, split:'calibration'|'holdout',
            period:{...}, incidence:{relationId: passagesPerTrip}}]}.
Company contributions remain fixed; only background volumes are changed.
A fit is not proof of complete regional demand or of out-of-sample validity.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path


def fit(rows, data, minimum=.25, maximum=4., iterations=500):
    if not 0 <= minimum <= 1 <= maximum or not math.isfinite(maximum):
        raise ValueError('finite bounds containing factor1 required')
    period=data['period']
    if set(period) != {'month','dayType','vehicleClass'} or period['vehicleClass'] not in ('truck','trailer','SGV') or period['dayType'] not in ('Mo-So','Mo-Fr','Tuesday'):
        raise ValueError('explicit month/dayType/vehicleClass required')
    if not isinstance(data.get('networkSha256'),str) or len(data['networkSha256']) != 64:
        raise ValueError('exact native network hash required')
    if any(r.get('referenceDate', period['month'])[:7] != period['month'] for r in rows):
        raise ValueError('background reference month does not match observations')
    if period['vehicleClass'] != 'SGV' and any(r.get('vehicleClass', period['vehicleClass']) != period['vehicleClass'] for r in rows):
        raise ValueError('background vehicle class does not match observations')
    names=[r['id'] for r in rows]
    if len(names)!=len(set(names)):
        raise ValueError('duplicate relation ID')
    quantities=[int(r['trips']) for r in rows]
    if any(n<=0 for n in quantities):raise ValueError('positive background quantities required')
    stations=data['stations'];seen=set()
    for s in stations:
        if s['id'] in seen or s.get('period') != period or s['split'] not in ('calibration','holdout'):
            raise ValueError('distinct stations, matching observation period and valid split required')
        seen.add(s['id'])
        if not set(s['incidence']).issubset(names):raise ValueError('incidence references unknown relation')
        values=[s['observed'],s.get('fixedCompanyCrossings',0),*s['incidence'].values()]
        if any(type(x) not in (int,float) or not math.isfinite(x) or x<0 for x in values):raise ValueError('nonnegative finite observations/incidence required')
        if s.get('fixedCompanyCrossings',0)>s['observed']:
            raise ValueError('fixed company contribution already exceeds observed total; background cannot subtract company tours')
    training=[s for s in stations if s['split']=='calibration']
    holdout=[s for s in stations if s['split']=='holdout']
    if not training or not holdout:raise ValueError('separate calibration and holdout stations required')
    a=[[s['incidence'].get(n,0)*q for n,q in zip(names,quantities)] for s in training]
    target=[s['observed']-s.get('fixedCompanyCrossings',0) for s in training]
    factors=[1.]*len(rows);predicted=[sum(row) for row in a]
    for step in range(iterations):
        change=0.
        for j in range(len(rows)):
            denominator=sum(row[j]**2 for row in a)
            if not denominator:continue
            gradient=sum(row[j]*(pred-y) for row,pred,y in zip(a,predicted,target))
            value=max(minimum,min(maximum,factors[j]-gradient/denominator))
            delta=value-factors[j];change=max(change,abs(delta));factors[j]=value
            for i in range(len(a)):predicted[i]+=a[i][j]*delta
        if change<1e-8:break
    expected=[q*f for q,f in zip(quantities,factors)];rounded=[math.floor(x) for x in expected]
    extra=math.floor(sum(expected)+.5)-sum(rounded)
    for j in sorted(range(len(rows)),key=lambda j:(-(expected[j]-rounded[j]),names[j]))[:extra]:rounded[j]+=1
    if sum(rounded)>200000:raise ValueError('fitted demand exceeds platform daily limit')
    result=[]
    for row,n in zip(rows,rounded):
        if n:result.append(dict(row,trips=n))
    comparisons=[]
    for s in stations:
        before=s.get('fixedCompanyCrossings',0)+sum(s['incidence'].get(n,0)*q for n,q in zip(names,quantities))
        after=s.get('fixedCompanyCrossings',0)+sum(s['incidence'].get(n,0)*q for n,q in zip(names,rounded))
        comparisons.append(dict(station=s['id'],split=s['split'],observed=s['observed'],before=before,after=after,
                                beforeAbsoluteError=abs(before-s['observed']),afterAbsoluteError=abs(after-s['observed'])))
    independent=[s for s in comparisons if s['split']=='holdout']
    improves=sum(s['afterAbsoluteError'] for s in independent)<sum(s['beforeAbsoluteError'] for s in independent)
    report=dict(contract='synthesis-background-count-fit-v1',status='model-fit',period=period,networkSha256=data['networkSha256'],
                factors=dict(zip(names,factors)),minimumFactor=minimum,maximumFactor=maximum,iterations=step+1,
                originalBackgroundTrips=sum(quantities),fittedBackgroundTrips=sum(rounded),stations=comparisons,
                holdoutImproves=improves,completeBackground=data.get('completeBackground',False),
                empiricallyValidated=False, nativeRevalidationRequired=True,
                companyToursPolicy='fixed additive contributions; never rescaled or deduplicated',
                limitation='Linear native-route incidence fit; route changes and congestion require a new native run and renewed holdout evaluation')
    return result,report


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('relations','incidence','network','output'):p.add_argument('--'+name,required=True,type=Path)
    args=p.parse_args();data=json.loads(args.incidence.read_text())
    digest=hashlib.sha256()
    with args.network.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    if digest.hexdigest()!=data.get('networkSha256'):raise ValueError('incidence belongs to a different native network')
    with args.relations.open() as stream:reader=csv.DictReader(stream,delimiter=';');fields=reader.fieldnames;rows=list(reader)
    result,report=fit(rows,data)
    report['inputHashes']={name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in [('relations',args.relations),('incidence',args.incidence)]}
    args.output.mkdir(parents=True,exist_ok=True)
    with (args.output/'freight-relations.csv').open('w',newline='') as stream:
        writer=csv.DictWriter(stream,fieldnames=fields,delimiter=';');writer.writeheader();writer.writerows(result)
    (args.output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ['status','originalBackgroundTrips','fittedBackgroundTrips','holdoutImproves','empiricallyValidated']}))

if __name__=='__main__':main()
