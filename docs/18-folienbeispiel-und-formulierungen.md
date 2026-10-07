# Folienformulierungen und durchgängiges Beispiel: Alex

Stand: 01.10.2026. Acht fachliche Phasen; nicht mit den technischen Stufennummern verwechseln.

## Kurze Kernaussagen pro Phase

| Phase | Kernaussage für die Folie |
|---|---|
| 1 · Szenariomodellierung | **Gebiet, Rahmenbedingungen und Modellumfang festlegen.** |
| 2 · Zonierung | **Das gewählte Gebiet in Verkehrszellen gliedern.** |
| 3 · Datenintegration | **Regionale Daten aufbereiten und dem Modell zuordnen.** |
| 4 · Bevölkerungssynthese | **Haushalte und Personen erzeugen; Fahrzeuge und feste Ziele zuordnen.** |
| 5 · Aktivitätenplanung | **Für jede Person einen Wochenablauf mit Aktivitäten und Zeiten erstellen.** |
| 6 · Netz & Logistik | **Reisezeiten berechnen und die Zustellung modellieren.** |
| 7 · Nachfragegenerierung | **Aus Bevölkerung, Wochenplänen und Reisematrizen entstehen Personenwege und Paketaufträge.** |
| 8 · Verkehrsablauf | **Den Verkehr auf dem Straßennetz simulieren und Fahrzeiten ermitteln.** |

Der Kartenausschnitt gehört in Phase 1: Dort legen wir fest, welches Gebiet wir untersuchen. Phase 2 gliedert dieses Gebiet und erzeugt Zellgeometrien und IDs. Eine zweite Gebietsauswahl muss auf der Folie nicht erscheinen.

Alternative Formulierungen für Phase 6: „Reisezeiten und Logistikgrundlagen bestimmen“ oder „Straßennetz auswerten und die Zustellung modellieren“. Als Ergebnis: **„Reisematrizen und Logistikgrundlagen“**.

Alternative Formulierungen für Phase 7: „Aus dem Szenariomodell konkrete Wege und Lieferaufträge erzeugen“ oder „Wochenpläne in Personenwege übersetzen und Paketnachfrage erzeugen“. Technisch entstehen konkrete variable Zielzonen und Verkehrsmittel im mobiTopp/logiTopp-Lauf; es sind nicht nur geplante Aktivitäten.

Alternative Formulierungen für Phase 8: „Fahrzeugbewegungen simulieren und verkehrsabhängige Fahrzeiten bestimmen“ oder „Den Verkehrsfluss berechnen und Verzögerungen auswerten“. SUMO ist hier die Verkehrssimulation; Phase 7 ist die vorgelagerte Nachfrageberechnung.

Für den bisherigen Punkt „Bevölkerungsvorgaben berücksichtigen“ in Phase 4: **„Regionale Struktur abbilden“**, **„Bevölkerungsstruktur berücksichtigen“** oder **„Haushaltspool regional gewichten“**. Die letzte Formulierung beschreibt die optionale Zensusanpassung am genauesten. Standardmäßig ist diese Anpassung derzeit nicht aktiviert.

## Worauf Bevölkerung, Pkw und feste Ziele beruhen

- Einwohnerzahlen und räumliche Verteilung: bevorzugt Zensus 2022 oder hochgeladenes Bevölkerungsraster; Gesamtzahl optional manuell; bei Ausfall Schätzverfahren.
- Haushaltszusammensetzung, Alter und Geschlecht: mitgelieferter synthetischer KIT-Pool. Bei aktivierter Zensusstruktur wird dieser auf regionale Alters- und Haushaltsgrößenanteile gewichtet. Zensus enthält keine individuelle Familie „Alex“.
- Erwerbsstatus und Einkommen: derzeit Modellannahmen und Zufallsziehungen, nicht individuell aus dem Zensus.
- Pkw: aus Szenarioansatz `carsPerHousehold` und Haushaltsmitgliedern gezogen; Antrieb derzeit pauschal 8 % BEV in der Bevölkerungssynthese. KBA im MobileCity-Export ist ein separater Anwendungsweg.
- Arbeitsorte: optionale Pendlerbeziehungen oder gewichtete Ziel-/Arbeitsplatzverteilung. Bildungsorte: altersabhängige Bildungszielgewichte.
- Wohnorte: derzeit Zufallspunkte in Zonen. Die geplante Gebäude-/Straßenzuordnung ist noch nicht umgesetzt.

