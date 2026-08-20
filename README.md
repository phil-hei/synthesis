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

Empfohlener Einstieg mit versioniertem Umgebungscheck und gemeinsamem Prozess-Lifecycle:

```bash
scripts/preflight.sh core
scripts/dev.sh
```

Der vollständige Neuaufbau der externen Laufumgebung ist in
[docs/08-reproduzierbare-laufumgebung.md](docs/08-reproduzierbare-laufumgebung.md) beschrieben.

Alternativ können beide Anwendungen weiterhin einzeln gestartet werden:

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

Für reproduzierbare A/B-Kampagnen kann zusätzlich ein kombinierter **Overpass-JSON-Snapshot**
mit POIs und bevölkerten `place`-Nodes hochgeladen werden. Er ersetzt die beiden Live-Abrufe
der OSM-Datenstufe; `manifest.json` hält seinen SHA-256 sowie den SHA-256 der daraus erzeugten
`attractivities.csv` fest. So lassen sich unterschiedliche Overpass-Antworten als verdeckte
zweite Versuchsvariable ausschließen.

Den Snapshot muss man dafür nicht selbst beschaffen: Jeder Lauf mit Live-Abruf schreibt die
tatsächlich verwendeten OSM-Daten als `osm-data.json` in seine Artefakte und hinterlegt deren
SHA-256 im Manifest. Dasselbe gilt für das **Straßennetz**: Ein live geholtes Netz wird als
`road-network.osm` gesichert und gehasht. Beide Dateien in die Uploads eines zweiten Szenarios
kopiert, frieren die Eingabe für einen A/B-Vergleich ein — `manifest.json` →
`inputProvenance` belegt dann für POI-Daten, Straßennetz und die daraus erzeugten
`attractivities.csv` denselben Hash in beiden Armen.

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
„Distanz-Abklingen korrigieren" legt den über drei Seeds bestätigten festen Term
`b_tt_* +0,06` szenariogebunden ab; zusammen mit der Nahdistanz-Kalibrierung trifft er
die vorregistrierten Sechs-Band-Gates im Großraum Rastatt. Eine streng isolierte
Freiburg-Probe bestätigte zwar die Wirkungsrichtung, verwarf `+0,06` aber als
universellen Transferwert. Selbst der höchste gemeinsame Offset vor einem
Koeffizienten-Vorzeichenwechsel verfehlte die Fernweg- und RMSE-Gates; das Flag
bleibt deshalb opt-in. Auch ein auf Pkw-Fahrer/Mitfahrer begrenzter Offset wurde
verworfen: Er erzeugt zwar mehr Fernwege, verschlechtert aber die Bandform und
treibt über die nachgelagerte Moduswahl den ÖV-Anteil hoch. Der nächste Versuch
muss deshalb mehrere Distanzbänder und den Modal-Schutz gemeinsam kontrollieren.
Der dafür vorgesehene Legacy-Hebel `b_1_2` ist derzeit blockiert: Das generierte
Modell vergleicht Kilometergrenzen mit einer Meter-Distanzmatrix, sodass `b_0_1`
und `b_1_2` wirkungslos bleiben. Ein isolierter `DISTANCE / 1000`-Spike bestätigt
die Einheitenursache und aktiviert den Hebel, verfehlt mit **+2,42 pp** im
`1–2 km`-Band aber den vorab gesetzten Elastizitäts-Gate von +3 pp. Deshalb gibt
es vorerst keinen neuen Produktvertrag; Runtime und Defaults bleiben unverändert.
Eine unabhängige Joint-Sonde trifft mit der daraus abgeleiteten Einzeldosis das
`1–2 km`-Ziel (14,12 %) und korrigiert den Modal-RMSE 0,1133→0,0176. Sie wird
trotzdem nicht befördert: `>=10 km` bleibt bei 19,68 % und der Distanzband-RMSE
bei 0,0553 statt höchstens 0,05. Offen ist nun ein eigener Langwegmechanismus,
nicht weiteres Kurzband- oder Modal-Tuning. Ein analytisch aus den gesamten
J-RAW-Log-Odds abgeleiteter Direktbonus `+1,35` verbessert anschließend
`>20 km` 3,73→7,17 % und den Band-RMSE 0,0551→0,0427, verfehlt aber das
präregistrierte Langweg-Gate von mindestens +5 pp. HOME- und Fixziele umgehen
die Zielwahl-Utility, wodurch die Gesamtmengen-Dosis den End-to-End-Effekt
überschätzt. Eine verhaltensneutrale Nachmessung klassifiziert exakt alle
42.989 Ziele und findet **44,52 % freie Zielentscheidungen** (HOME 43,55 %,
Fixziele 11,93 %); das Demand-CSV bleibt byteidentisch. Das vorab gesetzte
30–45-%-Erklärungsfenster besteht, die Quote liegt aber 7,41 pp über dem
LT-135-Wirkungsverhältnis. Vor jeder neuen Dosis muss deshalb Entscheidungsweg
und gewählte Distanz direkt verknüpft werden. Eine erste read-only Paarung über
`(tripId, legId)` ist unentscheidbar: Trotz 42.988 numerischer Treffer stimmen
die Aktivitätscodes nur in 37,18 % statt mindestens 99,9 % überein; `tripId` ist
zwischen Läufen kein stabiler Wegschlüssel. Benötigt wird deshalb eine
Same-run-Instrumentierung oder ein vorab stabilisierter Schlüssel. Ein
join-freier Marginalvergleich lokalisiert den Effekt anschließend exakt:
82,32 % der Wege sind neben freien Aktivitäten exponiert, sämtliche 1.480
zusätzlichen `>20 km`-Wege entstehen dort, und 7.600 nicht exponierte Wege
bleiben unverändert. Der exponierte Langanteil steigt aber nur
2,854→7,036 %, also +4,182 statt des vorab geforderten Minimums +5 pp.
Die anschließende Same-run-Choice-Set-Messung reproduziert beide Demand-Hashes
byteidentisch und widerlegt fehlende Fernalternativen: **Alle 19.138 freien
Entscheidungen sind eligible**, nicht die erwarteten 65–85 %. Entscheidend ist
stattdessen die heterogene MNL-Masse. CE-000 misst 2,3376 %; der exakte
je-Choice-Set-`+1,35`-Kontrafakt 6,6643 % liegt nur 0,3960 pp von der wirklichen
CE-135-Masse 7,0603 % entfernt. Bei `SHOPPING_OTHER` stehen trotz 100 %
Eligibility nur 0,0156 % Basismasse auf Fernzielen. Eine neue Dosis bleibt
gesperrt; der nächste Beleg muss diese Wahrscheinlichkeitsverteilung vorab und
verhaltensneutral klassifizieren.
Der daraufhin präregistrierte PD-000-Lauf bestätigt ein breites, stark
konzentriertes Niedrigmassenregime: 60,3511 % der freien Entscheidungen liegen
unter 1 % Fernzielmasse und tragen nur 3,7926 % der Gesamtmasse, während
4,3369 % mindestens 10 % erreichen und 42,5757 % der Masse tragen. **93,2080 %
der Varianz liegen innerhalb** statt zwischen Aktivitätstypen. Ein konstanter
Zweckbonus erklärt die Struktur daher nicht hinreichend; die nächste zulässige
Diagnose zerlegt verhaltensneutral die Ausgangszonenkomponente innerhalb der
Zwecke. Dosis und Produktänderung bleiben gesperrt.
Diese Zerlegung scheitert anschließend als Erklärung: OZ-000 deckt mit
`n>=5`-Zellen 77,0613 % der freien Entscheidungen ab, aber Ausgangszellen
erklären nur **20,0786 %** statt mindestens 70 % der Within-Zweck-Varianz;
79,9214 % verbleiben innerhalb identischer Zweck-Zonen-Zellen. Besonders bei
SHOPPING_DAILY sind es trotz größter absoluter Streuung nur 9,54 %. Der nächste
zulässige Same-run-Beleg muss daher Personen-/Modusverfügbarkeits- oder andere
situative Merkmale prüfen. Eine geografische Folgesonde, Dosis und
Produktänderung bleiben gesperrt.
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
| [docs/08-reproduzierbare-laufumgebung.md](docs/08-reproduzierbare-laufumgebung.md) | Gepinnte Laufzeiten und KIT-Commits, Bootstrap, Preflight, gemeinsamer Start und vollständige Self-hosted-CI-Abnahme |

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
