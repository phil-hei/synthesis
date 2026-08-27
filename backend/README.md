# SyntheSis Backend

Spring-Boot-Backend (Java 21, Spring Boot 3.5) für die SyntheSis-Szenariomodellierung:
Erzeugt aus einem auf der Karte gewählten Gebiet plus wenigen Parametern die Eingabe-Artefakte
für ein mobiTopp/logiTopp-Verkehrsnachfragemodell.

## Starten

```bash
mvn spring-boot:run
```

- API: http://localhost:8090/api
- Swagger-UI: http://localhost:8090/swagger-ui.html
- Artefakte werden unter `data/scenarios/<id>/artifacts/` abgelegt

Alternativ als Jar:

```bash
mvn -DskipTests package
java -jar target/synthesis-backend-0.1.0-SNAPSHOT.jar
```

## Module (Pakete)

| Paket | Verantwortung |
|---|---|
| `scenario` | Szenario-Aggregat (Region, Zonierung, Bevölkerung, Logistik), CRUD-REST-API, JSON-Persistenz |
| `generation` | Asynchrone 10-Stufen-Pipeline + Statusendpoint (Polling) |
| `generation.pipeline` | Die einzelnen Stufen (siehe unten) |
| `storage` | Artefakt-Auflistung und -Download mit Pfad-Absicherung |

## Pipeline-Stufen

1. **Zonierung** – Quadratraster über der Region (`GridZoning`); die Zellgröße wird automatisch
   auf die feinste Auflösung angehoben, die das Zonenlimit (`synthesis.max-zones`, Default 600)
   einhält – die Matrizen wachsen mit O(n²)
2. **OSM-Daten** – POIs + Siedlungsdaten (place-Nodes mit Einwohnerzahl) automatisch aus Szenario-Snapshot oder Overpass (`OverpassClient`); Fallback auf synthetische POIs. Die Einwohnerzahl nutzt OSM erst nach einem nicht verfügbaren Zensus-Raster.
3. **Attraktivitäten** – zonale Attraktivität je Aktivitätstyp (`AttractivityCalculator`) → `zones.csv`, `attractivities.csv`, `zones.geojson`. Optional Ring externer Zielzonen (`ExternalZones`, Flag `externalTrips`): Ziele außerhalb des Gebiets ohne Einwohner, damit ein Teil der Wege das Gebiet verlässt (realistischere Weglängen; Luftlinien-Impedanz)
4. **Bevölkerungssynthese** – Haushalte/Personen/Pkw/feste Ziele (`PopulationGenerator`) → `household.csv`, `person.csv`, `car.csv`, `fixedDestination.csv`. Haushaltskompositionen optional aus einem **KIT-synthetisierten Demografie-Sample** (`PopulationSample`, Flag `useKitPopulation`, Resource `kit-population-sample.csv` aus KIT populationsynthesis/IPU: echte Alters-/Geschlechts-/Haushaltsgrößen-Verteilung), sonst Ad-hoc-Ziehung; Erwerbsstatus weiter altersbasiert. Quellen-Priorität: manuelle Gesamtzahl → hochgeladenes alternatives/neueres Raster → automatisch gecachtes, per SHA-256 geprüftes Destatis-Zensus-2022-100-m-Raster (`CensusPopulationSource`) → OSM-Siedlungsdaten → Dichteannahme; deren Quelle, Stichtag und Hash stehen im Manifest. Ein amtlicher BA-Auspendlerexport erhält seine Außenquote automatisch aus der versionierten Resource `reference/ba-workplace-margins-2025.csv` (10.152 Gemeinden, Stichtag 30.06.2025, Original-URL+SHA-256 im Ressourcenheader; verwendete Quelle und Datum in Job-Zusammenfassung und Manifest-Qualitätsfeld).
5. **Aktivitätenpläne** – Wochenpläne je Person. Primär über das kalibrierte KIT-Modell **actiTopp** (`ActitoppRunner` ruft den Gradle-Task `runSynthesis` im `actitoppNG`-Projekt; `ActivityGenerator.generateFromActitopp` übersetzt die actiTopp-Aktivitätstypen in die Legacy-Codes), Fallback auf den einfachen Template-Generator (`ActivityGenerator.generate`) bei Abschaltung/Fehler → `activity.csv`. Voraussetzung für actiTopp: jede Person hat ein festes WORK- und EDUCATION-Ziel (`fixedDestination.csv`), da actiTopp selbst entscheidet, wer arbeitet/lernt
6. **Matrizen** – Reisezeit/Kosten/Distanz je Modus, Visum-`$V;D3`-Format (`MatrixGenerator`, `VisumMatrixWriter`) → `*.mtx.bz2` + Matrix-YAMLs. Auto/Rad/Fuß über echtes Routing auf dem OSM-Straßennetz (`RoutingEngine`, eingebettetes GraphHopper; Netz via Overpass als OSM-XML); ÖV und Kosten aus der Auto-Distanz abgeleitet. Bei deaktiviertem `useRealRouting` oder nicht erreichbarem Netz: Luftlinie × Umwegfaktor (Fallback je Relation)
7. **Logistik** – Verteilzentren, Packstationen, Zustelldienste, Paketnachfrage- und logiTopp-Choice-Model-Parameter (`LogisticsGenerator`)
8. **Legacy-Export** – komplette Eingabestruktur für den Legacy-Stack (mobiTopp 0.3.x + logiTopp) unter `artifacts/legacy/` (`LegacyExportGenerator`): zone-repository, zoneproperties, demand-data mit Legacy-Spalten/OID-Konvention/Tour-Struktur, entpackte Visum-Matrizen + legacy matrix-configuration.yaml sowie `logistics/` mit den Szenario-Depots im `DistributionCenterParser`-Schema (`distribution-centers.csv`, CEPSP-Zuordnung aus den Zustelldiensten) und `cepsp-shares.csv` (renormalisierte Marktanteile); validiert durch erfolgreiche logiTopp-Wochensimulationen (docs/05-validierung.md, Befunde A1–A12)
9. **MobileCity-Export** – district-aggregierte Config-Tabellen für die MobileCity/Takomat-App (`MobileCityExporter`) unter `artifacts/mobilecity/`: `StudyAreaConfig.csv` (Gebiets-Metadaten: Jahre, District-Anzahlen, Kartenzentrum), `REGdistricts.csv` (Zonen → Districts; intern=Typ 1, extern=Typ 0), `REGattractivity.csv` (Zonen-Attraktivität → 4 Purpose-Scores Education/Work/Shopping/Leisure, 0..100 normiert), `POPinhabitants.csv` (Einwohner je Altersgruppe Children/Adults/Seniors je District) und `POPtrips.csv` (mittlere Wege/Person·Tag je Altersgruppe × Zweck; jede Aktivitäts-Transition = ein Weg, Heimweg behält den Herkunftszweck). MobileCity ist ein district-aggregiertes Strategiemodell (keine Einzeltrips); Format gegen den C#-Parser des Config Tools validiert (feste Trenner-Breite, `FindTable`-Sub-Labels), District-IDs synthetisch (`SYN-NNN`) – NUTS3-IDs/Netz-Tabellen/Messagepack mit Takomat abstimmen
10. **Manifest** – `manifest.json` mit Kennzahlen, Datenqualitäts-Stufen und Validierungshinweisen
    Optional **Modal-Split-Kalibrierung** (`ModeChoiceCalibrator`, Flag `population.calibrateModeChoice`):
    passt iterativ die Moduswahl-Konstanten (ASC) im MNL-File an den MiD-Modal-Split an (mehrere
    Sim-Läufe; `synthesis.simulation.calibration-iterations/-tolerance`) und behebt so die
    Rastatt-kalibrierten Verzerrungen.
