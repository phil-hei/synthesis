---
name: synthesis-config-and-flags
description: >
  Vollständiger Katalog aller Konfigurationsachsen des SyntheSis-Repos: Szenario-Flags
  (Scenario.java, z. B. behaviorSource, targetModels, sumoFeedback,
  calibrateModeChoice, llmModeChoice, useRealRouting, samplingFraction sowie das Legacy-Feld useOsmData) und
  Betriebs-Properties (SynthesisProperties, Prefix synthesis.: census, overpass, simulation, actitopp,
  sumo, transit inkl. GTFS-Feed-Registry, llm) samt Defaults, Konsumenten und Gates. Laden, WENN
  du wissen musst (a) welches Flag/Property Verhalten X steuert, (b) was der Default von Y ist
  (inkl. berechneter Defaults aus $SUMO_HOME), (c) ob ein Feature produktiv oder experimentell
  ist, (d) wie man ein neues Szenario-Flag oder eine neue Betriebs-Property anlegt (Checkliste
  Scenario.java → scenario.model.ts → studio.component.html → GenerationService-Gate), (e) welche
  Env-Variablen (SUMO_HOME, SYNTHESIS_TRANSIT_VENV) was bewirken, oder (f) warum eine
  Pipeline-Stufe SKIPPED ist. Stichwörter: Flag, Property, Default, application.yml,
  Konfiguration, Szenario-Parameter, Feature-Toggle, Zielmodell, Verhaltensquelle, GTFS-Feed,
  venv-python, Gate, experimentell.
---

# SyntheSis – Konfigurationsachsen & Flags

Dieses Dokument ist der **Ground-Truth-Katalog** aller Konfiguration im SyntheSis-Repo
(Angular-Studio `frontend/` + Spring-Boot-Backend `backend/`, 14-stufige Pipeline in
`backend/src/main/java/de/synthesis/generation/GenerationService.java`). Es gibt **drei Ebenen**:

1. **Szenario-Flags** – pro Szenario, vom Nutzer im Studio gesetzt, als JSON persistiert
   (`backend/src/main/java/de/synthesis/scenario/Scenario.java`).
2. **Betriebs-Properties** – pro Deployment, Prefix `synthesis.` in
   `backend/src/main/resources/application.yml`
   (`backend/src/main/java/de/synthesis/SynthesisProperties.java`).
3. **Env-Variablen** – Maschinen-Setup (`SUMO_HOME`, `SYNTHESIS_TRANSIT_VENV`).

**Merkregel:** Feature-Gates kombinieren je nach Funktion Szenario-Flag, Betriebs-Property und
Verfügbarkeit (Binary/venv/LLM erreichbar). OSM-POI-/Siedlungsdaten sind die bewusste Ausnahme:
Sie werden automatisch als Snapshot → Live-Overpass → synthetischer Fallback gewählt und haben
keinen Szenario-Schalter mehr.

## Wann diesen Skill NICHT nutzen

- **Commit-/Merge-/Gate-Fragen** („darf ich das committen?", Beförderung eines Flags von
  experimentell zu produktiv) → `synthesis-change-control`.
