import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const tbankSource = fs.readFileSync('src/app/features/tbank.page.ts', 'utf8');
const webTbankSource = fs.readFileSync('../frontend/src/app/features/admin/tbank-payments/tbank-payments.component.ts', 'utf8');
const webTbankTemplate = fs.readFileSync('../frontend/src/app/features/admin/tbank-payments/tbank-payments.component.html', 'utf8');
const mobileCommonSource = fs.readFileSync('src/app/shared/mobile-common-manual-payment-dialog.component.ts', 'utf8');
const webCommonSource = fs.readFileSync(
  '../frontend/src/app/features/admin/common-billing/common-manual-payment-attribution-modal.component.ts',
  'utf8'
);
const {
  MobileManualPaymentTaskOperationKeyDraft,
  newMobileManualPaymentTaskOperationKey
} = loadTsModule('src/app/shared/manual-payment-operation-key.ts');
const { mobileCommonManualPaymentDraftAfterRouteRefresh } = loadTsModule(
  'src/app/shared/common-manual-payment-route-refresh.ts'
);

test('manual task create key survives retries and rotates only for a new draft', () => {
  const keys = ['draft-one', 'draft-two'];
  const draft = new MobileManualPaymentTaskOperationKeyDraft(() => keys.shift());
  assert.equal(draft.current(), 'draft-one');
  assert.equal(draft.current(), 'draft-one');
  assert.equal(draft.rotate(), 'draft-two');
  assert.ok(newMobileManualPaymentTaskOperationKey().length <= 160);

  const createMethod = tbankSource.match(/async createManualTask\(\): Promise<void> \{[\s\S]*?\r?\n  \}\r?\n\r?\n  resetAdminTaskDraft/);
  assert.ok(createMethod, 'createManualTask method was not found');
  assert.match(createMethod[0], /operationKey: this\.adminTaskOperationKey\.current\(\)/);
  const catchBlock = createMethod[0].slice(createMethod[0].indexOf('catch (error)'));
  assert.doesNotMatch(catchBlock, /adminTaskOperationKey\.rotate/);
  assert.match(tbankSource, /private startNewAdminTaskDraft\(\): void \{[\s\S]*?this\.adminTaskOperationKey\.rotate\(\)/);
});

test('journal legacy confirmation opens typed flow only for actual-recipient requirement', () => {
  const method = tbankSource.match(/async confirmManual\(link: AdminPaymentLinkResponse\): Promise<void> \{[\s\S]*?\r?\n  \}\r?\n\r?\n  async markManualReceipt/);
  assert.ok(method, 'confirmManual method was not found');
  assert.match(method[0], /mobilePaymentRouteErrorCode\(error\)/);
  assert.match(method[0], /routeCode === 'ACTUAL_RECIPIENT_REQUIRED'/);
  assert.match(method[0], /this\.manualCardPaymentFlow\.confirm\(orderId\)/);
  assert.match(method[0], /mobilePaymentRouteErrorMessage\(\s*typedError/);
  assert.match(tbankSource, /link\.manualSource !== 'CONTRACTOR_PAYMENT_PROFILE'/);
  assert.match(webTbankSource, /routeCode === 'ACTUAL_RECIPIENT_REQUIRED'/);
  assert.match(webTbankSource, /this\.journalManualCardPaymentOrder\.set\(\{/);
  assert.match(webTbankSource, /manualPaymentRouteErrorMessage\(err/);
});

test('monthly recipient summary is fail-soft and outside the main bootstrap Promise.all', () => {
  const method = tbankSource.match(/async load\(\): Promise<void> \{[\s\S]*?\r?\n  \}\r?\n\r?\n  setMode/);
  assert.ok(method, 'load method was not found');
  assert.match(method[0], /void this\.loadRecipientMonthlySummary\(\)/);
  const all = method[0].match(/Promise\.all\(\[([\s\S]*?)\]\)/);
  assert.ok(all, 'bootstrap Promise.all was not found');
  assert.doesNotMatch(all[1], /getAdminManualRecipientMonthlySummary/);
  assert.match(tbankSource, /readonly recipientSummaryError = signal<string \| null>\(null\)/);

  const webLoad = webTbankSource.match(/load\(\): void \{[\s\S]*?\r?\n  \}\r?\n\r?\n  setRecipientSummaryMonth/);
  assert.ok(webLoad, 'web load method was not found');
  const webForkJoin = webLoad[0].match(/forkJoin\(\{([\s\S]*?)\}\)/);
  assert.ok(webForkJoin, 'web bootstrap forkJoin was not found');
  assert.doesNotMatch(webForkJoin[1], /recipientSummary|getAdminManualRecipientMonthlySummary/);
  assert.match(webTbankSource, /readonly recipientSummaryError = signal<string \| null>\(null\)/);
});

test('monthly recipient summary explicitly reports gross confirmations before returns', () => {
  assert.match(tbankSource, /Подтверждённые оплаты по получателям до возвратов/);
  assert.match(tbankSource, /Подтверждено до возвратов/);
  assert.match(webTbankTemplate, /Подтверждённые ручные оплаты · до возвратов/);
  assert.match(webTbankTemplate, /последующие возвраты здесь не вычитаются/);
  assert.match(webTbankTemplate, /Подтверждено до возвратов/);
});

test('stale common split refresh preserves evidence and resets route-sensitive state', () => {
  const refreshed = mobileCommonManualPaymentDraftAfterRouteRefresh({
    remainingKopecks: 250000,
    defaultRecipientKey: 'TASK:12:4',
    candidates: [{ key: 'TASK:12:4' }]
  }, 'reason', 'https://example.test/receipt', () => 'new-row');

  assert.deepEqual(JSON.parse(JSON.stringify(refreshed)), {
    rows: [{ rowKey: 'new-row', recipientKey: 'TASK:12:4', amountRubles: '2500,00' }],
    reason: 'reason',
    receiptUrl: 'https://example.test/receipt',
    paymentReceived: false,
    finalAcknowledged: false
  });
  for (const source of [mobileCommonSource, webCommonSource]) {
    assert.match(source, /if \([^)]*RetryablePaymentRouteError\(error\)\) \{[\s\S]*?rows\.set\(\[\]\)[\s\S]*?paymentReceived\.set\(false\)[\s\S]*?finalAcknowledged\.set\(false\)[\s\S]*?load\(true\)/i);
  }
});

test('external-link task forms keep the typed accounting target selector visible', () => {
  assert.match(
    webTbankTemplate,
    /\} @else \{[\s\S]*?<span>Телефон<\/span>[\s\S]*?<span>Получатель в банке<\/span>[\s\S]*?\}\s*<label class="profile-field manual-task-accounting-field">/
  );
  assert.match(
    tbankSource,
    /\} @else \{[\s\S]*?<span>Телефон<\/span>[\s\S]*?\}\s*<label class="profile-field">\s*<span>Получатель в банке<\/span>[\s\S]*?<span>Кому учитывать оплату<\/span>/
  );
});
