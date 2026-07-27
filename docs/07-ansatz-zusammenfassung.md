# SyntheSis – Ansatz & Pipeline (Briefing)

Kompakte, vollständige Zusammenfassung des Ansatzes – als Grundlage, um ein anderes Modell zu
informieren bzw. Präsentationsgrafiken abzuleiten (klare Inputs → Prozess → Outputs).

## 1. Ziel in einem Satz
SyntheSis ist ein Web-Tool, in dem man auf einer Karte eine Region + Parameter wählt und daraus
automatisch ein **synthetisches Verkehrsnachfrage-Modell** generiert wird – das wahlweise in eine
logistische Mikrosimulation (mobiTopp/logiTopp), eine Verkehrssimulation (SUMO) oder die
MobileCity/Takomat-App fließt.

## 2. Grundprinzip: Zwei-Achsen-Modell
Der Kern ist eine **frei kombinierbare Matrix** aus zwei austauschbaren Achsen:

- **Achse A – Verhaltensquelle** (*wie wird Verhalten erzeugt?*): `actiTopp` (kalibriertes
  KIT-Modell) · `GTA` (KI/LLM-Agenten) · `Template` (interner Fallback)
- **Achse B – Zielmodell** (*wer verwertet die Nachfrage?*, Mehrfachauswahl): `logiTopp`
  (Personen + Pakete) · `SUMO` (mikrosk. Verkehr) · `MobileCity-Export`

Jede Achse ist ein **austauschbarer Runner**. Alle schweren externen Modelle laufen als **lokale
Prozesse** (JVM/Gradle, SUMO-Binaries, LLM-Server) – nicht eingebettet (u. a. wegen GPL-Trennung
von logiTopp).

## 3. Die Generierungs-Pipeline (Reihenfolge, Input → Output)
Eine Generierung durchläuft 14 Stufen. „A/B-gated" = läuft nur, wenn die Achse gewählt ist.

| # | Stufe | Was passiert | Wichtigste Artefakte |
|---|---|---|---|
| 1 | Zonierung | Region-BBox → Gitter aus Verkehrszellen (Zellgröße, Auto-Anpassung an Zonenlimit) | `zones.csv` |
| 2 | OSM-Daten (POIs) | Overpass: Shops/Amenities/… je Zelle (synthetischer Fallback) | (intern) |
| 3 | Attraktivitäten | Ziel-Attraktivität je Zelle & Zweck (Arbeit/Bildung/Einkauf/Freizeit) | `attractivities.csv` |
| 4 | Bevölkerungssynthese | Personen/Haushalte (KIT-Sample oder synthetisch) + Pkw + feste Ziele | `person.csv`, `household.csv`, `car.csv`, `fixedDestination.csv` |
| 5 | **Aktivitätenpläne (Achse A)** | Wochenpläne je Person aus `actiTopp` \| `GTA-LLM` \| `Template` | `activity.csv` |
| 6 | Matrizen | GraphHopper-Routing (Pkw/Rad/Fuß) Zelle↔Zelle, Luftlinien-Fallback | Visum `.mtx.bz2` + Matrix-YAMLs |
| 7 | Logistik | Depots, Packstationen, Paketnachfrage-Parameter | `distribution-centers.csv`, `cepsp-shares.csv` |
| 8 | Legacy-Export | Umwandlung in das logiTopp-Legacy-Format | `legacy/…` |
| 9 | **MobileCity-Export (B)** | District-aggregierte Config-Tabellen | `mobilecity/` (5 CSV + `summary.json`) |
| 10 | Manifest | Lauf-Metadaten | `manifest.json` |
| 11 | **Simulation logiTopp (B)** | Externe Wochensimulation Personen+Pakete (+ optional Kalibrierungsschleife) | `demandsimulationResult.csv`, `kpis.json`, `depot-stats.json`, `tours.geojson`, `parcel-density.geojson` |
| 12 | **SUMO (B)** | OSM → `netconvert` → `duarouter` → `sumo`: **PKW-, Rad- und Fuß-Trips** der Nachfrage + **Zustelltouren** (Lkw/Lastenrad) + optional **ÖV/Bus** (GTFS → `gtfs2pt.py`, intermodal); misst edgeData + CO₂ (HBEFA je Fahrzeugtyp inkl. Bus) | `sumo/` (net, routes, `statistics.xml`, `summary.json` mit CO₂, `congestion.geojson` = Netzlast-Karte) |
| 13 | **Netz-Feedback (optional)** | Gleichgewichts-Schleife: Stau-Faktoren je Zonenrelation (1–3), **MSA-gedämpft** in die Pkw-Matrix → logiTopp entscheidet neu → SUMO erneut – bis Konvergenz (Δ<0,01, max. 3 Iterationen) | aktualisierte `CAR_TTC`-Matrizen + neue `kpis.json`/SUMO-Artefakte |
| 14 | **LLM-Moduswahl (A, GTA 3c)** | LLM wählt Modus je Distanzband auf simulierten Trips | `simulation/llm-mode-choice.json` |

