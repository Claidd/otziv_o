"use strict";

const { serializedId, trackedBody, messageId, reconciliationPayloads } = require("./message-webhook");

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

async function recoverHistory({ client, inbox, clientId, groupEnabled = true, outreachEnabled = false,
  outboundRegistry, generatedMessageStore, participantResolver, log = () => {},
  overlapSeconds = 120, pageSize = 100, maxMessages = 10000, current = () => true }) {
  inbox.paused = true;
  let errors = 0;
  try {
    // Discovery is independent of backend OPEN conversations, including first replies.
    const chats = await client.getChats();
    inbox.historyDiscoveryFailed = false;
    for (const chat of chats) {
      if (!current()) break;
      const chatId = serializedId(chat.id);
      const isGroup = chat.isGroup || chatId.endsWith("@g.us");
      if (!chatId || chatId === "status@broadcast" || (isGroup ? !groupEnabled : !outreachEnabled)) continue;
      const cutoff = Math.max(inbox.metadata.since, inbox.cursor(chatId) - overlapSeconds);
      try {
        const window = await fetchAvailableWindow(chat, cutoff, { pageSize, maxMessages });
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
        if (window.covered) { inbox.historyGaps.delete(chatId); inbox.blockedHistoryChats.delete(chatId); }
        else { inbox.historyGaps.add(chatId); inbox.blockedHistoryChats.add(chatId); errors += 1; }
      } catch (error) {
        inbox.historyGaps.add(chatId); errors += 1;
        // Capacity must not deadlock the accepted prefix: drain it to create room,
        // then revisit the unchanged page cursor. Other history gaps hold the chat.
        if (error.message === "inbound_store_full") inbox.blockedHistoryChats.delete(chatId);
        else inbox.blockedHistoryChats.add(chatId);
        log("warn", "Inbound history recovery incomplete", { stage: "history", code: error.code || "history_failed" });
      }
    }
  } catch (error) {
    inbox.historyDiscoveryFailed = true;
    throw error;
  } finally { inbox.paused = inbox.historyDiscoveryFailed || !current(); }
  return { errors, gaps: inbox.historyGaps.size };
}

module.exports = { fetchAvailableWindow, recoverHistory };
