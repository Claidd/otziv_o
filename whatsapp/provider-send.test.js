"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { sendProviderMessage } = require("./provider-send");

test("text and document sends bypass a stalled read acknowledgement", async () => {
  let readCalls = 0, sends = 0;
  const client = { async sendMessage(destination, content, options) {
    if (options.sendSeen !== false) { readCalls++; throw Error("sendSeen stalled"); }
    sends++;
    return { id: "receipt", destination, content, options };
  }};
  assert.equal((await sendProviderMessage(client, "chat", "text")).id, "receipt");
  const document = await sendProviderMessage(client, "chat", { data: "file" },
    { caption: "invoice", sendMediaAsDocument: true, sendSeen: true });
  assert.equal(document.options.caption, "invoice");
  assert.equal(document.options.sendMediaAsDocument, true);
  assert.equal(readCalls, 0);
  assert.equal(sends, 2);
});

test("provider timeout remains ambiguous and is never retried", async () => {
  let calls = 0;
  const failure = Error("Runtime.callFunctionOn timed out");
  const client = { async sendMessage() { calls++; throw failure; } };
  await assert.rejects(sendProviderMessage(client, "chat", "text", {}, { ackTimeoutMs: 50 }), e => e === failure);
  assert.equal(calls, 1);
});

const created = (ack = 0) => ({ id: { _serialized: "true_chat_ID1" }, ack, timestamp: 100 });

test("an already acknowledged message returns without waiting", async () => {
  let reads = 0;
  const client = { sendMessage: async () => created(1), getMessageById: async () => { reads++; return created(2); } };
  const sent = await sendProviderMessage(client, "chat", "text", {}, { ackTimeoutMs: 10_000 });
  assert.equal(sent.ack, 1); assert.equal(reads, 0);
});

test("a server ACK event completes the wait for this exact message", async () => {
  const acks = new EventEmitter();
  const client = { sendMessage: async () => created(0) };
  const pending = sendProviderMessage(client, "chat", "text", {}, { ackTimeoutMs: 10_000, pollMs: 10_000, acks });
  await new Promise(resolve => setImmediate(resolve));
  acks.emit("ack", "another-message", 3);
  acks.emit("ack", "true_chat_ID1", 1);
  assert.equal((await pending).ack, 1);
  assert.equal(acks.listenerCount("ack"), 0);
});

test("polling observes an ACK that arrived before the listener existed", async () => {
  const client = { sendMessage: async () => created(0), getMessageById: async id => ({ ...created(2), id: { _serialized: id } }) };
  assert.equal((await sendProviderMessage(client, "chat", "text", {}, { ackTimeoutMs: 5_000, pollMs: 5 })).ack, 2);
});

test("an ACK that never progresses returns the created message with its last ACK, never an error", async () => {
  let reads = 0;
  const client = { sendMessage: async () => created(0), getMessageById: async () => { reads++; throw new Error("evaluate failed"); } };
  const started = Date.now();
  const sent = await sendProviderMessage(client, "chat", "text", {}, { ackTimeoutMs: 60, pollMs: 10 });
  assert.equal(sent.ack, 0); assert.equal(sent.id._serialized, "true_chat_ID1");
  assert.ok(Date.now() - started >= 50); assert.ok(reads >= 1);
});

test("ACK waiting is disabled by default and for legacy message shapes", async () => {
  const client = { sendMessage: async () => created(0), getMessageById: assert.fail };
  assert.equal((await sendProviderMessage(client, "chat", "text")).ack, 0);
  const legacy = { sendMessage: async () => ({ id: "legacy" }), getMessageById: assert.fail };
  assert.equal((await sendProviderMessage(legacy, "chat", "text", {}, { ackTimeoutMs: 1000 })).id, "legacy");
});
