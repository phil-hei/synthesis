---
name: synthesis-research-methodology
description: >
  Die Forschungsdisziplin des SyntheSis-Projekts — der Prozess, der aus einer Ahnung ein
  akzeptiertes Ergebnis macht. Laden, WENN du (a) sagst „ich habe eine Idee/Hypothese" und
  wissen willst, wie daraus ein sauberes Experiment wird, (b) ein Experiment aufsetzen willst
  (Experiment-Flag, Vorab-Kennzahlen, Seed-Protokoll), (c) fragst „wann ist etwas bewiesen?"
  oder „reicht diese Evidenz?", (d) entscheiden musst, ob ein Ergebnis akzeptiert, befördert
  oder verworfen wird, (e) einen Negativbefund sauber dokumentieren willst statt ihn
  wegzuoptimieren, (f) ein Ergebnis vor der Übernahme gezielt widerlegen (Red-Teaming) sollst,
  oder (g) neue Forschungsideen suchst und wissen willst, aus welchen Quellen hier historisch
  gute Ideen kamen. Stichwörter: Hypothese, Experiment, Evidenzlatte, Negativbefund,
  Widerlegung, Akzeptanzkriterium vorab, Ideen-Lebenszyklus, Spike, Stilllegung, Beförderung.
---

# SyntheSis-Forschungsmethodik: Von der Ahnung zum akzeptierten Ergebnis

Dieses Projekt hat den doppelten Anspruch **Publikation + Produktreife**. Beides verlangt
dieselbe Disziplin: Ergebnisse, die eine Review überleben, und Features, die man Nutzern
guten Gewissens per Default anschaltet. Dieser Skill beschreibt den PROZESS. Die konkreten
Mess- und Analyse-Rezepte liefert `synthesis-proof-and-analysis-toolkit`, den Evidenz- und
Test-Standard `synthesis-validation-and-qa`, die Beförderungs-Gates `synthesis-change-control`.

---

## 1. Die Evidenzlatte

**Ein Ergebnis ist akzeptiert, wenn EIN Mechanismus ALLE Beobachtungen erklärt — auch die
negativen — und einen zugewiesenen Widerlegungsversuch überlebt hat.**

Das ist die ganze Latte. Kein Teil davon ist optional:

- **EIN Mechanismus**: nicht drei Teilerklärungen, die zusammen zufällig passen. Wenn du zwei
  Mechanismen brauchst, hast du (noch) keinen verstanden.
- **ALLE Beobachtungen**: auch die unbequemen. Ein Mechanismus, der die Ausreißer „ignoriert",
  ist eine Kurvenanpassung, keine Erklärung.
- **auch die negativen**: Ein guter Mechanismus sagt vorher, was NICHT funktionieren wird —
  und wird gestärkt, wenn genau das eintritt.
- **zugewiesener Widerlegungsversuch**: jemand (zweite Session/Person) hatte explizit den
  Auftrag, das Ergebnis zu brechen, und hat es nicht geschafft (siehe Abschnitt 4).

### Durchgearbeitetes Beispiel: LLM-Moduswahl-Bias (2026-06-26, docs/06 Phase 3c)

Beobachtung: Die rohe LLM-Moduswahl (`LlmModeChoiceRunner`, Flag `population.llmModeChoice`)
liefert einen Modal-Split mit starkem Bias zu aktiven Modi — Rad ~35–45 % statt MiD 11 %,
Pkw deutlich zu niedrig; RMSE zu MiD ~0,19 gegenüber ~0,07 beim kalibrierten MNL.

Der akzeptierte Mechanismus: **„Das LLM wählt zeitrational das schnellste Verkehrsmittel,
weil ihm die kalibrierten modus-spezifischen Konstanten (ASCs) fehlen, die im MNL die
reale Pkw-Präferenz abbilden."** Dieser eine Mechanismus erklärt alle drei Beobachtungen:

1. **Rad-Überschätzung**: Der Runner füttert dem LLM je Distanzband die Reisezeiten je Modus.
   Auf kurzen und mittleren Distanzen (Größenordnung 2–5 km) ist das Rad zeitlich mit dem Pkw
   konkurrenzfähig — eine rein zeitrationale Wahl greift dort systematisch zum Rad. Genau dort
   entsteht der Überschuss.
2. **Pkw-Unterschätzung**: Die reale Pkw-Präferenz (Komfort, Gewohnheit, Verfügbarkeit) steckt
   im MNL in den ASCs — dem LLM fehlt sie, also wählt es den Pkw nur, wo er zeitlich klar
   gewinnt.
