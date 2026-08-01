import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { mobileEnvironment } from './mobile-environment';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const apiBaseUrl = mobileEnvironment.apiBaseUrl;
  const requestPath = pathFromRequestUrl(request.url, apiBaseUrl);
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
  const targetsApi = request.url.startsWith('/api')
    || (apiBaseUrl.length > 0 && request.url.startsWith(apiBaseUrl));

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
