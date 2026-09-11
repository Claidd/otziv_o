import { readFile } from 'node:fs/promises';
import { test, expect, deferred } from './fixtures.mjs';

const responses = JSON.parse(await readFile(new URL('../../../contracts/fixtures/client-api-current.json', import.meta.url), 'utf8')).responses;
const journalPath = '/api/admin/payments/tbank-links';

function journal(name, page = 0, source = 'LIVE') {
  return { ...responses.AdminPaymentLinksPageResponseOutput, page, size: 25, totalPages: 4, totalElements: 83, source,
    items: [{ ...responses.AdminPaymentLinkResponseOutput, id: 700 + page, companyTitle: name,
      paymentMethod: 'BANK_FORM', status: 'NEW', lastError: null, publicUrl: null, paymentUrl: null }],
    summary: { ...responses.AdminPaymentLinkSummaryResponseOutput, totalElements: 83 } };
}

function task(id, name) {
  return { ...responses.ManualPaymentTaskResponseOutput, id, managerId: 71, manualRecipientName: name,
    manualPaymentType: 'MOBILE_BANK', manualPhone: '+79990000701', manualBankName: 'Fixture bank',
    status: 'ACTIVE', accountingTargetKind: 'OWNER', accountingTargetProfileId: 77,
    accountingTargetResolved: true, targetAmountKopecks: 100000, reservedAmountKopecks: 0,
    confirmedAmountKopecks: 0, pendingAmountKopecks: 0, remainingAmountKopecks: 100000,
    targetProjectedOverrunKopecks: 0, comment: '' };
}

test.beforeEach(({ fixture }) => {
  fixture.roles = ['ADMIN'];
  fixture.api.set('POST /api/manager-activity', (route) => route.fulfill({ status: 204 }));
  fixture.api.set('GET /api/admin/payments/tbank-status', (route) => route.fulfill({ json: {
    ...responses.TbankPaymentStatusResponseOutput, runtimeMode: 'TEST', publicBaseUrl: 'https://fixture.example.test' } }));
  fixture.api.set('GET /api/admin/payments/bank-profiles', (route) => route.fulfill({ json: { profiles: [], managers: [] } }));
  fixture.api.set('GET /api/admin/payments/manual-tasks', (route) => route.fulfill({ json: [] }));
  fixture.api.set('GET /api/admin/payments/tbank-runtime-settings', (route) => route.fulfill({ json: {
    ...responses.TbankRuntimeSettingsResponseOutput, runtimeMode: 'TEST', paymentPageMode: 'BANK_ONLY' } }));
  fixture.api.set('GET /api/admin/payments/manual-recipients/monthly-summary', (route) => route.fulfill({ json: {
    ...responses.ManualPaymentRecipientMonthlySummaryResponseOutput, month: '2026-09', items: [] } }));
  fixture.api.set('GET /api/admin/payments/manual-tasks/accounting-targets', (route) => route.fulfill({ json: [{
    ...responses.ManualPaymentTaskAccountingTargetOptionOutput, key: 'owner-77', kind: 'OWNER', profileId: 77,
    label: 'Fixture owner', enabled: true, projectedOverrunKopecks: 0, needsAcknowledgement: false }] }));
  fixture.api.set(`GET ${journalPath}`, (route) => route.fulfill({ json: journal('Initial journal') }));
});

