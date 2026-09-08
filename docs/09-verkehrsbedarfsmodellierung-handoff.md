# Verkehrsbedarfsmodellierung – technisches Handoff zur Wiederverwendung

**Stand: 2026-09-08.** Dieses Dokument beschreibt die kleinste fachlich sinnvolle Teilmenge von
SyntheSis, mit der ein anderes Projekt aus einer geografischen Region eine synthetische
Bevölkerung und einen agentenbasierten Verkehrsbedarf erzeugen kann. Es ist zugleich als Kontext
für ein implementierendes LLM gedacht: Neben Codefragmenten nennt es Reihenfolge,
Datenverträge, Fallbacks, Invarianten und bekannte Grenzen.

## 1. Ziel und Ergebnis

Eingabe ist mindestens eine geografische Region als Bounding Box:

```json
{
  "minLat": 48.84,
  "minLon": 8.17,
  "maxLat": 48.88,
  "maxLon": 8.23
}
```

Die Verarbeitungskette lautet:

```text
Region
  → Verkehrszellen
  → Einwohnerzahl und räumliche Verteilung
  → Haushalte und Personen
  → Zielattraktivitäten
  → feste Arbeits- und Bildungsorte
  → Wochenaktivitätenpläne
  → Distanz-, Reisezeit- und Kostenmatrizen
  → Ziel- und Verkehrsmittelwahl
  → Wege, Touren und Kennzahlen
```

Ein nachgebautes System sollte mindestens folgende Artefakte erzeugen:

| Artefakt | Zweck |
|---|---|
| `zones.csv`/`zones.geojson` | Geometrie und IDs der Verkehrszellen |
| `attractivities.csv` | Zielattraktivität je Zone und Aktivitätszweck |
| `household.csv` | Haushalte mit Wohnzone |
| `person.csv` | Personen und Mobilitätsmerkmale |
| `fixedDestination.csv` | feste Arbeits- und Bildungsziele |
| `activity.csv` | zeitliche Wochenaktivitäten je Person |
| Distanz-/Zeit-/Kostenmatrizen | Widerstände zwischen allen Zonenpaaren |
| normalisierte Wege-Datei | Start, Ziel, Zeit, Zweck, Modus und Distanz je Weg |
| `kpis.json` | Wege/Person/Tag, Distanzen und Modal-Split |

Die SyntheSis-Orchestrierung steht in
[`GenerationService`](../backend/src/main/java/de/synthesis/generation/GenerationService.java).

## 2. Region zonieren

Für einen unabhängigen Prototyp genügt ein quadratisches Raster. Jede Zone benötigt eine stabile
ID, einen fortlaufenden Matrixindex, Bounding Box, Schwerpunkt, Fläche, Einwohnerzahl und
Attraktivitäten.

```java
double middleLatitude = (region.minLat + region.maxLat) / 2;
double heightM = (region.maxLat - region.minLat) * 111_320;
double widthM = (region.maxLon - region.minLon) * 111_320
        * Math.cos(Math.toRadians(middleLatitude));

double requiredCellSize = Math.sqrt(heightM * widthM / maxZones);
double effectiveCellSize = Math.ceil(requiredCellSize / 50.0) * 50.0;
```

```java
int rows = (int) Math.ceil(heightM / effectiveCellSize);
int columns = (int) Math.ceil(widthM / effectiveCellSize);
if ((long) rows * columns > maxZones) {
    throw new IllegalArgumentException("Zu viele Zonen");
}
```

Referenz: [`GridZoning`](../backend/src/main/java/de/synthesis/generation/pipeline/GridZoning.java).
SyntheSis unterstützt zusätzlich ein flexibel aufgelöstes EPSG:3035-Raster und das amtlich
ausgerichtete INSPIRE-Gitter. Entscheidend ist, dass dieselben Zonen-IDs und Matrixindizes in
allen Folgeartefakten verwendet werden.

