"use strict";

const fs = require("node:fs");
const crypto = require("node:crypto");
const { OperationLedger } = require("./operation-ledger");
const { normalizeManifest } = require("./operation-ledger-recovery");

function argumentsOf(values) {
  const [command, ...rest] = values, options = {};
  for (let i = 0; i < rest.length; i++) {
    const name = rest[i];
    if (name === "--confirm-authoritative-coverage") options[name] = true;
    else if (name.startsWith("--") && rest[i + 1] && !rest[i + 1].startsWith("--")) options[name] = rest[++i];
    else throw new Error("invalid maintenance arguments");
  }
  return { command, options };
}
function required(options, name) {
  if (!options[name]) throw new Error(`required argument: ${name}`);
  return options[name];
}
function readManifest(options) {
  const filename = required(options, "--manifest");
  if (fs.statSync(filename).size > 256 * 1024 * 1024) throw new Error("manifest exceeds 256 MiB");
  return JSON.parse(fs.readFileSync(filename, "utf8"));
}
async function main(values = process.argv.slice(2)) {
  const { command, options } = argumentsOf(values);
  if (command === "digest") {
    const manifest = readManifest(options);
    const canonical = normalizeManifest(manifest, required(options, "--restore-id"), OperationLedger.prototype);
    return { manifestHash: crypto.createHash("sha256").update(JSON.stringify(canonical)).digest("hex"),
      authority: canonical.authority, watermark: canonical.watermark, records: canonical.operations.length };
  }
  if (!["status", "prepare", "recover"].includes(command)) throw new Error("command must be status, prepare, recover or digest");
  const directory = required(options, "--directory");
  if (command === "prepare") required(options, "--restore-id");
  const ledger = new OperationLedger(directory, {
    restoreId: command === "prepare" ? options["--restore-id"] : undefined,
    maxRecords: options["--max-records"] || process.env.WHATSAPP_OPERATION_LEDGER_MAX_RECORDS,
  });
  try {
    if (command === "status") return ledger.metrics();
    ledger.owner.assertOwner(); // Never report an unpersisted follower fence as prepared.
    if (command === "prepare") {
      if (!ledger.recovery.required) throw new Error("restore ID was already completed; every restore needs a new unique ID");
      return { restoreId: ledger.recovery.record.restoreId, state: "REQUIRED", ...ledger.metrics() };
    }
    const manifest = readManifest(options);
    const restoreId = required(options, "--restore-id"), expectedHash = required(options, "--expected-sha256");
    const authority = required(options, "--authority"), watermark = required(options, "--watermark");
    if (!options["--confirm-authoritative-coverage"]) throw new Error("independent authoritative coverage confirmation is required");
    if (manifest.restoreId !== restoreId) throw new Error("restore ID does not match manifest");
    return await ledger.reconcileRecovery(manifest, async ({ manifest: canonical, manifestHash }) => {
      // This is an explicit offline operator attestation, not an automatic claim
      // that the backup/export itself proves completeness. See the runbook.
      if (manifestHash !== expectedHash || canonical.authority !== authority || canonical.watermark !== watermark) return null;
      return { complete: true, restoreId, manifestHash, authority, watermark };
    });
  } finally { await ledger.close(); }
}
if (require.main === module) {
  main().then(result => process.stdout.write(`${JSON.stringify(result)}\n`), error => {
    process.stderr.write(`${error.code || error.message}\n`); process.exitCode = 1;
  });
}
module.exports = { main };
