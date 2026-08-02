import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  botBrowserApiPaths,
  botBrowserSessionClosePath,
  botBrowserSessionHeartbeatPath
} = loadTsModule('src/app/core/bot-browser-api-paths.ts');

test('uses the safe browser metadata endpoint without changing session endpoints', () => {
  const paths = botBrowserApiPaths(37);

  assert.deepEqual(
    { ...paths },
    {
      metadata: '/api/bots/37/browser/metadata',
      open: '/api/bots/37/browser/open',
      close: '/api/bots/37/browser/close'
    }
  );
  assert.equal(paths.metadata.includes('/api/admin/bots/'), false);
  assert.equal(
    botBrowserSessionHeartbeatPath(37, 'session/id'),
    '/api/bots/37/browser/sessions/session%2Fid/heartbeat'
  );
  assert.equal(
    botBrowserSessionClosePath(37, 'session/id'),
    '/api/bots/37/browser/sessions/session%2Fid/close'
  );
});
