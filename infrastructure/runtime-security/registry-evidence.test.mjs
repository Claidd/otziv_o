import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { checkedJson, createRegistryReader, gitSource, takePublicationRegistryReader, verifyRegistryEvidence } from './registry-evidence.mjs';

const directory = new URL('./fixtures/provenance-scratch/', import.meta.url);
const fixture = JSON.parse(await readFile(new URL('fixture.json', directory)));
const sha = bytes => 'sha256:' + createHash('sha256').update(bytes).digest('hex');
async function documents() {
  const values = {};
  for (const artifact of fixture.result.artifacts) values[artifact.file] = JSON.parse(await readFile(new URL(artifact.file, directory)));
  return values;
}

// Recompute every dependent descriptor after a semantic mutation. This proves
// content validation rejects an internally consistent forged evidence graph.
function graph(values) {
  const blobs = new Map();
  function pack(name, descriptor) {
    const bytes = Buffer.from(JSON.stringify(values[name]));
    descriptor.digest = sha(bytes); descriptor.size = bytes.length;
    blobs.set(descriptor.digest, bytes);
  }
  const index = values['registry-index.json'];
  const image = index.manifests.find(item => item.platform.os === 'linux');
  const originalImageDigest = image.digest;
  const attached = index.manifests.find(item => item.platform.os === 'unknown');
  pack('registry-amd64-config.json', values['registry-amd64-manifest.json'].config);
  pack('registry-amd64-manifest.json', image);
  const attestation = values['registry-attestation-0.json'];
  attached.annotations['vnd.docker.reference.digest'] = image.digest;
  attestation.subject.digest = image.digest;
  attestation.subject.size = image.size;
  for (const layer of attestation.layers) {
    const file = layer.annotations['in-toto.io/predicate-type'] === 'https://spdx.dev/Document' ? 'registry-attestation-0-payload-0.json' : 'registry-attestation-0-payload-1.json';
    for (const subject of values[file].subject) {
      if (subject.digest.sha256 === originalImageDigest.slice(7)) subject.digest.sha256 = image.digest.slice(7);
    }
    pack(file, layer);
  }
  pack('registry-attestation-0.json', attached);
  const root = {};
  pack('registry-index.json', root);
  return { digest: root.digest, read: async (kind, digest) => { assert.ok(blobs.has(digest)); return blobs.get(digest); },
    retain: async () => {}, expected: fixture.expected };
}

test('real pinned BuildKit local-context OCI statements verify with original bytes', async () => {
  const blobs = new Map(await Promise.all(fixture.result.artifacts.map(async item => [item.digest, await readFile(new URL(item.file, directory))])));
  const retained = [];
  const result = await verifyRegistryEvidence({ digest: fixture.indexDigest,
    read: async (kind, digest) => blobs.get(digest), retain: async (name, bytes) => retained.push({ name, digest: sha(bytes) }), expected: fixture.expected });
  assert.deepEqual(result, fixture.result);
  assert.equal(retained.length, 6);
});

test('original byte digest and length reject corruption without reserializing JSON', async () => {
  const item = fixture.result.artifacts.find(item => item.file.includes('payload-1'));
  const bytes = await readFile(new URL(item.file, directory));
  assert.throws(() => checkedJson(Buffer.concat([bytes, Buffer.from('\n')]), item), /registry_blob_digest_mismatch/);
  assert.throws(() => checkedJson(bytes, { ...item, size: bytes.length + 1 }), /registry_blob_size_mismatch/);
});

test('real nested local context with an external Dockerfile verifies Windows Git paths', async () => {
  const nestedDirectory = new URL('./fixtures/provenance-nested/', import.meta.url);
  const nested = JSON.parse(await readFile(new URL('fixture.json', nestedDirectory)));
  const blobs = new Map(await Promise.all(nested.result.artifacts.map(async item => [item.digest, await readFile(new URL(item.file, nestedDirectory))])));
  const result = await verifyRegistryEvidence({ digest: nested.indexDigest, read: async (kind, digest) => blobs.get(digest), retain: async () => {}, expected: nested.expected });
  assert.deepEqual(result, nested.result);
});

