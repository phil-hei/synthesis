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

Nach einem logiTopp-Lauf normalisiert SyntheSis die simulierte Personenverkehrsnachfrage in
den nativen Vertrag `synthesis-travel-demand-v1` unter `artifacts/demand/`. Vollständige Wege,
ihre einzelnen Legs sowie dünn besetzte 15-Minuten-Zonen- und OD-Profile stehen damit
reproduzierbar für KPI-Auswertung, Kalibrierung, SUMO und weitere Zielmodelle bereit.
Modus-, Ziel- und Routenentscheidungen sind Ergebnisse des Laufs; die Zeitstempel bleiben
geplante Zeiten. Das ist bewusst ein SyntheSis-Vertrag und keine FleetPy-Dateischnittstelle.

Für reproduzierbares Routing kann jedes gespeicherte Szenario ein eigenes **OSM-XML-Straßennetz**
hochladen. Der Upload wird als OSM-Dokument mit `highway`-Wegen geprüft, muss die gewählte Region
vollständig abdecken und gewinnt vor globaler Server-Datei und Overpass; Quelle und SHA-256-Kürzel
stehen im Lauf-Manifest. Zusätzlich kann eine normalisierte **Pendler-OD-Matrix**
(`homeLat;homeLon;workLat;workLon;commuters`) oder direkt ein amtlicher BA-CSV-Export
**„Auspendler Gemeinden“** Arbeitsziele verankern. BA-Gemeindeschlüssel werden über die
BKG-VG250-Gemeindepunkte aufgelöst. Weil der BA-Export nur Auspendler enthält, steuert er bedingt
die Ziele bereits gewählter Außenbeziehungen. Optional setzt ein amtlicher Flat-File-CSV-Export
der Regionalstatistik-Tabelle **13111-08-02-5-B** über `PEN092 / ERW033` zusätzlich deren Anteil;
ohne diesen zweiten Upload bleibt die unbehandelte WORK-Gravitation maßgeblich. Externe Relationen
verschieben die acht Außenanker auf pendlergewichtete reale Arbeitsortkoordinaten. PBF-Unterstützung bleibt
ein getrennter nächster Adapter; ohne Uploads gelten unverändert die Overpass-/Luftlinien- und
Gravitations-Fallbacks.

