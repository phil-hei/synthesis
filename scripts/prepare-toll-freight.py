#!/usr/bin/env python3
"""Offline Toll Collect monthly OD normalizer; optional explicit access mapping.

Inputs: complete ArcGIS JSON/GeoJSON query responses, one calendar month,
land='x'. Outputs preserve observations separately from synthetic day assumptions.
No network access, municipal centroid snapping, class inference or calibration.
"""
import argparse
import calendar
import csv
import datetime as dt
import hashlib
import json
import math
import pathlib
import re
from collections import defaultdict

HEADER = 'id;vehicleClass;movement;fromLon;fromLat;toLon;toLat;trips;beginHour;endHour;source;referenceDate;fromEdge;toEdge'
MEASURES = ('anzahl_befahrungen', 'fahrleistung_km')


def normalize(paths, month):
    date = dt.date.fromisoformat(month + '-01')
    expected = int(dt.datetime.combine(date, dt.time(), dt.timezone.utc).timestamp() * 1000)
    records, files, duplicates = {}, [], 0
    for path in paths:
        data = pathlib.Path(path).read_bytes()
        obj = json.loads(data)
        if 'error' in obj or obj.get('exceededTransferLimit'):
            raise ValueError(f'{path}: API error or incomplete page; combine complete paginated response first')
        if not isinstance(obj.get('features'), list):
            raise ValueError(f'{path}: features missing')
        files.append({'file': str(path), 'sha256': hashlib.sha256(data).hexdigest(), 'rows': len(obj['features'])})
        for feature in obj['features']:
            a = feature.get('attributes', feature.get('properties'))
            if not isinstance(a, dict) or a.get('monat') != expected or a.get('land') != 'x':
                raise ValueError(f'{path}: unexpected month or land aggregate')
            if a.get('richtung') not in (0, 1):
                raise ValueError('unknown perspective')
            for name in ('ags_start', 'ags_ziel'):
                if not isinstance(a.get(name), str) or not re.fullmatch(r'\d{8}', a[name]):
                    raise ValueError(f'invalid {name}; foreign/nonmunicipal aggregate needs separate adapter')
            count = a.get('anzahl_befahrungen')
            distance = a.get('fahrleistung_km')
            if type(count) is not int or count <= 0 or type(distance) not in (int, float) or not math.isfinite(distance) or distance < 0:
                raise ValueError('invalid/suppressed count or distance')
            key = (a['ags_start'], a['ags_ziel'])
            observation = {n: a.get(n) for n in (*MEASURES, 'name_start', 'name_ziel')}
            if key in records:
                if records[key] != observation:
                    raise ValueError(f'conflicting duplicate OD {key}; never sum perspectives')
                duplicates += 1
            else:
                records[key] = observation
    if not records:
        raise ValueError('no observed relations')
    days = calendar.monthrange(date.year, date.month)[1]
    report = {'contract': 'toll-monthly-od-v1', 'month': month, 'calendarDays': days,
              'files': files, 'inputRows': sum(x['rows'] for x in files),
              'uniqueRelations': len(records), 'duplicatePerspectivesRemoved': duplicates,
              'monthlyObservedEntries': sum(a['anzahl_befahrungen'] for a in records.values()),
              'monthlyIntramunicipalEntries': sum(a['anzahl_befahrungen'] for k, a in records.items() if k[0] == k[1]),
              'sourceSemantics': 'entries/exits of toll network, not shipment origins/destinations',
              'coverage': 'only supplied queries; through traffic without focus municipality entry/exit absent',
              'license': 'DL-DE-BY-2.0', 'attribution': ['BALM', 'Toll Collect GmbH'],
              'dayAssumption': 'calendar-month mean, no weekday/hour observation',
              'publishedCountsAllMultiplesOfTen': all(a['anzahl_befahrungen'] % 10 == 0 for a in records.values()),
              'countPrecision': 'portal documentation: counts rounded to nearest ten for privacy; not exact vehicle counts',
              'dailyDemandGenerated': False, 'calibrated': False}
    return records, report


