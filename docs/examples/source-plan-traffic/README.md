# Schwerlastquellen, additive Unternehmensfahrten und SUMO-Wirkung

Stand: 02.11.2026. Technische Migrationsabnahme mit künstlichen Vertragsdaten.
Keine empirische regionale Nachfragekalibrierung und keine Freigabe des fachlichen
Schwerlastfallbacks.

## Umfang und Auswahl

`freight.vehicleOd` steuert ausschließlich den schweren Hintergrundverkehr.
Unternehmensfahrten, Paketlogistik und Personennachfrage sind eigenständige Beiträge.
Die UI „Daten & Quellen“ weist auf diese Trennung hin. Insbesondere entfernt
„Schwerlastverkehr bewusst ausschließen“ keine aktivierten Unternehmenstouren.

Eine importierte `vehicle-od-v1`-Revision kann direkt oder über ein geordnetes
Quellenprofil gewählt werden. Der Lauf bindet die konkrete Revision vor Queueing;
spätere Quell- oder Szenarioänderungen ersetzen die gebundenen Relationen nicht.
Die vorhandenen Firmenimport-/Mapping-/Ausführungsversionen bleiben separat.

## Tatsächlich angewendete Quellenbilanz

`sources/resolution.json.freight` enthält jetzt
`synthesis-freight-source-resolution-v1` mit folgenden Informationen:

| Feld | Bedeutung |
|---|---|
| `source`, `profile` | Effektive Auswahl und gegebenenfalls konkrete Quellenprofilversion |
| `status` | `generated`, `excluded` oder `not-enabled`; keine Aussage über abgeschlossene Fahrzeuge |
| `inputVehicleTrips`, `generatedVehicleTrips` | Eingangs- und tatsächlich erzeugte Tagesmenge dieses Beitrags |
| `contributions` | Pro Relation Quellenzeile, Revisions-/Rohhash, Metadaten, Eingangs-/Ergebnismenge und vollständige ursprüngliche Relation |
| `byMovement` | Erzeugte Binnen-, Quell-, Ziel- und Durchgangsfahrten getrennt |
| `tripLedger`, `tripJoin` | Vollständige Fahrten unter `freight/trips.csv`, Join über `relationId` |
| `departureProfile` | Schichtung innerhalb des vorgegebenen Zeitfensters, Annahmenstatus, Seed und Seedableitung |
| `targetEvidence` | Verweise auf eigenständige SUMO-Routing-/Ausführungsbilanzen |

Erzeugte Fahrtmengen, Klassen, Relation, Koordinaten, Kanten und Zeitfenster werden
gegen ihre gebundenen Relationen geprüft. Eingangs- und Ergebnismengen müssen je
Relation übereinstimmen. Ein Quellenwechsel kann andere Relationen/Mengen liefern;
die unveränderten Relationen behalten ihre eigene reproduzierbare Zufallsfolge.

Nutzerdateien gelten ohne weitere fachliche Prüfung als `unknown`. Die technische
Lkw-Demonstration bleibt `assumed` und für fachliche Auswertung gesperrt. Auch das
Manifest und die älteren Dateien `freight/sources.json` und `freight/summary.json` kennzeichnen diesen
expliziten Demonstrationspfad jetzt als Annahme. Die Modellierung einzelner
Abfahrten innerhalb eines Eingangsfensters bleibt eine Annahme, auch bei importierten
Relationsmengen. Aus erzeugten Fahrten entsteht kein Beweis einer Beobachtung.

## Erzeugung, Routing und Ausführung getrennt lesen

Die Quellenbilanz belegt erzeugte Anfragen. Erst `sumo/traffic-routing.json` und
`sumo/traffic-execution.json` belegen Routing beziehungsweise Abfahrt/Abschluss.
`freight/execution.json` bilanziert den schweren Beitrag getrennt. Die Zahl der
Bewegungen ist nicht zwingend die Zahl physischer Fahrzeuge: Eine Unternehmenstour
mit drei Beinen kann mit einem Fahrzeug ausgeführt werden.

Ein abgewähltes oder fehlendes Zielmodell liefert keinen Ausführungsbeweis. Die
neue Quellenbilanz verweist deshalb auf diese separaten Belege, statt einen Export
als erfolgreich simuliert auszugeben.

## Feste Prüfmenge

Die kleinen [Testfixtures](../../../backend/src/test/resources/source-plan-traffic/)
enthalten zwei übersetzte künstliche Netzlagen und eingefrorene CSV-Dateien.
Die zweite Netzlage ist keine unabhängige reale Stadt-/Landreferenz.
Seeds: 7, 42 und 777. Region Süd 8,2/48,848–8,206/48,852; zweite Lage
7,4/51,498–7,406/51,502. Quelle A enthält vier Außenbeziehungsarten mit je zwei
Fahrten; Quelle B erhöht nur die Binnenmenge auf drei. Die bestätigte
Unternehmenstour liefert jeweils drei Beine mit fester Fahrzeugidentität.

| Auswahl | Schwerlast | Unternehmensbeine | Gemeinsame Bewegungen |
|---|---:|---:|---:|
| Bestand, Revision A oder Profil mit A zuerst | 8 | 3 | 11 |
| Revision B | 9 | 3 | 12 |
| Schwerlasthintergrund ausgeschlossen | 0 | 3 | 3 |

Der Exportvergleich erhält in jeder Lage und bei jedem Seed 69 fachliche Dateien
unter `population/`, `matrix/`, `zone-repository/`, `legacy/`, `logistics/`
bytegleich. Unternehmenstouren und ihre Import-/Mapping-/Ausführungsidentitäten
bleiben gleich. Bestand und gleichwertige Revision A liefern zudem denselben
**gefüllten** gemeinsamen Nachfragecontainer.

Die native Abnahme läuft durch `GenerationService` mit gewähltem Quellenprofil A,
Revision B und Schwerlastausschluss, insgesamt 18 SUMO-Läufe. Sie prüft angeforderte,
geroutete, abgefahrene und abgeschlossene Bewegungen sowie null Teleports. logiTopp
ist dabei ausdrücklich deaktiviert: Die privaten Population-/Aktivitätsartefakte
sind vorhanden und unverändert, **simulierte private Personenfahrten sind in diesen
SUMO-Läufen nicht enthalten**. Paketfahrten sind ebenfalls nicht aktiv.

Tests aus `backend/`:

```bash
mvn -o -Daether.syncContext.named.factory=noop \
  -Dtest=SourcePlanTrafficTest,SourcePlanTrafficIntegrationTest test
```

Die fünf Export-/Vertragsfälle laufen ohne externe Laufzeit. Die zwei parametrisierten
nativen Fälle benötigen installierte SUMO-Binaries und werden bei fehlender Laufzeit
mit benanntem Grund übersprungen. Es gibt keinen Live-Datenabruf. Der ausgeführte
Nachweis muss deshalb neben grünen Tests auch die tatsächliche Skip-Zahl ausweisen.

[Vorab festgelegte Eingaben und Sollwerte](../../verification/2026-11-02-source-plan-traffic-plan.json),
[ausgeführte Prüfungen und Grenzen](../../05-validierung.md#quellenplan-schwerlast-unternehmensbeiträge-und-native-zielwirkung-2026-11-02).
