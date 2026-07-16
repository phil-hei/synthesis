---
name: synthesis-architecture-contract
description: Der Architektur-Vertrag von SyntheSis — tragende Designentscheidungen mit Begründung, harte Invarianten und offene Schwachstellen. LADEN, bevor du Architektur änderst, eine Pipeline-Stufe hinzufügst/entfernst/umsortierst, Nebenläufigkeit anfasst (Executor-Pool, parallele Jobs), eine neue externe Abhängigkeit integrierst (Prozess, HTTP-Dienst, Python-Tool), das Artefakt-Layout unter data/scenarios/ änderst, Zonen-IDs oder Seeds anfasst, oder ein Refactoring von GenerationService planst. Auch laden, wenn du beurteilen musst, ob ein Vorschlag eine Invariante verletzt.
---

# SyntheSis: Architektur-Vertrag

Dieses Dokument ist der Vertrag: was gilt, warum es gilt, und was du nicht brechen darfst,
ohne die dokumentierte Begründung zu widerlegen. Publikum: Mid-Level-Entwickler und Agenten,
die Änderungen an Pipeline, Nebenläufigkeit oder Integrationen vornehmen.

## Wann NICHT nutzen

| Anliegen | Stattdessen |
|---|---|
| Einzelne Config-Flags / application.yaml-Details | `synthesis-config-and-flags` |
| Ein Lauf schlägt fehl, du debuggst | `synthesis-debugging-playbook` |
| Build, JDK/venv/SUMO-Installation | `synthesis-build-and-env` |
| App starten/bedienen | `synthesis-run-and-operate` |
| Distanz-/Zielwahl-Plausibilität verbessern | `synthesis-distanz-kampagne` |
| Historische Fehlschläge und ihre Ursachen | `synthesis-failure-archaeology` |
| Fachliche Verkehrsmodellierungs-Begriffe | `verkehrsmodellierung-reference` |

## Big Picture: Zwei-Achsen-Modell

Kernidee: **Verhaltensquelle (Achse A)** und **Zielmodell (Achse B)** sind unabhängig
austauschbare Runner. Der Nutzer kombiniert frei.

| Achse | Optionen | Auswahl-Feld |
|---|---|---|
| A – Verhaltensquelle | `actitopp` (Default) · `gta` (LLM) · `template` | `Scenario.population.behaviorSource` (`behaviorSourceEffective()` löst Legacy-Flag `useActitopp` auf) |
| B – Zielmodell (Mehrfachauswahl) | `logitopp` · `mobilecity` · `sumo` | `Scenario.targetModels`; `targets()` liefert Default `logitopp+mobilecity` bei leer/null |

**WARUM:** Vergleichbarkeit — identische synthetische Population, nur die Quelle tauschen,
KPI-Differenz messen (E2E bewiesen 2026-06-26). Plus Risikostreuung: fällt eine Achse aus
(LLM down, SUMO fehlt), bleiben die anderen Kombinationen lauffähig.

## Die 14 Pipeline-Stufen und ihre Gates

Orchestrator: `backend/src/main/java/de/synthesis/generation/GenerationService.java`,
Methode `run()` — ein ~50-Zeilen-Ablauf über private `stage*`-Methoden. Stufennamen aus
`GenerationService.STAGES` (Zeile 55). Geteilter Zustand liegt explizit im inneren
`PipelineContext` (Szenario, Job, artifacts-Pfad, `Random`, memoisiertes OSM-Netz,
Cross-Stage-Felder wie `carTravelTimeMin`) — bewusst statt Lambda-Captures.

Achtung: Das Javadoc an `run()` sagt „13 Pipeline-Stufen" — das ist veraltet, es sind 14
(Stand 2026-07-08, `STAGES.size() == 14`).

