import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  buildManualCardPaymentConfirmationRequest,
  buildManualPaymentConfirmationRequest,
  exactPaymentAmountKopecks,
  manualCardPaymentConfirmationPrompt,
  manualCardPaymentFallbackAccessDecision,
  manualCardPaymentFallbackDecision,
  shouldSubmitManualCardPaymentFallback,
  UNFINISHED_PROVIDER_PAYMENT_MESSAGE
} = loadTsModule('src/app/shared/manual-payment-confirmation.ts');

const exactConflict = (message = UNFINISHED_PROVIDER_PAYMENT_MESSAGE) => ({
  status: 409,
  error: { message }
});

test('requires a comment or receipt for a common-invoice manual payment', () => {
  assert.equal(buildManualPaymentConfirmationRequest('  ', ''), null);
  assert.equal(
    JSON.stringify(buildManualPaymentConfirmationRequest('  сверено по выписке  ', '  ')),
    JSON.stringify({ comment: 'сверено по выписке', receiptUrl: '' })
  );
  assert.equal(
    JSON.stringify(buildManualPaymentConfirmationRequest('', ' https://example.test/receipt ')),
    JSON.stringify({ comment: '', receiptUrl: 'https://example.test/receipt' })
  );
});

test('builds the privileged paid fallback with exact kopecks and both assertions', () => {
  assert.equal(buildManualCardPaymentConfirmationRequest(0, 'проверено'), null);
  assert.equal(buildManualCardPaymentConfirmationRequest(100000.5, 'проверено'), null);
  assert.equal(buildManualCardPaymentConfirmationRequest(100000, '  '), null);
  assert.equal(
    JSON.stringify(buildManualCardPaymentConfirmationRequest(100000, '  подтверждено владельцем  ')),
    JSON.stringify({
      recipientStatementChecked: true,
      paymentReceived: true,
      receivedAmountKopecks: 100000,
      note: 'подтверждено владельцем'
    })
  );
});

test('allows the exact unfinished-payment 409 only for owner or admin', () => {
  for (const role of ['OWNER', 'ADMIN', 'owner', ' admin ']) {
    const access = manualCardPaymentFallbackAccessDecision(exactConflict(), [role], 25047);
    assert.equal(access.allowed, true);
    const decision = manualCardPaymentFallbackDecision(exactConflict(), [role], 25047, 100000);
    assert.equal(decision.allowed, true);
    assert.equal(decision.exactConflict, true);
    assert.equal(decision.reason, 'allowed');
  }
});

test('does not allow manager, worker or anonymous to invoke the privileged fallback', () => {
  for (const roles of [['MANAGER'], ['WORKER'], []]) {
    const decision = manualCardPaymentFallbackDecision(exactConflict(), roles, 25047, 100000);
    assert.equal(decision.allowed, false);
    assert.equal(decision.exactConflict, true);
    assert.equal(decision.reason, 'insufficient-role');
  }
});

test('does not treat another 409 or another backend message as the privileged conflict', () => {
  const other409 = manualCardPaymentFallbackDecision(
    exactConflict('Другой конфликт'),
    ['OWNER'],
    25047,
    100000
  );
  assert.equal(other409.allowed, false);
  assert.equal(other409.exactConflict, false);
  assert.equal(other409.reason, 'not-exact-conflict');

  const otherStatus = manualCardPaymentFallbackDecision(
    { status: 400, error: UNFINISHED_PROVIDER_PAYMENT_MESSAGE },
    ['ADMIN'],
    25047,
    100000
  );
  assert.equal(otherStatus.allowed, false);
  assert.equal(otherStatus.exactConflict, false);
});

test('rejects missing order id or non-positive/non-exact amount with an explicit safe error', () => {
  const missingOrder = manualCardPaymentFallbackDecision(exactConflict(), ['OWNER'], null, 100000);
  assert.equal(missingOrder.allowed, false);
  assert.equal(missingOrder.reason, 'missing-order-id');
  assert.match(missingOrder.userMessage, /номер заказа/i);
  assert.match(missingOrder.userMessage, /не закрыта/i);

  const invalidAmount = manualCardPaymentFallbackDecision(exactConflict(), ['ADMIN'], 25047, null);
  assert.equal(invalidAmount.allowed, false);
  assert.equal(invalidAmount.reason, 'invalid-amount');
  assert.match(invalidAmount.userMessage, /точную положительную сумму/i);

  assert.equal(exactPaymentAmountKopecks(undefined), null);
  assert.equal(exactPaymentAmountKopecks(0), null);
  assert.equal(exactPaymentAmountKopecks(10.001), null);
  assert.equal(exactPaymentAmountKopecks(1000), 100000);
  assert.equal(exactPaymentAmountKopecks(47.5), 4750);
});

test('requires the explicit confirmation before building/sending the fallback request', () => {
  const decision = manualCardPaymentFallbackDecision(exactConflict(), ['OWNER'], 25047, 100000);
  assert.equal(shouldSubmitManualCardPaymentFallback(decision, false), false);
  assert.equal(shouldSubmitManualCardPaymentFallback(decision, true), true);

  const prompt = manualCardPaymentConfirmationPrompt(25047, 100000);
  assert.ok(prompt);
  assert.match(prompt.message, /выписку/i);
  assert.match(prompt.message, /полная сумма/i);
  assert.match(prompt.message, /1\s000,00\s₽/);
  assert.match(prompt.message, /ссылка T-Bank\/СБП будет .* закрыта/i);
  assert.match(prompt.confirmText, /закрыть ссылку/i);
  assert.equal(manualCardPaymentConfirmationPrompt(0, 100000), null);
  assert.equal(manualCardPaymentConfirmationPrompt(25047, 0), null);
});
