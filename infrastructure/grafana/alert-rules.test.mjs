import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, chmodSync, rmSync, mkdirSync, cpSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const provisioning = JSON.parse(read('./provisioning/alerting/otziv-alert-rules.json'));
const rules = provisioning.groups.flatMap(group => group.rules.map(rule => ({ ...rule, group: group.name })));
const byTitle = Object.fromEntries(rules.map(rule => [rule.title, rule]));
const compose = read('../../docker-compose.yaml');
const service = name => compose.match(new RegExp(`^  ${name}:\\r?\\n([\\s\\S]*?)(?=^  [a-zA-Z0-9_-]+:|(?![\\s\\S]))`, 'm'))?.[1] || '';
const imageOf = name => service(name).match(/^    image: (\S+)$/m)?.[1];

function docker(args, input) {
  return spawnSync('docker', ['run', '--rm', '--network', 'none', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', ...args],
    { encoding: 'utf8', timeout: 180_000, input });
}
function fixtureDir(prefix) {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  // Only synthetic test data; the unprivileged image users must read it on Linux.
  chmodSync(dir, 0o755);
  return dir;
}

test('every rule is a threshold on one Prometheus query with Russian text safe for Telegram HTML', () => {
  assert.equal(new Set(rules.map(r => r.uid)).size, rules.length);
  assert.equal(new Set(rules.map(r => r.title)).size, rules.length);
  // Grafana expands $VAR in provisioning files: nothing here may contain a dollar sign.
  assert.ok(!read('./provisioning/alerting/otziv-alert-rules.json').includes('$'));
  for (const rule of rules) {
    const [query, reduce, threshold] = rule.data;
    assert.equal(rule.data.length, 3, rule.title);
    assert.equal(rule.condition, 'C', rule.title);
    assert.equal(query.datasourceUid, 'prometheus', rule.title);
    assert.ok(query.model.instant && !query.model.range, rule.title);
    assert.deepEqual([reduce.model.type, reduce.model.expression, reduce.model.reducer], ['reduce', 'A', 'last'], rule.title);
    assert.equal(threshold.model.type, 'threshold', rule.title);
    assert.equal(threshold.model.expression, 'B', rule.title);
    assert.ok(['critical', 'warning'].includes(rule.labels.severity), rule.title);
    assert.equal(rule.execErrState, 'Error', rule.title);
    assert.ok(['OK', 'Alerting'].includes(rule.noDataState), rule.title);
    for (const text of [rule.annotations.summary, rule.annotations.description]) {
      assert.match(text, /[а-яё]/i, rule.title);
      assert.doesNotMatch(text, /[<>&]/, `${rule.title}: Telegram HTML parse mode`);
      // Templates see expression results only, and NoData leaves them empty: read B inside "with".
      for (const use of text.match(/\.Values[.\w]*/g) || []) assert.equal(use, '.Values.B', rule.title);
      if (text.includes('.Values')) assert.match(text, /\{\{ with \.Values\.B \}\}/, rule.title);
    }
  }
  const template = provisioning.templates.find(t => t.name === 'otziv-telegram').template;
  assert.match(template, /define "otziv\.telegram\.message"/);
  assert.match(template, /\.Alerts\.Firing/);
  assert.match(template, /\.Alerts\.Resolved/);
});

test('audit INF-3 conditions are covered', () => {
  for (const title of ['OtzivAppDown', 'OtzivKeycloakDown', 'OtzivProcessRestartLoop', 'OtzivContainerRestarted',
    'OtzivContainerUnhealthy', 'OtzivMonitoringSourceDown', 'OtzivHostDiskLow', 'OtzivHostMemoryLow', 'OtzivJvmHeapHigh',
    'OtzivBackupFailed', 'OtzivBackupStale', 'OtzivErrorLogBurst']) {
    assert.ok(byTitle[title], title);
  }
  // Prometheus collects what the rules read.
  const prometheus = read('../prometheus/prometheus.yml');
  for (const job of ['app', 'keycloak', 'containers', 'host']) assert.match(prometheus, new RegExp(`job_name: ${job}\\r?\\n`));
  assert.match(prometheus, /docker-observer:9323/);
  assert.match(prometheus, /\/api\/v0\/component\/prometheus\.exporter\.unix\.host\/metrics/);
  assert.match(read('../alloy/config.alloy'), /prometheus\.exporter\.unix "host"/);
  // The metrics listener is reachable only from Prometheus, not from log consumers.
  assert.match(service('docker-observer'), /- observer_metrics_net/);
  assert.match(service('prometheus'), /- observer_metrics_net/);
  for (const name of ['dozzle', 'alloy', 'grafana', 'app']) assert.doesNotMatch(service(name), /observer_metrics_net/, name);
  assert.match(compose, /^  observer_metrics_net:\r?\n    internal: true/m);
});

test('the delivery rules from Prometheus are delivered unchanged through Grafana', () => {
  const source = read('../prometheus/delivery-rules.yml');
  const alerts = [...source.matchAll(/- alert: (\S+)\r?\n\s+expr: (.+)\r?\n\s+for: (\S+)\r?\n\s+labels:\r?\n\s+severity: (\S+)\r?\n\s+annotations:\r?\n\s+summary: "(.+)"\r?\n\s+description: "(.+)"/g)];
  assert.equal(alerts.length, 7);
  for (const [, name, , forDuration, severity, summary, description] of alerts) {
    const rule = byTitle[name];
    assert.ok(rule, name);
    assert.equal(rule.for, forDuration, name);
    assert.equal(rule.labels.severity, severity, name);
    assert.equal(rule.annotations.summary, summary.replaceAll('$labels.', '.Labels.'), name);
    assert.equal(rule.annotations.description, description, name);
  }
});

// The Grafana rule as Prometheus evaluates it: query, threshold, and NoData=Alerting as absent().
function asPrometheusRule(rule) {
  const query = rule.data[0].model.expr;
  const { type, params: [value] } = rule.data.find(node => node.model.type === 'threshold').model.conditions[0].evaluator;
  const compared = `(${query}) ${{ gt: '>', lt: '<' }[type]} ${value}`;
  return { alert: rule.title, expr: rule.noDataState === 'Alerting' ? `(${compared}) or absent(${query})` : compared,
    for: rule.for, labels: rule.labels };
}

test('rule expressions fire on synthetic failures and stay quiet otherwise (pinned promtool)', () => {
  const s = (series, values) => ({ series, values });
  const alert = (name, at, labels = []) => ({ alertname: name, eval_time: at,
    exp_alerts: labels.map(l => ({ exp_labels: { severity: byTitle[name].labels.severity, ...l } })) });
  const app = { job: 'app', instance: 'app:8080' };
  const container = { project: 'otziv-prod', service: 'mysql', container: 'my-mysql', job: 'containers', instance: 'docker-observer:9323' };
  const tests = [
    { interval: '1m', input_series: [s('up{job="app",instance="app:8080"}', '1 1 0x10'), s('up{job="keycloak",instance="kc:9000"}', '1x12')],
      alert_rule_test: [alert('OtzivAppDown', '5m'), alert('OtzivAppDown', '9m', [app]), alert('OtzivKeycloakDown', '9m')] },
    // No series at all, e.g. a lost scrape job, also alerts.
    { interval: '1m', input_series: [s('up{job="keycloak",instance="kc:9000"}', '1x12')],
      alert_rule_test: [alert('OtzivAppDown', '7m', [{ job: 'app' }])] },
    { interval: '1m', input_series: [
        s('process_start_time_seconds{job="app",instance="app:8080"}', '100x5 400x5 700x5'),
        s('process_start_time_seconds{job="keycloak",instance="kc:9000"}', '100x5 400x10')],
      alert_rule_test: [alert('OtzivProcessRestartLoop', '8m'), alert('OtzivProcessRestartLoop', '12m', [app])] },
    { interval: '1m', input_series: [
        s('otziv_container_restarts_total{project="otziv-prod",service="mysql",container="my-mysql",job="containers",instance="docker-observer:9323"}', '0x5 1x30'),
        // Recreated on deploy: the new container starts from 0, which is not a restart.
        s('otziv_container_restarts_total{project="otziv-prod",service="app",container="otziv-prod-app-1",job="containers",instance="docker-observer:9323"}', '3x5 0x30')],
      alert_rule_test: [alert('OtzivContainerRestarted', '4m'), alert('OtzivContainerRestarted', '8m', [container]),
        alert('OtzivContainerRestarted', '25m')] },
    { interval: '1m', input_series: [
        s('otziv_container_health_status{project="otziv-prod",service="mysql",container="my-mysql",status="unhealthy",job="containers",instance="docker-observer:9323"}', '0 1x10'),
        s('otziv_container_health_status{project="otziv-prod",service="app",container="otziv-prod-app-1",status="unhealthy",job="containers",instance="docker-observer:9323"}', '0x11')],
      alert_rule_test: [alert('OtzivContainerUnhealthy', '3m'), alert('OtzivContainerUnhealthy', '5m', [{ ...container, status: 'unhealthy' }])] },
    { interval: '1m', input_series: [
        s('up{job="containers",instance="docker-observer:9323"}', '0x15'), s('up{job="host",instance="alloy:12345"}', '1x15'),
        s('node_filesystem_avail_bytes{job="host",instance="alloy:12345",device="/dev/sda1",mountpoint="/var/lib/alloy/data"}', '5x15'),
        s('node_filesystem_size_bytes{job="host",instance="alloy:12345",device="/dev/sda1",mountpoint="/var/lib/alloy/data"}', '100x15'),
        s('node_filesystem_avail_bytes{job="host",instance="alloy:12345",device="/dev/sdb",mountpoint="/var/log/otziv-app"}', '50x15'),
        s('node_filesystem_size_bytes{job="host",instance="alloy:12345",device="/dev/sdb",mountpoint="/var/log/otziv-app"}', '100x15'),
        s('node_memory_MemAvailable_bytes{job="host",instance="alloy:12345"}', '8x15'),
        s('node_memory_MemTotal_bytes{job="host",instance="alloy:12345"}', '100x15'),
        s('jvm_gc_live_data_size_bytes{job="app",instance="app:8080"}', '95x20'),
        s('jvm_gc_max_data_size_bytes{job="app",instance="app:8080"}', '100x20')],
      alert_rule_test: [
        alert('OtzivMonitoringSourceDown', '11m', [{ job: 'containers', instance: 'docker-observer:9323' }]),
        alert('OtzivHostDiskLow', '9m'), alert('OtzivHostDiskLow', '11m', [{ device: '/dev/sda1' }]),
        alert('OtzivHostMemoryLow', '11m', [{ job: 'host', instance: 'alloy:12345' }]),
        alert('OtzivJvmHeapHigh', '14m'), alert('OtzivJvmHeapHigh', '16m', [app])] },
    { interval: '5m', input_series: [
        s('otziv_backup_failures_total{trigger="daily",job="app",instance="app:8080"}', '0x10 1x40'),
        s('otziv_backup_failures_total{trigger="catch_up",job="app",instance="app:8080"}', '0x50'),
        // Verified at 1h (3600 s); the next receipt never comes.
        s('otziv_backup_last_verified_epoch{database="mysql",job="app",instance="app:8080"}', '3600x400'),
        s('otziv_backup_last_verified_epoch{database="keycloak",job="app",instance="app:8080"}', '0x400')],
      alert_rule_test: [
        alert('OtzivBackupFailed', '45m'), alert('OtzivBackupFailed', '55m', [{ trigger: 'daily', job: 'app', instance: 'app:8080' }]),
        alert('OtzivBackupFailed', '200m'),
        // Older than 27 h for 15 minutes: never-verified keycloak from 27h15m, mysql (verified at 1h) from 28h15m.
        alert('OtzivBackupStale', '27h'),
        alert('OtzivBackupStale', '27h20m', [{ database: 'keycloak', job: 'app', instance: 'app:8080' }]),
        alert('OtzivBackupStale', '28h20m', [{ database: 'keycloak', job: 'app', instance: 'app:8080' },
          { database: 'mysql', job: 'app', instance: 'app:8080' }])] },
    // A steady 3 errors per 15 minutes for a day, then 20 every 5 minutes.
    { interval: '5m', input_series: [s('logback_events_total{level="error",job="app",instance="app:8080"}', '0+1x300 320+20x5')],
      alert_rule_test: [alert('OtzivErrorLogBurst', '1500m'), alert('OtzivErrorLogBurst', '1515m', [{}])] },
    { interval: '1m', input_series: [
        s('otziv_delivery_queue_oldest_seconds{queue="paper",state="QUEUED",job="app",instance="app:8080"}', '400x10'),
        s('otziv_delivery_queue_jobs{queue="paper",state="UNKNOWN",job="app",instance="app:8080"}', '0x10'),
        s('otziv_delivery_queue_expired_leases{queue="paper",job="app",instance="app:8080"}', '0x10'),
        s('otziv_projection_last_success_epoch{projection="manager-control",job="app",instance="app:8080"}', '0x10'),
        s('otziv_projection_enabled{projection="manager-control",job="app",instance="app:8080"}', '1x10')],
      alert_rule_test: [
        alert('ClientDeliveryQueueDelayed', '6m', [{ queue: 'paper', state: 'QUEUED', job: 'app', instance: 'app:8080' }]),
        alert('ClientDeliveryRequiresReconciliation', '6m'), alert('ClientDeliveryLeaseExpired', '6m'),
        alert('ClientDeliveryFailed', '6m'), alert('WhatsAppUnlinkedGroupMessage', '6m'),
        alert('ClientDeliveryInspectionUnavailable', '3m', [{}]),
        alert('InteractiveProjectionRefreshStale', '4m'),
        alert('InteractiveProjectionRefreshStale', '7m', [{ projection: 'manager-control', job: 'app', instance: 'app:8080' }])] },
    // Old terminal failures and parked unlinked messages stay quiet; only a new one alerts, once, and resolves.
    { interval: '1m', input_series: [
        s('otziv_delivery_queue_jobs{queue="manager_client",state="FAILED",job="app",instance="app:8080"}', '7x20 8x70'),
        s('otziv_delivery_queue_jobs{queue="manager_client",state="UNKNOWN",job="app",instance="app:8080"}', '0x90'),
        s('otziv_delivery_queue_oldest_seconds{queue="whatsapp_unlinked",state="QUEUED",job="app",instance="app:8080"}', '200000x90'),
        s('otziv_delivery_queue_jobs{queue="whatsapp_unlinked",state="QUEUED",job="app",instance="app:8080"}', '1x30 2x60')],
      alert_rule_test: [
        alert('ClientDeliveryRequiresReconciliation', '15m'), alert('ClientDeliveryQueueDelayed', '15m'),
        alert('ClientDeliveryFailed', '15m'), alert('WhatsAppUnlinkedGroupMessage', '15m'),
        alert('ClientDeliveryFailed', '25m', [{ queue: 'manager_client', state: 'FAILED', job: 'app', instance: 'app:8080' }]),
        alert('ClientDeliveryFailed', '58m'),
        alert('WhatsAppUnlinkedGroupMessage', '35m', [{ queue: 'whatsapp_unlinked', state: 'QUEUED', job: 'app', instance: 'app:8080' }]),
        alert('WhatsAppUnlinkedGroupMessage', '75m')] },
  ];
  const dir = fixtureDir('otziv-alert-rules-');
  try {
    writeFileSync(join(dir, 'rules.yml'), JSON.stringify({ groups: [{ name: 'grafana', interval: '1m', rules: rules.map(asPrometheusRule) }] }));
    writeFileSync(join(dir, 'tests.yml'), JSON.stringify({ rule_files: ['rules.yml'], evaluation_interval: '1m', tests }));
    for (const name of ['rules.yml', 'tests.yml']) chmodSync(join(dir, name), 0o644);
    const run = docker(['--read-only', '--tmpfs', '/tmp:rw,noexec,nosuid,size=128m', '--mount', `type=bind,source=${dir},target=/fixture,readonly`,
      '--entrypoint', '/bin/promtool', imageOf('prometheus'), 'test', 'rules', '/fixture/tests.yml']);
    assert.equal(run.status, 0, `${run.error ?? ''}\n${run.stdout}\n${run.stderr}`);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('the Grafana entrypoint builds one Telegram integration per admin chat without writing secrets', () => {
  const grafana = service('grafana');
  assert.match(grafana, /entrypoint: \["\/bin\/sh", "\/etc\/otziv\/grafana-alerting-entrypoint\.sh"\]/);
  assert.match(grafana, /alerting-entrypoint\.sh:\/etc\/otziv\/grafana-alerting-entrypoint\.sh:ro/);
  assert.match(grafana, /TELEGRAM_BOT_TOKEN: \$\{TELEGRAM_BOT_TOKEN\}/);
  assert.match(grafana, /TELEGRAM_ADMIN_CHAT_IDS: \*telegram-admin-chat-ids/);
  assert.match(service('app'), /TELEGRAM_ADMIN_CHAT_IDS: &telegram-admin-chat-ids \$\{TELEGRAM_ADMIN_CHAT_IDS:-/);
  assert.doesNotMatch(read('../../compose.prod-local.yaml').match(/^  grafana:\r?\n[\s\S]*?(?=^  [a-z]|(?![\s\S]))/m)[0], /TELEGRAM_BOT_TOKEN/,
    'the local stand never gets the bot token');

  const token = '123456789:' + 'A'.repeat(35);
  const dir = fixtureDir('otziv-grafana-entrypoint-');
  try {
    cpSync(new URL('./provisioning', import.meta.url), join(dir, 'provisioning'), { recursive: true });
    cpSync(new URL('./alerting-entrypoint.sh', import.meta.url), join(dir, 'entrypoint.sh'));
    const render = (env, out) => docker(['--user', '472', '--mount', `type=bind,source=${dir},target=/fixture,readonly`,
      ...Object.entries(env).flatMap(([k, v]) => ['-e', `${k}=${v}`]),
      '--entrypoint', '/bin/sh', imageOf('grafana'), '-c',
      `sh /fixture/entrypoint.sh --render /fixture/provisioning /tmp/out && ls /tmp/out/alerting && cat /tmp/out/alerting/${out} 2>/dev/null; true`]);

    const on = render({ TELEGRAM_BOT_TOKEN: token, TELEGRAM_ADMIN_CHAT_IDS: '111, -100222;bad,111' }, 'otziv-telegram.yaml');
    assert.equal(on.status, 0, on.stderr);
    assert.match(on.stdout, /otziv-alert-rules\.json/);
    assert.equal((on.stdout.match(/type: telegram/g) || []).length, 2);
    assert.match(on.stdout, /chatid: "111"/);
    assert.match(on.stdout, /chatid: "-100222"/);
    assert.match(on.stdout, /bottoken: \$TELEGRAM_BOT_TOKEN\r?\n/);
    assert.match(on.stdout, /message: '\{\{ template "otziv\.telegram\.message" \. \}\}'/);
    assert.match(on.stdout, /receiver: otziv-telegram/);
    assert.ok(!on.stdout.includes(token) && !on.stderr.includes(token));
    assert.ok(!/111|100222/.test(on.stderr), 'chat ids are not logged');
    assert.match(on.stderr, /ignored 1 malformed/);
    assert.match(on.stderr, /delivery is on: 2 admin chat/);

    for (const env of [{ TELEGRAM_ADMIN_CHAT_IDS: '111' }, { TELEGRAM_BOT_TOKEN: 'not-a-token', TELEGRAM_ADMIN_CHAT_IDS: '111' },
      { TELEGRAM_BOT_TOKEN: token, TELEGRAM_ADMIN_CHAT_IDS: ' , bad' }, { TELEGRAM_BOT_TOKEN: token, TELEGRAM_ADMIN_CHAT_IDS: '*' }]) {
      const off = render(env, 'otziv-telegram.yaml');
      assert.equal(off.status, 0, off.stderr);
      assert.match(off.stdout, /otziv-alert-rules\.json/);
      assert.doesNotMatch(off.stdout, /otziv-telegram\.yaml|type: telegram/);
      assert.match(off.stderr, /delivery is off/);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
