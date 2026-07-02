# SyntheSis Frontend

Angular-18-Frontend (Szenario-Studio) für die SyntheSis-Szenariomodellierung:
Kartenbasierte Auswahl einer Region (Leaflet/OpenStreetMap), Parametrierung von Bevölkerung
und Logistik (Depots per Klick auf der Karte), Start und Überwachung der Generierungspipeline
sowie Download der erzeugten mobiTopp/logiTopp-Artefakte.

## Voraussetzungen

- Node.js 20+
- laufendes SyntheSis-Backend auf `http://localhost:8090` (siehe [../backend/README.md](../backend/README.md))

## Starten

```bash
npm install   # nur beim ersten Mal
npm start
```

Die App läuft dann auf http://localhost:4300; `/api`-Requests werden per
`proxy.conf.json` an das Backend (Port 8090) weitergeleitet.

## Bedienung

1. **Region festlegen:** „Region zeichnen" aktivieren und mit gedrückter Maustaste ein Rechteck
   aufziehen — oder „Sichtbaren Ausschnitt übernehmen" (nimmt den aktuellen Karten-Viewport).
   Nach dem Setzen erscheinen automatisch die Zonen-Vorschau und eine Einwohner-Schätzung aus
   OSM-Siedlungsdaten (place-Nodes mit population-Tag), die das Einwohner-Feld vorbefüllt.
2. **Parametrieren:** Zellgröße (Kantenlänge einer Rasterzelle = einer Verkehrszone), Einwohner,
   Stichprobenanteil, Seed, Paketaufkommen und Zielanteile; optional Depots über „Depot auf
   Karte setzen" platzieren. Ohne eigene Depots nutzt die Pipeline echte OSM-Logistikstandorte
   bzw. platziert automatisch. Alle Felder haben Tooltips (Maus über das Label halten).
3. **Generieren:** „Speichern" → „Nachfragemodell generieren"; rechts erscheinen Pipeline-Status,
   Ergebnis-Kennzahlen und die Artefaktliste mit Download-Links.

## Projektstruktur

```text
src/app/core/models      DTO-Interfaces passend zur Backend-API
src/app/core/services    REST-Client (ApiService)
src/app/features/studio  Szenario-Studio (Karte, Formulare, Pipeline-Monitor, Artefakte)
```

## Build

```bash
npm run build   # Produktions-Build nach dist/synthesis-frontend
```