test('a valid descriptor graph cannot conceal a forged subject, commit, source or body', async t => {
  const cases = [
    ['provenance subject', value => { value['registry-attestation-0-payload-1.json'].subject[0].digest.sha256 = 'a'.repeat(64); }, /registry_statement_subject_mismatch/],
    ['SBOM subject', value => { value['registry-attestation-0-payload-0.json'].subject[0].digest.sha256 = 'a'.repeat(64); }, /registry_statement_subject_mismatch/],
    ['source revision', value => { value['registry-attestation-0-payload-1.json'].predicate.runDetails.metadata.buildkit_metadata.vcs.revision = 'a'.repeat(40); }, /registry_provenance_revision_mismatch/],
    ['source repository', value => { value['registry-attestation-0-payload-1.json'].predicate.runDetails.metadata.buildkit_metadata.vcs.source = 'https://github.com/example/different-fixture'; }, /registry_provenance_source_mismatch/],
    ['local context', value => { value['registry-attestation-0-payload-1.json'].predicate.runDetails.metadata.buildkit_metadata.vcs['localdir:context'] = 'other'; }, /registry_provenance_context_mismatch/],
    ['Dockerfile bytes', value => { value['registry-attestation-0-payload-1.json'].predicate.runDetails.metadata.buildkit_metadata.source.infos[0].data = Buffer.from('FROM scratch\n').toString('base64'); }, /registry_provenance_dockerfile_bytes_mismatch/],
    ['request revision', value => { value['registry-attestation-0-payload-1.json'].predicate.buildDefinition.externalParameters.request.root.request.args['vcs:revision'] = 'b'.repeat(40); }, /registry_provenance_request_vcs_mismatch/],
    ['missing SBOM layer', value => { value['registry-attestation-0.json'].layers = value['registry-attestation-0.json'].layers.filter(item => item.annotations['in-toto.io/predicate-type'] !== 'https://spdx.dev/Document'); }, /registry_sbom_missing/],
    ['empty SBOM body', value => { value['registry-attestation-0-payload-0.json'].predicate = {}; }, /registry_sbom_version/],
    ['missing SBOM packages', value => { delete value['registry-attestation-0-payload-0.json'].predicate.packages; }, /registry_sbom_packages_missing/],
    ['missing SBOM relationship', value => { value['registry-attestation-0-payload-0.json'].predicate.relationships = []; }, /registry_sbom_described_package_missing/],
    ['predicate annotation', value => { value['registry-attestation-0-payload-0.json'].predicateType = 'https://example.invalid/predicate'; }, /registry_predicate_annotation_mismatch/],
    ['actual image platform', value => { value['registry-amd64-config.json'].architecture = 'arm64'; }, /registry_actual_image_architecture/],
  ];
  for (const [name, change, error] of cases) await t.test(name, async () => {
    const values = await documents(); change(values);
    await assert.rejects(verifyRegistryEvidence(graph(values)), error);
  });
});

test('failed blob verification retains the actual received bytes', async () => {
  const values = await documents(), input = graph(values), read = input.read;
  const retained = [];
  input.read = async (kind, digest) => Buffer.concat([await read(kind, digest), Buffer.from('\n')]);
  input.retain = async (name, bytes) => retained.push({ name, bytes });
  await assert.rejects(verifyRegistryEvidence(input), /registry_blob_digest_mismatch/);
  assert.equal(retained.length, 1);
  assert.equal(retained[0].name, 'registry-index.json');
  assert.equal(retained[0].bytes.at(-1), 10);
});

test('source normalization accepts GitHub clone suffix but rejects URL ambiguity', () => {
  assert.equal(gitSource('https://github.com/Claidd/otziv_o.git'), gitSource('https://github.com/claidd/otziv_o'));
  for (const value of ['file:///tmp/source', 'https://name:password@example.invalid/repo', 'https://github.com/Claidd/otziv_o#different', 'https://github.com/Claidd/otziv_o?revision=x']) assert.throws(() => gitSource(value));
});

test('registry reader requests only pull scope and never forwards credentials on redirects', async () => {
  const calls = [];
  const read = createRegistryReader({ actor: 'fixture-user', token: 'benign-fixture-value', fetchImpl: async (url, options) => {
    calls.push({ url, options });
    if (calls.length === 1) return new Response(JSON.stringify({ token: 'fixture-bearer' }));
    if (calls.length === 2) return new Response(null, { status: 307, headers: { location: 'https://example.invalid/public-fixture-blob' } });
    return new Response('fixture body');
  } });
  assert.equal((await read('blobs', 'sha256:' + 'a'.repeat(64))).toString(), 'fixture body');
  assert.equal(new URL(calls[0].url).searchParams.get('scope'), 'repository:claidd/otziv-security:pull');
  assert.match(calls[0].options.headers.Authorization, /^Basic /);
  assert.equal(calls[1].options.headers.Authorization, 'Bearer fixture-bearer');
  assert.equal(calls[2].options.headers.Authorization, undefined);
});

test('anonymous evidence reads have no account credential and errors do not expose tokens', async () => {
  const read = createRegistryReader({ fetchImpl: async (url, options) => {
    assert.equal(options.headers.Authorization, undefined);
    throw new Error('fixture-only-sensitive-looking-text');
  } });
  await assert.rejects(read('manifests', 'sha256:' + 'a'.repeat(64)), error => error.message === 'registry_read_failed');
});

test('publication consumes its step token before subprocesses can inherit it', async () => {
  const environment = { GHCR_TOKEN: 'benign-fixture-value', GITHUB_ACTOR: 'fixture-user', UNRELATED: 'unchanged' };
  let requests = 0;
  const read = takePublicationRegistryReader(environment, async (url, options) => {
    if (++requests === 1) {
      assert.equal(options.headers.Authorization, 'Basic ' + Buffer.from('fixture-user:benign-fixture-value').toString('base64'));
      return new Response(JSON.stringify({ token: 'fixture-bearer' }));
    }
    return new Response('fixture body');
  });
  assert.equal(Object.hasOwn(environment, 'GHCR_TOKEN'), false);
  assert.equal(environment.UNRELATED, 'unchanged');
  assert.equal((await read('manifests', 'sha256:' + 'a'.repeat(64))).toString(), 'fixture body');
});
