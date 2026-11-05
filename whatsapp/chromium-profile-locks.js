"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const LOCK_NAMES = ["SingletonLock", "SingletonCookie", "SingletonSocket"];

// Chromium's SingletonLock is a symbolic link to "<hostname>-<pid>".
function lockOwner(io, file) {
  try {
    const match = /^(.+)-([0-9]+)$/u.exec(io.readlinkSync(file));
    return match ? { hostname: match[1], pid: Number(match[2]) } : null;
  } catch { return null; }
}

function processAlive(pid) {
  try { process.kill(pid, 0); return true; }
  catch (error) { return error.code === "EPERM"; }
}

/**
 * Removes Chromium profile locks left by a browser that no longer runs, for
 * example after container recreation changed the hostname. Only the exclusive
 * owner of this auth volume (the kernel-locked operation-ledger writer) may
 * remove a lock, and never one whose owner process is alive on this host. A
 * second gateway process on the same volume therefore cannot break the
 * profile of a live browser; Chromium itself then refuses the shared profile.
 */
function removeStaleChromiumLocks(rootPath, { exclusiveOwner = false, hostname = os.hostname(),
  isProcessAlive = processAlive, log = () => {}, io = fs } = {}) {
  const removed = [], kept = [];

  function visit(currentPath) {
    let entries = [];
    try {
      entries = io.readdirSync(currentPath, { withFileTypes: true });
    } catch (error) {
      return;
    }

    const locks = [];
    for (const entry of entries) {
      if (entry.isDirectory()) {
        visit(path.join(currentPath, entry.name));
        continue;
      }
      if (LOCK_NAMES.includes(entry.name)) {
        locks.push(entry.name);
      }
    }
    if (!locks.length) {
      return;
    }

    const owner = locks.includes("SingletonLock") ? lockOwner(io, path.join(currentPath, "SingletonLock")) : null;
    const liveHere = Boolean(owner && owner.hostname === hostname && isProcessAlive(owner.pid));
    if (!exclusiveOwner || liveHere) {
      kept.push(currentPath);
      log("warn", "Kept Chromium profile lock", {
        profile: currentPath,
        reason: exclusiveOwner ? "owner_process_alive" : "not_exclusive_owner",
      });
      return;
    }

    for (const name of locks) {
      const lockFile = path.join(currentPath, name);
      try {
        io.rmSync(lockFile, { force: true });
        removed.push(lockFile);
        log("info", "Removed stale Chromium profile lock", { lockFile });
      } catch (error) {
        log("warn", "Failed to remove stale Chromium profile lock", {
          lockFile,
          error: error.message,
        });
      }
    }
  }

  visit(rootPath);
  return { removed, kept };
}

module.exports = { removeStaleChromiumLocks, lockOwner };
