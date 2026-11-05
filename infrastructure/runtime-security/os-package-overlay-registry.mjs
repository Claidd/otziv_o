// Registered official OS package overlays over accepted Keycloak/Alloy images.
// Each overlay is one reviewed JSON spec, bound here by path and SHA-256. The
// registry is pure data loaded synchronously, so release sets, the evidence
// reader, activation gates and staging tools all read the same definition.
// An empty registry changes nothing for existing generations.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';

export const OVERLAY_ROOT = 'infrastructure/runtime-security/';
export const REGISTRY_PATH = OVERLAY_ROOT + 'os-package-overlays.json';
export const SPEC_SCHEMA = 'otziv-os-package-overlay-spec-v1';
const COMPONENTS = new Set(['keycloak', 'alloy']);
const ALLOY_ADJUDICATIONS = new Set(['c43']);
const DIGEST = /^[a-f0-9]{64}$/;
const REFERENCE = /^ghcr\.io\/claidd\/otziv-security@sha256:[a-f0-9]{64}$/;
const CONFIG = /^sha256:[a-f0-9]{64}$/;
const VERSION = /^(?:[0-9]+:)?[A-Za-z0-9.+~-]+$/;
const ARCHIVE = /^https:\/\/(?:security\.ubuntu\.com\/ubuntu|archive\.ubuntu\.com\/ubuntu|deb\.debian\.org\/debian|security\.debian\.org\/debian-security)\/pool\/[a-z0-9/._+-]+\.deb$/;
const RELATIVE = /^(?!\/)(?!.*(?:^|\/)\.\.?(?:\/|$))[A-Za-z0-9._+@:/-]+$/;
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const object = (value, code) => { assert.ok(value && typeof value === 'object' && !Array.isArray(value), code); return value; };
const exact = (value, keys, code) => {
  object(value, code);
  for (const key of Object.keys(value)) assert.ok(keys.includes(key), code + ':' + key);
  return value;
};
const evidencePath = (path, code) => {
  assert.ok(typeof path === 'string' && path.startsWith(OVERLAY_ROOT) && RELATIVE.test(path), code);
  return path;
};

// Debian/Ubuntu package file names omit the epoch.
export const packageFileVersion = version => version.replace(/^[0-9]+:/, '');

