"use strict";
const crypto = require("node:crypto");
const path = require("node:path");
const { exclusive, syncDirectory } = require("./operation-ledger-owner");
const TOKEN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/u;
const HASH = /^[a-f0-9]{64}$/u;
function failure(code, statusCode = 503) { return Object.assign(new Error(code), { code, statusCode }); }

/** Restore fence survives restart independently of process environment. */
class OperationLedgerRecovery {
  constructor(ledger, restoreId) {
    this.ledger = ledger; this.filename = path.join(ledger.directory, ".recovery.json"); this.active = false;
    this.record = this.read();
    if (restoreId !== undefined && restoreId !== "") {
      if (typeof restoreId !== "string" || !TOKEN.test(restoreId)) throw failure("invalid_operation_restore_id");
      if (this.record?.restoreId !== restoreId) {
        this.record = { version: 1, restoreId, state: "REQUIRED", startedAt: ledger.now() };
        // A follower can read known IDs but must never open new admission.
        if (ledger.owner.writer) this.write(this.record);
      }
    }
  }
  get required() { return this.record?.state === "REQUIRED"; }
  read() {
    const io = this.ledger.io;
    try {
      const stat = io.lstatSync(this.filename);
      if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 8192) throw new Error("invalid marker");
      const value = JSON.parse(io.readFileSync(this.filename, "utf8"));
      if (value.version !== 1 || !TOKEN.test(value.restoreId) || !["REQUIRED", "COMPLETE"].includes(value.state)
          || (value.state === "COMPLETE" && !HASH.test(value.manifestHash))) throw new Error("invalid marker");
      return value;
    } catch (error) {
      if (error.code === "ENOENT") return null;
      throw failure("operation_recovery_marker_unreadable");
    }
  }
  write(record) {
    const ledger = this.ledger, io = ledger.io;
    ledger.owner.assertOwner();
    const temporary = path.join(ledger.directory, `.recovery-write-${crypto.randomUUID()}`);
    try {
      exclusive(io, temporary, record); io.renameSync(temporary, this.filename); syncDirectory(io, ledger.directory);
    } catch { throw ledger.failPersistence("operation_recovery_not_durable"); }
    finally { try { if (io.existsSync(temporary)) io.unlinkSync(temporary); } catch { /* Failed closed. */ } }
  }
  async reconcile(manifest, verifyCoverage) {
    if (!this.required) throw failure("operation_recovery_not_required", 409);
    if (this.active) throw failure("operation_recovery_running", 409);
    const canonical = normalizeManifest(manifest, this.record.restoreId, this.ledger);
    const manifestHash = crypto.createHash("sha256").update(JSON.stringify(canonical)).digest("hex");
    this.active = true;
    try {
      // Completeness cannot be inferred from a restored directory. A separate
      // authority must verify the entire outage window, including IDs absent
      // from the snapshot, and bind that attestation to this exact manifest.
      const proof = typeof verifyCoverage === "function" ? await verifyCoverage({ manifest: canonical, manifestHash }) : null;
      if (!proof || proof.complete !== true || proof.restoreId !== canonical.restoreId || proof.manifestHash !== manifestHash
          || proof.watermark !== canonical.watermark || proof.authority !== canonical.authority) {
        throw failure("operation_recovery_coverage_unverified", 409);
      }
      for (const operation of canonical.operations) {
        const key = this.ledger.key(operation.operationId);
        let existing = this.ledger.read(key);
        if (!existing) {
          // Recovery protection may exceed configured capacity: never discard
          // an authoritative historical identity just to make room for sends.
          existing = this.ledger.claim(key, operation.envelopeHash, { state: "UNKNOWN", startedAt: operation.startedAt, recovery: true });
          if (!existing) existing = this.ledger.read(key);
        }
        if (!existing) throw failure("operation_recovery_claim_unavailable");
        this.ledger.assertEnvelope(existing, operation.envelopeHash);
      }
      const completed = { ...this.record, state: "COMPLETE", completedAt: this.ledger.now(), manifestHash,
        authority: canonical.authority, watermark: canonical.watermark, importedCoverageRecords: canonical.operations.length };
      this.write(completed); this.record = completed;
      return { restoreId: completed.restoreId, state: completed.state, manifestHash, records: canonical.operations.length };
    } finally { this.active = false; }
  }
}
function normalizeManifest(manifest, restoreId, ledger) {
  if (!manifest || manifest.restoreId !== restoreId || typeof manifest.authority !== "string" || !manifest.authority.trim()
      || manifest.authority.length > 256 || typeof manifest.watermark !== "string" || !manifest.watermark.trim()
      || manifest.watermark.length > 512 || !Array.isArray(manifest.operations) || manifest.operations.length > 1_000_000) {
    throw failure("invalid_operation_recovery_manifest", 400);
  }
  const seen = new Set();
  const operations = manifest.operations.map(operation => {
    const key = ledger.key(operation.operationId);
    if (seen.has(key) || !HASH.test(operation.envelopeHash) || !Number.isSafeInteger(operation.startedAt) || operation.startedAt < 0) {
      throw failure("invalid_operation_recovery_manifest", 400);
    }
    seen.add(key);
    return { operationId: operation.operationId, envelopeHash: operation.envelopeHash, startedAt: operation.startedAt };
  }).sort((a, b) => a.operationId < b.operationId ? -1 : a.operationId > b.operationId ? 1 : 0);
  return { restoreId, authority: manifest.authority, watermark: manifest.watermark, operations };
}
module.exports = { OperationLedgerRecovery, normalizeManifest };
