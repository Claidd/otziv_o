import { DefaultUrlSerializer, NavigationCancel, NavigationEnd, NavigationError, NavigationSkipped, NavigationStart, PRIMARY_OUTLET, Router } from '@angular/router';

/** Router navigation starts before an asynchronously loaded Ionic page emits willLeave. */
export function watchPublicPaymentNavigation(
  router: Router,
  group: boolean,
  token: () => string | null,
  active: () => boolean,
  leave: () => void,
  enter: () => void
) {
  const serializer = new DefaultUrlSerializer();
  const isCurrentPaymentPath = (url: string) => {
    const segments = serializer.parse(url).root.children[PRIMARY_OUTLET]?.segments.map(segment => segment.path) ?? [];
    const expected = group ? ['pay', 'group', token()] : ['pay', token()];
    return !!token() && segments.length === expected.length && segments.every((segment, index) => segment === expected[index]);
  };
  return router.events.subscribe(event => {
    if (event instanceof NavigationStart && active() && !isCurrentPaymentPath(event.url)) {
      leave();
    } else if ((event instanceof NavigationEnd || event instanceof NavigationCancel
      || event instanceof NavigationError || event instanceof NavigationSkipped)
      && !active() && isCurrentPaymentPath(router.url)) {
      // A cancelled departure gets a new read/session; an old command is never replayed or revived.
      enter();
    }
  });
}
