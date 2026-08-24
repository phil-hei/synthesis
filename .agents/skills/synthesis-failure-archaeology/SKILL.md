---
name: synthesis-failure-archaeology
description: >
  Failure-Archäologie für SyntheSis — die Chronik aller größeren Untersuchungen, Sackgassen,
  verworfenen Lösungen und Rollbacks (Symptom → Root Cause → Evidenz → Status). Nutze diesen
  Skill, BEVOR du etwas erneut versuchst, das schon einmal scheiterte oder bewusst verworfen
  wurde. Stichworte der Vorfälle: Rail/Tram-ÖV in SUMO zurückgerollt, "No destination edge",
  LLM-Moduswahl mit MiD-Verankerung revertiert, externe Zonen werden nie gewählt,
  pyproj/PEP 668, gtfs2pt/tracemapper, fehlender vType "bus", KIT populationsynthesis/
  Mega-Haushalte, Modelllizenz, Reproduzierbarkeit, GTA-Überproduktion,
  POPtrips-Halbzählung, Kalibrier-Loop-Bugs, Executor-Pool, Overpass-Rate-Limit,
  "No destination available for WORK", OID vs. externe Zonen-ID, "SynthesisApplication 2.class".
  Auch nutzen bei Fragen wie "warum ist X so gebaut?", "wurde Y schon mal probiert?",
  "können wir nicht einfach Z machen?".
---

# SyntheSis Failure-Archaeology — entschiedene Schlachten nicht neu schlagen

Dieses Dokument ist das institutionelle Gedächtnis des Projekts: jede größere Untersuchung,
Sackgasse, verworfene Lösung und jedes Rollback, jeweils als **Symptom → Root Cause → Evidenz →
Status → Was du stattdessen tust**. Bevor du einen "naheliegenden" Ansatz umsetzt, prüfe hier,
ob er schon einmal gescheitert ist — und warum.

**Stand: 2026-08-24.** Primärquellen: `docs/05-validierung.md` (Phase-0-/Adapter-/logiTopp-Befunde)
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
| E21 | `PUT_ACT`/`PUT_EGT` erreichen die Legacy-Moduswahl nicht | Legacy-Formate | BLOCKIERT |
| E22 | `<1 km`-Kalibrierung bei grober Zonierung | Kalibrierung | ENTSCHIEDEN |
| E23 | Gradle-Quellsnapshot hängt im lokalen KIT-Quellbaum | Infrastruktur | ENTSCHIEDEN |
| E24 | Stiller OSM-Fallback konfundiert A/B-Vergleiche | Infrastruktur | ENTSCHIEDEN |
| E25 | `b_tt_* +0,06` als universeller Regionaltransfer | Kalibrierung | ENTSCHIEDEN |
| E26 | Pkw-spezifischer `b_tt`-Split in Freiburg | Kalibrierung | ENTSCHIEDEN |
| E27 | `b_0_1`/`b_1_2` gegen Meter statt Kilometer | Legacy/Kalibrierung | BLOCKIERT |
| E28 | Kurzband + ASC-Modalschutz in Freiburg | Kalibrierung | ENTSCHIEDEN |
| E29 | Direkter Langwegbonus aus Gesamt-Log-Odds | Kalibrierung | ENTSCHIEDEN |
| E30 | `tripId` als Cross-run-Wegschlüssel | Diagnose/Kalibrierung | ENTSCHIEDEN |
| E31 | Aktivitätsnachbarschaft als Langweg-Dosisformel | Kalibrierung | ENTSCHIEDEN |
| E32 | Binäre Choice-Set-Eligibility als Unterwirkungsursache | Kalibrierung | ENTSCHIEDEN |
| E33 | Aktivitätstypmittel als Erklärung der Langwegmassen-Heterogenität | Kalibrierung | ENTSCHIEDEN |
| E34 | Ausgangszelle als dominante Within-Zweck-Erklärung | Kalibrierung | ENTSCHIEDEN |
| E35 | Exaktes Modusset als Zerlegungsdimension | Diagnose/Kalibrierung | ENTSCHIEDEN |
| E36 | Einzelne Modusverfügbarkeit als dominante Resterklärung | Kalibrierung | ENTSCHIEDEN |
| E37 | Vierstufiges Altersband als sparse-cell-Zerlegung | Diagnose/Kalibrierung | ENTSCHIEDEN |
| E38 | Einzelne Altersindikatoren als dominante Resterklärung | Kalibrierung | ENTSCHIEDEN |
| E39 | Skalare Fixziel-Ankerdistanz als dominante Resterklärung | Diagnose/Kalibrierung | ENTSCHIEDEN |
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

### E23 — Gradle-Quellsnapshot hängt im lokalen KIT-Quellbaum — ENTSCHIEDEN

- **Symptom/Frage:** Im präregistrierten 3.249-Zonen-A42 blieb die externe
  Simulation nach dem Legacy-Export länger als 31 Minuten bei Gradle
  `compileJava` stehen; logiTopp erzeugte keine Ergebnisdatei.
- **Befund:** Der Gradle-Daemon verbrauchte kaum CPU. Ein Thread-Dump zeigte
  `UnixNativeDispatcher.fdopendir` über `DirectorySnapshotter`; `lsof`
  lokalisierte den aktiven Quellsnapshot unter
  `validation/mobitopp-rastatt/src/main/java/edu/kit/ifv/mobitopp/populationsynthesis/mobilityprovider`.
  Selbst einfache Verzeichnisabfragen auf diesem gitignorierten Teilbaum
  blockierten. Der vorherige 13-Matrix-Spike war dreimal in unter 95 Sekunden
  und mit 1.376,9 MiB Heap erfolgreich; Matrixskalierung und OOM sind daher
  nicht die Ursache.
