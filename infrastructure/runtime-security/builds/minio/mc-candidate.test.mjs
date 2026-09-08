import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../../../', import.meta.url));
const proof = resolve(root, 'infrastructure/runtime-security/proofs/c14-minio-mc');
const bytes = path => readFile(path);
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const hash = buffer => createHash('sha256').update(buffer).digest('hex');
const review = await json(resolve(proof, 'review.json'));

test('mc frozen source and retained proof files have exact hashes', async () => {
  assert.equal(review.schema, 'otziv-c14-mc-review-v1');
  for (const [path, expected] of Object.entries(review.sourceFiles)) assert.equal(hash(await bytes(resolve(root, path))), expected, path);
  for (const [path, expected] of Object.entries(review.proofFiles)) assert.equal(hash(await bytes(resolve(proof, path))), expected, path);
});
test('raw actual image scan has no HIGH/CRITICAL and retains lower findings', async () => {
  const raw = await json(resolve(proof, 'raw.json'));
  assert.equal(hash(await bytes(resolve(proof, 'raw.json'))), review.scan.rawSha256);
  assert.equal(raw.Metadata.ImageID, review.image.configDigest);
  const findings = raw.Results.flatMap(r => r.Vulnerabilities ?? []);
  assert.equal(findings.length, review.scan.totalVulnerabilities);
  assert.equal(findings.filter(v => ['HIGH', 'CRITICAL'].includes(v.Severity)).length, 0);
  assert.ok(findings.length > 0, 'lower-severity records remain visible');
});
test('real RPM inventory remains while the unnecessary curl/package-manager closure is absent', async () => {
  const raw = await json(resolve(proof, 'raw.json'));
  const rpm = raw.Results.find(r => r.Class === 'os-pkgs');
  assert.ok(rpm?.Packages.length > 50);
  const names = new Set(rpm.Packages.map(p => p.Name));
  for (const name of ['bash', 'glibc', 'ca-certificates']) assert.ok(names.has(name), name);
  for (const name of ['curl-minimal', 'libcurl-minimal', 'microdnf', 'libdnf', 'librepo', 'rpm', 'rpm-libs', 'libmodulemd', 'libsolv']) assert.ok(!names.has(name), name);
});
test('actual Go binary retains its source identity and fixed toolchain', async () => {
  const raw = await json(resolve(proof, 'raw.json'));
  const go = raw.Results.find(r => r.Type === 'gobinary');
  assert.ok(go.Packages.some(p => p.Name === 'stdlib' && p.Version === 'v1.27.1'));
  const info = await readFile(resolve(proof, 'buildinfo.txt'), 'utf8');
  assert.ok(info.includes('vcs.revision=' + review.source.commit));
  assert.ok(info.includes('vcs.modified=true'));
  assert.ok(info.includes('go1.27.1'));
  assert.equal((await readFile(resolve(proof, 'binary.sha256'), 'utf8')).split(/\s+/)[0], review.image.binarySha256);
});
test('actual CLI proof binds this immutable image and verifies cleanup/version/public-private behavior', async () => {
  const runtime = await json(resolve(proof, 'runtime.json'));
  assert.equal(runtime.result, 'PASS');
  assert.equal(runtime.image, review.image.localManifestId);
  assert.equal(runtime.syntheticOnly, true);
  assert.ok(runtime.cleanup.every(r => r.result === 'PASS'));
  for (const name of ['compose_init_idempotent_0', 'compose_init_idempotent_1', 'public_anonymous_http_bytes', 'private_anonymous_http_403', 'two_distinct_object_versions', 'each_version_readable', 'server_graceful_stop']) assert.ok(runtime.checks.includes(name), name);
});
test('publication payload binds the exact recipe and does not alter baseline source identity', async () => {
  const payload = await json(resolve(proof, 'publication-payload.json'));
  assert.equal(payload.publicationSet, 'c14-mc');
  assert.equal(payload.images.length, 1);
  const image = payload.images[0];
  assert.equal(image.component, 'mc');
  assert.equal(hash(await bytes(resolve(root, image.dockerfile))), image.dockerfileSha256);
  assert.equal(image.sourceBeforeRef, 'minio/mc@sha256:aead63c77f9db9107f1696fb08ecb0faeda23729cde94b0f663edf4fe09728e3');
  assert.deepEqual(image.buildArgs, {});
});
