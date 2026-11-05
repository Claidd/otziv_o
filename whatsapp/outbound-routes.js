"use strict";

const { operationIdFromRequest, OperationLedgerError, providerOutcome } = require("./operation-ledger");

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
      let outcome = {};
      try { outcome = providerOutcome(await send(destination, message)); } catch { /* UNKNOWN */ }
      const confirmed = Boolean(outcome.messageId) && (outcome.ack === undefined || outcome.ack >= 1);
      result = { state: confirmed ? "SUCCEEDED" : "UNKNOWN", messageId: confirmed ? outcome.messageId : null, replayed: false };
      if (outcome.ack !== undefined) result.providerAck = outcome.ack;
    }
    res.set("Cache-Control", "no-store");
    // Optional evidence: the WhatsApp ACK observed for the provider message.
    const ack = result.providerAck !== undefined ? { providerAck: result.providerAck } : {};
    if (result.state !== "SUCCEEDED") {
      res.status(409).json({
        status: result.state === "RUNNING" ? "pending" : "unknown",
        code: result.state === "RUNNING" ? "operation_running" : "operation_unknown",
        operationId, state: result.state, envelopeHash: result.envelopeHash ?? null, ...ack,
      });
      return;
    }
    res.json({
      status: "ok", clientId, operationId, state: result.state, replayed: result.replayed,
      [kind === "send" ? "to" : "groupId"]: destination,
      messageId: result.messageId,
      envelopeHash: result.envelopeHash ?? null, ...ack,
    });
  };
}

module.exports = { createOutboundHandler };
