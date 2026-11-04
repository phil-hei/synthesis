# Bestehende Bevölkerungsquellen einzeln auswählen

Stand: 29.10.2026. Experimenteller Schnitt für fünf vorhandene Provider, kein
Nachweis empirischer Qualität oder einer vollständigen Bestandsmigration.

Im Studio „Daten & Quellen öffnen“, den Beitrag „Bevölkerungsverteilung“ wählen,
den Quellenplan aktivieren und als Quelle „Bevölkerungsquellen einzeln konfigurieren“
auswählen. Mit den Pfeilen die Reihenfolge ändern, unerwünschte Quellen abwählen
und ihre Ausfallregel festlegen. Anschließend den Plan prüfen und das Szenario speichern.
Die erste im Lauf verwendbare Quelle gewinnt. Nachfolgende Quellen werden nicht
aufgelöst. Eine Änderung des Plans ist erst für den nächsten Lauf wirksam.

| Provider | Gesamtzahl ohne manuelle Vorgabe | Verteilung |
|---|---|---|
| `upload-grid` | Summe der den Modellzonen zugeordneten Rasterwerte | Raster-/Punktgewichte, ohne zusätzliche Glättung |
| `census-2022` | Zugeordnete Summe des versionierten, hashgeprüften Zensus | Derselbe native Rasterleser; bekannte grobe Zellen modelliert auf 100-m-Punkte verteilt |
| `osm-places` | Bisherige regionale Siedlungsschätzung | Bisherige geglättete Siedlungsgewichte |
| `poi-density` | Modellzonenfläche × eingestellte Einwohnerdichte | POI-Anzahl und Zufallsgewichte des bisherigen Generators |
| `uniform` | Modellzonenfläche × eingestellte Einwohnerdichte | Zufallsgewichte ohne Ortsdaten; keine exakt gleiche Zonenbelegung |

Eine manuelle Einwohnerzahl überschreibt nur die Gesamtzahl. Die ausgewählte Quelle
bleibt für die Verteilung zuständig. OSM-Arbeitsgewichte und Straßennetz sind von
Abwahlen in dieser Bevölkerungskette unabhängig. Ein abgeschalteter Zensusprovider
verhindert nur diesen Bevölkerungsabruf; andere optionale Zensusverbraucher werden
weiter über ihre eigenen Einstellungen gesteuert.

Fehlende Quellen dürfen nur bei `onUnavailable: next` zum nächsten zugelassenen
Kandidaten führen. Eine räumlich/zeitlich unpassende Quelle wird übersprungen.
Sind alle Kandidaten unbrauchbar, bricht der Lauf ab: Es gibt keinen zusätzlichen
versteckten Bestandspfad. Defekte explizite Daten, Hashabweichungen und ungültige
Werte sind Fehler, auch bei `next`. Unterbrechung beendet die Auflösung.

Der bestehende Rasterupload nimmt weiterhin CSV im nativen WGS84-, Zensus- oder
INSPIRE-Dialekt an. Im neuen Pfad sind explizite Nullwerte gültig; unlesbare,
negative, unterdrückte oder nicht endliche Zahlen werden abgewiesen. Die strenge
Prüfung gilt auch für den eingelesenen Zensusbestand; derzeit gibt es dafür noch
keine eigene NoData-/Geheimhaltungsmaske. Eine Datei mit solchen Werten muss über
einen dafür geeigneten Fachadapter aufbereitet werden. Bei anderen Dateiformaten
steht weiterhin der gemeinsame Import mit Auswahl einer geprüften Datenrevision
zur Verfügung. Diese fünf Bestandsprovider lassen sich inzwischen über
[gemischte Profile](../mixed-sources/README.md) mit Revisions-/Paketkandidaten in
einer gemeinsamen Kette auswählen.

Die [Beispielkonfiguration](plan.json) ist der Inhalt von `scenario.dataPlan`.
Sie verlangt einen vorhandenen Upload. Soll ein fehlender Upload übersprungen
werden dürfen, dessen Ausfallregel ausdrücklich auf `next` setzen. `legacy`
bleibt die Standardquellenwahl, solange keine eigene Kette gewählt wird.

Die Vorprüfung bestätigt Konfiguration und bindet bei zugelassenem Upload dessen
Hash oder den Zustand „fehlt“. Räumliche Auswahl und tatsächliche Quellenverfügbarkeit
werden erst nach Zonierung/OSM-Auflösung geprüft. Ein später neu hinzugefügter Upload
ersetzt den vorher fehlenden Input nicht im bereits gestarteten Lauf. Änderungen
oder Entfernung eines gebundenen Uploads führen zum Fehler. Die tatsächlich verwendete
Uploadkopie bleibt unter `sources/population-grid.csv` erhalten.

`sources/resolution.json` enthält unter `population` Kandidatendiagnosen,
Gesamtzahl-/Verteilungsquelle und zwei getrennte Herkunftsgraphen. Die Rasterbilanz
bezieht sich auf die vom Parser für die Szenarioregion zurückgegebenen Punkte;
außerhalb des Parserzuschnitts liegende Originalzeilen sind kein Teil dieses Nenners.
Bei einem Quellenfehler steht der bis dahin geprüfte Pfad in
`sources/population-failure.json`. Alte Berichte und Uploadkopien werden beim
nächsten Lauf entfernt. Bestehende Szenarioartefakte sind weiterhin kein Archiv
mehrerer Läufe.

Weder Upload noch OSM werden durch diese Integration automatisch zu geprüften
Beobachtungen. Dichte-, POI- und Siedlungsmodelle bleiben Annahmen. Auch der Modus
„Fachliche Auswertung“ ersetzt keine gesonderten Evidenz-/Abdeckungsanforderungen
für Bevölkerung; seine bisherige explizite Sperre betrifft die Lkw-Demonstration.

Ergänzung 29.10.2026: Für ausdrücklich zugeordnete Zellzustände steht inzwischen
der separate [typisierte Rastervertrag](../masked-cells/README.md) zur Verfügung.
Dies erweitert die alten Punkt-/nativen Rasterverträge nicht stillschweigend.