## 4. Die KI-Pipeline (GTA) im Detail
Das LLM läuft **lokal** über **Ollama** mit **Qwen2.5-7B-Instruct** (OpenAI-kompatible API). Zwei
Einsatzpunkte:

**(a) GTA-Verhaltensquelle (Stufe 5)** – generative Tagespläne:

```
person.csv → Persona je Person (Erwerbsstatus × Altersband × Geschlecht)
   → Persona-Bündelung: 1 LLM-Call je distinkter Signatur (gekappt), nicht je Person
   → LLM liefert JSON: weekday/weekend-TOUREN [{type, duration_min}]
   → ActivityGenerator.generateFromGta: webt HOME ein, Timing/Jitter, Mitternachts-Fit
     (bewährte scheduleDay-Logik)
   → activity.csv
```

- **Reproduzierbar:** Seed pro Anfrage = `Szenario-Seed × 31 + Persona-Hash` (Ollama respektiert `seed`).
- **Effizient:** z. B. 651 Personen → nur 18 LLM-Calls (~140 s). Fallback auf actiTopp, wenn LLM
  nicht erreichbar.

**(b) LLM-Moduswahl (Stufe 14, GTA 3c)** – geerdet in Reisezeiten:

```
demandsimulationResult.csv (distanceInKm) → Trips nach Distanzband bündeln
   → je Band 1 LLM-Call mit Reisezeiten (Fuß/Rad/Pkw/ÖV) → Modal-Split-Anteile
   → gewichtete Aggregation → llm-mode-choice.json (LLM vs. MNL vs. MiD)
```

- ~5 Calls für 70.000 Trips. **Ergänzt** die logiTopp-Moduswahl, ersetzt sie nicht.

