import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { validateAlloyC36ActivationScan } from './reviewed-image-defaults.mjs';
import { C36_ALLOY_BUILD_INFO_READER, C36_ALLOY_PROOF_LABEL } from './alloy-c36-adjudication.mjs';
import { combinedScanSummary } from './scan-verdict.mjs';
import { summarizeReport, TRIVY_IMAGE } from './scan.mjs';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');

async function fixture() {
  const imageId = 'sha256:' + 'a'.repeat(64);
  const findings = ['CVE-2026-41567', 'CVE-2026-42306'].map(VulnerabilityID => ({
    VulnerabilityID, PkgName: 'github.com/docker/docker', InstalledVersion: 'v28.5.2+incompatible',
    Severity: 'HIGH', FixedVersion: 'v28.5.3' }));
  const raw = { Metadata: { ImageID: imageId, ImageConfig: { config: { Labels: {
    'com.otziv.security.proof': C36_ALLOY_PROOF_LABEL,
    'org.opencontainers.image.source': 'https://github.com/grafana/alloy',
    'org.opencontainers.image.revision': 'becfd489a7bb459c0496893b555fb87a003296b1',
    'org.opencontainers.image.version': '1.19.2-otziv.3',
  } } } }, Results: [{ Type: 'gobinary', Target: 'usr/bin/alloy', Vulnerabilities: findings }] };
  const receipt = { alloy: { schema: 'otziv-alloy-c36-adjudication-v1', status: 'EXACT_BINARY_AFFECTED_CODE_ABSENT',
    imageConfigId: imageId, immutableImageId: imageId, rawReportModified: false,
    inspectedBinaryExecuted: false, buildInfoReader: C36_ALLOY_BUILD_INFO_READER,
    binarySha256: 'b'.repeat(64), functionNamesSha256: 'c'.repeat(64),
    closureSha256: 'd'.repeat(64), buildInfoSha256: 'e'.repeat(64),
    functionCount: 2000, clientFunctions: 20, packageCount: 3000,
    daemonFunctions: 0, daemonPackages: 0,
    decisions: findings.map((finding, findingIndex) => ({ resultIndex: 0, findingIndex,
      cve: finding.VulnerabilityID, target: 'usr/bin/alloy', package: finding.PkgName,
      version: finding.InstalledVersion, state: 'not_affected', justification: 'vulnerable_code_not_present',
      fixedHighOrCritical: true, unfixedHighOrCritical: false })) } };
  const publication = { component: 'alloy', imageId, reference: 'ghcr.io/claidd/otziv-security@' + imageId };
  const seal = () => {
    receipt.alloy.rawReportSha256 = hash(Buffer.from(JSON.stringify(raw)));
    publication.security = { ...combinedScanSummary(summarizeReport(raw), { alloy: receipt.alloy }), scannerImage: TRIVY_IMAGE };
  };
  seal();
  const directory = 'infrastructure/runtime-security/proofs/c36-alloy/publication';
  const publicationPath = directory + '/publication.json';
  const read = async path => {
    if (path === directory + '/vulnerabilities.json') return Buffer.from(JSON.stringify(raw));
    if (path === directory + '/vulnerabilities.adjudications.json') {
      receipt.alloy.rawReportSha256 = hash(Buffer.from(JSON.stringify(raw)));
      return Buffer.from(JSON.stringify(receipt));
    }
    throw new Error('unexpected_fixture_path');
  };
  return { publication, raw, receipt, publicationPath, read };
}

test('C36 Alloy activation checks a separate proof generation and its unchanged decision scope', async () => {
  const v = await fixture();
  await validateAlloyC36ActivationScan(v.publication, v.publicationPath, v.read);
});

test('C36 Alloy activation rejects a borrowed C29 proof label', async () => {
  const v = await fixture();
  v.raw.Metadata.ImageConfig.config.Labels['com.otziv.security.proof'] = 'alloy-daemon-c29';
  await assert.rejects(validateAlloyC36ActivationScan(v.publication, v.publicationPath, v.read),
    /activation_alloy_c36_proof_label/);
});

test('C36 Alloy activation rejects a scan with a missing affected-code decision', async () => {
  const v = await fixture();
  v.receipt.alloy.decisions.pop();
  await assert.rejects(validateAlloyC36ActivationScan(v.publication, v.publicationPath, v.read),
    /activation_alloy_c36_cve_scope/);
});
