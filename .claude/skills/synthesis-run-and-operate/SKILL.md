---
name: synthesis-run-and-operate
description: SyntheSis fahren — Backend/Frontend starten, Szenario anlegen und generieren (UI-Walkthrough + REST-API mit curl), Artefakte finden (wo liegt kpis.json, zones.csv, tours.geojson, sumo/summary.json), Job-Status pollen, Logs lesen. LADEN bei Fragen wie „Szenario generieren", „REST-API-Endpunkte", „Artefakte finden", „wo liegt kpis.json", „welche Stufe hat welches Gate" (Ursachen-Triage für SKIPPED/FAILED wohnt in synthesis-debugging-playbook), „Generierung hängt/QUEUED", „GTFS hochladen", „Bevölkerungsraster-Upload", „Szenario-Vergleich abrufen", „run.log/sumo.log finden", „welche Klasse schreibt welche Datei".
---

# SyntheSis: Betrieb & Bedienung (Runbook)

Wie man das System startet, ein Szenario anlegt, die 14-stufige Pipeline laufen lässt und
die Ergebnisse (Artefakte) findet. Publikum: Mid-Level-Entwickler und Agenten, die das
System bedienen — nicht verändern.

## Wann NICHT nutzen

| Anliegen | Stattdessen |
|---|---|
| Build schlägt fehl, JDK/SUMO/venv-Installation | `synthesis-build-and-env` |
| Ergebnisse fachlich interpretieren (KPIs, Modal-Split) | `synthesis-diagnostics-and-tooling` |
| Was ein Config-Flag genau bewirkt (application.yml) | `synthesis-config-and-flags` |
| Ein Lauf schlägt fehl, du debuggst die Ursache | `synthesis-debugging-playbook` |
| Architektur/Pipeline-Stufen ändern | `synthesis-architecture-contract` |
| Code ändern, committen, pushen | `synthesis-change-control` |

## 1. Start

```bash
# Backend (Spring Boot, Port 8090) – Artefakte landen unter backend/data/scenarios/<id>/
cd backend && mvn spring-boot:run

# Frontend (Angular + Leaflet, Port 4300, Proxy /api → 8090)
cd frontend && npm install && npm start
```

- Studio: http://localhost:4300 · Swagger-UI: http://localhost:8090/swagger-ui.html
- Der `data-dir` ist relativ (`synthesis.data-dir: data` in `backend/src/main/resources/application.yml`)
  → das Backend **aus `backend/` starten**, sonst entsteht ein zweiter `data/`-Baum.
- Job-Status lebt **in-memory** (`GenerationService.jobs`): nach Backend-Neustart ist
  `GET /generation` 404, die Artefakte auf Platte bleiben aber erhalten.

## 2. UI-Walkthrough (Studio, 6 Schritte)

1. **Region:** „+ Neu" → „Region zeichnen" (Rechteck auf der Karte aufziehen) oder
   „Aktuellen Kartenausschnitt übernehmen". Danach laufen Zonen-Vorschau und
   OSM-Einwohnerschätzung automatisch.
2. **Parameter:** Zellgröße (min. 100 m), maximales Zonenlimit (Default 600, max. 1.200) und
   Rastermodus (`grid` | flexibel ausgerichtet `epsg3035` | amtlich `inspire`); die Zellgröße
   wird automatisch angehoben, wenn das gewählte Limit überschritten würde. Einwohner (leer =
   automatische Quellenwahl), Sampling; optional
   Bevölkerungsraster-Upload (Zensus-CSV).
3. **Sektion „Modellierung"**: Verhaltensquelle (actiTopp | LLM-Agenten | Template),
   Nachfragesimulation logiTopp, unabhängiger MobileCity-Export und nachgelagerte
   Verkehrssimulation SUMO. SUMO aktiviert logiTopp automatisch; GTFS/ÖV wird ohne
   Szenario-Checkbox automatisch versucht. Dazu kommen Kalibrierung, Netz-Feedback und
   LLM-Moduswahl. Was die Flags tun: `synthesis-config-and-flags`.
4. **Depots:** „Depot platzieren"-Modus aktivieren, auf die Karte klicken; je Depot
   Name/Mitarbeiter/Anteil/Fahrzeugtyp (TRUCK | CARGO_BIKE). Keine Depots = Auto-Platzierung.
