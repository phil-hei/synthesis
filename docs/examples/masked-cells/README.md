# Raster mit explizitem Wertstatus

Stand: 30.10.2026. Experimenteller technischer Vertrag für Bevölkerung und Work;
künstliche Vertragsdaten, keine reale Regionalbeobachtung.

## Import und Auswahl

Im Arbeitsbereich „Daten & Quellen“ über „Daten hinzufügen“ einen der Beiträge
„Bevölkerungsraster mit Wertstatus (EPSG:3035)“ (`population-cells-v1`) oder
„Arbeitsplatzraster mit Wertstatus (EPSG:3035)“ (`workplace-cells-v1`) wählen.
Der gemeinsame Reader und Mappingeditor ordnen verschiedene tabellarische Formate
auf diese Fachverträge ab; eine neue Formatdatei braucht keinen eigenen Generator.
Das Beispiel [cells.csv](masked-inputs/cells.csv) funktioniert für beide Verträge.

| Feld | Vertrag |
|---|---|
| `id` | Eindeutige Zell-ID |
| `easting`, `northing` | West-/Südkante der vollständigen Quellzelle, EPSG:3035 in Metern, Vielfache von 100 |
| `resolution` | Quadratkante, 100–10.000 m, Vielfaches von 100 |
| `state` | Genau `value`, `missing`, `not-collected` oder `suppressed` |
| `value` | Bei `value`: endliche nichtnegative Menge, einschließlich echter Null; bei anderen Zuständen: leer, Textmarker oder negative Kennzahl |

`missing` bedeutet fehlend, `not-collected` nicht erhoben und `suppressed`
unterdrückt/geheim gehalten. Der Status muss ausdrücklich zugeordnet sein. Eine
negative Kennzahl allein bestimmt keinen Status. Nichtnegative Zahlen unter einem
unbekannten Status sind widersprüchlich und werden abgelehnt. Nach Normalisierung
bleibt ihr Marker als `rawValue` erhalten; er fließt niemals in Mengen ein.
Überlappende Quellzellen, auch Maskenzellen, und doppelte IDs werden abgelehnt.
Jede vollständige Zelle muss in der deklarierten Quellenbox und im unterstützten
EPSG:3035-Gültigkeitsbereich liegen. Die Box allein bestätigt keine Werteabdeckung.

Quelle, Bezugsdatum, Einheit und Box prüfen, Vorschau ausführen und Revision
veröffentlichen. Danach die konkrete Revision für den Beitrag auswählen. Eine
Veröffentlichung aktiviert keine Datenquelle. Alternativ das fertige
[masked-inputs.zip](masked-inputs.zip) installieren und die
[Profildefinition](source-profile.json) über
`POST /api/scenarios/{id}/data/source-profiles` speichern; beide Aspekte können
an den zurückgegebenen Profilhash gebunden werden. Das Profil bindet den konkreten
Paketinhalt; [Manifest](masked-inputs/manifest.json).

## Lücken und Mengenanker

Vollständig bekannte Modellzonen übernehmen den Flächenanteil ihrer Quellmengen.
Bekannte Nullzellen belegen Raum und bleiben null. Die Quelle wird nicht auf die
Szenarioregion hochskaliert: Ein Viertel einer 30er-Zelle liefert 7,5. Die Verteilung
innerhalb der Quellzelle ist eine dokumentierte Gleichverteilungsannahme.

Nicht vollständig bekannte Zonen gelten **als ganze Zone** als Lücke. Ihre bekannten
Teilwerte werden nicht zusätzlich angewendet. Sie bleiben unter
`knownInPartialZonesNotApplied` sichtbar. Ein späteres regionales Work-Register
kann diese Zonen übernehmen; ansonsten gilt die explizite Lückenliste.

- Work: Ohne Lücken abbrechen (`gaps=none`) oder `gaps=providers` mit geordneten
  `osm-pois`-/`synthetic`-Kandidaten. Nur offene Zonen erhalten Modellgewichte.
  Registermengen und Ersatzgewichte werden nicht als beobachtete Gesamtsumme addiert.
