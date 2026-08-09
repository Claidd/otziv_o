export type ManualPaymentUnpaidCloseCandidate = {
  orderId?: number | null;
  amount: number;
  status: string;
  paymentMethod?: string | null;
  paidAt?: string | null;
  manualConfirmedAt?: string | null;
  manualConfirmedBy?: string | null;
  confirmedAmountKopecks?: number | null;
  receiptStatus?: string | null;
  tbankPaymentId?: string | null;
};

const MANUAL_PAYMENT_METHODS = new Set(['MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK']);
const CLOSEABLE_STATUSES = new Set(['WAITING_MANUAL_PAYMENT', 'MANUAL_REPORTED', 'EXPIRED']);

export function canCloseManualPaymentAsUnpaid(link: ManualPaymentUnpaidCloseCandidate): boolean {
  return MANUAL_PAYMENT_METHODS.has(link.paymentMethod ?? '')
    && CLOSEABLE_STATUSES.has(link.status)
    && !link.paidAt
    && !link.manualConfirmedAt
    && !(link.manualConfirmedBy ?? '').trim()
    && !(Number(link.confirmedAmountKopecks ?? 0) > 0)
    && link.receiptStatus !== 'MARKED'
    && link.receiptStatus !== 'LEGACY_NOT_REQUIRED'
    && !(link.tbankPaymentId ?? '').trim();
}

export function manualPaymentUnpaidCloseConfirmation(link: ManualPaymentUnpaidCloseCandidate): string {
  return `Вы проверили выписку счета или карты получателя и подтверждаете, что перевод по заказу №${link.orderId ?? '-'} на сумму ${link.amount} руб. НЕ поступил?\n\n`
    + 'Будет закрыта только эта ручная инструкция. Заказ НЕ будет отмечен оплаченным и НЕ перейдет в статус «Не оплачено». Если перевод найден или вы не уверены — нажмите «Отмена».';
}

export function manualPaymentUnpaidCloseNotePrompt(): string {
  return 'Обязательная заметка для истории проверки: укажите дату/период выписки и счет или карту получателя (без полного номера).';
}
