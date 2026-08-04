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
  { path: '**', redirectTo: '' }
];
