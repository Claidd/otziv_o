import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import { createEvidenceReader, validateActivation, validateReviewedDefaults, validateRepositoryDefaults } from './reviewed-image-defaults.mjs';
import { BUILDKIT, REPOSITORY, SBOM_GENERATOR } from './publish-reviewed-images.mjs';
import { checkedJson, SOURCE_REPOSITORY, verifyRegistryEvidence } from './registry-evidence.mjs';
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

test('filesystem parts preserve the complete paired OCI proof and cannot replace its descriptor hashes', async () => {
  const value = scenario();
  const raw = new Map(blobs);
  const payloadName = 'registry-attestation-0-payload-0.json';
  const payload = Buffer.concat([raw.get(payloadName), Buffer.alloc(6 * 1024 * 1024, 32)]);
  const manifest = JSON.parse(raw.get('registry-attestation-0.json'));
  const rootIndex = JSON.parse(raw.get('registry-index.json'));
  function replace(name, content, descriptors) {
    const previous = 'sha256:' + hash(raw.get(name));
    const descriptor = descriptors.find(item => item.digest === previous);
    assert.ok(descriptor);
    raw.set(name, content); descriptor.digest = 'sha256:' + hash(content); descriptor.size = content.length;
  }
  replace(payloadName, payload, manifest.layers);
  replace('registry-attestation-0.json', bytes(manifest), rootIndex.manifests);
  raw.set('registry-index.json', bytes(rootIndex));
  const digest = 'sha256:' + hash(raw.get('registry-index.json'));
  const rawByDigest = new Map([...raw.values()].map(content => ['sha256:' + hash(content), content]));
  const proof = await verifyRegistryEvidence({ digest, expected: fixture.expected,
    read: async (kind, digest) => rawByDigest.get(digest), retain: async () => {} });
  value.entry.reference = value.publication.reference = value.anonymous.reference = REPOSITORY + '@' + digest;
  value.publication.attestationEvidence = value.anonymous.attestationEvidence = proof;
  value.refresh();
  const inspectPath = 'infrastructure/runtime-security/proofs/test/anonymous/anonymous-image-inspect.json';
  const inspected = JSON.parse(value.files.get(inspectPath)); inspected[0].RepoDigests = [value.entry.reference];
  value.files.set(inspectPath, bytes(inspected));
  for (const group of ['publication', 'anonymous']) for (const [name, content] of raw)
    value.files.set(`infrastructure/runtime-security/proofs/test/${group}/${name}`, content);
  await repositoryFixture(async root => {
    for (const [path, content] of value.files) {
      await mkdir(dirname(join(root, path)), { recursive: true }); await writeFile(join(root, path), content);
    }
    const read = await createEvidenceReader(root);
    await validateActivation(value.image, value.entry, value.manifestBytes, read);
    for (const group of ['publication', 'anonymous']) {
      const path = `infrastructure/runtime-security/proofs/test/${group}/${payloadName}`;
      const buffers = [payload.subarray(0, 4 * 1024 * 1024), payload.subarray(4 * 1024 * 1024)];
      const metadata = { schema: 'otziv-raw-evidence-parts-v1', bytes: payload.length, sha256: hash(payload),
        parts: buffers.map((content, index) => ({ file: payloadName + '.part00' + (index + 1), bytes: content.length, sha256: hash(content) })) };
      for (const [index, content] of buffers.entries()) await writeFile(join(root, path + '.part00' + (index + 1)), content);
      await writeFile(join(root, path + '.parts.json'), bytes(metadata)); await rm(join(root, path));
    }
    await validateActivation(value.image, value.entry, value.manifestBytes, read);
    // An internally consistent rewritten sidecar cannot authorize changed OCI bytes.
    const path = `infrastructure/runtime-security/proofs/test/anonymous/${payloadName}`;
    const changed = Buffer.from(payload); changed[changed.length - 1] = 10;
    const metadata = JSON.parse(await readFile(join(root, path + '.parts.json')));
    metadata.sha256 = hash(changed);
    metadata.parts[1].sha256 = hash(changed.subarray(4 * 1024 * 1024));
    await writeFile(join(root, path + '.part002'), changed.subarray(4 * 1024 * 1024));
    await writeFile(join(root, path + '.parts.json'), bytes(metadata));
    await assert.rejects(validateActivation(value.image, value.entry, value.manifestBytes, read), /registry_blob_digest_mismatch/);
  });
});

