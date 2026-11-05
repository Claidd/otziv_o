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
  let ack = Number(sent.ack);
  // Without an ID or a numeric ACK there is nothing to wait for (legacy shape).
  if (!id || !Number.isFinite(ack) || ack >= 1) return Promise.resolve(sent);
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
      const next = Number(value);
      if (!Number.isFinite(next)) return;
      ack = next;
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

module.exports = { sendProviderMessage, awaitServerAck };
