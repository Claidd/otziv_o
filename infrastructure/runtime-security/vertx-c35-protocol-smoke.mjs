// C35 production-path compatibility proof. No host ports or existing databases.
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import assert from 'node:assert/strict';

const [image, outputArgument] = process.argv.slice(2);
if (!image || !outputArgument || !/^[-a-zA-Z0-9_./:@]+$/.test(image)) throw Error('Expected IMAGE NEW_OUTPUT_JSON');
// Container names are also DNS labels: keep the longest mode suffix under 63.
const output = resolve(outputArgument), owner = 'otziv-c35-protocol-' + randomUUID().replaceAll('-', '').slice(0, 20);
const network = owner + '-net', pg = owner + '-pg', runner = owner + '-http';
const password = randomUUID() + '-Fixture9!', realm = 'fixture-' + randomUUID();
const allocated = [];
const report = { schema: 'otziv-keycloak-c35-protocol-v1', startedAt: new Date().toISOString(), production: false, image, checks: [] };
const publicBase = 'https://issuer.fixture.invalid/keycloak';
const postgres = 'ghcr.io/claidd/otziv-security@sha256:c974c12a1107e314b1ba8cf65bd1fc5b6964645ced420c2151d336ee8a9a6534';
const nodeImage = 'node:22-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5';
function docker(args, { input, allowFailure = false, env = {}, timeout = 120000 } = {}) {
  const result = spawnSync('docker', args, { input, encoding: 'utf8', windowsHide: true, timeout,
    env: { ...process.env, ...env }, maxBuffer: 4 * 1024 * 1024 });
  if (result.error || result.status !== 0) {
    if (allowFailure) return null;
    throw Error('docker_' + args[0] + '_failed');
  }
  return result.stdout.trim();
}
function check(name, condition) { assert.ok(condition, name); report.checks.push({ name, passed: true }); console.log('PASS ' + name); }
function envOptions(values) { return Object.keys(values).flatMap(key => ['-e', key]); }
function allocate(name, args, env = {}, start = true) {
  docker(['create', '--name', name, '--label', 'otziv.proof=' + owner, '--network', network, ...args], { env });
  allocated.push(name);
  if (start) docker(['start', name]);
}
function http(url) {
  const code = "let s='';for await(const c of process.stdin)s+=c;const r=await fetch(s,{signal:AbortSignal.timeout(3000)});console.log(JSON.stringify({status:r.status,body:await r.text()}));";
  const result = docker(['exec', '-i', runner, 'node', '--input-type=module', '-e', code], { input: url, allowFailure: true, timeout: 6000 });
  return result === null ? null : JSON.parse(result);
}
async function waitRealm(name) {
  for (let i = 0; i < 120; i++) {
    const result = http('http://' + name + ':8080/keycloak/realms/' + realm + '/.well-known/openid-configuration');
    if (result?.status === 200) return JSON.parse(result.body);
    if (docker(['inspect', '--format', '{{.State.Running}}', name]) !== 'true') throw Error('issuer_exited_before_ready');
    await new Promise(done => setTimeout(done, 500));
  }
  throw Error('issuer_startup_timeout');
}
await mkdir(dirname(output), { recursive: true });
await writeFile(output + '.claim', owner, { flag: 'wx', mode: 0o600 });
try {
  assert.match(docker(['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']), /^(npipe|unix):\/\//);
  report.imageId = docker(['image', 'inspect', '--format', '{{.Id}}', image]);
  report.postgresImage = { reference: postgres, id: docker(['image', 'inspect', '--format', '{{.Id}}', postgres]) };
  docker(['network', 'create', '--internal', '--label', 'otziv.proof=' + owner, network]);
  const pgEnv = { POSTGRES_DB: 'keycloak', POSTGRES_USER: 'keycloak', POSTGRES_PASSWORD: password };
  allocate(pg, ['--memory', '768m', '--pids-limit', '160', ...envOptions(pgEnv), postgres], pgEnv);
  let pgReady = false;
  for (let i = 0; i < 60; i++) {
    if (docker(['exec', pg, 'pg_isready', '-U', 'keycloak'], { allowFailure: true }) !== null) { pgReady = true; break; }
    await new Promise(done => setTimeout(done, 500));
  }
  assert.ok(pgReady, 'isolated PostgreSQL ready');
  allocate(runner, ['--memory', '128m', '--pids-limit', '64', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
    '--entrypoint', 'node', nodeImage, '-e', 'setInterval(()=>{},100000)']);
  const importDirectory = output + '.import';
  await mkdir(importDirectory, { mode: 0o755 });
  await writeFile(resolve(importDirectory, realm + '-realm.json'), JSON.stringify({ realm, enabled: true }), { flag: 'wx', mode: 0o644 });
  for (const mode of ['start']) {
    const name = owner + '-' + mode;
    const env = { KC_DB: 'postgres', KC_DB_URL: 'jdbc:postgresql://' + pg + ':5432/keycloak', KC_DB_USERNAME: 'keycloak', KC_DB_PASSWORD: password,
      KC_HEALTH_ENABLED: 'true', KC_METRICS_ENABLED: 'true', KC_HTTP_ENABLED: 'true',
      KC_HTTP_RELATIVE_PATH: '/keycloak', KC_HOSTNAME: publicBase, KC_HOSTNAME_ADMIN: publicBase,
      KC_HOSTNAME_STRICT: 'true', KC_PROXY_HEADERS: 'xforwarded' };
    allocate(name, ['--memory', '1536m', '--pids-limit', '300', ...envOptions(env), report.imageId,
      mode, '--import-realm'], env, false);
    docker(['cp', importDirectory, name + ':/opt/keycloak/data/import']);
    docker(['start', name]);
    const discovery = await waitRealm(name);
    check('production_path_discovery_issuer', discovery.issuer === publicBase + '/realms/' + realm);
    check('production_path_jwks_uri', discovery.jwks_uri === publicBase + '/realms/' + realm + '/protocol/openid-connect/certs');
    const jwks = http('http://' + name + ':8080/keycloak/realms/' + realm + '/protocol/openid-connect/certs');
    check('production_path_jwks_served', jwks?.status === 200 && Array.isArray(JSON.parse(jwks.body).keys)
      && JSON.parse(jwks.body).keys.length > 0);
    const ready = http('http://' + name + ':9000/keycloak/health/ready');
    check('production_path_management_ready', ready?.status === 200 && JSON.parse(ready.body).status === 'UP');
    const log = docker(['logs', name]);
    check('production_path_has_no_missing_classpath_error', !/ClassNotFoundException|NoClassDefFoundError|does not exist|Failed to start server/i.test(log));
    await writeFile(output + '.' + mode + '.log', log.replaceAll(password, '[REDACTED]'));
    docker(['stop', '--time', '20', name]);
  }
  report.result = 'PASS';
} catch (error) {
  report.result = 'FAIL'; report.error = String(error.message).replaceAll(password, '[REDACTED]'); process.exitCode = 1;
  for (const name of allocated) {
    const log = docker(['logs', name], { allowFailure: true });
    if (log !== null) await writeFile(output + '.' + name.slice(owner.length + 1) + '.failure.log', log.replaceAll(password, '[REDACTED]'));
  }
} finally {
  let clean = true;
  for (const name of allocated.reverse()) {
    const label = docker(['inspect', '--format', '{{index .Config.Labels "otziv.proof"}}', name], { allowFailure: true });
    if (label !== owner || docker(['rm', '-f', '-v', name], { allowFailure: true }) === null) clean = false;
  }
  const label = docker(['network', 'inspect', '--format', '{{index .Labels "otziv.proof"}}', network], { allowFailure: true });
  if (label === owner && docker(['network', 'rm', network], { allowFailure: true }) === null) clean = false;
  report.cleanup = clean ? 'PASS' : 'FAIL'; report.finishedAt = new Date().toISOString();
  await writeFile(output, JSON.stringify(report, null, 2), { flag: 'wx' });
  if (!clean) process.exitCode = 1;
  console.log(JSON.stringify(report));
}
