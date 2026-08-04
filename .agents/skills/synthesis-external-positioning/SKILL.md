---
name: synthesis-external-positioning
description: >
  Externe Positionierung von SyntheSis: was gegenüber der Fachwelt behauptet werden darf und was
  nicht. Laden, WENN du (a) ein Paper schreibst oder reviewst, (b) einen Vortrag, ein Poster oder
  eine Projektwebseiten-Aussage vorbereitest, (c) ein Release oder eine Weitergabe an Partner
  (Takomat, KIT) planst, (d) einen Claim formulieren sollst ("multimodal", "kalibriert",
  "reproduzierbar", "neuartig"), (e) eine Lizenzfrage hast (GPL, EPL, ODbL, Modell-Lizenzen,
  actitoppNG-Widerspruch), (f) wissen musst, was hier NEU ist vs. was aus dem GTA-Paper stammt,
  (g) Reproduzierbarkeits-Anforderungen für eine Veröffentlichung festlegst, oder (h) gefragt
  wirst "was dürfen wir behaupten?". Stichwörter: Paper, Publikation, CHI, Zitat, Related Work,
  Claim, Oversell, Lizenz, GPL-3.0, copyleft, ODbL, Attribution, Release, Weitergabe,
  Reproduzierbarkeit, Seed-Protokoll, Limitation, Neuheit, Beitrag, contribution.
---

# SyntheSis – Externe Positionierung (Papers, Releases, Claims, Lizenzen)

Dieses Runbook regelt, **was SyntheSis nach außen behaupten darf, welcher Beweis vor welcher
Behauptung stehen muss, und wie die Lizenz- und Datenlage aussieht**. Es ist die
**Anti-Oversell-Instanz** des Projekts: Im Zweifel wird die schwächere Formulierung gewählt und
die Limitation genannt. Zielgruppe: Entwickler und KI-Modelle ohne Projektvorwissen.

**Kontext in einem Satz:** SyntheSis (www.synthesis-projekt.de) ist ein Web-Tool, das aus einer
Kartenregion ein agentenbasiertes Verkehrsnachfragemodell generiert (Zwei-Achsen-Modell:
Verhaltensquelle actiTopp/GTA-LLM × Zielmodell logiTopp/SUMO/MobileCity-Export); die Ambition ist
**beides gleichrangig**: publizierbare Forschung **und** belastbares Partner-Tool (Takomat/KIT).
Ansatz: `docs/07-ansatz-zusammenfassung.md`; Phasen-Historie und Lizenzkapitel:
`docs/06-gta-integration-konzept.md`.

## Wann diesen Skill NICHT nutzen

| Anliegen | Stattdessen |
|---|---|
| Code ändern, committen, Gates vor Commit | `synthesis-change-control` |
| Ein Symptom triagieren (Pipeline-Stufe rot) | `synthesis-debugging-playbook` |
| Was wurde früher versucht und verworfen (Rail/Tram, LLM-Prompt-Verankerung …) | `synthesis-failure-archaeology` |
| Architektur-Invarianten (Prozess-Trennung als Design) | `synthesis-architecture-contract` |
| Fachbegriffe (MiD, MNL, MSA, Modal-Split, RMSE …) | `verkehrsmodellierung-reference` |
| Flags/Konfig-Schlüssel im Detail | `synthesis-config-and-flags` |
| Build/Umgebung (Maven, SUMO_HOME, venv, Ollama-Setup) | `synthesis-build-and-env` |
| Anwendung starten und einen Lauf durchführen | `synthesis-run-and-operate` |
| KPI-Skripte, Auswertungs-Tooling | `synthesis-diagnostics-and-tooling` |
| Validierungs-Methodik (wie wird gegen MiD verglichen) | `synthesis-validation-and-qa` |
| Doku-Stil, Schreibkonventionen | `synthesis-docs-and-writing` |
| Distanz-Schwäche aktiv beheben (Kampagne) | `synthesis-distanz-kampagne` |
| Beweise/Analysen selbst durchführen | `synthesis-proof-and-analysis-toolkit` |
| Offene Forschungsfragen, nächste Experimente | `synthesis-research-frontier` |
| Experiment-Design, wissenschaftliche Methodik | `synthesis-research-methodology` |

