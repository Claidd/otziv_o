import { createHash, randomUUID } from 'node:crypto';
import { mkdir, open, readFile, rename, unlink, stat } from 'node:fs/promises';
import { dirname, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';

const METRICS = ['errorRate', 'latencyP95Ms', 'saturationRatio', 'oldestDueSeconds', 'deadCount', 'unknownCount', 'oldestCleanupSeconds'];
export function validateConfiguration(config, env = process.env) {
  if (config.schema !== 'otziv-external-monitor-v1' || config.outsideApplicationHostConfirmed !== true) throw new Error('independent_monitor_host_unconfirmed');
  for (const field of ['serviceAlias', 'owner', 'runbook', 'receiverContract']) {
    if (typeof config[field] !== 'string' || !config[field] || /REQUIRED|\.invalid/i.test(config[field])) throw new Error('monitor_owner_contact_or_runbook_missing');
  }
  if (config.receiverContract !== 'idempotency-key-dedup-v1') throw new Error('receiver_dedup_contract_missing');
  for (const field of ['availabilityUrl', 'signalsUrl', 'alertUrl', 'watchdogUrl']) {
    const url = new URL(config[field]);
    if (url.protocol !== 'https:' || url.username || url.password || url.hash || url.search || /\.invalid$/.test(url.hostname)) throw new Error('monitor_endpoint_invalid');
  }
  if (new URL(config.availabilityUrl).hostname === new URL(config.watchdogUrl).hostname ||
      new URL(config.alertUrl).hostname === new URL(config.watchdogUrl).hostname) throw new Error('watchdog_provider_must_be_independent');
  if (!isAbsolute(config.stateFile || '')) throw new Error('monitor_state_path_invalid');
  for (const name of ['MONITOR_ALERT_TOKEN', 'MONITOR_WATCHDOG_TOKEN', 'MONITOR_SIGNALS_TOKEN']) {
    if (!env[name]) throw new Error('monitor_secret_missing');
  }
  for (const name of ['httpTimeoutMs', 'heartbeatMaxAgeSeconds', 'backupMysqlMaxAgeSeconds', 'backupPostgresMaxAgeSeconds',
    'backupCompletionMaxAgeSeconds',
    'repeatAlertSeconds', 'maximumMaintenanceSeconds', ...METRICS]) {
    const value = config.thresholds?.[name];
    if (!Number.isFinite(value) || value <= 0 || value > Number.MAX_SAFE_INTEGER) throw new Error('monitor_policy_threshold_missing');
  }
  if (config.thresholds.httpTimeoutMs > 60_000 || config.thresholds.errorRate > 1 || config.thresholds.saturationRatio > 1) throw new Error('monitor_policy_threshold_invalid');
  return config;
}

export async function requestJson(url, { token, timeoutMs, payload, eventId, authHeader = 'Authorization', fetchImpl = fetch }) {
  if (!['Authorization','X-Otziv-Internal-Token','X-Otziv-Monitor-Token'].includes(authHeader)) throw new Error('auth_header_invalid');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchImpl(url, { method: payload ? 'POST' : 'GET', redirect: 'error', signal: controller.signal,
      headers: { ...(token ? { [authHeader]: authHeader === 'Authorization' ? `Bearer ${token}` : token } : {}),
        ...(payload ? { 'Content-Type': 'application/json', 'Idempotency-Key': eventId } : {}) },
      ...(payload ? { body: JSON.stringify(payload) } : {}) });
    if (!response.ok) throw new Error('http_status_failed');
    if (payload) { await response.body?.cancel(); return {}; }
    const reader = response.body?.getReader();
    if (!reader) throw new Error('response_body_missing');
    let bytes = 0;
    const chunks = [];
    try {
      for (;;) {
        const { done, value } = await reader.read(); if (done) break;
        bytes += value.length;
        if (bytes > 65_536) throw new Error('response_body_too_large');
        chunks.push(Buffer.from(value));
      }
    } finally { await reader.cancel(); }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } finally { clearTimeout(timer); }
}

