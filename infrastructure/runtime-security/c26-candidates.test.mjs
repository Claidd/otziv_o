import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readReviewedImageSet, validateReviewedImageSet } from './reviewed-image-sets.mjs';
import { validateManifest } from './publish-reviewed-images.mjs';
import { C26_POLICY } from './keycloak-runtime-dependencies.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const hash = value => createHash('sha256').update(value).digest('hex');

for (const component of ['keycloak', 'nginx', 'phpmyadmin']) {
  test(`C26 ${component} candidate retains source coverage and binds its overlay recipe`, async () => {
    const loaded = await readReviewedImageSet(root, 'c26-' + component);
    const [image] = validateManifest(loaded.manifest);
    assert.equal(image.component, component);
    const dockerfile = (await readFile(new URL('../../' + image.dockerfile, import.meta.url), 'utf8')).replaceAll('\r\n', '\n');
    assert.equal(hash(dockerfile), image.dockerfileSha256);
    assert.ok(dockerfile.startsWith('FROM ' + image.candidateBaseRef));
    const workflow = await readFile(new URL('../../.github/workflows/quality-gates.yml', import.meta.url), 'utf8');
    assert.ok(workflow.includes('          - c26-' + component));
  });
}

test('C26 Keycloak manifest must explicitly request the new dependency policy', async () => {
  const loaded = await readReviewedImageSet(root, 'c26-keycloak');
  const baseline = await readFile(new URL('./reviewed-images.json', import.meta.url));
  assert.equal(loaded.manifest.images[0].knownRuntimeDependencyPolicy, C26_POLICY);
  delete loaded.manifest.images[0].knownRuntimeDependencyPolicy;
  assert.throws(() => validateReviewedImageSet('c26-keycloak', Buffer.from(JSON.stringify(loaded.manifest)), baseline), /dependency_policy/);
});

test('C26 CLI patch and stopped-image inspection reject incomplete or unrelated changes', () => {
  const result = spawnSync(process.platform === 'win32' ? 'python' : 'python3', ['-B', '-m', 'unittest', 'discover',
    '-s', 'infrastructure/runtime-security/builds/c26-keycloak', '-p', 'test_*.py', '-v'],
  { cwd: root, encoding: 'utf8', windowsHide: true });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
});
