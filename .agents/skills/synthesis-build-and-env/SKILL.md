---
name: synthesis-build-and-env
description: >
  Setup-Runbook: die SyntheSis-Entwicklungsumgebung von null auf einer frischen Maschine
  reproduzieren (Angular 21 + Spring Boot 3.5/Java 21 + externe Toolchain). Laden, WENN du
  (a) auf einem neuen Rechner das Projekt aufsetzt ("frische Maschine", "Setup", "Installation",
  "Onboarding"), (b) eine Komponente fehlt oder falsch installiert ist ("SUMO_HOME nicht gesetzt",
  "sumo not found", "venv fehlt", "pip install schlägt fehl", "PEP 668", "externally-managed-environment"),
  (c) das gitignorierte validation/ fehlt oder unvollständig ist ("validation/ fehlt",
  "mobitopp-rastatt nicht vorhanden", "Stufe SKIPPED", "actiTopp startet nicht",
  "Could not resolve ... mobitopp" / mavenLocal-Fehler), (d) Ollama/Qwen fürs GTA-Feature
  eingerichtet werden soll, (e) Fixtures fehlen (rastatt-test.osm, kvv-gtfs.zip), oder
  (f) Build-Basics klemmen (mvn spring-boot:run, npm install, Port 8090/4300 belegt).
  Stichwörter: JDK 21, Maven, Node, Angular CLI, SUMO 1.26, EclipseSUMO.framework, SUMO_HOME,
  gtfs2pt, transit-venv, pandas/rtree/pyproj, Ollama, qwen2.5:7b-instruct, publishToMavenLocal,
  mavenLocal, kotlin-units, DCML, actitoppNG, mobitopp, logitopp, mobitopp-rastatt, KVV-GTFS,
  Overpass, Fixtures. Für den laufenden Betrieb danach: synthesis-run-and-operate; wenn etwas
  unerwartet kaputt ist: synthesis-debugging-playbook.
---

# SyntheSis: Build & Umgebung von null

Ziel: Auf einer frischen Maschine (macOS-Referenz; Pfade als solche gekennzeichnet) die
komplette SyntheSis-Umgebung reproduzieren — Backend, Frontend, SUMO, Ollama, das
Python-venv für den ÖV und vor allem das **gitignorierte `validation/`**, ohne das
Simulation (logiTopp), actiTopp, ÖV-Tests und Fixtures nicht funktionieren.

Publikum: Mid-Level-Entwickler:in bzw. ein Agent, der das Repo nicht kennt. Alle Pfade
repo-relativ (Repo-Root = Verzeichnis mit `backend/`, `frontend/`, `docs/`, `validation/`).

## Wann NICHT nutzen

- **Die Umgebung steht schon und du willst sie benutzen** (Pipeline starten, Szenario
  generieren, Ergebnisse deuten) → `synthesis-run-and-operate`.
- **Etwas war gestern grün und ist heute rot** (Regression, Stack-Traces, komisches
  Verhalten) → `synthesis-debugging-playbook`. Dieser Skill deckt nur „fehlt/nie
  installiert" ab.
- **Konfigurations-Feintuning** (Flags, `application.yml`-Optionen, Feature-Gates)
  → `synthesis-config-and-flags`.
- **Code ändern/committen** → `synthesis-change-control`.
- Container-Setup: nicht abgedeckt. Die GitLab-CI prüft Backend-Tests sowie Frontend-Tests und
  Produktions-Build; die externe Toolchain bleibt lokal (Stand 2026-08-04).

## Big Picture: Was gehört zur Umgebung?

