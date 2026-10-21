import assert from 'node:assert/strict';
import { gunzipSync } from 'node:zlib';
import { dirname } from 'node:path';
import { hash } from './ssl-refresh.mjs';
import { C26_POLICY } from './keycloak-runtime-dependencies.mjs';
import { summarizeReport, TRIVY_IMAGE } from './scan.mjs';
import { combinedScanSummary } from './scan-verdict.mjs';
import { buildTriage } from './triage-report.mjs';

export const C26_REFERENCE = 'ghcr.io/claidd/otziv-security@sha256:76ad15fac3c0041798b6b2b87632bbf91ae7c171ebfa813aa1d1633b48f90ff9';
export const C26_CONFIG = 'sha256:f641317e4919c0959cf51693cf434024094922ec612f5f3bb5a08122f10ab511';
export const C23_POSTGRES = 'ghcr.io/claidd/otziv-security@sha256:c974c12a1107e314b1ba8cf65bd1fc5b6964645ced420c2151d336ee8a9a6534';
export const C23_POSTGRES_CONFIG = 'sha256:1f5c2590fadde6b449908569285f1ed91a10831d36535d5c4ed647e9a0afca6a';
const PARENT = 'infrastructure/runtime-security/c35-parent-keycloak.json';
const PARENT_SHA = '8f822f5ce291a3cbe855f51eb557ffdf9195bcddf09204e30b9b6efe22169d7f';
const BASELINE = 'infrastructure/runtime-security/reviewed-images.json';
const ACCEPTANCE = 'infrastructure/runtime-security/proofs/c35-keycloak/acceptance.json';
const INSPECTOR = 'infrastructure/runtime-security/vertx-c35-inspection.py';
const UNDERLYING = 'infrastructure/runtime-security/ssl-refresh-inspection.py';
const REHEARSAL = 'infrastructure/runtime-security/vertx-c35-rehearsal.py';
const PROTOCOL = 'infrastructure/runtime-security/vertx-c35-protocol-smoke.mjs';
const VERSION = '4.5.35';
const NETTY_VERSION = '4.1.139.Final';
const TCNATIVE_VERSION = '2.0.84.Final';
export const C35_JARS = new Map(Object.entries({
  'vertx-auth-common':
    '5103b6ce5121aa783707278b4ec08a19' +
    'a0405d87b91dced1d9f6054d7ffb5dc1',
  'vertx-bridge-common': 'a004fef3622ce6db8ba11c502f2c752dd0975eaf7c8e940e71c4c4ca0ccbf4cd',
  'vertx-codegen': 'b82fe8fe7324af7a37fd41940c228af99ea9c400e96ac55d0cfe8016b85b5337',
  'vertx-core': '1a274ba3739dc72506d5e801a8337ab734e07fbfd48ece7e77a518fbf40cea9a',
  'vertx-grpc': '70c91f9d4d9716f130057bc6d286687da9222a7fb96251dd91ac0a7bb55ed143',
  'vertx-grpc-client': '474b83d79944d1b4869452a9712b8cb51c9b6584e48d32ef113720c4de1072f7',
  'vertx-grpc-common': '908557303faea174e8755e6a1fb01771208db2e215dbeaff2549891855b50f8e',
  'vertx-grpc-server': '115d31258beffa4c2205ccd614981653df4a3ff48ad6dfb428fb9bd2a8a46962',
  'vertx-web': 'a8d9d06554e23286edb542d9adf15d057bda0399455a8f04ceb27e39bea1a0ac',
  'vertx-web-common': 'e6938a7c555ae87ccde10af71cfb264483ea7f952e7d7724e4196bef21cec64d',
}));
export const C35_NETTY_JARS = new Map(Object.entries({
  'netty-buffer': '48d805b74e56dc5f92b1f288858185abc86db16d293e8da183542df6243427f2',
  'netty-codec': 'ad43f07219dfb757a87b9c2b4765002be478c5401971379954e3d182f285fe6b',
  'netty-codec-dns': 'e044715cfd72e4717a06e41f4614dea0246ac20059d3aa72f36f16be20bc623c',
  'netty-codec-haproxy': '3109e099f34523799479c70b12b7d3731b9bb891acc8654a145eefd189a63472',
  'netty-codec-http': '627cfdac2cd5dbd8a33af85977fb81fa6b46f0c00389ab4c8677a415f0f85480',
  'netty-codec-http2': '4cb8bc95386112f3a9da935272781d577d9e70ca4c56cf0eef603a45e9526e02',
  'netty-codec-socks': '94d6800d5f717b40db5d0bf42a77b448d472cd9033735e69e7b18311bd902f23',
  'netty-common': '50372d86e27b4b1cf0810d75eb534795966a199049a1e85a0c41bbd2bb97f519',
  'netty-handler': 'ff85f00781b956d618b20f33da076c839a5e43e0084108b07bab0c19737168ec',
  'netty-handler-proxy': '9c92c2407b6fd3c120ab5efb900475a1fe40ce099703d2500c8679d3c75a90cb',
  'netty-resolver': '562a5388c88325b7d6ed05e8168736871ce0e64ab01e0ce52ea7710760b9b457',
  'netty-resolver-dns': '15feeffd0cbc2987ba35b798ca5b05fa1bc10cb880b8aa13e9bd81af3d483525',
  'netty-transport': 'be7bff8b6274e7fae12bbad3c565777539e9b069517e0b3acc06d9ac2cd6ec05',
  'netty-transport-classes-epoll': '99ea06d67e3e2792cc93521b686223c78da2561bfdf98533ebb29fed1729cce4',
  'netty-transport-native-epoll:linux-aarch_64': '039c9ce3bef089dfde34987cac3c928466cb71d54d3df79c0068fd17ee166e51',
  'netty-transport-native-epoll:linux-x86_64': '696fff4fb645d64fec6bc54fae27d9b643d64814e9be3bf42cbd94a608e2bcd0',
  'netty-transport-native-unix-common': '8e660ac8a485fc8d8b47d87d04ea33d19902dc6fc5a113e9353b762188b3f333',
  'netty-tcnative-classes': '7a4437abf98b0cec053af4443fe321177d2cfccfec7bcad4e7d8b75571bbec4d',
}));
const jarPath = artifact => `opt/keycloak/lib/lib/main/io.vertx.${artifact}-4.5.31.jar`;
const nettyPath = name => {
  const [artifact, classifier] = name.split(':');
  const version = artifact === 'netty-tcnative-classes' ? '2.0.78.Final' : '4.1.136.Final';
  return `opt/keycloak/lib/lib/main/io.netty.${artifact}-${version}${classifier ? '-' + classifier : ''}.jar`;
};
const generated = ['generated-bytecode.jar', 'quarkus-application.dat', 'transformed-bytecode.jar']
  .map(name => 'opt/keycloak/lib/quarkus/' + name);