5. **„Speichern"** (erst danach existiert die Szenario-ID) → **„Nachfragemodell generieren"**.
   Stufen-Fortschritt + Log erscheinen live im Studio.
6. **Vergleich:** http://localhost:4300/vergleich — mehrere Szenarien nebeneinander
   (Nachfrage-, Logistik-, CO₂-Kennzahlen).

## 3. REST-Referenz

Alle Pfade aus dem Controller-Code abgelesen (Stand 2026-07-08). curl-Beispiele sind
**UNGETESTET**, die Struktur ist aber durch den Controller-Code belegt. Direkt gegen
`:8090` oder via Frontend-Proxy `:4300/api`.

### Szenarien (`ScenarioController`, `/api/scenarios`)

| Methode + Pfad | Zweck |
|---|---|
| `GET /api/scenarios` | Alle Szenarien (neueste zuerst) |
| `GET /api/scenarios/defaults` | Default-Szenario als Vorlage (alle Feld-Defaults) |
| `GET /api/scenarios/{id}` | Ein Szenario (404 wenn unbekannt) |
| `POST /api/scenarios` | Anlegen → 201; Server vergibt 8-stellige ID (UUID-Präfix) |
| `PUT /api/scenarios/{id}` | Vollständiges Update (id/createdAt bleiben erhalten) |
| `DELETE /api/scenarios/{id}` | Löscht Szenario **inkl. aller Artefakte** → 204 |
| `POST /api/scenarios/{id}/gtfs` | GTFS-Upload, multipart `file`, **ZIP-Pflicht** (sonst 400) |

Validierung bei POST/PUT (sonst 400): `region` gesetzt, `minLat < maxLat` und
`minLon < maxLon`, `zoning.cellSizeMeters >= 100`, `zoning.maxZones` zwischen 100 und der
Betriebsgrenze (Default 1.200), Modus `grid|epsg3035|inspire`.

```bash
# Anlegen – Minimalbeispiel; alle nicht gesetzten Felder bekommen Defaults (UNGETESTET)
curl -s -X POST http://localhost:8090/api/scenarios \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Rastatt klein",
    "region": {"minLat": 48.83, "minLon": 8.15, "maxLat": 48.88, "maxLon": 8.25},
    "zoning": {"cellSizeMeters": 700, "maxZones": 600, "mode": "epsg3035"},
    "population": {"totalPersons": 250, "seed": 42, "behaviorSource": "actitopp"},
    "targetModels": ["logitopp", "sumo"]
  }'
# → {"id":"ab12cd34", ...}
```

Wichtigste Felder des Scenario-JSON (`Scenario.java`): `region` (BBox),
`zoning.{cellSizeMeters, maxZones, mode}` (`mode`: `grid` freies Bbox-Raster (Default) \|
`epsg3035` europaweit ausgerichtet in flexiblen 100-m-Schritten \| `inspire` amtliche
100-m-/1-km-/10-km-Stufen; beide LAEA-Modi nur Europa — siehe `synthesis-config-and-flags`),
`population.{totalPersons, samplingFraction, seed, useOsmData,
useRealRouting, externalTrips, behaviorSource, gtaWhatIf, calibrateModeChoice,
calibrationTarget, llmModeChoice, sumoFeedback, carsPerHousehold}`,
`logistics.{parcelsPerPersonWeek, shareHome/Work/Packstation, depots[], services[]}`,
`targetModels` (leer/null ⇒ Default `["logitopp","mobilecity"]` — siehe `Scenario.targets()`).

```bash
# GTFS-Feed hochladen (Fallback, wenn Auto-Fetch keinen Regionsfeed findet) (UNGETESTET)
curl -s -X POST http://localhost:8090/api/scenarios/ab12cd34/gtfs \
  -F 'file=@feed.zip'
# → speichert uploads/gtfs.zip und setzt population.gtfsUploadPath im Szenario
```

### Generierung (`GenerationController`, `/api`)

