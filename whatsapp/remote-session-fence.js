"use strict";
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { spawnSync } = require("node:child_process");

const hash = value => crypto.createHash("sha256").update(value).digest("hex");
const safeId = value => typeof value === "string" && /^[A-Za-z0-9._:-]{1,160}$/.test(value);

async function remoteSnapshot(endpoint) {
  const read = async suffix => {
    const response = await fetch(`${endpoint}${suffix}`, { redirect: "error", signal: AbortSignal.timeout(5000) });
    if (response.status !== 200) throw new Error("remote_session_probe_failed");
    let text = "";
    for await (const chunk of response.body) {
      text += Buffer.from(chunk).toString("utf8");
      if (text.length > 262144) throw new Error("remote_session_probe_too_large");
    }
    return JSON.parse(text);
  };
  const version = await read("/json/version");
  const browserId = new URL(version.webSocketDebuggerUrl).pathname.split("/").at(-1);
  const targets = await read("/json/list");
  if (!safeId(browserId) || !Array.isArray(targets) || targets.some(target => !safeId(target.id) || typeof target.type !== "string" || typeof target.url !== "string")) {
    throw new Error("remote_session_probe_invalid");
  }
  // A dedicated profile must have no page or WhatsApp worker before attach or
  // operator reconciliation. Merely losing our local process proves nothing.
  const active = targets.filter(target => target.type === "page" || /(^|\.)whatsapp\.com$/i.test((() => {
    try { return new URL(target.url).hostname; } catch { return ""; }
  })()));
  return { browserId, activeTargetIds: active.map(target => target.id) };
}

function lockFile(filename) {
  if (process.platform !== "linux") throw new Error("remote_session_requires_linux_flock");
  const fd = fs.openSync(filename, fs.constants.O_CREAT | fs.constants.O_RDWR | fs.constants.O_NOFOLLOW, 0o600);
  // flock on the inherited fd locks the shared open-file description. Node
  // retains that descriptor after the helper exits; crash closes it in-kernel.
  const result = spawnSync("flock", ["--exclusive", "--nonblock", "--conflict-exit-code", "73", "3"],
    { stdio: ["ignore", "pipe", "pipe", fd], timeout: 5000 });
  if (result.status !== 0) { fs.closeSync(fd); throw new Error(result.status === 73 ? "remote_session_writer_active" : "remote_session_lock_failed"); }
  return () => fs.closeSync(fd);
}

class RemoteSessionFence {
  constructor({ directory, clientId, profileId = hash(browserUrl), browserUrl, probe = remoteSnapshot, lock = lockFile }) {
    if (!safeId(clientId) || !safeId(String(profileId))) throw new Error("remote_session_identity_invalid");
    this.identity = { clientId, profileId: String(profileId), endpointHash: hash(browserUrl) };
    this.probe = probe;
    this.directory = path.resolve(directory);
    fs.mkdirSync(this.directory, { recursive: true, mode: 0o700 });
    if (fs.lstatSync(this.directory).isSymbolicLink() || !fs.lstatSync(this.directory).isDirectory()) throw new Error("remote_session_directory_invalid");
    // The writer lock belongs to the profile, not a caller-selected client ID.
    const key = hash(String(profileId));
    this.filename = path.join(this.directory, `${key}.json`);
    this.auditFile = path.join(this.directory, `${key}.audit.jsonl`);
    this.release = lock(path.join(this.directory, `${key}.lock`));
  }

  read() {
    if (!fs.existsSync(this.filename)) return null;
    const info = fs.lstatSync(this.filename);
    if (!info.isFile() || info.isSymbolicLink() || info.size > 8192) throw new Error("remote_session_record_invalid");
    const record = JSON.parse(fs.readFileSync(this.filename, "utf8"));
    if (record.schema !== "otziv-remote-session-v1" || !["CLEAN", "DIRTY"].includes(record.state)
      || !safeId(record.generation) || !safeId(record.browserId)
      || Object.entries(this.identity).some(([key, value]) => record[key] !== value)
      || record.targetId !== null && !safeId(record.targetId)) throw new Error("remote_session_record_invalid");
    return record;
  }