export function evaluateSignals(signals, thresholds, now = Date.now()) {
  if (signals?.schema !== 'otziv-monitor-signals-v1') return ['signals_invalid'];
  const conditions = [];
  if (signals.queues?.some(queue => queue.countLowerBound === true)) conditions.push('queue_counts_capped');
  const age = value => (now - Date.parse(value)) / 1000;
  const heartbeatAge = age(signals.heartbeatAt);
  if (!Number.isFinite(heartbeatAge) || heartbeatAge < -30 || heartbeatAge > thresholds.heartbeatMaxAgeSeconds) conditions.push('heartbeat_stale');
  for (const [name, maximum] of [['mysql', thresholds.backupMysqlMaxAgeSeconds], ['postgres', thresholds.backupPostgresMaxAgeSeconds]]) {
    const receipt = signals.backups?.[name];
    const backupAge = age(receipt?.verifiedAt);
    if (!receipt?.remoteVerified || !Number.isFinite(backupAge) || backupAge < -30 || backupAge > maximum) conditions.push(`${name}_backup_stale`);
    if (receipt?.postVerificationFailure === true) conditions.push(`${name}_backup_delivery_or_cleanup_failed`);
    if (receipt?.postVerificationPending === true && Number.isFinite(backupAge) &&
        backupAge >= thresholds.backupCompletionMaxAgeSeconds) conditions.push(`${name}_backup_completion_missing`);
  }
  for (const name of METRICS) {
    const value = signals.metrics?.[name];
    if (['errorRate','latencyP95Ms'].includes(name) && signals.metricStates?.[name] === 'NO_TRAFFIC'
        && signals.observedRequests === 0 && value === null) continue;
    if (name === 'oldestCleanupSeconds' && signals.metricStates?.[name] === 'DISABLED' && value === null
        && Array.isArray(signals.taskSources) && signals.taskSources.length > 0
        && signals.taskSources.every(source => source.state === 'DISABLED')) continue;
    if (!Number.isFinite(value) || value < 0) conditions.push(`${name}_signal_missing`);
    else if (value >= thresholds[name]) conditions.push(`${name}_threshold`);
  }
  return conditions.sort();
}

async function saveState(path, state) {
  const temp = `${path}.${randomUUID()}.tmp`;
  const handle = await open(temp, 'wx', 0o600);
  try { await handle.writeFile(`${JSON.stringify(state)}\n`); await handle.sync(); } finally { await handle.close(); }
  try {
    await rename(temp, path);
    if (process.platform !== 'win32') { const directory = await open(dirname(path), 'r'); try { await directory.sync(); } finally { await directory.close(); } }
  } catch (error) { await unlink(temp).catch(() => {}); throw error; }
}

