import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { Scenario } from '../../core/models/scenario.model';
import { EnergyQuality, EnergyStation, energyPopup } from '../../core/models/energy-infrastructure.model';
import { EnergyInfrastructurePanelComponent } from './energy-infrastructure-panel.component';

describe('EnergyInfrastructurePanelComponent', () => {
  const api = { uploadEnergySnapshot: vi.fn(), deleteEnergySnapshot: vi.fn(), artifactUrl: vi.fn(() => '/quality.json') };
  beforeEach(async () => {
    vi.clearAllMocks();
    await TestBed.configureTestingModule({ imports: [EnergyInfrastructurePanelComponent], providers: [{ provide: ApiService, useValue: api }] }).compileComponents();
  });
  function create() {
    const f = TestBed.createComponent(EnergyInfrastructurePanelComponent);
    f.componentRef.setInput('scenario', { id: 'energy', energyInfrastructure: { enabled: true, useBnetza: true, useOsm: true } } as Scenario);
    f.detectChanges(); return f;
  }
  it('keeps an unavailable register count distinct from observed zero and shows source errors', async () => {
    const f = create();
    const quality = { stationRecords: 0, recordsByAccess: {}, recordsByCarrier: {}, observedRegisterChargingPoints: null, sources: [], qualityFlagCounts: {}, errors: ['OSM: Quelle nicht erreichbar'] } as unknown as EnergyQuality;
    f.componentRef.setInput('quality', quality); f.detectChanges();
    expect(f.nativeElement.textContent).toContain('OSM: Quelle nicht erreichbar');
    expect(f.nativeElement.textContent).not.toContain('0 Ladepunkte im BNetzA-Bestand');
    f.componentRef.setInput('quality', { ...quality, observedRegisterChargingPoints: 0 }); f.detectChanges();
    expect(f.nativeElement.textContent).toContain('0 Ladepunkte im BNetzA-Bestand');
    f.componentRef.setInput('disabled', true); f.detectChanges();
    await f.whenStable();
    expect(Array.from(f.nativeElement.querySelectorAll('input,button')).every(input => (input as HTMLInputElement).disabled)).toBe(true);
    f.destroy();
  });
  it('only invalidates the inventory after a successful source mutation and cancels stale scenario responses', () => {
    const f = create(), c = f.componentInstance;
    const changed = vi.fn(); c.sourcesChanged.subscribe(changed);
    api.uploadEnergySnapshot.mockReturnValue(throwError(() => ({ error: { detail: 'Region stimmt nicht überein' } })));
    c.upload({ target: { files: [new File(['{}'], 'stations.json')], value: 'stations.json' } } as unknown as Event);
    expect(c.message).toContain('Region stimmt nicht überein'); expect(changed).not.toHaveBeenCalled();
    const pending = new Subject<void>(); api.deleteEnergySnapshot.mockReturnValue(pending); c.refresh();
    expect(c.working).toBe(true);
    f.componentRef.setInput('scenario', { ...c.scenario, id: 'other' }); f.detectChanges();
    pending.next(); pending.complete();
    expect(c.message).toBe(''); expect(c.working).toBe(false); expect(changed).not.toHaveBeenCalled();
    api.deleteEnergySnapshot.mockReturnValue(of(undefined)); c.refresh();
    expect(api.deleteEnergySnapshot).toHaveBeenLastCalledWith('other'); expect(changed).toHaveBeenCalledOnce();
    f.destroy();
  });
});

describe('energyPopup', () => {
  it('renders untrusted source text literally and keeps pressure and access qualifications visible', () => {
    const station = {
      name: '<img src=x onerror=alert(1)>', source: 'osm', access: 'unknown', coordinateKind: 'bounding-box-center',
      chargingPoints: 2, possibleDuplicateIds: ['bnetza:123'],
      offers: [{ carrier: 'hydrogen', pressureBars: [350, 700], points: [], connectors: [] }]
    } as unknown as EnergyStation;
    const popup = energyPopup(station);
    expect(popup.querySelector('img')).toBeNull(); expect(popup.textContent).toContain(station.name);
    expect(popup.textContent).toContain('350 / 700 bar'); expect(popup.textContent).toContain('Öffentlicher Zugang ungeklärt');
    expect(popup.textContent).toContain('Zufahrt nicht bekannt'); expect(popup.textContent).toContain('Fahrzeugeignung ungeprüft');
  });
});
