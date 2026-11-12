import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {reviewedImageSetForComponent, validateReviewedImageSet} from './reviewed-image-sets.mjs';

const sha256 = value => createHash('sha256').update(value).digest('hex');
const baseline = await readFile(new URL('./reviewed-images.json', import.meta.url));
const parent = 'ghcr.io/claidd/otziv-security@sha256:374430936cad1fcc6ea665ce93aa12314d980f35f623938b4d9c821a720322e5';

test('C53 phpMyAdmin keeps the C50 service scope and installs only the official apache2 fix', async () => {
  const bytes = await readFile(new URL('./reviewed-images-c53-phpmyadmin.json', import.meta.url));
  const manifest = validateReviewedImageSet('c53-phpmyadmin', bytes, baseline);
  assert.equal(manifest.images.length, 1);
  const image = manifest.images[0];
  assert.equal(reviewedImageSetForComponent('phpmyadmin', 'infrastructure/runtime-security/reviewed-images-c53-phpmyadmin.json').name, 'c53-phpmyadmin');
  const c50 = JSON.parse(await readFile(new URL('./reviewed-images-c50-phpmyadmin.json', import.meta.url))).images[0];
  assert.equal(image.sourceBeforeRef, c50.sourceBeforeRef);
  assert.deepEqual(image.defaultReferencesBefore, c50.defaultReferencesBefore);
  assert.deepEqual(image.buildArgs, {});
  assert.equal(image.candidateBaseRef, parent);
  // The parent is the active phpMyAdmin image: the C50 publication recorded in the activation index before C53.
  const c50Publication = JSON.parse(await readFile(new URL('./proofs/c50-phpmyadmin/publication/publication.json', import.meta.url)));
  assert.equal(c50Publication.reference, parent);
  const recipe = (await readFile(new URL('../../' + image.dockerfile, import.meta.url), 'utf8')).replaceAll('\r\n', '\n');
  assert.equal(sha256(recipe), image.dockerfileSha256);
  // One stage from the accepted C50 digest; nothing is copied or added from outside.
  assert.deepEqual(recipe.match(/^FROM .+$/gm), [`FROM ${parent}`]);
  assert.equal(recipe.match(/^(COPY|ADD|ARG|ENV|USER|ENTRYPOINT|CMD) /gm), null);
  // The only installation is the pinned package from the official Alpine 3.24 main repository.
  const installs = recipe.match(/apk add [^;]+;/g);
  assert.equal(installs.length, 1);
  assert.equal(installs[0].replace(/\\\n\s*/g, ''),
    'apk add --no-cache --repositories-file /dev/null --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main apache2=2.4.71-r0;');
  assert.equal(recipe.match(/apk (upgrade|del|fix)/g), null);
  // The parent must carry the vulnerable build and the result the fixed one.
  assert.ok(recipe.includes("apk --no-network info -v -e apache2 | grep -Fx 'apache2-2.4.69-r0';"));
  assert.ok(recipe.includes("apk --no-network info -v -e apache2 | grep -Fx 'apache2-2.4.71-r0';"));
  // Every other package and the phpMyAdmin payload, the Apache configuration included, are compared before and after.
  assert.ok(recipe.includes('cmp /tmp/c53.packages.before /tmp/c53.packages.after;'));
  assert.ok(recipe.includes('cmp /tmp/c53.payload.before /tmp/c53.payload.after;'));
  assert.equal(recipe.match(/grep -Ev '\^apache2-\[0-9\]'/g).length, 2);
  const c50Recipe = (await readFile(new URL('./builds/c50-phpmyadmin/Dockerfile', import.meta.url), 'utf8')).replaceAll('\r\n', '\n');
  const protectedPaths = '/var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc /usr/lib/php83 /usr/bin/php83 /usr/local/share/otziv /docker-entrypoint.sh /usr/local/bin';
  assert.equal(recipe.split(`find ${protectedPaths} `).length, 3);
  assert.equal(c50Recipe.split(`find ${protectedPaths} `).length, 3, 'C53 protects the same paths as C50');
  // The upgraded server starts with the accepted configuration, and the application stays the accepted one.
  assert.match(recipe, /^RUN php -v && apachectl configtest && httpd -v \| grep -F 'Apache\/2\.4\.71' && php -r '.*"v5\.4\.54"\) exit\(2\);'$/m);
  assert.match(recipe, /^LABEL com\.otziv\.security\.c53="C53: official apache2 security package; accepted application and configuration bytes unchanged"$/m);
  const workflow = await readFile(new URL('../../.github/workflows/quality-gates.yml', import.meta.url), 'utf8');
  assert.match(workflow, /^ {10}- c53-phpmyadmin\r?$/m);
  const incomplete = structuredClone(manifest);
  incomplete.images[0].defaultReferencesBefore.pop();
  assert.throws(() => validateReviewedImageSet('c53-phpmyadmin', Buffer.from(JSON.stringify(incomplete)), baseline), /service_coverage_changed/);
});
