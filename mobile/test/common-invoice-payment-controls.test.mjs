import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const apiSource = fs.readFileSync('src/app/core/api.service.ts', 'utf8');
const pageSource = fs.readFileSync('src/app/features/common-billing.page.ts', 'utf8');

test('mobile common invoice exposes the same safe payment-route switches as web', () => {
  assert.match(pageSource, />Сменить способ оплаты</u);
  assert.match(pageSource, /'Бумажный счёт'/u);
  assert.match(pageSource, /'Вернуть авто-распределение'/u);
  assert.match(pageSource, />Счёт отправлен</u);
  assert.match(pageSource, /getCommonInvoicePaymentRouteChangeContext\(invoiceId\)/u);
  assert.match(pageSource, /context\.paymentEvidenceToken/u);
  assert.match(pageSource, /this\.auth\.hasAnyRealmRole\(\['ADMIN', 'OWNER'\]\)/u);

  assert.match(apiSource, /\/payment-route-change-context`/u);
  assert.match(apiSource, /\/payment-route-change`/u);
  assert.match(apiSource, /confirmedUnpaid: true, expectedPaymentEvidenceToken/u);
  assert.match(apiSource, /\/payment-mode`/u);
  assert.match(apiSource, /\{ mode, confirmedUnpaid: true \}/u);
});

test('paper invoice confirmation uses dedicated endpoints and does not enter recipient attribution', () => {
  assert.match(apiSource, /\/paper-invoice\/issued`/u);
  assert.match(apiSource, /\/paper-invoice\/paid`/u);

  const paidBranch = pageSource.match(/if \(action === 'paid'\) \{[\s\S]*?const required = this\.manualAttributionRequired\(\);/u);
  assert.ok(paidBranch, 'paid action branch was not found');
  assert.match(paidBranch[0], /invoice\?\.invoicePaymentMode === 'OWNER_PAPER_INVOICE'/u);
  assert.match(paidBranch[0], /markCommonInvoicePaperInvoicePaid\(invoiceId, evidence\)/u);
});

test('mobile common invoice keeps operational recovery controls available', () => {
  for (const label of [
    'Починить маршрут',
    'Закрыть хвост',
    'Уведомление обработано',
    'Сверить поступление',
    'В архив'
  ]) {
    assert.match(pageSource, new RegExp(`>${label}<`, 'u'));
  }

  assert.match(apiSource, /\/attention\/repair-payment-route`/u);
  assert.match(apiSource, /\/technical-tail\/resolve`/u);
  assert.match(apiSource, /\/payment-notification\/resolve`/u);
  assert.match(apiSource, /\/contractor-confirmation`/u);
  assert.match(apiSource, /\/archive-preview`/u);
});
