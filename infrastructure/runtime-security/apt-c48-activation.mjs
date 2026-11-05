// Exact official Ubuntu apt overlays; the accepted C45 issuer and C43 Alloy lineages remain bound.
import assert from 'node:assert/strict';
import {isDeepStrictEqual} from 'node:util';
import {dirname} from 'node:path';
import {gunzipSync} from 'node:zlib';
import {hash} from './ssl-refresh.mjs';
import {C35_REFERENCE, C35_CONFIG, C36_POSTGRES, C36_POSTGRES_CONFIG} from './guava-c43-activation.mjs';

export const ADVISORY = 'CVE-2026-109518';
export const C45_REFERENCE = 'ghcr.io/claidd/otziv-security@sha256:6c6f398170954330c03dc2c16db1b96c5ebf2cb29546fe59542b6dcc7b60ee5e';
export const C45_CONFIG = 'sha256:ba4b2b070f6b58a0f74725860b143e722372a556002e28079654ce2be44048b3';
export const C43_ALLOY_REFERENCE = 'ghcr.io/claidd/otziv-security@sha256:27905df1862decf6e9c2543cb476e70e83d68351f7a5bb17194a6a094764db32';
export const C43_ALLOY_CONFIG = 'sha256:a40087d44026f83768b1d6c6a94005478bbc357c56e0943ef8b63ac4ba486aa5';
const ROOT = 'infrastructure/runtime-security/';
const POOL = 'https://security.ubuntu.com/ubuntu/pool/main/a/apt/';
export const SPEC = Object.freeze({
  keycloak: {parent: C45_REFERENCE, parentConfig: C45_CONFIG, distribution: 'ubuntu-22.04',
    version: '2.4.15ubuntu0.1', previous: '2.4.14',
    packages: {'libapt-pkg6.0': ['libapt-pkg6.0:amd64', '3a6fbab029e15ca7b8da642c17ec110995a6e3a0bc40eaa08e239cfc94d6c426'],
      apt: ['apt', '2d4db9a3017c5364e50af5bc2f0c76828b52933b682eff4728f1211e081c6736']},
    inventorySha256: 'cda895082b4c69c324d248a6e2c07da90d4bb830440a1d932dd20ee8fdce21ad',
    parentActivation: ROOT + 'c48-parent-keycloak.json',
    parentActivationSha256: 'b25e0fb8fe0c993333e5b363c675e0ef8ab6cf8a711cbe7310589d9acb35fff6',
    protected: ['opt/keycloak/providers/otziv-security-generation.jar', 'opt/keycloak/bin/kc.sh'],
    proofs: ['inspection/inspection.json', 'inspection/runtime.json.gz', 'executed-inspection.py.txt',
      'executed-runtime-inventory.py.txt', 'rehearsal/capture.json', 'rehearsal/replay.json', 'executed-rehearsal.py.txt']},
  alloy: {parent: C43_ALLOY_REFERENCE, parentConfig: C43_ALLOY_CONFIG, distribution: 'ubuntu-24.04',
    version: '2.8.4ubuntu0.1', previous: '2.8.3',
    packages: {'libapt-pkg6.0t64': ['libapt-pkg6.0t64:amd64', 'a50f3e68a793f90b05f44a4b5f2f59f3ec7687e39b5d8bbd1da44c84671fa3d6'],
      apt: ['apt', 'ad2ea7f107f250ba88b0bfdba31812a341e3247589647aa2b03b9dc4e8abf8ed']},
    inventorySha256: '8acf21f15177a65942b580739aa97908a89b9709f22e8a8f40474beb8edcf5ec',
    parentActivation: ROOT + 'c48-parent-alloy.json',
    parentActivationSha256: '854ba36e3c70cb5d3adc4c0cdd769735ddda41a63c63db43deda1918974df6c9',
    protected: ['usr/bin/alloy', 'usr/share/otziv-build/alloy.packages.txt'],
    proofs: ['inspection/inspection.json', 'inspection/runtime.json.gz', 'executed-inspection.py.txt',
      'executed-runtime-inventory.py.txt']},
});
// dpkg configuration of both parents, including the image path filters.
const DPKG_CONFIG = Object.freeze({
  'etc/dpkg/dpkg.cfg': 'fead43b89af3ea5691c48f32d7fe1ba0f7ab229fb5d230f612d76fe8e6f5a015',
  'etc/dpkg/dpkg.cfg.d/docker-apt-speedup': '94372f4a7672c283c0fe956b0638ec8731e21584d880ba782a1a338b906caeb1',
  'etc/dpkg/dpkg.cfg.d/excludes': '61159013263411a7c7a4157a6ddcd4eca3329ee698e508850070af11338098db',
});
const METADATA = new Set(['etc/ld.so.cache', 'var/cache/ldconfig/aux-cache', 'var/lib/dpkg/status',
  'var/lib/dpkg/status-old', 'var/log/dpkg.log', 'var/lib/dpkg/lock', 'var/lib/dpkg/lock-frontend']);
