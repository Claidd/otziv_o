import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
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
  'http://vnc.example.test/session',
  '/relative/vnc/session',
  'not a url',
  'https://'
]) {
  test(`rejects unsafe or malformed VNC URL: ${rawUrl}`, () => {
    assert.equal(prepareBotBrowserVncUrl(rawUrl), null);
  });
}

for (const rawUrl of [
  'http://localhost:6080/session',
  'https://vnc.example.test/session'
]) {
  test(`accepts and prepares an explicitly allowed secure VNC URL: ${rawUrl}`, () => {
    const prepared = prepareBotBrowserVncUrl(rawUrl, {
      pageOrigin: 'https://app.example.test',
      allowedOrigins: [new URL(rawUrl).origin]
    });

    assert.equal(typeof prepared, 'string');
    const url = new URL(prepared);
    assert.equal(url.host, new URL(rawUrl).host);
    assert.equal(url.searchParams.get('autoconnect'), '1');
    assert.equal(url.searchParams.get('reconnect'), '1');
    assert.equal(url.searchParams.get('resize'), 'none');
    assert.equal(url.searchParams.get('clip'), 'true');
  });
}

test('rejects an HTTPS VNC origin that was not configured', () => {
  assert.equal(prepareBotBrowserVncUrl('https://evil.example.test/session', {
    pageOrigin: 'https://app.example.test',
    allowedOrigins: ['https://vnc.example.test']
  }), null);
});

test('mobile VNC lifecycle queues route or view re-entry while the old session closes', () => {
  const page = readFileSync(new URL('../src/app/features/bot-browser.page.ts', import.meta.url), 'utf8');
  const environment = readFileSync(new URL('../src/app/core/mobile-environment.ts', import.meta.url), 'utf8');

  assert.match(page, /private reopenAfterClose = false/);
  assert.match(page, /private openSession\(queueAfterClose = false\)/);
  assert.match(page, /if \(queueAfterClose && this\.isCurrentContext\(generation, botId\)\) \{\s*this\.reopenAfterClose = true;/);
  assert.match(page, /ionViewWillEnter[\s\S]*?this\.openSession\(true\)/);
  assert.match(page, /activateBot[\s\S]*?this\.openSession\(true\)/);
  assert.match(environment, /botBrowserVncAllowedOrigins:\s*\[\s*nativeBackendBaseUrl,/);
});