Dieser Skill sagt dir, **was nach außen gesagt werden darf** – nicht, wie man es intern baut,
misst oder weiterentwickelt.

---

## 1. Ökosystem-Karte: worauf SyntheSis aufsetzt

Wer nach außen kommuniziert, muss die Abgrenzung kennen: **Was ist fremd, was ist eigen.**
Herkunfts- und Integrationsdetails: `docs/06-gta-integration-konzept.md` (Abschnitt A) und
`docs/01-analyse.md`.

| Komponente | Herkunft/Rolle | Verhältnis zu SyntheSis |
|---|---|---|
| **mobiTopp** (Kotlin-Rewrite) | KIT IFV, `kit-ifv/mobitopp` | Nur einmalige Phase-0-Formatvalidierung; NICHT im Laufzeitpfad |
| **mobiTopp legacy** 0.3.580 | KIT IFV, Maven Central | Simulationskern, auf dem logiTopp läuft; Modus-/Zielwahl **Rastatt-kalibriert** |
| **logiTopp** | KIT IFV, `kit-ifv/logitopp` | Paketlogistik-Simulation; läuft je Szenario als **externer Prozess** |
| **actiTopp/actitoppNG** | KIT IFV, `kit-ifv/actitoppNG` | Default-Verhaltensquelle (Wochen-Aktivitätenpläne), externer Runner |
| **populationsynthesis** (IPU) | KIT IFV | IPU lief **offline** (Marne-Beispiel); nur der eingefrorene Output wird wiederverwendet |
| **SUMO 1.26** | Eclipse Foundation | Mikroskopisches Zielmodell (Achse B), externe Prozesse (`netconvert`/`duarouter`/`sumo`) |
| **GraphHopper 8.0** | Open Source | Eingebettete Routing-Bibliothek (Matrizen, Routenoptionen) |
| **Ollama + Qwen2.5-7B-Instruct** | Open Source | Lokales LLM für die GTA-Verhaltensquelle |
| **MobileCity / Takomat** | Projektpartner | **District-aggregiertes Strategiemodell.** Importiert ausschließlich aggregierte District-Tabellen, **keine Einzeltrips/-touren** – per E-Mail bestätigt am 2026-07-03 (`docs/06`, Phase 5) |
| **NVBW / MobiData-BW** | Land BW | GTFS-Feed-Quelle für den ÖV/Bus-Pfad (Auto-Fetch nach Region, Upload-Fallback) |
| **OSM / Overpass** | OpenStreetMap | Netz, POIs; einzige (optionale) Remote-Abhängigkeit zur Laufzeit |

**Was bewusst eigen ist (docs/06 A.3):** Zonierung, Attraktivitäten, Matrizen/Visum-Export,
Bevölkerungs-Verteilung auf Zonen, Legacy-Export-Adapter, MobileCity-Export, die gesamte
Pipeline-Orchestrierung, GTA-Adapter (`GtaBehaviorRunner`, `LlmModeChoiceRunner`), Kalibrier-Loop
(`ModeChoiceCalibrator`), SUMO-Integration inkl. Netz-Feedback (`SumoRunner`, `TransitRunner`).
Die Wahlmodelle selbst (Modus/Ziel/Paket/Tour) sind echtes KIT.

### 1.1 Referenz-Paper: GTA (Pflicht-Zitat, keine Neuheits-Behauptung)

Lämmer, Colley, Ebel (2026): *GTA: Generative Traffic Agents for Simulating Realistic Mobility
Behavior*, CHI '26. Open Source: github.com/ciao-group/Generative-Traffic-Agents.

