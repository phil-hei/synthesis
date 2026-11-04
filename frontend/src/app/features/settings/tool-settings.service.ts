import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

interface Tool { enabled: boolean; timeoutMinutes: number; }
export interface ToolSettings {
  sumo: Tool & { binDir: string; sumoHome: string };
  llm: { enabled: boolean; endpoint: string; model: string; temperature: number; timeoutSeconds: number; maxPersonas: number };
  actitopp: Tool & { projectDir: string };
  simulation: Tool & { projectDir: string };
  transit: Tool & { venvPython: string; gtfs2pt: string };
  mobileCity: Tool & { python: string; script: string; dotnet: string; toolSource: string; nugetCache: string };
}
export interface ToolSettingsState { settings: ToolSettings; active: ToolSettings; restartRequired: boolean; }

@Injectable({ providedIn: 'root' })
export class ToolSettingsService {
  private readonly http = inject(HttpClient);
  load() { return this.http.get<ToolSettingsState>('/api/settings/tools'); }
  save(settings: ToolSettings) { return this.http.put<ToolSettingsState>('/api/settings/tools', settings); }
}
