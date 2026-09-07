import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { loadTsModule } from '../mobile/test/load-ts-module.mjs';

const fixture = name => JSON.parse(fs.readFileSync(new URL(`fixtures/${name}.json`, import.meta.url), 'utf8'));
const shared = loadTsModule('../shared/client-common/src/order-editor.ts');
const current = fixture('order-editor-current');

test('routing behavior table preserves common rules and the deliberate legacy empty-route adapter difference', () => {
  const web = loadTsModule('../frontend/src/app/shared/manual-payment-routing.ts');
  const mobile = loadTsModule('src/app/shared/manual-payment-routing.ts');
  const webBank = loadTsModule('../frontend/src/app/shared/bank-payment-presentation.ts');
  const mobileBank = loadTsModule('src/app/shared/bank-payment-source.ts');
  const cases = fixture('payment-routing-parity');
  for (const row of cases.errors) {
    assert.equal(web.manualPaymentRouteErrorCode(row.input), row.code);
    assert.equal(mobile.mobilePaymentRouteErrorCode(row.input), row.code);
    assert.equal(web.isRetryablePaymentRouteError(row.input), row.retryable);
    assert.equal(mobile.mobileRetryablePaymentRouteError(row.input), row.retryable);
    assert.equal(web.manualPaymentRouteErrorMessage(row.input, 'fallback'), mobile.mobilePaymentRouteErrorMessage(row.input, 'fallback'));
  }
  for (const row of cases.recipients) {
    assert.equal(web.manualPaymentRecipientKey(row.input), row.key);
    assert.equal(mobile.mobileTaskAwareRecipientKey(row.input), row.key);
    assert.equal(web.isManualPaymentTaskRecipient(row.input), row.task);
    assert.equal(mobile.mobileIsTaskRecipient(row.input), row.task);
    assert.equal(web.manualPaymentRecipientLabel(row.input), mobile.mobileTaskAwareRecipientLabel(row.input));
    assert.equal(web.manualPaymentRecipientEffect(row.input), mobile.mobileTaskAwareRecipientEffect(row.input));
  }
  for (const row of cases.bankRoutes) {
    assert.equal(webBank.isBankPaymentRouteType(row.input), row.web);
    assert.equal(mobileBank.isBankPaymentRoute(row.input), row.mobile);
  }
});

test('route refresh retains the explanation and receipt, clears consent and rejects a missing safe default on both clients', () => {
  const web = loadTsModule('../frontend/src/app/shared/common-manual-payment-route-refresh.ts');
  const mobile = loadTsModule('src/app/shared/common-manual-payment-route-refresh.ts');
  for (const amount of [0, 125050]) {
    const options = { remainingKopecks: amount, defaultRecipientKey: 'TASK:16:3', candidates: [{ key: 'TASK:16:3' }] };
    const first = web.commonManualPaymentDraftAfterRouteRefresh(options, 'reason', 'receipt', () => 'row');
    const second = mobile.mobileCommonManualPaymentDraftAfterRouteRefresh(options, 'reason', 'receipt', () => 'row');
    assert.equal(JSON.stringify(first), JSON.stringify(second));
    assert.equal(first.paymentReceived, false); assert.equal(first.finalAcknowledged, false);
    assert.equal(first.reason, 'reason'); assert.equal(first.receiptUrl, 'receipt');
  }
  const missing = { remainingKopecks: 1, defaultRecipientKey: 'missing', candidates: [] };
  assert.throws(() => web.commonManualPaymentDraftAfterRouteRefresh(missing, '', '', () => 'row'));
  assert.throws(() => mobile.mobileCommonManualPaymentDraftAfterRouteRefresh(missing, '', '', () => 'row'));
});

