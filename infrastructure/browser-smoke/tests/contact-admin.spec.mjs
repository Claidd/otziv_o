import { test, expect } from './fixtures.mjs';

test('stopped-company contact review stays readable in both themes', async ({ page, fixture }) => {
  fixture.roles = ['ADMIN'];
  fixture.api.set('POST /api/manager-activity', route => route.fulfill({ status: 204 }));
  fixture.api.set('GET /api/admin/client-messages/contacts', route => route.fulfill({ json: [{
    companyId: 701, title: 'Недоступный тестовый чат', companyStatus: 'На стопе', platform: 'TELEGRAM',
    description: 'Telegram не находит чат для текущего бота', checkStatus: 'UNAVAILABLE', excluded: true
  }] }));
  await page.goto('/admin/company-contacts');
  const panel = page.locator('app-inactive-company-contacts');
  await expect(panel.locator('article')).toContainText('Рассылка приостановлена');
  for (const dark of [true, false]) {
    await page.getByRole('button', { name: dark ? 'Темная тема' : 'Светлая тема', exact: true }).first().click();
    await expect.poll(() => page.locator('body').evaluate(el => el.classList.contains('otziv-dark-theme'))).toBe(dark);
    const contrast = await panel.evaluate(el => {
      const luminance = color => {
        const rgb = color.match(/[\d.]+/g).slice(0, 3).map(Number).map(c => {
          const x = c / 255;
          return x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
        });
        return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
      };
      const ratio = (a, b) => (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
      const body = getComputedStyle(document.body);
      const background = luminance(body.backgroundColor);
      const intro = getComputedStyle(el.querySelector('.contacts > p'));
      const card = getComputedStyle(el.querySelector('article'));
      const input = getComputedStyle(el.querySelector('input'));
      return {
        intro: ratio(luminance(intro.color), background),
        card: ratio(luminance(card.color), luminance(card.backgroundColor)),
        input: ratio(luminance(input.color), luminance(input.backgroundColor)),
        surface: luminance(card.backgroundColor)
      };
    });
    expect(contrast.intro).toBeGreaterThanOrEqual(4.5);
    expect(contrast.card).toBeGreaterThanOrEqual(4.5);
    expect(contrast.input).toBeGreaterThanOrEqual(4.5);
    expect(contrast.surface < 0.2).toBe(dark);
  }
});
