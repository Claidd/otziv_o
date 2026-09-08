import test from 'node:test';
import assert from 'node:assert/strict';
import { validateManifest, publicationIdentity, publishedReference, attestations, assertCleanSource, assertCleanRepository, assertPublicationPreparation, providerJarDigest, REPOSITORY } from './publish-reviewed-images.mjs';
import { execFileSync } from 'node:child_process';
import { mkdtemp, mkdir, writeFile, rm, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';

const digest = 'sha256:' + 'a'.repeat(64), second = 'sha256:' + 'b'.repeat(64);
const manifest = () => ({ schema: 'otziv-reviewed-images-v1', repository: REPOSITORY, images: [{
  component: 'nginx', context: 'infrastructure/runtime-security', dockerfile: 'infrastructure/runtime-security/builds/Nginx.Dockerfile',
  sourceBeforeRef: 'nginx@' + digest, candidateBaseRef: 'nginx@' + second, buildArgs: {} }] });
const env = () => ({ GITHUB_ACTIONS: 'true', GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_REPOSITORY: 'Claidd/otziv_o',
  OTZIV_PUBLISH_REVIEWED_IMAGES: 'true', GITHUB_REF: 'refs/heads/codex/review', GITHUB_SHA: 'c'.repeat(40), GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '1' });

test('actual Git status detects untracked Docker COPY inputs that git diff misses', async () => {
  const fixture = await mkdtemp(join(tmpdir(), 'otziv-publication-source-'));
  const git = args => execFileSync('git', ['-c', 'safe.directory=' + fixture, '-C', fixture, ...args], { encoding: 'utf8', windowsHide: true });
  try {
    git(['init', '--quiet']);
    await writeFile(join(fixture, 'Dockerfile'), 'FROM scratch\nCOPY . /inputs\n');
    git(['add', 'Dockerfile']);
    git(['-c', 'user.name=Publication fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '--quiet', '-m', 'Source fixture']);
    const execute = async (command, args) => { assert.equal(command, 'git'); return git(args); };
    await assertCleanSource(['.'], execute);
    await writeFile(join(fixture, 'injected.go'), 'package main\n');
    assert.equal(git(['diff', 'HEAD', '--', '.']).trim(), '');
    await assert.rejects(assertCleanSource(['.'], execute), /reviewed_build_context_changed_or_untracked/);
  } finally {
    const actual = await realpath(fixture), parent = await realpath(tmpdir());
    assert.ok(actual.startsWith(resolve(parent) + sep) && actual.includes('otziv-publication-source-'));
    await rm(actual, { recursive: true, force: true });
  }
});

test('ordinary CI, forks and missing explicit publication requests cannot publish', () => {
  assert.equal(publicationIdentity(env()).run, '123');
  for (const [key, value] of Object.entries({ GITHUB_ACTIONS: 'false', GITHUB_EVENT_NAME: 'pull_request', GITHUB_REPOSITORY: 'fork/otziv_o',
    OTZIV_PUBLISH_REVIEWED_IMAGES: 'false', GITHUB_REF: 'refs/pull/2/merge', GITHUB_SHA: 'main', GITHUB_RUN_ID: '../123' })) {
    assert.throws(() => publicationIdentity({ ...env(), [key]: value }), undefined, key);
  }
});

test('manifest scopes components, destinations, build contexts and source pins', () => {
  assert.equal(validateManifest(manifest()).length, 1);
  for (const change of [
    value => { value.repository = 'ghcr.io/another/destination'; },
    value => { value.images.push(structuredClone(value.images[0])); },
    value => { value.images[0].component = 'arbitrary'; },
    value => { value.images[0].context = 'infrastructure/../../private'; },
    value => { value.images[0].dockerfile = '/private/Dockerfile'; },
    value => { value.images[0].sourceBeforeRef = 'nginx:latest'; },
    value => { value.images[0].candidateBaseRef = digest; },
    value => { value.images[0].buildArgs = { API_TOKEN: 'fixture' }; },
    value => { value.images[0].prepare = { kind: 'execute-shell' }; },
  ]) {
    const value = manifest(); change(value); assert.throws(() => validateManifest(value));
  }
});

test('only the bounded Keycloak provider build preparation is accepted', () => {
  const value = manifest(); Object.assign(value.images[0], { component: 'keycloak', context: 'infrastructure/keycloak/security-generation', prepare: { kind: 'keycloak-provider-maven' } });
  assert.equal(validateManifest(value)[0].component, 'keycloak');
  assert.throws(() => assertPublicationPreparation(value.images[0]), /legacy_host_provider_build_cannot_publish_clean_source/);
  value.images[0].prepare.kind = 'keycloak-provider-docker-stage';
  assert.equal(validateManifest(value)[0].component, 'keycloak');
  assertPublicationPreparation(value.images[0]);
  assertPublicationPreparation(manifest().images[0]);
  value.images[0].context = 'infrastructure/unrelated'; assert.throws(() => validateManifest(value));
});

test('whole repository guard rejects actual ignored provider output and ignored files outside the build context', async () => {
  const fixture = await mkdtemp(join(tmpdir(), 'otziv-publication-ignored-'));
  const git = args => execFileSync('git', ['-c', 'safe.directory=' + fixture, '-C', fixture, ...args], { encoding: 'utf8', windowsHide: true });
  try {
    git(['init', '--quiet']);
    await mkdir(join(fixture, 'provider'));
    await writeFile(join(fixture, 'provider', 'pom.xml'), '<project/>\n');
    await writeFile(join(fixture, '.gitignore'), 'target/\n.cache/\n');
    git(['add', '.']);
    git(['-c', 'user.name=Publication fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '--quiet', '-m', 'Ignored output fixture']);
    const execute = async (command, args) => { assert.equal(command, 'git'); return git(args); };
    await assertCleanRepository(execute);
    await mkdir(join(fixture, 'provider', 'target'));
    await writeFile(join(fixture, 'provider', 'target', 'provider.jar'), 'generated provider fixture');
    await assertCleanSource(['provider'], execute);
    await assert.rejects(assertCleanRepository(execute), /reviewed_repository_changed_untracked_or_ignored/);
    await rm(join(fixture, 'provider', 'target', 'provider.jar'));
    await assertCleanRepository(execute);
    await mkdir(join(fixture, '.cache'));
    await writeFile(join(fixture, '.cache', 'generated'), 'outside build context');
    await assertCleanSource(['provider'], execute);
    await assert.rejects(assertCleanRepository(execute), /reviewed_repository_changed_untracked_or_ignored/);
  } finally {
    const actual = await realpath(fixture), parent = await realpath(tmpdir());
    assert.ok(actual.startsWith(resolve(parent) + sep) && actual.includes('otziv-publication-ignored-'));
    await rm(actual, { recursive: true, force: true });
  }
});

test('whole repository guard fails closed when Git cannot report source state', async () => {
  await assert.rejects(assertCleanRepository(async () => { throw new Error('fixture Git failure'); }), /fixture Git failure/);
});

test('provider evidence hashes the exact pulled registry digest with a bounded read-only probe', async () => {
  const reference = REPOSITORY + '@' + digest;
  const sha = 'd'.repeat(64);
  assert.equal(await providerJarDigest(reference, async (command, args) => {
    assert.equal(command, 'docker');
    assert.deepEqual(args, ['run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
      '--security-opt', 'no-new-privileges:true', '--pids-limit', '32', '--entrypoint', 'sha256sum', reference,
      '/opt/keycloak/providers/otziv-security-generation.jar']);
    return sha + '  /opt/keycloak/providers/otziv-security-generation.jar\n';
  }), sha);
});

test('provider probe rejects mutable or foreign image references before executing Docker', async () => {
  for (const reference of [REPOSITORY + ':latest', REPOSITORY + '@latest', 'ghcr.io/other/image@' + digest]) {
    let called = false;
    await assert.rejects(providerJarDigest(reference, async () => { called = true; return ''; }));
    assert.equal(called, false);
  }
});

test('provider digest evidence rejects wrong paths, malformed output and failed image reads', async () => {
  const reference = REPOSITORY + '@' + digest;
  for (const output of ['', 'd'.repeat(64) + '  /tmp/other.jar\n', 'x'.repeat(64) + '  /opt/keycloak/providers/otziv-security-generation.jar\n',
    'd'.repeat(64) + '  /opt/keycloak/providers/otziv-security-generation.jar\nextra output']) {
    await assert.rejects(providerJarDigest(reference, async () => output), /provider_probe_invalid_digest/);
  }
  await assert.rejects(providerJarDigest(reference, async () => { throw new Error('fixture image read failure'); }), /fixture image read failure/);
});

test('a local image configuration ID cannot substitute for a published manifest digest', () => {
  assert.throws(() => publishedReference({ 'containerimage.config.digest': digest }));
  assert.throws(() => publishedReference({ 'containerimage.digest': 'latest' }));
  assert.equal(publishedReference({ 'containerimage.digest': second, 'containerimage.config.digest': digest }), REPOSITORY + '@' + second);
});

test('registry evidence must attach to the actual amd64 image rather than another image', () => {
  const index = () => ({ schemaVersion: 2, manifests: [
    { digest, platform: { os: 'linux', architecture: 'amd64' } },
    { digest: second, platform: { os: 'unknown', architecture: 'unknown' }, annotations: { 'vnd.docker.reference.type': 'attestation-manifest', 'vnd.docker.reference.digest': digest } },
  ] });
  assert.equal(attestations(index()).length, 1);
  const missing = index(); missing.manifests.pop(); assert.throws(() => attestations(missing));
  const wrong = index(); wrong.manifests[1].annotations['vnd.docker.reference.digest'] = second; assert.throws(() => attestations(wrong));
  const arm = index(); arm.manifests[0].platform.architecture = 'arm64'; assert.throws(() => attestations(arm));
});
