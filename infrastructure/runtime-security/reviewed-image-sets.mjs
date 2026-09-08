import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, realpath } from 'node:fs/promises';
import { isAbsolute, relative, resolve, sep } from 'node:path';

export const BASELINE_MANIFEST_SHA256 = 'd48bdb7d6d869cde5d6f1a7b089491765e3eeaf46e0b0b6bb51b7719b38d3ef2';
const BASELINE_PATH = 'infrastructure/runtime-security/reviewed-images.json';
const C12_PATH = 'infrastructure/runtime-security/reviewed-images-c12-phpmyadmin.json';
const C12_CONTEXT = 'infrastructure/runtime-security/builds/phpmyadmin-alpine';
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

// Public entry points accept these names, never a caller-supplied manifest path.
export function reviewedImageSet(name = 'baseline') {
  assert.ok(name === 'baseline' || name === 'c12-phpmyadmin', 'reviewed_image_set_unknown');
  return Object.freeze({ name, path: name === 'baseline' ? BASELINE_PATH : C12_PATH });
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
  const image = manifest.images[0], original = baseline.images.find(item => item.component === 'phpmyadmin');
  assert.equal(image.component, 'phpmyadmin', 'reviewed_image_set_component');
  assert.equal(image.context, C12_CONTEXT, 'reviewed_image_set_context');
  assert.equal(image.dockerfile, C12_CONTEXT + '/Dockerfile', 'reviewed_image_set_dockerfile');
  assert.match(image.dockerfileSha256 || '', /^[a-f0-9]{64}$/, 'reviewed_image_set_dockerfile_hash');
  assert.equal(image.platform, 'linux/amd64', 'reviewed_image_set_platform');
  assert.equal(image.prepare, undefined, 'reviewed_image_set_preparation_forbidden');
  assert.equal(image.sourceBeforeRef, original.sourceBeforeRef, 'reviewed_image_set_historical_source_changed');
  assert.deepEqual(image.defaultReferencesBefore, original.defaultReferencesBefore, 'reviewed_image_set_service_coverage_changed');
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
