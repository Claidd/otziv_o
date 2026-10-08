import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, realpath } from 'node:fs/promises';
import { isAbsolute, relative, resolve, sep } from 'node:path';
import { C26_POLICY } from './keycloak-runtime-dependencies.mjs';

export const BASELINE_MANIFEST_SHA256 = 'd48bdb7d6d869cde5d6f1a7b089491765e3eeaf46e0b0b6bb51b7719b38d3ef2';
const BASELINE_PATH = 'infrastructure/runtime-security/reviewed-images.json';
const C12_PATH = 'infrastructure/runtime-security/reviewed-images-c12-phpmyadmin.json';
const C12_CONTEXT = 'infrastructure/runtime-security/builds/phpmyadmin-alpine';
const RELEASE_SETS = Object.freeze({
  'c25-certbot': { component: 'certbot', path: 'infrastructure/runtime-security/reviewed-images-c25-certbot.json', context: 'infrastructure/runtime-security/builds/c25-certbot', dockerfile: 'infrastructure/runtime-security/builds/c25-certbot/Dockerfile' },

  'c21-mysql': { component: 'mysql', path: 'infrastructure/runtime-security/reviewed-images-c21-mysql.json',
    context: 'infrastructure/runtime-security/builds/mysql-c21', dockerfile: 'infrastructure/runtime-security/builds/mysql-c21/Dockerfile' },
  'c18-mysql': { component: 'mysql', path: 'infrastructure/runtime-security/reviewed-images-c18-mysql.json',
    context: 'infrastructure/runtime-security/builds/mysql-c18', dockerfile: 'infrastructure/runtime-security/builds/mysql-c18/Dockerfile' },
  'c17-mysql': { component: 'mysql', path: 'infrastructure/runtime-security/reviewed-images-c17-mysql.json',
    context: 'infrastructure/mysql', dockerfile: 'infrastructure/mysql/Dockerfile' },
  'c12-phpmyadmin': { component: 'phpmyadmin', path: C12_PATH, context: C12_CONTEXT, dockerfile: C12_CONTEXT + '/Dockerfile' },
  'c14-mc': { component: 'mc', path: 'infrastructure/runtime-security/reviewed-images-c14-mc.json',
    context: 'infrastructure/runtime-security/builds/minio', dockerfile: 'infrastructure/runtime-security/builds/minio/mc.Dockerfile' },
  // Keep the historical service identity while replacing its local S3 implementation.
  'c14-local-s3': { component: 'minio', path: 'infrastructure/runtime-security/reviewed-images-c14-local-s3.json',
    context: 'infrastructure/runtime-security/builds/versity-c14', dockerfile: 'infrastructure/runtime-security/builds/versity-c14/Dockerfile' },
  'c14-postgres': { component: 'postgres', path: 'infrastructure/runtime-security/reviewed-images-c14-postgres.json',
    context: 'infrastructure/runtime-security/builds/postgres-c14', dockerfile: 'infrastructure/runtime-security/builds/postgres-c14/Dockerfile' },
  'c16-postgres': { component: 'postgres', path: 'infrastructure/runtime-security/reviewed-images-c16-postgres.json',
    context: 'infrastructure/runtime-security/builds/postgres-c16', dockerfile: 'infrastructure/runtime-security/builds/postgres-c16/Dockerfile' },
  'c14-keycloak': { component: 'keycloak', path: 'infrastructure/runtime-security/reviewed-images-c14-keycloak.json',
    context: 'infrastructure/keycloak/security-generation/c14-migration-fix',
    dockerfile: 'infrastructure/keycloak/security-generation/c14-migration-fix/Dockerfile' },
  'c15-prometheus': { component: 'prometheus', path: 'infrastructure/runtime-security/reviewed-images-c15-prometheus.json', context: 'infrastructure/runtime-security', dockerfile: 'infrastructure/runtime-security/builds/Prometheus.Dockerfile' },
  'c15-loki': { component: 'loki', path: 'infrastructure/runtime-security/reviewed-images-c15-loki.json', context: 'infrastructure/runtime-security', dockerfile: 'infrastructure/runtime-security/builds/Loki.Dockerfile' },
  'c15-alloy': { component: 'alloy', path: 'infrastructure/runtime-security/reviewed-images-c15-alloy.json', context: 'infrastructure/runtime-security', dockerfile: 'infrastructure/runtime-security/builds/Alloy.Dockerfile' },
  'c15-tempo': { component: 'tempo', path: 'infrastructure/runtime-security/reviewed-images-c15-tempo.json', context: 'infrastructure/runtime-security', dockerfile: 'infrastructure/runtime-security/builds/Tempo.Dockerfile' },
  'c15-grafana': { component: 'grafana', path: 'infrastructure/runtime-security/reviewed-images-c15-grafana.json', context: 'infrastructure/runtime-security', dockerfile: 'infrastructure/runtime-security/builds/Grafana.Dockerfile' },
  'c15-keycloak': { component: 'keycloak', path: 'infrastructure/runtime-security/reviewed-images-c15-keycloak.json', context: 'infrastructure/keycloak/security-generation/c15-netty', dockerfile: 'infrastructure/keycloak/security-generation/c15-netty/Dockerfile' },
  'c19-keycloak': { component: 'keycloak', path: 'infrastructure/runtime-security/reviewed-images-c19-keycloak.json',
    context: 'infrastructure/keycloak/security-generation/c19-bouncycastle', dockerfile: 'infrastructure/keycloak/security-generation/c19-bouncycastle/Dockerfile' },
  'c15-mc': { component: 'mc', path: 'infrastructure/runtime-security/reviewed-images-c15-mc.json', context: 'infrastructure/runtime-security/builds/mc-c15', dockerfile: 'infrastructure/runtime-security/builds/mc-c15/mc.Dockerfile' },
  'c20-mc': { component: 'mc', path: 'infrastructure/runtime-security/reviewed-images-c20-mc.json',
    context: 'infrastructure/runtime-security/builds/mc-c20', dockerfile: 'infrastructure/runtime-security/builds/mc-c20/Dockerfile' },
  'c22-nginx': { component: 'nginx', path: 'infrastructure/runtime-security/reviewed-images-c22-nginx.json', context: 'infrastructure/runtime-security/builds/c22-nginx', dockerfile: 'infrastructure/runtime-security/builds/c22-nginx/Dockerfile' },
  'c22-mysql': { component: 'mysql', path: 'infrastructure/runtime-security/reviewed-images-c22-mysql.json', context: 'infrastructure/runtime-security/builds/c22-mysql', dockerfile: 'infrastructure/runtime-security/builds/c22-mysql/Dockerfile' },
  'c22-keycloak': { component: 'keycloak', path: 'infrastructure/runtime-security/reviewed-images-c22-keycloak.json', context: 'infrastructure/runtime-security/builds/c22-keycloak', dockerfile: 'infrastructure/runtime-security/builds/c22-keycloak/Dockerfile' },
  'c22-mc': { component: 'mc', path: 'infrastructure/runtime-security/reviewed-images-c22-mc.json', context: 'infrastructure/runtime-security/builds/c22-mc', dockerfile: 'infrastructure/runtime-security/builds/c22-mc/Dockerfile' },
  'c22-phpmyadmin': { component: 'phpmyadmin', path: 'infrastructure/runtime-security/reviewed-images-c22-phpmyadmin.json', context: 'infrastructure/runtime-security/builds/c22-phpmyadmin', dockerfile: 'infrastructure/runtime-security/builds/c22-phpmyadmin/Dockerfile' },
  'c23-node': { component: 'node', path: 'infrastructure/runtime-security/reviewed-images-c23-node.json', context: 'infrastructure/runtime-security/builds/c23-node', dockerfile: 'infrastructure/runtime-security/builds/c23-node/Dockerfile' },
  'c23-postgres': { component: 'postgres', path: 'infrastructure/runtime-security/reviewed-images-c23-postgres.json', context: 'infrastructure/runtime-security/builds/c23-postgres', dockerfile: 'infrastructure/runtime-security/builds/c23-postgres/Dockerfile' },
  'c23-keycloak': { component: 'keycloak', path: 'infrastructure/runtime-security/reviewed-images-c23-keycloak.json', context: 'infrastructure/runtime-security/builds/c23-keycloak', dockerfile: 'infrastructure/runtime-security/builds/c23-keycloak/Dockerfile' },
  'c23-alloy': { component: 'alloy', path: 'infrastructure/runtime-security/reviewed-images-c23-alloy.json', context: 'infrastructure/runtime-security/builds/c23-alloy', dockerfile: 'infrastructure/runtime-security/builds/c23-alloy/Dockerfile' },
  'c24-keycloak': { component: 'keycloak', path: 'infrastructure/runtime-security/reviewed-images-c24-keycloak.json', context: 'infrastructure/runtime-security/builds/c24-keycloak', dockerfile: 'infrastructure/runtime-security/builds/c24-keycloak/Dockerfile' },
  'c26-keycloak': { component: 'keycloak', path: 'infrastructure/runtime-security/reviewed-images-c26-keycloak.json', context: 'infrastructure/runtime-security/builds/c26-keycloak', dockerfile: 'infrastructure/runtime-security/builds/c26-keycloak/Dockerfile' },
  'c26-nginx': { component: 'nginx', path: 'infrastructure/runtime-security/reviewed-images-c26-nginx.json', context: 'infrastructure/runtime-security/builds/c26-nginx', dockerfile: 'infrastructure/runtime-security/builds/c26-nginx/Dockerfile' },
  'c28-nginx': { component: 'nginx', path: 'infrastructure/runtime-security/reviewed-images-c28-nginx.json', context: 'infrastructure/runtime-security/builds/c28-nginx', dockerfile: 'infrastructure/runtime-security/builds/c28-nginx/Dockerfile' },
  'c26-phpmyadmin': { component: 'phpmyadmin', path: 'infrastructure/runtime-security/reviewed-images-c26-phpmyadmin.json', context: 'infrastructure/runtime-security/builds/c26-phpmyadmin', dockerfile: 'infrastructure/runtime-security/builds/c26-phpmyadmin/Dockerfile' },
  'c27-phpmyadmin': { component: 'phpmyadmin', path: 'infrastructure/runtime-security/reviewed-images-c27-phpmyadmin.json', context: 'infrastructure/runtime-security/builds/c27-phpmyadmin', dockerfile: 'infrastructure/runtime-security/builds/c27-phpmyadmin/Dockerfile' },
});
// These two local-stack dependencies were absent from the immutable C7 manifest.
// Keep their original pins and service coverage explicit when adding publication.
export function supplementalReviewedSources() {
  return [
    { component: 'mc', sourceBeforeRef: 'minio/mc@sha256:aead63c77f9db9107f1696fb08ecb0faeda23729cde94b0f663edf4fe09728e3',
      defaultReferencesBefore: [{ path: 'compose.prod-local.yaml', service: 'minio-init' }] },
    { component: 'minio', sourceBeforeRef: 'minio/minio@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e',
      defaultReferencesBefore: [{ path: 'compose.prod-local.yaml', service: 'minio' }] },
  ];
}
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

