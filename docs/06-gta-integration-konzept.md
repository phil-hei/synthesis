# 06 – Konzept: Integration generativer LLM-Agenten (GTA-Ansatz)

Grundlage: Lämmer, Colley, Ebel (2026), *GTA: Generative Traffic Agents for Simulating
Realistic Mobility Behavior*, CHI '26 (Open Source: github.com/ciao-group/Generative-Traffic-Agents).
Dieses Dokument beschreibt (A) den aktuellen Integrationsstand von SyntheSis und (B) ein
gestuftes Konzept, wie sich der GTA-Ansatz ergänzend einbetten ließe. Abschnitt G hält das
geplante Konzept für Kundenretouren als logiTopp-Abholaufträge fest.

---

## A. Ausgangslage – was heute integriert ist

### A.1 Was wirklich echtes KIT-GitHub-Tooling ist (und zur Laufzeit läuft)

| Komponente | Herkunft | Integrationsart | Läuft je Szenario? |
|---|---|---|---|
| **logiTopp** (Zustell-/Paketsimulation) | `kit-ifv/logitopp` geklont, modernisiert, Retouren-Patchvertrag als `logitopp:0.0-synthesis-returns-v1` nach mavenLocal publiziert | echte Klassen (DemandSimulatorDelivery, DistributionCenterParser, DepotOperations …) | **Ja** (Pipeline-Stufe „Simulation") |
| **mobiTopp legacy** (Simulationskern) | `edu.kit.ifv.mobitopp:mobitopp:0.3.580` (Maven Central, KIT-Release) | Bibliothek, auf der logiTopp läuft; enthält Modus-/Zielwahl (Rastatt-kalibriert) | **Ja** |
| **actiTopp** (Aktivitätenplanung) | `kit-ifv/actitoppNG` geklont, gebaut, `actitoppNG:1.0.0` (+ `kotlin-units`, `discrete-choice`) | eigener Kotlin-Runner `runSynthesis` (DefaultPlanGeneration / AllChoiceModels) | **Ja** (Stufe „Aktivitätenpläne") |
| **mobitopp-rastatt** | `kit-ifv/mobitopp-rastatt` geklont, modernisiert | Lauf-Harness; `buildDelivery()` plus Vertrag `synthesis-logitopp-returns-v1` | **Ja** (führt logiTopp aus) |
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

### A.7 Retouren-Status — experimenteller MVP technisch abgenommen (2026-07-29)

- **Der experimentelle Szenariopfad kann Kundenretouren erzeugen.** Szenario-API und Frontend
  exponieren Quote sowie `HOME`-/`WORK`-Abholanteile nur für das Zielmodell logiTopp. Der
  rückwärtskompatible Default bleibt `returns.enabled=false`; es gibt keine vorausgefüllte
  Branchenquote.
- **Die externe Konfigurationskette ist implementiert.** Der Vertrag
  `synthesis-logitopp-returns-v1` liest Paketparameter und optional zusätzliche
  `IsPickup=true`-Aufträge aus `synthesis-parcel-demand-v1`, clustert sie am tatsächlichen Ort
  und registriert sie über den Pickup-Bestand. Ein alter Harness schlägt sichtbar fehl.
- **`RETURNING` ist keine Kundenretoure.** Der logiTopp-Status bezeichnet ein Paket, das nach
  einem fehlgeschlagenen Zustellversuch zum Verteilzentrum zurückkehrt. Ebenso ist
  `PICK_UP_PARCEL` die Abholung einer eingehenden Sendung durch den Empfänger an einer
  Packstation.
- **Die Mechanik ist Ende-zu-Ende belegt.** Ein realer Vertragslauf erzeugte 101 Zustellungen
  plus 101 Retouren; 67 Pickups wurden innerhalb des Wochenhorizonts erfolgreich ausgeführt.
  Der finale seed-identische A/B-Lauf hielt 86 Zustellungen konstant und ergänzte
  86 geplante und eingesammelte Pickups; SUMO wies deren zusätzliche Verkehrs- und
  Emissionswirkung aus. Ein kalibriertes Endkunden-Retourenmodell liefert logiTopp weiterhin
  nicht fertig mit, deshalb bleibt der Default aus.

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
| Angular 21 (MIT), Leaflet (BSD-2), OpenJDK (GPL+CE) | permissiv/open |
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
- **RegioStaR-Presets (✅, 2026-07-06):** Studio-Dropdown „Vorlage" füllt die Zielwerte mit
  regionstyp-typischen Modal-Splits (RegioStaR7: Metropole 71 · Regiopole/Großstadt 72 ·
  Mittel-/Kleinstadt 73/74 · kleinstädtisch/ländlich 75/76 · ländlich/dörflich 77 · MiD-bundesweit);
  danach frei editierbar. Richtwerte nach dem MiD-2017-Stadt-Land-Muster (Pkw-Anteil steigt
  urban→ländlich, ÖV/Fuß fallen), je Vorlage auf 1,0 normiert. *Offen/optional:* Ersatz durch
  exakte offizielle MiD-RegioStaR-Tabellen; automatische Vorauswahl aus der Bevölkerungsdichte.

### Phase 1b – Distanz-Sensitivität der Zielwahl (größte offene Plausibilitätslücke) — 🔬 IN ARBEIT (2026-07-10)
- **Problem:** Die Modal-Split-Kalibrierung (Phase 1) trifft die Modus-*Anteile*, aber die
  *Weglängen* bleiben viel zu kurz (geroutet Ø ~2,9 km vs. MiD 12,5 km; die Zielwahl wählt fast
  immer nahe Ziele). Die ausführbare, gegatete Kampagne dazu steht im Skill
  `.claude/skills/synthesis-distanz-kampagne/`.
- **Theorie (Phase 1 der Kampagne):** Die Zielwahl-Parameter liegen in
  `validation/mobitopp-rastatt/config/shared/parameters/destination_choice_*` und sind über
  `destinationChoice:` in der Run-Config verdrahtet. Die Kurzdistanz-Hebel je Zweck:
  `b_intrazonal` (Bonus für Ziel in derselben Zone, SHOPPING = 2,25), `b_0_1`/`b_1_2`
  (Nahband-Boni 0–1/1–2 km). In einem 6×5-km-Gebiet dominieren diese die Zielwahl. Additive
  Term-Syntax (`0.93 -0.2 -0.1` = Summe) — Patches als zusätzlicher Term.
- **Befund Baseline-Kontamination:** Das vorgefundene
  `output/synthesis/results/demandsimulationResult.csv` (Ø 2,0 km, Fuß 52 %) trägt exakt die
  Luftlinien-Fallback-Signatur (docs/05) — als Kampagnen-Baseline unzulässig ohne Job-Log-Prüfung
  („Fallback auf Luftlinie"). Der zugehörige Legacy-Export ist nicht mehr vorhanden (lebte in
  `artifacts/`/`/tmp`), ein Probelauf braucht also eine Neugenerierung (empfohlen klein,
  `samplingFraction` 0,05, damit logiTopp in Sekunden läuft — wie bei der Modal-Split-Kalibrierung).
- **✅ Phase 2a der Kampagne umgesetzt (Infrastruktur):** `SimulationRunner.run(…, modeChoiceMnl,
  destinationChoiceDir)` — optionales Verzeichnis mit gepatchten Zielwahl-Dateien überschreibt die
  fünf `destinationChoice:`-Pfade (gleiche Dateinamen), analog zum bewährten `modeChoiceMnl`-Weg;
  `null` = Projekt-Standard (verhaltensgleich, `SimulationRunnerTest` beweist beide Fälle + dass die
  Moduswahl unberührt bleibt). Damit ist der Hebel für die Elastizitäts-Sonde (Phase 2b) und einen
  späteren `DistanceCalibrator` (Phase 3, Flag `calibrateDistance` default=false) verdrahtet.
- *Nächster Schritt:* Elastizitäts-Sonde auf einem sauberen (gerouteten) Lauf — `b_intrazonal`
  ±1,0, EIN Lauf, Distanzprofil-Delta gegen die Vorhersage (Ø-km steigt) prüfen. Eingezäunte
  Irrwege (ferne externe Zonen, LLM-Prompt-Steuerung, Roh-Ziel 12,5 km in kleiner Region) siehe
  Skill `synthesis-failure-archaeology` bzw. Kampagnen-Skill.
- **Phase-0-Versuch kontaminiert (2026-07-12):** Baseline-Lauf (Szenario `0e020eab`, seed 42,
  samplingFraction 0,05, `useRealRouting=true`, Bbox 48.825/8.155–48.885/8.245 aus den
  Testfixtures) zeigte im Job-Log
  `"Routing nicht verfügbar (request timed out) – Fallback auf Luftlinie"` beim
  OSM-Straßennetz-Abruf (nach dem erfolgreichen POI-Abruf) → Luftlinien-Fallback, per Skill-Kriterium
  als Baseline unzulässig. Gemessen (zur Doku, nicht als Baseline verwendbar):
  Ø 2,65 km, Median 1,91 km, Fuß-Anteil 48,9 % (2634 Trips) — überlappt mit beiden erwarteten
  Signaturen (Luftlinie ~2,0 km/50 % Fuß vs. geroutet ~2,9 km/35 % Fuß), was genau zeigt, warum
  der Job-Log-Grep und nicht die Zahlen allein entscheiden muss. Die Session-Disziplin
  (max. ein Generierungslauf, kein blindes Wiederholen schwerer Overpass-Abfragen) verbietet
  einen zweiten Lauf in derselben Session — die Elastizitäts-Sonde (Phase 2b) bleibt daher
  **offen**, Phase-2a-Infrastruktur ist unverändert einsatzbereit.
- **✅ Routing-Netzabruf gehärtet (2026-07-12):** Ursachenkorrektur zur obigen Vermutung — die
  Overpass-**Mirror**-Fallbacks (`OverpassClient.post` → `allEndpoints()`) gelten sehr wohl auch für
  den Straßennetz-Abruf des Routings (`fetchRoadNetworkOsm`), nicht nur für SUMO. Die eigentliche
  Ursache war das zu knappe, **serverseitige** Query-Budget: die schwere `way["highway"]`+recurse-
  Extraktion lief mit dem allgemeinen `[timeout:30]` unter Last auf **allen** Endpunkten in den
  Timeout (der leichte POI-Abruf mit `[timeout:30]` kam durch). Fix: eigenes
  `synthesis.overpass.road-network-timeout-seconds` (Default **180**) wird in die Query eingebettet
  UND als Client-Timeout genutzt (`post` nimmt jetzt ein absolutes Client-Timeout). `OverpassClientTest`
  prüft die Einbettung; Suite 23/23 grün. Damit sollte der nächste Baseline-Anlauf durchrouten;
  bleibt Overpass komplett aus, ist die Bbox klein (Region verkleinern) oder ein self-hosted/PBF-Weg
  der robustere Produktions-Ausbau (docs §D/§F).
- **✅ Elastizitäts-Sonde (Phase 2b) — Hebel empirisch bestätigt (2026-07-12):** Zwei logiTopp-Läufe
  auf DEMSELBEN Legacy-Export (Szenario `0e020eab`, seed 42, fraction 0,05) über den neuen
  `destinationChoiceDir`-Override, einziger Unterschied = `b_intrazonal` in allen vier Zweck-Dateien
  (business/leisure/service/shopping) um einen additiven Term reduziert. Vorhersage (vor dem Lauf):
  Ø steigt, `<1 km`-Anteil fällt. Ergebnis (streng monoton):

  | `b_intrazonal` Δ | Ø-Distanz | Median | `<1 km` | `2–5 km` |
  |---|---|---|---|---|
  | 0 (Baseline) | 2,65 km | 1,91 | 25,0 % | 37,1 % |
  | −1,0 | 2,80 km (+5,7 %) | 1,91 | 18,1 % | 39,7 % |
  | −3,0 | 2,94 km (+10,9 %) | 1,91 | 9,4 % | 40,8 % |

  **Befund:** Die Zielwahl-Distanz reagiert korrekt signiert und dosisabhängig auf `b_intrazonal` —
  der Hebel für eine Distanz-Kalibrierung ist bewiesen. **Grenze (ehrlich):** selbst bei −3,0 bleibt
  Ø ~2,9 km; die Kalibrierung löst die **Nahdistanz-Überkonzentration** (Intrazonal-Kollaps 25→9 %),
  kann aber **keine Fernwege erzeugen**, wo im ~6-km-Gebiet keine fernen Ziele liegen — die absolute
  Ø-Decke ist ein Gebietsgrößen-Problem (→ größeres Untersuchungsgebiet bzw. gravitationskonsistente
  externe Zonen, Kampagnen-Optionen B/C). Provenienz: geteilter Export → Δ/Richtung/Monotonie valide,
  absolute Werte NICHT die (geroutet) saubere Produktions-Baseline.
- **✅ Phase 3 — `DistanceCalibrator` umgesetzt + validiert (2026-07-12):** neuer Kalibrator nach
  `ModeChoiceCalibrator`-Muster (Flag `population.calibrateDistance` default=false; benötigt Ziel
  logiTopp). Stellgröße `b_intrazonal` in allen vier Zweck-Dateien, Zielgröße = `<1 km`-Wegeanteil;
  Update `Δb = 3·ln(Ziel/Ist)` mit Clamp ±2 je Iteration (die Konstante 3 ≈ 1/0,32 aus der
  gemessenen Elastizität). Läuft über den `destinationChoiceDir`-Override, patcht die Dateien in
  `artifacts/simulation/dc-calibrated/`, Ziel-Nahanteil `synthesis.simulation.distance-calibration-
  target-near-share`. Der damalige Näherungs-Default 0,14 ist am 2026-07-27 nach Beschaffung
  der amtlichen MiD-Tabelle auf **0,21** korrigiert worden. `DistanceCalibratorTest`
  (Fake-Sim-Konvergenz in beide Regelrichtungen + Clamp) grün. Studio-Checkbox
  „Distanz-Verteilung kalibrieren". **Historische End-to-End-Mechanikprüfung:** ein Schritt
  (`Δb −1,74`) brachte den `<1 km`-Anteil von 0,250 auf **0,142** und bewies die
  Reglerwirkung; der dabei verwendete Zielwert 0,14 ist fachlich verworfen und diese Messung
  validiert den neuen Zielwert 0,21 nicht.
- *Weiterhin offen (Grenze, nicht Kalibrier-Ziel):* der absolute Ø (~2,9 km) — dafür braucht es ein
  größeres Untersuchungsgebiet bzw. gravitationskonsistente externe Zonen. Optional: Multi-Band-Ziel
  (zusätzliche Hebel `b_0_1`/`b_1_2`). Die Distanz-Kalibrierung lässt sich inzwischen
  sequenziell mit der Zwei-Hebel-Modus-Distanz-Kalibrierung kombinieren; die reine
  Modal-Split-Kalibrierung bleibt eine alternative MNL-Schleife.
- **🔬 Gebietsgröße getestet (Option B, 2026-07-12) — These bestätigt, neue Grenze entdeckt:**
  zwei kontrollierte **Offline**-Läufe (identisch, je 4000 Personen, Template-Verhalten, seed 42,
  `useRealRouting=false` → kein Overpass), nur die Fläche unterschiedlich: klein ~10 km (108 Zonen)
  vs. groß ~55 km (594 Zonen). Ergebnis Ø 2,58 → **4,95 km** (+92 %), und die große Fläche erzeugt
  **erstmals Fernwege** (>10 km: 0 % → 13,3 %). **These bestätigt:** die Gebietsgröße ist der Hebel
  für den absoluten Ø. **ABER neue Grenze:** im großen Gebiet bricht der Median auf 0,30 km ein und
  der `<1 km`-Anteil springt auf 52,6 % — die Verteilung wird bimodal. Ursache (verifiziert): alle
  `<1 km`-Wege liegen bei **exakt 0,30 km** — der Intrazonal-Distanz-Boden in `MatrixGenerator`
  (Zeile ~79, `Math.max(0.3, beelineKm·CAR_DETOUR)`) ist **fest** und skaliert NICHT mit der
  Zellgröße; das Zonenlimit (`max-zones` 600) erzwingt über großer Fläche grobe Zellen (~2,3 km), in
  denen Intra-Zonen-Wege real ~1,2 km lang wären, aber als 0,30 km erfasst werden.
  **Konsequenz (die drei Hebel sind komplementär, nicht alternativ):** (1) größere Fläche für die
  Fernwege, (2) **feinere Zonen** (höheres `max-zones`) bzw. ein zellgrößen-skalierter Intrazonal-
  Boden, damit das kurze Ende stimmt, (3) `DistanceCalibrator` für den Nahdistanz-Anteil.
  *Vorgeschlagener Fix (eigener Change-Control-Zyklus, weil er die Matrix JEDES Luftlinien-Laufs und
  damit bestehende Kalibrier-Baselines verschiebt):* Intrazonal-Boden ≈ halbe Zellbreite statt fester
  0,3 km — mit Spike + Re-Validierung der Kalibrier-Ergebnisse.
- **✅ INSPIRE-/EPSG:3035-Zonierung als Provider (2026-07-13, Europa-Fokus):** Zweiter
  Zonierungs-Modus `zoning.mode = grid | inspire` (Default `grid`, unverändert). `inspire` rastet
  die Zonen auf das europaweite, **flächengleiche** Zensus-Standardgitter (EPSG:3035/ETRS89-LAEA)
  ein — kanonische Auflösungen 100 m/1 km/10 km, INSPIRE-Zell-IDs (`CRS3035RESxmN…E…`),
  überlappungsfreies Containment in LAEA-Metern. Neu: `Epsg3035.fromLatLon` (Forward-Projektion,
  Roundtrip-getestet), `InspireGridZoning`, `Zone.laeaE/laeaN/laeaRes`. Nutzen: europaweit
  vergleichbare/mergebare Zonen-IDs und die **verlustfreie** Zuordnung der Zensus-Rasterdaten (die
  `PopulationGridParser` ohnehin in EPSG:3035 liest) statt Re-Gridding. **Nur Europa** (EPSG:3035
  ist für den EU/EWR-Raum definiert) → der globale Bbox-Modus bleibt Default/Fallback. Tests:
  `Epsg3035Test`, `InspireGridZoningTest` + E2E-Variante `fullPipelineWithInspireZoningOffline`
  (INSPIRE-Zonen laufen durch die ganze Pipeline, `zones.csv` trägt die INSPIRE-IDs); Suite 32/32,
  ng build grün. Studio-Auswahl „Zonierungs-Gitter". *Ausblick:* das oben genannte Intrazonal-Boden-
  Problem wird mit INSPIRE-Zellen sauber lösbar (bekannte, echte Zellkante → Boden ≈ 0,5214·R);
  OLC/Geohash bleiben, falls je gebraucht, abgeleitete Zentroid-Labels (keine Geometrie-Änderung).
- **✅ Intrazonal-Boden zellgrößen-skaliert (2026-07-16):** der feste 0,3-km-Boden in
  `MatrixGenerator` ist ersetzt durch die **erwartete Binnendistanz der Zelle**
  (Luftlinie = 0,5214 · √`areaKm2`, der Erwartungswert der Distanz zweier gleichverteilter Punkte
  im Quadrat); alle Modi erben konsistent (vorher hatte NUR das Auto den Boden — Fuß/Rad waren
  intrazonal 0 km/0 min, Laufen innerhalb der Zone also „gratis" → Fuß-Bias). Intrazonale Paare
  werden nicht mehr geroutet (Zentroid→sich selbst wäre 0 m). Kontrollierter A/B (je zwei
  identische Offline-Läufe, Template, seed 42, 4000 Personen, Vorhersage vorab notiert):
  **großes Gebiet** (600 Zonen, ~2,3-km-Zellen): Median 0,30 → **3,10 km**, `<1 km` 51,2 → **0,0 %**,
  Ø 5,07 → 5,86 km, Bimodalität weg (der frühere 0,30-Spike liegt jetzt exakt auf der Formel:
  1,62 km = 0,5214·2,3·1,35), Fuß 45,8 → 34,0 %, Modal-RMSE 0,161 → 0,119. **Standard-Gebiet**
  (1-km-Zellen): Ø 2,08 → 2,24 km, Median 1,35 → 1,72 km, `<1 km` 31,8 → 29,1 %, Fuß 51,5 → 48,5 %,
  RMSE 0,165 → 0,150; Wegezahl exakt unverändert (87 010 Trips, 3,108 Wege/Person·Tag) — der Fix
  verschiebt nur Distanzen/Impedanzen, keine Nachfrage. **Konsequenz für Kalibrier-Baselines:**
  alle vorherigen Luftlinien-Kennzahlen (z. B. `<1 km`-Ist des `DistanceCalibrator`) verschieben
  sich; der Kalibrator regelt weiterhin auf `b_intrazonal`, sein amtliches `<1 km`-Ziel ist seit
  2026-07-27 jedoch 0,21 statt des verworfenen Näherungswerts 0,14.
  Tests: neuer `MatrixGeneratorTest` (Skalierung mit Zellgröße, Interzonal ohne Boden, intrazonal
  nie geroutet); Suite 35/35, ng build grün.
- **🔬 Phase 4 — Drei-Seed-A/B teilweise bestanden (2026-07-27):** Präregistrierter Vergleich
  mit Seeds 42/43/44, actiTopp, logiTopp und lokalem Rastatt-OSM-Extrakt; einziger
  A/B-Unterschied `population.calibrateDistance`. Der `<1 km`-Anteil änderte sich
  **15,2→15,2 / 16,1→13,3 / 15,5→15,5 %** (Mittel 15,6→14,7 %); zwei Baselines lagen bereits
  innerhalb der damaligen Toleranz und wurden nicht verstellt. **Zielwert-Korrektur:** Das
  damalige Näherungsziel 0,14 ist fachlich verworfen; MiD A W12 ergibt für `<1 km`
  **0,21** (10 % + 11 %), während 0,14 zur Klasse `1–<2 km` gehört. Seed 43 wurde daher
  in die falsche Richtung geregelt. Modal-RMSE
  **0,069→0,069 / 0,073→0,070 / 0,072→0,072**, Wege/P/Tag 3,19–3,26. Alle sechs Läufe
  nutzten GraphHopper ohne Stufen-Fallback. **Ehrliche Grenze:** Ø bleibt 3,77–3,87 km;
  gegen den amtlich aus MiD-Tabelle A W12 aggregierten Sechs-Band-Zielvektor
  `[21, 14, 23, 16, 13, 13] %` scheitert der vorab fixierte RMSE-Gate:
  **0,1055→0,1055 / 0,1014→0,1094 / 0,1019→0,1019** (Ziel höchstens 0,05).
  `calibrateDistance` bleibt `default=false`; der Ziel-Property-Default ist **0,14→0,21**
  korrigiert. `kpis.json` weist Bandanteile, Ziel, Quelle
  und RMSE für Folgeläufe direkt aus. Details: `docs/05-validierung.md`.
- **🔬 Phase 4 — Wiederholung mit amtlichem 21-%-Ziel (2026-07-28): Mechanik bestanden,
  Beförderung gescheitert.** Präregistrierte A/B-Wiederholung mit derselben Laufmatrix und ohne
  ausgelöste Fallbacks. Der `<1 km`-Anteil stieg reproduzierbar
  **15,15→19,49 / 16,05→20,22 / 15,52→19,70 %**; der Sechs-Band-RMSE verbesserte sich
  **0,1055→0,0946 / 0,1014→0,0919 / 0,1019→0,0912**. Modal-RMSE verschlechterte sich nur um
  0,0003–0,0048 (Gate höchstens 0,02), Wege/P/Tag blieben bei 3,19–3,26 und 98,7–98,8 % der
  exportierten Personen waren mobil. **Ehrliche Grenze:** Der absolute RMSE-Gate ≤0,05 scheitert
  in allen Seeds; mindestens 20 km erreichen in B nur 0,29–0,43 % statt 13 % in MiD.
  `calibrateDistance` bleibt deshalb `default=false`. Nächster falsifizierbarer Hebel ist das
  lange Ende über größeren Untersuchungsraum und/oder gravitationskonsistente externe Ziele,
  danach Multi-Band-Kalibrierung. Details: `docs/05-validierung.md`.
- **🔬 Großraum-Spike nach Intrazonal-Fix (2026-07-28): Gebietshebel bestätigt,
  weiterhin nicht promotionsfähig.** Kontrolliertes Offline-A/B mit identischer
  2,3-km-Auflösung, Seed 42, 2.000 Personen, Template-Verhalten, Luftlinienmatrizen und ohne
  externe Zonen; einziger Szenario-Unterschied war die Region (ca. 7 × 7 km/9 Zonen gegenüber
  55 × 55 km/576 Zonen). `>=10 km` stieg **0,0→11,6 %**, `>=20 km`
  **0,0→5,3 %**, Ø **2,46→5,51 km**, Median **1,62→3,09 km** und Distanzband-RMSE
  **0,2332→0,1693**; Wege/P/Tag blieben 3,09→3,06. Der frühere 0,30-km-Kollaps ist damit
  nachweislich beseitigt. **Ehrliche Grenze:** MiD (Ø 12,5 km, 13 % ab 20 km, RMSE-Gate
  höchstens 0,05) bleibt deutlich verfehlt, und 96,7 % der Wege über 20 km fallen auf den ÖV.
  Nächster Hebel: modusspezifische Distanzsensitivität/Mehrband-Kalibrierung, danach
  Drei-Seed- und Realrouting-Validierung.
- **🔬 ÖV-Reisezeit-Elastizität isoliert + gemeinsames Ziel verankert (2026-07-28):**
  Ein Ein-Faktor-Spike auf demselben Großraum-Export änderte ausschließlich
  `b_tt_put` von −0,05 auf den Pkw-Wert −0,20. Die Distanzverteilung blieb stabil
  (Ø 5,51→5,54 km), der ÖV-Anteil fiel jedoch insgesamt 21,8→0,4 % und ab 20 km
  96,7→0,0 %; die langen Wege wechselten vollständig zu Pkw-Fahrer/Mitfahrer.
  Damit ist der MNL-Zeitkoeffizient als Ursache bestätigt, −0,20 aber als
  Überkorrektur verworfen. Weil getrennte Randverteilungs-RMSEs diesen Fehler
  verdecken, schreibt `kpis.json` nun `modalSplitByDistanceBand` und den
  30-Zellen-`modeDistanceJointRmseToMid2017`. Ziel ist MiD 2017 Tabelle A W10.5,
  auf die vorhandenen sechs Distanzbänder aggregiert; ab 20 km: 61,1 % Pkw-Fahrer,
  18,9 % Mitfahrer, 17,6 % ÖV. Nächster Versuch: `b_tt_put` zwischen −0,05 und
  −0,20 gegen diese gemeinsame Zielmatrix, dann drei Seeds und Realrouting.
  Das Studio visualisiert alle 30 Zellen als Simulation/MiD-Paare; der
  Szenario-Vergleich zeigt den gemeinsamen RMSE.
- **🔬 Modus-Distanz-Kalibrierung (2026-07-28): Zwei-Hebel-Mechanik bestätigt,
  Promotions-Gate verfehlt.** Das präregistrierte `b_tt_put`-Raster
  −0,08/−0,10/−0,12 verschob ÖV ab 20 km streng monoton auf
  71,03/23,16/3,50 %, verbesserte den Joint-RMSE aber höchstens
  0,054671→0,054109. Ein Zeitkoeffizient trifft Niveau und Schräge nicht
  gleichzeitig. Eine separat präregistrierte Kombination
  `b_tt_put=-0,11`, `asc_put=-1,22` erreichte **9,34 % ÖV gesamt** (MiD 10 %)
  und **17,60 % ÖV ab 20 km** (MiD 17,61 %); Modal-RMSE
  0,105742→0,085375, Joint-RMSE 0,054671→0,053002, Wege/Distanzbänder stabil.
  **Keine Beförderung:** Der unveränderte Gate Joint-RMSE <0,05 und
  Verbesserung ≥0,004 scheitert; die fehlende Distanzmarginale (hier 0 %
  statt 21 % unter 1 km) ist nicht über Modusparameter heilbar. Kein
  Produktparameter geändert. Nächster Schritt: drei Seeds + Realrouting +
  gemeinsame Distanzmarginale; Legacy-ÖV-Zugang/-Abgang separat korrigieren.
- **🧪 Zwei-Hebel-Regler opt-in implementiert (2026-07-28; E2E-Promotion weiter offen):**
  `ModeDistanceCalibrator` setzt die bestätigte Mechanik ohne fest verdrahtete
  Versuchswerte um. Das Flag `population.calibrateModeDistance` bleibt `default=false`.
  Pro logiTopp-Iteration regelt `asc_put` die globale ÖV-Log-Odds-Abweichung und
  `b_tt_put` den ASC-robusteren Kontrast
  `logit(ÖV >=20 km) − logit(ÖV gesamt)`; Updates sind auf ±0,75 beziehungsweise
  ±0,04 geklemmt. Ziel sind der konfigurierbare globale ÖV-Anteil
  (`calibrationTarget.publicTransport`, Default 10 %) und die MiD-Zelle ÖV ab
  20 km (17,61 %). Weniger als 30 Fernwege brechen mit einer verständlichen
  Fehlermeldung ab, statt Rauschen zu kalibrieren. Mit
  `population.calibrateDistance=true` läuft zuerst die
  `b_intrazonal`-Kalibrierung; der Zwei-Hebel-Regler verwendet anschließend deren
  Zielwahlparameter zusammen mit seiner aktuellen MNL-Datei. Nur die reine
  `calibrateModeChoice`-Schleife wird weiterhin ersetzt. Das Studio erlaubt genau
  diese fachlich definierte Kombination. Eine nachfolgende Netzfeedback-Schleife
  behält beide aktiven Overrides bei und wählt sie über die Szenario-Flags statt
  nur anhand eventuell veralteter Artefakte.
  **Verifiziert:** `ModeDistanceCalibratorTest` belegt Konvergenz beider Ziele,
  getrennte Clamps, unveränderte Fremdparameter und Wegketten-Aggregation vor der
  Distanzbandzuordnung; `SimulationRunnerTest` belegt beide Overrides in derselben
  Run-Config. Der externe Großraum-Spike belegt die Hebelmechanik (9,34 % gesamt /
  17,60 % ab 20 km). Die damals noch offene automatische Schleife über drei
  Seeds mit Realrouting wurde am 2026-07-29 begonnen und über ihr Stop-Gate
  beendet (nächster Eintrag); daher weiterhin kein Default-Flip.
- **❌ Gemeinsame Drei-Seed-/Realrouting-Kampagne gestoppt (2026-07-29) —
  ehrlicher Modellierungsbefund:** Das präregistrierte Stop-Gate griff nach
  A42/B42. Bei 2,3-km-Zellen repräsentiert `CAR_DIS` intrazonal genau
  **1,62 km**; `b_intrazonal` kann daher das kalibrierte `<1 km`-Band nicht
  beeinflussen. Acht Iterationen änderten den Anteil trotz Parameter-Delta
  **0→14** nicht (**0,0→0,0 %**) und verschoben stattdessen **81,54 %** der
  Wege ins Band 1–<2 km. Distanzband-RMSE verschlechterte sich
  **0,1343→0,3072**, Joint-RMSE **0,0438→0,0857**. Der ÖV-Regler traf zwar
  9,28 % gesamt und 18,89 % ab 20 km, kann diese Distanzmarginale aber nicht
  heilen. A/B nutzten byte-identische GraphHopper-Matrizen; Routing-Fallback
  ist als Ursache ausgeschlossen. Seeds 43/44 wurden nicht gestartet.
  **Fix:** `GenerationService` lehnt `calibrateDistance=true` jetzt vor dem
  teuren Graphaufbau mit der gemessenen Mindestdistanz ab, wenn kein
  intrazonaler Pkw-Wert im Zielband liegt. `GenerationServiceCalibrationTest`
  belegt Ablehnung bei 2,3-km- und Zulassung bei 1-km-Zellen. Beide
  Kalibrierflags bleiben opt-in; ein neuer Promotionslauf braucht feinere
  Zonierung oder eine methodisch neu präregistrierte Distanzrepräsentation.
  **Verifiziert:** REST-Prüflauf scheitert bereits in der Zonierungsstufe nach
  4 ms mit der gemessenen Mindestdistanz; OSM, actiTopp und GraphHopper bleiben
  unberührt. Ein MobileCity-only-Gegenlauf mit demselben historischen Flag
  endet weiterhin `DONE`. Backend-Suite 69 Tests grün (6 erwartete Skips),
  Angular-Strict-AOT-Build grün.
- **🧪 Folgeexperiment „Großraum × feine Zonierung" präregistriert (2026-08-11):**
  **Hypothese:** Ein ca. 55 × 55 km großer Raum erzeugt das bislang fehlende lange Ende,
  während ein 1-km-INSPIRE-Gitter den intrazonalen Pkw-Wert mit ca. 0,70 km im kalibrierten
  `<1 km`-Band hält. Damit sind Gebietsgröße und `b_intrazonal` erstmals gleichzeitig
  identifizierbar. A/B unterscheiden sich ausschließlich durch
  `population.calibrateDistance`; Region, 1-km-Zonierung, Population, Matrizen und Seed
  bleiben identisch. **Vorab-Ziele:** Distanzband-RMSE sinkt gegenüber A um mindestens
  0,02 und auf höchstens 0,08; `<1 km` liegt in B bei 19–23 %, `>=20 km` bei mindestens
  8 %; Modal-RMSE verschlechtert sich höchstens 0,02 und Wege/Person·Tag bleiben in
  [3,0; 3,4]. Das Promotions-Gate bleibt unverändert strenger (Distanzband-RMSE ≤0,05
  über Seeds 42/43/44).
  **Stop-Gate 1 – Ressourcen:** zunächst nur Zonierung plus Luftlinienmatrizen mit
  `synthesis.max-zones=3600`, 2.000 Personen, Template-Verhalten und Seed 42. Das
  1-km-Gitter muss 3.000–3.600 Zonen liefern, ohne automatische Vergröberung und ohne
  OOM; Laufzeit, Peak-RSS und Matrixgrößen werden notiert. Bei OOM, mehr als 30 Minuten
  Matrixlauf oder mehr als 8 GiB Matrixartefakten wird kein logiTopp-Lauf gestartet.
  **Stop-Gate 2 – Mechanik:** erst danach ein seed-identisches A42/B42 auf denselben
  Matrizen. Bei `<1 km` ohne Bewegung in Richtung 21 % oder Distanzband-RMSE ohne
  Verbesserung um mindestens 0,02 werden Seeds 43/44 nicht gestartet. Besteht B42,
  folgt die Drei-Seed-/Realrouting-Wiederholung; ein Multi-Band-Regler wird erst nach
  bestätigter Hebelelastizität entworfen.
  **Vorprüfung:** Die präregistrierte Bbox 48,613/7,825–49,107/8,575 ergibt exakt
  **3.249 Zonen**, **10.556.001 OD-Zellen je Matrix** und **0,70389 km** intrazonale
  Pkw-Distanz. `GenerationServiceCalibrationTest.fineLargeAreaKeepsDistanceCalibrationObservable`
  fixiert Region, 1-km-Raster, 3.600-Zonen-Limit und Beobachtbarkeits-Guard.
  **Stop-Gate 1 bestanden (2026-08-11):** Drei isolierte Wiederholungen erzeugten jeweils
  alle 13 Luftlinienmatrizen in **90,608 s**, **93,252 s** und **94,148 s**. Der intern
  gemessene Java-Heap-Peak lag bei **1.376,9 MiB**, ein vollständiger Matrixsatz bei
  **215.640 KiB**; `CAR_DIS` ließ sich als `$V;D3` mit 3.249 Netzobjekten zurücklesen.
  Damit wurden weder das 30-Minuten- noch das 8-GiB-Abbruchkriterium berührt.
  **Stop-Gate 2 unentscheidbar (2026-08-11):** A42 erreichte mit 2.000 Personen,
  56.859 Template-Aktivitäten und denselben 3.249 Zonen den Simulationsexport; der
  Matrixschritt dauerte rund **122 s**, der Legacy-Export **13:08 min**. Danach blieb
  Gradle länger als 31 Minuten in `compileJava` beim Quellbaum-Snapshot unter
  `validation/mobitopp-rastatt/.../populationsynthesis/mobilityprovider` hängen
  (`UnixNativeDispatcher.fdopendir`/`DirectorySnapshotter`). logiTopp startete nicht,
  B42 wurde regelkonform nicht gestartet und der isolierte Prozess kontrolliert beendet.
  Das ist ein lokaler Runtime-/Dateisystemblocker, kein Negativbefund zur Distanzhypothese.
  Vor einer Wiederholung muss der gitignorierte KIT-Quellbaum unter Erhalt seines lokalen
  Zustands gegen den gepinnten Runtime-Vertrag rekonstruiert werden; bis dahin bleiben
  Seeds 43/44 und ein Multi-Band-Regler gesperrt.
  **Rekonstruktions-Update:** Der frische Harness-Clone beseitigt den blockierenden
  `mobilityprovider`-Pfad, offenbart aber eine Lücke im Bootstrap-Vertrag: Upstream
  `build.gradle` hat Hash `6a23e17a…`, der logiTopp-Installer verlangt vor dem Patch
  `fecda2bf…`; kein versionierter Schritt erzeugt diesen Zwischenstand. Außerdem ist
  der bestehende actiTopp-Clone beim Gradle-Dateihashing auffällig langsam. Der alte
  Harness bleibt als `mobitopp-rastatt.backup-20260811-e23` erhalten; Hash-Guards wurden
  nicht umgangen und A42 bleibt bis zur reproduzierbaren Runtime-Reparatur gesperrt.
  **Testabdeckung wiederhergestellt (2026-08-13):** Der Re-Clone hatte nebenbei die
  Demand-Fixture dreier Testklassen entfernt (`SumoRunnerTest`, `PtIntegrationTest`,
  Teilfall `LogitoppDemandAdapterTest` — still SKIPPED). Nach bit-identischem
  Zurückkopieren zweier Ergebnisdateien aus dem Backup läuft die Backend-Suite wieder
  127/0/0 ohne Skip. Das berührt weder Hash-Guard noch Bootstrap-Vertrag: E23 und die
  A42/B42-Sperre bleiben unverändert offen (Details in
  [docs/05-validierung.md](05-validierung.md), Abschnitt „Großraum × feine Zonierung").
  **❌ Stop-Gate 2 nicht bestanden (2026-08-15):** Das A/B lief erstmals vollständig
  durch (logiTopp-Stufe 102–392 s statt >31 min Hänger). Der erste A-Arm war durch
  einen stillen Overpass-Fallback konfundiert (`poi-density` statt `osm-places`,
  2.000 statt 1.595 Haushalte, Stufe trotzdem DONE) und wurde als A42b in derselben
  Backend-Session wiederholt; danach unterscheiden sich die Manifeste in genau einem
  Feld. Gültiges Ergebnis A42b → B42: Distanzband-RMSE **0,0582 → 0,0448**
  (Verbesserung 0,0134, gefordert ≥0,02 → verfehlt), `<1 km` **15,81 → 20,99 %**
  (Ziel 19–23 % erreicht, 2 Kalibrier-Iterationen), Modal-RMSE +0,0049,
  Wege/Person·Tag 3,07. Nach Vorab-Regel werden **Seeds 43/44 nicht gestartet**.
  Mechanisch ist der Hebel besser als erwartet: Er speist die kurzen Wege aus den
  mittleren Bändern (`1–2 km` −2,6 pp, `2–5 km` −1,5 pp), während `>=20 km` in beiden
  Armen bei exakt 7,30 % liegt — das verfehlte `>=20 km`-Kriterium misst also nicht
  den Regler. Gestoppt wird an der **relativen** Schwelle, die ein schwächeres A
  unterstellte; absolut liegt B mit 0,0448 bereits unter der Promotions-Schwelle
  ≤0,05. Die Regel bleibt unangetastet — ein zweiter Anlauf braucht eine **neue**
  Präregistrierung mit an A42b geeichten Schwellen. Details:
  [docs/05-validierung.md](05-validierung.md), Abschnitt „Großraum-A/B durchgeführt".
- **🧪 Folgeexperiment „Distanz-Abklingen" präregistriert (2026-08-15):**
  **Hypothese (ein Mechanismus):** Der nach der `b_intrazonal`-Kalibrierung verbleibende
  Distanzfehler ist **kein Band-Zuordnungsfehler, sondern ein Distanz-Abklingfehler**: Die
  Reisezeit-Betas der Zielwahl (`b_tt_*` in
  `destination_choice_utility_calculation_parameters.txt`) bestrafen entfernte Ziele so
  stark, dass Masse in 1–5 km liegen bleibt, statt 5–20 km zu erreichen.
  **Belegende Residualstruktur (B42, gemessen):** Überschuss `1–2 km` +7,73 pp und
  `2–5 km` +3,22 pp ergeben **+10,95 pp**; Defizit `5–10 km` −1,23, `10–20 km` −4,01 und
  `>=20 km` −5,70 ergeben **−10,94 pp**. Die Bilanz gleicht sich bis auf 0,01 pp aus — die
  Masse muss weit wandern, nicht ins Nachbarband. Derselbe Mechanismus erklärt die
  Negativbefunde: `<1 km` traf punktgenau (eine Stellgröße, ein Ziel, wohlgestellt),
  `>=20 km` ist in A42b und B42 **exakt identisch** (7,30 %), weil `b_intrazonal` das
  lange Ende gar nicht erreicht. Er sagt vorher, was **nicht** wirkt: ein zusätzlicher
  Band-Bonus `b_1_2` verschöbe nur nach `2–5 km`, das bereits +3,22 pp über MiD liegt.
  Damit weicht diese Präregistrierung bewusst von der in `synthesis-distanz-kampagne`
  vorgemerkten Multi-Band-Reihenfolge ab; `b_0_1`/`b_1_2` bleiben Rückfalloption.
  **Stellgröße:** additiver Positivterm auf `b_tt_car_d`, `b_tt_car_p`, `b_tt_ped`,
  `b_tt_bike`, `b_tt_put` (Format: `b_tt_car_d = -0.0688 -0.05 +X`), über den bestehenden
  `destinationChoiceDir`-Override.
  **Stop-Gate 1 – Elastizität (Zahlen vorab):** Dosis-Wirkungs-Sonde mit X ∈ {+0,01;
  +0,03; +0,06} auf dem **wiederverwendeten** Legacy-Export von A42b (nur EIN Unterschied),
  Seed 42, gemessen per `distance_profile.py`. **Vorhersage:** `>=10 km`-Anteil steigt
  monoton von 16,29 %; bei X=+0,06 mindestens 20 %. Bricht die Monotonie oder bleibt die
  Änderung unter 1 pp, gilt der Hebel als unwirksam → Stop, Rückfall auf `b_1_2`.
  **Stop-Gate 2 – A/B, Schwellen an A42b/B42 geeicht:** seed-identisches **C42**
  (`calibrateDistance=true`, Betas unverändert = B42-Konfiguration) gegen **D42**
  (zusätzlich bester X-Wert aus Gate 1), beide **in derselben Backend-Session** (E24).
  | Kriterium | Schwelle | Referenz |
  |---|---|---|
  | Distanzband-RMSE | **≤ 0,035** | A42b 0,0582 · B42 0,0448 |
  | `>=10 km` (Mechanismus) | **≥ 20 %** | beide Arme 16,3 % |
  | `<1 km` (Nichtregression) | bleibt 19–23 % | B42 20,99 % |
  | `1–2 km` | ≤ 18 % | B42 21,73 % |
  | Modal-RMSE | ≤ +0,02 gegen C42 | A42b 0,0982 · B42 0,1031 |
  | Wege/Person·Tag | [3,0; 3,4] | beide 3,07 |
  **Bewusst gestrichen:** das frühere `>=20 km ≥ 8 %`-Kriterium. Es war in beiden Armen
  identisch (7,30 %) und misst Gebietsgröße statt Regler; ersetzt durch das
  `>=10 km`-Mechanismuskriterium. **Absolut statt relativ:** Die Schwelle ist als fester
  Zielwert gesetzt, nicht als Delta gegen den Kontrollarm — genau daran scheiterte B42
  formal, obwohl das Niveau gut war.
  **Seed-Protokoll:** zuerst Seed 42; die Drei-Seed-Wiederholung (42/43/44) startet erst,
  wenn Gate 2 vollständig besteht. **Vorbedingung je Lauf:** `populationDistribution` und
  `counts.households` beider Manifeste vergleichen (E24), sonst ist der Lauf ungültig.
  **Beförderung** erst nach zugewiesenem Widerlegungsversuch in einer zweiten Session
  (`synthesis-research-methodology`, Abschnitt 4).
  **✅ Stop-Gate 1 bestanden (2026-08-15):** Dosis-Sonde auf dem A42b-Export, logiTopp je
  ~58 s. `>=10 km` steigt monoton 16,57 → 17,01 → 18,47 → **22,09 %**; Vorhersage
  getroffen. Mechanismus bestätigt — die Masse wandert weit (`1–2 km` −7,6 pp gegen
  `5–10 km` +6,5 und `10–20 km` +4,8), nicht ins Nachbarband. Harness validiert: X=0
  reproduziert A42b auf 0,1 pp. Dosiswahl **X=+0,06** nach dem Mechanismuskriterium
  `>=10 km ≥ 20 %`, das nur diese Dosis erreicht — nicht nach dem Sonden-RMSE, das ohne
  Nahband-Regler bei X=+0,03 optimiert.
  **✅ Stop-Gate 2 bestanden (2026-08-15, Seed 42):** C42 `36b1fb61` gegen D42
  `90e250ae`, Manifeste bis auf id/Name/Zeit identisch. Alle sechs Vorab-Kriterien
  erfüllt: Distanzband-RMSE **0,0448 → 0,0258** (Schwelle ≤0,035, unterbietet auch die
  Promotions-Schwelle ≤0,05), `>=10 km` 16,28 → **20,70 %**, `1–2 km` 21,73 → **13,75 %**
  (MiD 14 %), `<1 km` 21,34 %, Modal-RMSE +0,0063, Wege/Person·Tag 3,07. Ø-Distanz
  6,08 → 6,93 km. Drei-Seed-Wiederholung (43/44) damit freigegeben.
  **Vor Beförderung offen:** `>=20 km` bleibt bei 7,89 % gegen MiD 13 % (Gebietsgrößen-
  Grenze, von Zielwahl-Parametern unerreichbar — wie die Sonde vorhersagte), und der
  `b_tt_*`-Hebel hat **kein Szenario-Flag**: D42 entstand über ein temporäres Patchen der
  Shared-Parameterdatei mit anschließend hash-verifizierter Wiederherstellung. Details:
  [docs/05-validierung.md](05-validierung.md), Abschnitte „Distanz-Abkling-Sonde" und
  „Distanz-Abkling-A/B".
  **⚠ Drei-Seed-Wiederholung (2026-08-16) — zwei von drei Seeds gültig:** Seeds 43/44
  nachgefahren. Alle sechs Kriterien sind in allen drei Seeds erfüllt (D-RMSE
  0,0258 / 0,0229 / 0,0245, Promotions-Schwelle ≤0,05 klar unterboten), aber **Seed 43
  ist kein Ein-Faktor-Kontrast**: Die `attractivities.csv` der beiden Arme weichen ab,
  bei byte-identischer `zones.csv`. Ursache ist eine instabile Overpass-Antwort, nicht
  Nichtdeterminismus im Code — zwei Kontrollläufe mit identischer Konfiguration liefern
  byte-identische Ergebnisse. Zwei Anläufe für Seed 43 blieben ungültig. Das
  Drei-Seed-Gate ist damit **nicht abgeschlossen**; belastbar sind Seeds 42 und 44.
  Vorbedingung für den nächsten Anlauf: OSM-Eingabe einfrieren und allen Armen als
  Datei vorgeben, plus Hash-Vergleich der Attraktivitäten vor jeder Auswertung.
  **✅ Reproduzierbare OSM-Kampagneneingabe umgesetzt (2026-08-17):** Das Studio und
  `POST /api/scenarios/{id}/uploads/osm-data` akzeptieren einen kombinierten
  Overpass-JSON-Snapshot mit POIs und bevölkerten `place`-Nodes. Die Pipeline verwendet
  `uploads/osm-data.json` vor jedem Live-Abruf und bricht bei nachträglicher Beschädigung
  sichtbar ab. `manifest.json.inputProvenance` enthält Snapshot-Quelle/-SHA-256 sowie
  den SHA-256 der resultierenden `attractivities.csv`; damit ist Datengleichheit direkt
  prüfbar und nicht mehr aus Knotenzahlen oder flüchtigen Logs zu folgern.
  **Verifiziert:** `OsmDataSnapshotTest` (Parsing, Negativfall),
  `UploadControllerTest` (validierter stabiler Uploadname) und der vollständige
  Offline-`GenerationPipelineE2ETest` mit echtem Snapshot-Pfad; Backend 130 Tests,
  0 Fehler, 7 umgebungsbedingte Skips, Frontend 21/21, Produktions-Audit ohne Befund
  und Angular-AOT-Build grün.
  **✅ Drei-Seed-Gate abgeschlossen (2026-08-17):** Der Live-Abruf eines historischen
  Snapshots blieb an allen erreichbaren Overpass-Endpunkten im Dispatcher-Timeout; ein
  unvollständiger Snapshot wurde nicht akzeptiert. Als stärker isolierter Ersatz liefen
  C43 und D43 auf demselben vollständigen C43-Legacy-Export. Einziger Unterschied war
  `b_tt_* +0,06`; D43 konvergierte danach über `b_intrazonal` in zwei Iterationen.
  Distanzband-RMSE **0,0439→0,0240**, `>=10 km` **16,39→21,04 %**, `1–2 km`
  **21,32→13,53 %**, Modal-RMSE-Delta +0,006 und 3,077 Wege/Person·Tag. Damit bestehen
  Seeds 42/43/44 alle sechs vorregistrierten Gates.
  **✅ Distanz-Abkling-Hebel als Szenario-Flag umgesetzt (2026-08-17):**
  `population.correctDistanceDecay` (Default `false`, Studio-Checkbox) erzeugt unter
  `artifacts/simulation/distance-decay-adjusted/` eine szenariogebundene Kopie des
  vollständigen Zielwahl-Dateivertrags und addiert den bestätigten Term `+0,06` auf
  `b_tt_car_d`, `b_tt_car_p`, `b_tt_put`, `b_tt_ped` und `b_tt_bike`. Fehlende oder
  doppelte Schlüssel brechen sichtbar ab; die geteilten Legacy-Parameter bleiben
  unverändert. Der Stand wird auch als Basis für `calibrateDistance`, Modus-Kalibrierung
  und Netz-Feedback verwendet. **Verifiziert:** echter Seed-43-logiTopp-Lauf oben sowie
  `DestinationChoiceParameterAdjusterTest`, `GenerationServiceCalibrationTest` und
  `SimulationRunnerTest`; Backend 135 Tests, 0 Fehler, 7 umgebungsbedingte Skips,
  Frontend 21/21 und Angular-AOT-Build grün. Das Flag bleibt opt-in, bis `>20 km` und
  Mehrregionen-Transfer belegt sind.
  **❌ Mehrregionen-Transferprobe Freiburg gestoppt (2026-08-17):** Die Schwellen
  wurden vor dem Lauf in Commit `bc9a19a` festgeschrieben. T-C42 `01b40fea` und
  T-D42 `798bc6fc` verwendeten byte-identische OSM-, Attraktivitäts-, Populations-,
  Matrix-, Logistik- und Legacy-Eingaben; einziger Unterschied war das produktive
  Flag `correctDistanceDecay`. Die Richtung transferiert (`>=10 km`
  10,16→14,00 %, `1–2 km` 18,35→11,90 %, Ø 4,58→5,38 km), die feste Dosis
  `+0,06` aber nicht: Distanzband-RMSE **0,0611→0,0644** statt Verbesserung,
  absolutes Fernweg-Gate 20 % verfehlt und `>20 km` nur 2,11→2,42 % gegen MiD
  13 %. Vier Schutzkriterien bestehen, drei Primärkriterien scheitern; Seeds 43/44
  wurden gemäß Stop-Regel nicht gestartet. **Konsequenz:** kein universeller
  Transferwert, kein Default-Flip. Ein weiterer Anlauf muss regionale Elastizität
  beziehungsweise hierarchische Parameter neu präregistrieren (vollständiger Befund:
  [docs/05-validierung.md](05-validierung.md)).
  **❌ Sign-erhaltende Grenzsonde beendet (2026-08-17):** Commit `68b4baa`
  fixierte vorab `+0,075` und `+0,09`; letzteres lässt den kleinsten resultierenden
  Koeffizienten `b_tt_put=-0,0006` gerade noch negativ. Mit produktivem Runner und
  Nahkalibrierung steigt `>=10 km` monoton auf 15,95/18,80 %, bleibt aber selbst
  an der fachlichen Grenze unter 20 %. Der Distanzband-RMSE verschlechtert sich
  weiter auf 0,0657/0,0685, während `1–2 km` auf 10,07/8,29 % fällt.
  **Entscheidung:** Der gemeinsame additive Offset ist in Freiburg strukturell
  unzureichend; größere Dosen würden die Reisezeit-Nutzensemantik umkehren und sind
  ebenso gesperrt wie weitere Seeds. Nächster zulässiger Ansatz: getrennte
  Koeffizienten oder regionale/hierarchische Parametrisierung.
  **Sperre aufgehoben (2026-08-13):** Der Bootstrap-Vertrag ist rekonstruiert und setzt
  jetzt auf dem Upstream-Stand auf; der Harness kompiliert in 3 s statt >31 min zu hängen,
  `scripts/preflight.sh full` meldet 0 Fehler. Damit ist die Vorbedingung „KIT-Quellbaum
  gegen den gepinnten Runtime-Vertrag rekonstruiert" erfüllt und **A42 darf wiederholt
  werden**. Die Präregistrierung bleibt unverändert gültig: erst ein vollständiges A42,
  dann B42, Seeds 43/44 und Multi-Band-Regler weiterhin erst nach dem Mechanik-Gate
  (Befund: [docs/05-validierung.md](05-validierung.md), Abschnitt „Bootstrap-Vertrag
  rekonstruiert").
- **⛔ Legacy-ÖV-Zugang/-Abgang nicht angeschlossen (2026-07-28):**
  SyntheSis schreibt `PUT_ACT`/`PUT_EGT` mit je sechs Minuten, der externe
  `MultiNomialLogit1HelperOldImpl` gibt Zugang und Abgang jedoch hart als `0.0`
  zurück; `ImpedanceIfc` stellt dafür keine separate Matrix-API bereit. Ein
  Aufschlag auf `PUT_TTC` wäre keine äquivalente Reparatur. Benötigt wird ein
  expliziter Adapter beziehungsweise eine Erweiterung des externen
  Legacy-Vertrags mit anschließendem A/B-Lauf.
- **✅ Globale Stichprobengröße zonenzahlfest (2026-07-28):** Der erste Großraumlauf wurde
  verworfen, weil `PopulationGenerator` bei 40.000 × 5 % zonenweise 2.015 Personen (9 Zonen)
  beziehungsweise **2.516** (576 Zonen) erzeugte. Größter-Rest-Verteilung und Begrenzung des
  letzten KIT-Haushalts liefern nun in beiden Gebieten exakt **2.000** Personen;
  `PopulationGeneratorTest` reproduziert den 576-Zonen-Fall. Das Manifest nennt die aktive
  Strukturquelle jetzt korrekt `kit-population-sample`. Details: `docs/05-validierung.md`.
- **✅ Distanzdiagnostik im Studio und Szenario-Vergleich (2026-07-28):** Die sechs
  `distanceBands` und `distanceBandRmseToMid2017` aus `kpis.json` sind typisiert und werden neben
  der MiD-2017-Referenz im Studio sowie im A/B-Vergleich angezeigt. Ältere KPI-Dateien ohne diese
  Felder bleiben kompatibel. Dabei behoben: Nach einem Backend-Neustart war der flüchtige
  Generierungsstatus nicht mehr verfügbar; das Studio lud dann nur die Artefaktliste, aber keine
  persistierten Simulationsergebnisse. Der Fehlerpfad ruft nun `loadArtifacts()` inklusive KPIs
  auf. **Verifiziert:** Angular-AOT-Build grün; lokaler Wiederanlauftest lädt Szenario
  `0876c317` mit RMSE 0,0946 und allen sechs Bändern; A/B-Vergleich
  `ced38f5e`→`0876c317` zeigt 0,1055→0,0946; keine Browserwarnungen/-fehler.
- **✅ Zonierungs-Default für Neuanlagen auf INSPIRE geflippt (2026-07-16):**
  `GET /api/scenarios/defaults` (Quelle des Studio-„Neues Szenario") liefert jetzt
  `zoning.mode = inspire`. Der **Feld-Default in `Scenario.Zoning` bleibt bewusst `grid`**:
  drei persistierte Alt-Szenarien tragen keinen `mode`-Key und würden bei einem Feld-Default-Flip
  stillschweigend aufs INSPIRE-Gitter wechseln (andere Zonen bei Regenerierung) — so behalten
  Bestandsdateien und rohe API-POSTs ohne `mode` ihr Bbox-Raster (Muster wie der
  `targets()`-Fallback). Dazu **Europa-Guard**: `Epsg3035.covers(bbox)` prüft den amtlichen
  EPSG:3035-Gültigkeitsbereich (lat 32,88–84,73, lon −16,1–40,18); Regionen außerhalb fallen in
  `previewZones` UND in der Zonierungs-Stufe aufs Bbox-Raster zurück (Job-Log-Hinweis,
  `summary.zoningMode` meldet jetzt in beiden Modi den tatsächlich verwendeten Provider).
  Verifiziert per Live-API: `/defaults` → `inspire`; Alt-Szenario ohne Key lädt weiter `grid`.
  Tests: `coversOnlyTheEuropeanAreaOfUse` (Epsg3035Test), E2E
  `inspireRequestOutsideEuropeFallsBackToGrid` (New-York-Bbox → Grid-Zonen); Suite 37/37.
- **✅ Bevölkerungs-Upload liest INSPIRE-/Eurostat-Zell-IDs + gtfs.de in der Feed-Registry
  (2026-07-17):** Dritter Parser-Dialekt in `PopulationGridParser`: Zell-IDs
  `CRS3035RES<res>mN…E…` (Eurostat Census Grid 2021, Spalte `GRD_ID`+`T`; Zensus-Dateien ohne
  `x_mp`-Spalten via `GITTER_ID_*`) und GEOSTAT-Kurzform `1kmN…E…` (Koordinate × Auflösung);
  verarbeitet wird der Zellmittelpunkt (Ecke + res/2), Rest (LAEA→WGS84, Zonen-Zuordnung,
  Geheimhaltungs-Skip) unverändert. Damit ist der Europa-Teil der INSPIRE-Zonierung erstmals mit
  echten Daten befüllbar (EU-weites 1-km-Zensusgitter, CC BY). Dazu `Feed.gtfsDeNahverkehr()`
  als **letzter** Default-Registry-Eintrag (gtfs.de/DELFI, CC BY 4.0, Bbox Deutschland):
  Verbund-Feeds gewinnen per `findFirst`; der ~223-MB-Feed (Stand 2026-07) liegt bewusst über
  dem `max-feed-mb`-Default 200 → deutsche Regionen ohne Verbund-Feed bekommen statt „kein Feed"
  die handlungsfähige „Upload empfohlen"-Meldung (max-feed-mb erhöhen ODER Upload). Quellen-
  Recherche (Zensus/Eurostat/13111/Pendler/MiD 2023/BASt/MOBIDROM/Dortmund) als neuer Abschnitt
  „Verifizierte Bezugsquellen" in docs/02. **Verifiziert:** neuer `PopulationGridParserTest`
  (6 Tests, u. a. Langform-ID ≙ `x_mp`-Punkt derselben Zelle, Kurzform ≙ Langform,
  Geheimhaltungs-Skip, Regressions-Dialekte); Suite 43/43, `ng build` grün.

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
- **Rad multimodal (2026-07-06):** neben PKW+Lieferverkehr speist SUMO jetzt auch die **Rad-Trips**
  der Personen-Nachfrage ein (kanonischer `mainMode==0`=BICYCLE, `vType="fahrrad"` vClass
  bicycle, emissionsfrei); Netz behält Radkanten (`--keep-edges.by-vclass passenger,bicycle`).
  `Result.bikeTrips` + `summary.json` + Studio-Zeile „Zusammensetzung" (PKW/Rad/Lieferverkehr).
- **Fuß multimodal (2026-07-06):** Fußgänger sind in SUMO **keine Fahrzeuge**, sondern
  `<person>` mit `<personTrip>` (kein `<trip>`) — eigener Schreibpfad (`persons.xml`), Netz um
  Gehwege/Querungen erweitert (`--sidewalks.guess --crossings.guess`). `duarouter --mapmatch.distance`
  snappt die Geo-Koordinaten wie bei Fahrzeugen; Fahrzeuge und Personen werden in **einem**
  duarouter-Lauf geroutet. Kanonischer `mainMode==3`=PEDESTRIAN; kein CO₂.
- **ÖV/Bus via GTFS — Phase C (✅ 2026-07-07):** GTFS-Fahrplan → `gtfs2pt.py` → Bus-Haltestellen/-Fahrten;
  Nachfrage-Personen mit `mainMode==4` (PUBLICTRANSPORT) fahren **intermodal** mit
  (`modes="public"` → Fußweg → `<ride>` → Fußweg). Pipeline: **`GtfsCropper`** schneidet einen
  Verbund-Feed streaming auf Region+Zeitfenster zu (verwirft `shapes.txt`, wahrt referenzielle
  Integrität; KVV-Feed 121 MB → 720 KB) → **`GtfsSource`** löst die Quelle auf: **Auto-Fetch** aus
  einer Feed-Registry (Bbox-Abdeckung, z. B. KVV/NVBW für Rastatt; Größen-Cap) mit
  **Upload-Fallback** (`POST /api/scenarios/{id}/gtfs`), falls kein Feed passt oder er zu groß ist
  → **`TransitRunner`** ruft `gtfs2pt.py` in einem **gekapselten Python-venv** (pandas/rtree/pyproj;
  einzige Python-Abhängigkeit im Projekt, Stage wird SKIPPED wenn das venv fehlt) und **cached** das
  Ergebnis. **Wichtiger Praxis-Befund:** `gtfs2pt.py` findet ohne kompilierte Mapping-Bibliothek
  (Java-Jar, 16-GB-Heap) keinen schnellen Mapper und fällt auf den langsamen `tracemapper` zurück
  (~20–25 Min. für ein Zeitfenster) — deshalb Zuschnitt auf ein konfigurierbares Zeitfenster
  (Default 6–10 Uhr) statt den ganzen Tag, plus Cache. **Bug gefunden+gefixt:** `gtfs2pt.py`
  schreibt den `<vType id="bus">` nur in eine Zwischendatei, nicht in die Fahrzeug-Ausgabe →
  SUMO „vehicle type 'bus' is not known"; `TransitRunner.writeVtypes` stellt die vTypes jetzt
  separat bereit. Verifiziert per `PtIntegrationTest` (voller Kettenlauf, gated auf
  `SYNTHESIS_TRANSIT_VENV`, da langsam): 141 Haltestellen, 498 Busse, Fahrgäste real geroutet.
  Konfiguration `SynthesisProperties.Transit` (venv/Feed-Registry/Zeitfenster/Größen-Cap/Timeout).
  Nur **Bus** (das SUMO-Netz ist road-only).
- **ÖV-Emissionen (✅ 2026-07-07):** Bus-vType bekommt `emissionClass="HBEFA3/Bus"` (gegen SUMO 1.26
  validiert) → CO₂ je Bus wird automatisch erfasst wie bei Pkw/Lkw; Studio-Panel + Szenario-Vergleich
  zeigen CO₂ jetzt als Pkw/Lieferverkehr/Bus.
- **Rail/Tram versucht, zurückgerollt (2026-07-07) — ehrlicher Technik-Befund:** die Rastatt-Region
  hat neben Bus (1532 GTFS-Fahrten) auch Tram (995) und Rail (903) — implementiert war der
  railway-OSM-Fetch (`way["railway"~"rail|tram|light_rail|subway"]`, GraphHopper ignoriert es),
  `netconvert --keep-edges.by-vclass +rail,rail_urban,tram --railway.topology.repair` (baute
  869 Rail-Kanten) und `gtfs2pt --modes bus,train,tram`. **Blocker:** `duarouter --mapmatch.distance`
  snappt Personen-Koordinaten auf die **nächste Kante ohne vClass-Filter** — nahe Bahnhöfen sind das
  die Rail-Kanten, auf denen Fußgänger nicht laufen können → „No destination edge for walk or
  personTrip" bricht den Lauf ab; die Alternative `--mapmatch.junctions` verwirft die betroffenen
  Personen **kommentarlos** (0 geroutet), kein echter Fix. Es gibt kein `--mapmatch.vclass` in
  duarouter. **Lösungsweg für einen späteren Anlauf:** explizites, vClass-gefiltertes
  Edge-Snapping in Java (Netz selbst einlesen, je Trip die nächste fußgänger-/pkw-fähige Kante
  bestimmen, `<personTrip from=EDGE to=EDGE>` statt `fromLonLat`) — eigenes Teilprojekt. Alle
  Rail-Änderungen wurden zurückgerollt, Bus-ÖV bleibt solide (Test-Suite 18/18 grün).
- *Offen/optional:* Rail/Tram (s. Blocker oben); ÖV-Fahrgäste im Netz-Feedback berücksichtigen
  (bisher nur Pkw); Lauf-Projekte als feste Abhängigkeit/Container (siehe Phase 4).

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
- **✅ Sampling-Vertrag und Routing-Provenienz korrigiert (2026-07-27):**
  `samplingFraction` wird bereits in `PopulationGenerator` auf die synthetisierte/exportierte
  Bevölkerung angewandt; logiTopp verarbeitet diesen Export jetzt mit
  `fractionOfPopulation: 1.0` statt denselben Anteil ein zweites Mal zu ziehen. Im Drei-Seed-Lauf
  wurden damit 98,7–98,8 % der exportierten Personen als mobile Ergebnis-Personen beobachtet.
  Für reproduzierbare Offline-Läufe kann `synthesis.overpass.road-network-file` ein lokales
  OSM-XML nur für den schweren Routing-Abruf vorgeben. Das Manifest meldet die tatsächliche
  Matrizenquelle (`graphhopper-with-local-fallbacks` statt des vorher statischen
  `beeline-estimate`) und erläutert die lokalen Heuristiken. **Verifiziert:**
  `SimulationRunnerTest`, `OverpassClientTest` (lokales OSM→GraphHopper→Route) und
  `GenerationPipelineE2ETest` (beide Manifest-Varianten); sechs echte logiTopp-Läufe.
- **✅ Szenario-API validiert Konfigurationsgrenzen (2026-07-27):** Create/Update weisen
  fehlende Teilkonfigurationen, nicht-endliche oder außerhalb der Erdkoordinaten liegende
  Regionen, unbekannte Zonierungs-/Verhaltens-/Zielmodellwerte und Stichprobenanteile außerhalb
  `(0, 1]` als HTTP 400 ab, statt später mit einem 500er oder stillschweigendem Template-Fallback
  zu reagieren. **Verifiziert:** `ScenarioControllerTest` deckt die Fehlerklassen mit vier
  Negativtests ab; Offline-Suite 50/50 grün (6 umgebungsabhängige Tests übersprungen).
- **✅ Verhaltensquellen-Vertrag bereinigt (2026-07-27):** Die wirkungslose
  `useActitopp`-Checkbox wurde aus dem Studio entfernt; `behaviorSource` ist nun die einzige
  sichtbare Steuerung und bietet `actitopp`, `gta` sowie `template` explizit an. Persistierte
  Altszenarien ohne `behaviorSource` werden einmalig aus dem Legacy-Flag migriert; das veraltete
  Feld bleibt nur noch write-only für die Deserialisierung und wird nicht erneut ausgegeben.
  **Verifiziert:** `ScenarioRepositoryTest` deckt Migration und Vorrang des neuen Felds ab;
  Offline-Suite 46/46 grün (6 umgebungsabhängige Tests übersprungen), Angular-Build mit Node 20
  erfolgreich. Der optionale Angular-Disk-Cache bleibt deaktiviert, da dessen natives
  `lmdb`-Modul auf dem aktuellen macOS reproduzierbar mit `SIGABRT` abstürzt.
- **✅ Szenario-/Test-Härtung (2026-07-27):** Szenario-IDs werden im Repository auf ein
  pfadsicheres Format begrenzt; damit können Artefakt-, Upload- und Löschpfade das
  Szenario-Verzeichnis nicht mehr verlassen. Die Offline-E2E-Tests deaktivieren die externe
  logiTopp-Simulation explizit, beenden ihren Executor und räumen Temp-Daten auf. Der
  GTFS-„kein Feed"-Test verwendet eine leere Feed-Registry statt versehentlich einen Download zu
  starten; das Studio-Polling bleibt an der beim Start erfassten Szenario-ID. Frontend verlangt
  Node 20 (`.nvmrc`), weil Node 26 mit Angular 18/esbuild abstürzte. Nach der Migration auf
  Angular 21 gelten über `engines` die offiziell unterstützten Linien 20.19+, 22.12+ und 24.
  **Verifiziert:** Backend-Suite 44 Tests grün (6 externe Skips), `npx ng build` grün mit
  Node 20.20.2.
- **✅ Frontend-Sicherheits- und Testtooling aktualisiert (2026-08-04):** Angular wurde gemäß
  offiziellem Migrationspfad einzeln von 18 über 19 und 20 auf **21.2.19** angehoben; der
  schlankere Builder aus `@angular/build` ersetzt `@angular-devkit/build-angular`. Angulars
  nativer Vitest-Runner ersetzt Jest samt Kompatibilitätsschicht. Der Produktions-Audit sank von
  acht hohen Treffern auf null; im reinen Entwicklungs-Tooling verbleiben fünf Upstream-Treffer
  (vier moderat, einer hoch), deren betroffene Versionen Angular CLI/Build derzeit exakt pinnt.
  **Verifiziert:** 3/3 Frontend-Tests, Produktions-AOT-Build (305,57 kB initial) und
  GitLab-CI-Vertrag mit `npm run audit:prod` + `npm test` + `npm run build`.
- **✅ Szenariovergleich gegen Layout-Regressionen abgesichert (2026-08-04):** Die
  Gruppenzeilen verwenden nun die tatsächlich gerenderte Spaltenzahl; ohne KPI-Referenz entsteht
  keine unsichtbare zusätzliche MiD-Spalte mehr. API-Fehler werden sichtbar gemeldet und löschen
  veraltete Vergleichsergebnisse, statt sie als aktuellen Stand stehen zu lassen. Während einer
  Anfrage verhindert ein sichtbarer Ladezustand parallele Doppelaufrufe; Statusmeldungen werden
  über `aria-live` angekündigt. Das Backend setzt `simulated=true` nur noch für eine parsebare
  `simulation/kpis.json`; eine beschädigte Datei wird nicht als gültiges Ergebnis ausgegeben.
  Der Frontend-Vertrag für Parameter, KPIs, Depot- und SUMO-Ergebnisse ist vollständig typisiert;
  `strictTemplates` prüft den Vergleich damit ohne `any`-Ausnahme.
  Fixture-freie Komponententests rendern den Vergleich mit und ohne MiD-Spalte sowie die
  Studio-Shell inklusive Leaflet-Karte. **Verifiziert:** `ComparisonControllerTest`,
  Angular/Vitest-Suite 11/11 grün und Produktions-AOT-Build.
- **✅ Studio-Fehlerzustände sichtbar gemacht (2026-08-04):** Fehler beim Laden von
  Szenarioliste, Defaults, Upload-Status, Artefakten oder laufendem Job-Polling werden sichtbar
  gemeldet. Ein gescheitertes Löschen setzt den Busy-Zustand zurück und behält die Auswahl;
  Polling stoppt beim ersten Statusfehler statt den Backend-Aufruf endlos zu wiederholen.
  Save-Validierung, Polling-Abbruch und Timer-Cleanup beim Verlassen des Studios sind als
  Interaktionstests abgesichert. Auch das Entfernen von Bevölkerungsraster, Straßennetz,
  Pendlermatrix und Arbeitsplatz-Randwert meldet API-Fehler sichtbar und setzt den jeweiligen
  Busy-Zustand zurück. **Verifiziert:** fixture-freie Angular/Vitest-Suite 20/20 grün
  und Produktions-AOT-Build.
- **✅ Reproduzierbarer Laufzeitvertrag, erste Stufe (2026-08-11):**
  `runtime/versions.env` und `runtime/kit-sources.env` pinnen Toolversionen sowie fünf
  KIT-Upstream-Commits; `scripts/bootstrap-runtime.sh` rekonstruiert die gitignorierten
  Laufprojekte und das Transit-venv. Versionierte logiTopp-/actiTopp-Patches besitzen
  Marker- und Hash-Verträge. `scripts/preflight.sh` erkennt unvollständige oder
  abweichende Installationen, `scripts/dev.sh` startet den lokalen Stack und
  `scripts/verify.sh full` prüft actiTopp, SUMO, GTFS/PT und Ollama. Ein manueller
  GitLab-Job adressiert dafür self-hosted Runner. **Offen:** vollständiger
  logiTopp-Golden-Lauf im Volltest, vorkonfiguriertes Runner-/Container-Image,
  self-hosted Overpass oder gebündelter GraphHopper-PBF-Extrakt sowie abschließende
  GPL-Arm's-length-Dokumentation.

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
  - **TEC- und Governance-Formate analysiert (2026-07-16,** aus dem IML/Takomat-Austausch
    2026-07-10: `MC_CityConfig_TEC.xlsx` Arbeitsstand 04–05/2026 + `GovernanceConfig
    ME0400_B8_OnDemandShuttle.xlsx`**):**
    - **TECCarFleet** = private Pkw-Flotte **je District × Jahr** (2016–2025):
      `registrations.total/.shareElectric`, `stock.total/.shareElectric`; Zeilen sind
      `districtID`s in derselben NUTS3-artigen Systematik wie REGdistricts (`DE122-01` …).
      Arbeitsstand mit Lücken („values under revision", 0 = fehlend).
    - **TECTransit** = Klasse `TECStartValues`, study-area-weit je Jahr (`bus/train.stock.total`,
      `seatsPerVehicle`, `kmPerVehiclePerYear`, `capacityUtilisation`) **plus**
      `seasonTicketRate` je District **mit `default`-Zeile** — d. h. District-Tabellen müssen
      NICHT vollständig sein, ein Default-Fallback ist vorgesehen (Granularität je District vs.
      study-area-weit wird laut Sheet-Kommentaren bei Takomat/KIT noch diskutiert).
    - **TECEmissions** = Well-to-Wheel-Emissionsfaktoren gCO₂/vkm je Jahr (Car/Bus/Train ×
      Verbrenner/Elektro, Pedelec) + E-Anteile + Bus/Bahn-pkm-Split — Literatur-/Bestandswerte,
      nicht aus unserer Pipeline zu erzeugen (unsere SUMO-/HBEFA-CO₂-KPIs taugen aber als
      Plausibilisierung).
    - **Governance/Maßnahmen (ME_xx)** = die Spielelemente des Serious Game: Master-Typologie
      (CGET, z. B. `B` = Bauen/Investieren), je Maßnahme ME_XX_00 (Typ) + ME_XX_NN (Variationen).
      Kern-Attribute: `planningTime`/`buildingTime`/`lifespan`, `investment`/`maintenance`,
      **`effectClimate`/`effectLivability`/`effectFinance` als −3..+3-Scores**,
      `shareCar/Bike/Transit` (Kontenzuordnung), `acceptance*` (±50 Siegpunkte), `min/maxAmount`,
      `parameterFile` (Instanz-CSV) — plus maßnahmen-spezifische Attribute (Beispiel On-Demand-
      Shuttle: `times`, `vehicleType`, `drive`, `accessability`) und DE/EN-TEXT-Tabellen
      (Motivation/Description/Specifics). Status-Workflow: 0 = offen, 1 = KIT definiert,
      2 = Takomat implementiert. Referenzbeispiel: 10 KVV.MyShuttle-Bediengebiete (BEV
      „London Taxi", Abend-/Nachtbetrieb).
    - **Konsequenzen für uns (Folge-Items):** (a) **✅ TECCarFleet-Beitrag umgesetzt
      (2026-08-04):** `MobileCityExporter` liefert `stock.total` je District direkt aus der
      synthetischen Population (`car.csv` × `household.homeZone`) als 6. MobileCity-Exporttabelle
      im EpigeneCSV-Stil;
      `registrations`/`shareElectric` bewusst NICHT (keine Neuzulassungs-/Antriebsmix-
      Modellierung) → Takomat-Defaults. `MobileCityExporterTest` emuliert den Fremdparser und
      prüft Tabellenname, Basisjahr und District-Zählung; die Offline-E2E-Pipeline fordert das
      Artefakt. (b) **Maßnahmen-Evidenz-Export (Konzept):** die
      ±3-Wirkungs-Scores sind grob — unser Szenario-Vergleich liefert die quantitative
      Grundlage je Maßnahme (Basis- vs. Maßnahmen-Szenario → Δ CO₂ aus SUMO/HBEFA,
      Δ Modal-Split, Δ km), aus der KIT/Takomat Scores und `parameterFile`-Inhalte ableiten
      können — das ist die konkrete Andockstelle unserer Zwei-Achsen-Vergleiche an MobileCity.
      (c) On-Demand-Shuttle als eigener Modus ist im Legacy-logiTopp-Stack nicht abbildbar
      (kein Ride-Pooling) → kein kurzfristiges Pipeline-Ziel, allenfalls fern SUMO-DRT.
- **KIT:** actitoppNG-Lizenz, logiTopp-Migrationsstand, ggf. MiD-/Zensus-Datenzugang.
- **GTA-Autoren (Leipzig/ScaDS.AI):** Kooperation, Persona-/MiD-Datengrundlage.

### Wiederinbetriebnahme auf neuem Laptop — ✅ KERN + ÖV + LLM (2026-08-11)

- **Kern bestätigt:** Backend-Suite zunächst 124 Tests ohne Fehler; nach Ergänzung der
  actiTopp-Vertrags- und Integrationstests bestand die Vollabnahme 126/126 ohne Skip.
  Frontend 20/20 Tests und Angular-Produktionsbuild sind grün. Der kleine
  `GenerationPipelineE2ETest` lief 4/4 einschließlich lokalem Legacy/logiTopp-Pfad.
- **SUMO 1.27.1 kompatibel:** Der versionsunabhängige macOS-Framework-Pfad und die
  PATH-Erkennung ersetzen den fest verdrahteten 1.26.0-Pfad. Die native SUMO-Kette bestand
  mit 1.27.1; 1.26 bleibt der historische Reproduktions-Pin für publizierte Altwerte.
- **ÖV vollständig bestätigt:** Python 3.14 mit pandas 3.0.5, rtree 1.4.1 und pyproj 3.7.2;
  `PtIntegrationTest` lief KVV-GTFS → `gtfs2pt.py` → SUMO mit ÖV-Fahrzeugen und
  intermodalen `<ride>`-Etappen zuletzt in 143,9 s. Dabei wurde ein echter Portierungsfehler behoben:
  relative `venv-python`-/`gtfs2pt`-Pfade werden vor dem Wechsel ins Artefaktverzeichnis
  absolut aufgelöst.
- **LLM vollständig bestätigt:** Ollama 0.32.8 und `qwen2.5:7b-instruct` (4,7 GB) laufen
  auf dem Apple M5 über Metal; 29/29 Modellschichten werden auf die GPU ausgelagert. Der
  Terminal-Smoke-Test antwortete korrekt, anschließend bestanden `GtaBehaviorTest` und
  `LlmModeChoiceTest` zusammen 6/6 Live-Tests ohne Skip in 72 s. Wichtig für Agentenläufe:
  Ollama außerhalb der verwalteten Codex-Sandbox starten und den laufenden Server per HTTP
  verwenden. Nur ein innerhalb der Sandbox gestarteter GPU-Prozess scheiterte mit
  `failed to create command queue`; dies ist kein M5-/Ollama-Laufzeitblocker des Projekts.

**Aktuelle Priorität:** (P0) den neuen reproduzierbaren Laufzeitvertrag um einen vollständigen
logiTopp-Golden-Lauf, ein vorkonfiguriertes Runner-/Container-Image und ein lokales PBF ergänzen;
(P1) Distanz-/Moduskalibrierung über weitere Regionen und Seeds validieren; (P2)
Maßnahmen-Evidenz- und INSPIRE-Exporte erst nach Takomat/KIT-Abstimmung.

### Empfohlene Reihenfolge
**0 ✅ → 1 ✅ → (2 ✅ ∥ 3 ✅) → 4**, Phase 5 durchgehend parallel. Phasen 0–3 vollständig umgesetzt
(3a–3d): Zwei-Achsen-Gerüst, On-demand-Kalibrierung, SUMO-Zielmodell inkl. Lieferverkehr,
Emissions-/Netzlast-KPIs und Nachfrage↔Netz-Feedback (alle ✅), GTA-Verhaltensquelle (lokales LLM,
Cache, Reproduzierbarkeit), LLM-Moduswahl, Was-wäre-wenn-Szenarien mit Agenten-Begründungen.
Offen in **Phase 4**: vollständiger logiTopp-Golden-Lauf, Runner-/Container-Image,
self-hosted Overpass beziehungsweise lokales PBF und GPL-Abschlussprüfung. Die gepinnten
Laufprojekte, reproduzierbaren Patch-Verträge, Vorprüfung und Full-Runtime-CI sind umgesetzt.
Optionale nächste Bausteine aus der TEC-/Governance-Analyse (2026-07-16, s. Phase 5):
**Maßnahmen-Evidenz-Export** (Konzept: Szenario-Deltas als quantitative
Grundlage für ME-Wirkungs-Scores — vor Umsetzung mit Takomat/KIT abstimmen). Aus der
INSPIRE-Schiene weiter offen: Zensus-Direktzuordnung (Rasterzelle→INSPIRE-Zone ohne Re-Gridding)
und INSPIRE-Zell-IDs als MobileCity-`districtId` statt `SYN-NNN`.

---

## G. Konzept: Retourenlogistik mit logiTopp — ✅ MVP TECHNISCH ABGENOMMEN (2026-07-29)

### G.1 Ziel und fachliche Abgrenzung

SyntheSis soll zusätzlich zur Vorwärtslogistik die **Rücksendung eines Pakets vom privaten
Empfänger in Richtung Verteilzentrum** abbilden. Im ersten Schritt ist eine Retoure ein
eigenständiger Abholauftrag in derselben Simulationswoche; sie ist noch nicht kausal mit einer
konkreten vorherigen Bestellung verknüpft.

Die Ergebnisbegriffe bleiben strikt getrennt:

| Begriff | Bedeutung | logiTopp-Abbildung |
|---|---|---|
| **Zustellung** | Paket vom Verteilzentrum zum Empfänger | `IsPickup=false` |
| **Kundenretoure** | Rücksendung vom Empfänger zum Verteilzentrum | `IsPickup=true` |
| **Zustell-Rückläufer** | Zustellung scheitert und das unveränderte Paket fährt zurück zum Depot | Paketstatus `RETURNING` |
| **Packstationsabholung** | Empfänger holt eine eingehende Sendung an der Packstation ab | Personenaktivität `PICK_UP_PARCEL`; keine Retoure |

Diese Trennung ist eine harte Invariante: Weder `RETURNING` noch `PICK_UP_PARCEL` dürfen in
Retouren-KPIs gezählt oder in der UI als „Retoure" bezeichnet werden.

### G.2 Empfohlener MVP

Der MVP modelliert Retouren als **exogene, szenariogesteuerte zusätzliche Abholnachfrage**:

1. Das bestehende Zustellmodell erzeugt zunächst unverändert `N` private Zustellaufträge. Aus
   dieser Zahl wird mit `shareOfPrivateOrders` deterministisch ein zusätzliches Retourenvolumen
   `R` gezogen; die Gesamtmenge ist `N + R`. Aktivierte Retouren dürfen die Zahl der
   Zustellaufträge nicht reduzieren.
2. Für jede gezogene Retoure erzeugt der private Paket-Demand-Builder einen Auftrag mit
   `IsPickup=true`. Abholquelle ist im MVP `HOME` oder `WORK`; Ziel ist das gewählte
   Verteilzentrum.
3. logiTopp plant Zustellungen und Abholungen gemeinsam auf Touren. Dadurch entstehen die
   zusätzlichen Stopps und Fahrzeugbewegungen endogen aus der Tourenplanung.
4. Die Ergebnis-Ingestion wertet Paketstatus und Vehicle-Events richtungsgetrennt aus.

**Bewusste Einschränkung:** `shareOfPrivateOrders` ist im MVP eine Szenarioannahme, keine
behauptete empirische Rücksendequote. SyntheSis kennt derzeit weder Warengruppe noch Händler,
Retourengrund oder ein konkretes Ursprungspaket. Ein voreingestellter Branchenwert würde eine
nicht vorhandene Kalibrierung vortäuschen; deshalb bleiben Feature und Quote standardmäßig aus.

### G.3 Szenario- und Konfigurationsvertrag

Zielstruktur im Szenario:

```yaml
logistics:
  returns:
    enabled: false
    model: exogenous-share
    shareOfPrivateOrders: 0.0
    pickupShares:
      home: 1.0
      work: 0.0
```

Regeln:

- `enabled=false` und `shareOfPrivateOrders=0.0` sind die rückwärtskompatiblen Defaults.
- Bei aktiviertem Feature gilt `0 < shareOfPrivateOrders <= 1`.
- `pickupShares.home + pickupShares.work = 1`; negative oder nicht-endliche Werte müssen von
  der Szenario-API mit HTTP 400 abgelehnt werden.
- Jede Ziehung hängt deterministisch an `Scenario.population.seed`; kein ungesetzter
  Zufallszahlengenerator.
- `PACKSTATION` ist im MVP **kein Retourenkanal**, weil eine Abgabe durch den Kunden einen
  zusätzlichen Personenweg und eine konsolidierte Abholung an der Station erfordert.

Das Frontend zeigt den Block nur bei Zielmodell `logitopp` und kennzeichnet ihn als
experimentell. Nach Aktivierung sind Quote sowie Abholortanteile editierbar; ein Hilfetext
erklärt die Abgrenzung zu fehlgeschlagenen Zustellungen. Wird logiTopp abgewählt, deaktiviert
das Studio die Retourenoption, damit kein wirkungsloser Zustand gespeichert wird.

### G.4 Technische Integration

Die Erweiterung bleibt innerhalb der bestehenden Architektur und erzeugt keine 15. Pipeline-Stufe:

| Bestehender Ort | Änderung | Stand |
|---|---|---|
| `Scenario.Logistics` + Frontend-Modell | verschachtelte Retourenkonfiguration und Validierung ergänzen | ✅ API + Studio, Default aus |
| `LogisticsGenerator` | `parcel-demand-config.yaml` um Richtung/Retourenparameter erweitern | ✅ Vertrag + sicherer Off-Default |
| `SimulationRunner` | Pfad bzw. Werte in `run-config.yaml` an den externen Lauf-Harness übergeben | ✅ Pfad + Harness-Vertrag |
| `mobitopp-rastatt`-Harness | Paketparameter aus der Run-Config lesen; Delivery- und zusätzliches Pickup-Modell zusammensetzen | ✅ versionierter Patch |
| logiTopp | private Pickups nach tatsächlicher Position clustern und über `DistributionCenter.requestPickup(...)` registrieren | ✅ versionierter Patch |
| Ergebnis-Ingestion | `IsPickup`, `to_pickup`, `success_pickup` und `collected` richtungsgetrennt aggregieren | ✅ KPI-Vertrag + Postconditions |
| SUMO-Adapter | Abholstopps übernehmen und das abschließende Leg zurück zum Depot ergänzen | ✅ geschlossen, ohne Doppel-Leg |

Die Konfigurationskette
`scenario.json → parcel-demand-config.yaml/run-config.yaml → mobitopp-rastatt → logiTopp`
ist damit vollständig wirksam. Alte Szenarien ohne `returns`-Block werden beim Lesen auf den
ausgeschalteten Default normalisiert. Die API lehnt unbekannte Modelle, nicht endliche oder
außerhalb `[0,1]` liegende Quoten, Abholanteile ungleich 1 sowie aktivierte Retouren ohne
Zielmodell logiTopp mit HTTP 400 ab.

Die Run-Config enthält dafür eine explizite Vertragsversion. Nach einem Lauf prüft die Ingestion
als Laufzeit-Postcondition eindeutige und richtungskonsistente Paket-IDs. Bei aktivierten
Retouren muss die Pickup-Zahl je Empfänger innerhalb der durch `floor`/`ceil` definierten
stochastischen Rundungsbandbreite liegen; erfolgreiche Stop-Pickups, Depot-Unloads und
eindeutige dort angekommene Pickup-Pakete müssen übereinstimmen. Bei deaktivierten Retouren
darf kein Pickup-Ergebnis entstehen. Eine alte Harness-Version wird bereits am Vertragsmarker
abgewiesen und kann das neue Feld nicht unbemerkt ignorieren.

Die Prozessgrenze zum GPL-lizenzierten Legacy-Stack bleibt bestehen. Weil
`validation/mobitopp-rastatt` gitignored ist, liegt der reproduzierbare Patch-/Build-Vertrag
unter `legacy/logitopp-returns-v1/`. Er prüft Upstream-, Patch-, Vorher- und Nachher-Hashes,
publiziert eine eindeutige Maven-Version und hinterlegt den vom Backend geprüften Harness-Marker.
Ein festes Lauf-Image kann später denselben Vertrag konsumieren.

### G.5 Artefakte und KPIs

`kpis.json` erhält einen versionierten, additiven Block:

```json
{
  "logistics": {
    "contract": "synthesis-logistics-kpis-v1",
    "deliveries": {
      "generated": 0,
      "delivered": 0,
      "failedReturning": 0,
      "deliveryRate": 0.0
    },
    "customerReturns": {
      "generated": 0,
      "plannedPickups": 0,
      "collected": 0,
      "collectionRate": 0.0
    }
  }
}
```

Weitere Ergebnisanforderungen:

- `parcel-states.csv` bleibt Rohbeleg; eindeutige Paket-IDs mit `IsPickup=true` werden gezählt,
  nicht die mehrfachen Zustandszeilen.
- `vehicle_events.csv` liefert geplante und erfolgreiche Abholungen pro Stopp; `collected` und
  `returning` werden nur bei `event=unload` gezählt, weil sie an Stopps kumulativ sind.
- Tour-Features in `tours.geojson` aggregieren additiv `deliveries`, `pickups` und
  `failedDeliveries`; bestehende Konsumenten bleiben kompatibel.
- Studio und Szenariovergleich zeigen **Kundenretouren**, **Abholerfolg** und
  **Zustell-Rückläufer** getrennt; alte `kpis.json` fallen auf die bisherigen Paketzeilen zurück.
- Zusätzliche Fahrzeugkilometer oder CO₂ werden zunächst nur als
  **Szenario-Differenz Basis→Retoure** berichtet. Eine direkte Zuordnung gemeinsamer
  Tourkilometer zu einzelnen Retouren wäre ohne Allokationsregel Scheingenauigkeit.

### G.6 Beweis- und Akzeptanzplan

Vor der Produktfreigabe ist ein kleiner Ende-zu-Ende-Spike über die vollständige externe Kette
Pflicht:

1. Minimales Szenario mit `returns.enabled=true` und bewusst hoher Quote.
2. Echter logiTopp-Lauf erzeugt mindestens einen Paketstatus mit `IsPickup=true`.
3. Mindestens ein Tour-Event weist `to_pickup > 0` und `success_pickup > 0` aus.
4. Das abgeholte Paket erreicht den Depot-/`collected`-Pfad; kein Zustell-Rückläufer wird als
   Kundenretoure gezählt.
5. `planned-tours.csv` und der SUMO-Import werden darauf geprüft, dass der Abholstopp in der
   gefahrenen Tour erhalten bleibt.

**Spike-Ergebnis Schritt 1 (✅ Mechanik bedingt bewiesen, 2026-07-29):** Der unveränderte Stack erzeugte
101 Pickups und sammelte sie scheinbar ein, gruppierte aber 13–20 verschiedene Zielzonen eines
Tages fast vollständig am ersten Stopp. Nach Clusterung über tatsächliche Zone/Position trat eine
zweite Ursache offen zutage: Pickups lagen im normalen Depotbestand und wurden auf räumlich
getrennten Touren doppelt geplant. Mit Positionsclusterung und Registrierung über
`requestPickup(...)` endete der echte externe Lauf mit Exit 0: **101 Pickups, davon 87 innerhalb
des Wochenhorizonts geplant und eingesammelt, `to_pickup=87`, `success_pickup=87`,
`collected=87`, keine Doppelplanung und kein Positionsfehler**. Die übrigen 14 blieben offen;
`returning=0`.

Ein realer Pickup-Stopp wurde anschließend in SUMO geroutet (**ein Lkw-Leg, 6,81 km, 430 s,
1,61 kg CO₂; Test Exit 0**). Der Beweis deckte zugleich eine Exportgrenze auf:
`planned-tours.csv` enthält kein abschließendes Leg vom letzten Stopp zurück zum Depot. Der
interne logiTopp-Pfad erreichte `collected`, SUMO bildete die vollständige Rückfahrt und ihre
Emissionen in diesem historischen Spike aber noch nicht ab. Die reproduzierbaren lokalen Belege liegen unter
`validation/returns-spike-2026-07-29/`.

**Implementierungsnachweis Schritt 2 (✅ Konfigurationskette, 2026-07-29):** Der
Patch-/Build-Vertrag `synthesis-logitopp-returns-v1` wurde installiert, erneut idempotent
ausgeführt und der Harness kompiliert. Ein echter Mischlauf mit
`shareOfPrivateOrders=1.0` erzeugte **101 Zustellungen plus 101 Retouren** für 96 Agenten.
**65 Zustellungen und 67 Pickups** wurden innerhalb des Wochenhorizonts geplant und erfolgreich
ausgeführt; alle 67 Pickup-IDs waren eindeutig und lagen an der Paket-Zielkoordinate. Beim
Depot-Unload wurden 67 eingesammelte Pakete gezählt, `returning=0`. Anschließend routete SUMO
den realen Pickup als Hin- und Abschluss-Leg: **2 Lkw-Fahrzeuge, 3,03 kg CO₂, Test Exit 0**.
Damit sind Positionsclusterung, Pickup-Bestand und geschlossene Tour an der echten
Prozesskette nachgewiesen.

**Implementierungsnachweis Schritt 3 (✅ MVP-Konfiguration, 2026-07-29):** Der verschachtelte
Szenariovertrag wird mit `enabled=false`, Quote `0.0` und HOME/WORK `1.0/0.0` sicher
initialisiert; alte Szenarien ohne `returns`-Block behalten diese Defaults. Controller-Tests
belegen HTTP 400 für ungültige Modelle, Quoten, Abholortanteile und aktivierte Retouren ohne
logiTopp. Generator-Tests belegen den Durchstich von `Scenario.Logistics` bis
`parcel-demand-config.yaml`; die weitere externe Kette ist durch Schritt 2 belegt. Die
Offline-Backend-Suite lief mit **82 Tests, 0 Fehlern und 6 umgebungsabhängigen Skips**, der
Angular-AOT-Build war grün. Die Browserprüfung bestätigte:
Retouren sind zunächst aus, die Detailfelder erscheinen erst nach Aktivierung, das Abwählen von
logiTopp blendet den Block aus und setzt `enabled` zurück; es gab keine Konsolenfehler. Der
echte externe Prozessbeleg bleibt der unter Schritt 2 dokumentierte Mischlauf.

**Implementierungsnachweis Schritt 4 (✅ Ingestion/KPIs/UI, 2026-07-29):**
`SimulationResultIngestorTest` lief mit sechs Tests einschließlich der read-only Auswertung des
echten Schritt-2-Mischlaufs: **101 Zustellaufträge / 65 zugestellt, 101 Kundenretouren /
67 geplant und eingesammelt, 0 Zustell-Rückläufer**. Separate Negativtests beweisen
Fail-fast bei Pickups trotz deaktivierter Retouren sowie bei Verletzung der Empfänger-genauen
Ziehregel. Die vollständige Offline-Backend-Suite lief mit **86 Tests, 0 Fehlern und
6 umgebungsabhängigen Skips**, der Angular-AOT-Build erzeugte 2,17 MB. Eine isoliert geseedete
Browserprüfung bestätigte die Richtungstrennung in Studio und Szenariovergleich sowie die
horizontal nutzbare Depot-Tabelle; keine Konsolenfehler.

**Implementierungsnachweis Schritt 5 (✅ vollständiger A/B-E2E, 2026-07-29):**
Zwei ansonsten identische Szenarien mit 100 Personen und Seed `4242` liefen über die reale
Pipeline einschließlich externem logiTopp und SUMO mit Exit 0. Die Baseline erzeugte
**86 Zustellungen / 86 zugestellt, 0 Kundenretouren und 72 Touren**; das Retourenszenario
erzeugte weiterhin **86 Zustellungen / 86 zugestellt** plus **86 Kundenretouren /
86 geplant / 86 eingesammelt** und 124 Touren. Der Personenverkehr blieb mit 2.137 Wegen
identisch. In SUMO stieg der Lieferverkehr von **23 auf 47 Legs**, das gesamte CO₂ von
**48,04 auf 59,06 kg** und das Lieferverkehrs-CO₂ von **10,19 auf 21,19 kg**.

Der Lauf deckte drei Integrationsfehler auf und schloss sie: `destinationShares` steht nun
korrekt unter `parcelOrders`; Tourwerte zählen kommaseparierte Paket-IDs statt sie numerisch zu
parsen; Zielanteile behalten im Harness über eine `LinkedHashMap` eine feste Reihenfolge.
Danach waren zwei getrennte Prozessläufe in allen fachlichen logiTopp-Rohartefakten
byte-identisch. Zwei Wiederholungen des Retourenszenarios erzeugten außerdem identische
Retouren-IDs, Touren, `kpis.json` und – abgesehen von der Laufzeit – SUMO-Kennzahlen.
Studio und Vergleich zeigten genau diese A/B-Werte richtungsgetrennt; die Browser-Konsole
enthielt weder Fehler noch Warnungen.

Akzeptanzkriterien für die Implementierung:

- **Baseline-Invariante:** Bei `enabled=false` bleiben Paketanzahl, Richtungen und fachliche KPIs
  für denselben Seed unverändert; `IsPickup` bleibt vollständig `false`.
- **Determinismus:** Zwei Läufe mit identischem Szenario und Seed erzeugen dieselben
  Retouren-Paket-IDs und Kennzahlen.
- **Mengentreue:** Die erzeugte Retourenzahl entspricht der dokumentierten Rundungs-/Ziehregel
  für `shareOfPrivateOrders`; die ursprüngliche Zahl der Zustellaufträge bleibt dabei erhalten.
- **Semantische Trennung:** Tests decken `customerReturns`, `failedReturning` und
  Packstationsabholung als drei verschiedene Fälle ab.
- **API-Validierung:** Ungültige Quoten und Abholortanteile ergeben HTTP 400; aktivierte
  Retouren mit inkompatiblem Harness schlagen sichtbar fehl und werden nie still als reine
  Zustellung simuliert.
- **Gates:** Backend-Testsuite, Angular-AOT-Build und ein echter kleiner logiTopp-E2E-Lauf sind
  grün; die Verifikation wird mit Datum und konkreten Zählwerten in diesem Abschnitt ergänzt.

Konfigurations-, Validierungs-, Aggregations- und Determinismustests laufen offline in der
Standardsuite. Der externe logiTopp-E2E-Test darf nach dem bestehenden Fixture-/`assumeTrue`-
Muster umgebungsabhängig überspringen; ein Skip gilt aber ausdrücklich **nicht** als
Ende-zu-Ende-Beleg. Vor der Beförderung muss der Test tatsächlich mit Exit 0 gelaufen sein und
die beobachteten Zustell-, Pickup- und `collected`-Zahlen werden dokumentiert.

### G.7 Ausbau nach dem MVP

1. **Packstation als Rückgabekanal:** eigene Semantik `DROP_OFF_RETURN`, Personenweg zur
   Packstation und konsolidierter Pickup durch den KEP-Dienst; nicht durch Umbenennen von
   `PICK_UP_PARCEL`.
2. **Kausaler Lebenszyklus:** `sourceParcelId`, Zustellerhalt → Rücksendeentscheidung →
   Verzögerungsverteilung; dafür sind mehrwöchige Läufe oder Zustandsübergabe zwischen Läufen
   erforderlich.
3. **Segmentiertes Retourenmodell:** Quoten nach Warengruppe, Haushaltstyp, Zustellkanal und
   Dienstleister – erst nach verfügbarer und dokumentierter Datengrundlage.
4. **Händler-/Senkenmodell:** Rückführung nicht nur zum Depot, sondern zu Händler,
   Konsolidierungszentrum oder Wiederaufbereitung.
5. **Kalibrierung:** beobachtete Abholmengen, Retourenquote und Rücklaufzeiten als Referenzen;
   bis dahin bleiben Ergebnisse ausdrücklich Szenario- und keine Prognosewerte.

Diese Punkte werden nicht gesammelt in den technisch abgenommenen MVP gezogen. Ihre nächsten
Evidenz-Gates sind:

| Ausbau | Nächster belastbarer Schritt | Voraussetzung für Implementierungs-Go |
|---|---|---|
| Packstation-Rückgabe | Ereignis- und Wegekonzept für Kundendrop-off plus konsolidierte KEP-Abholung präregistrieren | Personenweg und Paketbestand dürfen nicht als `PICK_UP_PARCEL` umetikettiert werden |
| Kausaler Lebenszyklus | Zustandsübergabe über mehr als eine Simulationswoche als kleinen Spike beweisen | stabile `sourceParcelId` und reproduzierbare Rücksendeverzögerung |
| Segmentierung | erste dokumentierte Quotenmatrix mit Stichprobengröße/Quelle beschaffen | belastbare Warengruppen- oder Haushaltstypdaten |
| Händler-/Senkenmodell | Zielknoten und Transportketten fachlich festlegen | Quelle für Händler-, Konsolidierungs- oder Wiederaufbereitungsziele |
| Kalibrierung | beobachtete Abholmengen und Rücklaufzeiten als Referenzdatensatz definieren | vorab festgelegte Fehlermaße und Holdout-Prüfung |

Bis diese Voraussetzungen erfüllt sind, ist ein Ausbau-No-Go eine bewusste
Modellierungsentscheidung, kein technischer Restfehler des MVP.

### G.8 Umsetzungsschnitt

Empfohlene Reihenfolge:

1. **✅ Spike (2026-07-29):** Pickup-Mechanik, Ergebnisdateien und Pickup-Stopp in SUMO bewiesen;
   zwei notwendige logiTopp-Korrekturen und das fehlende SUMO-Abschluss-Leg identifiziert.
2. **✅ Konfigurationskette (2026-07-29):** Paketparameter und Retourenwerte im Harness wirksam;
   versionierter, hashgeprüfter Patchvertrag einschließlich Positionsclusterung,
   `requestPickup(...)`, Fail-fast-Marker und geschlossenem SUMO-Tourexport.
3. **✅ MVP hinter `returns.enabled=false` (2026-07-29):** verschachtelter Backend-/JSON-Vertrag,
   HTTP-400-Validierung, logiTopp-gegateter Studio-Block und YAML-Durchstich; keine
   unkalibrierte Vorgabequote.
4. **✅ Ingestion/KPIs/UI (2026-07-29):** versionierter Richtungsvertrag, Laufzeit-
   Postconditions, additive Depot-/Dichte-/Tourwerte sowie Darstellung in Studio und Vergleich.
5. **✅ E2E-Verifikation und Beförderungsentscheidung (2026-07-29):** seed-identischer
   Baseline-/Retourenlauf über logiTopp und SUMO, Wiederholungsnachweis sowie Browser-Abnahme;
   technisches Go für den experimentellen MVP, Default bleibt aus.

**Go/No-Go nach Schritt 5:** technisches Go für den experimentellen MVP; Produktiv-Default und
Prognoseeinsatz bleiben No-Go, weil eine empirisch kalibrierte Quote und Rücklaufzeit fehlen.
Die Ausbaustufen aus G.7 beginnen jeweils erst nach ihrem eigenen Evidenz-Gate.

## H. Native Verkehrsnachfrage-Schicht — ✅ UMGESETZT (2026-07-30)

### H.1 Übernommenes Konzept und Abgrenzung

Aus dem FleetPy-Ansatz werden die **konzeptionellen** Bausteine einer klaren
Nachfrage-Prozessgrenze, reproduzierbarer Einzelwege und zeitlich-räumlicher Aggregate
übernommen. Die Implementierung bindet weder FleetPy ein noch erzeugt sie dessen
Request-Dateiformat. Sie ist ein nativer, simulatorunabhängiger SyntheSis-Vertrag zwischen
simulierter logiTopp-Nachfrage und den nachgelagerten Auswertungs-/Zielmodellen.

Referenzen für den konzeptionellen Ausgangspunkt:
[TUM-VT/FleetPy](https://github.com/TUM-VT/FleetPy) und
[FleetPy-Wiki](https://github.com/TUM-VT/FleetPy/wiki).

### H.2 Modell- und Parservertrag

`TravelDemand` bildet vollständige Personenwege und ihre geordneten Legs ab. Der einzige
Legacy-Adapter `LogitoppDemandAdapter` löst alle Spalten über Namen statt Positionen auf und
setzt folgende Semantik durch:

- Wegschlüssel ist `(personOid, tripId)`;
- der kanonische Hauptmodus kommt aus `mainMode`; die potenziell intermodale
  `tripMode`-Sequenz bleibt als Raw-Wert erhalten;
- geplante Startzeit und Ursprungszone kommen aus `legId=0`, geplante Ankunft und Zielzone aus
  dem letzten Leg;
- die Aktivitätskoordinaten `fromX…toY` bilden nur die Hülle des Gesamtwegs; mobiTopp 0.3.580
  exportiert keine Leg-Geometrien;
- Wegdistanz ist die Summe aller Leg-Distanzen; je Leg bleiben Modus, Quell-/Zielzone und
  Distanz erhalten, aber keine vorgetäuschten Leg-Koordinaten oder Leg-Abfahrtszeiten;
- Abfahrten liegen im Wochenhorizont, Ankünfte dürfen bei einem am Sonntag begonnenen Weg
  höchstens einen Tag darüber hinausreichen;
- fehlende Pflichtspalten, kurze Zeilen, doppelte/lückenhafte Legs, negative Distanzen/Zeiten
  oder nicht-endliche Werte führen zu einem Fehler mit Datei, Zeile, Feld und Wegschlüssel;
- die `mainMode`-Codes 0–4 werden auf Rad/Pkw/Mitfahrer/Fuß/ÖV abgebildet; unbekannte Codes
  bleiben für Audits erhalten und tragen den expliziten Modus `other`.

Damit berechnen KPI-Ingestion, Distanz- und Modus-Distanz-Kalibrierung, LLM-Modusauswertung
und SUMO dieselbe Wegdistanz. Die vier festen SUMO-Spaltenparser und der separate
Nahdistanz-Parser entfallen.

### H.3 Artefakte und Aggregate

Jeder erfolgreiche Ingest schreibt atomar und deterministisch:

| Artefakt | Inhalt |
|---|---|
| `demand/metadata.json` | Contract, geplante Zeit-/Zonenbasis, SHA-256 des Raw-Inputs, Mengen und Moduszähler |
| `demand/person-trips.csv` | ein vollständiger Weg je Zeile; kanonischer `mainMode` plus rohe `tripMode`-Sequenz |
| `demand/legs.csv` | erhaltene Leg-Modi, Quell-/Zielzonen und Distanzen; keine im Raw-Export nicht vorhandene Leg-Geometrie |
| `demand/zone-flows.csv` | Abfahrten und Ankünfte je `Zone.id`, Modus und 15-Minuten-Bin |
| `demand/od-flows.csv` | gerichtete sparse OD-Nachfrage je `Zone.id`, Modus und 15-Minuten-Bin |

Die Bins sind halboffen (`[Start, Start+15 min)`) und verwenden Minuten seit Montag 00:00;
Wochentage werden nicht übereinandergelegt. OD-Flüsse und Zonenabfahrten werden nach
geplanter Abfahrtszeit, Zonenankünfte nach geplanter Ankunftszeit gebinnt. Für jeden Lauf
gelten die Bilanzen
`Quellzeilen = Legs`, `Wege = Summe OD`, `Wege = Summe Abfahrten = Summe Ankünfte`.
Alle Zähler repräsentieren die simulierte Stichprobe mit realisierten Modus- und
Zielentscheidungen; die Zeitstempel sind keine Messwerte. Eine spätere Hochrechnung braucht
einen eigenen versionierten Vertrag und erfolgt nicht implizit.

### H.4 SUMO-Selektion

SUMO filtert das gemeinsame Modell einmal nach Tag und Modus. Das bestehende Limit bleibt
zunächst je Modus bestehen. Wird es überschritten, zieht SyntheSis deterministisch die
Mittelpunkte gleich breiter empirischer Zeitquantile; dadurch bleibt der beobachtete
Tagesverlauf proportional vertreten, statt nur die frühesten Wege zu übernehmen. Innerhalb
gleicher Abfahrtszeit löst ein stabiler Weg-Hash die Auswahl von Personen-/Zonenreihenfolgen.
Jeder Personenweg wird genau einer Kategorie zugeordnet:

`an duarouter übergeben + anderer Tag + nicht abbildbarer Modus/fehlendes ÖV-Netz + Modus-Cap`.

Mitfahrer werden weiterhin nicht als zusätzliche Fahrzeuge emittiert, weil die
Fahrer-Mitfahrer-Verknüpfung im Raw-Vertrag fehlt. Diese fachliche Grenze ist nun sichtbar
bilanziert statt still übersprungen. Die Bilanz endet bewusst vor dem Routing:
Map-Matching-Drops durch `duarouter --ignore-errors` werden noch nicht separat ausgewiesen.

## I. Amtliche Arbeitsplatz-Randwerte — ✅ UMGESETZT (2026-08-03)

Der Regionalstatistik-Flat-File `13111-08-02-5-B` ergänzt den BA-Auspendleradapter um die
fehlende Marginale. `WorkplaceMargins` liest UTF-8 und ISO-8859-1, akzeptiert ausschließlich
Statistik `13111` sowie `ERW033`/`PEN092`, wählt den neuesten vollständigen Stichtag und
aggregiert über die per BKG-VG250-Punkt in der Szenarioregion liegenden Gemeinden. Persistiert
wird atomar der normalisierte Szenariovertrag `uploads/workplace-margins.csv`; die Live-API ist
bewusst keine Laufzeitabhängigkeit.

Im Modell entscheidet der Randwert `PEN092/ERW033` über innen/außen. Erst danach verteilt die
externe BA-OD das Ziel. Getrennte, stabile Personen-Substreams verhindern Rückwirkungen auf
Demografie und Haushaltszufälle. Ein Randwert ohne externe OD wird sichtbar nicht angewendet;
ein fehlender Randwert erhält den bisherigen Gravitationspfad.

Für Rastatt (30.06.2025: 13.723/22.971 = 59,7405 %) bestanden Parser-, Upload-, Zufallsstrom-
und Pipeline-E2E-Tests sowie ein vorregistrierter Drei-Seed-Lauf. Die Außenanteile lagen bei
59,64–60,44 %, Demografie/Pkw waren bytegleich. Gegen BA-only stieg Ø Weglänge 4,57→8,10 km,
`≥20 km` 3,13→10,86 % und der Distanz-RMSE sank 0,1035→0,0829. Die Modal-RMSE stieg
0,0747→0,0943; deshalb ist die Randwertintegration befördert, eine produktive Langweg-
Moduskalibrierung und die Übertragung auf weitere Regionen jedoch noch offen.
Die abschließenden Repository-Gates bestanden mit 117 Backend-Tests ohne Fehler sowie einem
erfolgreichen Angular-Produktionsbuild.