## Woher kommen Kosten- und ÖPNV-Näherungen?

Der gemeinsame Matrixgenerator setzt folgende Konstanten im SyntheSis-Code:

| Größe | Aktueller Ansatz |
|---|---|
| Pkw-Kosten | Straßenentfernung × 0,32 €/km |
| ÖPNV-Kosten | 1,80 € + Luftlinienentfernung × 0,15 €/km |
| ÖPNV-Fahrzeit | Pkw-Reisezeit × 1,7 + 8 Minuten |
| ÖPNV-Zugang/Abgang | jeweils 6 Minuten |
| Pkw bei fehlendem Routing | Luftlinie × 1,35; Zeit mit 28 km/h plus 3 Minuten |
| Rad bei fehlendem Routing | Luftlinie × 1,25; Zeit mit 14 km/h plus 2 Minuten |
| Fuß bei fehlendem Routing | Luftlinie × 1,2; Zeit mit 4,7 km/h |

Quelle der Implementierung: `MatrixGenerator.java`. Im geprüften Code ist für diese konkreten Werte keine belastbare Studienquelle oder regionale Kalibrierung dokumentiert. Sie sind pauschale Modellannahmen; sie dürfen nicht als KIT-, MiD- oder örtliche Tarifwerte bezeichnet werden. Die ÖPNV-Zeiten des gemeinsamen Nachfragemodells stammen bisher nicht automatisch aus dem GTFS-Fahrplan. GTFS-Verarbeitung an anderen Export-/Simulationsstellen ändert diese Matrix nicht automatisch.

Ziel: gemeinsame ÖPNV-Matrizen aus GTFS-Routing mit Abfahrtszeit, Zugang, Wartezeit und Umstiegen ableiten; Kosten als explizite, dokumentierte Szenarioparameter bzw. regionale Tarife führen. Nicht erreichbare Verbindungen dürfen durch Näherungen nicht still wieder erreichbar werden.

## Ein Beispiel von Phase 4 bis 8

**Illustrative Familie:** Alex (38, erwerbstätig), Sam (36) und ein Kind (8); ein gemeinsamer Pkw. Wohnzone A in Brackel, Arbeitszone B in Dortmund. Die Standorte sind für die Erklärung gewählt, keine realen Haushaltsadressen. Depot D ist ausdrücklich ein modellierter Beispielstandort, kein behauptetes reales Paketdepot.

| Phase | Beispiel |
|---|---|
| 4 | Haushalt und Mitglieder sind angelegt; ein Pkw sowie Alex’ fester Arbeitsort B sind zugeordnet. |
| 5 | Alex: Montag 08:30–16:30 Arbeit und 17:00–17:30 Einkauf; übrige Aufenthalte zu Hause. |
| 6 | Reisezeiten und Entfernungen zwischen A, B und möglichen Einkaufszonen stehen bereit. Depot, Flotte und Paketraten sind festgelegt. |
| 7 | Ein mögliches Ergebnis: A → B → C → A mit Verkehrsmittelwahl. Separat entsteht eine Zustelltour D → A → E → D: Paket für Alex an A, weitere Pakete in der eigenständigen Zustellzone E. |
| 8 | Alex’ Fahrt und die Lieferfahrt werden zusammen mit anderen Fahrzeugen auf dem Straßenmodell simuliert. Daraus entstehen Fahrzeiten, Verzögerungen und Straßenbelastungen. |

Die Einkaufszone C ist ein persönliches Aktivitätsziel. Eine Paketbestellung ist ein separater Logistikauftrag. Die gleiche Karte kann beides zeigen, ohne die Nachfragearten gleichzusetzen.

## Drei Beispielgrafiken

- [Phase 6: GraphHopper-Netz und Reisematrizen](figures/phasenbeispiel-dortmund-unna/phase-6.png), auch [SVG](figures/phasenbeispiel-dortmund-unna/phase-6.svg).
- [Phase 7: Alex’ Wege und eine separate Zustelltour](figures/phasenbeispiel-dortmund-unna/phase-7.png), auch [SVG](figures/phasenbeispiel-dortmund-unna/phase-7.svg).
- [Phase 8: tatsächlicher SUMO-Screenshot im Folienrahmen](figures/phasenbeispiel-dortmund-unna/phase-8.png).
- [Unbeschrifteter SUMO-Screenshot](figures/phasenbeispiel-dortmund-unna/sumo-screenshot.png).

