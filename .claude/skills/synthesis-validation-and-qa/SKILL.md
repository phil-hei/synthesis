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

## 2. Test-Inventar (Stand 2026-08-17)

Die Backend-Tests liegen unter `backend/src/test/java/de/synthesis/`. Suite-Lauf:
`scripts/verify.sh core` → 135 Testfälle, bei laufendem Ollama nur die beiden explizit
Env-gesteuerten Integrationstests übersprungen (2026-08-11). Ohne externe Laufzeit
können sieben Tests sauber übersprungen werden. `scripts/verify.sh full` aktiviert
actiTopp und PT; der vollständige Lauf bestand am 2026-08-13 mit **127 Tests,
0 Fehlern und 0 Skips** in 43:07 min (davon 2.389 s allein `GtaBehaviorTest` mit
kaltem Plan-Cache).

**Falle nach einem Re-Clone von `mobitopp-rastatt`:** `SumoRunnerTest`,
`PtIntegrationTest` und ein Teilfall von `LogitoppDemandAdapterTest` hängen an
`validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv`
(SUMO zusätzlich optional an `planned-tours.csv`, das erst die Logistik-Asserts
`deliveryTrips() > 0` und Lkw-CO₂ scharf schaltet). Ein frischer Clone bringt diese
Ergebnisdateien nicht mit — die drei Klassen gehen dann **still in SKIPPED**, ohne
dass SUMO, ÖV oder Adapter defekt wären. Immer die Skip-Zahl gegen den erwarteten
Stand prüfen, nicht nur „0 Fehler". Siehe E23 in `synthesis-failure-archaeology`.

