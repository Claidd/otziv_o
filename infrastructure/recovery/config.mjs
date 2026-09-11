import { stat, readFile } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { httpsEndpoint } from './storage.mjs';

export function required(value, code) {
  if (typeof value !== 'string' || !value.trim() || /REQUIRED|CHOOSE|CHANGE_ME|\.invalid/i.test(value)) {
    throw new Error(code);
  }
  return value;
}
export function positive(value, code, maximum = Number.MAX_SAFE_INTEGER) {
  if (!Number.isSafeInteger(value) || value < 1 || value > maximum) throw new Error(code);
}
// Keep the recovery runtime paired with the published PostgreSQL activation.
// Other images in the shared GHCR repository are not PostgreSQL runtimes.
export const REVIEWED_POSTGRES_IMAGE = 'ghcr.io/claidd/otziv-security@sha256:07834abbfd80ed7183afa9096db99afc4d93b99fd51aae726e550d07ade65dbf';
export function assertPostgresImage(value) {
  if (typeof value !== 'string' || (!/^postgres(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?@sha256:[a-f0-9]{64}$/.test(value) &&
      value !== REVIEWED_POSTGRES_IMAGE)) throw new Error('postgres_image_not_pinned');
}
export function validateConfig(config) {
  if (config.schema !== 'otziv-recovery-config-v1') throw new Error('config_schema_invalid');
  for (const key of ['owner', 'keyId', 'releaseCommit', 'keycloakVersion', 'postgresImage']) required(config[key], `${key}_missing`);
  required(config.primaryStorageBucket, 'primary_storage_bucket_identity_missing');
  if (!/^[a-f0-9]{40}$/.test(config.releaseCommit)) throw new Error('release_commit_invalid');
  assertPostgresImage(config.postgresImage);
  if (!isAbsolute(config.workDirectory || '')) throw new Error('work_directory_not_absolute');
  positive(config.rpoSeconds, 'rpo_missing');
  positive(config.rtoSeconds, 'rto_missing');
  positive(config.maxDumpBytes, 'dump_limit_missing', 4 * 1024 ** 3 - 36);
  positive(config.dumpTimeoutSeconds, 'dump_timeout_missing', 86_400);
  const pg = config.postgres || {};
  for (const key of ['host', 'user', 'database']) required(pg[key], `postgres_${key}_missing`);
  positive(pg.port, 'postgres_port_invalid', 65535);
  positive(pg.major, 'postgres_major_missing', 99);
  if (pg.transport !== 'unix' && pg.transport !== 'tls-verify-full') throw new Error('postgres_transport_unverified');
  if (pg.transport === 'unix' && (!pg.host.startsWith('/') || !isAbsolute(pg.host))) throw new Error('postgres_socket_invalid');
  if (pg.transport === 'tls-verify-full') required(pg.sslRootCert, 'postgres_ca_missing');
  const storage = config.storage || {};
  for (const key of ['endpoint', 'bucket', 'region', 'prefix']) required(storage[key], `storage_${key}_missing`);
  httpsEndpoint(storage.endpoint);
  if (storage.independentConfirmed !== true || storage.privateConfirmed !== true || storage.versioningConfirmed !== true) {
    throw new Error('storage_independence_privacy_versioning_unconfirmed');
  }
  if (typeof storage.requireServerSideEncryption !== 'boolean') throw new Error('storage_sse_choice_missing');
  if (storage.retention?.enabled) {
    positive(storage.retention.days, 'retention_invalid', 36500);
    if (!['GOVERNANCE', 'COMPLIANCE'].includes(storage.retention.mode)) throw new Error('retention_mode_invalid');
  }
  if (storage.bucket === config.primaryStorageBucket) throw new Error('primary_storage_bucket_reused');
  return config;
}

export async function readConfig(path) { return validateConfig(JSON.parse(await readFile(path, 'utf8'))); }

export async function protectedFile(path) {
  if (!path || !isAbsolute(path)) throw new Error('protected_file_missing');
  const info = await stat(path);
  if (!info.isFile() || (process.platform !== 'win32' && (info.mode & 0o077))) throw new Error('protected_file_permissions_invalid');
}
