---
name: verkehrsmodellierung-reference
description: Domänen-Referenz Verkehrsmodellierung für SyntheSis — nutze diesen Skill, wenn du beim Arbeiten im Repo Begriffe wie Modal-Split, MNL, ASC, MSA, HBEFA, GTFS, gtfs2pt, Visum-Matrix ($V;D3, .mtx.bz2), MiD 2017, RegioStaR, actiTopp, IPU/Populationssynthese, StandardMode-Codes, LegacyActivityType, fixedDestination, CEP/cepsp, Packstation, vClass/vType, edgeData, tripinfo, duarouter/netconvert oder demandsimulationResult.csv im Code triffst und wissen musst, was sie fachlich bedeuten UND wie genau dieses Repo sie kodiert (Codes, Spaltenindizes, Dateinamen, Einheiten).
---

# Verkehrsmodellierungs-Referenz (wie in SyntheSis angewandt)

Zielgruppe: Mid-Level-Informatiker:in ohne Verkehrsplanungs-Hintergrund, die an diesem
Repo arbeitet. Jeder Begriff: 1–2 Sätze Definition, dann sofort der konkrete Repo-Bezug
(Datei/Klasse/Codes), dann die Stolperfalle. Kein Lehrbuch — alles hier ist gegen den
Code bzw. gegen echte Läufe (docs/05-validierung.md) verifiziert.

Systemkontext (das WARUM der Architektur): siehe Skill `synthesis-architecture-contract`.
Die MNL-/Zielwahl-Sektion hier ist die fachliche Grundlage der Distanz-Kampagne
(`synthesis-distanz-kampagne`).

---

## 1. Agentenbasierte Nachfragemodellierung / mobiTopp / logiTopp

**Definition:** Ein agentenbasiertes Nachfragemodell simuliert jede Person einzeln:
sie hat einen Wochen-Aktivitätenplan (wo bin ich wann, wie lange?) und trifft je Weg
Ziel- und Verkehrsmittelentscheidungen. mobiTopp (KIT IFV) ist so ein Modell mit
einer Simulationswoche als Zeithorizont; logiTopp erweitert es um Paketlogistik
(Bestellungen, Depots, Zustelltouren).

**So hier benutzt:** SyntheSis *generiert* die mobiTopp-Eingaben aus einer Kartenregion
und ruft dann den Legacy-Stack als externen Gradle-Prozess auf
(`backend/src/main/java/de/synthesis/generation/pipeline/SimulationRunner.java`,
Projekt-Default `../validation/mobitopp-rastatt`, Task `runDeliverySynthesis`).
Die 7 Kern-Eingabe-CSVs (alle `;`-getrennt) plus Matrizen:

| Datei | Inhalt | Erzeuger |
|---|---|---|
| `zones.csv` | Verkehrszellen (Gitter aus der Region-BBox) | `GridZoning` |
| `attractivities.csv` | Ziel-Attraktivität je Zelle × Zweck (aus OSM-POIs) | `AttractivityGenerator` |
| `person.csv`, `household.csv` | synthetische Bevölkerung | `PopulationGenerator` |
| `activity.csv` | Wochenaktivitätenpläne (Achse A) | `ActivityGenerator` |
| `car.csv` | Fahrzeuge (positional geparst, 12 Felder!) | `PopulationGenerator` |
| `fixedDestination.csv` | feste Ziele (WORK/EDUCATION je Person) | `PopulationGenerator` |
| `matrix/**.mtx.bz2` + YAMLs | Reisezeit/Kosten/Distanz | `MatrixGenerator` |

**Versionslage (docs/01-analyse.md):** Das aktuelle mobiTopp ist ein **Kotlin-Rewrite**;
logiTopp hängt aber an `mobitopp:0.3+` (**Legacy-Java**, `0.3.580` auf Maven Central).
Deshalb der Hybrid: Artefakte werden für *beide* Welten geschrieben — das Tutorial-Format
(Kotlin-Stack, validiert in docs/05) und der `artifacts/legacy/`-Export
(`LegacyExportGenerator`) für den logiTopp-Lauf.

**Stolperfalle:** Legacy-Demand-Daten (`homeZone` in household.csv, `zoneId` in
fixedDestination.csv) referenzieren Zonen über die **OID/matrixColumn**, nicht die
externe ID — falsche IDs werden *still* verworfen (0 Haushalte, kein Fehler; Befund A2
in docs/05). Ausnahme: der `DistributionCenterParser` (Depots) nutzt die **externe ID** (A9).

---

## 2. StandardMode-Codes (Verkehrsmittel, Legacy)

**Definition:** mobiTopp-Legacy kodiert das gewählte Verkehrsmittel (mode) je Weg als
Integer (`StandardMode`-Enum).

**So hier benutzt** — verifiziert in `SimulationResultIngestor.modeName` und den
`SumoRunner.read*Trips`-Filtern:

