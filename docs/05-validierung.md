# 05 – Phase-0-Validierung: Eigene Artefakte gegen echtes mobiTopp/logiTopp

Datum: 2026-06-10. Arbeitsverzeichnis: `validation/` (Klone von mobiTopp-tutorial, mobitopp,
kotlin-units, DCML, actitoppNG, logitopp; lokales JDK 11 unter `validation/tools/`).

## Capstone: Gesamtlauf mit allen Features (2026-06-21)

Ein Lauf mit **allen Features gleichzeitig** (OSM-POIs + KIT-Populations-Sample + KIT-actiTopp +
GraphHopper-Routing + Außenbeziehungen + 2 Depots + logiTopp-Wochensimulation + MobileCity-Export)
lief vollständig durch — **alle 11 Pipeline-Stufen DONE**, kompletter Artefaktsatz (legacy/,
matrix/, mobilecity/ [5 Tabellen + summary], simulation/ [results + kpis/depot-stats/geojson]):
1.260 OSM-POIs, 143k actiTopp-Aktivitäten, 135.226 Wege, 6.341 Pakete, 2.768 Touren (~23 s Sim).
Jede externe Abhängigkeit (Overpass-POIs/-Places/-Straßennetz) hat einen sauber greifenden
Fallback — beim Lauf lieferte Overpass für die schwere Straßennetz-Abfrage 504/429 (Eigen-
Rate-Limiting nach vielen Session-Abfragen) → Luftlinien-Matrizen; Routing selbst ist separat
verifiziert (56×56 in 7 s, Ø-Weglänge 3,05→3,65 km). **Empfehlung für den Produktivbetrieb**:
self-hosted Overpass oder ein gebündelter GraphHopper-PBF-Extrakt statt des öffentlichen Overpass,
da dessen schwere Netz-Abfrage unzuverlässig/rate-limitiert ist.

## Ergebnis (TL;DR)

**Die Pipeline-Artefakte laufen unverändert durch einen echten mobiTopp-Short-Term-Lauf.**
Nach Rückspielen von 8 Format-Befunden in die Generatoren wurde der Regressionstest bestanden:
frisch generiertes Szenario (Rastatt-Region, 56 Zonen, 5.999 Personen) → ins unveränderte
Tutorial eingespeist → `runShortTerm` → **BUILD SUCCESSFUL, 13.832 simulierte Wege**
(`demandsimulation.csv` mit plausiblen Distanzen/Kosten, HTML-Report, Plots).

## Build-Kette (neuer Kotlin-Stack)

Die KIT-Artefakte liegen **nicht auf Maven Central** → lokal bauen und mit
`./gradlew publishToMavenLocal -PdoPublish=true -PbuildVersion=<v> -PlocalUrl=file:///tmp/dummy-repo -PlocalRepoUser=x -PlocalRepoPassword=x`
publizieren (Dummy-Werte nötig, weil das Build-Skript die internen Repo-Parameter eager prüft):

| Reihenfolge | Repo | Artefakt | Version |
|---|---|---|---|
| 1 | kotlin-units | kotlin-units | 1.0.0 |
| 2 | DCML | discrete-choice | 1.1.0 |
| 3 | actitoppNG | actitoppNG | 1.0.0 |
| 4 | mobitopp | mobitopp-reengineering, annotations, processor | 1.0.0 |
| 5 | mobiTopp-tutorial | – (Anwendung) | – |

Toolchain: JDK 25 (jvmToolchain(25)), Gradle 9.5 (Wrapper). Tutorial-Läufe:
`sh gradlew runLongTerm` (47.414 Agenten, ~21 s) und `sh gradlew runShortTerm` (~43 s).

## Die 8 Befunde (alle in die Pipeline zurückgespielt)

| # | Befund | Fix in unserer Pipeline |
|---|---|---|
| 1 | `chargingInfluencesDestinationChoice` ist Enum (`NEVER`/…), kein Boolean; `mobilityProviderCustomership` erwartet `{}` | PopulationGenerator schreibt `NEVER;{}` |
| 2 | `activity.csv`: `activityType` muss **numerisch** sein (LegacyActivityType-Codes: WORK=1, EDUCATION_PRIMARY/SEC/TERT=31/32/33, SHOPPING_DAILY=41, SHOPPING_OTHER=42, LEISURE_IN/OUT=51/52, SERVICE=6, HOME=7, PRIVATE_VISIT=12, DELIVER_PARCEL=23, PICK_UP_PARCEL=71) | ActivityGenerator mappt auf Codes; EDUCATION altersabhängig 31/32/33 |
| 3 | Das Aktivitätsset ist **projektspezifisch** (Enum im Projektcode, z. B. `TutorialActivity`); Tutorial kennt weder 71 noch generisches EDUCATION(3) | Keine geplanten PICK_UP_PARCEL-Aktivitäten mehr (logiTopp erzeugt Abholungen selbst per Rescheduling) |
| 4 | `fixedDestination.csv` nutzt **Namen**; PICK_UP_PARCEL bricht Projekte ohne logiTopp | Zwei Dateien: `fixedDestination.csv` (kompatibel) + `fixedDestination-logitopp.csv` (inkl. PICK_UP_PARCEL) |
| 5 | `car.csv` wird **positional** geparst (12 Felder, Segment=Idx 7, Sitze=Idx 8); Header `ownerId;mainUserId;personalUserId;carType;car attributes`; `carType` lowercase (`conventional`/`bev`) | Legacy-12-Feld-Layout inkl. `CarId(value=…)`/Positionsfeld |
| 6 | Distanzmatrizen werden aus `data/matrix/other/{CAR,BIKE,AIR}_DIS.mtx.bz2` geladen | MatrixGenerator schreibt zusätzlich das `other/`-Layout |
| 7 | Beide Matrix-YAMLs müssen **alle Modi** des Projekts abdecken (auch BIKESHARING); kostenlose Modi via `constant_matrix: "0.0"` | Vollständige Mode-Abdeckung + BS_COST.mtx.bz2 |
| 8 | Distanzmatrix-Einheit ist **Meter** (nicht km) | Distanzen ×1000 |

**Positiv validiert ohne Änderung:** `zones.csv` (inkl. `centroidLocation`-Format `(lon,lat: edge, share)`),
`attractivities.csv`, `household.csv` (inkl. `homeLocation`), `person.csv`-Spalten, Visum-`$V;D3`-Matrizen
(unser Writer wird vom mobiTopp-Parser gelesen), Matrix-YAML-Struktur, `fractionOfPopulation`-Filter.

