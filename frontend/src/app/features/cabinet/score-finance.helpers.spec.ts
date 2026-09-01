import {
  scoreMonthLabel,
  scoreOrphanPayments,
  scoreOutstandingDebtKopecks,
  scoreOutstandingReservedKopecks,
  scorePaymentsForUser
} from './score-finance.helpers';
import type { ScoreContractorPaymentSummary } from '../../core/cabinet.api';

function payment(
  overrides: Partial<ScoreContractorPaymentSummary> = {}
): ScoreContractorPaymentSummary {
  return {
    profileId: 1,
    userId: 10,
    fio: 'Елена Ч.',
    role: 'SPECIALIST',
    profileEnabled: true,
    liveEnabled: true,
    accruedMonthKopecks: 16_425,
    accruedTotalKopecks: 38_595,
    reservedKopecks: 15_000,
    pendingKopecks: 4_000,
    paidMonthKopecks: 4_100,
    paidTotalKopecks: 18_800,
    actualTransferCount: 2,
    actualTransferAmountKopecks: 4_100,
    availableKopecks: 795,
    reportingLive: true,
    currentMonthCoverageComplete: true,
    ...overrides
  };
}

describe('score finance helpers', () => {
  it('labels the financial period from the selected historical date', () => {
    expect(scoreMonthLabel('2026-08-31')).toBe('август 2026');
  });

  it('shows full debt and includes pending confirmations in the full reserve', () => {
    const row = payment();

    expect(scoreOutstandingDebtKopecks(row)).toBe(19_795);
    expect(scoreOutstandingReservedKopecks(row)).toBe(19_000);
  });

  it('keeps disabled historical profiles visible and puts the current role first', () => {
    const disabledManager = payment({
      profileId: 2,
      role: 'MANAGER',
      profileEnabled: false,
      liveEnabled: false
    });
    const specialist = payment({ profileId: 3 });

    expect(scorePaymentsForUser([disabledManager, specialist], 10, 'SPECIALIST'))
      .toEqual([specialist, disabledManager]);
  });

  it('returns a nonzero orphan balance once without duplicating visible users', () => {
    const visible = payment({ userId: 10, profileId: 3 });
    const orphan = payment({
      userId: 99,
      profileId: 4,
      fio: 'Бывший сотрудник',
      profileEnabled: false,
      outstandingDebtKopecks: 9_000
    });

    expect(scoreOrphanPayments([visible, orphan], [10, 10])).toEqual([orphan]);
  });
});
