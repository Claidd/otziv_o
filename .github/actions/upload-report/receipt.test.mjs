import assert from 'node:assert/strict';
import {mkdtempSync, readFileSync, writeFileSync, rmSync, realpathSync, lstatSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, dirname, basename} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {settings, acceptUpload} from './receipt.mjs';

const directory = dirname(fileURLToPath(import.meta.url));
const action = readFileSync(join(directory, 'action.yml'), 'utf8');
const helper = join(directory, 'receipt.mjs');
const pin = 'actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a';
// Read the real composite's bounded step/condition subset. Unknown expressions
// fail the test rather than silently obtaining permissive runner semantics.
const steps = action.split(/^    - name: /m).slice(1).map(block => ({
  block, name: block.split('\n')[0], id: block.match(/^      id: (.+)$/m)?.[1],
  condition: block.match(/^      if: \$\{\{ (.+) \}\}$/m)?.[1],
  uses: block.match(/^      uses: ([^ ]+)/m)?.[1],
  run: block.match(/^      run: (.+)$/m)?.[1],
}));
function condition(expression, state, cancelled, previousFailure) {
  if (!expression) return !previousFailure;
  return expression.split(' && ').every(term => {
    if (term === '!cancelled()') return !cancelled;
    if (term === 'always()') return true;
    const match = term.match(/^steps\.([a-z0-9_]+)\.outcome == '(success|failure)'$/);
    assert.ok(match, `Unsupported actual action condition: ${term}`);
    return state[match[1]]?.outcome === match[2];
  });
}
function decodeOutputs(path) {
  return Object.fromEntries(readFileSync(path, 'utf8').trim().split('\n').filter(Boolean).map(line => {
    const equals = line.indexOf('=');
    return [line.slice(0, equals), line.slice(equals + 1)];
  }));
}
const uploaded = number => ({outcome: 'success', outputs: {
  'artifact-id': String(1000 + number),
  'artifact-url': `https://github.com/example/repo/actions/runs/123/artifacts/${1000 + number}`,
  'artifact-digest': String(number).repeat(64),
}});

function executeActualComposite(plannedUploads, options = {}) {
  const temp = mkdtempSync(join(tmpdir(), 'otziv-report-test-'));
  const ownedPath = realpathSync.native(temp);
  const ownedIdentity = lstatSync(ownedPath);
  try {
    const output = join(temp, 'output');
    const summary = join(temp, 'summary');
    const report = join(temp, 'already-tested-report.json');
    writeFileSync(output, ''); writeFileSync(summary, '');
    const bytes = '{"scanResult":"PASS","buildAlreadyCompleted":true}\n';
    writeFileSync(report, bytes);
    const env = {...process.env, OTZIV_REPORT_NAME: options.name ?? 'upstream-security-test',
      OTZIV_REPORT_RUN_ATTEMPT: options.runAttempt ?? '7',
      OTZIV_REPORT_NO_FILES: options.policy ?? 'error', GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: summary};
    const state = {}, uploads = [], delays = [];
    let cancelled = false, previousFailure = options.earlierFailure === true, accepted;
    for (const step of steps) {
      if (!condition(step.condition, state, cancelled, previousFailure)) {
        state[step.id] = {outcome: 'skipped', outputs: {}};
        continue;
      }
      if (step.uses) {
        assert.equal(step.uses, pin);
        assert.match(step.block, /^      continue-on-error: true$/m);
        assert.match(step.block, /^        overwrite: false$/m);
        const number = Number(step.id.split('_')[1]);
        assert.match(step.block, new RegExp('name: \\$\\{\\{ steps.prepare.outputs.name-' + number + ' \\}\\}'));
        uploads.push({name: state.prepare.outputs[`name-${number}`], bytes: readFileSync(report, 'utf8')});
        assert.ok(plannedUploads[uploads.length - 1], 'Unexpected extra official upload');
        const result = plannedUploads[uploads.length - 1];
        state[step.id] = {...result, conclusion: 'success'}; // Runner continue-on-error semantics.
        if (options.cancelAfter === number) cancelled = true;
      } else if (step.id.startsWith('delay_')) {
        assert.match(step.run, /^sleep (2|5)$/);
        delays.push(Number(step.run.slice(6)));
        const outcome = options.failDelay === step.id ? 'failure' : 'success';
        state[step.id] = {outcome};
        previousFailure ||= outcome === 'failure';
      } else {
        const command = step.id === 'prepare' ? 'prepare' : 'finalize';
        assert.equal(step.run, `node "$OTZIV_REPORT_ACTION_PATH/receipt.mjs" ${command}`);
        writeFileSync(output, '');
        const child = spawnSync(process.execPath, [helper, command], {encoding: 'utf8',
          env: {...env, OTZIV_REPORT_STEPS: JSON.stringify(state), OTZIV_REPORT_CANCELLED: String(cancelled)}});
        assert.ifError(child.error);
        state[step.id] = {outcome: child.status === 0 ? 'success' : 'failure', outputs: decodeOutputs(output)};
        previousFailure ||= child.status !== 0;
        if (command === 'finalize') accepted = {exit: child.status, outputs: state[step.id].outputs, stdout: child.stdout, stderr: child.stderr};
      }
    }
    assert.equal(readFileSync(report, 'utf8'), bytes, 'Prepared report must not change');
    assert.ok(uploads.every(upload => upload.bytes === bytes), 'Every retry must use the same prepared report');
    return {accepted, uploads, delays, state, cancelled, jobSuccess: !previousFailure && !cancelled,
      summary: readFileSync(summary, 'utf8')};
  } finally {
    // A recursive cleanup is limited to this exact newly created directory,
    // with its original filesystem identity and the resolved temporary parent.
    assert.equal(realpathSync.native(temp), ownedPath, 'Owned test path changed');
    assert.equal(realpathSync.native(dirname(ownedPath)), realpathSync.native(tmpdir()), 'Unexpected cleanup parent');
    assert.match(basename(ownedPath), /^otziv-report-test-[A-Za-z0-9]+$/, 'Unexpected cleanup name');
    const current = lstatSync(temp);
    assert.ok(current.isDirectory() && !current.isSymbolicLink(), 'Owned test path replaced');
    assert.equal(current.dev, ownedIdentity.dev, 'Owned test device changed');
    assert.equal(current.ino, ownedIdentity.ino, 'Owned test directory changed');
    rmSync(ownedPath, {recursive: true, force: true});
  }
}