## Legacy-Stack (logiTopp)

- **`mobitopp:0.3.580` liegt auf Maven Central** → Legacy-Kern muss nicht gebaut werden.
- logitopp-Original-Build (Gradle 6.3/Java 12/jcenter) läuft auf aktuellen Toolchains nicht;
  unser Klon enthält ein **modernisiertes `build.gradle`** (Gradle 9, JDK-25-Toolchain,
  Lombok 1.18.46, `implementation`-Syntax, Publishing entschlackt) → baut und ist als
  `edu.kit.ifv.mobitopp:logitopp:0.0-SNAPSHOT` in mavenLocal publiziert.
- Referenz-Long-Term-Output bestätigt: Das echte mobiTopp schreibt `fixeddestination.csv`
  (lowercase) und akzeptiert beide Varianten über die Default-Pfad-Auflösung.

## logiTopp-Simulationslauf (2026-06-11) — erfolgreich

Vollständige Wochensimulation mit Paketlogistik auf dem Legacy-Stack
(`validation/mobitopp-rastatt`, Task `runDelivery`): **10.966 Paketbestellungen für
10.481 Agenten (10 % der Rastatt-Population), 3.279 geplante Zustelltouren, 3.357
Zustellereignisse**, Laufzeit 75 s. Ergebnisdateien unter
`output/rastatt/results/simulation/`: `parcel-orders-private.csv`,
`parcel-states.csv` (Zustellstatus inkl. Zustellversuche), `planned-tours.csv`
(Touren mit Distanz, Stopps, Volumen, Fahrzeug) + die regulären mobiTopp-Wege.

### Setup-Weg

1. **mobitopp-rastatt** geklont; `build.gradle` modernisiert (Gradle 9, JDK-25-Toolchain,
   `mobitopp:0.3.580` von Maven Central, Lombok 1.18.46, slf4j ergänzt).
2. Das alte **Codegen-Gradle-Plugin umgangen**: Das gebündelte Fat-Jar
   `libs/edu.kit.ifv.codegen-0.2.2.90.jar` hat einen direkt aufrufbaren
   `GeneratorRunner.main(outputDir, gen-Dateien…)` (braucht `--add-opens
   java.base/java.lang=ALL-UNNAMED` wegen altem Guice) → Wahlmodell-Klassen generiert.
3. Legacy-Long-Term gelaufen (Population), dann eigener Main
   `ShortTermModuleDelivery` + `SimulatorBuilder.buildDelivery()` mit
   `DemandSimulatorDelivery`.

### Befunde für die logiTopp-Integration (für unsere Pipeline/Phase 3)

| # | Befund |
|---|---|
| L1 | PACKSTATION-Pakete setzen `PICK_UP_PARCEL`-fixedDestinations in der Population voraus — fehlen sie, crasht der Parcel-Build. Bestätigt unser `fixedDestination-logitopp.csv`-Design; alternativ Zielanteile ohne PACKSTATION konfigurieren. |
| L2 | Das Paketnachfragemodell braucht explizit einen `allAsDelivery()`/`allAsPickup()`-Builder-Step (isPickUp), sonst NPE beim Parcel-Build. |
| L3 | Jedes `DistributionCenter` braucht ein `DepotOperations`-Objekt (Tourplanung z. B. `TspBasedDeliveryTourStrategy` + `AgentDeliveryClustering` + `DummyDurationModel`, `ParcelPolicyProvider`-Defaults, `DispatchTimeStrategy` mit leerem `TimeTable`); registriert sich selbst am Depot. |
| L4 | Zustellfahrzeuge routen über `StandardMode.TRUCK` → im `DynamicTypeMapping` auf die Pkw-Reisezeitmatrix mappen (`mapping.add(StandardMode.TRUCK, TravelTimeMatrixType.car)`). |
| L5 | Speicher: 100 % Population (104k Agenten, 109k Pakete) sprengt 8–12 GB Heap in der Tourplanung; 10 %-Fraktion läuft problemlos. Für unsere Pipeline: `samplingFraction` klein halten oder Heap dimensionieren. |
| L6 | Depots/CEPSP lassen sich programmatisch ODER per `DistributionCenterParser` (CSV) anlegen — unser `distribution-centers.csv` sollte auf dessen Spaltenschema ausgerichtet werden (Folgeaufgabe). |

## Legacy-Adapter: eigene Artefakte → logiTopp (2026-06-11) — erfolgreich

**Der komplette Durchstich steht: Kartenauswahl → SyntheSis-Pipeline → logiTopp-Wochensimulation.**
Die neue Pipeline-Stufe „Legacy-Export" (`LegacyExportGenerator`) erzeugt unter
`artifacts/legacy/` die vollständige Eingabestruktur des Legacy-Stacks (zone-repository,
zoneproperties, demand-data, Matrizen + matrix-configuration.yaml). Lauf über
`runDeliverySynthesis` im rastatt-Projekt (Config `config/synthesis/short-term-module.yaml`
zeigt auf den Export): **6.264 Pakete für 5.999 Agenten, 2.240 Zustelltouren, 2.279
Zustellereignisse, 70.188 Wege, 21 s** — mit der synthetischen Population aus unserer Pipeline.

### Adapter-Befunde (Legacy-Eingabeformate)

| # | Befund |
|---|---|
| A1 | **Kein Visum-Netz nötig**: Liegt ein vollständiges `zone-repository/` vor (`zones.csv` + Begleitdateien), wird `visumFile` nie geladen (lazy Supplier in `LocalFiles.loadZonesFromMobiTopp`). Begleitdateien (chargingdata, stations, *-cars, *-organizations) genügen header-only. |
| A2 | `homeZone` (household.csv) und `zoneId` (fixedDestination.csv, opportunity.csv) referenzieren Zonen über die **OID/matrixColumn**, nicht die externe ID — externe IDs werden still verworfen (0 Haushalte!). |
| A3 | car.csv „car attributes" = positional `carId(int); zoneOid; location; segment; capacity; mileage; fuelLevel; maxRange`; `bev` bräuchte ein zusätzliches batteryLevel-Feld → Export schreibt alle als `conventional`. |
| A4 | fixedDestination.csv braucht `householdNumber/householdYear ≥ 0` (Validierung im HouseholdId-Konstruktor). |
| A5 | activity.csv braucht die **Tour-Struktur**: `tournr` inkrementiert je Blockwechsel (Heimphase ↔ Tour), genau eine `isMainActivity=true` je Tour, HOME mit `observedTripDuration=0`, und Heimphasen müssen **eine** Zeile sein (aufeinanderfolgende HOME-Zeilen, z. B. über Mitternacht, mergen — sonst crasht `TourPattern`). |
| A6 | zoneproperties.csv (`zoneId;isDestination;…;cs_density`) und Attractivities.csv (identisch zu unserem Format) gehören in den `zoneproperties`-Block der Config. |
| A7 | opportunity.csv: Zielwahl-Gelegenheiten je Zone × Aktivitätstyp (Name) mit Attraktivität — aus unseren zonalen Attraktivitäten generiert. |
| A8 | Legacy-matrix-configuration.yaml (eigenes Schema `costMatrices/travelTimeMatrices → at/weekdays/between`) wird als Resource mitgeliefert; alle referenzierten `.mtx` entstehen durch Entpacken/Mapping unserer `.mtx.bz2` (BS/CSFF/CSSB/TAXI = Kopien, dummy/VM_BLOCK = Nullmatrizen). |

