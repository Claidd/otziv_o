/**
 * Russian names of the payment statuses the server sends: the statuses of a payment link and the two
 * the order's «Получатель оплаты» sheet gets while there is no link yet. Shared by the payments list and that sheet.
 */
const LABELS: Record<string, string> = {
  CREATED: 'Создана',
  INITIATED: 'Форма',
  AUTHORIZED: 'Авторизован',
  WAITING_MANUAL_PAYMENT: 'Ждет перевод',
  MANUAL_REPORTED: 'Клиент оплатил',
  TEST_CONFIRMED: 'Тест оплачен',
  CONFIRMED: 'Оплачен',
  REJECTED: 'Отклонен',
  CANCELED: 'Отменен',
  REVERSED: 'Отменен',
  PARTIAL_REVERSED: 'Частично отменен',
  REFUNDED: 'Возвращен',
  PARTIAL_REFUNDED: 'Частичный возврат',
  EXPIRED: 'Истек',
  FAILED: 'Ошибка',
  NEEDS_RECONCILIATION: 'Требует сверки с банком',
  NOT_CREATED: 'Счёт ещё не выставлен',
  WAITING_BANK: 'Ждём ответ банка'
};

/** A status this list does not know is shown as the server sent it: nothing is hidden or guessed. */
export function paymentLinkStatusLabel(status: string | null | undefined): string {
  const code = (status ?? '').trim();
  return LABELS[code] ?? code;
}
