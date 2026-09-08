import { createHash } from 'node:crypto';
import { required, positive } from './config.mjs';

function instant(value, code) {
  const date = Date.parse(value);
  if (!Number.isFinite(date)) throw new Error(code);
  return date;
}
function hash(value) {
  if (!/^[a-f0-9]{64}$/.test(value || '')) throw new Error('artifact_checksum_invalid');
  return value;
}
function verifiedObject(receipt) {
  if (!['remote-verified', 'completed'].includes(receipt?.phase)) throw new Error('remote_verification_missing');
  for (const flag of ['head', 'download', 'sha256', 'clientSideEnvelopeVerified']) {
    if (receipt.verification?.[flag] !== true) throw new Error('remote_verification_missing');
  }
  for (const key of ['bucket', 'objectKey', 'format']) required(receipt[key], `artifact_${key}_missing`);
  positive(receipt.bytes, 'artifact_size_invalid');
  instant(receipt.timestampUtc, 'artifact_verification_time_missing');
  return { bucket: receipt.bucket, objectKey: receipt.objectKey, objectVersionId: receipt.objectVersionId || null,
    sha256: hash(receipt.sha256), bytes: receipt.bytes, format: receipt.format,
    verifiedAt: receipt.timestampUtc };
}

// A receipt is an operator attestation of an externally established write fence.
// This tool checks coverage; it never claims to establish or observe the fence itself.
export function buildManifest(input, now = Date.now()) {
  const fence = input.fence || {};
  for (const key of ['id', 'attestedBy', 'evidenceReference']) required(fence[key], 'write_fence_attestation_missing');
  if (fence.scope !== 'all-mysql-keycloak-and-business-object-writes') throw new Error('write_fence_scope_incomplete');
  const from = instant(fence.startedAt, 'write_fence_time_invalid');
  const to = instant(fence.endedAt, 'write_fence_time_invalid');
  if (to < from || to > now + 30_000) throw new Error('write_fence_time_invalid');
  const pg = input.postgres;
  const mysql = input.mysql;
  const pgObject = verifiedObject(pg);
  const mysqlObject = verifiedObject(mysql);
  if (pg?.schema !== 'otziv-postgres-backup-v1' || pg.format !== 'OTZIVPG1_AES256_GCM' ||
      !pg.objectVersionId || pg.objectVersionId === 'null') throw new Error('postgres_backup_invalid');
  if (!String(mysql?.format).includes('OTZIVDB2')) throw new Error('mysql_encrypted_backup_invalid');
  const pgFrom = instant(pg.captureStartedAt, 'postgres_capture_time_missing');
  const pgTo = instant(pg.captureCompletedAt, 'postgres_capture_time_missing');
  positive(mysql.elapsedMillis, 'mysql_capture_duration_missing');
  const mysqlTo = instant(mysql.timestampUtc, 'mysql_capture_time_missing');
  const mysqlFrom = mysqlTo - mysql.elapsedMillis; // Conservative: includes encryption and remote verification.
  if (pgTo < pgFrom || pgFrom < from || pgTo > to || mysqlFrom < from || mysqlTo > to) throw new Error('snapshots_outside_write_fence');
  required(input.releaseCommit, 'release_commit_missing');
  if (!/^[a-f0-9]{40}$/.test(input.releaseCommit) || pg.releaseCommit !== input.releaseCommit ||
      mysql.sourceCommit !== input.releaseCommit) throw new Error('snapshot_release_mismatch');
  required(pg.keycloakVersion, 'keycloak_version_missing');
  required(pg.postgresImage, 'postgres_image_missing');
  required(input.mysqlSchemaVersion, 'mysql_schema_version_missing');
  required(input.keycloakSchemaVersion, 'keycloak_schema_version_missing');
  required(input.mysqlKeyId, 'mysql_key_id_missing');
  required(pg.keyId, 'postgres_key_id_missing');
  positive(input.rpoSeconds, 'rpo_missing'); positive(input.rtoSeconds, 'rto_missing');
  required(input.owner, 'recovery_owner_missing');
  const components = {};
  for (const name of ['business-objects', 'secrets', 'android-signing', 'integration-state']) {
    const value = input.components?.[name];
    for (const key of ['recoveryReference', 'versionId', 'owner']) required(value?.[key], `component_${name}_missing`);
    if (value.independentCopyVerified !== true) throw new Error('component_independence_unverified');
    if (name !== 'business-objects' && value.encrypted !== true) throw new Error('secret_component_not_encrypted');
    if (name === 'business-objects' && value.versionedInventoryVerified !== true) throw new Error('object_inventory_unverified');
    components[name] = { recoveryReference: value.recoveryReference, versionId: value.versionId,
      sha256: hash(value.sha256), owner: value.owner, independentCopyVerified: true,
      encrypted: value.encrypted === true, versionedInventoryVerified: value.versionedInventoryVerified === true };
  }
  if (!Array.isArray(input.recoverableKeyIds) || ![input.mysqlKeyId, pg.keyId].every(id => input.recoverableKeyIds.includes(id))) {
    throw new Error('backup_decryption_key_recovery_missing');
  }
  if (input.sessionRecoveryPolicy !== 'invalidate-all-sessions-and-rotate-signing-keys-before-public-access') {
    throw new Error('restored_session_invalidation_policy_missing');
  }
  const result = { schema: 'otziv-paired-recovery-manifest-v1', createdAt: new Date(now).toISOString(),
    owner: input.owner, releaseCommit: input.releaseCommit, keycloakVersion: pg.keycloakVersion,
    postgresImage: pg.postgresImage, postgresMajor: pg.postgresMajor,
    mysqlSchemaVersion: input.mysqlSchemaVersion, keycloakSchemaVersion: input.keycloakSchemaVersion,
    recoveryPointAt: fence.startedAt, rpoSeconds: input.rpoSeconds, rtoSeconds: input.rtoSeconds,
    writeFence: { id: fence.id, startedAt: fence.startedAt, endedAt: fence.endedAt,
      scope: fence.scope, attestedBy: fence.attestedBy, evidenceReference: fence.evidenceReference },
    databases: { mysql: { ...mysqlObject, keyId: input.mysqlKeyId }, postgres: { ...pgObject, keyId: pg.keyId } },
    components, recoverableKeyIds: [...new Set(input.recoverableKeyIds)], sessionRecoveryPolicy: input.sessionRecoveryPolicy,
    acceptance: 'ARTIFACT_SET_VALIDATED_FULL_SYSTEM_RESTORE_NOT_YET_PROVEN' };
  return { ...result, contentSha256: createHash('sha256').update(JSON.stringify(result)).digest('hex') };
}

