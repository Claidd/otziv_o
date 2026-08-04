"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  fetchRecentMessagesFromRawChat,
  normalizeMessage,
} = require("./raw-chat-reconciliation");

test("normalizes the raw WhatsApp message fields used by reconciliation", () => {
  const message = normalizeMessage({
    id: { _serialized: "message-1", fromMe: false },
    t: 123,
    from: { _serialized: "12001@g.us" },
    author: { _serialized: "79990001122@c.us" },
    body: "Ответ клиента",
    type: "chat",
    notifyName: "Клиент",
  });

  assert.equal(message.id._serialized, "message-1");
  assert.equal(message.timestamp, 123);
  assert.equal(message.from, "12001@g.us");
  assert.equal(message.author, "79990001122@c.us");
  assert.equal(message.body, "Ответ клиента");
  assert.equal(message._data.notifyName, "Клиент");
});

test("uses the raw chat model without serializing group metadata", async () => {
  const originalWindow = global.window;
  const calls = [];
  const rawMessages = [
    { id: { _serialized: "out", fromMe: true }, t: 100, body: "Бот" },
    { id: { _serialized: "old", fromMe: false }, t: 110, body: "Старый" },
    { id: { _serialized: "new", fromMe: false }, t: 120, body: "Новый" },
  ];
  global.window = {
    WWebJS: {
      getChat: async (groupId, options) => {
        calls.push({ groupId, options });
        return {
          formattedTitle: "Тестовая группа",
          msgs: { getModelsArray: () => rawMessages },
        };
      },
      getMessageModel: (message) => ({ ...message, from: "12001@g.us", author: "7999@c.us" }),
    },
    require: () => ({ loadEarlierMsgs: async () => [] }),
  };

  try {
    const client = {
      pupPage: {
        evaluate: async (callback, options) => callback(options),
      },
    };
    const result = await fetchRecentMessagesFromRawChat(client, "12001@g.us", 2);

    assert.deepEqual(calls, [{ groupId: "12001@g.us", options: { getAsModel: false } }]);
    assert.equal(result.found, true);
    assert.equal(result.groupName, "Тестовая группа");
    assert.deepEqual(result.messages.map((message) => message.id._serialized), ["old", "new"]);
  } finally {
    global.window = originalWindow;
  }
});

test("returns an empty result when the raw chat is no longer available", async () => {
  const originalWindow = global.window;
  global.window = {
    WWebJS: { getChat: async () => null },
  };

  try {
    const result = await fetchRecentMessagesFromRawChat({
      pupPage: { evaluate: async (callback, options) => callback(options) },
    }, "12001@g.us");
    assert.deepEqual(result, { found: false, groupName: "", messages: [] });
  } finally {
    global.window = originalWindow;
  }
});

test("rejects invalid group identifiers before browser evaluation", async () => {
  let evaluated = false;
  await assert.rejects(
    fetchRecentMessagesFromRawChat({
      pupPage: { evaluate: async () => { evaluated = true; } },
    }, "79990001122@c.us"),
    /valid WhatsApp group id/
  );
  assert.equal(evaluated, false);
});
