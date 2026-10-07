# Inhaltliches Review der FHDO-Präsentation

Prüfstand: 2. Oktober 2026. Präsentation: `2026-10-09_SyntheSis_FHDO_Übersicht.pptx`, 22 Folien.

## Umfang und Einordnung

Die Prüfung umfasst alle sichtbaren Folien, eingebettete Grafiken und Sprechernotizen. Die PPTX wurde für die Sichtprüfung mit LibreOffice als PDF gerendert. Aussagen zum Implementierungsstand wurden anhand des aktuellen SyntheSis-Codes geprüft. Die PPTX bleibt unverändert. Ein PowerPoint-Test und ein neuer vollständiger Simulationslauf waren nicht Bestandteil dieses Reviews.

Die Präsentation erklärt den fachlichen Prozess überwiegend richtig. Besonders das Beispiel mit Alex, die Unterscheidung zwischen Aktivitätenplanung und Nachfrage sowie die separate Zustelltour funktionieren. Vor einer Vorstellung sollten jedoch die technische Reihenfolge, der MobileCity-Zweig, die Datenquellen und die Aussagen zur Emissionsauswertung korrigiert werden. Einige Grafiken zeigen einen Zielzustand oder einen illustrativen Beispiellauf und brauchen eine entsprechende Kennzeichnung.

## Änderungen mit hoher Priorität

### 1. Die Netzberechnung steht im Ablauf zu spät

Betroffen: Folien 2, 4, 7, 9, 12, 15, 19 und die zusammenfassende Folie 21.

Das Diagramm stellt Bevölkerungssynthese und Aktivitätenplanung vor die Berechnung der Reisematrizen. Im aktuellen Code entstehen die Matrizen bereits nach den Attraktivitäten und vor der Bevölkerungssynthese. Die feste Arbeits-/Bildungszielzuordnung benötigt diese Zeiten.

Technischer Ablauf im geprüften Code:

1. Zonen erzeugen.
2. OSM-Daten und Bevölkerungsverteilung aufbereiten.
3. Attraktivitäten bestimmen.
4. Reisematrizen berechnen.
5. Haushalte, Personen, Pkw und feste Ziele erzeugen.
6. Aktivitätenpläne erstellen.
7. Logistikartefakte und Exportpakete erzeugen.
8. Optional mobiTopp/logiTopp ausführen.
9. Optional SUMO und Matrixfeedback ausführen.
10. Optional LLM-Verkehrsmittelvergleich erstellen.

Die acht Phasen können als fachliche Gliederung bleiben. Dann sollte das Diagramm als **„Fachliche Phasen“** bezeichnet werden und einen sichtbaren Hinweis erhalten:

> Reisematrizen stehen bereits für die feste Arbeits- und Bildungszielzuordnung bereit.

Für ein Diagramm, das ausdrücklich die Ausführungsreihenfolge zeigt, muss die Netzberechnung vorgezogen werden. Netzberechnung und Logistikkonfiguration lassen sich dabei fachlich getrennt zeigen. Ein einfacher linearer Pfeil von Phase 5 zu Phase 6 bildet die aktuelle Abhängigkeit nicht korrekt ab.

Außerdem fehlt in den achtphasigen Übersichten bei Phase 7 die variable Zielwahl, obwohl sie auf Folie 16 korrekt genannt wird. Geeigneter erster Bulletpoint: **„Ziele und Verkehrsmittel wählen“**.

### 2. MobileCity erhält aktuell nicht die spätere mobiTopp/logiTopp-Nachfrage

Betroffen: besonders Folie 21.

Der gemeinsame Ausgangspfeil von Personenwegen und Zustelltouren zur MobileCity-Seite suggeriert, dass das Ergebnis der Nachfrageausführung exportiert wird. Der aktuelle Code exportiert MobileCity jedoch vor dieser Ausführung. Er verwendet gemeinsame Modellinformationen: Zonen, Attraktivitäten, Bevölkerung, Aktivitätenraten, Reisematrizen sowie weitere regionale und technische Parameter.

Die Bezeichnung **„Nachfragedaten“** ist zu unspezifisch, wenn darunter vollständige Personenwege und Zustelltouren verstanden werden können. Besser:

- Eingang: **„Aggregierte Modelldaten“**.
- Verarbeitung: **„MobileCity-Export“**.
- Ergebnis: **„Konfigurationspaket“**.

Den Pfeil aus der gemeinsamen Modellgrundlage zum Export führen. SUMO bekommt dagegen die aufbereiteten Wege und Touren aus dem Nachfragezweig. Eine spätere Übernahme von Logistiknachfrage oder SUMO-Ergebnissen nach MobileCity als geplanten zusätzlichen Pfeil kennzeichnen.

Die Platzierung von MobileCity im großen Kasten „Simulation“ ist für die aktuelle SyntheSis-Anbindung missverständlich: Hier wird ein Exportpaket erzeugt. Eine tatsächlich ausgeführte MobileCity-Simulation darf daraus nicht abgeleitet werden. Die optionale native ConfigTool-Prüfung ist eine getrennte Prüfung und kann mangels Umgebung übersprungen werden.

