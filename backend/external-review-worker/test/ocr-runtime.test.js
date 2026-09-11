import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { OcrRuntime } from "../src/ocr-runtime.js";

function childFixture() {
  const child = new EventEmitter();
  child.pid = 123;
  child.signals = [];
  child.send = (message, callback) => { child.last = message; callback?.(); };
  child.kill = (signal) => { child.signals.push(signal); return true; };
  return child;
}
test("OCR initialization timeout waits for actual process exit before rejecting", async () => {
  const child = childFixture();
  const runtime = new OcrRuntime({ forkProcess: () => child, timeoutMs: 10 });
  let settled = false;
  const result = runtime.ready.catch((error) => { settled = true; return error; });
  await new Promise((resolve) => setTimeout(resolve, 25));
  assert.deepEqual(child.signals, ["SIGTERM"]);
  assert.equal(settled, false);
  child.emit("exit", 0);
  assert.match((await result).message, /timed out/);
});
test("OCR cleanup does not mistake an IPC error for process exit", async () => {
  const child = childFixture();
  const runtime = new OcrRuntime({ forkProcess: () => child, timeoutMs: 1000 });
  let settled = false;
  const result = runtime.ready.catch((error) => { settled = true; return error; });
  child.emit("error", new Error("synthetic IPC failure"));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(settled, false);
  assert.deepEqual(child.signals, ["SIGTERM"]);
  child.emit("exit", 1);
  assert.match((await result).message, /process failure/);
});
test("successful OCR request and idempotent close wait for exit once", async () => {
  const child = childFixture();
  const runtime = new OcrRuntime({ forkProcess: () => child });
  child.emit("message", { id: child.last.id, ok: true });
  await runtime.ready;
  const recognized = runtime.recognize(Buffer.from("fixture"));
  await new Promise((resolve) => setImmediate(resolve));
  child.emit("message", { id: child.last.id, ok: true, text: "OTZIV READY 12345" });
  assert.equal(await recognized, "OTZIV READY 12345");
  const closed = runtime.close();
  assert.equal(runtime.close(), closed);
  assert.deepEqual(child.signals, ["SIGTERM"]);
  child.emit("exit", 0); await closed;
});
