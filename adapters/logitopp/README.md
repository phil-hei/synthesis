# Separater mobiTopp/logiTopp-Adapter

Build: `bash scripts/build-logitopp-adapter.sh` aus dem Repository-Hauptverzeichnis.
Java 25 wird über `runtime-common.sh` gewählt. Der Build führt vier Adaptertests
und anschließend `installDist` aus. Das Backend startet die fertige Distribution,
kein Gradle-Build erfolgt während der Nachfragegenerierung.

## Herkunft und Trennung

- `logitopp-core`: unveränderte Quellen des in `runtime/kit-sources.env` fixierten KIT-Commits.
- `rastatt-core`: unveränderte Quellen des dort fixierten Rastatt-Commits und separat
  bereitgestellte, per SHA-256 geprüfte generierte Wahlmodellklassen aus
  `legacy/logitopp-returns-v1/harness-artifacts`. Keine Anwendung des alten Patchvertrags.
- mobiTopp 0.3.580 und actiTopp 1.9.2.53: unveränderte, im Original-Rastatt-Commit enthaltene JARs.
- Eigene Klassen: `SynthesisDeliveryRunner`, `SynthesisSimulatorBuilder`,
  Nachfragekonfiguration/-modelle, Standortgruppierung und `SynthesisPrivateParcelBuilder`.

Eigene Java-Klassen liegen hier außerhalb der KIT-Checkouts. Der gemeinsame
Java-Paketname ist wegen der paketinternen KIT-Strategie `ReschedulingSkipTillHome`
nötig. **Keine gleichnamige KIT-Klasse wird ersetzt**, keine Bytecode-Manipulation
und kein Patch am KIT-Quellcode. `SynthesisSimulatorBuilder` enthält eine separate
Zusammenstellung auf Basis des ursprünglichen KIT-Builders; KIT-Klassen bleiben
zusätzlich unverändert verfügbar. Die bestehende Zielwahl-Diagnosesonde ist in
diesem Adapter nicht enthalten.

Originalquellen werden mit `git archive` in temporäre Verzeichnisse gelesen.
Lokale Änderungen und zusätzliche Runner in `validation/` gehen nicht ein.
Der Gradle-9.5-Wrapper wird ebenfalls aus dem Original-actiTopp-Commit gelesen.
Quelltexte und verfügbare Lizenzdateien werden in der Distribution aufbewahrt.

Die unveränderte mobiTopp-Binärversion benötigt die ältere SnakeYAML-API 1.17 und
Rhino als Java-ScriptEngine für Parameterformeln. Diese Abhängigkeiten sind
explizit fixiert. Es werden die vom Backend erzeugten Modellkonfigurationen verarbeitet.

## Vertrag

Distribution: `build/install/synthesis-logitopp-adapter`
Launcher: `bin/synthesis-logitopp-adapter <run-config.yaml>`
Marker: `synthesis-contract.properties`, Version `synthesis-logitopp-adapter-v2`.
Paketnachfrageschema bleibt `synthesis-parcel-demand-v1`.
Alle Arbeitsverzeichnispfade für Modellparameter bleiben relativ zur Distribution;
Szenario- und Ergebnisdateien werden absolut angegeben. Depots müssen ausdrücklich
vorliegen; es wird kein Ersatzdepot in einer beliebigen Zone angelegt.

Retouren werden über `useParcelFactory(...)` und die eigene Builder-Unterklasse
als Abholaufträge registriert. Die Tests prüfen genau einmalige Registrierung
für Zustellungen und Abholungen. Zwei weitere Tests sichern das Verhalten der
unveränderten KIT-Umschedulingstrategie bei Verspätungen ab.

## Abnahme und Grenzen

Siehe `docs/verification/2026-10-02-logitopp-adapter-v2.json` und
`docs/17-kit-adapter-und-haushaltskonzept.md`. Der Test prüft einen vollständigen
Wochenlauf und die Backend-Anbindung. Er belegt keine vollständige Zustellung
aller Pakete innerhalb dieser Woche und keine regionale Kalibrierung.
Tourplanung nutzt weiterhin die bestehende geometrische TSP-Strategie.
SUMO-Rückkopplung und eine eigene Priorisierung verspäteter Aktivitäten sind
separate nächste Schritte.
