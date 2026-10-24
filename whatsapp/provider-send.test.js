"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
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
  await assert.rejects(sendProviderMessage(client, "chat", "text"), e => e === failure);
  assert.equal(calls, 1);
});
