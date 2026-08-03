"use strict";

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const DELIVERY_STORE_COMPACT_EVERY = 256;
const DELIVERY_STORE_MAX_ENTRIES = 50_000;

function serializedId(value) {
  if (!value) {
    return "";
  }
  if (typeof value === "string") {
    return value.trim();
  }
  if (typeof value._serialized === "string") {
    return value._serialized.trim();
  }
  return "";
}

function messageId(message) {
  return serializedId(message && message.id);
}

function deriveGroupId(message) {
  if (!message) {
    return "";
  }
  return [message.from, message.to]
    .map(serializedId)
    .find((value) => value.endsWith("@g.us")) || "";
}

function outboundFingerprint(groupId, body) {
  return `${String(groupId || "").trim()}\n${String(body || "").trim()}`;
}

function trackedBody(message) {
  const body = String(message && message.body || "").trim();
  if (body) {
    return body;
  }
  const type = String(message && message.type || "").trim();
  if (message && (message.hasMedia || (type && type !== "chat"))) {
    return `[Вложение${type ? `: ${type}` : ""}]`;
  }
  return "";
}

class RecentOutboundRegistry {
  constructor(ttlMs = 10 * 60 * 1000, now = () => Date.now()) {
    this.ttlMs = ttlMs;
    this.now = now;
    this.pending = new Map();
    this.sentIds = new Map();
    this.sequence = 0;
  }

  begin(groupId, body) {
    this.cleanup();
    const token = `${this.now()}-${++this.sequence}`;
    const key = outboundFingerprint(groupId, body);
    this.pending.set(token, { key, expiresAt: this.now() + this.ttlMs });
    return token;
  }

  complete(token, externalMessageId) {
    this.cleanup();
    this.pending.delete(token);
    const id = String(externalMessageId || "").trim();
    if (id) {
      this.sentIds.set(id, this.now() + this.ttlMs);
    }
  }

  cancel(token) {
    this.pending.delete(token);
  }

  matches(groupId, body, externalMessageId) {
    this.cleanup();
    const id = String(externalMessageId || "").trim();
    if (id && this.sentIds.has(id)) {
      return true;
    }
    const key = outboundFingerprint(groupId, body);
    for (const pending of this.pending.values()) {
      if (pending.key === key) {
        return true;
      }
    }
    return false;
  }

  cleanup() {
    const cutoff = this.now();
    for (const [token, pending] of this.pending.entries()) {
      if (pending.expiresAt <= cutoff) {
        this.pending.delete(token);
      }
    }
    for (const [id, expiresAt] of this.sentIds.entries()) {
      if (expiresAt <= cutoff) {
        this.sentIds.delete(id);
      }
    }
  }
}

class MemoryDeliveryIdempotencyStore {
  constructor(now = () => Date.now()) {
    this.now = now;
    this.entries = new Map();
  }

  async has(key) {
    this.cleanup();
    return this.entries.has(key);
  }

  async mark(key, expiresAt) {
    this.cleanup();
    this.entries.set(key, expiresAt);
  }

  cleanup() {
    const cutoff = this.now();
    for (const [key, expiresAt] of this.entries) {
      if (!Number.isFinite(expiresAt) || expiresAt <= cutoff) {
        this.entries.delete(key);
      }
    }
  }
}

class FileDeliveryIdempotencyStore {
  constructor(filePath, now = () => Date.now(), maxEntries = DELIVERY_STORE_MAX_ENTRIES) {
    if (!filePath) {
      throw new Error("Delivery idempotency path is required");
    }
    this.filePath = path.resolve(filePath);
    this.now = now;
    this.entries = new Map();
    this.loaded = false;
    this.needsCompaction = false;
    this.operationsSinceCompaction = 0;
    this.maxEntries = Math.max(1, Math.min(Number(maxEntries) || DELIVERY_STORE_MAX_ENTRIES, DELIVERY_STORE_MAX_ENTRIES));
    this.operation = Promise.resolve();
  }

  has(key) {
    return this.serialize(async () => {
      await this.load();
      this.cleanup();
      return this.entries.has(key);
    });
  }

  mark(key, expiresAt) {
    return this.serialize(async () => {
      await this.load();
      this.cleanup();
      this.entries.delete(key);
      this.entries.set(key, expiresAt);
      const evicted = this.enforceEntryLimit();
      if (this.needsCompaction || evicted || this.operationsSinceCompaction >= DELIVERY_STORE_COMPACT_EVERY - 1) {
        await this.compact();
        return;
      }
      await this.append({ version: 2, key, expiresAt });
      this.operationsSinceCompaction += 1;
    });
  }

