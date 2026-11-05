// Generic acceptance for an official OS package overlay over an accepted
// Keycloak or Alloy image. Every rule mirrors the C45/C48 acceptance; all
// generation-specific values come from one reviewed spec
// (os-package-overlay-registry.mjs). Error codes start with overlay_.
import assert from 'node:assert/strict';
import {isDeepStrictEqual} from 'node:util';
import {dirname} from 'node:path';
import {gunzipSync} from 'node:zlib';
import {hash} from './ssl-refresh.mjs';
import {packageFileVersion} from './os-package-overlay-registry.mjs';

const METADATA = new Set(['etc/ld.so.cache', 'var/cache/ldconfig/aux-cache', 'var/lib/dpkg/status',
  'var/lib/dpkg/status-old', 'var/log/dpkg.log', 'var/lib/dpkg/lock', 'var/lib/dpkg/lock-frontend']);
const ALLOY_BINARY_FIELDS = ['schema', 'status', 'binarySha256', 'buildInfoSha256', 'goJoseVersion', 'buildInfoReader',
  'functionCount', 'daemonFunctions', 'clientFunctions', 'functionNamesSha256', 'packageCount', 'daemonPackages', 'closureSha256'];
const sameId = (id, config, ref) => assert.ok([config, ref.split('@')[1]].includes(id), 'overlay_image_identity');
export const overlaySchema = (spec, kind) => `${spec.schemaPrefix}-${kind}-v1`;

function packages(status) {
  const values = {};
  for (const block of status.trim().split('\n\n')) {
    const fields = {}; let key;
    for (const line of block.split('\n')) {
      if (/^\s/.test(line)) { assert.ok(key, 'overlay_package_continuation'); fields[key] += '\n' + line; }
      else { const colon = line.indexOf(':'); assert.ok(colon > 0, 'overlay_package_field');
        key = line.slice(0, colon); fields[key] = line.slice(colon + 1).trimStart(); }
    }
    assert.ok(fields.Package && !Object.hasOwn(values, fields.Package), 'overlay_duplicate_package');
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
    assert.ok(match && !/[[\]\\]/.test(match[2]), 'overlay_unsupported_dpkg_filter');
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
    if (index < parts.length - 1 && inventory[resolved.join('/')]?.kind === 'symlink') {
      const target = inventory[resolved.join('/')].target;
      const joined = target.startsWith('/') ? target.replace(/^\/+/, '').split('/') : [...resolved.slice(0, -1), ...target.split('/')];
      resolved = [];
      for (const item of joined) {
        if (item === '..') { assert.ok(resolved.length, 'overlay_symlink_escape'); resolved.pop(); }
        else if (item && item !== '.') resolved.push(item);
      }
    }
  }
  return resolved.join('/');
}

// Trivy splits a Debian version into epoch, upstream version and revision.
export function scannerVersion(version) {
  const [, epoch, rest] = /^(?:([0-9]+):)?(.+)$/.exec(version);
  const index = rest.lastIndexOf('-');
  return {epoch: epoch ? Number(epoch) : 0, version: index < 0 ? rest : rest.slice(0, index), release: index < 0 ? '' : rest.slice(index + 1)};
}