| Code | Mode | kpis.json-Schlüssel | SUMO-vType |
|---|---|---|---|
| 0 | BICYCLE | `bike` | `fahrrad` |
| 1 | CAR (Fahrer) | `car` | `pkw` |
| 2 | PASSENGER (Mitfahrer) | `passenger` | — (nicht simuliert) |
| 3 | PEDESTRIAN | `pedestrian` | `fuss` (SUMO-`<person>`) |
| 4 | PUBLICTRANSPORT | `publicTransport` | `oev` (SUMO-`<person>`, intermodal) |
| sonst | — | `other` | — |

**Ergebnisdatei `demandsimulationResult.csv`** (logiTopp-Output, eine Zeile je
Weg-Etappe). Zwei Lesarten im Repo:

- `SimulationResultIngestor` liest **per Header-Name** (`personOid`, `distanceInKm`,
  `legId`, `tripMode`) — robust gegen Spaltenverschiebung.
- `SumoRunner.readCarTrips/readBikeTrips/readWalkTrips/readTransitTrips` liest **per
  festem 0-basiertem Index** (verifiziert gegen einen echten Lauf-Header):

| Index (0-basiert) | Spalte | Bedeutung |
|---|---|---|
| 1 | `legId` | `0` = Wegbeginn (nur diese Zeilen zählen als Trip) |
| 12 | `tripMode` | StandardMode-Code (Tabelle oben) |
| 16 | `distanceInKm` | Weglänge in km |
| 38–41 | `fromX;fromY;toX;toY` | Lon/Lat-Koordinaten |
| 42 | `tripBegin` | **Minuten seit Montag 00:00 der Modellwoche** |

**Stolperfalle:** `tripBegin` sind Minuten der ganzen Modellwoche, nicht des Tages —
`SumoRunner` filtert mit `day*1440 <= begin < (day+1)*1440` (Config `synthesis.sumo.day`,
0 = Montag) und rechnet `(begin - dayStart) * 60` in SUMO-Sekunden um. Wer neue Modi
in SUMO einspeist, muss beide Lesarten (Header vs. Index) konsistent halten.

---

## 3. Aktivitätstypen (LegacyActivityType-Codes)

**Definition:** Jede Aktivität eines Wochenplans hat einen Zweck (activity type);
Legacy-mobiTopp erwartet ihn in `activity.csv` als **numerischen** Code, und das
Aktivitätsset ist **projektspezifisch** (Enum im jeweiligen Projektcode — Befunde 2/3
in docs/05).

**So hier benutzt** — Quelle `ActivityGenerator.code(type, age)`:

| Code | Typ | Anmerkung |
|---|---|---|
| 1 | WORK | |
| 31 / 32 / 33 | EDUCATION Primary/Secondary/Tertiary | altersabhängig: `<11` → 31, `<19` → 32, sonst 33 |
| 41 | SHOPPING_DAILY | actiTopp-SHOPPING `<60 min` |
| 42 | SHOPPING_OTHER | actiTopp-SHOPPING `>=60 min` |
| 51 / 52 | LEISURE_INDOOR / _OUTDOOR | |
| 6 | SERVICE | Begleit-/Bringwege (actiTopp TRANSPORT) |
| 7 | HOME | |
| 12 | PRIVATE_VISIT | |
| 23 / 71 | DELIVER_PARCEL / PICK_UP_PARCEL | nur das logiTopp-Aktivitätsset kennt sie |

Rückwärts-Mapping auf Zwecke: `MobileCityExporter.purposeOf` gruppiert
31/32/33→Education, 1→Work, 41/42→Shopping, **51/52/6/12→Leisure**; alles andere
(7=HOME, Paket-Codes) → −1 (kein Weg-Zweck). actiTopp→Legacy:
`ActivityGenerator.mapActitoppType` (WORK, EDUCATION, SHOPPING nach Dauer,
LEISURE→LEISURE_OUTDOOR, TRANSPORT→SERVICE, Rest→HOME).

**Stolperfalle:** `activity.csv` braucht für den Legacy-Parser die **Tour-Struktur**:
der Tag muss zu Hause enden, und aufeinanderfolgende HOME-Zeilen (z. B. über
Mitternacht) müssen zu einer gemergt sein, sonst crasht `TourPattern` (Befund A5).
`ActivityGenerator.scheduleDay` erzwingt das (verwirft Aktivitäten, die nicht vor
Mitternacht passen; `DAY_MARGIN = 40`). Keine geplanten 71er-Aktivitäten schreiben —
logiTopp erzeugt Abholungen selbst per Rescheduling (Befund 3).

---

## 4. Modal-Split, MiD 2017 & RegioStaR

