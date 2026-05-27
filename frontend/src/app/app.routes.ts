import { Routes } from '@angular/router';
import type { UrlMatchResult, UrlSegment } from '@angular/router';
import { roleGuard } from './core/role.guard';

const REVIEW_SHORT_LINK_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function reviewShortLinkMatcher(segments: UrlSegment[]): UrlMatchResult | null {
  if (segments.length !== 1 || !REVIEW_SHORT_LINK_PATTERN.test(segments[0].path)) {
    return null;
  }

  return {
    consumed: segments,
    posParams: {
      orderDetailId: segments[0]
    }
  };
}

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./features/home/home.component')
      .then((m) => m.HomeComponent)
  },
  {
    path: 'services',
    loadComponent: () => import('./features/public/public-page.component')
      .then((m) => m.PublicPageComponent),
    data: { page: 'services' }
  },
  {
    path: 'prices',
    loadComponent: () => import('./features/public/public-page.component')
      .then((m) => m.PublicPageComponent),
    data: { page: 'prices' }
  },
  {
    path: 'payment',
    loadComponent: () => import('./features/public/public-page.component')
      .then((m) => m.PublicPageComponent),
    data: { page: 'payment' }
  },
  {
    path: 'refund',
    loadComponent: () => import('./features/public/public-page.component')
      .then((m) => m.PublicPageComponent),
    data: { page: 'refund' }
  },
  {
    path: 'offer',
    loadComponent: () => import('./features/public/public-page.component')
      .then((m) => m.PublicPageComponent),
    data: { page: 'offer' }
  },
  {
    path: 'privacy',
    loadComponent: () => import('./features/public/public-page.component')
      .then((m) => m.PublicPageComponent),
    data: { page: 'privacy' }
  },
  {
    path: 'contacts',
    loadComponent: () => import('./features/public/public-page.component')
      .then((m) => m.PublicPageComponent),
    data: { page: 'contacts' }
  },
  {
    path: 'receipt-consent',
    loadComponent: () => import('./features/public/public-page.component')
      .then((m) => m.PublicPageComponent),
    data: { page: 'receiptConsent' }
  },
  {
    path: 'pay/success',
    loadComponent: () => import('./features/pay/pay-result.component')
      .then((m) => m.PayResultComponent),
    data: { result: 'success' }
  },
  {
    path: 'pay/fail',
    loadComponent: () => import('./features/pay/pay-result.component')
      .then((m) => m.PayResultComponent),
    data: { result: 'fail' }
  },
  {
    path: 'pay/:token',
    loadComponent: () => import('./features/pay/pay-page.component')
      .then((m) => m.PayPageComponent)
  },
  {
    path: 'pay',
    loadComponent: () => import('./features/public/public-page.component')
      .then((m) => m.PublicPageComponent),
    data: { page: 'pay' }
  },
  {
    path: 'uslugi',
    redirectTo: 'services',
    pathMatch: 'full'
  },
  {
    path: 'tarify',
    redirectTo: 'prices',
    pathMatch: 'full'
  },
  {
    path: 'oplata',
    redirectTo: 'payment',
    pathMatch: 'full'
  },
  {
    path: 'vozvrat',
    redirectTo: 'refund',
    pathMatch: 'full'
  },
  {
    path: 'oferta',
    redirectTo: 'offer',
    pathMatch: 'full'
  },
  {
    path: 'politika',
    redirectTo: 'privacy',
    pathMatch: 'full'
  },
  {
    path: 'kontakty',
    redirectTo: 'contacts',
    pathMatch: 'full'
  },
  {
    path: 'register-client',
    loadComponent: () => import('./features/auth/register-client.component')
      .then((m) => m.RegisterClientComponent)
  },
  {
    path: 'legacy-migration',
    loadComponent: () => import('./features/auth/legacy-migration.component')
      .then((m) => m.LegacyMigrationComponent)
  },
  {
    path: 'leads',
    loadComponent: () => import('./features/leads/leads-board.component')
      .then((m) => m.LeadsBoardComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG']
    }
  },
  {
    path: 'operator',
    loadComponent: () => import('./features/operator/operator-board.component')
      .then((m) => m.OperatorBoardComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'OPERATOR']
    }
  },
  {
    path: 'operator/phones',
    redirectTo: 'admin/dictionaries/phones',
    pathMatch: 'full'
  },
  {
    path: 'manager',
    loadComponent: () => import('./features/manager/manager-board.component')
      .then((m) => m.ManagerBoardComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER']
    }
  },
  {
    path: 'manager/archive',
    loadComponent: () => import('./features/manager/manager-archive.component')
      .then((m) => m.ManagerArchiveComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER']
    }
  },
  {
    path: 'manager/orders/:companyId/:orderId',
    loadComponent: () => import('./features/manager/order-details.component')
      .then((m) => m.OrderDetailsComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']
    }
  },
  {
    path: 'worker',
    loadComponent: () => import('./features/worker/worker-board.component')
      .then((m) => m.WorkerBoardComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']
    }
  },
  {
    path: 'training',
    loadComponent: () => import('./features/training/training.component')
      .then((m) => m.TrainingComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']
    }
  },
  {
    path: 'admin/team',
    loadComponent: () => import('./features/cabinet/team.component')
      .then((m) => m.TeamComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER']
    }
  },
  {
    path: 'admin/score',
    loadComponent: () => import('./features/cabinet/score.component')
      .then((m) => m.ScoreComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER', 'OPERATOR', 'MARKETOLOG']
    }
  },
  {
    path: 'cabinet/whatsapp',
    loadComponent: () => import('./features/cabinet/whatsapp-bind.component')
      .then((m) => m.WhatsAppBindComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['MANAGER']
    }
  },
  {
    path: 'admin/analyse',
    loadComponent: () => import('./features/cabinet/admin-analytics.component')
      .then((m) => m.AdminAnalyticsComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  },
  {
    path: 'admin/archive',
    loadComponent: () => import('./features/admin/archive-admin.component')
      .then((m) => m.ArchiveAdminComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  },
  {
    path: 'admin/tbank-payments',
    loadComponent: () => import('./features/admin/tbank-payments/tbank-payments.component')
      .then((m) => m.TbankPaymentsComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  },
  {
    path: 'admin/reputation-ai',
    loadComponent: () => import('./features/admin/reputation-ai/reputation-ai.component')
      .then((m) => m.ReputationAiComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  },
  {
    path: 'admin/user-info/:userId',
    loadComponent: () => import('./features/cabinet/user-info.component')
      .then((m) => m.UserInfoComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  },
  {
    path: 'review/editReviews/:orderDetailId',
    loadComponent: () => import('./features/review-check/review-check.component')
      .then((m) => m.ReviewCheckComponent)
  },
  {
    matcher: reviewShortLinkMatcher,
    loadComponent: () => import('./features/review-check/review-check.component')
      .then((m) => m.ReviewCheckComponent)
  },
  {
    path: 'admin/cities',
    loadComponent: () => import('./features/admin/cities/admin-cities.component')
      .then((m) => m.AdminCitiesComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  },
  {
    path: 'admin/dictionaries/accounts/:botId/browser',
    loadComponent: () => import('./features/admin/dictionaries/bot-browser.component')
      .then((m) => m.BotBrowserComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']
    }
  },
  {
    path: 'admin/dictionaries/phones',
    loadComponent: () => import('./features/admin/dictionaries/admin-dictionaries.component')
      .then((m) => m.AdminDictionariesComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER'],
      initialTab: 'phones'
    }
  },
  {
    path: 'admin/dictionaries',
    loadComponent: () => import('./features/admin/dictionaries/admin-dictionaries.component')
      .then((m) => m.AdminDictionariesComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER']
    }
  },
  {
    path: 'admin/users',
    loadComponent: () => import('./features/admin/users/users-admin.component')
      .then((m) => m.UsersAdminComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  },
  {
    path: 'admin/users/new',
    loadComponent: () => import('./features/admin/users/user-create.component')
      .then((m) => m.UserCreateComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  }
];
