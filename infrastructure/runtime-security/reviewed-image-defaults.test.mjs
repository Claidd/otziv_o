import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import { validateActivation, validateReviewedDefaults, validateRepositoryDefaults } from './reviewed-image-defaults.mjs';
import { BUILDKIT, REPOSITORY, SBOM_GENERATOR } from './publish-reviewed-images.mjs';
import { SOURCE_REPOSITORY, verifyRegistryEvidence } from './registry-evidence.mjs';
import { inventory } from './upstream-images.mjs';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const bytes = value => Buffer.from(JSON.stringify(value));
const directory = new URL('./fixtures/provenance-scratch/', import.meta.url);
const fixture = JSON.parse(await readFile(new URL('fixture.json', directory)));
const blobs = new Map(await Promise.all(fixture.result.artifacts.map(async item => [item.file, await readFile(new URL(item.file, directory))])));
const reviewedBytes = await readFile(new URL('./reviewed-images.json', import.meta.url));
const reviewed = JSON.parse(reviewedBytes);
// Rebind the existing scratch fixture in memory to this API's fixed source/path.
// These recomputed descriptor hashes are synthetic test data, never publication refs.
fixture.expected.source = SOURCE_REPOSITORY;
fixture.expected.context = 'infrastructure/runtime-security/fixtures/provenance-scratch';
fixture.expected.dockerfile = fixture.expected.context + '/Dockerfile';
const provenance = JSON.parse(blobs.get('registry-attestation-0-payload-1.json'));
const vcs = provenance.predicate.runDetails.metadata.buildkit_metadata.vcs;
vcs.source = SOURCE_REPOSITORY;
vcs['localdir:context'] = fixture.expected.context;
vcs['localdir:dockerfile'] = fixture.expected.context;
for (const [key, value] of Object.entries(vcs))
  provenance.predicate.buildDefinition.externalParameters.request.root.request.args['vcs:' + key] = value;
const attestation = JSON.parse(blobs.get('registry-attestation-0.json'));
const index = JSON.parse(blobs.get('registry-index.json'));
function pack(name, value, descriptor) {
  const content = bytes(value); blobs.set(name, content);
  descriptor.digest = 'sha256:' + hash(content); descriptor.size = content.length;
}
pack('registry-attestation-0-payload-1.json', provenance,
  attestation.layers.find(item => item.annotations['in-toto.io/predicate-type'] === 'https://slsa.dev/provenance/v1'));
pack('registry-attestation-0.json', attestation, index.manifests.find(item => item.platform.os === 'unknown'));
const rootDescriptor = {};
pack('registry-index.json', index, rootDescriptor);
fixture.indexDigest = rootDescriptor.digest;
const byDigest = new Map([...blobs.values()].map(content => ['sha256:' + hash(content), content]));
fixture.result = await verifyRegistryEvidence({ digest: fixture.indexDigest, expected: fixture.expected,
  read: async (kind, digest) => byDigest.get(digest), retain: async () => {} });

function scenario() {
  const image = { ...reviewed.images.find(item => item.component === 'nginx'),
    context: fixture.expected.context, dockerfile: fixture.expected.dockerfile,
    dockerfileSha256: fixture.expected.dockerfileSha256,
    defaultReferencesBefore: [{ path: 'fixture.yaml', service: 'nginx' }] };
  const manifestBytes = bytes({ ...reviewed, images: [image] });
  const publication = { schema: 'otziv-reviewed-image-publication-v1', component: image.component,
    commit: fixture.expected.commit, run: '123', attempt: '1', platform: 'linux/amd64', productionActivated: false,
    sourceBeforeRef: image.sourceBeforeRef, dockerfileSha256: image.dockerfileSha256, manifestSha256: hash(manifestBytes),
    builder: BUILDKIT, sbomGenerator: SBOM_GENERATOR,
    imageId: fixture.result.artifacts.find(item => item.file === 'registry-amd64-config.json').digest,
    reference: REPOSITORY + '@' + fixture.indexDigest, result: 'PASS',
    security: { result: 'PASS', effectiveBlockingFixedHighOrCritical: 0, unresolvedRiskReview: 'NONE' }, attestationEvidence: structuredClone(fixture.result) };
  publication.tag = `${REPOSITORY}:nginx-${publication.commit}-123-1`;
  const anonymous = { schema: 'otziv-anonymous-download-v1', component: 'nginx',
    commit: publication.commit, run: '123', attempt: '1', reference: publication.reference,
    imageId: publication.imageId, platform: 'linux/amd64', imageAbsentBeforePull: true,
    accountCredentialsUsed: false, dockerCredentialHelpersAvailable: false,
    dockerTransport: 'unix:///var/run/docker.sock', pullExitCode: 0,
    publicDownloadReadiness: 'VERIFIED_ANONYMOUS_DIGEST_PULL', result: 'PASS',
    attestationEvidence: structuredClone(fixture.result) };
  const pubPath = 'infrastructure/runtime-security/proofs/test/publication/publication.json';
  const anonPath = 'infrastructure/runtime-security/proofs/test/anonymous/anonymous-download.json';
  const entry = { component: 'nginx', reference: publication.reference, commit: publication.commit, run: '123', attempt: '1' };
  const files = new Map();
  function refresh() {
    files.set(pubPath, bytes(publication)); anonymous.sourcePublicationSha256 = hash(files.get(pubPath));
    files.set(anonPath, bytes(anonymous));
    entry.publication = { path: pubPath, sha256: hash(files.get(pubPath)) };
    entry.anonymous = { path: anonPath, sha256: hash(files.get(anonPath)) };
  }
  refresh();
  for (const [name, content] of blobs) for (const group of ['publication', 'anonymous'])
    files.set(`infrastructure/runtime-security/proofs/test/${group}/${name}`, content);
  files.set('infrastructure/runtime-security/proofs/test/anonymous/anonymous-image-inspect.json', bytes([{
    Id: publication.imageId, Os: 'linux', Architecture: 'amd64', RepoDigests: [publication.reference], Config: { Labels: {
      'com.otziv.publication.source': SOURCE_REPOSITORY, 'com.otziv.publication.revision': publication.commit,
      'com.otziv.reviewed-component': 'nginx' } } }]));
  return { image, manifestBytes, publication, anonymous, entry, files, refresh,
    read: async path => { assert.ok(files.has(path), 'test_file_missing'); return files.get(path); } };
}
const rows = reference => inventory([{ path: 'fixture.yaml', text: `services:\n  nginx:\n    image: ${reference}\n` }]);
const registry = entry => ({ schema: 'otziv-reviewed-image-activations-v1', images: [entry] });

