import { TestBed } from '@angular/core/testing';
import { FormBuilder } from '@angular/forms';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AdminUser, AdminUsersApi, ContractorAccrualAdjustment } from '../../../core/admin-users.api';
import { AuthService } from '../../../core/auth.service';
import { CabinetApi } from '../../../core/cabinet.api';
import { ContractorPaymentsApi } from '../../../core/contractor-payments.api';
import { ToastService } from '../../../shared/toast.service';
import { UsersAdminComponent } from './users-admin.component';

describe('UsersAdminComponent accrual history', () => {
  it('loads pages beyond the first hundred entries without losing earlier pages', () => {
    const rows = Array.from({ length: 150 }, (_, index) =>
      ({ id: 150 - index }) as ContractorAccrualAdjustment);
    const getContractorAccrualAdjustmentsPage = vi.fn((_userId: number, _profileId: number, page: number) =>
      of({ items: rows.slice(page * 50, (page + 1) * 50), hasMore: page < 2 }));
    TestBed.configureTestingModule({
      providers: [
        FormBuilder,
        { provide: AdminUsersApi, useValue: {
          getUsers: () => of([]),
          getAssignmentOptions: () => of({ managers: [], workers: [], operators: [], marketologs: [] }),
          getContractorAccrualAdjustmentsPage
        } },
        { provide: AuthService, useValue: {} },
        { provide: CabinetApi, useValue: {} },
        { provide: ContractorPaymentsApi, useValue: { getQueueHealth: () => of(null) } },
        { provide: ToastService, useValue: {} }
      ]
    });
    const component = TestBed.runInInjectionContext(() => new UsersAdminComponent());
    component.selectedUser.set({ id: 42 } as AdminUser);

    component.loadContractorAccrualHistory(7, true);
    component.loadMoreContractorAccrualHistory(7);
    component.loadMoreContractorAccrualHistory(7);

    expect(getContractorAccrualAdjustmentsPage.mock.calls.map((call) => call[2]))
      .toEqual([0, 1, 2]);
    expect(component.contractorAccrualHistory()[7]).toHaveLength(150);
    expect(component.contractorAccrualHistory()[7].at(-1)?.id).toBe(1);
    expect(component.contractorAccrualHistoryNextPage()[7]).toBeUndefined();
    component.ngOnDestroy();
  });
});
