"use strict";

const { serializedId } = require("./message-identity");

// Marking a chat read is a separate operation. whatsapp-web.js otherwise awaits
// sendSeen before even starting the message, which can exhaust the CDP timeout.
// Keep one provider call: a timeout is ambiguous and must never trigger a resend.
//
// sendMessage resolves when WhatsApp Web has created the message (ACK 0), not
// when its server accepted it. whatsapp-web.js 1.34.7 also has
// `waitUntilMsgSent`, but it awaits the send job inside one page evaluation
// without its own deadline and discards that job's result: a stalled job would
// hold the call until the CDP protocol timeout and lose the created message ID.
// Waiting for the ACK afterwards keeps the ID and bounds the wait. A timeout
// returns the message with its last ACK; the caller records it as uncertain.
async function sendProviderMessage(client, destination, content, options = {},
  { ackTimeoutMs = 0, pollMs = 1000, acks } = {}) {
  const sent = await client.sendMessage(destination, content, { ...options, sendSeen: false });
  if (!(ackTimeoutMs > 0) || !sent) return sent;
  return awaitServerAck(client, sent, { timeoutMs: ackTimeoutMs, pollMs, acks });
}

function awaitServerAck(client, sent, { timeoutMs, pollMs = 1000, acks }) {
  const id = serializedId(sent.id);
  let ack = Number.isSafeInteger(sent.ack) ? sent.ack : undefined;
  if (!id || ack >= 1) return Promise.resolve(sent);
  return new Promise(resolve => {
    let settled = false, poller = null;
    const finish = () => {
      if (settled) return;
      settled = true; clearTimeout(timer); clearTimeout(poller);
      if (acks) acks.removeListener("ack", onAck);
      sent.ack = ack;
      resolve(sent);
    };
    const observe = value => {
      if (!Number.isSafeInteger(value)) return;
      ack = value;
      if (ack >= 1) finish();
    };
    const onAck = (messageId, value) => { if (messageId === id) observe(value); };
    const poll = async () => {
      if (settled || typeof client.getMessageById !== "function") return;
      try { observe((await client.getMessageById(id))?.ack); } catch { /* The deadline still applies. */ }
      if (!settled) poller = setTimeout(poll, pollMs);
    };
    const timer = setTimeout(finish, timeoutMs);
    if (acks) acks.on("ack", onAck);
    poller = setTimeout(poll, Math.min(pollMs, timeoutMs));
  });
}

// A browser timeout quarantines only this client. A single read-only probe runs
// in the background; a later timeout invalidates an older probe's result.
class OutboundBrowserHealth {
  constructor(getState, now = Date.now) {
    this.getState = getState;
    this.now = now;
    this.generation = 0;
    this.held = false;
    this.probing = false;
    this.retryAt = 0;
  }
  timeout() {
    this.generation++;
    this.held = true;
    this.retryAt = 0;
  }
  available() {
    if (!this.held) return true;
    if (!this.probing && this.now() >= this.retryAt) {
      const generation = this.generation;
      this.probing = true;
      Promise.resolve().then(() => this.getState()).then(state => {
        if (generation === this.generation && state === "CONNECTED") this.held = false;
      }).catch(() => {}).finally(() => {
        this.probing = false;
        this.retryAt = this.now() + 1000;
      });
    }
    return false;
  }
}
function providerFailureCode(error) {
  if (/timeout|timed out/iu.test(String(error?.name || "") + " " + String(error?.message || ""))) return "provider_timeout";
  return "provider_error";
}
module.exports = { sendProviderMessage, awaitServerAck, OutboundBrowserHealth, providerFailureCode };
