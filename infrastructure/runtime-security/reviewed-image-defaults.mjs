import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstat, open, readFile, readdir, realpath } from 'node:fs/promises';
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';
import { inventory, repositoryInventory } from './upstream-images.mjs';
import { validateManifest } from './publish-reviewed-images.mjs';
import { assertPulledImage, validatePublication } from './verify-anonymous-download.mjs';
import { checkedJson, SOURCE_REPOSITORY, verifyRegistryEvidence } from './registry-evidence.mjs';
import { checkKeycloakRuntimeDependencies, requiresKeycloakDependencyProof } from './keycloak-runtime-dependencies.mjs';
import { loadAlloyProof, matchingAlloyFindings, effectiveAlloySummary } from './alloy-daemon-adjudication.mjs';
import { effectiveScanSummary } from './grafana-tempo-adjudication.mjs';
import { summarizeReport, TRIVY_IMAGE } from './scan.mjs';
import { BUILD_INFO_READER } from './go-binary-inspection.mjs';
import { assertPublicationSet, reviewedImageSetForComponent, supplementalReviewedSources, validateReviewedImageSet } from './reviewed-image-sets.mjs';
import { validateDatabaseTransitionReadiness } from './database-transition-readiness.mjs';
import { validatePostgresActivationScan } from './postgres-activation-proof.mjs';
import { validatePostgresTransitionReadiness, validatePublishedKeycloakMigrationAcceptance,
  assertPostgresKeycloakCoupling } from './postgres-transition-readiness.mjs';

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const DATABASE_HOLD = new Set(['mysql', 'postgres']);
const MANIFEST = 'infrastructure/runtime-security/reviewed-images.json';
// The C7 publication binds these exact bytes, including the original service/path coverage.
const MANIFEST_SHA256 = 'd48bdb7d6d869cde5d6f1a7b089491765e3eeaf46e0b0b6bb51b7719b38d3ef2';
const ACTIVATIONS = 'infrastructure/runtime-security/reviewed-image-activations.json';
const EVIDENCE_FILE = /^registry-(?:index|amd64-(?:manifest|config)|attestation-[0-9]+(?:-payload-[0-9]+)?)\.json$/;

