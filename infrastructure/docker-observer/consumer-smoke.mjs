import { randomUUID, createHash } from 'node:crypto';
import { mkdtemp, readFile, writeFile, rm, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { run as runProcess } from '../recovery/process.mjs';
import { assertLocalDocker } from '../recovery/drill.mjs';

let lastOperation = '';
const run = (command, args, options) => {
  lastOperation = `${command} ${args.slice(0, 2).join(' ')}`;
  return runProcess(command, args, options);
};
await assertLocalDocker();
const observerImage = process.argv[2] || 'otziv-docker-observer:local';
const compose = await readFile(new URL('../../docker-compose.yaml', import.meta.url), 'utf8');
const pinnedImage = name => {
  const candidate = process.env[`OTZIV_CONSUMER_PROOF_${name.toUpperCase()}_IMAGE`];
  if (candidate) {
    if (!/^(?:[a-z0-9./-]+@)?sha256:[a-f0-9]{64}$/.test(candidate)) throw new Error('candidate consumer image must be immutable');
    return candidate;
  }
  const match = compose.match(new RegExp(`^  ${name}:\\r?\\n    image: ([^\\r\\n]+@sha256:[a-f0-9]{64})`, 'm'));
  if (!match) throw new Error('consumer image must be pinned in production compose'); return match[1];
};
const dozzle = pinnedImage('dozzle'), alloy = pinnedImage('alloy'), loki = pinnedImage('loki');
const owner = randomUUID(), network = `otziv-observer-test-${owner}`;
const label = 'com.otziv.observer-smoke.owner';
const marker = `OTZIV_OBSERVER_FIXTURE_${owner}`;
const local = await mkdtemp(join(tmpdir(), 'otziv-observer-test-'));
const allocated = []; let networkAllocated = false;
const create = async (role, image, args = [], command = []) => {
  const name = `${network}-${role}`;
  await run('docker', ['create', '--name', name, '--label', `${label}=${owner}`, '--network', network,
    '--network-alias', role, '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
    '--tmpfs', '/tmp:rw,nosuid,size=128m', '--memory', '512m', '--pids-limit', '256', ...args, image, ...command]);
  allocated.push(name); return name;
};
const fixtureScript = fileURLToPath(new URL('./consumer-fixture.cjs', import.meta.url));
try {
  for (const image of [dozzle, alloy, loki]) {
    await run('docker', image.startsWith('sha256:') ? ['image', 'inspect', image] : ['pull', image], { timeoutMs: 300_000 });
  }
  await run('docker', ['network', 'create', '--internal', '--label', `${label}=${owner}`, network]); networkAllocated = true;
  const observer = await create('observer', observerImage, ['--network-alias', 'docker-observer', '--mount', 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock,readonly',
    '--mount', `type=bind,source=${fixtureScript},target=/fixtures/consumer.cjs,readonly`]);
  await run('docker', ['start', observer]);
  const fixtureName = `${network}-fixture`;
  const fixture = await run('docker', ['run', '--detach', '--name', fixtureName, '--label', `${label}=${owner}`,
    '--label', `dozzle.name=${marker}`, '--network', network, '--cap-drop', 'ALL', '--read-only', '--security-opt', 'no-new-privileges:true',
    '--memory', '128m', '--pids-limit', '32', '--env', 'FIXTURE_PRIVATE_VALUE=synthetic-only-secret', observerImage,
    'node', '-e', `setInterval(()=>console.log('${marker}'),250)`]);
  allocated.push(fixtureName);
  const viewer = await create('dozzle', dozzle, ['--tmpfs', '/data:rw,nosuid,size=128m',
    '--env', 'DOZZLE_REMOTE_HOST=tcp://observer:2375', '--env', `DOZZLE_FILTER=name=${network}`]);
  await run('docker', ['start', viewer]);
  const sink = await create('sink', observerImage, ['--env', `FIXTURE_MARKER=${marker}`,
    '--mount', `type=bind,source=${fixtureScript},target=/fixtures/consumer.cjs,readonly`], ['node', '/fixtures/consumer.cjs', 'sink']);
  await run('docker', ['start', sink]);
  const lokiConfig = join(local, 'fixture-loki.yaml');
  await writeFile(lokiConfig, `auth_enabled: false\nserver:\n  http_listen_port: 3100\ncommon:\n  instance_addr: 127.0.0.1\n  path_prefix: /tmp/loki\n  storage:\n    filesystem:\n      chunks_directory: /tmp/loki/chunks\n      rules_directory: /tmp/loki/rules\n  replication_factor: 1\n  ring:\n    kvstore:\n      store: inmemory\nschema_config:\n  configs:\n    - from: 2020-01-01\n      store: tsdb\n      object_store: filesystem\n      schema: v13\n      index:\n        prefix: index_\n        period: 24h\nanalytics:\n  reporting_enabled: false\n`);
  const lokiContainer = await create('loki', loki, ['--mount', `type=bind,source=${lokiConfig},target=/etc/loki/fixture.yaml,readonly`], ['-config.file=/etc/loki/fixture.yaml']);
  await run('docker', ['start', lokiContainer]);
  const originalConfig = await readFile(new URL('../alloy/config.alloy', import.meta.url), 'utf8');
  // Keep every production component; the fixture-only discovery fence prevents reading unrelated host logs.
  if (!originalConfig.includes('discovery.docker "containers" {') || !originalConfig.includes('loki.write "local" {')) throw new Error('fixture_alloy_configuration_changed');
  const config = originalConfig.replace('discovery.docker "containers" {',
    `discovery.docker "containers" {\n  filter { name = "name"\n values = ["${network}"] }`)
    .replace('loki.write "local" {', 'loki.write "local" {\n  endpoint { url = "http://sink:3100/loki/api/v1/push"\n batch_wait = "1s" }');
  const backendLogDirectory = join(local, 'backend-logs'); await mkdir(backendLogDirectory);
  for (const name of ['app', 'error', 'access', 'debug']) await writeFile(join(backendLogDirectory, name + '.log'), marker + '-file-' + name + '\n');
  console.log(JSON.stringify({ images: { dozzle, alloy, loki },
    productionAlloyConfigSHA256: createHash('sha256').update(originalConfig).digest('hex'),
    fixtureAlloyConfigSHA256: createHash('sha256').update(config).digest('hex'),
    processHelperSHA256: createHash('sha256').update(await readFile(new URL('../recovery/process.mjs', import.meta.url))).digest('hex') }));
  const configPath = join(local, 'fixture.alloy'); await writeFile(configPath, config);
  const collector = `${network}-alloy`;
  await run('docker', ['create', '--name', collector, '--label', `${label}=${owner}`, '--network', network, '--network-alias', 'alloy',
    '--read-only', '--tmpfs', '/tmp:rw,nosuid,size=128m', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
    '--mount', `type=bind,source=${configPath},target=/etc/alloy/fixture.alloy,readonly`,
    '--mount', `type=bind,source=${backendLogDirectory},target=/var/log/otziv-app,readonly`,
    '--memory', '512m', '--pids-limit', '256', alloy, 'run', '--storage.path=/tmp/alloy', '/etc/alloy/fixture.alloy']);
  allocated.push(collector); await run('docker', ['start', collector]);
  await new Promise(resolve => setTimeout(resolve, 5000));
  const result = await run('docker', ['exec', '--env', `FIXTURE_ID=${fixture.trim()}`, '--env', `FIXTURE_MARKER=${marker}`,
    observer, 'node', '/fixtures/consumer.cjs', 'probe'], { timeoutMs: 90_000 });
  if (result.includes('OBSERVER_FIXTURE_FAILURE')) throw new Error(result.trim());
  console.log(result.trim());
  const fileProbe = `
    const names = ['app', 'error', 'access', 'debug'];
    for (const name of names) {
      const query = '{job="backend-file",log_file="' + name + '"} |= "' + process.env.FIXTURE_MARKER + '-file-' + name + '"';
      const deadline = Date.now() + 45000; let found = false;
      while (Date.now() < deadline) {
        try {
          const response = await fetch('http://loki:3100/loki/api/v1/query_range?' + new URLSearchParams({query, limit:'10'}), {signal:AbortSignal.timeout(5000)});
          const data = await response.json();
          if (response.status === 200 && data.data?.result?.some(stream => stream.values?.some(value => value[1].includes(process.env.FIXTURE_MARKER + '-file-' + name)))) { found = true; break; }
        } catch {}
        await new Promise(resolve => setTimeout(resolve,500));
      }
      if (!found) throw Error('production_file_source_missing_' + name);
    }
    console.log('All four production Alloy file sources reached real Loki');
  `;
  console.log((await run('docker', ['exec', '--env', `FIXTURE_MARKER=${marker}`, observer, 'node', '--input-type=module', '-e', fileProbe], { timeoutMs: 200000 })).trim());
  for (const consumer of ['dozzle', 'alloy']) {
    const deployMarker = `OTZIV_DEPLOY_${randomUUID().replaceAll('-', '')}`;
    const probe = await create(`probe-${consumer}`, observerImage, ['--label', `dozzle.name=${deployMarker}`],
      ['node', 'deployment-probe.cjs', consumer, deployMarker]);
    const proof = await run('docker', ['start', '--attach', probe], { timeoutMs: 75000 });
    if ((await run('docker', ['inspect', '--format', '{{.State.ExitCode}}', probe])).trim() !== '0'
        || !proof.includes('"syntheticLogFlow":true')) throw new Error('deployed_consumer_probe_failed');
  }
  console.log('Actual production deployment probes verified against real Dozzle SSE and Alloy/Loki query results');
} catch (error) {
  console.error(`Observer fixture failed during ${lastOperation}`);
  for (const name of allocated.filter(name => name.endsWith('-dozzle') || name.endsWith('-alloy'))) {
    try { console.error((await run('docker', ['logs', '--tail', '12', name])).trim()); } catch { /* Preserve primary failure. */ }
  }
  throw error;
} finally {
  const errors = [];
  for (const name of allocated.reverse()) {
    try {
      const actual = (await run('docker', ['inspect', '--format', `{{index .Config.Labels "${label}"}}`, name])).trim();
      if (actual !== owner) throw new Error('ownership mismatch');
      await run('docker', ['rm', '--force', name]);
    } catch { errors.push('container'); }
  }
  if (networkAllocated) {
    try {
      const actual = (await run('docker', ['network', 'inspect', '--format', `{{index .Labels "${label}"}}`, network])).trim();
      if (actual !== owner) throw new Error('ownership mismatch');
      await run('docker', ['network', 'rm', network]);
    } catch { errors.push('network'); }
  }
  await rm(local, { recursive: true, force: true });
  if (errors.length) throw new Error('observer fixture cleanup failed');
}