- **Verworfener Scheinfix:** Nicht den Lauf weiter warten lassen, B42 parallel
  starten oder `validation/` ungeprüft löschen. Der konfigurierte 30-Minuten-
  Timeout terminierte den im Dateisystemaufruf blockierten Wrapper nicht
  zuverlässig.
- **Status/Regel:** A42/B42 ist **unentscheidbar**. Den lokalen Zustand zuerst
  erhalten, dann `mobitopp-rastatt` aus dem gepinnten Runtime-Vertrag mit
  `scripts/bootstrap-runtime.sh kit` rekonstruieren und A42 wiederholen. B42,
  weitere Seeds und Reglerentwicklung bleiben bis zu einem vollständigen A42
  gesperrt. Evidenz: `docs/05-validierung.md`, Abschnitt
  „Großraum × feine Zonierung (2026-08-11)“.
- **Rekonstruktions-Nachtrag (2026-08-11):** Der frische Harness-Clone macht den
  problematischen Pfad wieder sofort lesbar, reicht aber nicht für einen
  reproduzierbaren Bootstrap. Upstream-`build.gradle` (`6a23e17a…`, 188 CRLF-
  Zeilen) entspricht nicht dem vom logiTopp-Vertragsinstaller vorausgesetzten
  Vorzustand (`fecda2bf…`, 60 Zeilen); der notwendige Transformationsschritt ist
  nicht versioniert. Parallel bleibt der bestehende actiTopp-Clone beim
  Gradle-Dateihashing auffällig langsam. Hash-Guards nicht umgehen: verbleibende
  KIT-Clones gesichert rekonstruieren und die fehlende Baseline-Transformation
  zuerst als prüfbaren Vertrag ergänzen.
- **Fixture-Folgewirkung (2026-08-13):** Der Re-Clone nahm auch die Ergebnisdateien
  des alten Harness mit. Dadurch gingen `SumoRunnerTest`, `PtIntegrationTest` und
  ein Teilfall von `LogitoppDemandAdapterTest` **still in SKIPPED** — sie hängen an
  `validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv`
  (und optional `planned-tours.csv`), nicht am Vertrag. Wer nach einem Re-Clone nur
  auf „Suite grün" schaut, hält den Verlust der SUMO-/ÖV-Abdeckung für Normalzustand.
  Diese Abhängigkeit ist von E23 trennbar: Beide Dateien lassen sich bit-identisch
  aus `mobitopp-rastatt.backup-20260811-e23` zurückkopieren (SHA-256 `35a4cc63…`
  bzw. `e4f2eb9f…`), danach 127 Tests, 0 Fehler, 0 Skips. **Kein Hash-Guard wird
  dabei berührt** — aber das ist ausdrücklich **kein** Fix für E23: Der
  Bootstrap-Vertrag bleibt kaputt, `preflight.sh full` meldet weiterhin den
  fehlenden logiTopp-Harness-Vertrag, und A42/B42 bleibt gesperrt. Eine
  zurückkopierte Fixture ist ein Testeingang, kein Laufzeitvertrag.
- **Auflösung (2026-08-13):** Der dokumentierte Reparaturweg war eine Sackgasse und
  ist **nicht** zu versuchen: Die erwartete Baseline `fecda2bf…`/`7bc76816…` lässt
  sich nicht rekonstruieren, weil der Altpatch mit `--ignore-space-change` angewandt
  wurde und rückwärts scheitert; Stash und dangling Blobs existieren nicht. Der
  Vertrag wurde deshalb auf den **Upstream-Stand** umgestellt statt auf einen
  Zwischenstand. Zwei Root Causes kamen dabei heraus, die vorher niemand auf dem
  Schirm hatte: (1) ein **Gradle-Wrapper-Upgrade 6.3 → 9.5.0**, das von keinem
  Hash-Guard erfasst war — Upstream-Gradle 6.3 kann `SYNTHESIS_KIT_JAVA=25` gar
  nicht bauen, der Vertrag konnte aus einem sauberen Clone also nie funktionieren;
  (2) **58 gitignorierte Pflicht-Dateien** (42 DCML-generierte Klassen, 6 `.xmi`,
  8 `.gen`, 2 Config-Varianten), ohne die der Harness nicht kompiliert, weil der
  Vertrags-Build das Codegen-Plugin entfernt. Beides liegt jetzt versioniert in
  `legacy/logitopp-returns-v1/` (strikte Patches + `harness-artifacts/` mit
  SHA-256-Manifest). Belegt: `compileJava` des rekonstruierten Harness **3 s**
  statt >31 min Hänger, `scripts/preflight.sh full` 0 Fehler.
- **Regel für die Zukunft:** Keinen Patch mit `--ignore-space-change` in einen
  Vertrag aufnehmen. Er ist nicht rückrechenbar und erzeugt genau diese Sackgasse.
  Ebenso: Wenn eine Datei zum Bauen nötig ist, aber in der `.gitignore` des
  Fremdrepos steht, gehört sie ins Vertrags-Artefaktbündel — sonst ist der
  Bootstrap nur scheinbar reproduzierbar.

### E24 — Stiller OSM-Fallback konfundiert A/B-Vergleiche — ENTSCHIEDEN

