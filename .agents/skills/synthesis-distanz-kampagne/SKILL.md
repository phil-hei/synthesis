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
python3 .agents/skills/synthesis-diagnostics-and-tooling/scripts/distance_profile.py \
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

### Phase 3 — DistanceCalibrator ✅ UMGESETZT + VALIDIERT (2026-07-12)

Erledigt (`backend/.../pipeline/DistanceCalibrator.java`, Muster `ModeChoiceCalibrator`):
- **Zielgröße** = `<1 km`-Wegeanteil (EIN Ziel, EINE Stellgröße — bewusst kein Multi-Band, das
  wäre über-determiniert mit nur `b_intrazonal`). **Stellgröße** = `b_intrazonal` in allen 4
  Zweck-Dateien. **Update** `Δb = 3·ln(Ziel/Ist)`, Clamp ±2/Iteration; die Konstante 3 ≈ 1/0,32
  ist die inverse gemessene Elastizität.
- Läuft über den `destinationChoiceDir`-Override (Phase 2a), patcht nach
  `artifacts/simulation/dc-calibrated/`. Flag `population.calibrateDistance` default=false; Ziel
  `synthesis.simulation.distance-calibration-target-near-share` (Default **0,21** aus MiD 2017
  Tabelle A W12: 10 % `<0,5 km` + 11 % `0,5–<1 km`; tunbar).
- `DistanceCalibratorTest` (Fake-Sim-Konvergenz von oberhalb UND unterhalb des Ziels +
  Clamp-Semantik: Datei hält Zustand VOR dem letzten Update) grün. Studio-Checkbox vorhanden.
- **Mechanik End-to-End-validiert:** Die historische Einzelprüfung brachte `<1 km` mit dem
  später verworfenen Näherungswert 0,14 von 0,250 → 0,142. Die präregistrierte
  Drei-Seed-Wiederholung vom 2026-07-28 validiert den amtlichen Default 0,21:
  0,1515→0,1949 / 0,1605→0,2022 / 0,1552→0,1970, jeweils nach drei Iterationen.

*Offen für später:* Multi-Band-Ziel (Hebel `b_0_1`/`b_1_2` ergänzen) und die
absolute Ø-Decke als Gebietsgrößen-Problem (Kampagnen-Option B). Die Komposition
mit `calibrateModeDistance` ist umgesetzt: zuerst Zielwahl (`b_intrazonal`),
danach ÖV-Niveau/-Schräge (`asc_put`/`b_tt_put`) auf demselben
Zielwahl-Override. Der kombinierte Realrouting-Lauf vom 2026-07-29 wurde nach
Seed 42 gestoppt: Bei 2,3-km-Zellen liegt der intrazonale Pkw-Matrixwert mit
1,62 km außerhalb des `<1 km`-Zielbands, der Regler ist dort nicht
identifizierbar. Solche Konfigurationen werden inzwischen vor dem Graphaufbau
abgelehnt. Die reine `calibrateModeChoice`-Schleife ist weiterhin eine
Alternative.

### Phase 4 — Joint-Validierung & Beförderung

Erfolg NUR wenn alle drei gleichzeitig (über ≥3 Seeds):
1. Distanzband-RMSE zum Ziel sinkt unter die vorab notierte Schwelle,
2. Modal-Split-RMSE zu MiD verschlechtert sich um höchstens 0,02,
3. Wege/Person·Tag bleibt in [3,0; 3,4].

Messung ausschließlich per Skripte (distance_profile.py, modal_split.py, kpi_report.py).
Beförderung über synthesis-change-control (Tests grün, docs/06-✅-Eintrag mit
vorher→nachher-Zahlen, Flag bleibt opt-in bis Partner-Feedback).

**🔬 Drei-Seed-Gate 2026-07-27 gescheitert:** Der Lauf mit dem inzwischen verworfenen
Näherungsziel 0,14 senkte `<1 km` im Mittel 15,6→14,7 %, verfehlte aber den amtlich aus
MiD A W12 aggregierten Sechs-Band-Vektor `[21, 14, 23, 16, 13, 13] %`: B-RMSE
0,1019–0,1094 bei vorab gesetztem Ziel ≤0,05; Seed 43 verschlechterte sich um 0,0080.
Kein Default-Flip.

