import { requestJson } from './watch.mjs';
import { latestVerified, records } from './export-signals.mjs';

const QUEUES = ['lead','performer','session_revocation','integration_outbox','workload'];
const tokenName = name => typeof name === 'string' && /^[A-Z][A-Z0-9_]{2,79}$/.test(name);
export function validateCollector(config, env = process.env) {
  if (config.schema !== 'otziv-signals-publisher-v1') throw new Error('publisher_schema_invalid');
  for (const [name,min,max] of [['intervalMs',1000,300000],['sourceTimeoutMs',100,30000],['maxSourceAgeSeconds',30,3600]])
    if (!Number.isInteger(config[name]) || config[name]<min || config[name]>max) throw new Error('publisher_bounds_invalid');
  if (!Array.isArray(config.taskSources) || config.taskSources.length<1 || config.taskSources.length>16) throw new Error('task_inventory_missing');
  const seen = new Set();
  for (const source of [config.backend,...config.taskSources]) {
    if (!source || typeof source.alias !== 'string' || !/^[a-z][a-z0-9_-]{0,47}$/.test(source.alias) || seen.has(source.alias)) throw new Error('source_alias_invalid');
    seen.add(source.alias);
    if (source.enabled === false && source !== config.backend) continue;
    if (source.enabled !== true || !tokenName(source.tokenEnv) || !env[source.tokenEnv]) throw new Error('source_config_or_secret_missing');
    const url = new URL(source.url);
    if (url.username || url.password || url.hash || url.search || !['https:','http:'].includes(url.protocol)) throw new Error('source_url_invalid');
    if (url.protocol === 'http:' && !(config.privateHttpHosts || []).includes(url.hostname)) throw new Error('source_plaintext_host_not_allowlisted');
    const expected = source === config.backend ? '/api/internal/monitoring/runtime' : source.kind === 'worker' ? '/api/internal/task-metrics' : source.kind === 'whatsapp' ? '/internal/task-metrics' : null;
    if (url.pathname !== expected) throw new Error('source_path_invalid');
  }
  for (const name of ['mysqlReceiptsFile','postgresReceiptsFile']) if (typeof config[name] !== 'string' || !config[name]) throw new Error('receipt_path_missing');
  return config;
}
function observed(value, now, maxAge) {
  const time = Date.parse(value), age = (now-time)/1000;
  if (!Number.isFinite(age) || age < -30 || age > maxAge) throw new Error('source_observation_stale');
  return time;
}
const finite = value => typeof value === 'number' && Number.isFinite(value) && value>=0;
export async function collectSignals(config, env=process.env, {request=requestJson, readRecords=records, now=Date.now()}={}) {
  validateCollector(config,env);
  const read = (source,header) => request(source.url,{token:env[source.tokenEnv],authHeader:header,timeoutMs:config.sourceTimeoutMs});
  const [backend, tasks, mysql, postgres] = await Promise.all([
    read(config.backend,'X-Otziv-Monitor-Token'),
    Promise.all(config.taskSources.map(async source => source.enabled ? {alias:source.alias,state:'AVAILABLE',metrics:await read(source,'X-Otziv-Internal-Token')} : {alias:source.alias,state:'DISABLED'})),
    readRecords(config.mysqlReceiptsFile),readRecords(config.postgresReceiptsFile)
  ]);
  if (backend?.schema!=='otziv-runtime-observed-v1') throw new Error('runtime_schema_invalid');
  const times=[observed(backend.observedAt,now,config.maxSourceAgeSeconds)];
  const req=backend.requests;
  if (!req || !Number.isSafeInteger(req.samples) || req.samples<0) throw new Error('request_window_invalid');
  times.push(observed(req.observedAt,now,config.maxSourceAgeSeconds));
  const noTraffic=req.state==='NO_TRAFFIC' && req.samples===0 && req.errorRate===null && req.latencyP95Ms===null;
  if (!noTraffic && (req.state!=='AVAILABLE' || !finite(req.errorRate) || req.errorRate>1 || !finite(req.latencyP95Ms) || req.samples===0)) throw new Error('request_window_unavailable');
  if (!finite(backend.saturation?.ratio) || backend.saturation.ratio>1) throw new Error('saturation_unavailable');
  times.push(observed(backend.saturation.observedAt,now,config.maxSourceAgeSeconds));
  if (!Array.isArray(backend.queues) || backend.queues.length!==QUEUES.length || new Set(backend.queues.map(q=>q.name)).size!==QUEUES.length) throw new Error('queue_inventory_invalid');
  let oldest=0,dead=0,unknown=0;
  for (const q of backend.queues) {
    if (!QUEUES.includes(q.name) || q.state!=='AVAILABLE' || typeof q.dispatchEnabled!=='boolean' || !finite(q.backlog) || !finite(q.oldestDueSeconds)) throw new Error('queue_snapshot_unavailable');
    times.push(observed(q.observedAt,now,config.maxSourceAgeSeconds));
    oldest=Math.max(oldest,q.oldestDueSeconds);
    // Null is legal only for a protocol that has no such state. Missing a supported counter is unavailable.
    if ((q.name==='session_revocation' ? q.dead!==null : !finite(q.dead))
        || (['integration_outbox','workload'].includes(q.name) ? q.unknown!==null : !finite(q.unknown))) throw new Error('queue_count_unavailable');
    if (q.dead!==null) {if(!finite(q.dead))throw new Error('queue_count_invalid');dead+=q.dead;}
    if (q.unknown!==null) {if(!finite(q.unknown))throw new Error('queue_count_invalid');unknown+=q.unknown;}
  }
  let cleanup=null;
  for (const task of tasks.filter(source=>source.state==='AVAILABLE')) {
    const m=task.metrics;
    if (m?.schema!=='otziv-task-metrics-v1' || !finite(m.active) || !finite(m.running) || !finite(m.cleanup)
        || m.active!==m.running+m.cleanup || !Number.isSafeInteger(m.limit) || m.limit<1 || m.active>m.limit || !finite(m.oldestCleanupSeconds)
        || ![m.active,m.running,m.cleanup,m.timedOutTotal,m.cancelledTotal,...['busy','draining','not_ready'].map(key=>m.rejectedTotal?.[key])].every(value=>Number.isSafeInteger(value)&&value>=0)
        || typeof m.accepting!=='boolean') throw new Error('task_snapshot_invalid');
    times.push(observed(m.measuredAt,now,config.maxSourceAgeSeconds));
    cleanup=Math.max(cleanup??0,m.oldestCleanupSeconds);
  }
  return {schema:'otziv-monitor-signals-v1',heartbeatAt:new Date(Math.min(...times)).toISOString(),
    metrics:{errorRate:req.errorRate,latencyP95Ms:req.latencyP95Ms,saturationRatio:backend.saturation.ratio,
      oldestDueSeconds:oldest,deadCount:dead,unknownCount:unknown,oldestCleanupSeconds:cleanup},
    observedRequests:req.samples,metricStates:{errorRate:noTraffic?'NO_TRAFFIC':'AVAILABLE',latencyP95Ms:noTraffic?'NO_TRAFFIC':'AVAILABLE',oldestCleanupSeconds:cleanup===null?'DISABLED':'AVAILABLE'},
    taskSources:tasks,queues:backend.queues,backups:{mysql:latestVerified(mysql),postgres:latestVerified(postgres)}};
}