- **Symptom/Frage:** Zwei Läufe desselben präregistrierten Szenarios, die sich nur in
  einem Flag unterscheiden sollten, lieferten unterschiedliche Haushaltszahlen
  (2.000 vs. 1.595).
- **Befund:** Die Overpass-Abfrage nach Siedlungs-`place`-Nodes lief im ersten Lauf
  ins Timeout (OSM-Stufe 297,4 s statt 22,4 s) und fiel auf POI-Dichte zurück. Die
  Pipeline behandelt das als Erfolg: Stufe **DONE**, kein FAILED, kein SKIPPED — der
  Unterschied steht ausschließlich als `dataQuality.populationDistribution`
  (`poi-density` vs. `osm-places`) im Manifest. Damit unterscheiden sich die
  Wohnstandorte, also genau die Größe, die Wegdistanzen bestimmt.
- **Warum das gefährlich ist:** Der Fallback ist als Robustheitsmaßnahme richtig und
  soll bleiben. Für einen A/B-Vergleich macht er aber aus einem Ein-Faktor-Kontrast
  unbemerkt einen Zwei-Faktor-Kontrast. Wer nur auf „beide Läufe DONE" schaut, wertet
  Kriterien auf einem ungültigen Test aus.
- **Was du stattdessen tust:** Vor jedem A/B-Vergleich die `attractivities.csv` beider
  Arme **hashen** und vergleichen — das ist der belastbare Test. Manifest-Felder
  (`dataQuality.populationDistribution`, `counts.households`) sind nur ein Vorfilter:
  Sie fangen den groben Quellenwechsel, aber nicht jede Datenabweichung. Ungültige
  Läufe markieren, nicht löschen. Evidenz: `docs/05-validierung.md`, Abschnitt
  „Großraum-A/B durchgeführt (2026-08-15)".
- **⚠ Korrektur (2026-08-16):** Der frühere Rat „beide Arme in derselben Backend-Session
  fahren, dann sind die OSM-Daten garantiert gleich" ist **widerlegt**. Die
  Memoisierung deckt die Overpass-Abfragen nicht zuverlässig: In einer einzigen Session
  erhielten drei Läufe denselben Attraktivitäts-Hash, zwei weitere je einen anderen —
  einmal mit 866 statt 868 Siedlungsknoten (Abfrage 50 s statt 8–10 s), einmal mit
  gleicher Knotenzahl und trotzdem anderem Inhalt. **Die Knotenzahl belegt keine
  Datengleichheit.** Die Pipeline meldet das nicht: Stufen bleiben DONE,
  `populationDistribution` bleibt `osm-places`. Gleiche Session hilft, garantiert aber
  nichts — nur der Hash-Vergleich entscheidet. Der saubere Weg für Kampagnen ist, die
  OSM-Eingabe einmal einzufrieren und allen Armen als Datei vorzugeben. Evidenz:
  `docs/05-validierung.md`, Abschnitt „Drei-Seed-Wiederholung (2026-08-16)".
- **✅ Abhilfe im Produkt (2026-08-20):** Beide Teile sind jetzt gebaut. Jeder Lauf mit
  Live-Abruf schreibt die tatsächlich verwendeten OSM-Daten als
  `artifacts/osm-data.json` und legt deren SHA-256 zusammen mit dem der erzeugten
  `attractivities.csv` unter `manifest.json` → `inputProvenance` ab. Der A/B-Check ist
  damit maschinell: `inputProvenance.attractivities.sha256` beider Arme vergleichen,
  statt hinterher Logs zu rekonstruieren. Und dieselbe Datei nach `uploads/` eines
  zweiten Szenarios kopiert **friert die Eingabe ein** — verifiziert: Lauf 2 meldet
  `scenario-upload:osm-data.json` mit identischem OSM-Hash und identischen
  Attraktivitäten. Für Kampagnen gilt ab jetzt: ersten Arm live fahren, dessen
  `osm-data.json` allen weiteren Armen vorgeben.
- **✅ Prüfung im Produkt (2026-08-21):** Das Straßennetz ist gleichgezogen
  (`inputProvenance.roadNetwork`), und der **Szenario-Vergleich prüft die Hashes
  selbst**: Weichen die verglichenen Läufe in OSM-Daten, Straßennetz oder
  Attraktivitäten ab, erscheint eine sichtbare Warnung, dass Kennzahlunterschiede
  nicht allein den Parametern zuzuschreiben sind. Läufe ohne hinterlegte Herkunft
  werden als **nicht belegt** ausgewiesen statt stillschweigend als gleich — genau
  der Fall der Seed-43-Arme. Damit ist die Regel oben keine Handarbeit mehr, sondern
  greift am Ort der Auswertung.

### E25 — `b_tt_* +0,06` als universeller Regionaltransfer — ENTSCHIEDEN

- **Symptom/Frage:** Der feste Term bestand im Großraum Rastatt alle Gates über
  Seeds 42/43/44. Ist `+0,06` deshalb ein regionsunabhängiger Produktwert?
- **Evidenz:** Nein. Die vorab festgeschriebene Freiburg-Probe T-C42/T-D42
  verwendete byte-identische OSM-, Attraktivitäts-, Populations-, Matrix-,
  Logistik- und Legacy-Eingaben. Der produktive Flag-Wechsel verschob die Verteilung
  in der erwarteten Richtung (`1–2 km` 18,35→11,90 %, `>=10 km`
  10,16→14,00 %, Ø 4,58→5,38 km), verschlechterte aber den Distanzband-RMSE
  **0,0611→0,0644**. Das absolute 20-%-Fernweg-Gate scheiterte; `>20 km` stieg
  nur 2,11→2,42 % gegen MiD 13 %. Drei von sieben Vorab-Kriterien scheiterten.
