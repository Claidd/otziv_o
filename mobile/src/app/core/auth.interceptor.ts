import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { mobileEnvironment } from './mobile-environment';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const apiBaseUrl = mobileEnvironment.apiBaseUrl;
  const shouldAttachToken = request.url.startsWith('/api') || (apiBaseUrl.length > 0 && request.url.startsWith(apiBaseUrl));

  if (!shouldAttachToken) {
    return next(request);
  }

  return from(auth.getAccessToken()).pipe(
    switchMap((token) => {
      if (!token) {
        return next(request);
      }

      return next(request.clone({
        setHeaders: {
          Authorization: `Bearer ${token}`
        }
      }));
    }),
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        void auth.handleUnauthorized();
      }

      return throwError(() => error);
    })
  );
};
