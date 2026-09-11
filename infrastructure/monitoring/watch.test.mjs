import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { runMonitor, evaluateSignals, requestJson, validateConfiguration } from './watch.mjs';
import { buildSignals, latestVerified } from './export-signals.mjs';

const now = Date.parse('2026-09-07T01:00:00Z');
const env = { MONITOR_ALERT_TOKEN: 'fixture-alert-token', MONITOR_WATCHDOG_TOKEN: 'fixture-watchdog-token', MONITOR_SIGNALS_TOKEN: 'fixture-signals-token' };
function signals() {
  return { schema: 'otziv-monitor-signals-v1', heartbeatAt: new Date(now).toISOString(),
    backups: Object.fromEntries(['mysql', 'postgres'].map(name => [name, { remoteVerified: true, verifiedAt: new Date(now - 1000).toISOString() }])),
    metrics: { errorRate: 0, latencyP95Ms: 10, saturationRatio: 0.1, oldestDueSeconds: 0, deadCount: 0, unknownCount: 0, oldestCleanupSeconds: 0 } };
}
async function fixture(t) {
  const path = await mkdtemp(join(tmpdir(), 'otziv-monitor-test-'));
  t.after(() => rm(path, { recursive: true, force: true }));
  return { schema: 'otziv-external-monitor-v1', serviceAlias: 'fixture-service', owner: 'fixture-owner', runbook: 'fixture-runbook',
    receiverContract: 'idempotency-key-dedup-v1', outsideApplicationHostConfirmed: true,
    availabilityUrl: 'https://app.example.org/up', signalsUrl: 'https://signals.example.org/signals',
    alertUrl: 'https://alerts.example.org/alerts', watchdogUrl: 'https://independent.example.org/watchdog', stateFile: join(path, 'state.json'),
    thresholds: { httpTimeoutMs: 1000, heartbeatMaxAgeSeconds: 120, backupMysqlMaxAgeSeconds: 3600, backupPostgresMaxAgeSeconds: 3600,
      backupCompletionMaxAgeSeconds: 300,
      repeatAlertSeconds: 600, maximumMaintenanceSeconds: 3600, errorRate: 0.1, latencyP95Ms: 2000, saturationRatio: 0.9,
      oldestDueSeconds: 300, deadCount: 1, unknownCount: 1, oldestCleanupSeconds: 60 } };
}

test('mock HTTP drill: whole host down fires once, deduplicates and resolves', async t => {
  const config = await fixture(t);
  let down = true;
  const events = [], heartbeats = [];
  const server = createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/up' || req.url === '/signals') {
      res.statusCode = down ? 503 : 200;
      return res.end(JSON.stringify(req.url === '/up' ? { status: 'UP' } : signals()));
    }
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    const payload = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (req.url === '/alerts') events.push({ key: req.headers['idempotency-key'], payload });
    else heartbeats.push(payload);
    res.end('{}');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const request = (url, options) => requestJson(`http://127.0.0.1:${server.address().port}${new URL(url).pathname}`, options);
  await runMonitor(config, env, { now, request });
  await runMonitor(config, env, { now: now + 1000, request });
  assert.equal(events.length, 1); assert.deepEqual(events[0].payload.conditionCodes, ['application_unavailable', 'signals_unavailable']);
  down = false;
  await runMonitor(config, env, { now: now + 2000, request });
  assert.equal(events.length, 2); assert.equal(events[1].payload.state, 'resolved');
  assert.equal(heartbeats.length, 3);
  assert.equal(JSON.stringify(events).includes('fixture-alert-token'), false);
});

test('uncertain alert delivery persists the exact event; no watchdog until delivery succeeds', async t => {
  const config = await fixture(t), sent = [];
  let fail = true, pings = 0;
  const request = async (url, options) => {
    if (url === config.availabilityUrl) throw new Error('fixture-host-down');
    if (url === config.signalsUrl) return signals();
    if (url === config.watchdogUrl) { pings++; return {}; }
    sent.push(structuredClone(options));
    if (fail) throw new Error('fixture-ambiguous-ack');
    return {};
  };
  await assert.rejects(runMonitor(config, env, { now, request }));
  const pending = JSON.parse(await readFile(config.stateFile, 'utf8')).pending;
  assert.ok(pending); assert.equal(pings, 0);
  fail = false;
  await runMonitor(config, env, { now: now + 1000, request });
  assert.deepEqual(sent[0].payload, sent[1].payload); assert.equal(sent[0].eventId, sent[1].eventId);
  assert.equal(pings, 1);
});

