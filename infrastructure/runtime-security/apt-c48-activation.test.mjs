import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {gunzipSync} from 'node:zlib';
import {hash} from './ssl-refresh.mjs';
import {validateAptC48Runtime, validateAptC48Rehearsal, validateAptC48CaptureWindow, couplePostgresC36WithKeycloakC48,
  dpkgFilters, excluded, resolvePath, C45_REFERENCE, C45_CONFIG, C43_ALLOY_REFERENCE} from './apt-c48-activation.mjs';
import {C36_POSTGRES, C36_POSTGRES_CONFIG} from './guava-c43-activation.mjs';

const load = async component => {
  const prefix = new URL(`./proofs/c48-${component}/`, import.meta.url);
  const json = async name => JSON.parse(await readFile(new URL(name, prefix)));
  return {runtime: JSON.parse(gunzipSync(await readFile(new URL('inspection/runtime.json.gz', prefix)))),
    receipt: await json('inspection/inspection.json'), publication: await json('publication/publication.json'),
    packageBytes: await readFile(new URL(`./c48-${component}-apt-packages.json`, import.meta.url))};
};
const actual = {keycloak: await load('keycloak'), alloy: await load('alloy')};
const fixture = component => {
  const {packageBytes, ...rest} = actual[component];
  return {...structuredClone(rest), packageBytes};
};
const run = (component, value) => validateAptC48Runtime(component, value.runtime, value.receipt, value.publication, value.packageBytes);
const recapture = (image, path, text) => {
  image.captured[path] = text;
  image.inventory[path].sha256 = hash(Buffer.from(text));
};

for (const component of ['keycloak', 'alloy']) {
  test(`C48 ${component} permits only the exact official apt payload and package bookkeeping`, () => {
    const value = fixture(component);
    const changed = run(component, value);
    assert.deepEqual(changed, value.receipt.changedFiles);
    assert.ok(changed.includes('usr/lib/x86_64-linux-gnu/libapt-pkg.so.6.0.0'));
    assert.ok(changed.every(path => path.startsWith('usr/') || path.startsWith('var/')));
  });
}

const library = 'usr/lib/x86_64-linux-gnu/libapt-pkg.so.6.0.0';
for (const [component, name, mutate, error] of [
  ['keycloak', 'another parent', value => { value.runtime.parent.reference = 'other'; }, 'apt_c48_parent'],
  ['alloy', 'another candidate image', value => { value.runtime.candidate.imageId = 'other'; }, 'image_identity'],
  ['keycloak', 'replaced parent layer', value => { value.runtime.candidate.rootfs[0] = 'other'; }, 'parent_layers'],
  ['keycloak', 'root runtime user', value => { value.runtime.candidate.configuration.User = '0'; }, 'unrelated_configuration'],
  ['alloy', 'changed entrypoint', value => { value.runtime.candidate.configuration.Entrypoint = ['/bin/sh']; }, 'unrelated_configuration'],
  ['alloy', 'executed inspected image', value => { value.runtime.candidate.containerExecuted = true; }, 'executed_container'],
  ['keycloak', 'wrong library bytes', value => { value.runtime.candidate.inventory[library].sha256 = 'wrong'; }, 'official_payload'],
  ['alloy', 'wrong library permissions', value => { value.runtime.candidate.inventory[library].mode = 511; }, 'official_payload'],
  ['keycloak', 'wrong package control', value => { value.runtime.candidate.inventory['var/lib/dpkg/info/apt.postinst'].sha256 = 'wrong'; }, 'official_control'],
  ['alloy', 'forged captured status', value => { value.runtime.candidate.captured['var/lib/dpkg/status'] += '\n'; }, 'captured_metadata_digest'],
  ['keycloak', 'unrelated package change', value => {
    const image = value.runtime.candidate, path = 'var/lib/dpkg/status';
    recapture(image, path, image.captured[path].replace(/(Package: libc6\n[\s\S]*?\nVersion: )[^\n]+/, '$1other'));
  }, 'unrelated_package'],
  ['alloy', 'unchanged vulnerable apt', value => {
    const image = value.runtime.candidate, path = 'var/lib/dpkg/status';
    recapture(image, path, image.captured[path].replace(/(Package: apt\n[\s\S]*?\nVersion: )[^\n]+/, '$12.8.3'));
  }, 'target_package'],
  ['keycloak', 'forged package ownership', value => {
    const image = value.runtime.candidate, path = 'var/lib/dpkg/info/apt.list';
    recapture(image, path, image.captured[path] + '/opt/keycloak/bin/kc.sh\n');
  }, 'package_manifest'],
  ['keycloak', 'provider replacement', value => {
    value.runtime.candidate.inventory['opt/keycloak/providers/otziv-security-generation.jar'].sha256 = 'wrong';
  }, 'unrelated_payload'],
  ['alloy', 'Alloy binary replacement', value => { value.runtime.candidate.inventory['usr/bin/alloy'].sha256 = 'wrong'; }, 'unrelated_payload'],
  ['alloy', 'build closure replacement', value => {
    value.runtime.candidate.inventory['usr/share/otziv-build/alloy.packages.txt'].sha256 = 'wrong';
  }, 'unrelated_payload'],
  ['keycloak', 'directory permission change', value => { value.runtime.candidate.inventory['opt/keycloak'].mode = 511; }, 'unrelated_payload'],
  ['alloy', 'existing package directory permission change', value => {
    value.runtime.candidate.inventory['var/cache/apt/archives/partial'].mode = 493;
  }, 'existing_directory'],
  ['keycloak', 'filtered documentation installed', value => {
    value.runtime.candidate.inventory['usr/share/doc/apt/NEWS.Debian.gz'] = value.runtime.parent.inventory['usr/share/doc/apt/NEWS.Debian.gz'];
  }, 'filtered_path_present'],
  ['alloy', 'changed dpkg path filters', value => {
    recapture(value.runtime.candidate, 'etc/dpkg/dpkg.cfg.d/excludes', 'path-exclude=/usr/*\n');
  }, 'dpkg_configuration'],
  ['keycloak', 'additional dpkg configuration', value => {
    value.runtime.candidate.inventory['etc/dpkg/dpkg.cfg.d/zz-otziv'] = {mode: 420, uid: 0, gid: 0, kind: 'file', sha256: hash('x')};
  }, 'dpkg_fragments'],
  ['alloy', 'concealed changed file', value => { value.receipt.changedFiles.pop(); }, 'changed_files'],
  ['keycloak', 'another inspected component', value => { value.receipt.component = 'alloy'; }, 'inspection_component'],
]) test(`C48 ${component} rejects ${name}`, () => {
  const value = fixture(component); mutate(value);
  assert.throws(() => run(component, value), new RegExp(error));
});