// Public entry points accept these names, never a caller-supplied manifest path.
export function reviewedImageSet(name = 'baseline') {
  assert.ok(typeof name === 'string' && (name === 'baseline' || Object.hasOwn(RELEASE_SETS, name)), 'reviewed_image_set_unknown');
  return Object.freeze({ name, path: name === 'baseline' ? BASELINE_PATH : RELEASE_SETS[name].path });
}

export function reviewedImageSetForComponent(component, manifestPath) {
  const match = Object.entries(RELEASE_SETS).find(([, value]) => value.component === component && (manifestPath === undefined || value.path === manifestPath));
  assert.ok(match, 'activation_versioned_manifest_component');
  return reviewedImageSet(match[0]);
}

export function selectedReviewedImageSet(environment = process.env) {
  return reviewedImageSet(environment.OTZIV_REVIEWED_IMAGE_SET === undefined ? 'baseline' : environment.OTZIV_REVIEWED_IMAGE_SET);
}

export function validateReviewedImageSet(name, manifestBytes, baselineBytes = manifestBytes) {
  const selected = reviewedImageSet(name);
  assert.equal(sha256(baselineBytes), BASELINE_MANIFEST_SHA256, 'reviewed_image_set_baseline_changed');
  if (selected.name === 'baseline') {
    assert.equal(sha256(manifestBytes), BASELINE_MANIFEST_SHA256, 'reviewed_image_set_baseline_changed');
    return JSON.parse(manifestBytes);
  }
  const baseline = JSON.parse(baselineBytes), manifest = JSON.parse(manifestBytes);
  assert.equal(manifest.schema, baseline.schema, 'reviewed_image_set_schema');
  assert.equal(manifest.repository, baseline.repository, 'reviewed_image_set_repository');
  assert.equal(manifest.publicationSet, selected.name, 'reviewed_image_set_manifest_mismatch');
  assert.equal(manifest.baselineManifestSha256, BASELINE_MANIFEST_SHA256, 'reviewed_image_set_baseline_binding');
  assert.ok(Array.isArray(manifest.images) && manifest.images.length === 1, 'reviewed_image_set_single_component_required');
  const definition = RELEASE_SETS[name];
  const image = manifest.images[0], original = [...baseline.images, ...supplementalReviewedSources()].find(item => item.component === definition.component);
  assert.equal(image.component, definition.component, 'reviewed_image_set_component');
  assert.equal(image.context, definition.context, 'reviewed_image_set_context');
  assert.equal(image.dockerfile, definition.dockerfile, 'reviewed_image_set_dockerfile');
  assert.match(image.dockerfileSha256 || '', /^[a-f0-9]{64}$/, 'reviewed_image_set_dockerfile_hash');
  assert.equal(image.platform, 'linux/amd64', 'reviewed_image_set_platform');
  assert.equal(image.prepare, undefined, 'reviewed_image_set_preparation_forbidden');
  assert.equal(image.sourceBeforeRef, original.sourceBeforeRef, 'reviewed_image_set_historical_source_changed');
  assert.deepEqual(image.defaultReferencesBefore, original.defaultReferencesBefore, 'reviewed_image_set_service_coverage_changed');
  if (name === 'c26-keycloak') assert.equal(image.knownRuntimeDependencyPolicy, C26_POLICY, 'reviewed_image_set_dependency_policy');
  return manifest;
}

