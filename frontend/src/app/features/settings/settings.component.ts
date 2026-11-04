import { Component, DestroyRef, HostListener, inject, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ToolSettings, ToolSettingsService, ToolSettingsState } from './tool-settings.service';

type Group = keyof ToolSettings;
interface Field { key: string; label: string; hint?: string; type?: 'number' | 'url'; min?: number; max?: number; step?: number; optional?: boolean; }
interface Section { key: Group; title: string; description: string; fields: Field[]; }
const timeout: Field = { key: 'timeoutMinutes', label: 'Zeitlimit (Minuten)', type: 'number', min: 1, max: 1440 };

@Component({
  selector: 'app-settings', imports: [FormsModule],
  templateUrl: './settings.component.html', styleUrl: './settings.component.css'
})
export class SettingsComponent implements OnInit {
  private readonly api = inject(ToolSettingsService);
  private readonly destroyRef = inject(DestroyRef);
  settings: ToolSettings | null = null;
  private savedSettings = '';
  loading = false;
  saving = false;
  restartRequired = false;
  error = '';
  saved = false;
  readonly sections: Section[] = [
    { key: 'sumo', title: 'SUMO', description: 'Lokale Laufzeit für die Verkehrssimulation.', fields: [
      { key: 'binDir', label: 'Binärverzeichnis', hint: 'Verzeichnis mit sumo, netconvert und duarouter.' },
      { key: 'sumoHome', label: 'SUMO_HOME', hint: 'Daten- und Toolverzeichnis, üblicherweise share/sumo. gtfs2pt unten separat anpassen.' }, timeout] },
    { key: 'llm', title: 'Sprachmodell', description: 'Für GTA-Aktivitätenpläne und LLM-Moduswahl. Ein OpenAI-kompatibler Dienst wie Ollama muss separat laufen.', fields: [
      { key: 'endpoint', label: 'API-Basis-URL', type: 'url', hint: 'Zum Beispiel http://localhost:11434/v1 · ohne abschließenden Schrägstrich. Aktuell ohne API-Key-Authentifizierung.' },
      { key: 'model', label: 'Modellname', hint: 'Exakter Name eines beim Dienst verfügbaren Modells, z. B. qwen2.5:7b-instruct. Installiert oder lädt kein Modell.' },
      { key: 'temperature', label: 'Temperatur', type: 'number', min: 0, max: 2, step: 0.1 },
      { key: 'timeoutSeconds', label: 'Zeitlimit je Anfrage (Sekunden)', type: 'number', min: 1, max: 3600 },
      { key: 'maxPersonas', label: 'Maximale Personas je Lauf', type: 'number', min: 1, max: 10000 }] },
    { key: 'transit', title: 'Python & ÖV', description: 'Python-Umgebung für die GTFS-Verarbeitung und SUMO-Busfahrpläne.', fields: [
      { key: 'venvPython', label: 'Python-Interpreter des Transit-venv', optional: true, hint: 'Benötigt pandas, rtree und pyproj. Leer bedeutet: SUMO-ÖV nicht verfügbar.' },
      { key: 'gtfs2pt', label: 'gtfs2pt.py', hint: 'Vollständiger Pfad zu SUMO/tools/import/gtfs/gtfs2pt.py.' }, timeout] },
    { key: 'actitopp', title: 'actiTopp', description: 'Adapter für die Erzeugung von Aktivitätenplänen.', fields: [
      { key: 'projectDir', label: 'Adapter-Verzeichnis', hint: 'installDist-Ausgabe des SyntheSis-actiTopp-Adapters.' }, timeout] },
    { key: 'simulation', title: 'logiTopp', description: 'Adapter für die Nachfragesimulation.', fields: [
      { key: 'projectDir', label: 'Adapter-Verzeichnis', hint: 'installDist-Ausgabe des SyntheSis-logiTopp-Adapters.' }, timeout] },
    { key: 'mobileCity', title: 'MobileCity-Prüfung', description: 'Lokale Python- und .NET-Werkzeuge für die native Paketprüfung.', fields: [
      { key: 'python', label: 'Python-Interpreter' }, { key: 'script', label: 'Validierungsskript' },
      { key: 'dotnet', label: '.NET-Laufzeit' }, { key: 'toolSource', label: 'ConfigTool-Quellverzeichnis' },
      { key: 'nugetCache', label: 'NuGet-Cache', optional: true }, timeout] }
  ];
  ngOnInit() { this.load(); }
  get dirty() { return this.settings !== null && JSON.stringify(this.settings) !== this.savedSettings; }
  load() {
    if (this.loading || this.saving) return;
    this.loading = true; this.error = '';
    this.api.load().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: state => { this.accept(state); this.loading = false; },
      error: () => { this.error = 'Einstellungen konnten nicht geladen werden. Bitte erneut versuchen.'; this.loading = false; }
    });
  }
  value(group: Group, key: string): string | number | boolean {
    return (this.settings![group] as unknown as Record<string, string | number | boolean>)[key];
  }
  change(group: Group, key: string, value: string | number | boolean) {
    (this.settings![group] as unknown as Record<string, string | number | boolean>)[key] = value;
    this.saved = false;
  }
  save() {
    if (!this.settings || this.saving || !this.dirty) return;
    this.saving = true; this.error = ''; this.saved = false;
    this.api.save(this.settings).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: state => { this.accept(state); this.saving = false; this.saved = true; },
      error: error => { this.error = error.error?.detail ?? 'Speichern fehlgeschlagen. Bitte Eingaben und Backend-Verbindung prüfen.'; this.saving = false; }
    });
  }
  private accept(state: ToolSettingsState) {
    this.settings = structuredClone(state.settings);
    this.savedSettings = JSON.stringify(this.settings);
    this.restartRequired = state.restartRequired;
  }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent) { if (this.dirty) event.preventDefault(); }
}
