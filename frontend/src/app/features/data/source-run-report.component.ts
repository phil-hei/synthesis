import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges, OnDestroy, inject } from '@angular/core';
import { Subscription } from 'rxjs';
import { DataSourceService } from '../../core/services/data-source.service';
import { CandidateDecision } from '../../core/models/data-source.model';
import { SOURCE_RUN_CONTRACT, SourceAspectReport, SourceProvenance, SourceRunReport } from '../../core/models/source-run-report.model';

interface Balance { label: string; value: number; }
interface Ledger { title: string; revision?: string; balances: Balance[]; }
interface Aspect { title: string; report?: SourceAspectReport; ledgers: Ledger[]; graphs: { title: string; graph: SourceProvenance }[]; candidates: CandidateDecision[]; gapCandidates: CandidateDecision[]; }
const labels: Record<string, string> = {
  totalPersons: 'Zielmenge Bevölkerung (Personen)', allocatedPersons: 'Räumlich zugeteilt (Personen)',
  persons: 'Mit Ersatzgewichten verteilte Personen',
  targetPersons: 'Zielmenge vor Haushaltsrundung (Personen)', actualPersons: 'Tatsächlich erzeugte Personen', difference: 'Differenz durch vollständige Haushalte (Personen)',
  inputJobs: 'Quellmenge (deklarierte Arbeitsplätze)', appliedJobs: 'Übernommene Quellmenge (Arbeitsplätze)',
  mappedJobs: 'Räumlich zugeordnet (Arbeitsplätze)', outsideJobs: 'Außerhalb des Modellgebiets (Arbeitsplätze)',
  shadowedJobs: 'Durch vorrangige Register verdrängt (Arbeitsplätze)', unresolvedJobs: 'Ungeklärt (Arbeitsplätze)',
  unappliedPartialJobs: 'In Teilzonen nicht übernommen (Arbeitsplätze)', jobsInPartiallyCoveredZonesNotApplied: 'In Teilzonen nicht übernommen (Arbeitsplätze)',
  sumWeights: 'Summe Modellgewichte; keine gezählte Arbeitsplatzmenge', coveredZones: 'Von Quellen abgedeckte Zonen', fallbackZones: 'Zonen mit Ersatzgewichten',
  knownInput: 'Bekannte Quellmenge', knownApplied: 'Bekannte Menge übernommen', knownOutside: 'Bekannte Menge außerhalb', knownInPartialZonesNotApplied: 'Bekannte Menge in Teilzonen nicht übernommen',
  missingCells: 'Zellen ohne Wert', suppressedCells: 'Geheimgehaltene Zellen', notCollectedCells: 'Nicht erhobene Zellen',
  inputVehicleTrips: 'Quellmenge (Fahrzeugfahrten/Tag)', generatedVehicleTrips: 'Erzeugte Fahrzeugfahrten/Tag'
};

