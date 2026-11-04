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

## Öffentliche Lade- und Tankinfrastruktur: Bestandsprüfung und Integrationsplan (2026-10-20)

**Stand: Stationsbestand mit Karte umgesetzt; Lade-/Tankbetrieb offen.**
Im Studio aktiviert „Stationsbestand erfassen (experimentell)“ die unabhängige
Quellenaufnahme (`energyInfrastructure.enabled=false` als Default,
`useBnetza=true`, `useOsm=true`). Region speichern, Quellen wählen und neu
generieren; „Lade-/Tankstationen“ schaltet die Karte ein. Sie zeigt zunächst
nur laut Quelle öffentliche Stationen. Ungeklärter und beschränkter Zugang
kann ausdrücklich zugeschaltet werden. Quellenbericht und Popups nennen
Datenstand, Anschlüsse, Druckstufen und ungeprüfte Eigenschaften.

`EnergyInfrastructure` ergänzt den vorhandenen Registerimport mit Ladepunkt-/
Steckerdetails und einer eigenen OSM-`nwr`-Abfrage. OSM wird als regionaler
Eingabestand gespeichert; Upload und bewusste Erneuerung sind im Studio möglich.
Ein gespeicherter Stand wird bei Wiederholung wiederverwendet. Bei geänderter
Region muss er neu hochgeladen oder freigegeben werden. Quellenfehler bleiben
`FAILED` mit `energy/quality.json`; sie erzeugen keinen Ersatzbestand.
Artefaktvertrag und API: [docs/03](03-architektur.md#öffentlicher-stationsbestand-2026-10-20-experimentell).
Messungen und Prüfgrenzen: [docs/05](05-validierung.md#öffentlicher-stationsbestand-registerdetails-und-kartenprüfung-2026-10-20).

`MobileCityChargingSource` liest weiterhin einen
hashgeprüften BNetzA-Snapshot vom 01.09.2026 und ordnet öffentliche Ladepunkte
räumlich den MobileCity-Districts zu. Aktivierung:
`mobileCity.useRegionalCharging=true`, standardmäßig aus; eine optionale manuelle
Ladepunktdichte ist als Annahme/Fallback getrennt. Der lokale normalisierte
Bestand enthält 116.422 Ladeeinrichtungen mit 209.096 Ladepunkten. Das sind die
Zahlen dieses eingebundenen Snapshots, keine aktuelle Vollerfassung. Ausgabe:
`charging-points.csv`, `charging-provenance.json` und aggregierte MobileCity-Werte.

Eine Nutzung dieser Standorte zum Laden oder Tanken ist nicht integriert:
`LegacyExportGenerator` schreibt `chargingdata.csv` derzeit nur mit Kopfzeile.
`SumoVehicleTypes` exportiert Fahr-/Emissionsparameter, keine Batterie-Devices
oder Stationsstopps. Eine neue optionale externe Referenzbilanz führt Energie
über explizit verbundene Fahrtsegmente fort; der native Typvertrag weist weiterhin
`allocationBasis=existing-trip-segments` und `fleetStateSimulated=false` aus.
Der Fahrzeugvertrag kennt batterieelektrische Eigenschaften einschließlich
Steckern, aber bei Verbrennern nur `gasoline`/`diesel`; Gas- und H₂-Antriebe fehlen.
Der generische `OverpassClient.Poi`-Vertrag bewahrt keine OSM-Objekt-ID und
genügt deshalb nicht als dauerhafte Stationsidentität. Der neue Stationsimport
bewahrt Objektart, ID, Version und Zeitstempel gesondert; Mittelpunkte von
Flächen/Relationen sind ausdrücklich keine nachgewiesenen Zufahrten.

### Quellenentscheidung

| Energie / Quelle | Nutzbare Informationen und Zugang | Vorgesehene Rolle |
|---|---|---|
| Strom: **BNetzA-Ladesäulenregister** | CSV/XLSX; inzwischen auch offiziell dokumentierte öffentliche REST-Schnittstelle in JSON/XML mit täglichem Datenstand. Register enthält abgeschlossene Anzeigeverfahren, keine garantierte Vollerfassung. Lizenz CC BY 4.0, Quellenangabe Bundesnetzagentur.de. | Deutsche Hauptquelle; bestehenden Import wiederverwenden und für Einzelstandorte erweitern. API-Vertrag/Zugang vor Implementierung verifizieren; CSV bleibt nutzbar. |
| H₂: **Clean Hydrogen JU / H2-Stations** | Dokumentierte Export-API v2, persönlicher Bearer-Token; Stationsliste paginiert, statische Angaben und optionale Statusdaten. Anschluss-/Druckstufen, LD/HD-Eignung und Zugangs-/Fahrzeugbeschränkungen getrennt. CC BY 4.0 mit vorgegebener Quellenangabe; dokumentierte Sandbox bietet nur Beispieldaten. | Bevorzugte H₂-Quelle. Ohne produktiven Token zunächst Quellenadapter gegen Sandbox/Fixtures prüfen; keine Beispielstationen als reale Versorgung ausgeben. |
| CNG / LNG / LPG, ergänzend Strom/H₂: **OpenStreetMap** | `amenity=fuel`, `amenity=charging_station`, auch Ways/Relationen; Kraftstoff-Tags `fuel:cng`, `fuel:lng`, `fuel:lpg`, `fuel:h35`, `fuel:h70`. Detail-/Zugangsangaben können fehlen oder veraltet sein. ODbL, Attribution und Datenbankpflichten berücksichtigen. | Offene Ausgangsbasis für Gastankstellen und zusätzliche Zufahrts-/Steckerdetails. Fehlende Angaben bleiben unbekannt; keine Vollständigkeitszusage. |
| Gas: **gibgas / Betreiberbestände** | Spezialdaten zu europäischen CNG-Stationen und teilweise Betriebsstatus. Eine frei dokumentierte API samt Weiterverwendungslizenz konnte bei dieser Prüfung nicht bestätigt werden. | Optionale Qualitätsergänzung nach geklärtem Export-/Nutzungsrecht; kein undokumentiertes Website-Scraping als Produktabhängigkeit. |
| H₂: **H2.LIVE** | Öffentliches Stations-/Statusangebot, einschließlich 350-/700-bar- und Nutzfahrzeuginformationen; freie maschinelle Weiterverwendung nicht aus der Karte ableiten. | Optionaler Abgleich bzw. spätere Vereinbarung; für den ersten Adapter bietet H2-Stations den klareren dokumentierten Datenvertrag. |

Primärquellen, am 20.10.2026 geprüft:
[BNetzA-Register und Downloads](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/start.html),
[offizielle BNetzA-Schnittstellenbeschreibung](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Schnittstellen/start.html),
[BNetzA-Datennutzung](https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenkarte/start.html),
[H2-Stations API und Lizenz](https://docs.h2-stations.eu/for-data-users/),
[H2-Stations v2](https://docs.h2-stations.eu/for-data-users/api-v2/),
[CHJU als Auftraggeber](https://www.clean-hydrogen.europa.eu/get-involved/h2-stations-europes-trusted-hub-hydrogen-refuelling-station-data_en),
[OSM-Ladestationen](https://wiki.openstreetmap.org/wiki/Tag:amenity%3Dcharging_station),
[OSM-Kraftstoffarten](https://wiki.openstreetmap.org/wiki/Template:Fuel_types),
[OSM-Lizenz](https://www.openstreetmap.org/copyright),
[gibgas-Datenangebot](https://cngapp.gibgas.de/de_DE/app_hilfe?locale=de_DE),
[H2.LIVE](https://h2.live/app/).
Die Markttransparenzstelle für Kraftstoffe meldet E5/E10/Diesel und ersetzt
keinen Gas-/H₂-Stationsbestand: [Bundeskartellamt](https://www.bundeskartellamt.de/DE/Aufgaben/MarkttransparenzstelleFuerKraftstoffe/markttransparenzstellefuerkraftstoffe_node.html).

### Gemeinsamer Datenvertrag und weitere Ausbauziele

Der modellunabhängige Stationsbestand unterscheidet **Standort, Lade-/Tankpunkt
und Anschluss/Abgabemöglichkeit**. Drei Stecker können zu nur einem
gleichzeitig nutzbaren Ladepunkt gehören. Nennleistung einer gesamten
Ladeeinrichtung darf nicht ungeprüft jedem Stecker zugewiesen werden. Der heutige
BNetzA-Normalisierer behält Standort, Datum, Punktzahl, Einrichtungsleistung und
Typ. `prepare-energy-charging.py` ergänzt deshalb Anschlussdetails aus genau
demselben hashgeprüften Rohdatensatz. Gemeinsame Leistungsgrenzen und
Live-Verfügbarkeit bleiben unbekannt.

Weitere Ausbauziele des Vertrags: Herkunft je Attribut,
Standort-/Zufahrtskoordinate, Energieträger, Punkt-/Anschluss-ID, Steckertyp bzw.
Druckstufe, elektrische Leistung oder Abgaberate mit Einheit, gemeinsame
Leistungs-/Kapazitätsgrenze, Öffnungszeiten mit Zeitzone, öffentlicher oder
beschränkter Zugang, Pkw-/Lkw-Eignung und bekannte Maß-/Gewichtsgrenzen.
„In Betrieb“ im Register ist keine Live-Freimeldung eines Ladepunkts.
Bestandsdatum und zeitgestempelter Betriebsstatus bleiben getrennt; unbekannte
Verfügbarkeit ist weder „frei“ noch „geschlossen“. CNG, LNG, LPG und H₂ sind
getrennte Kompatibilitätsklassen; H₂-Druck allein beweist keine HD-Eignung.

Jeder Lauf verwendet einen gespeicherten Quellenstand mit Abrufzeit, Datenzeit,
SHA-256, Lizenz, regionaler Filterung und Qualitätsbericht. Mehrfachmeldungen
werden über IDs und nachvollziehbare Kandidatenabgleiche behandelt; nahe Punkte
unterschiedlicher Betreiber sind nicht automatisch Dubletten. OSM- und
Registerbeiträge behalten getrennte Herkunft. Eine leere oder gescheiterte
Quelle erzeugt keinen synthetischen Versorgungsbestand und keine behauptete
Nullversorgung. Daten für einen erweiterten Netzkorridor dürfen ausgewertet
werden; das ist noch offen. Der aktuelle Import bleibt innerhalb des gezeichneten
Rechtecks, bei OSM-Flächen anhand ihres repräsentativen Mittelpunkts. Ein
heutiger Bestand beweist keine vollständige historische Versorgung;
Szenariojahr und tatsächlicher Quellenstand müssen getrennt ausgewiesen werden.

### Konkrete Umsetzungsreihenfolge

1. **Stationsimport und Karte — ✅ erste Stufe umgesetzt (2026-10-20).**
   BNetzA-Anschlussdetails und OSM `nwr` mit stabilen Identitäten, Quellenständen,
   Zugangsfilter und Qualitätsbericht sind vorhanden. Quellenmeldungen werden
   erhalten; räumliche Nähe bzw. identische EVSE-Referenz markieren lediglich
   mögliche Mehrfachmeldungen. Ladepunkte werden ausschließlich aus BNetzA
   gezählt. MobileCity verwendet weiterhin denselben Registerbasisbestand;
   seine Aggregation wird noch nicht aus dem neuen GeoJSON abgeleitet.
   Offen bleiben H₂-Register-Adapter/API-Zugang und diese gemeinsame Ableitung.
2. **Erreichbarkeit und Kompatibilität — ✅ erste Prüfstufe (2026-10-20).**
   `energyInfrastructure.verifyNetworkAccess` ordnet gerichtete Kanten/Spuren
   zu und prüft native Zu-/Abfahrtsrouten separat von der Verkehrsnachfrage.
   Bekannte Maß-/Zugangsregeln entlang der getesteten Pfade können die Zuordnung
   verhindern; unbekannte Regeln und HGV-Eignung bleiben prüfpflichtig. Originalort
   und Projektion bleiben getrennt, Steckerübereinstimmung ist keine vollständige
   Fahrzeugeignungsfreigabe. Offen sind reale Einfahrtsgeometrien, globale
   Erreichbarkeit, vollständige Bedienbarkeit und zertifizierte nutzbare Kapazität.
3. **Fahrzeugidentitäten/Energie — ✅ Referenzbilanz (2026-10-20).**
   `vehicleOperations.enabled` verbindet bestehende Quellfahrten explizit mit
   `initialVehicles`. Die native Ausgabe erhält stabile physische IDs; ein
   externer Zustand führt kWh bzw. Liter mit deklariertem Verbrauch je Kilometer
   und tatsächlicher SUMO-Distanz fort. Unbekannte Werte, Orts-/Zeitbrüche,
   Teleports und Defizite werden sichtbar. Ohne `chainTrips` führt SUMO getrennte
   Fahrtsegmente aus. Mit `vehicleOperations.chainTrips=true` sind seit 21.10.2026
   native Fahrzeugketten mit Halten, Rückfahrten und Verspätungsfortschreibung
   implementiert. Die bisherigen Verbrauchswerte werden dabei nicht auf einzelne
   Etappen geschätzt; die Etappenenergie bleibt unbekannt. Energiebegrenzung und
   Disposition bleiben offen. Gas-/H₂-Antriebsverträge
   benötigen eigene Mengen/Einheiten; Liter werden nicht umgedeutet. Private
   Heim-/Depotladung bleibt eine gesonderte Annahme.
   [Verträge und Bedienung](03-architektur.md#stations-netzprüfung-und-fahrzeugzustände-2026-10-20-erste-stufe).
4. **Batterieelektrischen Kleinfall vollständig beweisen.** Zuerst ein Fahrzeug,
   eine Station und mehrere zusammenhängende Fahrten: Energieverbrauch,
   Ladebedarf, kompatibler Halt, Leistungsbegrenzung, Ladezeit und Folgeankunft.
   Danach belegte/gesperrte/unerreichbare Station, zwei Fahrzeuge an einem Punkt,
   fehlender Anschluss und Tagesende. Erst danach regionale Ladeplanung aktivieren.
5. **Gas/H₂-Tankstopps ergänzen.** Kompatibilität und Abgabemengen separat testen;
   elektrische Ladezeiten oder Verbrauchsfaktoren nicht übernehmen. Tank-/Lade-
   stopps müssen Tourdauer, Folgestarts und ggf. Warteschlangen beeinflussen,
   ohne Transportnachfrage oder Halte doppelt zu zählen. Unbediente Nachfrage,
   Energiemangel und Rettung/Teleport getrennt bilanzieren.

**SUMO-Anschluss:** `device.battery`, `chargingStation`, Batterie-/Ladeausgaben und
`stationfinder` sind native Bausteine für einen späteren BEV-Adapter. SUMO fährt
standardmäßig auch bei leerer Batterie weiter; Emissionsklasse oder Stationspunkt
allein erzeugen daher keine Reichweitenrestriktion. `stationfinder` wird als
Batterielogik dokumentiert und ist kein nachgewiesener CNG-/H₂-Tankplaner.
`chargingStation chargeType=fuel` und Kraftstofftracking existieren, ersetzen
aber keinen projektspezifischen Einheiten-/Kompatibilitätsnachweis.
Quellen: [SUMO Electric](https://sumo.dlr.de/docs/Models/Electric.html),
[SUMO Stationfinder](https://sumo.dlr.de/docs/Simulation/Stationfinder.html).

Der **Stationsbestand, lokale Netzprüfung und Referenzbilanz sind implementiert**;
native Fahrzeugketten sind als abschaltbare erste Betriebsstufe ergänzt.
Schritte 4–5 bleiben offen und sind nach Nutzerentscheidung vom 21.10.2026
zurückgestellt. Vorrang haben die Güterverkehrsnachfrage, vollständige Touren,
Fahrzeugzuordnung und ihre gemeinsame Ausführung mit dem übrigen Verkehr.
Es gibt noch keine operative Lade-/Tankplanung;
ein eigenständiges Gebäude-/Betriebseinfahrtsmodell wird dafür nicht vorausgesetzt.

Die Bedienung der Fahrzeugketten, Artefakte und Modellgrenzen stehen im
[Architekturvertrag der nativen Fahrtketten](03-architektur.md#native-fahrzeugketten-für-den-güterverkehr-2026-10-21).
Anbindung innerhalb der vorhandenen Quellen-/Export-/SUMO-Stufen bevorzugen;
neue Nebenläufigkeit oder eine automatische Änderung der Nachfrage ist nicht Teil
dieses Vorschlags. Neue operative Funktionen bleiben bis zum nativen Nachweis opt-in.
