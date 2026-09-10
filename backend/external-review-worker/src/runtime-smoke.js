import { chromium } from "playwright";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import { chromiumLaunchArgs } from "./chromium-security.js";
import { OcrRuntime, prepareOcrModels } from "./ocr-runtime.js";
import { checkRuntime } from "./runtime-readiness.js";
import { EventEmitter } from "node:events";
import assert from "node:assert/strict";
import { TaskLimiter } from "./task-limiter.js";
import { TaskCancellation } from "./task-cancellation.js";

const cacheDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-ocr-smoke-"));
const modelDirectory = prepareOcrModels(cacheDirectory);
const watchdog = setTimeout(() => process.exit(1), 90_000);
try {
  await checkRuntime({
    launchBrowser: () => chromium.launch({
      chromiumSandbox: true,
      executablePath: process.env.CHROMIUM_EXECUTABLE_PATH || undefined,
      headless: true, args: chromiumLaunchArgs(), timeout: 30_000,
    }),
    createOcr: async () => {
      const ocr = new OcrRuntime({ cacheDirectory, modelDirectory });
      await ocr.ready;
      return ocr;
    },
  });
  console.log("Chromium + offline English/Russian OCR readiness passed");
  const limiter=new TaskLimiter(1), request=new EventEmitter(), response=new EventEmitter();
  let prepared, released;
  const ready=new Promise(resolve=>{prepared=resolve;}), finish=new Promise(resolve=>{released=resolve;});
  let browser,ocr;
  const task=limiter.run(async()=>{
    const cancellation=new TaskCancellation(request,response,limiter);
    try {
      browser=await cancellation.own(await chromium.launch({chromiumSandbox:true,
        executablePath:process.env.CHROMIUM_EXECUTABLE_PATH||undefined,headless:true,args:chromiumLaunchArgs(),timeout:30000}));
      assert.ok(process.env.OTZIV_BROWSER_VERSION, "Release browser version must be pinned");
      assert.equal(browser.version(), process.env.OTZIV_BROWSER_VERSION);
      ocr=await cancellation.own(new OcrRuntime({cacheDirectory,modelDirectory}));await ocr.ready;
      prepared();await finish;
    } finally {await cancellation.close();}
  });
  await ready;response.emit('close');
  assert.equal(limiter.snapshot().cleanup,1);assert.equal(limiter.snapshot().cancelledTotal,1);
  await assert.rejects(limiter.run(async()=>{}),/busy/);
  released();await task;
  assert.equal(browser.isConnected(),false);assert.equal(ocr.closed,true);assert.equal(limiter.active,0);
  console.log("Actual Chromium/OCR disconnect cleanup retained its task permit until settlement");
} finally {
  clearTimeout(watchdog);
  fs.rmSync(cacheDirectory, { recursive: true, force: true });
}
