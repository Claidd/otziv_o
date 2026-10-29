// Exact official Ubuntu Brotli overlay; the accepted C43 issuer/DB lineage remains bound.
import assert from 'node:assert/strict';
import {isDeepStrictEqual} from 'node:util';
import {dirname} from 'node:path';
import {gunzipSync} from 'node:zlib';
import {hash} from './ssl-refresh.mjs';
import {C35_REFERENCE, C35_CONFIG, C36_POSTGRES, C36_POSTGRES_CONFIG,
  validateGuavaC43Rehearsal} from './guava-c43-activation.mjs';

export const C43_REFERENCE = 'ghcr.io/claidd/otziv-security@sha256:6df30e829eec786f9f7b51f56a5e0c10451e96b4ff086157129c649df80632f1';
export const C43_CONFIG = 'sha256:8ff19b507872c8b27b444184c93bae61a2835ed889b410324e7436923d57c240';
export const BROTLI_VERSION = '1.0.9-2ubuntu0.1';
export const PACKAGE_SHA256 = '908295e77a65219a62114c3c79690a3c99712274d1317c3ba47ffa2f8ea52ca5';
export const PACKAGE_INVENTORY_SHA256 = '8ea4699bd36f21de6174106ffa18fd76fb6fab34a3d34deeb3bdd61b4fd62b0a';
const ROOT = 'infrastructure/runtime-security/';
const PROOF = ROOT + 'proofs/c45-keycloak/';
const PARENT = ROOT + 'c45-parent-keycloak.json';
const PARENT_SHA256 = 'dae1486f4bfa76b45b2aebf5d2d4c90b9f5a4c4f2e9111aa8b0f4507f98425e7';
const PACKAGE = ROOT + 'c45-brotli-package.json';
const CONTROL_PREFIX = 'var/lib/dpkg/info/libbrotli1:amd64.';
const METADATA = new Set(['etc/ld.so.cache', 'var/cache/ldconfig/aux-cache', 'var/lib/dpkg/status',
  'var/lib/dpkg/status-old', 'var/log/dpkg.log', 'var/lib/dpkg/lock', 'var/lib/dpkg/lock-frontend']);
const sameId = (id, config, ref) => assert.ok([config, ref.split('@')[1]].includes(id), 'brotli_c45_image_identity');

function packages(status) {
  const values = {};
  for (const block of status.trim().split('\n\n')) {
    const fields = {}; let key;
    for (const line of block.split('\n')) {
      if (/^\s/.test(line)) { assert.ok(key, 'brotli_c45_package_continuation'); fields[key] += '\n' + line; }
      else { const colon = line.indexOf(':'); assert.ok(colon > 0, 'brotli_c45_package_field');
        key = line.slice(0, colon); fields[key] = line.slice(colon + 1).trimStart(); }
    }
    assert.ok(fields.Package && !Object.hasOwn(values, fields.Package), 'brotli_c45_duplicate_package');
    values[fields.Package] = fields;
  }
  return values;
}

