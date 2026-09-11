import { inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, NavigationError, Router } from '@angular/router';
import { filter, take } from 'rxjs';

/** Keep the independent loading screen until the first route has actually loaded. */
export function watchAppStartup(): void {
  inject(Router).events.pipe(
    filter((event) => event instanceof NavigationEnd || event instanceof NavigationError),
    take(1),
    takeUntilDestroyed()
  ).subscribe((event) => {
    window.dispatchEvent(new Event(
      event instanceof NavigationEnd ? 'otziv:startup-ready' : 'otziv:startup-error'
    ));
  });
}

export function reportAppStartupError(): void {
  window.dispatchEvent(new Event('otziv:startup-error'));
}
