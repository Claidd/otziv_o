// A permit belongs to a task and all explicitly tracked work, never to a socket.
const { AsyncLocalStorage } = require("node:async_hooks");

class TaskAdmissionError extends Error {
  constructor(code) { super(code); this.code = code; }
}

class TaskLimiter {
  constructor(limit, { maximum = 64, busyCode = "gateway_busy", now = Date.now } = {}) {
    const parsed = Number.parseInt(String(limit), 10);
    this.limit = Number.isFinite(parsed) ? Math.max(1, Math.min(parsed, maximum)) : 1;
    this.busyCode = busyCode;
    this.active = 0;
    this.accepting = true;
    this.context = new AsyncLocalStorage();
    this.waiters = new Set();
    this.tasks = new Set();
    this.now = now;
    this.timeouts = 0;
    this.cancelled = 0;
    this.rejections = { busy: 0, draining: 0, not_ready: 0 };
  }

  reject(reason) {
    if (!Object.hasOwn(this.rejections, reason)) throw new Error("Unknown admission reason");
    this.rejections[reason] += 1;
  }

  // Capture these callbacks while inside the task. Socket events need not run
  // in its AsyncLocalStorage context. Counters are once per actual task.
  control() {
    const task = this.context.getStore();
    return {
      cleanup: () => { if (task && task.cleanupAt === null) task.cleanupAt = this.now(); },
      timeout: () => { if (task && !task.timedOut) { task.timedOut = true; this.timeouts++; } },
      cancel: () => { if (task && !task.cancelled) { task.cancelled = true; this.cancelled++; } },
    };
  }

  snapshot() {
    const now = this.now();
    const cleanup = [...this.tasks].filter(task => task.cleanupAt !== null);
    return { schema: "otziv-task-metrics-v1", measuredAt: new Date(now).toISOString(),
      active: this.active, running: this.active - cleanup.length, cleanup: cleanup.length,
      oldestCleanupSeconds: cleanup.length ? Math.max(...cleanup.map(task => Math.max(0, now - task.cleanupAt) / 1000)) : 0,
      limit: this.limit, accepting: this.accepting, timedOutTotal: this.timeouts,
      cancelledTotal: this.cancelled, rejectedTotal: { ...this.rejections } };
  }

  // Promise.race does not cancel its losers. Register the actual underlying
  // operation, including cleanup, so a timeout cannot release its permit.
  track(promise) {
    const task = this.context.getStore();
    const work = Promise.resolve(promise);
    if (task) {
      task.children.add(work);
      work.then(() => task.children.delete(work), () => task.children.delete(work));
    }
    return work;
  }

  async run(handler) {
    if (!this.accepting) { this.reject("draining"); throw new TaskAdmissionError("draining"); }
    if (this.active >= this.limit) { this.reject("busy"); throw new TaskAdmissionError(this.busyCode); }
    this.active += 1;
    const task = { children: new Set(), cleanupAt: null, timedOut: false, cancelled: false };
    this.tasks.add(task);
    try {
      return await this.context.run(task, handler);
    } finally {
      if (task.cleanupAt === null) task.cleanupAt = this.now();
      // Children can schedule cleanup children while settling.
      while (task.children.size) await Promise.allSettled([...task.children]);
      this.tasks.delete(task);
      this.active -= 1;
      if (this.active === 0) {
        for (const resolve of this.waiters) resolve(true);
        this.waiters.clear();
      }
    }
  }

  wrap(handler, status = "error") {
    return (req, res, next) => this.run(() => handler(req, res, next))
      .catch((error) => {
        if (error instanceof TaskAdmissionError) {
          if (!res.destroyed && !res.headersSent) {
            res.set("Retry-After", "1");
            res.status(error.code === "draining" ? 503 : 429).json({ status, code: error.code });
          }
          return;
        }
        next(error);
      });
  }

  stop() { this.accepting = false; }

  resume() {
    if (this.active !== 0) throw new Error("Cannot resume admission before drain completes");
    this.accepting = true;
  }

  async drain(timeoutMs = 30_000) {
    this.stop();
    if (this.active === 0) return true;
    return new Promise((resolve) => {
      const done = (drained) => {
        clearTimeout(timer);
        this.waiters.delete(done);
        resolve(drained);
      };
      const timer = setTimeout(() => done(false), Math.max(1, timeoutMs));
      this.waiters.add(done);
    });
  }
}

module.exports = { TaskLimiter, TaskAdmissionError };
