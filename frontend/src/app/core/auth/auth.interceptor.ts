import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Eine abgelaufene Sitzung antwortet mit 401. Statt jeden Aufruf einzeln zu behandeln, führt der
 * Interceptor zur Anmeldung zurück; die Anmelde- und Sitzungsendpunkte selbst sind ausgenommen,
 * damit ein falsches Passwort oder eine Sitzungsprüfung keine Schleife auslöst.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && !request.url.startsWith('/api/auth/')) {
        auth.sessionLost();
        if (!router.url.startsWith('/login')) {
          void router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
        }
      }
      return throwError(() => error);
    })
  );
};