- **Root Cause:** Bestätigt ist die Mechanik des Reisezeit-Beta-Hebels, nicht die
  Übertragbarkeit seiner Dosis. Ausgangsverteilung, Gebietsgeometrie und
  Attraktivitätsstruktur unterscheiden sich regional; ein fester additiver Term
  kann deshalb dieselbe Richtung bei falscher Zielgröße liefern.
- **Status/Regel:** Seeds 43/44 wurden nach Stop-Regel nicht gestartet;
  `correctDistanceDecay` bleibt opt-in. `+0,06` nicht als universellen Default
  befördern und dieselbe Freiburg-Serie nicht ohne neue Hypothese wiederholen.
  Die anschließend präregistrierte Grenzsonde schöpfte den gemeinsamen Offset
  bis `+0,09` aus; dort bleibt `b_tt_put=-0,0006` gerade noch negativ, aber
  `>=10 km` endet bei 18,80 %, RMSE bei 0,0685 und `1–2 km` bei 8,29 %.
  Größere gemeinsame Dosen würden die Nutzensemantik umkehren und sind ebenso
  gesperrt wie weitere Seeds. Stattdessen einzelne Koeffizienten trennen oder einen
  hierarchisch/regional parametrisierten Hebel untersuchen. Evidenz:
  `docs/05-validierung.md`, Abschnitt „Mehrregionen-Transferprobe Freiburg
  (2026-08-17)"; Schwellen vorab in Commits `bc9a19a` und `68b4baa`.

### E26 — Pkw-spezifischer `b_tt`-Split in Freiburg — ENTSCHIEDEN

- **Frage:** Kann die fehlende Fernwegwirkung dem Pkw zugeordnet werden, ohne den
  bereits schwachen ÖV-Zeitkoeffizienten weiter Richtung null zu verschieben?
- **Evidenz:** Commit `f337233` präregistrierte einen bytegleich orchestrierten
  PC-0060-Kontrollarm und Pkw-Gesamtoffsets `+0,09`/`+0,115`; `put`/`ped`/`bike`
  blieben auf `+0,06`. `>=10 km` reagiert stark und monoton
  **14,00→20,98→33,85 %**, Ø Weglänge 5,38→6,38→8,60 km. Trotzdem steigt der
  Distanzband-RMSE **0,0644→0,0675→0,0729**, während `1–2 km` auf 7,89/4,61 %
  fällt. Der ÖV-Anteil wächst trotz unverändertem Zielwahl-`b_tt_put=-0,0306`
  auf 29,29/37,83 %; am Grenzarm verschlechtert sich der Modal-RMSE um 0,053129.
- **Root Cause:** Die Zielwahl-`b_tt_*` steuern die Erreichbarkeit von Zielen, nicht
  den realisierten Modus isoliert. Öffnet der Pkw-Term weiter entfernte Ziele, wählt
  die nachgelagerte Moduswahl für diese Wege überproportional ÖV. Der Pkw-Split
  behebt damit einen Randwert und beschädigt zugleich Bandform und Modalverteilung.
- **Status/Regel:** Keine höheren Pkw-Dosen, weiteren Seeds oder Scans anderer
  Modus-Teilmengen ohne neue Mechanismushypothese. Ein weiterer Anlauf muss
  mindestens das `1–2 km`-Band explizit kontrollieren (`b_1_2`-Elastizität auf
  PC-0090) und einen getrennten Modal-Schutz vorsehen; alternativ regional oder
  hierarchisch schätzen. Vollständige Zahlen: `docs/05-validierung.md`, Abschnitt
  „Freiburg-Split-Sonde (2026-08-17)".

### E27 — `b_0_1`/`b_1_2` gegen Meter statt Kilometer — BLOCKIERT

- **Symptom:** Die präregistrierte Freiburg-Sonde `b_1_2 +0,50` änderte trotz
  nachweislich gepatchter Zweckdateien kein einziges Ergebnisbyte; MB-000,
  MB-050 und PC-0090 liefern exakt dieselben 42.990 Wege und Kennzahlen.
- **Root Cause:** `DestinationChoiceUtilityFunction` enthält zwar die Terme
  `b_0_1 * [0 < DISTANCE <= 1]` und `b_1_2 * [1 < DISTANCE <= 2]`.
  `DefaultDestinationChoiceHelper.getDISTANCE` gibt aber
  `ImpedanceIfc.getDistance` unverändert zurück; die Run-Config bindet dafür
  `other/CAR_DIS.mtx` in **Metern**. Freiburgs kleinster Matrixwert beträgt
  411,918 m. Andere Legacy-Stellen teilen für Kilometer explizit durch 1.000,
  womit die Fehlverdrahtung unabhängig bestätigt ist.
- **Verworfener Scheinfix:** Nicht einfach größere `b_0_1`-/`b_1_2`-Dosen testen:
  der Indikator bleibt null. Auch `CAR_DIS.mtx` nicht global auf Kilometer skalieren;
  dieselbe Matrix speist weitere Distanzkonsumenten und Ergebnisverträge.
