import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Scenario } from '../../core/models/scenario.model';
import { ApiService } from '../../core/services/api.service';
import { StudioComponent } from './studio.component';

describe('StudioComponent', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<StudioComponent>> | null;
  let api: {
    listScenarios: ReturnType<typeof vi.fn>;
    scenarioDefaults: ReturnType<typeof vi.fn>;
    createScenario: ReturnType<typeof vi.fn>;
    updateScenario: ReturnType<typeof vi.fn>;
    deleteScenario: ReturnType<typeof vi.fn>;
    listUploads: ReturnType<typeof vi.fn>;
    generate: ReturnType<typeof vi.fn>;
    generationStatus: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    fixture = null;
    api = {
      listScenarios: vi.fn(() => of([])),
      scenarioDefaults: vi.fn(() => of(null)),
      createScenario: vi.fn(),
      updateScenario: vi.fn(),
      deleteScenario: vi.fn(() => of(undefined)),
      listUploads: vi.fn(() => of([])),
      generate: vi.fn(() => of(job('QUEUED'))),
      generationStatus: vi.fn(() => of(job('RUNNING')))
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
    zoning: { cellSizeMeters: 500, mode: 'grid' },
    targetModels: ['logitopp'],
    population: {
      totalPersons: null, personsPerKm2: 1000, samplingFraction: 1, seed: 42,
      useOsmData: false, useRealRouting: false, externalTrips: false,
      behaviorSource: 'template', gtaWhatIf: '', sumoFeedback: false,
      publicTransport: false, useKitPopulation: false, calibrateModeChoice: false,
      calibrateDistance: false, calibrateModeDistance: false, calibrationTarget: {},
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
