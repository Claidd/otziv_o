import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (relativeUrl) => readFileSync(new URL(relativeUrl, import.meta.url), 'utf8');
const api = read('../src/app/core/api.service.ts');
const orderDetails = read('../src/app/features/order-details.page.ts');
const worker = read('../src/app/features/worker.page.ts');
const workerEditor = read('../src/app/features/worker/mobile-worker-review-edit-sheet.component.ts');

const sliceBetween = (source, startMarker, endMarker) => {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.ok(start >= 0, `missing start marker: ${startMarker}`);
  assert.ok(end > start, `missing end marker after: ${startMarker}`);
  return source.slice(start, end);
};

test('mobile API exposes all scoped credential reveal endpoints', () => {
  for (const endpoint of [
    '/api/manager/orders/${orderId}/reviews/${reviewId}/credential-reveal',
    '/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/credential-reveal',
    '/api/manager/orders/${orderId}/recovery-tasks/${taskId}/credential-reveal',
    '/api/worker/reviews/${reviewId}/credential-reveal',
    '/api/worker/bad-review-tasks/${taskId}/credential-reveal',
    '/api/worker/recovery-tasks/${taskId}/credential-reveal'
  ]) {
    assert.ok(api.includes(endpoint), `missing endpoint: ${endpoint}`);
  }
  assert.doesNotMatch(api, /\/copy-click/);
});

test('ordinary review and task DTOs expose only credential-presence flags', () => {
  for (const [name, next] of [
    ['OrderReviewItem', 'BadReviewSummary'],
    ['BadReviewTaskItem', 'BadReviewTaskUpdateRequest'],
    ['ReviewRecoveryTaskItem', 'ReviewRecoveryTaskUpdateRequest'],
    ['WorkerReviewItem', 'WorkerBotItem']
  ]) {
    const dto = sliceBetween(api, `export interface ${name}`, `export interface ${next}`);
    assert.match(dto, /botLoginPresent: boolean/);
    assert.match(dto, /botPasswordPresent: boolean/);
    assert.doesNotMatch(dto, /\bbotLogin\??:\s*string/);
    assert.doesNotMatch(dto, /\bbotPassword\??:\s*string/);
  }

  const archive = sliceBetween(api, 'export interface ArchiveReviewItem', 'export interface ArchiveOrderDetails');
  assert.doesNotMatch(archive, /\bbotLogin\??:\s*string/);

  const bot = sliceBetween(api, 'export interface WorkerBotItem', 'export interface WorkerPermissions');
  assert.match(bot, /loginPresent: boolean/);
  assert.match(bot, /passwordPresent: boolean/);
  assert.doesNotMatch(bot, /^\s*(login|password)\??:\s*string/m);
});

test('worker and order pages reveal credentials before touching the clipboard', () => {
  const workerCopy = sliceBetween(worker, 'async copyReviewValue(', '\n  reviewCredentialCopyDisabled(');
  assert.ok(workerCopy.indexOf('revealWorkerReviewCredential') < workerCopy.indexOf('this.copyText'));
  assert.match(workerCopy, /revealWorkerBadReviewTaskCredential/);
  assert.match(workerCopy, /revealWorkerRecoveryTaskCredential/);
  assert.match(workerCopy, /response\.credentialPreparation/);

  const orderCopy = sliceBetween(orderDetails, 'async copyReviewField(', '\n  changeBot(');
  assert.ok(orderCopy.indexOf('revealManagerOrderReviewCredential') < orderCopy.indexOf('this.copyText'));
  assert.match(orderCopy, /response\.credentialPreparation/);

  const badTaskCopy = sliceBetween(orderDetails, 'async copyBadReviewTaskField(', '\n  changeBadReviewTaskBot(');
  assert.ok(badTaskCopy.indexOf('revealManagerBadReviewTaskCredential') < badTaskCopy.indexOf('this.copyText'));

  const recoveryCopy = sliceBetween(orderDetails, 'async copyRecoveryTaskField(', '\n  recoveryTaskBotBrowserUrl(');
  assert.ok(recoveryCopy.indexOf('revealManagerRecoveryTaskCredential') < recoveryCopy.indexOf('this.copyText'));
});

test('editors never prefill the stored password and use a new-password control', () => {
  assert.match(orderDetails, /type="password"[\s\S]*autocomplete="new-password"/);
  assert.match(orderDetails, /botPassword:\s*''/);
  assert.match(workerEditor, /type="password"[\s\S]*autocomplete="new-password"/);
  assert.match(workerEditor, /botPassword:\s*''/);
  assert.doesNotMatch(workerEditor, /botPassword:\s*review\.botPassword/);
});