**🔬 Drei-Seed-Wiederholung 2026-07-28 mit Default 0,21: Mechanik bestanden,
Beförderung gescheitert.** `<1 km` steigt je Seed in das vorab definierte Fenster 19–23 %
(19,49/20,22/19,70 %); der Sechs-Band-RMSE verbessert sich
0,1055→0,0946 / 0,1014→0,0919 / 0,1019→0,0912. Modal-RMSE-Verschlechterung
≤0,0048, Wege/P/Tag 3,19–3,26, keine ausgelösten Fallbacks. Der absolute Gate ≤0,05
scheitert dennoch in allen Seeds, weil ≥20-km-Wege mit 0,29–0,43 % statt 13 % fast fehlen.
`calibrateDistance` bleibt opt-in. Nächster Schritt: langes Ende über größeren Raum und/oder
gravitationskonsistente externe Ziele, danach Multi-Band-Hebel.

**🔬 Modus×Distanz-Kalibrierung 2026-07-28: Mechanik bestanden, Beförderung
gescheitert.** Auf demselben Großraum-Export reagierte ÖV ab 20 km streng monoton auf
`b_tt_put=-0,08/-0,10/-0,12` (71,03/23,16/3,50 %); ein Koeffizient konnte globales
ÖV-Niveau und Distanzschräge nicht zugleich treffen. Die präregistrierte Zwei-Hebel-
Kombination `b_tt_put=-0,11`, `asc_put=-1,22` traf beide Ziele nahezu:
ÖV gesamt 9,34 % (MiD 10 %), ÖV ab 20 km 17,60 % (MiD 17,61 %),
Modal-RMSE 0,105742→0,085375, Joint-RMSE 0,054671→0,053002. Wegezahl und
Distanzbänder blieben stabil. Der Promotions-Gate Joint-RMSE <0,05 und Verbesserung
≥0,004 scheitert dennoch, weil die Distanzmarginale selbst falsch bleibt (0 % statt
21 % unter 1 km). **Regel:** `b_tt_put` (Schräge) nie allein kalibrieren; mit `asc_put`
(Niveau) komponieren, aber keine Werte vor Drei-Seed-/Realrouting-/Distanzmarginal-
Validierung übernehmen.

**❌ Gemeinsame Realrouting-Kampagne 2026-07-29 nach Seed 42 gestoppt.**
A/B verwendeten byte-identische GraphHopper-Matrizen, aber bei 2,3-km-Zellen
bildet `CAR_DIS` intrazonal 1,62 km ab. Acht `b_intrazonal`-Iterationen mit
Delta 0→14 ließen `<1 km` bei 0,0 %, während 1–<2 km auf 81,54 % stieg.
Distanzband-RMSE verschlechterte sich 0,1343→0,3072, Joint-RMSE
0,0438→0,0857. Der Modus-Distanz-Regler traf seine direkten ÖV-Ziele
(9,28 % gesamt, 18,89 % ab 20 km), konnte die Distanzmarginale aber nicht
heilen. **Regel:** Vor jeder Kampagne prüfen, ob der intrazonale Pkw-Matrixwert
im kalibrierten Band liegt. Der Produktcode erzwingt dies nun fail-fast; bei
`>=1 km` Zellgröße verkleinern oder die Distanzrepräsentation/Zielgröße
methodisch neu präregistrieren.

**✅ Distanz-Abklingen über drei Seeds bestätigt (2026-08-17):** Der kombinierte
Hebel `b_tt_* +0,06` plus nachgelagerter `b_intrazonal`-Kalibrierung erreicht für
Seeds 42/43/44 Distanzband-RMSE 0,0258/0,0240/0,0245 und besteht alle
vorregistrierten Schutzgates. Seed 43 wurde nach einem ungültigen Live-Overpass-A/B
als strikter Ein-Faktor-Kontrast auf demselben eingefrorenen Legacy-Export wiederholt
(43.084/43.076 Wege; D43: `<1 km` 22,15 %, `1–2 km` 13,53 %, `>=10 km` 21,04 %,
Modal-RMSE-Delta +0,006). Kein Default-Flip: Der `b_tt_*`-Hebel ist noch kein
Produktiv-Default. Das opt-in-Flag `population.correctDistanceDecay=false` erzeugt
inzwischen einen szenariogebundenen Parameterstand mit dem festen Term `+0,06` und
komponiert ihn vor `calibrateDistance`, Modus-Kalibrierungen und Netz-Feedback;
`>20 km` bleibt unter MiD und der Mehrregionen-Beleg steht aus.

