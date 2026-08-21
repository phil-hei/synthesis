import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComparisonEntry, ComparisonParams } from '../../core/models/results.model';
import { Scenario } from '../../core/models/scenario.model';
import { ApiService } from '../../core/services/api.service';
import { ComparisonComponent } from './comparison.component';

describe('ComparisonComponent', () => {
  const scenarios: Scenario[] = [
    scenario('basis', 'Basis'),
    scenario('ohne-ergebnis', 'Ohne Ergebnis')
  ];

  let comparisonEntries: ComparisonEntry[];
  let api: {
    listScenarios: ReturnType<typeof vi.fn>;
    compare: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    comparisonEntries = [];
    api = {
      listScenarios: vi.fn(() => of(scenarios)),
      compare: vi.fn((_ids: string[]) => of(comparisonEntries))
    };

    await TestBed.configureTestingModule({
      imports: [ComparisonComponent],
      providers: [{ provide: ApiService, useValue: api }]
    }).compileComponents();
  });

  it('loads scenarios and renders the migrated picker template', () => {
    const fixture = TestBed.createComponent(ComparisonComponent);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(api.listScenarios).toHaveBeenCalledOnce();
    expect(element.querySelectorAll('input[type="checkbox"]')).toHaveLength(2);
    expect(element.textContent).toContain('Basis');
    expect(element.textContent).toContain('Ohne Ergebnis');
  });

  it('shows a visible error when scenarios cannot be loaded', () => {
    api.listScenarios.mockReturnValue(throwError(() => new Error('offline')));
    const fixture = TestBed.createComponent(ComparisonComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.scenarios).toEqual([]);
    expect(fixture.nativeElement.textContent).toContain('Szenarien konnten nicht geladen werden');
  });

  it('does not call the comparison API without a selection', () => {
    const fixture = TestBed.createComponent(ComparisonComponent);
    fixture.detectChanges();

    fixture.componentInstance.loadComparison();
    fixture.detectChanges();

    expect(api.compare).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Bitte mindestens ein Szenario auswählen');
  });

  it('spans group rows only across columns that are rendered', () => {
    comparisonEntries = [
      comparisonEntry('basis', 'Basis'),
      comparisonEntry('ohne-ergebnis', 'Ohne Ergebnis')
    ];
    const fixture = TestBed.createComponent(ComparisonComponent);
    fixture.detectChanges();
    fixture.componentInstance.selected = { basis: true, 'ohne-ergebnis': true };

    fixture.componentInstance.loadComparison();
    fixture.detectChanges();

    const groupCell = (fixture.nativeElement as HTMLElement).querySelector('.group td');
    expect(groupCell?.getAttribute('colspan')).toBe('3');
  });

  // Ein Vergleich mit abweichenden OSM-Eingaben ist kein Ein-Faktor-Kontrast. Das war
  // bisher nur eine Doku-Regel und wurde in einer Kampagne übersehen (E24).

  it('warns visibly when compared runs used different OSM inputs', () => {
    comparisonEntries = [
      comparisonEntry('a', 'A', { inputProvenance: provenance('aaa', 'net', 'attr-1') }),
      comparisonEntry('b', 'B', { inputProvenance: provenance('aaa', 'net', 'attr-2') })
    ];
    const fixture = TestBed.createComponent(ComparisonComponent);
    fixture.detectChanges();
    fixture.componentInstance.selected = { a: true, b: true };

    fixture.componentInstance.loadComparison();
    fixture.detectChanges();

    expect(fixture.componentInstance.divergingInputs).toEqual(['Attraktivitäten']);
    const warning = (fixture.nativeElement as HTMLElement).querySelector('.provenance-warning');
    expect(warning?.textContent).toContain('Eingaben unterschiedlich');
    expect(warning?.getAttribute('role')).toBe('alert');
  });

  it('stays silent when compared runs share their inputs', () => {
    comparisonEntries = [
      comparisonEntry('a', 'A', { inputProvenance: provenance('aaa', 'net', 'attr-1') }),
      comparisonEntry('b', 'B', { inputProvenance: provenance('aaa', 'net', 'attr-1') })
    ];
    const fixture = TestBed.createComponent(ComparisonComponent);
    fixture.detectChanges();
    fixture.componentInstance.selected = { a: true, b: true };

    fixture.componentInstance.loadComparison();
    fixture.detectChanges();

    expect(fixture.componentInstance.divergingInputs).toEqual([]);
    expect(fixture.componentInstance.provenanceUnknown).toBe(false);
    expect((fixture.nativeElement as HTMLElement).querySelector('.provenance-warning')).toBeNull();
  });

  it('flags runs without recorded provenance as unproven rather than equal', () => {
    comparisonEntries = [
      comparisonEntry('alt', 'Alt'),
      comparisonEntry('neu', 'Neu', { inputProvenance: provenance('aaa', 'net', 'attr-1') })
    ];
    const fixture = TestBed.createComponent(ComparisonComponent);
    fixture.detectChanges();
    fixture.componentInstance.selected = { alt: true, neu: true };

    fixture.componentInstance.loadComparison();
    fixture.detectChanges();

    expect(fixture.componentInstance.divergingInputs).toEqual([]);
    expect(fixture.componentInstance.provenanceUnknown).toBe(true);
    const notice = (fixture.nativeElement as HTMLElement).querySelector('.provenance-warning.subtle');
    expect(notice?.textContent).toContain('nicht hinterlegt');
  });

  it('prevents duplicate requests while a comparison is loading', () => {
    const response = new Subject<ComparisonEntry[]>();
    api.compare.mockReturnValue(response);
    const fixture = TestBed.createComponent(ComparisonComponent);
    fixture.detectChanges();
    fixture.componentInstance.selected = { basis: true };

    fixture.componentInstance.loadComparison();
    fixture.detectChanges();

    const button = (fixture.nativeElement as HTMLElement).querySelector('button');
    expect(fixture.componentInstance.loading).toBe(true);
    expect(button?.getAttribute('disabled')).not.toBeNull();
    expect(button?.textContent).toContain('Wird geladen');

    response.next([]);
    response.complete();
    fixture.detectChanges();

    expect(fixture.componentInstance.loading).toBe(false);
    expect(button?.getAttribute('disabled')).toBeNull();
  });

  it('clears stale results when loading the comparison fails', () => {
    api.compare.mockReturnValue(throwError(() => new Error('offline')));
    const fixture = TestBed.createComponent(ComparisonComponent);
    fixture.detectChanges();
    fixture.componentInstance.entries = [comparisonEntry('stale', 'Veraltet')];
    fixture.componentInstance.mid = { avgTripKm: 12.5 };
    fixture.componentInstance.selected = { basis: true };

    fixture.componentInstance.loadComparison();
    fixture.detectChanges();

    expect(fixture.componentInstance.entries).toEqual([]);
    expect(fixture.componentInstance.mid).toBeNull();
    const message = (fixture.nativeElement as HTMLElement).querySelector('.message');
    expect(message?.textContent).toContain('Vergleich konnte nicht geladen werden');
    expect(message?.getAttribute('aria-live')).toBe('polite');
    expect(fixture.nativeElement.textContent).not.toContain('Veraltet');
  });

  it('renders mixed comparison results and the MiD reference', () => {
    comparisonEntries = [
      comparisonEntry('basis', 'Basis', {
        simulated: true,
        kpis: {
          demand: { trips: 1000, tripsPerPersonDay: 3.0, avgTripKm: 9.5 },
          mid2017: { tripsPerPersonDay: 3.1, avgTripKm: 12.5 }
        }
      }),
      comparisonEntry('ohne-ergebnis', 'Ohne Ergebnis')
    ];
    const fixture = TestBed.createComponent(ComparisonComponent);
    fixture.detectChanges();
    fixture.componentInstance.selected = { basis: true, 'ohne-ergebnis': true };

    fixture.componentInstance.loadComparison();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(api.compare).toHaveBeenCalledWith(['basis', 'ohne-ergebnis']);
    expect(text).toContain('MiD 2017');
    expect(text).toContain('Ohne Simulationsergebnis (nur Parameter): Ohne Ergebnis');
    expect(text).toContain('12.5 km');
    expect((fixture.nativeElement as HTMLElement).querySelector('.group td')
      ?.getAttribute('colspan')).toBe('4');
  });
});

