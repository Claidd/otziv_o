import {
  APP_PRIMARY_NAVIGATION,
  appNavigationGroupForUrl,
  appNavigationLinksForGroup,
  appNavigationRouteForGroup,
  isAppNavigationGroupRootUrl,
  visibleAppNavigationLinks
} from './app-navigation';

describe('app navigation registry', () => {
  it('filters primary tabs by role', () => {
    const visiblePrimary = (roles: string[]) => visibleAppNavigationLinks(roles, APP_PRIMARY_NAVIGATION)
      .map((link) => link.id);

    expect(visiblePrimary(['MARKETOLOG'])).toEqual(['home', 'leads']);
    expect(visiblePrimary(['WORKER'])).toEqual(['home', 'worker']);
    expect(visiblePrimary(['OPERATOR'])).toEqual(['home', 'operator']);
    expect(visiblePrimary(['MANAGER'])).toEqual(['home', 'leads', 'companies', 'orders', 'worker']);
    expect(visiblePrimary(['ADMIN'])).toEqual(['home', 'leads', 'companies', 'orders', 'worker', 'operator']);
  });

  it('keeps performer in the worker group but targets the performer route', () => {
    expect(visibleAppNavigationLinks(['PERFORMER'], APP_PRIMARY_NAVIGATION).map((link) => link.id)).toEqual([
      'home',
      'worker'
    ]);
    expect(appNavigationRouteForGroup('worker', ['PERFORMER'])).toBe('/performer');
    expect(appNavigationRouteForGroup('worker', ['ADMIN', 'PERFORMER'])).toBe('/worker');
  });

  it('maps nested canonical routes to their active bottom tab', () => {
    expect(appNavigationGroupForUrl('/orders/12/34?mobileNav=menu')).toBe('orders');
    expect(appNavigationGroupForUrl('/manager/orders/12/34')).toBe('orders');
    expect(appNavigationGroupForUrl('/admin/common-billing')).toBe('orders');
    expect(appNavigationGroupForUrl('/manager/archive')).toBe('companies');
    expect(appNavigationGroupForUrl('/worker/risk')).toBe('worker');
    expect(appNavigationGroupForUrl('/admin/analyse')).toBe('home');
    expect(isAppNavigationGroupRootUrl('orders', '/orders/12/34', ['MANAGER'])).toBe(false);
    expect(isAppNavigationGroupRootUrl('orders', '/orders?status=new', ['MANAGER'])).toBe(true);
    expect(isAppNavigationGroupRootUrl('worker', '/performer', ['PERFORMER'])).toBe(true);
  });

  it('provides role-aware home subdivisions including separate cabinet and analytics', () => {
    const adminSections = appNavigationLinksForGroup('home', ['ADMIN']).map((link) => link.id);
    expect(adminSections).toEqual(expect.arrayContaining([
      'personal-cabinet',
      'analytics',
      'team',
      'score',
      'dictionaries',
      'tbank',
      'manager-control',
      'users'
    ]));
    expect(appNavigationLinksForGroup('home', ['MANAGER']).map((link) => link.id)).toEqual(expect.arrayContaining([
      'personal-cabinet',
      'team',
      'manager-control-self'
    ]));
    expect(appNavigationLinksForGroup('home', ['OWNER']).map((link) => link.id)).not.toContain('manager-control-self');
    expect(adminSections.indexOf('manager-control')).toBeLessThan(adminSections.indexOf('team'));
  });
});
