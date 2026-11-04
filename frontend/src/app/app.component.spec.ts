import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([])]
    }).compileComponents();
  });

  it('renders the application shell and navigation', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.brand')?.textContent).toContain('SyntheSis');
    expect(element.querySelectorAll('nav a').length).toBe(3);
    expect(element.querySelector('a[aria-label="Einstellungen"]')?.getAttribute('href')).toBe('/einstellungen');
    expect(element.querySelector('router-outlet')).not.toBeNull();
  });
});
