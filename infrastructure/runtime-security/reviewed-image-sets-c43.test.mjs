import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { validateReviewedImageSet } from './reviewed-image-sets.mjs';

const root = new URL('../../', import.meta.url);
const baseline = await readFile(new URL('infrastructure/runtime-security/reviewed-images.json', root));
for (const component of ['tempo', 'loki', 'alloy', 'grafana', 'keycloak']) {
  test(`C43 ${component} retains service identity and pins the security patch`, async () => {
    const set = `c43-${component}`;
    const bytes = await readFile(new URL(`infrastructure/runtime-security/reviewed-images-${set}.json`, root));
    const manifest = validateReviewedImageSet(set, bytes, baseline);
    assert.equal(manifest.images.length, 1);
    const image = manifest.images[0];
    assert.equal(image.component, component);
    const source = (await readFile(new URL(image.dockerfile, root), 'utf8')).replaceAll('\r\n', '\n');
    assert.equal(createHash('sha256').update(source).digest('hex'), image.dockerfileSha256);
    if (component === 'keycloak') {
      assert.match(source, /guava\/33\.7\.2-jre\/guava-33\.7\.2-jre\.jar/);
      assert.equal((source.match(/^ADD /gm) || []).length, 1);
      assert.match(source, /RUN --network=none .*kc\.sh build --db=postgres/);
      assert.match(source, /FROM ghcr\.io\/claidd\/otziv-security@sha256:c28364544835f5fbef6d4e0b0b3e63154127f7fb440b05432760f11235b5f4a9/);
    } else {
      assert.match(source, /get github\.com\/go-jose\/go-jose\/v4@v4\.1\.6/);
      assert.match(source, /go mod verify/);
      assert.match(source, /golang:1\.27\.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132/);
    }
  });
}
