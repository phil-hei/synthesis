import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { OperationsPreview } from '../models/vehicle-operations.model';
import { CompanyTourImport, TourMapping, CompanyTourContext, CompanyTourLocationLookup, TourImportProfile, CompanyTourGeocodingLookup } from '../models/company-tour.model';
import { ComparisonEntry, ZonePreview } from '../models/results.model';
import {
  ArtifactInfo, CommuterUploadSummary, GenerationJob, OsmDataUploadSummary, Region, RegionEstimate, RoadNetworkUploadSummary,
  FreightDemand, Scenario, UploadInfo, UploadSummary, WorkplaceMarginUploadSummary
} from '../models/scenario.model';

@Injectable({ providedIn: 'root' })
export class ApiService {

  private readonly base = '/api';

  constructor(private http: HttpClient) {
  }

  validateVehicleOperations(bundle: unknown): Observable<OperationsPreview> {
    return this.http.post<OperationsPreview>(`${this.base}/vehicle-operations/validate`, bundle);
  }

  listScenarios(): Observable<Scenario[]> {
    return this.http.get<Scenario[]>(`${this.base}/scenarios`);
  }

  listCompanyTours(id: string): Observable<CompanyTourImport[]> {
    return this.http.get<CompanyTourImport[]>(`${this.base}/scenarios/${id}/company-tours`);
  }

  uploadCompanyTours(id: string, file: File): Observable<CompanyTourImport> {
    const body = new FormData(); body.append('file', file);
    return this.http.post<CompanyTourImport>(`${this.base}/scenarios/${id}/company-tours`, body);
  }

  mapCompanyTours(id: string, hash: string, action: 'preview' | 'confirm' | 'suggest', mapping: TourMapping, sourceReviewed = false): Observable<CompanyTourImport> {
    return this.http.post<CompanyTourImport>(`${this.base}/scenarios/${id}/company-tours/${hash}/${action}`, sourceReviewed ? { mapping, sourceReviewed } : { mapping });
  }

  deleteCompanyTours(id: string, hash: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/scenarios/${id}/company-tours/${hash}`);
  }

  companyTourExportUrl(id: string, hash: string): string {
    return `${this.base}/scenarios/${id}/company-tours/${hash}/export`;
  }
  analyzeCompanyTours(id: string, hash: string, mode: string): Observable<CompanyTourImport> {
    return this.http.post<CompanyTourImport>(`${this.base}/scenarios/${id}/company-tours/${hash}/analyze`, { mode });
  }
  listTourImportProfiles(id: string): Observable<TourImportProfile[]> {
    return this.http.get<TourImportProfile[]>(`${this.base}/scenarios/${id}/company-tours/profiles`);
  }
  saveTourImportProfile(id: string, hash: string, name: string): Observable<TourImportProfile> {
    return this.http.post<TourImportProfile>(`${this.base}/scenarios/${id}/company-tours/${hash}/profile`, { name });
  }
  applyTourImportProfile(id: string, hash: string, profileId: string): Observable<CompanyTourImport> {
    return this.http.post<CompanyTourImport>(`${this.base}/scenarios/${id}/company-tours/${hash}/apply-profile`, { profileId });
  }
  saveCompanyTourContext(id: string, hash: string, context: CompanyTourContext): Observable<CompanyTourImport> {
    return this.http.put<CompanyTourImport>(`${this.base}/scenarios/${id}/company-tours/${hash}/context`, context);
  }
  activateCompanyTours(id: string, hash: string, enabled: boolean): Observable<CompanyTourImport> {
    return this.http.post<CompanyTourImport>(`${this.base}/scenarios/${id}/company-tours/${hash}/activation`, { enabled });
  }
  geocodeCompanyTours(id: string, hash: string, targets?: string[], refresh = false): Observable<CompanyTourGeocodingLookup> {
    return this.http.post<CompanyTourGeocodingLookup>(`${this.base}/scenarios/${id}/company-tours/${hash}/geocode`, { targets, refresh });
  }
  locateCompanyTours(id: string, hash: string, file: File): Observable<CompanyTourLocationLookup> {
    const body = new FormData(); body.append('file', file);
    return this.http.post<CompanyTourLocationLookup>(`${this.base}/scenarios/${id}/company-tours/${hash}/locations`, body);
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

  previewZones(region: Region, cellSizeMeters: number, mode = 'grid',
               maxZones = 600): Observable<ZonePreview> {
    return this.http.post<ZonePreview>(
      `${this.base}/preview/zones`, { region, cellSizeMeters, mode, maxZones });
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

  uploadSevas(id: string, file: File): Observable<FreightDemand> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<FreightDemand>(`${this.base}/scenarios/${id}/freight/sevas`, form);
  }

  uploadFreight(id: string, file: File): Observable<FreightDemand> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<FreightDemand>(`${this.base}/scenarios/${id}/freight`, form);
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

  uploadOsmData(id: string, file: File): Observable<OsmDataUploadSummary> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<OsmDataUploadSummary>(
      `${this.base}/scenarios/${id}/uploads/osm-data`, form);
  }

  deleteOsmData(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/scenarios/${id}/uploads/osm-data`);
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

  uploadEnergySnapshot(id: string, file: File): Observable<{ name: string; objects: number; sha256: string }> {
    const form = new FormData(); form.append('file', file);
    return this.http.post<{ name: string; objects: number; sha256: string }>(`${this.base}/scenarios/${id}/energy-infrastructure/osm-snapshot`, form);
  }

  deleteEnergySnapshot(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/scenarios/${id}/energy-infrastructure/osm-snapshot`);
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
  artifactJson<T = unknown>(id: string, path: string): Observable<T> {
    return this.http.get<T>(this.artifactUrl(id, path));
  }

  /** Bündelt Parameter + Simulations-KPIs mehrerer Szenarien für den Vergleich. */
  compare(ids: string[]): Observable<ComparisonEntry[]> {
    return this.http.get<ComparisonEntry[]>(
      `${this.base}/comparison`, { params: { ids: ids.join(',') } });
  }
}
