---
name: synthesis-debugging-playbook
description: >
  Symptom-zu-Fix-Runbook für die real aufgetretenen Fehlerbilder im SyntheSis-Projekt
  (Angular + Spring Boot, 14-stufige Pipeline, logiTopp/SUMO/MobileCity). Nutzen bei:
  "Unable to find a single main class" bei mvn spring-boot:run; Overpass HTTP 504 / HTTP 429
  ("Overpass-Antwort: HTTP …"); Modal-Split plötzlich falsch (Fuß-Anteil ~50 %, zu kurze Wege,
  "Fallback auf Luftlinie"); SUMO-Fehler "vehicle type 'bus' is not known"; duarouter
  "No destination edge for walk or personTrip"; gtfs2pt.py hängt scheinbar / "No mapping library
  found, falling back to tracemapper"; "pj_obj_create: Cannot find proj.db";
  ModuleNotFoundError pandas/rtree/pyproj; pip "externally-managed-environment" (PEP 668);
  logiTopp OutOfMemoryError / Java heap space; Angular NG5002 "Unexpected character" /
  TS2531 "Object is possibly 'null'"; Port 4300 belegt; Pipeline-Stufe SKIPPED
  ("SUMO übersprungen", "Simulation übersprungen", "ÖV übersprungen"); PtIntegrationTest skipped;
  SUMO meanSpeed=0 / KPIs sind 0; actiTopp "No destination available for WORK";
  run-config.yaml zeigt auf verschwundene Pfade; Fixtures/Szenarien nach Neustart weg.
  Auch laden, wenn ein Test rot ist / fehlschlägt oder eine Exception / ein Stacktrace
  in Pipeline oder Suite auftaucht, deren Text oben nicht wörtlich steht.
---

# SyntheSis Debugging-Playbook

Dieses Runbook beantwortet: **„Es ist kaputt — was tue ich JETZT?"** Jedes Fehlerbild hier ist
im Projekt real aufgetreten. Arbeite die Triage-Tabelle von oben nach unten ab; für die
komplexen Fälle gibt es darunter je einen Abschnitt mit dem **diskriminierenden Experiment**
(wie unterscheide ich Ursache A von Ursache B, bevor ich 20 Minuten in den falschen Fix stecke).

**Begriffe** (Minimum, Details in `verkehrsmodellierung-reference`):

| Begriff | Bedeutung |
|---|---|
| Pipeline / Stufe | Die 14-stufige Generierung in `backend/src/main/java/de/synthesis/generation/GenerationService.java`; jede Stufe endet als `DONE`, `SKIPPED` oder `FAILED` (`GenerationJob.java`) |
| Achse A / Achse B | Verhaltensquelle (actiTopp, GTA-LLM, Template) / Zielmodell (logiTopp, SUMO, MobileCity) — Stufen sind „gated": sie laufen nur, wenn die Achse im Szenario gewählt ist |
| Overpass | Öffentliche OSM-Abfrage-API (Straßennetz, POIs); `OverpassClient.java` probiert Mirrors durch |
| SUMO | Mikroskopische Verkehrssimulation; Kette `netconvert` → `duarouter` → `sumo` in `SumoRunner.java` |
| gtfs2pt.py | SUMO-Tool, das einen GTFS-Fahrplan (ÖV-Rohdaten) auf das SUMO-Netz mappt; gekapselt in `TransitRunner.java` |
| Job-Log | Lauf-Protokoll der Pipeline; alle unten zitierten deutschen Meldungen stammen aus `job.log(...)`-Aufrufen und sind per grep im Quellcode auffindbar |

**Wo liegen die Artefakte?** Unter `backend/data/scenarios/<id>/artifacts/` (laut README.md),
darin u. a. `simulation/`, `sumo/` (mit `sumo.log`, `statistics.xml`, `transit/gtfs2pt.log`).

---

## 1. Triage-Tabelle

