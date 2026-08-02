import { Observable, Observer, Subscription } from 'rxjs';

/**
 * Owns one route-scoped read subscription. Starting a newer read aborts the
 * previous one, while the generation check also rejects re-entrant stale
 * notifications from a source that emits synchronously during replacement.
 *
 * Keep mutation subscriptions outside this helper: navigating away may stop
 * waiting for a read response, but it must not imply that a write was undone.
 */
export class LatestRouteRequest<T> {
  private generation = 0;
  private subscription: Subscription | null = null;

  start(source: Observable<T>, observer: Partial<Observer<T>>): void {
    const generation = ++this.generation;
    const previous = this.subscription;
    this.subscription = null;
    previous?.unsubscribe();

    const subscription = source.subscribe({
      next: (value) => {
        if (this.generation === generation) {
          observer.next?.(value);
        }
      },
      error: (error) => {
        if (this.generation === generation) {
          this.subscription = null;
          observer.error?.(error);
        }
      },
      complete: () => {
        if (this.generation === generation) {
          this.subscription = null;
          observer.complete?.();
        }
      }
    });

    if (this.generation === generation && !subscription.closed) {
      this.subscription = subscription;
    } else {
      subscription.unsubscribe();
    }
  }

  cancel(): void {
    this.generation += 1;
    const current = this.subscription;
    this.subscription = null;
    current?.unsubscribe();
  }
}