export function validateOverlayInventory(spec, packageBytes) {
  assert.equal(hash(packageBytes), spec.packageInventory.sha256, 'overlay_package_inventory_digest');
  const inventory = JSON.parse(Buffer.from(packageBytes).toString('utf8'));
  assert.equal(inventory.schema, spec.packageInventory.schema, 'overlay_package_schema');
  assert.equal(inventory.component, spec.component, 'overlay_package_component');
  assert.equal(inventory.parent, spec.parent.reference, 'overlay_package_parent');
  assert.equal(inventory.advisory, spec.advisory, 'overlay_package_advisory');
  assert.equal(inventory.distribution, spec.distribution, 'overlay_package_distribution');
  assert.deepEqual(inventory.packages.map(item => item.package), spec.packages.map(item => item.name), 'overlay_package_set');
  for (const [index, item] of inventory.packages.entries()) {
    const expected = spec.packages[index];
    assert.deepEqual([item.dpkgInfoName, item.sha256, item.version, item.previousVersion, item.architecture, item.url],
      [expected.dpkgInfoName, expected.sha256, expected.version, expected.previousVersion, 'amd64', expected.url], 'overlay_official_deb');
    const index_ = item.signedArchiveIndex;
    assert.ok(index_ && index_.sha256 === expected.sha256 && index_.size === item.bytes
      && expected.url.endsWith('/' + index_.filename) && index_.filename.startsWith('pool/')
      && index_.filename.endsWith(`/${expected.name}_${packageFileVersion(expected.version)}_amd64.deb`), 'overlay_signed_archive_index');
  }
  return inventory;
}

