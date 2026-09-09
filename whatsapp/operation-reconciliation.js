"use strict";
const { OperationLedgerError } = require("./operation-ledger");
const { serializedId } = require("./message-identity");

// This route accepts only a reference to evidence. Content, recipient and ACK
// are read independently from the authenticated provider session, never trusted
// from the request. There is deliberately no retry/mark-unsent operation.
function createOperationReconciliationHandler({ ledger, clientId, getMessage, canRead, onReconciled = async () => {} }) {
  return async (req, res) => {
    const id = req.params.operationId;
    const messageId = req.body?.messageId;
    if (typeof messageId !== "string" || !messageId || messageId.length > 512) {
      throw new OperationLedgerError("invalid_message_id", 400);
    }
    const existing = ledger.lookup(id);
    if (!canRead()) throw new OperationLedgerError("gateway_not_ready");
    const record = ledger.read(ledger.key(id));
    const message = await getMessage(messageId);
    const destination = serializedId(message?.id?.remote) || serializedId(message?.to);
    const sentAt = Number(message?.timestamp) * 1000;
    if (!message?.fromMe || serializedId(message.id) !== messageId || Number(message.ack) < 1
        || !Number.isFinite(Number(message.ack)) || !Number.isFinite(sentAt)
        || sentAt < record.startedAt - 2000 || sentAt > (record.completedAt || Date.now()) + 2000
        || !/^.+@(?:g\.us|c\.us|lid)$/.test(destination)) {
      throw new OperationLedgerError("operation_evidence_mismatch", 409);
    }
    const envelope = { clientId, kind: destination.endsWith("@g.us") ? "send-group" : "send",
      destination, message: typeof message.body === "string" ? message.body.trim() : "" };
    if (existing.state === "SUCCEEDED" && existing.messageId !== messageId) {
      throw new OperationLedgerError("operation_evidence_mismatch", 409);
    }
    const result = await ledger.reconcile(id, envelope, messageId, async () => true);
    await onReconciled(messageId);
    res.set("Cache-Control", "no-store");
    res.json({ operationId: id, ...result });
  };
}
module.exports = { createOperationReconciliationHandler };
