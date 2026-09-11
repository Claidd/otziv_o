"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { spawnSync } = require("node:child_process");

function failure(code) { const error = new Error(code); error.code = code; error.statusCode = 503; return error; }
function syncDirectory(io, directory) {
  if (process.platform === "win32") return;
  const fd = io.openSync(directory, "r");
  try { io.fsyncSync(fd); } finally { io.closeSync(fd); }
}
function exclusive(io, filename, value) {
  const fd = io.openSync(filename, "wx", 0o600);
  try { io.writeFileSync(fd, JSON.stringify(value)); io.fsyncSync(fd); }
  finally { io.closeSync(fd); }
}
function processStart(io, procRoot, pid) {
  const stat = io.readFileSync(path.join(procRoot, String(pid), "stat"), "utf8");
  // comm may contain spaces and ')'; fields after the last ')' start at #3.
  return stat.slice(stat.lastIndexOf(")") + 2).split(" ")[19];
}
function processIdentity(io = fs, procRoot = "/proc") {
  const identity = { pid: process.pid, hostname: os.hostname(), token: crypto.randomUUID(), platform: process.platform };
  if (process.platform === "linux") {
    identity.bootId = io.readFileSync(path.join(procRoot, "sys/kernel/random/boot_id"), "utf8").trim();
    identity.pidNamespace = io.readlinkSync(path.join(procRoot, "self/ns/pid"));
    identity.start = processStart(io, procRoot, process.pid);
  }
  return identity;
}
function ownerRecord(io, filename) {
  const stat = io.lstatSync(filename);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 4096) throw failure("operation_writer_lock_unreadable");
  const owner = JSON.parse(io.readFileSync(filename, "utf8"));
  if (!Number.isSafeInteger(owner.pid) || owner.pid < 1 || typeof owner.token !== "string"
      || !/^[a-f0-9-]{36}$/.test(owner.token) || typeof owner.hostname !== "string") throw failure("operation_writer_lock_unreadable");
  return owner;
}
function absentInSameScope(owner, identity, io = fs) {
  if (owner.platform === "linux" && identity.platform === "linux") {
    if (owner.bootId !== identity.bootId || owner.pidNamespace !== identity.pidNamespace) return false;
    try { return processStart(io, "/proc", owner.pid) !== owner.start; }
    catch (error) { return error.code === "ENOENT"; }
  }
  if (owner.hostname !== identity.hostname || owner.platform !== identity.platform) return false;
  try { process.kill(owner.pid, 0); return false; }
  catch (error) { return error.code === "ESRCH"; }
}

/**
 * Linux: kernel-held flock on a permanent inode, released by process death.
 * Windows local development: conservative O_EXCL owner without time leases;
 * takeover requires proving the old PID absent, serialized by a hard link.
 */
class OperationLedgerOwner {
  constructor(directory, { io = fs } = {}) {
    this.io = io; this.directory = directory;
    this.filename = path.join(directory, ".writer.json");
    this.identity = processIdentity(io); this.writer = false; this.recovered = false;
    this.descriptor = undefined;
    if (process.platform === "linux") { this.acquireKernelLock(); return; }
    try { this.acquire(); }
    catch (error) {
      if (error.code !== "EEXIST") throw failure("operation_writer_lock_failed");
      const owner = ownerRecord(io, this.filename);
      if (absentInSameScope(owner, this.identity, io)) {
        this.reclaim(owner.token, candidate => absentInSameScope(candidate, this.identity, io));
      }
    }
  }

  acquireKernelLock() {
    this.lockfile = path.join(this.directory, ".writer.lock");
    const fd = this.io.openSync(this.lockfile, this.io.constants.O_CREAT | this.io.constants.O_RDWR | this.io.constants.O_NOFOLLOW, 0o600);
    try {
      if (!this.io.fstatSync(fd).isFile()) throw failure("operation_writer_lock_unreadable");
      // flock applies to the inherited open-file description. The parent's FD
      // keeps it locked after the short helper exits; the kernel releases it on
      // process death, including container recreation. Never unlink this file.
      const result = spawnSync("flock", ["--exclusive", "--nonblock", "3"], {
        stdio: ["ignore", "ignore", "pipe", fd], encoding: "utf8", timeout: 5_000
      });
      if (result.error) throw failure("operation_writer_flock_unavailable");
      if (result.status === 1) { this.io.closeSync(fd); return; }
      if (result.status !== 0) throw failure("operation_writer_flock_failed");
      this.descriptor = fd; this.writer = true;
    } catch (error) { this.io.closeSync(fd); throw error; }
  }

  acquire() {
    exclusive(this.io, this.filename, this.identity);
    syncDirectory(this.io, this.directory);
    this.writer = true;
  }

  assertOwner() {
    if (!this.writer) throw failure("operation_writer_locked");
    if (this.descriptor !== undefined) {
      try {
        const held = this.io.fstatSync(this.descriptor), named = this.io.lstatSync(this.lockfile);
        if (held.dev !== named.dev || held.ino !== named.ino || named.isSymbolicLink()) throw failure("operation_writer_lost");
      } catch {
        try { this.io.closeSync(this.descriptor); } catch { /* A lost descriptor is already unusable. */ }
        this.descriptor = undefined; this.writer = false;
        throw failure("operation_writer_lost");
      }
      return;
    }
    try {
      if (ownerRecord(this.io, this.filename).token !== this.identity.token) throw failure("operation_writer_lost");
    } catch (error) { this.writer = false; throw error; }
  }

  reclaim(expectedToken, verifyAbsent) {
    const owner = ownerRecord(this.io, this.filename);
    if (owner.token !== expectedToken || !verifyAbsent(owner)) throw failure("operation_writer_not_fenced");
    const receipt = path.join(this.directory, `.reclaimed-${owner.token}.json`);
    try { this.io.linkSync(this.filename, receipt); }
    catch (error) { if (error.code === "EEXIST") throw failure("operation_writer_takeover_in_progress"); throw error; }
    syncDirectory(this.io, this.directory);
    // The old process was proved absent. Only the winner of linkSync can now
    // remove that particular owner; receipts are never recycled or expired.
    if (ownerRecord(this.io, receipt).token !== expectedToken || ownerRecord(this.io, this.filename).token !== expectedToken) {
      throw failure("operation_writer_changed");
    }
    this.io.unlinkSync(this.filename); syncDirectory(this.io, this.directory);
    this.acquire(); this.recovered = true;
  }

  close() {
    if (!this.writer) return;
    this.assertOwner();
    if (this.descriptor !== undefined) {
      this.io.closeSync(this.descriptor); this.descriptor = undefined; this.writer = false;
      return;
    }
    this.io.unlinkSync(this.filename); syncDirectory(this.io, this.directory);
    this.writer = false;
  }
}

module.exports = { OperationLedgerOwner, ownerRecord, syncDirectory, exclusive };
