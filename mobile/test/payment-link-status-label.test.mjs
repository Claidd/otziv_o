import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const read = file => fs.readFileSync(file, 'utf8').replace(/\r\n/gu, '\n');
const { paymentLinkStatusLabel } = loadTsModule('src/app/shared/payment-link-status-label.ts');

test('a payment status is named in Russian, never printed as a code', () => {
  assert.equal(paymentLinkStatusLabel('WAITING_MANUAL_PAYMENT'), 'Ждет перевод');
  assert.equal(paymentLinkStatusLabel('NEEDS_RECONCILIATION'), 'Требует сверки с банком');
  // The two values the order sheet gets while there is no payment link.
  assert.equal(paymentLinkStatusLabel('NOT_CREATED'), 'Счёт ещё не выставлен');
  assert.equal(paymentLinkStatusLabel('WAITING_BANK'), 'Ждём ответ банка');
});

test('a status the app does not know is shown as sent, an empty one stays empty', () => {
  assert.equal(paymentLinkStatusLabel('SOMETHING_NEW'), 'SOMETHING_NEW');
  assert.equal(paymentLinkStatusLabel(' CONFIRMED '), 'Оплачен');
  assert.equal(paymentLinkStatusLabel(null), '');
  assert.equal(paymentLinkStatusLabel(undefined), '');
});

test('the order sheet and the payments list use the same names', () => {
  const sheet = read('src/app/features/order-details.page.ts');
  assert.match(sheet, /<em>\{\{ paymentLinkStatusLabel\(context\.status\) \}\}<\/em>/u);
  assert.doesNotMatch(sheet, /<em>\{\{ context\.status \}\}<\/em>/u);
  const list = read('src/app/features/tbank.page.ts');
  assert.match(list, /statusLabel\(status: string\): string \{\s*return paymentLinkStatusLabel\(status\);/u);
});