export async function validateAlloyReassessment(image, entry, publication, proof) {
  // The original C7 publication remains immutable. This later finding review is
  // allowed only for the single reproduced Alloy executable, never other images.
  assert.equal(image.component, 'alloy', 'activation_unresolved_security_review');
  const { review, reviewSha256, files, closure } = await loadAlloyProof(new Date(), publication.imageId);
  assert.equal(publication.reference, review.reference, 'activation_reassessment_reference');
  assert.equal(publication.imageId, review.imageConfigId, 'activation_reassessment_image');
  const reassessment = entry.securityReassessment;
  assert.ok(reassessment, 'activation_unresolved_security_review');
  assert.deepEqual(Object.keys(reassessment).sort(), ['adjudication', 'report', 'summary'], 'activation_reassessment_fields');
  const raw = await proof(reassessment.report), receipt = await proof(reassessment.adjudication), summary = await proof(reassessment.summary);
  assert.equal(raw.value.Metadata?.ImageID, publication.imageId, 'activation_reassessment_report_image');
  const reviewedReport = JSON.parse(gunzipSync(files.get('reviewed-scan.json.gz')));
  assert.deepEqual(raw.value.Metadata?.ImageConfig?.rootfs, reviewedReport.Metadata.ImageConfig.rootfs, 'activation_reassessment_report_rootfs');
  assert.deepEqual(raw.value.Metadata?.ImageConfig?.config?.Labels, reviewedReport.Metadata.ImageConfig.config.Labels, 'activation_reassessment_report_labels');
  const actual = receipt.value.alloy;
  assert.equal(actual?.schema, 'otziv-alloy-adjudication-v1', 'activation_reassessment_schema');
  assert.equal(actual.status, 'EXACT_BINARY_AFFECTED_CODE_ABSENT', 'activation_reassessment_not_proven');
  assert.equal(actual.reviewSha256, reviewSha256, 'activation_reassessment_review_changed');
  assert.equal(actual.rawReportSha256, sha256(raw.bytes), 'activation_reassessment_raw_report_changed');
  assert.equal(actual.imageConfigId, publication.imageId, 'activation_reassessment_receipt_image');
  assert.ok([publication.imageId, publication.reference.split('@')[1]].includes(actual.immutableImageId), 'activation_reassessment_inspected_image');
  assert.equal(actual.binarySha256, review.binary.sha256, 'activation_reassessment_binary');
  assert.equal(actual.canonicalBuildInfoSha256, review.binary.canonicalBuildInfoSha256, 'activation_reassessment_build_info');
  assert.equal(actual.buildInfoReader, BUILD_INFO_READER, 'activation_reassessment_parser');
  assert.deepEqual(actual.closure, closure, 'activation_reassessment_closure');
  assert.deepEqual(actual.module, review.module, 'activation_reassessment_module');
  assert.equal(actual.rawReportModified, false, 'activation_reassessment_raw_modified');
  assert.equal(actual.inspectedBinaryExecuted, false, 'activation_reassessment_inspection_mode');
  assert.equal(actual.validUntil, review.validUntil, 'activation_reassessment_expiry');
  assert.deepEqual(actual.decisions, matchingAlloyFindings(raw.value, review), 'activation_reassessment_decisions');
  assert.equal(actual.decisions.length, 2, 'activation_reassessment_scope_incomplete');
  const expected = { ...effectiveAlloySummary(effectiveScanSummary(summarizeReport(raw.value), null), actual), scannerImage: TRIVY_IMAGE };
  assert.deepEqual(summary.value, expected, 'activation_reassessment_summary_changed');
  assert.equal(expected.result, 'PASS', 'activation_reassessment_security_failed');
  assert.equal(expected.unresolvedRiskReview, 'NONE', 'activation_unresolved_security_review');
  assert.equal(expected.effectiveBlockingFixedHighOrCritical, 0, 'activation_reassessment_fixed_findings');
}

export async function resolveActivationManifest(image, entry, manifestBytes, proof) {
  if (['mc', 'minio'].includes(image.component)) assert.ok(entry.manifest, 'activation_versioned_manifest_required');
  if (entry.manifest === undefined) return { image, manifestBytes, manifestSet: 'baseline' };
  assert.ok(entry.manifest && typeof entry.manifest === 'object', 'activation_versioned_manifest_missing');
  assert.deepEqual(Object.keys(entry.manifest).sort(), ['path', 'sha256'], 'activation_versioned_manifest_fields');
  const selected = reviewedImageSetForComponent(image.component, entry.manifest.path);
  assert.equal(entry.manifest.path, selected.path, 'activation_versioned_manifest_path');
  const loaded = await proof(entry.manifest);
  const manifest = validateReviewedImageSet(selected.name, loaded.bytes, manifestBytes);
  const [publicationImage] = validateManifest(manifest);
  assert.equal(publicationImage.sourceBeforeRef, image.sourceBeforeRef, 'activation_versioned_source_mismatch');
  assert.deepEqual(publicationImage.defaultReferencesBefore, image.defaultReferencesBefore, 'activation_versioned_coverage_mismatch');
  return { image: publicationImage, manifestBytes: loaded.bytes, manifestSet: selected.name };
}

