import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { Scenario } from '../../core/models/scenario.model';
import { EnergyGeoJson, EnergyQuality } from '../../core/models/energy-infrastructure.model';
import { StudioComponent } from './studio.component';

describe('Studio energy inventory lifecycle', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [StudioComponent], providers: [{ provide: ApiService, useValue: { artifactJson: vi.fn() } }] }).compileComponents();
  });
  it('rejects delayed and mismatched artifacts after changing region, year, sources or scenario', () => {
    const f = TestBed.createComponent(StudioComponent), c = f.componentInstance;
    // Isolate artifact request lifecycle from Leaflet and other scenario artifacts.
    const load = () => (c as unknown as { loadEnergyInfrastructure(): void }).loadEnergyInfrastructure();
    const api = TestBed.inject(ApiService);
    const region = { minLon: 7.4, maxLon: 7.5, minLat: 51.4, maxLat: 51.5 };
    const config = { enabled: true, useBnetza: true, useOsm: true };
    c.scenario = { id: 'first', region, referenceYear: 2026, energyInfrastructure: config } as Scenario;
    const data = { type: 'FeatureCollection', features: [], region, referenceYear: 2026, configuration: config } as unknown as EnergyGeoJson;
    const quality = { stationRecords: 0 } as EnergyQuality;
    function pending() {
      const geo = new Subject<EnergyGeoJson>(), report = new Subject<EnergyQuality>();
      vi.mocked(api.artifactJson).mockImplementation((_id, path) => (path.endsWith('.geojson') ? geo : report) as never);
      load(); return () => { geo.next(data); geo.complete(); report.next(quality); report.complete(); };
    }
    const stale = pending(); c.clearEnergyInfrastructure(); stale(); expect(c.energyData).toBeNull();
    const otherYear = pending(); c.scenario.referenceYear = 2025; otherYear(); expect(c.energyData).toBeNull(); c.scenario.referenceYear = 2026;
    const otherRegion = pending(); c.scenario.region = { ...region, maxLon: 7.6 }; otherRegion(); expect(c.energyData).toBeNull(); c.scenario.region = region;
    const otherSource = pending(); c.scenario.energyInfrastructure = { ...config, useOsm: false }; otherSource(); expect(c.energyData).toBeNull(); c.scenario.energyInfrastructure = config;
    const otherScenario = pending(); c.scenario = { ...c.scenario, id: 'second' }; otherScenario(); expect(c.energyData).toBeNull();
    pending()(); expect(c.energyData).toBe(data); expect(c.energyQuality).toBe(quality); expect(c.showUnconfirmedEnergy).toBe(false);
    const destroyed = pending(); f.destroy(); destroyed(); expect(c.energyData).toBeNull();
  });
});
