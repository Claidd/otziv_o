import test from 'node:test';
import assert from 'node:assert/strict';
import { loadReviewedProof, matchingFindings, effectiveScanSummary, validateObservedBinary } from './grafana-tempo-adjudication.mjs';

test('C50 binds its independently inspected executable to the unchanged reviewed Tempo source', async () => {
  const [current, previous] = await Promise.all([loadReviewedProof('c50'), loadReviewedProof('c43')]);
  assert.equal(current.review.product.version, '12.4.10+otziv.6');
  assert.notEqual(current.review.binary.sha256, previous.review.binary.sha256);
  assert.notEqual(current.review.binary.canonicalBuildInfoSha256, previous.review.binary.canonicalBuildInfoSha256);
  assert.deepEqual(current.review.module, previous.review.module);
  assert.deepEqual(current.review.decisions, previous.review.decisions);
  assert.equal(current.review.binary.goVersion, 'go1.27.3');
});

test('C50 never applies the old binary identity to the new product version', async () => {
  const [{ review }, previous] = await Promise.all([loadReviewedProof('c50'), loadReviewedProof('c43')]);
  const labels = Object.fromEntries(Object.entries(review.product).map(([k,v]) => ['org.opencontainers.image.' + k, v]));
  const layers = ['sha256:' + '1'.repeat(64)];
  const report = { Metadata: { ImageConfig: { config: { Labels: labels }, rootfs: { diff_ids: layers } } } };
  assert.throws(() => validateObservedBinary(report, { Config: { Labels: labels }, RootFS: { Layers: layers } },
    previous.review.binary.sha256, '', review), /adjudication_binary_mismatch/);
});

test('the existing Tempo correction cannot exempt x/net or any additional CVE', async () => {
  const { review } = await loadReviewedProof('c50');
  const labels = Object.fromEntries(Object.entries(review.product).map(([k,v]) => ['org.opencontainers.image.' + k, v]));
  const findings = review.decisions.map(d => ({ VulnerabilityID: d.cve, PkgName: review.module.name,
    InstalledVersion: review.module.version, PkgIdentifier: { PURL: `pkg:golang/${review.module.name}@${review.module.version}` }, Severity: 'HIGH', FixedVersion: '2' }));
  findings.push({ VulnerabilityID: 'CVE-2026-78661', PkgName: 'golang.org/x/net', InstalledVersion: 'v0.60.0', Severity: 'HIGH', FixedVersion: '0.62.0' });
  const report = { ArtifactType: 'container_image', Metadata: { ImageConfig: { config: { Labels: labels } } },
    Results: [{ Type: 'gobinary', Target: review.binary.path, Vulnerabilities: findings }] };
  const decisions = matchingFindings(report, review);
  assert.equal(decisions.length, 2);
  assert.ok(decisions.every(d => d.package === 'github.com/grafana/tempo'));
  const verdict = effectiveScanSummary({ blockingFixedHighOrCritical: 3 }, { decisions });
  assert.equal(verdict.result, 'FAIL');
  assert.equal(verdict.effectiveBlockingFixedHighOrCritical, 1);
});
