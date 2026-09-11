import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { checkKeycloakRuntimeDependencies } from '../../../../../runtime-security/keycloak-runtime-dependencies.mjs';
import { summarizeReport } from '../../../../../runtime-security/scan.mjs';
import { combinedScanSummary } from '../../../../../runtime-security/scan-verdict.mjs';

const local = new URL('./', import.meta.url);
const bytes = path => readFile(new URL(path, local));
const json = async path => JSON.parse(await bytes(path));
const sha = value => createHash('sha256').update(value).digest('hex');
const review = await json('review.json');

test('frozen evidence and exact nine source inputs remain byte-bound', async () => {
  for (const [path, expected] of Object.entries(review.files)) assert.equal(sha(await bytes(path)), expected, path);
  assert.equal(Object.keys(review.buildInputSha256).length, 9);
  for (const [path, expected] of Object.entries(review.buildInputSha256)) assert.equal(sha(await bytes(`../../${path}`)), expected, path);
});

test('fresh raw scan, OCI config and existing dependency policy agree', async () => {
  const raw = await bytes('vulnerabilities.json'), report = JSON.parse(raw);
  const execution = await json('scanner-execution.json');
  assert.equal(sha(raw), execution.rawReportSha256);
  assert.equal(`sha256:${sha(await bytes('target-oci-config.json'))}`, execution.expectedConfig);
  assert.equal(report.Metadata.ImageID, execution.expectedConfig);
  const findings = report.Results.flatMap(row => row.Vulnerabilities || []);
  assert.equal(findings.length, 49);
  assert.equal(report.Results.find(row => row.Type === 'ubuntu').Packages.length, 143);
  assert.equal(report.Results.find(row => row.Type === 'jar').Packages.length, 512);
  const result = combinedScanSummary(summarizeReport(report, true), {});
  assert.equal(result.result, 'PASS'); assert.equal(result.high, 0); assert.equal(result.critical, 0);
  assert.deepEqual(checkKeycloakRuntimeDependencies(raw, execution.expectedConfig), await json('known-runtime-dependencies.json'));
  assert.equal(execution.attempts.length, 1); assert.equal(execution.attempts[0].memory, '512m');
  assert.equal(execution.attempts[0].oomKilled, false); assert.equal(execution.attempts[0].exitCode, 0);
  assert.equal(sha(await bytes('executed-scan-image.py')), execution.scriptSha256);
  const sbom = await json('vulnerabilities.sbom.cdx.json');
  assert.equal(sbom.bomFormat, 'CycloneDX'); assert.ok(sbom.components.length > 0);
});

test('complete rootfs inventory has precisely the reviewed nine differences', async () => {
  const a = await json('base-rootfs-inventory.json'), b = await json('target-rootfs-inventory.json');
  const report = await json('rootfs-comparison.json');
  const excluded = new Set(['etc/hosts', 'etc/hostname', 'etc/resolv.conf']);
  const diff = [...new Set([...Object.keys(a), ...Object.keys(b)])].sort().filter(p => !excluded.has(p) && JSON.stringify(a[p]) !== JSON.stringify(b[p]));
  assert.deepEqual(diff, report.changedFiles.map(r => r.path)); assert.equal(diff.length, 9);
  const expected = [
    'opt/keycloak/lib/lib/main/org.keycloak.keycloak-model-infinispan-26.7.3.jar',
    'opt/keycloak/lib/lib/main/org.keycloak.keycloak-model-storage-private-26.7.3.jar',
    'opt/keycloak/lib/quarkus/generated-bytecode.jar', 'opt/keycloak/lib/quarkus/transformed-bytecode.jar',
    'opt/keycloak/lib/quarkus/quarkus-application.dat', 'opt/keycloak/otziv-realm-migration-provenance.json',
    'opt/keycloak/otziv-realm-migration-proof', 'opt/keycloak/otziv-realm-migration-proof/causal-baseline.txt',
    'opt/keycloak/otziv-realm-migration-proof/causal-patched.txt',
  ].sort();
  assert.deepEqual(diff, expected); assert.deepEqual(report.unexpectedPaths, []);
  assert.deepEqual(report.images.target.rootfsLayers.slice(0, report.images.base.rootfsLayers.length), report.images.base.rootfsLayers);
  assert.deepEqual((await json('target-oci-config.json')).rootfs.diff_ids, report.images.target.rootfsLayers);
  assert.equal(sha(await bytes('executed-export-compare.py')), report.scriptSha256);
});

test('actual image provenance binds exact source families and original dependency bytes', async () => {
  const provenance = await json('otziv-realm-migration-provenance.json');
  const before = await json('base-keycloak-jars.json'), after = await json('target-keycloak-jars.json');
  const rootfs = await json('rootfs-comparison.json');
  assert.equal(sha(await bytes('../../managed-models.patch')), provenance.patchSha256);
  assert.equal(sha(await bytes('../../source-provenance.json')), provenance.sourceProvenanceSha256);
  for (const [path, hash] of Object.entries(provenance.classpathJarSha256)) assert.equal(before[`opt/keycloak/lib/lib/${path}`], hash, path);
  assert.equal(Object.keys(before).length, 476); assert.equal(Object.keys(after).length, 476);
  assert.equal(Object.keys(before).filter(p => before[p] === after[p]).length, 472);
  assert.equal(provenance.modules.length, 2);
  let replaced = 0, unchanged = 0;
  for (const module of provenance.modules) {
    const path = `opt/keycloak/lib/lib/main/org.keycloak.${module.module}-26.7.3.jar`;
    assert.equal(module.originalSha256, before[path]); assert.equal(module.patchedSha256, after[path]);
    assert.deepEqual(module.removedObsoleteSourceClasses, []);
    const actual = rootfs.patchedJarEntryDifferences[path].differences;
    for (const row of module.recompiledEntries) {
      assert.equal(actual.find(r => r.entry === row.entry)?.target, row.compiledSha256);
      assert.equal(actual.find(r => r.entry === row.entry)?.base, row.originalSha256);
    }
    assert.equal(actual.length, module.recompiledEntries.length);
    replaced += actual.length; unchanged += Object.keys(module.unchangedEntrySha256).length;
  }
  assert.equal(replaced, 6); assert.equal(unchanged, 785);
});

test('v2/v3 evidence explicitly rejects whole generated-payload byte parity', async () => {
  const report = await json('v2-v3-selected-payload-comparison.json');
  assert.equal(report.v3Image, review.image); assert.equal(report.allFiveFilesByteEqual, false);
  assert.equal(report.files.length, 5);
  assert.ok(report.files.filter(f => f.path.includes('/lib/main/')).every(f => f.bytesEqual));
  const generated = report.files.find(f => f.path.endsWith('/generated-bytecode.jar'));
  assert.equal(generated.entryDifferences.length, 138);
  assert.equal(report.files.find(f => f.path.endsWith('/transformed-bytecode.jar')).entryDifferences.length, 0);
  assert.equal(report.files.find(f => f.path.endsWith('/quarkus-application.dat')).bytesEqual, true);
  assert.equal(sha(await bytes('executed-compare-v2-v3.py')), report.scriptSha256);
});