**Skalierungsgrenze:** Die Matrizen wachsen quadratisch. 600 Zonen erzeugen 360.000, 1.200 Zonen
bereits 1.440.000 OD-Beziehungen je Matrix. Die feinste geometrisch mögliche Auflösung ist daher
nicht automatisch die feinste praktisch rechenbare Auflösung.

## 3. Einwohnerzahl des Gebiets bestimmen

Die Frage „Wie viele Menschen wohnen in der Region?“ wird mit einer priorisierten Quellenkette
beantwortet:

```java
if (manualPopulation != null) {
    totalPopulation = manualPopulation;
    source = "user-defined";
} else if (uploadedPopulationGridAvailable) {
    totalPopulation = sumGridCellsInsideRegion(uploadedGrid);
    source = "upload-grid";
} else if (census2022Available) {
    totalPopulation = sumGridCellsInsideRegion(censusGrid);
    source = "census-2022";
} else if (osmSettlementsAvailable) {
    totalPopulation = estimateFromOsmSettlements(region, settlements);
    source = "osm-places-estimate";
} else {
    totalPopulation = Math.round(regionAreaKm2 * assumedPersonsPerKm2);
    source = "density-estimate";
}
```

### 3.1 Amtliches Bevölkerungsraster

Für Deutschland verwendet SyntheSis das Zensus-2022-100-m-Raster. Das Archiv wird über URL,
Version, Stichtag und SHA-256 reproduzierbar fixiert. Eine Hash-Abweichung oder ein Downloadfehler
aktiviert den nächsten Fallback und darf keinen unbemerkten Datenwechsel verursachen.

Der Parser unterstützt:

- Zensus-Koordinaten `x_mp_100m;y_mp_100m` in EPSG:3035,
- INSPIRE-/Eurostat-Zell-IDs wie `CRS3035RES100mN...E...`,
- generische WGS84-Dateien `lat;lon;population`.

Referenzen:

- [`CensusPopulationSource`](../backend/src/main/java/de/synthesis/generation/pipeline/CensusPopulationSource.java)
- [`PopulationGridParser`](../backend/src/main/java/de/synthesis/generation/pipeline/PopulationGridParser.java)
- [`Epsg3035`](../backend/src/main/java/de/synthesis/generation/pipeline/Epsg3035.java)

### 3.2 OSM-Fallback

OSM-`place`-Knoten enthalten teilweise ein `population`-Attribut. Ein Ortsmittelpunkt darf nicht
als punktförmige Bevölkerung behandelt werden. SyntheSis legt deshalb eine zweidimensionale
Gauß-Verteilung um den Mittelpunkt und integriert deren Anteil innerhalb der Region:

```java
double sigmaKm = Math.max(
        0.6,
        Math.sqrt(place.population() / (3500 * Math.PI))
);

double populationInside = place.population()
        * gaussianShareInsideLatitudeBounds
        * gaussianShareInsideLongitudeBounds;
```

Referenz: [`PopulationEstimator`](../backend/src/main/java/de/synthesis/generation/pipeline/PopulationEstimator.java).
OSM ist hier ausdrücklich eine Schätzung. Die belastbare Reihenfolge bleibt:
manuell beziehungsweise alternatives Raster → Zensus → OSM → Dichteannahme.
Für die Bevölkerung werden Places auf den lokalen Abfrageausschnitt gefiltert
(`OverpassClient.populationPlaces`). Siedlungen aus dem erweiterten 80-km-Diagnoseradius
für Außenbeziehungen dürfen die Einwohnerzahl oder ihre Verteilung nicht verändern.
Dies gilt auch beim Wiederverwenden eingefrorener OSM-Snapshots.

## 4. Einwohner auf Zellen verteilen

Rasterpunkte werden über ihre Koordinaten einer Zone zugeordnet. Ohne Raster dienen die
integrierten OSM-Siedlungsgewichte, danach POI-Dichte und schließlich gleichmäßiges Rauschen als
Fallback.

