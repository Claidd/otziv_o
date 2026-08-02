import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  safeExternalSchemeUrl,
  safeHttpsExternalUrl,
  safeHttpsOrInternalUrl,
  safeInternalNavigationPath
} = loadTsModule('src/app/shared/external-navigation.ts');

test('allows credential-free HTTPS and safe internal paths', () => {
  assert.equal(safeHttpsExternalUrl(' https://2gis.ru/irkutsk/firm/1 '), 'https://2gis.ru/irkutsk/firm/1');
  assert.equal(safeInternalNavigationPath('/tabs/orders/42'), '/tabs/orders/42');
  assert.equal(safeHttpsOrInternalUrl('/public-review/abc'), '/public-review/abc');
});

test('rejects active, insecure and credential-bearing navigation targets', () => {
  for (const value of [
    'javascript:alert(1)',
    'data:text/html,test',
    'http://2gis.ru/irkutsk',
    'https://user:pass@example.com/',
    '//evil.example/path',
    '/\\evil.example/path',
    'https://example.com/\r\nLocation:https://evil.example',
    'https://example.com/%0d%0aLocation:https://evil.example'
  ]) {
    assert.equal(safeHttpsOrInternalUrl(value), null);
  }
});

test('allows only explicitly requested application schemes', () => {
  assert.equal(safeExternalSchemeUrl('tg://resolve?phone=79990000000', ['tg:']), 'tg://resolve?phone=79990000000');
  assert.equal(safeExternalSchemeUrl('javascript:alert(1)', ['tg:']), null);
  assert.equal(safeExternalSchemeUrl('tel:+79990000000', ['tg:']), null);
});
