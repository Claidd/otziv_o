// Manual real storage/configuration proof; never opens a deployed service volume.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { randomUUID, createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, readdir, cp } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scenario } from './monitoring-scenarios.mjs';
import { readMonitoringSource, assertDistinctMonitoringImageIds } from './monitoring-source.mjs';

const [component, candidate, outputArgument] = process.argv.slice(2);
if (!['prometheus', 'grafana', 'loki', 'tempo', 'alloy'].includes(component) || !/^(?:[a-z0-9./-]+@)?sha256:[a-f0-9]{64}$/.test(candidate || '') || !outputArgument) throw Error('Expected COMPONENT IMMUTABLE_CANDIDATE NEW_OUTPUT_DIRECTORY');
const root = resolve(fileURLToPath(new URL('../../', import.meta.url))), output = resolve(outputArgument);
const { source, manifestSha256: sourceManifestSha256 } = await readMonitoringSource(component);
await mkdir(output, { recursive: true });
if ((await readdir(output)).length) throw Error('output_must_be_new');
const owner = 'otziv-monitor-' + randomUUID().replaceAll('-', '').slice(0, 16), network = owner + '-net', label = 'com.otziv.monitoring-proof.owner';
await writeFile(join(output, 'run.claim'), owner, { flag: 'wx' });
const runnerImage = process.env.OTZIV_MONITORING_PROOF_NODE_IMAGE || 'otziv-observer-rollout:20260907';
const containers = new Set(), volumes = new Set(), password = randomUUID() + '-Fixture9!';
const report = { schema: 'otziv-monitoring-upgrade-v1', component, production: false, startedAt: new Date().toISOString(), source, candidate,
  sourcePolicy: 'REVIEWED_IMMUTABLE_HISTORICAL_BASELINE', sourceManifestSha256,
  dataScope: 'SYNTHETIC_SEEDED_PERSISTENT_VOLUME', productionHistoricalData: 'NOT_TESTED', checks: [], incompatibilities: [], configurationHashes: {} };