```java
for (Zone zone : zones) {
    double weight;
    if (sumOfPlaceWeights > 0) {
        weight = placeWeights[zone.index]
                + sumOfPlaceWeights / zones.size() * 0.03;
    } else {
        weight = 1 + 0.5 * random.nextDouble();
        weight += Math.min(poiCount[zone.index], 50) * 0.06;
    }
    weights[zone.index] = weight;
}
for (Zone zone : zones) {
    zone.population = totalPopulation * weights[zone.index] / sum(weights);
}
```

Der kleine Drei-Prozent-Boden verhindert vollständig leere Randzonen. Referenz:
[`PopulationGenerator.distributePopulation`](../backend/src/main/java/de/synthesis/generation/pipeline/PopulationGenerator.java).

Invarianten:

1. Die Summe der zonalen Zielbevölkerung entspricht der Einwohnerzahl der Region.
2. Externe Zielzonen erhalten keine Einwohner.
3. Quelle, Stichtag, Version und Hash werden im Manifest festgehalten.

## 5. Haushalte und Personen synthetisieren

Pro Zone werden ganze Haushalte gezogen, bis ihre Zielbevölkerung erreicht ist. SyntheSis kann
ein gebündeltes KIT-IPU-Sample im Format `householdId;age;sex` ziehen. Dadurch bleiben Alter,
Geschlecht und Haushaltsgröße gemeinsam erhalten. Alternativ existiert ein vereinfachter
synthetischer Fallback:

```java
double[] householdSizeShares = {
    0.42, // eine Person
    0.33, // zwei Personen
    0.12, // drei Personen
    0.09, // vier Personen
    0.04  // fünf Personen
};
```

Je Person werden mindestens Alter, Geschlecht, Erwerbsstatus, Fahrradbesitz, Führerschein,
ÖV-Zeitkarte und Haushaltszugehörigkeit benötigt. Je Haushalt werden Wohnzone, Koordinate,
Einkommen und Pkw-Bestand erzeugt.

Referenzen:

- [`PopulationGenerator`](../backend/src/main/java/de/synthesis/generation/pipeline/PopulationGenerator.java)
- [`PopulationSample`](../backend/src/main/java/de/synthesis/generation/pipeline/PopulationSample.java)
- [`kit-population-sample.csv`](../backend/src/main/resources/kit-population-sample.csv)

Das vorhandene Sample stammt aus einem ländlich geprägten Beispielgebiet. Für andere Regionen
sollte es durch ein passendes Mikrodaten-Sample oder eine echte IPU-Synthese ersetzt werden.
Die Stichprobe wird erst nach der regionalen Bevölkerungsverteilung gezogen. Aus
`samplingFraction = 0.1` folgt eine Zehn-Prozent-Modellbevölkerung; die globale Rundung muss
zonenzahlunabhängig bleiben.

## 6. Zielattraktivitäten aus OSM ableiten

POIs werden Aktivitätszwecken zugeordnet. Das vorhandene Modell ist ein transparenter Startpunkt,
keine universell kalibrierte Wahrheit:

```java
if (shop != null) {
    add(zone, dailyShop ? "Shopping_Daily" : "Shopping_Other", 25);
    add(zone, "Work", 8);
}
if (office != null) {
    add(zone, "Business", 30);
    add(zone, "Work", 40);
}
if ("school".equals(amenity)) {
    add(zone, "Education_Primary", 300);
    add(zone, "Education_Secondary", 400);
    add(zone, "Work", 30);
}
```

Verwendete Zwecke sind `Work`, drei Bildungsstufen, täglicher und sonstiger Einkauf, Indoor-
und Outdoor-Freizeit, Service, private Erledigung und privater Besuch. Private Besuche skalieren
zusätzlich mit der Wohnbevölkerung.

Referenz: [`AttractivityCalculator`](../backend/src/main/java/de/synthesis/generation/pipeline/AttractivityCalculator.java).

## 7. Arbeits-, Bildungs- und sonstige Ziele wählen

