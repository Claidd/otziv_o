import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { SKIP_AUTH_REDIRECT_ON_401, SKIP_AUTH_TOKEN } from './auth-http-context';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router, { optional: true });
  const shouldAttachToken = req.url.startsWith('/api') && !req.context.get(SKIP_AUTH_TOKEN);

  if (!shouldAttachToken) {
    return next(req);
  }

  return from(auth.getToken()).pipe(
    switchMap((token) => {
      if (!token) {
        return next(req);
      }

      return next(req.clone({
        setHeaders: {
          Authorization: `Bearer ${token}`
        }
      }));
    }),
    catchError((error) => {
      if (isManagerReportReviewRequired(error)) {
        void router?.navigate(['/'], {
          queryParams: { reportReviewRequired: '1' }
        });
        return throwError(() => error);
      }
      if (isForbidden(error) && auth.isAuthenticated()) {
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

function isForbidden(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 403;
}

function isManagerReportReviewRequired(error: unknown): boolean {
  return error instanceof HttpErrorResponse
    && error.status === 423
    && error.error?.code === 'MANAGER_REPORT_REVIEW_REQUIRED';
}

function isExpiredForbidden(error: unknown, auth: AuthService): boolean {
  return error instanceof HttpErrorResponse && error.status === 403 && !auth.isAuthenticated();
}

function currentBrowserPath(): string {
  return `${window.location.pathname}${window.location.search}${window.location.hash}` || '/';
}