test('exact official pin and immutable parameter forwarding in all three attempts', () => {
  assert.equal(steps.length, 7);
  const attempts = steps.filter(step => step.uses);
  assert.equal(attempts.length, 3);
  for (const step of attempts) {
    assert.equal(step.uses, pin);
    for (const key of ['path','if-no-files-found','retention-days','compression-level','include-hidden-files']) {
      assert.ok(step.block.includes(key + ': ${{ inputs.' + key + ' }}'), key);
    }
    assert.match(step.block, /^        overwrite: false$/m);
    assert.match(step.block, /^        archive: true$/m);
  }
  assert.equal(steps.filter(step => /continue-on-error: true/.test(step.block)).length, 3);
  assert.equal(steps.at(-1).condition, 'always()');
  assert.match(steps.at(-1).block, /OTZIV_REPORT_CANCELLED: \$\{\{ job\.status == 'cancelled' \}\}/);
  assert.doesNotMatch(action, /^        [A-Z_]+:.*(?:cancelled|always|success|failure)\(\)/m,
    'GitHub status functions belong in step-if, not the step-env expression context');
  assert.ok(steps.slice(0, -1).every(step => step.condition.includes('!cancelled()')));
  assert.doesNotMatch(action, /download-artifact|docker |npm |mvn |overwrite: true|delete-artifact/);
});