export function validateBrotliC45Runtime(runtime, receipt, publication, packageBytes) {
  assert.equal(hash(packageBytes), PACKAGE_INVENTORY_SHA256, 'brotli_c45_package_inventory_digest');
  const pkg = JSON.parse(packageBytes);
  assert.equal(pkg.schema, 'otziv-official-brotli-package-v1', 'brotli_c45_package_schema');
  assert.equal(pkg.sha256, PACKAGE_SHA256, 'brotli_c45_official_deb');
  assert.equal(pkg.package, 'libbrotli1', 'brotli_c45_package_name');
  assert.equal(pkg.version, BROTLI_VERSION, 'brotli_c45_package_version');
  assert.equal(runtime.schema, 'otziv-keycloak-brotli-c45-inventory-v1', 'brotli_c45_runtime_schema');
  assert.equal(receipt.schema, 'otziv-keycloak-brotli-c45-inspection-v1', 'brotli_c45_inspection_schema');
  assert.equal(receipt.result, 'PASS', 'brotli_c45_inspection_failed');
  assert.equal(receipt.inspectedContainersExecuted, false, 'brotli_c45_inspected_binary_executed');
  const before = runtime.parent, after = runtime.candidate;
  assert.equal(before.reference, C43_REFERENCE, 'brotli_c45_parent');
  sameId(before.imageId, C43_CONFIG, C43_REFERENCE);
  assert.equal(after.reference, publication.reference, 'brotli_c45_candidate');
  sameId(after.imageId, publication.imageId, publication.reference);
  for (const [image, ref, config] of [[before, C43_REFERENCE, C43_CONFIG], [after, publication.reference, publication.imageId]]) {
    assert.equal(image.containerExecuted, false, 'brotli_c45_executed_container');
    sameId(image.imageId, config, ref);
    for (const path of ['var/lib/dpkg/status', CONTROL_PREFIX + 'list']) {
      assert.equal(typeof image.captured[path], 'string', 'brotli_c45_missing_captured_metadata');
      assert.equal(image.inventory[path]?.kind, 'file', 'brotli_c45_captured_metadata_kind');
      assert.equal(image.inventory[path].sha256, hash(Buffer.from(image.captured[path])), 'brotli_c45_captured_metadata_digest');
    }
  }
  assert.equal(receipt.parent, before.reference, 'brotli_c45_receipt_parent');
  assert.equal(receipt.parentImageId, before.imageId, 'brotli_c45_receipt_parent_image');
  assert.equal(receipt.candidate, after.reference, 'brotli_c45_receipt_candidate');
  assert.equal(receipt.candidateImageId, after.imageId, 'brotli_c45_receipt_candidate_image');
  assert.deepEqual(after.rootfs.slice(0, before.rootfs.length), before.rootfs, 'brotli_c45_parent_layers');
  assert.ok(after.rootfs.length > before.rootfs.length, 'brotli_c45_overlay_missing');
  const config = structuredClone(before.configuration);
  config.Labels = {...config.Labels, 'otziv.brotli': BROTLI_VERSION, 'otziv.brotli-advisory': 'CVE-2026-111497',
    'com.otziv.publication.revision': publication.commit};
  assert.deepEqual(after.configuration, config, 'brotli_c45_unrelated_configuration');
  const oldPackages = packages(before.captured['var/lib/dpkg/status']);
  const newPackages = packages(after.captured['var/lib/dpkg/status']);
  assert.equal(oldPackages.libbrotli1.Version, '1.0.9-2build6', 'brotli_c45_source_package');
  assert.equal(newPackages.libbrotli1.Version, BROTLI_VERSION, 'brotli_c45_target_package');
  assert.equal(newPackages.libbrotli1.Status, 'install ok installed', 'brotli_c45_package_not_installed');
  delete oldPackages.libbrotli1; delete newPackages.libbrotli1;
  assert.deepEqual(newPackages, oldPackages, 'brotli_c45_unrelated_package');
  for (const [path, value] of Object.entries(pkg.payload))
    assert.deepEqual(after.inventory[path], value, 'brotli_c45_official_payload:' + path);
  for (const [name, value] of Object.entries(pkg.control)) if (name !== 'control')
    assert.deepEqual(after.inventory[CONTROL_PREFIX + name], value, 'brotli_c45_official_control:' + name);
  const listed = [...new Set(after.captured[CONTROL_PREFIX + 'list'].split('\n')
    .map(path => path.replace(/^\/+|\/+$/g, '')).filter(path => path && path !== '.'))].sort();
  assert.deepEqual(listed, Object.keys(pkg.payload).sort(), 'brotli_c45_package_manifest');
  const allowed = new Set([...Object.keys(pkg.payload), ...METADATA, CONTROL_PREFIX + 'list',
    ...Object.keys(pkg.control).filter(name => name !== 'control').map(name => CONTROL_PREFIX + name)]);
  const changed = [...new Set([...Object.keys(before.inventory), ...Object.keys(after.inventory)])]
    .filter(path => !isDeepStrictEqual(before.inventory[path], after.inventory[path])).sort();
  assert.ok(changed.some(path => path.startsWith('usr/lib/')), 'brotli_c45_library_unchanged');
  assert.ok(changed.every(path => allowed.has(path)), 'brotli_c45_unrelated_payload');
  assert.deepEqual(receipt.changedFiles, changed, 'brotli_c45_changed_files');
  assert.equal(receipt.packageInventorySha256, PACKAGE_INVENTORY_SHA256, 'brotli_c45_receipt_package');
  const provider = 'opt/keycloak/providers/otziv-security-generation.jar';
  assert.deepEqual(after.inventory[provider], before.inventory[provider], 'brotli_c45_provider_changed');
  assert.equal(after.inventory[provider].sha256, publication.providerJarSha256, 'brotli_c45_provider_identity');
  return changed;
}

