---
name: synthesis-config-and-flags
description: >
  Vollständiger Katalog aller Konfigurationsachsen des SyntheSis-Repos: Szenario-Flags
  (Scenario.java, z. B. behaviorSource, targetModels, sumoFeedback,
  calibrateModeChoice, llmModeChoice, useRealRouting, samplingFraction sowie das Legacy-Feld useOsmData) und
  Betriebs-Properties (SynthesisProperties, Prefix synthesis.: census, overpass, simulation, actitopp,
  sumo, transit inkl. GTFS-Feed-Registry, MobileCity-Validierung, llm, auth, frontend) samt Defaults, Konsumenten und Gates. Laden, WENN
  du wissen musst (a) welches Flag/Property Verhalten X steuert, (b) was der Default von Y ist
  (inkl. berechneter Defaults aus $SUMO_HOME), (c) ob ein Feature produktiv oder experimentell
  ist, (d) wie man ein neues Szenario-Flag oder eine neue Betriebs-Property anlegt (Checkliste
  Scenario.java → scenario.model.ts → studio.component.html → GenerationService-Gate), (e) welche
  Env-Variablen (SUMO_HOME, SYNTHESIS_TRANSIT_VENV) was bewirken, oder (f) warum eine
  Pipeline-Stufe SKIPPED ist. Stichwörter: Flag, Property, Default, application.yml,
  Konfiguration, Szenario-Parameter, Feature-Toggle, Zielmodell, Verhaltensquelle, GTFS-Feed,
  venv-python, Gate, experimentell.
---

# SyntheSis – Konfigurationsachsen & Flags

Dieses Dokument ist der **Ground-Truth-Katalog** aller Konfiguration im SyntheSis-Repo
(Angular-Studio `frontend/` + Spring-Boot-Backend `backend/`, 15-stufige Pipeline in
`backend/src/main/java/de/synthesis/generation/GenerationService.java`). Es gibt **drei Ebenen**:

1. **Szenario-Flags** – pro Szenario, vom Nutzer im Studio gesetzt, als JSON persistiert
   (`backend/src/main/java/de/synthesis/scenario/Scenario.java`).
2. **Betriebs-Properties** – pro Deployment, Prefix `synthesis.` in
   `backend/src/main/resources/application.yml`
   (`backend/src/main/java/de/synthesis/SynthesisProperties.java`).
3. **Env-Variablen** – Maschinen-Setup (`SUMO_HOME`, `SYNTHESIS_TRANSIT_VENV`).

**Merkregel:** Feature-Gates kombinieren je nach Funktion Szenario-Flag, Betriebs-Property und
Verfügbarkeit (Binary/venv/LLM erreichbar). OSM-POI-/Siedlungsdaten sind die bewusste Ausnahme:
Sie werden automatisch als Snapshot → Live-Overpass → synthetischer Fallback gewählt und haben
keinen Szenario-Schalter mehr.

## Wann diesen Skill NICHT nutzen

- **Commit-/Merge-/Gate-Fragen** („darf ich das committen?", Beförderung eines Flags von
  experimentell zu produktiv) → `synthesis-change-control`.
