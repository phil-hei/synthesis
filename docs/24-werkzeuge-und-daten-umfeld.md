# Werkzeuge und Daten im Umfeld von SyntheSis

Stand: 2026-11-04. Ergebnis einer Websuche aus der Cloud-Sandbox (keine Downloads, keine
Installationen); Versionen und Lizenzen sind aus den verlinkten Primärquellen übernommen oder als
**„zu prüfen“** markiert. Die Liste ist nach Nutzen für bekannte Lücken des Projekts geordnet:
Overpass-Ausfälle, fehlende Kalibrierung des Schwerverkehrs, Tourenbildung, Modulgrenzen,
Abhängigkeitspflege, Betriebssicht, veraltete Referenzwerte. Datenquellen speziell für den
Schwerlastverkehr stehen in [docs/23](23-offene-datenquellen-schwerlastverkehr.md).

## 1. Hohe Priorität: schließt eine bekannte Lücke

| Lücke | Werkzeug / Datensatz | Lizenz / Zugang | Einbindung in SyntheSis | Aufwand |
|---|---|---|---|---|
| Overpass liefert 403/429/504, Läufe kippen auf synthetische Daten | **Geofabrik-Extrakte (`.osm.pbf`, Länder und für NRW Regierungsbezirke) + `osmium extract`/`osmium tags-filter`** | ODbL (Namensnennung, Share-alike für abgeleitete Datenbanken) | Zweite Netzquelle neben `synthesis.overpass.road-network-file`: PBF einmal laden, per `osmium extract` auf die Region schneiden; GraphHopper importiert PBF direkt; POIs/Orte aus demselben Extrakt statt Overpass-Abfragen. Macht `inputProvenance.osmData` reproduzierbar (Hash des Extrakts). | mittel |
| Schwerverkehr wird nicht an Zählungen kalibriert | **SUMO `routeSampler.py`** (Routen aus Kanten-, Abbiege- und OD-Zählwerten, GEH-Schwelle, Whitelist) mit `edgeDataFromFlow.py`, Kandidatenrouten aus den vorhandenen Fahrten | EPL 2.0 (SUMO) | Kalibrierschritt nach der Lkw-Nachfrage: Zählwerte aus docs/23 (Dauerzählstellen, SVZ) als `edgeData`, Kandidatenrouten aus `freight.relations`; Ergebnis ersetzt die angenommenen Mengen und wird im Freight-Report ausgewiesen. Nur Routen aus der Eingabe werden gewählt, daher genügend Routenvielfalt erzeugen. | mittel |
| Referenzwerte sind MiD 2017 (`Mid2017`) | **MiD 2023** (Kurzbericht, Studienzusammenfassung, Bericht zur kleinräumigen Regionalisierung „SAE“) | PDFs frei; Mikrodaten über die Clearingstelle | Zweite Referenzklasse `Mid2023` mit Wegen je Person und Tag (2,9 statt 3,1), Modal Split und Distanzbändern; Vergleichstabellen im Studio zeigen beide; Kalibrierziele wählbar. Der SAE-Bericht liefert regionalisierte Kennwerte für RegioStaR-Typen. | klein bis mittel |
| Lkw-Routing beachtet keine Gewichts-/Höhenlimits außerhalb SEVAS | **GraphHopper Custom Models** (`max_weight`, `max_height`, `max_width`, `toll` mit `HGV`; Werte müssen in `graph.encoded_values` stehen; Truck-Modell in Release 11.0 erweitert) | Apache 2.0 | Eigenes Profil `truck` neben dem Pkw-Profil für `freight`-Fahrten; OSM-`maxweight`-Tags fließen automatisch ein. Projektversion in `pom.xml` prüfen; Encoded Values explizit konfigurieren. | klein bis mittel |
| Zustell- und Unternehmenstouren werden heuristisch gebildet | **VROOM** (C++20, BSD-2, TSP/CVRP/VRPTW, Mehrdepot, Abhol/Zustell, Kostenmatrix von GraphHopper/OSRM/Valhalla; `pyvroom`) oder **jsprit** (Java, Apache 2.0, von GraphHopper gepflegt, Basis des MATSim-Freight-Contribs) | BSD-2 / Apache 2.0 | jsprit passt als Java-Bibliothek in das Backend (Touren für `companyTours` und Paketfahrzeuge mit Zeitfenstern); VROOM als Prozess, wenn Laufzeit zählt. Ergebnisse weiter als `assumed` kennzeichnen, bis Beladung und Zeitfenster belegt sind. | mittel |