- **Start/Betrieb/Ports/Läufe** („wie starte ich Backend + Frontend?") → `synthesis-run-and-operate`.
- **Build-/Toolchain-Installation** (SUMO installieren, venv bauen) → `synthesis-build-and-env`.
- **Fehlersuche in einer laufenden Pipeline** (Stufe rot, Stacktrace) → `synthesis-debugging-playbook`.
- **Fachliche Fragen** zu Modal-Split, MiD, actiTopp-Semantik → `verkehrsmodellierung-reference`.
- **Architektur-Invarianten** (wer darf wen aufrufen) → `synthesis-architecture-contract`.

---

## 1. Szenario-Flags (`Scenario.java`)

Quelle: `backend/src/main/java/de/synthesis/scenario/Scenario.java`. Public Fields; die
**Feld-Initialisierer sind zugleich die Defaults**, die das Frontend anbietet. Spiegel im
Frontend: `frontend/src/app/core/models/scenario.model.ts`. UI:
`frontend/src/app/features/studio/studio.component.html` (Blocknamen unten; Zeilennummern
driften, daher nur Block + Bedienelement).

### 1.1 Top-Level

| Feld | Typ | Default | Wirkung / Konsument | Status |
|---|---|---|---|---|
| `id` | String | – (vom Backend vergeben) | Persistenz-Schlüssel (`ScenarioRepository`) | produktiv |
| `name` | String | `"Neues Szenario"` | Anzeigename; Studio-Block „Szenario" | produktiv |
| `createdAt` | Instant | – | Metadatum | produktiv |
| `region` | Region (`minLat/minLon/maxLat/maxLon`) | `null` bis Karte gezeichnet | Bounding-Box aller Stufen (Zonierung, OSM, Routing, GTFS-Feed-Auswahl) | produktiv |
| `zoning.cellSizeMeters` | double | `100` | Tatsächlich verwendete Kantenlänge quadratischer Rasterzellen (m). Bei neuer/neu gezeichneter Region fragt das Studio mit 100 m an und übernimmt die feinste Auflösung innerhalb von `zoning.maxZones`: `grid` in 50-m-, `epsg3035` in 100-m-, `inspire` in amtlichen Standardstufen. | produktiv |
| `zoning.maxZones` | int | `600` (über `/defaults` aus `synthesis.max-zones`) | Szenariospezifische Obergrenze für Vorschau und Pipeline. Gültig 100 bis `synthesis.max-zones-hard-limit` (1.200), da OD-Matrizen quadratisch wachsen. | produktiv |
| `zoning.mode` | String | `"grid"` (auch über `/defaults`) | `grid` = freies metrisches Bbox-Raster (global) \| `epsg3035` = flächengleiches, europaweit fest ausgerichtetes LAEA-Gitter in flexiblen 100-m-Schritten, nicht amtlich; stabile Namen `LAEA3035RES…` \| `inspire` = amtliches INSPIRE-/Zensus-Gitter mit 100 m/1 km/10 km und Zell-ID `CRS3035RES…`. Beide LAEA-Modi verwenden `Zone.laeaE/laeaN/laeaRes`; außerhalb Europas Fallback auf `grid`. Job-Summary: `zoningMode`. | produktiv |
| `targetModels` | List\<String\> | `["logitopp", "mobilecity"]` | Nachfragesimulation/Weiterverarbeitung: `logitopp` \| unabhängiger Export `mobilecity` \| nachgelagerte Verkehrssimulation `sumo`. SUMO impliziert logiTopp; Studio und API normalisieren die Abhängigkeit. | produktiv (`sumo` nicht im Default) |

**`targets()`-Helper (wichtig!):** Leere oder `null` Liste fällt auf `["logitopp", "mobilecity"]`
zurück (Rückwärtskompatibilität alter persistierter Szenarien). **Gates immer über
`scenario.targets()` prüfen, nie über das rohe Feld `targetModels`.** `targets()` ergänzt bei
SUMO automatisch logiTopp; `normalizeTargetDependencies()` persistiert diese Abhängigkeit. Gate-Stellen in
`GenerationService`: `!targets().contains("mobilecity")` → Stufe „MobileCity-Export" SKIPPED;
`!targets().contains("logitopp")` → „Simulation (logiTopp)" SKIPPED;
`!targets().contains("sumo")` → „SUMO (Verkehr)" SKIPPED.

### 1.2 `Population`

UI-Ort, wenn nicht anders vermerkt: Studio-Block „Bevölkerung"; die Modellierungs-Flags
(behaviorSource, Kalibrierung, LLM, Feedback, ÖV) im Block „Modellierung".

| Feld | Typ | Default | Wirkung / Konsument | Status |
|---|---|---|---|---|
| `totalPersons` | Integer | `null` | Optionale manuelle Einwohnerzahl. `null` aktiviert: alternatives Upload-Raster → Destatis Zensus 2022 → OSM-Siedlungsschätzung → `personsPerKm2`. Der UI-Quellenhinweis schreibt bewusst nicht in dieses Feld zurück, damit die Manifest-Provenienz korrekt bleibt. | produktiv |
| `personsPerKm2` | double | `1500` | Fallback-Dichte, wenn `totalPersons` leer und OSM nichts liefert | produktiv |
| `samplingFraction` | double | `1.0` | Anteil synthetisierter Bevölkerung (mobiTopp `fractionOfPopulation`). API/Persistenz bleiben `(0, 1]`; das Studio zeigt 5–100 % als Regler und der Vergleich als Prozentwert. Konsumenten: `PopulationGenerator` (Zonen-Sampling), `SimulationRunner` (an logiTopp durchgereicht), `ComparisonController` (Vergleichs-Metadatum) | produktiv |
| `seed` | long | `42` | Zufalls-Seed – gleicher Seed + gleiche Parameter = identisches Ergebnis. Konsumenten: Pipeline-`random`, `GtaBehaviorRunner`/`LlmModeChoiceRunner` (baseSeed) | produktiv |
| `useOsmData` | boolean | `true` | **@Deprecated, zur JSON-Kompatibilität behalten und zur Laufzeit ignoriert.** Die Quelle wird automatisch als Szenario-Snapshot → Live-Overpass → synthetischer Fallback gewählt; im Studio gibt es dafür keine Checkbox mehr. | Legacy |
| `useRealRouting` | boolean | `true` | Echte Reisezeit-/Distanzmatrizen via GraphHopper (OSM-Straßennetz) in Stufe „Matrizen"; bei `false`/Fehlschlag Luftlinie. Konsument: `GenerationService` → `RoutingEngine`/`MatrixGenerator` | produktiv |
| `externalTrips` | boolean | `true` | Externe Zielzonen um das Gebiet, damit ein Teil der Wege es verlässt (realistische Weglängen). Konsument: `GenerationService` → `ExternalZones` | produktiv |
| `behaviorSource` | String | `"actitopp"` | **Achse A**: `actitopp` (KIT, kalibriert) \| `gta` (LLM-Agenten, **experimentell**, Fallback actiTopp) \| `template` (einfacher Generator; **nicht** im UI-Select, nur Legacy-Fallback). Konsument: Stufe „Aktivitätenpläne" via `behaviorSourceEffective()` → `GtaBehaviorRunner`/`ActitoppRunner`/`ActivityGenerator`. UI: Select „Verhaltensmodell" | `actitopp` produktiv; `gta` **experimentell** |
| `gtaWhatIf` | String | `""` | GTA 3d: Freitext-Was-wäre-wenn-Kontext (Policy/Technologie) für die LLM-Tagesplanung; leer = normales Verhalten. UI: Textarea, nur sichtbar bei `behaviorSource === 'gta'` | experimentell |
| `useActitopp` | boolean | `true` | **@Deprecated** – durch `behaviorSource` ersetzt; nur noch in `behaviorSourceEffective()` gelesen. UI: Checkbox „KIT actiTopp für Aktivitätenpläne" (Block „Bevölkerung") existiert noch | Legacy |
| `useKitPopulation` | boolean | `true` | Haushalte aus KIT-synthetisiertem Demografie-Sample (realistische Zusammensetzungen) statt Ad-hoc-Ziehung. Konsument: `PopulationGenerator` | produktiv |
| `calibrateModeChoice` | boolean | **`false`** | Iterative ASC-Kalibrierung der Moduswahl gegen Ziel-Modal-Split (mehrere Sim-Läufe, teuer). Konsument: `GenerationService` (Stufe „Simulation (logiTopp)") → `ModeChoiceCalibrator`. UI: Checkbox „Verkehrsmittelanteile kalibrieren (langsam)", gated `*ngIf="hasTarget('logitopp')"` | **experimentell** |
| `calibrateDistance` | boolean | **`false`** | Iterative Zielwahl-Kalibrierung von `b_intrazonal` auf den MiD-Nahanteil `<1 km`; benötigt mehrere logiTopp-Läufe und eine intrazonale Pkw-Distanz unter 1 km. Konsument: `GenerationService` → `DistanceCalibrator`. UI: „Kurze Wege kalibrieren (experimentell, langsam)" | **experimentell** |
| `correctDistanceDecay` | boolean | **`false`** | Erzeugt einen szenariogebundenen Zielwahl-Parameterstand und addiert den über Seeds 42/43/44 bestätigten festen Term `+0,06` auf `b_tt_car_d`, `b_tt_car_p`, `b_tt_put`, `b_tt_ped`, `b_tt_bike`. Komponiert mit `calibrateDistance`, Modus-Kalibrierungen und Netz-Feedback; verändert nie die geteilten Legacy-Dateien. Konsument: `GenerationService` → `DestinationChoiceParameterAdjuster`. UI: „Längere Wege begünstigen (experimentell)" | **experimentell** |
| `calibrateModeDistance` | boolean | **`false`** | Gemeinsame Kalibrierung von ÖV-Niveau (`asc_put`) und ÖV-Fernweg-Schräge (`b_tt_put`) gegen MiD; mehrere logiTopp-Läufe. Konsument: `GenerationService` → `ModeDistanceCalibrator`. UI: „ÖV nach Distanz kalibrieren" | **experimentell** |
| `calibrationTarget` | Map\<String, Double\> | MiD 2017 bundesweit: `pedestrian 0.22, bike 0.11, car 0.43, passenger 0.14, publicTransport 0.10` | Ziel-Modal-Split der Kalibrierung; leer/null → Fallback `ModeChoiceCalibrator.MID_2017`. Auch von der LLM-Moduswahl als Referenz gelesen. UI: editierbare Zeilen + RegioStaR-Preset-Select, nur sichtbar wenn `calibrateModeChoice` an | Datum produktiv; nur von experimentellen Flags genutzt |
| `llmModeChoice` | boolean | **`false`** | GTA 3c: zusätzliche LLM-gestützte Moduswahl auf den simulierten Trips (geerdet in Reisezeiten je Modus) – alternativer Modal-Split zum Vergleich mit MNL/MiD. Benötigt logiTopp + LLM. Konsument: Stufe „LLM-Moduswahl" → `LlmModeChoiceRunner`. UI: Checkbox, gated `hasTarget('logitopp')` | **experimentell** |
| `sumoFeedback` | boolean | **`false`** | Netz-Feedback: staubelastete SUMO-Reisezeiten fließen MSA-gedämpft in die Pkw-Matrix zurück, logiTopp entscheidet neu (Gleichgewichts-Schleife). Benötigt logiTopp + SUMO. Konsument: Stufe „Netz-Feedback" → `FeedbackMath`. UI: Checkbox, gated `hasTarget('logitopp') && hasTarget('sumo')` | **experimentell** |
| `publicTransport` | boolean | **`false`** | **@Deprecated, zur JSON-Kompatibilität behalten und ignoriert.** Bei SUMO wird ÖV automatisch versucht, wenn Registry-Feed und Transit-Werkzeuge verfügbar sind; keine UI-Checkbox mehr. | Legacy |
| `gtfsUploadPath` | String | – (unset) | Pfad eines hochgeladenen GTFS-Feeds; Fallback, falls der Auto-Fetch keinen Regionsfeed findet. Gesetzt vom Upload-Endpoint in `ScenarioController`. UI: „GTFS-ZIP hochladen"-Link im automatischen ÖV-Hinweis | experimenteller Fallback |
| `carsPerHousehold` | double | `1.0` | Mittlere Pkw-Ausstattung der Haushalte; `PopulationGenerator.drawCarCount(...)` (stochastische Rundung) | produktiv |

**`behaviorSourceEffective()`-Fallback-Logik (wichtig!):** Explizites `behaviorSource` gewinnt,
wenn nicht null/blank; sonst wird aus dem Legacy-Flag abgeleitet: `useActitopp == true` →
`"actitopp"`, sonst `"template"`. **Konsumenten müssen immer `behaviorSourceEffective()`
aufrufen, nie das rohe Feld lesen.** Zusätzlich fällt die Pipeline zur Laufzeit von `gta` auf
`actitopp` zurück, wenn `synthesis.llm.enabled == false`, das LLM nicht erreichbar ist oder der
GTA-Lauf eine Exception wirft (Log: „… Fallback actiTopp").

### 1.3 `Logistics` (Studio-Block „Logistik (logiTopp)")

| Feld | Typ | Default | Wirkung / Konsument | Status |
|---|---|---|---|---|
| `parcelsPerPersonWeek` | double | `0.75` | Mittelwert Paketbestellungen je Person/Woche (logiTopp `NumberOfParcelsSelector`); `LogisticsGenerator` | produktiv |
| `parcelStdDev` | double | `0.5` | Standardabweichung dazu | produktiv |
| `maxParcelsPerPerson` | int | `10` | Obergrenze je Person | produktiv |
| `shareHome` / `shareWork` / `sharePackstation` | double | `0.8` / `0.1` / `0.1` | Zustellort-Anteile | produktiv |
| `depots` | List\<Depot\> | leer | Verteilzentren (auf der Karte platziert) | produktiv |
| `services` | List\<DeliveryService\> | DHL 0.45, Hermes 0.20, DPD 0.15, GLS 0.10, UPS 0.10 | KEP-Dienstleister-Marktanteile | produktiv |

`Depot`-Felder: `name` (`"Depot"`), `lat`, `lon`, `employees` (`20`), `share` (`1.0`),
`vehicleType` (`"TRUCK"` \| `"CARGO_BIKE"` – als Union-Typ auch im Frontend-Modell).
`DeliveryService`-Felder: `name`, `share`.

### 1.4 Experimentell vs. produktiv – Kurzliste (Stand 2026-08-17)

**Experimentell (alle default `false` bzw. nicht die Default-Option; Beförderung nur über das
Gate in `synthesis-change-control`):** `calibrateModeChoice`, `calibrateDistance`,
`correctDistanceDecay`, `calibrateModeDistance`, `llmModeChoice`, `sumoFeedback`,
`behaviorSource = "gta"` (+ Anhängsel `gtaWhatIf`, `gtfsUploadPath`).
Ziel `sumo` in `targetModels` ist funktionsfähig, aber **nicht** im Default enthalten.

**Produktiv-Default (an, ohne Zutun):** automatische OSM-Datenquellenwahl,
`useRealRouting`, `externalTrips`, `useKitPopulation`, `behaviorSource = "actitopp"`,
Targets `logitopp` + `mobilecity`.

---

## 2. Betriebs-Properties (`SynthesisProperties`, Prefix `synthesis.`)

Quelle: `backend/src/main/java/de/synthesis/SynthesisProperties.java`
(`@ConfigurationProperties(prefix = "synthesis")`). Gesetzt in
`backend/src/main/resources/application.yml` – dort sind **ganze Achsen auskommentiert
(`# sumo:`, `# llm:`, `# transit:`, Teile von `overpass`/`simulation`)**: Das ist bewusste
**Doku der verfügbaren Keys**, die Java-Defaults gelten dann. Nicht löschen; beim Ergänzen von
Keys mitpflegen.

### 2.0 Root

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `synthesis.data-dir` | `data` | Wurzel für Szenarien (`data/scenarios/<id>`), `llm-cache`, `gtfs-cache`, `census-cache` | `ScenarioRepository`, `GenerationService` |
| `synthesis.max-zones` | `600` | Default für `zoning.maxZones` neuer Szenarien; alte API-Vorschauen ohne Feld verwenden ihn ebenfalls | `ScenarioController.defaults`, `GenerationService` |
| `synthesis.max-zones-hard-limit` | `1200` | Harte Schutzgrenze für das Szenariofeld und die Vorschau, weil mehrere OD-Artefakte O(n²) wachsen | `ScenarioController`, `GenerationService` |

### 2.1 `synthesis.overpass` (OSM-Datenquelle)

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `live-data-enabled` | `true` | Zentraler Betriebs-/Testschalter für Live-POI-/Place-Abfragen. Ein Szenario-Snapshot bleibt auch bei `false` nutzbar; ohne Snapshot nutzt die Pipeline synthetische Daten. Kein Nutzerparameter im Studio. | `GenerationService` |
| `endpoint` | `https://overpass-api.de/api/interpreter` | Primärer Overpass-Server | `OverpassClient` |
| `mirrors` | `["https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter"]` | Ausweich-Endpunkte bei 504/429; `allEndpoints()` = primär + Mirrors, dedupliziert | `OverpassClient` |
| `timeout-seconds` | `30` | HTTP-Timeout | `OverpassClient` |

### 2.2 `synthesis.census` (amtliches Bevölkerungsraster Deutschland)

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | Automatische Zensus-Quelle zentral deaktivierbar; kein Szenario-Schalter | `CensusPopulationSource` |
| `version` | `zensus-2022-2024-10-08` | Stabiler Cache-/Manifest-Versionsbezeichner des geprüften Archivs | `CensusPopulationSource` |
| `reference-date` | `2022-05-15` | Amtlicher Zensus-Stichtag für UI und Manifest | `GenerationService` |
| `source-url` | Destatis `Zensus2022_Bevoelkerungszahl.zip` | Amtliches ZIP mit 100-m-/1-km-/10-km-CSV | `CensusPopulationSource` |
| `archive-sha256` | `1a8428…a941a` | Erlaubter Inhalt; Abweichung führt ohne Cache-Vergiftung zum OSM-/Dichte-Fallback | `CensusPopulationSource` |
| `grid-entry` | `Zensus2022_Bevoelkerungszahl_100m-Gitter.csv` | Aus dem ZIP atomar extrahierter Rastereintrag | `CensusPopulationSource` |
| `timeout-seconds` | `180` | HTTP-Zeitbudget des einmaligen Downloads | `CensusPopulationSource` |
| `max-archive-mb` | `64` | Download-Obergrenze (amtlicher Stand: ca. 18 MB komprimiert) | `CensusPopulationSource` |

### 2.3 `synthesis.simulation` (logiTopp, Stufe „Simulation (logiTopp)")

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | Stufe global abschaltbar (SKIPPED) | `GenerationService`-Gate |
| `project-dir` | `../validation/mobitopp-rastatt` | Legacy-Projekt mit `gradlew runDeliverySynthesis` | `SimulationRunner` |
| `timeout-minutes` | `30` | Timeout des Gradle-Laufs | `SimulationRunner` |
| `calibration-iterations` | `8` | Max. Iterationen der Modal-Split-Kalibrierung (je Iteration ein Sim-Lauf) | Kalibrierschleife (`ModeChoiceCalibrator`) |
| `calibration-tolerance` | `0.02` | Abbruch, wenn RMSE der Modus-Anteile zum Ziel darunter liegt | Kalibrierschleife |

### 2.4 `synthesis.actitopp` (KIT actiTopp, Stufe „Aktivitätenpläne")

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | actiTopp abschaltbar (Fallback Template-Generator) | `GenerationService`-Gate |
| `project-dir` | `../validation/actitoppNG` | actitoppNG-Projekt (`gradlew` + `runSynthesis`) | `ActitoppRunner` |
| `timeout-minutes` | `20` | Timeout des Gradle-Laufs | `ActitoppRunner` |

### 2.5 `synthesis.sumo` (nachgelagerte Verkehrssimulation + „Netz-Feedback")

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | Stufe abschaltbar (SKIPPED) | `GenerationService`-Gate |
| `bin-dir` | **berechnet** aus `$SUMO_HOME` (Ableitung s. u.) | Verzeichnis mit `sumo`/`netconvert`/`duarouter` | `SumoRunner` |
| `sumo-home` | **berechnet**: `$SUMO_HOME`; ungesetzt → `sumo` auf dem PATH, danach versionsunabhängiger macOS-Framework-Fallback `/Library/Frameworks/EclipseSUMO.framework/EclipseSUMO/share/sumo` | SUMO-Daten/Tools (i. d. R. `.../share/sumo`) | `SumoRunner`; wird an `TransitRunner` durchgereicht |
| `day` | `0` | Simulierter Wochentag (0 = Montag) | `SumoRunner` |
| `max-trips` | `4000` | Obergrenze eingespeister Fahrzeug-Trips (Performance) | `SumoRunner` |
| `timeout-minutes` | `15` | Timeout des SUMO-Laufs | `SumoRunner` |
| `feedback-max-zones` | `150` | Netz-Feedback: Zonen-Obergrenze (OD-Routing ist n²) | Stufe „Netz-Feedback" |
| `feedback-iterations` | `3` | Max. Gleichgewichts-Iterationen (je Iteration 1 logiTopp- + 1 SUMO-Lauf) | Stufe „Netz-Feedback" |
| `feedback-tolerance` | `0.01` | Konvergenz: Abbruch bei mittlerer Faktor-Änderung darunter | Stufe „Netz-Feedback" |

**Default-Ableitung `bin-dir`** (Logik in `Sumo.defaultBinDir()`, exakt so implementiert):
1. `$SUMO_HOME` gesetzt und `$SUMO_HOME/bin/sumo` existiert → `$SUMO_HOME/bin` (Standard-Layout).
2. Sonst, wenn `$SUMO_HOME/../../bin/sumo` existiert → dieses `bin` (macOS-Framework-Layout:
   `SUMO_HOME = .../EclipseSUMO/share/sumo`, Binaries unter `.../EclipseSUMO/bin`).
3. Sonst `$SUMO_HOME/bin` (auch wenn nicht existent – `SumoRunner.isAvailable()` fängt das ab →
   SKIPPED „SUMO_HOME/Binaries prüfen").
4. `$SUMO_HOME` ungesetzt/leer → Wurzel aus dem ersten ausführbaren `sumo` auf dem PATH;
   ohne Treffer macOS-Fallback `/Library/Frameworks/EclipseSUMO.framework/EclipseSUMO/bin`.

Der Framework-Fallback verwendet den Installer-Symlink `Versions/Current` indirekt und enthält
keine Versionsnummer. Verifiziert mit SUMO 1.27.1 am 2026-08-11.

### 2.6 `synthesis.transit` (automatische ÖV-Ergänzung der SUMO-Simulation)

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | ÖV-Provider abschaltbar (ÖV entfällt im SUMO-Lauf) | `GenerationService.buildPtProvider` |
| `venv-python` | `../validation/transit-venv/bin/python` in `application.yml` | Reproduzierbarer lokaler Python-Interpreter mit pandas/rtree/pyproj; fehlt er, wird ÖV verständlich übersprungen | `TransitRunner.isAvailable()` |
| `gtfs2pt` | **berechnet**: `$SUMO_HOME/tools/import/gtfs/gtfs2pt.py` (ungesetzt → macOS-Framework-`share/sumo` wie oben) | Pfad zu gtfs2pt.py | `TransitRunner` |
| `feeds` | `[kvv]` (Registry s. u.) | Auto-Fetch-Feeds: Verbund-GTFS mit Abdeckungs-Bbox | `GtfsSource` |
| `max-feed-mb` | `200` | Obergrenze Auto-Fetch-Download (MB); größere Feeds → Upload-Empfehlung | `GtfsSource` |
| `window-start-hour` / `window-end-hour` | `6` / `10` | Zeitfenster (Stunden) für den Fahrplan-Zuschnitt – begrenzt die gtfs2pt-Laufzeit | `GtfsCropper`/`TransitRunner` |
| `timeout-minutes` | `25` | gtfs2pt-Map-Matching (tracemapper) ist langsam → großzügiger Timeout; Ergebnis wird gecacht | `TransitRunner` |
| `modes` | `bus` | gtfs2pt-Modi (OSM-Namen). Aktuell nur Bus – Netz ist road-only; Rail/Tram-Befund in `docs/06-gta-integration-konzept.md` („Phase 2 – SUMO") | `TransitRunner` |

**Feed-Registry-Struktur** (`Transit.Feed`): je Feed `name`, `url`, `min-lat`, `min-lon`,
`max-lat`, `max-lon`. Auswahl über `Feed.covers(lat, lon)`: **enthält die Feed-Bbox das
Regions-Zentrum?** Erster Treffer gewinnt. Vorbelegt ist genau ein Feed (Stand 2026-07-08):

| name | url | Bbox (minLat, minLon, maxLat, maxLon) | Anmerkung |
|---|---|---|---|
| `kvv` | `https://www.nvbw.de/fileadmin/user_upload/service/open_data/fahrplandaten_mit_liniennetz/kvv.zip` | 48.55, 7.95, 49.25, 8.95 | Karlsruher Verkehrsverbund (deckt u. a. Rastatt); NVBW/MobiData-BW, ODbL |

Neue Verbünde: in `application.yml` unter `synthesis.transit.feeds` ergänzen. **Achtung:** Eine
YAML-Liste **ersetzt** den Java-Default komplett – KVV dann mit auflisten. Kein Feed für die
Region + kein Upload → ÖV entfällt mit Log-Hinweis.

### 2.7 `synthesis.llm` (Achse A GTA + LLM-Moduswahl)

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | LLM-Nutzung global abschaltbar (GTA → Fallback actiTopp; Stufe „LLM-Moduswahl" SKIPPED) | Gates in `GenerationService` |
| `endpoint` | `http://localhost:11434/v1` | OpenAI-kompatibler Basis-Endpunkt (Ollama) | `GtaBehaviorRunner`, `LlmModeChoiceRunner` |
| `model` | `qwen2.5:7b-instruct` | Modellname | dito |
| `temperature` | `0.7` | Sampling-Temperatur | dito |
| `timeout-seconds` | `120` | Timeout je LLM-Call | dito |
| `max-personas` | `60` | Obergrenze distinkter Personas (LLM-Calls); je Persona ca. 30–40 s auf einem M1 | `GtaBehaviorRunner` |

Beachte: `synthesis.llm.enabled` ist default `true`, **wirkt aber nur**, wenn ein Szenario
`behaviorSource = "gta"` oder `llmModeChoice = true` setzt – beides experimentell. Ist kein
Ollama erreichbar, greift der Fallback; es gibt keinen harten Fehler.

---

## 3. Env-Variablen

| Variable | Wirkung | Konsument |
|---|---|---|
| `SUMO_HOME` | Quelle der **berechneten Defaults** `synthesis.sumo.bin-dir`, `synthesis.sumo.sumo-home`, `synthesis.transit.gtfs2pt` (Ableitung s. Abschnitte 2.4/2.5). Ungesetzt → PATH-Erkennung, danach versionsunabhängiger macOS-Framework-Fallback | `SynthesisProperties.Sumo`/`Transit` – statisch bei Bean-Erzeugung gelesen; **Backend-Neustart nötig** nach Änderung |
| `SYNTHESIS_TRANSIT_VENV` | **Nur Test-Gate, keine Laufzeit-Wirkung:** `backend/src/test/java/de/synthesis/generation/pipeline/PtIntegrationTest.java` liest sie via `System.getenv` und überspringt den (langsamen) ÖV-Integrationstest, wenn ungesetzt („SYNTHESIS_TRANSIT_VENV nicht gesetzt – ÖV-Integrationstest übersprungen"). Zeigt auf den Python-Interpreter eines venv mit pandas/rtree/pyproj. Für den **Betrieb** gilt stattdessen `synthesis.transit.venv-python` | `PtIntegrationTest` |

---

## 4. Checkliste: Neues Szenario-Flag anlegen

Reihenfolge einhalten; jeder Schritt hat einen historischen Grund (Vorfälle:
`synthesis-failure-archaeology`).

1. **`Scenario.java`**: public Feld mit Initialisierer (= Default) + Javadoc, die Achse und den
   Experimentell-Status benennt. **Default `false`/aus, bis das Feature verifiziert ist**
   (Beförderung via `synthesis-change-control`).
2. **`frontend/src/app/core/models/scenario.model.ts`**: Feld im `Scenario`-Interface spiegeln
   (gleicher Name, TS-Typ, JSDoc-Einzeiler). Vergessen = Type-Checks und Autocomplete lügen.
3. **`frontend/src/app/features/studio/studio.component.html`**: Checkbox (bzw. Select/Input) im
   passenden Block („Bevölkerung" oder „Modellierung") mit `[(ngModel)]` und erklärendem
   `title`-Attribut (Hinweis auf Kosten/Voraussetzungen). **Gating** über die Voraussetzungen:
   `*ngIf="hasTarget('logitopp')"`, `hasTarget('sumo')` etc. – ein Flag, dessen Voraussetzung
   fehlt, darf nicht anklickbar sein. **ACHTUNG: KEINE typographischen Anführungszeichen
   („ " ‚ ') im `title`-Attribut** – bricht den strict-AOT-Build mit **NG5002**
   (Vorfall 2026-07-02); gerade Quotes verwenden oder umformulieren.
4. **`GenerationService`-Gate**: Am Anfang der betroffenen Stufe prüfen; wenn aus →
   `job.stage("<Stufenname>").status = "SKIPPED"` + `job.log("... übersprungen (<Grund>)")`.
   Konvention: **SKIPPED = bewusste Abwahl/fehlende Voraussetzung, FAILED = echter Fehler** (der
   Nutzer muss beides unterscheiden können). Bei neuer Stufe: `STAGES`-Liste ergänzen (aktuell
   14 Stufen: Zonierung … LLM-Moduswahl).
5. **`application.yml`**: Falls das Flag eine begleitende Betriebs-Property bekommt →
   auskommentierten Doku-Block ergänzen (Muster `# sumo:` / `# transit:`).
6. **`docs/06-gta-integration-konzept.md`**: Eintrag im passenden Phasen-Abschnitt (was, warum,
   Status experimentell).
7. **Tests/E2E anpassen**: Default-Szenario-Fixtures; für Integrationen mit externem Tooling ggf.
   Env-Gate nach dem Muster `PtIntegrationTest`/`SYNTHESIS_TRANSIT_VENV`.
8. **Default `false` lassen**, bis Verifikation + Change-Control-Gate durchlaufen sind.

Die frühere Referenz `publicTransport` ist seit 2026-09-01 bewusst kein neues Flag-Muster mehr:
ÖV wird für SUMO automatisch versucht, das alte Feld ist nur JSON-Kompatibilität.

## 5. Checkliste: Neue Betriebs-Property anlegen

1. **`SynthesisProperties.java`**: Feld in der passenden inneren Klasse (oder neue innere Klasse
   nach dem Muster `Transit`), mit Default-Initialisierer, Javadoc, Getter **und** Setter
   (Spring-Binding braucht beide). Berechnete Defaults (Env-Ableitung) als `private static`
   Methode wie `defaultBinDir()`/`defaultGtfs2pt()`.
2. **`application.yml`**: Key eintragen – aktiv, wenn er im Standard-Deployment gesetzt sein
   soll; sonst **auskommentiert mit Inline-Kommentar** (die auskommentierten Achsen sind die
   Key-Doku dieses Repos).
3. **Konsument**: Property nur über die Config-Klasse injizieren (`SynthesisProperties.Xyz` in
   den Runner-Konstruktor, Muster `SumoRunner(SynthesisProperties.Sumo config)`,
   `TransitRunner(SynthesisProperties.Transit config, String sumoHome)`) – kein
   `@Value`-Wildwuchs.
4. **Kebab-Case beachten**: Java `maxFeedMb` ↔ YAML `max-feed-mb`.
5. **docs/06** ergänzen, falls die Property Feature-Verhalten steuert (nicht nur ein Timeout).
6. Bei berechnetem Default: Test, der die Ableitung sichert (Env-abhängig → Gate wie in
   Abschnitt 3).

---

## 6. Drift-Schutz: Re-Verifikations-Greps

Flags driften! Vor jeder Entscheidung auf Basis dieser Tabellen (und bei jeder Wartung dieses
Skills) gegen die Quelldateien prüfen – alle Kommandos vom Repo-Root:

```bash
# 1. Alle Szenario-Felder + Defaults (muss 1:1 zu Abschnitt 1 passen):
grep -n "public " backend/src/main/java/de/synthesis/scenario/Scenario.java

# 2. Alle Betriebs-Property-Felder + Defaults (muss 1:1 zu Abschnitt 2 passen):
grep -n "private .*=\|public static class" backend/src/main/java/de/synthesis/SynthesisProperties.java

# 3. Frontend-Modell synchron zu Scenario.java?
grep -n ":" frontend/src/app/core/models/scenario.model.ts

# 4. YAML-Doku-Achsen vollständig (auch auskommentierte zählen!)?
grep -n "^\s*#\?\s*[a-z-]*:" backend/src/main/resources/application.yml

# 5. Welche Stufe liest welches Flag (Konsumenten-Spalte verifizieren)?
grep -rn "scenario.population\.\|scenario.targets()" backend/src/main/java/de/synthesis/generation/GenerationService.java

# 6. Stufenliste + SKIPPED-Gates aktuell?
grep -n "STAGES = List.of\|SKIPPED" backend/src/main/java/de/synthesis/generation/GenerationService.java

# 7. UI-Ort + Gating der Flags im Studio:
grep -n "ngModel)]=\"scenario\.\|hasTarget(" frontend/src/app/features/studio/studio.component.html

# 8. Env-Variablen-Inventar (Erwartung: nur SUMO_HOME + SYNTHESIS_TRANSIT_VENV):
grep -rn "System.getenv" backend/src/main/java backend/src/test/java

# 9. Feed-Registry-Vorbelegung:
grep -n -A6 "static Feed kvv" backend/src/main/java/de/synthesis/SynthesisProperties.java

# 10. Property-Konsumenten (Runner-Konstruktoren):
grep -rn "SynthesisProperties\." backend/src/main/java/de/synthesis/generation/pipeline/ | grep -v import
```

Weicht ein Grep-Ergebnis von diesem Dokument ab, gilt der **Code**; dieses Dokument
aktualisieren und den Stand-Vermerk unten anpassen.

---

## Provenienz & Wartung

- **Stand: 2026-08-25.** Ground Truth vollständig gelesen und übertragen aus:
  `backend/src/main/java/de/synthesis/scenario/Scenario.java`,
  `backend/src/main/java/de/synthesis/SynthesisProperties.java`,
  `backend/src/main/resources/application.yml`,
  `frontend/src/app/core/models/scenario.model.ts`; Konsumenten/Gates per Grep verifiziert in
  `backend/src/main/java/de/synthesis/generation/GenerationService.java`,
  `backend/src/main/java/de/synthesis/generation/pipeline/*.java`,
  `frontend/src/app/features/studio/studio.component.html`,
  `backend/src/main/java/de/synthesis/scenario/ScenarioController.java`,
  `backend/src/test/java/de/synthesis/generation/pipeline/PtIntegrationTest.java`.
- **Volatil** (bei jeder Berührung der Quelldateien neu prüfen, Greps in Abschnitt 6):
  Default-Werte, Experimentell-Status (Flags werden befördert!), Feed-Registry (weitere Verbünde
  geplant), `transit.modes` (Rail/Tram-Befund offen, docs/06), hart codierte
  SUMO-Framework-Version 1.26.0, UI-Positionen im Studio-Template, Stufenliste (aktuell 14).
- **Wartungsregel:** Jede Änderung an `Scenario.java` oder `SynthesisProperties.java` zieht ein
  Update dieses Skills nach sich (Teil der Doku-Pflicht aus `synthesis-change-control`).
- Geschwister-Skills (nicht duplizieren): `synthesis-change-control` (Beförderungs-Gate für
  Flags), `synthesis-architecture-contract`, `synthesis-run-and-operate`,
  `synthesis-build-and-env`, `synthesis-debugging-playbook`, `synthesis-failure-archaeology`,
  `verkehrsmodellierung-reference`.
