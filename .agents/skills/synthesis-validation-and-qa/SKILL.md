---
name: synthesis-validation-and-qa
description: >
  Validierung und QA im SyntheSis-Projekt: Was gilt als bewiesen (Evidenz-Hierarchie),
  wie man einen Test hinzufügt, welches Akzeptanzkriterium wo gilt, was tun wenn ein
  Fixture fehlt oder ein Test geskippt wird. Nutzen bei: "Test hinzufügen", "was gilt
  als bewiesen", "Fixture fehlt", "Akzeptanzkriterium", "assumeTrue", "Test wird
  übersprungen/skipped", "RMSE-Schwelle", "Golden-Daten", "Test-Suite läuft nicht",
  "wie prüfe ich, dass X wirklich funktioniert", "Merge-Gate", "PtIntegrationTest",
  "SumoRunnerTest", "E2E-Test".
---

# SyntheSis: Validierung & QA

Verkehrsnachfrage-Generator (siehe `README.md`, `docs/05` Validierung, `docs/07`).
Dieser Skill definiert, was in diesem Projekt als Evidenz zählt, inventarisiert die
tragenden Testklassen samt Gates und Fixtures und beschreibt, wie man Tests korrekt ergänzt.

## Wann NICHT nutzen

- **Prozess-Gates** (wann darf gemergt werden, Commit-Disziplin): → `synthesis-change-control`.
- **Messwerkzeuge bauen/bedienen** (Profiler, Zähl-Skripte): → `synthesis-diagnostics-and-tooling`.
- **Evidenzlatte für Forschungsfragen** (Hypothesen, Experimentdesign): → `synthesis-research-methodology`.
- **Fixtures beschaffen/Umgebung aufsetzen** (SUMO installieren, GTFS-Download-URL, venv): → `synthesis-build-and-env`.
- **Einen konkreten roten Test debuggen**: → `synthesis-debugging-playbook`.
- Fachliche Verkehrsmodellierungs-Fragen (MiD, Modal-Split-Definitionen): → `verkehrsmodellierung-reference`.

## 1. Evidenz-Hierarchie dieses Projekts

Was „bewiesen" heißt, in absteigender Beweiskraft:

1. **Echter externer Lauf, EXIT 0, mit gezählten Ergebnissen.** Beispiele aus der
   Projektgeschichte: logiTopp-Woche mit 10.966 Paketen / 3.279 Touren beim Erstnachweis;
   ÖV-Kette mit 141 Halten / 498 Bussen plus `<ride>`-Etappen im `routes.rou.xml`.
   Das ist die Latte für Behauptungen wie „Feature X funktioniert end-to-end".
2. **Grüner Unit-/Integrationstest** (die Suite unten). Beweist die getestete Invariante,
   nicht mehr — ein grüner `SumoRunnerTest` beweist z. B. nichts über die ÖV-Kette.
3. **Code-Lektüre.** Zulässig als Hypothesenquelle, nie als Beweis.

Zusatzregeln:
- **UI-Behauptungen** brauchen mindestens `cd frontend && npm test` sowie
  `cd frontend && npm run build` (Interaktionstests plus strict AOT sind das Frontend-Gate)
  und historisch zusätzlich einen Screenshot einer geseedeten Preview.
- **„Läuft bei mir" zählt nicht.** Das ausgeführte Kommando plus die konkrete Zahl
  (Fahrzeuge, Trips, RMSE, Dateigröße) gehört in die Doku bzw. Commit-Message.

## 2. Test-Inventar (Stand 2026-08-11)

Die Backend-Tests liegen unter `backend/src/test/java/de/synthesis/`. Suite-Lauf:
`scripts/verify.sh core` → 127 Testfälle, bei laufendem Ollama nur die beiden explizit
Env-gesteuerten Integrationstests übersprungen (2026-08-11). Ohne externe Laufzeit
können sieben Tests sauber übersprungen werden. `scripts/verify.sh full` aktiviert
actiTopp und PT; der vollständige Lauf bestand am 2026-08-11 ohne Skip.

