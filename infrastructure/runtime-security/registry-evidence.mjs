import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { posix } from 'node:path';

export const SOURCE_REPOSITORY = 'https://github.com/Claidd/otziv_o';
const DIGEST = /^sha256:[a-f0-9]{64}$/;
const PROVENANCE = 'https://slsa.dev/provenance/v1';
const SPDX = 'https://spdx.dev/Document';
const BUILD_TYPE = 'https://github.com/moby/buildkit/blob/master/docs/attestations/slsa-definitions.md';
const ACCEPT = 'application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json';
const MAX_BYTES = 64 * 1024 * 1024;
const localPath = value => typeof value === 'string' ? value.replaceAll('\\', '/') : value;

export function gitSource(value) {
  assert.equal(typeof value, 'string', 'provenance_source_missing');
  let url;
  try { url = new URL(value); } catch { throw new Error('provenance_source_invalid'); }
  assert.ok(url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash && !url.port, 'provenance_source_invalid');
  return url.origin.toLowerCase() + url.pathname.replace(/\/$/, '').replace(/\.git$/, '').toLowerCase();
}

export function checkedJson(bytes, descriptor) {
  assert.ok(bytes instanceof Uint8Array, 'registry_raw_bytes_required');
  assert.match(descriptor.digest || '', DIGEST, 'registry_descriptor_digest_invalid');
  assert.equal('sha256:' + createHash('sha256').update(bytes).digest('hex'), descriptor.digest, 'registry_blob_digest_mismatch');
  if (descriptor.size !== undefined) {
    assert.ok(Number.isSafeInteger(descriptor.size) && descriptor.size >= 0, 'registry_descriptor_size_invalid');
    assert.equal(bytes.byteLength, descriptor.size, 'registry_blob_size_mismatch');
  }
  try { return JSON.parse(Buffer.from(bytes).toString('utf8')); } catch { throw new Error('registry_blob_json_invalid'); }
}