## Szenario-Depots im Lauf (2026-06-12) — erfolgreich

Die Depots kommen jetzt **aus dem Szenario** statt programmatisch aus Zone 0: Der
Legacy-Export schreibt zusätzlich `legacy/logistics/distribution-centers.csv` im Schema des
logiTopp-`DistributionCenterParser` plus `cepsp-shares.csv` (Marktanteile), und
`buildDelivery()` parst diese Dateien über die experimental-Parameter `distributionCenters`
und `cepspShares` (Fallback ohne Parameter: bisheriges Einzeldepot). Testlauf mit zwei
Depots (8 Trucks „Depot Nord"/DHL + 4 Lastenräder „Mikrodepot Lastenrad"/Hermes):
**EXIT=0, 6.264 Pakete — Aufteilung 4.330/1.934 ≈ 69/31 % exakt nach CEPSP-Shares,
2.641 geplante Touren und 3.013 Zustellungen aus beiden Depots, Flotte korrekt (TRUCK/BIKE).**

### Befunde Depot-Anbindung

| # | Befund |
|---|---|
| A9 | `DistributionCenterParser` referenziert die Zone — anders als die demand-data! — über die **externe ID** (`getByExternalId`); Spalten `id;name;cepsp;vehicles;attempts;loc_x,loc_y (lon/lat);zone;vehicle_type;service_area;volume`, Trenner `;`. |
| A10 | `vehicle_type` als Int-Code (`VehicleType`: OTHER=0, BIKE=1, TRUCK=2, TRAM=3) → Lastenrad-Depots = 1; BIKE-Mode braucht das `StandardMode.BIKE→bike`-Matrix-Mapping (war schon gesetzt, vgl. L4). |
| A11 | `service_area` als Int-Code: `<0` keine, `0` alle Zonen, `>0` Distanzradius — alternativ kommaseparierte Zonenliste. |
| A12 | Der Parser legt je `cepsp`-Wert die `CEPServiceProvider` selbst an; `DepotOperations` muss weiterhin **pro Depot** explizit verdrahtet werden (registriert sich im Konstruktor). Marktanteile (`shareBasedCepspSelection`) sind nicht Teil der CSV → eigene `cepsp-shares.csv`, renormalisiert auf die Dienste, die tatsächlich ein Depot besitzen. |

## Stufe 10: Lauf-Orchestrierung + Visualisierung (2026-06-12) — erfolgreich

Die Pipeline endet nicht mehr beim Export: Stufe 10 „Simulation (logiTopp)" (`SimulationRunner`)
schreibt eine per-Szenario-Run-Config (`simulation/run-config.yaml`, absolute Pfade auf den
Legacy-Export, `fractionOfPopulation`/`seed` aus dem Szenario), ruft
`sh gradlew runDeliverySynthesis -PconfigFile=…` im Legacy-Projekt auf (Pfad konfigurierbar:
`synthesis.simulation.project-dir`, Default `../validation/mobitopp-rastatt`; ohne Projekt wird
die Stufe SKIPPED) und verdichtet die Ergebnisse (`SimulationResultIngestor`) zu
`simulation/kpis.json`, `depot-stats.json`, `parcel-density.geojson` (Zonenpolygone mit
Paketzahl) und `tours.geojson` (geplante Touren als LineStrings). Das Frontend zeigt nach der
Generierung ein Simulations-Panel (KPIs, Modal Split vs. MiD 2017, Depot-Statistiken) und zwei
zuschaltbare Kartenlayer (Paketdichte-Choropleth, Tourlinien). Verifiziert im Browser:
kompletter Durchlauf inkl. 22-s-Simulation aus einem Klick auf „Nachfragemodell generieren".

### Plausibilität vs. MiD 2017 (erster Befund, Rastatt-Region, 6.000 Einwohner synthetisch)

| Kennwert | Simulation | MiD 2017 | Einordnung |
|---|---|---|---|
| Wege/Person/Tag | 1,8 | 3,1 | zu wenig — synthetische Wochenpläne sind konservativ |
| Ø Weglänge | 2,0 km | 12,5 km | erwartbar: Luftlinien-Matrizen, kleines Gebiet (≈ 6×5 km), keine Fernwege |
| Fuß / Rad / MIV / Mitf. / ÖV | 51/6/22/10/11 % | 22/11/43/14/10 % | Fuß überschätzt, MIV unterschätzt — Folge der kurzen Distanzen + Rastatt-kalibrierter Wahlmodelle |

Konsequenz (Roadmap): echtes Routing (GraphHopper/R5) und Kalibrierung der Wahlmodell-
Parameter sind die wirksamsten Hebel; die KPI-Anzeige macht den Fortschritt sichtbar.

## Echtes Routing statt Luftlinie (2026-06-12) — erfolgreich

Die Reisezeit-/Distanzmatrizen entstehen jetzt über echtes Routing auf dem OSM-Straßennetz:
`RoutingEngine` (eingebettetes GraphHopper 8.0) baut aus dem per Overpass geladenen Netz
(`fetchRoadNetworkOsm`, `[out:xml]` highway-Ways + Geometrie) einen Graph mit CH-Profilen für
Auto/Rad/Fuß und routet alle Zone-Centroid-Paare. `MatrixGenerator` nimmt einen optionalen
`Impedance`-Provider; nicht routbare Relationen und der deaktivierte Fall fallen je Relation auf
Luftlinie × Umwegfaktor zurück. Steuerung: Szenario-Flag `useRealRouting` (Default an,
Frontend-Checkbox). 56×56-Routing für Rastatt in ~7 s.

