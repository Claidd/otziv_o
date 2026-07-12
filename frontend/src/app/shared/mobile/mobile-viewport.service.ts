import { DestroyRef, Injectable, inject, signal } from '@angular/core';

export const MOBILE_LAYOUT_MAX_WIDTH = 860;

@Injectable({ providedIn: 'root' })
export class MobileViewportService {
  private readonly destroyRef = inject(DestroyRef);
  private readonly mobileState = signal(false);

  readonly mobile = this.mobileState.asReadonly();

  constructor() {
    if (typeof window === 'undefined') {
      return;
    }

    if (typeof window.matchMedia === 'function') {
      const mediaQuery = window.matchMedia(`(max-width: ${MOBILE_LAYOUT_MAX_WIDTH}px)`);
      const update = (event: MediaQueryListEvent | MediaQueryList): void => this.mobileState.set(event.matches);
      update(mediaQuery);
      mediaQuery.addEventListener('change', update);
      this.destroyRef.onDestroy(() => mediaQuery.removeEventListener('change', update));
      return;
    }

    const update = (): void => this.mobileState.set(window.innerWidth <= MOBILE_LAYOUT_MAX_WIDTH);
    update();
    window.addEventListener('resize', update, { passive: true });
    this.destroyRef.onDestroy(() => window.removeEventListener('resize', update));
  }
}
