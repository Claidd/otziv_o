import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { join } from 'node:path';
import { readFile, writeFile, appendFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const json = value => JSON.stringify(value);
const requestJson = body => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: json(body) });
const parsed = response => { assert.equal(response.status, 200); return JSON.parse(response.text); };
const identity = () => ({});

const prometheus = {
  port: 9090, ready: '/-/ready', uid: 65534, gid: 65534, memory: '768m', dataPath: '/prometheus', environment: identity,
  command: ['--config.file=/etc/prometheus/prometheus.yml', '--storage.tsdb.path=/prometheus', '--storage.tsdb.retention.time=7d', '--web.enable-lifecycle'],
  mounts: directory => [[join(directory, 'prometheus/prometheus.yml'), '/etc/prometheus/prometheus.yml']],
  async seed(c, phase) {
    c.setPhase(phase);
    const expression = `fixture_upgrade_value{phase="${phase}"}`;
    const result = await c.until(async () => {
      const r = parsed(await c.http('/api/v1/query?query=' + encodeURIComponent(expression))).data.result;
      return r[0] || null;
    }, 'prometheus_scrape_' + phase, 60000);
    return { expression, timestamp: result.value[0], value: phase === 'before' ? '17' : '29' };
  },
  async verify(c, record) {
    const data = parsed(await c.http('/api/v1/query?' + new URLSearchParams({ query: record.expression, time: String(record.timestamp + 0.001) }))).data.result;
    assert.equal(data.length, 1); assert.equal(data[0].value[1], record.value);
  },
  async absent(c, record) {
    return parsed(await c.http('/api/v1/query?' + new URLSearchParams({ query: record.expression, time: String(record.timestamp + 0.001) }))).data.result.length === 0;
  },
  async flush() { /* Graceful stop closes the real TSDB WAL before copying. */ }
};

const grafana = {
  port: 3000, ready: '/api/health', uid: 472, gid: 0, memory: '768m', dataPath: '/var/lib/grafana',
  environment: password => ({ GF_SECURITY_ADMIN_USER: 'admin', GF_SECURITY_ADMIN_PASSWORD: password, GF_SECURITY_SECRET_KEY: password,
    GF_SERVER_ROOT_URL: 'http://grafana:3000/grafana/', GF_SERVER_SERVE_FROM_SUB_PATH: 'true', GF_METRICS_ENABLED: 'true',
    GF_AUTH_ANONYMOUS_ENABLED: 'false', GF_USERS_ALLOW_SIGN_UP: 'false', GF_ANALYTICS_REPORTING_ENABLED: 'false', GF_ANALYTICS_CHECK_FOR_UPDATES: 'false',
    GF_PLUGINS_PREINSTALL_DISABLED: 'true' }),
  command: [],
  mounts: directory => [[join(directory, 'grafana/provisioning'), '/etc/grafana/provisioning'], [join(directory, 'grafana/dashboards'), '/etc/grafana/dashboards']],
  async seed(c, phase) {
    const dashboard = { uid: 'fixture-' + phase, title: 'Upgrade fixture ' + phase, schemaVersion: 39, version: 0,
      panels: [{ id: 1, type: 'text', title: 'Stored marker', options: { mode: 'markdown', content: c.owner + '-' + phase }, gridPos: { h: 4, w: 8, x: 0, y: 0 } }] };
    parsed(await c.http('/grafana/api/dashboards/db', { ...requestJson({ dashboard, overwrite: false }), headers: { ...c.auth, 'Content-Type': 'application/json' } }));
    if (phase === 'before') {
      const response = await c.http('/grafana/api/datasources', { ...requestJson({ name: 'Fixture credential', uid: 'fixture-credentials', type: 'prometheus', access: 'proxy',
        url: 'http://app:8080', basicAuth: true, basicAuthUser: 'fixture', secureJsonData: { basicAuthPassword: c.password } }), headers: { ...c.auth, 'Content-Type': 'application/json' } });
      assert.equal(response.status, 200);
    }
    return { uid: dashboard.uid, marker: c.owner + '-' + phase };
  },
  async verify(c, record) {
    const dashboard = parsed(await c.http('/grafana/api/dashboards/uid/' + record.uid, { headers: c.auth })).dashboard;
    assert.equal(dashboard.panels[0].options.content, record.marker);
    const datasources = parsed(await c.http('/grafana/api/datasources', { headers: c.auth }));
    for (const uid of ['prometheus', 'loki', 'tempo', 'fixture-credentials']) assert.ok(datasources.some(d => d.uid === uid));
    const query = parsed(await c.http('/grafana/api/datasources/proxy/uid/fixture-credentials/api/v1/query?query=fixture', { headers: c.auth }));
    assert.equal(query.data.result[0].value[1], '17'); // Real HTTP Basic header proves stored secret decryption.
  },
  async absent(c, record) { return (await c.http('/grafana/api/dashboards/uid/' + record.uid, { headers: c.auth })).status === 404; },
  async flush() { /* Graceful stop checkpoints the actual SQLite database. */ }
};

