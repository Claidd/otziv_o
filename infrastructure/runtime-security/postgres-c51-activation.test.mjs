import assert from 'node:assert/strict';
import test from 'node:test';
import {readdir, readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {createEvidenceReader, coupleRegisteredKeycloak} from './reviewed-image-defaults.mjs';
import {validatePostgresC36Transition, C35_KEYCLOAK_REFERENCE, C35_KEYCLOAK_CONFIG} from './postgres-c36-activation.mjs';
import {validatePostgresC51Acceptance, validatePostgresC51Rehearsal, validatePostgresC51CaptureWindow,
  validatePostgresC51Transition, couplePostgresC51WithKeycloakC48, C36_ACTIVATION_SHA256, C48_KEYCLOAK_REFERENCE,
  C48_KEYCLOAK_CONFIG, C51_ACCEPTANCE, C51_MANIFEST, C51_PROOF} from './postgres-c51-activation.mjs';
import {PARENT_REFERENCE, PARENT_CONFIG_ID, hash} from './postgres-c51-adjudication.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = await createEvidenceReader(root);
const R = 'infrastructure/runtime-security/';
const json = async path => JSON.parse(await read(path));
const bytes = value => Buffer.from(JSON.stringify(value, null, 2) + '\n');
const baseline = await read(R + 'reviewed-images.json');
const image = JSON.parse(baseline).images.find(item => item.component === 'postgres');
const draft = (await json(C51_PROOF + 'activation-draft.json')).entry;
const publication = await json(draft.publication.path);
const C45_REFERENCE = 'ghcr.io/claidd/otziv-security@sha256:6c6f398170954330c03dc2c16db1b96c5ebf2cb29546fe59542b6dcc7b60ee5e';
const index = digest => 'sha256:' + digest.split('@sha256:')[1];
const scripts = {rehearsal: hash(await read(R + 'postgres-c51-rehearsal.py')),
  window: hash(await read(R + 'postgres-c51-capture-window.py'))};

// The retained C48 capture window and C36 replay share the C51 record layout. Rebind
// their identities to the C36 database and active C48 issuer for unit fixtures.
async function rehearsal() {
  const capture = await json(R + 'proofs/c48-keycloak/rehearsal/capture.json');
  const window = await json(R + 'proofs/c48-keycloak/rehearsal/capture-window.json');
  const replay = await json(R + 'proofs/c36-postgres/rehearsal/replay.json');
  for (const record of [capture, replay]) Object.assign(record, {schema: 'otziv-postgres-c51-rehearsal-v1',
    sourcePostgres: PARENT_REFERENCE, sourcePostgresConfig: PARENT_CONFIG_ID, keycloak: C48_KEYCLOAK_REFERENCE,
    keycloakConfig: C48_KEYCLOAK_CONFIG, executedScriptSha256: scripts.rehearsal});
  delete capture.replayBaselinePostgres; delete capture.replayBaselineKeycloak; delete capture.replayBaselineKeycloakConfig;
  capture.sourceBefore.containers.keycloak.imageId = index(C48_KEYCLOAK_REFERENCE);
  Object.assign(replay, {candidate: C48_KEYCLOAK_REFERENCE, candidateLocalId: C48_KEYCLOAK_CONFIG,
    candidatePostgres: draft.reference, candidatePostgresLocalId: publication.imageId, dumpSha256: capture.dumpSha256});
  const owner = window.temporaryNetwork.name.slice(-12);
  Object.assign(window, {schema: 'otziv-postgres-c51-capture-window-v1', executedScriptSha256: scripts.window,
    temporaryNetwork: {name: 'otziv-postgres-c51-capture-' + owner, label: 'com.otziv.postgres-c51-capture.owner=' + owner,
      internal: true}});
  for (const service of ['keycloak', 'keycloak-postgres']) window.isolated[service].networks = [window.temporaryNetwork.name];
  for (const side of [window.before, window.after]) side.keycloak.imageId = index(C48_KEYCLOAK_REFERENCE);
  const value = {reference: draft.reference, imageConfigId: publication.imageId,
    capturePath: C51_PROOF + 'rehearsal/capture.json', replayPath: C51_PROOF + 'rehearsal/replay.json',
    captureWindowPath: C51_PROOF + 'rehearsal/capture-window.json', files: {},
    executedSources: {[R + 'postgres-c51-rehearsal.py']: scripts.rehearsal, [R + 'postgres-c51-capture-window.py']: scripts.window}};
  const documents = () => {
    const captured = bytes(capture);
    window.captureSha256 = value.files[value.capturePath] = hash(captured);
    return new Map([[value.capturePath, captured], [value.replayPath, bytes(replay)], [value.captureWindowPath, bytes(window)]]);
  };
  const reader = async path => documents().get(path) ?? read(path);
  return {value, capture, replay, window, read: reader};
}

test('C51 transition proof requires the candidate run, exact rollback checks and the bound capture window', async () => {
  const x = await rehearsal();
  assert.equal((await validatePostgresC51Rehearsal(x.value, x.read)).candidatePostgres, draft.reference);
  x.replay.checks.find(check => check.name === 'same_volume_all_tables_exact').passed = false;
  await assert.rejects(validatePostgresC51Rehearsal(x.value, x.read), /postgres_c51_rehearsal_incomplete/);
});

for (const [name, mutate, error] of [
  ['the earlier C36 schema', x => { x.replay.schema = 'otziv-postgres-c36-rehearsal-v1'; }, /rehearsal_schema/],
  ['the earlier C35 issuer', x => { x.replay.keycloak = C35_KEYCLOAK_REFERENCE; }, /rehearsal_issuer/],
  ['the C45 issuer during capture', x => { x.capture.keycloak = C45_REFERENCE; }, /rehearsal_issuer/],
  ['a replay under another issuer', x => { x.replay.candidate = C45_REFERENCE; }, /candidate_keycloak/],
  ['another database candidate', x => { x.replay.candidatePostgres = PARENT_REFERENCE; }, /rehearsal_candidate/],
  ['another database source', x => { x.capture.sourcePostgres = draft.reference; }, /rehearsal_parent/],
  ['a missing schema-stability check', x => {
    x.replay.checks = x.replay.checks.filter(check => check.name !== 'library_patch_no_schema_migration');
  }, /missing_check:library_patch_no_schema_migration/],
  ['a missing same-volume rollback', x => {
    x.replay.checks = x.replay.checks.filter(check => check.name !== 'same_volume_rollback_preserves_all_post_upgrade_writes');
  }, /missing_check:same_volume_rollback/],
  ['a missing fresh rollback restore', x => {
    x.replay.checks = x.replay.checks.filter(check => check.name !== 'fresh_rollback_restore_preserves_all_post_upgrade_writes');
  }, /missing_check:fresh_rollback/],
  ['a duplicated check name', x => { x.replay.checks.push({name: 'same_volume_all_tables_exact', passed: true}); }, /missing_check/],
  ['an incomplete capture', x => { x.capture.checks = x.capture.checks.filter(check => check.name !== 'complete_custom_dump'); },
    /missing_check:complete_custom_dump/],
  ['a changed dump', x => { x.replay.dumpSha256 = 'e'.repeat(64); }, /rehearsal_dump/],
  ['reduced table coverage', x => { x.replay.tableCount = 101; }, /table_coverage/],
  ['reduced stable coverage', x => { x.replay.stableTableCount = 101; }, /stable_coverage/],
  ['another server version', x => { x.replay.candidateDatabase.serverVersion = '17.12'; }, /database_changed:serverVersion/],
  ['another encoding', x => { x.replay.candidateDatabase.encoding = 'SQL_ASCII'; }, /database_changed:encoding/],
  ['another locale', x => { x.replay.candidateDatabase.locale = {...x.replay.candidateDatabase.locale, ctype: 'C'}; },
    /database_changed:locale/],
  ['another cluster', x => { x.replay.candidateDatabase.clusterIdentifier = '1'; }, /database_changed:clusterIdentifier/],
  ['changed configuration', x => { x.replay.candidateDatabase.configSha256 = '0'.repeat(64); }, /database_changed:configSha256/],
  ['changed host authentication', x => { x.replay.candidateDatabase.hbaSha256 = '0'.repeat(64); }, /database_changed:hbaSha256/],
  ['a non-17.11 source', x => { x.replay.sourceDatabase.serverVersion = '17.10'; }, /server_version/],
  ['production writes', x => { x.replay.productionWrites = true; }, /production_write/],
  ['published ports', x => { x.capture.publishedPorts = 1; }, /published_ports/],
  ['a resource leak', x => { x.replay.ownedResourcesRemaining.volume = 1; }, /rehearsal_cleanup/],
  ['another executed rehearsal', x => { x.replay.executedScriptSha256 = '0'.repeat(64); }, /rehearsal_script/],
  ['a non-internal capture window', x => { x.window.temporaryNetwork.internal = false; }, /window_not_internal/],
  ['a capture outside the window', x => { x.window.windowClosedAt = x.window.windowOpenedAt; }, /window_order/],
  ['a restarted issuer during capture', x => { x.window.after.keycloak.restarts = 1; }, /window_runtime_changed/],
  ['a changed address after capture', x => { x.window.after['keycloak-postgres'].attachment.ipAddress = '172.18.0.99'; },
    /window_not_restored/],
  ['a window around another issuer', x => {
    x.window.before.keycloak.imageId = x.window.after.keycloak.imageId = index(C45_REFERENCE);
  }, /window_image:keycloak/],
  ['a window from another capture script', x => { x.window.executedScriptSha256 = '0'.repeat(64); }, /window_executed_script/],
  ['an unbound capture window script', x => { delete x.value.executedSources[R + 'postgres-c51-capture-window.py']; },
    /window_source_missing/],
]) test('C51 rehearsal rejects ' + name, async () => {
  const x = await rehearsal();
  mutate(x);
  await assert.rejects(validatePostgresC51Rehearsal(x.value, x.read), error);
});

test('the capture window must bind the exact capture bytes', async () => {
  const x = await rehearsal();
  await validatePostgresC51Rehearsal(x.value, x.read);
  const capture = JSON.parse(await x.read(x.value.capturePath)), window = JSON.parse(await x.read(x.value.captureWindowPath));
  assert.throws(() => validatePostgresC51CaptureWindow(window, capture, '0'.repeat(64), scripts.window), /window_capture/);
});

// Acceptance fixture over the real published C51 evidence with in-memory rehearsal records.
async function acceptance() {
  const x = await rehearsal();
  const directory = C51_PROOF + 'publication/';
  const files = {};
  for (const name of (await readdir(new URL('./proofs/c51-postgres/publication/', import.meta.url))).sort())
    files[directory + name] = hash(await read(directory + name));
  for (const path of [x.value.capturePath, x.value.replayPath, x.value.captureWindowPath])
    files[path] = hash(await x.read(path));
  const executedSources = {...x.value.executedSources};
  for (const name of ['postgres-c51-inspection.py', 'ssl-refresh-inspection.py', 'postgres-c51-adjudication.mjs'])
    executedSources[R + name] = hash(await read(R + name));
  const value = {schema: 'otziv-postgres-c51-acceptance-v1', result: 'PASS', reference: draft.reference,
    imageConfigId: publication.imageId, parentReference: PARENT_REFERENCE, parentConfigId: PARENT_CONFIG_ID,
    commit: draft.commit, run: draft.run, attempt: draft.attempt,
    pair: {postgres: {reference: draft.reference, imageConfigId: publication.imageId},
      keycloak: {reference: C48_KEYCLOAK_REFERENCE, imageConfigId: C48_KEYCLOAK_CONFIG}},
    runtimePath: directory + 'vulnerabilities.runtime-c51.json.gz', capturePath: x.value.capturePath,
    replayPath: x.value.replayPath, captureWindowPath: x.value.captureWindowPath, files, executedSources};
  const entry = structuredClone(draft);
  const seal = () => {
    const sealed = bytes(value);
    entry.postgresC51Acceptance = {path: C51_ACCEPTANCE, sha256: hash(sealed)};
    entry.databaseTransition = {...entry.postgresC51Acceptance};
    return sealed;
  };
  seal();
  const reader = async path => path === C51_ACCEPTANCE ? seal() : x.read(path);
  return {...x, accepted: value, entry, seal, read: reader};
}

test('C51 acceptance binds the published scan, its inspectors, the rehearsal and the active C48 pair', async () => {
  const x = await acceptance();
  const value = await validatePostgresC51Acceptance(x.entry, x.read);
  assert.equal(value.reference, draft.reference);
  await validatePostgresC51Rehearsal(value, x.read);
});

for (const [name, mutate, error] of [
  ['an earlier issuer pair', x => { x.accepted.pair.keycloak = {reference: C35_KEYCLOAK_REFERENCE, imageConfigId: C35_KEYCLOAK_CONFIG}; },
    /acceptance_pair/],
  ['another parent', x => { x.accepted.parentReference = C35_KEYCLOAK_REFERENCE; }, /acceptance_parent/],
  ['another publication run', x => { x.accepted.run = '1'; }, /acceptance_identity:run/],
  ['an unbound capture window', x => { delete x.accepted.files[x.accepted.captureWindowPath]; }, /file_missing/],
  ['an unbound rehearsal script', x => { delete x.accepted.executedSources[R + 'postgres-c51-rehearsal.py']; }, /source_missing/],
  ['changed evidence', x => { x.accepted.files[x.accepted.replayPath] = '0'.repeat(64); }, /evidence_changed/],
  ['an evidence path outside the proof scope', x => { x.accepted.files['../outside.json'] = '0'.repeat(64); }, /unsafe_path/],
  ['another inspector', x => { x.accepted.executedSources[R + 'postgres-c51-inspection.py'] = hash(Buffer.from('other')); },
    /evidence_changed/],
  ['a relocated replay', x => { x.accepted.replayPath = C51_PROOF + 'replay.json'; }, /replay_path/],
]) test('C51 acceptance rejects ' + name, async () => {
  const x = await acceptance();
  mutate(x);
  x.seal();
  await assert.rejects(validatePostgresC51Acceptance(x.entry, x.read), error);
});

test('C51 transition cannot activate without its own bound acceptance', async () => {
  const entry = {component: 'postgres', reference: draft.reference, manifest: {path: C51_MANIFEST}};
  await assert.rejects(validatePostgresC51Acceptance(entry, async () => Buffer.alloc(0)), /postgres_c51_acceptance_missing/);
  entry.postgresC51Acceptance = {path: R + 'proofs/c36-postgres/acceptance.json', sha256: 'd'.repeat(64)};
  await assert.rejects(validatePostgresC51Acceptance(entry, async () => Buffer.alloc(0)), /postgres_c51_acceptance_missing/);
  entry.postgresC51Acceptance = {path: C51_ACCEPTANCE, sha256: 'd'.repeat(64)};
  await assert.rejects(validatePostgresC51Acceptance(entry, async () => Buffer.alloc(0)), /postgres_c51_transition_not_bound/);
  await assert.rejects(validatePostgresC51Acceptance({...entry, manifest: {path: R + 'reviewed-images-c36-postgres.json'}},
    async () => Buffer.alloc(0)), /postgres_c51_acceptance_manifest/);
});

test('frozen C36 parent is the replaced activation and still replays its C23 -> C36 transition', async () => {
  const parentBytes = await read(R + 'c51-parent-postgres.json');
  assert.equal(hash(parentBytes), C36_ACTIVATION_SHA256);
  const parent = JSON.parse(parentBytes);
  assert.equal(parent.reference, PARENT_REFERENCE);
  assert.equal(parent.manifest.path, R + 'reviewed-images-c36-postgres.json');
  const readiness = await validatePostgresC36Transition(parent, image, read);
  assert.equal(readiness.postgresReference, PARENT_REFERENCE);
  assert.equal(readiness.postgresConfigId, PARENT_CONFIG_ID);
  assert.equal(readiness.mode, 'COORDINATED_CANDIDATE_PREPARATION');
  // The active C48 issuer keeps its own proof on the frozen C36 database.
  const issuer = (await json(R + 'reviewed-image-activations.json')).images.find(item => item.component === 'keycloak');
  const coupled = await coupleRegisteredKeycloak(readiness, issuer, read);
  assert.equal(coupled.requiredKeycloakReference, C48_KEYCLOAK_REFERENCE);
  assert.equal(coupled.postgresReference, PARENT_REFERENCE);
});

test('C51 coupling refuses another database pair or issuer before reading issuer acceptance', async () => {
  const unreadable = async () => assert.fail('unexpected_read');
  const issuer = (await json(R + 'reviewed-image-activations.json')).images.find(item => item.component === 'keycloak');
  const pair = {keycloakReference: C48_KEYCLOAK_REFERENCE, keycloakConfigId: C48_KEYCLOAK_CONFIG};
  for (const [readiness, entry, error] of [
    [{}, issuer, /postgres_c51_coupling_acceptance_missing/],
    [{postgresC36Acceptance: {}, postgresC36Pair: pair}, issuer, /postgres_c51_coupling_acceptance_missing/],
    [{postgresC51Acceptance: {}, postgresC51Pair: {keycloakReference: C35_KEYCLOAK_REFERENCE, keycloakConfigId: C35_KEYCLOAK_CONFIG}},
      issuer, /postgres_c51_coupling_issuer_pair/],
    [{postgresC51Acceptance: {}, postgresC51Pair: pair}, {...issuer, reference: C45_REFERENCE}, /postgres_c51_coupling_registry_issuer/],
    [{postgresC51Acceptance: {}, postgresC51Pair: pair}, {...issuer, manifest: {path: R + 'reviewed-images-c45-keycloak.json'}},
      /postgres_c51_coupling_registry_manifest/],
  ]) await assert.rejects(couplePostgresC51WithKeycloakC48(readiness, entry, unreadable, unreadable), error);
  // The shared coupler routes a C51 readiness to its own pairing before any issuer generation.
  await assert.rejects(coupleRegisteredKeycloak({postgresC51Acceptance: {}, postgresC51Pair: {}}, issuer, unreadable),
    /postgres_c51_coupling_issuer_pair/);
});

test('C51 coupling with the active C48 issuer binds the issuer acceptance', async () => {
  const issuer = (await json(R + 'reviewed-image-activations.json')).images.find(item => item.component === 'keycloak');
  assert.equal(issuer.manifest.path, R + 'reviewed-images-c48-keycloak.json');
  const readiness = {component: 'postgres', mode: 'COORDINATED_CANDIDATE_PREPARATION', postgresC51Acceptance: {},
    postgresC51Pair: {keycloakReference: C48_KEYCLOAK_REFERENCE, keycloakConfigId: C48_KEYCLOAK_CONFIG}};
  const coupled = await coupleRegisteredKeycloak(readiness, issuer, read);
  assert.equal(coupled.requiredKeycloakReference, C48_KEYCLOAK_REFERENCE);
  assert.equal(coupled.requiredKeycloakConfigId, C48_KEYCLOAK_CONFIG);
  assert.deepEqual({path: coupled.acceptancePath, sha256: coupled.proofSha256}, issuer.migrationAcceptance);
});

test('the transition requires the exact frozen C36 parent', async () => {
  const x = await acceptance();
  const reader = async path => path === R + 'c51-parent-postgres.json' ? Buffer.from('{}') : x.read(path);
  await assert.rejects(validatePostgresC51Transition(x.entry, image, reader), /postgres_c51_parent_activation_changed/);
});

// Retained C51 evidence: the published image, its own rehearsal and capture window.
const candidate = await json(C51_PROOF + 'activation-candidate.json');

test('actual C51 acceptance binds all three rehearsal records and both executed scripts', async () => {
  const value = await validatePostgresC51Acceptance(candidate, read);
  for (const name of ['capture.json', 'replay.json', 'capture-window.json'])
    assert.equal(value.files[C51_PROOF + 'rehearsal/' + name], hash(await read(C51_PROOF + 'rehearsal/' + name)));
  for (const name of ['postgres-c51-rehearsal.py', 'postgres-c51-capture-window.py'])
    assert.equal(value.executedSources[R + name], hash(await read(R + name)));
  const replay = await validatePostgresC51Rehearsal(value, read);
  assert.equal(replay.checks.length, 54);
  assert.equal(replay.candidatePostgresLocalId, index(draft.reference));
});

test('actual C51 activation replays the C36 lineage and couples to the active C48 issuer only', async () => {
  const active = (await json(R + 'reviewed-image-activations.json')).images;
  assert.deepEqual(active.find(item => item.component === 'postgres'), candidate);
  const {validateActivation} = await import('./reviewed-image-defaults.mjs');
  assert.equal(await validateActivation(image, candidate, baseline, read), candidate.reference);
  const readiness = await validatePostgresC51Transition(candidate, image, read);
  assert.equal(readiness.postgresReference, candidate.reference);
  assert.equal(readiness.ordinaryDeploymentUpgradeAuthorized, false);
  const coupled = await coupleRegisteredKeycloak(readiness, active.find(item => item.component === 'keycloak'), read);
  assert.equal(coupled.requiredKeycloakReference, C48_KEYCLOAK_REFERENCE);
  const previous = JSON.parse(await read(R + 'c48-parent-keycloak.json'));
  await assert.rejects(coupleRegisteredKeycloak(readiness, previous, read), /postgres_c51_coupling_registry_issuer/);
  const unbound = structuredClone(candidate);
  unbound.databaseTransition = {...unbound.databaseTransition, sha256: '0'.repeat(64)};
  await assert.rejects(validateActivation(image, unbound, baseline, read), /postgres_c51_transition_not_bound/);
});
