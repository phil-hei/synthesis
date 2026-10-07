# Fahrzeugimport und Modellbegriffe

Stand: 02.10.2026. Die angehängte `vehicle.json` wurde als Formatbeispiel geprüft;
sie enthält keine Implementierungsanweisungen.

## Vorhandener Fahrzeugimport

Die Backend-API existiert bereits:

- `POST /api/vehicle-configurations/validate`: JSON prüfen.
- `GET /api/vehicle-configurations/schema`: versioniertes Zielformat.
- `GET /api/vehicle-configurations/example`: Beispielkonfiguration.
- `GET/PUT /api/scenarios/{id}/vehicle-configuration`: laden und speichern;
  Schreiben verlangt das zuvor geladene ETag als `If-Match`.

Der Vertrag ist `synthesis-vehicle-configuration-v1`. Das Mail-Beispiel mit einem
obersten `vehicles`-Array entspricht diesem Vertrag nicht. Eine Datei-Upload-UI
und ein expliziter Adapter für dieses vorläufige Format sind noch zu ergänzen.
Keine stillschweigende Umdeutung oder Löschung unbekannter Felder.

| Beispielparameter | Ziel im bestehenden Modell | Klärung / Wirkung |
|---|---|---|
| id, type, domain, modality | vehicleTypes / typeRef, category, routing | Typdefinition und konkrete Instanz unterscheiden; Versionskennung nötig |
| mass_kg, size_m, max_speed_kmh, acceleration_mps2 | physical | Leer- oder Gesamtmasse klären; Länge/Tempo/Beschleunigung können SUMO beeinflussen |
| energy_type, consumption_per_km | powertrain | Verbrauchseinheiten klären, insbesondere L/km versus kWh/km |
| co2_per_km_g | emissions | Bilanzgrenze und Quelle fehlen; Referenzwert ist kein automatisch angewendetes Emissionsmodell |
| noise_db | noise | Messbedingungen, Abstand und Gewichtung fehlen; bisher Metadaten |
| cargo_capacity | capacity | Nutzlast und Volumen sind Typmerkmale; derzeit keine Wirkung auf logiTopp-Tourkapazität |
| current_load | initialVehicles.state | Laufzustand einer konkreten Instanz; nicht Typmerkmal; bisher keine dynamische Beladung |
| delivery_mode, loading_time_s, unloading_time_s | operation | Bisher Metadaten, keine automatische Änderung der KIT-Servicezeiten |
| allowed_networks, parking_behavior | routing / operation | Explizite Verkehrsklasse nötig; keine automatische Umsetzung aller Parkregeln |
| charging.required, charging_speed_kw | powertrain / weitere Betriebsregeln | Ladeplanung und Batteriesimulation noch nicht implementiert |

`application=catalogOnly` speichert die Konfiguration ohne Verkehrszuordnung.
Für `sumoTraffic` müssen die Typen ausdrücklich den vorhandenen Wegen zugeordnet
werden. Ein importierter Fahrzeugtyp erzeugt keine zusätzlichen Fahrzeuge oder
Paketaufträge. logiTopp und MobileCity behandeln diese Fahrzeugkonfiguration
bislang als Metadaten; eine vollständige simulatorübergreifende Flottenanbindung
steht aus.

## Konkreter Fall: verspätete Heimkehr

Die unveränderte KIT-Strategie behandelt einen gewöhnlichen Aufenthalt zu Hause
über Nacht anders als eine Aktivität, die vollständig am selben Tag endet.
Beispiel im neuen Test:

- Geplante Heimkehr 18:00 Uhr, tatsächliche Ankunft 18:30 Uhr.
- Nächste Aktivität am Folgetag um 08:00 Uhr, hinterlegte Wegdauer 30 Minuten.
- Die Heimaktivität beginnt um 18:30 Uhr. Ihre Dauer wird auf 13 Stunden gesetzt.
- Sie endet damit um 07:30 Uhr; die nächste Abfahrt kann im Plan erhalten bleiben.

