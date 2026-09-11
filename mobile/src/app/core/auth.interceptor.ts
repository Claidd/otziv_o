import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { AuthTemporarilyUnavailableError } from './auth.models';
import { mobileEnvironment } from './mobile-environment';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const apiBaseUrl = mobileEnvironment.apiBaseUrl;
  const requestPath = pathFromRequestUrl(request.url, apiBaseUrl) ?? '';
  const anonymousRequest = request.clone({
    headers: request.headers.delete('Authorization')
  });
  const isOptionalReviewApi = requestPath === '/api/review-check'
    || requestPath.startsWith('/api/review-check/');
  const isBestEffortLogoutRevoke = requestPath === '/api/mobile/push-token/revoke';
  const isAlwaysAnonymousApi = requestPath.startsWith('/api/payments/public')
    || requestPath === '/api/auth'
    || requestPath.startsWith('/api/auth/')
    || requestPath === '/api/review-capability'
    || requestPath.startsWith('/api/review-capability/')
    || requestPath.startsWith('/api/mobile-update');
  const targetsApi = requestPath === '/api' || requestPath?.startsWith('/api/') === true;

  if (!targetsApi || isAlwaysAnonymousApi) {
    return next(isAlwaysAnonymousApi ? anonymousRequest : request);
  }

  if (isBestEffortLogoutRevoke) {
    const cachedToken = auth.getOptionalAccessToken(0);
    return next(cachedToken
      ? anonymousRequest.clone({ setHeaders: { Authorization: `Bearer ${cachedToken}` } })
      : anonymousRequest
    );
  }

  if (isOptionalReviewApi) {
    const cachedToken = auth.getOptionalAccessToken();
    if (!cachedToken) {
      return next(anonymousRequest);
    }

    return next(anonymousRequest.clone({
      setHeaders: {
        Authorization: `Bearer ${cachedToken}`
      }
    })).pipe(
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 401) {
          // A stale/revoked cached session must not break the public link.
          // Retry once without Authorization and do not refresh/logout/redirect.
          return next(anonymousRequest);
        }
        if (
          error instanceof HttpErrorResponse
          && error.status === 423
          && error.error?.code === 'MANAGER_REPORT_REVIEW_REQUIRED'
        ) {
          void router.navigate(['/tabs/home/profile'], {
            queryParams: { reportReviewRequired: '1' }
          });
        }
        return throwError(() => error);
      })
    );
  }

  let requestAccessToken: string | null = null;
  return from(auth.getAccessToken()).pipe(
    switchMap((token) => {
      requestAccessToken = token;
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
        // This response belongs to the credentials used for this request. A
        // different login or refresh must not be revoked by a late old 401.
        if ((auth.tokens()?.accessToken ?? null) !== requestAccessToken) return throwError(() => error);
        return from(auth.refreshTokens()).pipe(
          switchMap((refreshed) => {
            if (refreshed.status === 'temporary-unavailable') {
              return throwError(() => new AuthTemporarilyUnavailableError());
            }
            if (refreshed.status === 'superseded') {
              return throwError(() => error);
            }
            if (refreshed.status === 'session-invalid') {
              void auth.handleUnauthorized(false, null);
              return throwError(() => error);
            }

            // The cached token may be time-valid but the server just rejected
            // it. A failed refresh does not justify retrying that same token.
            if (!refreshed.refreshed) {
              return throwError(() => new AuthTemporarilyUnavailableError());
            }

            // Refresh credentials for subsequent actions, but never replay a
            // write whose server-side outcome this transport cannot establish.
            if (request.method !== 'GET' && request.method !== 'HEAD') {
              return throwError(() => error);
            }
            const retryToken = auth.getOptionalAccessToken(0);
            if (!retryToken) {
              return throwError(() => error);
            }
            return next(request.clone({
              setHeaders: { Authorization: `Bearer ${retryToken}` }
            })).pipe(
              catchError((retryError: unknown) => {
                if (retryError instanceof HttpErrorResponse && retryError.status === 401) {
                  void auth.handleUnauthorized(false, retryToken);
                }
                return throwError(() => retryError);
              })
            );
          })
        );
      }

      if (
        error instanceof HttpErrorResponse
        && error.status === 423
        && error.error?.code === 'MANAGER_REPORT_REVIEW_REQUIRED'
      ) {
        void router.navigate(['/tabs/home/profile'], {
          queryParams: { reportReviewRequired: '1' }
        });
      }

      return throwError(() => error);
    })
  );
};

function pathFromRequestUrl(url: string, apiBaseUrl: string): string | null {
  if (url.startsWith('/')) {
    if (url.startsWith('//')) {
      return null;
    }
    try {
      return new URL(url, 'https://mobile.invalid').pathname;
    } catch {
      return null;
    }
  }

  if (!apiBaseUrl) {
    return null;
  }

  try {
    const base = new URL(apiBaseUrl);
    const target = new URL(url);
    if (target.origin !== base.origin) {
      return null;
    }
    const basePath = base.pathname.replace(/\/+$/u, '');
    if (basePath && target.pathname !== basePath && !target.pathname.startsWith(`${basePath}/`)) {
      return null;
    }
    return target.pathname.slice(basePath.length) || '/';
  } catch {
    return null;
  }
}
