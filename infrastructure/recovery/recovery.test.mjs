import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { mkdtemp, readFile, writeFile, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { encryptArchive, decryptArchive, sha256File, encryptionKey } from './envelope.mjs';
import { BackupStorage } from './storage.mjs';
import { buildManifest, verifyManifest, compareIdentities } from './manifest.mjs';
import { assertLocalDocker, postgresDrill } from './drill.mjs';
import { validateConfig, assertPostgresImage, REVIEWED_POSTGRES_IMAGE, PREVIOUS_REVIEWED_POSTGRES_IMAGE } from './config.mjs';
import { backup } from './backup.mjs';

async function fixture(t) {
  const path = await mkdtemp(join(tmpdir(), 'otziv-recovery-test-'));
  t.after(() => rm(path, { recursive: true, force: true }));
  return path;
}
function pairing() {
  const hash = 'a'.repeat(64), commit = 'b'.repeat(40);
  const object = { phase: 'remote-verified', bucket: 'backup-fixture', objectKey: 'fixture.enc',
    objectVersionId: 'v1', bytes: 100, sha256: hash, timestampUtc: '2026-09-07T00:00:30Z',
    verification: { head: true, download: true, sha256: true, clientSideEnvelopeVerified: true } };
  const component = { recoveryReference: 'vault-fixture-recovery', versionId: 'v1', owner: 'fixture-owner',
    sha256: hash, independentCopyVerified: true, encrypted: true, versionedInventoryVerified: true };
  return { owner: 'fixture-owner', releaseCommit: commit, mysqlSchemaVersion: 'fixture-schema', keycloakSchemaVersion: 'fixture-schema',
    mysqlKeyId: 'mysql-key-v1', recoverableKeyIds: ['mysql-key-v1', 'pg-key-v1'], rpoSeconds: 3600, rtoSeconds: 3600,
    fence: { id: 'fence-fixture', attestedBy: 'fixture-owner', evidenceReference: 'fixture-ticket',
      scope: 'all-mysql-keycloak-and-business-object-writes', startedAt: '2026-09-07T00:00:00Z', endedAt: '2026-09-07T00:01:00Z' },
    mysql: { ...object, format: 'OTZIVDB2_CHUNKED_AES_256_GCM', elapsedMillis: 10000, sourceCommit: commit },
    postgres: { ...object, schema: 'otziv-postgres-backup-v1', format: 'OTZIVPG1_AES256_GCM', releaseCommit: commit,
      keyId: 'pg-key-v1', keycloakVersion: '26.2', postgresMajor: 17, postgresImage: `postgres:17@sha256:${hash}`,
      captureStartedAt: '2026-09-07T00:00:15Z', captureCompletedAt: '2026-09-07T00:00:25Z' },
    components: Object.fromEntries(['business-objects', 'secrets', 'android-signing', 'integration-state'].map(name => [name, { ...component }])),
    sessionRecoveryPolicy: 'invalidate-all-sessions-and-rotate-signing-keys-before-public-access' };
}

test('streamed encrypted archive authenticates, round trips and has no plaintext marker', async t => {
  const path = await fixture(t), archive = join(path, 'archive.enc'), destination = join(path, 'verified.dump');
  const plain = Buffer.concat([Buffer.from('PGDMP sensitive fixture marker '), randomBytes(100000)]), key = randomBytes(32);
  await encryptArchive(Readable.from([plain.subarray(0, 100), plain.subarray(100)]), archive, key);
  assert.equal((await readFile(archive)).includes(Buffer.from('sensitive fixture marker')), false);
  await decryptArchive(archive, key, { destination });
  assert.deepEqual(await readFile(destination), plain);
  assert.equal((await stat(archive)).size, plain.length + 36);
});

test('wrong key, corruption, truncation and extension never leave a plaintext destination', async t => {
  const path = await fixture(t), archive = join(path, 'archive.enc'), key = randomBytes(32);
  await encryptArchive(Readable.from([Buffer.from('PGDMP fixture data')]), archive, key);
  const original = await readFile(archive);
  for (const [i, mutate] of [x => x, x => { x[22] ^= 1; return x; }, x => x.subarray(0, -1), x => Buffer.concat([x, Buffer.from('x')])].entries()) {
    const bad = join(path, `bad-${i}`), destination = join(path, `plain-${i}`);
    await writeFile(bad, mutate(Buffer.from(original)));
    await assert.rejects(decryptArchive(bad, i === 0 ? randomBytes(32) : key, { destination }));
    await assert.rejects(stat(destination), { code: 'ENOENT' });
  }
});

test('size bound and invalid pg_dump fail encryption and remove incomplete object', async t => {
  const path = await fixture(t), key = randomBytes(32);
  for (const [name, bytes, max] of [['oversize', 'PGDMP123456', 5], ['wrong-format', 'not a dump', 100]]) {
    const destination = join(path, name);
    await assert.rejects(encryptArchive(Readable.from([Buffer.from(bytes)]), destination, key, max));
    await assert.rejects(stat(destination), { code: 'ENOENT' });
  }
  assert.throws(() => encryptionKey('not-a-key'));
});

test('exact remote version and independent credentials are mandatory', async () => {
  const calls = [];
  const cfg = { endpoint: 'https://backup.example.org', bucket: 'fixture-backup', region: 'fixture-region', requireServerSideEncryption: true };
  const env = { RECOVERY_S3_ACCESS_KEY: randomBytes(16).toString('hex'), RECOVERY_S3_SECRET_KEY: randomBytes(16).toString('hex'), AWS_PROFILE: 'must-be-removed' };
  const execute = async (command, args, options) => {
    calls.push(args); assert.equal(options.env.AWS_PROFILE, undefined);
    const operation = args[args.indexOf('s3api') + 1];
    return JSON.stringify(operation === 'put-object' ? { VersionId: 'exact-v1' } : operation === 'head-object'
      ? { VersionId: 'exact-v1', ContentLength: 50, Metadata: { sha256: 'hash' }, ServerSideEncryption: 'AES256' }
      : { VersionId: 'exact-v1' });
  };
  const store = new BackupStorage(cfg, env, execute);
  assert.equal(await store.upload('fixture-path', 'key', 'hash'), 'exact-v1');
  await store.verifyHead('key', 'exact-v1', 'hash', 50);
  await store.download('key', 'exact-v1', 'fixture-path');
  for (const args of calls.slice(1)) assert.equal(args[args.indexOf('--version-id') + 1], 'exact-v1');
  assert.throws(() => new BackupStorage(cfg, { ...env, S3_ACCESS_KEY: env.RECOVERY_S3_ACCESS_KEY }));
  assert.throws(() => new BackupStorage({ ...cfg, endpoint: 'http://backup.example.org' }, env));
  await assert.rejects(new BackupStorage(cfg, env, async () => '{}').upload('x', 'x', 'x'), /versioning/);
  await assert.rejects(new BackupStorage(cfg, env, async () => '{}').verifyHead('x', 'x', 'x', 50), /verification/);
});

test('paired manifest binds releases, snapshot fence, every component and key recovery', () => {
  const input = pairing(), manifest = buildManifest(input);
  assert.equal(verifyManifest(manifest), manifest);
  assert.match(manifest.acceptance, /NOT_YET_PROVEN/);
  for (const mutate of [x => { delete x.components.secrets; }, x => { x.postgres.captureStartedAt = '2026-09-06T23:59:59Z'; },
    x => { x.mysql.sourceCommit = 'c'.repeat(40); }, x => { x.recoverableKeyIds = []; },
    x => { x.sessionRecoveryPolicy = 'reuse-snapshot-sessions'; }, x => { x.mysql.verification.download = false; },
    x => { x.rpoSeconds = null; }, x => { x.fence.scope = 'mysql-only'; }]) {
    const bad = structuredClone(input); mutate(bad); assert.throws(() => buildManifest(bad));
  }
  manifest.databases.postgres.sha256 = 'c'.repeat(64);
  assert.throws(() => verifyManifest(manifest), /integrity/);
});

test('identity proof rejects orphan, duplicate, wrong realm and role drift without IDs in errors', () => {
  const local = [{ subject: 'fixture-sub', realmId: 'fixture-realm', roles: ['worker'] }];
  assert.equal(compareIdentities(local, local).linkedIdentitiesChecked, 1);
  for (const remote of [[{ ...local[0], subject: 'different-sub' }], [{ ...local[0], realmId: 'other' }],
    [{ ...local[0], roles: ['admin'] }], [...local, ...local]]) {
    assert.throws(() => compareIdentities(local, remote), error => !error.message.includes('fixture-sub'));
  }
  assert.throws(() => compareIdentities([...local, ...local], local), /duplicate_local/);
});

test('remote Docker and ambiguous context are rejected', async () => {
  await assert.rejects(assertLocalDocker({ DOCKER_HOST: 'ssh://fixture' }, async () => 'unix:///var/run/docker.sock'), /remote_docker/);
  await assert.rejects(assertLocalDocker({}, async () => 'tcp://fixture:2375'), /remote_docker/);
  await assert.rejects(assertLocalDocker({}, async () => 'unix:///one\nunix:///two'), /remote_docker/);
  await assertLocalDocker({}, async () => 'npipe:////./pipe/docker_engine');
});

test('corrupt backup fails before any Docker allocation or invocation', async t => {
  const path = await fixture(t), archive = join(path, 'bad.enc'), key = randomBytes(32);
  await encryptArchive(Readable.from([Buffer.from('PGDMP fixture data')]), archive, key);
  const data = await readFile(archive); data[25] ^= 1; await writeFile(archive, data);
  const input = pairing();
  input.postgres.sha256 = await sha256File(archive); input.postgres.bytes = data.length;
  const manifest = buildManifest(input);
  let called = false;
  await assert.rejects(postgresDrill({ workDirectory: path, maxDumpBytes: 1000, postgresImage: input.postgres.postgresImage,
    postgres: { major: 17 }, keycloakVersion: '26.2', keyId: 'pg-key-v1' }, manifest, archive,
  { RECOVERY_ENCRYPTION_KEY_BASE64: key.toString('base64') }, async () => { called = true; }), /authentication/);
  assert.equal(called, false);
});

test('example configuration fails closed before provider/contact/RPO are selected', async () => {
  const config = JSON.parse(await readFile(new URL('./config.example.json', import.meta.url), 'utf8'));
  assert.throws(() => validateConfig(config), /missing/);
});

test('recovery accepts the activated PostgreSQL runtime, not arbitrary shared-repository images', async () => {
  const activations = JSON.parse(await readFile(new URL('../runtime-security/reviewed-image-activations.json', import.meta.url), 'utf8'));
  const postgres = activations.images.filter(entry => entry.component === 'postgres');
  assert.equal(postgres.length, 1);
  assert.equal(REVIEWED_POSTGRES_IMAGE, postgres[0].reference);
  const example = JSON.parse(await readFile(new URL('./config.example.json', import.meta.url), 'utf8'));
  assert.equal(example.postgresImage, REVIEWED_POSTGRES_IMAGE);
  assertPostgresImage(REVIEWED_POSTGRES_IMAGE);
  assertPostgresImage(PREVIOUS_REVIEWED_POSTGRES_IMAGE);
  assertPostgresImage(`postgres:17@sha256:${'a'.repeat(64)}`);
  for (const value of ['postgres:17', 'ghcr.io/claidd/otziv-security:latest',
    `ghcr.io/claidd/otziv-security@sha256:${'a'.repeat(64)}`,
    REVIEWED_POSTGRES_IMAGE + '\n', `postgres:17 --privileged@sha256:${'a'.repeat(64)}`, null]) {
    assert.throws(() => assertPostgresImage(value), /postgres_image_not_pinned/);
  }
});

test('restore rejects an unreviewed runtime or a different paired manifest before I/O', async () => {
  const input = pairing(), manifest = buildManifest(input);
  let calls = 0;
  const execute = async () => { calls++; };
  const config = { postgresImage: REVIEWED_POSTGRES_IMAGE, postgres: { major: 17 }, keycloakVersion: '26.2', keyId: 'pg-key-v1' };
  await assert.rejects(postgresDrill(config, manifest, 'nonexistent-archive', {}, execute), /restore_version_or_key_identity_mismatch/);
  await assert.rejects(postgresDrill({ ...config, postgresImage: `ghcr.io/claidd/otziv-security@sha256:${'a'.repeat(64)}` },
    manifest, 'nonexistent-archive', {}, execute), /postgres_image_not_pinned/);
  assert.equal(calls, 0);
});

test('capture pipeline authenticates remote bytes, writes receipts and removes every local dump', async t => {
  const path = await fixture(t), pgpass = join(path, 'pgpass'), cert = join(path, 'ca.crt');
  await writeFile(pgpass, 'fixture-pgpass', { mode: 0o600 }); await writeFile(cert, 'fixture-ca', { mode: 0o600 });
  const key = randomBytes(32);
  const config = { schema: 'otziv-recovery-config-v1', owner: 'fixture-owner', keyId: 'pg-key-v1', releaseCommit: 'a'.repeat(40),
    keycloakVersion: '26.2', postgresImage: REVIEWED_POSTGRES_IMAGE, workDirectory: path,
    primaryStorageBucket: 'fixture-primary', rpoSeconds: 3600, rtoSeconds: 3600, maxDumpBytes: 1000, dumpTimeoutSeconds: 60,
    postgres: { host: 'fixture-db.example.org', port: 5432, user: 'fixture', database: 'keycloak', major: 17,
      transport: 'tls-verify-full', sslRootCert: cert },
    storage: { endpoint: 'https://backup.example.org', bucket: 'fixture-backup', region: 'fixture-region', prefix: 'fixture',
      independentConfirmed: true, privateConfirmed: true, versioningConfirmed: true, requireServerSideEncryption: true } };
  const env = { RECOVERY_PGPASSFILE: pgpass, RECOVERY_ENCRYPTION_KEY_BASE64: key.toString('base64'),
    RECOVERY_S3_ACCESS_KEY: randomBytes(16).toString('hex'), RECOVERY_S3_SECRET_KEY: randomBytes(16).toString('hex'), PGPASSWORD: randomBytes(16).toString('hex') };
  let uploaded, uploadCount = 0, corrupt = false, captureFails = false;
  const storage = {
    async upload(archive) { uploaded = await readFile(archive); uploadCount++; return 'fixture-version'; },
    async verifyHead() {}, async download(objectKey, version, output) {
      const data = Buffer.from(uploaded); if (corrupt) data[25] ^= 1; await writeFile(output, data);
    }
  };
  const execute = async command => command === 'pg_dump' ? 'pg_dump (PostgreSQL) 17.6' : 'aws-cli/2.0.0 fixture';
  const start = (command, args, options) => {
    assert.equal(options.env.PGPASSWORD, undefined); assert.equal(options.env.PGSSLMODE, 'verify-full');
    return { child: { stdin: { end() {} }, stdout: Readable.from([Buffer.from('PGDMP fixture database bytes')]), kill() {} },
      completed: captureFails ? Promise.reject(new Error('fixture-capture-failed')) : Promise.resolve('') };
  };
  const result = await backup(config, env, { storage, execute, start });
  assert.equal(result.phase, 'completed'); assert.equal(result.cleanup, 'PASS'); assert.equal(uploadCount, 1);
  assert.equal((await readdir(path)).filter(name => name.endsWith('.json')).length, 2);
  assert.equal((await readdir(path)).some(name => name.startsWith('otziv-pg-')), false);
  corrupt = true;
  await assert.rejects(backup(config, env, { storage, execute, start }), /remote_checksum/);
  assert.equal((await readdir(path)).filter(name => name.endsWith('.json')).length, 2);
  captureFails = true;
  const before = uploadCount;
  await assert.rejects(backup(config, env, { storage, execute, start }), /capture_failed/);
  assert.equal(uploadCount, before);
  assert.equal((await readdir(path)).some(name => name.startsWith('otziv-pg-')), false);
});

test('isolated restore uses no network, authenticates before restore and removes only owned resources', async t => {
  const path = await fixture(t), archive = join(path, 'archive.enc'), key = randomBytes(32);
  await encryptArchive(Readable.from([Buffer.from('PGDMP fixture database bytes')]), archive, key);
  const input = pairing(); input.postgres.sha256 = await sha256File(archive); input.postgres.bytes = (await stat(archive)).size;
  input.postgres.postgresImage = REVIEWED_POSTGRES_IMAGE;
  const manifest = buildManifest(input), calls = []; let owner;
  const execute = async (command, args, options = {}) => {
    calls.push(args);
    if (args[0] === 'context') return 'unix:///var/run/docker.sock';
    if (args[0] === 'volume' && args[1] === 'create') owner = args[args.indexOf('--label') + 1].split('=')[1];
    if (args.includes('inspect') && args.includes('--format')) return owner;
    if (args[0] === 'run') {
      assert.equal(args.at(-1), REVIEWED_POSTGRES_IMAGE);
      assert.equal(args[args.indexOf('--network') + 1], 'none'); assert.ok(args.includes('--pull=never'));
      assert.equal(args.includes('--publish'), false); assert.equal(args.includes('--privileged'), false);
    }
    if (args.includes('pg_restore')) {
      const chunks = []; for await (const chunk of options.input) chunks.push(chunk);
      assert.equal(Buffer.concat(chunks).toString(), 'PGDMP fixture database bytes');
    }
    return args.includes('psql') ? 't\nt\nt\n' : '';
  };
  const result = await postgresDrill({ workDirectory: path, maxDumpBytes: 1000, postgresImage: input.postgres.postgresImage,
    postgres: { major: 17 }, keycloakVersion: '26.2', keyId: 'pg-key-v1', rtoSeconds: 60 }, manifest, archive,
  { RECOVERY_ENCRYPTION_KEY_BASE64: key.toString('base64') }, execute);
  assert.equal(result.result, 'PASS'); assert.equal(result.fullSystemRestore, 'NOT_PROVEN');
  assert.equal(calls.filter(args => args[1] === 'rm').length, 2);
  assert.equal((await readdir(path)).some(name => name.startsWith('otziv-pg-')), false);
});