  serialize(operation) {
    const result = this.operation.then(operation, operation);
    this.operation = result.catch(() => undefined);
    return result;
  }

  async load() {
    if (this.loaded) {
      return;
    }
    try {
      const content = await fs.promises.readFile(this.filePath, "utf8");
      this.loadContent(content);
      this.cleanup();
      if (this.enforceEntryLimit()) {
        this.needsCompaction = true;
      }
      this.loaded = true;
    } catch (error) {
      if (error && error.code !== "ENOENT" && !(error instanceof SyntaxError)) {
        throw error;
      }
      this.entries.clear();
      this.loaded = true;
    }
  }

  cleanup() {
    const cutoff = this.now();
    let dirty = false;
    for (const [key, expiresAt] of this.entries) {
      if (!Number.isFinite(expiresAt) || expiresAt <= cutoff) {
        this.entries.delete(key);
        dirty = true;
      }
    }
    return dirty;
  }

  enforceEntryLimit() {
    let evicted = false;
    while (this.entries.size > this.maxEntries) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) {
        break;
      }
      this.entries.delete(oldest);
      evicted = true;
    }
    return evicted;
  }

  loadContent(content) {
    const source = String(content || "").trim();
    if (!source) {
      return;
    }
    try {
      const parsed = JSON.parse(source);
      this.applyStoredPayload(parsed);
      return;
    } catch {
      // Version 2 is newline-delimited so each acknowledgement is O(1).
    }

    for (const line of source.split(/\r?\n/)) {
      if (!line.trim()) {
        continue;
      }
      try {
        this.applyStoredPayload(JSON.parse(line));
      } catch {
        this.needsCompaction = true;
      }
    }
  }

  applyStoredPayload(payload) {
    if (payload && payload.version === 1 && payload.entries && typeof payload.entries === "object") {
      for (const [key, expiresAt] of Object.entries(payload.entries)) {
        this.setLoadedEntry(key, expiresAt);
      }
      this.needsCompaction = true;
      return;
    }
    if (payload && payload.version === 2 && Array.isArray(payload.entries)) {
      this.entries.clear();
      for (const entry of payload.entries) {
        if (Array.isArray(entry) && entry.length === 2) {
          this.setLoadedEntry(entry[0], entry[1]);
        }
      }
      return;
    }
    if (payload && payload.version === 2) {
      this.setLoadedEntry(payload.key, payload.expiresAt);
      return;
    }
    this.needsCompaction = true;
  }

  setLoadedEntry(key, expiresAt) {
    if (typeof key === "string" && key.length <= 128 && Number.isFinite(expiresAt)) {
      this.entries.delete(key);
      this.entries.set(key, expiresAt);
    }
  }

  async append(record) {
    const directory = path.dirname(this.filePath);
    await fs.promises.mkdir(directory, { recursive: true, mode: 0o700 });
    await fs.promises.appendFile(this.filePath, `${JSON.stringify(record)}\n`, { encoding: "utf8", mode: 0o600 });
  }

  async compact() {
    const directory = path.dirname(this.filePath);
    await fs.promises.mkdir(directory, { recursive: true, mode: 0o700 });
    const temporaryPath = `${this.filePath}.${process.pid}.${Date.now()}.tmp`;
    const payload = `${JSON.stringify({ version: 2, entries: Array.from(this.entries) })}\n`;
    try {
      await fs.promises.writeFile(temporaryPath, payload, { encoding: "utf8", mode: 0o600 });
      await fs.promises.rename(temporaryPath, this.filePath);
      this.operationsSinceCompaction = 0;
      this.needsCompaction = false;
    } finally {
      await fs.promises.rm(temporaryPath, { force: true }).catch(() => undefined);
    }
  }
}

class DeliveredMessageCache {
  constructor(ttlMs = 24 * 60 * 60 * 1000, now = () => Date.now(), store = null) {
    this.ttlMs = ttlMs;
    this.now = now;
    this.store = store || new MemoryDeliveryIdempotencyStore(now);
    this.inFlight = new Map();
  }

  key(path, payload) {
    const id = String(payload && payload.messageId || "").trim();
    if (!id) {
      return "";
    }
    return crypto.createHash("sha256")
      .update(JSON.stringify([
        String(path || ""),
        String(payload.clientId || "").trim(),
        String(payload.groupId || payload.from || "").trim(),
        id,
      ]), "utf8")
      .digest("hex");
  }

