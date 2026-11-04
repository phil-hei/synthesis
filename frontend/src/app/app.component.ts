import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="app-header">
      <span class="brand">SyntheSis</span>
      <span class="subtitle">Szenario-Studio – Verkehrsnachfrage für Logistikszenarien (mobiTopp/logiTopp)</span>
      <nav class="nav">
        <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Studio</a>
        <a routerLink="/vergleich" routerLinkActive="active">Vergleich</a>
        <a class="settings-link" routerLink="/einstellungen" routerLinkActive="active" aria-label="Einstellungen" title="Tool-Einstellungen">
          <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="m9 3-.7 2.3-2 .9L4 5.8 2 9.2l1.7 1.8v2L2 14.8l2 3.4 2.3-.4 2 .9L9 21h4l.7-2.3 2-.9 2.3.4 2-3.4-1.7-1.8v-2L20 9.2l-2-3.4-2.3.4-2-.9L13 3Z"/><circle cx="11" cy="12" r="3"/></svg>
        </a>
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
      background: #15314f;
      color: #fff;
    }
    .brand {
      font-size: 20px;
      font-weight: 700;
      letter-spacing: 0.5px;
    }
    .subtitle {
      font-size: 13px;
      opacity: 0.8;
    }
    .nav {
      margin-left: auto;
      display: flex;
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
    .nav a:focus-visible { outline: 2px solid #fff; outline-offset: 2px; }
    @media (max-width: 760px) { .subtitle { display: none; } .app-header { align-items: center; } }
    .nav a.active {
      background: rgba(255, 255, 255, 0.16);
      color: #fff;
    }
  `]
})
export class AppComponent {
}
