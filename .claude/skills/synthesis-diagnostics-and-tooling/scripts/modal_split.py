#!/usr/bin/env python3
"""Modal-Split direkt aus demandsimulationResult.csv – unabhängige Kontrolle zu kpis.json.

Aufruf:  python3 modal_split.py <szenario-dir | csv-pfad>
Zählt Trips (legId==0) je tripMode, druckt den Modal-Split und vergleicht mit
1) kpis.json (demand.modalSplit), falls im Szenario-Ordner auffindbar, und
2) dem MiD-2017-Anker. Nur stdout, keine Schreibzugriffe.
"""
import json
import math
import sys
from pathlib import Path

MODE_NAMES = {"0": "bike", "1": "car", "2": "passenger", "3": "pedestrian", "4": "publicTransport"}
MODES = ["pedestrian", "bike", "car", "passenger", "publicTransport"]
# MiD 2017 bundesweit (Anker, Stand 2026-07; identisch zu SimulationResultIngestor.MID_MODAL_SPLIT)
MID = {"pedestrian": 0.22, "bike": 0.11, "car": 0.43, "passenger": 0.14, "publicTransport": 0.10}

CSV_CANDIDATES = [
    "artifacts/simulation/results/demandsimulationResult.csv",
    "simulation/results/demandsimulationResult.csv",
    "results/demandsimulationResult.csv",
    "demandsimulationResult.csv",
]
KPIS_CANDIDATES = [
    "artifacts/simulation/kpis.json",
    "simulation/kpis.json",
    "kpis.json",
]


def find_csv(arg: str) -> Path:
    p = Path(arg)
    if p.is_file():
        return p
    for rel in CSV_CANDIDATES:
        cand = p / rel
        if cand.is_file():
            return cand
    sys.exit(f"FEHLER: keine demandsimulationResult.csv unter {arg} gefunden "
             f"(gesucht: {', '.join(CSV_CANDIDATES)})")


def find_kpis(arg: str):
    p = Path(arg)
    base = p if p.is_dir() else p.parent
    for rel in KPIS_CANDIDATES:
        cand = base / rel
        if cand.is_file():
            return cand
    return None


def count_modes(csv_path: Path):
    counts = {}
    total = 0
    with csv_path.open(encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split(";")
        idx = {name.strip(): i for i, name in enumerate(header)}
        i_leg = idx.get("legId", 1)
        i_mode = idx.get("tripMode", 12)
        for line in f:
            if not line.strip():
                continue
            c = line.rstrip("\n").split(";")
            if len(c) <= max(i_leg, i_mode) or c[i_leg] != "0":
                continue
            mode = MODE_NAMES.get(c[i_mode], "other")
            counts[mode] = counts.get(mode, 0) + 1
            total += 1
    return counts, total


def rmse(a: dict, b: dict) -> float:
    """Wie GenerationService.rmse: über die 5 Standard-Modi."""
    return math.sqrt(sum((a.get(m, 0.0) - b.get(m, 0.0)) ** 2 for m in MODES) / len(MODES))


def main():
    if len(sys.argv) != 2:
        sys.exit("Aufruf: python3 modal_split.py <szenario-dir | csv-pfad>")
    csv_path = find_csv(sys.argv[1])
    counts, total = count_modes(csv_path)
    if total == 0:
        sys.exit(f"FEHLER: keine Trips (legId==0) in {csv_path}")
    split = {m: counts.get(m, 0) / total for m in MODES}
    if "other" in counts:
        split["other"] = counts["other"] / total

    kpis_path = find_kpis(sys.argv[1])
    kpis_split = {}
    if kpis_path:
        try:
            kpis_split = json.loads(kpis_path.read_text(encoding="utf-8")) \
                .get("demand", {}).get("modalSplit", {})
        except (json.JSONDecodeError, OSError) as e:
            print(f"WARNUNG: kpis.json nicht lesbar ({e})")

    print(f"Quelle CSV : {csv_path}  ({total} Trips, legId==0)")
    print(f"Quelle KPIs: {kpis_path if kpis_path else '- (kpis.json nicht gefunden)'}")
    header = f"{'Modus':<16} {'CSV':>7} {'kpis.json':>10} {'MiD 2017':>9} {'Δ MiD':>7}"
    print("\n" + header)
    print("-" * len(header))
    for m in MODES + (["other"] if "other" in split else []):
        csv_v = split.get(m, 0.0)
        kpi_v = f"{kpis_split[m]:>10.2f}" if m in kpis_split else f"{'-':>10}"
        mid_v = f"{MID[m]:>9.2f}" if m in MID else f"{'-':>9}"
        d_mid = f"{csv_v - MID[m]:>+7.2f}" if m in MID else f"{'-':>7}"
        print(f"{m:<16} {csv_v:>7.3f} {kpi_v} {mid_v} {d_mid}")

    print(f"\nRMSE zu MiD 2017: {rmse(split, MID):.3f}  (Kalibrierungs-Ziel: <0.02, "
          "siehe synthesis-validation-and-qa)")
    if kpis_split:
        d = rmse(split, kpis_split)
        print(f"RMSE zu kpis.json: {d:.3f}"
              + ("  -> konsistent" if d < 0.01 else
                 "  -> WEICHT AB: kpis.json stammt evtl. von einem anderen Lauf"))


if __name__ == "__main__":
    main()
