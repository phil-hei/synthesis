---
name: synthesis-distanz-kampagne
description: >
  Ausführbare, entscheidungs-gegatete Kampagne für DAS härteste offene Problem von SyntheSis:
  die Wege sind viel zu kurz (Ø ~2 km gemessen vs. MiD 12,5 km) — die Distanz-Sensitivität der
  Zielwahl (destination choice) dominiert. Laden, WENN du (a) an "Wege zu kurz", "Ø-Distanz",
  "Distanzverteilung", "destination choice", "Zielwahl kalibrieren", "MiD 12,5 km",
  "b_intrazonal", "b_0_1", "b_logsum", "externe Trips fehlen" arbeitest, (b) gefragt wirst,
  warum der Ø-Weg bei ~2–3 km klemmt, oder (c) einen Kalibrier-Ansatz für Distanzen bauen
  sollst. NICHT laden für Modal-Split-Kalibrierung (das ist ModeChoiceCalibrator, produktiv,
  siehe synthesis-config-and-flags) oder für allgemeine KPI-Interpretation
  (synthesis-diagnostics-and-tooling).
---

# Distanz-Kampagne: Zielwahl-Sensitivität kalibrieren

**Missionsziel:** Die simulierte Distanzverteilung messbar in Richtung realer Mobilität
(MiD 2017) verschieben, ohne Modal-Split und Wegehäufigkeit zu beschädigen. Erfolg wird
über Kennzahlen entschieden, nie per Augenschein.

**Begriffe (einmalig):** *Zielwahl/destination choice* = das Logit-Modell, das je Aktivität
die Zielzone wählt (Distanz geht als Disnutzen über Reisezeit-Logsums ein). *MiD 2017* =
bundesweite Mobilitätserhebung (Referenz: Ø 12,5 km/Weg, 3,1 Wege/Person·Tag).
*Logsum* = zusammengefasster Erreichbarkeits-Nutzen über alle Modi. *RMSE* = Wurzel der
mittleren quadrierten Abweichung (hier über Distanzband-Anteile).

## 0. Problemstand (Stand: 2026-07-10, selbst nachgemessen — MIT Kontaminations-Vorbehalt)

Reale Messung auf `validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv`
(70 188 Trips, via `distance_profile.py` aus synthesis-diagnostics-and-tooling, 2026-07-10):

| Kennzahl | Gemessen (dieses CSV) | Geroutete Referenz (docs/05) | MiD-Anker |
|---|---|---|---|
| Ø Distanz | 2,00 km | **~2,9 km** | 12,5 km (in 6×5-km-Region unerreichbar) |
| Median | 1,53 km | — | — |
| Trips > 10 km | 0 | — | substanzieller Anteil |
| Fuß-Anteil | 51,9 % | **~35 %** | 22 % |
| Wege/Person·Tag | **2,38** (70 188/4 207/7) | ~3,1–3,3 (überliefert) | 3,1 |

