# Güterverkehr: Integrationskonzept und frei verfügbare Datenquellen

Stand: 27.10.2026. **Status: technischer MVP und regionale Datenpiloten implementiert; vollständige regionale Datengrundlage und Kalibrierung offen. Unternehmenstouren: Import, Kontext und zusätzliche native SUMO-Ausführung implementiert (Abschnitt 28).**
Bezug: Deutschland; Pilotvorschlag Rastatt/Karlsruhe mit Bundesfernstraßen und
Gewerbestandorten. Die Methodik soll anschließend auf andere Regionen übertragbar sein.

## 1. Ausgangslage vor dieser Erweiterung

SyntheSis enthält bereits einen Teil des Güterverkehrs: Paketbestellungen,
Verteilzentren, Packstationen sowie Zustelltouren und Retouren über logiTopp.
`LogisticsGenerator` schreibt die Eingaben; `SumoRunner.readDeliveryLegs` übernimmt
`planned-tours.csv` einschließlich ergänzter Depot-Rückfahrten in SUMO.

Der Name `lkw` ist dabei missverständlich: Der Standardexport verwendet
`vClass="delivery"` und `HBEFA3/LDV_D_EU4`, also eine leichte Lieferfahrzeugklasse.
`VehicleCatalog` erlaubte zuvor nur die Straßenklassen `passenger`, `delivery`
und `bicycle`; ein Import schwerer LKW war dadurch noch nicht vorgesehen.
Der vorhandene Fahrzeugimport verteilt Typen auf bestehende Fahrten und erzeugt
keine zusätzliche Güterverkehrsnachfrage.

Es fehlte ein allgemeines Modell für schwere LKW, Sattelzüge, industrielle
Transporte, Handelsbelieferung und regionalen Durchgangsverkehr. Gewerbliche
Servicefahrten mit PKW/Lieferwagen sind ein zusätzliches, getrennt abzugrenzendes Thema.
Ein Güterverkehrsanteil an Einwohnerwegen würde diese Lücke fachlich nicht schließen.

Codebelege:

- `backend/src/main/java/de/synthesis/generation/pipeline/LogisticsGenerator.java`
- `backend/src/main/java/de/synthesis/generation/pipeline/SumoRunner.java`, insbesondere
  `readDeliveryLegs`, `writeTripsXml`, `run`
- `backend/src/main/java/de/synthesis/vehicle/VehicleCatalog.java`
- `backend/src/main/java/de/synthesis/vehicle/SumoVehicleTypes.java`
- `backend/src/main/java/de/synthesis/generation/GenerationService.java`, `stageSumo`

## 2. Empfehlung und Modellgrenze

Empfohlen wird ein **eigenständiger, datenbasierter Güterverkehrsbaustein**, dessen
Fahrten zusammen mit Personenverkehr und bestehender Paketlogistik im selben
SUMO-Netz simuliert werden. Die räumliche Grundnachfrage stammt möglichst aus
beobachteten Maut-Verkehrsbeziehungen; fehlende Beziehungen werden mit einer
amtlichen Güterverkehrsmatrix und Standortinformationen ergänzt. Verkehrszählungen
liefern Tagesprofile und unabhängige Belastungsreferenzen.

Die Beiträge werden eindeutig getrennt:

| Beitrag | Erzeugung | Geplante Rolle |
|---|---|---|
| Personenverkehr | bestehende Bevölkerung, Aktivitäten und Moduswahl | vorhandene Nachfrage |
| Paket-Zustellung und Retouren | bestehendes logiTopp | vorhandene Touren |
| Allgemeiner schwerer Güterverkehr | neuer Baustein | zuerst umsetzen |
| Sonstige leichte Warenbelieferung | späteres Gewerbe-/Tourenmodell | zweite Ausbaustufe |
| Personenwirtschaftsverkehr | spätere Service-/Betriebsmodellierung | eigene Erweiterung |

Beim schweren Güterverkehr müssen Binnenverkehr, ausgehender Quellverkehr,
eingehender Zielverkehr und Durchgangsverkehr abgebildet werden. Gerade auf einer
Autobahn kann ein großer Teil der LKW weder Quelle noch Ziel im Untersuchungsgebiet
haben. Diese Fahrzeuge entstehen deshalb nicht aus der lokalen Bevölkerung.

## 3. Recherchierte Datenquellen

### 3.1 Priorisierte Quellen

| Quelle | Öffentlich angebotene Inhalte | Verwendung | Zugang und Grenzen |
|---|---|---|---|
| **Toll Collect/BALM: Lkw-Verkehrsportal** | Abschnittsbefahrungen, Fahrzeugparameter und Start-Ziel-Beziehungen zwischen Gemeinden; CSV-Export | erste Wahl für aktuelle LKW-Mengen und räumliche Beziehungen | kostenfreies Open-Data-Angebot; Katalog nennt DL-DE-BY 2.0; keine flächendeckenden Betriebsadressen oder Fahrzeugtrajektorien |
| **BALM: Mautdaten Bund und Mauttabelle** | monatliche Daten je Tarifabschnitt; Klassen nach Gewicht, Achsen und Emissionen; Abschnittsreferenzen | längerfristige Belastungs- und Flottenreferenz; räumliche Verknüpfung | BALM nennt grundsätzlich DL-DE-BY 2.0; Format und jeweilige Datenversion beim Import prüfen |
| **BASt: automatische Dauerzählstellen** | Stundenwerte an Bundesfernstraßen und Zählstellenmetadaten; Rohdaten und aufbereitete Angebote | LKW-Tagesprofile, Klassenvergleich und unabhängige Validierung | öffentlicher Datenkatalog/Mobilithek; Lizenz am konkreten Angebot prüfen; Rohdaten enthalten mögliche Mess- und Formatfehler |
| **MobiData BW: Stundenwerte der Dauerzählstellen** | CSV, Fahrstreifen und Fahrzeugarten; Archiv ab 2016 | gut dokumentierter Pilotzugang für Rastatt/Karlsruhe | direkte Downloadressourcen, DL-DE-BY 2.0 |
| **BMV: Verkehrsprognose 2040** | Güterverkehrs-Verflechtungsmatrizen für 2019 und 2040, NUTS3 bzw. VP-Verkehrszellen; Zellgeometrien | Ergänzung für Fern-, Außen- und fehlende Verkehrsbeziehungen | BMV verlinkt Mobilithek-Angebote; konkrete Lizenz, Downloadbedingungen und Dateischemata vor Einsatz prüfen |
| **KBA: Güterkraftverkehrsstatistik** | Last-/Leerfahrten, Kilometer, Gütermenge und Strukturmerkmale | Beladungs- und Leerfahrtenannahmen, Distanzplausibilität | öffentliche Aggregate; beispielhafter Leerfahrten-Datensatz als CSV mit DL-DE-BY 2.0; Statistik deutscher Fahrzeuge nicht mit territorialem Gesamtverkehr gleichsetzen |
| **KBA-FDZ: europäischer LKW-Verkehr (VE)** | anonymisierte regionale Transportbeziehungen deutscher und ausländischer Fahrzeuge laut KonsortSWD | ergänzender Kandidat für Außen-/Transitbeziehungen | öffentliches Downloadangebot laut FDZ-Verzeichnis; konkrete Dateien, Lizenz, Aktualität und Schema noch nicht direkt geprüft |
| **Statista: Straßengüterverkehr / Nutzfahrzeuge** | Themenübersichten zu Transportleistung, Fahrzeugbestand und Antrieben | Quellenfinder und Kontext | Detailstatistiken teilweise Premium/registrierungspflichtig; keine offene regionale OD-Grundlage |
| **Eurostat: Straßengüterverkehr** | regionale und internationale Aggregate, Tonnen, Tonnenkilometer und Fahrten | Plausibilisierung und Außenbeziehungen | öffentliche Tabellen; grobe räumliche Auflösung und statistische Abdeckung beachten |
| **OpenStreetMap** | Netz, Gewerbegebiete, Handels-/Logistikstandorte und Zugangsinformationen | kleinräumige Zuordnung und fahrzeugabhängiges Routing | ODbL; unvollständige Standorte und LKW-Beschränkungen; keine beobachteten Liefermengen |
| **SEVAS / NRW.Mobidrom** | kommunale LKW-Restriktionen, Vorrangrouten, Zonen und eingeschränkte Verkehrszeichendaten mit OSM-Referenzen | ergänzende Routinggrundlage für NRW | öffentlicher WFS, laut Open.NRW DL-DE-Zero 2.0; vollständige Schilderdaten IP-beschränkt; keine Nachfragemengen |
| **BA/Regionalstatistik** | Beschäftigung am Arbeitsort; zusätzliche Branchenaggregate je verfügbarer Raumebene | Gewichte für die räumliche Aufteilung auf Gewerbestandorte | veröffentlichte Aggregate; Geheimhaltung und unterschiedliche räumliche Auflösungen; keine öffentliche vollständige Betriebsdatei |

Direkte Quellen und Zugänge:

