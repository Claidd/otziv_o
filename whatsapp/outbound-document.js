"use strict";

const crypto = require("node:crypto");
const { operationIdFromRequest, OperationLedgerError } = require("./operation-ledger");

function createDocumentOutboundHandler({ ledger, clientId, normalizeDestination, canStart, send }) {
  return async (req, res) => {
    const operationId = operationIdFromRequest(req);
    const { groupId, caption, filename, contentType, data } = req.body || {};
    const destination = normalizeDestination(groupId);
    if (!operationId || !destination || typeof caption !== "string" || !caption.trim() || caption.length > 1000
        || typeof filename !== "string" || !filename || filename.length > 200 || /[\x00-\x1f\\/]/u.test(filename)
        || typeof contentType !== "string" || !/^[a-z]+\/[a-zA-Z0-9.+-]+$/u.test(contentType)
        || typeof data !== "string" || data.length > 6990508 || data.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/u.test(data)) {
      throw new OperationLedgerError("invalid_request", 400);
    }
    const bytes = Buffer.from(data, "base64");
    if (!bytes.length || bytes.length > 5 * 1024 * 1024) throw new OperationLedgerError("payload_too_large", 413);
    if (bytes.toString("base64") !== data) throw new OperationLedgerError("invalid_request", 400);
    const message = JSON.stringify([caption, filename, contentType, crypto.createHash("sha256").update(bytes).digest("hex")]);
    const result = await ledger.execute(operationId, { clientId, kind: "send-group-file", destination, message },
      () => send(destination, { caption, filename, contentType, data, size: bytes.length }), { canStart });
    res.set("Cache-Control", "no-store");
    res.status(result.state === "SUCCEEDED" ? 200 : 409).json({
      status: result.state === "SUCCEEDED" ? "ok" : "unknown", operationId, ...result,
    });
  };
}

module.exports = { createDocumentOutboundHandler };
