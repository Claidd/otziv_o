import '@angular/compiler';
import type { UrlSegment } from '@angular/router';
import { reviewShortLinkMatcher, routes } from './app.routes';
import { roleGuard } from './core/role.guard';

describe('routes', () => {
  const route = (path: string) => routes.find((candidate) => candidate.path === path);
  const segment = (path: string): UrlSegment => ({ path, parameters: {}, parameterMap: null as never });

  it('lazy-loads main feature routes instead of putting them in the initial bundle', () => {
    [
      '',
      'register-client',
      'pay/success',
      'pay/fail',
      'pay/group/:token',
      'pay/:token',
      'leads',
      'operator',
      'orders/:companyId/:orderId',
      'companies',
      'orders',
      'manager',
      'manager/archive',
      'manager/common-billing',
      'manager/orders/:companyId/:orderId',
      'worker',
      'training',
      'admin/team',
      'admin/score',
      'cabinet/manager-control',
      'admin/analyse',
      'admin/archive',
      'admin/workload-monitor',
      'admin/manager-control/:managerId',
      'admin/bank-payments',
      'admin/common-billing',
      'admin/user-info/:userId',
      'admin/dictionaries/phones',
      'admin/dictionaries',
      'review/editReviews/:orderDetailId',
      'review/c'
    ].forEach((path) => {
      expect(typeof route(path)?.loadComponent).toBe('function');
      expect(route(path)?.component).toBeUndefined();
    });
  });

  it('does not expose the retired legacy migration route', () => {
    expect(route('legacy-migration')).toBeUndefined();
  });

  it('keeps review edit route public', () => {
    const reviewRoute = route('review/editReviews/:orderDetailId');
    const secureReviewRoute = route('review/c');
    const shortReviewRoute = routes.find((candidate) => candidate.matcher === reviewShortLinkMatcher);

    expect(reviewRoute?.canActivate).toBeUndefined();
    expect(reviewRoute?.data).toBeUndefined();
    expect(secureReviewRoute?.canActivate).toBeUndefined();
    expect(secureReviewRoute?.data).toBeUndefined();
    expect(typeof shortReviewRoute?.loadComponent).toBe('function');
    expect(shortReviewRoute?.canActivate).toBeUndefined();
    expect(shortReviewRoute?.data).toBeUndefined();
  });

  it('matches short review links only for UUID-like paths', () => {
    const match = reviewShortLinkMatcher([
      segment('95f890f1-8514-4321-ba6a-0cf9b4c7a9f6')
    ]);

    expect(match?.posParams?.['orderDetailId'].path).toBe('95f890f1-8514-4321-ba6a-0cf9b4c7a9f6');
    expect(reviewShortLinkMatcher([segment('manager')])).toBeNull();
    expect(reviewShortLinkMatcher([
      segment('95f890f1-8514-4321-ba6a-0cf9b4c7a9f6'),
      segment('extra')
    ])).toBeNull();
  });

  it('preserves role protected routes', () => {
    const protectedRoutes = [
      ['leads', ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG']],
      ['operator', ['ADMIN', 'OWNER', 'OPERATOR']],
      ['orders/:companyId/:orderId', ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']],
      ['companies', ['ADMIN', 'OWNER', 'MANAGER']],
      ['orders', ['ADMIN', 'OWNER', 'MANAGER']],
      ['manager', ['ADMIN', 'OWNER', 'MANAGER']],
      ['manager/archive', ['ADMIN', 'OWNER', 'MANAGER']],
      ['manager/common-billing', ['ADMIN', 'OWNER', 'MANAGER']],
      ['worker', ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']],
      ['training', ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']],
      ['cabinet/manager-control', ['MANAGER']],
      ['admin/analyse', ['ADMIN', 'OWNER']],
      ['admin/archive', ['ADMIN', 'OWNER']],
      ['admin/workload-monitor', ['ADMIN', 'OWNER']],
      ['admin/manager-control/:managerId', ['ADMIN', 'OWNER']],
      ['admin/bank-payments', ['ADMIN', 'OWNER']],
      ['admin/common-billing', ['ADMIN', 'OWNER']],
      ['admin/dictionaries/phones', ['ADMIN', 'OWNER']],
      ['admin/dictionaries', ['ADMIN', 'OWNER', 'MANAGER']]
    ];

    protectedRoutes.forEach(([path, roles]) => {
      expect(route(path as string)?.canActivate).toEqual([roleGuard]);
      expect(route(path as string)?.data?.['roles']).toEqual(roles);
    });
  });

  it('keeps the old T-Bank URL as a backwards-compatible redirect', () => {
    expect(route('admin/tbank-payments')?.redirectTo).toBe('admin/bank-payments');
    expect(route('admin/tbank-payments')?.pathMatch).toBe('full');
  });

  it('keeps gamification UI routes disabled and provides a public not-found route', () => {
    expect(route('gamification/progress')).toBeUndefined();
    expect(route('admin/gamification/rewards')).toBeUndefined();

    const notFound = route('**');
    expect(typeof notFound?.loadComponent).toBe('function');
    expect(notFound?.canActivate).toBeUndefined();
  });
});
