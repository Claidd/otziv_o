"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const { installBrowserDocumentMediaCompatibility, installDocumentMediaCompatibility } = require("./document-media-compatibility");

// Run the installed library's real message assembly, rather than reproducing
// its object spread in the test. The fake provider constructs a Msg model and
// rejects the same private-ID collision before recording any dispatch.
function fixture() {
  const source = fs.readFileSync(require.resolve("whatsapp-web.js/src/util/Injected/Utils"), "utf8");
  const start = source.indexOf("window.WWebJS.sendMessage = async (");
  assert.ok(start >= 0, "Pinned library message builder changed");
  const end = source.indexOf("\n    window.WWebJS.", start + 1);
  assert.ok(end > start);
  const records = new Map();
  const effects = { calls: 0, dispatches: 0 };
  class MsgKey {
    constructor({ id, to }) { this.id = id; this.remote = to; this._serialized = `true_${to._serialized}_${id}`; }
    static async newId() { return "FIXTURE"; }
  }
  class MediaData {
    constructor() { this.__x_id = undefined; this.__x_preview = "preview"; }
    get preview() { return this.__x_preview; }
    toJSON() { assert.equal(this, prepared); return { type: "document", filehash: "file-hash", mimetype: "application/pdf", filename: "Счёт.pdf", size: 123 }; }
  }
  const prepared = new MediaData();
  const wid = { _serialized: "79000000000@c.us" };
  const chat = { id: { _serialized: "120000000000@g.us", isGroup: () => true, isLid: () => false, isStatus: () => false }, groupMetadata: {} };
  const modules = {
    WAWebChatGetters: { getIsNewsletter: () => false, getIsBroadcast: () => false },
    WALinkify: { findLink: () => undefined },
    WAWebUserPrefsMeUser: { getMaybeMeLidUser: () => wid, getMaybeMePnUser: () => wid },
    WAWebWidFactory: { asUserWidOrThrow: value => value },
    WAWebMsgKey: MsgKey,
    WAWebGetEphemeralFieldsMsgActionsUtils: { getEphemeralFields: () => ({}) },
    WAWebCollections: { Msg: { get: id => records.get(id) } },
    WAWebSendMsgChatAction: { addAndSendMsgToChat: (_chat, data) => {
      const model = { __x_id: data.id, ...data };
      if (!model.__x_id?.id) throw new Error("Data passed to getter must include an id property");
      effects.dispatches++;
      records.set(data.id._serialized, model);
      return [Promise.resolve(model), Promise.resolve()];
    } },
  };
  const WWebJS = { processMediaData: async function (...args) { effects.calls++; effects.receiver = this; effects.arguments = args; return prepared; } };
  const window = { WWebJS, require(name) { assert.ok(modules[name], `Unexpected provider module: ${name}`); return modules[name]; } };
  const context = vm.createContext({ window, console, Date, Uint8Array });
  vm.runInContext(source.slice(start, end), context);
  return { prepared, WWebJS, context, effects, chat };
}

test("real pinned document builder preserves MsgKey despite MediaData.__x_id", async () => {
  const f = fixture();
  const options = () => ({ media: { mimetype: "application/pdf", data: "JVBERg==" }, sendMediaAsDocument: true, caption: "Счёт на оплату" });
  await assert.rejects(f.WWebJS.sendMessage(f.chat, "", options()), /Data passed to getter/);
  assert.equal(f.effects.dispatches, 0);
  vm.runInContext(`(${installBrowserDocumentMediaCompatibility.toString()})()`, f.context);
  const sent = await f.WWebJS.sendMessage(f.chat, "", options());
  assert.equal(sent.id.id, "FIXTURE");
  assert.equal(sent.type, "document");
  assert.equal(sent.filehash, "file-hash");
  assert.equal(sent.filename, "Счёт.pdf");
  assert.equal(sent.caption, "Счёт на оплату");
  assert.equal(sent.body, "preview");
  assert.equal(f.effects.dispatches, 1);
  assert.equal(f.effects.calls, 2);
  assert.equal(f.effects.receiver, f.WWebJS);
  assert.equal(Object.hasOwn(f.prepared, "__x_id"), true);
  assert.equal(f.prepared.__x_id, undefined);
});

test("adaptation is idempotent and leaves other media and failed provider calls untouched", async () => {
  const f = fixture();
  vm.runInContext(`(${installBrowserDocumentMediaCompatibility.toString()})()`, f.context);
  const once = f.WWebJS.processMediaData;
  vm.runInContext(`(${installBrowserDocumentMediaCompatibility.toString()})()`, f.context);
  assert.equal(f.WWebJS.processMediaData, once);
  assert.equal(await once.call(f.WWebJS, {}, { forceDocument: false }), f.prepared);
  assert.equal(f.effects.calls, 1);
  f.WWebJS.processMediaData = async () => { throw new Error("upload failed"); };
  vm.runInContext(`(${installBrowserDocumentMediaCompatibility.toString()})()`, f.context);
  await assert.rejects(f.WWebJS.processMediaData({}, { forceDocument: true }), /upload failed/);
  assert.equal(f.effects.dispatches, 0);
});

test("document compatibility is installed before listeners on each injection", async () => {
  const events = [];
  const client = { pupPage: { evaluate: async fn => { assert.equal(fn, installBrowserDocumentMediaCompatibility); events.push("compat"); } },
    async attachEventListeners(value) { assert.equal(this, client); events.push(value); } };
  installDocumentMediaCompatibility(client);
  await client.attachEventListeners("listeners");
  await client.attachEventListeners("reloaded");
  assert.deepEqual(events, ["compat", "listeners", "compat", "reloaded"]);
  client.pupPage.evaluate = async () => { throw new Error("unsupported"); };
  await assert.rejects(client.attachEventListeners("must-not-run"), /unsupported/);
  assert.equal(events.length, 4);
  assert.throws(() => installDocumentMediaCompatibility({}), /lifecycle_unavailable/);
});