  async deliver(path, payload, sender) {
    const key = this.key(path, payload);
    if (!key) {
      return sender();
    }
    if (this.inFlight.has(key)) {
      return this.inFlight.get(key);
    }
    const delivery = Promise.resolve()
      .then(async () => {
        if (await this.store.has(key)) {
          return { duplicate: true };
        }
        const result = await sender();
        await this.store.mark(key, this.now() + this.ttlMs);
        return result;
      })
      .finally(() => this.inFlight.delete(key));
    this.inFlight.set(key, delivery);
    return delivery;
  }

}

async function groupMetadata(message, groupId, log) {
  if (!message || typeof message.getChat !== "function") {
    return { groupId, groupName: "" };
  }
  try {
    const chat = await message.getChat();
    return {
      groupId: serializedId(chat && chat.id) || groupId,
      groupName: String(chat && chat.name || "").trim(),
    };
  } catch (error) {
    if (typeof log === "function") {
      log("warn", "WhatsApp chat metadata unavailable; using message identifiers", {
        stage: "get_chat",
        groupId,
        messageId: messageId(message) || undefined,
        error: String(error && error.message || error || "unknown"),
      });
    }
    return { groupId, groupName: "" };
  }
}

function createMessageHandler({ clientId, postWebhook, outboundRegistry, log }) {
  if (typeof postWebhook !== "function") {
    throw new Error("postWebhook is required");
  }
  const registry = outboundRegistry || new RecentOutboundRegistry();

  return async function handleMessage(message) {
    const from = serializedId(message && message.from);
    if (!message || from === "status@broadcast") {
      return { ignored: true };
    }
    const body = trackedBody(message);
    if (!body) {
      return { ignored: true };
    }

    const externalMessageId = messageId(message) || null;
    const groupId = deriveGroupId(message);
    if (groupId) {
      const metadata = await groupMetadata(message, groupId, log);
      const systemGenerated = Boolean(message.fromMe)
        && registry.matches(metadata.groupId, body, externalMessageId);
      const payload = {
        clientId,
        groupId: metadata.groupId,
        groupName: metadata.groupName,
        from: serializedId(message.author) || from,
        fromName: String(message._data && message._data.notifyName || "").trim(),
        messageId: externalMessageId,
        timestamp: message.timestamp || null,
        fromMe: Boolean(message.fromMe),
        systemGenerated,
        message: body,
      };
      await postWebhook("/webhook/whatsapp-group-reply", payload);
      return { delivered: true, group: true, systemGenerated };
    }

    if (message.fromMe) {
      return { ignored: true };
    }
    await postWebhook("/webhook/whatsapp-reply", {
      clientId,
      from,
      messageId: externalMessageId,
      message: body,
    });
    return { delivered: true, group: false };
  };
}

function reconciliationPayloads({
  clientId,
  groupId,
  groupName,
  afterTimestamp,
  messages,
}) {
  const cutoffValue = Number(afterTimestamp);
  const cutoff = Number.isFinite(cutoffValue) ? Math.max(0, Math.floor(cutoffValue)) : 0;
  const seen = new Set();
  return (Array.isArray(messages) ? messages : [])
    .filter((message) => {
      const id = messageId(message);
      const timestamp = Number(message && message.timestamp);
      if (!message || message.fromMe || !id || seen.has(id) || !Number.isFinite(timestamp) || timestamp <= cutoff) {
        return false;
      }
      seen.add(id);
      return true;
    })
    .map((message) => ({
      clientId,
      groupId,
      groupName: String(groupName || "").trim().slice(0, 256),
      from: serializedId(message.author) || serializedId(message.from),
      fromName: String(message._data && message._data.notifyName || "").trim(),
      messageId: messageId(message) || null,
      timestamp: Math.floor(Number(message.timestamp)),
      fromMe: false,
      systemGenerated: false,
      message: trackedBody(message),
    }))
    .filter((payload) => payload.message && payload.messageId);
}

module.exports = {
  DeliveredMessageCache,
  FileDeliveryIdempotencyStore,
  MemoryDeliveryIdempotencyStore,
  RecentOutboundRegistry,
  createMessageHandler,
  deriveGroupId,
  groupMetadata,
  messageId,
  outboundFingerprint,
  reconciliationPayloads,
  serializedId,
  trackedBody,
};
