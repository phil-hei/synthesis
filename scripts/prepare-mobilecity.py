#!/usr/bin/env python3
"""Prepare a local, hash-pinned MobileCity template. Never changes the supplied sources.

Requires pyproj only for the one-time GeoPackage conversion (e.g. transit-venv).
Usage: python prepare-mobilecity.py --tool-dir PATH --districts-gpkg PATH --output PATH
"""
import argparse
import csv
import hashlib
import io
import json
import shutil
import sqlite3
import struct
from pathlib import Path

BLOCKS = json.loads((Path(__file__).resolve().parents[1] / 'backend/src/main/resources/mobilecity-contract.json').read_text())

def read_blocks(path):
    lines = path.read_text(encoding='utf-8-sig').splitlines()
    delim = ',' if lines[0].count(',') >= lines[0].count(';') else ';'
    separator = delim * (len(next(csv.reader([lines[0]], delimiter=delim))) - 1)
    result, block, name = {}, [], None
    for line in lines + [separator]:
        if not line:
            continue
        if line == separator:
            if name is not None and name not in result:
                result[name] = block
            block, name = [], None
        else:
            row = next(csv.reader([line], delimiter=delim))
            if name is None:
                name = row[0]
            else:
                block.append(row)
    return result

def write_blocks(path, blocks):
    width = max(8, *(len(rows[0]) for rows in blocks.values()))
    separator = ',' * (width - 1)
    out = io.StringIO(newline='')
    writer = csv.writer(out, lineterminator='\n')
    out.write(path.stem + separator + '\n')
    for name, rows in blocks.items():
        out.write(separator + '\n')
        writer.writerow([name])
        writer.writerows(rows)
    path.write_text(out.getvalue(), encoding='utf-8')

def wkb_geometry(raw, transform):
    """Polygon/MultiPolygon WKB from a GeoPackage, preserving rings and holes."""
    if raw[:2] != b'GP':
        raise ValueError('Invalid GeoPackage geometry')
    envelope = (raw[3] >> 1) & 7
    offset = 8 + {0: 0, 1: 32, 2: 48, 3: 48, 4: 64}[envelope]
    def parse(pos):
        endian = '<' if raw[pos] == 1 else '>'
        kind = struct.unpack_from(endian + 'I', raw, pos + 1)[0]
        pos += 5
        count = struct.unpack_from(endian + 'I', raw, pos)[0]
        pos += 4
        items = []
        if kind == 3:
            for _ in range(count):
                n = struct.unpack_from(endian + 'I', raw, pos)[0]
                pos += 4
                ring = []
                for _ in range(n):
                    x, y = struct.unpack_from(endian + 'dd', raw, pos)
                    pos += 16
                    ring.append(list(transform(x, y)))
                items.append(ring)
            return {'type': 'Polygon', 'coordinates': items}, pos
        if kind == 6:
            for _ in range(count):
                geom, pos = parse(pos)
                if geom['type'] != 'Polygon':
                    raise ValueError('MultiPolygon contains a non-polygon')
                items.append(geom['coordinates'])
            return {'type': 'MultiPolygon', 'coordinates': items}, pos
        raise ValueError(f'Unsupported geometry type {kind}')
    return parse(offset)[0]

