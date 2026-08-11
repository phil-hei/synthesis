---
name: synthesis-docs-and-writing
description: >
  Hausstil- und Pflege-Guide für die SyntheSis-Doku-of-Record (README.md + docs/01–07).
  Laden, WENN du (a) die Doku aktualisieren willst (Feature dokumentieren, Rollback/Blocker
  nachtragen, Befund festhalten), (b) eine Commit-Message schreiben sollst, (c) einen neuen
  docs-Abschnitt oder Phaseneintrag anlegst, (d) wissen musst, WELCHES Dokument für welchen
  Fakt zuständig ist (README vs. docs/05 vs. docs/06 vs. docs/07), oder (e) unsicher bist, wie
  datierte ✅/❌-Einträge, Befunde oder Zahlenangaben formatiert werden. Enthält wörtliche
  Templates für 06-Feature-/Rollback-Einträge, 05-Befunde und Commit-Messages.
---

# SyntheSis: Doku-of-Record pflegen & im Hausstil schreiben

Publikum: Mid-Level-Entwickler und KI-Assistenten (Sonnet-Klasse), die Doku oder
Commit-Messages für dieses Repo schreiben. Alle Pfade repo-relativ.

**Arbeitsteilung mit Geschwister-Skills:** DASS Doku im selben Zug wie Code aktualisiert
werden muss, ist ein Gate in `synthesis-change-control`. HIER steht das WIE: wohin welcher
Fakt gehört, in welchem Stil, mit welchen Templates. Außendarstellung (Paper, Folien,
Projektpartner) → `synthesis-external-positioning`.

## Wann NICHT nutzen

- **Code-Änderungen selbst** (Gates, Tests, Commit-Freigabe, Push-Verbot) →
  `synthesis-change-control`. Dieser Skill schreibt Texte, keine Freigaben.
- **Außenkommunikation** (Präsentationen für Takomat/KIT, Paper-Formulierungen,
  Marketing-Ton) → `synthesis-external-positioning`. docs/07 ist zwar Briefing-artig,
  bleibt aber interne Doku-of-Record.
- **Inhaltliche Fachfragen** (Was ist eine OD-Matrix? Was macht `gtfs2pt.py`?) →
  `verkehrsmodellierung-reference` bzw. die jeweiligen Betriebs-Skills.
- **Debugging-Notizen / Fehleranalyse führen** → `synthesis-debugging-playbook`,
  `synthesis-failure-archaeology`. Erst wenn ein Befund konsolidiert ist, landet er
  hier (in docs/05 oder docs/06).
- **Kein neues Doku-System erfinden**: keine Wikis, kein `CHANGELOG.md`, keine
  ADR-Verzeichnisse anlegen (siehe Anti-Patterns).

## 1. Karte des Doku-Systems (wer ist wofür zuständig)

| Dokument | Rolle | Was hinein gehört |
|---|---|---|
| `README.md` | Knapper Einstieg | Projektsatz + Links, Start-Befehle (Backend/Frontend), EIN Absatz Zwei-Achsen-Modell, EIN Absatz SUMO-Stand, Doku-Tabelle (verlinkt 01–07 mit Ein-Zeilen-Inhalt), „Kernaussagen (TL;DR)" (4 nummerierte Punkte) |
| `docs/01-analyse.md` | Analyse (weitgehend historisch) | mobiTopp/logiTopp-Ökosystem, MobileCity-Pipeline, simudrive-Referenz. Kopf trägt Datum: „Stand: 2026-06-10" |
| `docs/02-datenanforderungen.md` | Datenanforderungen | Mindest-Eingabedaten, was automatisch ableitbar / synthetisch generierbar ist |
| `docs/03-architektur.md` | Technische Zielarchitektur | Module, Pipeline-Stufen, Schnittstellen — Konzeptänderungen landen hier (und in 06) |
| `docs/04-roadmap.md` | Roadmap der MVP-Ära | MVP-Schnitt, Risiken — teils historisch; die LEBENDE Roadmap ist Abschnitt F in docs/06 |
| `docs/05-validierung.md` | **Befund-Register** | Nummerierte, tabellarische Befunde (Format-Befunde 1–8, `L1…L6`, `A1…A12`) + datierte Validierungs-Abschnitte. Stil: Befund → Evidenz → Konsequenz |
| `docs/06-gta-integration-konzept.md` | **Lebendes Konzept + Phasen-Chronik** | Zwei-Achsen-Modell, Integrationsstand (Abschnitt A), Konzept (B–E), Roadmap mit Phasen 0–3/C und datierten ✅/❌-Einträgen (Abschnitt F). **Das De-facto-CHANGELOG des Projekts** |
| `docs/07-ansatz-zusammenfassung.md` | Kompaktes Briefing | 14-Stufen-Pipeline-Tabelle, Datenfluss-ASCII, Kernbefunde-Liste, Tech-Stack — spiegelt nur den GESAMTSTAND |

