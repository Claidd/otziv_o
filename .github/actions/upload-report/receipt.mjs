import assert from 'node:assert/strict';
import {appendFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';

export function settings(env) {
  const name = env.OTZIV_REPORT_NAME;
  const runAttempt = env.OTZIV_REPORT_RUN_ATTEMPT;
  const policy = env.OTZIV_REPORT_NO_FILES;
  assert.ok(typeof name === 'string' && name.trim() && !/[\r\n\0]/.test(name), 'report_name_invalid');
  assert.match(runAttempt || '', /^[1-9][0-9]*$/, 'run_attempt_invalid');
  assert.ok(['error', 'warn', 'ignore'].includes(policy), 'no_files_policy_invalid');
  return {name, runAttempt, policy, names: [1, 2, 3].map(i => `${name}-run-${runAttempt}-upload-${i}`)};
}

export function acceptUpload(config, steps, cancelled = false) {
  assert.equal(cancelled, false, 'report_upload_cancelled');
  assert.equal(steps?.prepare?.outcome, 'success', 'report_preparation_failed');
  const attempts = [1, 2, 3].map(i => steps[`upload_${i}`] || {outcome: 'skipped', outputs: {}});
  const success = attempts.findIndex(step => step.outcome === 'success');
  assert.ok(success >= 0, 'all_report_upload_attempts_failed');
  assert.ok(attempts.slice(0, success).every(step => step.outcome === 'failure'), 'report_attempt_sequence_invalid');
  assert.ok(attempts.slice(success + 1).every(step => step.outcome === 'skipped'), 'multiple_report_uploads_accepted');
  const outputs = attempts[success].outputs || {};
  const id = outputs['artifact-id'] || '';
  const url = outputs['artifact-url'] || '';
  const digest = outputs['artifact-digest'] || '';
  if (!id && !url && !digest) {
    // The pinned official action returns success with no artifact outputs only
    // for its warn/ignore empty-file branch. Do not reinterpret a failed action.
    assert.ok(config.policy === 'warn' || config.policy === 'ignore', 'required_report_artifact_missing');
    return {status: 'NO_FILES_ALLOWED', attempt: success + 1, outputs: {
      'artifact-id': '', 'artifact-url': '', 'artifact-digest': '', 'artifact-name': ''}};
  }
  assert.match(id, /^[1-9][0-9]*$/, 'report_artifact_id_invalid');
  assert.match(digest, /^[a-f0-9]{64}$/, 'report_artifact_digest_invalid');
  const parsed = new URL(url);
  assert.equal(parsed.protocol, 'https:', 'report_artifact_url_invalid');
  assert.equal(parsed.username + parsed.password, '', 'report_artifact_url_invalid');
  assert.ok(!/[\r\n\0]/.test(url), 'report_artifact_url_invalid');
  return {status: 'UPLOADED', attempt: success + 1, outputs: {
    'artifact-id': id, 'artifact-url': url, 'artifact-digest': digest,
    'artifact-name': config.names[success]}};
}

function output(values, env) {
  assert.ok(env.GITHUB_OUTPUT, 'github_output_missing');
  appendFileSync(env.GITHUB_OUTPUT, Object.entries(values).map(([key, value]) => `${key}=${value}\n`).join(''));
}

export function main(command, env = process.env) {
  const config = settings(env);
  if (command === 'prepare') {
    output(Object.fromEntries(config.names.map((name, i) => [`name-${i + 1}`, name])), env);
    return;
  }
  assert.equal(command, 'finalize', 'report_command_invalid');
  assert.ok(['true', 'false'].includes(env.OTZIV_REPORT_CANCELLED), 'cancellation_state_missing');
  const steps = JSON.parse(env.OTZIV_REPORT_STEPS);
  const attempts = [1, 2, 3].map(i => `${i}:${steps[`upload_${i}`]?.outcome || 'skipped'}`).join(', ');
  // Keep failure/unknown-finalization history in the job summary as well as
  // each official action's log. Never delete or overwrite earlier artifacts.
  if (env.GITHUB_STEP_SUMMARY) appendFileSync(env.GITHUB_STEP_SUMMARY,
    `\nReport upload attempts: ${attempts}. Earlier attempt artifacts, if created, are retained.\n`);
  const accepted = acceptUpload(config, steps, env.OTZIV_REPORT_CANCELLED === 'true');
  output(accepted.outputs, env);
  console.log(JSON.stringify({result: accepted.status, acceptedAttempt: accepted.attempt}));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { main(process.argv[2]); }
  catch (error) {
    // Assertions contain stable diagnostic codes, never provider payloads.
    const code = String(error.message).split('\n')[0];
    console.error(/^[a-z_]+$/.test(code) ? code : 'report_upload_acceptance_failed');
    process.exitCode = 1;
  }
}
