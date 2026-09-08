"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { OperationLedger, envelopeHash } = require("./operation-ledger");
const envelope = { clientId: "client", kind: "send", destination: "fixture", message: "fixture" };
function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-ledger-recovery-")); const ledgers = [];
  const open = options => { const ledger = new OperationLedger(directory, options); ledgers.push(ledger); return ledger; };
  t.after(async () => { for (const ledger of ledgers) await ledger.close(); fs.rmSync(directory, { recursive: true, force: true }); });
  return { open, directory };
}
function manifest(restoreId, ids = ["missing-sent"]) {
  return { restoreId, authority: "producer-operation-export", watermark: "all-producers-fenced-at-2026-09-08T00:00:00Z",
    operations: ids.map(operationId => ({ operationId, envelopeHash: envelopeHash(envelope), startedAt: 1_000 })) };
}
const verified = async ({ manifest: value, manifestHash }) => ({ complete: true, restoreId: value.restoreId,
  authority: value.authority, watermark: value.watermark, manifestHash });

test("lagging backup cannot turn a missing sent ID into not-found; restart retains the restore fence", async t => {
  const { open } = fixture(t); const before = open();
  await before.execute("known-old", envelope, async () => "known-message"); await before.close();
  const restored = open({ restoreId: "recovery-1" });
  assert.equal(restored.admission().code, "operation_recovery_required");
  assert.throws(() => restored.lookup("missing-sent"), { code: "operation_recovery_required", statusCode: 503 });
  await assert.rejects(restored.execute("missing-sent", envelope, assert.fail), { code: "operation_recovery_required" });
  assert.equal((await restored.execute("known-old", envelope, assert.fail)).state, "SUCCEEDED");
  await restored.close();
  const restarted = open(); assert.equal(restarted.admission().code, "operation_recovery_required");
  await assert.rejects(restarted.reconcileRecovery(manifest("recovery-1"), async () => ({ complete: true })), { code: "operation_recovery_coverage_unverified" });
  assert.equal(restarted.admission().code, "operation_recovery_required");
  await restarted.reconcileRecovery(manifest("recovery-1"), verified);
  assert.equal((await restarted.execute("missing-sent", envelope, assert.fail)).state, "UNKNOWN");
  assert.equal((await restarted.execute("actually-new", envelope, async () => "new-message")).state, "SUCCEEDED");
  await restarted.close();
  const completed = open({ restoreId: "recovery-1" }); assert.equal(completed.metrics().recoveryRequired, false); await completed.close();
  // A copied COMPLETE marker is not evidence that the next raw restore is current.
  const secondRestore = open({ restoreId: "recovery-2" });
  assert.equal(secondRestore.admission().code, "operation_recovery_required");
  assert.throws(() => secondRestore.lookup("lost-again"), { code: "operation_recovery_required" });
});

test("partial recovery persistence failure never clears the durable barrier and is safely resumable", async t => {
  const { open } = fixture(t); let armed = false, writes = 0;
  const io = Object.create(fs);
  io.openSync = (...args) => {
    if (armed && args[1] === "wx" && /^[a-f0-9]{64}\.json$/.test(path.basename(String(args[0]))) && ++writes === 2) {
      throw Object.assign(new Error("no space"), { code: "ENOSPC" });
    }
    return fs.openSync(...args);
  };
  const restored = open({ restoreId: "partial", io }); armed = true;
  await assert.rejects(restored.reconcileRecovery(manifest("partial", ["one", "two"]), verified), { code: "operation_claim_failed" });
  armed = false; assert.equal(restored.metrics().recoveryRequired, true); await restored.close();
  const restarted = open();
  assert.equal(restarted.lookup("one").state, "UNKNOWN");
  assert.throws(() => restarted.lookup("two"), { code: "operation_recovery_required" });
  await restarted.reconcileRecovery(manifest("partial", ["one", "two"]), verified);
  for (const id of ["one", "two"]) assert.equal((await restarted.execute(id, envelope, assert.fail)).state, "UNKNOWN");
});

test("failed COMPLETE marker persistence retains fencing and imported protection on restart", async t => {
  const { open } = fixture(t); let armed = false;
  const io = Object.create(fs);
  io.renameSync = (...args) => {
    if (armed && path.basename(String(args[1])) === ".recovery.json") throw Object.assign(new Error("no space"), { code: "ENOSPC" });
    return fs.renameSync(...args);
  };
  const ledger = open({ restoreId: "marker-failure", io }); armed = true;
  await assert.rejects(ledger.reconcileRecovery(manifest("marker-failure"), verified), { code: "operation_recovery_not_durable" });
  armed = false; await ledger.close();
  const restarted = open(); assert.equal(restarted.metrics().recoveryRequired, true);
  assert.equal(restarted.lookup("missing-sent").state, "UNKNOWN");
  await restarted.reconcileRecovery(manifest("marker-failure"), verified);
  assert.equal(restarted.admission().acceptingNew, true);
});

test("recovery imports every authoritative ID even above capacity, preserving UNKNOWN and successful results", async t => {
  const { open } = fixture(t); const original = open({ maxRecords: 1 });
  await original.execute("known", envelope, async () => "known-message"); await original.close();
  const restored = open({ restoreId: "over-capacity", maxRecords: 1 });
  await restored.reconcileRecovery(manifest("over-capacity", ["known", "missing"]), verified);
  assert.equal(restored.lookup("known").messageId, "known-message");
  assert.equal(restored.lookup("missing").state, "UNKNOWN");
  assert.equal(restored.metrics().records, 2); assert.equal(restored.metrics().remaining, 0);
  assert.equal(restored.admission().code, "operation_ledger_full");
});
