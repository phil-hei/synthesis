# Gemischte Quellenprofile und explizite Registerlücken

Stand: 29.10.2026. Experimenteller, technisch geprüfter Schnitt für Bevölkerung und
Arbeitsgewichte. Die Dateien enthalten ausschließlich künstliche Vertragsdaten.

## Ausführbares Beispielpaket

[regional-inputs.zip](regional-inputs.zip) enthält eine Bevölkerungsquelle mit Summe
80 und ein vollständiges künstliches Punktregister für eine kleinere Teilabdeckung
mit Arbeitsplatzwerten 12 und 0. Die [entpackten Dateien](regional-inputs/manifest.json)
machen Mapping, Abdeckung und Rohhashes sichtbar. Die Bezugsregion ist die künstliche
Box 8,20/48,85–8,26/48,89; das Paket ist keine reale regionale Datengrundlage.

Das [Quellenprofil](source-profile.json) referenziert den konkreten ZIP-Hash und ist
als Definition über `POST /api/scenarios/{id}/data/source-profiles` speicherbar.
Zuerst das Paket im selben Szenario installieren. Die Definition kombiniert:

- Bevölkerung: nativer Zensus → Bevölkerungsquelle aus dem Paket → Ersatz ohne Ortsdaten.
- Arbeitsgewichte: Arbeitsplatzregister aus dem Paket → synthetisches Modell.

Der Zensus wird nur nach den Betriebseinstellungen und bei passender Region/Zeit
verwendet. Bei aktiviertem geeignetem Zensus gewinnt er vor der Beispielpopulation;
für den ausdrücklich isolierten Pakettest war er deaktiviert. Die Paketinstallation
allein aktiviert keine Quelle. Das Speichern der Profildefinition liefert deren Hash;
dieser muss je Aspekt im Szenario ausgewählt werden.

## Bedienung im Studio

„Daten & Quellen“ öffnen. Datenrevisionen wie bisher importieren oder das Paket
installieren. Unter dem jeweiligen Beitrag ein Quellenprofil anlegen: Im Feld
„Kandidat hinzufügen“ stehen jetzt Revisionen, Paketquellen und passende native
Provider gemeinsam zur Verfügung. Reihenfolge, Abwahl und Ausfallregeln speichern.
Die Auswahl einer konkreten Profilversion ist ein eigener Schritt.

Für Arbeitsplätze anschließend „Außerhalb der gewählten Datenabdeckung“ wählen:
„Eigene Quellen für nicht abgedeckte Zonen“. Unter „Quellen für Registerlücken“
OSM-Arbeitsgewichte und synthetische Gewichte ordnen oder abwählen. Der Plan wird
geprüft und mit dem Szenario gespeichert. Ein synthetischer Lückenfüller ist eine
Modellannahme und keine empirische Arbeitsplatzschätzung.

Ein Schema des Szenarioausschnitts nach Auswahl der gespeicherten Profilversion:

```json
{
  "source": "profile",
  "profile": "<Hash der gespeicherten Profilversion>",
  "gaps": "providers",
  "gapProviders": [
    { "provider": "osm-pois", "excluded": false, "onUnavailable": "next" },
    { "provider": "synthetic", "excluded": false, "onUnavailable": "fail" }
  ]
}
```

Dieser Ausschnitt gehört zu `dataPlan.aspects["opportunities.work"]`.
`dataPlan.enabled` muss ausdrücklich aktiviert sein. Direkte Datenrevisionen
unterstützen dieselbe neue Lückenregel; alte `legacy`-/`none`-Regeln bleiben erhalten.

## Verbindliche Regeln

Ein gemischtes Profil verwendet `builtin:<ID>`, `revision:<Hash>` oder
`pack:<ZIP-Hash>:<Quellen-ID>`. Nur zum Aspekt passende native Provider sind zulässig.
Ohne eigene Strategie gewinnt die erste verwendbare Quelle. Für mehrere Register
ist inzwischen [regionale Ergänzung](../regional-registers/README.md) verfügbar;
Überlappungen werden dabei nicht addiert. Native Profile haben `fallback=none`: Alle gewünschten Ersatzkandidaten
müssen explizit in der Liste stehen. Bei gemischten Arbeitsplatzprofilen ist auch
`gaps=legacy` ausgeschlossen. Damit gibt es keinen versteckten Rückweg zu abgewählten
nativen Quellen. Reine bestehende Importprofile behalten ihren alten Vertrag.

