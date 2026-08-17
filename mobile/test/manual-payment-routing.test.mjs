import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  mobileIsTaskRecipient,
  mobilePaymentRouteErrorCode,
  mobileRetryablePaymentRouteError,
  mobileTaskAwareRecipientEffect,
  mobileTaskAwareRecipientKey,
  mobileTaskAwareRecipientLabel
} = loadTsModule('src/app/shared/manual-payment-routing.ts');

const task = {
  key: 'TASK:16:3',
  cashDestinationKind: 'MANUAL_PAYMENT_TASK',
  manualPaymentTaskId: 16,
  manualPaymentTaskGeneration: 3,
  taskRecipientName: 'Наталья',
  taskTargetKind: 'SPECIALIST',
  accountingTargetLabel: 'Специалист · Наталья'
};

test('keeps the frozen payment-task key and separates bank/accounting recipients', () => {
  assert.equal(mobileIsTaskRecipient(task), true);
  assert.equal(mobileTaskAwareRecipientKey(task), 'TASK:16:3');
  assert.equal(
    mobileTaskAwareRecipientLabel(task),
    'Платёжное задание №16 · Наталья · учёт: Специалист · Наталья'
  );
  assert.match(mobileTaskAwareRecipientEffect(task), /лимит и резерв обновятся/i);
});

test('external task credit never implies contractor or owner accounting', () => {
  const external = { ...task, taskTargetKind: 'EXTERNAL_TASK', accountingTargetLabel: 'Только задание' };
  assert.match(mobileTaskAwareRecipientEffect(external), /только в платёжное задание/i);
  assert.match(mobileTaskAwareRecipientEffect(external), /не изменятся/i);
});

test('recognises stable route errors and only refreshes retryable conflicts', () => {
  assert.equal(
    mobilePaymentRouteErrorCode({ status: 409, error: { code: 'PAYMENT_ROUTE_STALE' } }),
    'PAYMENT_ROUTE_STALE'
  );
  assert.equal(mobileRetryablePaymentRouteError({ error: { errorCode: 'ACTUAL_RECIPIENT_REQUIRED' } }), true);
  assert.equal(mobileRetryablePaymentRouteError({ error: { code: 'TASK_TARGET_UNRESOLVED' } }), false);
});