- **Status/Regel:** Multi-Band-Kalibrierung ist blockiert. `validation/` nicht direkt
  verändern. Der nächste Anlauf braucht eine versionierte, experimentell
  aktivierbare Korrektur des externen Harness (`DISTANCE / 1000`) und neue
  präregistrierte Baselines, weil die bestehenden Kurzband-Parameter dadurch
  erstmals aktiv werden. Evidenz: `docs/05-validierung.md`, Abschnitt
  „Freiburg-Multi-Band-Sonde (2026-08-17)"; Präregistrierung Commit `1ae34ad`.
- **Spike-Nachtrag (2026-08-17):** Eine isolierte Harness-Kopie mit ausschließlich
  `getDistance(...) / 1000.0d` bestätigt den Einheitenmechanismus: KM-000 hebt
  `1–2 km` von 7,89 auf 9,67 %, KM-050 mit `b_1_2 +0,50` auf 12,09 %. Der
  zusätzliche Effekt von **+2,42 pp** verfehlt jedoch den in Commit `3382e17`
  vorab gesetzten +3-pp-Gate. Daher keine `+0,75`-Dosis, Seeds oder
  Produktimplementierung; produktive Runtime und `validation/` bleiben
  unverändert. Evidenz: `docs/05-validierung.md`, Abschnitt
  „Freiburg-Kilometer-Vertragsspike (2026-08-17)".

### E28 — Kurzband + ASC-Modalschutz in Freiburg — ENTSCHIEDEN

- **Frage:** Reicht die aus der korrigierten Kurzband-Elastizität abgeleitete
  Einzeldosis zusammen mit dem bestehenden ASC-Modalkalibrator für alle
  Freiburg-Produktgates?
- **Evidenz:** Commit `7bbcc2d` fixierte `b_1_2 +0,895` und alle Gates vorab.
  Der echte Harness trifft `1–2 km` mit 14,12 % roh und 14,08 % nach Modalschutz.
  Modal-RMSE sinkt 0,113275→0,017562, ÖV 27,42→10,70 %. Dennoch bleiben
  `>=10 km` bei 19,69/19,68 % und Distanzband-RMSE bei 0,055140/0,055292;
  beide verfehlen die unveränderten Gates 20 % beziehungsweise 0,05.
- **Root Cause:** `b_1_2` verteilt Ziele in das 1–2-km-Band um, erzeugt aber
  keine fehlenden Fernziele. Die nachgelagerte ASC-Kalibrierung korrigiert die
  Moduswahl und lässt die sechs Distanzbänder nahezu unverändert; sie kann das
  verbleibende `>20 km`-Defizit von rund neun Prozentpunkten nicht heilen.
- **Status/Regel:** Keine weitere `b_1_2`-Dosis, Seeds oder Produktimplementierung.
  Ein neuer Anlauf muss einen eigenständigen Langwegmechanismus präregistrieren;
  gemeinsame `b_tt`-Offsets, Pkw-Splits und Kurzband-Scans sind durch E25–E28
  ausgeschöpft. Evidenz: `docs/05-validierung.md`, Abschnitt
  „Freiburg-Joint-Kompositionssonde (2026-08-17)".

### E29 — Direkter Langwegbonus aus Gesamt-Log-Odds — ENTSCHIEDEN

- **Frage:** Kann ein analytisch aus J-RAW `>20 km` 3,73 % und MiD 13 %
  abgeleiteter Nutzenbonus `logit(0,13)-logit(0,0373119)=+1,35` das Langband
  ohne Parameterscan direkt herstellen?
- **Evidenz:** Der in Commit `a949eb2` präregistrierte, isolierte Harness bestand
  den externen Lauf mit EXIT 0. `>20 km` steigt **3,73→7,17 %** und damit nur
  +3,44 statt mindestens +5 pp; das absolute Gate `[10;16] %` scheitert.
  Zugleich bestehen die übrigen Schutzwerte: `<1 km` 20,75 %, `1–2 km`
  13,94 %, `>=10 km` 22,06 %, Distanzband-RMSE 0,042661 und 3,0706
  Wege/Person·Tag.
- **Root Cause der Fehlprognose:** Die Gesamtmengen-Log-Odds-Rechnung setzt
  voraus, dass alle End-to-End-Ziele dieselbe gepatchte MNL-Utility durchlaufen.
  `FixedLocationDestinationChoice` gibt HOME-Ziele und personengebundene
  Fixziele jedoch vor dem Utility-Aufruf zurück. Der Bonus wirkt nur auf freie
  Zielentscheidungen und wird in der Gesamtmarginale verdünnt.
- **Status/Regel:** Der `ModeChoiceCalibrator` wurde nach Stop-Regel nicht
  gestartet. Die in Commit `c2b283b` präregistrierte FD-000-Nachmessung zählt
  bei byteidentischem J-RAW-Demand 19.138 von 42.989 Entscheidungen als frei
  (**44,52 %**; HOME 43,55 %, FIXED 11,93 %) und besteht damit das feste
  `[30;45] %`-Erklärungsfenster. Der Bypass ist als wesentliche Verdünnung
  gestützt, erklärt die Unterwirkung aber noch nicht vollständig: Die Quote
  liegt 7,41 pp über dem LT-135-Wirkungsverhältnis 37,11 %. Keine zweite
  Bonusdosis, Seeds oder Produktimplementierung; der nächste Spike muss den
  Entscheidungsweg direkt mit der gewählten Distanz verknüpfen. Eine bloße
  Nachskalierung von `+1,35` bleibt gesperrt. Evidenz:
  `docs/05-validierung.md`, Abschnitte „Freiburg-Langweg-Nutzenbonusspike" und
  „Freiburg-Freiziel-Instrumentierung (2026-08-17)".

