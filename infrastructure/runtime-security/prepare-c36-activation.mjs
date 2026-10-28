// Stage the exact, downloaded publication and anonymous-download artifacts.
// This does not publish an image, edit Compose, or register an activation.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { lstat, mkdir, mkdtemp, readFile, readdir, rename, writeFile } from 'node:fs/promises';
import { basename, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildTriage } from './triage-report.mjs';
import { validateManifest } from './publish-reviewed-images.mjs';
import { validatePublication, assertPulledImage } from './verify-anonymous-download.mjs';
import { checkedJson, SOURCE_REPOSITORY, verifyRegistryEvidence } from './registry-evidence.mjs';
import { validateReviewedImageSet, reviewedImageSet, supplementalReviewedSources } from './reviewed-image-sets.mjs';
import { validateActivation } from './reviewed-image-defaults.mjs';
import { validatePostgresC36ActivationScan } from './postgres-c36-activation.mjs';
import { summarizeReport, TRIVY_IMAGE } from './scan.mjs';
import { combinedScanSummary } from './scan-verdict.mjs';

const ROOT = resolve(fileURLToPath(new URL('../../', import.meta.url)));
const PREFIX = 'infrastructure/runtime-security/';
const COMPONENTS = new Set(['dozzle', 'prometheus', 'loki', 'alloy', 'tempo', 'grafana', 'mc', 'minio', 'postgres']);
const C43_SETS = new Set(['c43-tempo', 'c43-loki', 'c43-alloy', 'c43-grafana', 'c43-keycloak']);
const MYSQL_REFRESH_SET = 'c37-mysql';
const REGISTRY_FILE = /^registry-(?:index|amd64-(?:manifest|config)|attestation-[0-9]+(?:-payload-[0-9]+)?)\.json$/;
const PUBLICATION_FILE = /^(?:publication\.json|build-metadata\.json|known-runtime-dependencies\.json|vulnerabilities\.[A-Za-z0-9.-]+)$/;
const ANONYMOUS_FILE = /^(?:anonymous-(?:download|image-inspect|job-failure)\.json|anonymous-pull\.(?:stdout|stderr)\.log|before-(?:image-ids|reference-inspect)\.txt|docker-client-isolation\.json)$/;
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const parse = bytes => JSON.parse(Buffer.from(bytes).toString('utf8'));
const bound = (path, bytes) => ({ path, sha256: hash(bytes) });

function inside(parent, child) {
  const path = relative(parent, child);
  return path && path !== '..' && !path.startsWith('..' + sep) && !isAbsolute(path);
}

export async function readArtifactDirectory(directory, kind) {
  assert.ok(['publication', 'anonymous'].includes(kind), 'c36_artifact_kind');
  assert.ok((await lstat(directory)).isDirectory(), 'c36_artifact_directory_required');
  const names = (await readdir(directory)).sort();
  assert.ok(names.length >= 2 && names.length <= 64, 'c36_artifact_file_count');
  const files = new Map();
  let total = 0;
  for (const name of names) {
    assert.ok(PUBLICATION_FILE.test(name) && kind === 'publication' ||
      ANONYMOUS_FILE.test(name) && kind === 'anonymous' || REGISTRY_FILE.test(name),
    'c36_artifact_filename');
    const path = join(directory, name), metadata = await lstat(path);
    assert.ok(metadata.isFile() && (metadata.size > 0 || /\.log$/.test(name)) &&
      metadata.size <= 64 * 1024 * 1024,
      'c36_artifact_regular_bounded_file');
    total += metadata.size;
    assert.ok(total <= 128 * 1024 * 1024, 'c36_artifact_total_size');
    const bytes = await readFile(path);
    assert.equal(bytes.length, metadata.size, 'c36_artifact_changed_during_read');
    files.set(name, bytes);
  }
  for (const required of kind === 'publication'
    ? ['publication.json', 'vulnerabilities.json', 'vulnerabilities.adjudications.json',
      'vulnerabilities.triage.json', 'registry-index.json']
    : ['anonymous-download.json', 'anonymous-image-inspect.json', 'registry-index.json'])
    assert.ok(files.has(required), 'c36_artifact_required_file:' + required);
  return files;
}