- [Toll Collect: aktueller Funktionsumfang des Lkw-Verkehrsportals](https://company.toll-collect.de/de/produkte-loesungen/daten-geoinformationssysteme/lkw-verkehrsportal/)
  und [Portal](https://lkw-verkehrsportal.de/).
- [Amtlicher Katalogeintrag zum Portal einschließlich Lizenz](https://data.gov.de/suche/daten/app-lkw-befahrungen?ids=fb55bab9-d1e7-4e7f-a05b-0c6b93b48501).
- [BALM: Mautdaten Bund](https://www.balm.bund.de/DE/Themen/Lkw-Maut/Mauteinnahme/mauteinnahmen.html)
  und [Mauttabelle](https://www.balm.bund.de/DE/Themen/Lkw-Maut/Mauttabelle/mauttabelle_node.html).
- [BASt-Rohdaten: amtliche Datensatzbeschreibung im Katalog](https://www.govdata.de/suche/daten/automatische-dauerzahlstellen-rohdaten?ids=0051a14c-bd13-433c-96f4-1fd7731c8b8a&ids=94b96cf4-8c75-4f70-bffa-80315c54cfad&ids=f8df6f16-ca45-412a-bb7a-c7af6b6f8de8)
  und [aufbereitete Zählstellen-Daten in der Mobilithek](https://mobilithek.info/offers/573280210227470336).
- [MobiData BW: Stundenwerte und Downloadressourcen](https://mobidata-bw.de/dataset/stundenwerte_dauerzaehlstellen).
- [BMV: Verkehrsprognose 2040, Daten und Methodenberichte](https://www.bmv.de/SharedDocs/DE/Artikel/G/verkehrsprognose-2040.html);
  Gütermatrix 2019 auf [NUTS3](https://mobilithek.info/offers/859846121350729728)
  und [VP-Verkehrszellen](https://mobilithek.info/offers/880062743587840000).
- [BMV: Abdeckung und Inhalte der KBA-Güterkraftverkehrsstatistik](https://www.bmv.de/SharedDocs/DE/Artikel/G/amtliche-gueterkraftverkehrsstatistik.html)
  und [KBA-Beispieldatensatz Leerfahrten 2023](https://www.govdata.de/suche/daten/vd-1b-2023-verkehr-deutscher-lastkraftfahrzeuge-leerfahrten-fahrzeugbezogen?ids=5bf791da-b06b-4d3f-a5ab-8c56ed523a94).
- [Eurostat: Straßengüterverkehr nach Entladeregion](https://ec.europa.eu/eurostat/databrowser/view/road_go_ta_ru/default/table?lang=EN).
- [OpenStreetMap: Lizenz und Namensnennung](https://www.openstreetmap.org/copyright).
- [Bundesagentur für Arbeit: Beschäftigungsstatistik und Gemeindedaten](https://statistik.arbeitsagentur.de/DE/Navigation/Statistiken/Fachstatistiken/Beschaeftigung/Beschaeftigte/Beschaeftigte-Nav.html).

### 3.2 Wichtiger aktueller Befund: Mautdaten enthalten inzwischen Verkehrsbeziehungen

Die aktuelle Toll-Collect-Seite beschreibt drei Ansichten: tägliche Befahrungen
für die letzten **365 Tage**, Monats-/Jahresauswertungen nach Fahrzeugparametern
und seit **Juni 2026** Start-Ziel-Beziehungen auf Gemeindeebene. Export ist als CSV
vorgesehen. Eine ältere BALM-Seite nennt noch 60 Tage; für die Planung ist die
aktuelle Betreiberbeschreibung maßgeblich. [Quelle: Toll Collect](https://company.toll-collect.de/de/produkte-loesungen/daten-geoinformationssysteme/lkw-verkehrsportal/)

Die Start-Ziel-Ansicht zählt **Mautfahrten**. Die Veröffentlichung zur Einführung
nennt Gemeinden mit direktem Anschluss an Autobahnen/Bundesstraßen. Daraus darf
ohne Prüfung der Datenmethodik keine vollständige Betrieb-zu-Betrieb-Matrix
abgeleitet werden. [Quelle: Toll-Collect-Pressemitteilung vom 18.06.2026](https://cache.pressmailing.net/content/429a78a1-da27-488c-873d-c8ee5fb5ff5e/Lkw-Verkehrsportal_v1.0.pdf)

### 3.3 Ergänzende Quellen und Zugangsbeschränkungen

**KiD 2010** ist für leichte Nutzfahrzeuge, Touren und Wirtschaftsverkehr fachlich
interessant. Ein DLR-Bericht belegt den früheren Bezug über die Clearingstelle;
ein aktueller frei automatisierbarer Mikrodatenzugang wurde in dieser Recherche
nicht bestätigt. Daher nur optional, nach Prüfung des heutigen Zugangs und der
Nutzungsrechte. Das Erhebungsjahr begrenzt die Übertragbarkeit auf heutige
Paketlogistik. [DLR-Bericht zum Datenbezug](https://elib.dlr.de/96491/1/Ergebnisbericht_E-Nutzer_2015.pdf)

Kommunale Zählungen, Landes-Straßenverkehrszählungen und Hafen-/Terminalstatistiken
sind regionsbezogene Ergänzungen. Für ein konkretes Gebiet müssen Maschinenlesbarkeit,
Fahrzeugklassen und Bezugsjahr separat geprüft werden. Öffentliche Branchenberichte
können Mengen plausibilisieren, ersetzen aber keine regionale Fahrtenmatrix.

**Recherchegrenze der ersten Recherche (10.10.2026):** Verifiziert wurden veröffentlichte Angebote und deren
Beschreibungen. Es wurden keine vollständigen Datenpakete importiert und keine
automatische API-Verfügbarkeit oder regionalen Trefferzahlen bewiesen. Besonders
bei der Mobilithek waren Angebotsdetails im Textabruf nicht vollständig lesbar.
Ein erster Pilot darf deshalb mit dokumentiertem CSV-Download/Upload starten.

### 3.4 Ergänzung: KBA-Statistik, FDZ-Angebote und Zugang (2026-10-10)

Die vom Nutzer genannten KBA-Zugänge sind getrennt zu prüfen:

| Angebot | Rolle im Konzept | Verifikation / Zugang |
|---|---|---|
| [Veröffentlichte Statistik deutscher LKW](https://www.kba.de/DE/Statistik/Kraftverkehr/deutscherLastkraftfahrzeuge/verkehrdeutscher_node.html) | Struktur- und Plausibilitätsreferenz | am 2026-10-12 im Browser erreichbar; Seite nennt XLSX/PDF und gebührenfreie maschinenlesbare PUF |
| [FDZ: Verkehr deutscher LKW (VD)](https://www.kba.de/DE/Statistik/Forschungsdatenzentrum/Datenangebot/Verkehr_deutscher_Lastkraftfahrzeuge/verkehr_deutscher_Lkw_node.html) | hochgerechnete veröffentlichte Ergebnisse als Public Use Files | am 2026-10-12 im Browser erreichbar; PUF 2025 ausdrücklich verlinkt; ZIP am 2026-10-12 importiert: elf CSV; Schema und Qualitätsmarker geprüft, Lizenz und Codebuch-Zuordnung noch offen |
| FDZ: europäischer LKW-Verkehr (VE) | ergänzende regionale Verflechtungen, Außen-/Transitverkehr | öffentlich anonymisiertes Downloadangebot laut KonsortSWD, noch kein Dateipaket geprüft |
| Eurostat ERFT Scientific Use File | vertiefte Forschungsanalyse | anerkannte Forschungseinrichtung und Forschungsvorhaben erforderlich |

Die amtliche Statistik deutscher Fahrzeuge erfasst Last- und Leerfahrten sowie
Fahrzeug- und Ladungsmerkmale; im Ausland zugelassene Fahrzeuge fehlen.
Erhebungsgrenze: mehr als 3,5 t **Nutzlast**, bei unbekannter Nutzlast mehr als
6 t Gesamtmasse, sowie Sattelzugmaschinen. Diese Definition unterscheidet sich
von der Mautgrenze. Last-/Leerkilometer und Fahrtenquoten haben verschiedene
Nenner; Tonnenkilometer sind keine Fahrzeugmengen. Forschungs-Mikrodaten über
Eurostat benötigen eine Zulassung. [BMV: Methodik und Forschungszugang](https://www.bmv.de/SharedDocs/DE/Artikel/G/amtliche-gueterkraftverkehrsstatistik.html)

Das FDZ-Verzeichnis beschreibt VE zusätzlich als öffentlichen, absolut anonymisierten
Download: Transportvorgänge deutscher und ausländischer Fahrzeuge in, von, nach
und durch Deutschland, mit Be-/Entladeregion, Wegstrecke, Gütergewicht und Güterart;
regionale Aggregate ab 2010. **Dieses Angebot ist nicht mit den veröffentlichten VD-Public-Use-Files
oder dem Eurostat-SUF gleichzusetzen.** [KonsortSWD: FDZ-KBA-Datenangebot und Zugang](https://www.konsortswd.de/angebote/forschende/alle-datenzentren/fdz-kba/)

**Abgeleitete Modellierungsentscheidung:** KBA-Aggregate zur Prüfung von Leerfahrten,
Distanzen und Flottenstruktur verwenden; VE als weitere großräumige Matrixquelle prüfen.
Eine deutschlandweite Quote nicht unmittelbar auf jeden regionalen LKW-Strom übertragen.
Beobachtete Maut-Fahrten enthalten bereits leere Fahrzeuge: kein zusätzlicher pauschaler
Leerfahrtenaufschlag. Forschungsdaten bleiben optional, damit der offene Basispfad ohne
institutionelle Zulassung funktioniert. Vor Auswahl von VE müssen Datei, Lizenz,
Erhebungsjahr, Einheit, Hochrechnung und Raumauflösung tatsächlich geprüft werden.

**Erneute Prüfung (2026-10-12):** Alle sechs vom Nutzer genannten Seiten ließen sich
im lokalen Browser öffnen. Das Recherchewerkzeug meldete für KBA weiterhin HTTP 503,
für Statista HTTP 403 und für den ursprünglichen SUMO-Link einen Cache-Fehler.
Diese Meldungen belegen keine allgemeine Nichterreichbarkeit der Websites.
Die KBA-FDZ-Seite nennt für VD ausdrücklich Public Use Files und verlinkt das Jahr 2025.
Es handelt sich laut Beschreibung um hochgerechnete Ergebnisse mit Qualitätskennzeichnung;
kleine bzw. ungenaue Ergebnisse können unterdrückt sein. Dies ist kein Nachweis frei
verfügbarer individueller Fahrzeugtrajektorien. Dateien wurden in dieser Prüfung nicht
heruntergeladen. [KBA: VD-Public-Use-Files](https://www.kba.de/DE/Statistik/Forschungsdatenzentrum/Datenangebot/Verkehr_deutscher_Lastkraftfahrzeuge/verkehr_deutscher_Lkw_node.html)

### 3.5 Ergänzung: Rolle von Statista (2026-10-10)

Die beiden Themenübersichten werden als Quellenfinder aufgenommen:
[Straßengüterverkehr](https://de.statista.com/themen/1171/strassengueterverkehr/) und
[Nutzfahrzeuge/LKW](https://de.statista.com/themen/735/lastkraftwagen-lkw/).
Die erste führt unter anderem Transportmengen, Fahrleistungen und Leerfahrten;
die zweite Fahrzeugbestand, Gewichtsklassen und Antriebe. Die LKW-Abgrenzung der
Übersicht umfasst auch leichte Fahrzeuge. Detailzugänge sind teilweise an Registrierung
oder Premium gebunden; ein freier Datenexport wurde nicht nachgewiesen.
[Statista: Report mit Kennzeichnung der Detailstatistiken](https://de.statista.com/statistik/studie/id/12452/dokument/strassengueterverkehr-deutschland-statista-dossier/)

Für SyntheSis folgt daraus: angegebene Originalquellen auffinden und deren Datenprodukte
verwenden. Fahrzeugbestand ersetzt keine Fahrtenmatrix. Anteile an Tonnenkilometern
ersetzen keinen LKW-Anteil am Straßenverkehr. Flottenanteile nach Bestand sind außerdem
von fahrleistungsgewichteten Emissionsanteilen zu unterscheiden. Diese Übersichten
begründen keine lokale Schwerverkehrsquote und werden nicht als automatische Eingabequelle
verwendet. Die Rechercheabrufe lieferten HTTP 403; beide Themenübersichten waren am
2026-10-12 im lokalen Browser lesbar. Detaildaten hinter Zugangsschranken wurden nicht übernommen.

## 4. Wie aus diesen Daten zusätzliche Fahrten entstehen

### Schritt 1: Gemeinsamen Bezugsrahmen festlegen

Ein Bezugsjahr, repräsentative Monate, Modellwochentag, Tageszeit und
Fahrzeugklassen festlegen. Ein durchschnittlicher Jahres-DTV ist nicht automatisch
ein Montag außerhalb der Ferien. Maut-, Zähl- und Modellwerte nur für kompatible
Zeiträume vergleichen. Baustellen, Feiertage und Datenlücken kennzeichnen.

Seit 01.07.2024 gilt die Mautpflicht grundsätzlich für Fahrzeuge über 3,5 t
technisch zulässiger Gesamtmasse, mit Ausnahmen. Lieferwagen bis 3,5 t werden
dadurch nicht erfasst. Zeitreihen über diesen Stichtag brauchen harmonisierte
Klassen. [Toll Collect: Mautgrenze und Stichtag](https://www.toll-collect.de/de/toll_collect/service/fragen___antworten/mautaenderungen_2023_und_2024/maut_ueber_3_5_tonnen/p1745_3_5_tonnen.html)

Mautgewichtsklasse, Nutzlast und Zählklassen sind verschiedene Größen.
Insbesondere enthält der Schwerverkehr in den BW-Zähldaten auch Busse.
Für die Güterverkehrsreferenz dort `LoA + LmA + Sat` verwenden, sofern die
Klassenqualität ausreicht; Lieferwagen separat führen.
[MobiData BW: Klassendefinition](https://mobidata-bw.de/dataset/stundenwerte_dauerzaehlstellen)

### Schritt 2: Großräumige Nachfrage und Netzrand modellieren

Maut-Start-Ziel-Beziehungen für das Gebiet und Umland als beobachtete Ausgangsbasis
verwenden. Abdeckung, Unterdrückung kleiner Zellen, Zeitbezug und Bedeutung von
Start/Ziel vorab prüfen. VP2040 ergänzt unvollständige Beziehungen und liefert
Gütergruppen bzw. Fernverkehrsstruktur. **Beide Quellen werden abgeglichen,
nicht einfach addiert.** Die 2019er Matrix ist eine historische Ausgangsmatrix;
2040 ist ein Zukunftsszenario, kein heutiger Messzustand.

Die VP-Methodenberichte beschreiben Güteraufkommen in Tonnen und Territorialleistung
in Tonnenkilometern. Falls das konkrete Datenangebot keine Fahrzeugfahrten
enthält, ist eine Umrechnung nötig. [BMV-Methodenbericht](https://www.bmv.de/SharedDocs/DE/Anlage/G/BVWP/verkehrsprognose-2040-band-6-2-m-verkehrsentwicklungsprognose-methodenbericht.pdf?__blob=publicationFile)

Für eine Quelle-Ziel-Beziehung `i → j`, Gütergruppe `g` und Fahrzeugklasse `k`:

```text
beladene Fahrten/Jahr = Tonnen/Jahr / mittlere transportierte Tonnen je Lastfahrt
mittlere Tonnen je Lastfahrt = Nutzlastkapazität × mittlere Auslastung
```

Gütergruppenanteile und Fahrzeugklassenmix separat berücksichtigen; anschließend
über einen dokumentierten Jahres-/Wochentagskalender auf Tage und Stunden verteilen.
Tonnenkilometer allein bestimmen keine eindeutige Fahrzeugnachfrage.
Transportketten dürfen nicht mehrfach als dieselbe Straßenfahrt gezählt werden.

Leerfahrten nur ergänzen, wenn die Ausgangsdaten sie nicht bereits enthalten.
Bei einem fahrtenbezogenen Leeranteil `e` gilt vereinfacht
`alle Fahrten = Lastfahrten / (1 - e)`. Ein kilometerbezogener Leeranteil ist
dafür ungeeignet. Maut-Befahrungen enthalten auch leere mautpflichtige LKW;
dort wäre ein pauschaler Leerfahrtenaufschlag eine Doppelzählung.
[Toll Collect: Beladung und Mautpflicht](https://www.toll-collect.de/de/toll_collect/rund_um_die_maut/mautpflichtige_fahrzeuge/mautpflichtige_fahrzeuge.html)

Außenbeziehungen auf einem gröberen Umland-/Fernnetz routen und an den Rand des
gepufferten SUMO-Netzes schneiden. Daraus entstehen gerichtete Ein-/Ausfahrtspunkte.
Durchgangsverkehr wird als zusammenhängende Rand-zu-Rand-Fahrt übernommen.
Sämtliche Außenbeziehungen als attraktive Ziele der lokalen Personenbevölkerung
anzulegen würde die Güterverkehrsmechanik nicht abbilden.

### Schritt 3: Kleinräumig verteilen

Innerhalb einer Gemeinde bzw. VP-Zelle die Mengen nach Gütergruppe auf
Industrieflächen, Lager, Handel, Baustoffstandorte und Terminals aufteilen.
OSM-Flächen und POIs sind Standortindikatoren; Beschäftigungsaggregate ergänzen
die Gewichtung. Explizite große Verkehrserzeuger haben Vorrang vor einer
gleichmäßigen Verteilung auf alle Arbeitsplätze.

Gewichte innerhalb jeder übergeordneten Zelle normieren: Die räumliche Aufteilung
muss die ursprüngliche Menge erhalten. Wohnbevölkerung allein ist kein
geeigneter Indikator für schwere Gütertransporte. Lokaler Verkehr ohne
Mautnetzberührung benötigt zusätzliche Zähldaten oder ausgewiesene Annahmen.

### Schritt 4: Zeit und Fahrzeuge zuweisen

Für den Einstieg Klassen `deliveryVan`, `rigidTruck` und `articulatedTruck`
fachlich unterscheiden. Gewichts-/Achsklassen dienen als Indikatoren;
sie identifizieren nicht immer eindeutig die Bauform.

Stündliche Profile aus vergleichbaren Zählstellen nach Straßen-/Gebietstyp,
Fahrzeugklasse und Wochentag ableiten. Bei einer aus Monatsdaten bestimmten
repräsentativen Tagesmenge `D` und Stundenanteilen `p_h` gilt
`Fahrten_h = D × p_h`, mit `Summe(p_h) = 1`.
Stochastische Abfahrten hängen an einem separat abgeleiteten Szenario-Seed.

Im MVP können schwere LKW zunächst einzelne OD-Fahrten bilden. Dies erlaubt
Netzbelastungsanalysen, aber noch keine Aussagen über Flottenauslastung,
Disposition oder Lenkzeiten. Später folgen zusammenhängende Fahrzeugeinsätze,
Beladung, Leer-Rückfahrten, Ladezeiten und Lieferfenster. Reale Stopps benötigen
zulässige Halte-/Ladeflächen statt beliebiger Stopps auf Fahrstreifen.

### Schritt 5: An beobachtete Belastungen anpassen

Nach der Netzumlegung die simulierten LKW je Abschnitt, Richtung und Zeitklasse
mit Maut-/Zählwerten vergleichen. Mit begrenzten Skalierungsparametern je
Beziehungsgruppe/Klasse beginnen; keine freie Anpassung tausender OD-Zellen an
wenige Messstellen. Eine mögliche spätere Zielfunktion lautet:

```text
gewichtete Belastungsabweichung
+ Regularisierung der Abweichung von der Ausgangsnachfrage
```

Gewichte berücksichtigen Messqualität und vergleichbare Fahrzeugabdeckung.
Zählwerte auf aufeinanderfolgenden Straßenabschnitten sind keine unabhängigen
Fahrten: Ein LKW kann mehrere Messabschnitte passieren. Aus einer Abschnittssumme
entsteht deshalb nicht direkt eine regionale Anzahl eindeutiger LKW.

## 5. Rolle von Quoten

Eine beobachtete Quote eignet sich als lokale Belastungsreferenz und als
gekennzeichnete Ersatzannahme. Sie muss denselben Ort, Zeitraum, dieselbe Richtung
und dieselbe Fahrzeugabgrenzung betreffen.

Bei `q = LKW / (PKW + LKW)` und einem vollständigen PKW-Fahrzeugwert `P` gilt:

```text
LKW = P × q / (1 - q)
```

Reines Rechenbeispiel: `P = 9.000 Fahrzeuge/Tag`, `q = 10 %` ergibt
`1.000 LKW/Tag`. Das sind keine recherchierten Ortswerte.
Bezieht sich die Quote auf alle Kfz einschließlich Bus, Motorrad und Lieferwagen,
gilt diese Zweiklassenformel nicht. Personenwege inklusive Mitfahrern sind ebenfalls
kein PKW-Fahrzeugwert.

Eine Quote erzeugt keine Quellen, Ziele oder Touren. Daher kein pauschaler
bundesweiter LKW-Prozentsatz auf alle Personenwege. Quoten nach Straßenklasse,
Gebiet und Tageszeit dürfen fehlende Messungen überbrücken; die Herkunft und
Unsicherheit bleiben im Ergebnis sichtbar.

## 6. Konkrete Integration in SyntheSis

Vorgeschlagener Datenfluss:

```text
Maut-Verkehrsbeziehungen / VP-Matrix ─┐
Standorte / Netzrand / Zählprofile ──┴─> Güterverkehrsfahrten ─┐
Personennachfrage ──────────────────────────────────────────┼─> SUMO
logiTopp-Pakettouren ────────────────────────────────────────┘
```

Umsetzung außerhalb der KIT-Legacy-Komponenten:

1. **`FreightDemandGenerator` als eigener fachlicher Baustein.** Vorbereiten der
   Quellen und räumlichen Gewichte im Logistikabschnitt; konkrete Nachfrage vor
   dem gemeinsamen SUMO-Lauf erzeugen. Im ersten Schritt keine neue Pipeline-Stufe
   erzwingen. Wochen- und SUMO-Tagesbezug ausdrücklich vereinbaren.
2. **Neue Szenariokonfiguration `freight`, zunächst `enabled=false`.** Datenmodus,
   Bezugsjahr/-tag, Klassen, lokale Ersatzannahmen und Mengenskalierung festhalten.
   Backend und Frontend spiegeln die Konfiguration. Betriebs-URLs und Cachepfade
   bleiben in `SynthesisProperties`.
3. **`VehicleCatalog` und `SumoVehicleTypes` erweitern.** Schwere Typen, Rollen und
   explizite SUMO-Zuordnungen `truck`/`trailer` zulassen. Länge, Beschleunigung,
   Zeitabstand, Geschwindigkeit und Emissionsklasse je Typ definieren.
   [SUMO: fahrzeugklassenspezifische Parameter](https://sumo.dlr.de/docs/Vehicle_Type_Parameter_Defaults.html)
4. **`SumoRunner` um zusätzliche Nachfrage erweitern.** Güterfahrten in denselben
   Routing-/Simulationslauf einfügen. Den Netzimportfilter prüfen: aktuell
   `passenger,bicycle,pedestrian`; neu benötigte LKW-Verbindungen dürfen nicht
   verschwinden. Schwerfahrzeugrouten benötigen eigene Zugangsmasken und
   klassengefilterte Start-/Zielkanten. Höhen-/Gewichtsrestriktionen und
   `hgv:conditional` nicht ungeprüft als vollständig von SUMO unterstützt ansehen.
5. **Bestehende gemeinsame GraphHopper-Routen prüfen.** Der optionale Adapter wurde
   für bisherige Klassen gebaut. Für schwere LKW entweder ein verifiziertes Profil
   ergänzen oder den dokumentierten SUMO-Routingpfad verwenden.
6. **Nachfragerollen und Doppelzählung sichern.** Jede Fahrt trägt ihren Ursprung.
   Falls schwere Pakettouren bereits existieren, müssen diese bei der Anpassung
   an Gesamt-LKW-Zählungen berücksichtigt werden. Paket-Ferntransporte zu Depots
   sind gesondert von der vorhandenen letzten Meile abzugrenzen.
7. **Bestehende SUMO-Abhängigkeit erhalten.** `stageSumo` braucht heute
   `demandsimulationResult.csv`. Ein eigenständiger reiner Güterverkehrslauf wäre
   eine spätere Änderung dieses Vertrags; im MVP läuft die Ergänzung zusammen
   mit der bestehenden logiTopp-/Personenpipeline.

Vorgeschlagene neue Artefakte, jeweils mit versioniertem Vertrag:

| Datei unter `artifacts/freight/` | Inhalt |
|---|---|
| `sources.json` | URLs, Anbieter, Lizenz, Datenstand, Downloads und Hashes, Qualitätsstatus |
| `connectors.csv` | gerichtete Netzrandzugänge und Zuordnung zu Außenbeziehungen |
| `od.csv` | Herkunft/Ziel, Zeitklasse, Fahrzeugklasse, Fahrtenzahl, Quellenkennung |
| `trips.csv` | eindeutige Fahrt-ID, Typreferenz, Abfahrt, konkrete Punkte/Kanten und Nachfrageursprung |
| `calibration-report.json` | beobachtete/simulierte Belastungen, Faktoren, Prüfmenge und Restabweichung |

KPI-Erweiterungen unterscheiden Fahrzeuganzahl, Fahrten, Fahrzeugkilometer,
Leer-/Lastfahrten und Personenwege. Den Personen-Modal-Split nicht um LKW erweitern.
Emissionswerte für Güterverkehr getrennt und kombiniert ausweisen; der bisherige
EU4-Lieferwagenstandard ist keine aktuelle Schwerlast-Flottenreferenz.

**Stichproben sind eine zentrale Voraussetzung:** Gemessene Vollverkehrs-LKW
nicht mit einer 5-%-Personenstichprobe als repräsentativen Stauzustand simulieren.
Für Belastungsanalysen vollständige Fahrzeugnachfrage verwenden oder eine
explizit validierte Skalierung sämtlicher Beiträge und Netzkapazitäten vorsehen.
Nachträglich hochgerechnete KPI-Gewichte stellen Stau nicht wieder her.
Ebenso müssen vorhandene SUMO-Trip-Caps berücksichtigt werden.
Fehlender externer PKW-/Durchgangsverkehr bleibt ein eigener Fehler des Gesamtmodells.

Ein Güterverkehrs-Seed muss stabil vom Szenario-Seed abgeleitet werden, ohne den
Zufallsstrom der vorhandenen Personenpipeline zu verschieben. OID-/externe
Zonen-IDs bleiben explizit getrennt. Downloads werden als versionierte
Quellensnapshots gesichert. Fehlende Daten führen zu einem ausgewiesenen
synthetischen Modus oder einer klaren Fehlermeldung; beobachtete Daten dürfen
nicht still durch erfundene Werte ersetzt werden. Neue Artefakte werden im
Manifest verzeichnet.

Die bestehende PKW-Zeitfeedbackschleife kann später Belastungsänderungen durch
LKW an die Personennachfrage zurückgeben. Dabei bleiben Gütermengen zunächst
fest; eine Änderung der LKW-Routenwahl ist eine eigene Rückkopplung.
MobileCity erhält im MVP höchstens separate Aggregate. Ein nativer
Güterverkehrsexport braucht eine eigene Prüfung des Zielmodellvertrags.

### SUMO-Ausbau: Güterobjekte und Umschlag statt ausschließlich Fahrzeugfahrten

Die verlinkte [SUMO-Logistikdokumentation](https://sumo.dlr.de/docs/Specification/Logistics.html)
beschreibt `container` und `containerStop` für Güter und Umschlagorte.
`containerStop` liegt auf einer Lane, wird über `additional-files` eingebunden und
von Fahrzeugen mit expliziten Stopps angefahren. Über `device.container.loadedType`
können An-/Abkuppeln sowie Änderungen von Länge, Masse und Fahrzeugklasse abgebildet
werden; eine geänderte Klasse kann Neurouting auslösen. Das ist eine dokumentierte
Simulationsfähigkeit, noch keine in SyntheSis getestete Integration.

**Folgerung für die Ausbaureihenfolge:** Der aktuelle OD-MVP bleibt die Basis für
Netzbelastung. Danach werden zusammenhängende Fahrzeugeinsätze mit Lade-/Entladestopps
geprüft; erst anschließend explizite Güterobjekte und gegebenenfalls Terminalumschlag.
Ein Behälter ist dabei ein Transportobjekt, nicht automatisch eine zusätzliche
Fahrzeugfahrt. Nachfrage, Touren und Disposition müssen weiter außerhalb dieses
SUMO-Mechanismus erzeugt werden. Die Kategorien `private`/`logistics` beziehen sich
weiterhin auf Fahrzeuge und Nachfragequellen.

Vor Integration ist ein nativer Spike vorgesehen: ein Güterobjekt laden, transportieren,
entladen; Fahrzeug-, Container- und Mengenbilanz einschließlich fehlgeschlagener
Beladung und Simulationsende prüfen. Parameter für Ladezeiten, Kapazitäten, Beladung
und Halteflächen brauchen eine eigene Datenbasis. Der aktuelle CSV-Vertrag wird
nicht stillschweigend zu einem Behälter- oder Tourenvertrag umgedeutet.

## 7. Umsetzung in überprüfbaren Schritten

| Phase | Ergebnis | Voraussetzung für den nächsten Schritt |
|---|---|---|
| 0: Datenpilot | Portal-CSV, eine Zählstellenserie, Klassenzuordnung und Netzrandzugänge für Rastatt/Karlsruhe | Download/Lizenz/Schema geprüft; Herkunft/Ziel-Semantik erklärt; regional ausreichende Abdeckung festgestellt |
| 1: Technischer Spike | PKW, vorhandene Pakettouren, schwere Solo-LKW und Sattelzüge in einem SUMO-Lauf | komplette Import-Routing-Simulationskette; verbotene Verbindung wird ausgeschlossen; kein stiller Fahrzeugverlust |
| 2: MVP-Nachfrage | beobachtungsbasierte OD-Fahrten einschließlich Außen- und Durchgangsverkehr, Stundenprofile | vollständige Mengenbilanz und getrennte KPIs; Stichproben-/Cap-Problem gelöst |
| 3: Kalibrierung | begrenzte Anpassung der Nachfrage an kompatible Maut-/Zählwerte | Güte auf zurückgehaltenen Messstellen und Zeiträumen bestätigt |
| 4: Touren und Szenarien | Handels-/Industriebelieferung, Ladezeiten, Flotten, ggf. Elektro-LKW | belastbare lokale Parameter und fahrzeug-/tourenkonsistente Abläufe |

Vor Phase 1 die Messkriterien festschreiben. Vorschlag für den Pilot,
**noch keine nachgewiesenen Ergebnisse oder allgemeingültigen Güteschwellen**:

- Alle Nachfragefahrten sind bilanziert: exportiert, absichtlich ausgeschlossen
  oder mit konkretem Routing-/Simulationsfehler ausgewiesen. Keine verdeckten Verluste.
- Deterministische Nachfrage bei gleichem Seed und Quellensnapshot; Prüfung mit
  mindestens drei Seeds und unterschiedlich zugeschnittenen Pilotgebieten.
- Güter-OD-Mengen bleiben bei räumlicher/zeitlicher Aufteilung bis auf ausgewiesene
  ganzzahlige Rundung erhalten.
- Für ausreichend belastete unabhängige Prüfzählstellen als erste Zielmarke
  mediane absolute relative LKW-Tagesabweichung höchstens 20 %. Niedrige Zählwerte
  mit absoluten Fehlern beurteilen; Schwelle nach Messqualität vor dem Fit festlegen.
- Stundenprofile, Randkordon und Fahrzeugklassen zusätzlich prüfen. Ein passender
  Tagesgesamtwert allein reicht nicht.
- Fit- und Prüfstellen trennen; mindestens einen anderen Zeitraum zurückhalten.
  Gemeinsame Messursprünge berücksichtigen: Portal-OD und Portal-Abschnittswerte
  sind keine zwei unabhängig erhobenen Datenquellen.
- A/B-Lauf ohne/mit Güterverkehr bei gleicher Personennachfrage und vollständiger
  Belastung: PKW-Zeit, Verzögerung, LKW-Kilometer und Emissionen vergleichen.
  Geringe Effekte bei geringer Netzauslastung sind kein Fehlschlag.
- Technischer Belastungstest auf derselben Strecke: Zusätzliche Fahrzeuge erhöhen
  bei ausreichender Nachfrage die Belastung nachvollziehbar. Keine pauschale
  monotone Wirkung auf jede einzelne OD-Reisezeit verlangen.

Die erste produktive Ausbaustufe ist damit eine überprüfbare Ergänzung des
Straßenverkehrs um schwere Güterfahrten. Detaillierte Lieferketten, Unternehmens-
und Flottenentscheidungen sind spätere Modellfähigkeiten.


## 8. Implementierter erster MVP (2026-10-10)

Der technische Einstieg ist opt-in über `Scenario.freight.enabled=false`.
Das Studio bietet Import und CSV-Vorlage; es speichert normalisierte Tages-OD-Mengen,
keinen beliebigen Server-Dateipfad. Die vorhandene Pipeline-Stufe Logistik schreibt
zusätzlich `freight/`; es entsteht keine neue Pipeline-Stufe. SUMO erhält separate
schwere Fahrten und führt sie mit Personenwegen und Paketverkehr zusammen.

### Eingabevertrag

UTF-8, Semikolon, Dezimalpunkt, keine CSV-Quotes. Pflichtkopf:

```csv
id;vehicleClass;movement;fromLon;fromLat;toLon;toLat;trips;beginHour;endHour;source;referenceDate
pilot01;truck;through;8.2002;48.85;8.2058;48.85;12;6;8;Manuelle Testannahme;2026-10-10
```

Das Beispiel ist **synthetisch**, keine reale Mautbeobachtung. Koordinaten müssen zum
konkreten Szenarionetz passen. `vehicleClass`: `truck` (Solo-LKW) oder `trailer`
(Sattelzug). `movement`: `internal`, `inbound`, `outbound`, `through`.
`trips` zählt Fahrzeuge pro ausgewähltem Tag **einschließlich Leerfahrten**.
Tonnage und Fahrzeugkilometer dürfen nicht direkt als Fahrtenmenge eingetragen werden.
`beginHour`/`endHour` sind Stunden innerhalb dieses Tages, nicht Wochenminuten.
Pro Beziehung werden exakt die eingegebenen ganzzahligen Mengen erzeugt; Abfahrten
werden innerhalb des Fensters geschichtet gleichverteilt gezogen. Reale Stundenprofile
lassen sich als separate Beziehungen/Fenster eingeben. Für jeden Datensatz sind Quelle
und ISO-Bezugsdatum erforderlich; sie werden als Nutzerangabe, nicht als verifizierte
Beobachtung geführt. Höchstens 5 MB, 10.000 Beziehungen und 200.000 schwere Fahrten.

Außenbeziehungen müssen **vor dem Import** auf Start-/Zielkoordinaten im Szenariogebiet
an geeigneten Netzrandzugängen aufgeteilt werden. Gemeindezentren sind keine verlässlichen
LKW-Startpunkte. Der Import führt noch keine solche Disaggregation durch.
Der Mapper wählt je Koordinate die nächste für die Fahrzeugklasse zulässige Kante
innerhalb 300 m. Er kennt keine gewünschte Fahrtrichtung: nahe parallele oder gegenläufige
Kanten können eine andere Anbindung ergeben. Randzugänge deshalb im Pilot explizit prüfen;
seit 2026-10-12 sind explizite gerichtete Kanten-IDs verfügbar (Abschnitt 9).
OSM-Sperren wirken nur, soweit sie im konvertierten SUMO-Netz enthalten sind; das ist
noch keine vollständige Prüfung von Gewichts-, Höhen- oder zeitabhängigen Beschränkungen.

### Kennzeichnung und Ergebnisse

- `trafficCategory=private`: Fahrzeuge aus der Personennachfrage, einschließlich
  Arbeits- und Einkaufswegen. Die Kategorie beschreibt den Nachfragebaustein,
  nicht Fahrzeugeigentum oder rechtlichen Nutzungszweck.
- `trafficCategory=logistics`: vorhandene Paket-/Retourentouren sowie neue schwere
  Güterfahrten. `demandSource` trennt `logitopp-parcels` und `heavy-freight`.
- ÖV-Fahrzeuge bleiben separat bilanziert; Fußgänger und ÖV-Personen erhalten in diesem
  MVP keinen Fahrzeug-Routenlayer. Die Karte schaltet tatsächliche SUMO-Fahrzeugrouten
  für Personenverkehr und Logistik getrennt. Das ist ein **gerouteter Plan**, keine GPS-Spur.

Artefakte: `freight/sources.json` (inklusive normalisiertem Eingabesnapshot und SHA-256),
`freight/trips.csv`, `freight/summary.json`, `freight/execution.json`;
`sumo/traffic-routes.geojson`, `sumo/traffic-categories.json` und
`sumo/summary.json.heavyFreightTrips`. Geroutete und angeforderte Fahrten werden verglichen;
fehlende schwere Routen oder nicht eingesetzte schwere Fahrzeuge führen zu SUMO-FAILED.
Am Tagesende noch unterwegs befindliche Fahrzeuge stehen einzeln in `execution.json`.
SUMO-Teleports bleiben wie im bisherigen Lauf aktiviert; abgeschlossen bedeutet daher
noch keine nachgewiesene physische Tourenkonsistenz. Neue Kartenartefakte werden pro Lauf
invalidiert, damit ein übersprungener/fehlgeschlagener SUMO-Lauf keine alten Güterrouten zeigt.

Der MVP verlangt 100 % Personennachfrage und schaltet SUMO-Modus-Caps ab, sobald
schwerer Güterverkehr aktiv ist. Güterwochentag und konfigurierter SUMO-Tag müssen
übereinstimmen; ungültige oder fehlende aktive Eingaben werden vor dem Start abgewiesen.
Die vorhandene PKW-Netzfeedbackschleife übernimmt dieselbe feste schwere Nachfrage erneut.
Das beseitigt weder fehlenden externen PKW-Verkehr noch andere Kalibrierungslücken.

Standardtypen verwenden `truck`/`trailer`, 10/16,5 m Länge und `HBEFA3/HDV_D_EU6`.
Der Fahrzeugkatalog erlaubt die Rollen `heavyTruck`/`articulatedTruck` für explizite
Typzuweisungen. Diese Referenztypen sind unkalibrierte Annahmen; Masse, Beladung und
Emissionsmix sind keine gemessenen regionalen Flottenzustände.

### Technische Evidenz und verbleibende Arbeit

`SumoFreightIntegrationTest` erzeugt ein eigenes OSM-Netz und führt native
`netconvert → duarouter → sumo` aus. Zwei PKW, drei schwere Fahrzeuge und ein Fußgänger
laufen gemeinsam; schwere Fahrzeuge meiden die mit `hgv=no` gesperrte Abkürzung,
werden vollständig eingesetzt und abgeschlossen, mit positiven Schwerlast-Emissionen.
Unit-Tests prüfen Mengen, Seed-Stabilität, Importfehler, Provenienz und deaktivierte Daten.
Der unabhängige Review deckte einen multimodalen Dateilistenfehler und zwei Probleme mit
alten/inaktiven Daten auf; diese wurden korrigiert.

**Fortschreibung 2026-10-12:** Reale Maut-API-Downloads und ein Offline-Maut-OD-Adapter
sind in Abschnitt 12 umgesetzt. Noch offen: VP2040-Adapter,
Gemeinde-/Zonen-Disaggregation, gerichtete Netzkordon-Anbindung, unabhängige Zählstellen-
Kalibrierung und regionale A/B-Läufe. Der native Fixture-Test belegt die technische
Integration; er belegt keine realistische regionale Güterverkehrsnachfrage.


Validierung am 2026-10-10: `mvn -o test` erfolgreich (238 Fälle, keine Fehler,
9 externe/umgebungsabhängige Fälle übersprungen); zusätzlicher `HeavyVehicleCatalogTest`
erfolgreich. `npm test`: 54 Tests erfolgreich; `npm run build`: erfolgreich.
Für die native SUMO-Laufzeit wurde `PROJ_DATA` auf das lokale gebündelte Verzeichnis
mit `proj.db` gesetzt. Der Routengeometrie-Parser wurde nach einem Thread-Dump korrigiert:
Xerces-`NodeList.getLength()` wird einmal statt für jeden Knoten aufgerufen. Der bestehende
Rastatt-SUMO-Test lief danach einschließlich Routenausgabe erfolgreich in 66 Sekunden;
die vollständige Backend-Suite benötigte 2:34 Minuten.


## 9. Gerichtete Zugänge und erster KBA-Datenadapter (2026-10-12)

### Gerichtete SUMO-Anbindung

Der CSV-Import akzeptiert weiterhin den ursprünglichen 12-Spalten-Kopf und zusätzlich
zwei optionale Spalten am Ende: `fromEdge;toEdge`. Beide müssen gemeinsam angegeben
werden; bei leeren Feldern bleibt die bisherige Koordinatensuche aktiv. Die Studio-Vorlage
enthält die neuen Spalten. Kanten-IDs stammen aus dem **aktuellen SUMO-Netz**, nicht
direkt aus OSM-Weg-IDs. SUMO kann Wege segmentieren und Netzgeometrien umformen.

`sumo/freight-network.geojson` enthält gerichtete, für mindestens eine Schwerlastklasse
zulässige Kanten, deren `edgeId`, `truck`-/`trailer`-Berechtigung und WGS84-Geometrie.
Das Studio bietet dafür einen Download. Praktischer Ablauf: zuerst SUMO ohne schweren
Güterverkehr ausführen, Netzkanten auswählen, Tages-OD-CSV damit vorbereiten und importieren.
Ein unverändertes Szenarionetz verwenden; nach Änderungen die IDs erneut prüfen.

Beim aktiven Güterlauf werden explizite Kanten auf Existenz, ausgewählte Fahrzeugklasse
und höchstens 300 m Abstand zur jeweiligen Eingabekoordinate geprüft. Ungültige IDs
führen zu einem Fehler; es erfolgt kein stiller Rückfall auf die Koordinatensuche.
`freight/edge-mapping.json` dokumentiert die verwendeten Kanten und die Methode je
Beziehung/Klasse. Diese Zuordnung wird innerhalb des Laufs wiederverwendet statt
für jede Fahrt das gesamte Netz erneut zu durchsuchen. Mengen und Abfahrtslogik bleiben
unverändert. Eine automatisch aus Gemeinde-OD abgeleitete Kordonzuteilung ist weiterhin offen.

**Korrigierter Sperrnachweis:** Die installierte SUMO-Version 1.27.1 übernimmt `hgv=no`
im getesteten OSM-Import nicht automatisch als Lane-Sperre. Der erste Fixture-Test vom
10.10.2026 isolierte diese Wirkung wegen zusammengeführter Kanten nicht zuverlässig.
`OsmFreightPermissions` übernimmt jetzt unbedingte `hgv=no` und richtungsbezogene
`hgv:forward=no`/`hgv:backward=no` als Sperre für `truck` und `trailer`. Richtungsspezifische
Werte überschreiben das allgemeine Tag. Die Geometrievereinfachung bleibt ausgeschaltet,
damit Wegsegment-IDs für die Zuordnung erhalten bleiben. Der Bericht
`sumo/freight-permissions.json` nennt gesperrte Kanten und nicht unterstützte HGV-Tags.
Gewichts-, Höhen-, bedingte und situationsabhängige Regeln sind weiterhin nicht umgesetzt.
Der erweiterte native Test verlangt, dass PKW die Abkürzung tatsächlich nutzen und
schwere Fahrzeuge sie meiden; der Umweg allein zählt nicht als Sperrnachweis.

### KBA-PUF als Referenzdaten importieren

Das [KBA-Paket VD 2025](https://www.kba.de/DE/Statistik/Forschungsdatenzentrum/Datenangebot/Verkehr_deutscher_Lastkraftfahrzeuge/PublicUseFiles_VD_2025.html)
wurde am 12.10.2026 heruntergeladen und durch `scripts/inspect-kba-freight.py` eingelesen.
Ergebnis: **elf CSV-Dateien, 70.716 einzelne Kennwerte**; keine erzeugte Tagesnachfrage.
Das ZIP enthält Semikolon-CSV mit Dezimalkomma, codierten Dimensionen und Qualitätsfeldern
`*_ZS`. Der Import normalisiert Zahlen und bewahrt Dimensionen, Originalmarker, Tabellen-
und Zeilenbezug. `/` bleibt unterdrückt, `()` eingeschränkt, `-` ein nicht numerisch
interpretierter Marker; fehlende Werte werden nicht zu null Fahrzeugen.

Reproduzierbarer manueller Einstieg, nachdem das Original-ZIP von KBA heruntergeladen wurde:

```bash
python3 scripts/inspect-kba-freight.py \
  --input /pfad/zum/PublicUseFiles_VD_2025.zip \
  --output .runtime/freight/kba-2025 \
  --source-url 'https://www.kba.de/DE/Statistik/Forschungsdatenzentrum/Datenangebot/Verkehr_deutscher_Lastkraftfahrzeuge/PublicUseFiles_VD_2025.zip?__blob=publicationFile&v=8' \
  --retrieved-on 2026-10-12
python3 -m unittest discover -s scripts -p test_inspect_kba_freight.py
```

Der lokale geprüfte Snapshot liegt unter `.runtime/freight/kba-2025/`:
`source.zip`, `inventory.json` (ZIP-/Datei-SHA-256, Quell-URL, Abrufdatum, Tabelleninventar,
Qualitätszählungen) und `observations.jsonl`. Die Rohdaten werden nicht versioniert.
ZIP-SHA-256: `e282b9a829fb686a2e762236cf4906fd08441fc85cf53a654537deb2e24d07b5`.
Der Parser lehnt fremde ZIP-Pfade, unbekannte Qualitätsmarker, fehlerhafte Zahlen und
abweichende Jahresangaben ab; vor vollständiger Validierung schreibt er keine Ergebnisse.

**Nächster Daten-Schritt:** Codes und Einheiten mit dem passenden amtlichen Codebuch
abgleichen, anschließend geeignete Fahrten-/Distanz-/Leerfahrtenreferenzen auswählen.
Die Tabellen können überlappende Aggregate enthalten und dürfen nicht einfach aufsummiert
werden. Lizenzprüfung, Regionalisierung und Tages-/Stundenumrechnung bleiben ausdrücklich
unbestätigt. Der Adapter setzt deshalb `codesDecoded=false`, `licenseVerified=false`
und `dailyDemandGenerated=false`. Die reale Maut-OD-Aufbereitung ist inzwischen in
Abschnitt 12 umgesetzt; ihre räumliche Verteilung und unabhängige regionale Kalibrierung
bleiben offen.


Validierung des Ausbaus am 2026-10-12: Backend-Gesamtsuite erfolgreich (242 Fälle,
0 Fehler, 9 Voraussetzungen-Skips), 54 Frontend-Tests und Produktionsbuild erfolgreich,
3 Python-Importtests erfolgreich. `SumoFreightIntegrationTest` führt den gemeinsamen
nativen Lauf aus und prüft die unterschiedliche Sperrwirkung für PKW und schwere LKW.

## 10. SEVAS als ergänzende Routinggrundlage für NRW (2026-10-12)

**Entscheidung im Konzept:** SEVAS für NRW priorisieren, um das schwere
Güterverkehrsrouting um kommunale Restriktionen und ein LKW-Positivnetz zu ergänzen.
Maut-OD und Zählungen liefern weiterhin Mengen; SEVAS liefert die räumlichen Regeln.
Der regionale Spike und die einfache Import-Teilmenge sind inzwischen umgesetzt;
der unten beschriebene vollständige Ausbau bleibt offen (siehe Abschnitt 11).

### Zugang und tatsächlich geprüfter Stand

Die [Mobidrom-Produktseite](https://www.mobidrom.nrw/produkte/sevas-von-mobidrom)
nennt mehr als 300 teilnehmende Kommunen, eine Abdeckung großer Teile NRWs und
kostenfreie Bereitstellung. Das bedeutet keine nachgewiesene Vollständigkeit für jede
Straße oder Kommune. [Open.NRW](https://open.nrw/dataset/sevas___daten_zur_navigation_von_lkw_ab_3_5_tonnen_1580828998)
führt die Datenlizenz Deutschland – Zero 2.0 für die veröffentlichten Dienste auf.
Die Lizenz des konkreten Snapshots wird beim späteren Import mitgespeichert;
die OSM-Lizenz für das zugrunde liegende Netz bleibt separat zu beachten.

Am 12.10.2026 waren Formatdokumentation und WFS-Dokumentation im lokalen Browser
lesbar. Auch die [öffentlichen GetCapabilities](https://sevas.nrw.de/osm/sevas?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetCapabilities)
lieferten dort tatsächlich XML. Der Text-Rechercheabruf scheiterte teilweise;
das war kein Nachweis fehlender öffentlicher Erreichbarkeit.
Die Capabilities weisen unter anderem diese Layer aus:

- `ms:restriktionen_segmente`
- `ms:vorrangrouten_segmente`
- `ms:zonen_segmente` und `ms:zonen_polygone`
- `ms:lkw_schilder_aufstellvorrichtungen`

Angebotene GetFeature-Formate umfassen GML, CSV, ShapeZip und GeoJSON;
EPSG:4326 ist das Default-CRS. Paging und BBOX sind ausgewiesen.
Dies bestätigt die Dienstbeschreibung, noch keinen vollständigen regionalen
Datenabruf, keine Trefferzahlen und keine funktionierende GeoJSON-Importkette.
WFS-Achsenreihenfolge und tatsächliche Koordinaten müssen im Import-Spike geprüft werden.

Die [WFS-Dokumentation](https://sevas.nrw.de/hilfe/export/dienste/wfs)
unterscheidet den öffentlichen Dienst von `sevas-schilder`: Der zweite Endpunkt
für vollständige Verkehrszeichen-/Aufstellvorrichtungsdaten ist IP-beschränkt und
erfordert eine Freischaltung auf Anfrage. WMS eignet sich zur Anzeige;
für die Modellintegration verwenden wir die Feature-Daten des WFS.

### Fachliche Zuordnung

Das [Restriktionsschema](https://sevas.nrw.de/hilfe/export/daten-format/restriktionen)
enthält `segment_id`, `restrkn_id`, `osm_id`, `osm_vers`, `fahrtri`, `typ`, `wert`,
Wochentags-/Zeitfelder sowie Zusatzzeichen. `fahrtri=0` gilt beidseitig,
`1` in Richtung des LineString und `2` entgegen dieser Richtung.
`wert` wird bei VZ 262/263 in Tonnen und bei 264/265/266 in Metern angegeben.
Ausnahmen wie Anlieger-/Lieferverkehr und Durchgangsverkehr sind ausdrücklich modelliert.

**Folgerung für SyntheSis:** Ein Treffer auf `osm_id` allein reicht nicht.
SEVAS-Segment und aktuelle SUMO-Kanten müssen nach Geometrieüberlappung,
Richtung und OSM-Version abgeglichen werden. Ein OSM-Way kann mehrere Teilsegmente
enthalten; eine lokale Sperre darf nicht ungeprüft den ganzen Way sperren.
Die LineString-Richtung darf nicht allein aus dem Vorzeichen einer SUMO-ID abgeleitet werden.

### Vorgesehener Ausbau und Nachweis

1. **Regionaler Import-Spike:** Einen kleinen NRW-Ausschnitt samt Netzpuffer abrufen;
   Restriktionen und Vorrangrouten getrennt halten. Rohantwort, Abrufzeit, Abfrage,
   Lizenz, Schema und SHA-256 sichern. Paging vollständig durchlaufen;
   leere Antwort von Abruffehler unterscheiden.
2. **Segmentzuordnung beweisen:** Gegen das erzeugte SUMO-Netz abgleichen und
   zugeordnete, mehrdeutige, veraltete und fehlende Segmente berichten. Unklare
   Zuordnungen nicht still auf die nächste Straße übertragen. Eine Kartenansicht
   soll Quellsegment und betroffene gerichtete Kanten vergleichbar machen.
3. **Zuerst einfache Sperren:** Nur eindeutig zugeordnete, unbedingte LKW-Verbote
   ohne Ausnahmen anwenden. Bestehende OSM-Sperren nicht durch eine SEVAS-Vorrangroute
   aufheben. Konflikte und nicht unterstützte Regeln sichtbar ausgeben.
4. **Danach fahrzeugbezogene Regeln:** Tatsächliche Masse, zulässige Gesamtmasse,
   Achslast und Maße benötigen getrennte Fahrzeugattribute. Zeitbedingungen
   brauchen Szenariodatum und Passagezeit; Lieferverkehr-Ausnahmen eine
   nachvollziehbare Fahrtberechtigung. Die Kategorie `logistics` allein genügt dafür nicht.
5. **Vorrangrouten als Präferenz:** Als gewichtete Routingkosten für schwere Fahrzeuge
   erproben; übrige zulässige Straßen für letzte Zufahrten offen halten.
   Die konkrete SUMO-Routingmechanik und Gewichtung müssen vor Implementierung
   im nativen Lauf bewiesen werden.
6. **A/B-Validierung:** Identische Nachfrage und Seeds mit/ohne SEVAS vergleichen:
   Sperrverletzungen, Vorrangroutenanteil, Umwege, nicht routbare Fahrten und
   regionale Zählstellenabweichungen. Testfälle müssen Gegenrichtung, Teilsegment,
   Ausnahme und PKW-Verhalten enthalten; aus gleicher Quelle abgeleitete Daten
   gelten nicht als unabhängige Validierung.

Der erste Schritt liefert einen prüfbaren NRW-Snapshot und einen Zuordnungsbericht.
Er ändert weder Fahrtenzahlen noch den Nachweisstand des bisherigen SUMO-MVP.

### Durchgeführter regionaler Zuordnungs-Spike (2026-10-12)

**Prüffrage:** Lassen sich öffentlich abgerufene SEVAS-Segmente anhand von OSM-Way,
Geometrie und Richtung einem tatsächlich erzeugten SUMO-Netz zuordnen?
Gebiet: Dortmunder Ausschnitt, CRS84-Bbox `7.4,51.48,7.5,51.54`; OSM-Netz mit
einem zusätzlichen Puffer von jeweils 0,005 Grad. Dies ist keine Prüfung von ganz
Dortmund, Unna oder NRW.

Öffentlicher WFS-GetFeature-Abruf mit GeoJSON erfolgreich. Die endgültigen Abfragen
verwenden `COUNT=500`, `STARTINDEX` und `SORTBY=segment_id`.
**Befund zum Paging:** Ohne Sortierung enthielten Vorrangrouten-Seiten doppelte IDs.
Mit Sortierung waren alle IDs eindeutig; die beobachtete Sortierung war lexikografisch.
Separate `RESULTTYPE=hits`-Abfragen bestätigten die Gesamtzahlen beider Layer.
Die GeoJSON-Koordinaten kamen als Longitude/Latitude und liegen im gewählten Gebiet.

Ein aktueller Overpass-Straßenabzug wurde mit dem nativen `netconvert` verarbeitet;
Geometrievereinfachung und Junction-Zusammenführung waren ausgeschaltet. Der Abgleich
verwendet geografische Plain-Exporte, projiziert nach EPSG:25832 und verlangt
mindestens 95 % Abdeckung der SEVAS-Linienlänge durch einen 5-m-Puffer um SUMO-Kanten
**desselben OSM-Ways**. Aufeinanderfolgende identische Koordinaten werden nur in
der Analyse entfernt; Rohantworten bleiben erhalten. Richtungen werden anhand
der projizierten Endpunkte auf jeder gerichteten Kante geprüft.

| Ergebnis | Restriktionen | Vorrangrouten |
|---|---:|---:|
| Vollständig abgerufene, eindeutige Segmente | 552 | 1.655 |
| Geometrieabdeckung nach obigem Kriterium erreicht | 538 (97,5 %) | 1.627 (98,3 %) |
| Way vorhanden, Geometrieabdeckung unzureichend | 12 | 28 |
| OSM-Way im erzeugten Netz nicht vorhanden | 2 | 0 |
| Geometrisch passende Segmente mit nur teilweise betroffener SUMO-Kante | 17 | 6 |

Die Teilkanten-Zeile zählt eine Teilmenge der passenden Segmente, keine zusätzlichen
Datensätze. Bei sechs passenden Vorrangroutensegmenten ist die einfache
Endpunktprojektion für mindestens eine zugeordnete Kante richtungsseitig unklar.
Die Zahlen sind ein geometrischer Eignungsnachweis bei gewählter Toleranz,
keine Bestätigung der straßenrechtlichen oder flächendeckenden Vollständigkeit.

550 Restriktionssegmente und alle Vorrangrouten referenzieren `osm_20260325`;
zwei Restriktionssegmente `osm_20250617`. Restriktionstypen: 374 × VZ 253,
117 × VZ 265, 51 × VZ 262 und 10 × VZ 250. Bei 306 Restriktionssegmenten ist
`vz_1026_35` (Lieferverkehr frei) gesetzt, bei 51 `vz_1020_30` (Anlieger frei).
Diese Ausnahmen dürfen nicht automatisch aus der Fahrtkategorie abgeleitet werden.

Nur **19 Segmente** erfüllen die zunächst betrachtete einfache Teilmenge:
VZ 253 ohne zeitliche Bedingungen oder gesetzte Zusatzzeichen. Davon sind **16**
geometrisch passende Kandidaten ohne relevante Teilkantenüberdeckung und ohne
unklare Endpunktorientierung. Auch diese Kandidaten benötigen vor Produktivübernahme
eine Prüfung auf Konflikte mit weiteren Regeln und auf Sonderfälle der Geometrie.
538 geometrische Treffer bedeuten daher ausdrücklich nicht 538 automatisch anwendbare Sperren.

**Nativer Wirkungsnachweis an einem Kandidaten:** SEVAS-Segment `3929504`,
OSM-Way `622357555`, SUMO-Startkante `-622357555#0`.
Ohne zusätzliche Sperre routet `duarouter` PKW und LKW über diese Kante.
Nach einer isolierten Lane-Sperre für `truck`/`trailer` bleibt die PKW-Route erhalten,
die LKW-Fahrt mit dieser gesperrten Startkante wird verworfen. Der anschließende
native SUMO-Lauf führt im Ausgangsfall beide Fahrzeuge und im Sperrfall den PKW
erfolgreich aus. Das beweist die Exklusion an einer zugeordneten Kante;
es ist kein Umwegnachweis und keine regionale Validierung aller SEVAS-Regeln.

**Artefakte:** `.runtime/sevas-spike-2026-10-12/` enthält Rohseiten,
Abfrage-URLs und SHA-256, Hits-Antworten, OSM-Abzug, SUMO-Netz und Logs sowie:

- `mapping-report.json`: Mengen, Kriterien, Quellversionen und Datei-Hashes.
- `segment-mapping.json`: Zuordnungen und Teilkanten-/Richtungsbefunde je Segment.
- `comparison.geojson`: SEVAS-Linien und referenzierte gerichtete SUMO-Kanten zur Kartenprüfung.
- `routing-probe.json`: isolierter Routing- und SUMO-Nachweis.
- `analyze.py` und `routing_probe.py`: lokale Spike-Auswertung; Python mit Shapely/pyproj,
  nicht Teil der produktiven Generierung.

**Folgerung:** Den NRW-Adapter weiterverfolgen. Als nächstes die abweichenden und
teilweise betroffenen Segmente visuell prüfen, Teilkantenbehandlung festlegen und
den Ausnahmevertrag für Liefer-/Anliegerverkehr definieren. Anschließend erst
die bewiesene einfache Teilmenge hinter einem standardmäßig deaktivierten Flag
integrieren. Es wurden keine produktiven Szenarien oder Nachfragezahlen verändert.

## 11. SEVAS-Snapshot im Studio verwenden (2026-10-12, experimentell)

Der neue Schalter `freight.sevasEnabled` startet mit `false`. Unter „Schwerer
Güterverkehr (SUMO)“ kann ein SEVAS-Restriktionssnapshot als GeoJSON hochgeladen
und separat aktiviert werden. SUMO und aktiver schwerer Güterverkehr sind erforderlich.
Zunächst das Szenario speichern; dann im Studio „SEVAS-LKW-Sperren für NRW“ aktivieren,
Restriktionssegmente hochladen, speichern und generieren. Für den geprüften Dortmunder
Testausschnitt kann `.runtime/sevas-spike-2026-10-12/restriktionen_segmente.json`
verwendet werden. Fahrtenmengen müssen weiterhin separat als OD-CSV vorbereitet sein.

`POST /api/scenarios/{id}/freight/sevas` akzeptiert ein Multipart-Feld `file` mit
höchstens 5 MB, 1–10.000 eindeutig identifizierten Restriktionssegmenten, vollständigen
Ausnahmefeldern und NRW-LineStrings in Longitude/Latitude. Ein fehlerhafter Upload
ersetzt den vorherigen Snapshot nicht. Die GeoJSON-Daten werden im Szenario gespeichert;
kein Live-Download während der Generierung. Fehlende Daten bei aktiviertem Schalter
werden vor dem Jobstart abgewiesen. Abschalten des schweren Güterverkehrs beziehungsweise
Entfernen des SUMO-Zielmodells deaktiviert SEVAS im Studio ebenfalls.

`SevasFreightPermissions` akzeptiert zunächst nur VZ 253 ohne Wert, Zusatzzeichen,
Ausnahmen oder Zeitbedingungen. Unbekannte Schemafelder werden nicht still ignoriert.
Die Zuordnung verwendet OSM-Way, gerichtete Kanten und Geometrie mit 5-m-Toleranz.
Anders als der explorative 95-%-Abgleich muss jede Probe im Abstand von höchstens 2 m
passen: sowohl die Quelllinie zum Netz als auch jede anzuwendende vollständige Kante
zur Quelllinie. Das ist ein konservativer Stichprobenabgleich, kein exakter geometrischer
Beweis zwischen allen Proben. Gemischte Regeln desselben Ways, Teilkanten und unklare
Richtungen werden für das ganze betroffene Segment übersprungen.

Die Sperre entfernt nur `truck`/`trailer` und erhält die übrigen Lane-Berechtigungen.
Sie wird nach der OSM-Sperrübertragung und vor ÖV-Vorbereitung, Routing und Netzkantenexport
angewendet. Explizite Güterzugänge müssen danach zulässig sein; ansonsten scheitert die
Zuordnung statt still eine neue Startkante zu wählen. Deaktivierung und Neugenerierung
bereinigen alte SEVAS-Berichte. Der Lauf meldet die Zahl gesperrter Kanten und ausdrücklich,
wenn keine anwendbare Sperre gefunden wurde.

`sumo/sevas-permissions.json` enthält den verwendeten Snapshot, dessen normalisierten
JSON-SHA-256, Entscheidungen je Segment, Statuszählungen und gesperrte Kanten.
Der Datenqualitätsstatus lautet `user-uploaded-unverified`: Ein hochgeladener Datensatz
ist kein bestätigter Abruf beim Betreiber. `osm_vers` wird formal geprüft und bleibt
im Snapshot erhalten; ein vollständiger Versions-/Aktualitätsabgleich zum aktuellen
OSM-Netz ist **nicht** umgesetzt (`networkVersionVerified=false`). Geometrische
Übereinstimmung ersetzt diesen Abgleich nicht. Vorrangroutengewichte, Liefer-/Anliegerrechte,
Zeit-, Gewichts- und Höhenregeln bleiben offen.

**Tatsächlicher Java-Abgleich des Dortmunder Snapshots:** 552 Segmente eingelesen;
14 angewendet auf 77 gerichtete Kanten, 2 wegen gemischter Regeln desselben Ways,
2 wegen Teilkanten und 1 wegen unklarer Richtung übersprungen. Die übrigen 533
Segmente liegen außerhalb der unterstützten Regelmenge. Dieser Stand ist strenger
als die 16 einfachen geometrischen Explorationskandidaten. Ergebnisdatei:
`.runtime/sevas-spike-2026-10-12/implementation-report.json`.

Ein zusätzlicher nativer Mischverkehrstest entfernt das OSM-`hgv=no` aus der
Testabkürzung und aktiviert stattdessen allein den SEVAS-Snapshot. PKW nutzen die
Abkürzung; Solo-LKW und Sattelzüge vermeiden sie und schließen ihre Fahrten ab.
Der Test prüft den Bericht und führt `netconvert`, `duarouter` und SUMO aus.
Unit-/API-Tests sichern Richtungsregeln, Teilkanten, Ausnahmen, fehlerhafte und
fehlende Felder, JSON-null-Kompatibilität sowie den Erhalt eines vorherigen Uploads.

Verifiziert am 2026-10-12: Backend-Gesamtsuite 249 Fälle ohne Fehler, neun externe
Voraussetzungen-Skips; 55 Frontend-Tests und Produktionsbuild erfolgreich.
Der native SEVAS-Mischverkehrstest wurde ausgeführt. Unabhängiger Read-only-Review
bestätigte die Snapshot-Zählungen und fand keinen weiteren belegten Fehler;
der fehlende Versionsabgleich bleibt als Grenze ausgewiesen.

## 12. Realer Maut-OD-Datenpilot Dortmund/Unna (2026-10-12)

**Umgesetzt:** öffentlicher API-Abruf und reproduzierbare Offline-Aufbereitung für
Juli 2026. **Noch kein vollständiger regionaler Nachfragelauf und keine Kalibrierung.**
Der Monat wurde gewählt, weil auch ein unabhängiger NRW-Zählstellenbericht verfügbar
ist. Ein Kalenderdurchschnitt im Ferienmonat ist kein beobachteter Normalwerktag.

### Datenvertrag und Herkunft

Die [Start-Ziel-Ansicht des LKW-Verkehrsportals](https://lkw-verkehrsportal.de/app/?mode=startZiel)
beschreibt Mautnetzeintritte und -austritte auf Gemeindeebene. Sie enthält keine
Betriebsstandorte, Warenarten, Touren oder tatsächlichen Lieferziele. Technische
Unterbrechungen können zusätzliche Ein-/Ausfahrten erzeugen. Nicht mautpflichtiger
Verkehr fehlt; Verkehr durch Dortmund/Unna ohne dortigen Mautnetzeintritt/-austritt
fehlt in den fokalen Gemeindeabfragen ebenfalls. Die Informationsansicht bestätigt:
Befahrungswerte werden für den Datenschutz auf die nächste Zehnerstufe gerundet.
Daher sind sämtliche folgenden Mengen **Summen veröffentlichter gerundeter Werte**.

Der im Portal dokumentierte öffentliche ArcGIS-Dienst:

```text
https://webgis.toll-collect.de/arcgis/rest/services/lkw-verkehrsportal/mautdaten_bund_monat_sz/FeatureServer/0/
```

Abfrage über `query`, `outFields=*`, `returnGeometry=false`, `f=json`,
`orderByFields=objectid`, `resultRecordCount=2000`. Filter für jede der beiden
Gemeinden (`05913000` Dortmund, `05978036` Unna):

```text
ags_start='05913000' AND richtung=0 AND monat=DATE '2026-07-01' AND land='x'
ags_ziel='05913000' AND richtung=1 AND monat=DATE '2026-07-01' AND land='x'
```

Für Unna entsprechend den AGS ersetzen. Der Dienst verlangt explizite AGS- und
Richtungsfilter; eine alleinige Namenssuche wird abgewiesen. `richtung` kennzeichnet
hier die Start-/Zielperspektive, keine zusätzlich zu summierende Fahrtrichtung.
`ags_start`/`ags_ziel` behalten ihre OD-Bedeutung. `land='x'` ist die gelieferte
Aggregation; eine zusätzliche Distinct-Abfrage lieferte für Dortmund nur `x`.

Der Dienst meldet maximal 2.000 Datensätze pro Antwort. Die vier Pilotabfragen
liegen darunter und melden kein Transferlimit. Künftige größere Abfragen müssen
vollständig paginiert werden; der Offline-Adapter weist eine abgeschnittene
Einzelseite zurück. Der globale Monatsindex enthielt beim Abruf Juni 2025 bis
August 2026, obwohl die Oberflächenbeschreibung von zwölf Monaten spricht.

**Lizenz:** laut Open-Data-Ansicht DL-DE-BY 2.0; Attribution BALM und Toll Collect
GmbH. Bei Übernahme der Gemeindegeometrien zusätzlich BKG nennen. Der Pilot nutzt
Attribute ohne Gemeindegeometrien. Das Rohdatenmanifest hält URLs und SHA-256 fest:
`.runtime/freight-pilot-dortmund-unna-2026-10-12/download-manifest.json`.
Rohdateien bleiben außerhalb der versionierten Produktartefakte.

### Beobachtete Mengen und Doppelzählung

| Abfrage Juli 2026 | Rohzeilen |
|---|---:|
| Dortmund, Startperspektive | 1.041 |
| Dortmund, Zielperspektive | 1.014 |
| Unna, Startperspektive | 517 |
| Unna, Zielperspektive | 595 |
| Gesamt vor Bereinigung | 3.167 |
| Eindeutige gerichtete Gemeinde-OD | 3.163 |

Vier doppelte Perspektiven betreffen Dortmund→Dortmund, Unna→Unna und beide
Dortmund↔Unna-Beziehungen. Ihre Mengen und Fahrleistungen stimmen überein;
sie werden einmal übernommen. Die veröffentlichte Monatssumme sinkt dadurch von
979.240 auf **775.840**, davon **192.260** innerhalb derselben Gemeinde.
Gemeindeinterne Beziehungen erhalten keine automatisch erfundenen Start-/Zielpunkte.

| Beziehung | Veröffentlichte Monatsbefahrungen | Reiner Kalenderdurchschnitt `/31` |
|---|---:|---:|
| Dortmund → Unna | 6.750 | 217,74 |
| Unna → Dortmund | 4.390 | 141,61 |
| Dortmund → Dortmund | 159.090 | 5.131,94 |
| Unna → Unna | 33.170 | 1.070,00 |

Alle 3.163 Werte sind Vielfache von zehn; das Minimum beträgt zehn. Aus fehlenden
Beziehungen werden keine Nullbeobachtungen abgeleitet. Die technische Erfassungsquote
des Mautsystems ersetzt keine Vollständigkeitsprüfung der regionalen Nachfrage.

### Offline-Adapter und optionale gerichtete Zugänge

`scripts/prepare-toll-freight.py` verwendet ausschließlich die Python-Standardbibliothek
und läuft als separate Datenvorbereitung, ohne neue Abhängigkeit der Java-Pipeline:

```bash
python3 scripts/prepare-toll-freight.py \
  --month 2026-07 \
  --output .runtime/freight-pilot-dortmund-unna-2026-10-12/normalized \
  .runtime/freight-pilot-dortmund-unna-2026-10-12/od-0*.json
python3 -m unittest discover -s scripts -p 'test_*freight*.py' -v
```

Ausgaben: `monthly-od.csv` mit gerundeten Monatsbeobachtungen und ungerundetem
Kalenderdurchschnitt sowie `report.json` mit Hashes, Perspektivenbereinigung,
Abdeckung, Präzisionshinweis und `dailyDemandGenerated=false`/`calibrated=false`.
API-Fehler, Transferlimits, falsche Monate/Aggregate, fehlende Mengen und widersprüchliche
Doppelbeobachtungen führen zum Abbruch. Der Adapter unterstützt vorerst numerische
achtstellige deutsche Gemeinde-AGS; andere Aggregationsschlüssel benötigen einen
separaten Vertrag.

Optional erzeugt `--mapping <datei.json>` die bestehende 14-Spalten-Importdatei
`freight-relations.csv`. Das JSON enthält eine ausdrückliche `assumption` und
`relations`: je Relation `id`, `ags_start`, `ags_ziel`, `share`, `vehicleClass`,
`movement`, `fromLon`, `fromLat`, `toLon`, `toLat`, `fromEdge`, `toEdge`,
`beginHour`, `endHour`. Klassen, Zugänge, Anteile und Stundenfenster sind Modellannahmen,
keine Beobachtungen aus dieser Mautdatei. Gerichtete unterschiedliche Kanten sind
Pflicht. Anteile je beobachteter OD dürfen zusammen eins nicht überschreiten.

Die Tagesmengen werden aus Monatsmenge × Anteil / Kalendertage berechnet und mit
größten Resten deterministisch auf ganze Fahrzeuge verteilt. Der Bericht enthält
das vollständige Mapping, dessen normalisierten JSON-SHA-256, repräsentierte und
unzugeordnete Monatsmengen sowie auf null gerundete Mappingzeilen. Die CSV verwendet
als Quellenreferenz den Monatsanfang; das bedeutet kein dort beobachtetes Fahrtdatum.
Der Import muss anschließend die tatsächlich vorhandenen, erlaubten Kanten prüfen.
`mappingValidatedAgainstNetwork=false` bleibt bis zu einem separaten Netzbeweis bestehen.
Ohne Mapping entsteht keine Tages-CSV; ein älterer Tagesexport im gleichen Ausgabeordner
wird bei erfolgreicher neuer Aufbereitung entfernt.

### Unabhängige Zählbasis: Klassenvergleich vor Kalibrierung

Der [Straßen.NRW-Monatsbericht Juli 2026](https://opendata.strassen.nrw.de/Verkehrsdaten/Dauerzaehlungen/Monatsdaten/2026/2026-07%20Dauerzaehlung.pdf)
ist heruntergeladen und aufbereitet. Anhang 3.1 weist den schweren Güterverkehr
`SGV = LKW ohne Anhänger + LKW mit Anhänger + Sattelkraftfahrzeuge` separat aus;
`SV = SGV + Bus`. Für schwere Logistik **SGV** verwenden. Die Kategoriengrenze der
Zählstatistik (zulässiges Gesamtgewicht) bleibt von der Mautgrenze (technisch
zulässige Gesamtmasse und Ausnahmen) verschieden.

| Station | Straße | Kfz/Tag | SGV/Tag | SV/Tag | SGV/Kfz | Auswertbare Tage, Anhang 3.1 |
|---|---|---:|---:|---:|---:|---:|
| 5318 Dortmund-Brechten | B54 | 21.398 | 1.610 | 1.630 | 7,52 % | 31 |
| 5365 Dortmund-Wambeldorf | B236 | 70.516 | 4.290 | 5.018 | 6,08 % | 31 |
| 5352 Dortmund-Berghofen | B236 | 34.640 | 2.236 | 2.277 | 6,45 % | 28 |

Werte sind Mo–So-Kalenderdurchschnitte im Gesamtquerschnitt, **keine einzelnen
Fahrtrichtungen**. Quelle: PDF-Seiten 20 und 23. GT ist die Anzahl ausreichend
auswertbarer Tage; fehlende Stunden-/Tageswerte können geschätzt sein. Kleine
Differenzen zwischen gerundeten Komponenten und veröffentlichten Gesamtkategorien
bleiben erhalten. Maschinenlesbarer Befund samt PDF-Hash:
`.runtime/freight-pilot-dortmund-unna-2026-10-12/count-observations.json`.
Diese Quoten dürfen nicht ungeprüft auf alle Personentrips übertragen werden.

**Abnahmegrenze am 2026-10-12 (Fortschreibung in Abschnitt 13):** Monats-OD und Zählbasis sind verfügbar und semantisch aufbereitet.
Ein Fehlermaß wäre erst nach eindeutiger Stationszuordnung auf gerichtete Netzkanten,
vollständiger regionaler Nachfrage einschließlich Transit, kontrolliertem Zeitbezug
und passender Fahrzeugklassifikation sinnvoll. Aktuell steht der Vergleich auf
`not_comparable_yet`; kein RMSE/GEH und kein Skalierungsfaktor werden erfunden.

Der größere OSM-Netzabruf wurde am 2026-10-12 versucht: Hauptendpoint antwortete HTTP 406,
der erste Ausweichserver HTTP 504; auch der kleinere Korridorabruf beim zweiten
Ausweichserver endete nach 180 Sekunden ohne Antwort. Ein vorhandener zentraler
Dortmunder SEVAS-Netzausschnitt
deckt Unna und die vollständige Zählstellenbasis nicht ab. Die räumliche Verteilung
bleibt deshalb bis zu einem erfolgreichen regionalen Netzabruf bzw. einem passenden
OSM-Snapshot offen. Kein Gemeindezentroid wurde ersatzweise als Logistikzugang verwendet.

**Verifikation:** sieben Adaptertests plus drei bestehende KBA-Aufbereitungstests
bestanden. Unabhängiger Read-only-Review reproduzierte Zeilenzahlen, Summen und
Rohdatei-Hashes. Sein Provenienzbefund wurde behoben: optionales Mapping und dessen
Hash werden jetzt mitexportiert; Datenschutzrundung wurde anhand der Portal-Methodik
bestätigt. Backend und Frontend wurden in diesem Datenvorbereitungsschritt nicht geändert.

## 13. Native Maut-OD-Routingprobe Dortmund/Unna (2026-10-13)

**Fortschritt gegenüber Abschnitt 12:** Ein neuer Overpass-Abruf war erfolgreich.
Der lokale Routingnachweis für Dortmund↔Unna und ein erster **Teilnachfragevergleich**
sind durchgeführt. Vollständige regionale Disaggregation und Kalibrierung bleiben offen.

### Netzsnapshot und räumliche Annahmen

Der Snapshot enthält Hauptstraßen und deren Verbindungen im Ausschnitt
`[7.30, 51.40, 7.85, 51.65]`, abgefragt als
`motorway|trunk|primary|motorway_link|trunk_link|primary_link` plus referenzierte Nodes.
Andere Straßenklassen fehlen; dies ist kein vollständiges Gemeinde-/Liefernetz.
OSM-Ways können über den Abfrageausschnitt hinausreichen.

Rohdatei `major.osm`: 4.747.344 Bytes,
SHA-256 `54e6b7ec47b2e5645e8004044c06d2ceb1f4cedcda28c7dfa51462682fbbe60b`.
URL, Query, UTC-Abrufzeit und ODbL-Attribution sind in
`.runtime/freight-routing-pilot-2026-10-13/major-source.json` erhalten.
Direkte OSM-Kartenabfragen wurden zuvor getestet, aber auch kleinere Ausschnitte
überschritten das dortige 50.000-Knoten-Limit. Kein erfolgreicher Abruf wurde durch
stilles Fallback auf ein synthetisches Netz ersetzt.

`netconvert` erzeugte das native Netz; `geometry.remove=false` erhält die OSM-Way-
Zuordnung. Der produktive `OsmFreightPermissions`-Adapter übertrug zwei unbedingte
HGV-Sperren. Der produktive `TrafficRoutes`-Mapper prüfte alle vier gerichteten
Klassenzuordnungen auf Existenz, Berechtigungen und Nähe zur Zugangskoordinate.
Der zulässige Güterkantenauszug enthält 7.329 gerichtete Kanten.
Der Snapshot stammt aus Oktober 2026, die Maut-/Zählbeobachtungen aus Juli 2026;
ein historischer Netzgleichstand ist **nicht** belegt.

Für diese begrenzte Probe wurden zwei Zugangsproxies ausdrücklich gewählt:

| Zugang | Von Dortmund nach Unna | Von Unna nach Dortmund |
|---|---|---|
| B236 bei Berghofen | Startkante `60354712` | Zielkante `-60354712` |
| B1 bei Unna | Zielkante `102460046` | Startkante `-102460046` |

Die Koordinaten liegen auf den zugehörigen Netzkanten. Dies sind **modellierte
Zugänge**, keine beobachteten Mautknoten, Depots oder Lieferadressen. Andere Zugänge
würden andere Routen und Zählstellenbeiträge erzeugen. Keine Gemeindezentroiden
wurden als angeblich beobachtete Betriebsstandorte ausgegeben.

### Durchgeführte Kette und Mengen

Aus den zwei gerichteten Monatsbeziehungen werden 11.140 veröffentlichte gerundete
Monatsbefahrungen repräsentiert; **764.700** der aufbereiteten Monatsbefahrungen
bleiben unzugeordnet. Innergemeindliche Beziehungen und vollständiger Transit sind
nicht enthalten. Annahmen: Kalenderdurchschnitt `/31`, 30 % `truck`, 70 % `trailer`,
stratifiziert gleichmäßige Abfahrten 06–20 Uhr. Weder Flottenanteile noch Stundenprofil
sind in diesem Maut-OD-Snapshot beobachtet. Der Quellenstichtag `2026-07-01` bezeichnet
den Beobachtungsmonat, keinen real gemessenen einzelnen Montag.

Die Probe nutzt die produktiven `parseCsv`, `FreightDemandGenerator.generate` und
`TrafficRoutes.resolve`. Seed 42 erzeugt bei zweimaligem Aufruf dieselben Trips.
Die physikalischen SUMO-Typen entsprechen den generischen MVP-Solo-LKW-/Sattelzug-
Defaults; daraus folgt keine regional beobachtete Flotte oder Emissionsvalidierung.
Zwei zusätzliche PKW sind synthetische Routingkontrollen; eine volle Population,
Paketnachfrage und Hintergrundbelastung wurden **nicht** simuliert.

| Teilrelation | Modellierte Tagesfahrten | Native SUMO-Abschlüsse |
|---|---:|---:|
| Dortmund → Unna, Solo-LKW | 65 | 65 |
| Dortmund → Unna, Sattelzug | 152 | 152 |
| Unna → Dortmund, Solo-LKW | 43 | 43 |
| Unna → Dortmund, Sattelzug | 99 | 99 |
| Schwere Fahrzeuge gesamt | **359** | **359** |
| Synthetische PKW-Kontrollen | 2 | 2 |

`duarouter` und SUMO liefen ohne Fehler. **Null verworfene/unerledigte Güterfahrten,
null Teleports.** Routenlängen 17,885–18,092 km; letzte Güterankunft um 20:14:21.
Die tatsächlichen Routen berühren keine der im Adapterbericht ausgewiesenen
unbedingten Sperren oder nicht unterstützten HGV-Tags. Allgemeine Zeit-, Gewichts-,
Höhen-/Breitenregeln sind dadurch nicht implementiert oder vollständig geprüft.
Die Tagesannahme hält Abstand zum nächtlichen Zeitfenster der im regionalen Snapshot
vorhandenen B1-HGV-Bedingungen.

Artefakte unter `.runtime/freight-routing-pilot-2026-10-13/`:

- `mapping.json` und `demand/freight-relations.csv`: Annahmen und 14-Spalten-Importdatei.
  Die gerichteten IDs gelten für genau das Pilotnetz; vor Import in ein neu generiertes
  vollständiges Szenarionetz ist die Kanten-/Klassenprüfung erneut nötig.
- `mapping-validation.json`: produktiver Kanten-/Klassenabgleich.
- `routes.rou.xml`, `executed-routes.xml`, `tripinfo.xml`, `statistics.xml`: native Evidenz.
- `traffic-routes.geojson`, `traffic-categories.json`: produktiver Export mit getrennten
  `private`-/`logistics`-Kategorien und ausdrücklich synthetischen PKW-Kontrollen.
- `pilot-report.json`: Abschlüsse, Grenzen, Dateihashes und zeitliche Netzabweichung.
- `experiment.json`: vorab festgelegte Kriterien (359 Abschlüsse, keine Verluste/Teleports)
  und beobachtete Ergebnisse. Urteil: begrenzter Routingnachweis bestätigt;
  regionale Realitäts-/Kalibrierbehauptung bleibt unentscheidbar.

### Amtliche Stationsgeometrien und erster Teilvergleich

Die [amtliche OpenGeoData-NRW-Bereitstellung](https://www.opengeodata.nrw.de/produkte/transport_verkehr/strassennetz/)
liefert `Zaehlstellen_EPSG25832_Shape.zip` unter DL-DE-BY 2.0, Attribution Straßen.NRW.
Der Download ist mit Hash archiviert. Die drei zur Monatszählbasis passenden IDs
wurden aus der DBF und dem Point-Shapefile gelesen und aus dem tatsächlichen
Projektions-WKT nach WGS84 umgerechnet. Dateibereitstellung Juni 2026, Feld
`NETZSTAND=20260331190000`.

**Datenkonflikt:** `44105318` (Brechten) und `44115365` (Wambeldorf) tragen im
Geodatensatz `ZSTARTKNZ=03`, also manuelle SVZ, und keine ausgewiesene Erfassung als
Dauerzählstelle. Der Monatsbericht führt sie in der automatischen Zählbasis.
Die IDs allein lösen diesen Konflikt nicht; beide bleiben im Vergleich
`not_comparable`. Eine Betreiberbestätigung oder eindeutig bezeichnete
Dauerzählstellengeometrie ist nötig. Brechten hat zudem im gefilterten Hauptstraßennetz
keine nahe passende B54-Kante.

`45115352` (Berghofen) ist als aktive automatische Dauerzählstelle (`01`) geführt.
Amtlicher Punkt: `7.546646146, 51.474107043`. Vorläufiger geometrischer Abgleich mit den
B236-Hauptfahrbahnen: Nordkante `23043737` in 5,11 m Abstand, Südkante `1094555317`
in 6,989 m Abstand; nächste gleichgerichtete Kandidaten jeweils mehr als 38 m entfernt.
Dies ist ein nachvollziehbarer geometrischer Abgleich, kein betreiberbestätigter
Abgleich der Installation. Beide Zählkanten liegen innerhalb der ausgeführten Routen,
keine ist deren Start- oder Zielkante.

| Station Berghofen, Juli Mo–So | Wert |
|---|---:|
| Beobachteter SGV-Gesamtquerschnitt | 2.236 Fahrzeuge/Kalendertag |
| Modellierte abgeschlossene Nordpassagen der Teilnachfrage | 217 |
| Modellierte abgeschlossene Südpassagen der Teilnachfrage | 142 |
| Modellierter Teilbeitrag / Beobachtung | **16,06 %** |

Das Verhältnis hängt unmittelbar von den gewählten B236-Zugangsproxies ab. Es ist
**keine unabhängig geschätzte Mautabdeckung und keine Kalibrierqualität**. Die
Differenz umfasst fehlende OD, Transit, Zugangs-/Flottenannahmen, Unterschiede der
Maut-/Zählklassifikation und mögliche Netzänderungen. Kein Faktor `2236/359`, RMSE
oder GEH wird als Kalibrierergebnis vorgeschlagen.

`scripts/compare-freight-counts.py` macht diesen Vergleich reproduzierbar. Es prüft
den genauen Netz-Hash und zählt nur abgeschlossene `truck`-/`trailer`-Fahrzeuge;
PKW und Busse fehlen korrekt im SGV-Modellbeitrag. Wiederholte Kantenpassagen zählen
mehrfach. Eine Zählkante am Routenanfang/-ende wird zurückgewiesen, weil ohne
Stationsposition und `departPos`/`arrivalPos` keine tatsächliche Passage bewiesen ist.
Simulationsstatistiken mit null Teleports sind Pflicht: teleportierte Fahrzeuge
könnten geplante Kanten überspringen und dennoch als abgeschlossen erscheinen.
SUMO schreibt in der ausgeführten Routendatei keine Typdefinitionen mit; deshalb
werden sie ausdrücklich mit `--types routes.rou.xml` samt Hash beigefügt.

```bash
python3 scripts/compare-freight-counts.py \
  --routes .runtime/freight-routing-pilot-2026-10-13/executed-routes.xml \
  --types .runtime/freight-routing-pilot-2026-10-13/routes.rou.xml \
  --network .runtime/freight-routing-pilot-2026-10-13/network.net.xml \
  --tripinfo .runtime/freight-routing-pilot-2026-10-13/tripinfo.xml \
  --statistics .runtime/freight-routing-pilot-2026-10-13/statistics.xml \
  --observations .runtime/freight-pilot-dortmund-unna-2026-10-12/count-observations.json \
  --mapping .runtime/freight-routing-pilot-2026-10-13/counter-mapping.json \
  --output .runtime/freight-routing-pilot-2026-10-13/count-comparison.json
python3 -m unittest discover -s scripts -p 'test_*freight*.py' -v
```

**Verifikation:** 17 Python-Tests erfolgreich, native SUMO-Kette durchgeführt.
Unabhängiger Review reproduzierte Abschlüsse, Klassen, Teleports, Zählkantenpassagen
und Vergleichsbericht. Sein Randkanten-Befund wurde im Comparator gehärtet und mit
einem Regressionstest gesichert. Keine Änderung der Java-/Frontend-Laufzeit in diesem
Schritt. Die Backend-/Frontend-Gates vom 12.10. gelten für den damaligen Implementierungsstand;
sie wurden für diese eigenständige Offline-Aufbereitung nicht erneut behauptet.

**Nächste fachliche Abnahme:** mehrere empirisch begründete Zugänge statt eines Proxypaars,
Zählstellenkonflikte klären, weitere Gemeinde-OD und Transit hinzufügen, historische
Netz-/Klassenkompatibilität sichern; erst anschließend regional kalibrieren und auf
zurückgehaltenen Messstellen validieren.

## 14. Mehrere Mautknotenzugänge und regionale OD-Erweiterung (2026-10-13)

**Umgesetzt und geprüft:** sechs Gemeinden statt ausschließlich Dortmund↔Unna,
neun veröffentlichte Mautknotenzugänge, mengenbilanzierte Aufteilung und drei native
SUMO-Läufe. Es bleibt eine ausdrücklich modellierte **Teilnachfrage**.

### Beobachtungen und Zugangskatalog

Für Juli 2026 wurden ergänzend die vier Startperspektiven Holzwickede, Kamen, Lünen
und Schwerte mit Zielen innerhalb der sechs Gemeinden abgerufen. 22 Rohzeilen ergänzen
die bisherigen fokalen Dortmund-/Unna-Abfragen. Nach gemeinsamer Perspektivenbereinigung
enthält die verfügbare Sammlung 3.177 OD und 842.570 veröffentlichte gerundete Monatswerte.
Die Sammlung ist keine vollständige Sechs-Gemeinden-Matrix mit sämtlichen Außenbeziehungen:
Die neuen Abfragen sind ausdrücklich auf die sechs Zielgemeinden eingeschränkt.

Innerhalb dieser Auswahl sind **28 gerichtete Beziehungen zwischen verschiedenen
Gemeinden** veröffentlicht, zusammen **72.430** Monatsbefahrungen. Holzwickede→Kamen
und Lünen→Holzwickede fehlen; fehlende Veröffentlichung wird nicht zu einer beobachteten
Null erklärt. **255.270** gemeindeinterne Monatsbefahrungen bleiben unaufgeteilt.
Über alle vorhandenen Rohbeobachtungen bleiben **770.140** Monatsbefahrungen unzugeordnet.

Die im Portal dokumentierte zusätzliche Abschnitts-API lautet:

```text
https://webgis.toll-collect.de/arcgis/rest/services/lkw-verkehrsportal/mautdaten_bund/FeatureServer/0/
```

Filter `monat=DATE '2026-07-01' AND tarifparameter='x'`, räumlich der eingefrorene
Hauptstraßenausschnitt `[7.30,51.40,7.85,51.65]`. 1.023 gerichtete Tarifabschnitte wurden
mit Mautknotennamen, Geometrien, Abschnittslänge und Befahrungszahl geladen; kein
Transferlimit gemeldet. Die Abschnittszahlen enthalten auch Transit und sind **keine
Gemeindeeinfahrtszahlen**. Sie wurden daher nicht als Zugangsgewichte verwendet.
Lizenz/Attribution laut Portal: DL-DE-BY 2.0, BALM/Toll Collect.

Sechs Gemeindegeometrien wurden aus der bestehenden OD-API geladen, zusätzlich
BKG-Attribution. Die Mautknotenpunkte wurden anhand der Gemeindegeometrie eindeutig
zugeordnet; Ortsnamen allein wären dafür nicht ausreichend.

| Gemeinde | Verwendete Mautknoten |
|---|---|
| Dortmund | Dortmund-Nordost (A2), Dortmund-Süd (A45) |
| Unna | Unna (A1), Unna-Ost (A44) |
| Holzwickede | B1 Holzwickede |
| Kamen | Kamen-Zentrum (A1) |
| Lünen | Lünen-Sued (A2) |
| Schwerte | Schwerte (A1), Schwerte-Ergste (A45) |

Diese Knoten sind veröffentlichte geografische Zugangskandidaten. Es ist **nicht
beobachtet**, welche Monats-OD welche Knoten, Fahrtrichtungen oder Lieferstandorte
nutzt. Der Katalog erhebt auch keinen Anspruch auf sämtliche verfügbaren Zugänge.
Pro Knoten wurden bis zu vier nahe Hauptfahrbahnkanten derselben Straße aufgenommen.
Der Adapter prüft Gemeindezugehörigkeit, höchstens 75 m Abstand und die native
LKW-/Sattelzugberechtigung.

### Offline-Aufteilung mit nativer Richtungsprüfung

`scripts/expand-toll-access-mapping.py` arbeitet in zwei Phasen:

1. Gemeindeübergreifende beobachtete OD mit den expliziten Zugangs-/Klassenvorgaben
   kombinieren und `routing-candidates.xml` für `duarouter` schreiben.
2. Zurückgegebene native Routen prüfen; pro Knotenpaar und Klasse den Kandidaten mit
   dem kleinsten offengelegten freien Fahrzeit-Proxy auswählen. Dieser Proxy summiert
   Kantenlänge/zulässige Geschwindigkeit mit 22,22 m/s Fahrzeugdeckel; er ist keine
   beobachtete Reisezeit. Anschließend positive Knotengewichte über die akzeptierten
   Knotenpaare normieren, getrennt je OD und Klasse.

Annahmen im Grundfall: gleich gewichtete Knoten je Gemeinde, 30 % Solo-LKW, 70 %
Sattelzugklasse und stratifiziert gleichmäßige Abfahrten 06–20 Uhr. Die Annahmen
werden samt Katalog und Rohdaten-/Netz-/Routenhashes exportiert. Nicht unterstützte
OSM-HGV-Regeln werden in dieser Probe konservativ durch Ausschluss betroffener
Routingkandidaten behandelt; das implementiert keine allgemeine Ausnahmen-/Zeitregel.

1.328 Kandidaten → 1.018 native Routen, 310 ohne Route; weitere 48 zurückgegebene
Routen wegen nicht unterstützter HGV-Ways ausgeschlossen. Das betrifft Kandidaten,
keine verlorenen erzeugten Güterfahrten. Alle 28 OD haben danach für beide
positiven Fahrzeugklassen mindestens ein akzeptiertes Knotenpaar.
Die Normierung ist auf **zurückgegebene akzeptierte Kandidaten** konditioniert;
sie beschreibt nicht sämtliche denkbaren zulässigen Routen.

Die Monatsanteile je OD summieren sich zu eins. Fehlt für eine positive Klasse
jeder akzeptierte Kandidat, bleibt die ganze OD unzugeordnet statt deren Menge
still auf eine andere Fahrzeugklasse zu übertragen. Eine neue Kandidatenphase
entfernt alte `mapping.json`/`distribution-report.json`, damit neue Kandidaten
nicht neben einer veralteten gültig aussehenden Zuordnung stehen.
Native Fahrzeugtyp, Start-/Zielkante und Klassenberechtigungen müssen zum
ursprünglichen Kandidaten passen.

Der bestehende Tagesadapter erzeugt aus 128 Mappingzeilen **125 positive Tageszeilen**;
drei Zeilen runden auf null. Die veröffentlichte Monatsbasis bleibt im Bericht erhalten.
Insgesamt **2.336** gerundete Kalenderdurchschnittsfahrten, kein beobachteter Normalwerktag.
Die gerichteten IDs gelten für das eingefrorene Pilotnetz; ein vollständiges neu
konvertiertes Szenarionetz muss sie erneut prüfen.

### Native Abnahme und Sensitivität

Die produktiven Java-Gütergenerator-/Mapperprüfungen, `duarouter` und SUMO sind
für die Grundvariante sowie zwei weitere Varianten durchgeführt. Keine volle
Personenpopulation oder Paket-/Hintergrundbelastung simuliert; zwei PKW bleiben
synthetische Kontrollen. Oktober-Netz und Juli-Beobachtung sind zeitlich nicht
nachweislich identisch.

| Grundvariante | Ergebnis |
|---|---:|
| Erzeugte / abgeschlossene Güterfahrten | **2.336 / 2.336** |
| Modellierte Solo-LKW / Sattelzüge | 701 / 1.635 |
| Synthetische PKW-Kontrollen abgeschlossen | 2 |
| Teleports / am Ende fahrend / wartend | 0 / 0 / 0 |
| Späteste Ankunft | 20:28:35 |
| Modellierte Berghofen-Passagen | 518 |

Für die Sensitivitätsprüfung ändern sich ausschließlich die Knotengewichte von
Dortmund-Nordost und Dortmund-Süd. OD-Monatsmengen, andere Gemeindegewichte,
Klassenanteile, Fahrzeitfenster, Seed 42, Netz und native Routingkandidaten bleiben
gleich. Pro Variante werden die neue Tagesaufteilung und die native Ausführung
geprüft; alle schließen 2.336 Güterfahrten ohne Teleports oder Restfahrzeuge ab.

| Dortmund Nordost : Süd | Berghofen-Passagen der Teilnachfrage |
|---|---:|
| 1 : 1 | 518 |
| 3 : 1 | 331 |
| 1 : 3 | 704 |

**Negativbefund:** Vorab wurde für Nordost-Betonung eine Zunahme, für Süd-Betonung
eine Abnahme um 20–150 Passagen/Tag erwartet. Tatsächlich tritt die umgekehrte
Richtung und eine Abweichung von rund 187 auf. Richtungs- **und** Größenvorhersage
sind widerlegt und im unveränderten Vorhersageprotokoll entsprechend bewertet.
Der Mechanismus muss anhand der gewählten Routen beurteilt werden, nicht allein
anhand der Lagebezeichnung eines Startknotens.

Belegt ist die erhebliche Abhängigkeit des Stationsbeitrags vom Zugangsprior trotz
gleicher veröffentlichter OD-Menge. Daraus folgt **keine Kalibrierqualität oder reale
Mautabdeckung**. Der beobachtete SGV Berghofen beträgt weiterhin 2.236/Tag;
Brechten/Wambeldorf bleiben wegen der in Abschnitt 13 beschriebenen widersprüchlichen
Stationsarten ungeklärt. Es wird kein Skalierungsfaktor auf diesen Teilbeitrag gefittet.

### Artefakte und Wiederholung

Ordner `.runtime/freight-regional-pilot-2026-10-13/`:
`access-catalog.json`, `municipalities.geojson`, `sections.geojson`, Quellenmanifeste,
`distribution/`, `demand/freight-relations.csv`, produktive Kantenprüfung,
native Routen/Tripinfos/Statistiken, `traffic-routes.geojson`, `count-comparison.json`,
`pilot-report.json`, `experiment.json` und `sensitivity-plan.json`.
Varianten mit eigenständigen Ausgaben unter `variants/north-heavy` und `variants/south-heavy`.
Ein reproduzierbarer lokaler Ablauf ist in den archivierten Abruf-/Probe-Skripten
und `run-sensitivity.py` enthalten; Rohdaten werden nicht in das Repository aufgenommen.

Der neue Adapter wird vor und nach dem nativen Kandidatenrouting mit identischen
`--month`, `--catalog`, `--network`, `--network-geo`, `--municipalities`, `--permissions`,
`--output` und den Maut-Rohdateien aufgerufen. In Phase 2 zusätzlich
`--routes <candidate-routes.rou.xml>`. Das resultierende `mapping.json` geht als
`--mapping` an `prepare-toll-freight.py`. Die vollständigen Eingabehashes stehen
im Verteilungsbericht. Mautabschnitte und OD werden nicht addiert.

```bash
python3 -m unittest discover -s scripts -p 'test_expand_toll_access_mapping.py' -v
python3 -m unittest discover -s scripts -p 'test_*freight*.py' -v
```

**Abgeschlossen:** acht neue Methodentests plus 17 vorhandene Aufbereitungstests,
drei native Simulationsläufe und unabhängiger Read-only-Review. Mengen, Abschlüsse,
Hashes und Sensitivität wurden reproduziert; Lifecycle-Befund behoben und getestet.
Keine neue Java-/Frontend-Laufzeitabhängigkeit, kein aktivierter Default und keine
Änderung an bestehenden Nutzerszenarien in diesem Schritt.

**Fachliche Grenze:** Innergemeindliche Fahrten, alle übrigen Außen-OD und Transit
sind nicht vollständig integriert. Abschnittsmarginalen allein bestimmen keine
eindeutige Transit-OD. Vor einer belastbaren regionalen Kalibrierung sind zusätzliche
OD-/Verkehrserzeugerinformationen, kompatible vollständige Netz-/Klassen-/Zeitbasis
und unabhängige zurückgehaltene Zählstellen nötig. Ein kalibrierter Gesamtverkehr
wird mit dieser Teilnachfrage nicht behauptet.

## 15. Unternehmenstouren: Quellenstand und LLM-gestützter Import (Konzept, 2026-10-13)

**Ziel:** Unternehmen laden bestehende Tourenplanungen hoch. SyntheSis überführt sie
in einen geprüften Tourenvertrag und simuliert die regional relevanten Fahrten
zusätzlich zum Personenverkehr und zu den bereits aktivierten Logistikbeiträgen.
Die Tourreihenfolge und die gemeinsame Fahrzeugidentität bleiben erhalten.
Dieser Abschnitt beschreibt eine geplante Erweiterung. Ein Import beliebiger
Unternehmensdateien ist aktuell noch nicht implementiert.
Die erste CSV-/XLSX-Importstufe wurde anschließend am 14.10.2026 umgesetzt;
der aktuelle Umfang steht in Abschnitt 16. Die folgende Bestandsaufnahme beschreibt
den Konzeptstand vom 13.10.2026.

### 15.1 Welche Güterverkehrsdaten heute tatsächlich einfließen

„Automatisch“ unterscheidet Quellenabruf, Datentransformation und Verwendung einer
bereits bereitgestellten Datei. Ein erfolgreicher Datenpilot aktiviert keine Quelle
in anderen Szenarien.

| Quelle / Daten | Aktuelle Verwendung | Automatisierungsgrenze |
|---|---|---|
| OSM: Straßennetz, geeignete Depot-POIs, Packstationen | Netzabruf bzw. Snapshot im jeweiligen OSM-/SUMO-Pfad; Logistikstandorte, soweit im verwendeten OSM-Datensatz vorhanden | Fehlende Logistikstandorte werden modelliert. OSM liefert keine beobachteten Güterverkehrsmengen. |
| OSM: explizite HGV-Verbote | `OsmFreightPermissions` übernimmt unterstützte unbedingte, richtungsbezogene Verbote beim SUMO-Netzaufbau | Keine vollständige Abbildung von Gewichts-, Höhen-, Zeit- und Ausnahmeregeln. |
| Paketnachfrage und logiTopp-Touren | Automatische Modellierung aus Bevölkerung, Szenarioparametern und Modellparametern; Verwendung des erzeugten `planned-tours.csv` in SUMO | Synthetische Nachfrage, keine automatisch abgerufenen realen Bestellungen. Nutzervorgaben und OSM ergänzen Standorte. |
| Schwerverkehrs-OD im Plattform-CSV | Nach Upload und Aktivierung erzeugt `FreightDemandGenerator` zusätzliche LKW-/Sattelzugfahrten | Manuelle Eingabe einer vorbereiteten Tagesnachfrage; standardmäßig deaktiviert. Keine allgemeinen Unternehmenstouren. |
| SEVAS NRW | Nach GeoJSON-Upload und Aktivierung wendet `SevasFreightPermissions` unterstützte einfache LKW-Sperren an | Kein laufender Produktabruf. Vorrangrouten und komplexe Restriktionen noch nicht vollständig unterstützt. |
| Maut / Toll Collect / amtliche Mautstatistik | Offline-Aufbereitung, Zugangszuordnung und regionale Routingpiloten durch `prepare-toll-freight.py` und `expand-toll-access-mapping.py`; Ergebnis über OD-CSV nutzbar | Kein automatischer Mautabruf oder regionales Nachfragemodell bei jedem Szenariolauf. Monatswerte benötigen explizite Tages-/Klassen-/Zugangsannahmen. |
| KBA-LKW-Daten, öffentliche Forschungsdaten | Offline-Auswertung mit `inspect-kba-freight.py`; Referenzen für Klassen, Leerfahrten und Distanzen | Noch keine automatische regionale Nachfrageableitung. |
| BASt / Straßen.NRW-Zählungen | Offline-Referenzen und SGV-Teilvergleiche mit `compare-freight-counts.py` | Keine automatische flächendeckende Kalibrierung. SV inklusive Busse und SGV müssen getrennt bleiben. |
| VP2040, weitere KBA-Verkehrsstatistiken, Statista | Recherchierte Ergänzungsquellen bzw. Kontext | Noch keine produktiven Importadapter. Statista ist keine frei verfügbare Primärdatenschnittstelle für unsere Nachfrage. |
| Zurbrüggen-Tourenplanung | Lokale Struktur- und Plausibilitätsanalyse für dieses Konzept | Noch keine importierten Touren, Geokodierung oder Simulation. |

Codebelege: `LogisticsGenerator`, `FreightDemandGenerator`, `OsmFreightPermissions`,
`SevasFreightPermissions`, `ScenarioController` (Uploads unter `/freight` und
`/freight/sevas`), `GenerationService` und `SumoRunner`, jeweils unter
`backend/src/main/java/de/synthesis/`. Quellenlinks und bisherige Datenpiloten stehen
in den Abschnitten 3–14.

### 15.2 Befund aus dem Zurbrüggen-Beispiel

Quelle: bereitgestellte `Auslieferungsdaten 29.09.2026.csv`, lokal gelesen am
13.10.2026. UTF-8 mit BOM, Semikolon, 222 Datenzeilen und 41 Spalten.
SHA-256: `454746df8eb9df41c30cac6d5ddac19e5d9d9b6272f8328af05450ffff5c6bca`.
Die Originaldatei bleibt außerhalb des Repositories. Hier stehen ausschließlich
Strukturmerkmale und aggregierte Ergebnisse, keine Kundenadressen oder Kundennamen.

| Beobachtung | Bedeutung für den Import |
|---|---|
| 15 benannte Fachspalten, anschließend leere oder datenartig beschriftete Zusatzspalten | Spalten per Position und Header-Vorkommen identifizieren; technische Zusatzfelder erhalten, Bedeutung offenlassen. |
| Zweimal `Zeitfenster Kunde`, an Position 8 und 9 (einsbasiert) | Vorschlag „Beginn/Ende“ prüfen; ein Wörterbuch nur nach Spaltenname würde ein Feld überschreiben. |
| `Liefertag` in allen Zeilen `20260929` | Feldbezogen `yyyyMMdd` lesen: Dienstag, 29.09.2026. Andere technische Datumsfelder haben abweichende Muster. |
| 41 Tourkennungen, 222 unterschiedliche Vorgänge, 221 unterschiedliche Auftragsnummern | Tourkennung ist noch kein belegtes Fahrzeug. Nicht anhand der Auftragsnummer allein deduplizieren. |
| 187 mögliche Halte aus Tour, Datum, normalisierter Adresse und identischen geplanten Ankunfts-/Abfahrtszeiten | Mehrere Auftragszeilen können zu einem Halt gehören. Gruppierung ist ein Vorschlag und fachlich noch nicht bestätigt. |
| Keine exakt doppelten Datenzeilen; 28 der möglichen Halte enthalten mehrere Zeilen | Mehrere Aufträge am Halt erhalten. Geplante Standzeit nur einmal ansetzen. |
| Bei allen 187 möglichen Halten entspricht die Summe der `Arbeitswerte` der Differenz zwischen geplanter Abfahrt und Ankunft in Minuten | Starke Hypothese zur Einheit, keine bestätigte Definition. Standzeit zunächst aus den ausdrücklichen Planzeiten ableiten. |
| Drei mögliche Halte liegen mit Ankunft oder Abfahrt außerhalb des Kundenfensters | Prüffälle anzeigen. Erst klären, ob das Fenster nur die Ankunft oder die gesamte Bedienung begrenzt. |
| Keine negativen Standzeiten oder zeitlichen Überschneidungen aufeinanderfolgender möglicher Halte | Zeitliche Struktur ist intern plausibel. Fahrzeitmachbarkeit wurde ohne Geokodierung noch nicht geprüft. |
| Auftragsarten `KDD` (39) und `AKP` (183) | Rohcodes erhalten; Bedeutung nicht durch das LLM erfinden. |

Geplante Ankünfte liegen zwischen 07:23 und 15:41 Uhr, Abfahrten zwischen 08:06 und
20:12 Uhr. Die Datei enthält keine eindeutig ausgewiesenen Depotstart-/Rückkehrdaten,
Fahrzeugidentitäten oder Fahrzeugklassen und keine explizite Stoppnummer.
Eine chronologische Sortierung ist ein Mappingvorschlag, keine belegte Tourreihenfolge.
41 Tourkennungen und 187 Haltkandidaten sind deshalb keine bereits validierten
41 Fahrzeugtouren mit 187 realen Halten.

**Folgerung:** Die Datei eignet sich für einen Importentwurf mit Auftrags- und
Kundenhaltketten. Für vollständige Depot-zu-Depot-Touren brauchen wir zusätzliche
Unternehmensangaben. Eine bewusste offene Tour kann später als eigener Modus zulässig
sein; der Import darf fehlende Depotfahrten und Rückfahrten nicht still ergänzen.

### 15.3 Importablauf: LLM schlägt Regeln vor, geprüfte Regeln übernehmen Daten

1. **Upload und Strukturprüfung:** CSV/TSV und XLSX zuerst unterstützen. Kodierung,
   Trennzeichen, Tabellenblätter, Kopfzeilen, Zelltypen und Formeln erkennen.
   Mehrzeilige Tabellen, Summenzeilen und verbundene Zellen separat markieren.
   Makros, Formeln und eingebettete Programme nicht ausführen. Weitere Formate wie
   JSON, XML, PDF oder Scans benötigen eigene Parser bzw. OCR und andere Prüfungen;
   „beliebiges Format“ bedeutet eine erweiterbare Adapterarchitektur.
2. **Profil und LLM-Vorschlag:** Spaltenstruktur, Typstatistik und begrenzte,
   pseudonymisierte Beispiele an das lokale LLM geben. Auch datenartig beschriftete
   Kopfzellen und unbekannte Zusatzspalten werden vorab auf sensible Werte geprüft
   und maskiert. Es liefert einen typisierten
   Mappingplan: Quellspalten, Zielfelder, erlaubte Transformationen, Gruppierung,
   Reihenfolge und offene Fragen. Keine vollständige Datei pro LLM-Aufruf.
3. **Deterministische Vorschau:** Mapping gegen JSON-Schema prüfen und auf alle
   Zeilen anwenden. Der Regelkatalog enthält beispielsweise Datums-/Zeitparser,
   Dezimalkomma, Einheitenumrechnung und bestätigte Gruppenschlüssel. Er enthält
   keinen frei erzeugten Python-/JavaScript-Code und kein `eval`.
4. **Fachliche Prüfung:** Vorschau der Touren und Halte, Anzahl verarbeiteter Zeilen,
   Gruppierungen, unbekannte Felder und konkrete Fehler anzeigen. Für einen neuen
   Exporttyp bestätigt der Nutzer das Mapping und ergänzt fehlenden Kontext.
   Unklare Fahrzeugklassen, Einheiten und Depotangaben verhindern die Aktivierung
   vollständiger Touren. Ein LLM-Konfidenzwert ersetzt keine Validierung.
5. **Orts- und Netzprüfung:** Adressen mit einem freigegebenen Geocoder auflösen,
   Qualität und Herkunft speichern, gerichtete zulässige Fahrzeugzugänge finden.
   Mehrdeutige Treffer bleiben Prüffälle. Ein LLM erzeugt keine Koordinaten.
6. **Szenarioaktivierung:** Nur freigegebene Versionen mit gültiger Region-, Datum-,
   Fahrzeug- und Tourprüfung hinzufügen. Planung bleibt erhalten; es findet keine
   automatische Neuoptimierung der Unternehmensreihenfolge durch logiTopp statt.

Ein gespeicherter Plan erhält Exportprofil, Schemaversion, Quellhash, Modell-/Prompt-
Version und bestätigte Annahmen. Folgedateien desselben Formats werden damit
regelbasiert verarbeitet. Neue Spalten, geänderte Datentypen oder verletzte Regeln
lösen eine erneute Prüfung aus. Temperatur 0 allein garantiert keine reproduzierbare
LLM-Antwort; der eingefrorene Mappingplan ist die Grundlage reproduzierbarer Importe.

Beim Zurbrüggen-Profil wären `Liefertag`, `Tour`, `Vorgang`, `Auftragsnummer`, Adresse
und Planzeiten direkt zuordenbar. Die beiden Fensterfelder werden anhand ihrer
Position unterschieden. `Arbeitswerte`, Auftragsarten und Zusatzspalten bleiben
mit Rohwert und ungeklärter Semantik erhalten. Jede Zielinformation trägt einen
Verweis auf Blatt/Zeile/Spalte oder eine ausdrücklich bestätigte Kontextangabe.

### 15.4 Kanonischer Tourenvertrag

Empfohlenes Zielformat: versioniertes JSON `synthesis-company-tours-v1`.
Tabellarische CSV-Exporte für Touren, Halte und Aufträge können daraus abgeleitet
werden. Die bestehende Schwerverkehrs-OD-CSV bleibt ein eigener Nachfragevertrag.

| Ebene | Wesentliche Felder und Regeln |
|---|---|
| Import | `importId`, `companyId`, `sourceHash`, `schemaVersion`, `mappingVersion`, `revision`, `timezone`, Quellzeitraum, Status, bestätigte Annahmen und Zeilenbilanz |
| Fahrzeug | pseudonymisierte `vehicleId`, `vehicleTypeRef` auf geprüften Fahrzeugkatalog; nötige Maße/Gewichte mit Einheiten und Herkunft. Leichter Lieferwagen, LKW und Sattelzug unterscheiden. |
| Tour | `tourId`, `sourceTourCode`, `serviceDate`, `vehicleId`, `start`, `end`, `returnPolicy`, geordnete `stops[]`; Identität aus Unternehmen, Datum, Kennung und gegebenenfalls Umlauf |
| Halt | `stopId`, `sequence`, Ort, Geokodierungs-/Netzqualität, `plannedArrival`, `plannedDeparture`, `plannedDwellSec`, Kundenfenster mit bestätigter Bedeutung, `orderRefs[]` und Quellbelege |
| Auftrag | eigener Vorgangsschlüssel, optionaler Auftragsnummernbezug, Roh-Auftragsart, optionale Mengen/Gewichte mit bestätigten Einheiten. Mehrere Aufträge je Halt zulassen. |
| Szenariobeitrag | `trafficCategory=logistics`, `demandSource=company-tours`, Importrevision, Unternehmen und Tourbezug, Zusammensetzungsmodus sowie regionale Abdeckung |

Zeitangaben werden mit Datum und Zeitzone geführt, anschließend in Simulationssekunden
umgerechnet. Sommerzeitwechsel, Fahrten über Mitternacht und mehrere Betriebstage
brauchen ausdrückliche Regeln. Plandaten bleiben von Ist-Beobachtungen unterscheidbar.
Das Lieferdatum wird nicht automatisch auf den Szenariowochentag verschoben und
ein einzelner Liefertag nicht als täglich wiederkehrende Nachfrage behandelt.
Eine gewünschte Datumsverschiebung wird als eigene Annahme gespeichert.
Der aktuelle `SumoRunner` begrenzt den Lauf auf 86.400 Sekunden. Im ersten MVP
werden vollständige Touren, deren geplantes Ende außerhalb dieses Tageshorizonts
liegt, nicht aktiviert. Ein Nacht-/Mehrtagemodus benötigt zuvor eine nativ geprüfte
Erweiterung des Simulationshorizonts und des Tourenexports. Bleibt eine zunächst
passende Tour wegen Verspätung am Laufende offen, wird sie als unvollständig
ausgewiesen. Abgeschlossene einzelne Teilfahrten bedeuten keinen Tourabschluss.
Dieselbe Fahrzeugidentität darf nicht gleichzeitig mehreren überlappenden Touren
zugewiesen sein. Fahrzeiten zwischen Halten und Standortwechsel zwischen Umläufen
werden mit der gewählten Fahrzeugklasse geprüft. Ladung/Kapazität und Fahrerregeln
sind nur prüfbar, wenn die hierfür benötigten Daten und Regeln ausdrücklich
vorliegen; eine zeitlich plausible Planung beweist diese zusätzlichen Bedingungen
nicht.

Synthetisches Beispiel für die Struktur eines **unvollständigen Entwurfs**:

```json
{
  "schemaVersion": "synthesis-company-tours-v1",
  "timezone": "Europe/Berlin",
  "status": "needs-context",
  "tours": [{
    "tourId": "example-company:2026-09-29:tour-01",
    "vehicleId": null,
    "start": null,
    "end": null,
    "returnPolicy": "unknown",
    "stops": [{
      "stopId": "stop-01",
      "sequence": 1,
      "locationRef": "protected-location-01",
      "plannedArrival": "2026-09-29T09:00:00+02:00",
      "plannedDeparture": "2026-09-29T10:15:00+02:00",
      "plannedDwellSec": 4500,
      "orderRefs": ["example-order-a", "example-order-b"]
    }]
  }],
  "issues": ["missing-vehicle", "missing-start", "missing-return-policy"]
}
```

Das Beispiel enthält keine Kundenwerte aus der Quelldatei. Nullable Kontextfelder
sind im Entwurf zulässig. Der Vertrag für aktivierbare vollständige Touren muss die
benötigten Felder dagegen verbindlich verlangen.

### 15.5 Zusätzliche Nachfrage, Region und Vermeidung doppelter Beiträge

Die gewünschte Standardeinstellung ist **zusätzlich**: Die aktivierten
Unternehmenstouren werden zu den bestehenden Verkehrsschichten hinzugefügt.
Auswertungen zeigen Personenverkehr, synthetische Paketlogistik, allgemeinen
Schwerverkehr und Unternehmenstouren getrennt nach Herkunft. Eine bereits in der
Mautstatistik enthaltene Unternehmensfahrt kann dabei doppelt repräsentiert sein.
Das Ergebnis ist ohne Abgleich ein zusätzliches Szenario, kein belegter Gesamtbestand.

Ein späterer Modus „enthaltenen Beitrag ersetzen“ braucht nachgewiesene Überdeckung
nach Quelle, Zeitraum, Klasse und räumlichem Bezug. Keine pauschale Subtraktion
einer Unternehmensquote aus allen OD und keine negativen Mengen. Synthetische
Paketaufträge werden ebenfalls nur bei belegter Überschneidung ersetzt.

Erneuter Upload derselben Unternehmensdatei ist idempotent. Eine korrigierte Planung
ersetzt atomar die ausgewählte Importrevision. Sie erzeugt keine zweite aktive
Kopie. Deduplizierung arbeitet im Unternehmens-/Datums-/Vorgangskontext; identische
Adressen oder mehrfach vorkommende Auftragsnummern sind kein Löschkriterium.
Population-Sampling darf vollständige beobachtete Unternehmenstouren nicht still
skalieren oder einzelne Halte entfernen. Im ersten MVP ist eine kompatible
Vollnachfrage vorgesehen; spätere Skalierung benötigt einen eigenen Vertrag.

Die regionale Auswahl betrachtet Tourverläufe: Binnen-, Quell-, Ziel- und
Durchgangsverkehr. Eine Tour ohne Lieferadresse innerhalb der Region kann sie
trotzdem durchfahren. Die tatsächliche Route und das gewählte Netz bestimmen die
Relevanz; eine gerade Linie zwischen Adressen reicht dafür nicht.

Bevorzugt wird zunächst ein ausreichend gepuffertes Netz, in dem die komplette
relevante Tour fahrbar bleibt. Andernfalls müssen Gebietseintritt/-austritt,
außerhalb liegende Fahr- und Bedienzeiten sowie gemeinsame Fahrzeugidentität
erhalten werden. Mehrfaches Verlassen und Wiedereintreten darf nicht zu einer
sofortigen Abkürzung zwischen inneren Halten führen. Der erste MVP weist solche
ununterstützten Touren zur Prüfung zurück, bis ein nativer Randbehandlungstest
vorliegt. Fahrzeugzahlen und regionale Eintritte werden getrennt gezählt.
Berichte bilanzieren eingeschlossene, ausgeschlossene und ungeklärte Touren/Zeilen.

### 15.6 Simulation und Einbindung in SyntheSis

Eine Unternehmenstour wird als zusammenhängende Fahrzeugroute mit geordneten
Halteereignissen ausgeführt. Dasselbe Fahrzeug fährt die Lieferkette und, wenn
belegt, die Rückfahrt. Ein Halt mit drei Aufträgen erzeugt einen Aufenthalt.
Spätere Halte übernehmen Verzögerungen desselben Fahrzeugs.

SUMO bietet dafür Fahrzeuge mit geordneten `stop`-Elementen. `duration` legt eine
Mindeststandzeit fest, `until` eine früheste Weiterfahrt; `arrival` beschreibt eine
geplante Ankunft und erzwingt sie nicht. Die konkrete Kombination mit unserem
Planzeitvertrag muss ein nativer Test prüfen. Halte benötigen zulässige Kanten und
Positionen; passende Park-/Ladeflächen oder geeignete Straßenzugänge verhindern,
dass ein falsch zugeordneter Halt etwa auf einer Autobahnspur landet.
Primärquellen: [SUMO-Fahrzeuge, Routen und Halte](https://sumo.dlr.de/docs/Definition_of_Vehicles,_Vehicle_Types,_and_Routes.html#stops),
[SUMO-Logistikspezifikation](https://sumo.dlr.de/userdoc/Specification/Logistics.html).

Die vorhandene `readDeliveryLegs`-Schnittstelle ist spezifisch für den logiTopp-
Export. Sie ist kein allgemeiner Tourenimport und kein Nachweis einer durchgehend
identischen Fahrzeuginstanz für importierte Touren. Unternehmenstouren benötigen
einen eigenen geprüften Adapter. Ungültige oder nicht routbare Touren dürfen nicht
mit gesundem Gesamtstatus still verschwinden oder durch synthetische Touren ersetzt
werden. Ein freigegebener Teilimport muss vollständig über verworfene ganze Touren
und ihre Gründe berichten.

Geplante Bausteine: `CompanyTourImportService` für Upload/Revisionen, Parseradapter,
`TourMappingPlanner` für den LLM-Regelvorschlag, deterministischer Mapper,
`CompanyTourValidator` und `CompanyTourSumoAdapter`. Diese Namen bezeichnen Vorschläge,
keine vorhandenen Klassen. Import und fachliche Freigabe erfolgen vor der
Generierung. Die Nutzung erfolgt in der vorhandenen Logistik-/SUMO-Vorbereitung,
nach Anwendung der Netzrestriktionen. Keine neue parallele Generierung und keine
Änderung des seriellen Executor-Vertrags oder der KIT-Kerne.

Ein separates Szenario-Flag, vorgeschlagen `companyTours.enabled=false`, aktiviert
freigegebene Importrevisionen. Das bestehende lokale LLM-Angebot kann über eine
getrennte Importkonfiguration genutzt werden. Modell, Temperatur, Zeitlimits und
Antwortschema werden nicht ungeprüft aus der GTA-Verhaltensgenerierung übernommen.
Das genaue Upload-/Artefaktlayout muss vor Implementierung mit dem Architekturvertrag
abgeglichen werden; Quellhash und Mappingversion gehören in die Eingabeprovenienz.

### 15.7 Datenschutz und Datenhoheit

Kundennamen und Kontaktdaten sind für den Verkehrsablauf nicht erforderlich. Adressen
werden ausschließlich für Ortszuordnung benötigt und getrennt von den Simulations-
artefakten geschützt gespeichert. Tour-/Auftrags-/Fahrzeugkennungen werden im
Export pseudonymisiert. Rohdateien, Zuordnungstabellen und Logs erhalten definierte
Zugriffs- und Löschregeln; Kundenwerte gehören nicht in allgemeine Logs oder Testfixtures.

Standard ist lokales Mapping mit begrenzten, maskierten Beispielen und einem lokalen
oder ausdrücklich freigegebenen Geocoder. Cloud-LLM oder externe Adressgeokodierung
sind gesondert zu konfigurieren und hinsichtlich Empfänger und übertragenen Feldern
freizugeben. Datei- und Zellinhalte gelten als untrusted data; darin enthaltene
Anweisungen ändern weder Mappingauftrag noch Werkzeugrechte. Die vorliegende Datei
wurde ausschließlich lokal ausgewertet, ohne externe Übertragung oder Geokodierung.

### 15.8 Umsetzungspakete und Abnahme

| Paket | Ergebnis | Abnahme vor der nächsten Stufe |
|---|---|---|
| 1. Dateiprüfung und Importentwurf | CSV-/XLSX-Parser, Profil, versionierter Tourenvertrag, Vorschau und Zeilenbelege | Synthetische Fixtures prüfen doppelte Header, mehrere Aufträge je Halt, Datumsmuster und technische Spalten. Jede Zeile verarbeitet, begründet ausgeschlossen oder als ungeklärt erhalten. |
| 2. LLM-Mapping und Exportprofile | Lokaler Vorschlag erlaubter Regeln, manuelle Korrektur, gespeichertes bestätigtes Profil | Ungültige LLM-Antworten und Zell-Anweisungen können keine Ausführung auslösen. Wiederholung mit eingefrorenem Plan produziert gleiche fachliche Ausgabe. Formatdrift wird erkannt. |
| 3. Fachkontext und Geokodierung | Depot, Fahrzeuge, Fenstersemantik, Reihenfolge und Orts-/Netzprüfung | Fehlende Pflichtangaben blockieren Aktivierung; mehrdeutige Ortszuordnung bleibt sichtbar. Keine erfundenen Fahrzeugklassen, Depots oder Koordinaten. |
| 4. Nativer Touren-Spike | Ein Fahrzeug mit mindestens drei Halten, mehrere Aufträge an einem Halt, belegte Rückfahrt, gleichzeitig PKW | Eine Fahrzeugidentität über alle Halte; Reihenfolge und einmalige Standzeiten stimmen; Verspätung wird fortgetragen, PKW fahren weiter. Keine verlorenen Halte oder unbemerkten Teleports. Randfälle separat beweisen. |
| 5. Studio und Szenariobeitrag | Upload, Vorschau, Karte, Freigabe, default-aus-Flag, zusätzliche SUMO-Nachfrage, Herkunfts-KPIs | Baseline ohne Flag unverändert. Wiederholter Upload und Revisionswechsel erzeugen keine Doppelkopien. Fehler-/Teilimportbericht stimmt mit SUMO-Ausgabe überein. |
| 6. Erweiterte Formate und Bestandsabgleich | Weitere Parser/OCR, wiederkehrende Profile, belegter Ersatz überlappender Nachfrage | Je Format eigene Testbelege; Wiederkehr nur explizit. Abgleich gegen Statistik ohne pauschale Mengenabzüge. |

**Nächster konkreter Schritt:** Paket 1 und 2 mit einem synthetischen Zurbrüggen-
ähnlichen Fixture vorbereiten. Parallel fachlich klären: Depotstart/-ende,
Fahrzeug-/Tourumlauf, Bedeutung von `KDD`/`AKP` und `Arbeitswerte`, Kundenfenster-
Semantik, bestätigte Reihenfolge sowie Umgang mit Fahrten außerhalb der Zielregion.
Die 222 Zeilen, 41 Kennungsgruppen und 187 Haltkandidaten dienen als Prüfgrößen für
den Importentwurf, nicht als bereits bestätigte Verkehrsnachfrage.

**Konzeptprüfung:** Unabhängiger Read-only-Review hat die Ist-Stand-Aussagen gegen
Generatoren, Uploads und SUMO-Code geprüft. Der gefundene 24-Stunden-Horizont wurde
als ausdrückliche MVP-Grenze ergänzt. Keine Importimplementierung oder native
Unternehmenstourensimulation wurde mit diesem Konzeptschritt abgenommen.

## 16. Unternehmenstouren: CSV-/Excel-Importentwurf (✅ 2026-10-14, experimentell)

**Umgesetzt:** Im Studio steht unter der Paketlogistik „Unternehmenstouren importieren“.
Nach dem Speichern des Szenarios lassen sich UTF-8-CSV/TSV und XLSX hochladen,
Tabellenblatt und Kopfzeile wählen, vorgeschlagene Spaltenzuordnungen korrigieren,
Halte/Aufträge prüfen und das Mapping bestätigen. Der bestätigte Entwurf ist als
JSON `synthesis-company-tours-v1` herunterladbar. Die erste Stufe verändert keine
Nachfrage und aktiviert keine SUMO-Touren; alle Entwürfe haben `simulationReady=false`.

`TourTableReader`, `CompanyTourMapper`, `CompanyTourImportService`,
`TourMappingPlanner` und `CompanyTourController` liegen unter
`backend/src/main/java/de/synthesis/companytour/`. Das Studio verwendet eine eigene
`CompanyTourImportComponent`; die Pipeline und ihre Executor-Konfiguration bleiben
unverändert.

### 16.1 Parser, Mapping und Auftrags-/Haltvertrag

- Begrenzungen: 5 MB Eingabedatei, 10.000 Datenzeilen, 200 Spalten, 20 Excel-Blätter,
  30 MB entpackte XLSX-Bestandteile. CSV unterstützt Quotes, escaped Quotes, BOM,
  CRLF und mehrzeilige Felder. Andere Kodierungen müssen vorher als UTF-8 exportiert werden.
- XLSX liest Blattreferenzen, Shared-/Inline-Strings, numerische Datums-/Zeitwerte
  und verbreitete Datumsformate einschließlich 1904-Datumssystem. Unbekannte Formate
  bleiben Rohwerte; typisierte Excel-Datumswerte werden zu ISO normalisiert.
  Formeln, verbundene Zellen, Makros, externe XML-Entities und unsichere Paketpfade
  werden zurückgewiesen. Kein Excel-/Python-Prozess und keine neue Maven-Abhängigkeit.
  XLS, PDF/OCR und komplexe Tabellenlayouts bleiben weitere Adapteraufgaben.
  Formatgrundlage: [Microsoft SpreadsheetML-Zelltypen](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.spreadsheet.cell),
  [Shared-String-Tabelle](https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/working-with-the-shared-string-table).
- Der Mappingplan enthält ausschließlich gewähltes Blatt/Kopfzeile, positionale
  Spaltenindizes, einen erlaubten Datumsparser, Zeitzone (`Europe/Berlin` oder `UTC`)
  und die Entscheidung zur Haltgruppierung. Unzulässige Felder, doppelte Indizes,
  fehlende Pflichtwerte und ungültige Datums-/Zeitwerte erzeugen sichtbare Prüffälle.
- Halte werden optional aus Tour, Tag, normalisierter Adresse und identischen
  Planzeiten gruppiert. Standzeit entsteht einmal aus der Zeitdifferenz. Alle
  Auftragszeilen, ihre Quellzeilen und individuellen Kundenfenster bleiben erhalten.
  Unterschiedliche Fenster am Halt werden nicht überschrieben; ein gemeinsames
  Haltfenster wird dann leer gelassen und ein Prüffall erzeugt. `Arbeitswerte` bleiben
  Rohwerte ohne angenommene Einheit. Reihenfolge wird chronologisch vorgeschlagen.
- Vorgänge und Auftragsbezüge werden nach Quellhash und Datum pseudonymisiert.
  Geplante Rückfahrten, Fahrzeuge und Koordinaten werden nicht erfunden. Fehlender
  Kontext führt zu `needs-context`; fehlerhafte Zeilen/überlappende Halte zu `invalid`.
  Ungültige Entwürfe lassen sich nicht bestätigen. Mappinghash berücksichtigt
  die fachlichen Regeln unabhängig von der JSON-Feldreihenfolge.

### 16.2 Speicher- und API-Vertrag

Speicherort: `data/scenarios/<id>/uploads/company-tours/<source-sha256>.json`.
Die Datei enthält die geparste Originaltabelle sowie Mapping und Entwurf. Sie liegt
außerhalb von `artifacts/` und wird nicht an SUMO/logiTopp übergeben. Unbekannte
Spalten und Kundenwerte bleiben ausschließlich in der internen Tabelle; der
kanonische Entwurf enthält keine unzugeordneten Rohwerte oder automatisch übernommenen
Kundennamen. Adressen sind für die nächste Geokodierungsstufe im Entwurf enthalten.

Identische Datei im selben Szenario wiederholt hochladen liefert denselben Import
einschließlich bestätigten Mappings. Vorschau speichert nichts. Bestätigung schreibt
atomar; ein abgelehnter Bestätigungsversuch verändert die gespeicherte Version nicht.
Importe lassen sich vollständig entfernen. Verschiedene Dateien werden noch als
separate Entwürfe geführt: unternehmensbezogene Revisionen, Ersetzung korrigierter
Planungen und wiederverwendbare Exportprofile über verschiedene Dateien sind offen.
Es existiert deshalb auch noch keine Zusammensetzung aktiver Unternehmenstouren.

| API unter `/api/scenarios/{id}/company-tours` | Wirkung |
|---|---|
| `POST` mit Multipart `file` | Upload, Profil und vorgeschlagenes Mapping |
| `GET` | Gespeicherte Importentwürfe laden |
| `POST /{hash}/preview` mit `{ "mapping": ... }` | Alle Zeilen deterministisch mit dem gewählten Plan prüfen |
| `POST /{hash}/suggest` mit `{ "mapping": ... }` | Expliziten lokalen LLM-Vorschlag erstellen, anschließend dieselbe Prüfung |
| `POST /{hash}/confirm` mit `{ "mapping": ... }` | Gültiges Mapping speichern; bestätigt noch keine Simulationsbereitschaft |
| `GET /{hash}/export` | Bestätigten JSON-Entwurf herunterladen |
| `DELETE /{hash}` | Quelle, Mapping und Entwurf entfernen |

### 16.3 Lokaler LLM-Vorschlag

Die separate Konfiguration `synthesis.company-tour-import.llm-enabled=false`
deaktiviert LLM-Aufrufe im Standard. Die manuelle Zuordnung und Header-Aliasvorschläge
funktionieren immer. Bei expliziter Aktivierung verwendet der Adapter standardmäßig
`http://localhost:11434/v1`, `qwen2.5:7b-instruct`, Temperatur 0 und den Szenario-Seed;
Timeout standardmäßig 60 Sekunden. Die Betriebsproperties stehen in
`TourImportProperties`, mit YAML-Beispiel in `application.yml`.

An das Modell gehen nur positionale Indizes, aggregierte Zelltypen und erkannte
semantische Feldbezeichnungen. Kundenwerte, Originaladressen, Blattnamen und unbekannte
oder datenartige Überschriften werden nicht übertragen. Daher bleibt die automatische
Bedeutungserschließung unbekannter Spalten begrenzt: Diese müssen manuell zugeordnet
werden. Cloud-Endpunkte und HTTP-Redirects werden abgelehnt; Antwortgröße und JSON-
Struktur sind begrenzt. Das Modell kann nur erlaubte Spaltenzuordnungen vorschlagen,
keinen Code und keine Zielwerte. LLM-Ausfälle sind sichtbare Fehler mit manueller
Alternative. Der Vorschlag wird erst durch ausdrückliche Mappingbestätigung gespeichert.

### 16.4 Verifikation und nächster Umsetzungsschritt

**Verifiziert (14.10.2026):** Zehn neue Backendtests prüfen CSV/OOXML, Gruppierung,
einmalige Standzeit, Quellzeilen, verschiedene Auftragsfenster, Mehrtagesidentität,
Mappingdeterminismus, ungültige API-Eingaben, Upload-Idempotenz, Bestätigung/Export/
Löschung sowie den HTTP-Adapter gegen einen lokalen Testserver. Dieser Nachweis ist
kein Lauf gegen ein echtes Sprachmodell. Ein unabhängig mit openpyxl geschriebenes
Excel-Beispiel wurde zusätzlich im Java-Pfad gelesen: zwei Aufträge → ein Halt,
Datum, Uhrzeit und führende PLZ-Null erhalten. Browserprüfung im isolierten Studio
zeigt korrekte Spaltenauswahl, Haltdetails und erfolgreiche Mappingbestätigung.

Die reale Zurbrüggen-Datei wurde ausschließlich im Speicher durch den Mapper geprüft:
222 Eingabezeilen → 222 zugeordnet, 0 abgelehnt, 41 Tourgruppen und 187 Haltkandidaten.
Drei Fensterprüffälle und ein Halt mit unterschiedlichen Auftragsfenstern bleiben
sichtbar. Ergebnis `needs-context`, keine Geokodierung oder Simulationsaktivierung.
Keine Rohkundendaten wurden für diese Prüfung ins Repository oder Testbackend kopiert.

Gesamtgates: `cd backend && mvn -o test` mit 259 Tests, 0 Fehlern, 9 Skips;
`cd frontend && npm test` mit 60 Tests und `npm run build` grün. Der erste Gesamt-
Backendlauf zeigte zwei bestehende native Gütertestfehler: SUMO 1.28 erzeugt im
künstlichen Primary-Netz keine begehbaren Kanten aus der bisherigen Heuristik.
Ein nativer Probeaufbau und anschließend die gesamte Testkette mit explizitem OSM-
Fußweg bestanden. Nur das Testnetz wurde gehärtet, keine SUMO-Produktivlogik geändert.
Unabhängiger Review führte zu Datum in Auftragsidentitäten, prüfbaren Haltdetails,
API-400 bei fehlenden Formatangaben und korrekt initialisierten Auswahlfeldern.

**Noch offen vor zusätzlichem Verkehr:** Unternehmenskontext und Revisionen,
Fahrzeug-/Depotzuordnung, bestätigte Fenster-/Reihenfolgesemantik, freigegebene
Geokodierung, Fahrzeugzugänge/Fahrzeitprüfung, Region-/Tageshorizontbehandlung,
native Ausführung einer ganzen Tour mit einer Fahrzeugidentität und anschließend
das opt-in-Szenarioflag samt Herkunfts-KPIs. Die ersten Umsetzungspakete 1/2 aus
Abschnitt 15 sind damit begonnen und als Importentwurf nutzbar; die vollständige
Tourenintegration ist noch nicht abgeschlossen.

## 17. Regionale Betriebsstandorte aus Overture (2026-10-15, Offline-Pilot)

**Ziel:** zusätzliche, quellenbelegte Standortkandidaten für die spätere räumliche
Aufteilung von Güterverkehr bereitstellen. Ein Betriebsstandort belegt weder
Liefermengen noch LKW-Zugänglichkeit. Es entsteht keine zusätzliche Nachfrage und
keine neue Pipeline-Stufe; die bestehenden Szenario- und Tourenpfade bleiben unverändert.

### 17.1 Quelle, Auswahl und Datenvertrag

Quelle ist das explizit gepinnte Overture-Places-Release `2026-09-23.1`,
`s3://overturemaps-us-west-2/release/2026-09-23.1/theme=places/type=place/`.
Der Pilot verwendet dieselbe Bounding-Box wie das regionale Mautexperiment:
`[7.30, 51.40, 7.85, 51.65]`. Die Box ist nicht die Vereinigung der sechs Gemeinden.
Die Veröffentlichungsfassung ist kein einheitlicher Beobachtungsstichtag aller Betriebe;
die einzelnen Quellzeitstände bleiben in `sources` erhalten.

- `scripts/fetch-overture-freight-sites.py` lädt ausschließlich den regionalen
  Ausschnitt, verlangt eine feste Release-ID und begrenzt die Ausgabe standardmäßig
  auf 100.000 Datensätze. Vorhandene Ausgabeordner werden nicht überschrieben.
  Benötigt wird eine bereits vorhandene `pyarrow`-Installation, keine neue
  Produktivabhängigkeit. Der Abruf erfolgt ausdrücklich außerhalb der Pipeline.
- Exakte Koordinaten stammen aus der WKB-Point-Geometrie. Die nach außen gerundeten
  Quell-Bounding-Boxen dienen nur der groben Abfrage; danach folgt der exakte Punkttest.
  Name, Klassifikation, Betriebsstatus, Konfidenz und Quellbelege werden erhalten;
  Kontaktfelder und Adressen gehören nicht in den wiederholbaren Snapshot.
- `scripts/inspect-freight-sites.py` verarbeitet den lokalen JSON-Snapshot ausschließlich
  mit der Python-Standardbibliothek. Das aktuelle Feld `taxonomy.primary` und das
  ältere `categories.primary` werden unterstützt. Die Kandidatenauswahl verwendet nur
  exakte primäre Kategorien, keine Teilwortsuche und keine ungeprüfte Hierarchieableitung.
- `scripts/freight-site-categories-v1.json` enthält eine explorative Auswahl von
  14 Logistik-, Produktions- und Handelsklassen. Die Liste ist nach Sichtung des
  Kategorieninventars festgelegt worden, kein vorab geschätztes vollständiges
  Güterverkehrs-Branchenmodell. Gastronomie, Bau und viele Spezialbranchen fehlen bewusst
  im ersten Pilotumfang. Sie sind damit nicht als nicht lieferrelevant bewertet.
- Geschlossene Betriebe, fehlende Primärkategorien oder Quellbelege und ungültige
  Geometrien werden mit Grund bilanziert. Unbekannter Betriebsstatus bleibt als
  Prüffall sichtbar. Doppelte Quell-IDs sind ein Fehler; verschiedene IDs desselben
  Betriebs werden noch nicht zusammengeführt. Jeder Kandidat bleibt
  `reviewRequired=true` und `truckAccessVerified=false`.
- Optional werden die vorhandenen sechs Gemeindegeometrien übergeben. Der Prüfer
  verwendet den Polygon-/Lochtest des bestehenden Mautzugangsadapters. Nicht oder
  mehrfach zugeordnete Punkte bleiben ausdrücklich unzugeordnet; ihre Mengen werden
  nicht automatisch auf die Gemeinden verteilt.

Ausgaben: `source/places.json` mit `source/source.json` als Abrufmanifest;
`inventory/sites.geojson` und `inventory/report.json` als quellenbelegtes Inventar
mit Zeilenbilanz, Kategorien, Gemeindezuordnung und Hashes. Kein Fahrtenfaktor,
keine Beladung, kein Fahrzeugbestand und keine Zugangsgewichte werden daraus abgeleitet.
Die Quelldateien liegen lokal unter `.runtime/freight-sites-pilot-2026-10-15/`.

Offizielle Grundlagen:
[Download mit PyArrow/S3](https://docs.overturemaps.org/getting-data/overturemaps-py/),
[Places-Schema einschließlich Point-Geometrie und gerundeter Bbox-Beispiele](https://docs.overturemaps.org/schema/reference/places/place/),
[Lizenz- und Attributionsangaben](https://docs.overturemaps.org/attribution/).
Die konkreten Lizenzbelege bleiben je Datensatz erhalten. Vor einer Weitergabe des
Inventars sind die einschlägigen Quellenhinweise und Lizenztexte mitzuliefern.

### 17.2 Wiederholung

Vom Repository-Root, mit bereits vorhandenem PyArrow und einem frischen Ausgabeordner:

```bash
python3 scripts/fetch-overture-freight-sites.py \
  --release 2026-09-23.1 --bbox 7.30 51.40 7.85 51.65 \
  --output .runtime/freight-sites-pilot-2026-10-15/source
python3 scripts/inspect-freight-sites.py \
  --input .runtime/freight-sites-pilot-2026-10-15/source/places.json \
  --release 2026-09-23.1 --bbox 7.30 51.40 7.85 51.65 \
  --categories scripts/freight-site-categories-v1.json \
  --municipalities .runtime/freight-regional-pilot-2026-10-13/municipalities.geojson \
  --output .runtime/freight-sites-pilot-2026-10-15/inventory
python3 -m unittest discover -s scripts -p 'test_*freight*.py' -v
python3 -m unittest discover -s scripts -p 'test_expand_toll_access_mapping.py' -v
```

Das Abrufmanifest enthält Release, räumlichen Filter, Quelldateien, PyArrow-Version,
Abrufzeitpunkt und Snapshot-/Skripthash. Das Inventar ergänzt Policy-, Geometrie- und
Prüferhashes. Ein erneuter Lauf des Offline-Prüfers auf denselben Eingaben ist unabhängig
von der Abrufzeit; es wird kein Zufall verwendet. Für amtliche Gemeindegeometrien gilt
zusätzlich die vorhandene BKG-Attribution aus Abschnitt 14.

### 17.3 Gemessener Stand und Grenzen

Der vollständige Downloadadapter und anschließend der lokale Prüfer wurden am
15.10.2026 gegen das echte Release ausgeführt. Snapshot-SHA-256:
`47284a9fd36955fada6576764b9f8e42c4886206603055a0b174bffd694a4cdd`.

| Bilanz des regionalen Ausschnitts | Datensätze |
|---|---:|
| Exakte Point-Geometrie innerhalb der Bounding-Box | 71.236 |
| Explizit ausgewählte Standortkandidaten | 3.274 |
| Andere Primärkategorie | 66.165 |
| Keine Primärkategorie | 1.614 |
| Ausgewählte Kategorie, aber geschlossen | 183 |
| Kandidaten eindeutig in einer der sechs Pilotgemeinden | 2.174 |
| Kandidaten keiner der sechs Gemeindegeometrien zugeordnet | 1.100 |

Die 3.274 Kandidaten enthalten 2.028 mit Betriebsstatus `open` und 1.246 mit
unbekanntem Betriebsstatus. Gemeindezuordnung: Dortmund 1.500, Holzwickede 57,
Kamen 107, Lünen 197, Schwerte 118 und Unna 195. Das sind Quellstandorte ohne
betriebsbezogenen Dublettenabgleich, keine Zahl aktiver unterschiedlicher Lieferbetriebe.
1.100 nicht zugeordnete Kandidaten bleiben im Bounding-Box-Inventar markiert und
werden nicht als Standorte der sechs Pilotgemeinden gezählt.

**Verifiziert:** 9 Standortprüftests einschließlich aktueller/alter Klassifikation,
beider WKB-Bytefolgen, exakter Punktkoordinaten statt gerundeter Bbox, vollständiger
Zeilenbilanz, Dubletten-IDs, geschlossener/unbelegter Standorte und Polygonlöcher/
mehrdeutiger Gemeindezuordnung. Zusammen mit den betroffenen vorhandenen Güterverkehrs-
und Zugangsprüfungen: 34 Python-Tests grün. Es wurde kein neuer SUMO-Lauf durchgeführt.
Zwei Läufe des Offline-Prüfers mit identischen Eingaben liefern byte-identische
Berichte und GeoJSON-Dateien; Eingabe-, Kandidaten- und Gemeindezuordnungsbilanz
wurden zusätzlich gegen die ausgegebenen Dateien geprüft.

### 17.4 Offener Quellenvergleich und nächster Umsetzungsschritt

Der geplante aktuelle OSM-Vergleich wurde versucht, aber nicht abgeschlossen:
`overpass-api.de` antwortete auf POST und GET mit HTTP 406, der vorgesehene
`overpass.kumi.systems`-Mirror mit HTTP 500. Die Abrufversuche stehen lokal in
`osm-attempts.json`. Bei diesem ersten Abruf entstand kein aktueller OSM-Standortvergleich.
Der nachfolgende PBF-Pilot in Abschnitt 18 schließt den Quellenvergleich an;
die Abdeckungsverbesserung bleibt fachlich ungeprüft. Das vorhandene Hauptstraßen-OSM
des Routingpiloten ist wegen seiner Straßenselektion kein geeigneter POI-Vergleich.

Nächster fachlicher Schritt ist der Abgleich gegen einen geeigneten regionalen
OSM-Snapshot/PBF: Kategorien angleichen, räumliche und namensbasierte mögliche
Dubletten als Prüffälle ausgeben und eine Stichprobe der Zuordnung prüfen.
Danach lokale LKW-Zugänge mit dem vollständigen Straßennetz testen. Das bisherige
Hauptstraßennetz reicht für Lager-/Betriebszufahrten nicht aus. Erst nach diesen
Prüfungen dürfen explizite Standortgewichte gegen die unveränderten OD-Mengen
erprobt werden; Standortanzahl und `confidence` sind keine beobachteten Liefermengen.

## 18. Lokaler OSM-Abgleich und LKW-Kantenprüfung (2026-10-15, Offline-Pilot)

### 18.1 Quelle und gemeinsame räumliche Abdeckung

Quelle ist der öffentliche [Geofabrik-Abzug Arnsberg vom 13.10.2026](https://download.geofabrik.de/europe/germany/nordrhein-westfalen/arnsberg-regbez-261013.osm.pbf):
194.838.605 Bytes, SHA-256
`9283044d53e5ed532741de22427d9399ab62d0bab957ebbe5bbe70b9d216bc8b`.
Die veröffentlichte MD5 `542b897b99d7ace1e2ac3a643c104c33` wurde ebenfalls bestätigt.
Lizenz: ODbL 1.0; Attribution OpenStreetMap contributors und Geofabrik GmbH.
[Anbieterbeschreibung](https://download.geofabrik.de/europe/germany/nordrhein-westfalen/arnsberg-regbez.html).

`scripts/prepare-freight-osm.py` liest die Datei mit der optionalen lokalen Laufzeit
PyOsmium 4.3.1 / Shapely 2.0.7. Keine neue Backend-/Pipeline-Abhängigkeit.
Punkte, geschlossene Wege und Multipolygonrelationen bleiben getrennte Quellobjekte.
Flächen erhalten einen innenliegenden Repräsentationspunkt, keine erfundene Zufahrt.
Der explizite Tagkatalog umfasst Lebensmittel-/Bau-/Möbelhandel, Großhandel, Lager,
Logistik und Produktion. Er ist nicht vollständig oder exakt gleichbedeutend mit
der Overture-Taxonomie. `building=warehouse` kann ein Gebäude ohne belegten aktiven
Betrieb sein; nicht gefundene Tags bedeuten keine beobachtete Nullnachfrage.

Im Kartenausschnitt werden 1.106 OSM-Standortobjekte gefunden. Beide Inventare werden
für den Vergleich zusätzlich auf dieselben sechs Gemeindegeometrien eingeschränkt.
Geofabrik Arnsberg deckt nicht jede Nachbarregion der Bounding-Box ab. Verglichen wird
der Schnitt des Kartenausschnitts mit den sechs Gemeinden, nicht deren vollständige Fläche.

| Gemeinsamer Ausschnitt | Overture-Kandidaten | OSM-Quellobjekte |
|---|---:|---:|
| Dortmund | 1.500 | 534 |
| Holzwickede | 57 | 13 |
| Kamen | 107 | 37 |
| Lünen | 197 | 78 |
| Schwerte | 118 | 40 |
| Unna | 195 | 62 |
| Gesamt | **2.174** | **764** |

### 18.2 Prüfpaare statt automatischer Zusammenführung

`scripts/compare-freight-sites.py` normalisiert Namen mit Unicode NFKC,
Kleinschreibung und alphanumerischen Zeichen. Die räumliche Suche ist auf regionale
Ausschnitte begrenzt. Gemessen wurden:

- **138 nahe Namensübereinstimmungen** zwischen Quellen, Abstand höchstens 75 m.
  Kategorienkonflikte bleiben sichtbar.
- **150 reine Nähefälle**, höchstens 25 m und gleiche grobe Kategoriegruppe, aber
  kein gleicher normalisierter Name. Dies sind keine Namensdubletten.
- 285 Overture- und 265 OSM-Objekte haben mindestens einen Prüfkandidaten.
- Innerhalb der gemeinsamen Auswahl: zwei mögliche Overture-Dublettenpaare,
  kein gleichnamiges OSM-Paar innerhalb von 50 m.

Quellobjekte werden weder gelöscht noch zu einem Betrieb zusammengezogen. Verschiedene
Namen und unbenannte Objekte können denselben Standort bezeichnen; Filialen können
denselben Namen tragen. Elf lokale Beispiele zeigen Namens-, Kategorien- und
Geometriebelege, darunter einen Kategorienkonflikt trotz beinahe gleicher Position.
Die Betriebsidentitäten wurden nicht unabhängig bestätigt. Unterschiedliche
Quellzeitstände, Kategorien und Gebäude-/Betriebsbegriffe verbieten, die rohe
Bestandsdifferenz als nachgewiesene Abdeckungsverbesserung zu interpretieren.

### 18.3 Straßennetz und technische Zugangskandidaten

Für Straßen wird die Bounding-Box um 0,02 Grad gepuffert. Vollständige OSM-Wege aller
Highway-Klassen mit überlappender Weg-Bounding-Box, alle referenzierten Knoten und
vollständig enthaltene Restriktionsrelationen bleiben erhalten: 215.163 Straßenwege,
852.945 Knoten und 1.798 Restriktionsrelationen. Der Export schneidet keine Wege
geometrisch ab. SUMO 1.28.0 importiert das Netz erfolgreich.

**Importgrenze:** Native Warnungen nennen unter anderem nicht bestimmbare Richtungen
oder Start-/Zielkanten von Restriktionsrelationen. Damit ist keine vollständige
Übertragung aller OSM-Regeln belegt. Eine anfängliche `proj.db`-Warnung wurde durch
einen unabhängigen PyProj-Abgleich geprüft: 20 ursprüngliche OSM-Knoten gegen native
Junction-Koordinaten, maximale Abweichung unter 0,012 m. Der wiederholte Routinglauf
verwendet den passenden lokalen PROJ-Datenpfad.

`scripts/inspect-freight-access.py` prüft bis zu drei Kanten je Standort und Klasse
innerhalb von 50 m. Native Klassenberechtigungen, HGV-Verbote, Maß-/Zeitregeln,
zweckabhängige Zugänge, Schranken und komplexe Restriktionsrelationen bleiben getrennt.
Höhenversetzte Straßen werden nicht automatisch als lokale Zugangskandidaten verwendet.
Für **1.791 von 2.174** Overture-Kandidaten gibt es einen nahen Kandidaten für beide
Schwerlastklassen. Dies belegt weder einen verbundenen Betriebseingang noch die richtige
Fahrbahnseite oder die Befahrbarkeit durch ein bestimmtes beladenes Fahrzeug.

Die native Probe verwendet 20 gleichmäßig verteilte Positionen der nach Quell-ID
sortierten Liste, keine räumlich oder mengenmäßig repräsentative Stichprobe.
14 davon haben Kandidaten: zwei Klassen und Hin-/Rückrichtung ergeben **56 technische
Proben** zu/von einer deterministisch gewählten Primärstraßen-Referenzkante.
Alle 56 werden zunächst nativ berechnet, vier passieren aber explizite OSM-HGV-Verbote.
Dies bestätigt die Notwendigkeit der zusätzlichen Berechtigungsübertragung im Produktivpfad.

`scripts/apply-freight-osm-permissions.py` überträgt für den Offline-Piloten dieselbe
unterstützte Mechanik wie `OsmFreightPermissions`: unbedingtes `hgv=no`, gerichtete
Überschreibungen, Erhalt anderer Fahrzeugklassen. Dadurch werden 616 gerichtete Kanten
für `truck` und `trailer` gesperrt. Maß-, Zeit- und Ausnahmeregeln bleiben ungeklärt.
Originalnetz und erste Probe bleiben als Negativbeleg erhalten.

**Wiederholungsbefund:** Nach Übertragung der Verbote berechnet duarouter erneut
56 von 56 Routen; vier Routen ändern ihren Verlauf. Die Zahl der Proben mit explizitem
HGV-Verbot auf dem Pfad sinkt von vier auf **null**. Alle 56 bleiben wegen anderer
Quellregeln als ungeklärt markiert, unter anderem Maß-/Zeitregeln und Höhenlagen;
einige zusätzlich wegen zweckabhängiger Zugänge, Schranken oder Restriktionsrelationen.
Es ist keine vollständige Befahrbarkeitsfreigabe und kein neuer SUMO-Verkehrslauf.

**Verifiziert:** 54 betroffene Python-Tests grün, einschließlich lokaler OSM-Punkt-/
Flächen-/Relationsverarbeitung mit echtem netconvert, beidseitigem Gemeindefilter,
Namens-/Näheabgrenzung, XML-Streaming, Quellregeln und gerichteter HGV-Übertragung.
Die native Routingprobe verwendet das echte Regionalnetz. Der erneute lokale
Extraktionslauf liefert identische Standort- und Straßendateien.

**Nächster Schritt:** Betriebsidentitäten und tatsächliche Zufahrten anhand der
Prüffälle bestätigen; importierte Restriktionsrelationen nachbearbeiten; Fahrzeugmaße,
Gewicht/Beladung, Fahrtzweck und Datum/Uhrzeit als expliziten Prüfkontext anbinden.
Erst anschließend dürfen Zugangsgewichte in einem kontrollierten OD-Vergleich
erprobt werden. Die vorliegenden Standortzahlen begründen keine Fahrtenfaktoren.

### 18.4 Werkzeuge und Wiederholung

Alle Quellen und Belege liegen unter `.runtime/freight-osm-pilot-2026-10-15/`:
`pbf-source.json`, `experiment.json`, `extract-final/`, `comparison/`,
`network.net.xml`, `netconvert.log`, `projection-check.json`, `access/`,
`network-hgv.net.xml`, `hgv-permissions.json` und `access-hgv/`.

Vom Repository-Root, mit lokalem PBF und bereits vorhandener optionaler Laufzeit;
die Extraktion benötigt einen frischen Ausgabeordner:

```bash
PYTHONPATH=.runtime/freight-osm-pilot-2026-10-15/python-tools \
  python3 scripts/prepare-freight-osm.py \
  --input .runtime/freight-osm-pilot-2026-10-15/arnsberg-regbez-261013.osm.pbf \
  --bbox 7.30 51.40 7.85 51.65 \
  --output .runtime/freight-osm-pilot-2026-10-15/extract-final
python3 scripts/compare-freight-sites.py \
  --left .runtime/freight-sites-pilot-2026-10-15/inventory/sites.geojson \
  --right .runtime/freight-osm-pilot-2026-10-15/extract-final/sites.geojson \
  --municipalities .runtime/freight-regional-pilot-2026-10-13/municipalities.geojson \
  --output .runtime/freight-osm-pilot-2026-10-15/comparison
```

Die Kantenprüfung erhält `--sites`, `--network`, `--osm`, `--import-log` und
`--output`; sie schreibt `probe-trips.xml`. `duarouter --seed 42 --routing-threads 1`
berechnet die technischen Fahrten. Der zweite Prüferaufruf mit denselben Eingaben und
`--routes` kontrolliert Typ/Endkanten und ungelöste Quellregeln entlang der ganzen Route.
Proben werden nicht als Nachfrage an SUMO oder die Szenariopipeline übergeben.

## 19. Fahrzeugprofile und numerische Restriktionen (2026-10-15, Offline-Pilot)

### 19.1 Explizite Betriebsprofile aus dem vorhandenen Katalog

`scripts/freight-vehicle-profiles-v1.json` referenziert die vorhandenen Typen
`standard.heavyTruck.diesel@1` und `standard.articulatedTruck.diesel@1` aus
`backend/src/main/resources/vehicle-types.json`. Maße werden aus diesen Versionen
aufgelöst; Katalog-, Policy-, Quell- und Prüferhash gehören in jeden Laufbericht.
Die Profile sind generische Annahmen, keine gemessenen Fahrzeuge oder regionale Flotten.

| Merkmal | Solo-LKW (`truck`) | Sattelzug (`trailer`) |
|---|---:|---:|
| Länge | 10 m | 16,5 m |
| Breite | 2,55 m | 2,55 m |
| Höhe | 4 m | 4 m |
| Leermasse | 10.000 kg | 15.000 kg |
| Zulässige Gesamtmasse | 26.000 kg | 40.000 kg |
| Tatsächliche Masse im angenommenen Volllastfall | 26.000 kg | 40.000 kg |
| Einzelne Achslasten | unbekannt | unbekannt |

Die tatsächliche Masse ist ein ausdrücklich gewählter Lastfall und wird von der
zulässigen Gesamtmasse getrennt geführt. Es gibt keine dynamische Beladung. Achslasten
werden nicht durch gleichmäßige Teilung einer Gesamtmasse erfunden. Optional angegebene
Achslasten müssen positiv sein und zur tatsächlichen Gesamtmasse summieren.

### 19.2 Prüfumfang und bewusst ungelöste Regeln

`scripts/freight_restrictions.py` prüft `maxheight`, `maxwidth`, `maxlength`,
`maxweight`, `maxweightrating` und `maxaxleload` mit expliziten Einheiten und
gerichteten beziehungsweise HGV-spezifischen Angaben. `maxweight` vergleicht die
tatsächliche Masse, `maxweightrating` die zulässige Gesamtmasse. Signatur-Metadaten
wie `maxheight:signed=no` werden nicht zu numerischen Grenzen umgedeutet.

Unterstützt werden Meter/Zentimeter, Tonnen/Kilogramm und die dokumentierte Fuß-/Zoll-
Schreibweise für Längen. Unbekannte Einheiten, Listen, nicht numerische Werte wie
`default`/`none`, fehlende Achslasten und unklare Spur-/Schlüsselzusätze bleiben ungeklärt.
Ein Fahrzeug genau an einer numerischen Grenze überschreitet sie nicht; daraus wird
keine zusätzliche Sicherheitsreserve oder vermessene Durchfahrtshöhe abgeleitet.

Einfache Wochen-/Uhrzeitfenster, auch über Mitternacht, können anhand eines expliziten
Berliner Zeitkontexts geprüft werden. Die native Wiederholung liefert geschätzte
Kantenaustrittszeiten über `duarouter --exit-times true`. Eine Bedingung muss während
des ganzen geschätzten Kantenintervalls konstant auswertbar sein. Wechselnde Bedingungen,
Feiertage, Wetter, Datumsbereiche und komplexe Mehrfachregeln bleiben ungeklärt.
Der Beispielkontext `2026-07-15T08:00:00+02:00` ist eine Annahme, kein beobachteter Fahrtbeginn.
Die Prüfung belegt keine historische Übereinstimmung des Oktober-Netzes mit dem Juli.

Aktive numerische Ausnahmen können eine Basisgrenze ersetzen; ohne auswertbaren Kontext
wird die Basis nicht trotz einer möglichen Ausnahme als sicher verletzte Grenze behandelt.
Liefer-/Anliegerberechtigungen, Schranken und Restriktionsrelationen erhalten keine
pauschale Freigabe. Bedingte Geschwindigkeiten werden gesondert als Betriebsprüffälle
geführt; sie sind nicht automatisch Einfahrtsverbote. Geschätzte Kantenaustrittszeiten
sind damit kein Nachweis regelkonformer Geschwindigkeiten oder realer Ankunftszeiten.

Grundlagen: [OSM maxweight](https://wiki.openstreetmap.org/wiki/Key:maxweight),
[maxweightrating](https://wiki.openstreetmap.org/wiki/Key:maxweightrating),
[maxheight](https://wiki.openstreetmap.org/wiki/Key:maxheight),
[maxaxleload](https://wiki.openstreetmap.org/wiki/Key:maxaxleload) und
[Conditional restrictions](https://wiki.openstreetmap.org/wiki/Conditional_restrictions).
Das ist eine Prüfung ausdrücklich unterstützter OSM-Datenregeln, keine vollständige
verkehrsrechtliche oder physische Zufahrtsfreigabe.

### 19.3 Pilotablauf und erste Messung

`scripts/audit-freight-profiles.py` besitzt die Modi `prepare` und `audit`.
`prepare` erzeugt ein separates Profilnetz: Nur explizit nachgewiesene Verstöße
schließen die jeweilige Fahrzeugklasse. Unbekannte Regeln werden nicht als gelöst
behandelt. Die 56 ursprünglichen Proben erhalten die konkreten vType-Maße, Masse und
Höchstgeschwindigkeit. Start-/Zielkanten und IDs bleiben erhalten; nicht mehr berechenbare
Fahrten bleiben in der Bilanz. Produktivpipeline, Fahrzeugkatalog und bestehende Läufe
werden nicht verändert.

**Baseline:** Von den bisherigen 56 Klassenrouten verletzen unter den angenommenen
Volllastprofilen **20** mindestens eine explizite numerische Breiten-, Höhen- oder
Gewichtsgrenze. 36 bleiben ohne solchen numerischen Befund, aber mit anderen Prüfgründen.
Beispiele: 2 m Breite bei 2,55-m-Fahrzeug, 3,8 m Höhe bei 4-m-Fahrzeug, 7,5 t tatsächliche
Masse sowie eine 30-t-Grenze, die den 26-t-Solo-LKW zulässt und den 40-t-Sattelzug ausschließt.
Paar-/Regelzählungen dürfen nicht zusätzlich zu den 20 betroffenen Routen summiert werden.

**Native Wiederholung mit Profilnetz:**

| Bilanz bei unveränderten 56 Proben und Endkanten | Vorher | Nachher |
|---|---:|---:|
| Nativ berechenbare Routen | 56 | 54 |
| Routen mit explizitem numerischem Profilverstoß | 20 | 0 |
| Nicht berechenbare Routen | 0 | 2 |

18 der vorher verletzenden Routen werden über andere Pfade berechnet, zwei Rückfahrten
(`probe_13_truck_out`, `probe_13_trailer_out`) sind auf den unveränderten Endkanten nicht
mehr berechenbar. Daraus folgt nicht, dass es an diesem Standort überhaupt keine
andere Zufahrt gibt. Insgesamt ändern 34 der 54 berechneten Routen ihren Pfad;
neben Sperren enthalten die Profile jetzt explizite Fahrzeugmaße und Geschwindigkeit.
Es handelt sich deshalb nicht um eine isolierte Elastizität einer einzelnen Größe.

Alle 54 berechneten Routen bleiben prüfpflichtig. Vor allem `maxheight=default`,
Anlieger-/Lieferrechte, bedingte Geschwindigkeiten und nicht vollständig nachgewiesene
Abbiegerelationen sind weiter ungeklärt. Der Befund ist **null explizite numerische
Verstöße im unterstützten Prüfumfang**, keine Freigabe aller Verkehrsregeln.
Die nativen vType-Ausgaben wurden unabhängig gegen Länge, Breite, Höhe und tatsächliche
Masse der aufgelösten Profile geprüft. Geschätzte Kantenaustrittszeiten sind für alle
54 Routen vorhanden und den jeweiligen Kanten zugeordnet.

**Verifiziert:** 70 betroffene Python-Tests grün, darunter 16 neue Profil-/Regelprüfungen.
Ein unabhängiges kleines natives Vergleichsnetz mit zwei Pfaden belegt die 30-t-
Unterscheidung: Solo-LKW über die kurze Strecke, Sattelzug über den zulässigen Umweg.
Weitere Fälle sichern Einheiten, Grenzgleichheit, tatsächliche gegenüber zulässiger
Masse, fehlende Achslasten, Richtungen, aktive Ausnahmen, wechselnde/fehlende Zeitkontexte,
Geschwindigkeitsregeln und nicht mehr berechenbare Fahrten ab. Es wurde kein neuer
SUMO-Verkehrslauf und keine automatische Szenarioaktivierung durchgeführt.

### 19.4 Artefakte und Wiederholung

Lokaler Ordner: `.runtime/freight-profile-pilot-2026-10-15/`. `experiment.json`
enthält die vor dem Lauf notierte Hypothese und Vorhersage: alle 56 Proben bilanziert,
mindestens vier bislang verdeckte numerische Verstöße, keine pauschale Zugangsfreigabe.
`baseline-final/` enthält die Auswertung der alten Klassenrouten;
`prepared-final/` Profilnetz, vTypes, Sperrbelege und native Routingausgaben;
`after-final/` die komplette Nachher-Bilanz aller 56 Proben. `comparison.json` fasst
die gemessenen Änderungen zusammen. Unbekannte Achslasten und weitere offene Regeln
sind ausdrücklich im Profil beziehungsweise Bericht sichtbar.

```bash
python3 scripts/audit-freight-profiles.py prepare \
  --osm .runtime/freight-osm-pilot-2026-10-15/extract-final/roads.osm \
  --profiles scripts/freight-vehicle-profiles-v1.json \
  --catalog backend/src/main/resources/vehicle-types.json \
  --network .runtime/freight-osm-pilot-2026-10-15/network-hgv.net.xml \
  --trips .runtime/freight-osm-pilot-2026-10-15/access/probe-trips.xml \
  --output .runtime/freight-profile-pilot-2026-10-15/prepared-final
```

Das Profilnetz benötigt einen frischen Ausgabepfad. Anschließend `duarouter` mit
`profile-trips.xml`, `--exit-times true`, `--ignore-errors true`, `--seed 42` und
`--routing-threads 1` ausführen. `audit` erhält denselben Katalog, dieselbe Policy,
`--osm`, zusätzlich `--routes`, `--probes`, `--departure` und einen eigenen `--output`.
Fehlende native Routen werden ausdrücklich als `no-native-route` ausgewiesen.

## 20. Rückfahrten, Abbiegerelationen und optionale Profilprüfung (2026-10-15)

### 20.1 Modellierungsentscheidung und Diagnose der zwei Rückfahrten

**Vorgabe:** Fahrzeuge starten und enden an der nächstgelegenen für die Fahrzeugklasse
zulässigen Straße (bestehende Suchgrenze 300 m). Separate Betriebseinfahrten, Torlagen
und Wege innerhalb eines Betriebsgeländes werden nicht modelliert. Gerichtete Kanten
in einer OD-Eingabe bleiben verbindlich; ein fehlgeschlagenes Routing verändert sie
nicht stillschweigend.

Die beiden bisherigen Ausfälle `probe_13_truck_out` und `probe_13_trailer_out` betreffen
denselben Overture-Standort `acf19e31-c0e2-4f61-abad-9fb5490f42ec` (Quellname:
Pew Gmbh Beleuchtungstechnik). Die gerichtete Startkante `922189281` auf der
Goethestraße besitzt im Profilnetz genau einen Nachfolger: `922189280` mit
`maxheight=3.8`. Beide angenommenen Fahrzeugprofile sind 4 m hoch; eine unmittelbare
Wendeverbindung fehlt. **Die festgelegte Richtung führt deshalb in eine Sackgasse
für diese Profile.** Das ist keine Aussage über die tatsächliche Betriebszufahrt.

Die gesondert gekennzeichnete Gegenprobe mit Start `-922189281` nutzt die Gegenrichtung
auf derselben Straße. `duarouter --seed 42 --routing-threads 1` berechnet beide Rückfahrten.
Die ursprünglichen zwei Ausfälle bleiben in der 56er-Bilanz erhalten; Gegenproben sind
zusätzliche Diagnosefälle. Ergebnis: `.runtime/freight-followup-2026-10-15/return-diagnosis.json`,
zugehörige Trips, Routen und Log im selben Verzeichnis.

### 20.2 Abbiege- und Zugangsregeln: Untersuchung statt pauschaler Freigabe

`scripts/check-freight-turns.py` vergleicht einfache, unbedingte OSM-Abbiegerelationen mit
den tatsächlich vorhandenen SUMO-Spurverbindungen und deren Klassenberechtigungen.
Berücksichtigt werden `from`-Way, `to`-Way und ein `via`-Knoten, LKW-Ausnahmen und
mehrere `only_*`-Relationen als **Vereinigung der zulässigen Ziele**. Unvollständige
Quellen, fehlende positive Verbindungsreferenzen, bedingte Regeln, Via-Ways und nicht
zuordenbare Knoten bleiben ungeklärt.

Ergebnis am vorhandenen Regionalnetz: **1.798 Quellrelationen × 2 Fahrzeugklassen =
3.596 Prüfungen**:

| Ergebnis pro Relation und Klasse | Anzahl |
|---|---:|
| Einfache Übertragung im Netz bestätigt | 2.943 |
| Ungeklärt | 639 |
| Klasse explizit ausgenommen | 2 |
| Nur andere Verkehrsart betroffen | 10 |
| Abweichende native Verbindung | 2 |

Die zwei Abweichungen betreffen dieselbe Relation `3756042`: `only_straight_on`,
Ausnahme `psv`. Trotzdem existiert die Verbindung `282940402#1` → `-282940402#1`
für `truck` und `trailer`. **Keine der 54 berechneten Pilotrouten nutzt diese
abweichende Verbindung.** Der Befund widerlegt eine pauschale Freigabe des Netzes;
er ist keine Aussage, dass alle 639 offenen Prüfungen fehlerhafte Relationen wären.
Ergebnis und quellbezogene SHA-256: `.runtime/freight-followup-2026-10-15/turn-transfer.json`.

Die Anwendung behandelt `delivery`, `destination`, private Zugänge und Genehmigungen
weiter als prüfpflichtig. Aus einem Standortnamen, einer eingehenden Fahrt oder der
Kategorie Logistik wird keine Lieferberechtigung abgeleitet. `hgv:conditional` kann
ein pauschales `hgv=no` aufheben; die Klassenübertragung sperrt solche ungeklärten
Ausnahmen daher nicht mehr bedingungslos. Ein ausdrücklich nur zusätzlich verbietendes
`no @ (...)` hebt ein bereits bestehendes Verbot dagegen nicht auf.
`hgv_articulated` hat bei Sattelzügen Vorrang vor `hgv`.

**Zusätzlicher Beschaffungsbefund:** Der bisherige Overpass-Straßennetzquery enthielt
keine Abbiegerelationen. Er lädt jetzt auch Elternrelationen vom Typ `restriction*`
samt Mitgliedern und Straßenknoten. Lokale OSM-Snapshots müssen diese selbst enthalten;
ihre Vollständigkeit wird weiterhin nicht behauptet. Der Query-Vertrag ist getestet,
eine Live-Overpass-Abfrage war für die lokale Untersuchung nicht erforderlich.

Primärreferenzen zur Interpretation:
[SUMO: OSM-Import und Spurverbindungen](https://sumo.dlr.de/docs/Networks/Import/OpenStreetMap.html#lane-to-lane_connections),
[OSM-Abbiegerelationen](https://wiki.openstreetmap.org/wiki/Relation:restriction),
[OSM-Zugangsregeln](https://wiki.openstreetmap.org/wiki/Key:access).

### 20.3 Kleiner Referenzsatz

`scripts/freight-reference-cases-v1.json` hält sechs Quellstandorte fest:
NODU Fliesendirektvertrieb, Hana Baumaterialien, Möllmann Technik Und Service,
Pew Gmbh Beleuchtungstechnik, Blees Leo Möbelhaus sowie Edelstahl- Und Metallhalbzeug-
Vertrieb Zweigniederlassung. Dies sind Quellbezeichnungen, keine verifizierten
Betriebsdaten. Die **24 ursprünglichen Richtungs-/Klassenproben** enthalten positive
Kontrollen für 2 m Breite, 3,8 m Höhe sowie 3,5/7,5/30 t Masse.

`scripts/verify-freight-reference.py` prüft Standortidentität, unveränderte gerichtete
Endpunkte, das ursprüngliche Auftreten der jeweiligen Beschränkung und die erwartete
Folgebilanz einschließlich der zwei Ausfälle. Er bilanziert weiterhin alle 56 Proben.
Ergebnis: `.runtime/freight-followup-2026-10-15/reference-verification.json`.
Dieser Satz sichert die Daten-/Routingregression; er ersetzt keine Beobachtung realer
Lieferfahrten und keine Erhebung von Anlieferfrequenzen.

### 20.4 Optional in der Szenario-Pipeline

**✅ UMGESETZT:** `freight.profileChecksEnabled=false` ist ein zusätzliches Szenarioflag,
im Studio als „LKW-Profile und Routen prüfen“ auswählbar. Voraussetzungen sind aktiver
schwerer Güterverkehr und SUMO. Die bestehende SUMO-Stufe übernimmt die Prüfung;
keine zusätzliche Pipeline-Stufe, kein Python-Prozess im nativen Kernpfad.

`FreightProfileChecks` referenziert die Standardkatalogtypen in Version `1` und setzt
explizit angenommene Volllastprofile (26/40 t, 4 m Höhe, 2,55 m Breite). Diese Maße und
Massen stehen auch in den tatsächlich exportierten SUMO-vTypes. Eigene Zuordnungen
schwerer SUMO-Fahrzeugtypen sind mit dieser ersten Standardprofilprüfung nicht
kombinierbar; die Eingabevalidierung meldet den Konflikt, statt falsche Maße anzunehmen.
Andere Fahrzeugrollen bleiben kombinierbar.

Vor dem Routing werden eindeutig verletzte statische Maß-/Masse- und Zugangsregeln
für die jeweilige LKW-Klasse gesperrt. Tatsächliche und zulässige Masse sind getrennt;
Achslasten bleiben unbekannt. Unterstützte Einheiten dieser Java-Stufe sind m/cm und
t/kg; andere Angaben werden ausgewiesen. Die ausführlichere zeitbezogene Offline-
Auswertung aus Abschnitt 19 bleibt eigenständig: **In der Pipeline werden bedingte
Regeln als ungeklärt ausgewiesen**, da dort kein gesicherter Kalender-/Ausnahmekontext
vorliegt. Physische Zusatzlimits und nicht eindeutig zuordenbare Knoten werden
ebenfalls sichtbar gehalten, nicht als erfüllt angenommen.

Nach `duarouter` werden alle angeforderten Güterfahrten bilanziert und ihre Quellregeln
sowie einfache Abbiegerelationen geprüft. Mehrere `only_*`-Ziele werden gemeinsam
betrachtet. Nicht routbare Fahrten oder nachgewiesene Verstöße erzeugen einen Fehler
in der vorhandenen SUMO-Stufe, nachdem der Bericht geschrieben wurde. Ungeklärte
Regeln bleiben im Bericht sichtbar; `accessCertified=false` und
`sourceRuleCoverageVerified=false` gelten auch bei technisch berechenbaren Routen.
Das Routing verwendet den Szenarioseed und einen Routingthread.

Neue Artefakte:

- `freight/profile-permissions.json`: aufgelöste Profile, Lastannahme, Profil-/Quellhash
  und klassenbezogene Sperrübertragung.
- `freight/route-checks.json`: angefordert, pro Fahrt berechenbar/ungeklärt/Verstoß/
  nicht routbar, konkrete Quellregeln und Quell-/Profil-/Routenhashes.

Das Studio bietet den vorhandenen Prüfbericht zum Download an. Deaktivierung oder
übersprungene SUMO-Ausführung entfernt alte Prüfartefakte. Der bestehende Standard-
Nachfragepfad und die Erzeugung von Standortnachfrage werden dadurch nicht aktiviert.

### 20.5 Wiederholung und technische Abnahme

```bash
python3 scripts/check-freight-turns.py \
  --osm .runtime/freight-osm-pilot-2026-10-15/extract-final/roads.osm \
  --network .runtime/freight-profile-pilot-2026-10-15/prepared-final/network-profile.net.xml \
  --routes .runtime/freight-profile-pilot-2026-10-15/prepared-final/profile-routes.rou.xml \
  --output .runtime/freight-followup-2026-10-15/turn-transfer.json
python3 scripts/verify-freight-reference.py \
  --cases scripts/freight-reference-cases-v1.json \
  --baseline .runtime/freight-profile-pilot-2026-10-15/baseline-final/route-audit.json \
  --after .runtime/freight-profile-pilot-2026-10-15/after-final/route-audit.json \
  --output .runtime/freight-followup-2026-10-15/reference-verification.json
```

`SumoFreightIntegrationTest` führt den Produktivpfad mit synthetischem OSM-Referenznetz
nativ aus: 26-t-LKW auf einer 30-t-Kante, 40-t-Sattelzug auf dem Umweg, unveränderte
Personennachfrage, sichtbare Lieferausnahme, Bericht für nicht routbare feste Endpunkte
und Bereinigung nach Deaktivierung. `FreightProfileChecksTest` sichert zusätzlich
Massentrennung, Einheiten, Prioritäten, ungeklärte Bedingungen, vereinigte `only_*`-Ziele,
Flagvoraussetzungen und wiederholbare Berichte. Der vollständige Regionalnetzlauf aus
Abschnitt 19 ist eine separate Offlineprobe, keine vollständige regionale
Nachfragesimulation mit aktivierter neuer Option.

**Abnahme:** Vollständiger Backend-Lauf mit 269 Testfällen ohne Fehler; anschließend
gezielte Nachprüfung der abschließenden Änderungen einschließlich eines weiteren
Regressionstests (aktueller Reportbestand: 270 Fälle, 0 Fehler, 4 explizite Skips).
Die vier Skips betreffen zwei quellenabhängige MobileCity-Proben sowie die ausdrücklich
aktivierbaren actiTopp-/GTFS-Integrationsläufe; alle fünf nativen SUMO-Güterverkehrstests
laufen ohne Skip. Frontend: 60 Tests und Produktionsbuild erfolgreich.
Betroffene Python-Werkzeuge: 78 Tests erfolgreich. `git diff --check` ohne Befund.
Protokolle, Experimentannahmen und Codehashes:
`.runtime/freight-followup-2026-10-15/experiment.json` und benachbarte Logdateien.

## 21. Regionaler Gesamttest mit Profilprüfung (2026-10-16, negativer Abnahmebefund)

### 21.1 Eingefrorene Eingaben und Modellumfang

Der Test nutzt die unveränderte produktive `GenerationService`-Kette in einem
isolierten Datenverzeichnis, ohne bestehende Nutzerszenarien zu verändern. Grundlage:
Dortmund/Unna, Bounding Box `[7.30, 51.40, 7.85, 51.65]`, Seed 42,
54 global ausgerichtete 5-km-Zonen, Ziele `logitopp` und `sumo`,
`freight.enabled=true`, `freight.profileChecksEnabled=true`, kein SUMO-Trip-Cap.

Die regionale Maut-Teilnachfrage aus Abschnitt 14 bleibt mengenmäßig unverändert:
**125 positive Beziehungen → 2.336 Tagesfahrten**, davon 701 Solo-LKW und 1.635
Sattelzüge. Mengenbasis ist die veröffentlichte, gerundete Monats-OD Juli 2026;
Kalenderdurchschnitt `/31`, Klassenanteile 30/70 und Abfahrtsfenster 06–20 Uhr sind
weiterhin offengelegte Modellannahmen. Dies ist keine neue vollständige regionale
OD-Erhebung und kein beobachteter einzelner Montag.

Die bisherigen gerichteten Kanten-IDs gehören zum früheren Hauptstraßennetz. Sie
werden im Experiment archiviert und in der abgeleiteten CSV freigelassen; Koordinaten,
Mengen, Zeitfenster und Klassen bleiben erhalten. Die neue Zuordnung zur nächsten
zulässigen Straße ist für den neu erzeugten vollständigen Netzstand vorgesehen.
Der Speicherabbruch tritt **vor dieser Zuordnung** ein; eine erfolgreiche neue
Kantenprüfung wird deshalb nicht behauptet. Separate Betriebseinfahrten werden
entsprechend der Modellvorgabe weiterhin nicht modelliert.

Der vorhandene datierte Geofabrik-PBF liefert denselben vollständigen lokalen
Straßenabzug wie Abschnitt 18 (172 MiB OSM-XML mit 215.163 Ways) sowie einen neuen,
regional begrenzten Snapshot von **37.588 OSM-POI-/Siedlungsknoten**. Der POI-Snapshot
übernimmt die Knoten-Semantik des produktiven Overpass-Adapters. PBF-/Straßen-/OD-/
Snapshot-Hashes und Attribution stehen in
`.runtime/freight-e2e-2026-10-16/inputs.json`; ein Live-Abruf ist nicht beteiligt.

Für den technischen Gesamttest ist eine **künstliche Zielpopulation von 1.000 Personen**
mit `samplingFraction=1` ausdrücklich festgelegt. Vollständige Haushalte ergeben
1.001 Personen. Verhaltensquelle: Template; kein KIT-Bevölkerungssample, keine
zusätzlichen externen Personenzielzonen. Die Paketlogistik bleibt aktiv. GTFS/ÖV,
MobileCity, Kalibrierung und Netz-Feedback sind für diesen Straßen-/Güterverkehrstest
abgewählt. Die Gesamtkette umfasst alle angeforderten Pflichtstufen, keine volle
reale Regionalbevölkerung und keine vollständige multimodale Belastung.

### 21.2 Erreichte Stufen und Ressourcenfehler

Vor dem Start festgelegte Kriterien stehen in `experiment.json`: logiTopp und SUMO
müssen als Stufen erfolgreich enden, die 2.336 Güterfahrten dürfen nicht verloren
gehen, offene Quellregeln müssen sichtbar bleiben. Die Befehlsaufzeichnung fixiert
**6 GiB Java-Heap** für die SyntheSis-Pipeline und **3 GiB** für den getrennten
logiTopp-Adapter. Diese Grenzen wurden nicht nachträglich zum Erfolg hochgesetzt.

| Teilkette | Beobachteter Befund |
|---|---|
| OSM-Snapshot, Zonierung, Attraktivitäten | erfolgreich, 54 Zonen |
| Matrizen | 14 Visum-Matrizen mit 54 × 54 Einträgen; GraphHopper erfolgreich initialisiert |
| Bevölkerung und Aktivitäten | 1.001 Personen, 515 Haushalte, 515 Pkw, 28.310 Template-Aktivitäten |
| Native logiTopp-Wochensimulation | erfolgreich; 21.303 Personenwege von 1.000 Personen mit Wegen |
| Paketlogistik | 764 Wochenaufträge, 634 laut logiTopp zugestellt, 456 Touren |
| Güterverkehrsexport | 2.336 Fahrten, Klassen-/Mengenbilanz unverändert |
| Native Netzkonvertierung | erfolgreich; `net.net.xml` **1.280.846.990 Bytes** im ersten Lauf |
| Java-Netznachbearbeitung | `OutOfMemoryError: Java heap space` bei 6 GiB |
| Profilübertragung, neue Kantenabbildung, duarouter und native SUMO-Simulation | nicht erreicht |

Die Matrixquelle heißt ausdrücklich `graphhopper-with-local-fallbacks`: Ein globaler
Routingausfall mit vollständigem Luftlinien-Fallback trat nicht ein. Die bestehenden
intrazonalen und lokalen Schätzregeln bleiben enthalten; es wird keine ausnahmslos
netzgeroutete Matrix behauptet. Die Differenz zwischen 1.001 erzeugten Personen und
1.000 Personen mit simulierten Wegen ist keine nachgewiesene verlorene Güterfahrt.

**Reproduzierte Ursache:** `OsmFreightPermissions.apply` liest das große SUMO-Netz als
vollständigen DOM-Baum. Die Materialisierung über `TrafficRoutes.elements` /
`DeepNodeListImpl.getLength` überschreitet den begrenzten Heap. Eine isolierte
Wiederholung dieses produktiven Schritts auf einer Dateikopie erzeugt denselben
Speicherfehler; Stacktrace unter `replay-error.txt`. Der Fehler liegt vor der neuen
optionalen Profilprüfung. Ein bloßes Abschalten dieser Prüfung beseitigt diesen
konkret nachgewiesenen Abbruchpfad nicht.

**Abnahme nicht bestanden.** Für die 2.336 schweren Fahrzeuge gibt es in diesem
Gesamttest keine belastbaren Werte für native Routen, Abschlüsse, Teleports oder
Einhaltung der Quellrestriktionen. Diese Größen bleiben im Ergebnisbericht `null`,
nicht null verlorene oder null problematische Fahrten. Die kleineren nativen
Nachweise aus den vorherigen Abschnitten bleiben eigenständige Belege.

### 21.3 Gefundener Statusfehler und begrenzte Korrektur

Der erste Ressourcenfehler wurde von `ExecutorService.submit` in einem unbeobachteten
Future aufgefangen. Die bisherigen `catch (Exception)`-Grenzen behandeln einen
`OutOfMemoryError` nicht. Daher waren Worker und Zeitmessung beendet, Job und SUMO-
Stufe standen aber weiterhin auf `RUNNING`. Ein Thread-Dump belegt den wieder
wartenden Executor-Worker. Der isolierte erste Testprozess wurde nach Sicherung des
Befunds beendet; der Ausgangszustand wird nicht nachträglich als FAILED umgeschrieben.

`GenerationService` veröffentlicht jetzt an Stufen- und Jobgrenze auch für `Error`
einen terminalen Fehlerstatus samt Typ/Meldung und wirft den Fehler anschließend
weiter. Der Executor-Pool bleibt unverändert seriell; die Änderung setzt weder
Speichergrenzen hoch noch meldet sie eine erfolgreiche Erholung vom Ressourcenfehler.
`GenerationServiceFailureTest` injiziert einen `OutOfMemoryError`, ohne real den
Testprozess auszuhungern: Stufe/Job enden als FAILED, Meldung und Endzeit existieren,
ein weiterer Job derselben Instanz kann wieder starten.

Bei einer Wiederholung war zusätzlich die laufende Logkopie des Experimenttreibers
vom gesättigten gemeinsamen Heap betroffen. Dieser Instrumentierungsfehler und dessen
Exit-Code bleiben separat erhalten; es wird kein nicht aufgezeichneter terminaler
Jobzustand erfunden. Der abschließende Prüftreiber wartet deshalb ohne periodische
Log-/JSON-Kopien auf das Ende des Workers; native Prozesslogs bleiben auf Platte.

### 21.4 Konkreter nächster Schritt

Die regionalen SUMO-XML-Verbraucher müssen große Dateien **stromweise** lesen und
schreiben und nur benötigte Kanten-/Spur-/Verbindungsdaten kompakt behalten. Der
nachgewiesene erste Engpass liegt bei `OsmFreightPermissions`; vor einer erneuten
Erfolgsabnahme sind auch `FreightProfileChecks`, `TrafficRoutes` und die späteren
Geometrie-/Netzlast-Exporte auf vergleichbare vollständige DOM-Materialisierung zu
prüfen. Die tatsächliche nächste Speichergrenze nach dem ersten Fix ist noch nicht
gemessen. Ein einzelner Streaming-Fix ist deshalb noch kein Nachweis für die ganze
Kette.

Danach denselben eingefrorenen Fall erneut ausführen und erst bei tatsächlich
berechneten und eingesetzten Fahrzeugen die vollständige Quellen-/Routing-/
Abschlussbilanz abnehmen. Eine Kalibrierung an Zählstellen oder zusätzliche
Standortnachfrage wird aus dem gescheiterten Gesamttest nicht abgeleitet.

Artefakte: `.runtime/freight-e2e-2026-10-16/` (Eingaben, Annahmen, erster Lauf,
Thread-Dump, direkte Fehlerreproduktion und Auswertung), die Instrumentierungsprobe
unter dem Namenszusatz `-recheck`, die abschließende Statusprobe unter
`-status-final`. Die Treiberquellen und Argumentlisten liegen jeweils neben den
Daten; `report.py` erzeugt den zusammengeführten `regional-report.json`.

### 21.5 Abschluss der Fehlerwiederholung und Codeabnahme

Die abschließende unveränderte Szenarioprobe endet nach der Statuskorrektur als
`FAILED`, ebenso die Stufe `SUMO (Verkehr)` (67.817 ms); Meldung:
`java.lang.OutOfMemoryError: Java heap space`. logiTopp endet als `DONE`.
Die Güterverkehrs-CSV ist zwischen erstem und letztem Lauf bytegleich, ebenso die
Klassenbilanz 701/1.635. Der eingefrorene Straßensnapshot ist nach allen Proben
unverändert. Die Heapaufzeichnung zeigt die Ausschöpfung von 6.144 MiB und die
Freigabe nach dem DOM-Fehler; es wurde kein alternativer erfolgreicher Routenlauf
als Ersatz in das Ergebnis eingesetzt.

**Codeabnahme:** Vollständige Backend-Testsuite erfolgreich: 271 Fälle, 0 Fehler,
4 explizite Skips für die vorhandenen quellen-/laufzeitabhängigen Integrationsproben.
Der neue Status-Regressionstest läuft ohne Skip. `git diff --check` ohne Befund.
Frontendcode wurde in diesem Schritt nicht geändert.

**Ergebnis:** Der regionale Gesamttest ist durchgeführt und negativ bewertet;
eine erfolgreiche vollständige regionale SUMO-Ausführung bleibt offen. Die
Statuskorrektur ist übernommen, der Speicherengpass bleibt ein eigener konkreter
Umsetzungsschritt. Maschinenlesbarer zusammengeführter Befund:
`.runtime/freight-e2e-2026-10-16/regional-report.json`.

## 22. Streaming der regionalen SUMO-Netze (2026-10-16)

### 22.1 Umsetzung und unveränderte Modellregeln

Die in Abschnitt 21 nachgewiesene DOM-Materialisierung ist durch den nativen Java-
Helfer `XmlRecords` ersetzt. Er liest XML mit StAX und hält für die Bearbeitung nur
jeweils einen ausgewählten Datensatz als kleinen DOM-Baum. Unbenötigte Junctions und
Connections werden weitergereicht; das vollständige Netzdokument entsteht nicht im
Java-Heap. Netzänderungen werden in eine temporäre Nachbardatei geschrieben und erst
nach vollständigem Lesen und erfolgreichem Schließen atomar ersetzt. Fehler löschen
die temporäre Datei und erhalten das ursprüngliche Netz. DTDs und externe Entitäten
bleiben gesperrt; Namespaces, XML-Escapes, Kommentare und Zwischenraum bleiben gültig.

Umgestellt sind OSM-HGV-Sperrübertragung, optionale statische Profilprüfung,
SEVAS-Netzänderungen sowie Kanten-/Spurleser und Routenleser in `TrafficRoutes`.
Profil-Abbiegerelationen speichern kompakte Member-IDs, die Netzkanten nur ihre
Zielknoten-ID; keine dieser Tabellen hält mehr das vollständige OSM-/SUMO-DOM am
Leben. Netzwerk- und Routen-GeoJSON werden Feature für Feature geschrieben. Auch
Tripinfo-/Audit-Leser verarbeiten einzelne Datensätze. Der gemeinsame Straßenadapter
berechnet den SUMO-Netzhash stückweise statt über `Files.readString`.

Die Netzlastauswertung in `SumoRunner` liest ihr XML bereits zeilenweise; sie hält
weiterhin die benötigten Messwerte und Ausgabegeometrien. Das ist kein Beleg für
konstanten Speicherbedarf der gesamten Pipeline: Kantenindex, Geometrien, Quellregeln
und Audit-Ergebnisse wachsen weiterhin mit Netz und Nachfrage. Beseitigt wird die
vollständige zusätzliche XML-Baumkopie der großen Netze.

Sperrregeln, Profile, Quellen-Semantik, 300-m-Suchradius, gerichtete Kantenwahl,
Abfahrtszeiten, Seeds und Artefaktverträge bleiben unverändert. Fahrzeuge werden wie
vorgegeben an der nächsten zulässigen Straße eingesetzt; separate Einfahrten werden
nicht eingeführt. Es gibt keine neue Pipeline-Stufe, Laufzeitabhängigkeit oder
Heap-Erhöhung. Die früheren Negativproben aus Abschnitt 21 bleiben erhalten.

### 22.2 Regression und begrenzter Speicherbeweis

`XmlRecordsTest` prüft Berechtigungsänderungen samt Namespaces/Escapes/Kommentaren,
DOCTYPE-Abweisung sowie den Erhalt des Originals und die Dateibereinigung nach
ungültigem XML oder Callback-Fehler. Ein separater JVM-Prozess mit **64 MiB Heap**
schreibt ein Netz mit **150.000 Kanten**, liest es erneut und prüft jede geänderte
Spurberechtigung. Diese Probe belegt den Streaming-Helfer; die größere regionale
Kette wird davon getrennt geprüft.

Die regionale Wiederholung liegt unter
`.runtime/freight-e2e-2026-10-16-streaming/`. Eingaben und Abnahmekriterien aus
Abschnitt 21 werden übernommen: Seed 42, 6 GiB Pipeline-Heap, 3 GiB Adapter-Heap,
1.000 künstliche Zielpersonen und unveränderte 2.336 schwere Fahrten.
`input-verification.json` prüft die Quellhashes, die kopierten Eingaben sowie die
Bytegleichheit von `freight/trips.csv` zur abschließenden Statusprobe.
`implementation.json` hält die Quellcode-Hashes des Streaming-Laufs fest.

### 22.3 Ergebnis der unveränderten Regionalwiederholung

**Streaming-Umbau bestätigt, regionale Gesamtabnahme weiter negativ.** Die zuvor
abbrechende HGV-Sperrübertragung und die anschließende Profilprüfung beenden sich
innerhalb desselben 6-GiB-Budgets. Ausgewiesen werden 1.275 HGV-beschränkte Kanten und
13.795 Kanten mit mindestens einer profilbedingten Einschränkung (27.433
Kante-/Klasse-Paare). Netzgeometrie, klassengefilterte Straßenbindung aller 2.336
Güterfahrten und Streaming-Netzexport werden erreicht. Das nachbearbeitete Netz hat
1.280.704.482 Bytes. Die größte in einer GC-Zeile ausgewiesene Belegung vor einer
Sammlung beträgt 2.781 MiB; dies ist ausdrücklich keine exakte Heap-Peakmessung.

Der nächste harte Fehler liegt im nativen `duarouter`: Exit 1 bei Fußgänger `p54`,
`No destination edge for walk or personTrip`. Die SUMO-Stufe endet nach 209.878 ms
als FAILED, die eigentliche SUMO-Simulation und die nachgeschaltete vollständige
Routenprüfung werden nicht erreicht. Der Job meldet nach bestehender Behandlung
optionaler Stufen DONE; deshalb prüft die Abnahme ausdrücklich den Stufenstatus.
Die 302 bis zum Abbruch geschriebenen Güterrouten sind nur **partielle Ausgabe**,
keine vollständige Routingbilanz. Abschluss- und Teleportwerte bleiben unbekannt.

Die Koordinate von `p54` ist für Start und Ziel identisch: `[7.321408, 51.611130]`.
Die nächste überhaupt vorhandene exportierte Kantenmittellinie liegt etwa
**3.076 m** entfernt, gegenüber 300 m Mapmatch-Radius. Dies ist eine nachgewiesene
Abdeckungslücke im verwendeten Straßensnapshot, kein Beleg für den früheren
Rail-/Fußgänger-Snappingfehler. Messmethode und nächste Kanten stehen in
`endpoint-diagnostic.json`; Spurversätze sind in dieser Entfernungsdiagnose nicht
enthalten und erklären den Größenunterschied nicht.

Eine getrennte **Güterverkehrsdiagnose** verwendet dasselbe Netz und unveränderte
Güter-Trips, lässt Personen und Paketverkehr allein zur Fehlerisolierung aus und
ersetzt den Regionaltest nicht. `duarouter` endet dort mit Exit 0 bei aktivem
`--ignore-errors`: **2.197 von 2.336 Fahrten routen, 139 fehlen** (43 Solo-LKW,
96 Sattelzüge, 13 Beziehungen). Alle fehlenden Fahrten haben dieselbe Zielkante
`1188978185`. Weder eine vollständige Quellregelprüfung noch eine SUMO-Ausführung
wird daraus abgeleitet. Rohbefund unter `freight-only-diagnostic/`.

**Codeabnahme:** vollständige Backend-Suite mit 275 Fällen, 0 Fehlern und vier
bestehenden expliziten Skips (zwei MobileCity-Quellenproben, actiTopp- und GTFS-
Prozesstest). Die neue Speicher-/XML-Regression und die fünf nativen
Güterverkehrs-Integrationstests laufen ohne Skip. Frontend unverändert.

Die Quellen-/Mengenhashes sind geprüft, die Güter-Trips bleiben bytegleich zum
Vorläufer. `regional-report.json` trennt Streaming-Nachweis, abgebrochene
Gesamtkette und isolierte Güterroutingprobe. Nächste Prüfung: vollständige räumliche
Quellabdeckung und Erreichbarkeit der auffälligen Zielkante; weder Suchradius noch
Nachfrage oder Sperren werden für einen scheinbaren Erfolg gelockert.

### 22.4 Eingrenzung des abgeschnittenen Güterziels

Die Zielkante `1188978185` ist ein nordwärts gerichteter A45-Abschnitt. Die
Rückwärtssuche im exportierten Verbindungsgraphen erreicht nur zwölf Vorgängerkanten;
keine vorgelagerte Kante wird durch die Truck-Berechtigung ausgeschlossen. Die
Zufahrtskette beginnt im vorhandenen Netz als `dead_end` am Knoten `4743335198`,
Koordinate `[7.524546, 51.378988]`, unmittelbar am südlichen Zuschnittrand von etwa
51,38° N. Dies ist eine Diagnose auf Kantenebene mit zugelassenen Truck-Spuren,
keine vollständige Spurwechselprüfung. Artefakte: `incoming-trace.json` und
`unreachable-edge-diagnostic.json`. Der Befund rechtfertigt einen größeren
Straßenpuffer, keine Lockerung der Profile oder willkürliche Ersatzzielkante.

## 23. Vergleichsprobe mit vollständigerer Netzabdeckung (2026-10-16)

Diese Probe ist ausdrücklich **ein neuer Netzfall**, keine Umdeutung der negativen
Abnahme aus Abschnitt 22. Der datierte
[Geofabrik-NRW-Snapshot](https://download.geofabrik.de/europe/germany/nordrhein-westfalen.html)
`nordrhein-westfalen-261013.osm.pbf` (914.912.483 Bytes) ersetzt die administrative
Teilquelle Arnsberg. Die veröffentlichte MD5-Prüfsumme ist verifiziert; SHA-256:
`3970456ab065820cc695312c5e1f1a21a37709bad4c87607abfb3f378bacb6bd`.
Quelle, Abrufdatum, Lizenz ODbL und Attribution liegen unter
`.runtime/freight-network-coverage-2026-10-16/source.json`.

Der vorhandene Offline-Extraktor bleibt unverändert. Er erhält den erweiterten
Extraktionsrahmen `[7.17, 51.32, 7.98, 51.73]`; inklusive seines bestehenden Puffers
entsteht der Straßenrahmen `[7.15, 51.30, 8.00, 51.75]`. Das entspricht ungefähr
10–11 km Rand um die eigentliche Modellregion `[7.30, 51.40, 7.85, 51.65]`.
Modellregion, Zonenparameter, POI-Snapshot, Populationseinstellungen, Seed 42,
2.336 Güterfahrten und Heapgrenzen bleiben gleich. Der neue Netzstand kann Matrizen
und daraus resultierende Personenwege verändern; Bytegleichheit der realisierten
Personennachfrage wird daher nicht vorausgesetzt. Der Güterverkehr wird nicht
skaliert, verschoben oder auf Ersatzkanten festgelegt.

Der neue isolierte `GenerationService`-Lauf wird unter
`.runtime/freight-e2e-2026-10-16-coverage/` aufgezeichnet. Die ursprünglichen
Abnahmekriterien bleiben bestehen: vollständige Pflichtstufen, keine verlorenen
Güterfahrten, keine expliziten Routenverstöße, keine Güter-Teleports und getrennte
Ausweisung unbekannter Quellregeln. Quellenabdeckung ist damit gezielt verändert;
eine getrennte Kausalschätzung allein für Quelle gegenüber Puffer ist nicht Zweck
dieser Reparaturprobe.

Der neue Straßenabzug umfasst **1.781.002 Knoten, 423.263 Ways und 4.160
Restriktionsrelationen** (369.972.828 Bytes OSM-XML), SHA-256:
`0a08658bb86eb0aa2f209189f60c639d98745d0b4d3d9fff407f53782b2dc163`.
`old-failing-endpoint-source-check.json` findet zur alten Abbruchkoordinate einen
beibehaltenen Straßenknoten in etwa 103 m Entfernung. Das ist eine obere Schranke
für den geometrischen Kantenabstand, keine Prüfung der Fußgängerberechtigung.
Die Dateien für Personen, Haushalte und Güter-Trips sind gegenüber Abschnitt 22
bytegleich. Die neu berechnete logiTopp-Nachfrage enthält wieder 21.303 Wochenwege
von 1.000 Personen mit Wegen; ihre Modi und Distanzen reagieren auf das neue Netz.
Die realisierte Personennachfrage wird deshalb separat bilanziert.

### 23.1 Native Ausführung und getrennte Abnahme

**Der regionale schwere Güterpfad besteht die vorab festgelegte Abnahme.** Job und
SUMO-Stufe enden als DONE. Alle **2.336 Güterfahrten** (701 Solo-LKW, 1.635
Sattelzüge) sind nativ geroutet, eingesetzt und laut Tripinfo beendet: keine
fehlende Güterroute, kein nicht gestartetes oder am Tagesende offenes schweres
Fahrzeug, kein Güter-Teleport und kein vaporisiertes Fahrzeug. Die statische
Quellprüfung enthält keine expliziten Verstöße. Alle 2.336 Routen tragen dennoch
`review-required`; bedingte Regeln, Ausnahmen, unbekannte Achslasten und nicht
vollständig belegte Quellabdeckung bleiben offen. Dies ist keine Zugangsbescheinigung
und keine kalibrierte oder vollständige reale Regionalnachfrage.

**Kontrollen:** Alle 125 gerichteten Start-/Zielkantenbindungen sind gegenüber
Abschnitt 22 unverändert. Die zuvor fehlenden 139 Fahrten werden damit nicht durch
veränderte Ziele, kleinere Mengen, gelockerte Profile oder höhere Suchradien zum
Erfolg gebracht. Für den früher abbrechenden identischen Fußgänger-Start/Zielpunkt
existiert im neuen Lauf unter ID `p50` eine native Route. Personen-IDs sind zwischen
den veränderten Modusauswahlen nicht als fachliche Cross-run-Schlüssel zu verwenden.

Das nachbearbeitete Netz umfasst **2.620.481.645 Bytes**. HGV-Sperrübertragung,
Profilprüfung, Routing, Audit, native Tages-SUMO-Ausführung sowie Netzlast- und
Routen-GeoJSON sind abgeschlossen, weiterhin mit **6 GiB Java-Heap**. Die größte
vor einer GC ausgewiesene Belegung beträgt **4.114 MiB**; kein exakter Peakwert.
2.946 Kanten tragen HGV-Sperren und 27.787 mindestens eine Profileinschränkung.
Der Netzlast-Layer enthält 36.180 befahrene Kanten. Die aufgezeichneten
Stufenzeiten umfassen mehrere externe Werkzeuge und sind keine Performanzabnahme.

**Die gesamte angeforderte Verkehrsnachfrage ist weiterhin nicht verlustfrei
abgebildet.** Der Güternachweis wird deshalb ausdrücklich von dieser offenen
Gesamtabnahme getrennt:

| Quelle / Einheit | angefordert | nativ geroutet | laut Tripinfo beendet | Routingverlust |
|---|---:|---:|---:|---:|
| Personenfahrzeuge: Pkw und Rad | 1.125 | 908 | 908 | 217 |
| Paketverkehr: einzelne Fahrzeug-Legs | 163 | 72 | 72 | 91 |
| Schwere Güterfahrzeuge | 2.336 | 2.336 | 2.336 | 0 |
| Fußgänger-Personenwege | 215 | 207 | 206 | 8 |

Ein Fußweg (`p214`, Abfahrt 85.740 s) ist um 86.400 s noch nicht angekommen; dieser
Tagesrandfall ist vom Routingverlust getrennt. Ein Fahrrad aus dem Personenverkehr
(`v166`) teleportiert wegen zu langer Wartezeit. Der native Gesamtzähler enthält
einen Teleport; die vollständige ID-Zuordnung belegt null schwere Güter-Teleports.
Die Routenverluste der übrigen Quellen werden durch `--ignore-errors` weiter
bilanziert, aber nicht zum erfolgreichen vollständigen Personen-/Paketnachweis erklärt.

**Zählereinheiten:** Nativ sind 3.316 Fahrzeuge einschließlich 61 Fahrrädern
inserted und beendet. SUMOs `vehicleTripStatistics.count=3255` zählt hier die
motorisierten Fahrzeuge, `bikeTripStatistics.count=61` die Fahrräder separat.
Der bisherige `SumoRunner.Result.vehicles` übernimmt den ersten Wert; er darf in
dieser Auswertung nicht als Gesamtzahl einschließlich Fahrrad interpretiert werden.
Die Tabelle verwendet die einzelnen Routen-/Tripinfo-IDs und löst diese Differenz
explizit auf. 207 Personenwege wurden geladen, davon 206 beendet und einer am
Tagesende offen; ein bloßer Statistik-`number`-Wert belegt keinen Abschluss.

Die Montag-Nachfrage enthält außerdem 997 Mitfahrer- und 1.024 ÖV-Wege, die dieser
bewusst ohne GTFS gefahrene Straßenmodellfall nicht abbildet. Sie werden nicht mit
den Routingverlusten der angeforderten 1.340 Pkw-/Rad-/Fußwege verrechnet. Die
Paketbilanz von logiTopp (764 Wochenaufträge, 657 dort zugestellt, 474 Touren) ist
ein anderer Zeit-/Modellhorizont als die 163 angeforderten SUMO-Montags-Legs.

**Reproduktions- und Prüfdateien:**
`.runtime/freight-e2e-2026-10-16-coverage/regional-report.json` trennt
`freightAcceptancePassed=true` von `fullTrafficDemandRetained=false`.
`report.py` erzeugt die Rohbilanz, `verify-result.py` prüft Quellen-/Codehashes,
Abschluss-IDs, Teleports, Zählereinheiten und die Vergleichseingaben.
`final-verification.json` enthält 14 bestandene Unverändertheitsprüfungen.
Die Produktivquellen sind seit der grünen Backend-Suite (275 Fälle, 0 Fehler,
vier bestehende Skips) unverändert; Codehashes des nativen Laufs sind geprüft.

### 23.2 Konkrete verbleibende Arbeit

Als nächster eigener Block sind die 217 Personenfahrzeuge, 91 Paket-Legs und acht
Fußrouten nach Ursache aufzuteilen: gerichtete Verbindung, Klassenberechtigung,
Koordinatenabbildung und tatsächliche Quellerreichbarkeit. Dazu die IDs im Bericht
und die nativen Routingwarnungen paarweise prüfen; keine Fahrzeuge still verwerfen,
keine Personennachfrage begrenzen und keine Sperren für einen Erfolg deaktivieren.
Der offene Fußweg am Tagesrand und der einzelne Fahrrad-Teleport bleiben getrennte
Befunde. Zusätzlich ist der produktive Gesamtfahrzeugzähler hinsichtlich Fahrrad-
Statistik abzusichern. Ungeklärte Quellrestriktionen bleiben ein weiterer fachlicher
Block. Das separate Betriebseinfahrtsmodell bleibt ausdrücklich abgewählt.

## 24. Routingverluste, Vorprüfung und vollständige Ausführungsbilanz (2026-10-17)

### 24.1 Diagnose am eingefrorenen Regionalfall

Die 316 fehlenden Anfragen aus Abschnitt 23 werden ID-genau mit den nativen
Warnungen verknüpft. 304 Fahrzeugverluste melden fehlende gerichtete Verbindungen
(194 Pkw, 19 Fahrräder, 91 Paket-Legs), vier Pkw-Verluste eine fehlende
Koordinatenzuordnung. Acht Fußwege melden keine Route. Zusätzlich sind bei 84
**erhaltenen** Routen Koordinatenwarnungen aufgezeichnet. Eine vorhandene ID allein
belegt in diesen Fällen keine vollständig erhaltene Start-/Zielbeziehung.

Artefakte: `.runtime/traffic-routing-2026-10-16/baseline-diagnosis.json` und
`matched-edges.json`; die Eingaben aus Abschnitt 23 bleiben unverändert.

**Reproduzierte Ursache:** `--no-turnarounds` unterdrückt auch Wendemanöver an
Sackgassen. Eine kleine native Probe routet ein Fahrzeug deshalb nicht aus einer
Sackgasse heraus. `--no-turnarounds.except-deadend` stellt diese Verbindung her;
derselbe Fall durchläuft danach auch SUMO. Eine getrennte OSM-Probe mit explizitem
`restriction=no_u_turn` bleibt dagegen nicht routbar. Beide Regeln sind zusätzlich
im nativen `SumoRoadConnectivityTest` abgesichert.

Die regionale **Ein-Faktor-Probe** ändert nur diese Netzbauoption. OSM-Quelle,
Profile, ursprüngliche Geo-Trips, Abfahrten, Klassen, Seed 42 und Mengen bleiben
identisch. Von 3.839 angeforderten Fahrzeug-/Personen-Anfragen routen nun 3.821;
die Zahl fehlender Anfragen sinkt **316 → 18**. Alle zuvor fehlenden Paket-Legs und
Fahrradfahrten werden wiedergefunden. Zehn Pkw- und acht Fußanfragen fehlen weiter.
Dies ist ein Routennachweis, keine neue Ausführungs-/Teleport-Abnahme.
`routing-comparison.json` hält die ursprünglichen und neuen IDs getrennt.

### 24.2 Produktive Korrekturen und Berichte

Der Netzbau erlaubt Wendemanöver ausschließlich an Sackgassen; explizite OSM-
Verbote werden dadurch nicht aufgehoben. Die klassenbezogene Straßenzuordnung gilt
jetzt auch für Pkw, Lieferwagen, Fahrrad und Fußgänger. Ein räumlicher Kantenindex
beschleunigt die Suche; die stabile Kantenreihenfolge bei gleichen Entfernungen
bleibt erhalten. Maßgeblich sind die tatsächlichen SUMO-Spurberechtigungen.
Der bisherige 300-m-Radius bleibt bestehen, ebenso die Vorgabe, an der nächsten
zulässigen Straße zu starten. Separate Einfahrten werden nicht eingeführt.
Gemeinsame explizite GraphHopper-Routen behalten ihren eigenen geprüften Pfad.

Die Richtung wird anhand der tatsächlichen SUMO-Verbindungen einschließlich
Berechtigungen von Ausgangs- und Zielspur geprüft. Ist die nächste Richtung
nachweislich ungeeignet, darf ausschließlich die zulässige Gegenrichtung desselben
OSM-Straßensegments mit vertauschten Endknoten gewählt werden. Explizite Kanten
bleiben fest. Ein kompakter Rückwärtsgraph mit begrenztem Cache prüft notwendige
Erreichbarkeit. Er überschätzt mögliche Verbindungen, weil interne Via-Spuren und
Spurwechsel weiter nativ geprüft werden: Ein fehlender Pfad ist ein Fehlerbeweis,
ein vorhandener Pfad noch keine Routenzertifizierung. Fußwege prüft weiterhin der
native Router. Ein eigener Test verhindert, dass reine Fußgänger-Spurverbindungen
als Pkw-Verbindungen gelten.

Neue additive Artefakte innerhalb der bestehenden SUMO-Stufe:

| Datei | Inhalt und Aussagegrenze |
|---|---|
| `sumo/network-coverage.json` | Prüfung jedes Endpunkts, Originalkoordinate, tatsächliche Klasse, zugeordnete Kante/Entfernung oder konkreter Fehler; OSM-/SUMO-Netzhash, Modellregion und beobachteter Netzumfang. Hauptstraßen-Sackgassen nahe dem Netzumfang werden als mögliche Randabbrüche markiert. Diese Hinweise sind **kein** vollständiger Topologie-/Quellabdeckungsbeweis; ein unbekannter Quell-Extraktionspuffer wird nicht erfunden. |
| `sumo/traffic-routing.json` | Jede angeforderte ID, Route vorhanden/fehlend, zugehörige native Warnungen und nach Quelle/Klasse gruppierte Gründe. Auch Koordinatenwarnungen bei erhaltenen IDs bleiben sichtbar. Bei abgebrochenem Router bleiben vollständige Routen-/Verlustzähler unbekannt (`null`), statt eine partielle Ausgabe als vollständige Bilanz auszugeben. |
| `sumo/traffic-execution.json` | Angefordert, geroutet, gestartet, beendet, nicht geroutet, nicht gestartet, am Tagesende offen und vaporisiert; Fahrzeuge und Personen getrennt, zusätzliche Gruppierung nach Nachfragequelle und Klasse. Teleport-IDs werden mit der nativen Gesamtsumme abgeglichen. Bei unvollständigen Warnungslogs bleiben einzelne Teleportzahlen ausdrücklich unbekannt. |

Im vorhandenen vollständigen Güterszenario-Modus (`freight.enabled`) führen
unzuordenbare Endpunkte oder Routingverluste **aller** angeforderten Quellen zu
FAILED mit Berichtspfad. Die HGV-Profilprüfung bleibt erhalten. Wird das native Routing erreicht,
werden dessen HGV-Fehlerartefakte vor der allgemeinen Routing-Schranke geschrieben.
Scheitert bereits die Netzvorprüfung, existiert noch kein nativer Routingbericht. In den bisherigen begrenzten Läufen wird die Unvollständigkeit
berichtet; sie ist kein vollständiger Nachfrage-Abnahmenachweis. Nicht beendete
Wege am festen Tagesrand werden vom Verlust vor dem Start getrennt. Teleports
werden nicht durch Abschalten der Protokollierung oder Verwerfen von IDs kaschiert.

Vor jedem neuen SUMO-Lauf werden eigene alte Anfrage-, Ergebnis-, Statistik- und
Logdateien entfernt. So entstehen beim Abschalten von Güterverkehr/Fußwegen keine
Phantomanfragen aus dem vorherigen Lauf; alte Teleportwarnungen oder Summaries
können keine neue Auswertung verfälschen. Die archivierten Vergleichsläufe werden
hierdurch nicht verändert.

### 24.3 Zähler und Oberfläche

`SumoRunner.Result.vehicles` übernimmt die native Zahl tatsächlich eingesetzter
Fahrzeuge einschließlich Fahrrädern. Bei älteren Statistikdateien ohne diesen
Zähler werden Motor- und Fahrrad-Tripstatistik addiert; auch reine Fahrrad-
Statistiken werden korrekt gelesen. Die Durchschnittswerte bleiben motorisiert
und werden entsprechend gekennzeichnet. Die bisherige Differenz 3.255 gegenüber
3.316 im Abschnitt-23-Fall ist damit erklärt und im Parser abgesichert.

`sumo/summary.json` enthält die additive Ausführungsbilanz. Das Studio zeigt
angefordert/geroutet/gestartet/beendet sowie fehlende Routen, Tagesrandfälle,
nicht gestartete Anfragen und Teleports getrennt. Ältere Summaries werden nicht
nachträglich als neuer Zählstandard ausgegeben. Fehler einer optionalen SUMO-
Stufe sind auch bei Jobstatus DONE sichtbar ausgeschrieben; sie stehen nicht nur
im Hover-Text. Alle Detailberichte stehen in der bestehenden Artefaktliste bereit.

### 24.4 Neu sichtbar gewordene Endpunktprobleme

Die klassenbezogene Vorprüfung der Nicht-HGV-Anfragen meldet **145 Endpunkte in
134 Anfragen**: 119 Pkw-, zehn Paket-, zwei Fahrrad- und drei Fußanfragen.
Diese Anzahl ist nicht mit der Zahl fehlender nativer Routen gleichzusetzen;
ein Teil hatte zuvor trotz Warnung eine Ausgabe erhalten. Die 145 Vorkommen
entsprechen 51 eindeutigen Paaren aus Koordinate und Klasse.

Eine vollständige Suche über alle Kanten bestätigt die Ergebnisse des räumlichen
Index: Die nächste zulässige Straßenmittellinie liegt je Paar etwa **304–814 m**
entfernt; kein Index-Falschnegativ innerhalb 300 m. Die Entfernungen verwenden
dieselbe lokale metrische Näherung wie die bisherige HGV-Abbildung, keine
amtliche Vermessung von Zufahrten. Rohwerte:
`native/exhaustive-distance-check.json`, Zusammenfassung `distance-summary.json`.
Die 125 automatischen HGV-Kantenbindungen bleiben auch nach der geänderten
Wendeoption unverändert (`native/heavy-auto-binding-comparison.json`).


### 24.5 Regressionen und verbleibende Grenze

Die native Sackgassenprobe sichert sowohl erlaubtes Wenden als auch fortbestehende
explizite OSM-Wendeverbote. Komponentenfälle prüfen Klassen, Richtung, feste
Kanten, Index gegen vollständige Suche, abgebrochene Router mit unbekannten
Gesamtzählern, Tagesrandfälle, unterdrückte Teleportlogs, Fahrradstatistik und
Bereinigung veralteter Dateien. Die regionale ID-Regression bewahrt zusätzlich die
ursprünglichen 2.336 abgeschlossenen schweren Fahrten und deren null Teleports.
Sie verwendet das lokale archivierte Ergebnis; fehlt dieses, ist sie ausdrücklich
geskippt und ersetzt keinen nativen neuen Lauf. Die Oberfläche prüft getrennte
Zählereinheiten und sichtbare SUMO-Fehler trotz übergeordnetem Jobstatus DONE.

Ein eigener negativer Zwischenversuch mit reiner Klassenabbildung hatte 29 statt
18 fehlender Routen, darunter neun neue Verluste. Klassenkorrektheit allein genügt
also nicht; deshalb wurde anschließend die Richtung derselben Straße berücksichtigt.
Dieser Zwischenstand bleibt unter `native/` erhalten. Die abschließende Probe liegt
getrennt unter `final-native/`; ihr diagnostisches Geo-Fallback bei unzuordenbaren
Endpunkten ist keine produktive Freigabe. Der vollständige Gütermodus stoppt dort
bereits mit dem Netzbericht.

Die technische Aufgabenliste umfasst Diagnose, Straßenzuordnung, Vorprüfung,
Zählung/Oberfläche und Regressionen. Die fachliche Gesamtabnahme bleibt an korrekte
Quellpositionen gebunden. Die bekannten Endpunkte außerhalb des Radius werden
weder gelöscht noch durch eine Radiusvergrößerung oder gelockerte Sperren versteckt.
Ein separates Einfahrtsmodell bleibt abgewählt.

**Verifiziert (2026-10-17):** Vollständige Backend-Suite: 286 Fälle, keine Fehler,
vier ausgewiesene Skips. Frontend: 62 Tests grün und Produktionsbuild erfolgreich.
Die 17 gezielten Routing-/Bilanz-/Güterfälle laufen ohne Skip; darunter echte
native Sackgassen-/Wendeverbotsfälle und die lokale regionale Archiv-Regression.


### 24.6 Abschließende regionale Verifikation (2026-10-17)

Die eingefrorene abschließende Klassen-/Richtungsabbildung routet nativ **3.821 von
3.839** Anfragen. Gegenüber dem Ursprung werden 298 fehlende IDs wiedergewonnen;
keine zuvor vorhandene ID geht neu verloren. Die 18 verbleibenden Verluste sind
vier Pkw mit fehlender Koordinatenzuordnung, sechs Pkw ohne gerichtete Verbindung
und acht Fußanfragen ohne native Route. Alle angeforderten Fahrrad-, Paket- und
2.336 schweren Güterfahrten haben Routen. Zusätzlich bleiben 84 vorhandene Routen
mit Koordinatenwarnungen sichtbar. Das ist weiterhin keine Gesamtabnahme.

Die Richtungskorrektur beseitigt damit die zusätzlichen Verluste des negativen
Klassen-Zwischenversuchs (29 → 18). Der HGV-Anfrage-XML ist bytegleich zum Ursprung;
alle 125 automatischen Kantenbindungen sind unverändert. Produktionsklassen liegen
für die Probe eingefroren unter `final-classes/`; Quellhashes, Kommandoargumente und
Exitcodes sind abgelegt. `routing-comparison-final.json` und
`final-native/traffic-routing.json` enthalten die vollständigen ID-Bilanzen.


**Produktiver Regionalnachweis (2026-10-17):** Die unveränderte vollständige
`GenerationService`-Kette mit eingefrorenen Produktionsklassen prüft alle 3.839
Anfragen und stoppt die SUMO-Stufe mit FAILED: 145 nicht zuordenbare Endpunkte und
sechs Anfragen ohne gerichteten Pfad, elf korrigierte Richtungen. Es werden keine
Routen-/Statistik-/Abschlussdateien oder Success-Summary vorgetäuscht. Der Job
bleibt gemäß bestehender Optionalstufen-Politik DONE; der konkrete SUMO-Fehler ist
sichtbar. Eingaben, Quell-/Klassenhashes und Ergebnis liegen separat unter
`.runtime/traffic-routing-regional-2026-10-17-final/verified-result.json`.


**Frische native Güterkontrolle (2026-10-17):** Auf dem abschließenden Netz werden
alle 2.336 schweren Fahrten mit ihren neuen nativen Routen separat bis zum
Tagesende ausgeführt: 2.336 angefordert, geroutet, gestartet und beendet; keine
fehlende/unfertige Fahrt, null Teleports. Die Kontrolle isoliert den Güterpfad und
ersetzt keine Gesamtabnahme oder Zugangsfreigabe. Kommando, Abschluss-IDs,
Tripinfo, Statistik und Ladehinweise sind unter
`.runtime/traffic-routing-2026-10-16/heavy-control/` archiviert.


## 25. Quellorte der Restfälle und konsistente Pkw-Initialisierung (2026-10-19)

Der vorhandene Vertrag `synthesis-network-coverage-v1` wird additiv ergänzt:
`endpointOrigins` dokumentiert Prüfumfang, exakte Vergleichsregel, Quellenhashes und
Lesestatus je Eingabedatei. Problematische Endpunkte erhalten `coordinateSources`
mit Datensatzanzahl, Quellarten und bis zu acht Beispielen (Datei, Zeile,
Datensatz-ID, Aktivität und Zonenreferenz). Die Gruppen überlappen bewusst;
Beispielbegrenzung ist durch `examplesTruncated` sichtbar. Bei Verbindungsfehlern
werden beide Endpunkte untersucht. Unvollständige/fehlende Quelldateien machen die
Diagnose unvollständig, heben aber die ursprüngliche Netzprüfung nicht auf.

Es gibt keine neue Pipeline-Stufe oder Abhängigkeit. Die bestehende Artefaktliste
stellt den erweiterten Netzbericht weiterhin zum Download bereit. Pkw werden
unabhängig davon im Populations- und Legacy-Export am jeweiligen Haushaltswohnort
initialisiert. Die 300-m-Grenze und die Güter-Kantenregeln bleiben unverändert.
Der alte Regionalbefund bleibt archiviert; ein neuer Gesamtrouting-Erfolg wird
nicht behauptet.
[Quellaufteilung, Pkw-Vergleich und Aussagegrenzen](05-validierung.md#ortsherkunft-der-sumo-restfälle-und-pkw-initialorte-2026-10-19).


## 26. Straßennahe synthetische Orte (2026-10-19, experimentell)

Die Quellkorrektur zu Abschnitt 25 ist als abschaltbare Szenario-Option
`population.roadLocatedActivities` verdrahtet (Default `false`). Sie ändert die
Erzeugung der synthetischen internen Punkte; sie vergrößert nicht die spätere
SUMO-Zuordnungsdistanz. Gemeinsame öffentliche Pkw-/Rad-/Fuß-Straßen werden im
vorhandenen GraphHopper-Graphen ausgewählt. Die unveränderte Ortsziehung wird ohne
weiteren Zufallszug auf einen geeigneten Abschnitt derselben Zone gelegt.
Serialisierte Punkte werden gegen die echte Bbox-/LAEA-Zone geprüft.

Externe Zielanker, hochgeladene/manuelle bzw. OSM-Depotkoordinaten und Güter-OD-
Koordinaten bleiben erhalten. Fehlende Straßen führen zum Fehler und zum
Ortsbericht. Ein positives Ortsmodell hebt keine Güterprofil-, Zugangs-, native
Routing- oder Ausführungsprüfung auf. Die Punkte sind ausdrücklich synthetisch;
ein separates Gebäude-/Betriebseinfahrtsmodell bleibt abgewählt.
[Modul- und Artefaktvertrag](03-architektur.md#synthetische-orte-an-gemeinsamen-straßen-2026-10-19-experimentell).


Der erste vollständige Regionalversuch der Ortswahl bleibt als negativer Nachweis
archiviert: 20 Endpunkt- und acht Verbindungsfehler statt der prognostizierten null.
Die korrigierte Auswahl berücksichtigt den Unterschied der Pkw-Freigaben auf
`service`-Straßen und eine konservative schwache Hauptkomponente. Ein Erfolg der
Ortswahl ersetzt weiterhin keine native Routing-/Ausführungsabnahme.
[Befund und Modellgrenzen](05-validierung.md#straßennahe-orte-negativer-erstlauf-und-native-netzberechtigungen-2026-10-19).


Die Ortswahl verlangt inzwischen sowohl die schwache Pkw- als auch die schwache
Fuß-Hauptkomponente: Geschwindigkeitsregeln gelten ebenfalls für Fußverbinder,
damit begehbare Standorte hinter nicht begehbaren Straßen keine scheinbare
Erreichbarkeit erhalten. Der Bericht führt beide Filter und verworfene
Kandidaten. Getrennte lokale Netze können konservativ ausgeschlossen werden;
die eigentliche native Routenprüfung bleibt die entscheidende Abnahme.


**Regionalabschluss:** Alle 3.839 exportierten Anfragen werden geroutet; keine
Fahrt geht durch Zuordnung oder Routing verloren. Alle 2.336 schweren Fahrten
enden ohne Teleport. Der Mengenerhalt der übrigen Nachfrage ist ebenfalls
bestätigt; ein Fußweg überschreitet den Tagesabschluss, zwei Rad-/Pkw-Teleports
an einer gemeinsamen Tram-/Straßen-Signalstelle bleiben prüfpflichtig. Die
Ortswahl ist damit technisch implementiert und für diesen Fall geprüft, bleibt
aber opt-in. Die bestehende Ausführungsbilanz und das Studio kennzeichnen den
Prüfstatus ausdrücklich.
[Gesamtbilanz, Eingaben und Grenzen](05-validierung.md#abschluss-version-4-routing-und-mengenerhalt-bestätigt-2026-10-19).

### Öffentliche Lade- und Tankmöglichkeiten (Prüfung 2026-10-20)

Der vorhandene BNetzA-Bestand versorgt bisher nur die MobileCity-Aggregation;
öffentliche Lade-/Tankstopps sind noch kein Bestandteil der Gütertouren.
Quellenwahl für Strom, CNG/LNG/LPG und H₂, Fahrzeugkompatibilität,
Straßenzuordnung und die Reihenfolge bis zur nativen Energie-/Stoppprüfung stehen
im [gemeinsamen Integrationsplan](19-fahrzeugimport-und-modellbegriffe.md#öffentliche-lade--und-tankinfrastruktur-bestandsprüfung-und-integrationsplan-2026-10-20).

Die anschließende Signalprüfung ist am 20.10.2026 abgeschlossen: Der korrigierte
Import beseitigt die beiden Rad-/Pkw-Teleports im selben Nachfragefall; sämtliche
schweren Fahrten bleiben unverändert und teleportfrei beendet. Der späte Fußweg
am Tagesende bleibt ausgewiesen. [Signalnachweis und Grenzen](05-validierung.md#regionaler-folgeversuch-zwei-teleports-beseitigt-2026-10-20).

## 27. Fahrzeugketten und erneuter Güterverkehrsfokus (2026-10-21)

Explizit zugeordnete Zustell- und schwere Güterfahrten können mit
`vehicleOperations.chainTrips` als native Fahrzeugketten ausgeführt werden.
Zwischen den Etappen bleibt dasselbe Fahrzeug an der Straßenposition; Bedienzeiten
und Verspätungen wirken auf die nächste Fahrt. Rückfahrten und der Tagesabschluss
werden in der vorhandenen Ausführungsbilanz berücksichtigt. Die Zuordnung erzeugt
keine neue Nachfrage und keine automatische Disposition.
[Bedienung und Modellgrenzen](03-architektur.md#native-fahrzeugketten-für-den-güterverkehr-2026-10-21).

Nach Nutzerentscheidung werden Verbrauchs-, Lade- und Tankmodelle zurückgestellt.
Die nächsten fachlichen Arbeiten sollen wieder die Güterverkehrsnachfrage und ihre
Integration betreffen: durchgängige Tour-/Fahrzeugzuordnung, belastbare Herkunft der
Gütermengen, gemeinsame Ausführung mit dem Personenverkehr sowie regionale
Prüfung von Mengen, Routen und vollständigen Tourabschlüssen. Der Stationsbestand
bleibt als optionale Datenbasis erhalten.


## 28. Unternehmenstouren: Kontext, lokale Ortszuordnung und zusätzliche SUMO-Ausführung (2026-10-21)

**Historischer Zwischenstand desselben Tages:** Die hier beschriebene SUMO-Pflicht
für Vorbereitung und Aktivierung wurde anschließend aufgelöst. Aktueller Vertrag
und Bedienung stehen in [Abschnitt 30](#30-gemeinsame-nachfrage-vor-den-zieladaptern-2026-10-21).

✅ **Umsetzungspakete 1–3 implementiert, experimentell und standardmäßig aus.**
Der bestätigte CSV-/XLSX-Entwurf bleibt unverändert im Vertrag
`synthesis-company-tours-v1` mit `simulationReady=false`. Ein separater Kontext
führt ihn nach ausdrücklicher Freigabe in `synthesis-company-tour-execution-v1`
über. Es gibt keine neue Pipeline-Stufe und keinen zusätzlichen Executor.

### Bedienung und Voraussetzungen

1. Im Szenario SUMO, vollständige Personennachfrage (100 %) und eine für SUMO
   wirksame Fahrzeugkonfiguration wählen. Physische Fahrzeuge anlegen und
   `vehicleOperations.enabled=true` sowie `chainTrips=true` setzen; speichern.
2. Importmapping bestätigen. Im Tourenbereich die gespeicherten Fahrzeuge neu
   laden und Unternehmenskennung, Simulationsdatum, Start-/Rückkehrdepot,
   Fahrzeug und Start-/Rückkehrzeit je ausgewählter Tour ergänzen. Ausgelassene
   Touren werden ausdrücklich ausgeschlossen und im Eingangssnapshot vermerkt.
3. Haltorte manuell mit Koordinaten belegen oder eine **lokale** CSV-/TSV-/XLSX-
   Adresstabelle hochladen: `street;postalCode;city;lon;lat`, alternativ
   `Strasse;PLZ;Ort;lon;lat`. Genau ein Tabellenblatt; Koordinaten in WGS84.
   Der Abgleich erhält Hausnummerntrennzeichen. Nur eindeutige Treffer werden
   übernommen; mehrdeutige Kandidaten brauchen eine Auswahl. Herkunft enthält
   Datei-SHA-256 und Quellzeile. Es werden keine Kundenadressen an externe
   Geokodierungsdienste übertragen.
4. Reihenfolge, Planzeiten, Fensterregel und Ortszuordnung fachlich bestätigen.
   Bei Datumsverschiebung ist eine eigene Bestätigung nötig. Die erste Fassung
   unterstützt einen Quelltag und einen vollständigen 24-Stunden-Simulationstag;
   Zeitumstellungstage, Nachtfahrten und mehrere ausgewählte Quelltage werden
   abgewiesen. Datum und konfigurierter SUMO-Wochentag müssen zusammenpassen.
5. **Kontext speichern und prüfen**, anschließend **Zusätzlich für SUMO auswählen**.
   `ready=true` bedeutet vollständig vorbereiteter Kontext, keine bestätigte
   Netzerreichbarkeit. Ein Upload oder bloßes Speichern aktiviert keine Fahrten.
   Kontext-/Mappingänderungen entziehen die Aktivierung. Ungespeicherte Änderungen
   an aktiven Importen sperren den Start im Studio, bis gespeichert oder durch
   erneute Importauswahl verworfen wurde.

Alle Depot- und Haltpunkte müssen innerhalb des Szenariorechtecks liegen.
Grenzüberschreitende Touren werden vollständig abgewiesen; es gibt noch keinen
Ein-/Austrittsschnitt oder Wiederanlauf außerhalb des Netzes. Keine erfundenen
Depotkoordinaten, Fahrzeuge oder Zusatzstopps. Mehrere Touren eines Fahrzeugs
müssen zeitlich passen und räumlich verbundene Depots haben. Eine bestehende
Zuordnung dieses Fahrzeugs zu anderer Nachfrage oder seine Nutzung in mehreren
aktiven Dateien wird abgewiesen.

### Verträge und Ausführung

Zusätzlich zur Import-API unter `/api/scenarios/{id}/company-tours`:

| Methode und Pfad | Wirkung |
|---|---|
| `PUT /{hash}/context` | Kontext speichern, Prüfpunkte zurückgeben, bisherige Auswahl entfernen |
| `POST /{hash}/locations` (multipart `file`) | Lokalen Adressbestand abgleichen; Kandidaten zurückgeben |
| `POST /{hash}/activation` (`{"enabled":true/false}`) | Exakte Ausführungsrevision zusätzlich auswählen/entfernen |

Das Szenario speichert `companyTours.enabled=false` und
`companyTours.imports=[{importId,executionHash}]`. Der Ausführungshash bindet
Quellhash, bestätigtes Mapping, Kontext und Fahrzeugkonfiguration. Vor dem Einreihen
in die Generierung wird die aktuelle Auswahl geprüft und als unveränderlicher
Batch eingelesen. Spätere Dateiänderungen verändern den bereits vorbereiteten
Lauf nicht. Ein geänderter Fahrzeugbestand erfordert erneute Aktivierung.

Die bestehenden nativen Fahrzeugketten führen Depot → Kundenhalte → Rückkehr
mit einem physischen Fahrzeug aus. Bedienzeiten werden einmal je Halt angesetzt,
Verspätungen wirken auf Folgeetappen. Der zusätzliche Beitrag trägt
`demandSource=company-tours`, Verkehrsart `logistics` und eigene Quellschlüssel.
Personen- und Paketnachfrage bleiben erhalten; die SUMO-Trip-Obergrenze für
Personenfahrten wird bei aktivem Unternehmensbeitrag aufgehoben. Zusätzliche
Fahrtetappen und abgeschlossene Touren stehen getrennt in der SUMO-Zusammenfassung
und im Studio.

| Artefakt unter `artifacts/` | Inhalt |
|---|---|
| `company-tours/input.json` | Ausgewählte, vorbereitete Revisionen einschließlich Fahrzeug, Koordinaten und Zeiten |
| `company-tours/routing.json` | Netz-SHA-256, routbare Etappen, optimistische Fahrzeituntergrenze, Planvergleich |
| `company-tours/execution.json` | Tatsächliche Ankunft/Bedienende, Verspätung, Fensterabweichungen, Tourabschluss einschließlich Rückfahrt |
| `vehicles/native-chains.json` | Gemeinsamer Nachweis physischer Fahrzeuge und durchgängiger Fahrtetappen |
| `manifest.json` → `companyTours` | Ausgewählte Revisionen, zusätzliche Etappen und Berichtspfade |

Die Fahrzeitvorprüfung verwendet innere Routenabschnitte und zulässige
Höchstgeschwindigkeiten: nachweislich zu kurze Planzeiten oder fehlende Routen
brechen die SUMO-Stufe mit Bericht ab. Das ist eine optimistische Untergrenze;
Kreuzungen, Stau und Ein-/Ausfahrtsanteile können weitere Verzögerungen erzeugen.
Fahrzeugklassensperren gelten, **individuelle Abmessungen und aktuelle Ladung sind
noch nicht vollständig zertifiziert** (`accessCertified=false`).

Fensterregeln: `report-only` dokumentiert Abweichungen; `arrival` prüft die Ankunft,
`service` die vollständige Bedienung. Die Regeln werden gegen Planung und echte
Ausführung geprüft; SUMO erzwingt keine pünktliche Ankunft. Nicht abgeschlossene
Halte/Rückfahrten, Teleports und Fensterverletzungen erhalten einen eigenen
Prüfstatus. Vollständig bediente Halte allein beweisen keinen Tagesabschluss des
Fahrzeugs. Vor jedem Runner-/Feedbackversuch werden frühere Erfolgsberichte
invalidiert, auch wenn der neue Versuch schon beim Netzbau scheitert.

### Nachweis und verbleibende Arbeit

`CompanyTourExecutionTest` prüft vollständigen Kontext, fehlende Fahrzeuge/Orte,
Regionsgrenze, Datumsverschiebung/Zeitumstellung, Reihenfolge, lokale
Adressmehrdeutigkeit und Hausnummerntrennzeichen sowie Aktivierung,
Änderungsinvalidierung und eingefrorene Eingaben. `CompanyTourIntegrationTest`
durchläuft Upload → Mappingbestätigung → Kontext → Aktivierung → eingelesenen
Batch → native SUMO-Ausführung: zwei PKW plus ein schwerer LKW, zwei Kundenhalte
und Rückfahrt, drei zusätzliche Etappen, ein physisches Tourenfahrzeug, genau
60 Sekunden Mindestbedienung je Halt. Wiederholung liefert denselben Bericht.
Ein gezielt fehlender nativer Fahrzeugabschluss wird als unvollständige Tour
bilanziert; ein echter früher Folgefehler lässt keinen alten Erfolgsbericht
stehen. Angular-Tests sichern lokale Ortsvorschläge, Kontextänderung,
Aktivierungssperre und aktuelle Auswahl nach Löschung ab. Isolierte Browserprobe
mit synthetischer Tour: Aktivieren, aktiven Kontext ändern (Start gesperrt),
speichern (Auswahl entzogen), erneut aktivieren.

**Offen außerhalb dieser Pakete:** fachliche Revisionserkennung zwischen
verschiedenen Dateien, mehrtägige oder grenzüberschreitende Touren, individuelle
Ladungs-/Abmessungsprüfung und regionale Abnahme mit realen Unternehmensdaten.
Die echte Zurbrüggen-Planung bleibt bis zur Ergänzung und Bestätigung ihrer
fehlenden Betriebsangaben ein Importentwurf. Lade-/Tankmodelle bleiben nach
Nutzerentscheidung zurückgestellt.


**Abschließende Gates:** 333 Backendtests ohne Fehler, vier explizit nicht
freigeschaltete Integrationstests übersprungen; ergänzte Abschlussregression
zusätzlich 6/6 grün ohne Skip. 74 Frontendtests und Produktionsbuild grün.
[Validierungsbefund](05-validierung.md#zusätzliche-unternehmenstouren-import-bis-native-ausführung-2026-10-21).


## 29. Additive Einspeisung und Erweiterung des LLM-Imports (2026-10-21)

**Nutzerentscheidung:** Überschneidungen zwischen eingelesenen Unternehmenstouren
und bestehender statistischer oder synthetischer Nachfrage werden bewusst ignoriert.
Die eingespeisten Unternehmensdaten werden als kleiner Zusatzbeitrag angenommen;
diese Größenordnung ist eine Szenarioannahme, keine gemessene Abdeckungsquote.
Ein Mengenabgleich oder Ersetzungsmodus ist deshalb kein aktueller Umsetzungsauftrag.
Die bestehende additive Einspeisung und getrennte Herkunftsauswertung bleiben bestehen.
Identische Uploads und Revisionen derselben Planung sind davon zu unterscheiden;
Schutz vor versehentlicher Mehrfachaktivierung bleibt sinnvoll.

**LLM-Import, damaliger Stand (überholt durch Abschnitt 31):** CSV/TSV/XLSX werden lokal geparst. Das optionale
lokale LLM schlägt ausschließlich Spaltenzuordnungen anhand bekannter semantischer
Feldbezeichnungen und Typstatistiken vor. Unbekannte Originalüberschriften und
Kundenwerte werden nicht übermittelt. Freie Dokumentlayouts, PDF/Scans, JSON/XML
und Transformationen über mehrere Tabellen sind noch nicht unterstützt.

**Vorgesehener Ausbau:** Formatadapter extrahieren Tabellen, Text oder bei Bedarf
OCR mit Quellreferenzen. Ein lokales LLM schlägt daraus einen versionierten,
deklarativen Mappingplan für Touren, Halte, Fahrzeuge, Zeit-/Datumsfelder und
Gruppierungen vor. Der Plan wird anhand einer Vorschau bestätigt; ein kontrollierter
Konverter verarbeitet danach sämtliche Datensätze und validiert den internen
Tourenvertrag. Fehlende oder mehrdeutige Angaben bleiben sichtbare Prüfpunkte.
Bestätigte Unternehmensprofile sollen bei Folgeimporten wiederverwendbar sein;
abweichende Quellstrukturen erfordern erneute Prüfung. Weitere Formate benötigen
jeweils einen passenden Extraktionsadapter; vollständig beliebige Dateien werden
nicht als fehlerfrei lesbar zugesichert. Dies ist ein Ausbaukonzept, keine bereits
implementierte universelle Formatunterstützung.


## 30. Gemeinsame Nachfrage vor den Zieladaptern (2026-10-21)

✅ **Architekturkorrektur nach Nutzerpräzisierung:** Güterverkehr und
Unternehmenstouren gehören zur internen Nachfrage. SUMO ist ein nachgelagerter
Konsument. Die SUMO-Pflicht aus Abschnitt 28 gilt für die Nachfragevorbereitung
und Aktivierung nicht mehr. Der bestehende Importvertrag bleibt erhalten.

### Gemeinsamer Vertrag und Zeitpunkt

`TrafficDemand` unter `generation/demand/` führt im Vertrag
`synthesis-traffic-demand-v1` vier Beiträge zusammen:

| Beitrag | Erhaltener Inhalt |
|---|---|
| Personenverkehr | vollständiger `TravelDemand` einschließlich intermodaler Legs, Zonen, Koordinaten und Planzeiten |
| Paketlogistik | vor dem Export normalisierte logiTopp-Tourenetappen, Quellschlüssel, Bedienzeiten, Rückfahrt und vorhandene geplante Ankunft |
| Allgemeiner schwerer Güterverkehr | einmal erzeugte Fahrten aus den Relationen, Klasse, Bewegung, Bezugstag und optionale SUMO-Kantenhinweise |
| Unternehmenstouren | eingefrorene freigegebene Revisionen mit Halten, Fensterregeln, physischem Fahrzeug und Depot-Rückkehr |

Der gemeinsame Bestand enthält auch den aufgelösten Fahrzeugkatalog und aktive
Fahrt-/Fahrzeugbindungen. Die Klassifikation `private`/`logistics` und
`demandSource` bleiben voneinander getrennt. Es handelt sich um Wege bzw.
Fahrtanfragen, nicht automatisch um gleich viele physische Fahrzeuge.
Zeitreferenz der gemeinsamen Bewegungstabelle: Sekunden seit Montag 00:00.
Personenverkehr umfasst die Modellwoche, Güterbeiträge ihre jeweiligen Bezugstage.

Die bestehende Logistikstufe erzeugt den ersten Bestand mit den vorbereiteten
Güter- und Unternehmensbeiträgen vor den Exportstufen. Nach erfolgreicher
logiTopp-Nachfrageerzeugung werden aktuelle Personenwege und Pakettouren ergänzt;
Netz-Feedback aktualisiert denselben Vertrag erneut. Keine zusätzliche Stufe,
kein weiterer Executor und keine neue externe Laufzeit. Die bisherige
Personen-Schnittstelle `synthesis-travel-demand-v1` samt Personen-CSV und Modal-Split
bleibt unverändert; Güterfahrten werden nicht als Personenwege ausgegeben.

Fehlt die logiTopp-Erzeugung, ist `personDemandStatus=not-generated` statt eines
behaupteten Nullverkehrs gesetzt. Für fehlende Pakettouren gilt dasselbe über
`parcelDemandStatus`. Alte Personenartefakte werden zu Laufbeginn entfernt;
vorhandene rohe Simulationsergebnisse eines früheren Laufs werden bei einer
übersprungenen Stufe nicht in den gemeinsamen Bestand übernommen.
Fehlerhafte Pakettabellen brechen die gemeinsame Aufbereitung ab; die strenge
Vorbereitung darf unbekannte Formate oder unvollständige Zeilen nicht still
als leere Quelle ausgeben. Der kompatible alte Runner-Einstieg bleibt erhalten.

### Dateien und Bedienung

| Artefakt unter `artifacts/demand/` | Bedeutung |
|---|---|
| `traffic-demand.json` | Vollständiger gemeinsamer, typisierter Eingang für weitere Adapter |
| `movements.csv` | Vereinheitlichte Bewegungen mit Quelle, Kategorie, Tour-/Fahrzeugreferenz und Wochenzeiten |
| `tours.json` | Touren und geordnete Referenzen auf ihre Etappen |
| `traffic-summary.json` | Mengen nach Herkunft/Kategorie, Tourzahl und Quellenverfügbarkeit |
| `planned-connections.geojson` | Quelle-Ziel-Verbindungen, ausdrücklich `routed=false`; keine Straßenrouten |

Das Manifest verweist über `trafficDemand` auf Eingang, Bewegungen, Touren,
Verbindungen und aktuelle Zusammenfassung. Im Studio zeigt **Gemeinsame Nachfrage**
die Quellenbilanz und Downloads auch ohne SUMO an.

Schwerer Güterverkehr aktiviert keine Zielmodelle mehr. Beim Abwählen von SUMO
bleiben allgemeine Güternachfrage und ausgewählte Unternehmenstouren bestehen.
Ein gültiger gespeicherter Fahrzeugkatalog mit konkreten Fahrzeugen reicht für
die Unternehmensvorbereitung; `application=catalogOnly` ist zulässig. Die eigene
Katalog-/Bindungsdatei kann auch ohne native Fahrzeugausführung geladen werden.
Die Anwendung `sumoTraffic` kann ebenfalls als Konfiguration gespeichert bleiben,
ohne die Auswahl dieses Zielmodells zu erzwingen. Direkte Fahrzeugbindungen sind
nun ebenfalls vom gewählten Exportziel unabhängig.

Die Aktivierung verlangt weiterhin 100 % Personennachfrage und vollständigen,
bestätigten Kontext. Datum und Quellenstände werden intern erhalten; der
SUMO-Wochentag blockiert ihre Vorbereitung nicht mehr. Die Begrenzungen auf
vollständige Unternehmenstouren innerhalb der Region und eines 24-Stunden-Tages
bleiben bestehen. Additive Einspeisung ohne Überschneidungsabgleich bleibt die
Nutzerentscheidung aus Abschnitt 29.

### SUMO und Grenzen der weiteren Adapter

Der produktive SUMO-Pfad liest `traffic-demand.json`. Er erzeugt keine schweren
Fahrten erneut und liest keine ursprüngliche Pakettabelle mehr. Tagesauswahl,
Netz-/Profilprüfung, native Straßenrouten und tatsächliche Ausführung gehören
zum Adapter. Unternehmensfahrzeuge erhalten dort die nötigen physischen Typen
und nativen Ketten, auch wenn nur ein Katalog vorliegt; eine globale zufällige
Fahrzeugtypverteilung bleibt bei `catalogOnly` ausgeschaltet. Exportbezogene
SEVAS-/Profilregeln und abweichende SUMO-Tage werden dort geprüft.

Der gemeinsame Bestand enthält noch keine universell zertifizierten LKW-
Straßenrouten. Fehlende Fahrzeit/Ankunft einer ergänzten Paket-Rückfahrt bleibt
unbekannt. Nicht straßengebundene Pakettouren bleiben im gemeinsamen Vertrag;
der bestehende Straßen-SUMO-Adapter unterstützt sie nicht. Die vorhandenen
MobileCity- und Legacy-Modelle erhalten hierdurch nicht automatisch einen neuen
nativen Gütertourenimport. Für deren individuelle Formatfähigkeiten sind weitere
Adapter nötig; die gemeinsame Datenbasis ist jetzt dafür vorhanden.

### Nachweis

- `TrafficDemandTest`: vier Quellen, unveränderte Personenwege, JSON-Roundtrip,
  Tour-/Quellreferenzen, einmalige Standzeit und explizit ungeroutete Geometrien;
  Schienen-Pakettouren bleiben im allgemeinen Bestand erhalten.
- `CommonTrafficDemandPipelineTest`: echte Pipeline ohne SUMO und ohne logiTopp-
  Laufzeit, zwei schwere Fahrten und drei Unternehmensetappen; unabhängige
  Bezugstage, Katalog ohne SUMO-Allokation, keine Wiederverwendung alter
  Personen-CSVs, identische Wiederholung und Bereinigung bei Abwahl. Personen-
  Synthese bleibt bei ein-/ausgeschalteten Zusatzbeiträgen bytegleich.
- `CompanyTourIntegrationTest`: Upload bis Aktivierung und danach JSON-Export/
  Wiedereinlesen; zwei PKW, zwei allgemeine schwere Fahrten, zwei Paketetappen
  und drei Unternehmensetappen ergeben neun Anfragen und sieben native Fahrzeuge.
  Die ursprüngliche Pakettabelle wird vor der Wiederholung gelöscht; SUMO liest
  weiterhin denselben gemeinsamen Bestand. Bedienung/Rückfahrt und früher
  Fehlerfall bleiben geprüft.

Logs und synthetische Bedienprobe: `.runtime/common-demand-2026-10-21/`.


**Abschließende Prüfung:** 339 Backendtests ohne Fehler, vier bestehende optionale
Skips; abschließende Adapter-/Skip-Regression 15/15 ohne Skip. 75 Frontendtests und
Produktionsbuild grün. [Validierungsbefund](05-validierung.md#gemeinsame-nachfrage-unabhängig-von-sumo-2026-10-21).

## 31. Formatübergreifender Tourenimport — ✅ UMGESETZT (2026-10-22)

Der Import erfolgt vor der gemeinsamen Verkehrsnachfrage und unabhängig vom
gewählten Export. Bestätigte Touren durchlaufen weiterhin Ortszuordnung,
Depot-/Fahrzeugkontext und explizite Aktivierung. Danach kommen ihre Etappen
additiv in `synthesis-traffic-demand-v1`; eine Überschneidungskorrektur ist nicht
vorgesehen. Der Importassistent aktiviert keine Tour und erfindet keinen Kontext.

### Extraktion und Zwischenformat

`TourSourceReader` bildet CSV, TSV, XLSX, JSON, XML, TXT, DOCX und textbasierte
PDF auf `synthesis-tour-source-v1` ab. Der Vertrag enthält Quellhash, Format,
Tabellen mit Zeilennummern sowie Textblöcke und Quellreferenzen. JSON/XML können
verschachtelte Tour-/Haltlisten enthalten: gemeinsame Elternwerte werden an
untergeordnete Datensätze weitergegeben. Postleitzahlen als Zeichenketten bleiben
unverändert. DOCX liefert Tabellen und Text; PDF liefert seitenbezogene Textblöcke.

Eine beliebige Datei ist damit nicht automatisch lesbar: XLS, proprietäre
Binärformate und Bilder benötigen weitere Extraktionsadapter; Scans benötigen
OCR außerhalb des aktuellen Imports. PDF erfordert Python 3 mit `pypdf` und den
lokalen Helfer `scripts/extract-tour-document.py`; die geprüfte Abhängigkeit ist
in `runtime/tour-import-requirements.txt` gepinnt. Installation in einer eigenen
Python-Umgebung: `python -m pip install -r runtime/tour-import-requirements.txt`.
Der Pythonpfad und der
Skriptpfad sind über `document-python` / `document-extractor` konfigurierbar.
Es wird keine Datei an einen externen Dienst hochgeladen.

Grenzen: 5 MB Upload; strukturierte Quellen höchstens 100 Tabellen, 10.000
Datensätze, 200 Spalten und 20 Verschachtelungsebenen; DOCX höchstens 30 MB
entpackt. PDF: höchstens 100 Seiten, 200.000 Textzeichen, 30 Sekunden Prozessbudget
und 20 Sekunden CPU-Budget. Für die Dokumentanalyse gelten zusätzlich 500
Textblöcke / 30.000 Zeichen / 200 Ergebniszeilen. Übergroße Quellen werden
abgewiesen, nicht still auf einen Ausschnitt verkürzt. Tabellenanalyse erhält
höchstens die ersten 30 Zeilen je Tabelle und kann daher spätere Strukturwechsel
nicht zuverlässig erkennen; die vollständige deterministische Vorschau bleibt
verbindlich.

### Lokale LLM-Analyse und kontrollierte Regeln

Der bisherige aggregierte `/suggest`-Pfad sendet weiterhin keine Kundenwerte.
Der neue `/analyze`-Pfad benötigt **beide** Betriebsflags
`synthesis.company-tour-import.llm-enabled=true` und
`synthesis.company-tour-import.source-analysis-enabled=true`; Defaults sind
`false`. Nur Loopback-Endpunkte sind erlaubt. Erst der Analyseknopf übergibt
Tabellenproben bzw. den extrahierten Dokumenttext an das lokale Modell.

Bei Tabellen schlägt das Modell Tabelle, Kopfzeile, Spalten und begrenzte Regeln
vor: Leerwerte explizit weiterführen, Spalten zu Adressen verbinden und Uhrzeiten
als `HH:mm`, `HH.mm` oder `HHmm` lesen. Es gibt keine vom Modell erzeugten Skripte,
Regulärausdrücke oder ausführbaren Transformationen.

Bei Dokumenten muss jeder ausgegebene Feldwert durch ein wörtliches Zitat aus
einem bestimmten Textblock belegt werden. Der Server ermittelt die Zeichenposition
selbst und verweigert erfundene Zitate, ungültige Positionen und unbekannte
Zielfelder. Jeder Textblock muss verwendet oder mit einem sichtbaren Grund
als ausgelassen klassifiziert sein. Diese strukturelle Prüfung beweist keine
semantisch vollständige Erfassung: Quellbelege, ausgelassene Inhalte und alle
Tourenzeilen müssen in der Vorschau geprüft werden. Der Bestätigungsendpunkt
verlangt dafür bei Dokumentextraktionen `sourceReviewed=true`. Fehlende
Pflichtwerte führen weiterhin zu einem ungültigen Entwurf.

### Wiederverwendbare Profile und API

Nur bestätigte Mappings können als szenariolokales Firmenprofil gespeichert
werden (`uploads/company-tour-profiles/`). Profile enthalten Regeln und einen
Strukturhash, keine Kundenbeispielwerte. Tabellenprofile verlangen dasselbe
Format, dieselbe Tabellenkennung und unveränderte Kopfzeilenposition/-inhalte.
Dokumentprofile extrahieren jede neue Quelle erneut mit frischen Belegen; alte
Textpositionen und Werte werden nicht übernommen. Anwendung liefert immer eine
unbestätigte Vorschau.

Ergänzungen unter `/api/scenarios/{id}/company-tours`:

| Methode | Pfad | Funktion |
|---|---|---|
| POST | `/{hash}/analyze` | `{mode: "auto"\|"tables"\|"document"}` |
| GET | `/profiles` | Szenariolokale Profile auflisten |
| POST | `/{hash}/profile` | Bestätigte Regeln mit `{name}` speichern |
| POST | `/{hash}/apply-profile` | Profil mit `{profileId}` als Vorschlag anwenden |

Extrahierte Quelltexte liegen wie bisherige Tabellen ausschließlich in privaten
Uploads. Die API liefert für Vorschau/Quellbelegprüfung Textblöcke und Referenzen,
keine vollständigen unbekannten Tabellenwerte im Analyseobjekt. Bereits vorhandene
Imports ohne Quellenhülle können durch erneuten Upload derselben Datei ergänzt
werden, ohne bestätigtes Mapping oder Kontext zu überschreiben.

**Verifikation und Grenzen:** siehe [Validierungsbefund](05-validierung.md#formatübergreifender-tourenimport-2026-10-22).

## 32. Schutz vor mehrfach ausgewählten Planrevisionen — ✅ UMGESETZT (2026-10-23)

Verschiedene Dateien können dieselbe Tourenplanung enthalten, obwohl Dateihash,
Auftragskennungen oder zugeordnetes Fahrzeug abweichen. Vor der Aktivierung und
beim Einfrieren der gemeinsamen Nachfrage prüft `CompanyTourRevisions` deshalb
jede tatsächlich ausgewählte Unternehmenstour über den Geschäftsschlüssel
**Unternehmenskennung + Simulationsdatum + Quell-Tourkennung**. Kennungen werden
unter Beachtung der Groß-/Kleinschreibung verglichen; führende/abschließende
Leerzeichen der Quell-Tourkennung werden entfernt. Bei einer Kollision wird die
Auswahl abgewiesen. Die Fehlermeldung nennt Unternehmen, Datum, Tourkennung und
den bereits ausgewählten Import.

Das Simulationsdatum ist maßgeblich: Eine bewusst auf denselben Szenariotag
verschobene Planung kann die Prüfung nicht durch ein anderes Quelldatum umgehen.
Ausgeschlossene Touren erzeugen keine Kollision. Verschiedene Unternehmens- oder
Tourkennungen bleiben eigenständige additive Beiträge. Die bisherige gesonderte
Prüfung mehrfach verwendeter physischer Fahrzeuge bleibt wirksam.

### Bewusster Wechsel der Planung

1. Den bisherigen Import in der Importliste auswählen und aus der Nachfrage
   entfernen; alternativ die betroffene Tour in dessen Kontext ausschließen,
   den Kontext speichern und den verbleibenden Import erneut auswählen.
2. Die neue Datei einschließlich Mapping und Betriebsangaben prüfen.
3. Die neue Revision zur Nachfrage hinzufügen.

Die Prüfung erfolgt vor dem Speichern einer neuen Aktivierung. Scheitert sie,
bleiben die zuvor gespeicherte Auswahl sowie beide Quellimporte erhalten. Bereits
eingefrorene Generierungsbatches ändern sich dadurch nicht. Eine automatische
Ersetzung oder Zusammenführung erfolgt nicht; mehrere Dateien werden auch nicht
anhand ähnlich aussehender Adressen oder Aufträge automatisch gleichgesetzt.

Dies ist ein Schutz gegen gleiche fachliche Kennungen, keine allgemeine
Revisionserkennung: Werden Unternehmen oder Tour in einer Folgeplanung umbenannt,
ist die Gleichheit weiterhin manuell zu beurteilen. Ein Wechsel der
Unternehmenskennung darf nicht als Umgehung verwendet werden; für denselben
Betrieb muss dieselbe Kennung verwendet werden. Der Schutz betrifft ausschließlich
Unternehmenstouren untereinander. Die ausdrückliche Nutzerentscheidung,
Überschneidungen mit statistischem oder synthetischem Verkehr zu ignorieren,
bleibt bestehen.

**Verifiziert:** `CompanyTourRevisionsTest` prüft geänderte Datei/Planzeiten mit
anderem Fahrzeug, unveränderte Szenariodatei nach Ablehnung, bewussten Wechsel,
Unveränderlichkeit eines eingefrorenen Batches, direkte Auswahl und
Datumsverschiebung, additive verschiedene Unternehmen/Tourkennungen und
explizit ausgeschlossene Touren. [Validierungsbefund](05-validierung.md#tourenrevisionen-vor-der-gemeinsamen-nachfrage-2026-10-23).

## 33. Verbleibende Umsetzungspakete nach dem Revisionsschutz (2026-10-23)

Die abgeschlossenen Abschnitte 30–32 ersetzen frühere Ausbauankündigungen zum
gemeinsamen Nachfragebestand, den zusätzlichen Dateiformaten, Importprofilen und
dem Schutz identischer fachlicher Tourkennungen. Folgende Arbeiten bleiben offen:

| Paket | Nächster konkreter Schritt | Abnahme |
|---|---|---|
| Mehrere Tabellenblätter verknüpfen | Deklarative Schlüsselverknüpfung zwischen Tourkopf und Halt-/Auftragstabelle, mit Vorschau der nicht zugeordneten und mehrfach passenden Schlüssel. | Keine stillen Zeilenverluste oder kartesische Vervielfachung; alle Quellzeilen nachweisbar verarbeitet/ausgewiesen. |
| Scans und Bilddateien | Lokaler optionaler OCR-Adapter mit Seiten-/Bildreferenzen und unveränderter nachfolgender Zitatprüfung. | Gedrehte/unscharfe synthetische Belege prüfen; fehlende OCR-Laufzeit und unlesbare Inhalte sichtbar abweisen. |
| Mehrtägige Touren und manuelle Gateways (Netzprojektion umgesetzt, Abschnitt 35) | Eigenständiger Zeitvertrag für Übernachtung/Tageswechsel; bestätigbare manuelle Korrektur von Grenzübergängen und Netzvorschau vor dem Generierungslauf. | Vollständige Kette einschließlich Rückfahrt; keine stille Verkürzung auf 24 Stunden oder unbelegte externe Fahrzeiten. |
| Reale Unternehmensabnahme | Fehlende Depot-, Fahrzeug-, Orts- und Zeitsemantik der Beispielplanung fachlich ergänzen und bestätigen. | Importentwurf bis gemeinsame Nachfrage und nachgelagerte Ausführung mit vollständiger Tourbilanz. |
| Regionaler Mengen-/Zugangsnachweis | Quellabdeckung und Mengen anhand unabhängiger Regionaldaten prüfen; noch unbekannte individuelle Ladungs-/Abmessungsrestriktionen gezielt schließen. | Mengenvergleich und nachvollziehbare Zugangsprüfung, keine pauschale Zertifizierung allein aus generischen Fahrzeugklassen. |

Eine automatische Zusammenführung umbenannter Planrevisionen ist weiterhin eine
eigene mögliche Erweiterung. Energie-, Lade- und Tankmodelle bleiben gemäß
Nutzerentscheidung zurückgestellt. Ein Überschneidungsabgleich mit sonstiger
Verkehrsnachfrage ist ausdrücklich nicht Teil der offenen Aufgaben.

## 34. Touren mit externem Start, Ende und Zwischenhalten — ✅ ERSTER ADAPTER (2026-10-23)

Unternehmenstouren können jetzt außerhalb des Untersuchungsgebiets beginnen oder
enden. Auch externe Zwischenhalte und eine spätere Wiedereinfahrt werden als
vollständige Originalplanung übernommen. Der bestätigte Tourenkontext muss dafür
`regionalProjectionConfirmed=true` enthalten; Standard und Altimporte sind
`false`. Im Studio heißt die ausdrückliche Freigabe **Tourorte außerhalb der Region
zulassen und regionale Schätzung bestätigen (experimentell)**. Die übrigen
Koordinaten-, Zeit-, Fahrzeug- und Revisionsprüfungen bleiben bestehen. Start- und
Endort dürfen voneinander abweichen; das optionale gemeinsame Depot belegt beide
Felder nur auf ausdrücklichen Knopfdruck.

### Originalnachfrage und regionale Ausführung

Die gemeinsame Nachfrage behält **alle Originaletappen, Koordinaten, Planzeiten
und Bedienzeiten**. `Prepared.regionalScope` hält bei der Freigabe die Region als
unveränderlichen Bestandteil der ausgewählten Ausführungsrevision fest. Eine
Regionsänderung verlangt erneute Kontextprüfung/Aktivierung. Default-Kontexte
behalten ihren bisherigen Hashaufbau und benötigen keine zusätzliche Freigabe,
solange ihre Orte vollständig innerhalb der Region liegen.

`CompanyTourRegion` erzeugt getrennt davon eine deklarierte regionale Projektion.
Der erste Adapter schneidet die **gerade Verbindung** zwischen zwei geplanten
Orten am Regionsrechteck; Eintritts-/Austrittszeiten werden proportional zur
geplanten Fahrzeit interpoliert. Das ist eine ausdrücklich bestätigte
Modellannahme, **keine berechnete externe LKW-Straßenroute**. Eine Straße kann einen
anderen Grenzübertritt verwenden oder das Gebiet trotz nicht schneidender gerader
Verbindung berühren. Der Adapter verändert die Netzregion nicht und verwendet
für regionale Straßenrouten das vorhandene SUMO-Netz einschließlich dessen
vorhandener Randbereiche. Der optionale netzbasierte Adapter in Abschnitt 35
ersetzt diese Geometrieschätzung. Manuell übersteuerbare Übergabepunkte bleiben offen.

| Etappentyp | Regionale Behandlung |
|---|---|
| Binnenverkehr | Koordinaten, Planzeiten, Bedienung und Fenster unverändert |
| Einfahrt | Beginn am geschätzten Grenzpunkt/-zeitpunkt; Bedienung am internen Ziel bleibt erhalten |
| Ausfahrt | Ende am geschätzten Grenzpunkt/-zeitpunkt; dort keine externe Kundenbedienung und keine externen Kundenfenster |
| Durchgangsverkehr | Geschätzter Abschnitt zwischen Eintritt und Austritt; keine externe Bedienung |
| Reine Außenetappe / bloße Berührung | Kein regionales Fahrzeugsegment; Original bleibt in der Nachfrage und wird im Projektionsbericht explizit als `outside` ausgewiesen |

Die ursprüngliche Abschlussfahrt zum bestätigten Endort bleibt erhalten. Das
Legacy-Feld `returning` bezeichnet weiterhin diese Abschlussfahrt; der Endort
muss dafür nicht mit dem Startort identisch sein. Die Tagesgrenze, Zeitumstellung
und mehrere ursprüngliche Quelltage sind damit nicht aufgehoben. Verbindungen
über die Datumsgrenze benötigen einen eigenen geometrischen Vertrag und werden
für diese Projektion abgewiesen.

### Wiedereinfahrten und Nachweise

Bei einer erneuten Einfahrt entsteht ein weiterer nativer **Gebietsbesuch**
desselben physischen Fahrzeugs. Die native SUMO-ID erhält dafür einen Besuchszusatz;
physische Fahrzeugkennung und Quellreferenzen bleiben erhalten. Zwischen den
Besuchen wird keine künstliche Fahrt auf dem regionalen Netz erzeugt. Die Prüfung
meldet zeitlich überlappende Gebietsbesuche als nachprüfungsbedürftig. Verzögerungen
innerhalb eines Besuchs wirken weiter auf dessen Folgeetappen; der externe
Zwischenverlauf wird nicht simuliert und nicht dynamisch neu disponiert.

`vehicles/native-chains.json` trennt `physicalVehicles` von `nativeVisits` und
enthält `regionalVisitTimingValid`. `company-tours/execution.json` verwendet bei
freigegebener Projektion `regional-complete` sowie `completedRegionalTours`.
`completedTours` bleibt dabei **0**; `outsideExecutionSimulated=false`. Ein regionaler Abschluss
bescheinigt somit keinen externen Kundenhalt oder vollständigen externen
Tourabschluss. Rein externe Etappen erscheinen nur in der Originalnachfrage und
im Projektionsbericht, nicht in der regional angeforderten Etappenzahl.

Neues Artefakt: **`company-tours/regional-projection.json`** mit Methode,
Original-/Regionalanzahl, Klassifikation, Schnittanteilen, regionalen Etappen und
Eintrittsreferenzen. Es entsteht bereits bei der gemeinsamen Nachfrage auch ohne
SUMO und ist im Manifest verlinkt. Beim nächsten Lauf wird ein alter Bericht
invalidiert. Die vollständige Eingabe steht weiterhin in `company-tours/input.json`
und `demand/traffic-demand.json`.

**Bedienung:** Originalorte einschließlich externer Start-/End- und Haltorte
zuordnen, Freigabe der Näherung setzen, Kontext speichern und prüfen, danach zur
Nachfrage hinzufügen. Abwahl der Freigabe sperrt externe Orte wieder und verlangt
erneute Prüfung. Die übrigen Nachfragemengen und der Personenverkehr werden nicht
verändert.

**Verifiziert:** Geometrie-/Zeit-/Default-/Snapshotprüfungen in
`CompanyTourRegionTest`; echte native Ausführung und Wiederholung in
`CompanyTourIntegrationTest`. [Befund und Grenzen](05-validierung.md#regionale-unternehmenstouren-mit-externen-orten-2026-10-23).


## 35. Netzbasierte regionale Touren — ✅ UMGESETZT (2026-10-23, experimentell)

Zusätzlich zur regionalen Freigabe kann der gespeicherte Tourenkontext
`networkProjectionRequested=true` enthalten. Der Assistent bietet dafür
**Grenzübergänge auf zulässigen Straßenrouten bestimmen (experimentell)** an.
Standard und Altimporte bleiben `false`; das Abschalten der regionalen Freigabe
löscht auch die Netzoption. Bestehende Kontextrevisionen behalten ohne neue Option
ihren bisherigen Hashaufbau.

Die vollständigen Originaletappen bleiben bereits vor dem Export in der gemeinsamen
Nachfrage. Die neue Option ersetzt ausschließlich die regionale Projektion im
SUMO-Adapter. Vor dem Netzaufbau lautet der Projektionsstatus `pending-network`;
es werden dabei keine vermeintlich netzgeprüften Luftlinienetappen ausgegeben.

`CompanyTourNetwork` routet alle Original-OD-Paare mit dem vorhandenen nativen
`duarouter` auf dem tatsächlich erzeugten gerichteten SUMO-Netz. Endpunkte werden
klassenabhängig höchstens 300 m an zulässige Straßen zugeordnet. Erforderlich ist
ein lokales Netz, das auch die externen Originalorte und Verbindungen abdeckt;
bei Bedarf ist ein größeres OSM-Netz über `synthesis.overpass.road-network-file`
bereitzustellen. Die Funktion beschafft keine zusätzlichen Kundenkoordinaten.
Fehlende Abdeckung oder Verbindungen führen zu einem Fehlerbericht und stoppen
die Ausführung; es gibt keinen stillen Wechsel auf die Luftlinienprojektion.

Die gerichtete Straßenpolylinie wird am bestätigten Regionsrechteck geschnitten.
Jeder getrennte Gebietsbesuch bleibt erhalten, auch wenn die Gerade zwischen den
Originalorten das Gebiet nicht schneidet. Die Regionaletappen erhalten die
zugehörige gerichtete Straßenfolge. Diese wird auch beim nativen Zusammenführen
zu Fahrzeugketten vorgeschrieben und anschließend auf unveränderte Reihenfolge
geprüft. Externe Zwischenräume erzeugen getrennte Besuche desselben physischen
Fahrzeugs. Kundenbedienung und Fenster gehören nur zum ursprünglichen Zielhalt;
ein Grenzaustritt erhält keine externe Kundenbedienung.

**Zeitmodell:** Der freie Fahrzeitanteil der befahrenen Straßenabschnitte bestimmt,
welcher Anteil der ursprünglichen Planfahrzeit vor/nach dem Grenzübergang liegt.
Die Gewichte berücksichtigen erlaubte Straßengeschwindigkeit und Fahrzeug-Maximum.
Eintrittszeiten sind dadurch weiterhin **geschätzte Planzeiten**; sie enthalten
keine gemessenen externen Verzögerungen oder vollständig modellierten
Kreuzungswartezeiten. Die regionale Ausführung wird als `regional-complete`
bilanziert; `completedTours=0` und `outsideExecutionSimulated=false` bleiben gültig.

**Berichte:** `company-tours/network-paths.json` enthält Netz-SHA-256,
Original-Snaps, gerichtete Straßenpolylinien mit Zeitgewichten, regionale
Straßenfolgen und Fehlerstatus. `regional-projection.json` enthält Grenzpunkte,
Planzeiten und alle Gebietsbesuche mit Methode
`directed-network-edge-free-flow-plan-time-scaling`. Der Manifestverweis
`companyTours.networkPaths` erschließt den zusätzlichen Bericht.

Die Netzprüfung verwendet die im SUMO-Netz vorhandenen Klassenberechtigungen.
Sie zertifiziert keine individuellen Gewichte, Abmessungen oder unbekannte
Zugangsregeln (`individualDimensionsCertified=false`). Bestehende OSM-/SEVAS-
Profilprüfungen bleiben eigenständige Prüfungen. Eine interaktive Netzvorschau vor
der Generierung und manuelle Gateway-Overrides sind noch nicht umgesetzt;
Originalorte, Auswahl und Planzeiten können im vorhandenen Kontext korrigiert
und anschließend erneut geprüft werden.

**Abnahme:** Native Integration mit gekrümmter Straßenroute, die das Gebiet trotz
außen verlaufender Verbindungsgerade zweimal durchfährt; vollständige Original-
nachfrage, feste regionale Straßenfolge, ein physisches Fahrzeug, Wiederholung
mit gleichem Projektionsbericht. Fehlende Netzabdeckung nach einem erfolgreichen
Lauf erzeugt `failed` und `not-executed`. Vollständige Backend-Suite: 365 Tests,
keine Fehler, vier bestehende optionale Skips. Frontend: 83 Tests und Build grün.
[Befund](05-validierung.md#netzbasierte-unternehmenstouren-und-zurbrüggen-import-2026-10-23).

## 36. Zurbrüggen: reale Importprüfung — ✅ IMPORT / ❌ AUSFÜHRUNGSABNAHME OFFEN (2026-10-23)

Die bereitgestellte CSV vom 29.09.2026 wurde direkt erneut gelesen. Ihr SHA-256
`454746df8eb9df41c30cac6d5ddac19e5d9d9b6272f8328af05450ffff5c6bca`
entspricht der früheren Fassung. Der aktuelle Produktionsimport und JSON-Export
verarbeiten **222/222 Datensätze**, **41 Tourgruppen** und **187 gruppierte
Haltkandidaten** ohne verworfene Zeile. Alle 222 Auftragszeilen tragen jeweils
genau eine originale Quellzeilenreferenz. 13 Spalten sind fachlich zugeordnet;
28 weitere Spalten bleiben im geschützten Upload erhalten. Drei Halte liegen
außerhalb der angegebenen Kundenfenster, ein Halt besitzt unterschiedliche
Fenster je Auftrag. Das Mapping benötigt bei dieser CSV keinen LLM-Aufruf.

Die vollständige reale Ausführung bleibt offen: gewünschte Modellregion,
bestätigte Depotorte und Start-/Rückkehrzeiten, konkrete Tour-Fahrzeug-Zuordnung,
Katalogprofile sowie bestätigte Haltkoordinaten/lokale Ortstabelle fehlen.
Haltgruppierung, Reihenfolge und Fensterregel benötigen eine fachliche Bestätigung.
Es werden dafür keine Fahrzeuge, Depots oder Kundenkoordinaten erfunden.
Aktivierung ohne diese Angaben liefert HTTP 400. Die verwendete isolierte
synthetische Prüfregion und ihr Testfahrzeug sind keine Unternehmensdaten.

Eine lokale JSON-Kontextvorlage mit den tatsächlichen Tour-/Halt-IDs, leeren
Betriebsfeldern und ausgeschalteten Bestätigungen sowie der Abnahmebericht liegen
unter `.runtime/company-network-2026-10-23/real-source/`. Private Quellzeilen,
Adressdaten und Importe gehören nicht in versionierte Testfixtures.


## 37. Fehlende Betriebsangaben als Rückfragen — ✅ UMGESETZT (2026-10-24)

Nach dem bestätigten Mapping zeigt der Importassistent **Was fehlt noch für diese
Touren?**. Die Fragen folgen dem aktuell bearbeiteten Kontext und zählen nur
noch fehlende Angaben der ausgewählten Touren. Abgefragt werden Unternehmen,
Start-/Enddepots, zugehörige Koordinaten, konkrete Fahrzeuge, Depotabfahrts- und
Rückkehrzeiten, Haltkoordinaten, Datumsverschiebung und fachliche Bestätigungen.
Die Liste aktualisiert sich sofort; eine beantwortete Depotfrage entfernt nicht
die noch offenen Fragen nach Fahrzeugen oder Koordinaten. Ausgeschlossene Touren
werden nicht als fehlende Betriebsangaben gezählt. Bei leerer Auswahl wird nach
den zu übernehmenden Touren gefragt.

Antworten werden in den vorhandenen Formularfeldern erfasst. Eine gemeinsame
Depotadresse lässt sich einmal auf Start und Ende aller ausgewählten Touren
übernehmen; tourweise unterschiedliche Start-/Endadressen sind ebenfalls möglich.
Das additive optionale Kontextfeld `depotAddresses` ordnet diese Angaben anhand
der stabilen Tour-ID zu. Adressänderungen verwerfen die bisherige zugehörige
Position und die Ortsbestätigung. Neue Koordinaten werden nicht erfunden.
Eine gespeicherte Adresse beantwortet die Depotfrage, lässt die Frage nach der
Ortszuordnung jedoch bis zu deren Ergänzung offen.

Die Rückfragen funktionieren ohne LLM und ergänzen dessen optionales Quellmapping.
Ein freies LLM-Antwortgespräch oder automatische Adressgeokodierung wird dadurch
noch nicht bereitgestellt. Die Backend-Prüfung bleibt maßgeblich für die
Aktivierung; eine leere Fragenliste ersetzt keine fachliche Prüfung oder
Netzprüfung. Unvollständige Antworten lassen sich speichern und später fortsetzen.

**Kompatibilität:** Ohne Adressantworten bleiben bestehende Ausführungsrevisionen
unverändert. Adressantworten sind Teil der Ausführungsrevision; unbekannte Tour-IDs
werden abgewiesen. Es gibt keine neue Generierungsstufe, externe Abhängigkeit
oder Aktivierung ohne vollständige Koordinaten und Fahrzeugzuordnung.

**Zurbrüggen:** Der Nutzer hat am 24.10.2026 eine gemeinsame Start-/Endadresse für
alle 41 Tourgruppen bestätigt. Diese Antwort ist im lokalen Abnahmekontext
übernommen. Depotkoordinaten und -zeiten, konkrete Fahrzeuge, Haltkoordinaten,
gewünschte reale Region sowie fachliche Bestätigungen bleiben offen. Der reale
Lauf ist weiterhin nicht freigegeben. Private Adresse und Kontext liegen im
ignorierten Verzeichnis `.runtime/company-import-questions-2026-10-24/`.

[Validierung und Grenzen](05-validierung.md#rückfragen-und-depotantworten-im-tourenimport-2026-10-24).


## 38. Lokale Ortsvorschläge und regionaler Modellpilot — ✅ UMGESETZT (2026-10-24)

Der Import kann für gespeicherte, noch unverortete Adressen Vorschläge von einem
bewusst konfigurierten lokalen Nominatim-kompatiblen Dienst abrufen. Einzelne
Kandidaten werden ausdrücklich gewählt; eine zusätzliche Sammelaktion übernimmt
nur eindeutige Vorschläge. Mehrdeutige Treffer bleiben zur Auswahl stehen.
Depotentscheidungen gelten für ausgewählte Touren mit derselben Depotadresse.
Adressänderungen verhindern die Übernahme veralteter Vorschläge. Anschließend
werden Kontext und Ortsbestätigung weiterhin ausdrücklich gespeichert/geprüft.

`company-tour-import.geocoding-enabled` bleibt standardmäßig `false`; weitere
Properties sind `geocoding-endpoint` (Default `http://127.0.0.1:8585`),
`geocoding-timeout-seconds` (3) und `geocoding-max-batch` (10, maximal 10).
Alle Properties stehen unter `synthesis.company-tour-import`.
Nur Loopback-Endpunkte sind zulässig. Öffentliche Geocoder werden nicht angesprochen.
Der Assistent zeigt Region, fehlende Fahrzeuge/Depotzeiten und externe bzw.
unverortete Punkte. Diese Vorprüfung behauptet keine bereits geprüfte Netzabdeckung.

Für lokale NRW-Daten bereitet `scripts/prepare-nrw-tour-addresses.py` aus dem
amtlichen Gebäudeadressbestand einen Kandidatenindex vor. Es vergleicht Gemeinde,
Straße und Hausnummer einschließlich Zusatz exakt nach Normalisierung; keine
unscharfen erfundenen Treffer. Nur Qualität A wird übernommen. Postleitzahlen und
LKW-Zufahrten sind damit nicht verifiziert. `scripts/serve-local-tour-geocoder.py`
stellt den Index auf Loopback bereit, ohne ausgehende Anfragen oder Adresslogging.
Vorbereitung benötigt `pyproj`; der Indexserver benötigt nur Python-Standardbibliothek.
Diese Werkzeuge sind optionale lokale Hilfen außerhalb der Generierungspipeline.

Auf Nutzerwunsch wurden Fahrzeugzuordnung, Depotzeiten und Region für einen ersten
Zurbrüggen-Modellpiloten selbst gewählt und als Annahmen gespeichert. Die gemeinsame
Depotadresse stammt vom Nutzer; Fahrzeuge und Zeitpuffer sind keine beobachteten
Betriebsdaten. Der Pilot führt eine ausgewählte Tour mit externen Kundenhalten
zusätzlich zu privaten Kontrollfahrten aus. Gesamtbestand und externe Bedienung
sind damit noch nicht vollständig abgenommen.
[Messwerte, Annahmen und Grenzen](05-validierung.md#lokale-geokodierung-und-regionaler-zurbrüggen-pilot-2026-10-24).


## 39. Fortsetzbare Ortssuche und ergänzende lokale Quellen — ✅ UMGESETZT (2026-10-26)

Die Adresssuche geht nach leeren oder mehrdeutigen Antworten mit den nächsten
Adresszielen weiter. Ergebnisse bleiben während derselben Importsitzung auch nach
Kontextspeicherung erhalten. Adressänderungen werden anhand des aktuellen Suchtexts
geprüft; Import-/Szenariowechsel setzt den Suchfortschritt zurück. Der Fortschritt
ist sitzungsbezogen, keine dauerhaft gespeicherte Warteschlange.

**Cache:** `POST .../{hash}/geocode` akzeptiert weiterhin `{}` und zusätzlich
`targets` sowie `refresh` (Default `false`). Die Schaltfläche zum erneuten Prüfen
setzt die Suche für noch unverortete Ziele zurück und fordert neue lokale Antworten
an. Dabei wird auch ein alter leerer Treffer ersetzt. Identische Suchtexte werden
innerhalb eines Pakets weiterhin nur einmal beim Dienst abgefragt. Die fachliche
Ortsauswahl bleibt ausdrücklich erforderlich; Neusuche aktiviert keine Touren.

**Amtlicher Abgleich:** Der NRW-Adapter normalisiert Leerzeichen, Bindestriche,
Umlaute und übliche Straßenabkürzungen. Hausnummern und Spannen bleiben getrennt;
70–72 wird nicht zu 7072. Gemeinde-Aliasse erfordern eine ausdrücklich angegebene
lokale JSON-Tabelle (`--municipality-aliases`). Standard bleibt Qualität A;
`--qualities B` liefert ausdrücklich angeforderte Flurstückspunkte als entsprechend
gekennzeichnete Modellvorschläge, keine gesicherten Gebäude- oder Zufahrtsorte.

**OSM-Ergänzung:** `scripts/prepare-osm-tour-addresses.py` ergänzt fehlende Vorschläge
lokal aus vollständigen OSM-Abzügen. Standard: Straße, Hausnummer und Postleitzahl
stimmen nach Formatnormalisierung überein. Vorhandene Vorschläge werden erhalten;
mehrere verschiedene Punkte bleiben mehrdeutig. Adressknoten und Repräsentanten
geschlossener Gebäude werden unterschieden. Ein Punkt im Gebäude belegt keine
LKW-Einfahrt. Optional lässt `--allow-city-without-postcode` bei ausschließlich
fehlender OSM-Postleitzahl einen exakten Gemeindeabgleich zu; widersprechende oder
ungültige Postleitzahlen werden dadurch nicht akzeptiert. Quell-Ortszusätze können
über die geprüfte Aliasdatei zugeordnet werden. `--corrections` verlangt für jede
bewusste Schreibkorrektur eine Begründung; diese bleibt als Modellannahme sichtbar.
Keine unscharfe automatische Reparatur und keine öffentliche Adressanfrage.
Die Vorbereitung benötigt optional pyosmium und shapely; sie ist keine neue
Generierungsstufe. Quelldateien und Hashes werden im lokalen Bericht festgehalten.

**Bedienprobe:** Am isolierten Ein-Tour-Prüfszenario wurden Neusuche, bewusste
Übernahme, Ortsbestätigung, Speicherung und zusätzliche Nachfrageauswahl erfolgreich
im Browser ausgeführt. Die Auswahl ist serverseitig gespeichert. Der Blocker der
Bedienprobe vom 24.10. besteht für diesen Stand nicht mehr.
[Bestandsprüfung und verbleibende Datengrenzen](05-validierung.md#tourenbestand-und-fortsetzbare-ortssuche-2026-10-26).

## 39. Regionaler Hintergrundverkehr und vereinfachte Ortszuordnung (2026-10-27)

Die bestehende gemeinsame Nachfrage nimmt Hintergrund-Lkw und Unternehmenstouren
additiv auf. Die räumliche Vorbereitung ist jetzt automatisiert; öffentliche
Mautdaten werden weiterhin bewusst als lokale Quelldateien eingelesen. Ein
Live-Abruf bei jeder Szenariogenerierung ist damit nicht verbunden.
Überschneidungen mit Unternehmensplänen werden entsprechend der Modellvorgabe
ignoriert. Personenwege und Unternehmensmengen werden nicht heruntergerechnet.

### Hintergrund- und Durchgangsbeziehungen vorbereiten

`prepare-toll-regional-od.py` übernimmt vollständige monatliche Toll-Collect-
GeoJSON-Abfragen. Der vorhandene Normalisierer prüft Monat und Landesaggregat,
entfernt doppelte Abfrageperspektiven und erhält die veröffentlichten Mengen.
Gemeinde-Polygonrechtecke liefern gekennzeichnete räumliche Repräsentanten.
Fahrzeugklasse `truck`, gleichmäßige Tagesverteilung und Kalender-Tagesmittel
sind Modellannahmen; die Quelle liefert damit keine konkreten Betriebsstandorte,
Tagesfahrpläne oder beobachteten Routen.

```bash
python3 scripts/prepare-toll-regional-od.py source/od-*.geojson \
  --month 2026-09 --output work/regional-od.json
python3 scripts/prepare-regional-freight.py \
  --od work/regional-od.json --network artifacts/sumo/net.net.xml \
  --geometry artifacts/sumo/freight-network.geojson \
  --region 7.7561 51.5941 7.8061 51.6341 --output work/background
```

Die zweite Vorbereitung akzeptiert auch andere räumliche OD-Quellen:

```json
{
  "source": "Bezeichnung der Quelle und Mengenannahme",
  "referenceDate": "2026-09-01",
  "periodDays": 30,
  "relations": [{
    "id": "relation1", "from": [7.7, 51.61], "to": [7.9, 51.62],
    "trips": 300, "vehicleClass": "truck", "beginHour": 0, "endHour": 24
  }]
}
```

Sie schneidet die geraden OD-Verbindungen mit dem Gebietsrechteck und klassifiziert
Binnen-, Quell-, Ziel- und Durchgangsverkehr. Danach wird das nächstgelegene
klassenverträgliche, auf Kantenebene erreichbare gerichtete Straßenpaar gewählt.
Bei gleichem Suchergebnis entscheidet die Kanten-ID deterministisch; es gibt keine
willkürliche Auswahl aus wenigen Kandidaten. Kantenkoordinaten bleiben in der
Region. Native Fahrstreifen-/Abbiegeprüfung und tatsächliche Ausführung bleiben
Aufgabe des bestehenden Routers und der Ausführungsbilanz.

Ergebnis `freight-relations.csv` entspricht dem vorhandenen gerichteten OD-Upload
unter `POST /api/scenarios/{id}/freight` und wird anschließend von
`FreightDemandGenerator` in den gemeinsamen Nachfragevertrag übernommen.
`original-od.json` und `report.json` erhalten Originalorte, Ausgangsmengen,
Quellenhashes, räumliche Verschiebungen und nicht dargestellte Beziehungen.
Größte Reste erhalten die gerundete Tagesgesamtsumme. Es erfolgt kein Zuschlag
für Leerfahrten, keine versteckte Hochrechnung und kein Abzug von Unternehmenstouren.

**Abdeckung:** Eine Gerade kann reale Umwege oder Gebietsdurchfahrten verfehlen.
Gleiche Gemeinde-Repräsentanten sind keine zwei unterschiedlichen lokalen Orte;
solche OD-Mengen bleiben im Bericht statt eine erfundene Binnenroute zu erhalten.
Kommunale Mautabfragen enthalten nicht automatisch alle Transitbeziehungen mit
beiden Enden außerhalb der abgefragten Kommunen. Auch unbemautete Verkehre fehlen.
Der Vorbereiter erzeugt deshalb ausdrücklich keine behauptete Vollerhebung.

### Fehlende Adressen und Zufahrten vereinfachen

Die lokale Ortstabellenzuordnung verwendet bei fehlender exakter Hausnummer die
nächste bekannte Hausnummer derselben Straße, Postleitzahl und Gemeinde.
Die Herkunft enthält `approximate-nearest-house-number`; exakte Treffer und die
Quelladresse bleiben erhalten. Eine Näherung ist in der Importansicht als Herkunft
sichtbar und ersetzt keine unbekannten Angaben durch behauptete Genauigkeit.

`scripts/approximate-tour-locations.py` ergänzt fehlende lokale Kandidaten aus dem
bereits verwendeten OSM-PBF-Bestand. Existiert keine passende Straße, dient ein
bekannter Adressknoten nahe dem Schwerpunkt der Adressknoten derselben Postleitzahl
als gekennzeichneter Ortsrepräsentant. Dafür ist die vorhandene optionale pyosmium-
Laufzeit nötig; Adressen werden nicht an einen öffentlichen Dienst übertragen.

Bei unaufgelösten oder ausdrücklich mit `approximate-nearest` gekennzeichneten
Punkten verwendet die Unternehmens-Netzprojektion das nächstgelegene erreichbare
Lkw-Kantenpaar innerhalb von fünf Kilometern. Die gewöhnliche Unternehmens-
Endpunktzuordnung unterstützt denselben Ersatz, wenn die genaue Zuordnung fehlt
oder nicht erreichbar ist; ihre Berichte kennzeichnen `approximateNearest`. Sie minimiert die Summe der beiden Verschiebungen und erhält
die tatsächlichen Klassenrestriktionen. Die Herkunft sowie beide Verschiebungen
stehen im Netzpfadbericht. Die bisherige genaue Zuordnung anderer Punkte bleibt
bestehen, solange sie erreichbar ist. Pkw-Zuordnungen und ausdrücklich gepinnte
Kanten werden nicht umgelegt. Detaillierte Gebäudezufahrten werden hier nicht simuliert.

### Hintergrundmengen an Zählungen anpassen

`scripts/calibrate-regional-freight.py` passt ausschließlich Hintergrundmengen
begrenzt an native Routendurchfahrten und Beobachtungen an. Unternehmensbeiträge
sind feste additive Zählbeiträge. Die Eingabe enthält das SHA-256 des tatsächlichen
Netzes, Monat, Tagestyp, Fahrzeugklasse und getrennte Kalibrierungs-/Prüfzähler.
Abweichende Zeiträume, unbekannte Relationen, unpassende Klassen und fehlende
Prüfzähler werden abgelehnt. Standardfaktoren liegen zwischen 0,25 und 4;
unbeobachtete Relationen behalten ihre Ausgangsmenge.

```bash
python3 scripts/calibrate-regional-freight.py \
  --relations work/background/freight-relations.csv \
  --incidence work/native-counter-incidence.json \
  --network artifacts/sumo/net.net.xml --output work/fitted-background
```

Der Bericht enthält Vorher-/Nachherfehler auch der zurückgehaltenen Zähler.
Ein linearer Mengenfit gilt weiterhin nicht als empirische Abnahme: Neue Mengen
müssen erneut nativ ausgeführt und die Prüfzähler erneut ausgewertet werden.
`empiricallyValidated` bleibt deshalb im Fitbericht `false`.
Bei fehlenden kompatiblen Beobachtungen wird kein Kalibrierwert erzeugt.
Aktuelle Datenprüfung und reale Ausführung: [Validierungsbefund](05-validierung.md#regionaler-hintergrundverkehr-und-näherungen-2026-10-27).
