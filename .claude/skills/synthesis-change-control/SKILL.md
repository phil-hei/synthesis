---
name: synthesis-change-control
description: >
  Change-Control-Runbook für das SyntheSis-Repo: Wie Änderungen klassifiziert, abgesichert,
  committet und dokumentiert werden. Laden, WENN du (a) Code oder Doku ändern/committen/mergen
  willst, (b) wissen musst, welche Gates (mvn -o test, npm test, npm run build) vor einem Commit grün sein
  müssen, (c) ein neues Feature/Flag/Pipeline-Stufe planst, (d) ein externes Tool (SUMO, gtfs2pt,
  duarouter, Ollama, GraphHopper) integrieren willst, (e) git push / GitHub Desktop / GitLab-Remote
  Fragen hast, (f) unsicher bist, ob validation/ angefasst werden darf, oder (g) wissen musst,
  welche Gates vor einem Commit gelten (das FORMAT der Commit-Message samt Templates wohnt
  in synthesis-docs-and-writing). Stichwörter: commit, merge, push, review, Gate, Flag,
  default=false, Spike, Rollback, Doku-Pflicht, FAILED vs. SKIPPED, .gitignore, validation/,
  Änderungsklasse, change control, Nicht-Verhandelbares. Symptome, die hierher gehören:
  "push schlägt fehl", "AOT-Build rot nach Template-Änderung", "darf ich das committen?",
  "wo dokumentiere ich das Feature?".
---

# SyntheSis Change Control

Dieses Runbook regelt, **wie Änderungen in diesem Repo klassifiziert, abgesichert, committet und
dokumentiert werden** – inklusive der Nicht-Verhandelbaren mit ihrer jeweiligen Begründung und dem
historischen Vorfall dahinter. Zielgruppe: Entwickler und KI-Modelle ohne Projektvorwissen.

**Kontext in einem Satz:** SyntheSis ist ein Web-Tool (Angular-Studio `frontend/` + Spring-Boot-
Backend `backend/`), das aus einer Kartenregion ein agentenbasiertes Verkehrsnachfragemodell
generiert (14-stufige Pipeline, orchestriert in
`backend/src/main/java/de/synthesis/generation/GenerationService.java`). Details zum Ansatz:
`docs/07-ansatz-zusammenfassung.md`; Phasen-Historie: `docs/06-gta-integration-konzept.md`.

## Wann diesen Skill NICHT nutzen