export async function validateUnadjudicatedActivationScan(publication, publicationPath, read) {
  assert.ok(['mc', 'minio'].includes(publication.component), 'activation_raw_scan_component');
  const directory = dirname(publicationPath).replaceAll('\\', '/');
  const [rawBytes, receiptBytes, triageBytes] = await Promise.all([
    read(directory + '/vulnerabilities.json'), read(directory + '/vulnerabilities.adjudications.json'),
    read(directory + '/vulnerabilities.triage.json')]);
  const raw = JSON.parse(rawBytes), receipt = JSON.parse(receiptBytes), triage = JSON.parse(triageBytes);
  // These fields are already emitted by scan.mjs. Do not rewrite historical
  // publication receipts or invent a security.imageId/raw-hash field in them.
  assert.equal(receipt.schema, 'otziv-image-adjudication-v1', 'activation_raw_scan_receipt_schema');
  assert.equal(receipt.rawReportSha256, sha256(rawBytes), 'activation_raw_scan_hash');
  assert.equal(receipt.imageConfigId, publication.imageId, 'activation_raw_scan_receipt_image');
  assert.ok([publication.imageId, publication.reference.split('@')[1]].includes(receipt.immutableImageId), 'activation_raw_scan_inspected_image');
  assert.equal(receipt.rawReportModified, false, 'activation_raw_scan_modified');
  assert.equal(receipt.status, 'NOT_APPLICABLE', 'activation_raw_scan_adjudication');
  assert.deepEqual(receipt.decisions, [], 'activation_raw_scan_adjudication');
  assert.equal(triage.schema, 'otziv-runtime-triage-v1', 'activation_raw_scan_triage_schema');
  assert.equal(triage.reportSHA256, sha256(rawBytes), 'activation_raw_scan_triage_hash');
  assert.equal(triage.imageId, publication.imageId, 'activation_raw_scan_triage_image');
  assert.equal(raw.Metadata?.ImageID, publication.imageId, 'activation_raw_scan_image');
  assert.equal(triage.scanCreatedAt, raw.CreatedAt, 'activation_raw_scan_created_at');
  const actual = summarizeReport(raw);
  assert.ok(raw.Results.some(result => result.Class === 'os-pkgs' && result.Packages?.length > 0)
    && raw.Results.some(result => result.Type === 'gobinary' && result.Packages?.length > 0), 'activation_raw_scan_coverage');
  const summary = publication.security;
  for (const [key, value] of Object.entries(actual)) assert.equal(summary[key], value, 'activation_raw_scan_summary_' + key);
  // Neither local S3 candidate has a reviewed exception. Raw HIGH/CRITICAL
  // findings cannot be hidden behind a successful presentation summary.
  assert.equal(actual.high + actual.critical, 0, 'activation_raw_scan_findings');
  assert.deepEqual(triage.findings, [], 'activation_raw_scan_triage_findings');
  for (const key of ['adjudicatedFixedHighOrCritical', 'effectiveBlockingFixedHighOrCritical',
    'adjudicatedAbsentFixedHighOrCritical', 'adjudicatedAbsentUnfixedHighOrCritical', 'effectiveUnfixedHighOrCritical']) {
    assert.equal(summary[key], 0, 'activation_raw_scan_effective_counts');
  }
  assert.equal(summary.result, 'PASS', 'activation_raw_scan_failed');
  assert.equal(summary.unresolvedRiskReview, 'NONE', 'activation_raw_scan_unresolved');
  assert.equal(summary.scannerImage, TRIVY_IMAGE, 'activation_raw_scan_scanner');
}

