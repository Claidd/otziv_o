// Exact Guava-only issuer transition, preserving the accepted C35/C36 pair.
import assert from 'node:assert/strict';
import { gunzipSync } from 'node:zlib';
import { dirname } from 'node:path';
import { hash } from './ssl-refresh.mjs';

export const C35_REFERENCE = 'ghcr.io/claidd/otziv-security@sha256:c28364544835f5fbef6d4e0b0b3e63154127f7fb440b05432760f11235b5f4a9';
export const C35_CONFIG = 'sha256:60a8dd92cf43b8cf909a9e13f231da1f59103f9259cf482185c54856dcfd6a1b';
export const C36_POSTGRES = 'ghcr.io/claidd/otziv-security@sha256:26663e414ba75586834873d6a43d8c1a105c12f58804e7fb961581d61a6dbdd5';
export const C36_POSTGRES_CONFIG = 'sha256:6cca407023398603e7122cb588dbed2078730b34774c9b8366191526a47149fc';
const C23_POSTGRES = 'ghcr.io/claidd/otziv-security@sha256:c974c12a1107e314b1ba8cf65bd1fc5b6964645ced420c2151d336ee8a9a6534';
const C23_POSTGRES_CONFIG = 'sha256:1f5c2590fadde6b449908569285f1ed91a10831d36535d5c4ed647e9a0afca6a';
const ROOT = 'infrastructure/runtime-security/';
const PROOF = ROOT + 'proofs/c43-keycloak/';
const PARENT = ROOT + 'c43-parent-keycloak.json';
const PARENT_SHA256 = 'b908e7aab18044c38902a091a50a291ef3a16d94275984b57b257e48b3667c62';
export const GUAVA_PATH = 'opt/keycloak/lib/lib/main/com.google.guava.guava-33.5.0-jre.jar';
export const GUAVA_SHA256 = 'b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c';
const GENERATED = ['generated-bytecode.jar', 'quarkus-application.dat', 'transformed-bytecode.jar']
  .map(name => 'opt/keycloak/lib/quarkus/' + name);
const sameId = (id, config, reference) => assert.ok([config, reference.split('@')[1]].includes(id), 'guava_c43_image_identity');
const checks = (record, names) => {
  assert.equal(record.result, 'PASS', 'guava_c43_rehearsal_failed');
  assert.ok(record.checks?.length && record.checks.every(check => check.passed === true), 'guava_c43_failed_check');
  for (const name of names) assert.equal(record.checks.filter(check => check.name === name && check.passed).length, 1,
    'guava_c43_missing_check:' + name);
};

export function validateGuavaC43Runtime(runtime, receipt, publication) {
  assert.equal(runtime.schema, 'otziv-keycloak-guava-c43-inventory-v1', 'guava_c43_runtime_schema');
  assert.equal(receipt.schema, 'otziv-keycloak-guava-c43-inspection-v1', 'guava_c43_inspection_schema');
  assert.equal(receipt.result, 'PASS', 'guava_c43_inspection_failed');
  assert.equal(receipt.inspectedContainersExecuted, false, 'guava_c43_inspected_binary_executed');
  const before = runtime.parent, after = runtime.candidate;
  assert.equal(before.reference, C35_REFERENCE, 'guava_c43_parent');
  sameId(before.imageId, C35_CONFIG, C35_REFERENCE);
  assert.equal(after.reference, publication.reference, 'guava_c43_candidate');
  sameId(after.imageId, publication.imageId, publication.reference);
  assert.equal(receipt.parent, before.reference, 'guava_c43_inspection_parent');
  assert.equal(receipt.parentImageId, before.imageId, 'guava_c43_inspection_parent_image');
  assert.equal(receipt.candidate, after.reference, 'guava_c43_inspection_candidate');
  assert.equal(receipt.candidateImageId, after.imageId, 'guava_c43_inspection_candidate_image');
  assert.deepEqual(after.rootfs.slice(0, before.rootfs.length), before.rootfs, 'guava_c43_parent_layers');
  assert.ok(after.rootfs.length > before.rootfs.length, 'guava_c43_overlay_missing');
  const expected = structuredClone(before.configuration);
  expected.Labels['otziv.guava'] = '33.7.2-jre';
  expected.Labels['otziv.guava-advisory'] = 'CVE-2026-102554';
  expected.Labels['com.otziv.publication.revision'] = publication.commit;
  assert.deepEqual(after.configuration, expected, 'guava_c43_unrelated_configuration');
  const changed = [...new Set([...Object.keys(before.inventory), ...Object.keys(after.inventory)])]
    .filter(path => JSON.stringify(before.inventory[path]) !== JSON.stringify(after.inventory[path])).sort();
  assert.ok(changed.includes(GUAVA_PATH) && changed.every(path => [GUAVA_PATH, ...GENERATED].includes(path)),
    'guava_c43_unrelated_payload');
  assert.deepEqual(receipt.changedFiles, changed, 'guava_c43_reported_changes');
  const old = before.inventory[GUAVA_PATH], current = after.inventory[GUAVA_PATH];
  assert.equal(current.sha256, GUAVA_SHA256, 'guava_c43_official_jar');
  assert.notEqual(old.sha256, current.sha256, 'guava_c43_patch_missing');
  for (const key of ['mode', 'uid', 'gid', 'kind']) assert.equal(current[key], old[key], 'guava_c43_jar_metadata');
  assert.deepEqual(receipt.jar, {path: GUAVA_PATH, sha256: GUAVA_SHA256,
    pom: {groupId: 'com.google.guava', artifactId: 'guava', version: '33.7.2-jre'}}, 'guava_c43_jar_identity');
  const provider = 'opt/keycloak/providers/otziv-security-generation.jar';
  assert.deepEqual(after.inventory[provider], before.inventory[provider], 'guava_c43_provider_changed');
  assert.equal(after.inventory[provider].sha256, publication.providerJarSha256, 'guava_c43_provider_identity');
  return changed;
}