| Methode + Pfad | Zweck |
|---|---|
| `POST /api/scenarios/{id}/generate` | Startet Job → 202 + `GenerationJob`; 404 unbekannt; **409 wenn schon QUEUED/RUNNING** |
| `GET /api/scenarios/{id}/generation` | Job-Status (Polling); 404 wenn kein Job seit Backend-Start |
| `POST /api/region/estimate` | Body = Region-JSON → OSM-Einwohnerschätzung `{estimatedPopulation, source, places[]}` |
| `POST /api/preview/zones` | Body `{"region": {...}, "cellSizeMeters": 700, "mode":"epsg3035", "maxZones":600}` → GeoJSON-Zonenvorschau + effektive `cellSizeMeters` und `autoAdjusted` |
| `GET /api/scenarios/{id}/artifacts` | Flache Liste aller Artefakte `[{path, sizeBytes}]` |
| `GET /api/scenarios/{id}/artifacts/{*path}` | Artefakt-Download (Pfad wie in der Liste, z. B. `simulation/kpis.json`) |

```bash
# Generierung starten und pollen (UNGETESTET)
curl -s -X POST http://localhost:8090/api/scenarios/ab12cd34/generate
curl -s http://localhost:8090/api/scenarios/ab12cd34/generation
```

`GenerationJob`-Antwort (aus `GenerationJob.java`): `state` (QUEUED | RUNNING | DONE |
FAILED), `currentStage`, `stages[]` mit je `{name, status, durationMs, message}` —
Stufen-Status: PENDING | RUNNING | DONE | FAILED | SKIPPED —, `log[]` (Zeilen mit
`HH:MM:SS`-Präfix), `summary{}` (Kennzahlen wie `zones`, `persons`, `sumoVehicles`,
`activitySource`), `error` (nur bei job-weitem FAILED).

```bash
# Artefakte auflisten und kpis.json holen (UNGETESTET)
curl -s http://localhost:8090/api/scenarios/ab12cd34/artifacts
curl -s http://localhost:8090/api/scenarios/ab12cd34/artifacts/simulation/kpis.json
```

### Uploads (`UploadController`, `/api/scenarios/{id}/uploads`)

| Methode + Pfad | Zweck |
|---|---|
| `POST /api/scenarios/{id}/uploads/population-grid` | Bevölkerungsraster (Zensus-2022-CSV o. `lat/lon/population`), multipart `file`; 400 wenn keine verwertbaren Punkte |
| `GET /api/scenarios/{id}/uploads` | Liste `[{name, sizeBytes}]` |
| `DELETE /api/scenarios/{id}/uploads/population-grid` | Raster entfernen → 204 |

```bash
# Zensus-Raster hochladen – wird zur höchstwertigen Bevölkerungsquelle (UNGETESTET)
curl -s -X POST http://localhost:8090/api/scenarios/ab12cd34/uploads/population-grid \
  -F 'file=@zensus2022.csv'
# → {"name":"population-grid.csv","format":"...","points":...,"totalPopulation":...,"skippedLines":...}
```

Upload-Limit: 200 MB (`spring.servlet.multipart.max-file-size`).

### Vergleich (`ComparisonController`)

```bash
# Mehrere Szenarien nebeneinander (UNGETESTET)
curl -s 'http://localhost:8090/api/comparison?ids=ab12cd34,ef56gh78'
```

`GET /api/comparison?ids=a,b,c` → Array mit je `{id, name, params{cellSizeMeters,
samplingFraction, useRealRouting, parcelsPerPersonWeek, depots}, kpis, depotStats, sumo,
simulated}`. `kpis`/`depotStats`/`sumo` sind die eingebetteten Dateien
`simulation/kpis.json`, `simulation/depot-stats.json`, `sumo/summary.json` — `null`,
wenn nicht vorhanden; unbekannte IDs werden stillschweigend weggelassen.

## 4. Artefakt-Baum: was landet wo (mit Producer-Klasse)

Wurzel: `backend/data/scenarios/<id>/` (Pfad-Layout: `ScenarioRepository`). Der
`artifacts/`-Teil ist 1:1 das, was `GET .../artifacts` listet. Stand 2026-07-08;
ältere Läufe auf Platte können weniger Dateien haben.