**Regel „eine Heimat pro Fakt":** Detailhistorie (wer wann was mit welchen Zahlen) wohnt in
docs/06 (Features/Phasen) bzw. docs/05 (Befunde). README und docs/07 spiegeln nur den
aktuellen Gesamtstand und VERLINKEN auf die Heimat — Belegbeispiel README:
> „(Befund + Lösungsweg im Abschnitt ‚Phase 2 – SUMO' von [docs/06-gta-integration-konzept.md](docs/06-gta-integration-konzept.md))"

## 2. Hausstil (aus den echten Dateien belegt)

Alle Beispielzeilen sind wörtliche Zitate aus dem Repo (Stand 2026-07-08).

1. **Deutsch als Dokumentsprache; Fachbegriffe, Tool- und Dateinamen englisch in Backticks.**
   > docs/06: „`GtaBehaviorRunner` baut pro Person eine Persona und" — deutscher Satz, Code-Bezeichner in Backticks.

2. **Datierte Status-Einträge mit ✅/❌.** Überschriften- oder Bullet-Format
   „✅ UMGESETZT (JJJJ-MM-TT)" bzw. „(✅ JJJJ-MM-TT)":
   > docs/06: „### Phase 1 – On-demand-Kalibrierung (größter Plausibilitäts-Hebel) — ✅ UMGESETZT (2026-06-21)"
   > docs/06: „- **ÖV/Bus via GTFS — Phase C (✅ 2026-07-07):** GTFS-Fahrplan → `gtfs2pt.py` → …"

3. **Negative Befunde sind Pflichtinhalt, keine Peinlichkeit.** Gescheiterte Versuche werden
   als „ehrlicher Befund" mit Blocker UND Lösungsweg dokumentiert — in der Heimat (06)
   UND als Kurzhinweis in README/07:
   > docs/06: „- **Rail/Tram versucht, zurückgerollt (2026-07-07) — ehrlicher Technik-Befund:** die Rastatt-Region"
   > docs/07: „**Ehrlicher KI-Befund:** rohe LLM-Moduswahl hat einen **Bias zu aktiven Modi** (zu viel Rad),"
   > README: „ÖV ist aktuell auf **Bus** beschränkt – Schienen-ÖV (Tram/Rail) wurde versucht, scheitert aber an"

4. **Zahlen immer mit Kontext, bevorzugt vorher→nachher.** Nackte Zahlen sind wertlos;
   das Muster ist `Wert_alt → Wert_neu` plus Referenz (MiD, Ziel, Iteration):
   > docs/06: „RMSE **0,170 → 0,055** monoton; Auto 22→32 %, Fuß 53→26 % Richtung MiD."
   > docs/07: „nach 2 Iterationen das Gleichgewicht (Δ 0,33 → 0,008; Ø Faktor 1,32) → Pkw 27 %→24 %, Fuß 45 %→49 %"

5. **Überlieferte/volatile Messwerte datieren.** Laufzeiten, Trefferquoten, Feed-Größen
   gelten nur für einen Stand — Abschnitte tragen Datum im Titel:
   > docs/05: „## logiTopp-Simulationslauf (2026-06-11) — erfolgreich"

6. **Befund-Stil in docs/05: Befund → Evidenz → Konsequenz**, tabellarisch mit laufender
   Nummer (Format-Befunde `1–8`, logiTopp `L1…`, Adapter `A1…`):
   > docs/05: „| A9 | `DistributionCenterParser` referenziert die Zone — anders als die demand-data! — über die **externe ID** (`getByExternalId`); …"
   Freitext-Befunde beginnen mit „**Befund**:" und enden mit „**Konsequenz**" / „Fix":
   > docs/05: „**Befund**: Bei 25 km wählt das **Rastatt-Zielwahlmodell die externen Zonen nie** (0 Wege von"

7. **Verifikation explizit ausweisen.** Feature-Einträge schließen mit „**Verifiziert:** …"
   plus konkreten Zahlen/Testnamen:
   > docs/06: „- **Verifiziert:** `SumoRunnerTest` führt den kompletten Pfad gegen echtes Rastatt-Demand-CSV + OSM aus"

8. **Kein Oversell, Vorbehalte benennen.** Einschränkungen stehen direkt beim Claim:
   > docs/06: „### A.2 Architektur-Realität (wichtiger Vorbehalt zu „vollständig integriert")"

## 3. Update-Checkliste je Änderungstyp

| Änderungstyp | Pflicht-Updates (in dieser Reihenfolge) |
|---|---|
| **Neues Feature** | 1) docs/06 Abschnitt F: datierter ✅-Phaseneintrag (Template a) mit „Verifiziert". 2) Falls nutzer-/betriebsrelevant: README-Absatz ergänzen/anpassen. 3) docs/07: Pipeline-Tabelle (Stufen-Zeile), Datenfluss-ASCII, ggf. Kernbefunde + Tech-Stack. 4) README-Doku-Tabelle: Ein-Zeilen-Inhalt der betroffenen docs aktualisieren (z. B. „inkl. Rad/Fuß/ÖV-GTFS") |
| **Rollback / gescheiterter Versuch** | docs/06: ❌-Eintrag „versucht, zurückgerollt (JJJJ-MM-TT) — ehrlicher Technik-Befund" (Template b) mit Blocker + Lösungsweg; Kurzhinweis in README und/oder docs/07-Kernbefunde mit Link auf 06. NIE stillschweigend entfernen |
| **Neuer Befund** (Format, Fremdsystem-Verhalten, Messung) | docs/05: laufende Nummer im passenden Register (Format-Nr. / `L`-Serie / `A`-Serie) oder datierter Abschnitt (Template c); wenn er die Pipeline ändert: „Fix in unserer Pipeline"-Spalte füllen |
| **Konzept-/Architekturänderung** | docs/03 (Zielarchitektur) + docs/06 (Konzeptabschnitte B–E bzw. A „Ausgangslage"); README-Zwei-Achsen-Absatz nur bei Achsen-Änderung |
| **Neue Messwerte/KPIs** | In der Heimat (05 oder 06) mit Datum + vorher→nachher; docs/07-Kernbefunde nur, wenn folientauglich |

Prüffrage am Ende jeder Doku-Änderung: „Steht der Fakt genau EINMAL ausführlich (05/06)
und überall sonst nur als Spiegel/Link (README/07)?"

## 4. Commit-Stil (aus `git log` belegt)

Beobachtete Konvention über die gesamte Historie (17 Commits, `729acdb…4ad6382`):

- **Titel:** `Bereich: prägnante deutsche Zusammenfassung`, ≤ ~70 Zeichen. Bereiche im
  Log: `SUMO:`, `Doku:`, `Kalibrierung:`, `Netz-Feedback:`, `MobileCity:`, `Refactoring:`,
  `Härtung:`, `Szenario-Vergleich:`. Beispiele (wörtlich):
  > `SUMO: ÖV/Bus multimodal via GTFS + gtfs2pt.py (Achse B, Phase C)`
  > `Doku: SUMO-multimodal-Stand (Rad/Fuß/ÖV) + Rail/Tram-Befund nachtragen`
- **Body = WARUM + Kernentscheidungen**, keine Aufzählung des Offensichtlichen. Belegt:
  der Doku-Commit `4ad6382` beginnt mit dem Warum („docs/06 … und docs/07 … hinkten dem
  Code hinterher"), nicht mit „Update docs". Technische Entscheidungen mit Begründung:
  > `4a055bd`: „TransitRunner.writeVtypes bildet den OSM-Modus auf die SUMO-vClass ab … Grundlage fuer spaeteres Rail/Tram (aktuell modes=bus, da das SUMO-Netz road-only ist)."
- **Verifikation im Body**, wo vorhanden:
  > `ad7f0ba`: „Verifiziert: PtIntegrationTest faehrt die volle Kette (gated per SYNTHESIS_TRANSIT_VENV); Suite 18/18 gruen (PT-Test ohne venv skipped)."
- **Schlusszeile bei KI-Beteiligung** (Modellname eingesetzt, belegt mit
  `Codex Sonnet 5`, `Codex Opus 4.8`, `Codex Fable 5`):
  > `Co-Authored-By: Codex <Modell> <noreply@anthropic.com>`
- **Kein `git push` aus der CLI** — Freigabe/Push-Prozess ist Sache von
  `synthesis-change-control`. Dieser Skill liefert nur den Message-Text.

## 5. Templates (wörtlich einbaubar)

### (a) docs/06 — Feature-/Phaseneintrag (Abschnitt F)

```markdown
### Phase <N> – <Titel> (<Einordnung, z. B. „größter Plausibilitäts-Hebel">) — ✅ UMGESETZT (JJJJ-MM-TT)
- **<Kernmechanik>:** `<Klasse/Flag>` <was es tut, 1–3 Sätze>; Steuerung: Flag
  `<scenario.flagName>` (Default <an/aus>, Frontend-<Checkbox/Dropdown> „<Label>").
- **<Bewusste Entscheidung>:** <Warum so und nicht anders (z. B. externer Prozess,
  pyproj-frei); welcher Blocker damit umgangen wird>.
- **Verifiziert:** <Testname/Lauf> (<konkrete Zahlen: N Fahrzeuge/Trips, Kennwert
  vorher→nachher, Laufzeit>); <UI im Browser bestätigt / Suite N/N grün>.
- *Offen/optional:* <bewusst zurückgestellte Punkte>.
```

### (b) docs/06 — Rollback-Eintrag

```markdown
- **<Feature> versucht, zurückgerollt (JJJJ-MM-TT) — ehrlicher Technik-Befund:** <Ausgangslage
  mit Zahlen (was da war, was probiert wurde)>. Implementiert war <Umfang>. **Blocker:**
  <präzise technische Ursache im Fremdsystem, mit Tool-/Optionsnamen in Backticks und
  ggf. wörtlicher Fehlermeldung>. <Lösungsweg(e) für später, mit Aufwands-Einschätzung>.
  Die Änderungen wurden zurückgerollt, <was solide bleibt> (Test-Suite N/N grün).
```

Danach: Kurzhinweis in README und/oder docs/07-Kernbefunde („**Ehrlicher Technik-Befund:** …
Bewusst zurückgestellt statt halbfertig ausgeliefert (Details: [docs/06](…)).").

### (c) docs/05 — Befund

Tabellenform (in bestehendes Register mit nächster laufender Nummer, Serien: Format-Nr. /
`L<n>` logiTopp / `A<n>` Legacy-Adapter):

```markdown
| <Serie><n> | <Befund: was das Fremdsystem wirklich tut, mit `Klasse`/`Datei`-Beleg — Evidenz in Klammern (Zahlen, Fehlermeldung)> | <Fix/Konsequenz in unserer Pipeline> |
```

Freitextform (neuer datierter Abschnitt):

```markdown
## <Thema> (JJJJ-MM-TT) — <erfolgreich | teilweise wirksam | gescheitert>

<1–2 Sätze: Was wurde getan/geprüft.>

**Befund**: <Kernaussage fett am Anfang> (<Evidenz: Zahlen, Dateien, Fehlermeldung>).

**Konsequenz**: <Was die Pipeline/Roadmap daraus macht; nächster Hebel>.
```

### (d) Commit-Message-Gerüst

```text
<Bereich>: <prägnante deutsche Zusammenfassung, ≤ ~70 Zeichen>

<WARUM: 1–3 Sätze Ausgangslage/Problem — nicht das Offensichtliche wiederholen.>

- <Kernentscheidung 1 mit Begründung (`Klasse`/Flag in Backticks)>
- <Kernentscheidung 2 …>

Verifiziert: <Test/Lauf + konkrete Zahlen; „Suite N/N grün">.

Co-Authored-By: Codex <Modell> <noreply@anthropic.com>
```

Die `Co-Authored-By`-Zeile nur bei KI-Beteiligung; `<Modell>` durch das tatsächliche
Modell ersetzen (Historie: `Codex Sonnet 5`, `Codex Opus 4.8`, `Codex Fable 5`).

## 6. Anti-Patterns

- **Kein separates `CHANGELOG.md` anlegen.** Bewusste Entscheidung: docs/06 (Phasen-Chronik
  mit datierten ✅/❌-Einträgen) + `git log` decken die Historie ab. Ein drittes Register
  würde die „eine Heimat pro Fakt"-Regel brechen.
- **Keine typographischen Anführungszeichen („…", ‚…') in HTML-`title`-Attributen**
  (Frontend-Templates): bricht den Angular-AOT-Build (NG5002). In Markdown-Doku sind sie
  dagegen Hausstil.
- **Keine absoluten Nutzer-Pfade in der Doku** (`/Users/…`): immer repo-relativ
  (`validation/mobitopp-rastatt`, `backend/data/scenarios/<id>/artifacts/`).
- **Keine undatierten Messwerte** („dauert ~7 s" ohne Stand) und **keine Zahlen ohne
  Vergleichskontext** (vorher→nachher bzw. Referenz wie MiD 2017).
- **Fehlschläge nicht verschweigen oder weichspülen** — der ❌-/„ehrlicher Befund"-Eintrag
  ist Pflicht, inkl. Blocker und Lösungsweg.
- **Detailhistorie nicht in README/07 duplizieren** — dort nur Gesamtstand + Link.

## Provenienz & Wartung

- **Stand: 2026-07-08.** Ground Truth: `README.md`, `docs/01`–`docs/07` (vollständig bzw.
  quergelesen), `git log` (alle 17 Commits `729acdb…4ad6382`). Alle Zitate wörtlich aus
  diesen Dateien; Commit-Beispiele aus den echten Commit-Bodies.
- **Volatil, bei Abweichung diesen Skill aktualisieren:** die Liste der Bereichs-Präfixe
  (Abschnitt 4), die Befund-Serien in docs/05 (aktuell 1–8/L/A), der Zuschnitt „docs/04
  teils historisch / lebende Roadmap in docs/06 F", die Modellnamen in `Co-Authored-By`.
- **Wartungsregel:** Wenn ein neues Doku-Dokument (docs/08+) entsteht oder ein Register
  umzieht, zuerst die Karte in Abschnitt 1 und die Checkliste in Abschnitt 3 nachziehen.
- Autor: ausscheidender Principal Engineer (Doku-Übergabe), erstellt mit Codex.
