import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { validateReleaseImages } from './monitoring-release.mjs';

const image = 'registry.example.invalid/otziv/candidate@sha256:' + 'a'.repeat(64);
const components = ['PROMETHEUS', 'LOKI', 'ALLOY', 'TEMPO', 'GRAFANA'];
const valid = () => Object.fromEntries(components.map(name => [`OTZIV_${name}_SECURITY_IMAGE`, image]));
test('all five immutable images are required and unrelated environment values never enter output', () => {
  const values = valid(); values.DATABASE_PASSWORD = randomBytes(24).toString('hex');
  assert.equal(Object.keys(validateReleaseImages(values)).length, 5);
  assert.ok(!JSON.stringify(validateReleaseImages(values)).includes(values.DATABASE_PASSWORD));
  for (const name of Object.keys(valid())) {
    const missing = valid(); delete missing[name]; assert.throws(() => validateReleaseImages(missing), /immutable_image_required/);
  }
});
test('mutable tags, local-only image IDs, whitespace and shell-shaped references fail without echoing values', () => {
  for (const component of components) for (const value of ['repo:latest', 'sha256:' + 'a'.repeat(64), image + '\n', image + ' --privileged', '$(private-value)', '', null]) {
    const values = valid(); values[`OTZIV_${component}_SECURITY_IMAGE`] = value;
    assert.throws(() => validateReleaseImages(values), error => error.message === `${component.toLowerCase()}_published_immutable_image_required`);
  }
});
test('opt-in override contains only the five reviewed services and executable HTTP readiness commands', async () => {
  const text = await readFile(new URL('../../compose.monitoring-security-upgrade.yaml', import.meta.url), 'utf8');
  assert.deepEqual([...text.matchAll(/^  ([a-z]+):$/gm)].map(match => match[1]), ['prometheus', 'loki', 'alloy', 'tempo', 'grafana']);
  for (const component of components) assert.ok(text.includes('${OTZIV_' + component + '_SECURITY_IMAGE:?'));
  assert.ok(text.includes('["CMD", "/usr/bin/http-ready", "http://127.0.0.1:3100/ready"]'));
  assert.ok(text.includes('["CMD", "/usr/bin/http-ready", "http://127.0.0.1:3200/ready"]'));
  assert.match(text, /tempo:[\s\S]*stop_grace_period: 30s/);
  assert.ok(!text.includes('CMD-SHELL'));
});