**Effekt auf die Plausibilität** (gleiches Szenario, sonst identisch):

| Kennwert | Luftlinie | Routing | MiD 2017 |
|---|---|---|---|
| Ø Weglänge | 2,0 km | **2,9 km** | 12,5 km |
| Fuß | 51 % | **35 %** | 22 % |
| MIV (Fahrer) | 22 % | **29 %** | 43 % |
| MIV (Mitfahrer) | 10 % | **18 %** | 14 % |
| ÖV | 11 % | 12 % | 10 % |

Modal Split verschiebt sich klar Richtung MiD (Fuß runter, MIV rauf). Die absolute Weglänge
bleibt niedrig — das Untersuchungsgebiet ist nur ≈ 6×5 km, längere Wege sind geometrisch
unmöglich; größere Region oder Außenbeziehungen wären der nächste Hebel.

## Angereicherte Aktivitätenpläne (2026-06-12) — erfolgreich

`ActivityGenerator` ist tour-basiert umgebaut: ein Tag ist eine Liste von Touren, eine Tour eine
Kette von Außer-Haus-Aktivitäten (HOME → a1 → a2 → HOME). Erwerbstätige/Schüler haben eine
Haupttour (Arbeit/Bildung, oft mit verkettetem Besorgungsstopp auf dem Heimweg) plus meist eine
separate Abend-Freizeit-Tour; Nicht-Erwerbstätige Besorgungen/Freizeit an den meisten Tagen
(wenige reine Heimtage), Wochenende eigene Verteilung. `scheduleDay` ordnet die Touren mit
Jitter zeitlich an und verwirft Aktivitäten, die nicht mehr vor Mitternacht passen (der Tag endet
immer zu Hause — Pflicht für den Tour-Pattern-Parser, vgl. A5).

**Effekt auf Wege/Person/Tag** (mit Routing, sonst identisch):

| | vorher (1 Außer-Haus-Akt.) | tour-basiert | getunt | MiD 2017 |
|---|---|---|---|---|
| Wege/Person/Tag | 1,8 | 2,9 | **3,18** | 3,1 |
| Wege gesamt | 70 k | 112 k | ~124 k | — |

Wahrscheinlichkeiten (Verkettung/Abend-Tour/Heimtage) sind so gewählt, dass der bundesweite
MiD-Mittelwert getroffen wird; sie sitzen gebündelt in `ActivityGenerator` und sind leicht
nachjustierbar. Ø Weglänge bleibt ~3 km (Gebietsgröße, s. o.).

## Außenbeziehungen / externe Zielzonen (2026-06-12) — teilweise wirksam

`ExternalZones` hängt nach der Attraktivitätsstufe einen Ring von 8 externen Zielzonen
(Himmelsrichtungen) um das Gebiet — ohne Einwohner, aber mit Attraktivität (Anteil der internen
Gesamtattraktivität je Kategorie). Sie fließen unverändert durch zones.csv, attractivities.csv,
Matrizen und Opportunities; `Zone.external=true` erzwingt in `MatrixGenerator` Luftlinie statt
Routing (GraphHopper würde sonst auf den nächsten Randknoten snappen → zu kurz). Steuerung:
Szenario-Flag `externalTrips` (Default an, Frontend-Checkbox).

**Befund**: Bei 25 km wählt das **Rastatt-Zielwahlmodell die externen Zonen nie** (0 Wege von
124 k — die Distanz-Disnutzität schließt sie faktisch aus). Erst näher + attraktiver greift der
Mechanismus: bei **12 km, Faktor 4,0** gehen ~3,3 % der Wege nach außen (je Zone ~500), und die
Ø Weglänge steigt von **3,05 → 3,65 km**, ÖV 12 → 15 % (Fernwege per Auto/ÖV, nicht zu Fuß);
Wege/Person·Tag bleibt 3,18. Die volle MiD-Distanz (12,5 km) ist so nicht erreichbar — das
Rastatt-Modell deckelt Fernwege; der eigentliche Hebel bleibt **Wahlmodell-Kalibrierung** (Distanz-
Sensitivität) bzw. ein realistisch großes Untersuchungsgebiet.

## Stichprobengröße bei vielen Zonen (2026-07-28) — korrigiert

Ein präregistrierter Großraum-Spike deckte einen Skalierungsfehler in
`PopulationGenerator` auf: Bei derselben Vorgabe 40.000 Personen × 5 % entstanden
im kleinen 9-Zonen-Gebiet 2.015 Personen, im 576-Zonen-Gebiet aber 2.516. Die
Ursache war zweifach: Die erwartete Stichprobe wurde je Zone einzeln gerundet und
der letzte gezogene KIT-Haushalt durfte die verbleibende Zonensumme überschreiten.
Der Fehler wuchs damit systematisch mit der Zonenzahl.

**Fix**: Die ganzzahligen Zonensummen werden jetzt per größtem Rest aus der
**globalen** Soll-Stichprobe abgeleitet; der letzte KIT-Haushalt wird auf die
verbleibende Personenzahl begrenzt. `PopulationGeneratorTest` reproduziert den
576-Zonen-Fall: 5 % von 40.000 ergeben exakt 2.000 Personen. Der wiederholte
End-to-End-Spike bestätigte 2.000 Personen sowohl bei 9 als auch bei 576 Zonen.
Das Manifest weist zugleich die aktive Strukturquelle korrekt als
`kit-population-sample` statt pauschal `synthetic` aus.

## Untersuchungsraum und Langdistanz (2026-07-28) — Mechanik bestätigt

Nach dem Stichproben-Fix wurde der frühere Großraum-Befund mit dem inzwischen
zellgrößen-skalierten Intrazonal-Boden wiederholt. Zwei Offline-Läufe unterschieden
sich nur in der Region: ca. 7 × 7 km (9 Zonen) gegenüber ca. 55 × 55 km
(576 Zonen), jeweils 2,3-km-Raster, 2.000 Personen, Template-Verhalten,
Luftlinienmatrizen, keine externen Ringzonen.

**Befund**: Der größere interne Zielraum erzeugt erstmals eine belastbare
Langdistanzverteilung: Anteil `>=10 km` **0,0 → 11,6 %**, `>=20 km`
**0,0 → 5,3 %**, Ø Weglänge **2,46 → 5,51 km**, Median
**1,62 → 3,09 km** und Sechs-Band-RMSE zu MiD **0,2332 → 0,1693**.
Wege/Person·Tag bleiben plausibel (**3,09 → 3,06**). Damit ist die Gebietsgröße
als Hebel bestätigt, reicht allein aber nicht für MiD (12,5 km Ø, 13 % ab
20 km, RMSE-Gate höchstens 0,05).

