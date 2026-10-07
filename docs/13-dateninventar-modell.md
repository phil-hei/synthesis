# Welche Daten fließen in das SyntheSis Mobility & Logistics Model ein?

**Stand: 30. September 2026.** Übersicht zur [Phasengrafik und den Vertiefungsfolien](12-praesentationsleitfaden.md). Sie beschreibt den aktuellen Code, keine Zusage, dass jede Quelle für jedes Gebiet verfügbar ist.

**Nummerierungshinweis:** Die Phasen 1–8 beziehen sich auf die aktuellen fachlichen [Vertiefungsfolien](12-praesentationsleitfaden.md). Davon getrennt beschreibt die [technische Pipeline](14-detailablauf-und-wochenplan.md) 15 Verarbeitungsschritte. Das Bezugsjahr bleibt hier als technische Einstellung dokumentiert; es entfällt als Stichpunkt auf der Szenariofolie.

## 1. Gesamtüberblick für eine Folie

| Datenblock | Vom Benutzer modelliert | Externe Grundlage | Daraus erzeugt |
|---|---|---|---|
| **Raum** | Gebiet, Zellgröße, Zonenlimit | OSM, Bevölkerungsraster | Verkehrszellen mit IDs und Geometrien |
| **Bevölkerung** | Einwohnerzahl optional, Stichprobe, Pkw-Ausstattung | Zensus; KIT-Haushaltspool; optional regionale Strukturen | Haushalte, Personen, Pkw, Wohn- und feste Zielorte |
| **Standorte** | Ergänzende Vorgaben im Szenario | OSM-Einrichtungen; optional Pendler-/Arbeitsplatzdaten | Zielattraktivitäten und Arbeits-/Bildungszuordnungen |
| **Verhalten** | Verhaltensquelle, optionale Szenarioannahmen | actiTopp-Parameter; alternative GTA-/Vorlagenmodelle | Aktivitätenpläne; später Wege und Moduswahl |
| **Verkehrsangebot** | Routingoptionen, Fahrzeugkonfiguration | OSM-Straßen; GTFS-Fahrpläne | Zonenmatrizen; SUMO-Netz und ÖPNV-Angebot; MobileCity-Netze |
| **Logistik** | Depots, Dienstanteile, Paketraten, Zustellanteile | OSM-Paketstationen und Depotkandidaten | Logistikeingaben; später Paketaufträge und Touren |
| **MobileCity-Ergänzungen** | E-Pkw-/Lade-/Sharing-/Umlandannahmen | KBA, BKG, BNetzA, GTFS, TRAMIGO-Referenz | Regionale Flotten-, Infrastruktur- und Modellparameter |
| **Qualität** | Bezugsjahr, Zufallsstartwert, Kalibrierziele | Quellenstände; MiD-Vergleichswerte | Manifest, Herkunftsberichte, Kennzahlen und Prüfstatus |

**Drei wichtige Trennungen:** Ein Bevölkerungsraster enthält keine echten Haushalte; ein GTFS-Fahrplan enthält keine gemessene Verkehrsnachfrage; eine Paketrate beschreibt noch keine konkreten Paketaufträge.

## 2. Benutzereingaben und Szenarioannahmen

Die Angaben liegen in der Szenariokonfiguration, in Uploads oder in Laufzeit-/Profilkonfigurationen. Nicht jede technische Einstellung ist ein eigenes sichtbares Studio-Eingabefeld.

