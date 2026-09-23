#!/usr/bin/env python3
"""Normalize the verified KBA FZ27.17 workbook; retains missing cells and exact source row.
Development-only preparation; no spreadsheet dependency is needed by the application.
"""
import argparse, csv, hashlib, json, re
from pathlib import Path
import openpyxl

SHA = 'd6a26702fa6f8358cc5f0e15e88c04ac6c36527be404058674138dd9571bc803'
URL = 'https://www.kba.de/SharedDocs/Downloads/DE/Statistik/Fahrzeuge/FZ27/fz27_202607.xlsx?__blob=publicationFile&v=3'
def prepare(source, output):
    if hashlib.sha256(source.read_bytes()).hexdigest() != SHA:
        raise ValueError('Workbook differs from verified July 2026 source')
    sheet = openpyxl.load_workbook(source,read_only=True,data_only=True)['FZ 27.17']
    output.parent.mkdir(parents=True,exist_ok=True)
    rows=[]; district=None
    def count(v):
        if v is None or v == '.': return ''
        if v in ('-', '–'): return 0
        if not isinstance(v,(int,float)) or v < 0 or int(v)!=v: raise ValueError(f'Invalid count {v!r}')
        return int(v)
    for number,row in enumerate(sheet.values,1):
        if number<12: continue
        if row[2] is not None:
            m=re.match(r'^(\d{5})\s',str(row[2])); district=m.group(1) if m else None
        if not district or not isinstance(row[3],str): continue
        values=[count(v) for v in row[4:10]]
        if not any(v!='' for v in values): continue
        rows.append([district,row[3],*values,number])
    with output.open('w',newline='',encoding='utf-8') as f:
        w=csv.writer(f,delimiter=';',lineterminator='\n');w.writerow(['districtCode','municipalityName','commercialTotal','commercialBev','commercialPhev','privateTotal','privateBev','privatePhev','sourceRow']);w.writerows(rows)
    output.with_suffix('.json').write_text(json.dumps({'source':'KBA FZ 27.17','sourceUrl':URL,'referenceDate':'2026-07-01','sourceSha256':SHA,'normalizedSha256':hashlib.sha256(output.read_bytes()).hexdigest(),'rows':len(rows),'join':'Registration district code plus unambiguous normalized municipality name; never fuzzy matching'},ensure_ascii=False,indent=2)+'\n')
    print(f'{len(rows)} municipality rows written')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('source',type=Path);p.add_argument('output',type=Path);a=p.parse_args();prepare(a.source,a.output)
