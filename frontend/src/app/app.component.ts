import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth/auth.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="app-header">
      <a class="brand" routerLink="/">SyntheSis</a>
      <span class="subtitle">Szenario-Studio – Verkehrsnachfrage für Logistikszenarien (mobiTopp/logiTopp)</span>
      <nav class="nav" aria-label="Hauptnavigation">
        <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Studio</a>
        <a routerLink="/vergleich" routerLinkActive="active">Vergleich</a>
        <a class="settings-link" routerLink="/einstellungen" routerLinkActive="active" aria-label="Einstellungen" title="Tool-Einstellungen">
          <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="m9 3-.7 2.3-2 .9L4 5.8 2 9.2l1.7 1.8v2L2 14.8l2 3.4 2.3-.4 2 .9L9 21h4l.7-2.3 2-.9 2.3.4 2-3.4-1.7-1.8v-2L20 9.2l-2-3.4-2.3.4-2-.9L13 3Z"/><circle cx="11" cy="12" r="3"/></svg>
        </a>
        @if (auth.user(); as user) {
          <span class="user-chip" [title]="user.roles.join(', ')">
            <span class="avatar" aria-hidden="true">{{ initial(user.displayName || user.username) }}</span>
            <span class="user-name">{{ user.displayName || user.username }}</span>
            @if (auth.isAdmin()) {
              <a class="user-link" routerLink="/benutzer" routerLinkActive="active">Benutzer</a>
            }
            <button type="button" class="logout" (click)="logout()" title="Abmelden">Abmelden</button>
          </span>
        }
      </nav>
    </header>
    <router-outlet />
  `,
  styles: [`
    .app-header {
      display: flex;
      align-items: baseline;
      gap: 16px;
      padding: 10px 20px;
      background: var(--sy-primary);
      color: #fff;
    }
    .brand {
      font-size: 20px;
      font-weight: 700;
      letter-spacing: 0.5px;
      color: #fff;
      text-decoration: none;
    }
    .subtitle {
      font-size: 13px;
      opacity: 0.8;
    }
    .nav {
      margin-left: auto;
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .nav a {
      color: #cfdcea;
      text-decoration: none;
      font-size: 13px;
      padding: 5px 12px;
      border-radius: 7px;
    }
    .settings-link { display: inline-flex; align-items: center; }
    .nav a:hover { background: rgba(255, 255, 255, 0.1); }
    .nav a:focus-visible, .logout:focus-visible { outline: 2px solid #fff; outline-offset: 2px; }
    .nav a.active {
      background: rgba(255, 255, 255, 0.16);
      color: #fff;
    }
    .user-chip {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      margin-left: 10px;
      padding: 3px 4px 3px 3px;
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.1);
      font-size: 12px;
    }
    .avatar {
      display: grid;
      place-items: center;
      width: 24px;
      height: 24px;
      border-radius: 50%;
      background: #fff;
      color: var(--sy-primary);
      font-weight: 700;
      font-size: 12px;
    }
    .user-name { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .nav a.user-link { padding: 3px 8px; }
    .logout {
      border: 0;
      border-radius: 999px;
      padding: 4px 10px;
      background: rgba(255, 255, 255, 0.16);
      color: #fff;
      font: inherit;
      font-size: 12px;
      cursor: pointer;
    }
    .logout:hover { background: rgba(255, 255, 255, 0.28); }
    @media (max-width: 760px) {
      .subtitle { display: none; }
      .app-header { align-items: center; flex-wrap: wrap; gap: 8px; padding: 8px 12px; }
      .user-name { display: none; }
    }
  `]
})
export class AppComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  initial(name: string): string {
    return (name || '?').trim().charAt(0).toUpperCase();
  }

  logout(): void {
    this.auth.logout().subscribe({
      next: () => void this.router.navigate(['/login']),
      error: () => void this.router.navigate(['/login'])
    });
  }
}