### 3. Emissionen und Verbrauch: Folie 20 verspricht den geplanten Ausbau bereits

Betroffen: Folie 20 und Phase 8 der Ablaufgrafiken.

SUMO unterstützt verschiedene Schadstoffe und Energieverbrauch. Unser Runner wertet aktuell aus `tripinfo.xml` **CO₂ insgesamt und je Fahrzeugtyp** aus. Diese Werte landen in `sumo/summary.json`. NOₓ, PMx, CO, HC, Kraftstoff und Strom werden noch nicht entsprechend aggregiert und bereitgestellt.

Für den aktuellen Stand formulieren:

> **CO₂ je Fahrzeugtyp auswerten**

Geplanten Ausbau getrennt nennen:

> **Weitere Emissionen und Energieverbrauch ergänzen**

Für Phase 8 ist **„Reisezeiten, Belastung & CO₂“** besonders präzise. **„Reisezeiten, Belastung & Emissionen“** ist als Oberbegriff ebenfalls vertretbar, sofern die Detailfolie den aktuellen CO₂-Umfang nennt. „Verbrauch ermitteln“ ohne Statuskennzeichnung ist derzeit zu weitgehend.

Fahrzeug- und Emissionsklassen beeinflussen die Ergebnisse. Die Defaultflotte mit beispielsweise Benzin-Pkw Euro 4 und Diesel-Lieferwagen Euro 4 ist eine Modellannahme. CO₂-Ergebnisse belegen keine regional repräsentative Flotte und keine vollständige Lebenszyklusbilanz. Auch am Tagesende noch nicht abgeschlossene Fahrten benötigen für eine vollständige Bilanz eine zusätzliche Behandlung.

### 4. MiD und Pendlerverflechtungen auseinanderhalten

Betroffen: Folie 8.

MiD beschreibt das alltägliche Mobilitätsverhalten, einschließlich Arbeitswegen. Die Beschriftung „Verkehrsmittelanteile, Pendlerströme“ suggeriert jedoch, dass MiD die konkret verwendete Quelle für Wohnort-Arbeitsort-Verflechtungen sei. Im Projekt existiert dafür ein BA-Pendler-CSV-Adapter.

Empfohlene Beschriftung:

> **MiD – Verkehrsmittelanteile, Weglängen, Wegezwecke**

Zusätzliche Quelle:

> **Pendlerdaten – Wohnort-Arbeitsort-Verflechtungen**

Als Quelle dafür die Bundesagentur für Arbeit nennen, gegebenenfalls ergänzt um regionale Datensätze. Die BA-Daten decken sozialversicherungspflichtig Beschäftigte ab und bilden nicht automatisch alle Berufstätigen ab.

Die standardmäßigen Modal-Split-Kalibrierungswerte im aktuellen Code beziehen sich noch auf MiD 2017. Eine Folie mit „MiD“ sollte keine automatische Nutzung der aktuellen MiD-2023-Ergebnisse suggerieren.

### 5. Die Beispielgrafiken sind kein belegter durchgängiger Nachfrage-SUMO-Lauf

Betroffen: Folien 14, 17 und 20.

Die Reisezeiten auf Folie 14 stimmen mit den gespeicherten GraphHopper-Beispieldaten überein: A–B etwa 7,1 km / 9,4 min, B–C etwa 9,3 km / 12,2 min, C–A etwa 2,6 km / 3,5 min. Die Routen wurden tatsächlich auf Straßen berechnet. Standorte, Zellen und Depot sind illustrativ. Ergänzung: **„Pkw-Beispiel, Basisreisezeiten ohne simulierte Verkehrsbelastung“**.

Folie 17 verwendet das gespeicherte illustrative Beispiel mit Alex und der Zustellfolge D–A–E–D. Die Straßenlinien wurden zur Darstellung mit GraphHopper berechnet. Sie sind kein ausgewerteter regionaler mobiTopp/logiTopp-Nachfragelauf. Die kleine Kennzeichnung ist bereits vorhanden, sollte aber besser lesbar sein.

Folie 20 enthält exakt die gespeicherte Phase-8-Grafik. Deren Herkunftsdaten belegen einen echten SUMO-Beispiellauf mit frei gewählter Nachfrage. Es ist kein durchgängiger Lauf, der die Alex-Nachfrage von Folie 17 vollständig übernommen hat. Die Kennzeichnung der ursprünglichen Beispieldarstellung wurde im verwendeten Ausschnitt nicht mit übernommen.

Empfohlene Bildunterschrift:

> **Echter SUMO-Beispiellauf auf OSM-Netz; illustrative Nachfrage**

Die gemeinsame Region und Farbgebung helfen didaktisch. Sie dürfen nicht als Nachweis eines vollständigen, kalibrierten Dortmund-Modells verstanden werden.

### 6. Aktuelle Integration und geplante Datenanbindung unterscheiden

Betroffen: Folien 8, 21 und 22.

