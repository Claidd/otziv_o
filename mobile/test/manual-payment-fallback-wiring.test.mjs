import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const reviewCheckSource = fs.readFileSync('src/app/features/review-check.page.ts', 'utf8');
const workerSource = fs.readFileSync('src/app/features/worker.page.ts', 'utf8');

test('review-check tries the ordinary paid action before the privileged exact-conflict fallback', () => {
  const method = reviewCheckSource.match(/async markPaid\(\): Promise<void> \{[\s\S]*?\r?\n  \}\r?\n\r?\n  previousReview/);
  assert.ok(method, 'markPaid method was not found');
  const source = method[0];
  const genericIndex = source.indexOf('this.api.markReviewCheckPaid');
  const detailsIndex = source.indexOf('this.api.getManagerOrderDetails');
  const fallbackIndex = source.indexOf('this.api.confirmManagerManualCardPayment');
  assert.ok(genericIndex >= 0 && detailsIndex > genericIndex && fallbackIndex > detailsIndex);
  assert.match(source, /authoritativeOrder\.totalSumWithBadReviews \?\? authoritativeOrder\.sum/);
  assert.match(source, /manualCardPaymentFallbackAccessDecision/);
  assert.match(source, /shouldSubmitManualCardPaymentFallback/);
});

test('worker tries the ordinary paid action before the privileged exact-conflict fallback', () => {
  const method = workerSource.match(/private async updatePaidOrderStatus\([\s\S]*?\r?\n  \}\r?\n\r?\n  async toggleOrderClientWaiting/);
  assert.ok(method, 'updatePaidOrderStatus method was not found');
  const source = method[0];
  const genericIndex = source.indexOf("this.api.updateWorkerOrderStatus(order.id, 'Оплачено')");
  const detailsIndex = source.indexOf('this.api.getManagerOrderDetails');
  const fallbackIndex = source.indexOf('this.api.confirmManagerManualCardPayment');
  assert.ok(genericIndex >= 0 && detailsIndex > genericIndex && fallbackIndex > detailsIndex);
  assert.match(source, /authoritativeOrder\.totalSumWithBadReviews \?\? authoritativeOrder\.sum/);
  assert.match(source, /manualCardPaymentFallbackAccessDecision/);
  assert.match(source, /manualCardPaymentFallbackDecision/);
  assert.match(source, /shouldSubmitManualCardPaymentFallback/);
});
