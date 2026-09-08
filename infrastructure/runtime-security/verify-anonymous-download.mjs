import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { run } from '../recovery/process.mjs';
import { BUILDKIT, REPOSITORY, SBOM_GENERATOR, validateManifest } from './publish-reviewed-images.mjs';
import { checkedJson, createRegistryReader, SOURCE_REPOSITORY, verifyRegistryEvidence } from './registry-evidence.mjs';
import { checkKeycloakRuntimeDependencies, requiresKeycloakDependencyProof, validateKeycloakDependencyReceipt } from './keycloak-runtime-dependencies.mjs';

const ROOT = resolve(fileURLToPath(new URL('../../', import.meta.url)));
const DIGEST = /^sha256:[a-f0-9]{64}$/;
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const json = value => Buffer.from(JSON.stringify(value, null, 2) + '\n');

export function anonymousIdentity(environment) {
  assert.equal(environment.GITHUB_ACTIONS, 'true', 'anonymous_requires_actions');
  assert.equal(environment.GITHUB_EVENT_NAME, 'workflow_dispatch', 'anonymous_requires_manual_dispatch');
  assert.equal(environment.GITHUB_REPOSITORY, 'Claidd/otziv_o', 'anonymous_requires_owner_repository');
  assert.equal(environment.OTZIV_VERIFY_ANONYMOUS_DOWNLOAD, 'true', 'anonymous_not_requested');
  assert.equal(environment.RUNNER_ENVIRONMENT, 'github-hosted', 'anonymous_requires_fresh_hosted_runner');
  assert.equal(environment.RUNNER_OS, 'Linux', 'anonymous_requires_linux_runner');
  assert.match(environment.GITHUB_SHA || '', /^[a-f0-9]{40}$/, 'anonymous_source_missing');
  assert.match(environment.GITHUB_REF || '', /^refs\/heads\/[A-Za-z0-9._/-]+$/, 'anonymous_requires_branch');
  for (const key of ['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT']) assert.match(environment[key] || '', /^[1-9][0-9]*$/, 'anonymous_run_missing');
  for (const key of ['GHCR_TOKEN', 'GITHUB_TOKEN', 'GH_TOKEN', 'DOCKER_AUTH_CONFIG', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CERT_PATH', 'DOCKER_TLS_VERIFY']) {
    assert.ok(!environment[key], 'anonymous_credential_or_remote_docker_environment');
  }
  return { commit: environment.GITHUB_SHA, run: environment.GITHUB_RUN_ID, attempt: environment.GITHUB_RUN_ATTEMPT };
}

export function validatePublication(publication, identity, image, manifestSha256) {
  assert.equal(publication.schema, 'otziv-reviewed-image-publication-v1', 'anonymous_publication_schema');
  assert.equal(publication.result, 'PASS', 'anonymous_publication_not_passed');
  assert.equal(publication.security?.result, 'PASS', 'anonymous_publication_security_not_passed');
  for (const key of ['commit', 'run', 'attempt']) assert.equal(publication[key], identity[key], 'anonymous_publication_identity_mismatch');
  assert.equal(publication.component, image.component, 'anonymous_publication_component_mismatch');
  assert.equal(publication.platform, 'linux/amd64', 'anonymous_publication_platform_mismatch');
  assert.equal(publication.productionActivated, false, 'anonymous_publication_activation_mismatch');
  assert.equal(publication.sourceBeforeRef, image.sourceBeforeRef, 'anonymous_publication_source_pin_mismatch');
  assert.equal(publication.dockerfileSha256, image.dockerfileSha256, 'anonymous_publication_dockerfile_mismatch');
  assert.equal(publication.manifestSha256, manifestSha256, 'anonymous_publication_reviewed_manifest_mismatch');
  assert.equal(publication.builder, BUILDKIT, 'anonymous_publication_builder_mismatch');
  assert.equal(publication.sbomGenerator, SBOM_GENERATOR, 'anonymous_publication_scanner_mismatch');
  assert.match(publication.imageId || '', DIGEST, 'anonymous_publication_image_id_missing');
  if (requiresKeycloakDependencyProof(image)) validateKeycloakDependencyReceipt(publication.knownRuntimeDependencies, publication.imageId);
  assert.equal(publication.tag, `${REPOSITORY}:${image.component}-${identity.commit}-${identity.run}-${identity.attempt}`, 'anonymous_publication_tag_identity_mismatch');
  assert.ok(typeof publication.reference === 'string' && publication.reference.startsWith(REPOSITORY + '@'), 'anonymous_publication_registry_scope');
  const digest = publication.reference.slice(REPOSITORY.length + 1);
  assert.match(digest, DIGEST, 'anonymous_publication_immutable_reference_missing');
  assert.equal(publication.attestationEvidence?.schema, 'otziv-registry-evidence-v1', 'anonymous_publication_evidence_missing');
  return digest;
}

export function assertPulledImage(actual, publication) {
  assert.ok(Array.isArray(actual) && actual.length === 1, 'anonymous_inspected_image_missing');
  const image = actual[0];
  assert.equal(image.Id, publication.imageId, 'anonymous_loaded_image_id_mismatch');
  assert.equal(image.Os, 'linux', 'anonymous_loaded_image_os_mismatch');
  assert.equal(image.Architecture, 'amd64', 'anonymous_loaded_image_architecture_mismatch');
  assert.ok(image.RepoDigests?.includes(publication.reference), 'anonymous_loaded_registry_digest_mismatch');
  assert.equal(image.Config?.Labels?.['com.otziv.publication.source'], SOURCE_REPOSITORY, 'anonymous_loaded_source_label_mismatch');
  assert.equal(image.Config?.Labels?.['com.otziv.publication.revision'], publication.commit, 'anonymous_loaded_revision_label_mismatch');
  assert.equal(image.Config?.Labels?.['com.otziv.reviewed-component'], publication.component, 'anonymous_loaded_component_label_mismatch');
  return image;
}

// No inherited account credentials, Docker contexts, or proxy configuration.
// The empty PATH also prevents Docker's default credential-helper discovery.
export function anonymousDockerEnvironment(config, emptyPath) {
  return { HOME: config, DOCKER_CONFIG: config, PATH: emptyPath, LANG: 'C.UTF-8' };
}

export async function verifyAnonymousDownload({ publicationBytes, identity, image, manifestSha256, expected,
  readPublicationArtifact, readAnonymous, docker, retain, now = () => new Date().toISOString(), runner = 'github-hosted' }) {
  const proof = { schema: 'otziv-anonymous-download-v1', component: image.component, ...identity,
    runner, startedAt: now(), sourcePublicationSha256: sha256(publicationBytes), result: 'IN_PROGRESS',
    accountCredentialsUsed: false, dockerCredentialHelpersAvailable: false, dockerTransport: 'unix:///var/run/docker.sock' };
  try {
    const publication = JSON.parse(Buffer.from(publicationBytes).toString('utf8'));
    const digest = validatePublication(publication, identity, image, manifestSha256);
    if (requiresKeycloakDependencyProof(image)) {
      const checked = checkKeycloakRuntimeDependencies(await readPublicationArtifact('vulnerabilities.json'), publication.imageId);
      assert.deepEqual(checked, publication.knownRuntimeDependencies, 'anonymous_known_dependencies_changed');
    }
    proof.reference = publication.reference;
    const saved = new Map(), names = new Set();
    assert.ok(Array.isArray(publication.attestationEvidence.artifacts) && publication.attestationEvidence.artifacts.length, 'anonymous_saved_evidence_missing');
    for (const artifact of publication.attestationEvidence.artifacts) {
      assert.match(artifact.file || '', /^registry-(?:index|amd64-(?:manifest|config)|attestation-[0-9]+(?:-payload-[0-9]+)?)\.json$/, 'anonymous_saved_evidence_path');
      assert.ok(!names.has(artifact.file), 'anonymous_saved_evidence_duplicate');
      names.add(artifact.file);
      const bytes = await readPublicationArtifact(artifact.file);
      checkedJson(bytes, artifact);
      saved.set(artifact.digest, bytes);
      if (artifact.file === 'registry-amd64-config.json') assert.equal(artifact.digest, publication.imageId, 'anonymous_saved_image_id_mismatch');
    }
    const original = await verifyRegistryEvidence({ digest, expected,
      read: async (kind, value) => { assert.ok(saved.has(value), 'anonymous_saved_blob_missing'); return saved.get(value); }, retain: async () => {} });
    assert.deepEqual(original, publication.attestationEvidence, 'anonymous_saved_evidence_record_mismatch');
    const ready = await docker(['info', '--format', '{{json .ServerVersion}}']);
    assert.equal(ready.code, 0, 'anonymous_local_daemon_unavailable');
    const listed = await docker(['image', 'ls', '--all', '--quiet', '--no-trunc']);
    assert.equal(listed.code, 0, 'anonymous_local_image_inventory_failed');
    await retain('before-image-ids.txt', Buffer.from(listed.stdout));
    assert.ok(!listed.stdout.split(/\s+/).includes(publication.imageId), 'anonymous_candidate_image_already_present');
    const absent = await docker(['image', 'inspect', publication.reference]);
    await retain('before-reference-inspect.txt', Buffer.from(absent.stdout + absent.stderr));
    assert.equal(absent.code, 1, 'anonymous_candidate_reference_already_present_or_inspection_failed');
    proof.imageAbsentBeforePull = true;
    proof.daemonVersion = ready.stdout.trim();
    const anonymous = await verifyRegistryEvidence({ digest, expected, read: readAnonymous, retain });
    assert.deepEqual(anonymous, original, 'anonymous_registry_evidence_changed');
    proof.attestationEvidence = anonymous;
    const pulled = await docker(['pull', '--platform', 'linux/amd64', publication.reference]);
    proof.pullExitCode = pulled.code;
    await retain('anonymous-pull.stdout.log', Buffer.from(pulled.stdout));
    await retain('anonymous-pull.stderr.log', Buffer.from(pulled.stderr));
    assert.equal(pulled.code, 0, 'anonymous_digest_pull_failed');
    const inspected = await docker(['image', 'inspect', publication.reference]);
    await retain('anonymous-image-inspect.json', Buffer.from(inspected.stdout));
    assert.equal(inspected.code, 0, 'anonymous_pulled_image_inspection_failed');
    const actual = assertPulledImage(JSON.parse(inspected.stdout), publication);
    proof.imageId = actual.Id;
    proof.platform = actual.Os + '/' + actual.Architecture;
    proof.labels = actual.Config.Labels;
    proof.publicDownloadReadiness = 'VERIFIED_ANONYMOUS_DIGEST_PULL';
    proof.result = 'PASS';
    return proof;
  } catch (error) {
    proof.result = 'FAIL';
    proof.publicDownloadReadiness = 'NOT_VERIFIED';
    proof.error = /^[a-z][a-z0-9_]+/.exec(error.message || '')?.[0] || 'anonymous_verification_failed';
    throw error;
  } finally {
    proof.finishedAt = now();
    await retain('anonymous-download.json', json(proof));
  }
}

async function executeDocker(executable, args, environment) {
  return new Promise((accept, reject) => {
    const child = spawn(executable, args, { env: environment, stdio: ['ignore', 'pipe', 'pipe'], shell: false, windowsHide: true });
    const output = { stdout: '', stderr: '' };
    let length = 0, stopped = false;
    const timer = setTimeout(() => { stopped = true; child.kill('SIGKILL'); }, 15 * 60_000);
    for (const name of ['stdout', 'stderr']) {
      child[name].setEncoding('utf8');
      child[name].on('data', chunk => {
        length += Buffer.byteLength(chunk);
        if (length > 8 * 1024 * 1024) { stopped = true; child.kill('SIGKILL'); }
        else output[name] += chunk;
      });
    }
    child.once('error', () => { clearTimeout(timer); reject(new Error('anonymous_docker_start_failed')); });
    child.once('close', code => { clearTimeout(timer); accept({ ...output, code: stopped || code === null ? -1 : code }); });
  });
}

function inside(parent, child) {
  const path = relative(parent, child);
  return path && path !== '..' && !path.startsWith('..' + sep) && !isAbsolute(path);
}

export async function runAnonymousCommand(component, inputArgument, outputArgument, {
  environment = process.env, platform = process.platform, sourceRoot = ROOT, execute = run,
  verify = verifyAnonymousDownload, executeDockerImpl = executeDocker,
} = {}) {
  // Establish the fixed, safe artifact destination before other preflight
  // checks, so rejected credentials or a missing downloaded input leave proof.
  const temporary = await realpath(environment.RUNNER_TEMP);
  const output = resolve(temporary, 'anonymous-download');
  await mkdir(output, { recursive: true });
  assert.ok(inside(temporary, await realpath(output)), 'anonymous_output_symlink_escaped');
  const retain = (name, bytes) => writeFile(resolve(output, name), bytes);
  let config, identity;
  try {
    assert.equal(resolve(outputArgument), output, 'anonymous_fixed_output_directory_required');
    identity = anonymousIdentity(environment);
    assert.equal(platform, 'linux', 'anonymous_requires_actual_linux_runner');
    const input = await realpath(resolve(inputArgument));
    assert.ok(inside(temporary, input) && !inside(input, output) && !inside(output, input) && input !== output, 'anonymous_artifact_directories_invalid');
    assert.equal((await execute('git', ['rev-parse', 'HEAD'])).trim(), identity.commit, 'anonymous_checkout_revision_changed');
    const manifestBytes = await readFile(resolve(sourceRoot, 'infrastructure/runtime-security/reviewed-images.json'));
    const image = validateManifest(JSON.parse(manifestBytes)).find(item => item.component === component);
    assert.ok(image, 'anonymous_unreviewed_component');
    const dockerfileSha256 = sha256((await readFile(resolve(sourceRoot, image.dockerfile), 'utf8')).replaceAll('\r\n', '\n'));
    assert.equal(dockerfileSha256, image.dockerfileSha256, 'anonymous_reviewed_dockerfile_changed');
    config = await mkdtemp(join(temporary, 'otziv-anonymous-docker-'));
    const emptyPath = join(config, 'empty-path');
    await mkdir(emptyPath);
    await writeFile(join(config, 'config.json'), '{"auths":{}}\n', { flag: 'wx', mode: 0o600 });
    const dockerExecutable = await realpath((await execute('/usr/bin/which', ['docker'])).trim());
    assert.ok(isAbsolute(dockerExecutable), 'anonymous_docker_executable_missing');
    const dockerEnvironment = anonymousDockerEnvironment(config, emptyPath);
    await retain('docker-client-isolation.json', json({ config: { auths: {} }, credentialHelpers: 'unavailable-empty-PATH', host: 'unix:///var/run/docker.sock' }));
    const readInput = async name => {
      const file = await realpath(join(input, name));
      assert.ok(inside(input, file), 'anonymous_input_symlink_escaped');
      return readFile(file);
    };
    await verify({ publicationBytes: await readInput('publication.json'), identity, image,
      manifestSha256: sha256(manifestBytes),
      expected: { source: SOURCE_REPOSITORY, commit: identity.commit, context: image.context, dockerfile: image.dockerfile, dockerfileSha256 },
      readPublicationArtifact: readInput,
      readAnonymous: createRegistryReader(),
      docker: args => executeDockerImpl(dockerExecutable, ['--config', config, '--host', 'unix:///var/run/docker.sock', ...args], dockerEnvironment),
      retain, runner: environment.RUNNER_NAME || 'github-hosted' });
  } catch (error) {
    await retain('anonymous-job-failure.json', json({ schema: 'otziv-anonymous-job-failure-v1', ...(identity || {}),
      result: 'FAIL', error: /^[a-z][a-z0-9_]+/.exec(error.message || '')?.[0] || 'anonymous_verification_failed' }));
    throw error;
  } finally {
    if (config) {
      const actual = await realpath(config);
      assert.ok(inside(temporary, actual) && actual === config && relative(temporary, actual).startsWith('otziv-anonymous-docker-'), 'anonymous_cleanup_path_escaped');
      await rm(actual, { recursive: true, force: true });
    }
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  assert.ok(process.argv[2] && process.argv[3] && process.argv[4], 'anonymous_arguments_missing');
  await runAnonymousCommand(process.argv[2], process.argv[3], process.argv[4]);
}
