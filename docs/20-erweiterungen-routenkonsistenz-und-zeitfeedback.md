# Erweiterungen: Routenkonsistenz, Zeitfeedback und Modellvergleich

Stand: 07.10.2026. Geprüft anhand von `GenerationService.stageNetworkFeedback`,
`SumoRunner.computeOdFactors`, `RoutingEngine` und der KIT-Rescheduling-Tests.

## Kurze Erweiterungspunkte für die Folien

- Dialogbasierter Szenarioassistent
- Einheitliche Routenbasis für GraphHopper und SUMO
- Verspätungen in Aktivitäten zurückführen
- GTFS-basierte ÖPNV-Reisematrizen
- Regionale Verhaltenskalibrierung
- Fahrzeugkonfigurationen importieren
- Pendler- und Umlandverkehr ergänzen
- Verkehrsmittelmodelle vergleichen
- Emissionen und Energieverbrauch auswerten
- Logistikexport für MobileCity erweitern

Die Liste benennt Erweiterungen; einzelne Grundlagen sind bereits vorhanden.
Fahrzeugimport hat beispielsweise schon eine Backend-Schnittstelle, aber noch
keinen vollständigen Upload- und Zuordnungsablauf in der UI. GTFS-Daten werden
bereits eingebunden; flächendeckende fahrplanbasierte Reisealternativen für die
Nachfrage sind davon zu unterscheiden.

## Gemeinsame Straßenrouten: nächster Umsetzungsschritt

Gemeinsames OSM allein garantiert keine identischen Routen. Aktuell gibt
GraphHopper nur Entfernung und Zeit seiner Zonenrouten weiter. SUMO berechnet
auf seinem importierten Netz erneut konkrete Routen. Damit können Unterschiede
zwischen Basiszeit und SUMO-Zeit neben Verkehrsbelastung auch Routenwahl,
Start-/Zielpunkte und unterschiedliche Netzaufbereitung enthalten.

Ziel: Eine nachvollziehbare Straßenfolge für denselben Weg. SUMO misst deren
simulierte Reisezeit. Das bedeutet nicht, dass in einer späteren Iteration keine
bewusste Routenänderung zulässig wäre; diese muss als solche ausgewiesen werden.

Umsetzung außerhalb der KIT-Komponenten:

1. GraphHopper-Pfade mit Profil, Start-/Zielpunkten, Netzversion und OSM-Bezügen
   speichern; interne GraphHopper-Kanten-IDs allein sind nicht übertragbar.
2. Dieselben konkreten Punkte für Personenwege und Zustellstopps verwenden.
   Eine Route zwischen Zonenschwerpunkten ist keine exakte Haus-zu-Haus-Route.
3. SUMO-Kanten beim Netzimport auf ihre OSM-Bezüge abbilden. Aufteilungen,
   zusammengeführte Kreuzungen und interne Verbindungskanten berücksichtigen.
4. Einen eigenen Adapter für die Straßenfolge erstellen und fahrzeugabhängige
   Befahrbarkeit, Richtungen und zusammenhängende Verbindungen prüfen.
5. Mappingfehler ausweisen; eine neu berechnete Ersatzroute kennzeichnen.
6. Mit einem kleinen Gebiet abnehmen: Straßenfolge vergleichen, freie SUMO-Zeit
   und belastete SUMO-Zeit getrennt ausweisen. Erst danach auf größere Gebiete
   erweitern.

Die Konsistenz der Reisematrizen ist zusätzlich zu prüfen: Auch bei identischem
Weg können GraphHopper-Profile und SUMO-Fahrzeuge unterschiedliche freie Zeiten
liefern. Eine gemeinsame Routenbasis entfernt nicht automatisch diese Differenz.

### Experimenteller Adapter umgesetzt (07.10.2026)

