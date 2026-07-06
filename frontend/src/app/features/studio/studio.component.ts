import { CommonModule } from '@angular/common';
import { AfterViewInit, Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import * as L from 'leaflet';
import { ApiService } from '../../core/services/api.service';
import { ArtifactInfo, Depot, GenerationJob, Scenario, UploadSummary } from '../../core/models/scenario.model';
import { GtaPersonas, LlmModeChoiceResult, ModalSplit, SimKpis, SumoSummary } from '../../core/models/results.model';

@Component({
  selector: 'app-studio',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './studio.component.html',
  styleUrls: ['./studio.component.css']
})
export class StudioComponent implements OnInit, AfterViewInit, OnDestroy {

  scenarios: Scenario[] = [];
  scenario: Scenario | null = null;
  selectedScenarioId = '';
  job: GenerationJob | null = null;
  artifacts: ArtifactInfo[] = [];
  depotMode = false;
  drawMode = false;
  busy = false;
  message = '';
  estimateInfo = '';
  upload: UploadSummary | null = null;
  uploadBusy = false;

  simKpis: SimKpis | null = null;
  sumoSummary: SumoSummary | null = null;
  llmModeChoice: LlmModeChoiceResult | null = null;
  gtaPersonas: GtaPersonas | null = null;
  mobileCity: any = null;
  simDepots: any[] = [];
  showParcelLayer = false;
  showTourLayer = false;
  showCongestionLayer = false;

  private map!: L.Map;
  private regionLayer: L.Rectangle | null = null;
  private drawRect: L.Rectangle | null = null;
  private drawStart: L.LatLng | null = null;
  private zoneLayer: L.GeoJSON | null = null;
  private depotLayer = L.layerGroup();
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private parcelLayer: L.GeoJSON | null = null;
  private tourLayer: L.GeoJSON | null = null;
  private congestionLayer: L.GeoJSON | null = null;
  private parcelDensity: any = null;
  private tourData: any = null;
  private congestionData: any = null;

  constructor(private api: ApiService) {
  }

  ngOnInit(): void {
    this.reloadScenarios();
    this.newScenario();
  }

  ngAfterViewInit(): void {
    this.map = L.map('map').setView([51.514, 7.466], 12); // Start: Dortmund
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap contributors'
    }).addTo(this.map);
    this.depotLayer.addTo(this.map);
    this.map.on('click', event => this.onMapClick(event));
    this.map.on('mousedown', event => this.onDrawStart(event as L.LeafletMouseEvent));
    this.map.on('mousemove', event => this.onDrawMove(event as L.LeafletMouseEvent));
    this.map.on('mouseup', () => this.onDrawEnd());
  }

  ngOnDestroy(): void {
    this.stopPolling();
    this.map?.remove();
  }

  // --- Szenario-Verwaltung -------------------------------------------------

  reloadScenarios(): void {
    this.api.listScenarios().subscribe(list => this.scenarios = list);
  }

  newScenario(): void {
    this.api.scenarioDefaults().subscribe(defaults => {
      this.scenario = defaults;          // region ist hier null: noch kein Rechteck
      this.selectedScenarioId = '';
      this.job = null;
      this.artifacts = [];
      this.estimateInfo = '';
      this.upload = null;
      this.message = '';
      this.clearSimulation();
      this.drawRegion();
      this.zoneLayer?.remove();
      this.drawDepots();
    });
  }

  onScenarioSelected(): void {
    const selected = this.scenarios.find(s => s.id === this.selectedScenarioId);
    if (!selected) {
      this.newScenario();
      return;
    }
    this.scenario = JSON.parse(JSON.stringify(selected));
    this.job = null;
    this.artifacts = [];
    this.estimateInfo = '';
    this.clearSimulation();
    this.loadUploads();
    this.zoneLayer?.remove();
    this.drawRegion();
    this.drawDepots();
    this.fitRegion();
    if (selected.id) {
      this.api.generationStatus(selected.id).subscribe({
        next: job => {
          this.job = job;
          if (job.state === 'DONE') {
            this.loadArtifacts();
          }
        },
        error: () => this.api.listArtifacts(selected.id!).subscribe(a => this.artifacts = a)
      });
    }
  }

  save(): void {
    if (!this.scenario) {
      return;
    }
    if (!this.scenario.region) {
      this.message = 'Bitte zuerst eine Region auf der Karte zeichnen';
      return;
    }
    this.busy = true;
    const request = this.scenario.id
      ? this.api.updateScenario(this.scenario)
      : this.api.createScenario(this.scenario);
    request.subscribe({
      next: saved => {
        this.scenario = saved;
        this.selectedScenarioId = saved.id ?? '';
        this.busy = false;
        this.message = 'Szenario gespeichert';
        this.reloadScenarios();
      },
      error: err => {
        this.busy = false;
        this.message = 'Fehler: ' + (err.error?.message ?? err.message);
      }
    });
  }

  deleteSelected(): void {
    const selected = this.scenarios.find(s => s.id === this.selectedScenarioId);
    if (!selected?.id || !confirm(`Szenario "${selected.name}" löschen?`)) {
      return;
    }
    this.api.deleteScenario(selected.id).subscribe(() => {
      this.reloadScenarios();
      this.newScenario();
    });
  }

  // --- Modellierung (Zwei-Achsen-Auswahl) ------------------------------------

  readonly calibrationModes = [
    { key: 'pedestrian', label: 'Fuß' },
    { key: 'bike', label: 'Rad' },
    { key: 'car', label: 'MIV (Fahrer)' },
    { key: 'passenger', label: 'MIV (Mitf.)' },
    { key: 'publicTransport', label: 'ÖV' },
  ];

  /**
   * Regionstyp-Vorlagen für den Kalibrierungs-Zielwert (RegioStaR-Raumtypen).
   * Richtwerte nach dem MiD-2017-Stadt-Land-Muster; an offizielle Regionaltabellen anpassbar.
   * Jede Vorlage summiert sich zu 1,0.
   */
  readonly regionPresets: { id: string; label: string; split: { [key: string]: number } }[] = [
    { id: 'mid', label: 'MiD 2017 (bundesweit)',
      split: { pedestrian: 0.22, bike: 0.11, car: 0.43, passenger: 0.14, publicTransport: 0.10 } },
    { id: 'metropole', label: 'Metropole (RegioStaR 71)',
      split: { pedestrian: 0.27, bike: 0.13, car: 0.27, passenger: 0.13, publicTransport: 0.20 } },
    { id: 'grossstadt', label: 'Regiopole / Großstadt (72)',
      split: { pedestrian: 0.24, bike: 0.13, car: 0.34, passenger: 0.14, publicTransport: 0.15 } },
    { id: 'mittelstadt', label: 'Mittel-/Kleinstadt, städtisch (73/74)',
      split: { pedestrian: 0.22, bike: 0.11, car: 0.42, passenger: 0.15, publicTransport: 0.10 } },
    { id: 'kleinstadt', label: 'Kleinstädtisch / ländlich (75/76)',
      split: { pedestrian: 0.18, bike: 0.10, car: 0.49, passenger: 0.16, publicTransport: 0.07 } },
    { id: 'laendlich', label: 'Ländlich / dörflich (77)',
      split: { pedestrian: 0.16, bike: 0.08, car: 0.55, passenger: 0.16, publicTransport: 0.05 } },
  ];

  selectedPreset = '';

  /** Füllt den Kalibrierungs-Zielwert mit der gewählten Regionstyp-Vorlage (danach editierbar). */
  applyRegionPreset(id: string): void {
    const preset = this.regionPresets.find(p => p.id === id);
    if (preset && this.scenario) {
      this.scenario.population.calibrationTarget = { ...preset.split };
    }
  }

  hasTarget(target: string): boolean {
    return this.scenario?.targetModels?.includes(target) ?? false;
  }

  toggleTarget(target: string): void {
    if (!this.scenario) {
      return;
    }
    const targets = this.scenario.targetModels ?? (this.scenario.targetModels = []);
    const i = targets.indexOf(target);
    if (i >= 0) {
      targets.splice(i, 1);
    } else {
      targets.push(target);
    }
  }

  // --- Region zeichnen -------------------------------------------------------

  toggleDrawMode(): void {
    this.drawMode = !this.drawMode;
    if (this.drawMode) {
      this.depotMode = false;
      this.map.dragging.disable();
      this.map.getContainer().classList.add('drawing');
    } else {
      this.map.dragging.enable();
      this.map.getContainer().classList.remove('drawing');
    }
  }

  private onDrawStart(event: L.LeafletMouseEvent): void {
    if (!this.drawMode) {
      return;
    }
    this.drawStart = event.latlng;
    this.drawRect?.remove();
    this.drawRect = L.rectangle(L.latLngBounds(event.latlng, event.latlng),
      { color: '#c0392b', weight: 2, dashArray: '6 4', fillOpacity: 0.05 }).addTo(this.map);
  }

  private onDrawMove(event: L.LeafletMouseEvent): void {
    if (!this.drawMode || !this.drawStart || !this.drawRect) {
      return;
    }
    this.drawRect.setBounds(L.latLngBounds(this.drawStart, event.latlng));
  }

  private onDrawEnd(): void {
    if (!this.drawMode || !this.drawStart || !this.drawRect || !this.scenario) {
      return;
    }
    const bounds = this.drawRect.getBounds();
    this.drawStart = null;
    this.drawRect.remove();
    this.drawRect = null;
    if (bounds.getSouth() === bounds.getNorth() || bounds.getWest() === bounds.getEast()) {
      return; // nur ein Klick, kein aufgezogenes Rechteck
    }
    this.scenario.region = {
      minLat: +bounds.getSouth().toFixed(5),
      minLon: +bounds.getWest().toFixed(5),
      maxLat: +bounds.getNorth().toFixed(5),
      maxLon: +bounds.getEast().toFixed(5)
    };
    this.toggleDrawMode();
    this.afterRegionChanged();
  }

  useMapBounds(): void {
    if (!this.scenario) {
      return;
    }
    const bounds = this.map.getBounds();
    this.scenario.region = {
      minLat: +bounds.getSouth().toFixed(5),
      minLon: +bounds.getWest().toFixed(5),
      maxLat: +bounds.getNorth().toFixed(5),
      maxLon: +bounds.getEast().toFixed(5)
    };
    this.afterRegionChanged();
  }

  /** Nach Regionsänderung: Rechteck zeichnen, Zonenvorschau, Einwohner aus OSM schätzen. */
  private afterRegionChanged(): void {
    this.drawRegion();
    this.previewZones();
    this.estimatePopulation();
  }

  private estimatePopulation(): void {
    if (!this.scenario?.region) {
      return;
    }
    this.estimateInfo = 'Einwohner werden aus OSM geschätzt…';
    this.api.estimateRegion(this.scenario.region).subscribe({
      next: estimate => {
        if (estimate.source === 'osm-places' && estimate.estimatedPopulation > 0) {
          const names = estimate.places.slice(0, 3).map(p => p.name).join(', ');
          this.estimateInfo = `≈ ${estimate.estimatedPopulation.toLocaleString('de-DE')} Einwohner `
            + `(OSM-Siedlungsdaten: ${names}${estimate.places.length > 3 ? ', …' : ''})`;
          if (this.scenario && this.scenario.population.totalPersons == null) {
            this.scenario.population.totalPersons = estimate.estimatedPopulation;
          }
        } else {
          this.estimateInfo = 'Keine OSM-Einwohnerdaten gefunden – es gilt die Dichte-Schätzung';
        }
      },
      error: () => this.estimateInfo = 'Einwohner-Schätzung nicht verfügbar'
    });
  }

  previewZones(): void {
    if (!this.scenario?.region) {
      this.message = 'Bitte zuerst eine Region auf der Karte zeichnen';
      return;
    }
    this.api.previewZones(this.scenario.region, this.scenario.zoning.cellSizeMeters).subscribe({
      next: geojson => {
        this.zoneLayer?.remove();
        this.zoneLayer = L.geoJSON(geojson, {
          style: { color: '#15314f', weight: 1, fillOpacity: 0.05 }
        }).addTo(this.map);
        if (geojson.autoAdjusted && this.scenario) {
          this.scenario.zoning.cellSizeMeters = geojson.cellSizeMeters;
          this.message = `${geojson.features.length} Zonen – Zellgröße automatisch auf `
            + `${geojson.cellSizeMeters} m erhöht (Zonenlimit)`;
        } else {
          this.message = `${geojson.features.length} Zonen`;
        }
      },
      error: err => this.message = 'Fehler: ' + (err.error?.message ?? err.message)
    });
  }

  // --- Daten-Upload (Bevölkerungsraster) --------------------------------------

  onGridFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !this.scenario?.id) {
      if (!this.scenario?.id) {
        this.message = 'Bitte Szenario zuerst speichern, dann Daten hochladen';
      }
      return;
    }
    this.uploadBusy = true;
    this.api.uploadPopulationGrid(this.scenario.id, file).subscribe({
      next: summary => {
        this.upload = summary;
        this.uploadBusy = false;
        this.message = `Bevölkerungsraster übernommen (${summary.points.toLocaleString('de-DE')} Punkte)`;
      },
      error: err => {
        this.uploadBusy = false;
        this.message = 'Upload-Fehler: ' + (err.error?.message ?? err.message);
      }
    });
  }

  removeUpload(): void {
    if (!this.scenario?.id) {
      return;
    }
    this.api.deletePopulationGrid(this.scenario.id).subscribe(() => {
      this.upload = null;
      this.message = 'Bevölkerungsraster entfernt';
    });
  }

  private loadUploads(): void {
    this.upload = null;
    if (!this.scenario?.id) {
      return;
    }
    this.api.listUploads(this.scenario.id).subscribe(list => {
      const grid = list.find(u => u.name === 'population-grid.csv');
      if (grid) {
        this.upload = {
          name: grid.name, format: 'hochgeladen', points: 0,
          totalPopulation: 0, skippedLines: 0
        };
      }
    });
  }

  // --- Depots ----------------------------------------------------------------

  toggleDepotMode(): void {
    this.depotMode = !this.depotMode;
    if (this.depotMode && this.drawMode) {
      this.toggleDrawMode();
    }
  }

  removeDepot(index: number): void {
    this.scenario?.logistics.depots.splice(index, 1);
    this.drawDepots();
  }

  private onMapClick(event: L.LeafletMouseEvent): void {
    if (!this.depotMode || !this.scenario) {
      return;
    }
    const depot: Depot = {
      name: `Depot ${this.scenario.logistics.depots.length + 1}`,
      lat: +event.latlng.lat.toFixed(5),
      lon: +event.latlng.lng.toFixed(5),
      employees: 20,
      share: 1,
      vehicleType: 'TRUCK'
    };
    this.scenario.logistics.depots.push(depot);
    this.drawDepots();
  }

  private drawRegion(): void {
    this.regionLayer?.remove();
    this.regionLayer = null;
    if (!this.scenario?.region) {
      return;
    }
    const r = this.scenario.region;
    this.regionLayer = L.rectangle(
      [[r.minLat, r.minLon], [r.maxLat, r.maxLon]],
      { color: '#c0392b', weight: 2, fillOpacity: 0.03 }
    ).addTo(this.map);
  }

  private fitRegion(): void {
    if (this.regionLayer) {
      this.map.fitBounds(this.regionLayer.getBounds(), { padding: [20, 20] });
    }
  }

  private drawDepots(): void {
    this.depotLayer.clearLayers();
    this.scenario?.logistics.depots.forEach(depot => {
      L.circleMarker([depot.lat, depot.lon], {
        radius: 8, color: '#d35400', fillColor: '#e67e22', fillOpacity: 0.9
      }).bindTooltip(`${depot.name} (${depot.vehicleType})`).addTo(this.depotLayer);
    });
  }

  // --- Generierung ----------------------------------------------------------

  generate(): void {
    if (!this.scenario?.id) {
      this.message = 'Bitte zuerst speichern';
      return;
    }
    this.api.generate(this.scenario.id).subscribe({
      next: job => {
        this.job = job;
        this.artifacts = [];
        this.startPolling();
      },
      error: err => this.message = 'Fehler: ' + (err.error?.message ?? err.message)
    });
  }

  private startPolling(): void {
    this.stopPolling();
    this.pollTimer = setInterval(() => {
      if (!this.scenario?.id) {
        return;
      }
      this.api.generationStatus(this.scenario.id).subscribe(job => {
        this.job = job;
        if (job.state === 'DONE' || job.state === 'FAILED') {
          this.stopPolling();
          if (job.state === 'DONE') {
            this.loadArtifacts();
          }
        }
      });
    }, 1500);
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  private loadArtifacts(): void {
    if (this.scenario?.id) {
      this.api.listArtifacts(this.scenario.id).subscribe(list => this.artifacts = list);
      this.loadSimulation();
      this.api.artifactJson(this.scenario.id, 'mobilecity/summary.json')
        .subscribe({ next: s => this.mobileCity = s, error: () => this.mobileCity = null });
    }
  }

  /** Download-URL einer MobileCity-Tabelle. */
  mobileCityUrl(table: string): string {
    return this.artifactUrl('mobilecity/' + table);
  }

  // --- Simulationsergebnisse (Stufe 10) ----------------------------------------

  /** Lädt KPIs, Depot-Statistiken und Kartenlayer; 404 = Simulation übersprungen. */
  private loadSimulation(): void {
    this.clearSimulation();
    const id = this.scenario?.id;
    if (!id) {
      return;
    }
    this.api.artifactJson<SimKpis>(id, 'simulation/kpis.json')
      .subscribe({ next: kpis => this.simKpis = kpis, error: () => this.simKpis = null });
    this.api.artifactJson<any[]>(id, 'simulation/depot-stats.json')
      .subscribe({ next: depots => this.simDepots = depots, error: () => this.simDepots = [] });
    this.api.artifactJson(id, 'simulation/parcel-density.geojson')
      .subscribe({ next: geo => this.parcelDensity = geo, error: () => this.parcelDensity = null });
    this.api.artifactJson(id, 'simulation/tours.geojson')
      .subscribe({ next: geo => this.tourData = geo, error: () => this.tourData = null });
    this.api.artifactJson<SumoSummary>(id, 'sumo/summary.json')
      .subscribe({ next: s => this.sumoSummary = s, error: () => this.sumoSummary = null });
    this.api.artifactJson<LlmModeChoiceResult>(id, 'simulation/llm-mode-choice.json')
      .subscribe({ next: s => this.llmModeChoice = s, error: () => this.llmModeChoice = null });
    this.api.artifactJson<GtaPersonas>(id, 'population/gta-personas.json')
      .subscribe({ next: s => this.gtaPersonas = s, error: () => this.gtaPersonas = null });
    this.api.artifactJson(id, 'sumo/congestion.geojson')
      .subscribe({ next: geo => this.congestionData = geo, error: () => this.congestionData = null });
  }

  private clearSimulation(): void {
    this.simKpis = null;
    this.sumoSummary = null;
    this.llmModeChoice = null;
    this.gtaPersonas = null;
    this.mobileCity = null;
    this.simDepots = [];
    this.parcelDensity = null;
    this.tourData = null;
    this.congestionData = null;
    this.parcelLayer?.remove();
    this.parcelLayer = null;
    this.tourLayer?.remove();
    this.tourLayer = null;
    this.congestionLayer?.remove();
    this.congestionLayer = null;
    this.showParcelLayer = false;
    this.showTourLayer = false;
    this.showCongestionLayer = false;
  }

  get hasSimLayers(): boolean {
    return !!this.parcelDensity || !!this.tourData || !!this.congestionData;
  }

  toggleParcelLayer(): void {
    this.showParcelLayer = !this.showParcelLayer;
    this.parcelLayer?.remove();
    this.parcelLayer = null;
    if (!this.showParcelLayer || !this.parcelDensity) {
      return;
    }
    const max = Math.max(1, ...this.parcelDensity.features
      .map((f: any) => f.properties?.parcels ?? 0));
    this.parcelLayer = L.geoJSON(this.parcelDensity, {
      style: feature => ({
        color: '#8e44ad', weight: 1,
        fillColor: '#8e44ad',
        fillOpacity: 0.75 * ((feature?.properties?.parcels ?? 0) / max)
      }),
      onEachFeature: (feature, layer) =>
        layer.bindTooltip(`${feature.properties?.name ?? feature.properties?.zoneId}: `
          + `${feature.properties?.parcels ?? 0} Pakete`)
    }).addTo(this.map);
  }

  toggleTourLayer(): void {
    this.showTourLayer = !this.showTourLayer;
    this.tourLayer?.remove();
    this.tourLayer = null;
    if (!this.showTourLayer || !this.tourData) {
      return;
    }
    this.tourLayer = L.geoJSON(this.tourData, {
      style: feature => ({
        color: feature?.properties?.vehicleType === 'BIKE' ? '#27ae60' : '#2980b9',
        weight: 1, opacity: 0.35
      }),
      onEachFeature: (feature, layer) =>
        layer.bindTooltip(`${feature.properties?.depot} (${feature.properties?.vehicleType}), `
          + `${feature.properties?.stops} Stopps, ${feature.properties?.distanceKm} km`)
    }).addTo(this.map);
  }

  /** Netzlast aus SUMO: Kanten eingefärbt nach relSpeed (gemessen/Tempolimit). */
  toggleCongestionLayer(): void {
    this.showCongestionLayer = !this.showCongestionLayer;
    this.congestionLayer?.remove();
    this.congestionLayer = null;
    if (!this.showCongestionLayer || !this.congestionData) {
      return;
    }
    this.congestionLayer = L.geoJSON(this.congestionData, {
      style: feature => {
        const rel = feature?.properties?.relSpeed ?? 1;
        return {
          color: rel >= 0.8 ? '#2e7d32' : rel >= 0.5 ? '#f39c12' : '#c0392b',
          weight: 3, opacity: 0.8
        };
      },
      onEachFeature: (feature, layer) =>
        layer.bindTooltip(`${feature.properties?.speedKmh} km/h `
          + `(Limit ${feature.properties?.limitKmh} km/h, `
          + `Zeitverlust ${feature.properties?.timeLossS}s)`)
    }).addTo(this.map);
  }

  /** Modal Split der Simulation neben den MiD-2017-Referenzwerten. */
  get modalSplitRows(): { label: string; sim: number; mid: number | null }[] {
    if (!this.simKpis) {
      return [];
    }
    const labels: Record<string, string> = {
      pedestrian: 'Fuß', bike: 'Rad', car: 'MIV (Fahrer)',
      passenger: 'MIV (Mitfahrer)', publicTransport: 'ÖV', other: 'Sonstige'
    };
    const sim: ModalSplit = this.simKpis.demand?.modalSplit ?? {};
    const mid: ModalSplit = this.simKpis.mid2017?.modalSplit ?? {};
    return Object.keys(labels)
      .filter(key => sim[key] != null || mid[key] != null)
      .map(key => ({ label: labels[key], sim: sim[key] ?? 0, mid: mid[key] ?? null }));
  }

  artifactUrl(path: string): string {
    return this.scenario?.id ? this.api.artifactUrl(this.scenario.id, path) : '#';
  }

  formatSize(bytes: number): string {
    if (bytes < 1024) {
      return bytes + ' B';
    }
    if (bytes < 1024 * 1024) {
      return (bytes / 1024).toFixed(1) + ' KB';
    }
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  }

  get running(): boolean {
    return this.job?.state === 'QUEUED' || this.job?.state === 'RUNNING';
  }
}
