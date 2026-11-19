import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {C50_GO_TARGETS, C50_MUSL} from './c50-refresh.mjs';
import {C55_COMPONENTS, validateC55ActivationScan} from './c55-refresh.mjs';
import {summarizeReport, TRIVY_IMAGE} from './scan.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';

function fixture(component) {
  const imageId = 'sha256:' + '1'.repeat(64);
  const raw = {Metadata: {ImageID: imageId, OS: {Family: 'alpine', Name: '3.24.1'}}, Results: []};
  if (C50_MUSL[component]) raw.Results.push({Class: 'os-pkgs', Packages:
    ['musl', 'musl-utils'].map(Name => ({Name, Version: C50_MUSL[component]}))});
  for (const Target of C50_GO_TARGETS[component]) raw.Results.push({Type: 'gobinary', Target, Packages:
    [{Name: 'stdlib', Version: 'v1.27.4'}, {Name: 'golang.org/x/net', Version: 'v0.62.0'}]});
  const receipt = {schema: 'otziv-image-adjudication-v1', imageConfigId: imageId, immutableImageId: imageId,
    rawReportModified: false, status: 'NOT_APPLICABLE', decisions: [], alloy: {status: 'NOT_APPLICABLE', decisions: []}};
  const publication = {component, imageId, reference: 'ghcr.io/claidd/otziv-security@sha256:' + '2'.repeat(64)};
  let bytes;
  const seal = () => {
    bytes = Buffer.from(JSON.stringify(raw)); receipt.rawReportSha256 = createHash('sha256').update(bytes).digest('hex');
    publication.security = {...combinedScanSummary(summarizeReport(raw), {grafana: receipt, alloy: receipt.alloy}), scannerImage: TRIVY_IMAGE};
  };
  seal();
  return {raw, receipt, seal, validate: () => validateC55ActivationScan(publication,
    'infrastructure/runtime-security/proofs/c55-' + component + '/publication/publication.json',
    async path => path.endsWith('/vulnerabilities.json') ? bytes : Buffer.from(JSON.stringify(receipt)))};
}

for (const component of C55_COMPONENTS) {
  test('C55 requires fixed compiler and retained dependency checks: ' + component, async () => fixture(component).validate());
  test('old compiler fails even without CVE database coverage: ' + component, async () => {
    const f = fixture(component); f.raw.Results.find(r => r.Type === 'gobinary').Packages[0].Version = 'v1.27.3'; f.seal();
    await assert.rejects(f.validate(), /c55_stdlib_version/);
  });
}
test('missing compiler metadata fails closed', async () => {
  const f = fixture('mc'); f.raw.Results.find(r => r.Type === 'gobinary').Packages.shift(); f.seal();
  await assert.rejects(f.validate(), /c55_stdlib_coverage/);
});
test('a secondary executable built with old Go cannot inherit the primary verdict', async () => {
  const f = fixture('mc'); f.raw.Results.push({Type: 'gobinary', Target: 'unexpected/helper', Packages: [{Name: 'stdlib', Version: 'v1.27.3'}]}); f.seal();
  await assert.rejects(f.validate(), /c55_stdlib_version:unexpected\/helper/);
});
test('new raw high severity findings remain blocking', async () => {
  const f = fixture('mc'); f.raw.Results[0].Vulnerabilities = [{VulnerabilityID: 'CVE-2099-1234', Severity: 'HIGH', FixedVersion: '2'}]; f.seal();
  await assert.rejects(f.validate(), /c50_raw_findings/);
});