`population.sharedRoadRoutes=false` ist eine neue, ausdrücklich optionale
Szenarioeinstellung. Im Studio ist sie bei gewähltem SUMO als „Gemeinsame
Straßenrouten verwenden“ sichtbar. Sie benötigt `useRealRouting=true`.
Der Standardpfad oben bleibt bestehen; der Adapter überträgt konkrete Wege aus
GraphHopper für Pkw, Rad, Lieferwagen und Lastenrad auf dasselbe OSM-Netz in SUMO.
Es werden die tatsächlichen Start-/Zielkoordinaten der Nachfragewege bzw.
Zustell-Legs verwendet, keine Zonenschwerpunkte als Ersatz.

Der GraphHopper-Import erhält OSM-Weg-IDs über eine eigene Parser-Erweiterung.
Der SUMO-Import bewahrt Abschnitte (`geometry.remove=false`,
`junctions.join=false`), importiert Radzugangsregeln (`osm.bike-access`) und
exportiert ursprüngliche OSM-Bezüge sowie geografische Kantengeometrien.
Der Adapter prüft Fahrtrichtung, geometrische Nähe (15 m), Fahrzeugklassen und
zulässige Fahrstreifenverbindungen einschließlich interner Kreuzungskanten.
Die gesamte ursprüngliche Geometrie wird in höchstens 15-m-Abständen auf
Abdeckung geprüft, damit gleiche Weg-IDs keine Abkürzung verdecken.
Geteilte Kanten werden verbunden; mehrere OSM-Bezüge je Kante werden berücksichtigt.
`duarouter --keep-route-probability 1` erhält explizite Routen. Verändert oder
verwirft der Router eine bereits zugeordnete Route, scheitert die SUMO-Stufe sichtbar.
Eine nicht zuordenbare Route wird als `fallback-duarouter` ausgewiesen und weiterhin
auf dem SUMO-Netz berechnet. Die Zuordnungs-/Fallbackzahlen erscheinen im Job-Log.

Artefakte unter `sumo/`:

- `shared-road-routes.jsonl`: Profil, konkrete Punkte, gerichtete OSM-Wegfolge,
  Netzhashes, SUMO-Kanten und Anfangs-/Endpositionen pro Fahrzeug.
- `shared-road-routes-summary.json`: Zuordnungen, Ersatzrouten, nach dem Router
  geprüfte Routen, GraphHopper-Version und Importannahmen.
- `shared-road-times.jsonl`: GraphHopper-Zeit, statische SUMO-Kantenzeit-Schätzung
  und tatsächliche TripInfo-Zeiten getrennt. Fehlende Ankünfte heißen
  `missing-tripinfo` und werden nicht als null Sekunden interpretiert.

**Grenzen:** Die statische Kantenzeit berücksichtigt keine Kreuzungswartezeiten,
Fahrzeugbeschleunigung oder individuelle Geschwindigkeitsfaktoren und ist kein
separater unbelasteter Simulationslauf. Das kleine Rastatt-Beispiel prüft die
technische Kette, keine Stauwirkung oder regionale Abdeckung. Die Zuordnung ist
konservativ; jedes Netz muss auf seine Fallbackquote geprüft werden. Der geänderte
Netzimport erzeugt mehr Kanten und benötigt zusätzliche Rechenzeit. Fußgänger,
ÖV und Rail/Tram bleiben im bisherigen Pfad. Die Tourreihenfolge in logiTopp
wird weiterhin geometrisch geplant; stoppspezifische Netzzeiten in der
Tourplanung sind ein weiterer Schritt. Die zonalen Matrixzeiten bleiben
zonale Näherungen und werden durch diese Routeübertragung nicht zu Haus-zu-Haus-Zeiten.