**Konsequenz**: Kein weiterer reiner Ringversuch. Der nächste Schritt ist eine
regionale Mehrband-Kalibrierung beziehungsweise die Distanzsensitivität je Modus:
96,7 % der Wege über 20 km im Großraumlauf entfallen auf den ÖV, Pkw und
Mitfahrer tragen zusammen nur 76 von 2.270 Fernwegen. Dieser starke Modus-Effekt
muss vor einer Produktbeförderung erklärt und über mehrere Seeds mit realem
Routing validiert werden.

**Modus-Distanz-Ursache isoliert**: Im aktiven Rastatt-MNL ist der
Reisezeitkoeffizient für ÖV mit `b_tt_put=-0,05` nur ein Viertel so stark wie
für Pkw-Fahrer (`b_tt_car_d=-0,20`). Eine präregistrierte Ein-Faktor-Sonde auf
dem identischen Großraum-Export setzte nur `b_tt_put` auf `-0,20`. Die
Distanzverteilung blieb praktisch gleich (Ø **5,51→5,54 km**), aber der
ÖV-Anteil brach insgesamt **21,8→0,4 %** und ab 20 km **96,7→0,0 %** ein;
Pkw-Fahrer und Mitfahrer übernahmen dort 100 %. Der Koeffizient ist damit als
dominanter Hebel bewiesen, `-0,20` zugleich als Überkorrektur verworfen.

