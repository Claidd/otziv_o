"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/u;
const HASH = /^[a-f0-9]{64}$/u;
const MAX_RECORD_BYTES = 4096;

class OperationLedgerError extends Error {
  constructor(code, statusCode = 503) { super(code); this.code = code; this.statusCode = statusCode; }
}

function operationIdFromRequest(req) {
  const header = req.get("Idempotency-Key");
  const body = req.body?.operationId;
  if (header !== undefined && body !== undefined && header !== body) {
    throw new OperationLedgerError("operation_id_conflict", 400);
  }
  const id = header ?? body;
  if (id === undefined) return null; // Explicit compatibility for the legacy producer.
  if (typeof id !== "string" || !ID.test(id)) throw new OperationLedgerError("invalid_operation_id", 400);
  return id;
}

function envelopeHash({ clientId, kind, destination, message }) {
  if (![clientId, destination, message].every((value) => typeof value === "string" && value.length > 0)
      || !["send", "send-group"].includes(kind)) {
    throw new OperationLedgerError("invalid_operation_envelope", 400);
  }
  return digest("otziv.whatsapp.envelope.v1", JSON.stringify([clientId, kind, destination, message]));
}

function digest(domain, value) {
  return crypto.createHash("sha256").update(domain).update("\0").update(value).digest("hex");
}

/**
 * One immutable claim per operation. O_EXCL and fsync happen BEFORE sendMessage.
 * Terminal records replace the claim atomically. Unfinished/corrupt records never
 * authorize another send, including after a process crash. No TTL eviction:
 * deleting records would silently remove replay protection. Use a persistent,
 * local filesystem with atomic create/rename and fsync; not an object/NFS mount.
 */
class OperationLedger {
  constructor(directory, { maxRecords = 100_000, now = () => Date.now() } = {}) {
    this.directory = path.resolve(directory);
    this.now = now;
    this.maxRecords = Math.max(1, Math.min(Number(maxRecords) || 100_000, 1_000_000));
    this.inFlight = new Set();
    this.healthy = true;
    fs.mkdirSync(this.directory, { recursive: true, mode: 0o700 });
    if (!fs.lstatSync(this.directory).isDirectory() || fs.lstatSync(this.directory).isSymbolicLink()) {
      throw new OperationLedgerError("invalid_ledger_directory");
    }
    fs.accessSync(this.directory, fs.constants.R_OK | fs.constants.W_OK);
    // Confirm writes can be made durable before declaring the store ready.
    const probe = path.join(this.directory, `.probe-${crypto.randomUUID()}`);
    try {
      writeExclusive(probe, "probe\n");
      fs.unlinkSync(probe);
      syncDirectory(this.directory);
    } finally {
      if (fs.existsSync(probe)) fs.unlinkSync(probe);
    }
  }

  key(id) {
    if (typeof id !== "string" || !ID.test(id)) throw new OperationLedgerError("invalid_operation_id", 400);
    return digest("otziv.whatsapp.operation.v1", id);
  }

  filename(key) { return path.join(this.directory, `${key}.json`); }

  read(key) {
    const file = this.filename(key);
    try {
      const info = fs.lstatSync(file);
      if (!info.isFile() || info.isSymbolicLink() || info.size > MAX_RECORD_BYTES) throw new Error("invalid record");
      const record = JSON.parse(fs.readFileSync(file, "utf8"));
      if (record.version !== 1 || record.key !== key || !HASH.test(record.envelopeHash)
          || !["RUNNING", "UNKNOWN", "SUCCEEDED"].includes(record.state)
          || !Number.isSafeInteger(record.startedAt)
          || (record.state === "SUCCEEDED" && !validMessageId(record.messageId))) throw new Error("invalid record");
      return record;
    } catch (error) {
      if (error.code === "ENOENT") return null;
      // A torn claim still occupies its identity. Never replace/replay it.
      this.healthy = false;
      throw new OperationLedgerError("operation_record_unreadable");
    }
  }

  snapshot(record) {
    return {
      state: record.state === "RUNNING" && !this.inFlight.has(record.key) ? "UNKNOWN" : record.state,
      messageId: record.state === "SUCCEEDED" ? record.messageId : null,
      envelopeHash: record.envelopeHash,
    };
  }

  lookup(id) {
    const record = this.read(this.key(id));
    if (!record) throw new OperationLedgerError("operation_not_found", 404);
    return this.snapshot(record);
  }

