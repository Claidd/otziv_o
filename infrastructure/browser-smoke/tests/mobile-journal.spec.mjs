import { readFile } from 'node:fs/promises';
import { test, expect, deferred } from './fixtures.mjs';

const responses = JSON.parse(await readFile(new URL('../../../contracts/fixtures/client-api-current.json', import.meta.url), 'utf8')).responses;
const journalPath = '/api/admin/payments/tbank-links';

function journal(names, page = 0) {
  return { ...responses.AdminPaymentLinksPageResponseOutput, page, size: 10, totalPages: 4, totalElements: 33, source: 'LIVE',
    items: names.map((name, index) => ({ ...responses.AdminPaymentLinkResponseOutput,
      id: 701 + index + page * 10, companyTitle: name, paymentMethod: 'BANK_FORM', status: 'NEW',
      // Deliberately opposite chronological order: the client must preserve the
      // server page even when these dates would produce a different local sort.
      createdAt: index === 0 ? '2026-09-08T12:00:00' : '2026-09-07T12:00:00',
      lastError: null, publicUrl: null, paymentUrl: null })),
    summary: { ...responses.AdminPaymentLinkSummaryResponseOutput, totalElements: 33 } };
}

test.beforeEach(({ fixture }) => {
  fixture.roles = ['ADMIN'];
  fixture.completeMobileLogin = true;
  fixture.api.set('POST /api/manager-activity', (route) => route.fulfill({ status: 204 }));
  fixture.api.set('GET /api/admin/payments/tbank-status', (route) => route.fulfill({ json: {
    ...responses.TbankPaymentStatusResponseOutput, runtimeMode: 'TEST', publicBaseUrl: 'https://fixture.example.test' } }));
  fixture.api.set('GET /api/admin/payments/bank-profiles', (route) => route.fulfill({ json: { profiles: [], managers: [] } }));
  fixture.api.set('GET /api/admin/payments/manual-tasks', (route) => route.fulfill({ json: [] }));
  fixture.api.set('GET /api/admin/payments/tbank-runtime-settings', (route) => route.fulfill({ json: {
    ...responses.TbankRuntimeSettingsResponseOutput, runtimeMode: 'TEST', paymentPageMode: 'BANK_ONLY' } }));
  fixture.api.set('GET /api/admin/payments/manual-recipients/monthly-summary', (route) => route.fulfill({ json: {
    ...responses.ManualPaymentRecipientMonthlySummaryResponseOutput, month: '2026-09', items: [] } }));
  fixture.api.set('GET /api/personal-reminders', (route) => route.fulfill({ json: [] }));
  fixture.api.set('GET /api/manager-report-review/access-state', (route) => route.fulfill({ json: {
    pending: false, restricted: false, questionCount: 0, answeredQuestionCount: 0 } }));
});

async function openJournal(page, fixture) {
  // Exercise the shipped mobile AuthService, PKCE and callback against a local
  // synthetic issuer. No injected tokens, Angular hooks or native storage shim.
  await page.goto('/login?target=%2Ftabs%2Ftbank');
  await expect(page).toHaveURL(/\/tabs\/tbank$/);
  expect(fixture.tokenGrants).toContain('authorization_code');
  await expect(page.locator('app-tbank-page .payment-card h2')).toHaveText(['Initial mobile journal']);
}

test('mobile journal sorting cancels a pending page, requests server order and ignores the old page', async ({ page, fixture }) => {
  const release = deferred();
  const queries = [];
  fixture.api.set(`GET ${journalPath}`, async (route) => {
    const query = Object.fromEntries(new URL(route.request().url()).searchParams);
    queries.push(query);
    if (query.page === '1') {
      await release.promise;
      return route.fulfill({ json: journal(['Stale mobile page'], 1) }).catch(() => {});
    }
    return route.fulfill({ json: journal(query.sortDirection === 'asc'
      ? ['Server first mobile payment', 'Server second mobile payment'] : ['Initial mobile journal']) });
  });
  try {
    await openJournal(page, fixture);
    const panel = page.locator('app-tbank-page');
    const aborted = page.waitForEvent('requestfailed', (request) => request.url().includes(journalPath)
      && new URL(request.url()).searchParams.get('page') === '1');
    await panel.getByRole('button', { name: 'Следующая страница', exact: true }).click();
    await expect.poll(() => queries.some((query) => query.page === '1')).toBe(true);
    await expect(panel.getByText('Загружаем платежи', { exact: true })).toBeVisible();
    await panel.getByRole('button', { name: 'Показать сначала старые платежи', exact: true }).click();
    await aborted;
    await expect(panel.locator('.payment-card h2')).toHaveText(['Server first mobile payment', 'Server second mobile payment']);
    release.resolve();
    expect(queries.at(-1)).toMatchObject({ page: '0', sortDirection: 'asc', source: 'LIVE' });
    await expect(panel.getByRole('region', { name: 'Пагинация', exact: true })).toContainText('1 / 4');
    await expect(panel.getByRole('button', { name: 'платежи', exact: true })).toContainText('2/33');
    await expect(panel.getByRole('button', { name: 'Следующая страница', exact: true })).toBeEnabled();
    await expect(panel.getByText('Stale mobile page', { exact: true })).toHaveCount(0);
    await expect(panel.getByText('Загружаем платежи', { exact: true })).toHaveCount(0);
  } finally { release.resolve(); }
});

test('mobile journal accepts the latest status while a previous status read is loading', async ({ page, fixture }) => {
  const release = deferred();
  const queries = [];
  fixture.api.set(`GET ${journalPath}`, async (route) => {
    const query = Object.fromEntries(new URL(route.request().url()).searchParams);
    queries.push(query);
    if (query.status === 'paid') {
      await release.promise;
      return route.fulfill({ status: 503, json: { message: 'Stale mobile paid failure' } }).catch(() => {});
    }
    return route.fulfill({ json: journal(query.status === 'failed' ? ['Current mobile failed journal'] : ['Initial mobile journal']) });
  });
  try {
    await openJournal(page, fixture);
    const panel = page.locator('app-tbank-page');
    const statuses = panel.getByRole('region', { name: 'Статусы платежей', exact: true });
    const aborted = page.waitForEvent('requestfailed', (request) => request.url().includes(journalPath)
      && new URL(request.url()).searchParams.get('status') === 'paid');
    await statuses.getByRole('button', { name: 'Оплачены', exact: true }).click();
    await expect.poll(() => queries.some((query) => query.status === 'paid')).toBe(true);
    await statuses.getByRole('button', { name: 'Ошибки', exact: true }).click();
    await aborted;
    await expect(panel.locator('.payment-card h2')).toHaveText(['Current mobile failed journal']);
    release.resolve();
    expect(queries.at(-1)).toMatchObject({ page: '0', status: 'failed', source: 'LIVE', sortDirection: 'desc' });
    await expect(statuses.getByRole('button', { name: 'Ошибки', exact: true })).toHaveClass(/active/);
    await expect(panel.getByRole('region', { name: 'Пагинация', exact: true })).toContainText('1 / 4');
    await expect(panel.getByRole('button', { name: 'платежи', exact: true })).toContainText('1/33');
    await expect(panel.getByText('Stale mobile paid failure', { exact: true })).toHaveCount(0);
    await expect(panel.getByText('Загружаем платежи', { exact: true })).toHaveCount(0);
  } finally { release.resolve(); }
});