11. **Simulation (logiTopp)** – orchestriert die logiTopp-Wochensimulation im Legacy-Projekt
    (`SimulationRunner`: per-Szenario `simulation/run-config.yaml` + `sh gradlew runDeliverySynthesis`)
    und verdichtet die Ergebnisse (`SimulationResultIngestor`) zu `simulation/kpis.json`
    (Nachfrage-KPIs + Modal Split inkl. MiD-2017-Referenz), `depot-stats.json`,
    `parcel-density.geojson` und `tours.geojson`; ohne Legacy-Projekt wird die Stufe übersprungen
    (`synthesis.simulation.*` in `application.yml`)

Die CSV-Spalten folgen den Parsern des aktuellen mobiTopp-main-Branch (kit-ifv/mobitopp),
das Matrixformat dem `VisumMatrixParser`; Details und offene Validierungspunkte stehen im
jeweils erzeugten `manifest.json` sowie in [../docs/](../docs/).

## Wichtige Endpoints

```http
GET    /api/scenarios                  Szenarienliste
GET    /api/scenarios/defaults         Default-Szenario (Formular-Vorlage)
POST   /api/scenarios                  Szenario anlegen
PUT    /api/scenarios/{id}             Szenario ändern
DELETE /api/scenarios/{id}             Szenario löschen
POST   /api/scenarios/{id}/generate    Pipeline starten (202)
GET    /api/scenarios/{id}/generation  Pipeline-Status (Polling)
POST   /api/preview/zones              Zonen-Vorschau als GeoJSON (ohne Persistenz)
POST   /api/region/estimate            Einwohnerzahl: Zensus 2022, danach OSM-/Dichte-Fallback
POST   /api/scenarios/{id}/uploads/population-grid   Bevölkerungsraster-Upload (CSV, multipart)
GET    /api/scenarios/{id}/uploads     Liste der Uploads
DELETE /api/scenarios/{id}/uploads/population-grid   Upload entfernen
GET    /api/scenarios/{id}/artifacts   Artefaktliste
GET    /api/scenarios/{id}/artifacts/{pfad}  Artefakt-Download
GET    /api/comparison?ids=a,b,c        Szenario-Vergleich (Parameter + Simulations-KPIs je Szenario)
```

## Konfiguration (`src/main/resources/application.yml`)

```yaml
synthesis:
  data-dir: data                 # Ablageort für Szenarien/Artefakte
  max-zones: 600                 # Obergrenze Zonenzahl (Matrizen wachsen mit O(n²))
  overpass:
    endpoint: https://overpass-api.de/api/interpreter
    timeout-seconds: 30
  census:
    enabled: true
    version: zensus-2022-2024-10-08
    reference-date: 2022-05-15
    source-url: https://www.destatis.de/static/DE/zensus/gitterdaten/Zensus2022_Bevoelkerungszahl.zip
    archive-sha256: 1a8428d31eab264c8717bfdcbf40fbd66c217edb2e2a98d0e67741bd8a1a941a
  simulation:
    enabled: true                               # Stufe 10 an/aus
    project-dir: ../validation/mobitopp-rastatt # Legacy-Projekt mit runDeliverySynthesis
    timeout-minutes: 30
  actitopp:
    enabled: true                               # Stufe 5: KIT actiTopp statt Template
    project-dir: ../validation/actitoppNG       # actitoppNG-Projekt mit runSynthesis
    timeout-minutes: 20
```
