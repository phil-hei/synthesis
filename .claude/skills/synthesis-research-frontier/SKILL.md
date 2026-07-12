---
name: synthesis-research-frontier
description: >
  Die Forschungs-Frontier von SyntheSis: offene Probleme, bei denen dieses Projekt den
  Stand der Technik (SOTA) voranbringen kann — je mit SOTA-Lücke, projektspezifischem
  Asset, den ersten drei konkreten Schritten IN DIESEM REPO und einem falsifizierbaren
  Meilenstein. Laden, WENN du fragst „woran forschen?", „was ist offen?", „was wäre eine
  Publikationsidee?", „was ist das nächste Experiment?", „wo sind wir besser als SOTA?",
  oder wenn du eine neue Forschungsrichtung bewerten sollst. NICHT laden für die
  Durchführungs-Disziplin (synthesis-research-methodology), für Claim-Formulierungen nach
  außen (synthesis-external-positioning) oder für bereits verbrannte Pfade
  (synthesis-failure-archaeology).
---

# Research-Frontier

Sechs Frontiers, geordnet nach Publikationswert × Machbarkeit (Stand: 2026-07-08).
*SOTA* = State of the Art. Jeder Meilenstein ist so formuliert, dass er scheitern kann —
ein Ergebnis ohne Falsifizierbarkeit ist keins (synthesis-research-methodology).

## F1 — On-demand-Distanzkalibrierung der Zielwahl ★ höchste Priorität

**SOTA-Lücke:** Regionale Nachfragemodelle brauchen für neue Regionen eine
Parameter-Neuschätzung aus Erhebungs-Mikrodaten (Monate; MiD/MOP zugangsbeschränkt).
Ein On-demand-Weg über öffentliche Aggregate existiert nicht.
**Unser Asset:** Funktionierende Konstanten-Kalibrier-Schleife (`ModeChoiceCalibrator`,
backend/.../pipeline; logiTopp-Iterationen in Sekunden bei kleinen Samples) + belegter
Befund, dass der Distanz-Disnutzen dominiert (externe Zonen bei 25 km: 0 von 124k Trips
gewählt, 2026-06-12) + real gemessene Baseline (Ø 2,00 km, 2026-07-10).
**Erste 3 Schritte:** Stehen ausgearbeitet in **synthesis-distanz-kampagne** (Phasen 0–2).
**Ergebnis, wenn:** Distanzband-RMSE unter vorab notierter Schwelle bei Modal-Split-
Verschlechterung ≤0,02 und stabiler Wegehäufigkeit, reproduziert über ≥3 Seeds.

## F2 — Kalibrierte LLM-Moduswahl

**SOTA-Lücke:** LLM-Verkehrsagenten (CHI'26 „Generative Traffic Agents", Lämmer/Colley/
Ebel) haben KEINEN Kalibrier-Mechanismus; unser dokumentierter Negativ-Befund (2026-06-26):
roher LLM-Split hat Aktiv-Modi-Bias (Rad 35–45 % vs. MiD 11 %; RMSE ~0,19 vs. kalibrierte
MNL 0,07), und quantitative Prompt-Verankerung ist wirkungslos.
**Unser Asset:** `LlmModeChoiceRunner` (Distanzband-Architektur, seed-reproduzierbar) +
funktionierende ASC-Kalibrier-Mathematik im Nachbarmodul.
**Erste 3 Schritte:** (1) Korrekturschicht entwerfen: additive Log-Korrektur je Modus auf
die LLM-Splits (ASC-Analogon), NACH dem LLM-Call in `LlmModeChoiceRunner` — nicht im
Prompt (belegt wirkungslos). (2) Korrektur gegen MiD auf einem Lauf fitten
(ΔK=ln(target/llm), Clamp — `ModeChoiceCalibrator`-Formel). (3) Auf zweitem Seed/Szenario
validieren (Generalisierung, nicht Memorierung). 
**Ergebnis, wenn:** LLM-Pfad RMSE-zu-MiD < 0,07 (MNL-Niveau) auf einem NICHT zum Fitten
benutzten Lauf, Reproduzierbarkeit erhalten. Gescheitert, wenn die Korrektur je Region neu
gefittet werden muss und nicht überträgt — auch das wäre publizierbar (Negativ-Befund).

