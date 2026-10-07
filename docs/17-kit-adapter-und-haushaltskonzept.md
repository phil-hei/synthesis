# KIT-Komponenten unverändert anbinden und Haushalte vollständig übergeben

Stand: 02.10.2026. actiTopp und mobiTopp/logiTopp sind über separate Adapter angebunden. Beide Builds verwenden unveränderte Originalquellen.

## Laufzeit: Java 25 und Java 26

Java 25 ist bereits unter `/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` installiert (25.0.4). Die macOS-Suche `java_home -V` listet diese Homebrew-Installation nicht auf. Die frühere Schlussfolgerung, Java 25 sei nicht vorhanden, war daher falsch. `scripts/runtime-common.sh` berücksichtigt den Homebrew-Pfad bereits.

Der bisherige native Integrationstest scheitert auch mit korrekt gesetztem Java 25: Im lokalen actiTopp-Projekt liegen zwei SyntheSis-Runner mit identischem JVM-Klassennamen. Dieser Integrationsfehler tritt beim Kompilieren auf. Am KIT-Projekt wurde in dieser Prüfung nichts geändert.

Für einen isolierten Probelauf wurde ein separates Gradle-Projekt mit unserem vorhandenen Runner gebaut. Es verwendet die vorhandene actiTopp-Kern-JAR und deren Abhängigkeiten, ohne Source- oder Buildänderungen am KIT-Projekt. Kompiliert mit Java 25, anschließend direkt gestartet:

| Laufzeit | Eingabe | Ergebnis |
|---|---|---|
| Homebrew Java 25.0.4 | Drei Personen: 38/FULLTIME, 36/PARTTIME, 8/EDUCATION | 68 Aktivitätszeilen plus Header |
| Temurin Java 26.0.2 | Gleiche Eingabe | 68 Aktivitätszeilen plus Header |

Das belegt die grundsätzliche Laufbarkeit dieses actiTopp-Kerns unter beiden Laufzeiten. Es belegt weder identische stochastische Ergebnisse noch Java-26-Kompatibilität der gesamten logiTopp-Pipeline. Die produktive Standardlaufzeit bleibt Java 25 entsprechend dem versionierten Vertrag. Java 26 kann als zusätzlicher Kompatibilitätstest dienen.

Die verwendete Kern-JAR stammt aus dem vorhandenen lokalen Build. Ein reproduzierbarer Neubau aus unverändertem, gepinntem Upstream und ein vollständiger Nachfrage-/Logistiklauf bleiben Abnahmekriterien.

## Zielarchitektur

```text
SyntheSis Mobility & Logistics Model
  ├─ Haushalte, Personen, Fahrzeuge, feste Ziele
  ├─ Zonen, Straßennetz, Reisematrizen
  └─ Depots, Paketparameter und Szenarioannahmen
               ↓ versionierte Eingabeverträge
Separates SyntheSis-KIT-Adapterprojekt
  ├─ actiTopp-Runner: Modellobjekte aufbauen, Wochenpläne erzeugen
  ├─ mobiTopp/logiTopp-Runner: Simulation über öffentliche APIs zusammenstellen
  └─ eigene Paket-/Retouren-/Tourstrategien über Erweiterungsschnittstellen
               ↓
Unveränderte, gepinnte KIT-Bibliotheken
               ↓
Versionierte Ergebnisse und Quellen-/Annahmenbericht
```

KIT-Repositories bleiben unverändert. Eigene Klassen und Builddateien liegen außerhalb der KIT-Checkouts in einem eigenen Namensraum, etwa `de.synthesis.kit`. Der Backendprozess startet diesen Adapter. Modellkern und Adapter werden getrennt versioniert; Java 25 wird beim Start ausdrücklich ausgewählt. Alte Patchverträge werden erst nach nachgewiesener funktionaler Ablösung aus dem Laufweg genommen.

## Welche Anpassungen lassen sich auslagern?

