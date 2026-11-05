import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DataImport, DataPack, DataLibraryEntry, SourceProfileLibraryEntry, SourceProfile, SourceProfileDefinition, DataProfile, DatasetRevision, ImportMapping, ImportMetadata, ImportPreview, ImportSchema, SourcePlanCheck } from '../models/data-source.model';
import { Scenario } from '../models/scenario.model';
import { SourceRunReport } from '../models/source-run-report.model';
@Injectable({ providedIn: 'root' })
export class DataSourceService {
  private readonly http = inject(HttpClient);
  private base(id: string): string { return `/api/scenarios/${encodeURIComponent(id)}/data`; }
  runReport(id: string): Observable<SourceRunReport> {
    return this.http.get<SourceRunReport>(`/api/scenarios/${encodeURIComponent(id)}/artifacts/sources/resolution.json`, { headers: { 'Cache-Control': 'no-cache' } });
  }
  schemas(id: string): Observable<ImportSchema[]> { return this.http.get<ImportSchema[]>(`${this.base(id)}/schemas`); }
  imports(id: string): Observable<DataImport[]> { return this.http.get<DataImport[]>(`${this.base(id)}/imports`); }
  revisions(id: string): Observable<DatasetRevision[]> { return this.http.get<DatasetRevision[]>(`${this.base(id)}/revisions`); }
  profiles(id: string): Observable<DataProfile[]> { return this.http.get<DataProfile[]>(`${this.base(id)}/profiles`); }
  packs(id: string): Observable<DataPack[]> { return this.http.get<DataPack[]>(`${this.base(id)}/packs`); }
  libraryPacks(): Observable<DataLibraryEntry[]> { return this.http.get<DataLibraryEntry[]>('/api/data-library/packs'); }
  publishLibraryPack(file: File): Observable<DataPack> { const form = new FormData(); form.append('file', file); return this.http.post<DataPack>('/api/data-library/packs', form); }
  promotePack(id: string, pack: string): Observable<DataPack> { return this.http.post<DataPack>(`${this.base(id)}/packs/${encodeURIComponent(pack)}/library`, {}); }
  installLibraryPack(id: string, pack: string): Observable<DataPack> { return this.http.post<DataPack>(`${this.base(id)}/library/packs/${encodeURIComponent(pack)}/install`, {}); }
  installPack(id: string, file: File): Observable<DataPack> { const form = new FormData(); form.append('file', file); return this.http.post<DataPack>(`${this.base(id)}/packs`, form); }
  librarySourceProfiles(): Observable<SourceProfileLibraryEntry[]> { return this.http.get<SourceProfileLibraryEntry[]>('/api/data-library/source-profiles'); }
  publishLibrarySourceProfile(definition: SourceProfileDefinition): Observable<SourceProfile> { return this.http.post<SourceProfile>('/api/data-library/source-profiles', definition); }
  promoteSourceProfile(id: string, profile: string): Observable<SourceProfile> { return this.http.post<SourceProfile>(`${this.base(id)}/source-profiles/${encodeURIComponent(profile)}/library`, {}); }
  installLibrarySourceProfile(id: string, profile: string): Observable<SourceProfile> { return this.http.post<SourceProfile>(`${this.base(id)}/library/source-profiles/${encodeURIComponent(profile)}/install`, {}); }
  sourceProfiles(id: string): Observable<SourceProfile[]> { return this.http.get<SourceProfile[]>(`${this.base(id)}/source-profiles`); }
  saveSourceProfile(id: string, definition: SourceProfileDefinition): Observable<SourceProfile> { return this.http.post<SourceProfile>(`${this.base(id)}/source-profiles`, definition); }
  upload(id: string, schema: string, file: File): Observable<DataImport> { const form = new FormData(); form.append('schema', schema); form.append('file', file); return this.http.post<DataImport>(`${this.base(id)}/imports`, form); }
  preview(id: string, input: string, mapping: ImportMapping, metadata: ImportMetadata): Observable<ImportPreview> { return this.http.post<ImportPreview>(`${this.base(id)}/imports/${input}/preview`, { mapping, metadata }); }
  publish(id: string, input: string, mapping: ImportMapping, metadata: ImportMetadata): Observable<DatasetRevision> { return this.http.post<DatasetRevision>(`${this.base(id)}/imports/${input}/revisions`, { mapping, metadata }); }
  saveProfile(id: string, input: string, name: string, mapping: ImportMapping, metadata: ImportMetadata): Observable<DataProfile> { return this.http.post<DataProfile>(`${this.base(id)}/imports/${input}/profiles`, { name, request: { mapping, metadata } }); }
  applyProfile(id: string, input: string, profileId: string): Observable<ImportMapping> { return this.http.post<ImportMapping>(`${this.base(id)}/imports/${input}/profile`, { profileId }); }
  check(id: string, scenario: Scenario): Observable<SourcePlanCheck> { return this.http.post<SourcePlanCheck>(`${this.base(id)}/plan/preview`, scenario); }
}