Der einfache Fallback verwendet ein Gravitationsmodell:

```java
double distanceKm = distance(homeZone, candidateZone);
double attractivity = candidateZone.attractivity(category) + 1;
double weight = attractivity / (1 + distanceKm * distanceKm);
```

Aus kumulierten Gewichten wird reproduzierbar ein Ziel gezogen. Für Arbeitsorte kann eine
beobachtete Pendler-OD-Matrix das Gravitationsmodell ersetzen. Der robuste Austauschvertrag ist:

```text
homeLat;homeLon;workLat;workLon;commuters;homeScope
```

Dabei sind zwei getrennte Aussagen nötig:

```text
Arbeitsplatz-Randwert → ob ein Arbeitsziel innerhalb oder außerhalb liegt
Pendler-OD           → wo dieses Ziel liegt
```

Referenzen:

- [`PopulationGenerator.sampleWorkZone`](../backend/src/main/java/de/synthesis/generation/pipeline/PopulationGenerator.java)
- [`CommuterMatrix`](../backend/src/main/java/de/synthesis/generation/pipeline/CommuterMatrix.java)
- [`WorkplaceMargins`](../backend/src/main/java/de/synthesis/generation/pipeline/WorkplaceMargins.java)
- [`ExternalZones`](../backend/src/main/java/de/synthesis/generation/pipeline/ExternalZones.java)

Die synthetischen Außenanker verbessern kleine Gebiete, reproduzieren aber nicht allein die
MiD-Fernwegverteilung. Amtliche Ziele dürfen nicht still durch eine OSM-Schätzung ersetzt werden.

## 8. Wochenaktivitäten statt direkter Wegerzeugung

Ein agentenbasiertes Modell erzeugt zunächst Aufenthalte:

```text
Montag 00:00  HOME
Montag 07:30  WORK
Montag 16:30  SHOPPING_DAILY
Montag 17:15  HOME
```

Jeder Übergang zwischen zwei Aktivitäten bildet einen Weg. SyntheSis nutzt bevorzugt actiTopp;
daneben existieren ein einfacher Template-Generator und eine experimentelle LLM-Quelle.

Wichtige Invarianten:

- Zeitangaben sind Minuten seit Montag, 00:00 Uhr.
- Jeder Tag endet wieder zu Hause.
- Aufeinanderfolgende HOME-Aktivitäten werden zusammengeführt.
- Genau eine Hauptaktivität wird je Tour markiert.
- Jede Person erhält ein WORK- und EDUCATION-Fixziel; es wird nur verwendet, wenn die Aktivität
  im erzeugten Plan tatsächlich vorkommt.

Referenzen:

- [`ActivityGenerator`](../backend/src/main/java/de/synthesis/generation/pipeline/ActivityGenerator.java)
- [`ActitoppRunner`](../backend/src/main/java/de/synthesis/generation/pipeline/ActitoppRunner.java)
- [`GtaBehaviorRunner`](../backend/src/main/java/de/synthesis/generation/pipeline/GtaBehaviorRunner.java)

Ohne actiTopp kann ein anderes Projekt plausible Templates erzeugen, aber keine gleichwertig
kalibrierte Wochenverhaltensmodellierung beanspruchen.

## 9. Distanz-, Reisezeit- und Kostenmatrizen

Wenn ein Routinggraph verfügbar ist, werden Pkw, Rad und Fuß geroutet. Andernfalls gelten
transparente Näherungen:

```java
carDistanceKm  = beelineKm * 1.35;
bikeDistanceKm = beelineKm * 1.25;
walkDistanceKm = beelineKm * 1.20;

carTimeMin  = carDistanceKm / 28.0 * 60 + 3;
bikeTimeMin = bikeDistanceKm / 14.0 * 60 + 2;
walkTimeMin = walkDistanceKm / 4.7 * 60;
ptTimeMin   = carTimeMin * 1.7 + 8;

carCost = carDistanceKm * 0.32;
ptCost  = 1.80 + beelineKm * 0.15;
```

