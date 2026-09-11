import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { reviewedImageSet, reviewedImageSetForComponent, validateReviewedImageSet } from './reviewed-image-sets.mjs';
import { validateManifest } from './publish-reviewed-images.mjs';

const baseline = await readFile(new URL('./reviewed-images.json', import.meta.url));
for (const component of ['prometheus', 'loki', 'alloy', 'tempo', 'grafana', 'keycloak', 'mc']) {
  test(`C15 ${component} binds publication to its actual recipe and original coverage`, async () => {
    const selected = reviewedImageSet(`c15-${component}`);
    const bytes = await readFile(new URL(`./reviewed-images-c15-${component}.json`, import.meta.url));
    const manifest = validateReviewedImageSet(selected.name, bytes, baseline);
    assert.deepEqual(validateManifest(manifest).map(image => image.component), [component]);
    assert.deepEqual(reviewedImageSetForComponent(component, selected.path), selected);
    const recipe = await readFile(new URL('../../' + manifest.images[0].dockerfile, import.meta.url), 'utf8');
    assert.equal(createHash('sha256').update(recipe.replace(/\r\n/g, '\n')).digest('hex'), manifest.images[0].dockerfileSha256);
    if (component === 'mc') {
      const modules = await readFile(new URL('./builds/mc-c15/mc.go.mod', import.meta.url), 'utf8');
      assert.match(modules, /google\.golang\.org\/grpc v1\.83\.2/);
      assert.match(modules, /golang\.org\/x\/net v0\.58\.0/);
    } else if (component !== 'keycloak') assert.match(recipe, /google\.golang\.org\/grpc@v1\.83\.2/);
    const substituted = structuredClone(manifest);
    substituted.images[0].defaultReferencesBefore.pop();
    assert.throws(() => validateReviewedImageSet(selected.name, Buffer.from(JSON.stringify(substituted)), baseline), /service_coverage_changed/);
    assert.throws(() => reviewedImageSetForComponent('unregistered', selected.path), /manifest_component/);
    assert.throws(() => reviewedImageSetForComponent(component, selected.path + '.other'), /manifest_component/);
  });
}

test('C15 selection keeps historical Keycloak publication evidence addressable', () => {
  assert.equal(reviewedImageSetForComponent('keycloak').name, 'c14-keycloak');
  assert.equal(reviewedImageSetForComponent('keycloak', reviewedImageSet('c14-keycloak').path).name, 'c14-keycloak');
});
