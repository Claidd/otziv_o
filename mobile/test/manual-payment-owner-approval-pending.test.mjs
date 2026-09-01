import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const apiSource = fs.readFileSync('src/app/core/api.service.ts', 'utf8');
const dialogSource = fs.readFileSync(
  'src/app/shared/mobile-manual-card-payment-dialog.component.ts',
  'utf8'
);
const dialogTemplate = fs.readFileSync(
  'src/app/shared/mobile-manual-card-payment-dialog.component.html',
  'utf8'
);
const flowSource = fs.readFileSync(
  'src/app/shared/mobile-manual-card-payment-flow.service.ts',
  'utf8'
);
const managerSource = fs.readFileSync('src/app/features/manager.page.ts', 'utf8');
const reviewCheckSource = fs.readFileSync('src/app/features/review-check.page.ts', 'utf8');
const workerSource = fs.readFileSync('src/app/features/worker.page.ts', 'utf8');
const tbankSource = fs.readFileSync('src/app/features/tbank.page.ts', 'utf8');

function requiredMethod(source, expression, label) {
  const match = source.match(expression);
  assert.ok(match, `${label} method was not found`);
  return match[0];
}

function assertNonCompletedReturnsBefore(source, boundary, label) {
  const confirmationIndex = source.indexOf('manualCardPaymentFlow.confirm');
  const nonCompletedGuard = source
    .slice(confirmationIndex)
    .match(/!mobileManualCardPaymentIsCompleted\(\w+\.result\)/);
  const guardIndex = nonCompletedGuard
    ? confirmationIndex + nonCompletedGuard.index
    : -1;
  const boundaryIndex = source.indexOf(boundary, confirmationIndex);

  assert.ok(confirmationIndex >= 0, `${label} does not invoke the shared manual-card flow`);
  assert.ok(
    guardIndex > confirmationIndex,
    `${label} does not reject every result other than explicit COMPLETED`
  );
  assert.ok(boundaryIndex > guardIndex, `${label} does not check the result before ${boundary}`);
  assert.match(
    source.slice(guardIndex, boundaryIndex),
    /\breturn\b/,
    `${label} must return before treating a non-completed result as paid`
  );
}

