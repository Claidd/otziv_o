import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {cp, mkdtemp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {gunzipSync} from 'node:zlib';
import {hash} from './ssl-refresh.mjs';
import {OVERLAYS, normalizeOverlaySpec, overlaysFromRegistry, overlayReleaseSets, overlayCompressedProofs,
  packageFileVersion} from './os-package-overlay-registry.mjs';
import {validateOverlayRuntime, validateOverlayRehearsal, validateOverlayCaptureWindow, validateOverlayActivation,
  coupleOverlayKeycloak, scannerVersion} from './os-package-overlay.mjs';

// The C48 apt overlays expressed as template specs. The template must accept the
// real C48 evidence exactly as the dedicated C48 validator does.
const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const here = name => new URL(name, import.meta.url);
const specBytes = component => readFile(here(`./fixtures/os-overlay-c48-${component}.parity.json`));
const specFor = async component => normalizeOverlaySpec(JSON.parse(await specBytes(component)));
const PYTHON = process.platform === 'win32' ? 'python' : 'python3';
const CLI = fileURLToPath(here('./os-package-overlay.py'));
const python = (...args) => {
  const result = spawnSync(PYTHON, ['-B', CLI, ...args], {cwd: ROOT, encoding: 'utf8', timeout: 120_000});
  assert.equal(result.status, 0, result.stderr);
  return result.stdout;
};
const evidence = async component => {
  const prefix = here(`./proofs/c48-${component}/`);
  const json = async name => JSON.parse(await readFile(new URL(name, prefix)));
  return {runtime: JSON.parse(gunzipSync(await readFile(new URL('inspection/runtime.json.gz', prefix)))),
    receipt: await json('inspection/inspection.json'), publication: await json('publication/publication.json'),
    packageBytes: await readFile(here(`./c48-${component}-apt-packages.json`))};
};
const actual = {keycloak: await evidence('keycloak'), alloy: await evidence('alloy')};
const fixture = component => { const {packageBytes, ...rest} = actual[component]; return {...structuredClone(rest), packageBytes}; };

test('the committed registry is valid and registers no overlay yet', () => {
  assert.equal(OVERLAYS.size, 0);
});

test('a spec derives every name, path and proof of its generation', async () => {
  const keycloak = await specFor('keycloak'), alloy = await specFor('alloy');
  assert.equal(keycloak.set, 'c48-keycloak');
  assert.equal(keycloak.schemaPrefix, 'otziv-apt-c48');
  assert.equal(keycloak.manifestPath, 'infrastructure/runtime-security/reviewed-images-c48-keycloak.json');
  assert.equal(keycloak.dockerfile, 'infrastructure/runtime-security/builds/c48-keycloak/Dockerfile');
  assert.equal(keycloak.proofs.length, 9);
  assert.equal(alloy.proofs.length, 4);
  const plain = JSON.parse(await specBytes('alloy'));
  delete plain.namePrefix; delete plain.acceptanceKey; delete plain.packages[0].staging;
  plain.generation = 'c99'; plain.compressedProofs = {};
  const derived = normalizeOverlaySpec(plain);
  assert.equal(derived.schemaPrefix, 'otziv-os-c99');
  assert.equal(derived.acceptanceKey, 'packageRefreshAcceptance');
  assert.equal(derived.packages[0].staging, '/tmp/otziv-libapt-pkg6.0t64.deb');
});

for (const [name, mutate, error] of [
  ['an unknown component', value => { value.component = 'grafana'; }, 'component'],
  ['an unknown field', value => { value.extra = true; }, 'fields'],
  ['an unofficial package source', value => { value.packages[0].url = value.packages[0].url.replace('security.ubuntu.com', 'example.com'); }, 'package_url'],
  ['another package file', value => { value.packages[0].url = value.packages[0].url.replace('libapt', 'libapx'); }, 'package_url'],
  ['an unchanged package', value => { value.packages[0].version = value.packages[0].previousVersion; }, 'package_unchanged'],
  ['shared staging files', value => { value.packages[1].staging = value.packages[0].staging; }, 'package_duplicate'],
  ['an issuer without data rehearsal', value => { delete value.rehearsal; }, 'rehearsal'],
  ['a mutable parent', value => { value.parent.reference = 'quay.io/keycloak/keycloak:26'; }, 'parent_reference'],
  ['a reserved label', value => { value.labels['com.otziv.publication.revision'] = 'x'; }, 'label_name'],
  ['a compressed proof of another generation', value => {
    value.compressedProofs = {'infrastructure/runtime-security/proofs/c45-keycloak/publication/vulnerabilities.json': 'a'.repeat(64)};
  }, 'compressed'],
]) test('a spec rejects ' + name, async () => {
  const value = JSON.parse(await specBytes('keycloak')); mutate(value);
  assert.throws(() => normalizeOverlaySpec(value), new RegExp('overlay_spec_' + error));
});

test('an Alloy spec requires its binary adjudication and forbids issuer rehearsals', async () => {
  const value = JSON.parse(await specBytes('alloy'));
  assert.throws(() => normalizeOverlaySpec({...value, alloyAdjudication: 'c99'}), /overlay_spec_alloy_adjudication/);
  const issuer = JSON.parse(await specBytes('keycloak'));
  assert.throws(() => normalizeOverlaySpec({...value, rehearsal: issuer.rehearsal}), /overlay_spec_rehearsal_forbidden/);
});

test('the registry binds each spec by exact path and digest and never replaces an explicit set', async () => {
  const bytes = await specBytes('keycloak');
  const path = 'infrastructure/runtime-security/os-overlay-c48-keycloak.json';
  const registry = digest => Buffer.from(JSON.stringify({schema: 'otziv-os-package-overlay-registry-v1', overlays: [{path, sha256: digest}]}));
  const overlays = overlaysFromRegistry(registry(hash(bytes)), () => bytes);
  assert.deepEqual([...overlays.keys()], ['c48-keycloak']);
  assert.throws(() => overlaysFromRegistry(registry('0'.repeat(64)), () => bytes), /overlay_registry_spec_digest/);
  assert.throws(() => overlayReleaseSets({'c48-keycloak': {}}, overlays), /overlay_registry_release_set_exists/);
  assert.deepEqual(overlayReleaseSets({}, overlays), {'c48-keycloak': {component: 'keycloak',
    path: 'infrastructure/runtime-security/reviewed-images-c48-keycloak.json',
    context: 'infrastructure/runtime-security/builds/c48-keycloak',
    dockerfile: 'infrastructure/runtime-security/builds/c48-keycloak/Dockerfile'}});
  assert.equal(overlayCompressedProofs(new Map(), overlays).length, 3);
  assert.throws(() => overlayCompressedProofs(new Map(overlayCompressedProofs(new Map(), overlays)), overlays),
    /overlay_registry_compressed_proof_exists/);
  const renamed = Buffer.from(bytes.toString().replace('"generation": "c48"', '"generation": "c99"'));
  assert.throws(() => overlaysFromRegistry(Buffer.from(JSON.stringify({schema: 'otziv-os-package-overlay-registry-v1',
    overlays: [{path, sha256: hash(renamed)}]})), () => renamed), /overlay_registry_spec_name|overlay_spec_compressed/);
});

test('scanner versions follow Trivy epoch, version and revision fields', () => {
  assert.deepEqual(scannerVersion('2.4.15ubuntu0.1'), {epoch: 0, version: '2.4.15ubuntu0.1', release: ''});
  assert.deepEqual(scannerVersion('1.0.9-2ubuntu0.1'), {epoch: 0, version: '1.0.9', release: '2ubuntu0.1'});
  assert.deepEqual(scannerVersion('1:2.38-4ubuntu2.3'), {epoch: 1, version: '2.38', release: '4ubuntu2.3'});
  assert.equal(packageFileVersion('1:2.38-4ubuntu2.3'), '2.38-4ubuntu2.3');
});

for (const component of ['keycloak', 'alloy']) {
  test(`the template accepts the real C48 ${component} filesystem evidence exactly`, async () => {
    const value = fixture(component);
    assert.deepEqual(validateOverlayRuntime(await specFor(component), value.runtime, value.receipt, value.publication, value.packageBytes),
      value.receipt.changedFiles);
  });
}

const library = 'usr/lib/x86_64-linux-gnu/libapt-pkg.so.6.0.0';
for (const [component, name, mutate, error] of [
  ['keycloak', 'another parent', value => { value.runtime.parent.reference = 'other'; }, 'overlay_parent'],
  ['alloy', 'a changed entrypoint', value => { value.runtime.candidate.configuration.Entrypoint = ['/bin/sh']; }, 'unrelated_configuration'],
  ['keycloak', 'wrong library bytes', value => { value.runtime.candidate.inventory[library].sha256 = 'wrong'; }, 'official_payload'],
  ['alloy', 'an Alloy binary replacement', value => { value.runtime.candidate.inventory['usr/bin/alloy'].sha256 = 'wrong'; }, 'unrelated_payload'],
  ['keycloak', 'a provider replacement', value => {
    value.runtime.candidate.inventory['opt/keycloak/providers/otziv-security-generation.jar'].sha256 = 'wrong';
  }, 'unrelated_payload'],
  ['alloy', 'a concealed changed file', value => { value.receipt.changedFiles.pop(); }, 'changed_files'],
  ['keycloak', 'another package inventory', value => { value.packageBytes = Buffer.concat([value.packageBytes, Buffer.from(' ')]); }, 'inventory_digest'],
]) test(`the template rejects ${name} (${component})`, async () => {
  const value = fixture(component), spec = await specFor(component); mutate(value);
  assert.throws(() => validateOverlayRuntime(spec, value.runtime, value.receipt, value.publication, value.packageBytes),
    new RegExp(error));
});

test('the template accepts the real C48 rehearsal and capture window and rejects a leaked window', async () => {
  const spec = await specFor('keycloak'), prefix = here('./proofs/c48-keycloak/');
  const raw = async name => readFile(new URL(name, prefix));
  const capture = JSON.parse(await raw('rehearsal/capture.json')), replay = JSON.parse(await raw('rehearsal/replay.json'));
  const window = JSON.parse(await raw('rehearsal/capture-window.json'));
  validateOverlayRehearsal(spec, capture, replay, actual.keycloak.publication, hash(await raw('executed-rehearsal.py.txt')));
  const captureSha = hash(await raw('rehearsal/capture.json')), windowSha = hash(await raw('executed-capture-window.py.txt'));
  validateOverlayCaptureWindow(spec, window, capture, captureSha, windowSha);
  assert.throws(() => validateOverlayCaptureWindow(spec, {...window, temporaryNetworkRemaining: 1}, capture, captureSha, windowSha),
    /overlay_window_network_leak/);
  const rehearsalSha = hash(await raw('executed-rehearsal.py.txt'));
  assert.throws(() => validateOverlayRehearsal(spec, capture, {...replay, tableCount: 101}, actual.keycloak.publication,
    rehearsalSha), /overlay_table_coverage/);
});

for (const component of ['keycloak', 'alloy']) {
  test(`the template binds the full C48 ${component} acceptance to its accepted parent lineage`, async () => {
    const {createEvidenceReader, validateActivation} = await import('./reviewed-image-defaults.mjs');
    const read = await createEvidenceReader(ROOT);
    const spec = await specFor(component);
    const entry = JSON.parse(await read(spec.proofRoot + 'activation-candidate.json'));
    const accepted = await validateOverlayActivation(spec, actual[component].publication, entry, read, validateActivation);
    assert.equal(accepted.reference, entry.reference);
    const unbound = structuredClone(entry);
    unbound[spec.acceptanceKey].sha256 = '0'.repeat(64);
    await assert.rejects(validateOverlayActivation(spec, actual[component].publication, unbound, read, validateActivation),
      /overlay_(acceptance_digest|migration_binding)/);
  });
}

test('issuer coupling refuses another database pair or a held overlay before reading evidence', async () => {
  const spec = await specFor('keycloak');
  for (const readiness of [{}, {postgresC36Acceptance: {}, postgresReference: 'other'}])
    await assert.rejects(coupleOverlayKeycloak(spec, readiness, {}, () => assert.fail('unexpected read')),
      /overlay_database_(acceptance_missing|pair)/);
  const held = normalizeOverlaySpec({...JSON.parse(await specBytes('keycloak')), status: 'held'});
  const readiness = {postgresC36Acceptance: {}, postgresReference: spec.coupling.postgresReference,
    postgresConfigId: spec.coupling.postgresConfigId, postgresC36Pair: {keycloakReference: spec.coupling.historicalKeycloakReference,
      keycloakConfigId: spec.coupling.historicalKeycloakConfigId}};
  await assert.rejects(coupleOverlayKeycloak(held, readiness, {}, () => assert.fail('unexpected read')),
    /overlay_runtime_and_data_acceptance_required/);
});

for (const component of ['keycloak', 'alloy']) {
  test(`the generator reproduces the exact C48 ${component} recipe and manifest`, () => {
    python('render', '--spec', fileURLToPath(here(`./fixtures/os-overlay-c48-${component}.parity.json`)), '--check');
  });

  test(`the Python inspector reproduces the recorded C48 ${component} comparison`, () => {
    const changed = JSON.parse(python('verify-runtime', '--spec', fileURLToPath(here(`./fixtures/os-overlay-c48-${component}.parity.json`)),
      '--runtime', fileURLToPath(here(`./proofs/c48-${component}/inspection/runtime.json.gz`)),
      '--commit', actual[component].publication.commit));
    assert.deepEqual(changed, actual[component].receipt.changedFiles);
  });

  test(`acceptance generation reproduces the C48 ${component} acceptance and activation entry`, async () => {
    const root = await mkdtemp(join(tmpdir(), 'otziv-overlay-accept-'));
    try {
      const proofs = `infrastructure/runtime-security/proofs/c48-${component}`;
      await cp(join(ROOT, proofs), join(root, proofs), {recursive: true});
      python('accept', '--spec', fileURLToPath(here(`./fixtures/os-overlay-c48-${component}.parity.json`)), '--root', root);
      for (const name of ['acceptance.json', 'activation-candidate.json'])
        assert.equal((await readFile(join(root, proofs, name))).toString(), (await readFile(join(ROOT, proofs, name))).toString(), name);
    } finally { await rm(root, {recursive: true, force: true}); }
  });
}

test('wiring registers the spec, adds one workflow option and exact line-ending rules, idempotently', async () => {
  const root = await mkdtemp(join(tmpdir(), 'otziv-overlay-wire-'));
  try {
    const prefix = 'infrastructure/runtime-security/';
    await mkdir(join(root, prefix), {recursive: true});
    await mkdir(join(root, '.github/workflows'), {recursive: true});
    for (const path of [prefix + 'os-package-overlays.json', '.github/workflows/quality-gates.yml', '.gitattributes'])
      await cp(join(ROOT, path), join(root, path));
    const spec = JSON.parse(await specBytes('alloy'));
    spec.generation = 'c99'; spec.compressedProofs = {};
    const specPath = prefix + 'os-overlay-c99-alloy.json';
    await writeFile(join(root, specPath), JSON.stringify(spec, null, 2) + '\n');
    const before = (await readFile(join(root, '.github/workflows/quality-gates.yml'), 'utf8')).replaceAll('\r\n', '\n');
    for (let round = 0; round < 2; round++) python('wire', '--spec', join(root, specPath), '--root', root);
    const workflow = await readFile(join(root, '.github/workflows/quality-gates.yml'), 'utf8');
    assert.equal(workflow, before.replace('          - c48-alloy\n  pull_request:\n', '          - c48-alloy\n          - c99-alloy\n  pull_request:\n'));
    const registry = JSON.parse(await readFile(join(root, prefix + 'os-package-overlays.json')));
    assert.deepEqual(registry.overlays, [{path: specPath, sha256: hash(await readFile(join(root, specPath)))}]);
    const attributes = await readFile(join(root, '.gitattributes'), 'utf8');
    assert.equal(attributes.split('# C99-ALLOY official OS package overlay').length, 2);
    assert.match(attributes, /infrastructure\/runtime-security\/proofs\/c99-alloy\/\*\* -text\n$/);
  } finally { await rm(root, {recursive: true, force: true}); }
});

test('the issuer rehearsal refuses an Alloy spec before touching Docker or private data', () => {
  const result = spawnSync(PYTHON, ['-B', fileURLToPath(here('./os-package-overlay-rehearsal.py')),
    '--spec', fileURLToPath(here('./fixtures/os-overlay-c48-alloy.parity.json')), 'capture',
    '--private-directory', join(tmpdir(), 'otziv-overlay-never-created'), '--output', join(tmpdir(), 'otziv-overlay-never-created-out')],
  {cwd: ROOT, encoding: 'utf8', timeout: 60_000});
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /keycloak_overlay_spec_required/);
});

