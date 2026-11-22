import { test, expect } from './fixtures.mjs';

test.beforeEach(({ fixture }) => {
  fixture.roles = ['ADMIN'];
  fixture.api.set('POST /api/manager-activity', (route) => route.fulfill({ status: 204 }));
  fixture.api.set('GET /api/admin/categories', (route) => route.fulfill({ json: [
    { id: 1, title: 'Тестовая категория', subCategoryCount: 2 }
  ] }));
  fixture.api.set('GET /api/admin/subcategories', (route) => route.fulfill({ json: [] }));
  fixture.api.set('GET /api/admin/phones', (route) => route.fulfill({ json: { operators: [], phones: [
    { id: 701, number: '+79990000701', fio: 'Тестовый номер', amountAllowed: 3, amountSent: 1, blockTime: 5, active: true,
      timer: null, operator: null, googleLoginPresent: false, googlePasswordPresent: true, avitoPasswordPresent: false,
      mailLoginPresent: false, mailPasswordPresent: false, deviceTokens: [] }
  ] } }));
});

test('dictionaries menu shows every section in five groups with one active item in both themes', async ({ page }) => {
  await page.goto('/admin/dictionaries?tab=phones');
  const menu = page.getByRole('navigation', { name: 'Справочники' });
  await expect(menu.getByRole('group')).toHaveCount(5);
  await expect(menu.getByRole('button')).toHaveCount(14);
  await expect(menu.getByRole('button', { name: /Телефоны/ })).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('.dictionary-menu-item.active')).toHaveCount(1);
  await expect(page.getByRole('heading', { name: 'Телефоны операторов', exact: true })).toBeVisible();
  // The layout title must not stay as a browser tooltip over the whole page.
  await expect(page.locator('app-admin-layout')).not.toHaveAttribute('title', /.*/);

  const products = menu.getByRole('button', { name: /Продукты/ });
  await products.hover();
  await expect(products).toHaveCSS('background-image', 'none');

  const surfaces = page.locator('.dictionary-card, .list-search, .dictionary-help-facts div');
  for (const dark of [true, false]) {
    await page.getByRole('button', { name: dark ? 'Темная тема' : 'Светлая тема', exact: true }).first().click();
    await expect.poll(() => surfaces.evaluateAll((elements, dark) => elements.length > 0 && elements.every((element) => {
      const rgb = getComputedStyle(element).backgroundColor.match(/[\d.]+/g).slice(0, 3).map(Number);
      return dark ? rgb.every((channel) => channel < 128) : rgb.every((channel) => channel > 128);
    }), dark)).toBe(true);
    const active = page.locator('.dictionary-menu-item.active');
    await expect(active).toHaveCSS('color', 'rgb(255, 255, 255)');
    expect(await active.evaluate((element) => getComputedStyle(element).backgroundImage)).toContain('gradient');
    const ratios = await page.locator('.dictionary-card').evaluateAll((elements) => elements.map((element) => {
      const channel = (value) => {
        const c = Number(value) / 255;
        return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
      };
      const lum = (color) => {
        const [r, g, b] = color.match(/[\d.]+/g).slice(0, 3).map(channel);
        return r * 0.2126 + g * 0.7152 + b * 0.0722;
      };
      const text = element.querySelector('h3') || element;
      const a = lum(getComputedStyle(text).color);
      const b = lum(getComputedStyle(element).backgroundColor);
      return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }));
    for (const ratio of ratios) expect(ratio).toBeGreaterThanOrEqual(4.5);
  }
});

test('the phone layout folds the menu, shows rows as cards and never scrolls sideways', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/admin/dictionaries?tab=phones');
  await expect(page.getByText('+79990000701', { exact: true })).toBeVisible();
  const toggle = page.locator('.dictionary-menu-toggle');
  await expect(toggle).toBeVisible();
  await expect(page.locator('.dictionary-menu-item').first()).toBeHidden();
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  await page.getByRole('navigation', { name: 'Справочники' }).getByRole('button', { name: /Категории/ }).click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(page.getByRole('heading', { name: 'Категории', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await toggle.click();
  await page.getByRole('navigation', { name: 'Справочники' }).getByRole('button', { name: /Телефоны/ }).click();
  await expect(page.locator('.phone-table thead')).toHaveCSS('position', 'absolute');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test('the «Боты» link of the cities page opens the accounts of that city', async ({ page, fixture }) => {
  const keywords = [];
  fixture.api.set('GET /api/admin/bots', (route) => {
    const keyword = new URL(route.request().url()).searchParams.get('keyword');
    keywords.push(keyword);
    return route.fulfill({ json: { bots: [], workers: [], statuses: [], cities: [{ id: 5, title: 'Тестовый город' }],
      total: 0, page: 0, size: 50, totalPages: 1 } });
  });
  fixture.api.set('GET /api/admin/bots/unblocked-count', (route) => route.fulfill({ json: { cityId: 325, unblockedAccounts: 0 } }));
  await page.goto('/admin/dictionaries?cityId=5&section=bots');
  await expect(page.getByRole('navigation', { name: 'Справочники' }).getByRole('button', { name: /Аккаунты/ }))
    .toHaveAttribute('aria-current', 'page');
  await expect.poll(() => keywords).toEqual([null, 'Тестовый город']);
  await expect(page.locator('.list-search input')).toHaveValue('Тестовый город');
});
