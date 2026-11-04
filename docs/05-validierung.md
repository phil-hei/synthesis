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

## OSM-Außenzielkandidat (2026-09-01) — Messpfad umgesetzt, Promotion offen

`OsmExternalDestinationCandidates` verdichtet bevölkerte OSM-Siedlungen außerhalb der Region
bis 80 km in acht Himmelssektoren. Pro Sektor werden Bevölkerungsanteil, gewichteter Schwerpunkt,
mittlere Entfernung und Anteil ab 20 km in
`population/external-destination-candidates.json` geschrieben. Die leichte Overpass-Abfrage für
`place`-Nodes deckt seit dem Review vom 2026-09-08 den vollständigen 80-km-Radius um den
Regionsmittelpunkt ab; Live-Daten bleiben wie bisher im
`osm-data.json` mit SHA-256 einfrierbar.

**Befund**: Der technische Vergleichspfad misst bei vorhandener Pendler-OD automatisch den
Richtungsanteils-MAE, den relativen Fehler der mittleren Außendistanz und die Abweichung des
Anteils ab 20 km. Ein synthetischer Referenzfall mit identischer 75-%-Nord-/25-%-Ost-Verteilung
liefert für alle drei Fehlermaße **0**; der Offline-Pipeline-E2E schreibt Artefakt, Hash und
Messwerte ins `manifest.json/externalRelations`.

Die Bevölkerungsschätzung filtert Live- und Snapshot-Places auf den bisherigen lokalen
Abfrageausschnitt. Entfernte Siedlungen erweitern damit nur die Diagnose. Der Replay-Test
vergleicht die Hashes von Personen, Haushalten, festen Zielen, Aktivitäten und Attraktivitäten
mit und ohne Außensiedlung und prüft, dass kein veraltetes Kandidatenartefakt zurückbleibt.

**Konsequenz**: Der Kandidat ist ausdrücklich `diagnostic-only` und verändert weder die acht
produktiven Ringzonen noch die Zielwahl. Die Promotion bleibt an mindestens drei Regionen und
drei Seeds sowie die in docs/06 vorregistrierten Fehler- und Nachfragegates gebunden. Damit ist
der Messaufbau vorhanden, ohne amtliche Ziele vorzeitig oder unsichtbar durch OSM zu ersetzen.

<a id="osm-aussenziele-vorpruefung-2026-09-10"></a>

### Räumliche Vorprüfung in drei Regionen (2026-09-10) — ❌ Kriterien verfehlt

**Befund:** Der unveränderte OSM-Siedlungskandidat verfehlt in Rastatt, Freiburg und Karlsruhe
jeweils beide Distanzkriterien. Die Richtungs-MAE besteht jeweils. Das ist ein Negativbefund
für die festgelegten Regionsboxen und die geokodierbaren BA-Relationen, keine allgemeine
Widerlegung von OSM als Datenquelle für Arbeitsziele.

Die Hypothese und Abbruchregel wurden am 2026-09-09 vor der ersten Auswertung in
`backend/data/campaigns/osm-external-2026-09-09/preregistration.md` festgehalten: Alle drei Regionen
müssen Richtungs-MAE ≤0,15, relativen Fehler der mittleren Außendistanz ≤25 % und absolute
Abweichung des Anteils `>=20 km` ≤10 Prozentpunkte bestehen. Bei räumlichem Fehlschlag folgen
keine Nachfrage-Wirkungsläufe. Die Schwellen aus docs/06 wurden nicht nachträglich verändert.

**Referenz und Zuschnitt:** Öffentlicher CSV-Export der
[BA-Pendlerstatistik](https://statistik.arbeitsagentur.de/DE/Navigation/Statistiken/Interaktive-Statistiken/Pendler/Pendler-Nav.html),
Berichtsmonat/Gebietsstand Juni 2025, Datenstand Januar 2026; gefiltert auf die drei
Wohnortschlüssel. `BaCommuterAdapter` verarbeitet die Gemeindeziele mit eingefrorenen
BKG-VG250-Koordinaten. Die nativen Overpass-Abfragen wurden je Region eingefroren; nach
HTTP 429/504 gelang Karlsruhe mit gleicher räumlicher Abfrage und erhöhtem Timeout.

| Region / Wohnort-AGS | Breite min…max | Länge min…max | BA-Export gesamt | Geokodiert / Abdeckung | Davon Ziel innerhalb Box | Vergleichsgewicht außen |
|---|---|---|---:|---:|---:|---:|
| Rastatt / `08216043` | 48,825…48,885 | 8,155…8,245 | 13.720 | 12.650 / 92,20 % | 0 | 12.650 |
| Freiburg / `08311000` | 47,85…48,12 | 7,7…8,1 | 27.350 | 25.210 / 92,18 % | 8.370 | 16.840 |
| Karlsruhe / `08212000` | 48,93…49,09 | 8,27…8,55 | 44.220 | 41.340 / 93,49 % | 9.970 | 31.370 |

Die Boxen sind **keine Gemeindegrenzen**: Insbesondere die großen Boxen schließen Ziele in
Nachbargemeinden aus dem Außenvergleich aus. Die übrigen rund 6,5–7,8 % der Exportgewichte sind
nicht als einzeln geokodierbare Gemeindeziele enthalten; sie werden weder als Null noch als
bekannte räumliche Verteilung behandelt. Unter den eingelesenen Gemeindezielen gibt es keine
ungelösten AGS. Alle nachfolgenden Referenzanteile sind auf das Vergleichsgewicht außen bedingt.

| Region | Richtungs-MAE (≤0,15) | Ø Außendistanz BA → OSM | Relativer Distanzfehler (≤25 %) | Anteil ≥20 km BA → OSM | Abweichung (≤10 pp) |
|---|---:|---:|---:|---:|---:|
| Rastatt | 0,09995 ✅ | 31,55 → 55,95 km | 77,35 % ❌ | 34,62 → 95,26 % | +60,64 pp ❌ |
| Freiburg | 0,07216 ✅ | 117,34 → 57,62 km | 50,90 % ❌ | 79,81 → 98,62 % | +18,81 pp ❌ |
| Karlsruhe | 0,03192 ✅ | 81,98 → 54,39 km | 33,65 % ❌ | 76,38 → 96,38 % | +20,00 pp ❌ |

Gemessen wird die Luftlinie vom Boxmittelpunkt zu Gemeinde- bzw. Siedlungspunkten, keine
simulierte Weglänge. Die BA-Referenz wurde nicht nachträglich auf 80 km gekappt: 6,09 % / 35,81 % /
22,86 % ihres jeweiligen Außenvergleichsgewichts liegen darüber. Der Kandidat enthält dort
konstruktionsbedingt keine Ziele. Diese Asymmetrie begrenzt die Interpretation und ist ein
Hindernis für den geprüften Ersatz der gesamten Außenverteilung.
Hinzu kommt die Länderabdeckung: OSM enthält auch französische und schweizerische Siedlungen,
während die eingefrorenen BA-Exporte hier deutsche Gemeindeziele ausweisen. Die Abweichungen
lassen sich deshalb nicht allein der Bevölkerungsgewichtung zuschreiben.

**Gegenprüfung und Grenzen:** Eine separate Python-Berechnung aus den exportierten
Gemeinderelationen und geparsten OSM-Siedlungen bestätigt alle sieben Java-Kennzahlen je Region
(`rel_tol=1e-10`, `abs_tol=1e-12`). Ein erneuter nativer Lauf erzeugt bytegleiche Kandidaten-JSONs.
Das ist eine Rechen- und Replay-Prüfung, kein Beleg für Nachfragewirkung. Die Richtungs-MAE über
acht Sektoren ist zudem ein schwaches Kriterium: Total Variation beträgt 0,39979 / 0,28863 /
0,12770, der größte Sektorfehler 21,40 / 19,16 / 8,75 Prozentpunkte. Alle acht Sektoranteile,
die größten zehn OSM-Gewichte und die 1.324 / 1.458 / 1.266 verwendeten Siedlungen sind im
lokalen Bericht nachvollziehbar. Bevölkerungsgewichte messen keine Arbeitsplätze; überlagerte
OSM-Stadt-/Ortsteilobjekte können dieselbe Bevölkerung mehrfach repräsentieren. Außerdem
beschreiben die Distanzkennzahlen die Siedlungsverteilung und nicht die späteren Entfernungen
zu acht verdichteten Sektorschwerpunkten.
Ein unabhängiger Reviewer (`osm_gate_review`) hat alle sieben Kennzahlen je Region erneut
nachgerechnet und die unveränderten initialen Quellen-/Präregistrierungshashes geprüft.
Er bestätigt die eingeschränkte Abbruchentscheidung und hebt die Abdeckungs-, Zuschnitts-,
Horizont- und Ländergrenzen hervor; das Review ist lokal in `review.md` gesichert.

**Konsequenz:** Keine Promotion und keine Änderung der produktiven Zielwahl. Die räumliche
Vorprüfung ist abgeschlossen; die geplanten Wirkungsläufe mit Seeds 42/43/44 je Region wurden
gemäß Abbruchregel **nicht gestartet**. Der Kandidat ist seedfrei und nur diagnostisch;
dreifaches Wiederholen wäre keine 3×3-Wirkungsvalidierung. Das Nachfragegate
`[3,0; 3,4] Wege/Person·Tag` bleibt ungeprüft. Ein neuer Ansatz braucht vorab einen passenden
räumlichen Bezugsraum, explizite Behandlung der Referenzlücken und Fernziele sowie begründete
Arbeitsplatzgewichte. Erst nach neuer Präregistrierung und bestandenem räumlichem Gate folgt
eine experimentelle Nachfrageintegration mit echten Mehrseed-Wirkungsläufen.

**Evidenz:** Lokal dauerhaft, aber gitignoriert unter
`backend/data/campaigns/osm-external-2026-09-09/`: Quellen/URLs in `sources.json`, Rohdaten in
`inputs/`, native Ergebnisse in `*-candidate.json`, vollständige Vergleichseingaben in
`*-details.json`, Auswertung in `report.json`, Replay in `replay.json` und SHA-256 in
`hashes.json`. Codebasis: `0d0517bce5ebe8ec4182f2f70cf8731783174556`.
SHA-256 der Präregistrierung:
`c08d1c428580e3f4e491850b12c6456d8e845c50530dd5c4b4bf7b79af75de79`.
Die Dateien sind nicht Bestandteil eines frischen Git-Checkouts. Mit vorhandenen lokalen
Eingaben und Backend-Klassen wiederholbar vom Repo-Root:

```bash
python3 backend/data/campaigns/osm-external-2026-09-09/run_probe.py evaluate
python3 backend/data/campaigns/osm-external-2026-09-09/analyze.py
```

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

### Mehrregionen-Transferprobe Freiburg (2026-08-17) — Stop-Gate verfehlt

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

**Eingabeprüfung bestanden:** T-C42 `01b40fea` und T-D42 `798bc6fc` verwendeten
denselben validierten OSM-Snapshot (24.117 Elemente, 23.944 POIs, 174 bevölkerte
Siedlungsknoten; SHA-256 `380c1060…5774b`) und denselben
`attractivities.csv`-Hash (`54f20a89…b4b13`). `diff -rq` blieb für Uploads,
Zonen, Population, Matrizen, Logistik und den vollständigen Legacy-Export leer.
Beide Arme erzeugten 1.024 interne 1-km-Zellen plus acht Außenbeziehungszonen,
2.000 Personen, 1.251 Haushalte und 56.996 Aktivitäten; `CAR_DIS` bildet
intrazonal 703,890 m ab. Der einzige Parameterunterschied im Simulationspfad ist
der protokollierte Term `+0,06` auf den fünf `b_tt_*`-Werten von T-D42.

| Vorab-Kriterium | T-C42 | T-D42 | |
|---|---:|---:|---|
| Distanzband-RMSE absolut `<=0,05` | 0,0611 | **0,0644** | ❌ |
| RMSE-Verbesserung `>=0,01` | – | **-0,0033** | ❌ |
| `>=10 km`: `>=20 %` und `>=+3 pp` | 10,16 % | **14,00 %** (+3,84 pp) | ❌ absolut |
| `<1 km` in 19–23 % | 21,40 % | **20,34 %** | ✅ |
| `1–2 km <=18 %` | 18,35 % | **11,90 %** | ✅ |
| Modal-RMSE-Verschlechterung `<=+0,02` | 0,099697 | **0,101357** (+0,001661) | ✅ |
| Wege/Person·Tag in [3,0; 3,4] | 3,07 | **3,07** | ✅ |

**Befund:** Die Mechanismusrichtung transferiert, die feste Dosis nicht. T-D42
verschiebt wie vorhergesagt Masse aus `1–2 km` nach `5–10 km` und `10–20 km`;
die mittlere Weglänge steigt **4,58→5,38 km** und die Nahkalibrierung stellt
`<1 km` in zwei Iterationen wieder her. Dennoch endet `>=10 km` bei nur 14,00 %,
der Distanzband-RMSE verschlechtert sich, und `>20 km` bewegt sich lediglich
**2,11→2,42 %** gegen MiD 13 %.

**Konsequenz nach Vorab-Regel:** Seeds 43/44 wurden nicht gestartet. Der auf
Rastatt geschätzte Term `+0,06` ist kein regionsunabhängiger Kalibrierwert;
`correctDistanceDecay` bleibt opt-in und der Default-Flip gesperrt. Ein nächster
Anlauf braucht eine neue Präregistrierung für eine regionale Elastizitäts-Sonde
beziehungsweise einen hierarchisch oder regional parametrisierten Hebel — keine
Wiederholung derselben universellen Dosis.

#### Freiburg-Grenzsonde (2026-08-17) — gemeinsamer Offset verworfen

**Frage vor dem ersten Lauf:** Kann eine stärkere gemeinsame Dosis das verfehlte
`>=10-km`-Gate erreichen, solange alle fünf resultierenden Reisezeitkoeffizienten
negativ bleiben? Auf dem eingefrorenen T-C42-Legacy-Export werden genau die Offsets
`+0,075` und `+0,09` untersucht; jeder Arm erhält danach dieselbe
`b_intrazonal`-Kalibrierung auf 21 % Nahwege. `+0,09` ist die vorab definierte
Obergrenze, weil der kleinste Betrag der unveränderten Koeffizientensumme bei
`b_tt_put=-0,0906` liegt. Größere gemeinsame Offsets würden mindestens einen
Reisezeitkoeffizienten nichtnegativ machen und damit die fachliche Bedeutung des
Nutzenterms umkehren.

**Vorhersage und Entscheidung:** `>=10 km` muss von `+0,06` über `+0,075` bis
`+0,09` monoton steigen. Erfüllt eine Dosis alle sieben oben festgelegten
Transferkriterien, wird die kleinste bestehende Dosis Kandidat für eine neue
Mehrseed-Prüfung. Bleibt selbst `+0,09` unter 20 % oder RMSE `>0,05`, ist der
gemeinsame sign-erhaltende Offset für Freiburg strukturell unzureichend; dann
werden weder größere Offsets noch weitere Seeds gestartet. Zulässiger Folgeschritt
ist nur ein anders parametrisierter beziehungsweise hierarchischer Hebel.

**Ergebnis:** Beide Grenzsonden liefen auf dem eingefrorenen T-C42-Legacy-Export
mit identischem Seed und identischem Legacy-Personen-Hash. Der temporäre Harness
nutzte die produktiven `SimulationRunner`- und `DistanceCalibrator`-Klassen und
wurde nach dem Lauf entfernt. Alle fünf resultierenden `b_tt_*`-Summen blieben
negativ; bei `+0,09` liegt `b_tt_put` mit `-0,0006` unmittelbar an der Grenze.

| Dosis | Ø km | `<1` | `1–2` | `5–10` | `10–20` | `>20` | `>=10` | Band-RMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `+0,075` | 5,66 | 20,94 % | 10,07 % | 27,34 % | 13,31 % | 2,64 % | **15,95 %** | 0,0657 |
| `+0,09` | 6,08 | 21,10 % | 8,29 % | 28,03 % | 15,62 % | 3,18 % | **18,80 %** | 0,0685 |

Die Vorhersage der monotonen Fernwegwirkung besteht zwischen den beiden exakt
gleich orchestrierten Grenzsonden. Beide liefern 42.987 Wege und 3,07
Wege/Person·Tag; der Modal-RMSE liegt bei 0,104378 beziehungsweise 0,109389.
Der separat über den Produktpfad orchestrierte `+0,06`-Arm lieferte 42.989 Wege;
deshalb stützt sich die Strukturentscheidung auf die absoluten Gates bei `+0,09`,
nicht auf ein exaktes Delta zwischen den Harness-Läufen.

**Entscheidung nach Vorab-Regel:** Selbst der höchste sign-erhaltende gemeinsame
Offset verfehlt `>=10 km >=20 %` und RMSE `<=0,05`; zugleich wird `1–2 km` mit
8,29 % weit unter das MiD-Ziel 14 % gedrückt. Größere gemeinsame Dosen würden
mindestens `b_tt_put` nichtnegativ machen und sind gesperrt. Der gemeinsame additive
Offset ist für Freiburg strukturell unzureichend. Der nächste Forschungshebel muss
die fünf Koeffizienten trennen oder regional/hierarchisch parametrisieren; weitere
Seeds werden nicht gestartet.

#### Freiburg-Split-Sonde (2026-08-17) — präregistriert

**Hypothese vor dem ersten Lauf:** Das verbleibende Fernwegdefizit lässt sich dem
motorisierten Individualverkehr zuordnen, ohne die bereits schwache ÖV-Zeitstrafe
weiter abzubauen. Auf dem eingefrorenen T-C42-Legacy-Export bleiben deshalb
`b_tt_put`, `b_tt_ped` und `b_tt_bike` auf dem produktiven Offset `+0,06`; nur
`b_tt_car_d` und `b_tt_car_p` erhalten höhere Gesamtoffsets. Alle Arme verwenden
Seed 42, denselben produktiven `SimulationRunner` und anschließend dieselbe
`b_intrazonal`-Kalibrierung auf 21 % Nahwege.

| Arm | `car_d`/`car_p` | `put`/`ped`/`bike` | resultierende Koeffizienten `car_d` / `car_p` / `put` |
|---|---:|---:|---:|
| PC-0060 (Harness-Kontrolle) | `+0,060` | `+0,060` | `-0,0588` / `-0,0881` / `-0,0306` |
| PC-0090 | `+0,090` | `+0,060` | `-0,0288` / `-0,0581` / `-0,0306` |
| PC-0115 | `+0,115` | `+0,060` | `-0,0038` / `-0,0331` / `-0,0306` |

`PC-0115` ist die vorab definierte Obergrenze: Ein größerer Pkw-Gesamtoffset
würde zuerst `b_tt_car_d=-0,1188` nichtnegativ machen. Gegenüber PC-0060 wird
`>=10 km` für PC-0090 bei mindestens 16 % und für PC-0115 bei mindestens 20 %
erwartet; die Wirkung muss mit der Dosis monoton steigen. Weil der ÖV-Koeffizient
unverändert bleibt, soll der globale ÖV-Anteil höchstens 26 % betragen. Diese
ÖV-Marke ist eine zusätzliche Mechanismusdiagnose; die sieben bereits festgelegten
Transferkriterien bleiben unverändert das Entscheidungs-Gate und werden jeweils
gegen PC-0060 ausgewertet.

**Ablauf- und Stop-Regel:** Zuerst laufen PC-0060 und PC-0090. Steigt `>=10 km`
nicht oder verschlechtert sich der Modal-RMSE um mehr als 0,02, ist die Split-
Hypothese widerlegt und PC-0115 wird nicht gestartet. Besteht PC-0090 alle sieben
Transferkriterien, ist er als kleinste Dosis Kandidat für eine separat
präregistrierte Mehrseed-Prüfung; PC-0115 entfällt. Andernfalls wird PC-0115 nur
freigegeben, wenn die Fernwegwirkung in der vorhergesagten Richtung liegt und die
Modal-Schutzschwelle hält. Verfehlt auch PC-0115 `>=10 km >=20 %` oder
Distanzband-RMSE `<=0,05`, ist auch die getrennte Pkw-Korrektur an ihrer
sign-erhaltenden Grenze verworfen; größere Dosen und weitere Seeds bleiben gesperrt.

**Ausführung und Isolation:** Der temporäre Harness lief mit den produktiven
`SimulationRunner`- und `DistanceCalibrator`-Klassen erfolgreich durch (1 Test,
EXIT 0, 110,9 s) und wurde danach entfernt. PC-0060 ist im vollständigen
`demandsimulationResult.csv` byte-identisch zu T-D42; alle drei Arme teilen den
Legacy-Personen-Hash `9dda440f…fca90`. Die Parameterdateien belegen ausschließlich
die präregistrierten Offsets, alle resultierenden Koeffizienten bleiben negativ.

| Kennzahl | PC-0060 | PC-0090 | PC-0115 |
|---|---:|---:|---:|
| Wege | 42.989 | 42.990 | 42.988 |
| Nahkalibrierung `<1 km` | 9,60→20,34 % (2) | 6,77→20,85 % (3) | 3,88→19,96 % (3) |
| Ø Weglänge | 5,38 km | 6,38 km | 8,60 km |
| `1–2 km` | 11,90 % | 7,89 % | 4,61 % |
| `10–20 km` | 11,58 % | 17,31 % | 23,97 % |
| `>20 km` | 2,42 % | 3,67 % | 9,88 % |
| `>=10 km` | 14,00 % | **20,98 %** | **33,85 %** |
| Distanzband-RMSE | 0,0644 | **0,0675** | **0,0729** |
| ÖV-Anteil | 24,86 % | **29,29 %** | **37,83 %** |
| Modal-RMSE | 0,101357 | 0,115920 | 0,154486 |
| Wege/Person·Tag | 3,07 | 3,07 | 3,07 |

PC-0090 bestätigt die Richtungsprognose und überschreitet erstmals das absolute
Fernweg-Gate; auch der formale Modal-RMSE-Schutz hält mit Δ `+0,014563`. Beide
Distanz-RMSE-Gates scheitern jedoch (`-0,0031` statt Verbesserung), und die
zusätzliche ÖV-Diagnose `<=26 %` wird verfehlt. Damit bestand PC-0090 nicht alle
sieben Transferkriterien und PC-0115 war gemäß Fortsetzungsregel zulässig. An der
Obergrenze wächst das Fernwegmaß weiter monoton, zugleich scheitern Distanz-RMSE,
Modal-Schutz (Δ `+0,053129`) und ÖV-Diagnose noch deutlicher.

**Befund und Mechanismus:** Die getrennte Pkw-Korrektur kann Fernziele erzeugen,
aber die realisierten Modi nicht isoliert steuern. Obwohl `b_tt_put` in allen drei
Armen unverändert bei `-0,0306` bleibt, wählt die Zielwahl weiter entfernte Ziele;
die nachgelagerte Moduswahl bedient diese Wege überproportional mit ÖV. Gleichzeitig
wird Masse aus `1–2 km` bis auf 4,61 % verdrängt, sodass der Sechs-Band-RMSE trotz
mehr Fernwegen steigt. Die Hypothese „Pkw-Fernwege ohne ÖV-Schaden“ ist widerlegt.

**Entscheidung nach Vorab-Regel:** Keine weiteren Seeds und keine größeren Pkw-
Offsets. Auch ein bloßes Scannen anderer Modus-Teilmengen ist ohne neuen Mechanismus
gesperrt. Ein nächster Anlauf muss die Verteilungsform explizit mehrdimensional
kontrollieren: präregistrierte Elastizität von `b_1_2` auf Basis von PC-0090 zur
Wiederherstellung des 1–2-km-Bands, kombiniert mit einem separaten Modal-Schutz;
alternativ eine regional/hierarchisch geschätzte Parametrisierung.

#### Freiburg-Multi-Band-Sonde (2026-08-17) — präregistriert

**Hypothese vor dem ersten Lauf:** PC-0090 erzeugt mit `>=10 km` 20,98 % bereits
genügend Fernwege; sein RMSE-Problem liegt unter anderem im zu schwachen Band
`1–2 km` (7,89 statt MiD 14 %). Ein additiver Bonus auf `b_1_2` in allen vier
Zweckdateien soll dieses Band gezielt auffüllen, ohne die Fernwegwirkung des
Pkw-Splits aufzugeben. `b_tt_*` und alle übrigen Parameter bleiben auf PC-0090;
nach jeder Sonde stellt dieselbe `b_intrazonal`-Kalibrierung das `<1 km`-Band
auf 21 % zurück.

| Arm | zusätzlicher Term auf `b_1_2` | erwartetes `1–2 km` |
|---|---:|---:|
| MB-000 (Harness-Kontrolle) | `+0,00` | Reproduktion PC-0090: ca. 7,9 % |
| MB-050 | `+0,50` | 12–14 % |
| MB-075 | `+0,75` | 14–17 % |

Die Dosen sind nicht nachträglich gesucht: Unter der Logit-Näherung
`p' = p·exp(Δ)/(1-p+p·exp(Δ))` verschieben `+0,50/+0,75` den Ausgangsanteil
7,89 % rechnerisch auf 12,4/15,3 %. Erwartet wird außerdem `>=10 km >=20 %`
und ein sinkender ÖV-Anteil bis höchstens 26 %, weil mehr Wegemasse in ein kurzes
Band zurückkehrt. Die sieben Transferkriterien gelten weiter gegen PC-0060;
das bisher einseitige `1–2 km <=18 %` wird für diese Formsonde auf **12–16 %**
verschärft. Die ÖV-Marke `<=26 %` ist ein zusätzliches Mechanismus-Gate.

**Ablauf- und Stop-Regel:** MB-000 muss PC-0090 byteidentisch reproduzieren,
danach läuft MB-050. Steigt `1–2 km` nicht, fällt `>=10 km` unter 20 % oder
verschlechtert sich der Modal-RMSE gegenüber MB-000 um mehr als 0,02, wird
MB-075 nicht gestartet. Besteht MB-050 alle verschärften Kriterien, ist er die
kleinste Kandidatendosis und MB-075 entfällt. Andernfalls darf MB-075 nur nach
bestandener Fortsetzungsregel laufen. Verfehlt auch MB-075 Distanzband-RMSE
`<=0,05`, `1–2 km` in 12–16 % oder ÖV `<=26 %`, ist ein gemeinsamer additiver
`b_1_2`-Bonus auf diesem Parameterstand verworfen; weitere Dosen und Seeds sind
ohne neue Hypothese gesperrt.

**Ergebnis:** Der temporäre Produktions-Harness bestand (1 Test, EXIT 0, 78,12 s)
und wurde danach entfernt. MB-000 reproduziert PC-0090 byteidentisch. Trotz des
nachweislich in allen vier Zweckdateien vorhandenen Terms `b_1_2 +0,50` ist auch
MB-050 im vollständigen `demandsimulationResult.csv` byteidentisch zu MB-000:
42.990 Wege, `1–2 km` 7,89 %, `>=10 km` 20,98 %, Band-RMSE 0,0675,
ÖV 29,29 % und Modal-RMSE 0,115920. Die erwartete Richtungswirkung bleibt exakt
null; MB-075 wurde gemäß Stop-Regel nicht gestartet.

**Root Cause im gepinnten Legacy-Vertrag:** Die generierte
`DestinationChoiceUtilityFunction` verwendet tatsächlich
`b_1_2 * [1 < DISTANCE <= 2]` (analog `b_0_1` für 0–1). Der
`DefaultDestinationChoiceHelper.getDISTANCE` gibt jedoch
`ImpedanceIfc.getDistance(...)` unverändert zurück, während die Run-Config dafür
`other/CAR_DIS.mtx` bindet und dieser Vertrag Distanzen in **Metern** hält. Der
kleinste der 1.065.024 Freiburg-Matrixwerte beträgt 411,918 m; kein Wert kann
also die Schwellen 1 oder 2 erfüllen. Dass andere Legacy-Stellen für Kilometer
explizit durch 1.000 teilen (`ElectricCarOwnership.distance`), bestätigt die
Einheitenforensik unabhängig. `b_0_1` und `b_1_2` sind im verwendeten Modellpfad
damit faktisch tote Parameter.

**Entscheidung nach Vorab-Regel:** Die Multi-Band-Sonde ist nicht negativ im Sinn
einer zu kleinen Dosis, sondern wegen einer bewiesenen Einheitenfehlverdrahtung
blockiert; weitere Dosen oder Seeds wären informationslos. `validation/` wird
nicht direkt gepatcht. Ein weiterer Anlauf braucht zuerst eine versionierte,
experimentell aktivierbare Korrektur des externen Harness-Vertrags
(`DISTANCE / 1000`) und muss danach die bisherigen Zielwahl-Baselines neu
präregistrieren, weil dadurch vorhandene `b_0_1`-/`b_1_2`-Terme erstmals wirksam
werden. Bis dahin darf Multi-Band-Kalibrierung nicht als verfügbar gelten.

#### Freiburg-Kilometer-Vertragsspike (2026-08-17) — präregistriert

**Binäre Frage vor dem ersten Lauf:** Aktiviert ausschließlich
`DefaultDestinationChoiceHelper.getDISTANCE := ImpedanceDistance / 1000` die
vorhandenen Kilometerband-Terme über die vollständige logiTopp-Kette? Dafür wird
der installierte Harness in die persistente, gitignorierte Kampagnenablage kopiert;
`validation/` bleibt unverändert. Die Kopie unterscheidet sich im Modellcode nur
in dieser Einheitenkorrektur und behält denselben Vertragsmarker, Build und
eingefrorenen PC-0090-Legacy-Export.

| Arm | korrigierter Helper | zusätzlicher `b_1_2`-Term |
|---|---:|---:|
| KM-000 | `DISTANCE / 1000` | `+0,00` |
| KM-050 | `DISTANCE / 1000` | `+0,50` |

**Vorhersage:** KM-000 darf wegen der erstmals aktiven bestehenden
`b_0_1`-/`b_1_2`-Terme nicht mehr byteidentisch zu PC-0090 sein. KM-050 muss
gegenüber KM-000 das `1–2 km`-Band um mindestens 3 Prozentpunkte erhöhen und ein
nicht byteidentisches Demand-CSV erzeugen. `<1 km` wird in beiden Armen wieder
auf 19–23 % kalibriert; alle übrigen Distanz-, Modal- und Wegekennzahlen werden
berichtet, sind in diesem Mechanikspike aber kein nachträglich veränderbares
Promotions-Gate.

**Stop-Regel:** Bleibt KM-000 byteidentisch zu PC-0090 oder steigt `1–2 km` in
KM-050 um weniger als 3 Prozentpunkte, ist die Einheitenkorrektur als Erklärung
widerlegt beziehungsweise unzureichend; keine Produktimplementierung. Bestehen
beide Binär-Gates, ist nur die Mechanik bestätigt. Dann darf ein separater
opt-in Harness-Vertrag geplant werden; Default-Änderung, weitere Dosen und Seeds
bleiben bis zu neuer Baseline- und Schutzgate-Präregistrierung gesperrt.

**Ausführung:** Die isolierte Harness-Kopie unterscheidet sich im relevanten
Quellpfad durch genau `getDistance(...) / 1000.0d`; ihr Helper-SHA-256 ist
`31f23b3c…118c7a` gegenüber `e2ed67f4…c24476d6` im unveränderten
`validation/`-Harness. Der temporäre Produktions-Harness bestand (1 Test,
EXIT 0, 78,13 s) und wurde danach entfernt. Beide Arme teilen den eingefrorenen
Legacy-Personen-Hash `9dda440f…fca90`; kein Run-Log enthält Fallback oder Exception.

| Kennzahl | PC-0090 (Metervertrag) | KM-000 | KM-050 |
|---|---:|---:|---:|
| Wege | 42.990 | 42.989 | 42.988 |
| `<1 km` nach Kalibrierung | 20,85 % | 20,78 % | 20,92 % |
| `1–2 km` | 7,89 % | **9,67 %** | **12,09 %** |
| `5–10 km` | 27,43 % | 27,01 % | 25,82 % |
| `>=10 km` | 20,98 % | 20,68 % | 20,19 % |
| Ø Weglänge | 6,38 km | 6,31 km | 6,16 km |
| Distanzband-RMSE | 0,0675 | **0,0636** | **0,0581** |
| ÖV-Anteil | 29,29 % | 29,05 % | 28,21 % |
| Modal-RMSE | 0,115920 | 0,115971 | 0,114158 |
| Wege/Person·Tag | 3,07 | 3,07 | 3,07 |

**Urteil gegen die Vorab-Gates:** Die Einheiten-Root-Cause ist mechanisch bestätigt:
KM-000 ist nicht byteidentisch zu PC-0090, vorhandene Kurzband-Terme werden wirksam,
und KM-050 unterscheidet sich wiederum byteweise von KM-000. Der vorhergesagte
Elastizitäts-Gate scheitert jedoch: `1–2 km` steigt nur um **2,42 pp** statt
mindestens 3 pp. Die Wirkung verbessert RMSE und ÖV-Anteil in der erwarteten
Richtung, reicht aber weder für das absolute RMSE-Gate 0,05 noch für die
ÖV-Diagnose 26 %.

**Entscheidung nach Stop-Regel:** Keine `+0,75`-Dosis, keine Seeds und keine
Produktimplementierung des Kilometervertrags. Die isolierte Harness-Kopie und
Ergebnisse bleiben als Kampagnenevidenz erhalten; der produktive und der
`validation/`-Vertrag sind unverändert. Ein neuer Anlauf müsste die nun gemessene
statt angenommene Elastizität in einer unabhängigen Präregistrierung verwenden
und zugleich alle Distanz- und Modal-Schutzgates adressieren; dieser Spike allein
rechtfertigt weder ein neues Szenario-Flag noch einen Default- oder Runtime-Wechsel.

#### Freiburg-Joint-Kompositionssonde (2026-08-17) — präregistriert

**Hypothese und Dosis vor dem Lauf:** Die korrigierte KM-000-Baseline enthält
9,67 % Wege im `1–2 km`-Band; `b_1_2 +0,50` erhöhte es auf 12,09 %. Die lokal
gemessene Elastizität beträgt damit `(0,1209−0,0967)/0,50 = 0,0484` je
Koeffizienteneinheit. Für das MiD-Ziel 14 % folgt ohne Raster oder Nachjustierung
`(0,14−0,0967)/0,0484 = 0,8946`, vorab gerundet auf **`b_1_2 +0,895`**.

Der einmalige Arm **J-0895** verwendet weiterhin den eingefrorenen
Freiburg-Seed-42-Legacy-Export, die isolierte Helper-Korrektur
`DISTANCE / 1000`, `b_tt_car_d/car_p +0,09`, alle übrigen `b_tt_* +0,06` und
die produktive `<1 km`-Kalibrierung auf 21 %. Es wird kein weiterer
Zielwahlparameter verändert. Danach kalibriert der produktive
`ModeChoiceCalibrator` ausschließlich die MNL-ASCs gegen
Fuß/Rad/Pkw/Mitfahrer/ÖV = 22/11/43/14/10 %, mit unveränderten Defaults von
höchstens acht Iterationen und RMSE-Toleranz 0,02. **J-RAW** bezeichnet den
Stand mit Basis-MNL, **J-MOD** denselben Zielwahlstand nach diesem Modalschutz.

**Vorab-Gates — alle müssen bestehen:**

1. Eingaben tragen weiter den Legacy-Personen-Hash `9dda440f…fca90`, der
   Helper den SHA-256 `31f23b3c…118c7a`; kein Fallback und keine Exception.
2. Der erste Basis-MNL-Lauf des Modalkalibrators reproduziert J-RAW bytegleich.
3. J-RAW und J-MOD halten `<1 km` in `[19; 23] %`, `1–2 km` in `[12; 16] %`,
   `>=10 km` bei mindestens 20 % und Wege/Person·Tag in `[3,0; 3,4]`.
4. Der Distanzband-RMSE liegt in beiden Ständen bei höchstens 0,05.
5. J-MOD senkt den Modal-RMSE gegenüber J-RAW um mindestens 0,02 auf höchstens
   0,08; sein ÖV-Anteil liegt gemäß Kalibrator-Toleranz in `[8; 12] %`.

**Stop-Regel:** J-RAW und J-MOD werden genau einmal ausgeführt; ein verfehltes
Gate wird weder durch eine zweite `b_1_2`-Dosis noch durch geänderte Schwellen
repariert. Bei mindestens einem Fehlschlag: keine Seeds, kein Produktvertrag und
kein neues Szenario-Flag. Bestehen alle Gates, ist nur die Komposition auf dem
eingefrorenen Seed 42 bestätigt; Seeds 43/44 und eine zweite Region benötigen
eine neue Präregistrierung. `validation/` bleibt in jedem Fall unverändert.

**Ausführung:** Ein erster technischer Aufruf endete nach 0,15 s vor der
Simulation, weil die Sandbox keine Gradle-Sperrdatei unter `~/.gradle` anlegen
konnte; er ist getrennt als ungültiger Umgebungsfehlanlauf erhalten. Der
unveränderte Retry bestand den vollständigen externen Harness mit 1 Test,
EXIT 0 und 158,1 s. Die Nahkalibrierung lief 6,61→14,93→21,38 % in drei
Iterationen, der Modalkalibrator nutzte die vorab erlaubten acht Iterationen.
Sein erster Basis-MNL-Lauf reproduzierte J-RAW byteidentisch; alle elf Run-Logs
sind frei von Fallback und Exception, Eingabe- und Helper-Hashes stimmen.

| Kennzahl | J-RAW | J-MOD | Vorab-Gate |
|---|---:|---:|---:|
| Wege | 42.989 | 42.988 | stabil |
| Wege/Person·Tag | 3,0706 | 3,0706 | ✅ `[3,0; 3,4]` |
| `<1 km` | 21,38 % | 21,43 % | ✅ `[19; 23] %` |
| `1–2 km` | **14,12 %** | **14,08 %** | ✅ `[12; 16] %` |
| `2–5 km` | 19,96 % | 19,94 % | berichtet |
| `5–10 km` | 24,85 % | 24,86 % | berichtet |
| `10–20 km` | 15,96 % | 15,98 % | berichtet |
| `>20 km` | 3,73 % | 3,70 % | MiD 13 % |
| `>=10 km` | **19,69 %** | **19,68 %** | ❌ mindestens 20 % |
| Ø Weglänge | 6,024 km | 6,019 km | berichtet |
| Distanzband-RMSE | **0,055140** | **0,055292** | ❌ höchstens 0,05 |
| ÖV-Anteil | 27,42 % | **10,70 %** | ✅ J-MOD `[8; 12] %` |
| Modal-RMSE | 0,113275 | **0,017562** | ✅ Δ −0,095713 und ≤0,08 |

**Urteil:** Die aus einer einzigen Elastizitätsmessung abgeleitete Dosis trifft
das MiD-Kurzband ohne Raster praktisch exakt, und der bestehende ASC-Regler
schützt den Modal-Split weit unter dem Vorab-Gate. Er verändert die
Distanzmarginale dabei nur minimal. Genau deshalb bleiben die strukturellen
Fehler `5–10 km` zu hoch und `>20 km` zu niedrig; Fernweg- und absoluter
Distanz-RMSE-Gate scheitern in beiden Ständen.

**Entscheidung nach Stop-Regel:** Keine weitere `b_1_2`-Dosis, keine Seeds und
keine Produktimplementierung. Der Kilometervertrag ist damit als notwendige
Kurzbandkorrektur erklärt, aber nicht als hinreichender Produktpfad befördert.
Ein neuer Anlauf muss einen eigenständigen Mechanismus für das `>20 km`-Band
präregistrieren, statt Kurzband- oder Modalparameter weiter zu scannen; Runtime,
Defaults und `validation/` bleiben unverändert.

#### Freiburg-Langweg-Nutzenbonusspike (2026-08-17) — präregistriert

**Forensik und binäre Frage vor dem Lauf:** Der verwendete generierte
`DestinationChoiceUtilityFunction` enthält direkte Distanzdummies nur für
`0–1 km` und `1–2 km`; ein unabhängiger Langwegterm fehlt. Die bisherigen
`b_tt_*`-Hebel verändern dagegen gleichzeitig Bandform und Moduserreichbarkeit
(E25–E28). Kann ein konstanter Nutzenbonus für jede Zielalternative mit
`DISTANCE > 20 km` das fehlende MiD-Langband über die vollständige
logiTopp-Kette erzeugen, ohne die bereits getroffenen Kurzband-, Distanz- und
Modalgates zu zerstören?

Der einmalige Arm **LT-135** verwendet den eingefrorenen J-0895-Stand mit
Seed 42, Helper `DISTANCE / 1000`, den bereits kalibrierten Zielwahlparametern
und unverändertem Basis-MNL. Ein isolierter, gitignorierter Harness-Klon ergänzt
im Zielwahlnutzen ausschließlich
`1,35 * [DISTANCE > 20]`; `validation/` und Produktcode bleiben unverändert.
Die Dosis ist kein Rasterwert, sondern folgt vorab aus den J-RAW-Log-Odds:
`logit(0,13) − logit(0,0373119) = 1,34946`, gerundet auf **`+1,35`**.
Unter einer reinen MNL-Umschichtung sagt dies `>20 km ≈ 13,01 %`,
`<1 km ≈ 19,32 %`, `1–2 km ≈ 12,76 %`, `>=10 km ≈ 27,42 %` und
Distanzband-RMSE ≈ 0,0348 voraus; feste Ziele und nachgelagerte Modellschritte
können den Istwert abweichen lassen.

**Mechanik-Gates vor Modalschutz — alle müssen bestehen:**

1. LT-135 nutzt weiter den Legacy-Personen-Hash `9dda440f…fca90`, dieselben
   fünf Zielwahlparameterdateien wie J-RAW und einen dokumentierten
   Harness-Quellhash; kein Fallback und keine Exception.
2. Das Demand-CSV unterscheidet sich von J-RAW; `>20 km` steigt um mindestens
   5 Prozentpunkte und liegt absolut in `[10; 16] %`.
3. `<1 km` bleibt in `[19; 23] %`, `1–2 km` in `[12; 16] %`, `>=10 km`
   bei mindestens 20 %, Distanzband-RMSE bei höchstens 0,05 und
   Wege/Person·Tag in `[3,0; 3,4]`.

Nur wenn alle drei Mechanik-Gates bestehen, wird auf demselben LT-135-Stand
einmal der unveränderte produktive `ModeChoiceCalibrator` gegen
Fuß/Rad/Pkw/Mitfahrer/ÖV = 22/11/43/14/10 % ausgeführt (höchstens acht
Iterationen, Toleranz 0,02). Sein erster Basis-MNL-Lauf muss LT-135 bytegleich
reproduzieren. **Schutzgates:** Der Endstand hält sämtliche Distanzgates aus
Punkt 3 einschließlich `>20 km in [10; 16] %`; Modal-RMSE sinkt gegenüber
LT-135 um mindestens 0,02 auf höchstens 0,08, und ÖV liegt in `[8; 12] %`.

**Stop-Regel:** Genau eine Dosis und ein Seed. Scheitert ein Mechanik-Gate,
entfällt die Modalkalibrierung. Scheitert danach ein Schutzgate, gibt es weder
eine zweite Bonusdosis noch neue Schwellen. Auch bei vollständigem Erfolg ist
nur der externe Mechanismus auf einem eingefrorenen Freiburg-Seed bestätigt;
ein versionierter opt-in Produktvertrag benötigt eine neue Präregistrierung mit
Seeds 43/44, zweiter Region und zugewiesenem Widerlegungsversuch.

**Ausführung:** Der isolierte Quellpatch unterscheidet sich von der korrigierten
Kilometer-Runtime ausschließlich durch die Konstante `LONG_DISTANCE_BONUS = 1.35`
und deren Dummy `[DISTANCE > 20]`; Utility-SHA-256
`842abe1f…4009e`, Helper weiter `31f23b3c…118c7a`. Die fünf bereits
kalibrierten J-0895-Zielwahlparameter und der Personen-Hash
`9dda440f…fca90` sind unverändert. Zwei Compile-Aufrufe waren vor dem Modelllauf
wegen Sandbox-Zugriff auf `~/.gradle` beziehungsweise fehlender expliziter
JDK-25-Übergabe unentscheidbar; derselbe Harness kompilierte danach mit dem
bestehenden Toolchain-Vertrag erfolgreich in 948 ms. Der externe logiTopp-Lauf
bestand mit EXIT 0 in 13 s; sein Run-Log enthält weder Fallback noch Exception.
Der temporäre JUnit-Harness meldete anschließend erwartungsgemäß einen roten
Mechanik-Gate und wurde entfernt.

| Kennzahl | J-RAW | LT-135 | Vorab-Gate |
|---|---:|---:|---:|
| Wege | 42.989 | 42.988 | stabil |
| Wege/Person·Tag | 3,0706 | 3,0706 | ✅ `[3,0; 3,4]` |
| `<1 km` | 21,38 % | **20,75 %** | ✅ `[19; 23] %` |
| `1–2 km` | 14,12 % | **13,94 %** | ✅ `[12; 16] %` |
| `2–5 km` | 19,96 % | 19,52 % | berichtet |
| `5–10 km` | 24,85 % | 23,72 % | berichtet |
| `10–20 km` | 15,96 % | 14,89 % | berichtet |
| `>20 km` | **3,73 %** | **7,17 %** | ❌ Δ +3,44 pp statt ≥5 pp; ❌ `[10; 16] %` |
| `>=10 km` | 19,69 % | **22,06 %** | ✅ mindestens 20 % |
| Ø Weglänge | 6,024 km | **6,600 km** | berichtet |
| Distanzband-RMSE | 0,055140 | **0,042661** | ✅ höchstens 0,05 |
| ÖV-Anteil | 27,42 % | 29,47 % | Diagnose vor Schutz |
| Modal-RMSE | 0,113275 | 0,122338 | Diagnose vor Schutz |

**Urteil gegen die Vorab-Regel:** Der direkte Bonus wirkt mechanisch und besteht
alle allgemeinen Distanz-Schutzgates, erreicht aber nur rund 37 % des naiv
vorhergesagten Langband-Zuwachses (3,44 statt 9,27 pp). Die Log-Odds-Herleitung
über alle End-to-End-Wege war als Dosismodell falsch: Der externe
`FixedLocationDestinationChoice` gibt HOME-Ziele und personengebundene Fixziele
zurück, bevor `DestinationChoiceUtilityFunction` aufgerufen wird. Der Bonus kann
also nur den frei gewählten Teil der Wege umschichten; sein Effekt wird in der
Gesamtmarginale verdünnt.

**Entscheidung nach Stop-Regel:** Kein Modalkalibrator, keine zweite Bonusdosis,
keine Seeds und kein Produktvertrag. Der vollständige Rohstand bleibt unter
`backend/data/campaigns/distance-decay-transfer-freiburg/long-tail-utility-spike/`
erhalten. Ein neuer Versuch muss zunächst den Anteil frei gewählter
Zielentscheidungen explizit messen und einen daran bedingten Langwegmechanismus
präregistrieren; eine bloße Nachskalierung von `+1,35` wäre ein verbotener Retry.

#### Freiburg-Freiziel-Instrumentierung (2026-08-17) — präregistriert

**Hypothese und binäre Frage vor dem Lauf:** LT-135 erreichte mit +3,4429 pp nur
`0,37105` des unter einer Gesamtmengen-Umschichtung vorhergesagten Zuwachses
**+9,2788 pp**. Erklärt der Anteil der Zielentscheidungen, die
`DestinationChoiceUtilityFunction` tatsächlich erreichen, diese Unterwirkung?
Wenn der HOME-/Fixziel-Bypass der dominante Verdünnungsmechanismus ist, muss der
gemessene Freizielanteil vorab in **`[30; 45] %`** liegen und damit das
beobachtete Wirkungsverhältnis 37,1 % einschließen.

Der einmalige Instrumentierungsarm **FD-000** verwendet weiter den eingefrorenen
J-0895-Stand mit Seed 42, Legacy-Personen-Hash `9dda440f…fca90`, Helper
`DISTANCE / 1000`, denselben fünf Zielwahlparameterdateien und unverändertem
Basis-MNL. Er enthält **keinen** Langwegbonus. Eine isolierte, gitignorierte
Kopie der korrigierten Kilometer-Runtime ergänzt ausschließlich threadsichere
Zähler in `FixedLocationDestinationChoice`: `HOME`, personengebundenes
`FIXED`, freies Ziel über ein zweckspezifisches Modell (`FREE_MAPPED`) und
freies Ziel über das Default-Modell (`FREE_DEFAULT`), jeweils zusätzlich nach
Aktivitätstyp. Genau eine Abschlusszeile mit dem Präfix
`SYNTHESIS_DESTINATION_CHOICE_PATHS` macht die Zählung maschinenlesbar;
Produktcode und `validation/` bleiben unverändert.

**Gates in fester Reihenfolge:**

1. Der externe logiTopp-Lauf endet mit EXIT 0; Personen-, Helper- und
   Zielwahlparameter-Hashes stimmen mit J-RAW überein, Run-Log ohne Fallback
   und Exception.
2. Die Instrumentierung ist verhaltensneutral: Das erzeugte Demand-CSV ist
   byteidentisch zu J-RAW, SHA-256
   `a58fac01c5f1a2623c3cda600842de68749e1b5f2bfd3cb17c345398f6c7a9de`.
3. Genau eine Abschlusszeile ist parsebar; ihre Bilanz erfüllt exakt
   `TOTAL = HOME + FIXED + FREE_MAPPED + FREE_DEFAULT`. `TOTAL`, der
   HOME-/Fixziel-Bypass und der Freizielpfad sind jeweils größer null. Der
   Freizielanteil ist
   `(FREE_MAPPED + FREE_DEFAULT) / TOTAL`; Aktivitätstypen und das Verhältnis
   `TOTAL / 42.989 J-RAW-Wege` werden nur diagnostisch berichtet.
4. Das Erklärungs-Gate besteht genau dann, wenn der Freizielanteil in
   `[30; 45] %` liegt. Ein Wert außerhalb des Fensters widerlegt die Erklärung,
   dass die LT-135-Unterwirkung allein hinreichend durch HOME-/Fixziel-Bypässe
   verdünnt wurde.

**Stop-Regel:** Genau ein Instrumentierungslauf, keine Bonusdosis, keine
Modalkalibrierung und keine neuen Seeds. Scheitert eines der technischen Gates
1–3, ist der Lauf unentscheidbar und die Erklärung wird nicht bewertet.
Bestehen sie und Gate 4 scheitert, wird der direkte Langwegbonus-Pfad gestoppt
und zunächst eine andere Ursache gesucht. Besteht Gate 4, ist nur die
Verdünnungserklärung auf diesem Seed gestützt; jede bedingte Dosis oder
Produktänderung braucht eine eigene Präregistrierung mit direkter Verknüpfung
zwischen Entscheidungsweg und gewählter Distanz, Seeds 43/44, zweiter Region
und zugewiesenem Widerlegungsversuch.

**Ausführung:** Der isolierte FD-000-Harness kompilierte mit dem bestehenden
JDK-25-Vertrag in 3 s und bestand den externen Lauf mit EXIT 0
(`BUILD SUCCESSFUL in 16s`, logiTopp rund 13 s). Personen-, Utility-, Helper-,
Basis-MNL- und alle fünf Zielwahlparameter-Hashes entsprechen dem eingefrorenen
bonusfreien Stand. Genau eine Abschlusszeile ist parsebar; das Log enthält
weder Fallback noch Exception. Das erzeugte Demand-CSV ist mit SHA-256
`a58fac01…c7a9de` byteidentisch zu J-RAW.

| Entscheidungsweg | Anzahl | Anteil an 42.989 |
|---|---:|---:|
| HOME | 18.722 | 43,55 % |
| FIXED | 5.129 | 11,93 % |
| FREE_MAPPED | 19.138 | **44,52 %** |
| FREE_DEFAULT | 0 | 0,00 % |
| **Bilanz** | **42.989** | **100,00 %** |

Die FIXED-Ziele zerfallen in WORK 3.870, EDUCATION_PRIMARY 536,
EDUCATION_SECONDARY 415 und EDUCATION_TERTIARY 308. Die freien Entscheidungen
entfallen auf SERVICE 3.445, PRIVATE_VISIT 2.594, SHOPPING_DAILY 6.726,
SHOPPING_OTHER 1.229, LEISURE_INDOOR 2.857 und LEISURE_OUTDOOR 2.287. Damit
entspricht die Zählung exakt einer Zielentscheidung je J-RAW-Weg.

**Urteil gegen die Vorab-Regel:** Alle technischen Gates und das feste
Erklärungsfenster `[30;45] %` bestehen. Der Freizielanteil 44,52 % stützt den
HOME-/Fixziel-Bypass als wesentlichen Verdünnungsmechanismus, liegt jedoch
7,41 Prozentpunkte über dem beobachteten LT-135-Wirkungsverhältnis 37,11 %.
Die Beteiligungsquote allein ist deshalb keine hinreichende Dosisformel.

**Entscheidung nach Stop-Regel:** Kein Bonus-Retry, keine Modalkalibrierung,
Seeds oder Produktänderung. Der Rohstand bleibt unter
`backend/data/campaigns/distance-decay-transfer-freiburg/free-destination-instrumentation/`
erhalten. Der nächste zulässige Spike muss verhaltensneutral den jeweiligen
Entscheidungsweg direkt mit der tatsächlich gewählten Distanz verknüpfen; erst
danach darf ein bedingter Langwegmechanismus neu präregistriert werden.

#### Freiburg-Wegeexpositionsanalyse (2026-08-17) — präregistriert

**Hypothese und binäre Frage vor der Auswertung:** Eine freie Zielwahl verändert
nicht nur den Weg **zur** gewählten Aktivität, sondern auch den anschließenden
Weg **von** diesem Ort, häufig zurück nach HOME. Lokalisiert die direkte
Nachbarschaft zu einer freien Aktivität den LT-135-Langwegeffekt vollständig?
Wenn ja, müssen mindestens 99 % der zusätzlichen `>20 km`-Wege in der
exponierten Gruppe liegen, während die nicht exponierte Gruppe im Langband um
höchstens 0,1 Prozentpunkte driftet.

Die einmalige, read-only Analyse **PX-135** paart die bereits erzeugten
J-RAW- und LT-135-Demand-CSVs über `(tripId, legId)`; es gibt keinen neuen
Simulationslauf. Der aus FD-000 bestätigte Zielpfad wird ausschließlich aus den
Legacy-Aktivitätscodes rekonstruiert: HOME = `7`, FIXED =
`1/31/32/33`, FREE = `6/12/41/42/51/52`. Ein Weg ist **direkt exponiert**, wenn
sein `activityType` FREE ist, **folgeexponiert**, wenn sein
`previousActivityType` FREE ist, und insgesamt exponiert, wenn mindestens eine
der beiden Bedingungen gilt. Alle Kennzahlen verwenden nur `legId == 0`.

**Technische Gates vor Interpretation:**

1. Beide Dateien haben eindeutige `(tripId, legId)`-Schlüssel; mindestens
   99,9 % der J-RAW-Wege lassen sich paaren. Aktivitäts- und
   Vorgängeraktivitätscode sind in mindestens 99,9 % der Paare identisch.
2. Die J-RAW-Zielklassifikation reproduziert exakt die FD-000-Bilanz:
   HOME 18.722, FIXED 5.129, FREE 19.138, gesamt 42.989.
3. Die Analyse verändert keine Eingabe; J-RAW und LT-135 behalten ihre
   dokumentierten SHA-256-Hashes. Das Skript schreibt nur eine neue
   `summary.json` in den gitignorierten Kampagnenordner.

**Mechanik-Gates — alle vorab fixiert:**

1. Der Anteil exponierter J-RAW-Wege liegt in `[65;85] %`; Anker ist die
   unabhängige Näherung `1-(1-0,4452)^2 = 69,2 %` für direkte oder
   Folgeexposition.
2. Mindestens 99 % des positiven J-RAW→LT-135-Deltas in der Anzahl
   `>20 km`-Wege entfällt auf exponierte Wege. Für nicht exponierte Wege liegt
   die absolute Änderung des `>20 km`-Anteils bei höchstens 0,1 pp.
3. Innerhalb der exponierten Gruppe steigt der `>20 km`-Anteil um mindestens
   5 pp. Direkt- und Folgeexposition sowie Überschneidungen werden getrennt
   berichtet; für diese Untergruppen gelten keine nachträglichen Gates.

**Stop-Regel:** Genau eine Auswertung der vorhandenen zwei CSVs, keine neue
Dosis, Simulation, Modalkalibrierung oder Seed-Variation. Scheitert ein
technisches Gate, ist PX-135 unentscheidbar. Scheitert ein Mechanik-Gate, ist
die einfache Nachbarschaftserklärung widerlegt und der direkte Bonuspfad bleibt
gestoppt. Bestehen alle Gates, ist nur die räumliche Wirkungskette auf diesem
Seed lokalisiert; eine konditionierte Dosis braucht anschließend eine eigene
Präregistrierung und darf die hier gemessenen Untergruppen nicht nachträglich
neu zuschneiden.

**Ausführung:** Die einmalige read-only Auswertung verwendete unverändert die
fixierten J-RAW-/LT-135-Hashes `a58fac01…c7a9de` und
`b13e675e…e6bb57`. Beide Dateien haben eindeutige `(tripId, legId)`-Schlüssel;
42.988 von 42.989 J-RAW-Zeilen finden numerisch ein LT-135-Gegenstück
(99,9977 %), und die J-RAW-Zielklassifikation reproduziert HOME 18.722,
FIXED 5.129 und FREE 19.138 exakt.

**Urteil am technischen Gate — unentscheidbar:** Nur 15.982 der 42.988
scheinbaren Paare (**37,18 %**) behalten Ziel- und Vorgängeraktivitätscode;
gefordert waren mindestens 99,9 %. `tripId` bezeichnet zwischen den beiden
kontrafaktischen Simulationsläufen also nicht stabil denselben Weg, obwohl die
Schlüsselmengen fast vollständig überlappen. Die im Roh-`summary.json`
nachgelagert ausgegebenen Expositionsgruppen werden deshalb ausdrücklich nicht
interpretiert; die Mechanik-Gates wurden nicht bewertet.

**Entscheidung nach Stop-Regel:** Kein alternativer Post-hoc-Join, keine Dosis
und kein weiterer Lauf. Evidenz und Skript bleiben unter
`backend/data/campaigns/distance-decay-transfer-freiburg/free-destination-instrumentation/path-exposure-analysis/`
erhalten. Der nächste zulässige Anlauf muss vorab einen semantisch stabilen
Wegschlüssel ausgeben oder Entscheidungsweg und Distanz innerhalb desselben
Simulationslaufs gemeinsam protokollieren.

#### Freiburg-Wegeexpositionsmarginalen (2026-08-17) — präregistriert

**Abgrenzung und binäre Frage vor der Auswertung:** Das PX-135-Ergebnis verbietet
eine Ersatzpaarung über einen nachträglich gewählten Schlüssel, nicht aber den
Vergleich von bereits im selben Lauf gemeinsam protokollierter Aktivitätsnähe
und Distanz. Lokalisiert ein unabhängiger Marginalvergleich der J-RAW- und
LT-135-Gruppen den Langwegeffekt, ohne irgendeinen Cross-run-Join?

Die einmalige read-only Analyse **PM-135** klassifiziert jede CSV separat mit
unverändertem Codevertrag: FREE = `6/12/41/42/51/52`; direkt exponiert bei
FREE-`activityType`, folgeexponiert bei FREE-`previousActivityType`, insgesamt
exponiert bei mindestens einer Bedingung. Sie aggregiert je Lauf Anzahl,
`>20 km`-Anzahl/-Anteil und mittlere Distanz für `directAndFollow`,
`directOnly`, `followOnly`, `unexposed` sowie die exponierte Vereinigung. Es
werden keine Zeilen zwischen Läufen gepaart und keine neuen Simulationen
gestartet.

**Technische Gates:** Eingabe-Hashes bleiben `a58fac01…c7a9de` und
`b13e675e…e6bb57`; alle `legId == 0`-Zeilen sind genau einem bekannten
Zielpfad zugeordnet. J-RAW reproduziert HOME 18.722, FIXED 5.129 und FREE
19.138; LT-135 hat je Zielpfad und je Expositionsgruppe höchstens eine Zeile
Abweichung, entsprechend seiner insgesamt einen fehlenden Zeile. Scheitert
dies, sind die Marginalen nicht vergleichbar.

**Unverändert aus der PX-135-Präregistrierung übernommene Mechanik-Gates:**

1. Der exponierte J-RAW-Anteil liegt in `[65;85] %`.
2. Mindestens 99 % des positiven globalen J-RAW→LT-135-Deltas in der Anzahl
   `>20 km`-Wege entfällt auf die exponierte Marginale; die absolute Änderung
   des nicht exponierten `>20 km`-Anteils beträgt höchstens 0,1 pp.
3. In der exponierten Marginale steigt der `>20 km`-Anteil um mindestens 5 pp.
   Untergruppen werden berichtet, aber nicht nachträglich gegatet.

**Stop-Regel:** Genau eine separate Aggregation der zwei bestehenden CSVs, kein
Join, keine Dosis, Simulation, Modalkalibrierung oder Seed-Variation. Scheitert
ein technisches Gate, ist PM-135 unentscheidbar. Scheitert ein Mechanik-Gate,
ist die einfache Aktivitätsnachbarschaft als hinreichende Erklärung verworfen.
Bestehen alle Gates, ist die Wirkungskette nur auf diesem Seed marginal
lokalisiert; ein bedingter Mechanismus benötigt weiterhin eine eigene
Präregistrierung und einen Widerlegungsversuch.

**Ausführung:** PM-135 verwendete die fixierten Eingabe-Hashes und führte
nachweislich keinen Join aus. Alle Aktivitätscodes sind bekannt, J-RAW
reproduziert FD-000 exakt; Zielpfad- und Expositionsgruppengrößen unterscheiden
sich zwischen den Läufen um höchstens eine Zeile. Damit bestehen sämtliche
technischen Gates.

| Marginale | J-RAW Wege | LT-135 Wege | J-RAW `>20 km` | LT-135 `>20 km` | Δ Anteil |
|---|---:|---:|---:|---:|---:|
| direkt + Folge | 2.886 | 2.886 | 0,66 % | 5,13 % | +4,47 pp |
| nur direkt | 16.252 | 16.252 | 2,68 % | 7,20 % | +4,52 pp |
| nur Folge | 16.251 | 16.250 | 3,42 % | 7,21 % | +3,79 pp |
| **exponiert gesamt** | **35.389** | **35.388** | **2,854 %** | **7,036 %** | **+4,182 pp** |
| nicht exponiert | 7.600 | 7.600 | 7,816 % | 7,816 % | **0,000 pp** |

Alle **1.480** zusätzlichen `>20 km`-Wege (gesamt 1.604→3.084) entstehen in
der exponierten Marginale; die nicht exponierten Wege bleiben zusätzlich bei
der mittleren Distanz exakt 8,6272 km. Der globale Zuwachs +3,4429 pp wird durch
den exponierten Anteil 82,321 % × dessen +4,1823 pp bis auf den
Ein-Zeilen-Nenner reproduziert. Drei Mechanik-Gates bestehen: Exposition im
Fenster, 100 % statt mindestens 99 % Langwegdelta exponiert, nicht exponierte
Drift 0,0 statt höchstens 0,1 pp.

**Urteil gegen die Vorab-Regel:** Das vierte Gate scheitert: Die exponierte
Marginale steigt nur um +4,182 statt mindestens +5 pp. Aktivitätsnachbarschaft
lokalisiert die Wirkungskette exakt, ist als hinreichende Dosisformel aber
verworfen.

**Entscheidung nach Stop-Regel:** Kein Bonus-Retry, keine Seeds,
Modalkalibrierung oder Produktänderung. Der nächste Mechanik-Kandidat ist nicht
eine höhere Konstante, sondern die vorab zu messende Eligibility freier
Choice-Sets für überhaupt verfügbare `>20 km`-Alternativen. Auch dieser Schritt
benötigt eine neue Präregistrierung und Same-run-Evidenz.

#### Freiburg-Choice-Set-Eligibility (2026-08-17) — präregistriert

**Forensik und binäre Frage vor dem Lauf:** `DestinationChoicePerActivity`
filtert das Choice-Set vor der Nutzenberechnung auf Zonen mit
`getAttractivity(nextActivity.activityType()) > 0` und übergibt die resultierenden
Nutzen an `DefaultLogitModel`. Eine freie Entscheidung ist deshalb genau dann
**langweg-eligible**, wenn dieses bereits zweckspezifisch attraktive Choice-Set
mindestens eine Alternative mit Modellentfernung `DISTANCE > 20 km` und strikt
positiver MNL-Wahrscheinlichkeit enthält. Erklärt ein strukturell ineligibler
Teil der 19.138 freien Entscheidungen die verbleibende LT-135-Unterwirkung?

Der Anker wird vorab aus den join-freien Direktmarginalen gebildet: J-RAW hat
454/19.138 direkte Langwege (**2,3722 %**), LT-135 1.318/19.138
(**6,8868 %**). Eine homogene Logit-Umschichtung um `+1,35` ergäbe aus dem
J-RAW-Anteil **8,5698 %**; der beobachtete Zuwachs erreicht davon **72,84 %**.
Die primäre Vorhersage lautet daher: Der CE-000-Eligibility-Anteil liegt im
vorab großzügig fixierten Fenster **`[65;85] %`**. Ein höherer Wert widerlegt
strukturelle Ineligibility als hinreichende Erklärung, ein niedrigerer Wert
widerlegt die aus dem beobachteten Wirkungsverhältnis abgeleitete Größenordnung.

Die zwei Instrumentierungsarme **CE-000** und **CE-135** verwenden denselben
eingefrorenen Freiburg-Stand, Seed 42, Legacy-Personen-Hash
`9dda440f…fca90`, Helper `DISTANCE / 1000` und dieselben fünf
Zielwahlparameter. CE-000 hat keinen Langwegbonus; CE-135 wiederholt
ausschließlich den bereits entschiedenen LT-135-Term
`1,35 * [DISTANCE > 20]` — dies ist keine neue Dosis. Ein isolierter,
gitignorierter Harness ergänzt in `DestinationChoicePerActivity` nur
threadsichere Aggregatzähler. Pro freier Entscheidung werden Choice-Set-Größe,
Anzahl `>20-km`-Alternativen, Eligibility, Summe ihrer MNL-Wahrscheinlichkeiten
und die Modellentfernung der tatsächlich gewählten Zone erfasst. Es gibt kein
Per-Decision-Log und keinen Cross-run-Join.

CE-000 berechnet außerdem read-only den exakten Kontrafakt für denselben
Choice-Set-Zustand. Für die Basis-Langwegmasse `p` ist er
`p_135 = exp(1,35) * p / (1 - p + exp(1,35) * p)`. Dieser Wert verändert weder
Nutzen noch Zufallszahl oder Auswahl. CE-135 protokolliert die wirkliche
Wahrscheinlichkeitsmasse nach dem bekannten Bonus; so wird getrennt geprüft,
ob Ineligibility, heterogene Basismasse oder eine endogen veränderte Folge von
Ausgangszonen die naive Gesamt-Log-Odds-Prognose bricht.

**Technische Gates vor Interpretation:**

1. Beide externen Läufe enden mit EXIT 0; Personen-, Helper-, Basis-MNL- und
   Zielwahlparameter-Hashes stimmen mit J-RAW überein. Die Quellunterschiede
   beschränken sich auf die Instrumentierung und im CE-135-Arm den bekannten
   `+1,35`-Term; kein Fallback und keine Exception.
2. Die Instrumentierung ist verhaltensneutral: CE-000 reproduziert den
   J-RAW-Demand-Hash `a58fac01…c7a9de`, CE-135 den LT-135-Hash
   `b13e675e…e6bb57` byteidentisch.
3. Je Arm ist genau eine Abschlusszeile parsebar. Alle Choice-Sets sind
   nichtleer, jede Wahrscheinlichkeit ist endlich und nichtnegativ, ihre Summe
   weicht je Entscheidung höchstens `1e-9` von 1 ab. CE-000 reproduziert exakt
   19.138 freie Entscheidungen und die FD-000-Aktivitätsbilanz; CE-135 darf
   wegen seiner bekannten Ein-Zeilen-Gesamtabweichung höchstens eine freie
   Entscheidung abweichen.

**Vorab fixierte Mechanik-Gates:**

1. Primär: CE-000-Eligibility liegt in `[65;85] %`.
2. Je Arm weicht die mittlere protokollierte Langweg-Wahrscheinlichkeitsmasse
   höchstens **0,5 Prozentpunkte** vom Anteil tatsächlich gewählter
   `DISTANCE > 20`-Zonen ab; damit wird die read-only Wahrscheinlichkeitsmessung
   gegen die reale Ein-Seed-Auswahl geprüft.
3. Die mittlere CE-000-Kontrafaktmasse `p_135` weicht höchstens
   **1,0 Prozentpunkt** von der mittleren wirklichen CE-135-Langwegmasse ab.
   Dieses Gate prüft ohne Wegpaarung, ob der Baseline-Choice-Set-Zustand den
   Bonusarm aggregiert vorhersagt. Eligibility und Wahrscheinlichkeitsmasse
   werden zusätzlich je freiem Aktivitätstyp berichtet, aber nicht post hoc
   gegatet.

**Stop-Regel:** Genau CE-000 und die Wiederholung der bereits bekannten Dosis
CE-135, jeweils nur Seed 42. Keine neue Bonuskonstante, Modalkalibrierung,
Seed- oder Regionenvariation und keine Produktänderung. Scheitert ein
technisches Gate, ist die Messung unentscheidbar. Scheitert Mechanik-Gate 1,
ist strukturelle Choice-Set-Ineligibility als hinreichende Erklärung verworfen;
die vorab fixierten Wahrscheinlichkeitsdiagnosen werden trotzdem berichtet,
aber nicht in eine neue Dosis umgerechnet. Auch bei vollständigem Bestehen ist
nur der Mechanismus dieses Freiburg-Seeds erklärt; ein Produktvertrag bleibt
bis zu einer neuen Mehrregionen-Präregistrierung gesperrt.

**Ausführung:** Ein erster CE-000-Preflight endete vor jeder Modellentscheidung,
weil der flüchtige `/tmp/synthesis-legacy`-Input des alten Laufs bereits
bereinigt war; er geht nicht in die Auswertung ein. Die erfolgreiche
Wiederholung band die persistente, mit FD-000 identische Legacy-Basis ein.
CE-000 und CE-135 kompilierten mit JDK 25 und endeten extern mit EXIT 0. Je Arm
existiert genau eine Abschlusszeile, es gibt keine ungültige Wahrscheinlichkeit,
keinen Fallback und keine Exception. Die maximale Abweichung der
Wahrscheinlichkeitssumme von 1 beträgt `3,55e-15` beziehungsweise `5,22e-15`.
CE-000 reproduziert J-RAW mit SHA-256 `a58fac01…c7a9de`, CE-135 LT-135 mit
`b13e675e…e6bb57` byteidentisch; damit bestehen alle technischen Gates.

| Arm | freie Entscheidungen | eligible | mittlere Langwegmasse | tatsächlich gewählt | Abweichung Auswahl−Masse | CE-000-Kontrafakt |
|---|---:|---:|---:|---:|---:|---:|
| CE-000 | 19.138 | **100,00 %** | 2,3376 % | 2,3722 % (454) | +0,0347 pp | **6,6643 %** |
| CE-135 | 19.138 | **100,00 %** | 7,0603 % | 6,8868 % (1.318) | −0,1735 pp | berichtet |

Beide Auswahl-gegen-Masse-Gates bestehen deutlich unter 0,5 pp. Auch die
CE-000-Kontrafaktmasse liegt nur **0,3960 pp** unter der wirklichen
CE-135-Masse und besteht den 1,0-pp-Gate. Die Anzahl aller Kandidaten ist in
beiden Armen 8.252.855; die Anzahl der Langalternativen unterscheidet sich
geringfügig (3.510.014/3.512.432), weil frühere Zielziehungen die späteren
Ausgangszonen endogen verändern.

| freier Aktivitätstyp | n | CE-000-Masse | exakter `+1,35`-Kontrafakt | CE-135-Masse | CE-135 gewählt |
|---|---:|---:|---:|---:|---:|
| SERVICE | 3.445 | 1,1532 % | 3,7201 % | 4,2716 % | 3,6575 % |
| PRIVATE_VISIT | 2.594 | 4,0185 % | 12,4289 % | 12,4455 % | 11,7965 % |
| SHOPPING_DAILY | 6.726 | 1,1289 % | 1,8437 % | 2,6781 % | 2,6464 % |
| SHOPPING_OTHER | 1.229 | **0,0156 %** | **0,0600 %** | 0,0606 % | 0,0814 % |
| LEISURE_INDOOR | 2.857 | 4,5844 % | 13,6604 % | 13,6714 % | 14,0707 % |
| LEISURE_OUTDOOR | 2.287 | 4,2109 % | 13,5472 % | 13,5439 % | 13,3362 % |

**Urteil gegen die Vorab-Regel:** Das primäre Eligibility-Fenster `[65;85] %`
scheitert mit **100 %** eindeutig. Fehlende attraktive Fernalternativen erklären
die Unterwirkung nicht; jede freie Entscheidung ist langweg-eligible. Erklärt
wird sie stattdessen durch die stark heterogene Ausgangswahrscheinlichkeitsmasse:
Die homogene Transformation eines Gesamtanteils (8,5698 %) ist nicht gleich
dem Mittel der je Choice-Set transformierten Wahrscheinlichkeiten (6,6643 %).
`SHOPPING_OTHER` zeigt die Grenze des binären Eligibility-Begriffs besonders
klar: alle Entscheidungen sind eligible, ihre mittlere Basismasse beträgt aber
nur 0,0156 %.

**Entscheidung nach Stop-Regel:** Keine neue Dosis, Seeds, Modalkalibrierung
oder Produktänderung. Strukturelle Choice-Set-Ineligibility ist verworfen; der
bekannte `+1,35`-Effekt ist auf dem Freiburg-Seed durch per-Choice-Set-
Wahrscheinlichkeitsmasse aggregiert erklärbar. Der nächste zulässige
Mechanikschritt ist eine separat präregistrierte, verhaltensneutrale Verteilung
dieser Basismasse über feste Klassen und Aktivitätstypen — nicht das
Hochskalieren des Bonus. Rohlogs, Quellsnapshots und maschinenlesbare Evidenz
bleiben unter
`backend/data/campaigns/distance-decay-transfer-freiburg/choice-set-eligibility/`;
Produktcode und `validation/` sind unverändert.

#### Freiburg-Langwegmassenverteilung (2026-08-17) — präregistriert

**Forensik und binäre Frage vor dem Lauf:** CE-000 belegt eine mittlere
Langweg-Wahrscheinlichkeitsmasse von 2,3376 %, aber noch nicht, ob die
Nichtlinearität überwiegend zwischen Aktivitätstypen oder innerhalb derselben
Aktivitätstypen entsteht. Ist die LT-135-Unterwirkung Ausdruck eines breiten,
innerhalb der Zwecke heterogenen Niedrigmassenregimes, statt nur eines
unterschiedlichen Mittelwerts je Zweck?

Der einmalige Arm **PD-000** verwendet exakt den CE-000-Stand: Freiburg,
Seed 42, J-0895-Zielwahlparameter, Basis-MNL, Helper `DISTANCE / 1000` und
keinen Langwegbonus. Die verhaltensneutrale Instrumentierung summiert je freier
Entscheidung die MNL-Wahrscheinlichkeit `p` aller `DISTANCE > 20 km`-Zonen und
ordnet sie vor der Auswahl genau einer festen Klasse zu:

1. `p < 0,0001` (`<0,01 %`),
2. `0,0001 <= p < 0,001` (`0,01–<0,1 %`),
3. `0,001 <= p < 0,01` (`0,1–<1 %`),
4. `0,01 <= p < 0,05` (`1–<5 %`),
5. `0,05 <= p < 0,10` (`5–<10 %`),
6. `p >= 0,10` (`>=10 %`).

Je Klasse und Aktivitätstyp werden nur Anzahl und Summe von `p` ausgegeben;
zusätzlich `sum(p²)` für die vorab festgelegte Varianzzerlegung. Es gibt weder
Per-Decision-Log noch Cross-run-Join. Mit Gesamtmittel `m`, Typmittel `m_g` und
Typumfang `n_g` gilt `SST = sum(p²) - n*m²`,
`SSB = sum(n_g*(m_g-m)²)`, `SSW = SST-SSB`; der Within-Anteil ist
`SSW/SST`.

**Technische Gates vor Interpretation:** Externer Lauf EXIT 0, kein Fallback
und keine Exception, genau eine Abschlusszeile, 19.138 freie Entscheidungen,
keine ungültige Wahrscheinlichkeit und maximale Summenabweichung `<=1e-9`.
Alle sechs Klassen summieren sich exakt auf 19.138 Entscheidungen; ihre
Massen summieren sich bis `1e-9` auf die Gesamtmasse. Der Demand-SHA-256 muss
J-RAW erneut byteidentisch als `a58fac01…c7a9de` reproduzieren; Gesamtmasse und
Aktivitätstypbilanz müssen CE-000 bis auf Gleitkomma-Additionsreihenfolge
(`<=1e-9`) entsprechen.

**Vorab fixierte Mechanik-Gates:**

1. Mindestens **50 %** aller freien Entscheidungen liegen im breiten
   Niedrigmassenbereich `p < 1 %` (Klassen 1–3).
2. Mindestens **50 %** der Gesamtvarianz von `p` entsteht **innerhalb** der
   Aktivitätstypen (`SSW/SST >= 0,50`).

`SHOPPING_OTHER` sowie Anzahl und Massenanteil jeder Klasse werden vollständig
berichtet, aber nicht post hoc gegatet. Bestehen beide Gates, ist ein bloß
zweckspezifischer konstanter Bonus als nächste hinreichende Erklärung
verworfen; ein weiterer Mechanismus müsste die innerhalb der Zwecke variierende
Ausgangslage explizit abbilden. Besteht nur Gate 1, ist ein zweckspezifischer
Mechanismus weiter prüfbar. Scheitert Gate 1, wird das breite
Niedrigmassenregime verworfen und die verbleibende CE-Differenz muss ohne neue
Dosis auf endogene Choice-Set-Folgen untersucht werden.

**Stop-Regel:** Genau ein PD-000-Lauf mit Seed 42. Keine Bonuskonstante,
Modalkalibrierung, Seed-/Regionenvariation oder Produktänderung. Ein technisches
Scheitern macht PD-000 unentscheidbar. Unabhängig vom Mechanikurteil werden aus
diesem Ein-Seed-Lauf weder Parameter abgeleitet noch Defaults geändert.

**Ausführung:** Die PD-000-Instrumentierung kompiliert mit JDK 25; ihre Utility
ist mit SHA-256 `5b13ad11…9f454c` byteidentisch zu CE-000 und bonusfrei. Der
einzige externe Lauf endet mit EXIT 0 und `BUILD SUCCESSFUL in 15s`. Genau eine
Abschlusszeile enthält 19.138 freie Entscheidungen, keine ungültige
Wahrscheinlichkeit und eine maximale Summenabweichung von `3,55e-15`; Fallback
und Exception treten nicht auf. Gesamtmasse, Aktivitätstypbilanz und
J-RAW-Demand-Hash `a58fac01…c7a9de` reproduzieren CE-000 exakt. Die sechs
Klassen summieren sich auf 19.138 und ihre Massen weichen nur um `1,48e-12`
von der Gesamtmasse ab. Alle technischen Gates bestehen.

| Basis-Langwegmasse `p` | Entscheidungen | Anteil Entscheidungen | Anteil Gesamtmasse |
|---|---:|---:|---:|
| `<0,01 %` | 5.283 | 27,6048 % | 0,0189 % |
| `0,01–<0,1 %` | 2.693 | 14,0715 % | 0,2566 % |
| `0,1–<1 %` | 3.574 | 18,6749 % | 3,5171 % |
| `1–<5 %` | 5.160 | 26,9621 % | 28,7186 % |
| `5–<10 %` | 1.598 | 8,3499 % | 24,9132 % |
| `>=10 %` | 830 | 4,3369 % | **42,5757 %** |

Damit liegen **11.550/19.138 = 60,3511 %** der Entscheidungen unter 1 % und
bestehen Mechanik-Gate 1. Diese breite Mehrheit trägt zusammen nur 3,7926 %
der gesamten Fernzielmasse. Umgekehrt tragen lediglich 830 Entscheidungen mit
`p >=10 %` bereits 42,5757 % der Masse.

Die vorab definierte Varianzzerlegung ergibt `SST = 75,1796550`, davon
`SSB = 5,1062364` zwischen und `SSW = 70,0734186` innerhalb der
Aktivitätstypen. Der Within-Anteil beträgt damit **93,2080 %** statt mindestens
50 %; auch Mechanik-Gate 2 besteht. Die Niedrigmassenanteile sind dennoch
fachlich unterschiedlich: SERVICE 70,91 %, PRIVATE_VISIT 19,70 %,
SHOPPING_DAILY **95,76 %**, SHOPPING_OTHER **100 %**, LEISURE_INDOOR 20,97 %
und LEISURE_OUTDOOR 14,30 %. Bei SHOPPING_OTHER liegen 96,50 % sogar unter
0,1 %. SHOPPING_DAILY verbindet dagegen 95,76 % Niedrigmassenfälle mit wenigen
Hochmassenfällen und erzeugt allein 45,8203 der 70,0734 Within-SSE.

**Urteil gegen die Vorab-Regel:** Beide Mechanik-Gates bestehen. Die
Langwegmasse ist stark konzentriert, und nur 6,7920 % ihrer Varianz lassen sich
durch unterschiedliche Aktivitätstypmittel erklären. Ein bloß
zweckspezifischer konstanter Bonus ist deshalb keine hinreichende strukturelle
Erklärung der Unterwirkung; die dominante Heterogenität liegt innerhalb
desselben Zwecks.

**Entscheidung nach Stop-Regel:** Keine Dosis, Seeds, Modalkalibrierung oder
Produktänderung. Der nächste zulässige Mechanikkandidat ist eine neue,
verhaltensneutrale Same-run-Varianzzerlegung nach Ausgangszone innerhalb des
Aktivitätstyps. Sie muss vorab klären, ob Geografie/Erreichbarkeit die
93,2080-%-Within-Varianz bindet; PD-000 selbst liefert keinen Produktparameter.
Rohlog, Quellhashes und maschinenlesbare Klassenbilanz bleiben unter
`backend/data/campaigns/distance-decay-transfer-freiburg/choice-set-eligibility/pd-000/`.

#### Freiburg-Ausgangszonenzerlegung (2026-08-17) — präregistriert

**Forensik und binäre Frage vor dem Lauf:** PD-000 lokalisiert 93,2080 % der
Langwegmassenvarianz innerhalb der Aktivitätstypen. Die nächstliegende
Same-run-Erklärung ist die Ausgangsgeografie: Für denselben Zweck ändert die
vorherige Zone, wie viele und wie gut erreichbare Fernziele relativ zu nahen
Zielen im Logit stehen. Erklärt die Ausgangszone einen dominanten Anteil dieser
Within-Zweck-Varianz, oder bleibt die Streuung selbst bei gleicher Zone
überwiegend personen-/situationsabhängig?

Der einmalige Arm **OZ-000** verwendet PD-000 unverändert: Freiburg, Seed 42,
J-0895-Zielwahlparameter, Basis-MNL, Helper `DISTANCE / 1000`, kein
Langwegbonus. Zusätzlich zu den bereits validierten PD-Aggregaten werden für
jede Zelle `(nextActivity.activityType(), previousActivity.zone().getId())`
nur `n`, `sum(p)` und `sum(p²)` threadsicher akkumuliert. Es gibt kein
Per-Decision-Log und keinen Cross-run-Join.

Um eine durch Einzelbeobachtungen künstlich hohe Erklärung zu verhindern,
fließen ausschließlich vorab als **wiederholt** definierte Zellen mit `n >=5`
in den Mechanik-Gate ein. Die Schwelle wurde auf reine Größenforensik des
bereits bestehenden J-RAW-Demand-CSV gestützt, ohne Wahrscheinlichkeitswerte zu
sehen: 1.293 solcher `(Aktivitätstyp, sourceZone)`-Zellen decken
14.748/19.138 = 77,0613 % der freien Wege ab; 646 von insgesamt 3.259 Zellen
sind Singletons.

Für die abgedeckten Entscheidungen werden je Aktivitätstyp neue Mittelwerte
gebildet. `SSW_purpose` ist deren gesamte Within-Zweck-SSE. Die Komponente
zwischen den Ausgangszellen desselben Zwecks ist
`SSB_origin = sum_c(n_c*(m_c-m_g)²)`; der Rest innerhalb gleicher Zellen ist
`SSW_cell = sum_c(sum(p²)_c-n_c*m_c²)`. Es muss bis `1e-9` gelten:
`SSW_purpose = SSB_origin + SSW_cell`.

**Technische Gates vor Interpretation:** Externer Lauf EXIT 0, kein Fallback
und keine Exception, genau eine Abschlusszeile, CE-/PD-Bilanz mit 19.138 freien
Entscheidungen, keine ungültige Wahrscheinlichkeit, maximale Summenabweichung
`<=1e-9` und byteidentischer J-RAW-Demand-Hash `a58fac01…c7a9de`. Gesamtmasse,
`sum(p²)`, sechs Klassen und Aktivitätstypaggregate stimmen bis `1e-9` mit
PD-000 überein. Alle Zellzahlen summieren sich auf 19.138; die `n>=5`-Abdeckung
beträgt mindestens **75 %**, und die Varianzidentität hält bis `1e-9`.

**Vorab fixiertes Mechanik-Gate:** Die Ausgangszellen erklären mindestens
**70 %** der Within-Zweck-Varianz der abgedeckten Entscheidungen:
`SSB_origin / SSW_purpose >=0,70`.

Besteht der Gate, ist Ausgangsgeografie/Erreichbarkeit der dominante nächste
Mechanismus; ein weiterer Diagnosepfad müsste dann vorab eine kontinuierliche
geografische Größe statt Zonen-IDs prüfen. Scheitert er, wird die
Ausgangszonenerklärung verworfen und der Rest innerhalb identischer
Zweck-Zonen-Zellen muss vor einem neuen Mechanismus auf Personen-/
Verfügbarkeitsmerkmale zerlegt werden.

**Stop-Regel:** Genau ein OZ-000-Lauf, nur Seed 42. Keine Bonuskonstante,
Modalkalibrierung, Seed-/Regionenvariation oder Produktänderung. Ein technisches
Scheitern macht OZ-000 unentscheidbar. Auch ein bestandener Gate liefert nur
eine Mechanismusrichtung, keinen Parameter oder Default.

**Ausführung:** OZ-000 kompiliert mit JDK 25; die Utility bleibt mit
`5b13ad11…9f454c` byteidentisch zu CE-/PD-000 und enthält keinen Bonus. Der
externe Lauf endet mit EXIT 0 und `BUILD SUCCESSFUL in 15s`. Genau eine
Abschlusszeile reproduziert alle 19.138 Entscheidungen, Gesamtmasse,
`sum(p²)`, Klassen und Aktivitätstypaggregate von PD-000 exakt; der Demand-Hash
ist erneut J-RAW `a58fac01…c7a9de`. Es gibt keine ungültige Wahrscheinlichkeit,
Exception oder Fallback; die maximale Summenabweichung bleibt `3,55e-15`.

Die 3.259 beobachteten Zweck-Ausgangszonen-Zellen enthalten 646 Singletons.
Wie vorab fixiert, gehen nur die 1.293 Zellen mit `n >=5` in die Zerlegung ein.
Sie decken **14.748/19.138 = 77,0613 %** ab und bestehen damit den technischen
75-%-Gate. Für diese Entscheidungen gilt exakt:

| Komponente | SSE | Anteil der Within-Zweck-SSE |
|---|---:|---:|
| gesamte Within-Zweck-Varianz | 50,37487925 | 100 % |
| zwischen Ausgangszellen desselben Zwecks | 10,11455006 | **20,0786 %** |
| innerhalb identischer Zweck-Ausgangszonen-Zellen | 40,26032918 | **79,9214 %** |

Die Varianzidentität hat Abweichung 0. Der Ausgangszonenanteil variiert nach
Zweck: SERVICE 9,80 %, PRIVATE_VISIT 35,42 %, SHOPPING_DAILY 9,54 %,
SHOPPING_OTHER 69,95 %, LEISURE_INDOOR 54,51 % und LEISURE_OUTDOOR 61,94 %.
Die größte absolute Within-SSE liegt bei SHOPPING_DAILY (34,7104); gerade dort
erklärt die Ausgangszelle nur 9,54 %, sodass sie das Gesamtergebnis dominiert.

**Urteil gegen die Vorab-Regel:** Das Mechanik-Gate scheitert mit **20,0786 %
statt mindestens 70 %** deutlich. Ausgangsgeografie beeinflusst einzelne
Zwecke, ist aber keine dominante Erklärung des breiten Freiburg-
Niedrigmassenregimes. Fast vier Fünftel der Varianz bleiben sogar bei gleichem
Zweck und gleicher Ausgangszone innerhalb der wiederholt belegten Zellen.

**Entscheidung nach Stop-Regel:** Keine geografische Folgesonde, Dosis, Seeds,
Modalkalibrierung oder Produktänderung. Der nächste zulässige Same-run-
Mechanikkandidat muss vorab Personen-/Modusverfügbarkeits- oder andere
situative Merkmale innerhalb identischer Zweck-Ausgangszonen-Zellen zerlegen.
OZ-000 liefert weder einen Parameter noch einen Default. Rohlog, Quellhashes
und maschinenlesbare Evidenz bleiben unter
`backend/data/campaigns/distance-decay-transfer-freiburg/choice-set-eligibility/oz-000/`.

#### Freiburg-Modusverfügbarkeitszerlegung (2026-08-24) — präregistriert

**Forensik und binäre Frage vor dem Lauf:** OZ-000 lässt 79,9214 % der
Langwegmassenvarianz innerhalb identischer `(Aktivitätstyp, Ausgangszone)`-
Zellen unerklärt. Die Zielwahl-Utility enthält getrennte Logsums für aktive/
öffentliche und motorisierte Modi; deren Verfügbarkeit hängt im verwendeten
`ModeAvailabilityInDestinationChoice` unter anderem davon ab, ob die Person zu
Hause ist, ein Fahrrad besitzt, einen Führerschein und aktuell Zugriff auf ein
Haushaltsfahrzeug hat oder außerhalb bereits an einen Modus gebunden ist.
Erklärt das tatsächlich verfügbare Modusset den überwiegenden Teil des
OZ-000-Rests, oder bleibt die Streuung auch bei gleichem Zweck, gleicher
Ausgangszone und gleicher Modusverfügbarkeit überwiegend bestehen?

Der einmalige Arm **AV-000** verwendet OZ-000 unverändert: Freiburg, Seed 42,
J-0895-Zielwahlparameter, Basis-MNL, Helper `DISTANCE / 1000`, kein
Langwegbonus. Zusätzlich wird je freier Entscheidung mit derselben
`ModeAvailabilityInDestinationChoice`-Implementierung und demselben
Sieben-Modi-Choice-Set wie in `SimulatorBuilder` das sortierte Set der
verfügbaren `StandardMode`s bestimmt. Akkumuliert werden ausschließlich `n`,
`sum(p)` und `sum(p²)` je
`(Aktivitätstyp, Ausgangszone, Verfügbarkeitssignatur)`; es gibt weder ein
Per-Decision-Log noch einen Cross-run-Join.

Für die Varianzzerlegung zählen nur Verfügbarkeits-Unterzellen mit `n >=5`.
Auf deren Entscheidungen werden die Mittelwerte der jeweiligen
`(Aktivitätstyp, Ausgangszone)`-Elternzellen neu gebildet. Der darin liegende
OZ-Rest `SSW_origin` zerfällt in
`SSB_availability = sum_a(n_a*(m_a-m_c)²)` zwischen Verfügbarkeitssignaturen
derselben Elternzelle und
`SSW_availability = sum_a(sum(p²)_a-n_a*m_a²)` innerhalb identischer
Signaturen. Bis `1e-9` muss gelten:
`SSW_origin = SSB_availability + SSW_availability`.

**Technische Gates vor Interpretation:** Externer Lauf EXIT 0, kein Fallback
und keine Exception, genau eine Abschlusszeile, CE-/PD-/OZ-Bilanz mit 19.138
freien Entscheidungen, keine ungültige Wahrscheinlichkeit, maximale
Summenabweichung `<=1e-9`, byteidentischer J-RAW-Demand-Hash
`a58fac01…c7a9de` und byteidentische Utility
`5b13ad11…9f454c`. Gesamtmasse, `sum(p²)`, Klassen,
Aktivitätstypaggregate und die vollständige OZ-Zerlegung stimmen bis `1e-9`
mit OZ-000 überein. Alle Verfügbarkeits-Unterzellen summieren sich auf 19.138;
die `n>=5`-Unterzellen decken mindestens **60 %** aller freien Entscheidungen
ab, und die Varianzidentität hält bis `1e-9`.

**Vorab fixiertes Mechanik-Gate:** Unterschiede zwischen den verfügbaren
Modussets erklären mindestens **50 %** der auf den abgedeckten Entscheidungen
neu berechneten Within-Zweck-/Ausgangszonen-Varianz:
`SSB_availability / SSW_origin >=0,50`.

Besteht der Gate, ist die dynamische Modusverfügbarkeit der dominante nächste
Mechanismus. Ein weiterer Diagnosepfad müsste dann vorab einzelne
Verfügbarkeitskomponenten trennen; AV-000 selbst liefert noch keinen
Kalibrierparameter. Scheitert der Gate, wird die Modusverfügbarkeit als
dominante Erklärung verworfen und als Nächstes dürfen nur die in der Utility
tatsächlich verwendeten Personenmerkmale (Altersband, Erwerbsstatus,
Zeitkartenbesitz, Pkw-Ausstattung und ökonomischer Status) innerhalb derselben
Zweck-Zonen-Zellen zerlegt werden.

**Stop-Regel:** Genau ein AV-000-Lauf, nur Seed 42. Keine Bonuskonstante,
Modalkalibrierung, Seed-/Regionenvariation oder Produktänderung. Ein
technisches Scheitern macht AV-000 unentscheidbar. Auch ein bestandener Gate
liefert nur eine Mechanismusrichtung, keinen Parameter oder Default.

**Ausführung:** AV-000 kompiliert mit dem gepinnten JDK 25 und endet extern mit
EXIT 0 sowie `BUILD SUCCESSFUL in 14s`. Genau eine Abschlusszeile enthält alle
19.138 freien Entscheidungen. Utility (`5b13ad11…9f454c`) und J-RAW-Demand
(`a58fac01…c7a9de`) bleiben byteidentisch; Gesamtmasse, `sum(p²)`, Klassen,
Aktivitätstypaggregate und die vollständige OZ-Zerlegung werden exakt
reproduziert. Es gibt keine ungültige Wahrscheinlichkeit, Exception oder
Fallback; beide Varianzidentitäten haben Abweichung 0.

Die exakte Verfügbarkeitssignatur fragmentiert die Stichprobe jedoch stark:
6.459 Unterzellen enthalten 2.529 Singletons. Nur 939 Unterzellen mit `n>=5`
in 718 Zweck-Zonen-Eltern decken **8.618/19.138 = 45,0308 %** statt der vorab
geforderten mindestens 60 % ab. Damit scheitert ein technischer Gate vor jeder
Mechanikinterpretation.

Auf der nicht hinreichend abgedeckten Teilmenge ergibt die reine Rechnung
`SSW_origin = 31,41837067`, `SSB_availability = 1,18291499` und
`SSW_availability = 30,23545569`. Der daraus folgende rohe Anteil 3,7650 %
wird nach der Vorab-Regel **nicht interpretiert**; insbesondere ist er kein
Beleg gegen Modusverfügbarkeit.

**Urteil und Stop-Regel:** AV-000 ist wegen Sparse Cells unentscheidbar, nicht
negativ. Die Mindestzellgröße wird nicht nachträglich gesenkt, und es folgen
keine Dosis, Seeds, Modalkalibrierung oder Produktänderung. Der nächste
zulässige Same-run-Spike muss die Verfügbarkeitsdimensionen vorab gröber und
komponentenweise definieren, bevor Personenmerkmale untersucht werden. Rohlog,
Quellhashes und maschinenlesbare Evidenz liegen unter
`backend/data/campaigns/distance-decay-transfer-freiburg/choice-set-eligibility/av-000/`.

#### Freiburg-Verfügbarkeitskomponenten (2026-08-24) — präregistriert

**Frage und Abgrenzung vor dem Lauf:** AV-000 ist nur deshalb unentscheidbar,
weil die vollständige Modusset-Signatur zu viele dünn besetzte Unterzellen
erzeugt. AC-000 prüft daher dieselben Verfügbarkeitsinformationen gröber, ohne
den vorab gesetzten Zellfilter zu lockern: Erklärt mindestens eine einzelne
binäre Modusverfügbarkeit den überwiegenden Teil des OZ-000-Rests innerhalb
gleicher Zweck-Ausgangszonen-Zellen?

Der einmalige Arm **AC-000** verwendet AV-000 unverändert: Freiburg, Seed 42,
J-0895-Zielwahlparameter, Basis-MNL, Helper `DISTANCE / 1000`, kein
Langwegbonus und dieselbe produktive
`ModeAvailabilityInDestinationChoice`-Abfrage. Aus ihrem einmal je
Entscheidung berechneten Sieben-Modi-Set werden die Komponenten `CAR`,
`PASSENGER`, `BIKE`, `PEDESTRIAN`, `PUBLICTRANSPORT`,
`CARSHARING_STATION` und `CARSHARING_FREE` jeweils separat als
verfügbar/nicht verfügbar ausgewertet. Je Komponente werden ausschließlich
`n`, `sum(p)` und `sum(p²)` pro
`(Aktivitätstyp, Ausgangszone, Verfügbarkeitswert)` akkumuliert. Die sieben
Zerlegungen überlappen und dürfen weder addiert noch als unabhängige Effekte
interpretiert werden.

Pro Komponente zählen weiterhin nur binäre Unterzellen mit `n >=5`; die
Elternmittel werden auf deren jeweiliger Teilpopulation neu gebildet. Damit
zerfällt `SSW_mode` in `SSB_mode` zwischen verfügbar/nicht verfügbar und
`SSW_within_mode` innerhalb desselben Werts. Bis `1e-9` muss gelten:
`SSW_mode = SSB_mode + SSW_within_mode`.

**Technische Gates vor Interpretation:** Externer Lauf EXIT 0, kein Fallback
und keine Exception, genau eine Abschlusszeile, byteidentische Utility
`5b13ad11…9f454c`, byteidentischer J-RAW-Demand
`a58fac01…c7a9de` sowie exakte Reproduktion aller CE-/PD-/OZ-/AV-Aggregate.
Jede der sieben Komponententabellen enthält vor dem Zellfilter alle 19.138
freien Entscheidungen. Eine Komponente ist nur interpretierbar, wenn ihre
`n>=5`-Unterzellen mindestens **60 %** der Entscheidungen und ihre neu
zentrierte `SSW_mode` mindestens **60 %** der vollständigen OZ-Restvarianz
`40,26032918334451` abdecken und die Varianzidentität bis `1e-9` hält.

**Vorab fixiertes Mechanik-Gate:** Für jede technisch interpretierbare
Komponente wird einzeln `SSB_mode / SSW_mode` berechnet. Mindestens eine
Komponente muss **50 %** erreichen, um als dominanter nächster
Mechanismuskandidat zu gelten. Die größte Einzelquote wird berichtet, aber
nicht aus mehreren Komponenten zusammengesetzt. Erreicht keine Komponente den
Gate, gilt der Mechanismus nur dann als nicht dominant, wenn alle sieben
Komponenten technisch interpretierbar waren; andernfalls bleibt AC-000
unentscheidbar.

**Stop-Regel:** Genau ein AC-000-Lauf, nur Seed 42. Kein post-hoc veränderter
Zellfilter, keine kombinierte Signatur, Bonuskonstante, Dosis,
Modalkalibrierung, Seed-/Regionenvariation oder Produktänderung. Ein positiver
Befund erlaubt erst eine neue Diagnose zur Trennung von Ressource und
Tourbindung; ein vollständig negativer Befund erlaubt als Nächstes die vorab
definierte Zerlegung der tatsächlich in der Utility verwendeten
Personenmerkmale. AC-000 selbst liefert keinen Parameter oder Default.

**Ausführung:** AC-000 kompiliert mit JDK 25 und endet extern mit EXIT 0 sowie
`BUILD SUCCESSFUL in 14s`. Es gibt genau eine Abschlusszeile, keine ungültige
Wahrscheinlichkeit, Exception oder keinen Fallback. Utility
(`5b13ad11…9f454c`), J-RAW-Demand (`a58fac01…c7a9de`) und sämtliche
CE-/PD-/OZ-/AV-Aggregate werden exakt reproduziert. Jede Komponententabelle
enthält vor dem Zellfilter alle 19.138 Entscheidungen; alle sieben
Varianzidentitäten schließen mit Abweichung 0.

| Komponente | Entscheidungen mit `n>=5` | OZ-Restvarianz abgedeckt | erklärt |
|---|---:|---:|---:|
| `CAR` | 61,5843 % | 91,4904 % | **4,3735 %** |
| `PASSENGER` | 73,7904 % | 99,6819 % | 2,1291 % |
| `BIKE` | 62,6502 % | 89,8752 % | 1,0205 % |
| `PEDESTRIAN` | 73,7904 % | 99,6819 % | 2,1291 % |
| `PUBLICTRANSPORT` | 73,7904 % | 99,6819 % | 2,1291 % |
| `CARSHARING_STATION` | 72,0086 % | 96,9052 % | 1,1036 % |
| `CARSHARING_FREE` | 73,7904 % | 99,6819 % | 2,1291 % |

Damit bestehen alle Komponenten beide technischen 60-%-Gates und sind
interpretierbar. Keine erreicht den vorab geforderten Mechanik-Gate von 50 %;
das Maximum ist `CAR` mit nur 4,3735 %. Die identischen Werte von
`PASSENGER`, `PEDESTRIAN`, `PUBLICTRANSPORT` und `CARSHARING_FREE` folgen aus
identischen Verfügbarkeitsmustern in diesem Lauf und sind ausdrücklich nicht
addierbar.

**Urteil und Stop-Regel:** Die sieben binären Modusverfügbarkeiten sind als
jeweils dominante Erklärung des OZ-000-Rests verworfen. Keine Dosis, Seeds,
Modalkalibrierung oder Produktänderung. Der nächste zulässige Same-run-Spike
darf die tatsächlich in der Zielwahl-Utility verwendeten Personenmerkmale
vorab zerlegen. AC-000 liefert weder Parameter noch Default. Rohlog,
Quellhashes und maschinenlesbare Evidenz liegen unter
`backend/data/campaigns/distance-decay-transfer-freiburg/choice-set-eligibility/ac-000/`.

#### Freiburg-Personenkomponenten (2026-08-24) — präregistriert

**Forensik und Frage vor dem Lauf:** Die generierte
`DestinationChoiceUtilityFunction` konsumiert Personendaten nur über sieben
getrennte Kodierungen: ein Altersband sowie sechs binäre Statusindikatoren.
PC-000 prüft, ob mindestens eine dieser tatsächlich verwendeten Komponenten
den überwiegenden Teil des nach OZ-000 verbleibenden Rests erklärt. Rohwerte
oder Kategorien, die in der Utility nicht vorkommen, sind ausgeschlossen.

Der einmalige Arm **PC-000** verwendet AC-000 unverändert: Freiburg, Seed 42,
J-0895-Zielwahlparameter, Basis-MNL, Helper `DISTANCE / 1000`, kein
Langwegbonus. Die sieben vorab fixierten Komponenten bilden die Utility-
Bedingungen exakt nach:

- `AGE_BAND`: `0_17`, `50_69`, `70_120`, sonst `BASELINE_OTHER`;
- `EDUCATION`: Employment ist `STUDENT`, `STUDENT_PRIMARY`,
  `STUDENT_SECONDARY`, `STUDENT_TERTIARY` oder `EDUCATION`;
- `HOME_OR_UNEMPLOYED`: Employment ist `HOMEKEEPER` oder `UNEMPLOYED`;
- `COMMUTER_TICKET`: `person.hasCommuterTicket()`;
- `CARS_PER_ADULT_AT_LEAST_ONE`: eigene Pkw geteilt durch Erwachsene im
  Haushalt, mit demselben Mindestnenner 1 wie im Helper, ist mindestens 1;
- `NO_OWNED_CAR`: Zahl eigener Haushalts-Pkw ist 0;
- `HIGH_ECONOMICAL_STATUS`: Code des ökonomischen Haushaltsstatus liegt in
  `[4;5]`.

Je Komponente werden ausschließlich `n`, `sum(p)` und `sum(p²)` pro
`(Aktivitätstyp, Ausgangszone, Komponentenwert)` akkumuliert. Jede Zerlegung
wird unabhängig mit Unterzellen `n>=5` ausgewertet und auf ihrer
Teilpopulation neu zentriert: `SSW_person = SSB_person +
SSW_within_person`, Toleranz `1e-9`. Die Komponenten überlappen und dürfen
nicht addiert oder als unabhängige Effekte gelesen werden.

**Technische Gates vor Interpretation:** Externer Lauf EXIT 0, kein Fallback
und keine Exception, genau eine Abschlusszeile, byteidentische Utility
`5b13ad11…9f454c`, byteidentischer J-RAW-Demand
`a58fac01…c7a9de` sowie exakte Reproduktion aller CE-/PD-/OZ-/AV-/AC-
Aggregate. Jede Komponententabelle enthält vor dem Zellfilter alle 19.138
freien Entscheidungen. Eine Komponente ist nur interpretierbar, wenn ihre
`n>=5`-Unterzellen mindestens **60 %** der Entscheidungen und ihre neu
zentrierte `SSW_person` mindestens **60 %** der vollständigen OZ-Restvarianz
`40,26032918334451` abdecken und ihre Identität bis `1e-9` hält.

**Vorab fixiertes Mechanik-Gate:** Eine technisch interpretierbare Komponente
ist nur ab `SSB_person / SSW_person >=0,50` ein dominanter nächster Kandidat.
Alle sieben Einzelquoten und die größte werden berichtet, aber nicht addiert.
Erreicht keine den Gate, gilt nur dann jede einzelne Kodierung als nicht
dominant, wenn alle sieben technisch interpretierbar waren; andernfalls ist
PC-000 unentscheidbar.

**Stop-Regel:** Genau ein PC-000-Lauf, nur Seed 42. Keine gemeinsame
Personensignatur, kein post-hoc Zellfilter, keine neue Kategorie,
Bonuskonstante, Dosis, Modalkalibrierung, Seed-/Regionenvariation oder
Produktänderung. Ein positiver Befund erlaubt erst eine neue Diagnose des
betroffenen Merkmals. Ein vollständig negativer Befund erzwingt neue
Mechanismusforensik statt einer weiteren Personen- oder Verfügbarkeitssonde.
PC-000 liefert keinen Parameter oder Default.

**Ausführung:** PC-000 kompiliert mit JDK 25 und endet extern mit EXIT 0 sowie
`BUILD SUCCESSFUL in 16s`. Genau eine Abschlusszeile enthält je Komponente
alle 19.138 Entscheidungen. Es gibt keine ungültige Wahrscheinlichkeit,
Exception oder keinen Fallback. Utility (`5b13ad11…9f454c`), J-RAW-Demand
(`a58fac01…c7a9de`) und sämtliche CE-/PD-/OZ-/AV-/AC-Aggregate werden exakt
reproduziert; alle sieben Varianzidentitäten schließen mit Abweichung 0.

| Komponente | Entscheidungen mit `n>=5` | OZ-Restvarianz abgedeckt | erklärt |
|---|---:|---:|---:|
| `AGE_BAND` | **50,9928 %** | 78,5814 % | 9,5722 % roh |
| `EDUCATION` | 70,3104 % | 96,0795 % | **6,8995 %** |
| `HOME_OR_UNEMPLOYED` | 68,0008 % | 92,8824 % | 0,9742 % |
| `COMMUTER_TICKET` | 67,6194 % | 82,9665 % | 1,3721 % |
| `CARS_PER_ADULT_AT_LEAST_ONE` | 63,8050 % | 88,1247 % | 1,9917 % |
| `NO_OWNED_CAR` | 77,0613 % | 100,0000 % | 0,0000 % |
| `HIGH_ECONOMICAL_STATUS` | 65,2001 % | 93,2238 % | 2,0895 % |

Die sechs binären Komponenten bestehen beide technischen 60-%-Gates und
verfehlen den 50-%-Mechanik-Gate klar; `EDUCATION` ist mit 6,8995 % ihr
Maximum. `AGE_BAND` besteht zwar die Restvarianzabdeckung, erreicht mit
9.759/19.138 Entscheidungen aber nur 50,9928 % statt mindestens 60 %. Sein
roher Anteil 9,5722 % wird deshalb nicht interpretiert.

**Urteil und Stop-Regel:** PC-000 ist insgesamt wegen der dünn besetzten
Altersbandzellen unentscheidbar, nicht negativ. Die sechs binären
Personenkodierungen sind jeweils als dominante Erklärung verworfen; für das
Altersband gilt das ausdrücklich nicht. Der Zellfilter wird nicht gesenkt,
und es folgen keine Dosis, Seeds, Modalkalibrierung oder Produktänderung. Ein
neuer Same-run-Spike darf nur die drei von der Utility tatsächlich getrennt
verwendeten binären Altersindikatoren vorab einzeln zerlegen. Rohlog,
Quellhashes und maschinenlesbare Evidenz liegen unter
`backend/data/campaigns/distance-decay-transfer-freiburg/choice-set-eligibility/pc-000/`.

#### Freiburg-Altersindikatoren (2026-08-24) — präregistriert

**Frage vor dem Lauf:** PC-000 ist allein wegen der gemeinsamen vierstufigen
Alterszerlegung unentscheidbar. Die Utility verwendet Alter jedoch nicht als
gemeinsame Kategorie, sondern als drei getrennte Indikatoren. AI-000 prüft
daher ausschließlich `AGE_0_17` (`0<=age<=17`), `AGE_50_69`
(`50<=age<=69`) und `AGE_70_120` (`70<=age<=120`) jeweils binär gegen ihren
Rest. Neue Altersgrenzen oder ein gemeinsamer Schlüssel sind ausgeschlossen.

Der einmalige Arm **AI-000** übernimmt PC-000 unverändert: Freiburg, Seed 42,
J-0895, Basis-MNL, `DISTANCE / 1000`, kein Bonus. Je Indikator werden nur
`n`, `sum(p)` und `sum(p²)` pro
`(Aktivitätstyp, Ausgangszone, Indikatorwert)` akkumuliert. Unterzellen zählen
weiter nur ab `n>=5`; ihre Elternmittel werden separat neu zentriert. Bis
`1e-9` gilt `SSW_age = SSB_age + SSW_within_age`. Die drei Anteile überlappen
und werden nicht addiert.

**Technische Gates:** EXIT 0, keine Exception oder Fallback, genau eine
Abschlusszeile, byteidentische Utility (`5b13ad11…9f454c`) und J-RAW-Demand
(`a58fac01…c7a9de`), exakte CE-/PD-/OZ-/AV-/AC-/PC-Reproduktion und je
Indikator vor Filter 19.138 Entscheidungen. Interpretierbar ist ein Indikator
nur mit mindestens 60 % Entscheidungs- und 60 % OZ-Restvarianzabdeckung sowie
Varianzidentität bis `1e-9`.

**Mechanik-Gate und Stop-Regel:** Ein Indikator ist nur ab 50 % erklärter
Restvarianz dominant. Alle drei müssen technisch interpretierbar sein, um bei
vollständig verfehltem Gate die Altersindikatoren insgesamt als nicht
dominant zu verwerfen; sonst ist AI-000 unentscheidbar. Genau ein Lauf, Seed
42; kein post-hoc Filter, keine weitere Alterszerlegung, Dosis,
Modalkalibrierung, Seed-/Regionenvariation oder Produktänderung. Ein negativer
Befund erzwingt neue Mechanismusforensik außerhalb von Verfügbarkeits- und
Personenmerkmalen. AI-000 liefert keinen Parameter oder Default.

**Ausführung und Urteil:** AI-000 endet mit EXIT 0 und `BUILD SUCCESSFUL in
15s`, genau einer Abschlusszeile, ohne ungültige Wahrscheinlichkeit,
Exception oder Fallback. Utility, Demand und sämtliche früheren Aggregate
werden exakt reproduziert; alle Identitäten schließen mit Abweichung 0.

| Indikator | Entscheidungen mit `n>=5` | OZ-Restvarianz abgedeckt | erklärt |
|---|---:|---:|---:|
| `AGE_0_17` | 71,3502 % | 97,3496 % | **5,5282 %** |
| `AGE_50_69` | 64,0401 % | 82,8165 % | 1,5368 % |
| `AGE_70_120` | 64,4947 % | 94,8987 % | 0,7127 % |

Alle drei Indikatoren bestehen beide technischen 60-%-Gates und verfehlen
den 50-%-Mechanik-Gate klar. Die Altersindikatoren sind damit als jeweils
dominante Resterklärung verworfen. Weitere Verfügbarkeits-, Personen- oder
Alterszerlegungen sowie Dosis und Produktänderung bleiben gesperrt. Der
nächste Schritt muss neue Mechanismusforensik außerhalb dieser Merkmalsklassen
präregistrieren. Evidenz liegt unter
`backend/data/campaigns/distance-decay-transfer-freiburg/choice-set-eligibility/ai-000/`.

#### Freiburg-Fixziel-Ankergeometrie (2026-08-24) — präregistriert

**Frage vor dem Lauf:** Erklärt die Entfernung vom aktuellen Ausgangspunkt
zum nächsten personengebundenen Fixziel mindestens 50 % der nach OZ-000
verbleibenden Varianz der freien Langwegmasse? Dieser situative Kandidat folgt
direkt aus der ausgeführten Utility: Für jede Alternative gehen Reisezeiten
und -kosten vom Kandidaten zum Ergebnis von
`person.nextFixedActivityZone(previousActivity)` über die `*_FIX`-Terme ein.
Der ebenfalls übergebene Zeitpunkt ist im fixierten Freiburg-Arm dagegen kein
unabhängiger Kandidat, weil die Matrixkonfiguration für Werktag, Samstag und
Sonntag von 0 bis 23 Uhr jeweils dieselben Dateien bindet.

Der einmalige Arm **FA-000** übernimmt AI-000 unverändert: Freiburg, Seed 42,
J-0895, Basis-MNL, `DISTANCE / 1000`, kein Bonus. Pro freier Entscheidung ist
`y` die bereits validierte MNL-Masse aller Ziele über 20 km. Der einzige neue
Prädiktor ist `x=log1p(d_origin,fix / km)`, wobei `d_origin,fix` über denselben
`ImpedanceIfc.getDistance(...)/1000`-Vertrag wie die Zielentfernung berechnet
wird. Je `(Aktivitätstyp, Ausgangszone)` werden ausschließlich `n`, `sum(x)`,
`sum(x²)`, `sum(y)`, `sum(y²)` und `sum(x*y)` akkumuliert; es werden keine
Einzelentscheidungen gespeichert und keine Distanzklassen nachträglich
gebildet.

Aus den OZ-Elternzellen mit `n>=5` und positiver Within-Cell-Varianz von `x`
werden `Sxx`, `Syy` und `Sxy` jeweils um das eigene Zellenmittel zentriert und
über alle zulässigen Zellen gepoolt. Die präregistrierte lineare
Within-Cell-Erklärung ist `R²=(Sxy²/Sxx)/Syy`; bis `1e-9` muss
`Syy = Sxy²/Sxx + SSE_residual` gelten. Das Vorzeichen der gepoolten Steigung
`Sxy/Sxx` muss positiv sein: weiter entferntes nächstes Fixziel soll die
Langwegmasse erhöhen, nicht senken.

**Technische Gates:** EXIT 0, keine Exception oder Fallback, genau eine
Abschlusszeile, byteidentische Utility (`5b13ad11…9f454c`) und J-RAW-Demand
(`a58fac01…c7a9de`), exakte CE-/PD-/OZ-/AV-/AC-/PC-/AI-Reproduktion, 19.138
Entscheidungen vor Filter, keine fehlende Fixzone sowie nur endliche,
nichtnegative Ankerdistanzen. Zulässige Zellen müssen mindestens 60 % aller
Entscheidungen und mindestens 60 % der OZ-Restvarianz `40,2603291833`
abdecken; sonst ist FA-000 vor jeder Mechanikinterpretation unentscheidbar.

**Mechanik-Gate und Stop-Regel:** Bestätigt ist der Fixziel-Anker als
dominanter nächster Kandidat nur bei positiver Steigung und `R²>=0,50`.
Verfehlt ein technisch gültiger Lauf dieses Gate, ist die skalare
Origin-Fixziel-Distanz als dominante Erklärung verworfen; daraus folgt weder,
dass die einzelnen `*_FIX`-Beiträge wirkungslos sind, noch dass sie verändert
werden dürfen. Genau ein Lauf, Seed 42; kein post-hoc Transformationswechsel,
keine weitere Geografie-, Personen- oder Verfügbarkeitssonde, Dosis,
Modalkalibrierung, Seed-/Regionenvariation oder Produktänderung. FA-000 liefert
keinen Parameter oder Default.

**Ausführung und Urteil:** FA-000 endet mit EXIT 0 und `BUILD SUCCESSFUL in
16s`, genau einer Abschlusszeile, ohne fehlendes Fixziel, ungültige
Ankerdistanz, Exception oder Fallback. Utility, Demand und sämtliche früheren
Aggregate werden exakt reproduziert; die Regressionsidentität schließt mit
Abweichung 0.

Von 1.293 wiederholt belegten OZ-Elternzellen haben 478 mit 3.485
Entscheidungen eine konstante Ankerdistanz. Die verbleibenden 815 Zellen decken
11.263/19.138 = **58,8515 %** statt mindestens 60 % der Entscheidungen ab.
Der SSE-Gate besteht mit 39,335809/40,260329 = 97,7036 %. Wegen des zuerst
greifenden Entscheidungsgates ist FA-000 **unentscheidbar**. Die rohe positive
Steigung `0,0334171` und der rohe Erklärungsanteil 13,2780 % werden nicht als
Mechanismusbeleg interpretiert.

Die Mindestabdeckung wird nicht nachträglich gesenkt, und es folgt keine neue
Transformation oder Klassierung derselben Ankerdistanz. Damit ist weder die
Fixziel-Utility bestätigt noch verworfen. Ein nächster Anlauf braucht eine
neue, vorab fixierte Mechanismusfrage, etwa eine direkte Zerlegung der
kandidatenbezogenen `*_FIX`-Utilitybeiträge statt eines weiteren skalaren
Distanz-Proxys. Dosis, Seeds und Produktänderung bleiben gesperrt. Evidenz
liegt unter
`backend/data/campaigns/distance-decay-transfer-freiburg/choice-set-eligibility/fa-000/`.

#### Freiburg-Fixziel-Utilitybeitrag (2026-08-24) — präregistriert

**Frage vor dem Lauf:** Erklärt der exakte kandidatenbezogene Beitrag der
beiden additiven `*_FIX`-Logsummen mindestens 50 % der nach OZ-000
verbleibenden Varianz der freien Langwegmasse? FU-000 ist keine Rettung der in
FA-000 unentscheidbaren Ankerdistanz: Es verwendet weder deren Wert noch eine
neue Transformation oder Klasse, sondern zerlegt die tatsächlich ausgeführte
Zielwahl-Utility an ihrer obersten Summenstruktur.

Der einmalige Arm **FU-000** übernimmt FA-000 unverändert: Freiburg, Seed 42,
J-0895, Basis-MNL, `DISTANCE / 1000`, kein Bonus. Für jeden Kandidaten `z`
wird zusätzlich zum unveränderten Vollnutzen `U(z)` exakt die Summe aus dem
Fixziel-PT-/Aktiv-Logsum-Term und dem Fixziel-Drive-Logsum-Term als `U_fix(z)`
berechnet. Der verhaltensneutrale Gegenlauf verwendet
`U_withoutFix(z)=U(z)-U_fix(z)` nur für eine zweite
Wahrscheinlichkeitsberechnung; ausgewählt wird weiterhin ausschließlich aus
`U(z)`.

Pro Entscheidung sind `p_full` und `p_withoutFix` jeweils die summierten
Wahrscheinlichkeiten aller Ziele über 20 km. Ohne Clipping muss für beide
`0<p<1` gelten. Der einzige Prädiktor ist der exakte Fixziel-Beitrag zu den
Lang-vs.-Kurz-Log-Odds
`x=logit(p_full)-logit(p_withoutFix)`; Zielgröße bleibt
`y=p_full`. Je `(Aktivitätstyp, Ausgangszone)` werden nur `n`, `sum(x)`,
`sum(x²)`, `sum(y)`, `sum(y²)` und `sum(x*y)` gesammelt. Aus Zellen mit
`n>=5` und positiver Within-Varianz von `x` werden lokal zentrierte `Sxx`,
`Syy` und `Sxy` gepoolt. Vorab gilt
`R²=(Sxy²/Sxx)/Syy` und bis `1e-9`
`Syy=Sxy²/Sxx+SSE_residual`; die Steigung `Sxy/Sxx` muss positiv sein.

**Technische Gates:** EXIT 0, keine Exception oder Fallback, genau eine
Abschlusszeile, J-RAW-Demand `a58fac01…c7a9de`, unveränderter Quelltext der
bestehenden `calculateU_destination`-Methode, exakte
CE-/PD-/OZ-/AV-/AC-/PC-/AI-/FA-Reproduktion und 19.138 Entscheidungen. Beide
Wahrscheinlichkeitskarten müssen endlich, nichtnegativ und je Entscheidung
bis `1e-12` auf eins normiert sein; beide Langmassen müssen strikt in `(0,1)`
liegen. Zulässige Zellen müssen mindestens 60 % aller Entscheidungen und 60 %
der OZ-Restvarianz `40,2603291833` abdecken; sonst ist FU-000 vor der
Mechanikinterpretation unentscheidbar.

**Mechanik-Gate und Stop-Regel:** Dominant ist der direkte Fixziel-
Utilitybeitrag nur bei positiver Steigung und `R²>=0,50`. Bei technisch
gültigem, aber verfehltem Gate ist genau diese direkte `U_fix`-Erklärung als
nächster Einzelmechanismus verworfen; bei gescheiterter Abdeckung bleibt sie
unentscheidbar. Genau ein Lauf, Seed 42; kein post-hoc Prädiktorwechsel, keine
Koeffizientenzerlegung, Dosis, Modalkalibrierung, Seed-/Regionenvariation oder
Produktänderung. FU-000 liefert keinen Parameter oder Default.

**Ausführung und Urteil:** FU-000 endet mit EXIT 0 und `BUILD SUCCESSFUL in
23s`, genau einer Abschlusszeile und ohne Exception oder Fallback. Die
bestehende `calculateU_destination`-Methode bleibt textuell unverändert;
Demand und sämtliche früheren Aggregate werden exakt reproduziert. Beide
Wahrscheinlichkeitskarten sind gültig und bis maximal `4,3299e-15` auf eins
normiert; es gibt keine ungültige Langmassenpaarung.

Nach `n>=5` und positiver Within-Varianz bleiben 1.274 Zellen mit
14.647/19.138 = **76,5336 %** der Entscheidungen. Sie decken
40,260329/40,260329 = praktisch **100 %** der OZ-Restvarianz ab; die
Regressionsidentität schließt mit Abweichung 0. Die gepoolte Steigung ist
positiv (`0,0174347`), doch `U_fix` erklärt nur **32,6632 %** statt mindestens
50 %. Der direkte kombinierte Fixziel-Utilitybeitrag ist damit als dominanter
Einzelmechanismus verworfen.

Deskriptiv erhöht das Entfernen beider FIX-Terme die mittlere Langwegmasse von
2,3376 % auf 6,8881 % (+4,5506 Prozentpunkte). Das belegt eine deutliche
Niveauwirkung, ersetzt aber nicht das vorab fixierte Varianzgate. Es folgt
keine post-hoc Zerlegung der beiden FIX-Logsummen und keine Dosis. Ein weiterer
Anlauf benötigt einen neuen, vorab fixierten Mechanismus außerhalb des
kombinierten Fixzielbeitrags. Evidenz liegt unter
`backend/data/campaigns/distance-decay-transfer-freiburg/choice-set-eligibility/fu-000/`.

#### Freiburg-Origin-Utilitybeitrag (2026-08-24) — präregistriert

**Frage vor dem Lauf:** Erklärt der exakte kandidatenbezogene Beitrag der
beiden ausgangsseitigen Nicht-FIX-Logsummen mindestens 50 % der nach OZ-000
verbleibenden Varianz der freien Langwegmasse? OU-000 ist keine nachträgliche
Aufteilung von FU-000: Die beiden dort gemeinsam geprüften `*_FIX`-Terme
bleiben unberührt. Der neue Kandidat liegt außerhalb dieses Beitrags und fasst
die zwei anderen, vom aktuellen Ausgang zum Ziel gerichteten Reiseimpedanz-
Logsummen der obersten Utility-Summenstruktur zusammen.

Der einmalige Arm **OU-000** übernimmt FU-000 unverändert: Freiburg, Seed 42,
J-0895, Basis-MNL, `DISTANCE / 1000`, kein Bonus. Für jeden Kandidaten `z`
wird zusätzlich zum unveränderten Vollnutzen `U(z)` exakt die Summe aus dem
ausgangsseitigen PT-/Aktiv-Logsum-Term einschließlich ÖV-Zugang/-Abgang und dem
ausgangsseitigen Drive-Logsum-Term als `U_origin(z)` berechnet. Der
verhaltensneutrale Gegenlauf verwendet
`U_withoutOrigin(z)=U(z)-U_origin(z)` ausschließlich für eine zweite
Wahrscheinlichkeitsberechnung; ausgewählt wird weiterhin nur aus `U(z)`.

Pro Entscheidung sind `p_full` und `p_withoutOrigin` jeweils die summierten
Wahrscheinlichkeiten aller Ziele über 20 km. Ohne Clipping muss für beide
`0<p<1` gelten. Einziger Prädiktor ist der von `U_origin` verursachte
Lang-vs.-Kurz-Log-Odds-Shift
`x=logit(p_full)-logit(p_withoutOrigin)`; Zielgröße bleibt `y=p_full`. Je
`(Aktivitätstyp, Ausgangszone)` werden nur `n`, `sum(x)`, `sum(x²)`, `sum(y)`,
`sum(y²)` und `sum(x*y)` gesammelt. Aus Zellen mit `n>=5` und positiver
Within-Varianz von `x` werden lokal zentrierte `Sxx`, `Syy` und `Sxy` gepoolt.
Vorab gilt `R²=(Sxy²/Sxx)/Syy` und bis `1e-9`
`Syy=Sxy²/Sxx+SSE_residual`; die Steigung `Sxy/Sxx` muss positiv sein.

**Technische Gates:** EXIT 0, keine Exception oder Fallback, genau eine
Abschlusszeile, J-RAW-Demand `a58fac01…c7a9de`, unveränderter Quelltext der
bestehenden `calculateU_destination`-Methode, exakte
CE-/PD-/OZ-/AV-/AC-/PC-/AI-/FA-/FU-Reproduktion und 19.138 Entscheidungen.
Beide Wahrscheinlichkeitskarten müssen endlich, nichtnegativ und je
Entscheidung bis `1e-12` auf eins normiert sein; beide Langmassen müssen strikt
in `(0,1)` liegen. Zulässige Zellen müssen mindestens 60 % aller Entscheidungen
und 60 % der OZ-Restvarianz `40,2603291833` abdecken; sonst ist OU-000 vor der
Mechanikinterpretation unentscheidbar.

**Mechanik-Gate und Stop-Regel:** Dominant ist der kombinierte
Origin-Utilitybeitrag nur bei positiver Steigung und `R²>=0,50`. Bei technisch
gültigem, aber verfehltem Gate ist genau diese direkte `U_origin`-Erklärung als
nächster Einzelmechanismus verworfen; bei gescheiterter Abdeckung bleibt sie
unentscheidbar. Genau ein Lauf, Seed 42; keine post-hoc Aufteilung in PT-/Aktiv-
und Drive-Logsum, kein Prädiktorwechsel, keine Koeffizientendosis,
Modalkalibrierung, Seed-/Regionenvariation oder Produktänderung. OU-000 liefert
keinen Parameter oder Default.

**Ausführung und Urteil:** OU-000 endet mit EXIT 0 und `BUILD SUCCESSFUL in
25s`, genau einer Abschlusszeile und ohne Exception oder Fallback. Die
bestehende `calculateU_destination`-Methode bleibt textuell unverändert;
Demand und sämtliche früheren Aggregate werden exakt reproduziert. Beide
Wahrscheinlichkeitskarten sind gültig und bis maximal `4,4409e-15` auf eins
normiert; es gibt keine ungültige Langmassenpaarung.

Nach `n>=5` und positiver Within-Varianz bleiben erneut 1.274 Zellen mit
14.647/19.138 = **76,5336 %** der Entscheidungen. Sie decken
40,260329/40,260329 = praktisch **100 %** der OZ-Restvarianz ab; die
Regressionsidentität schließt mit Abweichung 0. Die gepoolte Steigung ist
positiv (`0,0237359`), doch `U_origin` erklärt nur **5,1121 %** statt
mindestens 50 %. Der kombinierte ausgangsseitige Nicht-FIX-Utilitybeitrag ist
damit als dominanter Einzelmechanismus verworfen.

Deskriptiv erhöht das Entfernen beider Origin-Terme die mittlere Langwegmasse
von 2,3376 % auf 10,0636 % (+7,7261 Prozentpunkte). Das belegt eine starke
Niveauwirkung, ersetzt aber nicht das vorab fixierte Varianzgate. Es folgt
keine post-hoc Aufteilung in PT-/Aktiv- und Drive-Logsum und keine Dosis. Ein
weiterer Anlauf benötigt einen neuen, vorab fixierten Mechanismus außerhalb
der kombinierten Origin- und FIX-Beiträge. Evidenz liegt unter
`backend/data/campaigns/distance-decay-transfer-freiburg/choice-set-eligibility/ou-000/`.

#### Freiburg-Zielstruktur-Utilitybeitrag (2026-08-24) — präregistriert

**Frage vor dem Lauf:** Erklärt der exakte kandidatenbezogene Beitrag des
verbleibenden Zielstruktur-Blocks mindestens 50 % der nach OZ-000 verbleibenden
Varianz der freien Langwegmasse? DU-000 teilt weder die in FU-000 gemeinsam
geprüften FIX-Logsummen noch die in OU-000 gemeinsam geprüften Origin-Logsummen
auf. Der neue Kandidat ist der dazu disjunkte Rest der obersten Utility-
Summenstruktur: der person- und distanzmodifizierte Attraktivitäts-Logsum plus
die direkten `b_0_1`-, `b_1_2`- und `b_intrazonal`-Terme.

Der einmalige Arm **DU-000** übernimmt OU-000 unverändert: Freiburg, Seed 42,
J-0895, Basis-MNL, `DISTANCE / 1000`, kein Bonus. Für jeden Kandidaten `z`
wird zusätzlich zum unveränderten Vollnutzen `U(z)` der Zielstruktur-Beitrag
`U_destinationStructure(z)` exakt mit denselben Attraktivitäts-, Distanzband-
und Intrazonalwerten berechnet. Der verhaltensneutrale Gegenlauf verwendet
`U_withoutDestinationStructure(z)=U(z)-U_destinationStructure(z)` nur für eine
zweite Wahrscheinlichkeitsberechnung; ausgewählt wird weiterhin ausschließlich
aus `U(z)`.

Pro Entscheidung sind `p_full` und `p_withoutDestinationStructure` jeweils die
summierten Wahrscheinlichkeiten aller Ziele über 20 km. Ohne Clipping muss für
beide `0<p<1` gelten. Einziger Prädiktor ist der vom Zielstruktur-Block
verursachte Lang-vs.-Kurz-Log-Odds-Shift
`x=logit(p_full)-logit(p_withoutDestinationStructure)`; Zielgröße bleibt
`y=p_full`. Je `(Aktivitätstyp, Ausgangszone)` werden nur `n`, `sum(x)`,
`sum(x²)`, `sum(y)`, `sum(y²)` und `sum(x*y)` gesammelt. Aus Zellen mit
`n>=5` und positiver Within-Varianz von `x` werden lokal zentrierte `Sxx`,
`Syy` und `Sxy` gepoolt. Vorab gilt `R²=(Sxy²/Sxx)/Syy` und bis `1e-9`
`Syy=Sxy²/Sxx+SSE_residual`; die Steigung `Sxy/Sxx` muss positiv sein.

**Technische Gates:** EXIT 0, keine Exception oder Fallback, genau eine
Abschlusszeile, J-RAW-Demand `a58fac01…c7a9de`, unveränderter Quelltext der
bestehenden `calculateU_destination`-Methode, exakte
CE-/PD-/OZ-/AV-/AC-/PC-/AI-/FA-/FU-/OU-Reproduktion und 19.138 Entscheidungen.
Beide Wahrscheinlichkeitskarten müssen endlich, nichtnegativ und je
Entscheidung bis `1e-12` auf eins normiert sein; beide Langmassen müssen strikt
in `(0,1)` liegen. Zulässige Zellen müssen mindestens 60 % aller Entscheidungen
und 60 % der OZ-Restvarianz `40,2603291833` abdecken; sonst ist DU-000 vor der
Mechanikinterpretation unentscheidbar.

**Mechanik-Gate und Stop-Regel:** Dominant ist der Zielstruktur-Beitrag nur bei
positiver Steigung und `R²>=0,50`. Bei technisch gültigem, aber verfehltem Gate
ist genau diese direkte `U_destinationStructure`-Erklärung als nächster
Einzelmechanismus verworfen; bei gescheiterter Abdeckung bleibt sie
unentscheidbar. Genau ein Lauf, Seed 42; keine post-hoc Aufteilung in
Attraktivitäts-, Kurzband- und Intrazonal-Terme, kein Prädiktorwechsel, keine
Koeffizientendosis, Modalkalibrierung, Seed-/Regionenvariation oder
Produktänderung. DU-000 liefert keinen Parameter oder Default.

**Ausführung und Urteil:** DU-000 endet mit EXIT 0 und `BUILD SUCCESSFUL in
30s`, genau einer Abschlusszeile und ohne Exception oder Fallback. Die
bestehende `calculateU_destination`-Methode bleibt textuell unverändert;
Demand und sämtliche früheren Aggregate werden exakt reproduziert. Beide
Wahrscheinlichkeitskarten sind gültig und bis maximal `4,2188e-15` auf eins
normiert; es gibt keine ungültige Langmassenpaarung.

Nach `n>=5` und positiver Within-Varianz bleiben erneut 1.274 Zellen mit
14.647/19.138 = **76,5336 %** der Entscheidungen. Sie decken
40,260329/40,260329 = praktisch **100 %** der OZ-Restvarianz ab; die
Regressionsidentität schließt mit Abweichung 0. Die gepoolte Steigung ist
positiv (`0,0407584`), doch `U_destinationStructure` erklärt nur
**17,8117 %** statt mindestens 50 %. Der kombinierte Zielstruktur-
Utilitybeitrag ist damit als dominanter Einzelmechanismus verworfen.

Deskriptiv erhöht das Entfernen des Zielstruktur-Blocks die mittlere
Langwegmasse von 2,3376 % auf 2,9077 % (+0,5702 Prozentpunkte). Das ersetzt
nicht das vorab fixierte Varianzgate. FU-000, OU-000 und DU-000 bilden nach
Quellformel die vollständige Top-Level-Utility disjunkt ab: FIX erklärt
32,6632 %, Origin 5,1121 % und Zielstruktur 17,8117 % jeweils einzeln; kein
Block dominiert die Restvarianz. Es folgt keine post-hoc Einzeltermzerlegung.
Der nächste zulässige Anlauf muss einen gemeinsamen, vorab fixierten
Kompositions-/Kompensationsmechanismus der drei Blöcke prüfen. Evidenz liegt
unter
`backend/data/campaigns/distance-decay-transfer-freiburg/choice-set-eligibility/du-000/`.

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

## MobileCity-Zielformat TRAMIGO (2026-09-15) — Teil-Export bestätigt, Vollimport offen

> **Historischer Ausgangsstand:** Die folgende Bestandsaufnahme beschreibt den Export am
> 15. September 2026. Die danach umgesetzten Regional-, Quellen-, Netz-, Validierungs- und
> Umlandbausteine sind in den nachfolgenden MobileCity-Abschnitten fortgeschrieben.

Geprüft wurden der aktuelle `MobileCityExporter`, die fünf XLSX-Dateien, zwei Transit-CSVs
und `TRAMIGO_GIS.gpkg` aus dem bereitgestellten Ordner `22 TRAMIGO Mittlerer Oberrhein`
sowie Quellcode und mitgelieferter TRAMIGO-Importsatz des `MobileCityConfigTool` aus
`Network Pipeline`. Kommentare und Arbeitsaufträge innerhalb der Beispielmappen wurden
als Referenzinhalt behandelt. Es wurde keine Umsetzung dieser Arbeitsaufträge vorgenommen.

**Befund:** Das aggregierte Zielformat lässt sich aus SyntheSis bedienen. Der bestehende
Export ist jedoch ein Beitrag zu einer vollständigen CityConfig und kein eigenständig
importierbares MobileCity-Paket. Der aktuelle C#-CityConfig-Import liest **25 CSV-Dateitypen**;
Davon sind 24 zwingend; `TECRidepooling` ist im Reader optional.
SyntheSis lieferte zum Prüfzeitpunkt **6**, mit teilweise unvollständigen Tabellenblöcken. Die Aussage
aus dem früheren TECCarFleet-Befund, fehlende Teilbestände blieben einfach Takomat-Defaults,
ist als automatische Importfunktion nicht belegt und muss eingeschränkt werden.

### Zielformat und vorhandene Abdeckung

Die Hauptmappe `2026.01.07_MC_CityConfig_TRAMIGO.xlsx` enthält 24 Blätter einschließlich
`Contents` und `TEMPLATE`. Rad- und Transit-Nodes/Links liegen separat. XLSX ist hier das
Bearbeitungsformat; der mitgelieferte Importer liest benannte CSV-Dateien mit Epigene-
Tabellenblöcken und erzeugt anschließend `CityConfig.bin` per MessagePack. Die komplette
App-Konfiguration umfasst zusätzlich Governance- und Szenario-Binärdateien. Ein direktes
XLSX-Importverfahren ist im geprüften CityConfig-Reader nicht vorhanden.

| Bereich | SyntheSis heute | Abweichung / erforderlicher Beitrag |
|---|---|---|
| `StudyAreaConfig` | Jahresparameter, District-Anzahlen, Kartenzentrum | Basisjahr fest 2025; frühestes Jahr ebenfalls 2025, Ende 2050. Hauptreferenz: 2021 / 2025 / 2050. Jahresvertrag vereinheitlichen. |
| `REGdistricts` | `districtID,name,districtType`; intern 1, extern 0 | Referenz enthält zusätzlich `totalArea,settlementArea,urbanization` und Typ 2 für das Kerngebiet. Die drei Zusatzspalten werden vom geprüften District-Reader nicht gelesen; für das vollständige Referenzschema dennoch berücksichtigen. |
| `REGattractivity` | Vier Zwecke, je Zweck auf maximal 100 normalisiert | Format passend; Methode weicht von der Referenz ab: dort 25 % besuchergewichtete POIs und 75 % POIs/km². Gleiche Skala bedeutet keine gleiche Kalibrierung. |
| `POPinhabitants` | Drei Altersgruppen je District für 2025 | Liefert Stichprobenzahlen; Referenz enthält absolute Bevölkerungszeitreihen 2015–2045. Hochrechnung und Umgang mit Jahresverläufen fehlen. |
| `POPtrips` | Drei Altersgruppen × vier Zwecke, Wege pro Person und Tag aus Wochenplänen | Kernstruktur passend; nur 2025 statt Referenz 2016–2025. Raten nicht mit dem Bevölkerungshochrechnungsfaktor multiplizieren. |
| `TECCarFleet` | Nur `stock.total` je District für 2025 | Stichproben-Pkw; `registrations.total`, `registrations.shareElectric`, `stock.shareElectric` und `TECCarFleetParameters` fehlen. Bestands-/Antriebsdaten und Parameter gezielt aus einer abgestimmten Basis übernehmen. |
| District-Geometrie | Vier WGS84-Shapefile-Dateien mit `districtID` | Aktuell nur Lat/Lon-Begrenzungsrechtecke; weder TRAMIGO-Multipolygone noch echte projizierte Rasterpolygone. |
| `POPtripsSurrounding` | fehlt | Zweckabhängige Anteile der Umlandwege ins Untersuchungsgebiet; benötigt reale Umland-Districts mit Bevölkerung. |
| `INFcarNodes/Links`, `INFbikeNodes/Links`, `INFtransitNodes/Links` | fehlen | Netzaufbereitung über die mitgelieferten Pipelines oder einen eigenen Adapter. Nodes und Links müssen dieselben Districts wie Bevölkerung/Geometrie verwenden. |
| `INFpedestrian`, `INFdistricts`, `TECTravelDistance` | fehlen | Fuß-OD-Zeiten/-Distanzen, Zu-/Abgang und Tarifparameter sowie Streckenanteile innerhalb des Gebiets. Vorhandene Matrizen helfen, decken aber nicht alle Inhalte ab; MobileCity erwartet km und Minuten. |
| `REGchargers`, `TECTransit`, `TECCarsharing`, `TECRidepooling`, `TECEmissions`, `MODparameters`, `ENV`, `FIN`, `LAQ` | fehlen | Regionale Bestandsdaten, Modell-/Kosten-/Wirkungsparameter benötigen explizite Quellen oder abgestimmte Vorlagen. Nicht vollständig aus synthetischen Aktivitäten ableitbar. |
| Governance / Logistik | kein MobileCity-Export | Die Shuttle-Beispiele beschreiben Maßnahmen und ihren Zeitplan. Ein Vertrag für Paketmengen, Depots und Liefertouren ist darin nicht enthalten. `TECcargobikeSharing` bezeichnet Sharing und ersetzt keinen Logistikvertrag. |

`TECcargobikeSharing` ist in der Hauptmappe und im mitgelieferten Importordner vorhanden,
wird aber vom geprüften `ST2100_CityConfigTransformer` nicht als eigener CSV-Reader aufgerufen.
Es ist deshalb nicht in den 25 gezählten CityConfig-Dateitypen enthalten. Die sechs
SyntheSis-CSVs werden ergänzt durch vier Shapefile-Komponenten und `summary.json`.

### Nachgewiesene Hindernisse

1. **Vollimport scheitert bereits an fehlenden Dateien bzw. Tabellen.**
   `ST2122_CityConfigREGDistrictsReader` lädt unter anderem `REGchargers`, `TECCarsharing`
   und `INFdistricts`. `EpigeneCSVFileReader.GetCSV` wirft bei fehlender Datei.
   Auch `EpigeneCSVFile.TryGetValue` ruft zuerst `FindTable` auf; ein fehlender Tabellenblock
   wirft eine Exception. Ein `stock.total`-Export darf deshalb eine vollständige
   `TECCarFleet`-Vorlage nicht schlicht ersetzen. Zusätzlich fordert
   `ST2128_CityConfigTECCarFleetReader` acht Einträge aus `TECCarFleetParameters`.
   Benötigt wird eine Zusammenführung auf Tabellen-/District-/Jahresebene.

2. **Stichprobenumfang wird als Gesamtbestand ausgegeben.** Isolierte Sonde mit dem
   aktuellen `PopulationGenerator` und `MobileCityExporter`: eine Zone mit 1.000 Einwohnern,
   `samplingFraction=0.1`, Seed 42, `useKitPopulation=false`, `carsPerHousehold=1.0`.
   Erzeugt und exportiert wurden **100 Personen und 50 Pkw**. Eine Hochrechnung erfolgt
   nicht. `POPinhabitants` darf für einen vollständigen Gebietsexport nicht 100 Einwohner
   behaupten. Pkw benötigen eine explizite Gewichtung; kleine/leere Stichprobenzellen
   benötigen eine definierte Behandlung statt blindem Teilen durch den Stichprobenanteil.

3. **Jahre sind widersprüchlich.** Dieselbe Sonde schreibt in `household.csv` 2026
   (`PopulationGenerator.YEAR`), in `POPinhabitants` und `StudyAreaConfig` dagegen 2025
   (`MobileCityExporter.BASE_YEAR`). Das gewünschte Referenzjahr muss Bestandteil eines
   gemeinsamen Exportvertrags werden. Historie und Projektionen dürfen nicht erfunden werden;
   ein einzelnes Basisjahr beweist keine lauffähige Simulation bis 2050.

4. **Districts und Umland brauchen eine gemeinsame räumliche Definition.** Synthetische
   `SYN-NNN`-IDs sind laut dokumentierter Takomat-Rückmeldung vom 2026-07-03 zulässig.
   Sie passen aber nicht unmittelbar zu vorhandenen TRAMIGO-Netzen mit anderen District-IDs.
   Zum Weiterverwenden dieser Netze müssen unsere Daten auf deren Gebietspolygone aggregiert
   werden. Alternativ müssen alle zonenabhängigen Tabellen und Netze für unsere Districts
   neu entstehen. Die acht SyntheSis-Außenzonen haben absichtlich keine Einwohner und bilden
   damit keine vollständige Bevölkerung des MobileCity-Umlands ab.

5. **Shapefile-Format korrekt, Geometrie für LAEA-Zonen unvollständig.** Die binäre
   Struktur besteht den bestehenden Test. Eine zusätzliche Sonde mit einem aktuellen
   1-km-INSPIRE-Raster bei 8,40–8,41° E / 49,00–49,01° N zeigt unterschiedliche Eckpunkte
   zwischen `ShapefileWriter.writeRectangles` und `GeoJson.zonesToFeatureCollection`:
   Shapefile = WGS84-Bounding-Box, GeoJSON = tatsächliche projizierte Zelle.
   Für TRAMIGO sind Polygon-/Multipolygon-Geometrien und die Transformation aus
   EPSG:25832 (`TRAMIGO_GIS.gpkg`) nach WGS84 erforderlich.

### Referenzdateien sind unterschiedliche Bearbeitungsstände

- Die Hauptmappe enthält **99 Districts** (27 Typ 2, 56 Typ 1, 16 Typ 0), das GeoPackage
  und die Bevölkerungs-/Attraktivitätstabellen jeweils **98**. `DEB3H` steht zusätzlich
  in `REGdistricts`, fehlt aber in diesen anderen Quellen. Die in `StudyAreaConfig!B25:B26`
  stehenden Anzahlen 145/15 sind als nicht mehr verwendet kommentiert.
- Die vier TECCarFleet-Bestandstabellen enthalten für `FRF11` insgesamt **38 gespeicherte
  `#N/A`-Zellen** (`B136:K136`, `B237:K237`, `B338:J338`, `B439:J439`). Diese sind keine
  fachlich gültigen Nullwerte.
- Die separate Netzreferenz enthält **1.276 Rad-Nodes / 3.812 Rad-Links** und
  **7.615 Transit-Nodes / 10.106 Transit-Links**. Alle dort referenzierten Link-Endpunkte
  existieren. Das belegt noch keine Erreichbarkeit zwischen allen Districts.
- Im Pkw-Netz der Hauptmappe stehen **6.156 Nodes / 9.696 Links**, darunter vier zusätzliche
  Einträge mit bereits vorhandenem gerichtetem `(fromNodeID,toNodeID)`-Paar. Der aktuelle
  C#-Reader verwendet diesen Paar-Schlüssel in `Dictionary.Add`, sodass diese Duplikate
  beim direkten Übernehmen einen Konflikt erzeugen. Außerdem gehören Referenzknoten teils
  nicht zur District-Liste.
- Der mitgelieferte Ordner `MobileCityConfigTool/Includes/StudyAreas/Tramigo/Import` ist
  weiterverarbeitet: **alle 25 benötigten CSV-Dateitypen vorhanden**, 98 Districts,
  Pkw-Netz **4.075 Nodes / 4.893 Links** ohne doppelte gerichtete Paar-Schlüssel. Pkw,
  Rad und Transit enthalten für jeden dieser 98 Districts einen aktiven Referenzknoten,
  und alle Link-Endpunkte existieren. Im Radnetz liegen jedoch **21 Referenzknoten mit
  nicht in der District-Liste enthaltenen Zuordnungen**. Auch dieser Satz ist damit ein
  zu validierender Ausgangspunkt, kein bereits abgenommener Golden-Datensatz.

### Verifikation und nächste Umsetzung

**Verifiziert:** `cd backend && mvn -o test -Dtest=MobileCityExporterTest,ShapefileWriterTest`
bestand mit **2 Tests, 0 Fehlern, 0 Skips**. Isolierte Java-Sonden führten den aktuellen
Bestands- und Geometriecode aus. Eine separate Python-Auswertung emulierte die
Epigene-Blocktrennung, fand alle zehn fachlichen Tabellenblöcke der sechs erzeugten CSVs
und prüfte Datei-/Tabellenanforderungen sowie Referenz-IDs. XLSX-Zahlen stammen aus den
gespeicherten Werten; die Mappen wurden weder neu berechnet noch verändert.

**Nicht nachgewiesen:** erfolgreicher C#-Vollimport, MessagePack-Roundtrip oder
MobileCity-Simulationslauf. `dotnet` ist im geprüften PATH und den üblichen lokalen
Installationspfaden nicht verfügbar. Die Befunde zum Exception-Verhalten beruhen auf
dem mitgelieferten C#-Quellcode, nicht auf einer ausgeführten .NET-Konvertierung.

**Konsequenz / empfohlene Reihenfolge:**

1. Einen versionierten TRAMIGO-Importsatz mit konsistenten Districts als Basis festlegen
   und seine bekannten Referenzfehler bereinigen; Pflichtblöcke und Schlüssel validieren.
2. Die vorhandenen POP-/REG-/Pkw-Beiträge absichern: gemeinsame Jahre, absolute Bestände,
   Definition der Attraktivität, echtes District-Mapping und korrekte Polygongeometrien.
3. Beiträge gezielt in die Basis einfügen. Netz- und Modellparameter übernehmen oder
   über die mitgelieferten Pipelines erzeugen; Fußmatrizen und Umlandbeziehungen ergänzen.
4. In einer isolierten Kopie den echten Config-Tool-Import, Netzvalidierung, binäres
   Zurücklesen und einen MobileCity-Lauf im Basis- und Folgejahr durchführen.
5. Die spätere Logistik-Erweiterung an einen separat abgestimmten Tabellenvertrag anbinden.
   Für den hier geprüften Personenverkehrsexport ist dessen Fertigstellung keine Voraussetzung.

## MobileCity-Vorlagenexport (2026-09-17) — Import und Simulation nachgewiesen, Referenzprüfung offen

**Befund:** Der experimentelle Vorlagenmodus ergänzt einen vollständigen Importsatz.
Die Format- und Exportlücken aus der Prüfung vom 15. September sind für diesen Pfad
behoben. Eine fachlich fehlerfreie MobileCity-Konfiguration ist damit noch nicht belegt.

| Prüfung | Ergebnis / Konsequenz |
|---|---|
| Versionierte Basis | `tramigo-v2`: 25 CSV-Familien, Governance-Dateien und 98 District-Geometrien. SHA-256 je Quelldatei und vorbereiteter Datei; lokale Daten werden nicht eingecheckt. `mobilecity-contract.json` beschreibt die für diese Vorlage geforderten Blöcke, einschließlich des im nativen Reader optionalen Ridepoolings. |
| Referenzkorrekturen | District-Zähler 145→83 Untersuchungsgebiet, weiterhin 15 Umland. 21 Fahrradknoten mit Referenz auf nicht vorhandene Districts behalten ihren Netzknoten, verlieren aber `refNodeTrue`. |
| Hochrechnung | Bestände beruhen auf zugeteilter Gesamtbevölkerung je Zone und Alters-/Pkw-Anteilen ihrer Stichprobe. Leere Stichprobenzonen verwenden ausdrücklich protokollierte Ersatzanteile. Wege bleiben Raten pro Person und Tag. |
| District-Mapping | Flächenanteile in EPSG:3035 nach Polygonverschneidung; nur vollständig durch Region und Zonen abgedeckte Districts werden aktualisiert. Eine gleichmäßige Verteilung innerhalb der Rasterzelle ist eine Modellannahme. |
| Erhalt der Vorlage | Unbetroffene Districts und vorhandene andere Jahresstützpunkte bleiben erhalten. Für ein fehlendes Beitragsjahr werden Werte linear interpoliert bzw. Randwerte konstant fortgeführt; Personen/Pkw ganzzahlig gerundet. Attraktivitäten, Netze, Umlandbeziehungen und andere Modellparameter werden übernommen. Neue Daten sind keine neue Kalibrierung dieser Parameter. |
| Jahresvertrag | SyntheSis-Beitragsjahr und Haushaltsjahr standardmäßig 2026. `StudyAreaConfig.referenceYear` bedeutet im Fremdmodell **Simulationsstart** und bleibt hier 2018: direkt auf 2026 verschoben fehlt beim ersten Berechnungsschritt die Transitkapazität von 2025 (`GetCapacityUtilization`). Kein erfundener Ersatzwert; die historische Anlaufphase bleibt erhalten. |
| Tatsächlicher Export | Testbeitrag für `DE122-01` (Karlsruhe Innenstadt-Ost), 97 weitere Districts behalten ihre Bestände. Testdaten dienen dem technischen Nachweis, nicht einer fachlichen Karlsruher Prognose. 25 CSV-Familien und echte MultiPolygon-Shapefiles einschließlich Löchern, ZIP sowie Herkunftsnachweis. |
| Pkw-Übernahme im Modell | `CAL5020_TECCarFleet` übernimmt `stock.total` nur bei vorhandenem `stock.shareElectric` desselben Jahres. Der Export ergänzt deshalb ausschließlich für aktualisierte Districts den interpolierten/fortgeführten Referenzanteil. Ohne diese Ergänzung wurden im Test 1.293 statt exportierter 1.872 Pkw verwendet. Mit Ergänzung: **1.872→1.872**, Bevölkerung **5.617→5.617**; das Prüfskript prüft beide Zahlen im nativen Simulationsergebnis. |
| Echter Import / Zurücklesen | Der lokale .NET-8-Konverter erzeugt `CityConfig.bin`, `GovernanceConfig.bin`, `StartScenario.bin`. Ein separater Prozess lädt die Dateien und simuliert bis einschließlich 2026 und 2027. |
| Konverter-Korrekturen | Nur isolierte Arbeitskopie: fehlende Binärdateien beim Erstimport zulassen; Routingprüfung bis zur initialisierten Config verschieben; vor Prüfung beider Pkw-Matrizen auch beide berechnen. Das Prüfskript protokolliert Quell- und Patch-Hashes. Originale bleiben unverändert. |
| Verbleibende Datenfehler | Sieben aktive Transitkanten besitzen positive Distanz, aber weder Fahrzeit noch Geschwindigkeit. Dafür existiert auch keine direkte Gegenkante als belegter Ersatz. Die Simulation liefert außerdem zwölf nicht endliche Zellen in zwei Ergebnisdateien (`LAQ`, `seasonTicketRate`) für 2026/2027. Entsprechende NaN-Werte traten bereits im Baseline-Lauf ohne SyntheSis-Beitrag auf. |

**Evidenz:** Lokal liegen der erzeugte Importsatz unter
`validation/mobilecity/export-probe-v5/mobilecity/` und die isolierte Konverterprüfung
unter `validation/mobilecity/validation-probe-v5/`. Der maschinenlesbare
`validation-report.json` verknüpft Eingabe-/Tool-Hashes, Binärdateien, Fehler und
nicht endliche Ergebniszellen. Er bleibt bei solchen Befunden `failed`, auch wenn
Import und Simulation mit Exit-Code 0 enden. Ein erfolgreicher CSV-Export meldet
im Studio daher keine erfolgreiche Modellvalidierung.

**Software-Gates (2026-09-17):** `mvn -o test`: 169 Tests, 0 Fehler,
7 übersprungen (162 ausgeführt). Darunter neun MobileCity-/Shapefile-Tests ohne Skip.
Frontend: 44 Tests grün, `npm run build` erfolgreich; `git diff --check` sauber.
Diese Software-Gates ersetzen nicht die oben ausgewiesene Referenzvalidierung.

**Konsequenz:** Der Export kann für Formatabstimmung und technische Übergabe verwendet
werden. Für die Freigabe der mitgelieferten Referenz müssen die sieben Fahrzeiten und
die Ursachen der NaN-Ergebnisse mit belastbaren Daten bzw. Modellkorrekturen geklärt
werden. Die Logistik-Erweiterung bleibt ein separater Tabellenvertrag.

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

## MobileCity-Datenquellen (2026-09-18) — regionale Struktur und Pkw-Antriebe recherchiert

**Befund:** Zusätzliche amtliche Quellen können wesentliche Eingaben für einen eigenständigen
MobileCity-Export liefern. Zensus-Alters- und Haushaltsraster sowie KBA-Gemeindebestände wurden
heruntergeladen und inhaltlich geprüft. Die folgenden Anbindungen sind noch nicht im
Produktivcode umgesetzt. Recherche und Abrufprobe sind von einer vollständigen
Regionszuordnung und einem Exportnachweis zu unterscheiden.

### Priorisierte Quellen

| Priorität | Quelle | Auflösung / Stand | Einsatz und Abruf |
|---|---|---|---|
| 1 | [Zensus: Alter in fünf Altersklassen](https://www.destatis.de/static/DE/zensus/gitterdaten/Alter_in_5_Altersklassen.zip) | 100 m, 1 km, 10 km; Stichtag 15.05.2022 | Altersrandwerte je Zone; ZIP mit CSVs und Beschreibung. Download und 100-m-Header geprüft. |
| 1 | [Zensus: Größe des privaten Haushalts](https://www.destatis.de/static/DE/zensus/gitterdaten/Zensus2022_Groesse_des_privaten_Haushalts_in_Gitterzellen.zip) | 100 m, 1 km, 10 km; Stichtag 15.05.2022 | Haushaltsgrößenverteilung für die Synthese; Download und Header geprüft. |
| 1 | [KBA FZ 27, Juli 2026](https://www.kba.de/SharedDocs/Downloads/DE/Statistik/Fahrzeuge/FZ27/fz27_202607.xlsx?__blob=publicationFile&v=3), Blatt FZ 27.17 | Gemeinden; 01.07.2026 | Pkw-Gesamtbestand, BEV und Plug-in-Hybride, jeweils private/gewerbliche Halter; XLSX geprüft. |
| 2 | [Regionaldatenbank 12411-02-03-5-B](https://www.regionalstatistik.de/genesisws/downloader/00/tables/12411-02-03-5-B_00.csv) | Gemeinden; Abrufprobe beginnt mit 31.12.2024 | 17 Altersgruppen und Geschlecht zur Fortschreibung; öffentliches CSV, erster Dateiausschnitt geprüft. Kein Vollständigkeitsnachweis für alle Gemeinden/Jahre. |
| 2 | [BNetzA-Ladesäulenregister](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenkarte/start.html) | Einzelstandorte; Liste 01.09.2026 | Koordinatenbezogene Zuordnung zu Districts; CSV/XLSX. Zusätzlich dokumentierte tägliche öffentliche Schnittstelle. |
| 2 | [BA-Pendlerdaten](https://statistik.arbeitsagentur.de/DE/Navigation/Statistiken/Interaktive-Statistiken/Pendler/Pendler-Nav.html) | Gemeinden und Kreise; Jahresdaten | Umlandverflechtungen für Arbeit; interaktive Auswahl mit CSV-Download. Automatischer Abruf in dieser Recherche nicht getestet. |
| 3 | [MiD 2023, regionalisierte Ergebnisse](https://www.mobilitaet-in-deutschland.de/publikationen2023.html) | Kreise und Gemeinden ab 10.000 Einwohnern | Wegezahl, Tageskilometer, Mobilitätsquote und Modal-Split als Vergleichswerte; Gemeinde-XLSX geprüft. Modellschätzungen, keine amtlichen Bestandszahlen. |
| Grundlage | [BKG-Verwaltungsgebiete](https://gdz.bkg.bund.de/index.php/default/webdienste/verwaltungsgebiete.html) | Gemeindegrenzen und statistische Schlüssel; versionierter Gebietsstand | Räumlicher Join des Kartenausschnitts auf amtliche Schlüssel. Für feinere Zuordnung VG25 prüfen; VG250 ist generalisiert. |

### Zensus: direkte Alterszuordnung, Haushalte als eigene Grundgesamtheit

Die fünf CSV-Altersspalten lassen sich ohne Aufteilung von Altersintervallen zuordnen:
`Unter18` → Children, `a18bis29 + a30bis49 + a50bis64` → Adults,
`a65undaelter` → Seniors. Diese Grenzen entsprechen dem aktuellen
`MobileCityPopulation`-Code. Schlüssel ist `GITTER_ID_100m`; Zellmittelpunkte liegen in
EPSG:3035 vor. CSV-Trenner ist Semikolon.

Die Haushaltsdatei enthält `Insgesamt_Haushalte`, `1_Person` bis `5_Personen` sowie
`6_Personen_und_mehr`. Die offene obere Klasse darf nicht pauschal als sechs Personen
behandelt werden. Private Haushalte schließen Gemeinschaftsunterkünfte aus und verwenden
das gemeinsame Wohnen einschließlich Nebenwohnungen. Deshalb dürfen ihre Personensummen
nicht ungeprüft mit der Wohnbevölkerung gleichgesetzt werden.

Die heruntergeladenen Beschreibungen dokumentieren statistisch veränderte Zellwerte und
abweichende Summen. Im CSV-Test kommen positive Gesamtzahlen bei ausschließlich als „–“
ausgewiesenen Altersgruppen vor. **Adapterfolge:** Originalwerte erhalten, fehlende tragfähige
Struktur markieren und gegebenenfalls gröbere Raster-/Gemeindeanteile verwenden. Eine
anschließende Anpassung auf das Szenario-Gesamttotal ist eine dokumentierte Modellierung.

Weitere angebotene Merkmale sind Haushaltstyp, Familien mit Kindern und Seniorenstatus;
deren Dateien wurden noch nicht praktisch geprüft. Einstieg und zusätzliche Regionaltabellen:
[Destatis-Zensusangebot](https://www.destatis.de/DE/Themen/Gesellschaft-Umwelt/Bevoelkerung/Zensus2022/_inhalt.html).

### Pkw: FZ 27.17 bevorzugen, Portalquoten fachlich unterscheiden

Im geprüften KBA-Blatt `FZ 27.17`, Zeile 407, stehen für Rastatt am 01.07.2026:

| Haltergruppe | Pkw insgesamt | BEV | Plug-in-Hybrid |
|---|---:|---:|---:|
| privat | 27.035 | 598 | 381 |
| gewerblich | 3.467 | 907 | 352 |

Eigene Rechnung: privater BEV-Anteil `598 / 27035 = 2,2119 %`, privater PHEV-Anteil
`381 / 27035 = 1,4093 %`. Für die private Flotte ist diese Abgrenzung geeigneter als die
allgemeine Elektroquote. Firmenwagen, die privat genutzt werden, bleiben damit allerdings
außerhalb des privaten Zulassungsbestands.

**Offener Join:** Das XLSX liefert Gemeindenamen unter einem Zulassungsbezirksschlüssel,
keinen AGS pro Gemeindezeile. Zuordnung über Bezirkskontext und amtliches Gemeindeverzeichnis
aufbauen; Mehrdeutigkeiten ablehnen. Gebietsstände abgleichen. Nach kartografischem Zuschnitt
einer Teilgemeinde ist die Verteilung des Bestands auf Districts eine Schätzung.

Das [KBA-Statistikportal](https://das-kba-statistikportal.hub.arcgis.com/datasets/statistikportal::fz-pkw-mit-elektro-antrieb-regionen/about)
ist zusätzlich ohne Anmeldung per ArcGIS-REST abfragbar. Getestet: Tabellen-Metadaten,
Stichtage 2023.01 bis 2026.07, Rastatt Stadt (`08216043`) und Zulassungsbezirk (`08216`).
Die Stadt liefert zuletzt 7,3 % gemeinsame Elektroquote, aber keine getrennte BEV-Quote;
der Bezirk liefert 4,7 % BEV und 2,4 % PHEV. Die Portaldefinition umfasst auch Brennstoffzellen.
**Nicht direkt als `stock.shareElectric` übernehmen.**

Technischer Endpunkt: Item `42111224b0934144ae7187cf1a391737`, `FeatureServer/0`,
Service `FZ Pkw mit Elektro Antrieb Regionen` unter `services-eu1.arcgis.com/U09msXRZoxesNntH`.
Metadaten nennen maximal 2.000 Datensätze pro Antwort; Pagination berücksichtigen.
Numerische Felder haben separate `ZS_*`-Kennzeichen. `0` mit Kennzeichen `.` ist kein
Nullbestand; laut [KBA-Zeichenerklärung](https://www.kba.de/DE/Statistik/zeichenerklaerung_ab_201801.html)
ist der Wert unbekannt oder geheim. Prozentwerte sind etwa `7.3`, keine Anteile `0.073`.
Das Portal meldet DL-DE BY 2.0; Quelle, Stichtag und eigene Berechnung mitführen.

### Weitere Quellen und Integrationsregeln

- **Fortschreibung:** Regionalstatistik kann neuere Gemeinde-Altersränder liefern. Das
  Zensus-Raster bleibt die räumliche Ausgangsverteilung; die Übertragung neuer Ränder ist
  eine modellierte Fortschreibung. Datenjahr nicht durch das Szenariojahr ersetzen.
  GENESIS-Instanzen getrennt behandeln: Für die Bundes-API gelten seit Juli 2025 POST-
  Requests; das aktuelle [API-Handbuch](https://genesis.destatis.de/datenbank/online/docs/GENESIS-Webservices_Introduction.pdf)
  verlangt Authentifizierung. Der getestete Regionalstatistik-CSV-Download benötigt sie nicht.
- **Laden:** Die [BNetzA-Schnittstellenbeschreibung](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Schnittstellen/start.html)
  bestätigt einen täglich aktualisierten öffentlichen Datensatz. Für den ersten Adapter
  ist ein versionierter CSV-Snapshot ausreichend. Ladeeinrichtung, Ladepunkt und Stecker
  getrennt zählen; private Wallboxen und Vollständigkeit werden dadurch nicht belegt.
  Die veröffentlichte Liste verwendet CC BY 4.0.
- **Pendeln:** Die BA-Daten betreffen sozialversicherungspflichtige Beschäftigung.
  Erwerbspendelbeziehungen sind keine beobachteten täglichen Fahrten; Homeoffice und
  Wegehäufigkeit benötigen ein Verhaltensmodell. Einkauf/Freizeit benötigen eigene Ansätze.
- **Mobilität:** MiD-Small-Area-Werte liefern regionale Vergleichsgrößen, keine vollständige
  Altersgruppe-×-Wegezweck-Tabelle für `POPtrips`. Nach dem
  [Methodenbericht](https://www.mobilitaet-in-deutschland.de/pdf/MiD2023_SAE-Bericht.pdf)
  beziehen sie sich auf Wohnbevölkerung und modellierte Kennzahlen. Veröffentlichte
  regionale Vertiefungsergebnisse sind laut Datei vorzuziehen, wenn vorhanden.
- **Konsistenz:** Bestandsanteil und Neuzulassungsanteil sind verschiedene Größen.
  Die Übertragung von BEV/PHEV auf MobileCitys Antriebsklassen muss vor Integration am
  Zielmodell festgelegt werden. Ein Pkw-Elektroanteil darf nicht auf Lieferflotten übertragen
  werden. Quellenwahl, Abdeckung, Null-/Fehlwertstatus und manuelle Vorgaben persistieren.

**Nachweise:** Originaldateien, Download-URLs, SHA-256-Werte, REST-Antworten und
`research-check.json` liegen im lokalen, gitignorierten Verzeichnis
`validation/mobilecity/source-research-2026-09-18/`. Zensus-Archive wurden geladen und
stichprobenartig gelesen, kein deutschlandweiter fachlicher Abgleich durchgeführt.
Der Regionalstatistik-Test umfasst nur die ersten 8.000 Bytes. BNetzA/BA/BKG wurden anhand
der Primärdokumentation bewertet. Keine neuen produktiven Datenabrufe oder Modelländerungen.

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
5. **Sharing-/Ladeinfrastruktur bewusst leer** (präzisiert 2026-08-21): Der Punkt lautete
   zuvor, bei eigenen Zonen-IDs würden „die Rastatt-Stationen verworfen (nur WARNING)".
   Das kann in unserer Pipeline nicht eintreten: `LegacyExportGenerator.writeZoneRepository`
   schreibt `stations.csv`, `chargingdata.csv` und die Carsharing-Dateien **header-only**,
   ebenso `writeZoneProperties` die `bikesharing-properties.csv` — nachgeprüft an einem
   echten Lauf (`stations.csv` = 1 Zeile). Es gibt also keine Stationen, die verworfen
   werden könnten; Sharing und Laden sind schlicht nicht modelliert. Der ursprüngliche
   Befund stammt aus der tutorial-basierten Frühphase. **Offen bleibt nur**, falls
   Sharing-Modi je in Scope kommen: Der Legacy-Serializer referenziert Stationen über die
   zone-`id`, was mit eigenen Zonen-IDs einen Abbildungsschritt braucht.
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


## Zensus-Strukturgewichtung (2026-09-21) — Originaldateien bis MobileCity geprüft

**Hypothese und Vorabkriterien:** Durch Gewichtung ganzer KIT-Haushalte lassen sich
regionale Alters- und Haushaltsgrößenanteile gemeinsam annähern: maximal 2 Prozentpunkte
Abweichung der erwarteten Anteile und maximal 4 Prozentpunkte in einer Stichprobe von
20.000 Personen bei Seeds 42/43/44. Diese Kriterien galten vor dem Java-Lauf; sie sind
keine Garantie für kleine Zonenstichproben.

**Aufbau (Messung 18.09.2026):** Beide Originalarchive aus dem Quellenbefund, festgelegte
SHA-256-Prüfung, eine aggregierte Testzone für BBox 48,82–48,90° N / 8,15–8,25° E.
`CensusStructureSource` ordnete 1.224 Alterszellen und 1.130 Haushaltszellen zu.
Die Zielanteile unter 18 / 18–64 / ab 65 betrugen 17,5666 / 61,4209 / 21,0125 %;
Haushalte 1 / 2 / 3 / 4 / 5 / 6+ Personen: 39,3034 / 31,6383 / 13,7163 / 10,4492 /
3,1336 / 1,7592 %. Je Seed wurden 20.000 Personen erzeugt und die Alterszahlen im
MobileCity-Beitrag gegen die tatsächlich erzeugten Personen geprüft. Aktivitätsdatei
nur mit Header: Diese Probe belegt Bestandsübertragung, keine plausiblen Wegehäufigkeiten.

| Seed | Kinder / Erwachsene / Senioren im Export | Maximaler Stichprobenfehler über Alter und Haushaltsgrößen |
|---|---|---|
| 42 | 3.434 / 12.240 / 4.326 | 1,9258 Prozentpunkte |
| 43 | 3.535 / 12.311 / 4.154 | 1,7592 Prozentpunkte |
| 44 | 3.525 / 12.202 / 4.273 | 1,7592 Prozentpunkte |

**Befund:** Der maximale erwartete Fehler sank von 5,7114 auf 1,7592 Prozentpunkte;
die erwarteten Altersanteile trafen ihre Ziele auf unter 0,001 Prozentpunkte.
Der Restfehler kommt von den fehlenden 6+-Haushalten im KIT-Pool, nicht von einer
numerischen Ungenauigkeit. Gleicher Seed reproduziert die Personen-, Haushalts-,
Diagnose- und MobileCity-Bevölkerungsdateien in den Offline-Tests bytegleich.

**Beim Gegencheck gefunden und behoben:**

- Das Haushalts-ZIP enthält einen CP437-kodierten Beschreibungsdateinamen mit „ß“;
  ein UTF-8-ZIP-Leser bricht schon am Inhaltsverzeichnis ab. Der Adapter unterstützt
  nun die ZIP-Standardcodierung CP437 und explizit UTF-8-markierte Einträge.
- Eine nicht darstellbare Gruppe kann den maximalen Fitfehler konstant halten.
  Spätere Fits mit gleichem Maximalfehler werden weiter übernommen, damit sich die
  darstellbaren Altersanteile verbessern können.
- Unabhängiger Review: Bei Fallback waren zunächst verworfene Fit-Erwartungen als
  angewendete Erwartungen beschriftet, und Restkürzungen fehlten im Zähler. Beide Fälle
  sind korrigiert und durch einen gezielten Regressionstest abgesichert.

**Evidenz:** `CensusStructureTest`, zusätzlicher Fallback-Lauf in
`GenerationPipelineE2ETest`, UI-Test zum Abschalten der erforderlichen KIT-Quelle.
Lokale Originaldateiprobe und Ergebnisse unter
`validation/mobilecity/census-structure-spike/{CensusProbe.java,java/result.json}`
(ignorierter Arbeitsbereich; keine Voraussetzung für die regulären Offline-Tests).

**Abschlussprüfung (21.09.2026):** `mvn -o test`: 178 Testfälle, davon 171 erfolgreich
und 7 übersprungen, keine Fehler. `npm test`: 45 erfolgreich.
`npm run build`: Produktionsbuild erfolgreich. Abschließender unabhängiger Review:
beide Diagnosefehler behoben, keine weiteren bestätigten Fehler.

**Konsequenz:** Anbindung als abschaltbare experimentelle Option nutzbar. Sie erhält
vorhandene Haushaltszusammensetzungen, ersetzt jedoch keine vollständige regionale
Populationssynthese. Altersstruktur der Gesamtbevölkerung und Größen privater Haushalte
haben unterschiedliche statistische Grundgesamtheiten. 2022-Anteile werden ohne
Fortschreibung verwendet. Die anschließend ergänzte KBA-Anbindung ist im folgenden
Befund vom 22.09.2026 beschrieben.


<a id="mobilecity-regionalpaket-2026-09-22"></a>

## MobileCity-Regionalpaket (2026-09-22) — native Baseline technisch erfolgreich

**Befund:** Der neue Regionalmodus erzeugt ein vollständiges technisches Basispaket
außerhalb der TRAMIGO-Geografie. Das ersetzt keine Kalibrierung oder fachliche Abnahme.
Für die nachfolgenden Berliner Testgebiete wurden echte Java-Populations-, Aktivitäts-,
Matrix- und Exportstufen verwendet. Die Bevölkerung war synthetisch vorgegeben;
diese Proben behaupten keine beobachtete Berliner Bevölkerungs- oder Verkehrslage.

| Nachweis | Ergebnis |
|---|---|
| Eingabe | Berlin, 52,50–52,52° N / 13,40–13,43° E; neun Rasterzonen, eine unbewohnt; Seed 42, KIT-Haushalte, 8.000 Personen, 3.886 private Pkw. |
| Vier Districts | 25 CSV-Familien, eigene Geometrien und Verbindungsnetze, 3 % BEV als explizite Annahme. Native Übernahme aller 8.000 Personen und 3.886 Pkw; BEV-Anteile innerhalb 0,00001 absoluter Toleranz. |
| Ein District | Gleiche Bestände, manuell 0 % BEV; keine Inter-District-Kanten notwendig. Native Übernahme ebenfalls erfolgreich. |
| Gesamter exportierter Horizont | Beide Pakete: echter Import, binäres Zurücklesen und Simulation 2026–2036; jeweils 0 Fehler und 0 nicht endliche Zellen. Bestands-/BEV-Vergleich bezieht sich auf 2026; die Endlichkeitsprüfung umfasst alle ausgegebenen Jahre. |
| Governance | Nur leerer, neu erzeugter Zeitplan. Keine TRAMIGO-Maßnahmen oder fremden Node-/Link-IDs; native Baseline funktioniert ohne diese Definitionen. |
| Regionaldatenquelle | Offline-Test mit tatsächlicher BKG-Gemeindeantwort für Rastatt und gepinnter KBA-Tabelle: privater BEV-Anteil 598/27.035; PHEV 381/27.035 separat. Fehlende/mehrdeutige Zuordnung, unvollständige WFS-Antwort und frühere Bezugsjahre erzeugen keine erfundene Null. |
| Reproduzierbarkeit/Fehlerfall | Gleiches Szenario und gleiche Eingaben ergeben bytegleiches ZIP. Fehlender BEV-Anteil lässt einen bestehenden Export unverändert. Matrix-IDs, Einheiten und nicht endliche Werte werden geprüft. |

**Im Review gefunden und behoben:** Flächen angeschnittener Grid-Zellen kommen aus
projizierten Polygonen statt aus der nominellen Zellgröße. Finanzskalierung verwendet
nur StudyArea-Districts im Basisjahr des hashgeprüften Profils; sparse Zukunftswerte und
Umland sind ausgeschlossen. Attraktivitäten verwenden die tatsächlichen Unterkategorien
für Bildung/Einkauf/Freizeit. Native Integer-Geschwindigkeitsgrenzen werden aufgerundet.
Wiederverwendete Exporter behalten keinen ausgeschalteten BEV-Fallback aus einem Vorlauf.

**Native Modellgrenzen:** Fehlende Erwachsene, null Wege oder kein Pkw-Gesamtbestand
können Null-Divisoren erzeugen und werden für Regionalpakete abgelehnt. Zusätzlich enthält
`CAL6010_SOC` eine Ganzzahldivision, die bei konstant 1 bis unter 2 Pkw je Einwohner eines
Districts ab dem dritten Jahr NaN erzeugt. Der Export blockiert diesen Bereich und nennt
den Grund; er verändert keine Bestandsdaten. Das Prüfskript verlangt deshalb mindestens
drei Jahre (`--years`, Default 3). Die hier geprüften Pakete liegen außerhalb dieses Bereichs.

**Evidenz:** Lokale, ignorierte Arbeitsdateien unter
`validation/mobilecity/regional-spike/`: `RegionalProbe.java`, `SingleDistrictProbe.java`,
`java-export/`, `single-export/`, `native-java-v3/validation-report.json` und
`native-single-v2/validation-report.json`. Berichte enthalten Eingabe-/Tool-/Binärhashes,
Konverter-Anpassungen und native Bestands-/BEV-Prüfungen. Keine Simulationsgleichungen
wurden angepasst; technische Bootstrap-/Routingprüfungs-Anpassungen sind protokolliert.

**Software-Prüfungen:** `MobileCityRegionalExporterTest` deckt Summenerhalt, alle internen
Zonen einschließlich leerer Randzonen, Polygonflächen, Profilbasisjahr ohne Umland,
Governance-Ausschluss, korrekte Attraktivitäten, Integer-Geschwindigkeit, native
Null-Divisoren, Matrixfehler und wiederholte Ausgabe ab. `MobileCityFleetSourceTest`
prüft Quelle, Geometriezuordnung, Missing-vs.-Zero und Fallback. Frontend-Test prüft
gegenseitigen Ausschluss von Regional- und Vorlagenmodus samt passender Anleitung.
Gesamtsuite: 185 Backend-Testfälle, davon 178 erfolgreich und 7 übersprungen;
46 Frontend-Tests und Produktionsbuild erfolgreich.

**Konsequenz:** Das Regionalpaket ist für technische Übergabe und weitere Modellierung
nutzbar. ÖV bleibt eine synthetische Angebotsannahme; Netze sind aggregierte Verbindungen,
Ladepunkte/Sharing null als Annahme, keine Umlandbevölkerung/Logistik. Koeffizienten,
Finanzen und zeitliche Verläufe sind keine beobachtete regionale Prognose. Jedes neue
Gebiet benötigt seine eigene native Prüfung. Der separate TRAMIGO-Vorlagenmodus behält
die am 17.09.2026 dokumentierten Referenzfehler. Priorisierter Ausbau und fachliche
Abnahme stehen im [Konzept](06-gta-integration-konzept.md#mobilecity-naechste-schritte).

## MobileCity-Datenstatus und optionale ConfigTool-Stufe (2026-09-22) — umgesetzt

**Prüffrage:** Kann ein Nutzer vor dem Export erkennen, welche Regionaldaten automatisch
aufgelöst, manuell gesetzt, als Fallback verwendet oder noch nicht modelliert sind, und kann
ein vollständiges Paket anschließend ohne Verwechslung von Export- und Simulationsstatus im
echten ConfigTool geprüft werden?

**Ergebnis:** Der Regionalexport schreibt `mobilecity/data-inventory.json` und bettet denselben
Inhalt in `summary.json` ein. Elf Bereiche führen Status, Auflösungsweg, Quelle, Bezugsstand,
Abdeckung, Wert, Einheit und Erläuterung. Ein kanonisches Eingabeabbild deckt Gebiet, Jahr,
Zonierung, Bevölkerung, Flotte und Profilannahmen ab; das Studio markiert ein dazu nicht mehr
passendes Ergebnis als veraltet. Die tatsächlich verwendete BKG-Grenzantwort wird neben dem
gepinnten KBA-Stand und einem vorhandenen Zensus-Strukturbericht im Paket gesichert bzw.
gehasht. Der deterministische ZIP-Test umfasst auch diese Dateien.

Stufe 10 `MobileCity-Validierung` ist pro Szenario standardmäßig aus. Bei Aktivierung führt
`MobileCityValidationRunner` das bestehende native Prüfskript in einem neuen temporären
Arbeitsverzeichnis aus. `status.json` unterscheidet `passed`, `failed` und `skipped`; ein
neuer Lauf ersetzt einen älteren Status atomar. Fehlende lokale Werkzeuge werden übersprungen.
Timeout, Prozessfehler oder ein negativer nativer Bericht lassen den Job fehlschlagen und
bewahren `validation-report.json`, `run.log` sowie vorhandene Build-, Import-, Netz- und
Simulationslogs. Damit kann ein erzeugtes CSV-Paket allein keinen grünen Prüfstatus erzeugen.

**Evidenz:** `MobileCityRegionalExporterTest` prüft Dateninventar, wirksame manuelle Werte,
offene Logistik, Summary-Identität und ZIP-Inhalt. `MobileCityFleetSourceTest` prüft den exakten
BKG-Snapshot sowie Offline-/Manuellpfade. `MobileCityValidationRunnerTest` verwendet einen
kontrollierten Prozessersatz und prüft Erfolgsbericht, Fehlerbeweissicherung und das Entfernen
eines alten grünen Berichts beim Überspringen. `ScenarioControllerTest` erzwingt Zielmodell und
vollständigen Exportmodus. Der Studio-Test prüft Vorschau, Veraltet-Markierung, Checkbox und
separaten Erfolgsstatus. Die reale ConfigTool-Kette bleibt durch die direkt zuvor dokumentierten
2026–2036-Läufe für Ein- und Vier-District-Pakete belegt.

**Abschlussprüfung:** `mvn -o test`: 188 Testfälle, davon 181 erfolgreich und 7 wegen nicht
gesetzter optionaler Integrationsumgebungen übersprungen, keine Fehler. `npm test`: 48/48
erfolgreich. `npm run build`: Produktionsbuild erfolgreich. Python-Skripte kompiliert;
`git diff --check` ohne Befund.

**Konsequenz zum Abschluss von Arbeitspaket 1:** Die Anzeige nennt weiterhin Umland,
Logistik, reales ÖV-Angebot sowie Lade-/Sharingdaten als offen bzw. angenommen. Der damals
nächste Adapter war das reale GTFS-Angebot; dessen technische Umsetzung dokumentiert der
folgende Befund. Eine regionale Kalibrierung oder fachliche Abnahme durch Takomat ist damit
weiterhin nicht behauptet.

## MobileCity-GTFS-Netzadapter (2026-09-22) — technisch umgesetzt, regionale Abnahme offen

**Prüffrage:** Lässt sich ein regionaler GTFS-Fahrplan ohne SUMO- oder Python-Abhängigkeit in
die Transit-Nodes/-Links eines beliebigen MobileCity-Regionalpakets überführen, sodass der
native Reader alle Districts erreicht und die Mehrjahressimulation endlich bleibt?

**Formatbefund:** Der native Graph ergänzt zu jeder importierten Kante selbst die Rückrichtung.
`freq` ist laut Referenztabelle die Zahl der Abfahrten pro Werktag; der Dijkstra-Handler
rechnet daraus über einen 16-Stunden-Betriebstag die Wartezeit. Richtungsduplikate würden
deshalb Kanten verlieren oder das Angebot doppelt zählen. Der Adapter aggregiert zunächst
gerichtet und exportiert je ungerichtetem Haltestellenpaar die größere Richtungsfrequenz.
Für Fußtransfers und District-Zugänge wird `freq=138` verwendet: Damit liegt der rechnerische
Takt unter der nativen Mindestschwelle von sieben Minuten und es entsteht kein künstlicher
Wartezeitaufschlag auf den Fußweg.

**Umsetzung:** `GtfsSource.resolveFullDay` nutzt Upload vor Registry/Cache und behält nun auch
`frequencies.txt`. `MobileCityGtfsAdapter` wertet Kalenderausnahmen und Zeiten über 24 Uhr aus,
wählt deterministisch einen repräsentativen Mittwoch, fasst Plattformen über
`parent_station` zusammen, berechnet Haltestellenfolgen, GTFS-Transfers und die Fahrplan-
Spitzenflotte und übernimmt die Route-Type-Kodierung aus der beigefügten Network Pipeline.
Nur die größte Servicekomponente wird exportiert; jeder District erhält einen eigenen
Referenzknoten mit Fußanschluss an die nächste Haltestelle dieser Komponente. Der genaue
Feed und `transit-provenance.json` werden in das Import-ZIP aufgenommen. Ein fehlender
Auto-Feed darf nur bei `allowSyntheticTransitFallback=true` auf das offengelegte Matrixnetz
zurückfallen. Ein ausgewählter, aber defekter Upload ist ein Fehler.

| Nachweis | Ergebnis |
|---|---|
| Kontrollierter Java-Adaptertest | Kalenderausnahme, Frequenzfahrt, Gegenrichtung, Bus-/Bahn-Mapping, Transfer, zwei District-Zugänge, Spitzenflotte und Quellbericht bestanden. |
| Vollständiger Exporttest | GTFS ersetzt die synthetischen Transitdateien, `TECTransit` übernimmt Peak-Bus/-Bahn, Feed und Bericht liegen im deterministischen ZIP, Dateninventar nennt GTFS als Quelldatum. |
| Echter ConfigTool-Lauf | Vier Districts, eine Bus-Ringlinie plus Bahnrelation: Import und Netzprüfung melden für Transit keine NaN/Infinity; Simulation 2026–2028 erzeugt 92 CSV-Ausgaben, 0 Fehler und 0 nicht endliche Zellen. Alle vorhandenen Bestandsprüfungen bleiben grün. Bericht und Logs: `validation/mobilecity/gtfs-spike/native-v1/` (lokale, gitignorierte Evidenz). |
| Reales KVV-GTFS Rastatt | Feedstand 01.07.2026, Gültigkeit 11.01.–12.12.2026; ausgewählter Verkehrstag 01.07.2026. 722 tägliche Fahrten, 544 Knoten, 673 Angebotskanten und 210 Transfers wurden verarbeitet, einschließlich GTFS-Typen Tram, Bahn und Bus. Die Spitzenbelegung beträgt je 29 Bus- und Schienenfahrzeuge. |

**Abschlussprüfung WP2:** `mvn -o test`: 193 Testfälle, davon 186 erfolgreich und 7 wegen
nicht gesetzter optionaler Integrationsumgebungen übersprungen, keine Fehler. Die fokussierte
GTFS-/Regionalexport-/Quellen-/Szenarioprüfung umfasst 23 grüne Tests. `npm test`: 48/48
erfolgreich; `npm run build`: Produktionsbuild erfolgreich. `git diff --check` ohne Befund.

**Grenzen:** Der reale KVV-Lauf belegt Parser und Größenordnung, ersetzt aber noch keinen
Relationsvergleich mit Fahrplanauskunft. Stadt-, Land- und Randlage müssen separat abgenommen
werden. Luftlinienentfernung zwischen Haltestellen ersetzt vorerst Shape-Länge; Fahrzeit und
Frequenz stammen aus dem Fahrplan. Kapazitäten, Tarifzonen und regionale Preisparameter sind
nicht in GTFS enthalten. Die Bahn-/Tram-Sperre des SUMO-Pfads gilt hier nicht, weil MobileCity
auf dem abstrakten Transitgraphen routet. Eine fachliche Abnahme durch Takomat steht aus.

**Konsequenz:** Der technische Kern von Arbeitspaket 2 ist integriert. Als nächstes folgen
die drei Regionstypen samt Relationsprüfung. Die anschließende Klärung und Anbindung von
`REGchargers` dokumentiert der folgende Befund.

## MobileCity-BNetzA-Ladepunktadapter (2026-09-22) — technisch umgesetzt

**Prüffrage:** Welche Einheit erwartet `REGchargers`, lässt sie sich für ein frei gewähltes
deutsches Gebiet aus dem öffentlichen BNetzA-Register bilden, und übernimmt der native Kern
die Werte, ohne die weitere Entwicklung versehentlich festzuschreiben?

**Zielvertrag:** Die beigefügte Network Pipeline bezeichnet
`numberOfChargingStations` als gesamte Zahl der Ladepunkte bzw. Kapazität aller AC- und
DC-Typen je District und Jahr. `ST2122_CityConfigREGDistrictsReader` liest die Zelle als
nullable Ganzzahl. `CAL5020_TECCarFleet` verwendet einen vorhandenen Wert direkt; bei einer
leeren Zelle berechnet es den Folgejahresbestand aus E-Pkw-Bestand, Sättigungsparametern und
Vorjahresbestand. Der irreführende Feldname bedeutet daher weder Standorte noch Stecker.

**Quelle und Normalisierung:** Verwendet wird die offizielle
[BNetzA-Liste vom 01.09.2026](https://data.bundesnetzagentur.de/Bundesnetzagentur/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenregister_BNetzA_2026-09-01.csv).
Laut [Schnittstellenbeschreibung](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Schnittstellen/start.html)
ist die öffentliche Liste monatlich, die API täglich aktualisiert. Eine CSV-Zeile steht für
eine Ladeeinrichtung; exportiert wird die Summe aus `Anzahl Ladepunkte`. Das Register umfasst
nur öffentlich zugängliche, abgeschlossene Meldungen und ist kein Vollständigkeitsnachweis
für sämtliche Ladeinfrastruktur. Lizenz: CC BY 4.0, Namensnennung `bundesnetzagentur.de`.

`scripts/prepare-bnetza-charging.py` prüft den Originalhash
`cdd8e38f1fa7e8512bd97caa8cf77e9f875436be1f8fe3fe1f2bd9789a504c5b`, entfernt
Betreiber-/Kontaktdaten und erzeugt einen deterministischen, komprimierten Snapshot. Er
enthält 116.422 Einrichtungen und 209.096 öffentliche Ladepunkte, davon 150.278 an Normal-
und 58.818 an Schnellladeeinrichtungen. Eine Zeile mit Koordinaten außerhalb des
Deutschland-Guards wird ausgewiesen und verworfen. Der normalisierte Inhalt hat SHA-256
`24d386acdb239607b9b707b294b537d54d7e8e444c441a5448e9ff170d667fe5`.

**Umsetzung:** `MobileCityChargingSource` lädt und prüft den Snapshot, ordnet jeden Punkt
per räumlichem Index und tatsächlicher District-Geometrie zu und löst Grenztreffer
deterministisch über die sortierte District-ID. `REGchargers` erhält kumulative Werte nach
Inbetriebnahmejahr bis zum beobachtbaren Bezugsjahr. Diese Historie ist eine
Surviving-Stock-Untergrenze, weil ein aktueller Snapshot außer Betrieb genommene frühere
Anlagen nicht vollständig rekonstruiert. Beginnt ein Szenario nach dem Snapshot, wird der
Snapshotbestand nur bis zum ersten Modelljahr getragen. Alle späteren Zellen bleiben leer
und aktivieren den endogenen MobileCity-Ausbau. Optional verteilt
`chargingPointsPer1000` eine explizite Annahme nach District-Bevölkerung; bei aktivierter
Automatik dient sie nur als Quellenfallback. Ohne Quelle oder Annahme bleibt der Wert
unbekannt und wird nicht als Null ausgegeben.

Das Paket enthält `charging-provenance.json` und bei automatischer Auflösung den exakt
verwendeten regionalen Ausschnitt `charging-points.csv`, jeweils mit Hash. Das
`data-inventory.json` führt Ladepunkte und Sharing getrennt. Das Studio zeigt Quelle,
Fallback und Einheit; ein Regions- oder Parameterwechsel markiert das Ergebnis als veraltet.

| Nachweis | Ergebnis |
|---|---|
| Adaptertests | Räumliche Zuordnung, deterministischer Grenzfall, kumulative Historie, leere Zukunft, später Modellstart, manueller Quellenfallback sowie Snapshot-Gesamtsummen bestanden. |
| Vollständiger Exporttest | BNetzA-Ausschnitt, Herkunftsbericht und ZIP-Einträge vorhanden; für das Berliner Testgebiet ist die wirksame Ladepunktzahl größer null. |
| Echter ConfigTool-Lauf | Vier Districts mit 11, 9, 14 und 6 Ladepunkten im Bezugsjahr 2026 wurden im isolierten Kern exakt wiedergefunden. Leere Zellen 2027–2028 aktivierten den nativen Folgepfad. 92 Ergebnis-CSV-Dateien, 0 Prozessfehler und 0 nicht endliche Zellen. Bericht und Logs: `validation/mobilecity/charging-spike/native-v1/` (lokale, gitignorierte Evidenz). |

**Abschlussprüfung Ladepunktadapter:** `mvn -o test`: 198 Testfälle, davon 191
erfolgreich und 7 wegen nicht gesetzter optionaler Integrationsumgebungen übersprungen,
keine Fehler. Die fokussierte Ladepunkt-/Regionalexport-/Quellen-/Szenarioprüfung umfasst
23 grüne Tests. `npm test`: 48/48 erfolgreich; `npm run build`: Produktionsbuild
erfolgreich. Der reale native Lauf belegt zusätzlich die exakte Übernahme der vier
Bezugsjahreswerte und den endogenen Folgepfad.

**Grenzen:** Die Automatik ist auf Deutschland beschränkt. Das Register bildet öffentliche
Ladepunkte ab, nicht private Wallboxen; sein Veröffentlichungsstand belegt keine vollständige
Marktabdeckung. Leistung und Normal-/Schnellladetyp bleiben im Quellsnapshot erhalten, das
aktuelle MobileCity-Zielfeld konsumiert jedoch nur die Anzahl. Eine fachliche Abnahme über
Stadt-, Land- und Grenzräume steht aus.

## MobileCity-Carsharing-Annahmen (2026-09-22) — technisch umgesetzt

**Prüffrage:** Welche Sharing-Größen konsumiert MobileCity tatsächlich, können sie für ein
beliebiges Gebiet belastbar automatisch bezogen werden, und wie lässt sich die bestehende
Nullannahme fachlich transparent durch den Nutzer ersetzen?

**Zielvertrag:** `ST2127_CityConfigTECCarsharingReader` liest `carsharing.member` als
gebietsweite absolute Mitgliederzahl je Jahr. `ST2122_CityConfigREGDistrictsReader` liest
`carsharing.stock.total` als Fahrzeugbestand je District und Jahr. `CAL4010_MODTrips`
verwendet daraus den Mitgliederanteil an der Gesamtbevölkerung und die Fahrzeugzahl je
1.000 Einwohner im Ursprungs-District für die Moduswahl. Fehlende Jahreswerte lösen
Regression bzw. Fortschreibung aus; deshalb exportiert SyntheSis die gewählte Annahme
bewusst konstant statt aus einem einzigen Punkt einen Trend zu erfinden. Der native Kern
begrenzt den Mitgliederanteil auf 40 Prozent.

**Quellenentscheidung:** Das
[bcs-Städteranking 2024](https://carsharing.de/carsharing-staedteranking) veröffentlicht
Fahrzeugzahlen und Fahrzeugdichten für deutsche Kommunen ab 50.000 Einwohnern. Es deckt
damit beliebige Kartenausschnitte und kleinere Gemeinden nicht vollständig ab und enthält
keine regional konsistente Mitgliederzahl. Die
[ECSS-Schnittstelle](https://carsharing.de/themen/carsharing-schnittstelle) standardisiert
Stationen, Fahrzeugklassen und Verfügbarkeit teilnehmender Anbieter, ist aber kein
flächendeckender öffentlicher Bestandsdienst. Eine automatische Null für nicht gelistete
Gebiete wäre fachlich falsch. Für den generischen Export ist daher die UI-Modellierung der
belastbare aktuelle Pfad.

**Umsetzung:** `carsharingVehiclesPer1000` verteilt die Flotte proportional zur erzeugten
District-Bevölkerung; `carsharingMemberShare` erzeugt die absolute gebietsweite Zahl. Beide
Felder sind optional, separat editierbar und werden in API und Studio validiert. Ein nicht
gesetztes Gegenfeld wird als null angenommen. Ohne beide Eingaben bleibt die bisherige, im
Dateninventar ausdrücklich bezeichnete Nullannahme bestehen. Eingaben, effektive Werte und
zeitliche Konstanz stehen in `data-inventory.json`, `provenance.json` und im
Eingangssnapshot für die Veraltet-Erkennung.

| Nachweis | Ergebnis |
|---|---|
| Exporttest | Mitgliederzahl wird aus Bevölkerung × Anteil gebildet; Fahrzeugdichte wird je District gerundet und bleibt über alle Jahre konstant. Dateninventar und Herkunftsnachweis weisen die Nutzervorgabe aus. |
| API-/UI-Test | Werte außerhalb 0–100 Fahrzeuge je 1.000 Einwohner bzw. 0–0,4 Mitgliederanteil werden abgelehnt. Das Studio zeigt beide Größen und markiert geänderte Ergebnisse als veraltet. |
| Echter ConfigTool-Lauf | 100 Mitglieder sowie je 5 Fahrzeuge in vier Districts wurden 2026–2028 exakt im isolierten Rechenkern wiedergefunden. 92 Ergebnis-CSV-Dateien, 0 Prozessfehler und 0 nicht endliche Zellen. Bericht und Logs: `validation/mobilecity/sharing-spike/native-v1/` (lokale, gitignorierte Evidenz). |

**Abschlussprüfung WP3:** `mvn -o test`: 199 Testfälle, davon 192 erfolgreich und 7 wegen
nicht gesetzter optionaler Integrationsumgebungen übersprungen, keine Fehler. Die fokussierte
Regionalexport-/Szenarioprüfung umfasst 20 grüne Tests. `npm test`: 48/48 erfolgreich;
`npm run build`: Produktionsbuild erfolgreich. `git diff --check` ohne Befund.

**Grenzen:** Die Werte sind Szenarioannahmen, keine beobachteten Regionaldaten. Stations-
und free-floating-Angebote werden im aktuellen MobileCity-Zielfeld gemeinsam gezählt;
Servicegebiete und einzelne Stationen werden nicht exportiert. Ein späterer bcs-/ECSS-
Adapter benötigt einen reproduzierbaren, rechtlich nutzbaren und räumlich vollständigen
Snapshot sowie eine eigene Abdeckungsdiagnose.

## MobileCity-Umland und eingehende Nachfrage (2026-09-23) — manuell und Zensus-automatisch

**Prüffrage:** Beschreibt `POPtripsSurrounding` ausgehende Wege der Binnenbevölkerung oder
eingehende Wege von Umlandbewohnern, und kann SyntheSis dafür konsistente `Outer`-Districts
erzeugen, ohne die bisherigen externen Zielanker still als Wohnbevölkerung umzudeuten?

**Zielvertrag:** `DistrictType.Outer=0`; `D7000_CityConfig` bildet daraus die
`SurroundingDistrictIdList`. `ST2126_CityConfigPopTripsSurroundingReader` liest je Zweck und
Umland-District `tripShareToStudyArea`. `CAL4010_MODTrips` multipliziert diesen Anteil mit
den alters- und zweckspezifischen Tageswegen der Bevölkerung des Umland-Districts und
verteilt das Ergebnis anhand von Bevölkerung mal Attraktivität auf Binnenziele. Der Vertrag
ist damit gerichtet: Umland → Untersuchungsgebiet.

**Umsetzung:** `surroundingPopulation` aktiviert bis zu acht vorhandene Richtungsanker als
echte `Outer`-Districts. Die Personensumme wird deterministisch und ohne Rundungsverlust
verteilt. Alle vier Anteile `surroundingEducationTripShare`,
`surroundingWorkTripShare`, `surroundingShoppingTripShare` und
`surroundingLeisureTripShare` sind dann erforderlich und liegen in `[0,1]`.
Altersstruktur, Pkw-Dichte und privater BEV-Anteil werden aus dem Untersuchungsgebiet
extrapoliert und im Herkunftsbericht als Annahme ausgewiesen. Bei fehlender Umlandvorgabe
bleiben die externen Zonen Zielanker und werden vom Regionalpaket ausgeschlossen. Die
Study-Area-Bevölkerung bleibt die Basis für Finanzen, Transitflotte und gebietsweite
Carsharing-Mitglieder.

`autoSurrounding=true` ersetzt die gleichmäßige Verteilung durch einen echten metrischen
Pufferring. Der Kartenausschnitt wird in EPSG:3035 um `surroundingBufferKm` erweitert, der
Ring in N/NE/E/SE/S/SW/W/NW geschnitten und anschließend nach WGS84 zurücktransformiert.
Das gepinnte Zensus-2022-Bevölkerungsraster und das Fünf-Altersklassen-Raster werden direkt
in diese Polygone aggregiert. Die drei MobileCity-Altersgruppen bleiben je Sektor exakt auf
seine Einwohnerzahl bilanziert. Eine optionale `surroundingPopulation` skaliert nur die
beobachteten Sektorgewichte; leer bedeutet automatische Gesamtsumme. Pkw-Dichte und BEV-
Anteil bleiben ausgewiesene Extrapolationen aus dem Untersuchungsgebiet. Die acht externen
Matrixanker werden bei dieser Betriebsart an Gebietsrand und halbe Pufferbreite gesetzt,
damit ihr Referenzpunkt innerhalb des zugehörigen Outer-Sektors liegt.

| Nachweis | Ergebnis |
|---|---|
| Exporttest | Zwei Binnen- und acht Umland-Districts; 803 Umlandbewohner bleiben in den drei Altersblöcken exakt erhalten. `StudyAreaConfig`, `REGdistricts`, `POPinhabitants`, `POPtripsSurrounding`, Zusammenfassung, Inventar und Provenienz stimmen überein. |
| API-/UI-Test | Teilweise Umlandangaben, deaktivierte Außenbeziehungen, nicht endliche Werte und Anteile außerhalb `[0,1]` blockieren den Export. Vollständige Eingaben erscheinen im Datenstatus und im Veraltet-Abbild. |
| Echter ConfigTool-Lauf | Vier Binnen- und acht Umland-Districts mit 8.000 Umlandbewohnern: alle 32 Kombinationen aus acht Ursprüngen und vier Zwecken stimmen im Bezugsjahr innerhalb numerischer Toleranz mit dem Export überein. 92 Ergebnis-CSV-Dateien, 0 Prozessfehler und nach dem unten beschriebenen Zielmodell-Guard 0 nicht endliche Zellen. Bericht und Logs: `validation/mobilecity/surrounding-spike/native-v1/` (lokale, gitignorierte Evidenz). |
| Automatische Geometrie | Acht gültige und überlappungsfreie Ringsektoren ohne Flächenüberschneidung mit dem Kartenausschnitt; adaptive Richtungsanker liegen auch beim großen Testgebiet außerhalb des Ausschnitts und innerhalb ihres Sektors. Proportionale Ganzzahlverteilung erhält jede vorgegebene Gesamtsumme exakt. |
| Reale Zensus-Sonde | Ausschnitt 48,82–48,90° N / 8,15–8,25° E, 10 km Puffer: 202.412 Einwohner und 5.797 Altersrasterzellen. Sektoren N/NE/E/SE/S/SW/W/NW: 12.533 / 42.736 / 22.294 / 47.699 / 60.327 / 14.973 / 1.822 / 28 Einwohner. Beide Originalarchive wurden über ihre gepinnten SHA-256-Werte gelesen. |
| Nativer Automatiklauf | Vier Binnen- und acht automatisch erzeugte Outer-Districts, 71.000 Binnen- und 202.412 Umlandbewohner. Import, binäres Zurücklesen und Simulation 2026–2028 bestanden: 92 Ergebnis-CSVs, 0 Fehler, 0 nicht endliche Zellen, 81/81 Beitragsprüfungen einschließlich 32/32 Umland-Zweckbeiträgen im Bezugsjahr. Evidenz: `validation/mobilecity/auto-surrounding-spike/native-v1/`. |

**Zielmodellbefund:** Ohne Transitfahrten eines Outer-Districts sind aktueller und vorheriger
`singleTicketPriceIndex` beide null. `CAL6010_SOC` dividiert beim dritten Simulationsjahr
ungeprüft `0/0`; im Kontrolllauf entstanden acht `NaN`-Saisonticketraten und vier davon
abhängige `LAQ`-Werte. Die isolierte Validierung setzt nur für diesen Fall den
Single-Ticket-Elastizitätsbeitrag auf null. Positive Nenner verwenden unverändert die native
Formel. Quell- und Patchhash stehen im Prüfbericht. Das ist ein klar abgegrenzter Fehler des
mitgelieferten Rechenkerns und muss mit Takomat behoben werden; er ist keine Rechtfertigung,
Umlandwerte im Export zu unterdrücken.

**Noch offen:** BA-Pendlerrelationen nur für den Arbeitszweck auf die eingehenden Anteile
übertragen; Bildung, Einkauf und Freizeit separat belegen. Anschließend folgen Randgebiet,
vergrößerter Ausschnitt, Stadt/Land und ein Gebiet außerhalb Deutschlands. Das Zensus-Raster
deckt nur Deutschland ab; außerhalb davon bleibt eine manuelle Umlandvorgabe erforderlich.

**Abschlussprüfung:** Die vollständige Offline-Backend-Suite besteht mit **201 Tests,
0 Fehlern und 7 umgebungsabhängigen Skips**. Das Frontend besteht **49/49** Tests und den
Angular-Produktionsbuild. Beide MobileCity-Hilfsskripte kompilieren mit Python; der
Gesamtdiff enthält keine Whitespace-Fehler. Der native Umlandlauf besteht mit **92
Ergebnis-CSVs, 0 nicht endlichen Zellen und 32/32 exakt geprüften Umlandbeiträgen**.

**Abschlussprüfung nach der Zensus-Automatik:** Die vollständige Offline-Backend-Suite
besteht mit **207 Tests, 0 Fehlern und 7 umgebungsabhängigen Skips**. Das Frontend besteht
**50/50** Tests und den Angular-Produktionsbuild. Der native Automatiklauf besteht mit
**92 Ergebnis-CSVs, 0 nicht endlichen Zellen und 81/81 Beitragsprüfungen**; darin sind alle
32 Kombinationen aus acht Umlandsektoren und vier Wegezwecken im Bezugsjahr enthalten.


## MobileCity-Rudimentärpfad (2026-09-23) — UI bis Import-ZIP nachgewiesen

**Prüffrage:** Kann ein Nutzer aus einem gespeicherten Kartenausschnitt ohne Routingdienst,
GTFS, KBA-Abdeckung, Umland oder logiTopp-Lauf einen vollständigen MobileCity-Importsatz
erzeugen, und bleiben Personen- und Pkw-Summen dabei exakt erhalten?

**Umsetzung:** Der Studio-Schnellstart wählt ausschließlich MobileCity, Template-Verhalten,
Luftlinienmatrizen, höchstens zwölf Districts, den sichtbaren modellierten ÖV-Fallback,
0 % BEV als explizite Annahme und kein Umland. „MobileCity-Export erzeugen“ speichert die
sichtbare Konfiguration vor dem Start. Das Backend prüft Modellprofil und BEV-Grundlage vor
dem Queueing. Personen und Pkw werden über alle Districts gemeinsam mit dem deterministischen
Größter-Rest-Verfahren gerundet; dadurch kann unabhängiges Runden je Zone die Gebietssumme
nicht mehr verändern.

**REST-Smoke-Test:** Isolierter Offline-Dienst, Ausschnitt 48,85–48,87° N / 8,19–8,22° E,
250 Personen, neun Zonen. Alle Exportstufen bestanden; `mobilecity-import.zip` enthielt die
25 Tabellenfamilien, Shapefile, Dateninventar, Provenienz und Zusammenfassung und bestand
`unzip -t` ohne Fehler. Nach der Bilanzkorrektur meldeten Pipeline und MobileCity-Paket
identisch **250 Personen und 121 Pkw**. Der Regressionstest reproduziert die zuvor sichtbare
Rundungsstelle und verlangt beide Gebietssummen exakt.

**Abschlussprüfung nach dem Rudimentärpfad:** Die vollständige Offline-Backend-Suite besteht
mit **210 Tests, 0 Fehlern und 7 umgebungsabhängigen Skips**. Das Frontend besteht **52/52**
Tests und den Angular-Produktionsbuild. Die vier MobileCity-Hilfsskripte kompilieren mit
Python; `git diff --check` bleibt ohne Befund.


<a id="mobilecity-mehrregionenpruefung-2026-09-25"></a>

## MobileCity-Mehrregionenprüfung (2026-09-25) — fünf geografische Basispakete bestanden

`MobileCityRegionalExporterTest.regionalMatrixExportsCompletePackagesAcrossGeographies`
erzeugt fünf feste Kartenausschnitte mit demselben Seed. Er prüft Gebietssummen für Personen
und Pkw, vollständige Zuordnung aller Quellzonen, erforderliche CSV-Familien und Shapefile-
Teile, Dateninventar sowie bytegleichen ZIP-Inhalt beim erneuten Export. Das Skript
`scripts/check-mobilecity-regions.py` führt danach für jedes Paket den echten ConfigTool-
Import, das binäre Zurücklesen, Bestands-/BEV-Prüfungen und elf Simulationsjahre aus.

| Gebietstyp | Testgebiet | Personen | Pkw | Districts | Nativer Befund |
|---|---|---:|---:|---:|---|
| Stadt | Berlin | 1.800 | 891 | 4 | 11 Jahre, 276 CSV, 17/17 Beiträge, 0 nicht endliche Zellen |
| Land | Uckermark | 4.000 | 1.954 | 4 | 11 Jahre, 276 CSV, 17/17 Beiträge, 0 nicht endliche Zellen |
| Grenzlage | Kehl/Straßburg | 1.800 | 891 | 4 | 11 Jahre, 276 CSV, 17/17 Beiträge, 0 nicht endliche Zellen |
| Kleiner angeschnittener Ausschnitt | Rastatt | 200 | 96 | 1 | 11 Jahre, 276 CSV, 5/5 Beiträge, 0 nicht endliche Zellen |
| Außerhalb Deutschlands | Basel | 1.800 | 891 | 4 | 11 Jahre, 276 CSV, 17/17 Beiträge, 0 nicht endliche Zellen |

Wiederholung mit lokal vorbereitetem `tramigo-v2`-Profil, .NET 8 und ConfigTool:

```bash
python3 scripts/check-mobilecity-regions.py --work-dir /tmp/mobilecity-regions-neuer-lauf
```

Der Sammelbericht `matrix-report.json` und die Einzelberichte `native-*/validation-report.json`
liegen im gewählten Arbeitsverzeichnis. Der hier geprüfte Bericht liegt lokal unter
`/private/tmp/mobilecity-region-matrix-20260925-final/matrix-report.json`; Gesamtstatus
`passed`, fünf Fälle, null Fehler. Das Skript bricht bei fehlenden lokalen Werkzeugen oder
Profilen sichtbar ab und bewahrt Logs und Teilergebnisse.

**Aussagegrenze:** Die geografischen Fälle nutzen synthetische Populationen, 3 % BEV als
manuelle Annahme, modellierten ÖV und ein unkalibriertes TRAMIGO-Modellprofil. Sie prüfen
technische Übertragbarkeit des Exports, nicht beobachtete regionale Bestände, echte GTFS-
Relationen, KBA-/Zensus-Abdeckung, Umland-Pendleranteile oder fachliche Prognosegüte.
Ein realer Mehrzonen-Quellenpfad ist im folgenden Abschnitt geprüft; der
GTFS-Relationsvergleich und die fachliche Abnahme mit Takomat bleiben offen.


<a id="mobilecity-rastatt-livequellen-2026-09-28"></a>

## MobileCity-Rastatt mit Live-BKG und regionalen Quellen (2026-09-28)

`scripts/check-mobilecity-rastatt-sources.py --case cross-municipality
--work-dir /private/tmp/mobilecity-cross-municipality-neuer-lauf` prüft vier
2-km-Zonen zwischen Rastatt und Nachbargemeinden. Der Test nutzt das amtliche
Zensus-2022-Raster und dessen Alters-/Haushaltsarchive, die gepinnte KBA-Tabelle
vom 01.07.2026, den gepinnten BNetzA-Snapshot vom 01.09.2026 und den lokalen
KVV-GTFS-Feed. Nur die Gemeindegeometrien kommen live vom BKG-WFS; die genaue
Antwort wird für den Lauf als `fleet-boundaries.geojson` gespeichert und gehasht.

| Zone | Zensus-Einwohner | BKG-Abdeckung | Öffentliche Ladepunkte 2026 | KVV-Halte im Ausschnitt |
|---|---:|---:|---:|---:|
| 100001 | 12.034 | 100 % | 12 | 23 |
| 100002 | 1.670 | 100 % | 20 | 7 |
| 100003 | 1.630 | 100 % | 0 | 6 |
| 100004 | 4.274 | 100 % | 14 | 15 |

Alle vier Zonen erhielten Zensus-Alters-/Haushaltsstrukturen, KBA-BEV-Anteile
und einen GTFS-Zugangsknoten. Insgesamt wurden 19.608 Einwohner, 46
öffentliche Ladepunkte und 370 Fahrten am repräsentativen Mittwoch
(08.07.2026) exportiert. Die fünf berührten Gemeinden sind Bietigheim,
Muggensturm, Ötigheim, Rastatt und Steinmauern. Eine künstliche, ansonsten
formal gültige BKG-Teilantwort nur für Rastatt ließ drei Zonen ohne BEV-Anteil;
der Regionalexport verweigerte das Paket mit `BEV-Anteil fehlt`.

Der ConfigTool-Import, das binäre Zurücklesen und die Simulation 2026–2036
bestanden; 276 native CSV-Ausgaben enthielten keine nicht endlichen Werte.
Der Bericht des Prüflaufs liegt unter
`/private/tmp/mobilecity-cross-municipality-20260928-final/rastatt-source-report.json`.
Der BKG-Zeitstempel der live abgerufenen Antwort war
`2026-09-28T07:57:42.611Z` (den exakten Wert pro Wiederholung enthält der Bericht).

**Aussagegrenze:** Die Prüfung belegt einen funktionsfähigen Mehrzonen-Export
für diesen Ausschnitt. Pkw-Bestände kommen weiterhin aus synthetischen
Haushalten, das OD-Netz ist aggregiert, und das Modellprofil bleibt
unkalibriert. Weitere Regionen und der fachliche Relationsvergleich mit
Takomat sind nicht durch diesen Test abgenommen.

## Gemeinsame GraphHopper-/SUMO-Straßenrouten (2026-10-07) — kleine native Kette bestanden

**Befund:** Ein Rastatt-Probelauf mit Pkw, Fahrrad sowie Hin-/Rückwegen von
Lieferwagen und Lastenrad überträgt alle sechs Straßenfolgen. Nach `duarouter`
sind alle sechs Kantenfolgen unverändert; alle sechs TripInfo-Ankünfte sind
vorhanden und melden `rerouteNo=0`. Die vier ursprünglichen Adaptertests plus
die zusätzliche Geometrieabdeckung prüfen auch unzulässige Verbindungen,
Gegenrichtung, Abkürzungen und verworfene/veränderte Routen.
[Native Zahlen und Netz-Hashes](verification/2026-10-07-shared-road-routes.json).

Beim Pkw-Beispiel beträgt die GraphHopper-Zeit 190,777 s, die statische
SUMO-Kantenzeit-Schätzung 120,720 s und die tatsächliche SUMO-Dauer 238 s.
Das zeigt trotz gleicher Straßenfolge unterschiedliche Zeitannahmen. Der
Unterschied ist kein isolierter Staueffekt: Kantenzeit-Schätzung und
Simulation unterscheiden sich zusätzlich bei Kreuzungen, Beschleunigung und
Fahrzeuggeschwindigkeit. Der Probelauf enthält keine Belastungs-A/B-Prüfung.

**Negativbefunde:** Kreuzungszusammenführung entfernt Kurzabschnitte;
fehlender OSM-Radzugangsimport verhindert zunächst Radzuordnungen;
`duarouter` kann explizite Rad-Routen neu auswählen. Der experimentelle
Import erhält Abschnitte und Radzugänge, und die Routenübernahme wird explizit
festgelegt. Die Nachprüfung erkennt jede verlorene oder veränderte Zuordnung.

**Konsequenz:** Option bleibt `sharedRoadRoutes=false` im Standard.
Gebietsabdeckung, Fallbackquote, Rechenkosten und getrennte unbelastete/belastete
Läufe sind nächste Abnahmen. Aus der technischen Kette folgt weder eine
kalibrierte Reisezeitmatrix noch eine Netzzeit-basierte logiTopp-Tourplanung.


## Schwere LKW: OSM-Sperrübertragung (2026-10-12) — ursprünglicher Nachweis korrigiert

**Befund:** Native `netconvert`-Versuche mit SUMO 1.27.1 lassen bei `hgv=no`
`truck`/`trailer` auf den Lanes der Testkante 20 zunächst zu, auch mit
`--osm.lane-access`. Das Fixture vom 10.10.2026 konnte durch zusammengeführte
Start-/Zielkanten einen Umweg erzeugen, ohne die Sperrwirkung unabhängig nachzuweisen.
Die damalige Formulierung als Nachweis für die OSM-HGV-Sperre war deshalb zu weitgehend.

**Konsequenz:** Unbedingte und richtungsbezogene HGV-Sperren werden jetzt vor Routing
explizit übertragen. `SumoFreightIntegrationTest` fordert sowohl die PKW-Nutzung der
Abkürzung als auch deren Ausschluss für schwere Fahrzeuge. Ein Unit-Test prüft die
richtungsbezogene Ausnahme und das Ausweisen nicht unterstützter bedingter Regeln.
Die generierten Netzkanten und ihre Berechtigungen sind als GeoJSON prüfbar.
Details und verbliebene Grenzen: [Güterverkehrskonzept, Abschnitt 9](21-gueterverkehr-konzept-und-datenquellen.md#9-gerichtete-zugänge-und-erster-kba-datenadapter-2026-10-12).


## Güterverkehr: reale Maut-OD-Routingprobe (2026-10-13)

**Befund:** Zwei gerichtete Dortmund/Unna-Mautbeziehungen lassen sich mit expliziten
Zugangs-/Klassen-/Tagesannahmen über die produktiven Gütergenerator-/Kantenprüfungen
und die native SUMO-Kette ausführen: 359 erzeugte und abgeschlossene Güterfahrten,
keine Verluste oder Teleports. Zwei PKW sind ausschließlich synthetische Kontrollen.
Ein voller regionaler Mischverkehrslauf ist damit nicht belegt.

**Evidenz:** `.runtime/freight-routing-pilot-2026-10-13/pilot-report.json` mit Hashes,
`tripinfo.xml`, `statistics.xml`, ausgeführte Routen und unabhängiger Review.
17 Offline-Aufbereitungstests erfolgreich. Der SGV-Teilvergleich an der vorläufig
geometrisch zugeordneten automatischen Station Berghofen ist von den gewählten
Zugängen abhängig; zwei weitere Stations-IDs widersprechen sich in Monatsbericht
und amtlicher Geodatensatz-Klassifikation. Randkanten und Teleports werden im
Vergleich zurückgewiesen, damit geplante Kanten keine falsche Passage beweisen.

**Konsequenz:** Routingmechanik bestätigt; regionale Realitäts-/Kalibrierbehauptung
weiter offen. Erst weitere begründete Zugänge, vollständige OD/Transit und
Stations-/Zeitkompatibilität absichern. Quellen, Grenzfälle und reproduzierbare
Befehle: [Güterverkehrskonzept, Abschnitt 13](21-gueterverkehr-konzept-und-datenquellen.md#13-native-maut-od-routingprobe-dortmundunna-2026-10-13).

## Regionale Güter-OD: Zugangssensitivität (2026-10-13)

**Befund:** 28 veröffentlichte Gemeinde-OD mit neun geografisch geprüften Mautknoten
lassen sich zu 2.336 Tagesfahrten aufteilen und nativ abschließen. Drei ausschließlich
in Dortmunder Knotengewichten verschiedene Varianten halten Mengen und Netz konstant;
Berghofen-Passagen variieren von 331 bis 704. Null Teleports/Restfahrzeuge in allen
Varianten; noch keine volle Personen-/Paket-/Hintergrundbelastung.

**Negativbefund:** Die vorher festgelegte Wirkung „Nordost-Betonung erhöht, Süd-Betonung
senkt um 20–150 Passagen/Tag“ ist in Richtung und Größenordnung widerlegt. Statt die
Vorhersage umzuschreiben, wird sie in `sensitivity-plan.json` samt Beobachtung erhalten.

**Evidenz:** `.runtime/freight-regional-pilot-2026-10-13/` mit Rohquellenhashes,
Mengenbilanz, nativen Tripinfos/Statistiken, drei Vergleichsberichten und unabhängiger
Reproduktion; 25 Offline-Aufbereitungstests grün. **Konsequenz:** keine Kalibrierung
gegen angenommene Einzelzugänge. Zugangsmarginalen/joint-OD fehlen; Transit und
innergemeindliche Verteilung bleiben unvollständig. Ausführliche Quellen und Verfahren:
[Güterverkehrskonzept, Abschnitt 14](21-gueterverkehr-konzept-und-datenquellen.md#14-mehrere-mautknotenzugänge-und-regionale-od-erweiterung-2026-10-13).

## Overture-Standortkoordinaten (2026-10-15) — exakte Geometrie erforderlich

**Befund:** Die Bounding-Box eines Overture-Places-Punkts kann wegen Rundung eine
positive Breite/Höhe haben. Im geprüften Release `2026-09-23.1` lieferte die Auswahl
über ausschließlich innerhalb der Regionsgrenzen liegende Bbox-Minimalwerte drei
Datensätze weniger als ein Bbox-Schnittfilter mit nachfolgendem exaktem Point-Test.
Eine positive Bbox-Ausdehnung belegt deshalb keine ungültige Point-Geometrie.

**Konsequenz:** Der Forschungsadapter liest die echten WKB-Point-Koordinaten.
Die Bounding-Box dient nur zum Vorfiltern. Tests sichern Punktkoordinaten gegen
nach außen gerundete Bbox-Werte und beide WKB-Bytefolgen ab. Kein Einfluss auf die
bisherige Produktivpipeline. Quellen, Datenbilanz und lokale Belege:
[Güterverkehrskonzept, Abschnitt 17](21-gueterverkehr-konzept-und-datenquellen.md#17-regionale-betriebsstandorte-aus-overture-2026-10-15-offline-pilot).

## Regionale LKW-Routingprobe (2026-10-15) — native Route ist keine Zugangsfreigabe

**Befund:** Der native Import eines vollständigen lokalen Straßenausschnitts berechnet
alle angeforderten Klassenrouten, übernimmt aber nicht automatisch sämtliche
OSM-HGV-Verbote und Restriktionsrelationen. Der Offline-Prüfer erkennt ausdrücklich
verbotene Wege und weitere ungeklärte Quellregeln auf ansonsten erfolgreichen Routen.

**Konsequenz:** Eindeutige gerichtete HGV-Verbote zusätzlich übertragen und die
Originalprobe als Negativbeleg erhalten. Fahrzeugmaße, Zeitpunkt, Fahrtzweck und reale
Zufahrten benötigen eigene Nachweise. Die Trennung entspricht der zusätzlichen
`OsmFreightPermissions`-Übertragung im Produktivpfad; es wurde kein Produktivcode
geändert. Quellen, Mengen und Wiederholungsprobe stehen im
[Güterverkehrskonzept, Abschnitt 18](21-gueterverkehr-konzept-und-datenquellen.md#18-lokaler-osm-abgleich-und-lkw-kantenprüfung-2026-10-15-offline-pilot).

## LKW-Profile statt reiner Verkehrsklasse (2026-10-15) — numerische Regeln werden wirksam

**Befund:** Eine erfolgreiche native `truck`-/`trailer`-Route belegt keine Einhaltung
expliziter Breiten-, Höhen- und Massegrenzen. Der vorhandene Fahrzeugkatalog liefert
versionsgebundene Maße; eine Betriebsannahme ergänzt die tatsächliche Beladungsmasse.
Eine native 30-t-Vergleichsstrecke trennt 26-t-Solo-LKW und 40-t-Sattelzug erst nach
profilabhängiger Sperrübertragung zuverlässig.

**Konsequenz:** Tatsächliche und zulässige Masse getrennt prüfen, Achslasten nicht
erfinden, nicht mehr berechenbare Proben erhalten. Numerische Prüfung und globale
Zufahrtsfreigabe bleiben getrennt. Detaillierte regionale Vorher-/Nachher-Bilanz:
[Güterverkehrskonzept, Abschnitt 19](21-gueterverkehr-konzept-und-datenquellen.md#19-fahrzeugprofile-und-numerische-restriktionen-2026-10-15-offline-pilot).

## Gerichtete LKW-Rückfahrten und Abbiegeübertragung (2026-10-15)

**Befund:** Die zwei nicht routbaren Rückfahrten der Profilprobe starten auf einer
Kante, deren einziger Nachfolger auf 3,8 m Höhe begrenzt ist. Die angenommenen Profile
sind 4 m hoch. Die ausdrücklich alternative Gegenrichtung derselben Straße routet
beide Klassen; feste OD-Endpunkte werden deshalb nicht automatisch umgeschrieben.
Der Abgleich aller 1.798 Quellrelationen für zwei Klassen zeigt eine native
Abbiegeabweichung in beiden Klassen und zahlreiche ungeklärte Zuordnungen. Keine
der 54 vorhandenen Routen nutzt die abweichende Verbindung.

**Konsequenz:** Standortanbindung bleibt gemäß Modellvorgabe auf Straßen beschränkt.
Der neue optionale Produktivprüfer erhält Fehl-/Unklarheitsbilanzen; er erteilt keine
pauschale Zugangsfreigabe. Der bisherige Overpass-Netzquery ließ Abbiegerelationen aus
und wurde ergänzt. Referenzsatz, Quelle, Rückfahrtdiagnose und Einschränkungen:
[Güterverkehrskonzept, Abschnitt 20](21-gueterverkehr-konzept-und-datenquellen.md#20-rückfahrten-abbiegerelationen-und-optionale-profilprüfung-2026-10-15).

## Regionales SUMO-Netz: DOM-Speichergrenze und terminaler Fehlerstatus (2026-10-16)

**Befund:** Der regionale Szenariogesamttest mit 2.336 schweren Modellfahrten erreicht
die native logiTopp-Simulation und eine erfolgreiche vollständige Netzkonvertierung.
Das resultierende 1,28-GB-Netz sprengt beim DOM-Zugriff in `OsmFreightPermissions.apply`
das vorab begrenzte 6-GiB-Heapbudget. Eine direkte Kopienprobe reproduziert den
Stacktrace. Profilübertragung, Güterrouting und SUMO-Ausführung werden nicht erreicht;
Routen-/Abschluss-/Teleportwerte sind unbekannt, nicht null.

**Konsequenz:** Die regionale Abnahme ist negativ; vor der nächsten Wiederholung sind
die großen XML-Verbraucher stromweise auszulegen. Zugleich war `Error` an den
Job-/Stufengrenzen nicht behandelt: Der Executor fing ihn im unbeobachteten Future,
der Status blieb RUNNING. Die Korrektur veröffentlicht FAILED und wirft weiter.
Ein injizierter Ressourcenfehler samt erneut startbarem Job und die wirkliche
Regionalwiederholung prüfen diesen Statusvertrag. Eingaben, Wochen-/Tageshorizont,
Modellpopulation, Budget, Ausgaben und Einschränkungen:
[Güterverkehrskonzept, Abschnitt 21](21-gueterverkehr-konzept-und-datenquellen.md#21-regionaler-gesamttest-mit-profilprüfung-2026-10-16-negativer-abnahmebefund).

## Regionales SUMO-Netz: Streaming und freigelegte Netzlücken (2026-10-16)

Die unveränderte Wiederholung aus Abschnitt 21 des Güterverkehrskonzepts verarbeitet
nach dem Streaming-Umbau das 1,28-GB-Netz innerhalb desselben 6-GiB-Heapbudgets.
HGV-/Profilübertragung, Straßenbindung aller 2.336 Güterfahrten und Netzexport sind
belegt. Die vollständige Backend-Suite ist grün: 275 Fälle, vier explizite Skips.
Der neue Speicherbeweis verarbeitet 150.000 Kanten in einer eigenen 64-MiB-JVM.

**Abnahme weiterhin negativ:** Das Personenrouting bricht bei einem Ziel mit rund
3,08 km Abstand zur nächsten vorhandenen Kante ab. Die isolierte Güterroutingprobe
liefert 2.197 Routen, 139 fehlen zu derselben am Netzrand abgeschnittenen A45-
Zufahrtskette. Die echte SUMO-Ausführung wird nicht erreicht; Abschluss-/Teleport-
werte bleiben unbekannt. Diese Fehler werden durch die behobene Speichergrenze
sichtbar. Der unveränderte Fall bleibt archiviert; eine größere räumliche
Quellabdeckung wird separat geprüft.
[Umsetzung, Messmethode und Ergebnis](21-gueterverkehr-konzept-und-datenquellen.md#22-streaming-der-regionalen-sumo-netze-2026-10-16).

## Regionaler Güterpfad mit erweitertem Straßenabzug (2026-10-16)

Ein separat eingefrorener NRW-Netzfall desselben Quellenstichtags mit größerem
Straßenpuffer beseitigt die zuvor nachgewiesenen Abdeckungs-/Zufahrtslücken.
Modellregion, Population, Güter-Trips, Profile, Seed und Java-Heap bleiben gleich;
alle 125 Güter-Kantenbindungen sind unverändert. Das 2,62-GB-Netz durchläuft die
native Kette: 2.336 Güterfahrten geroutet, eingesetzt und beendet, keine fehlende,
unfertige oder teleportierte schwere Fahrt und keine expliziten Quellverstöße.
Alle Güterrouten bleiben wegen ungeklärter Regeln `review-required`.

**Begrenzte Abnahme:** Der schwere Güterpfad ist bestanden; die Gesamtnachfrage bleibt
unvollständig. 217 Pkw-/Radfahrten, 91 Paket-Legs und acht Fußrouten fehlen im
Routing. Ein weiterer Fußweg ist am Tagesende offen; ein Fahrrad teleportiert.
Quell-/Codehashes und Abschluss-IDs sind separat geprüft. Der unveränderte frühere
Negativfall bleibt erhalten.
[Bilanz, Zählereinheiten und nächste Arbeit](21-gueterverkehr-konzept-und-datenquellen.md#231-native-ausführung-und-getrennte-abnahme).


## Regionales Routing: gerichtete Zuordnung und ehrliche Nachfragebilanz (2026-10-17)

Im eingefrorenen Regionalfall fehlen ursprünglich 316 Anfragen. Die alleinige
Freigabe erlaubten Sackgassenwendens reduziert dies auf 18. Ein nativer OSM-Test
belegt zugleich, dass ein explizites Wendeverbot erhalten bleibt. Die Vorprüfung
findet 145 Endpunkte außerhalb der nächsten klassenberechtigten Straße innerhalb
300 m. Ein vollständiger Kantenvergleich bestätigt alle 51 eindeutigen
Klassen-/Koordinatenpaare (nächste Straßenmittellinie etwa 304–814 m).

Die zusätzliche gerichtete Prüfung korrigiert elf Richtungen desselben Segments,
findet sechs Anfragen ohne möglichen Fahrzeugpfad und bewahrt alle 125 HGV-
Kantenbindungen. Eine positive Vorprüfung ersetzt keine native Routenzertifizierung;
Fußwege bleiben nativ geprüft. Der produktive vollständige Modus stoppt bei den
nicht zuordenbaren Endpunkten mit Bericht. Der ursprüngliche Regionalfall und der
negative Zwischenversuch der reinen Klassenabbildung bleiben erhalten.

Die Fahrzeugstatistik zählt eingesetzte Fahrräder mit (im historischen Regionalfall
3.316 statt 3.255). Anfrage-/Routing-/Start-/Abschlusszahlen, Tagesrand und Teleports
werden ID-genau getrennt bilanziert. Unvollständige Router-/Teleportlogs erzeugen
keine erfundenen Nullverluste. Lokale Archiv-Regressionen und echte kleine native
Netztests werden als unterschiedliche Evidenz behandelt.
[Versuchsaufbau und Grenzen](21-gueterverkehr-konzept-und-datenquellen.md#24-routingverluste-vorprüfung-und-vollständige-ausführungsbilanz-2026-10-17).

**Verifiziert (2026-10-17):** Vollständige Backend-Suite: 286 Fälle, keine Fehler,
vier ausgewiesene Skips. Frontend: 62 Tests grün und Produktionsbuild erfolgreich.
Die 17 gezielten Routing-/Bilanz-/Güterfälle laufen ohne Skip; darunter echte
native Sackgassen-/Wendeverbotsfälle und die lokale regionale Archiv-Regression.


**Produktiver Regionalnachweis (2026-10-17):** Die unveränderte vollständige
`GenerationService`-Kette mit eingefrorenen Produktionsklassen prüft alle 3.839
Anfragen und stoppt die SUMO-Stufe mit FAILED: 145 nicht zuordenbare Endpunkte und
sechs Anfragen ohne gerichteten Pfad, elf korrigierte Richtungen. Es werden keine
Routen-/Statistik-/Abschlussdateien oder Success-Summary vorgetäuscht. Der Job
bleibt gemäß bestehender Optionalstufen-Politik DONE; der konkrete SUMO-Fehler ist
sichtbar. Eingaben, Quell-/Klassenhashes und Ergebnis liegen separat unter
`.runtime/traffic-routing-regional-2026-10-17-final/verified-result.json`.


**Frische native Güterkontrolle (2026-10-17):** Auf dem abschließenden Netz werden
alle 2.336 schweren Fahrten mit ihren neuen nativen Routen separat bis zum
Tagesende ausgeführt: 2.336 angefordert, geroutet, gestartet und beendet; keine
fehlende/unfertige Fahrt, null Teleports. Die Kontrolle isoliert den Güterpfad und
ersetzt keine Gesamtabnahme oder Zugangsfreigabe. Kommando, Abschluss-IDs,
Tripinfo, Statistik und Ladehinweise sind unter
`.runtime/traffic-routing-2026-10-16/heavy-control/` archiviert.


## Ortsherkunft der SUMO-Restfälle und Pkw-Initialorte (2026-10-19)

**Befund:** Die 145 nicht zuordenbaren Endpunkte des eingefrorenen Regionalfalls
vom 17.10. lassen sich ohne Distanztoleranz zu den tatsächlich exportierten
logiTopp-Eingaben zurückverfolgen:

| Quellort | Endpunktvorkommen | Bedeutung |
|---|---:|---|
| `legacy/demand-data/household.csv` | 92 | synthetisch erzeugte Wohnorte |
| `legacy/demand-data/opportunity.csv` | 35 | synthetisch erzeugte Aktivitätsorte |
| `legacy/demand-data/fixedDestination.csv` | 18 | feste Ziele; zugleich Koordinaten der Zonenanker |

Die zusätzlichen 18 Treffer in `zones.csv` sind dieselben Fixziel-Vorkommen,
keine weiteren Endpunkte. Unter Einschluss beider Enden der sechs getrennten
Verbindungsfehler werden 157 Endpunkte geprüft, alle mit mindestens einer
Quellreferenz. Mehrere Datensätze können dieselbe Koordinate teilen. Ein exakter
Koordinatentreffer belegt den Quellort, aber ohne semantischen Wegschlüssel keine
eindeutige Verursachung durch eine bestimmte Quellzeile.

**Separater Ortsfehler:** `PopulationGenerator` und `LegacyExportGenerator` setzen
Pkw bislang an den Zonenmittelpunkt, während der zugehörige Haushaltswohnort bereits
vorliegt. Im unveränderten Regionalexport betrifft dies **515 von 515 Pkw**;
Abstand zum Wohnort im Mittel **1.981 m**, maximal **3.599 m** (lokale metrische
Näherung). Der korrigierte Export verwendet den jeweiligen Haushaltswohnort.
Fahrzeugzahl, Besitzer, Nummerierung und sämtliche anderen Legacy-Pkw-Attribute
sind im regionalen Vergleich identisch. Die Population behält externe Zonen-IDs,
der Legacy-Pkw-Export weiterhin `matrixColumn`/OID. Ein fehlender Wohnhaushalt oder
eine unbekannte Wohnzone führt zum Fehler und nicht zum stillen Fahrzeugverlust.

**Evidenz:** `.runtime/traffic-origins-2026-10-19/replay-summary.json` und
`network-coverage.json` entstehen durch erneutes Einlesen der eingefrorenen
Regionalartefakte mit dem aktuellen Java-Code. `car.csv` ist der getrennt neu
exportierte Pkw-Vertrag. Der archivierte Lauf wird nicht verändert. Die Herkunft
wird zusätzlich im produktiven Netzbericht ausgewiesen: Datei, Zeile, Datensatz,
Zonen-ID-Raum, Quellenhash und mehrere mögliche Referenzen. Beispiele sind je
Koordinate begrenzt; Gesamtzahlen bleiben vollständig. Fehlende/fehlerhafte
CSV-Quellen werden sichtbar als fehlend/unlesbar ausgewiesen; fehlerhafte Dateien
hinterlassen keine scheinbar verifizierten Teiltreffer.

**Konsequenz:** Dies ist eine Ortskorrektur samt Quellendiagnose, keine neue
regionale Routingabnahme. Die 145 bekannten Endpunkte werden hierdurch nicht
nachträglich als behoben ausgegeben. Für ihre Behebung ist die Erzeugung
synthetischer Wohn-/Aktivitätsorte fachlich weiterzuentwickeln; eine Vergrößerung
des 300-m-Radius oder ein Wechsel auf weiter entfernte Straßen ersetzt diesen
Schritt nicht. Keine separaten Betriebseinfahrten, keine neuen Zufallsziehungen.


**Verifiziert (2026-10-19):** 290 Backend-Testfälle, keine Fehler, vier ausgewiesene
Skips; 19 gezielte Fälle ohne Skip. Neue Regressionen prüfen Haushalts-/Pkw-Orts-
konsistenz einschließlich Zonen-ID-Räumen und fehlendem Haushalt sowie exakte,
mehrdeutige und fehlgeschlagene Quellzuordnungen. Die regionale Artefaktwiederholung
prüft Fahrzeugzahl und alle übrigen Pkw-Attribute unverändert; sie führt keine
neue Verkehrssimulation aus. Befehlslogs und Quell-/Eingabehashes:
`.runtime/traffic-origins-2026-10-19/verification.json`.


## Straßennahe Orte: negativer Erstlauf und native Netzberechtigungen (2026-10-19)

**Befund:** Die vorab notierte Prognose, alle 145 nicht zuordenbaren Endpunkte
zu beseitigen, wurde im ersten Regionalversuch widerlegt. Die unveränderte
`GenerationService`-Kette mit aktivierter Option prüft 3.839 Anfragen und meldet
**20 nicht zuordenbare Endpunkte sowie acht gerichtete Verbindungsfehler**.
Die SUMO-Stufe endet mit FAILED vor Routing und Ausführung. Demografie,
Zielzonen und alle nicht räumlichen Populationsattribute bleiben gleich;
515 Wohnorte, 2.002 reguläre Fixziele, 3.003 logiTopp-Fixziele und 500 Opportunities
liegen nach tatsächlicher CSV-Serialisierung in ihren ursprünglichen LAEA-Zonen.

**Mechanismus:** Alle 20 Endpunktfehler sind Pkw-Enden auf sieben `service`-Wegen.
GraphHopper lässt dort Pkw zu, die native SUMO-1.28-Standardtypmap erlaubt nur
`delivery pedestrian bicycle`. Die acht getrennten Verbindungen betreffen zwei
`residential`-/`living_street`-Straßen, deren Verbindungen zum Hauptnetz über
`service`-Straßen führen. Der tatsächliche OSM-Quellgraph und die nativen
Lane-Berechtigungen bestätigen diesen Unterschied. Die öffentliche Zugänglichkeit
und GraphHopper-Teilnetzprüfung allein reichen daher nicht aus.

**Evidenz:** Der negative Erstlauf bleibt unverändert unter
`.runtime/road-locations-regional-2026-10-19/` mit Quell-/Klassenhashes,
`generation-job.json`, Ortsbericht und `sumo/network-coverage.json` erhalten.
Der native Kleinfall unter `.runtime/road-locations-2026-10-19/service-spike/`
reproduziert zwei Wohnstraßennetze, die nur durch eine öffentliche
`service`-Straße verbunden sind: die Wohnstraßen erlauben Pkw, der Verbinder nicht.

**Konsequenz:** Version 2 schließt `service` als Ortskandidaten aus und prüft die
nach Straßenlänge größte schwache Pkw-Komponente ohne `service`-/`track`-Verbinder.
Reguläre Straßenbrücken bleiben als Verbinder erhalten. Ein neuer Regionalversuch
hat unveränderte Quellen, Nachfrage und Seed 42; die neue Vorab-Prognose bleibt
null Endpunkt- und null Verbindungsfehler. Die 300-m-Zuordnung und alle nativen
Berechtigungen bleiben unverändert. Das schwache Teilnetz ist ein konservativer
Ortsfilter, kein gerichtetes Erreichbarkeitszertifikat. Isolierte lokale Netze
können dadurch aus dem Ortsmodell ausgeschlossen werden.


**Zweiter Negativbefund (Version 2):** Die Regionalvorprüfung erreicht alle
**3.839 Anfragen mit null nicht zuordenbaren Endpunkten und null nachweislich
fehlenden Verbindungen**. Alle 2.336 schweren Anfragen behalten unveränderte IDs,
Koordinaten und 125 verschiedene Klassen-/Koordinaten-/Kantenbindungen.
Das tatsächliche native Routing verwirft dennoch drei Fußwege (`p31`, `p38`,
`p75`; letzterer ist der Rückweg der ersten Verbindung): **3.836 von 3.839
Anfragen geroutet**, keine Koordinatenwarnung bei behaltenen Routen. Die
SUMO-Stufe endet vor Ausführung mit FAILED. Damit ist die native Vollabnahme
weiterhin nicht erreicht. Archiv: `.runtime/road-locations-regional-2026-10-19-v2/`.

Die vier gewählten Quellwege dieser Fußverbindungen sind `secondary` mit
`maxspeed=70` beziehungsweise `100`. GraphHopper erlaubt Fußverkehr, SUMOs
`--sidewalks.guess` ergänzt Gehwege nur bis 13,89 m/s. Auf den tatsächlichen
nativen Fahrstreifen fehlt dort die Fußfreigabe; die nächstgelegenen begehbaren
Kanten bilden keine vollständige Verbindung. Der native Kleinfall
`.runtime/road-locations-2026-10-19/sidewalk-spike/` bestätigt: `secondary` ohne
Tempoangabe oder mit 70/100 km/h erlaubt keinen Fußverkehr, mit 50 km/h dagegen
schon. Die Standardgeschwindigkeit von `secondary` ist in dieser Typmap höher
als die Gehwegschwelle.

**Korrektur in Version 3:** Gemeinsame Ortskandidaten benötigen explizit numerisch
höchstens 50 km/h in beiden Richtungen oder einen langsamen nativen Default
(`residential`, `living_street`, `unclassified`). Unbekannte Geschwindigkeitsformate
werden konservativ verworfen. Schnelle Pkw-Verbinder bleiben erhalten. Der
Schritt verschiebt synthetische Orte innerhalb ihrer Zone; er ändert keine native
Berechtigung und schaltet keine Gehwegprüfung aus. Die neue Vorab-Prognose
verlangt zusätzlich null native Routingverluste und null Koordinatenwarnungen
bei behaltenen Routen. Tagesende-Fälle und Teleports werden getrennt bilanziert.


**Widerlegungsversuch und Parserkorrektur:** Die zweite Session findet einen
zusätzlichen Randfall: Java akzeptiert `50d` und Leerzeichen als Zahl, der native
Importer verwirft diese Tempoangaben und verwendet den schnellen Straßendefault.
Der korrigierte Parser prüft deshalb den unveränderten Originalstring auf eine
einfache ASCII-Dezimalzahl. Unit- und native Regressionen sichern die Abweichung
ab; die erneute Gegenprüfung findet keine Umgehung. Unbekannte Einheiten,
Exponenten und Länder-/Zonenformate bleiben konservativ ausgeschlossen.

Der letzte Parserguard wurde nach Einfrieren des Version-3-Regionallaufs ergänzt.
Ein vollständiger Vergleich beider isoliert geladener Produktionsklassifikatoren
über **423.263 tatsächliche OSM-Wege** findet **null Kandidaten- und null
Verbinderunterschiede**; beide wählen 72.140 Kandidatenwege. Alle anderen 99
Produktionsquellen sind unverändert. Quellen-/Klassenhashes und GraphHopper-
Abhängigkeit stehen in
`.runtime/road-locations-regional-2026-10-19-v3/final-policy-equivalence.json`.
Dies belegt die Äquivalenz der Ortsauswahl für diese Eingabe, ist ausdrücklich
kein erneut ausgeführter vollständiger Lauf des letzten Binärstands.


**Dritter Negativbefund (Version 3):** Zulässige begehbare Endpunktstraßen genügen
noch nicht. Der native Lauf routet **3.833 von 3.839 Anfragen**; sechs Fußwege
(`p31`, `p52`, `p58`, `p75`, `p100`, `p196`) fehlen, ohne Koordinatenwarnungen bei
behaltenen Routen. Die SUMO-Stufe bleibt vor Ausführung auf FAILED. Der Lauf und
der damalige Parser-Äquivalenznachweis bleiben unter
`.runtime/road-locations-regional-2026-10-19-v3/` erhalten.

Der zusätzliche Mechanismus sind **begehbare Inseln hinter schnelleren
Pkw-Verbindern**. Auch deren begehbare Standortstraßen liegen in der Pkw-
Hauptkomponente. Der native Gegenfall unter
`.runtime/road-locations-2026-10-19/walk-island-spike/` verbindet zwei Wohnstraßen-
netze mit `secondary, maxspeed=70`: Pkw geroutet, Fußweg verworfen. Der neue
gepaarte native Test bestätigt den Verlust vor der Ortskorrektur und erfolgreiche
Pkw-, Rad- und Fußabschlüsse danach.

**Version 4:** Kandidaten müssen zusätzlich zur Pkw-Komponente in der größten
schwachen Fußkomponente liegen. Sie verbindet begehbare Straßen innerhalb der
nativen Tempogrenze und öffentliche Fußwege; `service`/`track` können Fußverbinder
sein, bleiben aber als gemeinsame Standorte ausgeschlossen. Beide Klassen nutzen
ihre GraphHopper-Zugangsflags. Straßenbrücken bleiben bei klassenkompatiblen
Bedingungen als Verbinder erhalten. Der Ortsbericht weist beide Filter aus.
Der neue Vorab-Lauf verlangt erneut null Endpunkt-/Verbindungsfehler, null native
Routingverluste und null Koordinatenwarnungen bei behaltenen Routen.

**Grenze:** Beide schwachen Hauptkomponenten werden unabhängig global nach Länge
gewählt. Ihre Schnittmenge kann leer sein, obwohl lokal Straßen nutzbar wären;
die Ortswahl scheitert dann ausdrücklich. Gehseiten, Querungen, Radzugänge,
Barrieren und Abbiegebeziehungen bleiben native Prüfgegenstände. Die erneute
Widerlegungsprüfung findet keinen materiellen Codefehler, benennt diese
konservativen Fehlfilter aber ausdrücklich. Es gibt keine neue Pipeline-Stufe,
keine native Freigabenerweiterung und keine Änderung des 300-m-Radius.


### Abschluss Version 4: Routing und Mengenerhalt bestätigt (2026-10-19)

Der vollständige `GenerationService`-Lauf mit eingefrorenem Version-4-Code
endet mit DONE ohne fehlgeschlagene Stufe. Alle 100 Produktionsquellen stimmen
weiterhin mit dem eingefrorenen Stand überein. Unverändert: Quell-OSM (SHA-256
`0a08658bb86eb0aa2f209189f60c639d98745d0b4d3d9fff407f53782b2dc163`),
Seed 42, 54 Zonen, 1.001 synthetische Personen und die Tag-0-Auswahl ohne Trip-Cap.

| Stand | Nicht zuordenbare Endpunkte | Nachgewiesene getrennte Verbindungen | Native geroutete SUMO-Anfragen |
|---|---:|---:|---:|
| Referenz 17.10. | 145 | 6 | vor Routing abgebrochen |
| Ortswahl Version 1 | 20 | 8 | vor Routing abgebrochen |
| Version 2: Pkw-Hauptkomponente | 0 | 0 | 3.836 / 3.839 |
| Version 3: begehbare Standortstraßen | 0 | 0 | 3.833 / 3.839 |
| Version 4: zusätzliche Fuß-Hauptkomponente | **0** | **0** | **3.839 / 3.839** |

**Routing:** Null verlorene Anfragen und null Koordinatenwarnungen bei behaltenen
Routen. Alle 3.839 exportierten Anfragen sind über IDs zwischen Netzvorprüfung,
Routen- und Ausführungsbericht abgeglichen. Der Radius bleibt 300 m. Die 2.336
schweren Anfragen behalten ihre IDs, Koordinaten und 125 verschiedene native
Klassen-/Koordinaten-/Kantenbindungen; `freight-trips.xml` ist bytegleich zur
Referenz. Alle sechs zuvor verlorenen Fußverbindungen erhalten durch den
Fußkomponentenfilter mindestens einen korrigierten Endpunkt in derselben Zone.

**Erhaltene Modelldaten:** Personen- und Aktivitäten-Dateien, Güterfahrten und
Zonen sind bytegleich zur Referenz. Haushalts-/Pkw-Zahl, sämtliche nicht räumlichen
Attribute sowie Zielzonen bleiben erhalten. Alle 515 Pkw stehen am eigenen
Haushaltswohnort. Tatsächlich serialisierte 515 Wohnorte, 2.002 reguläre Fixziele,
3.003 logiTopp-Fixziele und 500 Opportunities liegen in ihren ursprünglichen
LAEA-Zonen. Die Quellenprüfung bestätigt alle 953 gewählten OSM-Wege ohne
Kandidatenregel-Verletzung. Der Fußfilter verwirft zusätzlich 825 Kanten nach
dem Pkw-Filter; alle 54 Zonen haben weiterhin Kandidaten.

**Ausführung bleibt `review-required`:** Alle 3.624 Fahrzeuge wurden gestartet
und beendet, darunter sämtliche 2.336 schweren Fahrzeuge. Alle 215 Fußgänger
starteten; 214 beendeten ihren Weg, einer war am festen Tagesende noch unterwegs.
Es gibt keine ungerouteten, nicht gestarteten oder verworfenen Anfragen. Zwei
Teleports betreffen ein Fahrrad (`v166`) und einen Pkw (`v185`); keine schwere
Güterfahrt hat einen Teleport. Die Fahrzeugabschlüsse sind deshalb keine
Behauptung einer vollständig teleportfreien mikroskopischen Ausführung.

Beide Teleports entstehen nach langem Warten (`yield`) auf `57383418#1_1` vor
Knoten `13959855585`, der als `rail_signal` auf einer gemeinsam genutzten
`highway.secondary|railway.tram`-Straße importiert ist. Das ist ein konkreter
Prüfort, kein bereits bewiesener Signalfehler. Native Signal-, Freigabe- und
Teleport-Einstellungen wurden nicht verändert. Der Fußweg `p214` startet um
23:49 Uhr und benötigt mindestens 2.201,1 m begehbare Kanten; bei seiner nativen
Maximalgeschwindigkeit von 1,30 m/s sind bis Mitternacht höchstens 858 m möglich.
Er kann den festen Tagesabschluss daher nicht erreichen; native Wartezeit und
Zeitverlust sind null. Dieser Tagesende-Fall wird separat ausgewiesen.

**Evidenz:** `.runtime/road-locations-regional-2026-10-19-v4/verified-result.json`,
`execution-review.json`, `population-comparison.json`, `location-verification.json`,
`selected-source-ways.json`, `previous-foot-loss-origin-changes.json`,
`counter-review.json` und die nativen Artefakte desselben Laufs. Das Studio zeigt
neben Mengen, Teleports und Tagesende-Fällen den Status der Ausführungsprüfung
explizit an. Die Implementierung bleibt opt-in (`roadLocatedActivities=false`);
belegt sind Routing/Mengenerhalt dieser Region und dieses Seeds, keine reale
Adresszuordnung, vollständige Gebietsnachfrage oder allgemeine native
Querungs-/Zugangsfreigabe. Die Signalstelle bleibt als separater Prüfpunkt offen.

## Gemeinsame Tram-/Straßen-Signale: nativer Gegenversuch und Importkorrektur (2026-10-20)

**Befund:** Die zwei Teleports des Version-4-Laufs lassen sich in einem nativen
Kleinfall mit geteilter Straßen-/Tramkante und importiertem `rail_signal`
reproduzieren. Fahrrad und Pkw warten jeweils bis zum Yield-Teleport. Im
vollständigen `SumoRunner`-Pfad beendet reine Straßennachfrage nach der
Importkorrektur beide Fahrten ohne Teleport; ein zusätzlicher Fußweg bleibt
vollständig. Ein opaker ÖV-Provider erhält die Signalsteuerung und reproduziert
den negativen Kontrollfall. Das ist ein mechanischer Nachweis unter SUMO 1.28,
keine Behauptung über einen bestimmten upstream behobenen Fehler.

**Verworfene Ansätze:** Leere `--osm.railsignals`-Regeln entfernen zusätzlich
Signal-Teilkanten und ändern Kantenkennungen; globales Verwerfen ohne Erhalt
kombiniert erfasster Straßenampeln verliert deren Steuerung. Die Korrektur
verwendet deshalb `--railway.signals.discard` zusammen mit gezieltem `--tls.set`
für duale Straßen-/Bahnsignalquellen, ausschließlich ohne mögliche Schienennachfrage.
Ein echter beschrankter Bahnübergang bleibt nativ `rail_crossing`; gewöhnliche
Straßenampeln und die Teilkanten `11#0`, `11#1` bleiben im Kontrollnetz erhalten.

**Erster regionaler Gegenbefund:** Der erste neue Regionallauf bricht vor dem
Nachfrage-Routing an der Signalprüfung ab. Zwei Quellenknoten sind nativ
`priority`, weil `--tls.guess-signals` ihre Ampelwirkung an einen nachgelagerten
Kreuzungscluster überträgt. Die Prüfung darf deren Wirkung nicht allein am
Quellen-Knotentyp beurteilen. Version 2 protokolliert deshalb direkte kurze
Zufahrtskanten zu tatsächlich als `traffic_light` importierten Zielknoten;
alle ausgehenden Zufahrten eines akzeptierten Quellenknotens müssen innerhalb
von 25 m in einer solchen Ampel enden. Weiter entfernte oder unkontrollierte
Ziele bleiben Fehler. Eine fehlende Quellen-ID bleibt gesondert prüfpflichtig.
Der Erstlauf ist als negativer Nachweis unter
`.runtime/rail-signal-regional-2026-10-20/` erhalten; er belegt keine ausgeführten
Fahrten. Prognose und Vergleichsregeln für den Folgeversuch stehen vorab in
`.runtime/rail-signal-regional-2026-10-20-v2/preregister.json`.

**Widerlegung:** Die separate Prüfung weist nach, dass native XML-Includes eine
auf ausgewählte ÖV-Records beschränkte Typprüfung umgehen können. Der Guard
prüft nun jedes Element aller drei ÖV-Zusatzdateien; auch verschachtelte Includes,
Typverteilungen und verschobene Typdefinitionen werden abgewiesen. Ein
`roadOnly`-Provider mit tatsächlichen Schienen-/unbekannten Typen scheitert vor
Routing und Simulation. Kontrollfälle stehen in `RoadSignalPolicyTest` und
`RoadSignalIntegrationTest`; die bestehende native Abbiegeverbotsprüfung bleibt
unverändert grün. Mechanik- und Gegenversuchsartefakte:
`.runtime/rail-signal-2026-10-20/`.

### Regionaler Folgeversuch: zwei Teleports beseitigt (2026-10-20)

Der unveränderte regionale Nachfragefall mit Seed 42, 54 Zonen und 1.001 Personen
wurde mit eingefrorenem korrigiertem Signalimport erneut nativ ausgeführt.

| Bilanz | Version 4 vor Signalfix | Mit Signalfix |
|---|---:|---:|
| Native geroutete Anfragen | 3.839 / 3.839 | 3.839 / 3.839 |
| Fahrzeuge gestartet / beendet | 3.624 / 3.624 | 3.624 / 3.624 |
| Teleports | 2 (Rad/Pkw) | **0** |
| Schwere Güterfahrten beendet | 2.336 | 2.336 |
| Fußwege gestartet / beendet | 215 / 214 | 215 / 214 |
| Nicht geroutet / nicht gestartet / vaporisiert | 0 / 0 / 0 | 0 / 0 / 0 |

Alle Anfragen bestehen die 300-m-Netzvorprüfung ohne Endpunkt- oder
Verbindungsfehler; auch das native Routing meldet keine Verluste oder
Koordinatenwarnungen bei behaltenen Routen. Es gibt keinen Güter-Teleport und
kein am Tagesende offenes Fahrzeug. Der Ausführungsstatus bleibt wegen des
bekannten Fußwegs `p214` am festen Tagesende `review-required`; das ist kein
Routingverlust und keine pauschale Vollständigkeitsfreigabe.

**Netzvergleich:** Alle 1.197.429 äußeren Kanten behalten IDs, Start-/Endknoten,
Typen und vollständige Fahrspurdaten einschließlich Geometrie und Berechtigungen.
Alle 3.391 vorher vorhandenen Straßenampelknoten behalten ihren Typ. Die 63
nativen Bahnsignale werden für diesen reinen Straßenlauf entfernt; danach gibt
es 3.402 Straßenampelknoten und kein `rail_signal`. Im klassenbeschnittenen
Regionalnetz existierte bereits vorher kein `rail_crossing`; dessen Erhalt wird
am eigenständigen echten Bahnübergangs-Kontrollnetz geprüft.

Von den 13 dual erfassten Quellenknoten bleiben zehn unmittelbar Straßenampeln;
zwei steuern nativ den folgenden Ampelcluster über 8,24 bzw. 8,35 m lange
Zufahrten. Die verbleibende Quellen-ID `5084349658` ist sowohl im alten als auch
im neuen Netz abwesend. Sie bleibt im Quellenbericht sichtbar prüfpflichtig;
der Fix hat keinen bisher vorhandenen nativen Ampelknoten entfernt. Eine
allgemeine Vollständigkeit des importierten Signalbestands wird nicht behauptet.

**Erhaltene Eingaben:** 15 verglichene Personen-/Aktivitäten-/Haushalts-/Pkw-/
Zonen-/Güter-CSV-Dateien sind bytegleich zum Version-4-Lauf. Der gesamte
Ortsbericht ist nach JSON-Einlesen wertgleich; lediglich die Reihenfolge von
Objektschlüsseln unterscheidet sich. Alle 515 Pkw bleiben am eigenen Wohnort;
die 125 verschiedenen schweren Klassen-/Koordinaten-/Kantenbindungen und
`freight-trips.xml` bleiben unverändert. Quellenhash, Seed, Mengen, Freigaben,
Teleportoptionen und Tagesgrenze werden nicht geändert.

**Verifiziert:** Backend-Suite 305 Fälle, null Fehler, vier ausgewiesene Skips;
sechs gezielte Signal-/Abbiegefälle ohne Skip. Frontend-Code ist gegenüber dem
bereits geprüften Stand unverändert. Alle 101 Produktionsquellen sowie 366
Klassen-/Ressourcendateien stimmen mit dem eingefrorenen Lauf überein. Archiv:
`.runtime/rail-signal-regional-2026-10-20-v2/` mit `verified-result.json`,
`network-comparison.json`, `execution-review.json`,
`v4-input-artifacts-comparison.json` und `quality-gates.json`. Die Ortswahl bleibt
opt-in; dies ist ein technischer Nachweis für einen regionalen Seed, keine
Kalibrierung oder Vollerfassung realen Personen-/Güterverkehrs.

## Öffentlicher Stationsbestand: Registerdetails und Kartenprüfung (2026-10-20)

**Befund:** Der bisherige BNetzA-Basisbestand enthält Ladeeinrichtungen und
Ladepunktzahlen, aber keine getrennten Anschlussdetails. Der zusätzliche
Normalisierer `scripts/prepare-energy-charging.py` verwendet genau denselben
Originalsnapshot vom 01.09.2026. Der Originalhash ist
`cdd8e38f1fa7e8512bd97caa8cf77e9f875436be1f8fe3fe1f2bd9789a504c5b`.
Er akzeptiert nur Einrichtungen der bereits geprüften Basisreferenz; die schon
dort entfernte ungültige Auslandskoordinate wird nicht wieder eingeführt.
Damit bleiben **116.422 Einrichtungen / 209.096 Ladepunkte** erhalten statt
116.423 / 209.098 vor der damaligen Koordinatenbereinigung.

Anschlüsse werden je ursprünglichem Ladepunktslot erhalten; alternative Stecker
werden nicht zu zusätzlichen Ladepunkten. Bei **110 Ladepunkten** stimmt die Zahl
der veröffentlichten Steckertypen nicht mit der Zahl der Leistungsangaben überein.
Deren Leistungszuordnung bleibt unbekannt; der Rohwert wird erhalten. Die
normalisierten Detaildaten besitzen SHA-256
`b3832c49eae29596ca97e644689e56bca078d2fad444bfc5199edc01144bab68`.
Einrichtungsleistung beweist keine gemeinsame Leistungsgrenze und wird keinem
Stecker ersatzweise zugewiesen.

**Evidenz:** Die vollständige Pipeline wurde über die isolierte REST-Anwendung
mit eingefrorenen Produktionsklassen ausgeführt (Seed 42, Vorlagenverhalten,
100 vorgegebene/101 haushaltsbedingt erzeugte Personen, 25 Rasterzonen,
Simulation und Live-OSM aus). Im Rechteck 7,43–7,49° O / 51,49–51,53° N liefert
der reale Registerstand **359 Einrichtungen / 487 Ladepunkte**, Job `DONE`,
keine Quellenfehler. Die Karte zeigt den Bestand samt Attribution; das Popup
„Parkhaus Westentor“ weist zwei Ladepunkte mit je einem Typ-2-Anschluss und
22 kW aus.
Dies ist ein räumlicher Ausschnitt des gespeicherten Registerstands, keine
aktuelle Vollerfassung Dortmunds und kein Nachweis ausgeführter Ladevorgänge.

Ein getrenntes, im Namen und in den Stationen ausdrücklich als **TESTDATEN**
gekennzeichnetes OSM-Szenario prüft echten Multipart-Upload → gespeicherte Quelle
→ vollständige Pipeline → Karte. Vier Eingangsobjekte ergeben drei Meldungen:
eine öffentliche CNG/LNG/LPG-Station, eine H₂-Fläche mit ungeklärtem Zugang und
eine Kundenladestation. Die private Station ist ausgeschlossen. Im Browser
zeigt der Standardfilter genau einen Marker; nach ausdrücklichem Zuschalten
sind es drei, zwei davon visuell abgeschwächt. Das H₂-Popup zeigt 350/700 bar,
ungeklärten Zugang, repräsentativen Mittelpunkt und ungeprüfte Fahrzeugeignung.
Damit ist die Mechanik geprüft, kein realer regionaler Gas-/H₂-Bestand.

**Negativer Quellenbefund:** Die zusätzliche Live-Overpass-Abfrage scheiterte
an HTTP 406 bei der Hauptinstanz bzw. einer 55-s-Zeitüberschreitung beim
GET-Versuch an einer Spiegelinstanz. Eine erfolgreiche aktuelle Gas-/H₂-Abfrage
wird daher nicht behauptet. Netzwerk-/Mirror-Verhalten wird mit lokalem
HTTP-Server geprüft; Quellenfehler führen in der Pipeline zu `FAILED` mit
Qualitätsbericht, nie zu einer erfolgreichen behaupteten Nullversorgung.

**Verifiziert:** `EnergyInfrastructureTest` prüft Objektarten, Zugang,
Druckstufen, getrennte Stecker-/Ladepunktzahlen, echte BNetzA-Details,
Dublettenmarkierung ohne Mengenaddition, bytegleiche Wiederholung und
Regionswechsel. `EnergyInfrastructurePipelineTest` prüft die komplette
Offline-Pipeline und bytegleiche Personen-/Aktivitätendateien beim Abschalten;
`EnergyInfrastructureControllerTest` schützt gültige Eingaben vor fehlerhaftem
Ersatz und verweigert Upload/Refresh während aktiver Jobs.
`EnergyOverpassTest` prüft Abfrageschema und erfolgreichen Spiegelendpunkt.
Zusammen sechs gezielte Fälle ohne Skip. Backend-Suite: **311 Fälle, null
Fehler, vier Skips** (zwei regionale MobileCity-Livequellen und die explizit
freizugebenden actiTopp-/PT-Integrationstests). Frontend: **67 Tests grün**,
Produktionsbuild ohne Warnung; Quelltext-Injektion, verspätete Antworten,
Szenario-/Gebiets-/Jahres-/Quellenwechsel und fehlende Bestände sind abgedeckt.

Kommandos: `mvn -o -Daether.syncContext.named.factory=noop -f backend/pom.xml test`
mit dem vorhandenen SUMO-`PROJ_DATA`; `cd frontend && npm test` sowie
`npm run build`. Belege unter `.runtime/energy-infrastructure-2026-10-20/`:
`implementation/*-tests.log`, `implementation/backend-full.log`,
`implementation/frontend-build.log`, `e2e/source-freeze.json`,
`e2e/inventory-verification.json`, `e2e/fixture-verification.json` und
Karten-Screenshots.

**Konsequenz:** Stationsbestand und Datenqualität sind nutzbar. Eine Freigabe
von Straßenzufahrten, HGV-/Stecker-Kompatibilität, Lade-/Tankstopps oder
fortgeschriebenen Energiezuständen folgt daraus nicht. H2-Stations-API und
MobileCity-Ableitung aus dem gemeinsamen Stationsvertrag bleiben weitere
Integrationsschritte; MobileCity verwendet weiterhin denselben geprüften
Registerbasisbestand ohne OSM-Kapazitätsaddition.

## Stationszufahrt und Fahrzeugkontinuität: native Abnahme (2026-10-20)

**Befund:** Ein räumlicher Stationspunkt genügt weder als gerichteter
Zufahrtsnachweis noch als Grundlage für fortlaufende Energiezustände. Die bisherige
SUMO-Typwahl gilt je Fahrtsegment; dieselbe Typbezeichnung ist keine physische
Fahrzeugidentität. Deshalb wurden eine getrennte Netzprüfung und eine ausdrücklich
zugeordnete Referenzbilanz ergänzt, beide standardmäßig aus.

**Vorab-Spike:** Ein minimales natives Netz weist Fahrspur-/Positionsparameter
für Zu-/Abfahrt sowie den Erhalt von `synthesis.physicalVehicleId` durch
`duarouter` nach. Alle vier Probe-/Fahrtfahrzeuge werden nativ beendet. Eine
externe Bilanz mit ausdrücklich angenommenen 0,2 kWh/km setzt den Endwert der
ersten Fahrt exakt als Anfangswert der zweiten fort. Belege:
`.runtime/energy-access-continuity-2026-10-20/spike/verified.json`, native Eingaben
und Logs. Quellenvertrag: [SUMO Fahrzeug-/Fahrtparameter](https://sumo.dlr.de/docs/Definition_of_Vehicles%2C_Vehicle_Types%2C_and_Routes.html),
[TripInfo mit tatsächlicher Abfahrt/Ankunft und RouteLength](https://sumo.dlr.de/docs/Simulation/Output/TripInfo.html).

**Native Integrationsprüfung:** `EnergyOperationsIntegrationTest` läuft durch
Netzimport → gesonderte Stations-Routing-Proben → unveränderte Anzahl der
Verkehrsfahrten → natives Routing → Simulation → Zustandsbilanz. Stationsseitige
Höhenbegrenzung verhindert positive Profilzuordnungen. Zusätzlich bleibt eine
native Klassenroute über eine benachbarte Straße mit `maxheight=1 m` zunächst
auffindbar; die Quellenprüfung verwirft sie für zu hohe Profile. Damit ist
nachgewiesen, dass Klassenrouting allein diese Maßfreigabe nicht ersetzt.
Beim Wiederholen bleiben die fachlichen Ledger-Inhalte gleich. Native XML-Kommentare
enthalten Erstellungszeiten; deren Datei-Hashes bleiben korrekt je Lauf erhalten
und werden ausdrücklich aus dem fachlichen Gleichheitsvergleich ausgenommen.
Abschalten entfernt alte Netz- und Zustandsberichte.

**Vollständiger Pipeline-Kleinfall:** Isolierter Datenordner, eingefrorene
Produktionsklassen, Seed 42, Vorlagenverhalten, sechs Zonen, Live-Dienste und
logiTopp-Ausführung aus. Zwei ausdrücklich vorbereitete Legacy-Testfahrten
werden durch den regulären Demand-Adapter und die SUMO-Stufe verarbeitet.
Dies ist keine gemessene oder neu von logiTopp berechnete Verkehrsnachfrage.
Job und SUMO-Stufe sind `DONE`.

| Bilanz | Gemessen |
|---|---:|
| Native Verkehrssegmente angefordert / geroutet / gestartet / beendet | 2 / 2 / 2 / 2 |
| Explizite physische IDs in der externen Bilanz | 1 |
| Zugeordnete / nicht zugeordnete Segmente | 2 / 0 |
| Teleports / unvollständige Verkehrsfahrten | 0 / 0 |
| Getrennte Stations-Routing-Proben | 30 |
| Native Proben mit Route / nach Quellen-Profilprüfung ohne bekannte Verletzung | 30 / 20 |
| Stationsmeldungen × Fahrzeugprofile | 2 × 5 = 10 |
| Zugeordnete / gesperrte / nicht bestätigte Profilprüfungen | 5 / 5 / 0 |
| Fahrzeugbilanz bei 0,2 kWh/km | 30 → 29,913788 → 29,827572 kWh |
| Energie-/Identitäts-/Zeit-/Orts-Prüfhinweise im Kleinfall | 0 |

Die zweite Teststation besitzt eine 1-m-Höhenbegrenzung; alle fünf geprüften
Profile überschreiten sie. Die erste Station liefert lokale gerichtete
Routennachweise, weiterhin mit ungeprüfter physischer Einfahrt. Die 30 Proben
stehen ausschließlich in `energy/access-probes.*.xml`; `sumo/routes.rou.xml`
enthält weiterhin nur `v0` und `v1`. In der UI stehen die zehn **Profilprüfungen**
getrennt von zwei Stationsmeldungen und von zwei Verkehrssegmenten. Der
Fahrzeugwert wird als Referenzbilanz gerundet angezeigt, nicht als gemessener
Batteriestand. Screenshot und Daten unter
`.runtime/energy-access-continuity-2026-10-20/e2e/`.

**Gegenfälle:** `VehicleContinuityTest` prüft fortgeführte Werte ohne Reset,
negative Bilanz als offen ausgewiesenes Defizit, unbekannten Verbrauch,
unvollständige Vorgänger, zeitliche Überlappung, Ortsbruch und Zahlenüberlauf.
Ein eigener Diesel-Fall verifiziert Liter statt kWh (10 → 9,9 → 9,7 L).
`EnergyAccessRulesTest` schützt Maße, fehlende Masse, bedingte Angaben,
Schranken und mode-/richtungsabhängige Regeln. `VehicleOperationsContractTest`
prüft das Beispiel sowie doppelte Fahrten, unbekannte Fahrzeug-IDs/Versionen
und nicht zugelassene JSON-Felder. Die bestehende vollständige Offline-Pipeline
prüft zusätzlich die Entfernung alter Berichte bei abgeschalteter/übersprungener
SUMO-Stufe und unveränderte Personen-/Aktivitätendateien.

**Gates:** Backend 320 Fälle, null Fehler, vier bereits bekannte Skips;
Frontend 70 Tests und strict Produktionsbuild grün. Kommandos:
`mvn -o -Daether.syncContext.named.factory=noop -f backend/pom.xml test` mit
vorhandenem SUMO-`PROJ_DATA`, `cd frontend && npm test`, `npm run build`.
Frontend-Prüfungen decken unbekannte Energie, unzugeordnete Nachfrage,
Szenariowechsel, verspätete Antworten, fremde Konfigurationshashes und ungültige
Importe ohne Überschreiben des bisherigen Entwurfs ab. Logs, Quellenfreeze
und Abschlusszahlen liegen unter `.runtime/energy-access-continuity-2026-10-20/`.

**Konsequenz und Grenze:** Lokale Netzprüfung und explizite Identitäts-/
Referenzzustandsführung sind vorhanden. Ein Zugangspunkt kann ein
Flächenmittelpunkt oder eine Registerkoordinate sein; eine reale Zufahrt,
Öffnungszeit, Lkw-Bedienbarkeit oder nutzbare Kapazität wird nicht zertifiziert.
Native Fahrtsegmente bleiben eigenständige SUMO-Fahrzeuge. Die externe Bilanz
verwendet konstante Nutzerverbräuche und erzwingt weder Energiegrenzen noch
Lade-/Tankhalte. Deshalb bleiben `nativeFleetIdentitySimulated=false`,
`energyConstrainsTraffic=false` und `chargingSimulated=false` gesetzt. Gas-/H₂-
Mengen, native Fahrzeugketten und operative Zeitfolgen sind weitere Schritte.

## Native Güterfahrzeugketten (2026-10-21)

**Befund:** Getrennte SUMO-Fahrzeuge pro Fahrt bewahrten bisher zwar eine externe
physische ID, aber weder denselben nativen Fahrzeugzustand noch Bedienzeiten und
Verspätung zwischen Etappen. Der native Spike weist jetzt eine Lieferwagen-ID mit
Hin-/Rückroute, Straßenhalt und Weiterfahrt nach geplanter Abfahrt nach. Ein zweiter
Spikelauf hält das Fahrzeug über das Laufende hinaus fest: Der erste Halt ist
begonnen, aber nicht beendet; eine Folgefahrt darf daraus nicht als gestartet gelten.
Grundlage ist SUMOs [native Halteausgabe](https://sumo.dlr.de/docs/Simulation/Output/StopOutput.html).

| Prüffall | Beobachtung | Konsequenz |
|---|---|---|
| Zustelltour mit Rückfahrt und zusätzlichem Pkw | Drei zugeordnete Etappen werden von einem Lieferwagen gefahren; der Pkw bleibt separat. 120 s Bedienzeit werden vollständig eingehalten; die Folgefahrt übernimmt das tatsächliche Haltende. | Native Fahrzeugzahl und logische Fahrtsegmente getrennt zählen. |
| Zwei schwere Güterrelationen als Hin-/Rückfahrt | Zwei angeforderte und beendete Güterfahrten, ein natives Fahrzeug; bestehende Güterausführungsbilanz bleibt vollständig. | Keine neuen Gütermengen aus der Fahrzeugzuordnung ableiten. |
| Unterschiedliche Folgeorte | Ortsbruch über 25 m bricht vor dem SUMO-Lauf mit `vehicles/native-chains.json` ab. | Weder stilles Versetzen noch ungeplante Leerfahrt. |
| Bedienung dauert über das Tagesende hinaus | Erste Etappe angekommen, zweite noch nicht gestartet; physische Kette unvollständig. SUMOs `vaporized="end"` wird als Tagesabschluss behandelt. | Ankunft einer Etappe, Halteende und Abschluss der Fahrzeugkette unterscheiden. |
| Zusätzlicher Bus mit zwei Halten | Halte ohne Quellfahrtschlüssel bleiben außerhalb der Güterkettenauswertung. | Gemeinsame Halteausgabe auf zugeordnete native IDs filtern. |
| Wiederholung und Abwahl | Identische semantische Kettenberichte bei gleichem Seed; native Zeitstempel-Hashes ausgenommen. Abwahl stellt die bisherige Fahrzeugzahl wieder her und entfernt eigene Kettenartefakte, auch bei übersprungener SUMO-Stufe. | Kein Wiederverwenden von Zuständen oder Berichten eines vorherigen Laufs. |
| Mehrere Fahrzeugtypen im Anwendungsprozess | Der strikte Woodstox-Schreiber verwarf zunächst die zweite `vType`-Definition als zweite XML-Wurzel; getrennte Fragment-Schreiber beheben den Fehler. | Vollständige Anwendungskette zusätzlich zu isolierten nativen Tests prüfen. |

**Vollständiger Pipeline-Prüffall:** Szenario `freight-chains-demo`, Seed 42,
100 angeforderte Personen, sechs Zonen, lokales kleines OSM-Netz. Nachfrage und
Zustelltouren sind ausdrücklich vorbereitete Testdateien; die logiTopp-Ausführung
ist deaktiviert. `GenerationService` und SUMO-Stufe enden beide mit `DONE`.
Zwei physische Güterfahrzeuge bedienen fünf Etappen; ein nicht zugeordneter Pkw
bleibt erhalten. Bilanz: **sechs logische Straßenetappen → sechs beendet; drei
native Fahrzeuge → drei beendet; null Teleports**, vollständige Teleportzuordnung.
Der Lieferwagen startet planmäßig um 240 s, erreicht den ersten Halt um 270 s und
fährt nach 120 s Bedienung um 390 s weiter statt um geplante 300 s. Nach dem zweiten
Halt beginnt die Rückfahrt um 482 s statt um geplante 420 s. Der Lkw wartet zwischen
seinen beiden Etappen bis zum geplanten zweiten Start um 365 s.

Das Studio zeigt fünf Güteretappen mit zwei Fahrzeugen und daneben die vollständige
Sechs-/Drei-Bilanz; Energiezustände werden in diesem Modus nicht als berechnet
angezeigt. Ausführungsbericht, tatsächliche Etappenzeiten und Beispielkonfiguration
sind zugänglich. Screenshot und Eingabe-/Ergebnisnachweise liegen unter
`.runtime/vehicle-chains-2026-10-21/`; 124 Hauptquell-/Ressourcendateien stimmen mit
speziell eingefrorenen E2E-Klassen überein.

**Grenzen:** Explizite Fahrt-/Fahrzeugzuordnung für den ausgewählten Betriebstag,
keine automatische Disposition, keine regionale Güterverkehrskalibrierung. Parken
neben der Straße hat keine Kapazitätsbegrenzung; dynamische Beladung, Gebäudezufahrt,
Fahrerregeln und Energiegrenzen bleiben unmodelliert. Etappendistanzen und -verbrauch
werden bei nativen Ketten nicht aus der gesamten Fahrzeugdistanz geschätzt. Verbrauchs-,
Lade- und Tankausbau sind nach Nutzerentscheidung zurückgestellt; weitere Arbeit gilt
der Güterverkehrsnachfrage und ihrer Integration.

**Abschließende Gates (2026-10-21):** 327 Backend-Testfälle ohne Fehler, davon vier
übersprungen; 71 Frontend-Tests und Produktions-AOT-Build erfolgreich. Native Laufzeit:
SUMO 1.28.0. Zusätzlich stimmen alle 400 eingefrorenen Laufzeitdateien des
Pipeline-Prüflaufs mit dem geprüften Build überein.


## Zusätzliche Unternehmenstouren: Import bis native Ausführung (2026-10-21)

**Befund:** Der synthetische Import durchläuft echte Upload-/Mapping-/Kontext-/
Aktivierungsdienste und anschließend natives SUMO 1.28.0. Zwei PKW bleiben trotz
konfigurierter Trip-Obergrenze 1 erhalten; ein schwerer LKW bedient zwei Kundenhalte
und kehrt zurück: drei zusätzliche Fahrtetappen, ein physisches Tourenfahrzeug,
drei Fahrzeuge insgesamt. Je Halt gilt eine Mindestbedienung von 60 Sekunden.
Wiederholung erzeugt denselben Ausführungsbericht. Ein gezielt fehlender nativer
Fahrzeugabschluss verhindert eine abgeschlossene Tour trotz beobachteter Halte.
Ein echter früher Fehler des anschließenden Netzbaus ersetzt den Erfolgsbericht
durch `not-executed` und entfernt das alte Routingergebnis.

**Evidenz:** `mvn -o test`: 333 Tests, 0 Fehler, 4 Skips (gesondert freizuschaltende
actiTopp-/PT-/MobileCity-Quellenfälle). Die abschließend ergänzte Abschlussregression
besteht zusätzlich mit `mvn -o -Dtest=CompanyTourExecutionTest,CompanyTourIntegrationTest test`:
6 Tests, 0 Fehler/Skips. `npm test`: 74 Tests grün; `npm run build`: Produktions-AOT
besteht. Isolierte Browserprobe auf 8092/4302 mit synthetischem Kontext: Aktivieren,
Unternehmenskennung ändern, Generierung und Aktivierung gesperrt, Kontext speichern
entzieht Auswahl, erneutes Aktivieren erfolgreich. Eingaben, Logs und Screenshot
unter `.runtime/company-tours-2026-10-21/`; keine Originalkundenadressen verwendet.

**Konsequenz:** Umsetzungspakete Kontext, lokale Ortszuordnung und additive
Fahrzeugtouren sind technisch abgesichert. Das ist keine regionale Abnahme echter
Unternehmensmengen oder Garantie individueller LKW-Zulässigkeit. Regeln,
API und offene Pakete stehen in [docs/21, Abschnitt 28](21-gueterverkehr-konzept-und-datenquellen.md#28-unternehmenstouren-kontext-lokale-ortszuordnung-und-zusätzliche-sumo-ausführung-2026-10-21).


## Gemeinsame Nachfrage unabhängig von SUMO (2026-10-21)

**Befund:** Der gemeinsame Vertrag `synthesis-traffic-demand-v1` enthält alle
verfügbaren Beiträge vor dem Zieladapter. Ein echter Pipeline-Lauf ohne SUMO und
bei deaktivierter logiTopp-Laufzeit exportiert zwei schwere Güterfahrten plus eine
Unternehmenstour mit zwei Kundenhalten und Rückfahrt: fünf Bewegungen und eine
Tour, Personen- und Paketquelle ausdrücklich `not-generated`. Wiederholung ist
bytegleich. Bei ausgeschalteten Zusatzbeiträgen bleiben synthetische Personen
bytegleich; frühere rohe Personen-Ergebnisse werden nicht als neuer Eingang gelesen.
Auch ausgewähltes, betrieblich deaktiviertes SUMO darf seinen abweichenden Wochentag
nicht vor dem Überspringen gegen den gemeinsamen Bestand validieren.

**Evidenz:** `TrafficDemandTest` prüft die vier Quellen, geplante Ankunft/Bedienung,
Tourreferenzen, JSON-Roundtrip und ausschließlich als ungeroutet deklarierte
Start-Ziel-Geometrien. Unbekannte oder unvollständige Pakettabellen werden im
strengen gemeinsamen Pfad abgewiesen. Eine Schienen-Pakettour bleibt im allgemeinen
Bestand erhalten, obwohl der Straßen-SUMO-Adapter sie nicht unterstützt.

Der native SUMO-1.28.0-Test liest den gemeinsamen JSON-Export erneut ein: zwei PKW,
zwei schwere Fahrten, zwei Paketetappen und drei Unternehmensetappen ergeben neun
Anfragen und sieben Fahrzeuge. Der Unternehmens-LKW behält über Halte und Rückfahrt
seine Identität; globale Fahrzeugtypverteilung und allgemeine Operations-Ausführung
sind dabei ausgeschaltet (`catalogOnly`). Das Löschen der ursprünglichen Pakettabelle
verändert Wiederholung und Tourbericht nicht. Der bekannte frühe Fehlerfall und ein
gezielt fehlender physischer Abschluss bleiben korrekt ausgewiesen.

**Bedienprobe:** Isolierter Server auf 8092/4302, synthetisches Szenario ohne SUMO,
vorbereiteter Kontext und Aktivierung über dieselbe Import-API. Generierung aus dem
Studio erfolgreich; Ergebnisbereich zeigt fünf Bewegungen, eine Tour, Herkunft und
fehlende Quellen sowie Downloadlinks für JSON, CSV und GeoJSON. Die bestehende
Studio-Voreinstellung fordert bei der Bedienprobe zusätzlich das Straßennetz für
Personen-Fahrzeitmatrizen an; dafür lief GraphHopper, kein SUMO. Screenshot und
Testlogs: `.runtime/common-demand-2026-10-21/`.

**Grenzen:** Kein neuer individueller Gütertourenimport in MobileCity oder im
nativen Legacy-Modell; diese Adapter brauchen ihre eigene Formatabbildung.
Personen-KPIs bleiben personenbezogen, der gemeinsame Bestand ist kein Nachweis
vollständiger regionaler Gütermengen oder zulässiger LKW-Straßenrouten.
[Vertrag und Ablauf](21-gueterverkehr-konzept-und-datenquellen.md#30-gemeinsame-nachfrage-vor-den-zieladaptern-2026-10-21).


**Abschließende Gates:** `mvn -o test`: 339 Tests, 0 Fehler, 4 Skips
(explizit freizuschaltende actiTopp-/PT-/MobileCity-Quellenfälle).
Die letzte Adapter-/Skip-Regression besteht zusätzlich mit 15 Tests ohne Skip
(`TrafficDemandTest,CommonTrafficDemandPipelineTest,CompanyTourIntegrationTest,FreightDemandGeneratorTest,VehicleOperationsContractTest`).
`npm test`: 75 Tests grün; `npm run build`: Produktions-AOT erfolgreich.
`git diff --check` ohne Befund. Temporäre Testserver und Browserprüfung beendet.

## Formatübergreifender Tourenimport (2026-10-22)

**Befund:** Unterschiedliche Quellen lassen sich auf einen gemeinsamen,
prüfbaren Tourenentwurf abbilden. Die Strukturprüfung eines LLM-Ergebnisses
allein reicht nicht: In den realen Modellproben traten unbekannte Zielfelder,
ein Feld je Zeile und falsche Vorkommensnummern von Zitaten auf. Diese Vorschläge
wurden abgewiesen bzw. als ungültiger Entwurf gekennzeichnet und nicht aktiviert.

**Umsetzung:** Der Ausgabevertrag verlangt vollständige Halt-/Auftragsobjekte
und wörtliche Zitate mit Blockkennung. Zeichenpositionen berechnet der Server.
Ein vollständiges Formbeispiel, ausdrückliche Trennung verwendeter/ausgelassener
Blöcke und feste Zielfeldnamen verbessern den lokalen Modellpfad. Die Prüfung
aller Tourenzeilen bleibt notwendig; es wird keine allgemeine Erkennungsquote
oder Vollständigkeit beliebiger Dokumente behauptet.

**Evidenz:**

- `TourSourceReaderTest`: verschachtelte JSON-Elternwerte, XML-Attribute,
  führende Postleitzahl-Nullen, Quellreferenzen, Text/DOCX und Abweisung von
  XML-Entitäten sowie übertiefen Strukturen. Ein breiterer Titel vor der
  Kopfzeile darf die Breite der eigentlichen Tabelle nicht verändern.
  Feste Weiterführung/Adressverbindung und kompakte Uhrzeiten werden gegen den
  resultierenden Tourenentwurf geprüft.
- `TourSourceAnalysisTest`: unveränderte Quellzitate, sichtbare ausgelassene
  Inhalte, keine erfundenen Werte, keine überlaufenden Ganzzahlen, vollständige
  Blockklassifikation und getrennte Betriebsflags. Tabellenanalyse sendet
  Rohproben nur im ausdrücklich aktivierten Modus; numerische Spaltenindizes
  dürfen nicht durch Zeichenketten eingeschleust werden.
- `TourImportAssistantTest`: Upload → lokaler HTTP-Testserver → Vorschlag →
  Ablehnung ohne Quellenprüfung → bestätigter Entwurf → Profil → neue Quelle
  mit neuer Analyse. Kein Fahrzeug-/Depotkontext und keine Aktivierung werden
  dabei erfunden. Tabellenprofile lehnen geänderte Header ab.
- Echte lokale PDF-Modellproben mit Python/pypdf **6.10.0** und
  **qwen2.5:7b-instruct**: ein Halt sowie anschließend zwei Halte mit gegenüber
  dem Prompt-Beispiel veränderten Tour-/Vorgangskennungen, Adressen, Zeiten und
  Datum. Zweite Probe: Tour `Z42`, zwei Halte, zwei zugeordnete Zeilen, null
  verworfene Zeilen, PLZ `07654` unverändert. Reale PDF-Extraktion und echte
  lokale HTTP-Modellantwort, keine Parser-/LLM-Attrappe.
- Bedienprobe auf isoliertem Testszenario (Ports 8092/4302): bestätigtes Mapping
  als Unternehmensprofil gespeichert, Profil erneut als Vorschlag angewendet;
  Nachfrageaktivierung dabei nicht angeboten. Screenshot und Logs:
  `.runtime/tour-assistant-2026-10-21/import-profile.png`.

**Prüfstand:** Vollständige Backend-Suite ohne Fehler mit vier bestehenden optionalen
Skips. Danach gezielte Abschlussregression für die letzten Import-Vertragsänderungen
und zusätzliche Typprüfung: **27/27 Tests grün, kein Skip**. **81 Frontendtests**
und Produktionsbuild grün. Logs und ausschließlich synthetische Proben unter
`.runtime/tour-assistant-2026-10-21/`. Die Abschlussregression ist in `backend-final.log` und den Surefire-Berichten
dokumentiert.

**Konsequenz:** Unterstützte Formate sind durch Extraktionsadapter begrenzt.
Scans benötigen externe OCR; übergroße Dokumente werden vollständig abgewiesen.
Quellbelege verhindern erfundene Feldwerte, beweisen jedoch nicht, dass alle
Aufträge erkannt oder fachlich richtig gruppiert wurden. Bestätigung und
nachgelagerte Kontext-/Aktivierungsprüfung bleiben verbindlich.

## Tourenrevisionen vor der gemeinsamen Nachfrage (2026-10-23)

**Befund:** Verschiedene Quelldateien derselben Unternehmensplanung erhalten
unterschiedliche Quellhashes und gesalzene Tour-/Auftrags-IDs. Selbst die
bestehende Prüfung physischer Fahrzeuge verhindert eine doppelte Auswahl nicht,
wenn die neue Datei ein anderes Fahrzeug verwendet.

**Fix:** `CompanyTourRevisions` prüft die ausgewählten Touren auf identische
Unternehmenskennung, Simulationsdatum und Quell-Tourkennung. Die Prüfung ist
Teil der Batch-Bildung und läuft sowohl beim Aktivieren als auch beim erneuten
Einlesen/Einfrieren ausgewählter Eingaben. Der vorhandene Fahrzeugkonfliktschutz
bleibt bestehen; es gibt keine zusätzliche Mengenanpassung des Personenverkehrs
oder statistischen Güterverkehrs.

**Evidenz:** Vier Tests in `CompanyTourRevisionsTest`, ohne externe Werkzeuge:

| Fall | Nachweis |
|---|---|
| Andere Datei, andere Planzeiten und anderes Fahrzeug, gleiche fachliche Tour | Zweite Aktivierung wird abgewiesen; Meldung nennt Tour und bisherigen Import. Gespeicherte Szenariodatei bleibt bytegleich. |
| Bewusster Wechsel | Erst alte Auswahl deaktivieren, dann neue Revision auswählen. Beide Quellimporte bleiben vorhanden; ein zuvor eingefrorener Batch bleibt unverändert. |
| Direkte Auswahl / verschobenes Quelldatum | Batch-Bildung verweigert auch direkt eingereichte doppelte Auswahl. Ein auf den gleichen Szenariotag verschobener Quelltag umgeht den Schutz nicht. |
| Additive und teilweise ausgewählte Importe | Verschiedene Unternehmen oder Tourkennungen ergeben weiterhin drei Importe/neun Fahrtetappen. Eine ausgeschlossene kollidierende Tour blockiert eine andere ausgewählte Tour derselben Datei nicht: zwei Importe/sechs Etappen. |

**Gates:** Vollständige Backend-Suite: 358 Tests, keine Fehler, vier bestehende
optionale Skips. Alle vier neuen Revisionsprüfungen ohne Skip bestanden.
Frontend: 81 Tests und
Produktionsbuild grün. Die lokale Haltorttabelle bietet wieder ausschließlich
CSV/TSV/XLSX zur Auswahl; die erweiterten Formate gelten für Tourenplanungen.
Vollständige Backend-Regression und Aufzeichnungen unter
`.runtime/tour-revisions-2026-10-23/`.

**Konsequenz:** Schutz gegen gleiche fachliche Kennungen, keine unscharfe
Revisionserkennung bei umbenannten Unternehmen/Touren. Bei Kollision muss die
frühere Auswahl bewusst entfernt oder ihre betroffene Tour ausgeschlossen werden.
Die additive Einspeisung gegenüber bestehender Verkehrsnachfrage bleibt erhalten.

## Regionale Unternehmenstouren mit externen Orten (2026-10-23)

**Befund:** Die bisherige harte Regionsprüfung blockierte auch Touren mit
externem Start-/Enddepot oder einzelnen externen Kundenhalten. Eine bloße
Entfernung der Prüfung hätte Koordinaten außerhalb des Netzes an SUMO übergeben
und bei Wiedereinfahrten eine künstliche Ortsverbindung im regionalen Netz
verlangt.

**Fix:** Opt-in `Context.regionalProjectionConfirmed` mit Default `false`.
Originaletappen bleiben im gemeinsamen Nachfragevertrag; ein eigener Bericht
klassifiziert jede Etappe und erzeugt die ausdrücklich bestätigte regionale
Näherung. Grenzpunkte werden geometrisch aus geraden Verbindungen, Zeiten aus
proportionalen Planfahrzeiten bestimmt. SUMO übernimmt nur die regionalen
Etappen; externe Kundenbedienung wird nicht auf einen Grenzpunkt übertragen.
Wiedereinfahrten teilen native Fahrzeugketten in getrennte Gebietsbesuche,
behalten aber die physische Fahrzeugkennung und alle Quellreferenzen.

**Evidenz:**

- Vorabprobe mit echten `netgenerate`, `duarouter` und `sumo`: Zwei native
  Besuche mit derselben physischen Kennung kommen um 14 und 314 Sekunden an.
  Kein virtueller Verbindungstrip zwischen den Besuchen.
- `CompanyTourRegionTest`, vier Fälle: Ein-/Ausfahrt, Durchgang, reine
  Außenetappe und Tangenten; interpolierte Zeiten; interne/ externe Bedienung;
  unveränderte Originaletappen; physische Identität; deterministische Projektion;
  Freigabe erforderlich; unvollständige Koordinaten als Prüfpunkt; JSON-Roundtrip
  der gemeinsamen Nachfrage; Regionsänderung invalidiert die Freigaberevision;
  Default-Kontext bleibt mit dem vorherigen Hashaufbau kompatibel.
- Neuer echter Fall in `CompanyTourIntegrationTest`: LKW mit externem Start
  und Ende, internem und externem Kundenhalt sowie Durchgang bei der Rückfahrt.
  Gemeinsame Nachfrage: ein privater Weg plus drei originale Unternehmensetappen,
  externe Koordinaten unverändert. SUMO: ein privates Fahrzeug und zwei native
  Gebietsbesuche desselben physischen LKW. Ein physisches Fahrzeug, zwei native
  Besuchs-IDs, drei regionale Etappen, keine externe Kundenbedienung am Austritt.
  Nativer Bericht `complete`, Besuchszeiten konsistent; Unternehmensbericht
  `regional-complete`, ein regional abgeschlossener Touranteil, **null bestätigte
  vollständige externe Tourabschlüsse**. Wiederholung identisch. Keine optionalen
  Skips in dieser nativen Prüfung.
- Bestehender vollständiger interner Tourfall und fünf Tests für native
  Fahrzeugketten bleiben grün.
- Bedienprobe mit synthetischen Daten auf isolierten Ports 8092/4302: Externe
  Planung zunächst gesperrt, Freigabe setzen, Kontext speichern, anschließend
  erfolgreich zur Nachfrage auswählen. Erklärung der Modellannahme und
  Standard `false` durch Komponentenprüfung abgesichert. Screenshot:
  `.runtime/company-boundary-2026-10-23/external-tour-active.png`.

**Gates:** Vollständige Backend-Suite 363 Tests ohne Fehler, vier bestehende
optionale Skips; vier neue Regionsprüfungen und beide nativen Unternehmenstourfälle
ohne Skip. 82 Frontendtests und Produktionsbuild grün. Auch die abschließende Gesamtsuite nach Absicherung fehlender Koordinaten
ist ohne Fehler durchgelaufen (`backend-final.log`).
Aufzeichnungen und ausschließlich synthetische Eingaben:
`.runtime/company-boundary-2026-10-23/`.

**Konsequenz und Grenzen:** Der erste Adapter macht externe Touren verwendbar,
aber keine Aussage über exakte Straßenführung oder echte Eintrittszeiten
außerhalb des Gebiets. Gerade Verbindungen können reale Netzübergänge verfehlen;
netzgenaue Gateways und bestätigbare manuelle Grenzübergänge sind weitere Arbeit.
Reine Außenetappen bleiben ausdrücklich im Originalbestand und Projektionsbericht.
Externe Fahrten, externe Bedienung und Verzögerungen zwischen Gebietsbesuchen
werden nicht simuliert. Gleichzeitige native Besuche desselben physischen
Fahrzeugs führen zur Nachprüfung. Mehrtägigkeit und 24-Stunden-/Zeitumstellungs-
Regeln bleiben eigenständige offene Aufgaben.


## Netzbasierte Unternehmenstouren und Zurbrüggen-Import (2026-10-23)

**Fragestellung:** Können regionale Besuche aus vollständigen Straßenrouten
anstatt der Verbindungsgeraden erzeugt und bis zur nativen Ausführung erhalten
werden? Wie weit trägt der Import der tatsächlich bereitgestellten CSV?

**Native Evidenz:** `CompanyTourNetworkIntegrationTest` aktiviert eine synthetische
LKW-Tour mit externen Originalorten. Eine gekrümmte Straße durchquert das Gebiet,
obwohl die Verbindungsgerade außerhalb liegt. Alle drei Originaletappen bleiben
in der gemeinsamen Nachfrage; beide regionalen Besuche werden auf den
vorgeschriebenen Straßenfolgen als ein physisches Fahrzeug ausgeführt. Der Bericht
lautet `regional-complete`; die Wiederholung liefert denselben Projektionsbericht.
Ein absichtlich außerhalb der Netzabdeckung liegender Originalort stoppt den
Folgelauf mit `network-paths.status=failed` und `execution.status=not-executed`.
`CompanyTourRegionTest` prüft zusätzlich mehrere getrennte Gebietsbesuche innerhalb
einer Originaletappe und fehlende Straßenpfade ohne Luftlinienersatz.

**Bedienprobe:** Synthetischer gespeicherter Tourenkontext auf isolierten Ports
8092/4302. Neue Netzoption setzen, Kontext speichern und den gespeicherten
Backendwert prüfen. Komponentenprüfung sichert den Standard `false`, die
Abhängigkeit von der regionalen Freigabe sowie das Löschen der Netzoption bei
Abwahl. Screenshot `.runtime/company-network-2026-10-23/network-context.png`.

**Realer Import:** Direkt gelesene Zurbrüggen-CSV vom 29.09.2026, unveränderte
Quellfassung mit SHA-256 `454746df8eb9df41c30cac6d5ddac19e5d9d9b6272f8328af05450ffff5c6bca`.
Aktuelle Produktions-API: 222/222 Zeilen ohne Zurückweisung, 41 Tourgruppen,
187 Haltkandidaten; jede Quellzeile genau einmal als Auftrag erhalten. Bestätigtes
strukturelles Mapping und JSON-Export stimmen überein. Drei Kundenfenster-
abweichungen und ein Fensterkonflikt bleiben sichtbar. Ohne Unternehmenskontext
liefert die Aktivierung HTTP 400. Es gibt **keinen realen regionalen SUMO-Lauf**:
Modellregion, Depots/Zeiten, Fahrzeugzuordnung, lokale Koordinaten und fachliche
Bestätigungen fehlen. Private Daten ausschließlich im ignorierten Laufverzeichnis,
keine Kundendaten als versionierte Fixtures.

**Gates:** `mvn -o test`: 365 Tests, keine Fehler, vier bestehende optionale Skips.
Neue native Integration ohne Skip. `npm test -- --watch=false`: 83 Tests grün;
`npm run build` grün. Logs und Importaudit unter
`.runtime/company-network-2026-10-23/`.

**Grenzen:** Gerichtete Klassenberechtigungen des tatsächlich gebauten Netzes
sind maßgeblich; keine pauschale Zertifizierung individueller Abmessungen oder
Gewichte. Grenzzeiten bleiben aus Planzeiten und freien Fahrzeitanteilen geschätzt;
externe Bedienung, Verzögerungen und Kreuzungswartezeiten werden damit nicht
vollständig simuliert. Interaktive Netzvorschau vor Generierung, manuelle
Gateway-Overrides und Mehrtägigkeit bleiben offen.


## Rückfragen und Depotantworten im Tourenimport (2026-10-24)

**Befund:** Fehlende Betriebsangaben werden nun als konkrete Rückfragen neben dem
bearbeiteten Kontext angezeigt. Eine gespeicherte Adresse beseitigt ausschließlich
die Frage nach dem Depot, während fehlende Koordinaten, Fahrzeuge und Zeiten offen
bleiben. Bei Adressänderungen werden alte Positionen und die Ortsbestätigung
zurückgesetzt. Die Fragenliste verwendet nur ausgewählte Touren und funktioniert
bei deaktiviertem LLM.

**Evidenz:** `CompanyTourExecutionTest.depotAnswersPersistWhileCoordinatesRemainRequiredAndRevisionsStayExplicit`
speichert unvollständige Depotantworten über den Importservice und liest sie erneut,
prüft leere Koordinaten und die Aktivierungssperre, geänderte Revisionen bei
Adressantworten, unveränderte Revisionen bei leeren Antworten sowie die Ablehnung
fremder Tour-IDs. Frontend-Komponentenprüfungen sichern verbleibende Rückfragen,
Übernahme ausschließlich ausgewählter Touren, getrennte Start-/Endadressen,
Zurücksetzen alter Punkte und keinen Antwortübertrag auf andere Szenarien.

Die UI-Probe auf isolierten lokalen Ports 8092/4302 übernimmt und speichert die
vom Nutzer bestätigte gemeinsame Depotadresse für alle 41 Zurbrüggen-Tourgruppen.
Der erneut gelesene Backendkontext enthält genau 41 Adressantworten, leere
Depotkoordinaten und `ready=false`, `active=false`. Die Depotfrage ist beantwortet;
die Fragen nach Koordinaten, Fahrzeugen und Zeiten bleiben sichtbar. Das verwendete
Testszenario bleibt eine synthetische Prüfregion, kein tatsächlicher Betriebsraum.
Screenshot und private Antworten unter
`.runtime/company-import-questions-2026-10-24/`.

**Konsequenz:** Unvollständige Antworten können gespeichert und später ergänzt
werden. Die Aktivierung bleibt von der bestehenden Backendprüfung abhängig.
Adressgeokodierung, freie LLM-Antwortdialoge und reale regionale Ausführungsabnahme
sind mit dieser Bedienänderung nicht nachgewiesen.


**Gates für den Stand 2026-10-24:** Vollständiges `mvn -o test`: 366 Tests,
keine Fehler, vier bestehende optionale Skips. `npm test -- --watch=false`:
85 Tests grün. `npm run build`: grün. Logs unter
`.runtime/company-import-questions-2026-10-24/`.


## Lokale Geokodierung und regionaler Zurbrüggen-Pilot (2026-10-24)

**Befund:** Ortsvorschläge können lokal eingelesen und bewusst übernommen werden.
Ein nativer regionaler Lauf mit einer real importierten Unternehmenstour zusätzlich
zu privaten Kontrollfahrten ist erfolgreich. Dies ist eine begrenzte Modellabnahme.

**Evidenz:** Aus 222 Quellzeilen entstehen 41 Tourgruppen mit 187 Halten. Der lokale
Abgleich gegen 4.509.543 amtliche NRW-Referenzen verortet nach Normalisierung von
Ortszusätzen wie „Westf“ 155 Halte; 32 bleiben unverortet. 19 Tourgruppen sind
vollständig verortet. Mehrdeutigkeiten werden erhalten. Qualität A belegt weder
Postleitzahlen noch geeignete LKW-Einfahrten. Quellen:
[NRW-Gebäudereferenzen](https://www.bezreg-koeln.nrw.de/geobasis-nrw/produkte-und-dienste/liegenschaftskataster/aktuelles-liegenschaftskataster/gebaeudereferenzen),
[Downloadbestand](https://www.opengeodata.nrw.de/produkte/geobasis/lk/akt/gebref_txt/).
Verwendete ZIP vom 01.07.2026, SHA-256
`8f3cc3398799c2e6358f22e1272e435c8dcc3375de1597da1b3339251d4692a8`.
Quelltag 29.09.2026 und lokaler Straßensnapshot vom 15.10.2026 stimmen zeitlich
nicht exakt überein; keine historische Rekonstruktion zugesichert.

**Modellannahmen:** Auf ausdrückliche Nutzerdelegation wird pro Quelltour ein
eigenes generisches Fahrzeug `standard.heavyTruck.diesel:1` vorgeschlagen.
Depotabfahrt eine Stunde vor erster Ankunft, Rückkehr eine Stunde nach letzter
Abfahrt; Fensterregel `report-only`. Keine Aussage über die tatsächliche Flotte.
Die Nutzeradresse ist gemeinsames Start-/Enddepot. Das Pilotgebiet ist ein rund
3,5 × 4,4 km großes Rechteck um das Depot. Zwei Kundenhalte der ausgewählten Tour
liegen außerhalb. Der vollständige Kontext bleibt wegen fehlender Punkte unbestätigt.

**Native Messung:** Genau eine der 41 Quelltouren wurde aktiviert. Gemeinsame
Nachfrage: drei private synthetische Kontrollfahrten plus drei originale
Unternehmensetappen. Regionale Projektion: drei → zwei Unternehmensetappen,
eine außerhalb liegende Etappe nicht regional ausgeführt. Ein physischer LKW,
zwei native Gebietsbesuche, beide abgeschlossen; null Teleports. Bericht
`regional-complete`, ein regionaler Abschluss, null vollständige Tourabschlüsse,
`outsideExecutionSimulated=false`. Laufzeit rund 236 s. Die drei privaten
Kontrollfahrten bilden keine kalibrierte regionale Privatnachfrage ab.
Der Scratch-Aufruf meldet nativen Seed 0; Szenariovorlage 42. Daher keine
durchgängige Seed-42-Reproduzierbarkeit behauptet.

Das Netz wurde aus einem vorhandenen lokalen OSM-Ausschnitt gewonnen. Es wurde
keine Tourengebiets-Anfrage an Overpass versendet. Netz-Hash:
`d3688a122faa0a32d951e8faa0fc6e17cdd019b53c7d0f7535487b13e08e80b5`.
Private Kontexte, Kandidatenindex, Berichte und Logs liegen ausschließlich unter
`.runtime/company-geocoding-2026-10-24/`; Originaladressen gehören nicht in Fixtures.
Die erste Pilotkontextdatei hält ihren damaligen Stand fest; der vollständige
Modellkontext enthält den später verbesserten Abgleich mit 155 Halten.

**Gates:** 371 Backendtests, null Fehler, vier bestehende optionale Skips;
86 Frontendtests, Produktionsbuild und zwei Pythonprüfungen grün. HTTP-Fixtures
prüfen Ambiguität, Cache, ungültige Punkte und Redirect-Ablehnung. Komponentenprüfungen
sichern gespeicherten Kontext, bewusste Auswahl und veraltete Adressantworten.
Ein Integrationstest führt Geocoder-Auswahl bis zur nativen Ausführung durch.
Manueller Browser-Gegentest blieb offen: Die automatische Freigabeprüfung konnte
den lokalen Frontendstart wegen ihres Nutzungslimits nicht freigeben.

**Konsequenz:** Die technische Kette und erste regionale Abnahme sind belegt.
Vor Gesamtbestands-Abnahme verbleiben 32 Ortszuordnungen, Bestätigung/Verfeinerung
der Modellannahmen und Ausführung aller ausgewählten Touren. Externe Bedienung,
reale Fahrzeugdimensionen und Betriebszeiten sind gesondert zu validieren.


<!-- BEGIN_COMPANY_COMPLETION_2026_10_26 -->

## Tourenbestand und fortsetzbare Ortssuche (2026-10-26)

**Befund:** Lokale Zuordnung steigt von 155 → 185 der 187 Quellhalte. Die regionale
native Bestandsprüfung umfasst 38 der 41 Quelltouren; drei Touren bleiben
mit konkretem Daten-/Netzgrund ausgeschlossen. Der vollständige Originalimport
bleibt unverändert. Das ist eine technische Teilabnahme mit Modellannahmen.

**Adress-Evidenz:** Erweiterte Formatnormalisierung und ausdrücklich geprüfte
Gemeinde-Aliasse, amtliche NRW-Gebäudereferenzen sowie ergänzende lokale OSM-Abzüge.
OSM-Standardabgleich: Straße, Hausnummer und Postleitzahl; bei ausschließlich
fehlender OSM-Postleitzahl optional Gemeindeabgleich (hier zwei zusätzliche Treffer).
Drei Schreibkorrekturen, ein gewählter Qualität-B-Flurstückspunkt und ein Gebäude
als Repräsentant einer Quell-Hausnummernspanne sind ausdrücklich dokumentiert.
Keine automatische unscharfe Ersetzung und keine bestätigten LKW-Einfahrten.
Die zwei verbleibenden Punkte betreffen zwei Touren; serverseitige Prüfung aller
41 Touren verweigert die Aktivierung. Private Adressen und Kontexte ausschließlich
unter `.runtime/company-completion-2026-10-25/` (Arbeitsbeginn 25., Abschluss 26.10.).

**Negative Netzbefunde:** Der erste geometrische Bestandsversuch stoppt bei zehn
nicht zuordenbaren Endpunkten und vier nicht gerichteten Verbindungen. Danach
wird ein größerer lokaler Straßenabzug erstellt. Prüfung aller 216 Originaletappen
der 39 ortsvollständigen Touren: zwei nicht verbundene Etappen derselben Tour,
keine verbundene Straßenalternative innerhalb 150 m. Der betroffene gemappte Halt
ist im verwendeten Netz nicht mit der LKW-Klasse verbunden; keine Aussage über ein
tatsächliches Fahrverbot. Diese Tour wird für die abschließende Teilabnahme zusätzlich
ausgeschlossen; eine überprüfte Zufahrt bzw. belegte Netz-/Fahrzeugkorrektur
bleibt erforderlich. Beide Fehlversuche und der vollständige Audit sind erhalten. Ein weiterer Versuch
wurde bei nahezu gefülltem 6-GB-Java-Heap unterbrochen. Die anschließende kompakte
Haltung von Kantenlänge und Geschwindigkeiten ersetzt zusammengesetzte String-Keys
und geboxte Map-Werte, bei unveränderten Berechtigungs- und Zeitregeln.

**Native Evidenz:** 210 ursprüngliche Unternehmensetappen und drei
synthetische private Kontrollfahrten in der gemeinsamen Nachfrage. Daraus
85 regionale Unternehmensetappen; 38 physische LKW,
84 native Gebietsbesuche und 38 regionale Abschlüsse,
0 Teleports. `regional-complete`, `outsideExecutionSimulated=false`;
0 vollständige Tourabschlüsse. Projektionsmethode
`directed-network-edge-free-flow-plan-time-scaling`. Netz-Hash
`6c5da172e480879a8300ffa2e800b1934d4cecdc7bbd09f2db683f805c8126d8`. Nativer Seed 42; keine Behauptung eines durch vollständigen
Zweitlauf bewiesenen identischen Ergebnisses. Externe Bedienung bleibt ungemessen.
Private Kontrollfahrten sind keine kalibrierte regionale Nachfrage; Flotte und
Depotzeiten bleiben delegierte Modellannahmen. Der pauschale Ein-Stunden-Puffer
war bei neun regionalen Etappen aus sieben Touren selbst unter optimistischen
freien Fahrzeiten zu kurz. Er wird je Richtung durch mindestens eine Stunde bzw.
das 1,35-Fache der freien Originalrouten-Fahrzeit plus 15 Minuten, auf volle Minuten
aufgerundet, ersetzt. Kundenhalte und ihre Quellzeiten bleiben unverändert. Ampelknoten-
Zusammenfassungen und teilweise nicht interpretierte Abbiegerestriktionen bleiben
prüfpflichtig; Zulässigkeit ist nur im importierten Modell geprüft. Fahrzeugdimensionen nicht individuell
zertifiziert. Quelltag September und Netzauszug Oktober sind keine genaue historische
Rekonstruktion.

**Quellen:** Vollständige öffentliche
[NRW-](https://download.geofabrik.de/europe/germany/nordrhein-westfalen.html) und
[Niedersachsen-OSM-Abzüge](https://download.geofabrik.de/europe/germany/niedersachsen.html),
OSM/Geofabrik, Stand 24.10.2026, ausschließlich lokal abgeglichen. Keine einzelnen
Kundenadressen oder Tourengebiete an einen öffentlichen Geocoder gesendet.
NRW-PBF SHA-256 `b10c630076a39c56aa6546843df760e6b88678ff8ea65621cf7b2afc46ab54db`,
NI-PBF SHA-256 `c90ba810aca20a44c4d2f27f93f070616ce4840ac5840b48fbef20a186791f55`.
Amtlicher NRW-Adressbestand wie im Befund vom 24.10.; Index-, Entscheidungs- und
Netzauswahlberichte enthalten Herkunft und Abgleichsregeln. Hauptverbindungen und
unclassified-Straßen innerhalb der dokumentierten Außen-Bbox sowie alle Straßen
im Umfeld der Quellorte und Modellregion; keine Vollständigkeitszertifizierung.

**Bedienung und Gates:** Browserprobe im isolierten Ein-Tour-Prüfszenario: lokale
Neusuche umgeht alte Cache-Antworten, bewusste Auswahl und Ortsbestätigung,
Kontextspeicherung und zusätzliche Nachfrageaktivierung erfolgreich; serverseitige
Auswahl erneut aus Datei geprüft. Screenshot `ui-activation.png`; der frühere
Browser-Prüfblocker ist erledigt. 372 Backendtests ohne Fehler, vier bestehende
optionale Skips; 87 Frontendtests, Produktionsbuild und sechs Pythonprüfungen grün.
Neue Regressionen schützen Fortsetzung nach leeren Antworten, Ergebnisbewahrung
bei Speicherung, explizite Cache-Aktualisierung sowie getrennte Hausnummern,
Mehrdeutigkeiten und Gemeindeabgleich ohne widersprechende Postleitzahlen.

**Konsequenz:** Die Technik und die geprüfte Teilmenge sind abgenommen. Für den
Gesamtbestand bleiben zwei Ortszuordnungen und ein Netz-/Zufahrtsfall zu klären;
keine dieser Touren wird stillschweigend ersetzt oder verworfen. Eine tatsächliche
Betriebsabnahme benötigt weiterhin beobachtete/überprüfte Betriebsangaben.

<!-- END_COMPANY_COMPLETION_2026_10_26 -->


## Regionale Nachfrage mit Unternehmenstouren (2026-10-26)

**Fragestellung und vorab fixierter Vergleich:** Unverändertes Bönen-Untersuchungsrechteck
wie bei der Tourenbestandsprüfung; Seed 42, volle Bevölkerungsstichprobe. Nachfrage
über die Produktionskette Zensus → KIT-Haushalte → actiTopp → logiTopp einmal erzeugen.
Alle Pkw-Fahrerfahrten mit Dienstagabfahrt identisch in A (ohne Unternehmenstouren)
und B (mit den zuvor geprüften 38 Touren) übernehmen. Kein Trip-Cap, keine weiteren
statistischen Schwerverkehrs- oder Paketfahrten. Andere Personenmodi bleiben in
der vollständigen vorgelagerten Nachfrage dokumentiert, sind kein Teil des nativen
Pkw/LKW-Vergleichs. Drei ausgeschlossene Quelltouren bleiben ausgeschlossen.
Keine Überschneidungskorrektur. Protokoll, Quellenkopien und personenbezogene
Artefakte ausschließlich lokal unter `.runtime/freight-regional-validation-2026-10-26/`.

**Nachfragegrundlage:** Amtliches Zensusziel 8.718 Personen; vollständige
KIT-Haushalte ergeben 8.719 Personen in 4.242 Haushalten (+1 durch Haushaltsrundung).
Alters-/Haushaltsstruktur hier aus dem KIT-Sample, keine Zensus-Strukturkalibrierung.
actiTopp tatsächlich ausgeführt, kein Template-Ersatz. 213.527 Personenwege in der
Woche; Dienstag 32.707 Wege: 6.961 Pkw-Fahrer, 3.586 Mitfahrer, 13.205 Fuß-,
2.026 Rad- und 6.929 ÖV-Wege. 2.090 zusätzlich im logiTopp-Ergebnis vorhandene
Paketetappen werden ausdrücklich nicht in den A/B-Vergleich übernommen.
1.205 der Dienstag-Pkw-Wege haben einen externen Endpunkt; 23 geplante Ankünfte
liegen nach Mitternacht. Die private Eingabe wird unverändert eingefroren und
über `sourceTripKey` verglichen; native `vN`-IDs verschieben sich beim Hinzufügen
von Unternehmenstouren und sind kein geeigneter Paarungsschlüssel.

**Zonen- und Matrixgrenzen:** 500-m- und 1.000-m-Raster scheitern konservativ an
geeigneten öffentlichen Straßen in dünnen nördlichen Randzellen, auch wenn die
Zielwahl dort eine Aktivität auswählt. Beide Fehlversuche bleiben erhalten.
Das 1.500-m-Raster vermeidet diesen schmalen Reststreifen: neun interne und acht
externe Zonen, unverändertes Gebiet und Einwohnerziel. Keine entfernten Personen,
keine erfundenen Straßenpunkte, keine automatische zonenübergreifende Verlagerung.
Alle 72 internen gerichteten Zonenpaare je Pkw/Rad/Fuß sind mit GraphHopper routbar;
sechs interne Distanz-/Zeitmatrixblöcke stimmen im Wiederholungstest exakt auf
Dateigenauigkeit überein. Je Modus sind neun intrazonale und 208 externe
Matrixeinträge bewusst Schätzwerte. ÖV-Zeit/Kosten sind abgeleitete Schätzungen,
kein GTFS-basierter ÖV-Nachweis. Die Wochen-Wege sind durchschnittlich 4,03 km
lang, Dienstag 4,97 km; die nationale MiD-Referenz ist kein regionales Kalibrierziel.
Die bestehende Distanz-/Moduswahl ist damit nicht regional empirisch validiert.

**Native Importkorrekturen:** Der Erstlauf scheitert vor dem Nachfrage-Routing an
zwei über mehrere kurze Split-Kanten übertragenen Ampelwirkungen. Gemessene Wege
zur echten nativen Ampel: 9,51 und 10,87 m. Der Guard verfolgt nun alle Verzweigungen
innerhalb derselben kumulierten 25-m-Grenze, ohne Steuerungen umzuschreiben.
Ein nicht mehr vorhandener Quellenknoten bleibt gesondert prüfpflichtig.
Der feste Mitternachtsabbruch wird um einen aus geplanten Ankünften/ausgewählten
Abfahrten bestimmten Nachlauf ergänzt. Die Auswahl bleibt auf Dienstagabfahrten
beschränkt; Nachlauf, unvollständige Fahrten und Teleports werden ausgewiesen.
Regressionen: komplette Backend-Suite 374 Tests ohne Fehler, vier optionale Skips;
zusätzlich drei native Signal-/Tagesgrenzentests ohne Fehler. Die beiden Abbrüche
bei feineren Rastern und der ursprüngliche Importfehler sind keine positiven
Ausführungsnachweise.

**Native A/B-Auswertung — ❌ GESAMTABNAHME NICHT BESTANDEN:** A meldet
6.960 geroutete, eingesetzte und abgeschlossene von 6.961 angeforderten Pkw-Fahrten,
null Teleports und null verbliebene Fahrzeuge. 13 tatsächliche Ankünfte liegen
nach Mitternacht, die letzte bei Sekunde 87.372 (00:16:12 am Folgetag); der
Simulationshorizont beträgt 92.280 s. Der Runner kehrt im bisherigen Privatnachfrage-
Teilbetrieb regulär zurück, aber `traffic-execution.json` ist ausdrücklich
`incomplete`. Ein regulärer Prozessabschluss ist hier kein Abnahmenachweis.

Die Netzvorprüfung findet 76 nicht zugeordnete Endpunkt-Vorkommen in 75 Pkw-Anfragen,
verteilt auf fünf Koordinaten: ein innerer Zonenmittelpunkt (21 Vorkommen) und vier
synthetische Außenpunkte (55 Vorkommen). Null Anfragen ohne gerichtete Verbindung
unter den zugeordneten Punkten. Bei 41 behaltenen nativen Routen protokolliert SUMO
Koordinatenwarnungen. Eine vollständig außerhalb liegende Anfrage mit gleichem
Start-/Zielpunkt erhält keine Route. Diese Fahrt wird weder entfernt noch durch eine
erfundene Unternehmensadresse ersetzt. Ankunftszahlen allein beweisen keine korrekte
Ortsabbildung. Die Auswahl enthält 5.756 Innen→Innen-, 490 Innen→Außen-,
482 Außen→Innen- und 233 Außen→Außen-Fahrten der erzeugten Einwohnernachfrage.

B übernimmt dieselben 6.961 privaten Eingaben unverändert und zusätzlich 38
Unternehmenstouren mit 210 ursprünglichen bzw. 85 regional projizierten Etappen.
Unternehmensrouten und Projektion sind vorbereitet; der strenge gemeinsame Lauf
bricht vor dem Hauptrouting und der Simulation an denselben 76 privaten
Endpunkt-Vorkommen ab. Alle 75 nicht vollständig zugeordneten Anfragen sind
`person-demand`, keine `company-tours`. Das ist **kein** gemeinsamer nativer
Ausführungsnachweis. Fahrzeit-, Stau- und Emissionsdifferenzen A/B sind deshalb
nicht ermittelt und dürfen nicht als Wirkung der LKW ausgegeben werden.
Die zuvor erfolgreiche Unternehmens-Teilabnahme bleibt als eigener Nachweis bestehen.

**Vergleichbarkeit:** Private Eingabe inklusive Reihenfolge, Fahrzeugkonfiguration,
Seed 42 und OSM-Quelle sind gleich. Auch der native Graph ist nach Entfernung
lediglich des generierten XML-Kommentarheaders bytegleich (SHA-256
`a133eb4fb78959e1a5a5dffedb660c1c2f41a35b17010c585ce83fcef7c36b40`).
Die OSM-Quelle hat SHA-256
`788d891dd8ab09f777f45a3ce84724e662ee95f8a853e015ebae05f7c5624a45`.
Maschinenlesbare Abnahme: `regional-validation.json`; Herkunfts-/Ortsbefunde:
`private-endpoint-findings.json`, `internal-centroid-findings.json`; negative
Erstversuche und native A/B-Artefakte im oben genannten lokalen Laufverzeichnis.
Keine einzelnen Kundenadressen an einen öffentlichen Dienst übermittelt.

**Nächste Umsetzung:** (1) Koordinatenübergabe typisierter Bildungsziele prüfen:
21 betroffene Innen-Endpunkte gehören zu `EDUCATION_TERTIARY`-Wegen, obwohl die
exportierten festen Ziele bereits Straßenpunkte enthalten. Die verwendete Legacy-
Personenlogik behandelt nur den generischen Typ `EDUCATION` als feste Aktivität;
ohne passende Opportunity fällt sie auf den Zonenmittelpunkt zurück. Korrektur
im eigenen Adapter nachweisen, nicht in `validation/`. (2) Synthetische
Außen-Opportunities an geeignete Straßen anbinden, mit expliziter Provenienz und
Grenzen; Außenbeziehungen erhalten. (3) Neue gemeinsame Nachfrage einmal einfrieren
und A/B vollständig wiederholen, einschließlich Ortsdeckung, Mengenerhalt,
Teleports und Tagesnachlauf. Erst danach empirische regionale Kalibrierung sowie
Zählstellen-/Durchgangsverkehrsabgleich. Drei gesperrte Quelltouren, Flotten-/Depotzeit-
Annahmen, fehlende externe Kundenbedienung und Signal-/Abbiegerestriktionsprüfpunkte
bleiben gesonderte Grenzen. Dies ist ein abgeschlossener technischer Prüfversuch
mit negativem Ergebnis, keine empirische regionale Gesamtvalidierung.


## Korrigierte Ortsübergabe: regionaler Wiederholungslauf (2026-10-26)

**Hypothese:** Die fünf im ersten Vergleich nicht zugeordneten Koordinaten werden
an ihrer Quelle korrigiert; die gemeinsame Nachfrage muss danach ohne Ortslücken,
Verluste oder Teleports mit und ohne Unternehmenstouren nativ ausgeführt werden.
Vorheriger Fehlversuch bleibt als Negativbefund erhalten. Neues lokales Verzeichnis:
`.runtime/freight-regional-validation-2026-10-26-v2/`.

**Gegenproben vor Großlauf:** Die tatsächliche Legacy-Klasse fällt bei allen drei
typisierten Bildungszwecken auf den Zonenmittelpunkt zurück. Eigene Unterklasse
liefert das exportierte feste Ziel nur bei passender fester Zone; gleicher
Zufallsverbrauch gegen die Originalklasse nachgewiesen. Anders gewählte Zonen und
nicht feste Ziele bleiben im bestehenden Pfad. Außen-Opportunities verwenden
bestehende konservative Straßenkandidaten und bleiben innerhalb derselben Zone
sowie 1.500 m; fehlende/zu entfernte Kandidaten scheitern sichtbar. Feste Außenanker
und getrennte Cache-Einträge bleiben erhalten. Backend 377 Tests ohne Fehler,
vier optionale Skips; sieben Adaptertests grün. Keine KIT-Quellen geändert.

**Echte Produktions-Neuerzeugung:** Zensusziel, Haushalte, Gebiet, Raster, Seed,
POIs und Straßenquelle unverändert. Alle 213.527 Personenwege stimmen bei
Identität, Modus, geplanten Abfahrten/Ankünften, Zonen, Distanzen und Legs exakt
mit dem ersten Lauf überein. Es ändern sich ausschließlich Koordinaten. 88 externe
Opportunity-Zuordnungen, maximale Verschiebung 688,66 m. Alle fünf zuvor
problematischen Koordinaten sind aus den 6.961 Dienstag-Pkw-Anfragen verschwunden.
`location-correction-proof.json` enthält den maschinenlesbaren Nachweis;
`population/road-locations.json` enthält Einzelzuordnungen und Grenzen.

**Empirische Kalibrierbereitschaft:** Vorhandene Straßen.NRW-Beobachtungen sind
Juli-2026-Mittel über Montag bis Sonntag. Die Unternehmenspläne beziehen sich
auf einen Dienstag im September; ältere Zählstellen-Kantenzuordnungen gehören
zu einem anderen Netz. Ohne passende Zeitbasis und geometrisch geprüfte
Zuordnung an dieses Netz wird kein Kalibrierwert abgeleitet. Der isolierte
A/B-Vergleich enthält außerdem keinen vollständigen regionalen Hintergrund-/
Durchgangsgüterverkehr und keine vollständige Nachfrage Nichtansässiger.
Die zusätzliche Unternehmensnachfrage darf nicht auf den gesamten SGV hochskaliert
werden. `calibration-readiness.json` hält die nötigen Daten und Abnahmekriterien fest.

**Prüfung der tatsächlichen Seeds (2026-10-27):** Der native Konfigurationsexport
belegt den SUMO-Standardseed 23.423. Der erste vollständig ausgeführte korrigierte
Pkw-Kontrolllauf hat diesen Standard verwendet, während physische Lkw-Ketten den
Szenarioseed 42 explizit übergeben. Dessen Ankunftszahlen belegen die Ortskorrektur,
aber seine Kennzahlen sind keine geeignete A/B-Vergleichsbasis. Der Runner
übernimmt den Konstruktorseed und übergibt ihn nun immer an SUMO, unabhängig von
Fahrzeugketten. Nativer Test ohne Ketten: angeforderter Seed 777 erscheint im
von SUMO selbst geschriebenen Konfigurationsheader. Backend-Suite weiterhin
377 Tests ohne Fehler, vier optionale Skips.

Die getrennten nativen Importe sind zudem nicht bytegleich; der erste beobachtete
Unterschied betrifft eine interne Fahrstreifenlänge (3,64 gegenüber 3,56 m bei
gleicher Shape). Daraus wird keine allgemeine Ursachenbehauptung abgeleitet.
Für die Abnahme wird das vollständig ausgeführte B-Netz als ein einziges kanonisches
Netz für beide Simulationen verwendet. Der erste A-Import und dessen Resultate
bleiben separat aufbewahrt und werden aus Vergleichskennzahlen ausgeschlossen.

A übernimmt die tatsächlich geprüften privaten B-Endpunktzuordnungen für identische
Quellschlüssel, Koordinaten, Typen und Abfahrten; native IDs werden eindeutig auf A
übersetzt. Der Herkunfts-Hash der B-Vorprüfung wird im abgeleiteten A-Ortsbericht
festgehalten; das ist keine behauptete unabhängige zweite Ortszuordnung. A wird
anschließend auf dem gemeinsamen Netz **nativ neu geroutet und simuliert**.
Eingabe-Hashes werden davor/danach geprüft. Ausführungsprüfung, Energie-Ledger,
Emissionen, Kennzahlen und Routengeometrien werden mit den Produktionsprozessoren
neu aus den nativen Ausgaben aufgebaut; die Staukarte verwendet ebenfalls die
kanonischen Netzgeometrien. Die tatsächlichen nativen Header belegen in beiden
Simulationen Seed 42. Das Hauptrouting verwendet in A explizit und in B implizit
denselben duarouter-Seed 23.423 (installierter Standardwert geprüft); die
Unternehmensprojektion benutzt explizit 42. Keine Änderung der Router-Seed-Policy
in dieser Korrektur.

**Native Auswertung — ✅ TECHNISCHE ABNAHME BESTANDEN (2026-10-27):**
Alle 14 Kriterien in `regional-validation.json` bestehen. 6.961 Pkw-Anfragen
in A und in B jeweils geroutet, eingesetzt und abgeschlossen. In beiden Fällen
null Ortslücken, nicht gerichtete Anfragen, Koordinatenwarnungen, Teleports und
unvollendete Fahrzeuge. A/B haben dieselbe private Eingabe und Fahrzeugkonfiguration,
dieselben Quellschlüssel, effektiven Router-/Simulationsseeds und dasselbe native Netz.
Alle 6.961 privaten Routenkantenfolgen stimmen überein. Aktuell verwendetes kanonisches
Netz: `network-comparison.json`; der verworfene separate Importvergleich bleibt
als `network-comparison-before-canonical.json` erhalten.

B führt zusätzlich 85 regionale Unternehmensetappen und 38 regionale Tourabschlüsse
aus. 84 native Lkw-Gebietsbesuche mit 38 physischen Lkw; zusammen mit den Pkw
7.045 vollständig abgeschlossene native Fahrzeuge/Gebietsbesuche. Alle 7.046
logischen Etappen bleiben erhalten; eine Fahrzeugkette fasst Etappen zusammen.
210 Originaletappen im gemeinsamen Bedarf, `outsideExecutionSimulated=false`,
null vollständige Unternehmens-Tourabschlüsse. Die drei zuvor ausgeschlossenen
Quelltouren bleiben ausgeschlossen. 13 Pkw-Ankünfte nach Mitternacht in A, elf
in B, beide vollständig; effektiver Simulationshorizont 92.280 s.

**Deskriptive Pkw-Kennzahlen:** Fahrtdauer A 410.04 → B 410.69 s
(Δ +0.65 s); Zeitverlust 66.77 → 67.39 s
(Δ +0.62 s); CO₂ je Pkw-Fahrt 970.74 → 972.28 g
(Δ +1.54 g). Jeweils 6.961 vollständig gepaarte Anfragen über
`sourceTripKey`. Ein einzelnes Seed-Paar und das Einfügen zusätzlicher Fahrzeuge
ändern den globalen Zufallsverbrauch; das ist kein allgemeiner kausaler Wirkungs-
oder beobachteter Kalibrierungsnachweis. Prozesslaufzeiten werden wegen
wiederverwendeter Vorbereitung nicht verglichen.

**Abschlussgrenze:** Ortskorrekturen und technische A/B-Abnahme sind umgesetzt und
belegt. Empirische regionale Kalibrierung ist mit den vorhandenen Teilnachfragen,
Zeitbezügen und Netzzuordnungen nicht belegt. Die Datenvoraussetzungen wurden geprüft
und explizit in `calibration-readiness.json` festgehalten; keine Hochskalierung der
38 Unternehmenspläne auf den gesamten SGV. Fahrzeug-/Depotzeitannahmen, externe
Bedienung sowie Signal-/Abbiegerestriktionsprüfpunkte bleiben wie dokumentiert.
Lesbarer lokaler Abschlussbericht: `abnahme.md` im v2-Laufverzeichnis.

## Datenquellenimport und Quellenplan (2026-10-28) — technischer Schnitt bestanden

**Befund:** Der aus dem Tourenimport abgeleitete gemeinsame Reader-/Mappingkern
versorgt weitere Fachverträge. Quellenprüfung vor dem Queueing und im Studio
verwenden dieselbe Backendregel. Fehlende Ersatzmengen blockieren einen aktiven
Schwerlastannahmenpfad; eine veröffentlichte Datenrevision ist zunächst inaktiv.

**Evidenz:**

| Prüfung | Ergebnis |
|---|---|
| Gesamtes Backend | 389 Tests, 0 Fehler, 4 Skips (externe MobileCity-Quellen, actiTopp und ÖV); Offline-Maven mit deaktiviertem Named-Lock und erlaubten lokalen Testservern |
| Abschließende `DataSourcesTest` | 10 Tests ohne Fehler oder Skips; ein Bytevergleich wurde nach der Gesamtsuite zusätzlich aufgenommen |
| Kompatibilität | Im eingefrorenen Offlinefall sind `population/`, `matrix/`, `zone-repository/`, `legacy/` und `logistics/` bei ausgeschaltetem Plan und bei reiner Legacy-Auswahl bytegleich |
| Importverträge | CSV/JSON/XML/XLSX liefern gleiche Fachwerte; Einheit, Dezimalzeichen, Nullwerte, fehlerhafte Zeilen, Profilstruktur und manipulierte Revisionen geprüft |
| Frontend | 92 Tests in 11 Dateien grün; Produktions-AOT-Build erfolgreich |
| Echter Browser | JSON-Import → vollständige Prüfung → Revision ohne Aktivierung → Auswahl → Mengenblocker → gespeicherter Plan → Offlinegenerierung mit drei schweren Fahrten; keine JS-Laufzeitfehler |

Der Browserfall lief mit eigenem temporärem Datenverzeichnis auf Port 8091,
ohne Livequellen, actiTopp, logiTopp, SUMO oder Realrouting. Der neue Dialog wurde
bei 1.440 und 390 Pixeln geprüft; dies ist keine Aussage über die gesamte
Mobilansicht des bisherigen Studios. Der Bytevergleich gilt für den geprüften
Offlinefall und ersetzt keine Mehrregionenabnahme der noch nicht migrierten Quellen.

**Konsequenz:** Der Import-/Auswahlpfad ist als erster, standardmäßig ausgeschalteter
Schnitt nutzbar. Sein explizites Ersatzmengenprofil ist eine Modellannahme und
belegt keine regionale Güterverkehrsmenge. Vollständige Providerketten,
Dienstconnectoren und Datenpakete bleiben weitere Ausbauschritte.
[Maschinenlesbarer Browsernachweis](verification/2026-10-28-data-source-ui.json),
[Desktopbild](figures/data-source-ui/desktop.png),
[Dialog bei 390 Pixeln](figures/data-source-ui/mobile.png) und
[Vertrag/Umfang](22-datenquellen-adapter-und-fallbackkonzept.md#19-erste-experimentelle-umsetzung-und-verifikation).

### Nachgeschärfte Beweisgrenze nach Konzeptreview (2026-10-28)

Der Kompatibilitätstest schaltet den Quellenplan im **selben neuen Code** aus
bzw. auf reine Legacy-Auswahl. Er sichert diesen Vergleich, aber keinen vollständigen
Vorher-/Nachherbeweis gegen die Version vor der Readerextraktion. Die Mehrregionen-
und Zweigmatrix wurde nicht ausgeführt; vorhandene frühere regionale Gütertests
belegen nicht automatisch die neue Quellenmigration.

Der Punktimport setzt bislang in vollständig abgedeckten Boxzonen ohne Punkt den
Arbeitswert null. Ohne expliziten Erhebungs-/Vollständigkeitsvertrag ist das für
lückenhafte Register nicht abgesichert. Ein AGS-/Gebietsstandsjoin für kommunale
Aggregate und ein vollständiger Provenienzvertrag für kombinierte Teile fehlen.
Die Lkw-Demonstration bestätigt technische Mengenerzeugung, nicht die Erfüllung
der vier fachlichen Ersatzprobleme. Hierfür gibt es noch keinen eigenständigen
Qualitätspolicy-Blocker im Code.

**Konsequenz:** Die Bezeichnung „technischer Schnitt bestanden“ gilt ausschließlich
für die genannten Prüfungen. Sie ist keine allgemeine Fachfreigabe des Quellenplans
oder des Schwerlastfallbacks. Die [Fallmatrix G1–G12](22-datenquellen-adapter-und-fallbackkonzept.md#131-verbindliche-fallmenge)
benennt bestandene Teilprüfungen und offene Nachweise getrennt. In dieser
Dokumentrevision wurden diese Läufe nicht nachgeholt und keine neuen Codeprüfungen
behauptet.

## Datenquellen M0: Vollständigkeit und Teileherkunft (2026-10-28)

**Befund:** Der Arbeitsplatzpunktimport verlangt nun eine mit der Datenrevision
unveränderlich gespeicherte Vollständigkeitsangabe und einen beschriebenen
Erhebungsumfang. Unbekannte oder lückenhafte Bestände und Gemeindeaggregate werden
vor Veröffentlichung bzw. Auswahl abgelehnt. Fehlende Metadaten alter Revisionen
werden nicht ergänzt; deren Inhaltshashes bleiben lesbar, die Auswahl blockiert
vor dem Queueing. Ein bestätigter leerer Teil eines vollständigen Registers ist
hingegen ein erlaubter Nullbereich.

Der neue Herkunftsvertrag `synthesis-source-provenance-v1` bewahrt Quellrevision,
Rohhash, Quellzeilen/-IDs, Punktzuordnung und räumliche Ergänzung je Zone.
Der alte Teil bleibt ausdrücklich `unknown`; keine erfundene Auflösung in OSM
oder synthetische Provider. Ein Fehler der Lückenregel verändert keine Zonen.

| Prüfung | Ergebnis und Reichweite |
|---|---|
| Backend komplett | `mvn -o -Daether.syncContext.named.factory=noop test`: 394 Tests, 0 Fehler, 4 optionale Skips; 27:40 Minuten inklusive externer Bestandsprüfungen |
| `DataSourcesTest` | 14 Tests ohne Fehler/Skips; darunter vier neue Fälle für Vollständigkeit, unveränderte alte Revisionshashes, fehlende Queue bei Blocker, atomare Teilabdeckung, Quellzeilenbilanz und deklarierte Nullbereiche |
| Persistierter Bericht | Offline-Pipeline schreibt Vollständigkeitsangabe, `rowAssignments` und typisierten Herkunftsgraph nach `sources/resolution.json`; wiederholte Ausführung und Legacy-Vergleich bleiben geprüft |
| Frontend | 95 Tests in 11 Dateien grün; drei neue Fälle prüfen ungeklärte/partielle/kommunale Eingaben, Metadatenweitergabe, Invalidierung nach Änderungen und Anzeige der noch ausstehenden Zuordnung |
| Produktions-AOT | Erfolgreich |
| Echte Browserkette dieses Schritts | Nicht ausgeführt: automatische Freigabe des isolierten Testservers wegen Kontingentlimit nicht verfügbar; der Server wurde nicht gestartet |

**Konsequenz:** Die gefährliche Annahme „Box bedeutet automatisch Vollständigkeit“
ist für neue Arbeitsplatzimporte durch einen expliziten Vertrag ersetzt. Die
Erklärung bleibt eine Quellenangabe, keine unabhängig gemessene Vollständigkeit.
Die Abweisung kommunaler Aggregate implementiert noch keinen AGS-Adapter.
Die gesamte regionale Fallmatrix, Vor-Umbau-Baseline und fachliche Lkw-Abnahme
bleiben offen. Der frühere Browsernachweis bleibt historisch und wird nicht als
Nachweis der neuen Eingaben ausgegeben.

[Maschinenlesbarer Prüfstand](verification/2026-10-28-data-source-m0.json).

## Datenquellen M1: Pakete, Profile und Gebietswechsel (2026-10-28)

**Befund:** Szenariolokale ZIP-Pakete lassen sich ohne Kerncodeänderung installieren
und über versionierte Profile priorisieren. Installation und Profilspeicherung
aktivieren keine Quelle und verändern bestehende Profilbindungen nicht. Identische
Paketbytes sind idempotent; andere Inhalte bei gleicher Edition erhalten andere
IDs. Fehlende Dateien fallen nur nach expliziter Regel durch, beschädigte Daten
bleiben Fehler. Die Entscheidung einschließlich übersprungener und nicht geprüfter
Kandidaten stimmt zwischen Vorschau, Eingabelock und Generierungsbericht überein.

| Prüfung | Ergebnis und Reichweite |
|---|---|
| Backend-Gesamtsuite | 401 Tests, 0 Fehler, 4 optionale Skips; Offline-Maven mit Named-Lock-Umgehung, 3:05 Minuten |
| Abschließende Quellenfälle | 22 Tests, keine Fehler/Skips: 14 bestehende Import-/Pipelinefälle plus acht Paket-/Profilfälle; der achte Profilfall wurde nach der Gesamtsuite ergänzt |
| Fremdgebiet | Künstliche französische Box: deutsches CSV-Paket ungeeignet, französisches JSON-Paket ausgewählt; Arbeitswerte und Teileherkunft in Offline-Pipeline geprüft |
| Weitere Verbraucher | Dasselbe Profil versorgt Bevölkerungsverteilung und schwere Fahrzeug-OD; 43 Personen und drei schwere Fahrten im Offlinefall, kein Simulation-/Kalibrierungsnachweis |
| Fehler-/Integritätsfälle | Profiländerung, Paketmanipulation, falscher Hash, Pfad im ZIP, übergroßer entpackter Inhalt, nicht deklarierter Download, ungültige Einheit und fehlende Revisionen; bei fachlich fehlerhaftem Mehrquellenpaket keine Revision veröffentlicht |
| Frontend | 99 Tests in 11 Dateien und Produktions-AOT grün; Reihenfolge, Abwahl, getrennte Profilentwürfe, fehlende Aktivierung und Fehlerdiagnosen geprüft |
| Echter Browser | Zwei dokumentierte Pakete per UI installiert, Profil angelegt und ausdrücklich ausgewählt; geografische Auswahl erklärt, Plan gespeichert, Offlinegenerierung mit zwölf angewendeten Arbeitsgewichten und Teileherkunft; keine JS-Laufzeitfehler |

Der Browserlauf verwendete einen isolierten Server auf Port 8093 mit eigenem
Datenverzeichnis. Livequellen, actiTopp, logiTopp und Realrouting waren ausgeschaltet.
Desktop und Dialog bei 390 Pixeln geprüft; keine Aussage zur gesamten bestehenden
mobilen Studioansicht. Die Beispielpakete enthalten künstliche Werte, keine
beobachteten Arbeitsplatzregister. Auswahl einer Ersatzquelle wird getrennt von
einer Modellannahme bezeichnet.

**Konsequenz:** Lokaler M1-Schnitt für die drei vorhandenen Verträge technisch
nachgewiesen. Kein allgemeiner Beweis für nationale Großbestände, alle Provider,
alle Gebiets-/Zeitkonstellationen oder empirische Gütermengen. Die vollständige
Freigabematrix bleibt teilweise offen; allgemeine Providerabhängigkeiten wurden
nicht als vorhanden ausgegeben.

[Browsernachweis](verification/2026-10-28-data-source-m1-ui.json),
[Desktopbild](figures/data-source-m1/desktop.png),
[Dialog bei 390 Pixeln](figures/data-source-m1/mobile.png),
[Beispielpakete](examples/data-packs/README.md).

## Gemeindeaggregate und Qualitätsmodus (2026-10-29)

**Befund:** Gemeindeaggregate bleiben als Schlüssel-/Gebietsstandssummen erhalten.
Ihre räumliche Verteilung verwendet eine eigene unveränderliche Gewichtsrevision,
die mit den übrigen Eingaben eingefroren wird. Der Nenner enthält auch außerhalb
liegende Gewichtspunkte. Der Bericht dokumentiert deshalb einen regionalen Teil,
keine still auf die Region hochskalierte Gemeindesumme.

| Prüfung | Ergebnis und Reichweite |
|---|---|
| Backend-Gesamtsuite | 411 Tests, 0 Fehler, 4 optionale Skips; Offline-Maven mit Named-Lock-Umgehung, 2:55 Minuten |
| Fokussierte Quellenfälle | 31 Fälle ohne Fehler/Skips: neun neue Gemeindevertrags-/Qualitätsfälle sowie 22 vorhandene Import-, Paket- und Profilfälle |
| Mengen-/Raumprobe | Künstliche Gemeindesumme 100, Gewichte 3:1, ein Punkt außerhalb: 75 angewendet, 25 außerhalb. Explizite Null erhalten, nicht zugeordnete Zonen gemäß Lückenregel; keine Mengenhochrechnung |
| Ganz außerhalb | 0 angewendet, 100 außerhalb; bisherige Gewichte nur bei erlaubtem Legacy-Fallback erhalten, sonst atomarer Abbruch |
| Schlüssel/Referenzen | Führende Nullen, doppelte/falsch formatierte AGS, falscher Gebietsstand/Fachvertrag, fehlende Zuordnung, manipulierte Revision und zyklische Abhängigkeit geprüft |
| Persistierte Pipeline | Anwendungsbericht mit Gemeindemengen und Teileherkunft, Eingabelock mit der referenzierten Gewichtsrevision geprüft |
| Qualitätsmodus | Fehlende Explorationsfreigabe/-beschreibung und fachlicher Auswertungsmodus blockieren die technische Lkw-Demonstration; expliziter Ausschluss bleibt möglich |
| Frontend | 102 Tests in 11 Dateien und Produktions-AOT grün; unterstützende Referenzen bleiben aus der Aspektauswahl, Importinitialisierung und Qualitätsanzeigen geprüft |

**Prüfgrenze:** Die künstlichen Gemeindeschlüssel und Ortskoordinaten sind keine
realen administrativen Referenzen. Die Verteilung ist ein explizites Modell und
keine beobachtete Betriebsgeografie. Ein Hash belegt Unverändertheit, nicht die
amtliche Gültigkeit eines Schlüssels oder die räumliche Vollständigkeit der Quelle.
Der bisherige verhaltensgleiche Legacy-Vergleich bleibt ein Vergleich im aktuellen
Code; eine allgemeine Vor-Umbau-Baseline ist weiterhin offen.

**Konsequenz:** Nutzbare Gemeindeimporte benötigen passende, ausdrücklich bereitgestellte
Gewichte. Amtliche Geometrieprüfung, Polygonallokation, Schlüsselüberleitung und
fachlich geprüfter Güterfallback werden nicht als fertig ausgegeben. Die Auswahl
„Fachliche Auswertung“ allein ist keine wissenschaftliche Freigabe.

Die neue Browserkette ist gegen Produktionsfrontend und isoliertes Backend auf
Port 8094 bestanden: Gewichtsrevision veröffentlichen, AGS-/Gebietsstand binden,
Aggregate auswählen, fehlende Demonstrationsfreigabe und Auswertungsmodus blockieren,
ausdrücklich erlaubten Explorationslauf speichern und offline generieren.
Bestätigt sind Originalsumme 100, angewendet 75, außerhalb 25, drei schwere
Demonstrationsfahrten und die eingefrorene Referenzrevision. Keine JS-Laufzeitfehler.
Livequellen, actiTopp, logiTopp und Realrouting waren deaktiviert. Ansichten bei
1.440 und 390 Pixeln geprüft; die gesamte bestehende mobile Studioansicht wird
hierdurch nicht abgenommen.

[Browsernachweis](verification/2026-10-29-municipal-sources-ui.json),
[Desktopbild](figures/municipal-sources/desktop.png),
[Dialog bei 390 Pixeln](figures/municipal-sources/mobile.png),
[Importdateien und Modellgrenzen](examples/municipal-workplaces/README.md).

## Gemeindegeometrien und Flächenverteilung (2026-10-29)

**Befund:** Ein Geometriereader erhält Polygon-/MultiPolygon-Objekte und ihre
Featurebelege. Der normalisierte Geometriewert bleibt strukturiert; Revisionshashes
sind nach Speicherung und Wiederlesen stabil. Der Aggregateimport bindet eine
bestimmte Geometrierevision desselben Schlüssel-/Gebietsstands und verwendet ein
explizites Dichtemodell über die vollständige Gemeindefläche.

Die finale Backend-Gesamtsuite bestand mit **418 Tests ohne Fehler und vier
optionalen Skips** (Offline-Maven mit Named-Lock-Umgehung, 3:23 Minuten). Die
fokussierten Quellenfälle umfassen 38 Tests ohne Fehler/Skips; Frontend 103 Tests
in elf Dateien und Produktions-AOT grün.

Sieben gezielte Flächenfälle prüfen Geometrie-/Hash-Roundtrip, lokale GeoJSON-Pakete,
wahre EPSG:3035-Zellen, deckungsgleiche Ränder, Löcher, disjunkte MultiPolygone,
Überlappungen, ungültige Ringe und CRS-Konflikte. Mengen werden nicht auf die innerhalb
liegenden Zonen renormalisiert. Die Abdeckung toleriert nur einen dokumentierten
1-cm-Randpuffer; echte größere Löcher bleiben offen. Mengen sind ungepuffert.

Die echte Browserkette importierte das künstliche Lochpolygon, band es an zwei
Gemeindeaggregate, prüfte die Analysesperre und generierte offline. Aus der Summe
100 wurden rund 29,373 angewendet, 6,011 wegen Teilzonen nicht angewendet und 64,615
außerhalb dargestellt. Die ungerundete Bilanz im JSON ergibt 100. Keine JS-Fehler;
Ansichten bei 1.440 und 390 Pixeln geprüft. Livequellen, actiTopp, logiTopp und
Realrouting waren deaktiviert, Serverport 8095 mit eigenem Datenverzeichnis.

**Konsequenz:** Flächenzuordnung ist ein technisch nachgewiesenes Modell mit
konstanter innergemeindlicher Dichte, keine beobachtete Betriebsverteilung. Die
synthetischen Schlüssel/Geometrien sind keine realen Verwaltungsreferenzen.
Amtliche Datenqualität, Schlüsselüberleitungen und der fachliche Güterfallback
sind weiterhin nicht belegt.

[Browsernachweis](verification/2026-10-29-municipal-areas-ui.json),
[Desktopbild](figures/municipal-areas/desktop.png),
[Dialog bei 390 Pixeln](figures/municipal-areas/mobile.png) und
[GeoJSON-Beispiel](examples/municipal-workplaces/areas.geojson).

## Konfigurierbare Bevölkerungsprovider (2026-10-29)

**Befund:** Die bisherige Bevölkerungskette kann als explizite Reihenfolge ihrer
fünf nativen Provider ausgeführt werden. `PopulationSourcesTest` prüft verzögertes
Einlesen, Abwahl, Vorrang, Ausfallregeln, erschöpfte Ketten, Integritätsfehler,
Unterbrechung, Planvorprüfung, Uploadbindung, getrennte Herkunft für manuelle
Gesamtzahl und Verteilung sowie Entfernung alter Berichte.

Fünf getrennte synthetische Offlinefälle decken Uploadraster, gecachtes Zensusarchiv,
OSM-Siedlungen, POIs ohne Siedlungen und Ersatz ohne Ortsdaten ab. Jeder Fall vergleicht
Plan aus → Plan an mit Legacy-Auswahl → explizite native Standardreihenfolge.
Alle Dateien unter `population/`, `matrix/`, `zone-repository/`, `legacy/`, `logistics/`
und die gemeinsame `demand/traffic-demand.json` müssen bytegleich bleiben. Seed 42,
WGS84-Testbox 8,20/48,85–8,24/48,89, 1-km-Zonierung, externe Ziele und externe
Simulation aus. Die Zensus-ZIP ist eine künstliche lokale Testquelle, keine amtliche
Datei. Das sind Vergleiche der Pfade im aktuellen Code; kein unabhängiger Vergleich
mit einem isoliert ausgeführten alten Commit und keine realregionale Abnahme.

**Konsequenz:** Die erste native Providerfamilie ist technisch auswählbar und ihre
Standardreihenfolge abgesichert. Weitere Familien, gemeinsame native/importierte
Profilketten und empirische Freigaben sind offen. Raster mit unterdrückten Werten
werden im expliziten Pfad abgelehnt; eine NoData-Maske wird nicht vorgetäuscht.
[Bedienung und Beispielplan](examples/population-sources/README.md).

**Ausgeführt:** `mvn -o -Daether.syncContext.named.factory=noop test` mit lokalen
Testports: 429 Tests, 0 Fehler, 4 optionale Skips; elf neue Providerfälle.
`npm test`: 105 Tests grün; `npm run build`: Produktions-AOT grün.
Die vollständige Suite lief abschließend ohne parallele Backendkompilierung.
Ein vorheriger Lauf war durch gleichzeitiges Kompilieren gestört; ein weiterer
Sandboxlauf konnte bestehende lokale HTTP-Testports nicht öffnen. Diese Läufe
sind keine grünen Nachweise.

Die echte Browserkette speicherte Reihenfolge und Uploadabwahl, erzeugte 50 manuell
vorgegebene Personen mit Ersatzverteilung, wählte danach den Upload an erster Stelle
und erzeugte 80 Personen aus dem Raster. Der Eingabelock enthält dessen Hash;
keine JavaScriptfehler, Dialogbreite bei 1.440/390 Pixeln geprüft. Isolierter
Backendport 8096, Simulation/Live-OSM/Zensusabruf/Realrouting aus.
[Browsernachweis](verification/2026-10-29-population-sources-ui.json),
[Desktopbild](figures/population-sources/desktop.png),
[Dialog bei 390 Pixeln](figures/population-sources/mobile.png).

## Gemeinsame Providerauflösung und Arbeitsplatzquellen (2026-10-29)

**Befund:** `WorkplaceSourcesTest` prüft fünf Fälle: Der gemeinsame generische Kern
löst einen unabhängigen Integer-Vertrag auf und verwirft ungültige Werte; OSM-Work
bleibt zur bisherigen Tag-Ableitung identisch; explizite synthetische Auswahl ist
seedabhängig und wiederholbar, ohne andere Kategorien zu ändern; unpassende/defekte
Quellen überschreiben keine Teilmenge; die Offlinepipeline persistiert Teileherkunft,
Fehlerbericht und entfernt diesen beim Abschalten. Die bisherigen elf
Bevölkerungsfälle laufen nun über denselben Kern, einschließlich der fünf
Bytevergleiche gegen die Bestandspfade.

Eine synthetische OSM-Fixture mit einem Shop-/Office-POI liefert Gewicht 48,
keine gemessenen 48 Arbeitsplätze. Die neue Browserkette wählt zuerst bei weiterhin
aktivem OSM die synthetische Work-Quelle und schließt OSM-Work aus. Danach werden
OSM-Work und Uploadbevölkerung gewählt. Der Bericht muss die jeweiligen Beiträge,
die Rastermenge 80 und Work-Gewichtssumme 48 erhalten; andere Zwecke bleiben OSM-basiert.

**Konsequenz:** Gemeinsame Auswahlregeln sind technisch wiederverwendet, und die
zweite native Providerfamilie ist im Studio auswählbar. Ein globaler Plugin-Lader,
gemeinsame native/Revision-/Paketketten, frei konfigurierbare Registerlücken und
empirische Arbeitsplatzmodellfreigabe sind damit nicht umgesetzt.
[Bedienung und Beispielplan](examples/workplace-sources/README.md).

**Ausgeführt:** Finale vollständige Backend-Suite
`mvn -o -Daether.syncContext.named.factory=noop test`: 434 Tests, keine Fehler,
vier optionale Skips (ActitoppIntegrationTest, PtIntegrationTest,
MobileCityRastattSourcesTest, MobileCityCrossMunicipalitySourcesTest).
54 gezielte Quellenfälle ohne Fehler/Skips. `npm test`: 106 Tests grün;
`npm run build`: Produktions-AOT grün. Der finale Backendlauf wurde nach Abschluss
aller Kompilierungen gestartet.

Die echte Browserkette bestand mit der finalen Produktionsoberfläche: 50 manuell
vorgegebene Personen mit unabhängiger Verteilung, anschließend 80 aus dem
hashgebundenen Upload; synthetische Work-Auswahl mit abgewähltem OSM-Work,
anschließend OSM-Work mit Gewichtssumme 48. Keine JavaScriptfehler; der Dialog
wurde bei 1.440 und 390 Pixeln geprüft. Serverport 8097 und eigenes Testverzeichnis;
externe Simulation, Realrouting, Live-OSM und Live-Zensus waren deaktiviert.
Die mobile Aufnahme zeigt den Dialog; eine vollständige mobile Studioabnahme
ist damit nicht behauptet.
[Browsernachweis](verification/2026-10-29-provider-chains-ui.json),
[Desktopbild](figures/provider-chains/desktop.png),
[Dialog bei 390 Pixeln](figures/provider-chains/mobile.png).

## Gemischte Profile und explizite Registerlücken (2026-10-29)

**Befund:** Zehn neue Fälle in `MixedSourcesTest` prüfen native Fachverträge,
Ausschluss impliziter Legacy-Rückwege, gebundene Revisionsdaten nach Entfernen ihrer
Datei, potenzielle/abgewählte/nachrangige Kandidaten, echte Nullwerte, disjunkte
Register-/Modellscopes, atomaren Abbruch bei fehlendem Ersatz, Nichtausführung unnötiger
Lückenfüller, ausschließlich partielle Punktabdeckung, Gemeindeaußenmengen und
Polygon-Teilzonen. Ein Paket-/Native-Fall und die vollständige Offlinepipeline mit
Population/Work-Profilen ergänzen die Vertragsfälle. Gleicher Input/Seed muss
bytegleiche gemeinsame Verkehrsnachfrage liefern; Quellenfehler bleiben sichtbar.

**Konsequenz:** Die gemeinsame Kette und explizite native Registerlücken sind für
Bevölkerung/Work technisch implementiert. Vergleichbare Registermengen bleiben
getrennt von heuristischen Modellgewichten. Die Vorprüfung darf defekte potenzielle
Importdateien vor dem Lauf ablehnen; sie behauptet für native Kandidaten noch keine
räumliche Verfügbarkeit. Dieser Nachweis ist keine empirische Modellkalibrierung
oder vollständige Abnahme aller Regionen/Zielmodelle.
[Beispielpaket und genaue Regeln](examples/mixed-sources/README.md).

**Ausgeführt:** Finale vollständige Backend-Suite
`mvn -o -Daether.syncContext.named.factory=noop test`: 444 Tests, keine Fehler,
vier optionale Skips (ActitoppIntegrationTest, PtIntegrationTest,
MobileCityRastattSourcesTest, MobileCityCrossMunicipalitySourcesTest).
64 gezielte Quellenfälle ohne Fehler/Skips; `npm test`: 108 Tests grün;
`npm run build`: Produktions-AOT grün. Bestehende Bevölkerungspfade bleiben in den
fünf enthaltenen Bytevergleichsfällen gleich.

Die echte Browserkette importierte Bevölkerungsdaten und ein vollständiges künstliches
Teilregister mit 12/0-Werten, erstellte beide gemischten Profile über die UI und
konfigurierte synthetische Registerlücken bei abgewähltem OSM-Lückenfüller. Nach
Speichern und Generierung enthielt der Lauf 80 Personen, 12 angewendete Register-
arbeitsplätze und getrennte Modellgewichte für die anderen Zonen. Der echte Nullwert
wurde zusätzlich in `zone-repository/attractivities.csv` über die externe Zonen-ID
geprüft. Beide Profilbindungen und Revisionsdaten sind im Lock erhalten. Keine
JavaScriptfehler; Dialog bei 1.440/390 Pixeln geprüft. Isolierter Backendport 8098,
Livequellen, Realrouting und externe Simulation deaktiviert.

Das mitgelieferte ZIP und die gemeinsame Profildefinition wurden zusätzlich über
REST installiert, geprüft und generiert: Paketpopulation 80, Registermenge 12 und
separate synthetische Modellgewichte. Die künstlichen Datensätze bleiben technische
Fixtures; diese Ergebnisse belegen keine regionale oder empirische Modellgüte.
[Browsernachweis](verification/2026-10-29-mixed-sources-ui.json),
[Beispielpaketnachweis](verification/2026-10-29-mixed-sources-example.json),
[Desktopbild](figures/mixed-sources/desktop.png),
[Dialog bei 390 Pixeln](figures/mixed-sources/mobile.png).

## Mehrere Register regional ergänzen (2026-10-29)

**Befund:** Acht neue `RegionalWorkplacesTest`-Fälle prüfen den vorab erfassten alten
Profilhash, überlappende Register mit echtem Nullwert und Prioritätsumkehr, atomaren
Abbruch bei Restlücken, expliziten Modellgebrauch bei fehlenden Daten, gefrorene
Revisionen nach Dateientfernung, kommunale Außenmengen unter Vorrang, unabhängige
Punkt-/Polygonreferenzen mit echten EPSG:3035-Zellen, unzulässige Strategien/Provider,
Vorprüfung aller potenziellen Quellen und bytegleiche wiederholte Offlinegenerierung.

Der künstliche Referenzfall A vor B erhält 12 und 0 aus A. B liefert zusätzlich 7;
seine 99 im Überlappungsbereich werden verdeckt. Die Quellenmengen sind pro Register
bilanziert. Für die kombinierte Registerabdeckung wird kein unnötiges natives Modell
aufgelöst. Die Gegenreihenfolge darf andere Gewichte erzeugen; sie ist explizite Policy.

**Konsequenz:** Der regionale Mehrregisterfall ist technisch implementiert. Diese
Belege bescheinigen keine reale Registervollständigkeit oder gemeinsame Definition
verschiedener Beschäftigtenbestände. Die bisherige nicht-regionale Quellenwahl und
alte Profilhashes bleiben abgesichert.
[Beispiel, Bericht und Grenzen](examples/regional-registers/README.md).

**Ausgeführt:** Finale vollständige Backend-Suite
`mvn -o -Daether.syncContext.named.factory=noop test`: 452 Tests, keine Fehler,
neun optionale Skips. Gegenüber dem vorherigen Lauf wurden zusätzlich vier
Live-LLM-Fälle in `GtaBehaviorTest` und ein Fall in `LlmModeChoiceTest` übersprungen;
die übrigen vier sind die bekannten optionalen MobileCity-/actiTopp-/PT-Fälle.
72 gezielte Quellenfälle ohne Fehler/Skips. `npm test`: 110 Tests grün;
`npm run build`: Produktions-AOT grün.

Die echte Browserkette importierte beide Register, wählte die regionale Strategie
und ihre Reihenfolge im Profileditor, konfigurierte die getrennte Lückenliste,
prüfte/speicherte den Plan und generierte offline. A lieferte 12 und den erhaltenen
Nullwert; B lieferte 7 bei verdeckten 99. Kein natives Ersatzmodell wurde benötigt,
obwohl die Lückenliste konfiguriert war. Der Nullwert wurde zusätzlich über die
externe Zonen-ID im Export geprüft. Keine JavaScriptfehler; der Dialog wurde bei
1.440 und 390 Pixeln geprüft. Backendport 8099 mit eigenem Datenverzeichnis,
Livequellen, Realrouting und externe Simulation deaktiviert.

Das mitgelieferte Beispiel-ZIP und seine konkrete Profildefinition wurden zusätzlich
installiert und tatsächlich generiert; dieselben Mengen-/Vorrangregeln bestanden.
Die Zahlen sind synthetische Vertragsbelege, keine empirischen Regionalergebnisse.
[Browsernachweis](verification/2026-10-29-regional-registers-ui.json),
[Beispielpaketnachweis](verification/2026-10-29-regional-registers-example.json),
[Desktopbild](figures/regional-registers/desktop.png),
[Dialog bei 390 Pixeln](figures/regional-registers/mobile.png).

## Rasterzellen mit expliziten Masken (2026-10-30)

**Befund:** Zehn `MaskedCellsTest`-Fälle prüfen Normalisierung und erhaltene
Rohmarker, echte Null, fehlend/nicht erhoben/unterdrückt, widersprüchliche Werte,
Rasterüberlappung, Flächenanteile ohne regionale Hochskalierung, atomare Work-Fehler,
regionale Ergänzung ohne Überschreiben einer Null, Bevölkerungsmengenanker und
widersprüchliche Gesamtsummen, Restmenge null, vollständig unbekannte Quellen,
Teilzonen und bytegleiche wiederholte Offlinegenerierung mit gefrorenen Eingaben.

Im künstlichen Fall liefern die zwei bekannten 1-km-Zellen 30 und 0; drei weitere
Zellen enthalten `missing: NA`, `suppressed: -1` und `not-collected: leer`.
Zielzahl 60 erlaubt eine modellierte Restmenge 30. Unbekannte Zellwerte sind keine
Nullmengen und bilden keine bekannte regionale Gesamtsumme. Bei Work werden
Registermengen nicht mit Ersatzgewichten als beobachtete Summe verrechnet.

**Ausgeführt:** Vollständige Offline-Backend-Suite vom 29.10.2026:
`mvn -o -Daether.syncContext.named.factory=noop test`, 462 Tests, keine Fehler,
neun optionale Skips (zwei MobileCity-Referenzfälle, actiTopp, PT, vier GTA-Live-LLM-
Fälle und LLM-Moduswahl). 82 gezielte Quellenfälle ohne Fehler/Skips.
`npm test`: 112 Tests grün; `npm run build`: Produktions-AOT grün.
[Maschinenlesbarer Abschlussnachweis](verification/2026-10-30-masked-cells-final.json).

Der tatsächliche UI-Ablauf vom 30.10.2026 importierte beide Zellverträge über den
Mappingeditor, wählte konkrete Revisionen und explizite Lückenmodelle, setzte
Gesamtzahl 60, prüfte/speicherte den Plan und generierte offline. Population:
30 bekannte angewendete Personen und 30 modellierte Restmenge; die Nullzone blieb
im Personenexport mit Ziel/Ergebnis 0 erhalten. Work: 30 und Null aus der Quelle,
synthetische Gewichte ausschließlich in offenen Zonen; Null zusätzlich im
Attractivity-Export geprüft. Keine JavaScriptfehler; Dialog bei 1.440/390 Pixeln
geprüft. Eigener Backendport 8100 und eigenes Datenverzeichnis, Livequellen,
Realrouting und externe Simulation deaktiviert.

Das Beispiel-ZIP und die konkrete gemeinsame Profildefinition wurden zusätzlich
installiert, geprüft und generiert. Die gemischten Formate verwenden den gemeinsamen
Importvertrag; dieser Nachweis prüft konkret CSV im UI und im ZIP.
[Browsernachweis](verification/2026-10-30-masked-cells-ui.json),
[Beispielpaketnachweis](verification/2026-10-30-masked-cells-example.json),
[Desktopbild](figures/masked-cells/desktop.png),
[Dialog bei 390 Pixeln](figures/masked-cells/mobile.png).

**Konsequenz/Grenzen:** Der typisierte Zellvertrag ist technisch nutzbar, die
allgemeine Rastermigration bleibt offen. Mengen beziehen sich auf bekannte
Quellzellen; teilweise bekannte Modellzonen werden ganz ergänzt und ihre bekannten
nicht angewendeten Mengen bleiben im Bericht. Kein Schluss auf geheime Einzelwerte.
Die vorhandene Haushaltsrundung erzeugte im Browserfall 63 statt 60 Personen;
die Restmengenregel gilt vor dieser getrennt ausgewiesenen Rundung. Technische
Fixtures belegen keine empirische Regional- oder Ersatzmodellgüte.

## Szenarioübergreifende Datenpaketbibliothek (2026-10-30)

**Befund:** Sieben `DataPackLibraryTest`-Fälle prüfen unabhängige Veröffentlichung,
explizite Kopie ohne Aktivierung, Idempotenz und identische Paket-/Revisionshashes,
Bereitstellung eines installierten Pakets, Versionswechsel ohne Umbindung, sichtbare
Integritätsfehler und weiter nutzbare Szenariokopien, portable Gemeindeabhängigkeiten
und Gebietsstand, ungültige Archive/Pfade, REST-Status sowie wiederholte bytegleiche
Offlinegenerierung nach Entfernen der zentralen Paketdatei. Gemeindeaggregate ohne
mitgelieferte Referenzrevision werden vor Bibliotheksspeicherung abgelehnt.

**Ausgeführt:** Vollständige Backend-Suite
`mvn -o -Daether.syncContext.named.factory=noop test`: 469 Tests ohne Fehler,
neun optionale Skips (zwei MobileCity-Referenzfälle, actiTopp, PT, vier GTA-Live-LLM-
Fälle und LLM-Moduswahl). 89 gezielte Quellenfälle ohne Fehler/Skips.
`npm test`: 116 Tests grün; `npm run build`: Produktions-AOT grün.
[Maschinenlesbarer Abschlussnachweis](verification/2026-10-30-source-library-final.json).

Der [vor dem Browserlauf festgelegte Prüffall](verification/2026-10-30-source-library-plan.json)
verwendet zwei feste ZIP-Versionen mit künstlichen EPSG:3035-Zellen. V1 enthält
bekannte Werte 30/0, V2 50/0; drei weitere Zellwerte bleiben ausdrücklich unbekannt.
Region, Zonierung, Zielzahl 60, Seed 42 und Vergleichsartefakt sind festgehalten.
Der tatsächliche Browserlauf installierte V1 in Szenario A und veröffentlichte sie
bewusst in die Bibliothek. Szenario B übernahm genau V1 mit identischen
Revisionshashes; sein Quellenplan blieb zunächst unverändert deaktiviert. Erst eine
separate UI-Auswahl aktivierte Population/Work mit expliziten Lückenmodellen.

V2 wurde anschließend in der UI veröffentlicht. B behielt V1, seine Quellenbindung
und 30 bekannte Personen plus Restmenge 30. Danach wurde ausschließlich die zentrale
V1-Testdatei absichtlich beschädigt. Die UI zeigte den Fehler und blockierte ihre
Übernahme. Die fünf erneut erzeugten, gefüllten CSVs von B blieben bytegleich: Personen,
Haushalte, Aktivitäten, Zonen und Attraktivitäten. Der ursprüngliche Vergleich des
im Offlinefall leeren gemeinsamen Nachfragecontainers war dafür zu schwach und
wurde vor dem finalen frischen Browserlauf entsprechend ersetzt. Der eigene
Eingabestand von B blieb erhalten. Der
Browserlauf hatte keine JavaScriptfehler. Dialog bei 1.440/390 Pixeln geprüft;
kein horizontaler Dialogüberlauf. Isolierter Backendport 8101 mit eigenem
Datenverzeichnis, Livequellen, Realrouting und externe Simulation deaktiviert.
[Browsernachweis](verification/2026-10-30-source-library-ui.json),
[Desktopbild](figures/source-library/desktop.png),
[Dialog bei 390 Pixeln](figures/source-library/mobile.png).

Im finalen Browservergleich sind die Ausgaben tatsächlich gefüllt: 63 Personen,
28 Haushalte, 1.814 Aktivitätenzeilen und jeweils 21 Zonen-/Attraktivitätszeilen.
Die fünf Dateien besitzen vor/nach Zentralbeschädigung identische Bytes/Hashes.
Der weiterhin separate Haushaltsrundungsbefund (63 statt Ziel 60) wird dadurch nicht
behoben. **Korrektur der ersten Einordnung:** Die älteren Fünfzweigtests vergleichen über
`DataSourcesTest.coreArtifacts` bereits sämtliche Dateien unter `population/`,
`matrix/`, `zone-repository/`, `legacy/` und `logistics/`. Der zusätzliche leere
Nachfragecontainer war nicht ihre einzige Absicherung. Die gegenteilige Aussage
war zu weitgehend. Ergänzt wurden nun eine ausdrückliche Mindestmenge gefüllter
CSV-Dateien sowie maschinenlesbare Fixture-/Ergebnishashes je Zweig. §13/G1 bleibt
wegen der fehlenden isolierten Vor-Umbau-Baseline teilweise offen.

**Konsequenz/Grenzen:** Die installationsweite Paketwiederverwendung ist technisch
belegt. Szenarien haben keine neue zentrale Laufzeitabhängigkeit. Der Nachweis
erstreckt sich auf Paketdaten und ihre notwendigen Referenzen; Quellenprofile
bleiben szenariolokal. Kein Beleg für automatische Regionalauswahl, entfernte
Kataloge, vollständige Quellfamilienmigration oder empirische Modellgüte.
[Bedienung, API und Versionsbeispiele](examples/source-library/README.md).

## Gemeinsame Quellenprofile und Kompatibilitätsnachweise (2026-10-30)

**Befund:** Sieben neue `SourceProfileLibraryTest`-Fälle prüfen identische Profil-/
Paket-/Revisionshashes und Idempotenz ohne Aktivierung, lokale Revisionskandidaten
gegen portable Paketbeiträge, native Regeln und Ausschluss ohne Eingabelesezugriff,
vollständige Vorprüfung aktiver Abhängigkeiten vor der ersten Zielschreiboperation,
Versionswechsel ohne Umbindung, beschädigte gemeinsame Dateien bei gültigen
Szenariokopien, REST-Status und wiederholte gefüllte bytegleiche Ausgaben ohne
zentrale Dateien. Das Ziel wird bei fehlendem nachrangigem Paket nicht teilweise
materialisiert; es bleibt vor dem Übernahmeversuch unverändert.

**Ausgeführt:** Vollständige Backend-Suite
`mvn -o -Daether.syncContext.named.factory=noop test`: 476 Tests ohne Fehler,
neun optionale Skips (zwei MobileCity-Referenzfälle, actiTopp, PT, vier GTA-Live-LLM-
Fälle und LLM-Moduswahl). 96 gezielte Quellenfälle ohne Fehler/Skips.
`npm test`: 120 Tests grün; `npm run build`: Produktions-AOT grün.
[Abschlussnachweis](verification/2026-10-30-shared-source-profiles-final.json).

Der [vorab festgelegte UI-Fall](verification/2026-10-30-shared-source-profiles-plan.json)
verwendet ein festes Rasterpaket und zwei Profildefinitionen für Population und
Work. Szenario A stellte sein vorhandenes Profil bereit; B übernahm dessen konkrete
Version einschließlich einer gemeinsam benötigten Paketversion ohne Aktivierung.
Erst danach wurden Profil, Lückenregeln und Mengenanker in der UI ausdrücklich
gewählt und gespeichert. Der anschließende JSON-Import von V2 änderte die Bindung
von B nicht. Nach absichtlicher Beschädigung ausschließlich zentraler Profil-/
Pakettestdateien erzeugte B aus eigenen Inputs fünf bytegleiche gefüllte CSV-Dateien:
63 Personen, 28 Haushalte, 1.814 Aktivitätenzeilen und jeweils 21 Zonen-/
Attraktivitätszeilen. Die bestehende Haushaltsrundung (Ziel 60, tatsächlich 63)
bleibt unabhängig davon sichtbar. Keine JavaScriptfehler; Dialog bei 1.440/390 Pixeln
ohne horizontalen Überlauf geprüft. Isolierter Testserver auf Port 8102, Livequellen,
Realrouting und externe Simulation aus.
[UI-Nachweis](verification/2026-10-30-shared-source-profiles-ui.json),
[Desktopbild](figures/shared-source-profiles/desktop.png),
[Dialog bei 390 Pixeln](figures/shared-source-profiles/mobile.png).

**Korrektur und Präzisierung von G1:** Die früheren Fünfzweigtests verglichen über
`DataSourcesTest.coreArtifacts` bereits alle Ausgaben unter `population/`, `matrix/`,
`zone-repository/`, `legacy/` und `logistics/`; der zusätzlich verglichene leere
Offline-Nachfragecontainer war nicht ihr einziger Bytevergleich. Die vorige
gegenteilige Einordnung wurde korrigiert. Jetzt werden Personen-, Haushalts-,
Aktivitäten-, Zonen- und Attraktivitätsdateien zusätzlich als vorhandene, gefüllte
Mindestartefakte verlangt. Pro Zweig werden Fixturehashes, Seed, verglichene Modi
und sämtliche Ergebnishashes festgehalten. Im ausgeführten Stand wurden für jeden
der fünf nativen Zweige 69 fachliche Dateien über Plan aus, explizites Legacy und
Standardprovider bytegleich verglichen.
[Maschinenlesbarer Fünfzweignachweis](verification/2026-10-30-population-compatibility.json).
Dies schützt die aktuelle Implementierung; eine isolierte Vor-Umbau-Baseline wird
weiterhin nicht behauptet.

**Konsequenz/Grenzen:** Profile sind innerhalb derselben Installation samt festen
Datenabhängigkeiten wiederverwendbar. Native Regeln bleiben Aufträge zur Auflösung
im Ziel, keine eingefrorenen Quellantworten. Keine automatische regionale Auswahl,
keine vollständige Migration aller Quellfamilien und keine empirische Modellfreigabe.
[Portabler Vertrag und Beispieldefinitionen](examples/shared-source-profiles/README.md).

## Quellenplan, Schwerlast-/Unternehmensbeiträge und native Zielwirkung (2026-11-02)

**Befund:** Der neue relationale Bericht `synthesis-freight-source-resolution-v1`
verbindet tatsächliche Schwerlastfahrten mit ihren gebundenen Quellrelationen.
Die Eingangs-/Ergebnismenge stimmt je Relation überein; `freight/trips.csv` liefert
sämtliche Einzelidentitäten. Die technische Mengendemonstration ist in diesem
Bericht, den bisherigen Freight-Berichten und dem Manifest konsistent als Annahme
gekennzeichnet. Die Zuordnung behauptet keinen Routing-/Abschlussnachweis; dafür
bleiben native Ergebnisbilanzen zuständig.

Die [vorab festgelegte Prüfmenge](verification/2026-11-02-source-plan-traffic-plan.json)
verwendet versionierte, gehashte CSV-/OSM-Fixtures, Seeds 7/42/777 und zwei übersetzte
synthetische Netzlagen. Das sind keine unabhängigen Stadt-/Landreferenzen und keine
empirische Quellenabnahme. Der vorhandene bestätigte Firmenimport bleibt als
separater Beitrag aktiv. Der neue Exporttest umfasst Bestand, Revision A, Profil
mit A zuerst, Revision B und Schwerlastausschluss:

| Fall | Schwere Fahrten | Unternehmensbeine | Gemeinsame Bewegungen |
|---|---:|---:|---:|
| Bestand / Revision A / Profil A | 8 | 3 | 11 |
| Revision B | 9 | 3 | 12 |
| Schwerlastausschluss | 0 | 3 | 3 |

**Ergebnis:** Die 69 Dateien unter Population, Matrizen, Zonen, Legacy und Logistik
bleiben in jeder Lage und bei jedem Seed bytegleich. Die gleichwertigen Bestands-/
Revisionsdaten liefern zudem denselben gefüllten gemeinsamen Nachfragecontainer.
Unternehmensimport-, Mapping-, Ausführungsidentitäten und Beine bleiben gleich.
Die Erhöhung einer einzelnen Schwerlastrelation verändert die übrigen Relationen
und deren Abfahrten nicht. Ein kontrollierter POI-Wartepunkt belegt, dass die bereits
gebundene Revision trotz später beschädigter Eingabedatei und umgestellter
Szenarioauswahl im laufenden Job erhalten bleibt.

Die neue native Abnahme läuft durch den echten `GenerationService` mit Profil A,
Revision B und Schwerlastausschluss: 2 Netzlagen × 3 Seeds × 3 Varianten = **18 echte
SUMO-Läufe**. Die 11/12/3 angeforderten Bewegungen sind jeweils geroutet, abgefahren
und abgeschlossen; null Teleports. Die physische Fahrzeugzahl beträgt dabei 9/10/1,
weil die drei Unternehmensbeine mit einem Fahrzeug ausgeführt werden. Generierte
Anfragen und physische Fahrzeuge werden nicht gleichgesetzt.

**Wichtige Grenze:** logiTopp und GTFS sind in diesen Fällen ausdrücklich deaktiviert.
Es gibt keine simulierten privaten Personen- oder Paketfahrten in den nativen
Nachfragecontainern. Die Population-/Aktivitätsartefakte sind gefüllt und unverändert;
das ist eine andere Aussage als private Nachfrage bis zur SUMO-Ausführung. G12 ist
daher nur für den schweren Hintergrund plus Unternehmen teilweise abgedeckt.

**Negativbefund während der Absicherung:** Der erste vollständige Lauf traf im
neuen Wartepunkttest ein Zeitlimit. Sein Testdouble hatte die einargumentige
Places-Methode überschrieben, die Pipeline ruft aber die zweistellige Überladung
auf. Damit war ein externer Abruf möglich. Der Test überschreibt nun beide Methoden;
zusätzlich sind öffentliche Overpass-Endpunkte/Mirrors abgeschirmt und GTFS ist in
der Fixture ausdrücklich aus. Die korrigierten sieben Fälle wurden unter denselben
Ausführungsrechten erneut ohne Fehler geprüft. Es wurde keine Toleranz ausgeweitet.

**Ausgeführt:** Vollständige Backend-Suite
`mvn -o -Daether.syncContext.named.factory=noop test`: 483 Tests ohne Fehler,
neun optionale Skips (zwei MobileCity-Referenzfälle, actiTopp, PT, vier GTA-Live-LLM-
Fälle und LLM-Moduswahl). Die zwei neuen nativen Parameterfälle wurden ausgeführt,
keiner übersprungen. Der gezielte Quellenlauf umfasste 103 Fälle ohne Fehler/Skips;
`npm test` 121 Tests grün, `npm run build` Produktions-AOT grün.
[Abschlussnachweis](verification/2026-11-02-source-plan-traffic-final.json),
[Export-/Invarianzbelege](verification/2026-11-02-source-plan-traffic-export.json),
[native Routing-/Ausführungsbelege](verification/2026-11-02-source-plan-traffic-native.json).

**Konsequenz:** G10 ist für den benannten Schwerlast-/Unternehmensschnitt technisch
abgesichert; G12 ist um echte native Belege erweitert. Der fachliche Schwerlastfallback,
aktive Paket-/private Ausführung, MobileCity und unabhängige reale Regionalfälle
bleiben eigene Abnahmen. Die neue UI-Formulierung erklärt diese Trennung; sie ist
keine zusätzliche Schaltung anderer Nachfragebeiträge.
[Bericht, Bedienung und reproduzierbare Testbefehle](examples/source-plan-traffic/README.md).

### Quellenlaufbericht und lesbare Teileherkunft (2026-11-04)

**Befund:** Die vorhandene Eingangsprüfung und ein JSON-Link genügten nicht für
eine lesbare Beurteilung der tatsächlich verwendeten Teilbeiträge. Insbesondere
fehlte eine persistierte Laufzuordnung, wenn die Pipeline vor dem Manifest abbrach.

**Umgesetzt und geprüft:** `SourceRunReport` bindet den vorhandenen Bericht an
Laufstatus, Zeitpunkte, Quellenplan und Quellenbindungshashes. Am Start, bei Manifest
und beim Laufende wird ein vollständiger atomarer Dateistand geschrieben. Ein
kontrollierter Wartepunkt prüft Erfolg → neuer laufender Stand → früher Work-Fehler:
Die neue Teilbilanz enthält ihre aufgelöste Bevölkerungsverteilung, aber keine
früheren Arbeitsgewichte, synthetisierten Personen oder Schwerlastmengen. Bei
ausgeschaltetem Plan wird der alte Bericht entfernt. Ein absichtlich vorhandener
SUMO-Dateirest bleibt bei übersprungener Zielstufe ungebunden. Native Migrationsläufe
prüfen die aktuelle aggregierte Ausführungsbilanz samt Hash der vollständigen Datei.

Die Frontendtests sichern echte Nullwerte, getrennte Maskenzähler, Registermengen
einschließlich verdrängter Mengen, unbekannte/angenommene/abgeleitete Evidenz,
Zonenumfang, Haushaltsrundung und die Trennung von Generierung und Abschluss.
Ladefehler und Szenariowechsel entfernen veraltete Anzeigen; alte/unpassende oder
unvollständige Berichte werden nicht als aktuelle Laufbilanz interpretiert.

**Browserabnahme:** Isoliertes Backend und Produktionsfrontend, ausschließlich
künstliche Quellen. Der erfolgreiche Lauf zeigt Zielbevölkerung 12, tatsächlich
13 Personen und Rundungsdifferenz 1 sowie acht erzeugte, geroutete, gestartete und
abgeschlossene Schwerlastfahrten bei null Teleports. Ein danach absichtlich erzeugter
Work-Quellenfehler zeigt den neuen Fehlerstatus und seine Kandidatendiagnose;
vorherige Freight-/SUMO-Erfolge werden nicht übernommen. Keine Browser-Konsolenfehler
in dieser Prüfung. Die Nachweise stehen im
[Browserprotokoll](verification/2026-11-04-source-run-report-ui.json) und in den
[Ansichten](examples/source-run-report/README.md).

**Vertragsänderung während der Absicherung:** Drei ältere Fehlerpfadtests erwarteten
nach einem Abbruch überhaupt keine `resolution.json`. Diese Annahme wurde bewusst
durch den neuen Vertrag ersetzt: Bericht mit `run.state=FAILED`, eigene Fehler und
ausschließlich eigene Teilbeiträge. Die Tests wurden auf fehlende alte Erfolgsanteile
verschärft; Fehlerdiagnose und Bereinigung beim Abschalten bleiben abgesichert.

**Ausgeführt:** Backend-Gesamtsuite 485 Fälle ohne Fehler, 4 optionale Skips; 18 native Quellenplan-SUMO-Läufe tatsächlich ausgeführt. Frontend 130 Tests ohne Fehler. [Gesamtsuiten, optionale Skips und Prüfbelege](verification/2026-11-04-source-run-report-final.json).
`npm run build` prüft den strikten Produktions-AOT. Die Browserabnahme nutzt den
Produktionsbuild; nachfolgende Änderungen ergänzen nur die Abwehr unvollständiger
Berichte und die konsistente Herkunft in Teilfehler-Metadaten, jeweils testabgesichert.

**Konsequenz:** Die UI-Lücke des bisherigen Drei-Aspekte-Schnitts ist geschlossen.
Die technische Beweismenge ersetzt keine fachliche Quellenfreigabe. G1–G12 behalten
ihre bisherigen, begrenzten Zuständigkeiten; private/Paket-Migration, echte regionale
Referenzen und MobileCity-Abnahme bleiben offen. Nur letzter Artefaktsatz, kein
Laufarchiv und kein automatischer Abgleich mit allen später geänderten Eingaben.
