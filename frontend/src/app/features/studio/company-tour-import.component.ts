import { MappingEditorComponent } from '../../shared/mapping-editor.component';
import { hasTourPoint, tourContextQuestions } from './company-tour-questions';
import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, OnChanges, OnDestroy, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { CompanyTourImport, TourMapping, TourTableProfile, CompanyTourContext, CompanyTourPoint, TourImportProfile, CompanyTourGeocodingLookup } from '../../core/models/company-tour.model';

@Component({
  selector: 'app-company-tour-import',
  imports: [CommonModule, FormsModule, MappingEditorComponent],
  templateUrl: './company-tour-import.component.html',
  styles: [`:host { display:block;overflow-wrap:anywhere; } .fields { display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:.5rem; }
    label { display:flex;flex-direction:column;gap:.2rem;min-width:0; } select,input { max-width:100%;box-sizing:border-box; }
    select,input[type=number] { padding:.4rem;border:1px solid #cbd5e1;border-radius:5px;font:inherit;font-size:.85rem; }
    button { padding:.4rem .6rem;border:1px solid #cbd5e1;border-radius:5px;background:#edf3f8;color:#18354e;font:inherit;font-size:.85rem;cursor:pointer; }
    button:disabled { opacity:.5;cursor:default; } summary { cursor:pointer;font-size:.85rem; }
    .actions { display:flex;gap:.5rem;flex-wrap:wrap;margin:.7rem 0; }
    .hint { font-size:.85rem;color:#52616b; } .error { color:#b42318; } ul {padding-left:1.2rem;}
    table {width:100%;font-size:.85rem;} td,th {text-align:left;padding:.3rem;} `]
})
export class CompanyTourImportComponent implements OnChanges, OnDestroy {
  @Input() scenarioId = '';
  @Output() selectionChange = new EventEmitter<{ enabled: boolean; imports: { importId: string; executionHash: string }[] }>();
  @Output() pendingChange = new EventEmitter<boolean>();
  imports: CompanyTourImport[] = [];
  current: CompanyTourImport | null = null;
  mapping: TourMapping | null = null;
  busy = false;
  dirty = false;
  error = '';
  notice = '';
  previewLimit = 10;
  context: CompanyTourContext | null = null;
  contextDirty = false;
  sourceReviewed = false;
  analysisMode = 'auto';
  profileName = '';
  selectedProfile = '';
  profiles: TourImportProfile[] = [];
  commonDepotAddress = '';
  commonDepot: CompanyTourPoint = { lon: null, lat: null, provenance: 'manual-confirmed' };
  locationCandidates: Partial<Record<string, CompanyTourPoint[]>> = {};
  locationNotice = '';
  geocoding: CompanyTourGeocodingLookup | null = null;
  private searchedAddresses = new Set<string>();
  private refreshGeocoding = false;
  private pendingSavedSelection = false;
  private requests = new Subscription();
  readonly fields = [
    ['tour', 'Tourkennung *'], ['date', 'Liefertag *'], ['transaction', 'Vorgang / eindeutiger Datensatz *'],
    ['street', 'Straße und Hausnummer *'], ['postalCode', 'Postleitzahl *'], ['city', 'Ort *'],
    ['arrival', 'Geplante Ankunft *'], ['departure', 'Geplante Abfahrt *'], ['order', 'Auftragsnummer'],
    ['windowStart', 'Kundenfenster Beginn'], ['windowEnd', 'Kundenfenster Ende'],
    ['orderType', 'Auftragsart (Rohcode)'], ['workValue', 'Arbeitswert (Einheit ungeklärt)']
  ];
  constructor(public api: ApiService) {}
  ngOnChanges(): void {
    this.requests.unsubscribe(); this.requests = new Subscription();
    this.commonDepotAddress = ''; this.commonDepot = this.point();
    this.current = null; this.mapping = null; this.imports = []; this.busy = false; this.error = ''; this.notice = '';
    this.geocoding = null; this.searchedAddresses.clear(); this.refreshGeocoding = false; this.context = null; this.locationCandidates = {}; this.locationNotice = ''; this.pendingChange.emit(false); this.profiles = []; this.selectedProfile = '';
    if (this.scenarioId) this.reload();
  }
  ngOnDestroy(): void { this.requests.unsubscribe(); this.pendingChange.emit(false); }
  reload(): void {
    if (this.busy || !this.scenarioId) return;
    this.busy = true;
    this.requests.add(this.api.listCompanyTours(this.scenarioId).subscribe({
      next: values => { this.imports = values; this.select(values[0] ?? null); this.busy = false; },
      error: e => this.failed(e)
    }));
  }
  select(value: CompanyTourImport | null): void {
    this.pendingSavedSelection = false;
    if (value?.importId !== this.current?.importId) { this.geocoding = null; this.searchedAddresses.clear(); this.refreshGeocoding = false; }
    this.current = value; this.mapping = value ? structuredClone(value.mapping) : null; if(this.mapping) this.mapping.timeFormat ??= 'HH:mm';
    this.dirty = false; this.sourceReviewed = false; this.notice = ''; this.error = ''; this.previewLimit = 10;
    this.prepareContext(value); this.pendingChange.emit(false);
  }
  selectImport(hash: string): void { if (!this.busy) this.select(this.imports.find(v => v.importId === hash) ?? null); }
  get table(): TourTableProfile | undefined { return this.current?.tables.find(t => t.id === this.mapping?.tableId); }
  get stopCount(): number { return this.current?.draft.tours.reduce((n, t) => n + t.stops.length, 0) ?? 0; }
  orderCount(stops: { orders: { sourceRow: number }[] }[]): number { return stops.reduce((n, stop) => n + stop.orders.length, 0); }
  get exportUrl(): string { return this.current ? this.api.companyTourExportUrl(this.scenarioId, this.current.importId) : ''; }
  changed(): void { this.sourceReviewed = false; this.dirty = true; this.notice = ''; this.pendingChange.emit(this.pendingSavedSelection || !!this.current?.active); }
  column(field: string): number { const value = this.mapping?.columns[field]; return value === undefined ? -1 : value; }
  selectTable(id: string): void {
    const table = this.current?.tables.find(t => t.id === id);
    if (!this.mapping || !table) return;
    this.mapping.tableId = id; this.mapping.headerRow = table.headerRow; this.mapping.columns = {}; this.mapping.joins = {}; this.mapping.fillDownFields = []; this.changed();
  }
  setColumn(field: string, raw: string | number): void {
    if (!this.mapping) return;
    const index = Number(raw); if (index < 0) delete this.mapping.columns[field]; else this.mapping.columns[field] = index;
    if (index < 0) { delete this.mapping.joins?.[field]; this.mapping.fillDownFields = this.mapping.fillDownFields?.filter(f => f !== field); }
    else if (this.mapping.joins?.[field]) this.mapping.joins[field].columns[0] = index;
    this.changed();
  }
  upload(event: Event): void {
    const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = '';
    if (!file || !this.scenarioId || this.busy) return;
    this.busy = true; this.error = ''; this.notice = '';
    this.requests.add(this.api.uploadCompanyTours(this.scenarioId, file).subscribe({
      next: value => {
        this.imports = [value, ...this.imports.filter(v => v.importId !== value.importId)];
        this.select(value); this.busy = false;
      }, error: e => this.failed(e)
    }));
  }
  run(action: 'preview' | 'confirm' | 'suggest'): void {
    if (!this.current || !this.mapping || this.busy || (action === 'confirm' && (this.dirty || this.current.draft.status === 'invalid' || (this.evidenceNeedsReview && !this.sourceReviewed)))) return;
    this.busy = true; this.error = ''; this.notice = '';
    const selected = this.pendingSavedSelection || !!this.current.active;
    this.requests.add(this.api.mapCompanyTours(this.scenarioId, this.current.importId, action, structuredClone(this.mapping), this.sourceReviewed).subscribe({
      next: value => {
        this.current = value; this.mapping = structuredClone(value.mapping); this.dirty = false; this.busy = false;
        this.prepareContext(value);
        // Previews are unsaved; retain the saved list entry until explicit confirmation.
        if (action !== 'confirm') { this.pendingSavedSelection = selected; this.pendingChange.emit(selected); }
        if (action === 'confirm') {
          this.pendingSavedSelection = false;
          this.pendingChange.emit(false);
          this.imports = this.imports.map(v => v.importId === value.importId ? value : v);
          this.notice = 'Mapping gespeichert. Depot, Fahrzeuge und Ortszuordnung jetzt für die Nachfrage ergänzen.';
          this.syncSelection(value.selection);
        }
      }, error: e => this.failed(e)
    }));
  }
  get evidenceNeedsReview(): boolean { return !!this.current?.analysis?.evidence.length && !this.current.mappingConfirmed; }
  fillDown(field: string): boolean { return this.mapping?.fillDownFields?.includes(field) ?? false; }
  setFillDown(field: string, enabled: boolean): void {
    if (!this.mapping) return;
    this.mapping.fillDownFields = enabled ? [...new Set([...(this.mapping.fillDownFields ?? []),field])] : (this.mapping.fillDownFields ?? []).filter(f => f !== field); this.changed();
  }
  extraColumn(field: string): number { return this.mapping?.joins?.[field]?.columns[1] ?? -1; }
  setExtraColumn(field: string, raw: number | string): void {
    if (!this.mapping) return; const extra = Number(raw); this.mapping.joins ??= {};
    if (extra < 0) delete this.mapping.joins[field]; else this.mapping.joins[field] = { columns: [this.column(field),extra], separator: ' ' };
    this.changed();
  }
  analyzeSource(): void {
    if (!this.current || this.busy) return; const active = !!this.current.selection?.imports.some(i => i.importId === this.current!.importId);
    this.busy = true; this.error = '';
    this.requests.add(this.api.analyzeCompanyTours(this.scenarioId,this.current.importId,this.analysisMode).subscribe({
      next: v => this.proposal(v,active), error: e => this.failed(e)
    }));
  }
  private proposal(value: CompanyTourImport, previouslySelected: boolean): void {
    this.select(value); this.busy = false; this.pendingSavedSelection = previouslySelected; this.pendingChange.emit(previouslySelected);
    this.notice = 'Mappingvorschlag prüfen. Quellbelege, ausgelassene Inhalte und alle Tourenzeilen kontrollieren; erst Bestätigung speichert das Mapping.';
  }
  loadProfiles(): void {
    if (!this.scenarioId || this.busy) return; this.busy = true;
    this.requests.add(this.api.listTourImportProfiles(this.scenarioId).subscribe({ next: values => { this.profiles = values; this.busy = false; }, error: e => this.failed(e) }));
  }
  saveProfile(): void {
    if (!this.current?.mappingConfirmed || this.dirty || this.busy || !this.profileName.trim()) return; this.busy = true;
    this.requests.add(this.api.saveTourImportProfile(this.scenarioId,this.current.importId,this.profileName).subscribe({
      next: p => { this.profiles = [p,...this.profiles.filter(v => v.id !== p.id)]; this.selectedProfile = p.id; this.busy = false; this.notice = 'Bestätigtes Mappingprofil gespeichert; neue Dateistrukturen benötigen erneute Prüfung.'; }, error: e => this.failed(e)
    }));
  }
  applyProfile(): void {
    if (!this.current || this.busy || !this.selectedProfile) return;
    const active = !!this.current.selection?.imports.some(i => i.importId === this.current!.importId); this.busy = true;
    this.requests.add(this.api.applyTourImportProfile(this.scenarioId,this.current.importId,this.selectedProfile).subscribe({ next: v => this.proposal(v,active), error: e => this.failed(e) }));
  }
  sourceBlock(id: number): string { return this.current?.source?.blocks.find(b => b.id === id)?.text ?? ''; }
  remove(): void {
    if (!this.current || this.busy) return;
    const hash = this.current.importId; this.busy = true; this.error = '';
    this.requests.add(this.api.deleteCompanyTours(this.scenarioId, hash).subscribe({
      next: () => {
        this.requests.add(this.api.listCompanyTours(this.scenarioId).subscribe({
          next: values => {
            this.imports = values; this.select(values[0] ?? null); this.busy = false;
            this.syncSelection(values[0]?.selection ?? { enabled: false, imports: [] });
          }, error: e => this.failed(e)
        }));
      },
      error: e => this.failed(e)
    }));
  }
  private syncSelection(selection: CompanyTourImport['selection']): void {
    if (!selection) return;
    for (const view of this.imports) {
      view.selection = structuredClone(selection);
      view.active = selection.enabled && selection.imports.some(i => i.importId === view.importId && i.executionHash === view.execution?.executionHash);
    }
    this.selectionChange.emit(selection);
  }
  private point(): CompanyTourPoint { return { lon: null, lat: null, provenance: 'manual-confirmed' }; }
  private prepareContext(value: CompanyTourImport | null): void {
    this.commonDepotAddress = ''; this.commonDepot = this.point();
    this.contextDirty = false; this.locationCandidates = {}; this.locationNotice = '';
    if (!value) { this.context = null; return; }
    this.context = value.context ? structuredClone(value.context) : {
      companyId: '', simulationDate: value.draft.tours[0]?.serviceDate ?? '', dateShiftConfirmed: false,
      sequenceConfirmed: false, windowsConfirmed: false, locationsConfirmed: false, windowPolicy: 'report-only',
      tours: value.draft.tours.map(t => ({ tourId: t.tourId, included: true, vehicleId: '', startTime: '', returnTime: '',
        start: this.point(), end: this.point(), stopOrder: t.stops.map(s => s.stopId ?? `${t.tourId}:${s.sequence}`) })),
      locations: Object.fromEntries(value.draft.tours.flatMap(t => t.stops.map(s => [s.stopId ?? `${t.tourId}:${s.sequence}`, this.point()])))
    };
    this.context.depotAddresses ??= {};
    for (const tour of this.context.tours) {
      this.context.depotAddresses[tour.tourId] ??= { start: '', end: '' };
      tour.start ??= this.point(); tour.end ??= this.point();
      for (const stopId of tour.stopOrder) this.context.locations[stopId] ??= this.point();
    }
    const selected = this.context.tours.filter(t => t.included);
    const address = selected.length ? this.context.depotAddresses[selected[0].tourId] : undefined;
    if (address?.start && address.start === address.end && selected.every(t => {
      const a = this.context!.depotAddresses![t.tourId]; return a.start === address.start && a.end === address.end;
    })) this.commonDepotAddress = address.start;
    if (selected.length && selected.every(t => hasTourPoint(t.start) && hasTourPoint(t.end) &&
      t.start.lon === selected[0].start.lon && t.start.lat === selected[0].start.lat &&
      t.end.lon === selected[0].start.lon && t.end.lat === selected[0].start.lat))
      this.commonDepot = structuredClone(selected[0].start);
  }
  get contextQuestions() { return this.current && this.context ? tourContextQuestions(this.current, this.context) : []; }
  get canApplyDepot(): boolean { return !!this.commonDepotAddress.trim() || hasTourPoint(this.commonDepot); }
  commonDepotAddressChanged(address: string): void {
    this.commonDepotAddress = address; this.commonDepot = this.point();
  }
  depotAddressChanged(tour: CompanyTourContext['tours'][number], end: 'start' | 'end'): void {
    tour[end] = this.point();
    if (this.context) this.context.locationsConfirmed = false;
    this.contextChanged();
  }
  regionalProjectionChanged(confirmed: boolean): void {
    if (this.context) this.context.regionalProjectionConfirmed = confirmed;
    this.contextChanged();
  }
  contextChanged(): void { if(this.context && !this.context.regionalProjectionConfirmed)this.context.networkProjectionRequested=false; this.contextDirty = true; this.notice = ''; this.pendingChange.emit(this.pendingSavedSelection || !!this.current?.active); }
  setPoint(point: CompanyTourPoint): void { point.provenance = 'manual-confirmed'; if (this.context) this.context.locationsConfirmed = false; this.contextChanged(); }
  applyDepot(): void {
    if (!this.context || this.busy || !this.canApplyDepot) return;
    const address = this.commonDepotAddress.trim();
    this.context.depotAddresses ??= {};
    for (const tour of this.context.tours) if (tour.included) {
      this.context.depotAddresses[tour.tourId] = { start: address, end: address };
      const point = hasTourPoint(this.commonDepot) ? this.commonDepot : this.point();
      tour.start = structuredClone(point); tour.end = structuredClone(point);
    }
    this.context.locationsConfirmed = false;
    this.contextChanged();
  }
  stopsFor(tourId: string) { return this.current?.draft.tours.find(t => t.tourId === tourId)?.stops ?? []; }
  tourName(tourId: string): string { return this.current?.draft.tours.find(t => t.tourId === tourId)?.sourceTourCode ?? tourId; }
  moveStop(tour: CompanyTourContext['tours'][number], index: number, direction: number): void {
    const target = index + direction;
    if (target < 0 || target >= tour.stopOrder.length) return;
    [tour.stopOrder[index], tour.stopOrder[target]] = [tour.stopOrder[target], tour.stopOrder[index]]; this.contextChanged();
  }
  stopFor(tourId: string, stopId: string) { return this.stopsFor(tourId).find(s => (s.stopId ?? `${tourId}:${s.sequence}`) === stopId); }
  saveContext(): void {
    if (!this.current || !this.context || this.busy || this.dirty) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.saveCompanyTourContext(this.scenarioId, this.current.importId, structuredClone(this.context)).subscribe({
      next: v => {
        this.select(v); this.imports = this.imports.map(i => i.importId === v.importId ? v : i); this.busy = false;
        this.syncSelection(v.selection);
        this.notice = v.execution?.ready ? 'Tourenkontext vollständig. Für die gemeinsame Nachfrage auswählbar; Netzprüfungen gehören zum jeweiligen Zielmodell.' : 'Kontext gespeichert. Offene Prüfpunkte unten ergänzen.';
      }, error: e => this.failed(e)
    }));
  }
  activate(enabled: boolean): void {
    if (!this.current || this.busy || (enabled && (this.contextDirty || this.dirty || !this.current.execution?.ready))) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.activateCompanyTours(this.scenarioId, this.current.importId, enabled).subscribe({
      next: v => {
        this.select(v); this.imports = this.imports.map(i => i.importId === v.importId ? v : i); this.busy = false;
        this.syncSelection(v.selection);
        this.notice = enabled ? 'Touren für die gemeinsame Nachfrage zusätzlich ausgewählt.' : 'Touren aus der Nachfrageauswahl entfernt.';
      }, error: e => this.failed(e)
    }));
  }
  get pendingGeocodingTargets(): string[] {
    const targets: string[] = [];
    const consider = (id: string, point: CompanyTourPoint | undefined) => {
      const query = this.targetQuery(id);
      if (!hasTourPoint(point) && query?.trim() && !this.searchedAddresses.has(`${id}\n${query}`)) targets.push(id);
    };
    for (const tour of this.context?.tours ?? []) if (tour.included) {
      consider(`start:${tour.tourId}`, tour.start); consider(`end:${tour.tourId}`, tour.end);
      for (const stop of tour.stopOrder) consider(`stop:${stop}`, this.context?.locations[stop]);
    }
    return targets;
  }
  restartGeocoding(): void {
    if (this.busy || this.contextDirty) return;
    this.searchedAddresses.clear(); this.refreshGeocoding = true; this.geocoding = null;
    this.geocode();
  }
  geocode(): void {
    if (!this.current || !this.context || this.busy || this.contextDirty || !this.current.geocoding?.enabled) return;
    const targets = this.pendingGeocodingTargets.slice(0, this.current.geocoding.maxBatch);
    if (!targets.length) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.geocodeCompanyTours(this.scenarioId, this.current.importId, targets, this.refreshGeocoding).subscribe({
      next: lookup => {
        this.busy = false;
        if (lookup.importId !== this.current?.importId) return;
        for (const answer of lookup.answers) this.searchedAddresses.add(`${answer.targetId}\n${answer.query}`);
        const answers = new Map((this.geocoding?.answers ?? []).map(a => [a.targetId, a]));
        for (const answer of lookup.answers) answers.set(answer.targetId, answer);
        this.geocoding = { ...lookup, answers: [...answers.values()] };
      },
      error: e => this.failed(e)
    }));
  }
  private targetQuery(targetId: string): string | undefined {
    if (!this.context) return undefined;
    const part = targetId.indexOf(':'); const kind = targetId.slice(0, part), id = targetId.slice(part + 1);
    if (kind === 'start' || kind === 'end') {
      if (!this.context.tours.some(t => t.tourId === id && t.included)) return undefined;
      return this.context.depotAddresses?.[id]?.[kind];
    }
    if (kind === 'stop' && this.context.tours.some(t => t.included && t.stopOrder.includes(id))) {
      const stop = this.current?.draft.tours.flatMap(t => t.stops).find(s => s.stopId === id);
      if (stop) return `${stop.location.street}, ${stop.location.postalCode} ${stop.location.city}`;
    }
    return undefined;
  }
  applyGeocoding(answer: CompanyTourGeocodingLookup['answers'][number], index: number): void {
    if (!this.context || this.busy || this.targetQuery(answer.targetId) !== answer.query) return;
    const candidate = answer.candidates[index]; if (!candidate || !hasTourPoint(candidate.point)) return;
    if (answer.targetId.startsWith('stop:')) this.context.locations[answer.targetId.slice(5)] = structuredClone(candidate.point);
    else for (const tour of this.context.tours.filter(t => t.included)) for (const end of ['start', 'end'] as const)
      if (this.context.depotAddresses?.[tour.tourId]?.[end] === answer.query) tour[end] = structuredClone(candidate.point);
    this.context.locationsConfirmed = false; this.contextChanged();
  }
  applyUniqueGeocoding(): void {
    for (const answer of this.geocoding?.answers ?? []) if (answer.candidates.length === 1) this.applyGeocoding(answer, 0);
  }
  locate(event: Event): void {
    const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = '';
    if (!file || !this.current || !this.context || this.busy) return;
    this.busy = true; this.error = '';
    this.requests.add(this.api.locateCompanyTours(this.scenarioId, this.current.importId, file).subscribe({
      next: lookup => {
        this.locationCandidates = Object.fromEntries(lookup.matches.map(m => [m.stopId, m.candidates]));
        for (const match of lookup.matches) if (match.candidates.length === 1) this.context!.locations[match.stopId] = structuredClone(match.candidates[0]);
        this.locationNotice = `${lookup.matched} eindeutige Treffer, ${lookup.ambiguous} mehrdeutig, ${lookup.missing} ohne Treffer. Koordinaten prüfen und bestätigen.`;
        this.busy = false; this.contextChanged(); this.context!.locationsConfirmed = false;
      }, error: e => this.failed(e)
    }));
  }
  chooseLocation(stopId: string, raw: string): void {
    const candidate = this.locationCandidates[stopId]?.[Number(raw)];
    if (this.context && candidate) { this.context.locations[stopId] = structuredClone(candidate); this.contextChanged(); }
  }
  issueLabel(code: string): string {
    const labels: Record<string, string> = {
      'missing-column': 'Pflichtspalte fehlt', 'missing-value': 'Pflichtwert fehlt', 'invalid-date': 'Datum ungültig',
      'invalid-time': 'Zeit ungültig', 'row-width': 'Zeile hat abweichende Spaltenzahl',
      'duplicate-transaction': 'Vorgangsschlüssel mehrfach vorhanden', 'overlapping-stops': 'Halte überschneiden sich',
      'window-semantics-review': 'Halt außerhalb des Kundenfensters; Fensterbedeutung prüfen',
      'conflicting-window': 'Unterschiedliche Kundenfenster am Halt; Fenster je Auftrag erhalten', 'invalid-window': 'Kundenfenster ungültig',
      'invalid-window-time': 'Kundenfenster enthält ungültige Zeit',
      'overnight-or-negative-dwell': 'Abfahrt vor Ankunft; Nachtfahrt braucht gesonderte Datumsangaben',
      'ambiguous-or-invalid-local-time': 'Zeit am Sommerzeitwechsel mehrdeutig oder ungültig',
      'missing-depot-and-vehicle-context': 'Depotstart, Rückkehr und Fahrzeugangaben fehlen',
      'geocoding-and-routing-required': 'Ortszuordnung und Fahrzeitprüfung stehen aus',
      'chronological-sequence-needs-review': 'Chronologisch abgeleitete Haltreihenfolge prüfen'
    };
    return labels[code] ?? code;
  }
  private failed(e: { error?: { message?: string } }): void {
    this.error = e.error?.message ?? 'Tourenimport fehlgeschlagen'; this.busy = false;
  }
}
