export interface CommonInvoiceAttentionPolicy {
  latePayment: boolean;
  finalCancelCheck: boolean;
  paymentInitCheck: boolean;
  migrationQuarantine: boolean;
  requiresManualCheck: boolean;
}

export interface CommonInvoicePaymentEvidenceSource {
  tbankOrderId?: string | null;
  tbankPaymentId?: string | null;
  tbankPaymentAmountKopecks?: number | null;
  tbankTerminalLabel?: string | null;
  tbankTerminalKey?: string | null;
}

export interface CommonInvoicePaymentRefEvidenceSource {
  id: number;
  status?: string | null;
  orderId?: string | null;
  paymentId?: string | null;
  amountKopecks?: number | null;
  reason?: string | null;
  terminalLabel?: string | null;
  terminalKey?: string | null;
}

export interface CommonInvoicePaymentEvidenceItem {
  key: string;
  label: string;
  orderId: string;
  paymentId: string;
  amountKopecks: number | null;
  status: string;
  reason: string;
  terminalLabel: string;
  terminalKey: string;
}

export interface CommonInvoicePaymentEvidenceDetailsSource {
  summary: CommonInvoicePaymentEvidenceSource & {
    id: number;
    lastError?: string | null;
  };
  paymentRefs?: readonly CommonInvoicePaymentRefEvidenceSource[] | null;
  paymentEvidenceToken?: string | null;
}

export interface CommonInvoicePaymentEvidenceSnapshot {
  invoiceId: number;
  evidenceToken: string;
  evidence: CommonInvoicePaymentEvidenceItem[];
}

const PAYMENT_INIT_PREFIXES = [
  'payment_init_stale',
  'payment_init_conflict',
  'payment_init_exception',
  'payment_init_response_mismatch',
  'payment_init_response_collision',
  'payment_init_invalid_url',
  'payment_cached_invalid_url',
  'tbank_init_failed'
] as const;

const MANUALLY_CONFIRMABLE_MIGRATION_PAYMENT_ERROR =
  'migration_common_payment_registry:nonterminal_or_unknown_payment_ref_on_invoice';

export const COMMON_INVOICE_NO_PAYMENT_ACTION_LABEL = 'В T‑Bank оплаты нет — продолжить';

export function commonInvoicePaymentInitInstructions(): string[] {
  return [
    'В T-Бизнесе откройте «Интернет-эквайринг → Операции». В поле «Номер заказа» вводите только OrderId.',
    'PaymentId — это отдельный идентификатор платежа. Не вводите его в поле «Номер заказа»: переключите тип поиска на идентификатор платежа, если такой фильтр доступен, либо сверьте PaymentId в карточке найденной операции.',
    'Если найден активный платёж — не создавайте новую ссылку и дождитесь его итога.',
    'Если найден успешный платёж или операция, которой нужна отмена/возврат, — не нажимайте кнопку ниже. Обработайте операцию по её фактическому статусу.',
    `Только если ни один платёж не активен, не успешен и не требует отмены/возврата, нажмите «${COMMON_INVOICE_NO_PAYMENT_ACTION_LABEL}».`
  ];
}

export function commonInvoiceNoPaymentActionHint(): string {
  return `«${COMMON_INVOICE_NO_PAYMENT_ACTION_LABEL}» подтверждает только отсутствие оплаты `
    + 'по перечисленным OrderId и PaymentId в T‑Bank и закрывает техническую ошибку. '
    + 'Счёт не будет отмечен оплаченным и не перейдёт в статус «Не оплачено». '
    + 'Если клиент оплатил переводом на обычную карту, после этого отдельным действием «Оплачено» '
    + 'зафиксируйте перевод и добавьте комментарий или чек.';
}

export function commonInvoiceNoPaymentConfirmation(evidenceText: string): string {
  return 'РЕЗУЛЬТАТ ПРОВЕРКИ T‑BANK: ПО УКАЗАННЫМ ID ОПЛАТЫ НЕТ.\n\n'
    + 'Подтвердите, что вы проверили в T‑Bank ВСЕ перечисленные OrderId и PaymentId и ни один платёж по ним '
    + 'не активен, не успешен и не требует отмены или возврата.\n\n'
    + 'Если такой платёж найден, нажмите «Отмена» и не продолжайте.\n\n'
    + 'Это действие означает ТОЛЬКО отсутствие оплаты по перечисленным T‑Bank ID. '
    + 'Оно НЕ отмечает счёт оплаченным и НЕ переводит его в «Не оплачено», а только закрывает техническую ошибку.\n\n'
    + 'Если клиент оплатил переводом на обычную карту, после этого отдельным действием «Оплачено» '
    + 'зафиксируйте перевод и добавьте комментарий или чек.'
    + evidenceText;
}

