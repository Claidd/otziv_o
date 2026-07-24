"use strict";

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

class DeliveredMessageCache {
  constructor(ttlMs = 24 * 60 * 60 * 1000, now = () => Date.now()) {
    this.ttlMs = ttlMs;
    this.now = now;
    this.delivered = new Map();
    this.inFlight = new Map();
  }

  key(path, payload) {
    const id = String(payload && payload.messageId || "").trim();
    if (!id) {
      return "";
    }
    return `${path}|${String(payload.clientId || "").trim()}|${String(payload.groupId || payload.from || "").trim()}|${id}`;
  }

  async deliver(path, payload, sender) {
    this.cleanup();
    const key = this.key(path, payload);
    if (!key) {
      return sender();
    }
    if (this.delivered.has(key)) {
      return { duplicate: true };
    }
    if (this.inFlight.has(key)) {
      return this.inFlight.get(key);
    }
    const delivery = Promise.resolve()
      .then(sender)
      .then((result) => {
        this.delivered.set(key, this.now() + this.ttlMs);
        return result;
      })
      .finally(() => this.inFlight.delete(key));
    this.inFlight.set(key, delivery);
    return delivery;
  }

  cleanup() {
    const cutoff = this.now();
    for (const [key, expiresAt] of this.delivered.entries()) {
      if (expiresAt <= cutoff) {
        this.delivered.delete(key);
      }
    }
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

module.exports = {
  DeliveredMessageCache,
  RecentOutboundRegistry,
  createMessageHandler,
  deriveGroupId,
  groupMetadata,
  messageId,
  outboundFingerprint,
  serializedId,
  trackedBody,
};
