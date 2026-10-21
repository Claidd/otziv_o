"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { BrowserReadHealth } = require("./browser-read-health");

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
