"use strict";

const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

function identity(route, payload) {
  const parts = ["whatsapp", route, payload.clientId, payload.groupId || payload.from, payload.messageId];
  if (parts.some(value => typeof value !== "string" || !value.trim())) {
    throw new Error("inbound_identity_required");
  }
  return crypto.createHash("sha256").update(JSON.stringify(parts)).digest("hex");
}

// A private local persistent volume is required. A successful write includes fsync;
// the history cursor may move only after enqueue returns. No delivery TTL is used.
class DurableInboundInbox {
  constructor(directory, { clientId, now = Date.now, since, maxRecords = 100000,
    maxRecordBytes = 262144, retryBaseMs = 1000, retryMaxMs = 300000, io = fs } = {}) {
    this.directory = directory; this.clientId = clientId; this.now = now; this.io = io;
    this.maxRecords = maxRecords; this.maxRecordBytes = maxRecordBytes;
    this.retryBaseMs = retryBaseMs; this.retryMaxMs = retryMaxMs;
    this.records = new Map(); this.acknowledged = new Map(); this.lastOrder = 0; this.healthy = true; this.persistFailures = 0;
    this.historyGaps = new Set(); this.blockedHistoryChats = new Set(); this.historyDiscoveryFailed = false; this.paused = false; this.stopped = false;
    io.mkdirSync(directory, { recursive: true, mode: 0o700 });
    if (!io.lstatSync(directory).isDirectory() || io.lstatSync(directory).isSymbolicLink()) throw new Error("inbound_directory_not_regular");
    if (process.platform !== "win32") {
      const parent = io.openSync(path.dirname(directory), "r");
      try { io.fsyncSync(parent); } finally { io.closeSync(parent); }
    }
    const metadataPath = path.join(directory, "metadata.json");
    if (io.existsSync(metadataPath)) {
      this.metadata = JSON.parse(io.readFileSync(metadataPath, "utf8"));
      if (this.metadata.version !== 1 || this.metadata.clientId !== clientId || !Number.isFinite(this.metadata.since)
          || !this.metadata.cursors || typeof this.metadata.cursors !== "object" || Array.isArray(this.metadata.cursors)
          || Object.values(this.metadata.cursors).some(value => !Number.isFinite(value) || value < this.metadata.since)) {
        throw new Error("inbound_store_identity_mismatch");
      }
    } else {
      this.metadata = { version: 1, clientId, since: since === undefined ? Math.floor(now() / 1000) : Number(since), cursors: {} };
      if (!Number.isFinite(this.metadata.since) || this.metadata.since < 0) throw new Error("inbound_history_since_invalid");
      this.write("metadata.json", this.metadata);
    }
    for (const name of io.readdirSync(directory)) {
      if (!/^[a-f0-9]{64}\.json$/.test(name)) continue;
      const recordPath = path.join(directory, name);
      if (!io.lstatSync(recordPath).isFile()) throw new Error("inbound_record_not_regular");
      const record = JSON.parse(io.readFileSync(recordPath, "utf8"));
      if (record.version !== 1 || record.key !== name.slice(0, -5) || identity(record.route, record.payload) !== record.key
          || record.payload.clientId !== clientId || !["PENDING", "DEAD"].includes(record.status)
          || !Number.isSafeInteger(record.attempts) || record.attempts < 0
          || !Number.isSafeInteger(record.order) || record.order < 1
          || !Number.isFinite(record.createdAt) || !Number.isFinite(record.nextAttemptAt)) {
        throw new Error("inbound_store_corrupt");
      }
      this.records.set(record.key, record);
      this.lastOrder = Math.max(this.lastOrder, record.order);
    }
  }

  syncDirectory() {
    // Windows does not expose POSIX directory fsync. Runtime production is Linux.
    if (process.platform === "win32") return;
    const fd = this.io.openSync(this.directory, "r");
    try { this.io.fsyncSync(fd); } finally { this.io.closeSync(fd); }
  }

  write(name, value) {
    const temp = path.join(this.directory, `${name}.${crypto.randomUUID()}.tmp`);
    let fd;
    try {
      const data = JSON.stringify(value);
      if (Buffer.byteLength(data) > this.maxRecordBytes && name !== "metadata.json") throw new Error("inbound_record_too_large");
      fd = this.io.openSync(temp, "wx", 0o600);
      this.io.writeFileSync(fd, data); this.io.fsyncSync(fd); this.io.closeSync(fd); fd = undefined;
      this.io.renameSync(temp, path.join(this.directory, name)); this.syncDirectory();
    } catch (error) {
      this.healthy = false; this.persistFailures += 1;
      if (fd !== undefined) try { this.io.closeSync(fd); } catch (_) { /* preserve first failure */ }
      try { this.io.unlinkSync(temp); } catch (_) { /* recovery ignores uncommitted temp files */ }
      throw error;
    }
  }

