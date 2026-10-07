# Separater actiTopp-Haushaltsadapter

Der Runner gehört zu SyntheSis. Der KIT-Kern wird unverändert aus dem in
`runtime/kit-sources.env` festgelegten Commit kompiliert; weder lokale Patches
noch unversionierte Runner im KIT-Checkout werden übernommen. Das Gradle-Build
für diese Einbindung ist ebenfalls ein eigenes SyntheSis-Projekt.

## Bauen

```sh
bash scripts/build-actitopp-adapter.sh
```

Voraussetzungen: KIT-Clone unter `validation/actitoppNG`, JDK 25 und die
Abhängigkeiten `kotlin-units:1.0.0` / `discrete-choice:1.1.0`. Der Clone wird nur
gelesen. Ein temporärer Export des Originalcommits wird nach dem Build entfernt.
Der bisherige Patch `legacy/actitopp-synthesis-v1` wird nicht mehr angewendet.
Bestehende lokale Änderungen werden dadurch auch nicht gelöscht.

Installation: `adapters/actitopp/build/install/synthesis-actitopp-adapter`.
Der Backend-Konfigurationsschlüssel `synthesis.actitopp.project-dir` zeigt auf
**diese Installation**, nicht auf den KIT-Clone.

## Haushaltsvertrag v2

Der Backend-Adapter verbindet `person.csv`, `household.csv` und `car.csv` anhand
der Haushalts-ID. Er prüft doppelte Personen/Haushalte, fehlende Zuordnungen und
Pkw ohne Besitzerhaushalt. Die Eingabe enthält:

```text
personId;householdId;age;employment;gender;householdSize;childrenUpTo10;childrenBelow18;numberOfCars;areaType
```

Alle Mitglieder werden in derselben KIT-Haushaltsinstanz angelegt, bevor die
Planung beginnt. Die Kinderzahlen und Pkw-Anzahl stammen aus dem tatsächlichen
synthetischen Haushalt. Unter Sechsjährige bleiben im Haushaltskontext; ihre
Wochenpläne kommen weiterhin aus dem Backend-Ersatzverfahren.

Der Siedlungstyp wird in den Bevölkerungseinstellungen gewählt und explizit als
Szenarioannahme übergeben. Standard für bestehende Szenarien: `PROVINCIAL`.
Eine automatische regionale Klassifikation und Pendeldistanzen sind noch offen.

Ausgabe:

- `actitopp-schedule.csv`: Aktivitäten für die spätere Pipeline.
- `actitopp-households.csv`: tatsächlich im KIT-Kern angelegte Haushaltskontexte.
- `actitopp.log`: Prozessausgabe; Haushalts-/Pkw-Zahlen stehen auch in der Jobzusammenfassung.

Die vollständige Eingabe garantiert keine gemeinsame konfliktfreie Pkw-Nutzung
oder Synchronisierung aller Haushaltspläne. Die vorhergehende
Bevölkerungssynthese kann bei exakter Personenbegrenzung weiterhin den letzten
gezogenen Poolhaushalt kürzen; das wird durch diesen Adapter nicht behoben.

## Prüfung

```sh
SYNTHESIS_RUN_ACTITOPP_TEST=true \
SYNTHESIS_ACTITOPP_DIR="$PWD/adapters/actitopp/build/install/synthesis-actitopp-adapter" \
mvn -f backend/pom.xml test
```

Der native Test prüft zwei Haushalte mit unterschiedlichen Pkw-Zahlen,
Jugendlichen und einem Kleinkind anhand der tatsächlich erzeugten Kontextdatei.
Der Prozess verwendet Java 25 über `ExternalJavaToolchain`.

Die Auslagerung von logiTopp und dem Rastatt-Laufprojekt erfolgt separat;
deren bestehende Patches werden durch diese Umstellung noch nicht abgelöst.