**Regel (nicht verhandelbar):** GTA ist **architektonisch nahezu deckungsgleich** mit unserem
GTA-Pfad (Profil → LLM-Persona → Tagesplan → Moduswahl → Simulation; Validierung gegen MiD;
dokumentiert in `docs/06` B.1). Jede externe Aussage zum LLM-Verhaltenspfad **zitiert dieses
Paper**. Die Grundidee „LLM-Personas erzeugen Verkehrsverhalten" wird **niemals** als eigener
Beitrag beansprucht. Unser Beitrag ist die Integration, Bündelung, Komposition und der
Negativ-Befund (siehe Abschnitt 2).

---

## 2. Was ist HIER neu – Claim-Kandidaten mit Beweisstand

Alle Zahlen sind **überlieferte Einzellauf-Ergebnisse** aus `docs/06`/`docs/07` (Datum jeweils
angegeben), keine mehrfach replizierten Messreihen. **Vor jeder Publikation gilt für JEDEN
Claim: mit mindestens N=5 Seeds re-runnen, Streuung berichten** (Protokoll: Abschnitt 6;
Werkzeuge: `synthesis-proof-and-analysis-toolkit`).

| # | Claim-Kandidat | Beweisstand (überliefert) | Vor Publikation zwingend |
|---|---|---|---|
| a | **Persona-Signatur-Bündelung** macht LLM-Verhalten auf ECHTEN Populationen praktikabel (1 LLM-Call je Signatur Erwerbsstatus × Altersband × Geschlecht statt je Person) | 651 Personen → 18 LLM-Calls (~140 s), dokumentiert 2026-06-26 (`docs/07` §4, `docs/06` Phase 3b) | Re-Run mit ≥5 Seeds; Verhältnis Calls/Personen für ≥2 Regionsgrößen; Abgrenzung: Bündelung reduziert Vielfalt (gleiche Signatur = gleicher Plan-Prototyp) als Limitation nennen |
| b | **Zwei-Achsen-Austauschbarkeit**: identische Population, Verhaltensquelle getauscht (GTA vs. actiTopp), Ende-zu-Ende messbare KPI-Differenz | Voller In-App-Lauf, Seed 42, 651 Personen: GTA trifft Wege/Person·Tag = 3,1 (= MiD), wie actiTopp; 2026-06-26 (`docs/06` Phase 3b) | Re-Run mit ≥5 Seeds; Vergleich über MEHRERE KPIs (nicht nur Wegehäufigkeit) im Szenario-Vergleichs-Panel; klarstellen, dass Wegehäufigkeit erst NACH Prompt-Tuning stimmt (roh: ~5,7 Aktivitäten/Tag, siehe `synthesis-failure-archaeology`) |
| c | **Komposition GTA × Kalibrierung**: On-demand-ASC-Kalibrierung wirkt auch auf LLM-generierten Plänen | Auf GTA-Output: Modal-Split-RMSE 0,163 → 0,070 (Fuß 50 %→26 %, Pkw 20 %→29 %); 2026-06-26 (`docs/06` Phase 3b) | Re-Run mit ≥5 Seeds; RMSE-Definition und Zielvektor (MiD 2017 bundesweit) explizit angeben; Konvergenzverlauf zeigen |
| d | **Seed-reproduzierbare LLM-Pläne**: gleicher Szenario-Seed → identische Pläne (Seed je Anfrage = `scenario.seed*31 + persona.hashCode`, Ollama respektiert `seed`) | Test `sameSeedIsReproducible` grün; 2026-06-26 (`docs/06` Phase 3b) | Byte-Identität über ≥3 unabhängige Läufe UND nach Ollama-Neustart nachweisen; Gültigkeitsgrenze nennen: gilt für DIESE Ollama-Version + DIESES Modell auf DIESER Hardware – nicht als modell-/backendunabhängig verkaufen |
| e | **Ehrlicher Negativ-Befund als Beitrag**: rohe LLM-Moduswahl hat systematischen Aktiv-Modi-Bias (Rad ~35–45 % vs. MiD 11 %; RMSE ~0,19 vs. kalibriertes MNL 0,07); quantitative MiD-Verankerung im Prompt wirkungslos | Dokumentiert 2026-06-26 (`docs/06` Phase 3c); Revert der Prompt-Verankerung in `synthesis-failure-archaeology` | Re-Run mit ≥5 Seeds und ≥2 Prompt-Varianten (zeigen, dass der Befund robust ist, nicht Prompt-Pech); Ursachen-Hypothese (fehlende ASCs) als Hypothese kennzeichnen, nicht als bewiesen |
| f | **Nachfrage↔Netz-Gleichgewicht** über logiTopp+SUMO mit MSA-Dämpfung | Rastatt, volle Population: Konvergenz nach 2 Iterationen (Δ 0,33 → 0,008), Ø Faktor 1,32 auf 324 Relationen, Pkw 27 %→24 %; 2026-07-06 (`docs/06` Phase 2) | Re-Run mit ≥5 Seeds und ≥2 Regionen/Größen; „Konvergenz in 2 Iterationen" nur als Einzelbeobachtung berichten, NICHT als allgemeine Eigenschaft; Kappe `feedbackMaxZones=150` als Skalierungsgrenze nennen |

