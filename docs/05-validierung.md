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
Seit 2026-08-03 deckt der unten dokumentierte szenariogebundene OSM-XML-Upload den
reproduzierbaren Offline-Betrieb ab; Auto-Clip/PBF bleiben die Produktiv-Automatisierung.

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
  Lombok 1.18.46, `implementation`-Syntax, Publishing entschlackt). Historisch wurde
  `0.0-SNAPSHOT` publiziert; der Retouren-Vertrag verwendet heute die eindeutige Version
  `edu.kit.ifv.mobitopp:logitopp:0.0-synthesis-returns-v1`.
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
| L7 | Der unveränderte `AgentDeliveryClustering` gruppiert private Pickups über deren Producer. Da dies immer das Depot ist, kollabieren 13–20 Zielzonen eines Tages fast vollständig zu einem falschen Stopp. **Behoben:** Der Vertrag `synthesis-logitopp-returns-v1` gruppiert nach tatsächlicher Zone/Position. |
| L8 | `ParcelBuilder.get()` legt private Pickups im normalen Depotbestand ab, während die Tourplanung geplante Pickups aus `pickupRequests` entfernt. Mit mehreren räumlichen Touren wird dasselbe Paket dadurch erneut geplant (`ONDELIVERY`). **Behoben:** Depot-Pickups werden im versionierten logiTopp-Patch über `requestPickup(...)` registriert. |
| L9 | `planned-tours.csv` enthält kein abschließendes Leg vom letzten Stopp zurück zum Depot. **Behoben:** `SumoRunner` ergänzt pro normaler Tour genau ein Abschluss-Leg und vermeidet Duplikate bei `isReturning=true` oder bereits erreichtem Depot. |
| L10 | Paketparameter konnten früher in `parcel-demand-config.yaml` stehen, ohne vom Harness gelesen zu werden. **Behoben:** Paket- und Harness-Vertrag werden explizit geprüft; alter oder driftender Code schlägt vor dem Lauf bzw. vor dem Publizieren fehl. |
| L11 | `vehicle_events.csv.collected` ist an `stop`-Zeilen der kumulative Fahrzeugbestand und würde beim Summieren mehrfach zählen; erst `event=unload` protokolliert jede eingesammelte Retoure genau einmal. **Behoben:** Die Ingestion summiert `to_pickup`/`success_pickup` nur an Stopps, `collected`/`returning` nur beim Depot-Unload und gleicht Pickup-Erfolg gegen eindeutige `IsPickup=true`-Paket-IDs ab. |
| L12 | `destinationShares` war im erzeugten YAML versehentlich neben statt unter `parcelOrders` eingerückt; der echte Harness brach deshalb mit `Missing parcel demand key` ab. **Behoben:** Generator und Vertragstest prüfen den verschachtelten Block. |
| L13 | `planned-tours.csv.deliveries` und `.pickups` enthalten kommaseparierte Paket-IDs pro Stopp, keine numerischen Mengen. Ein numerischer Parser erzeugte dadurch falsche Tour-KPIs. **Behoben:** Die Ingestion zählt IDs; synthetischer und echter Fixture-Test sichern 65 Zustellungen/67 Pickups. |
| L14 | `Map.of(...)` lieferte für Zielanteile zwischen getrennten JVM-Prozessen keine garantierte Iterationsreihenfolge. Gleiche Zufallszahlen wurden dadurch unterschiedlichen Zieltypen zugeordnet. **Behoben:** Der Harness-Vertrag baut `LinkedHashMap` in der festen Reihenfolge HOME, WORK, PACKSTATION; zwei Prozessläufe sind in allen fachlichen Rohartefakten byte-identisch. |

## Retouren-Pickup-Spike (2026-07-29) — mechanisch bewiesen, Integration damals offen

Der präregistrierte Spike nutzte eine bestehende 2.000-Personen-Population mit
`samplingFraction=0.05` und reiner Pickup-Nachfrage. Der erste Lauf endete zwar mit Exit 0 und
101 eingesammelten Paketen, war wegen L7 aber räumlich ungültig. Die tatsächliche
Positionsclusterung deckte anschließend L8 als zweite unabhängige Ursache auf.

Mit beiden Korrekturen lief die externe Wochensimulation erfolgreich: **101 eindeutige
`IsPickup=true`-Pakete, 87 geplante und eingesammelte Pickups, 82 Pickup-Stoppereignisse,
`to_pickup=87`, `success_pickup=87`, `collected=87`, keine doppelt geplante Paket-ID und kein
Positionsfehler**. Die übrigen 14 Aufträge blieben im endlichen Wochen-/Kapazitätshorizont offen;
sie sind keine fehlgeschlagenen Zustellungen. `returning=0` bestätigt die Trennung von
Kundenretouren und Zustell-Rückläufern.

Ein echter Pickup-Stopp aus diesem Lauf wurde anschließend vom bestehenden SUMO-Adapter
eingelesen und gegen einen Ausschnitt des tatsächlich verwendeten OSM-Netzes geroutet:
**ein Lkw-Leg, 6,81 km, 430 s, 1,61 kg CO₂, 66 befahrene Kanten; Test Exit 0**. Damit ist der
Abholstopp im Verkehrsmodell nachgewiesen, nicht jedoch das fehlende Abschluss-Leg zurück zum
Depot (L9).

**Entscheidung nach dem Spike:** bedingtes Go für die Pickup-Mechanik, noch kein Produkt-Go. Die
gitignorierten Belege liegen unter `validation/returns-spike-2026-07-29/`. Die 64
logiTopp-Legacy-Tests konnten wegen ihres JUnit-4-Imports im auf JUnit 5 modernisierten Build
nicht kompiliert werden; der Hauptcode wurde erfolgreich gebaut und publiziert, dieser
Test-Skip zählt aber nicht als Evidenz.

### Retouren-Konfigurationskette (2026-07-29) — Schritt 2 erfolgreich

Der versionierte Vertrag `synthesis-logitopp-returns-v1` behebt L7–L10 reproduzierbar.
`LogisticsGenerator` schreibt `synthesis-parcel-demand-v1` mit sicheren
`returns.enabled=false`-Defaults; `SimulationRunner` übergibt Datei und Vertragskennung und
verweigert alte Harness-Versionen. Der Installer unter `legacy/logitopp-returns-v1/` prüft
Patch-, Vorher- und Nachher-Hashes, wendet die zwei GPL-Patches an und publiziert die eindeutige
Maven-Version `0.0-synthesis-returns-v1`. Der idempotente Installationspfad und der
Harness-Klassenbau liefen mit Exit 0.

Ein echter externer Mischlauf mit bewusst aktivierter Retourenquote `1.0` lief mit Exit 0:
**101 Zustellpakete plus 101 Kundenretouren** für 96 Agenten. Innerhalb des endlichen
Wochen-/Kapazitätshorizonts wurden **65 Zustellungen und 67 Pickups** geplant und erfolgreich
ausgeführt. Alle 67 geplanten Pickup-IDs waren eindeutig, alle lagen am in den Paketbestellungen
ausgewiesenen Ziel, und die 67 beim Stopp erfolgreichen Pickups wurden beim Depot-Unload wieder
gezählt. `returning=0` bestätigt weiterhin die semantische Trennung. Die offenen 36
Zustellungen und 34 Retouren blieben `UNDEFINED`; sie sind keine fehlgeschlagenen Zustellungen.

Der reale SUMO-Korridortest routete denselben Pickup anschließend als **Hinweg plus
Depot-Rückfahrt**: 2 Lkw-Legs/Fahrzeuge, zusammen **3,03 kg CO₂**, Test Exit 0. Damit ist L9
nicht nur durch einen Parser-Test, sondern an der echten externen Kette geschlossen.

**Entscheidung nach Schritt 2:** technisches Go für den MVP hinter einem standardmäßig
deaktivierten Szenario-Flag. Noch kein Produkt-Go: Szenario-API/Frontend, richtungsgetrennte
Ingestion/KPIs und die vollständigen Akzeptanzläufe sind die nächsten Schritte.

