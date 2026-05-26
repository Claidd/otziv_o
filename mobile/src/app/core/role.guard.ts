import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const roleGuard: CanActivateFn = async (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.isAuthenticated()) {
    return router.createUrlTree(['/login'], { queryParams: { target: state.url } });
  }

  const roles = route.data['roles'] as readonly string[] | undefined;
  if (!roles?.length || auth.hasAnyRealmRole(roles)) {
    return true;
  }

  return router.createUrlTree(['/forbidden']);
};
