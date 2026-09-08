import { fork } from "node:child_process";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";

const require = createRequire(import.meta.url);

export function prepareOcrModels(cacheDirectory) {
  const modelDirectory = path.join(cacheDirectory, "bundled-models-v1");
  fs.mkdirSync(modelDirectory, { recursive: true });
  for (const language of ["eng", "rus"]) {
    const entry = require.resolve(`@tesseract.js-data/${language}`);
    const source = path.join(path.dirname(entry), "4.0.0_best_int", `${language}.traineddata.gz`);
    const target = path.join(modelDirectory, `${language}.traineddata.gz`);
    // Always use the lockfile-pinned bundled bytes, not a cached CDN download.
    fs.copyFileSync(source, target);
  }
  return modelDirectory;
}

export class OcrRuntime {
  constructor({ cacheDirectory, modelDirectory, timeoutMs = 30_000, forkProcess = fork } = {}) {
    this.timeoutMs = timeoutMs;
    this.pending = new Map();
    this.sequence = 0;
    this.closed = false;
    this.child = forkProcess(fileURLToPath(new URL("./ocr-process.js", import.meta.url)), [], {
      stdio: ["ignore", "ignore", "ignore", "ipc"], serialization: "advanced", execArgv: [],
    });
    this.exited = new Promise((resolve) => {
      const finish = () => {
        this.closed = true;
        for (const request of this.pending.values()) request.reject(new Error("OCR process exited"));
        this.pending.clear();
        resolve();
      };
      this.child.once("exit", finish);
      this.child.on("error", () => {
        // Spawn failure has no process to reap. A later IPC/kill error is not
        // proof of exit and must not prematurely resolve cleanup ownership.
        if (!this.child.pid) finish();
        else for (const request of this.pending.values()) request.reject(new Error("OCR process failure"));
      });
    });
    this.child.on("message", (message) => {
      const request = this.pending.get(message?.id);
      if (!request) return;
      this.pending.delete(message.id);
      message.ok ? request.resolve(message.text || "") : request.reject(new Error("OCR operation failed"));
    });
    this.ready = this.request({ type: "initialize", cacheDirectory, modelDirectory });
  }

  async request(message) {
    if (this.closed) throw new Error("OCR process is closed");
    const id = ++this.sequence;
    let timer;
    const operation = new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.child.send({ ...message, id }, (error) => { if (error) reject(new Error("OCR transport failed")); });
      timer = setTimeout(() => reject(new Error("OCR operation timed out")), this.timeoutMs);
    });
    try {
      return await operation;
    } catch (error) {
      // Reject only after process exit: no detached computation survives timeout.
      await this.close();
      throw error;
    } finally {
      clearTimeout(timer);
      this.pending.delete(id);
    }
  }

  async recognize(image) {
    await this.ready;
    return this.request({ type: "recognize", image });
  }

  close() {
    if (!this.closing) {
      this.closing = (async () => {
        if (!this.closed) {
          this.child.kill("SIGTERM");
          const force = setTimeout(() => this.child.kill("SIGKILL"), 1000);
          try { await this.exited; } finally { clearTimeout(force); }
        }
      })();
    }
    return this.closing;
  }
}