function logQuery(c, record) {
  return '/loki/api/v1/query_range?' + new URLSearchParams({ query: `{fixture="${c.owner}",phase="${record.phase}"}`, start: String(record.timestamp - 1000000n),
    end: String(BigInt(Date.now() + 1000) * 1000000n), limit: '20' });
}
const loki = {
  port: 3100, ready: '/ready', uid: 10001, gid: 10001, memory: '768m', dataPath: '/loki', environment: identity,
  command: ['-config.file=/etc/loki/loki-config.yaml'], mounts: directory => [[join(directory, 'loki/loki-config.yaml'), '/etc/loki/loki-config.yaml']],
  async seed(c, phase) {
    const timestamp = BigInt(Date.now()) * 1000000n, marker = c.owner + '-' + phase;
    const response = await c.http('/loki/api/v1/push', requestJson({ streams: [{ stream: { fixture: c.owner, phase }, values: [[String(timestamp), marker]] }] }));
    assert.equal(response.status, 204); return { phase, timestamp, marker };
  },
  async verify(c, record) {
    await c.until(async () => {
      const data = parsed(await c.http(logQuery(c, record))).data.result;
      return data.some(stream => stream.values.some(value => value[1] === record.marker));
    }, 'loki_stored_log', 45000);
  },
  async absent(c, record) { return parsed(await c.http(logQuery(c, record))).data.result.every(stream => stream.values.every(value => value[1] !== record.marker)); },
  async flush(c) {
    const result = await c.http('/flush', { method: 'POST' }); assert.ok([200, 204].includes(result.status));
    await new Promise(done => setTimeout(done, 3000));
  }
};

const tempo = {
  port: 3200, ready: '/ready', uid: 10001, gid: 10001, memory: '768m', dataPath: '/var/tempo', environment: identity,
  command: ['-config.file=/etc/tempo/tempo.yaml'], mounts: directory => [[join(directory, 'tempo/tempo.yaml'), '/etc/tempo/tempo.yaml']],
  async seed(c, phase) {
    const traceId = randomBytes(16).toString('hex'), marker = c.owner + '-' + phase, now = BigInt(Date.now()) * 1000000n;
    const result = await c.http('/v1/traces', requestJson({ resourceSpans: [{ resource: { attributes: [{ key: 'service.name', value: { stringValue: 'upgrade-fixture' } }] },
      scopeSpans: [{ scope: { name: 'fixture' }, spans: [{ traceId, spanId: randomBytes(8).toString('hex'), name: marker, kind: 1,
        startTimeUnixNano: String(now), endTimeUnixNano: String(now + 1000000n) }] }] }] }), 4318);
    assert.equal(result.status, 200); return { traceId, marker };
  },
  async verify(c, record) {
    await c.until(async () => { const r = await c.http('/api/traces/' + record.traceId, { headers: { Accept: 'application/json' } }); return r.status === 200 && r.text.includes(record.marker); }, 'tempo_stored_trace', 60000);
  },
  async absent(c, record) { return (await c.http('/api/traces/' + record.traceId, { headers: { Accept: 'application/json' } })).status === 404; },
  async flush(c) {
    await new Promise(done => setTimeout(done, 11000));
    const response = await c.http('/flush', { method: 'POST' }); assert.ok([200, 204].includes(response.status));
    await new Promise(done => setTimeout(done, 3000));
  }
};

