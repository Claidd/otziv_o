import test from 'node:test';
import assert from 'node:assert/strict';
import {changedPaths, scopes, selectChecks} from './ci-changes.mjs';

test('a UI change does not rebuild monitoring or run the backend suite', () => {
  const {checks} = selectChecks(['frontend/src/app/card.ts'], 'pull_request');
  assert.equal(checks.frontend, true); assert.equal(checks.browser, true);
  assert.equal(checks.backend, false); assert.equal(checks.monitoring, false);
});
test('backend changes retain real authentication, database and client contract checks', () => {
  const {checks} = selectChecks(['backend/src/main/java/Order.java'], 'push');
  for (const scope of ['backend', 'issuer', 'browser', 'parity']) assert.equal(checks[scope], true);
  assert.equal(checks.monitoring, false);
  assert.equal(checks.infrastructure, false);
});
test('shared code expands to both clients and their consumers', () => {
  const {checks} = selectChecks(['shared/client-common/src/copy.ts'], 'pull_request');
  for (const scope of ['backend', 'frontend', 'mobile', 'android', 'browser', 'parity', 'whatsapp', 'worker']) assert.equal(checks[scope], true);
});
test('policy, infrastructure, unknown paths, manual runs and missing history fail closed', () => {
  for (const [paths, event, usable] of [[['.github/workflows/quality-gates.yml'], 'push', true],
    [['compose.yaml'], 'push', true], [['infrastructure/runtime-security/scan.mjs'], 'push', true],
    [['new-service/main.go'], 'push', true], [['docs/release-helper.py'], 'push', true],
    [[], 'workflow_dispatch', true], [[], 'push', false]]) {
    assert.deepEqual(Object.values(selectChecks(paths, event, usable).checks), scopes.map(() => true));
  }
});
test('documentation needs repository/secret checks but no component builds', () => {
  assert.deepEqual(Object.values(selectChecks(['docs/guide.md'], 'pull_request').checks), scopes.map(() => false));
});
test('deleted and renamed code paths remain visible and missing base expands coverage', () => {
  const sha = 'a'.repeat(40), head = 'b'.repeat(40), calls = [];
  const paths = changedPaths({before: sha, after: head}, args => {
    calls.push(args); return args[0] === 'diff' ? 'frontend/old.ts\0backend/new.java\0' : '';
  });
  assert.deepEqual(paths, ['frontend/old.ts', 'backend/new.java']);
  assert.ok(calls.at(-1).includes('--no-renames'));
  assert.equal(changedPaths({before: sha, after: head}, () => { throw Error('history missing'); }), null);
  assert.equal(changedPaths({before: '0'.repeat(40), after: head}), null);
});
test('PR selection uses its merge base, not only the last commit', () => {
  const base = 'a'.repeat(40), head = 'b'.repeat(40), ancestor = 'c'.repeat(40), calls = [];
  changedPaths({pull_request: {base: {sha: base}, head: {sha: head}}}, args => {
    calls.push(args); return args[0] === 'merge-base' ? ancestor + '\n' : '';
  });
  assert.deepEqual(calls.at(-1).slice(-2), [ancestor, head]);
});
