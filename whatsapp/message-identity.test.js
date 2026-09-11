"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const { serializedId, installBrowserMessageIdentity, installMessageIdentityCompatibility } = require("./message-identity");

test("old and current JSON MsgKeys retain the same identity, including participant", () => {
  const id = "true_120000@g.us_ABC123_79000000000@c.us";
  const modern = { fromMe: true, remote: { _serialized: "120000@g.us" }, id: "ABC123",
    participant: { _serialized: "79000000000@c.us" }, $1: id };
  assert.equal(serializedId({ _serialized: id }), id);
  assert.equal(serializedId(JSON.parse(JSON.stringify(modern))), id);
  delete modern.$1;
  assert.equal(serializedId(modern), id); // No dependence on minified field names.
  assert.equal(serializedId({ fromMe: false, remote: "79000000000@c.us", id: "ABC" }), "false_79000000000@c.us_ABC");
  assert.equal(serializedId({}), "");
  assert.equal(serializedId({ fromMe: true, id: "ABC" }), "");
});

test("live prototype and transported message both support library lookups after adaptation", () => {
  class MsgKey {
    constructor() { this.fromMe = true; this.remote = { _serialized: "120000@g.us" }; this.id = "ABC"; this.$1 = "true_120000@g.us_ABC"; }
    toString() { return this.$1; }
  }
  const key = new MsgKey();
  const lookup = new Map([[key.toString(), { id: key }]]);
  const WWebJS = {
    getMessageModel: message => ({ id: { ...message.id, remote: message.id.remote._serialized } }),
    getChatModel: chat => {
      const message = lookup.get(chat.lastReceivedKey._serialized);
      if (!message) throw new Error("missing lookup key");
      return { lastMessage: WWebJS.getMessageModel(message) };
    },
  };
  assert.throws(() => WWebJS.getChatModel({ lastReceivedKey: key }), /missing lookup key/);
  const context = vm.createContext({ window: { require: name => { assert.equal(name, "WAWebMsgKey"); return MsgKey; }, WWebJS } });
  vm.runInContext(`(${installBrowserMessageIdentity.toString()})()`, context);
  const once = WWebJS.getMessageModel;
  vm.runInContext(`(${installBrowserMessageIdentity.toString()})()`, context);
  assert.equal(WWebJS.getMessageModel, once);
  assert.equal(key._serialized, key.toString());
  const transported = JSON.parse(JSON.stringify(WWebJS.getChatModel({ lastReceivedKey: key })));
  assert.equal(transported.lastMessage.id._serialized, key.toString());
  assert.equal(transported.lastMessage.id.remote, "120000@g.us");
  key._serialized = "legacy-own-field";
  assert.equal(key._serialized, "legacy-own-field");
});

test("compatibility runs before event listeners on each reinjection and fails closed", async () => {
  const events = [];
  const client = { pupPage: { evaluate: async fn => { assert.equal(fn, installBrowserMessageIdentity); events.push("compat"); } },
    async attachEventListeners(value) { assert.equal(this, client); events.push(value); } };
  installMessageIdentityCompatibility(client);
  await client.attachEventListeners("listeners");
  await client.attachEventListeners("reinjected");
  assert.deepEqual(events, ["compat", "listeners", "compat", "reinjected"]);
  client.pupPage.evaluate = async () => { throw new Error("unsupported"); };
  await assert.rejects(client.attachEventListeners("must-not-run"), /unsupported/);
  assert.equal(events.length, 4);
});