// Bounded raw OCI storage is exercised through the same required test entrypoint.
{
const MiB = 1024 * 1024;
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const name = 'registry-attestation-0-payload-0.json';
const path = 'infrastructure/runtime-security/proofs/test/' + name;
const original = Buffer.from(JSON.stringify({ value: 'x'.repeat(5 * MiB + 64), unicode: 'Ж🧭' }));

async function workspace(action) {
  const parent = await realpath(tmpdir());
  const root = await mkdtemp(join(parent, 'otziv-parts-'));
  try { await action(root); }
  finally {
    const actual = await realpath(root);
    assert.equal(dirname(actual), parent);
    assert.ok(basename(actual).startsWith('otziv-parts-'));
    await rm(actual, { recursive: true });
  }
}

async function fixture(root, content = original) {
  await mkdir(dirname(join(root, path)), { recursive: true });
  const buffers = [content.subarray(0, 4 * MiB), content.subarray(4 * MiB)];
  const manifest = { schema: 'otziv-raw-evidence-parts-v1', bytes: content.length, sha256: hash(content),
    parts: buffers.map((bytes, index) => ({ file: name + '.part00' + (index + 1), bytes: bytes.length, sha256: hash(bytes) })) };
  async function save() { await writeFile(join(root, path + '.parts.json'), JSON.stringify(manifest)); }
  for (const [index, bytes] of buffers.entries()) await writeFile(join(root, path + '.part00' + (index + 1)), bytes);
  await save();
  return { manifest, buffers, save, read: await createEvidenceReader(root) };
}

test('two bounded plain UTF-8 chunks reconstruct exact large logical bytes and original OCI hash/size', async () => {
  await workspace(async root => {
    const { read } = await fixture(root);
    const actual = await read(path);
    assert.deepEqual(actual, original);
    assert.deepEqual(checkedJson(actual, { digest: 'sha256:' + hash(original), size: original.length }), JSON.parse(original));
    assert.throws(() => checkedJson(actual, { digest: 'sha256:' + 'a'.repeat(64), size: original.length }), /registry_blob_digest_mismatch/);
    assert.throws(() => checkedJson(actual, { digest: 'sha256:' + hash(original), size: original.length - 1 }), /registry_blob_size_mismatch/);
  });
});

test('existing unfragmented file retains original behavior even with unused invalid sidecar', async () => {
  await workspace(async root => {
    const { read } = await fixture(root);
    await writeFile(join(root, path), original);
    await writeFile(join(root, path + '.parts.json'), '{invalid');
    assert.deepEqual(await read(path), original);
  });
});

const invalidManifests = [
  ['wrong schema', x => { x.schema = 'other'; }, /parts_schema/],
  ['unknown manifest field', x => { x.other = true; }, /parts_fields/],
  ['unknown chunk field', x => { x.parts[0].other = true; }, /parts_fields/],
  ['missing chunk', x => { x.parts.pop(); }, /parts_count/],
  ['extra chunk descriptor', x => { x.parts.push({ ...x.parts[0] }); }, /parts_count/],
  ['ordered descriptors reversed', x => { x.parts.reverse(); }, /filename_or_order/],
  ['duplicate descriptor', x => { x.parts[1] = { ...x.parts[0] }; }, /filename_or_order/],
  ['traversal', x => { x.parts[0].file = '../' + x.parts[0].file; }, /filename_or_order/],
  ['absolute file', x => { x.parts[0].file = '/tmp/' + x.parts[0].file; }, /filename_or_order/],
  ['backslash file', x => { x.parts[0].file = 'other\\' + x.parts[0].file; }, /filename_or_order/],
  ['recursive sidecar', x => { x.parts[0].file += '.parts.json'; }, /filename_or_order/],
  ['whole bytes too small', x => { x.bytes = 5 * MiB; }, /total_size/],
  ['whole bytes too large', x => { x.bytes = 8 * MiB + 1; }, /total_size/],
  ['whole bytes not integer', x => { x.bytes = 6.5; }, /total_size/],
  ['empty part', x => { x.parts[1].bytes = 0; }, /chunk_size/],
  ['oversized part', x => { x.parts[0].bytes = 4 * MiB + 1; }, /chunk_size/],
  ['wrong size sum', x => { x.parts[0].bytes--; }, /size_sum/],
  ['wrong full hash', x => { x.sha256 = 'a'.repeat(64); }, /parts_hash/],
  ['wrong part hash', x => { x.parts[1].sha256 = 'a'.repeat(64); }, /chunk_hash/]
];
for (const [label, change, expected] of invalidManifests) test('rejects ' + label, async () => {
  await workspace(async root => {
    const value = await fixture(root);
    change(value.manifest); await value.save();
    await assert.rejects(value.read(path), expected);
  });
});

test('rejects oversized manifest before parsing it', async () => {
  await workspace(async root => {
    const value = await fixture(root);
    await writeFile(join(root, path + '.parts.json'), ' '.repeat(4097));
    await assert.rejects(value.read(path), /parts_file_size/);
  });
});

for (const [label, suffix, value] of [
  ['missing physical chunk', '.part002', null],
  ['truncated chunk', '.part002', Buffer.from('x')],
  ['changed same-size bytes', '.part002', Buffer.alloc(original.length - 4 * MiB, 120)],
  ['oversized physical chunk', '.part001', Buffer.alloc(4 * MiB + 1, 120)],
  ['extra chunk', '.part003', Buffer.from('extra')],
  ['nested parts', '.part001.parts.json', Buffer.from('{}')]
]) test('rejects ' + label, async () => {
  await workspace(async root => {
    const { read } = await fixture(root);
    if (value === null) await rm(join(root, path + suffix));
    else await writeFile(join(root, path + suffix), value);
    await assert.rejects(read(path), /parts_/);
  });
});

test('rejects physically swapped chunks even if descriptor names remain ordered', async () => {
  await workspace(async root => {
    const value = await fixture(root);
    await writeFile(join(root, path + '.part001'), value.buffers[1]);
    await writeFile(join(root, path + '.part002'), value.buffers[0]);
    await assert.rejects(value.read(path), /parts_/);
  });
});

test('each part must be valid UTF-8; split a multibyte codepoint only at its boundary', async () => {
  await workspace(async root => {
    const raw = Buffer.from('{"v":"' + 'x'.repeat(4 * MiB - 7) + 'Ж' + 'x'.repeat(2 * MiB) + '"}');
    const value = await fixture(root, raw);
    await assert.rejects(value.read(path), /encoded data was not valid/);
    const buffers = [raw.subarray(0, 4 * MiB - 1), raw.subarray(4 * MiB - 1)];
    for (const [index, bytes] of buffers.entries()) {
      value.manifest.parts[index].bytes = bytes.length;
      value.manifest.parts[index].sha256 = hash(bytes);
      await writeFile(join(root, path + '.part00' + (index + 1)), bytes);
    }
    await value.save();
    assert.deepEqual(await value.read(path), raw);
  });
});

test('scope guard rejects traversal, absolute paths, alternate separators and other artifact types', async () => {
  await workspace(async root => {
    const { read } = await fixture(root);
    for (const input of ['/tmp/' + name, 'infrastructure/runtime-security/../' + name,
      'infrastructure/runtime-security/./' + name, 'infrastructure/runtime-security/escape:ads',
      'infrastructure/runtime-security/escape\\' + name, 'infrastructure/runtime-security-foreign/' + name]) {
      await assert.rejects(read(input), /path_outside_evidence_scope/);
    }
    const unsupported = 'infrastructure/runtime-security/proofs/test/publication.json';
    await writeFile(join(root, unsupported + '.parts.json'), await readFile(join(root, path + '.parts.json')));
    await assert.rejects(read(unsupported), { code: 'ENOENT' });
  });
});

test('evidence root and nested directory symlinks cannot escape the repository scope', async () => {
  await workspace(async owned => {
    const root = join(owned, 'repository'), external = join(owned, 'outside');
    await mkdir(join(root, 'infrastructure'), { recursive: true }); await mkdir(external);
    const kind = process.platform === 'win32' ? 'junction' : 'dir';
    await symlink(external, join(root, 'infrastructure/runtime-security'), kind);
    await assert.rejects(createEvidenceReader(root), /root_symlink_escape/);
    await rm(join(root, 'infrastructure/runtime-security'));
    await mkdir(join(root, 'infrastructure/runtime-security'));
    await symlink(external, join(root, 'infrastructure/runtime-security/proofs'), kind);
    await mkdir(join(external, 'test'));
    await writeFile(join(external, 'test', name + '.parts.json'), '{}');
    const read = await createEvidenceReader(root);
    await assert.rejects(read(path), /evidence_symlink_escape/);
  });
});

test('part files cannot be directories or recursive manifests', async () => {
  await workspace(async root => {
    const { read } = await fixture(root);
    await rm(join(root, path + '.part001'));
    await mkdir(join(root, path + '.part001'));
    await writeFile(join(root, path + '.part001', 'parts.json'), '{}');
    await assert.rejects(read(path), /regular_file_required/);
  });
});

test('invalid reconstructed JSON remains rejected by the mandatory original OCI parser', async () => {
  await workspace(async root => {
    const raw = Buffer.alloc(6 * MiB, 120);
    const { read } = await fixture(root, raw);
    assert.throws(() => checkedJson(raw, { digest: 'sha256:' + hash(raw), size: raw.length }), /blob_json_invalid/);
    const reassembled = await read(path);
    assert.throws(() => checkedJson(reassembled, { digest: 'sha256:' + hash(raw), size: raw.length }), /blob_json_invalid/);
  });
});

}
