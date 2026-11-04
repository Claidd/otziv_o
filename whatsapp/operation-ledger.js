"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { OperationLedgerIndex } = require("./operation-ledger-index");
const { OperationLedgerOwner, syncDirectory, exclusive } = require("./operation-ledger-owner");
const { OperationLedgerRecovery } = require("./operation-ledger-recovery");
const { OperationLedgerTombstones } = require("./operation-ledger-tombstones");

const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/u;
const HASH = /^[a-f0-9]{64}$/u;
const RECORD_FILE = /^[a-f0-9]{64}\.json$/u;
const MAX_RECORD_BYTES = 4096;
const MAX_RECEIPT_WINDOW_MS = 5 * 60 * 1000;
const HISTORY_SYNC_INTERVAL_MS = 30 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;
const DEFAULT_RETENTION_DAYS = 30;
const MIN_RETENTION_DAYS = 7;
const MAX_RETENTION_DAYS = 3650;
const DEFAULT_READY_MAX_UTILIZATION = 0.95;
// A receipt bound to a pruned operation can never fall into the receipt window
// of an unresolved operation of the same envelope started this far apart.
const PRUNE_SIBLING_GUARD_MS = MAX_RECEIPT_WINDOW_MS + 60 * 1000;
const PRUNE_IDLE_INTERVAL_MS = 10 * 60 * 1000;
const RECOVERY_RETRY_MS = 60 * 1000;
const TOMBSTONE_FIELDS = ["completedAt", "reconciledAt", "receiptCandidates", "receiptCandidateConflict",
  "providerAck", "providerMessageId", "ackConfirmedAt"];

class OperationLedgerError extends Error {
  constructor(code, statusCode = 503) { super(code); this.code = code; this.statusCode = statusCode; }
}

function operationIdFromRequest(req) {
  const header = req.get("Idempotency-Key");
  const body = req.body?.operationId;
  if (header !== undefined && body !== undefined && header !== body) throw new OperationLedgerError("operation_id_conflict", 400);
  const id = header ?? body;
  if (id === undefined) return null; // Explicit compatibility for the legacy producer.
  if (typeof id !== "string" || !ID.test(id)) throw new OperationLedgerError("invalid_operation_id", 400);
  return id;
}
function envelopeHash({ clientId, kind, destination, message }) {
  if (![clientId, destination, message].every(value => typeof value === "string" && value.length > 0)
      || !["send", "send-group", "send-group-file", "send-file"].includes(kind)) throw new OperationLedgerError("invalid_operation_envelope", 400);
  return digest("otziv.whatsapp.envelope.v1", JSON.stringify([clientId, kind, destination, message]));
}
function digest(domain, value) { return crypto.createHash("sha256").update(domain).update("\0").update(value).digest("hex"); }
// `dispatched` describes this operation ID at this gateway: false means no claim
// exists and the provider was not called; true means a provider call started.
function markDispatch(error, dispatched) {
  if (error && typeof error === "object" && error.dispatched === undefined) error.dispatched = dispatched;
  return error;
}
// HTTP body of a ledger error. `dispatched` is additive and appears only when
// the gateway can prove it for this operation ID.
function ledgerErrorBody(error) {
  return { status: "error", code: error.code,
    ...(typeof error.dispatched === "boolean" ? { dispatched: error.dispatched } : {}) };
}
// A provider result is either a legacy message ID or { messageId, ack, sentAt }.
// A numeric ACK below 1 means WhatsApp created the message but its server did
// not confirm it; the result is then UNKNOWN, never "not sent".
function providerOutcome(value) {
  if (typeof value === "string") return validMessageId(value) ? { messageId: value } : {};
  if (!value || typeof value !== "object" || !validMessageId(value.messageId)) return {};
  return { messageId: value.messageId,
    ack: Number.isSafeInteger(value.ack) && value.ack >= -1 && value.ack <= 10 ? value.ack : undefined,
    sentAt: Number.isSafeInteger(value.sentAt) ? value.sentAt : undefined };
}
function retentionMs(value) {
  if (value === undefined || value === null || String(value).trim() === "") return DEFAULT_RETENTION_DAYS * DAY_MS;
  const days = Number(value);
  if (!Number.isFinite(days) || days < 0) return DEFAULT_RETENTION_DAYS * DAY_MS;
  if (days === 0) return 0; // Explicitly disabled: full records are kept forever.
  return Math.min(Math.max(Math.floor(days), MIN_RETENTION_DAYS), MAX_RETENTION_DAYS) * DAY_MS;
}
function readinessRatio(value) {
  const ratio = Number(value);
  return value !== undefined && value !== null && String(value).trim() !== "" && Number.isFinite(ratio) && ratio > 0 && ratio <= 1
    ? ratio : DEFAULT_READY_MAX_UTILIZATION;
}

/**
 * JSON claims/results are authoritative. Metadata is rebuilt once at startup;
 * admission and metrics do not scan the directory. One OS-held writer lock
 * protects the count and atomic mutations. A durable claim precedes every
 * provider call. Use local persistent storage with atomic rename/fsync.
 * SUCCEEDED records older than the retention window are compacted into durable
 * identity tombstones; RUNNING and UNKNOWN records are never pruned.
 */
