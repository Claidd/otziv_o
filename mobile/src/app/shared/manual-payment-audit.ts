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

/** «Дата и время поступления по выписке» from a datetime-local field: an existing minute, as on the site. */
export function validReceiptEffectiveAt(input: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(input);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return year > 0 && month >= 1 && month <= 12
    && day >= 1 && day <= daysInMonth[month - 1]
    && hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
}
