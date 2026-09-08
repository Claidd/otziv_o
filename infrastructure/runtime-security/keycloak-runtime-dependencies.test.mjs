import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { checkKeycloakRuntimeDependencies, validateKeycloakDependencyReceipt } from './keycloak-runtime-dependencies.mjs';
import { BUILDKIT, REPOSITORY, SBOM_GENERATOR } from './publish-reviewed-images.mjs';
import { validatePublication, verifyAnonymousDownload } from './verify-anonymous-download.mjs';
import { validateActivation } from './reviewed-image-defaults.mjs';

const imageId = 'sha256:' + 'a'.repeat(64);
const bytes = value => Buffer.from(JSON.stringify(value));
const hash = value => createHash('sha256').update(value).digest('hex');
function scan() {
  const names = [
    ['com.fasterxml.jackson.core', 'jackson-databind', '2.21.6', 'lib/lib/main/com.fasterxml.jackson.core.jackson-databind-2.21.5.jar'],
    ['com.fasterxml.jackson.core', 'jackson-annotations', '2.21', 'lib/lib/main/com.fasterxml.jackson.core.jackson-annotations-2.21.jar'],
    ['org.eclipse.parsson', 'parsson', '1.1.9', 'lib/lib/boot/org.eclipse.parsson.parsson-1.1.7.jar'],
  ];
  return { ArtifactType: 'container_image', Metadata: { ImageID: imageId }, Results: [{ Type: 'jar', Target: 'Java',
    Vulnerabilities: [], Packages: names.map(([group, artifact, version, path]) => ({ Name: group + ':' + artifact,
      Version: version, Identifier: { PURL: `pkg:maven/${group}/${artifact}@${version}` }, FilePath: 'opt/keycloak/' + path })) }] };
}
function addOldCli(value) {
  value.Results[0].Packages.push({ Name: 'com.fasterxml.jackson.core:jackson-databind', Version: '2.21.5',
    Identifier: { PURL: 'pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.21.5' },
    FilePath: 'opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar' });
  return value;
}
function publicationFixture() {
  const image = { component: 'keycloak', prepare: { kind: 'keycloak-provider-docker-stage' },
    sourceBeforeRef: 'quay.io/keycloak/keycloak@sha256:' + 'b'.repeat(64), dockerfileSha256: 'c'.repeat(64) };
  const identity = { commit: 'd'.repeat(40), run: '123', attempt: '1' };
  const manifestBytes = bytes({ images: [image] });
  const publication = { schema: 'otziv-reviewed-image-publication-v1', result: 'PASS',
    security: { result: 'PASS', effectiveBlockingFixedHighOrCritical: 0, unresolvedRiskReview: 'NONE' },
    ...identity, component: 'keycloak', platform: 'linux/amd64', productionActivated: false,
    sourceBeforeRef: image.sourceBeforeRef, dockerfileSha256: image.dockerfileSha256,
    manifestSha256: hash(manifestBytes), builder: BUILDKIT, sbomGenerator: SBOM_GENERATOR, imageId,
    tag: `${REPOSITORY}:keycloak-${identity.commit}-123-1`, reference: REPOSITORY + '@sha256:' + 'e'.repeat(64),
    attestationEvidence: { schema: 'otziv-registry-evidence-v1' },
    knownRuntimeDependencies: checkKeycloakRuntimeDependencies(bytes(scan()), imageId) };
  return { image, identity, manifestBytes, publication };
}

test('approved observed packages pass without assuming that Trivy lists every physical copy', () => {
  const value = scan();
  const receipt = checkKeycloakRuntimeDependencies(bytes(value), imageId);
  validateKeycloakDependencyReceipt(receipt, imageId);
  assert.equal(receipt.rawScanSha256, hash(bytes(value)));
  value.Results[0].Packages[0].FilePath = 'opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar';
  assert.equal(checkKeycloakRuntimeDependencies(bytes(value), imageId).result, 'PASS');
});

test('zero scanner vulnerabilities cannot hide an old shaded Jackson copy', () => {
  const value = addOldCli(scan());
  assert.deepEqual(value.Results[0].Vulnerabilities, []);
  assert.throws(() => checkKeycloakRuntimeDependencies(bytes(value), imageId), /unreviewed_runtime_dependency_version/);
});

test('the retained actual partial image scan is rejected despite zero reported HIGH/CRITICAL', async () => {
  const raw = await readFile(new URL('./history/c7-server-only/vulnerabilities.json', import.meta.url));
  assert.equal(hash(raw), 'ae3f8c27fd60a65d97d6cd2a76371853bd51e71971f3f64bfac92fb3b0f357fb');
  const report = JSON.parse(raw);
  assert.equal(report.Metadata.ImageID, 'sha256:9683153a64b2f0b0e6d196e4664994294cda5d2a288adbcc669f9e39087cb3e1');
  assert.equal(report.Results.flatMap(item => item.Vulnerabilities || []).filter(item => ['HIGH', 'CRITICAL'].includes(item.Severity)).length, 0);
  assert.throws(() => checkKeycloakRuntimeDependencies(raw, report.Metadata.ImageID), /unreviewed_runtime_dependency_version/);
});

