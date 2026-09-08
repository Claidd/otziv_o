// Synthetic local-only compatibility proof. Credentials are generated in memory.
import { spawn } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { assertLocalDocker } from '../../../recovery/drill.mjs';

const [baseline, image, outputArg] = process.argv.slice(2);
const immutable = value => /^(?:sha256:[a-f0-9]{64}|[a-z0-9./:_-]+@sha256:[a-f0-9]{64})$/.test(value ?? '');
if (!immutable(baseline) || !immutable(image) || !outputArg) throw new Error('immutable_mc_image_and_new_output_required');
await assertLocalDocker();
const output = resolve(outputArg); await mkdir(output);
const owner = randomUUID(), label = 'com.otziv.c14-proof-owner';
const prefix = `otziv-c14-local-s3-${owner.slice(0, 8)}`;
const network = `${prefix}-network`, volume = `${prefix}-data`, server = `${prefix}-server`;
const env = { ...process.env, MINIO_ROOT_USER: `fixture${randomBytes(8).toString('hex')}`, MINIO_ROOT_PASSWORD: randomBytes(32).toString('base64url') };
env.ROOT_ACCESS_KEY = env.MINIO_ROOT_USER; env.ROOT_SECRET_KEY = env.MINIO_ROOT_PASSWORD;
const resources = [], checks = [], cleanup = [];
let failure = null, sequence = 0;
async function docker(args, allowFailure = false, timeout = 120000) {
  return new Promise((accept, reject) => {
    const p = spawn('docker', args, { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '', timedOut = false;
    p.stdout.on('data', b => { stdout += b; if (stdout.length > 1048576) p.kill(); });
    p.stderr.on('data', b => { stderr += b; if (stderr.length > 1048576) p.kill(); });
    const timer = setTimeout(() => { timedOut = true; p.kill(); }, timeout);
    p.once('error', error => { clearTimeout(timer); reject(error); });
    p.once('close', code => {
      clearTimeout(timer);
      if ((code !== 0 || timedOut) && !allowFailure) return reject(new Error(`docker_command_failed_${args[0]}_${code}`));
      accept({ code, stdout, stderr, timedOut });
    });
  });
}
async function inspect(kind, name) { return JSON.parse((await docker([kind, 'inspect', name])).stdout)[0]; }
async function owned(kind, name) {
  const state = await inspect(kind, name);
  if ((kind === 'container' ? state.Config.Labels : state.Labels)?.[label] !== owner) throw new Error('ownership_mismatch');
  return state;
}
function check(name, condition) { if (!condition) throw new Error(name); checks.push(name); }
async function cli(script, extraEnv = {}) {
  const name = `${prefix}-client-${++sequence}`;
  Object.assign(env, extraEnv);
  await docker(['create', '--name', name, '--label', `${label}=${owner}`, '--network', network, '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges:true', '--tmpfs', '/tmp:rw,nosuid,size=32m', '--memory', '384m', '-e', 'GOMEMLIMIT=256MiB', '--cpus', '1', '--pids-limit', '96', '-e', 'MINIO_ROOT_USER', '-e', 'MINIO_ROOT_PASSWORD', ...Object.keys(extraEnv).flatMap(k => ['-e', k]), '--entrypoint', '/bin/sh', image, '-ec', 'mc alias set fixture http://minio-proof:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null\n' + script]);
  resources.push({ kind: 'container', name });
  const result = await docker(['start', '-a', name], true);
  const state = await owned('container', name);
  if (result.code !== 0) {
    let stderr = result.stderr, stdout = result.stdout;
    for (const value of [env.MINIO_ROOT_USER, env.MINIO_ROOT_PASSWORD]) {stderr = stderr.replaceAll(value, '<fixture-value>'); stdout = stdout.replaceAll(value, '<fixture-value>');}
    await writeFile(resolve(output, `client-${sequence}-failure.json`), JSON.stringify({sequence,exitCode:state.State.ExitCode,oomKilled:state.State.OOMKilled,stdout,stderr},null,2));
  }
  check(`client_${sequence}_exit_no_oom`, state.State.ExitCode === 0 && !state.State.OOMKilled);
  await docker(['container', 'rm', name]); resources.splice(resources.findIndex(r => r.name === name), 1);
  return result.stdout;
}
try {
  await docker(['network', 'create', '--internal', '--label', `${label}=${owner}`, network]); resources.push({ kind: 'network', name: network });
  check('internal_network', (await owned('network', network)).Internal === true);
  await docker(['volume', 'create', '--label', `${label}=${owner}`, volume]); resources.push({ kind: 'volume', name: volume });
  await docker(['run', '--rm', '--network', 'none', '-v', `${volume}:/var/lib/versity`, '--entrypoint', '/bin/sh', baseline, '-ec', 'mkdir -p /var/lib/versity/data /var/lib/versity/iam /var/lib/versity/versions']);
  await docker(['run', '-d', '--name', server, '--label', `${label}=${owner}`, '--network', network, '--network-alias', 'minio-proof', '--memory', '768m', '--cpus', '2', '--pids-limit', '256', '-v', `${volume}:/var/lib/versity`, '-e', 'ROOT_ACCESS_KEY', '-e', 'ROOT_SECRET_KEY', baseline, '--port', ':9000', '--health', '/health', '--iam-dir', '/var/lib/versity/iam', 'posix', '--versioning-dir', '/var/lib/versity/versions', '/var/lib/versity/data']); resources.push({ kind: 'container', name: server });
  const state = await owned('container', server);
  check('no_published_ports', !Object.values(state.NetworkSettings.Ports ?? {}).some(v => v?.length));
  let ready = false;
  for (let n = 0; n < 90; n++) {
    const r = await docker(['exec', server, 'wget', '-q', '-T', '1', '-O', '-', 'http://127.0.0.1:9000/health'], true, 3000);
    if (r.code === 0) { ready = true; break; }
    if (!(await owned('container', server)).State.Running) throw new Error('server_exited');
    await delay(1000);
  }
  check('server_ready', ready);
  const init = 'mc mb --ignore-existing fixture/proof-public >/dev/null\nmc anonymous set download fixture/proof-public >/dev/null\nmc stat --json fixture/proof-public';
  for (let n = 0; n < 2; n++) check(`compose_init_idempotent_${n}`, JSON.parse((await cli(init)).trim()).status === 'success');
  await cli('printf "c14-public-object" | mc pipe fixture/proof-public/object.txt >/dev/null\nmc mb fixture/proof-private >/dev/null\nprintf "c14-private-object" | mc pipe fixture/proof-private/object.txt >/dev/null\ntest "$(mc cat fixture/proof-private/object.txt)" = c14-private-object');
  check('public_anonymous_http_bytes', (await docker(['exec', server, 'wget', '-q', '-O', '-', 'http://127.0.0.1:9000/proof-public/object.txt'])).stdout === 'c14-public-object');
  const denied = await docker(['exec', server, 'wget', '-S', '-O', '/dev/null', 'http://127.0.0.1:9000/proof-private/object.txt'], true);
  check('private_anonymous_http_403', denied.code !== 0 && /403 Forbidden/.test(denied.stderr));
  checks.push('public_anonymous_bytes', 'private_anonymous_denied', 'private_authenticated_bytes');
  await cli('mc mb fixture/proof-versioned >/dev/null'); checks.push('versioned_bucket_created');
  await cli('mc version enable fixture/proof-versioned >/dev/null'); checks.push('versioning_enabled');
  await cli('printf c14-v1 | mc pipe fixture/proof-versioned/object.txt >/dev/null'); checks.push('first_version_written');
  await cli('printf c14-v2 | mc pipe fixture/proof-versioned/object.txt >/dev/null'); checks.push('second_version_written');
  const versions = (await cli('mc ls --versions --json fixture/proof-versioned')).trim().split('\n').map(line => JSON.parse(line));
  check('two_distinct_object_versions', versions.length === 2 && versions.every(v => v.status === 'success' && typeof v.versionId === 'string' && v.versionId.length > 0) && versions[0].versionId !== versions[1].versionId);
  const actual = [];
  for (const v of versions) actual.push((await cli('mc cat --version-id "$PROOF_VERSION" fixture/proof-versioned/object.txt', { PROOF_VERSION: v.versionId })).trim());
  check('each_version_readable', actual.sort().join(',') === 'c14-v1,c14-v2');
  await cli('test "$(mc cat fixture/proof-versioned/object.txt)" = c14-v2\nmc cp fixture/proof-versioned/object.txt fixture/proof-private/restored.txt >/dev/null\ntest "$(mc cat fixture/proof-private/restored.txt)" = c14-v2');
  checks.push('latest_version_readable', 'object_copy_restore_bytes');
  await docker(['stop', '--time', '30', server]);
  const stopped = await owned('container', server); check('restart_graceful_stop', stopped.State.ExitCode === 0 && !stopped.State.OOMKilled);
  await docker(['start', server]);
  for (let n = 0; n < 60; n++) {
    const r = await docker(['exec', server, 'wget', '-q', '-T', '1', '-O', '-', 'http://127.0.0.1:9000/health'], true, 3000);
    if (r.code === 0) break;
    if (n === 59) throw new Error('restart_readiness_timeout');
    await delay(1000);
  }
  const restoredVersions = [];
  for (const v of versions) restoredVersions.push((await cli('mc cat --version-id "$PROOF_VERSION" fixture/proof-versioned/object.txt', { PROOF_VERSION: v.versionId })).trim());
  check('same_version_ids_and_bytes_after_restart', restoredVersions.sort().join(',') === 'c14-v1,c14-v2');
  await cli('test "$(mc cat fixture/proof-private/restored.txt)" = c14-v2');
  checks.push('copied_object_durable_after_restart');
  const stats = JSON.parse((await docker(['stats','--no-stream','--format','{{json .}}',server])).stdout.trim());
  await writeFile(resolve(output, 'runtime-resources.json'), JSON.stringify({memory:stats.MemUsage,cpu:stats.CPUPerc,pids:stats.PIDs},null,2)+'\n');
} catch (error) { failure = error.message; }
for (const { kind, name } of [...resources].reverse()) {
  try {
    const state = await owned(kind, name);
    if (kind === 'container' && state.State.Running) {
      await docker(['stop', '--time', '30', name], false, 40000);
      const stopped = await owned(kind, name);
      check('server_graceful_stop', stopped.State.ExitCode === 0 && !stopped.State.OOMKilled);
    }
    await docker([kind, 'rm', name]); cleanup.push({ kind, result: 'PASS' });
  } catch (error) { cleanup.push({ kind, result: 'FAIL', code: error.message }); failure ??= 'cleanup_failed'; }
}
const result = { schema: 'otziv-c14-versity-compatibility-v1', result: failure ? 'FAIL' : 'PASS', timestamp: new Date().toISOString(), image, publishedServer: baseline, syntheticOnly: true, limitsApplied: { serverMemoryMiB:768,serverCpus:2,clientMemoryMiB:384,clientGOMEMLIMIT:'256MiB' }, checks, cleanup, failure, limits: ['Versity v1.8.0 derivative (only two vendor Alpine package updates) on new synthetic POSIX storage only. This does not migrate a MinIO volume, prove distributed storage, or establish production durability.'] };
await writeFile(resolve(output, 'result.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({ result: result.result, checks: checks.length, cleanup: cleanup.map(r => r.result), failure }));
if (failure) process.exitCode = 1;