### E30 — `tripId` als Cross-run-Wegschlüssel — ENTSCHIEDEN

- **Frage:** Lassen sich J-RAW und LT-135 über `(tripId, legId)` semantisch
  paaren, um die Distanzwirkung einer freien Zielwahl auf Hin- und Folgeweg zu
  lokalisieren?
- **Evidenz:** Die in Commit `1b8ec51` präregistrierte PX-135-Auswertung findet
  eindeutige Schlüssel und 42.988 numerische Gegenstücke für 42.989
  J-RAW-Wege. Das vorgeschaltete Semantik-Gate scheitert trotzdem: Nur 15.982
  Paare (**37,18 %**) haben denselben Ziel- und Vorgängeraktivitätscode statt
  mindestens 99,9 %. Fast vollständige ID-Überlappung ist hier also ein
  irreführendes Scheinsignal.
- **Root Cause:** `tripId` verhält sich empirisch als laufslokale Weg-ID, nicht
  als stabiler fachlicher Schlüssel: Dieselben Nummern bezeichnen in den beiden
  Läufen überwiegend verschiedene Aktivitäten. Die interne Vergabelogik wurde
  für dieses Urteil nicht weiter aufgelöst; die beobachtete Semantik reicht aus,
  um den Join zu verwerfen.
- **Status/Regel:** PX-135 ist unentscheidbar; seine nachgelagerten
  Gruppenkennzahlen werden nicht interpretiert und es gibt keinen alternativen
  Post-hoc-Join. Cross-run-Vergleiche benötigen einen vorab bewiesenen stabilen
  semantischen Schlüssel. Für den Langwegspike ist die sichere Alternative,
  Entscheidungsweg und gewählte Distanz im selben Lauf gemeinsam zu
  protokollieren. Evidenz: `docs/05-validierung.md`, Abschnitt
  „Freiburg-Wegeexpositionsanalyse (2026-08-17)".

### E31 — Aktivitätsnachbarschaft als Langweg-Dosisformel — ENTSCHIEDEN

- **Frage:** Erklärt die direkte oder Folge-Nachbarschaft zu einer freien
  Aktivität die LT-135-Unterwirkung quantitativ hinreichend, wenn beide Läufe
  ohne Cross-run-Join separat aggregiert werden?
- **Evidenz:** Die in Commit `a08eb7a` präregistrierte PM-135-Auswertung besteht
  alle technischen Gates. Exponiert sind 35.389 von 42.989 J-RAW-Wegen
  (**82,32 %**). Sämtliche 1.480 zusätzlichen `>20 km`-Wege liegen in dieser
  Marginale; die 7.600 nicht exponierten Wege bleiben in Langanteil 7,8158 %
  und Ø Distanz 8,6272 km exakt unverändert. So rekonstruiert
  `82,32 % × 4,1823 pp` den globalen Zuwachs +3,4429 pp.
- **Warum dennoch verworfen:** Der vorab unverändert übernommene Stärke-Gate
  fordert mindestens +5 pp innerhalb der exponierten Marginale. Beobachtet
  werden nur 2,854→7,036 %, also **+4,182 pp**. Aktivitätsnachbarschaft
  lokalisiert den Effekt vollständig, liefert aber keine hinreichende
  Dosisformel.
- **Status/Regel:** Keine höhere Bonuskonstante, Seeds, Modalkalibrierung oder
  Produktimplementierung. Ein neuer Mechanismus muss zuerst same-run messen,
  welcher Anteil freier Choice-Sets überhaupt attraktive `>20 km`-Alternativen
  enthält. Evidenz: `docs/05-validierung.md`, Abschnitt
  „Freiburg-Wegeexpositionsmarginalen (2026-08-17)".

### E32 — Binäre Choice-Set-Eligibility als Unterwirkungsursache — ENTSCHIEDEN

- **Frage:** Fehlen einem wesentlichen Teil der freien Entscheidungen überhaupt
  zweckspezifisch attraktive `>20 km`-Alternativen mit positiver
  MNL-Wahrscheinlichkeit, sodass dies die Unterwirkung von `+1,35` erklärt?
- **Evidenz:** Die in Commit `f8064f6` präregistrierten CE-000/CE-135-Läufe
  bestehen alle technischen Gates und reproduzieren J-RAW/LT-135 byteidentisch.
  Entgegen dem Fenster `[65;85] %` sind **19.138 von 19.138** freien
  Entscheidungen eligible. Die mittlere MNL-Masse stimmt mit der realen
  Auswahl überein (CE-000 2,3376 % vs. 2,3722 %; CE-135 7,0603 % vs.
  6,8868 %). Der exakt je Basis-Choice-Set berechnete `+1,35`-Kontrafakt
  6,6643 % liegt nur 0,3960 pp von der wirklichen CE-135-Masse entfernt.
- **Root Cause:** Die homogene Logit-Transformation des aggregierten
  J-RAW-Anteils ignoriert die starke Heterogenität der einzelnen
  Ausgangsmassen. Binäre Verfügbarkeit ist zu grob; bei `SHOPPING_OTHER`
  beträgt die Basismasse trotz 100 % Eligibility nur 0,0156 %.
