import { test, expect, deferred } from './fixtures.mjs';

test.beforeEach(({ fixture }) => {
  fixture.api.set('POST /api/manager-activity', (route) => route.fulfill({ status: 204 }));
});

function phone(id, number, fio) {
  return { id, number, fio, amountAllowed: 1, amountSent: 0, blockTime: 3, active: true,
    googleLoginPresent: false, googlePasswordPresent: false, avitoPasswordPresent: false,
    mailLoginPresent: false, mailPasswordPresent: false, deviceTokens: [] };
}

test('switching dictionary tabs aborts a hidden list read before it can populate another editor', async ({ page, fixture }) => {
  fixture.roles = ['ADMIN'];
  const release = deferred();
  let started = false;
  fixture.api.set('GET /api/admin/phones', async (route) => {
    started = true;
    await release.promise;
    await route.fulfill({ json: { phones: [phone(701, '+79990000701', 'Hidden fixture')], operators: [] } }).catch(() => {});
  });
  fixture.api.set('GET /api/admin/categories', (route) => route.fulfill({ json: [] }));
  const aborted = page.waitForEvent('requestfailed', (request) => request.url().endsWith('/api/admin/phones'));
  await page.goto('/admin/dictionaries?tab=phones');
  await expect.poll(() => started).toBe(true);
  await page.getByRole('navigation', { name: 'Справочники' }).getByRole('button', { name: /Категории/ }).click();
  const request = await aborted;
  expect(request.failure().errorText).toMatch(/ABORTED|FAILED/);
  release.resolve();
  await expect(page.getByRole('heading', { name: 'Категории', exact: true })).toBeVisible();
  await expect(page.locator('input[formcontrolname="number"]')).toHaveCount(0);
  await expect(page.getByText('Hidden fixture', { exact: true })).toHaveCount(0);
});

test('worker role cannot open the administrator dictionary editor', async ({ page, fixture }) => {
  fixture.roles = ['WORKER'];
  await page.goto('/admin/dictionaries?tab=phones');
  await expect(page).toHaveURL(/\/$/);
  await expect(page.locator('input[formcontrolname="number"]')).toHaveCount(0);
  expect(fixture.requests.filter((request) => request.includes('/api/admin/'))).toEqual([]);
});

for (const selection of ['B', 'A after B']) {
test(`saving phone A cannot overwrite ${selection} selected while the response is pending`, async ({ page, fixture }) => {
  fixture.roles = ['ADMIN'];
  const first = phone(701, '+79990000701', 'Fixture A');
  const second = phone(702, '+79990000702', 'Fixture B');
  const release = deferred();
  const writes = [];
  fixture.api.set('GET /api/admin/phones', (route) => route.fulfill({ json: { phones: [first, second], operators: [] } }));
  fixture.api.set('PUT /api/admin/phones/701', async (route) => {
    const body = route.request().postDataJSON();
    writes.push(body);
    await release.promise;
    await route.fulfill({ json: { ...first, ...body } });
  });
  await page.goto('/admin/dictionaries?tab=phones');
  await page.getByRole('row').filter({ hasText: first.number }).click();
  await page.locator('input[formcontrolname="fio"]').fill('Fixture A edited');
  await page.locator('form').filter({ has: page.locator('input[formcontrolname="number"]') }).getByRole('button', { name: 'Сохранить', exact: true }).click();
  await expect.poll(() => writes.length).toBe(1);
  await page.getByRole('row').filter({ hasText: second.number }).click();
  if (selection === 'A after B') await page.getByRole('row').filter({ hasText: first.number }).click();
  await page.locator('input[formcontrolname="fio"]').fill('Selected editor unsaved');
  const response = page.waitForResponse((item) => item.url().endsWith('/api/admin/phones/701'));
  release.resolve();
  await (await response).finished();
  await expect(page.getByRole('button', { name: 'Сохранить', exact: true })).toBeEnabled();
  await expect(page.locator('input[formcontrolname="number"]')).toHaveValue(selection === 'B' ? second.number : first.number);
  await expect(page.locator('input[formcontrolname="fio"]')).toHaveValue('Selected editor unsaved');
  expect(writes).toHaveLength(1);
  expect(writes[0].fio).toBe('Fixture A edited');
});
}
