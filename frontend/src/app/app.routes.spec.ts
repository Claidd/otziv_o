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
      'legacy-migration',
      'leads',
      'operator',
      'operator/phones',
      'manager',
      'manager/orders/:companyId/:orderId',
      'worker',
      'admin/team',
      'admin/score',
      'admin/analyse',
      'admin/user-info/:userId',
      'admin/dictionaries',
      'review/editReviews/:orderDetailId'
    ].forEach((path) => {
      expect(typeof route(path)?.loadComponent).toBe('function');
      expect(route(path)?.component).toBeUndefined();
    });
  });

  it('keeps review edit route public', () => {
    const reviewRoute = route('review/editReviews/:orderDetailId');
    const shortReviewRoute = routes.find((candidate) => candidate.matcher === reviewShortLinkMatcher);

    expect(reviewRoute?.canActivate).toBeUndefined();
    expect(reviewRoute?.data).toBeUndefined();
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
      ['operator/phones', ['ADMIN', 'OWNER']],
      ['manager', ['ADMIN', 'OWNER', 'MANAGER']],
      ['worker', ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']],
      ['admin/analyse', ['ADMIN', 'OWNER']],
      ['admin/dictionaries', ['ADMIN', 'OWNER', 'MANAGER']]
    ];

    protectedRoutes.forEach(([path, roles]) => {
      expect(route(path as string)?.canActivate).toEqual([roleGuard]);
      expect(route(path as string)?.data?.['roles']).toEqual(roles);
    });
  });
});
