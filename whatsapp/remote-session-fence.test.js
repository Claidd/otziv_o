"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const { RemoteSessionFence } = require("./remote-session-fence");

function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-remote-fence-"));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const remote = { browserId: "fixture-browser", activeTargetIds: [] };
  const options = { directory, clientId: "fixture", profileId: "42", browserUrl: "http://browser_profile_42:9223",
    probe: async () => ({ ...remote }), lock: () => () => {} };
  const fence = new RemoteSessionFence(options);
  const reconcile = () => fence.reconcile("fixture", { expectedGeneration: fence.read()?.generation || "absent",
    expectedBrowserId: remote.browserId, actor: "fixture-operator", evidenceReference: "fixture-drain-1" });
  return { fence, remote, reconcile, options };
}

test("missing remote history requires explicit bootstrap and an actually empty remote profile", async t => {
  const f = fixture(t);
  await assert.rejects(f.fence.begin("fixture"), /bootstrap_required/);
  f.remote.activeTargetIds = ["old-owned-page"];
  await assert.rejects(f.reconcile(), /targets_still_active/);
  assert.equal(f.fence.read(), null);
  f.remote.activeTargetIds = []; await f.reconcile();
  const generation = await f.fence.begin("fixture");
  assert.equal(f.fence.read().state, "DIRTY"); assert.equal(f.fence.read().generation, generation);
});

test("a durable DIRTY marker never authorizes attach after process recreation", async t => {
  const f = fixture(t); await f.reconcile(); await f.fence.begin("fixture");
  const afterDeath = new RemoteSessionFence(f.options);
  await assert.rejects(afterDeath.begin("fixture"), /reconciliation_required/);
  f.remote.activeTargetIds = []; // An empty remote page list alone cannot erase history.
  await assert.rejects(afterDeath.begin("fixture"), /reconciliation_required/);
});

test("cleanup must match generation/browser and prove no active remote targets", async t => {
  const f = fixture(t); await f.reconcile(); const generation = await f.fence.begin("fixture");
  await assert.rejects(f.fence.complete("fixture", "stale-generation"), /generation_changed/);
  f.remote.browserId = "different-browser";
  await assert.rejects(f.fence.complete("fixture", generation), /cleanup_unproven/);
  f.remote.browserId = "fixture-browser"; f.remote.activeTargetIds = ["still-open"];
  await assert.rejects(f.fence.complete("fixture", generation), /cleanup_unproven/);
  f.remote.activeTargetIds = []; await f.fence.complete("fixture", generation);
  assert.equal(f.fence.read().state, "CLEAN");
});

test("operator reconciliation compares expected state and appends audit before allowing a new generation", async t => {
  const f = fixture(t); await f.reconcile(); await f.fence.begin("fixture");
  await assert.rejects(f.fence.reconcile("fixture", { expectedGeneration: "absent", expectedBrowserId: "fixture-browser",
    actor: "operator", evidenceReference: "ticket-1" }), /conflict/);
  await f.reconcile();
  const lines = fs.readFileSync(f.fence.auditFile, "utf8").trim().split("\n").map(JSON.parse);
  assert.equal(lines.length, 2); assert.equal(lines[1].action, "RECONCILE");
  assert.equal(lines[1].generation, f.fence.read().generation);
  assert.equal(lines[1].evidenceReference, "fixture-drain-1");
});
