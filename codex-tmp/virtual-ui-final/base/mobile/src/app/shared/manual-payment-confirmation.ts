export interface ManualPaymentConfirmationEvidence {
  comment: string;
  receiptUrl: string;
}

export interface ManualCardPaymentConfirmationEvidence {
  recipientStatementChecked: true;
  paymentReceived: true;
  receivedAmountKopecks: number;
  note: string;
  receiptUrl?: string;
}

export const UNFINISHED_PROVIDER_PAYMENT_MESSAGE =
  'У заказа есть незавершенный T-Bank/СБП платеж. Проверьте его в журнале перед ручным закрытием.';

export type ManualCardPaymentFallbackReason =
  | 'allowed'
  | 'not-exact-conflict'
  | 'insufficient-role'
  | 'missing-order-id'
  | 'invalid-amount';

export interface ManualCardPaymentFallbackDecision {
  allowed: boolean;
  exactConflict: boolean;
  reason: ManualCardPaymentFallbackReason;
  userMessage: string | null;
}

export interface ManualCardPaymentConfirmationPrompt {
  title: string;
  message: string;
  confirmText: string;
}

export function buildManualPaymentConfirmationRequest(
  commentValue: string | null | undefined,
  receiptUrlValue: string | null | undefined
): ManualPaymentConfirmationEvidence | null {
  const comment = commentValue?.trim() ?? '';
  const receiptUrl = receiptUrlValue?.trim() ?? '';
  if (!comment && !receiptUrl) {
    return null;
  }
  return { comment, receiptUrl };
}

export function buildManualCardPaymentConfirmationRequest(
  receivedAmountKopecks: number,
  noteValue: string | null | undefined,
  receiptUrlValue?: string | null
): ManualCardPaymentConfirmationEvidence | null {
  const note = noteValue?.trim() ?? '';
  const receiptUrl = receiptUrlValue?.trim() ?? '';
  if (!Number.isSafeInteger(receivedAmountKopecks) || receivedAmountKopecks <= 0 || !note) {
    return null;
  }
  return {
    recipientStatementChecked: true,
    paymentReceived: true,
    receivedAmountKopecks,
    note,
    ...(receiptUrl ? { receiptUrl } : {})
  };
}

export function exactPaymentAmountKopecks(amountRubles: number | null | undefined): number | null {
  if (!Number.isFinite(amountRubles) || (amountRubles ?? 0) <= 0) {
    return null;
  }
  const rawKopecks = (amountRubles as number) * 100;
  const amountKopecks = Math.round(rawKopecks);
  if (!Number.isSafeInteger(amountKopecks)
      || amountKopecks <= 0
      || Math.abs(rawKopecks - amountKopecks) > 1e-6) {
    return null;
  }
  return amountKopecks;
}

export function manualCardPaymentFallbackDecision(
  error: unknown,
  roles: readonly string[] | null | undefined,
  orderId: number | null | undefined,
  amountKopecks: number | null | undefined
): ManualCardPaymentFallbackDecision {
  const accessDecision = manualCardPaymentFallbackAccessDecision(error, roles, orderId);
  if (!accessDecision.allowed) {
    return accessDecision;
  }
  if (!Number.isSafeInteger(amountKopecks) || (amountKopecks ?? 0) <= 0) {
    return decision(
      false,
      true,
      'invalid-amount',
      'Не удалось определить точную положительную сумму заказа. Оплата не отмечена, ссылка T-Bank/СБП не закрыта.'
    );
  }
  return decision(true, true, 'allowed');
}

export function manualCardPaymentFallbackAccessDecision(
  error: unknown,
  roles: readonly string[] | null | undefined,
  orderId: number | null | undefined
): ManualCardPaymentFallbackDecision {
  const exactConflict = apiErrorStatus(error) === 409
    && apiErrorMessage(error) === UNFINISHED_PROVIDER_PAYMENT_MESSAGE;
  if (!exactConflict) {
    return decision(false, false, 'not-exact-conflict');
  }

  const normalizedRoles = new Set((roles ?? []).map((role) => role.trim().toUpperCase()));
  if (!normalizedRoles.has('OWNER') && !normalizedRoles.has('ADMIN')) {
    return decision(false, true, 'insufficient-role');
  }
  if (!Number.isSafeInteger(orderId) || (orderId ?? 0) <= 0) {
    return decision(
      false,
      true,
      'missing-order-id',
      'Не найден точный номер заказа. Оплата не отмечена, ссылка T-Bank/СБП не закрыта.'
    );
  }
  return decision(true, true, 'allowed');
}

export function shouldSubmitManualCardPaymentFallback(
  fallbackDecision: ManualCardPaymentFallbackDecision,
  explicitlyConfirmed: boolean
): boolean {
  return fallbackDecision.allowed && explicitlyConfirmed;
}

export function manualCardPaymentConfirmationPrompt(
  orderId: number,
  amountKopecks: number
): ManualCardPaymentConfirmationPrompt | null {
  if (!Number.isSafeInteger(orderId) || orderId <= 0
      || !Number.isSafeInteger(amountKopecks) || amountKopecks <= 0) {
    return null;
  }
  const amountLabel = `${new Intl.NumberFormat('ru-RU', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(amountKopecks / 100)} ₽`;
  return {
    title: 'Подтвердить ручную оплату',
    message: `Заказ #${orderId}: подтвердите, что вы проверили выписку счёта или карты получателя, `
      + `полная сумма ${amountLabel} фактически поступила. `
      + 'Незавершённая неоплаченная ссылка T-Bank/СБП будет безопасно закрыта, а заказ отмечен оплаченным.',
    confirmText: 'Деньги получены, закрыть ссылку'
  };
}

function decision(
  allowed: boolean,
  exactConflict: boolean,
  reason: ManualCardPaymentFallbackReason,
  userMessage: string | null = null
): ManualCardPaymentFallbackDecision {
  return { allowed, exactConflict, reason, userMessage };
}

function apiErrorStatus(error: unknown): number | null {
  if (!isRecord(error)) {
    return null;
  }
  const status = error['status'];
  return typeof status === 'number' && Number.isFinite(status) ? status : null;
}

function apiErrorMessage(error: unknown): string {
  if (!isRecord(error)) {
    return '';
  }
  const payload = error['error'];
  if (typeof payload === 'string') {
    return payload.trim();
  }
  if (!isRecord(payload)) {
    return '';
  }
  for (const key of ['message', 'detail', 'error']) {
    const value = payload[key];
    if (typeof value === 'string' && value.trim()) {
      return value.trim();
    }
  }
  return '';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
