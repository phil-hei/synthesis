#!/usr/bin/env python3
"""Verify a versioned set of regional freight regression cases against saved native audits."""
import argparse
import hashlib
import json
from pathlib import Path


def verify(policy,baseline,after):
    if policy.get('contract')!='freight-reference-cases-v1':raise ValueError('unsupported reference contract')
    before={r['id']:r for r in baseline};later={r['id']:r for r in after}
    if len(before)!=len(baseline) or len(later)!=len(after) or set(before)!=set(later):raise ValueError('original probe accounting changed')
    result=[]
    for case in policy['cases']:
        found_violation=False
        for identifier,status in case['expectedAfterByProbe'].items():
            b,a=before[identifier],later[identifier]
            if b['siteId']!=case['siteId'] or a['siteId']!=case['siteId']:raise ValueError('reference site changed')
            if any(b[k]!=a[k] for k in ('fromEdge','toEdge','vehicleClass','direction')):raise ValueError('fixed endpoints/profile changed')
            limit=case['baselineMustContainViolation']
            found_violation |= any(e.get('status')=='violation' and all(e.get(k)==v for k,v in limit.items()) for e in b['events'])
            if a['status']!=status or any(e.get('status')=='violation' for e in a['events']):raise ValueError('unexpected regional regression: '+identifier)
        if not found_violation:raise ValueError('missing positive baseline control')
        result.append({'siteId':case['siteId'],'sourceName':case['sourceName'],'probesVerified':len(case['expectedAfterByProbe']),
                       'baselinePositiveControl':True,'status':'regression-verified','accessCertified':False})
    return {'contract':'freight-reference-verification-v1','originalProbesAccounted':len(before),
            'referenceSites':len(result),'referenceProbes':sum(r['probesVerified'] for r in result),'cases':result,'accessCertified':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('cases','baseline','after','output'):parser.add_argument('--'+name,type=Path,required=True)
    a=parser.parse_args();report=verify(*(json.loads(getattr(a,n).read_text()) for n in ('cases','baseline','after')))
    report['sha256']={n:hashlib.sha256(getattr(a,n).read_bytes()).hexdigest() for n in ('cases','baseline','after')}
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('cases','sha256')}))


if __name__=='__main__':main()
