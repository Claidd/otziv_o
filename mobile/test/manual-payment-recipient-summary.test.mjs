import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  manualPaymentAccountingDestinationLabel,
  manualPaymentAccountingRecipientLabel,
  manualPaymentAccountingSourceLabel
} = loadTsModule('src/app/shared/manual-payment-recipient-summary.ts');

test('shows actual accounting profile without treating bank requisites as the recipient', () => {
  const item = {
    manualRecipientName: 'Старое имя в банке',
    accountingRecipientLabel: 'Специалист Наталья',
    accountingDestinationKind: 'CONTRACTOR_PROFILE',
    accountingRecipientType: 'SPECIALIST',
    accountingRecipientProfileId: 27,
    attributionKnown: true
  };

  assert.equal(manualPaymentAccountingRecipientLabel(item), 'Специалист Наталья');
  assert.equal(manualPaymentAccountingDestinationLabel(item), 'Профиль специалиста · профиль №27');
  assert.equal(manualPaymentAccountingSourceLabel(item), 'По фактическому получателю оплаты');
});

test('shows legacy payments as unattributed', () => {
  const item = {
    manualRecipientName: 'Получатель не указан (старые оплаты без атрибуции)',
    attributionKnown: false
  };

  assert.match(manualPaymentAccountingDestinationLabel(item), /Нет точной атрибуции/);
  assert.match(manualPaymentAccountingSourceLabel(item), /без данных о получателе/);
});

test('mobile recipient view consumes accounting fields and does not render a requisites column', () => {
  const source = fs.readFileSync(new URL('../src/app/features/tbank.page.ts', import.meta.url), 'utf8');

  assert.match(source, /recipientSummaryDestinationLabel/);
  assert.match(source, /accountingRecipientKey/);
  assert.doesNotMatch(source, /recipientSummaryPaymentTarget\(item\)/);
});