const specFor = component => { assert.ok(Object.hasOwn(SPEC, component), 'apt_c48_component'); return SPEC[component]; };
const sameId = (id, config, ref) => assert.ok([config, ref.split('@')[1]].includes(id), 'apt_c48_image_identity');
const proofRoot = component => ROOT + 'proofs/c48-' + component + '/';

function packages(status) {
  const values = {};
  for (const block of status.trim().split('\n\n')) {
    const fields = {}; let key;
    for (const line of block.split('\n')) {
      if (/^\s/.test(line)) { assert.ok(key, 'apt_c48_package_continuation'); fields[key] += '\n' + line; }
      else { const colon = line.indexOf(':'); assert.ok(colon > 0, 'apt_c48_package_field');
        key = line.slice(0, colon); fields[key] = line.slice(colon + 1).trimStart(); }
    }
    assert.ok(fields.Package && !Object.hasOwn(values, fields.Package), 'apt_c48_duplicate_package');
    values[fields.Package] = fields;
  }
  return values;
}

// dpkg reads dpkg.cfg.d fragments in name order, then dpkg.cfg; the last matching path filter wins.
export function dpkgFilters(image) {
  const names = Object.keys(image.captured).filter(name => name.startsWith('etc/dpkg/dpkg.cfg.d/')).sort();
  const rules = [];
  for (const name of [...names, 'etc/dpkg/dpkg.cfg']) for (const raw of (image.captured[name] || '').split('\n')) {
    const line = raw.trim();
    if (!/^path-(?:exclude|include)/.test(line)) continue;
    const match = /^(path-exclude|path-include)[= ](\/\S+)$/.exec(line);
    assert.ok(match && !/[[\]\\]/.test(match[2]), 'apt_c48_unsupported_dpkg_filter');
    rules.push([match[1], new RegExp('^' + match[2].replace(/[.+^${}()|]/g, '\\$&')
      .replaceAll('*', '[\\s\\S]*').replaceAll('?', '[\\s\\S]') + '$')]);
  }
  return rules;
}

export function excluded(path, rules) {
  let result = false;
  for (const [kind, pattern] of rules) if (pattern.test('/' + path)) result = kind === 'path-exclude';
  return result;
}

// Resolve parent-directory symlinks (merged /usr), never the final component.
export function resolvePath(path, inventory) {
  const parts = path.split('/');
  let resolved = [];
  for (const [index, part] of parts.entries()) {
    resolved.push(part);
    const current = resolved.join('/');
    if (index < parts.length - 1 && inventory[current]?.kind === 'symlink') {
      const target = inventory[current].target;
      const joined = target.startsWith('/') ? target.replace(/^\/+/, '').split('/') : [...resolved.slice(0, -1), ...target.split('/')];
      resolved = [];
      for (const item of joined) {
        if (item === '..') { assert.ok(resolved.length, 'apt_c48_symlink_escape'); resolved.pop(); }
        else if (item && item !== '.') resolved.push(item);
      }
    }
  }
  return resolved.join('/');
}

