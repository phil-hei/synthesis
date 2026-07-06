# 06 – Konzept: Integration generativer LLM-Agenten (GTA-Ansatz)

Grundlage: Lämmer, Colley, Ebel (2026), *GTA: Generative Traffic Agents for Simulating
Realistic Mobility Behavior*, CHI '26 (Open Source: github.com/ciao-group/Generative-Traffic-Agents).
Dieses Dokument beschreibt (A) den aktuellen Integrationsstand von SyntheSis und (B) ein
gestuftes Konzept, wie sich der GTA-Ansatz ergänzend einbetten ließe.

---

## A. Ausgangslage – was heute integriert ist

### A.1 Was wirklich echtes KIT-GitHub-Tooling ist (und zur Laufzeit läuft)

| Komponente | Herkunft | Integrationsart | Läuft je Szenario? |
|---|---|---|---|
| **logiTopp** (Zustell-/Paketsimulation) | `kit-ifv/logitopp` geklont, modernisiert, als `logitopp:0.0-SNAPSHOT` nach mavenLocal publiziert | echte Klassen (DemandSimulatorDelivery, DistributionCenterParser, DepotOperations …) | **Ja** (Pipeline-Stufe „Simulation") |
| **mobiTopp legacy** (Simulationskern) | `edu.kit.ifv.mobitopp:mobitopp:0.3.580` (Maven Central, KIT-Release) | Bibliothek, auf der logiTopp läuft; enthält Modus-/Zielwahl (Rastatt-kalibriert) | **Ja** |
| **actiTopp** (Aktivitätenplanung) | `kit-ifv/actitoppNG` geklont, gebaut, `actitoppNG:1.0.0` (+ `kotlin-units`, `discrete-choice`) | eigener Kotlin-Runner `runSynthesis` (DefaultPlanGeneration / AllChoiceModels) | **Ja** (Stufe „Aktivitätenpläne") |
| **mobitopp-rastatt** | `kit-ifv/mobitopp-rastatt` geklont, modernisiert | Lauf-Harness; wir haben `buildDelivery()` ergänzt | **Ja** (führt logiTopp aus) |
| **populationsynthesis** (IPU) | `kit-ifv/populationsynthesis` geklont, gebaut, Marne-Beispiel ausgeführt | IPU lief **offline** → Output als gebündelte `kit-population-sample.csv` | **Nein** – nur Output wiederverwendet |
| **mobiTopp main** (Kotlin-Rewrite) | `kit-ifv/mobitopp` geklont, gebaut | einmalige Phase-0-Formatvalidierung | **Nein** – nicht im Laufzeitpfad |

### A.2 Architektur-Realität (wichtiger Vorbehalt zu „vollständig integriert")

- **Funktional integriert: ja.** Bei jeder Generierung erzeugt unsere Pipeline die Eingaben und
  startet einen **echten logiTopp-Wochenlauf** auf echtem mobiTopp-Legacy-Code; die Ergebnisse
  (KPIs, Touren, Pakete) werden zurückgelesen und visualisiert. Die Aktivitätenpläne kommen aus
  echtem KIT-actiTopp.
- **Aber kein eingebettetes Monolith-Backend.** Der Legacy-Stack (mobiTopp 0.3 + logiTopp) ist
  **nicht** als JAR ins Spring-Boot-Backend eingebunden, sondern wird als **externer
  Gradle-/JVM-Prozess** orchestriert (`sh gradlew runDeliverySynthesis`). Ebenso actiTopp
  (`sh gradlew runSynthesis`). Es ist also ein **Hybrid**: Java-21-Backend erzeugt die Artefakte
  (im Format des neuen Stacks *und* des Legacy-Stacks), die eigentlichen KIT-Modelle laufen in
  separaten Prozessen.
- **Verortung:** Die KIT-Klone liegen unter `validation/` (gitignored) – das ist heute der
  „Server-Standort" für die Lauf-Projekte. Für einen Produktivbetrieb müssten diese als feste
  Abhängigkeit/Container bereitgestellt werden.
- **populationsynthesis** ist nur output-seitig genutzt (das IPU-Sample ist eingefroren); der
  generische Deutschland-Zensus-Pfad (echte Synthese je Region) ist nicht verdrahtet.

### A.3 Was bewusst **eigen** ist (nicht KIT)

Zonierung, Attraktivitäten, Matrizen/Visum-Export, Bevölkerungs-Verteilung auf Zonen
(Sampling aus dem KIT-IPU-Output, Zuordnung eigen), **GraphHopper**-Routing, der
**Legacy-Export-Adapter**, der **MobileCity-Export**. Die Wahlmodelle (Modus/Ziel/Paket/Tour)
selbst sind echtes KIT (im mobiTopp/logiTopp-Lauf), Rastatt-kalibriert.

### A.4 Plausibilitäts-Stand (gegen MiD 2017)

Wege/Person·Tag ≈ MiD (actiTopp); Modal-Split und Weglänge gebiets-/kalibrierungsbedingt
abweichend (kleines Untersuchungsgebiet, Rastatt-kalibrierte Zielwahl). Details: `docs/05`.

### A.5 Lokalität & Ausfallsicherheit (Klarstellung „externer Prozess")

- **„Externer Prozess" = separater Betriebssystem-Prozess auf derselben Maschine**, nicht
  „entfernter Dienst". Es läuft **nichts bei KIT**. Das Backend startet per `ProcessBuilder` eine
  lokale JVM (`sh gradlew …`); die KIT-Modelle + Bibliotheken liegen lokal (`validation/`, `~/.m2`).
- **Einzige Remote-Abhängigkeit: Overpass (OSM)** – optional, mit Fallback. Maven Central nur beim
  *Bauen*, nicht zur Laufzeit. GraphHopper ist eine eingebettete lokale Bibliothek.
- **Graceful Degradation** (im Capstone-Lauf bewiesen): Overpass weg → synthetische Attraktivitäten
  + Luftlinien-Matrizen; lokaler Lauf-Stack fehlt → Stufe SKIPPED (actiTopp→Vorlage, Simulation
  entfällt). Die Kern-Artefaktgenerierung braucht nur die JVM.

### A.6 „Rastatt-kalibriert" – Begriffsklärung

mobiTopps Modus-/Zielwahl sind **Logit-Modelle**, deren **Parameter** (Gewichte für Zeit/Kosten/
Distanz, Modus-Konstanten) aus Befragungsdaten **geschätzt** wurden. Das Lauf-Projekt
`mobitopp-rastatt` liefert geschätzte Parameter für die **Region Rastatt** mit; weil wir es als
Harness nutzen, rechnet unsere Simulation mit Rastatts Werten („Rastatt-Geschmack" bei Modal-Split/
Weglänge). Änderbar nur durch **Neu-Kalibrierung** dieser Parameter (siehe D).

---

## B. Integrationskonzept GTA-Ansatz

### B.1 Leitidee und Abgrenzung

GTA ist **architektonisch nahezu deckungsgleich** mit SyntheSis (Profil aus MiD-Mikrodaten →
Aktivitätenplan → Routen+Moduswahl → Verkehrssimulation; Validierung gegen MiD-Modal-Split +
Zählstellen). Der Unterschied: GTA ersetzt **actiTopp + die kalibrierte Moduswahl** durch
**LLM-Persona-Agenten** mit natürlichsprachlicher Begründung.

**Deshalb wird GTA nicht als Kern-Ersatz, sondern als optionales Alternativ-Modul integriert**
– parallel zu actiTopp, hinter einem Flag (analog „actiTopp vs. Template" heute). Begründung:

- **Stärke von GTA = Was-wäre-wenn / Policy-Reasoning** (Fahrpreis, neue Radinfrastruktur,
  „ÖV unzuverlässig", neue Mobilitäts-App). Genau der Zweck eines *Szenario*-Studios – und genau
  das, was kalibrierte Logit-Modelle nicht können (sie sind auf Bestandsverhalten kalibriert).
- **Kein klarer Genauigkeits-Gewinn**: GTA hat *eigene* Verzerrungen (Role-Model-Bias →
  Rad überschätzt; Weglängen verzerrt; prompt-/modell-sensitiv). Für reproduzierbare Default-
  Nachfrage bleibt actiTopp die sichere Wahl.
- **Logistik bleibt unberührt**: GTA modelliert nur Personenverkehr; unser logiTopp-Kern (Pakete)
  läuft unverändert weiter.

### B.2 Modul-Mapping GTA ↔ SyntheSis

| GTA-Modul | Ersetzt/ergänzt in SyntheSis | Wiederverwendung |
|---|---|---|
| Profile (MiD → Persona) | speist sich aus unserem **KIT-Populations-Sample** (Alter/Geschlecht/HH); + LLM-Persona-Text | GTA-OSS-Promptvorlagen |
| Planning (LLM-CoT → Tagesplan, OSM-Orte) | **Alternative zu actiTopp** → erzeugt `activity.csv` | GTA-Planning-Prompts; unser bestehender actiTopp→activity.csv-Adapter als Vorbild |
| Action (Routen + LLM-Moduswahl) | **GraphHopper** liefert Routenoptionen (statt SUMO/OTP); LLM wählt Modus | unser Routing; GTA-Moduswahl-Prompt |
| Verkehrssimulation (SUMO) | **entfällt** → wir speisen mobiTopp/logiTopp + MobileCity | – |

Wichtig: GTA ist um **SUMO** gebaut; wir übernehmen nur **Profile + Planning + Moduswahl** und
hängen den Output an unsere bestehende Pipeline (nicht an SUMO). Die LLM-Pläne werden – wie schon
bei actiTopp – auf unser `activity.csv`-Format (LegacyActivityType-Codes, Tour-Struktur) gemappt;
die LLM-Moduswahl kann optional die `mode_preferences` der Person bzw. die Matrix-Nutzung
beeinflussen.

### B.3 Datenfluss (Ziel-Pipeline mit „GTA-Modus")

```
KIT-Populations-Sample ─► Persona-Builder ─► (LLM) Persona-Text
        │                                          │
        ▼                                          ▼
   Person (Alter/Geschl./HH) ───────────► (LLM, CoT) Tagesplan + Orte (OSM-Kat.)
        │                                          │
        │   GraphHopper-Routenoptionen ───► (LLM) Moduswahl je Weg
        ▼                                          ▼
   activity.csv (+ mode_preferences) ──► bestehende Stufen (Legacy-Export, logiTopp, MobileCity)
```

### B.4 Stufenplan

**Stufe 0 – Machbarkeit/Setup (klein).** LLM-Backend festlegen (lokal **Ollama/vLLM** mit
Llama 3.1 8B / Qwen 2.5 7B wie im Paper, oder API). GTA-OSS sichten, Promptvorlagen extrahieren.

**Stufe 1 – Persona + Planning (MVP).** Aus N Personen (1-%-Stichprobe / ein Bezirk) Persona +
LLM-Tagesplan erzeugen, auf `activity.csv` mappen (Adapter analog `ActivityGenerator.generateFromActitopp`).
Flag `activitySource = template | actitopp | gta`. Erst **ohne** LLM-Moduswahl (Modus über
mobiTopp-Wahlmodell wie bisher). Verifikation: voller logiTopp-Lauf + MiD-Vergleich im
bestehenden **Szenario-Vergleichs-Panel** (GTA vs. actiTopp nebeneinander).

**Stufe 2 – LLM-Moduswahl.** GraphHopper liefert je Weg Auto/Rad/Fuß/ÖV-Kennwerte; LLM wählt
Modus + Begründung. Output in person-`mode_preferences` / Wege-Annotation. Plausibilität gegen MiD
prüfen (Role-Model-Bias beobachten).

**Stufe 3 – Was-wäre-wenn & Interpretierbarkeit.** Szenario-Kontext in den Prompt (Policy/Tech:
Fahrpreis, Radwege, App). Begründungen je Agent als erklärende Schicht im Frontend
(„warum dieser Modus"). Dies ist der eigentliche Mehrwert.

**Stufe 4 (optional) – Orchestrierung wie actiTopp.** Eigenes Runner-Projekt + `ProcessBuilder`
(wie `ActitoppRunner`/`SimulationRunner`), Flag in `application.yml`, Fallback auf actiTopp bei
Nichtverfügbarkeit des LLM-Backends.

### B.5 Technische Voraussetzungen & Risiken

- **Rechenleistung** ist der Haupt-Blocker: GTA = viele LLM-Calls je Person (Planning + je Weg
  Moduswahl). Im Paper: 10 % Berlins = 43 h auf 8×A30-GPU. ⇒ für SyntheSis **nicht interaktiv**;
  realistisch nur als **asynchroner Batch** auf kleiner Stichprobe / einem Bezirk, mit Caching.
- **Reproduzierbarkeit**: LLM-Stochastik vs. unsere deterministische Seed-Pipeline ⇒ GTA-Modus als
  explizit „experimentell" kennzeichnen; Seed/Temperatur fixieren.
- **Bias** (Role-Model, Prompt-/Modell-Sensitivität) ⇒ CoT-Scaffolding zwingend (Paper-Befund),
  Ergebnisse immer gegen MiD spiegeln.
- **Lizenz/Datenschutz**: GTA-OSS-Lizenz prüfen; Persona aus aggregierten Statistiken (kein
  Personenbezug) – unproblematisch.

### B.6 Aufwand (grobe Schätzung)

- Stufe 1 (MVP Persona+Planning, ohne Moduswahl, ein Bezirk): überschaubar, da der
  Adapter-Mechanismus (LLM-Output → `activity.csv`) bei actiTopp bereits existiert; Hauptarbeit =
  LLM-Anbindung + Prompt-Engineering + Format-Mapping.
- Stufe 2–3 (Moduswahl + Was-wäre-wenn + UI-Begründungen): mittel.
- Stufe 4 (saubere Orchestrierung/Flags/Fallback): klein, Muster vorhanden.

### B.7 Empfehlung

GTA als **optionalen „LLM-Agenten-Modus" für qualitative Szenario-/Policy-Exploration** einbauen,
**nicht** als Ersatz des deterministischen actiTopp-Defaults und **ohne** Eingriff in den
logiTopp-Logistikkern. Größter Nutzen: erklärbare Reaktion auf neue Maßnahmen (Tool-Zweck
„Szenariomodellierung"). Naheliegend: **Kontakt zu den Autoren (Leipzig, ScaDS.AI)** – beide
Projekte teilen MiD 2017 und mobiTopp-nahe Konzepte.

---

## C. Zielarchitektur: Zwei-Achsen-Modell

Aus den Überlegungen ergibt sich ein klares Bild: SyntheSis trennt **Nachfrage-Erzeugung** von
**Nachfrage-Verwertung**. Beide werden je ein vom Nutzer wählbarer, austauschbarer **externer
Runner** – exakt das Muster, das mit actiTopp/logiTopp heute schon existiert.

```
                Achse A: Verhaltensquelle              Achse B: Zielmodell
   Region  ──►  ( erzeugt die Nachfrage )       ──►   ( verwertet die Nachfrage )
                ┌───────────────────────────┐         ┌───────────────────────────────┐
                │ actiTopp (kalibriert) ★    │         │ logiTopp (Personen + Pakete) ★ │
                │ GTA (KI, experimentell)    │         │ SUMO (mikrosk. Verkehr)        │
                │ [Template = interner       │         │ MobileCity-Export (Takomat,    │
                │  Fallback, nicht sichtbar] │         │  strategisch-aggregiert)       │
                └───────────────────────────┘         └───────────────────────────────┘
                          ★ = Default
```

**Achse A – Verhaltensquelle** (erzeugt `activity.csv` + Modus-/Zielwahl):
| Option | Art | Nutzer-sichtbar |
|---|---|---|
| **actiTopp** | echtes KIT-Modell, kalibriert, deterministisch | ja (Default) |
| **GTA** | LLM-Persona-Agenten, Was-wäre-wenn, erklärbar, stochastisch | ja (experimentell) |
| Template | einfacher Regelgenerator | nein (nur Fallback, wenn actiTopp-Prozess fehlt) |

**Achse B – Zielmodell** (verwertet die Nachfrage):
| Option | Granularität | Rolle |
|---|---|---|
| **logiTopp/mobiTopp** | mikroskopisch, Personen **+ Pakete** | heutiger Default; liefert KPIs/Touren |
| **SUMO** | mikroskopisch, Verkehrsfluss | eigene Detailsimulation; OSM→`netconvert`, Pläne→`.rou.xml`; EPL, lokal, CPU; kommt mit GTAs Action-Modul teilweise „mit"; **unabhängig von Takomat** |
| **MobileCity-Export** | strategisch, district-aggregiert, mehrjährig | Takomat-Zielmodell; wir liefern Aggregat-Tabellen |

**Designprinzipien:**
- Die beiden Achsen sind **unabhängig wählbar** (Verhaltensquelle × Zielmodell). Zielmodell kann
  Mehrfachauswahl sein (z. B. logiTopp *und* MobileCity-Export gleichzeitig – heute schon der Fall).
- **SUMO ersetzt MobileCity nicht** – andere Granularität: MobileCity = strategisch/mehrjährig/
  Policy; SUMO = ein Szenario, detaillierte Flüsse, **selbst gefahren** (macht uns für „eigene
  Mikrosimulation" unabhängig vom Takomat-Tooling).
- **Logistik bleibt logiTopp-exklusiv**: GTA und SUMO modellieren nur Personenverkehr; der
  Paketkern läuft in jeder Kombination über logiTopp.

---

## D. On-demand-Kalibrierung der Region (Achse A, optional)

Adressiert das „Rastatt-Geschmack"-Problem (A.6). Zwei Stufen, sehr unterschiedlich teuer:

- **(a) Vollschätzung** der Sensitivitäten → braucht **disaggregate Regional-Surveymikrodaten**
  (zugangsbeschränkt) → **nicht on-demand**, nur mit Nutzer-Daten.
- **(b) Konstanten-Kalibrierung** (Standardpraxis „Transfer + lokale Kalibrierung"): geschätzte
  Sensitivitäten behalten, Modus-/Ziel-Konstanten iterativ so verschieben, dass der simulierte
  Modal-Split die **Regionalziele** trifft. **On-demand machbar.**

**Aufwand (b):** Schleife *Lauf → Vergleich mit Ziel → Konstanten-Update (+ ln(Ziel/Sim)) → Lauf*;
~5–15 Iterationen → **Minuten (klein) bis ~1 h (Stadt)**, CPU, asynchroner Batch, nicht interaktiv.
Deutlich billiger als GTA.

**Datenbedarf:** Zielwerte = **MiD-2017-Modal-Split nach Regionstyp/Bundesland** (öffentliche
Aggregate; bereits unsere Validierungsreferenz) oder Nutzer-Upload. Vollschätzung (a) bräuchte
Survey-Mikrodaten.

**Mit GTA?** **Nein** – GTA hat keine Parameter zum Kalibrieren; Verhalten emergiert aus LLM-Personas
(eigene Biases). Nur per Prompt „steuerbar" (unprinzipiell), Ergebnis muss gegen MiD geprüft werden.
Kalibrierung ist ein mobiTopp-Logit-Thema; GTA umgeht sie.

---

## E. Open-Source-/Lizenzstatus

**Tooling vollständig quelloffen/frei** (LICENSE-Dateien geprüft):

| Komponente | Lizenz |
|---|---|
| Spring Boot, Jackson, Commons, **GraphHopper-core 8.0** | Apache 2.0 |
| Angular 18 (MIT), Leaflet (BSD-2), OpenJDK (GPL+CE) | permissiv/open |
| **mobiTopp legacy + logiTopp** (Lauf-Stack) | **GPL-3.0** (copyleft) |
| mobiTopp (Kotlin), populationsynthesis | MIT |
| actitoppNG | LICENSE-Datei = **GPL**, POM = „MIT" → **widersprüchlich, mit KIT klären** |
| **SUMO 1.26** (`sumo`/`netconvert`/`duarouter`, externe Prozesse) | **EPL-2.0** (lokal, CPU) |
| **Ollama** (lokaler LLM-Server) | **MIT** |
| **Qwen2.5-7B-Instruct** (GTA-Verhaltensquelle) | **Apache-2.0** (echtes Open Source, nicht nur „open weight") |
| *(geplant)* GTA-Original-Code (open), Llama 3.1 (Llama-Community-Lizenz, Auflagen) | open/open-weight |

**Zwei Vorbehalte:**
1. **Copyleft:** Der GPL-Lauf-Stack (mobiTopp_legacy + logiTopp) ist der eigentliche Grund für die
   externe-Prozess-Trennung – direktes Linken könnte ein Produkt-Backend unter GPL zwingen.
   actitoppNG-Lizenz vor einer Veröffentlichung mit KIT klären.
2. **Daten ≠ Tooling:** OSM offen (ODbL); echte **MiD-/Zensus-Mikrodaten** (für „echte" Personas/
   Vollschätzung) sind zugangsbeschränkt. Wir nutzen sie aktuell **nicht** (synthetischer Seed +
   öffentliche MiD-Aggregate). Datenzugang – nicht Software – wäre der begrenzende Faktor; gilt für
   GTA gleichermaßen.

---

## F. Roadmap zur Umsetzung

Reihenfolge nach **Wert/Aufwand/Unabhängigkeit**. Phasen 1–3 sind weitgehend unabhängig und
nutzen ausschließlich Open-Source-Tooling; Phase 5 ist nicht-technisch (Kontakte).

### Phase 0 – Zwei-Achsen-Gerüst (klein, Voraussetzung) — ✅ UMGESETZT (2026-06-21)
- Backend: `Scenario.population.behaviorSource` (actitopp | gta | template) und
  `Scenario.targetModels` (logitopp | sumo | mobilecity, Mehrfachauswahl). Stufe „Aktivitätenpläne"
  routet nach Verhaltensquelle (GTA → Log + Fallback actiTopp → Fallback Template); Stufen
  „MobileCity-Export" und „Simulation (logiTopp)" werden auf die gewählten Zielmodelle gegated
  (SKIPPED, wenn nicht gewählt); SUMO-Auswahl loggt Phase-2-Hinweis. Legacy-Flag `useActitopp`
  bleibt rückwärtskompatibel (`behaviorSourceEffective()`).
- Frontend: Sektion „Modellierung" im Setup — Dropdown „Verhaltensmodell" (actiTopp | GTA
  experimentell, mit Hinweis) + Checkboxen „Zielmodell" (logiTopp / MobileCity-Export / SUMO
  „demnächst", disabled).
- **Verifiziert**: Auswahl routet korrekt (nur-MobileCity → Simulation SKIPPED; logitopp+sumo+gta
  → GTA-Fallback, MobileCity SKIPPED, SUMO-Hinweis); UI im Browser bestätigt.

### Phase 1 – On-demand-Kalibrierung (größter Plausibilitäts-Hebel) — ✅ UMGESETZT (2026-06-21)
- `ModeChoiceCalibrator`: passt die ASCs (`asc_ped/asc_bike/asc_car_p/asc_put`, Anker `asc_car_d`)
  im MNL-Mode-Choice-File iterativ an (ΔASC = ln(Ziel/Sim), Clamp ±1,5), je Iteration ein
  logiTopp-Lauf via `SimulationRunner.run(…, mnlOverride)` + Modal-Split aus kpis.json. Ziel =
  `ModeChoiceCalibrator.MID_2017` (überschreibbar). Flag `population.calibrateModeChoice` (Default
  aus, da mehrere Sim-Läufe); Iterationen/Toleranz via `synthesis.simulation.calibration-*`.
  Frontend-Checkbox „Modal-Split gegen MiD kalibrieren" (nur bei Zielmodell logiTopp).
- **Verifiziert** (Rastatt, beeline, 5 Iter): RMSE **0,170 → 0,055** monoton; Auto 22→32 %,
  Fuß 53→26 % Richtung MiD. Behebt „Rastatt-Geschmack" generisch, nur CPU, keine externen Deps.
- **Ziel-Modal-Split konfigurierbar** (✅): `population.calibrationTarget` (Default MiD-bundesweit,
  editierbar je Region); Frontend-Eingaben (Fuß/Rad/MIV-F/MIV-Mitf/ÖV) bei aktivierter Kalibrierung.
  Verifiziert: benutzerdefiniertes Auto-lastiges Ziel (58 %) → Modal-Split konvergiert dorthin
  (Auto 22→41 %, RMSE 0,235→0,086), klar über dem MiD-Lauf (32 %).
- *Offen:* vorgefertigte Regionstyp-Presets (MiD RegioStaR) statt manueller Eingabe — Datenanbindung.

### Phase 2 – SUMO als Zielmodell (Achse B) — ✅ UMGESETZT (2026-06-26)
- Stufe „SUMO (Verkehr)" nach der logiTopp-Simulation; **gated** auf Zielmodell `sumo` und
  vorhandene logiTopp-Trips (`demandsimulationResult.csv`), sonst SKIPPED. Toolchain als externe
  Prozesse (wie logiTopp): OSM (Overpass) → **`netconvert`** → Auto-Trips der Nachfrage → **`duarouter`**
  → **`sumo`** → `statistics.xml` (vehicleTripStatistics). Ergebnis: `artifacts/sumo/` mit lauffähigem
  Szenario (net/routes/sumocfg/statistics) + `summary.json` (Fahrzeuge, Ø Dauer/Länge/Geschwindigkeit).
- **Bewusst pyproj-/Python-frei:** Trips werden als Geo-Koordinaten (`fromLonLat`/`toLonLat`) in Java
  geschrieben, das Snapping auf Netzkanten übernimmt `duarouter --mapmatch.distance` nativ (C++).
  Vermeidet die `sumolib`-Abhängigkeit `pyproj` (Install scheiterte an PEP 668 „externally-managed").
- **Overpass robuster:** primärer Endpunkt + konfigurierbare **Mirror-Fallbacks**
  (`overpass.kumi.systems`, `…private.coffee`) gegen 504/429 bei schweren Straßennetz-Abfragen.
- **Verifiziert:** `SumoRunnerTest` führt den kompletten Pfad gegen echtes Rastatt-Demand-CSV + OSM aus
  (1014 Fahrzeuge, Ø 3,2 km / 54,8 km/h / 3,9 min); Frontend-Checkbox „SUMO" aktiv + Ergebnis-Panel
  im Browser bestätigt. Macht uns für eigene Mikrosimulation **unabhängig von Takomat**.
- Konfiguration: `SynthesisProperties.Sumo` (Binär-/SUMO_HOME-Pfade, `day`, `maxTrips`, `timeoutMinutes`).
- **Nachfrage↔Netz-Feedback als Gleichgewichts-Schleife (2026-07-06):** Flag
  `population.sumoFeedback` (benötigt logiTopp+SUMO). Stufe „Netz-Feedback": je Iteration werden
  alle Zonen-Zentroid-Paare zweimal auf dem SUMO-Netz geroutet (duarouter frei vs.
  `--weight-files edgedata.xml`); das Zeitverhältnis (geklemmt 1–3) wird **MSA-gedämpft**
  (Schrittweite 1/k, verhindert Oszillieren) in die CAR_TTC-Matrix gemischt (GraphHopper-Basis
  bleibt), **logiTopp entscheidet neu und SUMO simuliert die neue Nachfrage** – bis die mittlere
  Faktor-Änderung unter die Toleranz fällt (`sumo.feedbackTolerance` 0,01) oder das Limit erreicht
  ist (`sumo.feedbackIterations` 3); Zonen-Kappe `feedbackMaxZones` 150 (OD ist n²). Die
  SUMO-Artefakte (CO₂, Netzlast) bleiben konsistent zur finalen Nachfrage. **Live-Beweis (Rastatt,
  volle Population): Konvergenz nach 2 Iterationen** (Δ 0,33 → 0,008), Ø Faktor 1,32 auf 324
  Relationen, Modal-Split Pkw 27 %→24 %, Fuß 45 %→49 % – Nachfrage und Netz im Gleichgewicht.
  Damit verbessert SUMO das Nachfragemodell, statt es nur zu konsumieren.
- **Netzlast-Karte (2026-07-05):** tages-aggregierte edgeData-Messung + zweiter netconvert-Lauf
  (`--plain-output-prefix --proj.plain-geo`) verbinden Kantenmesswerte mit WGS84-Geometrien zu
  `sumo/congestion.geojson` (nur befahrene Kanten; Attribute speedKmh/limitKmh/relSpeed/timeLoss).
  Studio: Karten-Layer „🚦 Netzlast" färbt Kanten nach relSpeed (grün ≥80 %, orange 50–80 %,
  rot <50 % des Tempolimits) – kantenscharfe Stauvisualisierung der eigenen Nachfrage.
- **Emissions-KPIs (2026-07-03):** die vTypes tragen HBEFA3-Emissionsklassen (Pkw: Benzin EU4,
  Lieferwagen: Diesel EU4, Lastenrad: emissionsfrei); das SUMO-Emissions-Device schreibt CO₂ je
  Fahrzeug in die tripinfo.xml, die zu **CO₂ gesamt + je Fahrzeugtyp** aggregiert wird
  (`summary.json`: `co2Kg`, `co2KgByType`; Studio-Panel-Zeile „CO₂ (HBEFA)"). Damit sind
  Logistik-Szenarien (z. B. mehr Lastenräder statt Lkw) direkt emissionsseitig vergleichbar.
- **Lieferverkehr integriert (2026-07-02):** zusätzlich zu den PKW-Trips speist SUMO jetzt die
  logiTopp-**Zustelltouren** ein (`planned-tours.csv`, jede Zeile ein Stopp-Leg mit Koordinaten,
  Fahrzeugtyp und Sim-Zeit): Lkw als `vClass delivery`, Lastenräder als `vClass bicycle`
  (`<vType>`-Definitionen in trips.xml; TRAM wird verworfen). Rastatt-Beispiel Tag 0:
  455 Liefer-Legs (342 Lkw + 113 Lastenrad) neben ~1000 PKW. `summary.json` + Studio-Panel weisen
  `carTrips`/`deliveryTrips` getrennt aus. Damit simuliert SUMO die **Last-Mile-Logistik im realen
  Straßenverkehr** – Grundlage für Emissions-/Netzlast-Auswertungen je Fahrzeugtyp (offen).
- *Offen/optional:* Rückspeisung der SUMO-Reisezeiten in die KPIs/Matrizen; Karten-Layer der Flüsse;
  Lauf-Projekte als feste Abhängigkeit/Container (siehe Phase 4).

### Phase 3 – GTA als KI-Verhaltensquelle (Achse A)
- **3a Setup — ✅ UMGESETZT (2026-06-26):** lokales LLM via **Ollama** (`brew install ollama`,
  OpenAI-kompatibel auf `http://localhost:11434/v1`), Modell **Qwen2.5-7B-Instruct** (Apache 2.0,
  gewählt vor Llama 3.1 wegen echter Open-Source-Lizenz + besserer JSON-Treue). Basis-M1/16 GB:
  ~11 tok/s, ~30–40 s je Persona → für kleine Experimentier-Stichproben.
- **3b MVP — ✅ UMGESETZT (2026-06-26):** `GtaBehaviorRunner` baut pro Person eine Persona und
  fragt das LLM nach Werktags-/Wochenend-**Touren** (JSON, `response_format json_object`); die
  mechanische Auslage (HOME-Einweben, Timing, Mitternachts-Fit) übernimmt die erprobte
  `ActivityGenerator.generateFromGta` → `scheduleDay`. **Persona-Signatur-Bündelung**
  (Erwerbsstatus × Altersband × Geschlecht, gekappt durch `llm.maxPersonas`) macht GTA auf echten
  Populationen nutzbar: 12 Personen → 9 LLM-Calls, 100 % abgedeckt. Verhaltensquelle `gta` in
  `GenerationService` aktiv; Fallback auf actiTopp, wenn LLM nicht erreichbar. Konfiguration
  `SynthesisProperties.Llm` (endpoint/model/temperature/timeout/maxPersonas). Tests:
  `GtaBehaviorTest` (deterministisches Layout + Live-LLM + Reproduzierbarkeit). **Plausibilität:** rohe
  Prompt-Version ~5,7 Aktivitäten/Person·Tag (überproduziert) → nach Prompt-Tuning auf MiD-Häufigkeit
  (~3 Wege/Tag, max 2 Touren/Tag) gesenkt.
- **3b Ende-zu-Ende verifiziert (2026-06-26):** voller In-App-Lauf (Studio → GTA-LLM → logiTopp →
  Vergleichs-Panel) auf identischer Population (Seed 42, 651 Personen): GTA trifft **Wege/Person·Tag
  = 3,1 (= MiD)**, exakt wie actiTopp. Modal-Split beider überschätzt Fuß (kleine Region, Ø 1,5 km).
  **GTA + Phase-1-Kalibrierung greifen ineinander:** auf GTA-Output zieht die Kalibrierung den
  Modal-Split Richtung MiD (Fuß 50 %→26 %, Pkw 20 %→29 %, **RMSE 0,163 → 0,070**), ohne die
  Wege zu ändern (Kalibrierung re-simuliert nur logiTopp mit angepassten ASCs, GTA läuft einmal).
- **3b Reproduzierbarkeit (2026-06-26):** `temperature>0` machte GTA pro Lauf verschieden (Wege/Tag
  schwankten ~2,4–3,1). Behoben: pro LLM-Anfrage ein stabiler Seed `scenario.seed*31 + persona.hashCode`
  (Ollama respektiert `seed`) → gleicher Szenario-Seed liefert identische Pläne, verschiedene Personas
  trotzdem Vielfalt. Test `sameSeedIsReproducible` grün.
- **3c — ✅ UMGESETZT (2026-06-26):** LLM-gestützte Moduswahl, geerdet in Reisezeiten je Modus.
  `LlmModeChoiceRunner` liest die simulierten Trips (`demandsimulationResult.csv`, Spalte
  `distanceInKm`), bündelt sie nach **Distanzband** und fragt je Band – mit Reisezeiten für
  Fuß/Rad/Pkw/ÖV bei dieser Länge – nach dem Modal-Split (reproduzierbar via `seed`). Gewichtete
  Aggregation → alternativer Modal-Split, der **neben dem logiTopp-MNL und MiD** dargestellt wird
  (Artefakt `simulation/llm-mode-choice.json`, KPI-Panel im Studio). Ergänzt die logiTopp-Moduswahl,
  **ersetzt sie nicht** (die MNL-Wahl im GPL-Stack bleibt unangetastet). Stufe „LLM-Moduswahl",
  Flag `population.llmModeChoice` (Default aus), nur bei logiTopp-Ziel + erreichbarem LLM; sonst
  SKIPPED. Test `LlmModeChoiceTest` (gültige normalisierte Verteilung + Reproduzierbarkeit), im
  Browser verifiziert. **BEFUND:** die rohe LLM-Moduswahl hat einen starken **Bias zu aktiven Modi**
  (Rad ~35–45 % statt MiD 11 %, Pkw zu niedrig; RMSE zu MiD ~0,19 vs. kalibriertes MNL 0,07).
  Ursache: dem LLM fehlen die kalibrierten modus-spezifischen Konstanten (ASCs), die im MNL die
  Pkw-Präferenz abbilden – es wählt zeitrational das schnellste Verkehrsmittel. Explizite
  MiD-Erdung im Prompt half nicht (Modell folgt der quantitativen Vorgabe nicht). Damit ist die
  LLM-Moduswahl eine interessante, aber kalibrierbedürftige Alternativlinse – sie unterstreicht den
  Wert des kalibrierten MNL-Modells.
- **3d — ✅ UMGESETZT (2026-07-02):** Was-wäre-wenn-Szenarien + Agenten-Transparenz.
  `population.gtaWhatIf` (Freitext, z. B. „Die Innenstadt ist autofrei") fließt in den
  Persona-Prompt ein („unbedingt berücksichtigen"); das LLM liefert je Persona zusätzlich eine
  **`begruendung`** (ein Satz zur Tagesgestaltung bzw. zum Szenario-Einfluss). Artefakt
  `population/gta-personas.json` (whatIf + Personas mit Personenzahl und Begründung); Studio zeigt
  Textarea „Was-wäre-wenn-Kontext" (bei Verhaltensmodell GTA) und ein Ergebnis-Panel
  „GTA-Agenten (KI-Verhalten)" mit den Begründungen. Der Plan-Cache-Schlüssel enthält den
  Was-wäre-wenn-Text (Szenario-Wechsel invalidiert automatisch). Test `whatIfContextYieldsReasons`.
- *Abhängigkeiten:* lokales LLM (vorhanden); asynchroner Batch, experimentell gekennzeichnet,
  Fallback auf actiTopp. *Offen:* Vergleich GTA vs. actiTopp im Szenario-Vergleichs-Panel über einen
  echten logiTopp-Lauf; weitere Prompt-/Frequenz-Kalibrierung.

### Phase 4 – Produktiv-Härtung (querschnittlich)
- Lauf-Projekte (mobitopp-rastatt/actitoppNG/SUMO) als **feste Abhängigkeit/Container** statt
  `validation/`; self-hosted Overpass oder gebündelter GraphHopper-PBF-Extrakt (Routing-Robustheit,
  vgl. docs/05); GPL-Konformität (Arm's-length-Trennung) dokumentieren.

### Phase 5 – Externe Klärungen (nicht-technisch, parallel)
- **Takomat:** MobileCity-Import finalisieren (Netz-/TEC-/MOD-Tabellen, Messagepack,
  vollständiger Study-Area-Import). **Antworten vom 2026-07-03:**
  - **District-IDs:** synthetische IDs (SYN-NNN) sind zulässig – NUTS3-Optik war nur Lesbarkeit,
    einzige Anforderung ist Eindeutigkeit je Study Area. ✓ (unser Export ist konform)
  - **Geometrie:** Import erwartet **Shapefiles** (WGS84) mit einzigem Attribut **`districtID`**
    (muss zu REGdistricts passen). → umgesetzt: `mobilecity/districts.shp/.shx/.dbf/.prj`
    (eigener minimaler ShapefileWriter, formatgeprüft per Test).
  - **OLC:** dient als **ID-Schema für Netz-Links/-Nodes** (Codelänge **10**), nicht für
    Districts; ihr OLC Mapper konvertiert gelieferte Koordinaten/IDs selbst. → für uns keine
    OLC-Pflicht; die Zonen-Code-Idee (GeoHash/OLC/INSPIRE) bleibt rein intern optional.
  - **Weitere Tabellen (INF*/TEC*/MOD*/ENV/LAQ):** „prinzipiell alle benötigt"; welche Defaults
    möglich sind, prüft Takomat. **Netz-Erzeugung** (durch ihre Pipeline vs. Lieferung durch
    uns): Rücksprache mit Lars aussteht. → beide Punkte offen, warten auf Rückmeldung.
  - **Nachfrage-Schnittstelle bestätigt:** ausschließlich aggregierte District-Tabellen; kein
    Import von Einzelwegen/Touren (am nächsten kommen Maßnahmen/Governances auf Teilabschnitten).
- **KIT:** actitoppNG-Lizenz, logiTopp-Migrationsstand, ggf. MiD-/Zensus-Datenzugang.
- **GTA-Autoren (Leipzig/ScaDS.AI):** Kooperation, Persona-/MiD-Datengrundlage.

### Empfohlene Reihenfolge
**0 ✅ → 1 ✅ → (2 ✅ ∥ 3 ✅) → 4**, Phase 5 durchgehend parallel. Phasen 0–3 vollständig umgesetzt
(3a–3d): Zwei-Achsen-Gerüst, On-demand-Kalibrierung, SUMO-Zielmodell inkl. Lieferverkehr,
GTA-Verhaltensquelle (lokales LLM, Cache, Reproduzierbarkeit), LLM-Moduswahl, Was-wäre-wenn-Szenarien
mit Agenten-Begründungen. Offen: **Phase 4** (Produktiv-Härtung: Lauf-Projekte als feste
Abhängigkeit/Container, self-hosted Overpass) sowie die SUMO-Ausbauideen (Emissions-/Netzlast-KPIs,
Nachfrage↔Netz-Feedback).
