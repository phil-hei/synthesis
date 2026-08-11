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
| logiTopp-/Retouren-Patch | `legacy/logitopp-returns-v1/` |
| actiTopp-CLI-Adapter | `legacy/actitopp-synthesis-v1/` |

## Kommandos

```sh
# Schnelle Prüfung für Backend + Frontend
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

`scripts/dev.sh` startet nur eigene Kindprozesse und beendet beim Abbruch auch nur
diese. Laufzeitlogs liegen gitignoriert unter `.runtime/logs/`. Ein bereits extern
laufender Ollama-Server wird weiterverwendet.

## CI

Die bisherigen portablen Backend-/Frontend-Jobs bleiben unverändert. Zusätzlich
existiert der manuelle beziehungsweise per `RUN_FULL_INTEGRATION=true` aktivierbare
Job `full-runtime-tests`. Er verlangt einen Shell-Runner mit Tag
`synthesis-runtime`, auf dem `scripts/preflight.sh full` erfolgreich ist.

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
