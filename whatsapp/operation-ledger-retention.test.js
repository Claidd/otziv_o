"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { OperationLedger, envelopeHash, DAY_MS } = require("./operation-ledger");
const envelope = { clientId: "client", kind: "send-group", destination: "fixture@g.us", message: "fixture" };
const other = { ...envelope, message: "other fixture" };
const third = { ...envelope, message: "third fixture" };
function fixture(t, options = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-ledger-retention-"));
  const clock = { now: 1_000_000 };
  const ledgers = [];
  const open = (extra = {}) => {
    const ledger = new OperationLedger(directory, { now: () => clock.now, retentionDays: 7, ...options, ...extra });
    ledgers.push(ledger); return ledger;
  };
  t.after(async () => { for (const ledger of ledgers) await ledger.close().catch(() => {}); fs.rmSync(directory, { recursive: true, force: true }); });
  return { open, clock, directory };
}

test("old SUCCEEDED records become tombstones that replay without resending and free capacity", async t => {
  const { open, clock, directory } = fixture(t, { maxRecords: 2 });
  const ledger = open();
  await ledger.execute("confirmed", envelope, async () => "message-1");
  await ledger.execute("ambiguous", third, async () => null);
  await assert.rejects(ledger.execute("third", other, assert.fail), { code: "operation_ledger_full" });
  const before = ledger.lookup("confirmed");
  clock.now += 8 * DAY_MS;
  assert.deepEqual(ledger.maintain(), { recovered: false, pruned: 1, remaining: 0 });
  assert.equal(fs.existsSync(ledger.filename(ledger.key("confirmed"))), false);
  assert.equal(fs.existsSync(path.join(directory, "tombstones")), true);
  assert.deepEqual(ledger.lookup("confirmed"), before);
  assert.deepEqual(await ledger.execute("confirmed", envelope, assert.fail), { ...before, replayed: true });
  await assert.rejects(ledger.execute("confirmed", other, assert.fail), { code: "operation_payload_conflict" });
  // UNKNOWN is never compacted, regardless of age.
  assert.equal((await ledger.execute("ambiguous", third, assert.fail)).state, "UNKNOWN");
  const metrics = ledger.metrics();
  assert.equal(metrics.records, 1); assert.equal(metrics.prunedTotal, 1); assert.equal(metrics.retentionDays, 7);
  assert.equal((await ledger.execute("third", other, async () => "message-3")).state, "SUCCEEDED");
  await ledger.close();
  const restarted = open();
  assert.equal(restarted.metrics().records, 2);
  assert.deepEqual(await restarted.execute("confirmed", envelope, assert.fail), { ...before, replayed: true });
  assert.equal(restarted.lookup("confirmed").envelopeHash, envelopeHash(envelope));
});

test("recent, overlapping-sibling and disabled-retention records are kept in full", async t => {
  const { open, clock } = fixture(t);
  const ledger = open();
  await ledger.execute("old-sibling", envelope, async () => "message-1");
  clock.now += 1000;
  await ledger.execute("unknown-sibling", envelope, async () => null);
  await ledger.execute("old-alone", other, async () => "message-2");
  clock.now += 6 * DAY_MS;
  await ledger.execute("recent", other, async () => "message-3");
  clock.now += 2 * DAY_MS;
  // A receipt of "old-sibling" could still fall into the UNKNOWN sibling's window.
  assert.equal(ledger.maintain().pruned, 1);
  assert.equal(fs.existsSync(ledger.filename(ledger.key("old-alone"))), false);
  for (const id of ["old-sibling", "unknown-sibling", "recent"]) assert.equal(fs.existsSync(ledger.filename(ledger.key(id))), true);
  await ledger.close();
  const disabled = open({ retentionDays: 0 });
  clock.now += 365 * DAY_MS;
  assert.equal(disabled.maintain().pruned, 0);
  assert.equal(disabled.metrics().retentionDays, 0);
});

