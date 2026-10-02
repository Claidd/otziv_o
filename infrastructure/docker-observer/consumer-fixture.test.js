const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { once } = require('node:events');
const { setTimeout: sleep } = require('node:timers/promises');
const { decodeSnappy, probe } = require('./consumer-fixture.cjs');

test('Loki fixture decodes bounded literals and overlapping copy; rejects corrupt blocks', () => {
  assert.equal(decodeSnappy(Buffer.from([5, 16, ...Buffer.from('hello')])).toString(), 'hello');
  assert.equal(decodeSnappy(Buffer.from([8, 0, 97, 26, 1, 0])).toString(), 'aaaaaaaa');
  for (const bytes of [[8, 0, 97, 26, 0, 0], [5, 16, 104], [0x80, 0x80, 0x80, 0x20], [6, 16, ...Buffer.from('hello')]]) {
    assert.throws(() => decodeSnappy(Buffer.from(bytes)));
  }
});

async function withFixture(override, run) {
  const requests = [], closed = [];
  const marker = 'synthetic-log-marker', fixtureId = 'b'.repeat(64);
  const server = http.createServer((request, response) => {
    const path = request.url.split('?')[0];
    requests.push(`${request.method} ${path}`);
    response.on('close', () => closed.push(path));
    if (override?.(request, response, { requests, marker, fixtureId })) return;
    response.setHeader('Content-Type', 'application/json');
    if (path === '/info') response.end(JSON.stringify({ ID: 'synthetic-observer' }));
    else if (path === '/healthcheck') response.end('OK');
    else if (path === `/containers/${fixtureId}/json`) response.end(JSON.stringify({ Config: {} }));
    else if (path.startsWith('/api/labels/')) response.end(marker);
    else if (path === '/fixture-evidence') response.end(JSON.stringify({ markerReceived: true }));
    else { response.writeHead(403); response.end(); }
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const endpoint = `http://127.0.0.1:${server.address().port}`;
  const options = { observer: endpoint, dozzle: endpoint, sinkUrl: endpoint, marker, fixtureId,
    deadlineMs: 2000, readinessMs: 1000, requestMs: 1000, streamMs: 1000, evidenceMs: 1000, retryMs: 10 };
  try { await run({ options, requests, closed, endpoint }); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}

async function expectClosed(closed, path) {
  const until = performance.now() + 1000;
  while (!closed.includes(path) && performance.now() < until) await sleep(10);
  assert.ok(closed.includes(path), 'cancellation must close the active request, or the preceding sink poll before aborting sleep');
}

test('late readiness succeeds while every subsequent functional and denial assertion runs once', async () => {
  let infoRequests = 0, healthRequests = 0;
  await withFixture((request, response) => {
    if ((request.url === '/info' && ++infoRequests < 3) || (request.url === '/healthcheck' && ++healthRequests < 2)) {
      response.writeHead(503); response.end('starting'); return true;
    }
  }, async ({ options, requests }) => {
    await probe(options);
    assert.equal(infoRequests, 3); assert.equal(healthRequests, 2);
    assert.equal(requests.filter(value => value === `GET /containers/${options.fixtureId}/json`).length, 1);
    for (const value of ['POST /containers/create', `POST /containers/${'a'.repeat(64)}/exec`,
      `DELETE /containers/${'a'.repeat(64)}`, 'POST /build', 'GET /volumes', 'GET /containers/%2e%2e/info']) {
      assert.equal(requests.filter(request => request === value).length, 1, value);
    }
    assert.equal(requests.filter(value => value.startsWith('GET /api/labels/')).length, 1);
    assert.ok(requests.includes('GET /fixture-evidence'));
  });
});

test('never-ready request is really aborted within the shared readiness bound', async () => {
  await withFixture(request => request.url === '/info', async ({ options, closed, endpoint, requests }) => {
    const started = performance.now();
    await assert.rejects(probe({ ...options, readinessMs: 500 }), error => {
      assert.match(error.message, /stage=observer-readiness/);
      assert.ok(error.message.includes(`endpoint=${endpoint}/info`));
      assert.match(error.message, /type=TimeoutError/); return true;
    });
    assert.ok(performance.now() - started < 950, 'readiness must abort before the longer per-request and total deadlines');
    await expectClosed(closed, '/info');
    assert.equal(requests.length, 1);
  });
});

test('observer and Dozzle share one readiness budget', async () => {
  await withFixture((request, response) => {
    if (request.url === '/info') { setTimeout(() => response.end('{"ID":"ready"}'), 200); return true; }
    if (request.url === '/healthcheck') { setTimeout(() => response.end('OK'), 600); return true; }
  }, async ({ options, endpoint }) => {
    const started = performance.now();
    await assert.rejects(probe({ ...options, readinessMs: 700 }), error => {
      assert.match(error.message, /stage=dozzle-readiness/);
      assert.ok(error.message.includes(`endpoint=${endpoint}/healthcheck`)); return true;
    });
    assert.ok(performance.now() - started < 1000);
  });
});

test('unexpected readiness status fails with endpoint and status but without response text', async () => {
  await withFixture((request, response) => {
    if (request.url === '/info') { response.writeHead(403); response.end('untrusted-secret-response'); return true; }
  }, async ({ options, endpoint, requests }) => {
    await assert.rejects(probe(options), error => {
      assert.ok(error.message.includes(`endpoint=${endpoint}/info`));
      assert.match(error.message, /stage=observer-readiness.*status=403.*type=Error/);
      assert.doesNotMatch(error.message, /untrusted-secret-response/); return true;
    });
    assert.equal(requests.length, 1);
  });
});

test('post-ready inspect leak fails immediately without retry or secret diagnostics', async () => {
  await withFixture((request, response) => {
    if (request.url.endsWith('/json')) { response.end('{"Config":{"Env":["DO_NOT_PRINT_SECRET"]}}'); return true; }
  }, async ({ options, requests }) => {
    await assert.rejects(probe(options), error => {
      assert.match(error.message, /stage=inspect-redaction.*status=200.*type=AssertionError/);
      assert.doesNotMatch(error.message, /DO_NOT_PRINT_SECRET/); return true;
    });
    assert.equal(requests.filter(value => value.endsWith('/json')).length, 1);
    assert.equal(requests.filter(value => value.startsWith('POST')).length, 0);
  });
});

test('post-ready wrong denial status fails immediately without retry', async () => {
  await withFixture((request, response) => {
    if (request.method === 'POST') { response.end('incorrectly allowed'); return true; }
  }, async ({ options, requests }) => {
    await assert.rejects(probe(options), /stage=deny-POST.*endpoint=.*\/containers\/create.*status=200.*type=AssertionError/);
    assert.equal(requests.filter(value => value === 'POST /containers/create').length, 1);
    assert.equal(requests.filter(value => value.startsWith('GET /api/labels/')).length, 0);
  });
});

for (const phase of ['inspect', 'mutation', 'stream', 'sink-poll']) {
  test(`the total deadline aborts ${phase} without retrying functional assertions`, async () => {
    await withFixture((request, response) => {
      if (phase === 'inspect' && request.url.endsWith('/json')) return true;
      if (phase === 'mutation' && request.method === 'POST') return true;
      if (phase === 'stream' && request.url.startsWith('/api/labels/')) { response.write('waiting'); return true; }
      if (phase === 'sink-poll' && request.url === '/fixture-evidence') { response.end('{"markerReceived":false}'); return true; }
    }, async ({ options, requests, closed }) => {
      const stages = { inspect: 'inspect-redaction', mutation: 'deny-POST', stream: 'dozzle-log-stream', 'sink-poll': 'alloy-log-push' };
      const started = performance.now();
      await assert.rejects(probe({ ...options, deadlineMs: 500 }), error => {
        assert.ok(error.message.includes(`stage=${stages[phase]}`));
        assert.match(error.message, /type=(TimeoutError|AbortError)/); return true;
      });
      assert.ok(performance.now() - started < 950);
      const path = phase === 'inspect' ? `/containers/${options.fixtureId}/json` : phase === 'mutation' ? '/containers/create'
        : phase === 'stream' ? `/api/labels/dozzle.name:${options.marker}/logs/stream` : '/fixture-evidence';
      await expectClosed(closed, path);
      assert.equal(requests.filter(value => value.endsWith(path)).length, 1);
    });
  });
}
