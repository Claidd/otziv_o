/**
 * «Вернуть заказ в счёт» of a common invoice: the same rules and wording as the web window
 * (frontend/src/app/features/admin/common-billing/common-invoice-return-orders.component.ts).
 * The server re-checks every return; these functions only decide what the page offers and says.
 */

export interface CommonInvoiceReturnEligibility {
  status: string;
  sentAt?: string | null;
  paymentRouteSelectedAt?: string | null;
  paperInvoiceIssuedAt?: string | null;
  invoicePaymentMode?: string | null;
  invoicePurpose?: string | null;
}

export interface ReturnableOrder {
  orderId: number;
  companyId: number;
  companyTitle: string;
  orderTitle: string;
  status: string;
  /** What the order would add to the invoice; 0 when the amount cannot be calculated. */
  amountKopecks: number;
}

export const RETURN_ORDER_REFUSAL_LEAD = 'Заказ не возвращён, счёт не изменён.';

export const RETURN_ORDERS_INTRO = 'Заказы компаний этой связи, которые сейчас идут отдельно от общего счёта.';

export const RETURN_ORDERS_EMPTY = 'Возвращать нечего. У компаний этой связи нет заказов вне общего счёта: из счёта ничего не исключали, '
  + 'либо исключённый заказ уже завершён, оплачен, в архиве, в бане или «Не оплачено» — такие заказы в счёт не возвращаются. '
  + 'Вернуть можно незавершённый заказ в статусах от «Новый» до «Напоминание».';

export const RETURN_ORDERS_FIXED = 'Состав счёта уже зафиксирован. Что делать: ведите заказ отдельно — он оплачивается своим счётом.';

/**
 * Whether the button is offered: the invoice is still being collected, was not sent,
 * has no chosen recipient and is not a paper invoice.
 */
export function canReturnOrdersToCommonInvoice(invoice: CommonInvoiceReturnEligibility | null | undefined): boolean {
  return Boolean(
    invoice
      && (invoice.status === 'COLLECTING' || invoice.status === 'READY')
      && !invoice.sentAt
      && !invoice.paymentRouteSelectedAt
      && !invoice.paperInvoiceIssuedAt
      && invoice.invoicePaymentMode !== 'OWNER_PAPER_INVOICE'
      && invoice.invoicePurpose !== 'BAD_REVIEW_SUCCESSOR'
  );
}

export function returnOrderMoney(kopecks: number | null | undefined): string {
  return `${new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format((kopecks ?? 0) / 100)} ₽`;
}

/**
 * One line of the list under the company name: the order, its status and what it adds to the invoice.
 * The server title already names the order («Заказ №28523 · филиал»), so the number is added only without it.
 */
export function returnOrderLabel(order: ReturnableOrder): string {
  const title = (order.orderTitle || '').trim() || `Заказ №${order.orderId}`;
  const amount = order.amountKopecks > 0 ? returnOrderMoney(order.amountKopecks) : 'сумма пересчитается';
  return `${title} · ${order.status || 'статус не указан'} · ${amount}`;
}

/** What the confirmation will do, said before it is pressed. */
export function returnOrderConsequence(order: ReturnableOrder, invoiceId: number): string {
  const amount = order.amountKopecks > 0
    ? `сумма счёта вырастет на ${returnOrderMoney(order.amountKopecks)}`
    : 'сумма счёта будет пересчитана';
  return `Заказ №${order.orderId} вернётся в общий счёт №${invoiceId}: ${amount}. `
    + 'Отдельный счёт заказа закроется, если по нему ещё не платили; если оплата уже начата, заказ не вернётся и ничего не изменится. '
    + 'Клиенту сейчас ничего не отправляется — заказ уйдёт ему в составе общего счёта.';
}

/** A refusal always says that nothing changed and what to do next, whatever the server answered. */
export function returnOrderRefusal(message: string | null | undefined): string {
  const text = (message || '').trim();
  const lead = text.startsWith(RETURN_ORDER_REFUSAL_LEAD) ? text : `${RETURN_ORDER_REFUSAL_LEAD} ${text}`.trim();
  return lead.includes('Что делать')
    ? lead
    : `${lead} Что делать: обновите страницу и повторите возврат; если отказ повторится, ведите заказ отдельно.`;
}