Intrazonale Wege dürfen nicht die Distanz null erhalten:

```java
double intrazonalBeelineKm = 0.5214 * Math.sqrt(zone.areaKm2);
```

Referenzen:

- [`MatrixGenerator`](../backend/src/main/java/de/synthesis/generation/pipeline/MatrixGenerator.java)
- [`RoutingEngine`](../backend/src/main/java/de/synthesis/generation/pipeline/RoutingEngine.java)
- [`VisumMatrixWriter`](../backend/src/main/java/de/synthesis/generation/pipeline/VisumMatrixWriter.java)

Einheiten müssen explizit bleiben: Reisezeit in Minuten, Kosten in Euro und die vorhandenen
Legacy-Distanzmatrizen in Metern.

## 10. Ziel- und Verkehrsmittelwahl

Die einfache Zielwahl aus Abschnitt 7 reicht für einen Prototyp. SyntheSis übergibt seine
Artefakte für die vollständige diskrete Ziel- und Moduswahl an mobiTopp/logiTopp:

```text
P(Alternative i) = exp(U_i) / Σ_j exp(U_j)

U_i = ASC_i
    + β_time · Reisezeit_i
    + β_cost · Kosten_i
    + weitere Personen- und Wegeattribute
```

Alternative-spezifische Konstanten können gegen einen Ziel-Modal-Split kalibriert werden:

```java
double deltaAsc = Math.log(targetShare / simulatedShare);
deltaAsc = Math.max(-1.5, Math.min(1.5, deltaAsc));
```

Der Pkw-Fahrermodus bleibt als Referenz verankert. Referenzen:

- [`ModeChoiceCalibrator`](../backend/src/main/java/de/synthesis/generation/pipeline/ModeChoiceCalibrator.java)
- [`SimulationRunner`](../backend/src/main/java/de/synthesis/generation/pipeline/SimulationRunner.java)
- [`SimulationResultIngestor`](../backend/src/main/java/de/synthesis/generation/pipeline/SimulationResultIngestor.java)

Bundesweite MiD-2017-Vergleichswerte im Projekt sind 22 % Fuß, 11 % Rad, 43 % Pkw-Fahrer,
14 % Pkw-Mitfahrer und 10 % ÖV sowie ungefähr 3,1 Wege je Person und Tag. Diese Werte sind
Vergleichs- beziehungsweise Kalibrierziele, keine für jede Region gültige Wahrheit.

## 11. Reproduzierbarkeit und Provenienz

Ein nachgebautes System sollte jeden Lauf durch folgende Angaben reproduzierbar machen:

```text
Szenario-JSON
Zufalls-Seed
Quellversion und Stichtag
URL und SHA-256 heruntergeladener Daten
Hash des verwendeten OSM-Snapshots
Hash der Attraktivitäten
tatsächlich verwendete Zellgröße
Fallback-Entscheidungen
Versionen externer Modelle und Parameterdateien
```

Der Seed allein genügt nicht. Gleicher Seed bei veränderten OSM-, Zensus- oder Parameterdaten
ergibt fachlich keinen identischen Lauf. Live-Daten sollten nach dem ersten Abruf als
Szenario-Snapshot eingefroren werden.

## 12. Validierungskriterien

| Bereich | Prüfung |
|---|---|
| Bevölkerung | Summe, räumliche Verteilung, Haushaltsgrößen und Altersverteilung |
| Aktivitäten | Wege/Person/Tag, Heimtage, Hauptaktivitäten und Tagesabschluss HOME |
| Distanzen | Distanzbänder statt nur Mittelwert; insbesondere `<1 km`, `1–2 km`, `>=10 km`, `>20 km` |
| Modi | Modal-Split insgesamt und nach Distanzband |
| Pendeln | Innen-/Außenanteil, Richtungsverteilung und mittlere Außendistanz |
| Reproduzierbarkeit | gleiche Inputs + gleicher Seed → identische Kernartefakte |
| Datenqualität | jeder Fallback sichtbar in Log und Manifest |