export function validateAptC48Runtime(component, runtime, receipt, publication, packageBytes) {
  const spec = specFor(component);
  assert.equal(hash(packageBytes), spec.inventorySha256, 'apt_c48_package_inventory_digest');
  const inventory = JSON.parse(Buffer.from(packageBytes).toString('utf8'));
  assert.equal(inventory.schema, 'otziv-official-apt-packages-v1', 'apt_c48_package_schema');
  assert.equal(inventory.component, component, 'apt_c48_package_component');
  assert.equal(inventory.parent, spec.parent, 'apt_c48_package_parent');
  assert.equal(inventory.advisory, ADVISORY, 'apt_c48_package_advisory');
  assert.equal(inventory.distribution, spec.distribution, 'apt_c48_package_distribution');
  assert.deepEqual(inventory.packages.map(item => item.package), Object.keys(spec.packages), 'apt_c48_package_set');
  for (const item of inventory.packages) {
    const [info, digest] = spec.packages[item.package];
    const file = `${item.package}_${spec.version}_amd64.deb`;
    assert.deepEqual([item.dpkgInfoName, item.sha256, item.version, item.previousVersion, item.architecture, item.url],
      [info, digest, spec.version, spec.previous, 'amd64', POOL + file], 'apt_c48_official_deb');
    assert.deepEqual(item.signedArchiveIndex && [item.signedArchiveIndex.filename, item.signedArchiveIndex.sha256,
      item.signedArchiveIndex.size], ['pool/main/a/apt/' + file, digest, item.bytes], 'apt_c48_signed_archive_index');
  }
  assert.equal(runtime.schema, 'otziv-apt-c48-inventory-v1', 'apt_c48_runtime_schema');
  assert.equal(runtime.component, component, 'apt_c48_runtime_component');
  assert.equal(receipt.schema, 'otziv-apt-c48-inspection-v1', 'apt_c48_inspection_schema');
  assert.equal(receipt.result, 'PASS', 'apt_c48_inspection_failed');
  assert.equal(receipt.component, component, 'apt_c48_inspection_component');
  assert.equal(receipt.inspectedContainersExecuted, false, 'apt_c48_inspected_binary_executed');
  const before = runtime.parent, after = runtime.candidate;
  assert.equal(before.reference, spec.parent, 'apt_c48_parent');
  assert.equal(after.reference, publication.reference, 'apt_c48_candidate');
  const infos = Object.values(spec.packages).map(([info]) => 'var/lib/dpkg/info/' + info + '.list');
  for (const [image, ref, config] of [[before, spec.parent, spec.parentConfig], [after, publication.reference, publication.imageId]]) {
    assert.equal(image.containerExecuted, false, 'apt_c48_executed_container');
    sameId(image.imageId, config, ref);
    for (const path of ['var/lib/dpkg/status', ...infos, ...Object.keys(DPKG_CONFIG)]) {
      assert.equal(typeof image.captured[path], 'string', 'apt_c48_missing_captured_metadata');
      assert.equal(image.inventory[path]?.kind, 'file', 'apt_c48_captured_metadata_kind');
      assert.equal(image.inventory[path].sha256, hash(Buffer.from(image.captured[path])), 'apt_c48_captured_metadata_digest');
    }
    assert.deepEqual(Object.keys(image.inventory).filter(path => path.startsWith('etc/dpkg/dpkg.cfg.d/')).sort(),
      Object.keys(DPKG_CONFIG).filter(path => path.startsWith('etc/dpkg/dpkg.cfg.d/')), 'apt_c48_dpkg_fragments');
    for (const [path, digest] of Object.entries(DPKG_CONFIG))
      assert.equal(image.inventory[path].sha256, digest, 'apt_c48_dpkg_configuration');
  }
  assert.equal(receipt.parent, before.reference, 'apt_c48_receipt_parent');
  assert.equal(receipt.parentImageId, before.imageId, 'apt_c48_receipt_parent_image');
  assert.equal(receipt.candidate, after.reference, 'apt_c48_receipt_candidate');
  assert.equal(receipt.candidateImageId, after.imageId, 'apt_c48_receipt_candidate_image');
  assert.deepEqual(after.rootfs.slice(0, before.rootfs.length), before.rootfs, 'apt_c48_parent_layers');
  assert.ok(after.rootfs.length > before.rootfs.length, 'apt_c48_overlay_missing');
  const config = structuredClone(before.configuration);
  config.Labels = {...config.Labels, 'otziv.apt': spec.version, 'otziv.apt-advisory': ADVISORY,
    'com.otziv.publication.revision': publication.commit};
  assert.deepEqual(after.configuration, config, 'apt_c48_unrelated_configuration');
  const oldPackages = packages(before.captured['var/lib/dpkg/status']);
  const newPackages = packages(after.captured['var/lib/dpkg/status']);
  for (const name of Object.keys(spec.packages)) {
    assert.equal(oldPackages[name]?.Version, spec.previous, 'apt_c48_source_package');
    assert.equal(newPackages[name]?.Version, spec.version, 'apt_c48_target_package');
    assert.equal(newPackages[name].Status, 'install ok installed', 'apt_c48_package_not_installed');
    delete oldPackages[name]; delete newPackages[name];
  }
  assert.deepEqual(newPackages, oldPackages, 'apt_c48_unrelated_package');
  const rules = dpkgFilters(after);
  assert.ok(rules.length > 0, 'apt_c48_dpkg_filters_missing');
  const allowed = new Set(METADATA);
  for (const item of inventory.packages) {
    const prefix = 'var/lib/dpkg/info/' + item.dpkgInfoName + '.';
    const listed = text => new Set(text.split('\n').map(path => path.replace(/^\/+|\/+$/g, '')).filter(path => path && path !== '.'));
    const oldListed = listed(before.captured[prefix + 'list']);
    assert.deepEqual([...listed(after.captured[prefix + 'list'])].sort(), Object.keys(item.payload).sort(), 'apt_c48_package_manifest');
    for (const [path, row] of Object.entries(item.payload)) {
      const actual = resolvePath(path, after.inventory);
      assert.equal(resolvePath(path, before.inventory), actual, 'apt_c48_path_resolution_changed');
      allowed.add(actual);
      if (row.kind === 'directory') {
        if (Object.hasOwn(before.inventory, actual))
          assert.deepEqual(after.inventory[actual], before.inventory[actual], 'apt_c48_existing_directory:' + path);
        else if (Object.hasOwn(after.inventory, actual))
          assert.deepEqual(after.inventory[actual], row, 'apt_c48_official_directory:' + path);
        else assert.ok(excluded(path, rules), 'apt_c48_missing_directory:' + path);
      } else if (excluded(path, rules)) {
        assert.ok(!Object.hasOwn(after.inventory, actual), 'apt_c48_filtered_path_present:' + path);
        assert.ok(!Object.hasOwn(before.inventory, actual) || oldListed.has(path), 'apt_c48_filtered_path_owner:' + path);
      } else assert.deepEqual(after.inventory[actual], row, 'apt_c48_official_payload:' + path);
    }
    for (const [name, value] of Object.entries(item.control)) if (name !== 'control') {
      assert.deepEqual(after.inventory[prefix + name], value, 'apt_c48_official_control:' + name);
      allowed.add(prefix + name);
    }
    allowed.add(prefix + 'list');
  }
  const changed = [...new Set([...Object.keys(before.inventory), ...Object.keys(after.inventory)])]
    .filter(path => !isDeepStrictEqual(before.inventory[path], after.inventory[path])).sort();
  assert.ok(changed.some(path => path.startsWith('usr/lib/x86_64-linux-gnu/libapt-pkg.so.')), 'apt_c48_library_unchanged');
  assert.ok(changed.every(path => allowed.has(path)), 'apt_c48_unrelated_payload');
  assert.deepEqual(receipt.changedFiles, changed, 'apt_c48_changed_files');
  assert.equal(receipt.packageInventorySha256, spec.inventorySha256, 'apt_c48_receipt_package');
  for (const path of spec.protected) {
    assert.equal(after.inventory[path]?.kind, 'file', 'apt_c48_protected_missing:' + path);
    assert.deepEqual(after.inventory[path], before.inventory[path], 'apt_c48_protected_changed:' + path);
  }
  if (component === 'keycloak')
    assert.equal(after.inventory['opt/keycloak/providers/otziv-security-generation.jar'].sha256, publication.providerJarSha256,
      'apt_c48_provider_identity');
  return changed;
}

