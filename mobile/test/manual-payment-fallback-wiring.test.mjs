import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const managerSource = fs.readFileSync('src/app/features/manager.page.ts', 'utf8');
const reviewCheckSource = fs.readFileSync('src/app/features/review-check.page.ts', 'utf8');
const workerSource = fs.readFileSync('src/app/features/worker.page.ts', 'utf8');

test('review-check tries the ordinary paid action before the privileged exact-conflict fallback', () => {
  const method = reviewCheckSource.match(/async markPaid\(\): Promise<void> \{[\s\S]*?\r?\n  \}\r?\n\r?\n  previousReview/);
  assert.ok(method, 'markPaid method was not found');
  const source = method[0];
  const genericIndex = source.indexOf('this.api.markReviewCheckPaid');
  const fallbackIndex = source.indexOf('this.manualCardPaymentFlow.confirm');
  assert.ok(genericIndex >= 0 && fallbackIndex > genericIndex);
  assert.match(source, /manualCardPaymentFallbackAccessDecision/);
  assert.doesNotMatch(source, /confirmManagerManualCardPayment/);
});

test('worker tries the ordinary paid action before the privileged exact-conflict fallback', () => {
  const method = workerSource.match(/private async updatePaidOrderStatus\([\s\S]*?\r?\n  \}\r?\n\r?\n  async toggleOrderClientWaiting/);
  assert.ok(method, 'updatePaidOrderStatus method was not found');
  const source = method[0];
  const genericIndex = source.indexOf("this.api.updateWorkerOrderStatus(order.id, 'Оплачено')");
  const fallbackIndex = source.indexOf('this.manualCardPaymentFlow.confirm');
  assert.ok(genericIndex >= 0 && fallbackIndex > genericIndex);
  assert.match(source, /manualCardPaymentFallbackAccessDecision/);
  assert.doesNotMatch(source, /confirmManagerManualCardPayment/);
});

test('manager exact-conflict flow allows manager without weakening shared worker access', () => {
  const method = managerSource.match(/private async applyStandaloneOrderStatus\([\s\S]*?\r?\n  \}\r?\n\r?\n  private canUsePrivilegedPaymentFallback/);
  assert.ok(method, 'applyStandaloneOrderStatus method was not found');
  assert.match(method[0], /this\.manualCardPaymentFlow\.confirm\(order\.id\)/);
  assert.match(
    managerSource,
    /hasAnyRealmRole\(\['OWNER', 'ADMIN', 'MANAGER'\]\)/
  );
  assert.doesNotMatch(workerSource, /hasAnyRealmRole\(\['OWNER', 'ADMIN', 'MANAGER'\]\)/);
});
