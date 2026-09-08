import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { AuthService } from './core/auth.service';
import { authInterceptor } from './core/auth.interceptor';
import { clientApiContractInterceptor } from './core/client-api-contract.interceptor';
import { workerAccountActionCooldownInterceptor } from './core/worker-account-action-cooldown.interceptor';
import { captureReviewCapabilityToken } from './core/review-capability-token';

// Must run before the Keycloak initializer can inspect or normalize the fragment.
captureReviewCapabilityToken();

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([workerAccountActionCooldownInterceptor, authInterceptor, clientApiContractInterceptor])),
    provideAppInitializer(() => inject(AuthService).init())
  ]
};
