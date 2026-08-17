import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  mobileManualCardPaymentSelectionRecipient,
  mobileManualCardPaymentSubmission,
  mobileManualCardRecipientKey,
  mobileOriginalManualCardRecipient
} = loadTsModule('src/app/shared/mobile-manual-card-payment.ts');

const owner = {
  recipientType: 'OWNER',
  recipientProfileId: null,
  recipientUserId: 1,
  displayName: 'Владелец',
  availableKopecks: 0,
  projectedOverrunKopecks: 0
};
const specialist = {
  recipientType: 'SPECIALIST',
  recipientProfileId: 25,
  recipientUserId: 5,
  displayName: 'Елена',
  availableKopecks: 858000,
  projectedOverrunKopecks: 0
};

const context = (overrides = {}) => ({
  orderId: 123,
  amountKopecks: 100000,
  originalRecipient: specialist,
  candidates: [owner, specialist],
  anomalyWarning: null,
  recipientSelectionFrozen: false,
  preparedRecipient: null,
  preparedReason: null,
  preparedReceiptUrl: null,
  ...overrides
});

test('matches the frozen original using explicit backend recipient fields', () => {
  const fixture = context();

  assert.equal(mobileManualCardRecipientKey(fixture.originalRecipient), 'SPECIALIST:25');
  assert.equal(mobileOriginalManualCardRecipient(fixture), specialist);
});

test('fails closed instead of defaulting to the first candidate', () => {
  const fixture = context({ candidates: [owner] });

  assert.equal(mobileOriginalManualCardRecipient(fixture), null);
});

test('frozen retry submits exact prepared recipient, reason and nullable receipt', () => {
  const frozen = context({
    recipientSelectionFrozen: true,
    preparedRecipient: owner,
    preparedReason: '  сохранённая причина  ',
    preparedReceiptUrl: null
  });

  assert.equal(mobileManualCardPaymentSelectionRecipient(frozen), owner);
  assert.equal(
    JSON.stringify(mobileManualCardPaymentSubmission(
      frozen, specialist, 'другая причина', 'https://wrong.test'
    )),
    JSON.stringify({ recipient: owner, reason: '  сохранённая причина  ', receiptUrl: null })
  );
});

test('frozen retry fails closed when prepared recipient is missing from candidates', () => {
  const frozen = context({
    recipientSelectionFrozen: true,
    candidates: [owner],
    preparedRecipient: specialist,
    preparedReason: 'сохранено'
  });

  assert.equal(mobileManualCardPaymentSelectionRecipient(frozen), null);
});

test('task source keeps its frozen key even when accounting maps to a specialist profile', () => {
  const taskRecipient = {
    key: 'TASK:16:4',
    cashDestinationKind: 'MANUAL_PAYMENT_TASK',
    recipientType: 'SPECIALIST',
    recipientProfileId: 25,
    displayName: 'Наталья',
    manualPaymentTaskId: 16,
    manualPaymentTaskGeneration: 4,
    taskTargetKind: 'SPECIALIST',
    taskRecipientName: 'Наталья',
    accountingTargetLabel: 'Специалист · Наталья'
  };
  const fixture = context({
    originalRecipient: taskRecipient,
    candidates: [owner, specialist, taskRecipient]
  });

  assert.equal(mobileManualCardRecipientKey(taskRecipient), 'TASK:16:4');
  assert.equal(mobileManualCardPaymentSelectionRecipient(fixture), taskRecipient);
  assert.notEqual(mobileManualCardRecipientKey(taskRecipient), mobileManualCardRecipientKey(specialist));
});
