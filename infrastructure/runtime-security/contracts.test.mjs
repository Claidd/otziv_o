import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { containerBounds } from './container-smoke.mjs';
import { summarizeReport, TRIVY_IMAGE } from './scan.mjs';

test('Chromium profile denies by default, adds namespace syscalls without host capabilities', async () => {
  const profile = JSON.parse(await readFile(new URL('./chromium-seccomp.json', import.meta.url), 'utf8'));
  assert.equal(profile.defaultAction, 'SCMP_ACT_ERRNO');
  const userNamespace = profile.syscalls[0];
  assert.deepEqual(userNamespace.names, ['clone', 'setns', 'unshare', 'chroot']);
  assert.equal(userNamespace.action, 'SCMP_ACT_ALLOW');
  for (const forbidden of ['bpf', 'mount', 'sethostname', 'kexec_load']) {
    assert.equal(profile.syscalls.some(rule => rule.action === 'SCMP_ACT_ALLOW' && rule.names.includes(forbidden) &&
      !rule.includes?.caps?.length), false, forbidden);
  }
  const bounds = containerBounds('/fixture/profile.json');
  assert.equal(bounds[bounds.indexOf('--cap-drop') + 1], 'ALL');
  assert.ok(bounds.includes('no-new-privileges:true')); assert.ok(bounds.includes('--read-only'));
  assert.ok(bounds.some(value => value.includes('noexec,nosuid')));
  assert.equal(bounds.some(value => /SYS_ADMIN|unconfined|privileged|no-sandbox/.test(value)), false);
});

test('every Playwright launch explicitly enables sandbox and readiness verifies actual arguments', async () => {
  for (const file of ['index.js', 'runtime-smoke.js', 'chromium-smoke.js']) {
    const text = await readFile(new URL(`../../backend/external-review-worker/src/${file}`, import.meta.url), 'utf8');
    const calls = [...text.matchAll(/chromium\.launch\(\{([\s\S]*?)\}\)/g)];
    assert.ok(calls.length);
    for (const call of calls) assert.match(call[1], /chromiumSandbox:\s*true/);
  }
  const readiness = await readFile(new URL('../../backend/external-review-worker/src/runtime-readiness.js', import.meta.url), 'utf8');
  assert.ok(readiness.includes('Browser.getBrowserCommandLine'));
});

test('high/critical findings include unfixed CVEs and empty/unsupported scans do not pass', () => {
  assert.match(TRIVY_IMAGE, /^aquasec\/trivy@sha256:[a-f0-9]{64}$/);
  const summary = summarizeReport({ Results: [{ Type: 'jar', Vulnerabilities: [
    { Severity: 'HIGH', FixedVersion: '2' }, { Severity: 'CRITICAL' }, { Severity: 'MEDIUM' }
  ] }] }, true);
  assert.equal(summary.high, 1); assert.equal(summary.critical, 1); assert.equal(summary.unfixedHighOrCritical, 1);
  assert.equal(summary.blockingFixedHighOrCritical, 1);
  assert.throws(() => summarizeReport({ Results: [] }));
  assert.throws(() => summarizeReport({ Results: [{ Type: 'debian' }] }, true));
});

test('production rollout drain is longer than maximum task grace and observer is isolated', async () => {
  const compose = await readFile(new URL('../../docker-compose.yaml', import.meta.url), 'utf8');
  const service = name => compose.match(new RegExp(`^  ${name}:\\r?\\n([\\s\\S]*?)(?=^  [a-zA-Z0-9_-]+:|(?![\\s\\S]))`, 'm'))?.[1] || '';
  assert.match(service('docker-observer'), /docker_observer_net/);
  for (const name of ['dozzle', 'alloy']) {
    assert.ok(!service(name).includes('/var/run/docker.sock'), `${name} must not receive Docker socket`);
  }
  const worker = service('external-review-worker');
  assert.match(worker, /stop_grace_period:\s*400s/);
  assert.ok(worker.includes('chromium-seccomp.json'));
  const whatsappServices = [...compose.matchAll(/^  (whatsapp(?:[-_][\w-]+)?):\r?\n/gm)].map(match => match[1]);
  assert.ok(whatsappServices.length);
  for (const name of whatsappServices) { assert.match(service(name), /stop_grace_period:\s*330s/); assert.ok(service(name).includes('chromium-seccomp.json')); }
});
