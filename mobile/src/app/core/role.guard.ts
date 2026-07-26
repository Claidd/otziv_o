import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import {
  isManagerReportReviewPersonalRoute,
  ManagerReportReviewAccessService
} from './manager-report-review-access.service';

export const roleGuard: CanActivateFn = async (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const reportReview = inject(ManagerReportReviewAccessService);

  if (!await auth.ensureAuthenticated()) {
    return router.createUrlTree(['/login'], { queryParams: { target: state.url } });
  }

  const roles = route.data['roles'] as readonly string[] | undefined;
  if (roles?.length && !auth.hasAnyRealmRole(roles)) {
    return router.createUrlTree(['/forbidden']);
  }

  if (auth.hasRealmRole('MANAGER') && !isManagerReportReviewPersonalRoute(state.url)) {
    try {
      const access = await reportReview.refresh();
      if (access.restricted) {
        return router.createUrlTree(['/tabs/home/profile'], {
          queryParams: { reportReviewRequired: '1' }
        });
      }
    } catch {
      // Сервер также блокирует рабочие API. Временная ошибка проверки
      // не должна закрывать менеджеру личный кабинет.
    }
  }

  return true;
};
