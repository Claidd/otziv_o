import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  COMMON_INVOICE_NO_PAYMENT_ACTION_LABEL,
  commonInvoiceAttentionPolicy,
  commonInvoiceNoPaymentActionHint,
  commonInvoiceNoPaymentConfirmation,
  commonInvoicePaymentInitInstructions,
  commonInvoicePaymentEvidence,
  commonInvoicePaymentEvidenceConfirmationLines,
  commonInvoicePaymentEvidenceSnapshot
} = loadTsModule('src/app/shared/common-invoice-attention-policy.ts');

test('explains the no-payment action and keeps it separate from paid and unpaid statuses', () => {
  assert.equal(COMMON_INVOICE_NO_PAYMENT_ACTION_LABEL, 'В T‑Bank оплаты нет — продолжить');
  assert.match(commonInvoiceNoPaymentActionHint(), /по перечисленным OrderId и PaymentId в T‑Bank/u);
  assert.match(commonInvoiceNoPaymentActionHint(), /не будет отмечен оплаченным/u);
  assert.match(commonInvoiceNoPaymentActionHint(), /не перейдёт в статус «Не оплачено»/u);
  assert.match(commonInvoiceNoPaymentActionHint(), /отдельным действием «Оплачено»/u);
  assert.match(commonInvoiceNoPaymentActionHint(), /комментарий или чек/u);
});

test('distinguishes OrderId search from PaymentId verification and branches by payment outcome', () => {
  const instructions = commonInvoicePaymentInitInstructions().join(' ');
  assert.match(instructions, /поле «Номер заказа» вводите только OrderId/u);
  assert.match(instructions, /PaymentId — это отдельный идентификатор платежа/u);
  assert.match(instructions, /Не вводите его в поле «Номер заказа»/u);
  assert.match(instructions, /переключите тип поиска на идентификатор платежа/u);
  assert.match(instructions, /найден активный платёж/u);
  assert.match(instructions, /найден успешный платёж/u);
  assert.match(instructions, /отмена\/возврат/u);
});

test('confirmation acknowledges only the no-actionable-payment outcome', () => {
  const confirmation = commonInvoiceNoPaymentConfirmation('\n\nOrderId example');
  assert.match(confirmation, /РЕЗУЛЬТАТ ПРОВЕРКИ T‑BANK: ПО УКАЗАННЫМ ID ОПЛАТЫ НЕТ/u);
  assert.match(confirmation, /ВСЕ перечисленные OrderId и PaymentId/u);
  assert.match(confirmation, /не активен, не успешен/u);
  assert.match(confirmation, /ТОЛЬКО отсутствие оплаты по перечисленным T‑Bank ID/u);
  assert.match(confirmation, /НЕ отмечает счёт оплаченным/u);
  assert.match(confirmation, /НЕ переводит его в «Не оплачено»/u);
  assert.match(confirmation, /отдельным действием «Оплачено»/u);
  assert.match(confirmation, /комментарий или чек/u);
  assert.match(confirmation, /OrderId example/u);
});

test('classifies all manually confirmable payment-init failures and quarantines unsupported migration conflicts', () => {
  assert.equal(commonInvoiceAttentionPolicy('payment_init_exception: PKIX').paymentInitCheck, true);
  assert.equal(
    commonInvoiceAttentionPolicy(
      'migration_common_payment_registry:nonterminal_or_unknown_payment_ref_on_invoice'
    ).paymentInitCheck,
    true
  );
  const unsupported = commonInvoiceAttentionPolicy(
    'migration_common_payment_registry:provider_identity_cross_invoice_collision'
  );
  assert.equal(unsupported.paymentInitCheck, false);
  assert.equal(unsupported.migrationQuarantine, true);
  assert.equal(unsupported.requiresManualCheck, true);
});

