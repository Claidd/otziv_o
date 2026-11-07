import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {reviewedImageSetForComponent, validateReviewedImageSet} from './reviewed-image-sets.mjs';

const sha256 = value => createHash('sha256').update(value).digest('hex');
const baseline = await readFile(new URL('./reviewed-images.json', import.meta.url));
const parent = 'ghcr.io/claidd/otziv-security@sha256:26663e414ba75586834873d6a43d8c1a105c12f58804e7fb961581d61a6dbdd5';
const builder = 'node:22-trixie-slim@sha256:7b8a0c89c54499bee567618f96578e1a12a800f062fbdbfd1fb6a443fa6f6284';

test('C51 PostgreSQL keeps the C36 service scope and a frozen official zlib1g-only recipe', async () => {
  const bytes = await readFile(new URL('./reviewed-images-c51-postgres.json', import.meta.url));
  const manifest = validateReviewedImageSet('c51-postgres', bytes, baseline);
  assert.equal(manifest.images.length, 1);
  const image = manifest.images[0];
  assert.equal(reviewedImageSetForComponent('postgres', 'infrastructure/runtime-security/reviewed-images-c51-postgres.json').name, 'c51-postgres');
  const c36 = JSON.parse(await readFile(new URL('./reviewed-images-c36-postgres.json', import.meta.url))).images[0];
  assert.equal(image.sourceBeforeRef, c36.sourceBeforeRef);
  assert.deepEqual(image.defaultReferencesBefore, c36.defaultReferencesBefore);
  assert.equal(image.candidateBaseRef, parent);
  assert.deepEqual(image.buildImageRefs, [builder]);
  assert.deepEqual([image.publishedDigest, image.publicationStatus, image.deploymentStatus, image.unfixedRiskReview],
    [null, 'NOT_PUBLISHED', 'NOT_ACTIVATED', 'REQUIRED']);
  const recipe = (await readFile(new URL('../../' + image.dockerfile, import.meta.url), 'utf8')).replaceAll('\r\n', '\n');
  assert.equal(sha256(recipe), image.dockerfileSha256);
  assert.deepEqual(recipe.match(/^FROM .+$/gm), [`FROM ${parent} AS parent`, `FROM ${builder} AS packages`, 'FROM parent']);
  const inventory = JSON.parse(await readFile(new URL('./c51-postgres-zlib-package.json', import.meta.url)));
  const added = [...recipe.matchAll(/^ADD --checksum=sha256:([a-f0-9]{64}) \\\n {4}(\S+) /gm)]
    .map(([, digest, url]) => ({digest, url}));
  assert.deepEqual(added, [{digest: inventory.sha256, url: inventory.url}]);
  assert.equal(inventory.signedArchiveIndex.sha256, inventory.sha256);
  assert.ok(inventory.url.startsWith('https://deb.debian.org/debian-security/pool/updates/main/z/zlib/'));
  assert.match(recipe, /^RUN --network=none mkdir -p \/refresh\/root\/var\/lib\/dpkg\/info && node \/refresh\/update-status\.mjs$/m);
  assert.match(recipe, /^COPY --from=packages \/refresh\/root\/ \/$/m);
  assert.ok(recipe.includes(`printf '%s  %s\\n' ${inventory.payload['usr/lib/x86_64-linux-gnu/libz.so.1.3.1'].sha256} \\\n`));
  assert.match(recipe, /test "\$\(postgres --version\)" = 'postgres \(PostgreSQL\) 17\.11'/);
  assert.match(recipe, /com\.otziv\.security\.proof="postgres-c51-zlib-deb13u1"/);
  const updater = await readFile(new URL('./builds/c51-postgres/update-status.mjs', import.meta.url), 'utf8');
  assert.match(updater, /new Set\(\['zlib1g'\]\)/);
  assert.ok(updater.includes(`version='${inventory.version}', previous='${inventory.previousVersion}'`));
  const workflow = await readFile(new URL('../../.github/workflows/quality-gates.yml', import.meta.url), 'utf8');
  assert.match(workflow, /^ {10}- c51-postgres\r?$/m);
  const incomplete = structuredClone(manifest);
  incomplete.images[0].defaultReferencesBefore.pop();
  assert.throws(() => validateReviewedImageSet('c51-postgres', Buffer.from(JSON.stringify(incomplete)), baseline), /service_coverage_changed/);
});
