import { of } from 'rxjs';
import type {
  CabinetApi,
  ScoreContractorPaymentSummary,
  ScoreResponse
} from '../../core/cabinet.api';
import type { AdminUsersApi, ContractorAccrualAdjustmentPreview } from '../../core/admin-users.api';
import { ScoreComponent } from './score.component';

function payment(
  overrides: Partial<ScoreContractorPaymentSummary> = {}
): ScoreContractorPaymentSummary {
  return {
    profileId: 1,
    userId: 10,
    fio: 'Видимый сотрудник',
    role: 'SPECIALIST',
    profileEnabled: true,
    liveEnabled: true,
    accruedMonthKopecks: 0,
    accruedTotalKopecks: 0,
    reservedKopecks: 0,
    pendingKopecks: 0,
    paidMonthKopecks: 0,
    paidTotalKopecks: 0,
    actualTransferCount: 0,
    actualTransferAmountKopecks: 0,
    availableKopecks: 0,
    reportingLive: true,
    currentMonthCoverageComplete: true,
    ...overrides
  };
}

describe('ScoreComponent saved balances', () => {
  it('exposes an active orphan balance while excluding profiles already rendered in score groups', () => {
    const visible = payment();
    const orphan = payment({
      profileId: 2,
      userId: 99,
      fio: 'Действующий сотрудник без записей за месяц',
      profileEnabled: false,
      outstandingDebtKopecks: 9_000,
      availableKopecks: 9_000
    });
    const response = {
      date: '2026-09-01',
      user: {},
      financeVisible: true,
      contractorPayments: [visible, orphan],
      groups: {
        managers: [],
        marketologs: [],
        workers: [{ fio: visible.fio, role: 'ROLE_WORKER', userId: visible.userId }],
        operators: []
      }
    } as unknown as ScoreResponse;
    const api = {
      getScore: () => of(response),
      imageUrl: () => ''
    } as unknown as CabinetApi;

    const component = new ScoreComponent(api, {} as AdminUsersApi);

    expect(component.savedContractorPayments()).toEqual([orphan]);
    expect(component.visibleSections().at(-1)?.title).toBe('Сохранённые остатки');
  });

  it('uses exact salary kopecks in legacy score rows and falls back to rubles', () => {
    const response = {
      date: '2026-10-01',
      financeVisible: true,
      contractorPayments: [],
      groups: { managers: [], workers: [], operators: [], marketologs: [] }
    } as unknown as ScoreResponse;
    const api = { getScore: () => of(response), imageUrl: () => '' } as unknown as CabinetApi;
    const component = new ScoreComponent(api, {} as AdminUsersApi);
    const operatorSection = component.sections.find((section) => section.key === 'operators')!;

    expect(component.rows(operatorSection, {
      fio: 'Оператор', role: 'ROLE_OPERATOR', salary: 18, salaryKopecks: 1_855
    }).find((row) => row.label === 'Начислено')?.value).toBe('18,55 ₽');
    expect(component.rows(operatorSection, {
      fio: 'Оператор', role: 'ROLE_OPERATOR', salary: 18
    }).find((row) => row.label === 'Начислено')?.value).toBe('18,00 ₽');
  });

  it('offers enabled specialist and manager profiles with the employee role first', () => {
    const response = {
      date: '2026-10-01',
      financeVisible: true,
      contractorPayments: [
        payment({ profileId: 1, role: 'SPECIALIST' }),
        payment({ profileId: 2, role: 'MANAGER' }),
        payment({ profileId: 3, role: 'SPECIALIST', profileEnabled: false })
      ],
      groups: {
        managers: [],
        workers: [{ fio: 'Видимый сотрудник', role: 'ROLE_WORKER', userId: 10 }],
        operators: [],
        marketologs: []
      }
    } as unknown as ScoreResponse;
    const api = { getScore: () => of(response), imageUrl: () => '' } as unknown as CabinetApi;
    const component = new ScoreComponent(api, {} as AdminUsersApi);
    const user = response.groups.workers[0];

    expect(component.eligibleAccrualProfiles(user).map((row) => row.profileId)).toEqual([1, 2]);
    component.openAccrual(user, 'BONUS');
    expect(component.accrualProfileId()).toBe(1);
    expect(component.accrualTarget()?.kind).toBe('BONUS');
    expect(component.accrualDraft()?.referenceMonth).toBe(component.currentAccrualMonth());
    component.selectAccrualProfile('2');
    expect(component.accrualProfileId()).toBe(2);
    component.openAccrual(user, 'DEDUCTION');
    expect(component.accrualTarget()?.kind).toBe('DEDUCTION');
  });

  it('requires a matching preview before posting a premium to the selected profile', () => {
    const response = {
      date: '2026-10-01',
      financeVisible: true,
      contractorPayments: [payment()],
      groups: {
        managers: [],
        workers: [{ fio: 'Видимый сотрудник', role: 'ROLE_WORKER', userId: 10 }],
        operators: [],
        marketologs: []
      }
    } as unknown as ScoreResponse;
    const api = { getScore: () => of(response), imageUrl: () => '' } as unknown as CabinetApi;
    const preview = {
      accruedMonthBeforeKopecks: 0,
      accruedMonthAfterKopecks: 12_000,
      freeForNewPaymentsBeforeKopecks: 0,
      freeForNewPaymentsAfterKopecks: 12_000,
      paidTotalKopecks: 0,
      outstandingKopecks: 0,
      manualTaskCommitmentKopecks: 0
    } as ContractorAccrualAdjustmentPreview;
    const previewApi = vi.fn(() => of(preview));
    const createApi = vi.fn(() => of({ kind: 'BONUS' }));
    const adminApi = {
      previewContractorAccrualAdjustment: previewApi,
      createContractorAccrualAdjustment: createApi
    } as unknown as AdminUsersApi;
    const component = new ScoreComponent(api, adminApi);

    component.openAccrual(response.groups.workers[0], 'BONUS');
    component.updateAccrualDraft('amountRubles', '120,00');
    component.updateAccrualDraft('reason', 'За помощь команде');
    component.saveAccrual();
    expect(createApi).not.toHaveBeenCalled();

    component.previewAccrual();
    expect(previewApi).toHaveBeenCalledWith(10, 1, expect.objectContaining({
      kind: 'BONUS', amountKopecks: 12_000, reason: 'За помощь команде'
    }));
    component.updateAccrualDraft('reason', 'За помощь команде в сложном заказе');
    expect(component.accrualPreview()).toBeNull();
    component.previewAccrual();
    component.saveAccrual();
    expect(createApi).toHaveBeenCalledTimes(1);
    expect(createApi).toHaveBeenCalledWith(10, 1, expect.objectContaining({
      kind: 'BONUS', amountKopecks: 12_000, reason: 'За помощь команде в сложном заказе'
    }));
  });
});