| # | Stufe | Gate / SKIPPED-Bedingung | FAILED möglich? |
|---|---|---|---|
| 1 | Zonierung | immer; `zoning.mode` `grid` (Bbox-Raster, Default) \| `inspire` (EPSG:3035-Gitter, nur Europa, seit 2026-07-13); Zellgröße wird auf `synthesis.max-zones` (600) auto-angehoben (grid) bzw. auf die nächstgröbere kanonische Auflösung 100 m/1 km/10 km quantisiert (inspire) | Lauf bricht ab |
| 2 | OSM-Daten (POIs) | SKIPPED wenn `population.useOsmData=false`; Overpass-Fehler ⇒ Fallback synthetisch, Stage bleibt DONE mit `message` | nein (Fallback) |
| — | `preparePopulationDistribution` | **kein eigener Schritt**, läuft zwischen 2 und 3 (Nutzerwert > Upload-Raster > OSM-Schätzung > Dichte) | Lauf bricht ab |
| 3 | Attraktivitäten | immer; ohne POIs synthetisch; `externalTrips` hängt externe Zielzonen an | Lauf bricht ab |
| 4 | Bevölkerungssynthese | immer | Lauf bricht ab |
| 5 | Aktivitätenpläne (Achse A) | immer; Quelle per Fallback-Kette `gta → actitopp → template` | nein (Fallback) |
| 6 | Matrizen | immer; GraphHopper-Routing wenn `useRealRouting`, sonst/Fallback Luftlinie | Lauf bricht ab |
| 7 | Logistik | immer | Lauf bricht ab |
| 8 | Legacy-Export | immer (mobiTopp-0.3.x-/logiTopp-Layout unter `legacy/`) | Lauf bricht ab |
| 9 | MobileCity-Export (B) | SKIPPED wenn `mobilecity` nicht in `targets()` | Lauf bricht ab |
| 10 | Manifest | immer (`manifest.json` inkl. `dataQuality`-Block) | Lauf bricht ab |
| 11 | Simulation (logiTopp) (B) | SKIPPED wenn `logitopp` nicht in `targets()`, Simulation disabled oder `mobitopp-rastatt` nicht gefunden; optional Kalibrierschleife (`calibrateModeChoice`) | Lauf bricht ab |
| 12 | SUMO (Verkehr) (B) | SKIPPED wenn `sumo` nicht in `targets()`, SUMO nicht verfügbar, oder `simulation/results/demandsimulationResult.csv` fehlt (braucht Stufe 11) | ja: FAILED + message |
| 13 | Netz-Feedback | SKIPPED wenn `population.sumoFeedback=false`, SUMO-Stage nicht DONE / `sumo/edgedata.xml` fehlt, `logitopp` nicht gewählt / `carTravelTimeMin` fehlt, oder Zonen > `synthesis.sumo.feedback-max-zones` (150) | ja: FAILED + message |
| 14 | LLM-Moduswahl | SKIPPED wenn `population.llmModeChoice=false`, LLM nicht erreichbar, oder logiTopp-Trips fehlen | ja: FAILED + message |

