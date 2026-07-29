---
name: synthesis-failure-archaeology
description: >
  Failure-Archäologie für SyntheSis — die Chronik aller größeren Untersuchungen, Sackgassen,
  verworfenen Lösungen und Rollbacks (Symptom → Root Cause → Evidenz → Status). Nutze diesen
  Skill, BEVOR du etwas erneut versuchst, das schon einmal scheiterte oder bewusst verworfen
  wurde. Stichworte der Vorfälle: Rail/Tram-ÖV in SUMO zurückgerollt (duarouter mapmatch snappt
  Fußgänger auf Gleise, "No destination edge"), LLM-Moduswahl mit MiD-Verankerung revertiert
  (Qwen ignoriert quantitative Vorgaben), externe Zonen werden nie gewählt, pyproj/PEP 668,
  gtfs2pt langsamer tracemapper / fehlender vType "bus", KIT populationsynthesis nicht generisch
  anbindbar / Mega-Haushalte (444 Personen), Llama-3.1-Lizenz verworfen, temperature 0.7
  nicht reproduzierbar, GTA-Überproduktion (5,7 Aktivitäten/Tag), POPtrips-Halbzählung,
  Kalibrier-Loop-Bugs (createDirectories, ASC-Semantik), Executor-Pool 2→1, Overpass-Rate-Limit,
  "No destination available for WORK", OID vs. externe Zonen-ID, "SynthesisApplication 2.class".
  Auch nutzen bei Fragen wie "warum ist X so gebaut?", "wurde Y schon mal probiert?",
  "können wir nicht einfach Z machen?".
---

# SyntheSis Failure-Archaeology — entschiedene Schlachten nicht neu schlagen

Dieses Dokument ist das institutionelle Gedächtnis des Projekts: jede größere Untersuchung,
Sackgasse, verworfene Lösung und jedes Rollback, jeweils als **Symptom → Root Cause → Evidenz →
Status → Was du stattdessen tust**. Bevor du einen "naheliegenden" Ansatz umsetzt, prüfe hier,
ob er schon einmal gescheitert ist — und warum.

**Stand: 2026-07-28.** Primärquellen: `docs/05-validierung.md` (Phase-0-/Adapter-/logiTopp-Befunde)
und `docs/06-gta-integration-konzept.md` (Phasen-Chronik F). Historische Messzahlen sind
überlieferte Werte vom jeweils genannten Datum, keine aktuellen Messungen.

## Status-Labels

| Label | Bedeutung |
|---|---|
| **ENTSCHIEDEN** | Untersucht, entschieden, im Code/in der Doku verankert. Nicht neu aufrollen ohne neue Fakten. |
| **ZURÜCKGEROLLT** | Implementiert, an einem harten Blocker gescheitert, revertiert — **mit dokumentiertem Lösungsweg** für einen zweiten Anlauf. |
| **OFFEN** | Bekanntes Problem, bewusst vertagt; Workaround existiert. |

## Schnellindex

| # | Vorfall | Thema | Status |
|---|---|---|---|
| R1 | Rail/Tram-ÖV in SUMO | ÖV/GTFS | ZURÜCKGEROLLT |
| R2 | LLM-Prompt "MiD-Verankerung" für Moduswahl | LLM/GTA | ZURÜCKGEROLLT |
| E1 | Externe Zonen als Distanz-Hebel | Nachfrage | ENTSCHIEDEN |
| E2 | Python/pyproj im SUMO-Pfad | SUMO | ENTSCHIEDEN |
| E3 | gtfs2pt schneller Mapper (Java-Jar) | ÖV/GTFS | ENTSCHIEDEN |
| E4 | gtfs2pt-Zwischendatei-vType "bus" | ÖV/GTFS | ENTSCHIEDEN |
| E5 | KIT populationsynthesis generisch | Population | ENTSCHIEDEN |
| E6 | Mega-Haushalte aus Seed-IDs | Population | ENTSCHIEDEN |
| E7 | Llama 3.1 als LLM | LLM/GTA | ENTSCHIEDEN |
| E8 | GTA-Nicht-Reproduzierbarkeit (temperature) | LLM/GTA | ENTSCHIEDEN |
| E9 | GTA-Prompt-Überproduktion | LLM/GTA | ENTSCHIEDEN |
| E10 | POPtrips-Halbzählung + Kinder <6 | Nachfrage | ENTSCHIEDEN |
| E11 | Kalibrier-Loop-Bugs (writeMnl, ASC-Semantik) | Kalibrierung | ENTSCHIEDEN |
| E12 | Parallel-Generierung (Executor-Pool) | Infrastruktur | ENTSCHIEDEN |
| E13 | OSM-Fetch: Memoisierung statt Disk-Cache | Infrastruktur | ENTSCHIEDEN |
| E14 | SUMO-Statistik-Parser: kombinierter Regex | SUMO | ENTSCHIEDEN |
| E15 | actiTopp: "No destination available for WORK" | Legacy-Formate | ENTSCHIEDEN |
| E16 | Phase-0-Formatbefunde (8 Stück) | Legacy-Formate | ENTSCHIEDEN |
| E17 | Adapter-/Depot-Befunde A1–A12 | Legacy-Formate | ENTSCHIEDEN |
| E18 | logiTopp-Befunde L1–L6 | Legacy-Formate | ENTSCHIEDEN |
| E19 | "SynthesisApplication 2.class"-Falle | Infrastruktur | ENTSCHIEDEN |
| E20 | `b_tt_put` allein als Modus-Distanz-Kalibrierung | Kalibrierung | ENTSCHIEDEN |
| E22 | `<1 km`-Kalibrierung bei grober Zonierung | Kalibrierung | ENTSCHIEDEN |
| O1 | Overpass-Rate-Limit / self-hosted | Infrastruktur | OFFEN |