export function validateOverlayRuntime(spec, runtime, receipt, publication, packageBytes) {
  const inventory = validateOverlayInventory(spec, packageBytes);
  assert.equal(runtime.schema, overlaySchema(spec, 'inventory'), 'overlay_runtime_schema');
  assert.equal(runtime.component, spec.component, 'overlay_runtime_component');
  assert.equal(receipt.schema, overlaySchema(spec, 'inspection'), 'overlay_inspection_schema');
  assert.equal(receipt.result, 'PASS', 'overlay_inspection_failed');
  assert.equal(receipt.component, spec.component, 'overlay_inspection_component');
  assert.equal(receipt.inspectedContainersExecuted, false, 'overlay_inspected_binary_executed');
  const before = runtime.parent, after = runtime.candidate;
  assert.equal(before.reference, spec.parent.reference, 'overlay_parent');
  assert.equal(after.reference, publication.reference, 'overlay_candidate');
  const infos = spec.packages.map(item => 'var/lib/dpkg/info/' + item.dpkgInfoName + '.list');
  const fragments = Object.keys(spec.dpkgConfig).filter(path => path.startsWith('etc/dpkg/dpkg.cfg.d/')).sort();
  for (const [image, ref, config] of [[before, spec.parent.reference, spec.parent.configId], [after, publication.reference, publication.imageId]]) {
    assert.equal(image.containerExecuted, false, 'overlay_executed_container');
    sameId(image.imageId, config, ref);
    for (const path of ['var/lib/dpkg/status', ...infos, ...Object.keys(spec.dpkgConfig)]) {
      assert.equal(typeof image.captured[path], 'string', 'overlay_missing_captured_metadata');
      assert.equal(image.inventory[path]?.kind, 'file', 'overlay_captured_metadata_kind');
      assert.equal(image.inventory[path].sha256, hash(Buffer.from(image.captured[path])), 'overlay_captured_metadata_digest');
    }
    assert.deepEqual(Object.keys(image.inventory).filter(path => path.startsWith('etc/dpkg/dpkg.cfg.d/')).sort(), fragments,
      'overlay_dpkg_fragments');
    for (const [path, digest] of Object.entries(spec.dpkgConfig))
      assert.equal(image.inventory[path].sha256, digest, 'overlay_dpkg_configuration');
  }
  assert.equal(receipt.parent, before.reference, 'overlay_receipt_parent');
  assert.equal(receipt.parentImageId, before.imageId, 'overlay_receipt_parent_image');
  assert.equal(receipt.candidate, after.reference, 'overlay_receipt_candidate');
  assert.equal(receipt.candidateImageId, after.imageId, 'overlay_receipt_candidate_image');
  assert.deepEqual(after.rootfs.slice(0, before.rootfs.length), before.rootfs, 'overlay_parent_layers');
  assert.ok(after.rootfs.length > before.rootfs.length, 'overlay_missing');
  const config = structuredClone(before.configuration);
  config.Labels = {...config.Labels, ...spec.labels, 'com.otziv.publication.revision': publication.commit};
  assert.deepEqual(after.configuration, config, 'overlay_unrelated_configuration');
  const oldPackages = packages(before.captured['var/lib/dpkg/status']);
  const newPackages = packages(after.captured['var/lib/dpkg/status']);
  for (const item of spec.packages) {
    assert.equal(oldPackages[item.name]?.Version, item.previousVersion, 'overlay_source_package');
    assert.equal(newPackages[item.name]?.Version, item.version, 'overlay_target_package');
    assert.equal(newPackages[item.name].Status, 'install ok installed', 'overlay_package_not_installed');
    delete oldPackages[item.name]; delete newPackages[item.name];
  }
  assert.deepEqual(newPackages, oldPackages, 'overlay_unrelated_package');
  const rules = dpkgFilters(after);
  const allowed = new Set(METADATA);
  for (const item of inventory.packages) {
    const prefix = 'var/lib/dpkg/info/' + item.dpkgInfoName + '.';
    const listed = text => new Set(text.split('\n').map(path => path.replace(/^\/+|\/+$/g, '')).filter(path => path && path !== '.'));
    const oldListed = listed(before.captured[prefix + 'list']);
    assert.deepEqual([...listed(after.captured[prefix + 'list'])].sort(), Object.keys(item.payload).sort(), 'overlay_package_manifest');
    for (const [path, row] of Object.entries(item.payload)) {
      const actual = resolvePath(path, after.inventory);
      assert.equal(resolvePath(path, before.inventory), actual, 'overlay_path_resolution_changed');
      allowed.add(actual);
      if (row.kind === 'directory') {
        if (Object.hasOwn(before.inventory, actual))
          assert.deepEqual(after.inventory[actual], before.inventory[actual], 'overlay_existing_directory:' + path);
        else if (Object.hasOwn(after.inventory, actual))
          assert.deepEqual(after.inventory[actual], row, 'overlay_official_directory:' + path);
        else assert.ok(excluded(path, rules), 'overlay_missing_directory:' + path);
      } else if (excluded(path, rules)) {
        assert.ok(!Object.hasOwn(after.inventory, actual), 'overlay_filtered_path_present:' + path);
        assert.ok(!Object.hasOwn(before.inventory, actual) || oldListed.has(path), 'overlay_filtered_path_owner:' + path);
      } else assert.deepEqual(after.inventory[actual], row, 'overlay_official_payload:' + path);
    }
    for (const [name, value] of Object.entries(item.control)) if (name !== 'control') {
      assert.deepEqual(after.inventory[prefix + name], value, 'overlay_official_control:' + name);
      allowed.add(prefix + name);
    }
    allowed.add(prefix + 'list');
  }
  const changed = [...new Set([...Object.keys(before.inventory), ...Object.keys(after.inventory)])]
    .filter(path => !isDeepStrictEqual(before.inventory[path], after.inventory[path])).sort();
  assert.ok(spec.changedPrefixes.every(prefix => changed.some(path => path.startsWith(prefix))), 'overlay_required_change_missing');
  assert.ok(changed.every(path => allowed.has(path)), 'overlay_unrelated_payload');
  assert.deepEqual(receipt.changedFiles, changed, 'overlay_changed_files');
  assert.equal(receipt.packageInventorySha256, spec.packageInventory.sha256, 'overlay_receipt_package');
  for (const path of spec.protectedPaths) {
    assert.equal(after.inventory[path]?.kind, 'file', 'overlay_protected_missing:' + path);
    assert.deepEqual(after.inventory[path], before.inventory[path], 'overlay_protected_changed:' + path);
  }
  if (spec.component === 'keycloak')
    assert.equal(after.inventory['opt/keycloak/providers/otziv-security-generation.jar']?.sha256, publication.providerJarSha256,
      'overlay_provider_identity');
  return changed;
}

const checks = (record, names) => {
  assert.equal(record.result, 'PASS', 'overlay_rehearsal_failed');
  assert.ok(record.checks?.length && record.checks.every(check => check.passed === true), 'overlay_failed_check');
  for (const name of names) assert.equal(record.checks.filter(check => check.name === name && check.passed).length, 1,
    'overlay_missing_check:' + name);
};

