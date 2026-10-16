import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  canCloseManualPaymentAsUnpaid,
  canConfirmContractorPaymentSource,
  canMarkLegacyReceiptNotRequired,
  confirmedTotalKopecks
} = loadTsModule('src/app/shared/manual-payment-audit.ts');

const base = {
  id: 8327,
  amount: 1400,
  amountKopecks: 140000,
  status: 'MANUAL_REPORTED',
  paymentMethod: 'MANUAL_MOBILE_BANK',
  manualSource: 'CONTRACTOR_PAYMENT_PROFILE',
  receiptStatus: 'PENDING'
};

test('reported transfer can be closed after statement check, but recorded money cannot', () => {
  assert.equal(canCloseManualPaymentAsUnpaid(base), true);
  assert.equal(canCloseManualPaymentAsUnpaid({ ...base, confirmedAmountKopecks: 1 }), false);
  assert.equal(canCloseManualPaymentAsUnpaid({ ...base, manualConfirmedBy: 'owner' }), false);
  assert.equal(canCloseManualPaymentAsUnpaid({ ...base, receiptStatus: 'MARKED' }), false);
  assert.equal(canCloseManualPaymentAsUnpaid({ ...base, tbankPaymentId: 'bank-id' }), false);
  assert.equal(canCloseManualPaymentAsUnpaid({ ...base, archived: true }), false);
});

test('exact contractor confirmation is available for unresolved profile sources only', () => {
  assert.equal(canConfirmContractorPaymentSource(base), true);
  assert.equal(canConfirmContractorPaymentSource({ ...base, status: 'AMOUNT_MISMATCH' }), true);
  assert.equal(canConfirmContractorPaymentSource({ ...base, manualSource: 'MANUAL_TASK' }), false);
  assert.equal(canConfirmContractorPaymentSource({ ...base, status: 'CONFIRMED' }), false);
});

test('statement total accepts kopecks and rejects excess or imprecise amounts', () => {
  assert.equal(confirmedTotalKopecks('1 400,00', 140000), 140000);
  assert.equal(confirmedTotalKopecks('50.5', 140000), 5050);
  assert.equal(confirmedTotalKopecks('1 400,01', 140000), null);
  assert.equal(confirmedTotalKopecks('10.001', 140000), null);
  assert.equal(confirmedTotalKopecks('0', 140000), null);
  assert.equal(confirmedTotalKopecks('', 140000), null);
});

test('legacy no-receipt action requires a confirmed manual payment at least 30 days old', () => {
  const now = Date.parse('2026-10-16T00:00:00Z');
  const paidAt = '2026-09-16T00:00:00Z';
  assert.equal(canMarkLegacyReceiptNotRequired({ ...base, status: 'CONFIRMED', paidAt }, now), true);
  assert.equal(canMarkLegacyReceiptNotRequired({ ...base, status: 'CONFIRMED', paidAt: '2026-09-17T00:00:00Z' }, now), false);
  assert.equal(canMarkLegacyReceiptNotRequired({ ...base, status: 'CONFIRMED', paidAt, receiptStatus: 'MARKED' }, now), false);
});

test('mobile bank posts the audited evidence to the same dedicated endpoints as web', () => {
  const mobileApi = readFileSync(new URL('../src/app/core/payment-administration.api.ts', import.meta.url), 'utf8');
  const mobilePage = readFileSync(new URL('../src/app/features/tbank.page.ts', import.meta.url), 'utf8');
  assert.match(mobileApi, /\/manual-links\/\$\{linkId\}\/contractor-confirmation/);
  assert.match(mobileApi, /\/manual-links\/\$\{linkId\}\/close-unpaid/);
  assert.match(mobileApi, /\/manual-links\/\$\{linkId\}\/receipt\/legacy-not-required/);
  assert.match(mobilePage, /recipientStatementChecked: true,[\s\S]*?paymentReceived: true/);
  assert.match(mobilePage, /recipientStatementChecked: true,[\s\S]*?paymentAbsent: true/);
});
