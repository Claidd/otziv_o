import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  workers: 2,
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'test-results/junit.xml' }], ['./evidence-reporter.mjs']],
  use: {
    browserName: 'chromium',
    serviceWorkers: 'block',
    launchOptions: { chromiumSandbox: true },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off'
  },
  projects: [
    { name: 'web', testIgnore: '**/mobile-journal.spec.mjs', use: { baseURL: 'http://127.0.0.1:43171', viewport: { width: 1440, height: 1100 } } },
    { name: 'mobile-web', testMatch: ['**/public-payment.spec.mjs', '**/mobile-journal.spec.mjs'], use: { baseURL: 'http://127.0.0.1:43172', viewport: { width: 412, height: 915 }, isMobile: true, hasTouch: true } }
  ],
  webServer: { command: 'node server.mjs', url: 'http://127.0.0.1:43171', reuseExistingServer: false, timeout: 20_000 }
});
