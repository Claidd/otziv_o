import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../../../', import.meta.url));
const proof = resolve(root, 'infrastructure/runtime-security/proofs/c14-versity');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const review = await json(resolve(proof, 'review.json'));
const before = await json(resolve(proof, 'before/raw.json'));
const after = await json(resolve(proof, 'after/raw.json'));
const findings = report => report.Results.flatMap(r => r.Vulnerabilities ?? []);
const high = report => findings(report).filter(v => ['HIGH', 'CRITICAL'].includes(v.Severity));

test('complete frozen source and proof graph has exact byte hashes', async () => {
  assert.equal(review.schema, 'otziv-c14-local-s3-review-v1');
  assert.equal(review.scope, 'local-test-environment-only');
  assert.ok(Object.keys(review.sourceFiles).length >= 6);
  for (const [path, expected] of Object.entries(review.sourceFiles)) assert.equal(hash(await readFile(resolve(root, path))), expected, path);
  for (const [path, expected] of Object.entries(review.proofFiles)) assert.equal(hash(await readFile(resolve(proof, path))), expected, path);
  async function paths(dir, prefix = '') {
    const result = [];
    for (const item of await readdir(dir, { withFileTypes: true })) {
      const name = prefix + item.name;
      if (item.isDirectory()) result.push(...await paths(resolve(dir, item.name), name + '/'));
      else result.push(name);
    }
    return result;
  }
  assert.deepEqual((await paths(proof)).filter(p => p !== 'review.json').sort(), Object.keys(review.proofFiles).sort());
});
test('actual before/after full scans preserve OS and Go coverage and remove both HIGH records', () => {
  assert.deepEqual(after.Results.map(r => r.Type).sort(), ['alpine', 'gobinary']);
  assert.equal(after.Metadata.ImageID, review.imageConfigDigest);
  assert.equal(high(before).length, 2);
  assert.ok(high(before).every(v => v.VulnerabilityID === 'CVE-2026-14456' && ['libssl3', 'libcrypto3'].includes(v.PkgName)));
  assert.equal(high(after).length, 0);
  assert.equal(findings(after).length, review.scan.totalRetainedFindings);
  assert.equal(review.scan.suppressed, 0);
});
test('actual package graphs change only the two fixed vendor packages; Go graph is unchanged', () => {
  const packages = (r, type) => r.Results.find(x => x.Type === type).Packages.map(p => [p.Name, p.Version]).sort(([a], [b]) => a.localeCompare(b));
  assert.deepEqual(packages(after, 'gobinary'), packages(before, 'gobinary'));
  const old = new Map(packages(before, 'alpine')), current = new Map(packages(after, 'alpine'));
  assert.deepEqual([...current.keys()], [...old.keys()]);
  const changed = [...current].filter(([name, version]) => version !== old.get(name));
  assert.deepEqual(changed, [['libcrypto3', '3.5.8-r0'], ['libssl3', '3.5.8-r0']]);
});
test('direct hashes of original and candidate gateway binaries are identical', async () => {
  for (const path of ['binary.original.sha256', 'binary.candidate.sha256', 'binary.before.sha256']) {
    const value = (await readFile(resolve(proof, path), 'utf8')).trim().split(/\s+/)[0];
    assert.equal(value, review.upstreamBinarySha256);
  }
  assert.equal(review.upstreamBinaryUnchanged, true);
});
test('36 real S3 checks, graceful restart, resource limits and negative OOM state are retained', async () => {
  const runtime = await json(resolve(proof, 'runtime/candidate.json'));
  assert.equal(runtime.result, 'PASS'); assert.equal(runtime.checks.length, 36);
  assert.equal(runtime.publishedServer, review.localImageId);
  assert.equal(runtime.limitsApplied.clientMemoryMiB, 384);
  assert.equal(runtime.limitsApplied.clientGOMEMLIMIT, '256MiB');
  assert.ok(runtime.cleanup.every(row => row.result === 'PASS'));
  for (const name of ['compose_init_idempotent_1', 'public_anonymous_http_bytes', 'private_anonymous_http_403', 'two_distinct_object_versions', 'each_version_readable', 'object_copy_restore_bytes', 'same_version_ids_and_bytes_after_restart', 'server_graceful_stop']) assert.ok(runtime.checks.includes(name), name);
  const negative = await json(resolve(proof, 'runtime/negative-192MiB-process.json'));
  assert.equal(negative.exitCode, 137); assert.equal(negative.oomKilled, true);
});
test('vendor security record and actual altered-lock build establish a bounded package-only correction', async () => {
  const db = await json(resolve(proof, 'primary/alpine-v3.24-main.json'));
  const openssl = db.packages.find(p => p.pkg.name === 'openssl').pkg;
  assert.ok(openssl.secfixes['3.5.8-r0'].includes('CVE-2026-14456'));
  const afterLock = await readFile(resolve(proof, 'apk.after.lock'));
  assert.deepEqual(afterLock, await readFile(resolve(root, 'infrastructure/runtime-security/builds/versity-c14/runtime-apk.lock')));
  assert.notEqual(hash(afterLock), hash(await readFile(resolve(proof, 'negative-runtime-apk.lock'))));
  assert.equal(review.tests.alteredInventoryLockBuildExitCode, 1);
  const diagnostic = await readFile(resolve(proof, 'negative-lock.log'), 'utf8');
  assert.ok(diagnostic.includes('OK: 8200 KiB in 16 packages'));
  assert.ok(diagnostic.includes('did not complete successfully: exit code: 1'));
});
test('named publication explicitly maps legacy MinIO inventory to the stable Versity derivative', async () => {
  const payload = await json(resolve(proof, 'publication-payload.json'));
  assert.equal(payload.publicationSet, 'c14-local-s3'); assert.equal(payload.images.length, 1);
  const image = payload.images[0];
  assert.equal(image.component, 'minio'); assert.equal(image.candidateBaseRef, review.baseRef);
  assert.equal(image.sourceBeforeRef, 'minio/minio@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e');
  assert.equal(image.context, 'infrastructure/runtime-security/builds/versity-c14');
  assert.equal(hash(await readFile(resolve(root, image.dockerfile))), image.dockerfileSha256);
});

test('canonical LF checkout preserves historical proof and reproduces its exact image configuration', async () => {
  for (const path of Object.keys(review.sourceFiles)) assert.ok(!(await readFile(resolve(root, path))).includes(13), path);
  const previous = await readFile(resolve(proof, 'runtime/executed-runner-before-lf.mjs'), 'utf8');
  const current = await readFile(resolve(root, 'infrastructure/runtime-security/builds/versity-c14/run-compatibility.mjs'), 'utf8');
  assert.equal(current, previous.replaceAll('\r\n', '\n'));
  assert.equal(hash(Buffer.from(previous)), review.tests.runtimeRunnerSha256);
  const rebuilt = await json(resolve(proof, 'lf-rebuild.json'));
  assert.equal(rebuilt.result, 'PASS'); assert.equal(rebuilt.buildExitCode, 0);
  assert.equal(rebuilt.imageConfigDigest, review.imageConfigDigest);
  assert.equal(rebuilt.rootFilesystemEqual, true); assert.equal(rebuilt.imageConfigurationFieldsEqual, true);
  assert.equal(rebuilt.binarySha256, review.upstreamBinarySha256);
  assert.equal(rebuilt.runtimeRerun, false); assert.equal(rebuilt.scanRerun, false);
  const build = await readFile(resolve(proof, 'lf-build.log'), 'utf8');
  assert.ok(build.includes('exporting config ' + review.imageConfigDigest));
});
