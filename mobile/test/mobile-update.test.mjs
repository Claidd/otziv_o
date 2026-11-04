import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTsModule } from './load-ts-module.mjs';

const {
  formatUpdateSize,
  isDownloadedRelease,
  isUpdateAvailable,
  isUpdateRequired,
  MOBILE_UPDATE_RELEASE_PATH,
  mobileUpdateReleaseUrl,
  mobileUpdateResumeAction,
  resolveUpdateUrl,
  shouldAutoInstallOnStartup
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

test('new certificate checks an isolated static release channel', () => {
  assert.equal(MOBILE_UPDATE_RELEASE_PATH, '/android-new/release.json');
  assert.equal(mobileUpdateReleaseUrl('https://o-ogo.ru/'), 'https://o-ogo.ru/android-new/release.json');
  assert.equal(mobileUpdateReleaseUrl(''), '/android-new/release.json');
});

test('returning to the app reopens the installer only after the permission screen', () => {
  assert.equal(mobileUpdateResumeAction('permission'), 'recheck-permission');
  assert.equal(mobileUpdateResumeAction('installing'), 'installer-closed');
  assert.equal(mobileUpdateResumeAction('downloading'), 'poll-download');
  for (const state of ['idle', 'available', 'error']) {
    assert.equal(mobileUpdateResumeAction(state), 'check');
  }
});

test('reuses only a finished download of the same release', () => {
  assert.equal(isDownloadedRelease({ state: 'successful', versionCode: 54 }, release), true);
  assert.equal(isDownloadedRelease({ state: 'successful', versionCode: 53 }, release), false);
  assert.equal(isDownloadedRelease({ state: 'successful' }, release), false);
  assert.equal(isDownloadedRelease({ state: 'running', versionCode: 54 }, release), false);
  assert.equal(isDownloadedRelease({ state: 'successful', versionCode: 54 }, null), false);
});

test('a cold start opens the installer by itself only for a required update', () => {
  const downloaded = { state: 'successful', versionCode: 54 };
  assert.equal(shouldAutoInstallOnStartup(downloaded, release, 53), false);
  assert.equal(shouldAutoInstallOnStartup(downloaded, { ...release, required: true }, 53), true);
  assert.equal(shouldAutoInstallOnStartup(downloaded, release, 49), true);
  // Already installed: nothing to launch.
  assert.equal(shouldAutoInstallOnStartup(downloaded, { ...release, required: true }, 54), false);
  // No release information (server unavailable): never guess.
  assert.equal(shouldAutoInstallOnStartup(downloaded, null, 49), false);
});