Kleine Gebiete können die bundesweite mittlere Weglänge geometrisch nicht reproduzieren. Das ist
nicht automatisch ein Implementierungsfehler. Gebietsgröße, Zonenauflösung, Attraktivitätsraum
und Zielwahlparameter müssen gemeinsam beurteilt werden.

## 13. Was direkt wiederverwendbar ist – und was nicht

Mit diesem Dokument und den verlinkten Klassen kann ein anderes LLM Zonen erzeugen,
Zensus-/Rasterdaten einlesen, Einwohner verteilen, Haushalte und Personen synthetisieren,
OSM-POIs klassifizieren, Ziele ziehen, einfache Wochenpläne erzeugen, Matrizen berechnen sowie
Wege und grundlegende KPIs exportieren.

Nicht allein aus diesen Fragmenten rekonstruierbar sind:

- die vollständigen kalibrierten actiTopp-Verhaltensmodelle,
- mobiTopp/logiTopp und deren lokale Parameterstände,
- belastbare regionalisierte Zielwahlparameter,
- amtliche Pendler-OD-Daten für beliebige Regionen,
- wissenschaftlich validierte Übertragbarkeit auf andere Länder.

Vor der Übernahme müssen die Lizenzen von Code, Zensus, OSM und externen Modellen getrennt
geprüft werden. Aus transparenten Fallback-Heuristiken darf keine amtlich kalibrierte Nachfrage
abgeleitet oder behauptet werden.

## 14. Kopierbarer Arbeitsauftrag für ein anderes LLM

```text
Implementiere einen modularen Verkehrsbedarfsgenerator für eine WGS84-Bounding-Box.

1. Erzeuge stabile quadratische Verkehrszellen mit konfigurierbarer Zellgröße und
   Zonengrenze. Verwende durchgängig dieselbe Zonen-ID und denselben Matrixindex.
2. Bestimme die Einwohnerzahl über diese Priorität:
   manuell → hochgeladenes Raster → Zensus-Raster → OSM-place-Schätzung → Flächendichte.
3. Verteile die Einwohner räumlich über Rasterpunkte oder OSM-Gewichte und erzeuge ganze
   Haushalte sowie Personen mit reproduzierbarem Seed.
4. Klassifiziere OSM-POIs in die dokumentierten Aktivitätszwecke und bilde zonale
   Attraktivitäten.
5. Weise WORK- und EDUCATION-Fixziele über beobachtete Pendlerdaten oder als klar
   gekennzeichneten Gravitationsfallback zu.
6. Erzeuge gültige Wochenaktivitätenpläne und leite aus jedem Aktivitätswechsel einen Weg ab.
7. Berechne für jedes Zonenpaar Distanz, Reisezeit und Kosten je Modus. Nutze Routing, wenn
   verfügbar, sonst die dokumentierten Näherungen. Setze intrazonale Distanz niemals auf null.
8. Exportiere alle Zwischenartefakte sowie ein Manifest mit Seed, Quellen, Stichtagen,
   Versionen, Hashes und Fallbacks.
9. Trenne Datenbeschaffung, Synthese, Wahlmodelle und Exporte in testbare Module.
10. Schreibe Tests für Summenerhalt, stabile IDs, Seed-Reproduzierbarkeit, Einheiten,
    Fallbacks und die wichtigsten Nachfrage-KPIs.

Übernimm keine Behauptung fachlicher Kalibrierung, die nicht durch regionale Referenzdaten
und kontrollierte Mehrseed-Tests belegt ist.
```

Damit lässt sich ein eigenständiger, transparenter Prototyp bauen. Für Ergebnisse auf dem Niveau
des vollständigen SyntheSis-Laufs müssen die externen Verhaltens- und Simulationsmodelle sowie
ihre Daten- und Parameterverträge zusätzlich integriert werden.
