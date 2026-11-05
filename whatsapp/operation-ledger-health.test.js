"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { OperationLedger, envelopeHash, interruptedClaim } = require("./operation-ledger");
const envelope = { clientId: "client", kind: "send", destination: "79990000000@c.us", message: "fixture" };
const diskError = () => { throw Object.assign(new Error("disk full"), { code: "ENOSPC" }); };
function fixture(t, options = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-ledger-health-"));
  const clock = { now: 1_000_000 };
  const ledgers = [];
  const open = (extra = {}) => {
    const ledger = new OperationLedger(directory, { now: () => clock.now, ...options, ...extra });
    ledgers.push(ledger); return ledger;
  };
  t.after(async () => { for (const ledger of ledgers) await ledger.close().catch(() => {}); fs.rmSync(directory, { recursive: true, force: true }); });
  return { open, clock, directory };
}

test("a failed claim reports dispatched:false and health recovery releases only that claim", async t => {
  let armed = false;
  const io = Object.create(fs);
  io.writeFileSync = (...args) => armed ? diskError() : fs.writeFileSync(...args);
  const { open, clock } = fixture(t, { io });
  const ledger = open();
  armed = true; let sends = 0;
  await assert.rejects(ledger.execute("operation-1", envelope, async () => { sends++; return "message"; }),
    error => error.code === "operation_claim_failed" && error.dispatched === false);
  await assert.rejects(ledger.execute("operation-1", envelope, assert.fail),
    error => error.code === "operation_ledger_unavailable" && error.dispatched === false);
  await assert.rejects(ledger.execute("operation-2", envelope, assert.fail),
    error => error.code === "operation_ledger_unavailable" && error.dispatched === false);
  assert.equal(ledger.maintain().recovered, false); // storage still fails the probe
  armed = false;
  assert.equal(ledger.maintain().recovered, false); // retry is rate limited
  clock.now += 60_000;
  assert.equal(ledger.maintain().recovered, true);
  assert.equal(ledger.admission().acceptingNew, true);
  assert.equal((await ledger.execute("operation-1", envelope, async () => { sends++; return "message-1"; })).state, "SUCCEEDED");
  assert.equal(sends, 1);
  const metrics = ledger.metrics();
  assert.equal(metrics.persistenceFailures, 1); assert.equal(metrics.healthRecoveredTotal, 1);
  assert.equal(metrics.healthRecoveryFailures, 1); assert.equal(metrics.autoRecoveryBlocked, false);
});

test("a failed claim whose file content is not this process's own bytes stays fenced", async t => {
  let armed = false;
  const io = Object.create(fs);
  io.fsyncSync = (...args) => armed ? diskError() : fs.fsyncSync(...args);
  const { open, clock } = fixture(t, { io });
  const ledger = open();
  armed = true;
  await assert.rejects(ledger.execute("operation-1", envelope, assert.fail), { code: "operation_claim_failed" });
  armed = false;
  fs.writeFileSync(ledger.filename(ledger.key("operation-1")), "{\"unexpected\":true}");
  clock.now += 60_000;
  assert.equal(ledger.maintain().recovered, false);
  assert.equal(ledger.metrics().autoRecoveryBlocked, true);
  assert.equal(fs.readFileSync(ledger.filename(ledger.key("operation-1")), "utf8"), "{\"unexpected\":true}");
});

test("a result write failure after the provider call reports dispatched:true and recovery stores that exact result", async t => {
  let armed = false;
  const io = Object.create(fs);
  io.renameSync = (...args) => armed ? diskError() : fs.renameSync(...args);
  const { open, clock } = fixture(t, { io });
  const ledger = open();
  armed = true; let sends = 0;
  await assert.rejects(ledger.execute("operation-1", envelope, async () => { sends++; return "message-1"; }),
    error => error.code === "operation_result_not_durable" && error.dispatched === true);
  assert.equal(ledger.lookup("operation-1").state, "UNKNOWN");
  armed = false; clock.now += 60_000;
  assert.equal(ledger.maintain().recovered, true);
  assert.equal(ledger.lookup("operation-1").messageId, "message-1");
  assert.equal((await ledger.execute("operation-1", envelope, assert.fail)).state, "SUCCEEDED");
  assert.equal(sends, 1);
});

test("follower, restore fence and unreadable evidence never claim non-dispatch or auto-recover", async t => {
  const { open, clock } = fixture(t);
  const writer = open();
  const follower = open();
  await assert.rejects(follower.execute("operation-1", envelope, assert.fail),
    error => error.code === "operation_writer_locked" && error.dispatched === undefined);
  await writer.close();
  const fenced = open({ restoreId: "restore-1" });
  await assert.rejects(fenced.execute("operation-1", envelope, assert.fail),
    error => error.code === "operation_recovery_required" && error.dispatched === undefined);
  await fenced.close();
  const { open: openOther, clock: otherClock } = fixture(t);
  const ledger = openOther();
  await ledger.execute("known", envelope, async () => "message-1");
  fs.writeFileSync(ledger.filename(ledger.key("known")), "{\"version\":2}");
  await assert.rejects(ledger.execute("known", envelope, assert.fail),
    error => error.code === "operation_record_unreadable" && error.dispatched === undefined);
  otherClock.now += 60_000; clock.now += 60_000;
  assert.equal(ledger.maintain().recovered, false);
  assert.equal(ledger.metrics().autoRecoveryBlocked, true);
});

