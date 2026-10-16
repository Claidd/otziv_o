import type { AdminPaymentLinkResponse } from '../core/api.service';

const closeableStatuses = new Set(['WAITING_MANUAL_PAYMENT', 'MANUAL_REPORTED', 'EXPIRED']);
const manualMethods = new Set(['MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK']);

export function canCloseManualPaymentAsUnpaid(link: AdminPaymentLinkResponse): boolean {
  return !link.archived
    && manualMethods.has(link.paymentMethod ?? '')
    && closeableStatuses.has(link.status)
    && !link.paidAt
    && !link.manualConfirmedAt
    && !(link.manualConfirmedBy ?? '').trim()
    && !(Number(link.confirmedAmountKopecks ?? 0) > 0)
    && link.receiptStatus !== 'MARKED'
    && link.receiptStatus !== 'LEGACY_NOT_REQUIRED'
    && !(link.tbankPaymentId ?? '').trim();
}

export function canConfirmContractorPaymentSource(link: AdminPaymentLinkResponse): boolean {
  return !link.archived
    && link.manualSource === 'CONTRACTOR_PAYMENT_PROFILE'
    && link.paymentMethod === 'MANUAL_MOBILE_BANK'
    && ['WAITING_MANUAL_PAYMENT', 'MANUAL_REPORTED', 'EXPIRED', 'CANCELED', 'AMOUNT_MISMATCH']
      .includes(link.status);
}

export function confirmedTotalKopecks(input: string, maximumKopecks: number): number | null {
  const value = input.replace(/\s/g, '').replace(',', '.');
  if (!/^\d+(?:\.\d{1,2})?$/.test(value)) return null;
  const [rubles, kopecks = ''] = value.split('.');
  const amount = Number(rubles) * 100 + Number(kopecks.padEnd(2, '0'));
  return Number.isSafeInteger(amount) && amount > 0 && amount <= maximumKopecks ? amount : null;
}

export function canMarkLegacyReceiptNotRequired(link: AdminPaymentLinkResponse, now = Date.now()): boolean {
  const paidAt = link.paidAt ? new Date(link.paidAt).getTime() : NaN;
  return !link.archived
    && manualMethods.has(link.paymentMethod ?? '')
    && link.status === 'CONFIRMED'
    && link.receiptStatus === 'PENDING'
    && Number.isFinite(paidAt)
    && now - paidAt >= 30 * 24 * 60 * 60 * 1000;
}