export async function validateActivation(image, entry, manifestBytes, read) {
  assert.equal(entry.component, image.component, 'activation_component_mismatch');
  assert.match(entry.commit || '', /^[a-f0-9]{40}$/, 'activation_commit_missing');
  for (const key of ['run', 'attempt']) assert.match(entry[key] || '', /^[1-9][0-9]*$/, 'activation_run_missing');
  async function proof(input) {
    assert.ok(input && typeof input.path === 'string', 'activation_proof_path_missing');
    assert.match(input.sha256 || '', /^[a-f0-9]{64}$/, 'activation_proof_hash_missing');
    const bytes = await read(input.path);
    assert.equal(sha256(bytes), input.sha256, 'activation_proof_hash_mismatch');
    return { bytes, value: JSON.parse(bytes) };
  }
  const selected = await resolveActivationManifest(image, entry, manifestBytes, proof);
  const publicationImage = selected.image;
  const publication = await proof(entry.publication);
  const anonymous = await proof(entry.anonymous);
  const identity = { commit: entry.commit, run: entry.run, attempt: entry.attempt };
  const digest = validatePublication(publication.value, identity, publicationImage, sha256(selected.manifestBytes), selected.manifestSet);
  if (publicationImage.component === 'postgres') {
    await validatePostgresActivationScan(publication.value, entry.publication.path, read);
  }
  if (['mc', 'minio'].includes(publicationImage.component)) {
    await validateUnadjudicatedActivationScan(publication.value, entry.publication.path, read);
  }
  if (requiresKeycloakDependencyProof(publicationImage)) {
    const checked = checkKeycloakRuntimeDependencies(
      await read(dirname(entry.publication.path).replaceAll('\\', '/') + '/vulnerabilities.json'), publication.value.imageId);
    assert.deepEqual(checked, publication.value.knownRuntimeDependencies, 'activation_known_dependencies_changed');
  }
  if (['c14-keycloak', 'c15-keycloak'].includes(selected.manifestSet)) {
    await validatePublishedKeycloakMigrationAcceptance(publication.value, entry, read);
  }
  assert.equal(entry.reference, publication.value.reference, 'activation_registered_reference_mismatch');
  assert.equal(publication.value.security.effectiveBlockingFixedHighOrCritical, 0, 'activation_security_severity_mismatch');
  if (publication.value.security.unresolvedRiskReview === 'NONE') {
    assert.equal(entry.securityReassessment, undefined, 'activation_unnecessary_reassessment');
  } else {
    assert.equal(publication.value.security.unresolvedRiskReview, 'REQUIRED', 'activation_unresolved_security_review');
    await validateAlloyReassessment(image, entry, publication.value, proof);
  }
  const downloaded = anonymous.value;
  assertPublicationSet(downloaded, selected.manifestSet);
  assert.equal(downloaded.schema, 'otziv-anonymous-download-v1', 'activation_anonymous_schema');
  assert.equal(downloaded.result, 'PASS', 'activation_anonymous_not_passed');
  for (const key of ['commit', 'run', 'attempt']) assert.equal(downloaded[key], identity[key], 'activation_anonymous_identity_mismatch');
  assert.equal(downloaded.component, image.component, 'activation_anonymous_component_mismatch');
  assert.equal(downloaded.reference, entry.reference, 'activation_anonymous_reference_mismatch');
  assert.equal(downloaded.imageId, publication.value.imageId, 'activation_anonymous_image_mismatch');
  assert.equal(downloaded.sourcePublicationSha256, sha256(publication.bytes), 'activation_publication_pair_mismatch');
  assert.equal(downloaded.publicDownloadReadiness, 'VERIFIED_ANONYMOUS_DIGEST_PULL', 'activation_anonymous_pull_missing');
  assert.equal(downloaded.platform, 'linux/amd64', 'activation_anonymous_platform_mismatch');
  assert.equal(downloaded.imageAbsentBeforePull, true, 'activation_anonymous_not_fresh');
  assert.equal(downloaded.accountCredentialsUsed, false, 'activation_anonymous_credentials');
  assert.equal(downloaded.dockerCredentialHelpersAvailable, false, 'activation_anonymous_helpers');
  assert.equal(downloaded.dockerTransport, 'unix:///var/run/docker.sock', 'activation_anonymous_transport');
  assert.equal(downloaded.pullExitCode, 0, 'activation_anonymous_pull_failed');
  const expected = { source: SOURCE_REPOSITORY, commit: identity.commit, context: publicationImage.context,
    dockerfile: publicationImage.dockerfile, dockerfileSha256: publicationImage.dockerfileSha256 };
  for (const [document, path] of [[publication.value, entry.publication.path], [downloaded, entry.anonymous.path]]) {
    const blobs = new Map(), names = new Set();
    assert.ok(document.attestationEvidence?.artifacts?.length, 'activation_registry_evidence_missing');
    for (const artifact of document.attestationEvidence.artifacts) {
      assert.match(artifact.file || '', EVIDENCE_FILE, 'activation_registry_filename');
      assert.ok(!names.has(artifact.file), 'activation_registry_duplicate');
      names.add(artifact.file);
      const bytes = await read(dirname(path).replaceAll('\\', '/') + '/' + artifact.file);
      checkedJson(bytes, artifact);
      if (artifact.file === 'registry-amd64-config.json') assert.equal(artifact.digest, publication.value.imageId, 'activation_registry_image_mismatch');
      blobs.set(artifact.digest, bytes);
    }
    const actual = await verifyRegistryEvidence({ digest, expected,
      read: async (kind, hash) => { assert.ok(blobs.has(hash), 'activation_registry_blob_missing'); return blobs.get(hash); },
      retain: async () => {} });
    assert.deepEqual(actual, document.attestationEvidence, 'activation_registry_record_mismatch');
  }
  assert.deepEqual(downloaded.attestationEvidence, publication.value.attestationEvidence, 'activation_registry_pair_mismatch');
  assertPulledImage(JSON.parse(await read(dirname(entry.anonymous.path).replaceAll('\\', '/') + '/anonymous-image-inspect.json')), publication.value);
  return entry.reference;
}

