"use strict";

// MsgKey no longer exposes _serialized on current WhatsApp Web. Keep the wire
// identity stable across old instances, live objects and JSON transport.
function serializedId(value) {
  if (!value) return "";
  if (typeof value === "string") return value.trim();
  if (typeof value._serialized === "string") return value._serialized.trim();
  if (typeof value.fromMe === "boolean" && typeof value.id === "string" && value.id) {
    const remote = serializedId(value.remote);
    const participant = serializedId(value.participant);
    if (remote && (!value.participant || participant)) {
      return `${value.fromMe}_${remote}_${value.id}${participant ? `_${participant}` : ""}`;
    }
  }
  if (typeof value.toString === "function" && value.toString !== Object.prototype.toString) {
    const text = value.toString();
    if (typeof text === "string" && !text.startsWith("[object ")) return text.trim();
  }
  return "";
}

// Runs in the browser after WWebJS injection, before listeners and READY.
// The prototype alias fixes internal library lookups (including lastReceivedKey).
// The serializer wrapper adds an own property before Puppeteer removes prototypes.
function installBrowserMessageIdentity() {
  const MsgKey = window.require("WAWebMsgKey");
  if (typeof MsgKey !== "function" || typeof MsgKey.prototype.toString !== "function"
      || typeof window.WWebJS?.getMessageModel !== "function") {
    throw new Error("whatsapp_message_identity_unavailable");
  }
  if (!("_serialized" in MsgKey.prototype)) {
    const toString = MsgKey.prototype.toString;
    Object.defineProperty(MsgKey.prototype, "_serialized", {
      configurable: true,
      get() { return toString.call(this); },
      // Older shapes can still create an own field on an adapted prototype.
      set(value) { Object.defineProperty(this, "_serialized", { value, writable: true, configurable: true, enumerable: true }); },
    });
  }
  const original = window.WWebJS.getMessageModel;
  if (original.otzivMessageIdentity) return;
  const wrapped = function (message) {
    const model = original.apply(this, arguments);
    const id = message?.id?._serialized;
    if (model?.id && typeof id === "string" && id) {
      model.id = { ...model.id, _serialized: id };
    }
    return model;
  };
  wrapped.otzivMessageIdentity = true;
  window.WWebJS.getMessageModel = wrapped;
}

function installMessageIdentityCompatibility(client) {
  if (typeof client?.attachEventListeners !== "function") throw new Error("whatsapp_listener_lifecycle_unavailable");
  const attach = client.attachEventListeners;
  client.attachEventListeners = async function (...args) {
    await this.pupPage.evaluate(installBrowserMessageIdentity);
    return attach.apply(this, args);
  };
  return client;
}

module.exports = { serializedId, installBrowserMessageIdentity, installMessageIdentityCompatibility };
