import test from 'node:test';
import assert from 'node:assert/strict';
import {C26_REFERENCE, C26_CONFIG, C23_POSTGRES, C23_POSTGRES_CONFIG, C35_JARS, C35_NETTY_JARS,
  validateVertxC35Inspection, validateVertxC35Rehearsal, validateVertxC35Activation,
  validateC35LibraryScan} from './vertx-c35-activation.mjs';
import {createEvidenceReader, coupleRegisteredKeycloak, validateActivation} from './reviewed-image-defaults.mjs';
import {validateSslRefreshTransition} from './ssl-refresh-activation.mjs';

const candidate = 'ghcr.io/claidd/otziv-security@sha256:' + 'b'.repeat(64);
const candidateConfig = 'sha256:' + 'c'.repeat(64);
const parentLayer = 'sha256:' + 'd'.repeat(64), addedLayer = 'sha256:' + 'e'.repeat(64);
const path = artifact => `opt/keycloak/lib/lib/main/io.vertx.${artifact}-4.5.31.jar`;
const nettyPath = name => {
  const [artifact, classifier] = name.split(':');
  return `opt/keycloak/lib/lib/main/io.netty.${artifact}-${artifact === 'netty-tcnative-classes' ? '2.0.78.Final' : '4.1.136.Final'}${classifier ? '-' + classifier : ''}.jar`;
};
const generated = ['generated-bytecode.jar', 'quarkus-application.dat', 'transformed-bytecode.jar']
  .map(name => 'opt/keycloak/lib/quarkus/' + name);
const names = [...C35_JARS.keys()].map(path);
const nettyNames = [...C35_NETTY_JARS.keys()].map(nettyPath);
const metadata = sha256 => ({mode: 436, uid: 1000, gid: 0, kind: 'file', sha256});

function fixture() {
  const old = Object.fromEntries(Array.from({length: 101}, (_, i) => [`opt/keycloak/fixture-${i}`, metadata('same')]));
  const current = structuredClone(old), jars = {}, nettyJars = {};
  for (const [artifact, digest] of C35_JARS) {
    old[path(artifact)] = metadata('old-' + artifact);
    current[path(artifact)] = metadata(digest);
    jars[artifact] = {path: path(artifact), sha256: digest,
      pom: {groupId: 'io.vertx', artifactId: artifact, version: '4.5.35'}};
  }
  for (const [name, digest] of C35_NETTY_JARS) {
    const artifact = name.split(':')[0], version = artifact === 'netty-tcnative-classes' ? '2.0.84.Final' : '4.1.139.Final';
    const oldJar = metadata('old-' + name);
    if (artifact === 'netty-handler') oldJar.mode = 420;
    old[nettyPath(name)] = oldJar;
    current[nettyPath(name)] = {...oldJar, sha256: digest};
    nettyJars[name] = {path: nettyPath(name), sha256: digest,
      pom: {groupId: 'io.netty', artifactId: artifact, version}};
  }
  for (const item of generated) {
    old[item] = metadata('old-' + item);
    current[item] = metadata('new-' + item);
  }
  const config = {User: '1000', WorkingDir: '/opt/keycloak', Entrypoint: ['/opt/keycloak/bin/kc.sh'], Cmd: ['start'],
    Env: ['KC_DB=postgres'], Healthcheck: null, ExposedPorts: null, Volumes: null, StopSignal: null,
    Labels: {'com.otziv.publication.revision': 'a'.repeat(40),
      'otziv.netty-handler': '4.1.137.Final', 'otziv.netty-handler-advisory': 'CVE-2026-75595'}};
  const nextConfig = structuredClone(config);
  nextConfig.Labels['com.otziv.publication.revision'] = 'f'.repeat(40);
  nextConfig.Labels['otziv.vertx'] = '4.5.35';
  nextConfig.Labels['otziv.vertx-advisory'] = 'CVE-2026-102477';
  nextConfig.Labels['otziv.netty'] = '4.1.139.Final';
  nextConfig.Labels['otziv.netty-handler'] = '4.1.139.Final';
  nextConfig.Labels['otziv.netty-tcnative'] = '2.0.84.Final';
  const before = {reference: C26_REFERENCE, imageId: C26_CONFIG, containerExecuted: false,
    rootfs: [parentLayer], configuration: config, inventory: old};
  const after = {reference: candidate, imageId: candidateConfig, containerExecuted: false,
    rootfs: [parentLayer, addedLayer], configuration: nextConfig, inventory: current, jars, nettyJars};
  const changedFiles = [...names, ...nettyNames, ...generated].sort();
  const record = {schema: 'otziv-vertx-c35-inspection-v1', result: 'PASS', productionAccess: false,
    ownedContainersRemaining: 0, images: [before, after], changedFiles};
  return {record, parent: {rootfs: {diff_ids: before.rootfs}, config},
    child: {rootfs: {diff_ids: after.rootfs}, config: nextConfig}};
}

