import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const read = file => fs.readFileSync(file, 'utf8').replace(/\r\n/gu, '\n');
const { commonInvoiceDeliveryWait, commonInvoiceDeliveryWarning } =
  loadTsModule('node_modules/@otziv/client-common/src/billing-payments.ts');
const pageSource = read('src/app/features/common-billing.page.ts');

const sending = 'message_send_in_progress';
const routeChanged = 'payment_route_changed_message_in_progress';

test('a client message still in the queue is said as a wait in plain words', () => {
  assert.equal(commonInvoiceDeliveryWait(sending),
    'Сообщение клиенту ещё отправляется — это не ошибка. Отправлять повторно не нужно: '
    + 'если отправка не удастся, здесь появится ошибка.');
  assert.equal(commonInvoiceDeliveryWait(` ${routeChanged.toUpperCase()} `),
    'Сообщение клиенту с новым способом оплаты ещё отправляется — это не ошибка. '
    + 'Менять способ оплаты повторно не нужно: если отправка не удастся, здесь появится ошибка.');
  for (const text of [commonInvoiceDeliveryWait(sending), commonInvoiceDeliveryWait(routeChanged)]) {
    assert.doesNotMatch(text, /[a-z_]{6,}/u, 'no service code in the text');
    // The outcome is not promised: the send may still fail, and then the error shows in the same place.
    assert.doesNotMatch(text, /уйдёт само/u);
    assert.match(text, /если отправка не удастся, здесь появится ошибка/u);
  }
});

test('an unconfirmed or stopped send is said as it is while the marker is still there', () => {
  const operation = (status, errorCode = null) => ({ operationId: 'invoice-message:312:1', status, attempts: 1, errorCode });
  const wait = commonInvoiceDeliveryWait(routeChanged);
  for (const pending of ['QUEUED', 'SENDING', 'RETRYABLE', 'SENT']) {
    assert.equal(commonInvoiceDeliveryWait(routeChanged, operation(pending)), wait, pending);
  }
  assert.equal(commonInvoiceDeliveryWait(routeChanged, null), wait);
  assert.equal(commonInvoiceDeliveryWait(routeChanged, operation('UNKNOWN', 'operation_unknown')),
    'Исход отправки уточняется. Повторная рассылка заблокирована.');
  assert.equal(commonInvoiceDeliveryWait(sending, operation('FAILED')),
    'Сообщение не отправлено. Проверьте результат попыток перед повтором.');
  assert.equal(commonInvoiceDeliveryWait(null, operation('UNKNOWN')), null);
});

test('real errors stay errors, including a send that got stuck', () => {
  for (const error of ['message_send_stale: previous attempt', 'message_send_unknown', 'whatsapp_group_missing',
    'message_send_in_progress_extra', 'constructor', 'toString']) {
    assert.equal(commonInvoiceDeliveryWait(error), null, error);
    assert.equal(commonInvoiceDeliveryWarning(error), `Отправка не подтверждена. ${error}`);
  }
  assert.equal(commonInvoiceDeliveryWait(''), null);
  assert.equal(commonInvoiceDeliveryWait(null), null);
  assert.equal(commonInvoiceDeliveryWait(undefined), null);
  assert.equal(commonInvoiceDeliveryWarning(' '), null);
});

test('the invoice screen prints the wait as an ordinary line and keeps real errors red', () => {
  const timeline = pageSource.slice(pageSource.indexOf('<section class="invoice-timeline">'),
    pageSource.indexOf('paymentSuccessNotificationError }}</span>'));
  assert.match(timeline, /@if \(deliveryWait\(invoice\.lastError, details\(\)\?\.delivery\); as waiting\) \{\s*<span>\{\{ waiting \}\}<\/span>\s*\} @else if \(invoice\.lastError\) \{\s*<span class="error-line">\{\{ invoice\.lastError \}\}<\/span>/u);
  assert.match(pageSource, /readonly deliveryWait = commonInvoiceDeliveryWait;/u);
});

test('sending or reminding does not report the wait as a failed action', () => {
  const action = pageSource.slice(pageSource.indexOf('this.deliveryWatcher.watch(details, () => firstValueFrom(this.commonBillingApi.getCommonInvoice(invoiceId))'));
  assert.match(action, /!details\.delivery\s*&& !commonInvoiceDeliveryWait\(details\.summary\.lastError\)\s*\? commonInvoiceDeliveryWarning\(details\.summary\.lastError\) : null/u);
});

test('the site and the app read the same shared text', () => {
  const shared = read('../shared/client-common/src/billing-payments.ts');
  assert.equal(read('node_modules/@otziv/client-common/src/billing-payments.ts'), shared);
  const site = read('../frontend/src/app/features/admin/common-billing/common-billing.component.html');
  assert.match(site, /@if \(deliveryWait\(invoice\?\.lastError, invoiceDetails\(\)\?\.delivery\); as waiting\) \{/u);
  assert.match(site, /<span>\{\{ invoiceDeliveryWait\(\) \}\}<\/span>/u);
});