## 2. Mittlere Priorität: Qualität, Betrieb, Entwicklung

| Thema | Werkzeug / Datensatz | Lizenz / Zugang | Einbindung | Aufwand |
|---|---|---|---|---|
| Modulgrenzen nicht erzwungen (Review M1) | **ArchUnit** (`archunit-junit5`, Regeln `noClasses().that().resideInAnyPackage(…)`, `layeredArchitecture()`, Zyklenprüfung) | Apache 2.0 | Ein Test je Regel: `auth`/`config` dürfen nicht in `generation` hineinreichen, `pipeline` nicht auf Controller; Zyklen zwischen Paketen verboten. Läuft im bestehenden Gate. | klein |
| Abhängigkeiten veralten unbemerkt (Review H4/H5) | **OWASP dependency-check** (Maven-Plugin, 12.x; NVD-API-Schlüssel nötig, Cache im CI) und **Renovate** (GitLab-fähig) | Apache 2.0 / AGPL-Server, Renovate-Bot frei | CI-Job `dependency-check` mit gecachtem NVD-Verzeichnis; Renovate-Konfiguration für Maven und npm mit Gruppierung je Linie (Spring Boot 3.5.x, Angular 21.x). | klein |
| Keine Metriken, Health minimal (Review M4) | **Spring Boot Actuator + Micrometer** (Prometheus-Endpunkt), optional Grafana | Apache 2.0 | `health`, `info`, `metrics` hinter der Anmeldung; Zähler für Läufe, Stufenlaufzeiten, Warteschlange; Container-Healthcheck auf Actuator umstellen. | klein |
| Bevölkerung wird gleichverteilt oder nur über das 100-m-Einwohnerraster verortet | **Zensus 2022 Gitterdaten 100 m**: neben Einwohnern auch Gebäude, Wohnungen, Baujahr, Heizungsart (ZIP mit CSV je Raster, Beschreibung als Excel) | Open Data mit Quellenangabe | Gebäude- und Wohnungszahlen je Zelle als Gewicht für Wohnorte und Haushaltsgrößen; Baujahr/Heizung für Energieszenarien (MobileCity). | klein bis mittel |
| Adressgenaue Standorte (Depots, Unternehmen, Zustellpunkte) | **Amtliche Hauskoordinaten** (BKG HK-DE 2025-10, ca. 3,8 GB; Länder wie Hessen frei, Kommunen wie Essen/Bielefeld in Open.NRW) und **Hausumringe** | je Land verschieden, HK-DE bundesweit **zu prüfen** | Ersatz für Luftlinien-/Zonenpunkte bei `roadLocatedActivities` und Tourenadressen; Import als Datenpaket im Quellenplan. | mittel |
| Geokodierung läuft über ein lokales Hilfsskript | **Photon** (komoot/GraphHopper; Apache 2.0; Java 21; OpenSearch-Backend; wöchentliche Länder-Dumps) | Apache 2.0 | Selbst gehosteter Dienst statt `serve-local-tour-geocoder.py`; Deutschland-Dump statt Planet (Planet: 95 GB, 64 GB RAM). Nominatim-kompatible Antwort bleibt der Vertrag in `company-tour-import.geocoding-endpoint`. | klein bis mittel |
| ÖV-Fahrpläne regional gepflegt (`synthesis.transit`-Registry) | **DELFI-Gesamtdatensatz** (GTFS und NeTEx, Open-Data-ÖPNV, wöchentlich, Registrierung) bzw. **gtfs.de** (`download.gtfs.de/germany/nv_free/latest.zip`, ca. 1,8 GB entpackt) | DL-DE-BY / NVBW-Lizenz, **zu prüfen** je Feed | Ein bundesweiter Feed, per Region zugeschnitten (bestehendes GTFS-Clipping), statt Feed-Pflege je Region; `gtfs-validator` von MobilityData vor dem Import. | mittel |
| LLM-Agenten: freie Textantworten, feste Modellwahl (Qwen 2.5) | **Ollama `format` mit JSON-Schema** (seit 0.5, GBNF-Grammatik in llama.cpp) und neuere lokale Modelle (Qwen 3.x) | MIT (Ollama), Modelle je Lizenz | Persona- und Tagesplan-Antworten per Schema erzwingen (weniger Parser-Fallbacks, reproduzierbarer Cache). Vorsicht: für Qwen 3.5/3.6 ist ein Schema-Adhärenz-Problem gemeldet; Modellwechsel nur nach A/B-Lauf gegen den bestehenden GTA-Cache. | klein (Schema), mittel (Modell) |
| Städtische Kalibrierziele fehlen | **SrV 2023** (TU Dresden, ca. 500 Städte, Städtevergleich Dezember 2025, Methodenbericht) | PDFs frei, Tabellen je Stadt teils bei Kommunen | Stadtspezifische Modal-Split- und Wegeziele als wählbare Referenz neben MiD; besonders für Fuß-/Radanteile in Großstädten. | klein |
| Ergebnisdashboards jenseits des Studios | **SimWrapper** (TU Berlin VSP, GPL v3, CSV-basiert, Dashboards aus YAML) | GPL v3 | Als getrennter Dienst auf den Artefaktordner zeigen (CSV/GeoJSON vorhanden); wegen GPL nicht ins Backend einbetten. SUMO-Unterstützung **zu prüfen**. | klein |