  assertEnvelope(record, hash) {
    if (record.envelopeHash !== hash) throw new OperationLedgerError("operation_payload_conflict", 409);
  }

  claim(key, hash) {
    // A full ledger rejects new work instead of evicting deduplication memory.
    const count = fs.readdirSync(this.directory).filter((name) => /^[a-f0-9]{64}\.json$/u.test(name)).length;
    if (count >= this.maxRecords) throw new OperationLedgerError("operation_ledger_full");
    const record = { version: 1, key, envelopeHash: hash, state: "RUNNING", startedAt: this.now() };
    try {
      writeExclusive(this.filename(key), JSON.stringify(record));
      syncDirectory(this.directory);
      return record;
    } catch (error) {
      if (error.code === "EEXIST") return null;
      this.healthy = false;
      throw new OperationLedgerError("operation_claim_failed");
    }
  }

  replace(record) {
    const temporary = path.join(this.directory, `.write-${crypto.randomUUID()}`);
    try {
      writeExclusive(temporary, JSON.stringify(record));
      fs.renameSync(temporary, this.filename(record.key));
      syncDirectory(this.directory);
    } catch {
      this.healthy = false;
      throw new OperationLedgerError("operation_result_not_durable");
    } finally {
      if (fs.existsSync(temporary)) fs.unlinkSync(temporary);
    }
  }

  async execute(id, envelope, send, { canStart = () => true } = {}) {
    const key = this.key(id);
    const hash = envelopeHash(envelope);
    let record = this.read(key);
    if (record) {
      this.assertEnvelope(record, hash);
      return { ...this.snapshot(record), replayed: true };
    }
    if (!this.healthy) throw new OperationLedgerError("operation_ledger_unavailable");
    if (!canStart()) throw new OperationLedgerError("gateway_not_ready");
    record = this.claim(key, hash);
    if (!record) {
      record = this.read(key);
      if (!record) throw new OperationLedgerError("operation_claim_unavailable");
      this.assertEnvelope(record, hash);
      return { ...this.snapshot(record), replayed: true };
    }
    this.inFlight.add(key);
    try {
      // Once called, every rejection is ambiguous: the provider may have sent.
      let messageId;
      try { messageId = await send(); } catch { /* Persist UNKNOWN below. */ }
      record = { ...record, completedAt: this.now(), state: validMessageId(messageId) ? "SUCCEEDED" : "UNKNOWN" };
      if (record.state === "SUCCEEDED") record.messageId = messageId;
      this.replace(record);
      return { ...this.snapshot(record), replayed: false };
    } finally {
      this.inFlight.delete(key);
    }
  }

  // Only a provider-verified message matching the complete original envelope
  // can resolve UNKNOWN. There is intentionally no "mark unsent and retry" API.
  async reconcile(id, envelope, messageId, verify) {
    const key = this.key(id);
    const record = this.read(key);
    if (!record) throw new OperationLedgerError("operation_not_found", 404);
    this.assertEnvelope(record, envelopeHash(envelope));
    if (this.inFlight.has(key)) throw new OperationLedgerError("operation_running", 409);
    if (record.state === "SUCCEEDED") return { ...this.snapshot(record), replayed: true };
    if (!validMessageId(messageId) || !await verify(messageId, envelope)) {
      throw new OperationLedgerError("operation_evidence_mismatch", 409);
    }
    const resolved = { ...record, state: "SUCCEEDED", messageId, completedAt: this.now() };
    this.replace(resolved);
    return { ...this.snapshot(resolved), replayed: true };
  }
}

function validMessageId(value) { return typeof value === "string" && value.length > 0 && value.length <= 512; }

function writeExclusive(filename, text) {
  const descriptor = fs.openSync(filename, "wx", 0o600);
  try { fs.writeFileSync(descriptor, text, "utf8"); fs.fsyncSync(descriptor); }
  finally { fs.closeSync(descriptor); }
}

function syncDirectory(directory) {
  // Windows does not expose directory fsync through Node. Production is Linux;
  // file flush + atomic rename is still used for local Windows test/dev stores.
  if (process.platform === "win32") return;
  const descriptor = fs.openSync(directory, "r");
  try { fs.fsyncSync(descriptor); } finally { fs.closeSync(descriptor); }
}

module.exports = { OperationLedger, OperationLedgerError, operationIdFromRequest, envelopeHash };
