export type PaymentRouteErrorCode =
  | 'ACTUAL_RECIPIENT_REQUIRED'
  | 'PAYMENT_ROUTE_STALE'
  | 'TASK_TARGET_UNRESOLVED';

export type ManualPaymentCashDestinationKind =
  | 'OWNER'
  | 'CONTRACTOR_PROFILE'
  | 'MANUAL_PAYMENT_TASK';

export type ManualPaymentTaskAccountingTargetKind =
  | 'UNRESOLVED'
  | 'EXTERNAL_TASK'
  | 'OWNER'
  | 'SPECIALIST'
  | 'MANAGER';

export interface ManualPaymentRecipientLike {
  key?: string | null;
  cashDestinationKind?: ManualPaymentCashDestinationKind | null;
  recipientType?: 'OWNER' | 'MANAGER' | 'SPECIALIST' | null;
  recipientProfileId?: number | null;
  displayName?: string | null;
  label?: string | null;
  manualPaymentTaskId?: number | null;
  manualPaymentTaskGeneration?: number | null;
  taskTargetKind?: ManualPaymentTaskAccountingTargetKind | null;
  taskRecipientName?: string | null;
  accountingTargetLabel?: string | null;
  effectText?: string | null;
}

export interface ManualPaymentTaskAccountingTargetOptionLike {
  key: string;
  kind: ManualPaymentTaskAccountingTargetKind;
  profileId?: number | null;
  userId?: number | null;
  role?: 'SPECIALIST' | 'MANAGER' | null;
  label: string;
  enabled: boolean;
  currentAvailableKopecks?: number | null;
  projectedOverrunKopecks?: number | null;
  overrunAcknowledgementRequired?: boolean;
}

const ROUTE_ERROR_CODES = new Set<PaymentRouteErrorCode>([
  'ACTUAL_RECIPIENT_REQUIRED',
  'PAYMENT_ROUTE_STALE',
  'TASK_TARGET_UNRESOLVED'
]);

export function manualPaymentRouteErrorCode(error: unknown): PaymentRouteErrorCode | null {
  const outer = asRecord(error);
  const payload = asRecord(outer?.['error']) ?? outer;
  const properties = asRecord(payload?.['properties']);
  for (const value of [
    payload?.['code'],
    payload?.['errorCode'],
    payload?.['problemCode'],
    properties?.['code']
  ]) {
    if (typeof value === 'string') {
      const normalized = value.trim().toUpperCase() as PaymentRouteErrorCode;
      if (ROUTE_ERROR_CODES.has(normalized)) {
        return normalized;
      }
    }
  }
  return null;
}

export function isRetryablePaymentRouteError(error: unknown): boolean {
  const code = manualPaymentRouteErrorCode(error);
  return code === 'ACTUAL_RECIPIENT_REQUIRED' || code === 'PAYMENT_ROUTE_STALE';
}

export function manualPaymentRouteErrorMessage(error: unknown, fallback: string): string {
  const code = manualPaymentRouteErrorCode(error);
  if (code === 'TASK_TARGET_UNRESOLVED') {
    return 'Получатель платёжного задания не привязан. Сначала укажите, кому учитывать оплату, в настройках задания.';
  }
  if (code === 'PAYMENT_ROUTE_STALE') {
    return 'Маршрут оплаты изменился. Список получателей обновлён — проверьте выбор и подтвердите ещё раз.';
  }
  if (code === 'ACTUAL_RECIPIENT_REQUIRED') {
    return 'Нужно заново выбрать фактического получателя по актуальному маршруту счёта.';
  }
  const outer = asRecord(error);
  const payload = outer?.['error'];
  if (typeof payload === 'string' && payload.trim()) {
    return payload.trim();
  }
  const record = asRecord(payload);
  for (const key of ['message', 'detail', 'error']) {
    const value = record?.[key];
    if (typeof value === 'string' && value.trim()) {
      return value.trim();
    }
  }
  return error instanceof Error && error.message.trim() ? error.message.trim() : fallback;
}

export function manualPaymentRecipientKey(candidate: ManualPaymentRecipientLike): string {
  const explicit = candidate.key?.trim();
  if (explicit) {
    return explicit;
  }
  if (isManualPaymentTaskRecipient(candidate)) {
    const taskId = candidate.manualPaymentTaskId;
    const generation = candidate.manualPaymentTaskGeneration;
    return Number.isSafeInteger(taskId) && Number.isSafeInteger(generation)
      ? `TASK:${taskId}:${generation}`
      : '';
  }
  return candidate.recipientType
    ? `${candidate.recipientType}:${candidate.recipientProfileId ?? ''}`
    : '';
}

export function isManualPaymentTaskRecipient(candidate: ManualPaymentRecipientLike | null | undefined): boolean {
  return Boolean(candidate && (
    candidate.cashDestinationKind === 'MANUAL_PAYMENT_TASK'
      || candidate.manualPaymentTaskId != null
      || candidate.key?.trim().startsWith('TASK:')
  ));
}

export function manualPaymentRecipientLabel(candidate: ManualPaymentRecipientLike): string {
  if (isManualPaymentTaskRecipient(candidate)) {
    const task = candidate.manualPaymentTaskId != null
      ? `Платёжное задание №${candidate.manualPaymentTaskId}`
      : 'Платёжное задание';
    const bankRecipient = candidate.taskRecipientName?.trim()
      || candidate.displayName?.trim()
      || candidate.label?.trim()
      || 'получатель в банке не указан';
    const accountingTarget = candidate.accountingTargetLabel?.trim();
    return `${task} · ${bankRecipient}${accountingTarget ? ` · учёт: ${accountingTarget}` : ''}`;
  }
  const role = candidate.recipientType === 'OWNER'
    ? 'Владелец'
    : candidate.recipientType === 'MANAGER'
      ? 'Менеджер'
      : candidate.recipientType === 'SPECIALIST'
        ? 'Специалист'
        : 'Получатель';
  const name = candidate.displayName?.trim() || candidate.label?.trim();
  return name && name !== role ? `${role} · ${name}` : role;
}

export function manualPaymentRecipientEffect(candidate: ManualPaymentRecipientLike): string {
  const explicit = candidate.effectText?.trim();
  if (explicit) {
    return explicit;
  }
  if (!isManualPaymentTaskRecipient(candidate)) {
    return 'Сумма будет учтена у выбранного получателя; его лимит и резерв обновятся.';
  }
  switch (candidate.taskTargetKind) {
    case 'EXTERNAL_TASK':
      return 'Сумма будет зачтена только в платёжное задание. Лимиты владельца и сотрудников не изменятся.';
    case 'OWNER':
      return 'Сумма будет зачтена в платёжное задание и учтена как полученная владельцем.';
    case 'SPECIALIST':
    case 'MANAGER':
      return 'Сумма будет зачтена в платёжное задание и учтена у привязанного сотрудника; его лимит и резерв обновятся.';
    default:
      return 'Получатель задания не привязан. Подтверждение оплаты заблокировано.';
  }
}

export function manualPaymentTaskTargetKey(option: ManualPaymentTaskAccountingTargetOptionLike): string {
  return option.key?.trim() || `${option.kind}:${option.profileId ?? ''}`;
}

export function manualPaymentTaskOverrunAcknowledgementRequired(
  option: ManualPaymentTaskAccountingTargetOptionLike | null | undefined
): boolean {
  return Boolean(option
    && (option.overrunAcknowledgementRequired || (option.projectedOverrunKopecks ?? 0) > 0));
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : null;
}