def prepare(tool_dir, gpkg, output):
    from pyproj import Transformer
    if output.exists():
        raise ValueError(f'Output already exists; choose a new template version: {output}')
    source = tool_dir / 'Includes/StudyAreas/Tramigo/Import'
    selected, hashes, repairs, issues = {}, {}, [], []
    for family, names in BLOCKS.items():
        files = sorted(p for p in source.glob(family + '*.csv')
                       if p.stem == family or not p.stem[len(family)].isalpha())
        if len(files) != 1:
            raise ValueError(f'Expected one {family} CSV, found {len(files)}')
        hashes[str(files[0].relative_to(tool_dir))] = hashlib.sha256(files[0].read_bytes()).hexdigest()
        all_blocks = read_blocks(files[0])
        selected[family] = {}
        for name in names:
            if name not in all_blocks:
                raise ValueError(f'{family}: missing block {name}; found {list(all_blocks)}')
            rows = all_blocks[name]
            # Remove padding columns; their empty headers are not part of the data contract.
            columns = [i for i, h in enumerate(rows[0]) if h]
            selected[family][name] = [[r[i] if i < len(r) else '' for i in columns] for r in rows]

    district_rows = selected['REGdistricts']['districts']
    district_ids = {r[0] for r in district_rows[1:]}
    if len(district_ids) != len(district_rows) - 1:
        raise ValueError('Duplicate district IDs')
    types = district_rows[0].index('districtType')
    counts = {'studyAreaDistrictCount': sum(int(r[types]) > 0 for r in district_rows[1:]),
              'surroundingDistrictCount': sum(int(r[types]) == 0 for r in district_rows[1:])}
    for row in selected['StudyAreaConfig']['studyAreaParametersList'][1:]:
        if row[0] in counts and row[1] != str(counts[row[0]]):
            repairs.append({'file': 'StudyAreaConfig.csv', 'parameter': row[0], 'old': row[1], 'new': counts[row[0]]})
            row[1] = str(counts[row[0]])
    # Boundary nodes remain usable for through-routing, but cannot represent a foreign district.
    for mode in ('car', 'bike', 'transit'):
        rows = selected[f'INF{mode}Nodes'][mode + 'Nodes']
        district, ref = rows[0].index('districtID'), rows[0].index('refNodeTrue')
        changed = []
        for row in rows[1:]:
            if row[district] not in district_ids and row[ref] == '1':
                row[ref] = '0'
                changed.append(row[0])
        if changed:
            repairs.append({'file': f'INF{mode}Nodes.csv', 'action': 'foreign-district-refNodeTrue-to-0', 'nodeIds': changed})
        links = selected[f'INF{mode}Links'][mode + 'Links']
        for row in links[1:]:
            values = dict(zip(links[0], row))
            if values.get('active') == '1' and float(values['distance'] or 0) > 0 and float(values['time'] or 0) <= 0:
                issues.append({'file': f'INF{mode}Links.csv', 'from': values['fromNodeID'],
                               'to': values['toNodeID'], 'issue': 'positive-distance-without-positive-travel-time'})

    db = sqlite3.connect(gpkg.resolve().as_uri() + '?mode=ro', uri=True)
    table, geometry, srs = db.execute('select table_name,column_name,srs_id from gpkg_geometry_columns').fetchone()
    transform = Transformer.from_crs(srs, 4326, always_xy=True).transform
    quoted = lambda s: '"' + s.replace('"', '""') + '"'
    features = [{'type': 'Feature', 'properties': {'districtID': district}, 'geometry': wkb_geometry(raw, transform)}
                for district, raw in db.execute(f'select districtID,{quoted(geometry)} from {quoted(table)}')]
    db.close()
    if {f['properties']['districtID'] for f in features} != district_ids:
        raise ValueError('District geometry IDs do not match REGdistricts')
    hashes[gpkg.name] = hashlib.sha256(gpkg.read_bytes()).hexdigest()

    output.mkdir(parents=True)
    for family, blocks in selected.items():
        write_blocks(output / (family + '.csv'), blocks)
    (output / 'districts.geojson').write_text(json.dumps({'type': 'FeatureCollection', 'features': features}, ensure_ascii=False), encoding='utf-8')
    if (source / 'GovernanceConfig').is_dir():
        shutil.copytree(source / 'GovernanceConfig', output / 'GovernanceConfig')
    manifest = {'schemaVersion': 1, 'id': output.name, 'label': 'TRAMIGO Mittlerer Oberrhein',
                'sourceHashes': hashes, 'repairs': repairs, 'issues': issues,
                'files': {str(p.relative_to(output)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in sorted(output.rglob('*')) if p.is_file()}}
    (output / 'template-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'output': str(output), 'districts': len(district_ids), 'files': len(BLOCKS), 'repairs': repairs}, ensure_ascii=False))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tool-dir', type=Path, required=True)
    parser.add_argument('--districts-gpkg', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.tool_dir, args.districts_gpkg, args.output)
