import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {reviewedImageSetForComponent, validateReviewedImageSet} from './reviewed-image-sets.mjs';

test('C45 keeps issuer coverage, the frozen recipe and mandatory dependency policy', async () => {
  const baseline = await readFile(new URL('./reviewed-images.json', import.meta.url));
  const bytes = await readFile(new URL('./reviewed-images-c45-keycloak.json', import.meta.url));
  const manifest = validateReviewedImageSet('c45-keycloak', bytes, baseline);
  const image = manifest.images[0];
  assert.equal(reviewedImageSetForComponent('keycloak', 'infrastructure/runtime-security/reviewed-images-c45-keycloak.json').name, 'c45-keycloak');
  const recipe = await readFile(new URL('../../' + image.dockerfile, import.meta.url), 'utf8');
  assert.equal(createHash('sha256').update(recipe.replaceAll('\r\n', '\n')).digest('hex'), image.dockerfileSha256);
  const unaccepted = structuredClone(manifest);
  delete unaccepted.images[0].knownRuntimeDependencyPolicy;
  assert.throws(() => validateReviewedImageSet('c45-keycloak', Buffer.from(JSON.stringify(unaccepted)), baseline), /dependency_policy/);
  const incomplete = structuredClone(manifest);
  incomplete.images[0].defaultReferencesBefore.pop();
  assert.throws(() => validateReviewedImageSet('c45-keycloak', Buffer.from(JSON.stringify(incomplete)), baseline), /service_coverage_changed/);
});
