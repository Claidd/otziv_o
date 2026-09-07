import { readFile } from 'node:fs/promises';
import { test as base, expect } from '@playwright/test';

const contract = JSON.parse(await readFile(new URL('../../../contracts/fixtures/public-payment-current.json', import.meta.url), 'utf8'));
const compiled = JSON.parse(await readFile(new URL('../../../contracts/fixtures/client-api-current.json', import.meta.url), 'utf8'));

// Keep delayed successful callbacks valid under the runtime contract. A malformed
// fake response would be rejected before the lifecycle guard and prove nothing.
export function paymentInitResponse(overrides = {}) {
  return { ...compiled.responses.PublicPaymentInitResponseOutput, paymentId: 'fixture-init',
    status: 'NEW', method: 'BANK_FORM', qrPayload: null, qrImage: null, ...overrides };
}

export const test = base.extend({
  fixture: async ({ context, baseURL, browser }, use, info) => {
    const origin = new URL(baseURL).origin;
    const state = {
      payment: { ...contract, token: 'fixture-payment', companyTitle: 'Браузерная проверка',
        expiresAt: '2099-09-08T12:30:00', paymentPageMode: 'BANK_ONLY',
        sbpBankSelectionSupported: false, tpayEnabled: false },
      reads: 0, writes: [], blocked: [], unexpectedWrites: [], requests: [], roles: null, nonce: '',
      api: new Map(),
      respondToInit: async (route) => route.fulfill({ status: 503, json: { message: 'Результат операции неизвестен. Проверьте статус.' } })
    };
    await context.routeWebSocket('**/*', (socket) => socket.close());
    await context.route('**/*', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      state.requests.push(`${request.method()} ${url.pathname}`);
      if (url.origin !== origin) {
        state.blocked.push({ url: url.href, type: request.resourceType() });
        return route.abort('blockedbyclient');
      }
      if (url.pathname.endsWith('/3p-cookies/step1.html')) {
        return route.fulfill({ contentType: 'text/html', body: '<script>parent.postMessage("supported",location.origin)</script>' });
      }
      if (url.pathname.endsWith('/protocol/openid-connect/auth')) {
        if (url.searchParams.get('prompt') === 'none') {
          const target = new URL(url.searchParams.get('redirect_uri'));
          state.nonce = url.searchParams.get('nonce');
          target.hash = new URLSearchParams({ ...(state.roles ? { code: 'fixture-code', session_state: 'fixture-session' } : { error: 'login_required' }),
            state: url.searchParams.get('state'), iss: `${origin}/keycloak/realms/otziv` }).toString();
          return route.fulfill({ status: 302, headers: { location: target.href } });
        }
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: '<h1>Local sign-in fixture</h1>' });
      }
      if (url.pathname.endsWith('/protocol/openid-connect/token') && state.roles) {
        const now = Math.floor(Date.now() / 1000);
        const claims = { sub: 'browser-fixture-user', sid: 'fixture-session', session_state: 'fixture-session',
          iss: `${origin}/keycloak/realms/otziv`, aud: 'otziv-frontend', azp: 'otziv-frontend',
          nonce: state.nonce, iat: now, exp: now + 3600, preferred_username: 'browser-fixture',
          realm_access: { roles: state.roles } };
        // Client-only OIDC fixture: never used as a real bearer token or sent to any server.
        const jwt = [Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url'),
          Buffer.from(JSON.stringify(claims)).toString('base64url'), 'fixture-signature'].join('.');
        return route.fulfill({ json: { access_token: jwt, id_token: jwt, refresh_token: jwt,
          token_type: 'Bearer', expires_in: 3600, refresh_expires_in: 3600, session_state: 'fixture-session' } });
      }
      if (url.pathname === '/keycloak/realms/otziv/account' && state.roles) {
        return route.fulfill({ json: { id: 'browser-fixture-user', username: 'browser-fixture', firstName: 'Browser', lastName: 'Fixture' } });
      }
      if (url.pathname === '/api/payments/public/fixture-payment' && request.method() === 'GET') {
        state.reads++;
        return route.fulfill({ json: state.payment });
      }
      if (url.pathname === '/api/payments/public/fixture-payment/init' && request.method() === 'POST') {
        state.writes.push(request.postDataJSON());
        return state.respondToInit(route);
      }
      const custom = state.api.get(`${request.method()} ${url.pathname}`);
      if (custom) return custom(route);
      if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/keycloak/')) {
        if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) state.unexpectedWrites.push(`${request.method()} ${url.pathname}`);
        return route.fulfill({ status: 501, json: { message: `Unconfigured local fixture: ${url.pathname}` } });
      }
      return route.continue();
    });
    await use(state);
    await info.attach('browser-network-boundary', { contentType: 'application/json', body: JSON.stringify({
      browser: browser.version(), allowedOrigin: origin, blocked: state.blocked, unexpectedWrites: state.unexpectedWrites
    }, null, 2) });
    expect(state.unexpectedWrites, 'No real or unexpected mutation endpoints may be called').toEqual([]);
  }
});
export { expect };

export function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

export async function openPayment(page) {
  await page.goto('/pay/fixture-payment');
  await expect(page.getByText('Браузерная проверка', { exact: true })).toBeVisible();
}

export async function consent(page) {
  await page.locator('input[name="email"]').fill('browser-fixture@example.test');
  const boxes = page.locator('form.pay-form input[type="checkbox"]');
  await expect(boxes).toHaveCount(3);
  for (const box of await boxes.all()) await box.check();
}

export function payButton(page) { return page.locator('form.pay-form button[type="submit"]'); }

export async function resume(page, fixture) {
  const reads = fixture.reads;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect.poll(() => fixture.reads).toBeGreaterThan(reads);
}