export function normalizeOverlaySpec(raw) {
  exact(raw, ['schema', 'generation', 'component', 'status', 'namePrefix', 'advisory', 'distribution', 'parent',
    'recipeComment', 'runtimeUser', 'labels', 'packages', 'packageInventory', 'dpkgConfig', 'protectedPaths',
    'changedPrefixes', 'acceptanceKey', 'rehearsal', 'coupling', 'alloyAdjudication', 'compressedProofs'], 'overlay_spec_fields');
  assert.equal(raw.schema, SPEC_SCHEMA, 'overlay_spec_schema');
  assert.match(raw.generation || '', /^c[1-9][0-9]*$/, 'overlay_spec_generation');
  assert.ok(COMPONENTS.has(raw.component), 'overlay_spec_component');
  assert.ok(['held', 'accepted'].includes(raw.status), 'overlay_spec_status');
  const set = raw.generation + '-' + raw.component;
  const namePrefix = raw.namePrefix ?? 'os-' + raw.generation;
  assert.match(namePrefix, /^[a-z0-9]+(?:-[a-z0-9]+)*$/, 'overlay_spec_name_prefix');
  assert.match(raw.advisory || '', /^(?:CVE-[0-9]{4}-[0-9]{4,}|USN-[0-9]+-[0-9]+|DSA-[0-9]+-[0-9]+)$/, 'overlay_spec_advisory');
  assert.match(raw.distribution || '', /^(?:ubuntu|debian)-[0-9]+(?:\.[0-9]+)?$/, 'overlay_spec_distribution');
  const parent = exact(raw.parent, ['reference', 'configId', 'activation'], 'overlay_spec_parent');
  assert.match(parent.reference || '', REFERENCE, 'overlay_spec_parent_reference');
  assert.match(parent.configId || '', CONFIG, 'overlay_spec_parent_config');
  exact(parent.activation, ['path', 'sha256'], 'overlay_spec_parent_activation');
  evidencePath(parent.activation.path, 'overlay_spec_parent_activation_path');
  assert.match(parent.activation.sha256 || '', DIGEST, 'overlay_spec_parent_activation_digest');
  assert.ok(typeof raw.recipeComment === 'string' && /^[\x20-\x7e]{10,200}$/.test(raw.recipeComment), 'overlay_spec_recipe_comment');
  assert.ok(raw.runtimeUser === null || /^[0-9]+(?::[0-9]+)?$/.test(raw.runtimeUser), 'overlay_spec_runtime_user');
  const labels = object(raw.labels, 'overlay_spec_labels');
  assert.ok(Object.keys(labels).length > 0, 'overlay_spec_labels');
  for (const [key, value] of Object.entries(labels)) {
    assert.ok(/^[a-z0-9][a-z0-9.-]+$/.test(key) && !key.startsWith('com.otziv.') && !key.startsWith('org.opencontainers.'),
      'overlay_spec_label_name');
    assert.ok(typeof value === 'string' && /^[A-Za-z0-9.:+~,=_-]+$/.test(value), 'overlay_spec_label_value');
  }
  assert.ok(Array.isArray(raw.packages) && raw.packages.length > 0, 'overlay_spec_packages');
  const packages = raw.packages.map(item => {
    exact(item, ['name', 'dpkgInfoName', 'version', 'previousVersion', 'sha256', 'url', 'staging'], 'overlay_spec_package');
    assert.match(item.name || '', /^[a-z0-9][a-z0-9+.-]+$/, 'overlay_spec_package_name');
    assert.ok([item.name, item.name + ':amd64'].includes(item.dpkgInfoName), 'overlay_spec_package_info');
    assert.ok(VERSION.test(item.version || '') && VERSION.test(item.previousVersion || ''), 'overlay_spec_package_version');
    assert.notEqual(item.version, item.previousVersion, 'overlay_spec_package_unchanged');
    assert.match(item.sha256 || '', DIGEST, 'overlay_spec_package_digest');
    assert.ok(ARCHIVE.test(item.url || '') && item.url.endsWith(`/${item.name}_${packageFileVersion(item.version)}_amd64.deb`),
      'overlay_spec_package_url');
    const staging = item.staging ?? `/tmp/otziv-${item.name}.deb`;
    assert.match(staging, /^\/tmp\/otziv-[a-z0-9.+-]+\.deb$/, 'overlay_spec_package_staging');
    return Object.freeze({...item, staging});
  });
  for (const key of ['name', 'staging'])
    assert.equal(new Set(packages.map(item => item[key])).size, packages.length, 'overlay_spec_package_duplicate');
  const inventory = exact(raw.packageInventory, ['path', 'sha256', 'schema'], 'overlay_spec_inventory');
  evidencePath(inventory.path, 'overlay_spec_inventory_path');
  assert.match(inventory.sha256 || '', DIGEST, 'overlay_spec_inventory_digest');
  const inventorySchema = inventory.schema ?? 'otziv-official-os-packages-v1';
  assert.match(inventorySchema, /^otziv-official-[a-z]+-packages-v1$/, 'overlay_spec_inventory_schema');
  const dpkgConfig = object(raw.dpkgConfig, 'overlay_spec_dpkg_config');
  assert.ok(Object.hasOwn(dpkgConfig, 'etc/dpkg/dpkg.cfg'), 'overlay_spec_dpkg_config');
  for (const [path, digest] of Object.entries(dpkgConfig))
    assert.ok(/^etc\/dpkg\/dpkg\.cfg(?:\.d\/[A-Za-z0-9_-]+)?$/.test(path) && DIGEST.test(digest), 'overlay_spec_dpkg_config');
  for (const key of ['protectedPaths', 'changedPrefixes']) {
    assert.ok(Array.isArray(raw[key]) && raw[key].length > 0 && raw[key].every(path => RELATIVE.test(path)), 'overlay_spec_' + key);
  }
  const acceptanceKey = raw.acceptanceKey ?? 'packageRefreshAcceptance';
  assert.match(acceptanceKey, /^[a-z][A-Za-z]*RefreshAcceptance$/, 'overlay_spec_acceptance_key');
  let rehearsal = null, coupling = null;
  if (raw.component === 'keycloak') {
    rehearsal = exact(raw.rehearsal, ['sourcePostgres', 'sourcePostgresConfig', 'sourceKeycloak', 'sourceKeycloakConfig',
      'replayPostgres', 'replayPostgresConfig', 'tableCount', 'captureWindow'], 'overlay_spec_rehearsal');
    for (const key of ['sourcePostgres', 'sourceKeycloak', 'replayPostgres']) assert.match(rehearsal[key] || '', REFERENCE, 'overlay_spec_rehearsal_' + key);
    for (const key of ['sourcePostgresConfig', 'sourceKeycloakConfig', 'replayPostgresConfig'])
      assert.match(rehearsal[key] || '', CONFIG, 'overlay_spec_rehearsal_' + key);
    assert.ok(Number.isSafeInteger(rehearsal.tableCount) && rehearsal.tableCount > 0, 'overlay_spec_rehearsal_tables');
    assert.equal(typeof rehearsal.captureWindow, 'boolean', 'overlay_spec_rehearsal_window');
    coupling = exact(raw.coupling, ['postgresReference', 'postgresConfigId', 'historicalKeycloakReference',
      'historicalKeycloakConfigId'], 'overlay_spec_coupling');
    for (const key of ['postgresReference', 'historicalKeycloakReference']) assert.match(coupling[key] || '', REFERENCE, 'overlay_spec_coupling');
    for (const key of ['postgresConfigId', 'historicalKeycloakConfigId']) assert.match(coupling[key] || '', CONFIG, 'overlay_spec_coupling');
    assert.equal(raw.alloyAdjudication, undefined, 'overlay_spec_alloy_adjudication');
  } else {
    assert.equal(raw.rehearsal, undefined, 'overlay_spec_rehearsal_forbidden');
    assert.equal(raw.coupling, undefined, 'overlay_spec_coupling_forbidden');
    assert.ok(ALLOY_ADJUDICATIONS.has(raw.alloyAdjudication), 'overlay_spec_alloy_adjudication');
  }
  const proofRoot = OVERLAY_ROOT + 'proofs/' + set + '/';
  const compressedProofs = object(raw.compressedProofs ?? {}, 'overlay_spec_compressed');
  for (const [path, digest] of Object.entries(compressedProofs))
    assert.ok(evidencePath(path, 'overlay_spec_compressed_path').startsWith(proofRoot) && path.endsWith('.json') && DIGEST.test(digest),
      'overlay_spec_compressed');
  const proofs = ['inspection/inspection.json', 'inspection/runtime.json.gz', 'executed-inspection.py.txt',
    'executed-runtime-inventory.py.txt'];
  if (rehearsal) proofs.push('rehearsal/capture.json', 'rehearsal/replay.json', 'executed-rehearsal.py.txt');
  if (rehearsal?.captureWindow) proofs.push('rehearsal/capture-window.json', 'executed-capture-window.py.txt');
  const context = OVERLAY_ROOT + 'builds/' + set;
  return Object.freeze({...raw, set, namePrefix, schemaPrefix: 'otziv-' + namePrefix, packages: Object.freeze(packages),
    packageInventory: Object.freeze({...inventory, schema: inventorySchema}), acceptanceKey, rehearsal, coupling,
    compressedProofs: Object.freeze({...compressedProofs}), proofs: Object.freeze(proofs), proofRoot,
    manifestPath: OVERLAY_ROOT + 'reviewed-images-' + set + '.json', context, dockerfile: context + '/Dockerfile'});
}

