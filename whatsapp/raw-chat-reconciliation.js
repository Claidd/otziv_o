"use strict";

const { serializedId } = require("./message-identity");

function normalizeMessage(data) {
  if (!data || typeof data !== "object") {
    return null;
  }
  const hasMedia = Boolean(data.directPath || data.mediaObject);
  return {
    id: data.id,
    timestamp: Number(data.t || data.timestamp),
    from: serializedId(data.from),
    author: serializedId(data.author),
    fromMe: Boolean(data.id && data.id.fromMe),
    body: String(hasMedia ? data.caption || "" : data.body || data.pollName || data.eventName || ""),
    type: String(data.type || ""),
    hasMedia,
    _data: data,
  };
}

// Coverage is complete only when the loaded history reaches the requested
// cursor (a message at or before afterTimestamp) or WhatsApp reports no
// earlier messages, nothing newer than the cursor was dropped by the limit and
// no earlier-history load failed. A cache that merely ends is not coverage.
async function fetchRecentMessagesFromRawChat(client, groupId, limit = 100, { afterTimestamp, maxMessages = 1000 } = {}) {
  const page = client && client.pupPage;
  if (!page || typeof page.evaluate !== "function") {
    throw new Error("WhatsApp browser page is unavailable");
  }
  const chatId = serializedId(groupId);
  if (!chatId.endsWith("@g.us")) {
    throw new Error("A valid WhatsApp group id is required");
  }
  const messageLimit = Math.max(1, Math.min(100, Math.floor(Number(limit) || 100)));
  const cutoffValue = Number(afterTimestamp);
  const cutoff = afterTimestamp !== undefined && afterTimestamp !== null && Number.isFinite(cutoffValue)
    ? Math.max(0, Math.floor(cutoffValue)) : null;
  const loadLimit = Math.max(messageLimit, Math.min(10000, Math.floor(Number(maxMessages) || 1000)));

  const result = await page.evaluate(async ({ chatId: requestedChatId, messageLimit: requestedLimit,
    cutoff: requestedCutoff = null, loadLimit: requestedLoadLimit = 1000 }) => {
    const chat = await window.WWebJS.getChat(requestedChatId, { getAsModel: false });
    if (!chat) {
      return { found: false, groupName: "", messages: [], coverageComplete: false, oldestTimestamp: null };
    }

    const incoming = (message) => Boolean(message)
      && !message.isNotification
      && !(message.id && message.id.fromMe);
    const afterCutoff = (message) => requestedCutoff === null || Number(message && message.t) > requestedCutoff;
    const oldest = (messages) => messages.reduce((value, message) => {
      const timestamp = Number(message && message.t);
      return Number.isFinite(timestamp) && timestamp > 0 && timestamp < value ? timestamp : value;
    }, Infinity);
    const cached = chat.msgs && typeof chat.msgs.getModelsArray === "function"
      ? chat.msgs.getModelsArray()
      : [];
    let loaded = cached.slice();
    let exhausted = false;
    let loadFailed = false;

    while (loaded.filter(incoming).filter(afterCutoff).length < requestedLimit
        && !(requestedCutoff !== null && oldest(loaded) <= requestedCutoff)
        && loaded.length < requestedLoadLimit) {
      let earlier;
      try {
        earlier = await window.require("WAWebChatLoadMessages").loadEarlierMsgs({ chat });
      } catch (_ignoredError) {
        loadFailed = true;
        break;
      }
      if (!Array.isArray(earlier) || earlier.length === 0) {
        exhausted = true;
        break;
      }
      loaded = [...earlier, ...loaded];
    }

    let messages = loaded.filter(incoming);
    messages.sort((left, right) => Number(left && left.t || 0) - Number(right && right.t || 0));
    let truncated = false;
    if (messages.length > requestedLimit) {
      truncated = messages.slice(0, messages.length - requestedLimit).some(afterCutoff);
      messages = messages.slice(messages.length - requestedLimit);
    }
    const oldestLoaded = oldest(loaded);
    const coverageComplete = !loadFailed && !truncated
      && (exhausted || (requestedCutoff !== null && oldestLoaded <= requestedCutoff));

    const plainMessages = messages.map((message) => {
      try {
        return window.WWebJS.getMessageModel(message);
      } catch (_ignoredError) {
        const id = message && message.id;
        const asString = (value) => {
          if (!value) return "";
          if (typeof value === "string") return value;
          if (typeof value._serialized === "string") return value._serialized;
          return typeof value.toString === "function" ? value.toString() : "";
        };
        return {
          id: {
            _serialized: asString(id),
            fromMe: Boolean(id && id.fromMe),
          },
          t: Number(message && message.t || 0),
          from: asString(message && message.from),
          author: asString(message && message.author),
          body: String(message && message.body || ""),
          caption: String(message && message.caption || ""),
          type: String(message && message.type || ""),
          directPath: message && message.directPath || null,
          notifyName: String(message && message.notifyName || ""),
        };
      }
    });

    return {
      found: true,
      groupName: String(
        chat.name
        || chat.formattedTitle
        || chat.groupMetadata && chat.groupMetadata.subject
        || ""
      ),
      messages: plainMessages,
      coverageComplete,
      oldestTimestamp: Number.isFinite(oldestLoaded) ? oldestLoaded : null,
    };
  }, { chatId, messageLimit, cutoff, loadLimit });

  return {
    found: Boolean(result && result.found),
    groupName: String(result && result.groupName || "").trim(),
    messages: (Array.isArray(result && result.messages) ? result.messages : [])
      .map(normalizeMessage)
      .filter(Boolean),
    coverageComplete: Boolean(result && result.found && result.coverageComplete === true),
    oldestTimestamp: result && Number.isFinite(Number(result.oldestTimestamp)) && result.oldestTimestamp !== null
      ? Number(result.oldestTimestamp) : null,
  };
}

// Additive /groups/reconcile-messages fields. A failed or partially read group
// makes the whole response incomplete.
function groupCoverageSummary(batches) {
  const groupCoverage = (Array.isArray(batches) ? batches : []).map(batch => ({
    groupId: batch.groupId,
    coverageComplete: !batch.failed && batch.coverageComplete === true,
    source: batch.failed ? "failed" : batch.source || "direct",
  }));
  return {
    coverageComplete: groupCoverage.length > 0 && groupCoverage.every(group => group.coverageComplete),
    groupCoverage,
  };
}

module.exports = {
  fetchRecentMessagesFromRawChat,
  groupCoverageSummary,
  normalizeMessage,
};
