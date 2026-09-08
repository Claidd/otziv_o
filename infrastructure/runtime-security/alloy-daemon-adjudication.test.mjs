import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { gunzipSync, gzipSync } from 'node:zlib';
import { loadAlloyProof, validateAlloyProof, matchingAlloyFindings, effectiveAlloySummary } from './alloy-daemon-adjudication.mjs';
import { validateObservedBinary } from './go-binary-inspection.mjs';
import { summarizeReport } from './scan.mjs';

const NOW = new Date('2026-09-08T08:00:00Z');
const { review, files } = await loadAlloyProof(NOW);
const raw = gunzipSync(files.get('reviewed-scan.json.gz'));
const report = JSON.parse(raw);
const info = files.get('reviewed-buildinfo.txt').toString('utf8');
const inspected = { Config: { Labels: report.Metadata.ImageConfig.config.Labels }, RootFS: { Layers: report.Metadata.ImageConfig.rootfs.diff_ids } };
const adjudication = decisions => ({ status: 'EXACT_BINARY_AFFECTED_CODE_ABSENT', decisions });
const hash = b => createHash('sha256').update(b).digest('hex');

test('actual reviewed C7 image has two exact daemon-only findings and retains all raw findings', () => {
  assert.equal(hash(raw), '0ed6934afd2fce73be9eff9ecbfd2ee69c92ab4f210b06e88ca71315dbc8beec');
  const before = JSON.stringify(report), decisions = matchingAlloyFindings(report, review);
  assert.deepEqual(decisions.map(item => item.cve).sort(), ['CVE-2026-41567', 'CVE-2026-42306']);
  validateObservedBinary(report, inspected, review.binary.sha256, info, review);
  const result = effectiveAlloySummary(summarizeReport(report), adjudication(decisions));
  assert.equal(result.high, 2); assert.equal(result.unfixedHighOrCritical, 2);
  assert.equal(result.adjudicatedAbsentUnfixedHighOrCritical, 2);
  assert.equal(result.effectiveUnfixedHighOrCritical, 0); assert.equal(result.unresolvedRiskReview, 'NONE');
  assert.equal(result.result, 'PASS'); assert.equal(JSON.stringify(report), before);
});

for (const [name, mutate] of Object.entries({
  'artifact': r => r.ArtifactType = 'filesystem',
  'image config': r => r.Metadata.ImageID = 'sha256:' + 'a'.repeat(64),
  'source': r => r.Metadata.ImageConfig.config.Labels['org.opencontainers.image.source'] += '-other',
  'revision': r => r.Metadata.ImageConfig.config.Labels['org.opencontainers.image.revision'] += '0',
  'release': r => r.Metadata.ImageConfig.config.Labels['org.opencontainers.image.version'] += '-next',
  'target': r => r.Results.forEach(x => { if (x.Type === 'gobinary') x.Target = 'bin/alloy'; }),
  'ecosystem': r => r.Results.forEach(x => { if (x.Type === 'gobinary') x.Type = 'jar'; }),
  'module': r => r.Results.flatMap(x => x.Vulnerabilities || []).forEach(x => x.PkgName += '-other'),
  'version': r => r.Results.flatMap(x => x.Vulnerabilities || []).forEach(x => x.InstalledVersion = 'v28.5.1+incompatible'),
  'PURL encoding': r => r.Results.flatMap(x => x.Vulnerabilities || []).forEach(x => { if (x.PkgIdentifier?.PURL) x.PkgIdentifier.PURL = x.PkgIdentifier.PURL.replace('%2B', '+'); }),
  'advisory': r => r.Results.flatMap(x => x.Vulnerabilities || []).forEach(x => x.VulnerabilityID += '-other')
})) test(`changed ${name} cannot subtract the reviewed findings`, () => {
  const changed = structuredClone(report); mutate(changed);
  const decisions = matchingAlloyFindings(changed, review);
  assert.equal(decisions.length, 0);
  assert.equal(effectiveAlloySummary(summarizeReport(changed), adjudication(decisions)).unresolvedRiskReview, 'REQUIRED');
});