async function verifyDocumentEvidence(document, files, digest, expected) {
  assert.equal(document.attestationEvidence?.schema, 'otziv-registry-evidence-v1', 'c36_registry_evidence_missing');
  const blobs = new Map(), filenames = new Set();
  for (const artifact of document.attestationEvidence.artifacts || []) {
    assert.match(artifact.file || '', REGISTRY_FILE, 'c36_registry_file_name');
    assert.ok(!filenames.has(artifact.file), 'c36_registry_duplicate_file');
    filenames.add(artifact.file);
    const bytes = files.get(artifact.file);
    assert.ok(bytes, 'c36_registry_file_missing:' + artifact.file);
    checkedJson(bytes, artifact);
    blobs.set(artifact.digest, bytes);
  }
  const actual = await verifyRegistryEvidence({ digest, expected,
    read: async (_kind, key) => {
      assert.ok(blobs.has(key), 'c36_registry_blob_missing');
      return blobs.get(key);
    }, retain: async () => {} });
  assert.deepEqual(actual, document.attestationEvidence, 'c36_registry_record_changed');
}

function verifyScan(publication, files) {
  const rawBytes = files.get('vulnerabilities.json');
  const raw = parse(rawBytes), receipt = parse(files.get('vulnerabilities.adjudications.json'));
  const triage = parse(files.get('vulnerabilities.triage.json'));
  assert.equal(raw.Metadata?.ImageID, publication.imageId, 'c36_scan_image_changed');
  assert.equal(receipt.rawReportSha256, hash(rawBytes), 'c36_scan_receipt_raw_hash');
  assert.equal(receipt.imageConfigId, publication.imageId, 'c36_scan_receipt_image');
  assert.equal(receipt.rawReportModified, false, 'c36_scan_raw_modified');
  assert.deepEqual(triage, buildTriage(raw, rawBytes), 'c36_scan_triage_changed');
  const actual = { ...combinedScanSummary(summarizeReport(raw), {
    grafana: receipt, alloy: receipt.alloy, postgres: receipt.postgres, gosu: receipt.gosu,
  }), scannerImage: TRIVY_IMAGE };
  assert.deepEqual(publication.security, actual, 'c36_scan_summary_changed');
  assert.equal(actual.result, 'PASS', 'c36_scan_failed');
  assert.equal(actual.effectiveBlockingFixedHighOrCritical, 0, 'c36_scan_fixed_risk');
  assert.equal(actual.unresolvedRiskReview, 'NONE', 'c36_scan_unresolved_risk');
}