def mapped_day(records, report, mapping):
    if not isinstance(mapping.get('assumption'), str) or not mapping['assumption'].strip():
        raise ValueError('mapping assumption required: accesses, vehicle classes and departure window are modeled')
    totals = defaultdict(float)
    rows, fractions, ids = [], [], set()
    for row in mapping['relations']:
        key = (row['ags_start'], row['ags_ziel'])
        if key not in records:
            raise ValueError(f'mapping references unobserved OD {key}')
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', row['id']) or row['id'] in ids:
            raise ValueError('invalid/duplicate mapping id')
        ids.add(row['id'])
        if row['vehicleClass'] not in ('truck', 'trailer') or row['movement'] not in ('internal', 'inbound', 'outbound', 'through'):
            raise ValueError('invalid modeled vehicle class/movement')
        for n, limit in [('fromLon', 180), ('toLon', 180), ('fromLat', 90), ('toLat', 90)]:
            if type(row[n]) not in (float, int) or not math.isfinite(row[n]) or abs(row[n]) > limit:
                raise ValueError('invalid access coordinate')
        if (row['fromLon'], row['fromLat']) == (row['toLon'], row['toLat']):
            raise ValueError('distinct access coordinates required')
        if not all(isinstance(row.get(n), str) and re.fullmatch(r'[A-Za-z0-9_#.:+-]{1,160}', row[n]) for n in ('fromEdge', 'toEdge')) or row['fromEdge'] == row['toEdge']:
            raise ValueError('distinct directed access edges required')
        if not all(type(row[n]) in (float, int) and math.isfinite(row[n]) for n in ('beginHour', 'endHour', 'share')):
            raise ValueError('invalid window/share')
        if not 0 <= row['beginHour'] < row['endHour'] <= 24 or not 0 < row['share'] <= 1:
            raise ValueError('invalid window/share')
        totals[key] += row['share']
        if totals[key] > 1 + 1e-9:
            raise ValueError('mapped shares exceed observed OD')
        value = records[key]['anzahl_befahrungen'] * row['share'] / report['calendarDays']
        rows.append(dict(row, trips=math.floor(value)))
        fractions.append(value - math.floor(value))
    # Largest remainders; conserve rounded total across mapped shares, deterministic ties.
    target = math.floor(sum(x['trips'] + f for x, f in zip(rows, fractions)) + .5)
    for i in sorted(range(len(rows)), key=lambda i: (-fractions[i], rows[i]['id']))[:target - sum(x['trips'] for x in rows)]:
        rows[i]['trips'] += 1
    if not rows or target == 0 or len(rows) > 10000 or target > 200000:
        raise ValueError('empty or oversized daily demand')
    represented = sum(records[k]['anzahl_befahrungen'] * share for k, share in totals.items())
    report.update(dailyDemandGenerated=True, dailyTrips=target, mappingAssumption=mapping['assumption'], mapping=mapping,
                  mappingSha256=hashlib.sha256(json.dumps(mapping, sort_keys=True, separators=(',', ':')).encode()).hexdigest(),
                  mappedMonthlyEntries=represented, unmappedMonthlyEntries=report['monthlyObservedEntries'] - represented,
                  zeroRoundedMappingRows=sum(x['trips'] == 0 for x in rows),
                  mappingValidatedAgainstNetwork=False, referenceDateSemantics='month start labels monthly source, not observed trip date')
    return [x for x in sorted(rows, key=lambda x: x['id']) if x['trips'] > 0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs', nargs='+', type=pathlib.Path)
    parser.add_argument('--month', required=True)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--mapping', type=pathlib.Path)
    args = parser.parse_args()
    records, report = normalize(args.inputs, args.month)
    mapped = mapped_day(records, report, json.loads(args.mapping.read_text())) if args.mapping else None
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / 'monthly-od.csv').open('w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f, delimiter=';')
        writer.writerow(['month', 'ags_start', 'ags_ziel', 'name_start', 'name_ziel', 'monthly_entries', 'monthly_km', 'calendar_day_mean'])
        for (start, end), a in sorted(records.items()):
            writer.writerow([args.month, start, end, a['name_start'], a['name_ziel'], a['anzahl_befahrungen'], a['fahrleistung_km'], a['anzahl_befahrungen'] / report['calendarDays']])
    # Reusing an output directory must not leave an obsolete mapped-day export behind.
    daily = args.output / 'freight-relations.csv'
    if daily.exists():
        daily.unlink()
    if mapped is not None:
        with daily.open('w', newline='', encoding='utf-8') as f:
            writer = csv.writer(f, delimiter=';', quoting=csv.QUOTE_NONE)
            writer.writerow(HEADER.split(';'))
            for row in mapped:
                row.update(source='TollCollect-' + args.month + '-calendar-mean-modeled-access', referenceDate=args.month + '-01')
                writer.writerow([row[n] for n in HEADER.split(';')])
    (args.output / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'files'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