  write(record) {
    const temporary = `${this.filename}.${crypto.randomUUID()}.tmp`;
    const fd = fs.openSync(temporary, "wx", 0o600);
    try { fs.writeFileSync(fd, `${JSON.stringify(record)}\n`); fs.fsyncSync(fd); }
    finally { fs.closeSync(fd); }
    fs.renameSync(temporary, this.filename);
    this.syncDirectory();
  }

  syncDirectory() {
    if (process.platform === "win32") return; // Only injectable fixtures run here; production lock is Linux-only.
    const fd = fs.openSync(this.directory, "r"); try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
  }

  async inspect(endpoint) {
    const record = this.read();
    const remote = await this.probe(endpoint);
    return { ...remote, generation: record?.generation || "absent", state: record?.state || "UNINITIALIZED",
      recordedBrowserId: record?.browserId || null, recordedTargetId: record?.targetId || null };
  }

  async reconcile(endpoint, { expectedGeneration, expectedBrowserId, actor, evidenceReference }) {
    if (!safeId(actor) || !safeId(evidenceReference) || !safeId(expectedBrowserId)) throw new Error("remote_session_reconciliation_identity_required");
    const observed = await this.inspect(endpoint);
    if (observed.generation !== expectedGeneration || observed.browserId !== expectedBrowserId) throw new Error("remote_session_reconciliation_conflict");
    if (observed.activeTargetIds.length) throw new Error("remote_session_targets_still_active");
    const generation = crypto.randomUUID();
    const audit = { schema: "otziv-remote-session-audit-v1", ...this.identity, actor, evidenceReference,
      action: observed.state === "UNINITIALIZED" ? "BOOTSTRAP" : "RECONCILE", previousGeneration: observed.generation,
      generation, browserId: observed.browserId, timestamp: new Date().toISOString(), uid: process.getuid?.() ?? null };
    const fd = fs.openSync(this.auditFile, fs.constants.O_CREAT | fs.constants.O_APPEND | fs.constants.O_WRONLY | (fs.constants.O_NOFOLLOW || 0), 0o600);
    try { fs.writeFileSync(fd, `${JSON.stringify(audit)}\n`); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
    this.write({ schema: "otziv-remote-session-v1", ...this.identity, generation, browserId: observed.browserId,
      targetId: null, state: "CLEAN" });
    return { result: "PASS", generation, state: "CLEAN" };
  }

  async begin(endpoint) {
    const previous = this.read();
    if (!previous || previous.state !== "CLEAN") throw new Error(previous ? "remote_session_reconciliation_required" : "remote_session_bootstrap_required");
    const remote = await this.probe(endpoint);
    if (remote.activeTargetIds.length) throw new Error("remote_session_targets_still_active");
    const record = { schema: "otziv-remote-session-v1", ...this.identity, generation: crypto.randomUUID(),
      state: "DIRTY", browserId: remote.browserId, targetId: null };
    this.write(record);
    return record.generation;
  }

  async capture(instance, generation) {
    if (!instance.pupPage || instance.pupPage.isClosed()) return;
    const session = await instance.pupPage.createCDPSession();
    try {
      const { targetInfo } = await session.send("Target.getTargetInfo");
      if (!safeId(targetInfo?.targetId)) throw new Error("remote_session_target_identity_missing");
      const record = this.read();
      if (record?.state !== "DIRTY" || record.generation !== generation) throw new Error("remote_session_generation_changed");
      this.write({ ...record, targetId: targetInfo.targetId });
    } finally { await session.detach(); }
  }

  async complete(endpoint, generation) {
    const record = this.read();
    if (record?.state !== "DIRTY" || record.generation !== generation) throw new Error("remote_session_generation_changed");
    const remote = await this.probe(endpoint);
    if (remote.browserId !== record.browserId || remote.activeTargetIds.length) throw new Error("remote_session_cleanup_unproven");
    this.write({ ...record, state: "CLEAN" });
  }
}

module.exports = { RemoteSessionFence, remoteSnapshot };
