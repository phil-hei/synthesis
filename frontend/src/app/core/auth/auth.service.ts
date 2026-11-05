import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, of, tap } from 'rxjs';

export type Role = 'ADMIN' | 'USER';

export interface SessionUser {
  username: string;
  displayName: string;
  roles: Role[];
}

export interface UserAccount {
  username: string;
  displayName: string;
  roles: Role[];
  createdAt?: string;
  lastLoginAt?: string | null;
}

/**
 * Sitzungszustand der Plattform. Das Backend hält eine Cookie-Session; dieser Dienst kennt nur,
 * ob und als wer die aktuelle Sitzung angemeldet ist, und hält das als Signal für Guards,
 * Header und Seiten bereit.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  /** `undefined` = noch nicht geprüft, `null` = nicht angemeldet. */
  private readonly session = signal<SessionUser | null | undefined>(undefined);

  readonly user = computed(() => this.session() ?? null);
  readonly checked = computed(() => this.session() !== undefined);
  readonly loggedIn = computed(() => !!this.session());
  readonly isAdmin = computed(() => this.session()?.roles.includes('ADMIN') ?? false);

  /** Prüft die Sitzung beim Backend; 401 bedeutet schlicht „nicht angemeldet“. */
  refresh(): Observable<SessionUser | null> {
    return this.http.get<SessionUser>('/api/auth/me').pipe(
      tap(user => this.session.set(user)),
      catchError(() => {
        this.session.set(null);
        return of(null);
      })
    );
  }

  login(username: string, password: string): Observable<SessionUser> {
    return this.http.post<SessionUser>('/api/auth/login', { username, password })
      .pipe(tap(user => this.session.set(user)));
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', {}).pipe(tap(() => this.session.set(null)));
  }

  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http.post<void>('/api/auth/password', { currentPassword, newPassword });
  }

  /** Wird vom Interceptor bei einer abgelaufenen Sitzung aufgerufen. */
  sessionLost(): void {
    this.session.set(null);
  }
}
