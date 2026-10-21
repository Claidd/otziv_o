import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { validateReviewedImageSet } from './reviewed-image-sets.mjs';

const root = new URL('../../', import.meta.url);
const baseline = await readFile(new URL('infrastructure/runtime-security/reviewed-images.json', root));
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const components = new Map([
  ['dozzle', 'dozzle'], ['prometheus', 'prometheus'], ['loki', 'loki'],
  ['alloy', 'alloy'], ['tempo', 'tempo'], ['grafana', 'grafana'],
  ['mc', 'mc'], ['minio', 'minio'], ['postgres', 'postgres'],
]);

for (const [setComponent, component] of components) test(`C36 ${setComponent} keeps its historical service scope and pins a fixed Go builder`, async () => {
  const set = `c36-${setComponent}`;
  const bytes = await readFile(new URL(`infrastructure/runtime-security/reviewed-images-${set}.json`, root));
  const manifest = validateReviewedImageSet(set, bytes, baseline);
  assert.equal(manifest.images.length, 1);
  const image = manifest.images[0];
  assert.equal(image.component, component);
  const dockerfile = (await readFile(new URL(image.dockerfile, root), 'utf8')).replaceAll('\r\n', '\n');
  assert.equal(sha256(dockerfile), image.dockerfileSha256);
  assert.match(dockerfile, /golang:1\.27\.3-(?:bookworm|alpine)@sha256:[a-f0-9]{64}/);
  assert.match(dockerfile, /Go1\.27\.3|go1\.27\.3|1\.27\.3-bookworm/);
});