test("a crash between durable tombstones and record removal leaves a safe duplicate", async t => {
  let armed = false;
  const io = Object.create(fs);
  io.unlinkSync = (...args) => armed && String(args[0]).endsWith(".json") ? (() => { throw Object.assign(new Error("io"), { code: "EIO" }); })() : fs.unlinkSync(...args);
  const { open, clock } = fixture(t, { io });
  const ledger = open();
  await ledger.execute("confirmed", envelope, async () => "message-1");
  clock.now += 8 * DAY_MS; armed = true;
  assert.throws(() => ledger.maintain(), { code: "operation_prune_not_durable" });
  assert.equal(ledger.admission().code, "operation_ledger_unavailable");
  assert.equal((await ledger.execute("confirmed", envelope, assert.fail)).messageId, "message-1");
  armed = false; await ledger.close();
  const restarted = open();
  assert.equal(restarted.metrics().records, 1);
  assert.equal(restarted.maintain().pruned, 1);
  assert.equal((await restarted.execute("confirmed", envelope, assert.fail)).messageId, "message-1");
});

test("tombstone write failure keeps the full record and health recovery resumes compaction", async t => {
  let armed = false;
  const io = Object.create(fs);
  io.renameSync = (...args) => armed && String(args[1]).includes(`${path.sep}tombstones${path.sep}`)
    ? (() => { throw Object.assign(new Error("disk full"), { code: "ENOSPC" }); })() : fs.renameSync(...args);
  const { open, clock } = fixture(t, { io });
  const ledger = open();
  await ledger.execute("confirmed", envelope, async () => "message-1");
  clock.now += 8 * DAY_MS; armed = true;
  assert.throws(() => ledger.maintain(), { code: "operation_tombstone_not_durable" });
  assert.equal(fs.existsSync(ledger.filename(ledger.key("confirmed"))), true);
  assert.equal(ledger.lookup("confirmed").messageId, "message-1");
  armed = false;
  assert.deepEqual(ledger.maintain(), { recovered: true, pruned: 1, remaining: 0 });
  assert.equal(ledger.lookup("confirmed").messageId, "message-1");
  assert.equal(ledger.metrics().healthRecoveredTotal, 1);
});

test("a corrupt tombstone shard fails closed instead of authorizing a resend", async t => {
  const { open, clock, directory } = fixture(t);
  const ledger = open();
  await ledger.execute("confirmed", envelope, async () => "message-1");
  clock.now += 8 * DAY_MS; ledger.maintain();
  const shard = path.join(directory, "tombstones", `${ledger.key("confirmed").slice(0, 3)}.json`);
  fs.writeFileSync(shard, "{\"version\":");
  await assert.rejects(ledger.execute("confirmed", envelope, assert.fail), { code: "operation_record_unreadable" });
  assert.equal(ledger.metrics().autoRecoveryBlocked, true);
  clock.now += DAY_MS;
  assert.equal(ledger.maintain().recovered, false);
  assert.equal(ledger.admission().acceptingNew, false);
});

test("compaction is bounded per pass and continues on the next pass", async t => {
  const { open, clock } = fixture(t, { pruneBatchRecords: 2 });
  const ledger = open();
  for (let i = 0; i < 5; i++) await ledger.execute(`operation-${i}`, { ...envelope, message: `text ${i}` }, async () => `message-${i}`);
  clock.now += 8 * DAY_MS;
  assert.equal(ledger.maintain().pruned, 2);
  assert.equal(ledger.maintain().pruned, 2);
  assert.equal(ledger.maintain().pruned, 1);
  assert.equal(ledger.metrics().records, 0);
  for (let i = 0; i < 5; i++) assert.equal(ledger.lookup(`operation-${i}`).messageId, `message-${i}`);
});

test("readiness closes at the utilization threshold while admission still accepts", async t => {
  const { open } = fixture(t, { maxRecords: 20 });
  const ledger = open();
  for (let i = 0; i < 19; i++) await ledger.execute(`operation-${i}`, { ...envelope, message: `text ${i}` }, async () => `message-${i}`);
  assert.deepEqual(ledger.readiness(), { ready: false, code: "operation_ledger_near_capacity", capacityReady: false });
  assert.equal(ledger.admission().acceptingNew, true);
  assert.equal(ledger.metrics().readyUtilizationThreshold, 0.95);
  const relaxed = open({ readyMaxUtilization: 1 });
  assert.equal(relaxed.metrics().readinessCode, "operation_writer_locked");
});