export async function validateReviewedDefaults(rows, manifestBytes, activations, read, includeSupplementalSources = false) {
  assert.equal(typeof includeSupplementalSources, 'boolean', 'activation_supplemental_sources_flag');
  const images = [...validateManifest(JSON.parse(manifestBytes)), ...(includeSupplementalSources ? supplementalReviewedSources() : [])];
  if (activations) assert.equal(activations.schema, 'otziv-reviewed-image-activations-v1', 'activation_index_schema');
  const entries = activations?.images || [];
  assert.ok(Array.isArray(entries), 'activation_index_images');
  const registered = new Map();
  const databasePreparations = new Map();
  for (const entry of entries) {
    assert.ok(images.some(image => image.component === entry.component) && !registered.has(entry.component), 'activation_unknown_or_duplicate_component');
    if (DATABASE_HOLD.has(entry.component)) {
      assert.ok(entry.databaseTransition, 'activation_database_coordinated_transition_required');
      const image = images.find(image => image.component === entry.component);
      databasePreparations.set(entry.component, await (entry.component === 'mysql'
        ? validateDatabaseTransitionReadiness(entry, image, read)
        : validatePostgresTransitionReadiness(entry, image, read)));
    }
    registered.set(entry.component, entry);
  }
  if (databasePreparations.has('postgres')) {
    assertPostgresKeycloakCoupling(databasePreparations.get('postgres'), registered.get('keycloak'), rows,
      images.find(image => image.component === 'keycloak'));
  }
  const checks = [];
  for (const image of images) {
    assert.ok(image.defaultReferencesBefore?.length, 'reviewed_default_reference_missing');
    const source = inventory([{ path: 'source', text: 'services:\n  image:\n    image: ' + image.sourceBeforeRef + '\n' }])[0].image;
    let published;
    for (const reference of image.defaultReferencesBefore) {
      const matches = rows.filter(row => row.references.some(item => item.path === reference.path && item.service === reference.service));
      assert.equal(matches.length, 1, 'reviewed_default_service_missing_or_duplicate');
      const actual = matches[0].image;
      if (actual !== source) {
        assert.ok(!DATABASE_HOLD.has(image.component) || databasePreparations.has(image.component), 'activation_database_coordinated_transition_required');
        const entry = registered.get(image.component);
        assert.ok(entry, 'reviewed_default_unregistered_reference');
        published ||= await validateActivation(image, entry, manifestBytes, read);
        assert.equal(actual, published, 'reviewed_default_wrong_component_reference');
      }
      checks.push({ component: image.component, path: reference.path, service: reference.service,
        reference: actual, mode: actual === source ? 'EXACT_ORIGINAL_SOURCE' : 'PAIRED_PUBLICATION_AND_ANONYMOUS_EVIDENCE',
        ...(actual !== source && databasePreparations.has(image.component) ? {
          databaseTransition: databasePreparations.get(image.component).mode,
          ordinaryDeploymentUpgradeAuthorized: false,
        } : {}) });
    }
  }
  return checks;
}

