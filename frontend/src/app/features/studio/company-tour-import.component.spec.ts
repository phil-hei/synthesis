import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { CompanyTourImport } from '../../core/models/company-tour.model';
import { CompanyTourImportComponent } from './company-tour-import.component';

describe('CompanyTourImportComponent', () => {
  const api = {
    analyzeCompanyTours: vi.fn(), listTourImportProfiles: vi.fn(), saveTourImportProfile: vi.fn(), applyTourImportProfile: vi.fn(),
    listCompanyTours: vi.fn(), uploadCompanyTours: vi.fn(), mapCompanyTours: vi.fn(), deleteCompanyTours: vi.fn(),
    geocodeCompanyTours: vi.fn(), saveCompanyTourContext: vi.fn(), activateCompanyTours: vi.fn(), locateCompanyTours: vi.fn(),
    companyTourExportUrl: vi.fn((id: string, hash: string) => `/api/scenarios/${id}/company-tours/${hash}/export`)
  };
  beforeEach(async () => {
    vi.clearAllMocks(); api.listCompanyTours.mockReturnValue(of([]));
    await TestBed.configureTestingModule({ imports: [CompanyTourImportComponent], providers: [{ provide: ApiService, useValue: api }] }).compileComponents();
  });
  function create() {
    const fixture = TestBed.createComponent(CompanyTourImportComponent);
    fixture.componentRef.setInput('scenarioId', 'test'); fixture.detectChanges(); return fixture;
  }
  it('requires evidence review and retains the pending guard while editing an active source proposal', () => {
    const active = { ...view(), active: true, selection: { enabled: true, imports: [{ importId: 'hash', executionHash: 'e' }] } };
    api.listCompanyTours.mockReturnValue(of([active])); const f = create(); const c = f.componentInstance;
    const pending = vi.spyOn(c.pendingChange, 'emit');
    const proposal = { ...view(), mappingConfirmed: false, analysis: { id: 'a', evidence: [{ row: 2, fields: { tour: { block: 0, start: 0, end: 2 } } }], ignoredBlocks: [] } };
    api.analyzeCompanyTours.mockReturnValue(of(proposal)); c.analyzeSource();
    expect(pending).toHaveBeenLastCalledWith(true); expect(c.evidenceNeedsReview).toBe(true);
    c.run('confirm'); expect(api.mapCompanyTours).not.toHaveBeenCalled();
    c.changed(); expect(pending).toHaveBeenLastCalledWith(true);
    api.mapCompanyTours.mockReturnValue(of(proposal)); c.run('preview');
    c.sourceReviewed = true; api.mapCompanyTours.mockReturnValue(of({ ...proposal, mappingConfirmed: true })); c.run('confirm');
    expect(api.mapCompanyTours).toHaveBeenLastCalledWith('test', 'hash', 'confirm', expect.anything(), true);
    expect(pending).toHaveBeenLastCalledWith(false); f.destroy();
  });
  it('keeps the generation guard for a legacy mapping proposal on an active import', () => {
    api.listCompanyTours.mockReturnValue(of([{ ...view(), active: true }])); const f = create(); const c = f.componentInstance;
    const pending = vi.spyOn(c.pendingChange, 'emit'); api.mapCompanyTours.mockReturnValue(of(view()));
    c.run('suggest'); expect(pending).toHaveBeenLastCalledWith(true);
    c.changed(); expect(pending).toHaveBeenLastCalledWith(true); f.destroy();
  });
  it('requires an explicit regional projection choice and saves it as dirty context', () => {
    const saved = { ...view(), mappingConfirmed: true }; api.listCompanyTours.mockReturnValue(of([saved]));
    const f = create(); const c = f.componentInstance; expect(c.context?.regionalProjectionConfirmed ?? false).toBe(false);
    const label = (Array.from(f.nativeElement.querySelectorAll('label')) as HTMLLabelElement[]).find(l => l.textContent?.includes('Tourorte außerhalb'))!;
    label.querySelector('input')!.click(); f.detectChanges(); expect(c.context?.regionalProjectionConfirmed).toBe(true); expect(c.contextDirty).toBe(true);
    expect(f.nativeElement.textContent).toContain('externe Bedienung und Fahrten werden nicht als abgeschlossen');
    api.saveCompanyTourContext.mockReturnValue(of({ ...saved, context: structuredClone(c.context!) })); c.saveContext();
    expect(api.saveCompanyTourContext).toHaveBeenCalledWith('test','hash',expect.objectContaining({ regionalProjectionConfirmed: true }));f.destroy();
  });
  it('requires a regional choice before network projection and persists or clears that choice', async () => {
    const saved = { ...view(), mappingConfirmed: true }; api.listCompanyTours.mockReturnValue(of([saved]));
    const f = create(); const c = f.componentInstance; await f.whenStable();
    const labels = () => Array.from(f.nativeElement.querySelectorAll('label')) as HTMLLabelElement[];
    expect(c.context?.networkProjectionRequested ?? false).toBe(false);
    expect(labels().some(l => l.textContent?.includes('Grenzübergänge auf'))).toBe(false);
    labels().find(l => l.textContent?.includes('Tourorte außerhalb'))!.querySelector('input')!.click(); f.detectChanges(); await f.whenStable();
    labels().find(l => l.textContent?.includes('Grenzübergänge auf'))!.querySelector('input')!.click(); f.detectChanges(); await f.whenStable();
    expect(c.context?.networkProjectionRequested).toBe(true);
    expect(f.nativeElement.textContent).toContain('Das lokale Netz muss auch externe Tourorte abdecken');
    api.saveCompanyTourContext.mockReturnValue(of({ ...saved, context: structuredClone(c.context!) })); c.saveContext();
    expect(api.saveCompanyTourContext).toHaveBeenCalledWith('test', 'hash', expect.objectContaining({ regionalProjectionConfirmed: true, networkProjectionRequested: true }));
    f.detectChanges(); await f.whenStable(); labels().find(l => l.textContent?.includes('Tourorte außerhalb'))!.querySelector('input')!.click(); f.detectChanges(); await f.whenStable();
    expect(c.context?.networkProjectionRequested).toBe(false); expect(c.contextDirty).toBe(true); f.destroy();
  });
  it('asks for missing operating inputs without an LLM and saves a common depot answer without invented coordinates', async () => {
    const saved = tourView(); api.listCompanyTours.mockReturnValue(of([saved]));
    const f = create(); const c = f.componentInstance; await f.whenStable();
    c.context!.tours[1].included = false; f.detectChanges();
    expect(c.contextQuestions.find(q => q.id === 'depots')?.text).toContain('1 Tour');
    expect(c.contextQuestions.some(q => q.id === 'vehicles')).toBe(true);
    c.commonDepot = { lon: 8, lat: 49, provenance: 'previous-address' };
    c.commonDepotAddressChanged('Beispieldepot 21, 01234 Teststadt'); expect(c.commonDepot.lon).toBeNull();
    c.applyDepot(); f.detectChanges();
    expect(c.context!.depotAddresses?.['t1']).toEqual({ start: c.commonDepotAddress, end: c.commonDepotAddress });
    expect(c.context!.depotAddresses?.['t2']).toEqual({ start: '', end: '' });
    expect(c.context!.tours[0].start.lon).toBeNull();
    expect(c.contextQuestions.some(q => q.id === 'depots')).toBe(false);
    expect(c.contextQuestions.some(q => q.id === 'depot-points')).toBe(true);
    expect(c.contextQuestions.some(q => q.id === 'vehicles')).toBe(true);
    expect(f.nativeElement.textContent).toContain('Wann verlassen die Fahrzeuge das Depot');
    api.saveCompanyTourContext.mockReturnValue(of({ ...saved, context: structuredClone(c.context!) })); c.saveContext();
    expect(api.saveCompanyTourContext).toHaveBeenCalledWith('test', 'hash', expect.objectContaining({ depotAddresses: expect.objectContaining({ t1: { start: c.commonDepotAddress, end: c.commonDepotAddress } }) }));
    expect(c.commonDepotAddress).toBe('Beispieldepot 21, 01234 Teststadt');
    api.listCompanyTours.mockReturnValue(of([])); f.componentRef.setInput('scenarioId', 'other'); f.detectChanges();
    expect(c.commonDepotAddress).toBe(''); expect(c.commonDepot.lon).toBeNull(); f.destroy();
  });
  it('keeps separate depot answers and clears stale coordinates and confirmation after an address change', () => {
    const saved = tourView(); api.listCompanyTours.mockReturnValue(of([saved]));
    const f = create(); const c = f.componentInstance; const t = c.context!.tours[0];
    c.context!.depotAddresses!['t1'] = { start: 'Startdepot', end: 'Enddepot' };
    t.start = { lon: 0, lat: 0, provenance: 'fixture' }; t.end = { lon: 8, lat: 49, provenance: 'fixture' };
    c.context!.locationsConfirmed = true; c.depotAddressChanged(t, 'end');
    expect(t.start.lon).toBe(0); expect(t.end.lon).toBeNull(); expect(c.context!.locationsConfirmed).toBe(false);
    expect(c.context!.depotAddresses!['t1']).toEqual({ start: 'Startdepot', end: 'Enddepot' });
    c.context!.tours.forEach(tour => tour.included = false); c.context!.companyId = 'test';
    expect(c.contextQuestions.map(q => q.id)).toEqual(['selection']); f.destroy();
  });
  it('requires explicit geocoding choices and keeps changed addresses safe from stale candidates', () => {
    const saved = tourView(); api.listCompanyTours.mockReturnValue(of([saved]));
    const f = create(); const c = f.componentInstance; c.commonDepotAddressChanged('Testdepot 1'); c.applyDepot();
    c.current!.geocoding = { enabled: true, endpoint: 'http://127.0.0.1:8585/search', maxBatch: 10, message: 'Local' };
    c.geocode(); expect(api.geocodeCompanyTours).not.toHaveBeenCalled(); c.contextDirty = false;
    const answer = { targetId: 'start:t1', query: 'Testdepot 1', candidates: [
      { label: 'A', point: { lon: 8, lat: 49, provenance: 'local-geocoder:a' }, reference: 'A', attribution: 'Fixture' },
      { label: 'B', point: { lon: 9, lat: 50, provenance: 'local-geocoder:b' }, reference: 'B', attribution: 'Fixture' }
    ] };
    api.geocodeCompanyTours.mockReturnValue(of({ importId: 'hash', endpoint: 'http://127.0.0.1:8585/search', cachedQueries: 0, answers: [answer] })); c.geocode();
    expect(c.context!.tours[0].start.lon).toBeNull(); c.applyUniqueGeocoding(); expect(c.context!.tours[0].start.lon).toBeNull();
    c.applyGeocoding(answer, 1); expect(c.context!.tours.every(t => t.start.lon === 9 && t.end.lon === 9)).toBe(true);
    expect(c.context!.locationsConfirmed).toBe(false); expect(c.contextDirty).toBe(true);
    c.context!.depotAddresses!['t1'].start = 'Changed'; c.depotAddressChanged(c.context!.tours[0], 'start');
    c.applyGeocoding(answer, 0); expect(c.context!.tours[0].start.lon).toBeNull(); f.destroy();
  });
  it('continues after empty batches, preserves answers on save and refreshes explicitly', () => {
    const saved = tourView(); api.listCompanyTours.mockReturnValue(of([saved]));
    const f = create(); const c = f.componentInstance; c.commonDepotAddressChanged('Depot 1'); c.applyDepot(); c.contextDirty = false;
    c.current!.geocoding = { enabled: true, endpoint: 'local', maxBatch: 1, message: 'Local' };
    const first = c.pendingGeocodingTargets[0];
    api.geocodeCompanyTours.mockReturnValue(of({ importId: 'hash', endpoint: 'local', cachedQueries: 0,
      answers: [{ targetId: first, query: 'Depot 1', candidates: [] }] }));
    c.geocode(); expect(api.geocodeCompanyTours).toHaveBeenLastCalledWith(c.scenarioId, 'hash', [first], false);
    expect(c.pendingGeocodingTargets).not.toContain(first);
    const next = c.pendingGeocodingTargets[0];
    api.geocodeCompanyTours.mockReturnValue(of({ importId: 'hash', endpoint: 'local', cachedQueries: 1,
      answers: [{ targetId: next, query: 'Depot 1', candidates: [] }] }));
    c.geocode(); expect(api.geocodeCompanyTours).toHaveBeenLastCalledWith(c.scenarioId, 'hash', [next], false);
    expect(c.geocoding!.answers).toHaveLength(2);
    const count = c.pendingGeocodingTargets.length;
    c.select({ ...c.current!, context: structuredClone(c.context!) });
    expect(c.pendingGeocodingTargets).toHaveLength(count); expect(c.geocoding!.answers).toHaveLength(2);
    api.geocodeCompanyTours.mockReturnValue(of({ importId: 'hash', endpoint: 'local', cachedQueries: 0,
      answers: [{ targetId: first, query: 'Depot 1', candidates: [] }] }));
    c.restartGeocoding(); expect(api.geocodeCompanyTours).toHaveBeenLastCalledWith(c.scenarioId, 'hash', [first], true);
    c.select({ ...c.current!, importId: 'different' }); expect(c.geocoding).toBeNull();
    f.destroy();
  });
  it('renders distinct positional duplicate headers and invalidates confirmation after editing', async () => {
    api.listCompanyTours.mockReturnValue(of([view()]));
    const f = create(); const c = f.componentInstance; await f.whenStable();
    expect(f.nativeElement.textContent).toContain('1: Zeitfenster Kunde');
    expect(f.nativeElement.textContent).toContain('2: Zeitfenster Kunde');
    const windowSelect = Array.from(f.nativeElement.querySelectorAll('label')).find((label: unknown) =>
      (label as HTMLElement).textContent?.includes('Kundenfenster Ende')) as HTMLLabelElement;
    expect(windowSelect.querySelector('select')?.selectedOptions[0].textContent).toContain('2: Zeitfenster Kunde');
    c.setColumn('windowEnd', '0'); c.run('confirm');
    expect(api.mapCompanyTours).not.toHaveBeenCalled();
    api.mapCompanyTours.mockReturnValue(of(view())); c.run('preview');
    expect(api.mapCompanyTours).toHaveBeenCalledWith('test', 'hash', 'preview', expect.objectContaining({ columns: { windowStart: 0, windowEnd: 0 } }), false);
    expect(c.dirty).toBe(false); f.destroy();
  });
  it('keeps mapping available on LLM failure and distinguishes saved draft from simulation', () => {
    api.listCompanyTours.mockReturnValue(of([view()])); const f = create(); const c = f.componentInstance;
    api.mapCompanyTours.mockReturnValue(throwError(() => ({ error: { message: 'Lokales LLM nicht erreichbar' } })));
    c.run('suggest'); expect(c.error).toContain('nicht erreichbar'); expect(c.mapping?.columns['windowEnd']).toBe(1); expect(c.busy).toBe(false);
    api.mapCompanyTours.mockReturnValue(of({ ...view(), mappingConfirmed: true })); c.run('confirm'); f.detectChanges();
    expect(f.nativeElement.textContent).toContain('noch nicht zur Simulation hinzugefügt');
    expect(f.nativeElement.querySelector('a').getAttribute('href')).toContain('/test/company-tours/hash/export');
    f.destroy();
  });
  it('cancels stale requests on scenario switch and clears a previous company draft', () => {
    api.listCompanyTours.mockReturnValue(of([view()])); const f = create(); const c = f.componentInstance;
    const late = new Subject<CompanyTourImport>(); api.mapCompanyTours.mockReturnValue(late); c.run('suggest');
    api.listCompanyTours.mockReturnValue(of([])); f.componentRef.setInput('scenarioId', 'other'); f.detectChanges();
    late.next(view()); expect(c.current).toBe(null); expect(c.imports).toEqual([]); expect(c.busy).toBe(false); f.destroy();
  });
  it('deletes the saved draft and does not duplicate a repeated upload', () => {
    api.listCompanyTours.mockReturnValue(of([view()])); const f = create(); const c = f.componentInstance;
    api.uploadCompanyTours.mockReturnValue(of(view()));
    c.upload({ target: { files: [new File(['data'], 'plan.csv')], value: 'plan.csv' } } as unknown as Event);
    expect(c.imports.length).toBe(1);
    api.deleteCompanyTours.mockReturnValue(of(undefined)); api.listCompanyTours.mockReturnValue(of([])); c.remove();
    expect(api.deleteCompanyTours).toHaveBeenCalledWith('test', 'hash'); expect(c.current).toBe(null); f.destroy();
  });
  it('reloads authoritative selection after deletion rather than overwriting active imports with stale snapshots', () => {
    const a = { ...view(), importId: 'a', selection: { enabled: false, imports: [] } };
    const b = { ...view(), importId: 'b', selection: { enabled: false, imports: [] } };
    api.listCompanyTours.mockReturnValue(of([a, b])); const f = create(); const c = f.componentInstance;
    const emit = vi.spyOn(c.selectionChange, 'emit'); c.select(b);
    const selection = { enabled: true, imports: [{ importId: 'a', executionHash: 'rev' }] };
    api.listCompanyTours.mockReturnValue(of([{ ...a, selection }])); api.deleteCompanyTours.mockReturnValue(of(undefined));
    c.remove(); expect(emit).toHaveBeenLastCalledWith(selection); expect(c.imports.length).toBe(1); f.destroy();
  });
  it('blocks activation of unsaved context and signals edits to an active import until saved', () => {
    const selected = { ...view(), active: true, mappingConfirmed: true, execution: { ready: true, networkVerified: false,
      executionHash: 'rev', selectedTours: 0, legs: 0, problems: [] }, selection: { enabled: true, imports: [{ importId: 'hash', executionHash: 'rev' }] } };
    api.listCompanyTours.mockReturnValue(of([selected])); const f = create(); const c = f.componentInstance;
    const pending = vi.spyOn(c.pendingChange, 'emit'); c.contextChanged(); c.activate(true);
    expect(pending).toHaveBeenLastCalledWith(true); expect(api.activateCompanyTours).not.toHaveBeenCalled();
    const revised = { ...selected, active: false, selection: { enabled: false, imports: [] } };
    api.saveCompanyTourContext.mockReturnValue(of(revised)); c.saveContext();
    expect(c.contextDirty).toBe(false); expect(pending).toHaveBeenLastCalledWith(false); expect(c.current?.active).toBe(false); f.destroy();
  });
  it('only applies unique local coordinate matches and clears location confirmation', () => {
    api.listCompanyTours.mockReturnValue(of([view()])); const f = create(); const c = f.componentInstance;
    const unique = { lon: 8.2, lat: 48.85, provenance: 'local-address-file:abc:2' };
    api.locateCompanyTours.mockReturnValue(of({ matched: 1, ambiguous: 1, missing: 1, matches: [
      { stopId: 'unique', candidates: [unique] }, { stopId: 'ambiguous', candidates: [unique, { ...unique, lon: 8.3 }] }, { stopId: 'missing', candidates: [] }
    ] }));
    c.locate({ target: { files: [new File(['data'], 'places.csv')], value: '' } } as unknown as Event);
    expect(c.context?.locations['unique']).toEqual(unique); expect(c.context?.locations['ambiguous']).toBeUndefined();
    expect(c.context?.locationsConfirmed).toBe(false); expect(c.contextDirty).toBe(true); f.destroy();
  });
  it('shows the actual selected workbook sheet and non-first saved import', async () => {
    const first = view(); const second = { ...view(), importId: 'second' };
    second.tables = [{ ...second.tables[0], id: 'info', name: 'Hinweise' }, { ...second.tables[0], id: 'csv', name: 'Touren' }];
    api.listCompanyTours.mockReturnValue(of([first, second])); const f = create();
    f.componentInstance.select(second); f.detectChanges(); await f.whenStable();
    const labels = Array.from(f.nativeElement.querySelectorAll('label')) as HTMLLabelElement[];
    const saved = labels.find(l => l.textContent?.includes('Gespeicherter Import'))?.querySelector('select');
    const sheet = labels.find(l => l.textContent?.includes('Tabellenblatt'))?.querySelector('select');
    expect(saved?.selectedOptions[0].textContent).toContain('second');
    expect(sheet?.selectedOptions[0].textContent).toContain('Touren'); f.destroy();
  });
});
function view(): CompanyTourImport {
  return {
    importId: 'hash', mappingConfirmed: false, llmEnabled: true,
    mapping: { tableId: 'csv', headerRow: 1, columns: { windowStart: 0, windowEnd: 1 }, dateFormat: 'yyyyMMdd', timezone: 'Europe/Berlin', groupStops: true },
    tables: [{ id: 'csv', name: 'CSV', headerRow: 1, dataRows: 3,
      columns: [{ index: 0, header: 'Zeitfenster Kunde', types: {} }, { index: 1, header: 'Zeitfenster Kunde', types: {} }] }],
    draft: { schemaVersion: 'synthesis-company-tours-v1', sourceHash: 'hash', mappingHash: 'm', status: 'needs-context', simulationReady: false,
      inputRows: 3, mappedRows: 3, rejectedRows: 0, blankRows: 0, unmappedColumns: [], issues: [], tours: [] }
  };
}

function tourView(): CompanyTourImport {
  const value = view(); value.mappingConfirmed = true; value.llmEnabled = false; value.sourceAnalysisEnabled = false;
  value.draft.tours = ['t1', 't2'].map((id, i) => ({ tourId: id, serviceDate: '2026-09-29', stops: [{
    stopId: `s${i}`, sequence: 1, location: { street: 'Testweg 1', postalCode: '01234', city: 'Teststadt' },
    plannedArrival: '2026-09-29T08:00:00+02:00', plannedDeparture: '2026-09-29T08:10:00+02:00', plannedDwellSec: 600,
    sourceRows: [i + 2], orders: []
  }] })); return value;
}
