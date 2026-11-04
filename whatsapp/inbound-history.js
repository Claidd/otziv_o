"use strict";

const { Chat } = require("whatsapp-web.js");
const { serializedId, trackedBody, messageId, reconciliationPayloads } = require("./message-webhook");

// getChats() serializes every chat and refreshes group metadata for every
// group. One stalled metadata request can therefore block the entire history
// scan even while the authenticated browser is still responsive. Read only
// the collection's IDs and titles; Chat.fetchMessages() uses the raw chat by
// ID and does not require that metadata refresh.
async function discoverHistoryChats(client) {
  const descriptors = await client.pupPage.evaluate(() => {
    const collection = window.require("WAWebCollections").Chat;
    const chats = collection.getModelsArray();
    if (!Array.isArray(chats)) throw new Error("inbound_history_chat_list_invalid");
    return chats.map(chat => {
      const id = chat && chat.id;
      const chatId = id && (id._serialized || (typeof id.toString === "function" ? id.toString() : ""));
      return {
        id: typeof chatId === "string" ? chatId : "",
        name: String(chat && (chat.name || chat.formattedTitle || chat.groupMetadata && chat.groupMetadata.subject) || ""),
        isGroup: Boolean(chat && chat.groupMetadata),
      };
    });
  });
  if (!Array.isArray(descriptors)) throw new Error("inbound_history_chat_list_invalid");
  // Skipping a malformed ID would silently omit that chat while letting the
  // global history scan report success and release queued live messages.
  for (const descriptor of descriptors) {
    const id = descriptor && descriptor.id;
    if (typeof id !== "string" || id.length > 256 || !/^[^\s@]+@[A-Za-z0-9._-]+$/.test(id)) {
      throw new Error("inbound_history_chat_id_invalid");
    }
  }
  return descriptors.map(descriptor => new Chat(client, {
    id: { _serialized: descriptor.id },
    formattedTitle: descriptor.name,
    isGroup: descriptor.isGroup || descriptor.id.endsWith("@g.us"),
  }));
}

// whatsapp-web.js exposes a limit, not offset/cursor pagination. Expanding windows
// ask its supported loadEarlierMsgs implementation for earlier available history.
async function fetchAvailableWindow(chat, cutoff, { pageSize = 100, maxMessages = 10000 } = {}) {
  let limit = Math.min(pageSize, maxMessages);
  for (;;) {
    const messages = await chat.fetchMessages({ limit });
    if (!Array.isArray(messages)) throw new Error("inbound_history_invalid_response");
    const timestamps = messages.map(m => Number(m.timestamp)).filter(t => Number.isFinite(t) && t > 0);
    // Strictly older: many messages may share the cursor second, including more
    // than a whole window. Equality alone is not evidence of complete coverage.
    const crossedBoundary = timestamps.length && timestamps.reduce((oldest, timestamp) => Math.min(oldest, timestamp), Infinity) < cutoff;
    const exhaustedAvailableHistory = messages.length < limit;
    if (crossedBoundary || exhaustedAvailableHistory || limit >= maxMessages) {
      return { messages, covered: Boolean(crossedBoundary || exhaustedAvailableHistory) };
    }
    limit = Math.min(maxMessages, limit * 2);
  }
}

function withDeadline(promise, timeoutMs, code) {
  if (!(timeoutMs > 0)) return promise;
  let timer;
  return Promise.race([promise, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(code)), timeoutMs);
  })]).finally(() => clearTimeout(timer));
}

