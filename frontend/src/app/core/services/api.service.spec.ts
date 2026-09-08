import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiService } from './api.service';

describe('ApiService', () => {
  let service: ApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ApiService, provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads scenarios from the API contract', () => {
    service.listScenarios().subscribe(scenarios => expect(scenarios).toEqual([]));

    const request = http.expectOne('/api/scenarios');
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('encodes comparison ids as one query parameter', () => {
    service.compare(['basis', 'massnahme']).subscribe();

    const request = http.expectOne(
      req => req.url === '/api/comparison' && req.params.get('ids') === 'basis,massnahme');
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('sends the complete zoning contract for a preview', () => {
    const region = { minLat: 48.84, minLon: 8.16, maxLat: 48.88, maxLon: 8.23 };

    service.previewZones(region, 750, 'inspire', 900).subscribe();

    const request = http.expectOne('/api/preview/zones');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      region, cellSizeMeters: 750, mode: 'inspire', maxZones: 900
    });
    request.flush({
      type: 'FeatureCollection', features: [], cellSizeMeters: 1000, autoAdjusted: true
    });
  });

  it('uploads the frozen OSM data snapshot through the scenario contract', () => {
    service.uploadOsmData('campaign-42', new File(['{}'], 'overpass.json')).subscribe();

    const request = http.expectOne('/api/scenarios/campaign-42/uploads/osm-data');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeInstanceOf(FormData);
    expect((request.request.body as FormData).get('file')).toBeInstanceOf(File);
    request.flush({ name: 'osm-data.json', elements: 2, pois: 1, places: 1, sha256: 'abc' });
  });
});