const checks = (record, names) => {
  assert.equal(record.result, 'PASS', 'apt_c48_rehearsal_failed');
  assert.ok(record.checks?.length && record.checks.every(check => check.passed === true), 'apt_c48_failed_check');
  for (const name of names) assert.equal(record.checks.filter(check => check.name === name && check.passed).length, 1,
    'apt_c48_missing_check:' + name);
};

// Capture reads the local prod-like C45/C36 issuer pair; replay and both rollback paths run C45/C36.
export function validateAptC48Rehearsal(capture, replay, publication, sourceSha256) {
  for (const record of [capture, replay]) {
    assert.equal(record.schema, 'otziv-apt-c48-rehearsal-v1', 'apt_c48_rehearsal_schema');
    assert.equal(record.sourceScope, 'LOCAL_PROD_LIKE_KEYCLOAK', 'apt_c48_source_scope');
    assert.equal(record.productionWrites, false, 'apt_c48_production_write');
    assert.equal(record.privateRowsPublished, false, 'apt_c48_private_rows');
    assert.equal(record.publishedPorts, 0, 'apt_c48_host_ports');
    assert.equal(record.sourcePostgres, C36_POSTGRES, 'apt_c48_capture_postgres');
    assert.equal(record.sourcePostgresConfig, C36_POSTGRES_CONFIG, 'apt_c48_capture_postgres_config');
    assert.equal(record.keycloak, C45_REFERENCE, 'apt_c48_source_issuer');
    assert.equal(record.keycloakConfig, C45_CONFIG, 'apt_c48_source_issuer_config');
    assert.equal(record.replayBaselinePostgres, C36_POSTGRES, 'apt_c48_replay_pair');
    assert.equal(record.replayBaselineKeycloak, C45_REFERENCE, 'apt_c48_replay_issuer');
    assert.equal(record.replayBaselineKeycloakConfig, C45_CONFIG, 'apt_c48_replay_issuer_config');
    assert.equal(record.executedScriptSha256, sourceSha256, 'apt_c48_executed_rehearsal');
    checks(record, []);
  }
  checks(capture, ['complete_custom_dump', 'source_runtime_unchanged', 'source_critical_identity_stable_during_capture']);
  assert.equal(replay.dumpSha256, capture.dumpSha256, 'apt_c48_dump_changed');
  assert.equal(replay.candidate, publication.reference, 'apt_c48_rehearsal_candidate');
  sameId(replay.candidateLocalId, publication.imageId, publication.reference);
  assert.equal(replay.candidatePostgres, C36_POSTGRES, 'apt_c48_postgres_changed');
  sameId(replay.candidatePostgresLocalId, C36_POSTGRES_CONFIG, C36_POSTGRES);
  assert.deepEqual(replay.ownedResourcesRemaining, {container: 0, volume: 0, network: 0}, 'apt_c48_resource_leak');
  assert.ok(!replay.cleanupErrors?.length, 'apt_c48_cleanup_error');
  checks(replay, ['restored_critical_records_exact', 'capture_stable_tables_restored_exact', 'source_existing_password_login',
    'source_roles_and_credentials_preserved', 'same_volume_all_tables_exact', 'existing_admin_password_login',
    'existing_roles_and_credentials_preserved', 'unauthenticated_provider_denied', 'library_patch_no_schema_migration',
    'same_volume_rollback_preserves_all_post_upgrade_writes', 'same_volume_old_issuer_login',
    'same_volume_old_issuer_credentials_preserved', 'fresh_rollback_restore_preserves_all_post_upgrade_writes',
    'restored_existing_password_login', 'restored_roles_and_credentials_preserved']);
  assert.equal(replay.tableCount, 102, 'apt_c48_table_coverage');
  for (const key of ['serverVersion', 'encoding', 'locale', 'clusterIdentifier', 'extensions', 'configSha256', 'hbaSha256', 'identSha256'])
    assert.deepEqual(replay.candidateDatabase[key], replay.sourceDatabase[key], 'apt_c48_database_changed:' + key);
}

