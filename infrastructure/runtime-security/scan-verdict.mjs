import assert from 'node:assert/strict';
import { effectiveScanSummary } from './grafana-tempo-adjudication.mjs';
import { effectiveAlloySummary } from './alloy-daemon-adjudication.mjs';
import { effectivePostgresSummary } from './postgres-c15-adjudication.mjs';

// Accept the raw counter summary, never an earlier verdict or pre-subtracted counters.
// Inspection failures and finding counts are separate inputs to the final verdict.
export function combinedScanSummary(summary, { grafana = null, alloy = null, postgres = null } = {}) {
  assert.ok(!Object.hasOwn(summary, 'result') &&
    !Object.keys(summary).some(key => key.startsWith('effective') || key.startsWith('adjudicated')),
  'scan_verdict_requires_raw_summary');
  for (const key of ['high', 'critical', 'blockingFixedHighOrCritical', 'unfixedHighOrCritical'])
    assert.ok(Number.isSafeInteger(summary[key]) && summary[key] >= 0, 'scan_verdict_invalid_counts');
  assert.equal(summary.high + summary.critical,
    summary.blockingFixedHighOrCritical + summary.unfixedHighOrCritical, 'scan_verdict_invalid_counts');

  const positions = new Set();
  for (const [proof, acceptedStatus] of [[grafana, 'EXACT_BINARY_FIXED_CODE_PROVEN'],
    [alloy, 'EXACT_BINARY_AFFECTED_CODE_ABSENT'], [postgres, 'EXACT_RUNTIME_VERIFIED']]) {
    if (!proof) continue;
    assert.ok(['NOT_APPLICABLE', 'REJECTED', acceptedStatus].includes(proof.status), 'scan_verdict_unknown_status');
    assert.ok(Array.isArray(proof.decisions), 'scan_verdict_invalid_decisions');
    if (proof.status !== acceptedStatus) assert.equal(proof.decisions.length, 0, 'scan_verdict_unverified_decisions');
    for (const decision of proof.decisions) {
      assert.ok(Number.isSafeInteger(decision.resultIndex) && decision.resultIndex >= 0 &&
        Number.isSafeInteger(decision.findingIndex) && decision.findingIndex >= 0 &&
        !(decision.fixedHighOrCritical && decision.unfixedHighOrCritical), 'scan_verdict_invalid_decisions');
      const position = `${decision.resultIndex}:${decision.findingIndex}`;
      assert.ok(!positions.has(position), 'scan_verdict_overlapping_decisions');
      positions.add(position);
    }
  }
  const rejected = [grafana, alloy, postgres].some(proof => proof?.status === 'REJECTED');
  const legacy = effectiveAlloySummary(effectiveScanSummary(summary, grafana), alloy);
  if (!postgres || postgres.status === 'NOT_APPLICABLE')
    return rejected ? { ...legacy, result: 'FAIL' } : legacy;

  // The intermediate verdict above is derived only from remaining fixed findings.
  // PostgreSQL can account for those findings after exact runtime verification.
  // Carry counters forward and recompute severity; preserve every inspection failure.
  const { result: intermediateSeverityVerdict, ...counters } = legacy;
  const reviewed = effectivePostgresSummary(counters, postgres);
  return rejected ? { ...reviewed, result: 'FAIL' } : reviewed;
}
