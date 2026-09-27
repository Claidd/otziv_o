import { test, expect } from './fixtures.mjs';

test('released admin shell includes campaigns, test audience and reminder clearing', async ({ page, fixture }, info) => {
  fixture.roles = ['ADMIN'];
  fixture.api.set('POST /api/manager-activity', route => route.fulfill({ status: 204 }));
  fixture.api.set('GET /api/admin/client-offers', route => route.fulfill({ json: { liveEnabled: false, campaigns: [] } }));
  fixture.api.set('GET /api/personal-reminders', route => route.fulfill({ json: [{
    id: 90001, title: 'Проверка выпуска', text: 'Только локальный тест', reminderMode: 'datetime',
    remindAt: '2020-01-01T00:00:00Z', timerMinutes: null, createdAt: '2020-01-01T00:00:00Z',
    updatedAt: '2020-01-01T00:00:00Z', completedAt: null
  }] }));
  await page.goto('/admin/client-offers');
  await expect(page.getByRole('heading', { name: 'Рассылки клиентам' })).toBeVisible();
  await expect(page.locator('a[href="/admin/client-offers"]').filter({ hasText: 'Рассылка' }).first()).toBeVisible();
  await page.getByLabel('Кому отправлять').selectOption({ label: 'Тестовая — только администраторам и владельцам' });
  await expect(page.getByText('Клиентам ничего не отправится.', { exact: false })).toBeVisible();
  const clear = page.getByRole('button', { name: 'Удалить все напоминания', exact: true }).first();
  await expect(clear).toBeVisible();
  page.once('dialog', dialog => dialog.dismiss());
  await clear.click();
  await expect(page.getByText('Проверка выпуска', { exact: true }).first()).toBeVisible();
  expect(fixture.requests.filter(request => request.startsWith('DELETE '))).toEqual([]);
  await page.screenshot({ path: info.outputPath('unified-admin-release.png'), fullPage: true });
});