export function verifyStatement(bytes, descriptor, image, expected) {
  const statement = checkedJson(bytes, descriptor);
  assert.equal(descriptor.mediaType, 'application/vnd.in-toto+json', 'registry_attestation_media_type');
  assert.equal(statement._type, 'https://in-toto.io/Statement/v1', 'registry_statement_type');
  assert.equal(statement.predicateType, descriptor.annotations?.['in-toto.io/predicate-type'], 'registry_predicate_annotation_mismatch');
  assert.ok(Array.isArray(statement.subject) && statement.subject.length > 0, 'registry_statement_subject_missing');
  for (const subject of statement.subject) {
    assert.equal(subject.digest?.sha256, image.digest.slice(7), 'registry_statement_subject_mismatch');
    assert.equal(typeof subject.name, 'string', 'registry_statement_subject_name_missing');
  }
  const predicate = statement.predicate;
  assert.ok(predicate && typeof predicate === 'object' && !Array.isArray(predicate), 'registry_predicate_body_missing');
  if (statement.predicateType === PROVENANCE) {
    const definition = predicate.buildDefinition;
    assert.equal(definition?.buildType, BUILD_TYPE, 'registry_provenance_build_type');
    const parameters = definition.externalParameters;
    assert.equal(parameters?.configSource?.path, posix.basename(expected.dockerfile), 'registry_provenance_dockerfile_path');
    const vcs = predicate.runDetails?.metadata?.buildkit_metadata?.vcs;
    assert.equal(vcs?.revision, expected.commit, 'registry_provenance_revision_mismatch');
    assert.equal(gitSource(vcs?.source), gitSource(expected.source), 'registry_provenance_source_mismatch');
    assert.equal(localPath(vcs['localdir:context']), expected.context, 'registry_provenance_context_mismatch');
    assert.equal(localPath(vcs['localdir:dockerfile']), posix.dirname(expected.dockerfile), 'registry_provenance_dockerfile_directory');
    const args = parameters.request?.root?.request?.args;
    for (const [key, value] of Object.entries(vcs)) assert.equal(args?.['vcs:' + key], value, 'registry_provenance_request_vcs_mismatch');
    assert.ok(parameters.request?.locals?.some(item => item.name === 'context'), 'registry_provenance_local_context_missing');
    assert.ok(parameters.request?.locals?.some(item => item.name === 'dockerfile'), 'registry_provenance_local_dockerfile_missing');
    assert.ok(Array.isArray(definition.internalParameters?.buildConfig?.llbDefinition) && definition.internalParameters.buildConfig.llbDefinition.length, 'registry_provenance_max_build_config_missing');
    const sources = predicate.runDetails.metadata.buildkit_metadata.source?.infos;
    assert.ok(Array.isArray(sources) && sources.some(item => {
      if (item.filename !== posix.basename(expected.dockerfile) || typeof item.data !== 'string') return false;
      const source = Buffer.from(item.data, 'base64').toString('utf8').replaceAll('\r\n', '\n');
      return createHash('sha256').update(source).digest('hex') === expected.dockerfileSha256;
    }), 'registry_provenance_dockerfile_bytes_mismatch');
  } else if (statement.predicateType === SPDX) {
    assert.equal(predicate.spdxVersion, 'SPDX-2.3', 'registry_sbom_version');
    assert.equal(predicate.SPDXID, 'SPDXRef-DOCUMENT', 'registry_sbom_document_id');
    assert.equal(predicate.dataLicense, 'CC0-1.0', 'registry_sbom_data_license');
    assert.ok(typeof predicate.name === 'string' && predicate.name.length && typeof predicate.documentNamespace === 'string' && predicate.documentNamespace.length, 'registry_sbom_identity_missing');
    assert.ok(Array.isArray(predicate.creationInfo?.creators) && predicate.creationInfo.creators.some(value => /^Tool: syft-v/.test(value)), 'registry_sbom_generator_missing');
    assert.ok(Number.isFinite(Date.parse(predicate.creationInfo?.created)), 'registry_sbom_creation_missing');
    assert.ok(Array.isArray(predicate.packages) && predicate.packages.length, 'registry_sbom_packages_missing');
    const packages = new Set();
    for (const item of predicate.packages) {
      assert.ok(typeof item.SPDXID === 'string' && item.SPDXID.startsWith('SPDXRef-') && typeof item.name === 'string' && item.name.length, 'registry_sbom_package_invalid');
      assert.ok(!packages.has(item.SPDXID), 'registry_sbom_package_duplicate');
      packages.add(item.SPDXID);
    }
    assert.ok(predicate.relationships?.some(item => item.spdxElementId === 'SPDXRef-DOCUMENT' && item.relationshipType === 'DESCRIBES' && packages.has(item.relatedSpdxElement)), 'registry_sbom_described_package_missing');
  } else {
    throw new Error('registry_predicate_unsupported');
  }
  return statement.predicateType;
}

// read(kind, digest) returns registry response bytes. retain stores those exact
// bytes before validation, so failures leave inspectable evidence too.
export async function verifyRegistryEvidence({ digest, read, retain, expected }) {
  const artifacts = [];
  async function get(kind, descriptor, name) {
    const bytes = await read(kind, descriptor.digest);
    await retain(name, bytes);
    const document = checkedJson(bytes, descriptor);
    artifacts.push({ file: name, digest: descriptor.digest, size: bytes.byteLength });
    return { bytes, document };
  }
  const { document: index } = await get('manifests', { digest }, 'registry-index.json');
  assert.equal(index.schemaVersion, 2, 'registry_manifest_schema');
  const images = index.manifests?.filter(item => item.platform?.os === 'linux' && item.platform?.architecture === 'amd64') || [];
  assert.equal(images.length, 1, 'registry_amd64_manifest_missing');
  const image = images[0];
  const { document: imageManifest } = await get('manifests', image, 'registry-amd64-manifest.json');
  assert.equal(imageManifest.schemaVersion, 2, 'registry_image_manifest_schema');
  const { document: config } = await get('blobs', imageManifest.config, 'registry-amd64-config.json');
  assert.equal(config.os, 'linux', 'registry_actual_image_os');
  assert.equal(config.architecture, 'amd64', 'registry_actual_image_architecture');
  const attached = index.manifests.filter(item => item.annotations?.['vnd.docker.reference.type'] === 'attestation-manifest'
    && item.annotations['vnd.docker.reference.digest'] === image.digest);
  assert.ok(attached.length > 0, 'registry_attestations_missing');
  const predicates = new Set();
  for (const [number, descriptor] of attached.entries()) {
    const { document: manifest } = await get('manifests', descriptor, `registry-attestation-${number}.json`);
    assert.equal(manifest.schemaVersion, 2, 'registry_attestation_manifest_schema');
    assert.equal(manifest.subject?.digest, image.digest, 'registry_attestation_subject_mismatch');
    assert.ok(Array.isArray(manifest.layers) && manifest.layers.length, 'registry_attestation_layers_missing');
    for (const [layerNumber, layer] of manifest.layers.entries()) {
      const { bytes } = await get('blobs', layer, `registry-attestation-${number}-payload-${layerNumber}.json`);
      predicates.add(verifyStatement(bytes, layer, image, expected));
    }
  }
  assert.ok(predicates.has(PROVENANCE), 'registry_provenance_missing');
  assert.ok(predicates.has(SPDX), 'registry_sbom_missing');
  return { schema: 'otziv-registry-evidence-v1', imageManifestDigest: image.digest,
    predicates: [...predicates].sort(), artifacts,
    sourceBinding: 'LOCAL_GIT_METADATA_CHECKED_AGAINST_CLEAN_CHECKOUT_NOT_INDEPENDENT_SOURCE_AUTHENTICATION' };
}