| Bestehender Eingriff | Ersatz außerhalb des KIT-Projekts | Prüfstand |
|---|---|---|
| actiTopp-Task `runSynthesis` und eingebetteter Runner | Separates Kotlin-/Gradle-Adapterprojekt mit eigener Main-Klasse | Technischer Probelauf erfolgreich |
| Buildmodernisierung und lokale Veröffentlichung von logiTopp | Eigenes Buildprojekt kompiliert unveränderte Upstream-Quellen oder nutzt freigegebene Artefakte; keine Bearbeitung ihrer Builddateien | Implementiert unter `adapters/logitopp`, nativer Wochenlauf geprüft |
| Änderungen am Rastatt-`SimulatorBuilder` | Eigener Simulationseinstieg und eigene Zusammenstellung der KIT-Komponenten | Öffentliche Modell-/Simulationsklassen vorhanden; kein einfacher Ersatz durch Unterklasse, da zahlreiche Factory-Methoden privat sind |
| Paketmengen, Zielanteile, Dienste, Retouren | Eigene Nachfragekonfiguration und Nachfrageimplementierungen | `ParcelDemandModelBuilder.useParcelFactory(...)` und weitere öffentliche Modellschritte vorhanden |
| Änderung an `ParcelBuilder.get()` | Eigene Unterklasse von `PrivateParcelBuilder` mit eigener einmaliger Registrierung und Cache; Einbindung über Paketfactory | `get()` ist nicht final; `doBuild()` und `DistributionCenter.requestPickup(...)` sind zugänglich. Implementiert und getestet; 293 Abholaufträge im nativen Wochenlauf |
| Gruppierung von Abholstopps | Eigene Strategie im Adapter, die vorhandene Tour-/Stopp-APIs verwendet | Strategieanbindung und Toursemantik im vollständigen Lauf prüfen |

Die Retourenerweiterung muss nachweisen, dass ein Auftrag genau einmal erzeugt/registriert wird, aus dem richtigen Bestand verschwindet und keine Zustellung oder Abholung verloren geht. Falls vorhandene APIs die nötige Semantik nicht ermöglichen, bleibt die Funktion vorerst deaktiviert und wird mit KIT abgestimmt. Bytecode-Manipulation, Austausch gleichnamiger KIT-Klassen und nachträgliches Patchen gehören nicht zum Zielweg.

## Vollständiger Haushalt als gemeinsame Grundlage

Die Bevölkerungssynthese in SyntheSis bleibt vorgelagert. actiTopp bekommt daraus einen konsistenten Haushaltskontext; es bestimmt nicht die regionale Einwohner- oder Haushaltsstruktur.

| Eingabe im gemeinsamen Modell | Verwendung durch den Adapter |
|---|---|
| Haushalts-ID, Zone und Wohnpunkt | Mitglieder gemeinsam zuordnen; stabile Referenzen in allen Ergebnissen |
| Alle Haushaltsmitglieder mit ID, Alter, Geschlecht, Erwerbsstatus | Eine gemeinsame Household-Instanz und die zugehörigen Person-Instanzen aufbauen |
| Kinderzahlen 0–10 und unter 18 | Aus allen tatsächlichen Mitgliedern ableiten, nicht aus dem Alter der aktuell bearbeiteten Person |
| Tatsächliche Fahrzeuge und Pkw-Anzahl des Haushalts | Aus `car.csv`/Modellobjekten zählen; kein pauschaler Pkw je Erwachsenem |
| Raumtyp | Aus einer dokumentierten regionalen Klassifikation oder einer expliziten Szenarioannahme; keine stille Festlegung auf `PROVINCIAL` |
| Feste Arbeits-/Bildungsziele und Pendeldistanzen | Ziele unverändert weiterreichen; Distanzen aus gezielten Netzabfragen, wo verfügbar |
| Einkommen, Führerschein, Fahrrad, ÖPNV-Abo und weitere Merkmale | Im gemeinsamen Modell erhalten und an nachgelagerte Modelle übergeben; nur von actiTopp unterstützte Felder an actiTopp übertragen |