export async function runMonitor(config, env = process.env, { request = requestJson, now = Date.now() } = {}) {
  validateConfiguration(config, env);
  await mkdir(dirname(config.stateFile), { recursive: true, mode: 0o700 });
  if (process.platform !== 'win32' && ((await stat(dirname(config.stateFile))).mode & 0o077)) throw new Error('monitor_state_directory_permissions_invalid');
  const lock = await open(`${config.stateFile}.lock`, 'wx', 0o600).catch(() => { throw new Error('monitor_already_running_or_stale_lock'); });
  try {
    let state;
    try { state = JSON.parse(await readFile(config.stateFile, 'utf8')); }
    catch (error) { if (error.code !== 'ENOENT') throw new Error('monitor_state_corrupt'); state = { schema: 'otziv-monitor-state-v1', instance: randomUUID(), generation: 0, notifiedCodes: [], pending: null }; }
    if (state.schema !== 'otziv-monitor-state-v1' || typeof state.instance !== 'string' || !Number.isSafeInteger(state.generation) ||
        !Array.isArray(state.notifiedCodes) || state.notifiedCodes.some(code => typeof code !== 'string') ||
        (state.pending && (!/^[a-f0-9]{64}$/.test(state.pending.id) || !Array.isArray(state.pending.codes) || state.pending.payload?.eventId !== state.pending.id))) throw new Error('monitor_state_corrupt');
    const sendPending = async () => {
      if (!state.pending) return;
      await request(config.alertUrl, { token: env.MONITOR_ALERT_TOKEN, timeoutMs: config.thresholds.httpTimeoutMs,
        payload: state.pending.payload, eventId: state.pending.id });
      state.notifiedCodes = state.pending.codes;
      state.lastDeliveredAt = new Date(now).toISOString();
      state.pending = null;
      await saveState(config.stateFile, state);
    };
    // Pending delivery is durably retried with the SAME event identity and payload after a crash.
    await sendPending();
    const codes = [];
    const probes = await Promise.allSettled([
      request(config.availabilityUrl, { timeoutMs: config.thresholds.httpTimeoutMs }),
      request(config.signalsUrl, { token: env.MONITOR_SIGNALS_TOKEN, timeoutMs: config.thresholds.httpTimeoutMs })
    ]);
    if (probes[0].status !== 'fulfilled' || probes[0].value?.status !== 'UP') codes.push('application_unavailable');
    if (probes[1].status !== 'fulfilled') codes.push('signals_unavailable');
    else codes.push(...evaluateSignals(probes[1].value, config.thresholds, now));
    let maintenance = false;
    if (config.maintenance) {
      const from = Date.parse(config.maintenance.startedAt), to = Date.parse(config.maintenance.until);
      if (!config.maintenance.reasonReference || !Number.isFinite(from) || !Number.isFinite(to) ||
          to <= from || to - from > config.thresholds.maximumMaintenanceSeconds * 1000) throw new Error('maintenance_window_invalid');
      maintenance = from <= now && now < to;
    }
    const desired = [...new Set(codes)].sort();
    const changed = JSON.stringify(desired) !== JSON.stringify(state.notifiedCodes);
    const repeat = desired.length && now - Date.parse(state.lastDeliveredAt || 0) >= config.thresholds.repeatAlertSeconds * 1000;
    if (!maintenance && (changed || repeat)) {
      state.generation++;
      const id = createHash('sha256').update(`${config.serviceAlias}:${state.instance}:${state.generation}`).digest('hex');
      state.pending = { id, codes: desired, payload: { schema: 'otziv-alert-v1', eventId: id,
        service: config.serviceAlias, owner: config.owner, state: desired.length ? 'firing' : 'resolved',
        conditionCodes: desired, observedAt: new Date(now).toISOString(), runbook: config.runbook } };
      await saveState(config.stateFile, state);
      await sendPending();
    }
    // This ping is deliberately last. Probe errors are represented by alerts; delivery/state/config errors
    // prevent a ping, so an independent dead-man service detects a failed monitor or notification channel.
    await request(config.watchdogUrl, { token: env.MONITOR_WATCHDOG_TOKEN, timeoutMs: config.thresholds.httpTimeoutMs,
      payload: { schema: 'otziv-monitor-heartbeat-v1', service: config.serviceAlias, observedAt: new Date(now).toISOString(), maintenance },
      eventId: randomUUID() });
    return { result: 'PASS', conditionCodes: desired, maintenance, deliveryPending: false };
  } finally {
    await lock.close();
    await unlink(`${config.stateFile}.lock`);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    if (process.argv.length !== 3) throw new Error('monitor_config_path_required');
    const config = JSON.parse(await readFile(process.argv[2], 'utf8'));
    console.log(JSON.stringify(await runMonitor(config)));
  } catch (error) {
    const code = /^[a-z_]+$/.test(error.message || '') ? error.message : 'monitor_failed';
    console.error(JSON.stringify({ result: 'FAIL', code })); process.exitCode = 1;
  }
}
