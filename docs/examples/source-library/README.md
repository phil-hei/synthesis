# Datenpakete zwischen Szenarien wiederverwenden

Stand: 30.10.2026. Experimentelle Bibliothek einer SyntheSis-Installation.
Sie verwendet die vorhandenen typisierten Paket-/Importverträge und verändert die
Quellenauswahl eines Szenarios erst nach dessen ausdrücklicher Konfiguration.

## Bedienung

Im Studio „Daten & Quellen“ öffnen und „Datenbestand“ wählen:

1. Ein vorhandenes lokales Szenariopaket mit „In Bibliothek bereitstellen“ übernehmen.
   Alternativ ein ZIP über „Paket in Bibliothek speichern (ZIP)“ hochladen.
2. Ein anderes gespeichertes Szenario öffnen. Seine Datenbibliothek zeigt dieselben
   unveränderlichen Paketversionen dieser Installation.
3. „In dieses Szenario übernehmen“ kopiert die konkrete Version einschließlich
   Rohdateien und normalisierten Revisionen. Die Quelle ist damit noch nicht aktiv.
4. Unter „Beiträge“ eine kopierte Revision auswählen oder ein Quellenprofil aus den
   Paketquellen erstellen. Lückenregeln konfigurieren, Plan prüfen und speichern.

Gleiche ZIP-Bytes haben denselben Paket-SHA-256; gleiche kanonische Daten behalten
ihre Revisionshashes. Neue Inhalte erhalten eine eigene Version, auch bei gleichem
Paketnamen und gleicher Edition. Sie ersetzen keine Auswahl bestehender Szenarien.

Die Bibliothek ist auf die laufende Installation begrenzt. Sie ist kein öffentlicher
Marktplatz, Downloadcrawler oder automatischer Quellenempfehlungsdienst. Gemeinsame Quellenprofile mit aktiven Paketabhängigkeiten sind zusätzlich
[separat übernehmbar](../shared-source-profiles/README.md). Ihre Auswahl bleibt
szenariolokal. Ein unbekannter regionaler Geltungsbereich oder ein unpassendes
Quellenjahr wird durch die Installation nicht fachlich bestätigt; die vorhandene
Plan-/Laufprüfung entscheidet über die tatsächliche Verwendung.

## Portable Datenpakete und Abhängigkeiten

Es gilt `synthesis-data-pack-v1`: höchstens 5 MB ZIP, 1–10 Quellen, deklarierte
strukturierte Dateien, SHA-256 je Datei sowie typisierte Mappings/Metadaten.
Alle Quellen werden vor Veröffentlichung geprüft. Die bestehenden Reader für
CSV/TSV/XLSX, strukturierte JSON/XML/DOCX und GeoJSON werden wiederverwendet.
Die Bibliotheksprüfung besitzt keinen eigenen Formatparser.

Zusätzlich muss ein Bibliothekspaket **jede referenzierte Revision selbst enthalten**.
Gemeindeaggregate benötigen beispielsweise ihre Gewichts- oder Geometrierevision
im selben Paket. Die Referenzen verwenden die kanonischen Revisionshashes, keine
Dateipfade. Vertragstyp, Abhängigkeitsgraph und kommunaler Schlüssel-/Gebietsstandsjoin
werden vor Speicherung geprüft. Ein Aggregatpaket mit einer nur im Ursprungsszenario
vorhandenen Referenz wird als nicht portabel abgelehnt. Der bisherige szenariolokale
Paketvertrag bleibt verfügbar und wird dadurch nicht stillschweigend verschärft.

Nicht Bestandteil sind beliebige Fremdplugins, neue Fachverträge, Cloud-Synchronisierung,
eine amtliche/empirische Prüfung der Daten. Gemeinsame Quellenprofile bilden
den zusätzlichen Vertrag im verlinkten Profilbeispiel.

## API und Speicherung

| Aktion | HTTP |
|---|---|
| Bibliotheksversionen einschließlich sichtbarer Integritätsfehler | `GET /api/data-library/packs` |
| ZIP in Bibliothek speichern | `POST /api/data-library/packs`, Multipart-Feld `file` |
| Installierte Szenarioversion bereitstellen | `POST /api/scenarios/{scenario}/data/packs/{hash}/library` |
| Konkrete Bibliotheksversion in ein Szenario kopieren | `POST /api/scenarios/{scenario}/data/library/packs/{hash}/install` |

Zentrale ZIPs liegen unter `<synthesis.data-dir>/source-library/packs/<hash>.zip`.
Der Katalog wird aus den geprüften Inhalten abgeleitet; Name und Edition sind keine
änderlichen Aliase. Ungültige Dateien erscheinen mit `status=invalid` und Grund;
sie dürfen nicht übernommen werden. Ein defekter Eintrag blockiert die anderen
Versionen nicht. Ein kompletter Bibliotheks-Lesefehler wird in der UI angezeigt,
ohne den Szenariobestand unbenutzbar zu machen. Fehlende Version: HTTP 404;
beschädigte gespeicherte Version: HTTP 422.

Die Übernahme verwendet weiterhin die Szenariopfade `uploads/data-sources/packs/`
und `uploads/data-imports/revisions/`. Der Laufresolver liest ausschließlich diese
lokalen Eingaben und friert sie wie bisher ein. Er liest keine zentralen Pakete
nach. Eine fehlende oder beschädigte Bibliothekskopie verändert deshalb keinen
bereits übernommenen Stand. Eine beschädigte unveränderliche Zentraldatei wird bei
erneuter Veröffentlichung nicht automatisch überschrieben; ihre Wiederherstellung
ist eine bewusste lokale Dateireparatur.

## Ausführbare Versionsbeispiele

- [regional-cells-v1.zip](regional-cells-v1.zip) ist das bestehende
  [Rastermaskenbeispiel](../masked-cells/README.md): bekannte Zellen 30 und 0,
  zusätzlich fehlend, unterdrückt und nicht erhoben.
- [regional-cells-v2.zip](regional-cells-v2.zip) hat dieselbe Zellstruktur mit bekanntem
  Wert 50 statt 30 und eigener Edition/ZIP-Version.

Beide Dateien enthalten künstliche Vertragsdaten. Für den Testfall Region
8,33/49,199–8,42/49,213, EPSG:3035-Zonierung mit 1.000 m, Mengenanker 60 und
expliziten Lückenmodellen verwenden. V1 ergibt bekannte Bevölkerungsmenge 30 plus
Restmenge 30; V2 ergäbe 50 plus Restmenge 10. V2 nur veröffentlichen, um zu prüfen,
dass ein bereits an V1 gebundenes Szenario bei 30 plus 30 bleibt. Die Rundung auf
ganze Haushalte bleibt wie im Rastervertrag separat sichtbar.

[Vor dem Browserlauf festgelegter Prüffall](../../verification/2026-10-30-source-library-plan.json),
[ausgeführter Browsernachweis](../../verification/2026-10-30-source-library-ui.json).

[Ausgeführte Tests und genaue Beweisgrenzen](../../05-validierung.md#szenarioübergreifende-datenpaketbibliothek-2026-10-30),
[Abschlussnachweis](../../verification/2026-10-30-source-library-final.json).
