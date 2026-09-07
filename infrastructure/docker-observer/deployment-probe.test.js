'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { probe } = require('./deployment-probe.cjs');
const marker = `OTZIV_DEPLOY_${'a'.repeat(32)}`;

test('Dozzle deployment acceptance requires the exact current fixture stream marker', async () => {
  const urls = [];
  await probe('dozzle', marker, { fetcher: async url => {
    urls.push(url); return new Response(`data: ${JSON.stringify({ m: marker, ts: 1 })}\n\n`);
  }});
  assert.match(urls[0], /api\/labels\/dozzle.name:OTZIV_DEPLOY_/);
  await assert.rejects(probe('dozzle', marker, { timeoutMs: 10, fetcher: async () => new Response('unrelated logs') }), /log_flow_unverified/);
  await assert.rejects(probe('dozzle', marker, { timeoutMs: 10, fetcher: async () =>
    new Response(`event: container-event\ndata: ${JSON.stringify({ name: marker, m: marker })}\n\n`) }), /log_flow_unverified/);
  await probe('dozzle', marker, { fetcher: async () =>
    new Response(`event: logs-backfill\ndata: ${JSON.stringify([{ m: marker, ts: 1 }])}\n\n`) });
});

test('Alloy acceptance parses actual Loki values and cannot pass on an echoed query or health 200', async () => {
  await probe('alloy', marker, { fetcher: async url => {
    assert.equal(new URL(url).hostname, 'loki');
    return Response.json({ status: 'success', data: { result: [{ values: [['1', marker]] }] } });
  }});
  await assert.rejects(probe('alloy', marker, { timeoutMs: 10, fetcher: async () =>
    Response.json({ status: 'success', query: marker, data: { result: [] } }) }), /log_flow_unverified/);
});

test('deployment probe does not accept arbitrary consumer hosts or query injection', async () => {
  await assert.rejects(probe('external', marker), /invalid_probe/);
  await assert.rejects(probe('alloy', '"} |= "private'), /invalid_probe/);
});