| # | Symptom (wörtlich) | Sofort-Check | Ursache | Fix | Geschichte / Verweis |
|---|---|---|---|---|---|
| 1 | `Unable to find a single main class` bei `mvn spring-boot:run` | `ls backend/target/classes/de/synthesis/ \| grep " 2"` | macOS-Finder/Sync legt Duplikat `SynthesisApplication 2.class` in `target/classes` ab | `cd backend && mvn clean`, dann neu starten | Überliefert, mehrfach aufgetreten (2026-07-06). Kostet nur Nerven, wenn man es nicht kennt |
| 2 | Overpass `HTTP 504` / `HTTP 429`; Job-Log: `Overpass-Antwort: HTTP …` | Job-Log nach `Fallback auf Luftlinie` greppen | Alle Overpass-Endpunkte überlastet/rate-limited; Routing fällt auf Luftlinie (Beeline) zurück | Warten / Mirror konfigurieren (`synthesis.overpass.mirrors`); Lauf ggf. wiederholen | Selbst verschuldetes Rate-Limiting durch wiederholte schwere Netz-Queries in einer Session (überliefert 2026-06-21). → Abschnitt 2.1 |
| 3 | Modal-Split „komisch": Fuß ~50 %, Ø-Weglänge ~2,0–2,6 km statt ~2,9 km geroutet | Wie #2: Log greppen, NICHT am Modell drehen — die Kennzahlen allein diskriminieren nicht (ein unkalibrierter Lauf kann ähnlich aussehen), nur der Log-Grep entscheidet | Downstream-Symptom von #2 (Luftlinien-Matrizen statt GraphHopper-Routing) | Wie #2; Lauf mit funktionierendem Routing wiederholen | docs/05: 2,0 km; Session-Notiz 2026-06: 2,6 km — beides Luftlinien-Läufe. → Abschnitt 2.1 |
| 4 | SUMO: `vehicle type 'bus' is not known` beim Laden von `pt_vehicles.add.xml` | Reihenfolge der `additional-files` in `sumo/scenario.sumocfg` prüfen | `gtfs2pt.py` schreibt keine vType-Definitionen in die Fahrzeug-Datei; `pt_vtypes.add.xml` (aus `TransitRunner.writeVtypes`) muss VOR den Fahrzeugen geladen werden | Reihenfolge korrigieren — im Code steht sie richtig (`SumoRunner.java`: vtypes → stops → vehicles); bei Handläufen selbst darauf achten | Kostete einen vollen 23-min-Testlauf (überliefert 2026-07-07) |
| 5 | duarouter: `No destination edge for walk or personTrip` | Enthält das Netz Rail-Kanten? `grep -c 'railway' <artifacts>/sumo/network.osm` | Fußgänger-Koordinate per `--mapmatch.distance` auf Kante gesnappt, auf der Fußgänger nicht laufen dürfen (klassisch: Gleise nahe Bahnhöfen) | Netz road-only halten (Status quo: `netconvert --keep-edges.by-vclass passenger,bicycle,pedestrian`) | Führte zum Rail/Tram-Rollback 2026-07-07 (docs/06, Abschnitt „Phase 2 – SUMO"; WARUM in `synthesis-failure-archaeology`). → Abschnitt 2.2 |
| 6 | gtfs2pt.py „hängt" | `tail <artifacts>/sumo/transit/gtfs2pt.log` | Kein Hang: Warnung `No mapping library found, falling back to tracemapper` = langsamer Python-Mapper | Warten (Zeitfenster 6–10 Uhr ~20–25 min, voller Tag ~37 min, Stand 2026-07-07); erst dann `synthesis.transit.timeout-minutes` erhöhen (Default 25) | Ergebnis wird gecacht: existieren `pt_stops.add.xml` + `pt_vehicles.add.xml`, wird gtfs2pt übersprungen (Log: `ÖV-Infrastruktur aus Cache übernommen`). → Abschnitt 2.3 |
| 7 | `pj_obj_create: Cannot find proj.db` von duarouter/gtfs2pt | Läuft der Prozess trotzdem durch? | Harmlose PROJ-Warnung | Ignorieren | Mehrfach beobachtet, nie Ursache eines echten Fehlers (Stand 2026-07-08) |
| 8 | `ModuleNotFoundError: pandas` (oder rtree/pyproj) bzw. pip: `externally-managed-environment` | `ls validation/transit-venv/bin/python*` | venv fehlt/kaputt; System-Python ist PEP-668-geschützt | NIE ins System-Python installieren. venv neu bauen: `python3 -m venv validation/transit-venv && validation/transit-venv/bin/pip install pandas rtree pyproj` | Ohne venv wird die ÖV-Stufe still übersprungen (`TransitRunner.isAvailable()` = false) |
| 9 | logiTopp: `OutOfMemoryError` / Java heap space bei großen Regionen | Wie groß ist `samplingFraction` im Szenario? | 100 % Population sprengt den Heap in der Tourenplanung (Befund L5, docs/05: 104k Agenten / 109k Pakete sprengen 8–12 GB; 10 %-Fraktion läuft problemlos) | `samplingFraction` reduzieren oder Heap dimensionieren (bis ~12 GB einplanen) | Auf 16-GB-Maschine keine Parallel-Schwerlast: LLM-Batch (~4,6 GB) + logiTopp-JVM kollidieren (überliefert) |
| 10 | Angular `NG5002: Unexpected character` | Betroffene Template-Zeile auf typographische Zeichen prüfen | Typographische Anführungszeichen („…") in `title`-Attributen | Nur gerade Anführungszeichen (`"`) verwenden | Überliefert, mehrfach beim Schreiben deutscher UI-Texte |
| 11 | Angular `TS2531: Object is possibly 'null'` | — | Strict-AOT-Compiler | Lokale `const` mit Null-Check extrahieren statt Inline-Zugriff | Überliefert |
| 12 | `Port 4300 is already in use` | `lsof -i :4300` — wem gehört der Prozess? | Eigener Dev-Server läuft bereits (`npm start` = `ng serve --port 4300`, siehe `frontend/package.json`) | Bestehenden Server weiternutzen; NICHT blind killen | Überliefert |
| 13 | `PtIntegrationTest` wird geskippt | Testausgabe: `SYNTHESIS_TRANSIT_VENV nicht gesetzt – ÖV-Integrationstest übersprungen` | Gewollt: `assumeTrue`-Gate auf Env-Var `SYNTHESIS_TRANSIT_VENV` + Fixtures (`validation/kvv-gtfs.zip`, `validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv`, `validation/rastatt-test.osm`, SUMO-Binaries) | Kein Fehler. Zum Aktivieren: Env-Var auf venv-Python setzen, Fixtures bereitstellen | `backend/src/test/java/de/synthesis/generation/pipeline/PtIntegrationTest.java` |
| 14 | Fixtures/Szenario-IDs weg nach Session-/Rechner-Neustart | `ls validation/` | `/tmp` wird gereaped; persistente Fixtures gehören nach `validation/` | `validation/rastatt-test.osm` ist selbstheilend (`SumoRunnerTest.loadOsm()` lädt via Overpass nach und schreibt den Cache neu); Szenarien via `POST /api/scenarios` neu anlegen | Überliefert |
| 15 | SUMO-KPIs alle 0 (meanSpeed=0 usw.) | `sumo/statistics.xml` von Hand öffnen: stehen dort echte Werte? | Historisch: kombinierter Regex las `vehicleTripStatistics`-Attribute reihenfolgeabhängig | Gefixt: `SumoRunner.parseStatistics` + `attr()` lesen attributweise (reihenfolge-unabhängig). Lehre: bei 0-Werten IMMER zuerst Parser gegen Roh-XML prüfen | Historisch, Fix ist im Code (`SumoRunner.java`) |
| 16 | actiTopp/legacy: `No destination available for WORK` | Hat jede Person WORK+EDUCATION-Zeilen in `fixedDestination.csv`? | actiTopp entscheidet selbst, wer arbeitet — fehlt das Fixziel, bricht die Legacy-Zielwahl ab | Gefixt (2026-06-12): `PopulationGenerator` schreibt immer `sampleGravityZone`-Fixziele (Gating entfernt); bei Regression dort ansetzen | Kommentar im Code, `PopulationGenerator.java` ~Zeile 120 |
| 17 | Stufe `Simulation (logiTopp)` SKIPPED | Job-Log lesen — die Meldung nennt den Grund | (a) Zielmodell `logitopp` nicht gewählt, (b) `synthesis.simulation.project-dir` zeigt nicht auf existierendes `validation/mobitopp-rastatt` (Log: `Legacy-Projekt nicht gefunden: …`), (c) deaktiviert | Ziel wählen bzw. `project-dir` korrigieren (Default `../validation/mobitopp-rastatt`, relativ zu `backend/`) | `GenerationService.stageSimulation`. → Abschnitt 2.4 |
| 18 | Stufe `SUMO (Verkehr)` SKIPPED | Job-Log: welcher der drei Texte? | (a) Ziel `sumo` nicht gewählt, (b) `SUMO übersprungen (nicht verfügbar – SUMO_HOME/Binaries prüfen)`, (c) `benötigt logiTopp-Trips` = `demandsimulationResult.csv` fehlt | (b) `$SUMO_HOME` setzen — `SynthesisProperties.Sumo` leitet `binDir`/`sumoHome` daraus ab; (c) Zielmodell logiTopp mitwählen | `GenerationService.stageSumo`. → Abschnitt 2.4 |
| 19 | ÖV fehlt im SUMO-Lauf, kein Fehler | Job-Log: `ÖV übersprungen (Python-venv/gtfs2pt nicht konfiguriert – synthesis.transit.venv-python)` | `synthesis.transit.venv-python` leer oder venv/gtfs2pt.py nicht vorhanden | Key auf `validation/transit-venv/bin/python` (absolut) setzen; venv-Neubau siehe #8 | `GenerationService.buildPtProvider`, `TransitRunner.isAvailable()` |
| 20 | `run-config.yaml` zeigt auf verschwundene Pfade | Datei unter `<artifacts>/simulation/run-config.yaml` ansehen | `SimulationRunner` schreibt absolute Pfade (`simulationDir`, `resultsDir`) — nach Verschieben/Reapen sind sie tot | Szenario neu generieren (Datei wird je Lauf neu geschrieben) | `SimulationRunner.java` ~Zeile 146 |

---

## 2. Diskriminierende Experimente (komplexe Fälle)

### 2.1 „Der Modal-Split ist falsch" — Modellfehler oder Routing-Fallback?

**Geschichte:** In einer Session (2026-06-21, überliefert) wurden wiederholt schwere
Straßennetz-Queries abgesetzt → Overpass hat rate-limited (504/429) → alle Läufe der Session
liefen still auf Luftlinien-Matrizen. Symptom downstream: Fuß-Anteil ~50 %, Ø-Weg ~2,0–2,6 km
statt ~2,9 km geroutet (docs/05). Wer das für einen Modellfehler hält, kalibriert stundenlang gegen ein
Netzwerkproblem.

**Experiment (2 Minuten, statt raten):** Das Job-Log entscheidet.

```bash
# Fallback-Meldung? (Quelle: GenerationService.java, ~Zeile 873)
grep -rn "Fallback auf Luftlinie" backend/data/scenarios/*/artifacts 2>/dev/null
# Welcher Matrix-Modus wurde geloggt? Stufe 6 loggt "…(GraphHopper-Routing)" oder "…(Luftlinie)"
grep -rn "Luftlinie\|GraphHopper-Routing" backend/data/scenarios/<id>/artifacts/manifest.json
```

- **Treffer `Fallback auf Luftlinie`** → Ursache ist Overpass (Tabelle #2). Modell in Ruhe lassen.
- **Kein Treffer, Routing lief** → jetzt erst Modellursachen prüfen (Kalibrierung:
  `synthesis-config-and-flags`; Messung: `synthesis-diagnostics-and-tooling`).

Mirror-Kette (verifiziert in `OverpassClient.post()` und `SynthesisProperties.Overpass`):
probiert wird `synthesis.overpass.endpoint` (Default `https://overpass-api.de/api/interpreter`),
dann die `mirrors` (Defaults: `overpass.kumi.systems`, `overpass.private.coffee`) — nur der
**letzte** Fehler wird gemeldet. Nicht in Schleife retryen, das verschärft das Rate-Limiting.

### 2.2 duarouter verliert Fußgänger — laut ODER still

**Geschichte:** Beim Rail/Tram-Versuch (2026-07-07, Details docs/06 „Phase 2 – SUMO" und
`synthesis-failure-archaeology`) snappte `duarouter --mapmatch.distance` Personen-Koordinaten
auf die **nächstgelegene Kante ohne vClass-Filter** — nahe Bahnhöfen sind das Gleise, auf denen
Fußgänger nicht laufen dürfen. Ein `--mapmatch.vclass` existiert in duarouter nicht.
Zwei Fehlermodi:

| Modus | Verhalten | Erkennbar an |
|---|---|---|
| `--mapmatch.distance` (unser Setup) | Bricht LAUT ab | `No destination edge for walk or personTrip` in `sumo/sumo.log` |
| `--mapmatch.junctions` (die „naheliegende" Alternative) | Verwirft Personen STILL | Lauf „gelingt", aber 0 Fußgänger geroutet |

**Experiment — den stillen Modus IMMER gegenprüfen:**

```bash
# Wie viele Fuß-Etappen hat duarouter tatsächlich geroutet?
grep -c "<walk" backend/data/scenarios/<id>/artifacts/sumo/routes.rou.xml
# Enthält das Netz überhaupt Rail? (Sollte im Status quo NICHT)
grep -c "railway" backend/data/scenarios/<id>/artifacts/sumo/network.osm
```

- `<walk`-Anzahl 0 bei vorhandener Fuß-Nachfrage → Personen wurden still verworfen. Kein Erfolg.
- Status quo ist bewusst **road-only**: `netconvert … --keep-edges.by-vclass
  passenger,bicycle,pedestrian` (`SumoRunner.java`, ~Zeile 103). Wer Rail-Kanten wieder
  hereinholt, reaktiviert diesen Blocker — vorher `synthesis-failure-archaeology` und
  `synthesis-change-control` lesen.

### 2.3 gtfs2pt.py — hängt es, oder rechnet es?

**Fakten (verifiziert in `TransitRunner.java`):** Ausgabe läuft nach
`<artifacts>/sumo/transit/gtfs2pt.log`; Timeout über `synthesis.transit.timeout-minutes`
(Default 25); Cache: existieren `pt_stops.add.xml` und `pt_vehicles.add.xml` (>0 Bytes),
wird gtfs2pt komplett übersprungen.

**Experiment:**

```bash
tail -f backend/data/scenarios/<id>/artifacts/sumo/transit/gtfs2pt.log
```

- Log wächst + enthält `No mapping library found, falling back to tracemapper` (überlieferte
  Warnung, 2026-07-07) → es rechnet. Erwartung: Zeitfenster 6–10 Uhr ~20–25 min, voller Tag
  ~37 min (Stand 2026-07-07). Zeitfenster: `synthesis.transit.window-start-hour` /
  `window-end-hour` (Defaults 6/10).
- Log wächst NICHT über Minuten → echtes Problem; die Abbruchmeldungen des Runners lauten
  `gtfs2pt.py nach N min abgebrochen (Timeout)` bzw. `gtfs2pt.py fehlgeschlagen (Exit …)`.
- Erst nach dem Log-Blick den Timeout erhöhen — nie umgekehrt.

### 2.4 Stufe SKIPPED — Konfiguration, Umgebung oder Absicht?

`SKIPPED` ist in dieser Pipeline **dreideutig**. Die Job-Log-Meldung (alle Texte in
`GenerationService.java` verifizierbar) nennt immer den Grund — lies sie, bevor du suchst:

1. **Absicht:** Zielmodell/Flag nicht gewählt (`… übersprungen (Zielmodell nicht ausgewählt)`,
   `Netz-Feedback übersprungen (nicht aktiviert)`).
2. **Umgebung fehlt:** `SUMO übersprungen (nicht verfügbar – SUMO_HOME/Binaries prüfen)`,
   `Legacy-Projekt nicht gefunden: …`, `ÖV übersprungen (Python-venv/gtfs2pt nicht konfiguriert …)`
   → Umgebung reparieren mit `synthesis-build-and-env`.
3. **Abhängige Stufe fehlt:** `SUMO übersprungen (benötigt logiTopp-Trips – bitte Zielmodell
   logiTopp mitwählen)`, `Netz-Feedback übersprungen (benötigt erfolgreichen SUMO-Lauf)`,
   `… benötigt logiTopp-Ziel und Pkw-Matrix aus diesem Lauf` sowie das Zonenlimit
   (`… Zonen > Limit 150 – OD-Routing wäre n²`, konfigurierbar via
   `synthesis.sumo.feedback-max-zones`).

Merksatz: **SUMO scheitert als `FAILED`, nicht als `SKIPPED`** — die Pipeline unterscheidet
bewusst Fehler von Abwahl (Kommentar in `GenerationService.stageSumo`). Ein `SKIPPED` ist also
nie ein Crash.

### 2.5 Nachstellen ohne UI (Schnellbefehle)

```bash
# Backend starten (Port 8090)
cd backend && mvn spring-boot:run

# Frontend starten (Port 4300, proxied auf /api)
cd frontend && npm start

# Nur den SUMO-Pfad testen (überspringt sich selbst, wenn SUMO/Fixtures fehlen)
cd backend && mvn -o test -Dtest=SumoRunnerTest

# Volle Testsuite offline
cd backend && mvn -o test

# Frontend-Interaktionen und Strict-AOT-Compile prüfen (fängt Regressionen sowie NG5002/TS2531)
cd frontend && npm test
cd frontend && npm run build
```

---

## 3. Wann diesen Skill NICHT nutzen

| Anliegen | Richtiger Skill |
|---|---|
| WARUM ist eine Entscheidung so gefallen / Chronik eines Fehlschlags (z. B. Rail/Tram) | `synthesis-failure-archaeology` — das ist die WARUM-Chronik, dieses Playbook das WAS-TUN |
| Umgebung von null aufsetzen (SUMO, venv, KIT-Stacks, Ollama) | `synthesis-build-and-env` |
| Normale Bedienung/Betrieb, kein Fehler | `synthesis-run-and-operate` |
| Welche Config-Keys/Flags gibt es, was sind Defaults | `synthesis-config-and-flags` |
| Messwerkzeuge, KPIs auswerten, Log-Analyse-Tooling | `synthesis-diagnostics-and-tooling` |
| Validierung/QA-Prozesse | `synthesis-validation-and-qa` |
| Architektur-Invarianten vor einer Änderung | `synthesis-architecture-contract` + `synthesis-change-control` |
| Fachbegriffe der Verkehrsmodellierung | `verkehrsmodellierung-reference` |

---

## 4. Provenienz & Wartung

- **Stand: 2026-07-08.** Verifiziert per Read/Grep gegen den damaligen `main`
  (jüngster Commit `4ad6382`).
- **Primärquellen:** `backend/src/main/java/de/synthesis/generation/GenerationService.java`
  (SKIPPED-/Fallback-Meldungen), `backend/src/main/java/de/synthesis/generation/pipeline/SumoRunner.java`
  (netconvert/duarouter-Flags, `parseStatistics`, additional-files-Reihenfolge),
  `backend/src/main/java/de/synthesis/generation/pipeline/TransitRunner.java` (`writeVtypes`,
  `isAvailable`, Cache, `gtfs2pt.log`),
  `backend/src/main/java/de/synthesis/generation/pipeline/OverpassClient.java` (Mirror-Kette),
  `backend/src/main/java/de/synthesis/generation/pipeline/PopulationGenerator.java`
  (WORK/EDUCATION-Fix), `backend/src/main/java/de/synthesis/generation/pipeline/SimulationRunner.java`
  (`run-config.yaml`), `backend/src/main/java/de/synthesis/SynthesisProperties.java`
  (alle `synthesis.*`-Keys und Defaults),
  `backend/src/test/java/de/synthesis/generation/pipeline/SumoRunnerTest.java` und
  `PtIntegrationTest.java` (Gates/Fixtures), `README.md`, `docs/05-validierung.md` (Befund L5),
  `docs/06-gta-integration-konzept.md` (Rail/Tram-Blocker, tracemapper-Laufzeiten),
  `frontend/package.json` (Port 4300).
- **Überlieferte Beobachtungen** (Fehlertexte externer Tools, Laufzeiten, Kosten-Anekdoten)
  sind im Text als solche mit Datum markiert; sie stammen aus der Projekthistorie und stehen
  nicht wörtlich im Repo.
- **Volatil, bei jeder Nutzung gegenprüfen:** Overpass-Mirror-URLs, tracemapper-Laufzeiten,
  Timeout-Defaults, das road-only-Netz (Rail/Tram könnte inzwischen gelöst sein).
- **Re-Verifikation (ein Befehl):**
  `grep -rn "Luftlinie\|SKIPPED\|venv-python\|keep-edges.by-vclass\|pt_vtypes" backend/src/main/java | head -30`
  — liefern die Treffer noch dieselben Meldungen/Flags wie in der Triage-Tabelle, ist das
  Playbook aktuell; sonst betroffene Zeilen anpassen.
