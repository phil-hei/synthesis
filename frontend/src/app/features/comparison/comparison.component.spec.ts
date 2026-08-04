import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Scenario } from '../../core/models/scenario.model';
import { ApiService } from '../../core/services/api.service';
import { ComparisonComponent } from './comparison.component';

describe('ComparisonComponent', () => {
  const scenarios: Scenario[] = [
    scenario('basis', 'Basis'),
    scenario('ohne-ergebnis', 'Ohne Ergebnis')
  ];

  let comparisonEntries: any[];
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
      { name: 'Basis', simulated: false, params: params() },
      { name: 'Ohne Ergebnis', simulated: false, params: params() }
    ];
    const fixture = TestBed.createComponent(ComparisonComponent);
    fixture.detectChanges();
    fixture.componentInstance.selected = { basis: true, 'ohne-ergebnis': true };

    fixture.componentInstance.loadComparison();
    fixture.detectChanges();

    const groupCell = (fixture.nativeElement as HTMLElement).querySelector('.group td');
    expect(groupCell?.getAttribute('colspan')).toBe('3');
  });

  it('renders mixed comparison results and the MiD reference', () => {
    comparisonEntries = [
      {
        name: 'Basis',
        simulated: true,
        params: params(),
        kpis: {
          demand: { trips: 1000, tripsPerPersonDay: 3.0, avgTripKm: 9.5 },
          mid2017: { tripsPerPersonDay: 3.1, avgTripKm: 12.5 }
        }
      },
      { name: 'Ohne Ergebnis', simulated: false, params: params() }
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

function params(): Record<string, unknown> {
  return {
    cellSizeMeters: 500,
    samplingFraction: 1,
    useRealRouting: false,
    parcelsPerPersonWeek: 1,
    returnsEnabled: false,
    returnShare: 0,
    depots: 0
  };
}
