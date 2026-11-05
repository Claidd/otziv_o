"use strict";

const crypto = require("node:crypto");
const path = require("node:path");
const { exclusive, syncDirectory } = require("./operation-ledger-owner");

const HASH = /^[a-f0-9]{64}$/u;
const SHARD = /^[a-f0-9]{3}$/u;
const MAX_SHARD_BYTES = 64 * 1024 * 1024;

function failure(code) { return Object.assign(new Error(code), { code, statusCode: 503 }); }

/**
 * Compact identity tombstones of pruned SUCCEEDED records. 4096 shard files
 * keep an addressed lookup small without loading history into memory. Every
 * shard is replaced atomically and fsynced, together with its directory,
 * before any full record it covers may be unlinked.
 */
class OperationLedgerTombstones {
  constructor(directory, { io }) {
    this.parent = directory; this.directory = path.join(directory, "tombstones"); this.io = io; this.prepared = false;
  }
  shardOf(key) { return key.slice(0, 3); }
  filename(shard) { return path.join(this.directory, `${shard}.json`); }
  readShard(shard) {
    if (!SHARD.test(shard)) throw failure("operation_tombstone_unreadable");
    try {
      const file = this.filename(shard), info = this.io.lstatSync(file);
      if (!info.isFile() || info.isSymbolicLink() || info.size > MAX_SHARD_BYTES) throw new Error("invalid shard");
      const value = JSON.parse(this.io.readFileSync(file, "utf8"));
      if (value?.version !== 1 || value.shard !== shard || !value.tombstones || typeof value.tombstones !== "object"
          || Array.isArray(value.tombstones)
          || Object.keys(value.tombstones).some(key => !HASH.test(key) || !key.startsWith(shard))) throw new Error("invalid shard");
      return value;
    } catch (error) {
      if (error.code === "ENOENT") return null;
      throw failure("operation_tombstone_unreadable");
    }
  }
  find(key) {
    const shard = this.readShard(this.shardOf(key));
    return shard && Object.hasOwn(shard.tombstones, key) ? shard.tombstones[key] : null;
  }
  // Called by the writer before its first compaction in this process.
  prepare() {
    if (this.prepared) return;
    const info = (() => { try { return this.io.lstatSync(this.directory); } catch (error) { if (error.code === "ENOENT") return null; throw error; } })();
    if (info && (!info.isDirectory() || info.isSymbolicLink())) throw failure("operation_tombstone_directory_invalid");
    if (!info) { this.io.mkdirSync(this.directory, { mode: 0o700 }); syncDirectory(this.io, this.parent); }
    this.removeTemporary(); this.prepared = true;
  }
  // Merges tombstones into one shard through an exclusive temporary file and an
  // atomic rename. A crash leaves either the previous or the merged shard.
  write(shard, tombstones) {
    const current = this.readShard(shard) || { version: 1, shard, tombstones: {} };
    const next = { version: 1, shard, tombstones: { ...current.tombstones } };
    for (const tombstone of tombstones) {
      if (this.shardOf(tombstone.key) !== shard) throw failure("operation_tombstone_shard_mismatch");
      next.tombstones[tombstone.key] = tombstone;
    }
    const temporary = path.join(this.directory, `.write-${crypto.randomUUID()}`);
    try { exclusive(this.io, temporary, next); this.io.renameSync(temporary, this.filename(shard)); }
    finally { try { if (this.io.existsSync(temporary)) this.io.unlinkSync(temporary); } catch { /* Caller fails closed. */ } }
  }
  sync() { syncDirectory(this.io, this.directory); }
  // An uncommitted temporary shard never authorized removing a record.
  removeTemporary() {
    for (const name of this.io.readdirSync(this.directory)) {
      if (name.startsWith(".write-")) this.io.unlinkSync(path.join(this.directory, name));
    }
  }
}

module.exports = { OperationLedgerTombstones };
