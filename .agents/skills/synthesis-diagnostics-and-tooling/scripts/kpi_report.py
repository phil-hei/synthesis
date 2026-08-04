#!/usr/bin/env python3
"""Kompakter KPI-Bericht aus kpis.json (+ sumo/summary.json, falls vorhanden).

Aufruf:  python3 kpi_report.py <szenario-dir | kpis.json-pfad>
Liest artifacts/simulation/kpis.json (Producer: SimulationResultIngestor.writeKpis)
und artifacts/sumo/summary.json (Producer: GenerationService.writeSumoSummary)
und druckt eine Tabelle inkl. Abweichung zu den MiD-2017-Ankern. Nur stdout.
"""
import json
import sys
from pathlib import Path

MODES = ["pedestrian", "bike", "car", "passenger", "publicTransport"]
# Anker MiD 2017 bundesweit (Stand 2026-07); kpis.json trägt sie auch selbst unter "mid2017"
MID_FALLBACK = {
    "tripsPerPersonDay": 3.1,
    "avgTripKm": 12.5,
    "modalSplit": {"pedestrian": 0.22, "bike": 0.11, "car": 0.43,
                   "passenger": 0.14, "publicTransport": 0.10},
}

KPIS_CANDIDATES = ["artifacts/simulation/kpis.json", "simulation/kpis.json", "kpis.json"]
SUMO_CANDIDATES = ["artifacts/sumo/summary.json", "sumo/summary.json", "summary.json"]


def find(base: Path, candidates):
    for rel in candidates:
        cand = base / rel
        if cand.is_file():
            return cand
    return None


def load(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as e:
        sys.exit(f"FEHLER: {path} nicht lesbar: {e}")


def fmt_row(name, value, anchor=None):
    if anchor is None:
        print(f"  {name:<24} {value}")
    else:
        try:
            delta = f"{float(value) - float(anchor):+.2f}"
        except (TypeError, ValueError):
            delta = "-"
        print(f"  {name:<24} {value:>8}   (MiD {anchor}, Δ {delta})")


def main():
    if len(sys.argv) != 2:
        sys.exit("Aufruf: python3 kpi_report.py <szenario-dir | kpis.json-pfad>")
    arg = Path(sys.argv[1])
    if arg.is_file():
        kpis_path = arg
        base = arg.parent
    else:
        kpis_path = find(arg, KPIS_CANDIDATES)
        base = arg
    if kpis_path is None:
        sys.exit(f"FEHLER: keine kpis.json unter {arg} gefunden "
                 f"(gesucht: {', '.join(KPIS_CANDIDATES)}). "
                 "Lief die Stufe 'Simulation (logiTopp)' überhaupt (nicht SKIPPED)?")

    kpis = load(kpis_path)
    mid = kpis.get("mid2017", MID_FALLBACK)
    demand = kpis.get("demand", {})
    parcels = kpis.get("parcels", {})

    print(f"Quelle: {kpis_path}")
    print("\nNachfrage (logiTopp/mobiTopp)")
    fmt_row("Trips", demand.get("trips", "-"))
    fmt_row("Personen", demand.get("persons", "-"))
    fmt_row("Trips/Person/Tag", demand.get("tripsPerPersonDay", "-"),
            mid.get("tripsPerPersonDay"))
    fmt_row("Ø Weglänge (km)", demand.get("avgTripKm", "-"), mid.get("avgTripKm"))
    fmt_row("Sim-Dauer (s)", kpis.get("simulationSeconds", "-"))

    print("\nModal-Split (Anteil Wege)")
    split = demand.get("modalSplit", {})
    mid_split = mid.get("modalSplit", MID_FALLBACK["modalSplit"])
    for m in MODES + [k for k in split if k not in MODES]:
        v = split.get(m)
        a = mid_split.get(m)
        if v is None:
            continue
        d = f"{v - a:+.2f}" if a is not None else "-"
        print(f"  {m:<24} {v:>8.2f}   (MiD {a if a is not None else '-'}, Δ {d})")

    if parcels:
        print("\nPakete (logiTopp)")
        fmt_row("Bestellt", parcels.get("total", "-"))
        fmt_row("Zugestellt", parcels.get("delivered", "-"))
        fmt_row("Zustellquote", parcels.get("deliveryRate", "-"))

    sumo_path = find(base, SUMO_CANDIDATES)
    if sumo_path:
        s = load(sumo_path)
        print(f"\nSUMO (Quelle: {sumo_path})")
        fmt_row("Fahrzeuge simuliert", s.get("vehicles", "-"))
        fmt_row("Ø Fahrtdauer (min)", s.get("meanDurationMin", "-"))
        fmt_row("Ø Routenlänge (km)", s.get("meanRouteLengthKm", "-"))
        fmt_row("Ø Geschwindigkeit km/h", s.get("meanSpeedKmh", "-"))
        fmt_row("PKW/Rad/Fuß-Trips", f"{s.get('carTrips', '-')}/{s.get('bikeTrips', '-')}"
                                     f"/{s.get('walkTrips', '-')}")
        fmt_row("Lieferverkehr-Legs", s.get("deliveryTrips", "-"))
        fmt_row("ÖV Fahrgäste/Fahrten", f"{s.get('ptRiders', '-')}/{s.get('ptVehicles', '-')}")
        fmt_row("CO2 gesamt (kg)", s.get("co2Kg", "-"))
        for vtype, kg in (s.get("co2KgByType") or {}).items():
            fmt_row(f"  CO2 {vtype} (kg)", kg)
        fmt_row("SUMO-Laufzeit (s)", s.get("runtimeSeconds", "-"))
    else:
        print("\nSUMO: kein sumo/summary.json gefunden (Stufe 'SUMO (Verkehr)' SKIPPED/FAILED "
              "oder Zielmodell nicht gewählt)")


if __name__ == "__main__":
    main()
