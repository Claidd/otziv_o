"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const express = require("express");
const { OperationLedger, OperationLedgerError, envelopeHash, ledgerErrorBody } = require("./operation-ledger");
const { createOutboundHandler } = require("./outbound-routes");
const { TaskLimiter } = require("./task-limiter");

function deferred() { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; }
async function fixture(t, send, { canStart = () => true } = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-outbound-http-test-"));
  const ledger = new OperationLedger(directory);
  const gate = new TaskLimiter(2);
  const app = express(); app.use(express.json());
  app.post("/send", gate.wrap(createOutboundHandler({
    ledger, clientId: "fixture", kind: "send", normalizeDestination: (value) => typeof value === "string" ? value : "",
    maximumChars: 100, canStart, send,
  })));
  app.use((error, _req, res, _next) => error instanceof OperationLedgerError
    ? res.status(error.statusCode).json(ledgerErrorBody(error))
    : res.status(500).json({ code: error.code || "internal_error" }));
  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  t.after(async () => {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  const url = `http://127.0.0.1:${server.address().port}/send`;
  const post = (body, options = {}) => fetch(url, { method: "POST", body: JSON.stringify(body), headers: { "Content-Type": "application/json", ...options.headers }, signal: options.signal });
  return { post, ledger, gate };
}

test("real HTTP abort after send starts cannot replay the provider operation", async (t) => {
  const started = deferred(); const provider = deferred(); let calls = 0;
  const { post, gate } = await fixture(t, () => { calls++; started.resolve(); return provider.promise; });
  const payload = { operationId: "http-operation", phone: "fixture-recipient", message: "fixture" };
  const controller = new AbortController();
  const first = post(payload, { signal: controller.signal }).catch((error) => error);
  await started.promise; controller.abort(); await first;
  const retry = await post(payload);
  assert.equal(retry.status, 409);
  assert.equal((await retry.json()).code, "operation_running");
  assert.equal(calls, 1);
  assert.equal(gate.active, 1);
  provider.resolve("fixture-message-id");
  // drain is a deterministic completion barrier; a different server can replay
  // the durable result without requiring another provider call.
  assert.equal(await gate.drain(1000), true);
});

test("confirmed HTTP retries preserve response, mismatched payload gets 409", async (t) => {
  let calls = 0;
  const { post } = await fixture(t, async () => { calls++; return "fixture-message-id"; });
  const payload = { operationId: "http-operation", phone: "fixture-recipient", message: "fixture" };
  assert.equal((await post(payload)).status, 200);
  const duplicate = await post(payload);
  const result = await duplicate.json();
  assert.equal(result.status, "ok"); assert.equal(result.replayed, true);
  assert.equal(result.messageId, "fixture-message-id"); assert.equal(calls, 1);
  assert.equal(result.envelopeHash, envelopeHash({ clientId: "fixture", kind: "send", destination: payload.phone, message: payload.message }));
  assert.equal((await post({ ...payload, phone: "different-recipient" })).status, 409);
});

test("ambiguous provider failure is explicit and never retried by keyed requests", async (t) => {
  let calls = 0;
  const { post } = await fixture(t, async () => { calls++; throw new Error("synthetic provider timeout"); });
  const payload = { operationId: "http-operation", phone: "fixture-recipient", message: "fixture" };
  for (let attempt = 0; attempt < 2; attempt++) {
    const response = await post(payload);
    assert.equal(response.status, 409);
    const result = await response.json();
    assert.equal(result.code, "operation_unknown");
    assert.equal(result.envelopeHash, envelopeHash({ clientId: "fixture", kind: "send", destination: payload.phone, message: payload.message }));
  }
  assert.equal(calls, 1);
});

test("pre-dispatch rejections say dispatched:false; a lost result after the provider call says dispatched:true", async (t) => {
  let ready = false, calls = 0;
  const { post, ledger } = await fixture(t, async () => { calls++; return "fixture-message-id"; }, { canStart: () => ready });
  const payload = { operationId: "http-operation", phone: "fixture-recipient", message: "fixture" };
  const rejected = await post(payload);
  assert.equal(rejected.status, 503);
  assert.deepEqual(await rejected.json(), { status: "error", code: "gateway_not_ready", dispatched: false });
  ready = true;
  ledger.replace = () => { throw ledger.failPersistence("operation_result_not_durable"); };
  const lost = await post(payload);
  assert.deepEqual(await lost.json(), { status: "error", code: "operation_result_not_durable", dispatched: true });
  assert.equal(calls, 1);
  const invalid = await post({ ...payload, message: "" });
  assert.deepEqual(await invalid.json(), { status: "error", code: "invalid_request" });
});

test("an unacknowledged provider message is reported as UNKNOWN with its ACK, an acknowledged one as SUCCEEDED", async (t) => {
  let ack = 0;
  const { post } = await fixture(t, async () => ({ messageId: `message-${ack}`, ack, sentAt: Date.now() }));
  const pending = await post({ operationId: "unacked", phone: "fixture-recipient", message: "fixture" });
  assert.equal(pending.status, 409);
  const body = await pending.json();
  assert.equal(body.code, "operation_unknown"); assert.equal(body.providerAck, 0); assert.equal(body.messageId, undefined);
  ack = 1;
  const confirmed = await (await post({ operationId: "acked", phone: "fixture-recipient", message: "fixture" })).json();
  assert.equal(confirmed.state, "SUCCEEDED"); assert.equal(confirmed.messageId, "message-1"); assert.equal(confirmed.providerAck, 1);
});