const fileSet = [...C35_JARS.keys()].map(jarPath);
const nettyFileSet = [...C35_NETTY_JARS.keys()].map(nettyPath);
const requiredChanges = [...fileSet, ...nettyFileSet, ...generated].sort();
const FIELDS = ['User', 'WorkingDir', 'Entrypoint', 'Cmd', 'Env', 'Healthcheck', 'ExposedPorts', 'Volumes', 'StopSignal', 'Labels'];
const sameId = (id, config, reference) => assert.ok([config, reference.split('@')[1]].includes(id), 'vertx_c35_image_id');
const checks = (record, names) => {
  assert.equal(record.result, 'PASS', 'vertx_c35_proof_failed');
  assert.ok(Array.isArray(record.checks) && record.checks.length && record.checks.every(x => x.passed === true), 'vertx_c35_failed_check');
  for (const name of names) assert.equal(record.checks.filter(x => x.name === name && x.passed).length, 1, 'vertx_c35_missing_check:' + name);
};

export function validateVertxC35Inspection(record, parent, child, parentId, childId, childReference, commit) {
  assert.equal(record.schema, 'otziv-vertx-c35-inspection-v1', 'vertx_c35_inspection_schema');
  assert.equal(record.result, 'PASS', 'vertx_c35_inspection_failed');
  assert.equal(record.productionAccess, false, 'vertx_c35_production_access');
  assert.equal(record.ownedContainersRemaining, 0, 'vertx_c35_inspection_cleanup');
  assert.equal(record.images?.length, 2, 'vertx_c35_inspection_pair');
  const [before, after] = record.images;
  for (const [observed, config, id, reference] of [[before, parent, parentId, C26_REFERENCE], [after, child, childId, childReference]]) {
    assert.equal(observed.reference, reference, 'vertx_c35_inspection_reference');
    sameId(observed.imageId, id, reference);
    assert.equal(observed.containerExecuted, false, 'vertx_c35_inspection_executed');
    assert.deepEqual(observed.rootfs, config.rootfs.diff_ids, 'vertx_c35_inspection_rootfs');
    assert.deepEqual(observed.configuration, Object.fromEntries(FIELDS.map(k => [k, config.config[k] ?? null])), 'vertx_c35_inspection_config');
    assert.ok(Object.keys(observed.inventory).length > 100, 'vertx_c35_inventory_incomplete');
  }
  assert.deepEqual(after.rootfs.slice(0, before.rootfs.length), before.rootfs, 'vertx_c35_parent_layers');
  assert.ok(after.rootfs.length > before.rootfs.length, 'vertx_c35_overlay_missing');
  const expected = structuredClone(before.configuration);
  expected.Labels['otziv.vertx'] = VERSION;
  expected.Labels['otziv.vertx-advisory'] = 'CVE-2026-102477';
  expected.Labels['otziv.netty'] = NETTY_VERSION;
  expected.Labels['otziv.netty-handler'] = NETTY_VERSION;
  expected.Labels['otziv.netty-tcnative'] = TCNATIVE_VERSION;
  if (commit) expected.Labels['com.otziv.publication.revision'] = commit;
  assert.deepEqual(after.configuration, expected, 'vertx_c35_launch_changed');
  const paths = image => Object.keys(image.inventory).filter(p => p.startsWith('opt/keycloak/lib/lib/main/io.vertx.') && p.endsWith('.jar')).sort();
  assert.deepEqual(paths(before), [...fileSet].sort(), 'vertx_c35_parent_runtime_set');
  assert.deepEqual(paths(after), [...fileSet].sort(), 'vertx_c35_candidate_runtime_set');
  const nettyPaths = image => Object.keys(image.inventory).filter(p => p.startsWith('opt/keycloak/lib/lib/main/io.netty.') && p.endsWith('.jar')).sort();
  assert.deepEqual(nettyPaths(before), [...nettyFileSet].sort(), 'vertx_c35_parent_netty_set');
  assert.deepEqual(nettyPaths(after), [...nettyFileSet].sort(), 'vertx_c35_candidate_netty_set');
  const changed = [...new Set([...Object.keys(before.inventory), ...Object.keys(after.inventory)])]
    .filter(path => JSON.stringify(before.inventory[path]) !== JSON.stringify(after.inventory[path])).sort();
  assert.deepEqual(changed, requiredChanges, 'vertx_c35_unrelated_or_missing_file_change');
  assert.deepEqual(record.changedFiles, changed, 'vertx_c35_reported_change_mismatch');
  for (const [artifact, digest] of C35_JARS) {
    const path = jarPath(artifact), old = before.inventory[path], current = after.inventory[path], evidence = after.jars?.[artifact];
    assert.notEqual(old.sha256, digest, 'vertx_c35_parent_already_new:' + artifact);
    assert.equal(current.sha256, digest, 'vertx_c35_official_bytes:' + artifact);
    for (const key of ['mode', 'uid', 'gid', 'kind']) assert.equal(current[key], old[key], 'vertx_c35_file_metadata:' + artifact);
    assert.deepEqual(evidence, {path, sha256: digest, pom: {groupId: 'io.vertx', artifactId: artifact, version: VERSION}}, 'vertx_c35_maven_identity:' + artifact);
  }
  for (const [name, digest] of C35_NETTY_JARS) {
    const path = nettyPath(name), old = before.inventory[path], current = after.inventory[path], evidence = after.nettyJars?.[name];
    const artifact = name.split(':')[0], version = artifact === 'netty-tcnative-classes' ? TCNATIVE_VERSION : NETTY_VERSION;
    assert.notEqual(old.sha256, digest, 'vertx_c35_parent_netty_already_new:' + name);
    assert.equal(current.sha256, digest, 'vertx_c35_official_netty_bytes:' + name);
    for (const key of ['mode', 'uid', 'gid', 'kind']) assert.equal(current[key], old[key], 'vertx_c35_netty_file_metadata:' + name);
    assert.deepEqual(evidence, {path, sha256: digest, pom: {groupId: 'io.netty', artifactId: artifact, version}}, 'vertx_c35_netty_maven_identity:' + name);
  }
  return record;
}

