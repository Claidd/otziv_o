"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn, spawnSync } = require("node:child_process");
const { once } = require("node:events");
const { OperationLedger } = require("./operation-ledger");
const envelope = { clientId: "client", kind: "send", destination: "fixture", message: "fixture" };

test("Linux inherited FD remains flock-locked after helper exits; SIGKILL releases it for a new Node writer", { skip: process.platform !== "linux", timeout: 15_000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-ledger-flock-"));
  const modulePath = path.join(__dirname, "operation-ledger.js");
  const ownerScript = `const {OperationLedger}=require(process.argv[1]);const ledger=new OperationLedger(process.argv[2]);
    ledger.execute('interrupted',JSON.parse(process.argv[3]),()=>new Promise(()=>{}));
    process.send({writer:ledger.metrics().writer, pid:process.pid, helperReturned:true});setInterval(()=>{},1000);`;
  const owner = spawn(process.execPath, ["-e", ownerScript, modulePath, directory, JSON.stringify(envelope)], { stdio: ["ignore", "ignore", "pipe", "ipc"] });
  t.after(async () => { if (owner.exitCode === null && owner.signalCode === null) { owner.kill("SIGKILL"); await once(owner, "exit"); } fs.rmSync(directory, { recursive: true, force: true }); });
  const [ready] = await Promise.race([once(owner, "message"), once(owner, "exit").then(([code]) => { throw new Error(`owner exited ${code}`); })]);
  assert.equal(ready.writer, true); assert.equal(ready.helperReturned, true);
  const inodeBefore = fs.statSync(path.join(directory, ".writer.lock")).ino;
  const contenderScript = `const {OperationLedger}=require(process.argv[1]);const ledger=new OperationLedger(process.argv[2]);let sends=0;
    (async()=>{let code=null;try{await ledger.execute('new',JSON.parse(process.argv[3]),async()=>{sends++;return 'message'})}catch(error){code=error.code}
    const old=await ledger.execute('interrupted',JSON.parse(process.argv[3]),()=>{throw Error('duplicate')});
    console.log(JSON.stringify({writer:ledger.metrics().writer,code,sends,old:old.state}));await ledger.close()})().catch(error=>{console.error(error);process.exitCode=1});`;
  function contender() {
    const result = spawnSync(process.execPath, ["-e", contenderScript, modulePath, directory, JSON.stringify(envelope)], { encoding: "utf8", timeout: 5_000 });
    assert.equal(result.status, 0, result.stderr); return JSON.parse(result.stdout);
  }
  // The flock subprocess returned before owner sent IPC, yet a different Node
  // cannot write. This proves the parent's inherited open-file description owns it.
  assert.deepEqual(contender(), { writer: false, code: "operation_writer_locked", sends: 0, old: "UNKNOWN" });
  const exited = once(owner, "exit"); owner.kill("SIGKILL"); await exited;
  assert.deepEqual(contender(), { writer: true, code: null, sends: 1, old: "UNKNOWN" });
  assert.equal(fs.statSync(path.join(directory, ".writer.lock")).ino, inodeBefore, "takeover never unlinks the lock inode");
});

test("Linux missing flock fails closed instead of falling back to an unsafe writer", { skip: process.platform !== "linux" }, t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-ledger-no-flock-"));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const script = `try{new(require(process.argv[1]).OperationLedger)(process.argv[2]);process.exit(9)}catch(error){console.log(error.code)}`;
  const result = spawnSync(process.execPath, ["-e", script, path.join(__dirname, "operation-ledger.js"), directory], { encoding: "utf8", env: { ...process.env, PATH: "" } });
  assert.equal(result.status, 0, result.stderr); assert.equal(result.stdout.trim(), "operation_writer_flock_unavailable");
});

test("Linux directory fsync failure forbids provider invocation and survives as UNKNOWN", { skip: process.platform !== "linux" }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-ledger-directory-flush-"));
  let armed = false;
  const io = Object.create(fs);
  io.fsyncSync = fd => {
    if (armed && fs.fstatSync(fd).isDirectory()) throw Object.assign(new Error("I/O failure"), { code: "EIO" });
    return fs.fsyncSync(fd);
  };
  const ledger = new OperationLedger(directory, { io }); let restarted;
  t.after(async () => { armed = false; await ledger.close(); await restarted?.close(); fs.rmSync(directory, { recursive: true, force: true }); });
  armed = true;
  await assert.rejects(ledger.execute("unflushed", envelope, assert.fail), { code: "operation_claim_failed" });
  assert.equal(ledger.metrics().persistenceFailures, 1); assert.equal(ledger.admission().acceptingNew, false);
  armed = false; await ledger.close(); restarted = new OperationLedger(directory);
  assert.equal((await restarted.execute("unflushed", envelope, assert.fail)).state, "UNKNOWN");
});