```
backend/data/scenarios/<id>/
├── scenario.json                     # ScenarioRepository.save (Szenario-Definition, NICHT unter artifacts/)
├── uploads/
│   ├── population-grid.csv           # UploadController (optional)
│   └── gtfs.zip                      # ScenarioController.uploadGtfs (optional)
└── artifacts/
    ├── zone-repository/              # GenerationService.stageAttractivities (CsvArtifacts/GeoJson)
    │   ├── zones.csv                 #   ← zones.csv liegt HIER, nicht top-level!
    │   ├── attractivities.csv
    │   └── zones.geojson
    ├── population/                   # PopulationGenerator
    │   ├── household.csv · person.csv · car.csv
    │   ├── fixedDestination.csv · fixedDestination-logitopp.csv
    │   ├── activity.csv              # ActivityGenerator (Achse A: actiTopp|GTA|Template)
    │   ├── gta-personas.json         # GenerationService.writeGtaPersonas (nur bei GTA)
    │   └── actitopp/                 # ActitoppRunner-Arbeitsdir (input/schedule/actitopp.log)
    ├── matrix/                       # MatrixGenerator + VisumMatrixWriter (Visum $V;D3, .mtx.bz2)
    │   ├── travel_times/             #   CAR_TTC, BIKE_TTC, PED_TTC, PUT_TTC, PUT_ACT, PUT_EGT (Minuten)
    │   ├── travelcosts/              #   CAR_COST, PUT_COST, BS_COST
    │   ├── other/                    #   CAR_DIS, BIKE_DIS, AIR_DIS – Distanzen in METERN (Tutorial-Layout)
    │   ├── DIS_Car.mtx.bz2           #   Distanz-Duplikat top-level (Kompatibilität)
    │   └── time-/cost-matrix-configuration.yaml
    ├── logistics/                    # LogisticsGenerator
    │   ├── distribution-centers.csv · delivery-services.csv · packstations.csv
    │   ├── parcel-demand-config.yaml
    │   └── number_of_parcels_model_parameters.txt · parcel_destination_choice_parameters.txt
    ├── legacy/                       # LegacyExportGenerator (mobiTopp-0.3.x/logiTopp-Layout)
    │   ├── zone-repository/ · zoneproperties/ · demand-data/
    │   ├── matrices/                 #   travel_times/ (inkl. VM_BLOCK.mtx), dummy.mtx, matrix-configuration.yaml
    │   └── logistics/
    │       ├── distribution-centers.csv   # ACHTUNG: Zonen hier per EXTERNAL-Zonen-ID!
    │       ├── cepsp-shares.csv
    │       └── delivery-services.csv
    ├── mobilecity/                   # MobileCityExporter (nur bei Ziel mobilecity)
    │   ├── StudyAreaConfig.csv · REGdistricts.csv · REGattractivity.csv
    │   ├── POPinhabitants.csv · POPtrips.csv · TECCarFleet.csv  # 6 CSVs
    │   ├── districts.shp/.shx/.dbf/.prj          # ShapefileWriter, WGS84
    │   └── summary.json
    ├── simulation/                   # nur bei Ziel logitopp
    │   ├── run-config.yaml           # SimulationRunner – enthält ABSOLUTE Pfade (nicht portabel!)
    │   ├── run.log                   # SimulationRunner – bei Erfolg auf letzte 400 Zeilen gekürzt
    │   ├── mode_choice_calibrated.txt# ModeChoiceCalibrator (nur bei Kalibrierung)
    │   ├── results/                  # logiTopp-Rohoutput: demandsimulationResult.csv,
    │   │                             #   planned-tours.csv, parcel-orders/-states, fleet, vehicle_events
    │   ├── kpis.json                 # SimulationResultIngestor  ← DIE Ergebnis-Datei
    │   ├── depot-stats.json · tours.geojson · parcel-density.geojson   # SimulationResultIngestor
    │   └── llm-mode-choice.json      # GenerationService.writeLlmModeChoice (nur Stufe 14)
    ├── sumo/                         # SumoRunner (nur bei Ziel sumo)
    │   ├── network.osm · net.net.xml · additional.xml · scenario.sumocfg
    │   ├── trips.xml · persons.xml · routes.rou.xml
    │   ├── edgedata.xml · statistics.xml · tripinfo.xml · plain.nod/.edg.xml
    │   ├── congestion.geojson        # Netzlast-Karte (Kanten nach relSpeed)
    │   ├── summary.json              # GenerationService.writeSumoSummary (Fahrzeuge, CO₂ je Typ)
    │   ├── sumo.log
    │   ├── od-trips.xml · od-free/-congested.rou.xml   # nur bei Netz-Feedback
    │   └── transit/                  # TransitRunner (nur bei ÖV): pt_vtypes/pt_stops/pt_vehicles.add.xml,
    │                                 #   gtfs2pt.log, feed-cropped.zip (GtfsSource); dient zugleich als Cache
    └── manifest.json                 # GenerationService.writeManifest (Lauf-Metadaten, Seed, Datenqualität)
```