Das heutige actiToppNG hat `ActiToppHousehold.members`, `add(...)`, Kinderzahlen, Raumtyp und Pkw-Anzahl. `PersonAttributes` enthält zusätzlich optionale Pendeldistanzen. Die vollständige Objektübergabe ist damit möglich. Ob das Modell alle gewünschten gemeinsamen Haushaltsentscheidungen koordiniert, ist gesondert zu prüfen: Gemeinsame Eingabedaten garantieren beispielsweise keine kollisionsfreie Nutzung eines einzigen Pkw.

Die spätere Prüfung umfasst auch: alle erzeugten Personen erhalten einen gültigen Plan oder einen ausgewiesenen Ersatz; Haushalte bleiben vollständig; keine Person gehört zu mehreren Haushalten; Pkw-Anzahl stimmt zwischen Dateien und Modell überein. Die derzeit mögliche Kürzung des letzten gezogenen Haushalts zur Einhaltung einer exakten Personenzahl soll abgelöst werden. Für vollständige Haushalte ist eine kleine, ausgewiesene Abweichung von der Zielpersonenzahl oder eine passende Auswahl ganzer Haushalte sinnvoller.

Pendeldistanzen werden für die Aktivitätenplanung gezielt berechnet, sobald Wohn- und feste Ziele bekannt sind. Die vollständige Matrixberechnung in Phase 6 muss dafür nicht pauschal vor Phase 5 verschoben werden.

## Was die Bevölkerungssynthese heute tatsächlich macht

1. Einwohnerzahl und räumliche Verteilung: bevorzugt Upload-Raster bzw. Zensus-2022-Raster; eine manuelle Gesamtzahl kann die Summe überschreiben. Bei fehlender Quelle greifen Schätzverfahren.
2. Haushalte, Alter und Geschlecht: standardmäßig ganze Haushalte aus dem mitgelieferten synthetischen KIT-Pool (`useKitPopulation=true`). Dieser Pool ist kein deutschlandweit repräsentativer Zensus-Mikrodatensatz.
3. Regionale Strukturanpassung: optional (`useCensusStructure=false` als Standard) Gewichtung der Poolhaushalte auf drei Altersgruppen und sechs Haushaltsgrößenklassen aus Zensusdaten. Bei unzureichendem Pool oder fehlenden Daten wird der Rückfall ausgewiesen. Es ist keine Rekonstruktion realer Haushalte.
4. Weitere Merkmale: Erwerbsstatus, Einkommen, Führerschein, Fahrrad/ÖPNV-Abo werden derzeit mit Modellannahmen gezogen. Pkw-Zahl folgt `carsPerHousehold` und Haushaltsmitgliedern; Antrieb wird derzeit pauschal mit 8 % BEV gezogen. KBA-Daten im MobileCity-Export korrigieren diesen Syntheseschritt nicht automatisch.
5. Wohnpunkte: derzeit zufällig innerhalb der Zone; Gebäude-/Straßenzugang ist weiterhin offen.
6. Feste Ziele: Arbeitsorte aus optionalen Pendlerbeziehungen bzw. gewichteten Arbeitsplatz-/Zielzonen; Bildungsorte aus entsprechenden Zielgewichten. Das ist eine synthetische Zuordnung, keine individuelle Zensusinformation.

Korrekte Kurzform für die Folie: **„Haushaltspool anhand regionaler Bevölkerungsdaten gewichten; Personen, Fahrzeuge und feste Ziele ergänzen.“** Eine vollständig zensusbasierte Haushaltssynthese ist im Standardmodus nicht gegeben.

## Umsetzung und Abnahme

1. Separates Adapterprojekt mit festem Java-25-Start und unveränderter actiTopp-Abhängigkeit integrieren; bisherigen Patchstart ablösen.
2. Haushaltsvertrag erweitern und tatsächliche Mitglieder, Pkw und Raumtyp übergeben; Quellen, Ersatzwerte und fehlende Merkmale ausweisen.
3. Eigenen mobiTopp/logiTopp-Simulationseinstieg aufbauen; vorhandene eigene Nachfrageklassen aus dem KIT-Checkout herauslösen.
4. Retouren über öffentliche Factory-/Builder-Schnittstellen umsetzen und vollständig prüfen.
5. Unveränderte Upstream-Quellen reproduzierbar bauen, Checkouts auf Änderungen prüfen und einen kleinen vollständigen Personen-/Logistiklauf abnehmen.
6. Ergebnisse mit Quellen und Annahmen für den finalen MobileCity-Export bereitstellen.

