import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdtemp, writeFile, readFile, rm, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { SYSTEM_COMPONENTS, sealSystemBundle, openSystemBundle } from './system-bundle.mjs';

async function fixture(body) {
  const directory = await mkdtemp(join(tmpdir(), 'otziv-system-bundle-')), key = randomBytes(32);
  try {
    const files = {};
    for (const name of SYSTEM_COMPONENTS) { files[name] = join(directory, name); await writeFile(files[name], 'actual-fixture-' + name); }
    const metadata = { production: false, scope: 'isolated-real-full-system-fixture', captureStartedAt: new Date().toISOString(), captureFinishedAt: new Date().toISOString(),
      images: Object.fromEntries(['mysql', 'postgres', 'keycloak', 'app', 'objects', 'runner'].map(n => [n, 'sha256:' + '1'.repeat(64)])),
      fence: { appStopped: true, keycloakStopped: true, objectsStopped: true, mysqlReadOnly: true, postgresReadOnly: true, writerConnectionsAbsent: true, internalNetwork: true } };
    await body({ directory, key, files, metadata, bundle: join(directory, 'bundle'), target: join(directory, 'decoded') });
  } finally { key.fill(0); await rm(directory, { recursive: true, force: true }); }
}
test('all four actual archives survive one authenticated manifest and exact component decryption', () => fixture(async f => {
  await sealSystemBundle(f.bundle, f.files, f.metadata, f.key);
  const restored = await openSystemBundle(f.bundle, f.target, f.key, f.metadata.images);
  for (const name of SYSTEM_COMPONENTS) assert.deepEqual(await readFile(restored.files[name]), await readFile(f.files[name]));
  assert.equal(restored.metadata.production, false);
}));
test('wrong key or altered manifest fails before allocating any plaintext restore destination', () => fixture(async f => {
  await sealSystemBundle(f.bundle, f.files, f.metadata, f.key);
  await assert.rejects(openSystemBundle(f.bundle, f.target, randomBytes(32), f.metadata.images), /authentication/);
  await assert.rejects(access(f.target));
  const p = join(f.bundle, 'manifest.json'), m = JSON.parse(await readFile(p)); m.content.images.app = 'sha256:' + '2'.repeat(64); await writeFile(p, JSON.stringify(m));
  await assert.rejects(openSystemBundle(f.bundle, f.target, f.key, f.metadata.images), /authentication/);
}));
test('missing or swapped encrypted objects cannot be reported as a successful full-system restore', () => fixture(async f => {
  await sealSystemBundle(f.bundle, f.files, f.metadata, f.key);
  await writeFile(join(f.bundle, 'objects.aesgcm'), await readFile(join(f.bundle, 'secrets.aesgcm')));
  await assert.rejects(openSystemBundle(f.bundle, f.target, f.key, f.metadata.images), /checksum|size/); await assert.rejects(access(f.target));
}));
test('authentic backup for another runtime version is rejected before restore allocation', () => fixture(async f => {
  await sealSystemBundle(f.bundle, f.files, f.metadata, f.key);
  await assert.rejects(openSystemBundle(f.bundle, f.target, f.key, { ...f.metadata.images, keycloak: 'sha256:' + '2'.repeat(64) }), /version_mismatch/);
  await assert.rejects(access(f.target));
}));
test('missing component and corrupted ciphertext fail without leaving partial plaintext', () => fixture(async f => {
  await sealSystemBundle(f.bundle, f.files, f.metadata, f.key);
  const file = join(f.bundle, 'objects.aesgcm'), original = await readFile(file);
  const corrupt = Buffer.from(original); corrupt[15] ^= 1; await writeFile(file, corrupt);
  await assert.rejects(openSystemBundle(f.bundle, f.target, f.key, f.metadata.images), /checksum/); await assert.rejects(access(f.target));
  await rm(file);
  await assert.rejects(openSystemBundle(f.bundle, f.target, f.key, f.metadata.images)); await assert.rejects(access(f.target));
}));
test('an attestation without technical writer and object fences cannot seal a fixture bundle', () => fixture(async f => {
  f.metadata.fence.objectsStopped = false;
  await assert.rejects(sealSystemBundle(f.bundle, f.files, f.metadata, f.key), /evidence_invalid/);
  await assert.rejects(access(f.bundle));
  f.metadata.fence.objectsStopped = 'false';
  await assert.rejects(sealSystemBundle(f.bundle, f.files, f.metadata, f.key), /evidence_invalid/);
  await assert.rejects(access(f.bundle));
}));