Schnellantworten: **kpis.json** → `artifacts/simulation/kpis.json` ·
**zones.csv** → `artifacts/zone-repository/zones.csv` · **CO₂** → `artifacts/sumo/summary.json`
(`co2KgByType`) · **Netzlast** → `artifacts/sumo/congestion.geojson` ·
**Touren** → `artifacts/simulation/tours.geojson`.

## 5. Die 14 Pipeline-Stufen (aus `GenerationService.STAGES`)

Stufennamen exakt wie im Job-Log. **FAILED-Semantik:** Bei Stufen 1–11 bricht eine
Exception den **gesamten Job** ab (`state=FAILED`, `error` gesetzt, Folgestufen bleiben
PENDING). Die Stufen 12–14 fangen ihre Fehler selbst: die Stufe wird FAILED (mit
`message`), der Job läuft weiter und kann trotzdem DONE werden. FAILED ≠ SKIPPED:
SKIPPED = bewusst übersprungen (Gate nicht erfüllt), FAILED = versucht und gescheitert.

| # | Stufe (Job-Log-Name) | Gate (läuft nur wenn …) | SKIPPED wenn … | Dauer (überliefert, Größenordnung) |
|---|---|---|---|---|
| 1 | Zonierung | immer (`zoning.mode`: `grid` freies Bbox-Raster \| `epsg3035` flexibel ausgerichtetes 100-m-Stufenraster \| `inspire` amtliche 100-m-/1-km-/10-km-Stufen; LAEA-Modi außerhalb Europas mit Bbox-Fallback; Limit aus `zoning.maxZones`; tatsächlicher Provider in `summary.zoningMode`) | – | Sekunden |
| 2 | OSM-Daten (POIs) | automatisch: Szenario-Snapshot → Live-Overpass → synthetischer Fallback | nie wegen Nutzerabwahl; das Legacy-Feld `population.useOsmData` wird ignoriert | Sekunden–Minute (Overpass) |
| 3 | Attraktivitäten | immer | – | Sekunden |
| 4 | Bevölkerungssynthese | immer | – | Sekunden |
| 5 | Aktivitätenpläne | immer (Achse A intern: gta → actitopp → Template-Fallback, nie SKIPPED) | – | Sekunden (Template/actiTopp) bis Minuten (GTA-LLM, z. B. ~140 s für 18 Persona-Calls) |
| 6 | Matrizen | immer | – | Sekunden–Minuten (GraphHopper-Import; Fallback Luftlinie) |
| 7 | Logistik | immer | – | Sekunden |
| 8 | Legacy-Export | immer | – | Sekunden |
| 9 | MobileCity-Export | Ziel `mobilecity` gewählt | Ziel nicht gewählt | Sekunden |
| 10 | Manifest | immer | – | < 1 s |
| 11 | Simulation (logiTopp) | Ziel `logitopp` gewählt | Ziel nicht gewählt; `synthesis.simulation.enabled=false`; Legacy-Projekt (`validation/mobitopp-rastatt`) nicht gefunden | 250 Personen: Sekunden mit warmem Gradle-Daemon; Kalibrierung (`calibrateModeChoice`) = **mehrere** Sim-Läufe |
| 12 | SUMO (Verkehr) | Ziel `sumo` gewählt | Ziel nicht gewählt; SUMO nicht verfügbar (`$SUMO_HOME`/Binaries fehlen); `demandsimulationResult.csv` fehlt (→ logiTopp-Ziel mitwählen!). ÖV-Teil intern übersprungen, wenn venv/gtfs2pt fehlt | Minuten; mit Fußgängern deutlich länger; **ÖV-Erstlauf ~20–25 min** (gtfs2pt), danach Cache |
| 13 | Netz-Feedback | `population.sumoFeedback` | Flag aus; SUMO-Stufe nicht DONE oder `edgedata.xml` fehlt; logiTopp-Ziel fehlt; Zonen > `feedback-max-zones` (Default 150); keine routbaren Zonenrelationen | je Iteration 1× logiTopp + 1× SUMO (Default max. 3 Iterationen) |
| 14 | LLM-Moduswahl | `population.llmModeChoice` | Flag aus; LLM nicht erreichbar (Ollama); `demandsimulationResult.csv` fehlt | Minuten (~5 LLM-Calls) |