let runner, active, networkCreated = false;
function docker(args, { input, allowFailure = false, env = {}, timeout = 120000, includeStderr = false } = {}) {
  const result = spawnSync('docker', args, { input, encoding: 'utf8', windowsHide: true, timeout, env: { ...process.env, ...env }, maxBuffer: 8 * 1024 * 1024 });
  if (result.error || result.status !== 0) {
    if (allowFailure) return null;
    report.failedDockerOperation = args[0];
    report.diagnostic = (result.stderr || result.error?.message || '').replaceAll(password, '[REDACTED]').slice(-4000);
    throw Error('docker_' + args[0] + '_failed');
  }
  return (result.stdout + (includeStderr ? result.stderr : '')).trim();
}
function check(name, condition) { assert.ok(condition, name); report.checks.push({ name, passed: true }); console.log('PASS ' + name); }
function create(role, image, args = [], command = [], env = {}) {
  const name = owner + '-' + role;
  docker(['create', '--pull=never', '--name', name, '--label', label + '=' + owner, '--network', network, '--security-opt', 'no-new-privileges:true',
    '--pids-limit', '256', ...args, image, ...command], { env }); containers.add(name); return name;
}
function remove(name) {
  assert.ok(containers.has(name)); assert.equal(docker(['inspect', '--format', `{{index .Config.Labels "${label}"}}`, name]), owner);
  docker(['rm', '-f', '-v', name]); containers.delete(name);
}
function volume(role) { const name = owner + '-' + role; docker(['volume', 'create', '--label', label + '=' + owner, name]); volumes.add(name); return name; }
function helper(role, args, command) {
  const name = create(role, runnerImage, ['--memory', '128m', '--user', '0:0', '--entrypoint', 'sh', ...args], ['-c', command]);
  try { const result = docker(['start', '-a', name]); assert.equal(docker(['inspect', '--format', '{{.State.ExitCode}}', name]), '0'); return result; }
  finally { remove(name); }
}
function copyVolume(from, to, role) {
  assert.ok(volumes.has(from) && volumes.has(to) && from !== to);
  helper(role, ['--mount', `type=volume,source=${from},target=/source,readonly`, '--mount', `type=volume,source=${to},target=/target`], 'tar cf /tmp/fixture-copy.tar -C /source . && tar xf /tmp/fixture-copy.tar -C /target');
}
function volumeManifest(data, role) {
  assert.ok(volumes.has(data));
  const code = "const fs=require('fs'),p=require('path'),crypto=require('crypto');let result=[];async function walk(dir){for(const n of fs.readdirSync(dir).sort()){const f=p.join(dir,n),s=fs.lstatSync(f);if(s.isDirectory())await walk(f);else if(s.isSymbolicLink())result.push({path:f.slice(8),link:fs.readlinkSync(f)});else{const h=crypto.createHash('sha256');for await(const b of fs.createReadStream(f))h.update(b);result.push({path:f.slice(8),size:s.size,mode:s.mode,uid:s.uid,gid:s.gid,sha256:h.digest('hex')})}}}walk('/source').then(()=>console.log(JSON.stringify(result)))";
  const name = create(role, runnerImage, ['--memory', '128m', '--read-only', '--user', '0:0', '--entrypoint', 'node', '--mount', `type=volume,source=${data},target=/source,readonly`], ['-e', code]);
  try { const result = docker(['start', '-a', name]); assert.equal(docker(['inspect', '--format', '{{.State.ExitCode}}', name]), '0'); return JSON.parse(result); }
  finally { remove(name); }
}
async function http(path, options = {}, port = scenario(component).port, alias = component) {
  const code = "let s='';for await(const c of process.stdin)s+=c;const q=JSON.parse(s);const r=await fetch(q.url,{...q.options,redirect:'manual',signal:AbortSignal.timeout(5000)});console.log(JSON.stringify({status:r.status,text:await r.text()}));";
  const raw = docker(['exec', '-i', runner, 'node', '--input-type=module', '-e', code], {
    input: JSON.stringify({ url: `http://${alias}:${port}${path}`, options }), timeout: 8000 });
  return JSON.parse(raw);
}
async function until(work, message, timeout = 90000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    try { const result = await work(); if (result) return result; } catch { /* bounded readiness retry, preserved timeout below */ }
    if (active && docker(['inspect', '--format', '{{.State.Running}}', active], { allowFailure: true }) === 'false') throw Error('service_exited_' + message);
    await new Promise(done => setTimeout(done, 750));
  }
  throw Error('timeout_' + message);
}
async function snapshotConfigs() {
  const dir = join(output, 'configuration'); await mkdir(dir);
  for (const name of ['prometheus', 'grafana', 'loki', 'tempo', 'alloy']) await cp(join(root, 'infrastructure', name), join(dir, name), { recursive: true });
  async function hashes(path, prefix = '') {
    for (const entry of await readdir(path, { withFileTypes: true })) {
      const relative = prefix + entry.name;
      if (entry.isDirectory()) await hashes(join(path, entry.name), relative + '/');
      else report.configurationHashes[relative] = createHash('sha256').update(await readFile(join(path, entry.name))).digest('hex');
    }
  }
  await hashes(dir); return dir;
}
const context = { component, owner, password, report, check, docker, http, until, output,
  auth: { Authorization: 'Basic ' + Buffer.from('admin:' + password).toString('base64') },
  setPhase(phase) { docker(['exec', '-i', runner, 'node', '-e', "let s='';process.stdin.on('data',c=>s+=c);process.stdin.on('end',()=>require('fs').writeFileSync('/tmp/fixture-state.json',s))"], { input: JSON.stringify({ phase, password }) }); }
};
try {
  report.harnessSha256 = {};
  for (const name of ['monitoring-upgrade.mjs', 'monitoring-source.mjs', 'monitoring-scenarios.mjs', 'monitoring-fixture-http.cjs', '../docker-observer/consumer-fixture.cjs']) {
    report.harnessSha256[name] = createHash('sha256').update(await readFile(join(root, 'infrastructure/runtime-security', name))).digest('hex');
  }
  assert.match(docker(['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']), /^(npipe|unix):\/\//);
  report.sourceImageId = docker(['image', 'inspect', '--format', '{{.Id}}', source]);
  report.candidateImageId = docker(['image', 'inspect', '--format', '{{.Id}}', candidate]);
  assertDistinctMonitoringImageIds(report.sourceImageId, report.candidateImageId);
  const configDirectory = await snapshotConfigs();
  docker(['network', 'create', '--internal', '--label', label + '=' + owner, network]); networkCreated = true;
  runner = create('http', runnerImage, ['--network-alias', 'app', '--memory', '128m', '--read-only', '--tmpfs', '/tmp:rw,nosuid,size=32m', '--cap-drop', 'ALL',
    '--mount', `type=bind,source=${join(root, 'infrastructure/runtime-security/monitoring-fixture-http.cjs')},target=/fixture-http.cjs,readonly`,
    '--mount', `type=bind,source=${join(root, 'infrastructure/docker-observer/consumer-fixture.cjs')},target=/consumer-fixture.cjs,readonly`, '--entrypoint', 'node'], ['-e', 'setInterval(()=>{},100000)']);
  docker(['start', runner]);
  context.setPhase('before');
  docker(['exec', '-d', runner, 'node', '/fixture-http.cjs']);
  const ownerVolume = volume('data'), backup = volume('backup'), restored = volume('rollback');
  const behavior = scenario(component);
  await behavior.prepare?.(context, configDirectory);
  helper('permissions', ['--mount', `type=volume,source=${ownerVolume},target=/target`], `chown ${behavior.uid}:${behavior.gid} /target`);
  async function start(role, image, data) {
    const env = behavior.environment(password);
    const envArgs = Object.keys(env).flatMap(name => ['-e', name]);
    active = create(role, image, ['--network-alias', component, '--memory', behavior.memory, '--mount', `type=volume,source=${data},target=${behavior.dataPath}`,
      ...behavior.mounts(configDirectory, context).flatMap(([from, to]) => ['--mount', `type=bind,source=${from},target=${to},readonly`]), ...envArgs], behavior.command, env);
    docker(['start', active]);
    await until(async () => (await http(behavior.ready)).status === 200, role + '_ready', 180000);
    check(role + '_real_readiness', true);
    const imageHealth = JSON.parse(docker(['image', 'inspect', '--format', '{{json .Config.Healthcheck}}', image]))?.Test;
    const usePlannedImageHealth = ['candidate', 'restart'].includes(role) && imageHealth?.[0] === 'CMD';
    const healthCommand = usePlannedImageHealth ? imageHealth.slice(1)
      : behavior.healthCommand || ['sh', '-c', `wget -q -O /dev/null http://127.0.0.1:${behavior.port}${behavior.ready}`];
    const health = docker(['exec', active, ...healthCommand], { allowFailure: true });
    report.healthCommands ??= {}; report.healthCommands[role] = healthCommand;
    const healthName = role + (usePlannedImageHealth ? '_planned_image_http_health_command_works' : '_existing_compose_http_health_command_works');
    if (health !== null) check(healthName, true);
    else {
      report.checks.push({ name: healthName, passed: false });
      report.incompatibilities.push({ role, code: 'http_health_command_failed', command: healthCommand });
      console.log('FAIL ' + healthName);
    }
    if (usePlannedImageHealth) {
      await until(() => docker(['inspect', '--format', '{{.State.Health.Status}}', active]) === 'healthy', role + '_docker_health', 60000);
      check(role + '_docker_reports_healthy', true);
    }
  }
  async function stop(role) {
    docker(['stop', '--time', '30', active]);
    const state = JSON.parse(docker(['inspect', '--format', '{{json .State}}', active]));
    report.shutdowns ??= {}; report.shutdowns[role] = { exitCode: state.ExitCode, oomKilled: state.OOMKilled };
    await writeFile(join(output, role + '.log'), docker(['logs', active], { includeStderr: true }).replaceAll(password, '[REDACTED]'));
    assert.ok([0, 143].includes(state.ExitCode) && state.OOMKilled === false, role + '_graceful_shutdown');
    remove(active); active = undefined;
  }
  await start('source', report.sourceImageId, ownerVolume);
  const original = await behavior.seed(context, 'before');
  await behavior.verify(context, original);
  await behavior.flush(context);
  await stop('source');
  report.sourceVolumeManifest = volumeManifest(ownerVolume, 'source-manifest');
  if (component === 'alloy') assert.ok(report.sourceVolumeManifest.some(file => /positions/.test(file.path) && file.size > 0), 'actual_file_positions_are_in_backup');
  copyVolume(ownerVolume, backup, 'capture');
  assert.deepEqual(volumeManifest(backup, 'backup-manifest'), report.sourceVolumeManifest);
  check('stopped_source_volume_backed_up_before_upgrade', true);
  await start('candidate', report.candidateImageId, ownerVolume);
  await behavior.verify(context, original);
  check('candidate_reads_original_persisted_data', true);
  const next = await behavior.seed(context, 'after');
  await behavior.verify(context, next);
  await behavior.flush(context);
  await stop('candidate');
  await start('restart', report.candidateImageId, ownerVolume);
  await behavior.verify(context, original); await behavior.verify(context, next);
  check('candidate_restart_preserves_original_and_new_data', true);
  await stop('restart');
  await behavior.verifyAfterStop?.(context, original, next);
  copyVolume(backup, restored, 'restore');
  assert.deepEqual(volumeManifest(restored, 'restore-manifest'), report.sourceVolumeManifest);
  context.setPhase('rollback');
  await behavior.beforeRollback?.(context, original);
  await start('rollback', report.sourceImageId, restored);
  if (behavior.verifyRollback) await behavior.verifyRollback(context, original, next);
  else { await behavior.verify(context, original); assert.ok(await behavior.absent(context, next)); }
  check('rollback_uses_backup_and_reads_only_preupgrade_data', true);
  await stop('rollback');
  report.result = report.incompatibilities.length ? 'FAIL' : 'PASS';
  if (report.result === 'FAIL') process.exitCode = 1;
} catch (error) {
  report.result = 'FAIL'; report.error = String(error.message).replaceAll(password, '[REDACTED]'); process.exitCode = 1;
  if (active) await writeFile(join(output, 'failure.log'), (docker(['logs', active], { allowFailure: true, includeStderr: true }) || '').replaceAll(password, '[REDACTED]'));
} finally {
  let clean = true;
  for (const name of [...containers].reverse()) { try { remove(name); } catch { clean = false; } }
  for (const name of volumes) {
    if (docker(['volume', 'inspect', '--format', `{{index .Labels "${label}"}}`, name], { allowFailure: true }) !== owner
      || docker(['volume', 'rm', name], { allowFailure: true }) === null) clean = false;
  }
  if (networkCreated && (docker(['network', 'inspect', '--format', `{{index .Labels "${label}"}}`, network], { allowFailure: true }) !== owner
    || docker(['network', 'rm', network], { allowFailure: true }) === null)) clean = false;
  report.cleanup = clean ? 'PASS' : 'FAIL'; report.finishedAt = new Date().toISOString();
  await writeFile(join(output, 'proof.json'), JSON.stringify(report, null, 2));
  if (!clean) process.exitCode = 1;
  console.log(JSON.stringify({ result: report.result, component, checks: report.checks.length, cleanup: report.cleanup, error: report.error }));
}
