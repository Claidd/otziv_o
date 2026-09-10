import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { run } from '../recovery/process.mjs';
import { assertLocalDocker } from '../recovery/drill.mjs';

await assertLocalDocker();
const image = process.argv[2];
if (!image) throw new Error('observer_contract_image_required');
const source = fileURLToPath(new URL('../scripts/prod/rollout-docker-observer.sh', import.meta.url));
const script = `. /fixture/rollout.sh
record() { printf '%s\\n' "$1"; [ "$1" != "$FAIL_AT" ]; }
compose() { record "compose:$*"; }
recreate_service_with_retry() { record "recreate:$*"; }
wait_service_healthy() { record "ready:$*"; }
verify_observer_logflow() { record "flow:$*"; }
rollout_docker_observer
`;
const expected = ['compose:pull docker-observer', 'recreate:docker-observer', 'ready:docker-observer 120',
  'recreate:dozzle', 'ready:dozzle 120', 'flow:dozzle', 'recreate:alloy', 'ready:alloy 120', 'flow:alloy'];
for (const failure of ['', expected[0], expected[2], expected[5], expected[8]]) {
  // Run the real sourced shell owner with recording deployment ports. No daemon
  // socket, network or production credentials are visible inside the fixture.
  const output = await run('docker', ['run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
    '--security-opt', 'no-new-privileges:true', '--memory', '128m', '--pids-limit', '64', '--pull', 'never',
    '--mount', `type=bind,source=${source},target=/fixture/rollout.sh,readonly`, '--env', `FAIL_AT=${failure}`,
    image, 'sh', '-c', `${script}\nresult=$?; printf 'RESULT:%s\\n' "$result"`]);
  const lines = output.trim().split(/\r?\n/);
  assert.equal(lines.pop(), `RESULT:${failure ? 1 : 0}`);
  assert.deepEqual(lines, failure ? expected.slice(0, expected.indexOf(failure) + 1) : expected);
}
console.log(JSON.stringify({ result: 'PASS', actualSourcedShell: true, scenarios: 5,
  publishedImagePull: true, failBeforeConsumerReplacement: true, sequentialLogFlow: true }));
