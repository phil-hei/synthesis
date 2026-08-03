import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ArtifactInfo, CommuterUploadSummary, GenerationJob, Region, RegionEstimate, RoadNetworkUploadSummary,
  Scenario, UploadInfo, UploadSummary, WorkplaceMarginUploadSummary
} from '../models/scenario.model';

@Injectable({ providedIn: 'root' })
export class ApiService {

  private readonly base = '/api';

  constructor(private http: HttpClient) {
  }

  listScenarios(): Observable<Scenario[]> {
    return this.http.get<Scenario[]>(`${this.base}/scenarios`);
  }

  scenarioDefaults(): Observable<Scenario> {
    return this.http.get<Scenario>(`${this.base}/scenarios/defaults`);
  }

  createScenario(scenario: Scenario): Observable<Scenario> {
    return this.http.post<Scenario>(`${this.base}/scenarios`, scenario);
  }

  updateScenario(scenario: Scenario): Observable<Scenario> {
    return this.http.put<Scenario>(`${this.base}/scenarios/${scenario.id}`, scenario);
  }

  deleteScenario(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/scenarios/${id}`);
  }

  previewZones(region: Region, cellSizeMeters: number, mode = 'grid'): Observable<any> {
    return this.http.post<any>(`${this.base}/preview/zones`, { region, cellSizeMeters, mode });
  }

  estimateRegion(region: Region): Observable<RegionEstimate> {
    return this.http.post<RegionEstimate>(`${this.base}/region/estimate`, region);
  }

  uploadPopulationGrid(id: string, file: File): Observable<UploadSummary> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<UploadSummary>(`${this.base}/scenarios/${id}/uploads/population-grid`, form);
  }

  /** GTFS-Feed für die ÖV-Simulation hochladen (Phase-C-Fallback zum Auto-Fetch). */
  uploadGtfs(id: string, file: File): Observable<Scenario> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<Scenario>(`${this.base}/scenarios/${id}/gtfs`, form);
  }

  listUploads(id: string): Observable<UploadInfo[]> {
    return this.http.get<UploadInfo[]>(`${this.base}/scenarios/${id}/uploads`);
  }

  deletePopulationGrid(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/scenarios/${id}/uploads/population-grid`);
  }

  uploadRoadNetwork(id: string, file: File): Observable<RoadNetworkUploadSummary> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<RoadNetworkUploadSummary>(
      `${this.base}/scenarios/${id}/uploads/road-network`, form);
  }

  deleteRoadNetwork(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/scenarios/${id}/uploads/road-network`);
  }

  uploadCommuters(id: string, file: File): Observable<CommuterUploadSummary> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<CommuterUploadSummary>(
      `${this.base}/scenarios/${id}/uploads/commuters`, form);
  }

  deleteCommuters(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/scenarios/${id}/uploads/commuters`);
  }

  uploadWorkplaceMargins(id: string, file: File): Observable<WorkplaceMarginUploadSummary> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<WorkplaceMarginUploadSummary>(
      `${this.base}/scenarios/${id}/uploads/workplace-margins`, form);
  }

  deleteWorkplaceMargins(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/scenarios/${id}/uploads/workplace-margins`);
  }

  generate(id: string): Observable<GenerationJob> {
    return this.http.post<GenerationJob>(`${this.base}/scenarios/${id}/generate`, {});
  }

  generationStatus(id: string): Observable<GenerationJob> {
    return this.http.get<GenerationJob>(`${this.base}/scenarios/${id}/generation`);
  }

  listArtifacts(id: string): Observable<ArtifactInfo[]> {
    return this.http.get<ArtifactInfo[]>(`${this.base}/scenarios/${id}/artifacts`);
  }

  artifactUrl(id: string, path: string): string {
    return `${this.base}/scenarios/${id}/artifacts/${path}`;
  }

  /** Lädt ein JSON-Artefakt (z. B. simulation/kpis.json); 404 wenn Stufe übersprungen. */
  artifactJson<T = any>(id: string, path: string): Observable<T> {
    return this.http.get<T>(this.artifactUrl(id, path));
  }

  /** Bündelt Parameter + Simulations-KPIs mehrerer Szenarien für den Vergleich. */
  compare(ids: string[]): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/comparison`, { params: { ids: ids.join(',') } });
  }
}