function scenario(id: string, name: string): Scenario {
  return {
    id,
    name,
    region: null,
    zoning: { cellSizeMeters: 500, mode: 'grid' },
    targetModels: ['logitopp'],
    population: {
      totalPersons: null,
      personsPerKm2: 1000,
      samplingFraction: 1,
      seed: 42,
      useOsmData: false,
      useRealRouting: false,
      externalTrips: false,
      behaviorSource: 'template',
      gtaWhatIf: '',
      sumoFeedback: false,
      publicTransport: false,
      useKitPopulation: false,
      calibrateModeChoice: false,
      calibrateDistance: false,
      correctDistanceDecay: false,
      calibrateModeDistance: false,
      calibrationTarget: {},
      llmModeChoice: false,
      carsPerHousehold: 1
    },
    logistics: {
      parcelsPerPersonWeek: 1,
      parcelStdDev: 0,
      maxParcelsPerPerson: 1,
      shareHome: 1,
      shareWork: 0,
      sharePackstation: 0,
      returns: {
        enabled: false,
        model: 'exogenous-share',
        shareOfPrivateOrders: 0,
        pickupShares: { home: 1, work: 0 }
      },
      depots: [],
      services: []
    }
  };
}

function comparisonEntry(
    id: string, name: string, overrides: Partial<ComparisonEntry> = {}): ComparisonEntry {
  return {
    id,
    name,
    params: params(),
    kpis: null,
    depotStats: null,
    sumo: null,
    simulated: false,
    inputProvenance: null,
    ...overrides
  };
}

function provenance(osm: string, road: string, attr: string) {
  return {
    osmData: { source: 'overpass-live', sha256: osm },
    roadNetwork: { source: 'overpass-live', sha256: road },
    attractivities: { path: 'zone-repository/attractivities.csv', sha256: attr }
  };
}

function params(): ComparisonParams {
  return {
    cellSizeMeters: 500,
    samplingFraction: 1,
    useRealRouting: false,
    correctDistanceDecay: false,
    parcelsPerPersonWeek: 1,
    returnsEnabled: false,
    returnShare: 0,
    depots: 0
  };
}