function verify({record, parent, child}) {
  return validateVertxC35Inspection(record, parent, child, C26_CONFIG, candidateConfig, candidate, 'f'.repeat(40));
}

function scanFixture() {
  const packages = [
    ...[...C35_JARS.keys()].map(name => ({Name: 'io.vertx:' + name, Version: '4.5.35', FilePath: path(name)})),
    ...[...C35_NETTY_JARS.keys()].map(name => ({Name: 'io.netty:' + name.split(':')[0], FilePath: nettyPath(name),
      Version: name === 'netty-tcnative-classes' ? '2.0.84.Final' : '4.1.139.Final'})),
  ];
  return {Results: [{Packages: packages}]};
}

test('C35 scanner requires exact Vert.x and Netty runtime packages', () => {
  const raw = scanFixture();
  assert.doesNotThrow(() => validateC35LibraryScan(raw));
  const oldNetty = structuredClone(raw);
  oldNetty.Results[0].Packages.find(x => x.Name === 'io.netty:netty-handler').Version = '4.1.136.Final';
  assert.throws(() => validateC35LibraryScan(oldNetty), /netty_scanner_version/);
  const oldVertx = structuredClone(raw);
  oldVertx.Results[0].Packages.find(x => x.Name === 'io.vertx:vertx-core').Version = '4.5.31';
  assert.throws(() => validateC35LibraryScan(oldVertx), /scanner_runtime_version/);
  const missingTcnative = structuredClone(raw);
  missingTcnative.Results[0].Packages = missingTcnative.Results[0].Packages.filter(x => x.Name !== 'io.netty:netty-tcnative-classes');
  assert.throws(() => validateC35LibraryScan(missingTcnative), /netty_scanner_set/);
  const extraNetty = structuredClone(raw);
  extraNetty.Results[0].Packages.push({Name: 'io.netty:netty-all', Version: '4.1.139.Final', FilePath: 'extra.jar'});
  assert.throws(() => validateC35LibraryScan(extraNetty), /netty_scanner_set/);
  const wrongPath = structuredClone(raw);
  wrongPath.Results[0].Packages.find(x => x.Name === 'io.netty:netty-buffer').FilePath = 'old.jar';
  assert.throws(() => validateC35LibraryScan(wrongPath), /netty_scanner_set/);
});

test('C35 accepts only ten Vert.x, eighteen Netty and three Quarkus augmentation files', () => {
  assert.equal(verify(fixture()).result, 'PASS');
  const local = fixture();
  local.record.images[1].inventory['opt/keycloak/extra.jar'] = metadata('extra');
  assert.throws(() => verify(local), /candidate_runtime_set|unrelated_or_missing_file_change/);
});

