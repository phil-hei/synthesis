# Reproduzierbare Laufumgebung

Stand: 2026-08-11

## Ziel

Ein Checkout soll nicht mehr von undokumentierten Inhalten unter `validation/`
abhängen. Versionen, Upstream-Commits, SyntheSis-Patches und Python-Pakete sind im
Repository festgeschrieben. Externe GPL-/MIT-Projekte bleiben aus Lizenz- und
Speichergründen eigene Prozesse.

## Verträge

| Bereich | Versionierte Quelle |
|---|---|
| Java/Node/Python/SUMO/Ollama | `runtime/versions.env` |
| KIT-Clones und Commits | `runtime/kit-sources.env` |
| Transit-Python-Pakete | `runtime/transit-requirements.txt` |
| logiTopp-/Retouren-Patch | `legacy/logitopp-returns-v1/` (Patches + `harness-artifacts/`) |
| actiTopp-CLI-Adapter | `legacy/actitopp-synthesis-v1/` |

## Kommandos

```sh
# Schnelle Prüfung für Backend + Frontend, inkl. Abgleich der Skill-Registries
scripts/preflight.sh core

# Gepinnte KIT-Laufprojekte und Transit-venv rekonstruieren
scripts/bootstrap-runtime.sh all

# Alle nativen Abhängigkeiten und Fixtures prüfen
scripts/preflight.sh full

# Backend, Frontend und bei Bedarf Ollama gemeinsam starten
scripts/dev.sh

# Standard- beziehungsweise vollständige Abnahme
scripts/verify.sh core
scripts/verify.sh full
```

`scripts/preflight.sh core` prüft seit dem 2026-08-20 zusätzlich, ob `.agents/skills/`
und `.claude/skills/` byte-identisch sind. Die Skills liegen doppelt vor: `.agents/`
wird gepflegt, `.claude/` wird geladen. Läuft das auseinander, arbeiten Agenten still
nach veralteten Anweisungen — der Drift ist sonst unsichtbar, weil beide Bäume
git-getrackt sind und nichts sie vergleicht. Die Prüfung ist bewusst ein Fehler und
kein Hinweis: Der Abgleich kostet einen `cp`, ein übersehener Hinweis kostet Tage.
Vor dem Kopieren die Richtung prüfen — welcher Baum der neuere ist, entscheidet der
Inhalt, nicht der Zeitstempel.

`scripts/dev.sh` prüft Backend und Frontend per HTTP und meldet die Anwendung erst nach beiden
erfolgreichen Healthchecks als bereit. Bereits laufende SyntheSis-Prozesse werden wiederverwendet;
eine fremde Belegung von Port 8090 oder 4300 bricht mit einer konkreten Meldung ab. Neu gestartete
Kindprozesse werden beim Abbruch beendet, bereits vorhandene Prozesse bleiben unberührt.
Laufzeitlogs liegen gitignoriert unter `.runtime/logs/`. Ein bereits extern laufender
Ollama-Server wird weiterverwendet.

## CI

Die bisherigen portablen Backend-/Frontend-Jobs bleiben unverändert. Zusätzlich
existiert der manuelle beziehungsweise per `RUN_FULL_INTEGRATION=true` aktivierbare
Job `full-runtime-tests`. Er verlangt einen Shell-Runner mit Tag
`synthesis-runtime`, auf dem `scripts/preflight.sh full` erfolgreich ist.

## KIT-Vertrag: Ausgangspunkt ist Upstream (seit 2026-08-13)

`legacy/logitopp-returns-v1/apply.sh` setzt auf dem **Upstream-Stand der gepinnten
Commits** auf, nicht auf einem lokal gewachsenen Zwischenstand. Der Vertrag besteht aus
drei Teilen:

| Teil | Inhalt |
|---|---|
| `mobitopp-rastatt.patch`, `logitopp.patch` | Upstream → Vertragszustand, **strikt** (ohne `--ignore-space-change`), inkl. Binär-Diff des Gradle-Wrapper-Jars |
| `harness-artifacts/` | 58 Dateien, die im Harness gitignoriert, zum Kompilieren aber zwingend sind: 42 DCML-generierte Klassen, 6 `.xmi`-Modelle, 8 `.gen`, 2 Config-Varianten — mit `manifest.sha256` |
| Hash-Guards in `apply.sh` | Je Repo Upstream- **oder** bereits installierter Vertragszustand; danach Verifikation von Nach-Zustand und Artefakt-Manifest |

Zwei Punkte, die dabei nicht offensichtlich sind: Der Vertrag enthält ein
**Gradle-Wrapper-Upgrade 6.3 → 9.5.0** — Upstream-Gradle 6.3 kann `SYNTHESIS_KIT_JAVA=25`
nicht bauen. Und der Vertrags-Build entfernt das Codegen-Plugin, weshalb die aus den
`.xmi` erzeugten Klassen als Artefakte mitgeliefert werden müssen statt generiert zu
werden. Beides war vor 2026-08-13 unversioniert und machte den Bootstrap nur scheinbar
reproduzierbar (Befund: `docs/05-validierung.md`, Abschnitt „Bootstrap-Vertrag
rekonstruiert").

**Nicht aufweichen:** Patches für diesen Vertrag niemals mit `--ignore-space-change`
erzeugen oder anwenden — solche Patches sind nicht rückrechenbar, und genau daran ist
die Vorversion gescheitert.

## Abnahmekriterium

Ein Rechner gilt als vollständig eingerichtet, wenn `scripts/preflight.sh full`
keinen Fehler meldet und `scripts/verify.sh full` Backend, Frontend, actiTopp, SUMO,
GTFS/PT und die laufende Ollama-Instanz ohne umgebungsbedingte Skips prüft. Für
logiTopp prüft die Vorprüfung Commit und Patch-Vertrag; ein vollständiger
logiTopp-Wochenlauf bleibt wegen Laufzeit und Speicherbedarf eine eigene fachliche
Abnahme.

Die erste Härtungsstufe verwendet bewusst gepinnte Quellen und maschinenlesbare
Verträge statt Container-Images. Ein selbst gehosteter Runner kann denselben Vertrag
mit `scripts/verify.sh full` ausführen. Container beziehungsweise vorkonfigurierte
Runner-Images und ein lokaler GraphHopper-PBF bleiben mögliche nächste Ausbaustufen.
