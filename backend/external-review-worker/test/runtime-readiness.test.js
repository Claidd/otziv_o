import test from "node:test";
import assert from "node:assert/strict";
import { RuntimeReadiness, checkRuntime } from "../src/runtime-readiness.js";

const browserCommandLine = async () => ({ send: async () => ({ arguments: ["chromium", "--enable-automation"] }), detach: async () => {} });

test("readiness waits for the entire browser/OCR fixture including cleanup", async () => {
  let finish;
  const state = new RuntimeReadiness();
  const pending = state.check(() => new Promise((resolve) => { finish = resolve; }));
  assert.equal(state.ready, false);
  finish(); await pending;
  assert.equal(state.ready, true);
});
test("a failed fixture or startup during drain cannot report ready", async () => {
  const state = new RuntimeReadiness();
  assert.equal(await state.check(async () => { throw new Error("synthetic"); }), false);
  assert.equal(state.state, "failed");
  await state.check(async () => state.stop());
  assert.equal(state.state, "draining");
});
test("bad OCR fixture closes both runtime resources and fails readiness", async () => {
  let browsersClosed = 0; let ocrClosed = 0;
  const state = new RuntimeReadiness();
  const ok = await state.check(() => checkRuntime({
    launchBrowser: async () => ({
      newBrowserCDPSession: browserCommandLine,
      newPage: async () => ({ setContent: async () => {}, screenshot: async () => Buffer.from("fixture") }),
      close: async () => { browsersClosed++; },
    }),
    createOcr: async () => ({ recognize: async () => "incorrect", close: async () => { ocrClosed++; } }),
  }));
  assert.equal(ok, false);
  assert.equal(browsersClosed, 1); assert.equal(ocrClosed, 1);
});
test("OCR allocation failure still closes the browser", async () => {
  let closed = false;
  await assert.rejects(checkRuntime({
    launchBrowser: async () => ({
      newBrowserCDPSession: browserCommandLine,
      newPage: async () => ({ setContent: async () => {}, screenshot: async () => Buffer.alloc(0) }),
      close: async () => { closed = true; },
    }),
    createOcr: async () => { throw new Error("missing runtime"); },
  }), /missing runtime/);
  assert.equal(closed, true);
});

test("runtime-added no-sandbox argument fails readiness before OCR even if explicit args were safe", async () => {
  let closed = false; let ocrStarted = false;
  await assert.rejects(checkRuntime({
    launchBrowser: async () => ({
      newBrowserCDPSession: async () => ({ send: async () => ({ arguments: ["chromium", "--no-sandbox"] }), detach: async () => {} }),
      close: async () => { closed = true; },
    }),
    createOcr: async () => { ocrStarted = true; },
  }), /sandbox was disabled/);
  assert.equal(closed, true); assert.equal(ocrStarted, false);
});