## Gesicherte Prüfartefakte

- [Separater Adapter: Prüfergebnis](verification/2026-10-01-actitopp-standalone.json).
- [Aktivitäten unter Java 25](verification/2026-10-01-actitopp-standalone-java25.csv).
- [Aktivitäten unter Java 26](verification/2026-10-01-actitopp-standalone-java26.csv).
- [Bisheriger KIT-Projektstart mit Java 25: doppelter Runner](verification/2026-10-01-actitopp-java25-duplicate-runner.log).

## Implementierter erster Schritt

Der separate actiTopp-Adapter unter `adapters/actitopp` ist in die Backend-Pipeline
integriert. Der Build liest ausschließlich den festgelegten Originalcommit.
Haushaltszuordnung, alle Mitglieder einschließlich Kleinkindern, tatsächliche
Pkw-Anzahl und Kinderzahlen werden über den Vertrag v2 übergeben. Der
Siedlungstyp ist in der UI als explizite Annahme auswählbar. Die vom KIT-Kern
angelegten Haushalte stehen in `population/actitopp/actitopp-households.csv`.

Ganze Poolhaushalte bleiben inzwischen vollständig erhalten; kleine
Rundungsabweichungen werden zwischen Zonen ausgeglichen und ausgewiesen.
Noch offen: Pendeldistanzen und automatische Raumtypen sowie ein durchgängiger
neuer Haushaltslauf über beide Adapter. Gemeinsame Haushaltsdaten sind keine Garantie für
vollständig koordinierte Haushaltsentscheidungen.

Abnahme: 220 Backendtests (212 bestanden, 8 weitere Integrationstests übersprungen), darunter ein ausgeführter nativer actiTopp-Test; 52 Frontendtests und Produktionsbuild erfolgreich. Ein zusätzlicher Lauf mit 1.966 Personen, 986 Haushalten und 986 Pkw erzeugte Pläne für alle 1.874 Personen ab sechs Jahren. Die 92 Kleinkinder bleiben im Haushaltskontext und nutzen für Aktivitäten den Backend-Ersatz. Alle erzeugten KIT-Haushaltskontexte wurden gegen die Eingabedaten geprüft. [Prüfbericht](verification/2026-10-01-actitopp-households-v2.json).


## Implementierter zweiter Schritt: mobiTopp/logiTopp

`adapters/logitopp` ersetzt im Backend den Gradle-Start aus dem modifizierten
Rastatt-Checkout. Build und Modellkern bleiben getrennt: Originalquellen werden
mit `git archive` gelesen, generierte Wahlmodellbindungen über ihr Manifest
geprüft, eigene Runner und Paketstrategien separat kompiliert. Alte lokale
KIT-Änderungen bleiben für frühere Experimente erhalten, gehen aber nicht in
diesen Lauf ein. Der Bootstrap wendet den alten Patchvertrag nicht mehr an.

Eigene Klassen haben eindeutige `Synthesis…`-Namen. Wegen der paketinternen
KIT-Umschedulingstrategie verwenden sie denselben Java-Paketnamen, liegen aber
in unserem Adapterprojekt. Keine KIT-Klasse wird ersetzt. Die ursprüngliche
Zusammenstellung wird in `SynthesisSimulatorBuilder` separat ergänzt.
Retouren nutzen `useParcelFactory` und eine eigene `PrivateParcelBuilder`-
Unterklasse. Fehlende Depots führen zu einem verständlichen Fehler; ein
willkürliches Ersatzdepot entfällt.

