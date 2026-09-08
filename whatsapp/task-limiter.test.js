"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const { EventEmitter } = require("node:events");
const { TaskLimiter, TaskAdmissionError } = require("./task-limiter");
function deferred() { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; }
function response() {
  const res = new EventEmitter();
  res.set = () => res;
  res.status = (code) => { res.statusCode = code; return res; };
  res.json = (body) => { res.body = body; return res; };
  return res;
}
test("disconnect and early response finish do not release unfinished task work", async () => {
  const gate = new TaskLimiter(1);
  const work = deferred();
  const first = response();
  const running = gate.wrap(async () => work.promise)({}, first, assert.fail);
  first.emit("close"); first.emit("finish");
  const rejected = response();
  await gate.wrap(() => assert.fail("must remain full"))({}, rejected, assert.fail);
  assert.equal(rejected.statusCode, 429);
  assert.equal(gate.active, 1);
  work.resolve();
  await running;
  assert.equal(gate.active, 0);
  await gate.run(() => {});
});
test("a timed-out race keeps its permit until tracked underlying work and cleanup settle", async () => {
  const gate = new TaskLimiter(1);
  const underlying = deferred();
  const cleanup = deferred();
  let responseReturned = false;
  const running = gate.run(async () => {
    gate.track(underlying.promise.then(() => gate.track(cleanup.promise)));
    await Promise.race([underlying.promise, Promise.resolve("timeout")]);
    responseReturned = true;
  });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(responseReturned, true);
  await assert.rejects(gate.run(() => {}), TaskAdmissionError);
  underlying.resolve();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(gate.active, 1);
  cleanup.resolve();
  await running;
  assert.equal(gate.active, 0);
});
test("drain stops admission and waits for actual completion without socket ownership", async () => {
  const gate = new TaskLimiter(2);
  const work = deferred();
  const running = gate.run(() => work.promise);
  const draining = gate.drain(1000);
  await assert.rejects(gate.run(() => {}), (error) => error.code === "draining");
  work.resolve();
  assert.equal(await draining, true);
  await running;
});
test("drain timeout never frees a permit or reopens admission", async () => {
  const gate = new TaskLimiter(1);
  const work = deferred();
  const running = gate.run(() => work.promise);
  assert.equal(await gate.drain(5), false);
  assert.equal(gate.active, 1);
  assert.equal(gate.accepting, false);
  work.resolve();
  await running;
});
test("a handler failure still waits for tracked cleanup before releasing", async () => {
  const gate = new TaskLimiter(1);
  const cleanup = deferred();
  const running = gate.run(async () => { gate.track(cleanup.promise); throw new Error("synthetic"); });
  const rejected = assert.rejects(running, /synthetic/);
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(gate.active, 1);
  cleanup.resolve(); await rejected;
  assert.equal(gate.active, 0);
});
test("metrics reflect tracked cleanup, bounded rejections and one timeout per task", async () => {
  let now = 1000; const gate = new TaskLimiter(1, { now: () => now });
  const cleanup = deferred(); let control;
  const running = gate.run(() => { control = gate.control(); gate.track(cleanup.promise); });
  await new Promise(resolve => setImmediate(resolve));
  control.timeout(); control.timeout(); now = 4000;
  assert.equal(gate.snapshot().cleanup, 1); assert.equal(gate.snapshot().running, 0);
  assert.equal(gate.snapshot().oldestCleanupSeconds, 3); assert.equal(gate.snapshot().timedOutTotal, 1);
  await assert.rejects(gate.run(() => {})); gate.stop(); await assert.rejects(gate.run(() => {}));
  assert.deepEqual(gate.snapshot().rejectedTotal, {busy: 1, draining: 1, not_ready: 0});
  assert.throws(() => gate.reject('user-controlled-id')); cleanup.resolve(); await running;
  assert.equal(gate.snapshot().active, 0); assert.equal(gate.snapshot().oldestCleanupSeconds, 0);
});
