"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { mkdtempSync, rmSync } = require("node:fs");
const { tmpdir } = require("node:os");
const { join } = require("node:path");
const { spawn } = require("node:child_process");
const { ClientLifecycle } = require("./client-lifecycle");
const { TaskLimiter } = require("./task-limiter");
const { OperationLedger } = require("./operation-ledger");

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const turn = () => new Promise(resolve => setImmediate(resolve));

function fixture(options = {}) {
  const gate = new TaskLimiter(1), clients = [], exits = [], order = [], acceptedEvents = [];
  let live = 0, maximumLive = 0;
  const lifecycle = new ClientLifecycle({ limiter: gate, drainTimeoutMs: 60, cleanupTimeoutMs: 30,
    restartDelayMs: 0, startupTimeoutMs: 80,
    createClient: async () => {
      const index = clients.length;
      const client = new EventEmitter();
      client.initialize = async () => { order.push(`init:${index}`); await options.initialize?.(index); };
      client.destroy = async () => { order.push(`destroy:${index}`); await options.destroy?.(index); live--; };
      clients.push(client); live++; maximumLive = Math.max(live, maximumLive);
      order.push(`allocate:${index}`); return client;
    },
    wireClient: (client, current, trackEvent) => {
      client.on("ready", () => { if (current()) acceptedEvents.push(clients.indexOf(client)); });
      client.on("work", work => { void trackEvent(work); });
    },
    terminate: code => exits.push(code), ...options.lifecycle,
  });
  return { gate, clients, exits, order, acceptedEvents, lifecycle, maximumLive: () => maximumLive };
}

test("restart stops admission immediately and awaits both task settlement and browser cleanup", async () => {
  const work = deferred(), cleanup = deferred(), f = fixture({ destroy: index => index === 0 ? cleanup.promise : undefined });
  await f.lifecycle.start();
  const first = f.gate.run(() => work.promise);
  const restart = f.lifecycle.restart();
  await assert.rejects(f.gate.run(() => assert.fail("must not admit")), /draining/);
  f.clients[0].emit("ready");
  assert.deepEqual(f.acceptedEvents, []);
  await turn(); assert.equal(f.clients.length, 1); assert.equal(f.order.includes("destroy:0"), false);
  work.resolve(); await first; await turn();
  assert.equal(f.clients.length, 1); assert.equal(f.gate.accepting, false);
  cleanup.resolve(); await restart;
  assert.equal(f.clients.length, 2); assert.equal(f.maximumLive(), 1);
  f.clients[0].emit("ready"); f.clients[1].emit("ready");
  assert.deepEqual(f.acceptedEvents, [1]); assert.equal(f.gate.accepting, true);
  await f.lifecycle.shutdown(); assert.deepEqual(f.exits, [0]);
});

test("cleanup rejection fails closed instead of allocating a replacement client", async () => {
  const f = fixture({ destroy: async () => { throw new Error("fixture cleanup rejected"); } });
  await f.lifecycle.start(); await Promise.all([f.lifecycle.restart(), f.lifecycle.restart()]);
  assert.equal(f.clients.length, 1); assert.deepEqual(f.exits, [1]); assert.equal(f.gate.accepting, false);
  await f.lifecycle.restart(); await f.lifecycle.shutdown();
  assert.equal(f.order.filter(x => x === "destroy:0").length, 1); assert.deepEqual(f.exits, [1]);
});

test("hung cleanup reaches its deadline without freeing admission or starting another browser", async () => {
  const cleanup = deferred(), f = fixture({ destroy: () => cleanup.promise });
  await f.lifecycle.start(); await f.lifecycle.restart();
  assert.deepEqual(f.exits, [1]); assert.equal(f.clients.length, 1); assert.equal(f.gate.accepting, false);
  cleanup.resolve(); await turn(); assert.equal(f.clients.length, 1);
});

test("a hung active send keeps its permit and fails the restart before any browser replacement", async () => {
  const work = deferred(), f = fixture(); await f.lifecycle.start();
  const first = f.gate.run(() => work.promise);
  await f.lifecycle.restart();
  assert.equal(f.gate.active, 1); assert.equal(f.clients.length, 1); assert.deepEqual(f.exits, [1]);
  assert.equal(f.order.some(x => x.startsWith("destroy:")), false);
  work.resolve(); await first; assert.equal(f.gate.accepting, false);
});