**Formulierungs-Schablone für jeden Claim:** „bewiesen durch [Lauf/Test X] am [JJJJ-MM-TT];
vor Publikation mit N Seeds re-runnen". Ein Claim ohne ausgefüllte Schablone geht nicht in ein
Paper, einen Vortrag oder eine Release-Note.

---

## 3. Claim-Disziplin: Behauptung → Pflicht-Beweis

Diese Tabelle ist die Checkliste vor JEDER externen Formulierung (Abstract, Folie, README-Zeile,
Release-Note, Partner-Mail).

| Behauptung | Erlaubt? | Pflicht-Beweis / Pflicht-Fußnote |
|---|---|---|
| „multimodal" (SUMO) | Ja, **nur mit Scope-Fußnote** | Scope: Pkw, Rad, Fuß, Lieferverkehr (Lkw/Lastenrad), ÖV = **nur Bus**. Rail/Tram wurde versucht und am 2026-07-07 zurückgerollt (duarouter-Mapmatch snappt Fußgänger auf Gleise; Blocker + Lösungsweg in `docs/06` Phase 2). „Multimodal inkl. Schiene" ist FALSCH. |
| „kalibriert" | Ja, nur mit Metrik + Ziel | Immer angeben: RMSE-Wert, Zielvektor (z. B. MiD 2017 bundesweit oder RegioStaR-Preset), Iterationszahl. Kalibriert werden NUR die Modus-Konstanten (ASCs), nicht die Sensitivitäten (`docs/06` D). |
| „reproduzierbar" | Ja, nur mit Seed-Protokoll | Seed-Protokoll nach Abschnitt 6 beilegen. Für den GTA-Pfad zusätzlich: Ollama-Version + Modell-Tag fixiert; Reproduzierbarkeit ist backend-/hardwaregebunden. |
| „funktioniert für beliebige Regionen (Deutschland)" | **NEIN** | Die Wahlmodell-Parameter sind für **Rastatt geschätzt** („Rastatt-Geschmack", `docs/06` A.6); die ASC-Kalibrierung + RegioStaR-Presets sind **Näherungen** (Presets = Richtwerte nach MiD-Stadt-Land-Muster, keine offiziellen Tabellen; `docs/06` Phase 1). Erlaubt: „auf frei wählbare Regionen anwendbar, mit Rastatt-geschätzten Sensitivitäten und regional kalibrierten Konstanten". |
| „trifft MiD" | Nur je Kennzahl einzeln | Wegehäufigkeit ≈ MiD: ja (3,1 Wege/Person·Tag). Modal-Split: nur NACH Kalibrierung. Weglänge: **NEIN** (siehe nächste Zeile). |
| Weglängen-Aussagen | Nur mit Limitation | **Pflicht-Limitation, solange offen:** Ø Weglänge ~2,9–3 km vs. MiD 12,5 km (kleines Gebiet, Rastatt-Distanz-Disnutzität deckelt Fernwege; `docs/05`, Stand 2026-07-08 offen; Behebungsarbeit: `synthesis-distanz-kampagne`). Jede Publikation, die Distanzen berührt, nennt das. |
| „LLM-Agenten erzeugen realistisches Verkehrsverhalten" (Grundidee) | Als **eigene** Neuheit: NEIN | Immer Lämmer/Colley/Ebel CHI '26 zitieren (Abschnitt 1.1). Eigener Beitrag nur gemäß Tabelle in Abschnitt 2. |
| „vollständig integriert" (KIT-Stack) | Nur mit Architektur-Vorbehalt | Funktional integriert ja, aber als **externe lokale Prozesse**, nicht eingebettet (`docs/06` A.2, A.5) – das ist zugleich die GPL-Begründung (Abschnitt 4). |
| „echte Bevölkerungsdaten" | **NEIN** | Synthetischer Seed + öffentliche Aggregate; KIT-IPU-Sample ist Marne-basiert und eingefroren (Abschnitt 5). |
| „interaktiv/Echtzeit" (GTA, Kalibrierung, Feedback) | NEIN | Alles asynchrone Batches: Kalibrierung Minuten–~1 h, GTA ~30–40 s je Persona-Call auf M1/16 GB (Werte überliefert 2026-06-26, `docs/06` Phase 3a/D). |