export async function prepareActivationPair({ manifestSet, publicationFiles, anonymousFiles,
  expectedIdentity, root = ROOT }) {
  const component = manifestSet?.replace(/^c[0-9]+-/, '');
  const selected = reviewedImageSet(manifestSet);
  assert.ok(manifestSet === 'c36-' + component && COMPONENTS.has(component) ||
    manifestSet === 'c29-dozzle' || manifestSet === MYSQL_REFRESH_SET || C43_SETS.has(manifestSet),
  'c36_component_not_supported');
  const baselineBytes = await readFile(resolve(root, PREFIX + 'reviewed-images.json'));
  const manifestBytes = await readFile(resolve(root, selected.path));
  const manifest = validateReviewedImageSet(manifestSet, manifestBytes, baselineBytes);
  const image = validateManifest(manifest)[0];
  assert.equal(image.component, component, 'c36_component_mismatch');
  const dockerfile = await readFile(resolve(root, image.dockerfile));
  assert.equal(hash(Buffer.from(dockerfile.toString('utf8').replaceAll('\r\n', '\n'))),
    image.dockerfileSha256, 'c36_dockerfile_changed');
  const publicationBytes = publicationFiles.get('publication.json');
  const anonymousBytes = anonymousFiles.get('anonymous-download.json');
  assert.ok(publicationBytes && anonymousBytes, 'c36_pair_missing');
  const publication = parse(publicationBytes), anonymous = parse(anonymousBytes);
  const identity = { commit: publication.commit, run: publication.run, attempt: publication.attempt };
  for (const [name, pattern] of [['commit', /^[a-f0-9]{40}$/], ['run', /^[1-9][0-9]*$/],
    ['attempt', /^[1-9][0-9]*$/]]) assert.match(identity[name] || '', pattern, 'c36_identity_invalid:' + name);
  assert.deepEqual(identity, expectedIdentity, 'c36_expected_github_run_mismatch');
  const digest = validatePublication(publication, identity, image, hash(manifestBytes), manifestSet);
  assert.equal(anonymous.schema, 'otziv-anonymous-download-v1', 'c36_anonymous_schema');
  assert.equal(anonymous.result, 'PASS', 'c36_anonymous_failed');
  assert.equal(anonymous.component, component, 'c36_anonymous_component');
  assert.equal(anonymous.manifestSet, manifestSet, 'c36_anonymous_set');
  assert.equal(anonymous.manifestPath, selected.path, 'c36_anonymous_manifest');
  for (const key of ['commit', 'run', 'attempt'])
    assert.equal(anonymous[key], identity[key], 'c36_anonymous_identity:' + key);
  assert.equal(anonymous.sourcePublicationSha256, hash(publicationBytes), 'c36_pair_publication_hash');
  assert.equal(anonymous.reference, publication.reference, 'c36_pair_reference');
  assert.equal(anonymous.imageId, publication.imageId, 'c36_pair_image');
  assert.equal(anonymous.publicDownloadReadiness, 'VERIFIED_ANONYMOUS_DIGEST_PULL', 'c36_anonymous_pull_missing');
  assert.equal(anonymous.imageAbsentBeforePull, true, 'c36_anonymous_not_fresh');
  assert.equal(anonymous.accountCredentialsUsed, false, 'c36_anonymous_credentials');
  assert.equal(anonymous.dockerCredentialHelpersAvailable, false, 'c36_anonymous_credential_helper');
  assert.equal(anonymous.dockerTransport, 'unix:///var/run/docker.sock', 'c36_anonymous_transport');
  assert.equal(anonymous.pullExitCode, 0, 'c36_anonymous_pull_failed');
  assert.equal(anonymous.platform, 'linux/amd64', 'c36_anonymous_platform');
  assert.deepEqual(anonymous.attestationEvidence, publication.attestationEvidence, 'c36_pair_registry_changed');
  assertPulledImage(parse(anonymousFiles.get('anonymous-image-inspect.json')), publication);
  const expected = { source: SOURCE_REPOSITORY, commit: identity.commit, context: image.context,
    dockerfile: image.dockerfile, dockerfileSha256: image.dockerfileSha256 };
  await verifyDocumentEvidence(publication, publicationFiles, digest, expected);
  await verifyDocumentEvidence(anonymous, anonymousFiles, digest, expected);
  verifyScan(publication, publicationFiles);
  const proofRoot = PREFIX + 'proofs/' + manifestSet + '/';
  const entry = { component, reference: publication.reference, ...identity,
    manifest: bound(selected.path, manifestBytes),
    publication: bound(proofRoot + 'publication/publication.json', publicationBytes),
    anonymous: bound(proofRoot + 'anonymous/anonymous-download.json', anonymousBytes) };
  const read = async path => {
    if (path.startsWith(proofRoot + 'publication/')) {
      const bytes = publicationFiles.get(basename(path));
      assert.ok(bytes, 'c36_publication_proof_missing:' + path);
      return bytes;
    }
    if (path.startsWith(proofRoot + 'anonymous/')) {
      const bytes = anonymousFiles.get(basename(path));
      assert.ok(bytes, 'c36_anonymous_proof_missing:' + path);
      return bytes;
    }
    assert.ok(path.startsWith(PREFIX) && !path.includes('..') && !path.includes('\\'),
      'c36_read_outside_repository');
    return readFile(resolve(root, path));
  };
  if (component === 'postgres') {
    await validatePostgresC36ActivationScan(publication, entry.publication.path, read);
  } else {
    const original = [...validateManifest(parse(baselineBytes)), ...supplementalReviewedSources()]
      .find(item => item.component === component);
    assert.ok(original, 'c36_original_component_missing');
    await validateActivation(original, entry, baselineBytes, read);
  }
  const activationReady = component !== 'postgres' && manifestSet !== MYSQL_REFRESH_SET;
  return { entry, activationReady,
    ...(component === 'postgres' ? { remaining: 'same-volume PostgreSQL acceptance and C35 Keycloak coupling' } : {}),
    ...(manifestSet === MYSQL_REFRESH_SET ? { remaining: 'published-digest MySQL rehearsal and coordinated database cutover' } : {}) };
}

