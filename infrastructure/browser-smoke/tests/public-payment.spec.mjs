import { test, expect, consent, deferred, openPayment, payButton, resume, paymentInitResponse } from './fixtures.mjs';

test('unknown server status cannot expose an actionable payment capability', async ({ page, fixture }, info) => {
  fixture.payment.status = 'FUTURE_PAYMENT_STATE';
  fixture.payment.payable = true;
  await openPayment(page);
  if (info.project.name === 'web') {
    await consent(page);
    await expect(payButton(page)).toBeDisabled();
  } else {
    await expect(page.locator('form.pay-form')).toHaveCount(0);
    await expect(page.getByText('Эта ссылка недоступна для оплаты.')).toBeVisible();
  }
  expect(fixture.writes).toEqual([]);
});

test('consent gates payment and a pending/unknown result never automatically replays it', async ({ page, fixture }) => {
  const release = deferred();
  fixture.respondToInit = async (route) => {
    await release.promise;
    await route.fulfill({ status: 503, json: { message: 'Результат операции неизвестен. Проверьте статус.' } });
  };
  await openPayment(page);
  await expect(payButton(page)).toBeDisabled();
  await page.locator('input[name="email"]').fill('browser-fixture@example.test');
  const boxes = page.locator('form.pay-form input[type="checkbox"]');
  await boxes.nth(0).check();
  await boxes.nth(1).check();
  await expect(payButton(page)).toBeDisabled();
  await boxes.nth(2).check();
  await expect(payButton(page)).toBeEnabled();
  await payButton(page).click();
  await expect.poll(() => fixture.writes.length).toBe(1);
  await expect(payButton(page)).toBeDisabled();
  expect(fixture.writes[0]).toEqual({ email: 'browser-fixture@example.test', offerConsent: true, privacyConsent: true, receiptConsent: true });
  release.resolve();
  await expect(page.getByText('Результат операции неизвестен. Проверьте статус.', { exact: true })).toBeVisible();
  await resume(page, fixture);
  expect(fixture.writes).toHaveLength(1);
  await expect(page).toHaveURL(/\/pay\/fixture-payment$/);
  await expect(page.getByRole('heading', { name: 'Оплата прошла успешно', exact: true })).toHaveCount(0);
});

test('transport timeout does not claim success or navigate to a bank', async ({ page, fixture }) => {
  fixture.respondToInit = (route) => route.abort('timedout');
  await openPayment(page);
  await consent(page);
  await payButton(page).click();
  await expect.poll(() => fixture.writes.length).toBe(1);
  await expect(page.getByText(/^Не удалось перейти к оплате\./)).toBeVisible();
  await resume(page, fixture);
  expect(fixture.writes).toHaveLength(1);
  await expect(page).toHaveURL(/\/pay\/fixture-payment$/);
  expect(fixture.blocked.filter((request) => request.type === 'document')).toEqual([]);
});

test('return refresh with malformed capabilities clears the previously actionable form', async ({ page, fixture }) => {
  await openPayment(page);
  await consent(page);
  await expect(payButton(page)).toBeEnabled();
  fixture.payment = { ...fixture.payment, payable: 'true' };
  await resume(page, fixture);
  await expect(page.getByText(/Данные платежа изменились/).first()).toBeVisible();
  await expect(page.locator('form.pay-form')).toHaveCount(0);
  expect(fixture.writes).toEqual([]);
});

test('an in-progress payment cannot navigate a later route after leaving the page', async ({ page, fixture, context }) => {
  const release = deferred();
  const answered = deferred();
  fixture.respondToInit = async (route) => {
    await release.promise;
    await route.fulfill({ json: paymentInitResponse({ paymentUrl: 'https://securepay.tinkoff.ru/browser-fixture' }) });
    answered.resolve();
  };
  await openPayment(page);
  await consent(page);
  await payButton(page).click();
  await expect.poll(() => fixture.writes.length).toBe(1);
  await page.locator('form.pay-form a[href="/offer"]').click();
  await expect(page).toHaveURL(/\/offer$/);
  const response = page.waitForResponse((item) => item.url().endsWith('/fixture-payment/init'));
  release.resolve();
  await answered.promise;
  await (await response).finished();
  // Wait for browser rendering after the response without unloading the application.
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect(page).toHaveURL(/\/offer$/);
  expect(context.pages()).toHaveLength(1);
  expect(fixture.blocked.filter((request) => request.type === 'document')).toEqual([]);
  expect(fixture.writes).toHaveLength(1);
});

test('a status read preserves the edited receipt email and consents', async ({ page, fixture }) => {
  await openPayment(page);
  await consent(page);
  await resume(page, fixture);
  await expect(page.locator('input[name="email"]')).toHaveValue('browser-fixture@example.test');
  for (const box of await page.locator('form.pay-form input[type="checkbox"]').all()) await expect(box).toBeChecked();
  await expect(payButton(page)).toBeEnabled();
  expect(fixture.writes).toEqual([]);
});

test('returning to a payment view does not revive its previous pending bank callback', async ({ page, fixture, context }) => {
  const release = deferred();
  fixture.respondToInit = async (route) => {
    await release.promise;
    await route.fulfill({ json: paymentInitResponse({ paymentUrl: 'https://securepay.tinkoff.ru/browser-fixture' }) });
  };
  await openPayment(page);
  await consent(page);
  await payButton(page).click();
  await expect.poll(() => fixture.writes.length).toBe(1);
  await page.locator('form.pay-form a[href="/offer"]').click();
  await expect(page).toHaveURL(/\/offer$/);
  const reads = fixture.reads;
  await page.goBack();
  await expect(page).toHaveURL(/\/pay\/fixture-payment$/);
  await expect.poll(() => fixture.reads).toBeGreaterThan(reads);
  await expect(payButton(page)).toBeDisabled();
  const response = page.waitForResponse((item) => item.url().endsWith('/fixture-payment/init'));
  release.resolve();
  await (await response).finished();
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect(page).toHaveURL(/\/pay\/fixture-payment$/);
  await expect(payButton(page)).toBeDisabled();
  expect(fixture.writes).toHaveLength(1);
  expect(context.pages()).toHaveLength(1);
  expect(fixture.blocked.filter((request) => request.type === 'document')).toEqual([]);
});

test('only a confirmed status read changes the public page to successful payment', async ({ page, fixture }) => {
  await openPayment(page);
  fixture.payment = { ...fixture.payment, status: 'CONFIRMED', payable: false };
  await resume(page, fixture);
  await expect(page.getByRole('heading', { name: 'Оплата прошла успешно', exact: true })).toBeVisible();
  await expect(page.locator('form.pay-form')).toHaveCount(0);
  expect(fixture.writes).toEqual([]);
});

test('anonymous protected routes require sign-in without issuing privileged writes', async ({ page, fixture }, info) => {
  if (info.project.name === 'web') {
    await page.goto('/admin/dictionaries');
    await expect(page.getByRole('heading', { name: 'Local sign-in fixture' })).toBeVisible();
    const url = new URL(page.url());
    expect(url.searchParams.get('client_id')).toBe('otziv-frontend');
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('redirect_uri')).toMatch(/\/admin\/dictionaries$/);
  } else {
    await page.goto('/tabs/orders');
    await expect(page.getByRole('heading', { name: 'Local sign-in fixture' })).toBeVisible();
    const url = new URL(page.url());
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('scope')).toBe('openid profile email');
    expect(url.searchParams.get('redirect_uri')).toContain('/auth/callback');
  }
  expect(fixture.writes).toEqual([]);
});