Quellen: [SUMO-Netzexport](https://sumo.dlr.de/docs/Networks/Export.html),
[explizite Fahrzeugrouten](https://sumo.dlr.de/docs/Definition_of_Vehicles,_Vehicle_Types,_and_Routes.html).

## Zeitfeedback: schon vorhanden und noch offen

### Bestehende Rückkopplung über Matrizen

Optional (`population.sumoFeedback`, UI: „Stau auf die Nachfrage zurückwirken
lassen“) verwendet der Ablauf SUMO-Kantenzeiten und routet Zonenpaare einmal
frei und einmal mit diesen Zeiten. Das Verhältnis wird auf 1–3 begrenzt,
gedämpft in die initiale GraphHopper-Pkw-Matrix übernommen und
mobiTopp/logiTopp erneut ausgeführt; anschließend folgt erneut SUMO.
Die voreingestellte Obergrenze beträgt drei Iterationen und 150 Zonen.

Das ist bereits eine indirekte Anpassung der zeitlichen Nachfrageausführung.
Die unveränderte mobiTopp-Rescheduling-Strategie behandelt Konflikte im neuen
Lauf. Es ist keine Übernahme der konkreten SUMO-Ankunft jeder einzelnen Person,
keine zeitabhängige Matrix für alle Verkehrsmittel und keine nachgewiesene
Gleichgewichtslösung. Kleine Änderungen zwischen Iterationen sind allein kein
Nachweis realistischer oder stabiler Ergebnisse.

### Geplante Übernahme konkreter Verspätungen

Diese Erweiterung ist größer als eine zusätzliche Matrixübergabe und wird daher
für später festgehalten. Keine Änderungen an den KIT-Kernen vornehmen.

1. Stabile IDs für Person, Tag, Weg und SUMO-Fahrt durch alle Übergaben führen.
2. Geplante und simulierte Abfahrt/Ankunft ausgeben; abgeschlossene, nicht
   abgeschlossene, verworfene und gegebenenfalls teleportierte Fahrten trennen.
3. In einem unabhängigen Adapter die gemessene Ankunft einem Weg zuordnen.
4. Zuerst einen begrenzten Wiederholungslauf erproben: gemessene Wegzeiten in
   die Nachfrageausführung einspeisen und vorhandene KIT-Rescheduling-Hooks
   auf ihre Eignung prüfen. Keine Wochenpläne nur in den Ausgabedateien verschieben.
5. Fachregeln festlegen: feste Termine, Mindestaufenthalt, flexible Aktivitäten,
   Tagesgrenzen und Abhängigkeiten innerhalb eines Haushalts. Die vorhandene
   Strategie plant keinen vollständig neuen actiTopp-Wochenplan.
6. Neue Abfahrten und Verkehrsbelastung in einer begrenzten weiteren SUMO-Runde
   prüfen. Iterationslimit und ungelöste Konflikte sichtbar ausgeben.

Abnahmefälle: verspätete Heimkehr bei erhaltener Morgenabfahrt; nicht mehr
auffangbare Verzögerung vor einem festen Termin; Folgetag; fehlende SUMO-Ankunft;
Begleitwege innerhalb eines Haushalts. Online-Kopplung mit sofortigem Austausch
während beider Simulationen bleibt ein eigener, wesentlich größerer Schritt.

## Vergleich verschiedener Verkehrsmittelmodelle

Gesamttitel der Folie: **Verkehrsmittelvergleich**.
Leitfrage: **„Wie unterscheiden sich die Verkehrsmittelentscheidungen?“**

- Basismodell: mobiTopp-Verkehrsmittelwahl mit MNL (Multinomiales Logit-Modell).
- Optionaler alternativer Ansatz: LLM-Schätzung nach Entfernungsgruppen.
- Empirische Referenzen: MiD und regionale Erhebungen, soweit vergleichbar.
- Erweiterbar um weitere statistische oder regelbasierte Entscheidungsmodelle;
  deren Implementierung ist hiermit nicht als vorhanden behauptet.

MNL ist die Modellform innerhalb von mobiTopp, kein zusätzliches Programm.
MiD ist eine Erhebung, kein konkurrierendes Entscheidungsmodell. Die aktuelle
LLM-Schätzung erhält keinen vollständigen individuellen Haushaltskontext.
Vergleiche müssen dieselbe Region, Personengruppe, Verkehrsmittelabgrenzung und
Zeiteinheit verwenden. Der LLM-Zweig verändert die simulierten Wege nicht.


## Emissionen und Energieverbrauch aus SUMO

**Festgehalten:** SUMO-Emissionen systematisch auswerten und die Ergebnisse
gegebenenfalls als ergänzende Daten im MobileCity-Export bereitstellen.

### Bereits vorhanden

`SumoRunner` aktiviert Emissionsgeräte für alle Fahrzeuge und liest `CO2_abs`
aus `sumo/tripinfo.xml`. CO₂ wird von Milligramm nach Kilogramm umgerechnet und
in `sumo/summary.json` als `co2Kg` und `co2KgByType` ausgegeben. Bei aktivem
Fahrzeugkatalog enthält auch `vehicles/sumo-execution.json` die Aufteilung.
Das sind modellierte direkte Fahrzeugemissionen, keine Messwerte.

Die Standardklassen enthalten derzeit beispielsweise Benzin-Pkw Euro 4 und
Diesel-Lieferwagen Euro 4. Ein Fahrzeugkatalog kann eigene SUMO-Emissionsklassen
übergeben. Diese Annahmen müssen sichtbar sein; die Standardwerte sind kein
Nachweis einer regional repräsentativen Flotte.

### Nächste Umsetzung

1. Neben CO₂ auch NOₓ, CO, HC, PMx sowie Kraftstoff- und Stromverbrauch erfassen,
   soweit das gewählte SUMO-Modell sie unterstützt. Fehlende Werte von Nullwerten
   unterscheiden; Einheiten explizit speichern.
2. Ergebnisse je Fahrzeugtyp und Verkehrszweck sowie optional Straße/Zone und
   Zeitintervall aggregieren. Für räumliche Ergebnisse SUMO-Emissionsausgaben
   verwenden, nicht die gesamte Wegemission pauschal der Startzone zuordnen.
3. Nicht abgeschlossene Fahrten am Ende des simulierten Tages, verworfene und
   teleportierte Fahrten berücksichtigen und die Abdeckung ausweisen. Der aktuelle
   TripInfo-Abruf garantiert keine vollständige Tagesbilanz aller begonnenen Fahrten.
4. Simulierten Tag, Stichprobenanteil, Fahrzeugflotte, Emissionsmodell und finalen
   Feedback-Lauf als Herkunft dokumentieren. Eine Stichprobe nicht ungeprüft zu
   einer Vollsimulation hochrechnen; Verkehrsbelastung wirkt nicht linear.
5. Direkte Fahrzeugemissionen und Stromverbrauch getrennt behandeln. CO₂ aus
   Stromerzeugung braucht einen eigenen, dokumentierten Strommixfaktor;
   Lebenszyklus-, Abrieb- und weitere Umweltwirkungen nicht implizit behaupten.
6. MobileCity-Schnittstelle prüfen: Aktuell wird kein SUMO-Emissionsergebnis in
   den MobileCity-Export übernommen. Nur bei bestätigten passenden Zielfeldern
   native Modellparameter ableiten; Ergebniswerte nicht als Eingangskoeffizienten
   einsetzen. Andernfalls ein versioniertes, separat gekennzeichnetes Begleitartefakt
   mit Kennzahlen, Einheiten und Herkunft im Exportpaket vorsehen.

Abnahme: Einheiten und Summen aus SUMO-Rohdaten prüfen; Verbrenner und
Elektrofahrzeuge getrennt testen; zeitlich abgeschnittene Fahrten und fehlende
Modellwerte sichtbar behandeln; Exportvertrag auf Einlesbarkeit prüfen.

Folienpunkt für Schritt 8: **„Reisezeiten, Belastung & Emissionen“**.

Quellen: [SUMO TripInfo](https://sumo.dlr.de/docs/Simulation/Output/TripInfo.html),
[Emissionsausgabe](https://sumo.dlr.de/docs/Simulation/Output/EmissionOutput.html),
[Emissionsmodelle](https://sumo.dlr.de/docs/Models/Emissions.html).
