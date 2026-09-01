import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

const pageSource = source('src/app/features/public-pay-group.page.ts');
const apiSource = source('src/app/core/api.service.ts');
const bankRouteSource = source('src/app/shared/bank-payment-source.ts');

test('mobile common invoice models every public routing field returned by the backend', () => {
  const interfaceStart = apiSource.indexOf('export interface PublicCommonInvoice {');
  const interfaceEnd = apiSource.indexOf('\n}', interfaceStart);
  const block = apiSource.slice(interfaceStart, interfaceEnd);

  for (const field of [
    'paymentRouteType',
    'manualPaymentType',
    'manualPhone',
    'manualRecipientName',
    'manualBankName',
    'manualPaymentUrl',
    'manualComment',
    'paymentInstructionText',
    'clientReportable',
    'clientReportedAt'
  ]) {
    assert.match(block, new RegExp(`\\b${field}\\b`));
  }
});

test('mobile common invoice keeps bank-link init isolated from manual routes', () => {
  assert.match(pageSource, /bankRoute = computed[\s\S]*?isBankPaymentRoute/);
  assert.match(bankRouteSource, /BANK_LINK[\s\S]*?TBANK_LINK[\s\S]*?TOCHKA_LINK/);
  assert.match(pageSource, /manualRoute = computed[\s\S]*?MANUAL_MOBILE_BANK[\s\S]*?MANUAL_EXTERNAL_LINK/);
  assert.match(pageSource, /invoice\.payable && bankRoute\(\)[\s\S]*?submitPayment\(\)/);
  assert.match(pageSource, /canSubmit = computed[\s\S]*?this\.bankRoute\(\)/);
});

test('mobile common manual route shows and copies recipient requisites with card-aware labels', () => {
  assert.match(pageSource, /manualTransferDestinationPresentation\(this\.invoice\(\)\?\.manualPhone\)/);
  assert.match(pageSource, /manualTransferDestinationLabel\(\)[\s\S]*?invoice\.manualPhone/);
  assert.match(pageSource, /manualRecipientName[\s\S]*?manualBankName[\s\S]*?manualComment/);
  assert.match(pageSource, /navigator\.clipboard\.writeText\(clean\)/);
});

test('client payment report is gated by the backend flag and uses the public common endpoint', () => {
  assert.match(pageSource, /canReportPaid = computed[\s\S]*?clientReportable[\s\S]*?manualRoute\(\)[\s\S]*?clientReportedAt/);
  assert.match(pageSource, /reportPublicCommonInvoicePaid\(token\)[\s\S]*?isActiveRoute\(routeTicket\)/);
  assert.match(apiSource, /reportPublicCommonInvoicePaid[\s\S]*?\/reported-paid/);
});

function source(relativePath) {
  return readFileSync(new URL(`../${relativePath}`, import.meta.url), 'utf8');
}