| Klasse (Pfad relativ zu `backend/src/test/java/de/synthesis/`) | Prüft | Gates (`assumeTrue`) | Fixtures |
|---|---|---|---|
| `generation/pipeline/FeedbackMathTest.java` | Reine Mathematik der MSA-Dämpfung: k=1 übernimmt Faktoren ungedämpft, konstante Faktoren → Δ=0 (Konvergenz), Oszillation 1↔2 wird Richtung Mittel gedämpft (Ergebnis in (1.3, 1.7)) | keine | keine |
| `generation/pipeline/ModeChoiceCalibratorTest.java` | ASC-Kalibrierung gegen Fake-Sim (Softmax über die ASCs): RMSE sinkt und erreicht ≤0,02 in ≤10 Iterationen; Anker `asc_car_d` und `b_time` bleiben unverändert; Kommentarzeilen der MNL-Datei erhalten. Clamp-Test: ΔASC je Iteration auf ±1,5 begrenzt. **Clamp-Semantik:** die geschriebene Datei hält den Stand VOR dem letzten Update, daher Assert `(iterations-1) * 1.5` | keine | keine (MNL-Fixture inline als String) |
| `generation/pipeline/ShapefileWriterTest.java` | Liest das geschriebene ESRI-Shapefile binär zurück: file code 9994, Version 1000, shape type 5 (Polygon), Bbox, Record-Struktur (Header BE / Inhalt LE), geschlossener 5-Punkte-Ring, `.shx`-Offsets, dBase-Feld exakt `districtID` mit Werten `SYN-001`/`SYN-002`, `.prj` enthält `WGS_1984` — das Format, das der MobileCity-/Takomat-Import erwartet | keine | keine |
| `generation/pipeline/MobileCityExporterTest.java` | Emuliert unabhängig die gepaddete EpigeneCSV-Blocklogik und prüft `TECCarFleet.stock.total`: Basisjahr-Header, Pkw-Zählung über `car.ownerId` → `household.homeZone`, Mehrfach-Pkw und Ignorieren unbekannter Zonen | keine | keine (CSV-Fixtures inline im Temp-Verzeichnis) |
| `generation/pipeline/GtfsCropperTest.java` | Bbox-Zuschnitt am echten KVV-Feed auf Rastatt: Halte/Trips/Routen > 0, Ergebnis < 1/20 der Feed-Größe, `shapes.txt` verworfen, **referenzielle Integrität** (jede trip_id/stop_id aus `stop_times.txt` existiert in `trips.txt`/`stops.txt`). Zweiter Test: Zeitfenster 6–10 Uhr reduziert die Trip-Zahl echt | `Files.exists(validation/kvv-gtfs.zip)` | `validation/kvv-gtfs.zip` |
| `generation/pipeline/GtfsSourceTest.java` | GTFS-Quellenauflösung **ohne Netz**: Raw-Cache wird mit `kvv.zip` vorbelegt → `resolve()` liefert `source()=="fetch"`, `feedName()=="kvv"`, zugeschnittener Feed geschrieben und < 1/20 der Rohgröße. Zweiter Test: Region ohne Feed-Abdeckung (Nordsee) und ohne Upload → `null` (ÖV wird übersprungen) — läuft komplett fixture-frei | Test 1: `Files.exists(validation/kvv-gtfs.zip)`; Test 2: keins | `validation/kvv-gtfs.zip` (nur Test 1) |
| `generation/pipeline/GtaBehaviorTest.java` | GTA-Verhaltensquelle, 5 Tests: (1) deterministisches Layout LLM-Touren → lückenlose Legacy-`activity.csv` (gültige Aktivitätscodes, Startzeit in Wochen-Minuten, Vollzeit-Person hat WORK + HOME) — **ohne LLM**; (2) Live-LLM erzeugt Persona-Pläne; (3) Cache verhindert Zweit-Calls (`llmCalls()==0` im zweiten Lauf, identische Pläne); (4) What-If-Kontext liefert LLM-Begründungen; (5) `sameSeedIsReproducible`: gleicher Seed → identische `GtaWeek`-Pläne | Tests 2–5: `runner.isAvailable()` (lokales LLM/Ollama erreichbar); Test 1: keins | keine (Person-CSVs inline); ~2 min überliefert wegen Live-LLM (~30–40 s je Persona, `maxPersonas` begrenzt) |
| `generation/pipeline/LlmModeChoiceTest.java` | GTA-3c-Moduswahl gegen Live-LLM: 3 Trips in 3 Distanzbändern → Modal-Split je Modus in [0,1], Summe 1±0,02; gleicher Seed (77) reproduziert den Split exakt | `isAvailable()` (lokales LLM) | keine (Demand-CSV inline) |
| `generation/pipeline/ActitoppRunnerTest.java` | Verhindert die frühere falsche Verfügbarkeitsmeldung: actiTopp gilt nur mit Gradle-Wrapper, `runSynthesis`-Task, Runner-Quelle und passendem Vertragsmarker als verfügbar | keine | keine (`@TempDir`) |
| `generation/pipeline/ActitoppIntegrationTest.java` | Startet den echten gepinnten actiTopp-Adapter mit drei Personen und prüft nichtleere, zeitlich gültige Aktivitätspläne | **Env-gated:** `SYNTHESIS_RUN_ACTITOPP_TEST=true`; gültiger actiTopp-Vertrag | temporäre Personen-CSV; lokales `validation/actitoppNG`; 4,3 s auf dem M5 |
| `generation/GenerationServiceCalibrationTest.java` | Auswahl aktiver Kalibrier-Overrides und Beobachtbarkeits-Guard der `<1 km`-Distanzkalibrierung; der Großraum-Spike fixiert zusätzlich 1-km-INSPIRE-Auflösung, 3.000–3.600 Zonen und Zulassung durch den Guard | keine | keine |
| `generation/pipeline/SumoRunnerTest.java` | Volle native SUMO-Kette (netconvert → Trips → duarouter → sumo) gegen echtes Rastatt-Demand-CSV, `maxTrips=2000`: Fahrzeuge/Routenlänge/Speed/Dauer > 0; **multimodal**: `carTrips`, `bikeTrips`, `walkTrips` je > 0; CO₂ (HBEFA) > 0 inkl. `pkw`-Anteil; `congestion.geojson` mit `LineString` + `relSpeed`; OD-Stau-Faktoren über 3 Zentroiden: routbare Paare > 0, jeder Faktor in [1,3]; wenn `planned-tours.csv` da: `deliveryTrips` > 0 und `lkw`-CO₂ > 0 | `runner.isAvailable()` (SUMO-Binaries/`SUMO_HOME`); `Files.exists(DEMAND_CSV)`; OSM ladbar (Cache oder Overpass) | `validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv`, optional `planned-tours.csv`, `validation/rastatt-test.osm` (**selbstheilend**: Cache-Miss → Overpass-Fetch → Cache neu geschrieben); ~45 s überliefert |
| `generation/GenerationPipelineE2ETest.java` | Smoke der kompletten Pipeline, **komplett offline**: `useOsmData=false` (synthetische POIs), `useRealRouting=false` (Luftlinien-Matrizen), `behaviorSource="template"` (kein Gradle/LLM), 250 Personen, Timeout 6 min. Asserts: Job-State `DONE`, Kern-Artefakt je Stufe existiert (zones, person/household/activity, `CAR_TTC.mtx.bz2`, Legacy-Export, MobileCity-Shapefile-Quartett, `manifest.json`), keine Stufe `FAILED`, SUMO/LLM-Stufen `SKIPPED`. Mit lokalem `mobitopp-rastatt` zusätzlich: Simulation `DONE`, `kpis.json` + `demandsimulationResult.csv`. **Falle:** `scenario.id` MUSS manuell gesetzt werden — IDs vergibt sonst der Controller, `repo.save()` tut es nicht | Simulations-Asserts: `Files.exists(<projectDir>/gradlew)` (mobitopp-rastatt lokal); Rest gate-frei | keine externen (Temp-`dataDir`); ~4–7 s überliefert (ohne Simulation) |
| `generation/pipeline/PtIntegrationTest.java` | Volle ÖV-Kette (Phase C): GTFS-Auto-Fetch aus Raw-Cache → Zuschnitt → `gtfs2pt.py` (Fahrplan-Infrastruktur, Fenster 6–9 Uhr — **Test-Override**; Betriebs-Default ist 6–10) → SumoRunner mit intermodaler Personen-Routung, `maxTrips=1500`. Asserts: `ptVehicles() > 0`, `ptRiders() > 0`, PKW/Rad weiterhin simuliert, mindestens eine `<ride>`-Etappe im `routes.rou.xml` | **Env-gated**: `SYNTHESIS_TRANSIT_VENV` gesetzt + existiert; alle drei Fixtures vorhanden; `sumo.isAvailable()`; `transit.isAvailable()` | `demandsimulationResult.csv`, `validation/rastatt-test.osm`, `validation/kvv-gtfs.zip`; 143,9 s auf dem M5 am 2026-08-11 — deshalb Env-gated, nicht im Standard-Lauf |
| `scenario/ComparisonControllerTest.java` | Vergleich liefert nur vorhandene Szenarien in Anfrage-Reihenfolge; `simulated=true` ausschließlich bei parsebarer `kpis.json`, beschädigtes JSON wird nicht als Simulationsergebnis ausgegeben | keine | keine (`@TempDir`) |

