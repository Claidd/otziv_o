import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const shouldAttachToken = req.url.startsWith('/api');

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
      if (isUnauthorized(error) || isExpiredForbidden(error, auth)) {
        auth.handleUnauthorized(currentBrowserPath());
      }

      return throwError(() => error);
    })
  );
};

function isUnauthorized(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 401;
}

function isExpiredForbidden(error: unknown, auth: AuthService): boolean {
  return error instanceof HttpErrorResponse && error.status === 403 && !auth.isAuthenticated();
}

function currentBrowserPath(): string {
  return `${window.location.pathname}${window.location.search}${window.location.hash}` || '/';
}
