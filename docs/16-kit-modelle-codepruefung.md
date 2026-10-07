# Codeprüfung: actiTopp, mobiTopp, logiTopp und MobileCity-Export

**Aktueller Implementierungsstand:** actiTopp ist inzwischen auf einen separaten Adapter mit unveränderten Originalquellen und tatsächlichen Haushaltsdaten umgestellt. Native Tests und ein Lauf mit 1.966 Personen sind erfolgreich. [Umsetzung und verbleibende Aufgaben](17-kit-adapter-und-haushaltskonzept.md). Die folgenden Abschnitte dokumentieren überwiegend den ursprünglichen Auditbefund vor der Umstellung.

Stand: 01.10.2026. Prüfung der lokalen Quellstände, tatsächlichen Prozessaufrufe, vorhandenen Laufartefakte und gezielter Tests. Kein neuer vollständiger logiTopp-/SUMO-Lauf.

## Aufgaben und tatsächliche Anbindung

| Modell | Aufgabe in SyntheSis | Codepfad |
|---|---|---|
| actiToppNG | Wochenaktivitäten aus Personenmerkmalen erzeugen | `GenerationService.stageActivityPlans` → `ActitoppRunner.run` → Gradle `runSynthesis` → `SynthesisRunner.kt` → `DefaultPlanGeneration` |
| mobiTopp | Personenmobilität: variable Zielzonen, Verkehrsmittel und Personenwege | Als Bibliothek im `mobitopp-rastatt`-Laufprojekt. `SimulatorBuilder.buildDelivery` erstellt Ziel-/Moduswahl und nutzt diese im kombinierten Nachfragelauf. |
| logiTopp | Ergänzt private Paketnachfrage, Zustellabläufe und Touren | `SimulationRunner.run` → Gradle `runDeliverySynthesis` → `ShortTermModuleDelivery` → `SimulatorBuilder.buildDelivery` |

Es sind keine drei völlig voneinander getrennten Simulationen. Die Aktivitätenplanung läuft vorher extern; Personenmobilität und Logistik laufen gemeinsam im angebundenen KIT-Laufprojekt. Die separate Bevölkerungssynthese in SyntheSis ist nicht actiTopp.

## Versionen und Quellgrundlage

Die lokalen HEADs stimmen mit `runtime/kit-sources.env` überein:

| Komponente | Quelle / Version |
|---|---|
| actiToppNG | `kit-ifv/actitoppNG`, Commit `69729de78326dca5e4853c94bac1161d51592513` |
| logiTopp | `kit-ifv/logitopp`, Commit `70f4f6771034f4d1ec9050bb3744114428f67fa3` |
| Laufprojekt | `kit-ifv/mobitopp-rastatt`, Commit `8ba241b6d1cd4dbb9e0e961acd9f381a0f454611` |
| mobiTopp-Bibliothek | `edu.kit.ifv.mobitopp:mobitopp:0.3.580` |
| lokale logiTopp-Bibliothek | `edu.kit.ifv.mobitopp:logitopp:0.0-synthesis-returns-v1` |
| weitere actiTopp-Abhängigkeit im Laufprojekt | `edu.kit.ifv.mobitopp:actitopp:1.9.2.53`; nicht mit dem vorgelagerten actiToppNG-Prozess gleichzusetzen |

Die ausgeführte Grundlage ist **Upstream plus SyntheSis-Anpassungen**, nicht unveränderter Upstream. Die Verträge liegen unter `legacy/actitopp-synthesis-v1/` und `legacy/logitopp-returns-v1/`: CLI-Adapter, Buildanpassungen, Laufprojektintegration und Logistik-/Retourenanpassungen. Die frühere Vorgabe, künftig fremden Code nicht zu ändern, ist deshalb als Architekturziel für weitere Arbeiten von bereits vorhandenen Patches zu unterscheiden.

## Funktionsnachweise und aktueller Laufzeitblocker

**Nachprüfung am 01.10.2026:** Java 25 ist über Homebrew vorhanden, wird aber von `java_home -V` nicht aufgelistet. Mit diesem JDK zeigt sich ein zweiter Fehler: ein doppelt eingebundener SyntheSis-Runner. Ein separat gebauter Adapter hat inzwischen mit derselben Kern-JAR unter Java 25 und Java 26 jeweils gültige Aktivitätsausgaben erzeugt. Der folgende Erstbefund dokumentiert den zuvor verwendeten Startweg; aktueller Stand und Migrationskonzept: [separate Adapter und Haushalte](17-kit-adapter-und-haushaltskonzept.md).

- Gezielter Testlauf: `ActitoppIntegrationTest`, `ActitoppRunnerTest`, `SimulationRunnerTest`.
- Ergebnis: **7 Tests, 6 bestanden, 1 Fehler, keine übersprungenen Tests**.
- Der echte actiTopp-Prozesstest scheitert bereits beim Auflösen der Java-Toolchain, nicht bei der Wochenplanung.
- Direkter Gradle-Aufruf bestätigt: Java **25** wird verlangt, aber nicht gefunden. `/usr/libexec/java_home -V` meldet nur Temurin **26.0.2**. Ein Versionswunsch an `java_home` garantiert keine tatsächlich passende Version.
- [Gesichertes Fehlerprotokoll](verification/2026-10-01-actitopp-native.log).
- Auch das logiTopp-Laufprojekt verlangt Java 25; ein frischer erfolgreicher nativer Lauf ist damit heute nicht belegt. Kein SDK installiert und keine Modellversion verändert.

Historische lokale Nachweise:

