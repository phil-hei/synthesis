import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService, Role, UserAccount } from '../../core/auth/auth.service';

interface NewUser { username: string; displayName: string; password: string; role: Role; }

/** Benutzerverwaltung für Administratoren: Konten anlegen, Rolle setzen, Passwort zurücksetzen. */
@Component({
  selector: 'app-users',
  imports: [FormsModule, DatePipe],
  templateUrl: './users.component.html',
  styleUrl: './users.component.css'
})
export class UsersComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly auth = inject(AuthService);
  readonly users = signal<UserAccount[]>([]);
  readonly error = signal('');
  readonly notice = signal('');
  readonly busy = signal(false);
  draft: NewUser = { username: '', displayName: '', password: '', role: 'USER' };
  resetTarget = '';
  resetPassword = '';
  ownCurrent = '';
  ownNew = '';

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.http.get<UserAccount[]>('/api/users').subscribe({
      next: list => this.users.set(list),
      error: err => this.error.set(this.messageOf(err, 'Benutzerliste konnte nicht geladen werden.'))
    });
  }

  create(): void {
    if (this.busy()) return;
    this.busy.set(true); this.error.set(''); this.notice.set('');
    this.http.post<UserAccount>('/api/users', this.draft).subscribe({
      next: user => {
        this.busy.set(false);
        this.notice.set(`Konto ${user.username} angelegt.`);
        this.draft = { username: '', displayName: '', password: '', role: 'USER' };
        this.reload();
      },
      error: err => { this.busy.set(false); this.error.set(this.messageOf(err, 'Konto konnte nicht angelegt werden.')); }
    });
  }

  setRole(user: UserAccount, role: Role): void {
    this.http.put<UserAccount>(`/api/users/${encodeURIComponent(user.username)}/role`, { role }).subscribe({
      next: () => { this.notice.set(`Rolle von ${user.username} auf ${role} gesetzt.`); this.reload(); },
      error: err => this.error.set(this.messageOf(err, 'Rolle konnte nicht geändert werden.'))
    });
  }

  remove(user: UserAccount): void {
    if (!window.confirm(`Konto "${user.username}" löschen? Szenarien bleiben erhalten.`)) return;
    this.http.delete<void>(`/api/users/${encodeURIComponent(user.username)}`).subscribe({
      next: () => { this.notice.set(`Konto ${user.username} gelöscht.`); this.reload(); },
      error: err => this.error.set(this.messageOf(err, 'Konto konnte nicht gelöscht werden.'))
    });
  }

  reset(): void {
    if (!this.resetTarget || !this.resetPassword) return;
    this.http.put<void>(`/api/users/${encodeURIComponent(this.resetTarget)}/password`, { password: this.resetPassword }).subscribe({
      next: () => { this.notice.set(`Passwort von ${this.resetTarget} zurückgesetzt.`); this.resetTarget = ''; this.resetPassword = ''; },
      error: err => this.error.set(this.messageOf(err, 'Passwort konnte nicht zurückgesetzt werden.'))
    });
  }

  changeOwnPassword(): void {
    if (!this.ownCurrent || !this.ownNew) return;
    this.auth.changePassword(this.ownCurrent, this.ownNew).subscribe({
      next: () => { this.notice.set('Eigenes Passwort geändert.'); this.ownCurrent = ''; this.ownNew = ''; },
      error: err => this.error.set(this.messageOf(err, 'Passwort konnte nicht geändert werden.'))
    });
  }

  private messageOf(err: { error?: { message?: string; detail?: string }; message?: string }, fallback: string): string {
    return err?.error?.detail ?? err?.error?.message ?? fallback;
  }
}