Abnahme: Vier Adaptertests und sechs Backend-Runner-Tests bestanden. Die vollständige
Backendprüfung umfasst 221 Tests: 212 bestanden, neun Integrationstests
übersprungen. Ein
nativer Wochenlauf, zusätzlich über den echten Backend-Runner ausgeführt,
verarbeitet 1.966 Personen bei `fractionOfPopulation=1.0`: 44.021 Wegezeilen,
1.499 Zustellaufträge und 293 Abholaufträge. Am Wochenende sind 1.329
Zustellungen und 262 Abholungen abgeschlossen; 170 Zustellungen und 31
Abholungen bleiben `UNDEFINED`. Die Abnahme belegt Ausführung und Registrierung,
keine vollständige Abarbeitung aller Aufträge. Verwendet wurden bestehende
exportierte Wochenpläne; ein neuer gemeinsamer actiTopp-Haushaltslauf bleibt
eine weitere Abnahme. [Prüfbericht](verification/2026-10-02-logitopp-adapter-v2.json).

## Wenn Reisezeiten den Wochenplan verschieben

Im unveränderten mobiTopp 0.3.580 setzt `PersonForDemand.startActivity` den
Aktivitätsbeginn auf die tatsächliche Ankunft und ruft anschließend die
`ReschedulingSkipTillHome`-Strategie auf. Die zwei neuen Tests laufen direkt
gegen diese KIT-Klasse:

- Bei einer Verspätung am gleichen Tag, ohne weiteren Tagesgrenzenkonflikt,
  verkürzt diese Strategie die Aktivitätsdauer nicht automatisch und entfernt
  keine Aktivitäten. Ein späterer Beginn kann weitere Wege verschieben.
- Bei einer Ankunft am folgenden Tag kann sie Zwischenaktivitäten bis zur
  nächsten Heimaktivität entfernen und die laufende Aktivitätsdauer an den
  nächsten Beginn anpassen. Bestimmte weitere Tagesgrenzenkonflikte werden
  ebenfalls behandelt. Das ist keine vollständige Neuplanung der Woche.

Beispiel: Alex soll um 08:00 Uhr mit acht Stunden Arbeit beginnen, kommt aber
um 08:30 Uhr an. Der tatsächliche Beginn wird 08:30 Uhr; die aktuelle Strategie
kürzt die acht Stunden im normalen Fall nicht auf 7,5 Stunden. Ein Einkauf
nach der Arbeit kann dadurch später stattfinden. Sie garantiert keine
Einhaltung eines festen Termins.

SUMO wird danach ausgeführt. Seine zusätzlichen Stauverspätungen ändern die
bereits generierten Aktivitäten nicht automatisch. Das optionale `sumoFeedback` führt weitere Nachfrageläufe
mit aktualisierten Reisematrizen aus und kann andere Ausführungszeiten ergeben;
eine direkte zeitgleiche Kopplung ist nicht implementiert.

Nächster fachlicher Schritt: Eine eigene, austauschbare Strategie mit klaren
Regeln für feste Termine, flexible Aktivitäten und Mindestdauern, einschließlich
Protokoll der Verschiebungen und ausgelassenen Aktivitäten. Der KIT-Kern bleibt
unverändert. Parallel bleiben netzbasierte Zustelltouren und die spätere
SUMO-Rückkopplung offen.

## Präzisierungen und nächste Aufgaben (02.10.2026)

### SUMO-Rückkopplung: vorhanden und noch offen

`GenerationService.stageNetworkFeedback` ist bereits implementiert und über
`population.sumoFeedback` (standardmäßig `false`) aktivierbar. Nach erfolgreichem
SUMO-Lauf berechnet es zonenpaarbezogene Staufaktoren, dämpft diese und aktualisiert
die Pkw-Reisematrix. Danach führt es logiTopp und SUMO erneut aus. Es handelt sich
um eine begrenzte iterative Rückkopplung, nicht um eine zeitgleiche Kopplung.
Die früher verkürzte Aussage, es gebe keine automatische Rückkopplung, war zu
pauschal. Die actiTopp-Sollwochenpläne werden dabei nicht neu erzeugt; ihre
Ausführung kann sich durch geänderte Reisezeiten verschieben.

