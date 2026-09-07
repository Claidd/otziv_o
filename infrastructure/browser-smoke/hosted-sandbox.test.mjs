import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { hostedPaths, profileText, assertRendererSandbox, removeOwnedProfile } from './hosted-sandbox.mjs';

const env = { GITHUB_ACTIONS: 'true', RUNNER_OS: 'Linux', GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '1', HOME: '/home/runner', RUNNER_TEMP: '/home/runner/work/_temp' };
const paths = hostedPaths(env, '1228');
const healthy = { arguments: ['/exact/chrome', '--headless'], status: 'NoNewPrivs:\t1\nSeccomp:\t2\n', uidMap: '0 1001 1\n', profile: `${paths.name} (unconfined)` };

test('profile grants userns only to the exact pinned executable without wildcard or host-wide change', () => {
  const text = profileText(paths);
  assert.ok(text.includes(`"${paths.executable}" flags=(default_allow)`));
  assert.match(text, /\{\n  userns,\n\}/);
  assert.doesNotMatch(text, /\*|sysctl|capability|unconfined/);
});

test('non-hosted contexts and injected, wildcard, traversal or overridden paths are rejected before privileged commands', () => {
  for (const delta of [{ GITHUB_ACTIONS: 'false' }, { RUNNER_OS: 'Windows' }, { GITHUB_RUN_ID: '1;id' }, { GITHUB_RUN_ATTEMPT: '' }, { HOME: '/home/runner/**' }, { HOME: '/home/runner/../other' }, { HOME: '/home/runner" {}' }, { RUNNER_TEMP: 'relative' }, { PLAYWRIGHT_BROWSERS_PATH: '/tmp/browser' }]) {
    assert.throws(() => hostedPaths({ ...env, ...delta }, '1228'));
  }
  assert.throws(() => hostedPaths(env, '../1228'));
});

test('actual sandbox proof rejects disabled sandbox flags, host UID map, missing seccomp or wrong profile', () => {
  assert.doesNotThrow(() => assertRendererSandbox(healthy, paths.name));
  for (const flag of ['--no-sandbox', '--disable-setuid-sandbox', '--disable-seccomp-filter-sandbox', '--disable-namespace-sandbox=true']) {
    assert.throws(() => assertRendererSandbox({ ...healthy, arguments: [...healthy.arguments, flag] }, paths.name));
  }
  for (const delta of [{ status: 'NoNewPrivs:\t0\nSeccomp:\t2\n' }, { status: 'NoNewPrivs:\t1\nSeccomp:\t0\n' }, { uidMap: '0 0 4294967295\n' }, { profile: 'unconfined' }]) {
    assert.throws(() => assertRendererSandbox({ ...healthy, ...delta }, paths.name));
  }
});

function cleanupFixture({ changed = false, foreign = false, loaded = true, failure = false } = {}) {
  const text = profileText(paths);
  const calls = [];
  const files = new Map([[paths.state, JSON.stringify({ profile: foreign ? '/etc/apparmor.d/foreign' : paths.profile, sha256: createHash('sha256').update(text).digest('hex') })]]);
  if (loaded) files.set(paths.profile, changed ? text + '# changed' : text);
  const io = { readFile: async p => { if (!files.has(p)) throw Object.assign(new Error('missing'), { code: 'ENOENT' }); return files.get(p); }, rm: async p => { calls.push(['rm-local', p]); files.delete(p); } };
  const run = args => { calls.push(args); if (failure) throw new Error('unload failed'); };
  return { calls, io, run };
}

test('cleanup unloads only the exact owned profile then removes exact temporary files', async () => {
  const fixture = cleanupFixture();
  assert.equal(await removeOwnedProfile(paths, fixture.io, fixture.run), 'PASS');
  assert.deepEqual(fixture.calls, [['apparmor_parser', '-R', paths.profile], ['rm', '--', paths.profile], ['rm-local', paths.input], ['rm-local', paths.state]]);
  assert.equal(await removeOwnedProfile(paths, fixture.io, fixture.run), 'NO_OWNED_PROFILE');
});

test('cleanup refuses modified or foreign profile and retains evidence when unload fails', async () => {
  for (const options of [{ changed: true }, { foreign: true }, { failure: true }]) {
    const fixture = cleanupFixture(options);
    await assert.rejects(removeOwnedProfile(paths, fixture.io, fixture.run));
    assert.ok(!fixture.calls.some(call => call[0] === 'rm' || call[0] === 'rm-local'));
  }
});

test('failed installation without a root profile still removes only owned temporary files', async () => {
  const fixture = cleanupFixture({ loaded: false });
  assert.equal(await removeOwnedProfile(paths, fixture.io, fixture.run), 'PASS');
  assert.deepEqual(fixture.calls, [['rm-local', paths.input], ['rm-local', paths.state]]);
});
