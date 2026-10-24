"use strict";
const test = require("node:test"), assert = require("node:assert/strict");
const fs = require("node:fs"), os = require("node:os"), path = require("node:path");
const { OperationLedger } = require("./operation-ledger");
const { createOperationHistoryReconciliationHandler } = require("./operation-history-reconciliation");

async function fixture(t, options = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "wa-history-proof-"));
  const now = 1800000000000, ledger = new OperationLedger(dir, { now: () => now });
  t.after(async () => { await ledger.close(); fs.rmSync(dir, { recursive: true, force: true }); });
  const kind = options.kind || "send-group", destination = kind === "send" ? "12345678901@c.us" : "12345678@g.us";
  const envelope = { clientId: "fixture", kind, destination, message: "saved body" };
  let sends = 0, reads = 0;
  await ledger.execute("saved", envelope, async () => { sends++; return null; });
  const message = (id, seconds = 0, overrides = {}) => ({ id: { _serialized: `true_${destination}_${id}`, remote: destination },
    fromMe: true, body: envelope.message, timestamp: now / 1000 + seconds, ack: 1, ...overrides });
  const found = message("MATCH");
  let messages = [found];
  const limits = [];
  const handler = createOperationHistoryReconciliationHandler({ ledger, clientId: "fixture", canRead: () => true,
    normalizeGroup: value => value, normalizePhone: value => value, ...options,
    getChat: options.getChat || (async () => ({ async fetchMessages({ limit }) { limits.push(limit); return messages.slice(-limit); } })),
    getMessage: options.getMessage || (async id => { reads++; return messages.find(m => m.id._serialized === id); }) });
  const invoke = async (body = {}) => {
    let output;
    await handler({ params: { operationId: "saved" }, body: { kind, destination, message: envelope.message, ...body } },
      { set() {}, json(value) { output = value; } });
    return output;
  };
  return { ledger, envelope, message, found, invoke, limits, sends: () => sends, reads: () => reads,
    setMessages: value => { messages = value; } };
}

for (const kind of ["send-group", "send"]) test(`${kind}: older history is loaded and later identical text is excluded`, async t => {
  const f = await fixture(t, { kind });
  f.setMessages([f.message("OLD", -5), f.found, ...Array.from({ length: 120 }, (_, i) => f.message(`LATER${i}`, 60 + i))]);
  const result = await f.invoke();
  assert.equal(result.state, "SUCCEEDED"); assert.equal(result.messageId, f.found.id._serialized);
  assert.deepEqual(f.limits, [100, 200]); assert.equal(f.reads(), 1);
  await f.invoke(); assert.equal(f.reads(), 1); assert.equal(f.sends(), 1);
});

test("partial history never binds even one candidate", async t => {
  const f = await fixture(t, { maxMessages: 100 });
  f.setMessages([f.found, ...Array.from({ length: 99 }, (_, i) => f.message(`LATER${i}`, 10 + i))]);
  assert.equal((await f.invoke()).recoveryCode, "history_incomplete");
  assert.equal(f.ledger.lookup("saved").state, "UNKNOWN"); assert.equal(f.reads(), 0);
});

test("empty history is UNKNOWN, not proof of non-delivery", async t => {
  const f = await fixture(t); f.setMessages([]);
  assert.equal((await f.invoke()).recoveryCode, "history_no_receipt");
  assert.equal(f.ledger.lookup("saved").state, "UNKNOWN"); assert.equal(f.sends(), 1);
});

test("two matching provider messages in the operation window remain ambiguous", async t => {
  const f = await fixture(t); f.setMessages([f.found, f.message("SECOND", 1)]);
  assert.equal((await f.invoke()).recoveryCode, "history_ambiguous"); assert.equal(f.reads(), 0);
  assert.equal(f.ledger.lookup("saved").state, "UNKNOWN");
});

