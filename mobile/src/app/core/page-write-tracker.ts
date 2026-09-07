import { Injectable } from '@angular/core';
import { defer, finalize, Observable, Subject } from 'rxjs';

/** Page-local observation of writes. Leaving never unsubscribes or repeats a command. */
@Injectable()
export class PageWriteTracker {
  private visit = 0;
  private visible = true;
  private destroyed = false;
  private resourceKey: string | undefined;
  private readonly settledAfterReturn = new Subject<void>();
  readonly reconciliationRequired = this.settledAfterReturn.asObservable();

  enter(resourceKey?: string): void {
    if (resourceKey !== this.resourceKey) this.visit += 1;
    this.resourceKey = resourceKey;
    this.visible = true;
  }
  leave(): void { this.visible = false; this.visit += 1; }
  destroy(): void { this.leave(); this.destroyed = true; this.settledAfterReturn.complete(); }

  /** One settlement for a whole command, including sequential requests. */
  begin(): () => void {
    const startedVisit = this.visit;
    const resourceKey = this.resourceKey;
    let settled = false;
    return () => {
      if (settled) return;
      settled = true;
      // A return may have read before this command committed. An interrupted
      // transport cannot prove failure. Never refresh a different resource.
      if (!this.destroyed && this.visible && startedVisit !== this.visit && resourceKey === this.resourceKey) {
        this.settledAfterReturn.next();
      }
    };
  }

  track<T>(request: Observable<T>): Observable<T> {
    return defer(() => request.pipe(finalize(this.begin())));
  }
}
