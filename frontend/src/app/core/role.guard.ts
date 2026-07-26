import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { ManagerReportReviewAccessApi } from './manager-report-review-access.api';

export const roleGuard: CanActivateFn = async (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const reportReviewAccess = inject(ManagerReportReviewAccessApi);

  if (!auth.isAuthenticated()) {
    await auth.login(state.url);
    return false;
  }

  const roles = route.data['roles'] as string[] | undefined;
  if (roles?.length && !auth.hasAnyRealmRole(roles)) {
    return router.createUrlTree(['/']);
  }

  if (auth.hasRealmRole('MANAGER') && state.url !== '/') {
    try {
      const access = await reportReviewAccess.refresh();
      if (access.restricted) {
        return router.createUrlTree(['/'], {
          queryParams: { reportReviewRequired: '1' }
        });
      }
    } catch {
      // Сервер дополнительно блокирует бизнес-API; временная ошибка проверки
      // не должна разлогинивать пользователя или скрывать личный кабинет.
    }
  }
  return true;
};
