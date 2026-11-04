import { Component, EventEmitter, Input, Output, OnChanges, OnDestroy, SimpleChanges } from '@angular/core';
import { Subscription } from 'rxjs';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Scenario } from '../../core/models/scenario.model';
import { EnergyQuality, EnergySource, ENERGY_LABELS } from '../../core/models/energy-infrastructure.model';
import { ApiService } from '../../core/services/api.service';
@Component({
  selector: 'app-energy-infrastructure-panel', imports: [CommonModule, FormsModule],
  template: `
    @if (scenario.energyInfrastructure; as config) {
      <h2>Öffentliche Lade- und Tankstationen</h2>
      <label><input type="checkbox" [(ngModel)]="config.enabled" (ngModelChange)="sourcesChanged.emit()" [disabled]="disabled"> Stationsbestand erfassen (experimentell)</label>
      @if (config.enabled) {
        <p>Standorte und technische Angaben für Strom, CNG, LNG, LPG und Wasserstoff. Nach der Generierung auf der Karte verfügbar.</p>
        <label><input type="checkbox" [(ngModel)]="config.useBnetza" (ngModelChange)="sourcesChanged.emit()" [disabled]="disabled"> BNetzA-Ladesäulenregister · 01.09.2026</label>
        <label><input type="checkbox" [(ngModel)]="config.useOsm" (ngModelChange)="sourcesChanged.emit()" [disabled]="disabled"> OSM-Lade- und Gastankstationen</label>
        @if (!config.useBnetza && !config.useOsm) { <p role="alert">Mindestens eine Datenquelle wählen.</p> }
        @if (config.useOsm) {
          <p>Der OSM-Stand wird pro Region gespeichert und wiederverwendet. Aktuelle Region zuerst speichern.</p>
          <label>OSM-Stationssnapshot hochladen (.json)
            <input type="file" accept=".json" [disabled]="disabled || working || !scenario.id" (change)="upload($event)">
          </label>
          <button type="button" [disabled]="disabled || working || !scenario.id" (click)="refresh()">OSM-Quellenstand erneuern</button>
        }
        <p>Es werden noch keine Lade-/Tankhalte oder Reichweiten modelliert. H₂-Daten stammen hier aus OSM; die europäische Register-API ist noch nicht angebunden.</p>
      }
      @if (message) { <p aria-live="polite">{{ message }}</p> }
      @if (config.enabled && quality) {
        <h3>Stationsbestand</h3>
        @for (error of quality.errors; track error) { <p role="alert">{{ error }}</p> }
        <p>{{ quality.stationRecords }} Quellenmeldungen · {{ quality.recordsByAccess.public ?? 0 }} öffentlich bestätigt · {{ quality.recordsByAccess.unknown ?? 0 }} ungeklärt · {{ quality.recordsByAccess.restricted ?? 0 }} beschränkt</p>
        @if (quality.observedRegisterChargingPoints != null) { <p>{{ quality.observedRegisterChargingPoints }} Ladepunkte im BNetzA-Bestand</p> }
        <ul>@for (item of quality.recordsByCarrier | keyvalue; track item.key) { <li>{{ carrierLabel(item.key) }}: {{ item.value }} Meldungen</li> }</ul>
        @for (source of quality.sources; track source.id) {
          <p>{{ source.id === 'bnetza' ? 'BNetzA' : 'OpenStreetMap' }} · {{ sourceDate(source) }} · {{ source.metadata?.license ?? source.license ?? source.status }}</p>
        }
        <p>{{ quality.qualityFlagCounts['possible-cross-source-duplicate'] ?? 0 }} mögliche Mehrfachmeldungen. Ladepunkte werden ausschließlich aus dem BNetzA-Bestand gezählt; Stecker und OSM-Meldungen werden nicht addiert.</p>
        <p>Private und ausdrücklich stillgelegte Stationen sind ausgeschlossen. Datenlücken, Live-Verfügbarkeit, Zufahrt und Fahrzeugeignung bleiben ungeprüft.</p>
        <a [href]="qualityUrl()" target="_blank" rel="noopener">Quellen- und Qualitätsbericht herunterladen</a>
      }
    }
  `,
  styles: [`:host{display:block}label{display:block;margin:.6rem 0}p,li{font-size:.82rem;line-height:1.4;color:#4b5563}h2{font-size:1rem}h3{font-size:.9rem}button,input[type=file]{max-width:100%}[role=alert]{color:#b91c1c}`]
})
export class EnergyInfrastructurePanelComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) scenario!: Scenario;
  @Input() disabled = false;
  @Input() quality: EnergyQuality | null = null;
  @Output() sourcesChanged = new EventEmitter<void>();
  message = ''; working = false;
  private pending?: Subscription;
  constructor(private api: ApiService) { }
  ngOnChanges(changes: SimpleChanges): void {
    if (changes['scenario']) { this.pending?.unsubscribe(); this.message = ''; this.working = false; }
  }
  ngOnDestroy(): void { this.pending?.unsubscribe(); }
  carrierLabel(key: string): string { return ENERGY_LABELS[key as keyof typeof ENERGY_LABELS] ?? key; }
  sourceDate(source: EnergySource): string {
    const date = source.metadata?.referenceDate ?? source.referenceDate;
    return date && date !== 'unknown' ? date : 'Datenstand unbekannt';
  }
  qualityUrl(): string { return this.scenario.id ? this.api.artifactUrl(this.scenario.id, 'energy/quality.json') : ''; }
  upload(event: Event): void {
    const input = event.target as HTMLInputElement; const file = input.files?.[0]; const id = this.scenario.id;
    if (!file || !id || this.disabled || this.working) return;
    this.working = true; this.message = '';
    this.pending = this.api.uploadEnergySnapshot(id, file).subscribe({
      next: result => { this.working = false; this.message = `${result.objects} OSM-Objekte gespeichert. Neu generieren, um den Stationsbestand zu aktualisieren.`; this.sourcesChanged.emit(); input.value = ''; },
      error: err => { this.working = false; this.message = err.error?.detail ?? err.error?.message ?? 'Stationssnapshot konnte nicht geladen werden'; }
    });
  }
  refresh(): void {
    const id = this.scenario.id; if (!id || this.disabled || this.working) return;
    this.working = true; this.message = '';
    this.pending = this.api.deleteEnergySnapshot(id).subscribe({
      next: () => { this.working = false; this.message = 'OSM-Stand freigegeben. Beim nächsten Generieren wird neu abgerufen.'; this.sourcesChanged.emit(); },
      error: err => { this.working = false; this.message = err.error?.detail ?? 'OSM-Stand konnte nicht freigegeben werden'; }
    });
  }
}
