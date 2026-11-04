import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  template: `
    <main class="login-page">
      <section class="login-card" aria-labelledby="login-title">
        <div class="brand-row">
          <span class="logo" aria-hidden="true">S</span>
          <div>
            <h1 id="login-title">SyntheSis</h1>
            <p class="tagline">Szenario-Studio für Verkehrs- und Logistiknachfrage</p>
          </div>
        </div>
        <form (ngSubmit)="submit()" #form="ngForm" novalidate>
          <label for="login-user">Benutzername</label>
          <input id="login-user" name="username" type="text" autocomplete="username" required
            [(ngModel)]="username" [disabled]="busy()" autofocus>
          <label for="login-password">Passwort</label>
          <input id="login-password" name="password" type="password" autocomplete="current-password" required
            [(ngModel)]="password" [disabled]="busy()">
          @if (error()) {
            <p class="error" role="alert">{{ error() }}</p>
          }
          <button class="primary" type="submit" [disabled]="busy() || form.invalid">
            {{ busy() ? 'Anmeldung läuft …' : 'Anmelden' }}
          </button>
        </form>
        <p class="hint">Zugänge vergibt die Administration dieser Installation. Beim ersten Start steht das
          Startpasswort des Kontos <code>admin</code> im Backend-Log und unter
          <code>&lt;data-dir&gt;/initial-admin-password.txt</code>.</p>
      </section>
    </main>
  `,
  styles: `
    :host { display: block; }
    .login-page { min-height: calc(100vh - 45px); display: grid; place-items: center; padding: 24px 16px;
      background: radial-gradient(circle at 20% 10%, #e3ecf7 0, var(--sy-bg) 45%); }
    .login-card { width: min(420px, 100%); background: var(--sy-surface); border: 1px solid var(--sy-border);
      border-radius: var(--sy-radius-lg); padding: 30px 28px; box-shadow: var(--sy-shadow-lg); }
    .brand-row { display: flex; align-items: center; gap: 14px; margin-bottom: 22px; }
    .logo { display: grid; place-items: center; width: 44px; height: 44px; border-radius: 12px;
      background: var(--sy-primary); color: #fff; font-weight: 800; font-size: 22px; }
    h1 { margin: 0; font-size: 22px; color: var(--sy-text-strong); }
    .tagline { margin: 2px 0 0; font-size: 12px; color: var(--sy-text-muted); }
    form { display: grid; gap: 6px; }
    label { font-size: 12px; font-weight: 600; color: var(--sy-text-muted); margin-top: 8px; }
    input { padding: 10px 12px; border: 1px solid var(--sy-border-strong); border-radius: var(--sy-radius);
      background: #fbfcfe; font: inherit; font-size: 14px; color: var(--sy-text); }
    input:focus { outline: none; border-color: var(--sy-accent); box-shadow: 0 0 0 3px var(--sy-focus); }
    button.primary { margin-top: 16px; padding: 11px 14px; border: 0; border-radius: var(--sy-radius);
      background: var(--sy-primary); color: #fff; font: inherit; font-weight: 600; cursor: pointer; }
    button.primary:disabled { opacity: .55; cursor: default; }
    .error { margin: 8px 0 0; padding: 8px 10px; border-radius: var(--sy-radius); background: #fcebec;
      color: #9b2632; font-size: 13px; }
    .hint { margin: 18px 0 0; font-size: 11px; line-height: 1.5; color: var(--sy-text-muted); }
    code { font-size: 11px; }
  `
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  username = '';
  password = '';
  readonly busy = signal(false);
  readonly error = signal('');

  submit(): void {
    if (this.busy() || !this.username || !this.password) {
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.auth.login(this.username.trim(), this.password).subscribe({
      next: () => {
        this.busy.set(false);
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
        void this.router.navigateByUrl(returnUrl && returnUrl.startsWith('/') && !returnUrl.startsWith('/login') ? returnUrl : '/');
      },
      error: err => {
        this.busy.set(false);
        this.password = '';
        this.error.set(err?.status === 401 || err?.status === 403
          ? 'Benutzername oder Passwort ist falsch.'
          : err?.status === 429
            ? (err.error?.message || 'Zu viele Fehlversuche. Bitte später erneut versuchen.')
            : 'Anmeldung derzeit nicht möglich. Backend-Verbindung prüfen.');
      }
    });
  }
}
