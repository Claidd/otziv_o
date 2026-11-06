import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { C50_GO_TARGETS, C50_MUSL, C50_COMPONENTS, validateC50ActivationScan } from './c50-refresh.mjs';
import { summarizeReport, TRIVY_IMAGE } from './scan.mjs';
import { combinedScanSummary } from './scan-verdict.mjs';

function fixture(component) {
  const imageId = 'sha256:' + '1'.repeat(64);
  const raw = { Metadata: { ImageID: imageId, OS: { Family: 'alpine', Name: component === 'certbot' ? '3.23.5' : '3.24.1' } }, Results: [] };
  if (Object.hasOwn(C50_MUSL, component)) raw.Results.push({ Class: 'os-pkgs', Packages:
    ['musl', 'musl-utils'].map(Name => ({ Name, Version: C50_MUSL[component] })) });
  for (const Target of C50_GO_TARGETS[component] || []) raw.Results.push({ Type: 'gobinary', Target,
    Packages: [{ Name: 'golang.org/x/net', Version: 'v0.62.0' }] });
  const receipt = { schema: 'otziv-image-adjudication-v1', imageConfigId: imageId, immutableImageId: imageId,
    rawReportModified: false, status: 'NOT_APPLICABLE', decisions: [], alloy: { status: 'NOT_APPLICABLE', decisions: [] } };
  const publication = { component, imageId, reference: 'ghcr.io/claidd/otziv-security@sha256:' + '2'.repeat(64) };
  let rawBytes;
  const seal = () => {
    rawBytes = Buffer.from(JSON.stringify(raw)); receipt.rawReportSha256 = createHash('sha256').update(rawBytes).digest('hex');
    publication.security = { ...combinedScanSummary(summarizeReport(raw), { grafana: receipt, alloy: receipt.alloy }), scannerImage: TRIVY_IMAGE };
  };
  seal();
  return { raw, receipt, publication, seal, validate: () => validateC50ActivationScan(publication,
    'infrastructure/runtime-security/proofs/c50-' + component + '/publication/publication.json',
    async path => path.endsWith('/vulnerabilities.json') ? rawBytes : Buffer.from(JSON.stringify(receipt))) };
}

for (const component of C50_COMPONENTS) test('C50 checks actual package coverage: ' + component, async () => {
  await fixture(component).validate();
});

for (const component of Object.keys(C50_GO_TARGETS)) test('old x/net is rejected even when the scanner reports no CVE: ' + component, async () => {
  const f = fixture(component); f.raw.Results.find(r => r.Type === 'gobinary').Packages[0].Version = 'v0.60.0'; f.seal();
  await assert.rejects(f.validate(), /c50_xnet_version/);
});

for (const component of Object.keys(C50_MUSL)) test('old musl is rejected even with a successful scan summary: ' + component, async () => {
  const f = fixture(component); f.raw.Results[0].Packages[0].Version = '1.2.6-r2'; f.seal();
  await assert.rejects(f.validate(), /c50_musl_version/);
});

test('both Prometheus executables require the patched dependency', async () => {
  const f = fixture('prometheus'); f.raw.Results.pop(); f.seal();
  await assert.rejects(f.validate(), /c50_binary_coverage:bin\/promtool/);
});
test('new HIGH/CRITICAL findings cannot be erased by recounted publication fields', async () => {
  const f = fixture('nginx'); f.raw.Results[0].Vulnerabilities = [{ VulnerabilityID: 'CVE-2099-1111', Severity: 'HIGH', FixedVersion: '2' }]; f.seal();
  await assert.rejects(f.validate(), /c50_raw_findings/);
});
test('raw report identity must match the published image', async () => {
  const f = fixture('mc'); f.raw.Metadata.ImageID = 'sha256:' + '3'.repeat(64); f.seal();
  await assert.rejects(f.validate(), /c50_scanned_image/);
});
test('unrelated components cannot inherit a Grafana exception', async () => {
  const f = fixture('nginx'); f.receipt.status = 'EXACT_BINARY_FIXED_CODE_PROVEN';
  await assert.rejects(f.validate(), /c50_grafana_scope/);
});
test('all raw report bytes are bound to the scanner receipt', async () => {
  const f = fixture('mc'); f.receipt.rawReportSha256 = '0'.repeat(64);
  await assert.rejects(f.validate(), /c50_scan_hash/);
});