test("concurrent restart and shutdown share exactly one cleanup and never reopen admission", async () => {
  const cleanup = deferred(), f = fixture({ destroy: () => cleanup.promise });
  await f.lifecycle.start(); const restart = f.lifecycle.restart(); await turn();
  const shutdown = f.lifecycle.shutdown(); const duplicate = f.lifecycle.shutdown();
  cleanup.resolve(); await Promise.all([restart, shutdown, duplicate]);
  assert.equal(f.clients.length, 1); assert.equal(f.order.filter(x => x === "destroy:0").length, 1);
  assert.deepEqual(f.exits, [0]); assert.equal(f.gate.accepting, false);
});

test("accepted event work finishes before cleanup, late old-session events never execute", async () => {
  const event = deferred(), f = fixture(); let calls = 0; await f.lifecycle.start();
  f.clients[0].emit("work", () => { calls++; return event.promise; }); await turn();
  const restart = f.lifecycle.restart();
  f.clients[0].emit("work", () => { calls++; return Promise.resolve(); });
  await turn(); assert.equal(f.order.includes("destroy:0"), false);
  event.resolve(); await restart;
  f.clients[0].emit("work", () => { calls++; }); await turn();
  assert.equal(calls, 1); await f.lifecycle.shutdown();
});

test("shutdown during replacement initialization waits for it before one destroy", async () => {
  const initialize = deferred(), f = fixture({ initialize: index => index === 1 ? initialize.promise : undefined });
  await f.lifecycle.start(); const restart = f.lifecycle.restart(); await turn();
  assert.equal(f.clients.length, 2);
  const shutdown = f.lifecycle.shutdown(); await turn();
  assert.equal(f.order.includes("destroy:1"), false);
  initialize.resolve(); await Promise.all([restart, shutdown]);
  assert.equal(f.order.filter(x => x === "destroy:1").length, 1); assert.equal(f.clients.length, 2);
  assert.deepEqual(f.exits, [0]);
});

test("shutdown cannot hang behind an unresponsive replacement initialization", async () => {
  const initialize = deferred(), f = fixture({ initialize: index => index === 1 ? initialize.promise : undefined });
  await f.lifecycle.start(); const restart = f.lifecycle.restart(); await turn();
  await f.lifecycle.shutdown(); await restart;
  assert.deepEqual(f.exits, [1]); assert.equal(f.clients.length, 2);
  assert.equal(f.order.includes("destroy:1"), false);
  initialize.resolve(); await turn(); assert.equal(f.gate.accepting, false);
});

test("actual child-process restart timeout preserves a fsynced send as UNKNOWN after death", async t => {
  const directory = mkdtempSync(join(tmpdir(), "otziv-lifecycle-")); t.after(() => rmSync(directory, { recursive: true, force: true }));
  const modulePath = name => JSON.stringify(join(__dirname, name));
  const script = `const {ClientLifecycle}=require(${modulePath("client-lifecycle")});
    const {TaskLimiter}=require(${modulePath("task-limiter")});const {OperationLedger}=require(${modulePath("operation-ledger")});
    const gate=new TaskLimiter(1),ledger=new OperationLedger(process.argv[1]);
    const owner=new ClientLifecycle({limiter:gate,createClient:async()=>({initialize:async()=>{},destroy:async()=>{}}),wireClient:()=>{},terminate:code=>process.exit(code),drainTimeoutMs:25,cleanupTimeoutMs:25,restartDelayMs:0});
    owner.start().then(()=>gate.run(()=>ledger.execute('restart-fixture',{clientId:'fixture',kind:'send',destination:'fixture',message:'fixture'},()=>{process.stdout.write('SEND_STARTED\\n');setImmediate(()=>owner.restart());return new Promise(()=>{});},{canStart:()=>true})));`;
  const child = spawn(process.execPath, ["-e", script, directory], { stdio: ["ignore", "pipe", "pipe"] });
  let stdout = "", stderr = ""; child.stdout.on("data", data => { stdout += data; }); child.stderr.on("data", data => { stderr += data; });
  const timeout = setTimeout(() => child.kill(), 5000);
  const code = await new Promise((resolve, reject) => { child.on("error", reject); child.on("close", resolve); }); clearTimeout(timeout);
  assert.equal(code, 1, stderr); assert.equal(stdout.trim(), "SEND_STARTED");
  const recovered = new OperationLedger(directory);
  const result = await recovered.execute("restart-fixture", { clientId: "fixture", kind: "send", destination: "fixture", message: "fixture" },
    () => assert.fail("UNKNOWN must never re-send"), { canStart: () => true });
  assert.equal(result.state, "UNKNOWN");
});
