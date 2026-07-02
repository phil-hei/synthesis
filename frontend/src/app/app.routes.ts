import { Routes } from '@angular/router';
import { StudioComponent } from './features/studio/studio.component';
import { ComparisonComponent } from './features/comparison/comparison.component';

export const routes: Routes = [
  { path: '', component: StudioComponent },
  { path: 'vergleich', component: ComparisonComponent },
  { path: '**', redirectTo: '' }
];
