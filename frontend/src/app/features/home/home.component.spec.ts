import type { Router } from '@angular/router';
import { of } from 'rxjs';
import { vi } from 'vitest';
import type { AuthService } from '../../core/auth.service';
import type { CabinetApi, CabinetProfile } from '../../core/cabinet.api';
import type { ContractorPaymentSummary, ContractorPaymentsApi } from '../../core/contractor-payments.api';
import type { CurrentUserApi } from '../../core/current-user.api';
import type { ManagerReportReviewAccessApi } from '../../core/manager-report-review-access.api';
import type { SystemHealthApi } from '../../core/system-health.api';
import type { WorkloadShadowApi } from '../../core/workload-shadow.api';
import type { ToastService } from '../../shared/toast.service';
import { HomeComponent } from './home.component';

describe('HomeComponent accrual cards', () => {
  it('shows exact kopecks in both salary blocks and falls back to legacy rubles', () => {
    const auth = { isAuthenticated: () => false } as unknown as AuthService;
    const systemHealthApi = { getHealth: () => of({}) } as unknown as SystemHealthApi;
    const component = new HomeComponent(
      auth,
      {} as CurrentUserApi,
      systemHealthApi,
      {} as CabinetApi,
      {} as ManagerReportReviewAccessApi,
      {} as WorkloadShadowApi,
      {} as ContractorPaymentsApi,
      {} as ToastService,
      {} as Router
    );
    component.cabinet.set({
      workerZp: {
        sum1Day: 1,
        sum1Week: 5,
        sum1Month: 99,
        sum1Year: 3,
        sum1DayKopecks: 125,
        sum1WeekKopecks: null,
        sum1MonthKopecks: 0,
        sum1YearKopecks: 345,
        percent1Day: 0,
        percent1Week: 0,
        percent1Month: 0,
        percent1Year: 0
      }
    } as unknown as CabinetProfile);

    expect(component.cabinetMetrics().slice(0, 4).map((metric) => metric.value))
      .toEqual(['1,25 руб.', '5,00 руб.', '0,00 руб.', '3,45 руб.']);
    expect(component.contractorRewardMetrics().slice(1).map((metric) => metric.value))
      .toEqual(['1,25 руб.', '5,00 руб.', '0,00 руб.']);
    expect(component.contractorRewardMetrics()[0].value).toBe('0 руб.');
  });

  it('reloads accrual totals and adjustment history when explicitly refreshed', () => {
    const getMySummary = vi.fn()
      .mockReturnValueOnce(of([{ profileId: 7, accruedMonthKopecks: 100 }] as ContractorPaymentSummary[]))
      .mockReturnValueOnce(of([{ profileId: 7, accruedMonthKopecks: 250 }] as ContractorPaymentSummary[]));
    const getMyAccrualAdjustments = vi.fn()
      .mockReturnValueOnce(of([]))
      .mockReturnValueOnce(of([{ id: 1, profileId: 7, signedAmountKopecks: 150 }]));
    const component = new HomeComponent(
      { isAuthenticated: () => false, tokenParsed: () => undefined } as unknown as AuthService,
      {} as CurrentUserApi,
      { getHealth: () => of({}) } as unknown as SystemHealthApi,
      {} as CabinetApi,
      {} as ManagerReportReviewAccessApi,
      {} as WorkloadShadowApi,
      { getMySummary, getMyAccrualAdjustments } as unknown as ContractorPaymentsApi,
      {} as ToastService,
      {} as Router
    );

    component.loadContractorPayments();
    component.loadContractorPayments();
    component.loadContractorPayments(true);

    expect(getMySummary).toHaveBeenCalledTimes(2);
    expect(getMyAccrualAdjustments).toHaveBeenCalledTimes(2);
    expect(component.contractorPayments()[0].accruedMonthKopecks).toBe(250);
    expect(component.contractorAccrualAdjustments()[0].signedAmountKopecks).toBe(150);
  });
});
