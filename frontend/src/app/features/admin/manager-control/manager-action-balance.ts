import { ManagerControlManager } from '../../../core/manager-control.api';

export type ManagerActionBalanceView = {
  total: number;
  handled: number;
  autoClosed: number;
  remaining: number;
  resolved: number;
  actionTaken: number;
  deferred: number;
  acknowledged: number;
  overdue: number;
  risks: number;
  unanswered: number;
  other: number;
  consistent: boolean;
};

export function managerActionBalanceView(manager: ManagerControlManager): ManagerActionBalanceView {
  const total = nonNegative(manager.actionTotalCount);
  const handled = nonNegative(manager.actionCompletedCount);
  const autoClosed = nonNegative(manager.actionAutoClosedCount);
  const remaining = manager.actionRemainingCount == null
    ? Math.max(0, total - handled - autoClosed)
    : nonNegative(manager.actionRemainingCount);
  const resolved = nonNegative(manager.actionResolvedCount);
  const actionTaken = nonNegative(manager.actionTakenCount);
  const deferred = nonNegative(manager.actionDeferredCount);
  const acknowledged = nonNegative(manager.actionAcknowledgedCount);
  const overdue = nonNegative(manager.actionOverdueRemainingCount);
  const risks = nonNegative(manager.actionRiskRemainingCount);
  const unanswered = nonNegative(manager.actionUnansweredRemainingCount);
  const other = nonNegative(manager.actionOtherRemainingCount);

  return {
    total,
    handled,
    autoClosed,
    remaining,
    resolved,
    actionTaken,
    deferred,
    acknowledged,
    overdue,
    risks,
    unanswered,
    other,
    consistent: total === handled + autoClosed + remaining
      && handled === resolved + actionTaken + deferred + acknowledged
      && remaining === overdue + risks + unanswered + other
  };
}

function nonNegative(value: number | null | undefined): number {
  return Math.max(0, value ?? 0);
}
