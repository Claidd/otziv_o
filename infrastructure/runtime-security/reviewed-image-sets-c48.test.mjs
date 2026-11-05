import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {reviewedImageSetForComponent, validateReviewedImageSet} from './reviewed-image-sets.mjs';

const sha256 = value => createHash('sha256').update(value).digest('hex');
const baseline = await readFile(new URL('./reviewed-images.json', import.meta.url));

for (const [component, parent, packages] of [
  ['keycloak', 'ghcr.io/claidd/otziv-security@sha256:6c6f398170954330c03dc2c16db1b96c5ebf2cb29546fe59542b6dcc7b60ee5e',
    ['libapt-pkg6.0_2.4.15ubuntu0.1_amd64.deb', 'apt_2.4.15ubuntu0.1_amd64.deb']],
  ['alloy', 'ghcr.io/claidd/otziv-security@sha256:27905df1862decf6e9c2543cb476e70e83d68351f7a5bb17194a6a094764db32',
    ['libapt-pkg6.0t64_2.8.4ubuntu0.1_amd64.deb', 'apt_2.8.4ubuntu0.1_amd64.deb']],
]) test(`C48 ${component} keeps service coverage and a frozen official apt-only recipe`, async () => {
  const name = 'c48-' + component;
  const bytes = await readFile(new URL(`./reviewed-images-${name}.json`, import.meta.url));
  const manifest = validateReviewedImageSet(name, bytes, baseline);
  const image = manifest.images[0];
  assert.equal(reviewedImageSetForComponent(component, `infrastructure/runtime-security/reviewed-images-${name}.json`).name, name);
  const recipe = (await readFile(new URL('../../' + image.dockerfile, import.meta.url), 'utf8')).replaceAll('\r\n', '\n');
  assert.equal(sha256(recipe), image.dockerfileSha256);
  assert.equal(image.candidateBaseRef, parent);
  assert.equal(recipe.match(/^FROM .+$/gm).join('\n'), 'FROM ' + parent);
  const inventory = JSON.parse(await readFile(new URL(`./c48-${component}-apt-packages.json`, import.meta.url)));
  const added = [...recipe.matchAll(/^ADD --checksum=sha256:([a-f0-9]{64}) \\\n {4}(\S+) /gm)]
    .map(([, digest, url]) => ({digest, url}));
  assert.deepEqual(added.map(item => item.url.split('/').at(-1)), packages);
  assert.deepEqual(added, inventory.packages.map(item => ({digest: item.sha256, url: item.url})));
  assert.ok(added.every(item => item.url.startsWith('https://security.ubuntu.com/ubuntu/pool/main/a/apt/')));
  assert.match(recipe, /^RUN --network=none set -eu;/m);
  const incomplete = structuredClone(manifest);
  incomplete.images[0].defaultReferencesBefore.pop();
  assert.throws(() => validateReviewedImageSet(name, Buffer.from(JSON.stringify(incomplete)), baseline), /service_coverage_changed/);
});

test('C48 Keycloak keeps the mandatory issuer dependency policy', async () => {
  const bytes = await readFile(new URL('./reviewed-images-c48-keycloak.json', import.meta.url));
  const unaccepted = JSON.parse(bytes);
  delete unaccepted.images[0].knownRuntimeDependencyPolicy;
  assert.throws(() => validateReviewedImageSet('c48-keycloak', Buffer.from(JSON.stringify(unaccepted)), baseline), /dependency_policy/);
});
