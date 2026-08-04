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
    .nav a.active {
      background: rgba(255, 255, 255, 0.16);
      color: #fff;
    }
  `]
})
export class AppComponent {
}