| Eingabe | Beispiel / Inhalt | Verwendung und Phase | Aktueller Stand |
|---|---|---|---|
| Untersuchungsgebiet | Rechteckiger Kartenausschnitt | 1–2: räumliche Abgrenzung | Vorhanden. |
| Bezugsjahr | Jahr des Szenarios | 1, 4, MobileCity: gemeinsame zeitliche Einordnung | Vorhanden; Quellen können trotzdem andere Bezugsjahre haben. |
| Räumliche Auflösung | 100 m, 500 m, 1 km | 2: Verkehrszellen; Detailtiefe und Rechenaufwand | Neue Szenarien EPSG:3035; Zonenlimit kann Vergröberung auslösen. |
| Zonenlimit | Gewünschte Obergrenze | 2, 6: begrenzt quadratisch wachsende Matrizen | Vorhanden, im erweiterten UI-Abschnitt. |
| Gesamtbevölkerung | Explizite Einwohnerzahl | 3–4: überschreibt abgeleitete Gesamtzahl | Raster kann weiterhin räumliche Gewichte liefern. |
| Ersatzdichte | Einwohner pro km² | 3: Schätzung ohne bessere Bevölkerungsquelle | Modellannahme, kein Beobachtungswert. |
| Stichprobenanteil | Standard 100 % | 4, 7: Zahl synthetischer Personen | Reduktion wirkt auf Nachfrage; keine automatische Vollverkehrsskalierung. |
| Zufallsstartwert | Seed | 4–7: wiederholbare stochastische Generierung | Vorhanden; Reproduzierbarkeit braucht zusätzlich gleiche Quellen und Laufzeit. |
| Pkw-Ausstattung | Pkw je Haushalt | 4: Pkw-Bestand | Modellvorgabe; regionale BEV-Anteile sind davon getrennt. |
| Bevölkerungsquellenwahl | KIT-Pool, optionale Zensus-Nachgewichtung | 4: Haushaltszusammensetzung | Vorhanden; Nachgewichtung optional. |
| Aktivitätenmodell | actiTopp, GTA oder Vorlagen | 5: Wochenaktivitäten | actiTopp Standard; alternative Methoden gesondert kennzeichnen. |
| GTA-Szenariotext | Z. B. „Innenstadt autofrei“ | 5: experimenteller Verhaltenskontext | Ändert nicht automatisch das Straßennetz oder alle anderen Modelle. |
| Depots | Koordinaten, Beschäftigte, Anteil, Fahrzeugtyp | 6–7: Logistikstandorte und Bedienung | Explizite Depots möglich; automatische Ersatzstandorte noch vorhanden. |
| Paketdienste | Namen und Anteile | 6–7: Anbieterzuordnung | Vorhanden; Standardanteile sind Modellannahmen. |
| Paketnachfrage | Pakete je Person/Woche, Streuung, Maximum | 6–7: private Paketaufträge | Vorhanden; keine vollständige betriebliche Paketnachfrage. |
| Zustellorte | Anteile Zuhause, Arbeit, Paketstation | 6–7: Zustellzielwahl | Vorhanden. |
| Retouren | Aktivierung, Quote, Abholanteile | 6–7: zusätzliche private Abholaufträge | Experimentell; keine universelle Retourenmodellierung. |
| Fahrzeugkonfiguration | Fahrzeugtypen und explizite Zuordnung | 8: insbesondere SUMO | Wirkt nicht automatisch auf Haushalts-Pkw-Besitz oder Depotflotten. |
| Zielmodelle | logiTopp, MobileCity, SUMO | Nach Vorbereitung: Ausführung auswählen | SUMO setzt logiTopp voraus. |
| Kalibrierziele | Zielanteile der Verkehrsmittel, experimentelle Optionen | 7–8: wiederholte Nachfrageberechnung | Optional; Standardwerte kein regionaler Messnachweis. |
| SUMO-Laufauswahl | Tag, explizites Testlimit, Feedback | 8: Umfang der Verkehrssimulation | Kein standardmäßiges Mengenlimit; Tag und unterstützte Modi bleiben relevant. |
| MobileCity-Flotte/Infrastruktur | BEV-Anteil, Ladepunkte, Sharing-Fahrzeuge/-Nutzer | Export nach 6 | Quellen oder explizite Annahmen; nicht flächendeckend beobachtet. |
| MobileCity-Umland | Puffer, Bevölkerung, zweckspezifische Zuflussanteile | Export nach 6 | Bevölkerungsbasis und Verkehrsanteile sind getrennte Größen. |
| MobileCity-Modellprofil | Referenzvorlage, Export-/Prüfoptionen, Ersatzannahmen | Export nach 6 | Vervollständigt das Zielmodell, keine automatische regionale Kalibrierung. |

