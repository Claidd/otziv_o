import { Routes } from '@angular/router';
import { roleGuard } from './core/role.guard';

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
    children: [
      {
        path: 'home/:section',
        loadComponent: () => import('./features/home.page').then((m) => m.HomePage)
      },
      {
        path: 'home',
        loadComponent: () => import('./features/home.page').then((m) => m.HomePage)
      },
      {
        path: 'companies',
        loadComponent: () => import('./features/manager.page').then((m) => m.ManagerPage),
        canActivate: [roleGuard],
        data: { roles: ['ADMIN', 'OWNER', 'MANAGER'], managerSection: 'companies' }
      },
      {
        path: 'orders',
        loadComponent: () => import('./features/manager.page').then((m) => m.ManagerPage),
        canActivate: [roleGuard],
        data: { roles: ['ADMIN', 'OWNER', 'MANAGER'], managerSection: 'orders' }
      },
      {
        path: 'archive',
        loadComponent: () => import('./features/manager-archive.page').then((m) => m.ManagerArchivePage),
        canActivate: [roleGuard],
        data: { roles: ['ADMIN', 'OWNER', 'MANAGER'] }
      },
      {
        path: 'orders/:companyId/:orderId',
        loadComponent: () => import('./features/order-details.page').then((m) => m.OrderDetailsPage),
        canActivate: [roleGuard],
        data: { roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] }
      },
      {
        path: 'review-check/:orderDetailId',
        loadComponent: () => import('./features/review-check.page').then((m) => m.ReviewCheckPage),
        canActivate: [roleGuard],
        data: { roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] }
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
        data: { roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'] }
      },
      {
        path: 'leads',
        loadComponent: () => import('./features/leads.page').then((m) => m.LeadsPage),
        canActivate: [roleGuard],
        data: { roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG'] }
      },
      {
        path: 'operator',
        loadComponent: () => import('./features/operator.page').then((m) => m.OperatorPage),
        canActivate: [roleGuard],
        data: { roles: ['ADMIN', 'OWNER', 'OPERATOR'] }
      },
      {
        path: 'tbank',
        loadComponent: () => import('./features/tbank.page').then((m) => m.TbankPage),
        canActivate: [roleGuard],
        data: { roles: ['ADMIN'] }
      },
      {
        path: 'profile',
        loadComponent: () => import('./features/profile.page').then((m) => m.ProfilePage)
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
