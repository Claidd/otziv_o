import { randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, open, readFile, realpath, rm, stat } from 'node:fs/promises';
import { dirname, join, relative, isAbsolute } from 'node:path';
import { encryptArchive, decryptArchive, sha256File, encryptionKey } from './envelope.mjs';
import { startProcess, run } from './process.mjs';
import { BackupStorage } from './storage.mjs';
import { validateConfig, protectedFile } from './config.mjs';

export async function writeJsonExclusive(path, value) {
  const handle = await open(path, 'wx', 0o600);
  try { await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`); await handle.sync(); }
  finally { await handle.close(); }
  if (process.platform !== 'win32') {
    const directory = await open(dirname(path), 'r');
    try { await directory.sync(); } finally { await directory.close(); }
  }
}

export async function workspace(directory) {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const root = await realpath(directory);
  if (process.platform !== 'win32' && ((await stat(root)).mode & 0o077)) throw new Error('work_directory_permissions_invalid');
  const path = await mkdtemp(join(root, 'otziv-pg-'));
  return { path, async cleanup() {
    const resolved = await realpath(path);
    const child = relative(root, resolved);
    if (!child || child.startsWith('..') || isAbsolute(child) || resolved !== path) throw new Error('cleanup_path_invalid');
    await rm(path, { recursive: true });
  } };
}

export async function preflight(config, env = process.env, execute = run) {
  validateConfig(config);
  encryptionKey(env.RECOVERY_ENCRYPTION_KEY_BASE64).fill(0);
  new BackupStorage(config.storage, env, execute);
  await protectedFile(env.RECOVERY_PGPASSFILE);
  if (config.postgres.transport === 'tls-verify-full') await protectedFile(config.postgres.sslRootCert);
  const version = await execute(config.postgres.dumpExecutable || 'pg_dump', ['--version']);
  if (!new RegExp(`PostgreSQL\\) ${config.postgres.major}\\.`).test(version)) throw new Error('pg_dump_major_mismatch');
  const awsVersion = await execute(config.storage.awsExecutable || 'aws', ['--version']);
  if (!awsVersion.startsWith('aws-cli/2.')) throw new Error('aws_cli_v2_required');
  return { schema: 'otziv-recovery-preflight-v1', configuration: 'PASS', tools: 'PASS', remoteVerification: 'NOT_RUN' };
}

export async function backup(config, env = process.env, {
  storage = new BackupStorage(config.storage, env), execute = run, start = startProcess
} = {}) {
  await preflight(config, env, execute);
  const key = encryptionKey(env.RECOVERY_ENCRYPTION_KEY_BASE64);
  const local = await workspace(config.workDirectory);
  const captureStartedAt = new Date().toISOString();
  const id = randomUUID();
  const archive = join(local.path, 'postgres.dump.enc');
  let result;
  try {
    const pgEnv = { ...env, PGHOST: config.postgres.host, PGPORT: String(config.postgres.port),
      PGUSER: config.postgres.user, PGDATABASE: config.postgres.database, PGPASSFILE: env.RECOVERY_PGPASSFILE,
      PGCONNECT_TIMEOUT: '15', PGSSLMODE: config.postgres.transport === 'unix' ? 'disable' : 'verify-full' };
    delete pgEnv.PGPASSWORD; delete pgEnv.PGSERVICE; delete pgEnv.PGSERVICEFILE;
    if (config.postgres.sslRootCert) pgEnv.PGSSLROOTCERT = config.postgres.sslRootCert;
    const dump = start(config.postgres.dumpExecutable || 'pg_dump',
      ['--format=custom', '--no-password', '--lock-wait-timeout=30000'],
      { env: pgEnv, timeoutMs: config.dumpTimeoutSeconds * 1000 });
    dump.child.stdin.end();
    const encrypted = encryptArchive(dump.child.stdout, archive, key, config.maxDumpBytes).catch(error => {
      dump.child.kill('SIGKILL'); throw error;
    });
    const outcomes = await Promise.allSettled([encrypted, dump.completed]);
    if (outcomes.some(outcome => outcome.status === 'rejected')) throw new Error('postgres_capture_failed');
    if (dump.stderrBytes > 0) throw new Error('postgres_capture_warnings_require_operator_review');
    const captureCompletedAt = new Date().toISOString();
    await decryptArchive(archive, key, { maxBytes: config.maxDumpBytes });
    const bytes = (await stat(archive)).size;
    const sha256 = await sha256File(archive);
    const objectKey = `${config.storage.prefix.replace(/\/$/, '')}/${id}.pgdump.enc`;
    const objectVersionId = await storage.upload(archive, objectKey, sha256);
    await storage.verifyHead(objectKey, objectVersionId, sha256, bytes);
    const downloaded = join(local.path, 'verified-download.enc');
    await storage.download(objectKey, objectVersionId, downloaded);
    if ((await stat(downloaded)).size !== bytes || await sha256File(downloaded) !== sha256) throw new Error('remote_checksum_mismatch');
    await decryptArchive(downloaded, key, { maxBytes: config.maxDumpBytes });
    result = { schema: 'otziv-postgres-backup-v1', phase: 'remote-verified', id,
      timestampUtc: new Date().toISOString(), captureStartedAt, captureCompletedAt,
      bucket: config.storage.bucket, objectKey, objectVersionId, sha256, bytes, format: 'OTZIVPG1_AES256_GCM',
      keyId: config.keyId, releaseCommit: config.releaseCommit, postgresMajor: config.postgres.major,
      keycloakVersion: config.keycloakVersion, postgresImage: config.postgresImage,
      verification: { head: true, download: true, sha256: true, clientSideEnvelopeVerified: true } };
    await writeJsonExclusive(join(config.workDirectory, `${id}.remote-verified.json`), result);
  } finally {
    key.fill(0);
    await local.cleanup();
  }
  const completed = { ...result, phase: 'completed', cleanup: 'PASS' };
  await writeJsonExclusive(join(config.workDirectory, `${id}.completed.json`), completed);
  return completed;
}

export async function readJson(path) { return JSON.parse(await readFile(path, 'utf8')); }
