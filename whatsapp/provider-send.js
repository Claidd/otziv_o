"use strict";

// Marking a chat read is a separate operation. whatsapp-web.js otherwise awaits
// sendSeen before even starting the message, which can exhaust the CDP timeout.
// Keep one provider call: a timeout is ambiguous and must never trigger a resend.
function sendProviderMessage(client, destination, content, options = {}) {
  return client.sendMessage(destination, content, { ...options, sendSeen: false });
}

module.exports = { sendProviderMessage };
