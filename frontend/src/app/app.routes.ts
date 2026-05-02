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
import { ReviewCheckComponent } from './features/review-check/review-check.component';
import { WorkerBoardComponent } from './features/worker/worker-board.component';

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
      roles: ['ADMIN', 'OWNER', 'MANAGER']
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
    path: 'review/editReviews/:orderDetailId',
    component: ReviewCheckComponent
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