Die fixture-freie Frontend-Suite läuft mit `cd frontend && npm test` über Angulars nativen
Vitest-Runner (20 Tests, Stand 2026-08-11):

| Klasse (unter `frontend/src/app/`) | Prüft |
|---|---|
| `app.component.spec.ts` | Anwendungsshell, Navigation und Router-Outlets werden gerendert |
| `core/services/api.service.spec.ts` | Szenario-GET, Kodierung mehrerer Vergleichs-IDs und vollständiger Zonenvorschau-Request im API-Vertrag |
| `features/comparison/comparison.component.spec.ts` | Initialladen und Rendern des migrierten Pickers, sichtbare Ladefehler ohne veraltete Daten, blockierte Doppelaufrufe im Ladezustand, Leerauswahl ohne API-Aufruf, exakte Tabellenspannen mit/ohne MiD-Spalte sowie gemischte simulierte/nicht simulierte Ergebnisse inklusive MiD-Referenz |
| `features/studio/studio.component.spec.ts` | Rendern des migrierten Studio-Templates, Leaflet-Lebenszyklus, Save-Validierung, Polling-Abbruch und Timer-Cleanup sowie sichtbare Fehlerzustände für Szenarioliste, Defaults, Löschung, Upload-Status und das Entfernen aller vier Upload-Arten ohne Backend oder Netzwerk |

