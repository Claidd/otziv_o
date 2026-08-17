import type {
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

export function mobileManualCardRecipientLabel(candidate: ManualCardPaymentRecipientOption): string {
  return mobileTaskAwareRecipientLabel(candidate);
}

export function mobileManualCardMoney(kopecks: number | null | undefined): string {
  return `${new Intl.NumberFormat('ru-RU', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format((kopecks ?? 0) / 100)} ₽`;
}