### Richtungsgetrennte Retouren-KPIs (2026-07-29) — Schritt 4 erfolgreich

`SimulationResultIngestor` zählt Paket-IDs aus `parcel-states.csv` eindeutig nach `IsPickup`,
trennt Zustellerfolg und `RETURNING` von Kundenretouren und verdichtet Stopps sowie
Depot-Unloads nach der in L11 belegten Semantik. Der additive Vertrag
`synthesis-logistics-kpis-v1` wird in `kpis.json`, `depot-stats.json`,
`parcel-density.geojson` und `tours.geojson` gespiegelt. Vor dem Schreiben der Artefakte prüft
die Laufzeit-Postcondition die Empfänger-genaue Rundungsbandbreite der Retourenziehung,
Richtungskonsistenz sowie `success_pickup = collected =` eindeutige, beim Depot angekommene
Pickup-Pakete.

Der read-only Regressionslauf gegen den echten Schritt-2-Mischlauf ergab erneut
**101 Zustellaufträge / 65 zugestellt, 101 Kundenretouren / 67 geplant und eingesammelt,
0 Zustell-Rückläufer**. Damit ist die Ingestion gegen externe Rohdaten belegt; `validation/`
blieb unverändert und gitignoriert.

**Entscheidung nach Schritt 4:** technisches Go für den abschließenden Akzeptanzlauf und die
Beförderungsentscheidung. Bis dahin bleiben Feature und Quote standardmäßig aus.

### Retouren-E2E-Akzeptanz (2026-07-29) — Schritt 5 erfolgreich

Zwei Szenarien mit 100 Personen, Seed `4242`, identischer Region, Paketnachfrage,
Zustellzielanteilen, GraphHopper-Routing sowie den Zielmodellen logiTopp und SUMO wurden
vollständig ausgeführt. Der einzige fachliche Unterschied war
`returns.enabled=false` gegenüber `enabled=true`, Quote `1.0`, Abholung HOME `1.0`.
Beide externen logiTopp-Prozesse und beide SUMO-Läufe endeten mit Exit 0.

| Kennwert | Baseline | Retouren | Differenz |
|---|---:|---:|---:|
| Personenwege | 2.137 | 2.137 | 0 |
| Zustellaufträge / zugestellt | 86 / 86 | 86 / 86 | 0 / 0 |
| Kundenretouren / geplant / eingesammelt | 0 / 0 / 0 | 86 / 86 / 86 | +86 / +86 / +86 |
| Zustell-Rückläufer | 0 | 0 | 0 |
| logiTopp-Touren | 72 | 124 | +52 |
| Lieferverkehr-Legs in SUMO (Tag 0) | 23 | 47 | +24 |
| SUMO-Fahrzeuge | 65 | 86 | +21 |
| CO₂ gesamt | 48,04 kg | 59,06 kg | +11,02 kg |
| CO₂ Lieferverkehr | 10,19 kg | 21,19 kg | +11,00 kg |

Die normalisierten Zustellaufträge waren zwischen A und B identisch; die Retouren erweitern
also die Nachfrage, statt Zustellungen zu ersetzen. `tours.geojson` aggregierte exakt
86/0 beziehungsweise 86/86 Zustellungen/Pickups. Zwei Wiederholungen des Retouren-Szenarios
erzeugten byte-identische `parcel-orders-private.csv`, `parcel-states.csv`,
`planned-tours.csv`, `tours.geojson` und `kpis.json`; die SUMO-Kennzahlen waren bis auf die
gemessene Laufzeit ebenfalls identisch. Ein zusätzlicher Paarlauf in getrennten JVM-Prozessen
bestätigte byte-identische Nachfrage-, Flotten-, Paket-, Tour- und Fahrzeugereignisdateien.

Die Browser-Abnahme gegen genau diese Szenarien bestätigte Studio und Vergleich einschließlich
Richtungstrennung, A/B-Kennzahlen und SUMO-Emissionen; die Browser-Konsole enthielt weder Fehler
noch Warnungen. Die gitignorierten Laufbelege liegen unter
`validation/returns-acceptance-2026-07-29/`.

**Beförderungsentscheidung:** technisches Go für den experimentellen MVP. Kein Produktiv-Default
und keine Prognoseaussage: `returns.enabled=false` sowie Quote `0.0` bleiben bestehen, bis eine
dokumentierte Datengrundlage die Retourenquote und Rücklaufzeiten kalibriert. Packstation-Abgabe,
kausaler Lebenszyklus, Segmentierung und Händler-Senken sind eigenständige Ausbaustufen mit den
in `docs/06`, G.7 festgelegten Evidenz-Gates.

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

## Native Verkehrsnachfrage-Schicht (2026-07-30) — Parser und Bilanzen bestanden

Der neue Vertrag `synthesis-travel-demand-v1` wurde gegen kleine adversariale Fixtures und
den vorhandenen realen Rastatt-Output geprüft. Die Tests belegen:

- beliebige Header- und Raw-Zeilenreihenfolge sowie das Legacy-Abschluss-Trennzeichen `;`;
- korrekte Verdichtung eines unsortierten Walk–ÖV–Walk-Wegs aus drei Legs: kanonischer
  Hauptmodus aus `mainMode`, rohe `tripMode`-Sequenz erhalten, Ursprung/geplante Abfahrt vom
  ersten Leg, Ziel/geplante Ankunft vom letzten Leg, Distanz als Leg-Summe, Leg-Modi/-Zonen
  erhalten;
- die nachgewiesene mobiTopp-0.3.580-Provenienzgrenze: Aktivitätskoordinaten werden nicht als
  Leg-Geometrien ausgegeben oder dokumentiert;
- Fail-fast mit Datei-, Zeilen-, Feld- und Wegangabe bei fehlenden Spalten, doppelten Legs und
  `NaN`, überlaufender Leg-Distanzsumme oder unplausiblem Zeitüberlauf; unbekannte Moduscodes
  bleiben als `other` plus Raw-Code sichtbar;
- exakte 15-Minuten-Grenzen, getrennte Wochentage, gerichtete A→B/B→A- und intrazonale
  Relationen sowie die Mengenbilanzen von Wegen, OD, Abfahrten und Ankünften;
- byte-identische native CSV-Artefakte bei anderer Raw-Zeilenreihenfolge und byte-identische
  Gesamtausgabe bei Wiederholung desselben Inputs;
- explizite SUMO-Bilanz für anderen Tag, nicht abbildbare Modi/fehlendes ÖV-Netz und Modus-Cap.

Beim dokumentierten Lauf wurde das optionale Golden-Fixture gelesen: **70.188 Wege/Legs** mit den
Moduszählern Rad **3.895**, Pkw **15.288**, Mitfahrer **7.081**, Fuß **36.439** und ÖV
**7.485**. Der bestehende KPI-Multi-Leg-Test bestätigt unverändert die Aggregation von
`12 + 9 = 21 km` vor der Distanzband-Zuordnung. Damit ist die interne Semantik und
Reproduzierbarkeit bewiesen; die verkehrliche Prognosegüte der Nachfrage ist dadurch nicht
automatisch validiert. Die vollständige Offline-Backend-Suite lief anschließend mit
**93 Tests, 0 Fehlern und 6 umgebungsabhängigen Skips**; der installierte SUMO-E2E-Test
lief dabei tatsächlich durch und wurde nicht übersprungen.

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

### Szenariogebundener Offline-Netzvertrag (2026-08-03) — Mechanik bestanden

Der bewiesene lokale OSM-XML-Pfad ist nun als Nutzervertrag verfügbar:
`POST /api/scenarios/{id}/uploads/road-network` speichert atomar
`uploads/road-network.osm`. Ein Streaming-Inspector akzeptiert nur OSM-XML mit gültigen Nodes und
mindestens einem `highway`-Way, deaktiviert DTD/externe XML-Ressourcen, prüft die vollständige
Abdeckung der gespeicherten Szenarioregion und bildet SHA-256. Ein ungültiger Ersatz lässt die
zuletzt akzeptierte Datei unangetastet.

