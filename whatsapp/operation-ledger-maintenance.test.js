"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { OperationLedger, envelopeHash } = require("./operation-ledger");
const { main } = require("./operation-ledger-maintenance");

test("offline recovery command requires persisted fencing and exact independent manifest attestation", async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "otziv-ledger-cli-"));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const envelope = { clientId: "fixture", kind: "send", destination: "fixture", message: "fixture" };
  const manifest = { restoreId: "cli-restore", authority: "independent-history-export", watermark: "all-producers-fenced",
    operations: [{ operationId: "previously-sent", envelopeHash: envelopeHash(envelope), startedAt: 1 }] };
  const filename = path.join(directory, "manifest.fixture"); fs.writeFileSync(filename, JSON.stringify(manifest));
  const common = ["--directory", directory, "--restore-id", manifest.restoreId];
  const digest = await main(["digest", "--manifest", filename, "--restore-id", manifest.restoreId]);
  const recoveryArgs = ["recover", ...common, "--manifest", filename, "--expected-sha256", digest.manifestHash,
    "--authority", manifest.authority, "--watermark", manifest.watermark];
  await assert.rejects(main(recoveryArgs), /confirmation is required/);
  await assert.rejects(main([...recoveryArgs, "--confirm-authoritative-coverage"]), { code: "operation_recovery_not_required" });
  const live = new OperationLedger(directory);
  await assert.rejects(main(["prepare", ...common]), { code: "operation_writer_locked" }); await live.close();
  assert.equal((await main(["prepare", ...common])).state, "REQUIRED");
  const badHash = [...recoveryArgs]; badHash[badHash.indexOf("--expected-sha256") + 1] = "0".repeat(64);
  await assert.rejects(main([...badHash, "--confirm-authoritative-coverage"]), { code: "operation_recovery_coverage_unverified" });
  assert.equal((await main(["status", "--directory", directory])).recoveryRequired, true);
  assert.equal((await main([...recoveryArgs, "--confirm-authoritative-coverage"])).state, "COMPLETE");
  const recovered = new OperationLedger(directory);
  assert.equal((await recovered.execute("previously-sent", envelope, assert.fail)).state, "UNKNOWN"); await recovered.close();
  await assert.rejects(main(["prepare", ...common]), /new unique ID/);
});
