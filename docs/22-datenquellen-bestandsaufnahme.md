# Datenquellen: Bestandsaufnahme und Codebelege

**Stand: 28.10.2026.** Ergänzung zum [verbindlichen Adapterkonzept](22-datenquellen-adapter-und-fallbackkonzept.md).
Diese Bestandsaufnahme beschreibt die analysierten Bestandspfade und deren regionale
Bindung. Sie ist weder ein Katalog vollständig migrierter Provider noch eine erneute
empirische Validierung der Quellen. Der neue experimentelle Importpfad steht im
[Umsetzungsstand](06-gta-integration-konzept.md#datenquellen-import-umsetzungsstand).
Die bisherigen Abschnittsnummern bleiben für die Zuordnung der Codebelege erhalten.

## 2. Warum die heutige Integration starr wirkt

### 2.1 Quellenwahl, Interpretation und Modellierung sind vermischt

`GenerationService` entscheidet selbst über Dateien, Downloads, Quellenprioritäten und
Fallbacks. `PopulationGenerator` lädt den Haushaltspool direkt aus einer fest benannten
Classpath-Ressource. `AttractivityCalculator` enthält OSM-Tagzuordnung und Modellgewichte
in derselben Klasse. `SimulationRunner` verweist auf feste Dateinamen des mitgelieferten
Wahlmodellprofils. Neue regionale Daten passen dadurch häufig nur über Änderungen an
mehreren Stellen hinein. [Q1–Q4]

Es gibt bereits austauschbare Teilverträge, etwa `MatrixGenerator.Impedance` und
`MunicipalityCentroidSource`. Andere Klassen heißen zwar `Source` oder `Adapter`, werden
aber direkt instanziiert und folgen keinem gemeinsamen Auswahlvertrag. Das Problem ist
deshalb keine völlige Abwesenheit von Adaptern, sondern die fehlende gemeinsame
Komposition ihrer Beiträge. [Q5, Q8]

### 2.2 Ein globaler Quellschalter wäre zu grob

OSM trägt zu Siedlungsgewichten, Zielattraktivitäten, Paketstandorten, Straßen,
Restriktionen und Infrastruktur bei. Ein einziges „OSM an/aus“ könnte diese Aufgaben
nicht sinnvoll unterscheiden. Das frühere Feld `population.useOsmData` wird im aktuellen
Code ohnehin ignoriert; POIs und Siedlungen werden automatisch aufgelöst. Der zentrale
Schalter `overpass.live-data-enabled` steuert im POI-Pfad Livezugriffe, ist aber kein
allgemeiner fachlicher Ausschluss sämtlicher OSM-Verwendungen. Der Netzpfad hat seine
eigene Quellenwahl. [Q1, Q6]

### 2.3 Vorhandene Daten wirken nicht automatisch auf jedes Modell

- GTFS wirkt auf das MobileCity-Angebot und den SUMO-ÖV-Pfad. Die gemeinsame
  ÖV-Reisezeitmatrix entsteht weiterhin aus Pkw-Zeit mal 1,7 plus acht Minuten.
- KBA-Pkw-Antriebsanteile wirken im MobileCity-Pfad. Die gemeinsame Bevölkerungssynthese
  erzeugt ihren Pkw-Bestand weiterhin aus Haushalten und Szenarioannahmen.
- BNetzA-/OSM-Stationsdaten erzeugen einen optionalen Infrastrukturbestand und Prüfberichte.
  Dieser Bestand allein plant keine Ladehalte und erzeugt keine zusätzliche Nachfrage.
- Ein importierter Fahrzeugtyp erzeugt keine LKW-Fahrt. Ein Betriebsstandort ebenfalls nicht.
- MobileCity wird vor der logiTopp-Nachfragesimulation exportiert. Es übernimmt heute
  nicht einfach deren spätere gemeinsame Personen-, Paket- und Güterverkehrsbewegungen.

Diese Grenzen sind für ein Pluginmodell genauso wichtig wie ein Dateiformat. [Q1, Q5,
Q12–Q14, Q18]

## 3. Was bedeutet „generisch“ oder „regionsspezifisch“?

Die Einordnung braucht **zwei Achsen**: technische Austauschbarkeit und fachliche
Übertragbarkeit. Ein generischer CSV-Importer kann einen ausschließlich regional
gültigen Datensatz lesen. Ein global verfügbarer Datendienst liefert trotzdem nur
Beobachtungen des konkreten ausgewählten Gebiets.

| Einordnung | Bedeutung | Beispiele |
|---|---|---|
| Globales Format / globaler Anbieter | Technisch gebietsübergreifend nutzbar; lokale Vollständigkeit und Klassifikation bleiben zu prüfen | OSM, GTFS als Format, Overture |
| Nationale Datengrundlage | Für viele Regionen eines Landes nutzbar, nicht automatisch für andere Länder | Zensus 2022, deutsche BA-, KBA-, BKG- und BNetzA-Daten |
| Regionaler Datenbestand | Konkrete Abdeckung und Bezugszeit; gegebenenfalls landesspezifisches Schema | KVV-GTFS, SEVAS NRW, kommunale Zählungen, lokaler Mautexport |
| Betriebsbezogene Daten | Gültig für den ausgewählten Betrieb und Planungszeitraum | Unternehmenstouren, Kunden-/Depotorte |
| Übertragenes Modellprofil | Technisch anderswo ausführbar, dort aber nicht automatisch empirisch gültig | Rastatt-Wahlparameter, Marne-Haushaltspool, TRAMIGO-Profil |
| Generisches Ersatzverfahren | Algorithmus ist übertragbar; Parameter und Ergebnis sind Annahmen | Dichteverteilung, Luftlinienmatrizen, synthetische Attraktivitäten |

Deutschlandweit verfügbar bedeutet also weder weltweit generisch noch für jede
kleinräumige Fragestellung repräsentativ. Auch ein neuer Quellenadapter beseitigt die
regionalen Annahmen eines nachgeschalteten Verhaltensmodells nicht.

## 4. Quelleninventar: heutiger Beitrag zur Nachfrage

**Lesart:** „Automatisch“ bedeutet Verwendung im angegebenen Pfad bei erfüllten Gates.
„Optional“ bedeutet Aktivierung oder Upload erforderlich. „Offline“ bedeutet, dass
Skripte vorhanden sind, deren Ergebnis erst bereitgestellt/importiert werden muss.
Ein hier genannter Fallback ist der **heutige**, kein bereits umgesetzter Teil des
Vorschlags.

### 4.1 Bevölkerung und Ziele

| Quelle / Grundlage | Tatsächlicher Beitrag | Verfügbarkeit / Auswahl heute | Räumliche Bindung | Ersatz / Grenze heute |
|---|---|---|---|---|
| Manuelle Einwohnerzahl und Ersatzdichte | Gesamtbevölkerung; daraus Menge synthetischer Personen und mittelbar Paketnachfrage | Szenario; manuelle Gesamtzahl hat Vorrang | Beliebiges Gebiet; Annahme | Ohne Gesamtzahl: Raster, OSM-Siedlungen, dann Dichte. Manuelle Gesamtzahl verdrängt nicht automatisch Rastergewichte. [Q1] |
| Hochgeladenes Bevölkerungsraster | Gesamtzahl, falls nicht überschrieben; räumliche Verteilung | `population-grid.csv` vor automatischem Zensus | Importformat wiederverwendbar; Inhalt gebietsbezogen | Ohne zugeordnete positive Bevölkerung wird das Raster ignoriert; Parsefehler sind davon zu unterscheiden. [Q1, Q7] |
| Destatis Zensus 2022, Bevölkerungsraster | Gesamtbevölkerung und Verteilung auf Modellzonen | Automatischer, versionierter und hashgeprüfter Cache-/Downloadpfad für deutsche Gebiete | Deutschland; Stichtag 15.05.2022 | Bei Ausfall/fehlenden verwertbaren Zellen OSM, dann Dichte. Keine automatische Fortschreibung auf das Szenariojahr. [Q7] |
| OSM-Siedlungen mit Einwohnerangaben | Bevölkerungsschätzung und räumliche Gewichte | Snapshot oder Overpass; nach Raster | Globales Schema, lokale und zeitlich uneinheitliche Einträge | Ohne Siedlungen: POI-Signal für Verteilung, sonst Zufallsgewichte; Gesamtzahl gegebenenfalls Dichte. Keine amtliche Vollerhebung. [Q1, Q6] |
| OSM-POI-Dichte | Ersatzsignal für Wohnbevölkerungsverteilung | Wenn kein Raster-/Siedlungsgewicht greift | Technisch global, lokal unterschiedlich erfasst | POI-Dichte ist keine Wohnbevölkerungsmessung; ohne POIs räumliche Zufallsgewichte. [Q2] |
| Gebündelter KIT-Haushaltspool | Gemeinsame Haushaltszusammensetzung, Alter und Geschlecht; Ziehung ganzer Haushalte | `useKitPopulation=true`, festes `/kit-population-sample.csv` | Synthetisches Marne-Beispiel, übertragene Prototypgrundlage | Abwahl: eigene Haushaltsziehung. Fehlende Ressource bei aktivem Pool wirft einen Fehler; kein automatischer Pool-Download. Einkommen, Erwerb und Pkw werden zusätzlich modelliert. [Q2] |
| Zensus 2022, Alter und Haushaltsgrößen | Optionale regionale Nachgewichtung des vorhandenen Haushaltspools | `useCensusStructure=true`, benötigt KIT-Pool | Deutschland; lokale bzw. regionale Randsummen | Bei schwachen lokalen Daten regionale Anteile; bei unzureichenden Daten/Fit Rückfall auf Poolziehung mit Bericht. Erfindet keine im Pool fehlenden Haushaltstypen. [Q7] |
| OSM-Einrichtungen: Handel, Bildung, Büros, Freizeit usw. | Zweckbezogene Attraktivitäten; beeinflussen feste und variable Zielwahl | POI-Snapshot vor Live-Overpass | Globales Schema; lokale Vollständigkeit ungesichert | Feste Taggewichte sind Modellannahmen, keine gemessenen Arbeitsplätze oder Besucherzahlen. Ohne POIs insgesamt synthetische Attraktivitäten. [Q3, Q6] |
| Wohnbevölkerung je Zone | Private Besuche/Erledigungen als Teil der Attraktivitäten | Modellintern aus vorheriger Bevölkerungsverteilung | Generisches Ableitungsverfahren | Abgeleitete Attraktivität, keine zusätzliche externe Quelle. [Q3] |
| Normalisierte Pendler-OD / BA-Auspendlerexport | Wohnort-Arbeitsort-Beziehungen, feste Arbeitsziele, Lage externer Zielanker | Upload; BA-Format wird über Gemeindepunkte normalisiert | Normalisierter Vertrag übertragbar; BA-Quelle Deutschland | Nicht abgedeckte Wohnzonen verwenden Gravitation. Pendlerbestand ist kein Tagesfahrtenzählwert. Bedingte externe OD und vollständige OD werden unterschieden. [Q8] |
| BKG-Gemeindepunkte (VG250) | Übersetzung deutscher Gemeindeschlüssel in Koordinaten beim BA-Import | Live-WFS beim Import | Deutschland | Keine zusätzliche Nachfrage. Ohne Zuordnung scheitert der betreffende Importpfad bzw. bleiben Beziehungen unaufgelöst; kein universeller Geocoder. [Q8] |
| Regionalstatistik-Arbeitsplatzrandwerte / gebündelte BA-Gemeindedaten 2025 | Zielanteil externer fester Arbeitsorte | `workplace-margins.csv`; beim BA-Import kann automatisch ein gebündelter Randwert entstehen | Deutschland; BA-Referenz 30.06.2025 | Nur mit externer OD-Verteilung angewendet; sonst nicht angewendet. Kein Ersatz für Richtungs-/Zielinformation. Gemeindebezug ist nicht automatisch identisch mit einer beliebigen Studienbox. [Q8] |
| Synthetische Außenzonen | Richtungsanker für externe Ziele | `externalTrips=true`; im Normalfall acht Anker | Generisches Verfahren, räumliche Annahme | Keine Einwohner und keine beobachtete Einpendler-/Transitnachfrage allein durch diese Anker. [Q1, Q9] |
| OSM-Außenzielkandidaten | Diagnose und gegebenenfalls Vergleich mit Pendler-OD | Bei verfügbaren Siedlungsdaten als Artefakt | Globale Datenbasis, regional erzeugter Kandidat | Aktuell ausdrücklich **nicht verhaltenswirksam**. Nicht als aktive Quelle der Arbeitszielwahl zählen. [Q9] |

Die aktuelle POI-Logik ersetzt nicht gezielt jede einzelne fehlende Zweckkategorie.
`synthetic()` wird gewählt, wenn die gesamte POI-Liste leer ist. Bei vorhandenen POIs
gelten die Klassifikation und Nullinitialisierung auch für schlecht erfasste Kategorien.
Das ist ein konkreter Ansatzpunkt für die spätere Auflösung pro Aspekt/Zweck. [Q3]

### 4.2 Verhalten, Erreichbarkeit und Referenzen

| Quelle / Grundlage | Tatsächlicher Beitrag | Auswahl heute | Generisch / regional | Ersatz / Grenze heute |
|---|---|---|---|---|
| actiTopp samt Modellparametern | Wochenaktivitäten und zeitliche Struktur aus Personen-/Haushaltskontext | Standard-Verhaltensquelle, lokaler separater Adapterprozess | Algorithmus wiederverwendbar; deutsche Verhaltensgrundlage, Raumtyp als Szenarioannahme | Bei Nichtverfügbarkeit/Fehler Templategenerator. Keine lokale Beobachtung je neuer Region. [Q1, Q10] |
| GTA / konfiguriertes LLM | Alternative Aktivitätenpläne und experimenteller Szenariokontext | `behaviorSource=gta` | Modell-/Promptprofil übertragbar, keine regionale Erhebung | GTA → actiTopp → Template; innerhalb der Aktivitätskonvertierung bestehen weitere Ersatzregeln für fehlende Pläne. [Q1, Q10] |
| Synthetische Aktivitätenvorlagen | Ersatz-Wochenpläne | Explizit wählbar oder Fallback | Generisches Modellverfahren mit eigenen Annahmen | Technische Lauffähigkeit, kein empirisch kalibriertes lokales Verhalten. [Q10] |
| mobiTopp/logiTopp-Wahlparameter aus dem Adapterprofil | Variable Zielwahl, Moduswahl und Teile des Logistikverhaltens | Lokale Adapterdistribution; feste Parameterdateinamen im Runner | Wiederverwendbarer Code, insbesondere Rastatt-geprägtes Parameterprofil | Ohne kompatiblen Laufstack Simulation übersprungen. Die exportierten Aktivitäten sind dann noch keine fertig simulierten Personenwege. [Q4] |
| OSM-Straßennetz | Netz für GraphHopper-Matrizen und SUMO; optional gemeinsame Routen/straßennahe synthetische Orte | Szenariodatei → konfigurierte lokale OSM-Datei → Overpass; Wiederverwendung im Lauf | Globales Schema, konkreter regionaler Netzausschnitt | Für Matrizen Luftlinienersatz möglich; für echte SUMO-Ausführung kein gleichwertiger netzloser Ersatz. Bei angeforderten straßennahen Orten ist Graphfehler ein Fehler. [Q1, Q5, Q6] |
| GraphHopper | Berechnung von Pkw-, Rad- und Fußimpedanzen aus dem Netz | `useRealRouting=true` | Generisches Rechenwerkzeug, keine unabhängige Datenquelle | Nicht routbare Paare sowie intra-/externe Relationen verwenden Schätzregeln; Anbieterstatus allein beschreibt daher nicht jede Matrixzelle. [Q5] |
| Luftlinie, Geschwindigkeits-, Kosten- und Umwegannahmen | Ersatzimpedanzen; ÖV-Matrix und Kosten auch bei aktivem Straßenrouting teilweise modelliert | Feste Konstanten in `MatrixGenerator` | Generisches Verfahren, heute codegebundene Parameter | Keine Fahrplan- oder beobachtete Kostenmatrix; wichtiger Kandidat für konfigurierbare Modellprofile. [Q5] |
| GTFS-Upload / konfigurierte Feedregistry | Fahrplanangebot für MobileCity; Busangebot und ÖV-Nutzung im SUMO-Pfad | Upload zuerst, sonst erster Feed dessen Bbox das Regionszentrum enthält; Cache je Feedname | Format global; Feed regional. Defaults KVV und deutscher Nahverkehrsfeed | Kein allgemeiner Versuch aller überlappenden Feeds. MobileCity darf modellierten Ersatz nach Flag nutzen; defekter ausgewählter Upload wird dort als Fehler behandelt. Kein gemeinsames GTFS-Fahrplanrouting der Personen-Moduswahl. [Q11] |
| MiD 2017 und editierte Zielanteile | Referenzkennzahlen und optionale Kalibrierziele für Modus/Distanz | Gebündelte Werte und Szenariofelder; Kalibrierflags standardmäßig aus | Deutsche Referenz; regionale UI-Vorlagen sind Richtwerte, keine neuen amtlichen Tabellen | Keine neue regionale Nachfragequelle. Zielwerttreffer allein belegt weder passende Ziele noch korrektes Netz. Kein automatischer MiD-2023-Import. [Q15] |
| SUMO-Ausgabe `edgedata.xml` | Optionales Feedback auf Pkw-Zeitmatrix und erneute Nachfrageberechnung | `sumoFeedback=true`, geeigneter erfolgreicher Vorlauf | Modellinternes Ergebnis für genau diesen Lauf | Simulierter Zustand, keine unabhängige Verkehrsmessung. [Q1] |

GTFS beschreibt planmäßiges Angebot einschließlich Betriebstagen und Fahrzeiten; daraus
folgt keine gemessene Fahrgastnachfrage. Die offizielle
[GTFS-Referenz](https://gtfs.org/documentation/schedule/reference/) bestätigt den
Angebotsvertrag und die gesonderte Behandlung von Betriebstagen.

### 4.3 Pakete, Schwerlastverkehr und Unternehmen

| Quelle / Grundlage | Tatsächlicher Beitrag | Auswahl heute | Generisch / regional | Ersatz / Grenze heute |
|---|---|---|---|---|
| Szenario-Paketraten, Streuung, Empfangsanteile, Dienste und Retourenparameter | Private Paketaufträge und deren Modellkonfiguration; Touren entstehen im logiTopp-Lauf | Szenarioannahmen mit Defaults; Retouren optional | Parametermechanik übertragbar; Werte nicht automatisch regional beobachtet | Keine automatische reale Bestellquelle; keine vollständige betriebliche Güternachfrage. [Q16] |
| Manuelle Depots | Standorte, Personal-/Anteil-/Fahrzeugvorgaben | Vorrang vor automatischer Depotwahl | Betriebs-/regionsbezogen | Ohne manuelle Depots OSM-Kandidaten und synthetische Ergänzung. [Q16] |
| OSM-Depotkandidaten und Paketstationen | Logistikstandorte | Aus den bereits geladenen POIs | Globales Schema, lokale Erfassung | Depotanzahl/Personal und Stationskapazität teilweise Annahmen. Fehlende Depots werden ergänzt; bei fehlenden nutzbaren Paketstationen synthetische Standorte. Gemischte Herkunft ist möglich. [Q16] |
| Explizite schwere Tages-OD | Zusätzliche `truck`-/`trailer`-Fahrten für Binnen-, Quell-, Ziel- und Durchgangsverkehr | Upload nach `Scenario.freight.relations`, Aktivierung erforderlich | Vertrag übertragbar; Eingabedaten konkret regional | Kein automatisches Schwerlast-Ersatzmodell. Aktiv ohne Relationen wird abgelehnt; deaktiviert liefert keine schweren Fahrten. Derzeit nur bei `samplingFraction=1.0`. [Q17] |
| Toll Collect / BALM: lokale Maut-OD-Exporte | Beobachtungsbasis für schwere Hintergrundbeziehungen; normalisiert und räumlich modelliert | Offline: `prepare-toll-freight.py`, `prepare-toll-regional-od.py`, `prepare-regional-freight.py`; dann OD-Import | Deutschlandweite Quelle, jeweiliger Export und Pilot regional | Kein laufender automatischer Portalabruf. Monatsmittel, Klassenübertragung, lokale Repräsentanten und Netzrandanbindung sind Modellierungen. Mautabdeckung ist keine Vollerhebung aller Wirtschaftsfahrten. [Q19] |
| BASt-/Straßen.NRW-Zählungen und örtliche Zählreferenzen | Mengen-/Klassenvergleich, optionaler Offlinefit von Hintergrundmengen | Vergleichsskripte; `calibrate-regional-freight.py` mit bereitgestellten kompatiblen Zählern und Routeninzidenzen | Deutschland bzw. konkrete Stationen/Regionen | Kein allgemeiner automatischer Import bei jedem Szenario. Zurückgehaltene Prüfzähler sind getrennt; Fitbericht bleibt ohne neue native Abnahme unvalidiert. [Q19] |
| KBA-LKW-PUF | Referenzinventar zu Güterverkehrsstatistik, Einheiten und Qualitätsmarkern | Offline: `inspect-kba-freight.py` | Deutsche LKW-Statistik mit eigener Grundgesamtheit | Keine automatische lokale Tages-OD und kein pauschaler Leerfahrtenaufschlag. Nicht mit KBA-Pkw-BEV-Anteilen verwechseln. [Q19] |
| Overture-Places / OSM-Betriebsinventare | Zusätzliche Betriebs-/Logistikstandortkandidaten und Quellenvergleich | Offlinepilot mit gepinntem Overture-Release und OSM-Abgleich | Globaler Anbieter; lokale Kandidaten und Auswahlregeln | Noch keine allgemeine produktive Ableitung von Fahrtenzahlen aus Betrieben. Standort, Branche und Existenzkonfidenz belegen keine Liefermenge. [Q19] |
| OSM-HGV-Restriktionen / SEVAS NRW | LKW-Routing und Zugangs-/Profilprüfung | OSM beim SUMO-Netzaufbau; SEVAS als optionaler Snapshot | OSM globales Schema; SEVAS NRW | Keine Nachfragemengen. Unterstützte Teilmenge von Regeln; unbekannte/komplexe Regeln sind kein Nachweis freier Zufahrt. [Q20] |
| Importierte Unternehmenstouren | Explizite zusätzliche Bewegungen, Touren, Fahrzeug-/Zeit-/Servicekontext | Formatübergreifender Import; nur aktiv ausgewählte Planrevisionen werden verwendet | Betrieb und Planungsdatum; Importmechanik generisch | Import allein aktiviert keine Nachfrage. Aktuell additive Kombination mit Hintergrund; keine automatische mengenmäßige Überlappungsbereinigung. [Q18, Q19] |
| Lokale Ortstabellen, lokaler Geocoder, OSM-Adressen, NRW-Gebäudereferenzen | Ortszuordnung der Unternehmenstouren | Import-/Korrekturpfad und ergänzende Offlinevorbereitung | Schnittstellen übertragbar; jeweiliger Adressbestand regional | Explizit gekennzeichnete Näherungen vorhanden. Ortszuordnung fügt keine Aufträge hinzu und beweist keine genaue LKW-Zufahrt. [Q18] |

Das [Lkw-Verkehrsportal des Betreibers](https://company.toll-collect.de/de/produkte-loesungen/daten-geoinformationssysteme/lkw-verkehrsportal/)
beschreibt Start-Ziel-Beziehungen als auf Gemeinden aggregierte Mautfahrten. Das
bestätigt den Unterschied zu einer vollständigen Betrieb-zu-Betrieb-Nachfrage.
Overture beschreibt seine [Places](https://docs.overturemaps.org/schema/reference/places/place/)
als punktförmige Einrichtungen/Betriebe. Die Ableitung von Liefermengen wäre ein
zusätzlicher Modellschritt.

**Nur recherchiert bzw. vorgeschlagen:** VP2040-Gütermatrizen, weitere europäische
Güterverkehrsaggregate und zusätzliche Wirtschaftsverkehrserhebungen sind keine heute
allgemein verdrahteten Quellen der Generierung. Statista ist im Projekt Quellenhinweis/
Kontext, kein produktiver Demandadapter. Die historische Quellenrecherche steht in
[21](21-gueterverkehr-konzept-und-datenquellen.md); ihre früheren Statusabschnitte sind
zusammen mit den späteren Umsetzungseinträgen zu lesen.

### 4.4 Ergänzungen für Zielmodelle und Fahrzeugbetrieb

| Quelle / Grundlage | Tatsächlicher Beitrag | Räumliche Bindung | Grenze zur gemeinsamen Nachfrage |
|---|---|---|---|
| KBA-Pkw-Referenz 01.07.2026 + BKG-Gemeindegeometrien | Optionale private BEV-Anteile für MobileCity; räumlich übertragen | Deutschland, gebündelter KBA-Stand und BKG-Antwort | Kein beobachteter gemeinsamer Pkw-Gesamtbestand. Unabgedeckte Zonen benötigen gegebenenfalls explizite BEV-Annahme; für ein vollständiges Regionalpaket kann fehlende Abdeckung blockieren. [Q12] |
| BNetzA-Ladepunktreferenz 01.09.2026 | Öffentliche Ladepunktzahlen für MobileCity; optionales detailliertes Stationsinventar | Deutschland, gespeicherter Registerstand | Ladepunkte sind nicht Standorte oder Steckeranzahlen. Im MobileCity-Export sind historische Rückrechnungen nur überlebender Registerbestand; unbekannte Zukunft bleibt semantisch offen. [Q13] |
| OSM-Lade-/Tankstellen für Strom, CNG, LNG, LPG und H₂ | Optionales Stationsinventar, ergänzt durch BNetzA und Zugangsprüfung | Globales Schema, regionale Erfassung | Keine Liveverfügbarkeit und kein automatisch erzeugtes Lade-/Tankverhalten. Mögliche Quellendubletten werden ausgewiesen. [Q14] |
| Zensus-Umlandpuffer + manuelle Zweckanteile | MobileCity-Umlandbevölkerung/-alter und eingehende zweckspezifische Verkehrsanteile | Deutscher Zensus; Puffer regional | Bevölkerung ist nicht Einpendlerzahl; die vier Verkehrsanteile bleiben separate Annahmen. Kein gemeinsamer automatischer Außenverkehr für logiTopp/SUMO. [Q12] |
| TRAMIGO-Profil `tramigo-v2` | Pflicht-, Technologie-, Wirtschafts- und Modellparameter für vollständigen MobileCity-Export | Übertragenes Referenzprofil | Hashprüfung bestätigt Identität, keine lokale Kalibrierung. [Q12] |
| Carsharing-, Siedlungsflächen-, Flotten- und ÖV-Ersatzannahmen | Ergänzende MobileCity-Modellgrößen | Nutzerannahmen für das Gebiet | Nicht gesetztes Sharing wird heute explizit als Nullannahme behandelt; das ist keine Erhebung. [Q12] |
| Fahrzeugkatalog, explizite Zuordnungen und Betriebsprofile | Fahrzeugtypen, optional Identitäten, Ketten und Energiebilanz vorhandener Fahrten | Vertrag übertragbar; Zuordnungen szenariobezogen | Verändert nicht allein die Zahl von Haushalts-Pkw, Aufträgen oder schweren Fahrten. [Q18] |

Kartenkacheln dienen der Anzeige, EPSG/INSPIRE der räumlichen Referenz und Zonierung.
Beides sind keine zusätzlichen Nachfragebeobachtungen. GeoJSON-/CSV-Ergebnisse und
Simulatorausgaben sind abgeleitete Daten, sofern sie nicht ausdrücklich als externe
Beobachtung eines anderen Datensatzes importiert werden.

## 5. Heutige Fallbacks als überprüfbare Ketten

| Aspekt | Heutige Reihenfolge / Reaktion |
|---|---|
| POIs / Siedlungen | Szenario-Snapshot → Live-Overpass mit Endpunktausweichlogik → leere Teilmenge/synthetische Weiterverarbeitung; fehlerhafter vorhandener Snapshot bricht ab |
| Bevölkerungsgesamtzahl | Manuelle Zahl → verwertbarer Upload → Zensus → OSM-Siedlungsschätzung → Dichte |
| Räumliche Bevölkerungsverteilung | Upload/Zensusgewichte → OSM-Siedlungsgewichte → POI-gestützte Zufallsgewichte → Zufallsgewichte ohne POIs |
| Haushaltsstruktur | Optional regional gewichteter KIT-Pool → ungegewichteter KIT-Pool; explizite Poolabwahl führt zur eigenen Synthese |
| Feste Arbeitsziele | Passende Pendlerinformation gemäß vollständigem/extern bedingtem Vertrag → Gravitation mit Attraktivitäten und initialer Pkw-Zeitmatrix; externer Randwert nur mit externer OD |
| Aktivitäten | Gewählte Quelle; bei GTA: GTA → actiTopp → Template; bei actiTopp: actiTopp → Template |
| Pkw/Rad/Fuß-Matrizen | Straßenrouting soweit zulässig/verfügbar → Schätzregeln je Paar; ohne Graph insgesamt Schätzregeln. Straßennahe Orte verlangen erfolgreichen Graphen |
| ÖV-Matrix | Heute durchgehend Modellformel aus Pkw-Zeit; GTFS ist hier noch keine alternative Matrixquelle |
| Paketdepots | Manuell → OSM-Kandidaten → synthetische Ergänzung; Paketstationen OSM → synthetisch |
| Schwerlastverkehr | Ausgeschaltet: kein Beitrag. Eingeschaltet: vorbereitete Relationen notwendig; keine universelle automatische Ersatznachfrage |
| Unternehmenstouren | Nur ausgewählte, geprüfte Ausführungsrevisionen; fehlende Betriebstouren werden nicht erfunden |
| MobileCity-ÖV | GTFS → modellierter Ersatz, falls erlaubt; fehlerhafter ausgewählter Upload ist Fehler |
| SUMO-ÖV | Geeigneter Feed und Laufzeit nötig; ohne Voraussetzungen entfällt dieser Angebots-/Ausführungsteil, die gemeinsame geschätzte ÖV-Matrix bleibt davon getrennt |
| MobileCity-Flotte / Ladepunkte | Regionale Quelle → explizite Annahme, soweit vorgesehen; sonst fehlender Wert bzw. zielabhängiger Blocker |

**Befund:** Es existiert keine einheitliche Regel für „Quelle fehlt“, „Quelle ist ungültig“
und „Quelle ist abgewählt“. Einzelne Pfade sind bewusst streng, andere degradieren.
Diese Unterschiede dürfen bei einem mechanischen Umbau nicht unbemerkt geändert werden.

## 15. Codebelege und Prüfgrenze

Die Verweise beziehen sich auf den analysierten Workspace. Methodennamen sind stabilere
Fundstellen als Zeilennummern. Für die ursprüngliche Bestandsanalyse wurden Tests zur Einordnung gelesen, aber
keine neuen Läufe durchgeführt. Die späteren Implementierungsprüfungen sind getrennt
im [Befundregister](05-validierung.md#datenquellenimport-und-quellenplan-2026-10-28--technischer-schnitt-bestanden) dokumentiert. Quellenjahr, Onlineverfügbarkeit und Kalibrierung aller
gespeicherten Referenzen wurden nicht neu empirisch geprüft.

- **Q1:** [GenerationService](../backend/src/main/java/de/synthesis/generation/GenerationService.java):
  `run`, `stageOsmData`, `preparePopulationDistribution`, `stageAttractivities`,
  `prepareCommuterMapping`, `prepareWorkplaceMargin`, `stageActivityPlans`,
  `stageMobileCityExport`, `publishTrafficDemand`, `buildRouting`, `writeManifest`;
  [Scenario](../backend/src/main/java/de/synthesis/scenario/Scenario.java),
  [Betriebskonfiguration](../backend/src/main/resources/application.yml).
- **Q2:** [PopulationGenerator](../backend/src/main/java/de/synthesis/generation/pipeline/PopulationGenerator.java):
  `distributePopulation`, `generate`, `useDestinationTravelTimes`, `sampleWorkZone`;
  [PopulationSample](../backend/src/main/java/de/synthesis/generation/pipeline/PopulationSample.java).
  Herkunftsgrenzen des Pools: [Datenquellen und Modellrollen](15-datenquellen-und-modellrollen.md).
- **Q3:** [AttractivityCalculator](../backend/src/main/java/de/synthesis/generation/pipeline/AttractivityCalculator.java):
  `fromPois`, `classify`, `initialize`, `synthetic`, `addPopulationDerived`.
- **Q4:** [SimulationRunner](../backend/src/main/java/de/synthesis/generation/pipeline/SimulationRunner.java):
  `writeRunConfig`, `destinationChoicePath`;
  [logiTopp-Adapter](../adapters/logitopp/README.md).
- **Q5:** [MatrixGenerator](../backend/src/main/java/de/synthesis/generation/pipeline/MatrixGenerator.java):
  `Impedance`, `generate`, `route`;
  [RoutingEngine](../backend/src/main/java/de/synthesis/generation/pipeline/RoutingEngine.java),
  [RoadLocations](../backend/src/main/java/de/synthesis/generation/pipeline/RoadLocations.java).
- **Q6:** [OverpassClient](../backend/src/main/java/de/synthesis/generation/pipeline/OverpassClient.java):
  `fetchPois`, `fetchPlaces`, `fetchRoadNetworkOsm`, `selectedRoadNetworkFile`;
  [OsmDataSnapshot](../backend/src/main/java/de/synthesis/generation/pipeline/OsmDataSnapshot.java).
- **Q7:** [CensusPopulationSource](../backend/src/main/java/de/synthesis/generation/pipeline/CensusPopulationSource.java),
  [PopulationGridParser](../backend/src/main/java/de/synthesis/generation/pipeline/PopulationGridParser.java),
  [CensusStructureSource](../backend/src/main/java/de/synthesis/generation/pipeline/CensusStructureSource.java),
  [CensusHouseholdSampler](../backend/src/main/java/de/synthesis/generation/pipeline/CensusHouseholdSampler.java).
- **Q8:** [UploadController](../backend/src/main/java/de/synthesis/storage/UploadController.java):
  `uploadCommuters`, `uploadWorkplaceMargins`;
  [CommuterMatrix](../backend/src/main/java/de/synthesis/generation/pipeline/CommuterMatrix.java),
  [BaCommuterAdapter](../backend/src/main/java/de/synthesis/generation/pipeline/BaCommuterAdapter.java),
  [WorkplaceMargins](../backend/src/main/java/de/synthesis/generation/pipeline/WorkplaceMargins.java),
  [BkgMunicipalityCentroids](../backend/src/main/java/de/synthesis/generation/pipeline/BkgMunicipalityCentroids.java),
  [MunicipalityCentroidSource](../backend/src/main/java/de/synthesis/generation/pipeline/MunicipalityCentroidSource.java).
- **Q9:** [ExternalZones](../backend/src/main/java/de/synthesis/generation/pipeline/ExternalZones.java),
  [OsmExternalDestinationCandidates](../backend/src/main/java/de/synthesis/generation/pipeline/OsmExternalDestinationCandidates.java).
- **Q10:** [ActitoppRunner](../backend/src/main/java/de/synthesis/generation/pipeline/ActitoppRunner.java),
  [ActivityGenerator](../backend/src/main/java/de/synthesis/generation/pipeline/ActivityGenerator.java),
  [GtaBehaviorRunner](../backend/src/main/java/de/synthesis/generation/pipeline/GtaBehaviorRunner.java),
  [actiTopp-Haushaltsvertrag](../adapters/actitopp/README.md).
- **Q11:** [GtfsSource](../backend/src/main/java/de/synthesis/generation/pipeline/GtfsSource.java),
  [GtfsCropper](../backend/src/main/java/de/synthesis/generation/pipeline/GtfsCropper.java),
  [TransitRunner](../backend/src/main/java/de/synthesis/generation/pipeline/TransitRunner.java),
  [MobileCityGtfsAdapter](../backend/src/main/java/de/synthesis/generation/pipeline/MobileCityGtfsAdapter.java),
  [SynthesisProperties](../backend/src/main/java/de/synthesis/SynthesisProperties.java): `Transit`.
- **Q12:** [MobileCityRegionalExporter](../backend/src/main/java/de/synthesis/generation/pipeline/MobileCityRegionalExporter.java),
  [MobileCityFleetSource](../backend/src/main/java/de/synthesis/generation/pipeline/MobileCityFleetSource.java),
  [MobileCitySurroundingSource](../backend/src/main/java/de/synthesis/generation/pipeline/MobileCitySurroundingSource.java),
  [MobileCityDataInventory](../backend/src/main/java/de/synthesis/generation/pipeline/MobileCityDataInventory.java),
  [MobileCityTemplateExporter](../backend/src/main/java/de/synthesis/generation/pipeline/MobileCityTemplateExporter.java).
- **Q13:** [MobileCityChargingSource](../backend/src/main/java/de/synthesis/generation/pipeline/MobileCityChargingSource.java).
- **Q14:** [EnergyInfrastructure](../backend/src/main/java/de/synthesis/generation/pipeline/EnergyInfrastructure.java),
  [EnergyNetworkAccess](../backend/src/main/java/de/synthesis/generation/pipeline/EnergyNetworkAccess.java).
- **Q15:** [Mid2017](../backend/src/main/java/de/synthesis/reference/Mid2017.java),
  [ModeChoiceCalibrator](../backend/src/main/java/de/synthesis/generation/pipeline/ModeChoiceCalibrator.java),
  [ModeDistanceCalibrator](../backend/src/main/java/de/synthesis/generation/pipeline/ModeDistanceCalibrator.java),
  [Studio-Vorlagen](../frontend/src/app/features/studio/studio.component.ts): `regionPresets`.
- **Q16:** [LogisticsGenerator](../backend/src/main/java/de/synthesis/generation/pipeline/LogisticsGenerator.java),
  [Paketkonfiguration im Adapter](../adapters/logitopp/src/main/java/edu/kit/ifv/mobitopp/simulation/SynthesisParcelDemandModels.java).
- **Q17:** [FreightDemandGenerator](../backend/src/main/java/de/synthesis/generation/pipeline/FreightDemandGenerator.java):
  `parseCsv`, `validateDemand`, `validateRelations`, `generate`, `write`.
- **Q18:** [TrafficDemand](../backend/src/main/java/de/synthesis/generation/demand/TrafficDemand.java),
  [TrafficDemandArtifacts](../backend/src/main/java/de/synthesis/generation/demand/TrafficDemandArtifacts.java),
  [CompanyTourImportService](../backend/src/main/java/de/synthesis/companytour/CompanyTourImportService.java),
  [TourSourceReader](../backend/src/main/java/de/synthesis/companytour/TourSourceReader.java),
  [TourImportProfiles](../backend/src/main/java/de/synthesis/companytour/TourImportProfiles.java),
  [CompanyTourGeocoder](../backend/src/main/java/de/synthesis/companytour/CompanyTourGeocoder.java),
  [VehicleOperationsConfiguration](../backend/src/main/java/de/synthesis/vehicle/VehicleOperationsConfiguration.java).
- **Q19:** [Güterverkehrskonzept und datierte Piloten](21-gueterverkehr-konzept-und-datenquellen.md),
  [Mautnormalisierung](../scripts/prepare-toll-freight.py),
  [regionale Maut-OD](../scripts/prepare-toll-regional-od.py),
  [regionale Güteraufbereitung](../scripts/prepare-regional-freight.py),
  [begrenzter Mengenfit](../scripts/calibrate-regional-freight.py),
  [Zählvergleich](../scripts/compare-freight-counts.py),
  [KBA-Inventar](../scripts/inspect-kba-freight.py),
  [Overture-Download](../scripts/fetch-overture-freight-sites.py),
  [NRW-Adresszuordnung](../scripts/prepare-nrw-tour-addresses.py),
  [OSM-Adresszuordnung](../scripts/prepare-osm-tour-addresses.py).
- **Q20:** [OsmFreightPermissions](../backend/src/main/java/de/synthesis/generation/pipeline/OsmFreightPermissions.java),
  [SevasFreightPermissions](../backend/src/main/java/de/synthesis/generation/pipeline/SevasFreightPermissions.java),
  [FreightProfileChecks](../backend/src/main/java/de/synthesis/generation/pipeline/FreightProfileChecks.java).

Ergänzend herangezogene vorhandene Tests:
[CommuterMatrixTest](../backend/src/test/java/de/synthesis/generation/pipeline/CommuterMatrixTest.java),
[PopulationGeneratorTest](../backend/src/test/java/de/synthesis/generation/pipeline/PopulationGeneratorTest.java),
[GtfsSourceTest](../backend/src/test/java/de/synthesis/generation/pipeline/GtfsSourceTest.java),
[CommonTrafficDemandPipelineTest](../backend/src/test/java/de/synthesis/generation/CommonTrafficDemandPipelineTest.java),
[RegionalBackgroundIntegrationTest](../backend/src/test/java/de/synthesis/generation/pipeline/RegionalBackgroundIntegrationTest.java).