test('no activation registry accepts exact reviewed original only, including tagged equivalent', async () => {
  const value = scenario();
  const noRead = async () => { throw new Error('unexpected_proof_read'); };
  const result = await validateReviewedDefaults(rows(value.image.sourceBeforeRef), value.manifestBytes, undefined, noRead);
  assert.equal(result[0].mode, 'EXACT_ORIGINAL_SOURCE');
  await validateReviewedDefaults(rows(value.image.sourceBeforeRef.replace('@', ':reviewed@')), value.manifestBytes, undefined, noRead);
  await assert.rejects(validateReviewedDefaults(rows(value.publication.reference), value.manifestBytes, undefined, noRead), /unregistered/);
  const changed = value.image.sourceBeforeRef.replace(/sha256:.*/, 'sha256:' + 'a'.repeat(64));
  await assert.rejects(validateReviewedDefaults(rows(changed), value.manifestBytes, undefined, noRead), /unregistered/);
});

test('registered immutable reference requires paired proofs and validates actual retained OCI bytes', async () => {
  const value = scenario();
  const result = await validateReviewedDefaults(rows(value.entry.reference), value.manifestBytes, registry(value.entry), value.read);
  assert.equal(result[0].mode, 'PAIRED_PUBLICATION_AND_ANONYMOUS_EVIDENCE');
});

test('same repository digest cannot be substituted between reviewed components', async () => {
  const value = scenario();
  value.entry.reference = REPOSITORY + '@sha256:' + 'a'.repeat(64);
  await assert.rejects(validateReviewedDefaults(rows(value.entry.reference), value.manifestBytes, registry(value.entry), value.read), /registered_reference_mismatch/);
});

test('paired publication rejects commit/run/attempt/component mismatch and anonymous nonfresh or credentials', async () => {
  for (const [key, replacement] of Object.entries({ commit: 'a'.repeat(40), run: '456', attempt: '2', component: 'loki',
    imageAbsentBeforePull: false, accountCredentialsUsed: true, dockerCredentialHelpersAvailable: true,
    publicDownloadReadiness: 'NOT_VERIFIED', pullExitCode: 1 })) {
    const value = scenario(); value.anonymous[key] = replacement; value.refresh();
    await assert.rejects(validateActivation(value.image, value.entry, value.manifestBytes, value.read), undefined, key);
  }
});

test('publication failure, security failure and nonzero effective fixed severity never activate', async () => {
  for (const change of [value => { value.publication.result = 'FAIL'; }, value => { value.publication.security.result = 'FAIL'; },
    value => { value.publication.security.effectiveBlockingFixedHighOrCritical = 1; }]) {
    const value = scenario(); change(value); value.refresh();
    await assert.rejects(validateActivation(value.image, value.entry, value.manifestBytes, value.read));
  }
});

test('frozen manifest hash and proof hashes cannot be replaced with presentation-normalized bytes', async () => {
  const value = scenario();
  await assert.rejects(validateActivation(value.image, value.entry, Buffer.concat([value.manifestBytes, Buffer.from('\n')]), value.read), /manifest_mismatch/);
  value.files.set(value.entry.publication.path, Buffer.concat([value.files.get(value.entry.publication.path), Buffer.from('\n')]));
  await assert.rejects(validateActivation(value.image, value.entry, value.manifestBytes, value.read), /proof_hash_mismatch/);
});

