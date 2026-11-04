#!/usr/bin/env python3
"""Preserve EVSE/connector details of the same pinned BNetzA source as MobileCity."""
import argparse,csv,gzip,hashlib,io,json,math
from pathlib import Path
SOURCE_SHA='cdd8e38f1fa7e8512bd97caa8cf77e9f875436be1f8fe3fe1f2bd9789a504c5b'
TYPES={'AC Typ 2 Steckdose':'type2','AC Typ 2 Fahrzeugkupplung':'type2','DC Fahrzeugkupplung Typ Combo 2 (CCS)':'ccs2','DC CHAdeMO':'chademo','AC Schuko':'schuko'}
def number(s):
 try:
  value=float(s.strip().replace(',','.'));return value if math.isfinite(value) and value>0 else None
 except ValueError:return None

def prepare(source,reference,output):
 raw=source.read_bytes()
 if hashlib.sha256(raw).hexdigest()!=SOURCE_SHA:raise ValueError('Unverified BNetzA snapshot')
 normalized=gzip.decompress(reference.read_bytes());known={r['facilityId']:int(r['chargingPoints']) for r in csv.DictReader(io.StringIO(normalized.decode()),delimiter=';')}
 facilities=[];mismatches=0
 with source.open(encoding='utf-8-sig',newline='') as f:
  reader=csv.reader(f,delimiter=';')
  for header in reader:
   if header and header[0]=='Ladeeinrichtungs-ID':break
  for row in csv.DictReader(f,fieldnames=header,delimiter=';'):
   fid=row['Ladeeinrichtungs-ID']
   if fid not in known:continue
   points=[]
   for slot in range(1,7):
    names=[v.strip() for v in row['Steckertypen'+str(slot)].split(';') if v.strip()]
    powers=[number(v) for v in row['Nennleistung Stecker'+str(slot)].split(';') if v.strip()]
    evse=row['EVSE-ID'+str(slot)].strip()
    if not names and not powers and not evse:continue
    aligned=len(names)==len(powers)
    mismatches+=not aligned
    points.append({'id':f'bnetza:{fid}:point:{slot}','evseId':evse or None,'connectors':[{'type':TYPES.get(name,'unknown'),'sourceType':name,'powerKw':powers[i] if aligned else None,'count':None} for i,name in enumerate(names)],'powerAssociationKnown':aligned,'sourcePower':row['Nennleistung Stecker'+str(slot)]})
   if len(points)!=known[fid]:raise ValueError('Point count differs from pinned reference: '+fid)
   facilities.append({'facilityId':fid,'name':row['Standortbezeichnung'].strip() or 'Ladeeinrichtung '+fid,'openingHours':{'kind':row['Öffnungszeiten'],'weekdays':row['Öffnungszeiten: Wochentage'],'times':row['Öffnungszeiten: Tageszeiten']},'parkingInformation':row['Informationen zum Parkraum'],'points':points})
 facilities.sort(key=lambda x:x['facilityId'])
 if len(facilities)!=len(known):raise ValueError('Missing facilities')
 data=json.dumps(facilities,ensure_ascii=False,separators=(',',':')).encode();output.parent.mkdir(parents=True,exist_ok=True)
 with output.open('wb') as f:
  with gzip.GzipFile(filename='',mode='wb',fileobj=f,mtime=0) as z:z.write(data)
 metadata={'contract':'synthesis-bnetza-charging-details-v1','referenceDate':'2026-09-01','sourceSha256':SOURCE_SHA,'referenceNormalizedSha256':hashlib.sha256(normalized).hexdigest(),'contentSha256':hashlib.sha256(data).hexdigest(),'compressedSha256':hashlib.sha256(output.read_bytes()).hexdigest(),'facilities':len(facilities),'chargingPoints':sum(len(f['points']) for f in facilities),'unknownPowerAssociationPoints':mismatches,'license':'CC BY 4.0','attribution':'Bundesnetzagentur.de','sourceUrl':'https://data.bundesnetzagentur.de/Bundesnetzagentur/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenregister_BNetzA_2026-09-01.csv'}
 output.with_suffix('').with_suffix('.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(metadata))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('--reference',type=Path,default=Path('backend/src/main/resources/reference/bnetza-public-charging-2026-09-01.csv.gz'));p.add_argument('--output',type=Path,default=Path('backend/src/main/resources/reference/bnetza-charging-details-2026-09-01.json.gz'));a=p.parse_args();prepare(a.source,a.reference,a.output)
