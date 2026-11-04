import { provideZoneChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app/app.component';
import { routes } from './app/app.routes';
import { authInterceptor } from './app/core/auth/auth.interceptor';

bootstrapApplication(AppComponent, {
  providers: [
    provideZoneChangeDetection(),
    // Sitzungs-Cookie + CSRF-Cookie vom Backend; Angular hängt den Token an ändernde Requests.
    provideHttpClient(
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
      withInterceptors([authInterceptor])
    ),
    provideRouter(routes)
  ]
}).catch(err => console.error(err));
