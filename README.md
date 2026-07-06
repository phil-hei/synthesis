# SyntheSis – Szenariomodellierung für Logistik-Verkehrsnachfrage

Webbasierte Szenariomodellierung für das Forschungsprojekt [SyntheSis](https://www.synthesis-projekt.de/):
Ein Nutzer wählt auf einer Karte (OpenStreetMap) eine Region aus, parametriert ein Logistikszenario,
und das System generiert daraus automatisch ein agentenbasiertes Verkehrsnachfragemodell auf Basis von
[mobiTopp](https://github.com/kit-ifv/mobitopp) / [logiTopp](https://github.com/kit-ifv/logitopp) (KIT IFV).
Die Ergebnisse sollen später über die bestehende MobileCity Network Pipeline in die
MobileCity-Smartphone-App eingespielt werden können.

Architektur-Vorbild: **simudrive** (Angular + Leaflet Frontend, Spring-Modulith-Backend,
dateibasierte Szenario-Artefakte, externe Simulations-Toolchain).

## Anwendung starten (MVP)

```bash
# Backend (Spring Boot, Port 8090) – erzeugt Artefakte unter backend/data/scenarios/<id>/artifacts/
cd backend && mvn spring-boot:run

# Frontend (Angular + Leaflet, Port 4300, proxied auf /api)
cd frontend && npm install && npm start
```

Dann http://localhost:4300 öffnen: Kartenausschnitt wählen → Parameter setzen → optional Depots
auf der Karte platzieren → „Speichern" → „Nachfragemodell generieren". Die Pipeline erzeugt
zonen-, bevölkerungs-, matrix- und logistikbezogene mobiTopp/logiTopp-Artefakte
(zones.csv, attractivities.csv, person/household/activity/car/fixedDestination.csv,
Visum-Matrizen `.mtx.bz2` + Matrix-YAMLs, Verteilzentren, Paketnachfrage-Parameter, manifest.json).
Swagger-UI: http://localhost:8090/swagger-ui.html

**Zwei-Achsen-Modell** (im Studio unter „Modellierung" wählbar): _Achse A – Verhaltensquelle_
(actiTopp · **GTA/KI** – generative Tagespläne über ein lokales LLM, inkl. Was-wäre-wenn-Szenarien) ×
_Achse B – Zielmodell_ (logiTopp · MobileCity-Export · **SUMO**, Mehrfachauswahl).

Mit Zielmodell **SUMO** wird die logiTopp-Nachfrage mikroskopisch simuliert (OSM → `netconvert` →
`duarouter` → `sumo`, rein nativ ohne Python/pyproj) – **PKW-Trips plus Zustelltouren**
(Lkw/Lastenrad aus logiTopp). Ergebnisse: Verkehrs-KPIs (Fahrzeuge, Ø Reisedauer/Geschwindigkeit),
**CO₂ je Fahrzeugtyp** (HBEFA-Emissionsklassen), eine **Netzlast-Karte** (Kanten nach relSpeed
eingefärbt) und optional das **Netz-Feedback**: staubelastete SUMO-Reisezeiten fließen MSA-gedämpft in die
Pkw-Matrix zurück, logiTopp und SUMO iterieren bis zum Nachfrage-Netz-Gleichgewicht. Der **Szenario-Vergleich** stellt Nachfrage-,
Logistik- und Emissions-Kennzahlen mehrerer Szenarien nebeneinander (z. B. Lkw- vs.
Lastenrad-Flotte). Benötigt eine lokale SUMO-Installation (`$SUMO_HOME`).

Die Verhaltensquelle **GTA** erzeugt Persona-Tagespläne über ein lokales LLM (Ollama,
OpenAI-kompatibel, Plan-Cache, seed-reproduzierbar) und fällt auf actiTopp zurück, wenn es nicht
erreichbar ist – Setup: `brew install ollama && ollama pull qwen2.5:7b-instruct`.
Details: [docs/06-gta-integration-konzept.md](docs/06-gta-integration-konzept.md).

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [docs/01-analyse.md](docs/01-analyse.md) | Analyse mobiTopp/logiTopp-Ökosystem, MobileCity-Pipeline, simudrive als Referenz |
| [docs/02-datenanforderungen.md](docs/02-datenanforderungen.md) | Mindest-Eingabedaten, was automatisch ableitbar / synthetisch generierbar ist |
| [docs/03-architektur.md](docs/03-architektur.md) | Technische Zielarchitektur (generisch), Module, Pipeline-Stufen, Schnittstellen |
| [docs/04-roadmap.md](docs/04-roadmap.md) | MVP-Schnitt, Phasenplan, offene Punkte & Risiken |
| [docs/05-validierung.md](docs/05-validierung.md) | Phase-0-Validierung: Artefakte erfolgreich durch echten mobiTopp-Lauf, 8 Format-Befunde, Legacy-Stack-Build |
| [docs/06-gta-integration-konzept.md](docs/06-gta-integration-konzept.md) | Zwei-Achsen-Modell, GTA-Integrationskonzept, On-demand-Kalibrierung, SUMO-Zielmodell, Roadmap (Phasen 0–3 ✅) |
| [docs/07-ansatz-zusammenfassung.md](docs/07-ansatz-zusammenfassung.md) | Kompaktes Briefing: Ansatz, 14-stufige Pipeline, KI-Pipeline (GTA), Artefakte, Datenfluss, Tech-Stack |

## Kernaussagen (TL;DR)

1. **Machbar und weitgehend automatisierbar.** Für eine frei gewählte Region in Deutschland lassen sich
   fast alle mobiTopp-Eingaben automatisch erzeugen: Zonierung, Attraktivitäten (POIs), Reisezeit-/Kosten-/
   Distanzmatrizen (Routing auf OSM + GTFS) und Strukturdaten (Zensus-Raster). Nur das **Verhaltens-Template**
   (Mobilitätserhebung à la MiD/MOP) ist nicht frei ableitbar – dafür liefern wir ein synthetisches
   Default-Template mit und machen die Quelle austauschbar.
2. **Versionsentscheidung nötig:** logiTopp hängt am *Legacy-Java-mobiTopp* (`mobitopp:0.3+`), während das
   aktuelle mobiTopp ein Kotlin-Rewrite ist. Für Logistikszenarien ist kurzfristig der Legacy-Stack
   (`mobiTopp_legacy` + `logitopp`) der gangbare Weg; mittelfristig Abstimmung mit dem KIT IFV.
3. **Generik über drei Achsen:** austauschbare Daten-Provider je Datenebene (echt → öffentlich → synthetisch),
   ein deklaratives Szenario-Schema, und Export-Adapter (MobileCity, CSV/GeoJSON, später MATSim).
4. **MobileCity-Integration über definierte Artefakte:** unsere Zonierung liefert exakt die District-Inputs
   (GPKG + Excel), die die bestehenden Network-Pipelines (Car/Bike/Transit → OLC Mapper → Config Tool)
   erwarten; die Nachfrageergebnisse (Wege, Pakettouren) werden als zusätzlicher Export-Adapter angebunden.
