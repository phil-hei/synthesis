import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { DISTANCE_BAND_ROWS, DistanceBands } from '../../core/models/results.model';
import { Scenario } from '../../core/models/scenario.model';

/**
 * Side-by-side comparison of several scenarios: the user picks scenarios, the view
 * loads their parameters and simulation KPIs (if simulated) into one table with the
 * MiD-2017 reference column.
 */
@Component({
  selector: 'app-comparison',
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
  loading = false;

  private readonly modeLabels: Record<string, string> = {
    pedestrian: 'Fuß', bike: 'Rad', car: 'MIV (Fahrer)',
    passenger: 'MIV (Mitfahrer)', publicTransport: 'ÖV', other: 'Sonstige'
  };

  constructor(private api: ApiService) {
  }

  ngOnInit(): void {
    this.api.listScenarios().subscribe({
      next: list => this.scenarios = list,
      error: () => {
        this.scenarios = [];
        this.message = 'Szenarien konnten nicht geladen werden';
      }
    });
  }

  get selectedIds(): string[] {
    return Object.keys(this.selected).filter(id => this.selected[id]);
  }

  get tableColumnCount(): number {
    return 1 + this.entries.length + (this.mid ? 1 : 0);
  }

  loadComparison(): void {
    const ids = this.selectedIds;
    if (ids.length === 0) {
      this.entries = [];
      this.message = 'Bitte mindestens ein Szenario auswählen';
      return;
    }
    this.loading = true;
    this.api.compare(ids).pipe(finalize(() => this.loading = false)).subscribe({
      next: entries => {
        this.entries = entries;
        // Bei gemischten Alt-/Neuläufen die reichste Referenz wählen: ältere kpis.json
        // enthalten noch keine Distanzbänder, bleiben aber weiterhin vergleichbar.
        const referenceEntry = entries.find(e => e.kpis?.mid2017?.modalSplitByDistanceBand)
          ?? entries.find(e => e.kpis?.mid2017?.distanceBands)
          ?? entries.find(e => e.kpis);
        this.mid = referenceEntry?.kpis?.mid2017 ?? null;
        const unsimulated = entries.filter(e => !e.simulated).map(e => e.name);
        this.message = unsimulated.length
          ? `Ohne Simulationsergebnis (nur Parameter): ${unsimulated.join(', ')}`
          : '';
      },
      error: () => {
        this.entries = [];
        this.mid = null;
        this.message = 'Vergleich konnte nicht geladen werden';
      }
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

  /** Distanzbänder, die in mindestens einem Szenario oder der MiD-Referenz vorkommen. */
  get distanceBandRows(): { key: keyof DistanceBands; label: string }[] {
    return DISTANCE_BAND_ROWS.filter(({ key }) =>
      this.entries.some(e => e.kpis?.demand?.distanceBands?.[key] != null)
      || this.mid?.distanceBands?.[key] != null);
  }

  get hasModeDistanceKpis(): boolean {
    return this.entries.some(e =>
      e.kpis?.demand?.modeDistanceJointRmseToMid2017 != null);
  }

  /** Richtungszeilen nur zeigen, wenn mindestens ein Lauf den neuen KPI-Vertrag enthält. */
  get hasLogisticsKpis(): boolean {
    return this.entries.some(e => !!e.kpis?.logistics);
  }

  /** SUMO-Zeilen nur zeigen, wenn mindestens ein Szenario einen SUMO-Lauf hat. */
  get hasSumo(): boolean {
    return this.entries.some(e => !!e.sumo);
  }

  pct(value: number | null | undefined): string {
    return value == null ? '–' : Math.round(value * 100) + ' %';
  }

  pct1(value: number | null | undefined): string {
    return value == null ? '–' : (value * 100).toFixed(1).replace('.', ',') + ' %';
  }

  deliveryGenerated(entry: any): number | null {
    return entry.kpis?.logistics?.deliveries?.generated
      ?? entry.kpis?.parcels?.total
      ?? null;
  }

  deliveryRate(entry: any): number | null {
    return entry.kpis?.logistics?.deliveries?.deliveryRate
      ?? entry.kpis?.parcels?.deliveryRate
      ?? null;
  }
}
