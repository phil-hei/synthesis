#!/usr/bin/env python3
"""Explicit research download of a pinned Overture Places bbox, outside the pipeline.

Requires an already available pyarrow installation. No packages or extensions are
installed. Only site identity, classification, geometry and provenance are read;
contact information and addresses are not included in the resulting snapshot.
"""
import argparse
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import tempfile


def decode_point(wkb):
    if not isinstance(wkb, bytes) or len(wkb) != 21 or wkb[0] not in (0, 1):
        raise ValueError('expected a 2D WKB Point')
    endian = '<' if wkb[0] == 1 else '>'
    if struct.unpack(endian + 'I', wkb[1:5])[0] != 1:
        raise ValueError('expected a 2D WKB Point')
    return {'type': 'Point', 'coordinates': list(struct.unpack(endian + 'dd', wkb[5:]))}


def main():
    spec = importlib.util.spec_from_file_location('site_inspector', Path(__file__).with_name('inspect-freight-sites.py'))
    inspector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(inspector)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--bbox', nargs=4, type=float, required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--max-records', type=int, default=100_000)
    args = parser.parse_args()
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}\.\d+', args.release):
        parser.error('an explicit release such as 2026-09-23.1 is required')
    inspector.validate_bbox(args.bbox)
    if args.max_records <= 0:
        parser.error('max-records must be positive')
    try:
        import pyarrow
        import pyarrow.dataset as ds
        import pyarrow.fs as fs
    except ImportError:
        parser.error('pyarrow is required for this optional research download')
    # A fresh directory avoids mixing a failed download with a previous snapshot.
    args.output.mkdir(parents=True, exist_ok=False)
    filesystem = fs.S3FileSystem(anonymous=True, region='us-west-2', connect_timeout=15, request_timeout=45)
    path = 'overturemaps-us-west-2/release/' + args.release + '/theme=places/type=place/'
    dataset = ds.dataset(path, filesystem=filesystem, format='parquet')
    required = ['id', 'geometry', 'names', 'sources', 'bbox']
    missing = set(required) - set(dataset.schema.names)
    if missing or not {'taxonomy', 'categories'} & set(dataset.schema.names):
        raise ValueError('unsupported Places schema; missing fields: ' + str(sorted(missing)))
    columns = required + [c for c in ('taxonomy', 'categories', 'operating_status', 'confidence') if c in dataset.schema.names]
    west, south, east, north = args.bbox
    field = ds.field
    expression = ((field('bbox', 'xmax') >= west) & (field('bbox', 'xmin') <= east)
                  & (field('bbox', 'ymax') >= south) & (field('bbox', 'ymin') <= north))
    print('Reading pinned Places bbox from {} source files'.format(len(dataset.files)), flush=True)
    rows, ids = [], set()
    for batch in dataset.to_batches(columns=columns, filter=expression, use_threads=False,
                                    batch_readahead=1, fragment_readahead=1):
        for row in batch.to_pylist():
            row['geometry'] = decode_point(row['geometry'])
            x, y = row['geometry']['coordinates']
            if not (west <= x <= east and south <= y <= north):
                continue
            if row['id'] in ids:
                raise ValueError('duplicate source id')
            ids.add(row['id'])
            rows.append(row)
            if len(rows) > args.max_records:
                raise ValueError('record limit exceeded; reduce bbox, no partial snapshot written')
    rows.sort(key=lambda r: r['id'])
    raw = (json.dumps(rows, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode()
    manifest = {
        'release': args.release, 'bbox': args.bbox, 'records': len(rows),
        'retrievedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'sourcePath': 's3://' + path, 'sourceFiles': sorted(dataset.files),
        'snapshotSha256': hashlib.sha256(raw).hexdigest(), 'pyarrowVersion': pyarrow.__version__,
        'columns': columns, 'fetcherSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'queryMethod': 'intersecting rounded bboxes, followed by exact WKB Point containment',
        'simulationReady': False,
    }
    with tempfile.NamedTemporaryFile(dir=args.output, delete=False) as tmp:
        tmp.write(raw)
        temporary = Path(tmp.name)
    temporary.replace(args.output / 'places.json')
    (args.output / 'source.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'records': len(rows), 'snapshotSha256': manifest['snapshotSha256']}))


if __name__ == '__main__':
    main()
