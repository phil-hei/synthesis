# Gemeinsame Quellenprofile mit Paketabhängigkeiten

Stand: 30.10.2026. Experimenteller Schnitt innerhalb einer SyntheSis-Installation.
Ein gemeinsames Quellenprofil ist eine versionierte Auswahlregel. Es überträgt
keine aktiven Szenarioeinstellungen und keine bereits ausgeführten nativen Modelle.

## Bedienung

Im Studio „Daten & Quellen → Datenbestand“:

1. Benötigte Datenpakete in der [Datenbibliothek](../source-library/README.md)
   bereitstellen. Jedes Paket muss seine eigenen Referenzrevisionen enthalten.
2. Ein gespeichertes Quellenprofil mit „Quellenprofil in Bibliothek bereitstellen“
   veröffentlichen. Alternativ seine Definition über „Quellenprofil in Bibliothek
   speichern (JSON)“ hochladen, höchstens 256 KB.
3. Im Zielscenario „Profil und Daten übernehmen“ wählen. Alle aktiven
   Paketabhängigkeiten werden geprüft und eingelesen, bevor die erste Paketkopie
   geschrieben wird. Profil- und Revisionshashes bleiben identisch.
4. Unter „Beiträge“ die übernommene konkrete Profilversion ausdrücklich für die
   gewünschten Aspekte wählen. Szenariobezogene Lückenregeln, Mengenanker und
   Verwendungszweck prüfen, Plan prüfen und speichern.

Die Veröffentlichung aktiviert nichts. Auch die Übernahme wählt kein Profil aus.
Ein neues zentrales Profil ersetzt keine bereits gewählte Version. Im Lauf werden
wie bisher ausschließlich die eigenen Eingaben des Szenarios aufgelöst; zentrale
Profildateien und Pakete sind keine zusätzliche Laufzeitabhängigkeit.

## Portabler Profilvertrag

Es gilt unverändert `synthesis-source-profile-v1` mit Kandidatenreihenfolge,
`excluded`, `onUnavailable`, `fallback` und gegebenenfalls `strategy`. Die vorhandene
kanonische Serialisierung erzeugt auch in der Bibliothek denselben Profilhash.
Alte Profile einschließlich erfasster Hash-Fixture bleiben lesbar.

- `pack:<ZIP-SHA-256>:<Quellen-ID>`: Jeder aktive Kandidat muss einen passenden
  Beitrag in einem vorhandenen, gültigen Bibliothekspaket referenzieren. Höchstens
  16 verschiedene aktive Paketversionen pro gemeinsamem Profil. Auch spätere
  aktive Kandidaten müssen für eine vollständige Übernahme vorhanden sein.
- `builtin:<Provider-ID>`: Auswahlregel für den vorhandenen nativen Provider. Seine
  Daten werden erst im Zielscenario aufgelöst. Regionale Eignung, Ausfälle und
  Fehler folgen den vorhandenen Plan-/Laufregeln. Insbesondere kopiert
  `builtin:upload-grid` keinen Upload aus dem Ursprungsszenario; er bezeichnet
  den Upload des Zielscenarios. Ein Profil ist kein Snapshot späterer Liveantworten.
- Ausgeschlossene Paketkandidaten werden weder gelesen noch kopiert. Ihre Abwahl
  und ihr Platz in der Reihenfolge bleiben erhalten.
- Direkte `revision:`-Kandidaten bleiben szenariolokal. Solche Profile werden für die
  gemeinsame Bibliothek abgelehnt, auch bei aktuell ausgeschlossenen Kandidaten.
  Für portable Datenbeiträge Paketquellen im Profileditor verwenden.

Ungültige oder fehlende Abhängigkeiten verhindern Veröffentlichung/Übernahme. Sie
werden im Bibliothekskatalog als Fehler sichtbar; andere Profile bleiben nutzbar.
Ein Ausfall der Profilbibliothek blockiert den übrigen Szenariobestand nicht.
Benötigte Pakete werden vor Übernahme als konkrete Byte-Snapshots eingelesen.
Ein Dateisystemfehler während des anschließenden Schreibens kann vorbereitete
Kopien zurücklassen; er aktiviert weder das Profil noch eine Quelle.

## API und Speicherung

| Aktion | HTTP |
|---|---|
| Gemeinsame Profile mit Abhängigkeitsliste/Fehlern | `GET /api/data-library/source-profiles` |
| Definition veröffentlichen | `POST /api/data-library/source-profiles`, JSON-Definition |
| Vorhandene Szenarioversion bereitstellen | `POST /api/scenarios/{scenario}/data/source-profiles/{hash}/library` |
| Profil samt aktiven Paketabhängigkeiten übernehmen | `POST /api/scenarios/{scenario}/data/library/source-profiles/{hash}/install` |

Zentrale Profile liegen unter
`<synthesis.data-dir>/source-library/profiles/<Profilhash>.json`. Übernommene
Profile verwenden weiterhin `uploads/data-sources/profiles/`; Pakete und Revisionen
ihre bisherigen Szenariopfade. HTTP 400: ungültiger/nicht portabler Vertrag;
404: Profil oder benötigtes Paket fehlt; 422: gespeicherte Daten beschädigt.
Die manuelle Bearbeitung einer zentralen Datei unter einem bestehenden Hash ist
keine Versionsänderung und wird abgelehnt.

## Ausführbarer Versionsfall

Zuerst [regional-cells-v1.zip](../source-library/regional-cells-v1.zip) in die
Datenbibliothek aufnehmen. Danach [profile-v1.json](profile-v1.json) über die UI
veröffentlichen. Es verwendet dasselbe Paket für Population und Work und enthält
je einen expliziten nachrangigen nativen Kandidaten. Ein einzelnes Paket wird bei
der Übernahme nur einmal kopiert.

Für den künstlichen Fall Region 8,33/49,199–8,42/49,213, EPSG:3035-Zonierung 1.000 m,
Seed 42 und Bevölkerungsanker 60 verwenden. Beide Aspekte an V1 binden. Population:
`gaps=providers`, nur `uniform`; Work: `gaps=providers`, nur `synthetic`. Offline-
Template-Verhalten, Realrouting und externe Simulation deaktivieren. Bekannt bleiben
30 und der Nullwert, die Bevölkerungslücke erhält Restmenge 30.

[profile-v2.json](profile-v2.json) priorisiert die nativen Quellen und hat einen
anderen Hash. Allein seine Veröffentlichung verändert ein V1-Szenario nicht.
Die Beispiele sind künstliche Vertragsdaten und Konfigurationsregeln, keine
empirische Modellfreigabe. Native Quellen und weitere Fachverträge werden durch
Profilkopien nicht automatisch migriert oder fachlich akzeptiert.

[Vorab festgelegter Browserfall](../../verification/2026-10-30-shared-source-profiles-plan.json),
[ausgeführter UI-Nachweis](../../verification/2026-10-30-shared-source-profiles-ui.json).