| Schicht | Komponenten | Ohne sie |
|---|---|---|
| Kern | JDK 21, Maven, Node 20.19+/22.12+/24, git | nichts läuft |
| App | `backend/` (Spring Boot 3.5.5, Port 8090), `frontend/` (Angular 21, Port 4300) | keine App |
| SUMO | SUMO 1.26 (`sumo`/`netconvert`/`duarouter`), `$SUMO_HOME` | SUMO-Stufe SKIPPED, SumoRunnerTest übersprungen |
| LLM | Ollama + `qwen2.5:7b-instruct` | GTA fällt auf actiTopp zurück (dokumentierter Fallback) |
| ÖV | `validation/transit-venv` (pandas/rtree/pyproj) für `gtfs2pt.py` | ÖV-Stufe SKIPPED |
| **validation/** | KIT-Klone + gebaute mavenLocal-Artefakte + Fixtures | Simulation, actiTopp, ÖV-Test und Fixtures laufen nicht — **~die halbe Maschine** |

`validation/` ist gitignoriert (`.gitignore` enthält genau `validation/` und
`takomat-beispiel-export.zip`) — es kommt also NICHT mit dem Clone und ist der
aufwendigste Teil dieses Runbooks (Schritt 5).

---

## Schritt 1: Basis — JDK 21, Maven, Node, Backend + Frontend

Versionen (Ground Truth aus `backend/pom.xml` und `frontend/package.json`, Stand 2026-08-04):

- Backend: Spring Boot **3.5.5** (Parent-POM), `java.version` **21**, springdoc 2.8.5,
  commons-compress 1.27.1, GraphHopper 8.0 (eingebettet — kein separater Dienst nötig).
- Frontend: Angular **^21.2.19**, TypeScript ~5.9, Leaflet 1.9.4. Unterstützt werden
  Node **^20.19.0**, **^22.12.0** oder **^24.0.0**; `.nvmrc` fixiert die Referenz auf 20.20.2.
  Angular CLI wird über `npx ng` / devDependency benutzt — keine globale Installation nötig.

```bash
# macOS-typisch (Homebrew); jede andere JDK-21-Distribution geht auch:
brew install openjdk@21 maven node
git clone <repo-url> SyntheSis && cd SyntheSis
```

1. Backend starten:
   ```bash
   cd backend && mvn spring-boot:run
   ```
   **Erwartung:** Start auf Port **8090**; http://localhost:8090/swagger-ui.html zeigt die
   API. Artefakte entstehen später unter `backend/data/scenarios/<id>/artifacts/`.
2. Frontend starten (zweites Terminal):
   ```bash
   cd frontend && npm install && npm start
   ```
   **Erwartung:** `ng serve` auf Port **4300** (Proxy `/api` → 8090 via `proxy.conf.json`);
   http://localhost:4300 zeigt die Karte (Leaflet/OSM).
3. Build-Gates prüfen:
   ```bash
   cd backend && mvn -o test          # offline; erst NACH einem Online-Lauf möglich
   cd frontend && npm test            # nativer Angular/Vitest-Runner
   cd frontend && npm run build       # Produktions-AOT-Build
   ```
   **Erwartung:** beide grün — aber Achtung: viele Backend-Tests überspringen sich per
   `assumeTrue`, solange SUMO/validation//LLM fehlen (Verifikations-Matrix, Schritt 8).
   Grün heißt an diesem Punkt nur „Kern ok", nicht „alles da".

**Falle:** `mvn -o` (offline) schlägt beim allerersten Lauf fehl, weil der lokale
Maven-Cache leer ist — einmal `mvn test` (online) laufen lassen, danach ist offline ok.
Die KIT-Artefakte aus Schritt 5 sind dafür NICHT nötig: die Backend-Tests rufen die
Gradle-Projekte als externe Prozesse auf, nicht als Maven-Dependencies.

## Schritt 2: SUMO 1.26 (macOS: EclipseSUMO.framework)

macOS-Installationspfad (so auf der Referenzmaschine; als Konstante `MAC_FRAMEWORK` in
`backend/src/main/java/de/synthesis/SynthesisProperties.java` hinterlegt):

```
/Library/Frameworks/EclipseSUMO.framework/Versions/1.26.0/EclipseSUMO
```

Framework-Layout: `SUMO_HOME` ist `<Framework>/share/sumo` (Daten + `tools/`, u. a.
`tools/import/gtfs/gtfs2pt.py`); die Binaries liegen **nicht** unter `$SUMO_HOME/bin`,
sondern unter `<Framework>/bin` — also `$SUMO_HOME/../../bin`.

Ableitungslogik in `SynthesisProperties.Sumo` (im Code verifiziert):

- `sumoHome`-Default: `$SUMO_HOME` aus der Umgebung; wenn leer → `MAC_FRAMEWORK/share/sumo`.
- `binDir`-Default: erst `$SUMO_HOME/bin/sumo` probieren (Standard-/Linux-Layout), sonst
  `$SUMO_HOME/../../bin/sumo` (macOS-Framework), sonst Fallback `MAC_FRAMEWORK/bin`.
- Beides per `synthesis.sumo.bin-dir` / `synthesis.sumo.sumo-home` in
  `backend/src/main/resources/application.yml` übersteuerbar (dort auskommentiert vorbereitet).

Setup:

1. SUMO 1.26 installieren (macOS: offizielles EclipseSUMO-Framework-Paket von sumo.dlr.de;
   Linux wäre Paketmanager/Build — hier nicht verifiziert).
2. `SUMO_HOME` im Shell-Profil setzen:
   ```bash
   export SUMO_HOME=/Library/Frameworks/EclipseSUMO.framework/Versions/1.26.0/EclipseSUMO/share/sumo
   ```
3. **Erwartung:**
   ```bash
   "$SUMO_HOME/../../bin/sumo" --version    # macOS-Framework-Layout
   # bzw. auf Standard-Layouts: "$SUMO_HOME/bin/sumo" --version
   ```
   meldet `Eclipse SUMO sumo Version 1.26.x`; zusätzlich existiert
   `$SUMO_HOME/tools/import/gtfs/gtfs2pt.py` (braucht Schritt 4/ÖV).

**Falle:** Das Backend liest `SUMO_HOME` beim JVM-Start — die Variable muss in der Shell
gesetzt sein, aus der `mvn spring-boot:run` bzw. `mvn -o test` gestartet wird
(IDE-Run-Configs entsprechend konfigurieren), sonst greift der macOS-Fallback-Pfad.

## Schritt 3: Ollama + Qwen (nur für GTA-/LLM-Features)

Optional: Ohne erreichbares LLM fällt die Verhaltensquelle GTA sauber auf actiTopp
zurück (README + `SynthesisProperties.Llm`).

```bash
brew install ollama              # macOS
brew services start ollama       # API: http://localhost:11434
ollama pull qwen2.5:7b-instruct  # ~4,7 GB Download
```

**Erwartung:** `curl http://localhost:11434/v1/models` listet `qwen2.5:7b-instruct`.
Das Backend erwartet den OpenAI-kompatiblen Endpunkt `http://localhost:11434/v1` und
Modell `qwen2.5:7b-instruct` (Defaults in `SynthesisProperties.Llm`, übersteuerbar
unter `synthesis.llm.*`).

Hardware-Realität (16-GB-M1, Projekterfahrung Stand ~2026-07): ~11 tok/s, Modell
~4,6 GB im RAM, ~30–40 s je Persona (daher `max-personas`-Default 60).
**Falle:** Ollama NIE parallel zu einer großen logiTopp-JVM (bis 12 GB Heap) laufen
lassen — 16 GB RAM reichen nicht für beide.

## Schritt 4: transit-venv (nur für die ÖV-Stufe)

Optional: Ohne venv wird die ÖV-Stufe SKIPPED (kein Fehler). `gtfs2pt.py` braucht
pandas/rtree/pyproj.

```bash
python3 -m venv validation/transit-venv
validation/transit-venv/bin/pip install pandas rtree pyproj
```

Verifizierte Versionen (Stand 2026-07-07, Python 3.14): pandas 3.0.3, rtree 1.4.1,
pyproj 3.7.2. `rtree` bringt `libspatialindex` im Wheel mit — kein `brew install` nötig.

**PEP-668-Falle:** NIE `pip install` ins System-/Homebrew-Python — schlägt mit
`externally-managed-environment` fehl bzw. verschmutzt das System. Immer das venv.

Anbinden — zwei getrennte Konsumenten:

- **Pipeline/Backend:** `synthesis.transit.venv-python` in `application.yml` auf
  `../validation/transit-venv/bin/python` setzen (Default ist leer = ÖV-Stufe nicht
  verfügbar; auskommentierte Vorlage steht in der Datei). Pfad relativ zum
  Backend-Arbeitsverzeichnis.
- **ÖV-Integrationstest:** `PtIntegrationTest` liest die **Umgebungsvariable
  `SYNTHESIS_TRANSIT_VENV`** (Pfad zum Python-Binary), nicht die application.yml:
  ```bash
  cd backend && SYNTHESIS_TRANSIT_VENV=../validation/transit-venv/bin/python mvn -o test
  ```

**Erwartung:** `validation/transit-venv/bin/python -c "import pandas, rtree, pyproj"`
läuft ohne Fehler.

## Schritt 5: validation/ aufbauen — der heikelste Teil

`validation/` ist gitignoriert und muss von Hand rekonstruiert werden. Soll-Inventar
(per `ls validation/` auf der Referenzmaschine verifiziert, Stand 2026-07-08):

```
DCML/  actitoppNG/  kotlin-units/  logitopp/  mobiTopp-tutorial/  mobiTopp_legacy/
mobitopp/  mobitopp-rastatt/  populationsynthesis/  tools/  transit-venv/
kvv-gtfs.zip  rastatt-test.osm  rastatt-rail-test.osm
```

Quellen: die Klone kommen von `github.com/kit-ifv/<name>` (mobitopp, logitopp,
mobiTopp-tutorial, actitoppNG, DCML, kotlin-units, populationsynthesis; so in README
und docs/05 benannt). `mobitopp-rastatt` ist das KIT-Rastatt-Beispielprojekt, das hier
als **Legacy-Laufumgebung** der Pipeline dient. `tools/` enthält ein lokales JDK 11
(`jdk-11.0.31+11` + tar.gz) — laut docs/05-Kopf fürs Legacy-Umfeld beschafft; die
modernisierten Builds laufen auf JDK 25 (dass JDK 11 nur für frühe Legacy-Versuche
gebraucht wurde, ist aus der Projekthistorie überliefert — verifizieren, ob es noch
referenziert wird).

**Achtung — lokale Modifikationen:** `logitopp`, `mobitopp-rastatt` und `actitoppNG`
sind KEINE unveränderten Klone (modernisierte `build.gradle`, eigene Mains/Tasks,
s. u.). Ein frischer GitHub-Clone reicht dort nicht; die Änderungen sind in
`docs/05-validierung.md` beschrieben und müssen nachgezogen werden. Vor dem
Neuaufsetzen prüfen, ob ein Backup/Time-Machine-Stand der lokalen Klone existiert —
das ist billiger als die Rekonstruktion aus der Doku.

### 5a. Kotlin-Stack bauen (für actiTopp)

Build-Kette laut `docs/05-validierung.md`, Abschnitt „Build-Kette (neuer Kotlin-Stack)"
(dort dokumentiert und real durchgeführt): Die KIT-Artefakte liegen **nicht auf Maven
Central** → lokal bauen und publizieren mit

```bash
./gradlew publishToMavenLocal -PdoPublish=true -PbuildVersion=<v> \
  -PlocalUrl=file:///tmp/dummy-repo -PlocalRepoUser=x -PlocalRepoPassword=x
```

Die Dummy-`localUrl`/`localRepo*`-Parameter sind nötig, weil das Build-Skript die
internen Repo-Parameter eager prüft. Reihenfolge und Versionen (Tabelle aus docs/05):

| # | Repo | Artefakt | `buildVersion` |
|---|---|---|---|
| 1 | kotlin-units | kotlin-units | 1.0.0 |
| 2 | DCML | discrete-choice | 1.1.0 |
| 3 | actitoppNG | actitoppNG | 1.0.0 |
| 4 | mobitopp | mobitopp-reengineering, annotations, processor | 1.0.0 |
| 5 | mobiTopp-tutorial | – (Anwendung, wird nicht publiziert) | – |

Toolchain laut docs/05: **JDK 25** (`jvmToolchain(25)`), **Gradle 9.5** (Wrapper —
`./gradlew` genügt; nur ein JDK 25 muss auffindbar sein).

**Erwartung:** `ls ~/.m2/repository/` enthält danach die KIT-Artefakte (kotlin-units,
discrete-choice, actitoppNG, mobitopp-reengineering). Smoke-Test:
`cd validation/mobiTopp-tutorial && sh gradlew runLongTerm` → BUILD SUCCESSFUL
(47.414 Agenten, ~21 s laut docs/05); `sh gradlew runShortTerm` (~43 s).

actiTopp-Anbindung der Pipeline: Das Backend ruft den Gradle-Task
`runSynthesis -Pinput/-Poutput` im actitoppNG-Projekt auf
(`synthesis.actitopp.project-dir`, Default `../validation/actitoppNG`). Task +
`SynthesisRunner.kt` sind eine **lokale Ergänzung** im actitoppNG-Klon (docs/05,
Abschnitt „KIT actiTopp") — in einem frischen Klon fehlen sie und müssen nachgezogen
werden.

### 5b. Legacy-Stack bauen (für die logiTopp-Simulation)

Laut docs/05, Abschnitte „Legacy-Stack (logiTopp)" und „logiTopp-Simulationslauf":

- Der Legacy-Kern **`mobitopp:0.3.580` liegt auf Maven Central** — muss NICHT gebaut
  werden. (`mobiTopp_legacy/` liegt nur als Quell-Referenz da.)
- `logitopp`: Original-Build (Gradle 6.3/Java 12/jcenter) läuft auf aktuellen
  Toolchains nicht; der lokale Klon enthält ein **modernisiertes `build.gradle`**
  (Gradle 9, JDK-25-Toolchain, Lombok 1.18.46, `implementation`-Syntax, Publishing
  entschlackt). Damit `./gradlew publishToMavenLocal` → publiziert als
  `edu.kit.ifv.mobitopp:logitopp:0.0-SNAPSHOT` in mavenLocal.
- `mobitopp-rastatt`: ebenfalls modernisiertes `build.gradle` (mobitopp:0.3.580 von
  Central, Lombok 1.18.46, slf4j ergänzt); das alte Codegen-Gradle-Plugin wird
  umgangen — das gebündelte Fat-Jar `libs/edu.kit.ifv.codegen-0.2.2.90.jar` hat einen
  direkt aufrufbaren `GeneratorRunner.main(outputDir, gen-Dateien…)` (braucht
  `--add-opens java.base/java.lang=ALL-UNNAMED` wegen altem Guice) → Wahlmodell-Klassen
  generieren. Enthält den eigenen Main `ShortTermModuleDelivery` und den Task
  **`runDeliverySynthesis`**.

Die Pipeline ruft `sh gradlew runDeliverySynthesis -PconfigFile=…` im rastatt-Projekt
auf — verdrahtet über `synthesis.simulation.project-dir: ../validation/mobitopp-rastatt`
in `application.yml`. Fehlt das Projekt, wird die Simulations-Stufe SKIPPED.

**Erwartung:** `ls ~/.m2/repository/edu/kit/ifv/mobitopp/logitopp/` zeigt
`0.0-SNAPSHOT`; ein Pipeline-Lauf aus dem Frontend erreicht die Stufe
„Simulation (logiTopp)" mit Status DONE statt SKIPPED.

### 5c. populationsynthesis (optional)

Nur nötig, um das gebündelte KIT-Populations-Sample neu zu erzeugen — ein fertiges
Sample (`kit-population-sample.csv`) liegt als Backend-Resource im Repo; die Pipeline
läuft auch ohne diesen Klon. Details/Runner (`:exampleCensus:runSynthesisSample`):
docs/05, Abschnitt „KIT populationsynthesis".

## Schritt 6: Fixtures

1. **`validation/rastatt-test.osm`** (~3,8 MB): Straßennetz-Cache für SumoRunnerTest & Co.
   **Selbstheilend** — `SumoRunnerTest.loadOsm()` (im Code verifiziert) lädt bei fehlender
   Datei das Netz live über Overpass (inkl. Mirror-Fallback) und schreibt den Cache
   zurück. Nichts zu tun, außer den Test einmal online laufen zu lassen.
2. **`validation/kvv-gtfs.zip`** (121 MB, Stand 2026-07-07): GTFS-Cache für die ÖV-Tests
   (GtfsCropperTest, GtfsSourceTest, PtIntegrationTest):
   ```bash
   curl -L -o validation/kvv-gtfs.zip \
     https://www.nvbw.de/fileadmin/user_upload/service/open_data/fahrplandaten_mit_liniennetz/kvv.zip
   ```
   Lizenz: ODbL (NVBW/MobiData-BW) — bei Weitergabe/Publikation Attribution beachten.
3. **`validation/rastatt-rail-test.osm`** (~3,2 MB): nur für den **geparkten**
   Rail/Tram-Anlauf (Blocker-Befund in docs/06, Abschnitt „Phase 2 – SUMO"); für den
   normalen Betrieb nicht nötig.

**Erwartung:** `ls -la validation/*.osm validation/kvv-gtfs.zip` zeigt die drei Dateien
in den genannten Größenordnungen.

**Falle:** SUMO-/ÖV-Tests brauchen zusätzlich das **Demand-CSV**
`validation/mobitopp-rastatt/output/synthesis/results/demandsimulationResult.csv`
(+ `planned-tours.csv`) — das entsteht erst durch einen erfolgreichen Pipeline-Lauf
(Schritt 5b, dann einmal im Frontend generieren). Ohne: SumoRunnerTest/PtIntegrationTest
überspringen sich (Pfade im Test-Code verifiziert).

## Schritt 7: Bekannte Fallen (gesammelt)

| Falle | Symptom | Abhilfe |
|---|---|---|
| `/tmp` wird gereaped (macOS) | Caches/Dateien „verschwinden" nach Tagen | Nichts Persistentes nach `/tmp`; Projekt-Caches liegen deshalb in `validation/` |
| Finder-Duplikat `SynthesisApplication 2.class` | Spring Boot startet mit Bean-/Classpath-Fehlern nach Datei-Operationen im Finder | `cd backend && mvn clean` (verifizierter Fix) |
| Overpass-Rate-Limit | 504/429 bei wiederholten schweren Netz-Abfragen | Mirrors sind in `SynthesisProperties.Overpass` konfiguriert (kumi.systems, private.coffee); warten; produktiv: self-hosted Overpass (docs/05-Empfehlung) |
| Port-Konflikte 8090/4300 | `Address already in use` | Alten Prozess beenden (`lsof -i :8090` bzw. `:4300`); Ports fest in `application.yml` / `npm start`-Script |
| PEP 668 | `externally-managed-environment` bei pip | Nur ins venv installieren (Schritt 4) |
| RAM 16 GB | System friert bei LLM + Simulation | Ollama nie parallel zur logiTopp-JVM (Schritt 3) |
| `mvn -o` bei leerem Cache | Offline-Build schlägt fehl | Erster Lauf online (Schritt 1) |
| `SUMO_HOME` fehlt in der JVM-Umgebung | SUMO-Stufe SKIPPED trotz Installation | Variable in der startenden Shell/IDE-Run-Config setzen (Schritt 2) |
| actitoppNG-Lizenzwiderspruch | LICENSE-Datei = GPL vs. POM = MIT | Kein Setup-Blocker, aber **vor jeder Weitergabe/Veröffentlichung mit dem KIT klären** (aus der Projekthistorie überliefert; vor Weitergabe verifizieren) |

## Schritt 8: Verifikations-Matrix

Welche Komponente schaltet welches Feature/welchen Test frei? Alle bedingten Tests
überspringen sich sauber per `assumeTrue` (SKIPPED ≠ FAILED). Testlauf:
`cd backend && mvn -o test`. Testklassen liegen unter
`backend/src/test/java/de/synthesis/generation/`.

| Feature | Benötigt | Test/Check | Ohne Komponente |
|---|---|---|---|
| Kern-Pipeline (Artefakt-Generierung) | Schritt 1 | `GenerationPipelineE2ETest` (läuft offline: Luftlinie, Template-Verhalten) | — (Basis) |
| Reine Logik | Schritt 1 | `FeedbackMathTest`, `ModeChoiceCalibratorTest`, `ShapefileWriterTest` | — |
| logiTopp-Simulation (Pipeline-Stufe) | 5b (`mobitopp-rastatt` + logitopp in mavenLocal) | Simulations-Asserts in `GenerationPipelineE2ETest`; Stufe DONE im Frontend | Asserts übersprungen; Stufe SKIPPED |
| actiTopp-Verhalten | 5a (Kotlin-Stack + actitoppNG-`runSynthesis`) | Pipeline-Lauf mit `useActitopp`; Fallback-Log beobachten | Fallback auf Template-Generator |
| GTA/LLM (Achse A) + LLM-Moduswahl | Schritt 3 (Ollama erreichbar) | `GtaBehaviorTest`, `LlmModeChoiceTest` | Tests übersprungen; GTA fällt auf actiTopp zurück |
| SUMO (Achse B) | Schritt 2 + Demand-CSV (Schritt-6-Falle) + `rastatt-test.osm` (selbstheilend) | `SumoRunnerTest` | Test übersprungen; Stufe „SUMO (Verkehr)" SKIPPED |
| ÖV/Bus (Phase C) | Schritt 2 + 4 + Demand-CSV + `kvv-gtfs.zip` + env `SYNTHESIS_TRANSIT_VENV` | `PtIntegrationTest` | Test übersprungen; ÖV-Stufe SKIPPED |
| GTFS-Verarbeitung isoliert | `kvv-gtfs.zip` | `GtfsCropperTest`, `GtfsSourceTest` | Tests übersprungen |
| Frontend | Schritt 1 | `npx ng build`; http://localhost:4300 | — |

Endabnahme („alles da"): `mvn -o test` mit gesetztem `SUMO_HOME` und
`SYNTHESIS_TRANSIT_VENV` — **Erwartung: 0 Failures und keine übersprungenen
SUMO-/ÖV-/GTFS-Tests** (Skip-Meldungen im Log gegen die Matrix abgleichen); danach ein
voller Frontend-Lauf mit allen Zielmodellen, alle Stufen DONE.

## Provenienz & Wartung

- Stand: **2026-07-08**. Autor: ausscheidender Principal Engineer (Wissenstransfer).
- Ground Truth: Versionen aus `backend/pom.xml` / `frontend/package.json` abgelesen;
  SUMO-Pfadableitung aus `backend/src/main/java/de/synthesis/SynthesisProperties.java`;
  Config-Keys aus `backend/src/main/resources/application.yml`; Test-Skip-Bedingungen
  und Fixture-Pfade aus `backend/src/test/java/de/synthesis/generation/**`;
  `validation/`-Inventar per `ls` verifiziert; KIT-Build-Kette wörtlich aus
  `docs/05-validierung.md`; Rail/Tram-Blocker aus `docs/06-gta-integration-konzept.md`.
- Verifizierte Kommandos (real ausgeführt, Stand 2026-07-07/-08): Backend-/Frontend-Start,
  `mvn -o test`, `npx ng build`, `mvn clean` (Finder-Falle), venv-Aufbau (pandas 3.0.3 /
  rtree 1.4.1 / pyproj 3.7.2 auf Python 3.14), Ollama-Setup (brew, pull 4,7 GB),
  KVV-GTFS-curl (121 MB).
- Als „aus der Projekthistorie überliefert, verifizieren" markiert: Rolle des JDK 11 in
  `validation/tools/`, actitoppNG-Lizenzlage, Linux-Installationswege.
- Volatil (bei Nutzung > ~3 Monate nach diesem Stand prüfen): SUMO-Version/Framework-Pfad
  (1.26.0 fest im Code!), Ollama-Modellname/-größe, NVBW-GTFS-URL und Feed-Größe,
  pip-Paketversionen, KIT-Repo-Stände auf GitHub.
- Wartung: Bei Änderungen an `SynthesisProperties` (neue Stufen/Pfade),
  Dependency-Upgrades oder Umbauten in `validation/` diesen Skill mitziehen — er ist
  die einzige Reproduktionsanleitung für das gitignorierte `validation/`.
