import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (relativeUrl) => readFileSync(new URL(relativeUrl, import.meta.url), 'utf8');

const auth = read('../src/app/core/auth.service.ts');
const push = read('../src/app/core/mobile-push.service.ts');
const pushApi = read('../src/app/core/mobile-push-api.service.ts');
const interceptor = read('../src/app/core/auth.interceptor.ts');

test('logout attempts current push-token revoke before clearing authentication', () => {
  const start = auth.indexOf('async logout(): Promise<void>');
  const end = auth.indexOf('async handleUnauthorized', start);
  const logout = auth.slice(start, end);

  assert.ok(start >= 0 && end > start);
  assert.ok(logout.indexOf('revokeCurrentPushTokenBestEffort') >= 0);
  assert.ok(logout.indexOf('revokeCurrentPushTokenBestEffort') < logout.indexOf("clearSession('anonymous')"));
});

test('push revoke is bounded, best-effort and allows same-token registration after logout', () => {
  assert.match(pushApi, /\/api\/mobile\/push-token\/revoke/);
  assert.match(push, /timeout\(\{ first: 2_500 \}\)/);
  assert.match(push, /catch \{[\s\S]*older backend/);
  assert.match(push, /finally \{[\s\S]*this\.resetRegistrationState\(\)/);
  assert.match(push, /resetRegistrationState\(\): void \{[\s\S]*this\.backendToken = null/);
  assert.match(push, /resetRegistrationState\(\): void \{[\s\S]*this\.token\.set\(null\)/);
});

test('logout revoke never starts refresh and late refresh cannot restore a cleared session', () => {
  const revokeStart = interceptor.indexOf('if (isBestEffortLogoutRevoke)');
  const reviewStart = interceptor.indexOf('if (isOptionalReviewApi)', revokeStart);
  const revokeBranch = interceptor.slice(revokeStart, reviewStart);

  assert.match(revokeBranch, /getOptionalAccessToken\(0\)/);
  assert.doesNotMatch(revokeBranch, /getAccessToken|refreshTokens|handleUnauthorized/);
  assert.match(auth, /const refreshGeneration = this\.sessionGeneration/);
  assert.match(auth, /refreshGeneration !== this\.sessionGeneration/);
  assert.match(auth, /async logout\(\): Promise<void> \{[\s\S]*this\.sessionGeneration \+= 1/);
});

test('all local session clears reset push registration without revocation or refresh', () => {
  const clearStart = auth.indexOf('private clearState(status: AuthStatus): void');
  const clearEnd = auth.indexOf('private async clearSession', clearStart);
  const clearState = auth.slice(clearStart, clearEnd);
  const resetStart = auth.indexOf('private resetPushRegistrationState(): void');
  const resetEnd = auth.indexOf('private async clearSession', resetStart);
  const reset = auth.slice(resetStart, resetEnd);

  assert.ok(clearStart >= 0 && clearEnd > clearStart);
  assert.match(clearState, /this\.resetPushRegistrationState\(\)/);
  assert.ok(resetStart >= 0 && resetEnd > resetStart);
  assert.match(reset, /resetRegistrationState\(\)/);
  assert.doesNotMatch(reset, /refreshTokens|revokeCurrentToken|register\(/);
});

test('accepting tokens for another subject resets old push registration state', () => {
  const acceptStart = auth.indexOf('private async acceptTokens');
  const acceptEnd = auth.indexOf('private async requestToken', acceptStart);
  const accept = auth.slice(acceptStart, acceptEnd);

  assert.ok(acceptStart >= 0 && acceptEnd > acceptStart);
  assert.match(accept, /const previousSubject = this\.user\(\)\?\.subject/);
  assert.match(accept, /previousSubject !== undefined && previousSubject !== this\.user\(\)\?\.subject/);
  assert.match(accept, /this\.resetPushRegistrationState\(\)/);
});