test('compression keeps exact public payload bytes and records their digest in the spec', async () => {
  const root = await mkdtemp(join(tmpdir(), 'otziv-overlay-compress-'));
  try {
    const prefix = 'infrastructure/runtime-security/';
    const spec = JSON.parse(await specBytes('alloy'));
    spec.generation = 'c99'; spec.compressedProofs = {};
    const specPath = join(root, prefix + 'os-overlay-c99-alloy.json');
    const proof = prefix + 'proofs/c99-alloy/publication/vulnerabilities.json';
    await mkdir(join(root, prefix + 'proofs/c99-alloy/publication'), {recursive: true});
    await writeFile(specPath, JSON.stringify(spec, null, 2) + '\n');
    const payload = Buffer.from('{"public":"retained scanner report bytes"}\n');
    await writeFile(join(root, proof), payload);
    python('compress', '--spec', specPath, '--root', root, proof);
    assert.deepEqual(gunzipSync(await readFile(join(root, proof + '.gz'))), payload);
    await assert.rejects(readFile(join(root, proof)), /ENOENT/);
    const updated = normalizeOverlaySpec(JSON.parse(await readFile(specPath)));
    assert.deepEqual(updated.compressedProofs, {[proof]: hash(payload)});
  } finally { await rm(root, {recursive: true, force: true}); }
});