  enqueue(route, payload) {
    if (!this.healthy) throw new Error("inbound_store_unhealthy");
    if (payload.clientId !== this.clientId) throw new Error("inbound_client_mismatch");
    const key = identity(route, payload);
    if (this.records.has(key)) return { queued: true, duplicate: true, key };
    // This bounded cache only avoids filling a recovery page with already committed
    // effects. Losing it on restart causes safe receiver-receipt replay, never loss.
    if ((this.acknowledged.get(key) || 0) > this.now()) return { queued: true, duplicate: true, acknowledged: true, key };
    if (this.records.size >= this.maxRecords) { this.persistFailures += 1; throw new Error("inbound_store_full"); }
    const record = { version: 1, key, route, payload: JSON.parse(JSON.stringify(payload)),
      status: "PENDING", createdAt: this.now(), order: ++this.lastOrder, attempts: 0, nextAttemptAt: this.now() };
    this.write(`${key}.json`, record); this.records.set(key, record);
    return { queued: true, key };
  }

  cursor(chatId) { return this.metadata.cursors[chatId] ?? this.metadata.since; }

  checkpoint(chatId, timestamp) {
    if (!this.healthy) throw new Error("inbound_store_unhealthy");
    if (!Number.isFinite(timestamp)) throw new Error("inbound_cursor_invalid");
    const updated = { ...this.metadata, cursors: { ...this.metadata.cursors,
      [chatId]: Math.max(this.cursor(chatId), timestamp) } };
    this.write("metadata.json", updated); this.metadata = updated;
  }

  retry(key) {
    const record = this.records.get(key);
    if (!record || record.status !== "DEAD") return false;
    const updated = { ...record, status: "PENDING", nextAttemptAt: this.now(), lastStatus: undefined };
    this.write(`${key}.json`, updated); this.records.set(key, updated); return true;
  }

  snapshot() {
    const records = [...this.records.values()];
    return { healthy: this.healthy, pending: records.filter(r => r.status === "PENDING").length,
      dead: records.filter(r => r.status === "DEAD").length, capacity: this.maxRecords,
      oldestAgeSeconds: records.length ? Math.max(0, (this.now() - records.reduce((oldest, r) => Math.min(oldest, r.createdAt), this.now())) / 1000) : 0,
      persistenceFailures: this.persistFailures, historyGapChats: this.historyGaps.size,
      historyDiscoveryFailed: this.historyDiscoveryFailed, historySince: this.metadata.since, historyPaused: this.paused };
  }

  deadLetters() {
    return [...this.records.values()].filter(r => r.status === "DEAD").map(r =>
      ({ key: r.key, attempts: r.attempts, createdAt: r.createdAt, status: r.lastStatus }));
  }

  async drain(send, { limit = 50 } = {}) {
    if (this.running || this.paused || !this.healthy) return;
    this.running = this.deliverBatch(send, limit);
    try { await this.running; } finally { this.running = null; }
  }

  async deliverBatch(send, limit) {
    const blockedChats = new Set(); let delivered = 0;
    const candidates = [...this.records.values()].sort((a, b) =>
      (Number(a.payload.timestamp) || a.createdAt / 1000) - (Number(b.payload.timestamp) || b.createdAt / 1000)
      || a.order - b.order);
    for (const original of candidates) {
      if (this.stopped || this.paused || delivered >= limit) break;
      const chat = `${original.route}:${original.payload.groupId || original.payload.from}`;
      if (this.blockedHistoryChats.has(original.payload.groupId || original.payload.from)) continue;
      if (blockedChats.has(chat)) continue;
      blockedChats.add(chat);
      if (original.status !== "PENDING" || original.nextAttemptAt > this.now()) continue;
      const record = { ...original, attempts: original.attempts + 1,
        nextAttemptAt: this.now() + Math.min(this.retryMaxMs, this.retryBaseMs * 2 ** Math.min(original.attempts, 20)) };
      // Persist the attempt before network I/O. Restart can never turn an unknown ACK into success.
      this.write(`${record.key}.json`, record); this.records.set(record.key, record);
      let response;
      try { response = await send(record.route, record.payload); } catch (_) { response = { status: 0 }; }
      const status = Number(response && response.status) || 0;
      if (status >= 200 && status < 300 && status !== 202) {
        try { this.io.unlinkSync(path.join(this.directory, `${record.key}.json`)); this.syncDirectory(); }
        catch (error) { this.healthy = false; this.persistFailures += 1; throw error; }
        this.records.delete(record.key); blockedChats.delete(chat);
        this.acknowledged.set(record.key, this.now() + 86400000);
        while (this.acknowledged.size > 100000 || (this.acknowledged.size && this.acknowledged.values().next().value <= this.now())) {
          this.acknowledged.delete(this.acknowledged.keys().next().value);
        }
      } else if ([400, 404, 405, 410, 413, 415, 422].includes(status)) {
        const dead = { ...record, status: "DEAD", lastStatus: status };
        this.write(`${record.key}.json`, dead); this.records.set(record.key, dead);
      } else {
        const pending = { ...record, lastStatus: status };
        this.write(`${record.key}.json`, pending); this.records.set(record.key, pending);
      }
      delivered += 1;
    }
  }

  start(send, onError = () => {}, intervalMs = 1000) {
    this.stopped = false;
    this.timer = setInterval(() => this.drain(send).catch(onError), intervalMs); this.timer.unref();
  }

  async stop() { this.stopped = true; clearInterval(this.timer); if (this.running) await this.running; }
}

module.exports = { DurableInboundInbox, identity };
