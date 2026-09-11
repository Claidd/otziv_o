"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { OperationLedgerIndex } = require("./operation-ledger-index");
const { OperationLedgerOwner, syncDirectory, exclusive } = require("./operation-ledger-owner");
const { OperationLedgerRecovery } = require("./operation-ledger-recovery");

const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/u;
const HASH = /^[a-f0-9]{64}$/u;
const RECORD_FILE = /^[a-f0-9]{64}\.json$/u;
const MAX_RECORD_BYTES = 4096;

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
      || !["send", "send-group"].includes(kind)) throw new OperationLedgerError("invalid_operation_envelope", 400);
  return digest("otziv.whatsapp.envelope.v1", JSON.stringify([clientId, kind, destination, message]));
}
function digest(domain, value) { return crypto.createHash("sha256").update(domain).update("\0").update(value).digest("hex"); }

/**
 * JSON claims/results are authoritative and never expire. Metadata is rebuilt
 * once at startup; admission and metrics do not scan the directory. One OS-held
 * writer lock protects the count and atomic mutations. A durable claim precedes
 * every provider call. Use local persistent storage with atomic rename/fsync.
 */
class OperationLedger {
  constructor(directory, { maxRecords = 100_000, now = () => Date.now(), io = fs, restoreId } = {}) {
    this.directory = path.resolve(directory); this.now = now; this.io = io;
    this.maxRecords = Math.max(1, Math.min(Math.floor(Number(maxRecords)) || 100_000, 1_000_000));
    this.index = new OperationLedgerIndex(); this.inFlight = new Set(); this.maintenance = 0;
    this.healthy = true; this.closing = false; this.persistenceFailures = 0; this.readFailures = 0;
    this.admissionRejected = 0; this.closeWaiters = [];
    io.mkdirSync(this.directory, { recursive: true, mode: 0o700 });
    const directoryInfo = io.lstatSync(this.directory);
    if (!directoryInfo.isDirectory() || directoryInfo.isSymbolicLink()) throw new OperationLedgerError("invalid_ledger_directory");
    io.accessSync(this.directory, io.constants.R_OK | io.constants.W_OK);
    this.owner = new OperationLedgerOwner(this.directory, { io });
    try {
      // Arm restore mode before scanning or opening admission. A copied old
      // completed marker does not override a new restoreId supplied by deployment.
      this.recovery = new OperationLedgerRecovery(this, restoreId);
      for (const name of io.readdirSync(this.directory)) {
        if (!RECORD_FILE.test(name)) continue;
        const key = name.slice(0, -5);
        try { this.observe(this.read(key)); }
        catch { this.index.set(key, "UNKNOWN", 0); }
      }
      if (this.owner.writer) {
        const probe = path.join(this.directory, `.probe-${crypto.randomUUID()}`);
        try { exclusive(io, probe, { probe: true }); io.unlinkSync(probe); syncDirectory(io, this.directory); }
        finally { if (io.existsSync(probe)) io.unlinkSync(probe); }
      }
    } catch (error) { this.owner.close(); throw error; }
  }
  key(id) {
    if (typeof id !== "string" || !ID.test(id)) throw new OperationLedgerError("invalid_operation_id", 400);
    return digest("otziv.whatsapp.operation.v1", id);
  }
  filename(key) { return path.join(this.directory, `${key}.json`); }
  observe(record) {
    if (record) this.index.set(record.key, record.state === "RUNNING" && !this.inFlight.has(record.key) ? "UNKNOWN" : record.state, record.startedAt);
    return record;
  }
  read(key) {
    try {
      const file = this.filename(key), info = this.io.lstatSync(file);
      if (!info.isFile() || info.isSymbolicLink() || info.size > MAX_RECORD_BYTES) throw new Error("invalid record");
      const record = JSON.parse(this.io.readFileSync(file, "utf8"));
      if (record.version !== 1 || record.key !== key || !HASH.test(record.envelopeHash)
          || !["RUNNING", "UNKNOWN", "SUCCEEDED"].includes(record.state)
          || !Number.isSafeInteger(record.startedAt) || record.startedAt < 0
          || (record.state === "SUCCEEDED" && !validMessageId(record.messageId))) throw new Error("invalid record");
      return this.observe(record);
    } catch (error) {
      if (error.code === "ENOENT" && !this.index.records.has(key)) return null;
      this.healthy = false; this.readFailures++;
      throw new OperationLedgerError(error.code === "ENOENT" ? "operation_record_missing" : "operation_record_unreadable");
    }
  }
  snapshot(record) {
    return { state: record.state === "RUNNING" && !this.inFlight.has(record.key) ? "UNKNOWN" : record.state,
      messageId: record.state === "SUCCEEDED" ? record.messageId : null, envelopeHash: record.envelopeHash };
  }
  missing() {
    if (this.recovery.required) throw new OperationLedgerError("operation_recovery_required");
    throw new OperationLedgerError("operation_not_found", 404);
  }
  lookup(id) { const record = this.read(this.key(id)); if (!record) this.missing(); return this.snapshot(record); }
  assertEnvelope(record, hash) { if (record.envelopeHash !== hash) throw new OperationLedgerError("operation_payload_conflict", 409); }
  admission() {
    const code = !this.owner.writer ? "operation_writer_locked" : this.closing ? "operation_ledger_closing"
      : !this.healthy ? "operation_ledger_unavailable" : this.recovery.required ? "operation_recovery_required"
      : this.index.size >= this.maxRecords ? "operation_ledger_full" : null;
    return { acceptingNew: code === null, code };
  }
  metrics() {
    const oldest = this.index.oldestUnknown();
    return { capacity: this.maxRecords, records: this.index.size, remaining: Math.max(0, this.maxRecords - this.index.size),
      utilizationRatio: this.index.size / this.maxRecords, capacityWarning: this.index.size >= this.maxRecords * 0.8,
      unknown: this.index.unknown, running: this.inFlight.size,
      oldestUnknownAgeSeconds: oldest === null ? null : Math.max(0, (this.now() - oldest) / 1000),
      persistenceFailures: this.persistenceFailures, readFailures: this.readFailures, admissionRejected: this.admissionRejected,
      writer: this.owner.writer, recoveryRequired: this.recovery.required, ...this.admission() };
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
      this.index.set(key, state, startedAt); return record;
    } catch (error) {
      if (error.code === "EEXIST") return null;
      // Even a torn/unflushed claim reserves its identity; new work stops.
      this.index.set(key, "UNKNOWN", startedAt);
      throw this.failPersistence("operation_claim_failed");
    }
  }
  replace(record) {
    this.assertWritable();
    const temporary = path.join(this.directory, `.write-${crypto.randomUUID()}`);
    try {
      exclusive(this.io, temporary, record); this.io.renameSync(temporary, this.filename(record.key));
      syncDirectory(this.io, this.directory); this.observe(record);
    } catch { throw this.failPersistence("operation_result_not_durable"); }
    finally { try { if (this.io.existsSync(temporary)) this.io.unlinkSync(temporary); } catch { /* Store is already failed closed. */ } }
  }
  async execute(id, envelope, send, { canStart = () => true } = {}) {
    const key = this.key(id), hash = envelopeHash(envelope);
    let record = this.read(key);
    if (record) { this.assertEnvelope(record, hash); return { ...this.snapshot(record), replayed: true }; }
    const admission = this.admission();
    if (!admission.acceptingNew) { this.admissionRejected++; throw new OperationLedgerError(admission.code); }
    if (!canStart()) { this.admissionRejected++; throw new OperationLedgerError("gateway_not_ready"); }
    record = this.claim(key, hash);
    if (!record) {
      record = this.read(key);
      if (!record) throw new OperationLedgerError("operation_claim_unavailable");
      this.assertEnvelope(record, hash); return { ...this.snapshot(record), replayed: true };
    }
    this.inFlight.add(key);
    let durableResult = false;
    try {
      let messageId;
      try { messageId = await send(); } catch { /* A called provider may already have sent. */ }
      record = { ...record, completedAt: this.now(), state: validMessageId(messageId) ? "SUCCEEDED" : "UNKNOWN" };
      if (record.state === "SUCCEEDED") record.messageId = messageId;
      this.replace(record); durableResult = true;
      return { ...this.snapshot(record), replayed: false };
    } finally {
      this.inFlight.delete(key);
      if (!durableResult) this.index.set(key, "UNKNOWN", record.startedAt);
      this.finishDrain();
    }
  }
  // Positive provider evidence can resolve UNKNOWN; there is no mark-unsent API.
  async reconcile(id, envelope, messageId, verify) {
    const record = this.read(this.key(id)); if (!record) this.missing();
    this.assertEnvelope(record, envelopeHash(envelope));
    if (this.inFlight.has(record.key)) throw new OperationLedgerError("operation_running", 409);
    if (record.state === "SUCCEEDED") return { ...this.snapshot(record), replayed: true };
    this.assertWritable();
    if (this.closing) throw new OperationLedgerError("operation_ledger_closing");
    this.maintenance++;
    try {
      if (!validMessageId(messageId) || !await verify(messageId, envelope)) throw new OperationLedgerError("operation_evidence_mismatch", 409);
      const resolved = { ...record, state: "SUCCEEDED", messageId, completedAt: this.now() };
      this.replace(resolved); return { ...this.snapshot(resolved), replayed: true };
    } finally { this.maintenance--; this.finishDrain(); }
  }
  async reconcileRecovery(manifest, verifyCoverage) {
    this.assertWritable();
    if (this.closing) throw new OperationLedgerError("operation_ledger_closing");
    this.maintenance++;
    try { return await this.recovery.reconcile(manifest, verifyCoverage); }
    finally { this.maintenance--; this.finishDrain(); }
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
module.exports = { OperationLedger, OperationLedgerError, operationIdFromRequest, envelopeHash };
