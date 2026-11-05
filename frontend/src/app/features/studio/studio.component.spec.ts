import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Scenario } from '../../core/models/scenario.model';
import { ApiService } from '../../core/services/api.service';
import { StudioComponent } from './studio.component';

describe('StudioComponent', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<StudioComponent>> | null;
  let api: {
    listCompanyTours: ReturnType<typeof vi.fn>;
    listScenarios: ReturnType<typeof vi.fn>;
    scenarioDefaults: ReturnType<typeof vi.fn>;
    createScenario: ReturnType<typeof vi.fn>;
    updateScenario: ReturnType<typeof vi.fn>;
    deleteScenario: ReturnType<typeof vi.fn>;
    listUploads: ReturnType<typeof vi.fn>;
    deletePopulationGrid: ReturnType<typeof vi.fn>;
    deleteRoadNetwork: ReturnType<typeof vi.fn>;
    deleteOsmData: ReturnType<typeof vi.fn>;
    deleteCommuters: ReturnType<typeof vi.fn>;
    deleteWorkplaceMargins: ReturnType<typeof vi.fn>;
    uploadCommuters: ReturnType<typeof vi.fn>;
    uploadSevas: ReturnType<typeof vi.fn>;
    generate: ReturnType<typeof vi.fn>;
    generationStatus: ReturnType<typeof vi.fn>;
    artifactUrl: ReturnType<typeof vi.fn>;
    previewZones: ReturnType<typeof vi.fn>;
    estimateRegion: ReturnType<typeof vi.fn>;
    listArtifacts?: ReturnType<typeof vi.fn>;
    artifactJson?: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    fixture = null;
    api = {
      listCompanyTours: vi.fn(() => of([])),
      listScenarios: vi.fn(() => of([])),
      scenarioDefaults: vi.fn(() => of(null)),
      createScenario: vi.fn(),
      updateScenario: vi.fn((value: Scenario) => of(value)),
      deleteScenario: vi.fn(() => of(undefined)),
      listUploads: vi.fn(() => of([])),
      deletePopulationGrid: vi.fn(() => of(undefined)),
      deleteRoadNetwork: vi.fn(() => of(undefined)),
      deleteOsmData: vi.fn(() => of(undefined)),
      deleteCommuters: vi.fn(() => of(undefined)),
      deleteWorkplaceMargins: vi.fn(() => of(undefined)),
      uploadCommuters: vi.fn(),
      uploadSevas: vi.fn(),
      generate: vi.fn(() => of(job('QUEUED'))),
      generationStatus: vi.fn(() => of(job('RUNNING'))),
      artifactUrl: vi.fn((_id: string, path: string) => `/artifacts/${path}`),
      previewZones: vi.fn(() => of({
        type: 'FeatureCollection', features: [], cellSizeMeters: 500, autoAdjusted: false
      })),
      estimateRegion: vi.fn(() => of({
        estimatedPopulation: 0, source: 'none', sourceLabel: '', referenceDate: '',
        sourceUrl: '', places: []
      }))
    };

    await TestBed.configureTestingModule({
      imports: [StudioComponent],
      providers: [{ provide: ApiService, useValue: api }]
    }).compileComponents();
  });

  afterEach(() => {
    fixture?.destroy();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('hides the internal job.json from the artifact list', () => {
    api.listArtifacts = vi.fn(() => of([
      { path: 'job.json', sizeBytes: 900 }, { path: 'kpis.json', sizeBytes: 120 }
    ]));
    api.artifactJson = vi.fn(() => throwError(() => new Error('nicht vorhanden')));
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('saved');
    (component as unknown as { loadArtifacts(): void }).loadArtifacts();
    expect(component.artifacts.map(artifact => artifact.path)).toEqual(['kpis.json']);
  });

  it('shows the waiting position and the scenario occupying the worker while queued', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('saved');
    component.job = { ...job('QUEUED'), queuePosition: 2, runningScenarioId: 'other', runningScenarioName: 'Rastatt Mitte' };
    fixture.detectChanges();
    const note: HTMLElement = fixture.nativeElement.querySelector('.queue-note');
    expect(note.textContent).toContain('Platz 2 in der Warteschlange');
    expect(note.textContent).toContain('Gerade läuft: Rastatt Mitte');

    component.job = job('RUNNING');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.queue-note')).toBeNull();
  });

  it('separates inserted vehicles, routing losses and day-end persons in SUMO results', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('saved');
    component.job = job('DONE');
    component.sumoSummary = {
      vehicles: 3316, vehicleCountScope: 'inserted-including-bicycles',
      meanStatisticsScope: 'motor-vehicle-trip-statistics',
      meanDurationMin: 19.2, meanRouteLengthKm: 18.8, meanSpeedKmh: 59, runtimeSeconds: 100,
      accounting: {
        status: 'incomplete', demandRetained: false, nativeVehicleTeleports: 1,
        teleportAccountingComplete: true,
        vehicles: { requested: 3624, routed: 3316, departed: 3316, completed: 3316,
          unrouted: 308, notDeparted: 0, unfinished: 0, vaporized: 0 },
        persons: { requested: 215, routed: 207, departed: 207, completed: 206,
          unrouted: 8, notDeparted: 0, unfinished: 1, vaporized: 0 }
      }
    };
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Eingesetzte Fahrzeuge inkl. Rad');
    expect(text).toContain('3624 / 3316 / 3316 / 3316');
    expect(text).toContain('215 / 207 / 207 / 206');
    expect(text).toContain('308 / 8');
    expect(text).toContain('Die angeforderte Nachfrage ist nicht vollständig erhalten.');
    expect(text).toContain('Am Tagesende unterwegs');
    const accounting = component.sumoSummary!.accounting!;
    accounting.status = 'review-required';accounting.demandRetained = true;accounting.nativeVehicleTeleports = 2;
    accounting.vehicles = { requested: 3624, routed: 3624, departed: 3624, completed: 3624,
      unrouted: 0, notDeparted: 0, unfinished: 0, vaporized: 0 };
    accounting.persons = { requested: 215, routed: 215, departed: 215, completed: 214,
      unrouted: 0, notDeparted: 0, unfinished: 1, vaporized: 0 };
    fixture.detectChanges();
    const reviewed = fixture.nativeElement.textContent;
    expect(reviewed).toContain('Prüfung erforderlich');
    expect(reviewed).toContain('3624 / 3624 / 3624 / 3624');
    expect(reviewed).toContain('215 / 215 / 215 / 214');
    expect(reviewed).not.toContain('Die angeforderte Nachfrage ist nicht vollständig erhalten.');
  });
  it('enables road-based synthetic locations and explains their scope', () => {
    fixture=TestBed.createComponent(StudioComponent);fixture.detectChanges();const component=fixture.componentInstance;component.scenario=scenario('road-locations');fixture.detectChanges();
    const checkbox=Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('label')).find(label=>label.textContent?.includes('Synthetische Wohn- und Aktivitätsorte'))?.querySelector<HTMLInputElement>('input');
    expect(checkbox?.checked).toBe(false);checkbox!.click();fixture.detectChanges();expect(component.scenario.population.roadLocatedActivities).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('keine beobachteten Adressen');expect(fixture.nativeElement.textContent).toContain('Externe Zielanker');
  });

  it('shows a SUMO preflight failure visibly even if the optional-stage job itself is done', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('saved');
    fixture.componentInstance.job = { ...job('DONE'), stages: [{ name: 'SUMO (Verkehr)', status: 'FAILED', durationMs: 0,
      message: 'Straßennetz-Vorprüfung: 2 Endpunkte ohne zulässige Straße innerhalb 300 m' }] };
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.stage-message').textContent).toContain('2 Endpunkte');
  });

  it('shows the shared demand and downloads without selecting SUMO or claiming computed street routes', () => {
    fixture = TestBed.createComponent(StudioComponent); fixture.detectChanges();
    const c = fixture.componentInstance; c.scenario = scenario('saved'); c.scenario.targetModels = ['logitopp'];
    c.trafficDemandSummary = { contract: 'synthesis-traffic-demand-v1', movements: 5, tours: 1,
      byDemandSource: { 'heavy-freight': 2, 'company-tours': 3 }, byTrafficCategory: { logistics: 5 },
      personDemandStatus: 'not-generated', parcelDemandStatus: 'not-generated', geometry: 'OD-connections-not-routed' };
    fixture.detectChanges(); const html = fixture.nativeElement as HTMLElement;
    expect(html.textContent).toContain('Gemeinsame Nachfrage');
    expect(html.textContent).toContain('Personenwege wurden in diesem Lauf nicht erzeugt');
    expect(html.textContent).toContain('keine berechneten Straßenrouten');
    expect(html.querySelector('a[href="/artifacts/demand/traffic-demand.json"]')).not.toBeNull();
    expect(html.querySelector('a[href="/artifacts/demand/planned-connections.geojson"]')).not.toBeNull();
  });

  it('enables freight independently and keeps demand when deselecting SUMO', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('saved');
    component.scenario.freight = { enabled: false, day: 0, relations: [] };
    component.scenario.targetModels = [];
    component.scenario.population.samplingFraction = .1;
    component.setFreightEnabled(true);
    expect(component.scenario.freight.enabled).toBe(true);
    expect(component.scenario.population.samplingFraction).toBe(1);
    expect(component.scenario.targetModels).toEqual([]);
    component.scenario.targetModels = ['sumo', 'logitopp'];
    component.scenario.companyTours = { enabled: true, imports: [{ importId: 'abc', executionHash: 'rev' }] };
    component.scenario.freight.sevasEnabled = true;
    component.scenario.freight.profileChecksEnabled = true;
    component.toggleTarget('sumo');
    expect(component.scenario.freight.enabled).toBe(true);
    expect(component.scenario.companyTours.enabled).toBe(true);
    expect(component.scenario.freight.sevasEnabled).toBe(false);
    expect(component.scenario.freight.profileChecksEnabled).toBe(false);
  });

  it('imports a SEVAS snapshot without overwriting local flags and retains it on rejection', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('saved');
    component.scenario.freight = { enabled: true, sevasEnabled: true, day: 0, relations: [] };
    const snapshot = { type: 'FeatureCollection', features: [{}] };
    api.uploadSevas.mockReturnValue(of({ enabled: false, sevasEnabled: false, day: 0, relations: [], sevasSnapshot: snapshot }));
    const file = new File(['{}'], 'sevas.geojson');
    const input = { files: [file], value: 'sevas.geojson' };
    component.onSevasSelected({ target: input } as unknown as Event);
    expect(api.uploadSevas).toHaveBeenCalledWith('saved', file);
    expect(component.scenario.freight.sevasSnapshot).toBe(snapshot);
    expect(component.scenario.freight.sevasEnabled).toBe(true);
    expect(component.scenario.freight.enabled).toBe(true);
    api.uploadSevas.mockReturnValue(throwError(() => ({ error: { message: 'Invalid snapshot' } })));
    component.onSevasSelected({ target: input } as unknown as Event);
    expect(component.scenario.freight.sevasSnapshot).toBe(snapshot);
    expect(component.sevasUploadBusy).toBe(false);
    expect(component.message).toContain('Invalid snapshot');
  });

  it('disables census weighting when its KIT household source is switched off', async () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('saved');
    fixture.componentInstance.scenario.population.useKitPopulation = true;
    fixture.componentInstance.scenario.population.useCensusStructure = true;
    fixture.detectChanges();
    await fixture.whenStable();
    const labels = Array.from(fixture.nativeElement.querySelectorAll('label')) as HTMLLabelElement[];
    const kit = labels.find(label => label.textContent?.includes('KIT-Demografie-Sample (Haushalte)'))!.querySelector('input')!;
    const census = labels.find(label => label.textContent?.includes('Regionale Alters- und Haushaltsstruktur'))!.querySelector('input')!;
    expect(census.disabled).toBe(false);
    kit.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.scenario.population.useCensusStructure).toBe(false);
    expect(census.disabled).toBe(true);
  });

  it('renders the migrated studio shell and initializes its map', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(api.listScenarios).toHaveBeenCalledOnce();
    expect(api.scenarioDefaults).toHaveBeenCalledOnce();
    expect(element.querySelector('#map')).not.toBeNull();
    expect(element.querySelector('#map')?.classList).toContain('leaflet-container');
  });

  it('shows scenario-list failures independently from defaults', () => {
    api.listScenarios.mockReturnValue(throwError(() => new Error('offline')));
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.scenarios).toEqual([]);
    expect(fixture.componentInstance.scenarioListError)
      .toBe('Szenarien konnten nicht geladen werden');
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('Szenarien konnten nicht geladen werden');
  });

  it('keeps the studio usable when defaults cannot be loaded', () => {
    api.scenarioDefaults.mockReturnValue(throwError(() => new Error('offline')));
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.scenario).toBeNull();
    expect(fixture.componentInstance.message)
      .toBe('Szenariovorlage konnte nicht geladen werden');
  });

  it('resets busy and keeps the selection when deletion fails', () => {
    const saved = scenario('saved');
    api.listScenarios.mockReturnValue(of([saved]));
    api.deleteScenario.mockReturnValue(throwError(() => new Error('offline')));
    vi.stubGlobal('confirm', vi.fn(() => true));
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.selectedScenarioId = 'saved';

    fixture.componentInstance.deleteSelected();

    expect(api.deleteScenario).toHaveBeenCalledWith('saved');
    expect(fixture.componentInstance.busy).toBe(false);
    expect(fixture.componentInstance.selectedScenarioId).toBe('saved');
    expect(fixture.componentInstance.message).toBe('Szenario konnte nicht gelöscht werden');
  });

  it('reports an unavailable upload status for a selected scenario', () => {
    const saved = scenario('saved');
    api.listUploads.mockReturnValue(throwError(() => new Error('offline')));
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenarios = [saved];
    fixture.componentInstance.selectedScenarioId = 'saved';

    fixture.componentInstance.onScenarioSelected();

    expect(fixture.componentInstance.message).toBe('Upload-Status konnte nicht geladen werden');
  });

  it('refreshes zones and the automatic population when a saved scenario is selected', () => {
    const saved = scenario('saved');
    saved.region = { minLat: 48.84, minLon: 8.18, maxLat: 48.87, maxLon: 8.23 };
    api.estimateRegion.mockReturnValue(of({
      estimatedPopulation: 1234, source: 'zensus-2022', sourceLabel: 'Destatis Zensus 2022',
      referenceDate: '2022-05-15', sourceUrl: 'https://example.test/zensus.zip', places: []
    }));
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenarios = [saved];
    component.selectedScenarioId = 'saved';
    vi.spyOn(component as unknown as { drawRegion(): void }, 'drawRegion')
      .mockImplementation(() => undefined);
    const preview = vi.spyOn(component, 'previewZones').mockImplementation(() => undefined);

    component.onScenarioSelected();

    expect(preview).toHaveBeenCalledOnce();
    expect(api.estimateRegion).toHaveBeenCalledWith(saved.region);
    expect(component.displayedTotalPersons).toBe(1234);
    expect(component.estimateInfo).toContain('1.234 Einwohner');
  });

  it('refreshes the preview after zoning controls change', () => {
    const draft = scenario('');
    draft.region = { minLat: 48.84, minLon: 8.18, maxLat: 48.87, maxLon: 8.23 };
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = draft;
    const preview = vi.spyOn(component, 'previewZones').mockImplementation(() => undefined);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('label[title^="Zonierungs-Gitter"] select')).toBeNull();
    const migrate = Array.from(element.querySelectorAll('button'))
      .find(button => button.textContent?.includes('Auf europäisches Raster umstellen'));
    expect(draft.zoning.mode).toBe('grid');
    expect(migrate).toBeDefined();
    migrate!.click();
    expect(draft.zoning.mode).toBe('epsg3035');

    expect(preview).toHaveBeenCalledOnce();
    expect(preview).toHaveBeenCalledWith();
  });

  it('recomputes the finest preview when the scenario zone limit changes', () => {
    const draft = scenario('');
    draft.region = { minLat: 48.84, minLon: 8.18, maxLat: 48.87, maxLon: 8.23 };
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = draft;
    const preview = vi.spyOn(component, 'previewZones').mockImplementation(() => undefined);
    fixture.detectChanges();

    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      'label[title^="Obergrenze der Verkehrszellen"] input');
    expect(input?.min).toBe('100');
    expect(input?.max).toBe('1200');
    input!.value = '1200';
    input!.dispatchEvent(new Event('input'));
    input!.dispatchEvent(new Event('change'));

    expect(draft.zoning.maxZones).toBe(1200);
    expect(preview).toHaveBeenCalledWith(true);
  });

  it('writes the effective cell size back when the preview must use a coarser grid', () => {
    api.previewZones.mockReturnValue(of({
      type: 'FeatureCollection', features: [], cellSizeMeters: 10000, autoAdjusted: true
    }));
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const draft = scenario('draft');
    draft.region = { minLat: 47.2, minLon: 5.8, maxLat: 55.1, maxLon: 15.1 };
    draft.zoning.cellSizeMeters = 500;
    component.scenario = draft;

    component.previewZones();

    expect(api.previewZones).toHaveBeenCalledWith(
      draft.region, 500, 'grid', 600);
    expect(draft.zoning.cellSizeMeters).toBe(10000);
    expect(component.zonePreviewInfo).toContain('Auf 10.000 m angepasst');
    expect(component.zonePreviewInfo).toContain('tatsächlich verwendete Wert steht jetzt im Feld');
    expect(component.message).toContain('10.000 m angepasst');
  });

  it('presents the sampling fraction as a synchronized percentage slider', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('draft');
    component.scenario.population.samplingFraction = 0.25;
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const slider = element.querySelector<HTMLInputElement>('input[aria-label="Stichprobenanteil in Prozent"]');
    expect(slider?.type).toBe('range');
    expect(slider?.value).toBe('25');
    expect(slider?.min).toBe('5');
    expect(slider?.max).toBe('100');
    expect(element.querySelector('.range-label output')?.textContent).toContain('25 %');

    slider!.value = '40';
    slider!.dispatchEvent(new Event('input'));
    expect(component.scenario.population.samplingFraction).toBe(0.4);
  });

  it('exposes shared street routes only for SUMO and preserves the selection', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('shared-route-test');
    fixture.detectChanges();
    const checkbox = () => Array.from((fixture!.nativeElement as HTMLElement).querySelectorAll('label'))
      .find(label => label.textContent?.includes('Gemeinsame Straßenrouten'))?.querySelector<HTMLInputElement>('input');
    expect(checkbox()).toBeUndefined();
    component.scenario.targetModels = ['logitopp', 'sumo'];
    fixture.detectChanges();
    expect(checkbox()).toBeDefined();
    checkbox()!.click();
    expect(component.scenario.population.sharedRoadRoutes).toBe(true);
  });

  it('automatically selects and locks logiTopp when SUMO is selected', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('draft');
    component.scenario.targetModels = [];

    component.toggleTarget('sumo');

    expect(component.scenario.targetModels).toEqual(['logitopp', 'sumo']);
    expect(component.message).toContain('automatisch aktiviert');

    component.toggleTarget('logitopp');
    expect(component.scenario.targetModels).toEqual(['logitopp', 'sumo']);
    expect(component.message).toContain('SUMO benötigt');
  });

  it('presents MobileCity as an independent export and GTFS as automatic', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('draft');
    component.scenario.targetModels = [];
    component.toggleTarget('mobilecity');
    component.toggleTarget('sumo');
    fixture.detectChanges();

    expect(component.scenario.targetModels).toEqual(['logitopp', 'mobilecity', 'sumo']);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Export: MobileCity');
    expect(text).toContain('Verkehrssimulation: SUMO');
    expect(text).toContain('GTFS wird bei verfügbarem Fahrplan automatisch eingebunden');
    expect(text).not.toContain('ÖV/Bus in SUMO');
  });

  it('keeps regional export and template merge mutually exclusive with matching guidance', async () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('draft');
    fixture.componentInstance.scenario.targetModels = ['mobilecity'];
    fixture.componentInstance.scenario.mobileCity.useTemplate = true;
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const regional = Array.from(element.querySelectorAll('label')).find(l => l.textContent?.includes('Regionalpaket'))!.querySelector('input')!;
    regional.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.scenario.mobileCity.regionalExport).toBe(true);
    expect(fixture.componentInstance.scenario.mobileCity.useTemplate).toBe(false);
    expect(element.textContent).toContain('Wähle die KBA-Quelle');
    expect(element.textContent).not.toContain('mindestens einen ganzen District');
    const template = Array.from(element.querySelectorAll('label')).find(l => l.textContent?.includes('Vorlage ergänzen'))!.querySelector('input')!;
    template.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.scenario.mobileCity.regionalExport).toBe(false);
    expect(fixture.componentInstance.scenario.mobileCity.useTemplate).toBe(true);
  });

  it('configures a complete rudimentary MobileCity export with disclosed fallbacks', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('saved');
    component.scenario.region = { minLat: 48.8, minLon: 8.1, maxLat: 48.9, maxLon: 8.2 };
    component.scenario.targetModels = ['logitopp', 'sumo'];
    component.scenario.population.useRealRouting = true;
    component.scenario.population.externalTrips = true;

    component.configureMobileCityQuickStart();
    fixture.detectChanges();

    expect(component.scenario.targetModels).toEqual(['mobilecity']);
    expect(component.scenario.population.behaviorSource).toBe('template');
    expect(component.scenario.population.useRealRouting).toBe(false);
    expect(component.scenario.population.externalTrips).toBe(false);
    expect(component.scenario.mobileCity).toMatchObject({
      regionalExport: true,
      useTemplate: false,
      useRegionalFleet: false,
      electricShare: 0,
      allowSyntheticTransitFallback: true,
      transitVehiclesPer1000: 1,
      settlementAreaShare: 0.5,
      maxDistricts: 12,
      autoSurrounding: false,
      surroundingPopulation: null,
      validateNative: false
    });
    expect(component.mobileCityPlanReady()).toBe(true);
    expect(component.message).toContain('0 % BEV');
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('MobileCity-Export erzeugen');
  });

  it('shows planned MobileCity sources and marks an old regional result stale', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('draft');
    component.scenario.region = { minLat: 48.8, minLon: 8.1, maxLat: 48.9, maxLon: 8.2 };
    component.scenario.targetModels = ['mobilecity'];
    component.scenario.mobileCity.regionalExport = true;
    component.scenario.mobileCity.useRegionalFleet = true;
    component.scenario.mobileCity.useRegionalCharging = true;
    component.scenario.mobileCity.carsharingVehiclesPer1000 = 2.5;
    component.scenario.mobileCity.carsharingMemberShare = 0.08;
    component.scenario.mobileCity.surroundingPopulation = 25_000;
    component.scenario.mobileCity.surroundingEducationTripShare = 0.1;
    component.scenario.mobileCity.surroundingWorkTripShare = 0.4;
    component.scenario.mobileCity.surroundingShoppingTripShare = 0.15;
    component.scenario.mobileCity.surroundingLeisureTripShare = 0.08;
    component.scenario.population.externalTrips = true;
    fixture.detectChanges();

    expect(component.mobileCityPlanReady()).toBe(true);
    expect(component.mobileCityPlanItems().find(item => item.id === 'private-bev')).toMatchObject({
      status: 'observed', resolution: 'automatic', source: 'KBA/BKG automatisch'
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Datenstatus vor dem Export');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Umlandbevölkerung');
    expect(component.mobileCityPlanItems().find(item => item.id === 'transit')).toMatchObject({
      status: 'derived', resolution: 'fallback'
    });
    expect(component.mobileCityPlanItems().find(item => item.id === 'chargers')).toMatchObject({
      status: 'observed', resolution: 'automatic', source: 'BNetzA Ladesäulenregister'
    });
    expect(component.mobileCityPlanItems().find(item => item.id === 'sharing')).toMatchObject({
      status: 'assumption', resolution: 'manual', source: 'Nutzervorgabe'
    });
    expect(component.mobileCityPlanItems().find(item => item.id === 'surrounding')).toMatchObject({
      status: 'assumption', resolution: 'manual', source: 'Nutzervorgabe'
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Carsharing-Mitgliederanteil');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Eingehender Anteil Arbeitswege');

    const inputSnapshot = (component as any).mobileCityInputSnapshot();
    component.mobileCity = {
      mode: 'regional', tables: 25,
      districts: { total: 1, studyArea: 1, surrounding: 0 },
      inhabitants: { children: 1, adults: 1, seniors: 1, total: 3 },
      privateCars: 1, tripsPerDay: { children: 1, adults: 1, seniors: 1 },
      dataInventory: {
        schemaVersion: 1, mode: 'regional', referenceYear: 2026, inputSnapshot,
        ready: true, blockingIssues: [], items: [], sourceSnapshots: []
      }
    };
    expect(component.mobileCityResultStale()).toBe(false);
    component.scenario.referenceYear = 2027;
    expect(component.mobileCityResultStale()).toBe(true);
  });

  it('blocks the MobileCity plan when requested surrounding inputs are incomplete', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('draft');
    component.scenario.region = { minLat: 48.8, minLon: 8.1, maxLat: 48.9, maxLon: 8.2 };
    component.scenario.targetModels = ['mobilecity'];
    component.scenario.mobileCity.regionalExport = true;
    component.scenario.mobileCity.electricShare = 0.1;
    component.scenario.mobileCity.surroundingPopulation = 10_000;

    expect(component.mobileCityPlanReady()).toBe(false);
    expect(component.mobileCityPlanItems().find(item => item.id === 'surrounding')).toMatchObject({
      status: 'missing', required: true, source: 'Unvollständige Umlandkonfiguration'
    });
  });

  it('offers automatic census surroundings without a manual population total', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('draft');
    component.scenario.region = { minLat: 48.8, minLon: 8.1, maxLat: 48.9, maxLon: 8.2 };
    component.scenario.targetModels = ['mobilecity'];
    component.scenario.mobileCity.regionalExport = true;
    component.scenario.mobileCity.electricShare = 0.1;
    component.scenario.mobileCity.autoSurrounding = true;
    component.scenario.mobileCity.surroundingBufferKm = 15;
    component.scenario.mobileCity.surroundingEducationTripShare = 0.1;
    component.scenario.mobileCity.surroundingWorkTripShare = 0.4;
    component.scenario.mobileCity.surroundingShoppingTripShare = 0.15;
    component.scenario.mobileCity.surroundingLeisureTripShare = 0.08;
    component.scenario.population.externalTrips = true;
    fixture.detectChanges();

    expect(component.mobileCityPlanReady()).toBe(true);
    expect(component.mobileCityPlanItems().find(item => item.id === 'surrounding')).toMatchObject({
      status: 'derived', resolution: 'automatic', source: 'Zensus-2022-Pufferring'
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Breite des Umlandpuffers');
    expect((component as any).mobileCityInputSnapshot()).toMatchObject({
      autoSurrounding: true, surroundingBufferKm: 15, surroundingPopulation: null
    });
  });

  it('offers optional native validation and renders its separate result', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.scenario = scenario('draft');
    component.scenario.targetModels = ['mobilecity'];
    component.scenario.mobileCity.regionalExport = true;
    component.mobileCity = {
      mode: 'regional', tables: 25,
      districts: { total: 1, studyArea: 1, surrounding: 0 },
      inhabitants: { children: 1, adults: 1, seniors: 1, total: 3 },
      privateCars: 1, tripsPerDay: { children: 1, adults: 1, seniors: 1 }
    };
    component.mobileCityValidation = {
      status: 'passed', years: [2026, 2027, 2028], errors: [], nonFiniteCells: 0,
      report: 'validation-report.json'
    };
    component.artifacts = [{ path: 'mobilecity/summary.json', sizeBytes: 1 }];
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const validation = Array.from(element.querySelectorAll('label'))
      .find(label => label.textContent?.includes('nativ im MobileCity ConfigTool prüfen'))!;
    expect(validation.getAttribute('title')).toContain('erzeugten Binärdateien');
    expect(element.textContent).toContain('Native ConfigTool-Prüfung bestanden');
    expect(element.textContent).toContain('2026–2028');
  });

  it('provides an explanatory tooltip for every rendered form control', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const draft = scenario('draft');
    draft.targetModels = ['logitopp', 'sumo'];
    draft.population.calibrateModeChoice = true;
    draft.logistics.returns.enabled = true;
    fixture.componentInstance.scenario = draft;
    fixture.detectChanges();

    const controls = Array.from((fixture.nativeElement as HTMLElement)
      .querySelectorAll<HTMLElement>('input, select, textarea'));
    const withoutTooltip = controls.filter(control => !control.closest('[title]'));

    expect(withoutTooltip).toEqual([]);
  });

  it('saves beside the scenario picker and shows confirmation there', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const draft = scenario('');
    const component = fixture.componentInstance;
    component.scenario = draft;
    fixture.detectChanges();
    const controls = (fixture.nativeElement as HTMLElement).querySelector('.scenario-controls')!;
    const save = controls.querySelector<HTMLButtonElement>('.save-scenario')!;
    expect(controls.querySelector('select')).not.toBeNull();
    expect(save.disabled).toBe(true);

    draft.region = { minLat: 48.84, minLon: 8.18, maxLat: 48.87, maxLon: 8.23 };
    api.createScenario.mockReturnValue(of({ ...draft, id: 'created' }));
    fixture.detectChanges();
    save.click();
    fixture.detectChanges();

    expect(api.createScenario).toHaveBeenCalledWith(draft);
    expect(component.selectedScenarioId).toBe('created');
    expect(controls.textContent).toContain('Szenario gespeichert');
  });

  it('opens model help from the keyboard and dismisses it with Escape', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('saved');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const help = element.querySelector<HTMLButtonElement>(
      'button[aria-label="Zusammenspiel der Modelle erklären"]')!;
    help.dispatchEvent(new FocusEvent('focus'));
    fixture.detectChanges();
    const tooltip = element.querySelector(`#${help.getAttribute('aria-describedby')}`)!;
    expect(tooltip.classList.contains('visible')).toBe(true);
    expect(tooltip.textContent).toContain('keinen logiTopp-Lauf');
    help.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(tooltip.classList.contains('visible')).toBe(false);
  });

  it('shows OSM data as an automatic source without a scenario toggle', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('draft');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const automaticSource = element.querySelector('.automatic-source');
    expect(automaticSource?.textContent).toContain('OSM-Ortsdaten werden automatisch geladen');
    expect(automaticSource?.textContent).toContain('Overpass');
    expect(automaticSource?.querySelector('input')).toBeNull();
    expect(element.textContent).not.toContain('OSM-Ortsdatensnapshot hochladen');
    expect(Array.from(element.querySelectorAll('label')).some(label =>
      label.textContent?.includes('OSM-Daten nutzen'))).toBe(false);
  });

  it('separates OSM place snapshots from road-network routing', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('draft');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const groups = Array.from(element.querySelectorAll<HTMLElement>('.source-group'));
    expect(groups[0].textContent).toContain('Orte und Einrichtungen');
    expect(groups[0].querySelector('.automatic-source')?.getAttribute('title'))
      .toContain('OSM-Schnittstelle Overpass');
    expect(groups[1].textContent).toContain('Straßennetz und Routing');
    expect(groups[1].textContent).toContain('Fahrzeiten werden automatisch auf dem Straßennetz berechnet');
    expect(groups[1].textContent).not.toContain('Manuelles Straßennetz als Fallback hochladen');
  });

  it('offers a manual road-network upload only after automatic routing fell back', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const draft = scenario('draft');
    draft.population.useRealRouting = true;
    fixture.componentInstance.scenario = draft;
    fixture.componentInstance.job = {
      ...job('DONE'),
      summary: { matrixSource: 'beeline-estimate' }
    };
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Manuelles Straßennetz als Fallback hochladen');
    expect(text).toContain('letzte Routingversuch auf Luftlinie zurückfiel');
  });

  it('keeps the difficult workplace-margin upload out of the standard UI', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('draft');
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('Amtliche Auspendlerquote hochladen');
    expect(text).not.toContain('Arbeitsplatz-Randwert');
    expect(text).toContain('Pendlermatrix hochladen');
  });

  it('shows the automatically bundled BA outcommuter share after a commuter upload', () => {
    api.uploadCommuters.mockReturnValue(of({
      name: 'commuters.csv', relations: 2, totalCommuters: 900,
      relationsWithHomeInRegion: 2, source: 'upload-ba-pendler-vg250',
      exportTotalCommuters: 1000, coverage: 0.9, skippedAggregateRows: 1,
      unresolvedMunicipalities: 0,
      workplaceMargin: {
        name: 'workplace-margins.csv', source: 'ba-gemeindedaten-2025',
        date: '2025-06-30', municipalities: 1, residentWorkers: 22971,
        outboundWorkers: 13723, externalShare: 13723 / 22971,
        unresolvedMunicipalities: 0
      }
    }));
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('saved');
    const input = document.createElement('input');
    const file = new File(['test'], 'pendler.csv', { type: 'text/csv' });
    Object.defineProperty(input, 'files', { value: [file] });

    fixture.componentInstance.onCommutersSelected({ target: input } as unknown as Event);
    fixture.detectChanges();

    expect(fixture.componentInstance.workplaceMarginUpload?.source)
      .toBe('ba-gemeindedaten-2025');
    expect(fixture.componentInstance.message).toContain(
      'amtliche Auspendlerquote 59,7 % automatisch zugeordnet');
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('Amtliche Auspendlerquote automatisch zugeordnet');
  });

  it('explains external-work source, actual share and official target in results', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('saved');
    fixture.componentInstance.scenario.population.externalTrips = true;
    fixture.componentInstance.job = {
      ...job('DONE'),
      summary: {
        zones: 40, persons: 1000, households: 500, cars: 600, depots: 1,
        commuterSource: 'upload-ba-pendler-vg250',
        externalWorkDestinations: 596,
        externalWorkShareActual: 0.596,
        externalWorkShareTarget: 0.5974
      }
    };
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('amtliche BA-Pendlerziele');
    expect(text).toContain('59,6 %');
    expect(text).toContain('596 Ziele');
    expect(text).toContain('59,7 %');
  });

  it('keeps the three delivery sliders at exactly 100 percent', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const draft = scenario('draft');
    draft.logistics.shareHome = 0.8;
    draft.logistics.shareWork = 0.1;
    draft.logistics.sharePackstation = 0.1;
    fixture.componentInstance.scenario = draft;
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector<HTMLInputElement>('input[aria-label="Pakete je Person und Woche"]')?.type)
      .toBe('range');
    expect(element.querySelector<HTMLInputElement>('input[aria-label="Pakete je Person und Woche"]')?.max)
      .toBe('10');
    expect(element.querySelector<HTMLInputElement>('input[aria-label="Unterschiede bei der Paketanzahl"]')?.type)
      .toBe('range');
    expect(element.querySelectorAll('.linked-shares input[type="range"]')).toHaveLength(3);

    fixture.componentInstance.setDeliverySharePercent('shareHome', 60);
    fixture.detectChanges();

    expect(draft.logistics.shareHome).toBe(0.6);
    expect(draft.logistics.shareWork).toBe(0.2);
    expect(draft.logistics.sharePackstation).toBe(0.2);
    expect(fixture.componentInstance.deliveryShareTotalPercent).toBe(100);
    expect(element.querySelector('.sum-badge')?.textContent).toContain('Summe 100 %');
  });

  it('offers understandable random variants without exposing the internal seed', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('draft');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const randomVariant = Array.from(element.querySelectorAll('label')).find(label =>
      label.textContent?.includes('Bei jedem Lauf neue Zufallsvariante'));
    expect(randomVariant?.querySelector<HTMLInputElement>('input')?.type).toBe('checkbox');
    expect(randomVariant?.getAttribute('title')).toContain(
      'automatisch eine neue interne Zufallszahl gewählt');
    expect(element.textContent).not.toContain('42');
  });

  it('persists a new internal seed before generating a random variant', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const saved = scenario('saved');
    component.scenario = saved;
    component.randomizeEachRun = true;
    vi.spyOn(component as unknown as { createRandomSeed(): number }, 'createRandomSeed')
      .mockReturnValue(987654321);
    api.updateScenario.mockImplementation((value: Scenario) => of(value));

    component.generate();

    expect(saved.population.seed).toBe(987654321);
    expect(api.updateScenario).toHaveBeenCalledWith(saved);
    expect(api.generate).toHaveBeenCalledWith('saved');
  });

  it('persists current settings before every generation', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const saved = scenario('saved');
    saved.mobileCity.regionalExport = true;
    saved.mobileCity.electricShare = 0;
    component.scenario = saved;

    component.generate();

    expect(api.updateScenario).toHaveBeenCalledWith(saved);
    expect(api.updateScenario.mock.invocationCallOrder[0])
      .toBeLessThan(api.generate.mock.invocationCallOrder[0]);
    expect(api.generate).toHaveBeenCalledWith('saved');
  });

  it('shows the census estimate in the total field without persisting it as manual', () => {
    api.estimateRegion.mockReturnValue(of({
      estimatedPopulation: 52341,
      source: 'zensus-2022',
      sourceLabel: 'Destatis Zensus 2022',
      referenceDate: '2022-05-15',
      sourceUrl: 'https://www.destatis.de/zensus2022',
      places: []
    }));
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const draft = scenario('draft');
    draft.region = { minLat: 48.84, minLon: 8.18, maxLat: 48.87, maxLon: 8.23 };
    draft.population.totalPersons = null;
    fixture.componentInstance.scenario = draft;

    (fixture.componentInstance as unknown as { estimatePopulation(): void }).estimatePopulation();
    fixture.detectChanges();

    expect(fixture.componentInstance.estimateInfo)
      .toContain('Destatis Zensus 2022, Stichtag 15.05.2022');
    expect(fixture.componentInstance.estimateSourceUrl)
      .toBe('https://www.destatis.de/zensus2022');
    const totalInput = fixture.nativeElement.querySelector(
      'input[aria-label="Einwohner gesamt"]') as HTMLInputElement;
    expect(totalInput.valueAsNumber).toBe(52341);
    expect(fixture.nativeElement.textContent).toContain(
      'Automatisch ermittelt – Bearbeiten setzt eine manuelle Vorgabe.');
    expect(draft.population.totalPersons).toBeNull();

    totalInput.value = '53000';
    totalInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(draft.population.totalPersons).toBe(53000);
  });

  it('reports removal failures and resets every upload busy state', () => {
    const failure = throwError(() => new Error('offline'));
    api.deletePopulationGrid.mockReturnValue(failure);
    api.deleteRoadNetwork.mockReturnValue(failure);
    api.deleteOsmData.mockReturnValue(failure);
    api.deleteCommuters.mockReturnValue(failure);
    api.deleteWorkplaceMargins.mockReturnValue(failure);
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('saved');

    fixture.componentInstance.removeUpload();
    expect(fixture.componentInstance.uploadBusy).toBe(false);
    expect(fixture.componentInstance.message)
      .toBe('Bevölkerungsraster konnte nicht entfernt werden');

    fixture.componentInstance.removeRoadNetwork();
    expect(fixture.componentInstance.roadNetworkUploadBusy).toBe(false);
    expect(fixture.componentInstance.message).toBe('Straßennetz konnte nicht entfernt werden');

    fixture.componentInstance.removeOsmData();
    expect(fixture.componentInstance.osmDataUploadBusy).toBe(false);
    expect(fixture.componentInstance.message).toBe('OSM-Ortsdatensnapshot konnte nicht entfernt werden');

    fixture.componentInstance.removeCommuters();
    expect(fixture.componentInstance.commuterUploadBusy).toBe(false);
    expect(fixture.componentInstance.message).toBe('Pendlermatrix konnte nicht entfernt werden');

    fixture.componentInstance.removeWorkplaceMargins();
    expect(fixture.componentInstance.workplaceMarginUploadBusy).toBe(false);
    expect(fixture.componentInstance.message)
      .toBe('Amtliche Auspendlerquote konnte nicht entfernt werden');
  });

  it('rejects saving before a region has been selected', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('draft');

    fixture.componentInstance.save();

    expect(api.createScenario).not.toHaveBeenCalled();
    expect(api.updateScenario).not.toHaveBeenCalled();
    expect(fixture.componentInstance.busy).toBe(false);
    expect(fixture.componentInstance.message)
      .toBe('Bitte zuerst eine Region auf der Karte zeichnen');
  });

  it('stops polling after the first status failure', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('saved');
    api.generationStatus.mockReturnValue(throwError(() => new Error('offline')));
    vi.useFakeTimers();

    fixture.componentInstance.generate();
    vi.advanceTimersByTime(1500);

    expect(api.generate).toHaveBeenCalledWith('saved');
    expect(api.generationStatus).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.message).toBe('Job-Status konnte nicht geladen werden');

    vi.advanceTimersByTime(3000);
    expect(api.generationStatus).toHaveBeenCalledOnce();
  });

  it('clears active polling when the studio is destroyed', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    fixture.componentInstance.scenario = scenario('saved');
    vi.useFakeTimers();

    fixture.componentInstance.generate();
    fixture.destroy();
    fixture = null;
    vi.advanceTimersByTime(3000);

    expect(api.generationStatus).not.toHaveBeenCalled();
  });
});

