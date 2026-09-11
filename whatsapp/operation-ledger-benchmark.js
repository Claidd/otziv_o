"use strict";
// Metadata-only benchmark: no historical record files and no provider calls.
const { performance } = require("node:perf_hooks");
const { OperationLedger } = require("./operation-ledger");
const { OperationLedgerIndex } = require("./operation-ledger-index");
const count = 99_999, samples = 100_000;
const ledger = Object.create(OperationLedger.prototype);
Object.assign(ledger, { index: new OperationLedgerIndex(), maxRecords: 100_000, owner: { writer: true }, closing: false,
  healthy: true, recovery: { required: false }, inFlight: new Set(), now: () => 1_000_000,
  persistenceFailures: 0, readFailures: 0, admissionRejected: 0 });
const started = performance.now();
for (let i = 0; i < count; i++) ledger.index.set(String(i), i % 2 ? "SUCCEEDED" : "UNKNOWN", i);
const restoreMilliseconds = performance.now() - started;
const observationsStarted = performance.now();
let checksum = 0;
for (let i = 0; i < samples; i++) { checksum += ledger.metrics().remaining; if (!ledger.admission().acceptingNew) throw new Error("unexpected rejection"); }
const observationMilliseconds = performance.now() - observationsStarted;
console.log(JSON.stringify({ fixture: "metadata only; zero record files", records: count, samples, restoreMilliseconds,
  observationMilliseconds, meanMicrosecondsPerMetricsAndAdmission: observationMilliseconds * 1_000 / samples,
  checksum, metrics: ledger.metrics(), node: process.version, platform: process.platform }, null, 2));
