import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

const SCHEMA = 'otziv-keycloak-runtime-dependencies-v1';
const POLICY = 'keycloak-26.7.3-jackson-2.21.6-parsson-1.1.9';
const DIGEST = /^sha256:[a-f0-9]{64}$/;
const jackson = name => /^com\.fasterxml\.jackson[^:]*:/.test(name);
const known = name => jackson(name) || name === 'org.eclipse.parsson:parsson';

export const requiresKeycloakDependencyProof = image => image.component === 'keycloak'
  && image.prepare?.kind === 'keycloak-provider-docker-stage';

function validatePackages(packages) {
  assert.ok(Array.isArray(packages) && packages.length, 'keycloak_known_dependencies_missing');
  for (const item of packages) {
    assert.ok(known(item.name), 'keycloak_unknown_dependency_evidence');
    const [group, artifact] = item.name.split(':');
    const expected = artifact === 'jackson-annotations' ? '2.21'
      : jackson(item.name) ? '2.21.6' : '1.1.9';
    assert.equal(item.version, expected, 'keycloak_unreviewed_runtime_dependency_version');
    assert.equal(item.purl, `pkg:maven/${group}/${artifact}@${item.version}`, 'keycloak_dependency_identity_mismatch');
    assert.match(item.path || '', /^opt\/keycloak\/[^\r\n]+\.jar$/, 'keycloak_dependency_path_missing');
    assert.ok(!item.path.split('/').includes('..'), 'keycloak_dependency_path_invalid');
  }
  for (const name of ['com.fasterxml.jackson.core:jackson-databind', 'org.eclipse.parsson:parsson']) {
    assert.ok(packages.some(item => item.name === name), 'keycloak_required_dependency_missing');
  }
}

export function validateKeycloakDependencyReceipt(receipt, imageId) {
  assert.equal(receipt?.schema, SCHEMA, 'keycloak_dependency_receipt_missing');
  assert.equal(receipt.policy, POLICY, 'keycloak_dependency_policy_mismatch');
  assert.equal(receipt.result, 'PASS', 'keycloak_dependency_policy_not_passed');
  assert.match(imageId || '', DIGEST, 'keycloak_dependency_image_id_missing');
  assert.equal(receipt.imageId, imageId, 'keycloak_dependency_image_mismatch');
  assert.match(receipt.rawScanSha256 || '', /^[a-f0-9]{64}$/, 'keycloak_dependency_scan_hash_missing');
  validatePackages(receipt.packages);
}

// This checks every package reported by the pinned scanner independently of its
// CVE database. It does not count physical copies: Trivy can deduplicate equal
// package/version pairs. The Docker recipe must also prove the actual CLI binary.
export function checkKeycloakRuntimeDependencies(rawBytes, imageId) {
  const report = JSON.parse(Buffer.from(rawBytes).toString('utf8'));
  assert.match(imageId || '', DIGEST, 'keycloak_dependency_image_id_missing');
  assert.equal(report.ArtifactType, 'container_image', 'keycloak_dependency_scan_type');
  assert.equal(report.Metadata?.ImageID, imageId, 'keycloak_dependency_scan_image_mismatch');
  assert.ok(Array.isArray(report.Results) && report.Results.length, 'keycloak_dependency_scan_empty');
  const packages = [];
  for (const result of report.Results) {
    for (const item of result.Packages || []) {
      const purl = item.Identifier?.PURL;
      const recognizedPurl = typeof purl === 'string'
        && /^pkg:maven\/(?:com\.fasterxml\.jackson[^/]*\/|org\.eclipse\.parsson\/parsson@)/.test(purl);
      if (!known(item.Name || '') && !recognizedPurl) continue;
      assert.equal(result.Type, 'jar', 'keycloak_dependency_not_java');
      packages.push({ name: item.Name, version: item.Version, purl, path: item.FilePath });
    }
  }
  validatePackages(packages);
  packages.sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b), 'en'));
  return { schema: SCHEMA, policy: POLICY, result: 'PASS', imageId,
    rawScanSha256: createHash('sha256').update(rawBytes).digest('hex'), packages };
}
