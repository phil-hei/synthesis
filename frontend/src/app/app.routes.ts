import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./features/studio/studio.component')
      .then(module => module.StudioComponent)
  },
  {
    path: 'vergleich',
    loadComponent: () => import('./features/comparison/comparison.component')
      .then(module => module.ComparisonComponent)
  },
  {
    path: 'einstellungen',
    canDeactivate: [(component: { dirty: boolean }) => !component.dirty || window.confirm('Ungespeicherte Einstellungen verwerfen?')],
    loadComponent: () => import('./features/settings/settings.component')
      .then(module => module.SettingsComponent)
  },
  { path: '**', redirectTo: '' }
];
