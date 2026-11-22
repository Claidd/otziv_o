import { WritableSignal, signal } from '@angular/core';
import { EMPTY, Observable, Subject, catchError, defer, filter, finalize, takeUntil } from 'rxjs';

/** How a write ended after its view had been left; see {@link DictionaryViewScope.write}. */
export type WriteOutcomeAfterLeaving<T> = { failed: false; value: T | undefined } | { failed: true; error: unknown };

/** Cancels obsolete reads while allowing an already submitted command to settle exactly once locally. */
export class DictionaryViewScope {
  private generation = 0;
  private destroyed = false;
  private readonly reads = new Map<string, Subject<void>>();
  readonly writing = signal(false);

  /**
   * @param onLateWriteEnd called when a write ends after its view was left and the view is active again (another tab
   *   and back, a hidden browser tab shown again): the read started on return was cancelled by the write or skipped
   *   while it ran, so the list must be read again or it keeps the rows from before the write.
   */
  constructor(private readonly isActive: () => boolean, private readonly onLateWriteEnd?: () => void) {}

  active(): boolean {
    return !this.destroyed && this.isActive();
  }

  cancel(key: string): void {
    const cancel = this.reads.get(key);
    cancel?.next();
    cancel?.complete();
  }

  deactivate(): void {
    this.generation++;
    for (const cancel of this.reads.values()) {
      cancel.next();
      cancel.complete();
    }
    this.reads.clear();
  }

  destroy(): void {
    this.destroyed = true;
    this.deactivate();
  }

  read<T>(key: string, request: Observable<T>, busy?: WritableSignal<boolean>): Observable<T> {
    return defer(() => {
      if (!this.active()) return EMPTY;
      this.reads.get(key)?.next();
      const cancel = new Subject<void>();
      this.reads.set(key, cancel);
      const generation = this.generation;
      busy?.set(true);
      return request.pipe(
        takeUntil(cancel),
        filter(() => this.current(generation)),
        catchError((error) =>
          this.current(generation)
            ? new Observable<never>((subscriber) => subscriber.error(error))
            : EMPTY
        ),
        finalize(() => {
          if (this.reads.get(key) === cancel) {
            this.reads.delete(key);
            busy?.set(false);
          }
          cancel.complete();
        })
      );
    });
  }

  /**
   * Hiding/destroying a view never aborts or replays a write; obsolete UI callbacks are suppressed. `afterLeaving`
   * still learns how the command ended when its view was left meanwhile (another tab, another page, a hidden
   * browser tab), so a failed or finished save never disappears without a trace.
   */
  write<T>(
    request: Observable<T>,
    busy: WritableSignal<boolean>,
    afterLeaving?: (outcome: WriteOutcomeAfterLeaving<T>) => void
  ): Observable<T> {
    const generation = this.generation;
    return new Observable<T>((subscriber) => {
      let last: T | undefined;
      let delivered = false;
      // A response loaded before a command must not later overwrite its result/form.
      for (const key of this.reads.keys()) this.cancel(key);
      this.writing.set(true);
      const subscription = request.subscribe({
        next: (value) => {
          // Reads started while this command was in flight may still contain pre-commit data.
          // Cancel them before allowing its success handler to issue a fresh read.
          for (const key of this.reads.keys()) this.cancel(key);
          last = value;
          if (this.current(generation)) {
            delivered = true;
            subscriber.next(value);
          }
        },
        error: (error) => {
          busy.set(false);
          this.writing.set(false);
          if (this.current(generation)) subscriber.error(error);
          else {
            subscriber.unsubscribe();
            afterLeaving?.({ failed: true, error });
            if (this.active()) this.onLateWriteEnd?.();
          }
        },
        complete: () => {
          busy.set(false);
          this.writing.set(false);
          if (this.current(generation)) subscriber.complete();
          else {
            subscriber.unsubscribe();
            if (!delivered) afterLeaving?.({ failed: false, value: last });
            if (this.active()) this.onLateWriteEnd?.();
          }
        }
      });
      return () => subscription.unsubscribe();
    });
  }

  private current(generation: number): boolean {
    return generation === this.generation && this.active();
  }
}
