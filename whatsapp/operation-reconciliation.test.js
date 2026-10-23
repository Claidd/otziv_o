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
  return { ledger, invoke, sends: () => sends, envelope, dir, now };
}

test("one history message matching two uncertain operations confirms neither, including concurrently", async t => {
  const f = await fixture(t);
  await f.ledger.execute("second-op", f.envelope, async () => null);
  const handler = createOperationReconciliationHandler({ ledger: f.ledger, clientId: "fixture", canRead: () => true,
    getMessage: async () => ({ id: { _serialized: "true_123@g.us_ABCD", remote: "123@g.us" }, fromMe: true,
      body: f.envelope.message, timestamp: f.now / 1000, ack: 1 }) });
  const results = await Promise.allSettled(["test-op", "second-op"].map(operationId =>
    handler({ params: { operationId }, body: { messageId: "true_123@g.us_ABCD" } }, { set() {}, json() {} })));
  assert.equal(results.every(result => result.status === "rejected" && result.reason.code === "operation_evidence_ambiguous"), true);
  assert.equal(f.ledger.lookup("test-op").state, "UNKNOWN");
  assert.equal(f.ledger.lookup("second-op").state, "UNKNOWN");
});

test("a provider message already bound to another operation stays exclusive after restart", async t => {
  const f = await fixture(t);
  await f.invoke();
  await f.ledger.close();
  const reopened = new OperationLedger(f.dir, { now: () => f.now + 600000 });
  await reopened.execute("later-op", f.envelope, async () => null);
  await assert.rejects(reopened.reconcile("later-op", f.envelope, "true_123@g.us_ABCD", async () => true),
    error => error.code === "operation_evidence_already_bound");
  assert.equal(reopened.lookup("later-op").state, "UNKNOWN");
  await reopened.close();
});

test("legacy duplicate successful records never expose a successful receipt after restart", async t => {
  const f = await fixture(t);
  await f.invoke();
  await f.ledger.execute("legacy-second", { ...f.envelope, message: "other" }, async () => null);
  const duplicate = f.ledger.read(f.ledger.key("legacy-second"));
  // Simulate historical persisted corruption, not a valid reconciliation path.
  fs.writeFileSync(f.ledger.filename(duplicate.key), JSON.stringify({ ...duplicate, state: "SUCCEEDED", messageId: "true_123@g.us_ABCD" }));
  await f.ledger.close();
  const reopened = new OperationLedger(f.dir);
  for (const id of ["test-op", "legacy-second"])
    assert.throws(() => reopened.lookup(id), error => error.code === "operation_evidence_already_bound");
  let sends = 0;
  await assert.rejects(reopened.execute("test-op", f.envelope, async () => { sends++; }),
    error => error.code === "operation_evidence_already_bound");
  assert.equal(sends, 0);
  await reopened.close();
});

test("a missing completion timestamp never makes the history window extend until now", async t => {
  const f = await fixture(t);
  const record = f.ledger.read(f.ledger.key("test-op"));
  delete record.completedAt;
  f.ledger.replace(record);
  await assert.rejects(f.invoke({ timestamp: f.now / 1000 }),
    error => error.code === "operation_evidence_window_unavailable");
  assert.equal(f.ledger.lookup("test-op").state, "UNKNOWN");
});

test("concurrent verification rechecks the durable message binding after await", async t => {
  const f = await fixture(t);
  const second = { ...f.envelope, message: "different payload" };
  await f.ledger.execute("second-op", second, async () => null);
  const results = await Promise.allSettled([
    f.ledger.reconcile("test-op", f.envelope, "same-provider-id", async () => true),
    f.ledger.reconcile("second-op", second, "same-provider-id", async () => true)
  ]);
  assert.equal(results.filter(result => result.status === "fulfilled").length, 1);
  assert.equal(results.filter(result => result.status === "rejected" && result.reason.code === "operation_evidence_already_bound").length, 1);
});

test("positive provider evidence resolves an ambiguous send and replay never calls send again", async t => {
  const f = await fixture(t);
  const result = await f.invoke();
  assert.equal(result.receiptBindingVersion, 1);
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