test('another package inventory cannot authorize different file bytes', () => {
  const value = fixture('keycloak'), substituted = JSON.parse(value.packageBytes);
  substituted.packages[1].payload['opt/keycloak/bin/kc.sh'] = substituted.packages[1].payload['usr/bin/apt'];
  value.packageBytes = Buffer.from(JSON.stringify(substituted));
  assert.throws(() => run('keycloak', value), /package_inventory_digest/);
  assert.throws(() => run('alloy', fixture('keycloak')), /package_inventory_digest/);
});

test('dpkg path filters follow the parent configuration, last match wins', () => {
  const rules = dpkgFilters(actual.alloy.runtime.candidate);
  assert.equal(rules.length, 5);
  assert.ok(excluded('usr/share/man/man8/apt.8.gz', rules));
  assert.ok(excluded('usr/share/locale/de/LC_MESSAGES/apt.mo', rules));
  assert.ok(excluded('usr/share/doc/apt/NEWS.Debian.gz', rules));
  assert.ok(!excluded('usr/share/doc/apt/changelog.gz', rules));
  assert.ok(!excluded('usr/share/doc/apt/copyright', rules));
  assert.ok(!excluded('usr/lib/x86_64-linux-gnu/libapt-pkg.so.6.0.0', rules));
  assert.throws(() => dpkgFilters({captured: {'etc/dpkg/dpkg.cfg': 'path-exclude=/usr/[a]*\n'}}), /unsupported_dpkg_filter/);
});

test('merged /usr parents resolve without following the final component', () => {
  const inventory = {lib: {kind: 'symlink', target: 'usr/lib'}, 'usr/lib/link': {kind: 'symlink', target: '/etc'},
    up: {kind: 'symlink', target: '../..'}};
  assert.equal(resolvePath('lib/systemd/system/apt-daily.timer', inventory), 'usr/lib/systemd/system/apt-daily.timer');
  assert.equal(resolvePath('lib', inventory), 'lib');
  assert.equal(resolvePath('lib/link/apt', inventory), 'etc/apt');
  assert.throws(() => resolvePath('up/etc', inventory), /symlink_escape/);
});

