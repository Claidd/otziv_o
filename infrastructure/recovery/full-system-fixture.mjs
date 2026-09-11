import assert from 'node:assert/strict';
import { randomUUID, randomBytes, createHash, createCipheriv } from 'node:crypto';
import { mkdir, readFile, writeFile, rm, mkdtemp, readdir } from 'node:fs/promises';
import { createReadStream, createWriteStream } from 'node:fs';
import { pipeline } from 'node:stream/promises';
import { Readable } from 'node:stream';
import { resolve, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { run, startProcess } from './process.mjs';
import { assertLocalDocker } from './drill.mjs';
import { openSystemBundle, sealSystemBundle } from './system-bundle.mjs';
import { encryptionKey } from './envelope.mjs';
import { dockerEnvironment } from './docker-environment.mjs';
import { verifyApplicationShutdown } from './application-shutdown.mjs';

// Manual fixture only. Every container, volume and network is uniquely owned,
// has no published ports and is destroyed after verification. No stock restore.
const [appImage, keycloakImage, outputArgument] = process.argv.slice(2);
if (![appImage, keycloakImage].every(v => /^[-a-zA-Z0-9_./:@]+$/.test(v || ''))) throw Error('Expected app and Keycloak image references');
const output = resolve(outputArgument || '.codex-tmp/full-system-fixture');
await mkdir(output, { recursive: true, mode: 0o700 });
if ((await readdir(output)).length) throw Error('fixture_output_must_be_new_or_empty');
// Exclusive retained claim also protects a simultaneous run and interrupted proof.
await writeFile(join(output, 'run.claim'), randomUUID() + '\n', { flag: 'wx', mode: 0o600 });
const owner = 'otziv-system-proof-' + randomUUID(), label = 'com.otziv.system-proof.owner', network = owner + '-net';
const imageRefs = {
  app: appImage, keycloak: keycloakImage,
  mysql: process.env.OTZIV_SYSTEM_FIXTURE_MYSQL_IMAGE || 'mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383',
  postgres: process.env.OTZIV_SYSTEM_FIXTURE_POSTGRES_IMAGE || 'postgres@sha256:a426e44bac0b759c95894d68e1a0ac03ecc20b619f498a91aae373bf06d8508d',
  // The official Quay mirror serves the identical fixture digest after Docker Hub stopped serving it.
  objects: 'quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e',
  runner: process.env.OTZIV_SYSTEM_FIXTURE_NODE_IMAGE || 'otziv-observer-rollout:20260907'
};
const images = {}, allocated = new Set(), volumes = new Set(); let networkAllocated = false, phase = 'preflight';
let runner, mysql, pg, kc, objects, app, objectVolume, admin;
let secret = { password: 'Fixture-' + randomUUID() + '!aA9', credentialKey: randomBytes(32).toString('base64'),
  disabledIntegrationKey: randomBytes(24).toString('hex'),
  realm: 'restore-' + randomUUID(), username: 'restore-user', credential: 'Credential-' + randomUUID(),
  whatsappWebhook: randomBytes(32).toString('hex'), maxWebhook: randomBytes(32).toString('hex'),
  telegramLink: randomBytes(32).toString('hex'), maxLink: randomBytes(32).toString('hex'),
  dbPassword: randomBytes(24).toString('hex'), s3Access: 'fixture-' + randomBytes(8).toString('hex'), s3Secret: randomBytes(24).toString('hex') };
const recoveryKey = process.env.RECOVERY_ENCRYPTION_KEY_BASE64 ? encryptionKey(process.env.RECOVERY_ENCRYPTION_KEY_BASE64) : randomBytes(32);
const local = await mkdtemp(join(tmpdir(), 'otziv-system-proof-'));
const evidence = { schema: 'otziv-full-system-fixture-v1', startedAt: new Date().toISOString(), production: false,
  productionIndependence: 'NOT_PROVEN', agreedBusinessRpoRto: 'NOT_PROVIDED', releaseApproval: 'NOT_ASSERTED_BY_THIS_DRILL',
  recoveryKeyCustody: process.env.RECOVERY_ENCRYPTION_KEY_BASE64 ? 'OPERATOR_SUPPLIED_ENVIRONMENT' : 'EPHEMERAL_DESTROYED_AFTER_PROOF', checks: [], images };
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const digest = value => createHash('sha256').update(value).digest('hex');
const docker = (args, options = {}) => {
  // Docker resolves NAME from its client's environment. Fixture credentials are
  // never embedded in process arguments, output, or the public manifest.
  const prepared = dockerEnvironment(args, { ...process.env, ...options.env });
  return run('docker', prepared.args, { ...options, env: prepared.env });
};
const stdin = value => Readable.from([value]);
function check(name, condition) { assert.ok(condition, name); evidence.checks.push({ name, passed: true }); console.log('PASS ' + name); }
async function container(role, image, args, command = []) {
  const name = owner + '-' + role;
  await docker(['create', '--pull=never', '--name', name, '--label', `${label}=${owner}`, '--network', network,
    '--security-opt', 'no-new-privileges:true', '--pids-limit', '256', ...args, images[image], ...command]);
  allocated.add(name); await docker(['start', name]); return name;
}
async function removeContainer(name) {
  if (!name || !allocated.has(name)) return;
  assert.equal((await docker(['inspect', '--format', `{{index .Config.Labels "${label}"}}`, name])).trim(), owner);
  await docker(['rm', '-f', '-v', name]); allocated.delete(name);
}
async function createVolume(role) {
  const name = owner + '-' + role; await docker(['volume', 'create', '--label', `${label}=${owner}`, name]); volumes.add(name); return name;
}
async function http(q) {
  if ((await docker(['inspect', '--format', '{{.State.Running}}', runner])).trim() !== 'true') throw Error('fixture_http_runner_exited');
  return JSON.parse(await docker(['exec', '-i', runner, 'node', '/fixture/http.mjs'], { input: stdin(JSON.stringify(q)), timeoutMs: 35000, maxOutput: 5 * 1024 * 1024 }));
}
async function request(path, { method = 'GET', body, token = admin, form, expected = [200, 201, 204] } = {}) {
  const headers = { ...(token ? { Authorization: 'Bearer ' + token } : {}) };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (form) headers['Content-Type'] = 'application/x-www-form-urlencoded';
  const result = await http({ url: 'http://keycloak:8080' + path, options: { method, headers,
    body: form ? new URLSearchParams(form).toString() : body === undefined ? undefined : JSON.stringify(body) } });
  if (!expected.includes(result.status)) throw Error('keycloak_http_' + result.status + '_' + path.replaceAll(secret.realm, 'fixture-realm'));
  return { ...result, value: result.text ? JSON.parse(result.text) : null };
}
const realmPath = () => '/admin/realms/' + secret.realm;
async function login(refresh, offline = false) {
  return (await request('/realms/' + secret.realm + '/protocol/openid-connect/token', { method: 'POST', token: null,
    form: refresh ? { grant_type: 'refresh_token', client_id: 'fixture-mobile', refresh_token: refresh } :
      { grant_type: 'password', client_id: 'fixture-mobile', username: secret.username, password: secret.password, scope: offline ? 'openid offline_access' : 'openid' } })).value;
}
async function adminLogin() {
  admin = (await request('/realms/master/protocol/openid-connect/token', { method: 'POST', token: null,
    form: { grant_type: 'password', client_id: 'admin-cli', username: 'fixture-admin', password: secret.password } })).value.access_token;
}
async function waitHttp(url, timeoutMs = 180000, containerName) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (containerName && (await docker(['inspect', '--format', '{{.State.Running}}', containerName])).trim() !== 'true') throw Error('fixture_service_exited_' + new URL(url).hostname);
    try { if ((await http({ url })).status === 200) return; }
    catch (error) { if (error.message === 'fixture_http_runner_exited') throw error; }
    await pause(1000);
  }
  throw Error('fixture_service_readiness_timeout_' + new URL(url).hostname);
}
async function mysqlSql(sql, allowFailure = false) {
  try { return (await docker(['exec', '-i', '-e', 'MYSQL_PWD=' + secret.dbPassword, mysql, 'mysql', '-uroot', '--batch', '--skip-column-names', 'otziv'], { input: stdin(sql) })).trim(); }
  catch (error) { if (allowFailure) return null; throw error; }
}
const pgSql = sql => docker(['exec', '-i', '-e', 'PGPASSWORD=' + secret.dbPassword, pg, 'psql', '-h', '127.0.0.1', '-U', 'keycloak', '-d', 'keycloak', '-X', '-At', '-v', 'ON_ERROR_STOP=1'], { input: stdin(sql) });
async function startDatabases(suffix) {
  mysql = await container(suffix + '-mysql', 'mysql', ['--network-alias', 'mysql', '--memory', '768m', '-e', 'MYSQL_DATABASE=otziv',
    '-e', 'MYSQL_ROOT_PASSWORD=' + secret.dbPassword, '-e', 'MYSQL_USER=fixture', '-e', 'MYSQL_PASSWORD=' + secret.dbPassword],
    ['mysqld', '--innodb-buffer-pool-size=128M', '--max-connections=50', '--restrict-fk-on-non-standard-key=OFF']);
  pg = await container(suffix + '-pg', 'postgres', ['--network-alias', 'postgres', '--memory', '384m', '-e', 'POSTGRES_DB=keycloak',
    '-e', 'POSTGRES_USER=keycloak', '-e', 'POSTGRES_PASSWORD=' + secret.dbPassword]);
  const deadline = Date.now() + 180000;
  while (Date.now() < deadline) {
    for (const service of [mysql, pg]) {
      if ((await docker(['inspect', '--format', '{{.State.Running}}', service])).trim() !== 'true')
        throw Error('fixture_database_exited_before_readiness');
    }
    // Both entrypoints start a temporary socket-only server while initializing.
    // A successful socket SELECT alone can race its shutdown during restore.
    // Keep MySQL root restricted to its socket; verify networking is enabled
    // before accepting it. PostgreSQL readiness uses authenticated TCP.
    try { if (await mysqlSql('SELECT IF(@@skip_networking, 0, 1)') === '1' && (await pgSql('SELECT 1')).trim() === '1') return; } catch {}
    await pause(1000);
  }
  throw Error('fixture_database_readiness_timeout');
}
async function startKeycloak(suffix) {
  kc = await container(suffix + '-kc', 'keycloak', ['--network-alias', 'keycloak', '--memory', '1100m', '-e', 'JAVA_OPTS_KC_HEAP=-Xms128m -Xmx600m',
    '-e', 'KC_DB=postgres', '-e', 'KC_DB_URL=jdbc:postgresql://postgres:5432/keycloak', '-e', 'KC_DB_USERNAME=keycloak', '-e', 'KC_DB_PASSWORD=' + secret.dbPassword,
    '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME=fixture-admin', '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD=' + secret.password],
    ['start', '--optimized', '--http-enabled=true', '--hostname-strict=false']);
  await waitHttp('http://keycloak:8080/realms/master/.well-known/openid-configuration'); await adminLogin();
}
async function startObjects(suffix, volume) {
  objects = await container(suffix + '-objects', 'objects', ['--network-alias', 'objects', '--memory', '384m',
    '--mount', `type=volume,source=${volume},target=/data`, '-e', 'MINIO_ROOT_USER=' + secret.s3Access, '-e', 'MINIO_ROOT_PASSWORD=' + secret.s3Secret], ['server', '/data']);
  await waitHttp('http://objects:9000/minio/health/live');
}
async function startApp(suffix) {
  const env = {
    SPRING_PROFILES_ACTIVE: 'prod', JAVA_OPTS: '-Xms128m -Xmx800m -XX:MaxMetaspaceSize=320m -Djava.awt.headless=true',
    DATABASE_URL: 'jdbc:mysql://mysql:3306/otziv?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC', MYSQL_DATABASE: 'otziv', MYSQL_USER: 'fixture', MYSQL_PASSWORD: secret.dbPassword,
    KEYCLOAK_ISSUER_URI: `http://keycloak:8080/realms/${secret.realm}`, KEYCLOAK_JWK_SET_URI: `http://keycloak:8080/realms/${secret.realm}/protocol/openid-connect/certs`,
    KEYCLOAK_AUDIENCE: 'fixture-backend', KEYCLOAK_ADMIN_SERVER_URL: 'http://keycloak:8080', KEYCLOAK_ADMIN_REALM: secret.realm,
    KEYCLOAK_ADMIN_CLIENT_ID: 'fixture-backend', KEYCLOAK_ADMIN_CLIENT_SECRET: secret.password,
    OTZIV_SECURITY_SESSION_REVOCATION_MODE: 'enforce', OTZIV_SECURITY_SESSION_REVOCATION_CUTOVER_CONFIRMED: 'true',
    OTZIV_SECURITY_SESSION_REVOCATION_DISPATCH_ENABLED: 'true', OTZIV_SECURITY_ISSUER_GENERATION_REQUIRED: 'true',
    OTZIV_CREDENTIAL_ENCRYPTION_REQUIRED: 'true', OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID: 'fixture-v1',
    OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64: secret.credentialKey, OTZIV_CREDENTIAL_ENCRYPTION_BACKFILL_ENABLED: 'false',
    S3_ACCESS_KEY: secret.s3Access, S3_SECRET_KEY: secret.s3Secret, S3_ENDPOINT: 'http://objects:9000', S3_PUBLIC_BASE_URL: 'http://objects:9000/fixture', S3_REGION: 'us-east-1', S3_BUCKET: 'fixture', S3_PROJECT: 'fixture',
    MAIL_HOST: 'unavailable.invalid', MAIL_USERNAME: 'fixture@example.invalid', MAIL_PASSWORD: secret.password,
    TELEGRAM_BOT_TOKEN: 'fixture-disabled', TELEGRAM_BOT_USERNAME: 'fixture-disabled', TELEGRAM_BOT_REGISTRATION_ENABLED: 'false', TELEGRAM_BOT_SENDING_ENABLED: 'false',
    WHATSAPP_WEBHOOK_SECRET: secret.whatsappWebhook, MAX_BOT_WEBHOOK_SECRET: secret.maxWebhook, TELEGRAM_BOT_LINK_SECRET: secret.telegramLink, MAX_BOT_LINK_SECRET: secret.maxLink,
    OPENAI_API_KEY: secret.disabledIntegrationKey, JWT_SECRET: randomBytes(48).toString('base64'),
    LEAD_TRANSFER_URL: 'http://unavailable.invalid', LEAD_SYNCHRONY_URL: 'http://unavailable.invalid', LEAD_UPDATE_URL: 'http://unavailable.invalid', LEAD_VPS_URL: 'http://unavailable.invalid',
    OTZIV_LEGACY_ENABLED: 'false', BACKUP_SCHEDULE_ENABLED: 'false', OTZIV_PAYMENTS_TBANK_ENABLED: 'false',
    OTZIV_INTEGRATION_OUTBOX_RELAY_ENABLED: 'false', PERFORMERS_NOTIFICATIONS_DISPATCH_ENABLED: 'false', LEAD_COMMANDS_DISPATCH_ENABLED: 'false', LEAD_COMMANDS_RECEIVER_ENABLED: 'false',
    MAX_BOT_WEBHOOK_AUTO_REGISTER_ENABLED: 'false', MAX_BOT_LONG_POLLING_ENABLED: 'false', WHATSAPP_HEALTH_MONITOR_ENABLED: 'false',
    OTZIV_ARCHIVE_ORDERS_APPLY_ENABLED: 'false', OTZIV_ARCHIVE_ORDERS_SCHEDULE_ENABLED: 'false', OTZIV_ANALYTICS_REBUILD_API_ENABLED: 'false',
    OTZIV_ANALYTICS_REBUILD_STARTUP_ENABLED: 'false', OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED: 'false',
    OTZIV_REMINDER_NOTIFICATIONS_ENABLED: 'false', LOGGING_LEVEL_ROOT: 'WARN',
    LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_BOOT_WEB_SERVER_SERVLET_CONTEXT: 'INFO',
    LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_BOOT_TOMCAT: 'INFO',
    LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_ORM_JPA: 'INFO', LOGGING_LEVEL_COM_ZAXXER_HIKARI: 'INFO'
  };
  // JWT secret is part of the recovered configuration as well as credential keys.
  env.JWT_SECRET = secret.jwtSecret || (secret.jwtSecret = env.JWT_SECRET);
  app = await container(suffix + '-app', 'app', ['--network-alias', 'app', '--memory', '1400m', '--tmpfs', '/tmp:rw,nosuid,size=256m',
    ...Object.entries(env).flatMap(([k, v]) => ['-e', k + '=' + v])]);
  await waitHttp('http://app:8080/actuator/health', 300000, app);
}
async function configureRealm() {
  await request('/admin/realms', { method: 'POST', body: { realm: secret.realm, enabled: true, accessTokenLifespan: 900,
    attributes: { 'otziv.security.generation.enabled': 'true' }, adminEventsEnabled: true,
    authenticationFlows: [{ alias: 'otziv-direct', providerId: 'basic-flow', topLevel: true, builtIn: false,
      authenticationExecutions: [{ authenticator: 'direct-grant-validate-username', requirement: 'REQUIRED', priority: 10, authenticatorFlow: false },
        { authenticator: 'otziv-generation-direct-password', requirement: 'REQUIRED', priority: 20, authenticatorFlow: false }] }], directGrantFlow: 'otziv-direct' } });
  const mapper = { name: 'immutable fixture session generation', protocol: 'openid-connect', protocolMapper: 'oidc-usersessionmodel-note-mapper',
    config: { 'user.session.note': 'otziv.security.generation.v1', 'claim.name': 'otziv_session_generation', 'jsonType.label': 'String', 'access.token.claim': 'true', 'introspection.token.claim': 'true' } };
  const audience = { name: 'backend audience', protocol: 'openid-connect', protocolMapper: 'oidc-audience-mapper',
    config: { 'included.custom.audience': 'fixture-backend', 'access.token.claim': 'true' } };
  await request(realmPath() + '/clients', { method: 'POST', body: { clientId: 'fixture-mobile', enabled: true, publicClient: true, directAccessGrantsEnabled: true,
    defaultClientScopes: ['basic', 'profile', 'email', 'roles'], optionalClientScopes: ['offline_access'], protocolMappers: [mapper, audience] } });
  await request(realmPath() + '/clients', { method: 'POST', body: { clientId: 'fixture-backend', enabled: true, publicClient: false,
    secret: secret.password, serviceAccountsEnabled: true, standardFlowEnabled: false, directAccessGrantsEnabled: false } });
  const clients = (await request(realmPath() + '/clients')).value, backend = clients.find(c => c.clientId === 'fixture-backend'), management = clients.find(c => c.clientId === 'realm-management');
  const sa = (await request(realmPath() + '/clients/' + backend.id + '/service-account-user')).value;
  for (const name of ['view-users', 'manage-users']) {
    const role = (await request(realmPath() + '/clients/' + management.id + '/roles/' + name)).value;
    await request(realmPath() + '/users/' + sa.id + '/role-mappings/clients/' + management.id, { method: 'POST', body: [role] });
  }
  const user = await request(realmPath() + '/users', { method: 'POST', body: { username: secret.username, enabled: true,
    email: 'fixture@example.invalid', emailVerified: true, firstName: 'Fixture', lastName: 'Restore', credentials: [{ type: 'password', value: secret.password, temporary: false }] } });
  secret.subject = user.location.split('/').pop();
  await request(realmPath() + '/roles', { method: 'POST', body: { name: 'ADMIN' } });
  const role = (await request(realmPath() + '/roles/ADMIN')).value;
  await request(realmPath() + '/users/' + secret.subject + '/role-mappings/realm', { method: 'POST', body: [role] });
  // The live backend rejects ambiguous same-second credential/session starts.
  await pause(1500);
}
async function api(path, token, extra = {}) {
  return http({ url: 'http://app:8080' + path, ...extra, options: { ...extra.options, headers: { ...extra.options?.headers, Authorization: 'Bearer ' + token } } });
}
async function reveal(token) {
  return api('/api/manager/orders/910001/reviews/910001/credential-reveal', token, { options: { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ field: 'password', sourcePage: 'manager', sourceEntry: 'manager', sourceSection: 'orders' }) } });
}
async function s3(path, { method = 'GET', body, binary = false } = {}) {
  return http({ url: 'http://objects:9000' + path, options: { method, body }, binary, s3: { access: secret.s3Access, secret: secret.s3Secret } });
}
async function dump(args, destination) {
  const prepared = dockerEnvironment(args);
  const child = startProcess('docker', prepared.args, { timeoutMs: 180000, env: prepared.env }); child.child.stdin.end();
  const results = await Promise.allSettled([pipeline(child.child.stdout, createWriteStream(destination, { flags: 'wx', mode: 0o600 })), child.completed]);
  if (results.some(r => r.status === 'rejected')) throw Error('fixture_component_dump_failed');
}
async function archiveObjects(volume, destination, restore = false) {
  const helper = await container((restore ? 'restore' : 'capture') + '-tar', 'runner', ['--network-alias', 'tar-helper', '--memory', '128m', '--entrypoint', 'sh',
    '--mount', `type=volume,source=${volume},target=/data${restore ? '' : ',readonly'}`], ['-c', 'sleep 600']);
  try {
    if (restore) await docker(['exec', '-i', helper, 'tar', '-xf', '-', '-C', '/data'], { input: createReadStream(destination) });
    else await dump(['exec', helper, 'tar', '-cf', '-', '-C', '/data', '.'], destination);
  } finally { await removeContainer(helper); }
}
try {
  await mkdir(output, { recursive: true, mode: 0o700 });
  await assertLocalDocker();
  evidence.sourceSha256 = {};
  for (const name of ['full-system-fixture.mjs', 'system-http-bridge.mjs', 'system-bundle.mjs', 'docker-environment.mjs', 'process.mjs', 'application-shutdown.mjs'])
    evidence.sourceSha256[name] = digest(await readFile(new URL('./' + name, import.meta.url)));
  for (const [name, ref] of Object.entries(imageRefs)) images[name] = (await docker(['image', 'inspect', '--format', '{{.Id}}', ref])).trim();
  await docker(['network', 'create', '--internal', '--label', `${label}=${owner}`, network]); networkAllocated = true;
  runner = await container('runner', 'runner', ['--read-only', '--cap-drop', 'ALL', '--memory', '192m', '--entrypoint', 'node',
    '--mount', `type=bind,source=${fileURLToPath(new URL('./system-http-bridge.mjs', import.meta.url))},target=/fixture/http.mjs,readonly`], ['-e', 'setInterval(()=>{},100000)']);
  phase = 'source-start'; console.log('PHASE source services');
  await startDatabases('source'); await startKeycloak('source'); await configureRealm();
  objectVolume = await createVolume('source-objects'); await startObjects('source', objectVolume);
  assert.equal((await s3('/fixture', { method: 'PUT' })).status, 200);
  assert.equal((await s3('/fixture?versioning', { method: 'PUT', body: '<VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Status>Enabled</Status></VersioningConfiguration>' })).status, 200);
  console.log('PHASE source application migrations'); await startApp('source');
  phase = 'source-mutations';
  const nonce = randomBytes(12), cipher = createCipheriv('aes-256-gcm', Buffer.from(secret.credentialKey, 'base64'), nonce); cipher.setAAD(Buffer.from('enc:v1:fixture-v1'));
  const credentialEnvelope = 'enc:v1:fixture-v1:' + Buffer.concat([nonce, cipher.update(secret.credential), cipher.final(), cipher.getAuthTag()]).toString('base64url');
  await mysqlSql(`INSERT INTO users(id,username,password,fio,email,phone_number,active,keycloak_id,auth_provider,owner_control_view_mode,row_version,auth_epoch) VALUES(910001,'${secret.username}',NULL,'Fixture Restore','fixture@example.invalid','fixture-phone',1,'${secret.subject}','KEYCLOAK','OWN_MANAGERS',0,0);
    INSERT INTO users_roles(user_id,role_id) SELECT 910001,id FROM roles WHERE name='ROLE_ADMIN' LIMIT 1;
    INSERT INTO orders(order_id,order_created,order_changed,order_amount,order_counter,order_sum,order_complete,row_version,client_message_generation) VALUES(910001,CURDATE(),CURDATE(),1,0,10,0,0,1);
    INSERT INTO order_details(order_detail_id,order_detail_order,order_detail_amount,order_detail_price) VALUES(UNHEX('11111111111111111111111111111111'),910001,1,10);
    INSERT INTO bots(bot_id,bot_login,bot_password,bot_fio,bot_active,bot_counter) VALUES(910001,'fixture-account','${credentialEnvelope}','Fixture account',1,0);
    INSERT INTO reviews(review_id,review_text,review_order_details,review_bot,review_created,review_changed,review_publish,row_version) VALUES(910001,'Restorable fixture review',UNHEX('11111111111111111111111111111111'),910001,CURDATE(),CURDATE(),0,0);`);
  secret.beforeTokens = await login(); secret.offlineTokens = await login(undefined, true);
  check('source_actual_login_and_local_subject_binding', (await api('/api/me', secret.beforeTokens.access_token)).status === 200);
  check('source_actual_refresh', (await api('/api/me', (await login(secret.beforeTokens.refresh_token)).access_token)).status === 200);
  let response = await reveal(secret.beforeTokens.access_token);
  check('source_application_decrypts_encrypted_credential', response.status === 200 && JSON.parse(response.text).value === secret.credential);
  check('mysql_contains_ciphertext_not_plaintext', await mysqlSql('SELECT bot_password FROM bots WHERE bot_id=910001') === credentialEnvelope);
  response = await api('/api/manager/orders/910001/reviews/910001/photo', secret.beforeTokens.access_token, { options: { method: 'POST' },
    multipart: { base64: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=' } });
  check('source_application_uploads_real_object', response.status === 200);
  secret.objectUrl = await mysqlSql('SELECT review_url FROM reviews WHERE review_id=910001');
  const objectResult = await s3(new URL(secret.objectUrl).pathname, { binary: true });
  check('source_object_has_exact_version_and_bytes', objectResult.status === 200 && !!objectResult.versionId && objectResult.bytes > 0);
  secret.object = { versionId: objectResult.versionId, sha256: objectResult.sha256, bytes: objectResult.bytes };
  secret.generationBefore = JSON.parse(Buffer.from(secret.beforeTokens.access_token.split('.')[1], 'base64url')).otziv_session_generation;
  const captureStartedAt = new Date().toISOString(); phase = 'capture-fence';
  const appPid1 = (await docker(['exec', app, 'cat', '/proc/1/comm'])).trim();
  const stopStarted = Date.now();
  await docker(['stop', '--time', '60', app]);
  const stoppedApp = JSON.parse(await docker(['inspect', '--format', '{{json .State}}', app]));
  const shutdownLogs = await docker(['logs', app], { maxOutput: 8 * 1024 * 1024 });
  evidence.applicationShutdown = { pid1: appPid1, elapsedMs: Date.now() - stopStarted, exitCode: stoppedApp.ExitCode,
    oomKilled: stoppedApp.OOMKilled, running: stoppedApp.Running, logsSha256: digest(shutdownLogs),
  };
  Object.assign(evidence.applicationShutdown, verifyApplicationShutdown(evidence.applicationShutdown, shutdownLogs, 60000));
  check('application_sigterm_drains_without_forced_kill', true);
  await docker(['stop', '--time', '30', kc, objects]);
  await mysqlSql('SET GLOBAL super_read_only=ON;');
  await pgSql("ALTER DATABASE keycloak SET default_transaction_read_only=on;");
  const fence = { appStopped: false, keycloakStopped: false, objectsStopped: false,
    mysqlReadOnly: await mysqlSql('SELECT @@global.super_read_only') === '1',
    postgresReadOnly: (await pgSql('SHOW transaction_read_only')).trim() === 'on',
    writerConnectionsAbsent: await mysqlSql("SELECT COUNT(*) FROM information_schema.processlist WHERE USER='fixture'") === '0' &&
      (await pgSql("SELECT COUNT(*) FROM pg_stat_activity WHERE datname='keycloak' AND pid<>pg_backend_pid() AND backend_type='client backend'")).trim() === '0',
    internalNetwork: (await docker(['network', 'inspect', '--format', '{{.Internal}}', network])).trim() === 'true' };
  for (const [key, name] of [['appStopped', app], ['keycloakStopped', kc], ['objectsStopped', objects]]) fence[key] = (await docker(['inspect', '--format', '{{.State.Running}}', name])).trim() === 'false';
  check('actual_writer_stop_and_database_capture_fences', Object.values(fence).every(v => v));
  const files = Object.fromEntries(['mysql', 'keycloak', 'objects', 'secrets'].map(name => [name, join(local, name + '.capture')]));
  await dump(['exec', '-e', 'MYSQL_PWD=' + secret.dbPassword, mysql, 'mysqldump', '-uroot', '--single-transaction', '--skip-lock-tables', '--routines', '--triggers', '--events', '--hex-blob', '--set-gtid-purged=OFF', '--no-tablespaces', 'otziv'], files.mysql);
  await dump(['exec', pg, 'pg_dump', '-U', 'keycloak', '-d', 'keycloak', '--format=custom', '--no-owner', '--no-acl'], files.keycloak);
  await archiveObjects(objectVolume, files.objects);
  await writeFile(files.secrets, JSON.stringify(secret), { flag: 'wx', mode: 0o600 });
  evidence.bundle = await sealSystemBundle(join(output, 'bundle'), files, { production: false, scope: 'isolated-real-full-system-fixture',
    captureStartedAt, captureFinishedAt: new Date().toISOString(), fence, images,
    databaseVersions: { mysql: await mysqlSql('SELECT VERSION()'), postgres: (await pgSql('SHOW server_version')).trim(),
      latestFlyway: await mysqlSql('SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1') },
    sessionRecoveryPolicy: 'invalidate-online-and-offline-sessions-rotate-signing-keys-before-starting-application' }, recoveryKey);
  check('four_actual_components_sealed_in_one_authenticated_manifest', evidence.bundle.components === 4);
  // Destroy source data containers before allocating replacements. Restore reads
  // only the authenticated bundle; no live source database or source object volume.
  for (const name of [app, kc, objects, mysql, pg]) await removeContainer(name);
  await docker(['volume', 'rm', objectVolume]); volumes.delete(objectVolume);
  secret = null; admin = null;
  for (const path of Object.values(files)) await rm(path);
  phase = 'authenticated-restore'; console.log('PHASE authenticated component restore');
  evidence.restoreStartedAt = new Date().toISOString();
  const restored = await openSystemBundle(join(output, 'bundle'), join(local, 'restore'), recoveryKey, images);
  secret = JSON.parse(await readFile(restored.files.secrets, 'utf8'));
  assert.deepEqual(restored.metadata.images, images);
  phase = 'restore-database-readiness';
  await startDatabases('restored');
  phase = 'restore-mysql';
  await docker(['exec', '-i', '-e', 'MYSQL_PWD=' + secret.dbPassword, mysql, 'mysql', '-uroot', 'otziv'], { input: createReadStream(restored.files.mysql), timeoutMs: 180000 });
  phase = 'restore-postgres';
  await docker(['exec', '-i', '-e', 'PGPASSWORD=' + secret.dbPassword, pg, 'pg_restore', '-h', '127.0.0.1', '-U', 'keycloak', '-d', 'keycloak', '--exit-on-error', '--no-owner', '--no-acl'], { input: createReadStream(restored.files.keycloak), timeoutMs: 180000 });
  phase = 'restore-objects';
  objectVolume = await createVolume('restored-objects'); await archiveObjects(objectVolume, restored.files.objects, true);
  check('actual_mysql_postgres_and_versioned_object_volume_restored', true);
  await startKeycloak('restored');
  check('paired_subject_identity_survives_both_database_restores', await mysqlSql('SELECT keycloak_id FROM users WHERE id=910001') === secret.subject &&
    (await request(realmPath() + '/users/' + secret.subject)).value.username === secret.username);
  phase = 'restored-session-gate';
  // No application is running yet; the entire network still has no ingress.
  const oldKid = JSON.parse(Buffer.from(secret.beforeTokens.access_token.split('.')[0], 'base64url')).kid;
  await request(realmPath() + '/logout-all', { method: 'POST' });
  const clients = (await request(realmPath() + '/clients')).value, mobile = clients.find(c => c.clientId === 'fixture-mobile');
  const offline = (await request(realmPath() + '/users/' + secret.subject + '/offline-sessions/' + mobile.id)).value;
  for (const session of offline) await request(realmPath() + '/sessions/' + session.id + '?isOffline=true', { method: 'DELETE', expected: [204, 404] });
  const components = (await request(realmPath() + '/components?type=org.keycloak.keys.KeyProvider')).value;
  for (const component of components.filter(c => c.providerId === 'rsa-generated')) await request(realmPath() + '/components/' + component.id, { method: 'DELETE' });
  const realmId = (await request(realmPath())).value.id;
  await request(realmPath() + '/components', { method: 'POST', body: { name: 'restored-signing-key', providerId: 'rsa-generated', providerType: 'org.keycloak.keys.KeyProvider', parentId: realmId,
    config: { priority: ['200'], enabled: ['true'], active: ['true'], algorithm: ['RS256'], keySize: ['2048'] } } });
  check('old_key_removed_before_application_starts', !(await request('/realms/' + secret.realm + '/protocol/openid-connect/certs', { token: null })).value.keys.some(k => k.kid === oldKid));
  for (const [kind, tokens] of [['online', secret.beforeTokens], ['offline', secret.offlineTokens]]) {
    const r = await request('/realms/' + secret.realm + '/protocol/openid-connect/token', { method: 'POST', token: null, expected: [400],
      form: { grant_type: 'refresh_token', client_id: 'fixture-mobile', refresh_token: tokens.refresh_token } });
    check('restored_' + kind + '_refresh_session_invalidated_before_application', r.status === 400);
  }
  await startObjects('restored', objectVolume); await startApp('restored');
  phase = 'restored-application-proof';
  check('old_access_token_is_still_unexpired_when_revocation_is_tested',
    JSON.parse(Buffer.from(secret.beforeTokens.access_token.split('.')[1], 'base64url')).exp > Math.ceil(Date.now() / 1000) + 30);
  check('restored_old_access_token_denied_by_real_application', (await api('/api/me', secret.beforeTokens.access_token)).status === 401);
  const fresh = await login();
  check('restored_actual_login_with_recovered_issuer_and_backend', (await api('/api/me', fresh.access_token)).status === 200);
  check('restored_actual_refresh_is_accepted_by_backend', (await api('/api/me', (await login(fresh.refresh_token)).access_token)).status === 200);
  response = await reveal(fresh.access_token);
  check('restored_application_decrypts_with_recovered_key', response.status === 200 && JSON.parse(response.text).value === secret.credential);
  const restoredUrl = await mysqlSql('SELECT review_url FROM reviews WHERE review_id=910001');
  const restoredObject = await s3(new URL(restoredUrl).pathname + '?versionId=' + encodeURIComponent(secret.object.versionId), { binary: true });
  check('restored_database_reference_resolves_exact_original_object_version', restoredUrl === secret.objectUrl && restoredObject.status === 200 &&
    restoredObject.sha256 === secret.object.sha256 && restoredObject.bytes === secret.object.bytes && restoredObject.versionId === secret.object.versionId);
  check('strict_credential_audit_is_written_after_restore', Number(await mysqlSql("SELECT COUNT(*) FROM business_audit_events WHERE actor='restore-user'")) > 0);
  evidence.restoreFinishedAt = new Date().toISOString();
  evidence.observedRestoreSeconds = Math.ceil((Date.parse(evidence.restoreFinishedAt) - Date.parse(evidence.restoreStartedAt)) / 1000);
  evidence.observedRecoveryPointAgeSeconds = Math.ceil((Date.parse(evidence.restoreFinishedAt) - Date.parse(restored.metadata.captureFinishedAt)) / 1000);
  evidence.result = 'PASS'; evidence.fullSystemRestore = 'PROVEN_FOR_THIS_REAL_ISOLATED_FIXTURE';
} catch (error) {
  evidence.result = 'FAIL'; evidence.phase = phase; evidence.error = String(error.message).slice(0, 180);
  // Logs are private fixture-only diagnostics; never dump tokens/configuration.
  for (const [kind, name] of [['app', app], ['keycloak', kc]]) if (allocated.has(name)) {
    try { let log = await docker(['logs', '--tail', '100', name], { maxOutput: 200000 });
      for (const value of Object.values(secret || {}).filter(v => typeof v === 'string' && v.length > 6)) log = log.replaceAll(value, '[REDACTED]');
      await writeFile(join(output, kind + '-diagnostic.log'), log, { mode: 0o600 }); } catch {}
  }
} finally {
  phase = 'cleanup'; const cleanupFailures = [];
  for (const name of [...allocated].reverse()) try { await removeContainer(name); } catch { cleanupFailures.push('container'); }
  for (const volume of volumes) try {
    assert.equal((await docker(['volume', 'inspect', '--format', `{{index .Labels "${label}"}}`, volume])).trim(), owner);
    await docker(['volume', 'rm', volume]);
  } catch { cleanupFailures.push('volume'); }
  if (networkAllocated) try {
    assert.equal((await docker(['network', 'inspect', '--format', `{{index .Labels "${label}"}}`, network])).trim(), owner);
    await docker(['network', 'rm', network]);
  } catch { cleanupFailures.push('network'); }
  recoveryKey.fill(0); secret = null;
  await rm(local, { recursive: true, force: true });
  evidence.cleanup = cleanupFailures.length ? 'FAIL' : 'PASS'; if (cleanupFailures.length) evidence.result = 'FAIL';
  evidence.finishedAt = new Date().toISOString();
  evidence.elapsedSeconds = Math.ceil((Date.parse(evidence.finishedAt) - Date.parse(evidence.startedAt)) / 1000);
  await mkdir(output, { recursive: true }); await writeFile(join(output, 'proof.json'), JSON.stringify(evidence, null, 2) + '\n', { mode: 0o600 });
  console.log(JSON.stringify({ result: evidence.result, checks: evidence.checks.length, cleanup: evidence.cleanup, phase: evidence.phase, error: evidence.error }));
  if (evidence.result !== 'PASS') process.exitCode = 1;
}
