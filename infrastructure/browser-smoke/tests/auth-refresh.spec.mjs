import { test, expect } from './fixtures.mjs';

function configureAdmin(fixture) {
  fixture.roles = ['ADMIN'];
  fixture.api.set('POST /api/manager-activity', (route) => route.fulfill({ status: 204 }));
  fixture.api.set('GET /api/admin/categories', (route) => route.fulfill({ json: [] }));
}

for (const outage of ['http503', 'network']) {
  test(`real Keycloak SDK preserves a valid session after refresh ${outage}`, async ({ page, fixture }) => {
    configureAdmin(fixture);
    let categoriesWithBearer = 0;
    fixture.api.set('GET /api/admin/categories', (route) => {
      if (route.request().headers().authorization?.startsWith('Bearer ')) categoriesWithBearer++;
      return route.fulfill({ json: [] });
    });
    fixture.api.set('GET /api/admin/phones', (route) => route.fulfill({
      status: 403, json: { code: 'AUTH_TOKEN_STALE', message: 'refresh fixture' }
    }));
    await page.goto('/admin/dictionaries?tab=categories');
    await expect(page.getByRole('heading', { name: 'Категории', exact: true })).toBeVisible();
    await expect.poll(() => categoriesWithBearer).toBe(1);
    fixture.respondToToken = async (route, grant) => {
      if (grant !== 'refresh_token') return false;
      if (outage === 'http503') await route.fulfill({ status: 503, json: { error: 'temporarily_unavailable' } });
      else await route.abort('failed');
      return true;
    };
    await page.getByRole('navigation', { name: 'Справочники' }).getByRole('button', { name: /Телефоны/ }).click();
    await expect.poll(() => fixture.tokenGrants.filter((grant) => grant === 'refresh_token').length).toBeGreaterThan(0);
    await page.getByRole('navigation', { name: 'Справочники' }).getByRole('button', { name: /Категории/ }).click();
    await expect.poll(() => categoriesWithBearer).toBe(2);
    await expect(page).toHaveURL(/\/admin\/dictionaries/);
    expect(fixture.requests.some((request) => request.includes('/protocol/openid-connect/logout'))).toBe(false);
    await expect(page.getByText('Local sign-in fixture')).toHaveCount(0);
  });
}

test('real Keycloak invalid_grant follows the logout and forced sign-in flow', async ({ page, fixture }) => {
  configureAdmin(fixture);
  fixture.api.set('GET /api/admin/phones', (route) => route.fulfill({
    status: 403, json: { code: 'AUTH_TOKEN_STALE', message: 'refresh fixture' }
  }));
  await page.goto('/admin/dictionaries?tab=categories');
  await expect(page.getByRole('heading', { name: 'Категории', exact: true })).toBeVisible();
  fixture.respondToToken = async (route, grant) => {
    if (grant !== 'refresh_token') return false;
    await route.fulfill({ status: 400, json: { error: 'invalid_grant' } });
    return true;
  };
  await page.getByRole('navigation', { name: 'Справочники' }).getByRole('button', { name: /Телефоны/ }).click();
  await expect(page.getByText('Local sign-in fixture', { exact: true })).toBeVisible();
  expect(fixture.requests.some((request) => request.includes('/protocol/openid-connect/logout'))).toBe(true);
  await expect(page).toHaveURL(/prompt=login/);
});
