"use strict";

// A browser generation owns initialization, event work and cleanup. A failed
// cleanup is a process failure, never permission to create another session.
class ClientLifecycle {
  constructor({ limiter, createClient, wireClient, onStarting = () => {}, onBlocked = () => {},
    terminate, drainTimeoutMs = 30000, cleanupTimeoutMs = 5000, restartDelayMs = 5000,
    startupTimeoutMs = 600000 }) {
    for (const value of [drainTimeoutMs, cleanupTimeoutMs, restartDelayMs]) {
      if (!Number.isSafeInteger(value) || value < 0 || value > 300000) throw new Error("Invalid lifecycle bound");
    }
    if (!drainTimeoutMs || !cleanupTimeoutMs) throw new Error("Lifecycle deadlines must be positive");
    if (!Number.isSafeInteger(startupTimeoutMs) || startupTimeoutMs < 1 || startupTimeoutMs > 900000) {
      throw new Error("Invalid startup deadline");
    }
    Object.assign(this, { limiter, createClient, wireClient, onStarting, onBlocked, terminate,
      drainTimeoutMs, cleanupTimeoutMs, restartDelayMs, startupTimeoutMs });
    this.client = null;
    this.generation = 0;
    this.phase = "idle";
    this.starting = null;
    this.transition = null;
    this.restartRequested = false;
    this.shutdownRequested = false;
    this.background = new Set();
    this.destroying = new WeakMap();
    this.terminated = false;
  }

  current(instance, generation) {
    return !this.terminated && !this.shutdownRequested && this.client === instance
      && this.generation === generation && ["starting", "running"].includes(this.phase);
  }

  trackEvent(instance, generation, work) {
    if (!this.current(instance, generation)) return Promise.resolve();
    const pending = Promise.resolve().then(work);
    this.background.add(pending);
    pending.then(() => this.background.delete(pending), () => this.background.delete(pending));
    return pending;
  }

  start() {
    if (this.starting) return this.starting;
    if (this.terminated || this.shutdownRequested) return Promise.resolve();
    if (this.client) throw new Error("A client must be destroyed before replacement");
    const generation = ++this.generation;
    this.phase = "starting";
    this.limiter.stop();
    this.onStarting();
    this.starting = (async () => {
      const instance = await this.createClient();
      // Retain even a late allocation so the draining generation owns destroy.
      this.client = instance;
      if (this.generation !== generation || this.shutdownRequested) return;
      const current = () => this.current(instance, generation);
      this.wireClient(instance, current, work => this.trackEvent(instance, generation, work));
      // QR/status requests can run while initialization awaits authentication;
      // business routes separately require this generation's ready event.
      this.limiter.resume();
      await instance.initialize();
      if (current()) this.phase = "running";
    })().finally(() => { this.starting = null; });
    return this.starting;
  }

  block() {
    ++this.generation;
    this.limiter.stop();
    this.onBlocked();
  }

  restart() {
    if (this.terminated || this.shutdownRequested) return this.transition || Promise.resolve();
    if (!["draining", "destroying"].includes(this.phase) && !this.restartRequested) {
      this.restartRequested = true;
      this.block();
      this.interruptStart?.();
    }
    return this.ensureTransition();
  }

  shutdown() {
    if (this.terminated) return this.transition || Promise.resolve();
    if (!this.shutdownRequested) {
      this.shutdownRequested = true;
      this.restartRequested = false;
      this.block();
      this.interruptStart?.();
    }
    return this.ensureTransition();
  }

  ensureTransition() {
    if (!this.transition) this.transition = this.recover().finally(() => { this.transition = null; });
    return this.transition;
  }

  async settleEvents() {
    while (this.background.size) await Promise.allSettled([...this.background]);
  }

  async recover() {
    try {
      do {
        this.restartRequested = false;
        this.phase = "draining";
        const [, , drained] = await bounded(Promise.all([
          this.starting?.catch(() => {}), this.settleEvents(), this.limiter.drain(this.drainTimeoutMs),
        ]), this.drainTimeoutMs, "client_drain_timeout");
        if (!drained) throw new Error("client_drain_timeout");
        const instance = this.client;
        this.phase = "destroying";
        if (instance) {
          if (!this.destroying.has(instance)) {
            this.destroying.set(instance, Promise.resolve().then(() => instance.destroy()));
          }
          await bounded(this.destroying.get(instance), this.cleanupTimeoutMs, "client_cleanup_timeout");
        }
        this.client = null;
        if (this.shutdownRequested) { this.finish(0); return; }
        if (this.restartDelayMs) await new Promise(resolve => setTimeout(resolve, this.restartDelayMs));
        if (this.shutdownRequested) { this.finish(0); return; }
        this.phase = "idle";
        const interrupted = new Promise(resolve => { this.interruptStart = resolve; });
        try {
          await bounded(Promise.race([this.start(), interrupted]), this.startupTimeoutMs, "client_startup_timeout");
        } finally { this.interruptStart = null; }
      } while (this.restartRequested || this.shutdownRequested);
    } catch {
      // Outstanding provider work/ledger claims stay unresolved. Process death
      // recovers their durable RUNNING records as UNKNOWN; no in-process replay.
      this.finish(1);
    }
  }

  finish(code) {
    if (this.terminated) return;
    this.terminated = true;
    this.block();
    this.phase = code ? "failed" : "stopped";
    this.terminate(code);
  }
}

function bounded(work, milliseconds, code) {
  let timer;
  return Promise.race([work, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(code)), milliseconds);
  })]).finally(() => clearTimeout(timer));
}

module.exports = { ClientLifecycle };