Phase 6 zeigt tatsächlich importierte GraphHopper-Kanten aus einem OSM-Ausschnitt Dortmund/Unna: 14.784 Kanten. Die Beispielreisezeiten stammen aus echten Pkw-Routingabfragen. Die kleinen Zellen, Haushalts-/Zielstandorte und der Depotstandort sind Illustrationen.

Phase 7 zeigt illustrative Personenwege A → B → C → A und die separate Zustellfolge D → A → E → D. E ist eine zusätzliche Zustellzone; C bleibt ausschließlich Alex’ Einkaufsziel. Die orange Linie zeigt die Fahrt des Zustellfahrzeugs mit mehreren Paketen, nicht den Weg eines einzelnen Pakets. Deren Straßengeometrien wurden für die Grafik mit GraphHopper berechnet. Es handelt sich **nicht um aus einem Dortmund/Unna-logiTopp-Lauf ausgelesene Nachfrage**. Der tatsächliche heutige Tourplaner nutzt weiterhin geometrische Stoppfolgen; die Grafik soll keine bereits realisierte Netzoptimierung behaupten.

Phase 8 ist ein echter Screenshot der SUMO-GUI aus einem ausgeführten Beispiellauf auf demselben OSM-Netz. Eine Fahrt „Alex-Arbeit“, eine Lieferfahrt und zusätzlich erzeugte Hintergrundfahrten wurden durch SUMO geroutet. Bei Sekunde 180 waren 277 Fahrzeuge aktiv. Für die Lesbarkeit wurde ein kleinerer Ausschnitt um Alex’ Fahrt gewählt; Fahrzeuge sind vergrößert dargestellt. Die Nachfrage ist frei gewählt und nicht für Dortmund kalibriert. SUMO hat seine Routen eigenständig aus den Start-/Zielkanten berechnet; eine direkte Übernahme der GraphHopper-Kantenfolge wurde hier nicht implementiert.

## LLM / GTA in der Pipeline

**Hauptplatz: Phase 5 · Aktivitätenplanung.** Im vorhandenen `GtaBehaviorRunner` werden Personen nach Erwerbsstatus, Altersgruppe und Geschlecht zu Personas zusammengefasst. Ein LLM erzeugt Aktivitätenfolgen für diese Personas; die nachgelagerte Planung übersetzt sie in Wochenpläne. Ein optionaler Was-wäre-wenn-Kontext kann Verhaltensannahmen verändern. Im Code gibt es ein Persona-Budget und Ersatzverfahren für nicht abgedeckte Personen.

Darstellung auf der Folie: **„Wochenpläne: actiTopp oder experimentelle LLM-Planung (GTA)“**. Nicht beide als zwei notwendige aufeinanderfolgende Schritte darstellen. Alex kann das gleiche Erklärbeispiel behalten; der Plan unterscheidet sich je gewählter Quelle. Der aktuelle GTA-Adapter bekommt noch keinen vollständigen Haushaltskontext und ist nicht automatisch regional kalibriert.

**Zweiter Einsatz: optionale Analyse nach Phase 7.** Ein LLM kann alternative Verkehrsmittelentscheidungen beurteilen und mit mobiTopp-Ergebnissen vergleichen. Das ist ein separater experimenteller Vergleich, keine zusätzliche reguläre Verkehrssimulation.

**Langfristig:** Der gleiche vollständige Haushaltskontext sollte auch GTA erreichen, einschließlich Pkw-Ressourcen und Mitgliedern. Harte Konsistenzprüfungen bleiben im Programm: gültige Zeiten, vorhandene Ziele, Alters-/Erwerbsbedingungen und Ressourcen. Ein plausibel klingender LLM-Plan ist noch kein validiertes Nachfragemodell.

Für die SUMO-Nachfrage wurden nur routbare Fahrten übernommen. Der Depotstart wurde an eine nahe Straßenkante mit nachgewiesener Verbindung zum Wohnziel angebunden; nicht routbare Hintergrundpaare wurden ausgelassen. Das begrenzte Beispielnetz enthält nicht alle OSM-Straßenklassen.
