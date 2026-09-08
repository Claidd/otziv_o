"use strict";

const { operationIdFromRequest, OperationLedgerError } = require("./operation-ledger");

function createOutboundHandler({ ledger, clientId, kind, normalizeDestination, maximumChars, canStart, send }) {
  return async (req, res) => {
    const operationId = operationIdFromRequest(req);
    const destination = normalizeDestination(kind === "send"
      ? req.body.phone || req.body.to || req.body.number
      : req.body.groupId || req.body.chatId || req.body.to);
    const message = typeof req.body.message === "string" ? req.body.message.trim() : "";
    if (!destination || !message || message.length > maximumChars) {
      throw new OperationLedgerError("invalid_request", 400);
    }
    const envelope = { clientId, kind, destination, message };
    let result;
    if (operationId !== null) {
      result = await ledger.execute(operationId, envelope, () => send(destination, message), { canStart });
    } else {
      if (!canStart()) throw new OperationLedgerError("gateway_not_ready");
      // Compatibility requests cannot be replayed safely. Still report a send
      // exception as ambiguous rather than claiming the message was not sent.
      let messageId;
      try { messageId = await send(destination, message); } catch { /* UNKNOWN */ }
      result = { state: messageId ? "SUCCEEDED" : "UNKNOWN", messageId: messageId || null, replayed: false };
    }
    res.set("Cache-Control", "no-store");
    if (result.state !== "SUCCEEDED") {
      res.status(409).json({
        status: result.state === "RUNNING" ? "pending" : "unknown",
        code: result.state === "RUNNING" ? "operation_running" : "operation_unknown",
        operationId, state: result.state, envelopeHash: result.envelopeHash ?? null,
      });
      return;
    }
    res.json({
      status: "ok", clientId, operationId, state: result.state, replayed: result.replayed,
      [kind === "send" ? "to" : "groupId"]: destination,
      messageId: result.messageId,
      envelopeHash: result.envelopeHash ?? null,
    });
  };
}

module.exports = { createOutboundHandler };
