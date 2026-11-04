import { Component, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { catchError, forkJoin, of, Subscription } from 'rxjs';
import { Scenario, GenerationJob } from '../../core/models/scenario.model';
import { canonicalJson, emptyOperations, emptyVehicleConfiguration, EnergyNetworkReport, VehicleEnergyLedger, NativeVehicleChainReport } from '../../core/models/vehicle-operations.model';
import { ApiService } from '../../core/services/api.service';

@Component({
  selector: 'app-energy-operations-panel', imports: [CommonModule, FormsModule],
  template: `
    <h3>Fahrzeugzuordnungen und Stationsprüfung</h3>
    @if (scenario.energyInfrastructure; as energy) {
      <label><input type="checkbox" [(ngModel)]="energy.verifyNetworkAccess" (ngModelChange)="changed()"
        [disabled]="disabled || ((!energy.enabled || !sumoSelected) && !energy.verifyNetworkAccess)"> Stationszufahrten im SUMO-Netz prüfen (experimentell)</label>
      @if (energy.verifyNetworkAccess) {
        <label>Maximaler Abstand zur Straßenkante (m)
          <input type="number" min="1" max="300" [(ngModel)]="energy.maxSnapDistanceM" (ngModelChange)="changed()" [disabled]="disabled">
        </label>
        <p>Prüft gerichtete Zu-/Abfahrtsrouten, Fahrspuren und bekannte Zugangs-/Maßregeln. Nähe beweist keine physische Einfahrt; ungeklärte Regeln und Lkw-Eignung bleiben sichtbar.</p>
      }
    }
    @if (scenario.vehicleOperations; as operations) {
      <label><input type="checkbox" [(ngModel)]="operations.enabled" (ngModelChange)="operationsChanged()"
        [disabled]="disabled"> Fahrzeuge ihren Fahrten zuordnen (experimentell)</label>
      @if (operations.enabled) {
        <label><input type="checkbox" [(ngModel)]="operations.chainTrips" (ngModelChange)="changed()" [disabled]="disabled"> Zugeordnete Fahrten als durchgängige SUMO-Fahrzeugketten simulieren</label>
        @if (operations.chainTrips) {
          <p>Ein Fahrzeug fährt alle zugeordneten Etappen und bleibt dazwischen an der Straßenposition. Bedienzeiten aus Zustelltouren und geplante Abfahrten bestimmen die Halte; Verspätungen wirken auf Folgefahrten. Ortsbrüche brechen die Kette mit einem Fehlerbericht ab.</p>
        } @else {
        <p>SUMO führt einzelne Fahrtsegmente aus. Die externe Energiebilanz nutzt ausdrücklich vorgegebenen Verbrauch und Anfangsenergie; unbekannte Werte bleiben unbekannt.</p>
        <label>Reserveanteil (0–1)<input type="number" min="0" max="1" step="0.01" [(ngModel)]="operations.reserveFraction" (ngModelChange)="changed()" [disabled]="disabled"></label>
        }
        <p>{{ operations.bindings.length }} Fahrtzuordnungen. Zuerst ohne Zuordnungen generieren, Fahrtschlüssel herunterladen, dann die Fahrzeug-/Fahrtkonfiguration importieren und neu generieren.</p>
        <button type="button" [disabled]="disabled || working" (click)="download()">Aktuelle Konfiguration herunterladen</button>
        <a href="/api/vehicle-operations/freight-example" target="_blank" rel="noopener">Beispiel für eine Lieferwagen-Fahrzeugkette</a>
        <p>Startort ist die Straßenposition der ersten zugeordneten Fahrt. Es entstehen keine zusätzlichen Fahrzeuge oder automatisch ergänzten Leerfahrten. Lade-/Tankhalte und Energiegrenzen steuern den Verkehr nicht.</p>
        <a [href]="artifactUrl('vehicles/trip-identities.json')" target="_blank" rel="noopener">Fahrtschlüssel herunterladen</a>
      }
    }
    <label title="Fahrzeugtypen und konkrete Fahrzeuge für Unternehmenstouren laden; Fahrtzuordnungen sind optional; ihre Simulation benötigt SUMO">Fahrzeugkatalog und optionale Fahrtzuordnungen importieren (.json)
      <input type="file" accept=".json" [disabled]="disabled || working" (change)="upload($event)">
    </label>
    @if (!sumoSelected) { <p>Der Fahrzeugkatalog steht Unternehmenstouren auch ohne SUMO zur Verfügung. Native Fahrzeug- und Stationsprüfungen benötigen SUMO.</p> }
    @if (message) { <p aria-live="polite">{{ message }}</p> }
    @if (networkReport; as network) {
      <h4>Stations-Netzprüfung des letzten Laufs</h4>
      @if (network.status === 'failed') { <p role="alert">{{ network.message }}</p> }
      @else {
      <p>{{ network.stationRecords }} Stationsmeldungen · {{ network.profileChecks }} Fahrzeugprofil-Prüfungen</p>
      <p>{{ network.mappedProfileChecks }} mit geprüften gerichteten Routen · {{ network.blockedProfileChecks }} gesperrt · {{ network.unmappedProfileChecks }} ohne bestätigte Zuordnung</p>
      <p>Routennachweis und unbekannte Zufahrt/Fahrzeugeignung bleiben getrennt. Es wird keine nutzbare Stationskapazität bescheinigt.</p>
      }
      <a [href]="artifactUrl('energy/network-access.json')" target="_blank" rel="noopener">Zufahrts- und Regelbericht</a>
    }
    @if (chains; as report) {
      <h4>Fahrzeugketten des letzten Laufs</h4>
      @if (report.status === 'failed') { <p role="alert">{{ report.message }}</p> }
      @else if (report.phase !== 'executed') { <p>{{ report.physicalVehicles ?? 0 }} Fahrzeugketten vorbereitet; noch keine abgeschlossene Auswertung.</p> }
      @else {
        <p>Ausführungsprüfung: {{ report.status === 'complete' ? 'vollständig' : report.status === 'incomplete' ? 'unvollständig' : 'Prüfung erforderlich' }}</p>
        <p>{{ report.assignedSegments }} Fahrtsegmente mit {{ report.physicalVehicles }} nativen Fahrzeugen · {{ report.completedSegments }} Segmente beendet</p>
        <p>{{ report.unfinishedSegments }} am Tagesende unterwegs · {{ report.notDepartedSegments }} noch nicht gestartet · {{ report.unassignedSegments }} weitere Segmente ohne Fahrzeugzuordnung</p>
        <ul>@for (vehicle of report.vehicles; track vehicle.vehicleId) {
          <li>{{ vehicle.vehicleId }}: {{ vehicle.segments.length }} Etappen · {{ vehicle.nativeCompleted ? 'Fahrzeugkette beendet' : 'Fahrzeugkette nicht beendet' }} · {{ vehicle.teleports == null ? 'Teleportzahl unbekannt' : vehicle.teleports + ' Teleports' }}</li>
        }</ul>
      }
      <a [href]="artifactUrl('vehicles/native-chains.json')" target="_blank" rel="noopener">Fahrzeugketten und tatsächliche Etappenzeiten</a>
    }
    @if (!scenario.vehicleOperations?.chainTrips && ledger; as report) {
      <h4>Fahrzeugbilanz des letzten Laufs</h4>
      @if (report.status === 'pending') { <p>Noch keine abgeschlossene Auswertung der Fahrzeugzustände.</p> }
      @else {
        <p>{{ report.assignedSegments }} von {{ report.selectedSegments }} Fahrtsegmenten zugeordnet · {{ report.knownEnergySegments ?? 0 }} mit berechenbarer Energiebilanz</p>
        <p>{{ report.issues }} Prüfhinweise · {{ report.bindingsNotSelected?.length ?? 0 }} Zuordnungen nicht im ausgewählten Tag/Verkehrsbedarf gefunden</p>
        <ul>@for (vehicle of report.vehicles; track vehicle.vehicleId) {
          <li>{{ vehicle.vehicleId }}: {{ vehicle.balanceAfter == null ? 'Energie unbekannt' : (vehicle.balanceAfter | number:'1.0-3') + ' ' + vehicle.unit }} · {{ vehicle.stateValid ? 'Bilanz konsistent' : 'prüfpflichtig' }}</li>
        }</ul>
      }
      <a [href]="artifactUrl('vehicles/trip-identities.json')" target="_blank" rel="noopener">Fahrtschlüssel herunterladen</a>
      <a [href]="artifactUrl('vehicles/energy-ledger.json')" target="_blank" rel="noopener">Fahrzeug- und Energiebilanz herunterladen</a>
    }
  `,
  styles: [`:host{display:block;border-top:1px solid #e2e8f0;margin-top:1rem;padding-top:.5rem}label,a{display:block;margin:.6rem 0}p,li{font-size:.82rem;line-height:1.4;color:#4b5563}h3{font-size:.95rem}h4{font-size:.85rem}input[type=number],button{max-width:100%}`]
})
export class EnergyOperationsPanelComponent implements OnChanges, OnDestroy {
  @Input({required:true}) scenario!: Scenario;
  @Input() disabled = false;
  @Input() jobState: GenerationJob['state'] | null = null;
  @Input() inventoryAvailable = false;
  @Output() configurationChanged = new EventEmitter<void>();
  ledger: VehicleEnergyLedger | null = null;
  networkReport: EnergyNetworkReport | null = null;
  chains: NativeVehicleChainReport | null = null;
  message = ''; working = false;
  private pending?: Subscription;
  private epoch = 0;
  constructor(private api: ApiService) {}
  get sumoSelected(): boolean { return this.scenario.targetModels.includes('sumo'); }
  ngOnChanges(changes: SimpleChanges): void {
    this.pending?.unsubscribe(); this.epoch++; this.working = false; this.ledger = null; this.networkReport = null; this.chains = null;
    if (changes['scenario']) { this.message = ''; this.working = false; }
    if (!this.disabled) this.load();
  }
  ngOnDestroy(): void { this.pending?.unsubscribe(); this.epoch++; }
  changed(): void {
    this.pending?.unsubscribe(); this.epoch++; this.working = false; this.ledger = null; this.networkReport = null; this.chains = null; this.configurationChanged.emit();
  }
  operationsChanged(): void {
    if (this.scenario.vehicleOperations && !this.scenario.vehicleOperations.enabled) this.scenario.vehicleOperations.chainTrips = false;
    this.changed();
  }
  private bundle() {
    return { vehicleConfiguration: this.scenario.vehicleConfiguration ?? emptyVehicleConfiguration(), vehicleOperations: this.scenario.vehicleOperations ?? emptyOperations() };
  }
  private fingerprint(): string {
    return canonicalJson({id: this.scenario.id, region: this.scenario.region, year: this.scenario.referenceYear, energy: this.scenario.energyInfrastructure, ...this.bundle()});
  }
  artifactUrl(path: string): string { return this.scenario.id ? this.api.artifactUrl(this.scenario.id, path) : ''; }
  private load(): void {
    const id = this.scenario.id, operations = this.scenario.vehicleOperations;
    const checkNetwork = this.inventoryAvailable && this.scenario.energyInfrastructure?.verifyNetworkAccess;
    if (!id || this.jobState === 'RUNNING' || this.jobState === 'QUEUED' || (!operations?.enabled && !checkNetwork)) return;
    const fingerprint = this.fingerprint(), epoch = this.epoch;
    this.pending = forkJoin({
      preview: this.api.validateVehicleOperations(this.bundle()),
      ledger: operations?.enabled ? this.api.artifactJson<VehicleEnergyLedger>(id, 'vehicles/energy-ledger.json').pipe(catchError(() => of(null))) : of(null),
      chains: operations?.enabled && operations.chainTrips ? this.api.artifactJson<NativeVehicleChainReport>(id, 'vehicles/native-chains.json').pipe(catchError(() => of(null))) : of(null),
      network: checkNetwork ? this.api.artifactJson<EnergyNetworkReport>(id, 'energy/network-access.json').pipe(catchError(() => of(null))) : of(null)
    }).subscribe({next: ({preview, ledger, network, chains}) => {
      if (epoch !== this.epoch || fingerprint !== this.fingerprint() || !preview.valid) return;
      if (ledger?.configurationSha256 === preview.operationsSha256 && ledger.vehicleConfigurationSha256 === preview.vehicleConfigurationSha256) this.ledger = ledger;
      if (chains?.configurationSha256 === preview.operationsSha256 && chains.vehicleConfigurationSha256 === preview.vehicleConfigurationSha256) this.chains = chains;
      if ((network?.status === 'completed' || network?.status === 'failed') && network.vehicleConfigurationSha256 === preview.vehicleConfigurationSha256
        && canonicalJson(network.configuration) === canonicalJson(this.scenario.energyInfrastructure)
        && canonicalJson(network.region) === canonicalJson(this.scenario.region) && network.referenceYear === this.scenario.referenceYear) this.networkReport = network;
    }, error: () => { /* No artifact is not evidence of zero infrastructure or zero consumption. */ }});
  }
  async upload(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement, file = input.files?.[0];
    if (!file || this.disabled || this.working) return;
    if (file.size > 2_000_000) { this.message = 'Konfiguration überschreitet 2 MB'; return; }
    this.pending?.unsubscribe(); const epoch = ++this.epoch; this.working = true; this.message = '';
    try {
      const bundle = JSON.parse(await file.text()); if (epoch !== this.epoch) return;
      this.pending = this.api.validateVehicleOperations(bundle).subscribe({next: preview => {
        if (epoch !== this.epoch) return; this.working = false;
        if (!preview.valid) { this.message = preview.errors.map(issue => `${issue.path}: ${issue.message}`).join('; '); return; }
        this.scenario.vehicleConfiguration = preview.vehicleConfiguration; this.scenario.vehicleOperations = preview.vehicleOperations;
        this.message = 'Geprüfte Konfiguration übernommen. Szenario speichern und neu generieren.'; input.value = ''; this.changed();
      }, error: err => { this.working = false; this.message = err.error?.detail ?? err.error?.errors?.[0]?.message ?? 'Konfiguration konnte nicht geprüft werden'; }});
    } catch { if (epoch === this.epoch) { this.working = false; this.message = 'Ungültige JSON-Datei'; } }
  }
  download(): void {
    const url = URL.createObjectURL(new Blob([JSON.stringify(this.bundle(), null, 2)], {type:'application/json'}));
    const link = document.createElement('a'); link.href = url; link.download = 'fahrzeuge-und-fahrtzuordnungen.json'; link.click(); URL.revokeObjectURL(url);
  }
}