- `backend/data/scenarios/0e020eab/artifacts/population/actitopp/actitopp.log`: Meldung über erzeugte Pläne für 1.874 Personen, 92 Kleinkinder übersprungen; Schedule-Datei vorhanden. Das ist ein historischer Beleg, kein Test des heutigen vollständigen Zustands.
- `backend/data/scenarios/8ab8366a/artifacts/simulation/run.log`: erfolgreicher logiTopp-Lauf, gespeichertes Protokoll vom 17.08.2026, ca. 83 Sekunden.
- Zugehörige `kpis.json`: 2.000 Personen, 43.078 Wege, 1.525 private Paketaufträge, 469 als zugestellt ausgewiesen. Die geringe Zustellquote ist keine fachliche Vollabnahme; der Lauf war eine Zielwahl-Kampagne.
- Rohdateien einschließlich `demandsimulationResult.csv`, `parcel-orders-private.csv`, `planned-tours.csv`, `parcel-states.csv` und `vehicle_events.csv` sind vorhanden.

**Fazit:** Die echte Integration ist implementiert und wurde bereits ausgeführt. Aktuelle technische Einsatzfähigkeit und fachliche Kalibrierung dürfen daraus nicht pauschal abgeleitet werden. Zuerst Java 25 wieder verfügbar machen, dann den nativen Test und einen kleinen vollständigen Nachfrage-/Logistiklauf wiederholen.

## Wichtige fachliche Einschränkungen im Code

1. `ActitoppRunner.writeInput` übergibt nur ID, Alter, Erwerbsstatus und Geschlecht. Haushalts-ID und tatsächlicher Pkw-Bestand werden nicht übergeben.
2. `SynthesisRunner.kt` erstellt pro Person einen vereinfachten Haushaltskontext: Raumtyp `PROVINCIAL`, ein Pkw bei Erwachsenen, Kinderzahlen aus dem Alter der einzelnen Person. Vollständige Haushaltszusammensetzung und regionale Raumtypen kommen nicht durch. Eine gemeinsam abgestimmte Haushaltswochenplanung ist damit nicht belegt.
3. Unter Sechsjährige werden im actiTopp-Adapter ausgelassen und durch SyntheSis-Ersatzpläne behandelt. Bei fehlendem/fehlgeschlagenem actiTopp kann die Pipeline vollständig auf Vorlagen wechseln; `activitySource` und Log prüfen.
4. Der logiTopp-Aufruf integriert Personenmobilität und private Pakete. Betriebliche Paketmodelle werden über Nullmodelle deaktiviert.
5. Die Tourstrategie ist aktuell `TspBasedDeliveryTourStrategy`; Lieferfahrzeuge nutzen überwiegend Pkw-Matrixzeiten. Technische Lauffähigkeit bedeutet keine straßengenaue Touroptimierung oder regionale Validierung.

## Export nach Schritt 7 oder 8?

Gemeint sind hier die acht **fachlichen Präsentationsphasen**, nicht die 15 technischen Stufen.

- **Heute nach fachlicher Phase 6:** `MobileCityPopulation` liest `activity.csv`, der Regionalexporter liest gemeinsame Matrizen. Der Export findet technisch vor den Nachfragesimulationen statt.
- **Nach Phase 7:** Sinnvoll, wenn tatsächlich erzeugte Personenwege, Paketaufträge, Stopps oder Touren die Exportbasis werden sollen. Logistikunterstützung im Zielmodell allein erzwingt das nicht: Wenn MobileCity aus Depots und Paketraten selbst Nachfrage generiert, reichen andere Eingaben. Der Zielvertrag muss festlegen, welche Ebene übernommen wird.
- **Optional nach Phase 8 / Feedback:** Belastungsabhängige Zeiten können ergänzen. SUMO-Ergebnisse müssen fachlich passend auf Zonenpaare, Modi und Zeitintervalle aggregiert werden. Unbefahrene Beziehungen bleiben ohne Beobachtung; die aktuelle Rückkopplung behandelt insbesondere Pkw-Zeiten und ersetzt keine vollständige multimodale Matrix.
- Freie Fahrzeiten und belastete Fahrzeiten getrennt halten. Bereits enthaltene Stauwirkungen dürfen im Zielmodell nicht ungeprüft nochmals aufgeschlagen werden. Ein einzelner SUMO-Lauf macht Zeiten nicht automatisch genauer.

Zielablauf: **Modellgrundlage → logiTopp-Nachfrage → optional SUMO/Feedback → finalen Laufstand auswählen → MobileCity-Adapter → Prüfung → Paketfreigabe**. Ein schneller Export allein aus vorbereiteten Eingaben kann als klar benannter Modus bestehen bleiben. Aktuell schreibt ein späterer Feedbacklauf den MobileCity-Export nicht automatisch neu.

## Codeverweise

- [Quellstände](../runtime/kit-sources.env)
- [actiTopp-Adapter](../backend/src/main/java/de/synthesis/generation/pipeline/ActitoppRunner.java)
- [SyntheSis-actiTopp-Runner](../legacy/actitopp-synthesis-v1/SynthesisRunner.kt)
- [Nachfragesimulations-Adapter](../backend/src/main/java/de/synthesis/generation/pipeline/SimulationRunner.java)
- [Pipeline-Reihenfolge und Ersatzverfahren](../backend/src/main/java/de/synthesis/generation/GenerationService.java)
- [Versionierter logiTopp-Vertrag](../legacy/logitopp-returns-v1/)
- [Native actiTopp-Prüfung](../backend/src/test/java/de/synthesis/generation/pipeline/ActitoppIntegrationTest.java)
