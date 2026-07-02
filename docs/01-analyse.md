# 01 – Analyse: mobiTopp/logiTopp, MobileCity-Pipeline, simudrive

Stand: 2026-06-10. Quellen: GitHub-Repos der KIT-IFV-Organisation, „Network Pipeline.zip"
(MobileCity), simudrive-Quellcode.

## 1. Das mobiTopp-Ökosystem (KIT IFV) – aktueller Stand

Das IFV hat mobiTopp in den letzten Jahren als **Kotlin-Rewrite** neu aufgebaut. Relevante Repos:

| Repo | Rolle | Stand |
|---|---|---|
| [mobitopp](https://github.com/kit-ifv/mobitopp) | Kern (Kotlin, Gradle, MIT) – Wochensimulation, Ziel-/Moduswahl als Discrete-Choice | aktiv (`main`) |
| [mobiTopp-tutorial](https://github.com/kit-ifv/mobiTopp-tutorial) | **Vollständiges Beispielmodell Rastatt** inkl. Daten, Long-Term + Short-Term | aktiv, Juni 2026 |
| [populationsynthesis](https://github.com/kit-ifv/populationsynthesis) | Kotlin-Bibliothek Bevölkerungssynthese (IPU/Reweighting) | aktiv |
| [actitoppNG](https://github.com/kit-ifv/actitoppNG) | Generierung von Wochen-Aktivitätenplänen (Kotlin-Rework von actiTopp) | aktiv |
| [OSM2Attractiveness](https://github.com/kit-ifv/OSM2Attractiveness) | **OSM-PBF + Zonenlayer → zonale Attraktivitäts-Indikatoren (CSV/GPKG)**; Python + R | aktiv |
| [mobiToppIO](https://github.com/kit-ifv/mobiToppIO) | I/O-Parser (u. a. Visum-Matrixformate) | aktiv |
| [DCML](https://github.com/kit-ifv/DCML) | Kotlin-DSL für Discrete-Choice-Modelle | aktiv |
| [mobiTopp_legacy](https://github.com/kit-ifv/mobiTopp_legacy) | Fork der alten Java-Version (v0.x) | gepflegter Fork |
| [logitopp](https://github.com/kit-ifv/logitopp) | Paketlogistik-Erweiterung; **hängt an `mobitopp:0.3+` (Legacy-Java!)**, GPL-3.0 | aktiv (Mai 2026) |

### 1.1 Eingabedaten des neuen mobiTopp (Short-Term-Simulation)

Aus `ShortTermConfig`/`CoreCSVConfig`/`MatrixConfig` (Kotlin-Quellcode) und dem Tutorial-Datenlayout:

**CSV-Dateien (Pflicht):**

| Datei | Inhalt | Entsteht aus |
|---|---|---|
| `zones.csv` | Verkehrszellen (ID, Lage, Typ) | Zonierung |
| `attractivities.csv` | Zonale Attraktivität je Aktivitätstyp (Arbeit, Einkauf, Freizeit …) | OSM2Attractiveness |
| `person.csv` | Synthetische Personen (Alter, Erwerbsstatus, Mobilitätswerkzeuge) | Long-Term-Modul |
| `household.csv` | Synthetische Haushalte | Long-Term-Modul |
| `activity.csv` | Wochen-Aktivitätenpläne je Person | Long-Term / actiTopp |
| `car.csv` | Private Pkw je Haushalt | Long-Term-Modul |
| `fixedDestination.csv` | Feste Ziele (Wohn-/Arbeits-/Schulzone je Person) | Long-Term-Modul |

**Matrizen** (Visum-Format `.mtx.bz2`, gebündelt über zwei YAML-Konfigurationsdateien
`time-matrix-configuration.yaml` / `cost-matrix-configuration.yaml`, zeitscheiben- und modusabhängig):

- Reisezeiten je Modus: `CAR_TTC`, `BIKE_TTC`, `PED_TTC`, `PUT_TTC` (+ ÖV-Zugang/-Abgang `PUT_ACT`/`PUT_EGT`)
- Kosten je Modus: `CAR_COST`, `PUT_COST`, ggf. Sharing/Taxi
- Distanzen: `CAR_DIS` (Pflicht), optional je Modus

**Verhaltensmodelle:** Ziel- und Moduswahlmodelle werden als Kotlin-Code/DCML mit Parameterdateien
definiert (im Tutorial enthalten – als Default übernehmbar).

### 1.2 Eingabedaten des Long-Term-Moduls (Bevölkerungssynthese)

Aus dem Tutorial (`MainLongTerm.kt`, `src/main/resources/`):

| Datei | Inhalt | Quelle |
|---|---|---|
| `SurveyPopulation.csv` | **Mobilitätserhebung als Verhaltens-Template** (Haushalte/Personen/Wege, à la MiD/MOP) | nicht frei – kritischste Eingabe |
| `SynthesisZoneTargets.csv` | Strukturdaten-Randsummen je Zone (Einwohner nach Alter/Geschlecht, Haushaltsgrößen, Erwerbstätige) | Zensus/Kommunalstatistik oder synthetisch |
| `commuters-*.csv` | Pendlerverflechtungen | BA-Pendlerstatistik oder Gravitationsmodell |
| `zone-to-community.csv` | Zuordnung Zone → Gemeinde | Zonierung |
| `economical-status-*.csv` | OECD-Einkommensklassifikation | statisch mitlieferbar |

Ablauf: Long-Term (Synthese) schreibt `person/household/activity/car/fixedDestination.csv` →
Short-Term konsumiert sie. Beide Schritte sind getrennte ausführbare Programme (`run/long_term.sh`,
`run/short_term.sh`) – ideal für eine Pipeline-Orchestrierung.

### 1.3 logiTopp (Paketlogistik)

logiTopp erweitert mobiTopp um Paketbestellungen und Last-Mile-Zustellung:

- **Paketnachfrage je Person** über Wahlmodelle (Anzahl ~ Normalverteilung, Zieltyp HOME/WORK/PACKSTATION,
  Zustelltag, Zustelldienst, Verteilzentrum) – Parameter in `.gen`-Dateien + Text-Parameterdateien.
- **Verteilzentren** explizit modelliert: Standort, Mitarbeiterzahl (aus der synthetischen Bevölkerung
  rekrutiert), Anteil am Paketaufkommen, Delivery Policy, Tourenplanung (TSP-Approximation je Zone).
- **Effizienzprofile** für Zusteller (Lade-/Zustellzeiten, modelliert z. B. Lastenrad vs. Transporter).
- Output: CSVs zu Pakettouren, Zustellereignissen, Paketzuständen.

**Zentraler Befund:** `build.gradle` von logiTopp referenziert `edu.kit.ifv.mobitopp:mobitopp:0.3+`
→ logiTopp läuft heute nur mit dem **Legacy-Java-mobiTopp**, nicht mit dem Kotlin-Rewrite.
Beide Repos werden 2026 aktiv gepflegt; eine Migration von logiTopp auf den neuen Kern ist
wahrscheinlich, aber nicht verfügbar. Lizenz-Hinweis: logiTopp ist GPL-3.0 (mobiTopp neu: MIT) –
unkritisch, solange wir logiTopp als externes Tool aufrufen statt es einzubetten.

**Konsequenz für SyntheSis** (Entscheidung in [04-roadmap.md](04-roadmap.md)):
Logistikszenarien zunächst auf dem Legacy-Stack (`mobiTopp_legacy` + `logitopp`) rechnen;
die Daten-Pipeline aber am neuen CSV-/Matrix-Layout ausrichten (die Formate sind eng verwandt,
ein Adapter Legacy⇄Neu ist Teil der Pipeline). Parallel Kontakt zum KIT IFV zur logiTopp-Roadmap.

## 2. MobileCity Network Pipeline (Zielsystem für den Export)

Aus der ZIP (`2026.03.16_MCNetworkDataPipeline_Overview.md` + Readmes der Teiltools):

```
Bike/Transit/Car-Pipeline → xlsx-Netzdateien → OLC Mapper → CSV → MC Config Tool → Messagepack → MobileCity App
```

| Tool | Input | Bemerkung für uns |
|---|---|---|
| Pipeline **Car** (Python: osmnx, neatnet) | **District-Boundaries (GPKG/SHP)** + **`*_districts.xlsx`** (`statID`, `districtID`, `districtType`, Detail-Level) | unsere Zonierung kann genau diese zwei Artefakte erzeugen |
| Pipeline **Bike** (Python + GraphHopper/Google API) | District-Shapefiles, OSM-Gebäude | Referenzknoten via Clustering, Delaunay, externes Routing |
| Pipeline **Transit** (Python) | District-Shapefiles + **GTFS** | GTFS bekommen wir ohnehin für die ÖV-Matrizen → Durchreichen |
| **OLC Mapper** | Knoten/Link-Tabellen der drei Pipelines | umbenennt IDs auf Open Location Codes, Konsistenzprüfung |
| **Config Tool** (C#) | CSVs des OLC Mappers + Study-Area-Konfiguration | erzeugt Messagepack für die App |

Die Pipelines sind je Stadt per Config (`src/config.py`, `config/config.json`) parametriert und
laufen als eigenständige Skripte → sie lassen sich als **containerisierte Pipeline-Stufen** hinter
einem Export-Adapter kapseln, ohne sie umzuschreiben. Was heute fehlt, ist die Gegenrichtung
„Nachfrage in die App": In welcher Form MobileCity Nachfrage/Touren konsumiert, ist aus der ZIP
nicht ersichtlich (sie behandelt nur Netze) → mit Takomat/Projektpartnern zu klären.

## 3. simudrive als Architektur-Referenz

simudrive zeigt das Muster, das wir übernehmen:

- **Frontend:** Angular 18 + Leaflet/OSM; Szenario-CRUD, Gebiets-Auswahl (Bounding-Box wird in die
  Area zurückgeschrieben), Reactive Forms für Parametergruppen, metadatengetriebene Auswahllisten
  (`/api/scenario-metadata`).
- **Backend:** Spring Boot **Modulith** mit Modulen `scenario`, `maps` (Overpass-Download,
  OSM-Filterung, Artefakt-Metadaten), `generation`, `simulation`, `storage` (dateibasierte Ablage
  `data/scenarios/<id>/areas/<id>/…`). Modulgrenzen per Spring-Modulith-Test verifiziert.
- **Externe Toolchain** (MOSAIC/SUMO) wird nicht eingebettet, sondern über konfigurierte Pfade
  aufgerufen – exakt das richtige Muster für mobiTopp/logiTopp und die Python-Pipelines.
- Pragmatische Limits (Bounding-Box ≤ 0,25°) gegen zu große Overpass-Abfragen – übernehmen wir
  als konfigurierbare Regionsgrößen-/Zonenzahl-Limits (Matrizen wachsen mit O(n²)).
