import { mobilePaymentRouteErrorCode } from './manual-payment-routing';

export interface ManualPaymentConfirmationEvidence {
  comment: string;
  receiptUrl: string;
}

export const UNFINISHED_PROVIDER_PAYMENT_MESSAGE =
  'У заказа есть незавершенный банковский/СБП платеж. Проверьте его в журнале перед ручным закрытием.';
const LEGACY_UNFINISHED_PROVIDER_PAYMENT_MESSAGE =
  'У заказа есть незавершенный T-Bank/СБП платеж. Проверьте его в журнале перед ручным закрытием.';
const ACTUAL_RECIPIENT_REQUIRED_MESSAGE_HINT = 'фактического получателя';

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

export function manualCardPaymentFallbackAccessDecision(
  error: unknown,
  roles: readonly string[] | null | undefined,
  orderId: number | null | undefined
): ManualCardPaymentFallbackDecision {
  const exactConflict = isManualCardPaymentFallbackConflict(error);
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
      'Не найден точный номер заказа. Оплата не отмечена, банковская/СБП ссылка не закрыта.'
    );
  }
  return decision(true, true, 'allowed');
}

export function isManualCardPaymentFallbackConflict(error: unknown): boolean {
  const message = apiErrorMessage(error);
  return apiErrorStatus(error) === 409 && (
    mobilePaymentRouteErrorCode(error) === 'ACTUAL_RECIPIENT_REQUIRED'
      || message === UNFINISHED_PROVIDER_PAYMENT_MESSAGE
      || message === LEGACY_UNFINISHED_PROVIDER_PAYMENT_MESSAGE
      || isUnfinishedProviderPaymentMessage(message)
      || message.toLocaleLowerCase('ru-RU').includes(ACTUAL_RECIPIENT_REQUIRED_MESSAGE_HINT)
  );
}

function isUnfinishedProviderPaymentMessage(message: string): boolean {
  const normalized = message.toLocaleLowerCase('ru-RU').replaceAll('ё', 'е');
  return normalized.includes('незавершенн')
    && normalized.includes('платеж')
    && normalized.includes('журнал');
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
