import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { StudioComponent } from './studio.component';

describe('StudioComponent', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<StudioComponent>> | null;
  let api: {
    listScenarios: ReturnType<typeof vi.fn>;
    scenarioDefaults: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    fixture = null;
    api = {
      listScenarios: vi.fn(() => of([])),
      scenarioDefaults: vi.fn(() => of(null))
    };

    await TestBed.configureTestingModule({
      imports: [StudioComponent],
      providers: [{ provide: ApiService, useValue: api }]
    }).compileComponents();
  });

  afterEach(() => fixture?.destroy());

  it('renders the migrated studio shell and initializes its map', () => {
    fixture = TestBed.createComponent(StudioComponent);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(api.listScenarios).toHaveBeenCalledOnce();
    expect(api.scenarioDefaults).toHaveBeenCalledOnce();
    expect(element.querySelector('#map')).not.toBeNull();
    expect(element.querySelector('#map')?.classList).toContain('leaflet-container');
  });
});
