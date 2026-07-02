import { Routes } from '@angular/router';
import { roleGuard } from './core/role.guard';
import { MOBILE_ACTIONS, MOBILE_ROLES, MOBILE_SECTIONS, rolesForAction } from './core/mobile-permissions';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'tabs/home'
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
        path: 'profile',
        loadComponent: () => import('./features/profile.page').then((m) => m.ProfilePage),
        canActivate: [roleGuard],
        data: { roles: rolesForAction(MOBILE_SECTIONS.home, MOBILE_ACTIONS.view) }
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
