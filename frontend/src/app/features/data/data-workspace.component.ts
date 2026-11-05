import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, OnChanges, OnDestroy, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin, Subscription, catchError, of, from, switchMap } from 'rxjs';
import { Scenario } from '../../core/models/scenario.model';
import { DataImport, DataPack, DataLibraryEntry, SourceProfileLibraryEntry, SourceProfileDefinition, SourceProfile, SourceChain, CandidateDecision, DataProfile, DatasetRevision, emptySourcePolicy, ImportMapping, ImportMetadata, ImportPreview, ImportSchema, SourcePlanCheck, SourcePolicy } from '../../core/models/data-source.model';
import { DataSourceService } from '../../core/services/data-source.service';
import { MappingEditorComponent } from '../../shared/mapping-editor.component';
import { SourceRunReportComponent } from './source-run-report.component';

@Component({
  selector: 'app-data-workspace',
  imports: [CommonModule, FormsModule, MappingEditorComponent, SourceRunReportComponent],
  templateUrl: './data-workspace.component.html',
  styleUrl: './data-workspace.component.css'
})
export class DataWorkspaceComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) scenario!: Scenario;
  @Input() disabled = false;
  @Input() runVersion = '';
  @Output() changed = new EventEmitter<void>();
  @Output() savePlan = new EventEmitter<void>();
  @Output() pending = new EventEmitter<boolean>();
  tab: 'aspects' | 'inventory' | 'review' | 'import' = 'aspects';
  schemas: ImportSchema[] = []; imports: DataImport[] = []; revisions: DatasetRevision[] = []; profiles: DataProfile[] = [];
  library: DataLibraryEntry[] = []; libraryError = '';
  libraryProfiles: SourceProfileLibraryEntry[] = []; libraryProfilesError = '';
  packs: DataPack[] = []; sourceProfiles: SourceProfile[] = [];
  private sourceDrafts: Record<string, SourceChain & { name: string }> = {};
  private dirtySourceDrafts = new Set<string>();
  candidateToAdd = '';
  selected = 'opportunities.work'; importSchemaId = 'workplaces-v1';
  current: DataImport | null = null; mapping: ImportMapping | null = null; metadata: ImportMetadata | null = null;
  preview: ImportPreview | null = null; check: SourcePlanCheck | null = null;
  busy = false; error = ''; notice = ''; importDirty = false; coverageConfirmed = false; profileName = ''; selectedProfile = '';
  private loadedId = ''; private requests = new Subscription();
  readonly counts: { key: 'internalTrips' | 'inboundTrips' | 'outboundTrips' | 'throughTrips'; label: string }[] = [
    { key: 'internalTrips', label: 'Binnenfahrten' }, { key: 'inboundTrips', label: 'Eingehende Fahrten' },
    { key: 'outboundTrips', label: 'Ausgehende Fahrten' }, { key: 'throughTrips', label: 'Durchgangsfahrten' }
  ];
  readonly populationProviders = [
    { id: 'upload-grid', label: 'Hochgeladenes Bevölkerungsraster', detail: 'Datei im bisherigen Rasterimport; Gesamtzahl und Verteilung aus Rasterwerten.' },
    { id: 'census-2022', label: 'Destatis Zensus 2022', detail: 'Versionierter Deutschlandbestand; Abruf und regionale Verwendbarkeit werden im Lauf geprüft.' },
    { id: 'osm-places', label: 'OSM-Siedlungsdaten', detail: 'Modellierte Gesamtschätzung und geglättete räumliche Verteilung aus Siedlungen.' },
    { id: 'poi-density', label: 'POI-basierte Ersatzverteilung', detail: 'Gesamtzahl aus Dichteannahme; Verteilung aus POI-Anzahl und Zufallsgewichten.' },
    { id: 'uniform', label: 'Ersatz ohne Ortsdaten', detail: 'Gesamtzahl aus Dichteannahme; räumliche Verteilung mit Zufallsgewichten.' }
  ];
  readonly workplaceProviders = [
    { id: 'osm-pois', label: 'OSM-Arbeitsgewichte', detail: 'Arbeitsgewichte aus vorhandenen OSM-Ortsmerkmalen; keine gezählten Arbeitsplätze.' },
    { id: 'synthetic', label: 'Synthetische Arbeitsgewichte', detail: 'Modellannahme aus Zonenfläche und zentraler Lage; eigene reproduzierbare Zufallsfolge.' }
  ];
  workplaceProviderLabel(id: string): string { return this.workplaceProviders.find(p => p.id === id)?.label ?? id; }
  workplaceProviderDetail(id: string): string { return this.workplaceProviders.find(p => p.id === id)?.detail ?? ''; }
  populationProviderLabel(id: string): string { return this.populationProviders.find(p => p.id === id)?.label ?? id; }
  populationProviderDetail(id: string): string { return this.populationProviders.find(p => p.id === id)?.detail ?? ''; }
  movePopulationProvider(index: number, delta: number): void {
    const providers = this.policy().providers; const next = index + delta;
    if (!providers || next < 0 || next >= providers.length) return;
    [providers[index], providers[next]] = [providers[next], providers[index]]; this.planChanged();
  }
  private readonly api = inject(DataSourceService);
  ngOnChanges(): void {
    this.scenario.dataPlan ??= { enabled: false, aspects: {} };
    this.scenario.dataPlan.qualityMode ??= 'exploration';
    for (const aspect of ['population.distribution', 'opportunities.work', 'freight.vehicleOd']) this.scenario.dataPlan.aspects[aspect] ??= emptySourcePolicy();
    if (this.scenario.id && this.loadedId !== this.scenario.id) {
      this.requests.unsubscribe(); this.requests = new Subscription(); this.current = null; this.mapping = null; this.metadata = null; this.preview = null; this.check = null; this.sourceDrafts = {}; this.dirtySourceDrafts.clear(); this.candidateToAdd = ''; this.dirty(false);
      this.loadedId = this.scenario.id; this.reload();
    }
  }
  ngOnDestroy(): void { this.requests.unsubscribe(); this.pending.emit(false); }
  reload(): void {
    if (!this.scenario.id) return;
    this.busy = true; this.error = ''; this.libraryError = ''; this.libraryProfilesError = '';
    this.requests.add(forkJoin({ schemas: this.api.schemas(this.scenario.id), imports: this.api.imports(this.scenario.id), revisions: this.api.revisions(this.scenario.id), profiles: this.api.profiles(this.scenario.id), packs: this.api.packs(this.scenario.id), sourceProfiles: this.api.sourceProfiles(this.scenario.id), library: this.api.libraryPacks().pipe(catchError(e => { this.libraryError = e.error?.message ?? 'Datenbibliothek ist derzeit nicht erreichbar.'; return of([] as DataLibraryEntry[]); })), libraryProfiles: this.api.librarySourceProfiles().pipe(catchError(e => { this.libraryProfilesError = e.error?.message ?? 'Quellenprofilbibliothek ist derzeit nicht erreichbar.'; return of([] as SourceProfileLibraryEntry[]); })) }).subscribe({
      next: v => { this.schemas = v.schemas; this.imports = v.imports; this.revisions = v.revisions; this.profiles = v.profiles; this.packs = v.packs; this.sourceProfiles = v.sourceProfiles; this.library = v.library; this.libraryProfiles = v.libraryProfiles; this.busy = false; }, error: e => this.failed(e)
    }));
  }
  policy(aspect = this.selected): SourcePolicy { return this.scenario.dataPlan!.aspects[aspect]; }
  get schema(): ImportSchema | undefined { return this.schemas.find(s => s.id === (this.current?.draft.schemaId ?? this.importSchemaId)); }
  get fields(): string[][] { return this.schema?.fields.map(f => [f.id, f.label + (f.required ? ' *' : '')]) ?? []; }
  get table() { return this.current?.draft.source.tables.find(t => t.id === this.mapping?.tableId); }
  get columns(): { index: number; header: string }[] { return this.table?.rows.find(r => r.sourceRow === this.mapping?.headerRow)?.cells.map((header, index) => ({ header, index })) ?? []; }
  get matchingRevisions(): DatasetRevision[] { const ids = this.schemas.filter(s => s.aspect === this.selected).map(s => s.id); return this.revisions.filter(r => ids.includes(r.schemaId)); }
  get matchingProfiles(): DataProfile[] { return this.profiles.filter(p => p.schemaId === this.current?.draft.schemaId && p.format === this.current?.draft.source.format); }
  title(aspect: string): string { return ({ 'opportunities.work': 'Arbeitsplätze', 'population.distribution': 'Bevölkerungsverteilung', 'freight.vehicleOd': 'Schwerlastbeziehungen' } as Record<string, string>)[aspect] ?? this.schemas.find(s => s.aspect === aspect)?.label ?? aspect; }
  get aspects(): { aspect: string; label: string }[] { return [...new Set(this.schemas.map(s => s.aspect))].filter(a => !a.startsWith('support.')).map(aspect => ({ aspect, label: this.title(aspect) })); }
  get allocationRevisions(): DatasetRevision[] { return this.revisions.filter(r => r.schemaId === (this.metadata?.geography?.allocationMethod === 'uniform-area-v1' ? 'municipality-areas-v1' : 'municipality-allocation-v1')); }
  allocationMethodChanged(): void { if (this.metadata?.geography) delete this.metadata.geography.allocationRevision; this.mappingChanged(); }
  allocationLabel(method: string): string { return method === 'uniform-area-v1' ? 'Flächengleichverteilung' : 'Explizite Punktgewichte'; }
  formatCell(value: unknown): string { return value !== null && typeof value === 'object' ? JSON.stringify(value) : String(value ?? ''); }
  isScenarioRevision(revision: DatasetRevision): boolean { return !this.schemas.find(s => s.id === revision.schemaId)?.aspect.startsWith('support.'); }
  sourceLabel(value: string): string { return ({ legacy: 'Bisheriger Datenpfad', builtins: 'Einzeln konfigurierte Bestandsquellen', dataset: 'Gewählte Datenrevision', profile: 'Geordnetes Quellenprofil', model: 'Technische Mengendemonstration', excluded: 'Ausgeschlossen' } as Record<string, string>)[value] ?? value; }
  statusLabel(value: string, source?: string): string { if (value === 'resolved-with-fallback' && source === 'dataset') return 'Ersatzquelle ausgewählt'; return ({ resolved: 'Eingabe verwendbar', 'pending-resolution': 'Reihenfolge geprüft; Quellenauflösung im Lauf', 'resolved-with-fallback': 'Mit Ersatzannahmen', legacy: 'Bestehende Quellenwahl', 'insufficient-input': 'Eingabe unzureichend', disabled: 'Bewusst ausgeschlossen' } as Record<string, string>)[value] ?? value; }
  evidenceLabel(value: string): string { return ({ 'user-provided-unverified': 'Nutzerquelle; empirisch nicht verifiziert', assumed: 'Modellannahme', 'not-assessed': 'Evidenz noch nicht bewertet' } as Record<string, string>)[value] ?? value; }
  status(aspect: string): string {
    if (!this.scenario.dataPlan?.enabled) return 'Bisherige Quellenwahl';
    const p = this.policy(aspect);
    if (p.source === 'excluded') return 'Bewusst ausgeschlossen';
    if (p.source === 'builtins') return p.providers?.some(c => !c.excluded) ? 'Eigene Quellenreihenfolge' : aspect === 'population.distribution' ? 'Keine Bevölkerungsquelle zugelassen' : 'Keine Arbeitsplatzquelle zugelassen';
    if (p.source === 'model') {
      if (this.scenario.dataPlan?.qualityMode === 'analysis') return 'Für fachliche Auswertung gesperrt';
      if (this.counts.reduce((n, c) => n + Number(p[c.key]), 0) <= 0) return 'Demonstrationsmengen fehlen';
      if (!p.demonstrationAccepted) return 'Explorationsfreigabe fehlt';
      return (p.assumptionStatement?.trim().length ?? 0) < 10 ? 'Annahmenbeschreibung fehlt' : 'Explorationsdemonstration konfiguriert';
    }
    if (p.source === 'profile') return this.sourceProfiles.find(v => v.id === p.profile)?.definition.name ?? 'Quellenprofil fehlt';
    if (p.source === 'dataset') return this.revisions.find(r => r.id === p.revision)?.label ?? 'Datenrevision fehlt';
    return 'Bisherige Quellenkette';
  }
  planChanged(): void { this.check = null; this.notice = ''; this.changed.emit(); }
  modeChanged(): void { if (this.policy().source !== 'dataset') delete this.policy().revision; if (this.policy().source !== 'profile') delete this.policy().profile; if (this.policy().source === 'builtins' && !this.policy().providers?.length) this.policy().providers = (this.selected === 'population.distribution' ? this.populationProviders : this.workplaceProviders).map(p => ({ provider: p.id, excluded: false, onUnavailable: 'next' })); if (!['dataset', 'profile'].includes(this.policy().source) && this.policy().gaps === 'providers') this.policy().gaps = 'legacy'; this.planChanged(); }
  private selectedSchemas(): string[] {
    const policy = this.policy(); if (policy.source === 'dataset') return this.revisions.filter(r => r.id === policy.revision).map(r => r.schemaId);
    if (policy.source !== 'profile') return [];
    return this.sourceProfiles.find(p => p.id === policy.profile)?.definition.aspects[this.selected]?.candidates.flatMap(c => {
      const parts = c.provider.split(':'); if (parts[0] === 'revision') return this.revisions.filter(r => r.id === parts[1]).map(r => r.schemaId);
      return parts[0] === 'pack' ? this.packs.find(p => p.id === parts[1])?.sources.filter(r => r.id === parts[2]).map(r => r.schema) ?? [] : [];
    }) ?? [];
  }
  get maskedPopulationSelected(): boolean { return this.selected === 'population.distribution' && this.selectedSchemas().includes('population-cells-v1'); }
  get explicitWorkGaps(): boolean { return this.mixedProfileSelected || this.selectedSchemas().includes('workplace-cells-v1'); }
  get gapProviderChoices(): { id: string; label: string; detail: string }[] { return this.selected === 'population.distribution' ? this.populationProviders.filter(p => ['osm-places', 'poi-density', 'uniform'].includes(p.id)) : this.workplaceProviders; }
  get regionalProfileSelected(): boolean { return this.sourceProfiles.find(p => p.id === this.policy().profile)?.definition.aspects[this.selected]?.strategy === 'fill-gaps'; }
  get mixedProfileSelected(): boolean { const chain = this.sourceProfiles.find(p => p.id === this.policy().profile)?.definition.aspects[this.selected]; return chain?.strategy === 'fill-gaps' || !!chain?.candidates.some(c => c.provider.startsWith('builtin:')); }
  profileChanged(): void { if (this.selected === 'opportunities.work' && this.explicitWorkGaps && this.policy().gaps === 'legacy') this.policy().gaps = 'none'; if (this.maskedPopulationSelected && this.policy().gaps === 'legacy') this.policy().gaps = 'none'; this.planChanged(); }
  gapModeChanged(): void { if (this.policy().gaps === 'providers' && !this.policy().gapProviders?.length) this.policy().gapProviders = this.gapProviderChoices.map(p => ({ provider: p.id, excluded: false, onUnavailable: 'next' })); this.planChanged(); }
  moveGapProvider(index: number, delta: number): void { const list = this.policy().gapProviders; const next = index + delta; if (!list || next < 0 || next >= list.length) return; [list[index], list[next]] = [list[next], list[index]]; this.planChanged(); }
  beginImport(schemaId?: string): void {
    this.importSchemaId = schemaId ?? (this.selected === 'opportunities.work' ? 'workplaces-v1' : this.selected === 'population.distribution' ? 'population-grid-v1' : this.schemas.find(s => s.aspect === this.selected)?.id) ?? 'workplaces-v1';
    this.current = null; this.mapping = null; this.metadata = null; this.preview = null; this.coverageConfirmed = false; this.error = ''; this.notice = ''; this.tab = 'import'; this.dirty(false);
  }
  selectImport(value: DataImport): void {
    this.current = value; this.importSchemaId = value.draft.schemaId; this.mapping = structuredClone(value.mapping); this.tab = 'import';
    this.metadata = { label: value.draft.filename, source: '', referenceDate: '', unit: this.schema?.unit ?? '', coverage: structuredClone(this.scenario.region!), licence: 'unknown', attribution: '' };
    if (value.draft.schemaId === 'workplaces-v1') this.metadata.coverageDeclaration = { kind: 'point-register', completeness: 'unknown', statement: '' };
    if (['municipal-workplaces-v1', 'municipality-allocation-v1', 'municipality-areas-v1'].includes(value.draft.schemaId)) this.metadata.geography = { codeSystem: 'de:AGS', edition: '', allocationMethod: 'explicit-point-weights-v1' };
    if (value.draft.schemaId === 'municipality-areas-v1') this.metadata.geography!.allocationMethod = 'uniform-area-v1';
    if (value.draft.schemaId === 'municipal-workplaces-v1') this.metadata.coverageDeclaration = { kind: 'municipality-aggregate', completeness: 'unknown', statement: '' };
    this.preview = null; this.coverageConfirmed = false; this.error = ''; this.dirty(false);
  }
  upload(event: Event): void {
    const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = '';
    if (!file || !this.scenario.id || this.busy || this.disabled) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.upload(this.scenario.id, this.importSchemaId, file).subscribe({ next: value => {
      this.imports = [value, ...this.imports.filter(i => i.draft.id !== value.draft.id)]; this.selectImport(value); this.busy = false;
    }, error: e => this.failed(e) }));
  }
  tableChanged(): void { if (this.mapping) { this.mapping.headerRow = this.table?.rows[0]?.sourceRow ?? 1; this.mapping.columns = {}; this.mappingChanged(); } }
  mappingChanged(): void { this.preview = null; this.error = ''; this.dirty(true); }
  private dirty(value: boolean): void { this.importDirty = value; this.pending.emit(value || this.dirtySourceDrafts.size > 0); }
  runPreview(): void {
    if (!this.current || !this.mapping || !this.metadata || !this.scenario.id || this.busy) return;
    if (!this.coverageConfirmed) { this.error = 'Abdeckungsgebiet und Mengeneinheit zuerst fachlich bestätigen.'; return; }
    if (!this.workplaceDeclarationReady()) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.preview(this.scenario.id, this.current.draft.id, this.mapping, this.metadata).subscribe({ next: result => { this.preview = result; this.busy = false; }, error: e => this.failed(e) }));
  }
  publish(): void {
    if (!this.current || !this.mapping || !this.metadata || !this.scenario.id || this.preview?.status !== 'valid' || this.busy || this.disabled) return;
    if (!this.workplaceDeclarationReady()) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.publish(this.scenario.id, this.current.draft.id, this.mapping, this.metadata).subscribe({ next: result => {
      this.revisions = [result, ...this.revisions.filter(r => r.id !== result.id)]; this.busy = false; this.dirty(false); this.tab = 'inventory'; this.notice = 'Geprüfte Revision gespeichert. Sie ist noch keinem Beitrag zugewiesen.';
    }, error: e => this.failed(e) }));
  }
  useRevision(revision: DatasetRevision): void {
    const schema = this.schemas.find(s => s.id === revision.schemaId); if (!schema || schema.aspect.startsWith('support.')) return;
    this.selected = schema.aspect; this.scenario.dataPlan!.enabled = true; const p = this.policy(); p.source = 'dataset'; p.revision = revision.id; if (['workplace-cells-v1', 'population-cells-v1'].includes(revision.schemaId) && p.gaps === 'legacy') p.gaps = 'none'; this.tab = 'aspects'; this.planChanged();
  }
  saveProfile(): void {
    if (!this.scenario.id || !this.current || !this.mapping || !this.metadata || !this.profileName.trim() || this.preview?.status !== 'valid') return;
    this.busy = true;
    this.requests.add(this.api.saveProfile(this.scenario.id, this.current.draft.id, this.profileName, this.mapping, this.metadata).subscribe({ next: p => { this.profiles = [p, ...this.profiles.filter(v => v.id !== p.id)]; this.notice = 'Zuordnungsprofil gespeichert; keine Quellwerte übernommen.'; this.busy = false; }, error: e => this.failed(e) }));
  }
  applyProfile(): void {
    if (!this.scenario.id || !this.current || !this.selectedProfile) return; this.busy = true;
    this.requests.add(this.api.applyProfile(this.scenario.id, this.current.draft.id, this.selectedProfile).subscribe({ next: m => { this.mapping = m; this.busy = false; this.mappingChanged(); }, error: e => this.failed(e) }));
  }
  checkPlan(): void {
    if (!this.scenario.id || this.busy) return; this.tab = 'review'; this.busy = true; this.error = '';
    this.requests.add(this.api.check(this.scenario.id, this.scenario).subscribe({ next: c => { this.check = c; this.busy = false; }, error: e => this.failed(e) }));
  }
  get hasPendingEdits(): boolean { return this.importDirty || this.dirtySourceDrafts.size > 0; }
  get matchingSourceProfiles(): SourceProfile[] { return this.sourceProfiles.filter(p => !!p.definition.aspects[this.selected]); }
  sourceDraft(): SourceChain & { name: string } { return this.sourceDrafts[this.selected] ??= { name: '', candidates: [], fallback: 'none', strategy: 'choose' }; }
  get providerChoices(): { provider: string; label: string }[] {
    const schemas = this.schemas.filter(s => s.aspect === this.selected).map(s => s.id);
    return [
      ...(this.sourceDraftRegional ? [] : this.selected === 'population.distribution' ? this.populationProviders : this.selected === 'opportunities.work' ? this.workplaceProviders : []).map(p => ({ provider: `builtin:${p.id}`, label: p.label })),
      ...this.packs.flatMap(p => p.sources.filter(s => s.aspect === this.selected).map(s => ({ provider: `pack:${p.id}:${s.id}`, label: `${p.title} · ${p.edition} · ${s.label} · ${p.id.slice(0, 8)}` }))),
      ...this.revisions.filter(r => schemas.includes(r.schemaId)).map(r => ({ provider: `revision:${r.id}`, label: `${r.label} · ${r.referenceDate} · ${r.id.slice(0, 8)}` }))
    ];
  }
  providerLabel(provider: string): string { return this.providerChoices.find(v => v.provider === provider)?.label ?? this.workplaceProviderLabel(this.populationProviderLabel(provider.replace(/^builtin:/, ''))); }
  candidateStatus(status: string): string { return ({ staged: 'Eingabe geprüft und gebunden; Auswahl folgt im Lauf', pending: 'Erst im Lauf prüfbar', selected: 'Ausgewählt', excluded: 'Abgewählt', 'not-applicable': 'Nicht passend', unavailable: 'Nicht verfügbar', invalid: 'Ungültig', 'not-evaluated': 'Nicht aufgelöst' } as Record<string, string>)[status] ?? status; }
  candidateReason(value: CandidateDecision): string {
    return ({ 'revision-and-references-frozen': 'Revision und benötigte Referenzen für den Lauf festgehalten', 'earlier-retained-revision-is-terminal': 'Eine frühere gebundene Revision beendet diesen Auswahlpfad', 'requires-runtime-inputs': 'Benötigt die räumlichen Daten des Laufs', 'explicitly-excluded': 'Ausdrücklich ausgeschlossen', 'first-applicable-candidate': 'Erster passender Kandidat', 'higher-priority-candidate-selected': 'Eine vorrangige Quelle wurde ausgewählt', 'retained-source-missing': 'Gespeicherte Quelldatei fehlt' } as Record<string, string>)[value.reason] ?? value.reason;
  }
  sourceDraftChanged(): void { this.dirtySourceDrafts.add(this.selected); this.dirty(this.importDirty); }
  get sourceDraftRegional(): boolean { return this.sourceDraft().strategy === 'fill-gaps'; }
  strategyChanged(): void { if (this.sourceDraftRegional) this.sourceDraft().fallback = 'none'; this.candidateToAdd = ''; this.sourceDraftChanged(); }
  get sourceDraftHasNatives(): boolean { return this.sourceDraft().candidates.some(c => c.provider.startsWith('builtin:')); }
  addCandidate(provider = this.candidateToAdd): void {
    if (!provider || this.sourceDraft().candidates.some(c => c.provider === provider)) return;
    this.sourceDraft().candidates.push({ provider, excluded: false, onUnavailable: 'fail' }); if (provider.startsWith('builtin:')) this.sourceDraft().fallback = 'none'; this.candidateToAdd = ''; this.sourceDraftChanged();
  }
  moveCandidate(index: number, delta: number): void {
    const candidates = this.sourceDraft().candidates; const next = index + delta;
    if (next < 0 || next >= candidates.length) return;
    [candidates[index], candidates[next]] = [candidates[next], candidates[index]]; this.sourceDraftChanged();
  }
  removeCandidate(index: number): void { this.sourceDraft().candidates.splice(index, 1); this.sourceDraftChanged(); }
  saveSourceDraft(): void {
    const draft = this.sourceDraft(); if (!this.scenario.id || this.busy || this.disabled || !draft.name.trim() || !draft.candidates.length || (this.sourceDraftRegional && this.sourceDraftHasNatives)) return;
    const aspect = this.selected; this.busy = true; this.error = '';
    this.requests.add(this.api.saveSourceProfile(this.scenario.id, { contract: 'synthesis-source-profile-v1', name: draft.name, aspects: { [aspect]: structuredClone({ candidates: draft.candidates, fallback: draft.fallback, ...(draft.strategy === 'fill-gaps' ? { strategy: 'fill-gaps' as const } : {}) }) } }).subscribe({
      next: profile => { this.sourceProfiles = [profile, ...this.sourceProfiles.filter(p => p.id !== profile.id)]; this.dirtySourceDrafts.delete(aspect); this.dirty(this.importDirty); this.busy = false; this.notice = 'Quellenprofil gespeichert. Es ist noch keinem Beitrag zugewiesen.'; }, error: e => this.failed(e)
    }));
  }
  installPack(event: Event): void {
    const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = '';
    if (!file || !this.scenario.id || this.busy || this.disabled) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.installPack(this.scenario.id, file).subscribe({ next: () => { this.notice = 'Datenpaket installiert. Bestehende Quellenwahl bleibt erhalten.'; this.reload(); }, error: e => this.failed(e) }));
  }
  libraryHas(id: string): boolean { return this.library.some(e => e.id === id && e.status === 'available'); }
  scenarioHasPack(id: string): boolean { return this.packs.some(p => p.id === id); }
  publishLibraryPack(event: Event): void {
    const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = '';
    if (!file || this.busy || this.disabled) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.publishLibraryPack(file).subscribe({ next: () => { this.notice = 'Datenpaket in Bibliothek gespeichert. Kein Szenario wurde geändert.'; this.reload(); }, error: e => this.failed(e) }));
  }
  promotePack(id: string): void {
    if (!this.scenario.id || this.busy || this.disabled) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.promotePack(this.scenario.id, id).subscribe({ next: () => { this.notice = 'Konkrete Paketversion in Bibliothek gespeichert. Quellenwahl bleibt erhalten.'; this.reload(); }, error: e => this.failed(e) }));
  }
  installLibraryPack(id: string): void {
    if (!this.scenario.id || this.busy || this.disabled) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.installLibraryPack(this.scenario.id, id).subscribe({ next: () => { this.notice = 'Paketversion in dieses Szenario übernommen. Jetzt ein Quellenprofil erstellen oder die Revision für einen Beitrag auswählen.'; this.reload(); }, error: e => this.failed(e) }));
  }
  libraryHasProfile(id: string): boolean { return this.libraryProfiles.some(e => e.id === id && e.status === 'available'); }
  scenarioHasProfile(id: string): boolean { return this.sourceProfiles.some(p => p.id === id); }
  importLibraryProfile(event: Event): void {
    const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = '';
    if (!file || this.busy || this.disabled) return;
    if (file.size > 256 * 1024) { this.error = 'Quellenprofil als JSON erforderlich, höchstens 256 KB.'; return; }
    this.busy = true; this.error = '';
    this.requests.add(from(file.text()).pipe(switchMap(raw => this.api.publishLibrarySourceProfile(JSON.parse(raw) as SourceProfileDefinition))).subscribe({ next: () => { this.notice = 'Quellenprofil in Bibliothek gespeichert. Kein Szenario wurde geändert.'; this.reload(); }, error: e => this.failed(e) }));
  }
  promoteSourceProfile(id: string): void {
    if (!this.scenario.id || this.busy || this.disabled) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.promoteSourceProfile(this.scenario.id, id).subscribe({ next: () => { this.notice = 'Quellenprofil in Bibliothek bereitgestellt. Quellenwahl bleibt erhalten.'; this.reload(); }, error: e => this.failed(e) }));
  }
  installLibraryProfile(id: string): void {
    if (!this.scenario.id || this.busy || this.disabled) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.installLibrarySourceProfile(this.scenario.id, id).subscribe({ next: () => { this.notice = 'Quellenprofil und benötigte Daten übernommen. Profil jetzt ausdrücklich für die gewünschten Beiträge auswählen.'; this.reload(); }, error: e => this.failed(e) }));
  }
  private workplaceDeclarationReady(): boolean {
    if (!['workplaces-v1', 'municipal-workplaces-v1'].includes(this.current?.draft.schemaId ?? '')) return true;
    const declaration = this.metadata?.coverageDeclaration;
    if (declaration?.kind !== (this.current?.draft.schemaId === 'municipal-workplaces-v1' ? 'municipality-aggregate' : 'point-register')) {
      this.error = 'Datenform passt nicht zum gewählten Fachvertrag. Gemeindeaggregate benötigen den Gemeindeimport mit Gemeindeschlüssel und Gebietsstand.'; return false;
    }
    if (declaration.completeness !== 'complete' || declaration.statement.trim().length < 10) {
      this.error = this.current?.draft.schemaId === 'municipal-workplaces-v1' ? 'Vollständige Gemeindeaggregate für die enthaltenen Schlüssel angeben und den Erhebungsumfang beschreiben.' : 'Vollständiges Punktregister ausdrücklich angeben und seinen Erhebungsumfang beschreiben. Fehlende Punkte sind sonst nicht null.'; return false;
    }
    return true;
  }
  failed(e: { error?: { message?: string }; message?: string }): void { this.busy = false; this.error = e.error?.message ?? e.message ?? 'Datenanfrage fehlgeschlagen'; }
}