export function verifyManifest(manifest) {
  const { contentSha256, ...content } = manifest;
  if (manifest.schema !== 'otziv-paired-recovery-manifest-v1' ||
      createHash('sha256').update(JSON.stringify(content)).digest('hex') !== contentSha256) throw new Error('manifest_integrity_failed');
  return manifest;
}

export function compareIdentities(mysqlUsers, keycloakUsers) {
  if (!Array.isArray(mysqlUsers) || !Array.isArray(keycloakUsers) || !mysqlUsers.length || !keycloakUsers.length) throw new Error('identity_export_missing');
  const known = new Map();
  for (const value of keycloakUsers) {
    if (typeof value.subject !== 'string' || !value.subject || typeof value.realmId !== 'string' || !Array.isArray(value.roles)) throw new Error('identity_export_invalid');
    const key = `${value.realmId}\0${value.subject}`;
    if (known.has(key)) throw new Error('duplicate_keycloak_identity');
    known.set(key, value);
  }
  const seen = new Set();
  let checked = 0;
  for (const value of mysqlUsers) {
    if (value.subject == null) continue; // Local service accounts may intentionally have no external subject.
    const key = `${value.realmId}\0${value.subject}`;
    if (seen.has(key)) throw new Error('duplicate_local_identity');
    seen.add(key);
    const other = known.get(key);
    if (!other || !Array.isArray(value.roles) || JSON.stringify([...new Set(value.roles)].sort()) !==
        JSON.stringify([...new Set(other.roles)].sort())) throw new Error('identity_or_business_roles_mismatch');
    checked++;
  }
  if (!checked) throw new Error('no_linked_identities_checked');
  return { result: 'PASS', linkedIdentitiesChecked: checked };
}