Verifizierte Kommandos:

```bash
scripts/verify.sh core                           # 127 Testfälle, externe Tests ggf. geskippt
scripts/verify.sh full                           # komplette technische Laufzeitabnahme
cd backend && mvn -o test                        # reiner Backend-Lauf
mvn -o test -Dtest=SumoRunnerTest               # nur SUMO-Kette
SUMO_HOME=… SYNTHESIS_TRANSIT_VENV=validation/transit-venv/bin/python \
  mvn -o test -Dtest=PtIntegrationTest           # voller ÖV-Test
cd frontend && npm test && npm run build         # Vitest + Produktions-AOT = Frontend-Gate
```

## 3. Muster für neue Tests

1. **`assumeTrue`-Gates statt Fails** für alles Externe (SUMO-Binaries, LLM-Endpoint,
   Fixtures, Env-Vars). Ein fehlendes Fixture ist ein Skip mit sprechender Meldung
   („KVV-GTFS-Cache nicht vorhanden – Test übersprungen"), kein roter Test. Vorbilder:
   `GtfsCropperTest` (Fixture), `GtaBehaviorTest` (`runner.isAvailable()`),
   `PtIntegrationTest` (Env-Var).
2. **Temp-Verzeichnisse + `finally`-Cleanup.** `Files.createTempDirectory`/`createTempFile`,
   im `finally` löschen; für Verzeichnisse das `deleteRecursively`-Muster aus
   `SumoRunnerTest` kopieren (`Files.walk` + `reverseOrder`, best effort).
3. **Langsame Integrationstests hinter eine Env-Var** legen (Muster `PtIntegrationTest`
   mit `SYNTHESIS_TRANSIT_VENV`), damit `mvn -o test` schnell und offline-tauglich bleibt.
4. **Fixtures nach `validation/`** (gitignored, persistent — `/tmp` wird vom OS gereaped).
   Pfad im Test relativ zum Maven-Arbeitsverzeichnis `backend/`, also `../validation/…`.
5. **Selbstheilend wo möglich:** das `rastatt-test.osm`-Muster aus `SumoRunnerTest.loadOsm()`
   — Cache lesen, bei Miss live holen (Overpass inkl. Mirror-Fallback) und den Cache
   neu schreiben; Schreiben des Caches ist optional/best effort.
6. **Determinismus explizit testen**, wo Seeds im Spiel sind (`sameSeedIsReproducible`,
   `LlmModeChoiceTest`): gleicher Seed → tiefes `assertEquals` auf den Ergebnis-Records.
7. **Offline-fähig by default:** kein Test im Standard-Lauf darf Netz brauchen
   (`GtfsSourceTest` testet Auto-Fetch über einen vorbelegten Raw-Cache, nicht per Download).

## 4. Fixture-/Golden-Inventar (Stand 2026-07-08)

`validation/` ist gitignored; nichts davon kommt per `git clone`. Beschaffung im Detail:
→ `synthesis-build-and-env`.

| Fixture (repo-relativ) | Zweck | Regeneration |
|---|---|---|
| `validation/rastatt-test.osm` (~3,8 MB) | Rastatt-Straßennetz für `SumoRunnerTest`/`PtIntegrationTest` | **Selbstheilend**: löschen genügt, `SumoRunnerTest` holt es via Overpass neu und schreibt den Cache |
| `validation/rastatt-rail-test.osm` (~3,2 MB) | Geparkter Rail/Tram-Anlauf (siehe Doku-Befund im Commit-Log); von keinem aktiven Test referenziert | manuell via Overpass; nur relevant, wenn der Rail-Anlauf reaktiviert wird |
| `validation/kvv-gtfs.zip` (121 613 482 Bytes = 121 MB dezimal / 116 MiB, Stand 2026-07-07) | Echter KVV-Verbund-Feed für `GtfsCropperTest`, `GtfsSourceTest`, `PtIntegrationTest` | `curl`-URL siehe `synthesis-build-and-env`; ohne die Datei skippen die drei Tests sauber |
| `validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv` | Echtes Rastatt-Demand-CSV (Input für `SumoRunnerTest`/`PtIntegrationTest`) | Entsteht durch einen Simulationslauf des lokalen mobitopp-rastatt-Projekts (bzw. E2E-Test mit Simulation) |
| `validation/mobitopp-rastatt/output/synthesis/results/planned-tours.csv` | Zustelltouren (aktiviert die Liefer-Asserts in `SumoRunnerTest`) | dito, Simulationslauf; Fixture ist optional (Test läuft ohne, nur ohne Liefer-Asserts) |
| `backend/src/main/resources/kit-population-sample.csv` (~125 KB, **eingecheckt**) | Bevölkerungs-Sample (6.512 Haushalte / 13.168 Personen) als Basis der Populationssynthese | keine Regeneration nötig — Teil des Repos |

## 5. Akzeptanzschwellen (überliefert, Stand 2026-07-08)

Diese Schwellen sind historisch gewachsen und in Tests bzw. Doku verankert — **neue
Schwellen werden VOR dem Lauf festgelegt**, nie nachträglich ans Ergebnis angepasst
(→ `synthesis-research-methodology`):

- **Moduswahl-Kalibrierung:** RMSE < 0,02 als Ziel (so im Test verankert,
  `ModeChoiceCalibratorTest`); ~0,07 erreicht gilt in der echten Simulation als gut.
- **Netz-Feedback:** Konvergenz bei Δ < 0,01 (MSA-Δ über Relationen, vgl. `FeedbackMathTest`).
- **Plausibilitätsfenster trips/Person/Tag:** ~2,8–3,5, Referenz MiD 3,1.
- **Merge-Gate:** vollständige Suite grün (`cd backend && mvn -o test`) plus
  `cd frontend && npm test && npm run build` bei Frontend-Änderungen
  (Details → `synthesis-change-control`).
- **OD-Stau-Faktoren:** in [1,3] (hart im `SumoRunnerTest` asserted).

## Provenienz & Wartung

- **Stand: 2026-08-11.** Die tragenden Testklassen unter `backend/src/test/java/`
  wurden gelesen; Fixture-Größen per `ls` verifiziert (kvv-gtfs.zip 121 613 482 Bytes
  = 121 MB dezimal / 116 MiB, vom 2026-07-07 — andere Skills nennen dieselbe Datei
  „121 MB"). Laufzeiten und historische Zählwerte (10.966 Pakete, 141 Halte,
  498 Busse, RMSE ~0,07) sind **überliefert** aus Projekt-Doku/Commits, nicht in dieser
  Skill-Erstellung nachgemessen.
- **Wartungspflicht:** Bei neuem Test → Tabelle in §2 ergänzen (Gates + Fixtures + Laufzeit).
  Bei neuem/entferntem Fixture → §4. Bei geänderter Schwelle → §5 mit Datum. Die
  Testanzahl und Skip-Semantik nach jeder Suite-Änderung gegen `mvn -o test` prüfen.
- **Volatil:** kvv-gtfs.zip-Größe/Datum, Laufzeiten (hardwareabhängig), mobitopp-rastatt-Outputs.
- Geschwister-Skills: `synthesis-change-control`, `synthesis-diagnostics-and-tooling`,
  `synthesis-research-methodology`, `synthesis-build-and-env`, `synthesis-debugging-playbook`.
