# Arbeitsplatzquellen einzeln auswählen

Stand: 29.10.2026. Experimentelle Auswahl zweier vorhandener Modellansätze für
Arbeitsgewichte. Beide liefern `model-weight`, keine beobachteten Arbeitsplatzmengen.

Im Studio „Daten & Quellen öffnen“, „Arbeitsplätze“ wählen, Quellenplan aktivieren
und die Quelle „Arbeitsplatzquellen einzeln konfigurieren“ auswählen. Die Reihenfolge
mit den Pfeilen ändern, Quellen abwählen und den Plan prüfen/speichern.
[Beispielinhalt für `scenario.dataPlan`](plan.json).

- `osm-pois`: Nutzt die in der Attraktivitätsstufe aus den vorhandenen OSM-POIs
  berechneten Work-Gewichte. Shop-, Büro-, Handwerks- und Amenity-Tags liefern die
  bisherigen Modellfaktoren aus `AttractivityCalculator`. Ein vorhandener POI-Bestand
  ohne passende Arbeitsplatzmerkmale darf Modellgewicht null liefern; das ist kein
  Nachweis tatsächlich fehlender Arbeitsplätze. Ohne POIs ist der Kandidat unpassend.
- `synthetic`: Nutzt das bisherige flächen-/zentralitätsbasierte Attraktivitätsmodell
  auf privaten Zonenkopien. Die eigene reproduzierbare Zufallsfolge verwendet
  `population.seed XOR 0x574f524b5f56314c`. Dadurch kann das Modell auch bei vorhandenen
  OSM-Daten ausdrücklich gewählt werden, ohne andere Zwecke oder die bestehende
  Pipeline-Zufallsfolge zu verändern. Es ist keine kalibrierte Arbeitsplatzschätzung.

Die erste verwendbare Quelle gewinnt. Abgewählte Kandidaten werden nicht aufgelöst;
spätere Kandidaten werden nach einem Treffer nicht geprüft. Fehlerhafte Gewichte
sind fatal, auch bei zugelassenem Ersatz. Fehlt eine räumlich passende Quelle und
ist kein Ersatzmodell zugelassen, bricht die Attraktivitätsstufe ab. Die gemeinsame
Komponente `ProviderChain` verwendet für diese Regeln denselben Algorithmus wie die
[native Bevölkerungskette](../population-sources/README.md).

Nur `Work` wird überschrieben. Bildung, Einkaufen, Freizeit, Bevölkerung und Straßen
bleiben unabhängig. Der gemeinsame OSM-Abruf kann für diese anderen Beiträge weiter
nötig sein. Die native OSM-Aufbereitung folgt weiterhin dem bestehenden Snapshot-/
Live-/Ausweichpfad; ihre einzelnen Transportversuche sind mit dieser Auswahl noch
nicht vollständig in neue Provideradapter migriert.

Der Bericht unter `sources/resolution.json.workplaces` enthält Rang/Status/Grund der
Kandidaten, zonenweise Gewichte mit externen IDs und Modellindizes sowie Teileherkunft.
OSM-Eingaben bleiben `unknown`, die Tag-zu-Gewicht-Ableitung `derived`, synthetische
Gewichte `assumed`. Nur ein tatsächlich ungeeigneter/fehlender früherer Kandidat
macht die Auswahl zum Fallback; reine Abwahl tut dies nicht. Die Gewichts-Summe ist
keine Arbeitsplatzsumme. Bei einer gescheiterten Quellenauflösung bleibt
`sources/workplace-failure.json` erhalten; der Folgelauf entfernt alte Berichte.

Importierte Punktregister und Gemeindeaggregate sind inzwischen mit nativen Kandidaten
in [gemischten Profilen](../mixed-sources/README.md) kombinierbar. Auch direkte
Revisionen unterstützen explizite native Lückenfüller. Der alte Standard
`gaps=legacy` bleibt für alte Auswahlpfade verfügbar; gemischte native Profile
verlangen einen expliziten Vertrag ohne impliziten Rückweg. `source=legacy` bleibt
für bisherige Szenarien Standard. Der Laufzweck `analysis` ersetzt keine eigene
fachliche Qualitätsfreigabe dieser Arbeitsplatzmodelle.
