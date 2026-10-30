"use strict";
const { OperationLedgerError, envelopeHash } = require("./operation-ledger");
const { serializedId } = require("./message-identity");
const { fetchAvailableWindow } = require("./inbound-history");
const { createOperationReconciliationHandler } = require("./operation-reconciliation");
const { requestHistorySynchronization } = require("./operation-history-sync");

// Read the saved operation's chat, never the current company route. Neither an
// empty nor a partial history authorizes sending. Ledger binding is the only write.
function createOperationHistoryReconciliationHandler({ ledger, clientId, getChat, getMessage, canRead,
  normalizeGroup, normalizePhone, maximumChars = 65536, timeoutMs = 25000, maxMessages = 10000,
  getDirectMappings, onReconciled = async () => {} }) {
  const inFlight = new Map();
  return async (req, res) => {
    const operationId = req.params.operationId;
    const kind = req.body?.kind;
    const destination = kind === "send-group" ? normalizeGroup(req.body?.destination)
      : kind === "send" ? normalizePhone(req.body?.destination) : "";
    const message = typeof req.body?.message === "string" ? req.body.message.trim() : "";
    if (!destination || !message || message.length > maximumChars)
      throw new OperationLedgerError("invalid_operation_envelope", 400);
    const envelope = { clientId, kind, destination, message };
    const record = ledger.read(ledger.key(operationId));
    if (!record) ledger.missing();
    ledger.assertEnvelope(record, envelopeHash(envelope));
    const existing = ledger.lookup(operationId);
    const respond = value => { res.set("Cache-Control", "no-store"); res.json({ operationId, ...value }); };
    if (existing.state === "SUCCEEDED") return respond(existing);
    if (existing.state === "RUNNING") throw new OperationLedgerError("operation_running", 409);
    if (!canRead()) throw new OperationLedgerError("gateway_not_ready");
    const window = ledger.receiptWindow(record);
    if (inFlight.has(operationId)) return respond(await inFlight.get(operationId).response);
    if (inFlight.size >= 2) throw new OperationLedgerError("operation_history_busy");
    let expired = false, timer;
    const requireLive = () => {
      if (expired || !canRead()) throw new OperationLedgerError("operation_history_unavailable");
    };
    const work = Promise.resolve().then(async () => {
      requireLive();
      if (record.receiptCandidateConflict || (record.receiptCandidates?.length || 0) > 1)
        return { ...ledger.lookup(operationId), recoveryCode: "history_ambiguous" };
      if (record.receiptCandidates?.length === 1) {
        let captured;
        const binding = createOperationReconciliationHandler({ ledger, clientId,
          canRead: () => !expired && canRead(), getMessage: async id => {
            requireLive(); const found = await getMessage(id); requireLive(); return found;
          }, onReconciled });
        try {
          await binding({ params: { operationId }, body: { messageId: record.receiptCandidates[0].messageId } },
            { set() {}, json(value) { captured = value; } });
          return captured;
        } catch (error) {
          requireLive();
          if (error.code !== "operation_evidence_mismatch") throw error;
          // A queued message with ACK=0 is not a confirmed send. History may
          // become readable later; never submit that message a second time.
        }
      }
      const directIdentity = async id => {
        if (!getDirectMappings) return { chat: destination, aliases: new Set([destination]) };
        const mappings = await getDirectMappings(id);
        requireLive();
        if (!Array.isArray(mappings) || mappings.length !== 1)
          throw new OperationLedgerError("operation_recipient_identity_unavailable");
        const pn = serializedId(mappings[0]?.pn), lid = serializedId(mappings[0]?.lid);
        if (pn !== destination || lid && !/^[0-9]+@lid$/.test(lid)
            || id !== destination && id !== lid)
          throw new OperationLedgerError("operation_recipient_identity_mismatch", 409);
        return { chat: lid || pn, aliases: new Set(lid ? [pn, lid] : [pn]) };
      };
      // The provider's PN/LID relation is read independently; no alias from the
      // request or message text may redirect the frozen recipient.
      const identity = kind === "send" ? await directIdentity(destination)
        : { chat: destination, aliases: new Set([destination]) };
      const chat = await getChat(identity.chat);
      requireLive();
      if (!chat || typeof chat.fetchMessages !== "function")
        throw new OperationLedgerError("operation_history_unavailable");
      const history = await fetchAvailableWindow({ fetchMessages: async options => {
        requireLive();
        const page = await chat.fetchMessages(options);
        requireLive();
        return page;
      } }, window.from / 1000, { maxMessages });
      requireLive();
      const unresolved = async fallback => {
        const synchronization = await requestHistorySynchronization({ ledger, record, envelope, chat, requireLive });
        requireLive();
        return { ...ledger.lookup(operationId),
          recoveryCode: synchronization && synchronization.code !== "history_no_receipt" ? synchronization.code : fallback,
          ...(synchronization ? { historySyncNextAt: synchronization.nextAt } : {}) };
      };
      if (!history.covered) return unresolved("history_incomplete");
      const matches = new Map();
      for (const candidate of history.messages) {
        const time = Number(candidate?.timestamp) * 1000;
        const id = serializedId(candidate?.id);
        const target = serializedId(candidate?.id?.remote) || serializedId(candidate?.to);
        if (candidate?.fromMe === true && id && id.length <= 512 && identity.aliases.has(target)
            && Number.isFinite(time) && time >= window.from && time <= window.until
            && typeof candidate.body === "string" && candidate.body.trim() === message)
          matches.set(id, candidate);
      }
      if (matches.size > 1) return { ...ledger.lookup(operationId), recoveryCode: "history_ambiguous" };
      if (matches.size === 0) return unresolved("history_no_receipt");
      let result;
      const handler = createOperationReconciliationHandler({ ledger, clientId,
        canRead: () => !expired && canRead(),
        getMessage: async id => { requireLive(); const found = await getMessage(id); requireLive(); return found; },
        resolveDestination: async actual => {
          requireLive();
          if (actual === destination) return destination;
          if (kind !== "send" || !identity.aliases.has(actual))
            throw new OperationLedgerError("operation_evidence_mismatch", 409);
          // Recheck mapping after the final provider message read, so a changed
          // or mismatched contact cannot acquire the previous operation receipt.
          const fresh = await directIdentity(actual);
          if (!fresh.aliases.has(actual)) throw new OperationLedgerError("operation_evidence_mismatch", 409);
          return destination;
        },
        onReconciled });
      // Fresh provider read verifies ACK, exact body/recipient/time, exclusive
      // message ownership and competing operation windows again before binding.
      await handler({ params: { operationId }, body: { messageId: matches.keys().next().value } },
        { set() {}, json(value) { result = value; } });
      return result;
    });
    const response = Promise.race([work, new Promise((_, reject) => {
      timer = setTimeout(() => { expired = true; reject(new OperationLedgerError("operation_history_timeout")); }, timeoutMs);
    })]);
    inFlight.set(operationId, { response });
    // Keep an unresolved provider call counted after timeout: repeated clicks
    // cannot create unlimited browser work. A late result cannot bind evidence.
    work.finally(() => { clearTimeout(timer); inFlight.delete(operationId); }).catch(() => {});
    return respond(await response);
  };
}
module.exports = { createOperationHistoryReconciliationHandler };
