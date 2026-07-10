import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { mobileEnvironment } from './mobile-environment';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const apiBaseUrl = mobileEnvironment.apiBaseUrl;
  const requestPath = pathFromRequestUrl(request.url, apiBaseUrl);
  const isPublicApi = requestPath.startsWith('/api/payments/public')
    || requestPath.startsWith('/api/auth/')
    || requestPath.startsWith('/api/review-check/');
  const shouldAttachToken = !isPublicApi && (
    request.url.startsWith('/api') || (apiBaseUrl.length > 0 && request.url.startsWith(apiBaseUrl))
  );

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
        return from(auth.refreshTokens()).pipe(
          switchMap((refreshed) => {
            if (!refreshed) {
              void auth.handleUnauthorized(false);
              return throwError(() => error);
            }

            return from(auth.getAccessToken()).pipe(
              switchMap((retryToken) => {
                if (!retryToken) {
                  void auth.handleUnauthorized(false);
                  return throwError(() => error);
                }

                return next(request.clone({
                  setHeaders: {
                    Authorization: `Bearer ${retryToken}`
                  }
                }));
              })
            );
          })
        );
      }

      return throwError(() => error);
    })
  );
};

function pathFromRequestUrl(url: string, apiBaseUrl: string): string {
  if (url.startsWith('/')) {
    return url;
  }

  if (apiBaseUrl.length > 0 && url.startsWith(apiBaseUrl)) {
    return url.slice(apiBaseUrl.length) || '/';
  }

  try {
    return new URL(url, window.location.origin).pathname;
  } catch {
    return url;
  }
}
