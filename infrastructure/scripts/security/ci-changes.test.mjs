import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, readFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
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

test('unchanged-component summaries run without a checkout or component directory', () => {
  const workflow = readFileSync(new URL('../../../.github/workflows/quality-gates.yml', import.meta.url), 'utf8');
  const scratch = mkdtempSync(join(tmpdir(), 'otziv-no-checkout-'));
  let exercised = 0;
  try {
    for (const job of workflow.split(/^  [\w-]+:\r?$/m).slice(1)) {
      const defaults = job.match(/    defaults:\r?\n      run:\r?\n        working-directory: (.+)/)?.[1]?.trim();
      for (const step of job.split(/^      - /m).slice(1)) {
        if (!step.startsWith('name: Record unchanged component')) continue;
        const directory = step.match(/^        working-directory: (.+)$/m)?.[1]?.trim() ?? defaults ?? '${{ github.workspace }}';
        const cwd = directory.replace('${{ runner.temp }}', scratch)
          .replace('${{ github.workspace }}', join(scratch, 'workspace-without-checkout'));
        const script = step.match(/^        run: (.+)$/m)?.[1]?.trim();
        assert.ok(script, 'summary step must have a runnable command');
        const summary = join(scratch, `summary-${exercised++}.md`);
        const shell = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : '/bin/bash';
        const result = spawnSync(shell, ['-e', '-c', script], {
          cwd, env: {...process.env, GITHUB_STEP_SUMMARY: summary}, encoding: 'utf8',
        });
        assert.equal(result.status, 0, `summary cannot depend on skipped checkout: ${directory}; ${result.error ?? result.stderr}`);
        assert.match(readFileSync(summary, 'utf8'), /Component unchanged/);
      }
    }
    assert.ok(exercised >= 12, 'all conditional jobs must be exercised');
  } finally {
    rmSync(scratch, {recursive: true, force: true});
  }
});