// Capture reads the local prod-like source pair; replay and both rollback paths run the parent issuer.
export function validateOverlayRehearsal(spec, capture, replay, publication, sourceSha256) {
  const plan = spec.rehearsal;
  assert.ok(plan, 'overlay_rehearsal_not_planned');
  for (const record of [capture, replay]) {
    assert.equal(record.schema, overlaySchema(spec, 'rehearsal'), 'overlay_rehearsal_schema');
    assert.equal(record.sourceScope, 'LOCAL_PROD_LIKE_KEYCLOAK', 'overlay_source_scope');
    assert.equal(record.productionWrites, false, 'overlay_production_write');
    assert.equal(record.privateRowsPublished, false, 'overlay_private_rows');
    assert.equal(record.publishedPorts, 0, 'overlay_host_ports');
    assert.equal(record.sourcePostgres, plan.sourcePostgres, 'overlay_capture_postgres');
    assert.equal(record.sourcePostgresConfig, plan.sourcePostgresConfig, 'overlay_capture_postgres_config');
    assert.equal(record.keycloak, plan.sourceKeycloak, 'overlay_source_issuer');
    assert.equal(record.keycloakConfig, plan.sourceKeycloakConfig, 'overlay_source_issuer_config');
    assert.equal(record.replayBaselinePostgres, plan.replayPostgres, 'overlay_replay_pair');
    assert.equal(record.replayBaselineKeycloak, spec.parent.reference, 'overlay_replay_issuer');
    assert.equal(record.replayBaselineKeycloakConfig, spec.parent.configId, 'overlay_replay_issuer_config');
    assert.equal(record.executedScriptSha256, sourceSha256, 'overlay_executed_rehearsal');
    checks(record, []);
  }
  checks(capture, ['complete_custom_dump', 'source_runtime_unchanged', 'source_critical_identity_stable_during_capture']);
  assert.equal(replay.dumpSha256, capture.dumpSha256, 'overlay_dump_changed');
  assert.equal(replay.candidate, publication.reference, 'overlay_rehearsal_candidate');
  sameId(replay.candidateLocalId, publication.imageId, publication.reference);
  assert.equal(replay.candidatePostgres, plan.replayPostgres, 'overlay_postgres_changed');
  sameId(replay.candidatePostgresLocalId, plan.replayPostgresConfig, plan.replayPostgres);
  assert.deepEqual(replay.ownedResourcesRemaining, {container: 0, volume: 0, network: 0}, 'overlay_resource_leak');
  assert.ok(!replay.cleanupErrors?.length, 'overlay_cleanup_error');
  checks(replay, ['restored_critical_records_exact', 'capture_stable_tables_restored_exact', 'source_existing_password_login',
    'source_roles_and_credentials_preserved', 'same_volume_all_tables_exact', 'existing_admin_password_login',
    'existing_roles_and_credentials_preserved', 'unauthenticated_provider_denied', 'library_patch_no_schema_migration',
    'same_volume_rollback_preserves_all_post_upgrade_writes', 'same_volume_old_issuer_login',
    'same_volume_old_issuer_credentials_preserved', 'fresh_rollback_restore_preserves_all_post_upgrade_writes',
    'restored_existing_password_login', 'restored_roles_and_credentials_preserved']);
  assert.equal(replay.tableCount, plan.tableCount, 'overlay_table_coverage');
  for (const key of ['serverVersion', 'encoding', 'locale', 'clusterIdentifier', 'extensions', 'configSha256', 'hbaSha256', 'identSha256'])
    assert.deepEqual(replay.candidateDatabase[key], replay.sourceDatabase[key], 'overlay_database_changed:' + key);
}

