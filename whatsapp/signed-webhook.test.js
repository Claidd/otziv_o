"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const { createSignedWebhookPoster } = require("./signed-webhook");

async function server(t, handler) {
  const instance = http.createServer(handler);
  await new Promise(resolve => instance.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => { instance.closeAllConnections(); instance.close(resolve); }));
  return `http://127.0.0.1:${instance.address().port}`;
}

test("a redirect is reported as its own status and never carries the secret to the target", async t => {
  const reachedTarget = [];
  const target = await server(t, (req, res) => { reachedTarget.push(req.headers); res.end("ok"); });
  for (const status of [301, 302, 307, 308]) {
    const backend = await server(t, (_req, res) => { res.writeHead(status, { Location: `${target}/stolen` }); res.end(); });
    const post = createSignedWebhookPoster({ timeoutMs: 5000 });
    assert.deepEqual(await post(backend, "fixture-secret", "/webhook/whatsapp-group-reply", { messageId: "m1" }), { status });
  }
  assert.equal(reachedTarget.length, 0);
});

test("direct delivery keeps the signed body and returns the backend status", async t => {
  let received;
  const backend = await server(t, (req, res) => {
    let body = ""; req.on("data", chunk => { body += chunk; });
    req.on("end", () => { received = { headers: req.headers, body }; res.writeHead(204); res.end(); });
  });
  const post = createSignedWebhookPoster({ timeoutMs: 5000 });
  assert.deepEqual(await post(backend, "fixture-secret", "/webhook/outreach-reply", { messageId: "m1" }), { status: 204 });
  assert.equal(received.headers["x-whatsapp-webhook-secret"], "fixture-secret");
  assert.match(received.headers["x-whatsapp-webhook-signature"], /^sha256=[a-f0-9]{64}$/u);
  assert.equal(received.body, JSON.stringify({ messageId: "m1" }));
});

test("the configured fetch receives redirect manual", async () => {
  let options;
  const post = createSignedWebhookPoster({ fetchImpl: async (_url, init) => { options = init; return { status: 200, body: null }; } });
  await post("http://backend", "", "/path", {});
  assert.equal(options.redirect, "manual");
  const opaque = createSignedWebhookPoster({ fetchImpl: async () => ({ type: "opaqueredirect", status: 0, body: null }) });
  assert.deepEqual(await opaque("http://backend", "", "/path", {}), { status: 0 });
});