Die Vorprüfung validiert und bindet alle potenziell benötigten Importrevisionen samt
Referenzen bis zur ersten geeigneten Importrevision. Diese beendet den erreichbaren
Pfad: spätere Quellen können entweder nicht mehr gebraucht werden oder der Lauf
scheitert an einem räumlichen/inhaltlichen Zuordnungsfehler. Abgewählte Quellen und
Kandidaten nach diesem Punkt werden nicht gelesen. Eine noch unentschiedene native
Quelle davor verhindert die Bindung der danach möglicherweise benötigten Datei nicht.
Deshalb kann eine defekte potenzielle Datei bereits die Vorprüfung blockieren,
selbst wenn eine native Quelle im späteren Lauf hätte gewinnen können.

`staged` bedeutet geprüft und gebunden, nicht ausgewählt. Die eigentliche Auswahl
folgt nach Zonierung/OSM-Aufbereitung. Fehlende Dateien dürfen nur bei `next`
übersprungen werden; Defekte sind fatal. Normalisierte Revisionsdaten werden im
Lauf aus dem Eingabelock genutzt, nicht erneut aus veränderlichen Dateien gelesen.
Ein Fehler einer ausgewählten Revision löst keinen nachträglichen Quellenwechsel aus.

## Räumliche Komposition und Mengen

Vollständig vom Register abgedeckte Zonen behalten ihre Registerwerte, einschließlich
bestätigter Nullwerte. Nicht vollständig übernommene Zonen erhalten ganze Modellgewichte;
es gibt kein Addieren einer partiellen Registermenge auf ein ganzes Ersatzgewicht.
Das gilt für Punktregister, kommunale Punktgewichte und Polygonverteilung. Auch ein
nur teilweise überlappendes Punktregister ohne vollständig abgedeckte Zone ist bei
expliziten Lückenfüllern zulässig: Dann wird keine Registermenge angewendet.

Die native Lückenliste wählt einen Modellansatz für alle betroffenen Zonen. Ein lokales
OSM-Modellgewicht null löst nicht noch einen weiteren Quellenwechsel pro Zone aus.
Gemeindesummen, Außenmengen und nicht angewendete Teilzonenmengen bleiben getrennt.
Ergänzende Modellgewichte werden nicht zu einer angeblichen Arbeitsplatzgesamtzahl
addiert. Quellenjahre, Vollständigkeit und amtliche Schlüssel bleiben gesondert zu prüfen.

Ohne Registerlücken wird keine Ersatzquelle aufgelöst. Bei einer erschöpften oder
defekten Lückenliste werden auch die Registerwerte nicht teilweise übernommen.
Die komplette Komposition wird vor der Änderung der tatsächlichen Zonen berechnet.
Andere Zielzwecke und der gemeinsame Pipeline-Zufallsstrom bleiben erhalten.

`sources/lock.json.chains` enthält die gebundenen gemischten Eingaben.
`sources/resolution.json` zeigt tatsächliche Kandidaten und die Work-Komposition:
`gapResolution` enthält nur die ergänzten Zonen; der Herkunftsgraph enthält getrennte
Register- und `gap/…`-Beiträge. Fehler bleiben in den bisherigen Quellenfehlerberichten.
[Umsetzungsstand](../../06-gta-integration-konzept.md) und
[Nachweise und Grenzen](../../05-validierung.md).

Weitere Fachverträge, NoData-Masken,
amtliche Gebietsstandsüberleitungen und empirische Modellfreigaben sind damit nicht
vollständig umgesetzt.

Ergänzung 29.10.2026: Für ausdrücklich zugeordnete Zellzustände steht inzwischen
der separate [typisierte Rastervertrag](../masked-cells/README.md) zur Verfügung.
Dies erweitert die alten Punkt-/nativen Rasterverträge nicht stillschweigend.