function job(state: 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED') {
  return { scenarioId: 'saved', state, stages: [], log: [] };
}

function scenario(id: string): Scenario {
  return {
    id,
    name: 'Gespeichertes Szenario',
    region: null,
    zoning: { cellSizeMeters: 500, maxZones: 600, mode: 'grid' },
    targetModels: ['logitopp'],
    referenceYear: 2026,
    mobileCity: { useTemplate: false, templateId: 'tramigo-v2', allowSyntheticTransitFallback: true },
    population: {
      totalPersons: null, personsPerKm2: 1000, samplingFraction: 1, seed: 42,
      useOsmData: false, useRealRouting: false, externalTrips: false,
      behaviorSource: 'template', gtaWhatIf: '', sumoFeedback: false,
      useKitPopulation: false, calibrateModeChoice: false,
      calibrateDistance: false, correctDistanceDecay: false,
      calibrateModeDistance: false, calibrationTarget: {},
      llmModeChoice: false, carsPerHousehold: 1
    },
    logistics: {
      parcelsPerPersonWeek: 1, parcelStdDev: 0, maxParcelsPerPerson: 1,
      shareHome: 1, shareWork: 0, sharePackstation: 0,
      returns: {
        enabled: false, model: 'exogenous-share', shareOfPrivateOrders: 0,
        pickupShares: { home: 1, work: 0 }
      },
      depots: [], services: []
    }
  };
}