test('manual-card confirmation API exposes the backend result instead of erasing it as void', () => {
  assert.match(
    apiSource,
    /export\s+type\s+ManagerManualCardPaymentResultStatus\s*=\s*['"]COMPLETED['"]\s*\|\s*['"]OWNER_APPROVAL_PENDING['"]\s*;/
  );
  assert.match(
    apiSource,
    /export\s+interface\s+ManagerManualCardPaymentResult\s*\{[\s\S]*?status:\s*ManagerManualCardPaymentResultStatus\s*;[\s\S]*?orderId:\s*number\s*;[\s\S]*?paymentLinkId:\s*number\s*;[\s\S]*?message:\s*string\s*;[\s\S]*?\}/
  );

  const method = requiredMethod(
    apiSource,
    /confirmManagerManualCardPayment\([\s\S]*?\r?\n  \}/,
    'confirmManagerManualCardPayment'
  );
  assert.match(method, /\):\s*Observable<ManagerManualCardPaymentResult>\s*\{/);
  assert.match(method, /this\.http\.post<ManagerManualCardPaymentResult>\(/);
  assert.doesNotMatch(method, /Observable<void>|post<void>/);
});

test('manual-card dialog preserves the exact pending/completed backend outcome in modal data', () => {
  assert.match(
    dialogSource,
    /interface\s+MobileManualCardPaymentOutcome\s*\{[\s\S]*?result:\s*ManagerManualCardPaymentResult\s*;[\s\S]*?\}/
  );

  const submit = requiredMethod(
    dialogSource,
    /async submit\(\): Promise<void> \{[\s\S]*?\r?\n  \}\r?\n\r?\n  private async reloadContextAfterConflict/,
    'MobileManualCardPaymentDialogComponent.submit'
  );
  assert.match(
    submit,
    /const\s+\w+\s*=\s*await firstValueFrom\(\s*this\.api\.confirmManagerManualCardPayment\(/
  );
  assert.match(
    submit,
    /MobileManualCardPaymentOutcome\s*=\s*\{[\s\S]*?\bresult\s*:\s*\w+[\s\S]*?\}/
  );
  assert.match(submit, /modalController\.dismiss\(\w+,\s*['"]submitted['"]\)/);
  assert.doesNotMatch(submit, /modalController\.dismiss\(\w+,\s*['"]completed['"]\)/);
});

test('shared flow gives pending approval warning and reserves paid success for completed results', () => {
  const confirm = requiredMethod(
    flowSource,
    /async confirm\([\s\S]*?\r?\n  \}\r?\n\}/,
    'MobileManualCardPaymentFlowService.confirm'
  );

  assert.match(confirm, /result\.role\s*!==?\s*['"]submitted['"]/);
  assert.match(confirm, /\.result\.message/);
  assert.match(confirm, /mobileManualCardPaymentIsCompleted\(result\.data\.result\)/);
  assert.match(confirm, /Оплата отмечена\. Получатель/);
  assert.match(confirm, /Запрос отправлен владельцу|До подтверждения заказ не считается оплаченным/);
  assert.match(confirm, /color:\s*\w+\s*\?\s*['"]success['"]\s*:\s*['"]warning['"]/);
});

test('manager does not patch an order paid while owner approval is pending', () => {
  const method = requiredMethod(
    managerSource,
    /private async applyStandaloneOrderStatus\([\s\S]*?\r?\n  \}\r?\n\r?\n  private canUsePrivilegedPaymentFallback/,
    'ManagerPage.applyStandaloneOrderStatus'
  );
  assertNonCompletedReturnsBefore(method, 'this.patchOrder', 'manager paid fallback');
});

test('review-check does not reload and announce paid while owner approval is pending', () => {
  const method = requiredMethod(
    reviewCheckSource,
    /async markPaid\(\): Promise<void> \{[\s\S]*?\r?\n  \}\r?\n\r?\n  previousReview/,
    'ReviewCheckPage.markPaid'
  );
  assertNonCompletedReturnsBefore(method, 'this.api.getReviewCheck', 'review-check paid fallback');
  assert.match(method, /setStatusMessage\([\s\S]*?Ожидается подтверждение владельца[\s\S]*?true[\s\S]*?\)/);
  assert.match(reviewCheckSource, /\[class\.warning\]="statusMessageWarning\(\)"/);
  assert.match(reviewCheckSource, /statusMessageWarning\(\)\s*\?\s*['"]pending_actions['"]\s*:\s*['"]task_alt['"]/);
});

test('worker does not continue its paid refresh while owner approval is pending', () => {
  const method = requiredMethod(
    workerSource,
    /private async updatePaidOrderStatus\([\s\S]*?\r?\n  \}\r?\n\r?\n  async toggleOrderClientWaiting/,
    'WorkerPage.updatePaidOrderStatus'
  );
  assertNonCompletedReturnsBefore(method, 'await this.load()', 'worker paid fallback');
});

test('T-Bank journal does not treat owner approval pending as a completed manual confirmation', () => {
  const method = requiredMethod(
    tbankSource,
    /async confirmManual\([\s\S]*?\r?\n  \}\r?\n\r?\n  async markManualReceipt/,
    'TbankPage.confirmManual'
  );
  assert.match(
    method,
    /const\s+\w+\s*=\s*await this\.manualCardPaymentFlow\.confirm\([\s\S]*?if\s*\(mobileManualCardPaymentIsCompleted\(\w+\?\.result\)\)\s*\{\s*await this\.load\(\);\s*\}/
  );
  assert.doesNotMatch(
    method,
    /if\s*\(\w+\)\s*\{\s*await this\.load\(\);\s*\}/,
    'T-Bank reload must not be gated by truthiness alone'
  );
});

test('owner selection explicitly warns about pending Telegram approval and changes the action copy', () => {
  assert.match(
    dialogSource,
    /readonly\s+ownerSelected\s*=\s*computed\(\(\)\s*=>\s*this\.selectedRecipient\(\)\?\.recipientType\s*===\s*['"]OWNER['"]\)/
  );
  assert.match(dialogTemplate, /@if\s*\(ownerSelected\(\)\)/);
  assert.match(dialogTemplate, /запрос[^<]*Telegram/i);
  assert.match(dialogTemplate, /До[^<]*подтверждения[^<]*заказ не считается оплаченным/i);
  assert.match(dialogTemplate, /ownerSelected\(\)\s*\?\s*['"]Отправить владельцу['"]/);
});