export function validateGuavaC43Rehearsal(capture, replay, publication, sourceSha256) {
  for (const record of [capture, replay]) {
    assert.equal(record.schema, 'otziv-guava-c43-rehearsal-v1', 'guava_c43_rehearsal_schema');
    assert.equal(record.sourceScope, 'LOCAL_PROD_LIKE_KEYCLOAK', 'guava_c43_source_scope');
    assert.equal(record.productionWrites, false, 'guava_c43_production_write');
    assert.equal(record.privateRowsPublished, false, 'guava_c43_private_rows');
    assert.equal(record.publishedPorts, 0, 'guava_c43_host_ports');
    assert.equal(record.sourcePostgres, C23_POSTGRES, 'guava_c43_capture_postgres');
    assert.equal(record.sourcePostgresConfig, C23_POSTGRES_CONFIG, 'guava_c43_capture_postgres_config');
    assert.equal(record.replayBaselinePostgres, C36_POSTGRES, 'guava_c43_replay_pair');
    assert.equal(record.keycloak, C35_REFERENCE, 'guava_c43_source_issuer');
    assert.equal(record.keycloakConfig, C35_CONFIG, 'guava_c43_source_issuer_config');
    assert.equal(record.executedScriptSha256, sourceSha256, 'guava_c43_executed_rehearsal');
    checks(record, []);
  }
  checks(capture, ['complete_custom_dump', 'source_runtime_unchanged', 'source_critical_identity_stable_during_capture']);
  assert.equal(replay.dumpSha256, capture.dumpSha256, 'guava_c43_dump_changed');
  assert.equal(replay.candidate, publication.reference, 'guava_c43_rehearsal_candidate');
  sameId(replay.candidateLocalId, publication.imageId, publication.reference);
  assert.equal(replay.candidatePostgres, C36_POSTGRES, 'guava_c43_postgres_changed');
  sameId(replay.candidatePostgresLocalId, C36_POSTGRES_CONFIG, C36_POSTGRES);
  assert.deepEqual(replay.ownedResourcesRemaining, {container: 0, volume: 0, network: 0}, 'guava_c43_resource_leak');
  assert.ok(!replay.cleanupErrors?.length, 'guava_c43_cleanup_error');
  checks(replay, ['restored_critical_records_exact', 'capture_stable_tables_restored_exact', 'source_existing_password_login',
    'source_roles_and_credentials_preserved', 'same_volume_all_tables_exact', 'existing_admin_password_login',
    'existing_roles_and_credentials_preserved', 'unauthenticated_provider_denied', 'library_patch_no_schema_migration',
    'same_volume_rollback_preserves_all_post_upgrade_writes', 'same_volume_old_issuer_login',
    'same_volume_old_issuer_credentials_preserved', 'fresh_rollback_restore_preserves_all_post_upgrade_writes',
    'restored_existing_password_login', 'restored_roles_and_credentials_preserved']);
  assert.equal(replay.tableCount, 102, 'guava_c43_table_coverage');
  for (const key of ['serverVersion', 'encoding', 'locale', 'clusterIdentifier', 'extensions', 'configSha256', 'hbaSha256', 'identSha256'])
    assert.deepEqual(replay.candidateDatabase[key], replay.sourceDatabase[key], 'guava_c43_database_changed:' + key);
}