| Anliegen | Stattdessen |
|---|---|
| Ein Symptom triagieren („Pipeline-Stufe rot, warum?") | `synthesis-debugging-playbook` |
| Wissen, was früher schon versucht und verworfen wurde | `synthesis-failure-archaeology` |
| Architektur-Invarianten (was darf sich nie ändern) | `synthesis-architecture-contract` |
| Flag-Bedeutungen und Konfig-Schlüssel im Detail | `synthesis-config-and-flags` |
| Build-/Umgebungsprobleme (Maven, npm, SUMO_HOME, venv) | `synthesis-build-and-env` |
| Anwendung starten/betreiben | `synthesis-run-and-operate` |
| Validierungs-/QA-Methodik (Modal-Split, KPIs) | `synthesis-validation-and-qa` |
| Doku-Stil und Schreibkonventionen im Detail | `synthesis-docs-and-writing` |
| Verkehrsmodellierungs-Fachwissen (MiD, MNL, MSA …) | `verkehrsmodellierung-reference` |

Dieser Skill sagt dir, **welche Absicherung eine Änderung braucht** – nicht, wie man debuggt,
baut oder fachlich modelliert.

## Begriffe (Erstnennung)

- **Gate**: Ein Befehl, der grün sein muss, bevor committet/gemergt wird.
- **Flag**: Boolesches Szenario-Feld in `backend/src/main/java/de/synthesis/scenario/Scenario.java`,
  das ein Feature pro Szenario ein-/ausschaltet.
- **Spike**: Wegwerf-Experiment außerhalb des Produktivcodes, das eine unsichere Mechanik beweist,
  bevor sie implementiert wird.
- **Stage-Status**: Jede Pipeline-Stufe meldet `PENDING | RUNNING | DONE | FAILED | SKIPPED`
  (definiert in `backend/src/main/java/de/synthesis/generation/GenerationJob.java`).
- **AOT-Build** (Ahead-of-Time): `ng build` kompiliert Angular-Templates strikt und fängt dabei
  Fehlerklassen (z. B. NG5002, TS2531), die `ng serve` durchlässt.
- **Runner**: Kapselklasse für ein externes Tool unter
  `backend/src/main/java/de/synthesis/generation/pipeline/` (z. B. `SumoRunner`, `TransitRunner`,
  `ActitoppRunner`), jeweils mit `isAvailable()`-Check.

## Die Gates (kopierbar)

```bash
# Gate 1 – Backend-Testsuite (komplett, offline)
cd backend && mvn -o test
# Erwartung (Stand: 2026-08-11): 124 Testfälle grün; 6 umgebungsabhängige
# Testfälle werden ohne externe Laufzeit/Env-Variablen übersprungen – das ist normal.

# Gate 2 – Frontend-Tests + strict-AOT-Build (fängt NG5002/TS2531, die ng serve NICHT fängt)
cd frontend && npm test && npm run build
```

Nützlich bei Maven-Fehlern („Unable to find a single main class"):

```bash
cd backend && mvn clean
```

Zum manuellen Verifizieren (kein Gate, aber Standard-Start):

```bash
cd backend && mvn spring-boot:run     # Port 8090
cd frontend && npm install && npm start   # Port 4300, proxied auf /api
```

## Änderungsklassen

Klassifiziere JEDE Änderung zuerst; daraus folgt die Absicherung:

| Klasse | Beispiel | Pflicht-Gates | Zusatzpflichten |
|---|---|---|---|
| **Doku-only** | Tippfehler, Abschnitt in `docs/` | keine (nur Konsistenz: Querverweise/README stimmen weiter) | – |
| **Frontend-only** | Panel, Template, CSS | `cd frontend && npm test && npm run build` | – |
| **Backend-Refactoring, verhaltensgleich** | Umbau ohne Verhaltensänderung | `cd backend && mvn -o test` (komplett) | Verhaltensgleichheit ist BEHAUPTUNG, die Suite ist der Beweis. Vorbild: Commit `e4b6d64` (PipelineContext-Refactoring, „verhaltensgleich: komplette Testsuite inkl. E2E grün") |
| **Neue Pipeline-Stufe / neues Feature-Flag** | neue Achse-B-Fähigkeit | beide Gates | Voller Zyklus: Spike → Flag `default=false` → Tests → E2E-Verifikation → Doku-Eintrag (siehe Nicht-Verhandelbare 3–5) |
| **Externe-Tool-Integration** | neues SUMO-Tool, neues LLM | beide Gates | Spike-Pflicht über die GANZE Kette; Kapselung als Runner mit `isAvailable()`; graceful degradation: Stage `SKIPPED`, wenn das Tool fehlt (Muster: `TransitRunner.isAvailable()` in `backend/src/main/java/de/synthesis/generation/pipeline/TransitRunner.java`) |

## Die Nicht-Verhandelbaren

### 1. Push nur über GitHub Desktop, nie aus der CLI

- **Regel:** Committen aus Sessions ist erwünscht. `git push` aus der CLI ist verboten und
  schlägt ohnehin fehl.
- **Rationale:** Das Remote ist ein GitLab (`gitlab.idial.institute`, prüfbar via
  `git remote -v`); das Zugangs-Token (PAT) liegt ausschließlich in GitHub Desktop, die CLI hat
  keine Credentials.
- **Konsequenz für Agenten:** Commits sammeln sich lokal. **Weise den Nutzer am Ende der Session
  auf ausstehende Pushes hin** (`git log origin/main..HEAD --oneline` zeigt sie).

### 2. Kein Merge/Commit ohne grüne Gates

- **Regel:** Vor jedem Commit mit Code-Anteil: `cd backend && mvn -o test` UND
  `cd frontend && npm test` sowie `cd frontend && npm run build` (je nach Änderungsklasse,
  siehe Tabelle; im Zweifel alle drei Gates).
- **Rationale:** Der strict-AOT-Build fängt Fehlerklassen, die im Dev-Server unsichtbar bleiben.
- **Vorfall (überliefert, 2026-07-02):** Typographische Anführungszeichen „…" in einem
  HTML-`title`-Attribut brachen den AOT-Build (NG5002) – im laufenden `ng serve` fiel nichts auf.
  TS2531-Nullability-Fehler werden ebenfalls nur vom Build gefangen.

### 3. Spike vor Implementierung – und der Spike muss die GANZE Kette abdecken

- **Regel:** Unsichere Mechanik externer Tools (SUMO-Verhalten, `gtfs2pt.py`, `duarouter`, …)
  wird ERST als Wegwerf-Experiment im Scratch-Verzeichnis bewiesen, DANN in Java gegossen.
- **Positiv-Vorfall (überliefert, 2026-07-06):** Fußgänger-Phase – das
  `<personTrip fromLonLat>`-Mapmatching wurde vorab empirisch validiert; die Implementierung
  lief danach fehlerfrei.
- **Negativ-Vorfall (2026-07-07, dokumentiert in `docs/06-gta-integration-konzept.md`,
  Abschnitt „Phase 2 – SUMO"):** Rail/Tram – der Netz-Teil wurde gespikt, die Personen-Routung
  auf dem Rail-Netz NICHT. Blocker erst in der fertigen Implementierung entdeckt (`duarouter`
  snappt Personen-Koordinaten ohne vClass-Filter auf Gleise) → kompletter Rollback.
- **Lehre:** Der Spike muss die **gesamte Kette** des Features abdecken, nicht nur den neuen Teil.

### 4. Doku im selben Zug

- **Regel:** Jede verhaltensändernde Änderung bekommt **im selben Arbeitsgang** einen datierten
  Eintrag im passenden Phasen-Abschnitt von `docs/06-gta-integration-konzept.md`.
  Stil (im Dokument belegt): `✅ FEATURE (JJJJ-MM-TT): …` bzw.
  `❌/… versucht, zurückgerollt (JJJJ-MM-TT) — ehrlicher Technik-Befund: …`.
  `README.md` und `docs/07-ansatz-zusammenfassung.md` nachziehen, wenn sich der Gesamtstand ändert.
- **Negative Befunde werden dokumentiert, nicht verschwiegen.** Beispiel: Der Rail/Tram-Befund
  steht in `docs/06-gta-integration-konzept.md` UND im `README.md` (Abschnitt „Anwendung starten").
- **Rationale:** `docs/06` ist die Phasen-Chronik des Projekts; Doku, die „später" kommt, kommt nie
  (Vorbild-Gegenmaßnahme: Commit `4ad6382` musste genau solchen Doku-Rückstand nachtragen).

### 5. Experimentelle Features starten mit Flag `default=false`

- **Regel:** Neue experimentelle Fähigkeiten bekommen ein Flag in
  `backend/src/main/java/de/synthesis/scenario/Scenario.java` mit Default `false` und werden erst
  nach E2E-Verifikation + Doku-Eintrag befördert. Belegte Beispiele (Stand: 2026-07-08):
  `calibrateModeChoice`, `llmModeChoice`, `sumoFeedback`, `publicTransport` – alle `= false`.
- **FAILED ≠ SKIPPED:** Optionale Pipeline-Stufen setzen bei **Fehlern** den Status `FAILED`,
  nicht `SKIPPED`. `SKIPPED` ist ausschließlich für bewusste Abwahl/fehlende Tools reserviert –
  der Nutzer muss beides unterscheiden können. Belegt als Kommentar in `GenerationService.java`:
  „FAILED statt SKIPPED: der Nutzer soll Fehler von bewusster Abwahl unterscheiden können"
  (eingeführt mit Härtungs-Commit `bd43219`).

### 6. `validation/` ist gitignored UND tragend zugleich

- **Regel:** NIE committen, NIE löschen, NIE „aufräumen".
- **Inhalt (Stand: 2026-07-08):** KIT-Clones (`mobitopp`, `mobiTopp_legacy`, `logitopp`,
  `actitoppNG`, `populationsynthesis`), das Legacy-Simulationsprojekt `mobitopp-rastatt`,
  `transit-venv` (Python-venv für `gtfs2pt.py`) sowie Test-Fixtures (`rastatt-test.osm`,
  `rastatt-rail-test.osm`, `kvv-gtfs.zip`).
- **Rationale:** Die `.gitignore` besteht (Stand: 2026-07-08) genau aus `validation/` und
  `takomat-beispiel-export.zip`. Fixtures liegen bewusst in `validation/` und **nicht in `/tmp`**,
  weil `/tmp` vom System gereaped wird.
- **Vorfall (überliefert):** `/tmp/rastatt.osm` verschwand mehrfach zwischen Sessions, bevor die
  Fixtures nach `validation/` umzogen.

### 7. Commit-Stil

- **Sprache Deutsch**, Format `Bereich: prägnante Zusammenfassung` (Beleg: `git log --oneline`,
  z. B. `SUMO: ÖV/Bus multimodal via GTFS + gtfs2pt.py (Achse B, Phase C)`).
- **Body erklärt das WARUM** (nicht das Was – das zeigt der Diff), inkl. Verifikationsnachweis
  („Suite 18/18 grün" o. ä.).
- **Abschluss bei KI-Beteiligung:** `Co-Authored-By: Codex <Modell> <noreply@anthropic.com>`
  (Beleg: die jüngsten Commits im Log).

## Checkliste vor jedem Commit

1. [ ] Änderungsklasse bestimmt (Tabelle oben)?
2. [ ] Pflicht-Gates der Klasse grün (`mvn -o test` / `npm test` / `npm run build`)?
3. [ ] Bei neuem Feature: Flag in `Scenario.java` mit `default=false`? Fehlerpfad setzt `FAILED`,
   Tool-Abwesenheit `SKIPPED`?
4. [ ] Bei externem Tool: Spike über die ganze Kette gelaufen? Runner mit `isAvailable()`?
5. [ ] Doku-Eintrag in `docs/06-gta-integration-konzept.md` im selben Commit (datiert, ✅/❌-Stil)?
   README/docs/07 nachgezogen, falls Gesamtstand betroffen?
6. [ ] Nichts aus `validation/` gestaged? (`git status` zeigt es dank `.gitignore` normal gar
   nicht – wenn doch, stoppen.)
7. [ ] Commit-Message: Deutsch, `Bereich: Zusammenfassung`, WARUM im Body, Co-Authored-By.
8. [ ] Nutzer auf ausstehende Pushes hingewiesen (Push nur via GitHub Desktop)?

## Provenienz & Wartung

**Stand: 2026-07-08.** Repo-Fakten wurden gegen den Arbeitsstand verifiziert (Scenario-Flags,
Stage-Status-Semantik, `.gitignore`, Commit-Historie, Ports, Testliste). Historische Vorfälle
(NG5002 2026-07-02, Fußgänger-Spike 2026-07-06, Rail/Tram-Rollback 2026-07-07, `/tmp`-Reaping)
sind überliefertes Engineer-Wissen; der Rail/Tram-Befund ist zusätzlich in
`docs/06-gta-integration-konzept.md` und `README.md` belegt.

Driftende Fakten – vor Verwendung re-verifizieren:

| Fakt | Re-Verifikation (Einzeiler, repo-root) |
|---|---|
| Flag-Liste + Defaults | `grep -n "= false" backend/src/main/java/de/synthesis/scenario/Scenario.java` |
| FAILED/SKIPPED-Semantik | `grep -n "FAILED statt SKIPPED" backend/src/main/java/de/synthesis/generation/GenerationService.java` |
| Stage-Status-Werte | `grep -n "PENDING" backend/src/main/java/de/synthesis/generation/GenerationJob.java` |
| `.gitignore`-Umfang | `cat .gitignore` |
| Remote (GitLab, kein CLI-Push) | `git remote -v` |
| Ausstehende Pushes | `git log origin/main..HEAD --oneline` |
| Commit-Stil | `git log --oneline -15` |
| Runner-/`isAvailable()`-Muster | `grep -rn "isAvailable" backend/src/main/java --include="*.java"` |
| Testanzahl / PT-Test-Gating | `cd backend && mvn -o test` bzw. `grep -n SYNTHESIS_TRANSIT_VENV backend/src/test/java/de/synthesis/generation/pipeline/PtIntegrationTest.java` |
| Ports (8090/4300) | `grep -n "port" backend/src/main/resources/application.yml frontend/package.json` |
| `validation/`-Inhalt | `ls validation` |
