---
name: synthesis-proof-and-analysis-toolkit
description: >
  Die Erst-Prinzipien-Beweismethoden des SyntheSis-Projekts — „beweise es, statt es nur
  einzubauen". Laden, BEVOR du (a) ein externes Tool/Format integrierst (SUMO-Mechanik,
  GTFS, Fremd-Parser, Binärformate), (b) eine Behauptung über Systemverhalten akzeptierst,
  (c) eine Kalibrier-/Regler-Logik baust, (d) Reproduzierbarkeit zusichern sollst, oder
  (e) einen A/B-Vergleich oder eine Parameter-Sonde aufsetzt. Stichwörter: Spike, Beweis,
  Konvergenz, Fake-Sim, Roundtrip, Seed, Reproduzierbarkeit, Elastizität, Parser-Emulation,
  Forensik, "woher weißt du das?". NICHT laden für den Forschungs-PROZESS drumherum
  (synthesis-research-methodology) oder Test-Infrastruktur (synthesis-validation-and-qa).
---

# Proof- & Analysis-Toolkit

Neun Rezepte, mit denen dieses Projekt Behauptungen in Beweise verwandelt. Jedes Rezept:
Wann → Schritte → Gütekriterium → durchgearbeitetes Beispiel aus der Projekthistorie.

## Rezept 1: Empirisches Spike-Protokoll

**Wann:** Unsichere Mechanik eines externen Tools (SUMO, duarouter, gtfs2pt, LLM), BEVOR
Java-Code entsteht. *Spike* = Wegwerf-Experiment, das genau eine binäre Frage beantwortet.

**Schritte:** (1) Die Frage als Ja/Nein formulieren. (2) Minimalen Aufbau im
Scratch-Verzeichnis bauen (kleinstes Netz, 2–3 Trips/Personen). (3) Das Tool direkt
aufrufen, Ausgabe inspizieren. (4) **Die GANZE Kette abdecken, nicht nur den neuen Teil.**

**Gütekriterium:** Die Frage ist mit einer beobachteten Ausgabe beantwortet (Datei-Inhalt,
Exit-Code), nicht mit Doku-Lektüre.

**Beispiel positiv (2026-07-06, Fuß-Integration):** Frage: „Snappt duarouter
`<personTrip fromLonLat>` aufs Gehwegnetz und routet Fahrzeuge+Personen in EINEM Lauf?"
3 Test-Personen gegen das Rastatt-Netz → `<walk edges=…>` in der Ausgabe, kombinierter
Lauf ok → Implementierung danach fehlerfrei (docs/06).
**Beispiel negativ als Lehre (2026-07-07, Rail/Tram):** Der Netz-Teil wurde gespikt
(869 Rail-Kanten entstehen), die Personen-Routung auf dem Rail-Netz NICHT → kompletter
Rollback, weil duarouter Personen ohne vClass-Filter auf Gleise snappt.
**Regel daraus: Ein Spike, der nicht die ganze Kette fährt, beweist nichts.**

## Rezept 2: Parser-Emulations-Beweis

**Wann:** Ein Export muss von fremder Software gelesen werden, die du nicht ausführen
kannst (fehlende Runtime, Closed Deployment).

**Schritte:** (1) Quellcode des fremden Parsers lesen. (2) Seine Parse-Regeln in einem
kurzen Skript nachbauen (Python reicht). (3) Eigene Exporte durch die Emulation schicken;
jeden Lookup prüfen, den die echte Software macht.

**Gütekriterium:** Alle Lookups der Zielsoftware lösen gegen deinen Export auf.

**Beispiel (2026-06-21, MobileCity):** Der C#-`EpigeneCSVTableBuilder` splittet Tabellen
NUR an Zeilen == `(delimiter)*(fieldCount-1)`. Die Python-Emulation deckte auf, dass unsere
Kommas mit falscher Feldzahl die Splits brachen (`FindTable` hätte geworfen) → Fix
(FIELDS=8, BLANK-Zeile) VOR der Auslieferung. Beleg: docs/05/06, `MobileCityExporter`.

## Rezept 3: Seed-Reproduzierbarkeits-Beweis

**Wann:** Jede neue Zufalls- oder LLM-Quelle; jede Behauptung „reproduzierbar".

**Schritte:** (1) Identische Konfiguration zweimal ausführen. (2) Ausgaben byte- oder
wertweise vergleichen. (3) Neue Zufallsquellen an `scenario.population.seed` ableiten
(Muster: GtaBehaviorRunner `seed*31+persona.hashCode()`, LlmModeChoiceRunner `seed*131+band`).

**Gütekriterium:** Zweitlauf identisch; Test schützt die Eigenschaft dauerhaft.

**Beispiel (2026-06-26):** temperature 0.7 machte GTA-Läufe nicht-reproduzierbar
(Wege/P·Tag schwankte 2,4–3,1) → OpenAI-`seed` je Request; Ollama respektiert ihn
(byte-identisch verifiziert). Dauerhaft gesichert in
`GtaBehaviorTest.sameSeedIsReproducible` (backend/src/test/.../GtaBehaviorTest.java).

## Rezept 4: Konvergenz-Beweis per Fake-Simulation

**Wann:** Regler-/Kalibrier-Logik, deren echter Simulationslauf zu teuer für Unit-Tests ist.

**Schritte:** (1) Die Simulation durch ein analytisches Surrogat ersetzen (hier: Softmax
über ASCs). (2) Konvergenz als Test: erreicht der Regler die Toleranz in ≤N Iterationen?
(3) Rand-Semantik explizit testen (Clamp, Datei-Zustände).

**Gütekriterium:** Konvergenz-Test grün; Rand-Semantik dokumentiert.

