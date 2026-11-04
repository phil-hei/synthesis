# Quellenlaufbericht im Studio

Der Arbeitsbereich **Daten & Quellen → Lauf prüfen** zeigt seit 2026-11-04 zusätzlich
zur Eingangsprüfung einen lesbaren Bericht des zuletzt gestarteten Quellenlaufs.
Er wird auch bei fehlgeschlagenen Läufen geschrieben. Die neue Darstellung gilt
für die drei vorhandenen Aspekte; sie migriert keine weiteren Quellenfamilien.

## Bedienung

1. Quellenplan konfigurieren, prüfen und Szenario speichern.
2. Generierung starten. Im Arbeitsbereich **Lauf prüfen** den Laufbericht öffnen.
   Laufbeginn, Ende, Status und eventueller Fehler stehen über den Teilbeiträgen.
3. Mengenbilanzen prüfen: Bevölkerungsziel und Haushaltsrundung, einzelne
   Arbeitsplatzregister samt Außen-/verdrängten Mengen sowie erzeugte Schwerlastfahrten.
   Modellgewichte werden nicht als gezählte Arbeitsplätze addiert.
4. Teileherkunft aufklappen: Quellrevision, Lizenz, Evidenz, Ersatzrolle,
   Verarbeitungsschritt, Eingänge und betroffene Modellzonen bleiben getrennt.
5. Zielausführung separat prüfen. Eine erzeugte Fahrt belegt weder erfolgreiches
   Routing noch ihren Abschluss. Bei SUMO stehen angeforderte, geroutete,
   gestartete und abgeschlossene Bewegungen sowie Teleports nebeneinander.

Die Anzeige aktualisiert sich beim Öffnen und bei einer Änderung des beobachteten
Laufstatus/-zeitpunkts. **Laufbericht aktualisieren** lädt erneut. Ein Ladefehler
entfernt zuvor angezeigte Daten. Ein Szenariowechsel beendet ausstehende Anfragen.

## Persistierter Vertrag

`sources/resolution.json` trägt den Vertrag `synthesis-source-run-resolution-v1`.
Die bisherigen Schlüssel `decisions`, `workplaces`, `population`, `freight`,
`populationSource` und `populationDistribution` bleiben erhalten, soweit vorhanden.
Hinzu kommen:

| Feld | Bedeutung |
|---|---|
| `run` | Szenario-/Laufkennung, Beginn/Ende, Status/Fehler, Seed, Bezugsjahr sowie SHA-256 von `sources/plan.json` und `sources/lock.json` |
| `plan` | Quellenpolicy dieses Laufs; keine nachträgliche Szenarioauswahl |
| `populationSynthesis` | Tatsächlicher Haushaltsrundungsbericht nach erfolgreicher Bevölkerungssynthese |
| `failures` | Quellenfehler und Kandidatendiagnosen dieses Laufs |
| `targets` | Tatsächlicher Status von logiTopp und SUMO; bei erfolgreicher SUMO-Stufe aggregierte Ausführungsbilanz plus Dateipfad und SHA-256 der vollständigen Bilanz |

Der Bericht wird am Laufbeginn angelegt, bei der Manifeststufe aktualisiert und
vor Veröffentlichung des Laufendes atomar ersetzt. Fehlerläufe enthalten nur
ihre bereits ermittelten Teilbeiträge. Eingangsentscheidungen bleiben ausdrücklich
von angewendeten Mengen und Zieldiagnosen getrennt. Ein ausgeschalteter Quellenplan
entfernt den alten Quellenbericht beim nächsten Lauf. Ein abgewiesener Preflight
startet keinen Lauf und ersetzt daher keinen früheren Bericht.

Eine übersprungene, noch ausstehende oder fehlgeschlagene SUMO-Stufe bindet keine
vorhandene Ausführungsdatei als Erfolg dieses Laufs. Nur die erfolgreich beendete
Stufe übernimmt ihre aggregierte Bilanz; die große fahrtenbezogene Anforderungsliste
wird über den Hash referenziert. Ein optionaler Zielstufenfehler kann gemäß dem
bestehenden Jobvertrag bei insgesamt beendetem Job separat `FAILED` melden.

Die Browseransicht zeigt maximal 50 Schwerlastrelationen, je Herkunftsschritt die
ersten 20 Zonen und höchstens 4.000 Zeichen Parameter; Kürzungen sind beschriftet.
Der vollständige JSON-Bericht bleibt verlinkt. Bekannte Nullmengen bleiben null;
unbekannte oder geheimgehaltene Werte werden nicht als Nullmengen ergänzt.

## Grenzen

- Es gibt weiterhin nur den aktuellen Artefaktsatz pro Szenario. Dies ist kein
  Laufarchiv. Die Originaldateien werden beim nächsten Lauf ersetzt.
- Die Laufbindung bestätigt die verwendete Quellenpolicy/-revision. Sie behauptet
  keinen automatischen Gleichstand mit später bearbeiteten Szenariofeldern,
  Uploads oder neu geladenen Livequellen. Die Anzeige benennt diese Grenze.
- Alte Berichte ohne Laufvertrag werden nicht als aktuelle Bilanz interpretiert;
  sie erfordern eine erneute Generierung.
- Fachliche Quellenqualität, amtliche Stadt-/Landreferenzen, Migration privater
  und Paketnachfrage sowie vollständige MobileCity-Abnahme bleiben eigene Gates.

## Reproduzierbare Prüfungen

```bash
cd backend
mvn -o -Daether.syncContext.named.factory=noop \
  -Dtest=SourceRunReportTest,SourcePlanTrafficTest,SourcePlanTrafficIntegrationTest test
cd ../frontend
npm test
npm run build
```

`SourceRunReportTest` prüft Erfolg → neuer Lauf → Fehler → Abschalten, die atomar
gebundene Laufidentität, Quellenhashes, Haushaltsrundung und keine Übernahme alter
SUMO-Ergebnisse. Die nativen Migrationsfälle prüfen zusätzlich die Bindung an
wirklich ausgeführte SUMO-Berichte. Frontendtests prüfen Null-/Maskenwerte,
Registerteile, Evidenz und Zonen, technische Demonstration, Teilabschluss,
fehlende/alte/falsche Berichte sowie Abbruch veralteter Anfragen.

[Vorabkriterien](../../verification/2026-11-04-source-run-report-plan.json),
[ausgeführte Prüfungen und Grenzen](../../05-validierung.md#quellenlaufbericht-und-lesbare-teileherkunft-2026-11-04).

Browsernachweise: [Mengen und Haushaltsrundung](../../figures/source-run-report/completed.jpg),
[native Zielausführung](../../figures/source-run-report/target-execution.jpg),
[Fehlerlauf mit eigener Teilbilanz](../../figures/source-run-report/failed.jpg).