## 3. Niedrige Priorität oder Referenzen

- **GHS-POP R2023A** (JRC, 100 m, Epochen 1975–2030, CC BY 4.0, ohne Registrierung): Bevölkerungsraster
  außerhalb Deutschlands, falls MobileCity-Regionen im Ausland liegen; in Deutschland ist der Zensus genauer.
- **INKAR** (BBSR, über 700 Indikatoren auf Gemeinde-, Kreis-, NUTS-Ebene; R-Paket `inkaR` mit
  Ebene `GEM`/`KRE`): Raumtypen, Erreichbarkeiten, Sozialstruktur für RegioStaR-Ableitung und Plausibilisierung.
- **MATSim Open-Data-Szenarien** (Berlin, Hamburg, München, Ruhrgebiet, Kelheim; GitHub `matsim-scenarios`)
  und **eqasim-Bayern** (offene synthetische Bevölkerung aus Open Data, IRT SystemX/TUM): Vergleichsmaßstab
  für Bevölkerungssynthese und Nachfragekennwerte; Lizenz je Szenario beachten.
- **Eurostat GISCO LAU-Grenzen**: einheitliche Gemeindegeometrien für die Zuordnung von 13111- und
  INKAR-Werten (siehe docs/23, Abschnitt 3.1).
- **Playwright** (bereits für die Abnahme genutzt): als CI-Job für Login, Szenario, Generierung gegen das
  gebaute Jar; schließt die Lücke „keine End-to-End-Tests“.

## 4. Geprüft und nicht empfohlen

| Kandidat | Grund |
|---|---|
| ActivitySim / PopulationSim | US-zentriert, Konsortiumsmitgliedschaft für Support; actiTopp und die eigene Synthese decken den Bedarf |
| Kommerzielle Verkehrsdaten (TomTom, HERE, Floating-Car-Daten) | nicht offen, nicht reproduzierbar veröffentlichbar |
| Microsoft/Google Gebäudeumrisse | Abdeckung und Qualität für Deutschland nicht belegt; Zensus-Raster und ALKIS/Hausumringe sind amtlich |
| Spring Modulith | der Nutzen (Modulgrenzen) ist mit ArchUnit ohne Framework-Umbau erreichbar |

## 5. Vorgeschlagene Reihenfolge

1. Geofabrik/osmium als zweite OSM-Quelle (beseitigt die häufigste Fallback-Ursache).
2. ArchUnit, dependency-check, Renovate, Actuator (je ein Commit, kleiner Aufwand).
3. MiD 2023 und SrV 2023 als wählbare Referenzen.
4. GraphHopper-Truck-Profil, dann `routeSampler`-Kalibrierung mit den Zählstellen aus docs/23.
5. jsprit/VROOM für Touren, Zensus-Gebäuderaster und Hauskoordinaten für Standorte.
6. Ollama-Schema-Ausgabe, Photon, bundesweiter GTFS-Feed, SimWrapper nach Bedarf.

## Quellen

