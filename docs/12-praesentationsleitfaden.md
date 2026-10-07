# Folieninhalte: SyntheSis in acht fachlichen Phasen

**Überarbeitet nach den jüngsten Rückmeldungen.** Dies sind Inhalte für selbst erstellte Folien, keine Foliendateien. Kurze Titel, Tätigkeiten als Bulletpoints, Werkzeuge separat. Das Bezugsjahr entfällt als Folienpunkt; seine technische Verwendung im Modell bleibt bestehen.

Die acht fachlichen Phasen dienen der Präsentation. Die [detaillierte technische Pipeline](14-detailablauf-und-wochenplan.md) behält ihre 15 Schrittnummern. Beide Ebenen dürfen auf einer Folie nicht mit derselben Nummerierung vermischt werden. Die bisherigen Acht-Phasen-Grafiken enthalten ältere Beschriftungen; für neue Folien gelten die Texte dieses Dokuments.

## Überblicksfolie

**Szenarioauswahl → Zonierung → Datenintegration → Bevölkerungsmodell → Aktivitätenplanung → Netz & Logistik → Nachfrage → Verkehrsablauf**

Phasen 1–6 bilden die fachliche Grundlage des **SyntheSis Mobility & Logistics Model**. Phase 7 erzeugt Personen- und Paketnachfrage. Phase 8 berechnet deren Ablauf im Straßennetz. MobileCity bekommt heute einen eigenen Konfigurationsexport aus den vorbereiteten Eingaben nach Phase 6; die späteren logiTopp-Einzelwege werden noch nicht übernommen.

## Folie 1 · Szenarioauswahl

**Kernaussage:** Das Untersuchungsgebiet und der Umfang der Modellierung werden festgelegt.

- Untersuchungsgebiet auswählen.
- Räumliche Auflösung festlegen.
- Zielmodelle auswählen.

**Ergebnis:** Gebiet und Einstellungen für die Generierung.

**Bedienung:** Karte und Szenariokonfiguration im Studio.

**Sprechhinweis:** Bevölkerungsvorgaben gehören zu Phase 4, Logistikannahmen zu Phase 6. „Zielmodelle“ meint logiTopp, MobileCity und SUMO, keine Fahrtziele. SUMO setzt die logiTopp-Nachfrage voraus.

## Folie 2 · Zonierung

**Kernaussage:** Verkehrszellen schaffen einen gemeinsamen räumlichen Bezug für alle Folgeprozesse.

- Untersuchungsgebiet in Verkehrszellen aufteilen.
- Eindeutige Zellgrenzen und Kennungen vergeben.
- Zellgröße an das Zonenlimit anpassen.

**Ergebnis:** Verkehrszellen mit Geometrien und gemeinsamen IDs.

**Räumlicher Standard:** EPSG:3035; INSPIRE-Quelldaten werden im Hintergrund zugeordnet.

**Beispiel:** Eine Zone verbindet ihre Einwohner, Aktivitätsorte und Erreichbarkeitswerte über dieselbe ID. Kleinere Zellen erhöhen das Detail, aber auch die Zahl der zu berechnenden Zonenpaare.

**Sprechhinweis:** Die räumliche Auflösung wird in Phase 1 gewünscht und hier tatsächlich umgesetzt. Die Datenwerte werden anschließend zugeordnet; Zonierung und Datenintegration sind unterschiedliche Tätigkeiten.

## Folie 3 · Datenintegration

**Kernaussage:** Externe Daten und dokumentierte Annahmen werden für das Gebiet aufbereitet und miteinander verbunden.

- Datenquellen einlesen und auf das Gebiet beziehen.
- Räumliche Bezüge und Kennungen zusammenführen.
- Einwohnerverteilung und Zielattraktivitäten ableiten.
- Quellenstände und Ersatzannahmen dokumentieren.

**Ergebnis:** Räumlich zugeordnete Daten als Grundlage der weiteren Modellierung.

### Datenübersicht für die Folie

Diese Übersicht umfasst die im gesamten Ablauf verwendeten Daten. Einige Quellen werden erst später gelesen bzw. nur für bestimmte Zielmodelle eingesetzt. Phase 3 ist die passende Stelle, um sie im Vortrag gemeinsam vorzustellen; technisch findet nicht jede Integration in diesem einen Schritt statt.

