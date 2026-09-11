"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { DurableInboundInbox, identity } = require("./inbound-inbox");
const { recoverHistory, fetchAvailableWindow } = require("./inbound-history");
const { createMessageHandler } = require("./message-webhook");
const route = "/webhook/whatsapp-group-reply";
const payload = (id = "m1", timestamp = 1, groupId = "first@g.us") =>
  ({ clientId: "client", groupId, messageId: id, timestamp, from: "user@c.us", message: "hello" });
function fixture(t, options = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "wa-inbound-"));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  return { directory, inbox: new DurableInboundInbox(directory, { clientId: "client", since: 0, ...options }) };
}
function chat(messages, limits = []) {
  return { id: { _serialized: "first@g.us" }, isGroup: true, name: "First chat",
    async fetchMessages({ limit }) { limits.push(limit); return messages.slice(-limit); } };
}
const message = (n) => ({ id: { _serialized: `m${n}` }, from: "first@g.us", author: "user@c.us",
  timestamp: n, body: `body ${n}`, fromMe: false, type: "chat" });

test("outage beyond old retry budget and process restart retains payload until actual ACK", async t => {
  let now = 1000; const { directory, inbox } = fixture(t, { now: () => now });
  inbox.enqueue(route, payload());
  for (let i = 0; i < 8; i++) { await inbox.drain(async () => ({ status: 503 })); now += 300001; }
  assert.equal(inbox.records.values().next().value.attempts, 8);
  const restarted = new DurableInboundInbox(directory, { clientId: "client", now: () => now });
  assert.equal(restarted.snapshot().pending, 1);
  let observed;
  await restarted.drain(async (_route, body) => { observed = body; return { status: 200 }; });
  assert.deepEqual(observed, payload());
  assert.equal(new DurableInboundInbox(directory, { clientId: "client" }).snapshot().pending, 0);
});

test("backend commit followed by lost ACK replays identical identity after gateway restart", async t => {
  let now = 1000; const { directory, inbox } = fixture(t, { now: () => now });
  const receiverFile = path.join(directory, "receiver-fixture.json");
  fs.writeFileSync(receiverFile, JSON.stringify({ receipts: [], effects: 0 }));
  let loseAck = true; let calls = 0;
  async function receiver(route, body) {
    calls += 1;
    const db = JSON.parse(fs.readFileSync(receiverFile, "utf8"));
    const key = identity(route, body);
    if (!db.receipts.includes(key)) {
      db.receipts.push(key); db.effects += 1;
      fs.writeFileSync(receiverFile, JSON.stringify(db)); // committed receiver fixture
    }
    if (loseAck) { loseAck = false; throw new Error("connection lost after commit"); }
    return { status: 200 };
  }
  inbox.enqueue(route, payload()); await inbox.drain(receiver);
  assert.equal(inbox.snapshot().pending, 1);
  now += 300001;
  await new DurableInboundInbox(directory, { clientId: "client", now: () => now }).drain(receiver);
  assert.equal(calls, 2);
  assert.equal(JSON.parse(fs.readFileSync(receiverFile, "utf8")).effects, 1);
});

test("concurrent drain never ACKs or removes an in-flight record; 409 and 202 remain pending", async t => {
  let now = 1000; const { inbox } = fixture(t, { now: () => now }); inbox.enqueue(route, payload());
  let release; let calls = 0;
  const first = inbox.drain(async () => { calls += 1; return new Promise(resolve => { release = resolve; }); });
  await inbox.drain(async () => { calls += 1; return { status: 200 }; });
  assert.equal(calls, 1); assert.equal(inbox.snapshot().pending, 1);
  release({ status: 409 }); await first;
  now += 300001; await inbox.drain(async () => ({ status: 202 }));
  assert.equal(inbox.snapshot().pending, 1);
  now += 300001; await inbox.drain(async () => ({ status: 200 })); assert.equal(inbox.snapshot().pending, 0);
});

test("persistent dead letter is visible, blocks later same-chat effects, and retries same ID", async t => {
  const { directory, inbox } = fixture(t); const queued = inbox.enqueue(route, payload());
  inbox.enqueue(route, payload("m2", 2)); inbox.enqueue(route, payload("other", 3, "other@g.us"));
  const sent = [];
  await inbox.drain(async (_route, body) => { sent.push(body.messageId); return { status: body.messageId === "m1" ? 422 : 200 }; });
  assert.deepEqual(sent, ["m1", "other"]);
  const restarted = new DurableInboundInbox(directory, { clientId: "client" });
  assert.equal(restarted.deadLetters()[0].key, queued.key);
  assert.equal(restarted.retry(queued.key), true);
  const replayed = []; await restarted.drain(async (_route, body) => { replayed.push(body.messageId); return { status: 200 }; });
  assert.deepEqual(replayed, ["m1", "m2"]);
});