**⚠ Kontaminations-Vorbehalt (Review-Befund 2026-07-10):** Ø 2,0 km + Fuß ~51 % ist exakt
die in docs/05 dokumentierte **Luftlinien-Fallback-Signatur** (Overpass-Ausfall ⇒ Routing
fällt auf Luftlinie zurück; geroutet wären ~2,9 km / Fuß ~35 %). Das vorgefundene CSV ist
also mutmaßlich ein kontaminierter Lauf — genau die „Fallback-Kontamination", vor der
synthesis-research-methodology warnt. Konsequenz: **Die verbindliche Baseline entsteht erst
in Phase 0 aus einem nachweislich GEROUTETEN Lauf** (Job-Log ohne „Fallback auf Luftlinie").
Erwartung dafür: Ø ≈ 2,9–3 km, Fuß ≈ 35 %. Auch die niedrige Wegehäufigkeit (2,38) dieses
CSVs ist auf dem sauberen Lauf neu zu messen, bevor sie als Problem gilt.

Unabhängig davon gilt: Selbst die geroutete Referenz (~2,9 km) liegt weit unter jeder
realistischen Distanzverteilung. Ursache-Hypothese (durch Befunde gestützt): das
Rastatt-geschätzte Zielwahl-Modell belohnt Nähe massiv (Kurzdistanz-Boni) und bestraft
Distanz über Reisezeit-Betas.

## Eingezäunte Irrwege — NICHT erneut versuchen

| Irrweg | Beleg | Status |
|---|---|---|
| Ferne externe Zonen mit hoher Attraktivität | 25 km → **0 von 124k Trips** gewählt; 12 km + Faktor 4,0 → nur ~3,3 % external, Ø 3,05→3,65 km (2026-06-12, docs/06) | ENTSCHIEDEN: wirkungslos gegen den Disnutzen |
| Distanz per GTA-/LLM-Prompt steuern | Zielwahl trifft logiTopp, nicht das LLM; Qwen ignoriert quantitative Vorgaben (belegt an der Moduswahl, 2026-06-26) | ENTSCHIEDEN |
| Roh-Ziel „12,5 km" in kleiner Region | Geometrisch unmöglich (Bbox-Diagonale ~8 km) | Ziel = **Verteilungs-Form + externer Anteil**, nicht Roh-Mittelwert |
| Neuschätzung mit Erhebungs-Mikrodaten | MiD/MOP zugangsbeschränkt, nicht on-demand | Ausblick, kein Kampagnenpfad |

## Die Hebel (verifiziert am 2026-07-10)

Die Zielwahl-Parameter liegen in `validation/mobitopp-rastatt/config/shared/parameters/`
und sind in der Run-Config unter `destinationChoice:` verdrahtet
(`SimulationRunner.writeRunConfig`, backend/src/main/java/de/synthesis/generation/pipeline/SimulationRunner.java):

| Datei | Inhalt | Distanz-relevante Parameter |
|---|---|---|
| `destination_choice_utility_calculation_parameters.txt` (base) | Reisezeit-/Kosten-Betas je Modus | `b_tt_car_d = -0.0688 -0.05`, `b_tt_ped = -0.1149 -0.05`, `b_cost = -0.1283` … (negativer = distanzfeindlicher) |
| `destination_choice_parameters_SHOPPING.txt` (analog LEISURE/SERVICE/BUSINESS) | Zweck-spezifische Boni | **`b_intrazonal = 2.248`** (Intrazonal-Bonus!), **`b_0_1`**, **`b_1_2`** (0–1-/1–2-km-Band-Boni), `b_logsum_drive/pt` (Erreichbarkeitsgewichte), `b_attr` |
| `EducationDestinationParameters.txt` | Bildungs-Zielwahl | separat |

Format-Eigenheit: Werte sind **additive Terme** (`0.929 -0.2 -0.1` = Summe) — der Parser
summiert; Anpassungen können als zusätzlicher Term angehängt werden (gleiches Muster wie
die MNL-Datei, die `ModeChoiceCalibrator` patcht).

**Wichtig:** Der `destinationChoice:`-Block in `SimulationRunner.writeRunConfig` ist
aktuell HART auf die Shared-Dateien verdrahtet (nur `modeChoice.MNL` ist parametrisierbar,
Parameter `modeChoiceMnl` der 4-Arg-`run(...)`-Methode). → Phase 2a unten.

## Kampagnen-Phasen

### Phase 0 — Saubere Baseline herstellen (Pflicht vor allem anderen)

**Schritt 0a — Kontaminations-Check ZUERST:** Frisches Szenario generieren (seed 42,
useRealRouting=true, Ziel logitopp; synthesis-run-and-operate) und im Job-Log prüfen:

```bash
grep -i "Luftlinie\|Fallback" <job-log bzw. Generierungs-Protokoll>
```

Kein Fallback-Treffer ⇒ der Lauf ist geroutet und taugt als Baseline. Bei Treffer
`"Routing nicht verfügbar (request timed out) – Fallback auf Luftlinie"`: das
serverseitige Overpass-Query-Budget für den Straßennetz-Abruf war zu knapp — seit
2026-07-12 steuerbar über `synthesis.overpass.road-network-timeout-seconds` (Default 180;
notfalls höher setzen oder die Region verkleinern). NICHT blind wiederholen (schwere
Overpass-Queries; synthesis-debugging-playbook #2/#3). Ein CSV OHNE zugehöriges Job-Log
ist als Baseline UNZULÄSSIG.

**Schritt 0b — Messen:**

```bash
python3 .claude/skills/synthesis-diagnostics-and-tooling/scripts/distance_profile.py \
  <szenario-dir bzw. results/demandsimulationResult.csv>
```

**Erwartung (gerouteter Lauf):** Ø ≈ 2,9–3 km, Fuß ≈ 35 %, Bänder massiv <5 km,
praktisch keine Trips >10 km.
- **Siehst du Ø ≈ 2,0 km UND Fuß ≈ 50 %** → Luftlinien-Signatur: zurück zu 0a, der Lauf
  ist kontaminiert (Achtung: das vorgefundene validation-CSV zeigt genau diese Signatur,
  siehe §0).
- **Siehst du Ø > 5 km** → andere Region/Konfiguration als erwartet: Szenario-Parameter
  prüfen (Region-Bbox, useRealRouting).
- **Datei fehlt** → erst einen Pipeline-Lauf mit Ziel logitopp fahren
  (synthesis-run-and-operate).

Notiere: Ø, Median, Band-Anteile (<1 / 1–2 / 2–5 / 5–10 / 10–20 / >20 km — das
6-Band-Schema von distance_profile.py) je gesamt und je Modus, plus Wege/Person·Tag.
Das ist deine Vorher-Messung.

### Phase 1 — Parameter-Oberfläche bestätigen

```bash
ls validation/mobitopp-rastatt/config/shared/parameters/ | grep -i dest
grep -n "b_intrazonal\|b_0_1\|b_1_2\|b_logsum" \
  validation/mobitopp-rastatt/config/shared/parameters/destination_choice_parameters_SHOPPING.txt
```

**Erwartung:** Die 5 Zielwahl-Dateien + die genannten Parameter existieren (Stand 2026-07-10
bestätigt). **Gate:** Du kannst je Zweck-Datei die Kurzdistanz-Boni und Logsum-Gewichte
benennen. Falls Dateien fehlen → validation/ unvollständig (synthesis-build-and-env).

### Phase 2a — Override-Mechanismus ✅ BEREITS UMGESETZT (2026-07-10)

Erledigt: `SimulationRunner.run(scenario, artifacts, log, modeChoiceMnl, destinationChoiceDir)`
existiert. Ist `destinationChoiceDir` gesetzt, zeigen die fünf `destinationChoice:`-Pfade der
Run-Config auf gleichnamige Dateien in diesem Verzeichnis (absolut); `null` = Projekt-Standard.
`SimulationRunnerTest` beweist beide Fälle + dass die Moduswahl unberührt bleibt. **Du musst hier
nichts mehr bauen** — nur ein Verzeichnis mit gepatchten Kopien der 5 Zielwahl-Dateien anlegen und
`run(..., null, dcDir)` aufrufen (bzw. es aus einem Probe-/Kalibrier-Harness heraus tun). Der
Aufruf aus GenerationService (Phase 3) fehlt noch — bis dahin per Test-/Scratch-Harness ansteuern.

### Phase 2b — Elastizitäts-Sonde (Zahlen VOR dem Lauf!)

Schreibe die Vorhersage auf (synthesis-research-methodology-Vorlage), z. B.:
„`b_intrazonal` (SHOPPING) um −1,0 reduziert ⇒ Intrazonal-Anteil der Einkaufswege sinkt
deutlich, Ø-Distanz gesamt steigt um grob +5–15 %."

Dann: alle 5 Zielwahl-Dateien nach Scratch kopieren, in der SHOPPING-Kopie EINEN Term
anhängen (`b_intrazonal = 2.24786362258153 -1.0`), Einzellauf über den Phase-2a-Override
(`run(..., null, dcDir)`), danach Phase-0-Messung wiederholen. **Baseline und Sonde MÜSSEN
dieselben Matrizen/denselben Legacy-Export teilen** (nur EIN Unterschied = der Parameter) —
den Export aus dem sauberen Phase-0-Lauf wiederverwenden, NICHT neu generieren. Läuft am
schnellsten bei `samplingFraction` 0,05 (logiTopp in Sekunden, wie bei der Modal-Split-
Kalibrierung). Merke: der Legacy-Export persistiert nicht in `output/synthesis/` — er lebt im
`artifacts/`-Ordner des Szenarios; von dort kopieren, bevor er gereapt wird.

**Gate:** Änderung in vorhergesagter Richtung (Ø ↑, `<1 km`-Anteil ↓).
- **Keine Änderung** → Override greift nicht: `simulation/run-config.yaml` des Laufs
  öffnen und prüfen, ob die destinationChoice-Pfade wirklich auf deine Kopien zeigen;
  `run.log` auf Parse-Fehler. Danach: falscher Parameter → nächsten Hebel testen
  (Reihenfolge: `b_intrazonal` → `b_0_1`/`b_1_2` → `b_tt_*` im base-File).
- **Änderung entgegen Vorhersage** → Vorzeichen-/Summenformat-Verständnis prüfen (additive
  Terme!), Mechanismus neu formulieren, Sonde wiederholen.

**✅ ERGEBNIS (2026-07-12, bereits gemessen — nicht neu ausführen):** Sonde gelaufen auf
Export `0e020eab` (seed 42, alle 4 Zweck-Dateien `b_intrazonal` additiv gesenkt), dosis-
abhängig monoton: Ø 2,65 → 2,80 (−1,0) → 2,94 km (−3,0); `<1 km`-Anteil 25,0 → 18,1 → 9,4 %.
Hebel **bestätigt**. Grenze: absolute Ø-Decke ~2,9 km bleibt (Gebietsgröße — Kalibrierung
formt die Bänder, erzeugt keine Fernwege). logiTopp-Läufe je ~5 s (warmer Daemon). Damit ist
Phase 3 empirisch gerechtfertigt.

### Phase 3 — DistanceCalibrator implementieren

Blaupause: `ModeChoiceCalibrator` (backend/.../pipeline). Übernimm wörtlich dessen Struktur:
- Ziel: konfigurierbare Distanzband-Verteilung (Default: MiD-Form auf Regionsgröße
  skaliert; Ziel-Definition ist Teil der Arbeit — dokumentiere die Herleitung).
- Update-Regel je Iteration mit **Clamp** (Vorbild ±1,5) auf den gewählten Hebel-Termen;
  ein Anker-Parameter bleibt fix (Identifikation!, Vorbild `asc_car_d`).
- Konvergenz: Band-RMSE < 0,05 oder max. 8 Iterationen.
- Flag `population.calibrateDistance` default=false (Scenario.java + scenario.model.ts +
  Studio-Checkbox — Checkliste in synthesis-config-and-flags).
- Unit-Test nach `ModeChoiceCalibratorTest`-Muster (Fake-Sim statt logiTopp; beachte
  dessen Clamp-Semantik: Datei hält Zustand VOR dem letzten Update).

### Phase 4 — Joint-Validierung & Beförderung

Erfolg NUR wenn alle drei gleichzeitig (über ≥3 Seeds):
1. Distanzband-RMSE zum Ziel sinkt unter die vorab notierte Schwelle,
2. Modal-Split-RMSE zu MiD verschlechtert sich um höchstens 0,02,
3. Wege/Person·Tag bleibt in [3,0; 3,4].

Messung ausschließlich per Skripte (distance_profile.py, modal_split.py, kpi_report.py).
Beförderung über synthesis-change-control (Tests grün, docs/06-✅-Eintrag mit
vorher→nachher-Zahlen, Flag bleibt opt-in bis Partner-Feedback).

**Abbruchkriterium Richtung Option B:** Wenn nach sauberer Sonde + 2 Kalibrier-Anläufen
die Band-RMSE nicht unter das Doppelte der Schwelle fällt, ist die Region zu klein —
dann Untersuchungsgebiet vergrößern (Kosten: Matrizen n², `synthesis.max-zones` 600,
OOM-Grenze ~12 GB logiTopp-JVM) und Kampagne auf dem größeren Gebiet wiederholen.

## Wann diesen Skill NICHT nutzen

| Situation | Stattdessen |
|---|---|
| Modal-Split kalibrieren | `calibrateModeChoice` existiert produktiv → synthesis-config-and-flags |
| KPIs nur interpretieren | synthesis-diagnostics-and-tooling |
| Frage „wurde X schon probiert?" | synthesis-failure-archaeology |
| Prozess-/Evidenzfragen | synthesis-research-methodology |

## Provenienz & Wartung (Stand: 2026-07-10)

Verifiziert am 2026-07-10: Parameterdateien + Inhalte (ls/cat oben), `destinationChoice:`-
Verdrahtung (grep in SimulationRunner.java Z. 106–114), Baseline-Messung (distance_profile.py,
70 188 Trips). Überliefert: Externe-Zonen-Befunde (docs/06, 2026-06-12), Kalibrier-Blaupause-
Kennwerte (docs/06, 2026-06-21).

Re-Verifikation:
```bash
ls validation/mobitopp-rastatt/config/shared/parameters/ | grep -i dest
grep -n "destinationChoice" backend/src/main/java/de/synthesis/generation/pipeline/SimulationRunner.java
grep -n "calibrateDistance" backend/src/main/java/de/synthesis/scenario/Scenario.java  # leer = Phase 3 noch offen
python3 .claude/skills/synthesis-diagnostics-and-tooling/scripts/distance_profile.py validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv
```