| Datenbereich | Quelle / Vorgabe | Enthaltene Informationen | Verwendung |
|---|---|---|---|
| **Raum und Standorte** | OSM | Straßen, Einrichtungen, Geschäfte, Schulen, Siedlungen, Paketstationen und Depotkandidaten | Aktivitätsorte, Routing und Logistik |
| **Einwohnerverteilung** | Zensus 2022 oder hochgeladenes Bevölkerungsraster | Einwohnerzahlen je Rasterzelle | Bevölkerung je Modellzone |
| **Alters-/Haushaltsstruktur** | Regionale Zensus-Daten, optional | Altersgruppen und Haushaltsgrößen | Regionale Nachgewichtung der Synthese |
| **Haushaltszusammensetzung** | Mitgelieferter KIT-Haushaltspool | Synthetische Haushalte, Alter und Geschlecht | Haushalte und Personen erzeugen |
| **Pendler und Arbeitsplätze** | Optionale Pendler-/Arbeitsplatzdateien | Start-Ziel-Beziehungen und Arbeitsplatz-Randsummen | Arbeitsziele und Außenzonen |
| **ÖPNV-Angebot** | GTFS | Haltestellen, Linien, Fahrten, Haltezeiten und Betriebstage | Heute MobileCity und SUMO |
| **Pkw-Antriebe** | KBA, optional | Regionale Fahrzeugdaten, insbesondere Anteil batterieelektrischer Pkw | Regionaler MobileCity-Export |
| **Verwaltungsgebiete** | BKG, optional | Gemeindegrenzen und Gebietsschlüssel | Räumliche Zuordnung regionaler Fahrzeugdaten |
| **Ladeinfrastruktur** | BNetzA, optional | Öffentliche Ladepunkte und Standorte | Regionaler MobileCity-Export |
| **Umland** | Zensus-Puffer oder Nutzervorgaben | Bevölkerung außerhalb des Gebiets; angenommene Zuflussanteile | MobileCity-Umlandmodell |
| **Verhaltensparameter** | actiTopp und mobiTopp/logiTopp | Aktivitäts-, Zielwahl-, Moduswahl- und Logistikparameter | Aktivitäten und Nachfrage |
| **Vergleichswerte** | MiD bzw. vorgegebene Kalibrierziele | Verkehrsmittelanteile und weitere Vergleichsgrößen | Optionale Kalibrierung und Analyse |
| **MobileCity-Parameter** | TRAMIGO-Referenzkonfiguration | Technische, ökonomische und weitere Pflichtparameter | Vollständiges MobileCity-Modell |
| **Szenarioannahmen** | Benutzer / Konfiguration | Bevölkerung, Pkw, Depots, Dienste, Paketraten, Zustellanteile, Fahrzeuge, Sharing und weitere Annahmen | Varianten und Ergänzung fehlender Beobachtungen |

### Darstellung auf einer einzelnen Folie

Die Tabelle ist die vollständige Inhaltsgrundlage. Für eine gut lesbare Folie sechs Themenfelder verwenden:

1. **Raum & Standorte:** OSM, Straßen, Einrichtungen, Logistikstandorte.
2. **Bevölkerung:** Zensus, Alters-/Haushaltsstrukturen, KIT-Pool.
3. **Mobilität:** Pendler, Arbeitsplätze, GTFS-Fahrpläne.
4. **Fahrzeuge & Infrastruktur:** KBA, BKG, BNetzA.
5. **Modellgrundlagen:** Verhaltensparameter, MiD, TRAMIGO-Referenz.
6. **Nutzervorgaben:** Bevölkerung, Pkw, Depots, Paketraten und weitere Szenarioannahmen.

**Kleine Fußzeile:** „Quellen werden abhängig vom Gebiet und Zielmodell genutzt. Annahmen bleiben von Beobachtungen unterscheidbar.“

### Einordnung für den Vortrag

- GTFS liefert Fahrpläne, keine beobachtete Nachfrage. Die gemeinsame ÖPNV-Reisezeitmatrix ist noch nicht GTFS-basiert.
- KBA, BKG und BNetzA dienen derzeit insbesondere MobileCity; sie verändern nicht automatisch das gesamte logiTopp-Modell.
- Der KIT-Pool ist ein synthetisches Marne-Beispiel, keine repräsentative deutschlandweite Haushaltserhebung.
- OSM-Gebäude sind für plausible Wohnpunkte vorgesehen; eine durchgängige Zuordnung ist noch nicht umgesetzt. Deshalb nicht als fertige Wohnadressquelle aufführen.
- OSM-Kartenkacheln dienen nur zur Gebietsauswahl; Modellinformationen stammen aus strukturierten OSM-Daten.
- EPSG:3035 und INSPIRE sind räumliche Standards, keine eigenen Datenlieferanten.
- Hochgeladene Daten sind ein anderer Zugangsweg zu einer Quelle, keine zusätzliche Quellenart.

Die ausführliche Bestandsliste einschließlich Grenzen und Benutzereinstellungen steht im [Dateninventar](13-dateninventar-modell.md).

