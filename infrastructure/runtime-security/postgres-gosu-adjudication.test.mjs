import assert from 'node:assert/strict';
import { test } from 'node:test';
import { effectiveGosuSummary, GOSU_READER, GOSU_SHA256,
  matchGosuFindings, validateGosuInspection } from './postgres-gosu-adjudication.mjs';

const cves = ['CVE-2026-78667', 'CVE-2026-78669', 'CVE-2026-97031'];
const rootfs = [
  'sha256:3412f6e84bf5df2d548131d5a86650e84d37ed1758ea532826a7a25477fc8945',
  'sha256:5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef',
  'sha256:e9ea9f91c84d0aef4aa8e972f69267500c22cd0348233f2d30882d9c143ca91e',
];
const report = () => ({ Metadata: { ImageConfig: { rootfs: { diff_ids: rootfs }, config: { Labels: {
  'com.otziv.reviewed-component': 'postgres',
  'com.otziv.publication.revision': 'f2239d547142ad4e73f0b7f4b25799f410a240b9',
  'com.otziv.gosu.source': 'https://github.com/tianon/gosu/tree/6456aaa0f3c854d199d0f037f068eb97515b7513',
} } } }, Results: [{ Target: 'usr/local/bin/gosu', Vulnerabilities: cves.map(VulnerabilityID => ({
  VulnerabilityID, PkgName: 'stdlib', InstalledVersion: 'v1.27.1',
  Severity: 'HIGH', FixedVersion: '1.26.9, 1.27.2',
})) }] });

test('gosu scope is bound to exact C23 runtime and all three findings', () => {
  const decisions = matchGosuFindings(report(), { status: 'EXACT_RUNTIME_VERIFIED' });
  assert.deepEqual(decisions.map(item => item.cve), cves);
  const changed = report();
  changed.Results[0].Vulnerabilities.pop();
  assert.throws(() => matchGosuFindings(changed, { status: 'EXACT_RUNTIME_VERIFIED' }), /gosu_findings_changed/);
  changed.Results[0].Vulnerabilities.push({ ...report().Results[0].Vulnerabilities[2] });
  changed.Metadata.ImageConfig.rootfs.diff_ids = [...rootfs.slice(0, 2), 'sha256:other'];
  assert.throws(() => matchGosuFindings(changed, { status: 'EXACT_RUNTIME_VERIFIED' }), /gosu_rootfs_changed/);
});

test('gosu inspection rejects missing symbols or affected packages', () => {
  const output = '/tmp/gosu: go1.27.1\nSYMBOL_COUNT=2933\nAFFECTED_SYMBOL_COUNT=0\n';
  assert.equal(validateGosuInspection(output, GOSU_SHA256), 2933);
  assert.throws(() => validateGosuInspection(output.replace('AFFECTED_SYMBOL_COUNT=0', 'AFFECTED_SYMBOL_COUNT=1'), GOSU_SHA256),
    /gosu_affected_packages_linked/);
  assert.throws(() => validateGosuInspection(output, 'changed'), /gosu_binary_changed/);
});

test('gosu summary subtracts only the three inspected fixed findings', () => {
  const summary = { effectiveBlockingFixedHighOrCritical: 3, result: 'FAIL' };
  const receipt = { schema: 'otziv-postgres-gosu-adjudication-v1',
    status: 'EXACT_BINARY_AFFECTED_CODE_ABSENT', binarySha256: GOSU_SHA256,
    reader: GOSU_READER, inspectedBinaryExecuted: false, symbolCount: 2933,
    affectedSymbolCount: 0, decisions: matchGosuFindings(report(), { status: 'EXACT_RUNTIME_VERIFIED' }) };
  assert.equal(effectiveGosuSummary(summary, receipt).result, 'PASS');
  assert.equal(effectiveGosuSummary({ ...summary, effectiveBlockingFixedHighOrCritical: 4 }, receipt)
    .effectiveBlockingFixedHighOrCritical, 1);
  assert.equal(effectiveGosuSummary(summary, { ...receipt, status: 'REJECTED', decisions: [] }).result, 'FAIL');
});
