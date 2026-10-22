import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { readReviewedImageSet, reviewedImageSet, reviewedImageSetForComponent } from './reviewed-image-sets.mjs';
import { validateManifest } from './publish-reviewed-images.mjs';

const root = resolve(import.meta.dirname, '../..');
const dockerfilePath = 'infrastructure/runtime-security/builds/c34-certbot/Dockerfile';
const installerPath = 'infrastructure/runtime-security/builds/c34-certbot/install-certifi-wheel.py';
const base = 'ghcr.io/claidd/otziv-security@sha256:fc21f2b893f9db7550cbad759cb2ad50fbf49bee61d5f58a3a45b009037a8764';
const wheelHash = '42d35340cc5b9ba78e818ddbff0c5b1c2b779de448a340fd1b989f5fa8670e1b';

test('C34 Certbot keeps C25 immutable and binds the replacement wheel to a reviewed recipe', async () => {
  const selected = await readReviewedImageSet(root, 'c34-certbot');
  const [image] = validateManifest(selected.manifest);
  const c25 = await readReviewedImageSet(root, 'c25-certbot');
  const [previous] = validateManifest(c25.manifest);
  const dockerfile = (await readFile(resolve(root, dockerfilePath), 'utf8')).replaceAll('\r\n', '\n');
  const installer = await readFile(resolve(root, installerPath), 'utf8');

  assert.equal(reviewedImageSetForComponent('certbot').name, 'c34-certbot');
  assert.deepEqual(reviewedImageSetForComponent('certbot', reviewedImageSet('c25-certbot').path), reviewedImageSet('c25-certbot'));
  assert.equal(image.candidateBaseRef, base);
  assert.equal(previous.context, 'infrastructure/runtime-security/builds/c25-certbot');
  assert.equal(image.dockerfile, dockerfilePath);
  assert.equal(image.dockerfileSha256, createHash('sha256').update(dockerfile).digest('hex'));
  assert.match(dockerfile, /^FROM ghcr\.io\/claidd\/otziv-security@sha256:fc21f2b893f9db7550cbad759cb2ad50fbf49bee61d5f58a3a45b009037a8764$/m);
  assert.match(dockerfile, new RegExp('ADD --checksum=sha256:' + wheelHash + ' https://files\\.pythonhosted\\.org/.*/certifi-2026\\.10\\.19-py3-none-any\\.whl'));
  assert.ok(installer.includes("metadata.version('certifi') == '2026.7.22'"));
  assert.ok(installer.includes("metadata.version('certifi') == '2026.10.19'"));
  assert.ok(installer.includes(wheelHash));
});