const alloy = {
  port: 12345, ready: '/-/ready', uid: 0, gid: 0, memory: '768m', dataPath: '/var/lib/alloy/data', environment: identity,
  command: ['run', '--server.http.listen-addr=0.0.0.0:12345', '--storage.path=/var/lib/alloy/data', '/etc/alloy/fixture.alloy'],
  healthCommand: ['/bin/alloy', 'validate', '/etc/alloy/fixture.alloy'],
  async prepare(c, directory) {
    c.cursorDirectory = join(c.output, 'cursor-files'); await mkdir(c.cursorDirectory);
    for (const name of ['app', 'error', 'access', 'debug']) await writeFile(join(c.cursorDirectory, name + '.log'), '');
    c.cursorPath = join(c.cursorDirectory, 'app.log'); c.cursorId = randomBytes(16).toString('hex');
    const original = await readFile(join(directory, 'alloy/config.alloy'), 'utf8');
    // Only the persistence test isolates the file pipeline. Full Docker+file configuration is exercised by consumer-smoke.
    const start = original.indexOf('loki.source.file "backend_logs" {'); assert.ok(start >= 0);
    const config = original.slice(start).replace('http://loki:3100/loki/api/v1/push', 'http://app:8080/loki/api/v1/push');
    c.cursorConfig = join(c.output, 'cursor-fixture.alloy'); await writeFile(c.cursorConfig, config);
    c.report.cursorScope = 'ACTUAL_FILE_POSITIONS_ACROSS_UPGRADE_RESTART_AND_BACKUP_RESTORE';
    c.report.cursorFixtureConfigSHA256 = createHash('sha256').update(config).digest('hex');
  },
  mounts: (_directory, c) => [[c.cursorConfig, '/etc/alloy/fixture.alloy'], [c.cursorDirectory, '/var/log/otziv-app']],
  async counts(c) { return parsed(await c.http('/cursor-evidence', {}, 8080, 'app')); },
  async seed(c, phase) {
    const marker = 'OTZIV_CURSOR_' + c.cursorId + '_' + phase;
    await appendFile(c.cursorPath, marker + '\n');
    if (phase === 'before') c.report.sourceFileBackupSHA256 = createHash('sha256').update(await readFile(c.cursorPath)).digest('hex');
    await c.until(async () => (await this.counts(c))[marker] === 1, 'alloy_read_' + phase, 60000);
    return { marker };
  },
  async verify(c, record) { assert.equal((await this.counts(c))[record.marker], 1, 'file line forwarded exactly once in this fixture'); },
  async flush(c) {
    // The native source writes its positions periodically; also require nonempty persisted cursor data at backup.
    await new Promise(done => setTimeout(done, 11000));
  },
  async verifyAfterStop(c, original, next) { await this.verify(c, original); await this.verify(c, next); },
  async beforeRollback(c, original) {
    const content = original.marker + '\n'; await writeFile(c.cursorPath, content);
    c.report.restoredSourceFileSHA256 = createHash('sha256').update(content).digest('hex');
    assert.equal(c.report.restoredSourceFileSHA256, c.report.sourceFileBackupSHA256);
    assert.equal((await c.http('/cursor-reset', { method: 'POST' }, 8080, 'app')).status, 200);
  },
  async verifyRollback(c, original) {
    // Restoration must resume after the acknowledged original line, not replay it or skip subsequent bytes.
    const next = await this.seed(c, 'rollback'); await this.verify(c, next);
    const counts = await this.counts(c); assert.equal(counts[original.marker] || 0, 0, 'restored positions do not replay acknowledged bytes');
    c.check('restored_cursor_reads_new_append_without_replaying_original', true);
  }
};

export function scenario(component) { return { prometheus, grafana, loki, tempo, alloy }[component]; }
