#!/usr/bin/env python3
"""Distanzprofil der Nachfrage-Trips aus demandsimulationResult.csv (read-only).

Aufruf:  python3 distance_profile.py <szenario-dir | csv-pfad>
Akzeptiert einen Szenario-Ordner (sucht die CSV in bekannten Unterpfaden)
oder direkt den Pfad zur demandsimulationResult.csv.
Filtert legId==0 (zählt jeden Weg genau einmal), liest distanceInKm und druckt
Ø/Median sowie ein Distanzband-Histogramm gesamt und je Modus. Nur stdout.
"""
import sys
import statistics
from pathlib import Path

# StandardMode-Codes aus SimulationResultIngestor.modeName / SumoRunner
MODE_NAMES = {"0": "bike", "1": "car", "2": "passenger", "3": "pedestrian", "4": "publicTransport"}
# Distanzbänder wie LlmModeChoiceRunner.BAND_MAX/BAND_LABEL
BAND_MAX = [1, 2, 5, 10, 20, float("inf")]
BAND_LABEL = ["<1 km", "1-2 km", "2-5 km", "5-10 km", "10-20 km", ">20 km"]
MID_AVG_KM = 12.5  # MiD 2017, bundesweit (Anker, Stand 2026-07)

CSV_CANDIDATES = [
    "artifacts/simulation/results/demandsimulationResult.csv",  # Szenario-Ordner
    "simulation/results/demandsimulationResult.csv",            # artifacts-Ordner
    "results/demandsimulationResult.csv",                       # z. B. validation/.../output/synthesis
    "demandsimulationResult.csv",                               # results-Ordner
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


def read_trips(csv_path: Path):
    """Liefert Liste (mode_name, km) je Trip (legId==0, km>0)."""
    trips = []
    with csv_path.open(encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split(";")
        idx = {name.strip(): i for i, name in enumerate(header)}
        i_leg = idx.get("legId", 1)
        i_mode = idx.get("tripMode", 12)
        i_km = idx.get("distanceInKm", 16)
        for line in f:
            if not line.strip():
                continue
            c = line.rstrip("\n").split(";")
            if len(c) <= max(i_leg, i_mode, i_km) or c[i_leg] != "0":
                continue
            try:
                km = float(c[i_km])
            except ValueError:
                continue
            if km <= 0:
                continue
            trips.append((MODE_NAMES.get(c[i_mode], "other"), km))
    return trips


def band(km: float) -> int:
    for i, mx in enumerate(BAND_MAX):
        if km < mx:
            return i
    return len(BAND_MAX) - 1


def print_hist(label, kms, total_width=30):
    counts = [0] * len(BAND_MAX)
    for km in kms:
        counts[band(km)] += 1
    n = len(kms)
    print(f"\n{label}: {n} Trips, Ø {statistics.fmean(kms):.2f} km, "
          f"Median {statistics.median(kms):.2f} km")
    for lbl, cnt in zip(BAND_LABEL, counts):
        share = cnt / n if n else 0
        bar = "#" * round(share * total_width)
        print(f"  {lbl:>9}  {cnt:>7}  {share * 100:5.1f} %  {bar}")


def main():
    if len(sys.argv) != 2:
        sys.exit("Aufruf: python3 distance_profile.py <szenario-dir | csv-pfad>")
    csv_path = find_csv(sys.argv[1])
    trips = read_trips(csv_path)
    if not trips:
        sys.exit(f"FEHLER: keine Trips mit legId==0 und distanceInKm>0 in {csv_path}")

    print(f"Quelle: {csv_path}")
    all_km = [km for _, km in trips]
    print_hist("GESAMT", all_km)
    mean = statistics.fmean(all_km)
    print(f"\n  Anker MiD 2017: Ø {MID_AVG_KM} km  ->  Abweichung {mean - MID_AVG_KM:+.2f} km "
          f"({(mean / MID_AVG_KM - 1) * 100:+.0f} %)")
    print("  Hinweis: regional gebundene Läufe liegen hier erfahrungsgemäß bei ~3 km "
          "(bekannte Lücke, siehe Skill synthesis-distanz-kampagne).")

    by_mode = {}
    for mode, km in trips:
        by_mode.setdefault(mode, []).append(km)
    for mode in ("pedestrian", "bike", "car", "passenger", "publicTransport", "other"):
        if mode in by_mode:
            print_hist(mode, by_mode[mode])


if __name__ == "__main__":
    main()