Ein Pfeil „Kommunale Daten“ in die gemeinsame Modellgrundlage kann als Konzept gut bleiben. Eine allgemeine automatische Integration beliebiger kommunaler Verkehrszählungen und lokaler Erhebungen ist damit aber nicht belegt. Kennzeichnen: **„optional / projektspezifisch“**, gegebenenfalls **„im Ausbau“**.

Auch die Fahrzeugkonfigurationsschnittstelle ist nicht vollständig Zukunft: Schema, Validierung und Szenario-API existieren bereits. Geplant sind unter anderem ein vollständiger UI-Import und eine weitergehende Anbindung an Nachfrage, Flotte und Logistikbetrieb. Besser auf Folie 22:

> **Fahrzeugimport und Simulatoranbindung ausbauen**

Pendlerdaten-Upload und externe Zielzonen existieren ebenfalls bereits. Besser:

> **Pendler- und Umlandmodellierung ausbauen**

Die umfassende Integration von Einpendlern und Durchgangsverkehr ist damit nicht als bereits abgeschlossen behauptet.

## Detaillierter Foliencheck

### Folie 1 – Screenshot als Einstieg

Der Screenshot zeigt die Anwendung, aber eine klare Überschrift und ein Nutzenversprechen fehlen. Für einen Vortrag ist das ein abrupter Einstieg. Vorschlag:

- Titel: **„SyntheSis: Mobilität und Paketlogistik“**.
- Ein Satz: **„Gemeinsame Modelldaten für Nachfragegenerierung und Verkehrssimulation“**.
- Den Screenshot anschließend als Demonstration verwenden.

Die Notizen enthalten bereits die Beschreibung des gemeinsamen Modells, MiD und des Haushaltspools. Diese passen eher zu den Folien 8 und 10. Die Notizen sind teilweise erkennbar von früheren Folien übernommen.

### Folien 2, 4, 7, 9, 12, 15 und 19 – Wiederholte Phasenübersichten

Die roten Rahmen machen die aktuelle Phase erkennbar. Das ist ein brauchbares Navigationsprinzip. Sieben fast vollständige Wiederholungen beanspruchen aber viel Vortragszeit und enthalten kleine, kaum aus der Entfernung lesbare Texte.

Empfehlungen:

- Eine vollständige Übersicht am Anfang.
- In späteren Kapiteln nur die acht farbigen Phasentitel als schmale Navigation.
- Falls die großen Wiederholungen bleiben: nicht jedes Mal den Inhalt erneut erklären.
- Rote Rahmen durch eine stärkere Hervorhebung der aktuellen Phase ersetzen, wenn sie nicht bewusst die Hausschreibweise sind.
- Änderungen an einem gemeinsamen Diagramm pflegen. Auf Folie 12 weicht die Ergebnisformulierung der Bevölkerungssynthese bereits ab.
- Den derzeit fehlenden Bulletpoint zur variablen Zielwahl ergänzen.

Die Notizen auf Folie 2 enthalten die alte Formel `(Attraktivität + 1) / (1 + Entfernung²)`. Im aktuellen Generator gilt bei bereitgestellter Matrix `(Attraktivität + 1) / (1 + (Pkw-Zeit_min / 20)²)`. Pendlerrelationen haben Vorrang. Die 20 Minuten sind eine unkalibrierte Skalierungsannahme. Die veraltete Notiz ersetzen oder die Formel ganz aus der Vortragsebene entfernen.

### Folie 3 – Szenariomodellierung

„Gebiet, Rahmenbedingungen und Modellumfang festlegen“ ist inhaltlich passend. Der Kartenausschnitt gehört hierher. Die Screenshots zeigen sinnvolle Einstellungen, sind auf der Folie aber zu klein.

Drei kurze Bullets reichen:

- **Gebiet und Zellgröße festlegen**.
- **Szenarioannahmen konfigurieren**.
- **Modelle und Ausgaben wählen**.

Der Text nennt 500 m als Beispiel, die UI zeigt eine automatisch vorgeschlagene Auflösung von 1.300 m. Das ist kein fachlicher Fehler, sollte aber erklärt oder für ein einheitliches Demonstrationsszenario angeglichen werden. Die UI unterscheidet gewünschte Feinheit und durch das Zonenlimit praktisch nutzbare Auflösung.

Die UI zeigt mehr als eine Million Einwohner und 100 % Stichprobenanteil. Das ist eine Konfiguration und kein Beleg einer erfolgreich abgeschlossenen Vollsimulation. Für eine Demo besser ein tatsächlich geprüftes Gebiet verwenden und Konfiguration und Ergebnis klar trennen.

### Folie 5 – Zonierung

Der EPSG:3035-Bezug und die gemeinsame Zuordnung räumlicher Daten sind richtig. Die Aussage „feinere Datensätze werden summiert“ ist jedoch nur für zählbare Mengen allgemein richtig, beispielsweise Einwohnerzahlen und Haushaltszahlen. Anteile, Geschwindigkeiten, Dichten oder Durchschnittswerte benötigen andere Aggregationen.

Vorschlag:

- **Gebiet in Verkehrszellen gliedern**.
- **Einwohner- und Haushaltszahlen je Zelle zusammenfassen**.
- **Gröbere Bevölkerungswerte gleichmäßig verteilen**.