Die Quellenpriorität lautet **Szenario-Upload → globale `road-network-file`-Konfiguration →
Overpass**. Routing und SUMO teilen weiterhin exakt einen In-Memory-Abruf je Lauf. Job-Summary und
`manifest.json/dataQuality.roadNetwork` tragen die konkrete Provenienz
`scenario-upload:road-network.osm@<hash12>`; das Frontend zeigt Upload und Löschung im Studio.

Validierung am realen `validation/rastatt-test.osm` (23.846 Nodes, 7.241 Ways): vollständiger
GraphHopper-8-Import, routbares Zonenpaar und kompletter Pipeline-E2E-Lauf mit der
szenariogebundenen Datei. Die fokussierte Suite
`RoadNetworkFileTest, UploadControllerTest, OverpassClientTest, GenerationPipelineE2ETest`
bestand mit 14/14 Tests. **Nicht behauptet:** PBF. Dafür ist weiterhin eine separat zu beweisende
Konvertierungs-/GraphHopper-/SUMO-Kette nötig.

### Pendler-OD und direkter BA-Adapter (2026-08-03) — Mechanik und Acht-Anker-Gate bestanden

Der optionale Upload `uploads/commuters.csv` akzeptiert den normalisierten Vertrag
`homeLat;homeLon;workLat;workLon;commuters[;homeScope]` (Semikolon/Tab/Komma; bei Semikolon auch
Dezimalkomma) und direkt den amtlichen BA-CSV-Export `Auspendler Gemeinden`. Der BA-Adapter behält
exakte achtstellige Gemeinde-Relationen, verwirft die wiederholten Hierarchiezeilen und löst AGS
über den amtlichen BKG-WFS-Layer `vg250:vg250_pk` auf. Der reale Rastatt-Export (Berichtsmonat
Juni 2025, Datenstand Januar 2026) enthält 79 räumlich auflösbare Relationen mit 12.650 von 13.720
Auspendlern (92,201 %), 113 übersprungene Aggregatzeilen und null unaufgelöste Gemeinden.

Der BA-Export enthält definitionsgemäß nur Beschäftigte, deren Wohn- und Arbeitsgemeinde
verschieden sind. Ihn als vollständige WORK-Verteilung zu verwenden würde alle Arbeitswege nach
außen zwingen. Deshalb schreibt der Adapter `homeScope=external`: Das bestehende Gravitationsmodell
entscheidet weiterhin, **ob** eine Außenbeziehung entsteht; nur dann bestimmt die amtliche
Auspendlerverteilung das Ziel. Die unbehandelte WORK-Gravitationsverteilung wird vor dem Verschieben
der gemeinsamen Außenanker fixiert. Ein eigener stabiler Zufalls-Substream je Person und Zweck hält
Demografie, Haushalte, Pkw und spätere Aktivitätszufälle in A/B-Versuchen konstant. Damit ist je
Seed nicht nur die Population bytegleich, sondern auch die Zahl gewählter externer Arbeitsorte.

Die fokussierte Suite einschließlich BA-Dialekt, BKG-CSV-Vertrag, bedingter Ziehung,
Zufallsstrom-Isolation und echtem GraphHopper-Pipeline-E2E bestand mit 22/22 Tests. In jedem
gewerteten A/B-Paar sind `household.csv`, `person.csv` und `car.csv` byte-identisch.

Der vorregistrierte Drei-Seed-Versuch verwendete 2.500 Personen, actiTopp, logiTopp, dieselbe
Rastatt-Bbox und dasselbe hochgeladene OSM-Netz (SHA-256
`1de66fccfbcd79b03b879e2ab139795bb36607c3c7fefd42315f5d872e096ce9`). Alle sechs Jobs endeten
`DONE`, ohne actiTopp-, Routing- oder logiTopp-Fallback. Ein technischer Pilot vor der Wertung
wurde wegen eines gemeinsam verbrauchten Zufallsstroms verworfen. Ein zweiter Probelauf deckte auf,
dass verschobene Anker die Wahrscheinlichkeit einer Außenbeziehung mitänderten, und wurde ebenfalls
nicht gewertet. Erst nach der Fixierung der unbehandelten WORK-Gravitation wurde ohne Änderung der
Schwellen in `data-v3` neu gestartet.

| Seed | Haushalte A/B | externe WORK A/B | Ø km A→B | `≥20 km` A→B | Distanz-RMSE A→B | Modal-RMSE A→B | Wege/P/Tag A→B |
|---|---:|---:|---:|---:|---:|---:|---:|
| 42 | 1.249/1.249 | 443/443 | 3,84→4,62 | 0,40→3,30 % | 0,0999→0,1019 | 0,0681→0,0729 | 3,19→3,18 |
| 43 | 1.213/1.213 | 407/407 | 3,76→4,60 | 0,39→3,12 % | 0,1048→0,1068 | 0,0714→0,0780 | 3,20→3,18 |
| 44 | 1.267/1.267 | 438/438 | 3,72→4,50 | 0,42→2,97 % | 0,1001→0,1018 | 0,0700→0,0732 | 3,21→3,20 |

**Entscheidung:** Der vorregistrierte Promotions-Gate ist bestanden. Die mittlere Weglänge steigt
in jedem Seed und im Mittel um 0,80 km (3,77→4,57 km; Gate mindestens +0,5 km). Der Fernwegeanteil
steigt im Mittel um 2,73 Prozentpunkte (0,40→3,13 %; Gate mindestens +1 Punkt). Wege/Person/Tag
bleiben mit 3,18–3,20 im zulässigen Band 3,0–3,4; die mittlere Modal-RMSE steigt nur von 0,0698 auf
0,0747 (Delta +0,0049; Gate höchstens +0,02). Auch die Distanzband-RMSE verändert sich nur von
0,1016 auf 0,1035. Die acht gemeinsamen Richtungsanker werden daher als wirksamer, weiterhin
unzureichender Distanzhebel befördert. Der unmittelbar folgende Datenhebel sind die unten
geprüften Arbeitsplatz-Randsummen (13111); danach folgt die Validierung in weiteren Regionen.
Eine feinere Peripherie-/Korridorzonierung ist nicht mehr die unmittelbare Voraussetzung.

### Regionalstatistik-13111-Arbeitsplatz-Randwert (2026-08-03) — Drei-Seed-Gate bestanden

Der zweite optionale Upload akzeptiert den unveränderten Flat-File-CSV-Export der amtlichen
Tabelle `13111-08-02-5-B` (UTF-8 oder ISO-8859-1). Verwendet werden ausschließlich Statistik
`13111`, Gemeindezeilen und die Messcodes `ERW033` (SvB am Wohnort) und `PEN092`
(Auspendelnde). Je Gemeinde gewinnt der neueste vollständige Stichtag; BKG-VG250-Punkte wählen
die Gemeinden in der Szenarioregion. Der normalisierte, szenariogebundene Vertrag liegt als
`uploads/workplace-margins.csv` vor. Ein ungültiger Ersatz überschreibt keinen akzeptierten Wert.

Die Modelltrennung ist hart: `PEN092/ERW033` entscheidet reproduzierbar, **ob** das WORK-Fixziel
außen liegt; die BA-Relationen entscheiden bedingt, **wo** außen. Ohne externe Pendler-OD wird
der Randwert protokolliert, aber nicht angewendet. Ohne Randwert bleibt der bisherige BA-only-
Pfad unverändert. Rastatt weist am 30.06.2025 13.723 Auspendelnde bei 22.971 SvB am Wohnort auf,
also 59,7405 %. Die BA-Gesamtsumme 13.720 weicht um drei Personen ab; die Randwertquelle bleibt
deshalb eigenständig und wird nicht aus dem OD-Export rekonstruiert.

