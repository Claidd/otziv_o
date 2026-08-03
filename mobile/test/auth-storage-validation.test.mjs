import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { loadTsModule } from './load-ts-module.mjs';

const { isPendingLogin, isStoredTokens } = loadTsModule('src/app/core/auth-storage-validation.ts');
const {
  TOKEN_REVOCATION_MARKER,
  isTokenRevocationMarked,
  revokeStoredTokens
} = loadTsModule('src/app/core/auth-token-revocation.ts');

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

test('web auth keeps tokens in memory and PKCE state in session storage only', () => {
  const storage = readFileSync(new URL('../src/app/core/mobile-auth-storage.service.ts', import.meta.url), 'utf8');

  assert.match(storage, /async readTokens[\s\S]*?if \(!this\.isNative\) \{\s*await this\.removeWebValue\(TOKENS_KEY\);\s*return null;/);
  assert.match(storage, /async writeTokens[\s\S]*?if \(!this\.isNative\) \{\s*await this\.removeWebValue\(TOKENS_KEY\);\s*return;/);
  assert.match(storage, /this\.sessionStorage\(\)\.setItem\(key, JSON\.stringify\(value\)\)/);
  assert.doesNotMatch(storage, /localStorage/);
});

test('native auth fails closed without SecureStorage and only uses Preferences for migration cleanup', () => {
  const storage = readFileSync(new URL('../src/app/core/mobile-auth-storage.service.ts', import.meta.url), 'utf8');

  assert.match(storage, /private requireNativeSecureStorage\(\): void \{[\s\S]*?if \(!this\.secureStorageAvailable\) \{[\s\S]*?throw new Error/);
  assert.match(storage, /if \(this\.isNative\) \{\s*this\.requireNativeSecureStorage\(\);\s*await SecureStorage\.set/);
  assert.match(storage, /const normalized = this\.normalizeStoredValue<T>\(secureValue\);\s*if \(normalized\) \{[\s\S]*?await Preferences\.remove\(\{ key \}\);\s*return normalized;/);
  assert.doesNotMatch(storage, /SecureStorage\.get\(key\)\.catch/);
  assert.doesNotMatch(storage, /SecureStorage\.remove\(key\)\.catch/);
  assert.match(storage, /Preferences\.set\(\{\s*key: TOKENS_REVOCATION_KEY,\s*value: TOKEN_REVOCATION_MARKER/);
  assert.doesNotMatch(storage, /Preferences\.set\(\{\s*key: TOKENS_KEY/);
});

test('failed secure removal cannot resurrect refresh tokens after restart', async () => {
  let marker = null;
  let secureValue = { accessToken: 'old-access', refreshToken: 'old-refresh' };
  let legacyValue = 'old-plaintext-copy';

  await revokeStoredTokens({
    persistMarker: async () => {
      marker = TOKEN_REVOCATION_MARKER;
    },
    overwriteSecureToken: async () => {
      secureValue = { revoked: true };
    },
    removeLegacyToken: async () => {
      legacyValue = null;
    },
    removeSecureToken: async () => {
      throw new Error('simulated keystore remove failure');
    },
    clearMarker: async () => {
      marker = null;
    }
  });

  assert.equal(isTokenRevocationMarked(marker), true);
  assert.deepEqual(secureValue, { revoked: true });
  assert.equal(legacyValue, null);
  const restoredAfterRestart = isTokenRevocationMarked(marker) ? null : secureValue;
  assert.equal(restoredAfterRestart, null);

  await revokeStoredTokens({
    persistMarker: async () => {
      marker = TOKEN_REVOCATION_MARKER;
    },
    overwriteSecureToken: async () => {
      secureValue = { revoked: true };
    },
    removeLegacyToken: async () => {
      legacyValue = null;
    },
    removeSecureToken: async () => {
      secureValue = null;
    },
    clearMarker: async () => {
      marker = null;
    }
  });

  assert.equal(marker, null);
  assert.equal(secureValue, null);
});

test('token revocation fails when neither durable barrier can be stored', async () => {
  await assert.rejects(() => revokeStoredTokens({
    persistMarker: async () => {
      throw new Error('preferences unavailable');
    },
    overwriteSecureToken: async () => {
      throw new Error('secure storage unavailable');
    },
    removeLegacyToken: async () => {},
    removeSecureToken: async () => {},
    clearMarker: async () => {}
  }), /Не удалось надёжно отозвать/);
});

test('offline refresh tokens are requested only by the native app', () => {
  const auth = readFileSync(new URL('../src/app/core/auth.service.ts', import.meta.url), 'utf8');

  assert.match(auth, /authUrl\.searchParams\.set\('scope', this\.isNative\s*\? 'openid profile email offline_access'\s*: 'openid profile email'\)/);
});

test('Android backup and device transfer exclude all app data', () => {
  const manifest = readFileSync(new URL('../android/app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
  const legacyRules = readFileSync(new URL('../android/app/src/main/res/xml/backup_rules.xml', import.meta.url), 'utf8');
  const extractionRules = readFileSync(new URL('../android/app/src/main/res/xml/data_extraction_rules.xml', import.meta.url), 'utf8');

  assert.match(manifest, /android:allowBackup="false"/);
  assert.match(manifest, /android:fullBackupContent="@xml\/backup_rules"/);
  assert.match(manifest, /android:dataExtractionRules="@xml\/data_extraction_rules"/);
  for (const domain of ['root', 'file', 'database', 'sharedpref', 'external']) {
    assert.match(legacyRules, new RegExp(`<exclude domain="${domain}" path="\\." \\/>`));
    assert.match(extractionRules, new RegExp(`<exclude domain="${domain}" path="\\." \\/>`));
  }
  assert.match(extractionRules, /<device-transfer>[\s\S]*?<exclude domain="device_sharedpref" path="\." \/>/);
});