test('activation switches only the current parent to the accepted candidate in the index and Compose', async () => {
  const root = await mkdtemp(join(tmpdir(), 'otziv-overlay-activate-'));
  try {
    const prefix = 'infrastructure/runtime-security/', proofs = prefix + 'proofs/c48-keycloak/';
    const spec = await specFor('keycloak');
    const candidate = JSON.parse(await readFile(join(ROOT, proofs + 'activation-candidate.json')));
    const parent = await readFile(join(ROOT, prefix + 'c48-parent-keycloak.json'));
    const index = JSON.parse(await readFile(join(ROOT, prefix + 'reviewed-image-activations.json')));
    index.images = index.images.map(item => item.component === 'keycloak' ? JSON.parse(parent) : item);
    await mkdir(join(root, proofs), {recursive: true});
    await writeFile(join(root, proofs + 'activation-candidate.json'), JSON.stringify(candidate, null, 2) + '\n');
    await writeFile(join(root, prefix + 'reviewed-image-activations.json'), JSON.stringify(index, null, 2) + '\n');
    const compose = ['compose.yaml', 'compose.prod-local.yaml', 'docker-compose.yaml'];
    for (const path of compose) {
      const text = (await readFile(join(ROOT, path), 'utf8')).replaceAll('\r\n', '\n');
      await writeFile(join(root, path), text.replace(candidate.reference, spec.parent.reference));
    }
    assert.equal(python('activate', '--spec', fileURLToPath(here('./fixtures/os-overlay-c48-keycloak.parity.json')), '--root', root).trim(),
      candidate.reference);
    for (const path of [...compose, prefix + 'reviewed-image-activations.json'])
      assert.equal((await readFile(join(root, path), 'utf8')), (await readFile(join(ROOT, path), 'utf8')).replaceAll('\r\n', '\n'), path);
    const again = spawnSync(PYTHON, ['-B', CLI, 'activate', '--spec', fileURLToPath(here('./fixtures/os-overlay-c48-keycloak.parity.json')),
      '--root', root], {encoding: 'utf8'});
    assert.notEqual(again.status, 0);
    assert.match(again.stderr, /overlay_activation_parent_not_current/);
  } finally { await rm(root, {recursive: true, force: true}); }
});