Ein kleines Beispiel macht die Vereinfachung transparent:

> Eine 1-km-Zelle mit 400 Einwohnern wird auf vier 500-m-Zellen mit jeweils 100 Einwohnern verteilt.

Das erzeugt eine feinere Modellauflösung, aber keine neu beobachtete kleinräumige Bevölkerungsverteilung. Für Randzellen und nicht deckungsgleiche Raster ist die räumliche Zuordnung entscheidend. Die gleichmäßige Aufteilung ist eine bewusst gewählte Modellannahme.

### Folie 6 – EPSG:3035

Die Grundidee ist korrekt: europaweit geeignetes flächentreues Bezugssystem für statistische Analysen. „Standard im Rahmen von INSPIRE“ sollte auf statistische Flächenraster bezogen werden, nicht als einziges CRS für alle europäischen Geodaten verstanden werden.

Die Aussage „Zellnamen enthalten Koordinaten der Ecke in Kilometern“ ist zu allgemein. Ein Zellcode hängt vom Raster und der Auflösung ab. Unsere flexiblen LAEA-Zellnamen enthalten die Auflösung und südwestliche Eckkoordinaten in Metern. Ein 1-km-INSPIRE-Beispiel kann dagegen gekürzte Koordinaten verwenden. EPSG:3035 als Koordinatenreferenzsystem und INSPIRE als standardisiertes Raster beziehungsweise Codierung unterscheiden.

Bessere Formulierung:

> **Zellen werden durch Auflösung und Lage eindeutig beschrieben**.

„1 km² in der Realität ist auf der Karte exakt 1 km²“ würde ich durch **„Flächenverhältnisse bleiben erhalten“** ersetzen. Längen und Winkel bleiben dabei nicht gleichermaßen erhalten. Für die Präsentation reichen drei Hauptpunkte:

- **Gemeinsamer Raumbezug für Europa**.
- **Flächentreue Verkehrszellen**.
- **Kompatible Zuordnung von Zensusrastern**.

Die sichtbar schräge Rasterdarstellung auf der Hintergrundkarte ist ein normaler Effekt der unterschiedlichen Projektionen. Das lässt sich mündlich erklären. Für eine allgemeine FHDO-Übersicht kann die detaillierte Projektionserklärung in den Anhang.

### Folie 8 – Daten im gemeinsamen Modell

Die zentrale Grafik ist als Überblick verständlich. Folgende Änderungen sind nötig oder hilfreich:

- **GFTS → GTFS**.
- **Open Street Map → OpenStreetMap**.
- GTFS-Beschreibung: **„Haltestellen, Linien, Fahrten, Ankunfts-/Abfahrtszeiten, Betriebstage“**.
- MiD: **„Verkehrsmittelanteile, Weglängen, Wegezwecke“**.
- BA-Pendlerdaten separat ergänzen.
- Beim KIT-Pool optional **„Haushaltsvorlagen“** statt nur „synthetische Haushalte“ verwenden.
- Zensus-Alters-/Haushaltsstruktur als optionale regionale Anpassung kennzeichnen. Die Option ist standardmäßig ausgeschaltet; der Screenshot zeigt dies ebenfalls.
- Kommunale Daten als optional/projektspezifisch kennzeichnen.

Wenn die Folie alle verwendeten Quellen darstellen soll, fehlen auch KBA, BKG und BNetzA. Diese sind besonders für regionale Fahrzeuganteile, Gebietszuordnung und Ladeinfrastruktur im MobileCity-Zweig relevant. Dafür einen zusätzlichen Sammelpunkt **„Fahrzeuge & Infrastruktur“** vorsehen. Wenn die Grafik nur eine Auswahl zeigen soll, mit „Wichtige Datenquellen“ überschreiben.

Eine Legende würde die Rolle der Daten verdeutlichen: räumliche Eingaben, Strukturvorgaben, Verhaltensreferenzen und Nutzervorgaben. MiD ist insbesondere eine Referenz für Mobilitätsverhalten, kein Datensatz, aus dem jeder synthetische Haushalt direkt erzeugt wird. OSM-Einrichtungen liefern Hinweise auf Zielattraktivitäten. Die daraus abgeleiteten Gewichte sind nicht automatisch gemessene Arbeitsplatz-, Laden- oder Schulkapazitäten.

### Folie 10 – Bevölkerung und Aktivitäten

Der Übergang zur Aktivitätenplanung ist grundsätzlich richtig. Es fehlt aber der KIT-Haushaltspool als wichtigste Erklärung dafür, wie aus Einwohnerzahlen gemeinsame Haushaltskompositionen entstehen.

Die Nutzervorgaben sind zudem nicht insgesamt eine Alternative zum Zensus: Pkw-Ausstattung und Stichprobenanteil ergänzen auch eine zensusbasierte Bevölkerung. Nur eine manuelle Gesamtzahl kann den abgeleiteten Gesamtwert ersetzen.

Kurze Ersatzformulierungen:

- **Zensus: Einwohnerverteilung ableiten**.
- **KIT-Pool: vollständige Haushalte auswählen**.
- **Optional: regionale Alters- und Haushaltsstruktur gewichten**.
- **Pkw und feste Arbeits-/Bildungsziele zuweisen**.
- **actiTopp: Wochenpläne erzeugen**.
- **LLM-Planung: experimentelle Alternative**.

Erwerbsstatus, Einkommen und weitere Merkmale enthalten weiterhin eigene Modellannahmen. Der KIT-Pool ist ein Ausgangspool und kein automatischer Nachweis einer regional repräsentativen Haushaltspopulation. Auch die vollständige Haushaltsstruktur garantiert noch keine vollständig koordinierte gemeinsame Pkw-Nutzung oder Begleitplanung.

Der verlinkte actiTopp-Quellcode ist eine sinnvolle allgemeine Referenz. Für die konkret verwendete Aktivitätenplanung sollte zusätzlich **actiToppNG über einen separaten SyntheSis-Adapter** genannt werden. Der Build verwendet den festgelegten Originalstand von `kit-ifv/actitoppNG`. Die Schnittstelle übernimmt vollständigen Haushaltskontext. Die Folie sollte nicht suggerieren, dass actiTopp die regionale Bevölkerung selbst aus Zensusdaten erzeugt.

Der LLM-Ansatz existiert experimentell, arbeitet aber mit gruppierten Personas und wiederverwendeten Plänen. „LLM-Agenten“ klingt auf Folie 21 nach vollständig individuell und miteinander interagierend planenden Agenten. Präziser: **„LLM-basierte Wochenpläne“**.

### Folie 11 – Alex und sein Wochenplan

Inhaltlich passend und als durchgängiges Beispiel hilfreich. Haushalt, fester Arbeitsort und variable Einkaufszielzone sind richtig getrennt.

Ergänzen:

> **Illustratives Beispiel; Heimaktivitäten und Wegezeiten ausgeblendet**.

Die Tabelle zeigt Aktivitätszeiten, keine Abfahrtszeiten. „08:30–16:30 Arbeit“ bedeutet Aufenthalt am Arbeitsort. Die Fahrt beginnt vorher. Auf einer späteren Folie kann der Montag mit Abfahrt, Ankunft und Verkehrsmittel ergänzt werden. Das zeigt konkret den Übergang von Aktivitätsplan zu Personenwegen.

Der gemeinsame Pkw ist ein Haushaltsmerkmal. Nicht behaupten, dass damit bereits alle Konflikte zwischen Alex und Sam automatisch gelöst wären.

### Folie 13 – Netz und Logistik

Die genannten Aufgaben stimmen im Wesentlichen. Die Rolle von GraphHopper lässt sich klarer abgrenzen:

- **Straßenbasierte Zeiten für Pkw, Rad und Fuß bestimmen**.
- **Kostenannahmen ergänzen**.
- **Depots, Paketstationen und Dienste modellieren**.
- **Paketraten und Zustellanteile festlegen**.

GTFS wird bereits für bestimmte Export-/ÖV-Aufgaben verwendet, liefert aber noch keine vollständige fahrplanbasierte ÖPNV-Reisematrix für die Nachfrage. ÖPNV-Zeiten und Kostenansätze sind teilweise Näherungen. Diese Einschränkung gehört mindestens in die Notizen.

GraphHopper berechnet weder Paketaufträge noch die gesamte Logistikplanung. Die Kosten sind modellierte Verkehrsmittelkosten; im Code beispielsweise 0,32 €/km für Pkw sowie einfache ÖPNV-Ansätze. Diese Zahlen nicht als empirisch für Dortmund kalibrierte Kostensätze darstellen.

### Folie 14 – GraphHopper-Beispiel

Als Anschauung gelungen. Die echten Straßenkanten, konsistenten Standorte und tatsächlich berechneten Zeiten zeigen den Zweck der Reisematrizen. Verbesserungen:

- **Pkw** als Profil nennen.
- **Basisreisezeit ohne simulierten Stau** kenntlich machen.
- **Standorte/Depot illustrativ** lesbar kennzeichnen.
- Quelle **© OpenStreetMap-Mitwirkende** und Herkunft der Grafik aufnehmen.

Keinen real existierenden Depotstandort aus der gezeichneten Position ableiten. Die Modellzone und ein genauer Gebäudestandort sind ebenfalls unterschiedliche Genauigkeitsebenen.

### Folie 16 – Mobilitäts- und Logistiknachfrage

Die Aufgaben sind fachlich richtig. Die gemeinsame Darstellung von mobiTopp und logiTopp sollte deren Rollen erläutern:

- **mobiTopp: Ziele, Verkehrsmittel und Personenwege**.
- **logiTopp: Paketaufträge und Zustelltouren**.

logiTopp ist eine Erweiterung von mobiTopp. Der gemeinsame native Lauf erzeugt beide Ergebnisarten. Die Beschriftung eines Launchers oder Ergebnisverzeichnisses als „logiTopp“ macht Personenwege nicht zu einer alleinigen Leistung der Logistikerweiterung.

