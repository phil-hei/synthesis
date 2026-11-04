#!/usr/bin/env python3
"""Match a canonical tour draft locally against NRW building references (requires pyproj)."""
import argparse
import csv
import hashlib
import io
import json
import re
import unicodedata
import zipfile
from pathlib import Path


def normal(value):
    value = unicodedata.normalize("NFC", value).casefold().strip()
    value = value.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
    value = re.sub(r"str\s*\.?$", "strasse", value)
    # Formatting only: never alter letters, digits or house-number ranges.
    return re.sub(r"[\s.\-]+", "", value)


def address_key(city, street, aliases=None):
    match = re.fullmatch(r"(.*?)\s+(\d+.*)", street.strip())
    city = re.sub(r"\s*,\s*Westf\.?$", "", city.strip(), flags=re.IGNORECASE)
    city = (aliases or {}).get(normal(city), city)
    # Keep punctuation in house numbers: 70-72 must never become 7072.
    house = re.sub(r"\s+", "", unicodedata.normalize("NFC", match[2]).casefold()) if match else ""
    return (normal(city), normal(match[1]), house) if match else None


def prepare(source, draft, depots, transform, aliases=None, qualities=("A",)):
    queries, wanted = {}, {}
    for tour in draft["tours"]:
        for stop in tour["stops"]:
            location = stop["location"]
            query = f"{location['street']}, {location['postalCode']} {location['city']}"
            queries.setdefault(query, [])
            key = address_key(location["city"], location["street"], aliases)
            if key:
                wanted.setdefault(key, set()).add(query)
    for depot in depots:
        queries.setdefault(depot["query"], [])
        key = address_key(depot["city"], depot["street"], aliases)
        if key:
            wanted.setdefault(key, set()).add(depot["query"])
    if len(queries) > 10_000:
        raise ValueError("More than 10000 address queries")
    scanned = 0
    with zipfile.ZipFile(source) as archive:
        with io.TextIOWrapper(archive.open("gebref.txt"), encoding="utf-8") as stream:
            for row in csv.reader(stream, delimiter=";"):
                scanned += 1
                if len(row) != 21 or row[17] != "32":
                    raise ValueError(f"Unexpected NRW reference format at source row {scanned}")
                targets = wanted.get(address_key(row[10], row[14] + " " + row[15] + row[16]), ())
                if not targets or row[2] not in qualities:
                    continue
                lon, lat = transform.transform(float(row[18]), float(row[19]))
                candidate = {"lon": str(lon), "lat": str(lat), "display_name": row[14] + " " + row[15] + row[16] + ", " + row[10],
                             "osm_type": "nrw-gebref", "osm_id": row[1],
                             "licence": "Geobasis NRW, Gebäudereferenzen " + row[20] + ", Datenlizenz Deutschland Zero 2.0; Postleitzahl nicht geprüft"}
                if row[2] == "B":
                    candidate["licence"] += "; Qualität B: Flurstückspunkt, Gebäude nicht sicher; nur als ausdrückliche Modellannahme verwenden"
                for query in targets:
                    if all((c["lon"], c["lat"]) != (candidate["lon"], candidate["lat"]) for c in queries[query]):
                        queries[query].append(candidate)
    return queries, {"sourceRows": scanned, "queries": len(queries), "unique": sum(len(v) == 1 for v in queries.values()),
                     "ambiguous": sum(len(v) > 1 for v in queries.values()), "missing": sum(not v for v in queries.values()),
                     "postalCodesVerified": False, "truckEntrancesVerified": False,
                     "matching": "normalized municipality/street formatting and exact house number; no fuzzy replacement",
                     "acceptedQualities": list(qualities),
                     "municipalityAliases": aliases or {}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Official NRW ZIP containing gebref.txt")
    parser.add_argument("--draft", type=Path, required=True, help="Canonical company-tours draft JSON")
    parser.add_argument("--depots", type=Path, help="JSON list of {query, city, street}; explicit municipality for each depot")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--municipality-aliases", type=Path, help="Reviewed JSON object mapping source municipality names to official names")
    parser.add_argument("--qualities", nargs="+", choices=["A", "B"], default=["A"], help="Default A only; B is an explicitly requested parcel-point modelling proposal, not a building location")
    args = parser.parse_args()
    from pyproj import Transformer
    draft = json.loads(args.draft.read_text(encoding="utf-8"))
    if draft.get("schemaVersion") != "synthesis-company-tours-v1":
        parser.error("Expected synthesis-company-tours-v1 draft")
    depots = json.loads(args.depots.read_text(encoding="utf-8")) if args.depots else []
    aliases = json.loads(args.municipality_aliases.read_text(encoding="utf-8")) if args.municipality_aliases else {}
    if not isinstance(aliases, dict) or any(not isinstance(k, str) or not k.strip() or not isinstance(v, str) or not v.strip() for k, v in aliases.items()):
        parser.error("Municipality aliases must map nonempty names to official names")
    aliases = {normal(k): v for k, v in aliases.items()}
    queries, report = prepare(args.source, draft, depots, Transformer.from_crs(25832, 4326, always_xy=True), aliases, args.qualities)
    digest = hashlib.sha256()
    with args.source.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    report["sourceSha256"] = digest.hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(queries, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output.with_suffix(".report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report))  # Aggregates only; no customer addresses in logs.


if __name__ == "__main__":
    main()