## Folie 4 · Bevölkerungsmodell

**Kernaussage:** Aus statistischen Angaben entstehen einzelne modellierbare Haushalte und Personen.

- Bevölkerung konfigurieren.
- Haushalte und Personen erzeugen.
- Pkw zuordnen.
- Arbeits- und Bildungsorte zuweisen.

**Ergebnis:** Synthetische Haushalte, Personen, Pkw und feste Zielorte.

**Grundlagen / Verfahren:** KIT-Haushaltspool oder synthetische Ziehung; optionale Zensus-Nachgewichtung.

**„Bevölkerung konfigurieren“ bedeutet:** Optional Gesamtzahl, Stichprobenanteil und Pkw-Ausstattung vorgeben. Ohne manuelle Gesamtzahl wird die Einwohnerzahl aus Quellen abgeleitet. Standard ist die volle modellierte Bevölkerung, keine 10-%-Stichprobe.

**Beispielhaushalt für Folien 4 und 5 – frei gewählt, kein ausgelesener Datensatz:**

| Merkmal | Beispiel |
|---|---|
| Haushalt | Zwei Erwachsene und ein Kind |
| Mitglieder | Alex, 38; Sam, 36; Kind, 8 Jahre |
| Wohnort | Modellierter Wohnpunkt in Zone A |
| Pkw | Ein gemeinsam genutzter Pkw |
| Person im Wochenplan | Alex, erwerbstätig, fester Arbeitsort in Zone B |

**Sprechhinweis:** Die Wohnpunkte sind derzeit synthetisch innerhalb der Zone gesetzt. Reale Gebäude mit geeignetem Straßenzugang sind eine offene Verbesserung. Die Beispieldaten sind keine Behauptung einer realen Familie oder einer vollständig synchronisierten Haushaltsplanung.

## Folie 5 · Aktivitätenplanung

**Kernaussage:** Für jede Person wird eine zeitliche Folge von Aktivitäten geplant.

- Aktivitäten auswählen.
- Wochenabläufe zusammenstellen.
- Beginn und Dauer bestimmen.

**Ergebnis:** Individuelle Wochenpläne mit Aktivitäten und Zeitangaben.

**Werkzeuge:** actiTopp; alternativ GTA oder Vorlagen. Diese Namen separat als Werkzeugzeile, nicht als Tätigkeit aufführen.

### Wochenplan von Alex

Illustratives Beispiel. Zeiten bezeichnen Aktivitätenaufenthalte; zwischen Aktivitäten liegen Wege. Übrige Zeit wird zu Hause verbracht.

| Tag | Aktivitäten außerhalb des Zuhauses |
|---|---|
| Montag | 08:30–16:30 Arbeit; 17:00–17:30 Einkauf |
| Dienstag | 08:30–16:30 Arbeit; 18:00–19:00 Sport |
| Mittwoch | 08:30–16:30 Arbeit |
| Donnerstag | 08:30–16:30 Arbeit; 17:00–17:30 private Erledigung |
| Freitag | 08:30–15:00 Arbeit; 18:00–20:00 privater Besuch |
| Samstag | 10:00–11:00 Einkauf; 14:00–16:00 Freizeit |
| Sonntag | 11:00–13:00 privater Besuch |

**Montag:** Zuhause → Arbeit → Einkaufen → Zuhause.

**Sprechhinweis:** Der Plan gilt für Alex; weitere Haushaltsmitglieder haben eigene Pläne. Variable Zielzone und Verkehrsmittel werden in Phase 7 bestimmt. Straßengenaue Routen und belastungsabhängige Zeiten folgen in Phase 8. Eine Paketbestellung ist keine Einkaufsaktivität in dieser Tabelle.

## Folie 6 · Netz & Logistik

**Kernaussage:** Reisezeiten werden berechnet und die Zustellung wird modelliert.

- Reisezeiten und Entfernungen zwischen Zonen berechnen.
- Kostenansätze für die Verkehrsmittelwahl ergänzen.
- Depots, Paketstationen und Dienste einbinden.
- Paketraten und Zustellanteile festlegen.

**Ergebnis:** Reisematrizen und Logistikgrundlagen.

**Werkzeuge / Grundlagen:** GraphHopper, OSM-Straßennetz, Logistikgenerator und Szenarioannahmen.

**Beispiel:** Zwischen A und B stehen Zeit und Entfernung bereit. Für die Paketnachfrage sind Rate und Anteile für Zuhause, Arbeit und Paketstation definiert. Einzelne Aufträge existieren noch nicht.

