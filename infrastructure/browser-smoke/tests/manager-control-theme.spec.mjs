import { readFile } from 'node:fs/promises';
import { test, expect } from './fixtures.mjs';

const { responses } = JSON.parse(await readFile(new URL('../../../contracts/fixtures/client-api-current.json', import.meta.url), 'utf8'));

test('manager audit, actions and specialist reply statuses follow the selected theme', async ({ page, fixture }) => {
  fixture.roles = ['ADMIN'];
  const manager = { ...responses.ManagerControlManagerResponseOutput, managerId: 2,
    workerExplanationStats: [responses.ManagerControlWorkerExplanationStatsResponseOutput] };
  const review = { ...responses.ManagerReportReviewResponseOutput, summaryDate: '2026-11-20', managerId: 2,
    managerName: 'Тестовый менеджер', status: 'COMPLETED', issues: [], events: [], answerAttempts: [] };
  fixture.api.set('POST /api/manager-activity', route => route.fulfill({ status: 204 }));
  fixture.api.set('GET /api/admin/manager-control/today', route => route.fulfill({
    json: { ...responses.ManagerControlSummaryResponseOutput, managers: [manager] }
  }));
  fixture.api.set('GET /api/admin/manager-daily-summary/review-sessions', route => route.fulfill({ json: [review] }));
  await page.goto('/admin/manager-control');
  const item = page.locator('.report-review-item');
  await expect(item).toContainText(review.managerName);
  await item.locator('summary').click();
  await expect(item.locator('.report-review-details')).toBeVisible();
  const surfaces = page.locator('.report-review-item, .report-review-dates input, .report-review-recent button:not(.active), .worker-response-legend > span');
  await expect(page.locator('.worker-response-legend > span')).toHaveCount(3);
  for (const dark of [true, false]) {
    await page.getByRole('button', { name: dark ? 'Темная тема' : 'Светлая тема', exact: true }).first().click();
    await expect.poll(() => surfaces.evaluateAll((elements, dark) => elements.every(element => {
      const rgb = getComputedStyle(element).backgroundColor.match(/[\d.]+/g).slice(0, 3).map(Number);
      return dark ? rgb.every(channel => channel < 128) : rgb.every(channel => channel > 128);
    }), dark)).toBe(true);
    if (dark) {
      await expect(page.locator('.report-review-dates input')).toHaveCSS('color-scheme', 'dark');
      for (const button of await page.locator('.summary-preview-button').all()) {
        await expect(button).toHaveCSS('background-image', 'none');
      }
      const ratios = await page.locator('.worker-response-legend > span, .report-review-item').evaluateAll(elements => {
        const luminance = color => {
          const [r, g, b] = color.match(/[\d.]+/g).slice(0, 3).map(channel => {
            const c = Number(channel) / 255;
            return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
          });
          return r * 0.2126 + g * 0.7152 + b * 0.0722;
        };
        return elements.map(element => {
          const text = element.querySelector('summary strong') || element;
          return (luminance(getComputedStyle(text).color) + 0.05) /
            (luminance(getComputedStyle(element).backgroundColor) + 0.05);
        });
      });
      for (const ratio of ratios) expect(ratio).toBeGreaterThanOrEqual(4.5);
    }
  }
  await item.locator('summary').click();
  await expect(item.locator('.report-review-details')).toBeHidden();
});
