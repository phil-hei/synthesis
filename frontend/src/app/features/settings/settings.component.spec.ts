import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { SettingsComponent } from './settings.component';
import { ToolSettings, ToolSettingsState } from './tool-settings.service';

const initial: ToolSettings = {
  sumo: { enabled: true, binDir: '/sumo/bin', sumoHome: '/sumo/share/sumo', timeoutMinutes: 15 },
  llm: { enabled: true, endpoint: 'http://localhost:11434/v1', model: 'local-model', temperature: 0.7, timeoutSeconds: 120, maxPersonas: 60 },
  actitopp: { enabled: true, projectDir: '/actitopp', timeoutMinutes: 20 },
  simulation: { enabled: true, projectDir: '/logitopp', timeoutMinutes: 30 },
  transit: { enabled: true, venvPython: '', gtfs2pt: '/sumo/tools/gtfs2pt.py', timeoutMinutes: 25 },
  mobileCity: { enabled: true, python: 'python3', script: 'validate.py', dotnet: 'dotnet', toolSource: '/configtool', nugetCache: '', timeoutMinutes: 20 }
};
function state(settings = initial, restartRequired = false): ToolSettingsState {
  return { settings: structuredClone(settings), active: structuredClone(initial), restartRequired };
}
describe('SettingsComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SettingsComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  async function loaded() {
    const fixture = TestBed.createComponent(SettingsComponent); fixture.detectChanges();
    http.expectOne('/api/settings/tools').flush(state()); fixture.detectChanges();
    await fixture.whenStable(); fixture.detectChanges(); return fixture;
  }
  it('loads global values, renders the sections and requires valid edits before save', async () => {
    const fixture = await loaded(); const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelectorAll('section.card')).toHaveLength(6);
    const button = element.querySelector('button[type=submit]') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    const model = element.querySelector('#llm-model') as HTMLInputElement;
    expect(model.value).toBe('local-model');
    model.value = ''; model.dispatchEvent(new Event('input')); fixture.detectChanges();
    await fixture.whenStable(); fixture.detectChanges();
    expect(button.disabled).toBe(true);
    model.value = 'another-model'; model.dispatchEvent(new Event('input')); fixture.detectChanges();
    await fixture.whenStable(); fixture.detectChanges();
    expect(button.disabled).toBe(false); expect(fixture.componentInstance.dirty).toBe(true);
    fixture.componentInstance.save();
    const request = http.expectOne('/api/settings/tools'); expect(request.request.method).toBe('PUT');
    expect(request.request.body.llm.model).toBe('another-model');
    request.flush(state(request.request.body, true)); fixture.detectChanges();
    expect(element.textContent).toContain('Backend-Neustart erforderlich');
    expect(fixture.componentInstance.dirty).toBe(false);
  });
  it('keeps failed saves editable and discards only unsaved edits', async () => {
    const fixture = await loaded(); const component = fixture.componentInstance;
    component.change('sumo', 'binDir', '/changed'); component.save();
    http.expectOne('/api/settings/tools').flush({}, { status: 500, statusText: 'Failed' }); fixture.detectChanges();
    expect(component.dirty).toBe(true); expect(component.saved).toBe(false);
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('Speichern fehlgeschlagen');
    component.load(); http.expectOne('/api/settings/tools').flush(state()); fixture.detectChanges();
    expect(component.settings?.sumo.binDir).toBe('/sumo/bin'); expect(component.dirty).toBe(false);
  });
  it('shows a retry action after a load error', () => {
    const fixture = TestBed.createComponent(SettingsComponent); fixture.detectChanges();
    http.expectOne('/api/settings/tools').flush({}, { status: 503, statusText: 'Unavailable' }); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Erneut laden');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });
  it('restores a pending restart after reload and protects unsaved edits on window close', async () => {
    const fixture = await loaded(); const component = fixture.componentInstance;
    component.load(); http.expectOne('/api/settings/tools').flush(state(initial, true)); fixture.detectChanges();
    expect(component.restartRequired).toBe(true);
    component.change('llm', 'model', 'edited');
    const event = new Event('beforeunload', { cancelable: true });
    component.beforeUnload(event as BeforeUnloadEvent); expect(event.defaultPrevented).toBe(true);
  });
});