**Uploads sind ein Zugangsweg, keine eigene Datenqualität:** Ein vom Benutzer hochgeladener amtlicher Datensatz bleibt eine externe Quelle. Ein manuell eingetragener Wert bleibt eine Annahme, solange kein Nachweis zugeordnet ist.

## 3. Externe Daten und mitgelieferte Modellgrundlagen

| Quelle | Welche Daten? | Wofür / wann? | Status und Grenzen |
|---|---|---|---|
| **OSM-Kartenkacheln** | Sichtbares Kartenbild | 1: Gebiet auswählen | Nur Anzeige. Aus Kartenbildern werden keine Straßen oder Gebäude extrahiert. |
| **OSM-Ortsdaten** | Geschäfte, Schulen, Einrichtungen, Siedlungen, Paketstationen und Logistikhinweise | 3: Attraktivitäten; 6: Logistikstandorte | Upload/Snapshot bzw. Overpass. Tags liefern Hinweise, keine nachgewiesenen Kapazitäten oder Beschäftigtenzahlen. |
| **OSM-Straßennetz** | Straßenverläufe, Knoten, Richtungen, Zugangs-/Geschwindigkeitsangaben | 6: GraphHopper; 8: SUMO-Netz | Netzdaten sind zusätzlich zu POIs erforderlich. Beide Werkzeuge erstellen eigene Netzrepräsentationen. |
| **OSM-Gebäude** | Gebäudegeometrien/-nutzung, soweit vorhanden | Geplant: plausible Wohnpunkte mit Straßenzugang | Keine durchgehende heutige Gebäudezuordnung der Haushalte. Nicht als bereits implementierte Wohnadressquelle darstellen. |
| **Zensus 2022: Bevölkerungsraster** | Einwohner je Rasterzelle | 3–4: Gesamtbevölkerung und räumliche Verteilung | Angebunden. Quellenjahr und fehlende/geheim gehaltene Werte beachten. |
| **Hochgeladenes Bevölkerungsraster** | Zensus-/INSPIRE-Kennungen, EPSG-Mittelpunkte oder WGS84-Punkte plus Bevölkerung | 3: alternative Verteilung/Gesamtzahl | Bekannte grobe 1–10-km-Zellen gleichmäßig auf 100-m-Zuordnungspunkte verteilt; unbekannte Zellgröße bleibt punktbasiert. |
| **Regionale Zensus-Strukturen** | Altersgruppen und Haushaltsgrößen | 4: Nachgewichtung synthetischer Haushalte | Optional. Keine individuellen Haushalte oder Aktivitäten aus Zensus rekonstruiert. |
| **KIT-Haushaltspool** | Synthetische Haushaltszusammensetzung, Alter, Geschlecht | 4: Haushalte ziehen | Gebündeltes Marne-Beispiel, keine deutschlandweit repräsentativen Mikrodaten. Andere Merkmale entstehen zusätzlich. |
| **Pendler-/Arbeitsplatzdateien** | Pendlerbeziehungen und Arbeitsplatz-Randsummen | 3–4: Arbeitsziele und Außenzonen | Optionale Dateien `commuters.csv`, `workplace-margins.csv`; keine vollständige automatische deutschlandweite Pendleranbindung. |
| **actiTopp-Modellparameter** | Regeln/Koeffizienten für Aktivitäten | 5: Wochenplanung | Mit dem KIT-Modell übernommene Verhaltensgrundlage, keine neue lokale Erhebung. |
| **mobiTopp/logiTopp-Parameter** | Ziel-/Moduswahl und Logistikverhalten | 7: simulierte Nachfrage | Modellparameter; regionale Gültigkeit muss fachlich geprüft werden. |
| **GTFS-Fahrplan** | Haltestellen, Linien, Fahrten, Haltezeiten und Betriebstage | MobileCity: Verkehrsangebot; 8: ÖPNV-Fahrten, besonders Bus | Upload oder konfigurierter regionaler Feed. **Noch keine GTFS-basierte gemeinsame Reisezeitmatrix.** GTFS liefert weder Auslastungen noch alle Fahrzeugeigenschaften. |
| **KBA-Fahrzeugdaten** | Regionale Pkw-/Antriebsinformationen, insbesondere BEV-Anteil | MobileCity: regionale Flottenzusammensetzung | Optionaler Referenzstand. BEV = rein batterieelektrischer Pkw; nicht mit allen elektrifizierten Antrieben gleichsetzen. |
| **BKG-Gemeindegrenzen** | Verwaltungsgeometrien und Gebietsschlüssel | MobileCity: KBA-Regionaldaten räumlich zuordnen | Optional. Gemeindegrenzen ersetzen nicht das Modellraster. |
| **BNetzA-Ladesäulenregister** | Registrierte öffentliche Ladepunkte und Standortangaben | MobileCity: Ladeinfrastruktur | Optional. Keine vollständige Erfassung privater Ladepunkte. |
| **Zensus im Umlandpuffer** | Wohnbevölkerung außerhalb des Ausschnitts | MobileCity: Umlandbasis | Optional. Einwohnerzahlen allein sind keine gemessenen Einpendlerströme. |
| **TRAMIGO-Beispielkonfiguration** | Technische, ökonomische und weitere Pflichtparameter | MobileCity: vollständige Konfiguration | Lokal aufbereitetes Profil `tramigo-v2`; übernommene Werte sind Referenzannahmen für ein anderes Gebiet. |
| **MiD-Zielwerte** | Verkehrsmittelanteile und Vergleichsgrößen | Optionale Kalibrierung/Auswertung | Mitgelieferte Referenzen bzw. editierbare Ziele; keine neue Live-Erhebung je Gebiet. |

