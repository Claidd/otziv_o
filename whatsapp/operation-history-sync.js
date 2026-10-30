"use strict";

// syncHistory requests missing history from the primary device. It never sends
// a chat message and neither its success nor an empty cache proves delivery.
async function requestHistorySynchronization({ ledger, record, envelope, chat, requireLive }) {
  if (typeof chat?.syncHistory !== "function") return null;
  requireLive();
  const reservation = ledger.reserveHistorySynchronization(record, envelope);
  if (reservation.finished) return null;
  if (!reservation.reserved) return {
    code: reservation.outcome === "NOT_AVAILABLE" ? "history_no_receipt"
      : reservation.outcome === "UNAVAILABLE" ? "history_sync_unavailable" : "history_sync_pending",
    nextAt: reservation.nextAt,
  };
  try {
    const requested = await chat.syncHistory();
    requireLive();
    if (typeof requested !== "boolean") throw new Error("invalid_history_sync_response");
    ledger.finishHistorySynchronization(record, reservation, requested ? "REQUESTED" : "NOT_AVAILABLE");
    return { code: requested ? "history_sync_requested" : "history_no_receipt", nextAt: reservation.nextAt };
  } catch {
    requireLive();
    ledger.finishHistorySynchronization(record, reservation, "UNAVAILABLE");
    return { code: "history_sync_unavailable", nextAt: reservation.nextAt };
  }
}

module.exports = { requestHistorySynchronization };