Das bedeutet nicht, dass die folgende Fahrt garantiert pünktlich endet: auch
sie kann länger dauern. Die Berechnung verwendet die hinterlegte Wegdauer der
nächsten Aktivität, keine Garantie der tatsächlichen Netzfahrzeit. Bei extremen
Verspätungen/Tagesgrenzenkonflikten können Aktivitäten entfallen. Feste Termine
und flexible Freizeit werden noch nicht durch eine eigene Prioritätslogik behandelt.
Test: `KitReschedulingTest.lateHomeArrivalShortensOvernightStayToPreserveNextPlannedDeparture`.

## Vollständige Haushalte und Rundungsabweichungen

Der Generator übernimmt jetzt immer die vollständige Haushaltskomposition aus
KIT-Pool, Zensus-gewichteter Auswahl oder Template. Fehlen in einer Zone noch
zwei Personen und die gezogene Vorlage enthält vier Mitglieder, bleiben alle
vier erhalten. Auch Kinder werden nicht zur Einhaltung einer Zielzahl entfernt.

Der Überhang wird bei der nächsten Zone berücksichtigt, damit kleine
Abweichungen sich nicht über viele Zellen aufsummieren. Einzelne Zonen können
leicht über oder unter ihrer Zielzahl liegen. `population/household-rounding.json`
weist Zielzahl, tatsächliche Personenzahl und Differenz je Zone und insgesamt aus.
MobileCity verwendet weiterhin die Quellbevölkerung; synthetische Alters- und
Pkw-Anteile werden auf diese Bevölkerung umgerechnet.

## Netzzeiten für feste Ziele: erster Schritt umgesetzt

Die Basisreisematrizen entstehen jetzt nach Attraktivitäten und vor Bevölkerung
und Aktivitäten. Feste Arbeits-/Bildungszonen verwenden die initiale Pkw-Matrix;
beobachtete Pendlerrelationen behalten Vorrang. Der neue Gewichtungsansatz lautet
`(Attraktivität + 1) / (1 + (Zeit_min / 20)^2)`.
Die 20 Minuten sind eine ausdrücklich gesetzte Skalierungsannahme, kein
empirisch geschätzter Parameter. Die Quelle der Matrix wird weiterhin protokolliert;
bestehende lokale Routing-Näherungen bleiben Näherungen. Unendliche Reisezeit
liefert Gewicht null; sind alle Ziele unerreichbar, bricht die Zuordnung verständlich ab.
Multimodale Zielzuordnung einschließlich GTFS und Kalibrierung des Zeitabfalls
bleiben offen. Das ist getrennt von den MNL-Koeffizienten der Verkehrsmittelwahl.

## Verständliche Beschriftung der Modusanalyse

MNL bezeichnet die Modellform der mobiTopp-Verkehrsmittelwahl, nicht ein zweites
Programm neben mobiTopp. Die Parameter kommen aus den mitgelieferten KIT-Rastatt-
Dateien, insbesondere `mode_choice_main_parameters-multi-nomial-logit.txt`.
Es gibt Konstanten sowie Koeffizienten für Kosten und Personeneigenschaften;
regionale Gültigkeit für Dortmund/Unna ist nicht durch die Datei allein belegt.
Optionale Moduskalibrierung kann Konstanten gegen Referenzanteile anpassen.

Für die Grafik:

- Titel: **Verkehrsmittelvergleich**.
- Eingang: **Personenwege aus mobiTopp**.
- Modellseite: **mobiTopp-Verkehrsmittelwahl (MNL)**.
- Alternative: **LLM-Schätzung** (aktuell nach Distanzklassen mit geschätzten Zeiten).
- Referenz: **MiD / regionale Erhebungen**, soweit vorhanden und vergleichbar.

Die Personenwege liegen im gemeinsamen mobiTopp/logiTopp-Lauf. Der bisherige
Dateiname mit logiTopp macht die Personen-Verkehrsmittelwahl nicht zu einem
separaten logiTopp-Modell. Der LLM-Vergleich verändert die simulierten Wege nicht.

