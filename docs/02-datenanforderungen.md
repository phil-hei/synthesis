# 02 – Datenanforderungen: Was ist mindestens nötig, was können wir synthetisch erzeugen?

Leitfrage: Ein Nutzer wählt auf der Karte eine Region und ein paar Parameter – welche Daten
braucht das Nachfragemodell, und welche davon können wir **automatisch ableiten** (aus offenen
Quellen) bzw. **synthetisch/zufällig generieren**?

## Drei Qualitätsstufen je Datenebene

Jede Datenebene bekommt einen austauschbaren Provider mit drei Stufen. Das ist der Kern der
Generik – ein Szenario funktioniert immer, wird aber mit besseren Daten realistischer:

1. **Echt** – vom Nutzer hochgeladene Projektdaten (z. B. kommunale Strukturdaten, echte Depotstandorte)
2. **Öffentlich** – automatisch bezogene offene Daten (OSM, Zensus-Raster, GTFS, Pendlerstatistik)
3. **Synthetisch** – regelbasiert/zufällig generiert mit dokumentierten Annahmen (Seed-reproduzierbar)

Das Frontend zeigt je Ebene die aktive Stufe an („Datenqualitäts-Ampel"), damit klar ist,
wie belastbar ein Szenario ist.

## Datenebenen im Einzelnen

### A. Zonierung (Verkehrszellen / Districts) — vollständig ableitbar

- **Benötigt:** Polygone + IDs; daraus `zones.csv`, `zone-to-community.csv`, und für MobileCity
  `districts.gpkg` + `districts.xlsx`.
- **Stufe öffentlich:** OSM-Verwaltungsgrenzen (`admin_level` 8–10: Gemeinden/Stadtteile) innerhalb
  der gewählten Region; Deutschland-weit auch VG250 (BKG, Open Data).
- **Stufe synthetisch:** H3-Hexagon- oder Quadrat-Raster über der Auswahl (Auflösung = UI-Parameter).
  Vorteil: funktioniert weltweit und kontrolliert die Zonenzahl (Matrixgröße!).
- **UI:** Bounding-Box/Polygon zeichnen oder Gemeinde per Suche (Nominatim) wählen; Zonierungs-Modus
  und Auflösung als Parameter.

### B. Attraktivitäten je Zone — vollständig ableitbar

- **Benötigt:** `attractivities.csv` (Attraktivität je Zone und Aktivitätstyp: Arbeit, Einkauf,
  Freizeit, Bildung …).
- **Stufe öffentlich:** **OSM2Attractiveness** (KIT-eigenes Tool!) – OSM-PBF + Zonenlayer (GeoJSON)
  → zonale Indikatoren als CSV/GPKG. Direkt als Pipeline-Stufe einbindbar (Python/R, ggf. zunächst
  eigene schlanke Reimplementierung der POI-Aggregation, da das Tool Windows-orientiert ist).
- **Stufe synthetisch:** Proportional zu Gebäudegrundflächen/Landnutzung aus OSM, plus Rauschen.

### C. Strukturdaten / Synthese-Randsummen — öffentlich verfügbar, synthetisch ersetzbar

- **Benötigt:** `SynthesisZoneTargets.csv` – je Zone: Einwohner nach Alter/Geschlecht,
  Haushalte nach Größe, Erwerbstätige.
- **Stufe öffentlich (DE):** **Zensus 2022 100-m-Gitter** (Einwohner, Alter, Haushaltsgrößen;
  CSV, Open Data) – auf Zonen aggregieren. Ergänzend regionalstatistik.de (Erwerbstätige).
- **Stufe synthetisch:** Einwohnerzahl ∝ Wohngebäudevolumen aus OSM × Dichteannahme je Gebietstyp;
  Alters-/Haushaltsverteilungen aus bundesweiten Durchschnittswerten (mitgeliefert) + Zufallsstreuung.

### D. Verhaltens-Template (Mobilitätserhebung) — **die kritische, nicht ableitbare Eingabe**

- **Benötigt:** `SurveyPopulation.csv` – Stichprobe von Haushalten/Personen mit Wochenmobilität
  (Vorlage für IPU-Reweighting und Aktivitätenpläne). Klassisch: MiD 2017 / Deutsches Mobilitätspanel.
- **Problem:** MiD/MOP sind nutzungs-, aber nicht weiterverbreitungs-lizenziert → können nicht im
  Produkt mitgeliefert werden.
- **Lösung (dreistufig):**
  1. *Echt:* Nutzer/Institution legt eigene MiD-/MOP-Datei im definierten Schema ab (Provider-Slot).
  2. *Synthetisches Default-Template:* Wir generieren einmalig eine kunstvolle Stichprobe
     (z. B. via actiTopp-Verteilungen + publizierte MiD-Kennziffern: Wegehäufigkeiten, Modal Split,
     Aktivitätendauern) und liefern sie als frei verteilbaren Default mit. Für Demonstrations- und
     Logistik-Szenarien (wo die Paketnachfrage ohnehin parametrisch ist) ausreichend.
  3. *Tutorial-Daten:* Das mobiTopp-Tutorial (Rastatt) enthält bereits eine `SurveyPopulation.csv`
     – Lizenz prüfen; ggf. als Startpunkt für Phase 0/1 nutzbar.

### E. Pendlermatrix — öffentlich verfügbar, synthetisch ersetzbar

- **Stufe öffentlich (DE):** Pendlerstatistik der Bundesagentur für Arbeit (Gemeindeebene, Open Data).
- **Aktueller Importvertrag:** normalisierte CSV mit
  `homeLat;homeLon;workLat;workLon;commuters` **oder** direkter CSV-Export
  `Auspendler Gemeinden` aus der
  [BA-Pendlerstatistik](https://statistik.arbeitsagentur.de/DE/Navigation/Statistiken/Interaktive-Statistiken/Pendler/Pendler-Nav.html).
  Der Adapter erkennt den amtlichen Dialekt, behält ausschließlich achtstellige Gemeinde-
  relationen und löst die Schlüssel über die
  [BKG-VG250-Gemeindepunkte](https://gdz.bkg.bund.de/index.php/default/digitale-geodaten/verwaltungsgebiete/verwaltungsgebiete-1-250-000-stand-01-01-vg250-01-01.html)
  auf. Die normalisierten Koordinaten bleiben unabhängig von laufabhängigen Zonen-IDs.
  Der BA-Auspendlerexport enthält keine innergemeindlichen Beschäftigten und ist daher eine
  bedingte Zielverteilung. Das Backend ordnet beim Upload automatisch die passende Außenquote
  aus den versionierten BA-Gemeindedaten 2025 zu. Externe
  Ziele werden derzeit auf acht richtungsgetrennte, pendlergewichtete Arbeitsortanker verdichtet;
  die unbehandelte WORK-Gravitationsverteilung wird vor deren Verschiebung fixiert.
- **Automatischer Arbeitsplatz-Randwert:** Die gebündelte Referenz enthält 10.152 veröffentlichte
  Gemeindewerte aus den BA-Gemeindedaten 2025 (Stichtag 30.06.2025); URL und SHA-256 der
  Originaldatei stehen im Ressourcenheader. 600 amtlich geheim gehaltene Werte fehlen bewusst.
  Ein älterer Flat-File-Import der Regionalstatistik-Tabelle `13111-08-02-5-B` bleibt per API
  rückwärtskompatibel.
- **Stufe synthetisch:** Gravitationsmodell über Einwohner × Arbeitsplatz-Attraktivität × Distanzwiderstand.

### F. Reisezeit-/Kosten-/Distanzmatrizen — vollständig berechenbar

- **Benötigt:** je Modus `*_TTC`, `*_COST`, `*_DIS` als Visum `.mtx.bz2` + zwei Matrix-YAMLs.
- **MIV/Rad/Fuß:** Routing-Engine auf OSM-Auszug der Region – Empfehlung **GraphHopper**
  (JVM → passt in den Stack, Matrix-API offline) oder OSRM/Valhalla; Zonen-Zentroide
  (einwohnergewichtet) als Quellen/Ziele.
- **ÖV:** GTFS-Routing (R5/r5py oder GraphHopper-PT) auf offenen GTFS-Feeds – für DE der
  bundesweite **DELFI/gtfs.de-Feed**; daraus `PUT_TTC/ACT/EGT` und Takt-basierte Wartezeiten.
- **Kosten:** parametrisch (€/km Pkw, ÖV-Tarifmodell als einfache Zonen-/Distanzformel) – UI-Parameter.
- **Format:** Visum-Matrix-Writer aus **mobiToppIO** nutzen (sonst: Format ist dokumentiert/simpel).
- Achtung O(n²): Zonenzahl begrenzen (z. B. ≤ 500 Zonen im MVP) bzw. Auflösung an Regionsgröße koppeln.

### G. Verhaltensmodell-Parameter (Ziel-/Moduswahl) — Defaults mitliefern

- Tutorial-Modelle (Rastatt) als kalibrierte Defaults übernehmen; ausgewählte Stellschrauben
  (z. B. ÖV-Affinität, Kostenempfindlichkeit) als UI-Parameter exponieren. Keine Pflicht-Eingabe.

### H. Logistik-Ebene (logiTopp) — fast vollständig parametrisch/synthetisch

| Eingabe | Quelle |
|---|---|
| Paketaufkommen je Person (Verteilung, Min/Max) | **UI-Parameter** (Defaults: ~0,75 Pakete/Woche, σ 0,5) |
| Zieltyp-Anteile (HOME/WORK/PACKSTATION) | UI-Parameter |
| Verteilzentren (Lage, Mitarbeiter, Marktanteil) | (1) Nutzer setzt Marker auf Karte, (2) OSM-POIs (Logistik/Industriegebiete), (3) zufällig an Gewerbeflächen |
| Zustelldienste & Marktanteile | UI-Parameter (Defaults: DE-Marktanteile) |
| Effizienzprofile (Lastenrad vs. Transporter) | mitgelieferte Profile, UI-wählbar |
| Packstationen | OSM (`amenity=parcel_locker` ist gut gepflegt) oder zufällig |

→ **Genau die Logistik-Ebene, um die es in SyntheSis geht, ist am besten synthetisierbar.**
Die teuren „echten" Daten betreffen nur die Hintergrund-Bevölkerung.

## Minimal-Set zusammengefasst

Vom Nutzer zwingend nur: **Region + gewünschte Zonenauflösung + Logistik-Parameter (Defaults vorhanden)**.
Alles andere beschafft oder generiert die Pipeline selbst:

| # | Artefakt | automatisch aus | Fallback |
|---|---|---|---|
| 1 | Zonen | OSM-Grenzen | Hex-Raster |
| 2 | Attraktivitäten | OSM POIs (OSM2Attractiveness) | Gebäudeflächen + Rauschen |
| 3 | Strukturdaten | Zensus-2022-Raster | OSM-Gebäude × Dichte |
| 4 | Verhaltens-Template | Nutzer-Upload (MiD/MOP) | **synthetisches Default-Template** |
| 5 | Pendlermatrix | BA-Statistik | Gravitationsmodell |
| 6 | Matrizen | GraphHopper (OSM) + R5 (GTFS) | – (immer berechenbar) |
| 7 | Wahlmodell-Parameter | Tutorial-Defaults | – |
| 8 | Logistik (Depots, Pakete, Flotten) | OSM-POIs + UI | Zufall mit Seed |

## Verifizierte Bezugsquellen & Integrationsstand (Stand: 2026-07-17)

Web-Recherche zuletzt 2026-08-03, alle Quellen geprüft (Bezugsweg, Format, Lizenz). Die Status-Spalte
sagt, was die Pipeline heute damit macht.

### Deutschland-/europaweit

| Quelle | Inhalt / Format | Lizenz | Status in der Pipeline |
|---|---|---|---|
| **Zensus 2022 Gitterdaten** — `destatis.de/static/DE/zensus/gitterdaten/` (zensus2022.de leitet inzwischen auf destatis um) | Einwohner, Altersklassen, Ø-Haushaltsgröße, Wohnungen, Miete u. a.; 100 m/1 km/10 km, CSV in EPSG:3035 | dl-de/by-2-0 | ✅ Bevölkerungszahl automatisch: versionierter URL+SHA-256-Download, zentraler Cache, 100-m-Bbox-Filter und Manifest-Provenienz (`CensusPopulationSource`); ✅ Upload-Slot für alternative/neuere Raster; ⏳ Alter/Haushalte als Synthese-Randsummen (P3) |
| **Eurostat Census Grid 2021** — GISCO, `ec.europa.eu/eurostat/web/gisco/geodata/grids` | Bevölkerung EU-weit (30 Länder), 1 km; GPKG/CSV/Parquet, EPSG:3035 | CC BY | ✅ Upload-Slot (Dialekt `GRD_ID`+`T`, seit 2026-07-17); passt verlustfrei auf die INSPIRE-Zonierung |
| **gtfs.de / DELFI** — `download.gtfs.de` | GTFS deutschlandweit, täglich neu; Subsets de_full/de_nv/de_rv/de_fv; de_nv ~223 MB (Stand 2026-07) | CC BY 4.0 | ✅ Default-Registry-Eintrag `gtfs-de-nahverkehr` (nach Verbund-Feeds; über `max-feed-mb`-Default → Hinweis statt Auto-Download) |
| **Regionalstatistik, Tabelle 13111-08-02-5-B** — `regionalstatistik.de` | SvB am Arbeits-/Wohnort sowie Ein-/Auspendelnde je Gemeinde; Flat-File-CSV | dl-de/by-2-0 | ✅ direkter Flat-File-Import bleibt als API-/Legacy-Pfad; der vollständige GENESIS-Export ist für GAST nicht freigeschaltet |
| **BA-Pendlerstatistik / Pendleratlas + BA-Gemeindedaten 2025** — `statistik.arbeitsagentur.de` | Ein-/Auspendler-Verflechtungen und Wohnortbeschäftigte je Gemeinde, jährlich | frei mit Quellenvermerk | ✅ direkter BA-Dialekt + normalisierter OD-Upload; AGS→Punkt über amtliches BKG VG250; ✅ automatische Außenquote aus 10.152 versionierten Gemeindewerten, Original-URL+SHA-256 belegt; 600 geheim gehaltene Werte bewusst nicht ersetzt |
| **MiD 2023** — `mobilitaet-in-deutschland.de` (Ergebnisbericht seit 2025-11-21) | Modal-Split (Fuß 26 %, Rad 11 %, ÖV 11 %), Wegelängen, RegioStaR-Auswertungen | Berichte frei; Mikrodaten nur lizenziert | ⏳ offen (P5): Kalibrieranker-Update (heute MiD 2017 — eigener Change-Control-Zyklus, verschiebt Kalibrierziele); Mikrodaten bleiben Provider-Slot (Ebene D) |
| **BASt-Dauerzählstellen** — `bast.de` | 2 116 automatische Zählstellen (BAB + Bundesstraßen), Stundenwerte ab 2003 | offen | ⏳ offen (P5): SUMO-Validierung (edgeData vs. Zählwerte) |
| **Transitous / european-transport-feeds** — `transitous.org`, `eu.data.public-transport.earth` | >1 800 GTFS-Feeds aus 55 Ländern; EU-NAP-Verzeichnis | je Feed | Registry-Quelle für Europa-Szenarien (manuell eintragen) |

### NRW / kommunal (Beispiel Dortmund)

- **MOBIDROM Datenplattform** (`mobilitaetsdaten.nrw`, live seit 2025-05-23, Registrierung,
  DATEX II/OCPI): dynamische **Angebotsdaten** (Verkehrslage, Baustellen, Parken, Laden) — für
  die Nachfrageerzeugung ungeeignet, nützlich als Validierungsquelle (NRW-Zählwerte).
- **Stadt Dortmund** (`dortmund.opendatasoft.com`, 822 Datensätze, einheitliche Explore-API,
  überwiegend dl-de): `fb62-statistischebezirke` (62 Bezirks-Polygone), kleinräumige
  Bevölkerungsstatistik (Alter/Geschlecht/Migration je Bezirk),
  `fb61-mobilitatsbefragung-2019-personen/-haushalte` (10 296 Personen, **dl-de/zero** —
  Nutzungshäufigkeiten/Einstellungen, KEINE Wegetagebücher → lokaler Kalibrier-/Validierungs-
  anker, kein SurveyPopulation-Ersatz), Verkehrszählungs-Zählstellenplan, Rad-Dauerzählstelle,
  Parkplatzsensoren-Historie. Muster gilt für andere Opendatasoft-/CKAN-Kommunen; Andockpunkt
  bleibt der generische lat/lon-CSV-Upload.

### Integrationspfade (priorisiert)

1. **P1 ✅ (2026-07-17):** GTFS-Registry um den gtfs.de-DE-Feed ergänzt (Verbund-Feeds gewinnen).
2. **P2a ✅ (2026-07-17):** Eurostat-/INSPIRE-ID-Dialekt im `PopulationGridParser`.
3. **P2b ✅ (2026-08-25):** Zensus-Auto-Fetch (Download + SHA-256-Prüfung + Cache + Bbox-Filter) → Qualitätsstufe „öffentlich" ohne Upload; Stichtag, URL, Version, Lizenz und Hash im Manifest.
4. **P3 ⏳:** Zensus-Altersklassen/Haushaltsgrößen als echte Synthese-Randsummen (auch MobileCity `REGdistricts`).
5. **P4 ✅/🟡 (2026-08-03):** Normalisierter Pendler-OD-Upload, direkter BA-CSV-Adapter mit
   BKG-VG250-Auflösung und Regionalstatistik-13111-Randwert umgesetzt. Drei kontrollierte Seeds
   befördern beide Datenhebel; offen bleibt die Übertragung auf weitere Regionen.
6. **P5 ⏳:** Zähldaten-Validierung (BASt/Dortmund) + MiD-2023-Anker-Update.