export async function validateGuavaC43Activation(publication, entry, read, validateParent) {
  assert.equal(entry.manifest?.path, ROOT + 'reviewed-images-c43-keycloak.json', 'guava_c43_manifest');
  assert.deepEqual(entry.migrationAcceptance, entry.guavaRefreshAcceptance, 'guava_c43_migration_binding');
  assert.equal(entry.guavaRefreshAcceptance?.path, PROOF + 'acceptance.json', 'guava_c43_acceptance_path');
  const bytes = await read(entry.guavaRefreshAcceptance.path);
  assert.equal(hash(bytes), entry.guavaRefreshAcceptance.sha256, 'guava_c43_acceptance_digest');
  const value = JSON.parse(bytes);
  assert.equal(value.schema, 'otziv-keycloak-guava-c43-acceptance-v1', 'guava_c43_acceptance_schema');
  assert.equal(value.reference, publication.reference, 'guava_c43_acceptance_candidate');
  assert.equal(value.imageConfigId, publication.imageId, 'guava_c43_acceptance_config');
  const files = new Map();
  for (const [path, digest] of Object.entries(value.files)) {
    assert.ok(path.startsWith(PROOF) && !/[\\:]/.test(path) && path.split('/').every(part => part && part !== '.' && part !== '..'),
      'guava_c43_proof_path');
    const content = await read(path);
    assert.equal(hash(content), digest, 'guava_c43_proof_digest');
    files.set(path.slice(PROOF.length), content);
  }
  const take = name => { assert.ok(files.has(name), 'guava_c43_missing_proof:' + name); return files.get(name); };
  const inspection = JSON.parse(take('inspection/inspection.json'));
  const runtimeBytes = gunzipSync(take('inspection/runtime.json.gz'));
  assert.equal(hash(runtimeBytes), inspection.runtimeSha256, 'guava_c43_runtime_digest');
  const runtime = JSON.parse(runtimeBytes);
  validateGuavaC43Runtime(runtime, inspection, publication);
  assert.equal(inspection.executedScriptSha256, hash(take('executed-inspection.py.txt')), 'guava_c43_executed_inspector');
  assert.equal(inspection.inventoryScriptSha256, hash(take('executed-runtime-inventory.py.txt')), 'guava_c43_executed_inventory');
  validateGuavaC43Rehearsal(JSON.parse(take('rehearsal/capture.json')), JSON.parse(take('rehearsal/replay.json')),
    publication, hash(take('executed-rehearsal.py.txt')));
  const raw = JSON.parse(await read(dirname(entry.publication.path) + '/vulnerabilities.json'));
  assert.equal(raw.Metadata.ImageID, publication.imageId, 'guava_c43_scan_image');
  const guava = raw.Results.flatMap(result => result.Packages || []).filter(pkg => pkg.Name === 'com.google.guava:guava');
  assert.ok(guava.length > 0 && guava.every(pkg => pkg.Version === '33.7.2-jre'), 'guava_c43_runtime_library_scan');
  const parentBytes = await read(PARENT);
  assert.equal(hash(parentBytes), PARENT_SHA256, 'guava_c43_parent_activation_digest');
  const parent = JSON.parse(parentBytes), baseline = await read(ROOT + 'reviewed-images.json');
  assert.equal(parent.reference, C35_REFERENCE, 'guava_c43_parent_activation');
  await validateParent(JSON.parse(baseline).images.find(image => image.component === 'keycloak'), parent, baseline, read);
  const parentPublication = JSON.parse(await read(parent.publication.path));
  assert.equal(parentPublication.providerJarSha256, publication.providerJarSha256, 'guava_c43_provider_rebuilt');
  for (const [which, registryPublication, path] of [
    ['parent', parentPublication, parent.publication.path], ['candidate', publication, entry.publication.path],
  ]) {
    const configBytes = await read(dirname(path) + '/registry-amd64-config.json');
    assert.equal('sha256:' + hash(configBytes), registryPublication.imageId, 'guava_c43_registry_config');
    assert.deepEqual(runtime[which].rootfs, JSON.parse(configBytes).rootfs.diff_ids, 'guava_c43_registry_rootfs');
  }
  return value;
}

export async function couplePostgresC36WithKeycloakC43(readiness, entry, read, validateParent) {
  assert.ok(readiness.postgresC36Acceptance, 'guava_c43_database_acceptance_missing');
  assert.equal(readiness.postgresReference, C36_POSTGRES, 'guava_c43_database_pair');
  assert.deepEqual(readiness.postgresC36Pair, {keycloakReference: C35_REFERENCE, keycloakConfigId: C35_CONFIG},
    'guava_c43_parent_database_pair');
  const publication = JSON.parse(await read(entry.publication.path));
  const accepted = await validateGuavaC43Activation(publication, entry, read, validateParent);
  return {...readiness, requiredKeycloakReference: entry.reference, requiredKeycloakConfigId: accepted.imageConfigId,
    acceptancePath: entry.guavaRefreshAcceptance.path, proofSha256: entry.guavaRefreshAcceptance.sha256};
}
