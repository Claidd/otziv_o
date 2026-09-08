import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstat, open, readFile, readdir, realpath } from 'node:fs/promises';
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inventory, repositoryInventory } from './upstream-images.mjs';
import { validateManifest } from './publish-reviewed-images.mjs';
import { assertPulledImage, validatePublication } from './verify-anonymous-download.mjs';
import { checkedJson, SOURCE_REPOSITORY, verifyRegistryEvidence } from './registry-evidence.mjs';
import { checkKeycloakRuntimeDependencies, requiresKeycloakDependencyProof } from './keycloak-runtime-dependencies.mjs';

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const DATABASE_HOLD = new Set(['mysql', 'postgres']);
const MANIFEST = 'infrastructure/runtime-security/reviewed-images.json';
// The C7 publication binds these exact bytes, including the original service/path coverage.
const MANIFEST_SHA256 = 'd48bdb7d6d869cde5d6f1a7b089491765e3eeaf46e0b0b6bb51b7719b38d3ef2';
const ACTIVATIONS = 'infrastructure/runtime-security/reviewed-image-activations.json';
const EVIDENCE_FILE = /^registry-(?:index|amd64-(?:manifest|config)|attestation-[0-9]+(?:-payload-[0-9]+)?)\.json$/;

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
  const publication = await proof(entry.publication);
  const anonymous = await proof(entry.anonymous);
  const identity = { commit: entry.commit, run: entry.run, attempt: entry.attempt };
  const digest = validatePublication(publication.value, identity, image, sha256(manifestBytes));
  if (requiresKeycloakDependencyProof(image)) {
    const checked = checkKeycloakRuntimeDependencies(
      await read(dirname(entry.publication.path).replaceAll('\\', '/') + '/vulnerabilities.json'), publication.value.imageId);
    assert.deepEqual(checked, publication.value.knownRuntimeDependencies, 'activation_known_dependencies_changed');
  }
  assert.equal(entry.reference, publication.value.reference, 'activation_registered_reference_mismatch');
  assert.equal(publication.value.security.effectiveBlockingFixedHighOrCritical, 0, 'activation_security_severity_mismatch');
  assert.equal(publication.value.security.unresolvedRiskReview, 'NONE', 'activation_unresolved_security_review');
  const downloaded = anonymous.value;
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
  const expected = { source: SOURCE_REPOSITORY, commit: identity.commit, context: image.context,
    dockerfile: image.dockerfile, dockerfileSha256: image.dockerfileSha256 };
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

export async function validateReviewedDefaults(rows, manifestBytes, activations, read) {
  const images = validateManifest(JSON.parse(manifestBytes));
  if (activations) assert.equal(activations.schema, 'otziv-reviewed-image-activations-v1', 'activation_index_schema');
  const entries = activations?.images || [];
  assert.ok(Array.isArray(entries), 'activation_index_images');
  const registered = new Map();
  for (const entry of entries) {
    assert.ok(images.some(image => image.component === entry.component) && !registered.has(entry.component), 'activation_unknown_or_duplicate_component');
    assert.ok(!DATABASE_HOLD.has(entry.component), 'activation_database_coordinated_transition_required');
    registered.set(entry.component, entry);
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
        assert.ok(!DATABASE_HOLD.has(image.component), 'activation_database_coordinated_transition_required');
        const entry = registered.get(image.component);
        assert.ok(entry, 'reviewed_default_unregistered_reference');
        published ||= await validateActivation(image, entry, manifestBytes, read);
        assert.equal(actual, published, 'reviewed_default_wrong_component_reference');
      }
      checks.push({ component: image.component, path: reference.path, service: reference.service,
        reference: actual, mode: actual === source ? 'EXACT_ORIGINAL_SOURCE' : 'PAIRED_PUBLICATION_AND_ANONYMOUS_EVIDENCE' });
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
  const evidenceRoot = await realpath(resolve(root, 'infrastructure/runtime-security'));
  const evidenceRelative = relative(root, evidenceRoot);
  assert.ok(evidenceRelative && !isAbsolute(evidenceRelative) && evidenceRelative !== '..'
    && !evidenceRelative.startsWith('..' + sep), 'activation_evidence_root_symlink_escape');
  function pathInScope(path) {
    assert.ok(typeof path === 'string' && path.startsWith('infrastructure/runtime-security/') && !isAbsolute(path)
      && !path.includes('\\') && !path.includes(':')
      && path.split('/').every(part => part && part !== '.' && part !== '..'), 'activation_path_outside_evidence_scope');
    return resolve(root, path);
  }
  async function physicalPath(path) {
    const actual = await realpath(pathInScope(path));
    const inside = relative(evidenceRoot, actual);
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
  return validateReviewedDefaults(rows || await repositoryInventory(root), manifest, activations, read);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const checks = await validateRepositoryDefaults(process.argv[2] || process.cwd());
  console.log(JSON.stringify({ result: 'PASS', reviewedReferences: checks.length, databaseDefaults: 'ORIGINAL_SOURCE_HOLD', networkCalls: 0 }));
}
