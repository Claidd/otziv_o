import { test, expect, consent, openPayment, payButton } from './fixtures.mjs';

test('checkout loads and accepts payment details while authentication is unavailable', async ({ page, context, fixture }) => {
  const authRequests = [];
  const paymentHeaders = [];
  await context.route('**/keycloak/**', route => {
    authRequests.push(new URL(route.request().url()).pathname);
    return route.fulfill({ status: 503, body: 'Authentication unavailable' });
  });
  page.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/payments/public/')) paymentHeaders.push(request.headers());
  });
  await openPayment(page);
  await expect(page.locator('#app-startup')).toHaveCount(0);
  await consent(page);
  await expect(payButton(page)).toBeEnabled();
  await payButton(page).click();
  await expect.poll(() => fixture.writes.length).toBe(1);
  expect(authRequests).toEqual([]);
  expect(paymentHeaders.length).toBeGreaterThan(0);
  for (const headers of paymentHeaders) expect(headers.authorization).toBeUndefined();
  await expect(page).toHaveURL(/\/pay\/fixture-payment$/);
});

test('payment result pages also load without authentication', async ({ page, context, fixture }, info) => {
  const authRequests = [];
  await context.route('**/keycloak/**', route => {
    authRequests.push(route.request().url());
    return route.abort();
  });
  for (const pathname of ['/pay/success', '/pay/fail']) {
    await page.goto(pathname);
    await expect(page.locator('app-pay-result')).toBeVisible();
    await expect(page.locator('#app-startup')).toHaveCount(0);
  }
  expect(authRequests).toEqual([]);
});

test('a missing application bundle shows retry and retains the payment link on reload', async ({ page, context, fixture }, info) => {
  await context.route('**/main-*.js', route => route.abort());
  await page.setViewportSize({ width: 390, height: 844 });
  await page.clock.install();
  await page.goto('/pay/fixture-payment?source=message');
  await page.clock.fastForward(16000);
  await expect(page.locator('#app-startup')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Обновить' })).toBeVisible();
  await page.screenshot({ path: info.outputPath('startup-retry-mobile.png'), fullPage: true });
  await context.unroute('**/main-*.js');
  await page.getByRole('button', { name: 'Обновить' }).click();
  await expect(page.getByText('Браузерная проверка', { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/pay\/fixture-payment\?source=message$/);
  await expect(page.locator('#app-startup')).toHaveCount(0);
});