const replayFixture = () => {
  const named = names => names.map(name => ({name, passed: true}));
  const common = {sourceScope: 'LOCAL_PROD_LIKE_KEYCLOAK', productionWrites: false, privateRowsPublished: false,
    publishedPorts: 0, schema: 'otziv-apt-c48-rehearsal-v1', result: 'PASS', sourcePostgres: C36_POSTGRES,
    sourcePostgresConfig: C36_POSTGRES_CONFIG, keycloak: C45_REFERENCE, keycloakConfig: C45_CONFIG,
    replayBaselinePostgres: C36_POSTGRES, replayBaselineKeycloak: C45_REFERENCE, replayBaselineKeycloakConfig: C45_CONFIG,
    executedScriptSha256: 'f'.repeat(64), dumpSha256: 'd'.repeat(64)};
  const database = {serverVersion: '17', encoding: 'UTF8', locale: {}, clusterIdentifier: '1', extensions: [],
    configSha256: 'a', hbaSha256: 'b', identSha256: 'c'};
  const publication = actual.keycloak.publication;
  return {publication, capture: {...common, checks: named(['complete_custom_dump', 'source_runtime_unchanged',
    'source_critical_identity_stable_during_capture'])},
  replay: {...common, candidate: publication.reference, candidateLocalId: publication.imageId,
    candidatePostgres: C36_POSTGRES, candidatePostgresLocalId: C36_POSTGRES_CONFIG,
    ownedResourcesRemaining: {container: 0, volume: 0, network: 0}, tableCount: 102,
    sourceDatabase: database, candidateDatabase: structuredClone(database),
    checks: named(['restored_critical_records_exact', 'capture_stable_tables_restored_exact', 'source_existing_password_login',
      'source_roles_and_credentials_preserved', 'same_volume_all_tables_exact', 'existing_admin_password_login',
      'existing_roles_and_credentials_preserved', 'unauthenticated_provider_denied', 'library_patch_no_schema_migration',
      'same_volume_rollback_preserves_all_post_upgrade_writes', 'same_volume_old_issuer_login',
      'same_volume_old_issuer_credentials_preserved', 'fresh_rollback_restore_preserves_all_post_upgrade_writes',
      'restored_existing_password_login', 'restored_roles_and_credentials_preserved'])}};
};

test('C48 data acceptance requires the C45/C36 capture, replay and both rollback paths', () => {
  const value = replayFixture();
  validateAptC48Rehearsal(value.capture, value.replay, value.publication, 'f'.repeat(64));
});
for (const [name, mutate, error] of [
  ['historical schema substitution', value => { value.replay.schema = 'otziv-brotli-c45-rehearsal-v1'; }, 'rehearsal_schema'],
  ['another capture issuer', value => { value.capture.keycloak = 'other'; }, 'source_issuer'],
  ['another rollback issuer', value => { value.replay.replayBaselineKeycloak = 'other'; }, 'replay_issuer'],
  ['another database image', value => { value.replay.candidatePostgres = 'other'; }, 'postgres_changed'],
  ['another candidate', value => { value.replay.candidate = C43_ALLOY_REFERENCE; }, 'rehearsal_candidate'],
  ['changed dump', value => { value.replay.dumpSha256 = 'e'.repeat(64); }, 'dump_changed'],
  ['missing table coverage', value => { value.replay.tableCount = 101; }, 'table_coverage'],
  ['missing same-volume rollback', value => {
    value.replay.checks = value.replay.checks.filter(check => check.name !== 'same_volume_rollback_preserves_all_post_upgrade_writes');
  }, 'missing_check'],
  ['changed database identity', value => { value.replay.candidateDatabase.clusterIdentifier = '2'; }, 'database_changed'],
  ['resource leak', value => { value.replay.ownedResourcesRemaining.container = 1; }, 'resource_leak'],
  ['another executed script', value => { value.capture.executedScriptSha256 = '0'.repeat(64); }, 'executed_rehearsal'],
]) test('C48 data acceptance rejects ' + name, () => {
  const value = replayFixture(); mutate(value);
  assert.throws(() => validateAptC48Rehearsal(value.capture, value.replay, value.publication, 'f'.repeat(64)), new RegExp(error));
});

test('C48 refuses another database pair before reading issuer acceptance', async () => {
  for (const readiness of [{}, {postgresC36Acceptance: {}, postgresReference: 'other'},
    {postgresC36Acceptance: {}, postgresReference: C36_POSTGRES, postgresConfigId: 'other'}])
    await assert.rejects(couplePostgresC36WithKeycloakC48(readiness, {}, () => assert.fail('unexpected read')),
      /apt_c48_database_(acceptance_missing|pair|config)/);
});