test('old Parsson and old Jackson modules outside databind fail independently of the CVE database', () => {
  const parsson = scan();
  parsson.Results[0].Packages[2].Version = '1.1.7';
  assert.throws(() => checkKeycloakRuntimeDependencies(bytes(parsson), imageId), /unreviewed_runtime_dependency_version/);
  const core = addOldCli(scan());
  const item = core.Results[0].Packages.at(-1);
  item.Name = 'com.fasterxml.jackson.core:jackson-core';
  item.Identifier.PURL = 'pkg:maven/com.fasterxml.jackson.core/jackson-core@2.21.5';
  assert.throws(() => checkKeycloakRuntimeDependencies(bytes(core), imageId), /unreviewed_runtime_dependency_version/);
});

test('missing required packages, concealed names and mismatched package identity do not pass', () => {
  for (const change of [
    value => { value.Results = []; },
    value => { value.Results[0].Packages = []; },
    value => { value.Results[0].Packages.pop(); },
    value => { value.Results[0].Packages[0].Name = 'other:package'; },
    value => { value.Results[0].Packages[0].Identifier.PURL += '-changed'; },
    value => { delete value.Results[0].Packages[0].FilePath; },
    value => { value.Results[0].Type = 'other'; },
  ]) {
    const value = scan(); change(value);
    assert.throws(() => checkKeycloakRuntimeDependencies(bytes(value), imageId));
  }
});

test('scan and receipt must bind the actual platform image config', () => {
  assert.throws(() => checkKeycloakRuntimeDependencies(bytes(scan()), 'sha256:' + 'f'.repeat(64)), /scan_image_mismatch/);
  const value = scan(); value.ArtifactType = 'filesystem';
  assert.throws(() => checkKeycloakRuntimeDependencies(bytes(value), imageId), /scan_type/);
  for (const key of ['schema', 'policy', 'result', 'imageId', 'rawScanSha256']) {
    const receipt = checkKeycloakRuntimeDependencies(bytes(scan()), imageId);
    delete receipt[key]; assert.throws(() => validateKeycloakDependencyReceipt(receipt, imageId));
  }
});

test('a successful publication cannot omit the new Keycloak dependency evidence', () => {
  const value = publicationFixture();
  validatePublication(value.publication, value.identity, value.image, hash(value.manifestBytes));
  delete value.publication.knownRuntimeDependencies;
  assert.throws(() => validatePublication(value.publication, value.identity, value.image, hash(value.manifestBytes)), /receipt_missing/);
});

test('anonymous verification checks the actual retained scan before Docker or registry calls', async () => {
  const value = publicationFixture();
  for (const report of [addOldCli(scan()), { ...scan(), ExtraField: 'raw bytes changed' }]) {
    let contacted = false;
    await assert.rejects(verifyAnonymousDownload({ publicationBytes: bytes(value.publication), identity: value.identity,
      image: value.image, manifestSha256: hash(value.manifestBytes), expected: {},
      readPublicationArtifact: async name => { assert.equal(name, 'vulnerabilities.json'); return bytes(report); },
      readAnonymous: async () => { contacted = true; throw new Error('unexpected_registry'); },
      docker: async () => { contacted = true; throw new Error('unexpected_docker'); }, retain: async () => {} }),
    /unreviewed_runtime_dependency_version|anonymous_known_dependencies_changed/);
    assert.equal(contacted, false);
  }
});

test('default activation cannot rely on a forged PASS when the retained scan contains old CLI Jackson', async () => {
  const value = publicationFixture();
  const publicationPath = 'infrastructure/runtime-security/proofs/test/publication/publication.json';
  const anonymousPath = 'infrastructure/runtime-security/proofs/test/anonymous/anonymous-download.json';
  const publication = bytes(value.publication), anonymous = bytes({});
  const files = new Map([[publicationPath, publication], [anonymousPath, anonymous],
    [publicationPath.replace('publication.json', 'vulnerabilities.json'), bytes(addOldCli(scan()))]]);
  const entry = { component: 'keycloak', ...value.identity,
    publication: { path: publicationPath, sha256: hash(publication) }, anonymous: { path: anonymousPath, sha256: hash(anonymous) } };
  await assert.rejects(validateActivation(value.image, entry, value.manifestBytes,
    async path => { assert.ok(files.has(path)); return files.get(path); }), /unreviewed_runtime_dependency_version/);
});