- **Status/Regel:** Choice-Set-Ineligibility ist als Erklärung verworfen. Keine
  neue Bonusdosis, Seeds oder Produktimplementierung. Vor einem weiteren
  Mechanismus darf nur die per-Choice-Set-Massenverteilung in neu
  präregistrierten festen Klassen gemessen werden. Evidenz:
  `docs/05-validierung.md`, Abschnitt „Freiburg-Choice-Set-Eligibility
  (2026-08-17)".

### E33 — Aktivitätstypmittel als Heterogenitätserklärung — ENTSCHIEDEN

- **Frage:** Entsteht die starke Streuung der CE-000-Langwegmasse überwiegend
  zwischen den sechs freien Aktivitätstypen, sodass zweckspezifische Konstanten
  die nächste hinreichende Strukturerklärung wären?
- **Evidenz:** Der vorab fixierte PD-000-Lauf ist bonusfrei, technisch gültig
  und erneut byteidentisch zu J-RAW. 60,3511 % der 19.138 Entscheidungen haben
  `p <1 %` und tragen nur 3,7926 % der Gesamtmasse. Dagegen tragen 4,3369 % mit
  `p >=10 %` bereits 42,5757 %. Die feste Varianzzerlegung ergibt
  `SST=75,1796550`, `SSB=5,1062364` und `SSW=70,0734186`: **93,2080 %** liegen
  innerhalb der Aktivitätstypen, nur 6,7920 % zwischen ihnen.
- **Root Cause:** Zweckmittel verdecken die dominante innere Mischung.
  SHOPPING_DAILY liegt zu 95,76 % unter 1 %, enthält aber seltene
  Hochmassenfälle und erzeugt 45,8203 der 70,0734 Within-SSE.
- **Status/Regel:** Ein konstanter Zweckbonus ist keine hinreichende Erklärung
  der Heterogenität. Keine Dosis oder Produktimplementierung. Der nächste
  zulässige Same-run-Spike muss vorab die Ausgangszonenkomponente innerhalb der
  Zwecke zerlegen. Evidenz: `docs/05-validierung.md`, Abschnitt
  „Freiburg-Langwegmassenverteilung (2026-08-17)".

### E34 — Ausgangszelle als dominante Within-Zweck-Erklärung — ENTSCHIEDEN

- **Frage:** Erklärt die vorherige Zone mindestens 70 % der von PD-000
  lokalisierten Within-Zweck-Varianz der Langwegmasse?
- **Evidenz:** OZ-000 ist technisch gültig und erneut byteidentisch zu J-RAW.
  Um sparse-cell-Überanpassung zu begrenzen, waren nur 1.293
  `(Aktivitätstyp, Ausgangszone)`-Zellen mit `n>=5` zugelassen; sie decken
  14.748/19.138 = 77,0613 % ab. Von ihrer Within-Zweck-SSE 50,3748793 liegen
  10,1145501 zwischen Ausgangszellen und 40,2603292 innerhalb identischer
  Zellen: **20,0786 % erklärt, 79,9214 % Rest**.
- **Warum verworfen:** Der vorab gesetzte 70-%-Gate wird weit verfehlt. Bei
  SHOPPING_DAILY, dem größten absoluten Varianztreiber, erklärt die Zone nur
  9,54 %. Höhere Anteile bei einzelnen Freizeitzwecken ändern das
  Gesamturteil nicht.
- **Status/Regel:** Keine geografische Folgesonde, Dosis oder
  Produktimplementierung. Der nächste Same-run-Spike muss Personen-/
  Modusverfügbarkeits- oder situative Merkmale innerhalb identischer
  Zweck-Zonen-Zellen vorab zerlegen. Evidenz: `docs/05-validierung.md`,
  Abschnitt „Freiburg-Ausgangszonenzerlegung (2026-08-17)".

### E35 — Exaktes Modusset als Zerlegungsdimension — ENTSCHIEDEN

- **Frage:** Erklärt die vollständige Sieben-Modi-Verfügbarkeitssignatur
  mindestens 50 % der nach OZ-000 verbleibenden Restvarianz?
- **Evidenz:** AV-000 reproduziert alle 19.138 Entscheidungen sowie CE-000,
  PD-000 und OZ-000 exakt. Die Verfügbarkeitssignatur erzeugt 6.459
  Unterzellen, davon 2.529 Singletons. Nur 939 Unterzellen mit `n>=5` decken
  8.618/19.138 = 45,0308 % der Entscheidungen ab; präregistriert waren
  mindestens 60 %. Die Varianzidentität schließt numerisch exakt.
- **Warum unentscheidbar:** Der technische Abdeckungstest scheitert vor dem
  Mechanismus-Gate. Der rohe Erklärungsanteil von 3,7650 % beschreibt nur die
  selektierte Teilpopulation und darf nicht als Widerlegung des Mechanismus
  gelesen werden.
- **Status/Regel:** Mindestzellgröße nicht post hoc senken und
  Modusverfügbarkeit nicht verwerfen. Nächster Same-run-Spike nur als neu
  präregistrierte, gröbere komponentenweise Verfügbarkeitszerlegung; keine
  Dosis oder Produktimplementierung. Evidenz: `docs/05-validierung.md`,
  Abschnitt „Freiburg-Modusverfügbarkeitszerlegung (AV-000, 2026-08-24)".

### E36 — Einzelne Modusverfügbarkeit als dominante Resterklärung — ENTSCHIEDEN