- **Start/Betrieb/Ports/Läufe** („wie starte ich Backend + Frontend?") → `synthesis-run-and-operate`.
- **Build-/Toolchain-Installation** (SUMO installieren, venv bauen) → `synthesis-build-and-env`.
- **Fehlersuche in einer laufenden Pipeline** (Stufe rot, Stacktrace) → `synthesis-debugging-playbook`.
- **Fachliche Fragen** zu Modal-Split, MiD, actiTopp-Semantik → `verkehrsmodellierung-reference`.
- **Architektur-Invarianten** (wer darf wen aufrufen) → `synthesis-architecture-contract`.

---

## 1. Szenario-Flags (`Scenario.java`)

Quelle: `backend/src/main/java/de/synthesis/scenario/Scenario.java`. Public Fields; die
**Feld-Initialisierer sind zugleich die Defaults**, die das Frontend anbietet. Spiegel im
Frontend: `frontend/src/app/core/models/scenario.model.ts`. UI:
`frontend/src/app/features/studio/studio.component.html` (Blocknamen unten; Zeilennummern
driften, daher nur Block + Bedienelement).

### 1.1 Top-Level

| Feld | Typ | Default | Wirkung / Konsument | Status |
|---|---|---|---|---|
| `dataPlan` | SourcePlan | `enabled=false`, keine Policies | Experimenteller Quellenplan: `population.distribution`, `opportunities.work`, `freight.vehicleOd`; `SourceResolver` vor Queueing und Studio-Vorprüfung; unveränderliche Revisionen explizit auswählen, Workplace-Lückenregel `legacy`/`none`, schwere Fahrzeugmengen als explizite Annahme. UI „Daten & Quellen öffnen“. Arbeitsplatzpunktdaten erfordern vollständigen Erhebungsumfang. `source=profile` bindet einen unveränderlichen Quellenprofilhash: geordnete Revisions-/Paketkandidaten, explizite Abwahl und Ausfallregel; lokale ZIP-Paketinstallation aktiviert keine Quelle. Übrige Bestandsketten und allgemeiner Abhängigkeitsgraph offen (2026-10-28). | experimentell, aus |
| `id` | String | – (vom Backend vergeben) | Persistenz-Schlüssel (`ScenarioRepository`) | produktiv |
| `name` | String | `"Neues Szenario"` | Anzeigename; Studio-Block „Szenario" | produktiv |
| `createdAt` | Instant | – | Metadatum | produktiv |
| `region` | Region (`minLat/minLon/maxLat/maxLon`) | `null` bis Karte gezeichnet | Bounding-Box aller Stufen (Zonierung, OSM, Routing, GTFS-Feed-Auswahl) | produktiv |
| `zoning.cellSizeMeters` | double | `100` | Tatsächlich verwendete Kantenlänge quadratischer Rasterzellen (m). Bei neuer/neu gezeichneter Region fragt das Studio mit 100 m an und übernimmt die feinste Auflösung innerhalb von `zoning.maxZones`: `grid` in 50-m-, `epsg3035` in 100-m-, `inspire` in amtlichen Standardstufen. | produktiv |
| `zoning.maxZones` | int | `600` (über `/defaults` aus `synthesis.max-zones`) | Szenariospezifische Obergrenze für Vorschau und Pipeline. Gültig 100 bis `synthesis.max-zones-hard-limit` (1.200), da OD-Matrizen quadratisch wachsen. | produktiv |
| `zoning.mode` | String | `"grid"` (auch über `/defaults`) | `grid` = freies metrisches Bbox-Raster (global) \| `epsg3035` = flächengleiches, europaweit fest ausgerichtetes LAEA-Gitter in flexiblen 100-m-Schritten, nicht amtlich; stabile Namen `LAEA3035RES…` \| `inspire` = amtliches INSPIRE-/Zensus-Gitter mit 100 m/1 km/10 km und Zell-ID `CRS3035RES…`. Beide LAEA-Modi verwenden `Zone.laeaE/laeaN/laeaRes`; außerhalb Europas Fallback auf `grid`. Job-Summary: `zoningMode`. | produktiv |
| `targetModels` | List\<String\> | `["logitopp", "mobilecity"]` | Nachfragesimulation/Weiterverarbeitung: `logitopp` \| unabhängiger Export `mobilecity` \| nachgelagerte Verkehrssimulation `sumo`. SUMO impliziert logiTopp; Studio und API normalisieren die Abhängigkeit. | produktiv (`sumo` nicht im Default) |

**`targets()`-Helper (wichtig!):** Leere oder `null` Liste fällt auf `["logitopp", "mobilecity"]`
zurück (Rückwärtskompatibilität alter persistierter Szenarien). **Gates immer über
`scenario.targets()` prüfen, nie über das rohe Feld `targetModels`.** `targets()` ergänzt bei
SUMO automatisch logiTopp; `normalizeTargetDependencies()` persistiert diese Abhängigkeit. Gate-Stellen in
`GenerationService`: `!targets().contains("mobilecity")` → Stufe „MobileCity-Export" SKIPPED;
`!targets().contains("logitopp")` → „Simulation (logiTopp)" SKIPPED;
`!targets().contains("sumo")` → „SUMO (Verkehr)" SKIPPED.

### 1.2a `MobileCity`

| Feld | Typ | Default | Wirkung / Konsument | Status |
|---|---|---|---|---|
| `useTemplate` | boolean | `false` | Ergänzt ein lokal hashgeprüftes vollständiges Vorlagenpaket | experimentell |
| `templateId` | String | `"tramigo-v2"` | ID des Vorlagen- bzw. Modellprofils unter `synthesis.data-dir/mobilecity/` | experimentell |
| `useRegionalFleet` | boolean | `false` | Ordnet gepinnte private KBA-Bestände über BKG-Gemeindegrenzen zu | experimentell |
| `useRegionalCharging` | boolean | `false` | Ordnet die öffentlichen Ladepunkte des gepinnten BNetzA-Registers anhand ihrer Koordinaten den erzeugten Districts zu; nur Regionalexport | experimentell |
| `chargingPointsPer1000` | Double | `null` | Explizite Ladepunktannahme je 1.000 Einwohner; ohne Automatik manuell, mit aktivierter Automatik nur Quellenfallback. `null` = unbekannt, `0` = gültige Nullannahme | experimentell |
| `carsharingVehiclesPer1000` | Double | `null` | Explizite Carsharing-Flotte je 1.000 Einwohner; wird nach District-Bevölkerung verteilt und über alle Exportjahre konstant gehalten. `null` wirkt als ausgewiesene Nullannahme | experimentell |
| `carsharingMemberShare` | Double | `null` | Gebietsweiter Anteil der Carsharing-Fahrberechtigten in `[0,0.4]`; wird in eine absolute Mitgliederzahl umgerechnet und konstant gehalten. `null` wirkt als ausgewiesene Nullannahme | experimentell |
| `autoSurrounding` | boolean | `false` | Bildet acht Outer-Sektoren aus einem metrischen Puffer und aggregiert Zensus-2022-Bevölkerung/-Altersstruktur; benötigt `population.externalTrips=true` und alle vier Umlandanteile | experimentell |
| `surroundingBufferKm` | double | `10.0` | Pufferbreite des automatischen Umlands in km; bei aktivierter Automatik `[1,100]` | experimentell |
| `surroundingPopulation` | Integer | `null` | Ohne Automatik: manuelle Gesamtbevölkerung. Mit Automatik: optionaler positiver Override, der die beobachteten Sektorgewichte exakt skaliert | experimentell |
| `surroundingEducationTripShare` | Double | `null` | Anteil der Bildungswege der Umlandbewohner mit Ziel im Untersuchungsgebiet `[0,1]` | experimentell |
| `surroundingWorkTripShare` | Double | `null` | Anteil der Arbeitswege der Umlandbewohner mit Ziel im Untersuchungsgebiet `[0,1]` | experimentell |
| `surroundingShoppingTripShare` | Double | `null` | Anteil der Einkaufswege der Umlandbewohner mit Ziel im Untersuchungsgebiet `[0,1]` | experimentell |
| `surroundingLeisureTripShare` | Double | `null` | Anteil der Freizeitwege der Umlandbewohner mit Ziel im Untersuchungsgebiet `[0,1]` | experimentell |
| `regionalExport` | boolean | `false` | Erzeugt ein vollständiges gebietseigenes Basispaket mit unkalibriertem Profil | experimentell |
| `validateNative` | boolean | `false` | Startet nach einem vollständigen Export Stufe „MobileCity-Validierung"; benötigt Ziel `mobilecity` und `regionalExport` oder `useTemplate` | experimentell |
| `electricShare` | Double | `null` | Expliziter BEV-Anteil bzw. Lücken-Fallback; `null` = unbekannt, `0` = gültige Nullannahme | experimentell |
| `maxDistricts` | int | `100` | Maximale Aggregation auf 1–100 MobileCity-Districts im Untersuchungsgebiet; optionale Outer-Districts kommen zusätzlich hinzu | experimentell |
| `allowSyntheticTransitFallback` | boolean | `true` | Erlaubt bei fehlendem GTFS ausdrücklich das synthetische Transitnetz; `false` lässt den Regionalexport ohne Feed fehlschlagen | experimentell |
| `transitVehiclesPer1000` | double | `1.0` | Flottenannahme je 1.000 Einwohner ausschließlich für den freigegebenen synthetischen ÖV-Fallback | experimentell |
| `settlementAreaShare` | double | `0.5` | Modellierter Siedlungsflächenanteil `(0,1]` | experimentell |

`useTemplate` und `regionalExport` schließen sich gegenseitig aus. `validateNative` ist ein
harter Prüflauf, wenn er startet; fehlende lokale Werkzeuge ergeben dagegen `SKIPPED` mit
`mobilecity-validation/status.json`.

Bei `useRegionalCharging=true` schreibt der Regionalexport kumulative BNetzA-Bestände bis
zum letzten beobachtbaren Jahr in `REGchargers`. Spätere Zellen bleiben leer und aktivieren
den endogenen MobileCity-Ausbau. Die Einheit ist die Summe öffentlicher Ladepunkte, nicht
Standorte oder Stecker. Automatische Quellen sind derzeit auf Deutschland beschränkt.

Carsharing bleibt eine explizite Modellannahme. Sobald mindestens eines der beiden
Carsharing-Felder gesetzt ist, weist das Dateninventar die Auflösung als `manual` aus. Ein
nicht gesetztes Gegenfeld wird als null angenommen. Die Obergrenze 0,4 des
Mitgliederanteils entspricht der harten Begrenzung im nativen MobileCity-Rechenkern.

Umland ist nur als vollständiger Vertrag aktiv. Entweder aktiviert eine positive
`surroundingPopulation` den manuellen Pfad, oder `autoSurrounding=true` erzeugt aus
`surroundingBufferKm` acht Zensus-basierte Richtungssektoren. Ein gleichzeitig gesetzter
positiver Bevölkerungswert überschreibt nur die automatische Gesamtsumme. In beiden Fällen
sind alle vier Anteile Pflicht; sie füllen
`POPtripsSurrounding.tripShareToStudyArea` und beschreiben Umland → Untersuchungsgebiet.
Im Automatikpfad stammt die Altersstruktur aus Zensus 2022, soweit das Raster verwertbare
Gruppen liefert; Pkw- und BEV-Struktur bleiben sichtbare Extrapolationen aus dem
Untersuchungsgebiet. Ohne positive manuelle Bevölkerung und ohne Automatik bleiben externe
Zonen reine Zielanker und werden nicht als MobileCity-Districts exportiert.

### 1.2 `Population`

UI-Ort, wenn nicht anders vermerkt: Studio-Block „Bevölkerung"; die Modellierungs-Flags
(behaviorSource, Kalibrierung, LLM, Feedback, ÖV) im Block „Modellierung".

| Feld | Typ | Default | Wirkung / Konsument | Status |
|---|---|---|---|---|
| `totalPersons` | Integer | `null` | Optionale manuelle Einwohnerzahl. `null` aktiviert: alternatives Upload-Raster → Destatis Zensus 2022 → OSM-Siedlungsschätzung → `personsPerKm2`. Der UI-Quellenhinweis schreibt bewusst nicht in dieses Feld zurück, damit die Manifest-Provenienz korrekt bleibt. | produktiv |
| `personsPerKm2` | double | `1500` | Fallback-Dichte, wenn `totalPersons` leer und OSM nichts liefert | produktiv |
| `samplingFraction` | double | `1.0` | Anteil synthetisierter Bevölkerung (mobiTopp `fractionOfPopulation`). API/Persistenz bleiben `(0, 1]`; das Studio zeigt 5–100 % als Regler und der Vergleich als Prozentwert. Konsumenten: `PopulationGenerator` (Zonen-Sampling), `SimulationRunner` (an logiTopp durchgereicht), `ComparisonController` (Vergleichs-Metadatum) | produktiv |
| `seed` | long | `42` | Zufalls-Seed – gleicher Seed + gleiche Parameter = identisches Ergebnis. Konsumenten: Pipeline-`random`, `GtaBehaviorRunner`/`LlmModeChoiceRunner` (baseSeed) | produktiv |
| `useOsmData` | boolean | `true` | **@Deprecated, zur JSON-Kompatibilität behalten und zur Laufzeit ignoriert.** Die Quelle wird automatisch als Szenario-Snapshot → Live-Overpass → synthetischer Fallback gewählt; im Studio gibt es dafür keine Checkbox mehr. | Legacy |
| `useRealRouting` | boolean | `true` | Echte Reisezeit-/Distanzmatrizen via GraphHopper (OSM-Straßennetz) in Stufe „Matrizen"; bei `false`/Fehlschlag Luftlinie. Konsument: `GenerationService` → `RoutingEngine`/`MatrixGenerator` | produktiv |
| `roadLocatedActivities` | boolean | **`false`** | Synthetische interne Wohn-/Fix-/Aktivitätsorte auf gemeinsame öffentliche Pkw-/Rad-/Fuß-Straßen innerhalb derselben Zone legen; kein zusätzlicher Zufallszug. Benötigt erfolgreiches reales Routing, sonst Fehler statt Ortsfallback. Externe Anker und Depotkoordinaten bleiben unverändert. Report `population/road-locations.json`; UI „Synthetische Wohn- und Aktivitätsorte an Straßen platzieren“. | **experimentell, 2026-10-19** |
| `externalTrips` | boolean | `true` | Externe Zielzonen um das Gebiet, damit ein Teil der Wege es verlässt (realistische Weglängen). Konsument: `GenerationService` → `ExternalZones` | produktiv |
| `behaviorSource` | String | `"actitopp"` | **Achse A**: `actitopp` (KIT, kalibriert) \| `gta` (LLM-Agenten, **experimentell**, Fallback actiTopp) \| `template` (einfacher Generator; **nicht** im UI-Select, nur Legacy-Fallback). Konsument: Stufe „Aktivitätenpläne" via `behaviorSourceEffective()` → `GtaBehaviorRunner`/`ActitoppRunner`/`ActivityGenerator`. UI: Select „Verhaltensmodell" | `actitopp` produktiv; `gta` **experimentell** |
| `gtaWhatIf` | String | `""` | GTA 3d: Freitext-Was-wäre-wenn-Kontext (Policy/Technologie) für die LLM-Tagesplanung; leer = normales Verhalten. UI: Textarea, nur sichtbar bei `behaviorSource === 'gta'` | experimentell |
| `useActitopp` | boolean | `true` | **@Deprecated** – durch `behaviorSource` ersetzt; nur noch in `behaviorSourceEffective()` gelesen. UI: Checkbox „KIT actiTopp für Aktivitätenpläne" (Block „Bevölkerung") existiert noch | Legacy |
| `useKitPopulation` | boolean | `true` | Haushalte aus KIT-synthetisiertem Demografie-Sample (realistische Zusammensetzungen) statt Ad-hoc-Ziehung. Konsument: `PopulationGenerator` | produktiv |
| `calibrateModeChoice` | boolean | **`false`** | Iterative ASC-Kalibrierung der Moduswahl gegen Ziel-Modal-Split (mehrere Sim-Läufe, teuer). Konsument: `GenerationService` (Stufe „Simulation (logiTopp)") → `ModeChoiceCalibrator`. UI: Checkbox „Verkehrsmittelanteile kalibrieren (langsam)", gated `*ngIf="hasTarget('logitopp')"` | **experimentell** |
| `calibrateDistance` | boolean | **`false`** | Iterative Zielwahl-Kalibrierung von `b_intrazonal` auf den MiD-Nahanteil `<1 km`; benötigt mehrere logiTopp-Läufe und eine intrazonale Pkw-Distanz unter 1 km. Konsument: `GenerationService` → `DistanceCalibrator`. UI: „Kurze Wege kalibrieren (experimentell, langsam)" | **experimentell** |
| `correctDistanceDecay` | boolean | **`false`** | Erzeugt einen szenariogebundenen Zielwahl-Parameterstand und addiert den über Seeds 42/43/44 bestätigten festen Term `+0,06` auf `b_tt_car_d`, `b_tt_car_p`, `b_tt_put`, `b_tt_ped`, `b_tt_bike`. Komponiert mit `calibrateDistance`, Modus-Kalibrierungen und Netz-Feedback; verändert nie die geteilten Legacy-Dateien. Konsument: `GenerationService` → `DestinationChoiceParameterAdjuster`. UI: „Längere Wege begünstigen (experimentell)" | **experimentell** |
| `calibrateModeDistance` | boolean | **`false`** | Gemeinsame Kalibrierung von ÖV-Niveau (`asc_put`) und ÖV-Fernweg-Schräge (`b_tt_put`) gegen MiD; mehrere logiTopp-Läufe. Konsument: `GenerationService` → `ModeDistanceCalibrator`. UI: „ÖV nach Distanz kalibrieren" | **experimentell** |
| `calibrationTarget` | Map\<String, Double\> | MiD 2017 bundesweit: `pedestrian 0.22, bike 0.11, car 0.43, passenger 0.14, publicTransport 0.10` | Ziel-Modal-Split der Kalibrierung; leer/null → Fallback `ModeChoiceCalibrator.MID_2017`. Auch von der LLM-Moduswahl als Referenz gelesen. UI: editierbare Zeilen + RegioStaR-Preset-Select, nur sichtbar wenn `calibrateModeChoice` an | Datum produktiv; nur von experimentellen Flags genutzt |
| `llmModeChoice` | boolean | **`false`** | GTA 3c: zusätzliche LLM-gestützte Moduswahl auf den simulierten Trips (geerdet in Reisezeiten je Modus) – alternativer Modal-Split zum Vergleich mit MNL/MiD. Benötigt logiTopp + LLM. Konsument: Stufe „LLM-Moduswahl" → `LlmModeChoiceRunner`. UI: Checkbox, gated `hasTarget('logitopp')` | **experimentell** |
| `sumoFeedback` | boolean | **`false`** | Netz-Feedback: staubelastete SUMO-Reisezeiten fließen MSA-gedämpft in die Pkw-Matrix zurück, logiTopp entscheidet neu (Gleichgewichts-Schleife). Benötigt logiTopp + SUMO. Konsument: Stufe „Netz-Feedback" → `FeedbackMath`. UI: Checkbox, gated `hasTarget('logitopp') && hasTarget('sumo')` | **experimentell** |
| `publicTransport` | boolean | **`false`** | **@Deprecated, zur JSON-Kompatibilität behalten und ignoriert.** Bei SUMO wird ÖV automatisch versucht, wenn Registry-Feed und Transit-Werkzeuge verfügbar sind; keine UI-Checkbox mehr. | Legacy |
| `gtfsUploadPath` | String | – (unset) | Pfad eines bewusst ausgewählten GTFS-Feeds; hat Vorrang vor dem Auto-Fetch. Gesetzt vom Upload-Endpoint in `ScenarioController`. UI: „GTFS-ZIP hochladen"-Link im automatischen ÖV-Hinweis | experimentell |
| `carsPerHousehold` | double | `1.0` | Mittlere Pkw-Ausstattung der Haushalte; `PopulationGenerator.drawCarCount(...)` (stochastische Rundung) | produktiv |

**`behaviorSourceEffective()`-Fallback-Logik (wichtig!):** Explizites `behaviorSource` gewinnt,
wenn nicht null/blank; sonst wird aus dem Legacy-Flag abgeleitet: `useActitopp == true` →
`"actitopp"`, sonst `"template"`. **Konsumenten müssen immer `behaviorSourceEffective()`
aufrufen, nie das rohe Feld lesen.** Zusätzlich fällt die Pipeline zur Laufzeit von `gta` auf
`actitopp` zurück, wenn `synthesis.llm.enabled == false`, das LLM nicht erreichbar ist oder der
GTA-Lauf eine Exception wirft (Log: „… Fallback actiTopp").

### 1.3 `Logistics` (Studio-Block „Logistik (logiTopp)")

| Feld | Typ | Default | Wirkung / Konsument | Status |
|---|---|---|---|---|
| `parcelsPerPersonWeek` | double | `0.75` | Mittelwert Paketbestellungen je Person/Woche (logiTopp `NumberOfParcelsSelector`); `LogisticsGenerator` | produktiv |
| `parcelStdDev` | double | `0.5` | Standardabweichung dazu | produktiv |
| `maxParcelsPerPerson` | int | `10` | Obergrenze je Person | produktiv |
| `shareHome` / `shareWork` / `sharePackstation` | double | `0.8` / `0.1` / `0.1` | Zustellort-Anteile | produktiv |
| `depots` | List\<Depot\> | leer | Verteilzentren (auf der Karte platziert) | produktiv |
| `services` | List\<DeliveryService\> | DHL 0.45, Hermes 0.20, DPD 0.15, GLS 0.10, UPS 0.10 | KEP-Dienstleister-Marktanteile | produktiv |

`Depot`-Felder: `name` (`"Depot"`), `lat`, `lon`, `employees` (`20`), `share` (`1.0`),
`vehicleType` (`"TRUCK"` \| `"CARGO_BIKE"` – als Union-Typ auch im Frontend-Modell).
`DeliveryService`-Felder: `name`, `share`.

### 1.4 Experimentell vs. produktiv – Kurzliste (Stand 2026-08-17)

**Experimentell (alle default `false` bzw. nicht die Default-Option; Beförderung nur über das
Gate in `synthesis-change-control`):** `calibrateModeChoice`, `calibrateDistance`,
`correctDistanceDecay`, `calibrateModeDistance`, `llmModeChoice`, `sumoFeedback`,
`behaviorSource = "gta"`, `mobileCity.useTemplate`, `mobileCity.regionalExport`,
`mobileCity.validateNative`, `mobileCity.useRegionalFleet`
(+ Anhängsel `gtaWhatIf`, `gtfsUploadPath`).
Ziel `sumo` in `targetModels` ist funktionsfähig, aber **nicht** im Default enthalten.

**Produktiv-Default (an, ohne Zutun):** automatische OSM-Datenquellenwahl,
`useRealRouting`, `externalTrips`, `useKitPopulation`, `behaviorSource = "actitopp"`,
Targets `logitopp` + `mobilecity`.

---

### 1.5 Schwerer Güterverkehr (`Scenario.freight`, Stand 2026-10-15)

`enabled=false` aktiviert explizite Tages-OD nur mit SUMO und vollständiger Personen-
nachfrage. `day=0` bezeichnet Montag und muss zum SUMO-Betriebstag passen.
`sevasEnabled=false` verwendet einen vom Nutzer hochgeladenen Snapshot.
`profileChecksEnabled=false` ergänzt statische Standard-LKW-Profile (26/40 t Volllast,
4 m Höhe, 2,55 m Breite) plus Quellregel-/Abbiege-Audit in der bestehenden SUMO-Stufe.
Eigene schwere SUMO-Typzuordnungen sind damit vorerst nicht kombinierbar.
Ungeklärte Bedingungen/Zweckregeln und Achslasten bleiben ungeklärt; nicht routbare
Fahrten oder nachgewiesene Verstöße liefern FAILED mit erhaltenem
`freight/route-checks.json`. Deaktivierung entfernt alte Prüfartefakte.
Frontend: Checkbox im Güterverkehrsblock; beim Abwählen von Güterverkehr/SUMO wird
auch die Profilprüfung deaktiviert. Details: docs/21, Abschnitt 20.

---

### 1.6 Quellenplan: Gemeindeaggregate und Laufzweck (2026-10-29)

`SourcePlan` bleibt standardmäßig deaktiviert. `qualityMode` ist `exploration` oder
`analysis` (Default Exploration). Die bekannte technische Lkw-Mengendemonstration
ist ausschließlich bei Exploration, `Policy.demonstrationAccepted=true` (Default
false) und 10–1.000 Zeichen `Policy.assumptionStatement` erlaubt. Alte gespeicherte
Demonstrationen erhalten keine automatische Freigabe. Die Moduswahl selbst
zertifiziert keine empirische Qualität anderer Quellen/Modelle. UI: Daten & Quellen,
Beiträge → Verwendungszweck und bei Lkw-Demonstration Freigabe/Beschreibung.

Zusätzliche Importverträge: `municipal-workplaces-v1` für `opportunities.work` und
`municipality-allocation-v1` als unterstützende Revision. Metadaten `geography`:
`codeSystem=de:AGS`, `edition` als Datum, `allocationMethod=explicit-point-weights-v1`,
beim Aggregat `allocationRevision` als Hash. Die unterstützende Revision aktiviert
keinen Nachfrageaspekt. Referenzgraph mit erwarteten Schemas, Zyklus-/Tiefenprüfung
und gefrorenen Revisionen im Eingabelock; keine allgemeine Workflowplanung. Für Aggregate ist zusätzlich
`allocationMethod=uniform-area-v1` mit Referenzvertrag `municipality-areas-v1`
verfügbar: WGS84-GeoJSON, Flächenverschneidung in EPSG:3035, 1-cm-Abdeckungstoleranz,
ungepufferte Mengen, Löcher/MultiPolygone und getrennte Teilzonen-/Außenbilanz.
Der Methodenwechsel im Importeditor löscht die alte Referenzauswahl. Die
Punktgewichtsvariante bleibt eigenständig.

**Ergänzung 2026-10-29 – gemischte Profile und Registerlücken:**
Quellenprofile für Bevölkerung und Work unterstützen `builtin:<ID>` zusätzlich zu
`revision:<Hash>`/`pack:<ZIP-Hash>:<Quellen-ID>`. Native Profile verlangen `fallback=none`;
gemischte Work-Profile verlangen `gaps=none|providers`. Potenziell benötigte Revisionen
und Referenzen werden vor Queueing unter `sources/lock.json.chains` gebunden (`staged`),
Auswahl folgt im Lauf. Defekte potenzielle Dateien sind fatal; ausgeschlossene und
hinter einer geeigneten Importrevision liegende Kandidaten werden nicht eingelesen.
`Policy.gaps=providers` und `gapProviders` (geordnete native Work-Kandidaten) ergänzen
nur nicht vollständig übernommene Zonen. Bestätigte Nullwerte bleiben erhalten.
Punktregister, Gemeindegewichte und Flächenverteilung nutzen dieselbe atomare Komposition.
Work-Bericht: `gapResolution`, `gapPolicy`, getrennte `gap/…`-Herkunft; keine Summe aus
Registermengen und heuristischen Gewichten. Keine Lücken → kein nativer Ersatzaufruf.
Default bleibt ausgeschalteter Gesamtplan und der bestehende Legacy-Vertrag.
Ausführbares Paket und genaue Grenzen: `docs/examples/mixed-sources/README.md`.

**Ergänzung 2026-10-29 – regionale Mehrregisterkomposition:**
`SourceProfiles.Chain.strategy="fill-gaps"` ergänzt importierte Work-Register räumlich
nach Vorrang. Fehlend/null bedeutet weiterhin Auswahl der ersten verwendbaren Quelle;
null wird nicht serialisiert, alte Profilhashes bleiben erhalten. Regionale Profile
verlangen `fallback=none`, mindestens einen aktiven Registerkandidaten und dürfen
keine nativen Kandidaten enthalten; native Restlücken bleiben `Policy.gapProviders`.
Vorprüfung bindet alle potenziellen Registerrevisionen/Referenzen. Im Lauf übernimmt
jede Zone das erste vollständig abdeckende Register, einschließlich Nullwerten.
`RegionalWorkplaces` bilanziert angewendete, verdeckte, Außen- und Teilmengen pro
Revision; tatsächliche Scopes liegen unter `register-<Rang>/…`, die Diagnose vor
Vorrang unter `allocationBeforePriority`. Keine gemeinsame beobachtete Gesamtzahl.
Restlücken werden explizit ergänzt oder führen atomar zum Fehler. Andere Zwecke
und der Pipeline-RNG bleiben unverändert. UI: Profileditor → Zusammenführung →
Register regional ergänzen. Beispiel: `docs/examples/regional-registers/README.md`.

**Ergänzung 2026-10-30 – Rasterzellen und Masken:**
`population-cells-v1`/`workplace-cells-v1` verwenden vollständige EPSG:3035-Zellen:
West-/Südkante und Kante (100-m-ausgerichtet, 100–10.000 m), Status `value`, `missing`,
`not-collected`, `suppressed`; echte Null bleibt bekannt. `CellImportAdapter.normalize`
erhält unbekannte Rohmarker, bevor Validierung und Revisionshash erfolgen. Alte
Schemahashes/Reader bleiben erhalten. Zellen dürfen nicht überlappen; Quellbox ist
keine Werteabdeckung. `MaskedCells` verteilt ungepufferte Flächenanteile nur auf
vollständig bekannte Modellzonen; ganze Teilzonen bleiben Lücken, ihre bekannten
nicht angewendeten Mengen stehen separat. Work nutzt atomare Komposition und
`gaps=none|providers`. Population darf für diesen Vertrag ebenfalls
`gaps=providers` verwenden, mit `osm-places`, `poi-density`, `uniform` in
`gapProviders`. Bei tatsächlichen Lücken ist `population.totalPersons` als
konsistenter Mengenanker erforderlich; nur die Restmenge wird ergänzt. Vollabdeckung
mit widersprechendem manuellem Total ist fatal. Herkunft trennt Daten, Flächenannahme,
Mengenanker und Ersatzmodell. Neues UI: Rasterstatusvertrag im Import, Lückenmodelle
und Mengenanker. Keine native Zensusmigration oder unterzonale Ergänzung; ganze
Haushalte besitzen weiterhin eine eigene Rundungsabweichung. Vertrag/Beispiel:
`docs/examples/masked-cells/README.md`. Opt-in `dataPlan.enabled` bleibt `false`.

**Ergänzung 2026-10-30 – installationsweite Datenpaketbibliothek:**
`DataPackLibrary` speichert unveränderliche portable ZIP-Versionen unter
`<synthesis.data-dir>/source-library/packs/<SHA-256>.zip`. Keine neue Property, kein
Szenarioflag: Veröffentlichung/Übernahme sind explizite Verwaltungsaktionen und
aktivieren weder den Quellenplan noch eine Quelle. Bibliothekspakete müssen alle
referenzierten Revisionen enthalten; `DataPacks.validatePortable` prüft Vertrag,
Referenzgraph und kommunale Bindung vor Speicherung. Die Übernahme verwendet
weiterhin die lokalen Szenariopfade und identische Revisionshashes. Laufresolver und
Eingabelock lesen keine zentralen Dateien. Neue Versionen binden bestehende Szenarien
nicht um. `GET/POST /api/data-library/packs`,
`POST /api/scenarios/{scenario}/data/packs/{hash}/library` (bereitstellen),
`POST /api/scenarios/{scenario}/data/library/packs/{hash}/install` (kopieren).
UI: Daten & Quellen → Datenbestand → Szenarioübergreifende Datenbibliothek.
Ungültige zentrale Einträge sind sichtbar und nicht übernehmbar; ein Bibliotheks-
Lesefehler blockiert den übrigen Szenariobestand nicht. Quellenprofile bleiben
szenariolokal; keine Cloud-Synchronisierung oder Regionalempfehlung. Beispiel:
`docs/examples/source-library/README.md`.

**Ergänzung 2026-10-30 – gemeinsame Quellenprofile:**
`SourceProfileLibrary` speichert `synthesis-source-profile-v1` unter
`<synthesis.data-dir>/source-library/profiles/<Profilhash>.json`. Die gemeinsamen
`SourceProfiles.prepare/decode` erhalten die bisherigen kanonischen Profilhashes.
Portable Profile erlauben feste `pack:`-Beiträge und native `builtin:`-Regeln;
`revision:`-Kandidaten bleiben szenariolokal und werden für die Bibliothek abgelehnt.
Alle aktiven Paketversionen müssen vorhanden, portabel und aspektpassend sein;
höchstens 16 verschiedene. Ausgeschlossene Pakete werden nicht gelesen/kopiert.
Vor der ersten Zielschreiboperation werden alle aktiven Archive eingelesen/geprüft.
Die Übernahme kopiert Profile/Pakete, aktiviert nichts und übernimmt keine
Szenario-Lückenregeln/Mengenanker. Native Quellen werden im Ziel neu aufgelöst;
insbesondere wird kein Upload über `builtin:upload-grid` mitkopiert. Neue globale
Versionen binden vorhandene Szenarien nicht um. Keine zentrale Laufzeitabhängigkeit.
`GET/POST /api/data-library/source-profiles`,
`POST /api/scenarios/{scenario}/data/source-profiles/{hash}/library`,
`POST /api/scenarios/{scenario}/data/library/source-profiles/{hash}/install`.
UI: Daten & Quellen → Datenbestand → Szenarioübergreifende Quellenprofile; direkte
JSON-Definition oder Bereitstellung gespeicherter Szenarioprofile. Fehler werden
sichtbar, andere Datenpfade bleiben nutzbar. Details/Beispiele:
`docs/examples/shared-source-profiles/README.md`.

**Ergänzung 2026-11-02 – Schwerlastquellen und Zielwirkung:**
**Laufbindung und UI (2026-11-04):** `sources/resolution.json` trägt zusätzlich `synthesis-source-run-resolution-v1`: Beginn/Ende, Status/Fehler, Plan-/Lockhashes, tatsächliche Haushaltsrundung und eigene Teilfehler. Die Studioansicht „Daten & Quellen → Lauf prüfen“ zeigt Mengen, Herkunft und Zielausführung getrennt. SUMO-Bilanzen werden nur bei erfolgreicher aktueller Stufe mit Dateihash gebunden; alte Dateien bei übersprungenen Stufen zählen nicht. Nur letzter Lauf, kein Archiv und kein automatischer Gleichstandsbeleg für nachträglich bearbeitete Szenarioeingaben. Kein neues Flag; `dataPlan.enabled=false` bleibt der Default.

`sources/resolution.json.freight` enthält `synthesis-freight-source-resolution-v1`
(`FreightSources`): Eingangs-/Ergebnismengen pro gebundener Relation, Quellzeile und
Revisions-/Rohhash, Zeitpunkt-/Klassen-/Koordinatenprüfung, Join nach
`freight/trips.csv` über `relationId`. Quelle unbestätigt = `unknown`, explizite
Mengendemonstration = `assumed`; modellierte Abfahrtsverteilung wird getrennt als
Annahme mit Seedableitung ausgewiesen. `FreightDemandGenerator.sourceQuality`
kennzeichnet die Demonstration auch in Freight-Berichten und Manifest konsistent
als Annahme. Bestandspfade behalten ihre bisherigen Qualitätswerte.
Keine neue Konfigurationsachse: `dataPlan.enabled` bleibt Opt-in. Auswahl/Abwahl
von `freight.vehicleOd` betrifft allein schweren Hintergrund; aktivierte
Unternehmenstouren, Paketlogistik und Population bleiben unabhängig. Die UI benennt
das ausdrücklich. Erzeugte Fahrten sind kein Routing-/Abschlussbeweis: separate
`sumo/traffic-routing.json`, `sumo/traffic-execution.json`, `freight/execution.json`.
Begrenzte native Abnahme ohne private/Paketfahrten, keine G9-Fachfreigabe. Details:
`docs/examples/source-plan-traffic/README.md`.

## 2. Betriebs-Properties (`SynthesisProperties`, Prefix `synthesis.`)

Quelle: `backend/src/main/java/de/synthesis/SynthesisProperties.java`
(`@ConfigurationProperties(prefix = "synthesis")`). Gesetzt in
`backend/src/main/resources/application.yml` – dort sind **ganze Achsen auskommentiert
(`# sumo:`, `# llm:`, `# transit:`, Teile von `overpass`/`simulation`)**: Das ist bewusste
**Doku der verfügbaren Keys**, die Java-Defaults gelten dann. Nicht löschen; beim Ergänzen von
Keys mitpflegen.

**Ergänzung 2026-10-29 – gemeinsame Auswahl und Work:**
`dataPlan.aspects["opportunities.work"].source="builtins"` erlaubt die geordnete
`providers`-Liste mit `osm-pois` und `synthetic`; Einträge wie bei Bevölkerung.
`ProviderChain<T>` ist der gemeinsame verzögerte Auswahlkern. OSM liefert die
bereits in Stufe 3 abgeleiteten Work-Gewichte; das synthetische Modell verwendet
private Zonenkopien und Seed `population.seed XOR 0x574f524b5f56314c`.
Andere Attraktivitätszwecke und der gemeinsame Pipeline-RNG bleiben unverändert.
Bericht: `sources/resolution.json.workplaces`, Einheit `model-weight`, keine
beobachtete Arbeitsplatzsumme. Fehler: `sources/workplace-failure.json`.
Native Kette und Revisions-/Paketprofile sind inzwischen über gemischte Profile
verbindbar; explizite Registerlücken nutzen `gaps=providers` (siehe Ergänzung oben). OSM-Abwahl für Work deaktiviert keine anderen
OSM-Verbraucher. Reine Kandidatenabwahl gilt nicht als ausgefallene Primärquelle.
Beispiele: `docs/examples/workplace-sources/README.md`.

**Ergänzung 2026-10-29 – native Bevölkerungskette:**
`dataPlan.aspects["population.distribution"].source="builtins"` verwendet die explizite
Liste `providers` mit `provider`, `excluded`, `onUnavailable: fail|next`.
Zulässige IDs: `upload-grid`, `census-2022`, `osm-places`, `poi-density`, `uniform`.
Der Standard bleibt `source=legacy`, der Gesamtplan bleibt `enabled=false`.
Eine manuelle `totalPersons`-Zahl überschreibt nur die Gesamtzahl, nicht die
Verteilungsquelle. Abwahl in dieser Kette betrifft keine Straßen/Arbeitsplätze oder
anderen Zensusverbraucher. Vorprüfung: `pending-resolution`; tatsächliche räumliche
Auswahl im Lauf. Zugelassene Uploads binden Hash/Abwesenheit vor Queueing; Kopie unter
`sources/population-grid.csv`. Getrennte Gesamtzahl-/Verteilungsherkunft unter
`sources/resolution.json.population`; Quellenfehler unter `sources/population-failure.json`.
Die neue Kette schlägt bei Integritätsfehlern/unterdrückten Rasterwerten fehl; die
Legacy-Leiter bleibt kompatibel. Keine allgemeine Qualitätsfreigabe durch `analysis`.
Beispiele und Grenzen: `docs/examples/population-sources/README.md`.


### 2.0 Root

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `synthesis.data-dir` | `data` | Wurzel für Szenarien (`data/scenarios/<id>`), `llm-cache`, `gtfs-cache`, `census-cache` | `ScenarioRepository`, `GenerationService` |
| `synthesis.max-zones` | `600` | Default für `zoning.maxZones` neuer Szenarien; alte API-Vorschauen ohne Feld verwenden ihn ebenfalls | `ScenarioController.defaults`, `GenerationService` |
| `synthesis.max-zones-hard-limit` | `1200` | Harte Schutzgrenze für das Szenariofeld und die Vorschau, weil mehrere OD-Artefakte O(n²) wachsen | `ScenarioController`, `GenerationService` |

### 2.1 `synthesis.overpass` (OSM-Datenquelle)

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `live-data-enabled` | `true` | Zentraler Betriebs-/Testschalter für Live-POI-/Place-Abfragen. Ein Szenario-Snapshot bleibt auch bei `false` nutzbar; ohne Snapshot nutzt die Pipeline synthetische Daten. Kein Nutzerparameter im Studio. | `GenerationService` |
| `endpoint` | `https://overpass-api.de/api/interpreter` | Primärer Overpass-Server | `OverpassClient` |
| `mirrors` | `["https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter"]` | Ausweich-Endpunkte bei 504/429; `allEndpoints()` = primär + Mirrors, dedupliziert | `OverpassClient` |
| `timeout-seconds` | `30` | HTTP-Timeout | `OverpassClient` |

### 2.2 `synthesis.census` (amtliches Bevölkerungsraster Deutschland)

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | Automatische Zensus-Quelle zentral deaktivierbar; kein Szenario-Schalter | `CensusPopulationSource` |
| `version` | `zensus-2022-2024-10-08` | Stabiler Cache-/Manifest-Versionsbezeichner des geprüften Archivs | `CensusPopulationSource` |
| `reference-date` | `2022-05-15` | Amtlicher Zensus-Stichtag für UI und Manifest | `GenerationService` |
| `source-url` | Destatis `Zensus2022_Bevoelkerungszahl.zip` | Amtliches ZIP mit 100-m-/1-km-/10-km-CSV | `CensusPopulationSource` |
| `archive-sha256` | `1a8428…a941a` | Erlaubter Inhalt; Abweichung führt ohne Cache-Vergiftung zum OSM-/Dichte-Fallback | `CensusPopulationSource` |
| `grid-entry` | `Zensus2022_Bevoelkerungszahl_100m-Gitter.csv` | Aus dem ZIP atomar extrahierter Rastereintrag | `CensusPopulationSource` |
| `timeout-seconds` | `180` | HTTP-Zeitbudget des einmaligen Downloads | `CensusPopulationSource` |
| `max-archive-mb` | `64` | Download-Obergrenze (amtlicher Stand: ca. 18 MB komprimiert) | `CensusPopulationSource` |

### 2.3 `synthesis.simulation` (logiTopp, Stufe „Simulation (logiTopp)")

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | Stufe global abschaltbar (SKIPPED) | `GenerationService`-Gate |
| `project-dir` | `../validation/mobitopp-rastatt` | Legacy-Projekt mit `gradlew runDeliverySynthesis` | `SimulationRunner` |
| `timeout-minutes` | `30` | Timeout des Gradle-Laufs | `SimulationRunner` |
| `calibration-iterations` | `8` | Max. Iterationen der Modal-Split-Kalibrierung (je Iteration ein Sim-Lauf) | Kalibrierschleife (`ModeChoiceCalibrator`) |
| `calibration-tolerance` | `0.02` | Abbruch, wenn RMSE der Modus-Anteile zum Ziel darunter liegt | Kalibrierschleife |

### 2.4 `synthesis.actitopp` (KIT actiTopp, Stufe „Aktivitätenpläne")

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | actiTopp abschaltbar (Fallback Template-Generator) | `GenerationService`-Gate |
| `project-dir` | `../validation/actitoppNG` | actitoppNG-Projekt (`gradlew` + `runSynthesis`) | `ActitoppRunner` |
| `timeout-minutes` | `20` | Timeout des Gradle-Laufs | `ActitoppRunner` |

### 2.5 `synthesis.sumo` (nachgelagerte Verkehrssimulation + „Netz-Feedback")

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | Stufe abschaltbar (SKIPPED) | `GenerationService`-Gate |
| `bin-dir` | **berechnet** aus `$SUMO_HOME` (Ableitung s. u.) | Verzeichnis mit `sumo`/`netconvert`/`duarouter` | `SumoRunner` |
| `sumo-home` | **berechnet**: `$SUMO_HOME`; ungesetzt → `sumo` auf dem PATH, danach versionsunabhängiger macOS-Framework-Fallback `/Library/Frameworks/EclipseSUMO.framework/EclipseSUMO/share/sumo` | SUMO-Daten/Tools (i. d. R. `.../share/sumo`) | `SumoRunner`; wird an `TransitRunner` durchgereicht |
| `day` | `0` | Simulierter Wochentag (0 = Montag) | `SumoRunner` |
| `max-trips` | `4000` | Obergrenze eingespeister Fahrzeug-Trips (Performance) | `SumoRunner` |
| `timeout-minutes` | `15` | Timeout des SUMO-Laufs | `SumoRunner` |
| `feedback-max-zones` | `150` | Netz-Feedback: Zonen-Obergrenze (OD-Routing ist n²) | Stufe „Netz-Feedback" |
| `feedback-iterations` | `3` | Max. Gleichgewichts-Iterationen (je Iteration 1 logiTopp- + 1 SUMO-Lauf) | Stufe „Netz-Feedback" |
| `feedback-tolerance` | `0.01` | Konvergenz: Abbruch bei mittlerer Faktor-Änderung darunter | Stufe „Netz-Feedback" |

**Default-Ableitung `bin-dir`** (Logik in `Sumo.defaultBinDir()`, exakt so implementiert):
1. `$SUMO_HOME` gesetzt und `$SUMO_HOME/bin/sumo` existiert → `$SUMO_HOME/bin` (Standard-Layout).
2. Sonst, wenn `$SUMO_HOME/../../bin/sumo` existiert → dieses `bin` (macOS-Framework-Layout:
   `SUMO_HOME = .../EclipseSUMO/share/sumo`, Binaries unter `.../EclipseSUMO/bin`).
3. Sonst `$SUMO_HOME/bin` (auch wenn nicht existent – `SumoRunner.isAvailable()` fängt das ab →
   SKIPPED „SUMO_HOME/Binaries prüfen").
4. `$SUMO_HOME` ungesetzt/leer → Wurzel aus dem ersten ausführbaren `sumo` auf dem PATH;
   ohne Treffer macOS-Fallback `/Library/Frameworks/EclipseSUMO.framework/EclipseSUMO/bin`.

Der Framework-Fallback verwendet den Installer-Symlink `Versions/Current` indirekt und enthält
keine Versionsnummer. Verifiziert mit SUMO 1.27.1 am 2026-08-11.

### 2.6 `synthesis.transit` (GTFS für SUMO und MobileCity)

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | GTFS-Auflösung abschaltbar; SUMO-ÖV entfällt, MobileCity benötigt dann den ausdrücklich erlaubten synthetischen Fallback | `GenerationService.buildPtProvider`, `stageMobileCityExport` |
| `venv-python` | `../validation/transit-venv/bin/python` in `application.yml` | Reproduzierbarer lokaler Python-Interpreter mit pandas/rtree/pyproj; fehlt er, wird ÖV verständlich übersprungen | `TransitRunner.isAvailable()` |
| `gtfs2pt` | **berechnet**: `$SUMO_HOME/tools/import/gtfs/gtfs2pt.py` (ungesetzt → macOS-Framework-`share/sumo` wie oben) | Pfad zu gtfs2pt.py | `TransitRunner` |
| `feeds` | `[kvv]` (Registry s. u.) | Auto-Fetch-Feeds: Verbund-GTFS mit Abdeckungs-Bbox | `GtfsSource` |
| `max-feed-mb` | `200` | Obergrenze Auto-Fetch-Download (MB); größere Feeds → Upload-Empfehlung | `GtfsSource` |
| `window-start-hour` / `window-end-hour` | `6` / `10` | Zeitfenster nur für SUMO/gtfs2pt; MobileCity verwendet einen Volltageszuschnitt, weil `freq` Fahrten pro Werktag bezeichnet | `GtfsCropper`/`TransitRunner`; `MobileCityGtfsAdapter` |
| `timeout-minutes` | `25` | gtfs2pt-Map-Matching (tracemapper) ist langsam → großzügiger Timeout; Ergebnis wird gecacht | `TransitRunner` |
| `modes` | `bus` | gtfs2pt-Modi (OSM-Namen). Aktuell nur Bus – Netz ist road-only; Rail/Tram-Befund in `docs/06-gta-integration-konzept.md` („Phase 2 – SUMO") | `TransitRunner` |

**Feed-Registry-Struktur** (`Transit.Feed`): je Feed `name`, `url`, `min-lat`, `min-lon`,
`max-lat`, `max-lon`. Auswahl über `Feed.covers(lat, lon)`: **enthält die Feed-Bbox das
Regions-Zentrum?** Erster Treffer gewinnt. Vorbelegt ist genau ein Feed (Stand 2026-07-08):

| name | url | Bbox (minLat, minLon, maxLat, maxLon) | Anmerkung |
|---|---|---|---|
| `kvv` | `https://www.nvbw.de/fileadmin/user_upload/service/open_data/fahrplandaten_mit_liniennetz/kvv.zip` | 48.55, 7.95, 49.25, 8.95 | Karlsruher Verkehrsverbund (deckt u. a. Rastatt); NVBW/MobiData-BW, ODbL |

Neue Verbünde: in `application.yml` unter `synthesis.transit.feeds` ergänzen. **Achtung:** Eine
YAML-Liste **ersetzt** den Java-Default komplett – KVV dann mit auflisten. Kein Feed für die
Region + kein Upload → ÖV entfällt mit Log-Hinweis.

### 2.7 `synthesis.llm` (Achse A GTA + LLM-Moduswahl)

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | LLM-Nutzung global abschaltbar (GTA → Fallback actiTopp; Stufe „LLM-Moduswahl" SKIPPED) | Gates in `GenerationService` |
| `endpoint` | `http://localhost:11434/v1` | OpenAI-kompatibler Basis-Endpunkt (Ollama) | `GtaBehaviorRunner`, `LlmModeChoiceRunner` |
| `model` | `qwen2.5:7b-instruct` | Modellname | dito |
| `temperature` | `0.7` | Sampling-Temperatur | dito |
| `timeout-seconds` | `120` | Timeout je LLM-Call | dito |
| `max-personas` | `60` | Obergrenze distinkter Personas (LLM-Calls); je Persona ca. 30–40 s auf einem M1 | `GtaBehaviorRunner` |

Beachte: `synthesis.llm.enabled` ist default `true`, **wirkt aber nur**, wenn ein Szenario
`behaviorSource = "gta"` oder `llmModeChoice = true` setzt – beides experimentell. Ist kein
Ollama erreichbar, greift der Fallback; es gibt keinen harten Fehler.

### 2.8 `synthesis.mobile-city-validation` (native ConfigTool-Prüfung)

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | Laufzeit betriebsweit verfügbar machen; Szenario-Opt-in bleibt erforderlich | `GenerationService`-Gate |
| `python` | `python3` | Interpreter für `validate-mobilecity.py` | `MobileCityValidationRunner` |
| `script` | `../scripts/validate-mobilecity.py` | Isolierter Build-/Import-/Simulations-Adapter | dito |
| `dotnet` | `../validation/tools/dotnet-8.0.425/dotnet` | Gepinnte lokale .NET-8-Laufzeit | dito |
| `tool-source` | `../validation/mobilecity/ConfigTool` | Lokaler MobileCity-ConfigTool-Quellstand; wird nur in ein temporäres Verzeichnis kopiert | dito |
| `nuget-cache` | `../.runtime/nuget` | Optionaler lokaler Paketcache für Offline-Builds | dito |
| `timeout-minutes` | `20` | Gesamtbudget des Prüflaufs und Timeout je nativem Teilprozess | dito |
| `years` | `11` | Aufeinanderfolgende Prüfsimulationsjahre; mindestens 3 | dito |

Gate-Reihenfolge: Ziel `mobilecity` → vollständiger Regional-/Vorlagenmodus →
`mobileCity.validateNative` → Betriebs-Property/Werkzeuge. Nicht angefordert oder nicht
verfügbar = `SKIPPED`; gestarteter Prozess mit Fehler/Timeout = `FAILED`.

### 2.9 `synthesis.auth` (Plattform-Anmeldung, seit 2026-11-04)

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `enabled` | `true` | Cookie-Session-Login für `/api/**`; `false` nur für lokale Einzelnutzer-Entwicklung (API offen, `/api/auth/me` meldet einen lokalen Admin) | `SecurityConfig`, `AuthController` |
| `allowed-origins` | `[http://localhost:4300]` | CORS mit Credentials für externe Dev-Server; leer = same-origin, kein CORS | `WebConfig` |
| `bootstrap-admin` | `admin` | Name des Administrators, der beim ersten Start ohne Konten angelegt wird (Startpasswort in `<data-dir>/initial-admin-password.txt` und im Log) | `UserStore` |
| `min-password-length` | `8` | Mindestlänge neuer Passwörter | `UserStore` |
| `max-login-failures` | `10` | Fehlversuche je Benutzername und Herkunft, danach 429 (`LoginAttemptLimiter`) | `AuthController` |
| `lockout-minutes` | `15` | Dauer der Login-Sperre; alte Fehlversuche verfallen nach dieser Zeit | `LoginAttemptLimiter` |

Konten liegen in `<data-dir>/users.json` (bcrypt). Löschen, Rollenwechsel und Passwort-Reset beenden
laufende Sitzungen des Kontos sofort (`SessionRegistry`). Rollen `ADMIN` (Kontenverwaltung `/api/users`)
und `USER`; alle Angemeldeten teilen den Szenario-Arbeitsbereich, löschen darf nur Ersteller
(`Scenario.createdBy`) oder Admin. Anmeldefrei: `/api/auth/login`, `/api/auth/me`, `/api/health`
und die statischen Frontend-Dateien. Session-Cookie-Parameter unter `server.servlet.session.*`.

### 2.10 `synthesis.frontend` (SPA-Auslieferung durch das Backend, seit 2026-11-04)

| Key | Default | Zweck | Konsument |
|---|---|---|---|
| `dir` | `""` | Verzeichnis des Angular-Builds (`index.html`); leer = `classpath:/static/` (vom Docker-Build ins Jar gebündelt). Nichts gefunden = nur API | `FrontendConfig` |

---

## 3. Env-Variablen

| Variable | Wirkung | Konsument |
|---|---|---|
| `SUMO_HOME` | Quelle der **berechneten Defaults** `synthesis.sumo.bin-dir`, `synthesis.sumo.sumo-home`, `synthesis.transit.gtfs2pt` (Ableitung s. Abschnitte 2.4/2.5). Ungesetzt → PATH-Erkennung, danach versionsunabhängiger macOS-Framework-Fallback | `SynthesisProperties.Sumo`/`Transit` – statisch bei Bean-Erzeugung gelesen; **Backend-Neustart nötig** nach Änderung |
| `SYNTHESIS_DATA_DIR`, `SYNTHESIS_AUTH_ENABLED`, `SYNTHESIS_AUTH_ALLOWED_ORIGINS`, … | Spring-Relaxed-Binding: jede Property aus Abschnitt 2 lässt sich als Env-Variable setzen (`synthesis.data-dir` → `SYNTHESIS_DATA_DIR`). So konfiguriert `docker-compose.yml` den Container | Spring Boot Binder |
| `SYNTHESIS_TRANSIT_VENV` | **Nur Test-Gate, keine Laufzeit-Wirkung:** `backend/src/test/java/de/synthesis/generation/pipeline/PtIntegrationTest.java` liest sie via `System.getenv` und überspringt den (langsamen) ÖV-Integrationstest, wenn ungesetzt („SYNTHESIS_TRANSIT_VENV nicht gesetzt – ÖV-Integrationstest übersprungen"). Zeigt auf den Python-Interpreter eines venv mit pandas/rtree/pyproj. Für den **Betrieb** gilt stattdessen `synthesis.transit.venv-python` | `PtIntegrationTest` |

---

## 4. Checkliste: Neues Szenario-Flag anlegen

Reihenfolge einhalten; jeder Schritt hat einen historischen Grund (Vorfälle:
`synthesis-failure-archaeology`).

1. **`Scenario.java`**: public Feld mit Initialisierer (= Default) + Javadoc, die Achse und den
   Experimentell-Status benennt. **Default `false`/aus, bis das Feature verifiziert ist**
   (Beförderung via `synthesis-change-control`).
2. **`frontend/src/app/core/models/scenario.model.ts`**: Feld im `Scenario`-Interface spiegeln
   (gleicher Name, TS-Typ, JSDoc-Einzeiler). Vergessen = Type-Checks und Autocomplete lügen.
3. **`frontend/src/app/features/studio/studio.component.html`**: Checkbox (bzw. Select/Input) im
   passenden Block („Bevölkerung" oder „Modellierung") mit `[(ngModel)]` und erklärendem
   `title`-Attribut (Hinweis auf Kosten/Voraussetzungen). **Gating** über die Voraussetzungen:
   `*ngIf="hasTarget('logitopp')"`, `hasTarget('sumo')` etc. – ein Flag, dessen Voraussetzung
   fehlt, darf nicht anklickbar sein. **ACHTUNG: KEINE typographischen Anführungszeichen
   („ " ‚ ') im `title`-Attribut** – bricht den strict-AOT-Build mit **NG5002**
   (Vorfall 2026-07-02); gerade Quotes verwenden oder umformulieren.
4. **`GenerationService`-Gate**: Am Anfang der betroffenen Stufe prüfen; wenn aus →
   `job.stage("<Stufenname>").status = "SKIPPED"` + `job.log("... übersprungen (<Grund>)")`.
   Konvention: **SKIPPED = bewusste Abwahl/fehlende Voraussetzung, FAILED = echter Fehler** (der
   Nutzer muss beides unterscheiden können). Bei neuer Stufe: `STAGES`-Liste ergänzen (aktuell
   15 Stufen: Zonierung … LLM-Moduswahl).
5. **`application.yml`**: Falls das Flag eine begleitende Betriebs-Property bekommt →
   auskommentierten Doku-Block ergänzen (Muster `# sumo:` / `# transit:`).
6. **`docs/06-gta-integration-konzept.md`**: Eintrag im passenden Phasen-Abschnitt (was, warum,
   Status experimentell).
7. **Tests/E2E anpassen**: Default-Szenario-Fixtures; für Integrationen mit externem Tooling ggf.
   Env-Gate nach dem Muster `PtIntegrationTest`/`SYNTHESIS_TRANSIT_VENV`.
8. **Default `false` lassen**, bis Verifikation + Change-Control-Gate durchlaufen sind.

Die frühere Referenz `publicTransport` ist seit 2026-09-01 bewusst kein neues Flag-Muster mehr:
ÖV wird für SUMO automatisch versucht, das alte Feld ist nur JSON-Kompatibilität.

## 5. Checkliste: Neue Betriebs-Property anlegen

1. **`SynthesisProperties.java`**: Feld in der passenden inneren Klasse (oder neue innere Klasse
   nach dem Muster `Transit`), mit Default-Initialisierer, Javadoc, Getter **und** Setter
   (Spring-Binding braucht beide). Berechnete Defaults (Env-Ableitung) als `private static`
   Methode wie `defaultBinDir()`/`defaultGtfs2pt()`.
2. **`application.yml`**: Key eintragen – aktiv, wenn er im Standard-Deployment gesetzt sein
   soll; sonst **auskommentiert mit Inline-Kommentar** (die auskommentierten Achsen sind die
   Key-Doku dieses Repos).
3. **Konsument**: Property nur über die Config-Klasse injizieren (`SynthesisProperties.Xyz` in
   den Runner-Konstruktor, Muster `SumoRunner(SynthesisProperties.Sumo config)`,
   `TransitRunner(SynthesisProperties.Transit config, String sumoHome)`) – kein
   `@Value`-Wildwuchs.
4. **Kebab-Case beachten**: Java `maxFeedMb` ↔ YAML `max-feed-mb`.
5. **docs/06** ergänzen, falls die Property Feature-Verhalten steuert (nicht nur ein Timeout).
6. Bei berechnetem Default: Test, der die Ableitung sichert (Env-abhängig → Gate wie in
   Abschnitt 3).

---

## 6. Drift-Schutz: Re-Verifikations-Greps

Flags driften! Vor jeder Entscheidung auf Basis dieser Tabellen (und bei jeder Wartung dieses
Skills) gegen die Quelldateien prüfen – alle Kommandos vom Repo-Root:

```bash
# 1. Alle Szenario-Felder + Defaults (muss 1:1 zu Abschnitt 1 passen):
grep -n "public " backend/src/main/java/de/synthesis/scenario/Scenario.java

# 2. Alle Betriebs-Property-Felder + Defaults (muss 1:1 zu Abschnitt 2 passen):
grep -n "private .*=\|public static class" backend/src/main/java/de/synthesis/SynthesisProperties.java

# 3. Frontend-Modell synchron zu Scenario.java?
grep -n ":" frontend/src/app/core/models/scenario.model.ts

# 4. YAML-Doku-Achsen vollständig (auch auskommentierte zählen!)?
grep -n "^\s*#\?\s*[a-z-]*:" backend/src/main/resources/application.yml

# 5. Welche Stufe liest welches Flag (Konsumenten-Spalte verifizieren)?
grep -rn "scenario.population\.\|scenario.targets()" backend/src/main/java/de/synthesis/generation/GenerationService.java

# 6. Stufenliste + SKIPPED-Gates aktuell?
grep -n "STAGES = List.of\|SKIPPED" backend/src/main/java/de/synthesis/generation/GenerationService.java

# 7. UI-Ort + Gating der Flags im Studio:
grep -n "ngModel)]=\"scenario\.\|hasTarget(" frontend/src/app/features/studio/studio.component.html

# 8. Env-Variablen-Inventar (Erwartung: nur SUMO_HOME + SYNTHESIS_TRANSIT_VENV):
grep -rn "System.getenv" backend/src/main/java backend/src/test/java

# 9. Feed-Registry-Vorbelegung:
grep -n -A6 "static Feed kvv" backend/src/main/java/de/synthesis/SynthesisProperties.java

# 10. Property-Konsumenten (Runner-Konstruktoren):
grep -rn "SynthesisProperties\." backend/src/main/java/de/synthesis/generation/pipeline/ | grep -v import
```

Weicht ein Grep-Ergebnis von diesem Dokument ab, gilt der **Code**; dieses Dokument
aktualisieren und den Stand-Vermerk unten anpassen.

---

## Provenienz & Wartung

- **Stand: 2026-09-22.** MobileCity-Flags, native Prüfeigenschaften und 15. Stufe gegen die
  Ground Truth aktualisiert. Vollständiger Grundkatalog gelesen und übertragen aus:
  `backend/src/main/java/de/synthesis/scenario/Scenario.java`,
  `backend/src/main/java/de/synthesis/SynthesisProperties.java`,
  `backend/src/main/resources/application.yml`,
  `frontend/src/app/core/models/scenario.model.ts`; Konsumenten/Gates per Grep verifiziert in
  `backend/src/main/java/de/synthesis/generation/GenerationService.java`,
  `backend/src/main/java/de/synthesis/generation/pipeline/*.java`,
  `frontend/src/app/features/studio/studio.component.html`,
  `backend/src/main/java/de/synthesis/scenario/ScenarioController.java`,
  `backend/src/test/java/de/synthesis/generation/pipeline/PtIntegrationTest.java`.
- **Volatil** (bei jeder Berührung der Quelldateien neu prüfen, Greps in Abschnitt 6):
  Default-Werte, Experimentell-Status (Flags werden befördert!), Feed-Registry (weitere Verbünde
  geplant), `transit.modes` (Rail/Tram-Befund offen, docs/06), hart codierte
  SUMO-Framework-Version 1.26.0, UI-Positionen im Studio-Template, Stufenliste (aktuell 15).
- **Wartungsregel:** Jede Änderung an `Scenario.java` oder `SynthesisProperties.java` zieht ein
  Update dieses Skills nach sich (Teil der Doku-Pflicht aus `synthesis-change-control`).
- Geschwister-Skills (nicht duplizieren): `synthesis-change-control` (Beförderungs-Gate für
  Flags), `synthesis-architecture-contract`, `synthesis-run-and-operate`,
  `synthesis-build-and-env`, `synthesis-debugging-playbook`, `synthesis-failure-archaeology`,
  `verkehrsmodellierung-reference`.