test('bounded maintenance suppresses firing temporarily then alerts; unbounded silence fails', async t => {
  const config = await fixture(t); let alerts = 0;
  const request = async (url) => {
    if (url === config.availabilityUrl) throw new Error('down');
    if (url === config.signalsUrl) return signals();
    if (url === config.alertUrl) alerts++;
    return {};
  };
  config.maintenance = { reasonReference: 'fixture-ticket', startedAt: new Date(now - 1000).toISOString(), until: new Date(now + 2000).toISOString() };
  assert.equal((await runMonitor(config, env, { now, request })).maintenance, true); assert.equal(alerts, 0);
  await runMonitor(config, env, { now: now + 3000, request }); assert.equal(alerts, 1);
  config.maintenance.until = new Date(now + 24 * 3600 * 1000).toISOString();
  await assert.rejects(runMonitor(config, env, { now, request }), /maintenance/);
});

test('stale backups, missing metrics, clock skew and backlog are actionable independently', async t => {
  const config = await fixture(t), data = signals();
  data.backups.mysql.verifiedAt = new Date(now - 7_200_000).toISOString();
  data.backups.postgres.postVerificationFailure = true;
  data.backups.postgres.postVerificationPending = true;
  data.backups.postgres.verifiedAt = new Date(now - 600_000).toISOString();
  data.metrics.unknownCount = 1;
  delete data.metrics.oldestCleanupSeconds;
  data.heartbeatAt = new Date(now + 120_000).toISOString();
  const codes = evaluateSignals(data, config.thresholds, now);
  for (const code of ['mysql_backup_stale', 'postgres_backup_delivery_or_cleanup_failed', 'postgres_backup_completion_missing', 'unknownCount_threshold',
    'oldestCleanupSeconds_signal_missing', 'heartbeat_stale']) assert.ok(codes.includes(code));
  assert.equal(codes.includes('postgres_backup_stale'), false);
});

test('corrupt durable state and stale lock fail closed without probes or notification', async t => {
  const config = await fixture(t); let requests = 0;
  const request = async () => { requests++; return {}; };
  await writeFile(config.stateFile, '{corrupt');
  await assert.rejects(runMonitor(config, env, { request }), /state_corrupt/);
  await writeFile(`${config.stateFile}.lock`, 'fixture-lock');
  await assert.rejects(runMonitor(config, env, { request }), /stale_lock/);
  assert.equal(requests, 0);
});

test('signal export preserves original verified timestamp and separates later delivery failure', () => {
  const remote = { phase: 'remote-verified', timestampUtc: new Date(now - 5000).toISOString(), sha256: 'fixture-hash', objectKey: 'fixture-object',
    verification: { head: true, download: true, sha256: true, clientSideEnvelopeVerified: true } };
  const completed = { ...remote, phase: 'completed', emailDelivery: { attempted: true, succeeded: false } };
  const result = buildSignals({ schema: 'otziv-runtime-metrics-v1', measuredAt: new Date(now - 2000).toISOString(), values: signals().metrics },
    [remote, completed], [remote]);
  assert.equal(result.backups.mysql.verifiedAt, remote.timestampUtc);
  assert.equal(result.backups.mysql.remoteVerified, true); assert.equal(result.backups.mysql.postVerificationFailure, true);
  assert.equal(result.backups.mysql.postVerificationPending, false);
  assert.equal(result.backups.postgres.postVerificationPending, true);
  assert.equal(result.heartbeatAt, new Date(now - 2000).toISOString());
  assert.equal(latestVerified([{ ...remote, verification: { head: true } }]).remoteVerified, false);
});

test('endpoint, provider, contact and SLO defaults cannot accidentally enable production', async t => {
  const config = await fixture(t);
  const example = JSON.parse(await readFile(new URL('./config.example.json', import.meta.url), 'utf8'));
  assert.throws(() => validateConfiguration(example, env));
  assert.throws(() => validateConfiguration({ ...config, watchdogUrl: 'https://alerts.example.org/watchdog' }, env));
  assert.throws(() => validateConfiguration({ ...config, alertUrl: 'https://user:pass@alerts.example.org/hook' }, env));
  assert.throws(() => validateConfiguration({ ...config, thresholds: { ...config.thresholds, unknownCount: null } }, env));
  assert.throws(() => validateConfiguration(config, {}));
});

test('HTTP response bounds and redirect refusal survive a malformed endpoint', async () => {
  await assert.rejects(requestJson('http://fixture', { timeoutMs: 1000,
    fetchImpl: async () => new Response('x'.repeat(100000), { status: 200 }) }), /too_large/);
  await assert.rejects(requestJson('http://fixture', { timeoutMs: 1000,
    fetchImpl: async (url, options) => { assert.equal(options.redirect, 'error'); return new Response('', { status: 302 }); } }), /status_failed/);
});
