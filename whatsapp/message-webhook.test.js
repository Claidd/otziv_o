"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const {
  DeliveredMessageCache,
  FileDeliveryIdempotencyStore,
  RecentOutboundRegistry,
  createMessageHandler,
  deriveGroupId,
  reconciliationPayloads,
  trackedBody,
} = require("./message-webhook");

test("derives group id without loading chat metadata", () => {
  assert.equal(deriveGroupId({ from: "12001@g.us" }), "12001@g.us");
  assert.equal(deriveGroupId({ from: "100@c.us", to: "12002@g.us" }), "12002@g.us");
});

test("delivers group webhook when getChat throws", async () => {
  const calls = [];
  const logs = [];
  const handler = createMessageHandler({
    clientId: "whatsapp_vika",
    postWebhook: async (path, payload) => calls.push({ path, payload }),
    log: (level, message, extra) => logs.push({ level, message, extra }),
  });

  await handler({
    from: "12001@g.us",
    author: "79990001122@c.us",
    body: "Отключить уведомления",
    id: { _serialized: "message-1" },
    getChat: async () => { throw new Error("r"); },
  });

  assert.equal(calls.length, 1);
  assert.equal(calls[0].payload.groupId, "12001@g.us");
  assert.equal(calls[0].payload.groupName, "");
  assert.equal(calls[0].payload.message, "Отключить уведомления");
  assert.equal(logs[0].level, "info");
  assert.equal(logs[0].message, "WhatsApp chat metadata fallback used; delivery continuing");
  assert.equal(logs[0].extra.stage, "get_chat");
});

test("marks gateway generated group messages without marking manual messages", async () => {
  const calls = [];
  const registry = new RecentOutboundRegistry();
  registry.begin("12001@g.us", "Автоматический отчёт");
  const handler = createMessageHandler({
    clientId: "whatsapp_vika",
    outboundRegistry: registry,
    postWebhook: async (path, payload) => calls.push(payload),
  });

  await handler({ from: "12001@g.us", body: "Автоматический отчёт", fromMe: true });
  await handler({ from: "12001@g.us", body: "Ответ менеджера", fromMe: true });

  assert.equal(calls[0].systemGenerated, true);
  assert.equal(calls[1].systemGenerated, false);
});

test("deduplicates concurrent and repeated deliveries by message id", async () => {
  const cache = new DeliveredMessageCache();
  let calls = 0;
  let release;
  const pending = new Promise((resolve) => { release = resolve; });
  const payload = { clientId: "vika", groupId: "group", messageId: "same" };
  const sender = async () => { calls += 1; await pending; return { ok: true }; };

  const first = cache.deliver("/group", payload, sender);
  const second = cache.deliver("/group", payload, sender);
  release();
  await Promise.all([first, second]);
  const third = await cache.deliver("/group", payload, sender);

  assert.equal(calls, 1);
  assert.equal(third.duplicate, true);
});

test("persists delivery idempotency across gateway process instances", async (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "whatsapp-delivery-test-"));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const filePath = path.join(directory, "delivered.json");
  const payload = { clientId: "vika", groupId: "group", messageId: "durable" };
  let calls = 0;

  const first = new DeliveredMessageCache(
    60_000,
    () => 1_000,
    new FileDeliveryIdempotencyStore(filePath, () => 1_000),
  );
  await first.deliver("/group", payload, async () => { calls += 1; return { ok: true }; });

  const afterRestart = new DeliveredMessageCache(
    60_000,
    () => 2_000,
    new FileDeliveryIdempotencyStore(filePath, () => 2_000),
  );
  const result = await afterRestart.deliver("/group", payload, async () => { calls += 1; });

  assert.equal(calls, 1);
  assert.equal(result.duplicate, true);
  if (process.platform !== "win32") {
    assert.equal(fs.statSync(filePath).mode & 0o777, 0o600);
  }
});

test("migrates the legacy delivery cache and bounds durable entries", async (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "whatsapp-delivery-migration-"));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const filePath = path.join(directory, "delivered.json");
  fs.writeFileSync(filePath, JSON.stringify({
    version: 1,
    entries: { legacy: 60_000 },
  }));

  const store = new FileDeliveryIdempotencyStore(filePath, () => 1_000, 3);
  assert.equal(await store.has("legacy"), true);
  await store.mark("one", 60_000);
  await store.mark("two", 60_000);
  await store.mark("three", 60_000);

  const afterRestart = new FileDeliveryIdempotencyStore(filePath, () => 2_000, 3);
  assert.equal(await afterRestart.has("legacy"), false);
  assert.equal(await afterRestart.has("one"), true);
  assert.equal(await afterRestart.has("three"), true);
  assert.equal(JSON.parse(fs.readFileSync(filePath, "utf8")).version, 2);
});

test("delivers media-only group messages with a stable placeholder", async () => {
  const calls = [];
  const handler = createMessageHandler({
    clientId: "whatsapp_vika",
    postWebhook: async (path, payload) => calls.push({ path, payload }),
  });

  await handler({
    from: "12001@g.us",
    id: { _serialized: "media-1" },
    type: "ptt",
    hasMedia: true,
    body: "",
  });

  assert.equal(trackedBody({ type: "ptt", hasMedia: true }), "[Вложение: ptt]");
  assert.equal(calls.length, 1);
  assert.equal(calls[0].payload.message, "[Вложение: ptt]");
});

test("reconciliation returns only incoming messages newer than the open-card cursor", () => {
  const payloads = reconciliationPayloads({
    clientId: "whatsapp_vika",
    groupId: "120363000000000000@g.us",
    groupName: "Клиент",
    afterTimestamp: 100,
    messages: [
      {
        id: { _serialized: "old" },
        timestamp: 100,
        author: "70000000001@c.us",
        body: "Старое сообщение",
      },
      {
        id: { _serialized: "bot" },
        timestamp: 110,
        author: "70000000002@c.us",
        body: "Автоматическое сообщение",
        fromMe: true,
      },
      {
        id: { _serialized: "reply" },
        timestamp: 120,
        author: "70000000003@c.us",
        body: "Ответ сотрудника",
        fromMe: false,
      },
    ],
  });

  assert.equal(payloads.length, 1);
  assert.equal(payloads[0].messageId, "reply");
  assert.equal(payloads[0].message, "Ответ сотрудника");
  assert.equal(payloads[0].fromMe, false);
});

test("reconciliation normalizes timestamps and removes duplicate message ids", () => {
  const payloads = reconciliationPayloads({
    clientId: "whatsapp_vika",
    groupId: "120363000000000000@g.us",
    groupName: " Клиент ",
    afterTimestamp: "100.9",
    messages: [
      { id: { _serialized: "same" }, timestamp: "101.8", body: "Первый" },
      { id: { _serialized: "same" }, timestamp: 102, body: "Дубликат" },
      { id: { _serialized: "invalid-time" }, timestamp: "not-a-number", body: "Ошибка" },
    ],
  });

  assert.equal(payloads.length, 1);
  assert.equal(payloads[0].messageId, "same");
  assert.equal(payloads[0].timestamp, 101);
  assert.equal(payloads[0].groupName, "Клиент");
});
