#!/usr/bin/env python3
"""Normalize a pinned BNetzA charging-register CSV for the MobileCity adapter.

The product reads only the deterministic gzip result. This development script preserves the
source hash, counts charging points (not physical sites), and removes operator/contact fields.
"""
import argparse
import csv
import gzip
import hashlib
import io
import json
from datetime import date, datetime
from pathlib import Path

SOURCE_URL = (
    "https://data.bundesnetzagentur.de/Bundesnetzagentur/DE/Fachthemen/"
    "ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenregister_BNetzA_2026-09-01.csv"
)
SOURCE_SHA256 = "cdd8e38f1fa7e8512bd97caa8cf77e9f875436be1f8fe3fe1f2bd9789a504c5b"
REFERENCE_DATE = date(2026, 9, 1)
HEADER = [
    "facilityId", "commissionedOn", "latitude", "longitude", "chargingPoints",
    "nominalPowerKw", "facilityType",
]


def decimal(value: str) -> float:
    return float(value.strip().replace(",", "."))


def prepare(source: Path, output: Path) -> None:
    source_bytes = source.read_bytes()
    if hashlib.sha256(source_bytes).hexdigest() != SOURCE_SHA256:
        raise ValueError("CSV differs from the verified BNetzA snapshot from 2026-09-01")

    rows = []
    invalid = 0
    invalid_by_reason = {}
    invalid_examples = []
    duplicates = 0
    seen = set()
    with source.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.reader(stream, delimiter=";")
        for cells in reader:
            if cells and cells[0] == "Ladeeinrichtungs-ID":
                header = cells
                break
        else:
            raise ValueError("BNetzA header not found")
        columns = {name: index for index, name in enumerate(header)}
        required = [
            "Ladeeinrichtungs-ID", "Status", "Art der Ladeeinrichtung", "Anzahl Ladepunkte",
            "Nennleistung Ladeeinrichtung [kW]", "Inbetriebnahmedatum", "Breitengrad", "Längengrad",
        ]
        if any(name not in columns for name in required):
            raise ValueError("BNetzA schema changed")
        for cells in reader:
            facility_id = ""
            try:
                facility_id = cells[columns["Ladeeinrichtungs-ID"]].strip()
                if not facility_id or cells[columns["Status"]].strip() != "In Betrieb":
                    continue
                if facility_id in seen:
                    duplicates += 1
                    continue
                commissioned = datetime.strptime(
                    cells[columns["Inbetriebnahmedatum"]].strip(), "%d.%m.%Y"
                ).date()
                points = int(cells[columns["Anzahl Ladepunkte"]].strip())
                lat = decimal(cells[columns["Breitengrad"]])
                lon = decimal(cells[columns["Längengrad"]])
                power = decimal(cells[columns["Nennleistung Ladeeinrichtung [kW]"]])
                facility_type = cells[columns["Art der Ladeeinrichtung"]].strip()
                if commissioned > REFERENCE_DATE:
                    raise ValueError("commissioned after snapshot date")
                if points < 1:
                    raise ValueError("charging point count below one")
                if power < 0:
                    raise ValueError("negative nominal power")
                if not (47 <= lat <= 56 and 5 <= lon <= 16):
                    raise ValueError("coordinates outside Germany guard")
                seen.add(facility_id)
                rows.append([
                    facility_id, commissioned.isoformat(), format(lat, ".7f"),
                    format(lon, ".7f"), points, format(power, ".3f"), facility_type,
                ])
            except (IndexError, ValueError) as error:
                invalid += 1
                reason = str(error) or type(error).__name__
                invalid_by_reason[reason] = invalid_by_reason.get(reason, 0) + 1
                if len(invalid_examples) < 20:
                    invalid_examples.append({"facilityId": facility_id or None, "reason": reason})

    rows.sort(key=lambda row: (row[1], row[0]))
    text = io.StringIO(newline="")
    writer = csv.writer(text, delimiter=";", lineterminator="\n")
    writer.writerow(HEADER)
    writer.writerows(rows)
    normalized = text.getvalue().encode("utf-8")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            compressed.write(normalized)

    kinds = {}
    for row in rows:
        kinds[row[6]] = kinds.get(row[6], 0) + int(row[4])
    metadata = {
        "schemaVersion": 1,
        "source": "Bundesnetzagentur Ladesäulenregister",
        "sourceUrl": SOURCE_URL,
        "referenceDate": REFERENCE_DATE.isoformat(),
        "sourceSha256": SOURCE_SHA256,
        "normalizedContentSha256": hashlib.sha256(normalized).hexdigest(),
        "compressedSha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "license": "CC BY 4.0",
        "attribution": "bundesnetzagentur.de",
        "unit": "public charging points (sum of Anzahl Ladepunkte), not facilities or plugs",
        "rows": len(rows),
        "chargingPoints": sum(int(row[4]) for row in rows),
        "chargingPointsByFacilityType": kinds,
        "invalidRows": invalid,
        "invalidRowsByReason": invalid_by_reason,
        "invalidExamples": invalid_examples,
        "duplicateFacilityIds": duplicates,
        "columns": HEADER,
    }
    metadata_path = output.with_suffix("").with_suffix(".json")
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(rows)} facilities / {metadata['chargingPoints']} charging points written")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    prepare(args.source, args.output)