Überlieferte Gesamtdauer: kleiner Voll-Lauf (kleine Region, wenige Personen) ~1–2 min
ohne ÖV/Feedback/Kalibrierung.

## 6. Betriebliche Realitäten

- **Serieller Executor (Pool = 1):** Es läuft immer nur **ein** Job; weitere stehen
  sichtbar als QUEUED. Grund: die logiTopp-Stufe startet eine JVM mit bis zu 12 GB Heap
  und teilt sich das Gradle-Projekt `validation/mobitopp-rastatt`. Ein zweiter Start
  für **dasselbe** Szenario gibt 409.
- **Neustart = Statusverlust:** Jobs sind in-memory; nach Backend-Neustart liefert
  `GET .../generation` 404. Ob ein Lauf durchlief, sieht man an den Artefakten
  (`manifest.json`-Zeitstempel, `simulation/kpis.json`).
- **Caches unter `backend/data/`:** `gtfs-cache/` (rohe Verbund-GTFS-Feeds, eine ZIP je
  Feed-Name; `GtfsSource` lädt nur bei Cache-Miss neu) und `llm-cache/` (GTA-Plan-Cache).
  Der gtfs2pt-Output wird zusätzlich je Szenario unter `artifacts/sumo/transit/`
  gecacht (Wiederläufe melden „ÖV-Infrastruktur aus Cache übernommen").
- **/tmp-Reaping:** GraphHopper-Graphen entstehen in System-Temp-Verzeichnissen
  (`synthesis-routing-*`, `RoutingEngine`); Aufräumen ist Best-Effort, macOS räumt /tmp
  periodisch — dort nichts Wichtiges erwarten oder ablegen.
- **`run-config.yaml` enthält absolute Pfade** — ein Szenario-Verzeichnis ist nicht
  einfach auf eine andere Maschine kopierbar, ohne den logiTopp-Lauf zu wiederholen.
- **Logs:** Pipeline-Log = `log[]` im GenerationJob (UI + REST); Stufen-Details in
  `simulation/run.log` (bei Erfolg auf 400 Zeilen gekürzt!), `sumo/sumo.log`,
  `sumo/transit/gtfs2pt.log`, `population/actitopp/actitopp.log`.
- **Push:** über GitHub Desktop (kein CLI-Push) — Regeln in `synthesis-change-control`.

## Provenienz & Wartung

- Stand **2026-08-27**; Endpunkte aus `ScenarioController`, `GenerationController`,
  `ComparisonController`, `UploadController`, `ArtifactService` abgelesen; Stufen/Gates
  aus `GenerationService`; Artefakt-Pfade aus den `resolve(...)`-Aufrufen der
  Pipeline-Klassen und gegen `backend/data/scenarios/<id>/` gegengeprüft (die dort
  liegenden älteren Läufe enthalten nur den Kern-Baum ohne `simulation/`/`sumo/`).
- curl-Beispiele sind als UNGETESTET markiert; Struktur folgt dem Controller-Code.
- Dauerangaben sind überlieferte Größenordnungen, keine Messwerte.
- Bei Änderungen an `GenerationService.STAGES`, den Controllern oder dem
  Artefakt-Layout dieses Skill mitziehen (Stufen-Tabelle, REST-Referenz, Baum).