async function recoverHistory({ client, inbox, clientId, groupEnabled = true, outreachEnabled = false,
  outboundRegistry, generatedMessageStore, participantResolver, log = () => {},
  overlapSeconds = 120, pageSize = 100, maxMessages = 10000, discoveryTimeoutMs = 30000,
  chatTimeoutMs = 60000, maxChatFailures = 3, current = () => true }) {
  inbox.paused = true;
  let errors = 0;
  try {
    // Discovery is independent of backend OPEN conversations, including first replies.
    let discoveryTimer;
    const chats = await Promise.race([
      Promise.resolve().then(() => typeof client.pupPage?.evaluate === "function"
        ? discoverHistoryChats(client) : client.getChats()),
      new Promise((_, reject) => {
        discoveryTimer = setTimeout(() => reject(new Error("inbound_history_discovery_timeout")), discoveryTimeoutMs);
      }),
    ]).finally(() => clearTimeout(discoveryTimer));
    inbox.historyDiscoveryFailed = false;
    const eligibleChats = chats.map(chat => ({ chat, chatId: serializedId(chat.id) }))
      .filter(({ chat, chatId }) => {
        const isGroup = chat.isGroup || chatId.endsWith("@g.us");
        return chatId && chatId !== "status@broadcast" && (isGroup ? groupEnabled : outreachEnabled);
      });
    // Hold each chat until its own history is covered once in this client
    // generation. Other chats drain during a long scan instead of waiting
    // behind a global pause, and a periodic rescan never holds a covered chat.
    for (const { chatId } of eligibleChats) {
      if (!inbox.historyCoveredChats.has(chatId)) inbox.blockedHistoryChats.add(chatId);
    }
    inbox.paused = false;
    for (const { chat, chatId } of eligibleChats) {
      if (!current()) break;
      const isGroup = chat.isGroup || chatId.endsWith("@g.us");
      const cutoff = Math.max(inbox.metadata.since, inbox.cursor(chatId) - overlapSeconds);
      // A chat whose history keeps failing must still deliver live messages:
      // after the failure cap its hold is released and the gap stays visible.
      const incomplete = () => {
        inbox.historyGaps.add(chatId); errors += 1;
        const failures = (inbox.historyFailures.get(chatId) || 0) + 1;
        inbox.historyFailures.set(chatId, failures);
        if (inbox.historyCoveredChats.has(chatId)) return;
        if (failures < maxChatFailures) { inbox.blockedHistoryChats.add(chatId); return; }
        if (inbox.blockedHistoryChats.delete(chatId)) {
          log("warn", "Inbound history hold released after repeated failures",
            { stage: "history", code: "history_hold_released", failures });
        }
      };
      try {
        const window = await withDeadline(fetchAvailableWindow(chat, cutoff, { pageSize, maxMessages }),
          chatTimeoutMs, "inbound_history_chat_timeout");
        if (!current()) break;
        if (window.messages.some(m => trackedBody(m) && (!messageId(m) || !Number.isFinite(Number(m.timestamp))))) {
          throw new Error("inbound_history_identity_missing");
        }
        const candidates = window.messages.filter(m => Number(m.timestamp) >= cutoff)
          .sort((a, b) => Number(a.timestamp) - Number(b.timestamp));
        for (let offset = 0; offset < candidates.length; offset += pageSize) {
          const page = candidates.slice(offset, offset + pageSize);
          if (isGroup) {
            const payloads = await reconciliationPayloads({ clientId, groupId: chatId, groupName: chat.name,
              afterTimestamp: cutoff - 1, messages: page, outboundRegistry, generatedMessageStore, participantResolver, log });
            for (const payload of payloads) inbox.enqueue("/webhook/whatsapp-group-reply", payload);
          } else {
            for (const message of page) {
              if (message.fromMe || !trackedBody(message)) continue;
              inbox.enqueue("/webhook/outreach-reply", { clientId, from: chatId, messageId: messageId(message),
                timestamp: Number(message.timestamp), message: trackedBody(message) });
            }
          }
          // No cursor advancement when a configured cap hid older messages. A retry
          // after a crash before this write safely queues the same IDs again.
          if (window.covered && page.length) inbox.checkpoint(chatId, Number(page[page.length - 1].timestamp));
        }
        if (window.covered) {
          inbox.historyGaps.delete(chatId); inbox.blockedHistoryChats.delete(chatId);
          inbox.historyCoveredChats.add(chatId); inbox.historyFailures.delete(chatId);
        } else incomplete();
      } catch (error) {
        // Capacity must not deadlock the accepted prefix: drain it to create room,
        // then revisit the unchanged page cursor. Other history gaps hold the chat.
        if (error.message === "inbound_store_full") {
          inbox.historyGaps.add(chatId); errors += 1; inbox.blockedHistoryChats.delete(chatId);
        } else incomplete();
        log("warn", "Inbound history recovery incomplete", { stage: "history",
          code: error.code || (error.message === "inbound_history_chat_timeout" ? "history_chat_timeout" : "history_failed") });
      }
    }
  } catch (error) {
    inbox.historyDiscoveryFailed = true;
    throw error;
  } finally { inbox.paused = inbox.historyDiscoveryFailed || !current(); }
  return { errors, gaps: inbox.historyGaps.size };
}

module.exports = { discoverHistoryChats, fetchAvailableWindow, recoverHistory };