**Beispiel:** `ModeChoiceCalibratorTest` — Softmax-Fake-Sim konvergiert RMSE<0,02 in ≤10
Iterationen; Clamp-Test beweist ±1,5/Iteration mit der Feinheit, dass die Ausgabedatei den
Zustand VOR dem letzten Update hält (nach N Iterationen steht `(N−1)·1,5` in der Datei).

## Rezept 5: Binärformat-Roundtrip-Beweis

**Wann:** Selbst geschriebene Binär-/Fremdformate (Shapefile, Matrizen).

**Schritte:** Format schreiben → mit UNABHÄNGIGEM Code (nicht dem Writer!) zurücklesen →
Magic Numbers, Struktur, Werte prüfen.

**Beispiel:** `ShapefileWriterTest` liest die .shp/.dbf binär zurück: file code 9994,
shape type 5, Bbox, Ring-Schluss, DBF-Feldwerte. Analog prüft `GtfsCropperTest` nach dem
Zuschnitt die referenzielle Integrität (jede trip_id in stop_times existiert in trips.txt).

## Rezept 6: Elastizitäts-Sonde

**Wann:** Bevor du eine Kalibrier-Schleife baust: Wirkt der Hebel überhaupt?

**Schritte:** (1) Vorhersage notieren (Richtung + Größenordnung — VOR dem Lauf,
synthesis-research-methodology). (2) EINEN Parameter ±X %. (3) EIN Lauf. (4) KPI-Delta
messen (Skripte aus synthesis-diagnostics-and-tooling).

**Gütekriterium:** |Delta| über Rauschniveau UND in vorhergesagter Richtung. Erst dann
Automatisierung bauen.

**Anwendungsfall:** Phase 2b der synthesis-distanz-kampagne (b_intrazonal-Sonde).

## Rezept 7: A/B-Isolations-Messung

**Wann:** Wirkungsnachweis eines Features/einer Quelle.

**Schritte:** (1) Identischer Seed + Region + Parameter, GENAU EIN Unterschied.
(2) Isoliertes Test-Backend, damit Produktiv-Szenarien unberührt bleiben (überliefertes
Muster: `mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8091
--synthesis.data-dir=<eigenes-verzeichnis>"`). (3) Vergleich über `GET /api/comparison?ids=…`.

**Beispiel (2026-06-26):** GTA vs. actiTopp auf identischer Population (seed 42,
samplingFraction 0.05, 651 Personen): Wege/P·Tag 3,1 vs. 3,53 — die Differenz ist dem
Quellen-Tausch zuschreibbar, weil ALLES andere identisch war.

## Rezept 8: Live-System-Forensik

**Wann:** Externes System verhält sich plötzlich anders (Rate-Limits, Timeouts).

**Schritte:** (1) Eigene Logs zuerst: Fallback-Meldungen greppen (Job-Log: „Fallback auf
Luftlinie", Mirror-Versuche). (2) Hypothese „selbst verschuldet?" VOR „Dienst kaputt"
prüfen (eigene Request-Frequenz der Session rekonstruieren). (3) Downstream-Signaturen
kennen: Luftlinien-Fallback ⇒ Fuß-Anteil ~50 %, Ø-Weg sinkt.

**Beispiel (2026-06-21):** Overpass 504/429 nach wiederholten schweren Netz-Queries in
EINER Session — selbst verschuldet; Fix: Mirror-Liste + OSM-Memoisierung pro Lauf.

## Rezept 9: Referenzwert-Verankerung

**Wann:** Jede Plausibilitätsaussage über Modellergebnisse.

**Schritte:** Externen Anker wählen (MiD 2017: Modal-Split .22/.11/.43/.14/.10, 3,1
Wege/P·Tag; Karlsruhe-Referenz für Altersgruppen-Raten) → Abweichung messen → erklären
oder als Limitation dokumentieren. NIEMALS „sieht plausibel aus".

**Beispiel (2026-06-21, POPtrips):** Kinder-Wegerate 2,50 vs. Referenz 3,16 — Rest-Gap als
actiTopp-Kalibrierungsgrenze ERKLÄRT und dokumentiert, nicht durch Parameter-Drehen
wegoptimiert.

## Wann diesen Skill NICHT nutzen

| Situation | Stattdessen |
|---|---|
| Prozess: Hypothese→akzeptiertes Ergebnis, Flag-Lifecycle | synthesis-research-methodology |
| Test-Infrastruktur, Fixtures, assumeTrue-Muster | synthesis-validation-and-qa |
| Fertige Messskripte/KPI-Interpretation | synthesis-diagnostics-and-tooling |
| „Wurde X schon probiert?" | synthesis-failure-archaeology |

## Provenienz & Wartung (Stand: 2026-07-10)

Beispiele belegt in: docs/05 (Parser-Emulation, Befunde), docs/06 (Spikes, GTA-Seed,
A/B-E2E, Overpass), backend/src/test/java/de/synthesis/generation/pipeline/
(ModeChoiceCalibratorTest, GtaBehaviorTest, ShapefileWriterTest, GtfsCropperTest).
Historische Zahlen sind überlieferte Einzellauf-Werte (datiert im Text).

Re-Verifikation:
```bash
ls backend/src/test/java/de/synthesis/generation/pipeline/   # genannte Testklassen existieren
grep -n "sameSeedIsReproducible" backend/src/test/java/de/synthesis/generation/pipeline/GtaBehaviorTest.java
grep -rn "seed \* 31\|seed\*31\|\* 131" backend/src/main/java/de/synthesis/generation/pipeline/ | head -3
grep -n "9994" backend/src/test/java/de/synthesis/generation/pipeline/ShapefileWriterTest.java
```
