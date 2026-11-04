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

### A.8 Austauschbare Datenquellen und Fallbacks — erster experimenteller Schnitt (2026-10-28)

Die [Bestandsanalyse und der Architekturvorschlag](22-datenquellen-adapter-und-fallbackkonzept.md)
ordnen die heutigen Quellen ihren tatsächlichen Nachfrage-/Angebotsbeiträgen zu und
unterscheiden globale Formate, nationale Datenbestände, regionale Quellen sowie
übertragene Modellprofile. Vorgeschlagen sind Quelladapter und Auswahlregeln je
fachlichem Aspekt, mit expliziter Teilabdeckung, Herkunft und Ersatzannahmen. Für
Schwerlastverkehr gibt es zunächst einen expliziten Mengenannahmenpfad;
fehlende Quellen dürfen nicht als beobachteter Nullverkehr erscheinen.
Der [erste experimentelle Umsetzungsschnitt](22-datenquellen-adapter-und-fallbackkonzept.md#19-erste-experimentelle-umsetzung-und-verifikation)
ist standardmäßig ausgeschaltet. Er umfasst gemeinsame Reader und Feldzuordnung,
unveränderliche Importrevisionen, drei Fachverträge und einen Studioarbeitsbereich.
Das vollständige Quelleninventar und weitere Providerprofile bleiben im verlinkten
Konzept beschrieben; die übrigen Bestandsquellen sind noch nicht migriert.

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
                Verhaltensquelle                       Nachfrage / Weiterverarbeitung
   Region  ──►  ( erzeugt Aktivitäten )         ──►   ( simuliert, exportiert, belastet Netze )
                ┌───────────────────────────┐         ┌───────────────────────────────┐
                │ actiTopp (kalibriert) ★    │         │ logiTopp (Personen + Pakete) ★ │
                │ LLM-Agenten (experimentell)│         │ SUMO (nachgelagert zu logiTopp)│
                │ [Template = interner       │         │ MobileCity-Export (Takomat,    │
                │  Fallback, nicht sichtbar] │         │  strategisch-aggregiert)       │
                └───────────────────────────┘         └───────────────────────────────┘
                          ★ = Default
```

**Achse A – Verhaltensquelle** (erzeugt `activity.csv` + Modus-/Zielwahl):
| Option | Art | Nutzer-sichtbar |
|---|---|---|
| **actiTopp** | echtes KIT-Modell, kalibriert, deterministisch | ja (Default) |
| **LLM-Agenten** (intern `gta`) | LLM-Persona-Agenten, Was-wäre-wenn, erklärbar, stochastisch | ja (experimentell) |
| Template | einfacher Regelgenerator | nein (nur Fallback, wenn actiTopp-Prozess fehlt) |

**Nachfragesimulation und Weiterverarbeitung:**
| Option | Granularität | Rolle |
|---|---|---|
| **logiTopp/mobiTopp** | mikroskopisch, Personen **+ Pakete** | heutiger Default; liefert KPIs/Touren |
| **SUMO** | mikroskopisch, Verkehrsfluss | nachgelagerte Detailsimulation der logiTopp-Nachfrage; OSM→`netconvert`, Pläne→`.rou.xml`; EPL, lokal, CPU; **unabhängig von Takomat** |
| **MobileCity-Export** | strategisch, district-aggregiert, mehrjährig | Takomat-Zielmodell; wir liefern Aggregat-Tabellen |

**Designprinzipien:**
- Die Verhaltensquelle ist unabhängig von den Ausgaben wählbar. MobileCity ist heute ein
  unabhängiger Aggregat-Export; SUMO besitzt dagegen eine harte logiTopp-Abhängigkeit, die Studio
  und API automatisch ergänzen.
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
  **✅ Schreibpfad ergänzt (2026-08-20):** Bis hierhin konnte ein Szenario einen Snapshot
  nur *entgegennehmen*; erzeugt hat ihn nichts, weshalb `inputProvenance` in allen
  Kampagnenläufen leer blieb und ein Live-Abruf unbelegt war. `OsmDataSnapshot.write`
  serialisiert die geholten POIs und Siedlungen nun wieder als Overpass-`elements`
  (je Koordinate zusammengeführt, damit ein Knoten mit POI- *und* `place`-Tag beim
  Zurücklesen nicht doppelt zählt); jeder Live-Lauf legt sie als
  `artifacts/osm-data.json` ab und trägt den SHA-256 ins Manifest. Damit ist der
  Einfrier-Pfad erstmals benutzbar, ohne den Snapshot von Hand zu beschaffen.
  **Verifiziert:** Round-Trip, Doppelzähl-Fall und Fingerabdruck-Unterscheidung als
  Tests; live erzeugt ein Lauf `osm-data.json` (auch im degradierten Fall: POIs per
  HTTP 500 gescheitert, 18 Siedlungen geladen — genau der Lauf, der bisher unbelegt
  blieb), und derselbe Snapshot in ein zweites Szenario kopiert liefert
  `scenario-upload:osm-data.json` mit identischem OSM-Hash **und identischer
  `attractivities.csv`**. Backend 138 Tests, 0 Fehler, 7 umgebungsbedingte Skips.
  **✅ Straßennetz gleichgezogen (2026-08-20):** Dieselbe Asymmetrie bestand beim
  OSM-Straßennetz und war dort gefährlicher — der Upload-Pfad wurde geprüft und gehasht,
  ein Overpass-Abruf lebte dagegen nur im Speicher des Laufs. Ausgerechnet dieser Abruf
  fällt bei Zeitüberschreitung still auf Luftlinien-Matrizen zurück; genau diese
  Kontamination (Ø 2,0 km, Fuß 51,9 %) hat historisch eine ganze Kampagne entwertet.
  `RoadNetworkFile.persist` sichert das Netz jetzt als `artifacts/road-network.osm`,
  und `inputProvenance` trägt neben `osmData` und `attractivities` auch `roadNetwork`
  mit Quelle und SHA-256.
  **Verifiziert:** Zwei Tests (Schreiben inkl. Verzeichnisanlage mit
  fingerabdruck-gleicher Nachprüfung; Wiederverwendbarkeit des gesicherten Netzes als
  Szenario-Upload samt Regionsdeckung). Live: Ein Lauf mit `useRealRouting=true`
  schreibt 3,3 MB `road-network.osm` und meldet den Hash; beide Dateien in ein zweites
  Szenario kopiert liefern `scenario-upload:road-network.osm@922630532e0b` — alle drei
  Provenienz-Hashes in beiden Armen identisch, GraphHopper-Routing statt Luftlinie.
  **✅ Vergleich prüft die Herkunft (2026-08-21):** Die Provenienz stand im Manifest,
  geprüft hat sie niemand — die Regel „vor jeder Auswertung Hashes vergleichen" blieb
  Handarbeit und wurde in der Seed-43-Auswertung übersehen. Der `ComparisonController`
  liefert `inputProvenance` je Eintrag, und der Szenario-Vergleich wertet sie aus:
  Weichen OSM-Daten, Straßennetz oder Attraktivitäten ab, erscheint eine sichtbare
  Warnung samt Benennung der betroffenen Eingaben. Bewusst zurückhaltend: Gemeldet
  wird nur, wenn **mindestens zwei belegte** Hashes verschieden sind; fehlende
  Herkunft (Läufe vor der Einführung) erscheint als eigener, schwächerer Hinweis
  „nicht belegt" statt als Gleichheit.
  **Verifiziert:** Drei Frontend-Tests (Abweichung → `role="alert"` mit benannter
  Eingabe; gleiche Eingaben → keine Warnung; fehlende Herkunft → schwacher Hinweis),
  Frontend 24/24. Live im Browser gegen echte Läufe alle drei Fälle bestätigt — beim
  Paar mit verschiedenen Regionen nennt die Warnung korrekt OSM-Daten und
  Attraktivitäten, aber **nicht** das Straßennetz, weil einer der Läufe ohne
  Realrouting lief und dort kein Hash existiert. Für die historisch konfundierten
  Seed-43-Arme meldet die UI „nicht belegt".
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
  **❌ Pkw-Split-Sonde verworfen (2026-08-17):** Commit `f337233` trennte vorab
  ausschließlich `b_tt_car_d`/`b_tt_car_p` vom produktiven `+0,06`-Stand. Der
  byteidentisch reproduzierte Kontrollarm PC-0060 und die sign-erhaltenden Dosen
  PC-0090/PC-0115 verschieben `>=10 km` monoton **14,00→20,98→33,85 %** und
  Ø Weglänge 5,38→6,38→8,60 km. Der Sechs-Band-RMSE verschlechtert sich jedoch
  **0,0644→0,0675→0,0729**; `1–2 km` kollabiert 11,90→7,89→4,61 %.
  Obwohl `b_tt_put=-0,0306` unverändert bleibt, steigt der realisierte ÖV-Anteil
  24,86→29,29→37,83 %: Die Zielwahl öffnet Fernziele, die separate Moduswahl
  anschließend überproportional per ÖV bedient. PC-0115 verfehlt zusätzlich den
  Modal-Schutz (RMSE-Δ +0,053129). **Entscheidung:** Pkw-Split und weitere
  Modus-Teilmengen-Scans ohne neuen Mechanismus sind gestoppt; keine weiteren Seeds.
  Nächster zulässiger Spike ist eine präregistrierte Multi-Band-Sonde (`b_1_2`
  auf PC-0090 plus separater Modal-Schutz) oder eine regional/hierarchisch
  geschätzte Parametrisierung. Vollständiger Befund:
  [docs/05-validierung.md](05-validierung.md).
  **⛔ Multi-Band-Parameter im Legacy-Vertrag blockiert (2026-08-17):** Die in
  Commit `1ae34ad` präregistrierte `b_1_2 +0,50`-Sonde blieb trotz korrekt
  gepatchter Zweckdateien vollständig wirkungslos; MB-000, MB-050 und PC-0090
  sind im Demand-CSV byteidentisch (42.990 Wege). Die Forensik zeigt eine
  Einheitenfehlverdrahtung: Das generierte Modell prüft `1 < DISTANCE <= 2`,
  `DefaultDestinationChoiceHelper` liefert aber die in `CAR_DIS.mtx` gebundene
  Distanz unverändert in Metern. Der kleinste Freiburg-Wert ist 411,918 m;
  dadurch sind `b_0_1` und `b_1_2` faktisch tot. MB-075 wurde nach Stop-Regel
  nicht gestartet. **Lösungsweg:** keine Direktänderung unter `validation/`,
  sondern eine versionierte, experimentell aktivierbare Harness-Korrektur
  `DISTANCE / 1000` mit neu präregistrierten Baselines; die bestehenden
  Kurzband-Terme würden dadurch erstmals wirksam.
  **❌ Kilometer-Vertragsspike vor Produkt gestoppt (2026-08-17):** Commit
  `3382e17` fixierte zwei Binär-Gates vorab. In einer isolierten Harness-Kopie
  bestätigt `DISTANCE / 1000` die Root Cause: KM-000 unterscheidet sich von
  PC-0090 und verschiebt `1–2 km` 7,89→9,67 %, RMSE 0,0675→0,0636. Ein
  zusätzlicher `b_1_2 +0,50`-Term wirkt nun ebenfalls und erreicht 12,09 %
  sowie RMSE 0,0581; gegenüber KM-000 sind das aber nur **+2,42 pp** statt des
  vorab geforderten Minimums +3 pp. Beide Läufe halten `>=10 km` über 20 %,
  der ÖV-Anteil bleibt mit 29,05/28,21 % jedoch über der 26-%-Diagnose.
  **Entscheidung:** Einheitenerklärung bestätigt, Promotionshypothese verworfen;
  kein `+0,75`, keine Seeds, kein opt-in Produktvertrag. Die produktive Runtime
  und `validation/` bleiben unverändert.
  **❌ Joint-Komposition am Langende gestoppt (2026-08-17):** Commit `7bbcc2d`
  leitete vorab aus KM-000/KM-050 genau `b_1_2 +0,895` ab und koppelte den
  bestehenden ASC-Modalkalibrator als Schutz an. Der End-to-End-Harness bestand
  mit EXIT 0 (1 Test, 158,1 s): `1–2 km` trifft 14,12/14,08 %, Modal-RMSE sinkt
  0,113275→0,017562 und ÖV 27,42→10,70 %. Die Modalkalibrierung lässt die
  Distanzbänder praktisch invariant. **Blocker:** `>=10 km` bleibt bei
  19,69/19,68 % statt mindestens 20 %, der Distanzband-RMSE bei
  0,055140/0,055292 statt höchstens 0,05; insbesondere `>20 km` erreicht nur
  3,73/3,70 % gegen MiD 13 %. **Entscheidung:** keine weitere Dosis, Seeds oder
  Produktimplementierung. Der nächste Versuch braucht einen separat
  präregistrierten Langwegmechanismus; Runtime, Defaults und `validation/`
  bleiben unverändert.
  **❌ Direkter Langweg-Nutzenbonus unterdosiert (2026-08-17):** Commit
  `a949eb2` leitete aus J-RAW ohne Raster `+1,35` Nutzenpunkte für jede frei
  gewählte Zielalternative über 20 km ab. Der isolierte End-to-End-Harness lief
  mit EXIT 0 und verbessert `>20 km` **3,73→7,17 %**, `>=10 km`
  19,69→22,06 % sowie Distanzband-RMSE 0,055140→0,042661. Kurzband und
  Wegefrequenz bleiben in ihren Schutzfenstern. Das feste Mechanik-Gate
  scheitert trotzdem doppelt: nur +3,44 statt mindestens +5 pp und 7,17 statt
  `[10; 16] %`. **Root Cause der Fehlprognose:**
  `FixedLocationDestinationChoice` umgeht die gepatchte Utility für HOME- und
  personengebundene Fixziele; eine Gesamtmengen-Log-Odds-Dosis überschätzt
  deshalb den Effekt auf alle Wege. **Entscheidung:** Der Modalkalibrator wurde
  nicht gestartet; keine zweite Dosis, Seeds oder Produktänderung. Ein neuer
  Versuch muss den Anteil freier Zielentscheidungen zuerst messen und bedingt
  modellieren, statt den Bonus nachträglich hochzuskalieren.
  **✅ Freizielanteil verhaltensneutral gemessen (2026-08-17):** Commit
  `c2b283b` fixierte vorab ein Erklärungsfenster von `[30;45] %`. Der isolierte
  FD-000-Harness zählt bei byteidentischem J-RAW-Demand exakt 42.989
  Zielentscheidungen: HOME 18.722 (43,55 %), Fixziele 5.129 (11,93 %) und
  freie Utility-Pfade 19.138 (**44,52 %**); der externe Lauf endet mit EXIT 0,
  das Log bleibt frei von Fallback und Exception. Damit ist der Bypass als
  wesentlicher Verdünnungsmechanismus gestützt, aber nicht als vollständige
  Dosisformel: Die Quote liegt 7,41 pp über dem LT-135-Wirkungsverhältnis
  37,11 %. **Entscheidung:** kein Bonus-Retry. Vor einer neuen Dosis muss ein
  eigener Spike Entscheidungsweg und tatsächlich gewählte Distanz direkt
  verknüpfen; Produktcode und `validation/` bleiben unverändert.
  **❌ Cross-run-Wegepaarung unentscheidbar (2026-08-17):** Commit `1b8ec51`
  präregistrierte PX-135 als einmalige read-only Paarung von J-RAW und LT-135
  über `(tripId, legId)`. Die Hashes bleiben unverändert, beide Schlüsselräume
  sind eindeutig und 42.988 von 42.989 J-RAW-Zeilen finden numerisch ein
  Gegenstück. Das entscheidende Semantik-Gate scheitert jedoch hart: Nur
  15.982 Paare (**37,18 %**) behalten Ziel- und Vorgängeraktivitätscode statt
  mindestens 99,9 %. `tripId` ist damit zwischen kontrafaktischen Läufen kein
  stabiler Wegschlüssel; Gruppenkennzahlen werden nicht interpretiert und kein
  alternativer Post-hoc-Join versucht. **Lösungsweg:** Entscheidungsweg und
  gewählte Distanz im selben Lauf protokollieren oder vorab einen stabilen
  semantischen Schlüssel ausgeben. Keine neue Dosis oder Produktänderung.
  **❌ Expositionsmarginale lokalisiert, Wirkungsstärke zu klein (2026-08-17):**
  Commit `a08eb7a` übernahm für PM-135 unverändert die PX-135-Gates und verbot
  jeden Join. Die separate Same-run-Aggregation ist technisch gültig:
  35.389/42.989 J-RAW-Wege (**82,32 %**) liegen direkt oder als Folgeweg neben
  freien Aktivitäten. Sämtliche 1.480 zusätzlichen `>20 km`-Wege entstehen in
  dieser Marginale; 7.600 nicht exponierte Wege bleiben in Langanteil
  (7,8158 %) und Ø Distanz (8,6272 km) exakt unverändert. Damit rekonstruiert
  `82,32 % × 4,1823 pp` den globalen Zuwachs +3,4429 pp. **Gate dennoch
  verfehlt:** exponiert steigt `>20 km` nur 2,854→7,036 %, also +4,182 statt
  mindestens +5 pp. Wirkungskette lokalisiert, hinreichende Dosisformel
  verworfen; nächster Spike muss die `>20 km`-Eligibility der freien
  Choice-Sets messen, nicht den Bonus erhöhen.
  **❌ Binäre Choice-Set-Ineligibility widerlegt (2026-08-17):** Commit
  `f8064f6` präregistrierte CE-000/CE-135 als verhaltensneutrale Same-run-
  Messung. Beide Demand-Hashes reproduzieren J-RAW beziehungsweise LT-135
  byteidentisch; alle technischen Gates bestehen. Entgegen dem erwarteten
  Fenster `[65;85] %` enthalten **100 %** der 19.138 freien Entscheidungen
  mindestens eine attraktive `>20 km`-Alternative mit positiver
  MNL-Wahrscheinlichkeit. Die Wahrscheinlichkeitsmessung erklärt die
  Unterwirkung dennoch: CE-000 misst 2,3376 % Langwegmasse, der exakte
  je-Choice-Set-`+1,35`-Kontrafakt 6,6643 % und CE-135 tatsächlich 7,0603 %;
  die reale Auswahl liegt bei 2,3722/6,8868 %. Damit scheitert nicht die
  Verfügbarkeit, sondern die homogene Transformation eines aggregierten
  Anteils. Besonders `SHOPPING_OTHER` ist trotz 100 % Eligibility mit nur
  0,0156 % Basismasse praktisch fernzielblind. **Entscheidung:** keine neue
  Dosis oder Produktänderung; als Nächstes darf nur die Verteilung der
  per-Choice-Set-Basismasse vorab klassifiziert werden.
  **✅ Breites Within-Zweck-Niedrigmassenregime bestätigt (2026-08-17):** Der
  separat präregistrierte PD-000-Lauf ist erneut byteidentisch zu J-RAW und
  besteht alle technischen Gates. 60,3511 % der freien Entscheidungen haben
  weniger als 1 % Fernzielmasse und tragen zusammen nur 3,7926 % der
  Gesamtmasse; 4,3369 % der Entscheidungen mit mindestens 10 % tragen dagegen
  42,5757 %. Die Varianzzerlegung lokalisiert **93,2080 % innerhalb** und nur
  6,7920 % zwischen Aktivitätstypen. Besonders SHOPPING_DAILY kombiniert
  95,76 % Niedrigmassenfälle mit seltenen Hochmassenfällen. **Entscheidung:**
  Ein konstanter Zweckbonus ist keine hinreichende Strukturerklärung. Keine
  Dosis oder Produktänderung; als nächster Same-run-Kandidat darf nur die
  Ausgangszonenkomponente innerhalb der Zwecke zerlegt werden.
  **❌ Ausgangsgeografie nicht dominant (2026-08-17):** OZ-000 reproduziert
  PD-/J-RAW erneut byteidentisch und zerlegt ausschließlich die 1.293
  `(Zweck, Ausgangszone)`-Zellen mit mindestens fünf Beobachtungen. Die
  Abdeckung 14.748/19.138 = 77,0613 % besteht den technischen Gate, doch
  Ausgangszellen erklären nur **20,0786 % statt mindestens 70 %** der
  Within-Zweck-Varianz; 79,9214 % bleiben innerhalb identischer Zellen. Bei
  SHOPPING_DAILY, dem größten Varianztreiber, sind es nur 9,54 %.
  **Entscheidung:** keine geografische Folgesonde oder Dosis. Die nächste
  zulässige Same-run-Diagnose muss Personen-/Modusverfügbarkeit oder andere
  situative Merkmale innerhalb gleicher Zweck-Zonen-Zellen prüfen.
  **⛔ Exakte Modusset-Zerlegung zu dünn besetzt (2026-08-24):** AV-000
  reproduziert Utility, J-RAW-Demand und sämtliche CE-/PD-/OZ-Aggregate exakt;
  der externe Lauf endet mit EXIT 0. Die vollständige
  `ModeAvailabilityInDestinationChoice`-Signatur erzeugt jedoch 6.459
  Unterzellen, davon 2.529 Singletons. Die vorab zugelassenen `n>=5`-Zellen
  decken nur **8.618/19.138 = 45,0308 %** statt mindestens 60 % ab. Damit ist
  der Lauf vor jeder Mechanikinterpretation unentscheidbar; der rohe
  Varianzanteil der Teilmenge wird nicht gewertet. **Entscheidung:** Schwelle
  nicht nachträglich senken, keine Dosis. Der nächste Same-run-Spike muss
  Verfügbarkeitskomponenten gröber und vorab getrennt definieren.
  **❌ Einzelne Modusverfügbarkeiten nicht dominant (2026-08-24):** AC-000
  zerlegt alle sieben Komponenten separat und reproduziert sämtliche
  bisherigen Aggregate exakt. Jede Komponente besteht sowohl 60 %
  Entscheidungs- als auch 60 % Restvarianzabdeckung. Keine erreicht jedoch
  den vorab geforderten Erklärungsanteil von 50 %; `CAR` ist mit nur
  **4,3735 %** das Maximum, alle anderen liegen zwischen 1,0205 % und
  2,1291 %. Überlappende Anteile werden nicht addiert. **Entscheidung:** keine
  Dosis oder Produktänderung; als nächster Same-run-Spike dürfen nur die in
  der Utility verwendeten Personenmerkmale vorab zerlegt werden.
  **⛔ Personenkomponenten wegen Altersband teilweise unentscheidbar
  (2026-08-24):** PC-000 reproduziert alle bisherigen Aggregate exakt. Sechs
  binäre Utility-Kodierungen bestehen beide Abdeckungsgates und erklären
  einzeln höchstens 6,8995 %. Das vierstufige `AGE_BAND` deckt jedoch nur
  **50,9928 % statt mindestens 60 %** der Entscheidungen ab; sein roher Anteil
  wird nicht gewertet. **Entscheidung:** Die sechs binären Kodierungen sind
  nicht dominant, die Alterskodierung bleibt offen. Keine Dosis; nächster
  Same-run-Spike nur mit den drei von der Utility getrennt verwendeten binären
  Altersindikatoren.
  **❌ Altersindikatoren nicht dominant (2026-08-24):** AI-000 macht alle drei
  Indikatoren technisch interpretierbar. `AGE_0_17` erklärt maximal 5,5282 %,
  `AGE_50_69` 1,5368 % und `AGE_70_120` 0,7127 % statt mindestens 50 %.
  **Entscheidung:** keine weitere Personen-/Verfügbarkeitssonde und keine
  Dosis; neue Mechanismusforensik ist erforderlich.
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
  **Abgelöst am 2026-08-26:** Wegen des großen Sprungs von 1 km auf 10 km bei mehr als 600
  INSPIRE-Zellen starten Neuanlagen wieder im frei auflösbaren `grid`-Modus. INSPIRE bleibt als
  bewusst wählbares amtliches Standardraster erhalten.
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
  einer Feed-Registry (Bbox-Abdeckung, z. B. KVV/NVBW für Rastatt; Größen-Cap); ein über
  `POST /api/scenarios/{id}/gtfs` bewusst ausgewählter Upload hat Vorrang
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
- **✅ Globale Tool-Einstellungen (2026-10-22):** Ein Zahnrad rechts in der Kopfzeile
  öffnet `/einstellungen`: SUMO-Binärverzeichnis/SUMO_HOME, OpenAI-kompatibler LLM-Endpunkt,
  Modellname, Temperatur und Anfragebudget sowie actiTopp-/logiTopp-Adapter, Transit-Python/
  `gtfs2pt.py` und MobileCity-Python/.NET/ConfigTool mit Zeitlimits und globalen Freigaben.
  Diese Betriebswerte gehören zur Installation; Szenarioziele, Seeds und fachliche Parameter
  bleiben im Studio. Die Modellauswahl erfolgt über den exakten Namen eines bereits beim
  LLM-Dienst verfügbaren Modells; Installation, API-Key-Verwaltung und Modellerkennung sind
  nicht Teil dieses Bereichs. Der separate Unternehmenstouren-Assistent behält seine eigene
  `synthesis.company-tour-import`-Konfiguration.
- **Persistenz und Aktivierung:** `GET/PUT /api/settings/tools` liefert gespeicherte und aktive
  Werte plus `restartRequired`. Ein begrenztes, validiertes DTO wird atomar unter
  `<synthesis.data-dir>/tool-settings.json` geschrieben (standardmäßig
  `backend/data/tool-settings.json`). Änderungen werden ausschließlich beim Backend-Neustart
  nach Spring-Property-Binding und vor Übergabe an Konsumenten geladen; laufende und bis dahin
  gestartete Jobs verwenden die aktive Konfiguration. Die gespeicherten Felder haben Vorrang
  vor YAML/Umgebungswerten, andere Betriebs-Properties bleiben erhalten. Zum Rücksetzen auf
  die YAML-/Umgebungswerte die Datei bei gestopptem Backend sichern/entfernen und neu starten.
  Eine beschädigte Datei stoppt den Start mit Dateipfad im Fehler, statt still andere Werkzeuge
  zu verwenden. Pfade gelten auf dem Backend-Rechner; Verfügbarkeit wird weiterhin durch die
  jeweiligen Runner geprüft, Speichern installiert oder startet kein Tool.
- **Verifiziert:** `ToolSettingsTest` (5 Tests: atomare Persistenz, unveränderte aktive Werte,
  echtes Spring-Binding beim Neustart, HTTP-Validierung ohne Überschreiben, Schreibfehler/
  Cleanup und beschädigte Startdatei); Backend-Suite 354 Tests, 0 Fehler, 4 umgebungsabhängig
  übersprungen. Frontend-Suite 80 Tests inklusive vier Settings-Interaktionstests und
  Navigation, Produktions-AOT-Build grün. Browserprüfung gegen einen isolierten temporären
  Datenordner: Laden, LLM-Änderung, Speichern, Neustart-Hinweis und Rückkehr auf aktive Werte.
  Ungespeicherte Eingaben werden beim Verlassen der Seite bzw. Schließen des Fensters geschützt.
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
- **✅ Zonenvorschau konsistent nachgeführt (2026-08-25):** Beim Laden eines gespeicherten
  Szenarios sowie nach Änderungen an Zellgröße oder Zonierungsmodus fordert das Studio das
  aktuelle Raster unmittelbar neu an. INSPIRE-Zellen werden im GeoJSON aus ihren vier echten,
  nach WGS84 transformierten EPSG:3035-Ecken aufgebaut; die zuvor verwendeten geografischen
  Bounding-Boxen erzeugten in Leaflet sichtbare Scheinlücken beziehungsweise doppelte Kanten.
  Die fachliche LAEA-Partition und persistierte Szenarien bleiben unverändert. **Verifiziert:**
  `GeoJsonTest`, `InspireGridZoningTest`, Backend-Suite 142 Tests (7 externe Skips),
  Angular/Vitest-Suite 26/26 grün, Produktions-AOT-Build und Browserprüfung (gespeichertes
  INSPIRE-Szenario lädt mit Raster; Wechsel auf Bbox aktualisiert auf 16 Zonen).
- **✅ Studio-Parameter verständlicher geführt (2026-08-25):** Der intern weiterhin als
  Anteil `(0, 1]` persistierte `samplingFraction` wird im Studio als synchroner Prozent-Regler
  (5–100 %) und im Szenario-Vergleich als Prozentwert dargestellt. Alle gerenderten
  Eingabefelder besitzen einen eigenen oder geerbten Erklärungstext. Der wirkungsarme
  Szenario-Schalter `useOsmData` ist aus dem Studio entfernt und bleibt nur als ignoriertes
  Legacy-JSON-Feld erhalten: Die Pipeline wählt nun immer automatisch Szenario-Snapshot →
  Live-Overpass → synthetischen Fallback. Nur der Betriebs-/Testschalter
  `synthesis.overpass.live-data-enabled=false` unterbindet Live-Abfragen; vorhandene Snapshots
  bleiben nutzbar. **Verifiziert:** `StudioComponent`-Interaktionstests prüfen Prozentbindung,
  Tooltip-Abdeckung und fehlenden OSM-Schalter; `GenerationPipelineE2ETest` beweist, dass ein
  Snapshot trotz Legacy-Wert `false` verwendet wird und Offline-Läufe Live-Overpass zentral
  deaktivieren können; `SynthesisPropertiesTest` sichert den produktiven Default `true`.
- **✅ Amtliches Zensus-2022-Raster automatisch integriert (2026-08-25):**
  `CensusPopulationSource` lädt für Deutschlandregionen das Destatis-Archiv
  `Zensus2022_Bevoelkerungszahl.zip` einmalig nach `data/census-cache/`, akzeptiert es nur mit
  dem fest hinterlegten SHA-256
  `1a8428d31eab264c8717bfdcbf40fbd66c217edb2e2a98d0e67741bd8a1a941a` und extrahiert das
  100-m-Raster atomar. `PopulationGridParser` filtert das deutschlandweite CSV beim Einlesen
  auf die Szenario-Bbox. Die Quellenleiter lautet nun: manuelle Gesamtzahl → alternatives oder
  neueres Upload-Raster → Zensus 2022 → OSM-Siedlungsschätzung → Dichteannahme. Ein
  Download-, Format- oder Hashfehler blockiert keinen Lauf, sondern aktiviert den nächsten
  Fallback. Studio und Manifest zeigen Quelle und Stichtag 15.05.2022; das Manifest hält
  zusätzlich Version, URL, SHA-256, Lizenz und Namensnennung fest. Der CSV-Upload bleibt für
  alternative beziehungsweise neuere Raster bestehen. **Verifiziert:** Das echte amtliche ZIP
  wurde gegen URL, Hash, Eintragsnamen und Header geprüft; `CensusPopulationSourceTest` deckt
  Download, Hash-Ablehnung, Cache-Wiederverwendung, Deutschland-Guard und Regionsfilter ab;
  `GenerationPipelineE2ETest` führt die Offline-Pipeline mit einem hashgeprüften Zensus-ZIP bis
  zur Manifest-Provenienz aus. Der reale Laufzeitabruf für den Rastatt-Ausschnitt lieferte
  65.960 Einwohner, Quelle `Destatis Zensus 2022`, Stichtag 15.05.2022; das gecachte ZIP hatte
  exakt den erwarteten SHA-256. Gesamtgates: Backend 148 Tests grün (7 umgebungsbedingt
  übersprungen), Frontend 30/30 Tests und Produktions-Build grün; Browserprüfung ohne
  Laufzeitfehler bestätigte Quelle, Stichtag, Prozentregler und Tooltip-Texte in der UI.
  **UI-Nachschärfung (2026-08-25):** Das Feld „Einwohner gesamt“ zeigt die automatisch
  ermittelte Zensus-/OSM-Zahl nun direkt an, hält `totalPersons` aber bis zu einer bewussten
  Bearbeitung weiterhin leer. Dadurch bleibt die automatische Quellenprovenienz korrekt;
  Bearbeiten erzeugt eine manuelle Vorgabe, Leeren aktiviert wieder die Automatik.
  **Verifiziert:** Frontend 30/30 Tests und AOT-Produktions-Build grün; Browserlauf zeigte
  652.725 Zensus-Einwohner direkt im Feld, Stichtag 15.05.2022, keine Konsolenfehler.
- **✅ OSM-Eingaben getrennt und Logistikregler gekoppelt (2026-08-26):** Das Studio trennt
  den „OSM-Ortsdatensnapshot“ für Ziele, Bevölkerung, Depots und Packstationen sichtbar vom
  separaten Straßennetz für echtes Routing; Tooltips erklären, dass der Snapshot kein
  routbares Netz enthält. Der bisherige „Arbeitsplatz-Randwert“ heißt nun „Amtliche
  Auspendlerquote“: Er bestimmt den Anteil der Arbeitsziele außerhalb der Region, während eine
  externe Pendlermatrix deren räumliche Verteilung vorgibt. Pakete je Person, Unterschiede
  zwischen Personen sowie die Zustellanteile Zuhause/Arbeitsplatz/Packstation werden als
  Regler dargestellt. Beim Verschieben eines Zustellanteils verteilt das Studio den Rest
  proportional auf die beiden anderen und hält die Summe exakt bei 100 %. **Verifiziert:**
  Angular/Vitest-Suite 34/34 grün und Produktions-AOT-Build erfolgreich; der Browserlauf
  bestätigte für den Zuhause-Regler 80/10/10 % → 60/20/20 % bei weiterhin 100 % Summe.
  **UI-Nachschärfung (2026-08-26):** Der manuelle OSM-Ortsdatensnapshot-Upload ist aus dem
  Standard-Studio entfernt. Ortsdaten werden beim Generieren automatisch über Overpass geladen,
  als `osm-data.json` mit SHA-256 im Lauf dokumentiert und fallen bei Nichterreichbarkeit auf
  synthetische Daten zurück; vorhandene eingefrorene Szenario-Snapshots bleiben sichtbar und
  entfernbar. Auch der schwer beschaffbare Upload der amtlichen Auspendlerquote ist aus dem
  Standard-Studio entfernt: Er wirkt nur zusammen mit einer externen Pendler-OD-Verteilung und
  bleibt für bestehende beziehungsweise per API vorbereitete Szenarien rückwärtskompatibel. Eine
  automatische amtliche Zuordnung folgt in der nachstehenden Nachschärfung. **Verifiziert:** Frontend 34/34 Tests,
  AOT-Produktions-Build und Browserprüfung; direkter Backend-Aufruf auf Port 8090 sowie
  Frontend-Proxy lieferten HTTP 200.
  **Weitere UI-Nachschärfung (2026-08-26):** Bei einer neuen oder neu gezeichneten Region fragt
  das Studio ab 100 m an, berechnet in 50-m-Schritten die feinste Zellgröße innerhalb des
  600-Zonen-Limits und schreibt den tatsächlich verwendeten Wert ins Feld. Der praktische
  Neuanlagen-Default ist wieder das frei auflösbare Bbox-Raster; das INSPIRE-Gitter bleibt als
  bewusste Option mit seinen festen Stufen 100 m/1 km/10 km. Routingzeiten werden als automatische Kette
  OSM/Overpass → GraphHopper → Luftlinien-Fallback erklärt; ein manueller
  Straßennetz-Upload erscheint nur noch, wenn der letzte Lauf tatsächlich auf Luftlinie
  zurückfiel. Die interne Seed-Zahl ist aus dem Standardformular entfernt: Eine Checkbox wählt
  bei Bedarf vor jedem Lauf kryptographisch eine neue Variante und persistiert deren Seed für
  die Reproduzierbarkeit. Der Paket-Regler reicht nun von 0 bis 10 Paketen je Person und Woche;
  Zensus-Raster und Gebietsgrenzen sind in Alltagssprache erklärt. **Verifiziert:**
  Frontend 37/37 Tests und AOT-Produktions-Build grün.
  **✅ Flexible, global ausgerichtete Zonierung und Szenario-Zonenlimit (2026-08-27):** Neben
  dem freien Bbox-Raster und dem amtlichen INSPIRE-/Zensus-Raster steht nun
  `zoning.mode=epsg3035` zur Verfügung. Dieser Modus verwendet dasselbe flächengleiche,
  europaweit feste ETRS89-LAEA-Gitter, erlaubt aber flexible 100-m-Stufen wie 700 m. Seine
  stabilen Zellcodes beginnen bewusst mit `LAEA3035RES…` statt `CRS3035RES…`, damit
  nichtamtliche Auflösungen nicht als INSPIRE-konform erscheinen. Der INSPIRE-Modus bleibt
  unverändert auf 100 m/1 km/10 km beschränkt; außerhalb Europas fallen beide LAEA-Modi auf das
  freie Raster zurück. `zoning.maxZones` macht die Obergrenze je Szenario einstellbar (Default
  600, harte Betriebsgrenze `synthesis.max-zones-hard-limit=1200`). Vorschau und Generierung
  verwenden denselben Wert und schreiben die tatsächlich verwendete Zellgröße zurück in die UI.
  Ein UI-Hinweis macht sichtbar, dass eine geänderte Zonierung alle zonenabhängigen Artefakte
  ungültig macht und deshalb neu generiert werden muss. Tests sichern 700-m-Erhalt,
  100-m-Aufrundung, automatische Vergröberung sowie die API-/Validierungsgrenzen ab.
  **✅ Gemeinsamen Entwicklungsstart gehärtet (2026-08-26):** `scripts/dev.sh` wartet nun auf
  erfolgreiche HTTP-Healthchecks von Backend und Frontend, verwendet bereits laufende gesunde
  SyntheSis-Prozesse wieder und unterscheidet sie von fremden Portbelegungen. Damit führt ein
  erneuter Start nicht mehr zu einem konkurrierenden Maven-/Angular-Prozess oder einer verfrühten
  Erfolgsmeldung. `scripts/preflight.sh core` prüft zusätzlich das dafür benötigte `curl`.
  Der Browser-Abnahmelauf deckte dabei einen unabhängigen Wiederladefehler auf: Bei gespeicherten
  Szenarien wurde zwar das Zonenraster, nicht aber die automatische Zensus-/OSM-Einwohneranzeige
  neu angefragt. `onScenarioSelected()` aktualisiert jetzt beides; ein Roundtrip-Szenario behielt
  750 m Rasterauflösung und zeigte nach dem Neuladen erneut den Zensuswert im Einwohnerfeld.
  **Verifiziert (2026-08-27):** 150 Backend-Tests ohne Fehler (7 umgebungsabhängige Skips),
  38/38 Frontend-Tests, Angular-Produktionsbuild, idempotenter gemeinsamer Start und
  Browser-Roundtrip mit 652.725 Zensus-Einwohnern; das temporäre Abnahmeszenario wurde danach
  wieder entfernt.
  **✅ Amtliche Auspendlerquote backendseitig automatisiert (2026-08-26):** Der zunächst
  geprüfte GENESIS-Gastzugang darf die vollständige Tabelle `13111-08-02-5-B` nicht exportieren.
  Als belastbare zweite amtliche Quelle dient deshalb der frei verfügbare BA-Jahresdatensatz
  „Gemeindedaten der sozialversicherungspflichtig Beschäftigten nach Wohn- und Arbeitsort“ 2025.
  Das Backend enthält daraus 10.152 nutzbare Gemeindewerte mit Stichtag 30.06.2025; URL und
  SHA-256 der 1,5-MB-Originaldatei sind im Ressourcenheader festgehalten. 600 von der BA
  geheim gehaltene Kleingemeindewerte bleiben bewusst aus, statt geschätzt zu werden. Beim
  Upload eines BA-Exports „Auspendler Gemeinden“ ordnet `UploadController` den passenden
  Randwert automatisch zu und entfernt ihn wieder, wenn die zugehörige Pendlermatrix entfernt
  oder durch eine generische OD-Matrix ersetzt wird. Explizite alte Regionalstatistik-Uploads
  bleiben rückwärtskompatibel. **Verifiziert:** Backend 149 Tests grün (7 externe Skips),
  Frontend 38/38 Tests und AOT-Produktions-Build grün; der Rastatt-Referenzwert bleibt
  13.723/22.971 = 59,7405 %.
- **🔬 Fernwege/Außenbeziehungen – nächster belastbarer Ausbau (2026-09-01):** Die BA stellt
  Gemeinde-Pendlerrelationen kostenlos über ihre interaktive Statistik als CSV bereit, jedoch
  derzeit nur hinter einer internen MicroStrategy-Sitzung ohne dokumentierten stabilen
  Datenendpunkt. SyntheSis bindet diese private Web-API deshalb nicht per Screen-Scraping an.
  Als reproduzierbarer Kandidat wird stattdessen ein OSM-Siedlungsfallback untersucht: Die
  backendseitig versionierte BA-Außenquote entscheidet weiterhin **ob** ein WORK-Ziel außen
  liegt; bevölkerungsgewichtete OSM-Siedlungen außerhalb der Region würden nur **wo** schätzen.
  Vor einer Produktverdrahtung gelten vorab: Vergleich gegen amtliche BA-OD in mindestens drei
  Regionen und drei Seeds, Richtungsanteils-MAE höchstens 0,15, relative Abweichung der mittleren
  Außendistanz höchstens 25 %, Abweichung des Anteils `>=20 km` höchstens 10 Prozentpunkte sowie
  keine Verschlechterung von Wege/Person·Tag außerhalb `[3,0; 3,4]`. Ein Fehlschlag bedeutet:
  kein Default und kein verstecktes Ersetzen amtlicher Ziele. Bereits umgesetzt ist die
  Transparenzschicht: Job-Ergebnis und `manifest.json/externalRelations` weisen Zielquelle,
  amtlichen Sollanteil, Istanteil und Zahl externer Arbeitsziele explizit aus.
  **✅ Messpfad umgesetzt (2026-09-01):** `OsmExternalDestinationCandidates` gruppiert
  bevölkerungsgewichtete OSM-Siedlungen außerhalb der Region in acht Richtungssektoren und
  schreibt den reproduzierbaren, noch nicht verhaltenswirksamen Kandidaten nach
  `population/external-destination-candidates.json`. Bei vorhandener amtlicher Pendler-OD
  berechnet die Pipeline die drei Promotionsmaße automatisch und spiegelt sie samt Artefakt-Hash
  unter `manifest.json/externalRelations/osmDestinationCandidate`. Die Overpass-Place-Abfrage
  umfasst dafür den vollständigen 80-km-Prüfradius um den Regionsmittelpunkt. **Verifiziert:** 13 fokussierte Tests einschließlich
  vollständigem Offline-Pipeline-E2E, 0 Fehler/0 Skips; identische synthetische OSM-/OD-Verteilung
  ergibt Richtungs-MAE, Distanzfehler und `>=20-km`-Abweichung jeweils 0. Der Kandidat bleibt
  `diagnostic-only`, bis die vorregistrierte Mehrregionenmessung bestanden ist.
  **❌ Räumliche Vorprüfung verfehlt (2026-09-10):** Rastatt, Freiburg und Karlsruhe verfehlen
  gegen geokodierte BA-Relationen jeweils die Kriterien für mittlere Außendistanz und Anteil
  `>=20 km`. Die vorab festgelegte Abbruchregel stoppt damit die nachgelagerten Drei-Seed-
  Wirkungsläufe; keine Promotion, keine Änderung der produktiven Zielwahl. Die Diagnose ist
  seedfrei. **Verifiziert:** sieben Kennzahlen je Region separat in Python nachgerechnet,
  native Kandidaten-JSONs beim Replay bytegleich. Grenzen sind insbesondere unterschiedliche
  Gemeinde-/Boxzuschnitte, unvollständige Referenzgewichte und der 80-km-Radius des Kandidaten.
  Nächster Forschungsansatz: räumliche Bezugsräume und Fernzielbehandlung klären, Arbeitsplatz-
  statt reiner Bevölkerungsgewichte begründen und neu präregistrieren. Messwerte, Quellen und
  Abbruchentscheidung: [räumliche Vorprüfung in docs/05](05-validierung.md#osm-aussenziele-vorpruefung-2026-09-10).
- **✅ Studio-/Modellachsen nachgeschärft (2026-09-01):**
  - SUMO wird im Studio als nachgelagerte **Verkehrssimulation** statt als gleichartiges
    Zielmodell dargestellt und aktiviert seine harte logiTopp-Abhängigkeit automatisch; auch
    Backend/API normalisieren diese Abhängigkeit. MobileCity bleibt bewusst ein unabhängiger
    district-aggregierter Export: Die heute implementierte Stufe konsumiert keine
    logiTopp-Simulation, weshalb eine automatische teure Simulation fachlich falsch wäre.
  - Der sichtbare Schalter „ÖV/Bus in SUMO“ ist entfernt. Bei ausgewähltem SUMO versucht das
    Backend GTFS über einen bewusst ausgewählten Upload oder sonst automatisch über die
    regionsbezogene Feed-Registry; der lokale reproduzierbare Transit-Interpreter ist als
    Standardpfad konfiguriert.
  - Die Verhaltensoption heißt im Studio **„LLM-Agenten (lokales LLM, experimentell)“**. Stand:
    Persona- und
    Wochenplanerzeugung über Ollama/Qwen2.5 ist Ende-zu-Ende umgesetzt, reproduzierbar gecacht
    und unterstützt Was-wäre-wenn-Begründungen. Die zusätzliche LLM-Moduswahl ist technisch
    umgesetzt, bleibt wegen des dokumentierten Aktivmodus-Bias jedoch eine experimentelle
    Vergleichslinse und ersetzt die kalibrierte logiTopp-MNL-Wahl nicht.

- **✅ Studio-Bedienung verbessert (2026-09-08):** Speichern steht direkt neben der
  Szenarioauswahl in der beim Scrollen sichtbaren Verwaltung. Rückmeldungen erscheinen dort;
  ohne Region erklärt ein Hinweis den deaktivierten Button. „Modelle und Ausgaben“ erhält
  mehr Abstand. Fachliche Optionen nennen ihre Wirkung; ausführliche Zusatzinformationen zu
  Modellabhängigkeiten, Busverkehr, Retouren und Depots sind über fokussierbare Hilfeschalter
  erreichbar (Maus, Tastatur und Klick; Escape schließt). Wichtige Voraussetzungen und
  Einheiten bleiben sichtbar. **Verifiziert:** 44 Frontend-Tests und Produktionsbuild grün;
  Browserprüfung von Speichern, erneutem Laden, SUMO-Abhängigkeit und Hilfen einschließlich
  Escape sowie sichtbarer Abstände bestanden.
- **✅ Review der Zonierung und Außenzieldiagnostik (2026-09-08):** Überlaufende oder nicht
  endliche Rasterauflösungen werden abgewiesen. Abfrage und Kandidatenberechnung teilen den
  80-km-Horizont; die Bevölkerungsschätzung verwendet weiterhin nur den bisherigen lokalen
  Place-Ausschnitt, auch beim Einlesen eingefrorener Daten. Punktbezogene Pendlerrelationen
  mit Ursprung außerhalb der Region werden ausgeschlossen; regionsweite Referenzen bleiben
  gültig. Wiederholungsläufe entfernen veraltete Kandidatenartefakte. Der Offline-E2E prüft
  unveränderte Hashes der fünf produktiven Bevölkerungs-/Nachfragedateien beim Entfernen
  einer nur diagnostisch genutzten Außensiedlung. **Verifiziert:** vollständige Backend-Suite
  mit 162 Tests, 0 Fehlern und 7 extern bedingten Skips; fokussierte 20 Tests ohne Skips.
  Core-Preflight und Abgleich beider Skill-Registries grün.
- **⬜ Deutsch/Englisch umschaltbar (aufgenommen 2026-09-08):** Künftige Erweiterung: ein
  sichtbarer Sprachschalter im Anwendungskopf, der die Auswahl für spätere Besuche speichert.
  Beschriftungen, Hilfen, Fehlermeldungen sowie Zahlen- und Datumsdarstellung übersetzen;
  Szenario-IDs, gespeicherte Konfigurationsschlüssel und Exportformate bleiben stabil.
  Die Sprachumschaltung ist vorgemerkt und noch nicht implementiert.

### MobileCity-Vorlagenexport — ✅ IMPLEMENTIERT (2026-09-17), Referenzfreigabe offen

- **Opt-in:** `mobileCity.useTemplate=false` standardmäßig; `templateId=tramigo-v2`.
  Im Studio unter MobileCity aktivierbar. Der Server lädt nur vorbereitete Vorlagen
  unter `${synthesis.data-dir}/mobilecity/<templateId>/` und prüft deren Hashes.
- **Beitrag:** `referenceYear=2026` gilt für synthetische Haushalte und neue
  POP-/Pkw-/Wegebeiträge. MobileCitys historischer Simulationsstart bleibt erhalten.
  Bevölkerung und private Pkw werden auf volle Zonenbestände hochgerechnet.
  Vollständig abgedeckte Referenz-Districts erhalten nach Polygonverschneidung
  neue Beiträge; übrige Districts und Modellmodule bleiben in der Vorlage.
  Für die native Übernahme des Pkw-Bestands wird im Beitragsjahr zusätzlich der
  Elektroanteil der Referenz für die aktualisierten Districts bereitgestellt.
  Gebietsweite Wegeraten werden je Altersgruppe nach aktualisierter und erhaltener
  Bevölkerung gemischt. Attraktivitäten bleiben wegen abweichender Kalibrierung
  auf dem Referenzstand. Leere Stichprobenzonen und Annahmen stehen in `provenance.json`.
- **Artefakte:** Teilbeitrag weiterhin 6 CSVs; Vorlagenmodus 25 CSV-Familien,
  Governance-Dateien, WGS84-Shapefile (auch MultiPolygone/Löcher), `summary.json`,
  `provenance.json`, `mobilecity-import.zip`. Download im Studio; Schreiben zunächst
  in ein temporäres Verzeichnis, damit ein fehlgeschlagener Export kein halbes Paket ersetzt.
- **Verifiziert:** selbstständige Java-Tests für Hochrechnung, Flächenaggregation,
  historische Jahre, partielle Abdeckung, Hash-/Block-/Netzfehler, CSV-Quoting sowie
  binäres Polygon-/MultiPolygon-Zurücklesen. Backend: 169 Tests, davon 7 übersprungen,
  keine Fehler; Frontend: 44 Tests und AOT-Build grün. Echter Konverterimport, binäres
  Zurücklesen und Simulation 2026/2027 sind nachgewiesen. **Kein grüner fachlicher Gate:**
  fehlende Transitfahrzeiten und NaN-Ergebnisse in der Referenz bleiben offen;
  [Befunde in docs/05](05-validierung.md#mobilecity-vorlagenexport-2026-09-17--import-und-simulation-nachgewiesen-referenzprüfung-offen).

**Lokale Vorbereitung und Prüfung** (Projektwurzel; Pfade zur bereitgestellten Quelle einsetzen):

```bash
validation/transit-venv/bin/python scripts/prepare-mobilecity.py \
  --tool-dir "$MOBILECITY_SOURCE" \
  --districts-gpkg "$TRAMIGO_GPKG" \
  --output backend/data/mobilecity/tramigo-v2
```

Die Vorbereitung benötigt `pyproj`, verändert keine Quelle und überschreibt keine
vorhandene Vorlagenversion. Neue Referenzdaten bekommen eine neue Versions-ID.
Für die Übergabe im Studio mindestens einen vollständigen District mit der Region
abdecken, MobileCity und Vorlagenmodus wählen, speichern und generieren.

```bash
python3 scripts/validate-mobilecity.py \
  --dotnet validation/tools/dotnet-8.0.425/dotnet \
  --tool-source "$MOBILECITY_SOURCE" \
  --import-dir "backend/data/scenarios/<id>/artifacts/mobilecity" \
  --work-dir "validation/mobilecity/<neuer-prueflauf>" \
  --nuget-cache .runtime/nuget
```

Das Prüfskript baut eine isolierte Tool-Kopie mit dokumentierten technischen
Korrekturen, importiert, prüft die Netze und simuliert in einem separaten Prozess
standardmäßig drei Jahre (mit `--years N` erweiterbar). Es vergleicht außerdem die
exportierten Einwohner-/Pkw-Bestände und BEV-Anteile aktualisierter Districts im
Bezugsjahr mit dem nativen Ergebnis. .NET 8 muss lokal vorhanden sein; beim ersten Build werden
die Paketabhängigkeiten benötigt. Timeout pro Prozess standardmäßig 900 Sekunden.
Der JSON-Bericht und alle Logs bleiben auch bei Fehlern erhalten. Die .NET-Prüfung
ist ein expliziter lokaler Prüfschritt und läuft nicht automatisch bei jeder
SyntheSis-Generierung. Der Default bleibt der einfache Teilbeitrag; der Vorlagenmodus
ist außerhalb der Referenzgeometrien nicht anwendbar.

<a id="regionale-zensus-struktur"></a>

### Regionale Zensus-Struktur für Bevölkerung und MobileCity — ✅ UMGESETZT (2026-09-21, experimentell)

**Bedienung:** Im Studio unter Bevölkerung zuerst das KIT-Demografie-Sample und dann
„Regionale Alters- und Haushaltsstruktur“ aktivieren. API-Feld:
`population.useCensusStructure=true`, standardmäßig `false`; ohne `useKitPopulation=true`
ist die Kombination ungültig. Abschalten stellt die bisherige Synthese wieder her.

**Datenweg:** `CensusStructureSource` lädt die beiden am 18.09.2026 geprüften amtlichen
Zensus-2022-Archive mit festem SHA-256. Cache: `data/census-cache/structure/`.
`synthesis.census.enabled`, `timeoutSeconds` und `maxArchiveMb` gelten auch hier.
Der vorhandene Syntheseschritt verwendet die Rastermittelpunkte innerhalb des gewählten
Kartenausschnitts und der internen Zonen. Keine neue Pipeline-Stufe und keine zusätzliche
Bibliothek. Ein HTTP-, Hash- oder Formatfehler führt mit sichtbarem Grund zum KIT-Fallback.

**Modellierung:** Die fünf Altersklassen werden auf unter 18 / 18–64 / ab 65 Jahre
aggregiert; Haushaltsgrößen bleiben 1 bis 5 und 6+. Wegen statistischer Geheimhaltung
werden Gruppen auf ihre eigene Summe normiert. Die veröffentlichten Gesamtzahlen dienen
als Diagnose, nicht als Nenner der Anteile. Ab 50 gruppierten Personen bzw. 20 Haushalten
werden Zonenanteile verwendet, darunter Anteile der ausgewählten Region. Diese Schwellen
sind Modellannahmen, keine amtlichen Genauigkeitsgarantien.

`CensusHouseholdSampler` gewichtet bestehende KIT-Haushaltszusammensetzungen gleichzeitig
nach Personenalter und Haushaltsgröße. Die maximale Abweichung der erwarteten Anteile darf
höchstens 2 Prozentpunkte betragen; sonst wird in dieser Zone das Ausgangssample gezogen.
Es werden keine zusätzlichen Alterswerte oder Haushaltstypen erfunden. Für die exakte
Personenzahl kann ein letzter Haushalt gekürzt werden; das wird mitgezählt.
Die erzeugten Personen gehen anschließend in Aktivitäten und MobileCity ein.

**Herkunft und Grenzen:** `population/census-structure.json` enthält Quellen, Archiv-Hashes,
Datenstand, Seed, Fingerabdruck des Haushaltspools, lokale/regionale Zuordnung, Fit- und
Stichprobenabweichungen, fehlende Gruppen sowie Kürzungen. Job-Zusammenfassung und Manifest
verweisen darauf. Bei einem Fit-Fallback sind verworfene Fit-Anteile und tatsächlich
angewendete Ausgangsanteile getrennt. Die KIT-Stichprobe enthält keine 6+-Haushalte:
Ein Zielanteil bis 2 % kann deshalb trotz akzeptiertem Fit vollständig fehlen. Kleine
Stichproben können stärker abweichen als der erwartete Fit. Zensus-Anteile von 2022 werden
auf die Szenariobevölkerung übertragen; eine demografische Fortschreibung ist nicht enthalten.
Dies ist keine lokal vollständig kalibrierte Populationssynthese.

**Verifiziert:** Offline-Tests für Hash-/Datenfallback, unterdrückte Gruppen, alte
ZIP-Dateinamencodierung, nicht erreichbare Ziele, Randfälle mit 100-%-Anteilen,
Reproduzierbarkeit und Übergabe an `POPinhabitants.csv`. Der vollständige Pipeline-Test
prüft den sichtbaren Datenfallback. Die Probe mit Originalarchiven für Rastatt und drei
Seeds erreicht bei jeweils 20.000 Personen höchstens 1,93 Prozentpunkte Stichprobenfehler;
[Messaufbau und Grenzen](05-validierung.md#zensus-strukturgewichtung-2026-09-21--originaldateien-bis-mobilecity-geprüft).
Eine neue native MobileCity-Simulation war nicht Teil dieser Strukturprobe.

**Noch offen:** Regionale KBA-Antriebsanteile und deren konsistente Zuordnung zur Pkw-Flotte;
der eigenständige vollständige MobileCity-Netz-/Modellaufbau bleibt ein weiterer Ausbau.

<a id="mobilecity-regionalexport"></a>

### MobileCity-Regionalexport und private BEV-Anteile — ✅ UMGESETZT (2026-09-22, experimentell)

- **Eigenständiges Gebiet:** `mobileCity.regionalExport=true` erzeugt alle 25 CSV-Familien,
  eigene Districts, deren Shapefile, einen leeren Maßnahmenzeitplan und ein ZIP. Der Modus
  ist unabhängig von den TRAMIGO-Gebietsgrenzen und alternativ zu `useTemplate`. Default
  bleibt der Teilbeitrag. `templateId` bezeichnet im Regionalmodus das lokale, hashgeprüfte
  Modellprofil; dessen Koeffizienten sind für das neue Gebiet **nicht kalibriert**.
- **Districts und Netze:** Deterministische Zusammenfassung auf höchstens `maxDistricts=100`
  bewohnte Districts; leere interne Zonen behalten ihre Flächen über die Zuordnung zu einem
  Nachbardistrict. Personen und private Pkw bleiben in Summe erhalten. Flächen stammen aus
  der projizierten Polygonunion. Auto-/Rad-/ÖV-Netze sind vollständige ungerichtete Graphen
  zwischen repräsentativen Zonen aus vorhandenen OD-Matrizen; Fußmatrizen übernehmen die
  Zeit und die neu gespeicherte `PED_DIS` in Metern, Export in km. Dies ist kein physisches
  Straßennetz; native kürzeste Wege können von den Eingabematrizen abweichen.
- **KBA-Anbindung:** `mobileCity.useRegionalFleet=false` standardmäßig. Opt-in nutzt die
  gepinnte FZ-27.17-Tabelle vom 01.07.2026 (10.745 Gemeindezeilen), BKG-VG250-Geometrien
  und eindeutige Kreis-/Gemeindenamen-Zuordnung. Private Bestände werden nach Schnittfläche
  übertragen; innerhalb jeder Gemeinde wird gleichmäßige Pkw-Dichte angenommen. Quelle,
  Datenstand, Hashes und Abdeckung stehen in `fleet-provenance.json`. BEV zählt elektrisch;
  PHEV bleibt im Bericht separat und zählt im binären Zielmodell nicht zu BEV.
- **Lücken bleiben sichtbar:** `electricShare=null` bedeutet unbekannt, `0` ist eine
  ausdrückliche Nullannahme. Die manuelle Vorgabe füllt nur fehlende Regionalwerte auf.
  Außerhalb Deutschlands oder vor dem KBA-Datenjahr ist eine solche Vorgabe erforderlich.
  Beim Regionalpaket stoppt ein fehlender Anteil für eine Zone mit Pkw den Export; beim
  Vorlagenmodus bleibt der Referenzanteil erhalten und wird als Fallback ausgewiesen.
- **Modellannahmen:** `transitVehiclesPer1000=1` ist nur der sichtbar freigegebene Fallback,
  `settlementAreaShare=0.5`, konstante
  Bestände/Wegehäufigkeiten einschließlich zweier Initialisierungsjahre. Bezugsjahr
  2018–2048, Horizont höchstens zehn Folgejahre bis 2050. Bei verfügbarem regionalen oder
  hochgeladenen GTFS stammen Transitnetz, Frequenzen und Spitzenflotte aus einem expliziten
  repräsentativen Werktag. Öffentliche Ladepunkte sind ohne gewählte BNetzA-Automatik oder
  Nutzervorgabe unbekannt. Carsharing-Flotte und -Mitglieder können als konstante
  Szenarioannahmen eingegeben werden; sonst gilt die ausdrücklich ausgewiesene Nullannahme.
  Umlandbevölkerung und Altersstruktur können aus einem einstellbaren Zensus-Pufferring
  stammen oder manuell vorgegeben werden; die zweckspezifischen eingehenden Wegeanteile sind
  weiterhin ausdrückliche Annahmen. Ohne vollständigen Satz bleiben externe Zonen reine
  Zielanker. Keine Logistik. Finanz-/Netzlängenansätze werden pro Kopf
  aus dem Basisjahr und Untersuchungsgebiet des Profils übertragen, nicht aus sparse
  Zukunftswerten oder Umland. Regional fremde Governance-Maßnahmen werden nicht kopiert.
  Zu-/Abgangsparameter, Saisonticketrate und weitere Koeffizienten bleiben Profilannahmen.
- **Paket und Schutz:** Provenienz nennt Profil-/Matrix-Hashes, Aggregation, Quellen und
  Annahmen; ein vorhandener Zensus-Strukturbericht wird mit verpackt. Fehlende Matrizen,
  falsche IDs, nicht endliche/negative Impedanzen sowie bekannte native Null-Divisoren
  verhindern die Veröffentlichung. Ein zuvor erfolgreicher Export bleibt bei Fehlern
  erhalten. Native Modellgrenzen: positive Pkw-Gesamtzahl, Erwachsene und Wege erforderlich;
  Districts mit 1 bis unter 2 Pkw je Einwohner werden wegen einer Division durch null im
  nativen dritten Simulationsjahr abgelehnt, ohne die Daten still zu ändern.
- **Bedienung:** Für den minimalen Pfad MobileCity wählen → „Rudimentären MobileCity-Export
  konfigurieren“ → Region und Einwohnerzahl prüfen → „MobileCity-Export erzeugen“ → ZIP und
  Herkunftsbericht herunterladen. Der Schnellstart beschränkt auf zwölf Districts, verwendet
  Luftlinienmatrizen, modellierten ÖV, eine explizite Nullannahme für BEV und kein Umland.
  Beim Generieren wird der sichtbare Szenariostand zuerst gespeichert. Der manuelle Ausbaupfad
  bleibt Regionalpaket → KBA oder BEV-Annahme → Districtzahl/ÖV/Siedlung → optionale native
  Prüfung. Fehlendes Modellprofil oder fehlende BEV-Grundlage werden vor dem Queueing mit einer
  konkreten Meldung abgewiesen. Das lokale Profil muss wie oben vorbereitet sein; ein erfolgreicher
  Exportstatus allein ist keine Simulationfreigabe.
- **Verifiziert:** Ein Berliner Ausschnitt außerhalb der Referenzgeografie, neun interne
  Zonen einschließlich einer leeren Zone, 8.000 Personen und 3.886 Pkw: ein und vier
  Districts, 0 bzw. 3 % BEV. Beide Pakete im echten ConfigTool importiert, binär neu geladen
  und **2026–2036** simuliert; keine Fehler/nicht endlichen Zellen. Bestände und BEV-Anteile
  im Bezugsjahr exakt bzw. innerhalb numerischer Toleranz übernommen. Details und Grenzen
  im [Validierungsbefund](05-validierung.md#mobilecity-regionalpaket-2026-09-22).

<a id="mobilecity-naechste-schritte"></a>

### Konzept: vom Regional-Basispaket zum belastbaren Gebietsmodell (2026-09-22)

**Ziel:** Für einen Kartenausschnitt ein technisch geprüftes MobileCity-Paket erzeugen,
bei dem der Nutzer erkennen kann, welche Größen beobachtet, abgeleitet, angenommen oder
noch unbekannt sind. „Beliebiges Gebiet“ bezeichnet zuerst die unabhängige Geografie;
amtliche Automatik ist zunächst auf Deutschland begrenzt. Andere Länder benötigen
regionale Quellenadapter oder ausdrücklich gewählte Profile und Nutzervorgaben.

**Architektur:** Die bestehenden Exportmodi bleiben erhalten. Fachliche
Quellenadapter liefern gemeinsame, eingefrorene Regionaldaten an den Exporter; sie
schreiben nicht unmittelbar Ziel-CSV-Dateien. Jeder Wert führt Einheit, Bezugsdatum,
räumlichen Bezug, Abdeckung, Ableitung und Quellhash. Manuelle Annahmen und Messwerte
werden getrennt gespeichert. Für wiederholte Läufe werden dieselben Quellsnapshots
verwendet; Aktualisierung erfolgt bewusst, nicht unbemerkt beim Wiederholen eines Jobs.
Die weiteren Bausteine werden einzeln hinter standardmäßig deaktivierten Optionen integriert.

| Reihenfolge | Arbeitspaket | Konkretes Ergebnis und Abnahme |
|---|---|---|
| **✅ 1 – Datenübersicht und Prüfstatus** | Aufgelöste Werte und Lücken vor dem Export im Studio zeigen; `automatisch`, `manuell überschreiben` und `Fallback bei Lücke` ausdrücklich unterscheiden. Quellstand je Lauf einfrieren. Native Prüfung optional als eigener Job-Nachlauf mit separatem Status anbinden. | Umgesetzt: Nutzer sieht Herkunft und fehlende Pflichtdaten; Änderung von Region/Jahr/Konfiguration markiert das Ergebnis als veraltet. CSV-Erzeugung und native Prüfung besitzen getrennte Status. Quellenausfall, Cache-Wiederholung, manueller Wert und Prozessfehler sind getestet. |
| **🟡 2 – Reales ÖV-Angebot** | Vorhandene GTFS-Feeds für Haltestellen, Fahrten, Kalender und repräsentativen Werktag auf die MobileCity-Netze abbilden. Frequenz, Umstiege und Zu-/Abgang explizit definieren; Nachfrage- und Angebotsjahr getrennt führen. | Adapter und nativer Netz-/Simulationsnachweis umgesetzt. Ausstehend: systematische Abnahme an Stadt-, Land- und Randlage sowie Relationsvergleich. Fehlender Feed ist sichtbar; synthetischer ÖV ist eine eigene Szenariofreigabe. |
| **✅ 3 – Ladepunkte und weitere Flotten** | BNetzA-Ladesäulenregister: Standorte, Inbetriebnahmejahr, Ladepunkte und Leistung räumlich zuordnen. Zielvertrag und zeitliche Fortschreibung im nativen Kern nachweisen. Sharing-Angebot nur aus belastbarer Quelle oder UI-Modellierung ergänzen. | Ladepunktadapter, UI, Quellsnapshot, Herkunftsnachweis und native Übernahme sind umgesetzt. `REGchargers` zählt öffentliche Ladepunkte. Unbekannte Versorgung ergibt keinen stillen Nullwert; Folgejahre nutzt MobileCity endogen. Carsharing-Fahrzeugdichte und Mitgliederanteil sind editierbare, im Inventar ausgewiesene Annahmen und nativ geprüft. Eine automatische Sharing-Quelle bleibt eine optionale Verbesserung. |
| **🟡 4 – Umland und Außenbeziehungen** | Puffergebiet mit eigener Bevölkerung und konsistenten Districts; BA-Pendlerdaten für Arbeitsbeziehungen verwenden. Einkauf/Freizeit separat modellieren. Bestehende externe Zielanker sind ohne explizite Bevölkerung keine Umland-Districts. | Manuelle Modellierung, automatische Zensus-Puffergeometrie/-bevölkerung/-altersstruktur und native Hinwegsemantik sind umgesetzt. Offen: BA-Arbeitsanteile, getrennte Quellen für Bildung/Einkauf/Freizeit sowie Rand-/Größentests. |
| **5 – Regionale Profile und Jahresverläufe** | Referenzkoeffizienten fachlich isolieren; Kosten, Emissionen, Finanzen, Motorisierung und Verkehrsmittelwahl mit Geltungsbereich dokumentieren. Nutzer kann Trends und Maßnahmen modellieren; Zensus-2022-Struktur und KBA-2026-Bestand nur mit ausgewiesener Annahme fortschreiben. | Basisfall und mindestens eine verständliche Maßnahme zeigen plausible Richtung/Sensitivität. Keine TRAMIGO-District- oder Netz-ID im freien Regionalprofil. Regionale Daten werden nicht als bereits kalibrierte Koeffizienten ausgegeben. |
| **🟡 6 – Abnahme und Übergabe** | Technische Regressionen über drei deutsche Regionstypen, einen Ausschnitt außerhalb Deutschlands mit manuellen Angaben, kleine/angeschnittene Gebiete und Quelldatenlücken. Anschließend Abnahme in tatsächlicher MobileCity-Anwendung mit Takomat. | Fünf synthetische Geografieproben sowie ein realer Vier-Zonen-Fall mit Live-BKG, Zensus, KBA, BNetzA und KVV bestehen Export, Import und elf native Simulationsjahre. Teilweise BKG-Abdeckung führt sichtbar zum Fehler. Offen: weitere reale Regionstypen, GTFS-Relationsvergleich und fachliche Abnahme mit Takomat. [Geografiebericht](05-validierung.md#mobilecity-mehrregionenpruefung-2026-09-25), [Live-Quellenbericht](05-validierung.md#mobilecity-rastatt-livequellen-2026-09-28). |

**Arbeitspaket 1 umgesetzt (2026-09-22):** Vor dem Lauf zeigt das Studio elf Datenbereiche
mit Status, Auflösungsweg und konkreter Lücke. Nach dem Lauf ist derselbe Vertrag als
`mobilecity/data-inventory.json` im Paket fixiert. Er enthält die effektiven Werte,
Abdeckungen, Bezugsstände und ein kanonisches Eingabeabbild. Die beim KBA-Verschnitt
tatsächlich verwendete BKG-Antwort wird zusätzlich als `mobilecity/fleet-boundaries.geojson`
gesichert; Zensus-/KBA-Stände und Hashes werden referenziert. Ein geändertes Gebiet, Jahr,
Zonierungs- oder MobileCity-Parameter markiert das angezeigte Paket als veraltet.

Die neue, standardmäßig ausgeschaltete Stufe `MobileCity-Validierung` läuft direkt nach dem
Export. Sie startet `scripts/validate-mobilecity.py` mit der lokalen .NET-Laufzeit und einer
isolierten ConfigTool-Kopie, bewahrt Bericht und vier native Logs unter
`mobilecity-validation/` und unterscheidet `passed`, `failed` und `skipped`. Fehlende lokale
Werkzeuge stoppen andere Exporte nicht; ein gestarteter, aber fehlgeschlagener nativer Lauf
stoppt den Job. Bei jedem neuen Lauf ersetzt ein expliziter Status einen älteren Bericht.

**Arbeitspaket 2, technischer Adapter umgesetzt (2026-09-22):** `GtfsSource` erzeugt für
MobileCity einen räumlichen Volltageszuschnitt; ein Upload hat als bewusste Quellenwahl
Vorrang. `MobileCityGtfsAdapter` berücksichtigt `calendar.txt`, `calendar_dates.txt` und
`frequencies.txt`, wählt den mittleren Mittwoch mit maximalem aktivem Fahrtenangebot und
führt dieses Angebotsdatum getrennt vom Nachfrage-Bezugsjahr. Plattformen werden über
`parent_station` zusammengefasst. Fahrzeit ist die gewichtete planmäßige Zeit, Entfernung
die Haltestellen-Luftlinie in km. Weil der native Graph eine Kante selbst bidirektional
macht, wird je Haltestellenpaar die maximale Richtungsfrequenz in Fahrten/Werktag verwendet.
Die Route-Type-Codes entsprechen der beigefügten Network Pipeline. Transfers und je ein
District-Zugang an einem inneren Punkt der jeweiligen Geometrie nutzt Fußmodus 9;
`freq=138` vermeidet dort den sonst fälschlich
angewendeten Wartezeitaufschlag des 16-Stunden-Modelltags. Der Quellfeed, Hash,
Gültigkeitszeitraum, Verkehrstag, Ableitungsregeln und Kennzahlen liegen in
`gtfs-feed.zip` und `transit-provenance.json`.

Der kontrollierte Vier-District-Nachweis wurde im echten ConfigTool importiert, ohne
NaN/Infinity geroutet und für 2026–2028 simuliert. Der aktuelle KVV-Feed für Rastatt ergab
am ausgewählten 01.07.2026 722 Fahrten, 544 Knoten der größten Komponente, 673 Servicekanten,
210 Transfers sowie je 29 Bus- und Schienenfahrzeuge in der Fahrplan-Spitze. Die ausstehende
fachliche Abnahme über drei Regionstypen bleibt Teil von Arbeitspaket 2.

**Arbeitspaket 3, Ladepunkte und Carsharing umgesetzt (2026-09-22):** Der native Tabellenvertrag
bezeichnet `numberOfChargingStations` trotz des Feldnamens ausdrücklich als gesamte Zahl
der Ladepunkte. Ein gepinnter BNetzA-Snapshot vom 01.09.2026 wird hashgeprüft eingelesen;
116.422 veröffentlichte Einrichtungen enthalten 209.096 öffentliche Ladepunkte. Der Adapter
ordnet deren Koordinaten den tatsächlichen District-Geometrien zu, summiert `Anzahl Ladepunkte`
und schreibt den regionalen Ausschnitt mit Herkunftsnachweis ins Paket. Die
Inbetriebnahmedaten liefern eine kumulative Historie der im aktuellen Snapshot noch
geführten Anlagen und sind daher als Untergrenze gekennzeichnet. Nach dem letzten
beobachtbaren Jahr bleiben die Zellen leer, damit der native Ausbaupfad greift. Eine
Nutzervorgabe je 1.000 Einwohner ist als alleinige Annahme oder Quellenfallback verfügbar.
Der ConfigTool-Beleg hat die vier exportierten Bezugsjahrswerte exakt übernommen und die
leeren Folgejahre 2027–2028 endlich simuliert.

Für Carsharing liest der native Kern `carsharing.member` als gebietsweite absolute
Mitgliederzahl und `carsharing.stock.total` als Fahrzeuge je District. Beide Größen wirken
direkt auf die Moduswahl. Das Studio bietet deshalb `carsharingMemberShare` und
`carsharingVehiclesPer1000` als explizite Annahmen an; der Export rechnet sie anhand der
synthetisierten Bevölkerung um und hält sie über den Horizont konstant. Der native Beleg
übernahm 100 Mitglieder sowie je 5 Fahrzeuge in vier Districts für 2026–2028 exakt und
erzeugte 92 Ergebnisdateien ohne Prozessfehler oder nicht endliche Zellen.

Eine automatische Quelle wurde bewusst nicht vorgetäuscht: Das
[bcs-Städteranking 2024](https://carsharing.de/carsharing-staedteranking) enthält
Fahrzeugzahlen für deutsche Kommunen ab 50.000 Einwohnern, ist damit aber weder räumlich
vollständig noch eine Quelle für Mitgliederzahlen. Die
[ECSS-Schnittstelle](https://carsharing.de/themen/carsharing-schnittstelle) standardisiert
Stations- und Verfügbarkeitsdaten einzelner Anbieter, stellt jedoch keinen flächendeckenden
öffentlichen Bestandsdienst dar. Beide bleiben Kandidaten für einen späteren, getrennt
gekennzeichneten Quellenadapter.

**Arbeitspaket 4, manueller Umlandvertrag umgesetzt (2026-09-23):** Der native
`tripShareToStudyArea`-Vertrag wurde im Reader und Rechenkern geprüft. Er beschreibt je
Umland-District und Zweck den Anteil der Wege von dort in das Untersuchungsgebiet. Die
anschließende Verteilung auf Binnenziele gewichtet deren Bevölkerung und Attraktivität.
Er ist damit kein Anteil ausgehender Wege der Binnenbevölkerung.

Das Studio nimmt optional eine gesamte `surroundingPopulation` und vier Anteile für Bildung,
Arbeit, Einkauf und Freizeit in `[0,1]` auf. Sobald eine Umlandbevölkerung gesetzt ist, sind
alle vier Anteile und aktivierte Außenbeziehungen Pflicht. Der Export verteilt die Personen
deterministisch auf bis zu acht vorhandene Richtungsanker, kennzeichnet sie als
`districtType=0` und erhält die Gesamtsumme exakt. Altersstruktur, Pkw-Dichte und BEV-Anteil
werden als sichtbare Annahme aus dem Untersuchungsgebiet extrapoliert. Study-Area-Zähler,
Carsharing-Mitglieder, Transitflotte und Finanzskalierung bleiben auf die Binnenbevölkerung
begrenzt. `data-inventory.json`, `summary.json` und `provenance.json` weisen Umlandzahl,
Districtzahl, Anteile und Ableitungen aus.

Der native Drei-Jahres-Beleg verwendete vier Binnen- und acht Umland-Districts mit insgesamt
8.000 Umlandbewohnern. Alle 32 Beiträge aus acht Districts und vier Zwecken wurden im
Bezugsjahr innerhalb numerischer Toleranz exakt im Rechenkern wiedergefunden; 92 Ergebnis-
CSV-Dateien entstanden. Dabei wurde ein Zielmodellfehler sichtbar: Hat ein Outer-District in
zwei Jahren einen Transit-Fahrkartenindex von null, dividiert `CAL6010_SOC` bei der
Saisonticket-Fortschreibung `0/0`. Die isolierte Prüfung setzt für diesen mathematisch
unveränderten Fall den Elastizitätsbeitrag auf null; danach bleiben 2026–2028 endlich. Der
Patch ist im Prüfbericht gehasht und muss mit Takomat geklärt werden. Er verändert weder das
Exportpaket noch positive Fahrkartenindizes.

**Automatische Zensus-Seite umgesetzt (2026-09-23):** `autoSurrounding` bildet in EPSG:3035
einen Ring von `surroundingBufferKm` um den Kartenausschnitt und schneidet ihn in acht
überlappungsfreie Richtungssektoren. Die gepinnten 100-m-Raster liefern Einwohner und
Altersstruktur je Sektor; eine optionale manuelle Gesamtsumme skaliert die beobachteten
Gewichte ohne Bilanzverlust. Richtungsanker werden an Ausschnitt und Puffer angepasst, damit
Netzreferenzpunkt und Outer-Geometrie auch bei großen Gebieten zusammenpassen.
`surrounding-provenance.json` enthält Quellhashes, Stichtag und Sektorwerte.

Die reale Rastatt-Sonde für 48,82–48,90° N / 8,15–8,25° E ergab bei 10 km Puffer
**202.412 Einwohner** und **5.797 Altersrasterzellen**. Das daraus erzeugte Paket mit vier
Binnen- und acht Outer-Districts bestand Import, binäres Zurücklesen und 2026–2028:
**92 Ergebnis-CSVs, 0 Fehler, 0 nicht endliche Zellen und 81/81 Beitragsprüfungen**, darunter
32/32 Umland-Zweckbeiträge im Bezugsjahr. Lokale Evidenz:
`validation/mobilecity/auto-surrounding-spike/native-v1/`.

BA-Pendlerdaten decken weiterhin nur Arbeitswege ab und dürfen nicht als Ersatz für Bildung,
Einkauf und Freizeit verwendet werden. Die Automatisierung dieser Zweckanteile ist der nächste
Teil von Arbeitspaket 4.

**Entscheidungen vor fachlicher Freigabe:** Die technische Einheit der Ladeinfrastruktur
ist durch Referenztabellen, Reader, Rechenkern und nativen Beitragsnachweis geklärt. Takomat
muss noch den fachlichen Einsatzbereich des öffentlichen Registers, den Umgang mit
synthetischen Verbindungsnetzen und den Maßnahmenvertrag bestätigen. Die nativen Rechenfehler bleiben separat dokumentiert; Export-Grenzen dürfen
nicht durch erfundene Einwohner, Pkw oder Wege kaschiert werden. Für Logistik wird später
ein eigener Vertrag für Mengen, Depots, Fahrzeuge und Touren benötigt; dieses Konzept
behauptet noch keinen bestehenden MobileCity-Logistikexport.

### MobileCity aus Region, Datenquellen und Nutzervorgaben — Ausbauziel (2026-09-17)

**Nutzervorgabe:** Nach Auswahl eines Kartenausschnitts sollen ableitbare Größen automatisch
vorliegen. Regionale Statistiken ergänzen die Daten; verbleibende Annahmen lassen sich im
Studio modellieren. Ziel ist ein eigenständiger Export für die gewählte Region. Die folgende
Übersicht trennt umgesetzte Bausteine vom weiterhin geplanten Ausbau.

**Quellenrecherche (2026-09-18):** Zensus-Alters- und Haushaltsgrößenraster sowie KBA
FZ 27.17 wurden erfolgreich heruntergeladen und geprüft. Priorität haben die regionalen
Alters-/Haushaltsrandwerte und private Pkw-Bestände mit getrennten BEV-/PHEV-Anteilen.
Weitere Kandidaten: Bevölkerungsfortschreibung, BNetzA-Ladepunkte, BA-Pendler und
MiD-2023-Vergleichswerte. Konkrete Zugänge, Formatnachweise und Einschränkungen stehen im
[Quellenbefund](05-validierung.md#mobilecity-datenquellen-2026-09-18--regionale-struktur-und-pkw-antriebe-recherchiert).

| Bereich | Vorgesehene Quelle und Eingriff | Aktueller Stand / nächste Ergänzung |
|---|---|---|
| Districts, Flächen, Lage | Kartenausschnitt und Zonierung | Regionalexport erzeugt eigene, durchgängig konsistente Districts; maximal 100 durch Aggregation. |
| Einwohnerzahl und räumliche Verteilung | Automatisch Zensus; alternatives Raster oder manuelle Gesamtzahl | `CensusPopulationSource` liefert bereits das Zensus-2022-Bevölkerungsraster. Datenjahr und Szenariojahr getrennt halten; Fortschreibung als Annahme ausweisen. |
| Alters- und Haushaltsstruktur | Zensus-2022-Altersklassen und Haushaltsgrößenraster; zusätzliche Haushaltstypen, gekennzeichnetes Profil als Fallback | Opt-in-Adapter und Gewichtung bestehender KIT-Haushalte umgesetzt; fehlende 6+-Haushalte, kleine Stichproben und zeitliche Fortschreibung bleiben Grenzen (siehe oben). |
| Pkw-/Radnetze, Fußwege | OSM/Routing anhand der Region | Aggregierte Knoten/Links aus OD-Matrizen und Fußmatrizen umgesetzt; physische Netzabbildung und routingspezifische Abnahme offen. |
| ÖV-Netz und Fahrzeiten | Regionaler GTFS-Feed mit explizitem Verkehrstag | Java-Adapter für Bus/Tram/Bahn, Frequenzen, Transfers, District-Zugänge, Quellsnapshot und Spitzenflotte umgesetzt; Drei-Regionen-Abnahme, Kapazitäten und regionale Tarife bleiben offen. |
| Ziele und Attraktivität | OSM-POIs und regionale Strukturdaten; lokale Korrekturen | Bestehende Attraktivitäten fachlich auf MobileCity-Normalisierung abbilden; POI-Zahlen nicht ungeprüft als Arbeitsplätze behandeln. |
| Umland und einströmende Nachfrage | Einstellbares Umland, Bevölkerung und verfügbare Pendlerdaten | Bis zu acht `Outer`-Districts mit exakter Bilanz; Geometrie, Bevölkerung und Altersstruktur automatisch aus einem metrischen Zensus-Pufferring oder manuell. BA-Arbeitsanteile bleiben offen; Pendler decken die anderen Zwecke nicht ab. |
| Pkw-Bestand, Elektroanteil, Sharing und Ladeinfrastruktur | KBA FZ 27.17 für private Pkw/BEV/PHEV; BNetzA für öffentliche Ladepunkte; Sharing-Profil und UI-Vorgaben | KBA/BKG-Zuordnung und privater BEV-Anteil angebunden; PHEV separat diagnostiziert. BNetzA-Ladepunkte einschließlich manueller Ersatzannahme angebunden und nativ geprüft. Carsharing-Fahrzeugdichte und Mitgliederanteil sind editierbar, werden konstant exportiert und nativ geprüft; bcs/ECSS sind wegen unvollständiger räumlicher bzw. organisatorischer Abdeckung noch keine automatische Quelle. |
| Kosten, Emissionen und Verhaltensparameter | Versioniertes MobileCity-Modellprofil; erweiterte Bearbeitung | Regional unabhängige Parameter aus Referenzen isolieren und auf Übertragbarkeit prüfen. Bestehende Referenzfehler bleiben zu beheben. |
| Entwicklungen und Maßnahmen | Nutzer modelliert Jahreswerte bzw. Verlauf | Historie, Datenjahr, Beitragsjahr und Simulationsstart unterscheiden; keinen heutigen Wert still als historische Beobachtung ausgeben. |

**Bedienung im Studio:**

1. Region wählen; vorhandene Datenquellen und ihre Abdeckung anzeigen. Netzaufbereitung
   beim Generieren ausführen, nicht bei jeder Bewegung des Kartenausschnitts.
2. Unter „MobileCity – Daten und Annahmen“ die Bereiche Bevölkerung, Verkehr/Umland,
   Flotten/Infrastruktur und Entwicklung anbieten. Modellkoeffizienten unter „Erweitert“.
3. Je Wert effektiven Wert, Einheit, Herkunft, Datenjahr und Status anzeigen:
   `Quelldaten`, `abgeleitet`, `Modellannahme`, `manuell` oder `fehlt`.
4. Manuelle Vorgaben haben Vorrang; „Automatisch“ entfernt die Vorgabe. Ein leeres Feld
   bedeutet fehlend, eine explizite Null bleibt ein gültiger Wert, soweit fachlich zulässig.
   Für jede Größe gilt eine eigene Quellenkette; es gibt keinen pauschalen Zensus-Fallback.
5. Bei Regionswechsel automatische Ableitungen erneuern. Gebietsbezogene Überschreibungen
   als prüfbedürftig markieren, statt sie unbemerkt auf andere Districts zu übertragen.
   Fehlende Pflichtangaben verhindern einen als vollständig bezeichneten Export und
   erscheinen mit einer konkreten Handlungsmöglichkeit.

**Umsetzung in überprüfbaren Schritten:**

1. Auflösung von Quelldaten, versionierten Profilen und Nutzervorgaben samt UI und
   Herkunftsnachweis durchgängig für Bevölkerung und Pkw-/Elektrobestand umsetzen.
   Vorhandene Populationsfelder und Fahrzeugkonfiguration wiederverwenden, widersprüchliche
   Parallelkonfigurationen vermeiden. Die Werte müssen bis zur MobileCity-Ausgabe wirken.
2. Eigenständige Districts, Pkw-/Rad-/Fußnetze und GTFS-ÖV in einem kleinen Testgebiet
   außerhalb der TRAMIGO-Geometrie nachweisen. Die Integration des ÖV-Datenzugriffs bleibt
   unabhängig von einer Auswahl des Zielmodells SUMO.
3. Umland, regionale Strukturquellen, weitere Bestände und Szenarioverläufe ergänzen.
   Das Logistikformat folgt, sobald der Zielvertrag vorliegt.

**Abnahmeziel:** Eine neue Region lässt sich ohne TRAMIGO-Gebietsdaten vollständig
exportieren und mit dem MobileCity Config Tool prüfen. Keine fehlenden Pflichtblöcke,
ungültigen Netzbezüge oder nichtendlichen Pflichtwerte. Änderungen im Studio erscheinen
im Export und nativen Ergebnis; Rücksetzen auf Automatik sowie Regionswechsel werden
geprüft. Identische Quellstände, Profile und Seeds reproduzieren die Eingaben. Bestehende
Szenarien behalten ihren bisherigen Exportmodus bis zur expliziten Auswahl des neuen Pfads.

### Phase 5 – Externe Klärungen (nicht-technisch, parallel)
- **MobileCity-Zielformat erneut geprüft (Ausgangsbefund 2026-09-15):** Die TRAMIGO-Beispiele
  und der mitgelieferte C#-Importer bestätigen den aggregierten Ansatz. Der damalige Export
  lieferte sechs von 25 gelesenen CSV-Dateitypen; fehlende Dateien/Teilblöcke werden nicht
  automatisch durch Defaults ersetzt. Der neue Regionalpfad erzeugt inzwischen alle 25
  vertraglich erfassten CityConfig-Dateien, konsistente Districts/Polygone und Netze und
  besteht den nativen Import-/Simulationslauf. Regionalisierung und fachliche Kalibrierung
  der aus dem versionierten Profil übernommenen Modellparameter bleiben Arbeitspaket 5.
  Der mitgelieferte TRAMIGO-Importsatz ist eine mögliche Basis, enthält aber noch
  Konsistenzfehler. **Details und Messnachweise:**
  [Zielformatprüfung in docs/05](05-validierung.md#mobilecity-zielformat-tramigo-2026-09-15--teil-export-bestätigt-vollimport-offen).
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

## J. Fahrzeugkatalog und externe Konfigurationen — ENTWURF (2026-09-17)

Die externe `vehicle.json` mit Diesel-Lieferwagen und elektrischem Lastenrad wurde syntaktisch
und gegen den aktuellen Fahrzeugpfad geprüft. Der daraus abgeleitete
[Fahrzeugtypen- und Schnittstellenentwurf](03-architektur.md#fahrzeugtypen-und-externe-fahrzeugkonfigurationen--entwurf-2026-09-17)
enthält die Feldbewertung, das Metamodell, Standardvorlagen, den vorgesehenen JSON-/REST-Vertrag
und den gestuften Wirkungsnachweis für die Zielmodelle.

**Entscheidungsvorschlag:** gemeinsame Fahrzeugtypklasse mit komponierten Profilen;
Antriebsvarianten getrennt typisieren, Fahrzeugzustand und Flottenzuordnung eigenständig
führen. Konfigurierbarkeit und tatsächliche Simulationswirkung werden je Adapter ausgewiesen.

**Verifiziert:** JSON-Parsing einschließlich Prüfung auf doppelte Schlüssel und nicht-endliche
Konstanten; zwei eindeutige IDs; beide angegebenen Beladungen innerhalb ihrer Gewichts- und
Volumenkapazität. Codeprüfung von `Scenario`, `PopulationGenerator`, `LegacyExportGenerator`,
`SumoRunner` und `MobileCityExporter`; Abgleich mit offizieller SUMO-Dokumentation.
**Noch nicht umgesetzt:** Fahrzeugkatalog, Import-API, neue Klassen und Laufzeitanbindung.
Es wurden weder neue Simulationsparameter aktiviert noch Simulationsversuche durchgeführt.

## Separate KIT-Adapter und gemeinsame Haushaltsgrundlage (✅ 2026-10-07)

Die Laufwege von actiTopp und mobiTopp/logiTopp verwenden separate Adapter unter
`adapters/`, gebaut aus gepinnten Originalcommits per `git archive`. Der Bootstrap
wendet die früheren Patchverträge nicht mehr an. actiTopp erhält vollständige
Haushalte einschließlich Kleinkindern, tatsächliche Pkw-Anzahlen und einen
expliziten Siedlungstyp. Ganze gezogene Haushalte werden erhalten; der kleine
Personenüberhang wird über Zonen ausgeglichen und in
`population/household-rounding.json` ausgewiesen.

Die Matrizenstufe steht jetzt vor Bevölkerung und Aktivitäten: feste Arbeits-
und Bildungsziele nutzen initiale Pkw-Reisezeiten mit einer ausdrücklich
unkalibrierten Skala von 20 Minuten; beobachtete Pendlerbeziehungen haben Vorrang.
Bekannte grobe Bevölkerungsraster werden gleichmäßig auf 100-m-Zuordnungspunkte
verteilt, ohne zusätzliche Glättung. Neue Szenarien verwenden das ausgerichtete
EPSG:3035-Raster. SUMO übernimmt standardmäßig alle unterstützten Wege des
ausgewählten Tages; positive Mengenlimits bleiben für Tests verfügbar.

**Verifiziert (2026-10-07):** vollständige Backend-Suite mit 224 Tests,
215 bestanden, 9 explizit übersprungen, keine Fehler; 52 Frontendtests und
Produktionsbuild bestanden. Beide Adapter-Builds erfolgreich; der native
actiTopp-Haushaltstest zusätzlich aktiviert und ohne Skip bestanden.
Die vorherigen nativen Wochenlauf-Belege und Grenzen stehen in
[docs/17](17-kit-adapter-und-haushaltskonzept.md) und den dort verlinkten
Prüfberichten. Ein neuer durchgängiger Haushaltslauf über beide Adapter und
fachlich kalibrierte feste Ziele bleiben offen.

## Gemeinsame Straßenrouten GraphHopper → SUMO (✅ 2026-10-07, experimentell)

`population.sharedRoadRoutes` (Default `false`) überträgt konkrete gerichtete
OSM-Wegfolgen für Pkw/Rad und Zustell-Legs nach SUMO. Personenwege und
Zustellstopps verwenden dabei ihre tatsächlichen Nachfragekoordinaten.
Netzversionen, Profil, Punkte, Straßenfolge und Fallbacks werden pro Fahrzeug
festgehalten; nach `duarouter` wird jede zugeordnete Route auf unveränderte
Kantenfolge geprüft. Der Netzimport erhält OSM-Abschnitte und Radzugänge.

**Spikes und Negativbefunde:** Zusammengeführte Kreuzungen und entfernte
Kurzabschnitte verhinderten im Standardnetz eine vollständige Rastatt-Zuordnung.
Zusätzlich fehlte die OSM-Radzugangsübernahme; der Router wählte Rad-Routen
neu. Abschnitte erhalten, `osm.bike-access` und `keep-route-probability=1`
beheben diese Fälle im kleinen nativen Probelauf. Ein DOM-Netzleser wurde wegen
seines Hängers beim großen Netz verworfen; der Adapter liest XML als Stream.

**Verifiziert:** `SharedRoadRoutesTest` prüft Richtung, geteilte Kanten,
Teilpositionen, fehlende/gesperrte Verbindungen sowie veränderte/verlorene Routen.
Der native Rastatt-Test überträgt sechs Fahrten (Pkw, Rad, Lieferwagen und
Lastenrad einschließlich Depot-Rückwegen); alle sechs kommen ohne Umrouting an.
Die API prüft das Realrouting-Gate; das Studio prüft Sichtbarkeit und Auswahl.

**Grenzen und nächste Abnahme:** zusätzliche Gebiete und belastete A/B-Läufe,
Fallbackquote, Netzkosten und Reisezeitvergleich. Statische SUMO-Kantenzeiten
sind als Schätzung von beobachteten TripInfo-Zeiten getrennt. Fuß/ÖV,
stoppspezifische Tourplanung und zeitabhängige Matrizen bleiben offen.
[Vertrag und Artefakte](20-erweiterungen-routenkonsistenz-und-zeitfeedback.md#experimenteller-adapter-umgesetzt-07102026).

**Abschluss-Gates (07.10.2026):** 230 Backendtests, 221 bestanden und
9 explizit übersprungen, keine Fehler; 53 Frontendtests und Produktionsbuild
bestanden. Alle fünf Straßenroutentests einschließlich nativer Kette bestanden
ohne Skip. [Prüfbericht](verification/2026-10-07-shared-road-routes.json).


## Schwerer Güterverkehr und Routenkennzeichnung (✅ 2026-10-10, experimentell)

`freight.enabled=false` ergänzt die bestehende Logistikstufe um normalisierte Tages-OD-
Fahrten. CSV-Import im Studio, Quellen-Snapshot und separater Seed liefern schwere
Solo-LKW (`truck`) und Sattelzüge (`trailer`) zusätzlich zu Personen- und Paketverkehr.
SUMO routet mit Klassenberechtigungen, prüft fehlende schwere Routen/Fahrzeuge und
protokolliert unvollständige Fahrten am Tagesende. Aktives Freight setzt vollständige
Personennachfrage voraus und deaktiviert Modus-Caps. Gütertag und SUMO-Tag müssen passen.

`trafficCategory=private|logistics` und `demandSource` unterscheiden geroutete
Personenfahrzeuge, Paketlogistik und schwere Güterfahrten. Die Karte bietet separate
SUMO-Routenlayer; Fußgänger/ÖV werden weiterhin separat bilanziert. Neue Artefakte sind
`freight/{sources.json,trips.csv,summary.json,execution.json}` sowie
`sumo/{traffic-routes.geojson,traffic-categories.json}`. Standard-Referenztypen verwenden
`HBEFA3/HDV_D_EU6`; Fahrzeugkatalogrollen `heavyTruck`/`articulatedTruck` sind ergänzbar.

**Evidenz:** native Fixture-Kette mit zwei PKW, drei schweren Fahrzeugen und einem
Fußgänger; schwere Fahrzeuge meiden eine `hgv=no`-Strecke, alle drei werden abgeschlossen.
Ein unabhängiger Review fand und prüfte Korrekturen der multimodalen Eingabedateiliste,
der Invalidierung alter Routen und des Regionswechsels bei inaktiven Güterdaten.

**Grenze:** noch kein automatischer Mautimport und keine regionale Kalibrierung.
Der Import verlangt vorbereitete Koordinaten und ganze Fahrzeugmengen einschließlich
Leerfahrten. Nächste zulässige Kante ersetzt noch keinen gerichteten Netzrandkonnektor;
Beladungs-/Flottenzustände und Tourenmodell fehlen. Konzept, recherchierte öffentliche
Datenquellen, vollständiger CSV-Vertrag und nächste Schritte stehen in
[docs/21](21-gueterverkehr-konzept-und-datenquellen.md).


**Gates (2026-10-10):** Backend-Gesamtsuite erfolgreich (238 Fälle, 0 Fehler,
9 Voraussetzungen-Skips), zusätzlicher Schwerlast-Katalogtest erfolgreich;
54 Frontend-Tests und strict-AOT-Build erfolgreich. Der vorhandene Rastatt-SUMO-
Test und der neue multimodale Schwerlast-Fixture-Test wurden tatsächlich ausgeführt.


### Güterverkehr: gerichtete Zugänge und KBA-Referenzimport (✅ 2026-10-12, experimentell)

Der bestehende opt-in-Güterverkehr akzeptiert optional `fromEdge`/`toEdge` am Ende der
OD-CSV. Alte Eingaben bleiben gültig. Zulässige gerichtete Netzkanten sind im Studio als
`sumo/freight-network.geojson` herunterladbar; Existenz, Klasse und Koordinatenabstand
werden pro Lauf geprüft. `freight/edge-mapping.json` hält die tatsächliche Zuordnung fest.

Ein strengerer nativer Test deckte auf, dass der HGV-Sperrnachweis vom 10.10.2026 nicht
isoliert war. `OsmFreightPermissions` überträgt jetzt explizites `hgv=no` einschließlich
Richtungsregeln auf die Lane-Berechtigungen. Nicht unterstützte HGV-Werte werden berichtet;
Gewicht/Höhe/Conditional bleiben offen. Geometrievereinfachung wird deaktiviert, um
Wegsegment-IDs zu erhalten. Evidenz und Korrektur stehen in [docs/05](05-validierung.md#schwere-lkw-osm-sperrübertragung-2026-10-12--ursprünglicher-nachweis-korrigiert).

Der neue Offline-Adapter `scripts/inspect-kba-freight.py` importiert das tatsächliche
KBA-PUF-2025-ZIP: elf CSV und 70.716 Kennwerte. Quellenhash und Qualitätsmarker bleiben
erhalten, unterdrückte Daten werden nicht als null Verkehr behandelt. Die Daten sind
Referenzdaten; Codebuch, Einheiten, Lizenz und regionale Tagesumrechnung sind noch offen.
Bedienung, Datenvertrag und Snapshot: [docs/21, Abschnitt 9](21-gueterverkehr-konzept-und-datenquellen.md#9-gerichtete-zugänge-und-erster-kba-datenadapter-2026-10-12).


**Verifiziert (2026-10-12):** Backend-Gesamtsuite 242 Fälle, keine Fehler, neun
Voraussetzungen-Skips; 54 Frontend-Tests und strict-AOT-Build grün. Drei Python-Importtests
grün sowie tatsächlicher Import des KBA-ZIP. Native Schwerlastprüfung und bestehende
Rastatt-Simulation wurden ausgeführt. Read-only-Widerlegungsversuch prüfte Kantenvalidierung,
Cache-Lebensdauer und richtungsbezogene Lane-Berechtigungen ohne weiteren belegten Fehler.

### SEVAS: einfache NRW-LKW-Sperren per Snapshot (✅ 2026-10-12, experimentell)

Kommunale SEVAS-Restriktionen ergänzen die bisherige OSM-HGV-Übertragung.
Das Studio erlaubt GeoJSON-Upload und separates Opt-in (`freight.sevasEnabled=false`);
der Snapshot bleibt mit normalisiertem Hash und Entscheidungen im Ergebnis erhalten.
Nur unbedingte VZ-253-Regeln ohne Zusatzzeichen, Zeitbedingungen oder Ausnahmen greifen.
Nicht passende/teilweise Kanten, unklare Richtung, unbekannte Schemafelder und gemischte
Regeln desselben OSM-Ways werden übersprungen. Sperren werden vor Routing und ÖV-Vorbereitung
angewendet, andere Lane-Klassen bleiben erhalten. Fehlende aktive Daten scheitern im
Preflight; ungültige Uploads erhalten den bisherigen Snapshot; alte Berichte werden bereinigt.

Ein nativer Test ohne OSM-HGV-Tag beweist allein die SEVAS-Wirkung: PKW nutzen die
Abkürzung, Solo-LKW/Sattelzüge umgehen sie; alle Fahrten schließen ab. Der tatsächliche
Dortmunder Snapshot ergibt mit dem Java-Adapter 14 angewendete Segmente und 77 gerichtete
Kanten. Der unabhängige Read-only-Widerlegungsversuch fand keinen weiteren belegten Fehler,
grenzte aber den fehlenden OSM-Versionsabgleich ein: Versionskennungen werden erhalten,
geprüft wird die Geometrie, nicht die zeitliche Aktualität (`networkVersionVerified=false`).
Vorrangrouten, Teilkantensplitting und Liefer-/Anliegerrechte bleiben offen.
Bedienung und Vertrag: [docs/21, Abschnitt 11](21-gueterverkehr-konzept-und-datenquellen.md#11-sevas-snapshot-im-studio-verwenden-2026-10-12-experimentell).

**Verifiziert (2026-10-12):** Backend-Gesamtsuite 249 Fälle, 0 Fehler, 9 externe
Voraussetzungen-Skips; 55 Frontend-Tests und strict-AOT-Build erfolgreich.
Der SEVAS-Mischverkehrstest wurde nativ ausgeführt. Die erste Gesamtsuite deckte
JSON-`null` beim Laden eines deaktivierten Snapshots auf; korrigiert und durch einen
Roundtrip-Regressionstest abgesichert. Tatsächlicher Java-Snapshot-Abgleich und
unabhängiger Read-only-Widerlegungsversuch abgeschlossen. Keine automatische SEVAS-
Nachfrageableitung oder regionale Verkehrsvalidierung behauptet.

### Maut-OD: realer Dortmund/Unna-Datenpilot (✅ 2026-10-12, Datenvorbereitung)

Vier öffentliche Toll-Collect-API-Abfragen für Juli 2026 wurden mit URLs und Hashes
archiviert. `scripts/prepare-toll-freight.py` bereinigt Perspektivendoppelungen:
3.167 Rohzeilen → 3.163 gerichtete Gemeinde-OD, veröffentlichte gerundete Monatssumme
979.240 → 775.840. Optionaler gerichteter Zugangsmapping-Vertrag erzeugt eine
Kalenderdurchschnitts-Tages-CSV; Mapping, Anteile, unzugeordnete Mengen und Hash bleiben
im Bericht. Ohne Mapping keine erzeugte Tagesnachfrage. Datenschutzrundung auf
Zehnerstufen und Mautnetzein-/austritt-Semantik wurden im Portal bestätigt.

Eine unabhängige Straßen.NRW-Zählbasis für denselben Monat unterscheidet SGV von
SV inklusive Bussen. Noch keine Kalibrierung: vollständiger regionaler Netz-/Transit-
und Stationsabgleich fehlen; größere Overpass-Abfragen scheiterten an HTTP 406/504.
Zehn Python-Aufbereitungstests grün; unabhängiger Review und Provenienzhärtung.
Details, Befunde, Befehle und nächste Abnahmebedingungen in
[docs/21, Abschnitt 12](21-gueterverkehr-konzept-und-datenquellen.md#12-realer-maut-od-datenpilot-dortmundunna-2026-10-12).

### Maut-OD: native Routingprobe und SGV-Teilvergleich (✅ 2026-10-13)

Der Hauptstraßen-Netzabruf Dortmund/Unna gelang. Die produktiven Gütergenerator- und
Kantenprüfungen sowie `duarouter`/SUMO verarbeiteten zwei gerichtete reale Monats-OD
mit ausdrücklich modellierten Zugängen, Klassenanteilen und Tagesprofil:
359 erzeugte → 359 abgeschlossene schwere Fahrten; zwei synthetische PKW-Kontrollen,
keine Teleports oder unerledigten Güterfahrten. Keine volle regionale Population simuliert.

`scripts/compare-freight-counts.py` vergleicht abgeschlossene SGV-Passagen mit kompatiblen
Kalenderdurchschnittszählungen. Amtliche Stationsgeometrien ermöglichen einen vorläufigen
B236-Berghofen-Abgleich; Brechten/Wambeldorf bleiben wegen widersprüchlicher Stationsarten
ungeklärt. Der modellierte Teilbeitrag von 359 gegenüber 2.236 hängt vom Zugangsproxy ab,
keine unabhängige Abdeckungs- oder Kalibrierbehauptung. Oktober-Netz und Juli-Beobachtung
sind zeitlich nicht nachweislich identisch. 17 Python-Tests grün, native Kette ausgeführt;
unabhängiger Review und Randkanten-Härtung abgeschlossen.
Details und Artefakte: [docs/21, Abschnitt 13](21-gueterverkehr-konzept-und-datenquellen.md#13-native-maut-od-routingprobe-dortmundunna-2026-10-13).

### Gemeinsame Nachfrage unabhängig vom Exportziel (✅ 2026-10-21)

Die bisherige SUMO-Kopplung der schweren und importierten Güternachfrage ist
aufgelöst. `TrafficDemand` vereinigt Personenverkehr, Paketlogistik, allgemeine
schwere Fahrten und Unternehmenstouren in einem eigenen versionierten Vertrag
unter `demand/`. Aufbereitung und Aktivierung benötigen kein SUMO. Der
Produktivadapter liest den gemeinsamen JSON-Bestand; seine Straßenrouten und
native Ausführung bleiben nachgelagert. Die vorhandene Personen-Schnittstelle
und Modal-Split-Bedeutung bleiben unverändert.

Ohne Personenlauf wird dessen Beitrag als nicht erzeugt gekennzeichnet; alte
Ergebnisse werden nicht wiederverwendet. Gemeinsame Mengen und neutrale Downloads
sind im Studio verfügbar. Vollständige Pipeline ohne SUMO sowie nativer SUMO-Lauf
mit allen vier Quellen geprüft. [Verträge, Nachweise und Adaptergrenzen](21-gueterverkehr-konzept-und-datenquellen.md#30-gemeinsame-nachfrage-vor-den-zieladaptern-2026-10-21).

### Unternehmenstouren zusätzlich in SUMO ausführen (✅ 2026-10-21, experimentell)

Bestätigter Kontext für Unternehmen, Quelltag/Simulationsdatum, physische Fahrzeuge,
Depotstart/-rückkehr, Haltreihenfolge und Fensterregel sowie manuelle oder lokale
Adress-/Koordinatenzuordnung ergänzen den Importentwurf. Exakte Ausführungsrevisionen
werden ausdrücklich aktiviert und vor der Job-Warteschlange eingefroren.
`companyTours.enabled=false` bleibt der Default. Änderungen entziehen die Auswahl;
aktive ungespeicherte Änderungen sperren den Studio-Start.

Die vorhandenen nativen Fahrzeugketten führen Touren zusätzlich zur Personen- und
Paketnachfrage aus. Routen-/Fahrzeitvorprüfung und eigener Abschlussbericht umfassen
Bedienende, Verspätung, Kundenfenster und Rückfahrt. Zwei PKW plus ein importierter
schwerer LKW mit zwei Kundenhalten und Rückfahrt wurden über Upload/Mapping/Kontext/
Aktivierung nativ ausgeführt und deterministisch wiederholt. Unabhängiger Review
führte zu Hausnummern-, Auswahlzustands- und Berichtsinvalidierungsregressionen.

**Grenzen:** vollständige Touren innerhalb einer Region und eines 24-Stunden-Tages;
keine automatische Mengenüberlappungskorrektur, individuelle Ladungszertifizierung
oder regionale Abnahme der echten Zurbrüggen-Planung. Lade-/Tankmodelle bleiben
zurückgestellt. [Verträge, Bedienung und Nachweise](21-gueterverkehr-konzept-und-datenquellen.md#28-unternehmenstouren-kontext-lokale-ortszuordnung-und-zusätzliche-sumo-ausführung-2026-10-21).

### Unternehmenstouren: Importentwurf und lokales Mapping (✅ 2026-10-14, experimentell)

Das Studio bietet CSV-/XLSX-Upload, positionale Spaltenzuordnung, Haltdetails vor
Bestätigung und JSON-Export eines geprüften Tourenentwurfs. Mehrere Auftragszeilen
werden optional zu einem Halt gruppiert; Standzeit einmal, Kundenfenster je Auftrag.
Quellhash schützt gegen doppelte Uploads. Der lokale LLM-Vorschlag ist separat
`synthesis.company-tour-import.llm-enabled=false` und verarbeitet ausschließlich
Typstatistiken und bekannte Feldbezeichnungen. Manuelle Zuordnung bleibt verfügbar.

**Verifiziert:** zehn neue Backendtests; reale Datei im Speicher mit 222 zugeordneten
Zeilen, 41 Tourgruppen und 187 Haltkandidaten; unabhängiges Excel-Beispiel und isolierte
Browserprüfung. Gesamtbackend 259 Tests/0 Fehler/9 Skips, Frontend 60 Tests und
Produktionsbuild grün. Bestehendes natives SUMO-Testnetz um ausdrücklichen Fußweg
ergänzt, nachdem SUMO 1.28 dort keine begehbaren Kanten heuristisch erzeugte.
**Grenze:** alle Entwürfe `simulationReady=false`; Depot/Fahrzeuge, Geokodierung,
Revisionen und zusammenhängende Tourensimulation sind nächste Pakete.
[Betrieb, API und genaue Abgrenzung](21-gueterverkehr-konzept-und-datenquellen.md#16-unternehmenstouren-csv-excel-importentwurf--2026-10-14-experimentell).

### Unternehmenstouren mit LLM-Mapping (KONZEPT, 2026-10-13)

Unternehmensplanungen sollen als zusätzlicher, nach Herkunft getrennter
Logistikbeitrag in SUMO eingehen. Ein lokales LLM schlägt ein versioniertes Mapping
vor; bestätigte Regeln übernehmen alle Zeilen deterministisch in einen Tourenvertrag.
Aufträge, Halte und Fahrzeugtouren bleiben getrennt. Fehlende Depot-/Fahrzeugangaben
blockieren vollständige Touren statt erfundene Rückfahrten zu erzeugen.

**Stand:** Beispiel lokal strukturell analysiert, Importkomponente noch nicht
implementiert. Quellenstand, Dateibefund, Datenschutz, Region-/Zeitbehandlung,
Doppelzählungsgrenzen und Umsetzungspakete stehen in
[docs/21, Abschnitt 15](21-gueterverkehr-konzept-und-datenquellen.md#15-unternehmenstouren-quellenstand-und-llm-gestützter-import-konzept-2026-10-13).

### Regionale Maut-OD mit mehreren Zugängen (✅ 2026-10-13)

`scripts/expand-toll-access-mapping.py` ergänzt die Offline-Vorbereitung um einen
geometrisch geprüften Mautknoten-Katalog und native Routingkandidaten. Sechs Gemeinden,
neun veröffentlichte Knoten, 28 OD und 72.430 gerundete Monatswerte → 2.336 modellierte
Tagesfahrten; native Java-Gütergenerator-/Kantenprüfung, Routing und SUMO abgeschlossen.
Acht neue Tests sowie 17 vorhandene Aufbereitungstests grün. Unabhängiger Review
reproduzierte Mengen, Abschlüsse und Hashes; alte Phase-2-Ausgaben werden beim
Kandidaten-Neustart invalidiert, native Typen gegen den Kandidaten geprüft.

Drei reine Knotengewichtsvarianten schließen je 2.336 Güterfahrten ohne Teleports
oder Restfahrzeuge ab. Berghofen-Beiträge 518 / 331 / 704 bei gleichen OD-Mengen.
Die vorhergesagte Richtung und Größenordnung der Gewichtswirkung wurden widerlegt;
die Abhängigkeit vom Zugangsprior ist belegt. Keine beobachtete Knotenverteilung,
kein vollständiger Transit und keine Kalibrierqualität behauptet. Methoden, Quellen,
Artefakte und Grenzen: [docs/21, Abschnitt 14](21-gueterverkehr-konzept-und-datenquellen.md#14-mehrere-mautknotenzugänge-und-regionale-od-erweiterung-2026-10-13).

### Overture-Standortinventar für Güterverkehr (✅ 2026-10-15, Offline-Pilot)

`fetch-overture-freight-sites.py` lädt eine feste Places-Veröffentlichung mit exakten
Point-Geometrien; `inspect-freight-sites.py` erzeugt über eine versionierte Kategorienliste
ein quellenbelegtes GeoJSON-Inventar mit Zeilenbilanz und optionaler Gemeindezuordnung.
Die Werkzeuge laufen ausdrücklich außerhalb der Pipeline und aktivieren keine Nachfrage.

**Verifiziert:** echter regionaler Download und Offline-Auswertung, 34 betroffene
Python-Tests grün. Der aktuelle OSM-Vergleich bleibt wegen HTTP 406/500 offen;
eine verbesserte Standortabdeckung oder regionale Güterverkehrsmenge ist nicht belegt.
Quelle, Datenvertrag, Messergebnis und Wiederholung stehen in
[docs/21, Abschnitt 17](21-gueterverkehr-konzept-und-datenquellen.md#17-regionale-betriebsstandorte-aus-overture-2026-10-15-offline-pilot).

### OSM-Standortvergleich und native LKW-Zugangsprobe (✅ 2026-10-15, Offline-Pilot)

Datierter Geofabrik-PBF statt Live-Overpass; gemeinsame Gemeindegeometrien begrenzen
beide Standortinventare. Neue Offline-Werkzeuge bereiten OSM-Standorte und lokale
Straßen auf, erzeugen Dubletten-/Näheprüffälle und kontrollieren mögliche LKW-Kanten.
Keine neue Pipeline-Stufe oder automatisch aktivierte Nachfrage.

**Verifiziert:** echter Quellenvergleich, 54 betroffene Python-Tests und native
Regionalnetz-/Routingprobe. Zusätzliche Übertragung eindeutiger OSM-HGV-Verbote
entfernt vier explizit verbotene Pfade; alle 56 Proben bleiben technisch routbar.
Maß-/Zeitregeln, Restriktionsvollständigkeit und tatsächliche Betriebseingänge sind
weiterhin ungeprüft. Messergebnisse, Negativbefund, Werkzeuge und Wiederholung:
[docs/21, Abschnitt 18](21-gueterverkehr-konzept-und-datenquellen.md#18-lokaler-osm-abgleich-und-lkw-kantenprüfung-2026-10-15-offline-pilot).

### Explizite LKW-Profile und numerische Restriktionen (✅ 2026-10-15, Offline-Pilot)

Versionierte Betriebsprofile referenzieren die vorhandenen Katalogtypen. Der lokale
Prüfer trennt Maße, tatsächliche und zulässige Masse, unbekannte Achslasten sowie
Zeit-/Zugangsregeln. Ein separates Profilnetz sperrt nur explizit nachgewiesene
Verstöße der jeweiligen Fahrzeugklasse. Keine Änderung der Produktivpipeline.

**Verifiziert:** 70 betroffene Python-Tests einschließlich nativer 26-/40-t-Vergleichsprobe.
Die regionale Wiederholung bilanziert alle 56 ursprünglichen Proben; numerische Verstöße
entfallen, zwei Rückfahrten bleiben ausdrücklich nicht berechenbar. Die übrigen Routen
sind wegen weiterer Quellregeln nicht als vollständig befahrbar zertifiziert.
Profile, Quellen, Lastannahmen, Messwerte und Wiederholung:
[docs/21, Abschnitt 19](21-gueterverkehr-konzept-und-datenquellen.md#19-fahrzeugprofile-und-numerische-restriktionen-2026-10-15-offline-pilot).

### Optionale LKW-Profilprüfung und Abbiege-Audit (✅ 2026-10-15)

`freight.profileChecksEnabled=false` ergänzt die bestehende SUMO-Stufe um statische
Standardprofile mit expliziter 26-/40-t-Volllastannahme, klassenbezogene Sperren und
vollständige Bilanz der angeforderten Güterfahrten. Nicht routbare Fahrten und
nachgewiesene Quellverstöße führen mit erhaltenem Bericht zum Fehler; Zeit-/Zweckregeln
und unbekannte Achslasten bleiben sichtbar ungeklärt. Der Overpass-Netzabruf nimmt
jetzt auch Abbiegerelationen samt Mitgliedern auf. Keine neue Stufe oder Python-
Abhängigkeit im SUMO-Kernpfad. Standortanbindung ausdrücklich nur über die nächste
zulässige Straße, ohne separates Einfahrtsmodell.

**Verifiziert:** native Profil-/Umweg-/Fehlerproben, sechs regionale Referenzstandorte
mit 24 unverändert bilanzierten Proben und quellbezogener Abbiege-Audit. Die ursprünglichen
zwei Rückfahrten scheitern an Richtung plus Höhenlimit; die Gegenrichtung auf derselben
Straße routet beide Klassen. Eine abweichende native Abbiegeverbindung wird von keiner
der 54 bisherigen Regionalrouten benutzt. Details, Grenzen, Artefakte und Abnahme:
[docs/21, Abschnitt 20](21-gueterverkehr-konzept-und-datenquellen.md#20-rückfahrten-abbiegerelationen-und-optionale-profilprüfung-2026-10-15).

### Regionaler Güterverkehrs-Gesamttest (❌ Abnahme nicht bestanden, 2026-10-16)

Die produktive Szenariokette verarbeitet im isolierten Fall 1.001 künstliche
Modellpersonen, reale OSM-Daten und 2.336 aus veröffentlichten Maut-Monats-OD
abgeleitete Tagesfahrten. Native logiTopp-Simulation und Netzkonvertierung gelingen;
die Java-DOM-Nachbearbeitung des 1,28-GB-SUMO-Netzes überschreitet 6 GiB Heap vor der
optionalen Profilprüfung und dem LKW-Routing. Der regionale Abschlussnachweis bleibt
offen; der nächste technische Schritt ist die stromweise Netzverarbeitung.

**✅ Korrigiert:** Stufe und Job melden jetzt auch einen `Error` als FAILED und werfen
ihn anschließend weiter. Der ursprüngliche hängenbleibende RUNNING-Status ist im
Regressionstest und durch die tatsächliche regionale Fehlerwiederholung abgesichert.
Quellen, Annahmen, Ressourcenbudget und Negativbeleg:
[docs/21, Abschnitt 21](21-gueterverkehr-konzept-und-datenquellen.md#21-regionaler-gesamttest-mit-profilprüfung-2026-10-16-negativer-abnahmebefund).

### Regionale SUMO-Netze stromweise verarbeiten (✅ umgesetzt, 2026-10-16)

OSM-HGV-, Profil- und SEVAS-Netzänderungen sowie die Netz-/Routenleser verarbeiten
XML-Datensätze einzeln. Relationen und Kanten halten kompakte IDs; Netz-/Routen-
GeoJSON werden Feature für Feature geschrieben. Der gemeinsame Straßenadapter
hasht Netzdateien stückweise. Temporäre Netzdateien ersetzen das Original erst
nach erfolgreichem Abschluss; Sperren, Profile und Nachfrage bleiben unverändert.
Keine neue Abhängigkeit, Stufe oder Heap-Erhöhung.

**Verifiziert:** 275 Backend-Testfälle, 0 Fehler, vier bestehende Skips; 150.000
Kanten in separater 64-MiB-JVM. Die unveränderte 6-GiB-Regionalprobe erreicht jetzt
Profilprüfung und Routing. Die Gesamtabnahme bleibt negativ wegen nachgewiesener
Netzabdeckungs-/Randprobleme; kein vollständiger regionaler SUMO-Lauf behauptet.
[Messung und Grenzen](21-gueterverkehr-konzept-und-datenquellen.md#22-streaming-der-regionalen-sumo-netze-2026-10-16).

### Regionalen schweren Güterpfad nativ abgeschlossen (✅ begrenzt abgenommen, 2026-10-16)

Die separate Reparaturprobe verwendet den vollständigen NRW-Quellsnapshot desselben
Stichtags und einen größeren Straßenpuffer. Sie verändert keine Gütermengen,
Koordinaten, Profile oder Kantenbindungen. Das 2,62-GB-Netz läuft mit dem
Streaming-Code bei unveränderten 6 GiB Java-Heap durch Profilprüfung, Routing,
native SUMO-Ausführung und Geometrie-/Netzlastexport. Alle 2.336 schweren Fahrten
werden beendet, ohne Güter-Teleport oder expliziten Quellverstoß.

**Grenze:** Alle Routen bleiben prüfpflichtig; die Personen-/Paketnachfrage hat
separat ausgewiesene Routingverluste. Daher keine vollständige Gesamtverkehrs-
oder Zugangsabnahme. Der frühere unveränderte Netzfall bleibt negativ archiviert.
[Getrennte Bilanz und offene Schritte](21-gueterverkehr-konzept-und-datenquellen.md#231-native-ausführung-und-getrennte-abnahme).


### SUMO-Routingverluste sichtbar machen und Straßenzuordnung korrigieren (✅ umgesetzt, 2026-10-17)

Die Diagnose verknüpft jede angeforderte ID mit nativen Warnungen. Erlaubtes Wenden
an Sackgassen erhält explizite OSM-Wendeverbote. Klassen- und richtungsbezogene
Straßenbindung sowie eine Netzvorprüfung schützen den vollständigen Gütermodus
vor stillen Verlusten der Personen-/Paketnachfrage. Angeforderte, geroutete,
gestartete und abgeschlossene Fahrzeuge/Personen werden getrennt ausgewiesen;
Fahrräder zählen zur Fahrzeugsumme. Tagesrand und Teleports bleiben eigene Befunde.
Das Studio zeigt diese Bilanz und optionale SUMO-Stufenfehler sichtbar an.

**Regionalbefund:** Die isolierte Wendeoption reduziert 316 fehlende Routen auf 18.
145 Endpunkte haben keine zulässige Straße innerhalb 300 m; ein vollständiger
Kantenvergleich bestätigt die räumliche Suche. Die neue gerichtete Prüfung findet
zusätzlich sechs nicht erreichbare Anfragen und korrigiert elf Richtungen derselben
Straße. Alle 125 automatischen HGV-Kantenbindungen bleiben unverändert.
Keine neue Stufe, Abhängigkeit, Radiusvergrößerung oder separate Betriebseinfahrt.

**Grenze/Rollback:** Die frühere Gesamtabnahme bleibt negativ; Quellpositionen
außerhalb des Radius müssen fachlich geklärt werden. Der negative Zwischenstand
mit reiner Klassenabbildung bleibt dokumentiert. Ein Rückbau darf diese Befunde
nicht als erfolgreiche vollständige Nachfrage ausgeben.
[Detailberichte, Versuche und Regressionen](21-gueterverkehr-konzept-und-datenquellen.md#24-routingverluste-vorprüfung-und-vollständige-ausführungsbilanz-2026-10-17).

**Verifiziert (2026-10-17):** Vollständige Backend-Suite: 286 Fälle, keine Fehler,
vier ausgewiesene Skips. Frontend: 62 Tests grün und Produktionsbuild erfolgreich.
Die 17 gezielten Routing-/Bilanz-/Güterfälle laufen ohne Skip; darunter echte
native Sackgassen-/Wendeverbotsfälle und die lokale regionale Archiv-Regression.


**Produktiver Regionalnachweis (2026-10-17):** Die unveränderte vollständige
`GenerationService`-Kette mit eingefrorenen Produktionsklassen prüft alle 3.839
Anfragen und stoppt die SUMO-Stufe mit FAILED: 145 nicht zuordenbare Endpunkte und
sechs Anfragen ohne gerichteten Pfad, elf korrigierte Richtungen. Es werden keine
Routen-/Statistik-/Abschlussdateien oder Success-Summary vorgetäuscht. Der Job
bleibt gemäß bestehender Optionalstufen-Politik DONE; der konkrete SUMO-Fehler ist
sichtbar. Eingaben, Quell-/Klassenhashes und Ergebnis liegen separat unter
`.runtime/traffic-routing-regional-2026-10-17-final/verified-result.json`.


**Frische native Güterkontrolle (2026-10-17):** Auf dem abschließenden Netz werden
alle 2.336 schweren Fahrten mit ihren neuen nativen Routen separat bis zum
Tagesende ausgeführt: 2.336 angefordert, geroutet, gestartet und beendet; keine
fehlende/unfertige Fahrt, null Teleports. Die Kontrolle isoliert den Güterpfad und
ersetzt keine Gesamtabnahme oder Zugangsfreigabe. Kommando, Abschluss-IDs,
Tripinfo, Statistik und Ladehinweise sind unter
`.runtime/traffic-routing-2026-10-16/heavy-control/` archiviert.


### Quellorte nachweisen und Pkw am Wohnort initialisieren (✅ umgesetzt, 2026-10-19)

`TrafficEndpointOrigins` ergänzt den bestehenden SUMO-Netzbericht um exakte
Quellkoordinaten-Referenzen und Dateien mit Hash/Lesestatus. Mehrdeutige Orte und
unbekannte Herkunft bleiben ausdrücklich sichtbar. Die Diagnose verändert keine
Nachfrage und keine Straßenbindung. `PopulationGenerator` und der Legacy-Pkw-
Export initialisieren Fahrzeuge konsistent am Haushaltswohnort; IDs, Zonenräume
und Seed bleiben erhalten. Fehlende Haushalte verursachen einen Exportfehler.

Die regionale Artefaktwiederholung prüft alle 515 Pkw und die Herkunft aller
bekannten Endpunktfehler; sie ist keine erneute Verkehrsausführung. Eine neue
regionale Vollabnahme bleibt offen.
[Befund und quantitative Evidenz](05-validierung.md#ortsherkunft-der-sumo-restfälle-und-pkw-initialorte-2026-10-19).


**Verifiziert (2026-10-19):** 290 Backend-Testfälle, keine Fehler, vier ausgewiesene
Skips; 19 gezielte Fälle ohne Skip. Neue Regressionen prüfen Haushalts-/Pkw-Orts-
konsistenz einschließlich Zonen-ID-Räumen und fehlendem Haushalt sowie exakte,
mehrdeutige und fehlgeschlagene Quellzuordnungen. Die regionale Artefaktwiederholung
prüft Fahrzeugzahl und alle übrigen Pkw-Attribute unverändert; sie führt keine
neue Verkehrssimulation aus. Befehlslogs und Quell-/Eingabehashes:
`.runtime/traffic-origins-2026-10-19/verification.json`.


### Synthetische Wohn- und Aktivitätsorte straßennah erzeugen (✅ implementiert, 2026-10-19)

Die neue Szenario-Option `population.roadLocatedActivities` (Default `false`)
verankert synthetische interne Wohnorte, Fixziele und Opportunities an gemeinsam
für Pkw, Fahrrad und Fuß freigegebenen öffentlichen Straßen innerhalb ihrer
bisherigen Zone. Das Studio erklärt die Modellannahme und den Fehlerfall.
`RoadLocations` verwendet kopierte Geometrie aus dem ohnehin aufgebauten
GraphHopper-Graphen; keine neue Stufe, Abhängigkeit oder Zufallsziehung.
Der Bericht `population/road-locations.json` weist ursprüngliche/gewählte Orte,
OSM-Weg, Verschiebung, Zonenprüfung und Quellenhash aus. Externe Anker und
Depotkoordinaten bleiben erhalten. Fehlt eine geeignete Straße oder ein
funktionsfähiger Graph, wird die aktivierte Ortswahl mit Fehler abgebrochen.

**Technischer Nachweis:** Paarprüfungen erhalten Personendaten, Haushalts-/Pkw-Zahl,
Zielzonen und Zufallsfolge. Ein nativer Kleinfall führt ausgewählte Punkte mit Pkw,
Fahrrad und Fuß bis zum Abschluss. Die Pipeline prüft auch Abschalten und Entfernen
alter Berichte. Eine gesonderte Gegenprüfung fand keine materiellen Codefehler;
Netzinseln und native Abbiege-/Gehwegregeln bleiben ausdrücklich nachgelagerte
Prüfungen. Die Option bleibt experimentell; keine amtliche Adresszuordnung oder
fachliche Kalibrierung behauptet.


**Negativbefund und Korrektur:** Der erste Regionalversuch verfehlt die Vorab-
Prognose: 145 → 20 Endpunktfehler, zusätzlich acht Verbindungsfehler. Öffentliche
`service`-Straßen sind in GraphHopper für Pkw nutzbar, in SUMOs Standardimport
jedoch nicht. Version 2 schließt solche Standorte und nur über diese Zufahrten
angebundene Pkw-Inseln konservativ aus; Straßenbrücken bleiben gültige Verbinder.
Der Ortsbericht dokumentiert die schwache Hauptkomponente und verworfene Kanten.
Eine zweite Widerlegungsprüfung findet keine neue Codeverletzung, benennt aber
den Ausschluss lokal nutzbarer Inseln und die weiter erforderliche native Prüfung.
[Befund, Quellen und Grenzen](05-validierung.md#straßennahe-orte-negativer-erstlauf-und-native-netzberechtigungen-2026-10-19).


**Native Gehwege:** Version 2 besteht die regionale Netzvorprüfung, verliert im
nativen Routing jedoch drei Fußwege auf schneller befahrenen `secondary`-Straßen.
Version 3 berücksichtigt deshalb zusätzlich die 50-km/h-Grenze der nativen
Gehwegschätzung; unerkannte Tempoformate werden als Ortskandidaten verworfen.
Schnelle Straßen bleiben Pkw-Verbinder. Zwei unabhängige native Regressionen
prüfen Service-Berechtigungen sowie 50-/70-km/h- und Default-Gehwege.


**Fußnetz-Zusammenhang:** Version 3 verliert sechs Fußwege trotz begehbarer
Endpunktstraßen. Version 4 filtert deshalb beide schwachen Hauptkomponenten für
Pkw und Fuß; schnelle Straßen verbinden keine Fußinseln. Ein gepaarter nativer
Gegenfall beweist den vorherigen Fußverlust und danach vollständige Abschlüsse
für Pkw, Fahrrad und Fuß. Die Auswahl bleibt konservativ und experimentell;
beide unabhängig größten Komponenten können lokal geeignete Inseln ausschließen.
Die tatsächliche Routing-/Ausführungsprüfung bleibt verbindlich.


**Verifiziert (2026-10-19):** Vollständige Backend-Suite 300 Fälle, keine Fehler,
vier ausgewiesene Skips. Die neun gezielten Ortsfälle laufen ohne Skip; darunter
native Service-/Tempo-/Fußinsel-Regressionsfälle, drei Seeds für Demografie und
Zufallsfolge, LAEA-Rundung sowie Abschalten mit Entfernung alter Berichte.
Frontend: 63 Tests grün, Produktionsbuild erfolgreich. Befehlslogs unter
`.runtime/road-locations-2026-10-19/` (`backend-v4-tests.log`,
`locations-v4-tests.log`, `frontend-review-tests.log`, `frontend-review-build.log`).


**Regional verifiziert, Ausführung prüfpflichtig:** Version 4 routet alle 3.839
exportierten Tag-0-Anfragen ohne Verluste oder Koordinatenwarnungen; sämtliche
3.624 Fahrzeuge werden gestartet und beendet, einschließlich aller 2.336 schweren
Fahrten. 214 von 215 Fußwegen sind beendet, der letzte überschreitet rechnerisch
das feste Tagesende. Zwei Teleports (Rad/Pkw) an einer gemeinsamen Tram-/Straßen-
Signalstelle halten die Ausführung auf `review-required`. Das Studio zeigt diese
Prüfung ausdrücklich neben den vollständigen Mengen an. Die Option bleibt
standardmäßig aus; eine vollständig teleportfreie Ausführung wird nicht behauptet.
[Abschließende Bilanz und offene Signalprüfung](05-validierung.md#abschluss-version-4-routing-und-mengenerhalt-bestätigt-2026-10-19).

### Öffentliche Lade- und Tankmöglichkeiten — ✅ Stationsbestand umgesetzt (2026-10-20)

- **Quellen und Karte:** `EnergyInfrastructure` verbindet den bestehenden
  BNetzA-Registerstand mit erhaltenen Ladepunkt-/Anschlussdetails und gezielten
  OSM-Stationen (`nwr`, Objektart/ID/Version/Zeitstempel). Strom, CNG, LNG, LPG
  und H₂ bleiben getrennte Angebote. Steuerung:
  `energyInfrastructure.enabled=false` als Default; Frontend-Checkbox
  „Stationsbestand erfassen (experimentell)“, getrennte BNetzA-/OSM-Auswahl.
- **Wiederholung und Qualität:** Regionale OSM-Eingaben werden mit SHA-256
  eingefroren, wiederverwendet und auf die gespeicherte Region geprüft; Upload
  und bewusste Erneuerung sind möglich. Quellenfehler bleiben `FAILED` mit
  Qualitätsbericht. Private/inaktive Stationen werden ausgeschlossen,
  ungeklärter/beschränkter Zugang ist in der Karte ausdrücklich zuschaltbar.
  Mögliche Mehrfachmeldungen werden markiert; Stecker und OSM-Kapazitäten werden
  nicht zu BNetzA-Ladepunkten addiert.
- **Pipeline:** Einbettung in die vorhandene Quellenstufe, keine neue Stufe,
  Nebenläufigkeit oder Zufallsziehung. MobileCity verwendet denselben
  Registerbasisbestand; seine bisherige Aggregation bleibt erhalten.
- **Verifiziert:** sechs gezielte Backend-Fälle einschließlich vollständiger
  Offline-Pipeline, Wiederholung, Abschalten, Quellenfehler, Uploadschutz,
  Mirror-Abfrage und Register-/OSM-Zählung. Backend insgesamt 311 Fälle,
  null Fehler, vier Skips; Frontend 67 Tests und Produktionsbuild grün.
  Reales Dortmunder Registerbeispiel mit **359 Einrichtungen / 487 Ladepunkten**
  sowie Gas-/H₂-Zugangsfilter mit ausdrücklich benannten Testdaten im Browser
  geprüft. [Befund und Grenzen](05-validierung.md#öffentlicher-stationsbestand-registerdetails-und-kartenprüfung-2026-10-20).
- *Offen:* H2-Stations-API mit produktivem Token, gemeinsame MobileCity-Ableitung
  aus dem Stationsvertrag, physische Einfahrten, vollständige Fahrzeugeignung und
  operative Lade-/Tankplanung. Lokale Netzprüfung und externe Referenzbilanz sind
  in der folgenden Ausbaustufe umgesetzt.
  [Bedienung und nächste Schritte](19-fahrzeugimport-und-modellbegriffe.md#öffentliche-lade--und-tankinfrastruktur-bestandsprüfung-und-integrationsplan-2026-10-20).

### Falsche Bahnsignalblockade bei reinem Straßenverkehr (✅ korrigiert, 2026-10-20)

`RoadSignalPolicy` entfernt importierte Bahnsignalsteuerung nur bei nachgewiesen
schienenfreier Nachfrage. Duale Straßenampeln werden erhalten, native
Signalübertragungen auf Kreuzungscluster separat geprüft. Opake ÖV-Provider
behalten die bisherige Steuerung; falsch deklarierte Schienen-/unbekannte Typen
und ungeprüfte XML-Includes führen vor Routing/Simulation zum Fehler. Der
Quellen-/Importbericht liegt unter `sumo/road-signals.json`.

**Verifiziert:** Gepaarte native Kontrollfälle und regionaler Vergleich bestätigen
zwei → null Teleports bei vollständig erhaltener Nachfrage. Der spät gestartete
Fußweg am festen Tagesende bleibt getrennt als prüfpflichtig ausgewiesen;
`roadLocatedActivities` bleibt standardmäßig aus. Kein Fahrberechtigungs-,
Teleport- oder Tageslängen-Workaround.
[Befund, Gegenversuche und regionale Abnahme](05-validierung.md#gemeinsame-tram-straßen-signale-nativer-gegenversuch-und-importkorrektur-2026-10-20).

### Stationszufahrten und Fahrzeug-Energiebilanz — ✅ erste Stufe (2026-10-20)

- **Gerichtete Netzprüfung:** Opt-in `energyInfrastructure.verifyNetworkAccess`
  (Default aus), Suchradius `maxSnapDistanceM=50`. `EnergyNetworkAccess` bindet
  Stationsmeldungen an klassenzulässige gerichtete Kanten/Spuren und prüft
  Zu-/Abfahrten mit getrennten nativen Routing-Proben. Bekannte Zugangs-/Maßregeln
  auf dem gesamten getesteten Pfad werden berücksichtigt; unbekannte/bedingte
  Regeln bleiben prüfpflichtig. Der Bericht unterscheidet Routennachweis,
  Sperren, fehlende Zuordnung und weiterhin ungeprüfte physische Einfahrt.
- **Identität und Zustand:** Opt-in `vehicleOperations.enabled` (Default aus),
  versionierter Zuordnungsvertrag mit festen physischen Fahrzeug-IDs für
  bestehende Quellfahrten. Der gebundene Fahrzeugtyp bleibt stabil. Eine externe
  Referenzbilanz führt kWh bzw. Liter über native Fahrtsegmente fort, mit
  deklariertem Verbrauch und tatsächlich gefahrener Distanz. Fehlende Werte,
  Zeit-/Ortsbrüche, Teleports, Defizite und Reserveunterschreitungen bleiben
  sichtbar; es gibt keinen stillen Energie-Reset.
- **Studio und Artefakte:** Getrennte Checkboxen, Suchradius/Reserve,
  servergeprüfter JSON-Import für Fahrzeuge/Fahrtzuordnungen, Beispiel und
  Downloads für Fahrtschlüssel, Netzbericht und Energiebilanz. Andere
  Konfigurationshashes sowie verspätete Antworten werden verworfen. Beim
  Abschalten/Neustart werden alte zugehörige Ergebnisse entfernt.
- **Verifiziert:** nativer Spike über Routing und Simulation, 320 Backend-Fälle
  ohne Fehler (vier bekannte Skips), 70 Frontend-Tests und Produktionsbuild grün.
  Isolierte vollständige Pipeline mit vorbereiteter Testnachfrage: zwei native
  Fahrten, eine explizite physische ID, fortgeschriebene Bilanz **30 → 29,827572 kWh**.
  Zwei Teststationen ergeben zehn Profilprüfungen: fünf zugeordnet, fünf durch
  die niedrige Zufahrt gesperrt. 30 separate Proben verändern die Nachfrage
  nicht. Browserdarstellung mit Reportdownloads geprüft.
  [Befund und Prüfgrenzen](05-validierung.md#stationszufahrt-und-fahrzeugkontinuität-native-abnahme-2026-10-20).
- *Offen:* physische Stationszufahrten und vollständige Bedienbarkeit,
  zusammenhängende native Flottenausführung, dynamische Batterie-/Tankmodelle,
  Lade-/Tankhalte samt Zeitfolgen und Gas-/H₂-Antriebsverträge. Die native
  Simulation verwendet weiterhin einzelne Fahrtsegmente und erzwingt die
  Referenz-Energiegrenzen noch nicht.

### Native Güterfahrzeugketten — ✅ UMGESETZT (2026-10-21)

- **Problem und Verhalten:** Bisher startete jede zugeordnete Etappe ein eigenes
  SUMO-Fahrzeug. `vehicleOperations.chainTrips` (Default `false`) führt die
  vorhandenen Etappen jetzt unter einer nativen Fahrzeug-ID mit Straßenhalten
  aus. Zustellbedienzeiten und verspätete Ankünfte verschieben Folgefahrten;
  Rückfahrten starten am vorherigen Halt. Widersprüchliche Orte brechen mit
  einem eigenen Bericht ab. Nachfrage und Fahrzeugzahl werden getrennt bilanziert.
- **Integration:** Bestehende SUMO-Stufe, keine neue Abhängigkeit oder Pooländerung.
  Explizite Zuordnungen aus `vehicles/trip-identities.json`, Lieferwagenbeispiel
  und Schalter im Studio. Nicht zugeordneter Verkehr bleibt erhalten. Energie-
  und Tank-/Ladeplanung sind nach Nutzerentscheidung zurückgestellt; weitere
  Arbeit richtet sich auf Güterverkehr und dessen Simulationsintegration.
- **Verifiziert:** Native Lieferwagen-Tour mit drei Etappen einschließlich
  Rückfahrt, Bedienzeit und Verspätung; zwei schwere Güterfahrten unter einer
  Lkw-ID; Ortsbruch, Tagesende, Wiederholung und Abwahl. Vollständiger Pipeline-
  Prüffall mit vorbereiteten Nachfrage-/Tourdateien: fünf Güteretappen mit zwei
  physischen Fahrzeugen plus ein Pkw; sechs Etappen und drei native Fahrzeuge
  vollständig, null Teleports. Die Nachfragesimulation selbst war dabei deaktiviert.
  [Evidenz und Grenzen](05-validierung.md#native-güterfahrzeugketten-2026-10-21),
  [Bedienung und Verträge](03-architektur.md#native-fahrzeugketten-für-den-güterverkehr-2026-10-21).

### Formatübergreifender Firmen-Tourenimport — ✅ UMGESETZT (2026-10-22)

- **Änderung:** Gemeinsame Quellenhülle für CSV/TSV/XLSX, JSON/XML, TXT/DOCX
  und textbasierte PDF; opt-in lokale LLM-Analyse für Tabellenregeln und belegte
  Dokumentzeilen. Vorschau und ausdrückliche Bestätigung bleiben notwendig.
- **Profile:** Bestätigte Regeln szenariolokal speichern; Strukturwechsel bei
  Tabellen ablehnen, Dokumente mit frischen Zitaten erneut auswerten.
- **Gate/Rollback:** `llm-enabled=false` und `source-analysis-enabled=false`
  deaktivieren die Quellinhaltsanalyse; manuelles Tabellenmapping bleibt nutzbar.
- **Grenzen:** Kein OCR, kein universeller Binärformatparser und kein Nachweis
  semantischer Vollständigkeit allein durch Quellzitate. PDF benötigt lokales
  Python mit `pypdf`. Keine neue Pipeline-Stufe oder Exportabhängigkeit.
- **Verifiziert:** Adapter-, Negativ-, Profil- und Bestätigungsprüfungen;
  Messstand und lokale Modellprobe im [Befund](05-validierung.md#formatübergreifender-tourenimport-2026-10-22).
  [Vertrag und Bedienung](21-gueterverkehr-konzept-und-datenquellen.md#31-formatübergreifender-tourenimport--umgesetzt-2026-10-22).

### Gleiche Unternehmenstouren nicht mehrfach auswählen — ✅ UMGESETZT (2026-10-23)

- **Problem:** Dateihash und physisches Fahrzeug erkennen überarbeitete Dateien
  mit derselben fachlichen Tourkennung nicht zuverlässig als dieselbe Planung.
- **Änderung:** Aktivierung und Einfrieren der gemeinsamen Nachfrage verweigern
  doppelte ausgewählte Schlüssel aus Unternehmen, Simulationsdatum und
  Quell-Tourkennung. Ausgeschlossene Touren bleiben unberücksichtigt; unterschiedliche
  Unternehmen oder Tourkennungen bleiben additiv. Fehlgeschlagene Aktivierung
  überschreibt keine gespeicherte Auswahl.
- **Bedienung/Rollback:** Frühere Planung bewusst abwählen oder kollidierende Tour
  ausschließen, neue Revision danach auswählen. Keine neue Pipeline-Stufe,
  Betriebsabhängigkeit oder Artefaktversion; bestehende eindeutige Auswahlen
  bleiben kompatibel.
- **Grenzen:** Kein unscharfer Adress-/Auftragsvergleich, keine automatische
  Ersetzung. Statistische/synthetische Nachfrage wird weiterhin nicht dedupliziert.
- **Verifiziert:** Vier Integrationstests in `CompanyTourRevisionsTest`;
  [Befund und Gates](05-validierung.md#tourenrevisionen-vor-der-gemeinsamen-nachfrage-2026-10-23).

### Extern beginnende/ endende Unternehmenstouren — ✅ ERSTER ADAPTER (2026-10-23)

- **Änderung:** Externe Start-, End- und Haltorte per ausdrücklicher Kontextfreigabe
  übernehmen; Originalplanung im gemeinsamen Nachfragebestand unverändert halten.
  Der regionale Adapter klassifiziert Binnen-, Ein-/Ausfahrts-, Durchgangs- und
  Außenetappen und führt regionale Gebietsbesuche in SUMO aus.
- **Modellannahme:** Grenzpunkte aus geraden Planverbindungen, Zeiten proportional
  interpoliert. Keine externe Straßennetz-Routung oder externe Bedienung simuliert.
- **Identität/Bilanz:** Wiedereinfahrten als getrennte native Besuche desselben
  physischen Fahrzeugs; regionale Abschlüsse getrennt von vollständigen Touren.
  Projektionsbericht und bestätigte Regionsgrenzen werden mitgeschrieben.
- **Gate/Rollback:** `regionalProjectionConfirmed=false` bleibt Standard und stellt
  die bisherige Sperre externer Orte wieder her. Keine neue Pipeline-Stufe oder
  neue externe Laufzeit. 24-Stunden- und Fahrzeugprüfungen bleiben bestehen.
- **Verifiziert:** Nativer Zweibesuch-LKW mit privatem Verkehr, Originalnachfrage
  unverändert, keine Kundenbedienung am Grenzaustritt, Wiederholung identisch.
  [Befund/Gates](05-validierung.md#regionale-unternehmenstouren-mit-externen-orten-2026-10-23).


### Netzbasierte Gebietsbesuche — ✅ UMGESETZT (2026-10-23, experimentell)

- **Problem/Verhalten:** Die gerade Verbindung externer Originalorte konnte reale
  Durchfahrten verfehlen. Mit `networkProjectionRequested=true` erzeugen gerichtete
  klassenabhängige Straßenrouten alle regionalen Besuche und feste Straßenfolgen.
- **Nachfrage/Bilanz:** Vollständige Originalnachfrage bleibt erhalten; regionale
  Abschlüsse und physische Fahrzeugidentität bleiben getrennt bilanziert.
  Eintrittszeiten beruhen weiterhin auf Planzeiten, externe Bedienung bleibt offen.
- **Gate/Rollback:** Netzoption standardmäßig `false`, zusätzlich zur regionalen
  Freigabe. Fehlende vollständige Netzabdeckung stoppt den Lauf. Ausschalten stellt
  die ausdrücklich bestätigte geometrische Projektion wieder her.
- **Verifiziert:** Native gekrümmte Straßenroute mit Gebietsbesuchen trotz verfehlender
  Gerade, feste Straßenfolge, Wiederholung und fehlende Netzabdeckung. 365 Backendtests,
  keine Fehler, vier optionale Skips; 83 Frontendtests und Build grün.
- **Reale Abnahme:** Zurbrüggen-CSV vollständig importiert und exportiert; 222 Zeilen,
  41 Tourgruppen, 187 Haltkandidaten. Aktivierung ohne Betriebsdaten abgewiesen.
  Bestätigte Depots, Fahrzeugzuordnung, Koordinaten und Region fehlen weiterhin.
- **Offen:** Interaktive Netzvorschau/manuelle Gateways und mehrtägige Touren.
  [Befund](05-validierung.md#netzbasierte-unternehmenstouren-und-zurbrüggen-import-2026-10-23).


### Rückfragen im Tourenimport — ✅ UMGESETZT (2026-10-24)

- **Mechanik:** Der Assistent fragt anhand des bearbeiteten Kontexts nach fehlenden
  Betriebsangaben, auch ohne LLM. Eine gemeinsame Depotantwort gilt für alle
  ausgewählten Touren; unterschiedliche Start-/Endadressen sind je Tour möglich.
- **Persistenz:** Optionales Kontextfeld `depotAddresses` mit Tour-IDs. Antworten
  bleiben nach Speichern/Neuladen erhalten. Neue Adressen setzen alte Positionen
  und die Ortsbestätigung zurück; Koordinatenfragen bleiben bis zur Zuordnung offen.
- **Prüfung:** Backend-Aktivierung erfordert weiterhin vollständige Betriebsdaten.
  Leere Adressfelder ändern keine Altimport-Revisionen; Adressantworten werden
  revisionswirksam gespeichert. Keine neue Pipeline-Stufe oder externe Laufzeit.
- **Verifiziert:** `CompanyTourExecutionTest` prüft Persistenz, fehlende Koordinaten,
  Aktivierungssperre, Revisionswechsel und fremde Tour-IDs. Komponentenprüfungen
  decken verbleibende Rückfragen, ausgeschlossene Touren und ungültig gewordene
  Koordinaten ab. UI-Probe mit allen 41 Zurbrüggen-Tourgruppen: gemeinsame Antwort
  gespeichert, keine fehlenden Fahrzeug-/Koordinatenangaben verborgen.
- **Offen:** Reale Ausführungsabnahme benötigt weiterhin bestätigte Koordinaten,
  Fahrzeuge, Depotzeiten und Modellregion. Freie LLM-Antwortdialoge und automatische
  Adressgeokodierung sind weitere Erweiterungen.
  [Befund](05-validierung.md#rückfragen-und-depotantworten-im-tourenimport-2026-10-24).

  **Gates (2026-10-24):** 366 Backendtests ohne Fehler, vier optionale Skips;
  85 Frontendtests und Produktionsbuild grün.


### Lokale Ortsvorschläge und regionaler Tourenpilot — ✅ UMGESETZT (2026-10-24)

- **Mechanik:** Optionaler Loopback-Geocoder liefert begrenzte, gecachte Vorschläge.
  Nutzer wählen Koordinaten explizit; die Sammelübernahme behandelt nur eindeutige
  Treffer. Region und fehlende Betriebsangaben sind im Import sichtbar.
- **Steuerung:** `synthesis.company-tour-import.geocoding-enabled=false`;
  Endpunkt, Timeout und Batchgröße konfigurierbar. Lokaler NRW-Index über separate
  CLI-Werkzeuge, keine neue Generierungsstufe oder öffentliche Adressübermittlung.
- **Verifiziert:** HTTP-Vorschläge → bewusste Kontextentscheidung → gemeinsame
  Nachfrage → natives SUMO. Vollständige Gates: 371 Backendtests ohne Fehler,
  vier bestehende optionale Skips; 86 Frontendtests, Produktionsbuild und zwei
  Pythonprüfungen grün. Reale Quelldaten in einem begrenzten regionalen Pilotlauf
  erfolgreich; vollständige Unternehmensabnahme bleibt offen.
- **Grenzen:** Manueller Browser-Gegentest nicht ausgeführt: automatische
  Freigabeprüfung blockierte den lokalen Frontendstart wegen ihres Nutzungslimits.
  Fehlende Ortszuordnungen, reale Flotten-/Zeitbestätigung und Gesamtbestand-Abnahme
  bleiben offen. Freie LLM-Antwortdialoge sind keine Funktion dieser Erweiterung.
  [Befund und Pilotannahmen](05-validierung.md#lokale-geokodierung-und-regionaler-zurbrüggen-pilot-2026-10-24).


### Tourenbestand: fortsetzbare lokale Ortszuordnung — ✅ UMGESETZT (2026-10-26)

- **Mechanik:** Ortssuche durchläuft auch nach leeren/mehrdeutigen Antworten weitere
  Pakete. Explizite Neusuche aktualisiert alte Cache-Antworten. Vorherige Ergebnisse
  bleiben in derselben Importsitzung nach Kontextspeicherung verfügbar.
- **Daten:** Formatnormalisierung, geprüfte Gemeinde-Aliasse, ausdrücklich optionale
  amtliche Flurstücksvorschläge und lokaler OSM-Abgleich. Quellenkorrekturen werden
  als Modellannahmen markiert. Kein automatischer öffentlicher Geocoder und keine
  neue Generierungsstufe; Geokodierung bleibt standardmäßig deaktiviert.
- **Verifiziert:** 372 Backendtests ohne Fehler, vier bestehende optionale Skips;
  87 Frontendtests, Produktionsbuild und sechs Pythonprüfungen grün. Browserprobe
  von Neusuche bis zusätzlicher Auswahl samt persistiertem Kontext erfolgreich.
- **Grenzen:** Einzelne Quelladressen bleiben ohne belastbaren Treffer gesperrt.
  Generische Fahrzeuge, Depotzeitpuffer und gewählte Ortsrepräsentanten sind
  dokumentierte Modellannahmen. Die regionale Bestandsprüfung ist von einer
  tatsächlichen Betriebsabnahme und externer Kundenbedienung zu unterscheiden.
  [Messwerte und Abnahmestand](05-validierung.md#tourenbestand-und-fortsetzbare-ortssuche-2026-10-26).


### Netzmetadaten für große Unternehmenstouren-Abzüge — ✅ UMGESETZT (2026-10-26)

- **Auslöser:** Der Abnahmelauf am großen lokalen Netz belegt nahezu den gesamten
  6-GB-Java-Heap (6.197.630 von 6.291.456 KiB). Der Versuch wird zur Überarbeitung
  der internen Datenhaltung unterbrochen; das ist kein erfolgreicher Ausführungsnachweis.
- **Mechanik:** Länge und Maximalgeschwindigkeit je Unternehmens-Fahrzeugklasse in
  einem primitiven Kantendatensatz statt mehrerer String-/Double-Maps. Unveränderte
  Fahrstreifenberechtigungen, Geschwindigkeitsmaxima, Routing- und Zeitregeln.
- **Verifiziert:** Vollständige Backend-Suite ohne Fehler und regionaler nativer Bestandslauf erfolgreich;
  [gemessener Abschlussstand](05-validierung.md#tourenbestand-und-fortsetzbare-ortssuche-2026-10-26).


### Regionale Vergleichsprüfung: Signal-Zufahrten und Tagesnachlauf — ✅ UMGESETZT (2026-10-26)

- **Auslöser:** Die erzeugte regionale Pkw-Nachfrage enthält 23 am Dienstag beginnende
  Fahrten mit geplanter Ankunft nach Mitternacht. Der feste SUMO-Endzeitpunkt 86.400 s
  berücksichtigt diesen Überhang nicht. Die unveränderte Tagesauswahl erhält nun einen
  Nachlauf bis mindestens eine Stunde nach der spätesten geplanten Personenankunft bzw.
  ausgewählten Straßenabfahrt. Mengen- und Teleportprüfungen bleiben maßgeblich;
  der Nachlauf garantiert keinen Abschluss unter beliebiger Überlastung.
- **Signalprüfung:** Zwei duale Signalquellen sind über jeweils zwei Split-Kanten
  nach 9,51 bzw. 10,87 m mit einer nativen Ampel verbunden. Die Prüfung verfolgt
  nun auch kurze Ketten: jede ausgehende Verzweigung muss innerhalb insgesamt
  25 m an einer Ampel enden. Schleifen, Sackgassen und unkontrollierte oder längere
  Äste bleiben Fehler. Keine Änderung der importierten Steuerungen oder OSM-Daten.
  Zusätzliche Streaming-Durchläufe laden ausschließlich benötigte Fortsetzungskanten;
  maximal 32 Kanten pro Pfad, konservativer Abbruch bei weiterer Aufteilung.
- **Verifiziert:** Vollständige Offline-Backend-Suite: 374 Tests, keine Fehler,
  vier bestehende optionale Skips. Zusätzlich drei native Signal-/Tagesgrenzentests
  erfolgreich, einschließlich tatsächlicher Pkw-Ankunft nach Mitternacht.
  Keine Frontendänderung, neue Abhängigkeit oder Pipeline-Stufe.
- **Regionaler Nachweis:** Gesamtfreigabe nicht bestanden: unvollständige private
  Ortszuordnung, B vor der Simulation abgebrochen. Unternehmenstouren bleiben in
  der gemeinsamen Nachfrage und Projektion erhalten. Keine A/B-Wirkungskennzahlen.
  [Vergleichsprotokoll und Ergebnis](05-validierung.md#regionale-nachfrage-mit-unternehmenstouren-2026-10-26).


### Straßenkoordinaten für Bildungsziele und Außen-Opportunities — ✅ UMGESETZT (2026-10-26)

- **Bildung:** Eigene `SynthesisSimulationPerson` im separaten logiTopp-Adapter
  erhält exportierte feste Koordinaten für Primary/Secondary/Tertiary, wenn die
  gewählte Zone zur festen Zone passt. Die KIT-Ortsauswahl läuft davor unverändert,
  damit ihr Zufallsverbrauch erhalten bleibt. Andere Zielzonen und nicht feste
  Bildungsziele folgen der vorhandenen Auswahl. Eigene Unterklasse, unveränderte KIT-Quellen/JARs.
- **Außen-Opportunities:** Unter `roadLocatedActivities` werden synthetische
  Opportunities auch in Außenzonen auf konservative gemeinsame Pkw/Rad/Fuß-Straßen
  gelegt. Gleiche Außenzone, höchstens 1.500 m Verschiebung, sonst Fehler.
  Feste Außenanker bleiben unverändert. Ursprungs-/Zielpunkt, Straße und Verschiebung
  erscheinen im Ortsbericht; keine zusätzlichen Zufallszahlen. Keine neue Pipeline-Stufe,
  Abhängigkeit oder Konfigurationsachse.
- **Regressionen:** Backend 377 Tests ohne Fehler, vier optionale Skips;
  sieben Adaptertests grün, separater Adapter gebaut. Gegenproben sichern
  Zonen-/Distanzgrenze, fehlende Straßen, Außenanker-Cache und ursprüngliche KIT-Auswahl.
- **Neuerzeugung:** Alle 213.527 Personenwege behalten Identität, Modus, Zeiten,
  Zonen, Distanzen und Legs exakt. 88 Außen-Opportunities mit maximal rund 689 m
  Verschiebung. Technische A/B-Abnahme am 27.10. bestanden: sämtliche Pkw in
  beiden Fällen und alle ausgewählten regionalen Unternehmensetappen vollständig;
  empirische Kalibrierung bleibt gesondert offen.
  [Native Abnahme und Grenzen](05-validierung.md#korrigierte-ortsübergabe-regionaler-wiederholungslauf-2026-10-26).


### SUMO-Szenarioseed auch ohne physische Fahrzeugketten — ✅ UMGESETZT (2026-10-27)

- **Auslöser:** Native Kontrolle zeigt Standardseed 23.423 im reinen Pkw-Lauf,
  expliziten Szenarioseed im Lauf mit Lkw-Ketten. Gleiche Szenariometadaten allein
  beweisen keine gleichen nativen Prozessparameter.
- **Korrektur:** Konstruktorseed initialisiert den Runner; jede SUMO-Ausführung
  erhält ihn explizit. Szenariokonfiguration bleibt maßgeblich beim bestehenden
  `useFreight`-Einstieg. Kein neues Flag oder Eingriff in KIT-Quellen.
- **Verifiziert:** Native Gegenprobe ohne Ketten schreibt den angeforderten Seed 777
  selbst in den Ergebnisheader. Komplette Backend-Suite: 377 Tests, keine Fehler,
  vier optionale Skips. [Regionaler Nachlauf und Vergleich](05-validierung.md#korrigierte-ortsübergabe-regionaler-wiederholungslauf-2026-10-26).

### Hintergrund-Lkw und Näherungsorte — ✅ UMGESETZT (2026-10-27)

- **Gemeinsame Nachfrage:** Monatliche Maut-OD mit Gemeindegeometrien werden lokal
  automatisch auf die Region zugeschnitten und an das nächste erreichbare
  Lkw-Kantenpaar angebunden. Das Ergebnis nutzt den bestehenden gerichteten
  Güterverkehrs-Upload und wird vor dem SUMO-Export gemeinsam mit Personen- und
  Unternehmenstouren geführt. Keine neue Pipeline-Stufe oder automatische
  Kürzung der Unternehmenspläne; Überschneidungen werden ignoriert.
- **Näherungsorte:** Fehlende Hausnummern erhalten lokale Nachbarvorschläge mit
  sichtbarer Herkunft. Die Unternehmens-Netzprojektion unterstützt ausdrücklich
  als Näherung gekennzeichnete Orte bis fünf Kilometer zum nächsten erreichbaren
  Straßenpaar. Restriktionen bleiben wirksam, detaillierte Zufahrten entfallen.
- **Mengenanpassung:** Ein begrenzter Hintergrund-Mengenfit unterstützt feste
  Unternehmensbeiträge, Zeit-/Klassen-/Netzhashprüfungen und separate Prüfzähler.
  Sein Ergebnis verlangt eine erneute native Ausführung; eine lineare Anpassung
  wird nicht als empirische Gesamtvalidierung ausgegeben.
- **Verifiziert:** Native gemischte Regression in zwei Testregionen mit jeweils
  drei Seeds. Reale Datenaufbereitung und Grenzen stehen im
  [Befund](05-validierung.md#regionaler-hintergrundverkehr-und-näherungen-2026-10-27),
  Bedienung und Datenverträge in
  [Abschnitt 39](21-gueterverkehr-konzept-und-datenquellen.md#39-regionaler-hintergrundverkehr-und-vereinfachte-ortszuordnung-2026-10-27).

<a id="datenquellen-import-umsetzungsstand"></a>

### Datenquellen und gemeinsamer Importkern — ✅ ERSTER SCHNITT UMGESETZT (2026-10-28, experimentell)

- **Import und Auswahl:** `Scenario.dataPlan.enabled=false` erhält den Bestandspfad.
  Reader und Feldzuordnung werden mit dem Tourenimport geteilt. Neue Fachadapter
  normalisieren Bevölkerungspunkte, Arbeitsplatzgewichte und schwere Fahrzeug-OD
  in unveränderliche Revisionen; Uploads aktivieren keine Daten automatisch.
- **Studio und Pipeline:** „Daten & Quellen öffnen“ verbindet Import, Mapping,
  Herkunft, explizite Quellenwahl und serverseitige Vorprüfung. Dieselbe
  Resolverregel bereitet Generierungen vor; Quellenplan, Eingabelock und
  Anwendungsbericht werden im Manifest verlinkt. Mengenannahmen für schwere
  Fahrzeuge sind ausdrücklich als Annahme ausgewiesen und brauchen positive
  vorgegebene Tagesmengen.
- **Verifiziert:** Backend-Gesamtsuite 389 Tests, keine Fehler, vier optionale Skips;
  abschließend zehn fokussierte Import-/Pipelinefälle inklusive Bytevergleich des
  ausgeschalteten Plans gegen reine Legacy-Auswahl. Frontend 92 Tests grün und
  Produktionsbuild erfolgreich. Echte Browserkette vom JSON-Upload bis zu drei
  schweren Fahrten im isolierten Offlinefall erfolgreich.
- **Offen:** Vollständige Bestandsmigration, globale geordnete Quellenprofile,
  installierbare Datenpakete und Dienstconnectoren. Das Ersatzprofil ist kein
  empirisch kalibriertes Güterverkehrsmodell.
  [Verifikation und Grenzen](05-validierung.md#datenquellenimport-und-quellenplan-2026-10-28--technischer-schnitt-bestanden),
  [Vergleich mit dem zweiten Konzept](22-datenquellen-adapter-und-fallbackkonzept.md#18-vergleich-mit-dem-simudrive-konzept-eines-zweiten-llm).

#### Implementierter Import-/API-Vertrag dieses Schnitts

- Gemeinsame `TabularReader`-/`SourceReader`-Bausteine unter
  `backend/src/main/java/de/synthesis/data/importing/`. Der Tourenimport nutzt sie
  über kompatible Fassaden; sein fachlicher Mapper und seine Betriebslogik bleiben
  getrennt. Gemeinsame Mapping-UI für Touren und neue Fachimporte.
- Registrierte Importadapter als Spring-Beans für `workplaces-v1`,
  `population-grid-v1` und `vehicle-od-v1`. CSV/TSV/XLSX, JSON/XML und DOCX-Tabellen
  werden mit expliziter Zuordnung normalisiert. Dokumenttext ohne Tabellen braucht
  einen Fach-Extraktionsadapter; die vorhandene Tourentextanalyse bleibt separat.
- Deklarierte Einheit, Dezimalzeichen, Bezugsdatum, Abdeckungsbox, Herkunft sowie
  Lizenz-/Attributionsfelder. `unknown` kennzeichnet eine nicht angegebene Lizenz;
  die Software behauptet dadurch keine geklärten Nutzungsrechte.
- Szenariolokale Rohdateien, Importentwürfe, strukturgeprüfte Mappingprofile und
  per Inhaltshash unveränderliche normalisierte Revisionen unter
  `uploads/data-imports/`. Upload und Revisionserzeugung aktivieren keine Quelle.
- Quellenwahl für Bevölkerungsverteilung, Arbeitsplatzgewichte und schwere
  Fahrzeug-OD. Ein gewählter Datenvertrag muss zum Aspekt passen. Datenrevisionen
  werden vor dem Queueing eingelesen und auf Veränderung geprüft.
- Arbeitsplatzwerte ersetzen ausschließlich `Work` innerhalb vollständig durch
  die deklarierte Box abgedeckter Modellzonen. Andere Zwecke bleiben erhalten;
  Explizite Nullwerte bleiben null; Zonen ohne Punkt werden innerhalb der Box ebenfalls null. Ein expliziter Vollständigkeitsvertrag fehlt noch (siehe Reviewgrenze unten). Teilzonen behalten bei erlaubten Lücken die
  bisherigen Gewichte. Der Bericht trennt zugeordnete und tatsächlich angewendete
  Arbeitsplatzmengen. Ohne passende Lückenregel bricht die Verarbeitung ab.
- Eine technische Schwerlast-Mengendemonstration mit vier vorgegebenen
  Tagesmengen für Binnen-, eingehenden, ausgehenden und Durchgangsverkehr. Es
  verwendet repräsentative Binnen-/Randpunkte und gleichmäßige Abfahrten. Das ist
  **kein kalibriertes Branchenmodell, kein Güterkettenmodell und keine belegte
  regionale Vollnachfrage**. Mengen null sind erlaubt; die Gesamtsumme muss für
  ein aktives Ersatzmodell positiv sein. Die vorhandene 100-%-Bedingung bleibt.
- Gemeinsame Backendregel für Quellenplan-Vorprüfung und Vorbereitung der
  Generierung; keine zweite Auswahlregel im Frontend. Neue Laufberichte unter
  `sources/plan.json`, `sources/lock.json` und `sources/resolution.json`, verlinkt
  im Manifest. Die Bestandsquellen des Legacy-Pfads sind darin noch nicht einzeln
  vollständig als Providerkette aufgelöst.
- Native Studioansicht „Daten & Quellen öffnen“ als breiter Dialog mit Beiträgen,
  Datenbestand, Importeditor und Vorprüfung. Die Bestandseinstellungen bleiben
  erhalten. Keine Hintergrundaktivierung importierter Daten und kein automatisch
  als erfolgreich ausgegebener fehlender Mengenanker.

API-Einstieg ist `/api/scenarios/{id}/data`: `schemas`, `imports`,
`imports/{importId}/preview`, `imports/{importId}/revisions`, `revisions`,
`profiles`, `imports/{importId}/profiles`, `imports/{importId}/profile` und
`plan/preview`. Die letzte Route erhält den aktuellen, gegebenenfalls noch nicht
gespeicherten Szenariostand. Eine neue Revision braucht eine erneute explizite
Szenarioauswahl; bestehende Bindungen wandern nicht automatisch weiter.

#### Reviewgrenze des ersten Schnitts (2026-10-28)

Implementiert bedeutet hier technisch vorhanden und teilweise geprüft. Die
[Freigabematrix](22-datenquellen-adapter-und-fallbackkonzept.md#13-nachweise-die-vor-übernahme-nötig-sind)
ist nicht vollständig bestanden. Insbesondere fehlen eine umfassende
Vor-Umbau-Baseline, aufgelöste Teileherkunft und ein expliziter
Vollständigkeitsvertrag für Arbeitsplatzpunktdaten. Kommunale Aggregate mit AGS
und Gebietsstand sind vom Punktadapter nicht unterstützt. Die
[Lkw-Mengendemonstration](22-datenquellen-adapter-und-fallbackkonzept.md#95-vorhandene-mengendemonstration-und-fehlende-fachfreigabe)
ist kein freigegebener struktureller Fallback. Diese Klarstellung ändert keinen
Laufzeitcode und führt keine zusätzlichen bestandenen Gates ein.

### Datenquellen M0: vollständige Punktregister und Teileherkunft — ✅ UMGESETZT (2026-10-28, experimentell)

- **Vollständigkeit:** Arbeitsplatzimporte benötigen `coverageDeclaration` mit
  Datenform `point-register`, Vollständigkeit `complete` und beschriebenem
  Erhebungsumfang. Backendprüfung gilt auch bei Auswahl gespeicherter Revisionen;
  alte Hashes bleiben lesbar, aber ungeklärte Bestände werden nicht verwendet.
  Die UI startet mit „Nicht geklärt“ und übernimmt keine Erklärung aus Mappingprofilen.
- **Anwendungsbericht:** Typisierte Quell-, Zuordnungs- und Kompositionsknoten
  erhalten zonenbezogene Geltung, getrennte externe IDs/Modellindizes und
  Quellzeilen mit Status angewendet/Teilzone/nicht zugeordnet. Alte Arbeitsgewichte
  bleiben mit unbekannter Teileherkunft gekennzeichnet. Unvergleichbare
  Arbeitsplatzanzahlen und Modellgewichte werden nicht als Gesamtmenge addiert.
- **Fehlerverhalten:** Die Lückenregel wird vor dem Überschreiben geprüft;
  bestätigte vollständige Nullbereiche bleiben null, auch ohne lokal zugeordneten Punkt.
- **Verifiziert:** Backend 394 Tests, keine Fehler, vier optionale Skips;
  14 fokussierte Import-/Pipelinefälle; Frontend 95 Tests und Produktionsbuild grün.
  Neue Browserabnahme wegen fehlender automatischer Freigabe noch offen.
  [Befund und Grenzen](05-validierung.md#datenquellen-m0-vollständigkeit-und-teileherkunft-2026-10-28).
- **Offen:** AGS-/Gebietsstandsadapter, geordnete Quellenprofile/Datenpakete,
  übrige Aspekte, vollständige regionale Vergleichsmatrix und fachlich geprüfter
  Schwerlastfallback. Der Quellenplan bleibt standardmäßig ausgeschaltet.

### Datenquellen M1: lokale Pakete und geordnete Profile — ✅ ERSTER SCHNITT UMGESETZT (2026-10-28, experimentell)

- **Paketvertrag:** `synthesis-data-pack-v1` verbindet ein Manifest mit lokalen
  strukturierten Dateien, Rohhashes, Mapping und Fachmetadaten. Alle Quellen werden
  vor der Veröffentlichung geprüft. Grenzen: 5 MB ZIP, 15 MB entpackt, zehn Quellen;
  keine Pfade, nicht deklarierten Dateien oder Downloads. Die gesamte ZIP-Bytefolge
  bestimmt die Paketidentität, auch bei gleicher Paket-ID/Edition.
- **Quellenprofil:** `synthesis-source-profile-v1` versioniert explizite Reihenfolge,
  Abwahl und Ausfallregeln. Kandidaten sind Paketbeiträge oder vorhandene Revisionen.
  Ein Szenario wählt `source=profile` und den konkreten Hash im Feld `profile`.
  Neue Installationen oder Profilversionen verändern diese Auswahl nicht.
- **Auflösung:** Vorschau und Generierung verwenden denselben Resolver. Er erhält
  Abwahl, unpassende Abdeckung/Zeit/Fachvertrag, fehlende Datei, tatsächliche Auswahl
  und nicht geprüfte nachrangige Kandidaten als verschiedene Diagnosen. Nur die
  explizite `next`-Regel erlaubt bei fehlenden Dateien den nächsten Versuch;
  beschädigte Daten brechen ab. Ohne Gewinner gilt ausschließlich `none` oder
  ausdrücklich erlaubter Bestandspfad. Bei Blockern bleiben Diagnosen in der UI.
- **Studio:** Im Datenbestand ZIP installieren; unter Beiträge Quellenprofil
  erstellen, Kandidaten ordnen/abwählen und Regeln speichern. Aktivierung ist ein
  gesonderter Schritt. Ungespeicherte Profilentwürfe schützen den Generierungsstart
  wie ungespeicherte Importzuordnungen. Der Eingabelock hält Profildefinition,
  Kandidatendiagnosen und aufgelöste Revisionen fest.
- **Verifiziert:** Backend-Gesamtsuite 401 Tests ohne Fehler, vier optionale Skips;
  abschließend 22 fokussierte Fälle ohne Fehler/Skips, einschließlich einer danach
  ergänzten Profilprüfung für Bevölkerung und schwere Fahrzeuge. Frontend 99 Tests
  und Produktionsbuild grün. Echte Browserkette mit CSV-/JSON-Paketen, Nicht-DE-Auswahl
  und Offlinegenerierung bestanden; Details im verlinkten Befund.
- **Grenzen:** Szenariolokaler Schnitt für die drei vorhandenen Importverträge,
  keine vollständige Migration der Bestandsquellen, keine globale Paketverwaltung
  oder Dienstconnectoren. Abhängige Modellprovider und allgemeine Zyklusprüfung,
  kommunale Gebietsadapter und fachlicher Schwerlastfallback bleiben offen.
  [Befund und Nachweise](05-validierung.md#datenquellen-m1-pakete-profile-und-gebietswechsel-2026-10-28),
  [ausführbare Beispiele](examples/data-packs/README.md).

### Gemeindeaggregate und Qualitätsmodus — ✅ ERSTER SCHNITT UMGESETZT (2026-10-29, experimentell)

- **Gemeindevertrag:** `municipal-workplaces-v1` erhält Quell-ID, achtstelligen AGS
  und Arbeitsplatzsumme. Metadaten tragen `de:AGS`, einen Gebietsstand und die
  konkrete Revision separater räumlicher Gewichte. Führende Nullen bleiben erhalten;
  doppelte Gemeindeschlüssel sind fehlerhafte Importzeilen.
- **Explizite Verteilung:** `municipality-allocation-v1` liefert Punkte und positive
  Gewichte je Schlüssel. Der Join verlangt gleichen Schlüsselraum, Gebietsstand
  und Modellversion. Der Nenner umfasst alle Gewichte je AGS, auch außerhalb des
  Modells. Ein künstlicher 3:1-Fall liefert aus 100 Arbeitsplätzen genau 75 im
  Modell und 25 außerhalb; eine echte Null bleibt null. Nur explizit zugeordnete
  Zonen ändern ihre Arbeitsgewichte. Andere Zonen folgen der gespeicherten
  Lückenregel; ein Fehler überschreibt keine Zone teilweise.
- **Referenzen:** Fachadapter deklarieren typisierte Revisionsabhängigkeiten.
  Fehlende/falsche, zyklische und zu tiefe Abhängigkeiten werden geprüft; verwendete
  Revisionen stehen unter `references` im Eingabelock. Der Anwendungsbericht behält
  Quellzeilen, AGS, Verteilungsanteile, externe IDs/Modellindizes und Mengenbilanz.
  Der Verteilungsknoten ist ausdrücklich eine Annahme, keine Standortbeobachtung.
- **Laufzweck:** `dataPlan.qualityMode=exploration` als Default. Die technische
  Lkw-Demonstration braucht `demonstrationAccepted=true` und eine beschriebene
  Annahme. `analysis` blockiert sie vor dem Queueing. Alte Demonstrationen werden
  nicht automatisch freigegeben. Die UI zeigt Zweck, Sperre und Herkunft getrennt.
- **Verifiziert:** Backend 411 Tests ohne Fehler, vier optionale Skips;
  31 fokussierte Quellenfälle, 102 Frontendtests und Produktions-AOT grün.
  Echte Browserkette mit 75/25-Mengenbilanz und Analysesperre bestanden;
  Grenzen und Nachweise im [Befundregister](05-validierung.md#gemeindeaggregate-und-qualitätsmodus-2026-10-29)
  ausgewiesen.
- **Grenzen:** Explizites Punktgewichtsmodell, keine amtliche Grenz-/Schlüsselprüfung
  und keine automatische Überleitung zwischen Gebietsständen. Die Moduswahl
  zertifiziert nicht alle anderen Quellen oder Modelle. Empirischer Güterfallback,
  Polygonzuordnung und vollständige Bestandsmigration bleiben weitere Arbeit.
  [Beispiele und Bedienung](examples/municipal-workplaces/README.md).

### Gemeindegeometrien und Flächenverteilung — ✅ ERSTER SCHNITT UMGESETZT (2026-10-29, experimentell)

- **Geometriereader:** `municipality-areas-v1` erhält Polygon-/MultiPolygon-Objekte
  und Featurebelege aus WGS84-GeoJSON. Skalare Eigenschaften werden im gemeinsamen
  Mappingeditor zugeordnet. Geometrien werden nicht in Koordinatenzeilen zerlegt.
  Grenzen: 5 MB, 500 Features und 50.000 Koordinaten. Ungültige Ringe, doppelte AGS,
  abweichendes CRS und überlappende Gemeindegeometrien werden abgewiesen.
- **Modell:** `uniform-area-v1` verteilt die Gemeindesumme proportional zur
  Schnittfläche in EPSG:3035. Löcher und getrennte Teile bleiben erhalten. Echte
  projizierte Rasterecken ersetzen keine Zone durch ihre größere Lat/Lon-Box.
  Nicht dargestellte Außenmenge und nicht angewendete Teilzonenmenge stehen separat
  im Bericht. Teilmengen werden nicht mit einem ganzen alten Zonengewicht addiert.
- **Numerik:** Abdeckung verwendet einen 1-cm-Randpuffer für die bekannte
  Projektions-Rundlaufgenauigkeit. Mengen werden an ungepufferten Geometrien
  berechnet. Diese Rechentoleranz bescheinigt keine Messgenauigkeit der Quelle.
- **Wiederverwendung:** Die bisherigen MobileCity-Geometrieoperationen sind in
  `SpatialGeometry` wiederverwendbar, mit kompatibler Fassade für die Bestandspfade.
  Eine neue externe Bibliothek oder Pipeline-Stufe ist nicht hinzugekommen.
- **UI:** Geometrierevision importieren, im Gemeindeimport Flächenverteilung wählen
  und die genaue Revision binden. Punkt- und Geometriereferenzen bleiben getrennt;
  ein Modellwechsel verwirft die alte Referenzauswahl. GeoJSON wird auch im lokalen
  Datenpaketvertrag unterstützt.
- **Verifiziert:** Finale Backend-Suite 418 Tests ohne Fehler, vier optionale Skips;
  38 fokussierte Quellenfälle, Frontend 103 Tests und Produktions-AOT grün.
  Echte Browserkette mit Lochpolygon, Außenmenge und Teilzonen bestanden; Grenzen
  stehen im [Befundregister](05-validierung.md#gemeindegeometrien-und-flächenverteilung-2026-10-29).
- **Grenzen:** Konstante Dichte als explizite Modellannahme, synthetische Fixtures,
  keine amtliche Grenz-/Schlüsselbestätigung oder allgemeine Kalibrierungsabnahme.

### Konfigurierbare Bevölkerungsprovider — ✅ ERSTER SCHNITT UMGESETZT (2026-10-29, experimentell)

- **Quellenkette:** `population.distribution` kann mit `source=builtins` eine
  explizite Reihenfolge der vorhandenen Provider Uploadraster, Zensus 2022,
  OSM-Siedlungen, POI-Verteilung und Ersatz ohne Ortsdaten wählen. `PopulationSources`
  kapselt die verzögerte Auflösung; abgewählte oder nachrangige Loader werden nicht
  ausgeführt. Nur Nichtverfügbarkeit mit `onUnavailable=next` erlaubt Fortsetzung;
  eine unpassende Quelle wird übersprungen. Erschöpfung führt zum Fehler.
- **Kompatibilität:** `source=legacy` bleibt Standard. Die Pipeline hat dieselben
  Stufen und dieselbe Zufallsfolge. Der neue Standardreihenfolge-Schnitt verwendet
  die bisherigen Berechnungen. Die Migration umfasst eine Providerfamilie, keinen
  vollständigen allgemeinen Workflowplaner oder Katalog aller alten Quellen.
- **Eingaben und Fehler:** Der Quellenlock bindet bei zugelassenem Upload seinen
  Hash bzw. die Abwesenheit vor dem Queueing. Der Lauf prüft eine behaltene Kopie;
  eine zwischenzeitliche Änderung/Entfernung ist fatal. Der explizite Zensuspfad
  prüft Archiv und extrahierte Rasterdatei und behandelt Integritätsfehler als
  Fehler. Der alte Zensuspfad behält seine bisherige optionale Ausweichsemantik.
  Nullwerte sind gültig; unterdrückte/negative/ungültige Rasterwerte werden im neuen
  Pfad nicht still übersprungen. Eine eigene NoData-Maske ist weiterhin offen.
- **Herkunft:** `sources/resolution.json.population` trennt Gesamtzahl und Verteilung
  einschließlich manueller Übersteuerung, Modellannahmen und Zonengeltung.
  Kandidaten erhalten Rang, Status und Grund. Die Vorprüfung meldet ausstehende
  Laufauflösung. Fehlerpfade erhalten `sources/population-failure.json`;
  Folgeläufe entfernen veraltete Berichte und Uploadkopien.
- **UI:** Reihenfolge, Abwahl und Ausfallregel sind im Datenarbeitsbereich bedienbar.
  Der Plan greift ausschließlich in die Bevölkerungsquelle ein; Straßennetz,
  Arbeitsplatzgewichte und andere Zensusverbraucher behalten ihre eigenen Gates.
- **Verifikation:** Siehe [Befundregister](05-validierung.md#konfigurierbare-bevölkerungsprovider-2026-10-29)
  für tatsächliche Tests, Artefaktvergleiche und Browsernachweis.
- **Grenzen:** Keine neue empirische Freigabe. Die fünf Bestandsprovider und die
  bisherigen Revisions-/Paketprofile sind noch getrennte Auswahlmöglichkeiten;
  globale Providerregistrierung und weitere Quellfamilien bleiben offen.
  [Bedienung und Beispielplan](examples/population-sources/README.md).

### Gemeinsame Providerauflösung und Arbeitsplatzquellen — ✅ ERSTER SCHNITT UMGESETZT (2026-10-29, experimentell)

- **Gemeinsamer Kern:** `ProviderChain<T>` löst typisierte Werte unabhängig von
  Quelldomäne und Dateiformat auf. Bevölkerung und Arbeitsgewichte verwenden
  denselben Algorithmus für Vorrang, Abwahl, Nichtverfügbarkeit, ungültige Eingaben,
  Unterbrechung und erschöpfte Listen. Reine Abwahl wird nicht als gescheiterte
  Primärquelle und nicht als Fallback ausgegeben. Population behält seinen
  bisherigen spezifischen Wert-/Diagnosevertrag als Fassade.
- **Work-Verbraucher:** `source=builtins` am Aspekt `opportunities.work` ordnet
  `osm-pois` und `synthetic`. Die OSM-Variante übernimmt die unveränderte
  Work-Ableitung der Attraktivitätsstufe. Die synthetische Variante verwendet
  denselben Modellalgorithmus auf privaten Zonenkopien und einen versionierten,
  aus dem Szenarioseed abgeleiteten eigenen Zufallsstrom. Andere Zwecke und der
  gemeinsame Pipeline-Zufallsstrom werden nicht verändert. Die Auswahl wird
  vollständig validiert, bevor Work-Werte überschrieben werden.
- **Herkunft:** Bericht unter `sources/resolution.json.workplaces`, Einheit
  `model-weight`, getrennte Evidenz für OSM-Eingang und Gewichtableitung sowie
  Modellprofil/Seed für synthetische Werte. Es wird keine beobachtete Arbeitsplatz-
  gesamtsumme behauptet. Fehlerpfade liegen unter `sources/workplace-failure.json`.
- **UI:** Arbeitsplatzprovider einzeln anordnen/abwählen; Population und Work haben
  getrennte Konfigurationen. Die Herkunft des gemeinsamen OSM-Transports wird
  dadurch nicht vollständig neu implementiert. Datensatz-/Paketprofile und die
  konfigurierbare native Kette sind weiterhin getrennte Auswahlmöglichkeiten.
- **Verifikation und Grenzen:** [Befundregister](05-validierung.md#gemeinsame-providerauflösung-und-arbeitsplatzquellen-2026-10-29).
  [Bedienung und Beispielplan](examples/workplace-sources/README.md).

### Gemischte Profile und explizite Registerlücken — ✅ ERSTER SCHNITT UMGESETZT (2026-10-29, experimentell)

- **Gemeinsame Kette:** Quellenprofile für Bevölkerung und Work können importierte
  Revisionen, Paketquellen und native `builtin:`-Provider enthalten. `MixedSources`
  prüft/bindet potenziell benötigte Revisionen samt Referenzen vor dem Queueing und
  übergibt die typisierten Werte im Lauf an `ProviderChain<T>`. Die geplante Bindung
  (`staged`) ist von tatsächlicher Auswahl getrennt. Alte reine Importprofile
  bleiben unter unverändertem Hash lesbar und nutzen ihren bisherigen Vertrag.
- **Explizite Abwahl:** Gemischte Profile verlangen `fallback=none`; alle Ersatzquellen
  stehen in der Liste. Gemischte Work-Profile erlauben nur `gaps=none|providers`.
  Ein impliziter alter Pfad darf eine native Abwahl nicht rückgängig machen.
- **Lücken:** `Policy.gaps=providers` mit geordneter `gapProviders`-Liste wählt native
  Work-Gewichte für nicht vollständig übernommene Zonen. `WorkplaceComposition`
  unterstützt Punktregister und beide Gemeindeallokationen. Bestätigte Nullwerte
  bleiben erhalten; Teilzonen erhalten ganze Ersatzgewichte. Original-, Außen- und
  nicht angewendete Teilmengen werden nicht mit Modellgewichten addiert.
- **Atomarität/Herkunft:** Die ganze Zuordnung läuft zunächst auf Zonenkopien.
  Erst nach erfolgreicher Prüfung von Primärbeitrag, benötigtem Lückenfüller und
  Herkunftsgraph werden Work-Werte übernommen. Bei voller Abdeckung wird kein
  Lückenprovider aufgelöst. Scope und Modellparameter der `gap/…`-Beiträge bleiben
  im Graph erhalten; ihre Berichtssumme umfasst ausschließlich ergänzte Zonen.
- **UI:** Gemeinsame Kandidatenliste im Profileditor, explizite Lückenquellen mit
  Reihenfolge/Abwahl, Vorprüfung mit gebundenen Eingaben und geplanten Lückenfüllern.
  Installation, Veröffentlichung, Profilversion und Szenarioauswahl bleiben getrennt.
- **Verifikation/Grenzen:** [Befundregister](05-validierung.md#gemischte-profile-und-explizite-registerlücken-2026-10-29).
  [Ausführbares Paket, Profil und Bedienung](examples/mixed-sources/README.md).
  Mehrere regionale Register werden noch nicht automatisch kombiniert; externe
  Fachquellen, Masken und empirische Modellabnahme bleiben eigene Aufgaben.

### Mehrere Register regional ergänzen — ✅ ERSTER SCHNITT UMGESETZT (2026-10-29, experimentell)

- **Vertrag:** Optionale Profilstrategie `fill-gaps` für importierte Work-Register.
  Ohne Strategie bleibt `choose` wirksam; das neue Feld wird bei alten Profilen
  nicht serialisiert. Eine vor der Änderung gespeicherte Profil-Fixture behält
  exakt ihren erfassten Hash. Regionale Profile verlangen explizite Lückenregeln;
  native Modelle bleiben in der getrennten Lückenliste.
- **Komposition:** `RegionalWorkplaces` übernimmt ganze vollständig abgedeckte Zonen
  nach Quellenrang. Ein Nullwert ist ein belegter Wert. Nachrangige Register können
  nur noch offene Zonen ergänzen; Überlappungswerte werden als `shadowedJobs` erhalten.
  Gemeindeaußenmengen und polygonale Teilmengen behalten ihren jeweiligen Nenner.
- **Herkunft/Atomarität:** Mengenbilanz pro Revision, tatsächliche disjunkte Scopes
  unter `register-<Rang>/…`, Diagnose vor Vorrang unter `allocationBeforePriority`.
  Ein gemeinsamer beobachteter Arbeitsplatzgesamtwert wird nicht behauptet. Erst
  nach vollständiger Prüfung einschließlich erforderlichem Ersatz werden echte
  Work-Werte übernommen. Andere Zwecke und der Pipeline-RNG bleiben erhalten.
- **Vorprüfung:** Alle potenziellen Register werden vor Queueing gebunden, auch
  nachrangige, deren räumliche Notwendigkeit noch nicht feststeht. Abgewählte Quellen
  werden nicht gelesen; nach vollständiger tatsächlicher Abdeckung werden keine
  weiteren Register zugeordnet. Das ist von einer bestätigten Auswahl zu trennen.
- **UI:** „Zusammenführung → Register regional ergänzen“, Quellenvorrang, getrennte
  native Lücken und sichtbarer Strategiestatus im ausgewählten Profil/Preflight.
- **Nachweise/Grenzen:** [Befundregister](05-validierung.md#mehrere-register-regional-ergänzen-2026-10-29).
  [Ausführbares Paket und genaue Mengenregeln](examples/regional-registers/README.md).
  Fachliche Registervergleichbarkeit, NoData-Masken und empirische Abnahme bleiben offen.

### Rasterzellen mit expliziten Masken — ✅ ERSTER SCHNITT UMGESETZT (2026-10-30, experimentell)

- **Verträge:** `population-cells-v1` und `workplace-cells-v1` bilden vollständige
  EPSG:3035-Quadrate mit `value`, `missing`, `not-collected`, `suppressed` ab.
  Bekannte Null und unbekannte Menge bleiben getrennt; explizite Marker bleiben
  erhalten. Gemeinsame Reader, Mappingeditor, Revisionen, Profile und Pakete werden
  wiederverwendet. Der Adapter normalisiert vor Validierung und Revisionshash.
- **Raum/Mengen:** Vollständig bekannte Modellzonen erhalten ungepufferte
  Flächenanteile der Quellmenge. Die Flächenverteilung ist eine Annahme.
  Masken, Außenmengen und bekannte nicht angewendete Teilmengen bleiben separat.
  Ganze Teilzonen folgen der expliziten Lückenregel; es gibt keine Hochskalierung
  der Quellsumme auf die Region. Rasterüberlappungen werden abgelehnt.
- **Ersatz:** Work nutzt regionale Nachrangregister oder konfigurierte native
  Gewichte über die bestehende atomare Komposition. Population verlangt bei
  Lücken eine konsistente manuelle Gesamtzahl. Nur deren Restmenge wird mit den
  ausdrücklich gewählten räumlichen Modellen auf offene Zonen verteilt. Echte
  Nullwerte bleiben erhalten; Mengenanker, Flächenannahme und Modell bleiben
  einzelne Herkunftsteile. Ohne positive Restmenge wird kein Ersatzmodell geladen.
- **UI:** Eigene Rasterverträge im gemeinsamen Import, sichtbarer Statusvertrag,
  Lückenquellen mit Reihenfolge/Abwahl/Ausfallregel und manueller Bevölkerungsanker.
  Vorprüfung bindet Quellen; tatsächliche Maskenabdeckung folgt im Lauf.
- **Verifiziert:** Zehn neue `MaskedCellsTest`-Fälle; 82 Quellenfälle, vollständige
  Backend-Suite mit 462 Tests ohne Fehler (neun optionale Skips), 112 Frontendtests
  und Produktionsbuild. Tatsächlicher UI-Import und Offlinegenerierung sowie
  Installation/Generierung des Beispielpakets bestanden.
  [Befundregister](05-validierung.md#rasterzellen-mit-expliziten-masken-2026-10-30),
  [Vertrag und ausführbares Beispiel](examples/masked-cells/README.md).
- **Grenzen:** Festes EPSG:3035-Zellformat, keine automatische Migration nativer
  Zensusmasken, keine unterzonale Ergänzung oder empirische Modellfreigabe.
  Flächenmengen sind Verteilungsgewichte; die vorhandene Erzeugung ganzer Haushalte
  besitzt eine eigene Rundungsabweichung (im Browserfall Ziel 60, tatsächlich 63).

### Szenarioübergreifende Datenpaketbibliothek — ✅ ERSTER SCHNITT UMGESETZT (2026-10-30, experimentell)

- **Wiederverwendung:** `DataPackLibrary` verwaltet portable Pakete einer Installation
  unter `source-library/packs/<hash>.zip`. Eigene ZIPs können hochgeladen oder
  installierte Szenarioversionen ausdrücklich bereitgestellt werden. Kein neuer
  Fachparser, keine zusätzliche Pipeline-Stufe und keine Änderung an Seeds.
- **Portabilität:** `DataPacks.validatePortable` verwendet die vorhandenen
  Fachadapter und verlangt sämtliche Referenzrevisionen im selben ZIP. Graph,
  Vertragstyp und kommunaler Schlüssel-/Gebietsstandsjoin werden geprüft, bevor
  die Bibliothek eine Version speichert. Szenariolokale Pakete behalten ihren
  bisherigen Vertrag; ein nur dort auflösbarer externer Verweis ist nicht portabel.
- **Versionsbindung:** Eine Übernahme kopiert konkrete ZIP- und Revisionsbytes in
  den Szenariobestand. Neue Bibliotheksversionen verändern keine vorhandenen
  Quellenbindungen. Die Generierung liest ausschließlich die eigenen eingefrorenen
  Eingaben und benötigt die Zentraldatei nicht. Veröffentlichung, Installation
  und Aktivierung sind getrennte Aktionen.
- **UI:** Bibliothek im Datenbestand, Versions-/Lizenzangaben, ausdrückliche
  Bereitstellung und Übernahme. Ungültige Zentraldateien erscheinen mit Grund;
  ihre Übernahme ist blockiert. Ein Bibliotheks-Lesefehler lässt den übrigen
  Szenariobestand nutzbar. Bereits vorhandene konkrete Versionen sind erkennbar.
- **Verifiziert:** Sieben neue Backendfälle; vollständige Suite mit 469 Tests ohne
  Fehler (neun optionale Skips), 89 Quellenfälle ohne Fehler/Skips, 116 Frontendtests
  und Produktions-AOT. Die echte Browserkette mit zwei Szenarien
  veröffentlicht V1, übernimmt sie ohne Aktivierung, wählt die Beiträge ausdrücklich,
  veröffentlicht V2 ohne Umbindung und generiert nach zentraler Beschädigung
  bytegleiche gefüllte Personen-, Haushalts-, Aktivitäten-, Zonen- und
  Attraktivitätsdateien aus der Szenariokopie.
  [Befundregister](05-validierung.md#szenarioübergreifende-datenpaketbibliothek-2026-10-30),
  [Bedienung, API und Versionsbeispiele](examples/source-library/README.md).
- **Grenzen:** Installationsweite Datenpakete, weiterhin szenariolokale
  Quellenprofile. Keine automatische Regionalempfehlung, entfernten Dienste oder
  empirische Qualitätsfreigabe. Die vollständige Konzeptfreigabe bleibt separat.

### Gemeinsame Quellenprofile mit Paketabhängigkeiten — ✅ ERSTER SCHNITT UMGESETZT (2026-10-30, experimentell)

- **Vertrag:** `SourceProfileLibrary` verwendet die bestehende Profildefinition und
  gemeinsame kanonische Serialisierung. Profilhashes bleiben zwischen lokalem und
  gemeinsamem Bestand identisch; die alte erfasste Profilhash-Fixture besteht.
  Portable Profile enthalten native Regeln oder feste Paketbeiträge. Direkte
  Revisionskandidaten bleiben lokal. Alle aktiven Pakete müssen in der Bibliothek
  vorhanden und zum Aspekt passend sein; ausgeschlossene Pakete werden nicht gelesen.
- **Übernahme:** Vor der ersten Zielschreiboperation werden sämtliche aktiven
  Paketbytes geprüft/eingelesen. Danach werden konkrete Kopien und das Profil im
  Zielscenario gespeichert. Es wird kein Beitrag aktiviert. Mengenanker und
  Lückenregeln bleiben Szenarioentscheidungen. Native Provider lösen ihre Eingaben
  im Ziel neu auf; eine Profilkopie überträgt keine Uploads oder Liveantworten.
- **Versionen/UI:** Bereitstellung gespeicherter Szenarioprofile und JSON-Import
  in der Bibliothek, sichtbare Paketabhängigkeiten und explizite Profil-/Datenübernahme.
  Neue Versionen binden Szenarien nicht um. Fehlerhafte gemeinsame Profile bleiben
  sichtbar und sind nicht übernehmbar; ein Bibliotheksausfall blockiert den übrigen
  Szenariobestand nicht. Die Generierung liest weiterhin nur ihre eigenen Inputs.
- **Nachweise:** Sieben neue `SourceProfileLibraryTest`-Fälle; vollständige Backend-
  Suite mit 476 Tests ohne Fehler (neun optionale Skips), 96 Quellenfälle ohne
  Fehler/Skips; 120 Frontendtests und Produktionsbuild. Echte Browserkette mit
  gemeinsamer Zweiaspektdefinition, Paketübernahme, JSON-Version V2 ohne Umbindung
  und fünf gefüllten bytegleichen CSV-Dateien nach zentraler Beschädigung bestanden.
  [Befundregister](05-validierung.md#gemeinsame-quellenprofile-und-kompatibilitätsnachweise-2026-10-30),
  [Vertrag, Bedienung und Beispiele](examples/shared-source-profiles/README.md).
- **Kompatibilitätskorrektur:** Die fünf nativen Bevölkerungszweige verglichen
  bereits gefüllte Dateien über `coreArtifacts`; die vorige Einordnung als reiner
  Leercontainervergleich war zu weitgehend. Neu sind ausdrückliche gefüllte
  Mindestartefakte und nachvollziehbare Fixture-/Ergebnishashes je Zweig. Eine
  isolierte Vor-Umbau-Baseline bleibt offen. Weitere Quellfamilien, entfernte
  Katalogdienste und empirische Fachfreigaben bleiben eigene Aufgaben.

### Schwerlastquellen, additive Unternehmensbeiträge und native Zielwirkung — ✅ BEGRENZTER SCHNITT UMGESETZT (2026-11-02)

- **Herkunft:** `FreightSources` ergänzt unter `sources/resolution.json.freight` eine
  relationale Quellen-/Ergebnisbilanz. Quellzeile, Revision/Rohhash, Metadaten,
  ursprüngliche Relation und tatsächlich erzeugte Tagesmenge bleiben verbunden.
  Klassen, Koordinaten, Kanten, Zeitfenster und Mengen werden gegen die gebundenen
  Relationen geprüft. Einzelne Fahrten sind über `freight/trips.csv` zuordenbar.
- **Evidenz:** Nutzerquellen bleiben unbestätigt; die technische Demonstration ist
  `assumed`. Dieser Status gilt jetzt konsistent auch in Freight-Berichten und
  Manifest. Abfahrten innerhalb des Eingangsfensters sind weiterhin eine getrennte
  Modellannahme. Erzeugung ist kein Ausführungsbeweis; separate native Bilanzen
  werden ausdrücklich referenziert. Der fachliche Güterfallback aus G9 bleibt offen.
- **Semantik:** Auswahl/Abwahl des Schwerlasthintergrunds ersetzt keine aktivierten
  Unternehmenstouren. Die UI benennt die Abgrenzung zu Unternehmen und Paketlogistik.
  Die neue Migrationsfallmenge vergleicht Bestand, direkte Revision, Quellenprofil,
  Revisionswechsel und Abwahl bei drei Seeds und zwei künstlichen Netzlagen.
- **Verifiziert:** Für den Schnitt bleiben Unternehmensidentitäten und 69 fachliche
  Population-/Matrix-/Exportdateien unverändert. Gleichwertige Bestand-/Revisionsdaten
  liefern denselben gefüllten gemeinsamen Nachfragecontainer. Die geänderte Relation
  verändert nur ihre eigene Menge/Zufallsfolge. Ein kontrollierter Wartepunkt prüft
  die Bindung vor späteren Quell-/Szenarioänderungen. 18 echte SUMO-Läufe prüfen
  angeforderte, geroutete, abgefahrene und abgeschlossene Bewegungen ohne Teleports.
  [Ausgeführte Gesamtsuiten und genaue Grenzen](05-validierung.md#quellenplan-schwerlast-unternehmensbeiträge-und-native-zielwirkung-2026-11-02),
  [Vertrag und feste Prüfmenge](examples/source-plan-traffic/README.md).
- **Grenzen:** Zwei übersetzte synthetische Netze sind keine unabhängigen realen
  Stadt-/Landreferenzen. Die privaten Population-/Aktivitätsartefakte sind vorhanden,
  simulierte private Personenfahrten und Paketfahrten sind in diesen nativen Läufen
  nicht enthalten; logiTopp und GTFS sind ausdrücklich deaktiviert. Volle regionale,
  empirische und MobileCity-Abnahmen bleiben offen.

### Lesbarer Quellenlaufbericht mit Teilfehlern — ✅ UMGESETZT (2026-11-04)

- **Verhalten:** „Daten & Quellen → Lauf prüfen“ zeigt angewendete Quellen,
  getrennte Registermengen, Modellgewichte, Maskenzustände, Herkunftsschritte und
  Ersatzrollen. Die Bevölkerungssynthese weist Zielmenge und tatsächliche
  Haushaltsrundung getrennt aus, auch beim delegierten Bestandspfad.
- **Laufbindung:** `SourceRunReport` ergänzt den vorhandenen Quellenbericht um
  Laufkennung, Beginn/Ende, Status/Fehler sowie Plan-/Lockhashes. Er schreibt bereits
  am Start und bei Teilfehlern; atomarer Ersatz vor Veröffentlichung des Laufendes.
  Fehlgeschlagene Läufe erben keine früheren Erfolgsanteile. Die bisherigen
  Fehlerbereinigungstests prüfen nun ausdrücklich diese stärkere Invariante.
- **Zielwirkung:** Erzeugte Nachfrage und tatsächliche Zielausführung bleiben
  getrennt. Nur eine erfolgreich beendete aktuelle SUMO-Stufe bindet ihre aggregierte
  Bilanz und den Hash der vollständigen Ausführungsdatei. Ein alter Dateirest ist
  kein Erfolg einer übersprungenen oder noch ausstehenden Stufe.
- **Grenzen:** Nur letzter Lauf, kein Archiv. Nachträgliche Szenarioänderungen
  erfordern eine neue Generierung; der Bericht bestätigt keinen automatischen
  Gleichstand sämtlicher aktueller Eingaben. Keine zusätzliche Quellenfamilie,
  neue Pipeline-Stufe oder neue Zufallsfolge; `dataPlan.enabled=false` bleibt Default.
  [Bedienung und Vertrag](examples/source-run-report/README.md),
  [ausgeführte Tests und Browsernachweis](05-validierung.md#quellenlaufbericht-und-lesbare-teileherkunft-2026-11-04).

## K. Plattform-Härtung und Betrieb (ab 2026-11-04)

Dieser Abschnitt sammelt die Umbauten vom Einzelnutzer-Werkzeug zur Server-Plattform:
Statusvertrag, Nachvollziehbarkeit, Anmeldung, Deployment und Qualitätswerkzeuge. Jeder Eintrag
nennt den Anlass, die Entscheidung und den Nachweis.

### Fallback-Sichtbarkeit, Provenienz und persistierter Jobstatus — ✅ UMGESETZT (2026-11-04)
- **Anlass (Befund vom 2026-11-04):** Ein Lauf ohne Overpass-Zugang stand in der Pipeline-Anzeige
  als grüne Stufe „OSM-Daten (POIs)“, obwohl die Attraktivitäten synthetisch waren; die Meldung war
  gesetzt, aber nur bei `FAILED` sichtbar. Gleichzeitig nannte `manifest.json` unter
  `inputProvenance.osmData.source` den Wert `overpass-live`, weil `osmDataSource` vor dem Abruf
  gesetzt und bei Ausfall nicht korrigiert wurde. Beides widersprach dem Herkunftsprinzip.
- **Fallback-Markierung:** `GenerationJob.StageInfo.fallback` ergänzt den Statusvertrag
  `PENDING | RUNNING | DONE | FAILED | SKIPPED` um eine orthogonale Kennzeichnung: Die Stufe bleibt
  `DONE`, trägt aber Markierung und Meldung. Gesetzt wird sie bei Overpass-Ausfall (POIs oder
  Siedlungen), Luftlinienmatrizen nach gescheitertem Netzaufbau und bei Verhaltensquellen-Fallback
  (GTA → actiTopp → Template). Das Studio zeigt dafür ein zweifarbiges Statuszeichen, ein
  „Fallback“-Badge und die Meldung; übersprungene Stufen zeigen ihren Grund ebenfalls.
- **Provenienz:** `osmDataSource` wird erst nach den Abrufen bestimmt:
  `overpass-live` (beide Abrufe), `overpass-live-partial` (einer) oder `synthetic-fallback`.
- **Jobstatus überlebt Neustarts:** `GenerationJob` wird als `artifacts/job.json` nach jeder Stufe
  und am Laufende atomar gespeichert; `GenerationService.status()` liest die Datei, wenn der Lauf
  nicht mehr im Speicher ist. Ein beim Neustart noch aktiver Lauf wird als `FAILED` mit Grund
  gemeldet, damit das Studio nicht endlos pollt.
- **Thread-Sicherheit:** `summary` war eine `LinkedHashMap`, die der Worker schreibt, während der
  Status-Endpunkt sie serialisiert. Sie ist jetzt synchronisiert und wird nur als Kopie
  serialisiert; Zustandsfelder sind `volatile`.
- **Ergebnisanzeige:** `zonesInternal`/`zonesExternal` trennen interne Zellen von Außenankern
  (vorher „529 Zonen“ links und „537“ rechts ohne Erklärung).
- **Testfix:** `NativeVehicleChainsIntegrationTest` setzte `assumeTrue` innerhalb der
  `assertThrows`-Lambda; ohne SUMO wurde der Test rot statt übersprungen. Die Annahme steht jetzt
  davor.
- **Verifiziert:** `GenerationJobTest` (Serialisierung genau einmal, Roundtrip, nebenläufige
  Snapshots), `GenerationServiceFallbackTest` (Overpass-Ausfall → `DONE` + Fallback,
  Manifest `synthetic-fallback`, Status aus `job.json` nach Neustart); Backend-Suite 489 Tests,
  0 Fehler, 44 übersprungen; Frontend 130 Tests grün, AOT-Build grün.

### Anmeldung und Benutzerkonten (Plattform-Betrieb) — ✅ UMGESETZT (2026-11-04)
- **Anlass:** Das Werkzeug soll auf einem Server als Plattform laufen, auf der sich mehrere Personen
  anmelden. Bisher war die komplette API offen, CORS fest auf `http://localhost:4300` verdrahtet und
  jeder konnte jedes Szenario löschen.
- **Mechanik:** Spring Security mit Cookie-Session statt Token-Verwaltung im Browser, weil das
  SPA vom selben Backend ausgeliefert wird. Paket `de.synthesis.auth`: `SecurityConfig` (JSON-Login,
  `401` statt Login-Seite für `/api/**`, CSRF-Token als lesbares Cookie `XSRF-TOKEN`, das Angular
  per `withXsrfConfiguration` als Header `X-XSRF-TOKEN` zurückschickt), `AuthController`
  (`/api/auth/login|logout|me|password`), `UserController` (`/api/users`, nur Rolle `ADMIN`),
  `UserStore` (`<data-dir>/users.json`, bcrypt über `DelegatingPasswordEncoder`, atomar geschrieben,
  Dateirechte `rw-------`). Rollen: `ADMIN` verwaltet Konten, `USER` arbeitet mit Szenarien; alle
  Angemeldeten teilen sich den Szenario-Arbeitsbereich, löschen darf nur Ersteller oder Admin
  (`Scenario.createdBy`, in der Liste sichtbar).
- **Erststart:** Ohne Konten legt `UserStore` den Administrator `admin` mit zufälligem Startpasswort
  an; es steht einmalig im Log und in `<data-dir>/initial-admin-password.txt`. Nach dem ersten
  Login über „Benutzer → Eigenes Passwort“ ändern und die Datei löschen.
- **Konfiguration:** `synthesis.auth.enabled` (Default `true`; `false` nur für lokale
  Einzelnutzer-Entwicklung, dann bleibt die API wie bisher offen und `/api/auth/me` meldet einen
  lokalen Admin), `synthesis.auth.allowed-origins` (CORS mit Credentials, Default Dev-Server),
  `synthesis.auth.bootstrap-admin`, `synthesis.auth.min-password-length`. Session-Cookie
  `SameSite=Lax`, `HttpOnly`, 12 h; `forward-headers-strategy: framework` für Reverse-Proxys.
- **Frontend:** `AuthService` (Signals), `authGuard`/`adminGuard`, 401-Interceptor (Sitzung
  verloren → `/login` mit Rücksprung), Seiten „Anmelden“ und „Benutzer“, Kopfzeile mit Nutzerchip
  und Abmelden. Design-Tokens (`--sy-*`) in `styles.css` als Grundlage für die weitere
  Modernisierung.
- **Bewusst nicht:** kein OAuth/OIDC, keine Datenbank, keine Szenario-Mandantentrennung. Alles
  drei lässt sich später aufsetzen (`UserDetailsService` austauschen, `createdBy` filtern), ohne
  den Statusvertrag der Pipeline zu berühren.
- **Verifiziert:** `AuthFlowTest` (@SpringBootTest + MockMvc gegen die echte Security-Kette:
  Bootstrap-Admin, 401 ohne Sitzung, 403 ohne CSRF-Token, Login, `ADMIN`/`USER`-Trennung bei
  `/api/users`, Besitzerschutz beim Löschen, Logout), `UserStoreTest` (Hash statt Klartext,
  Neustart ohne zweiten Bootstrap, letzter Admin geschützt); Frontend `AppComponent`-Spec mit
  Nutzerchip/Admin-Link; beide Gates grün (Zahlen im Commit).

### SPA-Auslieferung, Container-Image und CI-Ausbau — ✅ UMGESETZT (2026-11-04)
- **Anlass:** Für den Server-Betrieb brauchte die Plattform einen zweiten Webserver für das
  Angular-Frontend, CORS mit Credentials und einen Dev-Server-Proxy; das ist für einen
  Login-geschützten Betrieb unnötig fragil.
- **Mechanik:** `FrontendConfig` liefert den Build aus `synthesis.frontend.dir` oder
  `classpath:/static/` aus. Dateien mit Hash im Namen (`main-*.js`, `styles-*.css`, `media/`)
  bekommen `Cache-Control: max-age=1 Jahr, immutable`, `index.html` `no-cache`; Router-Pfade ohne
  Dateiendung fallen auf `index.html` zurück, `/api/**` und Dateinamen nie (404). `/api/health`
  ist ein anmeldefreier Lebenszeichen-Endpunkt für Healthchecks. Leere `allowed-origins` schalten
  CORS ab (same-origin).
- **Container:** `Dockerfile` (node → maven → JRE, unprivilegiert, Volume `/app/data`, optional
  `WITH_SUMO=true`), `docker-compose.yml`, `.dockerignore`. `.gitlab-ci.yml` bekommt
  `scripts-tests` (Python-`unittest`, 96 Tests, 4 Skips wegen optionalem `openpyxl`) und den
  Job `docker-image` (Registry-Push auf Default-Branch/Tags, sonst manuell).
- **Linux-Pfade:** `ExternalJavaToolchain` und `kit_java_home` suchten das KIT-JDK nur unter
  Homebrew/macOS; jetzt zusätzlich `/usr/lib/jvm/java-25-openjdk-*`, `temurin-25-jdk-*`,
  `/opt/java/openjdk-25`.
- **Ehrlicher Befund:** Der Docker-Build selbst konnte in dieser Umgebung nicht laufen (kein
  Daemon); die erste Abnahme ist `docker compose up --build` auf dem Zielsystem (docs/08).
- **Verifiziert:** `SpaFallbackTest` (@SpringBootTest: `/` → Forward auf `index.html`, `/vergleich`
  und `/login` → `index.html` mit `no-cache`; `main-*.js` immutable; `/media/*` 200; `/missing.png`
  und `/api/does-not-exist` 404; `/api/health` 200), `AuthFlowTest` erweitert um `/api/health` ohne
  Sitzung; Backend-/Frontend-Gates grün (Zahlen im Commit).

