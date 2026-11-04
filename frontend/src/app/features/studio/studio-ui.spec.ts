import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Scenario } from '../../core/models/scenario.model';
import { ApiService } from '../../core/services/api.service';
import { StudioComponent, classifyMessage } from './studio.component';

/** Oberflächenverhalten des Studios: Meldungsarten, eingeklappte Experimente, Kartenlegende. */
describe('Studio UI', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<StudioComponent>> | null;

  beforeEach(async () => {
    fixture = null;
    const api = {
      listCompanyTours: vi.fn(() => of([])),
      listScenarios: vi.fn(() => of([])),
      scenarioDefaults: vi.fn(() => of(null)),
      listUploads: vi.fn(() => of([])),
      artifactUrl: vi.fn((_id: string, path: string) => `/artifacts/${path}`),
      estimateRegion: vi.fn(() => of({
        estimatedPopulation: 0, source: 'none', sourceLabel: '', referenceDate: '', sourceUrl: '', places: []
      }))
    };
    await TestBed.configureTestingModule({
      imports: [StudioComponent],
      providers: [{ provide: ApiService, useValue: api }]
    }).compileComponents();
  });

  afterEach(() => fixture?.destroy());

  it('classifies German status messages by wording', () => {
    expect(classifyMessage('')).toBe('info');
    expect(classifyMessage('Szenario gespeichert')).toBe('success');
    expect(classifyMessage('GTFS-Upload fehlgeschlagen: 500')).toBe('error');
    expect(classifyMessage('Szenario konnte nicht gelöscht werden')).toBe('error');
    expect(classifyMessage('Fehler: Invalid snapshot')).toBe('error');
    expect(classifyMessage('Bitte zuerst eine Region auf der Karte zeichnen')).toBe('warning');
    expect(classifyMessage('12 Zonen')).toBe('info');
  });

  it('renders the message as a dismissible banner carrying its kind', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.scenario-controls .message')).toBeNull();

    fixture.componentInstance.message = 'Upload-Fehler: zu groß';
    fixture.detectChanges();
    const banner = element.querySelector('.scenario-controls .message')!;
    expect(banner.getAttribute('data-kind')).toBe('error');
    expect(banner.getAttribute('role')).toBe('alert');
    expect(banner.textContent).toContain('Upload-Fehler');

    (banner.querySelector('button.dismiss') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance.message).toBe('');
    expect(element.querySelector('.scenario-controls .message')).toBeNull();

    fixture.componentInstance.notify('Szenario gespeichert', 'success');
    fixture.detectChanges();
    expect(element.querySelector('.scenario-controls .message')?.getAttribute('role')).toBe('status');
    expect(fixture.componentInstance.messageKind).toBe('success');
  });

  it('keeps experimental options collapsed unless one of them is already set', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const draft = {
      id: 'ui', name: 'UI', region: null,
      zoning: { cellSizeMeters: 500, maxZones: 600, mode: 'grid' },
      targetModels: ['logitopp'], referenceYear: 2026,
      mobileCity: { useTemplate: false, templateId: 'tramigo-v2', allowSyntheticTransitFallback: true },
      population: {
        totalPersons: null, personsPerKm2: 1000, samplingFraction: 1, seed: 42,
        useOsmData: false, useRealRouting: false, externalTrips: false,
        behaviorSource: 'template', gtaWhatIf: '', sumoFeedback: false,
        useKitPopulation: false, calibrateModeChoice: false,
        calibrateDistance: false, correctDistanceDecay: false, calibrateModeDistance: false,
        calibrationTarget: {}, llmModeChoice: false, sharedRoadRoutes: false, carsPerHousehold: 1
      },
      logistics: {
        parcelsPerPersonWeek: 1, parcelStdDev: 0, maxParcelsPerPerson: 1,
        shareHome: 1, shareWork: 0, sharePackstation: 0,
        returns: { enabled: false, model: 'exogenous-share', shareOfPrivateOrders: 0, pickupShares: { home: 1, work: 0 } },
        depots: [], services: []
      },
      companyTours: { imports: [] }
    };
    component.scenario = draft as unknown as Scenario;
    fixture.detectChanges();
    const details = () => (fixture!.nativeElement as HTMLElement).querySelector<HTMLDetailsElement>('details.advanced');
    expect(details()).not.toBeNull();
    expect(details()!.open).toBe(false);
    expect(details()!.textContent).toContain('Kurze Wege kalibrieren');

    draft.population.llmModeChoice = true;
    fixture.detectChanges();
    expect(details()!.open).toBe(true);
  });

  it('shows a legend only while a map layer is active', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.map-legend')).toBeNull();

    fixture.componentInstance.showCongestionLayer = true;
    fixture.componentInstance.showTourLayer = true;
    fixture.detectChanges();
    const legend = element.querySelector('.map-legend')!;
    expect(legend.textContent).toContain('Netzlast');
    expect(legend.textContent).toContain('Lastenrad');
    expect(legend.querySelectorAll('.legend-row').length).toBe(5);
  });
});