Bei Tourenplanung als aktuelle Vereinfachung festhalten: Die Stoppreihenfolge wird noch geometrisch bestimmt; die Beispielgrafik liefert zur Darstellung Straßenrouten. Eine fahrzeugspezifisch netzzeitoptimierte Tourenplanung ist ein weiterer Schritt.

### Folie 17 – Alex und die getrennte Zustelltour

Die Zustellung D–A–E–D ist richtig von Alex' Einkauf getrennt. Der zweite Zustellpunkt E behebt die frühere unverständliche Fahrt des Zustellfahrzeugs zum Einkaufsort.

Verbesserungen:

- Richtungspfeile oder eine nummerierte Stoppfolge ergänzen.
- Einen exemplarischen Modus für Alex nennen.
- Das illustrative Beispiel gut sichtbar markieren.
- Falls eine Paketübergabe an Alex persönlich erzählt wird, eine passende Zustellzeit oder eine Abstell-/Vertretungsregel nennen. Ohne Zustellzeit ist die Grafik nicht falsch, aber die Anwesenheit des Empfängers lässt sich nicht beurteilen.

Die heutige GraphHopper-Straßenfolge wird noch nicht automatisch an SUMO übergeben. Für einen als Ergebnisvergleich erzählten Vortrag diesen Unterschied erwähnen.

### Folie 18 – mobiTopp-Exkurs

Die Diagramme beschreiben die allgemeine mobiTopp-Architektur. Sie enthalten externe Programme wie VISUM und MATSim sowie Prozesse zur Parameterschätzung. Das bedeutet nicht, dass diese Programme in unserer aktuellen Pipeline eingesetzt werden.

Ergänzung:

> **Allgemeine KIT-Architektur; SyntheSis stellt die Eingaben über eigene Adapter bereit**.

Das rechte Diagramm eignet sich für die Erklärung besser. Ein einzelnes, größeres Diagramm oder zwei getrennte Anhangfolien wären leichter lesbar. Quellen der übernommenen Abbildungen angeben. Die Grafik allein belegt keine automatisch für Dortmund/Unna vorgenommene Parameterschätzung.

### Folie 20 – Verkehrsablauf

Die Aussagen zur Straßennetzsimulation, Fahrzeiten und Netzbelastung sind passend. Präzise kurze Bulletpoints:

- **Straßenrouten berechnen**.
- **Verkehr simulieren**.
- **Reisezeiten und Verzögerungen auswerten**.
- **Straßenbelastungen bestimmen**.
- **CO₂ je Fahrzeugtyp auswerten**.

SUMO-Aufbereitung berechnet die Routen derzeit erneut. „Übertragen“ darf nicht als bereits implementierte Übernahme der GraphHopper-Kantenfolge interpretiert werden.

Bei der Erklärung der Reisezeiten zwischen drei Ebenen unterscheiden: Basiszeiten aus GraphHopper, zeitliche Ausführung in mobiTopp und belastungsabhängige Zeiten aus SUMO. Die optionale Rückkopplung aktualisiert derzeit die Pkw-Matrix und führt Nachfrage und SUMO erneut aus. Einzelne gemessene Ankünfte gelangen noch nicht unmittelbar in denselben laufenden Wochenplan.

Bildunterschrift ergänzen: **„Echter SUMO-Beispiellauf; illustrative Nachfrage“**.

### Folie 21 – Gesamtübersicht

Die Grafik enthält viele nützliche Elemente, bildet aber gegenüber den Detailfolien eine zweite Phasengliederung ab. Für die Schlusszusammenfassung ist sie sehr dicht.

Vorrangige Änderungen:

1. Netzberechnung fachlich mit der festen Zielzuordnung verbinden.
2. MobileCity als Exportzweig aus gemeinsamen Modelldaten darstellen.
3. Die gemeinsame Überschrift „Simulation“ für SUMO und MobileCity-Export auflösen.
4. **„kalibrierts KIT-Modell“** korrigieren. Besser **„KIT-Aktivitätenmodell“**, sofern keine regionale Kalibrierung gezeigt wird.
5. **„LLM-Agenten“** durch **„LLM-basierte Wochenpläne (experimentell)“** ersetzen.
6. Verkehrsmittelvergleich neutral zeichnen: Modellergebnis, alternative Schätzung, empirische Referenz. Das Unterlabel „LLM-basierte Modusanalyse“ stellt weiterhin nur einen Zweig dar.
7. Optionalen Kalibrierungspfeil mit einem Referenzeingang verbinden. Sonst bleibt unklar, woran kalibriert wird. MiD ist eine empirische Referenz und kein weiteres Entscheidungsmodell. Die aktuelle LLM-Auswertung schätzt Anteile nach Entfernungsgruppen mit vereinfachten Reisezeiten, keine individuelle Verkehrsmittelentscheidung mit vollständigem Haushaltskontext.
8. Optionales SUMO-Matrixfeedback ergänzen, wenn Rückkopplung eine zentrale Aussage des Vortrags ist.

Der Ablauf `netconvert → duarouter → SUMO` ist richtig. Das Ausgangslabel **„Wege & Touren“** ist kurz und passend.