// The unchanged capture guard requires one shared Internal network. The local
// pair is moved to a labelled temporary --internal network only for the capture.
export function validateOverlayCaptureWindow(spec, window, capture, captureSha256, sourceSha256) {
  const plan = spec.rehearsal;
  assert.ok(plan?.captureWindow, 'overlay_window_not_planned');
  assert.equal(window.schema, overlaySchema(spec, 'capture-window'), 'overlay_window_schema');
  assert.equal(window.result, 'PASS', 'overlay_window_failed');
  assert.equal(window.executedScriptSha256, sourceSha256, 'overlay_window_executed_script');
  assert.deepEqual([window.productionAccess, window.dataWrites, window.containersRestarted], [false, false, false],
    'overlay_window_scope');
  assert.ok(['npipe', 'unix'].includes(window.dockerEndpoint), 'overlay_window_local_docker');
  assert.equal(window.originalNetwork, 'otziv-prod-local_internal_net', 'overlay_window_original_network');
  const temporary = window.temporaryNetwork?.name, base = `otziv-${spec.namePrefix}-capture`;
  assert.ok(typeof temporary === 'string' && new RegExp('^' + base + '-[a-f0-9]{12}$').test(temporary), 'overlay_window_temporary_network');
  assert.equal(window.temporaryNetwork.label, `com.otziv.${spec.namePrefix}-capture.owner=` + temporary.slice(-12), 'overlay_window_ownership');
  assert.equal(window.temporaryNetwork.internal, true, 'overlay_window_not_internal');
  assert.equal(window.temporaryNetworkRemaining, 0, 'overlay_window_network_leak');
  const images = {keycloak: plan.sourceKeycloak, 'keycloak-postgres': plan.sourcePostgres};
  assert.deepEqual(Object.keys(window.before).sort(), Object.keys(images).sort(), 'overlay_window_services');
  const keys = ['id', 'imageId', 'startedAt', 'restarts', 'running', 'networks', 'portBindings'];
  for (const [service, reference] of Object.entries(images)) {
    const before = window.before[service], after = window.after?.[service], isolated = window.isolated?.[service];
    const source = capture.sourceBefore.containers[service === 'keycloak' ? 'keycloak' : 'postgres'];
    assert.equal(before.imageId, 'sha256:' + reference.split('@sha256:')[1], 'overlay_window_image:' + service);
    assert.deepEqual([before.running, before.networks, before.portBindings], [true, [window.originalNetwork], {}],
      'overlay_window_source_state:' + service);
    assert.deepEqual(isolated, {networks: [temporary], aliases: before.attachment.aliases}, 'overlay_window_isolation:' + service);
    assert.deepEqual(Object.fromEntries(keys.map(key => [key, after?.[key]])), Object.fromEntries(keys.map(key => [key, before[key]])),
      'overlay_window_runtime_changed:' + service);
    assert.deepEqual(after.attachment, before.attachment, 'overlay_window_not_restored:' + service);
    assert.deepEqual({id: source.id, startedAt: source.startedAt}, {id: before.id, startedAt: before.startedAt},
      'overlay_window_capture_source:' + service);
  }
  assert.deepEqual([window.restoredExactly, window.addressesPreserved, window.stackStatus], [true, true, 200], 'overlay_window_restore');
  assert.deepEqual([window.captureExitCode, window.captureResult, window.captureSha256], [0, 'PASS', captureSha256],
    'overlay_window_capture');
  const times = [window.windowOpenedAt, window.captureStartedAt, capture.startedAt, capture.completedAt,
    window.captureCompletedAt, window.windowClosedAt].map(value => Date.parse(value));
  assert.ok(times.every(Number.isFinite) && times.every((value, index) => index === 0 || times[index - 1] <= value),
    'overlay_window_order');
}

