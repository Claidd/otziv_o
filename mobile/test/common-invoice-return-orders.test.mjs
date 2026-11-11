import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { apiTransportSource } from './api-source.mjs';
import { loadTsModule } from './load-ts-module.mjs';

/** Some sources are checked out with CRLF; the checks below read every file with plain line feeds. */
const read = file => fs.readFileSync(file, 'utf8').replace(/\r\n/gu, '\n');

const returns = loadTsModule('src/app/shared/common-invoice-return-orders.ts');
const pageSource = read('src/app/features/common-billing.page.ts');
const apiSource = apiTransportSource();
const webPageSource = read('../frontend/src/app/features/admin/common-billing/common-billing.component.ts');
const webWindowSource = read('../frontend/src/app/features/admin/common-billing/common-invoice-return-orders.component.ts');

const collecting = {
  status: 'COLLECTING', sentAt: null, paymentRouteSelectedAt: null, paperInvoiceIssuedAt: null,
  invoicePaymentMode: 'AUTO_ROUTING', invoicePurpose: null
};
const order = {
  orderId: 28523, companyId: 7, companyTitle: 'Denisenko', orderTitle: 'Заказ №28523 · Expert Lazer', status: 'Новый', amountKopecks: 25_000
};

test('the button is offered only while the invoice is collected and its composition can still change', () => {
  assert.equal(returns.canReturnOrdersToCommonInvoice(collecting), true);
  assert.equal(returns.canReturnOrdersToCommonInvoice({ ...collecting, status: 'READY' }), true);
  for (const fixed of [
    { status: 'INVOICED' },
    { status: 'PAID' },
    { sentAt: '2026-11-11T10:00:00' },
    { paymentRouteSelectedAt: '2026-11-11T10:00:00' },
    { paperInvoiceIssuedAt: '2026-11-11T10:00:00' },
    { invoicePaymentMode: 'OWNER_PAPER_INVOICE' },
    { invoicePurpose: 'BAD_REVIEW_SUCCESSOR' }
  ]) {
    assert.equal(returns.canReturnOrdersToCommonInvoice({ ...collecting, ...fixed }), false, JSON.stringify(fixed));
  }
  assert.equal(returns.canReturnOrdersToCommonInvoice(null), false);
  assert.equal(returns.canReturnOrdersToCommonInvoice(undefined), false);
});

test('the rule is the same as on the web page', () => {
  const rule = source => source.slice(
    source.indexOf('export function canReturnOrdersToCommonInvoice'),
    source.indexOf('}', source.indexOf('invoicePurpose !== \'BAD_REVIEW_SUCCESSOR\''))
  ).replace(/\s+/gu, ' ').replace(/^.*?return Boolean\(/u, '');
  const mobileRule = rule(read('src/app/shared/common-invoice-return-orders.ts'));
  assert.ok(mobileRule.includes("invoice.status === 'COLLECTING' || invoice.status === 'READY'"));
  assert.equal(mobileRule, rule(webPageSource));
});

test('the confirmation says what will happen before anything is sent', () => {
  const text = returns.returnOrderConsequence(order, 390);
  assert.match(text, /^Заказ №28523 вернётся в общий счёт №390: сумма счёта вырастет на 250 ₽\. /u);
  assert.match(text, /Отдельный счёт заказа закроется, если по нему ещё не платили/u);
  assert.match(text, /Клиенту сейчас ничего не отправляется/u);
  assert.match(returns.returnOrderConsequence({ ...order, amountKopecks: 0 }, 390), /сумма счёта будет пересчитана/u);
  // The same sentences as the web window shows.
  for (const sentence of [
    'Отдельный счёт заказа закроется, если по нему ещё не платили; если оплата уже начата, заказ не вернётся и ничего не изменится. ',
    'Клиенту сейчас ничего не отправляется — заказ уйдёт ему в составе общего счёта.'
  ]) {
    assert.ok(webWindowSource.includes(sentence), sentence);
  }
});

test('a list row names the order, its status and what it adds', () => {
  // The server title already carries the order number: it is shown once.
  assert.equal(returns.returnOrderLabel(order), 'Заказ №28523 · Expert Lazer · Новый · 250 ₽');
  assert.equal(returns.returnOrderLabel(order).match(/28523/gu).length, 1);
  assert.equal(returns.returnOrderLabel({ ...order, orderTitle: ' ', status: '', amountKopecks: 0 }),
    'Заказ №28523 · статус не указан · сумма пересчитается');
  assert.equal(returns.returnOrderMoney(123_456), '1 234,56 ₽'.replace(' ', ' '));
});

test('a refusal always says that nothing changed and what to do next', () => {
  const server = 'Заказ не возвращён, счёт не изменён. У связи собирается другой общий счёт №391. Что делать: откройте счёт №391 и верните заказ в него.';
  assert.equal(returns.returnOrderRefusal(server), server);
  assert.equal(returns.returnOrderRefusal('Сервер недоступен'),
    'Заказ не возвращён, счёт не изменён. Сервер недоступен Что делать: обновите страницу и повторите возврат; если отказ повторится, ведите заказ отдельно.');
  assert.match(returns.returnOrderRefusal(''), /^Заказ не возвращён, счёт не изменён\. Что делать: /u);
  assert.equal(returns.RETURN_ORDER_REFUSAL_LEAD, 'Заказ не возвращён, счёт не изменён.');
  assert.ok(webWindowSource.includes(`'${returns.RETURN_ORDER_REFUSAL_LEAD}'`));
});

test('the page asks before returning, uses the dedicated endpoints and keeps a refusal on screen', () => {
  assert.match(apiSource, /\/api\/common-billing\/invoices\/\$\{invoiceId\}\/returnable-orders/u);
  assert.match(apiSource, /\/api\/common-billing\/invoices\/\$\{invoiceId\}\/orders\/\$\{orderId\}\/attach/u);
  assert.match(pageSource, /@if \(canReturnOrders\(\)\) \{/u);
  assert.match(pageSource, />\s*\{\{ returnOrdersOpen\(\) \? 'Скрыть заказы для возврата' : 'Вернуть заказ в счёт' \}\}/u);
  const action = pageSource.slice(pageSource.indexOf('async returnOrder('), pageSource.indexOf('private closeReturnOrders'));
  assert.match(action, /this\.confirm\.confirm\(\{ message: returnOrderConsequence\(order, invoiceId\) \}\)/u);
  assert.ok(action.indexOf('this.confirm.confirm') < action.indexOf('returnCommonInvoiceOrder'), 'confirmation comes first');
  assert.match(action, /if \(!confirmed \|\| !this\.routeGuard\.accepts\(ticket\)\) \{\s*return;/u);
  assert.match(action, /this\.returnOrderRefusal\.set\(returnOrderRefusal\(/u);
  assert.match(pageSource, /answer\.blockReason \|\| returnOrdersFixed/u);
  assert.match(pageSource, /\{\{ returnOrdersEmpty \}\}/u);
});
