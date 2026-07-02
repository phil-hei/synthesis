import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { Scenario } from '../../core/models/scenario.model';

/**
 * Side-by-side comparison of several scenarios: the user picks scenarios, the view
 * loads their parameters and simulation KPIs (if simulated) into one table with the
 * MiD-2017 reference column.
 */
@Component({
  selector: 'app-comparison',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './comparison.component.html',
  styleUrls: ['./comparison.component.css']
})
export class ComparisonComponent implements OnInit {

  scenarios: Scenario[] = [];
  selected: Record<string, boolean> = {};
  entries: any[] = [];
  mid: any = null;
  message = '';

  private readonly modeLabels: Record<string, string> = {
    pedestrian: 'Fuß', bike: 'Rad', car: 'MIV (Fahrer)',
    passenger: 'MIV (Mitfahrer)', publicTransport: 'ÖV', other: 'Sonstige'
  };

  constructor(private api: ApiService) {
  }

  ngOnInit(): void {
    this.api.listScenarios().subscribe(list => this.scenarios = list);
  }

  get selectedIds(): string[] {
    return Object.keys(this.selected).filter(id => this.selected[id]);
  }

  loadComparison(): void {
    const ids = this.selectedIds;
    if (ids.length === 0) {
      this.entries = [];
      this.message = 'Bitte mindestens ein Szenario auswählen';
      return;
    }
    this.api.compare(ids).subscribe(entries => {
      this.entries = entries;
      this.mid = entries.find(e => e.kpis)?.kpis?.mid2017 ?? null;
      const unsimulated = entries.filter(e => !e.simulated).map(e => e.name);
      this.message = unsimulated.length
        ? `Ohne Simulationsergebnis (nur Parameter): ${unsimulated.join(', ')}`
        : '';
    });
  }

  /** Modal-Split-Modi, die in mindestens einem Szenario oder der MiD-Referenz vorkommen. */
  get modeKeys(): string[] {
    const keys = new Set<string>();
    this.entries.forEach(e => Object.keys(e.kpis?.demand?.modalSplit ?? {}).forEach(k => keys.add(k)));
    Object.keys(this.mid?.modalSplit ?? {}).forEach(k => keys.add(k));
    return Object.keys(this.modeLabels).filter(k => keys.has(k));
  }

  modeLabel(key: string): string {
    return this.modeLabels[key] ?? key;
  }

  pct(value: number | null | undefined): string {
    return value == null ? '–' : Math.round(value * 100) + ' %';
  }
}
