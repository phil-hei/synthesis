#!/usr/bin/env python3
"""Import a downloaded KBA VD PUF ZIP as auditable reference data, never as daily vehicle OD.
No third-party dependencies. Codes stay uninterpreted until a matching codebook is supplied.
"""
import argparse
import csv
from decimal import Decimal, InvalidOperation
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile
from collections import Counter

QUALITY = {"": "published", "()": "restricted", "/": "suppressed", "-": "dash"}


def inspect_package(input_path, output, source_url, retrieved_on):
    raw = Path(input_path).read_bytes()
    if len(raw) > 20_000_000:
        raise ValueError("ZIP exceeds 20 MB")
    tables, observations = [], []
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        infos = archive.infolist()
        if not infos or len(infos) > 100 or sum(i.file_size for i in infos) > 40_000_000:
            raise ValueError("Invalid or excessive ZIP contents")
        for info in sorted(infos, key=lambda i: i.filename):
            if not re.fullmatch(r"PUF-VD_[A-Za-z0-9-]+-\d{4}\.csv", info.filename):
                raise ValueError(f"Unexpected archive member: {info.filename}")
            data = archive.read(info)
            reader = csv.DictReader(io.StringIO(data.decode("utf-8-sig")), delimiter=";")
            columns = reader.fieldnames or []
            if "JAHR" not in columns or len(set(columns)) != len(columns):
                raise ValueError(f"Missing year or duplicate columns: {info.filename}")
            metrics = [c for c in columns if c + "_ZS" in columns]
            dimensions = [c for c in columns if c not in metrics and not c.endswith("_ZS")]
            if not metrics:
                raise ValueError(f"No metric/quality pairs: {info.filename}")
            counts, row_count = Counter(), 0
            for index, row in enumerate(reader, start=2):
                if None in row or any(v is None for v in row.values()):
                    raise ValueError(f"Invalid column count: {info.filename}:{index}")
                if row["JAHR"] != info.filename[-8:-4]:
                    raise ValueError(f"Year mismatch: {info.filename}:{index}")
                row_count += 1
                for metric in metrics:
                    value, flag = row[metric].strip(), row[metric + "_ZS"].strip()
                    if flag not in QUALITY:
                        raise ValueError(f"Unknown quality marker {flag!r}: {info.filename}:{index}")
                    quality = QUALITY[flag]
                    counts[quality] += 1
                    if quality in ("suppressed", "dash"):
                        if value:
                            raise ValueError(f"Unexpected value with missing marker: {info.filename}:{index}")
                        numeric = None  # Never interpret withheld/missing values as zero.
                    else:
                        try:
                            number = Decimal(value.replace(",", "."))
                            if not number.is_finite() or number < 0:
                                raise InvalidOperation()
                        except InvalidOperation as exc:
                            raise ValueError(f"Invalid numeric metric: {info.filename}:{index}") from exc
                        numeric = str(number)
                    observations.append({"table": info.filename, "row": index,
                                         "dimensions": {d: row[d] for d in dimensions},
                                         "metric": metric, "value": numeric, "quality": quality,
                                         "originalQualityMarker": flag})
            tables.append({"file": info.filename, "sha256": hashlib.sha256(data).hexdigest(),
                           "rows": row_count, "columns": columns, "qualityCounts": dict(counts)})
    # Parse everything first: a failed import cannot leave a plausible partial dataset.
    destination = Path(output)
    destination.mkdir(parents=True, exist_ok=True)
    summary = {"contract": "synthesis-kba-puf-reference-v1", "sourceUrl": source_url,
               "retrievedOn": retrieved_on, "zipSha256": hashlib.sha256(raw).hexdigest(),
               "tables": tables, "observations": len(observations),
               "codesDecoded": False, "dailyDemandGenerated": False,
               "licenseVerified": False, "suppressedValuesAreZero": False}
    (destination / "inventory.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    with (destination / "observations.jsonl").open("w", encoding="utf-8") as stream:
        for record in observations:
            stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True)+"\n")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source-url", required=True)
    parser.add_argument("--retrieved-on", required=True, help="ISO date of actual retrieval")
    args = parser.parse_args()
    import datetime
    datetime.date.fromisoformat(args.retrieved_on)
    result = inspect_package(args.input, args.output, args.source_url, args.retrieved_on)
    print(f"Imported {len(result['tables'])} tables / {result['observations']} metric observations; no daily demand generated.")
