import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { loadTsModule } from './load-ts-module.mjs';

const { sanitizeDiagnosticRoute, maskDiagnosticPathSegments } = loadTsModule('src/app/core/mobile-diagnostics-route.ts');
const {
  oidcCallbackErrorMessage,
  oidcCallbackErrorCode,
  OIDC_CALLBACK_GENERIC_ERROR
} = loadTsModule('src/app/core/auth-callback-errors.ts');
const auth = readFileSync(new URL('../src/app/core/auth.service.ts', import.meta.url), 'utf8');
const diagnostics = readFileSync(new URL('../src/app/core/mobile-auth-diagnostics.service.ts', import.meta.url), 'utf8');

test('diagnostic routes never contain payment capability tokens', () => {
  const token = ['Zk3_x9Qm', 'Lr7-Tq2p', 'Wn8vYb4c', 'Hd6sJe1a', 'Uf5gKo0iNt'].join(''); // fake, assembled for scanners
  assert.equal(sanitizeDiagnosticRoute(`/pay/${token}`), '/pay/:token');
  assert.equal(sanitizeDiagnosticRoute(`/pay/${token}?status=ok#frag`), '/pay/:token');
  assert.equal(sanitizeDiagnosticRoute(`/pay/group/${token}`), '/pay/group/:token');
  assert.equal(sanitizeDiagnosticRoute('/pay/short'), '/pay/:token');
  assert.equal(sanitizeDiagnosticRoute(`/pay/${token};x=1`), '/pay/:token');
  assert.equal(sanitizeDiagnosticRoute('/pay'), '/pay');
  assert.equal(sanitizeDiagnosticRoute('/pay/success'), '/pay/success');
  assert.equal(sanitizeDiagnosticRoute('/pay/fail?orderId=5'), '/pay/fail');
});

test('diagnostic routes mask review links, ids and other opaque segments', () => {
  assert.equal(sanitizeDiagnosticRoute('/9b2f4c1e-7a3d-4e5f-8a9b-0c1d2e3f4a5b'), '/:id');
  assert.equal(
    sanitizeDiagnosticRoute('/review/editReviews/9b2f4c1e-7a3d-4e5f-8a9b-0c1d2e3f4a5b'),
    '/review/editReviews/:id'
  );
  assert.equal(sanitizeDiagnosticRoute('/review/c#rc1_secret'), '/review/c');
  assert.equal(sanitizeDiagnosticRoute('/tabs/orders/12/345'), '/tabs/orders/:id/:id');
  assert.equal(sanitizeDiagnosticRoute('/tabs/unknown/AbCdEfGhIjKlMnOpQrStUv12'), '/tabs/unknown/:token');
  assert.equal(sanitizeDiagnosticRoute('/tabs/mobile-update-admin'), '/tabs/mobile-update-admin');
  assert.equal(sanitizeDiagnosticRoute('/tabs/home/companies'), '/tabs/home/companies');
  assert.equal(sanitizeDiagnosticRoute(''), '/');
});

test('HTTP breadcrumbs mask public payment tokens in API paths', () => {
  const token = ['Zk3_x9Qm', 'Lr7-Tq2p', 'Wn8vYb4c', 'Hd6sJe1a', 'Uf5gKo0iNt'].join(''); // fake, assembled for scanners
  assert.equal(maskDiagnosticPathSegments(`/api/payments/public/${token}/sbp/banks`), '/api/payments/public/:token/sbp/banks');
  assert.equal(maskDiagnosticPathSegments(`/api/payments/public/group/${token}/init`), '/api/payments/public/group/:token/init');
  assert.equal(maskDiagnosticPathSegments('/api/admin/phones/7/device-tokens/abc'), '/api/admin/phones/:id/device-tokens/:token');
  assert.equal(maskDiagnosticPathSegments('/api/manager/orders/15/details'), '/api/manager/orders/:id/details');
});

test('the diagnostics service and the login breadcrumb use the capability-aware sanitizer', () => {
  assert.match(diagnostics, /return sanitizeDiagnosticRoute\(route\)/);
  assert.match(auth, /'auth\.login_started', \{ target: sanitizeDiagnosticRoute\(targetUrl\) \}/);
});

test('OAuth error callbacks show fixed messages and never the provided description', () => {
  assert.match(oidcCallbackErrorMessage('access_denied'), /Вход отменён/);
  assert.match(oidcCallbackErrorMessage('temporarily_unavailable'), /временно недоступен/);
  assert.equal(oidcCallbackErrorMessage('Позвоните по номеру +7 000'), OIDC_CALLBACK_GENERIC_ERROR);
  assert.equal(oidcCallbackErrorMessage(null), OIDC_CALLBACK_GENERIC_ERROR);
  assert.equal(oidcCallbackErrorCode('access_denied'), 'access_denied');
  assert.equal(oidcCallbackErrorCode('invalid_request'), 'invalid_request');
  assert.equal(oidcCallbackErrorCode('free text from a link'), 'other');
  assert.doesNotMatch(auth, /error_description/);
});

test('native callbacks are processed only for the pending login state', () => {
  const start = auth.indexOf('async completeLoginFromCallback(');
  const end = auth.indexOf("void this.recordAuthDiagnostic('auth.code_exchange_started')", start);
  const callback = auth.slice(start, end);
  const ignore = callback.indexOf('if (!stateMatched && this.isNative)');
  assert.ok(ignore > callback.indexOf('readPendingLogin()'));
  assert.ok(ignore < callback.indexOf('clearPendingLogin()'));
  assert.ok(ignore < callback.indexOf('this.error.set('));
  assert.match(callback, /if \(error && stateMatched\)/);
});

test('logout revokes the offline refresh token at Keycloak before local cleanup', () => {
  const start = auth.indexOf('async logoutFrom(');
  const logout = auth.slice(start, auth.indexOf('private async revokeRefreshTokenBestEffort(', start));
  const revokeCall = logout.indexOf('this.revokeRefreshTokenBestEffort(refreshToken)');
  assert.ok(revokeCall > 0);
  assert.ok(revokeCall < logout.indexOf("this.clearSession('anonymous')"));

  const revokeStart = auth.indexOf('private async revokeRefreshTokenBestEffort(');
  const revoke = auth.slice(revokeStart, auth.indexOf('private async revokeCurrentPushTokenBestEffort', revokeStart));
  assert.match(revoke, /\/protocol\/openid-connect\/revoke/);
  assert.match(revoke, /token_type_hint: 'refresh_token'/);
  assert.match(revoke, /client_id: mobileEnvironment\.keycloak\.clientId/);
  assert.match(revoke, /Promise\.race\(\[request, timeout\]\)/);
  assert.match(revoke, /catch \(error: unknown\)/);
});
