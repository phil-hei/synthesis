import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of, Subject, throwError } from 'rxjs';
import { DataSourceService } from '../../core/services/data-source.service';
import { SOURCE_RUN_CONTRACT, SourceRunReport } from '../../core/models/source-run-report.model';
import { SourceRunReportComponent } from './source-run-report.component';

describe('SourceRunReportComponent', () => {
  const api = { runReport: vi.fn() };
  const result = (): SourceRunReport => ({
    contract: SOURCE_RUN_CONTRACT,
    run: { id: 's/run-1', scenarioId: 's', state: 'DONE', startedAt: '2026-11-04T10:00:00Z', finishedAt: '2026-11-04T10:01:00Z', seed: 42, referenceYear: 2026, planSha256: 'plan-hash', lockSha256: 'lock-hash' },
    plan: { enabled: true, aspects: {} }, decisions: [], failures: {},
    targets: { logitopp: { stageStatus: 'SKIPPED' }, sumo: { stageStatus: 'SKIPPED' } }
  });
  beforeEach(async () => {
    vi.clearAllMocks(); api.runReport.mockReturnValue(of(result()));
    await TestBed.configureTestingModule({ imports: [SourceRunReportComponent], providers: [{ provide: DataSourceService, useValue: api }] }).compileComponents();
  });
  function create() { const f = TestBed.createComponent(SourceRunReportComponent); f.componentRef.setInput('scenarioId', 's'); f.detectChanges(); return f; }
  it('binds the displayed amounts to a named run and never implies current edited inputs were used', () => {
    const r = result(); r.population = { totalPersons: 12, allocatedPersons: 12 }; r.populationSynthesis = { targetPersons: 12, actualPersons: 13, difference: 1 };
    api.runReport.mockReturnValue(of(r)); const f = create(); const text = f.nativeElement.textContent;
    expect(text).toContain('s/run-1'); expect(text).toContain('Aktuelle Änderungen'); expect(text).toContain('Tatsächlich erzeugte Personen'); expect(text).toContain('13');
    expect(text).toContain('keine erfolgreich gelesene SUMO-Ausführungsbilanz');
    expect(f.nativeElement.querySelector('a').href).toContain('/api/scenarios/s/artifacts/sources/resolution.json'); f.destroy();
  });
  it('shows household rounding even when the population source delegates to the legacy path', () => {
    const r = result(); r.populationSynthesis = { targetPersons: 12, actualPersons: 13, difference: 1 };
    api.runReport.mockReturnValue(of(r)); const f = create();
    expect(f.nativeElement.textContent).toContain('keine aufgeschlüsselte Quellenbilanz');
    expect(f.nativeElement.textContent).toContain('Tatsächlich erzeugte Personen');
    expect(f.componentInstance.aspects[0].report).toBeUndefined(); f.destroy();
  });
  it('preserves true zeros and mask counts without inventing values for missing quantities', () => {
    const r = result(); r.workplaces = { inputJobs: 0, appliedJobs: 0, missingCells: 2, suppressedCells: 1, notCollectedCells: 3 };
    api.runReport.mockReturnValue(of(r)); const f = create(); const c = f.componentInstance; const work = c.aspects.find(a => a.title === 'Arbeitsplätze')!;
    expect(work.ledgers[0].balances.filter(b => b.value === 0)).toHaveLength(2);
    expect(work.ledgers[0].balances).toHaveLength(5); expect(f.nativeElement.textContent).toContain('Geheimgehaltene Zellen');
    expect(c.balances({ appliedJobs: null, inputJobs: undefined, sumWeights: Infinity })).toEqual([]); f.destroy();
  });
  it('shows regional register amounts separately and retains evidence and geographic scope of every part', () => {
    const r = result(); r.workplaces = { unit: 'model-weight', sumWeights: 4, parts: [
      { rank: 1, provider: 'revision:first', revision: 'first', inputJobs: 10, appliedJobs: 10, outsideJobs: 0 },
      { rank: 2, provider: 'revision:second', revision: 'second', inputJobs: 50, appliedJobs: 0, shadowedJobs: 50 }
    ], provenance: { aspect: 'opportunities.work', root: 'combined', nodes: [
      { id: 'first', operation: 'source', evidence: 'unknown', selectionRole: 'primary', inputs: [], source: { kind: 'dataset', label: 'Arbeitsregister', revision: 'first', licence: 'CC0' }, scope: { purpose: 'Work', zones: [{ externalId: 1001, modelIndex: 0 }] }, parameters: {} },
      { id: 'gap', operation: 'synthetic-work-weights', evidence: 'assumed', selectionRole: 'fallback', inputs: [], scope: { purpose: 'Work', zones: [{ externalId: 1002, modelIndex: 1 }] }, parameters: { seed: 42 } },
      { id: 'combined', operation: 'fill-gaps', evidence: 'derived', selectionRole: 'composition', inputs: ['first', 'gap'], scope: { purpose: 'Work', zones: [{ externalId: 1001, modelIndex: 0 }, { externalId: 1002, modelIndex: 1 }] }, parameters: { quantitiesComparable: false } }
    ] } };
    api.runReport.mockReturnValue(of(r)); const f = create(); const work = f.componentInstance.aspects[1];
    expect(work.ledgers).toHaveLength(3); expect(work.ledgers.map(l => l.revision)).toEqual([undefined, 'first', 'second']);
    const text = f.nativeElement.textContent; for (const word of ['Modellgewichte', 'verdrängt', 'Evidenz unbekannt', 'Annahme', 'Ersatzbeitrag', '1002', 'CC0']) expect(text).toContain(word);
    expect(text).toContain('keine gemeinsame beobachtete Arbeitsplatzsumme'); f.destroy();
  });
  it('shows partial failure diagnostics without reusing a previous successful aspect ledger', () => {
    const f = create(); const r = result(); r.run.state = 'FAILED'; r.run.error = 'Arbeitsgewichte fehlen';
    r.failures = { workplace: { message: 'Keine erlaubte Quelle', candidates: [{ provider: 'osm-pois', rank: 1, status: 'not-applicable', reason: 'no-osm-pois', onUnavailable: 'fail' }] } };
    api.runReport.mockReturnValue(of(r)); f.componentInstance.refresh(); f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Fehlgeschlagen'); expect(f.nativeElement.textContent).toContain('Keine erlaubte Quelle'); expect(f.nativeElement.textContent).toContain('keinen vollständig erfolgreichen Lauf');
    expect(f.componentInstance.aspects[1].report).toBeUndefined(); f.destroy();
  });
  it('keeps generated freight amounts and actual routing and completion visibly distinct', () => {
    const r = result(); r.freight = { generatedVehicleTrips: 8, inputVehicleTrips: 8, scientificUse: 'not-approved', assumptionStatement: 'Testannahme', contributions: [{ relationId: 'internal', sourceRow: 2, source: { kind: 'model', label: 'Demonstration' }, evidence: 'assumed', inputVehicleTrips: 8, generatedVehicleTrips: 8 }] };
    r.targets.sumo = { stageStatus: 'DONE', execution: { status: 'partial', routingVerified: true, demandRetained: false, vehicles: { requested: 8, routed: 8, departed: 7, completed: 6 }, persons: { requested: 0, routed: 0, departed: 0, completed: 0 }, nativeVehicleTeleports: 1 } };
    api.runReport.mockReturnValue(of(r)); const f = create(); const text = f.nativeElement.textContent;
    for (const word of ['Testannahme', 'fachliche Auswertungen gesperrt', 'Unvollständig', 'Nachfrage erhalten: nein', 'Abgeschlossen', 'internal']) expect(text).toContain(word);
    const rows = f.nativeElement.querySelectorAll('table')[1].querySelectorAll('tbody tr');
    expect(Array.from(rows[0].querySelectorAll('td'), (cell: unknown) => (cell as HTMLElement).textContent)).toEqual(['8', '8', '7', '6']); f.destroy();
  });
  it('clears stale reports on reload errors and distinguishes absent, unreadable and older reports', () => {
    const f = create(); const c = f.componentInstance; expect(c.report).not.toBeNull();
    api.runReport.mockReturnValue(throwError(() => ({ status: 404 }))); c.refresh(); expect(c.report).toBeNull(); expect(c.message).toContain('Kein Quellenlaufbericht');
    api.runReport.mockReturnValue(throwError(() => ({ status: 422 }))); c.refresh(); expect(c.error).toContain('nicht geladen'); expect(c.message).toBe('');
    api.runReport.mockReturnValue(of({ workplaces: { appliedJobs: 99 } })); c.refresh(); expect(c.message).toContain('erneut generieren'); expect(c.report).toBeNull();
    api.runReport.mockReturnValue(of(null)); c.refresh(); expect(c.report).toBeNull(); expect(c.message).toContain('erneut generieren');
    api.runReport.mockReturnValue(of({ contract: SOURCE_RUN_CONTRACT, run: { scenarioId: 's' } })); c.refresh(); expect(c.report).toBeNull();
    const wrong = result(); wrong.run.scenarioId = 'other'; api.runReport.mockReturnValue(of(wrong)); c.refresh(); expect(c.report).toBeNull(); f.destroy();
  });
  it('cancels stale responses on scenario changes, reloads after a run transition and stops on destroy', () => {
    const pending = new Subject<SourceRunReport>(); api.runReport.mockReturnValue(pending); const f = create();
    const next = result(); next.run.scenarioId = 'other'; api.runReport.mockReturnValue(of(next));
    f.componentRef.setInput('scenarioId', 'other'); f.detectChanges(); pending.next(result());
    expect(f.componentInstance.report?.run.scenarioId).toBe('other'); expect(pending.observed).toBe(false);
    f.componentRef.setInput('runVersion', 'DONE:new'); f.detectChanges(); expect(api.runReport).toHaveBeenCalledTimes(3);
    api.runReport.mockReturnValue(pending); f.componentInstance.refresh(); f.destroy(); expect(pending.observed).toBe(false);
  });
});