**Definition:** Der Modal-Split ist die Anteilsverteilung der Wege auf Verkehrsmittel.
Die MiD 2017 („Mobilität in Deutschland", BMVI-Haushaltsbefragung) liefert die
bundesweiten Referenzwerte; RegioStaR7 ist die BMDV-Raumtypologie (71=Metropole …
77=dörflich), nach der die MiD regional aufgeschlüsselt wird.

**So hier benutzt:** Die MiD-Referenz ist doppelt fest verdrahtet — als
Vergleichsspalte in `SimulationResultIngestor.MID_MODAL_SPLIT` (+
`MID_TRIPS_PER_PERSON_DAY = 3.1`, `MID_AVG_TRIP_KM = 12.5`) und als Default-
Kalibrierziel in `Scenario.Population.defaultModalSplit()` /
`ModeChoiceCalibrator.MID_2017`:

| Mode | Anteil (MiD 2017 bundesweit) |
|---|---|
| pedestrian | 0.22 |
| bike | 0.11 |
| car (Fahrer) | 0.43 |
| passenger (Mitfahrer) | 0.14 |
| publicTransport | 0.10 |

Regionalisierung: `frontend/src/app/features/studio/studio.component.ts`
(`regionPresets`) bietet RegioStaR-Presets (Metropole 71 bis ländlich/dörflich 77) als
Kalibrierziel-Vorlagen, z. B. Metropole 0.27/0.13/0.27/0.13/0.20. **Das sind
Richtwerte nach dem MiD-Stadt-Land-Muster, keine offiziellen Regionaltabellen**
(so im Code kommentiert) — bei Bedarf gegen echte MiD-Regionalauswertungen ersetzen.

**Stolperfalle:** `tripsPerPersonDay` in kpis.json wird durch **7** geteilt
(Wochensimulation). Und: kleine Untersuchungsgebiete können die MiD-Weglänge
(12,5 km) geometrisch nicht erreichen — 2–4 km sind dort *erwartbar*, kein Bug
(docs/05, Plausibilitätstabellen).

---

## 5. Discrete Choice / MNL / ASC (Moduswahl-Kalibrierung)

**Definition:** Multinomiales Logit (MNL) ist das Standard-Wahlmodell der
Verkehrsmodellierung: die Wahrscheinlichkeit einer Alternative ist
`exp(Nutzen) / Σ exp(Nutzen)`. Die alternative-spezifische Konstante (ASC) ist der
Nutzen-Offset einer Alternative, der alles Unbeobachtete auffängt — der
Standardhebel, um einen Ziel-Modal-Split zu treffen, ohne die geschätzten
Sensitivitäten (Zeit-/Kostenparameter) anzufassen.

**So hier benutzt:** `ModeChoiceCalibrator` betreibt „Parameter-Transfer + lokale
Kalibrierung": das Rastatt-geschätzte MNL-Parameterfile
`validation/mobitopp-rastatt/config/shared/parameters/mode_choice_main_parameters-multi-nomial-logit.txt`
(Achtung: `validation/` ist **gitignored** — die Datei existiert nur lokal neben dem
Legacy-Projekt) wird iterativ umgeschrieben nach
`artifacts/simulation/mode_choice_calibrated.txt`.

| Parameter | Mode | Rolle |
|---|---|---|
| `asc_ped` | pedestrian | kalibriert |
| `asc_bike` | bike | kalibriert |
| `asc_car_d` | car (Fahrer) | **Anker, bleibt fest** (`ANCHOR_MODE`) |
| `asc_car_p` | passenger | kalibriert |
| `asc_put` | publicTransport | kalibriert |

Update-Regel je Iteration (Standard-Heuristik):
`ΔASC_m = ln(Ziel_m / Sim_m)`, geklemmt auf **±1,5**, Anteile nach unten auf 0,003
begrenzt (kein `ln(0)`). Konvergenz: **RMSE ≤ 0,02** über alle 5 Modi; Defaults
max. 8 Iterationen (`SynthesisProperties.Simulation`: `calibrationIterations=8`,
`calibrationTolerance=0.02`). Jede Iteration = ein voller logiTopp-Lauf
(`GenerationService`, Flag `scenario.population.calibrateModeChoice`; Ziel:
`scenario.population.calibrationTarget`, Fallback `MID_2017`).

**Zielwahl (destination choice):** analog ein Discrete-Choice-Modell, dessen
Distanz-Disnutzen (Rastatt-kalibriert) die Weglängen deckelt — externe Zonen bei
25 km werden **nie** gewählt (docs/05 „Außenbeziehungen"). Das ist DER Hebel der
Distanz-Kampagne (Skill `synthesis-distanz-kampagne`); die ASC-Kalibrierung hier
ändert daran nichts, sie verschiebt nur Modusanteile.

**Stolperfalle:** Der Calibrator parst nur Zeilen der Form `key = value` und ersetzt
ausschließlich die 4 ADJUSTABLE-Keys; Interaktionsterme wie `age_0_17_on_asc_car_d`
bleiben unberührt — genau so gewollt (Sensitivitäten erhalten). Fehlt das
Legacy-Projekt (und damit das MNL-File), wird die Kalibrier-/Simulationsstufe
SKIPPED, nicht FAILED.

---

## 6. MSA — Method of Successive Averages (Netz-Feedback)

**Definition:** MSA dämpft Gleichgewichtsiterationen mit Schrittweite 1/k:
`neu = alt + (beobachtet − alt)/k`. Ohne Dämpfung oszilliert eine
Nachfrage↔Netz-Schleife (Stau → alle weichen aus → kein Stau → alle zurück …).

**So hier benutzt:** `FeedbackMath.msaUpdate(smoothed, factors, k)` (in-place, k
1-basiert; k=1 übernimmt ungedämpft), Test `FeedbackMathTest`. Die Schleife
(`GenerationService`, Stufe 13 „Netz-Feedback", Flag `population.sumoFeedback`):

1. `SumoRunner.computeOdFactors` routet alle Zonen-Zentroid-Paare **zweimal** per
   duarouter — frei und gewichtet mit gemessenen Kantenzeiten (`edgedata.xml`,
   `--weight-attribute traveltime`). OD-Faktor = staugewichtete/freie Routenzeit,
   **geklemmt auf [1, 3]**; unroutbare/externe Relationen bleiben 1.
2. MSA-Glättung der Faktoren; Konvergenzmaß = mittlere absolute Änderung über
   Relationen ≠ 1.
3. Pkw-Matrix skalieren (`carTravelTimeMin × smoothed`) und als
   `matrix/travel_times/CAR_TTC.mtx.bz2` + `legacy/matrices/travel_times/CAR_TTC.mtx`
   zurückschreiben → logiTopp entscheidet neu → SUMO erneut.

Defaults (`synthesis.sumo.*` in `backend/src/main/resources/application.yml`):
`feedback-iterations: 3`, `feedback-tolerance: 0.01`, `feedback-max-zones: 150`
(OD-Routing ist n²).

**Stolperfalle:** Konvergenz-Check erst ab Iteration 2 (`iteration > 1 && delta <
tolerance`) — bei k=1 ist Δ konstruktionsbedingt groß. Beide Matrix-Kopien
(Tutorial-Layout *und* Legacy-Layout) müssen geschrieben werden, sonst rechnet
logiTopp auf den alten Zeiten weiter.

---

## 7. Visum-Matrixformat ($V;D3, .mtx.bz2, Matrix-YAMLs)

**Definition:** PTV Visum ist die kommerzielle Standard-Software der Verkehrsplanung;
ihr Text-Matrixformat `$V;D3` (Header mit Zeitbereich/Faktor, Zonenliste,
Werteblock) ist das Austauschformat, das mobiTopp für Reisezeit-/Kosten-/
Distanzmatrizen parst.

**So hier benutzt:** `VisumMatrixWriter` schreibt `$V;D3` byte-kompatibel zum
mobiTopp-Parser (`edu.kit.ifv.mobitopp.visum.VisumMatrixParser`, validiert docs/05);
Dateien mit Endung `.bz2` werden transparent komprimiert. `MatrixGenerator` erzeugt
das Tutorial-Layout unter `artifacts/matrix/`:

| Pfad | Inhalt | Einheit |
|---|---|---|
| `travel_times/{CAR,BIKE,PED,PUT}_TTC.mtx.bz2` | Reisezeiten | Minuten |
| `travel_times/PUT_{ACT,EGT}.mtx.bz2` | ÖV Zu-/Abgang (access/egress) | Minuten (konstant 6) |
| `travelcosts/{CAR,PUT,BS}_COST.mtx.bz2` | Kosten | EUR |
| `other/{CAR,BIKE,AIR}_DIS.mtx.bz2` + `DIS_Car.mtx.bz2` | Distanzen | **METER** |

Zwei YAML-Konfigs (`time-matrix-configuration.yaml`, `cost-matrix-configuration.yaml`)
mappen Modus → Matrix (Layout `MODE: → all weeks: → everyday: → 0:00 to 24:00: →
visum_matrix: $…`).

**Stolperfallen (docs/05-Befunde 6–8):** (a) Distanzmatrizen liegen unter `other/`
und sind in **Metern** — km-Werte machen alle Distanz-KPIs um Faktor 1000 falsch.
(b) Beide YAMLs müssen **alle** Modi des Zielprojekts abdecken, auch BIKESHARING;
kostenlose Modi (BIKE, PEDESTRIAN) über den Trick `constant_matrix: "0.0"`
(String!) statt einer Matrixdatei. (c) Externe Zonen (`Zone.external`) werden nie
geroutet — GraphHopper würde auf den Netzrand snappen (zu kurz) — sondern immer
Luftlinie × Umwegfaktor (`MatrixGenerator.route`).

---

## 8. SUMO-Grundbegriffe wie hier genutzt

**Definition:** SUMO (Eclipse) ist ein mikroskopischer Verkehrssimulator:
`netconvert` baut aus OSM ein Netz, `duarouter` routet Trips zu Routen, `sumo`
simuliert sekundenweise. `vClass` regelt, wer eine Kante benutzen darf
(passenger/bicycle/pedestrian/delivery/bus/rail…); ein `vType` ist eine
Fahrzeugtyp-Definition (mit vClass, Emissionsklasse etc.).

**So hier benutzt:** `SumoRunner.run` (Pipeline-Stufe 12) — bewusst ohne
Python/sumolib: Trips werden mit `fromLonLat/toLonLat` geschrieben, das Snapping
macht `duarouter --mapmatch.distance 300` nativ (`MAPMATCH_DISTANCE_M = 300`).

| Element | Repo-Konkretisierung |
|---|---|
| Netzbau | `netconvert --keep-edges.by-vclass passenger,bicycle,pedestrian --sidewalks.guess --crossings.guess …` → `net.net.xml` |
| Fahrzeuge | `<trip>` in `trips.xml`; vTypes aus `SumoRunner.writeTripsXml`: `pkw` (vClass passenger), `fahrrad` (bicycle), `lkw` (delivery), `lastenrad` (bicycle) |
| Personen | `<person><personTrip …/></person>` in `persons.xml` — Fußgänger ohne, ÖV-Fahrgäste mit `modes="public"` (intermodal Fußweg→Ride→Fußweg) |
| ÖV | additional-files aus `TransitRunner` (vtypes/stops/vehicles), müssen VOR den Fahrzeugen geladen werden |
| Netzlast | `<edgeData id="netzlast" file="edgedata.xml"/>` (tages-aggregiert) → `writeCongestionGeoJson` verbindet Messwerte mit dem plain-geo-Export zu `sumo/congestion.geojson` (`relSpeed` = gemessen/Limit, Stau-Indikator der Karten-Layer) |
| KPIs | `tripinfo.xml` (je Fahrzeug, inkl. Emissionen) + `statistics.xml` (`<vehicleTripStatistics count/duration/routeLength/speed>`) |
| Sim-Fenster | `scenario.sumocfg`: begin 0, end 86400 (ein Tag), `time-to-teleport 300`, `device.emissions.probability 1` |

**Stolperfalle (der Rail-Blocker, docs/06 „Phase 2 – SUMO", 2026-07-07):**
`--mapmatch.distance` snappt Personen-Koordinaten auf die **nächste Kante ohne
vClass-Filter**. Nahe Bahnhöfen sind das Rail-Kanten, auf denen Fußgänger nicht
laufen dürfen → „No destination edge for walk or personTrip" bricht den Lauf ab;
`--mapmatch.junctions` verwirft Betroffene kommentarlos (0 geroutet), ein
`--mapmatch.vclass` existiert nicht. Deshalb ist ÖV aktuell auf **Bus beschränkt**
(Rail/Tram-Versuch zurückgerollt; Lösungsweg in docs/06). Zweite Falle:
`time-to-teleport` + `--ignore-errors` kaschieren Netzprobleme — bei unplausiblen
KPIs zuerst `sumo/sumo.log` lesen.

---

## 9. GTFS (Fahrplandaten) & gtfs2pt

**Definition:** GTFS (General Transit Feed Specification) ist das Standard-Zip-Format
für ÖV-Fahrpläne: Kerntabellen `stops.txt`, `routes.txt`, `trips.txt`,
`stop_times.txt`, `calendar.txt`(+`calendar_dates.txt`); `route_type` in routes.txt
kodiert das Verkehrsmittel (0=Tram, 2=Rail, 3=Bus; dazu erweiterte Codes).

**So hier benutzt:**

- **Beschaffung:** Auto-Fetch nach Region über eine Feed-Registry
  (`synthesis.transit.feeds` in `backend/src/main/resources/application.yml`,
  z. B. KVV via NVBW/MobiData-BW); ein bewusst ausgewählter Upload hat Vorrang
  (`scenario.population.gtfsUploadPath`).
- **Zuschnitt:** `GtfsCropper` filtert streaming auf BBox + Zeitfenster
  (`window-start-hour`/`window-end-hour`, Default 6–10 Uhr) unter Wahrung der
  referenziellen Integrität (stops→trips→stop_times→routes→agency/calendar).
  **`shapes.txt` wird komplett verworfen** (dominiert die Feed-Größe, z. B. KVV
  121-MB-Zip → wenige hundert KB; gtfs2pt mappt selbst aufs Netz), die
  `shape_id`-Spalte in trips.txt wird gestrichen. `route_type` wird im eigenen Code
  nicht ausgewertet — die Modus-Auswahl passiert erst in gtfs2pt.
- **Konvertierung:** `TransitRunner` ruft `gtfs2pt.py` (aus `$SUMO_HOME/tools`) im
  Python-venv (pandas/rtree/pyproj — die einzige Python-Stelle im Projekt) auf →
  `pt_stops.add.xml` (Haltestellen) + `pt_vehicles.add.xml` (fahrplangebundene
  Fahrzeuge); Ergebnis wird gecacht (tracemapper ist langsam). Config
  `synthesis.transit.modes: bus`.

**Stolperfallen:** (a) GTFS-Zeiten können **>24 h** sein (`25:10:00` = 1:10 Uhr
Folgetag) — `GtfsCropper.gtfsSeconds` parst das korrekt, eigener Code muss es auch.
(b) `gtfs2pt --modes` nimmt **OSM-Namen** (bus/train/tram), referenziert die
Fahrzeuge in der Ausgabe aber über **SUMO-vClasses** (train→rail,
light_rail/monorail→rail_urban) — `TransitRunner.vClassOf` übersetzt das, sonst
fehlen die vTypes und kein ÖV-Fahrzeug startet. (c) gtfs2pt schreibt die
vType-Definitionen **nicht** selbst — `TransitRunner.writeVtypes` liefert sie
(`pt_vtypes.add.xml`, id = vClass). (d) GTFS-Dateien beginnen oft mit UTF-8-BOM
(wird im Cropper übersprungen). (e) `--date` (YYYYMMDD) muss im Feed-Kalender
liegen, sonst 0 Fahrten.

---

## 10. HBEFA (Emissionen)

**Definition:** Das „Handbuch Emissionsfaktoren des Straßenverkehrs" (HBEFA) liefert
Emissionsfaktoren je Fahrzeugkategorie/Euro-Norm; SUMO bündelt sie als
`emissionClass`-Presets (`HBEFA3/...`) und misst je Fahrzeug über
`device.emissions`.

**So hier benutzt:** `scenario.sumocfg` setzt `device.emissions.probability = 1`
(jedes Fahrzeug misst). Klassenzuordnung:

| vType | emissionClass | Interpretation |
|---|---|---|
| `pkw` | `HBEFA3/PC_G_EU4` | Benzin-Pkw Euro 4 (`SumoRunner.writeTripsXml`) |
| `lkw` | `HBEFA3/LDV_D_EU4` | Diesel-Lieferwagen Euro 4 |
| `fahrrad`, `lastenrad` | `Zero` | emissionsfrei |
| `bus` | `HBEFA3/Bus` | Diesel-Linienbus (`TransitRunner.emissionClass`) |
| rail/tram/… | `Zero` | elektrisch angenommen |

Auswertung: `SumoRunner.parseEmissions` scannt `tripinfo.xml` zeilenweise — jede
`<tripinfo vType="…">`-Zeile setzt den Kontext, der `<emissions CO2_abs="…"/>`-
Kindknoten liefert **CO₂ in Milligramm**, geteilt durch 1 000 000 → kg, summiert je
vType (`Result.co2KgByType`, im Frontend „CO₂ je Fahrzeugtyp").

**Stolperfalle:** `CO2_abs` ist mg, nicht g. Und die Klassen sind bewusste
Pauschalannahmen (eine Flotte = eine Euro-4-Klasse) — für Flottenmix-Szenarien
müssten mehrere vTypes mit Anteilen her (als Erweiterungsidee vorgemerkt).

---

## 11. actiTopp (Wochen-Aktivitätenpläne, Achse A)

**Definition:** actiTopp ist das KIT-Modell zur Generierung von
Wochen-Aktivitätenplänen je Person, kalibriert am Deutschen Mobilitätspanel (MOP);
es entscheidet selbst modellbasiert über Erwerbstätigkeit/Schulbesuch der Person.

**So hier benutzt:** `ActitoppRunner` ruft einen Kotlin-Runner im
actitoppNG-Projekt auf (Task `runSynthesis`); Austauschformat
`personId;activityType;startMinute;durationMinute` mit actiTopp-Typen
**WORK/EDUCATION/SHOPPING/LEISURE/TRANSPORT/HOME**, Zeiten in **Minuten der
Modellwoche** (Montag 00:00 = 0). `ActivityGenerator.generateFromActitopp` mappt auf
die Legacy-Codes (Sektion 3). Personen ohne Plan: Kleinkinder (age < 6, von actiTopp
nicht geplant) bekommen begleitete Wege (`youngChildDay`: Kita + Begleitwege), sonst
eine Heimwoche. Steuerung: `scenario.population.behaviorSource = "actitopp"`
(Default; Alternativen `gta` = LLM-Tagespläne mit actiTopp-Fallback, `template` =
eigener Generator `writeWeek`).

**Stolperfalle (docs/05, kritisch):** Weil actiTopp *selbst* entscheidet, wer
arbeitet, passt keine externe Heuristik dazu — **jede Person braucht ein festes
WORK- und EDUCATION-Ziel in `fixedDestination.csv`**, sonst bricht die Legacy-
Zielwahl mit „No destination available for activity type: WORK" ab. Das Fixziel
wird nur konsultiert, wenn die Aktivität tatsächlich auftritt (für Nicht-Arbeitende
harmlos).

---

## 12. Logistik-Begriffe (logiTopp)

**Definition:** CEP = Kurier-Express-Paket; ein CEP-Dienstleister (logiTopp-Kürzel
`cepsp`, CEP service provider) betreibt Depots/Verteilzentren (distribution
centers), von denen Zustelltouren starten. Eine Packstation ist ein Abhol-Automat
(Paket-Empfangsart neben Haustür/Arbeitsplatz).

**So hier benutzt:**

| Begriff | Repo-Konkretisierung |
|---|---|
| Depots | Studio-Karte → `Scenario.Logistics.depots` → `legacy/logistics/distribution-centers.csv` (Schema des logiTopp-`DistributionCenterParser`: `id;name;cepsp;vehicles;attempts;loc_x;loc_y;zone;vehicle_type;service_area;volume`, `LegacyExportGenerator`) |
| cepsp | Default-Marktanteile `Scenario.Logistics.defaultServices()` (DHL 0.45, Hermes 0.20, DPD 0.15, GLS 0.10, UPS 0.10) → `cepsp-shares.csv`, renormalisiert auf Dienste mit eigenem Depot |
| `vehicle_type` | Int-Codes des logiTopp-`VehicleType`: **OTHER=0, BIKE=1, TRUCK=2, TRAM=3** (Befund A10); Szenario-Ebene nutzt Strings `TRUCK`/`CARGO_BIKE` |
| Packstation | `LogisticsGenerator.writePackstations`: echte aus OSM (`amenity=parcel_locker`), Fallback synthetisch ~1 je 4000 Personen; Empfangsanteile `shareHome/shareWork/sharePackstation` (0.8/0.1/0.1) |
| Paketnachfrage | `parcelsPerPersonWeek = 0.75` (± `parcelStdDev`), Ergebnis in `parcel-orders-private.csv` / `parcel-states.csv` (State `DELIVERED`) |
| Touren | `planned-tours.csv`: eine Zeile je Stopp/Leg (`stopNo`, `vehicleTag`, `vehicleType`, `fromDepot`, `fromX/Y`, `toX/Y`, `distance` [m], `plannedTimeSimMin`, `tripDur` [Sim-Minuten der Woche]); neue Tour, wenn `stopNo` nicht mehr wächst oder das Fahrzeug wechselt (`SimulationResultIngestor.writeTours`) |

SUMO-Einspeisung der Touren: `SumoRunner.readDeliveryLegs` — `vehicleType` `1`
(BIKE) → vType `lastenrad`, `3` (TRAM) → **verworfen** (nicht auf dem Straßennetz),
alles andere → `lkw`. Abfahrt ≈ `plannedTimeSimMin − tripDur`.

**Stolperfallen:** (a) PACKSTATION-Empfang setzt **PICK_UP_PARCEL-fixedDestinations
für alle Personen** voraus — fehlen sie, crasht der Parcel-Build (Befund L1);
deshalb die Doppel-Datei `fixedDestination.csv` (kompatibel) +
`fixedDestination-logitopp.csv` (inkl. PICK_UP_PARCEL, `PopulationGenerator`).
(b) logiTopp protokolliert in der `cepsp`-Spalte von `vehicle_events.csv` den
*Depotnamen* — der echte Dienstleister wird aus `distribution-centers.csv`
nachgezogen (`SimulationResultIngestor.applyCepsp`). (c) 100 %-Population sprengt
den Heap in der Tourplanung — `samplingFraction` klein halten (Befund L5).

---

## 13. IPU / Populationssynthese

**Definition:** Iterative Proportional Updating (IPU) fittet ein Personen-/
Haushalts-Sample so um, dass es zonale Randsummen (Marginalien, z. B. Zensus-
Altersverteilungen) trifft — das KIT-Framework `populationsynthesis` implementiert
das, ist aber fest an deutsche amtliche Regionalschlüssel (ARS) + Zensus-
Regionaltabellen gebunden.

**So hier benutzt (bewusst nur als Sample-Quelle):** Eine frei gezeichnete
lat/lon-Box hat weder ARS noch Regionaltabelle → der generische „Seed-Pfad"
(docs/05): ein KIT-IPU-Lauf (Marne-Beispiel) wurde als
`backend/src/main/resources/kit-population-sample.csv` gebündelt — Header
**`householdId;age;sex`** (6.512 Haushalte / 13.168 Personen). `PopulationSample`
lädt den Pool, `PopulationGenerator` zieht bei `useKitPopulation=true` (Default)
**ganze Haushalte** je Zone bis zur Einwohnerzahl — realistische Alters-/
Geschlechts-/Haushaltsgrößen-Joint-Verteilung ohne reale zonale Marginalien.
Erwerbsstatus wird weiterhin altersbasiert selbst gezogen (das Sample enthält
keinen).

**Stolperfalle:** Das gebündelte Sample stammt aus dem ländlichen Marne (Alter
leicht erhöht). Ein besseres Sample ist drop-in-ersetzbar (gleiches Format, `sex`
als Int-Code); der „Deutschland-Pfad" (echter IPU-Lauf je Gemeinde mit
Zensus-Tabellen) ist konzipiert, aber nicht gebaut.

---

## 14. MobileCity `REGchargers`

**Definition im Zielmodell:** Das Feld heißt historisch
`numberOfChargingStations`, erwartet aber die gesamte Zahl öffentlicher **Ladepunkte**
je District und Jahr. Es zählt weder Standorte/Ladeeinrichtungen noch Steckertypen.

**So hier benutzt:** `MobileCityChargingSource` summiert die BNetzA-Spalte
`Anzahl Ladepunkte` räumlich über die District-Geometrien. Vorhandene Jahreszellen
überschreiben im nativen `CAL5020_TECCarFleet` den Bestand. Leere Folgejahre sind
semantisch relevant: MobileCity entwickelt den Bestand dann aus E-Pkw-Bestand,
Sättigung und Vorjahr endogen weiter. Deshalb unbekannte oder zukünftige Werte nie
still mit null füllen.

---

## 15. MobileCity `TECCarsharing`

`carsharing.member` ist eine gebietsweite absolute Zahl der Fahrberechtigten pro Jahr.
`carsharing.stock.total` ist die Zahl stationsbasierter und stationsunabhängiger
Carsharing-Fahrzeuge je District und Jahr. Im nativen `CAL4010_MODTrips` gehen daraus der
Mitgliederanteil an der Gesamtbevölkerung und die Fahrzeugdichte je 1.000 Einwohner des
Ursprungs-Districts in die Moduswahl ein.

SyntheSis modelliert die beiden Größen mit `carsharingMemberShare` und
`carsharingVehiclesPer1000`. Der Regionalexport rechnet sie in absolute Werte um und hält
sie über den gesamten Exporthorizont konstant. Nicht gesetzte Felder ergeben die explizit
ausgewiesene Nullannahme; Werte anderer Referenzregionen werden nicht übernommen.

## 16. MobileCity-Umland (`Outer`, `POPtripsSurrounding`)

`districtType=0` bedeutet `Outer`; Typen ab `1` gehören zum Untersuchungsgebiet.
`tripShareToStudyArea[(TripPurpose, outerDistrictId)]` ist der Anteil der Wege der
Umlandbewohner, der in das Untersuchungsgebiet führt. Im nativen `CAL4010_MODTrips` wird
dieser Anteil mit den alters-/zweckspezifischen Tageswegen des Outer-Districts multipliziert
und anschließend nach Bevölkerung mal Attraktivität auf Binnenziele verteilt. Das Feld ist
kein Anteil ausgehender Wege der Binnenbevölkerung.

SyntheSis aktiviert Outer-Districts manuell bei positiver `surroundingPopulation` oder
automatisch mit `autoSurrounding` und `surroundingBufferKm`; vier vollständige Zweckanteile
sind immer Pflicht. Automatik bedeutet: metrischer EPSG:3035-Ring, acht Richtungssektoren,
Zensus-2022-Bevölkerung und Altersstruktur. Ein manueller Gesamtwert skaliert dann die
beobachteten Sektorgewichte. Die bisherigen acht externen Zonen sind sonst ausschließlich
Zielanker ohne Einwohner. Pkw-Dichte und BEV-Anteil des Umlands bleiben extrapolierte
Annahmen aus dem Untersuchungsgebiet; BA-Pendlerdaten können später nur den Arbeitszweck
belegen.

**Zeitliche Grenze:** Die aus dem Inbetriebnahmedatum rekonstruierte Historie umfasst
nur Einrichtungen, die im aktuellen BNetzA-Snapshot noch geführt werden, und ist damit
eine Untergrenze. Private Wallboxen sind nicht enthalten.

---

## Wann diesen Skill NICHT nutzen

- **System-Architektur / Modul-Schnitt / WARUM-Entscheidungen** → Skill
  `synthesis-architecture-contract`.
- **Die Distanz-/Zielwahl-Kampagne konkret durchführen** → Skill
  `synthesis-distanz-kampagne` (dieser Skill liefert nur die MNL-/Zielwahl-Grundlage).
- **Build, Umgebung, SUMO-/venv-Setup, Läufe starten/debuggen** → Skills
  `synthesis-build-and-env`, `synthesis-run-and-operate`,
  `synthesis-debugging-playbook`.
- **Config-Keys und Feature-Flags vollständig nachschlagen** → Skill
  `synthesis-config-and-flags` (hier stehen nur die fachlich relevanten Defaults).
- **Validierungs-/QA-Prozeduren** → Skill `synthesis-validation-and-qa`; die
  Historie der Format-Befunde selbst steht in `docs/05-validierung.md`.
- **Allgemeine Verkehrsplanungs-Theorie** außerhalb dieses Repos (Vier-Stufen-Modell,
  MATSim, Visum-Bedienung): hier bewusst nicht abgedeckt — jede Aussage in diesem
  Skill ist an SyntheSis-Code gebunden.

## Provenienz & Wartung

- **Stand: 2026-07-08.** Alle Codes, Spaltenindizes, Dateinamen und Defaults wurden
  gegen den damaligen `main`-Stand verifiziert (u. a. `SimulationResultIngestor`,
  `SumoRunner`, `ActivityGenerator`, `MatrixGenerator`, `VisumMatrixWriter`,
  `ModeChoiceCalibrator`, `FeedbackMath`, `GtfsCropper`, `TransitRunner`,
  `MobileCityExporter`, `Scenario`, `SynthesisProperties`,
  `frontend/src/app/features/studio/studio.component.ts`) und gegen echte Läufe
  (`docs/05-validierung.md`; Spaltenindizes zusätzlich gegen den Header eines realen
  `demandsimulationResult.csv` aus `validation/mobitopp-rastatt/output/`).
- **Volatil, bei Änderung nachziehen:** die Spaltenindizes in
  `SumoRunner.read*Trips` (brechen, wenn logiTopp sein Ergebnisformat ändert); die
  RegioStaR-Presets (Näherungswerte, Stand 2026-07); `synthesis.transit.modes: bus`
  (fällt, sobald der Rail-Blocker gelöst ist — Lösungsweg in docs/06); die
  HBEFA-Klassenwahl (Euro-4-Pauschale); `kit-population-sample.csv`
  (Marne-Sample, austauschbar).
- **Nicht im Repo versioniert:** alles unter `validation/` (gitignored) —
  insbesondere das MNL-Parameterfile
  `mode_choice_main_parameters-multi-nomial-logit.txt`; ohne lokales Legacy-Projekt
  sind Kalibrierung/Simulation SKIPPED, dieser Skill bleibt trotzdem gültig.
- Bei Widerspruch zwischen diesem Skill und dem Code gilt der Code; diesen Skill
  dann im selben Change aktualisieren (Prozess: Skill `synthesis-change-control`).