## F3 — Was-wäre-wenn-Batterien mit begründenden Agenten (GTA 3d)

**SOTA-Lücke:** Policy-Szenarien in klassischen Modellen = Parameter-Raten ohne
Begründungsebene; LLM-Agenten liefern Begründungen, aber niemand prüft deren KONSISTENZ
mit den messbaren Verschiebungen.
**Unser Asset:** `Scenario.population.gtaWhatIf` (Freitext) + PersonaSummary-Begründungen
(`GtaBehaviorRunner`) + Szenario-Vergleich (`GET /api/comparison`) + Seed-Protokoll.
**Erste 3 Schritte:** (1) Batterie definieren (3–5 Policies, z. B. „ÖV kostenlos",
„3 Tage Homeoffice"), je Policy VORAB die erwartete KPI-Richtung notieren. (2) Je Policy
3 Seeds × Baseline-Paar über die REST-API fahren (A/B-Isolation,
synthesis-proof-and-analysis-toolkit Rezept 7). (3) Begründungs-Konsistenz auswerten: passt die
Persona-Begründung (population/gta-personas.json) zur gemessenen Verschiebung?
**Ergebnis, wenn:** ≥1 Policy über 3 Seeds gleichgerichtete, vorab vorhergesagte
KPI-Verschiebung zeigt UND die Begründungen dazu konsistent sind. Gescheitert, wenn
Verschiebungen seed-dominiert streuen (dann: Stichprobengröße/Prompt-Stabilität als Befund).

## F4 — Multimodales Nachfrage↔Netz-Gleichgewicht

**SOTA-Lücke:** Gleichgewichts-Feedback ist Standard für MIV; gekoppelte Multi-Modus-
Feedbacks (Stau→Rad/ÖV-Shift) in agentenbasierten On-demand-Pipelines nicht.
**Unser Asset:** Konvergierende MSA-Schleife (`FeedbackMath.msaUpdate`,
`SumoRunner.computeOdFactors`; live: Δ 0,33→0,008 in 2 Iterationen, 2026-07-06) — bisher
nur auf die CAR_TTC-Matrix.
**Erste 3 Schritte:** (1) OD-Zeiten je Modus aus SUMO ableiten (edgedata + duarouter je
vClass — Achtung: für Fußgänger den Rail-Blocker-Befund beachten). (2) `MatrixGenerator`-
Kopplung: BIKE_TTC/PED_TTC analog zur CAR_TTC-Mischung skalieren. (3) Konvergenz mit 2
gekoppelten Modi messen (gleiche Toleranz). Umsetzung nur hinter Experiment-Flag
default=false + Gates (synthesis-change-control).
**Ergebnis, wenn:** Konvergenz (Δ<0,01) mit ≥2 gekoppelten Modi UND messbare
Kreuz-Elastizität (Stau-Anstieg ⇒ Rad-Anteil steigt) über ≥3 Seeds. Gescheitert bei
Oszillation trotz MSA (dann Dämpfungs-Analyse als Beitrag).

## F5 — Rail/Tram-Intermodalität via vClass-gefiltertem Edge-Snapping

**SOTA-Lücke:** Engineering-Frontier, kein Paper-Kandidat — aber Voraussetzung, um
„multimodal" ohne Fußnote zu behaupten (synthesis-external-positioning).
**Dokumentierter Blocker (2026-07-07):** `duarouter --mapmatch.distance` snappt Personen
ohne vClass-Filter auf Rail-Kanten („No destination edge"); `--mapmatch.junctions` routet
still 0 Personen. Details/Beleg: synthesis-failure-archaeology.
**Unsere Assets:** Fixture `validation/rastatt-rail-test.osm`; Rastatt-GTFS enthält Tram
995 + Rail 903 Fahrten; XML-Parsing-Muster für Netzdateien existiert
(`SumoRunner.writeCongestionGeoJson` liest plain.nod/.edg).
**Erste 3 Schritte:** (1) Spike (GANZE Kette!): Netz-XML in Java parsen, je Koordinate die
nächste Kante MIT `allow`-Filter (pedestrian bzw. passenger) bestimmen. (2)
`<personTrip from=EDGE to=EDGE>` statt `fromLonLat` schreiben, duarouter-Lauf gegen das
Rail-Netz. (3) `TransitRunner.modes` auf bus,train,tram erweitern (vClass-Mapping
train→rail existiert bereits in `writeVtypes`). Umsetzung nur hinter Experiment-Flag
default=false + Gates (synthesis-change-control).
**Ergebnis, wenn:** PtIntegrationTest-Erweiterung grün mit type="rail"-Fahrzeugen UND >0
gerouteten Personen auf dem Rail-Netz.

## F6 — Zensus-Germany-Path der Populationssynthese

**SOTA-Lücke:** Frei gewählte Region → amtliche Bevölkerungs-Marginals, ohne manuelle
Datenbeschaffung. **Dokumentierter Blocker (2026-06-12):** KIT-Framework ist an ARS-Keys/
Zensus-Regionaltabellen gebunden (nur Marne+Bayern gebündelt).
**Unsere Assets:** Funktionierender Seed-Pfad (`PopulationSample`,
backend/src/main/resources/kit-population-sample.csv als Einspeisepunkt, Format
householdId;age;sex) + geklontes Framework (validation/populationsynthesis).
**Erste 3 Schritte:** (1) Region→ARS-Lookup prototypen (Gemeinde-Geometrien, z. B.
öffentliches VG250). (2) Für EINE Ziel-Gemeinde die Zensus-Regionaltabelle beschaffen und
ins Framework-Format bringen. (3) FlatCensusOutput der Gemeinde als Sample-Austausch
einspeisen, KPI-Differenz zum synthetischen Sample messen.
**Ergebnis, wenn:** Für eine frei gewählte Gemeinde treibt ein echtes Zensus-Marginal die
Population und die Demografie-Kennzahlen (Altersverteilung, Haushaltsgrößen) weichen
messbar & erklärbar vom Marne-Sample ab.

## Wann diesen Skill NICHT nutzen

| Situation | Stattdessen |
|---|---|
| WIE ein Experiment sauber läuft | synthesis-research-methodology |
| Was darf nach außen behauptet werden | synthesis-external-positioning |
| Verbrannte Pfade / „schon probiert?" | synthesis-failure-archaeology |
| F1 konkret ausführen | synthesis-distanz-kampagne |

## Provenienz & Wartung (Stand: 2026-07-08)

Assets verifiziert (Klassen existieren unter backend/src/main/java/de/synthesis/generation/pipeline/,
Fixtures unter validation/); Befund-Zahlen sind überlieferte, datierte Einzellauf-Werte
aus docs/05/06/07. Frontiers veralten, wenn Kampagnen abgeschlossen werden — nach jedem
docs/06-✅/❌-Eintrag hier nachziehen.

Re-Verifikation:
```bash
ls backend/src/main/java/de/synthesis/generation/pipeline/ | grep -E "GtaBehavior|LlmModeChoice|ModeChoiceCalibrator|FeedbackMath|PopulationSample"
grep -n "gtaWhatIf" backend/src/main/java/de/synthesis/scenario/Scenario.java
grep -n "computeOdFactors" backend/src/main/java/de/synthesis/generation/pipeline/SumoRunner.java
ls validation/rastatt-rail-test.osm validation/populationsynthesis 2>/dev/null
grep -n "✅\|❌" docs/06-gta-integration-konzept.md | tail -5   # neue Einträge ⇒ Frontier prüfen
```
