import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

const auth = readFileSync(new URL('../src/app/core/auth.service.ts', import.meta.url), 'utf8');

test('transient refresh failures keep the native session for automatic retry', () => {
  const methodStart = auth.indexOf('async refreshTokens()');
  const methodEnd = auth.indexOf('async logout()', methodStart);
  const method = auth.slice(methodStart, methodEnd);

  assert.match(method, /if \(!this\.isTerminalRefreshError\(error\)\) \{/);
  assert.match(method, /this\.status\.set\('authenticated'\)/);
  assert.match(method, /this\.scheduleRefresh\(\)/);
  assert.match(method, /return true;/);

  const transientStart = method.indexOf('if (!this.isTerminalRefreshError(error))');
  const terminalStart = method.indexOf('await this.clearSession', transientStart);
  const transientBranch = method.slice(transientStart, terminalStart);
  assert.doesNotMatch(transientBranch, /clearSession/);
});

test('terminal Keycloak refresh responses still clear the local session', () => {
  assert.match(auth, /class TokenEndpointHttpError extends Error/);
  assert.match(auth, /private isTerminalRefreshError\(error: unknown\): boolean \{/);
  assert.match(auth, /error\.statusCode === 400 \|\| error\.statusCode === 401 \|\| error\.statusCode === 403/);
  assert.match(auth, /await this\.clearSession\('anonymous'\)/);
});