Für das Publikum sind drei Aussagen besonders wichtig: gemeinsame Datenbasis, erzeugte Nachfrage, getrennte Zielsysteme. Die vollständige Artefaktübersicht besser in den Anhang verschieben oder zusätzlich eine größere Liste tatsächlicher Ausgabedateien anbieten.

### Folie 22 – Geplante Erweiterungen

Inhaltlich sinnvolle Richtung. Die Folie sollte den Ausbau vorhandener Ansätze von ganz neuen Funktionen unterscheiden. Der Assistent ist eine Konzeptgrafik und keine implementierte Oberfläche; eine sichtbare Bildunterschrift **„Konzept“** verhindert Missverständnisse.

Aktualisierte kurze Punkte:

- **Dialogbasierter Szenarioassistent**.
- **Gemeinsame Routenbasis für GraphHopper und SUMO**.
- **Verspätungen in Aktivitäten zurückführen**.
- **GTFS-basierte ÖPNV-Reisematrizen**.
- **Regionale Kalibrierung und kommunale Daten**.
- **Fahrzeugimport und Pendlerdaten ausbauen**.
- **MobileCity-Logistikexport erweitern**.

Weitere Emissionen und Energieverbrauch können bei Bedarf einen eigenen Punkt bekommen. Für eine kurze Folie eher nach Wichtigkeit auswählen als alle Vorhaben unterzubringen. Eine abschließende Zusammenarbeit-Frage könnte lauten: **„Welche regionalen Daten und Referenzfälle können wir gemeinsam prüfen?“**

## Verständlichkeit und Gestaltung

### Den Status sichtbar machen

Eine einfache wiederkehrende Kennzeichnung reicht:

- **Aktuell** für implementierte Funktionen.
- **Experimentell** für vorhandene, begrenzt erprobte Ansätze.
- **Geplant** für noch ausstehende Funktionen.
- **Beispiel** für illustrative Szenarien und Karten.

Es muss nicht jedes Textfeld einen Status bekommen. Gerade bei GTFS-Reisematrizen, regionaler Kalibrierung, LLMs und MobileCity hilft die Kennzeichnung jedoch erheblich.

### Einheitliches Demonstrationsszenario

Die Verknüpfung über Alex und Zonen A–E sollte bleiben. Pro Phase genau eine neue Information hinzufügen: Haushalt, Wochenplan, Reisezeit, gewähltes Verkehrsmittel, Straßenausführung und Ergebnis. Wo diese Informationen frei gewählt sind, „illustrativ“ nennen. Ein späterer echter durchgängiger Referenzlauf könnte diese Beispiele ersetzen.

### Ergebnis und Nachweis ergänzen

Die Präsentation erklärt überwiegend, wie der Prozess gedacht ist. Eine kurze Folie **„Aktueller Stand“** würde ergänzen, welche Teile tatsächlich geprüft sind. Mögliche Aussagen:

- Aktivitätenplanung über separaten actiToppNG-Adapter geprüft.
- Gemeinsamer mobiTopp/logiTopp-Adapter erzeugt Personenwege und Paket-/Tourenartefakte.
- SUMO-Beispiellauf und CO₂-Auswertung vorhanden.
- MobileCity-Paketgenerierung vorhanden; native Importprüfung getrennt ausweisen.

Die gespeicherte native logiTopp-Prüfung ist ein Adapterlauf mit bestehenden Plänen, kein frisch durchgenerierter kompletter Haushaltslauf. Eine Java-Laufzeit oder ein erfolgreich gestarteter Prozess allein belegt keine regionale Kalibrierung. Keine neue quantitative Ergebnisfolie ohne passendes Herkunftsprotokoll erfinden.

### Lesbarkeit

- Die langen UI-Screenshots auf Folie 3 in zwei oder drei gezielte Ausschnitte aufteilen.
- Tabellen auf Folie 11 vergrößern oder den Montag als Hauptbeispiel hervorheben.
- Auf Folie 18 nur ein Architekturdiagramm groß zeigen.
- Die dichteste Grafik auf Folie 21 als Anhang oder Handout verwenden.
- Bildunterschriften zu Beispielstatus und Quellen größer setzen.

Im LibreOffice-PDF bricht die Jahreszahl der Fußzeile auf vielen Folien um: „2. Oktober 20“ / „26“. Das kann ein Darstellungsunterschied zu PowerPoint sein, sollte aber vor dem Vortrag dort geprüft werden. Ein kürzeres Datum oder ein breiteres Datumsfeld ist robuster. Automatische Datumsfelder können zudem das Öffnungsdatum statt des gewünschten Vortragstermins zeigen.

### Schreibweisen und Quellen

- OpenStreetMap.
- GTFS.
- MiD, ausgeschrieben Mobilität in Deutschland.
- Pkw statt wechselnd PKW, PKWs und Pkws.
- actiTopp, mobiTopp, logiTopp und MobileCity konsistent.
- 500 m statt 500m.
- Dezimalkomma bei deutsch beschrifteten Reisezeiten.
- Quellen für OSM-Karten, KIT-Diagramme und Fremdlogos in Notizen beziehungsweise kleinen sichtbaren Quellenangaben ergänzen.

