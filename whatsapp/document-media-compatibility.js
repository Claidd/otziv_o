"use strict";

// wwebjs 1.34.7 spreads MediaData into the outgoing Msg. Current WhatsApp Web
// exposes MediaData.__x_id, which overwrites the Msg's private identity during
// model initialization (upstream issue wwebjs/whatsapp-web.js#201922).
// Materialize the document metadata without mutating the shared MediaData model.
function installBrowserDocumentMediaCompatibility() {
  const api = window.WWebJS;
  if (typeof api?.processMediaData !== "function") throw new Error("whatsapp_document_media_unavailable");
  const original = api.processMediaData;
  if (original.otzivDocumentMediaCompatibility) return;
  const wrapped = async function (...args) {
    const prepared = await original.apply(this, args);
    if (!args[1]?.forceDocument || !prepared || typeof prepared !== "object") return prepared;
    const json = typeof prepared.toJSON === "function" ? prepared.toJSON() : {};
    const metadata = { ...prepared, ...json, preview: prepared.preview };
    delete metadata.__x_id;
    // The public snapshot is already merged. Calling a model method with the
    // detached object as its receiver would lose the original model's state.
    delete metadata.toJSON;
    return metadata;
  };
  Object.defineProperty(wrapped, "otzivDocumentMediaCompatibility", { value: true });
  api.processMediaData = wrapped;
}

function installDocumentMediaCompatibility(client) {
  if (typeof client?.attachEventListeners !== "function") throw new Error("whatsapp_listener_lifecycle_unavailable");
  const attach = client.attachEventListeners;
  client.attachEventListeners = async function (...args) {
    await this.pupPage.evaluate(installBrowserDocumentMediaCompatibility);
    return attach.apply(this, args);
  };
  return client;
}

module.exports = { installBrowserDocumentMediaCompatibility, installDocumentMediaCompatibility };