test('payment journal cancels a pending page when status changes and only displays the last filter', async ({ page, fixture }) => {
  const pendingPage = deferred();
  const pendingPaid = deferred();
  const queries = [];
  fixture.api.set(`GET ${journalPath}`, async (route) => {
    const params = new URL(route.request().url()).searchParams;
    const query = Object.fromEntries(params);
    queries.push(query);
    if (query.page === '1') {
      await pendingPage.promise;
      return route.fulfill({ json: journal('Stale page two', 1) }).catch(() => {});
    }
    if (query.status === 'paid') {
      await pendingPaid.promise;
      return route.fulfill({ status: 503, json: { message: 'Stale paid failure' } }).catch(() => {});
    }
    return route.fulfill({ json: journal(query.status === 'failed' ? 'Current failed filter' : 'Initial journal') });
  });
  await page.goto('/admin/bank-payments');
  const panel = page.locator('.payments-panel');
  await expect(panel.getByText('Initial journal', { exact: true })).toBeVisible();
  const abortedPage = page.waitForEvent('requestfailed', (request) => request.url().includes(journalPath) && new URL(request.url()).searchParams.get('page') === '1');
  await panel.locator('.pagination-controls button').last().click();
  await expect.poll(() => queries.some((query) => query.page === '1')).toBe(true);
  await expect(panel.getByText('Initial journal', { exact: true })).toHaveCount(0);
  await page.getByLabel('Статус платежей', { exact: true }).selectOption({ label: 'Оплачены' });
  await abortedPage;
  await expect.poll(() => queries.some((query) => query.status === 'paid')).toBe(true);
  const abortedPaid = page.waitForEvent('requestfailed', (request) => request.url().includes(journalPath) && new URL(request.url()).searchParams.get('status') === 'paid');
  await page.getByLabel('Статус платежей', { exact: true }).selectOption({ label: 'Ошибки' });
  await abortedPaid;
  await expect(panel.getByText('Current failed filter', { exact: true })).toBeVisible();
  pendingPage.resolve(); pendingPaid.resolve();
  await expect(panel.locator('.pagination-row')).toContainText('Страница 1 из 4');
  await expect(panel.getByText('Stale page two', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Stale paid failure', { exact: true })).toHaveCount(0);
  await expect(panel.locator('.pagination-controls button').last()).toBeEnabled();
  expect(queries.at(-1)).toMatchObject({ page: '0', status: 'failed', source: 'LIVE' });
});

test('payment journal search cancels the bootstrap read before the debounce and preserves the current result', async ({ page, fixture }) => {
  const initial = deferred();
  const queries = [];
  fixture.api.set(`GET ${journalPath}`, async (route) => {
    const query = Object.fromEntries(new URL(route.request().url()).searchParams);
    queries.push(query);
    if (!query.search) {
      await initial.promise;
      return route.fulfill({ json: journal('Stale bootstrap') }).catch(() => {});
    }
    return route.fulfill({ json: journal('Current search result') });
  });
  const aborted = page.waitForEvent('requestfailed', (request) => request.url().includes(journalPath) && !new URL(request.url()).searchParams.has('search'));
  await page.goto('/admin/bank-payments');
  await expect.poll(() => queries.length).toBe(1);
  await page.getByLabel('Поиск платежей', { exact: true }).fill('current query');
  await aborted;
  await expect(page.locator('.payments-panel').getByText('Current search result', { exact: true })).toBeVisible();
  initial.resolve();
  await expect(page.getByText('Stale bootstrap', { exact: true })).toHaveCount(0);
  expect(queries.at(-1)).toMatchObject({ search: 'current query', page: '0' });
});

for (const outcome of ['success', 'error']) {
test(`late manual task A save ${outcome} preserves the newly selected B editor and does not replay the write`, async ({ page, fixture }) => {
  const first = task(701, 'Fixture task A');
  const second = task(702, 'Fixture task B');
  const release = deferred();
  const writes = [];
  fixture.api.set('GET /api/admin/payments/manual-tasks', (route) => route.fulfill({ json: [first, second] }));
  fixture.api.set('PUT /api/admin/payments/manual-tasks/701', async (route) => {
    const body = route.request().postDataJSON(); writes.push(body);
    await release.promise;
    return outcome === 'success'
      ? route.fulfill({ json: { ...first, ...body, generation: first.generation + 1 } })
      : route.fulfill({ status: 503, json: { message: 'Old task save failed' } });
  });
  await page.goto('/admin/bank-payments');
  const cardA = page.locator('.manual-task-admin-card').filter({ has: page.getByText(first.manualRecipientName, { exact: true }) });
  const cardB = page.locator('.manual-task-admin-card').filter({ has: page.getByText(second.manualRecipientName, { exact: true }) });
  await cardA.getByRole('button', { name: 'Ред.', exact: true }).click();
  await cardA.getByLabel('Комментарий', { exact: true }).fill('A saved comment');
  await cardA.getByRole('button', { name: 'Сохранить', exact: true }).click();
  await expect.poll(() => writes.length).toBe(1);
  await cardB.getByRole('button', { name: 'Ред.', exact: true }).click();
  await cardB.getByLabel('Комментарий', { exact: true }).fill('B unsaved comment');
  const response = page.waitForResponse((item) => item.url().endsWith('/api/admin/payments/manual-tasks/701'));
  release.resolve();
  await (await response).finished();
  await expect(cardB.getByLabel('Комментарий', { exact: true })).toHaveValue('B unsaved comment');
  await expect(cardB.getByRole('button', { name: 'Сохранить', exact: true })).toBeEnabled();
  await expect(page.getByText('Old task save failed', { exact: true })).toHaveCount(0);
  expect(writes).toHaveLength(1);
  expect(writes[0].comment).toBe('A saved comment');
});
}