test('first success stops retries and exposes exactly its artifact outputs', () => {
  const result = executeActualComposite([uploaded(1)]);
  assert.equal(result.accepted.exit, 0); assert.equal(result.uploads.length, 1);
  assert.deepEqual(result.delays, []);
  assert.deepEqual(result.accepted.outputs, {...uploaded(1).outputs, 'artifact-name': 'upstream-security-test-run-7-upload-1'});
});
test('Finalize-like failure then success preserves distinct immutable attempt names', () => {
  const result = executeActualComposite([{outcome:'failure',outputs:uploaded(1).outputs}, uploaded(2)]);
  assert.equal(result.accepted.exit, 0);
  assert.deepEqual(result.delays, [2]);
  assert.deepEqual(result.uploads.map(row => row.name), ['upstream-security-test-run-7-upload-1','upstream-security-test-run-7-upload-2']);
  assert.equal(result.accepted.outputs['artifact-id'], '1002');
  assert.match(result.summary, /1:failure, 2:success, 3:skipped/);
});
test('third success uses both bounded delays and only the third receipt', () => {
  const result = executeActualComposite([{outcome:'failure'}, {outcome:'failure'}, uploaded(3)]);
  assert.equal(result.accepted.exit, 0); assert.deepEqual(result.delays,[2,5]);
  assert.equal(result.uploads.length,3);
  assert.equal(result.accepted.outputs['artifact-name'],'upstream-security-test-run-7-upload-3');
  assert.equal(result.accepted.outputs['artifact-digest'],uploaded(3).outputs['artifact-digest']);
});
test('all three failures hard-fail the actual final CLI without accepted outputs', () => {
  const result = executeActualComposite([1,2,3].map(i=>({outcome:'failure',outputs:uploaded(i).outputs})));
  assert.equal(result.accepted.exit,1); assert.equal(result.jobSuccess,false);
  assert.equal(result.uploads.length,3); assert.deepEqual(result.accepted.outputs,{});
  assert.match(result.accepted.stderr,/all_report_upload_attempts_failed/);
  assert.match(result.summary,/1:failure, 2:failure, 3:failure/);
});
test('earlier failed scan does not skip diagnostics and does not turn the job green', () => {
  const result = executeActualComposite([{outcome:'failure'},uploaded(2)],{earlierFailure:true});
  assert.equal(result.accepted.exit,0); assert.equal(result.uploads.length,2);
  assert.equal(result.jobSuccess,false);
});
for (const policy of ['warn','ignore']) test(`official success with no files preserves ${policy} semantics`, () => {
  const result = executeActualComposite([{outcome:'success',outputs:{}}],{policy});
  assert.equal(result.accepted.exit,0); assert.equal(result.uploads.length,1);
  assert.ok(Object.values(result.accepted.outputs).every(value=>value===''));
  assert.match(result.accepted.stdout,/NO_FILES_ALLOWED/);
});
test('required no-files failure stays failed after exactly three attempts', () => {
  const result = executeActualComposite([1,2,3].map(()=>({outcome:'failure'})),{policy:'error'});
  assert.equal(result.accepted.exit,1); assert.equal(result.uploads.length,3);
});
test('missing or partial successful outputs cannot satisfy required reports', () => {
  for (const outputs of [{},{'artifact-id':'1001'},{...uploaded(1).outputs,'artifact-digest':''}]) {
    const result = executeActualComposite([{outcome:'success',outputs}]);
    assert.equal(result.accepted.exit,1); assert.deepEqual(result.accepted.outputs,{});
  }
});
test('cancellation after an upload cannot publish accepted success', () => {
  const result = executeActualComposite([uploaded(1)],{cancelAfter:1});
  assert.equal(result.accepted.exit,1); assert.equal(result.jobSuccess,false);
  assert.deepEqual(result.accepted.outputs,{}); assert.match(result.accepted.stderr,/report_upload_cancelled/);
});
test('failed delay prevents the next upload and acceptance', () => {
  const result = executeActualComposite([{outcome:'failure'}],{failDelay:'delay_2'});
  assert.equal(result.uploads.length,1); assert.equal(result.accepted.exit,1);
});
test('run attempts have disjoint names and newline input cannot inject outputs', () => {
  const second = executeActualComposite([uploaded(1)],{runAttempt:'2'});
  assert.equal(second.accepted.outputs['artifact-name'],'upstream-security-test-run-2-upload-1');
  const invalid = executeActualComposite([],{name:'report\nartifact-id=999'});
  assert.equal(invalid.uploads.length,0); assert.equal(invalid.accepted.exit,1);
  assert.deepEqual(invalid.accepted.outputs,{});
});
test('ambiguous success sequence and unrecognized policy fail closed', () => {
  const config=settings({OTZIV_REPORT_NAME:'report',OTZIV_REPORT_RUN_ATTEMPT:'1',OTZIV_REPORT_NO_FILES:'warn'});
  assert.throws(()=>acceptUpload(config,{prepare:{outcome:'success'},upload_1:uploaded(1),upload_2:uploaded(2)}),/multiple_report_uploads_accepted/);
  assert.throws(()=>acceptUpload(config,{prepare:{outcome:'success'},upload_1:{outcome:'skipped'},upload_2:uploaded(2)}),/report_attempt_sequence_invalid/);
  assert.throws(()=>settings({OTZIV_REPORT_NAME:'report',OTZIV_REPORT_RUN_ATTEMPT:'1',OTZIV_REPORT_NO_FILES:'continue'}),/no_files_policy_invalid/);
});