**Invariante (Status-Semantik):** Der Nutzer muss *bewusste Abwahl* (SKIPPED) von *Fehler*
(FAILED + `message`) unterscheiden können. Neue optionale Stufen implementieren beide Pfade —
siehe Kommentar in `stageSumo` („FAILED statt SKIPPED: der Nutzer soll Fehler von bewusster
Abwahl unterscheiden können"). Stufen 1–11 werfen bei Fehlern durch und beenden den Lauf als
FAILED; die nachgelagerten 12–14 fangen selbst und markieren nur die eigene Stage FAILED.

## Tragende Entscheidungen mit WARUM

### 1. Externe schwere Modelle als LOKALE Prozesse, nie eingebettet

logiTopp läuft via `sh gradlew runDeliverySynthesis` im Projekt `validation/mobitopp-rastatt`
(`SimulationRunner`), actiTopp via gradlew in `validation/actitoppNG` (`ActitoppRunner`),
SUMO als native Binaries (`SumoRunner`), das LLM per HTTP (Ollama, OpenAI-kompatibel),
gtfs2pt in einem gekapselten Python-venv (`TransitRunner`).

**WARUM (drei Gründe, alle gelten):**

| Grund | Detail |
|---|---|
| Lizenz-Isolation | logiTopp/mobiTopp_legacy sind **GPL-3.0**. Prozessgrenze statt Linken hält unseren Code lizenzrechtlich getrennt. |
| Speicher-Isolation | Der logiTopp-Lauf braucht bis zu **12 GB Heap** (`maxHeapSize = '12G'` in `validation/mobitopp-rastatt/build.gradle`). In unserer JVM wäre das nicht tragbar. |
| Absturz-Isolation | Ein OOM/Crash des externen Tools tötet nur den Kindprozess; der Job wird FAILED, das Backend lebt weiter. |

**Invariante — das Prozess-Muster:** Jede neue Integration folgt dem Vierklang
`ProcessBuilder` + `waitFor(timeout)` mit `destroyForcibly()` + Ausgabe in Log-Datei unter
`artifacts/` + `exitValue()`-Prüfung mit letzten Log-Zeilen in der Exception. Referenz:
`SumoRunner.exec(...)` (Zeile ~671) und `SimulationRunner.run(...)` (Zeile ~60). Dazu gehört
immer ein `isAvailable()`-Check, der die Stufe sauber SKIPPED statt sie crashen zu lassen.

### 2. Dateibasierte Artefakte als API — kein DB-Server

`data/scenarios/<id>/scenario.json` (via `ScenarioRepository`) plus der `artifacts/`-Baum
darunter sind der Vertrag zwischen den Stufen, dem Frontend (Downloads) und externen Tools
(logiTopp liest den Legacy-Export, SUMO schreibt nach `artifacts/sumo/`).

**WARUM:** Inspizierbarkeit (jedes Zwischenergebnis ist eine Datei, die man öffnen kann),
Reproduzierbarkeit (Ordner kopieren = Lauf archivieren), simudrive-Vorbild. Ein DB-Server
würde genau diese Eigenschaften kosten und nichts liefern, was das MVP braucht.

**Konsequenz:** Wer ein Artefakt-Format ändert, ändert eine öffentliche API — Frontend-Downloads,
`manifest.json`-Konsumenten und die externen Tool-Konfigurationen (`run-config.yaml`) hängen dran.

### 3. Nebenläufigkeit: Executor-Pool = 1, bewusst seriell

`GenerationService` Zeile 68: `Executors.newFixedThreadPool(1)` mit erklärendem Kommentar.
Weitere Jobs warten sichtbar als QUEUED; `start()` lehnt einen zweiten Job für dasselbe
Szenario ab, solange QUEUED/RUNNING.

**WARUM:** 16-GB-Zielmaschine, die Simulationsstufe startet eine 12-GB-logiTopp-JVM, und alle
Läufe teilen sich das Gradle-Arbeitsverzeichnis `validation/mobitopp-rastatt`. Parallele
Generierung ist ein **bekannter, real aufgetretener Kollisionsfall** — gehärtet in Commit
`bd43219` („Härtung: serielle Generierung, OSM-Reuse, FAILED-Status, SUMO-Env-Defaults").

**INVARIANTE:** Pool NICHT auf >1 stellen ohne (a) Workdir-Isolation pro Lauf für
mobitopp-rastatt und (b) ein RAM-Budget-Konzept. Beides existiert heute nicht.

### 4. Graceful-Degradation-Leiter

Jede externe Abhängigkeit hat eine definierte Ausweichkette:

| Abhängigkeit | Leiter |
|---|---|
| Overpass | Primär-Endpunkt → Mirror-Liste (`SynthesisProperties.Overpass.allEndpoints()`) → synthetische POIs bzw. Luftlinien-Matrizen |
| actiTopp | → Template-Generator |
| GTA-LLM | → actiTopp → Template (Kette in `stageActivityPlans`) |
| SUMO / LLM-Moduswahl / Transit(gtfs2pt) | Tool fehlt → Stage sauber SKIPPED mit Log-Begründung |

**INVARIANTE:** Jede *neue* Abhängigkeit MUSS entweder eine Ausweichstufe haben oder sauber
SKIPPED liefern. Ein Lauf darf nie daran scheitern, dass ein optionales Tool fehlt.

### 5. Zonen-ID-Dualität (kritischste Daten-Invariante)

Es gibt zwei Zonen-ID-Räume, und sie werden von verschiedenen Konsumenten erwartet:

| Konsument | ID-Raum | Beleg |
|---|---|---|
| Demand-Daten: `homeZone` (household.csv), `zoneId` (fixedDestination.csv, opportunity.csv) | **OID/matrixColumn** | docs/05-validierung.md, Befund A2 |
| `distribution-centers.csv` (`DistributionCenterParser.getByExternalId`) | **EXTERNAL id** | docs/05-validierung.md, Befund A9 |

**WARUM kritisch:** Verwechslung erzeugt **still** falsche Zuordnungen — im A2-Fall wurden
externe IDs kommentarlos verworfen (0 Haushalte). Kein Fehler, kein Crash, nur leises
falsches Ergebnis. Bei jeder Änderung an Zonen-Exporten beide Befunde in docs/05 gegenlesen.

**INSPIRE-Ergänzung (2026-07-13, Commit `cb2f40a`):** Im Modus `zoning.mode=inspire` bleibt die
numerische ID-Mechanik unverändert (fortlaufend ab 100 001, OID/matrixColumn wie gehabt);
zusätzlich trägt `Zone.name` die INSPIRE-Zell-ID (`CRS3035RES<res>mN<north>E<east>`) und
`Zone.laeaE/laeaN/laeaRes` die EPSG:3035-Zellkoordinaten — das Punkt-Containment läuft dann in
LAEA-Metern statt über die Lat/Lon-Bbox. Den `name` nie als numerische ID interpretieren; er ist
der europaweit vergleichbare Zell-Code (verlustfreier Join zu Zensus-2022-/GEOSTAT-Rastern).

### 6. Reproduzierbarkeit: alles hängt am Szenario-Seed

`scenario.population.seed` (Default 42) treibt sämtliche Stochastik:

| Zufallsquelle | Ableitung |
|---|---|
| Pipeline-`Random` | `new Random(scenario.population.seed)` im `PipelineContext`-Konstruktor |
| GTA-LLM-Requests | `seed * 31 + persona.hashCode()` (`GtaBehaviorRunner`, Zeile ~253) |
| LLM-Moduswahl | `seed * 131 + bandIndex` (`LlmModeChoiceRunner`, Zeile ~171) |

**INVARIANTE:** Neue Zufallsquellen (auch LLM-Sampling!) hängen deterministisch am
Szenario-Seed. Kein `new Random()` ohne Seed, kein System-Zufall.

### 7. OSM-Netz-Memoisierung pro Lauf — bewusst KEIN Disk-Cache

Das OSM-Straßennetz wird von Matrizen-Routing UND SUMO gebraucht; es liegt als
`AtomicReference<String> roadNetwork` im `PipelineContext` und wird via `roadNetworkOsm()`
genau einmal pro Lauf geladen.

**WARUM kein Disk-Cache:** Stale-Risiko bei Regionswechsel — `artifacts/` wird zwischen
Läufen nicht geleert; ein persistierter Netzabzug einer alten Region würde still
weiterverwendet. Memoisierung nur im Lauf-Kontext ist die sichere Variante.

### 8. SUMO-Pfad nativ, Python nur gekapselt

Der SUMO-Kernpfad ist rein nativ: OSM → `netconvert` → `duarouter` (`--mapmatch` erledigt
Geo→Kante) → `sumo`. Kein Python im Kern. Einzige Ausnahme: `gtfs2pt.py` im Transit-venv
(`synthesis.transit.venv-python`), und diese Stufe ist optional (venv fehlt ⇒ ÖV SKIPPED).
ÖV-Modi sind auf `bus` beschränkt (`SynthesisProperties.Transit.modes`, Rail/Tram-Befund
siehe docs/06, Abschnitt „Phase 2 – SUMO").

### 9. Konfigurations-Aufteilung

| Ebene | Ort | Beispiele |
|---|---|---|
| Betriebs-Achsen (Deployment) | `SynthesisProperties` (`@ConfigurationProperties("synthesis")`) | Pfade zu Tools, Timeouts, `max-zones`, Overpass-Mirrors, LLM-Endpoint |
| Nutzer-Szenario-Flags | `Scenario.java` (persistiert als `scenario.json`) | `behaviorSource`, `targetModels`, `sumoFeedback`, `publicTransport`, Seed |
| Frontend-Spiegel | `frontend/src/app/core/models/scenario.model.ts` | muss bei Scenario-Änderungen mitgezogen werden |

Details und alle Flags → Skill `synthesis-config-and-flags`.

### 10. Frontend-Vertrag

Angular strict/AOT, typisierte Ergebnis-Modelle in `frontend/src/app/core/models/results.model.ts`,
Studio + Szenario-Vergleich als Feature-Komponenten, Dev-Proxy `/api` → `http://localhost:8090`
(`frontend/proxy.conf.json`). Backend-JSON-Änderungen brauchen immer den Modell-Spiegel.

## Offene Schwachstellen (ehrlich, Stand 2026-07-08)

| Schwachstelle | Status / Verweis |
|---|---|
| Öffentlicher Overpass rate-limitet; Mirrors mildern nur | Produktions-Fix (self-hosted Overpass oder PBF-Import) offen |
| `GenerationService` ist ~1050 Zeilen (nicht ~900 — nachgezählt) | Klasse-pro-Stufe wäre der mechanische Folgeschritt; Orchestrator-Kern ist bereits schlank |
| Distanz-Sensitivität der Zielwahl: Ø-Weg geroutet ~2,9 km (Achtung: Luftlinien-kontaminierte Läufe zeigen ~2,0 km — Fallback-Signatur, docs/05) vs. MiD 12,5 km | größte Plausibilitätslücke → `synthesis-distanz-kampagne` (dort auch der Kontaminations-Check) |
| Rail/Tram in SUMO blockiert (duarouter-Fußgänger-Snapping nahe Bahnhöfen) | Befund + Lösungsweg in docs/06, „Phase 2 – SUMO"; ÖV bleibt Bus-only |
| gtfs2pt-Erstlauf ~20–25 min (tracemapper) | gecacht; Timeout 25 min (`synthesis.transit.timeout-minutes`) |
| `run-config.yaml` wird mit absoluten Pfaden geschrieben | `SimulationRunner.writeRunConfig` — Artefakt-Ordner sind dadurch nicht portabel |
| Keine CI; Push nur via GitHub Desktop | manuelle Disziplin nötig |
| Stale Javadoc: `run()` sagt „13 Stufen", es sind 14 | kosmetisch, aber verwirrend beim Zählen |

## Provenienz & Wartung

**Stand: 2026-07-08**, verifiziert gegen den Code auf `main` (Commit `4ad6382`). Alle
Klassen-, Pfad- und Flag-Aussagen wurden direkt im Repo nachgelesen (GenerationService,
Scenario, SynthesisProperties, SimulationRunner, SumoRunner, docs/05, docs/06, docs/07).
Zeilennummern sind Zirka-Angaben und driften; Namen und Muster sind das Verlässliche.

Re-Verifikations-Greps (im Repo-Root ausführen, bevor du dich auf Details stützt):

```bash
# Stufenliste + Anzahl (Erwartung: 14 Namen)
grep -n -A4 'STAGES = List.of' backend/src/main/java/de/synthesis/generation/GenerationService.java

# Executor-Pool weiterhin 1?
grep -n 'newFixedThreadPool' backend/src/main/java/de/synthesis/generation/GenerationService.java

# Seed-Ableitungen unverändert?
grep -rn 'seed \* 31\|seed \* 131\|baseSeed \* ' backend/src/main/java/de/synthesis/generation/pipeline/

# Prozess-Muster-Vertreter (ProcessBuilder-Familie)
grep -rln 'ProcessBuilder' backend/src/main/java/de/synthesis/generation/pipeline/

# Gate-Flags im Szenario noch vorhanden?
grep -n 'sumoFeedback\|llmModeChoice\|publicTransport\|behaviorSource\|targetModels' \
  backend/src/main/java/de/synthesis/scenario/Scenario.java

# Zonen-ID-Dualität (Befunde A2/A9)
grep -n 'OID/matrixColumn\|getByExternalId' docs/05-validierung.md

# ÖV-Modi weiterhin Bus-only?
grep -n 'modes = ' backend/src/main/java/de/synthesis/SynthesisProperties.java
```

Wartungsregel: Wenn ein Grep nicht mehr das Erwartete liefert, zuerst dieses Skill-Dokument
aktualisieren, dann die Änderung fortsetzen. Volatile Zahlen (Zeilenzahlen, Timeouts,
Limits wie `max-zones=600`, `feedback-max-zones=150`, `max-trips=4000`) gelten nur zum
oben genannten Stand.
