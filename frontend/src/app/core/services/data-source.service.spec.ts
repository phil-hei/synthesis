import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { DataSourceService } from './data-source.service';

describe('DataSourceService library contracts', () => {
  let service: DataSourceService; let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [DataSourceService, provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(DataSourceService); http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('reads a fresh run report through the existing artifact route with an encoded scenario identifier', () => {
    service.runReport('scenario/a').subscribe();
    const request = http.expectOne('/api/scenarios/scenario%2Fa/artifacts/sources/resolution.json');
    expect(request.request.method).toBe('GET'); expect(request.request.headers.get('Cache-Control')).toBe('no-cache'); request.flush({});
  });
  it('keeps global publication and scenario-specific transfer on distinct routes', () => {
    service.libraryPacks().subscribe(); const list = http.expectOne('/api/data-library/packs'); expect(list.request.method).toBe('GET'); list.flush([]);
    const file = new File(['zip'], 'pack.zip'); service.publishLibraryPack(file).subscribe(); const upload = http.expectOne('/api/data-library/packs'); expect(upload.request.method).toBe('POST'); expect(upload.request.body.get('file')).toBe(file); upload.flush({});
    service.installLibraryPack('scenario-a', 'version').subscribe(); const copy = http.expectOne('/api/scenarios/scenario-a/data/library/packs/version/install'); expect(copy.request.method).toBe('POST'); copy.flush({});
    service.promotePack('scenario-b', 'version').subscribe(); const share = http.expectOne('/api/scenarios/scenario-b/data/packs/version/library'); expect(share.request.method).toBe('POST'); share.flush({});
  });
  it('uses explicit profile publication and copy endpoints without scenario updates', () => {
    service.librarySourceProfiles().subscribe();const list = http.expectOne('/api/data-library/source-profiles');expect(list.request.method).toBe('GET');list.flush([]);
    const definition = { contract: 'synthesis-source-profile-v1' as const, name: 'Profil', aspects: {} };
    service.publishLibrarySourceProfile(definition).subscribe();const publish = http.expectOne('/api/data-library/source-profiles');expect(publish.request.method).toBe('POST');expect(publish.request.body).toEqual(definition);publish.flush({});
    service.promoteSourceProfile('origin', 'hash').subscribe();const share = http.expectOne('/api/scenarios/origin/data/source-profiles/hash/library');expect(share.request.method).toBe('POST');share.flush({});
    service.installLibrarySourceProfile('target', 'hash').subscribe();const copy = http.expectOne('/api/scenarios/target/data/library/source-profiles/hash/install');expect(copy.request.method).toBe('POST');copy.flush({});
  });

});