export function overlaysFromRegistry(registryBytes, readSpec) {
  const registry = exact(JSON.parse(Buffer.from(registryBytes).toString('utf8')), ['schema', 'overlays'], 'overlay_registry_fields');
  assert.equal(registry.schema, 'otziv-os-package-overlay-registry-v1', 'overlay_registry_schema');
  assert.ok(Array.isArray(registry.overlays), 'overlay_registry_overlays');
  const overlays = new Map();
  for (const item of registry.overlays) {
    exact(item, ['path', 'sha256'], 'overlay_registry_entry');
    assert.match(item.path || '', /^infrastructure\/runtime-security\/os-overlay-c[1-9][0-9]*-[a-z]+\.json$/, 'overlay_registry_path');
    const bytes = readSpec(item.path);
    assert.equal(sha256(bytes), item.sha256, 'overlay_registry_spec_digest');
    const spec = normalizeOverlaySpec(JSON.parse(Buffer.from(bytes).toString('utf8')));
    assert.equal(item.path, OVERLAY_ROOT + 'os-overlay-' + spec.set + '.json', 'overlay_registry_spec_name');
    assert.ok(!overlays.has(spec.set), 'overlay_registry_duplicate');
    overlays.set(spec.set, spec);
  }
  return overlays;
}

const repositoryFile = path => readFileSync(new URL('../../' + path, import.meta.url));
export const OVERLAYS = overlaysFromRegistry(repositoryFile(REGISTRY_PATH), repositoryFile);
export const overlayForSet = (name, overlays = OVERLAYS) => overlays.get(name);
export const overlayForManifest = (component, path, overlays = OVERLAYS) =>
  [...overlays.values()].find(spec => spec.component === component && spec.manifestPath === path);

export function overlayReleaseSets(existing, overlays = OVERLAYS) {
  const sets = {};
  for (const spec of overlays.values()) {
    assert.ok(!Object.hasOwn(existing, spec.set), 'overlay_registry_release_set_exists');
    sets[spec.set] = {component: spec.component, path: spec.manifestPath, context: spec.context, dockerfile: spec.dockerfile};
  }
  return sets;
}

export function overlayCompressedProofs(existing, overlays = OVERLAYS) {
  const entries = [];
  for (const spec of overlays.values()) for (const [path, digest] of Object.entries(spec.compressedProofs)) {
    assert.ok(!existing.has(path), 'overlay_registry_compressed_proof_exists');
    entries.push([path, digest]);
  }
  return entries;
}
