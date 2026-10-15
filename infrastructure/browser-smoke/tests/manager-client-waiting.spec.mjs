import { test, expect, deferred } from './fixtures.mjs';
import { readFile } from 'node:fs/promises';

const contract = JSON.parse(await readFile(new URL('../../../contracts/fixtures/client-api-current.json', import.meta.url), 'utf8'));
const example = contract.responses.ManagerBoardResponseOutput;

test.use({ viewport: { width: 393, height: 820 }, isMobile: true, hasTouch: true });

test('common invoice member client action shows pending state after carousel scroll', async ({ page, fixture }) => {
  fixture.roles = ['MANAGER'];
  let waiting = false;
  const response = deferred();
  const order = (id) => ({ ...example.orders.content[0], id, companyId: 2711, companyTitle: 'Марчук',
    filialTitle: '7-й микрорайон, 36/1', status: 'Новый', sum: 1600, companyTelephone: '7-904-154-55-64',
    categoryTitle: 'Компании', subCategoryTitle: 'Производственная компания', commonInvoice: false,
    commonInvoiceId: 323, waitingForClient: id === 27820 && waiting });
  fixture.api.set('GET /api/manager/board', async (route) => route.fulfill({ json: {
    ...example, section: 'orders', status: 'Все',
    companies: { ...example.companies, content: [], totalElements: 0, totalPages: 1, first: true, last: true },
    orders: { ...example.orders, content: [order(27819), order(27820)], number: 3, size: 10, totalElements: 60, totalPages: 6, first: false, last: false },
    companyStatuses: ['Все'], orderStatuses: ['Все', 'Новый'], metrics: [], promoTexts: []
  } }));
  fixture.api.set('POST /api/worker/orders/27820/client-waiting', async (route) => {
    expect(route.request().postDataJSON()).toEqual({ waitingForClient: true });
    await response.promise;
    waiting = true;
    return route.fulfill({ status: 204 });
  });
  fixture.api.set('POST /api/manager-activity', async (route) => route.fulfill({ status: 204 }));
  fixture.api.set('POST /api/manager-report-review/check-in', async (route) => route.fulfill({ json: {} }));

  await page.goto('/orders');
  await expect(page.locator('.manager-card.order-card')).toHaveCount(2);
  const cards = page.locator('.manager-cards.order-cards');
  await cards.evaluate((element) => element.scrollTo({ left: element.scrollWidth, behavior: 'instant' }));
  const button = page.locator('.manager-card.order-card').nth(1).locator('.client-waiting-action');
  await expect(button).toBeVisible();
  const bounds = await button.boundingBox();
  const x = bounds.x + bounds.width / 2;
  const y = bounds.y + bounds.height / 2;
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + 8, y: y + 16 }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await expect(button).toHaveText('сохраняем...');
  await expect(button).toBeDisabled();
  await expect.poll(() => fixture.requests.filter((request) => request === 'POST /api/worker/orders/27820/client-waiting').length).toBe(1);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await page.waitForTimeout(650);
  expect(await button.evaluate((element) => getComputedStyle(element).backgroundColor)).not.toBe('rgb(196, 97, 220)');
  expect(fixture.requests.filter((request) => request === 'POST /api/worker/orders/27820/client-waiting')).toHaveLength(1);
  response.resolve();
  await expect(button).toHaveText('ждет клиента');
});
