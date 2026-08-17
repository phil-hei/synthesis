---
name: synthesis-diagnostics-and-tooling
description: >
  MESSEN statt schätzen — Diagnose-Quellen von SyntheSis auswerten und interpretieren.
  Laden, WENN du (a) Ergebnis-Kennzahlen prüfen sollst („Modal-Split prüfen",
  „Distanzverteilung messen", „ist dieser Lauf plausibel?", „KPIs interpretieren"),
  (b) eine Ergebnisdatei lesen musst (kpis.json, sumo/summary.json, congestion.geojson,
  statistics.xml, llm-mode-choice.json, demandsimulationResult.csv — Spaltenindizes!),
  (c) das Job-Log deuten willst (Fallback-/Cache-/SKIPPED-Log-Strings wörtlich),
  oder (d) eine A/B-Messung aufsetzt. Enthält getestete Skripte: kpi_report.py,
  distance_profile.py, modal_split.py. NICHT laden für Fehler-Triage nach Symptom
  (synthesis-debugging-playbook), Freigabe-Schwellen (synthesis-validation-and-qa)
  oder Fachbegriffs-Bedeutung (verkehrsmodellierung-reference — dort wohnen auch
  die MiD-Ankerwerte).
---

# SyntheSis: Diagnose & Tooling

Dieser Skill beantwortet: „Was sagen mir die Ergebnisdateien eines Laufs — und
wie messe ich nach, statt zu schätzen?" Publikum: Mid-Level-Engineer, der das
Projekt nicht geschrieben hat.

**Grundregel:** Jede Aussage über einen Lauf kommt aus einer Datei oder einem
Skript-Output, nie aus dem Gedächtnis. Die Skripte unten sind read-only
(nur stdout, nur Python3-Stdlib, kein pandas, kein jq).

## Wann NICHT nutzen

- **Pipeline starten/betreiben, Läufe anstoßen** → `synthesis-run-and-operate`
  (dort steht, wo die hier interpretierten Dateien herkommen).
- **Was Kennzahlen fachlich bedeuten** (Modal-Split-Theorie, MiD-Methodik,
  Verkehrsmodell-Begriffe) → `verkehrsmodellierung-reference`.
- **Akzeptanz-/Freigabeschwellen** („ist 0.167 RMSE ok für Release?") →
  `synthesis-validation-and-qa`. Hier stehen nur Plausibilitäts-Anker.
- **Ursachenanalyse der Distanz-Lücke / Kampagnenplanung** →
  `synthesis-distanz-kampagne` (die nutzt `distance_profile.py` als Messwerkzeug).
- **Exceptions, Stacktraces, hängende Läufe** → `synthesis-debugging-playbook`.
- **Irgendetwas ändern/fixen** — dieser Skill misst nur. Änderungen laufen über
  `synthesis-change-control`.

## 1. Diagnose-Quellen-Katalog

Alle Pfade relativ zum Szenario-Ordner `backend/data/scenarios/<id>/`.
Strukturen sind aus den Producer-Klassen abgelesen (Ground Truth = Code).

| Datei | Producer-Klasse | Schlüssel/Spalten | Interpretation | Plausibilitäts-Anker (Stand 2026-07) |
|---|---|---|---|---|
| `artifacts/simulation/kpis.json` | `SimulationResultIngestor.writeKpis` | `simulationSeconds`; `demand.{trips, persons, tripsPerPersonDay, avgTripKm, modalSplit.{pedestrian,bike,car,passenger,publicTransport}}`; `parcels.{total, delivered, deliveryRate}`; `mid2017.{...}` (Referenz eingebettet) | Nachfrage-KPIs des logiTopp-Laufs. `tripsPerPersonDay` = trips/persons/7 (Modellwoche). `avgTripKm` = Summe `distanceInKm` über **alle** Zeilen ÷ Trips (legId==0) | MiD 2017: 3,1 Wege/Person·Tag; Ø 12,5 km; Modal-Split Fuß 0.22 / Rad 0.11 / Pkw 0.43 / Mitfahrer 0.14 / ÖV 0.10. Regional gebundene Läufe hier: Ø ~3 km — bekannte Lücke → `synthesis-distanz-kampagne` |
| `artifacts/simulation/depot-stats.json` | `SimulationResultIngestor.writeDepotStats` | Array: `name, cepsp, vehicleType, vehicles, parcelsAssigned, delivered, tours, distanceKm` | Logistik je Depot (aus fleet.csv + vehicle_events.csv) | `delivered` ≤ `parcelsAssigned`; `distanceKm` > 0 wenn Touren gefahren |
| `artifacts/simulation/parcel-density.geojson` | `SimulationResultIngestor.writeParcelDensity` | Zonen-Features + `properties.parcels` | Paketaufkommen je Zone | Summe `parcels` ≈ `kpis.parcels.total` |
| `artifacts/simulation/tours.geojson` | `SimulationResultIngestor.writeTours` | LineStrings, `properties.{depot, vehicleType, distanceKm, stops}` | Geplante Zustelltouren | — |
| `artifacts/simulation/results/demandsimulationResult.csv` | logiTopp/mobiTopp (Legacy-Sim) | siehe Abschnitt 2 | Roh-Trips; Quelle für alle drei Skripte | 70k Zeilen ≈ Rastatt-Lauf |
| `artifacts/sumo/summary.json` | `GenerationService.writeSumoSummary` | `vehicles, meanDurationMin, meanRouteLengthKm, meanSpeedKmh, runtimeSeconds, carTrips, bikeTrips, walkTrips, deliveryTrips, ptRiders, ptVehicles, co2Kg, co2KgByType.{pkw,fahrrad,lkw,lastenrad,...}` | Mikrosimulation: nur Fahrzeuge zählen in `vehicles`; Fuß/ÖV-Fahrgäste sind SUMO-`<person>` | `vehicles` ≲ carTrips+bikeTrips+deliveryTrips (Routing-Verluste normal); `meanSpeedKmh` innerorts ~20–40 |
| `artifacts/sumo/congestion.geojson` | `SumoRunner.writeCongestionGeoJson` | `properties.{edge, speedKmh, limitKmh, relSpeed, timeLossS, sampledS}` | Netzlast je befahrener Kante (sampledSeconds ≥ 1). `relSpeed` = min(1, gemessen/Limit) → Stau-Indikator: <0.5 = stark gestaut | Anzahl Features = Log-Zeile „N befahrene Kanten" |
| `artifacts/sumo/statistics.xml` | SUMO `statistic-output` (konfiguriert in `SumoRunner`) | `vehicleTripStatistics` (count/duration/routeLength/speed — genau diese liest `SumoRunner.parseStatistics`), `pedestrianStatistics`, `rideStatistics` | Aggregat der SUMO-Simulation; Quelle der summary.json-Mittelwerte | duration in s, routeLength in m, speed in m/s |
| `artifacts/simulation/llm-mode-choice.json` | `GenerationService.writeLlmModeChoice` (Daten: `LlmModeChoiceRunner`) | `trips, modalSplit, mid2017, logitoppMnl, rmseToMid, rmseToMnl, bands[].{band, trips, meanKm, travelTimesMin, modalSplit}` | Alternativer LLM-Modal-Split je Distanzband (<1, 1-2, 2-5, 5-10, 10-20, >20 km); experimentell (GTA 3c), ersetzt die MNL-Moduswahl nicht | `rmseToMid` klein = LLM plausibel; Kalibrierungs-RMSE-Ziel <0,02 gilt für die MNL-Kalibrierung |

Weitere Anker (überliefert, Stand 2026-07): Netz-Feedback konvergiert mit
Δ<0,01 in ≤3 Iterationen (`job.summary.feedbackIterations`/`feedbackDelta`);
Modal-Split-Kalibrierung zielt auf RMSE <0,02 (Log: „Kalibrierung beendet: N
Iterationen, RMSE a → b").

**Hinweis Ground Truth (2026-07-08):** In `backend/data/scenarios/*/artifacts/`
lag zum Redaktionszeitpunkt kein Szenario mit `simulation/`- oder `sumo/`-
Ergebnissen (nur population/matrix/logistics/zone-repository). Die JSON-
Strukturen oben sind deshalb 1:1 aus den Producer-Klassen abgelesen; die
CSV-Struktur ist zusätzlich gegen die echte Datei
`validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv`
verifiziert (70 188 Datenzeilen).

## 2. Anatomie demandsimulationResult.csv

Semikolon-separiert, Header-Zeile, Zeilenende mit `;` (→ leere letzte Spalte
beim Split). 44 benannte Spalten; die wichtigen (0-basierte Indizes, verifiziert
gegen die Rastatt-Datei UND gegen `SumoRunner.readCarTrips`/`LlmModeChoiceRunner.run`):

| Index | Spalte | Bedeutung |
|---|---|---|
| 0 | `tripId` | Weg-ID |
| 1 | `legId` | Etappen-Nr. **`legId==0` zählt jeden Weg genau einmal** — immer darauf filtern, sonst Doppelzählung bei Mehr-Etappen-Wegen. (Im Rastatt-Lauf sind alle 70 188 Zeilen legId==0.) |
| 4 | `personOid` | Person (für Personen-/Wege-pro-Person-Zählung) |
| 12 | `tripMode` | StandardMode-Code: **0=bike, 1=car, 2=passenger, 3=pedestrian, 4=publicTransport** (Mapping: `SimulationResultIngestor.modeName`) |
| 16 | `distanceInKm` | Weglänge in km |
| 38–41 | `fromX, fromY, toX, toY` | Koordinaten als **Lon, Lat** (WGS84) — X=Längengrad! |
| 42 | `tripBegin` | Abfahrt in **Minuten der Modellwoche** (Mo 00:00 = 0; Beispiel: Mo 07:10 → 430) |
| 43 | `tripEnd` | Ankunft, gleiche Einheit |

Die Skripte lösen die Indizes über die Header-Namen auf (robust gegen
Spaltenverschiebung) und fallen nur bei fehlendem Header auf 1/12/16 zurück.

**Cross-run-Stolperfalle (PX-135, 2026-08-17):** `tripId` ist nur innerhalb
eines Laufs eine Weg-ID, kein stabiler semantischer Schlüssel zwischen A/B-
Läufen. J-RAW und LT-135 hatten 42.988 numerisch gemeinsame Schlüssel bei
42.989/42.988 Wegen, aber nur **37,18 %** der vermeintlichen Paare behielten
Ziel- und Vorgängeraktivitätscode. Deshalb Demand-CSVs nie allein über
`(tripId, legId)` Cross-run-joinen; zuerst einen stabilen fachlichen Schlüssel
beweisen oder die benötigten Größen im selben Lauf protokollieren.

**Zulässige Alternative ohne Wegpaarung:** Gruppenmarginalen dürfen je Lauf
separat verglichen werden, wenn ihre Definition vorab feststeht und die
Gruppengrößen als technischer Gate stabil sind. PM-135 (2026-08-17) nutzte
genau dieses Muster: keine Paarung, Gruppenabweichung höchstens eine Zeile;
dadurch war die Aussage „alle 1.480 zusätzlichen Langwege liegen in der
exponierten Marginale" belastbar, aber keine Aussage darüber, welcher einzelne
Weg wechselte.

**Choice-Set-Diagnose (CE-000/CE-135, 2026-08-17):** Eine binäre Aussage
„mindestens eine Fernalternative vorhanden" kann trotz 100 % Eligibility
praktisch bedeutungslos sein. Maßgeblich ist die Summe der MNL-
Wahrscheinlichkeiten aller Fernalternativen je Entscheidung. Der Mittelwert
dieser Masse muss erst gegen den tatsächlich gewählten Anteil validiert werden;
danach ist ein Nutzenkontrafakt je Choice-Set zu transformieren und erst dann zu
mitteln. Die Transformation eines bereits aggregierten Anteils überschätzte im
Freiburg-Fall den Effekt (`8,5698 %` homogen gegenüber `6,6643 %` als Mittel
der Einzelkontrafakte). Aggregiere deshalb nie vor einer nichtlinearen
Logit-Transformation.

## 3. Skripte (read-only, Python3-Stdlib)

Alle Skripte akzeptieren als `argv[1]` **entweder** einen Szenario-Ordner
(`backend/data/scenarios/<id>`, auch `…/artifacts` oder ein Ordner mit
`results/`-Unterordner) **oder** den direkten Dateipfad. Sie schreiben nichts.

### a) `scripts/kpi_report.py` — KPI-Tabelle mit MiD-Abweichung

Liest `artifacts/simulation/kpis.json` + `artifacts/sumo/summary.json` (falls da).

```
python3 .agents/skills/synthesis-diagnostics-and-tooling/scripts/kpi_report.py backend/data/scenarios/<id>
```

Beispielausgabe (getestet 2026-07-10 gegen eine strukturgleiche **Fixture** — lokal
hatte kein Szenario eine kpis.json; Struktur verifiziert aus `SimulationResultIngestor`.
**Die Zahlenwerte sind Fixture-Werte, keine Messung eines echten Laufs** — insbesondere
„Trips/Person/Tag 2.38" ist NICHT als reale Baseline zitierfähig, siehe auch den
Kontaminations-Vorbehalt in `synthesis-distanz-kampagne` §0):

```
Nachfrage (logiTopp/mobiTopp)
  Trips                    70188
  Personen                 4207
  Trips/Person/Tag             2.38   (MiD 3.1, Δ -0.72)
  Ø Weglänge (km)               2.0   (MiD 12.5, Δ -10.50)
Modal-Split (Anteil Wege)
  pedestrian                   0.52   (MiD 0.22, Δ +0.30)
  car                          0.22   (MiD 0.43, Δ -0.21)
  ...
SUMO (Quelle: .../artifacts/sumo/summary.json)
  Fahrzeuge simuliert      5000
  Ø Geschwindigkeit km/h   31.4
  CO2 gesamt (kg)          812.4
```

### b) `scripts/distance_profile.py` — Herzstück der Distanz-Kampagne

Filtert `legId==0`, druckt Ø/Median km + Distanzband-Histogramm
(<1, 1–2, 2–5, 5–10, 10–20, >20 km) gesamt und je Modus.

```
python3 .agents/skills/synthesis-diagnostics-and-tooling/scripts/distance_profile.py \
  validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv
```

Echte Ausgabe (vorgefundenes Rastatt-Validierungs-CSV, getestet 2026-07-10, gekürzt).
**⚠ Signatur-Warnung (Review-Befund 2026-07-10):** Ø ≈ 2,0 km bei Fuß ≈ 52 % ist zugleich
die dokumentierte **Luftlinien-Fallback-Signatur** (docs/05: geroutet wären ~2,9 km /
Fuß ~35 %). Die Kennzahlen allein diskriminieren NICHT zwischen „Modell wählt kurze Ziele"
und „Routing lief auf Luftlinie" — das entscheidet ausschließlich der Job-Log-Grep auf
`"Fallback auf Luftlinie"` (Abschnitt 4). Dieses CSV steht unter Kontaminations-Verdacht;
als Kampagnen-Baseline erst nach Log-Prüfung verwenden (`synthesis-distanz-kampagne`, Phase 0a):

```
GESAMT: 70188 Trips, Ø 2.00 km, Median 1.53 km
      <1 km    20260   28.9 %  #########
     1-2 km    18774   26.7 %  ########
     2-5 km    26526   37.8 %  ###########
    5-10 km     4628    6.6 %  ##
   10-20 km        0    0.0 %
     >20 km        0    0.0 %

  Anker MiD 2017: Ø 12.5 km  ->  Abweichung -10.50 km (-84 %)

pedestrian: 36439 Trips, Ø 1.33 km, Median 1.08 km
car:        15288 Trips, Ø 2.43 km, Median 2.16 km
publicTransport: 7485 Trips, Ø 3.50 km, Median 3.40 km
```

Lesart: 0 Trips über 10 km + Ø 2 km statt 12,5 km = die bekannte Distanz-Lücke
regional gebundener Läufe (Ziele liegen alle im Zuschnitt). Ursachen/Maßnahmen
→ `synthesis-distanz-kampagne`.

### c) `scripts/modal_split.py` — Kontrolle unabhängig von kpis.json

Zählt den Modal-Split direkt aus der CSV und vergleicht gegen kpis.json
(falls im selben Szenario auffindbar) und den MiD-Anker. Weichen CSV und
kpis.json ab (RMSE ≥ 0.01), stammt die kpis.json von einem anderen Lauf.

```
python3 .agents/skills/synthesis-diagnostics-and-tooling/scripts/modal_split.py \
  validation/mobitopp-rastatt/output/synthesis
```

Echte Ausgabe (getestet 2026-07-10):

```
Modus                CSV  kpis.json  MiD 2017   Δ MiD
-----------------------------------------------------
pedestrian         0.519          -      0.22   +0.30
bike               0.055          -      0.11   -0.05
car                0.218          -      0.43   -0.21
passenger          0.101          -      0.14   -0.04
publicTransport    0.107          -      0.10   +0.01

RMSE zu MiD 2017: 0.167  (Kalibrierungs-Ziel: <0.02, ...)
```

## 4. Job-Log lesen

Das Job-Log lebt in `GenerationJob.log` (in-memory, via Job-API/Frontend;
Format `HH:MM:SS  Nachricht`). Der SUMO-Prozess-Output landet zusätzlich in
`artifacts/sumo/sumo.log`. Stufennamen (Reihenfolge, `GenerationService.STAGES`):

`Zonierung, OSM-Daten (POIs), Attraktivitäten, Bevölkerungssynthese,
Aktivitätenpläne, Matrizen, Logistik, Legacy-Export, MobileCity-Export,
Manifest, Simulation (logiTopp), SUMO (Verkehr), Netz-Feedback, LLM-Moduswahl`

Grep-Strings (wörtlich aus `GenerationService` u. a., Zeilennummern Stand 2026-07-08):

**Cache/Wiederverwendung** — Ergebnisse können aus früheren Läufen stammen:
- `"ÖV-Infrastruktur aus Cache übernommen"` (TransitRunner:69) — gtfs2pt lief NICHT neu; bei ÖV-Änderungen `artifacts/sumo/transit/` prüfen.
- `"OSM-Straßennetz aus diesem Lauf wiederverwendet"` (GenerationService:882) — Routing + SUMO teilen sich den Overpass-Abruf.
- GTFS-Rohfeeds werden still unter `backend/data/gtfs-cache/<feed>.zip` gecacht (`GtfsSource.fetch` — kein Log beim Cache-Hit!).

**Fallbacks** — der Lauf lief durch, aber mit schlechterer Datenqualität:
- `"– Fallback auf Luftlinie"` (Routing nicht verfügbar → Matrizen sind Luftlinien-Schätzungen; Erfolgszeile der Matrizen-Stufe endet auf `(GraphHopper-Routing)` oder `(Luftlinie)`).
- `"– Fallback auf synthetische Daten"` (Overpass-POIs fehlgeschlagen).
- `"– Fallback actiTopp"` / `"– Fallback Template-Generator"` (Verhaltensquellen-Kette GTA → actiTopp → Template).
- `"Upload-Fallback prüfen"` / `"Kein GTFS-Feed verfügbar (weder Auto-Fetch noch Upload) – ÖV übersprungen"` / `"– Auto-Fetch übersprungen (Upload empfohlen)"` (Feed zu groß).
- Overpass-Mirrors: der Failover primär→Mirror ist **still** (`OverpassClient.post`); nur der letzte Fehler erscheint, z. B. `"Overpass-Antwort: HTTP 504 (https://…)"`.

**SKIPPED-Gründe** (Status je Stufe; FAILED ≠ SKIPPED — FAILED heißt Fehler, SKIPPED heißt bewusst/mangels Voraussetzung übersprungen):
- `"übersprungen (Zielmodell nicht ausgewählt)"` — logiTopp/SUMO/MobileCity nicht angehakt.
- `"Simulation übersprungen (deaktiviert)"` bzw. `"(Legacy-Projekt nicht gefunden: …)"`.
- `"SUMO übersprungen (nicht verfügbar – SUMO_HOME/Binaries prüfen)"`.
- `"SUMO übersprungen (benötigt logiTopp-Trips – bitte Zielmodell logiTopp mitwählen)"`.
- `"LLM-Moduswahl übersprungen (nicht aktiviert)"` / `"(LLM nicht erreichbar)"` / `"(benötigt logiTopp-Trips)"`.
- Netz-Feedback wird SKIPPED, wenn SUMO nicht DONE ist.

**Erfolgs-/Messzeilen zum Nachrechnen:**
- `"Kalibrierung beendet: N Iterationen, RMSE a → b"` (+ `job.summary.calibrationRmse`).
- `"X PKW- + Y Rad- + Z Fuß-Trips (Tag d, max m je Modus) + … übernommen"` (SumoRunner — mit `maxTrips`-Kappung vergleichen!).
- `"N befahrene Kanten in sumo/congestion.geojson (Netzlast-Layer)"`.
- Netz-Feedback: `job.summary.{feedbackIterations, feedbackDelta, feedbackMeanFactor, feedbackRelations}` — Anker: Δ<0,01 in ≤3 Iterationen.

## 5. Isolations-Muster für A/B-Messungen (überliefertes Muster)

Für „bewirkt Flag X messbar Y?": Test-Backend auf eigenem Port mit eigenem
Daten-Verzeichnis — produktive Szenarien in `backend/data` bleiben unberührt:

```
cd backend && mvn spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8091 --synthesis.data-dir=<eigenes-scratch-verzeichnis>"
```

**NICHT `/tmp` als data-dir verwenden:** /tmp wird gereaped, und A/B-Messdaten sind
Evidenz (Doktrin: `synthesis-change-control`, Nicht-Verhandelbares Nr. 6).

Regeln: identischer `seed`, genau **ein** Flag Unterschied zwischen Lauf A und B;
danach beide Szenario-Ordner mit denselben Skripten aus Abschnitt 3 messen und
die Deltas berichten (nicht die Absolutwerte allein). Dieses Muster ist
überliefert (Stand 2026-07-08) und nicht durch einen frischen Lauf in dieser
Dokumentation verifiziert; Flag-Referenz → `synthesis-config-and-flags`.

## 6. SUMO-Detaildiagnose

Wenn `summary.json` auffällig ist, eine Ebene tiefer gehen (alles unter
`artifacts/sumo/`):

- **`statistics.xml`**: `<vehicleTripStatistics count duration routeLength speed …/>`
  (Fahrzeuge; Quelle der summary-Mittelwerte), `<pedestrianStatistics>` (Fußwege),
  `<rideStatistics>` (ÖV-Rides der intermodalen Personen). Einheiten: s, m, m/s.
- **`edgedata.xml`**: über den ganzen Tag aggregierte Kantenmesswerte
  (edgeData-Id `netzlast`); Attribute u. a. `sampledSeconds`, `speed`, `timeLoss`,
  `traveltime` — Letzteres treibt das Netz-Feedback (`computeOdFactors`).
- **`congestion.geojson`**: je befahrener Kante `edge` (SUMO-Kanten-Id),
  `speedKmh` (gemessen), `limitKmh`, `relSpeed` (=min(1, gemessen/Limit); klein =
  Stau), `timeLossS` (kumulierter Zeitverlust), `sampledS` (Messbasis in
  Fahrzeugsekunden — kleine Werte = dünne Datenbasis, nicht überinterpretieren).
- **`tripinfo.xml`**: je Fahrzeug ein `<emissions CO2_abs="…"/>`-Kindknoten in
  **mg**; kg = Wert / 1 000 000 (`SumoRunner.parseEmissions`). Emissionsklassen:
  pkw=HBEFA3/PC_G_EU4, lkw=HBEFA3/LDV_D_EU4, fahrrad/lastenrad=Zero — ÖV-Bus
  separat via HBEFA3 (siehe Commit 4a055bd).
- **`sumo.log`**: stdout/stderr aller netconvert/duarouter/sumo-Aufrufe; bei
  FAILED stehen die letzten 4 Zeilen auch in der Stufen-Message.

## Provenienz & Wartung

- **Stand: 2026-07-08.** Verifiziert gegen den Code auf `main` (Commit 4ad6382)
  und die echte CSV `validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv`.
- **Ground Truth:** JSON-Keys aus `SimulationResultIngestor` (kpis/depot-stats/
  parcel-density/tours), `GenerationService.writeSumoSummary`/`writeLlmModeChoice`,
  `SumoRunner` (congestion/statistics/tripinfo). Ändert jemand diese Klassen,
  MÜSSEN Katalog (Abschnitt 1) und ggf. `kpi_report.py` nachgezogen werden.
- **Volatil:** Zeilennummern der Grep-Strings (Abschnitt 4), MiD-Anker
  (MiD 2017 — bei Umstellung auf neuere MiD auch `MID_*`-Konstanten in allen
  drei Skripten UND in `SimulationResultIngestor` anpassen), die „~3 km"-
  Distanz-Beobachtung sowie das Isolations-Muster (Abschnitt 5, überliefert).
- **Skript-Tests:** distance_profile/modal_split am 2026-07-10 gegen die
  Rastatt-CSV gelaufen (echte Ausgaben oben); kpi_report gegen eine
  strukturgleiche Fixture, weil lokal kein Szenario mit kpis.json vorlag —
  sobald ein voller Lauf existiert, einmal gegen `backend/data/scenarios/<id>`
  laufen lassen und die Beispielausgabe hier ersetzen.
- **Ergänzung 2026-08-17:** Die Cross-run-Instabilität von `tripId` wurde in
  PX-135 gegen zwei echte Freiburg-Demand-CSVs mit fixierten Eingabe-Hashes
  gemessen; Detailbefund E30 in `synthesis-failure-archaeology`.