export function validateVertxC35Rehearsal(capture, replay, childReference, childConfig, sourceSha) {
  for (const record of [capture, replay]) {
    assert.equal(record.schema, 'otziv-vertx-c35-rehearsal-v1', 'vertx_c35_rehearsal_schema');
    assert.equal(record.sourceScope, ['LOCAL', 'PROD', 'LIKE', 'KEYCLOAK'].join('_'), 'vertx_c35_rehearsal_scope');
    assert.equal(record.productionWrites, false, 'vertx_c35_production_write');
    assert.equal(record.privateRowsPublished, false, 'vertx_c35_private_rows');
    assert.equal(record.publishedPorts, 0, 'vertx_c35_host_ports');
    assert.equal(record.sourcePostgres, C23_POSTGRES, 'vertx_c35_source_postgres');
    assert.equal(record.sourcePostgresConfig, C23_POSTGRES_CONFIG, 'vertx_c35_source_postgres_config');
    assert.equal(record.keycloak, C26_REFERENCE, 'vertx_c35_source_keycloak');
    assert.equal(record.keycloakConfig, C26_CONFIG, 'vertx_c35_source_keycloak_config');
    assert.equal(record.executedScriptSha256, sourceSha, 'vertx_c35_rehearsal_source');
    checks(record, []);
  }
  checks(capture, ['complete_custom_dump', 'source_runtime_unchanged', 'source_critical_identity_stable_during_capture']);
  assert.equal(replay.dumpSha256, capture.dumpSha256, 'vertx_c35_dump_binding');
  assert.equal(replay.candidate, childReference, 'vertx_c35_candidate_reference');
  sameId(replay.candidateLocalId, childConfig, childReference);
  assert.equal(replay.candidatePostgres, C23_POSTGRES, 'vertx_c35_postgres_changed');
  sameId(replay.candidatePostgresLocalId, C23_POSTGRES_CONFIG, C23_POSTGRES);
  assert.deepEqual(replay.ownedResourcesRemaining, {container: 0, volume: 0, network: 0}, 'vertx_c35_rehearsal_cleanup');
  assert.ok(!replay.cleanupErrors?.length, 'vertx_c35_cleanup_errors');
  checks(replay, ['restored_critical_records_exact', 'capture_stable_tables_restored_exact', 'source_existing_password_login',
    'source_roles_and_credentials_preserved', 'same_volume_all_tables_exact', 'existing_admin_password_login',
    'existing_roles_and_credentials_preserved', 'unauthenticated_provider_denied', 'library_patch_no_schema_migration',
    'same_volume_rollback_preserves_all_post_upgrade_writes', 'same_volume_old_issuer_login',
    'same_volume_old_issuer_credentials_preserved', 'fresh_rollback_restore_preserves_all_post_upgrade_writes',
    'restored_existing_password_login', 'restored_roles_and_credentials_preserved']);
  assert.equal(replay.tableCount, 102, 'vertx_c35_rehearsal_table_coverage');
  for (const key of ['serverVersion', 'encoding', 'locale', 'clusterIdentifier', 'extensions', 'configSha256', 'hbaSha256', 'identSha256'])
    assert.deepEqual(replay.candidateDatabase[key], replay.sourceDatabase[key], 'vertx_c35_database_changed:' + key);
}

