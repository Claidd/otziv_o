import { Routes } from '@angular/router';
import type { UrlMatchResult, UrlSegment } from '@angular/router';
import { roleGuard } from './core/role.guard';
import { MOBILE_ACTIONS, MOBILE_ROLES, MOBILE_SECTIONS, rolesForAction } from './core/mobile-permissions';

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
    pathMatch: 'full',
    redirectTo: 'tabs/home'
  },
  {
    path: 'services',
    loadComponent: () => import('./features/public.page').then((m) => m.PublicPage),
    data: { page: 'services' }
  },
  {
    path: 'prices',
    loadComponent: () => import('./features/public.page').then((m) => m.PublicPage),
    data: { page: 'prices' }
  },
  {
    path: 'payment',
    loadComponent: () => import('./features/public.page').then((m) => m.PublicPage),
    data: { page: 'payment' }
  },
  {
    path: 'refund',
    loadComponent: () => import('./features/public.page').then((m) => m.PublicPage),
    data: { page: 'refund' }
  },
  {
    path: 'offer',
    loadComponent: () => import('./features/public.page').then((m) => m.PublicPage),
    data: { page: 'offer' }
  },
  {
    path: 'privacy',
    loadComponent: () => import('./features/public.page').then((m) => m.PublicPage),
    data: { page: 'privacy' }
  },
  {
    path: 'contacts',
    loadComponent: () => import('./features/public.page').then((m) => m.PublicPage),
    data: { page: 'contacts' }
  },
  {
    path: 'receipt-consent',
    loadComponent: () => import('./features/public.page').then((m) => m.PublicPage),
    data: { page: 'receiptConsent' }
  },
  {
    path: 'pay/success',
    loadComponent: () => import('./features/pay-result.page').then((m) => m.PayResultPage),
    data: { result: 'success' }
  },
  {
    path: 'pay/fail',
    loadComponent: () => import('./features/pay-result.page').then((m) => m.PayResultPage),
    data: { result: 'fail' }
  },
  {
    path: 'pay/group/:token',
    loadComponent: () => import('./features/public-pay-group.page').then((m) => m.PublicPayGroupPage)
  },
  {
    path: 'pay/:token',
    loadComponent: () => import('./features/public-pay.page').then((m) => m.PublicPayPage)
  },
  {
    path: 'pay',
    loadComponent: () => import('./features/public.page').then((m) => m.PublicPage),
    data: { page: 'pay' }
  },
  { path: 'uslugi', redirectTo: 'services', pathMatch: 'full' },
  { path: 'tarify', redirectTo: 'prices', pathMatch: 'full' },
  { path: 'oplata', redirectTo: 'payment', pathMatch: 'full' },
  { path: 'vozvrat', redirectTo: 'refund', pathMatch: 'full' },
  { path: 'oferta', redirectTo: 'offer', pathMatch: 'full' },
  { path: 'politika', redirectTo: 'privacy', pathMatch: 'full' },
  { path: 'kontakty', redirectTo: 'contacts', pathMatch: 'full' },
  {
    path: 'register-client',
    loadComponent: () => import('./features/public-register.page').then((m) => m.PublicRegisterPage),
    data: { mode: 'client' }
  },
  {
    path: 'register-performer',
    loadComponent: () => import('./features/public-register.page').then((m) => m.PublicRegisterPage),
    data: { mode: 'performer' }
  },
  {
    path: 'review/editReviews/:orderDetailId',
    loadComponent: () => import('./features/review-check.page').then((m) => m.ReviewCheckPage)
  },
  {
    path: 'review/c',
    loadComponent: () => import('./features/review-check.page').then((m) => m.ReviewCheckPage)
  },
  {
    matcher: reviewShortLinkMatcher,
    loadComponent: () => import('./features/review-check.page').then((m) => m.ReviewCheckPage)
  },
  {
    path: 'cabinet/whatsapp',
    loadComponent: () => import('./features/whatsapp-bind.page').then((m) => m.WhatsAppBindPage),
    canActivate: [roleGuard],
    data: { roles: ['MANAGER'] }
  },
  {
    path: 'cabinet/manager-control',
    loadComponent: () => import('./features/manager-control.page').then((m) => m.ManagerControlPage),
    canActivate: [roleGuard],
    data: { roles: ['MANAGER'], personalControl: true }
  },
  {
    path: 'admin/manager-control/:managerId',
    loadComponent: () => import('./features/manager-control.page').then((m) => m.ManagerControlPage),
    canActivate: [roleGuard],
    data: { roles: ['ADMIN', 'OWNER'] }
  },
  {
    path: 'admin/manager-control',
    loadComponent: () => import('./features/manager-control.page').then((m) => m.ManagerControlPage),
    canActivate: [roleGuard],
    data: { roles: ['ADMIN', 'OWNER'] }
  },
  {
    path: 'login',
    loadComponent: () => import('./features/login.page').then((m) => m.LoginPage)
  },
  {
    path: 'auth/callback',
    loadComponent: () => import('./features/auth-callback.page').then((m) => m.AuthCallbackPage)
  },
  {
    path: 'forbidden',
    loadComponent: () => import('./features/forbidden.page').then((m) => m.ForbiddenPage)
  },
  {
    path: 'tabs',
    loadComponent: () => import('./features/tabs.page').then((m) => m.TabsPage),
    canActivate: [roleGuard],
    data: { roles: MOBILE_ROLES.authenticated },
    children: [
      {
        path: 'home/:section',
        loadComponent: () => import('./features/home.page').then((m) => m.HomePage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.home, MOBILE_ACTIONS.view) }
      },
      {
        path: 'home',
        loadComponent: () => import('./features/home.page').then((m) => m.HomePage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.home, MOBILE_ACTIONS.view) }
      },
      {
        path: 'companies',
        loadComponent: () => import('./features/manager.page').then((m) => m.ManagerPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.companies, MOBILE_ACTIONS.view), managerSection: 'companies' }
      },
      {
        path: 'orders',
        loadComponent: () => import('./features/manager.page').then((m) => m.ManagerPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.orders, MOBILE_ACTIONS.view), managerSection: 'orders' }
      },
      {
        path: 'archive',
        loadComponent: () => import('./features/manager-archive.page').then((m) => m.ManagerArchivePage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.archive, MOBILE_ACTIONS.view) }
      },
      {
        path: 'orders/:companyId/:orderId',
        loadComponent: () => import('./features/order-details.page').then((m) => m.OrderDetailsPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.worker, MOBILE_ACTIONS.view) }
      },
      {
        path: 'common-billing',
        loadComponent: () => import('./features/common-billing-admin.page').then((m) => m.CommonBillingAdminPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.commonBilling, MOBILE_ACTIONS.view) }
      },
      {
        path: 'common-billing/:invoiceId',
        loadComponent: () => import('./features/common-billing.page').then((m) => m.CommonBillingPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.commonBilling, MOBILE_ACTIONS.view) }
      },
      {
        path: 'control',
        loadComponent: () => import('./features/manager-control.page').then((m) => m.ManagerControlPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.managerControl, MOBILE_ACTIONS.view) }
      },
      {
        path: 'control/:managerId',
        loadComponent: () => import('./features/manager-control.page').then((m) => m.ManagerControlPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.managerControl, MOBILE_ACTIONS.view) }
      },
      {
        path: 'cabinet/manager-control',
        loadComponent: () => import('./features/manager-control.page').then((m) => m.ManagerControlPage),
        canActivate: [roleGuard],
        data: { roles: ['MANAGER'], personalControl: true }
      },
      {
        path: 'review-check/:orderDetailId',
        loadComponent: () => import('./features/review-check.page').then((m) => m.ReviewCheckPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.worker, MOBILE_ACTIONS.view) }
      },
      {
        path: 'manager',
        pathMatch: 'full',
        redirectTo: 'companies'
      },
      {
        path: 'worker',
        loadComponent: () => import('./features/worker.page').then((m) => m.WorkerPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.worker, MOBILE_ACTIONS.view) }
      },
      {
        path: 'worker-risk',
        loadComponent: () => import('./features/worker-risk.page').then((m) => m.WorkerRiskPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.workerRisk, MOBILE_ACTIONS.view) }
      },
      {
        path: 'leads',
        loadComponent: () => import('./features/leads.page').then((m) => m.LeadsPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.leads, MOBILE_ACTIONS.view) }
      },
      {
        path: 'operator',
        loadComponent: () => import('./features/operator.page').then((m) => m.OperatorPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.operator, MOBILE_ACTIONS.view) }
      },
      {
        path: 'tbank',
        loadComponent: () => import('./features/tbank.page').then((m) => m.TbankPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.tbank, MOBILE_ACTIONS.view) }
      },
      {
        path: 'users',
        loadComponent: () => import('./features/admin-users.page').then((m) => m.AdminUsersPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.adminUsers, MOBILE_ACTIONS.view) }
      },
      {
        path: 'bots/:botId/browser',
        loadComponent: () => import('./features/bot-browser.page').then((m) => m.BotBrowserPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.botBrowser, MOBILE_ACTIONS.view) }
      },
      {
        path: 'training',
        loadComponent: () => import('./features/training.page').then((m) => m.TrainingPage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.training, MOBILE_ACTIONS.view) }
      },
      {
        path: 'profile',
        pathMatch: 'full',
        redirectTo: 'home/profile'
      },
      {
        path: 'whatsapp',
        loadComponent: () => import('./features/whatsapp-bind.page').then((m) => m.WhatsAppBindPage),
        canActivate: [roleGuard],
        data: { roles: ['MANAGER'] }
      },
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'home'
      }
    ]
  },
  {
    path: '**',
    redirectTo: 'tabs/home'
  }
];