export function commonInvoiceAttentionPolicy(
  lastError: string | null | undefined
): CommonInvoiceAttentionPolicy {
  const error = (lastError ?? '').trim().toLowerCase();
  const latePayment = error.startsWith('late_tbank_payment') || error.startsWith('late_payment_');
  const finalCancelCheck = error.startsWith('payment_cancel_failed_final');
  const migrationQuarantine = error.startsWith('migration_common_payment_registry:');
  const paymentInitCheck = PAYMENT_INIT_PREFIXES.some(prefix => error.startsWith(prefix))
    || error.startsWith(MANUALLY_CONFIRMABLE_MIGRATION_PAYMENT_ERROR);
  return {
    latePayment,
    finalCancelCheck,
    paymentInitCheck,
    migrationQuarantine,
    requiresManualCheck: latePayment || finalCancelCheck || paymentInitCheck || migrationQuarantine
  };
}

export function commonInvoicePaymentEvidence(
  invoice: CommonInvoicePaymentEvidenceSource | null | undefined,
  refs: readonly CommonInvoicePaymentRefEvidenceSource[] | null | undefined,
  includeEmptyInvoiceBinding = false
): CommonInvoicePaymentEvidenceItem[] {
  const invoiceHasEvidence = Boolean(
    invoice?.tbankOrderId?.trim()
      || invoice?.tbankPaymentId?.trim()
      || invoice?.tbankPaymentAmountKopecks != null
      || invoice?.tbankTerminalLabel?.trim()
      || invoice?.tbankTerminalKey?.trim()
  );
  return [
    ...(includeEmptyInvoiceBinding || invoiceHasEvidence ? [{
      key: 'invoice',
      label: 'Счёт',
      orderId: invoice?.tbankOrderId?.trim() || 'не сохранён',
      paymentId: invoice?.tbankPaymentId?.trim() || 'не сохранён',
      amountKopecks: invoice?.tbankPaymentAmountKopecks ?? null,
      status: '',
      reason: '',
      terminalLabel: invoice?.tbankTerminalLabel?.trim() || 'не сохранён',
      terminalKey: invoice?.tbankTerminalKey?.trim() || 'не сохранён'
    }] : []),
    ...(refs ?? []).map(ref => ({
      key: `ref-${ref.id}`,
      label: `Реестр #${ref.id}`,
      orderId: ref.orderId?.trim() || 'не сохранён',
      paymentId: ref.paymentId?.trim() || 'не сохранён',
      amountKopecks: ref.amountKopecks ?? null,
      status: ref.status?.trim() || '',
      reason: ref.reason?.trim() || '',
      terminalLabel: ref.terminalLabel?.trim() || 'не сохранён',
      terminalKey: ref.terminalKey?.trim() || 'не сохранён'
    }))
  ];
}

export function commonInvoicePaymentEvidenceSnapshot(
  details: CommonInvoicePaymentEvidenceDetailsSource | null | undefined,
  expectedInvoiceId: number | null | undefined
): CommonInvoicePaymentEvidenceSnapshot | null {
  const invoice = details?.summary;
  const evidenceToken = details?.paymentEvidenceToken?.trim() || '';
  const policy = commonInvoiceAttentionPolicy(invoice?.lastError);
  if (!invoice
    || !expectedInvoiceId
    || invoice.id !== expectedInvoiceId
    || !policy.paymentInitCheck
    || !evidenceToken) {
    return null;
  }
  return {
    invoiceId: invoice.id,
    evidenceToken,
    evidence: commonInvoicePaymentEvidence(
      invoice,
      details.paymentRefs,
      policy.migrationQuarantine
    )
  };
}

export function commonInvoicePaymentEvidenceConfirmationLines(
  evidence: readonly CommonInvoicePaymentEvidenceItem[],
  formatAmount: (amountKopecks: number) => string = amountKopecks => String(amountKopecks)
): string {
  return evidence.map(item => {
    const amount = item.amountKopecks === null ? 'не сохранена' : formatAmount(item.amountKopecks);
    return `${item.label}: OrderId ${item.orderId}, PaymentId ${item.paymentId}, сумма ${amount}, `
      + `статус ${item.status || 'не сохранён'}, причина ${item.reason || 'не сохранена'}, `
      + `терминал ${item.terminalLabel} (${item.terminalKey})`;
  }).join('\n');
}