- **Frage:** Erklärt mindestens eine der sieben separat betrachteten binären
  Modusverfügbarkeiten mindestens 50 % des OZ-000-Rests?
- **Evidenz:** AC-000 reproduziert CE-/PD-/OZ-/AV-000 exakt. Alle sieben
  Komponenten bestehen jeweils mindestens 60 % Entscheidungs- und
  Restvarianzabdeckung; alle Identitäten schließen numerisch exakt. `CAR`
  erklärt mit 4,3735 % am meisten. `PASSENGER`, `PEDESTRIAN`,
  `PUBLICTRANSPORT` und `CARSHARING_FREE` liegen jeweils bei 2,1291 %,
  `CARSHARING_STATION` bei 1,1036 % und `BIKE` bei 1,0205 %.
- **Warum verworfen:** Der stärkste Einzelanteil verfehlt den 50-%-Gate um
  mehr als den Faktor elf. Identische Werte einiger Komponenten entstehen aus
  identischen Gruppen und dürfen nicht addiert werden.
- **Status/Regel:** Keine weitere Verfügbarkeitssonde, Dosis oder
  Produktimplementierung. Der nächste Same-run-Spike darf nur die tatsächlich
  in der Zielwahl-Utility verwendeten Personenmerkmale vorab zerlegen.
  Evidenz: `docs/05-validierung.md`, Abschnitt
  „Freiburg-Verfügbarkeitskomponenten (2026-08-24)".

### E37 — Vierstufiges Altersband als sparse-cell-Zerlegung — ENTSCHIEDEN

- **Frage:** Erklärt mindestens eine tatsächlich in der Zielwahl-Utility
  verwendete Personenkomponente mindestens 50 % des OZ-000-Rests?
- **Evidenz:** PC-000 reproduziert alle früheren Aggregate exakt. Sechs
  binäre Komponenten bestehen Entscheidungs- und SSE-Abdeckung und erklären
  einzeln höchstens 6,8995 %. `AGE_BAND` deckt zwar 78,5814 % der
  OZ-Restvarianz, aber nur 9.759/19.138 = 50,9928 % statt mindestens 60 % der
  Entscheidungen ab.
- **Warum teilweise unentscheidbar:** Der technische Entscheidungsgate des
  vierstufigen Altersbands scheitert vor der Interpretation; sein roher Anteil
  9,5722 % ist weder positiver noch negativer Mechanismusbeleg. Die sechs
  technisch gültigen binären Personenkodierungen sind dagegen jeweils klar
  nicht dominant.
- **Status/Regel:** `n>=5` nicht senken und Alterskodierung nicht verwerfen.
  Ein neuer Same-run-Spike darf ausschließlich die drei schon in der Utility
  getrennten binären Altersindikatoren vorab einzeln zerlegen. Keine Dosis
  oder Produktimplementierung. Evidenz: `docs/05-validierung.md`, Abschnitt
  „Freiburg-Personenkomponenten (2026-08-24)".

### E38 — Einzelne Altersindikatoren als dominante Resterklärung — ENTSCHIEDEN

- **Evidenz:** AI-000 reproduziert alle früheren Aggregate exakt; alle drei
  Indikatoren bestehen beide 60-%-Gates. `AGE_0_17` erklärt 5,5282 %,
  `AGE_50_69` 1,5368 % und `AGE_70_120` 0,7127 % statt mindestens 50 %.
- **Status/Regel:** Altersindikatoren sind nicht dominant. Keine weitere
  Personen-/Verfügbarkeitssonde oder Dosis; neue Mechanismusforensik ist
  nötig. Evidenz: `docs/05-validierung.md`, Abschnitt
  „Freiburg-Altersindikatoren (2026-08-24)".

### E39 — Skalare Fixziel-Ankerdistanz als dominante Resterklärung — ENTSCHIEDEN

- **Frage:** Erklärt `log1p` der Entfernung vom aktuellen Ausgang zum nächsten
  personengebundenen Fixziel mindestens 50 % des OZ-000-Rests? Der Kandidat ist
  situativ und folgt direkt aus den `*_FIX`-Termen der ausgeführten Utility;
  Zeit ist wegen ganztägig identischer Matrixdateien kein unabhängiger Hebel.
- **Evidenz:** FA-000 reproduziert Utility, Demand und alle früheren Aggregate
  exakt. Es fehlen keine Fixziele; alle Distanzen sind gültig. Nach `n>=5` und
  positiver Within-Varianz des Prädiktors decken 815 Zellen 11.263/19.138 =
  58,8515 % der Entscheidungen, aber 97,7036 % der OZ-Restvarianz ab.
- **Warum unentscheidbar:** Der vorab fixierte 60-%-Entscheidungsgate scheitert
  vor dem Mechanik-Gate. Die rohe positive Steigung und 13,2780 % Erklärung
  dürfen weder als Bestätigung noch als Widerlegung gelesen werden.
- **Status/Regel:** Mindestabdeckung nicht senken und denselben Prädiktor nicht
  nachträglich anders transformieren oder klassieren. Eine neue Sonde muss
  vorab direkt die kandidatenbezogenen `*_FIX`-Utilitybeiträge oder eine andere
  situative Struktur prüfen. Keine Dosis oder Produktimplementierung. Evidenz:
  `docs/05-validierung.md`, Abschnitt „Freiburg-Fixziel-Ankergeometrie
  (2026-08-24)".

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

- **Stand: 2026-08-24.** Verfasst beim Ausscheiden des Principal Engineers und bis E39 ergänzt.
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
