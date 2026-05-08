import { DestroyRef, Signal, signal } from '@angular/core';

const DEFAULT_MOBILE_ACTION_BOTTOM = 'calc(env(safe-area-inset-bottom) + 0.75rem)';

export function mobileKeyboardActionBottom(destroyRef: DestroyRef): Signal<string> {
  const bottom = signal(DEFAULT_MOBILE_ACTION_BOTTOM);

  if (typeof window === 'undefined') {
    return bottom;
  }

  const viewport = window.visualViewport;
  const update = (): void => {
    const keyboardOffset = viewport
      ? Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop)
      : 0;

    bottom.set(`calc(${Math.round(keyboardOffset)}px + env(safe-area-inset-bottom) + 0.75rem)`);
  };

  update();
  window.addEventListener('resize', update);
  viewport?.addEventListener('resize', update);
  viewport?.addEventListener('scroll', update);

  destroyRef.onDestroy(() => {
    window.removeEventListener('resize', update);
    viewport?.removeEventListener('resize', update);
    viewport?.removeEventListener('scroll', update);
  });

  return bottom;
}