export function validateC35LibraryScan(raw) {
  const packages = raw.Results.flatMap(result => result.Packages || []);
  const vertx = packages.filter(pkg => /^io\.vertx:vertx-/.test(pkg.Name || ''));
  const scannedPaths = rows => rows.map(pkg => pkg.Name + '@' + pkg.FilePath).sort();
  assert.deepEqual(scannedPaths(vertx), [...C35_JARS.keys()]
    .map(artifact => 'io.vertx:' + artifact + '@' + jarPath(artifact)).sort(), 'vertx_c35_scanner_runtime_set');
  assert.ok(vertx.every(pkg => pkg.Version === VERSION), 'vertx_c35_scanner_runtime_version');
  const expectedNetty = new Map([...C35_NETTY_JARS.keys()].map(name => {
    const artifact = name.split(':')[0];
    return ['io.netty:' + artifact, artifact === 'netty-tcnative-classes' ? TCNATIVE_VERSION : NETTY_VERSION];
  }));
  const netty = packages.filter(pkg => (pkg.Name || '').startsWith('io.netty:'));
  assert.deepEqual(scannedPaths(netty), [...C35_NETTY_JARS.keys()]
    .map(name => 'io.netty:' + name.split(':')[0] + '@' + nettyPath(name)).sort(), 'vertx_c35_netty_scanner_set');
  assert.ok(netty.every(pkg => pkg.Version === expectedNetty.get(pkg.Name)), 'vertx_c35_netty_scanner_version');
}