test('a duplicate report or an additional unreviewed executable cannot broaden the decision', () => {
  const changed = structuredClone(report), binary = changed.Results.find(x => x.Target === review.binary.path);
  const duplicate = structuredClone(binary.Vulnerabilities.find(x => x.VulnerabilityID === 'CVE-2026-41567'));
  binary.Vulnerabilities.push(duplicate);
  assert.equal(matchingAlloyFindings(changed, review).length, 0);
  binary.Vulnerabilities.pop();
  changed.Results.push({ ...structuredClone(binary), Target: 'opt/other/alloy' });
  const result = effectiveAlloySummary(summarizeReport(changed), adjudication(matchingAlloyFindings(changed, review)));
  assert.equal(result.adjudicatedAbsentUnfixedHighOrCritical, 2);
  assert.equal(result.effectiveUnfixedHighOrCritical, 2); assert.equal(result.unresolvedRiskReview, 'REQUIRED');
});

test('new fixed critical and vendor-unfixed findings remain blocking or require review', () => {
  const changed = structuredClone(report);
  changed.Results.push({ Type: 'debian', Target: 'OS', Vulnerabilities: [
    { VulnerabilityID: 'CVE-2099-00001', Severity: 'CRITICAL', FixedVersion: '2' },
    { VulnerabilityID: 'CVE-2099-00002', Severity: 'HIGH' }
  ] });
  const result = effectiveAlloySummary(summarizeReport(changed), adjudication(matchingAlloyFindings(changed, review)));
  assert.equal(result.result, 'FAIL'); assert.equal(result.effectiveBlockingFixedHighOrCritical, 1);
  assert.equal(result.effectiveUnfixedHighOrCritical, 1); assert.equal(result.unresolvedRiskReview, 'REQUIRED');
});

for (const name of ['expired', 'unapproved', 'missing primary evidence', 'modified compressed source', 'modified independent review']) test(`${name} rejects the proof`, () => {
  const changedReview = structuredClone(review), changedFiles = new Map(files);
  let now = NOW;
  if (name === 'expired') now = new Date(review.validUntil);
  if (name === 'unapproved') changedReview.status = 'PENDING';
  if (name === 'missing primary evidence') changedFiles.delete('GO-2026-5746.json');
  if (name === 'modified compressed source') changedFiles.set('packages.raw.jsons.gz', Buffer.from('changed'));
  if (name === 'modified independent review') changedFiles.set('independent-review.json', Buffer.from('{}'));
  assert.throws(() => validateAlloyProof(changedReview, changedFiles, now), /alloy_/);
});

test('daemon package in a rehashed graph is rejected rather than trusted from an absence flag', () => {
  const changedReview = structuredClone(review), changedFiles = new Map(files);
  const graph = JSON.parse(gunzipSync(files.get('import-closure.json.gz')));
  const old = graph.packages.find(x => x.startsWith('github.com/docker/docker/client'));
  graph.packages[graph.packages.indexOf(old)] = 'github.com/docker/docker/daemon';
  const graphBytes = Buffer.from(JSON.stringify(graph)), compressed = gzipSync(graphBytes);
  const analysis = JSON.parse(files.get('closure-analysis.json')); analysis.importGraphSha256 = hash(graphBytes);
  for (const [name, bytes] of [['import-closure.json.gz', compressed], ['closure-analysis.json', Buffer.from(JSON.stringify(analysis))]]) {
    changedFiles.set(name, bytes); const entry = changedReview.proofFiles.find(x => x.path === name); entry.sha256 = hash(bytes); entry.bytes = bytes.length;
  }
  assert.throws(() => validateAlloyProof(changedReview, changedFiles, NOW), /alloy_graph_positive_control_missing|alloy_daemon_package_present/);
});

test('rejected or incomplete adjudication keeps findings and fails closed', () => {
  const summary = summarizeReport(report), decisions = matchingAlloyFindings(report, review);
  const result = effectiveAlloySummary(summary, { status: 'REJECTED', decisions });
  assert.equal(result.result, 'FAIL'); assert.equal(result.adjudicatedAbsentUnfixedHighOrCritical, 0);
  assert.equal(result.unresolvedRiskReview, 'REQUIRED');
  assert.throws(() => effectiveAlloySummary(summary, adjudication([...decisions, ...decisions])), /alloy_summary_invalid/);
});

for (const [name, mutate] of Object.entries({
  'binary': f => f.sha = '0'.repeat(64),
  'rootfs': f => f.inspect.RootFS.Layers = [],
  'build info': f => f.info += '\tdep\tchanged/module\tv1.0.0\th1:changed\n',
  'module h1': f => f.info = f.info.replace(review.module.sum, 'h1:other')
})) test(`observed ${name} mismatch rejects the exact-artifact proof`, () => {
  const f = { inspect: structuredClone(inspected), sha: review.binary.sha256, info }; mutate(f);
  assert.throws(() => validateObservedBinary(report, f.inspect, f.sha, f.info, review), /adjudication_/);
});
