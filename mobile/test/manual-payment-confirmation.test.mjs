import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  buildManualPaymentConfirmationRequest,
  manualCardPaymentFallbackAccessDecision,
  UNFINISHED_PROVIDER_PAYMENT_MESSAGE
} =loadTsModule('src/app/shared/manual-payment-confirmation.ts');

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

test('allows the exact unfinished-payment 409 only for owner or admin', () => {
  for (const role of ['OWNER', 'ADMIN', 'owner', ' admin ']) {
    const access = manualCardPaymentFallbackAccessDecision(exactConflict(), [role], 25047);
    assert.equal(access.allowed, true);
    assert.equal(access.exactConflict, true);
    assert.equal(access.reason, 'allowed');
  }
});

test('does not allow manager, worker or anonymous to invoke the privileged fallback', () => {
  for (const roles of [['MANAGER'], ['WORKER'], []]) {
    const decision = manualCardPaymentFallbackAccessDecision(exactConflict(), roles, 25047);
    assert.equal(decision.allowed, false);
    assert.equal(decision.exactConflict, true);
    assert.equal(decision.reason, 'insufficient-role');
  }
});

test('does not treat another 409 or another backend message as the privileged conflict', () => {
  const other409 = manualCardPaymentFallbackAccessDecision(
    exactConflict('Другой конфликт'),
    ['OWNER'],
    25047
  );
  assert.equal(other409.allowed, false);
  assert.equal(other409.exactConflict, false);
  assert.equal(other409.reason, 'not-exact-conflict');

  const otherStatus = manualCardPaymentFallbackAccessDecision(
    { status: 400, error: UNFINISHED_PROVIDER_PAYMENT_MESSAGE },
    ['ADMIN'],
    25047
  );
  assert.equal(otherStatus.allowed, false);
  assert.equal(otherStatus.exactConflict, false);
});

test('accepts the structured actual-recipient-required conflict without matching localized text', () => {
  const error = {
    status: 409,
    error: { code: 'ACTUAL_RECIPIENT_REQUIRED', detail: 'Текст может меняться' }
  };
  const decision = manualCardPaymentFallbackAccessDecision(error, ['OWNER'], 25047);
  assert.equal(decision.allowed, true);
  assert.equal(decision.exactConflict, true);
  assert.equal(decision.reason, 'allowed');
});

test("accepts the legacy actual-recipient-required message without a structured code", () => {
  const error = {
    status: 409,
    error: { message: "Нужно заново выбрать фактического получателя оплаты." }
  };
  const decision = manualCardPaymentFallbackAccessDecision(error, ["OWNER"], 25047);
  assert.equal(decision.allowed, true);
  assert.equal(decision.exactConflict, true);
  assert.equal(decision.reason, "allowed");
});
test('rejects a missing order id with an explicit safe error', () => {
  const missingOrder = manualCardPaymentFallbackAccessDecision(exactConflict(), ['OWNER'], null);
  assert.equal(missingOrder.allowed, false);
  assert.equal(missingOrder.reason, 'missing-order-id');
  assert.match(missingOrder.userMessage, /номер заказа/i);
  assert.match(missingOrder.userMessage, /не закрыта/i);
});