**❌ Universelle Dosis in Freiburg widerlegt (2026-08-17):** Die vorab in
Commit `bc9a19a` festgelegte Transferprobe verwendete in T-C42/T-D42 byte-identische
OSM-, Attraktivitäts-, Populations-, Matrix-, Logistik- und Legacy-Eingaben; der
einzige Unterschied war `correctDistanceDecay=false→true`. Die Wirkungsrichtung
transferiert (`1–2 km` 18,35→11,90 %, `>=10 km` 10,16→14,00 %, Ø
4,58→5,38 km), aber der feste Rastatt-Term `+0,06` nicht als Kalibrierwert:
Distanzband-RMSE 0,0611→0,0644, absolutes Fernweg-Gate 20 % verfehlt und
`>20 km` nur 2,11→2,42 %. Nahband, Modal-Schutz und Wegefrequenz blieben intakt.
Stop-Regel: keine Seeds 43/44. **Regel:** `+0,06` nicht als universellen Wert
befördern oder in Freiburg bloß wiederholen; nächster zulässiger Anlauf ist eine
separat präregistrierte regionale Elastizitäts-Sonde beziehungsweise ein
hierarchisch/regional parametrisierter Hebel. Das Produktflag bleibt opt-in.

**❌ Gemeinsamer Offset an der Vorzeichengrenze ausgeschöpft (2026-08-17):**
Die in Commit `68b4baa` präregistrierte Freiburg-Sonde testete `+0,075` und
`+0,09` auf demselben eingefrorenen Legacy-Export mit produktivem Runner und
Nahkalibrierung. `+0,09` lässt `b_tt_put=-0,0006` gerade noch negativ; größere
gemeinsame Dosen würden die Reisezeit-Nutzensemantik umkehren. Trotzdem erreicht
die Obergrenze nur `>=10 km` 18,80 %, Distanzband-RMSE 0,0685 und `1–2 km`
8,29 %. **Regel:** Den gemeinsamen Offset in Freiburg nicht weiter erhöhen und
keine weiteren Seeds fahren. Die nächste Hypothese muss einzelne `b_tt_*`-Hebel
trennen oder regional/hierarchisch parametrisieren; wiederholtes Scannen eines
gemeinsamen Offsets ist eine entschiedene Sackgasse.

**❌ Pkw-spezifischer Split ebenfalls verworfen (2026-08-17):** Die in Commit
`f337233` präregistrierte Sonde ließ `put`/`ped`/`bike` auf `+0,06` und erhöhte
nur `car_d`/`car_p` auf `+0,09` beziehungsweise die sign-erhaltende Obergrenze
`+0,115`. Auf dem bytegleich reproduzierten Freiburg-Kontrollarm steigt
`>=10 km` stark und monoton 14,00→20,98→33,85 %, aber der Distanzband-RMSE
verschlechtert sich 0,0644→0,0675→0,0729 und `1–2 km` fällt auf 7,89/4,61 %.
Trotz unverändertem Zielwahl-`b_tt_put=-0,0306` wächst der realisierte ÖV-Anteil
24,86→29,29→37,83 %, weil die nachgelagerte Moduswahl die neu erreichbaren
Fernziele überproportional per ÖV bedient. **Regel:** Keine weiteren Seeds,
größeren Pkw-Offsets oder ungegründeten Scans anderer Modus-Teilmengen. Der nächste
zulässige Mechanismusversuch kontrolliert mehrere Distanzbänder explizit
(`b_1_2`-Elastizität auf PC-0090 plus separater Modal-Schutz) oder schätzt die
Parameter regional/hierarchisch.