export function validateBrotliC45Rehearsal(capture, replay, publication, sourceSha256) {
  for (const record of [capture, replay]) {
    assert.equal(record.schema, 'otziv-brotli-c45-rehearsal-v1', 'brotli_c45_rehearsal_schema');
    assert.equal(record.replayBaselineKeycloak, C43_REFERENCE, 'brotli_c45_replay_issuer');
    assert.equal(record.replayBaselineKeycloakConfig, C43_CONFIG, 'brotli_c45_replay_issuer_config');
  }
  // The captured local C35 fixture and C36 database are unchanged. Reuse its
  // reviewed restore/authentication/rollback protocol after checking C45's
  // actual schema and additional explicit C43 replay binding above.
  validateGuavaC43Rehearsal({...capture, schema: 'otziv-guava-c43-rehearsal-v1'},
    {...replay, schema: 'otziv-guava-c43-rehearsal-v1'}, publication, sourceSha256);
}

export async function validateBrotliC45Activation(publication, entry, read, validateParent) {
  assert.equal(entry.manifest?.path, ROOT + 'reviewed-images-c45-keycloak.json', 'brotli_c45_manifest');
  assert.deepEqual(entry.migrationAcceptance, entry.brotliRefreshAcceptance, 'brotli_c45_migration_binding');
  assert.equal(entry.brotliRefreshAcceptance?.path, PROOF + 'acceptance.json', 'brotli_c45_acceptance_path');
  const bytes = await read(entry.brotliRefreshAcceptance.path);
  assert.equal(hash(bytes), entry.brotliRefreshAcceptance.sha256, 'brotli_c45_acceptance_digest');
  const value = JSON.parse(bytes);
  assert.equal(value.schema, 'otziv-keycloak-brotli-c45-acceptance-v1', 'brotli_c45_acceptance_schema');
  assert.equal(value.reference, publication.reference, 'brotli_c45_acceptance_candidate');
  assert.equal(value.imageConfigId, publication.imageId, 'brotli_c45_acceptance_config');
  assert.equal(value.parentReference, C43_REFERENCE, 'brotli_c45_acceptance_parent');
  assert.equal(value.parentConfigId, C43_CONFIG, 'brotli_c45_acceptance_parent_config');
  assert.deepEqual(value.package, {name: 'libbrotli1', version: BROTLI_VERSION, sha256: PACKAGE_SHA256}, 'brotli_c45_acceptance_package');
  for (const key of ['commit', 'run', 'attempt']) assert.equal(value[key], entry[key], 'brotli_c45_publication_identity');
  const files = new Map();
  for (const [path, digest] of Object.entries(value.files)) {
    assert.ok(path.startsWith(PROOF) && !/[\\:]/.test(path) && path.split('/').every(part => part && part !== '.' && part !== '..'), 'brotli_c45_proof_path');
    const content = await read(path); assert.equal(hash(content), digest, 'brotli_c45_proof_digest');
    files.set(path.slice(PROOF.length), content);
  }
  const take = name => { assert.ok(files.has(name), 'brotli_c45_missing_proof:' + name); return files.get(name); };
  const inspection = JSON.parse(take('inspection/inspection.json'));
  const runtimeBytes = gunzipSync(take('inspection/runtime.json.gz'));
  assert.equal(hash(runtimeBytes), inspection.runtimeSha256, 'brotli_c45_runtime_digest');
  const runtime = JSON.parse(runtimeBytes);
  validateBrotliC45Runtime(runtime, inspection, publication, await read(PACKAGE));
  assert.equal(inspection.executedScriptSha256, hash(take('executed-inspection.py.txt')), 'brotli_c45_executed_inspector');
  assert.equal(inspection.inventoryScriptSha256, hash(take('executed-runtime-inventory.py.txt')), 'brotli_c45_executed_inventory');
  validateBrotliC45Rehearsal(JSON.parse(take('rehearsal/capture.json')), JSON.parse(take('rehearsal/replay.json')),
    publication, hash(take('executed-rehearsal.py.txt')));
  const raw = JSON.parse(await read(dirname(entry.publication.path) + '/vulnerabilities.json'));
  assert.equal(raw.Metadata.ImageID, publication.imageId, 'brotli_c45_scan_image');
  const packages = raw.Results.flatMap(result => result.Packages || []).filter(pkg => pkg.Name === 'libbrotli1');
  assert.ok(packages.length > 0 && packages.every(pkg => pkg.Version === '1.0.9' && pkg.Release === '2ubuntu0.1'
    && pkg.Arch === 'amd64' && pkg.Identifier?.PURL === 'pkg:deb/ubuntu/libbrotli1@1.0.9-2ubuntu0.1?arch=amd64&distro=ubuntu-22.04'), 'brotli_c45_runtime_library_scan');
  const parentBytes = await read(PARENT); assert.equal(hash(parentBytes), PARENT_SHA256, 'brotli_c45_parent_activation_digest');
  const parent = JSON.parse(parentBytes), baseline = await read(ROOT + 'reviewed-images.json');
  assert.equal(parent.reference, C43_REFERENCE, 'brotli_c45_parent_activation');
  await validateParent(JSON.parse(baseline).images.find(image => image.component === 'keycloak'), parent, baseline, read);
  const parentPublication = JSON.parse(await read(parent.publication.path));
  assert.equal(parentPublication.providerJarSha256, publication.providerJarSha256, 'brotli_c45_provider_rebuilt');
  for (const [which, record, path] of [['parent', parentPublication, parent.publication.path], ['candidate', publication, entry.publication.path]]) {
    const configBytes = await read(dirname(path) + '/registry-amd64-config.json');
    assert.equal('sha256:' + hash(configBytes), record.imageId, 'brotli_c45_registry_config');
    assert.deepEqual(runtime[which].rootfs, JSON.parse(configBytes).rootfs.diff_ids, 'brotli_c45_registry_rootfs');
  }
  return value;
}

export async function couplePostgresC36WithKeycloakC45(readiness, entry, read, validateParent) {
  assert.ok(readiness.postgresC36Acceptance, 'brotli_c45_database_acceptance_missing');
  assert.equal(readiness.postgresReference, C36_POSTGRES, 'brotli_c45_database_pair');
  assert.equal(readiness.postgresConfigId, C36_POSTGRES_CONFIG, 'brotli_c45_database_config');
  assert.deepEqual(readiness.postgresC36Pair, {keycloakReference: C35_REFERENCE, keycloakConfigId: C35_CONFIG}, 'brotli_c45_parent_database_pair');
  const publication = JSON.parse(await read(entry.publication.path));
  const accepted = await validateBrotliC45Activation(publication, entry, read, validateParent);
  return {...readiness, requiredKeycloakReference: entry.reference, requiredKeycloakConfigId: accepted.imageConfigId,
    acceptancePath: entry.brotliRefreshAcceptance.path, proofSha256: entry.brotliRefreshAcceptance.sha256};
}