// Only oversized OCI statement blobs use this storage representation. The logical
// bytes still pass checkedJson and the complete OCI descriptor chain above.
const PART_BYTES = 4 * 1024 * 1024;
const FILE_GATE_BYTES = 5 * 1024 * 1024;
const PARTS_MANIFEST_BYTES = 4096;
const SPLIT_EVIDENCE_FILE = /^registry-attestation-[0-9]+-payload-[0-9]+\.json$/;

export async function createEvidenceReader(root) {
  root = await realpath(root);
  const allowedRoots = ['infrastructure/runtime-security',
    'infrastructure/keycloak/security-generation/c14-migration-fix',
    'infrastructure/keycloak/security-generation/c15-netty'];
  const allowedFiles = ['infrastructure/keycloak/security-generation/container-proof.mjs'];
  const evidenceRoot = await realpath(resolve(root, 'infrastructure/runtime-security'));
  const evidenceRelative = relative(root, evidenceRoot);
  assert.ok(evidenceRelative && !isAbsolute(evidenceRelative) && evidenceRelative !== '..'
    && !evidenceRelative.startsWith('..' + sep)
    && relative(resolve(root, allowedRoots[0]), evidenceRoot) === '', 'activation_evidence_root_symlink_escape');
  function pathInScope(path) {
    assert.ok(typeof path === 'string' && (allowedFiles.includes(path) || allowedRoots.some(prefix => path.startsWith(prefix + '/'))) && !isAbsolute(path)
      && !path.includes('\\') && !path.includes(':')
      && path.split('/').every(part => part && part !== '.' && part !== '..'), 'activation_path_outside_evidence_scope');
    return resolve(root, path);
  }
  async function physicalPath(path) {
    const actual = await realpath(pathInScope(path));
    const prefix = allowedFiles.includes(path) ? dirname(path) : allowedRoots.find(prefix => path.startsWith(prefix + '/'));
    const selectedRoot = prefix === allowedRoots[0] ? evidenceRoot : await realpath(resolve(root, prefix));
    const rootInside = relative(root, selectedRoot);
    assert.ok(rootInside && !isAbsolute(rootInside) && rootInside !== '..'
      && !rootInside.startsWith('..' + sep)
      && relative(resolve(root, prefix), selectedRoot) === '', 'activation_evidence_root_symlink_escape');
    const inside = relative(selectedRoot, actual);
    assert.ok(inside && !isAbsolute(inside) && inside !== '..' && !inside.startsWith('..' + sep), 'activation_evidence_symlink_escape');
    return actual;
  }
  async function boundedRead(path, maximum) {
    assert.ok((await lstat(pathInScope(path))).isFile(), 'activation_parts_regular_file_required');
    const actual = await physicalPath(path);
    const file = await open(actual, 'r');
    try {
      const stat = await file.stat();
      assert.ok(stat.isFile() && stat.size > 0 && stat.size <= maximum, 'activation_parts_file_size');
      // One extra byte also detects growth after stat without an unbounded read.
      const buffer = Buffer.alloc(maximum + 1);
      let offset = 0;
      while (offset < buffer.length) {
        const { bytesRead } = await file.read(buffer, offset, buffer.length - offset, null);
        if (!bytesRead) break;
        offset += bytesRead;
      }
      assert.equal(offset, stat.size, 'activation_parts_file_changed');
      return buffer.subarray(0, offset);
    } finally { await file.close(); }
  }
  function exactKeys(value, names) {
    assert.ok(value && typeof value === 'object' && !Array.isArray(value), 'activation_parts_object');
    assert.deepEqual(Object.keys(value).sort(), [...names].sort(), 'activation_parts_fields');
  }
  return async path => {
    const resolved = pathInScope(path);
    let exists;
    try { exists = await lstat(resolved); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    // Preserve the original-file path, including its existing outer hash checks.
    // A dangling link is not an absent original and may not trigger the fallback.
    if (exists) return readFile(await physicalPath(path));
    const name = basename(path);
    if (!SPLIT_EVIDENCE_FILE.test(name)) return readFile(await physicalPath(path));
    const metadata = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(
      await boundedRead(path + '.parts.json', PARTS_MANIFEST_BYTES)));
    exactKeys(metadata, ['schema', 'bytes', 'sha256', 'parts']);
    assert.equal(metadata.schema, 'otziv-raw-evidence-parts-v1', 'activation_parts_schema');
    assert.ok(Number.isSafeInteger(metadata.bytes) && metadata.bytes > FILE_GATE_BYTES
      && metadata.bytes <= 2 * PART_BYTES, 'activation_parts_total_size');
    assert.match(metadata.sha256 || '', /^[a-f0-9]{64}$/, 'activation_parts_digest');
    assert.ok(Array.isArray(metadata.parts) && metadata.parts.length === 2, 'activation_parts_count');
    const expectedNames = [name + '.part001', name + '.part002'];
    for (const [index, part] of metadata.parts.entries()) {
      exactKeys(part, ['file', 'bytes', 'sha256']);
      assert.equal(part.file, expectedNames[index], 'activation_parts_filename_or_order');
      assert.ok(Number.isSafeInteger(part.bytes) && part.bytes > 0 && part.bytes <= PART_BYTES, 'activation_parts_chunk_size');
      assert.match(part.sha256 || '', /^[a-f0-9]{64}$/, 'activation_parts_chunk_digest');
    }
    assert.equal(metadata.parts.reduce((sum, part) => sum + part.bytes, 0), metadata.bytes, 'activation_parts_size_sum');
    const directory = dirname(await physicalPath(path + '.parts.json'));
    const found = (await readdir(directory)).filter(file => file.startsWith(name + '.part')).sort();
    assert.deepEqual(found, [...expectedNames, name + '.parts.json'].sort(), 'activation_parts_extra_or_missing_file');
    const buffers = [];
    for (const part of metadata.parts) {
      const bytes = await boundedRead(dirname(path).replaceAll('\\', '/') + '/' + part.file, part.bytes);
      assert.equal(bytes.length, part.bytes, 'activation_parts_chunk_bytes');
      assert.equal(sha256(bytes), part.sha256, 'activation_parts_chunk_hash');
      new TextDecoder('utf-8', { fatal: true }).decode(bytes);
      buffers.push(bytes);
    }
    const bytes = Buffer.concat(buffers, metadata.bytes);
    assert.equal(bytes.length, metadata.bytes, 'activation_parts_total_bytes');
    assert.equal(sha256(bytes), metadata.sha256, 'activation_parts_hash');
    return bytes;
  };
}

export async function validateRepositoryDefaults(root = process.cwd(), rows) {
  const read = await createEvidenceReader(root);
  const manifest = await read(MANIFEST);
  assert.equal(sha256(manifest), MANIFEST_SHA256, 'reviewed_frozen_manifest_changed');
  let activations;
  try { activations = JSON.parse(await read(ACTIVATIONS)); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  return validateReviewedDefaults(rows || await repositoryInventory(root), manifest, activations, read, true);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const checks = await validateRepositoryDefaults(process.argv[2] || process.cwd());
  console.log(JSON.stringify({ result: 'PASS', reviewedReferences: checks.length,
    databaseDefaults: checks.some(check => check.databaseTransition) ? 'COORDINATED_CANDIDATE_REQUIRES_EXPLICIT_CUTOVER' : 'ORIGINAL_SOURCE_HOLD', networkCalls: 0 }));
}