Der vorregistrierte Vergleich verwendete als Kontrolle die akzeptierten BA-only-Läufe aus
`data-v3` und generierte nur die Behandlung BA+13111 neu. Bbox, 2.500 Personen, Seeds,
actiTopp, logiTopp und OSM-Hash
`1de66fccfbcd79b03b879e2ab139795bb36607c3c7fefd42315f5d872e096ce9` blieben gleich.

| Seed | externe WORK BA→+13111 | Ø km BA→+13111 | `≥20 km` BA→+13111 | Distanz-RMSE BA→+13111 | Modal-RMSE BA→+13111 | Wege/P/Tag BA→+13111 |
|---|---:|---:|---:|---:|---:|---:|
| 42 | 443→1.510 (60,40 %) | 4,62→8,00 | 3,30→10,71 % | 0,1019→0,0828 | 0,0729→0,0923 | 3,18→3,14 |
| 43 | 407→1.491 (59,64 %) | 4,60→8,12 | 3,12→10,40 % | 0,1068→0,0852 | 0,0780→0,0954 | 3,18→3,14 |
| 44 | 438→1.511 (60,44 %) | 4,50→8,18 | 2,97→11,47 % | 0,1018→0,0807 | 0,0732→0,0952 | 3,20→3,15 |

Alle drei Jobs endeten `DONE`; die Ist-Außenquote liegt je Seed weniger als 0,8 Prozentpunkte
vom Ziel entfernt. `household.csv`, `person.csv` und `car.csv` sind je Paar byte-identisch.
Im Mittel steigt die Weglänge 4,57→8,10 km, der Fernwegeanteil 3,13→10,86 % und der
Distanz-RMSE sinkt 0,1035→0,0829. Zugleich steigt die Modal-RMSE 0,0747→0,0943, vor allem wegen
23–24 % ÖV. **Entscheidung:** Randwert befördert; Distanz bleibt unter MiD 12,5 km/13 % und die
Langweg-Moduswahl braucht einen eigenen Kalibrierzyklus. Übertragbarkeit auf weitere Regionen
ist noch nicht bewiesen.

Gesamtgates nach Implementierung und Dokumentation: `mvn -o test` mit 117 Tests,
0 Fehlern und 6 bewusst umgebungsabhängig geskippten Tests; `npx ng build` erfolgreich.

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

**Konsequenz im Produktcode (2026-07-28):** Die Mechanik ist als experimenteller
`ModeDistanceCalibrator` hinter `population.calibrateModeDistance=false`
verfügbar, ohne die gefundenen Werte `-0,11/-1,22` zu übernehmen. Der Regler
schätzt die nötigen Änderungen aus jedem Lauf und führt `asc_put` (Niveau) und
`b_tt_put` (Distanzschräge) getrennt. Bei gleichzeitigem
`calibrateDistance=true` wird zuerst `b_intrazonal` kalibriert; alle folgenden
Modus-Distanz-Läufe verwenden sowohl dieses Zielwahl-Verzeichnis als auch die
jeweils aktuelle MNL-Datei. `SimulationRunnerTest` belegt die gemeinsame
Run-Config-Verdrahtung. Eine anschließend aktivierte Netzfeedback-Schleife führt
beide Overrides flag-gesteuert weiter; deaktivierte Flags können dadurch keine
alten Kalibrierartefakte desselben Szenarios reaktivieren. Das ist eine
Implementierung des positiven Mechanismusbelegs, keine nachträgliche Beförderung
des gescheiterten 30-Zellen-Gates. Der damals nächste Pflichtlauf mit drei
Seeds und Realrouting wurde am 2026-07-29 begonnen und über sein Stop-Gate
beendet (folgender Abschnitt).

## Gemeinsame Kalibrierung mit Realrouting (2026-07-29) — strukturell gescheitert

Der präregistrierte Drei-Seed-Vergleich wurde nach dem Paar A42/B42 über das
vorab definierte Stop-Gate beendet. Beide Läufe verwendeten 576 Zonen,
2.000 Personen, actiTopp, logiTopp und denselben lokalen OSM-Abzug; die
SHA-256-Summen von `CAR_DIS`, `CAR_TTC` und `PUT_TTC` sind A/B-identisch.
GraphHopper lief in beiden Fällen ohne Stufen-Fallback.

**Befund:** Bei 2.300-m-Zellen liegt der einzige intrazonale Pkw-Matrixwert
auf **1,62 km** (`0,5214 × 2,3 km × 1,35`). Der Zielwahlhebel
`b_intrazonal` verändert nur die Zahl intrazonaler Ziele, nicht diesen Wert.
Deshalb blieb der kalibrierte `<1 km`-Anteil über alle acht Iterationen
**0,0→0,0 %**, obwohl der Parameter-Delta von **0→14** stieg. B konzentrierte
stattdessen **81,54 %** der Wege im Band 1–<2 km. Gegen A verschlechterten
sich Ø-Weglänge **8,57→5,05 km**, Distanzband-RMSE
**0,1343→0,3072**, globaler Modal-RMSE **0,0800→0,1176** und
30-Zellen-RMSE **0,0438→0,0857**. Der nachgelagerte Zwei-Hebel-Regler traf
seine direkten Ziele annähernd (ÖV gesamt **9,28 %**, ÖV ab 20 km
**18,89 %**), kann die zerstörte Distanzmarginale aber nicht reparieren.
Wege/Person/Tag blieben plausibel (**3,23→3,24**), die Wegezahl stabil
(**+0,13 %**).

**Konsequenz:** Die Seeds 43/44 wurden regelkonform nicht gestartet und beide
Flags bleiben `default=false`. Vor dem GraphHopper-Aufbau prüft die Pipeline
nun den tatsächlich repräsentierten intrazonalen Pkw-Wert. Liegt dessen
Minimum bei `>=1 km`, schlägt `calibrateDistance=true` mit einer konkreten
Auflösungsmeldung fehl, statt acht wirkungslose logiTopp-Läufe auszuführen.
Eine neue Kampagne benötigt feinere Zonen oder eine separat hergeleitete
Distanzrepräsentation/Zielgröße.

### Großraum × feine Zonierung (2026-08-11) — Ressourcen bestanden, Mechanik unentscheidbar

**Befund:** Das präregistrierte 1-km-INSPIRE-Gitter für die Bbox
48,613/7,825–49,107/8,575 erzeugt exakt **3.249 Zonen** und hält den
intrazonalen Pkw-Wert mit **0,70389 km** im beobachtbaren `<1 km`-Band. Drei
isolierte Läufe schrieben jeweils alle 13 Luftlinienmatrizen in **90,608 s**,
**93,252 s** und **94,148 s**. Der intern gemessene Java-Heap-Peak betrug
**1.376,9 MiB**, der vollständige Matrixsatz **215.640 KiB**. Stop-Gate 1 ist
damit bestanden.

**Evidenz:** A42 erreichte anschließend mit 2.000 Personen, 56.859
Template-Aktivitäten und 3.249 Zonen den Legacy-Simulationsexport. Nach rund
122 s Matrixlauf und 13:08 min Legacy-Export blieb der externe Gradle-Build
länger als 31 Minuten in `compileJava` hängen. Ein Thread-Dump zeigte
`UnixNativeDispatcher.fdopendir` über `DirectorySnapshotter`; `lsof` lokalisierte
den Snapshot im gitignorierten Quellbaum
`validation/mobitopp-rastatt/src/main/java/edu/kit/ifv/mobitopp/populationsynthesis/mobilityprovider`.
logiTopp startete nicht. Der konfigurierte 30-Minuten-Timeout beendete den
blockierten Wrapper nicht zuverlässig; der isolierte Lauf wurde kontrolliert
gestoppt und B42 nicht gestartet.

