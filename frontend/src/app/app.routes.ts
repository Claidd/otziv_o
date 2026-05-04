import { Routes } from '@angular/router';
import { roleGuard } from './core/role.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./features/home/home.component')
      .then((m) => m.HomeComponent)
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
    path: 'manager',
    loadComponent: () => import('./features/manager/manager-board.component')
      .then((m) => m.ManagerBoardComponent),
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
    path: 'admin/analyse',
    loadComponent: () => import('./features/cabinet/admin-analytics.component')
      .then((m) => m.AdminAnalyticsComponent),
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
      roles: ['ADMIN', 'OWNER']
    }
  },
  {
    path: 'admin/dictionaries',
    loadComponent: () => import('./features/admin/dictionaries/admin-dictionaries.component')
      .then((m) => m.AdminDictionariesComponent),
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
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
