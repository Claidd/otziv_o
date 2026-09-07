"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { OperationLedger, operationIdFromRequest, envelopeHash } = require("./operation-ledger");
const envelope = { clientId: "fixture-client", kind: "send-group", destination: "fixture-group@g.us", message: "fixture message" };
function fixture(t, options) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-ledger-test-"));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  return new OperationLedger(directory, options);
}
function deferred() { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; }

test("claim is durable before send and contains no raw operation ID, body or recipient", async (t) => {
  const ledger = fixture(t);
  await ledger.execute("fixture-operation", envelope, async () => {
    const content = fs.readFileSync(ledger.filename(ledger.key("fixture-operation")), "utf8");
    assert.equal(JSON.parse(content).state, "RUNNING");
    for (const raw of ["fixture-operation", envelope.message, envelope.destination]) assert.equal(content.includes(raw), false);
    return "fixture-message-id";
  });
  assert.equal(ledger.lookup("fixture-operation").state, "SUCCEEDED");
});

test("concurrent retries and another ledger instance cannot issue a second send", async (t) => {
  const ledger = fixture(t);
  const provider = deferred();
  let calls = 0;
  const first = ledger.execute("operation-1", envelope, () => { calls++; return provider.promise; });
  assert.equal((await ledger.execute("operation-1", envelope, assert.fail)).state, "RUNNING");
  const other = new OperationLedger(ledger.directory);
  assert.equal((await other.execute("operation-1", envelope, assert.fail)).state, "UNKNOWN");
  provider.resolve("message-1"); await first;
  assert.deepEqual(await other.execute("operation-1", envelope, assert.fail), { state: "SUCCEEDED", messageId: "message-1", envelopeHash: envelopeHash(envelope), replayed: true });
  assert.equal(calls, 1);
});

test("operation identity covers client, operation kind, destination and content", async (t) => {
  const ledger = fixture(t);
  await ledger.execute("operation-1", envelope, async () => "message-1");
  for (const changed of [{ clientId: "other" }, { kind: "send" }, { destination: "other@g.us" }, { message: "different" }]) {
    await assert.rejects(ledger.execute("operation-1", { ...envelope, ...changed }, assert.fail), (error) => error.code === "operation_payload_conflict");
  }
});

test("provider rejection and missing confirmation become UNKNOWN and never automatically replay", async (t) => {
  const ledger = fixture(t);
  await ledger.execute("rejection", envelope, async () => { throw new Error("private provider diagnostic"); });
  await ledger.execute("missing-id", envelope, async () => null);
  for (const id of ["rejection", "missing-id"]) {
    const recovered = new OperationLedger(ledger.directory).lookup(id);
    assert.equal(recovered.state, "UNKNOWN");
    assert.equal(recovered.envelopeHash, envelopeHash(envelope));
    assert.equal((await ledger.execute(id, envelope, assert.fail)).state, "UNKNOWN");
  }
});

test("a corrupt or incomplete claim fails closed and is not overwritten", async (t) => {
  const ledger = fixture(t);
  const file = ledger.filename(ledger.key("operation-1"));
  fs.writeFileSync(file, '{"version":');
  await assert.rejects(ledger.execute("operation-1", envelope, assert.fail), (error) => error.code === "operation_record_unreadable");
  assert.equal(fs.readFileSync(file, "utf8"), '{"version":');
  assert.equal(ledger.healthy, false);
});

test("result persistence failure after provider success leaves non-retryable UNKNOWN", async (t) => {
  const ledger = fixture(t);
  ledger.replace = () => { throw new Error("simulated disk failure"); };
  await assert.rejects(ledger.execute("operation-1", envelope, async () => "message-1"), /disk failure/);
  const restarted = new OperationLedger(ledger.directory);
  assert.equal((await restarted.execute("operation-1", envelope, assert.fail)).state, "UNKNOWN");
});

test("ledger capacity rejects new sends without expiring confirmed replay protection", async (t) => {
  const ledger = fixture(t, { maxRecords: 1 });
  await ledger.execute("operation-1", envelope, async () => "message-1");
  await assert.rejects(ledger.execute("operation-2", envelope, assert.fail), (error) => error.code === "operation_ledger_full");
  assert.equal((await ledger.execute("operation-1", envelope, assert.fail)).state, "SUCCEEDED");
});

test("readiness rejection happens before claim but does not block confirmed replay", async (t) => {
  const ledger = fixture(t);
  await assert.rejects(ledger.execute("operation-1", envelope, assert.fail, { canStart: () => false }), (error) => error.code === "gateway_not_ready");
  assert.throws(() => ledger.lookup("operation-1"), (error) => error.code === "operation_not_found");
  await ledger.execute("operation-1", envelope, async () => "message-1");
  assert.equal((await ledger.execute("operation-1", envelope, assert.fail, { canStart: () => false })).state, "SUCCEEDED");
});

test("an abrupt process exit after possible send never causes another send on restart", async (t) => {
  const ledger = fixture(t);
  const modulePath = path.join(__dirname, "operation-ledger.js");
  const script = `const {OperationLedger}=require(process.argv[1]); const ledger=new OperationLedger(process.argv[2]); ledger.execute('crash-operation',JSON.parse(process.argv[3]),async()=>process.exit(77));`;
  const child = spawn(process.execPath, ["-e", script, modulePath, ledger.directory, JSON.stringify(envelope)], { stdio: "ignore" });
  const exitCode = await new Promise((resolve, reject) => { child.once("exit", resolve); child.once("error", reject); });
  assert.equal(exitCode, 77);
  assert.equal((await ledger.execute("crash-operation", envelope, assert.fail)).state, "UNKNOWN");
});

test("reconciliation requires positive matching evidence and cannot resolve live work", async (t) => {
  const ledger = fixture(t);
  const pending = deferred();
  const running = ledger.execute("operation-1", envelope, () => pending.promise);
  await assert.rejects(ledger.reconcile("operation-1", envelope, "message-1", async () => true), (error) => error.code === "operation_running");
  pending.resolve(null); await running;
  await assert.rejects(ledger.reconcile("operation-1", envelope, "message-1", async () => false), (error) => error.code === "operation_evidence_mismatch");
  assert.equal((await ledger.reconcile("operation-1", envelope, "message-1", async () => true)).state, "SUCCEEDED");
});

test("operation header/body validation rejects conflicts and filesystem-shaped IDs", () => {
  assert.equal(operationIdFromRequest({ get: () => undefined, body: {} }), null);
  assert.equal(operationIdFromRequest({ get: () => "operation-1", body: { operationId: "operation-1" } }), "operation-1");
  for (const value of ["../escape", "", " space", {}, "x".repeat(161)]) {
    assert.throws(() => operationIdFromRequest({ get: () => undefined, body: { operationId: value } }));
  }
  assert.throws(() => operationIdFromRequest({ get: () => "operation-1", body: { operationId: "operation-2" } }));
});
