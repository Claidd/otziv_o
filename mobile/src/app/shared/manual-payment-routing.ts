export type PaymentRouteErrorCode =
  | 'ACTUAL_RECIPIENT_REQUIRED'
  | 'PAYMENT_ROUTE_STALE'
  | 'TASK_TARGET_UNRESOLVED';

export type ManualPaymentTaskAccountingTargetKind =
  | 'UNRESOLVED'
  | 'EXTERNAL_TASK'
  | 'OWNER'
  | 'SPECIALIST'
  | 'MANAGER';

export interface MobileManualPaymentRecipientLike {
  key?: string | null;
  cashDestinationKind?: 'OWNER' | 'CONTRACTOR_PROFILE' | 'MANUAL_PAYMENT_TASK' | null;
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

const ROUTE_ERROR_CODES = new Set<PaymentRouteErrorCode>([
  'ACTUAL_RECIPIENT_REQUIRED',
  'PAYMENT_ROUTE_STALE',
  'TASK_TARGET_UNRESOLVED'
]);

const HISTORICAL_PRE_CUTOVER_MANUAL_CARD_RECIPIENT_KEY = 'LEGACY_PRE_CUTOVER_MANUAL_CARD';

export function mobilePaymentRouteErrorCode(error: unknown): PaymentRouteErrorCode | null {
  const outer = asRecord(error);
  const payload = asRecord(outer?.['error']) ?? outer;
  const properties = asRecord(payload?.['properties']);
  for (const value of [payload?.['code'], payload?.['errorCode'], payload?.['problemCode'], properties?.['code']]) {
    if (typeof value === 'string') {
      const normalized = value.trim().toUpperCase() as PaymentRouteErrorCode;
      if (ROUTE_ERROR_CODES.has(normalized)) {
        return normalized;
      }
    }
  }
  return null;
}

export function mobileRetryablePaymentRouteError(error: unknown): boolean {
  const code = mobilePaymentRouteErrorCode(error);
  return code === 'ACTUAL_RECIPIENT_REQUIRED' || code === 'PAYMENT_ROUTE_STALE';
}

export function mobilePaymentRouteErrorMessage(error: unknown, fallback: string): string {
  const code = mobilePaymentRouteErrorCode(error);
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

export function mobileTaskAwareRecipientKey(candidate: MobileManualPaymentRecipientLike): string {
  const explicit = candidate.key?.trim();
  if (explicit) {
    return explicit;
  }
  if (mobileIsTaskRecipient(candidate)) {
    return Number.isSafeInteger(candidate.manualPaymentTaskId)
      && Number.isSafeInteger(candidate.manualPaymentTaskGeneration)
      ? `TASK:${candidate.manualPaymentTaskId}:${candidate.manualPaymentTaskGeneration}`
      : '';
  }
  return candidate.recipientType
    ? `${candidate.recipientType}:${candidate.recipientProfileId ?? ''}`
    : '';
}

export function mobileIsTaskRecipient(candidate: MobileManualPaymentRecipientLike | null | undefined): boolean {
  return Boolean(candidate && (
    candidate.cashDestinationKind === 'MANUAL_PAYMENT_TASK'
      || candidate.manualPaymentTaskId != null
      || candidate.key?.trim().startsWith('TASK:')
  ));
}

export function mobileTaskAwareRecipientLabel(candidate: MobileManualPaymentRecipientLike): string {
  if (candidate.key?.trim() === HISTORICAL_PRE_CUTOVER_MANUAL_CARD_RECIPIENT_KEY) {
    return candidate.displayName?.trim() || candidate.label?.trim() || 'Историческая оплата до запуска';
  }
  if (mobileIsTaskRecipient(candidate)) {
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

export function mobileTaskAwareRecipientEffect(candidate: MobileManualPaymentRecipientLike): string {
  if (candidate.effectText?.trim()) {
    return candidate.effectText.trim();
  }
  if (!mobileIsTaskRecipient(candidate)) {
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

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : null;
}