| Klasse (Pfad relativ zu `backend/src/test/java/de/synthesis/`) | Prüft | Gates (`assumeTrue`) | Fixtures |
|---|---|---|---|
| `generation/SourceRunReportTest.java` | Laufkennung, Plan-/Lockhashes, tatsächliche Haushaltsrundung, Erfolg → laufende/fehlgeschlagene Teilbilanz ohne übernommene Mengen, konsistente Teilherkunft, Abschalten und Ausschluss alter SUMO-Ausführungsdateien (2026-11-04); zwei Fälle. Die nativen SourcePlanTraffic-Fälle prüfen zusätzlich aggregierte Zielbilanz und Originalhash | keine externe Laufzeit in dieser Klasse | `SourcePlanTrafficFixture`, synthetische CSV/OSM, `@TempDir`, kontrollierter POI-Wartepunkt; Berichte unter `target/source-run-report/` |
| `generation/SourcePlanTrafficTest.java` | Neuer Quellenplan mit Schwerlastrevision/Profil/Versionswechsel/Abwahl plus aktivem Unternehmensimport: sechs Lage-/Seed-Fälle, unveränderte Unternehmensidentitäten und 69 Kernartefakte, unveränderte Abfahrten anderer Relationen, vor Queueing eingefrorene Quelle und konsistentes `assumed` in Berichten/Manifest (2026-11-02); fünf Testfälle inklusive Parameterfälle | keine externe Laufzeit; kontrollierter POI-Wartepunkt überschreibt beide Places-Überladungen; Overpass nur geschlossene Loopback-Adresse ohne Mirrors, GTFS deaktiviert | `source-plan-traffic/`-CSV/OSM, künstlicher bestätigter Firmenkontext, `@TempDir`; Hash-/Sollwertplan unter docs/verification |
| `generation/SourcePlanTrafficIntegrationTest.java` | Durchgehender `GenerationService`-Pfad: Profil A, Revision B und Schwerlastausschluss mit jeweils erhaltenen Firmenfahrten; zwei synthetische Netzlagen × drei Seeds × drei Varianten = 18 native Läufe. Routing/Abfahrt/Abschluss, Quellmengen und null Teleports (2026-11-02); zwei Parameterfälle | `SumoRunner.isAvailable()`; keine simulierten privaten/Paketfahrten, logiTopp/GTFS/Livequellen aus | dieselben versionierten kleinen CSV-/OSM-Fixtures; echte SUMO-Binaries; native Teilnachweise unter `target/source-plan-traffic/` |
| `generation/SourceProfileLibraryTest.java` | Gemeinsame Profilhashes und Paketübernahme ohne Aktivierung, lokale Revisionen vs. portable Beiträge, native Regeln/Ausschluss ohne Lesezugriff, Vorprüfung aller aktiven Pakete vor Zielschreiboperationen, Versions-/Korruptionsisolation, REST und gefüllte bytegleiche Population-/Aktivitäts-/Zonenausgaben ohne Zentraldateien (2026-10-30); sieben Fälle | keine externen Dienste | Inline-ZIP/CSV und `@TempDir`; Versionsbeispiele in `docs/examples/shared-source-profiles/` |
| `generation/DataPackLibraryTest.java` | Installationsweite Veröffentlichung/Übernahme ohne Aktivierung, identische Hashes und Idempotenz, Versionswechsel ohne Umbindung, isolierte Szenariokopie nach Zentralfehler, portable Gemeindeabhängigkeiten/Gebietsstand, ungültige Archive/Pfade, REST-Status und bytegleiche wiederholte Offlinegenerierung ohne Zentraldatei (2026-10-30); sieben Fälle | keine externen Dienste | Inline-CSV/ZIP, künstliche Gemeindegewichte und `@TempDir`; Versionsbeispiel `docs/examples/source-library/` |
| `generation/MaskedCellsTest.java` | Vier explizite Zellzustände und Rohmarker, echte Null, Mengen-/Geometriefehler, Flächenanteile/Maskenscopes, atomare Work-Lücken und regionale Ergänzung, konsistenter Bevölkerungsanker/Restmenge, ausschließlich unbekannte Quellen, ganze Teilzonen und wiederholte bytegleiche Offlinepipeline (2026-10-30); zehn Fälle | keine externen Dienste | künstliche EPSG:3035-Zellen und `@TempDir`; ausführbares Paket in `docs/examples/masked-cells/` |
| `generation/RegionalWorkplacesTest.java` | Regionale Registerpriorität mit Null-/Überlappungswerten, per Quelle bilanzierten verdeckten Mengen, atomarem Restlückenfehler, gefrorenen Inputs, kommunalen Außenmengen und echten projizierten Zellen; wiederholbare Offlinepipeline und erfasster alter Profilhash (2026-10-29); acht Fälle | keine externen Dienste | Inline-Register/Geometrien, `@TempDir` und `source-profile-legacy-v1.json` (erfasster Hash `0821e2ff…`) |
| `generation/MixedSourcesTest.java` | Gemischte native/Revisions-/Paketprofile, erreichbare und übersprungene Kandidaten, gefrorene Inputs nach Dateientfernung, explizite Work-Lücken, echte Nullwerte, rein partielle Abdeckung, disjunkte Herkunft, atomarer Abbruch, kommunale Außenmengen und Polygonteilzonen; vollständige wiederholbare Offlinepipeline (2026-10-29); zehn Fälle | keine externen Dienste | künstliche Inline-Revisionen/Pakete/Geometrien und `@TempDir` |
| `generation/WorkplaceSourcesTest.java` | Gemeinsamer typisierter Providerkern mit unabhängigem Integer-Vertrag; unveränderte OSM-Work-Gewichte, isolierte/seedabhängige synthetische Auswahl, echte Fallbackrolle statt reiner Abwahl, atomarer Fehler und persistierte Lauf-/Fehlerdiagnose samt Bereinigung (2026-10-29); fünf Fälle | keine externen Dienste | Inline-POIs/Zonen und `@TempDir` |
| `generation/PopulationSourcesTest.java` | Explizite native Bevölkerungsprovider: verzögerte Auswahl/Abwahl, Ausfall-/Fehlerregeln, fünf bytegleiche Pfadvergleiche (aus/Legacy/neue Standardreihenfolge) über alle Dateien der fünf Kernverzeichnisse, mit explizit gefüllten CSV-Mindestartefakten und Fixture-/Ergebnishashes unter `target/source-compatibility/` (präzisiert 2026-10-30), Uploadhashbindung, getrennte Gesamtzahl-/Verteilungsherkunft, Zensus-Archiv-/Rasterintegrität und Bereinigung alter Berichte (2026-10-29); elf Fälle | keine externen Dienste; Zensus nur künstlicher lokaler Cache | Inline-CSV/ZIP, OSM-Snapshots, `@TempDir`, Seed 42 |
| `generation/MunicipalAreasTest.java` | Typisierte GeoJSON-Revisionen/Pakete, Polygon-/MultiPolygon- und Lochgeometrie, korrekte EPSG:3035-Zellen, numerische Randtoleranz, Mengen-/Teilzonenbilanz und atomarer Abbruch sowie ungültige/überlappende Geometrien (2026-10-29); sieben Fälle | keine externen Tools | künstliche Flächen, AGS und `@TempDir` |
| `generation/MunicipalSourcesTest.java` | AGS mit führenden Nullen, Gebietsstands-/Referenzjoin, explizite Punktgewichte, 75/25-Außenmengenbilanz und Nullwerte, reine Außenzuordnung mit Lückenregel, atomarer Abbruch, alte/falsche/zyklische Referenzen, persistierte Offline-Pipeline und Sperre der Lkw-Demonstration für Analyse (2026-10-29); neun Fälle | keine externen Tools | künstliche AGS, Inline-CSV, Modellzonen und `@TempDir` |
| `generation/SourceProfilesAndPacksTest.java` | Lokale ZIP-Pakete CSV/JSON, vollständige Vorprüfung, Hash-/Pfad-/Größenregeln, unveränderte Aktivierung, geordnete/exkludierte Kandidaten, explizite Ausfallregeln, unveränderliche Profile, Nicht-DE-Offlinepipeline und Profilverbrauch für Bevölkerung/Lkw (2026-10-28); acht Fälle | keine externen Tools; REST-Test lokal | Inline-ZIPs, synthetische DE-/FR-Boxen und `@TempDir` |
| `generation/DataSourcesTest.java` | Gemeinsamer Reader CSV/JSON/XML/XLSX, Einheiten/Nullwerte/Dezimalzeichen, strukturgebundene Mappingprofile, unveränderliche Revisionen, Vorprüfung, Arbeitsplatzabdeckung, Ersatzmengen und bytegleicher Legacy-Pfad (2026-10-28); 14 Fälle inklusive Vollständigkeitsangabe, alte Revisionshashes, atomarer Anwendung und zonenbezogener Teileherkunft | keine externen Tools; REST-Test lokal | Inline-Dateien und `@TempDir`, Offline-Pipeline |
| `companytour/CompanyTourLocationsTest.java` | Fehlende Hausnummer → nächster bekannter Nachbar derselben Straße/PLZ/Gemeinde; sichtbare Näherungsherkunft, exakte Treffer bleiben exakt (2026-10-27) | keine | lokale CSV und Tourenfixture |
| `generation/pipeline/RegionalBackgroundIntegrationTest.java` | Native Pkw + vier Hintergrund-Beziehungsarten + additive Unternehmenstour in zwei räumlichen Testnetzen mit je Seeds 7/42/777; vollständige Ausführung, keine Teleports, tatsächlicher Seed und gemeinsamer Nachfrage-Roundtrip (2026-10-27) | SUMO verfügbar | erzeugte OSM-/Nachfrage-Fixtures, sechs native Läufe |
| `generation/pipeline/RoadLocationsTest.java` | Identische Demografie/Zielzonen/Zufallsfolge, echte LAEA-Zugehörigkeit nach Serialisierung, unveränderte externe Anker, konservative Kandidaten und fehlende Straßen mit Bericht | keine | Inline-OSM + `@TempDir` |
| `generation/pipeline/RoadLocationsIntegrationTest.java` | GraphHopper-Ortswahl → volle native SUMO-Kette mit Pkw, Fahrrad und Fuß, Abschlüsse und null Teleports | SUMO verfügbar | Inline-OSM + `@TempDir` |
| `generation/RoadLocationsPipelineTest.java` | Vollständige Offline-Pipeline, Ortsreport/Opportunities, identische Personendatei beim Abschalten und Entfernung alter Reports | keine | Inline-OSM, Simulation aus |
| `generation/pipeline/LegacyCarLocationTest.java` | Pkw-Initialorte in Population und Legacy-Export entsprechen dem jeweiligen Haushaltswohnort; OID/External-ID bleiben korrekt, verwaiste Fahrzeuge werden nicht still verworfen (ergänzt 2026-10-19) | keine | erzeugte Zwei-Zonen-Population in `@TempDir` |
| `generation/pipeline/TrafficEndpointOriginsTest.java` | Exakte Quellkoordinaten, mehrdeutige Referenzen, begrenzte Beispiele, Quellenhashes und sichtbare CSV-Lesefehler ohne Teilzuordnung (ergänzt 2026-10-19) | keine | native CSVs in `@TempDir` |
| `generation/pipeline/FeedbackMathTest.java` | Reine Mathematik der MSA-Dämpfung: k=1 übernimmt Faktoren ungedämpft, konstante Faktoren → Δ=0 (Konvergenz), Oszillation 1↔2 wird Richtung Mittel gedämpft (Ergebnis in (1.3, 1.7)) | keine | keine |
| `generation/pipeline/ModeChoiceCalibratorTest.java` | ASC-Kalibrierung gegen Fake-Sim (Softmax über die ASCs): RMSE sinkt und erreicht ≤0,02 in ≤10 Iterationen; Anker `asc_car_d` und `b_time` bleiben unverändert; Kommentarzeilen der MNL-Datei erhalten. Clamp-Test: ΔASC je Iteration auf ±1,5 begrenzt. **Clamp-Semantik:** die geschriebene Datei hält den Stand VOR dem letzten Update, daher Assert `(iterations-1) * 1.5` | keine | keine (MNL-Fixture inline als String) |
| `generation/pipeline/DestinationChoiceParameterAdjusterTest.java` | Szenariogebundene Distanz-Abkling-Korrektur: exakt fünf bestätigte `b_tt_*`-Schlüssel erhalten `+0,06`, andere Betas und vier Zweck-Dateien bleiben unverändert, die Quelle bleibt unberührt; fehlende Pflichtschlüssel brechen vor dem Schreiben sichtbar ab | keine | keine (Zielwahl-Dateien inline in `@TempDir`) |
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
| `generation/GenerationPipelineE2ETest.java` | Smoke der kompletten Pipeline, **komplett offline**: validierter `osm-data.json`-Snapshot statt Live-Overpass, `useRealRouting=false` (Luftlinien-Matrizen), `behaviorSource="template"` (kein Gradle/LLM), 250 Personen, Timeout 6 min. Asserts: Job-State `DONE`, Kern-Artefakt je Stufe existiert, Manifest enthält Snapshot-/Attraktivitäts-SHA-256, keine Stufe `FAILED`, SUMO/LLM-Stufen `SKIPPED`. Mit lokalem `mobitopp-rastatt` zusätzlich: Simulation `DONE`, `kpis.json` + `demandsimulationResult.csv`. **Falle:** `scenario.id` MUSS manuell gesetzt werden — IDs vergibt sonst der Controller, `repo.save()` tut es nicht | Simulations-Asserts: `Files.exists(<projectDir>/gradlew)` (mobitopp-rastatt lokal); Rest gate-frei | keine externen (Snapshot inline im Temp-`dataDir`); ~4–7 s überliefert (ohne Simulation) |
| `generation/pipeline/OsmDataSnapshotTest.java` | Parst POIs und bevölkerte `place`-Nodes gemeinsam aus einem Overpass-JSON-Snapshot, berechnet SHA-256 und weist einen Snapshot ohne verwertbare Elemente ab | keine | keine (JSON inline im Temp-Verzeichnis) |
| `generation/pipeline/PtIntegrationTest.java` | Volle ÖV-Kette (Phase C): GTFS-Auto-Fetch aus Raw-Cache → Zuschnitt → `gtfs2pt.py` (Fahrplan-Infrastruktur, Fenster 6–9 Uhr — **Test-Override**; Betriebs-Default ist 6–10) → SumoRunner mit intermodaler Personen-Routung, `maxTrips=1500`. Asserts: `ptVehicles() > 0`, `ptRiders() > 0`, PKW/Rad weiterhin simuliert, mindestens eine `<ride>`-Etappe im `routes.rou.xml` | **Env-gated**: `SYNTHESIS_TRANSIT_VENV` gesetzt + existiert; alle drei Fixtures vorhanden; `sumo.isAvailable()`; `transit.isAvailable()` | `demandsimulationResult.csv`, `validation/rastatt-test.osm`, `validation/kvv-gtfs.zip`; 143,9 s auf dem M5 am 2026-08-11 — deshalb Env-gated, nicht im Standard-Lauf |
| `scenario/ComparisonControllerTest.java` | Vergleich liefert nur vorhandene Szenarien in Anfrage-Reihenfolge; `simulated=true` ausschließlich bei parsebarer `kpis.json`, beschädigtes JSON wird nicht als Simulationsergebnis ausgegeben; der experimentelle Distanz-Abkling-Hebel wird als Vergleichsparameter exponiert | keine | keine (`@TempDir`) |