@Component({
  selector: 'app-source-run-report', imports: [CommonModule],
  templateUrl: './source-run-report.component.html', styleUrl: './source-run-report.component.css'
})
export class SourceRunReportComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) scenarioId = '';
  @Input() runVersion = '';
  report: SourceRunReport | null = null;
  aspects: Aspect[] = [];
  loading = false; message = ''; error = '';
  private request?: Subscription;
  private readonly api = inject(DataSourceService);
  ngOnChanges(): void { this.refresh(); }
  ngOnDestroy(): void { this.request?.unsubscribe(); }
  get artifactBase(): string { return `/api/scenarios/${encodeURIComponent(this.scenarioId)}/artifacts/`; }
  refresh(): void {
    this.request?.unsubscribe(); this.report = null; this.aspects = []; this.error = ''; this.message = '';
    if (!this.scenarioId) return;
    this.loading = true;
    this.request = this.api.runReport(this.scenarioId).subscribe({ next: report => {
      this.loading = false;
      if (!report || report.contract !== SOURCE_RUN_CONTRACT || report.run?.scenarioId !== this.scenarioId
          || !report.targets?.sumo || !report.targets?.logitopp || !Array.isArray(report.decisions)) {
        this.message = 'Für diesen älteren oder unbekannten Bericht ist keine gesicherte Laufzuordnung verfügbar. Bitte erneut generieren.'; return;
      }
      this.report = report;
      this.aspects = [this.aspect('Bevölkerung', report.population, report.populationSynthesis), this.aspect('Arbeitsplätze', report.workplaces), this.aspect('Schwerlastbeziehungen', report.freight)];
    }, error: e => {
      this.loading = false;
      if (e.status === 404) this.message = 'Kein Quellenlaufbericht vorhanden. Er entsteht bei der Generierung mit aktiviertem Quellenplan.';
      else this.error = 'Quellenlaufbericht konnte nicht geladen werden. Bitte erneut versuchen.';
    } });
  }
  state(value: string): string { return ({ RUNNING: 'Läuft', DONE: 'Abgeschlossen', FAILED: 'Fehlgeschlagen', SKIPPED: 'Übersprungen', PENDING: 'Noch nicht ausgeführt', QUEUED: 'Wartet', complete: 'Vollständig abgeschlossen', partial: 'Unvollständig', generated: 'Erzeugt', excluded: 'Bewusst ausgeschlossen', 'not-enabled': 'Nicht aktiviert', resolved: 'Aufgelöst', 'resolved-with-fallback': 'Mit Ersatzbeitrag aufgelöst' } as Record<string, string>)[value] ?? value; }
  evidence(value: string): string { return ({ observed: 'Beobachtung', derived: 'Abgeleitet', assumed: 'Annahme', unknown: 'Evidenz unbekannt' } as Record<string, string>)[value] ?? value; }
  role(value: string): string { return ({ primary: 'Primärbeitrag', fallback: 'Ersatzbeitrag', composition: 'Zusammensetzung' } as Record<string, string>)[value] ?? value; }
  candidate(value: string): string { return ({ selected: 'Ausgewählt', excluded: 'Abgewählt', unavailable: 'Nicht verfügbar', invalid: 'Ungültig', 'not-applicable': 'Nicht passend', 'not-evaluated': 'Nicht geprüft', pending: 'Noch nicht aufgelöst', staged: 'Eingabe gebunden' } as Record<string, string>)[value] ?? value; }
  provider(value: string): string { return ({ 'upload-grid': 'Hochgeladenes Bevölkerungsraster', 'census-2022': 'Zensus 2022', 'osm-places': 'OSM-Siedlungsdaten', 'poi-density': 'POI-basierte Ersatzverteilung', uniform: 'Ersatz ohne Ortsdaten', 'osm-pois': 'OSM-Arbeitsgewichte', synthetic: 'Synthetische Arbeitsgewichte' } as Record<string, string>)[value.replace(/^builtin:/, '')] ?? value; }
  reason(value: string): string { return ({
    'explicitly-excluded': 'Ausdrücklich abgewählt', 'higher-priority-candidate-selected': 'Eine vorrangige Quelle wurde ausgewählt',
    'first-usable-candidate': 'Erste verwendbare Quelle', 'first-applicable-candidate': 'Erste passende Quelle', 'explicit-selection': 'Direkt ausgewählte Revision',
    'no-osm-pois': 'Keine OSM-Ortsmerkmale für Arbeitsgewichte vorhanden', 'no-pois': 'Keine Ortsmerkmale für die Ersatzverteilung vorhanden',
    'no-population-places': 'Keine Siedlungen mit Bevölkerungsangaben vorhanden', 'no-upload-at-queue-time': 'Beim Start lag kein Bevölkerungsraster vor',
    'census-disabled-outside-coverage-or-empty': 'Zensus deaktiviert, außerhalb der Abdeckung oder ohne verwertbare Werte',
    'source-after-scenario-year': 'Quellenstand liegt nach dem Bezugsjahr', 'no-positive-population-in-model-zones': 'Keine positive Bevölkerung in den Modellzonen',
    'no-uncovered-zones': 'Keine Quellenlücken zu ergänzen', 'regional-register-contribution': 'Register trägt zu bisher nicht abgedeckten Zonen bei',
    'covered-by-higher-priority-registers': 'Bereits durch vorrangige Register abgedeckt', 'higher-priority-registers-cover-all-zones': 'Vorrangige Register decken alle Zonen ab',
    'no-complete-model-zone': 'Keine vollständig abgedeckte Modellzone', 'retained-source-missing': 'Gespeicherte Quelldatei fehlt'
  } as Record<string, string>)[value] ?? value; }
  parameters(value: Record<string, unknown>): string {
    const text = JSON.stringify(value, null, 2);
    return text.length > 4000 ? text.slice(0, 4000) + '\n… vollständige Parameter im JSON-Bericht.' : text;
  }
  balances(value: Record<string, unknown>): Balance[] {
    return Object.entries(labels).flatMap(([key, label]) => typeof value[key] === 'number' && Number.isFinite(value[key]) ? [{ label, value: value[key] as number }] : []);
  }
  get failures() { return Object.entries(this.report?.failures ?? {}).map(([aspect, failure]) => ({ aspect: aspect === 'population' ? 'Bevölkerung' : 'Arbeitsplätze', ...failure })); }
  private aspect(title: string, report?: SourceAspectReport, synthesis?: Record<string, unknown>): Aspect {
    const ledgers: Ledger[] = []; const graphs: Aspect['graphs'] = []; let gapCandidates: CandidateDecision[] = [];
    if (report) {
      ledgers.push({ title: 'Mengen und Abdeckung', revision: report.revision, balances: this.balances(report) });
      const diagnostics = this.object(report['diagnostics']);
      const masked = this.object(diagnostics?.['maskedCells']);
      if (masked) ledgers.push({ title: 'Bekannte Rasterwerte; Einheit Personen', balances: this.balances(masked) });
      const gap = this.object(report['gapResolution']) ?? this.object(diagnostics?.['gapResolution']);
      if (gap) {
        ledgers.push({ title: 'Ersatzbeitrag für Quellenlücken', balances: this.balances(gap) });
        if (Array.isArray(gap['candidates'])) gapCandidates = gap['candidates'] as CandidateDecision[];
      }
      const parts = report['parts'];
      if (Array.isArray(parts)) for (const part of parts) {
        const p = this.object(part); if (!p) continue;
        ledgers.push({ title: `Register ${p['rank']} · ${p['provider']}`, revision: String(p['revision'] ?? ''), balances: this.balances(p) });
      }
      for (const [key, name] of [['totalProvenance', 'Gesamtzahl'], ['distributionProvenance', 'Räumliche Verteilung'], ['provenance', 'Teileherkunft']] as const) {
        const graph = report[key]; if (graph) graphs.push({ title: name, graph });
      }
    }
    if (synthesis) ledgers.push({ title: 'Bevölkerungssynthese', balances: this.balances(synthesis) });
    return { title, report: report && Object.keys(report).length ? report : undefined, ledgers: ledgers.filter(l => l.balances.length), graphs, candidates: report?.candidates ?? [], gapCandidates };
  }
  private object(value: unknown): Record<string, unknown> | undefined { return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined; }
}
