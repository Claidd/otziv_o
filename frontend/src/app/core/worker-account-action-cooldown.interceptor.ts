import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, defer, finalize, shareReplay, tap, throwError } from 'rxjs';
import { WorkerAccountActionCooldownService } from './worker-account-action-cooldown.service';

/** Only explicit review-account mutations; credential reads and navigation are unaffected. */
export function isWorkerAccountAction(method: string, url: string): boolean {
  if (method !== 'POST') return false;
  const path = url.split('?')[0];
  return /^\/api\/worker\/(?:reviews|recovery-tasks|bad-review-tasks)\/\d+\/(?:change-bot|bots\/\d+\/deactivate)$/.test(path)
    || /^\/api\/manager\/orders\/\d+\/reviews\/\d+\/(?:change-bot|new-account|bots\/\d+\/deactivate)$/.test(path)
    || /^\/api\/manager\/orders\/\d+\/bad-review-tasks\/\d+\/change-bot$/.test(path);
}

export const workerAccountActionCooldownInterceptor: HttpInterceptorFn = (request, next) => {
  if (!isWorkerAccountAction(request.method, request.url)) return next(request);
  const cooldown = inject(WorkerAccountActionCooldownService);
  return defer(() => {
    if (!cooldown.isSpecialist()) return next(request);
    const attempt = cooldown.begin();
    if (!attempt) return throwError(() => cooldown.blockedError());
    return next(request).pipe(
      tap((event) => {
        if (event instanceof HttpResponse) cooldown.receive(attempt, event.headers);
      }),
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse) cooldown.receive(attempt, error.headers, error.error);
        return throwError(() => error);
      }),
      finalize(() => cooldown.finish(attempt))
    );
  }).pipe(
    // The request and its cooldown belong to the application, even if its card/page is destroyed.
    shareReplay({ bufferSize: 1, refCount: false })
  );
};
