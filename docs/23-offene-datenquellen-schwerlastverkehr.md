# Offene Datenquellen für Verkehrs- und Logistiknachfrage, Schwerpunkt Schwerlastverkehr

Stand: 2026-11-04. Ergänzt [docs/21](21-gueterverkehr-konzept-und-datenquellen.md), Abschnitt 3
(Recherche vom 10.10.2026) um neue oder dort nur gestreifte Quellen und ordnet sie den
Eingabeverträgen der Pipeline zu. Recherchiert wurde per Websuche aus einer Cloud-Sandbox ohne
Download-Möglichkeit: Angaben zu Lizenz, Dateiformat und Spalten sind als **„zu prüfen“**
markiert, wo sie nicht aus Primärquellen belegt sind. Die lokale Sitzung kann die Dateien laden
und die offenen Punkte schließen.

## 1. Was die Pipeline heute braucht (Eingabeverträge)

| Bedarf | Vertrag im Repo | Status |
|---|---|---|
| Fahrtenbeziehungen schwerer Fahrzeuge (Quelle → Ziel, Fahrzeugklasse, Tag) | Upload `freight.relations`, Quellenplan-Aspekt `freight.vehicleOd` (`dataPlan`) | Vertrag implementiert; Daten nur per Pilot (Maut-OD Dortmund/Unna, docs/21 Abschnitt 12) |
| Kleinräumige Verteilung von Kreis-/Gemeindewerten auf Standorte | Gewichte je Zone (`attractivities`, Arbeitsplätze `opportunities.work`) | Arbeitsplätze vorhanden; Branchengewichte und Gewerbeflächen fehlen |
| Fahrzeugflotte und Klassen | KBA-Bestände (`useRegionalFleet`), `freight-vehicle-profiles-v1.json` | implementiert |
| Fahrverbote und Vorrangrouten | SEVAS (NRW), OSM `maxweight`/`hgv` | implementiert (NRW), OSM bundesweit |
| Zeitprofile und Kalibrierziele | `freight.relations` (Abfahrtsprofil), KPI-Vergleich | Abfahrtsprofile als Annahme; keine Zählstellen-Kalibrierung |
| Außen- und Transitverkehr | Außenanker der Zonierung | ohne Datengrundlage |

Die größte Lücke ist also nicht ein fehlender Vertrag, sondern die **regionale Datengrundlage und
Kalibrierung** (docs/21, Kopfzeile). Die folgenden Quellen schließen genau diese Lücke.

## 2. Fahrtenbeziehungen und Mengen (Erzeugung)

### 2.1 Lkw-Verkehrsportal (Toll Collect/BALM): Start-Ziel auf Gemeindeebene

