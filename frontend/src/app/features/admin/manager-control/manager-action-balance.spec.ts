import { describe, expect, it } from 'vitest';
import { ManagerControlManager } from '../../../core/manager-control.api';
import { managerActionBalanceView } from './manager-action-balance';

describe('managerActionBalanceView', () => {
  it('keeps the site balance and both breakdowns consistent', () => {
    const balance = managerActionBalanceView({
      actionTotalCount: 111,
      actionCompletedCount: 84,
      actionAutoClosedCount: 1,
      actionRemainingCount: 26,
      actionResolvedCount: 32,
      actionTakenCount: 20,
      actionDeferredCount: 16,
      actionAcknowledgedCount: 16,
      actionOverdueRemainingCount: 0,
      actionRiskRemainingCount: 15,
      actionUnansweredRemainingCount: 9,
      actionOtherRemainingCount: 2
    } as ManagerControlManager);

    expect(balance.total).toBe(balance.handled + balance.autoClosed + balance.remaining);
    expect(balance.completed).toBe(85);
    expect(balance.handled).toBe(balance.resolved + balance.actionTaken + balance.deferred + balance.acknowledged);
    expect(balance.remaining).toBe(balance.overdue + balance.risks + balance.unanswered + balance.other);
    expect(balance.consistent).toBe(true);
  });

  it('derives the remaining count for a response from the previous API version', () => {
    const balance = managerActionBalanceView({
      actionTotalCount: 110,
      actionCompletedCount: 84,
      actionAutoClosedCount: 0
    } as ManagerControlManager);

    expect(balance.remaining).toBe(26);
  });

  it('counts automatically closed actions as completed progress', () => {
    const balance = managerActionBalanceView({
      actionTotalCount: 123,
      actionCompletedCount: 118,
      actionAutoClosedCount: 5,
      actionRemainingCount: 0
    } as ManagerControlManager);

    expect(balance.completed).toBe(123);
    expect(Math.round(balance.completed * 100 / balance.total)).toBe(100);
  });
});
