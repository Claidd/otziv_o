"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { OperationLedger, envelopeHash } = require("./operation-ledger");
const { OperationLedgerIndex } = require("./operation-ledger-index");
const envelope = { clientId: "client", kind: "send", destination: "fixture", message: "fixture" };
function fixture(t, options) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-ledger-capacity-"));
  const ledgers = [];
  const open = (extra = {}) => { const ledger = new OperationLedger(directory, { ...options, ...extra }); ledgers.push(ledger); return ledger; };
  t.after(async () => { for (const ledger of ledgers) await ledger.close(); fs.rmSync(directory, { recursive: true, force: true }); });
  return { open, directory };
}
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }

test("one startup scan, constant-time admission, durable count and UNKNOWN survive writer restart", async t => {
  let scans = 0;
  const io = Object.create(fs); io.readdirSync = (...args) => { scans++; return fs.readdirSync(...args); };
  const { open } = fixture(t, { maxRecords: 3, now: () => 100_000, io });
  const ledger = open(); assert.equal(scans, 1);
  await ledger.execute("known", envelope, async () => "message");
  await ledger.execute("ambiguous", envelope, async () => null);
  await ledger.execute("known-2", envelope, async () => "message-2");
  for (let i = 0; i < 1_000; i++) { ledger.admission(); ledger.metrics(); }
  assert.equal(scans, 1);
  assert.equal(ledger.metrics().remaining, 0); assert.equal(ledger.metrics().unknown, 1);
  assert.equal(ledger.metrics().oldestUnknownAgeSeconds, 0);
  await assert.rejects(ledger.execute("too-many", envelope, assert.fail), { code: "operation_ledger_full" });
  assert.equal(ledger.metrics().admissionRejected, 1);
  await ledger.close();
  const restarted = open({ now: () => 105_000 });
  assert.equal(scans, 2); assert.equal(restarted.metrics().records, 3);
  assert.equal(restarted.metrics().oldestUnknownAgeSeconds, 5);
  assert.equal((await restarted.execute("known", envelope, assert.fail)).state, "SUCCEEDED");
  assert.equal((await restarted.execute("ambiguous", envelope, assert.fail)).state, "UNKNOWN");
  await restarted.reconcile("ambiguous", envelope, "verified", async () => true);
  assert.equal(restarted.metrics().unknown, 0); assert.equal(restarted.metrics().oldestUnknownAgeSeconds, null);
});

test("second writer cannot claim or reconcile; graceful close keeps lock until provider result is durable", async t => {
  const { open } = fixture(t);
  const writer = open(), pending = deferred();
  const execution = writer.execute("active", envelope, () => pending.promise);
  const follower = open();
  assert.equal(follower.metrics().writer, false);
  await assert.rejects(follower.execute("new", envelope, assert.fail), { code: "operation_writer_locked" });
  assert.equal((await follower.execute("active", envelope, assert.fail)).state, "UNKNOWN");
  await assert.rejects(follower.reconcile("active", envelope, "verified", async () => true), { code: "operation_writer_locked" });
  let closed = false; const close = writer.close().then(() => { closed = true; });
  await Promise.resolve(); assert.equal(closed, false);
  assert.equal(open().metrics().writer, false);
  pending.resolve("delivered"); await execution; await close;
  const successor = open(); assert.equal(successor.metrics().writer, true);
  assert.equal(successor.lookup("active").messageId, "delivered");
  await successor.execute("new", envelope, async () => "new-message");
});

test("a known record removed while running fails closed instead of authorizing another send", async t => {
  const { open } = fixture(t); const ledger = open();
  await ledger.execute("known", envelope, async () => "message");
  fs.unlinkSync(ledger.filename(ledger.key("known")));
  await assert.rejects(ledger.execute("known", envelope, assert.fail), { code: "operation_record_missing" });
  assert.equal(ledger.admission().acceptingNew, false);
  assert.equal(ledger.metrics().readFailures, 1);
});

for (const stage of ["create", "write", "fsync", "rename"]) {
  test(`persistence ${stage} failure closes admission and preserves existing replay`, async t => {
    let armed = false;
    const io = Object.create(fs);
    const diskError = () => { throw Object.assign(new Error("disk full"), { code: "ENOSPC" }); };
    if (stage === "create") io.openSync = (...args) => armed && String(args[0]).endsWith(".json") && args[1] === "wx" ? diskError() : fs.openSync(...args);
    if (stage === "write") io.writeFileSync = (...args) => armed ? diskError() : fs.writeFileSync(...args);
    if (stage === "fsync") io.fsyncSync = (...args) => armed ? diskError() : fs.fsyncSync(...args);
    if (stage === "rename") io.renameSync = (...args) => armed ? diskError() : fs.renameSync(...args);
    const { open } = fixture(t, { io }); const ledger = open();
    await ledger.execute("old", envelope, async () => "old-message");
    armed = true; let sends = 0;
    await assert.rejects(ledger.execute("new", envelope, async () => { sends++; return "delivered"; }),
      { code: stage === "rename" ? "operation_result_not_durable" : "operation_claim_failed" });
    armed = false;
    assert.equal(sends, stage === "rename" ? 1 : 0);
    assert.equal(ledger.metrics().persistenceFailures, 1);
    assert.equal(ledger.admission().acceptingNew, false);
    assert.equal((await ledger.execute("old", envelope, assert.fail)).state, "SUCCEEDED");
    await assert.rejects(ledger.execute("next", envelope, assert.fail), { code: "operation_ledger_unavailable" });
    await ledger.close();
    const restarted = open();
    if (stage === "rename") assert.equal((await restarted.execute("new", envelope, assert.fail)).state, "UNKNOWN");
    if (stage === "write") assert.equal(restarted.admission().acceptingNew, false);
  });
}

test("99,999 metadata entries support bounded observations without creating record files", () => {
  const index = new OperationLedgerIndex();
  for (let i = 0; i < 99_999; i++) index.set(String(i), "UNKNOWN", i);
  assert.equal(index.size, 99_999); assert.equal(index.unknown, 99_999); assert.equal(index.oldestUnknown(), 0);
  for (let i = 0; i < 99_999; i++) index.set(String(i), "SUCCEEDED", i);
  assert.equal(index.oldestUnknown(), null); assert.equal(index.unknown, 0);
  assert.equal(index.heap.length, 0); assert.equal(index.positions.size, 0);
  index.set("last", "UNKNOWN", 7); index.set("first", "UNKNOWN", 2); index.set("middle", "UNKNOWN", 4);
  index.set("first", "SUCCEEDED", 2); assert.equal(index.oldestUnknown(), 4);
});

