import assert from 'node:assert/strict';
import { test } from 'node:test';
import { adjudicateAlloyC43Image, C43_ALLOY_PROOF_LABEL,
  C43_ALLOY_BUILD_INFO_READER } from './alloy-c43-adjudication.mjs';

const imageId = 'sha256:' + 'a'.repeat(64);
function report(labels) {
  return { Metadata: { ImageID: imageId, ImageConfig: { config: { Labels: labels } } },
    Results: [{ Target: 'usr/bin/alloy', Type: 'gobinary', Vulnerabilities: [
      { VulnerabilityID: 'CVE-2026-41567', PkgName: 'github.com/docker/docker',
        InstalledVersion: 'v28.5.2+incompatible', Severity: 'HIGH', FixedVersion: 'v28.5.3' }] }] };
}

test('C43 Alloy proof is a distinct Go 1.27.3 generation', () => {
  assert.equal(C43_ALLOY_PROOF_LABEL, 'alloy-daemon-c43');
  assert.match(C43_ALLOY_BUILD_INFO_READER, /^golang:1\.27\.3-bookworm@sha256:[a-f0-9]{64}$/);
});

test('C29 image cannot borrow C43 proof', async () => {
  const value = report({ 'com.otziv.security.proof': 'alloy-daemon-c29' });
  const receipt = await adjudicateAlloyC43Image(value, Buffer.from('{}'), imageId, 'unused');
  assert.equal(receipt.status, 'NOT_APPLICABLE');
  assert.deepEqual(receipt.decisions, []);
});

test('C43 proof rejects changed upstream identity before any Docker inspection', async () => {
  const value = report({ 'com.otziv.security.proof': C43_ALLOY_PROOF_LABEL,
    'org.opencontainers.image.source': 'https://github.com/grafana/alloy',
    'org.opencontainers.image.revision': 'becfd489a7bb459c0496893b555fb87a003296b1',
    'org.opencontainers.image.version': '1.19.2-otziv.2' });
  const receipt = await adjudicateAlloyC43Image(value, Buffer.from('{}'), imageId, 'unused');
  assert.equal(receipt.status, 'REJECTED');
  assert.equal(receipt.reason, 'alloy_c43_version');
  assert.deepEqual(receipt.decisions, []);
});
