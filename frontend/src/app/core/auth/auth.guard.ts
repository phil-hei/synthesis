import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

/** Lässt nur angemeldete Sitzungen auf das Studio; sonst Weiterleitung zur Anmeldung. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const toLogin = () => router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  if (auth.checked()) {
    return auth.loggedIn() ? true : toLogin();
  }
  return auth.refresh().pipe(map(user => (user ? true : toLogin())));
};

/** Nur Administratoren verwalten Benutzerkonten. */
export const adminGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const decide = () => (auth.isAdmin() ? true : router.createUrlTree(['/']));
  if (auth.checked()) {
    return auth.loggedIn() ? decide() : router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  }
  return auth.refresh().pipe(map(user => (user ? decide() : router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } }))));
};
