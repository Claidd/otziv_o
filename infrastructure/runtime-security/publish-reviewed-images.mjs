import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { appendFile, mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { run } from '../recovery/process.mjs';
import { scan } from './scan.mjs';
import { gitSource, SOURCE_REPOSITORY, takePublicationRegistryReader, verifyRegistryEvidence } from './registry-evidence.mjs';

export const REPOSITORY = 'ghcr.io/claidd/otziv-security';
export const BUILDKIT = 'moby/buildkit@sha256:28a898719c18a33f4e8000685287fa36fd0dd9560c6440227d3a732d79bb41d8';
export const SBOM_GENERATOR = 'docker/buildkit-syft-scanner@sha256:ae4f3b554449e7e25548e7d8ccc029d17357348e30c6e3df01b92bc93654d6a9';
const COMPONENTS = new Set(['prometheus', 'loki', 'alloy', 'tempo', 'grafana', 'mysql', 'postgres', 'keycloak', 'nginx', 'node', 'phpmyadmin', 'certbot', 'minio', 'mc']);
const DIGEST = /^sha256:[a-f0-9]{64}$/;
const ROOT = resolve(fileURLToPath(new URL('../../', import.meta.url)));

export function validateManifest(manifest) {
  assert.equal(manifest.schema, 'otziv-reviewed-images-v1', 'reviewed_manifest_schema');
  assert.equal(manifest.repository, REPOSITORY, 'reviewed_registry_scope');
  assert.ok(Array.isArray(manifest.images) && manifest.images.length, 'reviewed_images_missing');
  const seen = new Set();
  for (const image of manifest.images) {
    assert.ok(COMPONENTS.has(image.component) && !seen.has(image.component), 'reviewed_component_unknown_or_duplicate');
    seen.add(image.component);
    for (const key of ['context', 'dockerfile']) {
      assert.equal(typeof image[key], 'string', 'reviewed_build_path_missing');
      assert.ok(!isAbsolute(image[key]) && !image[key].includes('\\') && !image[key].split('/').includes('..') && image[key].startsWith('infrastructure/'), 'reviewed_build_path_escaped');
    }
    assert.match(image.sourceBeforeRef, /^[a-z0-9./:_-]+@sha256:[a-f0-9]{64}$/, 'reviewed_source_pin_missing');
    assert.match(image.candidateBaseRef, /^[a-z0-9./:_-]+@sha256:[a-f0-9]{64}$/, 'reviewed_candidate_pin_missing');
    assert.ok(image.buildArgs && typeof image.buildArgs === 'object' && !Array.isArray(image.buildArgs), 'reviewed_build_arguments_missing');
    for (const [key, value] of Object.entries(image.buildArgs)) {
      assert.match(key, /^[A-Z][A-Z0-9_]*$/, 'reviewed_build_argument_name');
      assert.ok(!/TOKEN|PASSWORD|SECRET|CREDENTIAL|PRIVATE_KEY/.test(key), 'credentials_must_not_be_build_arguments');
      assert.ok(typeof value === 'string' && !/[\r\n\0]/.test(value), 'reviewed_build_argument_value');
    }
    if (image.prepare) {
      assert.equal(image.component, 'keycloak', 'unexpected_build_preparation');
      assert.equal(image.prepare.kind, 'keycloak-provider-maven', 'unexpected_build_preparation');
      assert.equal(image.context, 'infrastructure/keycloak/security-generation', 'unexpected_provider_context');
    }
  }
  return manifest.images;
}

export function publicationIdentity(environment) {
  assert.equal(environment.GITHUB_ACTIONS, 'true', 'publication_requires_actions');
  assert.equal(environment.GITHUB_EVENT_NAME, 'workflow_dispatch', 'publication_requires_manual_dispatch');
  assert.equal(environment.GITHUB_REPOSITORY, 'Claidd/otziv_o', 'publication_requires_owner_repository');
  assert.equal(environment.OTZIV_PUBLISH_REVIEWED_IMAGES, 'true', 'publication_not_requested');
  assert.match(environment.GITHUB_REF || '', /^refs\/heads\/[A-Za-z0-9._/-]+$/, 'publication_requires_branch');
  assert.match(environment.GITHUB_SHA || '', /^[a-f0-9]{40}$/, 'publication_source_missing');
  for (const key of ['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT']) assert.match(environment[key] || '', /^[1-9][0-9]*$/, 'publication_run_missing');
  return { commit: environment.GITHUB_SHA, run: environment.GITHUB_RUN_ID, attempt: environment.GITHUB_RUN_ATTEMPT };
}

export function publishedReference(metadata) {
  assert.match(metadata['containerimage.digest'] || '', DIGEST, 'registry_manifest_digest_missing');
  return REPOSITORY + '@' + metadata['containerimage.digest'];
}

export function attestations(index) {
  assert.equal(index.schemaVersion, 2, 'registry_manifest_schema');
  const images = index.manifests?.filter(item => item.platform?.os === 'linux' && item.platform?.architecture === 'amd64') || [];
  assert.equal(images.length, 1, 'registry_amd64_manifest_missing');
  const attached = index.manifests.filter(item => item.annotations?.['vnd.docker.reference.type'] === 'attestation-manifest'
    && item.annotations['vnd.docker.reference.digest'] === images[0].digest);
  assert.ok(attached.length, 'registry_attestations_missing');
  for (const item of [...images, ...attached]) assert.match(item.digest, DIGEST);
  return attached;
}

export async function assertCleanSource(paths, execute = run) {
  const status = await execute('git', ['status', '--porcelain=v1', '--untracked-files=all', '--', ...paths]);
  assert.equal(status.trim(), '', 'reviewed_build_context_changed_or_untracked');
}

async function workspacePath(value) {
  const path = await realpath(resolve(ROOT, value)), inside = relative(await realpath(ROOT), path);
  assert.ok(inside && inside !== '..' && !inside.startsWith('..' + sep) && !isAbsolute(inside), 'build_symlink_escaped');
  return path;
}

async function publicBuild(executable, args, env = process.env) {
  // Only reviewed public source paths/versions are arguments. Credentials are
  // supplied by Docker's registry login, never as Dockerfile args or contexts.
  await new Promise((accept, reject) => {
    const child = spawn(executable, args, { env, stdio: ['ignore', 'inherit', 'inherit'], shell: false, windowsHide: true });
    child.once('error', () => reject(new Error('reviewed_build_start_failed')));
    child.once('close', code => code === 0 ? accept() : reject(new Error('reviewed_build_failed')));
  });
}

async function publish(component, outputArgument) {
  const identity = publicationIdentity(process.env);
  const registryRead = takePublicationRegistryReader(process.env);
  assert.equal((await run('git', ['rev-parse', 'HEAD'])).trim(), identity.commit, 'checkout_revision_changed');
  assert.equal(gitSource((await run('git', ['remote', 'get-url', 'origin'])).trim()), gitSource(SOURCE_REPOSITORY), 'checkout_source_repository_changed');
  const manifestPath = resolve(ROOT, 'infrastructure/runtime-security/reviewed-images.json');
  const manifestBytes = await readFile(manifestPath);
  const image = validateManifest(JSON.parse(manifestBytes)).find(item => item.component === component);
  assert.ok(image, 'unreviewed_component');
  const context = await workspacePath(image.context), dockerfile = await workspacePath(image.dockerfile);
  const dockerfileSha256 = createHash('sha256').update((await readFile(dockerfile, 'utf8')).replaceAll('\r\n', '\n')).digest('hex');
  assert.equal(dockerfileSha256, image.dockerfileSha256, 'reviewed_dockerfile_changed');
  const output = resolve(outputArgument);
  await mkdir(output, { recursive: true });
  const tag = `${REPOSITORY}:${component}-${identity.commit}-${identity.run}-${identity.attempt}`;
  const builder = `otziv-review-${component}-${identity.run}-${identity.attempt}`;
  const record = { schema: 'otziv-reviewed-image-publication-v1', component, ...identity, tag,
    sourceBeforeRef: image.sourceBeforeRef, platform: 'linux/amd64', productionActivated: false,
    publicDownloadReadiness: 'NOT_VERIFIED_REQUIRES_ANONYMOUS_PULL_AFTER_VISIBILITY_CONFIGURATION',
    manifestSha256: createHash('sha256').update(manifestBytes).digest('hex'),
    dockerfileSha256,
    builder: BUILDKIT, sbomGenerator: SBOM_GENERATOR, result: 'IN_PROGRESS' };
  let created = false;
  try {
    if (image.prepare) {
      await publicBuild('bash', [resolve(ROOT, 'backend/mvnw'), '-B', '-ntp', '-f', resolve(context, 'pom.xml'), 'verify']);
      record.providerJarSha256 = createHash('sha256').update(await readFile(resolve(context, 'target/otziv-security-generation.jar'))).digest('hex');
    }
    await assertCleanSource([image.context, image.dockerfile, 'infrastructure/runtime-security/reviewed-images.json']);
    await run('docker', ['buildx', 'create', '--name', builder, '--driver', 'docker-container', '--driver-opt', 'image=' + BUILDKIT]);
    created = true;
    await publicBuild('docker', ['buildx', 'build', '--builder', builder, '--platform', 'linux/amd64', '--push',
      '--provenance=mode=max', '--sbom=generator=' + SBOM_GENERATOR,
      '--metadata-file', resolve(output, 'build-metadata.json'), '--tag', tag,
      '--label', 'com.otziv.publication.source=https://github.com/Claidd/otziv_o',
      '--label', 'com.otziv.publication.revision=' + identity.commit,
      '--label', 'com.otziv.reviewed-component=' + component,
      ...Object.entries(image.buildArgs).flatMap(([key, value]) => ['--build-arg', `${key}=${value}`]), '-f', dockerfile, context],
    { ...process.env, BUILDX_GIT_INFO: 'true', BUILDX_GIT_CHECK_DIRTY: 'true' });
    const metadata = JSON.parse(await readFile(resolve(output, 'build-metadata.json'), 'utf8'));
    record.reference = publishedReference(metadata);
    record.attestationEvidence = await verifyRegistryEvidence({
      digest: metadata['containerimage.digest'],
      read: registryRead,
      retain: (name, bytes) => writeFile(resolve(output, name), bytes),
      expected: { source: SOURCE_REPOSITORY, commit: identity.commit, context: image.context,
        dockerfile: image.dockerfile, dockerfileSha256 },
    });
    await publicBuild('docker', ['pull', '--platform', 'linux/amd64', record.reference]);
    const actual = JSON.parse(await run('docker', ['image', 'inspect', record.reference]))[0];
    assert.ok(actual.RepoDigests?.includes(record.reference), 'registry_digest_not_loaded');
    assert.equal(actual.Config.Labels?.['com.otziv.publication.source'], 'https://github.com/Claidd/otziv_o', 'registry_source_repository_changed');
    assert.equal(actual.Config.Labels?.['com.otziv.publication.revision'], identity.commit, 'registry_source_revision_changed');
    assert.equal(actual.Config.Labels?.['com.otziv.reviewed-component'], component, 'registry_component_changed');
    record.imageId = actual.Id;
    record.security = await scan('image', record.reference, resolve(output, 'vulnerabilities.json'));
    record.result = record.security.result;
    if (record.result !== 'PASS') throw new Error('published_candidate_security_gate_failed');
  } catch (error) {
    record.result = 'FAIL';
    record.error = error.message;
    throw error;
  } finally {
    await writeFile(resolve(output, 'publication.json'), JSON.stringify(record, null, 2) + '\n');
    if (created) await run('docker', ['buildx', 'rm', builder]).catch(() => {});
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (process.argv[2] === 'matrix') {
    const images = validateManifest(JSON.parse(await readFile(resolve(ROOT, 'infrastructure/runtime-security/reviewed-images.json'), 'utf8')));
    const matrix = JSON.stringify({ include: images.map(({ component }) => ({ component })) });
    if (process.env.GITHUB_OUTPUT) await appendFile(process.env.GITHUB_OUTPUT, `matrix=${matrix}\n`);
    else console.log(matrix);
  } else {
    assert.ok(process.argv[3], 'publication_output_directory_missing');
    await publish(process.argv[2], process.argv[3]);
  }
}