for (const [name, mutation, error] of [
  ['wrong Maven JAR bytes', x => x.record.images[1].inventory[path('vertx-core')].sha256 = 'tampered', 'official_bytes'],
  ['wrong embedded Maven version', x => x.record.images[1].jars['vertx-core'].pom.version = '4.5.31', 'maven_identity'],
  ['missing transport JAR', x => {delete x.record.images[1].inventory[path('vertx-grpc-client')]; delete x.record.images[1].jars['vertx-grpc-client'];}, 'candidate_runtime_set'],
  ['changed JAR permissions', x => x.record.images[1].inventory[path('vertx-web')].mode = 420, 'file_metadata'],
  ['wrong Netty bytes', x => x.record.images[1].inventory[nettyPath('netty-handler')].sha256 = 'tampered', 'official_netty_bytes'],
  ['wrong embedded Netty version', x => x.record.images[1].nettyJars['netty-codec-http2'].pom.version = '4.1.136.Final', 'netty_maven_identity'],
  ['missing Netty native classifier', x => {delete x.record.images[1].inventory[nettyPath('netty-transport-native-epoll:linux-x86_64')]; delete x.record.images[1].nettyJars['netty-transport-native-epoll:linux-x86_64'];}, 'candidate_netty_set'],
  ['changed Netty handler permissions', x => x.record.images[1].inventory[nettyPath('netty-handler')].mode = 436, 'netty_file_metadata'],
  ['stale Netty handler label', x => {x.record.images[1].configuration.Labels['otziv.netty-handler'] = '4.1.137.Final'; x.child.config.Labels['otziv.netty-handler'] = '4.1.137.Final';}, 'launch_changed'],
  ['changed unrelated provider', x => x.record.images[1].inventory['opt/keycloak/fixture-1'].sha256 = 'changed', 'unrelated_or_missing_file_change'],
  ['new launch command', x => {x.record.images[1].configuration.Cmd = ['start-dev']; x.child.config.Cmd = ['start-dev'];}, 'launch_changed'],
  ['different parent layer', x => {x.record.images[1].rootfs[0] = 'other'; x.child.rootfs.diff_ids[0] = 'other';}, 'parent_layers'],
  ['executed candidate', x => x.record.images[1].containerExecuted = true, 'inspection_executed'],
  ['unclosed inspection container', x => x.record.ownedContainersRemaining = 1, 'inspection_cleanup'],
]) test('C35 rejects ' + name, () => {
  const value = fixture(); mutation(value);
  assert.throws(() => verify(value), new RegExp(error));
});

const replayChecks = ['restored_critical_records_exact', 'capture_stable_tables_restored_exact',
  'source_existing_password_login', 'source_roles_and_credentials_preserved', 'same_volume_all_tables_exact',
  'existing_admin_password_login', 'existing_roles_and_credentials_preserved', 'unauthenticated_provider_denied',
  'library_patch_no_schema_migration', 'same_volume_rollback_preserves_all_post_upgrade_writes',
  'same_volume_old_issuer_login', 'same_volume_old_issuer_credentials_preserved',
  'fresh_rollback_restore_preserves_all_post_upgrade_writes', 'restored_existing_password_login',
  'restored_roles_and_credentials_preserved'];
const passed = names => names.map(name => ({name, passed: true}));
function rehearsal() {
  const common = {schema: 'otziv-vertx-c35-rehearsal-v1', result: 'PASS', productionWrites: false,
    sourceScope: 'LOCAL_PROD_LIKE_KEYCLOAK', privateRowsPublished: false, publishedPorts: 0,
    sourcePostgres: C23_POSTGRES, sourcePostgresConfig: C23_POSTGRES_CONFIG,
    keycloak: C26_REFERENCE, keycloakConfig: C26_CONFIG, executedScriptSha256: 'a'.repeat(64), dumpSha256: 'b'.repeat(64)};
  return {
    capture: {...common, checks: passed(['complete_custom_dump', 'source_runtime_unchanged', 'source_critical_identity_stable_during_capture'])},
    replay: {...common, checks: passed(replayChecks), candidate, candidateLocalId: candidateConfig,
      candidatePostgres: C23_POSTGRES, candidatePostgresLocalId: C23_POSTGRES_CONFIG,
      ownedResourcesRemaining: {container: 0, volume: 0, network: 0}, tableCount: 102,
      sourceDatabase: {serverVersion: '16', encoding: 'UTF8', locale: 'C', clusterIdentifier: 'x',
        extensions: [], configSha256: 'a', hbaSha256: 'b', identSha256: 'c'},
      candidateDatabase: {serverVersion: '16', encoding: 'UTF8', locale: 'C', clusterIdentifier: 'x',
        extensions: [], configSha256: 'a', hbaSha256: 'b', identSha256: 'c'}},
  };
}
test('C35 requires rollback of the actual C26 issuer on unchanged C23 PostgreSQL', () => {
  const {capture, replay} = rehearsal();
  assert.doesNotThrow(() => validateVertxC35Rehearsal(capture, replay, candidate, candidateConfig, 'a'.repeat(64)));
  replay.checks.find(x => x.name === 'same_volume_old_issuer_login').passed = false;
  assert.throws(() => validateVertxC35Rehearsal(capture, replay, candidate, candidateConfig, 'a'.repeat(64)), /failed_check/);
});
test('C35 rejects a replay from the old C22 Keycloak or altered database image', () => {
  const pair = rehearsal(); pair.capture.keycloak = 'old-C22';
  assert.throws(() => validateVertxC35Rehearsal(pair.capture, pair.replay, candidate, candidateConfig, 'a'.repeat(64)), /source_keycloak/);
  const second = rehearsal(); second.replay.candidatePostgres = 'new-postgres';
  assert.throws(() => validateVertxC35Rehearsal(second.capture, second.replay, candidate, candidateConfig, 'a'.repeat(64)), /postgres_changed/);
});

