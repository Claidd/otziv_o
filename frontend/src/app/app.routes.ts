import { Routes } from '@angular/router';
import { HomeComponent } from './features/home/home.component';
import { UserCreateComponent } from './features/admin/users/user-create.component';
import { UsersAdminComponent } from './features/admin/users/users-admin.component';
import { roleGuard } from './core/role.guard';
import { RegisterClientComponent } from './features/auth/register-client.component';
import { LegacyMigrationComponent } from './features/auth/legacy-migration.component';
import { LeadsBoardComponent } from './features/leads/leads-board.component';
import { ManagerBoardComponent } from './features/manager/manager-board.component';
import { OrderDetailsComponent } from './features/manager/order-details.component';
import { OperatorBoardComponent } from './features/operator/operator-board.component';
import { ReviewCheckComponent } from './features/review-check/review-check.component';
import { WorkerBoardComponent } from './features/worker/worker-board.component';
import { AdminAnalyticsComponent } from './features/cabinet/admin-analytics.component';
import { ScoreComponent } from './features/cabinet/score.component';
import { TeamComponent } from './features/cabinet/team.component';
import { UserInfoComponent } from './features/cabinet/user-info.component';

export const routes: Routes = [
  {
    path: '',
    component: HomeComponent
  },
  {
    path: 'register-client',
    component: RegisterClientComponent
  },
  {
    path: 'legacy-migration',
    component: LegacyMigrationComponent
  },
  {
    path: 'leads',
    component: LeadsBoardComponent,
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG']
    }
  },
  {
    path: 'operator',
    component: OperatorBoardComponent,
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'OPERATOR']
    }
  },
  {
    path: 'manager',
    component: ManagerBoardComponent,
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER']
    }
  },
  {
    path: 'manager/orders/:companyId/:orderId',
    component: OrderDetailsComponent,
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']
    }
  },
  {
    path: 'worker',
    component: WorkerBoardComponent,
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']
    }
  },
  {
    path: 'admin/team',
    component: TeamComponent,
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER']
    }
  },
  {
    path: 'admin/score',
    component: ScoreComponent,
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER', 'OPERATOR', 'MARKETOLOG']
    }
  },
  {
    path: 'admin/analyse',
    component: AdminAnalyticsComponent,
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  },
  {
    path: 'admin/user-info/:userId',
    component: UserInfoComponent,
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  },
  {
    path: 'review/editReviews/:orderDetailId',
    component: ReviewCheckComponent
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
    component: UsersAdminComponent,
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  },
  {
    path: 'admin/users/new',
    component: UserCreateComponent,
    canActivate: [roleGuard],
    data: {
      roles: ['ADMIN', 'OWNER']
    }
  }
];
