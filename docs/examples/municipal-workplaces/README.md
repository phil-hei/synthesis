# Gemeindeaggregate und explizite Verteilungsgewichte

Diese Dateien sind **künstliche Vertragsfixtures**. Die Kombination aus Schlüsseln,
Koordinaten und Mengen bildet keine reale Gemeinde oder Arbeitsplatzerhebung ab.
Sie prüft ausschließlich Import, Referenzbindung und die Bilanz des gewählten Modells.

1. Ein Szenario um 8,20–8,26° Ost und 48,85–48,89° Nord speichern.
2. Unter **Daten & Quellen → Daten hinzufügen** den Vertrag **Räumliche Gemeindegewichte**
   wählen und `weights.csv` importieren. Quelle und Bezugsdatum angeben, Einheit
   `relative-weight`, Gebietsstand z. B. `2025-01-01`, deklarierte Datenbox
   Westen 8,1 / Osten 8,4 / Süden 48,7 / Norden 49,1. Zuordnung prüfen und Revision speichern.
3. **Arbeitsplätze je Gemeinde** mit `totals.csv` importieren. Einheit `jobs`, derselbe
   Gebietsstand, Box und Bezugsdatum; die eben gespeicherte Gewichtsrevision ausdrücklich
   wählen. Vollständige Aggregate für genau die enthaltenen Schlüssel und ihren
   Erhebungsumfang deklarieren, prüfen und eine neue Revision speichern.
4. Die Aggregatrevision für **Arbeitsplätze** auswählen. Die Lückenregel bestimmt,
   ob Zonen ohne explizite Zuordnung ihre bisherigen Gewichte behalten dürfen.
5. Quellenplan prüfen, speichern und generieren. Die supporting Gewichtsrevision
   wird nicht als eigener Nachfrageaspekt aktiviert.

## Fachlicher Vertrag

`municipal-workplaces-v1`: `id`, `ags`, `value`. Je AGS genau eine Summe, Werte
nicht negativ. `municipality-allocation-v1`: `id`, `ags`, `lon`, `lat`, `weight`.
Gewichte sind positiv. AGS werden als achtstelliger Text einschließlich führender
Nullen gelesen; verlorene führende Nullen sind ein Fehler.

Metadaten beider Revisionen enthalten `geography` mit `codeSystem: de:AGS`,
`edition: JJJJ-MM-TT` und `allocationMethod: explicit-point-weights-v1`.
Die Aggregatrevision referenziert zusätzlich `allocationRevision` als Inhaltshash.
Schlüsselraum, Gebietsstand und Modellversion müssen übereinstimmen. Für jeden
Aggregatschlüssel muss eine Zuordnung vorhanden sein. Amtliche Schlüsselgültigkeit,
Grenzpolygone und automatische Umrechnung zwischen Gebietsständen sind nicht enthalten.

## Bilanz

Für `01001000` gilt die Summe 100. Die Gewichte 3 und 1 ergeben Anteile 0,75 und
0,25. Der zweite Ort liegt außerhalb des Beispielgebiets. Der Nenner bleibt 4:
**75 werden zugeordnet, 25 als außerhalb liegend ausgewiesen.** Die Summe wird
nicht auf die innerhalb liegenden Orte umverteilt. `02000000` liefert eine explizite
Null an seinem zugeordneten Ort. Nicht zugeordnete Modellzonen sind unbekannt,
nicht automatisch null; allein die deklarierte Box erzeugt keine kommunale Abdeckung.

Die Punktverteilung ist eine explizit gewählte Modellannahme und macht aus
Gemeindesummen keine beobachteten Betriebsstandorte. Der Bericht erhält Originalsumme,
Referenzhash, Quellzeilen/-IDs, Gebietsstand, Verteilungsanteile, Zonen und Außenmenge.
Die Gewichtsrevision wird zusammen mit den eigentlichen Eingaben eingefroren.

## Alternative: Gemeindegeometrien mit Flächenverteilung

`areas.geojson` enthält künstliche Polygonflächen mit einem Loch. Sie dienen
keiner realen Verwaltungsgrenzenprüfung. Als Fachvertrag **Gemeindegeometrien
(GeoJSON)** importieren, `area-boundary` als Einheit, Gebietsstand und tatsächliche
Quellenbox angeben. Der GeoJSON-Reader erhält Geometrien und Featurebelege;
skalare Eigenschaften wie `AGS` können im gemeinsamen Editor zugeordnet werden.

Im Aggregateimport **Flächengleichverteilung anhand Gemeindegeometrien** wählen
und die veröffentlichte Geometrierevision auswählen. Intern ist das Modell
`uniform-area-v1` mit Referenzvertrag `municipality-areas-v1`. Beide Revisionen
müssen Schlüsselraum und Gebietsstand teilen. Die vorhandene Punktgewichtsmethode
bleibt als getrennte Auswahl verfügbar. Ein Methodenwechsel löscht die vorherige
Referenzauswahl, damit keine unpassende Revision weiterverwendet wird.

Es gilt eine konstante Arbeitsplatzdichte innerhalb der vollständigen jeweiligen
Gemeindefläche. Die berechnete Menge je Modellzone ist Gemeindesumme mal
Schnittfläche/gesamter Gemeindefläche in EPSG:3035. Der Nenner wird nicht auf das
Szenariogebiet zugeschnitten. Löcher und getrennte Polygonteile bleiben erhalten.
Eine teilweise überdeckte Zone behält ihr bisheriges Gewicht nur nach erlaubter
Lückenregel; die darin modellierte, nicht angewendete Gemeindemenge steht separat
als `unappliedPartialJobs` im Bericht. Die Bilanz lautet:

`inputJobs = appliedJobs + unappliedPartialJobs + outsideJobs` (numerische Genauigkeit).

Für die Abdeckungsentscheidung gilt ein dokumentierter numerischer Randpuffer von
1 cm, entsprechend der bestehenden Projektionsgenauigkeit. Mengen werden immer
an den ungepufferten Flächen berechnet; der Puffer erzeugt keine zusätzliche Menge.
Echte Löcher mit mehr als 2 cm Breite bleiben dadurch erkennbar. Die Toleranz ist
keine Aussage über die geografische Genauigkeit einer bereitgestellten Quelle.

Grenzen: WGS84 lon/lat ohne eigenes CRS-Feld, Polygon oder MultiPolygon, höchstens
500 Features und insgesamt 50.000 Koordinaten bei 5 MB Datei. Ungültige Ringe,
fehlende Schlüssel, doppelte AGS und überlappende Gemeindeflächen werden abgewiesen.
Amtliche Gültigkeit der Geometrien/AGS sowie automatische Schlüsselüberleitung
zwischen Gebietsständen sind zusätzliche Nachweise und bleiben offen.