test('accepted C26 Keycloak still couples to the active C23 PostgreSQL proof', async () => {
  const read = await createEvidenceReader(process.cwd());
  const baseline = JSON.parse(await read('infrastructure/runtime-security/reviewed-images.json'));
  const postgresEntry = JSON.parse(await read('infrastructure/runtime-security/c36-parent-postgres.json'));
  const issuerEntry = JSON.parse(await read('infrastructure/runtime-security/c35-parent-keycloak.json'));
  const postgresImage = baseline.images.find(image => image.component === 'postgres');
  const readiness = await validateSslRefreshTransition(postgresEntry, postgresImage, read);
  const coupled = await coupleRegisteredKeycloak(readiness, issuerEntry, read);
  assert.equal(coupled.requiredKeycloakReference, C26_REFERENCE);
  assert.equal(coupled.postgresReference, C23_POSTGRES);
  assert.deepEqual(issuerEntry.migrationAcceptance,
    {path: coupled.acceptancePath, sha256: coupled.proofSha256});
});

test('published C35 Keycloak validates its proofs and couples to C23 PostgreSQL', async () => {
  const read = await createEvidenceReader(process.cwd());
  const manifest = await read('infrastructure/runtime-security/reviewed-images.json');
  const baseline = JSON.parse(manifest);
  const postgresEntry = JSON.parse(await read('infrastructure/runtime-security/c36-parent-postgres.json'));
  const issuerEntry = JSON.parse(await read('infrastructure/runtime-security/c43-parent-keycloak.json'));
  assert.equal(issuerEntry.manifest.path, 'infrastructure/runtime-security/reviewed-images-c35-keycloak.json');
  const image = baseline.images.find(image => image.component === 'keycloak');
  assert.equal(await validateActivation(image, issuerEntry, manifest, read), issuerEntry.reference);
  const postgresImage = baseline.images.find(image => image.component === 'postgres');
  const readiness = await validateSslRefreshTransition(postgresEntry, postgresImage, read);
  const coupled = await coupleRegisteredKeycloak(readiness, issuerEntry, read);
  assert.equal(coupled.requiredKeycloakReference, issuerEntry.reference);
  assert.equal(coupled.postgresReference, C23_POSTGRES);
  assert.deepEqual(issuerEntry.migrationAcceptance,
    {path: coupled.acceptancePath, sha256: coupled.proofSha256});
});

test('C35 coupling rejects anything other than the accepted C26 plus C23 parent pair', async () => {
  const issuer = {manifest: {path: 'infrastructure/runtime-security/reviewed-images-c35-keycloak.json'}};
  const unreadable = async () => { throw Error('proof_read_before_parent_pair_checked'); };
  const readiness = {requiredKeycloakReference: C26_REFERENCE, requiredKeycloakConfigId: C26_CONFIG,
    postgresReference: C23_POSTGRES, postgresConfigId: C23_POSTGRES_CONFIG};
  await assert.rejects(coupleRegisteredKeycloak({...readiness, requiredKeycloakReference: 'other'}, issuer, unreadable),
    /vertx_c35_coupling_parent/);
  await assert.rejects(coupleRegisteredKeycloak({...readiness, postgresConfigId: 'other'}, issuer, unreadable),
    /vertx_c35_coupling_postgres_config/);
});

test('C35 standalone activation requires its migration acceptance binding', async () => {
  await assert.rejects(validateVertxC35Activation({}, {
    manifest: {path: 'infrastructure/runtime-security/reviewed-images-c35-keycloak.json'},
    migrationAcceptance: {path: 'old'}, vertxRefreshAcceptance: {path: 'new'},
  }, async () => { throw Error('proof_read_before_migration_binding_checked'); }, async () => {}),
  /vertx_c35_migration_binding/);
});
