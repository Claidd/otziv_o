/**
 * Background refresh loop for status screens.
 *
 * - The delay is chosen again before every run (so callers can poll fast
 *   while something is in progress and back off in steady or failing states).
 * - Nothing is requested while the page is hidden; one refresh runs as soon as
 *   the page becomes visible again.
 * - A null delay stops the loop.
 */
export class VisibilityAwarePoller {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private active = false;
  private dueWhileHidden = false;

  private readonly onVisibilityChange = (): void => {
    if (!this.active || !this.dueWhileHidden || pageHidden()) {
      return;
    }
    this.dueWhileHidden = false;
    this.run();
  };

  constructor(
    private readonly tick: () => void,
    private readonly nextDelayMs: () => number | null
  ) {}

  get running(): boolean {
    return this.active;
  }

  start(): void {
    if (this.active) {
      return;
    }
    this.active = true;
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', this.onVisibilityChange);
    }
    this.schedule();
  }

  /** Recomputes the next delay, e.g. after a response changed the state. */
  reschedule(): void {
    if (!this.active || this.dueWhileHidden) {
      return;
    }
    this.clearTimer();
    this.schedule();
  }

  stop(): void {
    this.active = false;
    this.dueWhileHidden = false;
    this.clearTimer();
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
    }
  }

  private schedule(): void {
    const delay = this.nextDelayMs();
    if (delay === null) {
      this.stop();
      return;
    }
    this.timer = setTimeout(() => {
      this.timer = null;
      if (!this.active) {
        return;
      }
      if (pageHidden()) {
        this.dueWhileHidden = true;
        return;
      }
      this.run();
    }, Math.max(0, delay));
  }

  private run(): void {
    this.tick();
    if (this.active && this.timer === null) {
      this.schedule();
    }
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}

/** Delay that doubles with every consecutive failure, capped. */
export function backoffDelayMs(baseMs: number, failures: number, maxMs: number): number {
  return Math.min(maxMs, baseMs * 2 ** Math.max(0, Math.min(failures, 16)));
}

function pageHidden(): boolean {
  return typeof document !== 'undefined' && document.visibilityState === 'hidden';
}
