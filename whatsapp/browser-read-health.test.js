"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { BrowserReadHealth, assessBrowserReadFailure, probeBrowserRead } = require("./browser-read-health");

test("restarts after repeated browser read failures, then observes cooldown", () => {
  let now = 1_000;
  const reasons = [];
  const health = new BrowserReadHealth({ now: () => now, cooldownMs: 10_000,
    onUnhealthy: reason => reasons.push(reason) });
  assert.equal(health.failure("history"), false);
  assert.equal(health.failure("history"), true);
  assert.deepEqual(reasons, ["history"]);
  now += 1_000;
  assert.equal(health.failure("history"), false);
  assert.equal(health.failure("history"), false);
  now += 10_000;
  assert.equal(health.failure("history"), true);
  assert.deepEqual(reasons, ["history", "history"]);
});

test("a successful browser read clears the failure streak", () => {
  const reasons = [];
  const health = new BrowserReadHealth({ onUnhealthy: reason => reasons.push(reason) });
  health.failure("history");
  health.success();
  health.failure("history");
  assert.deepEqual(reasons, []);
});

test("a stalled history read does not restart an independently responsive browser", async () => {
  const reasons = [];
  const health = new BrowserReadHealth({ onUnhealthy: reason => reasons.push(reason) });
  const client = { pupPage: { evaluate: async () => true, isClosed: () => false } };
  for (let attempt = 0; attempt < 3; attempt++) {
    assert.deepEqual(await assessBrowserReadFailure({ client, health, reason: "history_discovery" }),
      { responsive: true, restartScheduled: false });
  }
  assert.deepEqual(reasons, []);
  assert.equal(health.failures, 0);
});

test("only repeated failed independent page probes restart the browser", async () => {
  const reasons = [];
  const health = new BrowserReadHealth({ onUnhealthy: reason => reasons.push(reason) });
  const client = { pupPage: { evaluate: () => new Promise(() => {}) } };
  assert.equal(await probeBrowserRead(client, 5), false);
  assert.deepEqual(await assessBrowserReadFailure({ client, health, reason: "history_discovery", timeoutMs: 5 }),
    { responsive: false, restartScheduled: false });
  assert.deepEqual(await assessBrowserReadFailure({ client, health, reason: "history_discovery", timeoutMs: 5 }),
    { responsive: false, restartScheduled: true });
  assert.deepEqual(reasons, ["history_discovery"]);
});

test("a stale browser generation cannot trigger a restart after the probe", async () => {
  const reasons = [];
  const health = new BrowserReadHealth({ onUnhealthy: reason => reasons.push(reason) });
  const client = { pupPage: { evaluate: async () => false } };
  assert.deepEqual(await assessBrowserReadFailure({ client, health, reason: "history_discovery",
    current: () => false }), { responsive: false, restartScheduled: false });
  assert.deepEqual(reasons, []);
  assert.equal(health.failures, 0);
});
