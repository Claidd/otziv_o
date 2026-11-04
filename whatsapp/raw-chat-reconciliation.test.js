"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  fetchRecentMessagesFromRawChat,
  groupCoverageSummary,
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
    assert.deepEqual(result, { found: false, groupName: "", messages: [], coverageComplete: false, oldestTimestamp: null });
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

function rawClient(cached, loads) {
  const originalWindow = global.window;
  let calls = 0;
  global.window = {
    WWebJS: {
      getChat: async () => ({ name: "Group", msgs: { getModelsArray: () => cached } }),
      getMessageModel: (message) => ({ ...message, from: "12001@g.us" }),
    },
    require: () => ({ loadEarlierMsgs: async () => {
      const next = loads[calls++];
      if (next instanceof Error) throw next;
      return next;
    } }),
  };
  return {
    client: { pupPage: { evaluate: async (callback, options) => callback(options) } },
    restore: () => { global.window = originalWindow; },
    loads: () => calls,
  };
}
const raw = (id, t, fromMe = false) => ({ id: { _serialized: id, fromMe }, t, body: id });

test("raw history is covered only after reaching the requested cursor", async () => {
  const fixture = rawClient([raw("reply", 150), raw("bot", 160, true)], [[raw("older", 90), raw("cursor", 100)]]);
  try {
    const result = await fetchRecentMessagesFromRawChat(fixture.client, "12001@g.us", 100, { afterTimestamp: 100 });
    assert.equal(result.coverageComplete, true);
    assert.equal(result.oldestTimestamp, 90);
    assert.equal(fixture.loads(), 1);
  } finally { fixture.restore(); }
});

test("a failed earlier-history load or a limit-truncated window is not coverage", async () => {
  for (const [cached, loads, options, limit] of [
    [[raw("reply", 150)], [new Error("load failed")], { afterTimestamp: 100 }, 100],
    [[raw("a", 150), raw("b", 160), raw("c", 170)], [], { afterTimestamp: 100 }, 2],
    [Array.from({ length: 100 }, (_, i) => raw(`bot-${i}`, 200 + i, true)), [[raw("older", 50)]],
      { afterTimestamp: 100, maxMessages: 100 }, 100],
  ]) {
    const fixture = rawClient(cached, loads);
    try {
      const result = await fetchRecentMessagesFromRawChat(fixture.client, "12001@g.us", limit, options);
      assert.equal(result.found, true);
      assert.equal(result.coverageComplete, false);
    } finally { fixture.restore(); }
  }
});

test("explicitly exhausted history is coverage even when it starts after the cursor", async () => {
  const fixture = rawClient([raw("reply", 150)], [[]]);
  try {
    const result = await fetchRecentMessagesFromRawChat(fixture.client, "12001@g.us", 100, { afterTimestamp: 100 });
    assert.equal(result.coverageComplete, true);
  } finally { fixture.restore(); }
});

test("group coverage summary is complete only when every requested group is covered", () => {
  assert.deepEqual(groupCoverageSummary([
    { groupId: "a@g.us", failed: false, coverageComplete: true, source: "direct" },
    { groupId: "b@g.us", failed: false, coverageComplete: true, source: "raw" },
  ]).coverageComplete, true);
  const partial = groupCoverageSummary([
    { groupId: "a@g.us", failed: false, coverageComplete: true, source: "direct" },
    { groupId: "b@g.us", failed: true, coverageComplete: true },
    { groupId: "c@g.us", failed: false, coverageComplete: false, source: "raw" },
  ]);
  assert.equal(partial.coverageComplete, false);
  assert.deepEqual(partial.groupCoverage.map(group => [group.groupId, group.coverageComplete, group.source]),
    [["a@g.us", true, "direct"], ["b@g.us", false, "failed"], ["c@g.us", false, "raw"]]);
  assert.equal(groupCoverageSummary([]).coverageComplete, false);
});