function assertReleaseUnlocked(root) {
  let result;
  try {
    result = JSON.parse(execFileSync('python', ['-B', 'infrastructure/scripts/prod/release_session.py', 'status'],
      { cwd: root, encoding: 'utf8', timeout: 30_000, windowsHide: true }));
  } catch { throw new Error('c36_release_session_status_unavailable'); }
  assert.equal(result.active, false, 'c36_release_session_active');
}

export async function stageActivationPair({ manifestSet, publicationDirectory, anonymousDirectory,
  expectedIdentity, root = ROOT, write = false }) {
  const publicationFiles = await readArtifactDirectory(publicationDirectory, 'publication');
  const anonymousFiles = await readArtifactDirectory(anonymousDirectory, 'anonymous');
  const result = await prepareActivationPair({ manifestSet, publicationFiles, anonymousFiles, expectedIdentity, root });
  if (!write) return { ...result, mode: 'DRY_RUN', proofDirectory: null };
  assert.ok(manifestSet.startsWith('c36-') || manifestSet === MYSQL_REFRESH_SET || C43_SETS.has(manifestSet),
    'c36_write_requires_supported_set');
  assertReleaseUnlocked(root);
  const proofsRoot = resolve(root, PREFIX + 'proofs');
  const destination = resolve(proofsRoot, manifestSet);
  assert.ok(inside(proofsRoot, destination), 'c36_destination_outside_proofs');
  try { await lstat(destination); throw new Error('c36_destination_already_exists'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const staging = await mkdtemp(resolve(proofsRoot, '.' + manifestSet + '-stage-'));
  assert.ok(inside(proofsRoot, staging), 'c36_staging_outside_proofs');
  // Leave an incomplete staging directory for inspection if a write fails.
  for (const [kind, files] of [['publication', publicationFiles], ['anonymous', anonymousFiles]]) {
    const directory = resolve(staging, kind);
    await mkdir(directory);
    for (const [name, bytes] of files) await writeFile(resolve(directory, name), bytes, { flag: 'wx' });
  }
  const candidateName = result.activationReady ? 'activation-candidate.json' : 'activation-draft.json';
  const candidate = result.activationReady ? result.entry : {
    schema: 'otziv-reviewed-image-activation-draft-v1', activationReady: false,
    remaining: result.remaining, entry: result.entry,
  };
  await writeFile(resolve(staging, candidateName), JSON.stringify(candidate, null, 2) + '\n', { flag: 'wx' });
  await rename(staging, destination);
  return { ...result, mode: 'STAGED', proofDirectory: destination, candidate: join(destination, candidateName) };
}

function argumentsFrom(argv) {
  const [manifestSet, publicationDirectory, anonymousDirectory, commit, run, attempt, ...rest] = argv;
  assert.ok(manifestSet && publicationDirectory && anonymousDirectory && commit && run && attempt,
    'usage: prepare-c36-activation.mjs c36-<component>|c37-mysql <publication-dir> <anonymous-dir> <commit> <run> <attempt> [--write]');
  assert.ok(rest.length === 0 || rest.length === 1 && rest[0] === '--write', 'c36_arguments_invalid');
  assert.ok(manifestSet.startsWith('c36-') || manifestSet === MYSQL_REFRESH_SET || C43_SETS.has(manifestSet),
    'c36_cli_requires_supported_set');
  return { manifestSet, publicationDirectory: resolve(publicationDirectory), anonymousDirectory: resolve(anonymousDirectory),
    expectedIdentity: { commit, run, attempt }, write: rest[0] === '--write' };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const result = await stageActivationPair(argumentsFrom(process.argv.slice(2)));
  console.log(JSON.stringify(result, null, 2));
}
