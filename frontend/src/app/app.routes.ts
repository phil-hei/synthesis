import { Routes } from '@angular/router';
import { adminGuard, authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login/login.component')
      .then(module => module.LoginComponent)
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/studio/studio.component')
      .then(module => module.StudioComponent)
  },
  {
    path: 'vergleich',
    canActivate: [authGuard],
    loadComponent: () => import('./features/comparison/comparison.component')
      .then(module => module.ComparisonComponent)
  },
  {
    path: 'einstellungen',
    canActivate: [authGuard],
    canDeactivate: [(component: { dirty: boolean }) => !component.dirty || window.confirm('Ungespeicherte Einstellungen verwerfen?')],
    loadComponent: () => import('./features/settings/settings.component')
      .then(module => module.SettingsComponent)
  },
  {
    path: 'benutzer',
    canActivate: [adminGuard],
    loadComponent: () => import('./features/users/users.component')
      .then(module => module.UsersComponent)
  },
  { path: '**', redirectTo: '' }
];
