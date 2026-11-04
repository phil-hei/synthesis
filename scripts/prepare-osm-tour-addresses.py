#!/usr/bin/env python3
"""Supplement a local tour candidate index from complete OSM extracts; no network calls.

Requires pyosmium and shapely in an optional isolated runtime. Existing candidates
are retained. Only exact normalized street/house/postcode matches are proposed.
Building representative points and mapped address nodes are not verified entrances.
"""
import argparse
import hashlib
import importlib.util
import json
import re
from pathlib import Path

_spec = importlib.util.spec_from_file_location("nrw_addresses", Path(__file__).with_name("prepare-nrw-tour-addresses.py"))
nrw = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(nrw)


def address_key(street, house, postcode, city="", allow_city_without_postcode=False):
    if not re.fullmatch(r"\d{5}", postcode.strip()):
        if not allow_city_without_postcode or postcode.strip() or not city.strip():
            return None
        postcode = "city:" + nrw.normal(city)
    return nrw.normal(street), re.sub(r"\s+", "", house.casefold()), postcode.strip()


def wanted_queries(draft, index, corrections, allow_city_without_postcode=False, aliases=None):
    wanted = {}
    for tour in draft["tours"]:
        for stop in tour["stops"]:
            location = stop["location"]
            query = f"{location['street']}, {location['postalCode']} {location['city']}"
            index.setdefault(query, [])
            if index[query]:
                continue
            correction = corrections.get(query)
            street = correction["street"] if correction else location["street"]
            match = re.fullmatch(r"(.*?)\s+(\d+.*)", street.strip())
            key = address_key(match[1], match[2], location["postalCode"]) if match else None
            if key:
                wanted.setdefault(key, set()).add(query)
                if allow_city_without_postcode:
                    city = (aliases or {}).get(nrw.normal(location["city"]), location["city"])
                    wanted.setdefault(address_key(match[1], match[2], "", city, True), set()).add(query)
    unknown = set(corrections) - set(index)
    if unknown:
        raise ValueError("Correction refers to an unknown source address")
    return wanted


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def supplement(inputs, draft, index, corrections, allow_city_without_postcode=False, aliases=None):
    import osmium
    from shapely.geometry import Polygon
    wanted = wanted_queries(draft, index, corrections, allow_city_without_postcode, aliases)
    counts = {"matchedObjects": 0, "invalidGeometry": 0}

    class Addresses(osmium.SimpleHandler):
        def add(self, obj, point, kind, method):
            key = address_key(obj.tags.get("addr:street", ""), obj.tags.get("addr:housenumber", ""), obj.tags.get("addr:postcode", ""), obj.tags.get("addr:city", ""), allow_city_without_postcode)
            queries = wanted.get(key, ())
            if not queries:
                return
            label = f"{obj.tags['addr:street']} {obj.tags['addr:housenumber']}, {obj.tags.get('addr:postcode', '')} {obj.tags.get('addr:city', '')}".strip()
            candidate = {"lon": str(point[0]), "lat": str(point[1]), "display_name": label,
                         "osm_type": kind, "osm_id": str(obj.id),
                         "licence": f"OpenStreetMap contributors, ODbL-1.0; {method}; LKW-Zufahrt nicht geprüft"}
            if not obj.tags.get("addr:postcode"):
                candidate["licence"] += "; explizit freigegebener Gemeindeabgleich ohne Postleitzahl, Postleitzahl nicht geprüft"
            for query in queries:
                selected = dict(candidate)
                if query in corrections:
                    selected["licence"] += "; Modellannahme Adresskorrektur: " + corrections[query]["reason"]
                if all((v["lon"], v["lat"]) != (selected["lon"], selected["lat"]) for v in index[query]):
                    index[query].append(selected)
            counts["matchedObjects"] += 1

        def node(self, obj):
            if obj.tags.get("addr:street") and obj.tags.get("addr:housenumber"):
                self.add(obj, (obj.lon, obj.lat), "node", "mapped-address-node")

        def way(self, obj):
            if not obj.tags.get("addr:street") or not obj.tags.get("addr:housenumber"):
                return
            key = address_key(obj.tags["addr:street"], obj.tags["addr:housenumber"], obj.tags.get("addr:postcode", ""), obj.tags.get("addr:city", ""), allow_city_without_postcode)
            if key not in wanted:
                return
            try:
                points = [(node.lon, node.lat) for node in obj.nodes]
                if len(points) < 4 or points[0] != points[-1]:
                    return
                polygon = Polygon(points)
                if not polygon.is_valid or polygon.is_empty:
                    raise ValueError("Invalid address polygon")
                point = polygon.representative_point()
                self.add(obj, (point.x, point.y), "way", "building-representative-point-not-entrance")
            except (ValueError, osmium.InvalidLocationError):
                counts["invalidGeometry"] += 1

    for source in inputs:
        handler = Addresses()
        processor = osmium.FileProcessor(str(source)).with_locations().with_filter(osmium.filter.KeyFilter("addr:street")).with_filter(osmium.filter.KeyFilter("addr:housenumber"))
        for obj in processor:
            if obj.is_node():
                handler.node(obj)
            elif obj.is_way():
                handler.way(obj)
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, action="append", required=True)
    parser.add_argument("--draft", type=Path, required=True)
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--corrections", type=Path, help="Reviewed query -> {street, reason} corrections, always marked as modelling assumptions")
    parser.add_argument("--allow-city-without-postcode", action="store_true", help="Explicitly allow exact street/house/municipality matches only when the OSM postcode is absent, never when conflicting")
    parser.add_argument("--municipality-aliases", type=Path, help="Reviewed source municipality -> official municipality JSON object")
    args = parser.parse_args()
    draft = json.loads(args.draft.read_text(encoding="utf-8"))
    if draft.get("schemaVersion") != "synthesis-company-tours-v1":
        parser.error("Expected synthesis-company-tours-v1 draft")
    index = json.loads(args.index.read_text(encoding="utf-8"))
    corrections = json.loads(args.corrections.read_text(encoding="utf-8")) if args.corrections else {}
    if not isinstance(corrections, dict) or any(not isinstance(v, dict) or not isinstance(v.get("street"), str) or not v["street"].strip() or not isinstance(v.get("reason"), str) or not v["reason"].strip() for v in corrections.values()):
        parser.error("Each correction needs a street and explicit reason")
    aliases = json.loads(args.municipality_aliases.read_text(encoding="utf-8")) if args.municipality_aliases else {}
    if not isinstance(aliases, dict) or any(not isinstance(k, str) or not k.strip() or not isinstance(v, str) or not v.strip() for k, v in aliases.items()):
        parser.error("Municipality aliases must map nonempty names to official names")
    aliases = {nrw.normal(k): v for k, v in aliases.items()}
    counts = supplement(args.input, draft, index, corrections, args.allow_city_without_postcode, aliases)
    report = {**counts, "queries": len(index), "unique": sum(len(v) == 1 for v in index.values()),
              "ambiguous": sum(len(v) > 1 for v in index.values()), "missing": sum(not v for v in index.values()),
              "sources": [{"file": p.name, "sha256": digest(p)} for p in args.input],
              "correctionCount": len(corrections), "truckEntrancesVerified": False,
              "allowCityWithoutPostcode": args.allow_city_without_postcode, "municipalityAliases": aliases,
              "matching": "exact normalized street/house plus postcode; explicitly optional municipality match only for absent postcodes; corrections marked; proposals only"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output.with_suffix(".report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
