# Lokale Datenpakete: ausführbare Beispiele

Beide Pakete enthalten **ausschließlich künstliche Testdaten**. Sie sind keine
realen Arbeitsplatzregister und begründen keine regionalen Mengenannahmen.

- `workplaces-de-csv.zip`: CSV, Beispielbox im Raum 8,2° Ost / 48,9° Nord.
- `workplaces-fr-json.zip`: JSON, Beispielbox im Raum 2,3° Ost / 48,9° Nord.

Im Studio unter **Daten & Quellen → Datenbestand → Datenpaket installieren**
ein ZIP auswählen. Danach bei **Beiträge → Arbeitsplätze → Neues Quellenprofil**
die Paketbeiträge in der gewünschten Reihenfolge anfügen und das Profil speichern.
Erst **Quellenplan verwenden → Quelle: Geordnetes Quellenprofil → Profilversion**
aktiviert die Auswahl für das Szenario. Die Planprüfung zeigt Auswahlgründe.

Die entpackten Ordner enthalten dieselben Manifest-/Datendateien zur Bearbeitung.
Nach Datenänderungen den SHA-256 der Quelldatei im Manifest aktualisieren, dann
`manifest.json` und die Datendatei direkt ins ZIP legen, ohne umschließenden Ordner.
Neue ZIP-Bytes erhalten eine neue Paketversion; bestehende Profile bleiben gebunden.

## Manifestvertrag

`contract`, `id`, `edition`, `title` und `sources` sind Pflicht. Jede Quelle hat
`id`, `schema`, `file`, `sha256`, `mapping` und `metadata`. Das Fachschema bestimmt
den Aspekt; Mapping und Metadaten folgen dem vorhandenen Importvertrag.
Vollständigkeitsangaben für Punktregister werden beim Installieren geprüft.

Grenzen: höchstens 5 MB ZIP, 15 MB entpackt, zehn Quellen und 16 Dateien;
Manifest höchstens 256 KB, Datendatei höchstens 5 MB. Zulässig sind die bestehenden
strukturierten CSV/TSV/XLSX/JSON/XML/DOCX-Reader. Unreferenzierte Dateien, Pfade,
Downloads und ausführbarer Code sind kein Bestandteil des Paketvertrags.
Neue Fachformate benötigen weiterhin einen registrierten Importadapter.

Quellenprofile speichern geordnete Kandidaten `pack:<Paket-SHA256>:<Quellen-ID>`
oder `revision:<Revisions-SHA256>`, explizite Abwahl und `onUnavailable: fail|next`.
Die abschließende Regel ist `fallback: none|legacy`. `next` betrifft fehlende
Quelldateien; beschädigte Daten werden nicht still übersprungen. Räumlich oder
zeitlich unpassende Kandidaten werden protokolliert und übersprungen.

Diese Installation ist szenariolokal. Globale Paketverwaltung, Dienstconnectoren,
kommunale AGS-Aggregate und freie Mehrquellenkomposition sind weitere Ausbauschritte.