test("interrupted claim prefixes are recognized exactly", () => {
  const key = "a".repeat(64), hash = "b".repeat(64);
  const claim = JSON.stringify({ version: 1, key, envelopeHash: hash, state: "RUNNING", startedAt: 1234 });
  for (let length = 0; length < claim.length; length++) {
    const torn = interruptedClaim(claim.slice(0, length), key);
    assert.ok(torn, `prefix ${length}`);
    assert.equal(torn.envelopeHash, length >= claim.indexOf(hash) + 64 ? hash : null);
  }
  assert.ok(interruptedClaim(`${claim.slice(0, 40)}\u0000\u0000\u0000`, key));
  assert.equal(interruptedClaim(claim, key), null);
  assert.equal(interruptedClaim(`${claim.slice(0, 40)}\u0000x`, key), null);
  assert.equal(interruptedClaim(claim.replace(key, "c".repeat(64)).slice(0, 80), key), null);
  assert.equal(interruptedClaim("{\"version\":2", key), null);
  const recovered = JSON.stringify({ version: 1, key, envelopeHash: hash, state: "UNKNOWN", startedAt: 1 });
  assert.equal(interruptedClaim(recovered.slice(0, -2), key).envelopeHash, hash);
});

test("startup repairs a torn claim into durable UNKNOWN and keeps other corruption fenced", async t => {
  const { open, directory } = fixture(t);
  const probe = open(); const withHash = probe.key("with-hash"), empty = probe.key("empty"), corrupt = probe.key("corrupt");
  await probe.close();
  const hash = envelopeHash(envelope);
  fs.writeFileSync(path.join(directory, `${withHash}.json`),
    JSON.stringify({ version: 1, key: withHash, envelopeHash: hash, state: "RUNNING", startedAt: 5 }).slice(0, -3));
  fs.writeFileSync(path.join(directory, `${empty}.json`), "");
  const ledger = open();
  assert.equal(ledger.metrics().repairedInterruptedClaims, 2);
  assert.equal(ledger.admission().acceptingNew, true);
  assert.equal((await ledger.execute("with-hash", envelope, assert.fail)).state, "UNKNOWN");
  await assert.rejects(ledger.execute("with-hash", { ...envelope, message: "other" }, assert.fail), { code: "operation_payload_conflict" });
  const hashless = await ledger.execute("empty", { ...envelope, message: "anything" }, assert.fail);
  assert.equal(hashless.state, "UNKNOWN"); assert.equal(hashless.envelopeHash, null);
  await assert.rejects(ledger.reconcile("empty", envelope, "message", async () => true), { code: "operation_envelope_unavailable" });
  await ledger.close();
  fs.writeFileSync(path.join(directory, `${corrupt}.json`), "{\"version\":1,\"key\":\"other\"}");
  const fenced = open();
  assert.equal(fenced.admission().code, "operation_ledger_unavailable");
  assert.equal(fenced.metrics().autoRecoveryBlocked, true);
  assert.equal(fs.readFileSync(path.join(directory, `${corrupt}.json`), "utf8"), "{\"version\":1,\"key\":\"other\"}");
});

test("an unacknowledged provider message stays UNKNOWN with evidence until a server ACK resolves it", async t => {
  const { open, clock } = fixture(t);
  const ledger = open();
  const result = await ledger.execute("operation-1", envelope, async () => ({ messageId: "provider-1", ack: 0, sentAt: clock.now }));
  assert.equal(result.state, "UNKNOWN"); assert.equal(result.providerAck, 0); assert.equal(result.messageId, null);
  const record = ledger.read(ledger.key("operation-1"));
  assert.equal(record.providerMessageId, "provider-1");
  assert.deepEqual(record.receiptCandidates.map(candidate => candidate.messageId), ["provider-1"]);
  assert.equal((await ledger.execute("operation-1", envelope, assert.fail)).state, "UNKNOWN");
  assert.equal(ledger.confirmProviderAck("foreign", 3), false);
  assert.equal(ledger.confirmProviderAck("provider-1", 0), false);
  await ledger.close();
  const restarted = open();
  assert.equal(restarted.metrics().ackPending, 1);
  assert.equal(restarted.confirmProviderAck("provider-1", 2), true);
  const confirmed = restarted.lookup("operation-1");
  assert.equal(confirmed.state, "SUCCEEDED"); assert.equal(confirmed.messageId, "provider-1"); assert.equal(confirmed.providerAck, 2);
  assert.equal(restarted.metrics().ackPending, 0);
});

test("acknowledged and legacy provider results stay SUCCEEDED; reconciliation respects the provider ID", async t => {
  const { open, clock } = fixture(t);
  const ledger = open();
  const acked = await ledger.execute("acked", envelope, async () => ({ messageId: "provider-1", ack: 1 }));
  assert.equal(acked.state, "SUCCEEDED"); assert.equal(acked.providerAck, 1);
  const legacy = await ledger.execute("legacy", envelope, async () => "provider-2");
  assert.equal(legacy.state, "SUCCEEDED"); assert.equal("providerAck" in legacy, false);
  await ledger.execute("pending", { ...envelope, message: "pending" }, async () => ({ messageId: "provider-3", ack: 0, sentAt: clock.now }));
  await assert.rejects(ledger.reconcile("pending", { ...envelope, message: "pending" }, "different", async () => true),
    { code: "operation_evidence_mismatch" });
  const resolved = await ledger.reconcile("pending", { ...envelope, message: "pending" }, "provider-3", async () => true,
    { sentAt: clock.now, ack: 3 });
  assert.equal(resolved.state, "SUCCEEDED"); assert.equal(resolved.providerAck, 3);
});
