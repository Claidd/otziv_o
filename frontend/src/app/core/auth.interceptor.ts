import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { OPTIONAL_AUTH_TOKEN, SKIP_AUTH_REDIRECT_ON_401, SKIP_AUTH_TOKEN } from './auth-http-context';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router, { optional: true });
  const shouldAttachToken = req.url.startsWith('/api') && !req.context.get(SKIP_AUTH_TOKEN);
  const optionalAuth = req.context.get(OPTIONAL_AUTH_TOKEN);
  let optionalTokenAttached = false;

  if (!shouldAttachToken) {
    return next(req);
  }

  const token = optionalAuth
    ? Promise.resolve(auth.getOptionalToken())
    : auth.getToken();

  return from(token).pipe(
    switchMap((token) => {
      if (!token) {
        return next(req);
      }

      optionalTokenAttached = optionalAuth;

      return next(req.clone({
        setHeaders: {
          Authorization: `Bearer ${token}`
        }
      }));
    }),
    catchError((error) => {
      if (optionalAuth && optionalTokenAttached && isUnauthorized(error)) {
        // The capability carried by the URL remains valid independently of a
        // stale/revoked login. Retry once without Authorization and let the
        // controller expose its anonymous public-link permissions.
        return next(req);
      }
      if (isManagerReportReviewRequired(error)) {
        void router?.navigate(['/'], {
          queryParams: { reportReviewRequired: '1' }
        });
        return throwError(() => error);
      }
      if (isRefreshableForbidden(error) && auth.isAuthenticated()) {
        return from(auth.refreshToken(-1)).pipe(
          switchMap((refreshed) => {
            if (!refreshed) {
              return throwError(() => error);
            }
            return from(auth.getToken()).pipe(
              switchMap((token) => next(token
                ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
                : req
              ))
            );
          })
        );
      }
      if (!req.context.get(SKIP_AUTH_REDIRECT_ON_401) && (isUnauthorized(error) || isExpiredForbidden(error, auth))) {
        auth.handleUnauthorized(currentBrowserPath());
      }

      return throwError(() => error);
    })
  );
};

function isUnauthorized(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 401;
}

function isManagerReportReviewRequired(error: unknown): boolean {
  return error instanceof HttpErrorResponse
    && error.status === 423
    && error.error?.code === 'MANAGER_REPORT_REVIEW_REQUIRED';
}

function isRefreshableForbidden(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse) || error.status !== 403) {
    return false;
  }
  const code = typeof error.error?.code === 'string' ? error.error.code.toUpperCase() : '';
  if (code === 'AUTH_TOKEN_STALE' || code === 'TOKEN_EXPIRED' || code === 'STALE_TOKEN') {
    return true;
  }
  return /\binvalid_token\b/i.test(error.headers.get('WWW-Authenticate') ?? '');
}

function isExpiredForbidden(error: unknown, auth: AuthService): boolean {
  return error instanceof HttpErrorResponse && error.status === 403 && !auth.isAuthenticated();
}

function currentBrowserPath(): string {
  return `${window.location.pathname}${window.location.search}${window.location.hash}` || '/';
}