---

## 1. ÖV/GTFS

### R1 — Rail/Tram-ÖV in SUMO (2026-07-07) — ZURÜCKGEROLLT

- **Symptom/Frage:** Die Rastatt-Region hat neben Bus (1532 GTFS-Fahrten) auch Tram (995) und
  Rail (903 Fahrten) — warum simuliert SUMO nur Bus?
- **Was implementiert war:** railway-OSM-Fetch (`way["railway"~"rail|tram|light_rail|subway"]`,
  von GraphHopper ignoriert), `netconvert --keep-edges.by-vclass +rail,rail_urban,tram
  --railway.topology.repair` (baute 869 Rail-Kanten), `gtfs2pt --modes bus,train,tram`.
- **Root Cause (Blocker):** `duarouter --mapmatch.distance` snappt personTrip-Koordinaten auf die
  **nächste Kante ohne vClass-Filter**. Nahe Bahnhöfen ist das eine Rail-Kante, auf der Fußgänger
  nicht laufen dürfen → Abbruch mit *"No destination edge for walk or personTrip"*. Die
  Alternative `--mapmatch.junctions` verwirft betroffene Personen **kommentarlos** (0 Personen
  geroutet — stiller Fehler, kein Fix). Ein `--mapmatch.vclass` existiert in duarouter nicht.
- **Evidenz:** `docs/06-gta-integration-konzept.md`, Abschnitt "Phase 2 – SUMO", Unterpunkt
  "Rail/Tram versucht, zurückgerollt (2026-07-07)"; README-Hinweis "ÖV ist aktuell auf Bus
  beschränkt". Test-Fixture: `validation/rastatt-rail-test.osm`. Alle Rail-Änderungen revertiert,
  Bus-ÖV blieb grün (Test-Suite 18/18, Stand 2026-07-07).
- **Was du stattdessen tust (dokumentierter Lösungsweg für Anlauf 2):** vClass-gefiltertes
  Edge-Snapping **in Java**: SUMO-Netz selbst einlesen, je Trip die nächste fußgänger-/pkw-fähige
  Kante bestimmen und `<personTrip from=EDGE to=EDGE>` schreiben statt `fromLonLat`. Eigenes
  Teilprojekt — nicht als Nebenbei-Fix versuchen. Kein erneuter Versuch über
  duarouter-Mapmatch-Optionen: beide Varianten sind ausgetestet.

### E3 — gtfs2pt "schneller Mapper" auf 16-GB-Hardware — ENTSCHIEDEN

- **Symptom:** `gtfs2pt.py` extrem langsam (~20–25 Min. für ein Zeitfenster).
- **Root Cause:** Ohne die kompilierte Mapping-Bibliothek (externes Java-Jar `fcd-process-chain`,
  benötigt `-mx16000m`) fällt gtfs2pt auf den langsamen `tracemapper` zurück. 16 GB Heap sind auf
  der 16-GB-Entwicklungshardware unmöglich.
- **Evidenz:** `docs/06-gta-integration-konzept.md`, "Phase 2 – SUMO", Unterpunkt
  "ÖV/Bus via GTFS — Phase C" ("Wichtiger Praxis-Befund").
- **Was du stattdessen tust:** `tracemapper` akzeptieren; Laufzeit über Zeitfenster-Crop lösen
  (Default 6–10 Uhr, überliefert 2026-07-07: 674 von 3430 Fahrten) plus Ergebnis-Cache im
  `TransitRunner`. Nicht erneut versuchen, das Jar auf dieser Hardware zu betreiben.

### E4 — gtfs2pt definiert vType "bus" nur in Zwischendatei — ENTSCHIEDEN

- **Symptom:** SUMO bricht ab mit *"vehicle type 'bus' is not known"*.
- **Root Cause:** `gtfs2pt.py` referenziert `type="bus"` in der Fahrzeug-Ausgabe, schreibt die
  `<vType>`-Definition aber nur in eine Zwischendatei.
- **Evidenz:** `docs/06-gta-integration-konzept.md`, "Phase 2 – SUMO", Phase-C-Unterpunkt
  ("Bug gefunden+gefixt"); Fix im Code:
  `backend/src/main/java/de/synthesis/generation/pipeline/TransitRunner.java`,
  Methode `writeVtypes` → `pt_vtypes.add.xml`.
- **Was du stattdessen tust:** Nichts — Fix ist im Code. Beim Debuggen von "unknown vehicle type"
  zuerst prüfen, ob `pt_vtypes.add.xml` erzeugt und eingebunden wird.

---

## 2. LLM/GTA

### R2 — LLM-Prompt "MiD-Verankerung" für die Moduswahl (2026-06-26) — ZURÜCKGEROLLT

- **Symptom:** Die rohe LLM-Moduswahl (`llmModeChoice`) hat einen starken Bias zu aktiven Modi:
  Rad ~35–45 % statt MiD 11 %, Pkw zu niedrig; RMSE zu MiD ~0,19 vs. kalibriertes MNL 0,07
  (überlieferte Werte, 2026-06-26).