async function responseBytes(response) {
  assert.ok(response.ok, 'registry_http_request_failed');
  assert.ok(Number(response.headers.get('content-length') || 0) <= MAX_BYTES, 'registry_response_too_large');
  const chunks = [];
  let length = 0;
  for await (const chunk of response.body) {
    length += chunk.byteLength;
    assert.ok(length <= MAX_BYTES, 'registry_response_too_large');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

// This client is scoped to a fixed GHCR repository. It never reads Docker's
// credential store and never writes or logs either the input or bearer token.
export function createRegistryReader({ token, actor, fetchImpl = fetch } = {}) {
  let bearer;
  return async (kind, digest) => {
    assert.ok(['manifests', 'blobs'].includes(kind), 'registry_resource_kind');
    assert.match(digest, DIGEST, 'registry_resource_digest');
    try {
      if (!bearer) {
        const headers = {};
        if (token) {
          assert.match(actor || '', /^[A-Za-z0-9][A-Za-z0-9-]*$/, 'registry_actor_invalid');
          headers.Authorization = 'Basic ' + Buffer.from(actor + ':' + token).toString('base64');
        }
        const response = await fetchImpl('https://ghcr.io/token?service=ghcr.io&scope=repository%3Aclaidd%2Fotziv-security%3Apull',
          { headers, redirect: 'error', signal: AbortSignal.timeout(60_000) });
        const credentials = JSON.parse((await responseBytes(response)).toString('utf8'));
        bearer = credentials.token || credentials.access_token;
        assert.ok(typeof bearer === 'string' && bearer.length, 'registry_pull_token_missing');
      }
      let url = `https://ghcr.io/v2/claidd/otziv-security/${kind}/${digest}`;
      let headers = { Accept: ACCEPT, Authorization: 'Bearer ' + bearer };
      for (let redirects = 0; redirects < 4; redirects++) {
        const response = await fetchImpl(url, { headers, redirect: 'manual', signal: AbortSignal.timeout(60_000) });
        if ([301, 302, 303, 307, 308].includes(response.status)) {
          const next = new URL(response.headers.get('location'), url);
          assert.ok(next.protocol === 'https:' && !next.username && !next.password, 'registry_redirect_invalid');
          url = next.href;
          headers = { Accept: ACCEPT }; // Never forward registry credentials, even to a redirect.
          continue;
        }
        return await responseBytes(response);
      }
      throw new Error('registry_redirect_limit');
    } catch {
      // Fetch errors may contain URLs or header values: keep CI logs bounded.
      throw new Error('registry_read_failed');
    }
  };
}

// Consume only the publication step's token. Remove it before starting any
// subprocess; Docker's separate login remains responsible for push/pull auth.
export function takePublicationRegistryReader(environment, fetchImpl = fetch) {
  const read = createRegistryReader({ token: environment.GHCR_TOKEN, actor: environment.GITHUB_ACTOR, fetchImpl });
  delete environment.GHCR_TOKEN;
  return read;
}
