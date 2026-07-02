# 04 – Roadmap, MVP-Schnitt, Risiken

## Grundsatzentscheidung: mobiTopp-Version

| Option | Pro | Contra |
|---|---|---|
| **A: Legacy-Stack** (`mobiTopp_legacy` + `logitopp`) | Logistik funktioniert heute; logiTopp-Beispiele vorhanden | alte Codebasis (Java 12, Gradle 6), Doku dünner |
| **B: Neuer Kotlin-Stack** (`mobitopp` main + Tutorial) | aktiv entwickelt, sauberes CSV/YAML-Interface, Tutorial als Blaupause | **kein logiTopp** – Kernziel Logistik fehlt |
| **C: Hybrid (Empfehlung)** | Pipeline erzeugt das neue CSV/Matrix-Layout; ein dünner Adapter speist daraus den Legacy-Stack für logiTopp-Läufe | Adapteraufwand; zwei Stacks im Container |

**Empfehlung C:** Datenpipeline zukunftssicher am neuen Format ausrichten, Logistik-Simulation
vorerst auf Legacy rechnen. Parallel **Kontakt zum KIT IFV** (logiTopp wurde Mai 2026 aktualisiert –
Migrationsstand auf den neuen Kern erfragen; ggf. Kooperation im Projektkontext).

## Phasen

### Phase 0 – Machbarkeits-Spikes (1–2 Wochen)
- [ ] `mobiTopp-tutorial` (Rastatt) lokal bauen und Long-Term + Short-Term end-to-end ausführen;
      Dateiformate (CSV-Spalten, MTX) am lebenden Objekt verifizieren.
- [ ] `mobiTopp_legacy` + `logitopp` bauen; Minimal-Logistikszenario mit den mitgelieferten
      Choice-Model-Dateien zum Laufen bringen.
- [ ] GraphHopper-Matrix-Spike: 100 Zufalls-Zentroide in einer Beispielregion → Reisezeitmatrix
      → Visum-MTX schreiben (mobiToppIO evaluieren).
- [ ] Klärung mit Takomat: Nachfrage-Schnittstelle der MobileCity-App.

### Phase 1 – Headless-Pipeline (CLI, ohne UI)
Region als GeoJSON rein → mobiTopp-Lauf raus. Stufen 1–8 aus [03-architektur.md](03-architektur.md)
mit den „öffentlich"-Providern (OSM-Zonen, Zensus, OSM-Attraktivitäten, GraphHopper) und dem
synthetischen Survey-Template. Erfolgskriterium: **eine frei gewählte deutsche Kleinstadt läuft
vollautomatisch durch.**

### Phase 2 – Web-Frontend + Job-Orchestrierung
Spring-Modulith-Backend (scenario/jobs/storage), Angular-Frontend mit Kartenauswahl,
Parameterformularen, Pipeline-Monitor. simudrive-Code als Vorlage (Scenario-CRUD, Leaflet-Anbindung,
Metadaten-Formulare lassen sich weitgehend übernehmen).

### Phase 3 – Logistik-Ebene (logiTopp)
Depots auf der Karte, Paketnachfrage-Parameter, Effizienzprofile; Legacy-Adapter;
Ergebnis-Visualisierung (Touren, Paketdichte, KPIs).

### Phase 4 – MobileCity-Export
Network-Pipelines (Car/Bike/Transit), OLC Mapper und Config Tool containerisieren und als
Export-Adapter hinter einen Button legen; Nachfrage-Export gemäß geklärter Schnittstelle.

### Phase 5 – Härtung & Generik-Ausbau
Upload-Provider (eigene MiD/Strukturdaten), weitere Export-Adapter (MATSim), Kalibrierungs-Helfer,
Mehrbenutzer/Projekte.

## Risiken & offene Punkte

| # | Risiko | Einschätzung / Gegenmaßnahme |
|---|---|---|
| 1 | logiTopp ↔ neues mobiTopp inkompatibel | Hybrid-Ansatz; KIT-Kontakt; Logistik notfalls länger auf Legacy |
| 2 | Survey-Template-Lizenz (MiD/MOP) | synthetisches Default-Template; Upload-Slot für Lizenznehmer; Lizenz der Tutorial-Survey prüfen |
| 3 | Matrixgrößen O(n²) | Zonenlimit (≤ ~500 im MVP), Auflösung an Regionsgröße koppeln (simudrive-Muster: harte Limits) |
| 4 | Rechenzeit Wochensimulation | `fractionOfPopulation`-Sampling als UI-Parameter; Artefakt-Cache; asynchrone Jobs |
| 5 | MobileCity-Nachfrageformat unbekannt | früh mit Takomat klären (Phase 0) |
| 6 | Kalibrierung: synthetische Szenarien ≠ Realität | Datenqualitäts-Ampel + Disclaimer; Vergleichs-KPIs (Modal Split vs. MiD-Referenzwerte) ausweisen |
| 7 | Wahlmodell-Defaults passen nicht auf jede Region | Rastatt-Parameter als Default, Sensitivitäts-Parameter in UI; spätere Kalibrier-Workflows |
| 8 | GPL-3.0 (logiTopp) | nur als externer Prozess aufrufen, nicht linken; eigener Code bleibt frei lizenzierbar |