async function acceptance(entry, read) {
  const bound = entry.vertxRefreshAcceptance;
  assert.deepEqual(bound?.path, ACCEPTANCE, 'vertx_c35_acceptance_path');
  const bytes = await read(bound.path);
  assert.equal(hash(bytes), bound.sha256, 'vertx_c35_acceptance_hash');
  const value = JSON.parse(bytes);
  assert.equal(value.schema, 'otziv-keycloak-vertx-c35-acceptance-v1', 'vertx_c35_acceptance_schema');
  assert.equal(value.result, 'PASS', 'vertx_c35_acceptance_failed');
  assert.equal(value.component, 'keycloak', 'vertx_c35_component');
  assert.equal(value.reference, entry.reference, 'vertx_c35_reference');
  assert.equal(value.parentReference, C26_REFERENCE, 'vertx_c35_parent');
  assert.equal(value.parentConfigId, C26_CONFIG, 'vertx_c35_parent_config');
  assert.deepEqual(value.postgres, {reference: C23_POSTGRES, imageConfigId: C23_POSTGRES_CONFIG}, 'vertx_c35_postgres_pair');
  for (const field of ['commit', 'run', 'attempt']) assert.equal(value[field], entry[field], 'vertx_c35_publication_identity');
  assert.ok(Object.keys(value.files || {}).length >= 10 && Object.keys(value.executedSources || {}).length >= 6, 'vertx_c35_evidence_incomplete');
  for (const [path, sha] of Object.entries({...value.files, ...value.executedSources})) {
    assert.match(path, /^infrastructure\/[a-zA-Z0-9_/.-]+$/, 'vertx_c35_evidence_path');
    assert.ok(!path.split('/').includes('..'), 'vertx_c35_evidence_escape');
    assert.equal(hash(await read(path)), sha, 'vertx_c35_evidence_hash:' + path);
  }
  for (const path of [INSPECTOR, UNDERLYING, REHEARSAL, PROTOCOL,
    'infrastructure/keycloak/security-generation/container-proof.mjs',
    'infrastructure/keycloak/security-generation/image-startup-smoke.mjs'])
    assert.ok(value.executedSources[path], 'vertx_c35_executed_source_missing:' + path);
  for (const path of [value.runtimePath, value.capturePath, value.replayPath, value.issuerPath, value.startupPath, value.protocolPath])
    assert.ok(value.files[path], 'vertx_c35_proof_unbound:' + path);
  return value;
}