for (const app of ['frontend', 'mobile']) {
  const api = loadTsModule(`../${app}/node_modules/@otziv/client-common/src/client-api.ts`);
  test(`${app}: all current compiled DTO fixtures validate through the installed runtime SDK`, () => {
    const currentApi = fixture('client-api-current');
    for (const [name, value] of Object.entries(currentApi.responses)) api.validateClientJson(value, { $ref: `#/components/schemas/${name}` }, 'response');
    for (const [name, value] of Object.entries(currentApi.requests)) api.validateClientJson(value, { $ref: `#/components/schemas/${name}` }, 'request');
    assert.equal(api.CLIENT_API_CONTRACT_VERSION, '1.1.0');
    assert.ok(Object.keys(api.clientApiOperations).length >= 180);
    assert.ok(Object.keys(api.clientApiSchemas).length >= 200);
  });
  test(`${app}: generated SDK preserves path encoding, write body, 204 and explicit error semantics`, () => {
    const request = api.prepareClientOperation('POST /api/payments/public/{token}/init', {
      path: { token: 'a/b?x#y' }, query: {}, body: { email: 'contract@example.invalid', offerConsent: true, privacyConsent: true, receiptConsent: true }
    });
    assert.equal(request.path, '/api/payments/public/a%2Fb%3Fx%23y/init');
    assert.equal(request.method, 'POST');
    assert.equal(request.body.receiptConsent, true);
    assert.equal('send' in request, false);
    assert.throws(() => api.prepareClientOperation('POST /api/manager/orders/{orderId}/status', { path: { orderId: 'wrong' }, query: {}, body: { status: 'В работе' } }), /Invalid API request/);
    const update = api.clientApiOperations['POST /api/manager/orders/{orderId}/status'];
    assert.equal(update.successStatus, 204);
    const result = api.decodeClientError(update, 409, { message: 'Changed', code: 'PAYMENT_ROUTE_STALE' });
    assert.equal(result.schemaVerified, true);
    assert.equal(result.body.code, 'PAYMENT_ROUTE_STALE');
    assert.throws(() => api.decodeClientError(update, 409, { message: 7 }), /Invalid API response/);
    assert.equal(api.decodeClientError(update, 401, '<login>').schemaVerified, false);
    assert.equal(api.decodeClientError(update, 0, null).schemaVerified, false);
  });
  test(`${app}: new manager pages, money and permissions reject malformed wire values`, () => {
    const values = fixture('client-api-current').responses;
    const payment = values.PublicPaymentLinkResponseOutput;
    for (const patch of [{ amountKopecks: '200' }, { payable: 'true' }]) assert.throws(() => api.validateClientJson({ ...payment, ...patch }, { $ref: '#/components/schemas/PublicPaymentLinkResponseOutput' }, 'response'), /Invalid API response/);
    const pageName = Object.keys(values).find(name => name.startsWith('PageResponseOf'));
    assert.ok(pageName);
    assert.throws(() => api.validateClientJson({ ...values[pageName], totalElements: '20' }, { $ref: `#/components/schemas/${pageName}` }, 'response'), /Invalid API response/);
    assert.throws(() => api.validateClientJson({ ...values.OrderEditResponseOutput, canComplete: 'false' }, { $ref: '#/components/schemas/OrderEditResponseOutput' }, 'response'), /Invalid API response/);
    const match = api.findClientOperation('GET', 'https://backend.invalid/otziv/api/payments/public/group/abc');
    assert.equal(match.operation.path, '/api/payments/public/group/{token}');
    assert.equal(match.operation.publicCapability, true);
    const manager = api.clientApiOperations['GET /api/manager/board'];
    assert.match(manager.permission, /MANAGER/);
    assert.equal(manager.publicCapability, false);
  });
}

test('current and legacy wire responses decode through both installed client packages', () => {
  for (const app of ['frontend', 'mobile']) {
    const consumer = loadTsModule(`../${app}/node_modules/@otziv/client-common/src/order-editor.ts`);
    assert.deepEqual(JSON.parse(JSON.stringify(consumer.decodeOrderEditPayload(current))), current);
    const old = consumer.decodeOrderEditPayload(fixture('order-editor-legacy'));
    assert.equal(old.canCancelPayment, false);
    assert.equal(old.canComplete, false);
    assert.equal(old.status, 'НЕИЗВЕСТНЫЙ_СТАТУС');
    assert.equal(consumer.managerOrderEditPath(202), '/api/manager/orders/202/edit');
    assert.equal(consumer.ORDER_EDITOR_CONTRACT_VERSION, '1.1.0');
  }
});

test('hidden worker money and nullable references preserve the existing UI contract', () => {
  const result = shared.decodeOrderEditPayload({ ...current, companyId: null, sum: null, amount: null, counter: null });
  assert.equal(result.companyId, null);
  assert.equal(result.sum, undefined);
  assert.equal(result.counter, undefined);
  assert.equal(result.amount, undefined);
  assert.equal(current.sum, 1250.5);
});

test('invalid IDs, permissions, money and nested option fields are rejected', () => {
  for (const patch of [{ id: '202' }, { id: Number.MAX_SAFE_INTEGER + 1 }, { canDelete: 'true' }, { sum: '1250.50' }, { manager: { id: 17, label: null } }, { managers: null }]) {
    assert.throws(() => shared.decodeOrderEditPayload({ ...current, ...patch }), /Invalid order editor response/);
  }
  assert.throws(() => shared.decodeOrderEditPayload({ id: 202 }), /missing/);
  for (const id of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => shared.managerOrderEditPath(id), /Invalid order ID/);
});

