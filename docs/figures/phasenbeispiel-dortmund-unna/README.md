# Durchgängiges Beispiel für die fachlichen Phasen 6–8

- `phase-6.png` / `.svg`: echte GraphHopper-Kanten und Pkw-Reisezeiten; illustrative Standorte und Zellen.
- `phase-7.png` / `.svg`: illustrative Alex-Wege A → B → C → A und Zustellfolge D → A → E → D mit eigener Zustellzone E, Straßenrouten zur Darstellung mit GraphHopper berechnet.
- `phase-8.png`: Foliengrafik mit echtem SUMO-GUI-Screenshot einer frei gewählten Beispielnachfrage.
- `sumo-screenshot.png`: ursprünglicher Screenshot ohne Folienbeschriftung, t=180 s.
- `provenance.json`: Herkunft, Ausschnitt, Kennzahlen und Grenzen des Beispiels.

Die Bilder messen 2400 × 1350 Pixel. Phase 6 und 7 sind zusätzlich als editierbare SVG verfügbar. Keine Grafik behauptet eine kalibrierte Dortmund-Nachfrage, ein existierendes Depot oder eine neue vollständige logiTopp-Simulation. Fahrzeuge im SUMO-Screenshot sind für die Lesbarkeit vergrößert.

## Reproduktion

`sources/` enthält das tatsächlich verwendete SUMO-Netz und die Routen komprimiert sowie den GraphHopper-Geometrieexport. `sumo-gui -c sources/example.sumocfg` startet denselben Beispiellauf; Zufallsseed 42. Zum Screenshot bei Sekunde 180 verwendet `sources/gui_capture.py` die TraCI-Schnittstelle; es benötigt SUMO-Python-Tools und einen verfügbaren GUI-Desktop. Der derzeit eingetragene Tools-Pfad entspricht der lokalen macOS-Frameworkinstallation.

`sources/render.py` erzeugt die drei Foliengrafiken aus dem Geometrieexport und dem gespeicherten Screenshot (Python mit Pillow; lokale Arial-Schriften). GraphHopper-Kanten wurden mit `sources/GraphExample.java` erzeugt, kompiliert gegen den Backend-Abhängigkeitsklassenpfad. Der OSM-Download enthält die in `provenance.json` genannten Straßenklassen und ist deshalb ein Pkw-Beispiel, kein vollständiger Fuß-/Rad-/ÖPNV-Datensatz.

© OpenStreetMap-Mitwirkende. OSM-Daten und abgeleitete Netzdaten: ODbL. Die Standortnamen A–D und die zugehörige Nachfrage sind frei gewählt.

## Überarbeitete Grafiken für die Folien 12 und 18 (6. Oktober 2026)

- `folie-12-v2.png` / `.svg`: ohne übergeordneten Titel, Pkw-Basisreisezeiten, Einkaufsort C als Kandidat, beispielhafte Logistikparameter.
- `folie-18-v2.png` / `.svg`: ohne übergeordneten Titel, Alex’ Montag als Pkw-Beispiel; getrennte Zustelltour D → A → E → D an Alex’ Haushalt und eine andere Zustellzone.
- Reproduktion: `sources/render_slides_v2.py`. Dieselben gespeicherten GraphHopper-Geometrien; keine neue Nachfrage- oder Verkehrssimulation. PNG/SVG: 2400 × 1100 Pixel.