**Zwei-Achsen-Modell** (im Studio unter „Modellierung" wählbar): _Achse A – Verhaltensquelle_
(actiTopp · **GTA/KI** – generative Tagespläne über ein lokales LLM, inkl. Was-wäre-wenn-Szenarien) ×
_Achse B – Zielmodell_ (logiTopp · MobileCity-Export · **SUMO**, Mehrfachauswahl).

Mit Zielmodell **SUMO** wird die logiTopp-Nachfrage **multimodal** mikroskopisch simuliert (OSM →
`netconvert` → `duarouter` → `sumo`) – **PKW-, Rad- und Fuß-Trips** der Personen-Nachfrage
(Fußgänger als SUMO-Personen) **plus Zustelltouren** (Lkw/Lastenrad aus logiTopp). Optional auch
**ÖV/Bus** (Phase C): ein GTFS-Fahrplan (Auto-Fetch nach Region via NVBW/MobiData-BW, Upload-Fallback)
wird per `gtfs2pt.py` zu Haltestellen und fahrplangebundenen Bussen; die ÖV-Nachfrage fährt intermodal
mit (Fußweg → Bus → Fußweg). Ergebnisse: Verkehrs-KPIs (Fahrzeuge, Ø Reisedauer/Geschwindigkeit),
**CO₂ je Fahrzeugtyp** (HBEFA-Emissionsklassen), eine **Netzlast-Karte** (Kanten nach relSpeed
eingefärbt) und optional das **Netz-Feedback**: staubelastete SUMO-Reisezeiten fließen MSA-gedämpft in die
Pkw-Matrix zurück, logiTopp und SUMO iterieren bis zum Nachfrage-Netz-Gleichgewicht. Der **Szenario-Vergleich** stellt Nachfrage-,
Distanzverteilungs-, Logistik- und Emissions-Kennzahlen mehrerer Szenarien nebeneinander (z. B. Lkw- vs.
Lastenrad-Flotte). Benötigt eine lokale SUMO-Installation (`$SUMO_HOME`); ÖV zusätzlich ein
Python-venv (pandas/rtree/pyproj) für `gtfs2pt.py` (gekapselt, sonst wird die ÖV-Stufe übersprungen).
ÖV ist aktuell auf **Bus** beschränkt – Schienen-ÖV (Tram/Rail) wurde versucht, scheitert aber an
einer SUMO-Eigenheit beim Fußgänger-Routing nahe Bahnhöfen (Befund + Lösungsweg im Abschnitt
„Phase 2 – SUMO" von [docs/06-gta-integration-konzept.md](docs/06-gta-integration-konzept.md)).

Die Verhaltensquelle **GTA** erzeugt Persona-Tagespläne über ein lokales LLM (Ollama,
OpenAI-kompatibel, Plan-Cache, seed-reproduzierbar) und fällt auf actiTopp zurück, wenn es nicht
erreichbar ist – Setup: `brew install ollama && ollama pull qwen2.5:7b-instruct`.
Details: [docs/06-gta-integration-konzept.md](docs/06-gta-integration-konzept.md).

Im Logistik-Block können bei gewähltem Zielmodell logiTopp **Kundenretouren experimentell**
als zusätzliche Abholaufträge bei `HOME` oder `WORK` aktiviert werden. Das Feature ist
standardmäßig aus; die Retourenquote ist eine explizite Szenarioannahme und kein kalibrierter
Branchenwert. Studio und Vergleich trennen Zustellungen, Zustell-Rückläufer und eingesammelte
Kundenretouren; Laufzeit-Postconditions verhindern still ignorierte oder widersprüchliche
Pickup-Ergebnisse. Der vollständige A/B-Lauf über logiTopp und SUMO ist technisch abgenommen;
das Feature bleibt dennoch bewusst kein Produktiv-Default, solange die Quote nicht empirisch
kalibriert ist. Details, Messwerte und Ausbau-Gates:
[docs/06, Abschnitt G](docs/06-gta-integration-konzept.md).

Für logiTopp stehen experimentelle, standardmäßig deaktivierte Kalibrierungen bereit.
Neu trennt „ÖV nach Distanz kalibrieren" das globale ÖV-Niveau (`asc_put`) von der
Fernweg-Schräge (`b_tt_put`) gegen MiD 2017. Sie lässt sich nach der Kalibrierung der
Distanz-Verteilung auf deren korrigierten Zielwahlparametern ausführen. Die Mechanik ist
belegt, die gemeinsame Realrouting-Kampagne scheiterte jedoch an der groben zonalen
Distanzauflösung. Kann eine Zonierung das kalibrierte `<1 km`-Band intrazonal nicht
repräsentieren, bricht das Backend nun vor dem teuren Graphaufbau mit einer konkreten
Auflösungsmeldung ab. Beide Flags bleiben opt-in; Details und Grenzen stehen in
[docs/06](docs/06-gta-integration-konzept.md).

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [docs/01-analyse.md](docs/01-analyse.md) | Analyse mobiTopp/logiTopp-Ökosystem, MobileCity-Pipeline, simudrive als Referenz |
| [docs/02-datenanforderungen.md](docs/02-datenanforderungen.md) | Mindest-Eingabedaten, was automatisch ableitbar / synthetisch generierbar ist; verifizierte Bezugsquellen (Zensus/Eurostat/gtfs.de/Pendler, Stand 2026-08-03) |
| [docs/03-architektur.md](docs/03-architektur.md) | Technische Zielarchitektur (generisch), Module, Pipeline-Stufen, Schnittstellen |
| [docs/04-roadmap.md](docs/04-roadmap.md) | MVP-Schnitt, Phasenplan, offene Punkte & Risiken |
| [docs/05-validierung.md](docs/05-validierung.md) | Phase-0-Validierung: Artefakte erfolgreich durch echten mobiTopp-Lauf, 8 Format-Befunde, Legacy-Stack-Build |
| [docs/06-gta-integration-konzept.md](docs/06-gta-integration-konzept.md) | Zwei-Achsen-Modell, GTA-Integrationskonzept, On-demand- und Modus-Distanz-Kalibrierung, SUMO-Zielmodell (inkl. Rad/Fuß/ÖV-GTFS, Rail/Tram-Befund), experimentelle Retourenlogistik, Roadmap |
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
4. **MobileCity-Integration über definierte Artefakte:** unsere Zonierung liefert District-Geometrien
   als WGS84-Shapefile und aggregierte EpigeneCSV-Tabellen für Bevölkerung, Wege, Attraktivitäten und
   privaten Pkw-Bestand; Netz-/TEC-/MOD-Defaults und Messagepack-Import bleiben mit Takomat abzustimmen.
