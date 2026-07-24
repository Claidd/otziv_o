import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';
import { MobileNativeService } from './mobile-native.service';

export const mobileTelemetryInterceptor: HttpInterceptorFn = (request, next) => {
  const apiBaseUrl = mobileEnvironment.apiBaseUrl;
  const isBackendRequest = request.url.startsWith('/api')
    || (apiBaseUrl.length > 0 && request.url.startsWith(apiBaseUrl));
  if (!isBackendRequest) {
    return next(request);
  }
  const native = inject(MobileNativeService);
  return from(native.accessTelemetryHeaders()).pipe(
    switchMap((headers) => next(Object.keys(headers).length > 0
      ? request.clone({ setHeaders: headers })
      : request))
  );
};
