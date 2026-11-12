import type {
  ManagerManualCardPaymentResult,
  ManualCardPaymentContext,
  ManualCardPaymentRecipientOption
} from '../core/api.service';
import {
  mobileTaskAwareRecipientKey,
  mobileTaskAwareRecipientLabel
} from './manual-payment-routing';

function mobileMatchingManualCardRecipient(
  candidates: readonly ManualCardPaymentRecipientOption[],
  expectedRecipient: ManualCardPaymentRecipientOption | null | undefined
): ManualCardPaymentRecipientOption | null {
  if (!expectedRecipient) {
    return null;
  }
  const expectedKey = mobileManualCardRecipientKey(expectedRecipient);
  if (!expectedKey) {
    return null;
  }
  return candidates.find((candidate) => mobileManualCardRecipientKey(candidate) === expectedKey) ?? null;
}

export function mobileManualCardPaymentIsCompleted(
  result: ManagerManualCardPaymentResult | null | undefined
): boolean {
  return result?.status === 'COMPLETED';
}

export function mobileManualCardRecipientKey(candidate: ManualCardPaymentRecipientOption): string {
  return mobileTaskAwareRecipientKey(candidate);
}

export function mobileOriginalManualCardRecipient(
  context: ManualCardPaymentContext
): ManualCardPaymentRecipientOption | null {
  if (!context.originalRecipient) {
    return null;
  }
  return mobileMatchingManualCardRecipient(context.candidates ?? [], context.originalRecipient);
}

export function mobileManualCardPaymentSelectionRecipient(
  context: ManualCardPaymentContext
): ManualCardPaymentRecipientOption | null {
  return context.recipientSelectionFrozen
    ? mobileMatchingManualCardRecipient(context.candidates ?? [], context.preparedRecipient)
    : mobileOriginalManualCardRecipient(context);
}

export function mobileManualCardPaymentSubmission(
  context: ManualCardPaymentContext,
  selectedRecipient: ManualCardPaymentRecipientOption | null,
  reasonValue: string,
  receiptUrlValue: string
): { recipient: ManualCardPaymentRecipientOption; reason: string; receiptUrl: string | null } | null {
  const recipient = context.recipientSelectionFrozen
    ? mobileManualCardPaymentSelectionRecipient(context)
    : selectedRecipient;
  if (!recipient) {
    return null;
  }
  return {
    recipient,
    reason: context.recipientSelectionFrozen ? (context.preparedReason ?? '') : reasonValue.trim(),
    receiptUrl: context.recipientSelectionFrozen
      ? context.preparedReceiptUrl
      : (receiptUrlValue.trim() || null)
  };
}

/**
 * What the dialog says above the form when the invoice of the order is the owner's bank link: a payment by
 * the link is marked by the bank, and the owner is offered only where the bank profile lets the owner take
 * card transfers. Empty for any other invoice and for a repeated, already fixed confirmation.
 */
export function mobileManualCardBankLinkNotice(context: ManualCardPaymentContext): string {
  if (!context.bankLinkRoute || context.recipientSelectionFrozen) {
    return '';
  }
  return (context.candidates ?? []).some(candidate => candidate.recipientType === 'OWNER')
    ? 'Счёт этого заказа — ссылка банка владельца. Оплату по ссылке банк отмечает сам: отмечать её здесь не нужно. '
      + 'Если клиент перевёл мимо ссылки владельцу на карту — выберите «Владелец»: запрос уйдёт владельцу, '
      + 'и заказ закроется только после его подтверждения. Если клиент перевёл сотруднику — выберите сотрудника.'
    : 'Счёт этого заказа — ссылка банка владельца. Оплату по ссылке банк отмечает сам, а на карту этот владелец '
      + 'оплату не принимает, поэтому его в списке нет. Выберите сотрудника, только если клиент перевёл деньги ему напрямую.';
}

export function mobileManualCardRecipientLabel(candidate: ManualCardPaymentRecipientOption): string {
  return mobileTaskAwareRecipientLabel(candidate);
}

export function mobileManualCardMoney(kopecks: number | null | undefined): string {
  return `${new Intl.NumberFormat('ru-RU', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format((kopecks ?? 0) / 100)} ₽`;
}
