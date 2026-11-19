import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {validateReviewedImageSet} from './reviewed-image-sets.mjs';

const read = async path => (await readFile(new URL(path, import.meta.url), 'utf8')).replaceAll('\r\n', '\n');
const baseline = await readFile(new URL('./reviewed-images.json', import.meta.url));
const components = ['prometheus', 'loki', 'tempo', 'alloy', 'grafana', 'dozzle', 'mc', 'minio'];
const oldGo = 'golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132';
const fixedGo = 'golang:1.27.4-bookworm@sha256:4fa7c968ba93f1635670522dbb01a2c78150443e1385746924566079f36570ea';
for (const component of components) test('C55 changes only the compiler and generation identity: ' + component, async () => {
  const manifest = validateReviewedImageSet('c55-' + component,
    Buffer.from(await read('./reviewed-images-c55-' + component + '.json')), baseline);
  const image = manifest.images[0];
  const parent = JSON.parse(await read('./reviewed-images-c50-' + component + '.json')).images[0];
  assert.deepEqual(image.defaultReferencesBefore, parent.defaultReferencesBefore);
  assert.equal(image.sourceBeforeRef, parent.sourceBeforeRef);
  assert.equal(image.candidateBaseRef, parent.candidateBaseRef);
  let expected = await read('./builds/c50-' + component + '/Dockerfile');
  expected = expected.replaceAll(oldGo, fixedGo).replaceAll('Go1.27.3', 'Go1.27.4')
    .replaceAll('golang:1.27.3-alpine@sha256:f2c872bcee949b7be9c490efd02b48a8a2fbf947cdf533f1c58525ec9d8170fd',
      'golang:1.27.4-alpine@sha256:70bd7ddea8b6cbe5d6943cde279d6b67feb10cc924fb9100d1d8757c1575b280');
  if (component === 'grafana') expected = expected.replaceAll('12.4.10+otziv.6', '12.4.10+otziv.7');
  if (component === 'alloy') expected = expected.replaceAll('alloy-daemon-c43', 'alloy-daemon-c55');
  expected += '\nLABEL com.otziv.security.c55="C55: Go 1.27.4; CVE-2026-97054; unchanged upstream source and dependency closure"\n';
  const recipe = await read('../../' + image.dockerfile);
  assert.equal(recipe, expected, 'upstream source, dependency pins, runtime packages and build checks stay unchanged');
  assert.equal(createHash('sha256').update(recipe).digest('hex'), image.dockerfileSha256);
  assert.ok(recipe.includes(component === 'dozzle' ? 'golang:1.27.4-alpine@sha256:' : fixedGo));
});

test('C56 updates only four Oracle Linux libraries on the accepted MySQL engine', async () => {
  const manifest = validateReviewedImageSet('c56-mysql', Buffer.from(await read('./reviewed-images-c56-mysql.json')), baseline);
  const image = manifest.images[0];
  const parent = JSON.parse(await read('./proofs/c46-mysql/publication/publication.json'));
  assert.equal(image.candidateBaseRef, parent.reference);
  const recipe = await read('../../' + image.dockerfile);
  assert.equal(createHash('sha256').update(recipe).digest('hex'), image.dockerfileSha256);
  assert.deepEqual(recipe.match(/^FROM .+$/gm), ['FROM ' + parent.reference]);
  assert.equal(recipe.match(/^(COPY|ADD|ARG|ENV|USER|ENTRYPOINT|CMD) /gm), null);
  assert.equal(recipe.match(/microdnf update[^\n]+/g).length, 1);
  assert.ok(recipe.includes('microdnf update -y libblkid-2.37.4-28.0.1.el9_9 libmount-2.37.4-28.0.1.el9_9 libsmartcols-2.37.4-28.0.1.el9_9 libuuid-2.37.4-28.0.1.el9_9'));
  assert.equal(recipe.match(/grep -vE '\^\(libblkid\|libmount\|libsmartcols\|libuuid\)-'/g).length, 2);
  assert.ok(recipe.includes('sha256sum -c /tmp/mysql-engine.sha256'));
  assert.ok(recipe.includes('test "$(cat /tmp/packages.before)" = "$(cat /tmp/packages.after)"'));
  assert.ok(recipe.includes("'%{VERSION}' mysql-community-server-minimal)\" = '9.7.3'"));
});