test("disk write failure cannot report queued, advance cursor, or send unpersisted input", async t => {
  const { directory } = fixture(t);
  let failed = false;
  const io = new Proxy(fs, { get(target, prop) {
    if (prop === "fsyncSync") return fd => { if (failed) throw Object.assign(new Error("disk full"), { code: "ENOSPC" }); return fs.fsyncSync(fd); };
    return target[prop];
  } });
  const inbox = new DurableInboundInbox(directory, { clientId: "client", io }); failed = true;
  assert.throws(() => inbox.enqueue(route, payload()), /disk full/);
  assert.equal(inbox.snapshot().healthy, false); assert.equal(inbox.snapshot().persistenceFailures, 1);
  assert.throws(() => inbox.checkpoint("first@g.us", 100), /unhealthy/);
  let sent = false; await inbox.drain(async () => { sent = true; return { status: 200 }; }); assert.equal(sent, false);
  assert.equal(new DurableInboundInbox(directory, { clientId: "client" }).cursor("first@g.us"), 0);
});

test("capacity refuses new input but continues draining accepted records", async t => {
  const { inbox } = fixture(t, { maxRecords: 1 }); inbox.enqueue(route, payload());
  assert.throws(() => inbox.enqueue(route, payload("m2")), /inbound_store_full/);
  await inbox.drain(async () => ({ status: 200 })); inbox.enqueue(route, payload("m2"));
  assert.equal(inbox.snapshot().pending, 1);
});

test("first chat with 350 messages uses supported expanding windows and durable page checkpoints", async t => {
  const { directory, inbox } = fixture(t); const messages = Array.from({ length: 350 }, (_, i) => message(i + 1));
  const limits = []; const client = { getChats: async () => [chat(messages, limits)] };
  const result = await recoverHistory({ client, inbox, clientId: "client" });
  assert.equal(result.errors, 0); assert.deepEqual(limits, [100, 200, 400]);
  assert.equal(inbox.snapshot().pending, 350); assert.equal(inbox.cursor("first@g.us"), 350);
  const restarted = new DurableInboundInbox(directory, { clientId: "client" });
  await recoverHistory({ client, inbox: restarted, clientId: "client" });
  assert.equal(restarted.snapshot().pending, 350); // overlap reuses stable IDs
});

test("history cap is an explicit gap and cannot skip older recoverable messages", async t => {
  const { inbox } = fixture(t); const messages = Array.from({ length: 350 }, (_, i) => message(i + 1));
  const client = { getChats: async () => [chat(messages)] };
  await recoverHistory({ client, inbox, clientId: "client", maxMessages: 100 });
  assert.equal(inbox.cursor("first@g.us"), 0); assert.equal(inbox.snapshot().historyGapChats, 1);
  assert.equal(inbox.snapshot().pending, 100);
  let prematurelySent = false;
  await inbox.drain(async () => { prematurelySent = true; return { status: 200 }; });
  assert.equal(prematurelySent, false);
  await recoverHistory({ client, inbox, clientId: "client", maxMessages: 400 });
  assert.equal(inbox.snapshot().pending, 350); assert.equal(inbox.cursor("first@g.us"), 350);
  assert.equal(inbox.snapshot().historyGapChats, 0);
});

test("more than 100 messages in the exact initial cursor second cannot truncate recovery", async t => {
  const { inbox } = fixture(t, { since: 1 });
  const messages = Array.from({ length: 350 }, (_, i) => ({ ...message(i + 1), timestamp: 1 }));
  const limits = [];
  await recoverHistory({ client: { getChats: async () => [chat(messages, limits)] }, inbox, clientId: "client" });
  assert.deepEqual(limits, [100, 200, 400]); assert.equal(inbox.snapshot().pending, 350);
});

test("failure midway through durable page enqueue leaves cursor before page; restart recovers entire page", async t => {
  const { directory, inbox } = fixture(t, { maxRecords: 75 });
  const client = { getChats: async () => [chat(Array.from({ length: 150 }, (_, i) => message(i + 1)))] };
  await recoverHistory({ client, inbox, clientId: "client" });
  assert.equal(inbox.snapshot().pending, 75); assert.equal(inbox.cursor("first@g.us"), 0);
  const restarted = new DurableInboundInbox(directory, { clientId: "client", maxRecords: 1000 });
  await recoverHistory({ client, inbox: restarted, clientId: "client" });
  assert.equal(restarted.snapshot().pending, 150); assert.equal(restarted.cursor("first@g.us"), 150);
});

