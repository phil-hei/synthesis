import { CommonModule } from '@angular/common';
import { AfterViewInit, Component, OnDestroy, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HelpTipComponent } from '../../shared/help-tip.component';
import { CompanyTourImportComponent } from './company-tour-import.component';
import { DataWorkspaceComponent } from '../data/data-workspace.component';
import { EnergyOperationsPanelComponent } from './energy-operations-panel.component';
import { emptyOperations, emptyVehicleConfiguration } from '../../core/models/vehicle-operations.model';
import { EnergyInfrastructurePanelComponent } from './energy-infrastructure-panel.component';
import { EnergyGeoJson, EnergyQuality, energyPopup } from '../../core/models/energy-infrastructure.model';
import { forkJoin } from 'rxjs';
import * as L from 'leaflet';
import { ApiService } from '../../core/services/api.service';
import {
  ArtifactInfo, CommuterUploadSummary, Depot, GenerationJob, OsmDataUploadSummary, RoadNetworkUploadSummary,
  Scenario, UploadSummary, WorkplaceMarginUploadSummary
} from '../../core/models/scenario.model';
import {
  CongestionFeatureProperties,
  CongestionGeoJson,
  DepotStats,
  DISTANCE_BAND_ROWS,
  GtaPersonas,
  LlmModeChoiceResult,
  MobileCityDataItem,
  MobileCityDataResolution,
  MobileCityDataStatus,
  MobileCitySummary,
  MobileCityValidationStatus,
  ModalSplit,
  ParcelDensityGeoJson,
  SimKpis,
  SumoSummary,
  TrafficRouteProperties,
  TrafficRoutesGeoJson,
  TrafficDemandSummary,
  TourFeatureProperties,
  TourGeoJson,
  ZoneFeatureProperties
} from '../../core/models/results.model';
import type { LineString, Polygon, Point } from 'geojson';

type DeliveryShareKey = 'shareHome' | 'shareWork' | 'sharePackstation';
type LogisticsMetricKey = 'parcelsPerPersonWeek' | 'parcelStdDev';

