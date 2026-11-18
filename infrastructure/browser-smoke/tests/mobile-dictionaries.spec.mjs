import { test, expect, deferred } from './fixtures.mjs';

const cities = [{ id: 1, title: 'Иркутск' }, { id: 2, title: 'Тюмень' }];
const account = { id: 11, login: 'fixture-account', fio: 'Тестовый аккаунт', passwordPresent: true, city: cities[1], status: { id: 1, title: 'Новый' }, worker: null, counter: 0, active: false };

test.beforeEach(({ fixture }) => {
  fixture.roles = ['ADMIN'];
  fixture.completeMobileLogin = true;
  fixture.api.set('POST /api/manager-activity', route => route.fulfill({ status: 204 }));
  const responses = {
    '/api/personal-reminders': [],
    '/api/manager-report-review/access-state': { pending: false, restricted: false, questionCount: 0, answeredQuestionCount: 0 },
    '/api/admin/categories': [{ id: 1, title: 'Категория', subCategoryCount: 1, subCategories: [{ id: 1, title: 'Подкатегория', categoryId: 1 }] }],
    '/api/admin/subcategories': [{ id: 1, title: 'Подкатегория', category: { id: 1, title: 'Категория' } }],
    '/api/admin/cities': cities,
    '/api/admin/products': { products: [{ id: 1, title: 'Продукт', price: 200, category: { id: 1, title: 'Категория' }, photo: false }], categories: [] },
    '/api/admin/phones': { phones: [], operators: [] },
    '/api/admin/bots/count': { count: 1 },
    '/api/admin/bots': { bots: [account], cities, workers: [], statuses: [], total: 1, totalPages: 1, page: 0, size: 50 },
    '/api/admin/promo-texts': [],
    '/api/admin/manager-texts': []
  };
  for (const [url, json] of Object.entries(responses)) fixture.api.set(`GET ${url}`, route => route.fulfill({ json }));
});

async function open(page) {
  await page.goto('/login?target=%2Ftabs%2Fhome%2Fdictionaries');
  await expect(page).toHaveURL(/\/tabs\/home\/dictionaries$/);
  const panel = page.locator('app-mobile-dictionaries');
  await expect(panel.locator('.dict-card').first()).toContainText('Категория');
  return panel;
}

test('dictionary cards and portaled import controls follow the real light/dark switch', async ({ page }) => {
  const panel = await open(page);
  for (const mode of ['dark', 'light']) {
    // Exercise the accessible action independently of Ionic's route transition layers.
    await page.getByRole('button', { name: mode === 'dark' ? 'Ночная тема' : 'Дневная тема', exact: true }).press('Enter');
    for (const [tab, title] of [['Категории', 'Категория'], ['Города', 'Иркутск'], ['Продукты', 'Продукт'], ['Аккаунты', 'Импорт аккаунтов']]) {
      await panel.getByRole('button', { name: tab, exact: true }).click();
      const card = panel.locator('.dict-card').first();
      await expect(card).toContainText(title);
      await expect.poll(() => card.evaluate(el => getComputedStyle(el).backgroundImage.includes('31, 38, 41'))).toBe(mode === 'dark');
    }
    await panel.getByRole('button', { name: 'Добавить из файла по городу', exact: true }).click();
    const modal = page.locator('ion-modal.bot-import-modal');
    const input = modal.getByPlaceholder('Найти город');
    await expect(input).toBeVisible();
    await expect(input).toHaveCSS('background-color', mode === 'dark' ? 'rgb(32, 37, 40)' : 'rgb(255, 255, 255)');
    await expect(modal.getByRole('option', { name: 'Иркутск', exact: true })).toHaveCSS('background-color', mode === 'dark' ? 'rgb(32, 37, 40)' : 'rgb(255, 255, 255)');
    await modal.getByRole('button', { name: 'Отмена', exact: true }).click();
    await expect(modal).toBeHidden();
  }
});

test('city import requires selection, cancels without writing and submits only once while pending', async ({ page, fixture }) => {
  const panel = await open(page);
  const release = deferred();
  const writes = [];
  fixture.api.set('POST /api/admin/bots/import', async route => {
    writes.push(new URL(route.request().url()).searchParams.get('cityId'));
    await release.promise;
    await route.fulfill({ json: { added: 1, totalRows: 1, skippedDuplicates: 0, skippedInvalid: 0, duplicates: [] } });
  });
  await panel.getByRole('button', { name: 'Аккаунты', exact: true }).click();
  const openImport = () => panel.getByRole('button', { name: 'Добавить из файла по городу', exact: true }).click();
  const modal = page.locator('ion-modal.bot-import-modal');
  const upload = modal.getByRole('button', { name: 'Загрузить', exact: true });
  const file = { name: 'fixture-accounts.txt', mimeType: 'text/plain', buffer: Buffer.from('fixture-account:synthetic-password') };
  await openImport();
  await modal.locator('input[type=file]').setInputFiles(file);
  await modal.getByPlaceholder('Найти город').fill('Тюм');
  await expect(upload).toBeDisabled();
  await modal.getByRole('option', { name: 'Тюмень', exact: true }).click();
  await expect(upload).toBeEnabled();
  await modal.getByRole('button', { name: 'Отмена', exact: true }).click();
  await expect(modal).toBeHidden();
  expect(writes).toEqual([]);
  await openImport();
  await expect(modal.getByPlaceholder('Найти город')).toHaveValue('');
  await expect(upload).toBeDisabled();
  await modal.getByRole('option', { name: 'Тюмень', exact: true }).click();
  await modal.locator('input[type=file]').setInputFiles(file);
  try {
    await upload.click();
    await expect.poll(() => writes).toEqual(['2']);
    await expect(modal.getByRole('button', { name: 'Загружаем...', exact: true })).toBeDisabled();
    await expect(modal.getByRole('button', { name: 'Отмена', exact: true })).toBeDisabled();
    release.resolve();
    await expect(modal.getByRole('status')).toContainText('Добавлено: 1');
    await expect(upload).toBeDisabled();
    expect(writes).toEqual(['2']);
  } finally { release.resolve(); }
});
