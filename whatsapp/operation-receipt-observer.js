"use strict";
const { envelopeHash } = require("./operation-ledger");
const { serializedId } = require("./message-identity");

// An observation is a durable lookup hint, never delivery proof. Recovery must
// still read this exact ID from the provider and require its ACK and envelope.
function observeOperationReceipt({ ledger, clientId, message }) {
  if (message?.fromMe !== true || message.isNotification === true || (message.type && message.type !== "chat")
      || typeof message.body !== "string" || !message.body.trim()) return false;
  const destination = serializedId(message.id?.remote) || serializedId(message.to);
  const kind = destination.endsWith("@g.us") ? "send-group" : destination.endsWith("@c.us") ? "send" : null;
  const messageId = serializedId(message.id), sentAt = Number(message.timestamp) * 1000;
  if (!kind || !messageId || messageId.length > 512 || !Number.isSafeInteger(sentAt) || sentAt < 0) return false;
  const envelope = { clientId, kind, destination, message: message.body.trim() };
  const records = [...(ledger.envelopeRecords.get(envelopeHash(envelope))?.values() || [])]
    .filter(record => record.state !== "SUCCEEDED" && sentAt >= record.startedAt - 2000
      && sentAt <= Math.min(record.completedAt ?? record.startedAt + 300000, record.startedAt + 300000) + 2000);
  if (records.length !== 1) return false;
  return ledger.rememberReceiptCandidate(records[0], envelope, messageId, sentAt);
}

module.exports = { observeOperationReceipt };
