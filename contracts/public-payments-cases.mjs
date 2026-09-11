import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { loadTsModule } from '../mobile/test/load-ts-module.mjs';
const values = JSON.parse(fs.readFileSync(new URL('./fixtures/client-api-current.json', import.meta.url), 'utf8')).responses;
for (const location of ['../shared/client-common/src/public-payments.ts', '../frontend/node_modules/@otziv/client-common/src/public-payments.ts', '../mobile/node_modules/@otziv/client-common/src/public-payments.ts']) {
  const adapter = loadTsModule(location);
  test(`${location}: generated fixture values retain authoritative money, IDs and optional nulls`, () => {
    const source = { ...values.PublicCommonInvoiceResponseOutput, status: 'READY', paymentRouteType: 'BANK_LINK', payable: true, clientReportable: true, manualPhone: null };
    const decoded = adapter.decodePublicCommonInvoice(source);
    assert.equal(JSON.stringify(decoded), JSON.stringify(source));
    assert.equal(decoded.orders[0].amountKopecks, source.orders[0].amountKopecks);
    const banks = adapter.decodePublicSbpBanks([{ ...values.PublicSbpBankResponseOutput, nspkBankId: null, logoUrl: null }]);
    assert.equal(banks[0].nspkBankId, null); assert.equal(banks[0].logoUrl, null);
    assert.equal(JSON.stringify(adapter.decodePublicPaymentInit(values.PublicPaymentInitResponseOutput)), JSON.stringify(values.PublicPaymentInitResponseOutput));
  });
  test(`${location}: QR-only init retains nullable URL/ID/status and legacy omitted optional fields`, () => {
    const qr = { paymentUrl: null, paymentId: null, status: null, method: 'FUTURE_METHOD', qrPayload: 'bank://fixture', qrImage: null };
    assert.equal(JSON.stringify(adapter.decodePublicPaymentInit(qr)), JSON.stringify(qr));
    const legacy = { paymentUrl: '', paymentId: '', status: 'FUTURE_STATUS' };
    assert.equal(JSON.stringify(adapter.decodePublicPaymentInit(legacy)), JSON.stringify(legacy));
  });
  test(`${location}: unknown invoice state/route disables actions without rewriting money or server state`, () => {
    for (const patch of [{ status: 'FUTURE_STATE', paymentRouteType: 'BANK_LINK' }, { status: 'READY', paymentRouteType: 'FUTURE_ROUTE' }]) {
      const source = { ...values.PublicCommonInvoiceResponseOutput, ...patch, payable: true, clientReportable: true };
      const result = adapter.decodePublicCommonInvoice(source);
      assert.equal(result.payable, false); assert.equal(result.clientReportable, false);
      assert.equal(result.status, source.status); assert.equal(result.paymentRouteType, source.paymentRouteType);
      assert.equal(result.remainingKopecks, source.remainingKopecks);
      assert.equal(source.payable, true, 'decoder must not mutate the wire object');
    }
  });
  test(`${location}: malformed amounts, action booleans and actionable identifiers fail before presentation`, () => {
    const invoice = values.PublicCommonInvoiceResponseOutput;
    for (const patch of [{ amountKopecks: 1.2 }, { remainingKopecks: Number.MAX_SAFE_INTEGER + 1 }, { payable: 'false' }, { orders: null }]) {
      assert.throws(() => adapter.decodePublicCommonInvoice({ ...invoice, ...patch }), /Invalid public invoice/);
    }
    assert.throws(() => adapter.decodePublicCommonInvoice({ ...invoice, orders: [{ ...invoice.orders[0], orderId: null }] }), /Invalid public invoice order/);
    assert.throws(() => adapter.decodePublicSbpBanks([{ ...values.PublicSbpBankResponseOutput, bankId: null }]), /Invalid public SBP bank/);
    assert.throws(() => adapter.decodePublicPaymentInit({ ...values.PublicPaymentInitResponseOutput, paymentUrl: 7 }), /Invalid public payment initiation/);
  });
}

