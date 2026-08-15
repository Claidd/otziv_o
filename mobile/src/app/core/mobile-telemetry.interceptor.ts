import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap } from 'rxjs';
import { MobileAuthDiagnosticsService } from './mobile-auth-diagnostics.service';
import { mobileEnvironment } from './mobile-environment';
import { MobileNativeService } from './mobile-native.service';

export const mobileTelemetryInterceptor: HttpInterceptorFn = (request, next) => {
  const apiBaseUrl = mobileEnvironment.apiBaseUrl;
  const isBackendRequest = request.url.startsWith('/api')
    || (apiBaseUrl.length > 0 && request.url.startsWith(apiBaseUrl));
  if (!isBackendRequest) {
    return next(request);
  }
  inject(MobileAuthDiagnosticsService).breadcrumb(
    `http.${request.method.toLowerCase()}:${diagnosticRequestPath(request.url, apiBaseUrl)}`
  );
  const native = inject(MobileNativeService);
  return from(native.accessTelemetryHeaders()).pipe(
    switchMap((headers) => next(Object.keys(headers).length > 0
      ? request.clone({ setHeaders: headers })
      : request))
  );
};

export function diagnosticRequestPath(url: string, apiBaseUrl: string): string {
  try {
    const target = url.startsWith('/')
      ? new URL(url, 'https://mobile.invalid')
      : new URL(url);
    if (!url.startsWith('/') && apiBaseUrl) {
      const api = new URL(apiBaseUrl);
      if (target.origin !== api.origin) {
        return '/external';
      }
    }
    return target.pathname
      .replace(/\/[0-9a-f]{8}-[0-9a-f-]{27,}(?=\/|$)/giu, '/:id')
      .replace(/\/\d+(?=\/|$)/gu, '/:id')
      .slice(0, 96) || '/';
  } catch {
    return '/unknown';
  }
}