**(c) Kalibrierung (Phase 1, NICHT-LLM, aber Teil der „AI-Nähe"):** iterative Anpassung der
Moduswahl-Konstanten (ASCs) im logiTopp-MNL-Modell gegen einen Ziel-Modal-Split (MiD oder regional),
mehrere Sim-Läufe bis RMSE < Toleranz. Kombinierbar mit GTA.

## 5. Wichtigste Artefakt-Gruppen
- **mobiTopp-Inputs:** `zones`, `attractivities`, `person`, `household`, `activity`, `car`,
  `fixedDestination` (CSV) + Visum-Matrizen (`.mtx.bz2`)
- **logiTopp/Logistik:** `legacy/`, `distribution-centers`, `cepsp-shares`
- **Ergebnisse:** `kpis.json` (Wege/Person·Tag, Ø Weglänge, Modal-Split sowie sechs
  Distanzbänder inkl. RMSE vs. MiD), `tours.geojson`, `parcel-density.geojson`,
  `demandsimulationResult.csv`
- **SUMO:** `sumo/statistics.xml`, `summary.json` (Fahrzeuge nach PKW/Rad/Fuß/Lieferverkehr/ÖV,
  CO₂ je Fahrzeugtyp inkl. Bus), `congestion.geojson` (Netzlast)
- **KI:** `llm-mode-choice.json`, `gta-personas.json` (Begründungen + Was-wäre-wenn)
- **Export:** `mobilecity/*.csv` + `districts.shp/.shx/.dbf/.prj` (WGS84, districtID) · `manifest.json`

## 6. Datenfluss-Skelett (für eine Grafik)

```
[Karte/Parameter (UI)]
        ↓
[Spring-Boot-Pipeline]
  Zonen → POIs/Attraktivität → Population
        ↓
  ── Achse A: Verhalten ──  actiTopp | GTA-LLM | Template  → activity.csv
        ↓
  Routing-Matrizen (GraphHopper) + Logistik
        ↓
  ── Achse B: Zielmodell ──  logiTopp | SUMO | MobileCity
        ↓            ↑______________|
        ↓        (Netz-Feedback: SUMO-Stauzeiten → Pkw-Matrix → neue Entscheidung, MSA-iterativ bis Konvergenz)
  ── SUMO multimodal ──  PKW · Rad · Fuß · Lieferverkehr · optional ÖV/Bus (GTFS)
        ↓
  KPIs / Touren / CO₂ / Netzlast-Karte / LLM-Moduswahl
        ↓
[Studio-Panels + Szenario-Vergleich (UI, inkl. Emissionsvergleich)]
```

## 7. Kernbefunde (für „Ergebnis"-Folien)
- **Zwei-Achsen-Architektur funktioniert E2E:** gleiche Population, Achse-A-Quelle tauschen →
  vergleichbare KPIs.
- **GTA trifft die MiD-Wegehäufigkeit** (~3,1 Wege/Person·Tag) nach Prompt-Tuning.
- **GTA + Kalibrierung sind kombinierbar:** Kalibrierung halbiert den Modal-Split-Fehler auf
  GTA-Output (RMSE 0,16 → 0,07).
- **Ehrlicher Distanz-Befund:** Die Nahdistanz-Kalibrierung senkte `<1 km` mit dem inzwischen
  verworfenen Näherungsziel 14 % im Drei-Seed-Mittel 15,6→14,7 % und verfehlte die amtliche
  MiD-Sechs-Band-Verteilung deutlich (B-RMSE 0,1019–0,1094 bei Ziel höchstens 0,05).
  Amtlich sind `<1 km` 21 %; der Default ist korrigiert, aber noch nicht E2E revalidiert.
- **Ehrlicher KI-Befund:** rohe LLM-Moduswahl hat einen **Bias zu aktiven Modi** (zu viel Rad),
  weil ihr die kalibrierten Modus-Konstanten fehlen – sie ist eine interessante, aber
  kalibrierbedürftige Alternativlinse.
- **Netz-Feedback konvergiert:** Gleichgewichts-Schleife mit MSA-Dämpfung erreicht bei voller
  Population nach 2 Iterationen das Gleichgewicht (Δ 0,33 → 0,008; Ø Faktor 1,32) →
  Pkw 27 %→24 %, Fuß 45 %→49 % – die Nachfrage weicht dem Stau aus, stabil statt oszillierend.
- **Emissionsvergleich als Kern-Use-Case:** CO₂ je Fahrzeugtyp (HBEFA, inkl. Bus) macht
  Logistik-/Verkehrs-Szenarien wie „Lastenrad- statt Lkw-Flotte" oder „mehr ÖV-Nutzung" direkt im
  Szenario-Vergleich quantifizierbar.
- **SUMO ist jetzt echt multimodal:** PKW, Rad, Fuß, Lieferverkehr und optional ÖV/Bus (GTFS,
  intermodal) laufen in einer Simulation. **Ehrlicher Technik-Befund:** Schienen-ÖV (Tram/Rail)
  scheitert an einer SUMO-Eigenheit – `duarouter` snappt Fußgänger-Koordinaten auf die nächstgelegene
  Kante ohne Fahrzeugklassen-Filter, nahe Bahnhöfen landen sie so auf Gleisen statt Gehwegen. Bewusst
  zurückgestellt statt halbfertig ausgeliefert (Details: [docs/06](06-gta-integration-konzept.md)).

## 8. Technologie-Stack (Kurzüberblick)
- **Frontend:** Angular 18.2 (TypeScript 5.5, RxJS 7.8), Leaflet 1.9.4, OpenStreetMap-Tiles.
- **Backend/API:** Java 21, Spring Boot 3.5.5, springdoc-openapi (Swagger), Jackson, Maven.
- **Routing & Geo:** GraphHopper 8.0 (eingebettet, Apache-2.0), Overpass/OSM (+ Mirror-Fallbacks).
- **Achse A:** KIT actiTopp/actitoppNG (Kotlin, extern), KIT populationsynthesis (MIT),
  GTA-LLM (Ollama + Qwen2.5-7B, Apache-2.0), Template-Generator (eigener Code).
- **Achse B:** mobiTopp 0.3.580 + logiTopp (Legacy-Java, **GPL-3.0**, externer Prozess),
  SUMO 1.26 (EclipseSUMO, EPL-2.0), MobileCity/Takomat-Export.
- **Build/Laufzeit:** Maven (Backend), npm/Angular CLI (Frontend), Gradle (KIT-Stacks),
  JVM/OpenJDK 21. (SUMO-Pfad rein nativ – kein Python/pyproj mehr.)
- **Lizenz-Mix:** überwiegend permissiv (Apache/MIT/BSD), Verkehrssimulation EPL, logiTopp-Legacy
  GPL-3.0 (Grund für Prozess-Trennung). Offener Punkt: actitoppNG-Lizenz widersprüchlich (LICENSE
  = GPL vs. POM = MIT), mit KIT klären.

---

*Verweise:* Zwei-Achsen-Modell, GTA-Integration und Roadmap im Detail unter
[docs/06-gta-integration-konzept.md](06-gta-integration-konzept.md); Validierung des Legacy-Stacks
unter [docs/05-validierung.md](05-validierung.md).
