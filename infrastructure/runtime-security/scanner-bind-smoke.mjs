import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { run } from '../recovery/process.mjs';
import { assertLocalDocker } from '../recovery/drill.mjs';
import { scannerUserArguments, TRIVY_IMAGE } from './scan.mjs';

// A real Linux filesystem reproducer, including when invoked from Windows Docker.
// Only a fresh labelled volume is created; no Docker socket or host directory enters Trivy.
await assertLocalDocker();
const owner = randomUUID(), volume = `otziv-scanner-bind-${owner}`;
const label = 'com.otziv.scanner-bind.owner';
const output = resolve(process.argv[2] || '.codex-tmp/scanner-bind-proof.json');
let created = false;
const checks = [];
try {
  await run('docker', ['volume', 'create', '--label', `${label}=${owner}`, volume]); created = true;
  await run('docker', ['run', '--rm', '--network', 'none', '--read-only', '--entrypoint', 'sh',
    '--mount', `type=volume,source=${volume},target=/fixture`, TRIVY_IMAGE, '-ec',
    'mkdir /fixture/input /fixture/cache /fixture/scratch /fixture/results; chmod 755 /fixture/*; chown 1001:1002 /fixture/*']);
  const isolated = ['run', '--rm', '--network', 'none', '--read-only', '--cap-drop=ALL',
    '--security-opt=no-new-privileges:true', '--memory', '128m', '--pids-limit', '32',
    '--mount', `type=volume,source=${volume},target=/fixture`, '--entrypoint', 'sh'];
  await assert.rejects(run('docker', [...isolated, TRIVY_IMAGE, '-ec', 'mkdir /fixture/scratch/trivy-old']), /subprocess_failed/);
  checks.push('original capability-dropped root cannot write runner-owned scratch: reproduced');
  await run('docker', [...isolated, ...scannerUserArguments('linux', 1001, 1002), TRIVY_IMAGE, '-ec',
    'test "$(id -u):$(id -g)" = 1001:1002; mkdir /fixture/scratch/trivy-new; echo input > /fixture/input/image.tar; echo cache > /fixture/cache/db; echo report > /fixture/results/report.json; test -s /fixture/results/report.json']);
  checks.push('same owner writes input/cache/scratch/report with all capabilities dropped');
  await run('docker', [...isolated, ...scannerUserArguments('linux', 1001, 1002), TRIVY_IMAGE, '-ec',
    'test "$(cat /fixture/results/report.json)" = report; echo sbom > /fixture/results/sbom.cdx.json; test -s /fixture/results/sbom.cdx.json; test ! -w /etc/passwd']);
  checks.push('separate converter process reads report and writes SBOM; root filesystem remains read-only');
} finally {
  if (created) {
    assert.equal((await run('docker', ['volume', 'inspect', '--format', `{{index .Labels "${label}"}}`, volume])).trim(), owner);
    await run('docker', ['volume', 'rm', volume]);
  }
}
await mkdir(resolve(output, '..'), { recursive: true });
await writeFile(output, JSON.stringify({ result: 'PASS', scannerImage: TRIVY_IMAGE, checks, cleanup: 'PASS', network: 'none' }, null, 2) + '\n');
console.log(JSON.stringify({ result: 'PASS', checks: checks.length, cleanup: 'PASS' }));