**Konsequenz:** Stop-Gate 2 ist **unentscheidbar**, nicht gescheitert. Es gibt
noch kein A/B-Ergebnis zur Distanzhypothese. Vor dem nächsten A42 muss der lokale
KIT-Quellbaum unter Erhalt seines Zustands gegen den gepinnten Runtime-Vertrag
rekonstruiert werden. Erst nach einem vollständigen A42 darf B42 starten; Seeds
43/44 und Multi-Band-Regler bleiben bis zum vorab definierten Mechanik-Gate aus.

**Rekonstruktionsbefund (2026-08-11):** Der alte Harness wurde verlustfrei nach
`validation/mobitopp-rastatt.backup-20260811-e23` verschoben und der gepinnte
Commit `8ba241b6d1cd` frisch geklont. Der zuvor blockierte `mobilityprovider`-Pfad
ist danach sofort lesbar (8 KiB). Der vollständige Bootstrap ist trotzdem noch
nicht reproduzierbar: Der frische Clone liefert für `build.gradle` den Hash
`6a23e17a…` und 188 CRLF-Zeilen, während
`legacy/logitopp-returns-v1/apply.sh` vor dem Patch zwingend `fecda2bf…`
erwartet. Dieser Vorzustand wird vom Bootstrap-Skript nicht hergestellt. Zudem
blieb der Vertrags-Smoke im bestehenden `validation/actitoppNG` beim regulären
Gradle-Dateihashing auffällig langsam. Deshalb wurde weder der Hash-Guard
umgangen noch A42 gestartet. Nächster Reparaturschritt ist eine gesicherte
Rekonstruktion aller verbleibenden KIT-Clones plus ein explizit versionierter
Transformationsschritt vom echten mobiTopp-Upstream zum erwarteten
logiTopp-Vertrags-Baseline.

**Fixture-Folgewirkung und Teil-Wiederherstellung (2026-08-13):** Der Re-Clone nahm
nicht nur den Vertrag mit, sondern auch die Ergebnisdateien des alten Harness — und
damit die Eingabe-Fixture dreier Testklassen. `SumoRunnerTest`, `PtIntegrationTest`
und ein Teilfall von `LogitoppDemandAdapterTest` gingen daraufhin still in SKIPPED,
obwohl SUMO, ÖV-Kette und Legacy-Adapter selbst intakt waren. **Befund**: Die
Fixture-Abhängigkeit ist von E23 trennbar — sie hängt am Ergebnisartefakt
`output/synthesis/results/`, nicht am Vertrag oder am Hash-Guard. Zurückkopiert
wurden aus `mobitopp-rastatt.backup-20260811-e23` ausschließlich die beiden
referenzierten Dateien, bit-identisch geprüft: `demandsimulationResult.csv`
(19.112.725 B, SHA-256 `35a4cc63…`) und `planned-tours.csv` (737.385 B,
`e4f2eb9f…`); der 403 MiB große Rest des `output/`-Baums blieb im Backup.
**Evidenz**: `SumoRunnerTest` 26,0 s statt SKIPPED, `PtIntegrationTest` 124,9 s
statt SKIPPED, `LogitoppDemandAdapterTest` 4/4 statt 3/4. Die volle Backend-Suite
lief anschließend mit **127 Tests, 0 Fehlern und 0 Skips** in 43:07 min
(`SUMO_HOME`, `SYNTHESIS_TRANSIT_VENV`, `SYNTHESIS_RUN_ACTITOPP_TEST` und ein
laufender Ollama gesetzt). `scripts/preflight.sh full` fiel von zwei auf einen
Fehler. `planned-tours.csv` ist dabei nicht kosmetisch: erst damit ist `tours` in
`SumoRunnerTest` non-null und die Logistik-Asserts (`deliveryTrips() > 0`,
Lkw-CO₂ > 0) laufen wirklich mit.

**Konsequenz**: Die technische Abnahme ist wieder vollständig belegt, E23 selbst
bleibt davon **unberührt und offen**. `scripts/bootstrap-runtime.sh kit` scheitert
weiterhin am `build.gradle`-Hash (`6a23e17a…` vs. erwartet `fecda2bf…`),
`preflight.sh full` meldet weiterhin „FEHLT logiTopp-Harness-Vertrag", und A42/B42
bleibt gesperrt. Eine zurückkopierte Fixture ist ein Testeingang, kein
rekonstruierter Laufzeitvertrag — sie darf nicht als Beleg für einen
reproduzierbaren Bootstrap gewertet werden. Nebenbefund: Mit kaltem Plan-Cache
entfallen 2.389 s der 43 min allein auf `GtaBehaviorTest` (isoliert mit warmem
Cache: 53 s).

### Bootstrap-Vertrag rekonstruiert (2026-08-13) — E23 aufgelöst

**Befund**: Die dokumentierte Reparatur — ein versionierter Transformationsschritt
zur erwarteten Baseline `fecda2bf…` — ist **nicht durchführbar**. Der Altpatch
wurde mit `--ignore-space-change` angewandt und ist damit vorwärts verlustbehaftet:
strikt rückwärts scheitert er (`patch does not apply`, `SimulatorBuilder.java:307`).
Für `build.gradle` ließ sich die Baseline zufällig exakt zurückrechnen, für
`SimulatorBuilder.java` (erwartet `7bc76816…`) nicht. Im Backup-Repo existieren
weder Stash noch dangling Blobs, nur der Clone-Reflog-Eintrag — der Zwischenstand
ist unwiederbringlich. Derselbe Phantom-Vorzustand steckte auch im logiTopp-Teil
des Vertrags (erwartet `84e8499d…`, Upstream liefert `f363ac01…`).