**Grundregel:** Jede Zahl in einer externen Aussage trägt Datum und Quelle (`docs/0X`, Abschnitt).
Zahlen ohne Datum sind Oversell.

---

## 4. Lizenz-Gemengelage

Ground Truth: `docs/06-gta-integration-konzept.md`, Abschnitt E (LICENSE-Dateien dort geprüft;
Doku-Stand 2026-07-08). Vor einem Release diese Tabelle gegen docs/06 E re-verifizieren
(Befehle: „Provenienz & Wartung").

| Komponente | Lizenz | Konsequenz für Release/Weitergabe |
|---|---|---|
| **mobiTopp legacy + logiTopp** | **GPL-3.0** (copyleft) | **DER Grund für die Prozess-Trennung**: läuft ausschließlich als externer Gradle-/JVM-Prozess. **Niemals als JAR ins Backend einlinken** – direktes Linken könnte das Produkt-Backend unter GPL zwingen. Arm's-length-Trennung vor Produktivbetrieb dokumentieren (`docs/06` Phase 4). |
| mobiTopp (Kotlin-Rewrite) | MIT | unkritisch (ohnehin nicht im Laufzeitpfad) |
| populationsynthesis | MIT | unkritisch |
| **actitoppNG** | **WIDERSPRÜCHLICH**: LICENSE-Datei = GPL, POM = „MIT" | **Vor jeder Weitergabe mit KIT klären** (offener Punkt, `docs/06` E + Phase 5). **Bis zur Klärung als GPL behandeln** (externer Prozess, nicht einlinken). |
| GraphHopper-core 8.0 | Apache-2.0 | eingebettet ok |
| SUMO 1.26 | EPL-2.0 | externe Prozesse, ok |
| Ollama | MIT | ok |
| **Qwen2.5-7B-Instruct** | Apache-2.0 (echtes Open Source, nicht nur „open weight") | ok. **Llama 3.1 wurde bewusst verworfen** (Llama-Community-Lizenz mit Auflagen; Entscheidung `docs/06` Phase 3a, siehe auch `synthesis-failure-archaeology`). |
| Spring Boot, Jackson, Commons | Apache-2.0 | ok |
| Angular 21 / Leaflet / OpenJDK | MIT / BSD-2 / GPL+CE | ok |
| OSM-Daten (Overpass) | ODbL | Attribution „© OpenStreetMap-Mitwirkende" bei Karten-/Datennutzung |
| **NVBW/MobiData-BW-GTFS** | ODbL | Attribution beachten; bei Nutzung der `shapes.txt` (OSM-abgeleitete Geometrien) ist die Attribution „© OpenStreetMap-Mitwirkende" Pflicht. Hinweis: unser `GtfsCropper` **verwirft `shapes.txt`** derzeit (Stand 2026-07-08, `docs/06` Phase 2/Phase C) – die shapes-Attribution wird erst relevant, wenn das geändert wird. |

**Merksatz für Reviews:** Tooling ist vollständig quelloffen/frei; die zwei Vorbehalte sind
(1) GPL-Copyleft des Lauf-Stacks (Prozess-Trennung ist die Antwort, nie aufweichen) und
(2) actitoppNG-Widerspruch (bis KIT-Klärung als GPL behandeln).

---

## 5. Datenlage: was wir haben und was nicht

Für Papers und Partner-Gespräche ist die Datenlage oft kritischer als die Software (`docs/06` E,
Vorbehalt 2). Stand 2026-07-08:

| Datenebene | Realität | Externe Konsequenz |
|---|---|---|
| MiD-/MOP-Mikrodaten (Verhaltens-Template, Vollschätzung, „echte" Personas) | **Zugangsbeschränkt; werden aktuell NICHT genutzt** | Wir arbeiten mit synthetischem Seed + **öffentlichen MiD-Aggregaten** (Modal-Split nach Regionstyp). Nie „basiert auf MiD-Mikrodaten" schreiben; korrekt: „validiert gegen öffentliche MiD-2017-Aggregate". Datenzugang – nicht Software – ist der begrenzende Faktor; ggf. KIT-Datenzugang klären (`docs/06` Phase 5). |
| KIT-IPU-Populations-Sample | Eingefrorener Offline-Output des Marne-Beispiels (~13.168 Personen, Amt Marne-Nordsee; `docs/05`) | **Limitation nennen:** Marne ist ländlich, das Sample demografisch leicht alt; es wird auf beliebige Zielregionen übertragen (Sampling), nicht regional neu synthetisiert. |
| Zensus-Regionaltabellen (für echte IPU je Region) | Nur Marne + Bayern gebündelt (`docs/05`) | Der generische Deutschland-Zensus-Pfad (echte Synthese je Region) ist **nicht verdrahtet** (`docs/06` A.2) – „echte regionale Bevölkerungssynthese" nicht behaupten. |
| OSM / GTFS / Zensus-Raster-Aggregate | Offen (ODbL bzw. amtlich offen) | Nutzbar; Attribution nach Abschnitt 4. |

---

## 6. Reproduzierbarkeits-Standard für Veröffentlichungen

Jede publizierte Zahl muss von Dritten (und von uns in 6 Monaten) nachvollziehbar sein.
**Pflicht-Paket je berichteter Kennzahl:**

1. **Szenario-JSON** – die vollständige Szenario-Definition (Region-BBox, Parameter, Flags,
   Verhaltensquelle, Zielmodelle).
2. **Seed** – der Szenario-Seed; für GTA gilt das abgeleitete Seed-Schema
   (`scenario.seed*31 + persona.hashCode`) als Teil des Protokolls.
3. **Commit-Hash** – `git rev-parse HEAD` des Standes, mit dem gerechnet wurde.
4. **Versionsliste** (die Pins, Stand 2026-08-04 – vor Publikation re-verifizieren):
   SUMO **1.26**, Ollama-Modell **qwen2.5:7b-instruct**, mobiTopp legacy **0.3.580**,
   GraphHopper **8.0**, Java 21 / Spring Boot 3.5.5, Angular 21.2; plus Ollama-Version und
   Hardware (LLM-Reproduzierbarkeit ist backendgebunden, siehe Abschnitt 2d).
5. **Kennzahlen-Extraktion über Skripte** aus `synthesis-diagnostics-and-tooling` – keine
   handgelesenen Zahlen aus UI-Panels in Papers.
6. **Mehrfach-Läufe:** Für stochastische Pfade (GTA, Kalibrierung, Feedback) mindestens
   **N=5 Seeds**, Mittelwert + Streuung berichten; Einzellauf-Zahlen nur als „Einzelbeobachtung"
   gekennzeichnet.
7. **Limitations-Block:** Distanz-Schwäche (Abschnitt 3), Marne-Sample (Abschnitt 5),
   Rastatt-Sensitivitäten (Abschnitt 3), ÖV-Scope Bus-only (Abschnitt 3) – solange jeweils offen.

---

## Provenienz & Wartung

**Stand: 2026-07-08.** Quellen: `docs/06-gta-integration-konzept.md` (Lizenzkapitel E,
Phasen-Historie inkl. Datumsangaben, Takomat-Antworten Phase 5), `docs/07-ansatz-zusammenfassung.md`
(Kernbefunde, Stack), `docs/05-validierung.md` (MiD-Vergleich, Marne-Sample),
`docs/01-analyse.md` (KIT-Ökosystem), `README.md`. Alle Messwerte sind **überlieferte
Einzellauf-Ergebnisse** mit dem im Text genannten Datum; keiner wurde für diesen Skill neu
gerechnet.

Re-Verifikation (lesend, je ein Einzeiler; im Repo-Root ausführen):

| Aussage | Befehl |
|---|---|
| Lizenztabelle unverändert | `grep -n "GPL-3.0\|EPL-2.0\|widersprüchlich" docs/06-gta-integration-konzept.md` |
| actitoppNG-Widerspruch noch offen | `grep -n "actitoppNG" docs/06-gta-integration-konzept.md` |
| Takomat: keine Einzeltrips (E-Mail 2026-07-03) | `grep -n "Einzelwegen\|District-Tabellen" docs/06-gta-integration-konzept.md` |
| Rail/Tram weiterhin zurückgerollt (Bus-only-Scope) | `grep -n "zurückgerollt\|Rail/Tram" docs/06-gta-integration-konzept.md README.md` |
| Distanz-Schwäche noch offen | `grep -n "12,5" docs/05-validierung.md` |
| Claim-Zahlen (651→18, RMSE, Δ-Konvergenz) | `grep -n "651\|0,163\|0,070\|0,008" docs/06-gta-integration-konzept.md docs/07-ansatz-zusammenfassung.md` |
| Versions-Pins (SUMO/Modell/mobiTopp) | `grep -rn "SUMO 1.26\|qwen2.5\|0.3.580" README.md docs/07-ansatz-zusammenfassung.md backend/src/main/resources/application.yml` |
| Doku seither geändert? | `git log --oneline -5 -- docs/05-validierung.md docs/06-gta-integration-konzept.md docs/07-ansatz-zusammenfassung.md` |

**Wartungsregel:** Ändert sich einer der folgenden Punkte, MUSS dieser Skill aktualisiert werden:
actitoppNG-Lizenzklärung durch KIT · Rail/Tram-Integration gelingt (Scope-Fußnote „Bus-only"
entfällt) · Distanz-Schwäche behoben (Pflicht-Limitation entfällt) · Zensus-Pfad generisch
verdrahtet · neue Claim-Kandidaten aus `synthesis-research-frontier` mit Beweis · geänderte
Versions-Pins. Neue Messwerte immer mit Datum eintragen; alte Werte nicht löschen, sondern als
überholt datieren.
