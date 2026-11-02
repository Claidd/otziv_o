import type { Context, PaperDocument } from './paper-invoice-dialog.component';

export interface PaperInvoiceListState {
  invoiceId: number;
  context: Context;
}

/** A readiness notice is independent of bank document creation and delivery. */
export function paperInvoiceWorkflow(
  invoice: { id: number; sentAt?: string | null; paperInvoiceIssuedAt?: string | null } | null | undefined,
  state: PaperInvoiceListState | null
) {
  const context = state?.invoiceId === invoice?.id ? state?.context : null;
  const documents = context?.documents ?? [];
  const active = documents.filter(d => d.stage !== 'CANCELLED');
  const ready = (d: PaperDocument) => ['FILES_READY', 'WAITING_PAYMENT', 'PAID'].includes(d.stage);
  const delivered = (d: PaperDocument) => ready(d) && d.invoiceDelivery === 'SENT' && d.actDelivery === 'SENT';
  const allPositions = context?.positions ?? [];
  const positions = allPositions.filter(p=>p.documentId !== 'ORDINARY');
  const ordinaryCount = allPositions.length - positions.length;
  const covers = (d: PaperDocument, orderId: number, documentId: string | null) =>
    d.id === documentId || d.orderIds?.includes(orderId)
      || (d.targetKind === 'COMMON_INVOICE' && d.targetId === invoice?.id);
  const preparedCount = positions.filter(p => p.paid || active.some(d => covers(d, p.orderId, p.documentId) && ready(d))).length;
  const deliveredCount = positions.filter(p => p.paid || active.some(d => covers(d, p.orderId, p.documentId) && delivered(d))).length;
  const allPrepared = positions.length > 0 && preparedCount === positions.length;
  const allDelivered = positions.length > 0 && deliveredCount === positions.length;
  const manualAllowed = Boolean(context && !context.enabled && documents.length === 0);
  const legacyDelivered = manualAllowed && Boolean(invoice?.paperInvoiceIssuedAt);
  return {
    manualAllowed,
    ordinaryCount,
    ordinaryOnly: ordinaryCount > 0 && positions.every(p=>p.paid),
    prepared: allPrepared || legacyDelivered,
    delivered: allDelivered || legacyDelivered,
    anyDelivered: active.some(delivered) || legacyDelivered,
    preparationText: !context ? 'Состояние документов ещё не подтверждено'
      : legacyDelivered ? 'Отправка счёта подтверждена вручную'
      : allPrepared ? (ordinaryCount ? 'Документы сформированы для всех заказов с бумажной оплатой' : 'Документы сформированы для всех заказов')
      : !active.length ? 'Счёт и акт ещё не созданы. Нажмите «Счёт и акт» и подтвердите выпуск.'
      : `Документы готовы для ${preparedCount} из ${positions.length} заказов. ${active.some(d => !ready(d)) ? 'Остальные документы формируются или требуют проверки.' : 'Выставите счёт на оставшиеся заказы.'}`,
    deliveryText: allDelivered || legacyDelivered ? 'Доставка всех комплектов подтверждена'
      : !active.some(ready) ? 'Отправка начнётся после формирования счёта и акта'
      : `Доставка подтверждена для ${deliveredCount} из ${positions.length} заказов. Ожидаем отправку и подтверждение остальных документов.`
  };
}
