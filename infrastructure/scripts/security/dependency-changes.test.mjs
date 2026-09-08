import test from 'node:test';
import assert from 'node:assert/strict';
import { auditNeeded, gatePassed } from './dependency-changes.mjs';
test('dependency gate is present for every PR and audits every relevant graph/toolchain change', () => {
  for (const path of ['frontend/package-lock.json', 'mobile/.npmrc', 'shared/client-common/auth.js',
    'backend/.mvn/wrapper/maven-wrapper.properties', 'backend/pom.xml', 'infrastructure/browser-smoke/package-lock.json',
    'backend/build-support/audit.py', 'backend/build-support/site-plugin/src/main/java/SiteRunMojo.java',
    'backend/build-support/plugin-policy.json', 'infrastructure/runtime-security/maven-false-positives.xml'])
    assert.equal(auditNeeded([path], 'pull_request'), true);
  assert.equal(auditNeeded(['docs/README.md'], 'pull_request'), false);
  assert.equal(auditNeeded([], 'schedule'), true); assert.equal(auditNeeded([], 'push', false), true);
});
test('aggregate rejects failures, cancellation and unexpected skips rather than silently passing', () => {
  assert.equal(gatePassed('true', 'success', 'success', 'success'), true);
  assert.equal(gatePassed('false', 'success', 'skipped', 'skipped'), true);
  for (const result of ['failure', 'cancelled', 'skipped']) assert.equal(gatePassed('true', 'success', result, 'success'), false);
  assert.equal(gatePassed('false', 'failure', 'skipped', 'skipped'), false);
  assert.equal(gatePassed('unknown', 'success', 'skipped', 'skipped'), false);
});
