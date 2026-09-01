import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

const apiSource = source('src/app/core/api.service.ts');
const publicPaySource = source('src/app/features/public-pay.page.ts');
const publicGroupSource = source('src/app/features/public-pay-group.page.ts');
const orderDetailsSource = source('src/app/features/order-details.page.ts');
const commonBillingSource = source('src/app/features/common-billing.page.ts');
const bankPageSource = source('src/app/features/tbank.page.ts');
const bankRouteSource = source('src/app/shared/bank-payment-source.ts');

test('public bank routes and instruction sources accept canonical and provider aliases', () => {
  assert.match(bankRouteSource, /BANK_LINK[\s\S]*?TBANK_LINK[\s\S]*?TOCHKA_LINK/);
  assert.match(publicGroupSource, /bankRoute = computed[\s\S]*?isBankPaymentRoute/);
  assert.match(apiSource, /PaymentInstructionSource = [^;]*BANK_LINK[^;]*TBANK_LINK[^;]*TOCHKA_LINK/);
  assert.match(bankPageSource, /usesBankPaymentInstructionSource\(settings\.paymentInstructionSource\)/);
  assert.match(bankPageSource, /setPaymentInstructionSource\('BANK_LINK'\)/);
  assert.match(bankPageSource, /app-mobile-header title="Банк"/);
});

test('public payment UI consumes provider capabilities and locks a started Tochka method', () => {
  const interfaceBlock = block(apiSource, 'export interface PublicPaymentLink {', '\n}');
  assert.match(interfaceBlock, /provider\?/);
  assert.match(interfaceBlock, /sbpBankSelectionSupported\?/);
  assert.match(publicPaySource, /showSbpPayment\(\) && sbpBankSelectionSupported\(\)/);
  assert.match(publicPaySource, /if \(!this\.sbpBankSelectionSupported\(\)\)[\s\S]*?sbpBanks\.set\(\[\]\)/);
  assert.match(publicPaySource, /provider[^\n]*TOCHKA[\s\S]*?INITIATED[\s\S]*?AUTHORIZED/);
  assert.match(publicPaySource, /lockedTochkaPaymentMethod\(\) !== 'BANK_FORM'/);
  assert.match(publicPaySource, /lockedTochkaPaymentMethod\(\) !== 'SBP_QR'/);
  assert.match(publicPaySource, /submitSbp\(\)[\s\S]*?if \(!this\.showSbpPayment\(\)\)/);
  assert.match(publicPaySource, /submitBankForm\(\)[\s\S]*?if \(!this\.showBankPayment\(\)\)/);
});

test('ordinary order payment controls are provider-neutral and expose safe route changes', () => {
  const visibilityBlock = block(orderDetailsSource, 'canShowPaymentLinkAction(): boolean {', '\n  }');
  assert.match(visibilityBlock, /managerUiEnabled/);
  assert.match(visibilityBlock, /paymentLinksEnabled/);
  assert.doesNotMatch(visibilityBlock, /status\.enabled|applyConfirmedPayments/);
  assert.match(orderDetailsSource, /getManagerOrderPaymentRouteChangeContext/);
  assert.match(orderDetailsSource, /changeManagerOrderPaymentRoute/);
  assert.match(orderDetailsSource, /confirmedUnpaid: true/);
  assert.match(orderDetailsSource, /expectedTargetPaymentProfileId/);
  assert.match(orderDetailsSource, /Переиздать банковскую ссылку/);
  assert.match(orderDetailsSource, /Банковская ссылка владельца/);
  assert.match(apiSource, /\/payment-route-change-context/);
  assert.match(apiSource, /\/payment-route-change`/);
  assert.match(apiSource, /\/paper-invoice\/issued/);
});

test('common invoices can safely reissue a bank link after the manager bank changes', () => {
  assert.match(apiSource, /OWNER_BANK_REISSUE/);
  assert.match(apiSource, /ownerBankTargetPaymentProfileId/);
  assert.match(commonBillingSource, /reissueOwnerBankRoute/);
  assert.match(commonBillingSource, /context\.ownerBankTargetPaymentProfileId/);
  assert.match(commonBillingSource, /Обновить банк и ссылку/);
});

function block(text, startMarker, endMarker) {
  const start = text.indexOf(startMarker);
  assert.notEqual(start, -1, `missing start marker: ${startMarker}`);
  const end = text.indexOf(endMarker, start + startMarker.length);
  assert.notEqual(end, -1, `missing end marker: ${endMarker}`);
  return text.slice(start, end);
}

function source(relativePath) {
  return readFileSync(new URL(`../${relativePath}`, import.meta.url), 'utf8');
}