Getrennte Modal- und Distanz-RMSEs können diese Fehlallokation verdecken.
`simulation/kpis.json` enthält deshalb zusätzlich den bedingten
`modalSplitByDistanceBand` und den gemeinsamen 30-Zellen-
`modeDistanceJointRmseToMid2017`. Referenz ist die amtliche
[MiD-2017-Tabelle A W10.5](https://www.mobilitaet-in-deutschland.de/archive/pdf/MiD2017_Tabellenband_Deutschland.pdf);
die veröffentlichten Distanzklassen wurden ohne Interpolation auf dieselben
sechs Bänder aggregiert. Für Wege ab 20 km erwartet MiD beispielsweise
**61,1 % Pkw-Fahrer, 18,9 % Mitfahrer und 17,6 % ÖV**, nicht 96,7 % ÖV.
Der nächste Kalibrierlauf muss `b_tt_put` daher zwischen `-0,05` und `-0,20`
gegen diese gemeinsame Zielmatrix suchen und anschließend über drei Seeds mit
realem Routing bestehen.
Das Studio zeigt die vollständige 6×5-Matrix als Simulation/MiD-Paare; der
Szenario-Vergleich stellt den gemeinsamen RMSE laufübergreifend gegenüber.

## Modus-Distanz-Kalibrierung (2026-07-28) — Mechanik bestätigt, Beförderung gescheitert

Ein präregistriertes Raster auf demselben Großraum-Export variierte
ausschließlich `b_tt_put`: `-0,08/-0,10/-0,12`. Der ÖV-Anteil ab 20 km reagierte
streng monoton (**71,03/23,16/3,50 %**), der gemeinsame RMSE verbesserte sich
aber höchstens **0,054671→0,054109**. Kein Punkt bestand den vorab fixierten
Gate `<0,05` und mindestens `0,004` Verbesserung. Ein einzelner
Reisezeitkoeffizient kann die globale ÖV-Höhe und ihre Distanzschräge nicht
gleichzeitig treffen.

Die daraufhin separat präregistrierte Zwei-Hebel-Sonde trennte beide Effekte:
`b_tt_put=-0,11` steuert die Distanzschräge, `asc_put=-1,22` das Niveau.
Auf demselben Export ergaben sich **9,34 % ÖV gesamt** (MiD 10 %) und
**17,60 % ÖV ab 20 km** (MiD 17,61 %). Modal-RMSE verbesserte sich
**0,105742→0,085375**, der 30-Zellen-RMSE **0,054671→0,053002**;
Wegezahl (+0,37 %) und Distanzbänder (<0,1 Prozentpunkte Abweichung) blieben
stabil. Alle Mechanik-Gates bestanden.

**Keine Beförderung:** Der unveränderte Joint-Gate `<0,05` und die geforderte
Verbesserung um mindestens 0,004 scheitern. Hauptrestfehler ist jetzt die
Distanzmarginale selbst (im Großraum-Spike 0 % statt MiD 21 % unter 1 km),
die Moduswahlparameter nicht erzeugen können. Die gefundenen Werte werden
nicht in den Produktcode übernommen. Nächster Pflichtschritt: gemeinsamer
Drei-Seed-Vergleich mit echtem Routing und korrigierter Distanzmarginale;
ÖV-Zugang/Abgang im Legacy-Helper bleibt ein separater Modellierungsfehler.

## KIT actiTopp statt Template-Aktivitätenpläne (2026-06-12) — erfolgreich

Die Wochenpläne kommen jetzt aus dem **kalibrierten KIT-Modell actiTopp** (actitoppNG, Kotlin),
nicht mehr aus unserem handgetunten Template-Generator. actiTopp ist das offizielle mobiTopp-
Aktivitätenmodell (MOP-kalibriert). Architektur wie der logiTopp-Lauf: ein Kotlin-Runner
(`SynthesisRunner.kt` + Gradle-Task `runSynthesis -Pinput/-Poutput` im actitoppNG-Projekt) liest
die Personen-CSV, erzeugt je Person mit `DefaultPlanGeneration`/`AllChoiceModels.DEFAULT` einen
Wochenplan und schreibt `personId;activityType;startMinute;durationMinute`; das Backend
(`ActitoppRunner`) orchestriert den Prozess, `ActivityGenerator.generateFromActitopp` übersetzt
die actiTopp-Typen (WORK/EDUCATION/SHOPPING/LEISURE/TRANSPORT/HOME) in die Legacy-Codes.
Steuerung: Flag `useActitopp` (Default an, Frontend-Checkbox), Fallback auf den Template-Generator
bei Abschaltung/Fehler.

**Aktueller Stand (2026-07-27):** Die Verhaltensquelle wird inzwischen ausschließlich über
`behaviorSource` (`actitopp` | `gta` | `template`) gewählt. `useActitopp` wird nur noch beim
Einlesen alter Szenario-Dateien berücksichtigt und nicht mehr in API-Antworten ausgegeben.

**Befund (kritisch)**: actiTopp entscheidet anhand seines eigenen Modells, wer arbeitet/lernt —
unsere `needsWorkZone`-Heuristik passte nicht dazu, die Legacy-Zielwahl brach mit
*„No destination available for activity type: WORK"* ab. Fix: **jede Person bekommt ein festes
WORK- und EDUCATION-Ziel** (`fixedDestination.csv`); das Fixziel wird nur konsultiert, wenn die
Aktivität tatsächlich auftritt, ist also für Nicht-Arbeitende harmlos.

**Ergebnis** (voller Lauf, actiTopp + Routing + Außenbeziehungen + logiTopp): EXIT 0, 128.179
Wege, 6.290 Pakete. **Wege/Person·Tag 3,3 (MiD 3,1)** — jetzt aus dem kalibrierten Modell statt
handgetunt; Modal Split Auto 32 % / Fuß 32 % / Mitfahrer 16 % / ÖV 14 % / Rad 6 %; Ø Weglänge
3,5 km (weiterhin gebiets-/kalibrierungsbedingt). actiTopp-Mapping: SHOPPING→DAILY/OTHER nach
Dauer, LEISURE→OUTDOOR, TRANSPORT→SERVICE, INFANT übersprungen (Heimwoche).

## KIT populationsynthesis (2026-06-12) — läuft, aber datengebunden

Das echte KIT-`populationsynthesis` (IPU/IPU-Hierarchie, Kotlin, github.com/kit-ifv/
populationsynthesis) wurde geklont, gebaut (JDK 25, inkl. Spark-Abhängigkeit) und **erfolgreich
ausgeführt**: das gebündelte Marne-Beispiel synthetisiert mit `HierarchicDistribution` (GenericIPU)
eine zensus-gefittete Bevölkerung — **13.168 Personen** für die Gemeinden des Amts Marne-Nordsee
(passt zur realen Einwohnerzahl). Lauf: `sh gradlew :exampleCensus:runExample` (Subprojekt-`include`
in settings.gradle.kts ergänzt, JavaExec-Task `runExample`). Output `output/PopulationMarne.csv`:
`area(ARSKey);age;sex;householdID`.

**Befund (entscheidend für die generische Nutzung)**: Das Framework ist fundamental an deutsche
**amtliche Regionalschlüssel (ARSKey, Gemeindeebene) + Zensus-Regionaltabellen** gebunden
(`RuleProviderFactory`/`HierarchyFactory` mit fest verdrahteten Gemeinde-Keys; Marginalien aus
`Regionaltabelle_*.csv`). Eingaben: (1) ein nationaler Personen-Seed `personen_gesamt_DE_V2.csv`
(`ID;year;areaType;birthyear;sex`, **nicht im Repo** — hier synthetisch rekonstruiert, 18.552
Personen/9.000 HH), (2) Zensus-Regionaltabellen je Zielregion (nur Marne + Bayern gebündelt).

**Konsequenz**: Für unser „beliebige Region zeichnen"-Werkzeug ist eine *generische* Anbindung
**blockiert** — eine gezeichnete lat/lon-Box hat weder ARSKey noch Regionaltabelle. Das Tool liefert
zudem nur Alter+Geschlecht+Haushaltszuordnung (kein Erwerbsstatus/Einkommen — die ziehen wir
weiterhin selbst). Realistische Wege zur vollen Nutzung (Folgeaufwand, datenlastig):
- **Deutschland-Pfad**: Region → überlappende Gemeinden (ARS-Geometrie-Lookup) + Zensus-2022-
  Regionaltabellen je Gemeinde laden (Upload-Slot erweitern) → echter IPU-Lauf je Gemeinde.
- **Seed-Pfad (generisch, ohne reale Marginalien)**: KIT-synthetisierte Haushalte als realistisches
  Demografie-Sample, das unsere Pipeline nach Zonen-Einwohnerzahl verteilt — nutzt die realistische
  Alters-/Geschlechts-/Haushaltsgrößen-Joint-Verteilung, ohne reale zonale Marginalien.

Verifizierte Machbarkeit + I/O-Format liegen vor. Klone unter `validation/populationsynthesis/`
(gitignored).

**Umgesetzt — generischer Seed-Pfad (2026-06-12)**: Ein Runner (`SynthesisSample.kt` + Task
`:exampleCensus:runSynthesisSample`) exportiert die KIT-IPU-Synthese mit **eindeutigen laufenden
Haushalts-IDs** (statt der kollidierenden Seed-Template-ID des `CensusHouseholdConverter`, die alle
Klone zu Mega-„Haushalten" bündelt). Ergebnis: `kit-population-sample.csv` (6.512 Haushalte /
13.168 Personen, Ø 2,02 P/HH, Größen 40/36/12/8/5 %, Alter 0-17:15 % … 65+:27 %), als Backend-
Resource gebündelt. `PopulationSample` lädt den Pool, `PopulationGenerator` zieht bei
`useKitPopulation` ganze Haushalte je Zone bis zur Einwohnerzahl (echte Alters-/Geschlechts-/
Kompositions-Joint-Verteilung), Erwerbsstatus weiter altersbasiert. **Verifiziert**: voller Lauf,
person.csv-Demografie spiegelt den Pool (Ø-HH 1,98, Größen 40/36/11/8/5 %, Alter wie Sample),
Simulation EXIT 0, Wege/P/Tag 3,31. Caveat: das gebündelte Sample stammt aus dem ländlichen Marne
(Alter leicht erhöht); für repräsentative Demografie einen größeren/passenden Zensus-Lauf
einspielen — gleiches Format, `PopulationSample` nimmt es unverändert (Format = FlatCensusOutput
`householdId;age;sex`). Damit ist auch der spätere Deutschland-Zensus-Pfad direkt steckbar.

## MobileCity-Export (2026-06-12) — district-Config-Tabellen

Die erzeugten Daten gehen jetzt auch Richtung **MobileCity (Takomat-App)** — dem eigentlichen
Projektziel. Befund aus der lokalen MobileCity Network Pipeline (`Includes/StudyAreas/Karlsruhe`):
MobileCity ist ein **district-aggregiertes Strategiemodell** (eigene Simulation je Zieljahr
2025/2030/2050), das **keine Einzeltrips/Touren** importiert, sondern district-Tabellen
(POP*/REG*/INF*/TEC*/MOD*). Die Demand-Seite sind aggregierte `POPinhabitants` (Einwohner je
Altersgruppe je District) + `POPtrips` (mittlere Wegeraten je AgeGroup/TripPurpose/Jahr) +
`REGattractivity` (4 Purpose-Scores 0..100) + `REGdistricts` (Geografie).

`MobileCityExporter` (Pipeline-Stufe 9) erzeugt unter `artifacts/mobilecity/` die Study-Area-Config
`StudyAreaConfig.csv` (Jahre referenceYear/earliestDataYear=Basisjahr, endYear 2050; District-
Anzahlen Gebiet/Umland; Kartenzentrum aus den internen Zonen) und alle vier
Demand-Tabellen: `REGdistricts.csv` (Zone→District, intern=Typ 1 / extern=Typ 0),
`REGattractivity.csv` (Zonen-Attraktivität → Education/Work/Shopping/Leisure, je auf 0..100
normiert), `POPinhabitants.csv` (Children/Adults/Seniors je District je Basisjahr, aus der
KIT-Altersstruktur) und `POPtrips.csv` (mittlere Wege/Person·Tag je Altersgruppe × Zweck aus den
Aktivitätenplänen; jede Aktivitäts-Transition = ein Weg, Heimweg behält den Herkunftszweck).
**Verifiziert**: 64 Districts (56 intern/8 extern), Attraktivitäts-Scores 0..100 (genau ein 100 je
Purpose), Einwohner 6.042 = 907 Kinder/3.538 Erwachsene/1.597 Senioren (= KIT-Demografie). POPtrips
trifft die Karlsruhe-Referenz: **Erwachsene 3,44 (Ref 3,38), Senioren 3,04 (Ref 2,99)** Wege/Tag,
Zweck-Split plausibel (Erwachsene Work 1,02 / Shopping 0,95 / Leisure 1,39). Kinder 0–17 2,50
(Ref 3,16): Kleinkinder (age<6, von actiTopp nicht planbar) bekommen jetzt **begleitete Wege**
(`ActivityGenerator.youngChildDay`: Kita an den meisten Werktagen + gelegentliche Begleit-
Freizeit/-Einkäufe → ~5,5 Außer-Haus-Aktivitäten/Woche, vorher 0; hob die Kindergruppe 2,29 → 2,50);
der Rest-Abstand liegt an actiTopps MOP-kalibrierter Schulkind-Rate (6–17 ≈ 2,9/Tag), kein Defekt. Im MobileCity-Import-Format (Präambel + Variables + Values +
Datentabelle), District-IDs synthetisch `SYN-NNN`.

**Format gegen den echten Parser validiert (2026-06-21)**: Statische Analyse des C#-Config-Tools
(`takomat/EpigeneCSV`, `ST212x_*Reader`) ergab die exakten Parse-Regeln — und einen Bug im ersten
Export: Der `EpigeneCSVTableBuilder` trennt Tabellen **nur** an einer Zeile, die *exakt* der
„emptyLine" gleicht (Komma-Padding mit der Spaltenzahl der **ersten** Datei-Zeile); echte
Leerzeilen werden ignoriert. Unsere `,`-Trenner hatten die falsche Breite → `FindTable("districts")`
wäre gescheitert. Fix: feste Titel-Feldbreite (8) + Trenner exakt dieser Breite, je Tabelle
Name-/Header-/Datenzeilen (columnHeaderIndex=1), Sub-Labels `districts`/`attractivity`/
`population[AgeGroups.X]`/`averageDailyTrips[AgeGroups.X]`, Spalten `TripPurpose.*`/`tripPurpose`,
Enum-Werte exakt (Children/Adults/Seniors, Education/Work/Shopping/Leisure). Mit einer
Parser-Emulation (repliziert die Block-/emptyLine-Logik) bestätigt: alle vier `FindTable`-Aufrufe
greifen, Spalten/Zeilen/Werte korrekt. **Mit Takomat abzustimmen**: NUTS3-District-IDs (statt
`SYN-NNN`), vollständiger Study-Area-Import (Netz/TEC/MOD), Messagepack via Config Tool (.NET 8).

## Distanz-Kalibrierung über drei Seeds (2026-07-27) — teilweise wirksam

Präregistrierter A/B-Vergleich mit Seeds 42/43/44, je ca. 2.000 exportierten Personen, actiTopp,
logiTopp und lokalem Rastatt-OSM-Extrakt. Pro Seed war `population.calibrateDistance` der einzige
inhaltliche Unterschied. Alle sechs Läufe verwendeten GraphHopper
(`dataQuality.matrices = graphhopper-with-local-fallbacks`); actiTopp-, Routing- und
logiTopp-Fallbacks traten nicht auf.

| Seed | `<1 km` A→B | Ø km A→B | Distanz-RMSE A→B | Modal-RMSE A→B | Wege/P/Tag | Iterationen B |
|---|---:|---:|---:|---:|---:|---:|
| 42 | 15,2→15,2 % | 3,77→3,77 | 0,1055→0,1055 | 0,069→0,069 | 3,20 | 1 |
| 43 | 16,1→13,3 % | 3,76→3,84 | 0,1014→0,1094 | 0,073→0,070 | 3,26 | 2 |
| 44 | 15,5→15,5 % | 3,87→3,87 | 0,1019→0,1019 | 0,072→0,072 | 3,19 | 1 |

**Befund**: Das vorab festgelegte Nahdistanzfenster 12–16 % wird bei allen B-Läufen technisch
erreicht; der Mittelwert sinkt 15,6→14,7 %. Dieses Gate beruhte jedoch auf dem provisorischen
Default 0,14 und ist nach Beschaffung der amtlichen Tabelle fachlich ungültig: MiD weist für
`<1 km` 21 % aus; 14 % gehören zur benachbarten Klasse `1–<2 km`. Seed 43 wurde damit aktiv
in die falsche Richtung geregelt. Die Schutzmetriken des Laufs bestehen weiterhin:
Modal-RMSE verschlechtert sich nirgends, Wege/P/Tag liegen durchgehend in [3,0; 3,4].

**Sampling-Fix verifiziert**: `samplingFraction=0,05` erzeugt 2.045/2.033/2.037 Personen;
logiTopp verarbeitet den Export mit `fractionOfPopulation: 1.0`, woraus
2.020/2.007/2.010 mobile Ergebnis-Personen entstehen (98,7–98,8 % statt einer zweiten
5-%-Auswahl).

**Amtlicher Zielvektor und vollständiger Gate**: Die
[MiD-2017-Tabelle A W12](https://www.mobilitaet-in-deutschland.de/archive/pdf/MiD2017_Tabellenband_Deutschland.pdf)
weist bundesweit für alle Wege inklusive imputierter Werte neun Anteile
`[10, 11, 14, 23, 16, 13, 9, 2, 2] %` aus. Ohne Interpolation zu den sechs vorhandenen
Diagnosebändern aggregiert ergibt das `[21, 14, 23, 16, 13, 13] %`. Der vor der ersten
Berechnung ergänzte Gate (RMSE je B-Lauf höchstens 0,05; Verschlechterung gegenüber A
höchstens 0,005) scheitert: alle B-Läufe liegen bei 0,1019–0,1094, Seed 43 verschlechtert
sich zudem um 0,0080.

**Konsequenz**: Die Nahdistanz-Regel ist für weitere Experimente isoliert brauchbar, aber der
vollständige Distanz-Gate ist nicht bestanden. Der absolute Distanzmittelwert bleibt mit
3,77–3,87 km weit vom MiD-Anker 12,5 km entfernt; `calibrateDistance` bleibt
`default=false`. Der Betriebs-Default `distance-calibration-target-near-share` ist
**0,14→0,21** korrigiert und teilt nun dieselbe `Mid2017`-Referenz wie die KPI-Auswertung.
Die anschließende Drei-Seed-Wiederholung mit 0,21 steht im folgenden Abschnitt.
`SimulationResultIngestor` schreibt die sechs
gemessenen Anteile, den MiD-Zielvektor samt Quellenlink und `distanceBandRmseToMid2017`
direkt nach `simulation/kpis.json`.

## Distanz-Kalibrierung mit 21-%-Ziel (2026-07-28) — Mechanik bestanden, Beförderung gescheitert

Präregistrierte Wiederholung des A/B-Vergleichs mit identischer Laufmatrix (Seeds 42/43/44,
je 2.045/2.033/2.037 exportierte Personen, actiTopp, logiTopp, lokaler Rastatt-OSM-Extrakt).
Einziger A/B-Unterschied war erneut `population.calibrateDistance`; alle sechs Jobs endeten
mit `DONE`, `fractionOfPopulation: 1.0`, 98,7–98,8 % mobilen Personen und ohne ausgelösten
actiTopp-, Routing- oder logiTopp-Fallback.

| Seed | `<1 km` A→B | Ø km A→B | Median km A→B | Distanz-RMSE A→B | Modal-RMSE A→B | Wege/P/Tag | Iterationen B |
|---|---:|---:|---:|---:|---:|---:|---:|
| 42 | 15,15→19,49 % | 3,77→3,67 | 2,96→2,83 | 0,1055→0,0946 | 0,0697→0,0740 | 3,20 | 3 |
| 43 | 16,05→20,22 % | 3,76→3,66 | 2,95→2,83 | 0,1014→0,0919 | 0,0747→0,0795 | 3,26 | 3 |
| 44 | 15,52→19,70 % | 3,87→3,76 | 3,03→2,87 | 0,1019→0,0912 | 0,0729→0,0732 | 3,19 | 3 |

**Befund**: Der amtliche Zielwert 21 % wirkt reproduzierbar in der richtigen Richtung. Alle
B-Läufe erreichen das vorab festgelegte Nahdistanzfenster 19–23 %; der Sechs-Band-RMSE verbessert
sich je Seed um 0,0095–0,0109. Die Schutzmetriken bestehen: Modal-RMSE verschlechtert sich nur um
0,0003–0,0048 (Gate höchstens 0,02), Wege/P/Tag bleiben in [3,0; 3,4].

**Konsequenz**: Der Beförderungs-Gate `Distanz-RMSE ≤ 0,05` scheitert dennoch bei allen Seeds
(B: 0,0912–0,0946). Der einzelne `b_intrazonal`-Hebel korrigiert das kurze Ende, erzeugt aber
keine Fernwege: In B liegen nur 0,29–0,43 % der Wege bei mindestens 20 km statt 13 % in MiD;
Ø und Median sinken durch mehr Nahwege sogar leicht. `calibrateDistance` bleibt
`default=false`. Der nächste Versuch muss das lange Ende über einen größeren Untersuchungsraum
und/oder gravitationskonsistente externe Ziele adressieren; erst danach ist eine Multi-Band-
Kalibrierung mit getrennten Hebeln sinnvoll.

## Offene Punkte (nächste Schritte)

1. **MobileCity finalisieren** (Takomat): Parser-Anforderungen klären (Jahresspalten, NUTS3-IDs),
   alle vier Demand-Tabellen in die MobileCity Config Tool importieren/validieren, Messagepack-
   Konvertierung; ggf. Netz-/TEC-/MOD-Tabellen für einen vollständigen Study-Area-Import.
2. **Routing-Zuverlässigkeit (Produktiv)**: öffentliches Overpass rate-limitet die schwere
   Straßennetz-Abfrage → self-hosted Overpass oder gebündelter GraphHopper-PBF-Extrakt.
3. **populationsynthesis-Anbindung** entlang einer der beiden Pfade oben (Datenentscheidung nötig:
   Deutschland-Pfad mit Zensus-Tabellen vs. generischer Seed-Pfad).
2. **Wahlmodell-Kalibrierung**: Distanz-Sensitivität der Zielwahl (Rastatt-kalibriert) deckelt
   Weglänge; größtes verbleibendes Plausibilitäts-Thema (Fuß über, MIV-Fahrer unter MiD).
3. **MobileCity-Schnittstelle (Takomat)**: erzeugte Nachfrage/Touren in die Network Pipeline
   exportieren — eigentliches Projektziel; Import-Format mit Takomat klären.
4. Tutorial nutzt zone-`id`-Lookup für Bikesharing-Stationen → bei eigenen Zonen-IDs werden
   die Rastatt-Stationen verworfen (nur WARNING, kein Fehler).
5. KIT-Kontakt: Migrationsstand logiTopp → neuer Kern; offizielles Aktivitätsset mit 23/71.

## Reproduktion

```bash
cd validation/mobiTopp-tutorial
sh gradlew runLongTerm      # Referenz: Original-Synthese
sh gradlew runShortTerm     # Referenz: Original-Simulation
# Eigene Artefakte einspeisen: zones/attractivities → data/zone-repository/,
# Matrizen → data/matrix/, population-CSVs → results/long-term/demand-data/
# (fixedDestination.csv zusätzlich als fixeddestination.csv ablegen), Cache löschen:
rm -rf results/short-term/cache && sh gradlew runShortTerm
```