3. **Das Scheitern der quantitativen MiD-Verankerung** (der Widerlegungsversuch): Wäre der
   Bias bloß „fehlendes Wissen über den MiD-Split", hätte eine explizite MiD-Erdung im Prompt
   ihn beheben müssen. Sie half nicht — Qwen folgt der quantitativen Vorgabe nicht
   (siehe auch `synthesis-failure-archaeology`, Vorfall „LLM-Moduswahl mit MiD-Verankerung
   revertiert"). Der gescheiterte Fix ist keine Peinlichkeit, sondern die dritte Beobachtung,
   die den Mechanismus STÄRKT: Prompts ersetzen keine Kalibrierung.

Konsequenz: Das Ergebnis wurde als **ehrlicher Negativbefund** dokumentiert (docs/06,
„BEFUND"), das Feature bleibt eine experimentelle Vergleichslinse (`llmModeChoice`
default=false), und der Befund wurde zum Argument FÜR das kalibrierte MNL — statt den Bias
so lange wegzuprompten, bis die Zahlen zufällig stimmen. So sieht ein akzeptiertes Ergebnis
aus, auch wenn das Ergebnis „das Feature taugt (so) nicht" lautet.

---

## 2. Zahlen VOR dem Lauf

**Regel: Wer die Zahl nicht vorher aufschreibt, hat kein Experiment, sondern eine Anekdote.**

Vor jedem Experiment-Lauf diese Vorlage ausfüllen (kopieren, z. B. in die Session-Notizen
oder den späteren docs/06-Eintrag):

```markdown
## Experiment: <Kurzname>  (Datum, Seed(s), Szenario/Region)
- **Hypothese:** <Mechanismus in einem Satz>
- **Vorhergesagte Kennzahl:** <Name, z. B. RMSE zu MiD / Wege/Person·Tag / Δ Faktor-Änderung>
- **Richtung + Größenordnung (VORAB):** <z. B. „sinkt von ~0,17 auf <0,05" — VOR dem Lauf notiert>
- **Kommando/Messung:** <wie gemessen wird, z. B. kpis.json-Feld, Testklasse, summary.json>
- **Beobachtet:** <erst NACH dem Lauf ausfüllen>
- **Urteil:** bestätigt / widerlegt / unentscheidbar (+ warum; bei „unentscheidbar": was fehlt)
```

Ausfüllregeln:

- „Richtung + Größenordnung" ist Pflicht. „Wird besser" ist keine Vorhersage.
- Ein Lauf, dessen Kennzahl du erst nach dem Lauf auswählst, zählt nicht — das ist das
  Anti-Pattern Nr. 1 (Abschnitt 6).
- „Unentscheidbar" ist ein legitimes Urteil (z. B. Fallback kontaminiert, siehe Angriffsliste) —
  dann wird der Lauf wiederholt, nicht das Urteil weichgespült.
- Messrezepte und Auswertewege: `synthesis-proof-and-analysis-toolkit` und
  `synthesis-diagnostics-and-tooling`.

### Historische Anker (überliefert aus der Projektgeschichte, Stand 2026-07-08)

So sahen Vorab-Zahlen und Ergebnisse in diesem Projekt real aus — als Kalibrierung dafür,
welche Größenordnungen hier „Erfolg" bedeuten:

| Experiment | Vorab-Ziel | Erreicht | Beleg |
|---|---|---|---|
| ASC-Kalibrierung Modal-Split | RMSE < 0,02 (überliefertes Ziel) | 0,055 (MiD-Ziel) bzw. 0,086 (Custom-Ziel) je Lauf — Ziel verfehlt, Verbesserung (0,170→0,055) trotzdem dokumentiert | docs/06 Phase 1 (2026-06-21) |
| Netz-Feedback-Konvergenz | mittlere Faktor-Änderung Δ < 0,01 | 0,008 nach 2 Iterationen (Δ 0,33 → 0,008) | docs/06 Phase 2 (2026-07-06) |
| GTA-Wegehäufigkeit | ~3,1 Wege/Person·Tag (MiD) | 3,1 (nach Prompt-Tuning von roh 5,7) | docs/06 Phase 3b (2026-06-26), docs/07 §7 |

Lehrstück am ersten Anker: Das Ziel wurde VERFEHLT (0,055 statt <0,02) — und genau das steht
so in der Doku. Ein verfehltes Vorab-Ziel mit dokumentierter Teilverbesserung ist ein
brauchbares Ergebnis; eine nachträglich auf 0,055 „angepasste" Zielmarke wäre keins.

---

## 3. Ideen-Lebenszyklus

Jede Idee durchläuft diese Zustände. Kein Zustand wird übersprungen; jeder hat ein
explizites Ausgangs-Kriterium:

| Zustand | Was passiert | Ausgangs-Kriterium (weiter, wenn …) |
|---|---|---|
| **1. Ahnung** | Beobachtung/Idee formlos notieren | … sie sich als binäre Mechanik-Frage formulieren lässt („Kann X grundsätzlich Y?") |
| **2. Spike** | Kleinster Versuch, der die binäre Frage beantwortet — wegwerfbar, kein Produktionscode. Methoden: `synthesis-proof-and-analysis-toolkit` | … Antwort ja → weiter; Antwort nein → direkt Zustand 7 (Stilllegung, kurz) |
| **3. Experiment-Flag** | Feature hinter Szenario-Flag mit **default=false** in `backend/src/main/java/de/synthesis/scenario/Scenario.java` (+ Frontend-Verdrahtung, Checkliste in `synthesis-config-and-flags`). Verifizierte Muster im Repo: `calibrateModeChoice`, `llmModeChoice`, `sumoFeedback`, `publicTransport` — alle default=false | … Feature hinter dem Flag lauffähig, ohne den Default-Pfad zu verändern |
| **4. E2E-Verifikation** | Voller Lauf gegen die VORAB notierten Zahlen (Vorlage aus Abschnitt 2); Testklasse nach Repo-Muster (`backend/src/test/java/de/synthesis/generation/pipeline/`, z. B. `SumoRunnerTest`, `GtaBehaviorTest`, `LlmModeChoiceTest`, `PtIntegrationTest`) | … Vorab-Zahlen getroffen ODER Abweichung mechanistisch erklärt (Abschnitt 1) |
| **5. Dokumentierter Befund** | Datierter ✅-Eintrag in docs/06 (Format: `synthesis-docs-and-writing`) inkl. Zahlen | … Eintrag steht, Widerlegungsversuch (Abschnitt 4) überlebt |
| **6a. Beförderung** | Default-Flip / produktiver Status — Gates via `synthesis-change-control` | Endzustand (bleibt unter Beobachtung) |
| **6b. Bewusstes Opt-in** | Verifiziert, aber default=false bleibt — mit dokumentiertem Grund (meist: Kosten) | Endzustand, Grund steht in docs/06 + Flag-Javadoc |
| **7. Dokumentierte Stilllegung** | ❌-Eintrag in docs/06 mit **Blocker + Lösungsweg für einen späteren Anlauf**; Register: `synthesis-failure-archaeology` | Endzustand — Stilllegung OHNE Lösungsweg ist unvollständig |

### Belegte Beispiele je Endzustand (alle in docs/06 verifizierbar)

- **6b Opt-in (verifiziert, bewusst teuer):** `sumoFeedback` — Konvergenz live bewiesen
  (Δ 0,008 nach 2 Iterationen), bleibt aber opt-in, weil jede Iteration einen vollen
  logiTopp- **und** SUMO-Lauf kostet. Ebenso `calibrateModeChoice` — verifiziert
  (RMSE 0,170→0,055), opt-in wegen mehrerer Sim-Läufe je Kalibrierung.
- **6b Opt-in (bewusst experimentelle Vergleichslinse):** `llmModeChoice` — funktioniert
  technisch, hat aber den dokumentierten Bias (Abschnitt 1); bleibt als Vergleichslinse
  neben MNL und MiD, nicht als Ersatz.
- **7 Stilllegung MIT Lösungsweg:** Rail/Tram-ÖV in SUMO (❌ 2026-07-07, docs/06 Phase 2) —
  Blocker: `duarouter --mapmatch.distance` snappt Personen-Koordinaten ohne vClass-Filter
  auf Rail-Kanten („No destination edge for walk or personTrip"); `--mapmatch.junctions`
  verwirft Betroffene kommentarlos, kein `--mapmatch.vclass` vorhanden. Dokumentierter
  Lösungsweg: explizites vClass-gefiltertes Edge-Snapping in Java (eigenes Teilprojekt).
  Alle Rail-Änderungen zurückgerollt, Bus-ÖV blieb intakt (Suite 18/18 grün). DAS ist der
  Standard für eine Stilllegung: Der nächste Anlauf startet nicht bei null.

---

## 4. Zugewiesene Widerlegung (vor jeder Übernahme)

Bevor ein Befund akzeptiert (Zustand 5→6) wird, bekommt eine **zweite Session oder Person
den expliziten Auftrag, das Ergebnis zu BRECHEN** — nicht „mal drüberzuschauen". Der
Widerleger gilt als erfolgreich, wenn er eine alternative Erklärung oder eine Kontamination
findet; der Befund gilt als gestärkt, wenn er es trotz ernsthaften Versuchs nicht schafft.

Standard-Angriffsliste dieses Projekts (alle Punkte abarbeiten, Ergebnis je Punkt notieren):

```markdown
## Widerlegungsprotokoll: <Befund>  (Datum, Widerleger-Session)
- [ ] **Alternativer Mechanismus?** Gibt es eine zweite Erklärung, die dieselben
      Beobachtungen abdeckt? (Wenn ja: entscheidendes Experiment benennen.)
- [ ] **Seed-Variation:** Hält der Effekt über ≥3 Seeds (`population.seed`)? Oder ist er
      ein Ein-Seed-Artefakt?
- [ ] **Grenzfall-Region:** Hält er in einer winzigen UND einer großen Region? (Bekannt:
      kleine Gebiete verzerren Weglänge/Modal-Split systematisch, vgl. docs/05.)
- [ ] **Fallback-Kontamination:** Lief der Lauf wirklich geroutet — oder still auf
      Luftlinie/Synthetik? Job-Log auf „Fallback"-Meldungen prüfen (Overpass 504/429 →
      Luftlinien-Matrizen; LLM nicht erreichbar → actiTopp-Fallback; Stufen SKIPPED).
      Ein kontaminierter Lauf beweist NICHTS — Urteil „unentscheidbar", wiederholen.
- [ ] **Cache-Kontamination:** gtfs2pt-Ergebnis-Cache und GTA-Plan-Cache können alte
      Ergebnisse liefern; prüfen, ob der Cache-Schlüssel die geänderte Größe enthält
      (das GTA-Plan-Cache-Key-Problem beim Was-wäre-wenn-Text wurde genau so gefixt).
- [ ] **Stichproben-Effekt:** Hält der Effekt bei anderer `samplingFraction`? Oder ist er
      ein Kleine-Stichprobe-Artefakt?
```

Wer den eigenen Befund selbst „widerlegt" hat (gleiche Session), hat Punkt 1 strukturell
nicht erfüllt — die zweite Session ist der Punkt der Übung.

---

## 5. Woher gute Ideen historisch kamen

Handlungsregel: **Diese vier Quellen aktiv abgrasen** — sie haben nachweislich die
tragenden Ideen dieses Projekts geliefert. Wer „keine Ideen" hat, hat eine der vier Quellen
länger nicht besucht (was aktuell ansteht: `synthesis-research-frontier`).

1. **Paper-Lektüre → ganze Forschungsachse.** Das CHI'26-Paper (Lämmer/Colley/Ebel, GTA)
   wurde zur kompletten Achse-A-Erweiterung: LLM-Verhaltensquelle, LLM-Moduswahl,
   Was-wäre-wenn-Szenarien (docs/06, Abschnitte B + Phase 3).
2. **Negativbefunde → neue Frontier.** Der Befund „das Rastatt-Zielwahlmodell wählt externe
   Zonen nie / deckelt Fernwege" (docs/05, 2026-06-12) machte die zu kurzen Distanzen zum
   erkannten Kernproblem — daraus wurde die Distanz-Kampagne
   (`synthesis-distanz-kampagne`). Gescheiterte Experimente sind Ideen-Rohstoff, deshalb
   ist die Stilllegung mit Lösungsweg (Abschnitt 3) so wertvoll.
3. **Partner-Anforderungen → Features.** Die Takomat-Antworten vom 2026-07-03 (docs/06,
   Phase 5) — „Import erwartet Shapefiles (WGS84) mit Attribut districtID" — wurden direkt
   zum Shapefile-Export (`mobilecity/districts.shp/.shx/.dbf/.prj`, eigener
   ShapefileWriter, formatgeprüft per Test).
4. **Nutzer-/Domänen-Memos → Erweiterungen.** Vorgemerkte Ideen aus Projekt-Memos wurden
   zum Nachfrage↔Netz-Feedback (umgesetzt 2026-07-06) und zu den zusätzlichen
   Fahrzeugtypen im Lieferverkehr (Lkw/Lastenrad in SUMO, 2026-07-02).

---

## 6. Anti-Patterns (hier real beobachtet oder real abgewehrt)

1. **Ergebnis nach dem Lauf zum Ziel erklären.** Die Kennzahl stand nicht vorher fest →
   Anekdote, kein Experiment (Abschnitt 2). Auch subtil: Vorab-Ziel verfehlt, Zielmarke
   still nachziehen — das Gegenbeispiel-Vorbild ist der Kalibrierungs-Anker (Ziel <0,02,
   erreicht 0,055, ehrlich dokumentiert).
2. **Bias wegprompten statt Mechanismus verstehen.** Der Versuch, die LLM-Moduswahl per
   MiD-Zahlen im Prompt zu „erden", scheiterte und wurde revertiert — der richtige Umgang
   war die Mechanismus-Analyse (fehlende ASCs) + Negativbefund (Abschnitt 1).
3. **Erfolg per Augenschein.** „Die Karte sieht besser aus" ist keine Messung. Jede
   Behauptung braucht eine Kennzahl aus einem Artefakt (kpis.json, summary.json,
   statistics.xml, Test-Assertion).
4. **Experiment ohne Seed-Protokoll.** GTA war anfangs pro Lauf verschieden
   (temperature>0, Wege/Tag schwankten ~2,4–3,1) — erst der stabile Per-Request-Seed
   machte Ergebnisse vergleichbar (docs/06, 3b Reproduzierbarkeit). Seeds gehören in die
   Experiment-Notiz.
5. **Stilllegung ohne dokumentierten Lösungsweg.** Ein ❌ ohne „so ginge es beim nächsten
   Anlauf" zwingt den Nachfolger, die Sackgasse komplett neu zu erkunden. Vorbild:
   Rail/Tram-Eintrag (Abschnitt 3).
6. **Vergleichsläufe mit mehr als EINEM Unterschied.** Achse-A-Vergleiche liefen hier auf
   identischer Population (gleicher Seed, 651 Personen) mit genau einer getauschten
   Variablen — nur so ist „GTA vs. actiTopp" eine Aussage. Zwei gleichzeitige Änderungen =
   null attribuierbare Erkenntnis.

---

## Wann diesen Skill NICHT nutzen

- **Konkrete Mess-/Analyse-Rezepte** (wie messe ich X, welches Skript/Artefakt):
  `synthesis-proof-and-analysis-toolkit`; Werkzeuge: `synthesis-diagnostics-and-tooling`.
- **Test schreiben, Evidenz-Hierarchie, Akzeptanzkriterien, Fixtures**:
  `synthesis-validation-and-qa`.
- **Commit/Merge/Default-Flip und deren Gates**: `synthesis-change-control`.
- **Woran als Nächstes forschen** (Themenwahl statt Prozess): `synthesis-research-frontier`;
  laufender Anwendungsfall: `synthesis-distanz-kampagne`.
- **Was schon einmal scheiterte** (bevor du etwas erneut versuchst):
  `synthesis-failure-archaeology`.
- **Flag-Katalog/Defaults nachschlagen**: `synthesis-config-and-flags`; Architektur-Fragen:
  `synthesis-architecture-contract`; Doku-Formate: `synthesis-docs-and-writing`.
- **Betrieb, Setup, Debugging**: `synthesis-run-and-operate`, `synthesis-build-and-env`,
  `synthesis-debugging-playbook`.
- **Außendarstellung/Paper-Text**: `synthesis-external-positioning`; Domänen-Grundlagen:
  `verkehrsmodellierung-reference`.

---

## Provenienz & Wartung

- **Stand: 2026-07-08.** Verifiziert gegen: `backend/src/main/java/de/synthesis/scenario/Scenario.java`
  (Flags `calibrateModeChoice`, `llmModeChoice`, `sumoFeedback`, `publicTransport` — alle
  default=false), `docs/06-gta-integration-konzept.md` (LLM-Bias-Befund 3c 2026-06-26,
  Rail/Tram-Rollback 2026-07-07, Feedback-Konvergenz 2026-07-06, Takomat 2026-07-03),
  `docs/05-validierung.md` (Externe-Zonen-Befund, Fallback-Verhalten),
  `docs/07-ansatz-zusammenfassung.md` (Kernbefunde), Testklassen unter
  `backend/src/test/java/de/synthesis/generation/pipeline/`.
- **Als „überliefert" markierte Zahlen** (Vorab-Ziel RMSE<0,02; Interpretation des
  2–5-km-Bands beim Rad-Bias) stammen aus der Projektüberlieferung und sind in den docs
  nicht wörtlich belegt — bei Widerspruch gewinnen die docs.
- **Re-Verifikation (Einzeiler):** `grep -n "= false" backend/src/main/java/de/synthesis/scenario/Scenario.java && grep -n "❌\|BEFUND\|zurückgerollt" docs/06-gta-integration-konzept.md | head`
- Bei neuen Endzustands-Beispielen (Beförderung/Stilllegung) die Tabellen in Abschnitt 2
  und 3 ergänzen und das Stand-Datum aktualisieren.