export async function validateVertxC35Activation(publication, entry, read, validateParent) {
  assert.equal(entry.manifest?.path, 'infrastructure/runtime-security/reviewed-images-c35-keycloak.json', 'vertx_c35_manifest');
  assert.deepEqual(entry.migrationAcceptance, entry.vertxRefreshAcceptance, 'vertx_c35_migration_binding');
  const parentBytes = await read(PARENT);
  assert.equal(hash(parentBytes), PARENT_SHA, 'vertx_c35_parent_activation_changed');
  const parent = JSON.parse(parentBytes);
  assert.equal(parent.reference, C26_REFERENCE, 'vertx_c35_parent_activation_reference');
  const baseline = await read(BASELINE);
  const original = JSON.parse(baseline).images.find(x => x.component === 'keycloak');
  await validateParent(original, parent, baseline, read);
  const value = await acceptance(entry, read);
  assert.equal(publication.imageId, value.imageConfigId, 'vertx_c35_publication_config');
  const parentConfigBytes = await read(dirname(parent.publication.path) + '/registry-amd64-config.json');
  const childDirectory = dirname(entry.publication.path);
  const childConfigBytes = await read(childDirectory + '/registry-amd64-config.json');
  assert.equal('sha256:' + hash(parentConfigBytes), C26_CONFIG, 'vertx_c35_parent_registry_config');
  assert.equal('sha256:' + hash(childConfigBytes), value.imageConfigId, 'vertx_c35_candidate_registry_config');
  const runtime = JSON.parse(gunzipSync(await read(value.runtimePath)));
  assert.equal(runtime.executedScriptSha256, value.executedSources[INSPECTOR], 'vertx_c35_inspector_source');
  assert.equal(runtime.underlyingInspectorSha256, value.executedSources[UNDERLYING], 'vertx_c35_underlying_inspector_source');
  assert.equal(runtime.publicationCommit, entry.commit, 'vertx_c35_runtime_publication_commit');
  validateVertxC35Inspection(runtime, JSON.parse(parentConfigBytes), JSON.parse(childConfigBytes),
    C26_CONFIG, value.imageConfigId, entry.reference, entry.commit);
  const rawBytes = await read(childDirectory + '/vulnerabilities.json'), raw = JSON.parse(rawBytes);
  assert.equal(raw.Metadata?.ImageID, value.imageConfigId, 'vertx_c35_scan_image');
  assert.deepEqual(raw.Metadata.ImageConfig.rootfs, JSON.parse(childConfigBytes).rootfs, 'vertx_c35_scan_rootfs');
  const receipt = JSON.parse(await read(childDirectory + '/vulnerabilities.adjudications.json'));
  assert.equal(receipt.rawReportSha256, hash(rawBytes), 'vertx_c35_scan_hash');
  assert.equal(receipt.imageConfigId, value.imageConfigId, 'vertx_c35_receipt_image');
  assert.equal(receipt.rawReportModified, false, 'vertx_c35_scan_modified');
  assert.equal(receipt.status, 'NOT_APPLICABLE', 'vertx_c35_foreign_adjudication');
  assert.deepEqual(receipt.decisions, [], 'vertx_c35_foreign_decisions');
  assert.deepEqual(JSON.parse(await read(childDirectory + '/vulnerabilities.triage.json')), buildTriage(raw, rawBytes), 'vertx_c35_scan_triage');
  const summary = summarizeReport(raw);
  assert.equal(summary.high + summary.critical, 0, 'vertx_c35_raw_security_findings');
  assert.deepEqual(publication.security, {...combinedScanSummary(summary, {grafana: receipt, alloy: receipt.alloy, postgres: receipt.postgres}),
    scannerImage: TRIVY_IMAGE}, 'vertx_c35_security_summary');
  assert.equal(publication.knownRuntimeDependencies.policy, C26_POLICY, 'vertx_c35_jackson_policy');
  validateC35LibraryScan(raw);
  const capture = JSON.parse(await read(value.capturePath)), replay = JSON.parse(await read(value.replayPath));
  validateVertxC35Rehearsal(capture, replay, entry.reference, value.imageConfigId, value.executedSources[REHEARSAL]);
  const issuer = JSON.parse(await read(value.issuerPath)), startup = JSON.parse(await read(value.startupPath));
  for (const proof of [issuer, startup]) {
    assert.equal(proof.production, false, 'vertx_c35_fixture_production');
    assert.equal(proof.image, entry.reference, 'vertx_c35_fixture_image');
    sameId(proof.imageId, value.imageConfigId, entry.reference);
    checks(proof, []);
  }
  assert.equal(issuer.realProviderLogin, true, 'vertx_c35_real_provider_login');
  assert.ok(issuer.checks.length >= 20, 'vertx_c35_issuer_coverage');
  assert.equal(issuer.postgresImage, C23_POSTGRES, 'vertx_c35_issuer_postgres');
  sameId(issuer.postgresImageId, C23_POSTGRES_CONFIG, C23_POSTGRES);
  assert.equal(startup.postgresImage.reference, C23_POSTGRES, 'vertx_c35_startup_postgres');
  assert.equal(startup.cleanup, 'PASS', 'vertx_c35_startup_cleanup');
  checks(startup, ['start_imports_and_serves_real_postgres_realm', 'start_has_no_missing_classpath_error',
    'start-dev_imports_and_serves_real_postgres_realm', 'start-dev_has_no_missing_classpath_error']);
  const protocol = JSON.parse(await read(value.protocolPath));
  assert.equal(protocol.schema, 'otziv-keycloak-c35-protocol-v1', 'vertx_c35_protocol_schema');
  assert.equal(protocol.production, false, 'vertx_c35_protocol_production_access');
  assert.equal(protocol.image, entry.reference, 'vertx_c35_protocol_image');
  sameId(protocol.imageId, value.imageConfigId, entry.reference);
  assert.equal(protocol.postgresImage.reference, C23_POSTGRES, 'vertx_c35_protocol_postgres');
  sameId(protocol.postgresImage.id, C23_POSTGRES_CONFIG, C23_POSTGRES);
  assert.equal(protocol.cleanup, 'PASS', 'vertx_c35_protocol_cleanup');
  checks(protocol, ['production_path_discovery_issuer', 'production_path_jwks_uri',
    'production_path_jwks_served', 'production_path_management_ready',
    'production_path_has_no_missing_classpath_error']);
  return value;
}

export async function coupleVertxC35(readiness, entry, read, validateParent) {
  assert.equal(readiness.requiredKeycloakReference, C26_REFERENCE, 'vertx_c35_coupling_parent');
  assert.equal(readiness.requiredKeycloakConfigId, C26_CONFIG, 'vertx_c35_coupling_parent_config');
  assert.equal(readiness.postgresReference, C23_POSTGRES, 'vertx_c35_coupling_postgres');
  assert.equal(readiness.postgresConfigId, C23_POSTGRES_CONFIG, 'vertx_c35_coupling_postgres_config');
  const publication = JSON.parse(await read(entry.publication.path));
  const value = await validateVertxC35Activation(publication, entry, read, validateParent);
  return {...readiness, requiredKeycloakReference: entry.reference, requiredKeycloakConfigId: value.imageConfigId,
    acceptancePath: entry.vertxRefreshAcceptance.path, proofSha256: entry.vertxRefreshAcceptance.sha256};
}
