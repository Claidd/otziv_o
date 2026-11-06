import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { dirname } from 'node:path';
import { summarizeReport, TRIVY_IMAGE } from './scan.mjs';
import { combinedScanSummary } from './scan-verdict.mjs';

export const C50_GO_TARGETS = Object.freeze({
  prometheus: ['bin/prometheus', 'bin/promtool'], loki: ['usr/bin/loki'], tempo: ['tempo'],
  alloy: ['usr/bin/alloy'], grafana: ['usr/share/grafana/bin/grafana'],
  dozzle: ['dozzle'], mc: ['usr/bin/mc'], minio: ['usr/local/bin/versitygw'],
});
export const C50_MUSL = Object.freeze({
  nginx: '1.2.6-r3', phpmyadmin: '1.2.6-r3', grafana: '1.2.6-r3',
  minio: '1.2.6-r3', mc: '1.2.6-r3', certbot: '1.2.5-r24',
});
export const C50_COMPONENTS = Object.freeze([...new Set([
  ...Object.keys(C50_GO_TARGETS), ...Object.keys(C50_MUSL),
])]);
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

// Apply this only after the existing exact-binary Grafana/Alloy checks. C50
// introduces no new adjudication: every other component must have raw zero HC.
export async function validateC50ActivationScan(publication, publicationPath, read) {
  const component = publication.component;
  assert.ok(C50_COMPONENTS.includes(component), 'c50_component');
  const directory = dirname(publicationPath).replaceAll('\\', '/');
  const [rawBytes, receiptBytes] = await Promise.all([
    read(directory + '/vulnerabilities.json'), read(directory + '/vulnerabilities.adjudications.json'),
  ]);
  const raw = JSON.parse(rawBytes), receipt = JSON.parse(receiptBytes);
  assert.equal(raw.Metadata?.ImageID, publication.imageId, 'c50_scanned_image');
  assert.equal(receipt.rawReportSha256, sha256(rawBytes), 'c50_scan_hash');
  assert.equal(receipt.imageConfigId, publication.imageId, 'c50_receipt_image');
  assert.ok([publication.imageId, publication.reference.split('@')[1]].includes(receipt.immutableImageId),
    'c50_immutable_image');
  assert.equal(receipt.rawReportModified, false, 'c50_raw_modified');
  assert.equal(receipt.schema, 'otziv-image-adjudication-v1', 'c50_receipt_schema');
  assert.ok(!receipt.postgres || receipt.postgres.status === 'NOT_APPLICABLE', 'c50_postgres_scope');
  assert.ok(!receipt.gosu || receipt.gosu.status === 'NOT_APPLICABLE', 'c50_gosu_scope');
  if (component !== 'grafana') {
    assert.equal(receipt.status, 'NOT_APPLICABLE', 'c50_grafana_scope');
    assert.deepEqual(receipt.decisions, [], 'c50_grafana_decisions');
  }
  if (component !== 'alloy') {
    assert.equal(receipt.alloy?.status, 'NOT_APPLICABLE', 'c50_alloy_scope');
    assert.deepEqual(receipt.alloy.decisions, [], 'c50_alloy_decisions');
  }
  const results = raw.Results || [];
  if (Object.hasOwn(C50_MUSL, component)) {
    assert.equal(raw.Metadata.OS.Family, 'alpine', 'c50_distribution');
    assert.ok(raw.Metadata.OS.Name.startsWith(component === 'certbot' ? '3.23.' : '3.24.'), 'c50_alpine_branch');
    const os = results.filter(result => result.Class === 'os-pkgs');
    assert.equal(os.length, 1, 'c50_os_coverage');
    for (const name of ['musl', 'musl-utils']) {
      const packages = (os[0].Packages || []).filter(pkg => pkg.Name === name);
      assert.equal(packages.length, 1, 'c50_musl_coverage');
      assert.equal(packages[0].Version, C50_MUSL[component], 'c50_musl_version');
    }
  }
  for (const target of C50_GO_TARGETS[component] || []) {
    const binaries = results.filter(result => result.Type === 'gobinary' && result.Target === target);
    assert.equal(binaries.length, 1, 'c50_binary_coverage:' + target);
    const modules = (binaries[0].Packages || []).filter(pkg => pkg.Name === 'golang.org/x/net');
    assert.equal(modules.length, 1, 'c50_xnet_coverage:' + target);
    assert.equal(modules[0].Version, 'v0.62.0', 'c50_xnet_version:' + target);
  }
  const actual = summarizeReport(raw);
  if (!['grafana', 'alloy'].includes(component)) assert.equal(actual.high + actual.critical, 0, 'c50_raw_findings');
  const expected = { ...combinedScanSummary(actual, { grafana: receipt, alloy: receipt.alloy }), scannerImage: TRIVY_IMAGE };
  assert.deepEqual(publication.security, expected, 'c50_scan_summary');
  assert.equal(expected.result, 'PASS', 'c50_scan_failed');
  assert.equal(expected.effectiveBlockingFixedHighOrCritical, 0, 'c50_fixed_findings');
  assert.equal(expected.effectiveUnfixedHighOrCritical, 0, 'c50_unfixed_findings');
  assert.equal(expected.unresolvedRiskReview, 'NONE', 'c50_unresolved');
}
