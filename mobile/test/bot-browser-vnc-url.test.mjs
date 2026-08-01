import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const { prepareBotBrowserVncUrl } = loadTsModule('src/app/core/bot-browser-vnc-url.ts');

for (const rawUrl of [
  'javascript:alert(document.cookie)',
  'data:text/html,<script>alert(1)</script>',
  'https://user:password@vnc.example.test/session',
  'https://vnc.example.test/session\r\nLocation:https://evil.test',
  'https://vnc.example.test/session%0d%0aLocation:https://evil.test',
  'https://vnc.example.test/session%00',
  'https://vnc.example.test/session%7f',
  '/relative/vnc/session',
  'not a url',
  'https://'
]) {
  test(`rejects unsafe or malformed VNC URL: ${rawUrl}`, () => {
    assert.equal(prepareBotBrowserVncUrl(rawUrl), null);
  });
}

for (const rawUrl of [
  'http://vnc.example.test/session',
  'https://vnc.example.test/session'
]) {
  test(`accepts and prepares absolute HTTP(S) VNC URL: ${rawUrl}`, () => {
    const prepared = prepareBotBrowserVncUrl(rawUrl);

    assert.equal(typeof prepared, 'string');
    const url = new URL(prepared);
    assert.equal(url.host, 'vnc.example.test');
    assert.equal(url.searchParams.get('autoconnect'), '1');
    assert.equal(url.searchParams.get('reconnect'), '1');
    assert.equal(url.searchParams.get('resize'), 'none');
    assert.equal(url.searchParams.get('clip'), 'true');
  });
}