export async function validateOverlayActivation(spec, publication, entry, read, validateParent) {
  const key = spec.acceptanceKey;
  assert.equal(entry.manifest?.path, spec.manifestPath, 'overlay_manifest');
  if (spec.component === 'keycloak') assert.deepEqual(entry.migrationAcceptance, entry[key], 'overlay_migration_binding');
  else assert.equal(entry.migrationAcceptance, undefined, 'overlay_unexpected_migration');
  assert.equal(Object.keys(entry).filter(name => name.endsWith('RefreshAcceptance')).join(), key, 'overlay_acceptance_key');
  assert.equal(entry[key]?.path, spec.proofRoot + 'acceptance.json', 'overlay_acceptance_path');
  const bytes = await read(entry[key].path);
  assert.equal(hash(bytes), entry[key].sha256, 'overlay_acceptance_digest');
  const value = JSON.parse(bytes);
  assert.equal(value.schema, overlaySchema(spec, 'acceptance'), 'overlay_acceptance_schema');
  assert.equal(value.result, 'PASS', 'overlay_acceptance_result');
  assert.equal(value.component, spec.component, 'overlay_acceptance_component');
  assert.equal(value.reference, publication.reference, 'overlay_acceptance_candidate');
  assert.equal(value.imageConfigId, publication.imageId, 'overlay_acceptance_config');
  assert.equal(value.parentReference, spec.parent.reference, 'overlay_acceptance_parent');
  assert.equal(value.parentConfigId, spec.parent.configId, 'overlay_acceptance_parent_config');
  assert.equal(value.advisory, spec.advisory, 'overlay_acceptance_advisory');
  assert.deepEqual(value.packages, spec.packages.map(item => ({name: item.name, version: item.version, sha256: item.sha256})),
    'overlay_acceptance_packages');
  for (const name of ['commit', 'run', 'attempt']) assert.equal(value[name], entry[name], 'overlay_publication_identity');
  assert.deepEqual(Object.keys(value.files).sort(), spec.proofs.map(name => spec.proofRoot + name).sort(), 'overlay_proof_set');
  const files = new Map();
  for (const [path, digest] of Object.entries(value.files)) {
    assert.ok(path.startsWith(spec.proofRoot) && !/[\\:]/.test(path) && path.split('/').every(part => part && part !== '.' && part !== '..'),
      'overlay_proof_path');
    const content = await read(path); assert.equal(hash(content), digest, 'overlay_proof_digest');
    files.set(path.slice(spec.proofRoot.length), content);
  }
  const take = name => { assert.ok(files.has(name), 'overlay_missing_proof:' + name); return files.get(name); };
  const inspection = JSON.parse(take('inspection/inspection.json'));
  const runtimeBytes = gunzipSync(take('inspection/runtime.json.gz'));
  assert.equal(hash(runtimeBytes), inspection.runtimeSha256, 'overlay_runtime_digest');
  const runtime = JSON.parse(runtimeBytes);
  validateOverlayRuntime(spec, runtime, inspection, publication, await read(spec.packageInventory.path));
  assert.equal(inspection.executedScriptSha256, hash(take('executed-inspection.py.txt')), 'overlay_executed_inspector');
  assert.equal(inspection.inventoryScriptSha256, hash(take('executed-runtime-inventory.py.txt')), 'overlay_executed_inventory');
  if (spec.rehearsal) {
    const capture = JSON.parse(take('rehearsal/capture.json'));
    validateOverlayRehearsal(spec, capture, JSON.parse(take('rehearsal/replay.json')), publication, hash(take('executed-rehearsal.py.txt')));
    if (spec.rehearsal.captureWindow)
      validateOverlayCaptureWindow(spec, JSON.parse(take('rehearsal/capture-window.json')), capture,
        hash(take('rehearsal/capture.json')), hash(take('executed-capture-window.py.txt')));
  }
  const raw = JSON.parse(await read(dirname(entry.publication.path) + '/vulnerabilities.json'));
  assert.equal(raw.Metadata.ImageID, publication.imageId, 'overlay_scan_image');
  assert.equal(raw.Results.flatMap(result => result.Vulnerabilities || []).filter(item => item.VulnerabilityID === spec.advisory).length,
    0, 'overlay_advisory_still_reported');
  const [family] = spec.distribution.split('-');
  for (const item of spec.packages) {
    const expected = scannerVersion(item.version);
    const found = raw.Results.flatMap(result => result.Packages || []).filter(pkg => pkg.Name === item.name);
    assert.ok(found.length > 0 && found.every(pkg => pkg.Version === expected.version && (pkg.Release || '') === expected.release
      && (pkg.Epoch || 0) === expected.epoch && pkg.Arch === 'amd64'
      && pkg.Identifier?.PURL === `pkg:deb/${family}/${item.name}@${packageFileVersion(item.version)}?arch=amd64`
        + (expected.epoch ? `&epoch=${expected.epoch}` : '') + `&distro=${spec.distribution}`), 'overlay_runtime_package_scan');
  }
  const parentBytes = await read(spec.parent.activation.path);
  assert.equal(hash(parentBytes), spec.parent.activation.sha256, 'overlay_parent_activation_digest');
  const parent = JSON.parse(parentBytes), baseline = await read('infrastructure/runtime-security/reviewed-images.json');
  assert.equal(parent.reference, spec.parent.reference, 'overlay_parent_activation');
  assert.equal(parent.component, spec.component, 'overlay_parent_component');
  await validateParent(JSON.parse(baseline).images.find(image => image.component === spec.component), parent, baseline, read);
  const parentPublication = JSON.parse(await read(parent.publication.path));
  assert.equal(parentPublication.imageId, spec.parent.configId, 'overlay_parent_config');
  if (spec.component === 'keycloak')
    assert.equal(parentPublication.providerJarSha256, publication.providerJarSha256, 'overlay_provider_rebuilt');
  else {
    const binary = runtime.candidate.inventory['usr/bin/alloy']?.sha256;
    const receipts = [];
    for (const path of [parent.publication.path, entry.publication.path])
      receipts.push(JSON.parse(await read(dirname(path) + '/vulnerabilities.adjudications.json')).alloy);
    for (const receipt of receipts) assert.equal(receipt?.binarySha256, binary, 'overlay_alloy_binary_rebuilt');
    assert.deepEqual(Object.fromEntries(ALLOY_BINARY_FIELDS.map(name => [name, receipts[1][name]])),
      Object.fromEntries(ALLOY_BINARY_FIELDS.map(name => [name, receipts[0][name]])), 'overlay_alloy_binary_review_changed');
  }
  for (const [which, record, path] of [['parent', parentPublication, parent.publication.path], ['candidate', publication, entry.publication.path]]) {
    const configBytes = await read(dirname(path) + '/registry-amd64-config.json');
    assert.equal('sha256:' + hash(configBytes), record.imageId, 'overlay_registry_config');
    assert.deepEqual(runtime[which].rootfs, JSON.parse(configBytes).rootfs.diff_ids, 'overlay_registry_rootfs');
  }
  return value;
}

export async function coupleOverlayKeycloak(spec, readiness, entry, read, validateParent) {
  assert.equal(spec.component, 'keycloak', 'overlay_coupling_component');
  assert.ok(readiness.postgresC36Acceptance, 'overlay_database_acceptance_missing');
  assert.equal(readiness.postgresReference, spec.coupling.postgresReference, 'overlay_database_pair');
  assert.equal(readiness.postgresConfigId, spec.coupling.postgresConfigId, 'overlay_database_config');
  assert.deepEqual(readiness.postgresC36Pair, {keycloakReference: spec.coupling.historicalKeycloakReference,
    keycloakConfigId: spec.coupling.historicalKeycloakConfigId}, 'overlay_parent_database_pair');
  assert.equal(spec.status, 'accepted', 'overlay_runtime_and_data_acceptance_required');
  const publication = JSON.parse(await read(entry.publication.path));
  const accepted = await validateOverlayActivation(spec, publication, entry, read, validateParent);
  return {...readiness, requiredKeycloakReference: entry.reference, requiredKeycloakConfigId: accepted.imageConfigId,
    acceptancePath: entry[spec.acceptanceKey].path, proofSha256: entry[spec.acceptanceKey].sha256};
}
