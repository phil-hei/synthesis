import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { AppComponent } from './app.component';
import { AuthService } from './core/auth/auth.service';

describe('AppComponent', () => {
  const user = signal<{ username: string; displayName: string; roles: string[] } | null>(null);
  const isAdmin = signal(false);
  const auth = { user, isAdmin, logout: vi.fn(() => of(undefined)) };

  beforeEach(async () => {
    user.set(null);
    isAdmin.set(false);
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }]
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
    expect(element.querySelector('.user-chip')).toBeNull();
  });

  it('shows the signed-in user, the admin link and a logout action', () => {
    user.set({ username: 'philipp', displayName: 'Philipp', roles: ['ADMIN'] });
    isAdmin.set(true);
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.user-chip')?.textContent).toContain('Philipp');
    expect(element.querySelector('a.user-link')?.getAttribute('href')).toBe('/benutzer');
    (element.querySelector('button.logout') as HTMLButtonElement).click();
    expect(auth.logout).toHaveBeenCalled();
  });
});
