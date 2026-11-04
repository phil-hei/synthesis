import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of, Subject, throwError } from 'rxjs';
import { DataWorkspaceComponent } from './data-workspace.component';
import { DataSourceService } from '../../core/services/data-source.service';
import { Scenario } from '../../core/models/scenario.model';
import { DataImport, SourceProfileDefinition, DatasetRevision, ImportPreview, ImportSchema } from '../../core/models/data-source.model';

describe('DataWorkspaceComponent', () => {
  const schema: ImportSchema = { id: 'workplaces-v1', label: 'Arbeitsplätze', aspect: 'opportunities.work', unit: 'jobs', fields: [{ id: 'value', label: 'Arbeitsplatzanzahl', type: 'number', required: true, aliases: ['jobs'] }] };
  const source: DataImport = { draft: { id: 'raw', filename: 'jobs.csv', schemaId: schema.id, source: { format: 'csv', sourceHash: 'rawhash', tables: [{ id: 'csv', name: 'CSV', rows: [{ sourceRow: 1, cells: ['jobs'] }, { sourceRow: 2, cells: ['12'] }] }] } }, mapping: { tableId: 'csv', headerRow: 1, columns: { value: 0 }, decimalSeparator: '.' } };
  const revision: DatasetRevision = { id: 'revision', schemaId: schema.id, label: 'Gemeinde', source: 'Testquelle', referenceDate: '2026-01-01', unit: 'jobs', rows: 1 };
  const preview: ImportPreview = { status: 'valid', inputRows: 1, mappedRows: 1, rejectedRows: 0, blankRows: 0, issues: [], sample: [{ sourceRow: 2, values: { value: 12 } }] };
  const api = { runReport: vi.fn(), librarySourceProfiles: vi.fn(), publishLibrarySourceProfile: vi.fn(), promoteSourceProfile: vi.fn(), installLibrarySourceProfile: vi.fn(), libraryPacks: vi.fn(), publishLibraryPack: vi.fn(), promotePack: vi.fn(), installLibraryPack: vi.fn(), packs: vi.fn(), installPack: vi.fn(), sourceProfiles: vi.fn(), saveSourceProfile: vi.fn(), schemas: vi.fn(), imports: vi.fn(), revisions: vi.fn(), profiles: vi.fn(), preview: vi.fn(), publish: vi.fn(), upload: vi.fn(), applyProfile: vi.fn(), saveProfile: vi.fn(), check: vi.fn() };
  beforeEach(async () => {
    vi.clearAllMocks(); api.runReport.mockReturnValue(throwError(() => ({ status: 404 }))); api.librarySourceProfiles.mockReturnValue(of([])); api.libraryPacks.mockReturnValue(of([])); api.packs.mockReturnValue(of([])); api.sourceProfiles.mockReturnValue(of([])); api.schemas.mockReturnValue(of([schema])); api.imports.mockReturnValue(of([])); api.revisions.mockReturnValue(of([])); api.profiles.mockReturnValue(of([]));
    await TestBed.configureTestingModule({ imports: [DataWorkspaceComponent], providers: [{ provide: DataSourceService, useValue: api }] }).compileComponents();
  });
  function create() {
    const f = TestBed.createComponent(DataWorkspaceComponent);
    f.componentRef.setInput('scenario', { id: 's', region: { minLon: 8, maxLon: 9, minLat: 48, maxLat: 49 } } as Scenario); f.detectChanges(); return f;
  }
  it('keeps legacy defaults and separates importing, publishing and selecting a revision', () => {
    const f = create(); const c = f.componentInstance; expect(c.scenario.dataPlan?.enabled).toBe(false);
    c.selectImport(source); c.metadata!.source = 'Testquelle'; c.metadata!.referenceDate = '2026-01-01';
    c.runPreview(); expect(api.preview).not.toHaveBeenCalled(); expect(c.error).toContain('bestätigen');
    c.coverageConfirmed = true; c.metadata!.coverageDeclaration = { kind: 'point-register', completeness: 'complete', statement: 'Vollständiges Testregister für alle Betriebe im Gebiet' }; api.preview.mockReturnValue(of(preview)); c.runPreview(); expect(c.preview?.status).toBe('valid');
    api.publish.mockReturnValue(of(revision)); c.publish(); expect(c.revisions).toEqual([revision]); expect(c.scenario.dataPlan?.enabled).toBe(false); expect(c.policy().source).toBe('legacy');
    c.useRevision(revision); expect(c.scenario.dataPlan?.enabled).toBe(true); expect(c.policy().revision).toBe('revision'); expect(c.preview).toEqual(preview); f.destroy();
  });
  it('invalidates the normalized preview on edits and propagates the generation guard', () => {
    const f = create(); const c = f.componentInstance; const pending = vi.spyOn(c.pending, 'emit'); c.selectImport(source); c.preview = preview;
    c.mappingChanged(); expect(c.preview).toBeNull(); expect(pending).toHaveBeenLastCalledWith(true); c.publish(); expect(api.publish).not.toHaveBeenCalled();
    c.beginImport(); expect(pending).toHaveBeenLastCalledWith(false); f.destroy();
  });
  it('uses the server plan check and never treats a blocker as a ready plan', () => {
    const f = create(); const c = f.componentInstance; api.check.mockReturnValue(of({ ready: false, issues: ['Menge fehlt'], decisions: [] }));
    c.checkPlan(); f.detectChanges(); expect(api.check).toHaveBeenCalledWith('s', c.scenario); expect(f.nativeElement.textContent).toContain('Menge fehlt');
    const save = (Array.from(f.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(b => b.textContent?.includes('geprüftem Quellenplan')); expect(save?.disabled).toBe(true); f.destroy();
  });
  it('cancels requests on a scenario switch so stale data cannot enter the new scenario', () => {
    const f = create(); const c = f.componentInstance; const response = new Subject<DatasetRevision[]>(); api.revisions.mockReturnValue(response); c.reload();
    api.revisions.mockReturnValue(of([])); f.componentRef.setInput('scenario', { id: 'other', region: c.scenario.region } as Scenario); f.detectChanges(); response.next([revision]);
    expect(c.revisions).toEqual([]); expect(c.current).toBeNull(); f.destroy();
  });
  it('starts completeness as unknown and blocks partial registers and municipal aggregates', () => {
    const f = create(); const c = f.componentInstance; c.selectImport(source); c.coverageConfirmed = true;
    expect(c.metadata!.coverageDeclaration?.completeness).toBe('unknown');
    c.runPreview(); expect(api.preview).not.toHaveBeenCalled(); expect(c.error).toContain('Vollständiges Punktregister');
    c.metadata!.coverageDeclaration!.completeness = 'partial'; c.runPreview(); expect(api.preview).not.toHaveBeenCalled();
    c.metadata!.coverageDeclaration = { kind: 'municipality-aggregate', completeness: 'complete', statement: 'Gemeindesummen mit AGS und Gebietsstand' };
    c.runPreview(); expect(api.preview).not.toHaveBeenCalled(); expect(c.error).toContain('Gemeindeschlüssel');
    c.metadata!.coverageDeclaration = { kind: 'point-register', completeness: 'complete', statement: ' ' };
    c.preview = preview; c.publish(); expect(api.publish).not.toHaveBeenCalled();
    c.selectImport(source); expect(c.metadata!.coverageDeclaration?.completeness).toBe('unknown'); f.destroy();
  });
  it('persists the explicit declaration and invalidates a preview when its scope changes', async () => {
    const f = create(); const c = f.componentInstance; c.selectImport(source); c.coverageConfirmed = true;
    c.metadata!.coverageDeclaration = { kind: 'point-register', completeness: 'complete', statement: 'Vollständiges Register aller Arbeitsstätten im Gebiet' };
    api.preview.mockReturnValue(of(preview)); c.runPreview(); f.detectChanges(); await f.whenStable();
    expect(api.preview.mock.calls[0][3].coverageDeclaration.completeness).toBe('complete');
    const scope = f.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    scope.value = 'Anderer Erhebungsumfang'; scope.dispatchEvent(new Event('input')); await f.whenStable(); f.detectChanges();
    expect(c.preview).toBeNull(); expect(c.importDirty).toBe(true); c.publish(); expect(api.publish).not.toHaveBeenCalled();
    expect(c.metadata!.coverageDeclaration?.statement).toBe('Anderer Erhebungsumfang'); f.destroy();
  });
  it('shows the declared scope in the server check without claiming completed spatial allocation', () => {
    const f = create(); const c = f.componentInstance;
    api.check.mockReturnValue(of({ ready: true, issues: [], decisions: [{ aspect: schema.aspect, source: 'dataset', status: 'resolved', evidence: 'user-provided-unverified', applicationStatus: 'pending-spatial-allocation', coverageDeclaration: { kind: 'point-register', completeness: 'complete', statement: 'Alle Arbeitsstätten innerhalb der angegebenen Box' } }] }));
    c.checkPlan(); f.detectChanges(); expect(f.nativeElement.textContent).toContain('Alle Arbeitsstätten');
    expect(f.nativeElement.textContent).toContain('erst im Lauf bilanziert'); f.destroy();
  });

  it('versions explicit candidate order and exclusions without activating or replacing a scenario selection', () => {
    const f = create(); const c = f.componentInstance; const pending = vi.spyOn(c.pending, 'emit');
    c.sourceDraft().name = 'Regionale Arbeitsplätze'; c.addCandidate('revision:first'); c.addCandidate('revision:second');
    c.moveCandidate(1, -1); c.sourceDraft().candidates[0].excluded = true; c.sourceDraftChanged();
    expect(c.hasPendingEdits).toBe(true); expect(pending).toHaveBeenLastCalledWith(true);
    api.saveSourceProfile.mockImplementation((_id, definition) => of({ id: 'profile-version', definition }));
    c.saveSourceDraft();
    const definition = api.saveSourceProfile.mock.calls[0][1] as SourceProfileDefinition;
    expect(definition.aspects['opportunities.work'].candidates.map(v => v.provider)).toEqual(['revision:second', 'revision:first']);
    expect(definition.aspects['opportunities.work'].candidates[0].excluded).toBe(true);
    expect(definition.aspects['opportunities.work'].fallback).toBe('none');
    expect(c.sourceProfiles[0].id).toBe('profile-version'); expect(c.policy().source).toBe('legacy');
    expect(c.scenario.dataPlan?.enabled).toBe(false); expect(c.hasPendingEdits).toBe(false); f.destroy();
  });
  it('keeps pending source drafts separate per aspect and clears them on a scenario switch', () => {
    const f = create(); const c = f.componentInstance; c.sourceDraft().name = 'Arbeit'; c.addCandidate('revision:work');
    c.selected = 'population.distribution'; c.sourceDraft().name = 'Bevölkerung'; c.addCandidate('revision:population');
    c.selected = 'opportunities.work'; expect(c.sourceDraft().name).toBe('Arbeit');
    expect(c.sourceDraft().candidates[0].provider).toBe('revision:work');
    f.componentRef.setInput('scenario', { id: 'other', region: c.scenario.region } as Scenario); f.detectChanges();
    expect(c.sourceDraft().candidates).toEqual([]); expect(c.hasPendingEdits).toBe(false); f.destroy();
  });
  it('keeps candidate diagnostics visible when a source profile blocks generation', () => {
    const f = create(); const c = f.componentInstance;
    api.check.mockReturnValue(of({ ready: false, issues: ['Profilquelle ungültig'], decisions: [{ aspect: schema.aspect, source: 'profile', status: 'insufficient-input', evidence: 'unknown', candidates: [{ provider: 'pack:broken:work', rank: 1, status: 'invalid', reason: 'Datenpaket verändert', onUnavailable: 'next' }] }] }));
    c.checkPlan(); f.detectChanges(); expect(f.nativeElement.textContent).toContain('Datenpaket verändert');
    expect(f.nativeElement.textContent).toContain('Ungültig'); expect(f.nativeElement.textContent).toContain('Eingabe unzureichend'); f.destroy();
  });
  it('installs a pack into the catalogue without activating the source plan', () => {
    const f = create(); const c = f.componentInstance;
    const pack = { id: 'pack-version', packId: 'local', title: 'Lokale Daten', edition: '2026', sources: [] };
    api.installPack.mockReturnValue(of(pack)); api.packs.mockReturnValue(of([pack]));
    const input = { files: [new File(['zip'], 'data.zip')], value: 'data.zip' };
    c.installPack({ target: input } as unknown as Event);
    expect(input.value).toBe(''); expect(c.packs).toEqual([pack]); expect(c.scenario.dataPlan?.enabled).toBe(false);
    expect(c.policy().source).toBe('legacy'); f.destroy();
  });

  it('deduplicates aspects and keeps allocation references out of scenario source selection', () => {
    const f = create(); const c = f.componentInstance;
    c.schemas.push({ ...schema, id: 'municipal-workplaces-v1', label: 'Arbeitsplätze je Gemeinde' }, { ...schema, id: 'municipality-allocation-v1', label: 'Gemeindegewichte', aspect: 'support.municipalityAllocation', unit: 'relative-weight' });
    expect(c.aspects.map(s => s.aspect)).toEqual(['opportunities.work']);
    const allocation = { ...revision, id: 'allocation-revision', schemaId: 'municipality-allocation-v1' }; c.revisions = [allocation];
    expect(c.allocationRevisions).toEqual([allocation]); c.useRevision(allocation);
    expect(c.scenario.dataPlan?.enabled).toBe(false); expect(c.policy().source).toBe('legacy');
    c.beginImport(); expect(c.importSchemaId).toBe('workplaces-v1'); f.destroy();
  });
  it('initializes municipal key editions and explicit allocation references without guessing them', () => {
    const f = create(); const c = f.componentInstance;
    c.schemas.push({ ...schema, id: 'municipal-workplaces-v1', label: 'Arbeitsplätze je Gemeinde' });
    c.selectImport({ ...source, draft: { ...source.draft, schemaId: 'municipal-workplaces-v1' } });
    expect(c.metadata!.geography).toEqual({ codeSystem: 'de:AGS', edition: '', allocationMethod: 'explicit-point-weights-v1' });
    expect(c.metadata!.coverageDeclaration?.kind).toBe('municipality-aggregate');
    expect(c.metadata!.coverageDeclaration?.completeness).toBe('unknown');
    c.metadata!.coverageDeclaration!.completeness = 'complete'; c.metadata!.coverageDeclaration!.statement = 'Aggregate für alle enthaltenen Gemeindeschlüssel';
    c.metadata!.geography!.edition = '2025-01-01'; c.metadata!.geography!.allocationRevision = 'allocation-hash';
    c.coverageConfirmed = true; api.preview.mockReturnValue(of(preview)); c.runPreview();
    expect(api.preview.mock.calls[0][3].geography.allocationRevision).toBe('allocation-hash');
    c.selectImport(source); expect(c.metadata!.geography).toBeUndefined(); f.destroy();
  });
  it('requires an explicit exploration acknowledgement and displays the analysis restriction', () => {
    const f = create(); const c = f.componentInstance; c.scenario.dataPlan!.enabled = true;
    const freight = c.policy('freight.vehicleOd'); freight.source = 'model'; freight.internalTrips = 3;
    expect(c.scenario.dataPlan!.qualityMode).toBe('exploration'); expect(c.status('freight.vehicleOd')).toContain('freigabe');
    freight.demonstrationAccepted = true; expect(c.status('freight.vehicleOd')).toContain('Annahmenbeschreibung');
    freight.assumptionStatement = 'Technische synthetische Demonstration'; expect(c.status('freight.vehicleOd')).toContain('Explorationsdemonstration');
    c.scenario.dataPlan!.qualityMode = 'analysis'; expect(c.status('freight.vehicleOd')).toContain('gesperrt');
    api.check.mockReturnValue(of({ ready: false, issues: ['Lkw-Demonstration ist für fachliche Auswertungen nicht freigegeben'], decisions: [] }));
    c.checkPlan(); f.detectChanges(); expect(f.nativeElement.textContent).toContain('nicht freigegeben');
    expect(api.check.mock.calls[0][1].dataPlan.qualityMode).toBe('analysis'); f.destroy();
  });

  it('keeps geometry references and point weights separate when the allocation method changes', () => {
    const f = create(); const c = f.componentInstance;
    c.schemas.push({ ...schema, id: 'municipal-workplaces-v1' });
    c.selectImport({ ...source, draft: { ...source.draft, schemaId: 'municipal-workplaces-v1' } });
    const points = { ...revision, id: 'points', schemaId: 'municipality-allocation-v1' };
    const areas = { ...revision, id: 'areas', schemaId: 'municipality-areas-v1' }; c.revisions = [points, areas];
    expect(c.allocationRevisions).toEqual([points]); c.metadata!.geography!.allocationRevision = 'points';
    c.metadata!.geography!.allocationMethod = 'uniform-area-v1'; c.allocationMethodChanged();
    expect(c.metadata!.geography!.allocationRevision).toBeUndefined(); expect(c.allocationRevisions).toEqual([areas]); expect(c.hasPendingEdits).toBe(true);
    expect(c.formatCell({ type: 'Polygon', coordinates: [] })).toContain('Polygon'); f.destroy();
  });

  it('configures population provider order and exclusions without altering other aspects', async () => {
    const f = create(); const c = f.componentInstance;
    c.selected = 'population.distribution'; c.scenario.dataPlan!.enabled = true;
    c.policy().source = 'builtins'; c.modeChanged(); f.detectChanges(); await f.whenStable();
    expect(c.policy().providers!.map(p => p.provider)).toEqual(['upload-grid', 'census-2022', 'osm-places', 'poi-density', 'uniform']);
    expect(f.nativeElement.textContent).toContain('Reihenfolge der Bevölkerungsquellen');
    c.check = { ready: true, issues: [], decisions: [] };
    c.movePopulationProvider(4, -1); expect(c.check).toBeNull();
    expect(c.policy().providers![3].provider).toBe('uniform');
    const boxes = [...f.nativeElement.querySelectorAll('input[type=checkbox]')] as HTMLInputElement[];
    const exclude = boxes.find(b => b.parentElement!.textContent!.includes('Diese Quelle abwählen'))!;
    exclude.checked = true; exclude.dispatchEvent(new Event('change')); await f.whenStable();
    expect(c.policy().providers![0].excluded).toBe(true);
    expect(c.policy('opportunities.work').source).toBe('legacy');
    for (const provider of c.policy().providers!) provider.excluded = true;
    expect(c.status('population.distribution')).toContain('Keine Bevölkerungsquelle'); f.destroy();
  });
  it('shows pending native source resolution without claiming source availability', () => {
    const f = create(); const c = f.componentInstance;
    api.check.mockReturnValue(of({ ready: true, issues: [], decisions: [{ aspect: 'population.distribution', source: 'builtins', status: 'pending-resolution', evidence: 'not-assessed', candidates: [{ provider: 'census-2022', rank: 1, status: 'pending', reason: 'requires-runtime-inputs', onUnavailable: 'next' }] }] }));
    c.checkPlan(); f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Quellenauflösung im Lauf');
    expect(f.nativeElement.textContent).toContain('Erst im Lauf prüfbar');
    expect(f.nativeElement.textContent).toContain('Destatis Zensus 2022'); f.destroy();
  });

  it('configures workplace sources independently and labels their output as model weights', async () => {
    const f = create(); const c = f.componentInstance;
    c.scenario.dataPlan!.enabled = true; c.policy().source = 'builtins'; c.modeChanged(); f.detectChanges(); await f.whenStable();
    expect(c.policy().providers!.map(p => p.provider)).toEqual(['osm-pois', 'synthetic']);
    expect(f.nativeElement.textContent).toContain('Reihenfolge der Arbeitsplatzquellen');
    expect(f.nativeElement.textContent).toContain('keine beobachtete Arbeitsplatzsumme');
    expect(f.nativeElement.textContent).not.toContain('Reihenfolge der Bevölkerungsquellen');
    c.movePopulationProvider(1, -1); expect(c.policy().providers![0].provider).toBe('synthetic');
    c.policy().providers![1].excluded = true; c.planChanged();
    expect(c.policy('population.distribution').source).toBe('legacy');
    expect(c.providerLabel('osm-pois')).toBe('OSM-Arbeitsgewichte');
    expect(c.providerLabel('uniform')).toBe('Ersatz ohne Ortsdaten'); f.destroy();
  });

  it('mixes native candidates and revisions while preventing implicit legacy fallback', () => {
    const f = create(); const c = f.componentInstance;
    expect(c.providerChoices.some(p => p.provider === 'builtin:osm-pois')).toBe(true);
    c.sourceDraft().fallback = 'legacy'; c.addCandidate('builtin:osm-pois');
    expect(c.sourceDraft().fallback).toBe('none'); expect(c.sourceDraftHasNatives).toBe(true);
    expect(c.providerLabel('builtin:uniform')).toBe('Ersatz ohne Ortsdaten');
    api.saveSourceProfile.mockImplementation((_id, definition) => of({ id: 'mixed-profile', definition }));
    c.sourceDraft().name = 'Gemischtes Arbeitsplatzprofil'; c.addCandidate('revision:work'); c.saveSourceDraft();
    c.policy().source = 'profile'; c.policy().profile = 'mixed-profile'; c.policy().gaps = 'legacy'; c.profileChanged();
    expect(c.mixedProfileSelected).toBe(true); expect(c.policy().gaps).toBe('none');
    expect(c.candidateStatus('staged')).toContain('gebunden'); f.destroy();
  });
  it('configures gap sources separately and clears their check after edits', async () => {
    const f = create(); const c = f.componentInstance; c.scenario.dataPlan!.enabled = true;
    c.policy().source = 'dataset'; c.policy().gaps = 'providers'; c.gapModeChanged(); f.detectChanges(); await f.whenStable();
    expect(c.policy().gapProviders!.map(p => p.provider)).toEqual(['osm-pois', 'synthetic']);
    expect(f.nativeElement.textContent).toContain('Quellen für Registerlücken');
    c.check = { ready: true, issues: [], decisions: [] }; c.moveGapProvider(1, -1); expect(c.check).toBeNull();
    expect(c.policy().gapProviders![0].provider).toBe('synthetic'); f.detectChanges(); await f.whenStable();
    const box = [...f.nativeElement.querySelectorAll('input[type=checkbox]')].find((b: any) => b.parentElement.textContent.includes('Diese Lückenquelle abwählen')) as HTMLInputElement;
    box.checked = true; box.dispatchEvent(new Event('change')); await f.whenStable();
    expect(c.policy().gapProviders![0].excluded).toBe(true); expect(c.policy('population.distribution').source).toBe('legacy');
    c.policy().source = 'builtins'; c.modeChanged(); expect(c.policy().gaps).toBe('legacy'); f.destroy();
  });

  it('saves regional register strategy and keeps native fillers in their separate list', async () => {
    const f = create(); const c = f.componentInstance;
    c.sourceDraft().name = 'Regionale Register'; c.sourceDraft().strategy = 'fill-gaps'; c.strategyChanged();
    expect(c.providerChoices.every(p => !p.provider.startsWith('builtin:'))).toBe(true);
    c.addCandidate('revision:first'); c.addCandidate('revision:second');
    api.saveSourceProfile.mockImplementation((_id, definition) => of({ id: 'regional-profile', definition }));
    c.saveSourceDraft(); expect(api.saveSourceProfile.mock.calls[0][1].aspects['opportunities.work'].strategy).toBe('fill-gaps');
    c.policy().source = 'profile'; c.policy().profile = 'regional-profile'; c.policy().gaps = 'legacy'; c.profileChanged();
    expect(c.policy().gaps).toBe('none'); expect(c.regionalProfileSelected).toBe(true);
    c.policy().gaps = 'providers'; c.gapModeChanged(); f.detectChanges(); await f.whenStable();
    expect(f.nativeElement.textContent).toContain('Regionale Registerergänzung'); expect(c.policy().gapProviders).toHaveLength(2); f.destroy();
  });
  it('requires native draft candidates to be moved out of regional register strategy', () => {
    const f = create(); const c = f.componentInstance;
    c.sourceDraft().name = 'Ungültige regionale Mischung'; c.addCandidate('builtin:synthetic');
    c.sourceDraft().strategy = 'fill-gaps'; c.strategyChanged(); c.saveSourceDraft();
    expect(api.saveSourceProfile).not.toHaveBeenCalled(); f.detectChanges();
    expect(f.nativeElement.textContent).toContain('native Kandidaten hier entfernen'); f.destroy();
  });

  it('configures population mask models and a manual total independently of Work', async () => {
    const f = create(); const c = f.componentInstance;
    c.schemas.push({ id: 'population-cells-v1', label: 'Bevölkerungsraster mit Wertstatus', aspect: 'population.distribution', unit: 'persons', fields: [] });
    const revision = { id: 'mask', schemaId: 'population-cells-v1', label: 'Maskenraster', source: 'Fixture', referenceDate: '2026-01-01', unit: 'persons', rows: 5 };
    c.revisions = [revision]; c.scenario.population = { totalPersons: null } as Scenario['population']; c.useRevision(revision);
    expect(c.policy().gaps).toBe('none'); expect(c.maskedPopulationSelected).toBe(true);
    c.policy().gaps = 'providers'; c.gapModeChanged(); f.detectChanges(); await f.whenStable();
    expect(c.policy().gapProviders!.map(p => p.provider)).toEqual(['osm-places', 'poi-density', 'uniform']);
    expect(f.nativeElement.textContent).toContain('Modellquellen für Rasterlücken');
    const number = f.nativeElement.querySelector('input[type=number]') as HTMLInputElement; number.value = '60'; number.dispatchEvent(new Event('input')); await f.whenStable();
    expect(c.scenario.population.totalPersons).toBe(60); expect(c.policy('opportunities.work').source).toBe('legacy');
    c.beginImport(); expect(c.importSchemaId).toBe('population-grid-v1'); f.destroy();
  });
  it('shows the typed cell-status contract without requesting point-register completeness', () => {
    const f = create(); const c = f.componentInstance;
    c.schemas.push({ id: 'workplace-cells-v1', label: 'Arbeitsplatzraster mit Wertstatus', aspect: 'opportunities.work', unit: 'jobs', fields: [] });
    c.selectImport({ ...source, draft: { ...source.draft, schemaId: 'workplace-cells-v1' } }); f.detectChanges();
    expect(f.nativeElement.textContent).toContain('West-/Südkante'); expect(f.nativeElement.textContent).toContain('not-collected');
    expect(c.metadata!.coverageDeclaration).toBeUndefined(); expect(c.metadata!.geography).toBeUndefined(); f.destroy();
  });

  it('shares and copies exact library versions without activating or rebinding a source', () => {
    const f = create(); const c = f.componentInstance;
    const pack = { id: 'pack-version', packId: 'regional', title: 'Regionale Daten', edition: '2026', sources: [] };
    const entry = { id: pack.id, status: 'available', issue: '', pack };
    const before = structuredClone(c.scenario.dataPlan);
    api.promotePack.mockReturnValue(of(pack)); api.libraryPacks.mockReturnValue(of([entry]));
    c.promotePack(pack.id); expect(api.promotePack).toHaveBeenCalledWith('s', pack.id); expect(c.libraryHas(pack.id)).toBe(true);
    expect(c.scenario.dataPlan).toEqual(before);
    api.installLibraryPack.mockReturnValue(of(pack)); api.packs.mockReturnValue(of([pack])); api.revisions.mockReturnValue(of([revision]));
    c.installLibraryPack(pack.id); expect(api.installLibraryPack).toHaveBeenCalledWith('s', pack.id);
    expect(c.packs).toEqual([pack]); expect(c.revisions).toEqual([revision]); expect(c.scenario.dataPlan).toEqual(before);
    c.tab = 'inventory'; f.detectChanges();
    const buttons = [...f.nativeElement.querySelectorAll('button')] as HTMLButtonElement[];
    expect(buttons.find(b => b.textContent?.includes('In diesem Szenario vorhanden'))?.disabled).toBe(true);
    expect(buttons.find(b => b.textContent?.includes('In Bibliothek vorhanden'))?.disabled).toBe(true); f.destroy();
  });
  it('publishes packages independently and exposes invalid versions without permitting adoption', () => {
    const f = create(); const c = f.componentInstance;
    const pack = { id: 'pack-version', packId: 'regional', title: 'Regionale Daten', edition: '2026', sources: [] };
    api.publishLibraryPack.mockReturnValue(of(pack)); api.libraryPacks.mockReturnValue(of([{ id: 'corrupt', status: 'invalid', issue: 'Bibliothekspaket verändert' }]));
    const input = { files: [new File(['zip'], 'library.zip')], value: 'library.zip' };
    c.publishLibraryPack({ target: input } as unknown as Event); expect(input.value).toBe('');
    expect(api.publishLibraryPack).toHaveBeenCalledOnce(); expect(c.packs).toEqual([]); expect(c.scenario.dataPlan?.enabled).toBe(false);
    c.tab = 'inventory'; f.detectChanges(); expect(f.nativeElement.textContent).toContain('Bibliothekspaket verändert');
    const button = [...f.nativeElement.querySelectorAll('button')].find((b: HTMLButtonElement) => b.textContent?.includes('In dieses Szenario übernehmen')) as HTMLButtonElement;
    expect(button.disabled).toBe(true); f.destroy();
  });
  it('keeps scenario data usable when the shared library is unavailable and respects read-only mode', () => {
    const f = create(); const c = f.componentInstance;
    api.libraryPacks.mockReturnValue(throwError(() => ({ error: { message: 'Bibliothek offline' } })));api.revisions.mockReturnValue(of([revision]));
    c.reload(); expect(c.busy).toBe(false); expect(c.revisions).toEqual([revision]); expect(c.error).toBe(''); expect(c.libraryError).toBe('Bibliothek offline');
    f.componentRef.setInput('disabled', true); f.detectChanges(); c.promotePack('pack');c.installLibraryPack('pack');
    const input = { files: [new File(['zip'], 'library.zip')], value: 'library.zip' };c.publishLibraryPack({ target: input } as unknown as Event);
    expect(api.promotePack).not.toHaveBeenCalled();expect(api.installLibraryPack).not.toHaveBeenCalled();expect(api.publishLibraryPack).not.toHaveBeenCalled();f.destroy();
  });

  it('copies shared profiles and required packages without selecting either', () => {
    const f = create(); const c = f.componentInstance;
    const definition: SourceProfileDefinition = { contract: 'synthesis-source-profile-v1', name: 'Gemeinsame Quellen', aspects: { 'opportunities.work': { candidates: [{ provider: 'pack:version:work', excluded: false, onUnavailable: 'fail' }], fallback: 'none' } } };
    const profile = { id: 'shared-profile', definition }; const pack = { id: 'version', packId: 'regional', title: 'Paket', edition: '2026', sources: [] };
    api.librarySourceProfiles.mockReturnValue(of([{ id: profile.id, status: 'available', issue: '', profile, requiredPacks: [pack] }]));
    api.promoteSourceProfile.mockReturnValue(of(profile)); const before = structuredClone(c.scenario.dataPlan);c.promoteSourceProfile(profile.id);
    expect(c.libraryHasProfile(profile.id)).toBe(true);expect(c.scenario.dataPlan).toEqual(before);
    api.installLibrarySourceProfile.mockReturnValue(of(profile));api.sourceProfiles.mockReturnValue(of([profile]));api.packs.mockReturnValue(of([pack]));c.installLibraryProfile(profile.id);
    expect(api.installLibrarySourceProfile).toHaveBeenCalledWith('s', profile.id);expect(c.sourceProfiles).toEqual([profile]);expect(c.packs).toEqual([pack]);expect(c.scenario.dataPlan).toEqual(before);
    c.tab = 'inventory';f.detectChanges();const buttons = [...f.nativeElement.querySelectorAll('button')] as HTMLButtonElement[];
    expect(buttons.find(b => b.textContent?.includes('Quellenprofil in diesem Szenario vorhanden'))?.disabled).toBe(true);f.destroy();
  });
  it('imports JSON recipes with bounded input and exposes parse errors without writes', async () => {
    const f = create();const c = f.componentInstance;const definition: SourceProfileDefinition = { contract: 'synthesis-source-profile-v1', name: 'Native Quellen', aspects: { 'opportunities.work': { candidates: [{ provider: 'builtin:synthetic', excluded: false, onUnavailable: 'fail' }], fallback: 'none' } } };
    api.publishLibrarySourceProfile.mockReturnValue(of({ id: 'shared', definition }));
    const input = { files: [{ size: 100, text: () => Promise.resolve(JSON.stringify(definition)) }], value: 'profile.json' };
    c.importLibraryProfile({ target: input } as unknown as Event);await new Promise(r => setTimeout(r, 0));
    expect(input.value).toBe('');expect(api.publishLibrarySourceProfile).toHaveBeenCalledWith(definition);expect(c.scenario.dataPlan?.enabled).toBe(false);
    api.publishLibrarySourceProfile.mockClear();input.files[0].text = () => Promise.resolve('invalid');c.importLibraryProfile({ target: input } as unknown as Event);await new Promise(r => setTimeout(r, 0));expect(c.busy).toBe(false);expect(c.error).not.toBe('');expect(api.publishLibrarySourceProfile).not.toHaveBeenCalled();
    input.files[0].size = 256 * 1024 + 1;c.importLibraryProfile({ target: input } as unknown as Event);expect(c.error).toContain('256 KB');expect(api.publishLibrarySourceProfile).not.toHaveBeenCalled();f.destroy();
  });
  it('isolates profile catalog failures and blocks invalid or read-only profile transfer', () => {
    const f = create();const c = f.componentInstance;api.librarySourceProfiles.mockReturnValue(of([{ id: 'broken', status: 'invalid', issue: 'Benötigtes Paket fehlt', requiredPacks: [] }]));c.reload();c.tab = 'inventory';f.detectChanges();expect(f.nativeElement.textContent).toContain('Benötigtes Paket fehlt');
    const button = [...f.nativeElement.querySelectorAll('button')].find((b: HTMLButtonElement) => b.textContent?.includes('Profil und Daten übernehmen')) as HTMLButtonElement;expect(button.disabled).toBe(true);
    api.librarySourceProfiles.mockReturnValue(throwError(() => ({ error: { message: 'Profilbibliothek offline' } })));api.revisions.mockReturnValue(of([revision]));c.reload();expect(c.revisions).toEqual([revision]);expect(c.libraryProfilesError).toBe('Profilbibliothek offline');expect(c.busy).toBe(false);
    f.componentRef.setInput('disabled', true);f.detectChanges();c.promoteSourceProfile('profile');c.installLibraryProfile('profile');expect(api.promoteSourceProfile).not.toHaveBeenCalled();expect(api.installLibrarySourceProfile).not.toHaveBeenCalled();f.destroy();
  });

  it('makes heavy-traffic exclusion independent of company and parcel contributions explicit', () => {
    const f = create();const c = f.componentInstance;c.selected = 'freight.vehicleOd';c.scenario.dataPlan!.enabled = true;c.policy().source = 'excluded';f.detectChanges();
    expect(f.nativeElement.textContent).toContain('nur den Schwerlasthintergrund');expect(f.nativeElement.textContent).toContain('Unternehmenstouren und Paketlogistik bleiben separat');
    expect(c.policy('population.distribution').source).toBe('legacy');expect(c.policy('opportunities.work').source).toBe('legacy');f.destroy();
  });

});