test('a passed publication with unresolved or omitted risk review cannot activate', async () => {
  const baseline = scenario();
  await validateActivation(baseline.image, baseline.entry, baseline.manifestBytes, baseline.read);
  for (const risk of ['REQUIRED', 'ACCEPTED', undefined]) {
    const value = scenario();
    value.publication.security.unresolvedRiskReview = risk;
    value.refresh();
    await assert.rejects(validateActivation(value.image, value.entry, value.manifestBytes, value.read),
      /activation_unresolved_security_review/);
  }
});

test('corrupt OCI blobs in either publication or anonymous proof are rejected', async () => {
  for (const group of ['publication', 'anonymous']) {
    const value = scenario();
    const path = `infrastructure/runtime-security/proofs/test/${group}/registry-amd64-config.json`;
    value.files.set(path, Buffer.concat([value.files.get(path), Buffer.from('\n')]));
    await assert.rejects(validateActivation(value.image, value.entry, value.manifestBytes, value.read), /registry_blob_digest_mismatch/);
  }
});

test('database defaults remain on their exact original source despite otherwise valid publication proof', async () => {
  for (const component of ['mysql', 'postgres']) {
    const value = scenario(); const image = { ...value.image, component };
    const manifest = bytes({ ...reviewed, images: [image] });
    await validateReviewedDefaults(rows(image.sourceBeforeRef), manifest, undefined, value.read);
    await assert.rejects(validateReviewedDefaults(rows(value.entry.reference), manifest, undefined, value.read), /database_coordinated/);
    await assert.rejects(validateReviewedDefaults(rows(image.sourceBeforeRef), manifest, registry({ ...value.entry, component }), value.read), /database_coordinated/);
  }
});

test('missing reviewed service and duplicate registry entries cannot silently shrink coverage', async () => {
  const value = scenario();
  await assert.rejects(validateReviewedDefaults([], value.manifestBytes, undefined, value.read), /service_missing/);
  await assert.rejects(validateReviewedDefaults(rows(value.image.sourceBeforeRef), value.manifestBytes,
    { schema: 'otziv-reviewed-image-activations-v1', images: [value.entry, value.entry] }, value.read), /duplicate_component/);
});


async function repositoryFixture(action) {
  const temporary = await realpath(tmpdir());
  const owned = await mkdtemp(join(temporary, 'otziv-reviewed-defaults-'));
  try { await action(owned); }
  finally {
    const actual = await realpath(owned);
    assert.equal(dirname(actual), temporary, 'test_cleanup_outside_temporary_directory');
    assert.ok(basename(actual).startsWith('otziv-reviewed-defaults-'), 'test_cleanup_unowned_directory');
    await rm(actual, { recursive: true });
  }
}
const originalRepositoryRows = () => reviewed.images.map(image => ({ image: image.sourceBeforeRef,
  references: image.defaultReferencesBefore.map(reference => ({ ...reference })) }));

test('repository contract freezes C7 manifest bytes so five production checks cannot be removed without an index', async () => {
  await repositoryFixture(async root => {
    const evidence = join(root, 'infrastructure/runtime-security');
    await mkdir(evidence, { recursive: true });
    const manifestPath = join(evidence, 'reviewed-images.json');
    await writeFile(manifestPath, reviewedBytes);
    const baseline = await validateRepositoryDefaults(root, originalRepositoryRows());
    assert.equal(baseline.length, 30);
    const changed = new Set(['prometheus', 'loki', 'tempo', 'alloy', 'grafana']);
    const altered = structuredClone(reviewed);
    for (const image of altered.images) if (changed.has(image.component))
      image.defaultReferencesBefore = image.defaultReferencesBefore.filter(reference => reference.path !== 'docker-compose.yaml');
    const alteredRows = [];
    for (const [index, row] of originalRepositoryRows().entries()) {
      if (!changed.has(reviewed.images[index].component)) { alteredRows.push(row); continue; }
      alteredRows.push({ ...row, references: row.references.filter(reference => reference.path !== 'docker-compose.yaml') });
      alteredRows.push({ image: 'ghcr.io/example/unreviewed@sha256:' + 'd'.repeat(64),
        references: row.references.filter(reference => reference.path === 'docker-compose.yaml') });
    }
    await writeFile(manifestPath, bytes(altered));
    await assert.rejects(validateRepositoryDefaults(root, alteredRows), /reviewed_frozen_manifest_changed/);
  });
});

test('repository evidence directory itself cannot escape through a symlink or Windows junction', async () => {
  await repositoryFixture(async owned => {
    const root = join(owned, 'repository'), external = join(owned, 'external-evidence');
    await mkdir(join(root, 'infrastructure'), { recursive: true });
    await mkdir(external);
    await writeFile(join(external, 'reviewed-images.json'), reviewedBytes);
    await symlink(external, join(root, 'infrastructure/runtime-security'), process.platform === 'win32' ? 'junction' : 'dir');
    await assert.rejects(validateRepositoryDefaults(root, originalRepositoryRows()), /activation_evidence_root_symlink_escape/);
  });
});