## Konkrete Priorität vor dem Vortrag

1. MiD/Pendler und GTFS auf Folie 8 korrigieren.
2. Aktuellen CO₂-Umfang auf Folie 20 benennen.
3. MobileCity-Zweig auf Folie 21 korrigieren.
4. Technische Reihenfolge in Phasenübersichten erklären oder anpassen.
5. KIT-Haushaltspool auf Folie 10 ergänzen.
6. Beispielstatus auf Folien 14, 17, 20 und 22 deutlich machen.
7. Veraltete Sprechernotizen und Datumsfelder bereinigen.
8. Die kleinsten Screenshots und Diagramme für die Projektion vereinfachen.

## Technische Belege

Die Links führen zum geprüften lokalen Code beziehungsweise zu gespeicherten Herkunftsdaten. Sie dienen als Nachweis des Implementierungsstands, nicht als Vorschlag, Quellcode auf die Vortragsfolien zu setzen.

| Aussage | Beleg |
|---|---|
| Matrizen vor Bevölkerung; MobileCity vor Nachfrage; optionales Matrixfeedback | [GenerationService](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/GenerationService.java:315) |
| Vollständige Haushalte, Pkw-Zuordnung und Zeitgewichtung fester Ziele | [PopulationGenerator](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/pipeline/PopulationGenerator.java:184) |
| Zensus 2022, KIT-Pool und MiD-2017-Standardparameter | [Scenario](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/scenario/Scenario.java:124), [CensusStructureSource](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/pipeline/CensusStructureSource.java:29) |
| Einwohner-/Alters-/Pkw-Hochrechnung für MobileCity | [MobileCityPopulation](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/pipeline/MobileCityPopulation.java:15) |
| Konkrete KIT-Quellstände | [kit-sources.env](/Users/PH/Entwicklung/SyntheSis/runtime/kit-sources.env:6) |
| Attraktivitätsgewichte aus OSM statt gemessener Kapazitäten | [AttractivityCalculator](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/pipeline/AttractivityCalculator.java:75) |
| LLM-Vergleich nach Entfernungsgruppen | [LlmModeChoiceRunner](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/pipeline/LlmModeChoiceRunner.java:95) |
| LLM-Personagruppen statt vollständiger individueller Agentenplanung | [GtaBehaviorRunner](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/pipeline/GtaBehaviorRunner.java:27) |
| BA-Pendleradapter | [BaCommuterAdapter](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/pipeline/BaCommuterAdapter.java:13) |
| GraphHopper übernimmt nur Distanz und Zeit | [RoutingEngine](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/pipeline/RoutingEngine.java:73) |
| ÖPNV- und Kostenansätze | [MatrixGenerator](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/pipeline/MatrixGenerator.java:21) |
| SUMO-Ausgabe, CO₂-Parser und Feedbackfaktoren | [SumoRunner](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/pipeline/SumoRunner.java:195) |
| Fahrzeugkonfiguration im Backend vorhanden | [VehicleController](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/vehicle/VehicleController.java:19) |
| Flexible Raster und Zellenamen | [InspireGridZoning](/Users/PH/Entwicklung/SyntheSis/backend/src/main/java/de/synthesis/generation/pipeline/InspireGridZoning.java:114) |
| Echte Netz-/Routendaten, illustrative Nachfrage und SUMO-Screenshot | [Beispiel-Herkunft](/Users/PH/Entwicklung/SyntheSis/docs/figures/phasenbeispiel-dortmund-unna/provenance.json), [Beispielbeschreibung](/Users/PH/Entwicklung/SyntheSis/docs/figures/phasenbeispiel-dortmund-unna/README.md) |
| Nativer Adapterlauf und dessen Grenzen | [Prüfbericht](/Users/PH/Entwicklung/SyntheSis/docs/verification/2026-10-02-logitopp-adapter-v2.json) |

## Externe Primärquellen

- [GTFS Schedule Reference](https://gtfs.org/documentation/schedule/reference/): Datenstruktur und Fahrplanzeiten.
- [MiD-Publikationen](https://www.mobilitaet-in-deutschland.de/publikationen2023.html): Erhebung und aktuelle Ergebnisberichte.
- [BA: Regionale Mobilität](https://statistik.arbeitsagentur.de/DE/Navigation/Statistiken/Themen-im-Fokus/Regionale-Mobilitaet/Regionale-Mobilitaet-Nav.html): Pendlerverflechtungen und Abdeckung der Beschäftigtenstatistik.
- [INSPIRE: Geographical Grid Systems](https://inspire-mif.github.io/technical-guidelines/data/gg/dataspecification_gg.html): flächentreues Raster, Auflösungen und Zellcodes.
- [actiToppNG](https://github.com/kit-ifv/actitoppNG): konkret verwendete Modellfamilie für Aktivitätenpläne.
- [logiTopp](https://github.com/kit-ifv/logitopp): Erweiterung von mobiTopp für Paketaufträge und letzte Meile.
- [SUMO-Emissionsausgabe](https://sumo.dlr.de/docs/Simulation/Output/EmissionOutput.html): mögliche Emissions- und Verbrauchsgrößen.
