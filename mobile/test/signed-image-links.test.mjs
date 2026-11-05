import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const { backendImageUrl } = loadTsModule('src/app/core/image-urls.ts');
const base = 'https://example.test';

test('uses the signed photo link returned by the API', () => {
  assert.equal(
    backendImageUrl(base, 42, '/images/42?exp=1793790900&sig=abc_-'),
    'https://example.test/images/42?exp=1793790900&sig=abc_-'
  );
});

test('falls back to the bare id path only without a signed link', () => {
  assert.equal(backendImageUrl(base, 42, null), 'https://example.test/images/42');
  assert.equal(backendImageUrl(base, 42, undefined), 'https://example.test/images/42');
  assert.equal(backendImageUrl(base, 42, 'https://evil.example/x.png'), 'https://example.test/images/42');
});

test('team and score photos pass the signed link through', () => {
  const page = readFileSync(new URL('../src/app/features/home.page.ts', import.meta.url), 'utf8');
  assert.match(page, /imageUrl\(member\.imageId, member\.imageUrl\)/);
  assert.match(page, /imageUrl\(user\.imageId, user\.imageUrl\)/);
  assert.doesNotMatch(page, /imageUrl\((member|user)\.imageId\)/);
});