for (const [name, mutation] of Object.entries({ "incoming": { fromMe: false }, "different text": { body: "other" },
  "different chat": { id: { _serialized: "true_87654321@g.us_WRONG", remote: "87654321@g.us" } },
  "late identical": { timestamp: 1800000060 } })) test(`${name} cannot confirm`, async t => {
  const f = await fixture(t); f.setMessages([{ ...f.found, ...mutation }]);
  assert.equal((await f.invoke()).recoveryCode, "history_no_receipt");
  assert.equal(f.ledger.lookup("saved").state, "UNKNOWN");
});

test("provider ACK is read back and required", async t => {
  const f = await fixture(t); f.setMessages([{ ...f.found, ack: 0 }]);
  await assert.rejects(f.invoke(), error => error.code === "operation_evidence_mismatch");
  assert.equal(f.ledger.lookup("saved").state, "UNKNOWN");
});

test("wrong frozen envelope fails before any history read", async t => {
  const f = await fixture(t);
  await assert.rejects(f.invoke({ message: "changed" }), error => error.code === "operation_payload_conflict");
  assert.deepEqual(f.limits, []); assert.equal(f.reads(), 0);
});

test("missing completion window is not guessed from current time", async t => {
  const f = await fixture(t), record = f.ledger.read(f.ledger.key("saved"));
  delete record.completedAt; f.ledger.replace(record);
  await assert.rejects(f.invoke(), error => error.code === "operation_evidence_window_unavailable");
  assert.deepEqual(f.limits, []);
});

test("two overlapping saved operations cannot share a matching history message", async t => {
  const f = await fixture(t); await f.ledger.execute("competing", f.envelope, async () => null);
  await assert.rejects(f.invoke(), error => error.code === "operation_evidence_ambiguous");
  assert.equal(f.ledger.lookup("saved").state, "UNKNOWN");
  assert.equal(f.ledger.lookup("competing").state, "UNKNOWN");
});

test("concurrent clicks share one read and a timeout cannot bind a late result", async t => {
  let release, reads = 0;
  const gate = new Promise(resolve => { release = resolve; });
  const f = await fixture(t, { timeoutMs: 10, getChat: async () => ({ async fetchMessages() { reads++; await gate; return [f.found]; } }) });
  const results = await Promise.allSettled([f.invoke(), f.invoke()]);
  assert.equal(results.every(r => r.status === "rejected" && r.reason.code === "operation_history_timeout"), true);
  assert.equal(reads, 1); release(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.ledger.lookup("saved").state, "UNKNOWN"); assert.equal(f.sends(), 1);
});

test("direct LID history binds only after two trusted provider PN mappings", async t => {
  const lookedUp = [], chats = [], pn = "12345678901@c.us", lid = "987654321@lid";
  const f = await fixture(t, { kind: "send", getDirectMappings: async id => { lookedUp.push(id); return [{ pn, lid }]; },
    getChat: async id => { chats.push(id); return { fetchMessages: async () => [candidate] }; },
    getMessage: async () => candidate });
  const candidate = { ...f.found, id: { _serialized: `true_${lid}_MATCH`, remote: lid } };
  assert.equal((await f.invoke()).state, "SUCCEEDED");
  assert.deepEqual(chats, [lid]); assert.deepEqual(lookedUp, [pn, lid]); assert.equal(f.sends(), 1);
});

for (const mode of ["initial wrong PN", "mapping changed after history", "ambiguous provider mapping"])
  test(`${mode} cannot redirect a frozen direct operation`, async t => {
    const pn = "12345678901@c.us", lid = "987654321@lid"; let mappings = 0;
    const f = await fixture(t, { kind: "send", getDirectMappings: async () => {
      mappings++;
      if (mode === "ambiguous provider mapping") return [{ pn, lid }, { pn, lid: "87654321@lid" }];
      return [{ pn: mode === "initial wrong PN" || mappings > 1 ? "19876543210@c.us" : pn, lid }];
    }, getChat: async () => ({ fetchMessages: async () => [candidate] }), getMessage: async () => candidate });
    const candidate = { ...f.found, id: { _serialized: `true_${lid}_MATCH`, remote: lid } };
    await assert.rejects(f.invoke(), error => error.code.startsWith("operation_recipient_identity_"));
    assert.equal(f.ledger.lookup("saved").state, "UNKNOWN"); assert.equal(f.sends(), 1);
  });
