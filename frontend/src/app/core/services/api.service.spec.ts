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
});