## Rollen

actiTopp erzeugt Aktivitäten und Wochenpläne. mobiTopp verwendet diese für feste
und variable Ziele, Verkehrsmittelwahl und zeitliche Ausführung von Personenwegen;
die festen Arbeits-/Bildungsziele werden in unserer Pipeline vorab zugewiesen.
logiTopp erweitert den Lauf um Paketnachfrage, Zustellplanung und Logistikabläufe.
SUMO bildet die Fahrzeugwege auf Straßen ab und simuliert den Verkehrszustand.

Kurze Bulletpoints für die SUMO-Phase: **Straßenrouten erzeugen**, **Verkehr
simulieren**, **Fahrzeiten ermitteln**.


## GraphHopper, mobiTopp und SUMO: dieselbe Netzquelle, verschiedene Ebenen

GraphHopper und SUMO verwenden in einem Lauf dieselbe OSM-Netzquelle
(`osm/network.osm`). GraphHopper berechnet Straßenwege zwischen Zonenschwerpunkten.
Kanten stehen für Straßenabschnitte, Knoten für deren Verbindungen. Unser
`RoutingEngine` übernimmt daraus derzeit nur Entfernung und Reisezeit in die
Reisematrizen, nicht die Straßenfolge. Intrazonale Werte und Routingfehler können
weiterhin ausgewiesene Näherungen verwenden.

mobiTopp nutzt diese Matrizen für Entscheidungen und die zeitliche Ausführung von
Personenwegen. Die Straßen sind deshalb bereits indirekt enthalten. Das Modell
führt dabei keine einzelnen Fahrzeuge auf Fahrstreifen aus.

SUMO importiert dieselbe OSM-Quelle mit `netconvert` in ein eigenes Netz mit
Fahrstreifen, Kreuzungen und Verbindungen. `duarouter` berechnet für die konkreten
Start-/Zielpunkte der erzeugten Wege eine Folge von SUMO-Kanten. Diese kann vom
GraphHopper-Weg abweichen: Zonenschwerpunkte versus konkrete Punkte, unterschiedliche
Netzaufbereitung und Routingprofile sowie fehlende Übernahme der ursprünglichen
Straßenfolge. Es ist aktuell mehr als eine reine Formatkonvertierung. Anschließend
misst SUMO Reisezeiten unter der simulierten Verkehrsbelastung.

Eine direkte Übernahme von GraphHopper-Routen erfordert einen eigenen Adapter,
der die Straßenfolge über OSM-Bezüge auf SUMO-Kanten abbildet und dort prüft.
GraphHopper-Kanten-IDs lassen sich nicht unmittelbar als SUMO-Kanten-IDs verwenden.

## Aussagekraft des LLM-Vergleichs

Die Frage „Wie hätte ein LLM gewählt?“ beschreibt derzeit keinen individuellen
Gegenentwurf zu jedem Personenweg. Der Runner gruppiert vorhandene Personenwege
nach Entfernung und fragt das LLM nach Verkehrsmittelanteilen je Gruppe. Er
verwendet vereinfachte Zeitansätze, keinen vollständigen Haushaltskontext und
keine konkreten GraphHopper-/GTFS-Alternativen pro Person. Die bestehenden Wege
bleiben unverändert. Präzisere Bezeichnung ausschließlich für diesen Zweig:
**„Welche Verkehrsmittelanteile schätzt ein LLM?“**
Der gesamte Vergleich heißt **„Verkehrsmittelvergleich“** und umfasst
mobiTopp/MNL, die optionale LLM-Schätzung und empirische Referenzen wie MiD.

## Nächste Erweiterungen

[Routenkonsistenz, bestehendes und geplantes Zeitfeedback sowie Folienpunkte](20-erweiterungen-routenkonsistenz-und-zeitfeedback.md).
Es existiert bereits eine optionale SUMO-Rückkopplung über die Pkw-Matrix;
noch offen ist die Übernahme einzelner gemessener Ankünfte in den Aktivitätenablauf.
