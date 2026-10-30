"use strict";
const test = require("node:test"), assert = require("node:assert/strict");
const fs = require("node:fs"), os = require("node:os"), path = require("node:path");
const { OperationLedger } = require("./operation-ledger");
const { observeOperationReceipt } = require("./operation-receipt-observer");
const { createOperationHistoryReconciliationHandler } = require("./operation-history-reconciliation");

function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "wa-receipt-recovery-"));
  let now = 1800000000000, ledger = new OperationLedger(dir, { now: () => now });
  t.after(async () => { await ledger.close(); fs.rmSync(dir, { recursive: true, force: true }); });
  const envelope = { clientId: "fixture", kind: "send-group", destination: "12345678@g.us", message: "original body" };
  const message = (id = "ORIGINAL", extra = {}) => ({ id: { _serialized: `true_${envelope.destination}_${id}`, remote: envelope.destination },
    fromMe: true, type: "chat", body: envelope.message, timestamp: now / 1000, ack: 1, ...extra });
  const handler = ({ getMessage, chat }) => createOperationHistoryReconciliationHandler({ ledger, clientId: envelope.clientId,
    normalizeGroup: x => x, normalizePhone: x => x, canRead: () => true,
    getMessage, getChat: async () => chat });
  const invoke = async (run, id = "original", body = envelope.message) => {
    let output;
    await run({ params: { operationId: id }, body: { kind: envelope.kind, destination: envelope.destination, message: body } },
      { set() {}, json(value) { output = value; } });
    return output;
  };
  return { dir, envelope, message, handler, invoke, get ledger() { return ledger; },
    advance(ms) { now += ms; }, async restart() { await ledger.close(); ledger = new OperationLedger(dir, { now: () => now }); } };
}

test("lost send response recovers the observed provider ID after restart without a second send or cache scan", async t => {
  const f = fixture(t), sent = f.message(); let sends = 0, reads = 0;
  const first = await f.ledger.execute("original", f.envelope, async () => {
    sends++;
    assert.equal(observeOperationReceipt({ ledger: f.ledger, clientId: "fixture", message: sent }), true);
    throw new Error("response lost after provider created message");
  });
  assert.equal(first.state, "UNKNOWN"); await f.restart();
  const run = f.handler({ getMessage: async id => { reads++; assert.equal(id, sent.id._serialized); return sent; },
    chat: { fetchMessages: assert.fail, syncHistory: assert.fail } });
  const result = await f.invoke(run);
  assert.equal(result.state, "SUCCEEDED"); assert.equal(result.messageId, sent.id._serialized);
  assert.equal((await f.ledger.execute("original", f.envelope, assert.fail)).state, "SUCCEEDED");
  await f.invoke(run); assert.equal(reads, 1); assert.equal(sends, 1);
});

test("captured ID restores a crash with no completion timestamp, without guessing an unobserved operation window", async t => {
  const f = fixture(t), sent = f.message();
  await f.ledger.execute("original", f.envelope, async () => {
    observeOperationReceipt({ ledger: f.ledger, clientId: "fixture", message: sent }); return null;
  });
  const record = f.ledger.read(f.ledger.key("original")); delete record.completedAt;
  f.ledger.replace(record); await f.restart();
  assert.equal((await f.invoke(f.handler({ getMessage: async () => sent, chat: { fetchMessages: assert.fail } }))).state, "SUCCEEDED");
});

test("a captured ID with ACK=0 cannot close delivery and is never resent", async t => {
  const f = fixture(t), sent = f.message("QUEUED", { ack: 0 }); let sends = 0;
  await f.ledger.execute("original", f.envelope, async () => {
    sends++; observeOperationReceipt({ ledger: f.ledger, clientId: "fixture", message: sent }); return null;
  });
  const run = f.handler({ getMessage: async () => sent, chat: { fetchMessages: async () => [], syncHistory: async () => true } });
  const result = await f.invoke(run);
  assert.equal(result.state, "UNKNOWN"); assert.equal(result.recoveryCode, "history_sync_requested");
  assert.equal((await f.ledger.execute("original", f.envelope, assert.fail)).state, "UNKNOWN"); assert.equal(sends, 1);
});