@Component({
  selector: 'app-studio',
  imports: [CommonModule, FormsModule, HelpTipComponent, CompanyTourImportComponent, DataWorkspaceComponent, EnergyInfrastructurePanelComponent, EnergyOperationsPanelComponent],
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
  /** Statusmeldung der Szenarioverwaltung; die Art (Erfolg/Fehler/Hinweis) steuert die Darstellung. */
  private messageText = '';
  messageKind: MessageKind = 'info';
  scenarioListError = '';
  zonePreviewInfo = '';
  estimateInfo = '';
  estimateSourceUrl = '';
  estimatedPopulation: number | null = null;
  upload: UploadSummary | null = null;
  uploadBusy = false;
  roadNetworkUpload: RoadNetworkUploadSummary | null = null;
  roadNetworkUploadBusy = false;
  osmDataUpload: OsmDataUploadSummary | null = null;
  osmDataUploadBusy = false;
  commuterUpload: CommuterUploadSummary | null = null;
  commuterUploadBusy = false;
  workplaceMarginUpload: WorkplaceMarginUploadSummary | null = null;
  workplaceMarginUploadBusy = false;
  randomizeEachRun = false;

  trafficDemandSummary: TrafficDemandSummary | null = null;
  simKpis: SimKpis | null = null;
  sumoSummary: SumoSummary | null = null;
  llmModeChoice: LlmModeChoiceResult | null = null;
  gtaPersonas: GtaPersonas | null = null;
  mobileCity: MobileCitySummary | null = null;
  mobileCityValidation: MobileCityValidationStatus | null = null;
  simDepots: DepotStats[] = [];
  showParcelLayer = false;
  showTourLayer = false;
  showPrivateRoutes = false;
  showLogisticsRoutes = false;
  freightUploadBusy = false;
  sevasUploadBusy = false;
  showCongestionLayer = false;

  private map!: L.Map;
  private regionLayer: L.Rectangle | null = null;
  private drawRect: L.Rectangle | null = null;
  private drawStart: L.LatLng | null = null;
  private zoneLayer: L.GeoJSON<ZoneFeatureProperties, Polygon> | null = null;
  private depotLayer = L.layerGroup();
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private parcelLayer: L.GeoJSON<ZoneFeatureProperties, Polygon> | null = null;
  private tourLayer: L.GeoJSON<TourFeatureProperties, LineString> | null = null;
  private congestionLayer: L.GeoJSON<CongestionFeatureProperties, LineString> | null = null;
  private parcelDensity: ParcelDensityGeoJson | null = null;
  private tourData: TourGeoJson | null = null;
  private trafficRoutes: TrafficRoutesGeoJson | null = null;
  private trafficRouteLayer: L.GeoJSON<TrafficRouteProperties, LineString> | null = null;
  private congestionData: CongestionGeoJson | null = null;
  energyData: EnergyGeoJson | null = null;
  energyQuality: EnergyQuality | null = null;
  showEnergyLayer = false;
  showUnconfirmedEnergy = false;
  private energyLayer: L.GeoJSON<EnergyGeoJson['features'][number]['properties'], Point> | null = null;
  private energyRequest = 0;

  private readonly api = inject(ApiService);

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
    this.clearEnergyInfrastructure();
    this.map?.remove();
  }

  // --- Szenario-Verwaltung -------------------------------------------------

  reloadScenarios(): void {
    this.api.listScenarios().subscribe({
      next: list => {
        this.scenarios = list;
        this.scenarioListError = '';
      },
      error: () => {
        this.scenarios = [];
        this.scenarioListError = 'Szenarien konnten nicht geladen werden';
      }
    });
  }

  newScenario(): void {
    this.sourcePlanChanged = false;
    this.stopPolling();
    this.api.scenarioDefaults().subscribe({
      next: defaults => {
        if (defaults) {
          defaults.population.useRealRouting = true;
          this.withMobileCityDefaults(defaults);
        }
        this.scenario = defaults;          // region ist hier null: noch kein Rechteck
        this.selectedScenarioId = '';
        this.job = null;
        this.artifacts = [];
        this.estimateInfo = '';
        this.estimateSourceUrl = '';
        this.estimatedPopulation = null;
        this.upload = null;
        this.roadNetworkUpload = null;
        this.osmDataUpload = null;
        this.commuterUpload = null;
        this.message = '';
        this.zonePreviewInfo = '';
        this.clearSimulation();
        this.drawRegion();
        this.zoneLayer?.remove();
        this.drawDepots();
      },
      error: () => this.message = 'Szenariovorlage konnte nicht geladen werden'
    });
  }

  onScenarioSelected(): void {
    this.sourcePlanChanged = false;
    this.stopPolling();
    const selected = this.scenarios.find(s => s.id === this.selectedScenarioId);
    if (!selected) {
      this.newScenario();
      return;
    }
    this.scenario = this.withMobileCityDefaults(JSON.parse(JSON.stringify({
      ...selected, referenceYear: selected.referenceYear ?? 2026,
      mobileCity: selected.mobileCity ?? { useTemplate: false, templateId: 'tramigo-v2' },
    })));
    this.scenario!.population.useRealRouting = true;
    this.job = null;
    this.artifacts = [];
    this.estimateInfo = '';
    this.estimateSourceUrl = '';
    this.estimatedPopulation = null;
    this.zonePreviewInfo = '';
    this.clearSimulation();
    this.loadUploads();
    this.zoneLayer?.remove();
    this.drawRegion();
    this.drawDepots();
    this.fitRegion();
    if (selected.region) {
      this.previewZones();
      this.estimatePopulation();
    }
    if (selected.id) {
      this.api.generationStatus(selected.id).subscribe({
        next: job => {
          this.job = job;
          if (job.state === 'DONE') {
            this.loadArtifacts();
          } else if (job.state === 'FAILED') {
            this.loadEnergyInfrastructure();
          }
        },
        // Der Job-Status lebt nur im Backend-Prozess. Nach einem Neustart sind die
        // persistierten Artefakte weiterhin gültig und müssen inklusive KPIs geladen werden.
        error: () => this.loadArtifacts()
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
        this.scenario = this.withMobileCityDefaults(saved);
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

  /** GTFS-Feed hochladen (ÖV-Fallback); Szenario muss gespeichert sein. */
  onGtfsSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    const id = this.scenario?.id;
    if (!file || !id) {
      return;
    }
    this.busy = true;
    this.api.uploadGtfs(id, file).subscribe({
      next: saved => {
        this.scenario = this.withMobileCityDefaults(saved);
        this.busy = false;
        this.message = 'GTFS-Feed hochgeladen';
      },
      error: err => {
        this.busy = false;
        this.message = 'GTFS-Upload fehlgeschlagen: ' + (err.error?.message ?? err.message);
      }
    });
  }

  deleteSelected(): void {
    const selected = this.scenarios.find(s => s.id === this.selectedScenarioId);
    if (!selected?.id || !confirm(`Szenario "${selected.name}" löschen?`)) {
      return;
    }
    this.busy = true;
    this.api.deleteScenario(selected.id).subscribe({
      next: () => {
        this.busy = false;
        this.reloadScenarios();
        this.newScenario();
      },
      error: () => {
        this.busy = false;
        this.message = 'Szenario konnte nicht gelöscht werden';
      }
    });
  }

  // --- Modellierung (Zwei-Achsen-Auswahl) ------------------------------------

  readonly calibrationModes = [
    { key: 'pedestrian', label: 'Fuß', title: 'Zielanteil aller Wege, die zu Fuß zurückgelegt werden.' },
    { key: 'bike', label: 'Rad', title: 'Zielanteil aller Wege, die mit dem Fahrrad zurückgelegt werden.' },
    { key: 'car', label: 'MIV (Fahrer)', title: 'Zielanteil aller Wege als Fahrer eines Pkw.' },
    { key: 'passenger', label: 'MIV (Mitf.)', title: 'Zielanteil aller Wege als Mitfahrer in einem Pkw.' },
    { key: 'publicTransport', label: 'ÖV', title: 'Zielanteil aller Wege mit öffentlichen Verkehrsmitteln.' },
  ];

  /** UI-Prozentwert; das Szenario-/API-Modell behält seinen Anteil im Intervall (0, 1]. */
  get samplingPercent(): number {
    return Math.round((this.scenario?.population.samplingFraction ?? 1) * 100);
  }

  set samplingPercent(value: number | string) {
    if (!this.scenario) {
      return;
    }
    const percent = Number(value);
    if (Number.isFinite(percent)) {
      this.scenario.population.samplingFraction = Math.min(100, Math.max(5, percent)) / 100;
    }
  }

  logisticsMetricLabel(key: LogisticsMetricKey): string {
    const value = this.scenario?.logistics[key] ?? 0;
    return value.toLocaleString('de-DE', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
  }

  jobPercent(key: string): string {
    const value = Number(this.job?.summary?.[key]);
    if (!Number.isFinite(value)) {
      return '–';
    }
    return (value * 100).toLocaleString('de-DE', { maximumFractionDigits: 1 }) + ' %';
  }

  externalRelationSourceLabel(): string {
    const source = String(this.job?.summary?.['commuterSource'] ?? 'gravity-model');
    if (source === 'upload-ba-pendler-vg250') {
      return 'amtliche BA-Pendlerziele';
    }
    if (source === 'gravity-model') {
      return 'Schätzung über acht Außenanker';
    }
    if (source === 'upload-coordinate-od') {
      return 'hochgeladene Koordinaten-Pendlermatrix';
    }
    return 'hochgeladene Pendlermatrix';
  }

  setLogisticsMetric(key: LogisticsMetricKey, value: number | string, maximum: number): void {
    if (!this.scenario) {
      return;
    }
    const parsed = Number(value);
    if (Number.isFinite(parsed)) {
      this.scenario.logistics[key] = Math.round(
        Math.min(maximum, Math.max(0, parsed)) * 100) / 100;
    }
  }

  deliverySharePercent(key: DeliveryShareKey): number {
    return Math.round((this.scenario?.logistics[key] ?? 0) * 100);
  }

  get deliveryShareTotalPercent(): number {
    if (!this.scenario) {
      return 0;
    }
    return Math.round((this.scenario.logistics.shareHome
      + this.scenario.logistics.shareWork
      + this.scenario.logistics.sharePackstation) * 100);
  }

  /** Der manuelle Netz-Upload erscheint erst, wenn ein angeforderter Routinglauf zurückfiel. */
  get roadNetworkFallbackNeeded(): boolean {
    return this.scenario?.population.useRealRouting === true
      && this.job?.summary?.['matrixSource'] === 'beeline-estimate';
  }

  /** Hält alle drei Zustellanteile als ganze Prozentwerte exakt bei zusammen 100 %. */
  setDeliverySharePercent(key: DeliveryShareKey, value: number | string): void {
    if (!this.scenario) {
      return;
    }
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) {
      return;
    }
    const target = Math.min(100, Math.max(0, Math.round(parsed)));
    const keys: DeliveryShareKey[] = ['shareHome', 'shareWork', 'sharePackstation'];
    const others = keys.filter(candidate => candidate !== key);
    const firstOld = this.deliverySharePercent(others[0]);
    const secondOld = this.deliverySharePercent(others[1]);
    const remaining = 100 - target;
    const otherTotal = firstOld + secondOld;
    const first = otherTotal > 0
      ? Math.round(remaining * firstOld / otherTotal)
      : Math.floor(remaining / 2);
    const second = remaining - first;
    this.scenario.logistics[key] = target / 100;
    this.scenario.logistics[others[0]] = first / 100;
    this.scenario.logistics[others[1]] = second / 100;
  }

  /**
   * Regionstyp-Vorlagen für den Kalibrierungs-Zielwert (RegioStaR-Raumtypen).
   * Richtwerte nach dem MiD-2017-Stadt-Land-Muster; an offizielle Regionaltabellen anpassbar.
   * Jede Vorlage summiert sich zu 1,0.
   */
  readonly regionPresets: { id: string; label: string; split: Record<string, number> }[] = [
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

  private withMobileCityDefaults(scenario: Scenario): Scenario {
    scenario.energyInfrastructure ??= { enabled: false, useBnetza: true, useOsm: true };
    scenario.energyInfrastructure.verifyNetworkAccess ??= false;
    scenario.energyInfrastructure.maxSnapDistanceM ??= 50;
    scenario.vehicleOperations ??= emptyOperations();
    scenario.vehicleConfiguration ??= emptyVehicleConfiguration();
    scenario.freight ??= { enabled: false, day: 0, relations: [] };
    scenario.companyTours ??= { enabled: false, imports: [] };
    scenario.referenceYear ??= 2026;
    scenario.mobileCity ??= { useTemplate: false, templateId: 'tramigo-v2' };
    scenario.population.useCensusStructure ??= false;
    scenario.mobileCity.useRegionalFleet ??= false;
    scenario.mobileCity.electricShare ??= null;
    scenario.mobileCity.useRegionalCharging ??= false;
    scenario.mobileCity.chargingPointsPer1000 ??= null;
    scenario.mobileCity.carsharingVehiclesPer1000 ??= null;
    scenario.mobileCity.carsharingMemberShare ??= null;
    scenario.mobileCity.autoSurrounding ??= false;
    scenario.mobileCity.surroundingBufferKm ??= 10;
    scenario.mobileCity.surroundingPopulation ??= null;
    scenario.mobileCity.surroundingEducationTripShare ??= null;
    scenario.mobileCity.surroundingWorkTripShare ??= null;
    scenario.mobileCity.surroundingShoppingTripShare ??= null;
    scenario.mobileCity.surroundingLeisureTripShare ??= null;
    scenario.mobileCity.regionalExport ??= false;
    scenario.mobileCity.validateNative ??= false;
    scenario.mobileCity.maxDistricts ??= 100;
    scenario.mobileCity.allowSyntheticTransitFallback ??= true;
    scenario.mobileCity.transitVehiclesPer1000 ??= 1;
    scenario.mobileCity.settlementAreaShare ??= 0.5;
    return scenario;
  }

  setMobileCityMode(mode: 'regional' | 'template', enabled: boolean): void {
    if (!this.scenario) return;
    if (enabled) {
      if (mode === 'regional') this.scenario.mobileCity.useTemplate = false;
      else this.scenario.mobileCity.regionalExport = false;
    } else {
      this.scenario.mobileCity.validateNative = false;
    }
  }

  /**
   * Produces the smallest self-contained MobileCity configuration that can be exported
   * without optional regional services. Every fallback remains visible in the data inventory.
   */
  configureMobileCityQuickStart(): void {
    if (!this.scenario) return;
    this.scenario.targetModels = ['mobilecity'];
    this.scenario.population.behaviorSource = 'template';
    this.scenario.population.useRealRouting = false;
    this.scenario.population.externalTrips = false;
    this.scenario.mobileCity.regionalExport = true;
    this.scenario.mobileCity.useTemplate = false;
    this.scenario.mobileCity.templateId ||= 'tramigo-v2';
    this.scenario.mobileCity.useRegionalFleet = false;
    this.scenario.mobileCity.electricShare ??= 0;
    this.scenario.mobileCity.useRegionalCharging = false;
    this.scenario.mobileCity.allowSyntheticTransitFallback = true;
    this.scenario.mobileCity.transitVehiclesPer1000 = 1;
    this.scenario.mobileCity.settlementAreaShare = 0.5;
    this.scenario.mobileCity.maxDistricts = Math.min(this.scenario.mobileCity.maxDistricts ?? 12, 12);
    this.scenario.mobileCity.autoSurrounding = false;
    this.scenario.mobileCity.surroundingPopulation = null;
    this.scenario.mobileCity.surroundingEducationTripShare = null;
    this.scenario.mobileCity.surroundingWorkTripShare = null;
    this.scenario.mobileCity.surroundingShoppingTripShare = null;
    this.scenario.mobileCity.surroundingLeisureTripShare = null;
    this.scenario.mobileCity.validateNative = false;
    this.message = 'MobileCity-Schnellstart gesetzt: bis zu 12 Districts, Luftlinienmatrizen, modellierter ÖV, 0 % BEV und kein Umland. Beim Generieren wird diese Konfiguration automatisch gespeichert.';
  }

  mobileCityPlanItems(): MobileCityDataItem[] {
    const scenario = this.scenario;
    if (!scenario) return [];
    const populationManual = scenario.population.totalPersons != null;
    const censusStructure = scenario.population.useCensusStructure === true;
    const regionalFleet = scenario.mobileCity.useRegionalFleet === true;
    const manualBev = scenario.mobileCity.electricShare != null;
    const regionalCharging = scenario.mobileCity.useRegionalCharging === true;
    const manualCharging = scenario.mobileCity.chargingPointsPer1000 != null;
    const manualSharing = scenario.mobileCity.carsharingVehiclesPer1000 != null
      || scenario.mobileCity.carsharingMemberShare != null;
    const automaticSurrounding = scenario.mobileCity.autoSurrounding === true;
    const surroundingRequested = automaticSurrounding
      || (scenario.mobileCity.surroundingPopulation ?? 0) > 0;
    const surroundingComplete = surroundingRequested
      && scenario.mobileCity.surroundingEducationTripShare != null
      && scenario.mobileCity.surroundingWorkTripShare != null
      && scenario.mobileCity.surroundingShoppingTripShare != null
      && scenario.mobileCity.surroundingLeisureTripShare != null
      && scenario.population.externalTrips;
    const bevResolution: MobileCityDataResolution = regionalFleet && manualBev
      ? 'fallback' : regionalFleet ? 'automatic' : manualBev ? 'manual' : 'missing';
    const bevStatus: MobileCityDataStatus = regionalFleet && manualBev
      ? 'mixed' : regionalFleet ? 'observed' : manualBev ? 'assumption' : 'missing';
    const item = (id: string, label: string, status: MobileCityDataStatus,
                  resolution: MobileCityDataResolution, source: string,
                  required: boolean, note: string): MobileCityDataItem => ({
      id, label, status, resolution, source, required, note, coverage: status === 'missing' ? 0 : 1
    });
    return [
      item('geography', 'Gebiet und Districts', scenario.region ? 'derived' : 'missing',
        scenario.region ? 'automatic' : 'missing', scenario.region ? 'Kartenausschnitt und Zonierung' : 'Region fehlt', true,
        'Districts werden beim Lauf aus allen internen Zonen gebildet.'),
      item('population-total', 'Einwohnerzahl', populationManual ? 'assumption' : 'derived',
        populationManual ? 'manual' : 'automatic', populationManual ? 'Manuelle Gesamtzahl' : 'Upload → Zensus → OSM → Dichte', true,
        'Die tatsächlich verwendete Quelle und ihr Hash werden erst im Lauf aufgelöst.'),
      item('population-structure', 'Alters- und Haushaltsstruktur', censusStructure ? 'derived' : 'assumption',
        censusStructure ? 'automatic' : 'profile', censusStructure ? 'Zensus 2022 + KIT-Haushalte' : 'Haushaltspool ohne Regionalgewichtung', true,
        censusStructure ? 'Nicht abdeckbare Haushaltsgruppen bleiben als Fallback sichtbar.' : 'Keine regionale Strukturgewichtung gewählt.'),
      item('private-cars', 'Private Pkw', 'derived', 'automatic', 'Synthetische Haushalte', true,
        `${scenario.population.carsPerHousehold} Pkw je Haushalt als Modellparameter.`),
      item('private-bev', 'Privater BEV-Anteil', bevStatus, bevResolution,
        regionalFleet && manualBev ? 'KBA automatisch, manuelle Vorgabe bei Lücken' : regionalFleet ? 'KBA/BKG automatisch' : manualBev ? 'Manuelle Annahme' : 'Quelle oder Annahme fehlt', true,
        regionalFleet ? 'KBA-Stand 01.07.2026; PHEV bleibt separat.' : manualBev ? 'Explizite Annahme für alle Zonen.' : 'Blockiert das Regionalpaket.'),
      item('networks', 'Auto-, Rad- und Fußverbindungen', 'derived', 'automatic',
        scenario.population.useRealRouting ? 'OSM-Routing mit Luftlinien-Fallback' : 'Luftlinienmatrizen', true,
        'MobileCity erhält daraus aggregierte District-Verbindungen.'),
      item('transit', 'ÖV-Angebot', scenario.population.gtfsUploadPath ? 'observed' : 'derived',
        scenario.population.gtfsUploadPath ? 'manual' : scenario.mobileCity.allowSyntheticTransitFallback ? 'fallback' : 'automatic',
        scenario.population.gtfsUploadPath ? 'Hochgeladener GTFS-Feed' : scenario.mobileCity.allowSyntheticTransitFallback ? 'Regionaler GTFS-Abruf + freigegebener Modellfallback' : 'Nur regionaler GTFS-Abruf', true,
        scenario.mobileCity.allowSyntheticTransitFallback
          ? `Der Lauf wählt einen repräsentativen Werktag. Falls kein Regionsfeed verfügbar ist: ${scenario.mobileCity.transitVehiclesPer1000 ?? 1} Fahrzeuge je 1.000 Einwohner.`
          : 'Ohne auflösbaren Regionsfeed oder Upload stoppt der Export.'),
      item('chargers', 'Öffentliche Ladepunkte', regionalCharging && manualCharging ? 'mixed' : regionalCharging ? 'observed' : manualCharging ? 'assumption' : 'missing',
        regionalCharging && manualCharging ? 'fallback' : regionalCharging ? 'automatic' : manualCharging ? 'manual' : 'missing',
        regionalCharging && manualCharging ? 'BNetzA automatisch, Nutzervorgabe bei Quellenfehler' : regionalCharging ? 'BNetzA Ladesäulenregister' : manualCharging ? 'Manuelle Annahme' : 'Quelle oder Annahme fehlt', false,
        regionalCharging ? 'Öffentliche Ladepunkte werden nach Koordinaten den Districts zugeordnet; Datenjahr und Szenariojahr bleiben getrennt.'
          : manualCharging ? `${scenario.mobileCity.chargingPointsPer1000} Ladepunkte je 1.000 Einwohner.` : 'Bleibt im Export unbekannt; MobileCity modelliert die weitere Entwicklung endogen.'),
      item('sharing', 'Carsharing-Angebot', 'assumption', manualSharing ? 'manual' : 'profile',
        manualSharing ? 'Nutzervorgabe' : 'Regionalexport-Baseline', false,
        manualSharing
          ? `${scenario.mobileCity.carsharingVehiclesPer1000 ?? 0} Fahrzeuge je 1.000 Einwohner; ${((scenario.mobileCity.carsharingMemberShare ?? 0) * 100).toLocaleString('de-DE')} % Mitgliederanteil. Konstante Szenarioannahme.`
          : 'Explizite Nullannahme; keine kleinräumig vollständige Regionalquelle aufgelöst.'),
      item('model-profile', 'Modellparameter', 'assumption', 'profile', scenario.mobileCity.templateId, true,
        'Hashgeprüftes, für das Gebiet noch unkalibriertes Profil.'),
      item('surrounding', 'Umlandbevölkerung', surroundingComplete ? (automaticSurrounding ? 'derived' : 'assumption') : 'missing',
        surroundingComplete ? (automaticSurrounding ? (scenario.mobileCity.surroundingPopulation != null ? 'fallback' : 'automatic') : 'manual') : 'missing',
        surroundingComplete ? (automaticSurrounding ? 'Zensus-2022-Pufferring' : 'Nutzervorgabe') : surroundingRequested ? 'Unvollständige Umlandkonfiguration' : 'Noch nicht modelliert', surroundingRequested,
        surroundingComplete
          ? automaticSurrounding
            ? `${scenario.mobileCity.surroundingBufferKm ?? 10} km Pufferring;${scenario.mobileCity.surroundingPopulation != null ? ` manuelle Gesamtsumme ${scenario.mobileCity.surroundingPopulation};` : ''} zweckspezifische eingehende Wegeanteile werden exportiert.`
            : `${scenario.mobileCity.surroundingPopulation} Personen; zweckspezifische eingehende Wegeanteile werden exportiert.`
          : surroundingRequested
            ? 'Alle vier Wegeanteile und aktivierte Außenbeziehungen sind erforderlich.'
          : 'Externe Zielanker ersetzen ohne explizite Eingabe keine Umlandbevölkerung.'),
      item('logistics', 'Logistik', 'missing', 'missing', 'Kein bestätigter MobileCity-Vertrag', false,
        'Bleibt außerhalb dieses Exportpakets.')
    ];
  }

  mobileCityPlanReady(): boolean {
    return !this.mobileCityPlanItems().some(item => item.required && item.status === 'missing');
  }

  mobileCityResolutionLabel(resolution: MobileCityDataResolution): string {
    return ({ automatic: 'automatisch', manual: 'manuell', fallback: 'Automatik + Fallback',
      profile: 'Modellprofil', missing: 'fehlt' })[resolution];
  }

  mobileCityStatusLabel(status: MobileCityDataStatus): string {
    return ({ observed: 'Quelldaten', derived: 'abgeleitet', assumption: 'Annahme',
      mixed: 'gemischt', missing: 'offen' })[status];
  }

  mobileCityDisplayValue(item: MobileCityDataItem): string {
    if (item.value == null) return '';
    if (item.unit === 'Anteil' && typeof item.value === 'number') {
      return `${(item.value * 100).toLocaleString('de-DE', { maximumFractionDigits: 2 })} %`;
    }
    return `${item.value}${item.unit ? ` ${item.unit}` : ''}`;
  }

  mobileCityResultStale(): boolean {
    if (!this.scenario?.targetModels?.includes('mobilecity') || !this.scenario.mobileCity.regionalExport) {
      return true;
    }
    const previous = this.mobileCity?.dataInventory?.inputSnapshot;
    return previous != null && JSON.stringify(previous) !== JSON.stringify(this.mobileCityInputSnapshot());
  }

  private mobileCityInputSnapshot(): Record<string, unknown> {
    const scenario = this.scenario;
    if (!scenario) return {};
    const region = scenario.region ? {
      maxLat: scenario.region.maxLat, maxLon: scenario.region.maxLon,
      minLat: scenario.region.minLat, minLon: scenario.region.minLon
    } : {};
    return {
      allowSyntheticTransitFallback: scenario.mobileCity.allowSyntheticTransitFallback ?? true,
      carsPerHousehold: scenario.population.carsPerHousehold,
      carsharingMemberShare: scenario.mobileCity.carsharingMemberShare ?? null,
      carsharingVehiclesPer1000: scenario.mobileCity.carsharingVehiclesPer1000 ?? null,
      chargingPointsPer1000: scenario.mobileCity.chargingPointsPer1000 ?? null,
      electricShare: scenario.mobileCity.electricShare ?? null,
      externalTrips: scenario.population.externalTrips,
      gtfsUploadPath: scenario.population.gtfsUploadPath ?? null,
      maxDistricts: scenario.mobileCity.maxDistricts ?? 100,
      populationTotal: scenario.population.totalPersons,
      referenceYear: scenario.referenceYear,
      region,
      samplingFraction: scenario.population.samplingFraction,
      settlementAreaShare: scenario.mobileCity.settlementAreaShare ?? 0.5,
      autoSurrounding: scenario.mobileCity.autoSurrounding ?? false,
      surroundingBufferKm: scenario.mobileCity.surroundingBufferKm ?? 10,
      surroundingEducationTripShare: scenario.mobileCity.surroundingEducationTripShare ?? null,
      surroundingLeisureTripShare: scenario.mobileCity.surroundingLeisureTripShare ?? null,
      surroundingPopulation: scenario.mobileCity.surroundingPopulation ?? null,
      surroundingShoppingTripShare: scenario.mobileCity.surroundingShoppingTripShare ?? null,
      surroundingWorkTripShare: scenario.mobileCity.surroundingWorkTripShare ?? null,
      templateId: scenario.mobileCity.templateId,
      transitVehiclesPer1000: scenario.mobileCity.transitVehiclesPer1000 ?? 1,
      useCensusStructure: scenario.population.useCensusStructure ?? false,
      useKitPopulation: scenario.population.useKitPopulation,
      useRegionalCharging: scenario.mobileCity.useRegionalCharging ?? false,
      useRegionalFleet: scenario.mobileCity.useRegionalFleet ?? false,
      zoning: {
        cellSizeMeters: scenario.zoning.cellSizeMeters,
        maxZones: scenario.zoning.maxZones,
        mode: scenario.zoning.mode
      }
    };
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
      if (target === 'logitopp' && targets.includes('sumo')) {
        this.message = 'SUMO benötigt die von logiTopp erzeugte Nachfrage.';
        return;
      }
      targets.splice(i, 1);
      if (target === 'sumo' && this.scenario.freight) {
        this.scenario.freight.sevasEnabled = false; this.scenario.freight.profileChecksEnabled = false;
      }
      if (target === 'sumo') {
        if (this.scenario.energyInfrastructure) this.scenario.energyInfrastructure.verifyNetworkAccess = false;
      }
      if (target === 'logitopp') {
        this.scenario.logistics.returns.enabled = false;
      }
    } else {
      targets.push(target);
      if (target === 'sumo' && !targets.includes('logitopp')) {
        targets.unshift('logitopp');
        this.message = 'logiTopp wurde automatisch aktiviert, weil SUMO dessen Nachfrage simuliert.';
      }
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

  /** Nach Regionsänderung: Rechteck zeichnen, Zonenvorschau, amtliche/OSM-Einwohner prüfen. */
  private afterRegionChanged(): void {
    this.drawRegion();
    this.previewZones(true);
    this.estimatePopulation();
  }

  private estimatePopulation(): void {
    if (!this.scenario?.region) {
      return;
    }
    this.estimateInfo = 'Einwohnerquelle wird geprüft (Zensus 2022 → OSM → Dichte)…';
    this.estimateSourceUrl = '';
    this.estimatedPopulation = null;
    this.api.estimateRegion(this.scenario.region).subscribe({
      next: estimate => {
        this.estimateSourceUrl = estimate.sourceUrl;
        if (estimate.source === 'zensus-2022' && estimate.estimatedPopulation > 0) {
          this.estimatedPopulation = estimate.estimatedPopulation;
          const reference = estimate.referenceDate
            ? `, Stichtag ${estimate.referenceDate.split('-').reverse().join('.')}` : '';
          this.estimateInfo = `${estimate.estimatedPopulation.toLocaleString('de-DE')} Einwohner `
            + `(${estimate.sourceLabel}${reference})`;
        } else if (estimate.source === 'osm-places' && estimate.estimatedPopulation > 0) {
          this.estimatedPopulation = estimate.estimatedPopulation;
          const names = estimate.places.slice(0, 3).map(p => p.name).join(', ');
          this.estimateInfo = `≈ ${estimate.estimatedPopulation.toLocaleString('de-DE')} Einwohner `
            + `(OSM-Siedlungsdaten: ${names}${estimate.places.length > 3 ? ', …' : ''})`;
        } else {
          this.estimateInfo = 'Keine Zensus-/OSM-Einwohnerdaten gefunden – es gilt die Dichte-Schätzung';
        }
      },
      error: () => {
        this.estimateSourceUrl = '';
        this.estimatedPopulation = null;
        this.estimateInfo = 'Einwohner-Schätzung nicht verfügbar';
      }
    });
  }

  /** Zeigt die automatische Schätzung, ohne sie als manuelle Szenario-Vorgabe zu persistieren. */
  get displayedTotalPersons(): number | null {
    return this.scenario?.population.totalPersons ?? this.estimatedPopulation;
  }

  onTotalPersonsChanged(value: number | string | null): void {
    if (!this.scenario) {
      return;
    }
    if (value === null || value === '') {
      this.scenario.population.totalPersons = null;
      return;
    }
    const parsed = Number(value);
    this.scenario.population.totalPersons = Number.isFinite(parsed)
      ? Math.max(0, Math.round(parsed)) : null;
  }

  previewZones(useFinestResolution = false): void {
    if (!this.scenario?.region) {
      this.message = 'Bitte zuerst eine Region auf der Karte zeichnen';
      return;
    }
    const requested = useFinestResolution ? 100 : this.scenario.zoning.cellSizeMeters;
    this.api.previewZones(this.scenario.region, requested,
        this.scenario.zoning.mode, this.scenario.zoning.maxZones).subscribe({
      next: geojson => {
        this.zoneLayer?.remove();
        this.zoneLayer = L.geoJSON<ZoneFeatureProperties, Polygon>(geojson, {
          style: { color: '#15314f', weight: 1, fillOpacity: 0.05 }
        }).addTo(this.map);
        if (this.scenario) {
          this.scenario.zoning.cellSizeMeters = geojson.cellSizeMeters;
        }
        if (useFinestResolution) {
          const maxZones = this.scenario?.zoning.maxZones ?? 600;
          const standardNote = this.scenario?.zoning.mode === 'inspire'
            ? ' (feinste passende INSPIRE-Standardstufe: 100 m, 1 km oder 10 km)'
            : this.scenario?.zoning.mode === 'epsg3035'
              ? ` (feinste ausgerichtete 100-m-Stufe innerhalb des Limits von ${maxZones} Zonen)`
              : ` (feinste Auflösung innerhalb des Limits von ${maxZones} Zonen)`;
          this.zonePreviewInfo = `Automatisch vorgeschlagen: `
            + `${geojson.cellSizeMeters.toLocaleString('de-DE')} m${standardNote}.`;
          this.message = `${geojson.features.length} Zonen – automatisch `
            + `${geojson.cellSizeMeters.toLocaleString('de-DE')} m vorgeschlagen`;
        } else if (geojson.autoAdjusted) {
          const maxZones = this.scenario?.zoning.maxZones ?? 600;
          const reason = this.scenario?.zoning.mode === 'inspire'
            ? `INSPIRE-Standardstufe und ${maxZones}-Zonen-Limit`
            : this.scenario?.zoning.mode === 'epsg3035'
              ? `100-m-Stufe und ${maxZones}-Zonen-Limit`
              : `${maxZones}-Zonen-Limit`;
          this.zonePreviewInfo = `Auf ${geojson.cellSizeMeters.toLocaleString('de-DE')} m angepasst `
            + `(${reason}). Der tatsächlich verwendete Wert steht jetzt im Feld.`;
          this.message = `${geojson.features.length} Zonen – Zellgröße auf `
            + `${geojson.cellSizeMeters.toLocaleString('de-DE')} m angepasst`;
        } else {
          this.zonePreviewInfo = '';
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
    this.uploadBusy = true;
    this.api.deletePopulationGrid(this.scenario.id).subscribe({
      next: () => {
        this.upload = null;
        this.uploadBusy = false;
        this.message = 'Bevölkerungsraster entfernt';
      },
      error: () => {
        this.uploadBusy = false;
        this.message = 'Bevölkerungsraster konnte nicht entfernt werden';
      }
    });
  }

  onRoadNetworkSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !this.scenario?.id) {
      if (!this.scenario?.id) {
        this.message = 'Bitte Szenario zuerst speichern, dann Straßennetz hochladen';
      }
      return;
    }
    this.roadNetworkUploadBusy = true;
    this.api.uploadRoadNetwork(this.scenario.id, file).subscribe({
      next: summary => {
        this.roadNetworkUpload = summary;
        this.roadNetworkUploadBusy = false;
        this.message = `OSM-Straßennetz übernommen (${summary.highwayWays.toLocaleString('de-DE')} Straßen)`;
      },
      error: err => {
        this.roadNetworkUploadBusy = false;
        this.message = 'Netz-Upload fehlgeschlagen: ' + (err.error?.message ?? err.message);
      }
    });
  }

  removeRoadNetwork(): void {
    if (!this.scenario?.id) {
      return;
    }
    this.roadNetworkUploadBusy = true;
    this.api.deleteRoadNetwork(this.scenario.id).subscribe({
      next: () => {
        this.roadNetworkUpload = null;
        this.roadNetworkUploadBusy = false;
        this.message = 'OSM-Straßennetz entfernt; Routing nutzt wieder Server-Konfiguration oder Overpass';
      },
      error: () => {
        this.roadNetworkUploadBusy = false;
        this.message = 'Straßennetz konnte nicht entfernt werden';
      }
    });
  }

  onOsmDataSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !this.scenario?.id) {
      if (!this.scenario?.id) {
        this.message = 'Bitte Szenario zuerst speichern, dann OSM-Ortsdatensnapshot hochladen';
      }
      return;
    }
    this.osmDataUploadBusy = true;
    this.api.uploadOsmData(this.scenario.id, file).subscribe({
      next: summary => {
        this.osmDataUpload = summary;
        this.osmDataUploadBusy = false;
        this.message = `OSM-Ortsdatensnapshot übernommen (${summary.pois.toLocaleString('de-DE')} POIs)`;
      },
      error: err => {
        this.osmDataUploadBusy = false;
        this.message = 'OSM-Ortsdatensnapshot fehlgeschlagen: '
          + (err.error?.message ?? err.message);
      }
    });
  }

  removeOsmData(): void {
    if (!this.scenario?.id) {
      return;
    }
    this.osmDataUploadBusy = true;
    this.api.deleteOsmData(this.scenario.id).subscribe({
      next: () => {
        this.osmDataUpload = null;
        this.osmDataUploadBusy = false;
        this.message = 'OSM-Ortsdatensnapshot entfernt; Orte und Einrichtungen werden wieder live geladen';
      },
      error: () => {
        this.osmDataUploadBusy = false;
        this.message = 'OSM-Ortsdatensnapshot konnte nicht entfernt werden';
      }
    });
  }

  onCommutersSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !this.scenario?.id) {
      if (!this.scenario?.id) {
        this.message = 'Bitte Szenario zuerst speichern, dann Pendlermatrix hochladen';
      }
      return;
    }
    this.commuterUploadBusy = true;
    this.api.uploadCommuters(this.scenario.id, file).subscribe({
      next: summary => {
        this.commuterUpload = summary;
        this.workplaceMarginUpload = summary.workplaceMargin;
        this.commuterUploadBusy = false;
        const coverage = summary.source === 'upload-ba-pendler-vg250'
          ? `, ${(summary.coverage * 100).toLocaleString('de-DE', { maximumFractionDigits: 1 })} % BA-Abdeckung`
          : '';
        const margin = summary.workplaceMargin
          ? `; amtliche Auspendlerquote ${(summary.workplaceMargin.externalShare * 100)
            .toLocaleString('de-DE', { maximumFractionDigits: 1 })} % automatisch zugeordnet`
          : '';
        this.message = `Pendlermatrix übernommen (${summary.relations.toLocaleString('de-DE')} `
          + `Relationen${coverage}${margin})`;
      },
      error: err => {
        this.commuterUploadBusy = false;
        this.message = 'Pendler-Upload fehlgeschlagen: ' + (err.error?.message ?? err.message);
      }
    });
  }

  removeCommuters(): void {
    if (!this.scenario?.id) {
      return;
    }
    this.commuterUploadBusy = true;
    this.api.deleteCommuters(this.scenario.id).subscribe({
      next: () => {
        this.commuterUpload = null;
        if (this.workplaceMarginUpload?.source === 'ba-gemeindedaten-2025'
            || this.workplaceMarginUpload?.source === '') {
          this.workplaceMarginUpload = null;
        }
        this.commuterUploadBusy = false;
        this.message = 'Pendlermatrix entfernt; Arbeitsziele nutzen wieder das Gravitationsmodell';
      },
      error: () => {
        this.commuterUploadBusy = false;
        this.message = 'Pendlermatrix konnte nicht entfernt werden';
      }
    });
  }

  onWorkplaceMarginsSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !this.scenario?.id) {
      if (!this.scenario?.id) {
        this.message = 'Bitte Szenario zuerst speichern, dann die amtliche Auspendlerquote hochladen';
      }
      return;
    }
    this.workplaceMarginUploadBusy = true;
    this.api.uploadWorkplaceMargins(this.scenario.id, file).subscribe({
      next: summary => {
        this.workplaceMarginUpload = summary;
        this.workplaceMarginUploadBusy = false;
        this.message = `Amtliche Auspendlerquote übernommen (${(summary.externalShare * 100)
          .toLocaleString('de-DE', { maximumFractionDigits: 2 })} % Auspendelnde)`;
      },
      error: err => {
        this.workplaceMarginUploadBusy = false;
        this.message = 'Upload der Auspendlerquote fehlgeschlagen: '
          + (err.error?.message ?? err.message);
      }
    });
  }

  removeWorkplaceMargins(): void {
    if (!this.scenario?.id) {
      return;
    }
    this.workplaceMarginUploadBusy = true;
    this.api.deleteWorkplaceMargins(this.scenario.id).subscribe({
      next: () => {
        this.workplaceMarginUpload = null;
        this.workplaceMarginUploadBusy = false;
        this.message = 'Amtliche Auspendlerquote entfernt; Arbeitsziele nutzen wieder die Standardschätzung';
      },
      error: () => {
        this.workplaceMarginUploadBusy = false;
        this.message = 'Amtliche Auspendlerquote konnte nicht entfernt werden';
      }
    });
  }

  private loadUploads(): void {
    this.upload = null;
    this.roadNetworkUpload = null;
    this.osmDataUpload = null;
    this.commuterUpload = null;
    this.workplaceMarginUpload = null;
    if (!this.scenario?.id) {
      return;
    }
    this.api.listUploads(this.scenario.id).subscribe({
      next: list => {
        const grid = list.find(u => u.name === 'population-grid.csv');
        if (grid) {
          this.upload = {
            name: grid.name, format: 'hochgeladen', points: 0,
            totalPopulation: 0, skippedLines: 0
          };
        }
        const network = list.find(u => u.name === 'road-network.osm');
        if (network) {
          this.roadNetworkUpload = {
            name: network.name, nodes: 0, ways: 0, highwayWays: 0,
            minLat: 0, minLon: 0, maxLat: 0, maxLon: 0, sha256: ''
          };
        }
        const osmData = list.find(u => u.name === 'osm-data.json');
        if (osmData) {
          this.osmDataUpload = {
            name: osmData.name, elements: 0, pois: 0, places: 0, sha256: ''
          };
        }
        const commuters = list.find(u => u.name === 'commuters.csv');
        if (commuters) {
          this.commuterUpload = {
            name: commuters.name, relations: 0, totalCommuters: 0,
            relationsWithHomeInRegion: 0, source: '', exportTotalCommuters: 0,
            coverage: 0, skippedAggregateRows: 0, unresolvedMunicipalities: 0,
            workplaceMargin: null
          };
        }
        const workplaceMargins = list.find(u => u.name === 'workplace-margins.csv');
        if (workplaceMargins) {
          this.workplaceMarginUpload = {
            name: workplaceMargins.name, source: '', date: '', municipalities: 0,
            residentWorkers: 0, outboundWorkers: 0, externalShare: 0,
            unresolvedMunicipalities: 0
          };
        }
      },
      error: () => this.message = 'Upload-Status konnte nicht geladen werden'
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
    this.clearEnergyInfrastructure();
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

  companyTourPending = false;
  dataWorkspaceOpen = false;
  dataImportPending = false;
  sourcePlanChanged = false;
  openDataWorkspace(): void {
    this.dataWorkspaceOpen = true;
    (document.getElementById('data-workspace-dialog') as HTMLDialogElement | null)?.showModal();
  }
  closeDataWorkspace(event?: Event): void {
    event?.preventDefault();
    if (this.dataImportPending && !window.confirm('Ungespeicherte Importzuordnungen und Quellenprofilentwürfe verwerfen? Gespeicherte Importe und Profilversionen bleiben erhalten.')) return;
    (document.getElementById('data-workspace-dialog') as HTMLDialogElement | null)?.close();
    this.dataWorkspaceOpen = false; this.dataImportPending = false;
  }
  dataPlanChanged(): void { this.sourcePlanChanged = true; }

  generate(): void {
    if (this.dataImportPending) { this.message = 'Geänderten Datenimport oder Quellenprofilentwurf zuerst speichern oder die Änderungen im Datenarbeitsbereich verwerfen.'; return; }
    if (this.companyTourPending) {
      this.message = 'Geänderten Unternehmenstouren-Kontext zuerst speichern oder durch erneute Importauswahl verwerfen.';
      return;
    }
    if (!this.scenario?.id) {
      this.message = 'Bitte zuerst speichern';
      return;
    }
    if (this.randomizeEachRun) {
      this.scenario.population.seed = this.createRandomSeed();
    }
    this.busy = true;
    this.api.updateScenario(this.scenario).subscribe({
      next: saved => {
        this.scenario = this.withMobileCityDefaults(saved);
        this.selectedScenarioId = saved.id ?? this.selectedScenarioId;
        this.busy = false;
        this.startGeneration(saved.id ?? this.selectedScenarioId);
      },
      error: err => {
        this.busy = false;
        this.message = 'Konfiguration konnte vor der Generierung nicht gespeichert werden: '
          + (err.error?.message ?? err.message);
      }
    });
  }

  private startGeneration(scenarioId: string): void {
    this.clearEnergyInfrastructure();
    this.api.generate(scenarioId).subscribe({
      next: job => {
        this.job = job;
        this.artifacts = [];
        this.startPolling(job.scenarioId);
      },
      error: err => this.message = 'Fehler: ' + (err.error?.message ?? err.message)
    });
  }

  /** Erzeugt nur den neuen reproduzierbaren Lauf-Seed; die Pipeline leitet alles Weitere davon ab. */
  private createRandomSeed(): number {
    const values = new Uint32Array(1);
    globalThis.crypto.getRandomValues(values);
    return values[0] || 1;
  }

  private startPolling(scenarioId: string): void {
    this.stopPolling();
    this.pollTimer = setInterval(() => {
      this.api.generationStatus(scenarioId).subscribe({
        next: job => {
          if (this.scenario?.id !== scenarioId) {
            this.stopPolling();
            return;
          }
          this.job = job;
          if (job.state === 'DONE' || job.state === 'FAILED') {
            this.stopPolling();
            if (job.state === 'DONE') {
              this.sourcePlanChanged = false;
              this.loadArtifacts();
            } else {
              this.loadEnergyInfrastructure();
            }
          }
        },
        error: () => {
          this.stopPolling();
          this.message = 'Job-Status konnte nicht geladen werden';
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
      this.api.listArtifacts(this.scenario.id).subscribe({
        next: list => this.artifacts = list,
        error: () => {
          this.artifacts = [];
          this.message = 'Artefakte konnten nicht geladen werden';
        }
      });
      this.loadSimulation();
      this.api.artifactJson<MobileCitySummary>(this.scenario.id, 'mobilecity/summary.json')
        .subscribe({ next: s => this.mobileCity = s, error: () => this.mobileCity = null });
      this.api.artifactJson<MobileCityValidationStatus>(this.scenario.id, 'mobilecity-validation/status.json')
        .subscribe({ next: s => this.mobileCityValidation = s, error: () => this.mobileCityValidation = null });
    }
  }

  /** Download-URL einer MobileCity-Tabelle. */
  mobileCityUrl(table: string): string {
    return this.artifactUrl('mobilecity/' + table);
  }

  // --- Simulationsergebnisse (Stufe 12) ----------------------------------------

  /** Lädt KPIs, Depot-Statistiken und Kartenlayer; 404 = Simulation übersprungen. */
  private loadSimulation(): void {
    this.clearSimulation();
    this.loadEnergyInfrastructure();
    const id = this.scenario?.id;
    if (!id) {
      return;
    }
    this.api.artifactJson<TrafficDemandSummary>(id, 'demand/traffic-summary.json')
      .subscribe({ next: value => this.trafficDemandSummary = value, error: () => this.trafficDemandSummary = null });
    this.api.artifactJson<SimKpis>(id, 'simulation/kpis.json')
      .subscribe({ next: kpis => this.simKpis = kpis, error: () => this.simKpis = null });
    this.api.artifactJson<DepotStats[]>(id, 'simulation/depot-stats.json')
      .subscribe({ next: depots => this.simDepots = depots, error: () => this.simDepots = [] });
    this.api.artifactJson<ParcelDensityGeoJson>(id, 'simulation/parcel-density.geojson')
      .subscribe({ next: geo => this.parcelDensity = geo, error: () => this.parcelDensity = null });
    this.api.artifactJson<TrafficRoutesGeoJson>(id, 'sumo/traffic-routes.geojson')
      .subscribe({ next: geo => this.trafficRoutes = geo, error: () => this.trafficRoutes = null });
    this.api.artifactJson<TourGeoJson>(id, 'simulation/tours.geojson')
      .subscribe({ next: geo => this.tourData = geo, error: () => this.tourData = null });
    this.api.artifactJson<SumoSummary>(id, 'sumo/summary.json')
      .subscribe({ next: s => this.sumoSummary = s, error: () => this.sumoSummary = null });
    this.api.artifactJson<LlmModeChoiceResult>(id, 'simulation/llm-mode-choice.json')
      .subscribe({ next: s => this.llmModeChoice = s, error: () => this.llmModeChoice = null });
    this.api.artifactJson<GtaPersonas>(id, 'population/gta-personas.json')
      .subscribe({ next: s => this.gtaPersonas = s, error: () => this.gtaPersonas = null });
    this.api.artifactJson<CongestionGeoJson>(id, 'sumo/congestion.geojson')
      .subscribe({ next: geo => this.congestionData = geo, error: () => this.congestionData = null });
  }

  private clearSimulation(): void {
    this.clearEnergyInfrastructure();
    this.trafficDemandSummary = null;
    this.simKpis = null;
    this.sumoSummary = null;
    this.llmModeChoice = null;
    this.gtaPersonas = null;
    this.mobileCity = null;
    this.mobileCityValidation = null;
    this.simDepots = [];
    this.parcelDensity = null;
    this.tourData = null;
    this.trafficRoutes = null;
    this.trafficRouteLayer?.remove();
    this.trafficRouteLayer = null;
    this.showPrivateRoutes = false;
    this.showLogisticsRoutes = false;
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

  clearEnergyInfrastructure(): void {
    this.energyRequest++;
    this.energyLayer?.remove(); this.energyLayer = null;
    this.energyData = null; this.energyQuality = null;
    this.showEnergyLayer = false; this.showUnconfirmedEnergy = false;
  }

  private loadEnergyInfrastructure(): void {
    this.clearEnergyInfrastructure();
    const scenario = this.scenario;
    if (!scenario?.id || !scenario.region || !scenario.energyInfrastructure?.enabled) return;
    const id = scenario.id, request = this.energyRequest;
    forkJoin({
      data: this.api.artifactJson<EnergyGeoJson>(id, 'energy/stations.geojson'),
      quality: this.api.artifactJson<EnergyQuality>(id, 'energy/quality.json')
    }).subscribe({
      next: ({ data, quality }) => {
        const current = this.scenario;
        if (request !== this.energyRequest || current?.id !== id || !current.region) return;
        const sameRegion = (['minLon', 'minLat', 'maxLon', 'maxLat'] as const).every(key => current.region![key] === data.region[key]);
        const config = current.energyInfrastructure;
        if (!sameRegion || current.referenceYear !== data.referenceYear || !config?.enabled
          || config.useBnetza !== data.configuration.useBnetza || config.useOsm !== data.configuration.useOsm) return;
        this.energyData = data; this.energyQuality = quality;
      },
      error: () => { /* Missing artifacts do not imply observed zero supply. */ }
    });
  }

  toggleEnergyLayer(): void {
    this.showEnergyLayer = !this.showEnergyLayer; this.drawEnergyLayer();
  }

  toggleUnconfirmedEnergy(): void {
    this.showUnconfirmedEnergy = !this.showUnconfirmedEnergy; this.drawEnergyLayer();
  }

  private drawEnergyLayer(): void {
    this.energyLayer?.remove(); this.energyLayer = null;
    if (!this.showEnergyLayer || !this.energyData || !this.map) return;
    this.energyLayer = L.geoJSON<EnergyGeoJson['features'][number]['properties'], Point>(this.energyData, {
      attribution: 'Bundesnetzagentur.de (CC BY 4.0); © OpenStreetMap contributors (ODbL)',
      filter: feature => feature.properties.access === 'public' || this.showUnconfirmedEnergy,
      pointToLayer: (feature, latlng) => {
        const carrier = feature.properties.offers[0]?.carrier;
        const color = carrier === 'electricity' ? '#047857' : carrier === 'hydrogen' ? '#c2410c' : '#1d4ed8';
        return L.circleMarker(latlng, { radius: 6, color, weight: 2, fillOpacity: feature.properties.access === 'public' ? .8 : .25 });
      },
      onEachFeature: (feature, layer) => layer.bindPopup(energyPopup(feature.properties), {
        maxWidth: Math.max(160, Math.min(320, this.map.getSize().x - 50))
      })
    }).addTo(this.map);
  }

  get message(): string {
    return this.messageText;
  }

  /** Setter statt vieler Aufrufstellen: die Art wird aus der Wortwahl der deutschen Meldungen abgeleitet. */
  set message(text: string) {
    this.notify(text, classifyMessage(text));
  }

  notify(text: string, kind: MessageKind): void {
    this.messageText = text ?? '';
    this.messageKind = this.messageText ? kind : 'info';
  }

  dismissMessage(): void {
    this.notify('', 'info');
  }

  /** Experimentelle Optionen bleiben eingeklappt, außer eine davon ist bereits gesetzt. */
  get advancedOptionsOpen(): boolean {
    const p = this.scenario?.population;
    return !!p && !!(p.calibrateDistance || p.correctDistanceDecay || p.calibrateModeDistance
      || p.llmModeChoice || p.sharedRoadRoutes);
  }

  get anyMapLayerActive(): boolean {
    return this.showParcelLayer || this.showPrivateRoutes || this.showLogisticsRoutes
      || this.showTourLayer || this.showCongestionLayer || this.showEnergyLayer;
  }

  get hasSimLayers(): boolean {
    return !!this.parcelDensity || !!this.tourData || !!this.congestionData || !!this.trafficRoutes;
  }

  setFreightEnabled(enabled: boolean): void {
    if (!this.scenario?.freight) return;
    this.scenario.freight.enabled = enabled;
    if (!enabled) {
      this.scenario.freight.sevasEnabled = false;
      this.scenario.freight.profileChecksEnabled = false;
    }
    if (enabled) {
      this.scenario.population.samplingFraction = 1;
      this.message = 'Schwerer Güterverkehr für die gemeinsame Nachfrage aktiviert. Bitte vorbereitete Fahrtenbeziehungen hochladen.';
    }
  }

  get freightTripCount(): number {
    return this.scenario?.freight?.relations.reduce((sum, row) => sum + row.trips, 0) ?? 0;
  }

  onFreightSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0]; input.value = '';
    if (!file || !this.scenario?.id) return;
    this.freightUploadBusy = true;
    const id = this.scenario.id;
    this.api.uploadFreight(id, file).subscribe({
      next: freight => {
        if (this.scenario?.id === id && this.scenario.freight) this.scenario.freight.relations = freight.relations;
        this.freightUploadBusy = false;
        this.message = `${freight.relations.length} Güterverkehrsbeziehungen übernommen. Quelle: eigene Eingabedaten; noch nicht kalibriert.`;
      },
      error: err => {
        this.freightUploadBusy = false;
        this.message = 'Güterverkehrs-Upload fehlgeschlagen: ' + (err.error?.message ?? err.message);
      }
    });
  }

  onSevasSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    const id = this.scenario?.id;
    if (!file || !id) return;
    this.sevasUploadBusy = true;
    this.api.uploadSevas(id, file).subscribe({
      next: freight => {
        if (this.scenario?.id === id && this.scenario.freight) this.scenario.freight.sevasSnapshot = freight.sevasSnapshot;
        this.sevasUploadBusy = false;
        this.message = `${freight.sevasSnapshot?.features.length ?? 0} SEVAS-Segmente übernommen. Anwendbare Sperren werden im SUMO-Lauf geprüft.`;
      },
      error: err => {
        this.sevasUploadBusy = false;
        this.message = 'SEVAS-Upload fehlgeschlagen: ' + (err.error?.message ?? err.message);
      }
    });
    input.value = '';
  }

  get hasFreightCheckReport(): boolean {
    return this.artifacts.some(artifact => artifact.path === 'freight/route-checks.json');
  }

  get hasFreightNetwork(): boolean {
    return this.artifacts.some(artifact => artifact.path === 'sumo/freight-network.geojson');
  }

  downloadFreightTemplate(): void {
    const header = 'id;vehicleClass;movement;fromLon;fromLat;toLon;toLat;trips;beginHour;endHour;source;referenceDate;fromEdge;toEdge\n';
    const url = URL.createObjectURL(new Blob([header], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a'); link.href = url; link.download = 'freight-demand.csv'; link.click();
    URL.revokeObjectURL(url);
  }

  toggleTrafficRoutes(category: 'private' | 'logistics'): void {
    if (category === 'private') this.showPrivateRoutes = !this.showPrivateRoutes;
    else this.showLogisticsRoutes = !this.showLogisticsRoutes;
    this.trafficRouteLayer?.remove(); this.trafficRouteLayer = null;
    if (!this.trafficRoutes || (!this.showPrivateRoutes && !this.showLogisticsRoutes)) return;
    this.trafficRouteLayer = L.geoJSON<TrafficRouteProperties, LineString>(this.trafficRoutes, {
      filter: f => f.properties.trafficCategory === 'private' ? this.showPrivateRoutes : this.showLogisticsRoutes,
      style: f => ({ color: f?.properties.trafficCategory === 'logistics' ? '#d35400' : '#2471a3', weight: 2, opacity: 0.5 }),
      onEachFeature: (f, layer) => layer.bindTooltip(
        `${f.properties.trafficCategory === 'logistics' ? 'Logistik' : 'Privat / Personenverkehr'} · ${f.properties.vehicleType} · ${f.properties.demandSource}`)
    }).addTo(this.map);
  }

  toggleParcelLayer(): void {
    this.showParcelLayer = !this.showParcelLayer;
    this.parcelLayer?.remove();
    this.parcelLayer = null;
    if (!this.showParcelLayer || !this.parcelDensity) {
      return;
    }
    const max = Math.max(1, ...this.parcelDensity.features
      .map(feature => feature.properties?.parcels ?? 0));
    this.parcelLayer = L.geoJSON<ZoneFeatureProperties, Polygon>(this.parcelDensity, {
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
    this.tourLayer = L.geoJSON<TourFeatureProperties, LineString>(this.tourData, {
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
    this.congestionLayer = L.geoJSON<CongestionFeatureProperties, LineString>(this.congestionData, {
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

  /** Sechs amtlich aggregierte Distanzbänder der Simulation neben MiD 2017. */
  get distanceBandRows(): { label: string; sim: number | null; mid: number | null }[] {
    const sim = this.simKpis?.demand?.distanceBands;
    const mid = this.simKpis?.mid2017?.distanceBands;
    if (!sim && !mid) {
      return [];
    }
    return DISTANCE_BAND_ROWS.map(({ key, label }) => ({
      label,
      sim: sim?.[key] ?? null,
      mid: mid?.[key] ?? null
    }));
  }

  /** Bedingter Modal Split je Distanzband; jede Zelle vergleicht Simulation und MiD. */
  get modeDistanceRows(): {
    label: string;
    modes: { key: string; label: string; sim: number | null; mid: number | null }[];
  }[] {
    const sim = this.simKpis?.demand?.modalSplitByDistanceBand;
    const mid = this.simKpis?.mid2017?.modalSplitByDistanceBand;
    if (!sim && !mid) {
      return [];
    }
    const modes = [
      { key: 'pedestrian', label: 'Fuß' },
      { key: 'bike', label: 'Rad' },
      { key: 'car', label: 'Pkw' },
      { key: 'passenger', label: 'Mitf.' },
      { key: 'publicTransport', label: 'ÖV' }
    ];
    return DISTANCE_BAND_ROWS.map(({ key, label }) => ({
      label,
      modes: modes.map(mode => ({
        ...mode,
        sim: sim?.[key]?.[mode.key] ?? null,
        mid: mid?.[key]?.[mode.key] ?? null
      }))
    }));
  }

  pct1(value: number | null): string {
    return value == null ? '–' : (value * 100).toFixed(1).replace('.', ',') + ' %';
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

export type MessageKind = 'info' | 'success' | 'warning' | 'error';

/** Wortwahl der Meldungen im Studio: Fehler nennen „Fehler“/„fehlgeschlagen“/„konnte nicht“, Hinweise beginnen mit „Bitte“. */
export function classifyMessage(text: string): MessageKind {
  const t = (text ?? '').toLowerCase();
  if (!t) return 'info';
  if (/fehler|fehlgeschlagen|konnten? nicht|invalid|ungültig/.test(t)) return 'error';
  if (/^bitte|zuerst speichern|benötigt/.test(t)) return 'warning';
  if (/gespeichert|hochgeladen|übernommen|entfernt|aktiviert|gesetzt/.test(t)) return 'success';
  return 'info';
}
