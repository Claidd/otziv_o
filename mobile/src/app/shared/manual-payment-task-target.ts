export type MobileManualPaymentTaskAccountingTargetKind =
  | 'UNRESOLVED'
  | 'EXTERNAL_TASK'
  | 'OWNER'
  | 'SPECIALIST'
  | 'MANAGER';

export interface MobileManualPaymentTaskAccountingTargetOptionLike {
  key: string;
  kind: MobileManualPaymentTaskAccountingTargetKind;
  profileId?: number | null;
  label: string;
  enabled: boolean;
  currentAvailableKopecks?: number | null;
  projectedOverrunKopecks?: number | null;
  overrunAcknowledgementRequired?: boolean;
  recommended?: boolean | null;
}

export interface MobileManualPaymentTaskTargetSnapshotLike {
  accountingTargetKind?: MobileManualPaymentTaskAccountingTargetKind | null;
  accountingTargetProfileId?: number | null;
}

export function mobileManualTaskSelectedTarget<T extends MobileManualPaymentTaskAccountingTargetOptionLike>(
  options: readonly T[],
  key: string | null | undefined
): T | null {
  const normalized = key?.trim();
  return normalized ? options.find(option => option.key === normalized) ?? null : null;
}

export function mobileManualTaskTargetForSnapshot<T extends MobileManualPaymentTaskAccountingTargetOptionLike>(
  options: readonly T[],
  task: MobileManualPaymentTaskTargetSnapshotLike
): T | null {
  return options.find(option => option.kind === task.accountingTargetKind
    && (option.profileId ?? null) === (task.accountingTargetProfileId ?? null)) ?? null;
}

export function mobileManualTaskRecommendedTarget<T extends MobileManualPaymentTaskAccountingTargetOptionLike>(
  options: readonly T[]
): T | null {
  return options.find(option => option.recommended === true
    && option.enabled
    && option.kind !== 'UNRESOLVED') ?? null;
}

export function mobileManualTaskTargetEffect(
  target: MobileManualPaymentTaskAccountingTargetOptionLike | null | undefined
): string {
  switch (target?.kind) {
    case 'EXTERNAL_TASK':
      return 'Оплата выполнит задание, но не изменит лимиты владельца и сотрудников.';
    case 'OWNER':
      return 'Оплата выполнит задание и будет учтена как полученная владельцем.';
    case 'SPECIALIST':
    case 'MANAGER':
      return 'Оплата выполнит задание и будет учтена у выбранного сотрудника; его лимит и резервы обновятся.';
    case 'UNRESOLVED':
      return 'Получатель задания не привязан. Маршрутизация и подтверждение заблокированы.';
    default:
      return 'Выберите, кому учитывать фактически полученные деньги.';
  }
}

export function mobileManualTaskTargetValid(
  target: MobileManualPaymentTaskAccountingTargetOptionLike | null | undefined,
  overrunAcknowledged: boolean
): boolean {
  if (!target || !target.enabled || target.kind === 'UNRESOLVED') {
    return false;
  }
  return !(target.overrunAcknowledgementRequired || (target.projectedOverrunKopecks ?? 0) > 0)
    || overrunAcknowledged;
}