const ALLOY_BINARY_FIELDS = ['schema', 'status', 'binarySha256', 'buildInfoSha256', 'goJoseVersion', 'buildInfoReader',
  'functionCount', 'daemonFunctions', 'clientFunctions', 'functionNamesSha256', 'packageCount', 'daemonPackages', 'closureSha256'];

export async function validateAptC48Activation(component, publication, entry, read, validateParent) {
  const spec = specFor(component), proof = proofRoot(component);
  assert.equal(entry.manifest?.path, ROOT + 'reviewed-images-c48-' + component + '.json', 'apt_c48_manifest');
  if (component === 'keycloak') assert.deepEqual(entry.migrationAcceptance, entry.aptRefreshAcceptance, 'apt_c48_migration_binding');
  else assert.equal(entry.migrationAcceptance, undefined, 'apt_c48_unexpected_migration');
  assert.equal(entry.aptRefreshAcceptance?.path, proof + 'acceptance.json', 'apt_c48_acceptance_path');
  const bytes = await read(entry.aptRefreshAcceptance.path);
  assert.equal(hash(bytes), entry.aptRefreshAcceptance.sha256, 'apt_c48_acceptance_digest');
  const value = JSON.parse(bytes);
  assert.equal(value.schema, 'otziv-apt-c48-acceptance-v1', 'apt_c48_acceptance_schema');
  assert.equal(value.result, 'PASS', 'apt_c48_acceptance_result');
  assert.equal(value.component, component, 'apt_c48_acceptance_component');
  assert.equal(value.reference, publication.reference, 'apt_c48_acceptance_candidate');
  assert.equal(value.imageConfigId, publication.imageId, 'apt_c48_acceptance_config');
  assert.equal(value.parentReference, spec.parent, 'apt_c48_acceptance_parent');
  assert.equal(value.parentConfigId, spec.parentConfig, 'apt_c48_acceptance_parent_config');
  assert.equal(value.advisory, ADVISORY, 'apt_c48_acceptance_advisory');
  assert.deepEqual(value.packages, Object.entries(spec.packages).map(([name, [, sha256]]) => ({name, version: spec.version, sha256})),
    'apt_c48_acceptance_packages');
  for (const key of ['commit', 'run', 'attempt']) assert.equal(value[key], entry[key], 'apt_c48_publication_identity');
  assert.deepEqual(Object.keys(value.files).sort(), spec.proofs.map(name => proof + name).sort(), 'apt_c48_proof_set');
  const files = new Map();
  for (const [path, digest] of Object.entries(value.files)) {
    assert.ok(path.startsWith(proof) && !/[\\:]/.test(path) && path.split('/').every(part => part && part !== '.' && part !== '..'), 'apt_c48_proof_path');
    const content = await read(path); assert.equal(hash(content), digest, 'apt_c48_proof_digest');
    files.set(path.slice(proof.length), content);
  }
  const take = name => { assert.ok(files.has(name), 'apt_c48_missing_proof:' + name); return files.get(name); };
  const inspection = JSON.parse(take('inspection/inspection.json'));
  const runtimeBytes = gunzipSync(take('inspection/runtime.json.gz'));
  assert.equal(hash(runtimeBytes), inspection.runtimeSha256, 'apt_c48_runtime_digest');
  const runtime = JSON.parse(runtimeBytes);
  validateAptC48Runtime(component, runtime, inspection, publication, await read(ROOT + 'c48-' + component + '-apt-packages.json'));
  assert.equal(inspection.executedScriptSha256, hash(take('executed-inspection.py.txt')), 'apt_c48_executed_inspector');
  assert.equal(inspection.inventoryScriptSha256, hash(take('executed-runtime-inventory.py.txt')), 'apt_c48_executed_inventory');
  if (component === 'keycloak')
    validateAptC48Rehearsal(JSON.parse(take('rehearsal/capture.json')), JSON.parse(take('rehearsal/replay.json')),
      publication, hash(take('executed-rehearsal.py.txt')));
  const raw = JSON.parse(await read(dirname(entry.publication.path) + '/vulnerabilities.json'));
  assert.equal(raw.Metadata.ImageID, publication.imageId, 'apt_c48_scan_image');
  assert.equal(raw.Results.flatMap(result => result.Vulnerabilities || []).filter(item => item.VulnerabilityID === ADVISORY).length,
    0, 'apt_c48_advisory_still_reported');
  for (const name of Object.keys(spec.packages)) {
    const found = raw.Results.flatMap(result => result.Packages || []).filter(item => item.Name === name);
    assert.ok(found.length > 0 && found.every(item => item.Version === spec.version && !item.Release && item.Arch === 'amd64'
      && item.Identifier?.PURL === `pkg:deb/ubuntu/${name}@${spec.version}?arch=amd64&distro=${spec.distribution}`), 'apt_c48_runtime_package_scan');
  }
  const parentBytes = await read(spec.parentActivation);
  assert.equal(hash(parentBytes), spec.parentActivationSha256, 'apt_c48_parent_activation_digest');
  const parent = JSON.parse(parentBytes), baseline = await read(ROOT + 'reviewed-images.json');
  assert.equal(parent.reference, spec.parent, 'apt_c48_parent_activation');
  await validateParent(JSON.parse(baseline).images.find(image => image.component === component), parent, baseline, read);
  const parentPublication = JSON.parse(await read(parent.publication.path));
  if (component === 'keycloak')
    assert.equal(parentPublication.providerJarSha256, publication.providerJarSha256, 'apt_c48_provider_rebuilt');
  else {
    const binary = runtime.candidate.inventory['usr/bin/alloy'].sha256;
    const receipts = [];
    for (const path of [parent.publication.path, entry.publication.path])
      receipts.push(JSON.parse(await read(dirname(path) + '/vulnerabilities.adjudications.json')).alloy);
    for (const receipt of receipts) assert.equal(receipt?.binarySha256, binary, 'apt_c48_alloy_binary_rebuilt');
    assert.deepEqual(Object.fromEntries(ALLOY_BINARY_FIELDS.map(key => [key, receipts[1][key]])),
      Object.fromEntries(ALLOY_BINARY_FIELDS.map(key => [key, receipts[0][key]])), 'apt_c48_alloy_binary_review_changed');
  }
  for (const [which, record, path] of [['parent', parentPublication, parent.publication.path], ['candidate', publication, entry.publication.path]]) {
    const configBytes = await read(dirname(path) + '/registry-amd64-config.json');
    assert.equal('sha256:' + hash(configBytes), record.imageId, 'apt_c48_registry_config');
    assert.deepEqual(runtime[which].rootfs, JSON.parse(configBytes).rootfs.diff_ids, 'apt_c48_registry_rootfs');
  }
  return value;
}

export async function couplePostgresC36WithKeycloakC48(readiness, entry, read, validateParent) {
  assert.ok(readiness.postgresC36Acceptance, 'apt_c48_database_acceptance_missing');
  assert.equal(readiness.postgresReference, C36_POSTGRES, 'apt_c48_database_pair');
  assert.equal(readiness.postgresConfigId, C36_POSTGRES_CONFIG, 'apt_c48_database_config');
  assert.deepEqual(readiness.postgresC36Pair, {keycloakReference: C35_REFERENCE, keycloakConfigId: C35_CONFIG}, 'apt_c48_parent_database_pair');
  const publication = JSON.parse(await read(entry.publication.path));
  const accepted = await validateAptC48Activation('keycloak', publication, entry, read, validateParent);
  return {...readiness, requiredKeycloakReference: entry.reference, requiredKeycloakConfigId: accepted.imageConfigId,
    acceptancePath: entry.aptRefreshAcceptance.path, proofSha256: entry.aptRefreshAcceptance.sha256};
}
