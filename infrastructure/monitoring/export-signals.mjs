import { readFile, stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

export function latestVerified(records) {
  const verified = records.filter(value => ['remote-verified', 'completed'].includes(value.phase) &&
    ['head', 'download', 'sha256', 'clientSideEnvelopeVerified'].every(flag => value.verification?.[flag] === true) &&
    Number.isFinite(Date.parse(value.timestampUtc)));
  verified.sort((left, right) => Date.parse(right.timestampUtc) - Date.parse(left.timestampUtc));
  const latest = verified[0];
  if (!latest) return { remoteVerified: false, verifiedAt: null, postVerificationFailure: false, postVerificationPending: false };
  const completion = verified.find(value => value.phase === 'completed' && value.sha256 === latest.sha256 &&
    value.objectKey === latest.objectKey && value.objectVersionId === latest.objectVersionId);
  const postVerificationFailure = !!completion && (completion.cleanup === 'FAIL' ||
    (completion.emailDelivery?.attempted === true && completion.emailDelivery?.succeeded === false) ||
    Object.values(completion.temporaryFileCleanup || {}).some(value => value === false));
  return { remoteVerified: true, verifiedAt: latest.timestampUtc, postVerificationFailure, postVerificationPending: !completion };
}

export function buildSignals(metrics, mysqlRecords, postgresRecords) {
  if (metrics.schema !== 'otziv-runtime-metrics-v1' || !Number.isFinite(Date.parse(metrics.measuredAt))) throw new Error('runtime_metrics_invalid');
  const allowed = ['errorRate', 'latencyP95Ms', 'saturationRatio', 'oldestDueSeconds', 'deadCount', 'unknownCount', 'oldestCleanupSeconds'];
  const values = {};
  for (const name of allowed) {
    if (!Number.isFinite(metrics.values?.[name]) || metrics.values[name] < 0) throw new Error('runtime_metric_missing');
    values[name] = metrics.values[name];
  }
  return { schema: 'otziv-monitor-signals-v1', heartbeatAt: metrics.measuredAt, metrics: values,
    backups: { mysql: latestVerified(mysqlRecords), postgres: latestVerified(postgresRecords) } };
}

export async function records(path) {
  if ((await stat(path)).size > 20 * 1024 * 1024) throw new Error('evidence_file_too_large_rotate_required');
  const text = await readFile(path, 'utf8');
  try { const data = JSON.parse(text); return Array.isArray(data) ? data : [data]; }
  catch { return text.split(/\r?\n/).filter(line => line.trim()).map(line => JSON.parse(line)); }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    if (process.argv.length !== 5) throw new Error('usage_export_signals_metrics_mysql_postgres');
    const [metrics, mysql, pg] = await Promise.all(process.argv.slice(2).map(records));
    console.log(JSON.stringify(buildSignals(metrics[0], mysql, pg)));
  } catch (error) {
    console.error(JSON.stringify({ result: 'FAIL', code: /^[a-z_]+$/.test(error.message || '') ? error.message : 'signals_export_failed' }));
    process.exitCode = 1;
  }
}
