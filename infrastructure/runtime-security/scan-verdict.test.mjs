import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { gunzipSync } from 'node:zlib';
import test from 'node:test';
import { combinedScanSummary } from './scan-verdict.mjs';
import { summarizeReport } from './scan.mjs';
import { effectiveScanSummary } from './grafana-tempo-adjudication.mjs';
import { effectiveAlloySummary } from './alloy-daemon-adjudication.mjs';
import { loadPostgresProof, matchingPostgresFindings } from './postgres-c14-adjudication.mjs';

const report = JSON.parse(gunzipSync(await readFile(new URL('./proofs/c14-postgres/raw/candidate.json.gz', import.meta.url))));
const review = (await loadPostgresProof()).review;
const raw = summarizeReport(report);
const postgres = { status: 'EXACT_RUNTIME_VERIFIED', decisions: matchingPostgresFindings(report, review) };
const absent = { status: 'NOT_APPLICABLE', decisions: [] };
const rejected = { status: 'REJECTED', decisions: [] };

test('real PostgreSQL raw 27 findings reach PASS only after exact reviewed decisions', () => {
  assert.equal(raw.high + raw.critical, 27);
  assert.equal(combinedScanSummary(raw).result, 'FAIL');
  const result = combinedScanSummary(raw, { grafana: absent, alloy: absent, postgres });
  assert.equal(result.result, 'PASS');
  assert.equal(result.high + result.critical, 27);
  assert.equal(result.effectiveBlockingFixedHighOrCritical, 0);
  assert.equal(result.effectiveUnfixedHighOrCritical, 0);
});
test('an unknown fixed or unfixed HIGH finding stays blocking after PostgreSQL review', () => {
  for (const counter of ['blockingFixedHighOrCritical', 'unfixedHighOrCritical']) {
    const result = combinedScanSummary({ ...raw, high: raw.high + 1, [counter]: raw[counter] + 1 }, { postgres });
    assert.equal(result.result, 'FAIL');
    assert.equal(result[counter === 'unfixedHighOrCritical' ? 'effectiveUnfixedHighOrCritical' : 'effectiveBlockingFixedHighOrCritical'], 1);
  }
});
test('every rejected inspection survives a zero-finding or fully reviewed verdict', () => {
  const empty = { ...raw, high: 0, critical: 0, blockingFixedHighOrCritical: 0, unfixedHighOrCritical: 0 };
  for (const component of ['grafana', 'alloy', 'postgres'])
    assert.equal(combinedScanSummary(empty, { [component]: rejected }).result, 'FAIL');
  for (const component of ['grafana', 'alloy'])
    assert.equal(combinedScanSummary(raw, { postgres, [component]: rejected }).result, 'FAIL');
});
test('unreviewed, duplicate and cross-proof overlapping decisions never subtract findings', () => {
  for (const status of ['NOT_APPLICABLE', 'REJECTED', 'UNKNOWN'])
    assert.throws(() => combinedScanSummary(raw, { postgres: { ...postgres, status } }), /scan_verdict_/);
  assert.throws(() => combinedScanSummary(raw, { postgres: { ...postgres, decisions: [...postgres.decisions, postgres.decisions[0]] } }), /overlapping/);
  assert.throws(() => combinedScanSummary(raw, { postgres,
    grafana: { status: 'EXACT_BINARY_FIXED_CODE_PROVEN', decisions: [postgres.decisions[0]] } }), /overlapping/);
});
test('preexisting non-PostgreSQL count shape and vendor-risk policy remain identical', () => {
  for (const summary of [raw, { ...raw, high: 1, critical: 0, blockingFixedHighOrCritical: 0, unfixedHighOrCritical: 1 }])
    assert.deepEqual(combinedScanSummary(summary, { grafana: absent, alloy: absent, postgres: absent }),
      effectiveAlloySummary(effectiveScanSummary(summary, absent), absent));
});
test('pre-subtracted summaries, unrelated verdicts and impossible counters are refused', () => {
  for (const extra of [{ result: 'FAIL' }, { effectiveBlockingFixedHighOrCritical: 0 }, { high: -1 }, { high: 1.5 }])
    assert.throws(() => combinedScanSummary({ ...raw, ...extra }, { postgres }), /scan_verdict_/);
  assert.throws(() => combinedScanSummary({ ...raw, high: raw.high + 1 }, { postgres }), /invalid_counts/);
  assert.throws(() => combinedScanSummary({ ...raw, high: 0, critical: 0, blockingFixedHighOrCritical: 0,
    unfixedHighOrCritical: 0 }, { postgres }), /invalid_counts/);
});
