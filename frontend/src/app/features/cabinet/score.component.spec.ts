import { of } from 'rxjs';
import type {
  CabinetApi,
  ScoreContractorPaymentSummary,
  ScoreResponse
} from '../../core/cabinet.api';
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
  it('exposes orphan debt while excluding profiles already rendered in score groups', () => {
    const visible = payment();
    const orphan = payment({
      profileId: 2,
      userId: 99,
      fio: 'Бывший сотрудник',
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

    const component = new ScoreComponent(api);

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
    const component = new ScoreComponent(api);
    const operatorSection = component.sections.find((section) => section.key === 'operators')!;

    expect(component.rows(operatorSection, {
      fio: 'Оператор', role: 'ROLE_OPERATOR', salary: 18, salaryKopecks: 1_855
    }).find((row) => row.label === 'Начислено')?.value).toBe('18,55 ₽');
    expect(component.rows(operatorSection, {
      fio: 'Оператор', role: 'ROLE_OPERATOR', salary: 18
    }).find((row) => row.label === 'Начислено')?.value).toBe('18,00 ₽');
  });
});
