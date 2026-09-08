import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { access, mkdir, mkdtemp, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { BUILDKIT, REPOSITORY, SBOM_GENERATOR } from './publish-reviewed-images.mjs';
import { SOURCE_REPOSITORY } from './registry-evidence.mjs';
import { anonymousDockerEnvironment, anonymousIdentity, assertPulledImage, runAnonymousCommand, validatePublication, verifyAnonymousDownload } from './verify-anonymous-download.mjs';

const directory = new URL('./fixtures/provenance-scratch/', import.meta.url);
const fixture = JSON.parse(await readFile(new URL('fixture.json', directory)));
const files = new Map(await Promise.all(fixture.result.artifacts.map(async item => [item.file, await readFile(new URL(item.file, directory))])));
const blobs = new Map(fixture.result.artifacts.map(item => [item.digest, files.get(item.file)]));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const identity = { commit: fixture.expected.commit, run: '123', attempt: '1' };
const image = { component: 'nginx', sourceBeforeRef: 'example.invalid/base@sha256:' + 'a'.repeat(64), dockerfileSha256: fixture.expected.dockerfileSha256 };
const manifestSha256 = hash('benign reviewed manifest fixture');
function record() {
  return { schema: 'otziv-reviewed-image-publication-v1', ...identity, component: image.component, platform: 'linux/amd64',
    productionActivated: false, sourceBeforeRef: image.sourceBeforeRef, dockerfileSha256: image.dockerfileSha256, manifestSha256,
    builder: BUILDKIT, sbomGenerator: SBOM_GENERATOR, imageId: fixture.result.artifacts.find(item => item.file === 'registry-amd64-config.json').digest,
    tag: `${REPOSITORY}:${image.component}-${identity.commit}-${identity.run}-${identity.attempt}`,
    reference: REPOSITORY + '@' + fixture.indexDigest, result: 'PASS', security: { result: 'PASS' },
    attestationEvidence: structuredClone(fixture.result) };
}
function inspected(publication) {
  return [{ Id: publication.imageId, Os: 'linux', Architecture: 'amd64', RepoDigests: [publication.reference], Config: { Labels: {
    'com.otziv.publication.source': SOURCE_REPOSITORY, 'com.otziv.publication.revision': publication.commit,
    'com.otziv.reviewed-component': publication.component,
  } } }];
}
const environment = () => ({ GITHUB_ACTIONS: 'true', GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_REPOSITORY: 'Claidd/otziv_o',
  OTZIV_VERIFY_ANONYMOUS_DOWNLOAD: 'true', RUNNER_ENVIRONMENT: 'github-hosted', RUNNER_OS: 'Linux',
  GITHUB_SHA: identity.commit, GITHUB_REF: 'refs/heads/codex/review', GITHUB_RUN_ID: identity.run, GITHUB_RUN_ATTEMPT: identity.attempt });

function scenario() {
  const publication = record(), retained = new Map(), calls = [], fetched = [];
  let inspectCalls = 0;
  const input = { publicationBytes: Buffer.from(JSON.stringify(publication)), identity, image, manifestSha256, expected: fixture.expected,
    readPublicationArtifact: async name => files.get(name),
    readAnonymous: async (kind, digest) => { fetched.push({ kind, digest }); return blobs.get(digest); },
    retain: async (name, bytes) => retained.set(name, bytes), now: () => '2026-09-08T00:00:00Z',
    docker: async args => {
      calls.push(args);
      if (args[0] === 'info') return { code: 0, stdout: '"fixture-server"\n', stderr: '' };
      if (args[0] === 'image' && args[1] === 'ls') return { code: 0, stdout: '', stderr: '' };
      if (args[0] === 'image' && args[1] === 'inspect') return ++inspectCalls === 1
        ? { code: 1, stdout: '', stderr: 'No such image: benign fixture\n' }
        : { code: 0, stdout: JSON.stringify(inspected(publication)), stderr: '' };
      if (args[0] === 'pull') return { code: 0, stdout: 'Benign fixture pull completed\n', stderr: '' };
      throw new Error('unexpected_test_docker_command');
    } };
  return { input, retained, calls, fetched, publication, proof: () => JSON.parse(retained.get('anonymous-download.json')) };
}

test('successful anonymous proof consumes only the immutable publication reference and retains separate evidence', async () => {
  const value = scenario(), original = Buffer.from(value.input.publicationBytes);
  const result = await verifyAnonymousDownload(value.input);
  assert.equal(result.result, 'PASS');
  assert.equal(result.publicDownloadReadiness, 'VERIFIED_ANONYMOUS_DIGEST_PULL');
  assert.equal(result.imageAbsentBeforePull, true);
  assert.equal(result.sourcePublicationSha256, hash(original));
  assert.equal(result.finishedAt, '2026-09-08T00:00:00Z');
  assert.deepEqual(value.input.publicationBytes, original);
  assert.equal(value.fetched.length, fixture.result.artifacts.length);
  assert.deepEqual(value.calls.find(args => args[0] === 'pull'), ['pull', '--platform', 'linux/amd64', value.publication.reference]);
  assert.equal(value.calls.some(args => args.includes(value.publication.tag)), false);
  for (const artifact of fixture.result.artifacts) assert.deepEqual(value.retained.get(artifact.file), files.get(artifact.file));
  assert.deepEqual(value.proof(), result);
});

test('anonymous execution rejects forks, ordinary events, reused runners, credentials and remote Docker', () => {
  assert.deepEqual(anonymousIdentity(environment()), identity);
  for (const [key, value] of Object.entries({ GITHUB_ACTIONS: 'false', GITHUB_EVENT_NAME: 'push', GITHUB_REPOSITORY: 'example/another',
    OTZIV_VERIFY_ANONYMOUS_DOWNLOAD: 'false', RUNNER_ENVIRONMENT: 'self-hosted', RUNNER_OS: 'Windows', GITHUB_SHA: 'main',
    GITHUB_REF: 'refs/pull/1/merge', GITHUB_RUN_ID: '0', GITHUB_RUN_ATTEMPT: '0', GHCR_TOKEN: 'benign-fixture',
    GITHUB_TOKEN: 'benign-fixture', GH_TOKEN: 'benign-fixture', DOCKER_AUTH_CONFIG: '{}', DOCKER_HOST: 'tcp://example.invalid:2376',
    DOCKER_CONTEXT: 'other', DOCKER_CERT_PATH: '/fixture', DOCKER_TLS_VERIFY: '1' })) {
    assert.throws(() => anonymousIdentity({ ...environment(), [key]: value }), undefined, key);
  }
});

test('publication must be PASS for the exact current commit, run, attempt, component and reviewed files', () => {
  assert.equal(validatePublication(record(), identity, image, manifestSha256), fixture.indexDigest);
  for (const change of [
    value => { value.result = 'FAIL'; }, value => { value.security.result = 'FAIL'; },
    value => { value.commit = 'b'.repeat(40); }, value => { value.run = '124'; }, value => { value.attempt = '2'; },
    value => { value.component = 'postgres'; }, value => { value.platform = 'linux/arm64'; }, value => { value.productionActivated = true; },
    value => { value.reference = REPOSITORY + ':latest'; }, value => { value.reference = 'example.invalid/repository@' + fixture.indexDigest; },
    value => { value.tag = 'unrelated'; }, value => { value.manifestSha256 = 'b'.repeat(64); },
    value => { value.dockerfileSha256 = 'b'.repeat(64); }, value => { value.builder = 'moby/buildkit:latest'; },
    value => { value.sbomGenerator = 'scanner:latest'; }, value => { value.sourceBeforeRef = 'base:latest'; },
    value => { delete value.attestationEvidence; }, value => { delete value.imageId; },
  ]) { const value = record(); change(value); assert.throws(() => validatePublication(value, identity, image, manifestSha256)); }
});

test('corrupt saved artifact bytes fail before any Docker or registry request', async () => {
  const value = scenario();
  value.input.readPublicationArtifact = async name => Buffer.concat([files.get(name), Buffer.from('\n')]);
  await assert.rejects(verifyAnonymousDownload(value.input), /registry_blob_digest_mismatch/);
  assert.equal(value.calls.length, 0); assert.equal(value.fetched.length, 0);
  assert.equal(value.proof().result, 'FAIL');
});

test('cached image IDs and existing exact references cannot count as a fresh anonymous pull', async t => {
  for (const target of ['image-id', 'reference']) await t.test(target, async () => {
    const value = scenario(), docker = value.input.docker;
    value.input.docker = async args => {
      if (target === 'image-id' && args[1] === 'ls') return { code: 0, stdout: value.publication.imageId + '\n', stderr: '' };
      if (target === 'reference' && args[1] === 'inspect') return { code: 0, stdout: '[]', stderr: '' };
      return docker(args);
    };
    await assert.rejects(verifyAnonymousDownload(value.input), /anonymous_candidate_/);
    assert.equal(value.fetched.length, 0);
    assert.equal(value.calls.some(args => args[0] === 'pull'), false);
    assert.equal(value.proof().result, 'FAIL');
  });
});

test('private package or damaged anonymous evidence fails before pull with honest retained status', async t => {
  for (const target of ['private', 'damaged']) await t.test(target, async () => {
    const value = scenario();
    value.input.readAnonymous = async (kind, digest) => {
      if (target === 'private') throw new Error('registry_read_failed');
      return Buffer.concat([blobs.get(digest), Buffer.from('\n')]);
    };
    await assert.rejects(verifyAnonymousDownload(value.input), target === 'private' ? /registry_read_failed/ : /registry_blob_digest_mismatch/);
    assert.equal(value.calls.some(args => args[0] === 'pull'), false);
    assert.equal(value.proof().publicDownloadReadiness, 'NOT_VERIFIED');
    assert.equal(value.proof().result, 'FAIL');
  });
});

test('failed digest pull preserves both output streams and exit status without claiming success', async () => {
  const value = scenario(), docker = value.input.docker;
  value.input.docker = args => args[0] === 'pull' ? Promise.resolve({ code: 1, stdout: 'fixture partial output', stderr: 'fixture denied' }) : docker(args);
  await assert.rejects(verifyAnonymousDownload(value.input), /anonymous_digest_pull_failed/);
  assert.equal(value.retained.get('anonymous-pull.stdout.log').toString(), 'fixture partial output');
  assert.equal(value.retained.get('anonymous-pull.stderr.log').toString(), 'fixture denied');
  assert.equal(value.proof().pullExitCode, 1);
  assert.equal(value.proof().result, 'FAIL');
});

test('pulled image must match its image ID, RepoDigest, platform and each publication label', () => {
  const publication = record();
  assert.equal(assertPulledImage(inspected(publication), publication).Id, publication.imageId);
  for (const change of [
    value => { value[0].Id = 'sha256:' + 'b'.repeat(64); }, value => { value[0].RepoDigests = []; },
    value => { value[0].Os = 'windows'; }, value => { value[0].Architecture = 'arm64'; },
    ...['com.otziv.publication.source', 'com.otziv.publication.revision', 'com.otziv.reviewed-component'].map(key => value => { value[0].Config.Labels[key] = 'different'; }),
  ]) { const value = inspected(publication); change(value); assert.throws(() => assertPulledImage(value, publication)); }
});

test('isolated Docker environment exposes only owned config and empty executable-search directory', () => {
  assert.deepEqual(anonymousDockerEnvironment('/fixture/config', '/fixture/config/empty-path'), {
    HOME: '/fixture/config', DOCKER_CONFIG: '/fixture/config', PATH: '/fixture/config/empty-path', LANG: 'C.UTF-8',
  });
});

async function removeFixture(path) {
  const actual = await realpath(path), parent = await realpath(tmpdir());
  assert.ok(actual.startsWith(resolve(parent) + sep) && actual.includes('otziv-anonymous-command-'));
  await rm(actual, { recursive: true, force: true });
}

test('CLI credential and missing-input preflight failures retain a safe failure artifact', async t => {
  for (const failure of ['credential', 'missing-input']) await t.test(failure, async () => {
    const root = await mkdtemp(join(tmpdir(), 'otziv-anonymous-command-'));
    try {
      const env = { ...environment(), RUNNER_TEMP: root };
      if (failure === 'credential') env.GHCR_TOKEN = 'benign-test-value';
      await assert.rejects(runAnonymousCommand('nginx', join(root, 'missing-input'), join(root, 'anonymous-download'), {
        environment: env, platform: 'linux',
        execute: async () => { throw new Error('unexpected_subprocess'); },
        verify: async () => { throw new Error('unexpected_registry_verifier'); },
      }));
      const bytes = await readFile(join(root, 'anonymous-download/anonymous-job-failure.json'), 'utf8');
      assert.equal(JSON.parse(bytes).result, 'FAIL');
      assert.equal(bytes.includes('benign-test-value'), false);
    } finally { await removeFixture(root); }
  });
});

test('CLI wires the absolute Docker executable, blank config, empty PATH and local socket and cleans only its config', async () => {
  const root = await mkdtemp(join(tmpdir(), 'otziv-anonymous-command-'));
  try {
    const input = join(root, 'input'), source = join(root, 'source'), executable = join(root, 'never-executed-docker-fixture');
    await mkdir(input); await mkdir(join(source, 'infrastructure/runtime-security/builds'), { recursive: true });
    const baselineBytes = await readFile(new URL('./reviewed-images.json', import.meta.url));
    const reviewedImage = JSON.parse(baselineBytes).images.find(item => item.component === 'nginx');
    const dockerfile = await readFile(new URL('../../' + reviewedImage.dockerfile, import.meta.url));
    await mkdir(join(source, reviewedImage.context), { recursive: true });
    await writeFile(join(source, reviewedImage.dockerfile), dockerfile);
    await writeFile(join(source, 'infrastructure/runtime-security/reviewed-images.json'), baselineBytes);
    await writeFile(join(input, 'publication.json'), '{}');
    await writeFile(executable, 'Benign file; injected test executor never runs this.');
    let ownedConfig, called = false;
    await runAnonymousCommand('nginx', input, join(root, 'anonymous-download'), {
      environment: { ...environment(), RUNNER_TEMP: root }, platform: 'linux', sourceRoot: source,
      execute: async (command, args) => {
        if (command === 'git') { assert.deepEqual(args, ['rev-parse', 'HEAD']); return identity.commit; }
        assert.equal(command, '/usr/bin/which'); assert.deepEqual(args, ['docker']); return executable;
      },
      verify: async options => {
        assert.deepEqual(options.identity, identity);
        assert.equal(options.expected.source, SOURCE_REPOSITORY);
        assert.equal(typeof options.readAnonymous, 'function');
        assert.deepEqual(await options.docker(['info']), { code: 0, stdout: 'fixture', stderr: '' });
      },
      executeDockerImpl: async (binary, args, env) => {
        called = true; ownedConfig = args[1];
        assert.equal(binary, await realpath(executable));
        assert.deepEqual(args, ['--config', ownedConfig, '--host', 'unix:///var/run/docker.sock', 'info']);
        assert.deepEqual(JSON.parse(await readFile(join(ownedConfig, 'config.json'))), { auths: {} });
        assert.deepEqual(env, { HOME: ownedConfig, DOCKER_CONFIG: ownedConfig, PATH: join(ownedConfig, 'empty-path'), LANG: 'C.UTF-8' });
        assert.deepEqual(await readdir(env.PATH), []);
        return { code: 0, stdout: 'fixture', stderr: '' };
      },
    });
    assert.equal(called, true);
    await assert.rejects(access(ownedConfig));
    assert.equal(await readFile(executable, 'utf8'), 'Benign file; injected test executor never runs this.');
  } finally { await removeFixture(root); }
});

test('workflow waits for all publications and retains same-run component proofs after an unrelated publication failure', async () => {
  const workflow = await readFile(new URL('../../.github/workflows/quality-gates.yml', import.meta.url), 'utf8');
  const job = workflow.split('  reviewed-image-anonymous-download:')[1]?.split('  repository-contracts:')[0];
  assert.ok(job);
  assert.match(job, /needs: \[reviewed-image-inventory, reviewed-image-publication\]/);
  assert.match(job, /!cancelled\(\)/);
  assert.match(job, /needs\.reviewed-image-inventory\.result == 'success'/);
  assert.match(job, /\(needs\.reviewed-image-publication\.result == 'success' \|\| needs\.reviewed-image-publication\.result == 'failure'\)/);
  assert.match(job, /inputs\.publish-reviewed-images == true/);
  assert.match(job, /runs-on: ubuntu-24\.04/);
  assert.match(job, /matrix: \$\{\{ fromJSON\(needs\.reviewed-image-inventory\.outputs\.matrix\) \}\}/);
  assert.match(job, /persist-credentials: false/);
  assert.match(job, /actions\/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c/);
  assert.ok(job.includes('name: reviewed-publication-${{ matrix.component }}'));
  assert.ok(job.includes('name: anonymous-download-${{ matrix.component }}'));
  assert.doesNotMatch(job, /github-token:|run-id:|packages:|docker login|GHCR_TOKEN|GITHUB_TOKEN/);
});