- SUMO: [routeSampler-Dokumentation](https://sumo.dlr.de/docs/Tools/Turns.html), [Routes from Observation Points](https://sumo.dlr.de/docs/Demand/Routes_from_Observation_Points.html)
- GraphHopper: [Custom Models](https://github.com/fbonzon/graphhopper/blob/master/docs/core/custom-models.md), [Releases](https://github.com/graphhopper/graphhopper/releases), [Forum: Truck-Profil und Encoded Values](https://discuss.graphhopper.com/t/different-route-using-truck-profile-in-web-graphhopper-and-locally/9494)
- VROOM: [pyvroom auf PyPI](https://pypi.org/project/pyvroom/), [Projektübersicht](https://awesome.ecosyste.ms/projects/github.com%2FVROOM-Project%2Fvroom); jsprit: [Repository](https://github.com/faceang/jsprit), [MATSim freight contrib](https://matsim.org/apidocs/freight/12.0/org/matsim/contrib/freight/package-summary.html), [FreightSim 2026](https://www.dlr.de/de/vf/aktuelles/veranstaltungen/freightsim-2026-matsim)
- Geofabrik/osmium: [Download-Server](https://download.geofabrik.de), [NRW-Regierungsbezirke](https://download.geofabrik.de/europe/germany/nordrhein-westfalen.html), [osmium-extract](https://manpath.be/f38/1/osmium-extract)
- MiD 2023: [Studienzusammenfassung](https://www.mobilitaet-in-deutschland.de/pdf/MiD2023_Studienzusammenfassung.pdf), [Kurzbericht](https://www.mobilitaet-in-deutschland.de/pdf/MiD2023_ZusammenfassungKurzbericht.pdf), [SAE-Bericht](https://www.mobilitaet-in-deutschland.de/pdf/MiD2023_SAE-Bericht.pdf)
- SrV 2023: [Ergebnisseite TU Dresden](https://tu-dresden.de/bu/verkehr/ivs/srv/srv-2023/startseite), [Ergebnispräsentation](https://tu-dresden.de/bu/verkehr/ivs/srv/ressourcen/dateien/SrV2023_Gerike_Ergebnisse.pdf?lang=de)
- Zensus 2022: [Ergebnisse und Gitterdaten](https://www.zensus2022.de/EN/Census_results/02-release-of-results.html), [R-Paket z22](https://cran.r-project.org/web/packages/z22/index.html)
- Hauskoordinaten: [BKG HK-DE Dokumentation](https://sg.geodatenzentrum.de/public/gdz/dokumentation/deu/hk-de.pdf), [Hessen Produktblatt](https://hvbg.hessen.de/sites/hvbg.hessen.de/files/2022-09/produktblatt_hauskoordinaten.pdf), [Open.NRW Essen](https://ckan.open.nrw/dataset/amtliche-hauskoordinaten-essen-rvr/resource/b7eb4b4b-1ae0-4808-a2e7-e3e857d1d9c3)
- GTFS: [Mobility Database gtfs.de](https://mobilitydatabase.org/feeds/gtfs/mdb-1090), [BayernInfo zum DELFI-Datensatz](https://www.bayerninfo.de/en/about-bayerninfo-1/data-offer/public-transport-data)
- Ollama/LLM: [Strukturierte Ausgabe mit Ollama und Qwen3](https://www.glukhov.org/post/2025/09/llm-structured-output-with-ollama-in-python-and-go/), [Leitfaden lokale Modelle 2026](https://insiderllm.com/guides/structured-output-local-llms/)
- ArchUnit: [Beispielprojekt](https://github.com/mehmetpekdemir/spring-boot-archunit), [DZone-Einführung](https://dzone.com/articles/enforcing-architecture-with-archunit-in-java)
- dependency-check: [Repository](https://github.com/dependency-check/DependencyCheck), [Praxisleitfaden](https://appsecsanta.com/owasp-dependency-check)
- SimWrapper: [Einführung](https://billyc.github.io/blog/2023/11/what-is-simwrapper.html), [VSP-Arbeitspapier](https://svn.vsp.tu-berlin.de/repos/public-svn/publications/vspwp/2022/22-21/CharltonSana2022SimWrapper.pdf)
- Photon: [Repository](https://github.com/komoot/photon), [Wiki](https://github.com/komoot/photon/wiki)
- GHS-POP: [JRC-Datenkatalog](https://data.jrc.ec.europa.eu/dataset/2ff68a52-5b5b-4a22-8f40-c41da8332cfe)
- INKAR: [R-Paket inkaR](https://cran.r-project.org/web/packages/inkaR/refman/inkaR.html)
- MATSim/eqasim: [Open-Data-Modelle](https://matsim.org/install/open-data-models/), [eqasim-Bayern](https://science.ecosyste.ms/projects/45075), [TUM Open Data](https://www.mos.ed.tum.de/tb/opendata/)