test('the actual Alloy publication binds the apt overlay to the unchanged C43 binary review', async () => {
  const {createEvidenceReader, validateActivation} = await import('./reviewed-image-defaults.mjs');
  const read = await createEvidenceReader(fileURLToPath(new URL('../../', import.meta.url)));
  const baseline = await read('infrastructure/runtime-security/reviewed-images.json');
  const image = JSON.parse(baseline).images.find(item => item.component === 'alloy');
  const entry = JSON.parse(await read('infrastructure/runtime-security/proofs/c48-alloy/activation-candidate.json'));
  assert.equal(actual.alloy.runtime.parent.reference, C43_ALLOY_REFERENCE);
  assert.equal(await validateActivation(image, entry, baseline, read), entry.reference);
  const migrated = structuredClone(entry);
  migrated.migrationAcceptance = {...entry.aptRefreshAcceptance};
  await assert.rejects(validateActivation(image, migrated, baseline, read), /apt_c48_unexpected_migration/);
  const unbound = structuredClone(entry);
  unbound.aptRefreshAcceptance.sha256 = '0'.repeat(64);
  await assert.rejects(validateActivation(image, unbound, baseline, read), /apt_c48_acceptance_digest/);
});

const rehearsal = new URL('./proofs/c48-keycloak/', import.meta.url);
const rehearsalJson = async name => JSON.parse(await readFile(new URL(name, rehearsal)));
const actualCapture = await rehearsalJson('rehearsal/capture.json'), actualReplay = await rehearsalJson('rehearsal/replay.json');
const actualWindow = await rehearsalJson('rehearsal/capture-window.json');
const captureSha = hash(await readFile(new URL('rehearsal/capture.json', rehearsal)));
const rehearsalSha = hash(await readFile(new URL('executed-rehearsal.py.txt', rehearsal)));
const windowSha = hash(await readFile(new URL('executed-capture-window.py.txt', rehearsal)));

test('actual C48 rehearsal preserves all 102 tables and both rollback paths on C45/C36', () => {
  validateAptC48Rehearsal(actualCapture, actualReplay, actual.keycloak.publication, rehearsalSha);
});

test('the actual capture ran in an internal-only window and the stand was restored exactly', () => {
  validateAptC48CaptureWindow(actualWindow, actualCapture, captureSha, windowSha);
});
for (const [name, mutate, error] of [
  ['a non-internal temporary network', value => { value.temporaryNetwork.internal = false; }, 'window_not_internal'],
  ['a leaked temporary network', value => { value.temporaryNetworkRemaining = 1; }, 'window_network_leak'],
  ['another isolated network', value => { value.isolated.keycloak.networks.push('otziv-prod-local_internal_net'); }, 'window_isolation'],
  ['changed aliases', value => { value.after['keycloak-postgres'].attachment.aliases.pop(); }, 'window_not_restored'],
  ['a changed address', value => { value.after.keycloak.attachment.ipAddress = '172.18.0.99'; }, 'window_not_restored'],
  ['a restarted issuer', value => { value.after.keycloak.restarts = 1; }, 'window_runtime_changed'],
  ['an unhealthy stand', value => { value.stackStatus = 502; }, 'window_restore'],
  ['another capture', value => { value.captureSha256 = '0'.repeat(64); }, 'window_capture'],
  ['a capture outside the window', value => { value.windowClosedAt = value.windowOpenedAt; }, 'window_order'],
  ['another source container', value => { value.before.keycloak.id = '0'.repeat(64); value.after.keycloak.id = '0'.repeat(64); }, 'window_capture_source'],
  ['a remote Docker engine', value => { value.dockerEndpoint = 'ssh'; }, 'window_local_docker'],
  ['another image', value => { value.before['keycloak-postgres'].imageId = 'sha256:' + '0'.repeat(64); }, 'window_image'],
]) test('C48 capture window rejects ' + name, () => {
  const value = structuredClone(actualWindow); mutate(value);
  assert.throws(() => validateAptC48CaptureWindow(value, actualCapture, captureSha, windowSha), new RegExp(error));
});

test('the actual publication binds full C48 issuer acceptance to the accepted C45 parent', async () => {
  const {createEvidenceReader, validateActivation} = await import('./reviewed-image-defaults.mjs');
  const read = await createEvidenceReader(fileURLToPath(new URL('../../', import.meta.url)));
  const baseline = await read('infrastructure/runtime-security/reviewed-images.json');
  const image = JSON.parse(baseline).images.find(item => item.component === 'keycloak');
  const entry = JSON.parse(await read('infrastructure/runtime-security/proofs/c48-keycloak/activation-candidate.json'));
  assert.equal(actual.keycloak.runtime.parent.reference, C45_REFERENCE);
  assert.equal(await validateActivation(image, entry, baseline, read), entry.reference);
  const unbound = structuredClone(entry);
  unbound.migrationAcceptance.sha256 = '0'.repeat(64);
  await assert.rejects(validateActivation(image, unbound, baseline, read), /apt_c48_migration_binding/);
});
