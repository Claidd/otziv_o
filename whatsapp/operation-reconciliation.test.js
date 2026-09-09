"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { OperationLedger } = require("./operation-ledger");
const { createOperationReconciliationHandler } = require("./operation-reconciliation");

async function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "wa-proof-"));
  const now = 1800000000000;
  const ledger = new OperationLedger(dir, { now: () => now });
  t.after(async () => { await ledger.close(); fs.rmSync(dir, { recursive: true, force: true }); });
  const envelope = { clientId: "fixture", kind: "send-group", destination: "123@g.us", message: "test payload" };
  let sends = 0;
  await ledger.execute("test-op", envelope, async () => { sends++; return null; });
  const messageId = "true_123@g.us_ABCD";
  const message = { id: { fromMe: true, remote: "123@g.us", id: "ABCD" }, fromMe: true,
    body: envelope.message, timestamp: now / 1000, ack: 1 };
  const invoke = async (mutate = {}) => {
    let output;
    const handler = createOperationReconciliationHandler({ ledger, clientId: "fixture", canRead: () => true,
      getMessage: async value => { assert.equal(value, messageId); return { ...message, ...mutate }; } });
    await handler({ params: { operationId: "test-op" }, body: { messageId } }, { set() {}, json(value) { output = value; } });
    return output;
  };
  return { ledger, invoke, sends: () => sends, envelope };
}

test("positive provider evidence resolves an ambiguous send and replay never calls send again", async t => {
  const f = await fixture(t);
  const result = await f.invoke();
  assert.equal(result.state, "SUCCEEDED");
  assert.equal((await f.invoke()).messageId, result.messageId);
  await f.ledger.execute("test-op", f.envelope, async () => { throw new Error("must not send"); });
  assert.equal(f.sends(), 1);
});
for (const [name, mutation] of Object.entries({
  "unacknowledged message": { ack: 0 }, "missing ack": { ack: undefined },
  "incoming message": { fromMe: false }, "different content": { body: "other" },
  "different recipient": { id: { _serialized: "true_123@g.us_ABCD", remote: "456@g.us" } },
  "different identity": { id: { _serialized: "other", remote: "123@g.us" } },
  "old identical content": { timestamp: 1700000000 },
})) {
  test(`rejects ${name} without releasing the UNKNOWN barrier`, async t => {
    const f = await fixture(t);
    await assert.rejects(f.invoke(mutation), error => error.statusCode === 409);
    assert.equal(f.ledger.lookup("test-op").state, "UNKNOWN");
    assert.equal(f.sends(), 1);
  });
}
