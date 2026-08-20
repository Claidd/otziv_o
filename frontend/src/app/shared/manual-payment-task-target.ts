import type {
  ManualPaymentTaskAccountingTargetKind,
  ManualPaymentTaskAccountingTargetOptionLike
} from './manual-payment-routing';

export interface ManualPaymentTaskTargetSnapshotLike {
  accountingTargetKind?: ManualPaymentTaskAccountingTargetKind | null;
  accountingTargetProfileId?: number | null;
}

export function manualPaymentTaskSelectedTarget(
  options: readonly ManualPaymentTaskAccountingTargetOptionLike[],
  key: string | null | undefined
): ManualPaymentTaskAccountingTargetOptionLike | null {
  const normalized = key?.trim();
  return normalized ? options.find(option => option.key === normalized) ?? null : null;
}

export function manualPaymentTaskTargetForSnapshot(
  options: readonly ManualPaymentTaskAccountingTargetOptionLike[],
  task: ManualPaymentTaskTargetSnapshotLike
): ManualPaymentTaskAccountingTargetOptionLike | null {
  return options.find(option => option.kind === task.accountingTargetKind
    && (option.profileId ?? null) === (task.accountingTargetProfileId ?? null)) ?? null;
}

export function manualPaymentTaskRecommendedTarget(
  options: readonly ManualPaymentTaskAccountingTargetOptionLike[]
): ManualPaymentTaskAccountingTargetOptionLike | null {
  return options.find(option => option.recommended === true
    && option.enabled
    && option.kind !== 'UNRESOLVED') ?? null;
}

export function manualPaymentTaskTargetEffect(
  target: ManualPaymentTaskAccountingTargetOptionLike | null | undefined
): string {
  switch (target?.kind) {
    case 'EXTERNAL_TASK':
      return 'Оплата закроет резерв и увеличит выполнение задания. Лимиты владельца и сотрудников не изменятся.';
    case 'OWNER':
      return 'Оплата увеличит выполнение задания и будет учтена как полученная владельцем.';
    case 'SPECIALIST':
    case 'MANAGER':
      return 'Оплата увеличит выполнение задания и будет учтена у выбранного сотрудника. Его лимит и резервы обновятся.';
    case 'UNRESOLVED':
      return 'Получатель задания не привязан. Новые счета и подтверждение оплаты заблокированы.';
    default:
      return 'Выберите, кому система должна учитывать фактически полученные деньги.';
  }
}

export function manualPaymentTaskTargetValid(
  target: ManualPaymentTaskAccountingTargetOptionLike | null | undefined,
  overrunAcknowledged: boolean
): boolean {
  if (!target || !target.enabled || target.kind === 'UNRESOLVED') {
    return false;
  }
  return !(target.overrunAcknowledgementRequired || (target.projectedOverrunKopecks ?? 0) > 0)
    || overrunAcknowledged;
}