Die fixture-freie Frontend-Suite läuft mit `cd frontend && npm test` über Angulars nativen
Vitest-Runner (21 Tests, Stand 2026-08-17):

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

**Ergänzte kleine Migrationsfixtures (2026-11-02):** `backend/src/test/resources/source-plan-traffic/` enthält zwei synthetische OSM-Netzlagen, Schwerlastrevisionen A/B und die feste Firmenquelltabelle. Hashes und Sollwerte stehen vor dem Lauf in `docs/verification/2026-11-02-source-plan-traffic-plan.json`; keine amtliche Stadt-/Landreferenz und kein Liveabruf.

**Ergänzte kleine Vertragsfixture (2026-10-29):** `backend/src/test/resources/source-profile-legacy-v1.json` hält die alte Profildefinition ohne Strategie. Ihr vor der Erweiterung erfasster Profilhash muss unverändert bleiben; keine externe Beschaffung erforderlich.

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

- **Stand: 2026-08-17.** Die tragenden Testklassen unter `backend/src/test/java/`
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

**Quellenlaufbericht (2026-11-04):** `features/data/source-run-report.component.spec.ts` prüft Laufzuordnung, Null-/Maskenwerte, getrennte Registerteile, Teileherkunft/Zonen, Zielmengen vs. Abschluss, Rundung bei Legacy-Delegation, Lade-/Altformatfehler und Abbruch veralteter Requests. Fehlerläufe besitzen nun absichtlich eine `sources/resolution.json` mit `run.state=FAILED` und ausschließlich eigenen Teilbeiträgen; die bisherigen Population-/Work-/Mixed-Tests prüfen fehlende alte Erfolgsanteile statt das Nichtvorhandensein jeder Resolution-Datei. [Vertrag und Grenzen](../../../docs/examples/source-run-report/README.md).