test("incoming, wrong body/chat/account, attachment, notification and late messages cannot acquire an operation ID", async t => {
  const f = fixture(t); await f.ledger.execute("original", f.envelope, async () => null);
  for (const change of [{ fromMe: false }, { body: "different" }, { id: { _serialized: "true_87654321@g.us_OTHER", remote: "87654321@g.us" } },
    { type: "image" }, { isNotification: true }, { timestamp: 1800000010 }])
    assert.equal(observeOperationReceipt({ ledger: f.ledger, clientId: "fixture", message: f.message("OTHER", change) }), false);
  assert.equal(observeOperationReceipt({ ledger: f.ledger, clientId: "other-account", message: f.message() }), false);
  assert.equal(f.ledger.read(f.ledger.key("original")).receiptCandidates, undefined);
});

test("two candidate messages remain ambiguous even when only one is later visible", async t => {
  const f = fixture(t); await f.ledger.execute("original", f.envelope, async () => null);
  for (const id of ["FIRST", "SECOND"]) observeOperationReceipt({ ledger: f.ledger, clientId: "fixture", message: f.message(id) });
  await f.restart();
  const result = await f.invoke(f.handler({ getMessage: assert.fail, chat: { fetchMessages: assert.fail } }));
  assert.equal(result.state, "UNKNOWN"); assert.equal(result.recoveryCode, "history_ambiguous");
});

test("overlapping original operations cannot claim the same observed provider message", async t => {
  const f = fixture(t);
  await f.ledger.execute("original", f.envelope, async () => null);
  await f.ledger.execute("competing", f.envelope, async () => null);
  assert.equal(observeOperationReceipt({ ledger: f.ledger, clientId: "fixture", message: f.message() }), false);
});

test("primary-device synchronization is durable per chat, shared across operations and permits immediate receipt recovery", async t => {
  const f = fixture(t); let syncs = 0, history = [];
  await f.ledger.execute("original", f.envelope, async () => null);
  const chat = { fetchMessages: async () => history, syncHistory: async () => { syncs++; return true; } };
  let run = f.handler({ getMessage: async () => f.message(), chat });
  assert.equal((await f.invoke(run)).recoveryCode, "history_sync_requested"); await f.restart();
  run = f.handler({ getMessage: async () => f.message(), chat });
  assert.equal((await f.invoke(run)).recoveryCode, "history_sync_pending"); assert.equal(syncs, 1);
  const second = { ...f.envelope, message: "different original body" };
  await f.ledger.execute("second", second, async () => null);
  assert.equal((await f.invoke(run, "second", second.message)).recoveryCode, "history_sync_pending"); assert.equal(syncs, 1);
  history = [f.message()];
  assert.equal((await f.invoke(run)).state, "SUCCEEDED"); assert.equal(syncs, 1);
});

for (const mode of ["unavailable", "not supported", "malformed response"]) test(`${mode} history synchronization remains explicit after restart`, async t => {
  const f = fixture(t); let syncs = 0;
  await f.ledger.execute("original", f.envelope, async () => null);
  const chat = { fetchMessages: async () => [], syncHistory: async () => {
    syncs++; if (mode === "unavailable") throw new Error("primary phone unavailable");
    return mode === "not supported" ? false : {};
  } };
  const expected = mode === "not supported" ? "history_no_receipt" : "history_sync_unavailable";
  assert.equal((await f.invoke(f.handler({ getMessage: assert.fail, chat }))).recoveryCode, expected);
  await f.restart();
  assert.equal((await f.invoke(f.handler({ getMessage: assert.fail, chat }))).recoveryCode, expected); assert.equal(syncs, 1);
  f.advance(30 * 60 * 1000);
  assert.equal((await f.invoke(f.handler({ getMessage: assert.fail, chat }))).recoveryCode, expected); assert.equal(syncs, 2);
  assert.equal(f.ledger.lookup("original").state, "UNKNOWN");
});

test("candidate storage remains bounded and repeated ACK events do not rewrite the original send", async t => {
  const f = fixture(t); await f.ledger.execute("original", f.envelope, async () => null);
  for (let i = 0; i < 8; i++) observeOperationReceipt({ ledger: f.ledger, clientId: "fixture", message: f.message("M" + i) });
  const file = f.ledger.filename(f.ledger.key("original")), before = fs.readFileSync(file);
  observeOperationReceipt({ ledger: f.ledger, clientId: "fixture", message: f.message("M0") });
  assert.deepEqual(fs.readFileSync(file), before);
  assert.equal(f.ledger.read(f.ledger.key("original")).receiptCandidates.length, 4);
  assert.equal(fs.statSync(file).size < 4096, true);
  assert.equal(f.ledger.lookup("original").state, "UNKNOWN");
});