Abkürzungen: **GTFS** = General Transit Feed Specification; **KBA** = Kraftfahrt-Bundesamt; **BKG** = Bundesamt für Kartographie und Geodäsie; **BNetzA** = Bundesnetzagentur; **MiD** = Mobilität in Deutschland. EPSG:3035 ist ein Koordinatensystem und INSPIRE eine Standardisierung der Quelldaten, keine zusätzlichen Bevölkerungsquellen.

## 4. Im Modell erzeugte Daten

| Erzeugt in Phase | Ergebnis | Grundlage | Spätere Verwendung |
|---|---|---|---|
| 2 | Verkehrszellen, IDs, Geometrien | Gebiet und Auflösung | Gemeinsamer räumlicher Schlüssel in allen Folgephasen |
| 3 | Einwohner je Zone | Bevölkerungsquelle und ggf. manuelle Gesamtzahl | Haushaltszahl, Bevölkerung, besuchsbezogene Attraktivität |
| 3 | Attraktivitäten | OSM-Tags, Einwohner und Modellgewichte | Wahrscheinlichkeiten der Zielwahl; keine gemessenen Besucherzahlen |
| 4 | Haushalte, Personen, Pkw | Synthese, Pool, regionale Gewichte und Nutzerannahmen | Aktivitäten und Nachfrage; MobileCity-Aggregate |
| 4 | Wohnpunkte, feste Arbeits-/Bildungsziele | Modellregeln, Zonen, Attraktivitäten und optionale Pendlerdaten | Aktivitäten-/Nachfragekontext; derzeit keine echten Adressen |
| 5 | Wochenpläne / `activity.csv` | actiTopp, GTA oder Vorlagen | Nachfragesimulation; heutige MobileCity-Wegehäufigkeiten |
| 6 | Distanz-, Zeit- und Kostenmatrizen | OSM/GraphHopper plus Ersatz-/Kostenannahmen | Erreichbarkeit für Ziel- und Moduswahl |
| 6 | Logistikkonfiguration | Depots, Dienste, Stationen, Paketraten | Erzeugung und Zustellung privater Pakete |
| Nach 6 | MobileCity-Konfiguration | Aggregierte vorbereitete Daten plus externe Ergänzungen/Profil | MobileCity-Import und optionaler lokaler ConfigTool-Test |
| 7 | Personenwege / Teilstrecken | logiTopp-Simulation | `demand/`, KPIs, SUMO |
| 7 | Zonen- und OD-Ströme in 15-Minuten-Intervallen | Aggregation simulierter Personenwege | Zeitprofile, Karten, weitere Adapter |
| 7 | Paketaufträge, Touren und Flottenereignisse | logiTopp-Logistikmodell | Logistikauswertung und Lieferverkehr in SUMO |
| 8 | SUMO-Netz und Straßenrouten | OSM-Netz und simulierte Nachfrage | Verkehrsablauf auf konkreten Straßen |
| 8 | Fahrzeiten, Belastungen, Kennzahlen | SUMO- und Nachfrageergebnisse | Auswertung; optionales Feedback auf Pkw-Zeitmatrix |
| Begleitend | Manifest, Quellen-/Prüfberichte | Konfiguration, Quellenstände, ausgeführte Prüfungen | Nachvollziehbarkeit; ein Manifest allein bestätigt keine erfolgreiche Simulation |