Seit der dritten Ausbaustufe (Juni 2026) zeigt das Portal die Zahl der **Mautfahrten zwischen
Start- und Zielgemeinden**, Datenbasis sind Ein- und Ausfahrten in das mautpflichtige Netz;
alle Ansichten lassen sich als CSV herunterladen, eine Anmeldung ist nicht nötig. Zugang über
die Mobilithek, BALM und Toll Collect; der GovData-Eintrag wurde am 17.06.2026 aktualisiert.
**Verwendung:** direkte Quelle für `freight.relations` (bereits als Pilot genutzt). **Grenzen:**
nur mautpflichtige Fahrzeuge ab 7,5 t, Start/Ziel ist die Ein-/Ausfahrgemeinde, nicht der
Betrieb; Vor- und Nachlauf auf dem untergeordneten Netz fehlt. **Zu prüfen:** Spaltenbeschreibung
des CSV-Exports, ob ein automatisierbarer Abruf (API) existiert, Lizenztext des aktuellen Eintrags.
Quellen: [Toll Collect Produktseite](https://company.toll-collect.de/de/produkte-loesungen/daten-geoinformationssysteme/lkw-verkehrsportal/),
[Pressemitteilung dritte Ausbaustufe](https://company.toll-collect.de/de/presse/pressemitteilungen/dritte-ausbaustufe-des-lkw-verkehrsportals-veroeffentlicht-neue-ansicht-macht-verkehrsstroeme-sichtbar/),
[GovData-Eintrag](https://www.govdata.de/suche/daten/app-lkw-befahrungen),
[BALM-Pressemitteilung zum Start](https://www.balm.bund.de/SharedDocs/Pressemitteilungen/DE/2025/2025_06_02_PM_21-25_Start_des_Lkw-Verkehrsportals.html).

### 2.2 Verkehrsprognose 2040: Güterverkehrs-Verflechtungsmatrizen 2019 und 2040

Das BMV verlinkt in der Mobilithek die Gütermatrizen für das Basisjahr 2019 und die
Basisprognose 2040 auf **NUTS-3-Ebene** sowie das Verkehrszellen-Angebot (1 608 Zellen in
Deutschland, 170 im Ausland); die Matrizen sind nach den **Güterabteilungen der NST 2007**
gegliedert. **Verwendung:** Fern-, Außen- und Transitbeziehungen, die das Mautportal nicht
abdeckt; Gewichtung nach Gütergruppe für Fahrzeugklassen und Beladung. Umrechnung Tonnen →
Fahrten über Beladungs- und Leerfahrtenfaktoren aus der KBA-Güterkraftverkehrsstatistik (docs/21,
Abschnitt 3.4). **Zu prüfen:** Dateiformat und Lizenz der Mobilithek-Angebote, ob die
Zellgeometrien als Shapefile/GeoPackage vorliegen. Ein weiterer Eintrag `astra_raw_vp_2040`
(THG-Projektionen 2025, opencode.de) beschreibt dieselben Zellen und ist als zweiter Zugang zu
prüfen. Quellen: [BMV Verkehrsprognose 2040](https://www.bmv.de/SharedDocs/DE/Artikel/G/verkehrsprognose-2040.html),
[Mobilithek VP2040](https://mobilithek.info/offers/859851230935941120),
[Gütermatrix 2019 NUTS3](https://mobilithek.info/offers/859846121350729728),
[VP-Verkehrszellen](https://mobilithek.info/offers/880062743587840000),
[opencode.de Datensatz astra_raw_vp_2040](https://thg-projektionen2025-daten-modell-dokumentation-788cd5.usercontent.opencode.de/Datensatz/astra_raw_vp_2040).

### 2.3 Eurostat, ESPON und ETISplus für Außen- und Transitbeziehungen

Eurostat führt den Straßengüterverkehr nach Be- und Entladeregion (`road_go_ta_rl`,
`road_go_ta_ru`, NUTS-2; national nach NUTS-3 und Gütergruppe `road_go_na_rl3g`) in Tonnen,
Tonnenkilometern, Fahrzeugkilometern und Fahrten; die Zuordnung erfolgt nach Zulassungsland der
Fahrzeuge. ESPON bietet eine 329 × 329-OD-Matrix je Verkehrsträger für 2010–2018 (Tonnen).
ETISplus (2010) liefert ältere, aber kleinräumige OD-Matrizen; eine abgeleitete, frei
verfügbare Lkw-Fluss-Schätzung für 1 675 Regionen liegt auf Mendeley Data. **Verwendung:**
Außenanker und Transit durch die Region, Plausibilisierung der VP2040-Werte. **Zu prüfen:**
aktuelle NUTS-Ebene je Tabelle, Erreichbarkeit des ETISplus-FTP. Quellen:
[Eurostat Metadaten road_go](https://ec.europa.eu/eurostat/cache/metadata/en/road_go_esms.htm),
[Verordnung (EU) 202/2010](https://eur-lex.europa.eu/eli/reg/2010/202/oj),
[ESPON OD-Matrix](https://database.espon.eu/indicator-other-data/2648),
[Mendeley: synthetische Lkw-Flüsse aus ETISplus](https://data.mendeley.com/datasets/py2zkrb65h).

### 2.4 Umschlagpunkte als Erzeuger: KV-Terminals und Häfen

Terminals und Häfen erzeugen schweren Verkehr unabhängig von Beschäftigten. Die SGKV
Intermodal Map führt nach eigener Angabe (September 2026) über 3 000 Terminals und 6 500
wöchentliche Zugabfahrten in Europa; die Bundesnetzagentur zählt für 2023 rund 170 KV-Terminals
in Deutschland (Bericht mit Standortübersicht). Destatis veröffentlicht den Güterumschlag der
Binnenhäfen in GENESIS (Statistik 46321). **Verwendung:** Punktquellen und -senken je Zone mit
Mengen aus Umschlagstatistik; Kordonanteile für Außenanker. **Zu prüfen:** Lizenz der SGKV-Karte
(vermutlich keine offene Datenlizenz; dann nur manuelle Standortliste), Hafen-Mengen je Hafen in
GENESIS. Quellen: [SGKV Intermodal Map (Meldung)](https://www.verkehrsrundschau.de/nachrichten/transport-logistik/mehr-transparenz-3898564),
[BNetzA Bericht KV-Terminals](https://www.bundesnetzagentur.de/DE/Fachthemen/Eisenbahnen/Veroeffentlichungen/Berichte/_DL/BerichtGueterterminalsKombiniertenVerkehr.pdf),
[Destatis GENESIS 46321](https://www-genesis.destatis.de/datenbank/online/statistic/46321/details).

## 3. Kleinräumige Verteilung (Gewichte je Zone)

### 3.1 Sozialversicherungspflichtig Beschäftigte am Arbeitsort nach Wirtschaftsbereichen, Gemeinden

Die Regionalstatistik (Statistik 13111) und die Landesportale führen Beschäftigte am Arbeitsort
nach Wirtschaftsbereichen je Gemeinde; Open.NRW listet z. B. „13111-20d/20i: SvB (Arbeitsort)
nach Wirtschaftsbereichen (4) der WZ 2008 – Gemeinden – Stichtag“. Auf Bundeslandebene gibt es
GENESIS 13111-0006 nach Wirtschaftsabschnitten. Die BA selbst veröffentlicht die
Beschäftigungsstatistik nach Gemeinden und Branchen (Zitat mit Namensnennung frei, gewerbliche
Weitergabe genehmigungspflichtig). **Verwendung:** Erzeugungsraten je Branche
(WIVER-/Gebietstypen-Logik: Fahrten je Beschäftigtem in Industrie, Handel, Logistik, Bau) als
Gewichte für die Verteilung der Kreis-/Gemeindemengen aus 2.1/2.2 auf Zonen; ersetzt die
heutige Gleichverteilung über Arbeitsplätze. **Zu prüfen:** Geheimhaltungslücken bei kleinen
Gemeinden (nur 4 Wirtschaftsbereiche), GENESIS-Webservice-Zugang (kostenlose Registrierung).
Quellen: [Open.NRW 13111-20d](https://ckan.open.nrw.de/tl/dataset/sozialversicherungspflichtig-beschaftigte-arbeitsort-nachwirtschaftsbereichen-4-der-wz-2008-und-2),
[Statistikportal 13111-0006](https://www.statistikportal.de/de/sozialversicherungspflichtig-beschaeftigte-am-arbeitsort-nach-wirtschaftsbereichen),
[BA Beschäftigungsstatistik](https://statistik.arbeitsagentur.de/DE/Navigation/Statistiken/Fachstatistiken/Beschaeftigung/Beschaeftigte/Beschaeftigte-Nav.html).

### 3.2 Gewerbe- und Industrieflächen

Bundesweit einheitlich: ATKIS Basis-DLM (Objektart Industrie- und Gewerbefläche mit Funktion);
die Lizenz ist je Bundesland verschieden (Hessen DL-DE/Zero, Sachsen-Anhalt DL-DE/BY, NRW als
Open-Data-Download), beim BKG im Open-Data-Katalog zu prüfen. Europaweit: Copernicus Urban Atlas
2018 (revidierte Fassung 2026-01-31, FlatGeobuf/GeoPackage, Download nach kostenloser Anmeldung,
nur funktionale Stadtregionen über 50 000 Einwohner, Klassen „industrial, commercial, public,
military and private units“ laut Mapping Guide). OSM `landuse=industrial` ist schon im Einsatz.
**Verwendung:** Flächengewichte für Zonen ohne Beschäftigtendaten, Plausibilisierung der
Branchenverteilung. Quellen: [BKG Open-Data-Katalog](https://bkg.bund.de/SharedDocs/Downloads/BKG/EN/Downloads-EN-Flyer/BKG-Product-Catalog-Open-Data-EN.pdf),
[Open.NRW Basis-DLM](https://ckan.open.nrw.de/pl/dataset/digitales-basis-landschaftsmodell-sax),
[EEA Urban Atlas 2018](https://sdi.eea.europa.eu/catalogue/srv/api/records/fb4dffa1-6ceb-4cc0-8372-1ed354c285e6),
[Copernicus Urban Atlas](https://hds.hub.copernicus.eu/datasets/hh-clms-urban-atlas).

## 4. Zeitprofile, Kalibrierung und Validierung

### 4.1 BASt-Dauerzählstellen (Stundenwerte, Schwerverkehr)

Die Rohdaten der automatischen Dauerzählstellen an Bundesfernstraßen stehen als CSV je Jahr
bereit (Kodierung ISO-8859-1, Semikolon; Stundenwerte je Richtung und Fahrzeugart, Metadaten je
Zählstelle als monatliche CSV; zusätzlich plausibilisierte Endwerte). **Verwendung:**
Tagesganglinien des Schwerverkehrs für die Abfahrtsprofile in `freight.relations`, Wochen- und
Saisonfaktoren, und unabhängige Kalibrierziele je Querschnitt (Vergleich mit SUMO-`edgeData`).
MobiData BW liefert dasselbe für Baden-Württemberg ab 2016 (docs/21). Quellen:
[Datensatzbeschreibung (GovData)](https://www.govdata.de/suche/daten/automatische-dauerzahlstellen-rohdaten?ids=0051a14c-bd13-433c-96f4-1fd7731c8b8a),
[Aufbereitete Zählstellendaten (Mobilithek)](https://mobilithek.info/offers/573280210227470336),
[Anwendungsbaustein mit Leseanleitung](https://bausteine-der-datenanalyse.github.io/a-verkehrsmengen/book/Anwendungsbaustein-Analyse-von-Verkehrsmengen.pdf).

### 4.2 Straßenverkehrszählung 2021 (SVZ) und Landes-Verkehrsmengenkarten

Die SVZ 2021 liefert DTV und Schwerverkehrsanteile für alle Bundesfernstraßen (BASt-Bericht
V 417, Methodik V 416). Maschinenlesbare Werte je Zählabschnitt kommen von den Ländern:
Baden-Württemberg (Excel mit `DTVwSV` je Abschnitt, MobiData BW), Bayern (BAYSIS-WFS, CC BY 4.0),
Saarland (INSPIRE-WFS mit DTV und SV), Städte wie Düsseldorf (16-h-Zählungen 6–22 Uhr mit
Schwerverkehr über 3,5 t und Klassen). Für NRW wurde in dieser Recherche **kein landesweiter
Straßen.NRW-Dienst** gefunden; der Zugang ist zu klären. **Verwendung:** flächige Kalibrierziele
für das nachgeordnete Netz, wo Dauerzählstellen fehlen. Quellen:
[BASt V 417 SVZ 2021 Ergebnisse](https://bast.opus.hbz-nrw.de/frontdoor/deliver/index/docId/3260/file/V417-Gesamtversion-BA.pdf),
[MobiData BW Legende Zeilenformat](https://mobidata-bw.de/vm/Endergebnisse_Strassenverkehrszaehlung_BW/Ergebnisse_2023_Excel/Legende_Zeilenformat_2023.pdf),
[Bayern Verkehrsdaten/BAYSIS](https://stmb.bayern.de/vum/strasse/verkehrsdaten/index.php),
[Saarland INSPIRE-Download-Dienst](https://inspire-geoportal.ec.europa.eu/srv/api/records/8d2a15c9-06e4-409a-837f-bb0a8241f3fa),
[Verkehrszähldaten Düsseldorf](https://ckan-open-nrw.nrw.de/sk/dataset/verkehrszahldaten-dusseldorf-d).

### 4.3 Maut-Fahrleistung als Saison- und Netzreferenz

BALM veröffentlicht das jährliche „Mautdaten Tabellenwerk“ (Fahrleistung aller mautpflichtigen
Fahrzeuge auf Autobahnen und Bundesstraßen), Toll Collect Halbjahresberichte „Mautnetz und
Lkw-Verkehr“ (rund 142 000 Tarifabschnitte), Destatis seit 18.06.2026 einen **täglichen**
Lkw-Maut-Fahrleistungsindex (Lkw ab vier Achsen, nur Autobahnen). **Verwendung:** Saison- und
Wochentagsfaktoren, Plausibilisierung der Gesamtmenge je Netzklasse. Quellen:
[Destatis Lkw-Maut-Fahrleistungsindex](https://www.destatis.de/DE/Themen/Branchen-Unternehmen/Industrie-Verarbeitendes-Gewerbe/Tabellen/Lkw-Maut-Fahrleistungsindex-Daten.html),
[Toll Collect Mautnetz und Lkw-Verkehr 2. Hj. 2024](https://company.toll-collect.de/static/media/dokumente/report-mautnetz-und-lkw-verkehr/mautnetz-und-lkw-verkehr-2-halbjahr-2024.pdf),
[BALM Mautverkehr kompakt 2024](https://transport-online.de/sites/default/files/public/data-news/pm-07-25-balm-mautverkehr-kompakt-jahresausgabe-2024.pdf).

## 5. Einordnung: was wirklich fehlt

| Lücke | Beste offene Quelle | Zusätzlich nötig |
|---|---|---|
| Fahrten unter 7,5 t (leichte Lkw, Transporter im Wirtschaftsverkehr) | keine offene Beobachtung (KiD 2010 nur über Clearingstelle) | Erzeugungsraten je Branche aus 3.1 als Modell, Kalibrierung an 4.2 (Klassen > 3,5 t) |
| Betrieb-zu-Betrieb-Beziehungen innerhalb einer Gemeinde | keine | Verteilung über 3.1/3.2, Routingprüfung an SEVAS/OSM |
| Beladung, Leerfahrten, Tourenbildung | KBA-Güterkraftverkehrsstatistik (Aggregate), VD-PUF (docs/21) | bleiben Annahmen mit Kennzeichnung `assumed` |
| Grenzüberschreitender Verkehr | VP2040 (170 Auslandszellen), Eurostat/ETISplus | Außenanker der Zonierung mit Mengen belegen |

## 6. Umsetzungsvorschlag in drei Schritten

1. **Quellenpaket „Schwerverkehr regional“** als Datenpaket im Quellenplan: Toll-Collect-OD der
   Region (CSV), VP2040-Matrix 2019 für die betroffenen NUTS-3-Zellen, Beschäftigte 13111 der
   Gemeinden. Importer prüfen Hash, Lizenz und Spalten (bestehender `DataImportController`).
2. **Verteilung und Fahrzeuge:** Gemeindemengen → Zonen über Branchengewichte (3.1) und
   Gewerbeflächen (3.2); Tonnen → Fahrten je NST-Abteilung über die vorhandenen
   Fahrzeugprofile; Abfahrtsprofil aus 4.1 statt pauschaler Annahme.
3. **Kalibrierung:** Querschnittsvergleich SUMO-`edgeData` gegen Dauerzählstellen (4.1) und
   SVZ/Landes-WFS (4.2) als KPI im Freight-Report (`freight/summary.json`), Zielfunktion wie in
   docs/21 Abschnitt 4, Schritt 5.

Jeder Schritt bleibt hinter dem Flag `freight.enabled` (Default aus) und kennzeichnet Herkunft
und Qualität je Quelle im Manifest, wie heute bei `heavyFreightSources`.