export async function readReviewedImageSet(sourceRoot, name = 'baseline') {
  const selected = reviewedImageSet(name), root = await realpath(sourceRoot);
  async function fixedFile(path) {
    const actual = await realpath(resolve(root, path)), inside = relative(root, actual);
    assert.ok(inside && inside !== '..' && !inside.startsWith('..' + sep) && !isAbsolute(inside), 'reviewed_image_set_symlink_escaped');
    return readFile(actual);
  }
  const baselineBytes = await fixedFile(BASELINE_PATH);
  const bytes = selected.name === 'baseline' ? baselineBytes : await fixedFile(selected.path);
  return { ...selected, bytes, manifest: validateReviewedImageSet(selected.name, bytes, baselineBytes), sha256: sha256(bytes) };
}

export function publicationSetFields(name = 'baseline') {
  const selected = reviewedImageSet(name);
  // C7 receipts predate this optional discriminator and retain their exact shape.
  return selected.name === 'baseline' ? {} : { manifestSet: selected.name, manifestPath: selected.path };
}

export function assertPublicationSet(record, name = 'baseline') {
  const selected = reviewedImageSet(name);
  assert.equal(record.manifestSet === undefined ? 'baseline' : record.manifestSet, selected.name, 'anonymous_publication_manifest_set_mismatch');
  assert.equal(record.manifestPath === undefined ? BASELINE_PATH : record.manifestPath, selected.path, 'anonymous_publication_manifest_path_mismatch');
}
