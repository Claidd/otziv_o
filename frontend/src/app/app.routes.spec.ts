import { routes } from './app.routes';
import { roleGuard } from './core/role.guard';

describe('routes', () => {
  const route = (path: string) => routes.find((candidate) => candidate.path === path);

  it('lazy-loads main feature routes instead of putting them in the initial bundle', () => {
    [
      '',
      'register-client',
      'legacy-migration',
      'leads',
      'operator',
      'manager',
      'manager/orders/:companyId/:orderId',
      'worker',
      'admin/team',
      'admin/score',
      'admin/analyse',
      'admin/user-info/:userId',
      'review/editReviews/:orderDetailId'
    ].forEach((path) => {
      expect(typeof route(path)?.loadComponent).toBe('function');
      expect(route(path)?.component).toBeUndefined();
    });
  });

  it('keeps review edit route public', () => {
    const reviewRoute = route('review/editReviews/:orderDetailId');

    expect(reviewRoute?.canActivate).toBeUndefined();
    expect(reviewRoute?.data).toBeUndefined();
  });

  it('preserves role protected routes', () => {
    const protectedRoutes = [
      ['leads', ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG']],
      ['operator', ['ADMIN', 'OWNER', 'OPERATOR']],
      ['manager', ['ADMIN', 'OWNER', 'MANAGER']],
      ['worker', ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']],
      ['admin/analyse', ['ADMIN', 'OWNER']]
    ];

    protectedRoutes.forEach(([path, roles]) => {
      expect(route(path as string)?.canActivate).toEqual([roleGuard]);
      expect(route(path as string)?.data?.['roles']).toEqual(roles);
    });
  });
});
