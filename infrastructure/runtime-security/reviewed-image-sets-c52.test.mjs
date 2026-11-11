import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {reviewedImageSetForComponent, validateReviewedImageSet} from './reviewed-image-sets.mjs';

const sha256 = value => createHash('sha256').update(value).digest('hex');
const baseline = await readFile(new URL('./reviewed-images.json', import.meta.url));
const parent = 'ghcr.io/claidd/otziv-security@sha256:a1f83a9a65d250dc10276236d09161cf47c325cb4e876e7d82046b16dad7cefa';

test('C52 nginx keeps the C50 service scope and installs only the official libxslt fix', async () => {
  const bytes = await readFile(new URL('./reviewed-images-c52-nginx.json', import.meta.url));
  const manifest = validateReviewedImageSet('c52-nginx', bytes, baseline);
  assert.equal(manifest.images.length, 1);
  const image = manifest.images[0];
  assert.equal(reviewedImageSetForComponent('nginx', 'infrastructure/runtime-security/reviewed-images-c52-nginx.json').name, 'c52-nginx');
  const c50 = JSON.parse(await readFile(new URL('./reviewed-images-c50-nginx.json', import.meta.url))).images[0];
  assert.equal(image.sourceBeforeRef, c50.sourceBeforeRef);
  assert.deepEqual(image.defaultReferencesBefore, c50.defaultReferencesBefore);
  assert.deepEqual(image.buildArgs, {});
  assert.equal(image.candidateBaseRef, parent);
  const recipe = (await readFile(new URL('../../' + image.dockerfile, import.meta.url), 'utf8')).replaceAll('\r\n', '\n');
  assert.equal(sha256(recipe), image.dockerfileSha256);
  // One stage from the accepted C50 digest; nothing is copied or added from outside.
  assert.deepEqual(recipe.match(/^FROM .+$/gm), [`FROM ${parent}`]);
  assert.equal(recipe.match(/^(COPY|ADD|ARG|ENV|USER|ENTRYPOINT|CMD) /gm), null);
  // The only installation is the pinned package from the official Alpine 3.24 main repository.
  const installs = recipe.match(/apk add [^;]+;/g);
  assert.equal(installs.length, 1);
  assert.equal(installs[0].replace(/\\\n\s*/g, ''),
    'apk add --no-cache --repositories-file /dev/null --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main libxslt=1.1.43-r4;');
  assert.equal(recipe.match(/apk (upgrade|del|fix)/g), null);
  // The parent must carry the vulnerable build and the result the fixed one.
  assert.ok(recipe.includes("apk --no-network info -v -e libxslt | grep -Fx 'libxslt-1.1.43-r3';"));
  assert.ok(recipe.includes("apk --no-network info -v -e libxslt | grep -Fx 'libxslt-1.1.43-r4';"));
  // Every other package and the nginx payload are compared before and after.
  assert.ok(recipe.includes('cmp /tmp/c52.packages.before /tmp/c52.packages.after;'));
  assert.ok(recipe.includes('cmp /tmp/c52.payload.before /tmp/c52.payload.after;'));
  assert.equal(recipe.match(/grep -Ev '\^libxslt-\[0-9\]'/g).length, 2);
  assert.match(recipe, /^RUN nginx -t && nginx -v$/m);
  assert.match(recipe, /^LABEL com\.otziv\.security\.c52="C52: official libxslt security package; accepted application and configuration bytes unchanged"$/m);
  const workflow = await readFile(new URL('../../.github/workflows/quality-gates.yml', import.meta.url), 'utf8');
  assert.match(workflow, /^ {10}- c52-nginx\r?$/m);
  const incomplete = structuredClone(manifest);
  incomplete.images[0].defaultReferencesBefore.pop();
  assert.throws(() => validateReviewedImageSet('c52-nginx', Buffer.from(JSON.stringify(incomplete)), baseline), /service_coverage_changed/);
});

test('the web image pins the same fix for its Alpine 3.21 base and the gate checks the shipped version', async () => {
  const dockerfile = (await readFile(new URL('../../frontend/Dockerfile', import.meta.url), 'utf8')).replaceAll('\r\n', '\n');
  assert.match(dockerfile, /--repository https:\/\/dl-cdn\.alpinelinux\.org\/alpine\/v3\.21\/main [^\n]*\blibxslt=1\.1\.42-r3\b/);
  assert.ok(dockerfile.includes("apk --no-network info -v -e libxslt | grep -Fx 'libxslt-1.1.42-r3'"));
  const workflow = await readFile(new URL('../../.github/workflows/quality-gates.yml', import.meta.url), 'utf8');
  assert.ok(workflow.includes('apk --no-network info -v -e libxslt | grep -Fx libxslt-1.1.42-r3'));
});