class OperationLedger {
  constructor(directory, { maxRecords = 100_000, now = () => Date.now(), io = fs, restoreId, retentionDays,
    readyMaxUtilization, pruneBatchRecords = 5000, pruneBatchShards = 64 } = {}) {
    this.directory = path.resolve(directory); this.now = now; this.io = io;
    this.maxRecords = Math.max(1, Math.min(Math.floor(Number(maxRecords)) || 100_000, 1_000_000));
    this.retentionMs = retentionMs(retentionDays); this.readyMaxUtilization = readinessRatio(readyMaxUtilization);
    this.pruneBatchRecords = Math.max(1, Math.floor(Number(pruneBatchRecords)) || 5000);
    this.pruneBatchShards = Math.max(1, Math.floor(Number(pruneBatchShards)) || 64);
    this.index = new OperationLedgerIndex(); this.inFlight = new Set(); this.maintenance = 0;
    // Rebuilt from the same durable records. A receipt can identify only one operation.
    this.messageOwners = new Map(); this.envelopeRecords = new Map(); this.historySyncByChat = new Map();
    this.ackPending = new Map();
    this.healthy = true; this.closing = false; this.persistenceFailures = 0; this.readFailures = 0;
    this.admissionRejected = 0; this.closeWaiters = [];
    // Health recovery state: claims that failed before any provider call, and
    // results whose durable write failed after one. Unreadable evidence is never
    // recovered automatically.
    this.failedClaims = new Map(); this.pendingResults = new Map(); this.unsyncedKeys = new Set();
    this.manualRecoveryRequired = false; this.nextRecoveryAt = 0; this.recoveredTotal = 0; this.recoveryFailures = 0;
    this.lastRecoveredAt = null; this.repairedClaims = 0;
    this.prunedTotal = 0; this.pruneEligible = 0; this.lastPrunedAt = null; this.nextPruneAt = 0;
    io.mkdirSync(this.directory, { recursive: true, mode: 0o700 });
    const directoryInfo = io.lstatSync(this.directory);
    if (!directoryInfo.isDirectory() || directoryInfo.isSymbolicLink()) throw new OperationLedgerError("invalid_ledger_directory");
    io.accessSync(this.directory, io.constants.R_OK | io.constants.W_OK);
    this.tombstones = new OperationLedgerTombstones(this.directory, { io });
    this.owner = new OperationLedgerOwner(this.directory, { io });
    try {
      // Arm restore mode before scanning or opening admission. A copied old
      // completed marker does not override a new restoreId supplied by deployment.
      this.recovery = new OperationLedgerRecovery(this, restoreId);
      for (const name of io.readdirSync(this.directory)) {
        if (!RECORD_FILE.test(name)) continue;
        const key = name.slice(0, -5);
        try { this.observe(this.readRecordFile(key)); }
        catch {
          // The writer turns a claim torn before its fsync into durable UNKNOWN:
          // its identity stays reserved and is never dispatched again.
          const repaired = this.owner.writer ? this.repairInterruptedClaim(key) : null;
          if (repaired) { this.observe(repaired); continue; }
          this.index.set(key, "UNKNOWN", 0); this.markReadFailure();
        }
      }
      if (this.owner.writer) this.probe();
    } catch (error) { this.owner.close(); throw error; }
  }
  key(id) {
    if (typeof id !== "string" || !ID.test(id)) throw new OperationLedgerError("invalid_operation_id", 400);
    return digest("otziv.whatsapp.operation.v1", id);
  }
  filename(key) { return path.join(this.directory, `${key}.json`); }
  observe(record) {
    if (record) {
      this.index.set(record.key, record.state === "RUNNING" && !this.inFlight.has(record.key) ? "UNKNOWN" : record.state, record.startedAt);
      if (record.envelopeHash !== null) {
        let records = this.envelopeRecords.get(record.envelopeHash);
        if (!records) this.envelopeRecords.set(record.envelopeHash, records = new Map());
        records.set(record.key, record);
      }
      if (record.historySync) {
        const previous = this.historySyncByChat.get(record.historySync.chatHash);
        if (previous === undefined || record.historySync.requestedAt >= previous.requestedAt)
          this.historySyncByChat.set(record.historySync.chatHash, record.historySync);
      }
      if (record.state === "SUCCEEDED") {
        let owners = this.messageOwners.get(record.messageId);
        if (!owners) this.messageOwners.set(record.messageId, owners = new Set());
        owners.add(record.key);
      }
      if (record.providerMessageId !== undefined) {
        if (record.state === "UNKNOWN") this.ackPending.set(record.providerMessageId, record.key);
        else if (this.ackPending.get(record.providerMessageId) === record.key) this.ackPending.delete(record.providerMessageId);
      }
    }
    return record;
  }
  // Removes in-memory metadata of a record that now exists only as a tombstone.
  forget(record) {
    this.index.delete(record.key);
    const siblings = this.envelopeRecords.get(record.envelopeHash);
    if (siblings) { siblings.delete(record.key); if (!siblings.size) this.envelopeRecords.delete(record.envelopeHash); }
    const owners = this.messageOwners.get(record.messageId);
    if (owners) { owners.delete(record.key); if (!owners.size) this.messageOwners.delete(record.messageId); }
    if (record.providerMessageId !== undefined && this.ackPending.get(record.providerMessageId) === record.key)
      this.ackPending.delete(record.providerMessageId);
    const latest = record.historySync && this.historySyncByChat.get(record.historySync.chatHash);
    if (latest && latest.requestedAt === record.historySync.requestedAt) this.historySyncByChat.delete(record.historySync.chatHash);
  }
  readRecordFile(key) {
    const file = this.filename(key), info = this.io.lstatSync(file);
    if (!info.isFile() || info.isSymbolicLink() || info.size > MAX_RECORD_BYTES) throw new Error("invalid record");
    const record = JSON.parse(this.io.readFileSync(file, "utf8"));
    if (!validRecord(record, key)) throw new Error("invalid record");
    return record;
  }
  read(key) {
    // The provider was never called for a claim that failed in this process.
    if (this.failedClaims.has(key)) throw markDispatch(new OperationLedgerError("operation_ledger_unavailable"), false);
    let record;
    try { record = this.readRecordFile(key); }
    catch (error) {
      if (error.code === "ENOENT") {
        const tombstone = this.readTombstone(key);
        if (tombstone) { if (this.index.records.has(key)) this.forget(tombstone); return tombstone; }
        if (!this.index.records.has(key)) return null;
      }
      this.markReadFailure();
      throw new OperationLedgerError(error.code === "ENOENT" ? "operation_record_missing" : "operation_record_unreadable");
    }
    return this.observe(record);
  }
  // Tombstones are read on demand and are not loaded into memory.
  readTombstone(key) {
    try {
      const tombstone = this.tombstones.find(key);
      if (tombstone === null) return null;
      if (!validRecord(tombstone, key) || tombstone.state !== "SUCCEEDED") throw new Error("invalid tombstone");
      return tombstone;
    } catch {
      this.markReadFailure();
      throw new OperationLedgerError("operation_record_unreadable");
    }
  }
  markReadFailure() { this.healthy = false; this.readFailures++; this.manualRecoveryRequired = true; }
  // Only a strict prefix of this writer's own claim serialization qualifies;
  // any other unreadable content is preserved as evidence and fails closed.
  repairInterruptedClaim(key) {
    let info, content;
    try {
      info = this.io.lstatSync(this.filename(key));
      if (!info.isFile() || info.isSymbolicLink() || info.size > MAX_RECORD_BYTES) return null;
      content = this.io.readFileSync(this.filename(key), "utf8");
    } catch { return null; }
    const torn = interruptedClaim(content, key);
    if (!torn) return null;
    const record = { version: 1, key, envelopeHash: torn.envelopeHash, state: "UNKNOWN",
      startedAt: timestamp(info.mtimeMs),
      interruptedClaim: { size: info.size, repairedAt: timestamp(this.now()) } };
    try { this.persist(record); } catch { return null; }
    this.repairedClaims++;
    return record;
  }
  snapshot(record) {
    if (record.state === "SUCCEEDED" && [...(this.messageOwners.get(record.messageId) || [])].some(key => key !== record.key))
      throw new OperationLedgerError("operation_evidence_already_bound", 409);
    const value = { receiptBindingVersion: 1, historyReconciliationVersion: 1, state: record.state === "RUNNING" && !this.inFlight.has(record.key) ? "UNKNOWN" : record.state,
      messageId: record.state === "SUCCEEDED" ? record.messageId : null, envelopeHash: record.envelopeHash };
    if (record.providerAck !== undefined) value.providerAck = record.providerAck;
    return value;
  }
  missing() {
    if (this.recovery.required) throw new OperationLedgerError("operation_recovery_required");
    throw new OperationLedgerError("operation_not_found", 404);
  }
  lookup(id) { const record = this.read(this.key(id)); if (!record) this.missing(); return this.snapshot(record); }
  assertEnvelope(record, hash) {
    // A repaired interrupted claim may not contain its envelope hash.
    if (record.envelopeHash === null) throw new OperationLedgerError("operation_envelope_unavailable", 409);
    if (record.envelopeHash !== hash) throw new OperationLedgerError("operation_payload_conflict", 409);
  }
  replay(record, hash) {
    if (record.envelopeHash !== null) this.assertEnvelope(record, hash);
    return { ...this.snapshot(record), replayed: true };
  }
  reserveHistorySynchronization(record, envelope) {
    this.assertWritable();
    const current = this.read(record.key);
    if (!current) this.missing();
    this.assertEnvelope(current, envelopeHash(envelope));
    if (current.state === "SUCCEEDED") return { finished: true };
    if (this.inFlight.has(current.key)) throw new OperationLedgerError("operation_running", 409);
    const chatHash = digest("otziv.whatsapp.history-chat.v1", JSON.stringify([envelope.clientId, envelope.kind, envelope.destination]));
    const previous = this.historySyncByChat.get(chatHash), now = this.now();
    if (previous !== undefined && now < previous.requestedAt + HISTORY_SYNC_INTERVAL_MS)
      return { reserved: false, outcome: previous.outcome || "RESERVED", nextAt: previous.requestedAt + HISTORY_SYNC_INTERVAL_MS };
    // Persist before contacting the primary device. A restart or a second
    // operation in this chat must not flood it with history requests.
    this.replace({ ...current, historySync: { chatHash, requestedAt: now, outcome: "RESERVED" } });
    return { reserved: true, chatHash, requestedAt: now, nextAt: now + HISTORY_SYNC_INTERVAL_MS };
  }
  finishHistorySynchronization(record, reservation, outcome) {
    this.assertWritable();
    if (!["REQUESTED", "NOT_AVAILABLE", "UNAVAILABLE"].includes(outcome))
      throw new OperationLedgerError("invalid_history_sync_outcome");
    const current = this.read(record.key);
    if (!current || current.state === "SUCCEEDED" || current.historySync?.chatHash !== reservation.chatHash
        || current.historySync.requestedAt !== reservation.requestedAt) return;
    this.replace({ ...current, historySync: { ...current.historySync, outcome } });
  }
  rememberReceiptCandidate(record, envelope, messageId, sentAt) {
    this.assertWritable();
    const current = this.read(record.key);
    if (!current || current.state === "SUCCEEDED") return false;
    this.assertEnvelope(current, envelopeHash(envelope));
    if (!boundedMessageId(messageId) || !Number.isSafeInteger(sentAt)
        || sentAt < current.startedAt - 2000
        || sentAt > Math.min(current.completedAt ?? current.startedAt + MAX_RECEIPT_WINDOW_MS,
          current.startedAt + MAX_RECEIPT_WINDOW_MS) + 2000) return false;
    const candidates = current.receiptCandidates || [];
    if (candidates.some(candidate => candidate.messageId === messageId)) return true;
    const observedAt = this.now();
    if (!Number.isSafeInteger(observedAt) || observedAt < current.startedAt) return false;
    this.replace({ ...current, receiptCandidateConflict: candidates.length > 0,
      receiptCandidates: candidates.length >= 4 ? candidates
        : [...candidates, { messageId, sentAt, observedAt }] });
    return true;
  }
  // The provider returned this ID from this operation's own send; a later
  // server ACK for it is positive evidence that resolves the ACK timeout.
  confirmProviderAck(messageId, ack) {
    const value = Number(ack);
    if (!validMessageId(messageId) || !Number.isFinite(value) || value < 1) return false;
    const key = this.ackPending.get(messageId);
    if (key === undefined || this.inFlight.has(key)) return false;
    this.assertWritable();
    const current = this.read(key);
    if (!current || current.state !== "UNKNOWN" || current.providerMessageId !== messageId) return false;
    const owners = this.messageOwners.get(messageId);
    if (owners && [...owners].some(other => other !== key)) return false;
    const resolved = { ...current, state: "SUCCEEDED", messageId, providerAck: Math.min(10, Math.trunc(value)),
      ackConfirmedAt: timestamp(this.now()) };
    if (!fits(resolved)) return false;
    this.replace(resolved);
    return true;
  }
  admission() {
    const code = !this.owner.writer ? "operation_writer_locked" : this.closing ? "operation_ledger_closing"
      : !this.healthy ? "operation_ledger_unavailable" : this.recovery.required ? "operation_recovery_required"
      : this.index.size >= this.maxRecords ? "operation_ledger_full" : null;
    return { acceptingNew: code === null, code };
  }
  // Readiness closes before admission so monitoring sees exhaustion early.
  readiness() {
    const admission = this.admission();
    const capacityReady = this.index.size < this.maxRecords * this.readyMaxUtilization;
    return { ready: admission.acceptingNew && capacityReady,
      code: admission.code || (capacityReady ? null : "operation_ledger_near_capacity"), capacityReady };
  }
  metrics() {
    const oldest = this.index.oldestUnknown(), readiness = this.readiness();
    return { capacity: this.maxRecords, records: this.index.size, remaining: Math.max(0, this.maxRecords - this.index.size),
      utilizationRatio: this.index.size / this.maxRecords, capacityWarning: this.index.size >= this.maxRecords * 0.8,
      unknown: this.index.unknown, running: this.inFlight.size,
      oldestUnknownAgeSeconds: oldest === null ? null : Math.max(0, (this.now() - oldest) / 1000),
      persistenceFailures: this.persistenceFailures, readFailures: this.readFailures, admissionRejected: this.admissionRejected,
      writer: this.owner.writer, recoveryRequired: this.recovery.required,
      readyUtilizationThreshold: this.readyMaxUtilization, capacityReady: readiness.capacityReady,
      readinessReady: readiness.ready, readinessCode: readiness.code,
      retentionDays: this.retentionMs / DAY_MS, pruneEligible: this.pruneEligible, prunedTotal: this.prunedTotal,
      lastPrunedAt: this.lastPrunedAt, ackPending: this.ackPending.size,
      healthRecoveredTotal: this.recoveredTotal, healthRecoveryFailures: this.recoveryFailures,
      autoRecoveryBlocked: this.manualRecoveryRequired, repairedInterruptedClaims: this.repairedClaims,
      ...this.admission() };
  }
  assertWritable() {
    this.owner.assertOwner();
    if (!this.healthy) throw new OperationLedgerError("operation_ledger_unavailable");
  }
  failPersistence(code) { this.healthy = false; this.persistenceFailures++; return new OperationLedgerError(code); }
  claim(key, hash, { state = "RUNNING", startedAt = this.now(), recovery = false } = {}) {
    this.assertWritable();
    if (!recovery && this.index.size >= this.maxRecords) throw new OperationLedgerError("operation_ledger_full");
    const record = { version: 1, key, envelopeHash: hash, state, startedAt };
    try {
      exclusive(this.io, this.filename(key), record); syncDirectory(this.io, this.directory);
      this.observe(record); return record;
    } catch (error) {
      if (error.code === "EEXIST") return null;
      // Even a torn/unflushed claim reserves its identity; new work stops. The
      // provider was not called, so health recovery may release exactly this
      // content. An imported historical identity is never released.
      this.index.set(key, "UNKNOWN", startedAt);
      if (recovery) this.manualRecoveryRequired = true;
      else this.failedClaims.set(key, JSON.stringify(record));
      throw this.failPersistence("operation_claim_failed");
    }
  }
  persist(record) {
    const temporary = path.join(this.directory, `.write-${crypto.randomUUID()}`);
    try {
      exclusive(this.io, temporary, record); this.io.renameSync(temporary, this.filename(record.key));
      syncDirectory(this.io, this.directory);
    } finally { try { if (this.io.existsSync(temporary)) this.io.unlinkSync(temporary); } catch { /* Store is already failed closed. */ } }
  }
  replace(record) {
    this.assertWritable();
    try { this.persist(record); }
    catch { this.unsyncedKeys.add(record.key); throw this.failPersistence("operation_result_not_durable"); }
    this.observe(record);
  }
  probe() {
    const probe = path.join(this.directory, `.probe-${crypto.randomUUID()}`);
    try { exclusive(this.io, probe, { probe: true }); this.io.unlinkSync(probe); syncDirectory(this.io, this.directory); }
    finally { if (this.io.existsSync(probe)) this.io.unlinkSync(probe); }
  }
  completion(current, outcome) {
    const record = { ...current, completedAt: this.now() };
    if (outcome.messageId && (outcome.ack === undefined || outcome.ack >= 1)) {
      record.state = "SUCCEEDED"; record.messageId = outcome.messageId;
      if (outcome.ack !== undefined) record.providerAck = outcome.ack;
      return record;
    }
    record.state = "UNKNOWN";
    if (!outcome.messageId || !boundedMessageId(outcome.messageId)) return record;
    // WhatsApp created this message but did not confirm it to its server in
    // time. Keep the ID and ACK as evidence; a later ACK or reconciliation can
    // resolve it, while no path may treat it as unsent.
    const evidence = { ...record, providerMessageId: outcome.messageId, providerAck: outcome.ack };
    const candidates = record.receiptCandidates || [];
    const sentAt = outcome.sentAt;
    if (!candidates.some(candidate => candidate.messageId === outcome.messageId) && candidates.length < 4
        && Number.isSafeInteger(sentAt) && sentAt >= record.startedAt - 2000
        && sentAt <= Math.min(record.completedAt, record.startedAt + MAX_RECEIPT_WINDOW_MS) + 2000
        && Number.isSafeInteger(record.completedAt) && record.completedAt >= record.startedAt) {
      const withCandidate = { ...evidence, receiptCandidateConflict: candidates.length > 0 || record.receiptCandidateConflict === true,
        receiptCandidates: [...candidates, { messageId: outcome.messageId, sentAt, observedAt: record.completedAt }] };
      if (fits(withCandidate)) return withCandidate;
    }
    return fits(evidence) ? evidence : { ...record, providerAck: outcome.ack };
  }
  async execute(id, envelope, send, { canStart = () => true } = {}) {
    const key = this.key(id), hash = envelopeHash(envelope);
    let record = this.read(key);
    if (record) return this.replay(record, hash);
    // No record or tombstone exists, and only this writer can create a claim,
    // synchronously, before calling the provider. A rejection up to that point
    // proves the operation was not dispatched here. A follower or an armed
    // restore fence cannot prove that and reports no dispatch state.
    const beforeDispatch = error => this.owner.writer && !this.recovery.required ? markDispatch(error, false) : error;
    const admission = this.admission();
    if (!admission.acceptingNew) { this.admissionRejected++; throw beforeDispatch(new OperationLedgerError(admission.code)); }
    if (!canStart()) { this.admissionRejected++; throw beforeDispatch(new OperationLedgerError("gateway_not_ready")); }
    try { record = this.claim(key, hash); }
    catch (error) { throw beforeDispatch(error); }
    if (!record) {
      record = this.read(key);
      if (!record) throw new OperationLedgerError("operation_claim_unavailable");
      return this.replay(record, hash);
    }
    this.inFlight.add(key);
    let durableResult = false, intended = null;
    try {
      let outcome;
      try { outcome = await send(); } catch { /* A called provider may already have sent. */ }
      // Provider events may have recorded an ID while the awaited send was
      // stalled. Preserve that durable observation when its response is lost.
      intended = this.completion(this.read(key), providerOutcome(outcome));
      this.replace(intended); durableResult = true;
      return { ...this.snapshot(intended), replayed: false };
    } catch (error) {
      // Health recovery completes this exact result once storage works again.
      if (intended && !durableResult) this.pendingResults.set(key, intended);
      throw markDispatch(error, true);
    } finally {
      this.inFlight.delete(key);
      if (!durableResult) this.index.set(key, "UNKNOWN", record.startedAt);
      this.finishDrain();
    }
  }
  // Positive provider evidence can resolve UNKNOWN; there is no mark-unsent API.
  async reconcile(id, envelope, messageId, verify, { sentAt, ack } = {}) {
    const record = this.read(this.key(id)); if (!record) this.missing();
    this.assertEnvelope(record, envelopeHash(envelope));
    if (this.inFlight.has(record.key)) throw new OperationLedgerError("operation_running", 409);
    if (record.state === "SUCCEEDED") return { ...this.snapshot(record), replayed: true };
    this.assertWritable();
    if (this.closing) throw new OperationLedgerError("operation_ledger_closing");
    this.maintenance++;
    try {
      if (!validMessageId(messageId) || !await verify(messageId, envelope)) throw new OperationLedgerError("operation_evidence_mismatch", 409);
      // Verification may await I/O. Re-read and bind synchronously afterwards so
      // concurrent reconciliations cannot attach one provider message twice.
      const current = this.read(record.key);
      if (current.state === "SUCCEEDED") {
        if (current.messageId !== messageId) throw new OperationLedgerError("operation_evidence_mismatch", 409);
        return { ...this.snapshot(current), replayed: true };
      }
      const owners = this.messageOwners.get(messageId);
      if (owners && [...owners].some(key => key !== current.key))
        throw new OperationLedgerError("operation_evidence_already_bound", 409);
      if (current.receiptCandidateConflict || (current.receiptCandidates?.length || 0) > 1)
        throw new OperationLedgerError("operation_evidence_ambiguous", 409);
      if (current.receiptCandidates?.length === 1 && current.receiptCandidates[0].messageId !== messageId)
        throw new OperationLedgerError("operation_evidence_mismatch", 409);
      if (current.providerMessageId !== undefined && current.providerMessageId !== messageId)
        throw new OperationLedgerError("operation_evidence_mismatch", 409);
      if (sentAt !== undefined) this.assertReceiptWindow(current, sentAt);
      const resolved = { ...current, state: "SUCCEEDED", messageId, reconciledAt: this.now() };
      // The verifier read the provider ACK; an older timeout ACK is superseded.
      if (Number.isSafeInteger(ack) && ack >= 1) resolved.providerAck = Math.min(10, ack);
      else delete resolved.providerAck;
      this.replace(resolved); return { ...this.snapshot(resolved), replayed: true };
    } finally { this.maintenance--; this.finishDrain(); }
  }
  receiptWindow(record) {
    const captured = record.receiptCandidates?.length === 1 && !record.receiptCandidateConflict
      ? record.receiptCandidates[0] : null;
    const endAt = record.completedAt ?? (captured && captured.observedAt <= record.startedAt + MAX_RECEIPT_WINDOW_MS
      ? captured.observedAt : undefined);
    if (!Number.isSafeInteger(endAt) || endAt < record.startedAt)
      throw new OperationLedgerError("operation_evidence_window_unavailable", 409);
    const end = Math.min(endAt, record.startedAt + MAX_RECEIPT_WINDOW_MS);
    return { from: record.startedAt - 2000, until: end + 2000 };
  }
  assertReceiptWindow(record, sentAt) {
    const window = this.receiptWindow(record);
    if (!Number.isFinite(sentAt) || sentAt < window.from || sentAt > window.until)
      throw new OperationLedgerError("operation_evidence_mismatch", 409);
    for (const other of this.envelopeRecords.get(record.envelopeHash)?.values() || []) {
      if (other.key === record.key || other.state === "SUCCEEDED") continue;
      const otherEnd = Number.isSafeInteger(other.completedAt) && other.completedAt >= other.startedAt
        ? Math.min(other.completedAt, other.startedAt + MAX_RECEIPT_WINDOW_MS)
        : Number.POSITIVE_INFINITY; // An unfinished competing dispatch cannot be excluded by its age.
      if (sentAt >= other.startedAt - 2000 && sentAt <= otherEnd + 2000)
        throw new OperationLedgerError("operation_evidence_ambiguous", 409);
    }
  }
  async reconcileRecovery(manifest, verifyCoverage) {
    this.assertWritable();
    if (this.closing) throw new OperationLedgerError("operation_ledger_closing");
    this.maintenance++;
    try { return await this.recovery.reconcile(manifest, verifyCoverage); }
    finally { this.maintenance--; this.finishDrain(); }
  }
  prunable(record, cutoff, siblings) {
    if (record.state !== "SUCCEEDED" || !validMessageId(record.messageId) || this.inFlight.has(record.key)
        || terminalAt(record) > cutoff) return false;
    const owners = this.messageOwners.get(record.messageId);
    if (owners && [...owners].some(key => key !== record.key)) return false;
    for (const other of siblings?.values() || []) {
      if (other.key !== record.key && other.state !== "SUCCEEDED"
          && Math.abs(other.startedAt - record.startedAt) <= PRUNE_SIBLING_GUARD_MS) return false;
    }
    return true;
  }
  /**
   * Compacts SUCCEEDED records older than the retention window. Tombstones keep
   * every field a replay, lookup or reconciliation answer needs. They are
   * fsynced, with their directory, before any full record is unlinked; a crash
   * in between leaves a duplicate that the next pass compacts again.
   */
  prune({ now = this.now() } = {}) {
    if (!this.retentionMs || this.closing || this.recovery.required || !this.owner.writer || !this.healthy)
      return { pruned: 0, remaining: 0 };
    this.assertWritable();
    const cutoff = now - this.retentionMs, shards = new Map();
    let eligible = 0;
    for (const siblings of this.envelopeRecords.values()) {
      for (const record of siblings.values()) {
        if (!this.prunable(record, cutoff, siblings)) continue;
        eligible++;
        const shard = this.tombstones.shardOf(record.key);
        if (!shards.has(shard)) shards.set(shard, []);
        shards.get(shard).push(record.key);
      }
    }
    this.pruneEligible = eligible;
    if (!eligible) return { pruned: 0, remaining: 0 };
    // Whole shards bound the fsync work of one pass; the next pass continues.
    const writes = [], selected = [];
    let budget = this.pruneBatchRecords;
    for (const shard of [...shards.keys()].sort()) {
      if (writes.length >= this.pruneBatchShards || budget <= 0) break;
      const tombstones = [];
      for (const key of shards.get(shard).slice(0, budget)) {
        // Memory only nominates candidates; the authoritative file decides.
        let record;
        try { record = this.readRecordFile(key); }
        catch { this.markReadFailure(); throw new OperationLedgerError("operation_record_unreadable"); }
        if (!this.prunable(record, cutoff, this.envelopeRecords.get(record.envelopeHash))) continue;
        tombstones.push(tombstoneOf(record, timestamp(now))); selected.push(record);
      }
      budget -= tombstones.length;
      if (tombstones.length) writes.push([shard, tombstones]);
    }
    // Stale nominations only: wait for the idle interval instead of spinning.
    if (!selected.length) return { pruned: 0, remaining: 0 };
    try {
      this.tombstones.prepare();
      for (const [shard, tombstones] of writes) this.tombstones.write(shard, tombstones);
      this.tombstones.sync();
    } catch (error) {
      if (error.code === "operation_tombstone_unreadable") {
        this.markReadFailure(); throw new OperationLedgerError("operation_record_unreadable");
      }
      throw this.failPersistence("operation_tombstone_not_durable");
    }
    let pruned = 0;
    try {
      for (const record of selected) { this.io.unlinkSync(this.filename(record.key)); this.forget(record); pruned++; }
      syncDirectory(this.io, this.directory);
    } catch { throw this.failPersistence("operation_prune_not_durable"); }
    finally { this.prunedTotal += pruned; if (pruned) this.lastPrunedAt = now; }
    return { pruned, remaining: Math.max(0, eligible - pruned) };
  }
  /**
   * Re-probes storage after a persistence failure. Claims that failed before
   * the provider call are released only when their file holds a prefix of this
   * process's own claim bytes; results that failed after a provider call are
   * completed from memory only over the unchanged pre-result record. Any
   * unreadable or unexpected evidence keeps admission closed until an operator
   * restarts the gateway.
   */
  recoverHealth({ now = this.now() } = {}) {
    if (this.healthy) return true;
    if (this.closing || !this.owner.writer || this.manualRecoveryRequired || now < this.nextRecoveryAt) return false;
    this.nextRecoveryAt = now + RECOVERY_RETRY_MS;
    try {
      this.owner.assertOwner();
      this.probe();
      for (const [key, expected] of this.failedClaims) {
        let content = null;
        try { content = this.io.readFileSync(this.filename(key), "utf8"); }
        catch (error) { if (error.code !== "ENOENT") throw error; }
        if (content === null) continue;
        if (!expected.startsWith(content.replace(/\u0000+$/u, ""))) { this.manualRecoveryRequired = true; return false; }
        this.io.unlinkSync(this.filename(key));
      }
      syncDirectory(this.io, this.directory);
      for (const key of this.failedClaims.keys()) this.index.delete(key);
      this.failedClaims.clear();
      const pending = new Map();
      for (const key of new Set([...this.unsyncedKeys, ...this.pendingResults.keys()])) {
        let current;
        try { current = this.readRecordFile(key); }
        catch { this.markReadFailure(); return false; }
        const intended = this.pendingResults.get(key);
        if (intended && current.completedAt === undefined && current.state !== "SUCCEEDED"
            && current.envelopeHash === intended.envelopeHash && current.startedAt === intended.startedAt) pending.set(key, intended);
        else this.observe(current);
      }
      this.healthy = true;
      for (const [key, intended] of pending) { this.replace(intended); this.pendingResults.delete(key); }
      this.pendingResults.clear(); this.unsyncedKeys.clear();
      this.recoveredTotal++; this.lastRecoveredAt = now;
      return true;
    } catch {
      this.healthy = false; this.recoveryFailures++;
      return false;
    }
  }
  // One bounded maintenance step: health recovery, then retention compaction.
  maintain({ now = this.now() } = {}) {
    const result = { recovered: false, pruned: 0, remaining: 0 };
    if (this.closing || !this.owner.writer) return result;
    if (!this.healthy) {
      result.recovered = this.recoverHealth({ now });
      if (!result.recovered) return result;
    }
    if (now < this.nextPruneAt) return result;
    Object.assign(result, this.prune({ now }));
    this.nextPruneAt = result.remaining > 0 ? now : now + PRUNE_IDLE_INTERVAL_MS;
    return result;
  }
  finishDrain() {
    if (this.inFlight.size || this.maintenance) return;
    for (const resolve of this.closeWaiters.splice(0)) resolve();
  }
  async close() {
    this.closing = true;
    if (this.inFlight.size || this.maintenance) await new Promise(resolve => this.closeWaiters.push(resolve));
    this.owner.close();
  }
}
function validMessageId(value) { return typeof value === "string" && value.length > 0 && value.length <= 512; }
function boundedMessageId(value) { return validMessageId(value) && Buffer.byteLength(JSON.stringify(value), "utf8") <= 512; }
function timestamp(value) { return Math.max(0, Math.floor(Number(value) || 0)); }
function fits(record) { return Buffer.byteLength(JSON.stringify(record), "utf8") <= MAX_RECORD_BYTES; }
function terminalAt(record) {
  return Math.max(record.startedAt, ...["completedAt", "reconciledAt", "ackConfirmedAt"]
    .map(field => Number.isSafeInteger(record[field]) ? record[field] : 0));
}
function tombstoneOf(record, prunedAt) {
  const tombstone = { version: 1, key: record.key, envelopeHash: record.envelopeHash, state: "SUCCEEDED",
    startedAt: record.startedAt, messageId: record.messageId };
  for (const field of TOMBSTONE_FIELDS) if (record[field] !== undefined) tombstone[field] = record[field];
  tombstone.prunedAt = prunedAt;
  return tombstone;
}
function validInterruptedClaim(value) {
  return Boolean(value) && typeof value === "object" && Number.isSafeInteger(value.size) && value.size >= 0
    && Number.isSafeInteger(value.repairedAt) && value.repairedAt >= 0;
}
function validRecord(record, key) {
  if (!record || typeof record !== "object" || record.version !== 1 || record.key !== key
      || !["RUNNING", "UNKNOWN", "SUCCEEDED"].includes(record.state)
      || !Number.isSafeInteger(record.startedAt) || record.startedAt < 0) return false;
  if (!HASH.test(record.envelopeHash) && !(record.envelopeHash === null && record.state === "UNKNOWN"
      && validInterruptedClaim(record.interruptedClaim))) return false;
  if (record.interruptedClaim !== undefined && !validInterruptedClaim(record.interruptedClaim)) return false;
  if (record.historySync !== undefined && (!record.historySync || !HASH.test(record.historySync.chatHash)
      || !Number.isSafeInteger(record.historySync.requestedAt) || record.historySync.requestedAt < 0
      || (record.historySync.outcome !== undefined
        && !["RESERVED", "REQUESTED", "NOT_AVAILABLE", "UNAVAILABLE"].includes(record.historySync.outcome)))) return false;
  if (record.receiptCandidates !== undefined && (!Array.isArray(record.receiptCandidates)
      || record.receiptCandidates.length > 4 || record.receiptCandidates.some(candidate => !candidate
        || !boundedMessageId(candidate.messageId)
        || !Number.isSafeInteger(candidate.sentAt) || candidate.sentAt < 0
        || !Number.isSafeInteger(candidate.observedAt) || candidate.observedAt < record.startedAt))) return false;
  if (record.receiptCandidateConflict !== undefined && typeof record.receiptCandidateConflict !== "boolean") return false;
  if (record.providerAck !== undefined
      && (!Number.isSafeInteger(record.providerAck) || record.providerAck < -1 || record.providerAck > 10)) return false;
  if (record.providerMessageId !== undefined && !boundedMessageId(record.providerMessageId)) return false;
  for (const field of ["ackConfirmedAt", "prunedAt"]) {
    if (record[field] !== undefined && (!Number.isSafeInteger(record[field]) || record[field] < 0)) return false;
  }
  return record.state !== "SUCCEEDED" || validMessageId(record.messageId);
}
// A claim is serialized once, in this field order, before the provider call.
// Only a strict prefix of it (optionally zero-filled by the filesystem after
// a crash) is an interrupted claim.
function interruptedClaim(content, key) {
  const text = content.replace(/\u0000+$/u, "");
  if (text.includes("\u0000")) return null;
  const head = `{"version":1,"key":"${key}","envelopeHash":"`;
  if (text.length <= head.length) return head.startsWith(text) ? { envelopeHash: null } : null;
  if (!text.startsWith(head)) return null;
  let rest = text.slice(head.length);
  const hex = /^[a-f0-9]*/u.exec(rest)[0];
  if (hex.length < 64) return hex.length === rest.length ? { envelopeHash: null } : null;
  const hash = hex.slice(0, 64);
  rest = rest.slice(64);
  for (const state of ["RUNNING", "UNKNOWN"]) {
    const middle = `","state":"${state}","startedAt":`;
    if (rest.length <= middle.length ? middle.startsWith(rest) : rest.startsWith(middle) && /^[0-9]{0,16}$/u.test(rest.slice(middle.length)))
      return { envelopeHash: hash };
  }
  return null;
}
module.exports = { OperationLedger, OperationLedgerError, operationIdFromRequest, envelopeHash, providerOutcome,
  ledgerErrorBody, interruptedClaim, DAY_MS };
