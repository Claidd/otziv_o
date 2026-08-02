import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { loadTsModule } from './load-ts-module.mjs';

const { isPendingLogin, isStoredTokens } = loadTsModule('src/app/core/auth-storage-validation.ts');

test('accepts a complete stored token envelope', () => {
  assert.equal(isStoredTokens({
    accessToken: 'header.payload.signature',
    tokenType: 'Bearer',
    expiresAt: 2_000_000_000_000,
    refreshToken: 'refresh',
    refreshExpiresAt: 2_000_000_100_000
  }), true);
});

test('rejects malformed token envelopes before auth initialization', () => {
  for (const value of [null, {}, [], { accessToken: '' }, {
    accessToken: 'token', tokenType: 'Bearer', expiresAt: Number.NaN
  }, {
    accessToken: 'token', tokenType: 'Bearer', expiresAt: '2000000000000'
  }]) {
    assert.equal(isStoredTokens(value), false);
  }
});

test('accepts only internal pending-login return paths', () => {
  const base = {
    state: '0123456789abcdef',
    codeVerifier: '0123456789abcdef0123456789abcdef',
    redirectUri: 'otziv://auth/callback'
  };
  assert.equal(isPendingLogin({ ...base, targetUrl: '/tabs/home' }), true);
  assert.equal(isPendingLogin({ ...base, targetUrl: '//evil.example' }), false);
  assert.equal(isPendingLogin({ ...base, targetUrl: 'https://evil.example' }), false);
});

test('auth initialization rejects a token that has no subject claim', () => {
  const auth = readFileSync(new URL('../src/app/core/auth.service.ts', import.meta.url), 'utf8');
  assert.match(auth, /if \(!subject\) \{\s*throw new Error\('Access token has no subject claim'\)/);
});