**Sprechhinweis:** Kosten und ÖPNV-Zeiten beruhen teilweise auf Näherungen. OSM-Depotkandidaten sind nicht automatisch verifiziert. Matrizen speichern Zahlenwerte je Zonenpaar, keine vollständigen Straßenrouten.

## Folie 7 · Nachfrage

**Kernaussage:** Aus Bevölkerung, Wochenplänen und Reisematrizen entstehen Personenwege und Paketaufträge.

- Variable Ziele und Verkehrsmittel wählen.
- Personenwege erzeugen.
- Private Paketaufträge erzeugen und Touren planen.
- Verkehrsströme zwischen Zonen zusammenfassen.

**Ergebnis:** Personenwege, Paketaufträge, Zustelltouren und zeitliche Verkehrsströme.

**Werkzeuge:** mobiTopp / logiTopp.

**Beispiel:** Alex legt am Montag drei Wege zurück. Ein Paketauftrag wird unabhängig davon einem Zustellziel und einer Tour zugeordnet. „Verkehrsströme zwischen Zonen“ beschreibt beispielsweise die Zahl der Wege von A nach B in einem Zeitintervall; der technische Begriff OD ist auf der Folie unnötig.

**Sprechhinweis:** Tourstoppfolgen beruhen derzeit auf geometrischen Entfernungen und Zonenzeiten. Eine gezeichnete Tourlinie ist noch keine straßengenaue Route. Betriebliche Paketnachfrage ist nicht vollständig angebunden.

## Folie 8 · Verkehrsablauf

**Kernaussage:** Der Verkehr wird auf dem Straßennetz simuliert und Fahrzeiten werden ermittelt.

- Wege auf Straßenrouten übertragen.
- Fahrzeug- und Personenbewegungen simulieren.
- Verkehrsabhängige Fahrzeiten und Verzögerungen bestimmen.
- Straßenbelastungen auswerten.

**Ergebnis:** Simulierte Fahrzeiten, Verzögerungen und Straßenbelastungen.

**Werkzeuge:** SUMO mit Netz- und Routenaufbereitung.

**Beispiel:** Eine Verbindung braucht bei geringem Verkehr 12 Minuten, bei hoher Belastung im Modell 18 Minuten. Diese Zahlen illustrieren den Unterschied; sie stammen nicht aus einem konkreten Lauf.

**Sprechhinweis:** Es entstehen simulierte, nicht gemessene „wirkliche“ Zeiten. Unterstützte Verkehrsarten, gewählter Tag, Netz und Außennachfrage bestimmen den Umfang. Kein standardmäßiges Mengenlimit. Optional fließen Zeiten in erneute Nachfrageläufe zurück; mehr Wiederholungen garantieren keine bessere Realitätsnähe.

## Ergänzung · MobileCity

- Vorbereitete Bevölkerung, Aktivitäten, Attraktivitäten und Netzdaten aggregieren.
- GTFS, optionale regionale Flotten-/Ladedaten und Referenzparameter ergänzen.
- Konfigurationspaket erzeugen und optional lokal mit dem ConfigTool prüfen.
- Perspektivische gemeinsame Nachfragegrundlage und Logistikformat abstimmen.

**Heutiger Datenfluss:** Export nach der Vorbereitung in Phase 6; keine automatische Übernahme der Phase-7-Einzelwege. MobileCity rechnet mit seinem eigenen Modell. Eine Übereinstimmung seiner Ergebnisse mit SyntheSis ist keine Anforderung.

## Foliengestaltung

Je Phase dieselbe Struktur verwenden: **kurzer Titel → Kernaussage → drei bis vier Tätigkeiten → Ergebnis**. Werkzeuge stehen separat in einer kleinen Fußzeile. Beispiele, Implementierungsgrenzen und technische Dateinamen sind Sprechernotizen. Auf der Datenfolie markieren, welche Quellen optional oder nur für MobileCity relevant sind; die vollständige Tabelle eignet sich als Anhang.

## Ergänzende Begriffs- und Quellenklärung

[Zensus gegenüber Haushaltspool, MiD und Pendlerdaten, Behördenkürzel und Einkaufszielwahl](15-datenquellen-und-modellrollen.md). Die Einkaufszielzone wird im Personenmobilitätsmodell mobiTopp gewählt, das im angebundenen logiTopp-Lauf enthalten ist.


## Ergänzung: durchgängiges Kartenbeispiel und überprüfte Grundlagen

[Überarbeitete Kernaussagen, Bevölkerungssynthese, Näherungen, GTA und Grafiken für Phasen 6–8](18-folienbeispiel-und-formulierungen.md). [KIT-Anbindung ohne Patches und vollständiger Haushaltskontext](17-kit-adapter-und-haushaltskonzept.md).
