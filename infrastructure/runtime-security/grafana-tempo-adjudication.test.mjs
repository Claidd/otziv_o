import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { loadReviewedProof, matchingFindings, validateObservedBinary, effectiveScanSummary } from './grafana-tempo-adjudication.mjs';
import { summarizeReport } from './scan.mjs';

const { review } = await loadReviewedProof();
const buildInfo = await readFile(new URL('./adjudications/grafana-tempo/reviewed-buildinfo.txt', import.meta.url), 'utf8');
const controls = JSON.parse(await readFile(new URL('./adjudications/grafana-tempo/trivy-version-controls.raw.json', import.meta.url), 'utf8'));
for (const [version, expected] of [
  [review.module.version, ['CVE-2026-21728', 'CVE-2026-28377']],
  ['v2.10.1', ['CVE-2026-21728', 'CVE-2026-28377']], ['v2.10.2', ['CVE-2026-28377']], ['v2.10.3', []]
]) test(`retained actual pinned-Trivy version control ${version}`, () => {
  const actual = controls.Results.flatMap(r => r.Vulnerabilities || []).filter(v => v.InstalledVersion === version).map(v => v.VulnerabilityID).sort();
  assert.deepEqual(actual, expected);
});
function fixture() {
  const labels = Object.fromEntries(Object.entries(review.product).map(([key, value]) => [`org.opencontainers.image.${key}`, value]));
  const report = { ArtifactType: 'container_image', Metadata: { ImageConfig: { config: { Labels: labels }, rootfs: { diff_ids: ['sha256:fixture-layer'] } } },
    Results: [{ Type: 'gobinary', Target: review.binary.path, Vulnerabilities: review.decisions.map(item => ({
      VulnerabilityID: item.cve, PkgName: review.module.name, InstalledVersion: review.module.version, Severity: 'HIGH', FixedVersion: '2.10.3',
      PkgIdentifier: { PURL: `pkg:golang/${review.module.name}@${review.module.version}` }
    })) }] };
  return { report, inspect: { Config: { Labels: structuredClone(labels) }, RootFS: { Layers: ['sha256:fixture-layer'] } } };
}
test('reviewed primary source, original ancestry responses and actual build info validate', () => {
  const { report, inspect } = fixture();
  validateObservedBinary(report, inspect, review.binary.sha256, buildInfo, review);
  assert.equal(review.decisions.length, 2);
});

test('C15 review binds the new executable to the same exact fixed Tempo module', async () => {
  const current = await loadReviewedProof('c15');
  const currentBuild = await readFile(new URL('./adjudications/grafana-tempo-c15/reviewed-buildinfo.txt', import.meta.url), 'utf8');
  const { report, inspect } = fixture();
  assert.deepEqual(current.review.module, review.module);
  assert.deepEqual(current.review.decisions, review.decisions);
  assert.notEqual(current.review.binary.sha256, review.binary.sha256);
  assert.match(currentBuild, /\tdep\tgoogle\.golang\.org\/grpc\tv1\.83\.2\t/);
  validateObservedBinary(report, inspect, current.review.binary.sha256, currentBuild, current.review);
  assert.throws(() => validateObservedBinary(report, inspect, review.binary.sha256, currentBuild, current.review), /binary_mismatch/);
  assert.throws(() => validateObservedBinary(report, inspect, current.review.binary.sha256, buildInfo, current.review), /build_info_mismatch/);
  await assert.rejects(loadReviewedProof('../unreviewed'), /review_unknown/);
});
test('effective summary retains both raw HIGH findings and does not mutate the raw report', () => {
  const { report } = fixture(), before = JSON.stringify(report);
  const decisions = matchingFindings(report, review), result = effectiveScanSummary(summarizeReport(report), { decisions });
  assert.equal(decisions.length, 2); assert.equal(result.high, 2); assert.equal(result.blockingFixedHighOrCritical, 2);
  assert.equal(result.effectiveBlockingFixedHighOrCritical, 0); assert.equal(result.result, 'PASS');
  assert.equal(JSON.stringify(report), before);
});
for (const [name, mutate] of Object.entries({
  'artifact kind': r => r.ArtifactType = 'filesystem',
  'product source': r => r.Metadata.ImageConfig.config.Labels['org.opencontainers.image.source'] += '-other',
  'source revision': r => r.Metadata.ImageConfig.config.Labels['org.opencontainers.image.revision'] = 'another',
  'product release': r => r.Metadata.ImageConfig.config.Labels['org.opencontainers.image.version'] = '13.2.1',
  'package ecosystem': r => r.Results[0].Type = 'jar',
  'executable path': r => r.Results[0].Target = 'some/other/grafana',
  'package name': r => r.Results[0].Vulnerabilities.forEach(v => v.PkgName += '-other'),
  'module version': r => r.Results[0].Vulnerabilities.forEach(v => v.InstalledVersion = 'v2.10.1'),
  'PURL': r => r.Results[0].Vulnerabilities.forEach(v => v.PkgIdentifier.PURL += '-other'),
  'CVE identity': r => r.Results[0].Vulnerabilities.forEach(v => v.VulnerabilityID = 'CVE-2026-00000')
})) test(`changed ${name} cannot remove findings`, () => {
  const { report } = fixture(); mutate(report);
  assert.equal(matchingFindings(report, review).length, 0);
  assert.equal(effectiveScanSummary(summarizeReport(report), null).result, 'FAIL');
});
for (const [name, mutate] of Object.entries({
  'actual image source': f => f.inspect.Config.Labels['org.opencontainers.image.source'] = 'other',
  'actual image revision': f => f.inspect.Config.Labels['org.opencontainers.image.revision'] = 'other',
  'actual rootfs': f => f.inspect.RootFS.Layers.push('another-layer'),
  'missing rootfs': f => delete f.report.Metadata.ImageConfig.rootfs,
  'binary SHA': f => f.sha = '0'.repeat(64),
  'module h1': f => f.info = f.info.replace(review.module.sum, 'h1:wrong'),
  'other dependency in executable': f => f.info += '\tdep\tunreviewed/module\tv1.0.0\th1:changed\n',
  'Go compiler': f => f.info = f.info.replace(': go1.27.1', ': go1.27.0')
})) test(`observed ${name} mismatch fails attestation`, () => {
  const f = { ...fixture(), sha: review.binary.sha256, info: buildInfo }; mutate(f);
  assert.throws(() => validateObservedBinary(f.report, f.inspect, f.sha, f.info, review), /adjudication_/);
});
test('new real critical or vendor-unfixed findings remain present after the two exact decisions', () => {
  const { report } = fixture();
  report.Results.push({ Type: 'alpine', Target: 'OS', Vulnerabilities: [
    { VulnerabilityID: 'CVE-2026-00001', Severity: 'CRITICAL', FixedVersion: '2' },
    { VulnerabilityID: 'CVE-2026-00002', Severity: 'HIGH' }
  ] });
  const result = effectiveScanSummary(summarizeReport(report), { decisions: matchingFindings(report, review) });
  assert.equal(result.result, 'FAIL'); assert.equal(result.critical, 1); assert.equal(result.unfixedHighOrCritical, 1);
  assert.equal(result.effectiveBlockingFixedHighOrCritical, 1);
});