- Bevölkerung: Ohne Lücken abbrechen oder Restmenge aus manueller Gesamtzahl
  modellieren. Die Gesamtzahl muss mindestens der bekannten angewendeten Menge
  entsprechen. Nur die Differenz wird mit ausdrücklich gewählten `osm-places`,
  `poi-density` oder `uniform` auf offene Zonen verteilt. Ohne Mengenanker ist die
  regionale Summe unbekannt und der Lauf schlägt fehl. Bei Restmenge null entsteht
  eine als Annahme belegte Nullzuordnung; es wird kein Ersatzprovider geladen.
- Vollständig bekannte Bevölkerungszonen: Ohne manuelle Gesamtzahl wird die Summe
  abgeleitet. Eine widersprechende manuelle Zahl wird für diesen Vertrag abgelehnt.
  Die vorhandene Pipeline rundet abgeleitete Gesamtzahlen auf ganze Personen;
  zonale Verteilungsgewichte können gebrochene Mengen bleiben. Ganze Haushalte
  haben weiterhin ihre separate Rundungsdiagnose.

Die UI bietet Reihenfolge, Abwahl und Ausfallregel je Lückenmodell sowie den
Bevölkerungsmengenanker. Planprüfung bindet Eingaben; Masken und tatsächliche
Zonenübernahme werden nach Zonierung im Laufbericht geprüft.

## Ausführbarer künstlicher Fall

Szenariobox: West/Ost 8,33/8,42; Süd/Nord 49,199/49,213. Zonierung `epsg3035`,
1.000 m. Die Daten enthalten fünf Quellzellen: 30, echte 0, fehlend (`NA`),
unterdrückt (`-1`) und nicht erhoben (leer). Deklarierte Quellenbox wie im Manifest
5/45–16/56, Einheit `persons` bzw. `jobs`, Bezugsdatum 2026-01-01.

Population `totalPersons=60`, `gaps=providers`, ausschließlich `uniform` aktiv.
Work `gaps=providers`, ausschließlich `synthetic` aktiv. Andere Provider ausdrücklich
abwählen. Für einen Offlinebeleg Template-Verhalten, Realrouting und externe
Simulation deaktivieren; nur `logitopp` als Zielmodell exportieren.

Erwartung: 30 bekannte Personen und ein erhaltener Nullwert; Restmenge 30 für die
offenen Zonen. Work übernimmt ebenfalls 30 und 0, ergänzt nur offene Zonen durch
synthetische Gewichte. Die Gesamtheit der Rasterzeilen definiert keine bekannte
Arbeitsplatz- oder Einwohnergesamtzahl, weil drei Werte unbekannt sind.

## Bericht und Grenzen

`sources/resolution.json.population.diagnostics.maskedCells` bzw. `.workplaces`
enthalten `synthesis-masked-cell-allocation-v1`. `quantityBasis` ist
`known-source-cells-only`: `knownInput = knownApplied + knownOutside +
knownInPartialZonesNotApplied` bis auf numerische Toleranz. Zellzustände und
Rohmarker, `zoneMasks`, Mengen und Lückengründe bleiben getrennt sichtbar.
`gapResolution` und Herkunftsgraph enthalten verwendetes Modell, Scope, Mengenanker
und Modellparameter. Datenquellen gelten ohne eigene Evidenzprüfung als `unknown`;
Flächenverteilung und Ergänzung als `assumed`, die Zusammensetzung als `derived`.

Mengen verwenden ungepufferte Schnittflächen. Für die Vollabdeckungsentscheidung
bestehen 1 cm Kantentoleranz und höchstens 0,01 m² Maskenüberlappung je Zone, um
Projektionsrauschen nicht als fachliche Teilabdeckung auszulegen.

Dieser Schnitt ist kein universeller Rasterreader: keine GeoTIFFs, beliebigen CRS,
unterzonalen Ergänzungen oder Ableitung geheimer Werte aus der Quellsumme. Der
native Zensus-/alte Rasterpfad bleibt mit seinem bisherigen strikten Vertrag
bestehen. Amtliche Registerprüfung, Schlüsselüberleitung und empirische Modellgüte
bleiben eigene Freigaben.

Der technische Browserfall vom 30.10.2026 erhält die Nullzone auch im Personenexport.
Seine vollständigen Haushalte umfassen 63 statt der Zielmenge 60 Personen; die
Restverteilung ist vor dieser bestehenden Haushaltsrundung mengengetreu.
[Ausgeführte Nachweise und Grenzen](../../05-validierung.md#rasterzellen-mit-expliziten-masken-2026-10-30).
