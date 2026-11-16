import { test, expect } from './fixtures.mjs';

test('an open manager binding card switches from linked to QR and back', async ({ page, fixture }) => {
  fixture.roles = ['MANAGER'];
  let linked = true;
  const testImage = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jF1kAAAAASUVORK5CYII=';
  fixture.api.set('POST /api/manager-activity', route => route.fulfill({ status: 204 }));
  fixture.api.set('POST /api/manager-report-review/check-in', route => route.fulfill({ json: {} }));
  fixture.api.set('GET /api/cabinet/whatsapp', route => route.fulfill({ json: {
    clientId: 'whatsapp_fixture', configured: true, authenticated: linked, ready: linked,
    state: linked ? 'ready' : 'qr', hasQr: !linked, qrDataUrl: linked ? null : testImage
  } }));
  await page.clock.install();
  await page.goto('/cabinet/whatsapp');
  const check = page.locator('.whatsapp-bind .empty-panel.success');
  const image = page.locator('.whatsapp-bind .qr-panel img');
  await expect(check).toBeVisible();
  linked = false;
  await page.clock.fastForward(30_001);
  await expect(image).toBeVisible();
  await expect(image).toHaveAttribute('src', testImage);
  await expect(check).toHaveCount(0);
  linked = true;
  await page.clock.fastForward(6_001);
  await expect(check).toBeVisible();
  await expect(image).toHaveCount(0);
});