Weiter umzusetzen: Eine eigene Regelung für verspätete Aktivitäten mit festen
Terminen, flexiblen Aktivitäten und Mindestdauern. Plan und tatsächliche
Ausführung müssen getrennt erhalten bleiben; Verschiebungen, Kürzungen und
Ausfälle sollen ausgewiesen werden. Reisezeiten sollten langfristig nach
Abfahrtszeit unterschieden werden. Das Iterationslimit ist keine Garantie eines
Verkehrsgleichgewichts. Die genaue Entwicklung bleibt eine eigenständige Aufgabe.

### Haushalte ohne Erwachsene

Im mitgelieferten KIT-Pool haben alle 6.512 Haushalte mindestens eine Person ab
18 Jahren; auch das Template setzt erwachsene erste Haushaltsmitglieder.
Die Bedingung in `drawCarCount` ist eine defensive Absicherung, kein Hinweis
auf üblicherweise erzeugte Kinderhaushalte. Alle Poolkompositionen bleiben jetzt
vollständig erhalten, auch der letzte Haushalt einer Zone. Die Rundungsabweichung
steht in `population/household-rounding.json`. Abweichende externe Pools müssen
explizit validiert werden; keine stille Alterserhöhung vornehmen.

### Feste Ziele anhand von Netzreisezeiten

Die vorgelagerte Zuordnung fester Arbeits-/Bildungszonen verwendet inzwischen
die initiale Pkw-Reisematrix. Sie wird vor Bevölkerung und Aktivitäten erzeugt.
Die frühere geometrische Gewichtung bleibt nur bei Aufrufen ohne bereitgestellte
Matrix als Rückfall bestehen.

Umsetzungspfad: Netz und Reisematrizen vor der festen Zielzuordnung bereitstellen,
anschließend vorhandene Matrixwerte verwenden statt pro Person erneut zu routen.
Arbeits-Pendlerrelationen behalten Vorrang, soweit sie die Relation abdecken.
Je nach verfügbarer Mobilität sind Pkw-, Rad-, Fuß- und GTFS-ÖPNV-Zeiten zu
berücksichtigen. Nicht erreichbare Ziele werden für die jeweilige Verkehrsart
nicht als erreichbare Alternativen gewertet. Gewichte und Zeitkoeffizienten
müssen als Modellannahmen dokumentiert und überprüft werden. Der erste Schritt ist inzwischen implementiert: die initiale Pkw-Matrix
wird vor der Bevölkerungssynthese bereitgestellt und für feste Ziele verwendet.
Multimodalität und GTFS bleiben offen; siehe [Präzisierung](19-fahrzeugimport-und-modellbegriffe.md).

### Dialogbasierter Szenarioassistent

Konzeptgrafik: [PNG](figures/szenarioassistent/szenarioassistent-dortmund-unna.png)
und [SVG](figures/szenarioassistent/szenarioassistent-dortmund-unna.svg).
Der Dialog übersetzt eine Beschreibung in eine überprüfbare Szenariokonfiguration.
Gebiet und Depotstandorte werden auf der Karte bestätigt; Quellen und
Nutzerannahmen bleiben sichtbar. Das Chat-Beispiel ist illustrativ und keine
bereits implementierte Benutzeroberfläche.

### Begriffe für die Ablaufgrafik

- **MNL:** Multinomiales Logit-Modell; wählt Verkehrsmittel anhand relativer
  Nutzenwerte und daraus abgeleiteter Wahrscheinlichkeiten.
- **Simulierte Nachfrage:** ausgeführte Personenwege mit Start-/Zielzone,
  Verkehrsmittel, Abfahrts-/Ankunftszeit sowie Paketaufträge und Zustelltouren.
  Die Personennachfrage ist in der aktuellen Konfiguration zonenbasiert;
  sie enthält keine vollständig straßengenauen Personenrouten. SUMO-Aufbereitung
  und SUMO liefern Straßenrouten und Verkehrszustände.
- Verständlichere Ergebnisbeschriftung: **Personenwege und Zustelltouren**.


Aktuelle Präzisierung mit Fahrzeugformat und konkreter Heimkehrverspätung:
[Import und Modellbegriffe](19-fahrzeugimport-und-modellbegriffe.md).
