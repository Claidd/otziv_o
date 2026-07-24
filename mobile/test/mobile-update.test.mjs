import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTsModule } from './load-ts-module.mjs';

const {
  formatUpdateSize,
  isUpdateAvailable,
  isUpdateRequired,
  resolveUpdateUrl
} = loadTsModule('src/app/core/mobile-update.helpers.ts');

const release = {
  enabled: true,
  versionCode: 54,
  versionName: '1.0.54',
  minSupportedVersionCode: 50,
  required: false,
  notes: '',
  fileSize: 11 * 1024 * 1024,
  sha256: 'A'.repeat(64),
  publishedAt: '',
  downloadUrl: '/api/mobile-update/download'
};

test('shows only a newer enabled release', () => {
  assert.equal(isUpdateAvailable(release, 53), true);
  assert.equal(isUpdateAvailable(release, 54), false);
  assert.equal(isUpdateAvailable({ ...release, enabled: false }, 53), false);
});

test('requires update by release flag or minimum supported version', () => {
  assert.equal(isUpdateRequired(release, 49), true);
  assert.equal(isUpdateRequired(release, 50), false);
  assert.equal(isUpdateRequired({ ...release, required: true }, 53), true);
});

test('resolves server download URL and formats APK size', () => {
  assert.equal(resolveUpdateUrl(release.downloadUrl, 'https://o-ogo.ru/'), 'https://o-ogo.ru/api/mobile-update/download');
  assert.equal(resolveUpdateUrl('https://cdn.example/app.apk', 'https://o-ogo.ru'), 'https://cdn.example/app.apk');
  assert.equal(formatUpdateSize(release.fileSize), '11,0 МБ');
});