for (const app of ['frontend', 'mobile']) {
  const recipient = loadTsModule(`../${app}/src/app/shared/manual-payment-recipient-summary.ts`);
  const visibility = loadTsModule(`../${app}/src/app/shared/manual-payment-task-visibility.ts`);
  test(`${app}: unchanged recipient labels match the shared parity fixtures`, () => {
    for (const row of fixture('manual-payment-parity')) {
      assert.equal(recipient.manualPaymentAccountingRecipientLabel(row.input), row.recipient);
      assert.equal(recipient.manualPaymentAccountingDestinationLabel(row.input), row.destination);
      assert.equal(recipient.manualPaymentAccountingSourceLabel(row.input), row.source);
    }
  });
  test(`${app}: canceled tasks are hidden, unknown statuses stay visible, input is not mutated`, () => {
    const tasks = [{ id: 1, status: 'CANCELED' }, { id: 2, status: 'FUTURE' }, { id: 3 }, { id: 4, status: null }];
    const result = visibility.manualPaymentTaskWorklist(tasks);
    assert.deepEqual(Array.from(result, item => item.id), [2, 3, 4]);
    assert.equal(tasks.length, 4);
    assert.equal(visibility.manualPaymentTaskWorklist(null).length, 0);
  });
}

test('the shared package stays independent of Angular, native, DOM and transport', () => {
  const directory = new URL('../shared/client-common/src/', import.meta.url);
  for (const name of fs.readdirSync(directory)) {
    const source = fs.readFileSync(new URL(name, directory), 'utf8');
    assert.doesNotMatch(source, /from\s+['"](?:@angular|@capacitor|@ionic|rxjs)|\b(?:window|document|fetch|localStorage|sessionStorage)\s*[.(]/);
  }
});

for (const app of ['frontend', 'mobile']) {
  const billing = loadTsModule(`../${app}/node_modules/@otziv/client-common/src/billing-payments.ts`);
  test(`${app}: real billing/payment wire fixtures preserve every serialized field`, () => {
    for (const [name, decode] of [
      ['billing-account-current', billing.decodeCommonBillingAccount],
      ['billing-account-legacy', billing.decodeCommonBillingAccount],
      ['public-payment-current', billing.decodePublicPaymentLink],
      ['public-payment-legacy', billing.decodePublicPaymentLink]
    ]) {
      const value = fixture(name);
      assert.deepEqual(JSON.parse(JSON.stringify(decode(value))), value);
    }
    assert.equal(billing.BILLING_PAYMENT_CONTRACT_VERSION, '1.1.0');
  });
  test(`${app}: future payment state or method is preserved but cannot authorize payment`, () => {
    for (const patch of [{ status: 'FUTURE_STATUS' }, { paymentMethod: 'FUTURE_METHOD' }, { paymentPageMode: 'FUTURE_MODE' }]) {
      const input = { ...fixture('public-payment-current'), ...patch };
      const result = billing.decodePublicPaymentLink(input);
      for (const key of Object.keys(patch)) assert.equal(result[key], patch[key]);
      for (const flag of ['payable', 'sbpBankSelectionSupported', 'tpayEnabled', 'sberpayEnabled', 'mirpayEnabled']) assert.equal(result[flag], false);
      assert.equal(input.payable, true);
    }
  });
  test(`${app}: future invoice state or route is rejected without choosing a financial fallback`, () => {
    const account = fixture('billing-account-current');
    assert.throws(() => billing.decodeCommonBillingAccount({ ...account, invoicePaymentMode: 'FUTURE' }), /Unsupported invoice/);
    assert.throws(() => billing.decodeCommonBillingAccount({ ...account, currentInvoice: { ...account.currentInvoice, status: 'FUTURE' } }), /Unsupported invoice/);
    // Existing EMPLOYEE_REQUISITES and OWNER_TBANK routes are real server values, not AUTO_ROUTING.
    assert.equal(billing.decodeCommonBillingAccount({ ...account, invoicePaymentMode: 'OWNER_TBANK' }).invoicePaymentMode, 'OWNER_TBANK');
  });
  test(`${app}: invalid payment amounts and authorization flags fail closed`, () => {
    for (const patch of [{ payable: 'true' }, { amount: '1250.50' }, { amountKopecks: Number.MAX_SAFE_INTEGER + 1 }, { status: null }, { tpayEnabled: 'false' }]) {
      assert.throws(() => billing.decodePublicPaymentLink({ ...fixture('public-payment-current'), ...patch }), /Invalid API response/);
    }
    assert.throws(() => billing.decodeCommonBillingAccounts({}), /Invalid billing/);
    assert.throws(() => billing.decodeCommonBillingAccounts([{ ...fixture('billing-account-current'), companies: null }]), /Invalid API response/);
  });
  test(`${app}: a future public group state or route cannot authorize payment or a paid report`, () => {
    const input = { status: 'INVOICED', paymentRouteType: 'BANK_LINK', payable: true, clientReportable: true };
    for (const patch of [{ status: 'FUTURE' }, { paymentRouteType: 'FUTURE_ROUTE' }]) {
      const result = billing.guardPublicCommonInvoice({ ...input, ...patch });
      assert.equal(result.payable, false); assert.equal(result.clientReportable, false);
      for (const key of Object.keys(patch)) assert.equal(result[key], patch[key]);
    }
    assert.equal(billing.guardPublicCommonInvoice(input), input);
    assert.equal(billing.guardPublicCommonInvoice({ ...input, paymentRouteType: '' }).payable, true);
  });
}
