import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const orderSource = source('src/app/features/order-details.page.ts');
const paySource = source('src/app/features/public-pay.page.ts');
const groupSource = source('src/app/features/public-pay-group.page.ts');

test('mobile order details cancels route GETs and clears invalid or changed route state', () => {
  assert.match(orderSource, /route\.paramMap\.subscribe[\s\S]*?activateOrderRoute\(/);
  assert.match(orderSource, /activateOrderRoute\([\s\S]*?orderRouteGuard\.change\(routeKey\)[\s\S]*?cancelOrderRouteReads\(\)[\s\S]*?clearOrderRouteState\(\)/);
  assert.match(orderSource, /invalid:[\s\S]*?activateOrderRoute/);
  assert.match(orderSource, /clearOrderRouteState\(\)[\s\S]*?details\.set\(null\)[\s\S]*?mutationKey\.set\(null\)[\s\S]*?reviewFieldDrafts\.set\(\{\}\)[\s\S]*?reviewNoteDrafts\.set\(\{\}\)/);
  assert.match(orderSource, /cancelOrderRouteReads\(\)[\s\S]*?detailsSubscription\?\.unsubscribe\(\)[\s\S]*?companyReportSubscription\?\.unsubscribe\(\)/);
});

test('mobile order mutations finish independently but fence every late UI delivery by route epoch', () => {
  for (const method of ['runDetailsMutation', 'runReviewMutation', 'runRecoveryBotMutation']) {
    const start = orderSource.indexOf(`private ${method}(`);
    assert.ok(start >= 0, `${method} must exist`);
    const block = orderSource.slice(start, start + 1800);
    assert.match(block, /captureOrderRoute\(\)/);
    assert.match(block, /isActiveOrderRoute\(routeTicket\)/);
    assert.doesNotMatch(block, /takeUntil|unsubscribe\(/);
  }
  assert.match(orderSource, /saveAllReviewNotes[\s\S]*?const reviewComment[\s\S]*?const orderComments[\s\S]*?const companyComments[\s\S]*?isActiveOrderRoute\(routeTicket\)/);
  assert.match(orderSource, /deleteReviewEdit[\s\S]*?captureOrderRoute\(\)[\s\S]*?isActiveOrderRoute\(routeTicket\)/);
});

test('mobile T-Bank status and payment action are restricted to ADMIN or OWNER without role churn', () => {
  assert.match(orderSource, /ngOnInit\(\)[\s\S]*?hasAnyRealmRole\(\['ADMIN', 'OWNER'\]\)[\s\S]*?loadTbankStatus\(\)/);
  const actionStart = orderSource.indexOf('canShowPaymentLinkAction(): boolean');
  const actionBlock = orderSource.slice(actionStart, actionStart + 520);
  assert.match(actionBlock, /hasAnyRealmRole\(\['ADMIN', 'OWNER'\]\)/);
  assert.doesNotMatch(actionBlock, /hasRealmRole\('ADMIN'\)/);
});

test('mobile public single and group pay pages react to token changes and fence writes', () => {
  for (const page of [paySource, groupSource]) {
    assert.match(page, /route\.paramMap\.subscribe/);
    assert.match(page, /routeEpoch\.change\(routeKey\)/);
    assert.match(page, /cancelRouteRead/);
    assert.match(page, /clearRouteState\(\)/);
    assert.match(page, /captureRoute\(\)/);
    assert.match(page, /isActiveRoute\(routeTicket\)/);
  }
  assert.match(paySource, /paymentLoadSubscription[\s\S]*?unsubscribe\(\)/);
  assert.match(groupSource, /invoiceLoadSubscription[\s\S]*?unsubscribe\(\)/);
  assert.match(paySource, /initPublicPayment[\s\S]*?isActiveRoute\(routeTicket\)[\s\S]*?openPaymentTarget/);
  assert.match(groupSource, /initPublicCommonInvoicePayment[\s\S]*?isActiveRoute\(routeTicket\)[\s\S]*?externalLink\.openPayment/);
});

test('mobile public pay adopts canonical payment token and preserves a typed email on refresh', () => {
  const applyStart = paySource.indexOf('private applyPayment(');
  const banksStart = paySource.indexOf('private loadSbpBanks(', applyStart);
  const applyBlock = paySource.slice(applyStart, banksStart);

  assert.ok(applyStart >= 0 && banksStart > applyStart);
  assert.match(applyBlock, /preserveEmail = false/);
  assert.match(applyBlock, /const typedEmail = this\.email\(\)\.trim\(\)/);
  assert.match(applyBlock, /this\.applyCanonicalToken\(payment\.token\)/);
  assert.match(applyBlock, /if \(!preserveEmail \|\| !typedEmail\)[\s\S]*?this\.email\.set\(payment\.payerEmail \?\? ''\)/);
  assert.match(applyBlock, /applyCanonicalToken[\s\S]*?this\.token\.set\(cleanToken\)/);

  const refreshStart = paySource.indexOf('private refreshPaymentAfterReturn()');
  const activateStart = paySource.indexOf('private activatePaymentRoute(', refreshStart);
  const refreshBlock = paySource.slice(refreshStart, activateStart);
  assert.ok(refreshStart >= 0 && activateStart > refreshStart);
  assert.match(refreshBlock, /this\.applyPayment\(payment, true\)/);
});

function source(relativePath) {
  return readFileSync(new URL(`../${relativePath}`, import.meta.url), 'utf8');
}