## 5. Beispiel: welche Information kommt woher?

**Einwohner und Raster:** Der Benutzer wählt 500-m-Zellen. Eine externe 1-km-Zelle enthält 100 Einwohner. Bei vollständiger Abdeckung entstehen vier Modellzellen mit je 25 Einwohnern. Die Verteilung innerhalb der Quellzelle ist unsere gleichmäßige Annahme.

**Person und Einkauf:** Die Synthese zieht einen Haushalt und modelliert eine Person mit Pkw. actiTopp erzeugt einen Einkaufsanlass. OSM-Geschäfte erhöhen Zielattraktivitäten; logiTopp wählt Zielzone und Verkehrsmittel. Es wird nicht zwingend ein bestimmter realer Supermarkt gewählt.

**Paket:** Der Benutzer setzt eine erwartete Paketrate und Zustellanteile. Daraus erzeugt logiTopp einzelne private Paketaufträge und bündelt sie in Touren. Das Paket ist kein Einkaufsweg der Person.

**ÖPNV:** Ein GTFS-Feed liefert Linien und Abfahrten. Heute nutzt ihn die Pipeline für MobileCity und SUMO. Für die gemeinsame Moduswahlmatrix werden ÖPNV-Zeiten noch aus Pkw-Zeiten geschätzt; Fahrplanrouting ist eine offene Erweiterung.

**E-Pkw:** Der Benutzer aktiviert regionale Flottendaten. KBA-Daten liefern einen BEV-Anteil, BKG-Grenzen helfen bei der Zuordnung. MobileCity kombiniert ihn mit dem generierten Pkw-Bestand. Damit ist nicht automatisch jedem logiTopp-Pkw ein regional korrekt kalibrierter Antrieb zugewiesen.

## 6. Wichtige Lücken für die Präsentation

- Noch keine durchgängige Zuordnung der Wohnpunkte zu realen Gebäuden mit Straßenzugang.
- Gemeinsame ÖPNV-Zeitmatrix noch nicht fahrplanbasiert.
- Tourstoppfolge derzeit geometrisch; gemeinsame Straßenroutingnutzung für Touren offen.
- Reale Depotfunktion und Kapazitäten nicht allein durch OSM-Tags nachgewiesen.
- Betriebliche Paketnachfrage und vollständiger Außen-/Durchgangsverkehr nicht automatisch abgedeckt.
- Einige Kosten-, Technologie- und Zukunftsparameter bleiben Referenzannahmen.
- Ein durchgängig versionierter gemeinsamer Modellvertrag und die MobileCity-Nachfrageübergabe sind noch offen.

## 7. Nachvollziehbare Grundlagen im Projekt

Die Übersicht wurde mit [Scenario](../backend/src/main/java/de/synthesis/scenario/Scenario.java), [GenerationService](../backend/src/main/java/de/synthesis/generation/GenerationService.java) und der [ausführlichen Pipeline-Erklärung](10-modellierungs-generierungspipeline.md) abgeglichen. Letztere verlinkt die konkreten Parser, Generatoren und Zielmodelladapter. Diese Bestandsaufnahme ist keine neue Prüfung der Aktualität oder Verfügbarkeit externer Datenportale.