**Zweiter Befund**: Der Vertrag umfasste nie den vollständigen Umbau. Upstream →
Vertragszustand sind **elf** getrackte Dateien, darunter ein **Gradle-Wrapper-Upgrade
6.3 → 9.5.0**, das von keinem Hash-Guard erfasst war. Das ist nicht kosmetisch:
Upstream pinnt Gradle 6.3, `runtime/versions.env` pinnt `SYNTHESIS_KIT_JAVA=25`.
Ein Bootstrap, der Upstream getreu wiederherstellt, erzeugt einen Harness, der
überhaupt nicht baut. Hinzu kommen **58 gitignorierte Pflicht-Dateien** (42
DCML-generierte Klassen, 6 `.xmi`-Modelle, 8 `.gen`, 2 Config-Varianten): Die
`.gitignore` des Harness schließt `**/gen/`, `**/generated/`, `*.xmi` und `*p.yaml`
aus, während der Vertrags-Build das Codegen-Plugin entfernt („die generierten
Klassen sind committed"). Zehn nicht-generierte Harness-Dateien — darunter
`SimulatorBuilder.java` selbst — referenzieren diese Pakete. Ohne sie kompiliert
nichts.

**Konsequenz**: Der Vertrag wurde auf den Upstream-Stand umgestellt statt auf einen
nicht rekonstruierbaren Zwischenstand. `legacy/logitopp-returns-v1/` enthält jetzt
zwei strikte Patches (Upstream → Vertragszustand, ohne `--ignore-space-change`,
inkl. Binär-Diff des Wrapper-Jars) plus `harness-artifacts/` mit den 58
Pflicht-Dateien und einem SHA-256-Manifest. `apply.sh` prüft je Repo entweder den
Upstream- oder den bereits installierten Vertragszustand, patcht strikt,
installiert die Artefakte und verifiziert Nach-Zustand und Manifest.

**Evidenz**: Aus einem frischen Upstream-Clone erzeugt der Weg alle fünf
Vertrags-Hashes byte-exakt; der getrackte Baum ist identisch zum Backup.
`compileJava` des rekonstruierten Harness: **BUILD SUCCESSFUL in 3 s** — dieselbe
Stufe, an der E23 über 31 Minuten hing. Idempotenz (zweiter Lauf), Mischfall
(frischer Harness + bereits gepatchtes logiTopp) und Manipulationserkennung
(verändertes Artefakt → Abbruch) sind einzeln geprüft. Nach der echten
Installation meldet `scripts/preflight.sh full` erstmals **0 Fehler, 0 Hinweise**.

### Großraum-A/B durchgeführt (2026-08-15) — Stop-Gate 2 nach Vorab-Regel nicht bestanden

Das präregistrierte A/B lief erstmals vollständig durch. Die Läufe liegen unter
`backend/data/scenarios/`: **A42 `6fb122a7`** (ungültig, siehe unten), **B42
`71720d5c`**, **A42b `7d6101af`** (gültiger A-Arm). Die logiTopp-Stufe, an der E23
über 31 Minuten hing, brauchte 102–392 s.

**Befund 1 — stiller Konfundierer**: A42 und B42 waren *nicht* vergleichbar. In A42
lief die Overpass-Abfrage nach Siedlungs-`place`-Nodes ins Timeout (OSM-Stufe
297,4 s) und fiel auf POI-Dichte zurück; B42 erhielt 868 Siedlungen. Ergebnis:
`dataQuality.populationDistribution` **`poi-density` vs. `osm-places`**, 2.000 vs.
1.595 Haushalte — also unterschiedliche Wohnstandorte, die Zielgröße des
Experiments. Die Stufe blieb dabei **DONE**, der Fallback stand nur als Feld im
Manifest. Konsequenz: A42 wird als ungültig geführt, nicht gelöscht. Der A-Arm wurde
als A42b in derselben Backend-Session wie B42 wiederholt (prozessweite
OSM-Memoisierung); danach unterscheiden sich die Manifeste in **genau einem**
inhaltlichen Feld, `population.calibrateDistance`.

**Befund 2 — Ergebnis des gültigen Kontrasts** (A42b → B42):

| Vorab-Kriterium | Schwelle | Ergebnis | |
|---|---|---|---|
| Distanzband-RMSE sinkt gegen A | ≥ 0,02 | 0,0582 → 0,0448 = **0,0134** | ❌ |
| Distanzband-RMSE absolut | ≤ 0,08 | 0,0448 | ✅ |
| `<1 km` | 19–23 % | 15,81 % → **20,99 %** | ✅ |
| `>=20 km` | ≥ 8 % | 7,30 % | ❌ |
| Modal-RMSE-Verschlechterung | ≤ +0,02 | +0,0049 | ✅ |
| Wege/Person·Tag | [3,0; 3,4] | 3,07 | ✅ |

**Entscheidung nach Vorab-Regel: Seeds 43/44 werden nicht gestartet.** Die Regel
greift über die Oder-Bedingung — das `<1 km`-Band hat sich klar bewegt (Ziel 21 %
punktgenau in 2 Kalibrier-Iterationen, `b_intrazonal`-Δ 0,85), aber die
RMSE-Verbesserung bleibt unter 0,02.

**Befund 3 — Mechanik des Hebels**: `calibrateDistance` nimmt die kurzen Wege aus
den **mittleren** Bändern, nicht aus dem langen Ende: `1–2 km` −2,6 pp, `2–5 km`
−1,5 pp, während `>=20 km` in beiden Armen bei **exakt 7,30 %** liegt. Der Hebel ist
damit besser konditioniert als angenommen. Das verfehlte `>=20 km`-Kriterium misst
folglich nicht den Hebel, sondern Region und Aufbau — es ist in A und B identisch.

**Konsequenz**: Der Stopp ist regelkonform, sagt aber mehr über die Präregistrierung
als über den Regler. Die relative Schwelle (A − 0,02) unterstellte ein schwächeres A;
tatsächlich erreicht B mit 0,0448 absolut bereits ein Niveau unter der
Promotions-Schwelle ≤0,05. Die Regel wird **nicht** nachträglich aufgeweicht. Ein
zweiter Anlauf braucht eine **neue** Präregistrierung mit an A42b geeichten
Schwellen und einem Distanzband-Kriterium, das den Hebel tatsächlich abbildet.
Vorbedingung für jede weitere Kampagne: `populationDistribution` vor dem Vergleich
prüfen, nicht hinterher im Manifest entdecken.

### Distanz-Abkling-Sonde (2026-08-15) — Stop-Gate 1 bestanden, Mechanismus bestätigt

Elastizitäts-Sonde der präregistrierten Kampagne „Distanz-Abklingen": additiver
Positivterm X auf `b_tt_car_d`, `b_tt_car_p`, `b_tt_put`, `b_tt_ped`, `b_tt_bike`
in `destination_choice_utility_calculation_parameters.txt`, über den
`destinationChoice:`-Override gegen den **wiederverwendeten Legacy-Export von A42b**
(Szenario `7d6101af`) — pro Lauf genau ein Unterschied, logiTopp je ~58 s.

**Harness-Validierung**: X=0 reproduziert A42b exakt (43.045 Trips, Ø 6,22 km, alle
sechs Bänder auf 0,1 pp). Die Sonde misst damit ausschließlich den Parameter.

| X | Ø km | `<1` | `1–2` | `2–5` | `5–10` | `10–20` | `>20` | `>=10 km` | Band-RMSE |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 6,22 | 15,8 | 24,3 | 27,8 | 15,5 | 9,3 | 7,3 | **16,57 %** | 0,0582 |
| +0,01 | 6,35 | 15,2 | 23,0 | 28,5 | 16,3 | 9,7 | 7,3 | **17,01 %** | 0,0559 |
| +0,03 | 6,67 | 13,6 | 20,9 | 28,7 | 18,3 | 11,0 | 7,5 | **18,47 %** | 0,0540 |
| +0,06 | 7,41 | 11,3 | 16,7 | 27,9 | 22,0 | 14,1 | 8,0 | **22,09 %** | 0,0560 |

**Befund**: Die Vorhersage („`>=10 km` steigt monoton, bei X=+0,06 mindestens 20 %")
trifft zu — **Stop-Gate 1 bestanden**. Der Mechanismus ist bestätigt: Die Masse wandert
weit (`1–2 km` −7,6 pp gegen `5–10 km` +6,5 pp und `10–20 km` +4,8 pp), nicht ins
Nachbarband. Damit war die Residualbilanz aus B42 (+10,95 pp Überschuss 1–5 km gegen
−10,94 pp Defizit ab 5 km) die richtige Diagnose.

**Drei Nebenbefunde**: (1) `>20 km` bewegt sich kaum (7,3 → 8,0 %) — das ganz lange Ende
bleibt ein Gebietsgrößen-Limit, für Zielwahl-Parameter unerreichbar. (2) Der Hebel
beschädigt das Nahband (`<1 km` 15,8 → 11,3 %); deshalb fährt Gate 2 `calibrateDistance`
in **beiden** Armen als Gegensteuerung. (3) Das Sonden-RMSE ist **nicht monoton**
(Optimum bei X=+0,03), weil ohne Nahband-Regler der `<1 km`-Einbruch bestraft wird —
für die Dosiswahl mit Kalibrierung ist dieses Ranking irreführend.

**Dosiswahl X=+0,06**: nicht nach RMSE, sondern nach dem präregistrierten
Mechanismuskriterium `>=10 km ≥ 20 %` — das erreicht nur diese Dosis (+0,03 liefert
18,47 %). Die Wahl ist damit durch die Vorab-Schwelle bestimmt, nicht nachträglich gesetzt.

**Korrektur zur Präregistrierung**: Dort steht als Ausgangswert 16,29 % (der Wert von
B42). Die Sonde läuft auf dem A42b-Export mit 16,57 %. Die absolute Schwelle ≥20 % ist
davon unberührt.

**Werkzeug-Befund**: Der `ParameterEvaluator` lehnt vorzeichenlose Zusatzterme ab
(`Cannot parse parameter:  -0.0688 -0.05 0.01`). Angehängte additive Terme brauchen ein
explizites `+` oder `-`; die bisherige Kampagnen-Doku zeigt nur negative Beispiele.

### Distanz-Abkling-A/B (2026-08-15) — Stop-Gate 2 bestanden (Seed 42)

Seed-identisches A/B in **einer** Backend-Session: **C42 `36b1fb61`** (Kontrolle,
`calibrateDistance=true`, Betas unverändert) gegen **D42 `90e250ae`** (zusätzlich
`b_tt_*` +0,06). Die E24-Vorbedingung ist erfüllt — beide Manifeste sind bis auf
id/Name/Zeitstempel **identisch** (`osm-places`, 1.595 Haushalte); der Beta-Patch ist
kein Manifest-Feld, daher ist die Differenz dort nicht sichtbar und wurde über den
Kalibrier-Startwert verifiziert (D42 startet bei 11,3 % — exakt dem Sondenwert für
X=+0,06). C42 reproduziert B42 exakt (gleiche Trajektorie 15,8 → 21,0 %, 43.046 Wege).

| Kriterium (vorab) | Schwelle | C42 | D42 | |
|---|---|---|---|---|
| Distanzband-RMSE | ≤ 0,035 | 0,0448 | **0,0258** | ✅ |
| `>=10 km` | ≥ 20 % | 16,28 % | **20,70 %** | ✅ |
| `<1 km` | 19–23 % | 20,99 % | 21,34 % | ✅ |
| `1–2 km` | ≤ 18 % | 21,73 % | **13,75 %** | ✅ |
| Modal-RMSE | ≤ +0,02 | 0,1031 | 0,1094 (+0,0063) | ✅ |
| Wege/Person·Tag | [3,0; 3,4] | 3,07 | 3,07 | ✅ |

**Befund**: Alle sechs Vorab-Kriterien bestanden. Der Distanzband-RMSE fällt auf
**0,0258** und unterbietet damit auch die Promotions-Schwelle ≤0,05 deutlich. Das
`1–2 km`-Band, zuvor mit +7,73 pp der dominante Residualbeitrag (49,7 %), trifft mit
13,75 % praktisch den MiD-Wert 14 %. Ø-Distanz 6,08 → 6,93 km.

**Konsequenz**: Der Mechanismus „Distanz-Abklingfehler" ist damit auf Seed 42 bestätigt
— die Kombination aus `b_tt_*`-Anpassung (langes Ende) und `b_intrazonal` (Nahband)
löst beide Marginalen gleichzeitig, was `b_intrazonal` allein nicht konnte. Die
Drei-Seed-Wiederholung (43/44) ist nach Vorab-Regel freigegeben.

**Zwei offene Punkte vor jeder Beförderung**: (1) `>=20 km` bleibt mit 7,89 % gegen MiD
13 % der neue dominante Residualbeitrag — das ist die Gebietsgrößen-Grenze, die die
Sonde vorhergesagt hat und die Zielwahl-Parameter nicht erreichen. (2) **Der
`b_tt_*`-Override hat kein Szenario-Flag.** D42 entstand durch temporäres Patchen von
`config/shared/parameters/destination_choice_utility_calculation_parameters.txt`
(Pristine-Stand danach per Hash verifiziert wiederhergestellt). Für eine Beförderung
muss dieser Hebel denselben versionierten Weg bekommen wie `calibrateDistance` —
sonst ist das Ergebnis nicht reproduzierbar konfigurierbar. Der zugewiesene
Widerlegungsversuch in einer zweiten Session ist mit der unten dokumentierten,
eingefrorenen Seed-43-Wiederholung abgeschlossen.

### Drei-Seed-Wiederholung (2026-08-16/17) — Gate mit eingefrorenem Seed 43 bestanden

Nachgefahren wurden C/D für Seeds 43 und 44 in **einer** Backend-Session, dazu zwei
Wiederholungsläufe zur Ursachenklärung. Ergebnis der gültigen Kontraste:

| Seed | C-RMSE | D-RMSE | C `>=10 km` | D `>=10 km` | C `1–2 km` | D `1–2 km` | Status |
|---|---|---|---|---|---|---|---|
| 42 | 0,0448 | **0,0258** | 16,28 % | 20,70 % | 21,73 % | 13,75 % | gültig |
| 43 | 0,0439 | **0,0240** | 16,39 % | 21,04 % | 21,32 % | 13,53 % | gültig (eingefrorener Export) |
| 44 | 0,0408 | **0,0245** | 17,12 % | 21,48 % | 20,94 % | 12,90 % | gültig |

Alle sechs Vorab-Kriterien sind in allen drei Seeds erfüllt, die Promotions-Schwelle
≤0,05 wird mit max. 0,0258 klar unterboten. **Das Drei-Seed-Gate ist abgeschlossen.**

**Befund (Ursache, forensisch geklärt)**: Bei Seed 43 unterscheiden sich die
`attractivities.csv` der beiden Arme; `zones.csv` ist in allen Läufen byte-identisch.
Die Divergenz entsteht also in Stufe 3 aus den OSM-Eingangsdaten, nicht in der
Zonierung. Folgekette: Attraktivitäten → Bevölkerungsverteilung → Haushaltsbildung
(C43 1579, D43 1564, D43-Wdh 1550 Haushalte bei je 2.000 Personen).

**Der Mechanismus ist nicht Nichtdeterminismus im Code.** Zwei Kontrollläufe mit
identischer Konfiguration (C43 und C43-Wdh, 28 Minuten auseinander) liefern
byte-identische Attraktivitäten, Haushalte und Personen. Bei gleichen Eingaben ist die
Pipeline reproduzierbar. Variabel ist die **Overpass-Antwort**: D43 lud 866 statt 868
Siedlungs-`place`-Nodes und brauchte dafür 50 s statt 8–10 s; der Wiederholungslauf
D43-Wdh lud zwar wieder 868, erhielt aber erneut abweichende Attraktivitäten — die
Knotenzahl allein belegt Datengleichheit also nicht. C43, C44 und D44 teilen denselben
Attraktivitäts-Hash; betroffen waren nur die beiden D43-Läufe. Es variiert nicht jeder
Lauf, sondern gelegentlich einer — und die Pipeline meldet das nicht: beide Stufen
bleiben DONE, `dataQuality.populationDistribution` steht in beiden Armen auf
`osm-places`.

**Konsequenz für die Kampagne bis 2026-08-16**: Zwei gültige Seeds (42, 44) bestanden
alle Kriterien; der damalige Seed 43 war **unentscheidbar, nicht negativ**. Eine
belastbare Drei-Seed-Aussage verlangte eine stabile gemeinsame Eingabe.

**Konsequenz fürs System** (wichtiger als das Kampagnenergebnis): Ein A/B war nur dann
ein Ein-Faktor-Kontrast, wenn beide Arme zufällig dieselbe Overpass-Antwort erhielten.
**✅ Technische Abhilfe (2026-08-17):** Ein validierter, szenariogebundener
`uploads/osm-data.json`-Snapshot hat nun Vorrang vor Live-Overpass. `manifest.json`
schreibt unter `inputProvenance` sowohl Quelle und SHA-256 dieses Snapshots als auch
den SHA-256 der tatsächlich erzeugten `zone-repository/attractivities.csv`. Der
Offline-E2E-Test führt die Pipeline mit einem Snapshot aus und prüft beide 64-stelligen
Hashes. Damit kann die Kampagne identische Eingaben erzwingen und nach einem Neustart
belegen.

**✅ Gültige Seed-43-Wiederholung (2026-08-17):** Da alle erreichbaren Overpass-Endpunkte
beim Erzeugen des historischen Snapshots mit demselben Dispatcher-Timeout antworteten,
wurde kein unvollständiger Snapshot akzeptiert. Stattdessen liefen C43 und D43 direkt auf
demselben bereits vollständig erzeugten C43-Legacy-Export. Beide Arme verweisen damit
bytegleich auf Bevölkerung, Aktivitäten, Matrizen und Logistik; der einzige inhaltliche
Unterschied ist der vorab definierte additive Term `+0,06` auf allen fünf `b_tt_*`-Werten
in D43. C43 reproduziert 43.084 Wege und RMSE 0,043898. D43 konvergiert nach zwei
`b_intrazonal`-Iterationen (10,8→22,1 %) auf 43.076 Wege und RMSE **0,024046**.

Die sechs Vorab-Gates bestehen: `>=10 km` **21,04 %**, `<1 km` **22,15 %**,
`1–2 km` **13,53 %**, Modal-RMSE **0,102→0,108** (Δ +0,006) und
**3,077 Wege/Person·Tag**. Gegenüber der Kontrolle steigt die mittlere Weglänge
**6,19→7,00 km**. Der Ein-Faktor-Kontrast schließt damit Seed 43 und das
Drei-Seed-Gate. **Technische Beförderung (2026-08-17):** Das opt-in-Flag
`population.correctDistanceDecay=false` erzeugt nun pro Szenario einen Parameterstand
unter `simulation/distance-decay-adjusted/`, statt die geteilten Legacy-Dateien zu
patchen. Es addiert exakt `+0,06` auf die fünf bestätigten `b_tt_*`-Parameter und ist mit
`calibrateDistance`, den Modus-Kalibrierungen und Netz-Feedback komponierbar. Ein
Produktdefault ist damit ausdrücklich noch nicht befördert: Das `>20 km`-Band bleibt in
D43 mit 8,10 % unter dem MiD-Anteil 13 %, und ein Mehrregionen-Beleg steht aus.

### Mehrregionen-Transferprobe Freiburg (2026-08-17) — präregistriert, Ergebnis offen

**Hypothese vor dem ersten Lauf:** Der auf drei Seeds im Großraum Rastatt bestätigte
Term `b_tt_* +0,06` verschiebt auch in einem disjunkten Untersuchungsraum Wegemasse aus
dem Band `1–2 km` nach `>=10 km`; die nachgelagerte `b_intrazonal`-Kalibrierung stellt
das `<1 km`-Band wieder her. Geprüft wird Seed 42 in einem etwa 30×30-km-Ausschnitt um
Freiburg (`47.85–48.12 N`, `7.70–8.10 E`) mit 1-km-INSPIRE-Zellen, 2.000 Personen,
Template-Verhalten, Luftlinienmatrizen und Zielmodell logiTopp. Kontrolle T-C42 und
Behandlung T-D42 unterscheiden sich ausschließlich in
`population.correctDistanceDecay=false→true`; `calibrateDistance=true` gilt in beiden
Armen.

**Eingabevertrag:** Beide Arme erhalten byte-identisch denselben validierten
`uploads/osm-data.json`-Snapshot. Vor der Auswertung müssen Snapshot- und
`attractivities.csv`-SHA-256 übereinstimmen; zusätzlich werden Zonen, Personen,
Aktivitäten, Matrizen und der Legacy-Demand-Vertrag gehasht. Die tatsächliche
INSPIRE-Auflösung muss 1 km und der kleinste intrazonale Pkw-Matrixwert `<1 km` sein.
Eine Abweichung macht den Kontrast **unentscheidbar**, nicht negativ.

| Vorab-Kriterium | Schwelle für T-D42 |
|---|---:|
| Distanzband-RMSE absolut | `<= 0,05` |
| Distanzband-RMSE gegen T-C42 | Verbesserung `>= 0,01` |
| `>=10 km` | `>=20 %` und Zunahme `>=3 pp` |
| `<1 km` | `19–23 %` |
| `1–2 km` | `<=18 %` |
| Modal-RMSE-Verschlechterung | `<= +0,02` |
| Wege/Person·Tag | `[3,0; 3,4]` |

**Stop-Regel:** Verfehlt T-D42 eines der sieben Kriterien, werden Seeds 43/44 nicht
gestartet und der Transfer gilt als nicht bestätigt. Besteht T-D42 alle Kriterien,
werden zwei weitere Seeds auf demselben eingefrorenen OSM-Snapshot freigegeben; erst
danach ist ein Mehrregionen-Claim zulässig. Der Anteil `>20 km` wird separat gegen MiD
13 % berichtet, ist wegen des endlichen Zuschnitts aber kein nachträglich veränderbares
Gate dieser Mechanismusprobe. Ein Produktdefault bleibt unabhängig vom Ein-Seed-Ergebnis
gesperrt.

### ÖV-Zugang und -Abgang (2026-07-28) — im externen Legacy-Vertrag blockiert

SyntheSis erzeugt `PUT_ACT.mtx.bz2` und `PUT_EGT.mtx.bz2` mit je sechs Minuten.
Der tatsächlich verwendete externe Legacy-Helper
`MultiNomialLogit1HelperOldImpl` liefert für
`getACCESS_TIME_PUBLICTRANSPORT(...)` und
`getEGRESS_TIME_PUBLICTRANSPORT(...)` jedoch hart `0.0`; auch das dort verfügbare
`ImpedanceIfc` bietet keinen Zugriff auf separate Zugangs-/Abgangsmatrizen.
Damit erreichen die erzeugten Werte den Nutzen-Term `b_acc_put` derzeit nicht.

**Konsequenz:** Kein scheinbarer Fix durch Addition auf `PUT_TTC`: Das würde
Zugang/Abgang fachlich mit der fahrzeuggebundenen Reisezeit vermischen und den
existierenden Parametervertrag umgehen. Eine belastbare Korrektur benötigt eine
Erweiterung beziehungsweise einen Adapter im externen Legacy-Projekt und danach
einen echten A/B-Lauf. Bis dahin ist dies eine bekannte Modellgrenze.

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
Seit 2026-08-04 ergänzt `TECCarFleet.csv` als fünfte Fachtabelle den privaten Pkw-Bestand
`stock.total` je District im Basisjahr. Die Aggregation zählt `car.csv` über den Haushaltseigentümer
und `household.homeZone`; Neuzulassungen und Elektroanteile werden mangels kalibrierter Daten bewusst
nicht erzeugt und bleiben Takomat-Defaults.
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

**TECCarFleet verifiziert (2026-08-04):** `MobileCityExporterTest` emuliert die gepaddete
EpigeneCSV-Blocklogik unabhängig vom Writer, findet exakt `stock.total` mit Header
`districtID,2025` und weist Mehrfach-Pkw sowie das Ignorieren unbekannter Haushaltszonen nach.
`GenerationPipelineE2ETest` fordert das Artefakt in der vollständigen Offline-Pipeline.

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
   die vorhandenen Demand- und Geometrietabellen im MobileCity Config Tool importieren/validieren,
   Messagepack-Konvertierung sowie Defaults für Netz-/TEC-/MOD-Tabellen für einen vollständigen
   Study-Area-Import mit Takomat festlegen.
2. **Routing-Zuverlässigkeit (Produktiv)**: szenariogebundener, validierter OSM-XML-Upload ist
   umgesetzt; offen bleiben Auto-Clip/Cache und eine vollständig bewiesene PBF-Kette.
3. **Populationsdaten verbreitern**: Der generische Seed-Pfad ist umgesetzt; ein direkter
   Deutschland-Pfad über ARS-Geometrien und Zensus-Regionaltabellen bleibt eine optionale,
   datenabhängige Erweiterung.
4. **Distanz-/Moduskalibrierung übertragen**: Arbeitsplatz-Randwert und BA-Außenanker sind für
   Rastatt befördert; offen sind der Mehrregionen-Beleg und eine produktive
   Langweg-Moduskalibrierung.
5. **Bikesharing-Zonenreferenz**: Das Tutorial nutzt einen zone-`id`-Lookup für Stationen; bei
   eigenen Zonen-IDs werden
   die Rastatt-Stationen verworfen (nur WARNING, kein Fehler).
6. **Externe Klärungen**: KIT-Kontakt zu logiTopp-Migration, actitoppNG-Lizenz und offiziellem
   Aktivitätsset; Takomat-Klärung zu den noch benötigten MobileCity-Defaults.

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