- **Versuchter Fix:** Explizite quantitative MiD-Anteile in den Prompt schreiben.
- **Root Cause des Scheiterns:** Qwen2.5-7B **folgt quantitativen Vorgaben im Prompt nicht** —
  die Ausgabe änderte sich nicht. Mechanismus-Befund (bleibt gültig): Das LLM wählt zeitrational
  das schnellste Verkehrsmittel; ihm fehlen die kalibrierten modus-spezifischen Konstanten (ASCs),
  die im MNL die Pkw-Präferenz abbilden.
- **Evidenz:** `docs/06-gta-integration-konzept.md`, "Phase 3", Unterpunkt 3c ("BEFUND: …
  Explizite MiD-Erdung im Prompt half nicht").
- **Was du stattdessen tust:** `llmModeChoice` bleibt eine **experimentelle Vergleichslinse**
  neben dem kalibrierten MNL und MiD (Artefakt `simulation/llm-mode-choice.json`), ersetzt die
  MNL-Wahl nicht. Kein weiteres Prompt-Engineering mit Zahlenzielen bei diesem Modell; für
  Modal-Split-Ziele ist die ASC-Kalibrierung (Phase 1, `ModeChoiceCalibrator`) der Hebel.

### E7 — Llama 3.1 als LLM verworfen — ENTSCHIEDEN

- **Frage:** Welches lokale Modell für die GTA-Verhaltensquelle?
- **Root Cause der Ablehnung:** Llama 3.1 ist nur "open weight" mit Community-Lizenz-Klauseln
  (Auflagen); zudem schlechtere JSON-Treue als die Alternative.
- **Entscheidung:** **Qwen2.5-7B-Instruct** (Apache-2.0, echtes Open Source, bessere JSON-Treue)
  via Ollama.
- **Evidenz:** `docs/06-gta-integration-konzept.md`, Abschnitte E (Lizenztabelle) und
  "Phase 3 – 3a Setup".
- **Was du stattdessen tust:** Bei Modellwechsel-Ideen zuerst Lizenz (echtes OSS) und JSON-Treue
  prüfen — das waren die Entscheidungskriterien.

### E8 — GTA nicht reproduzierbar bei temperature 0.7 (2026-06-26) — ENTSCHIEDEN

- **Symptom:** Gleicher Szenario-Seed, unterschiedliche Läufe: Wege/Person·Tag schwankten
  ~2,4–3,1 (überliefert).
- **Root Cause:** LLM-Stochastik; `temperature > 0` ohne Request-Seed.
- **Fix (im Code):** Pro LLM-Request ein stabiler Seed
  `scenarioSeed * 31 + persona.hashCode()`; Ollama respektiert den OpenAI-`seed`-Parameter
  (byte-identische Antworten verifiziert).
- **Evidenz:** `docs/06-gta-integration-konzept.md`, "Phase 3", Unterpunkt "3b Reproduzierbarkeit";
  Test `sameSeedIsReproducible` (in `GtaBehaviorTest`).
- **Was du stattdessen tust:** Nie GTA-bezogene Reproduzierbarkeitsprobleme mit
  "LLMs sind halt stochastisch" abtun — der Seed-Mechanismus existiert; bei Regression dort suchen.

### E9 — GTA-Prompt-Überproduktion — ENTSCHIEDEN

- **Symptom:** Roher Prompt lieferte ~5,7 Aktivitäten/Person·Tag (MiD-Referenz: ~3,1 Wege/Tag).
- **Fix:** Prompt auf MiD-Frequenz getunt (~3 Wege/Tag, max. 2 Touren/Tag) plus Kappungen im
  Parsing (`parseTours`).
- **Evidenz:** `docs/06-gta-integration-konzept.md`, "Phase 3", Unterpunkt "3b MVP"
  ("Plausibilität"). End-to-End-Ergebnis (überliefert 2026-06-26): GTA trifft 3,1 Wege/Person·Tag.
- **Was du stattdessen tust:** Bei Prompt-Änderungen die Wege/Person·Tag-Kennzahl gegen MiD
  gegenprüfen — der Prompt ist bewusst gedrosselt, nicht "unvollständig".

---

## 3. Nachfrage & Distanzen

### E1 — Externe Zonen als naiver Distanz-Hebel (2026-06-12) — ENTSCHIEDEN

- **Frage:** Kann man die zu kurze Ø-Weglänge (MiD 12,5 km) über einen Ring externer Zielzonen
  heben?
- **Befund (überliefert 2026-06-12):** 8 Kompass-Zielzonen bei 25 km wurden von 124k Trips
  **nie** gewählt — die Distanz-Disnutzität des Rastatt-Zielwahlmodells schließt sie faktisch
  aus. Erst bei 12 km und Attraktivitäts-Faktor 4,0 gehen ~3,3 % der Wege nach außen; Ø-Weglänge
  steigt nur 3,05 → 3,65 km.
- **Root Cause:** Die MiD-Distanz ist über ferne Attraktivitäts-Zonen **nicht** erreichbar; das
  kalibrierte Zielwahlmodell deckelt Fernwege strukturell.
- **Evidenz:** `docs/05-validierung.md`, Abschnitt "Außenbeziehungen / externe Zielzonen
  (2026-06-12) — teilweise wirksam".
- **Was du stattdessen tust:** Der echte Hebel ist die **Distanz-Sensitivität der Zielwahl**
  (siehe Geschwister-Skill `synthesis-distanz-kampagne`) oder ein realistisch großes
  Untersuchungsgebiet. Keine weiteren Experimente mit noch attraktiveren/ferneren Ringzonen.

### E10 — POPtrips-Halbzählung + Kinder unter 6 (2026-06-21) — ENTSCHIEDEN

- **Symptom:** MobileCity-`POPtrips` wies nur etwa die halbe Wegezahl aus; Kinder-Wegerate weit
  unter Karlsruhe-Referenz.
- **Root Cause (zweiteilig):** (a) Naive Zählung nur der Out-of-home-Aktivitäten ignoriert die
  Heimwege; (b) actiTopp kann Kinder < 6 nicht planen → 0 Wege für Kleinkinder.
- **Fix:** (a) Jede Aktivitäts-Transition = ein Weg, der **Heimweg behält den Zweck der
  Ursprungsaktivität**; (b) `ActivityGenerator.youngChildDay`-Fallback (Begleitwege: Kita +
  gelegentliche Begleit-Freizeit/-Einkäufe) hob die Kindergruppe 2,29 → 2,50 Wege/Tag.
- **Evidenz:** `docs/05-validierung.md`, Abschnitt "MobileCity-Export (2026-06-12)"
  (POPtrips-Absatz, verifiziert 2026-06-21: Erwachsene 3,44 / Ref 3,38, Senioren 3,04 / Ref 2,99).
- **Wichtig:** Der Rest-Gap der Kinder (2,50 vs. Referenz 3,16) ist actiTopps MOP-kalibrierte
  Schulkind-Rate — **kein Defekt**, nicht "fixen".

---

## 4. Population

### E5 — KIT populationsynthesis generisch anbinden (2026-06-12) — ENTSCHIEDEN

- **Frage:** Kann das echte KIT-IPU-Framework je gezeichneter Region live laufen?
- **Root Cause der Blockade:** Das Framework ist fundamental an deutsche **ARS-Schlüssel
  (Gemeindeebene) + Zensus-Regionaltabellen** gebunden (`RuleProviderFactory`/`HierarchyFactory`
  fest verdrahtet); eine gezeichnete lat/lon-Box hat weder ARSKey noch Regionaltabelle. Der
  nationale Personen-Seed liegt zudem nicht im Repo.
- **Entscheidung: Seed-Pfad.** KIT-IPU-Output einmalig offline erzeugt und als demografisches
  Sample gebündelt: `backend/src/main/resources/kit-population-sample.csv` (6.512 Haushalte /
  13.168 Personen, Marne-Beispiel). `PopulationGenerator` zieht bei `useKitPopulation` ganze
  Haushalte je Zone. Der Deutschland-Zensus-Pfad bleibt als datenlastige Zukunftsoption
  dokumentiert, ist aber nicht verdrahtet.
- **Evidenz:** `docs/05-validierung.md`, Abschnitt "KIT populationsynthesis (2026-06-12) — läuft,
  aber datengebunden" inkl. "Umgesetzt — generischer Seed-Pfad".
- **Was du stattdessen tust:** Kein erneuter Versuch einer Live-Integration ohne ARS-Geometrie-
  Lookup + Zensus-Regionaltabellen-Upload. Besseres Sample einspielen geht jederzeit (gleiches
  Format `householdId;age;sex`, `PopulationSample` nimmt es unverändert).

### E6 — Mega-Haushalte durch Seed-Template-IDs — ENTSCHIEDEN

- **Symptom:** "Haushalte" mit bis zu 444 Personen im Export.
- **Root Cause:** `CensusHouseholdConverter` (KIT) behält die Seed-Template-`hh.id` bei —
  alle IPU-Klone desselben Templates kollabieren zu einem Mega-Haushalt.
- **Fix:** Eigener Export-Runner (`SynthesisSample.kt` im populationsynthesis-Klon) vergibt
  **laufende eindeutige Haushalts-IDs**.
- **Evidenz:** `docs/05-validierung.md`, "Umgesetzt — generischer Seed-Pfad (2026-06-12)"
  ("statt der kollidierenden Seed-Template-ID").
- **Was du stattdessen tust:** Beim Erneuern des Samples immer die HH-Größenverteilung prüfen
  (Soll ≈ 40/36/12/8/5 %); Ausreißer-Haushalte deuten auf die ID-Falle.

---

## 5. SUMO

### E2 — Python/pyproj im SUMO-Pfad (2026-06-26) — ENTSCHIEDEN

- **Symptom:** `pip install pyproj` (für sumolib-Geo-Helper) scheiterte an PEP 668
  ("externally-managed-environment").
- **Entscheidung:** `SumoRunner` bleibt **nativ Python-frei**: Trips werden als Geo-Koordinaten
  (`fromLonLat`/`toLonLat`) in Java geschrieben, das Kanten-Snapping macht
  `duarouter --mapmatch.distance` in C++. Die sumolib-Helper wurden gelöscht.
- **Bewusste, gekapselte Ausnahme (2026-07-07):** `gtfs2pt.py` braucht ein Python-venv
  (pandas/rtree/pyproj, „transit-venv") — die **einzige** Python-Stelle im Projekt; die
  ÖV-Stage wird SKIPPED, wenn das venv fehlt.
- **Evidenz:** `docs/06-gta-integration-konzept.md`, "Phase 2 – SUMO" ("Bewusst pyproj-/Python-
  frei" und Phase-C-Unterpunkt); Code `backend/src/main/java/de/synthesis/generation/pipeline/SumoRunner.java`,
  `TransitRunner.java`.
- **Was du stattdessen tust:** Keine neuen Python-Abhängigkeiten in den SUMO-Pfad einführen; wenn
  unvermeidbar, nach dem transit-venv-Muster kapseln (eigenes venv, Stage SKIPPED ohne venv).

### E14 — SUMO-Statistik-Parser: kombinierter Regex — ENTSCHIEDEN

- **Symptom:** `speed` wurde **still** 0 in den SUMO-KPIs.
- **Root Cause:** Ein kombinierter Regex über mehrere XML-Attribute matchte bei
  Attribut-Reihenfolge-/Format-Varianten nicht vollständig.
- **Fix:** Attributweise Regexe (je Attribut ein `name="([^"]*)"`-Pattern).
- **Evidenz:** Code `backend/src/main/java/de/synthesis/generation/pipeline/SumoRunner.java`
  (per-Attribut-`attr(...)`-Extraktion, z. B. für `vehicleTripStatistics`, `CO2_abs`, `vType`).
- **Was du stattdessen tust:** Beim Erweitern der XML-Auswertung das attributweise Muster
  beibehalten; nie mehrere Attribute in einem Regex koppeln.

### Randnotiz Lieferverkehr: TRAM-Legs verworfen — ENTSCHIEDEN

logiTopp-`vehicle_type`-Codes sind OTHER=0 / BIKE=1 / TRUCK=2 / TRAM=3 (Befund A10);
TRAM-Legs werden beim SUMO-Lieferverkehr-Import bewusst verworfen (kein Schienennetz, vgl. R1).
Evidenz: `docs/05-validierung.md` A10; `docs/06`, "Phase 2 – SUMO", Unterpunkt
"Lieferverkehr integriert (2026-07-02)" ("TRAM wird verworfen").

---

## 6. Kalibrierung

### E11 — Kalibrier-Loop-Bugs und -Semantik — ENTSCHIEDEN

Drei Stolpersteine im `ModeChoiceCalibrator`
(`backend/src/main/java/de/synthesis/generation/pipeline/ModeChoiceCalibrator.java`):

| Stolperstein | Root Cause | Fix/Regel |
|---|---|---|
| Crash in der ersten Iteration | `writeMnl` schrieb in `simulation/`, das beim ersten Aufruf noch nicht existiert | `Files.createDirectories(outputFile.getParent())` in `writeMnl` (im Code) |
| "Warum entspricht die Datei nicht dem letzten Log-Eintrag?" | Loop-Reihenfolge: schreiben → simulieren → ASC-Update. Die Output-Datei enthält den Zustand **vor** dem letzten Update; nach N Iterationen stecken (N−1) Updates in der Datei | Semantik verstanden lassen — kein Bug. Das letzte berechnete Delta wird bewusst nicht mehr geschrieben, wenn das Iterationslimit greift |
| Divergenz/Überschwingen | Ungedämpfte ΔASC = ln(Ziel/Sim) kann explodieren | ΔASC geklemmt auf ±1,5 je Iteration; `asc_car_d` als fester Anker (Referenzmodus) |

- **Evidenz:** Code (Zeilen mit Clamp ±1,5, `ANCHOR_MODE = "car"`, `createDirectories`);
  Test `backend/src/test/java/.../pipeline/ModeChoiceCalibratorTest.java` (Konvergenz, fester
  Anker, Klemmung — siehe Commit `bd43219`); `docs/06-gta-integration-konzept.md`, "Phase 1"
  (Verifikation überliefert 2026-06-21: RMSE 0,170 → 0,055 in 5 Iterationen).
- **Was du stattdessen tust:** Anker und Klemmung nicht "optimieren"; bei Kalibrierproblemen
  zuerst Ziel-Split-Plausibilität und Iterationszahl prüfen.

### E20 — `b_tt_put` allein als Modus-Distanz-Kalibrierung — ENTSCHIEDEN

- **Symptom/Frage:** 96,65 % der Wege ab 20 km lagen im Großraum-Spike beim ÖV.
  Reicht es, dessen Reisezeitkoeffizienten zwischen `-0,05` und `-0,20` zu kalibrieren?
- **Befund:** Präregistriertes Raster `-0,08/-0,10/-0,12` verschob den Fernweg-ÖV
  monoton auf 71,03/23,16/3,50 %, erreichte aber höchstens Joint-RMSE
  0,054109 gegenüber 0,054671 in der Baseline. Kein Punkt bestand `<0,05` und
  Verbesserung ≥0,004. `-0,10` lag bei Fernwegen nahe am Ziel, drückte ÖV global
  aber auf 6,70 %.
- **Root Cause:** `b_tt_put` verändert gleichzeitig die **Distanzschräge** und das
  globale ÖV-Niveau. Ein Parameter kann beide Ziele hier nicht unabhängig treffen.
  Zudem dominiert die falsche Distanzmarginale den gemeinsamen RMSE; im Spike fehlen
  Wege unter 1 km vollständig (MiD: 21 %).
- **Positiver Gegenbeweis/Lösungsweg:** Präregistrierte Komposition
  `b_tt_put=-0,11` + `asc_put=-1,22` traf ÖV gesamt 9,34 % (MiD 10 %) und
  ÖV ab 20 km 17,60 % (MiD 17,61 %); Modal-RMSE 0,105742→0,085375,
  Joint-RMSE 0,054671→0,053002, Nachfrage stabil.
- **Status/Regel:** Nie `b_tt_put` allein als Produktkalibrierung übernehmen.
  Schräge (`b_tt_put`) und Niveau (`asc_put`) getrennt komponieren. Diese Regel ist
  seit 2026-07-28 im opt-in `ModeDistanceCalibrator` umgesetzt; trotzdem kein
  Default-Flip vor drei Seeds, Realrouting und gemeinsamer Distanzmarginal-
  Kalibrierung. `calibrateDistance` + `calibrateModeDistance` werden inzwischen
  genau in dieser Reihenfolge ausgeführt und teilen den Zielwahl-Override.
  Der Legacy-Helper mit ÖV-Zugang/-Abgang `0.0` bleibt separat zu korrigieren.
  Evidenz: `docs/05-validierung.md`, Abschnitt
  „Modus-Distanz-Kalibrierung (2026-07-28)“.

### E22 — `<1 km`-Kalibrierung bei grober Zonierung — ENTSCHIEDEN

- **Symptom/Frage:** Kann der kombinierte Distanz-/ÖV-Regler in einem
  55×55-km-Großraum mit 2,3-km-Zellen die amtlichen 21 % Wege unter 1 km
  herstellen?
- **Befund:** Nein. Im Realrouting-A/B vom 2026-07-29 blieb `<1 km` trotz
  acht Iterationen und `b_intrazonal`-Delta 0→14 bei 0,0 %. 81,54 % der
  kalibrierten Wege landeten stattdessen im Band 1–<2 km;
  Distanzband-RMSE 0,1343→0,3072, Joint-RMSE 0,0438→0,0857.
  A/B hatten byte-identische `CAR_DIS`-/Zeitmatrizen, ein Routing-Fallback
  ist ausgeschlossen.
- **Root Cause:** Die zellgrößen-skalierte Matrix repräsentiert jede
  intrazonale Pkw-Beziehung dort mit genau
  `0,5214 × 2,3 km × 1,35 = 1,62 km`. `b_intrazonal` ändert nur die
  Zielhäufigkeit, nicht die Matrixdistanz. Der Hebel ist für das
  `<1 km`-Band deshalb strukturell nicht beobachtbar.
- **Verworfener Scheinfix:** Nicht Clamp oder Iterationszahl erhöhen. Das
  verstärkt ausschließlich die Fehlkonzentration im 1–<2-km-Band.
- **Status/Regel:** `GenerationService` prüft vor dem Graphaufbau den kleinsten
  intrazonalen Pkw-Matrixwert und lehnt `calibrateDistance=true` bei
  `>=1 km` mit konkreter Auflösungsmeldung ab. Für neue Versuche feinere
  Zonen wählen oder Distanzrepräsentation/Zielgröße separat herleiten und
  präregistrieren. Evidenz: `docs/05-validierung.md`, Abschnitt
  „Gemeinsame Kalibrierung mit Realrouting (2026-07-29)“.

---

## 7. Legacy-Formate (mobiTopp/logiTopp)

### E21 — `PUT_ACT`/`PUT_EGT` erreichen die Legacy-Moduswahl nicht — BLOCKIERT

- **Symptom/Frage:** SyntheSis erzeugt ÖV-Zugangs- und Abgangsmatrizen mit je
  sechs Minuten, im MNL-Nutzen steht `b_acc_put`; beeinflussen diese Werte die
  Moduswahl?
- **Befund:** Nein. Der externe `MultiNomialLogit1HelperOldImpl` liefert für
  `getACCESS_TIME_PUBLICTRANSPORT(...)` und
  `getEGRESS_TIME_PUBLICTRANSPORT(...)` hart `0.0`. Das dort verfügbare
  `ImpedanceIfc` exponiert Reisezeit, Kosten und Distanz, aber keine separaten
  Access-/Egress-Matrizen.
- **Root Cause:** Die Matrizen werden exportiert, der verwendete externe
  Legacy-Adapter bindet sie aber nicht an den Nutzen-Term an.
- **Verworfener Scheinfix:** Nicht `PUT_ACT + PUT_EGT` auf `PUT_TTC` addieren.
  Das vermischt Zugangs-/Abgangszeit mit In-Vehicle-Zeit und umgeht den
  vorgesehenen Koeffizienten `b_acc_put`.
- **Status/Regel:** Hauptrepo unverändert lassen. Erst den externen
  Adaptervertrag explizit erweitern und danach in einem A/B-Lauf belegen, dass
  `PUT_ACT`/`PUT_EGT` die Moduswahl erwartungsgemäß verändern. Evidenz:
  `docs/05-validierung.md`, Abschnitt „ÖV-Zugang und -Abgang (2026-07-28)“.

### E16 — Die 8 Phase-0-Formatbefunde (2026-06-10) — ENTSCHIEDEN

Alle acht sind in die Generatoren zurückgespielt; der Regressionstest (echter mobiTopp-Lauf,
13.832 Wege, überliefert) bestand. Vollständige Tabelle: `docs/05-validierung.md`,
Abschnitt "Die 8 Befunde". Die teuersten Fallen:

| Falle | Regel |
|---|---|
| `activity.csv` activityType | **Numerische** LegacyActivityType-Codes (WORK=1, SHOPPING_DAILY=41, HOME=7 …), kein Klartext |
| Enums statt Booleans | `chargingInfluencesDestinationChoice` = `NEVER`/…; `mobilityProviderCustomership` = `{}` |
| `car.csv` | **Positional** geparst, 12-Feld-Legacy-Layout (Segment=Idx 7, Sitze=Idx 8), `carType` lowercase |
| Distanzmatrizen | Einheit **Meter** (nicht km), zusätzlich unter `matrix/other/{CAR,BIKE,AIR}_DIS.mtx.bz2` |
| Matrix-YAMLs | Müssen **alle** Modi des Projekts abdecken; kostenlose Modi via `constant_matrix: "0.0"` |
| fixedDestination | Zwei Dateien: `fixedDestination.csv` (kompatibel) + separates `fixedDestination-logitopp.csv` (inkl. PICK_UP_PARCEL) |

### E17 — Adapter-/Depot-Befunde A1–A12 (2026-06-11/12) — ENTSCHIEDEN

Vollständig: `docs/05-validierung.md`, Abschnitte "Adapter-Befunde (Legacy-Eingabeformate)"
(A1–A8) und "Befunde Depot-Anbindung" (A9–A12). Die klassische Falle daraus:

- **A2 vs. A9 — Zonenreferenz-Verwechslung:** Demand-Daten (`household.csv` homeZone,
  `fixedDestination.csv`/`opportunity.csv` zoneId) referenzieren Zonen über die
  **OID/matrixColumn** — externe IDs werden **still verworfen** (Symptom: 0 Haushalte!).
  `distribution-centers.csv` referenziert dagegen über die **EXTERNE ID** (`getByExternalId`).
  Wer beides gleich behandelt, produziert stille Datenverluste.
- **A5 — Tour-Struktur in activity.csv:** `tournr` inkrementiert je Blockwechsel, genau eine
  `isMainActivity=true` je Tour, aufeinanderfolgende HOME-Zeilen (z. B. über Mitternacht)
  **mergen** — sonst crasht `TourPattern`.
- **A3 — Pkw-Attribute positional**; A10 — `vehicle_type`-Int-Codes (OTHER=0/BIKE=1/TRUCK=2/TRAM=3).

### E18 — logiTopp-Befunde L1–L6 (2026-06-11) — ENTSCHIEDEN

Vollständig: `docs/05-validierung.md`, Abschnitt "Befunde für die logiTopp-Integration". Kern:

| # | Falle | Regel |
|---|---|---|
| L1 | PACKSTATION crasht ohne `PICK_UP_PARCEL`-fixedDestinations | `fixedDestination-logitopp.csv` liefern (oder Zielanteile ohne PACKSTATION) |
| L2 | NPE beim Parcel-Build | `allAsDelivery()`/`allAsPickup()`-Builder-Step ist Pflicht |
| L3 | Depots brauchen `DepotOperations`-Verdrahtung pro Depot | siehe A12: Parser legt CEPSPs an, DepotOperations nicht |
| L4 | Zustellfahrzeuge routen über `StandardMode.TRUCK` | Mapping auf die Pkw-Reisezeitmatrix setzen |
| L5 | OOM bei 100 % Population (104k Agenten) in der Tourplanung | `samplingFraction` klein halten oder Heap dimensionieren |

### E15 — actiTopp: "No destination available for WORK" — ENTSCHIEDEN

- **Symptom:** Legacy-Zielwahl bricht ab mit *"No destination available for activity type: WORK"*.
- **Root Cause:** actiTopp entscheidet **selbst**, wer arbeitet/lernt — die eigene
  `needsWorkZone`-Heuristik passte nicht dazu.
- **Fix:** **Jede** Person bekommt ein festes WORK- und EDUCATION-Ziel in `fixedDestination.csv`;
  das Fixziel wird nur konsultiert, wenn die Aktivität tatsächlich auftritt (für Nicht-Arbeitende
  harmlos).
- **Evidenz:** `docs/05-validierung.md`, Abschnitt "KIT actiTopp statt Template-Aktivitätenpläne
  (2026-06-12)", "Befund (kritisch)".

---

## 8. Infrastruktur & Betrieb

### E12 — Parallel-Generierung: Executor-Pool 2→1 (2026-07-02) — ENTSCHIEDEN

- **Symptom:** Parallele Generierungen kollidierten.
- **Root Cause:** Zwei 12-GB-logiTopp-JVMs auf 16-GB-Hardware + das **geteilte**
  `validation/mobitopp-rastatt`-Arbeitsverzeichnis (beide Läufe schreiben in dasselbe Projekt).
- **Fix:** Executor-Pool auf 1; weitere Jobs warten sichtbar als QUEUED.
- **Evidenz:** Commit `bd43219` ("Härtung: serielle Generierung, …"), Code
  `backend/src/main/java/de/synthesis/generation/GenerationService.java`.
- **Was du stattdessen tust:** Pool nicht wieder hochdrehen, ohne beide Ursachen zu lösen
  (RAM-Budget UND per-Lauf-isolierte Lauf-Projekte, vgl. Phase 4 in `docs/06`).

### E13 — OSM-Fetch: In-Run-Memoisierung, bewusst KEIN Disk-Cache — ENTSCHIEDEN

- **Entscheidung:** Das OSM-Straßennetz wird **einmal pro Lauf** geladen (Routing + SUMO teilen
  sich den Abruf via `AtomicReference` in `GenerationService`). Ein Disk-Cache wurde bewusst
  verworfen: `artifacts/` wird nicht geleert und die Region kann zwischen Läufen wechseln →
  Stale-Daten-Risiko.
- **Evidenz:** Commit `bd43219` (Commit-Message nennt die Begründung explizit); Code
  `backend/src/main/java/de/synthesis/generation/GenerationService.java` (Zeile ~143).
- **Was du stattdessen tust:** Kein Disk-Cache "als Quick Win" nachrüsten; wenn Caching nötig
  wird, muss der Schlüssel Region+Parameter enthalten und Invalidierung geklärt sein.

### O1 — Overpass-Rate-Limit (2026-06-21) — OFFEN (mit Workaround)

- **Symptom:** 504/429 bei der schweren Straßennetz-Abfrage im Capstone-Lauf.
- **Root Cause:** Selbstverschuldet — wiederholte schwere Queries gegen das öffentliche Overpass
  innerhalb einer Session (Eigen-Rate-Limiting).
- **Workaround (im Code):** Mirror-Fallbacks (`overpass.kumi.systems`, `overpass.private.coffee`)
  + In-Run-Memoisierung (E13). Fallback-Kette greift sauber (Luftlinien-Matrizen).
- **Evidenz:** `docs/05-validierung.md`, "Capstone"-Abschnitt und "Offene Punkte";
  `docs/06-gta-integration-konzept.md`, "Phase 2 – SUMO" ("Overpass robuster") und Phase 4.
- **Offen bleibt:** Produktions-Empfehlung self-hosted Overpass oder gebündelter
  GraphHopper-PBF-Extrakt (Phase 4, nicht umgesetzt, Stand 2026-07-08).

### E19 — "SynthesisApplication 2.class"-Falle — ENTSCHIEDEN

- **Symptom:** Rätselhafte Klassenkonflikte/Startprobleme im Backend-Build.
- **Root Cause:** macOS-Finder-Duplikat `SynthesisApplication 2.class` im Build-Output
  (Datei mit Leerzeichen im Namen, vom Finder beim Kopieren erzeugt).
- **Fix:** `mvn clean`.
- **Evidenz:** Überlieferter Betriebsbefund des ausscheidenden Engineers (nicht in docs/05–06
  dokumentiert; hier festgehalten, Stand 2026-07-08).
- **Was du stattdessen tust:** Bei unerklärlichen Klassen-/Bean-Duplikaten zuerst
  `find backend/target -name "* 2.*"` und `mvn clean`, bevor du Code verdächtigst.

---

## Wann NICHT nutzen

- **Für "Wie mache ich X heute?"** — das liefern `synthesis-run-and-operate`,
  `synthesis-build-and-env`, `synthesis-config-and-flags`. Dieser Skill erklärt das
  **Warum/damals**, nicht das WAS-TUN.
- **Für aktives Debugging eines neuen Fehlers** — nimm `synthesis-debugging-playbook`
  (und komm hierher zurück, wenn das Symptom einem der Einträge ähnelt).
- **Für Architektur-Sollzustand und Änderungsregeln** — `synthesis-architecture-contract`,
  `synthesis-change-control`.
- **Für die laufende Distanz-/Zielwahl-Arbeit** — Eintrag E1 verweist bewusst nur auf
  `synthesis-distanz-kampagne`; die aktuelle Kampagnenlage steht dort, nicht hier.
- **Für Fachbegriffe der Verkehrsmodellierung** (MiD, MNL, ASC, IPU …) —
  `verkehrsmodellierung-reference`.
- **Nicht als Verbotsliste missverstehen:** ENTSCHEIDEN heißt "nicht ohne neue Fakten neu
  aufrollen". Liegen neue Fakten vor (neue Hardware, neue SUMO-Version mit
  vClass-Mapmatching, neues LLM), ist ein zweiter Anlauf legitim — dann mit Verweis auf den
  Eintrag hier und Update dieses Dokuments.

## Provenienz & Wartung

- **Stand: 2026-07-29.** Verfasst beim Ausscheiden des Principal Engineers und um E20/E22 ergänzt.
- **Primärquellen:** `docs/05-validierung.md` (Phase-0-Befunde, A1–A12, L1–L6, Capstone,
  Außenbeziehungen, populationsynthesis, MobileCity) und `docs/06-gta-integration-konzept.md`
  (Phasen-Chronik F: SUMO/ÖV/GTFS/Rail-Rollback, GTA/LLM, Kalibrierung). Bei Widerspruch
  zwischen diesem Skill und den docs gelten die docs; dann bitte hier nachziehen.
- **Git-Geschichte beginnt erst am 2026-07-02** (`729acdb` "Initial commit") — Ereignisse davor
  (Juni 2026) sind ausschließlich über docs/05–06 belegt, nicht über Commits. Commit-Evidenz
  existiert nur für E12/E13 (`bd43219`) und die Juli-Arbeit (R1: `4ad6382` Doku-Nachtrag).
- **Messzahlen** (RMSE-Werte, Wege/Person·Tag, Fahrtenzahlen, Laufzeiten) sind überlieferte
  Werte vom jeweils genannten Datum auf der damaligen Hardware (M1, 16 GB) — vor Wiederverwendung
  neu messen.
- **Wartungsregel:** Jede neue Sackgasse, jeder Rollback und jede "haben wir probiert,
  verworfen"-Entscheidung bekommt hier einen Eintrag (Symptom → Root Cause → Evidenz →
  Status → Stattdessen) **im selben Commit** wie der Revert bzw. die Entscheidung. Wird ein
  ZURÜCKGEROLLT-Eintrag später erfolgreich umgesetzt (z. B. R1 via Java-Edge-Snapping), Status
  auf ENTSCHEIDEN ändern und den Lösungsweg als Evidenz verlinken.