test('builds complete evidence using the persisted payment amount and terminal identity', () => {
  assert.equal(JSON.stringify(commonInvoicePaymentEvidence({
    tbankOrderId: 'invoice-order',
    tbankPaymentId: 'invoice-payment',
    tbankPaymentAmountKopecks: 475_000,
    tbankTerminalLabel: 'Основной терминал',
    tbankTerminalKey: 'terminal-key',
    amountKopecks: 999_999
  }, [{
    id: 8,
    status: 'INIT_CONFLICT',
    orderId: 'ref-order',
    paymentId: null,
    amountKopecks: 200_000,
    reason: 'init_exception_before_response',
    terminalLabel: 'Резервный терминал',
    terminalKey: 'reserve-key'
  }])), JSON.stringify([{
    key: 'invoice',
    label: 'Счёт',
    orderId: 'invoice-order',
    paymentId: 'invoice-payment',
    amountKopecks: 475_000,
    status: '',
    reason: '',
    terminalLabel: 'Основной терминал',
    terminalKey: 'terminal-key'
  }, {
    key: 'ref-8',
    label: 'Реестр #8',
    orderId: 'ref-order',
    paymentId: 'не сохранён',
    amountKopecks: 200_000,
    status: 'INIT_CONFLICT',
    reason: 'init_exception_before_response',
    terminalLabel: 'Резервный терминал',
    terminalKey: 'reserve-key'
  }]));
});

test('never substitutes the current invoice total for missing persisted payment evidence', () => {
  const [invoice] = commonInvoicePaymentEvidence({ amountKopecks: 999_999 }, [], true);
  assert.equal(invoice.amountKopecks, null);
});

test('captures a complete immutable payment snapshot only for the expected invoice with a token', () => {
  const details = {
    summary: {
      id: 97,
      lastError: 'payment_init_conflict: duplicate request',
      tbankOrderId: 'invoice-order',
      tbankPaymentId: 'invoice-payment',
      tbankPaymentAmountKopecks: 475_000,
      tbankTerminalLabel: 'Основной терминал',
      tbankTerminalKey: 'terminal-key'
    },
    paymentRefs: [{
      id: 8,
      status: 'INIT_CONFLICT',
      orderId: 'ref-order',
      paymentId: null,
      amountKopecks: 200_000,
      reason: 'init_exception_before_response',
      terminalLabel: 'Резервный терминал',
      terminalKey: 'reserve-key'
    }],
    paymentEvidenceToken: 'evidence-token-1'
  };

  const snapshot = commonInvoicePaymentEvidenceSnapshot(details, 97);
  details.paymentEvidenceToken = 'evidence-token-2';
  details.paymentRefs[0].orderId = 'changed-after-confirm-opened';

  assert.equal(snapshot.evidenceToken, 'evidence-token-1');
  assert.equal(JSON.stringify(snapshot.evidence.map(item => item.orderId)), JSON.stringify(['invoice-order', 'ref-order']));
  assert.equal(commonInvoicePaymentEvidenceSnapshot(details, 98), null);
  assert.equal(commonInvoicePaymentEvidenceSnapshot({ ...details, paymentEvidenceToken: '  ' }, 97), null);
  assert.equal(commonInvoicePaymentEvidenceSnapshot(null, 97), null);
});

test('confirmation lines include amount, status, reason and terminal without credentials', () => {
  const lines = commonInvoicePaymentEvidenceConfirmationLines([{
    key: 'ref-8',
    label: 'Реестр #8',
    orderId: 'ref-order',
    paymentId: 'ref-payment',
    amountKopecks: 200_000,
    status: 'INIT_CONFLICT',
    reason: 'init_exception_before_response',
    terminalLabel: 'Резервный терминал',
    terminalKey: 'reserve-key'
  }], value => `${value / 100} ₽`);

  assert.match(lines, /сумма 2000 ₽/u);
  assert.match(lines, /статус INIT_CONFLICT/u);
  assert.match(lines, /причина init_exception_before_response/u);
  assert.match(lines, /терминал Резервный терминал \(reserve-key\)/u);
  assert.doesNotMatch(lines, /password|secret|token/iu);
});
