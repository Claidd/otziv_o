"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { removeStaleChromiumLocks } = require("./chromium-profile-locks");

// An in-memory profile tree: symbolic links behave like Chromium's lock links.
function profile(lockTarget) {
  const root = path.join(path.sep, "auth"), dir = path.join(root, "session-client");
  const files = new Map([
    [path.join(dir, "SingletonLock"), lockTarget],
    [path.join(dir, "SingletonCookie"), "123"],
    [path.join(dir, "SingletonSocket"), "/tmp/socket"],
  ]);
  const entry = (name, directory) => ({ name, isDirectory: () => directory });
  const io = {
    readdirSync(current) {
      if (current === root) return [entry("session-client", true)];
      if (current === dir) return [...files.keys()].map(file => entry(path.basename(file), false));
      throw Object.assign(new Error("missing"), { code: "ENOENT" });
    },
    readlinkSync(file) { if (!files.has(file)) throw new Error("missing"); return files.get(file); },
    rmSync(file) { files.delete(file); },
  };
  return { root, files, io };
}

test("the exclusive owner removes locks left by a browser in a recreated container", () => {
  const { root, files, io } = profile("old-container-77");
  const result = removeStaleChromiumLocks(root, { exclusiveOwner: true, hostname: "new-container", io,
    isProcessAlive: () => true });
  assert.equal(result.removed.length, 3); assert.equal(files.size, 0);
});

test("a dead browser on this host is stale, a live one is kept", () => {
  const dead = profile("this-host-77");
  assert.equal(removeStaleChromiumLocks(dead.root, { exclusiveOwner: true, hostname: "this-host", io: dead.io,
    isProcessAlive: () => false }).removed.length, 3);
  const live = profile("this-host-77");
  const result = removeStaleChromiumLocks(live.root, { exclusiveOwner: true, hostname: "this-host", io: live.io,
    isProcessAlive: pid => pid === 77 });
  assert.equal(result.removed.length, 0); assert.equal(live.files.size, 3);
});

test("a second process without the auth volume writer lock never removes a live profile lock", () => {
  const { root, files, io } = profile("other-container-77");
  const logs = [];
  const result = removeStaleChromiumLocks(root, { exclusiveOwner: false, hostname: "this-host", io,
    isProcessAlive: () => false, log: (_level, _message, extra) => logs.push(extra.reason) });
  assert.equal(result.removed.length, 0); assert.equal(files.size, 3);
  assert.deepEqual(logs, ["not_exclusive_owner"]);
});
