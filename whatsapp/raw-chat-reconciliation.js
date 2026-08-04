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

async function fetchRecentMessagesFromRawChat(client, groupId, limit = 100) {
  const page = client && client.pupPage;
  if (!page || typeof page.evaluate !== "function") {
    throw new Error("WhatsApp browser page is unavailable");
  }
  const chatId = serializedId(groupId);
  if (!chatId.endsWith("@g.us")) {
    throw new Error("A valid WhatsApp group id is required");
  }
  const messageLimit = Math.max(1, Math.min(100, Math.floor(Number(limit) || 100)));

  const result = await page.evaluate(async ({ chatId: requestedChatId, messageLimit: requestedLimit }) => {
    const chat = await window.WWebJS.getChat(requestedChatId, { getAsModel: false });
    if (!chat) {
      return { found: false, groupName: "", messages: [] };
    }

    const incoming = (message) => Boolean(message)
      && !message.isNotification
      && !(message.id && message.id.fromMe);
    const cached = chat.msgs && typeof chat.msgs.getModelsArray === "function"
      ? chat.msgs.getModelsArray()
      : [];
    let messages = cached.filter(incoming);

    while (messages.length < requestedLimit) {
      let earlier;
      try {
        earlier = await window.require("WAWebChatLoadMessages").loadEarlierMsgs({ chat });
      } catch (_ignoredError) {
        break;
      }
      if (!Array.isArray(earlier) || earlier.length === 0) {
        break;
      }
      messages = [...earlier.filter(incoming), ...messages];
    }

    messages.sort((left, right) => Number(left && left.t || 0) - Number(right && right.t || 0));
    if (messages.length > requestedLimit) {
      messages = messages.slice(messages.length - requestedLimit);
    }

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
    };
  }, { chatId, messageLimit });

  return {
    found: Boolean(result && result.found),
    groupName: String(result && result.groupName || "").trim(),
    messages: (Array.isArray(result && result.messages) ? result.messages : [])
      .map(normalizeMessage)
      .filter(Boolean),
  };
}

module.exports = {
  fetchRecentMessagesFromRawChat,
  normalizeMessage,
};