test("history persistence capacity failure does not deadlock accepted messages", async t => {
  const { inbox } = fixture(t, { maxRecords: 75 });
  const messages = Array.from({ length: 150 }, (_, i) => message(i + 1));
  const client = { getChats: async () => [chat(messages)] };
  await recoverHistory({ client, inbox, clientId: "client" });
  assert.equal(inbox.snapshot().pending, 75); assert.equal(inbox.cursor("first@g.us"), 0);
  const received = new Set();
  await inbox.drain(async (_route, body) => { received.add(body.messageId); return { status: 200 }; }, { limit: 75 });
  assert.equal(inbox.snapshot().pending, 0); assert.equal(received.size, 75);
  await recoverHistory({ client, inbox, clientId: "client" });
  assert.equal(inbox.cursor("first@g.us"), 150);
  await inbox.drain(async (_route, body) => { received.add(body.messageId); return { status: 200 }; }, { limit: 75 });
  assert.equal(received.size, 150); assert.equal(inbox.snapshot().pending, 0);
});

test("process death after enqueue before cursor preserves payload and overlap recovery", async t => {
  const { directory } = fixture(t);
  const child = spawnSync(process.execPath, ["-e", `
    const {DurableInboundInbox}=require(${JSON.stringify(require.resolve("./inbound-inbox"))});
    const inbox=new DurableInboundInbox(${JSON.stringify(directory)},{clientId:"client"});
    inbox.enqueue(${JSON.stringify(route)},${JSON.stringify(payload())}); process.exit(17);
  `], { timeout: 10000 });
  assert.equal(child.status, 17);
  const restarted = new DurableInboundInbox(directory, { clientId: "client" });
  assert.equal(restarted.snapshot().pending, 1); assert.equal(restarted.cursor("first@g.us"), 0);
  await recoverHistory({ client: { getChats: async () => [chat([message(1), message(2)])] }, inbox: restarted, clientId: "client" });
  assert.equal(restarted.snapshot().pending, 2); assert.equal(restarted.cursor("first@g.us"), 2);
});

test("fetch failure leaves cursor unchanged and later first-chat discovery recovers input", async t => {
  const { inbox } = fixture(t);
  const broken = chat([]); broken.fetchMessages = async () => { throw new Error("disconnected"); };
  await recoverHistory({ client: { getChats: async () => [broken] }, inbox, clientId: "client" });
  assert.equal(inbox.cursor("first@g.us"), 0); assert.equal(inbox.snapshot().historyGapChats, 1);
  await recoverHistory({ client: { getChats: async () => [chat([message(1)])] }, inbox, clientId: "client" });
  assert.equal(inbox.snapshot().pending, 1); assert.equal(inbox.snapshot().historyGapChats, 0);
});

test("initial cutover boundary persists across restart and client identity cannot reuse a volume", t => {
  const { directory } = fixture(t, { since: 500 });
  assert.equal(new DurableInboundInbox(directory, { clientId: "client", since: 999 }).cursor("new@g.us"), 500);
  assert.throws(() => new DurableInboundInbox(directory, { clientId: "other" }), /identity_mismatch/);
  assert.throws(() => identity(route, { ...payload(), messageId: null }), /identity_required/);
});

test("history snapshots claim only available history, never a fictitious offset API", async () => {
  const requested = [];
  const result = await fetchAvailableWindow({ async fetchMessages(options) { requested.push(options); return [message(100)]; } }, 0);
  assert.equal(result.covered, true); assert.deepEqual(requested, [{ limit: 100 }]);
});

test("live handler reports durable queue admission and never HTTP delivery before a worker ACK", async t => {
  const { directory, inbox } = fixture(t);
  const handle = createMessageHandler({ clientId: "client", postWebhook: (route, payload) => inbox.enqueue(route, payload) });
  const result = await handle(message(1));
  assert.equal(result.queued, true); assert.equal(result.delivered, undefined);
  assert.equal(new DurableInboundInbox(directory, { clientId: "client" }).snapshot().pending, 1);
});

test("discovery failure is visible and holds dispatch until a successful recovery cycle", async t => {
  const { inbox } = fixture(t); inbox.enqueue(route, payload());
  await assert.rejects(recoverHistory({ client: { getChats: async () => { throw new Error("browser unavailable"); } }, inbox, clientId: "client" }));
  assert.equal(inbox.snapshot().historyDiscoveryFailed, true); assert.equal(inbox.paused, true);
  let sent = false; await inbox.drain(async () => { sent = true; return { status: 200 }; }); assert.equal(sent, false);
  await recoverHistory({ client: { getChats: async () => [chat([message(1)])] }, inbox, clientId: "client" });
  assert.equal(inbox.snapshot().historyDiscoveryFailed, false); assert.equal(inbox.paused, false);
});