**Option B — Gebietsgröße (✅ getestet 2026-07-12, These bestätigt):** Zwei kontrollierte
Offline-Läufe (je 4000 Personen, nur die Fläche unterschiedlich) → Ø 2,58 km (10 km) →
**4,95 km** (55 km), Fernwege >10 km 0 %→13,3 %. Fläche IST der Hebel für den absoluten Ø.
**ABER neue Grenze (inzwischen behoben, s. u.):** grobe Zonen (max-zones 600 erzwingt ~2,3-km-
Zellen über großer Fläche) + der damals **feste Intrazonal-Distanz-Boden 0,3 km** im
`MatrixGenerator` → Median-Kollaps auf 0,30 km, bimodale Verteilung. Die drei Hebel sind
**komplementär:** (1) größere Fläche (Fernwege), (2) feinere Zonen / zellgrößen-skalierter
Intrazonal-Boden (kurzes Ende), (3) `DistanceCalibrator` (Nahanteil).
Kosten großer Läufe: Matrizen n², OOM-Grenze ~12 GB logiTopp-JVM.

**✅ Intrazonal-Boden zellgrößen-skaliert (2026-07-16):** `MatrixGenerator` setzt für
intrazonale Paare jetzt die **erwartete Binnendistanz der Zelle** an
(Luftlinie = 0,5214 · √`areaKm2`; alle Modi erben konsistent — vorher hatte nur das Auto den
0,3-km-Boden, Fuß/Rad waren intrazonal 0 km/0 min → Fuß-Bias); intrazonal wird nicht mehr
geroutet. A/B-validiert (Vorhersage vorab, Offline-Läufe seed 42): großes Gebiet Median
0,30→3,10 km, `<1 km` 51,2→0,0 %, Bimodalität weg, Fuß 45,8→34,0 %; Standard-Gebiet (1-km-Zellen)
Ø 2,08→2,24 km, `<1 km` 31,8→29,1 %, Wegezahl exakt unverändert. **Achtung Baselines:** alle
Luftlinien-Kennzahlen vor 2026-07-16 (auch die Option-B-Zahlen oben und die
`DistanceCalibrator`-Ist-Werte) sind mit dem neuen Boden nicht mehr direkt vergleichbar;
der frühere Näherungs-Zielwert 0,14 ist seit 2026-07-27 durch den amtlichen Wert 0,21 ersetzt.
Details + vorher→nachher-Zahlen: docs/06 „Phase 1b" (Eintrag 2026-07-16), `MatrixGeneratorTest`.

## Wann diesen Skill NICHT nutzen

| Situation | Stattdessen |
|---|---|
| Modal-Split kalibrieren | `calibrateModeChoice` existiert produktiv → synthesis-config-and-flags |
| KPIs nur interpretieren | synthesis-diagnostics-and-tooling |
| Frage „wurde X schon probiert?" | synthesis-failure-archaeology |
| Prozess-/Evidenzfragen | synthesis-research-methodology |

## Provenienz & Wartung (Stand: 2026-08-17)

Verifiziert am 2026-07-10: Parameterdateien + Inhalte (ls/cat oben), `destinationChoice:`-
Verdrahtung (grep in SimulationRunner.java Z. 106–114), Baseline-Messung (distance_profile.py,
70 188 Trips). Überliefert: Externe-Zonen-Befunde (docs/06, 2026-06-12), Kalibrier-Blaupause-
Kennwerte (docs/06, 2026-06-21). MiD-Distanzziel: Tabellenband Deutschland, Tabelle A W12;
Drei-Seed-Gates und Zielwert-Korrektur: docs/05–06 (2026-07-27/28).
Realrouting-Stop-Gate und Auflösungsgrenze: docs/05–06 (2026-07-29).

Re-Verifikation:
```bash
ls validation/mobitopp-rastatt/config/shared/parameters/ | grep -i dest
grep -n "destinationChoice" backend/src/main/java/de/synthesis/generation/pipeline/SimulationRunner.java
grep -n "calibrateDistance" backend/src/main/java/de/synthesis/scenario/Scenario.java  # Erwartung: Feld default=false
python3 .agents/skills/synthesis-diagnostics-and-tooling/scripts/distance_profile.py validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv
```
