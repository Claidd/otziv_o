'use strict';
const http = require('node:http');

const paths = [
  /^\/_ping$/, /^\/version$/, /^\/info$/, /^\/events$/, /^\/containers\/json$/,
  /^\/containers\/[a-f0-9]{12,64}\/(json|logs|stats)$/,
  /^\/networks$/, /^\/networks\/[a-f0-9]{12,64}$/,
];
const allowedQuery = new Set(['all', 'filters', 'since', 'until', 'follow', 'stdout', 'stderr',
  'timestamps', 'tail', 'stream', 'one-shot', 'size', 'limit', 'verbose', 'scope']);

function authorize(method, raw) {
  // Reject encoded paths, dot segments, absolute URLs and duplicate separators before URL normalization.
  if (!['GET', 'HEAD'].includes(method) || !raw.startsWith('/') || raw.startsWith('//')) return null;
  const [path, query = ''] = raw.split('?');
  const canonical = path.replace(/^\/v\d+\.\d+/, '');
  if (/[%.\\\s#]/.test(canonical) || path.includes('//') || raw.length > 8192) return null;
  // Version dots are accepted only in the exact prefix below.
  if (!paths.some(rule => rule.test(canonical)) || (method === 'HEAD' && canonical !== '/_ping')) return null;
  const params = new URLSearchParams(query);
  for (const key of params.keys()) if (!allowedQuery.has(key)) return null;
  return canonical;
}

const pick = (obj, names) => Object.fromEntries(names.filter(k => obj?.[k] !== undefined).map(k => [k, obj[k]]));
const labels = values => Object.fromEntries(Object.entries(values || {}).filter(([key]) =>
  /^(com\.docker\.compose\.(project|service|container-number)|dozzle\.(name|group|enable))$/.test(key)));
function sanitize(path, value) {
  if (path === '/info') return pick(value, ['ID', 'Name', 'NCPU', 'MemTotal', 'ServerVersion', 'Containers', 'ContainersRunning', 'OSType', 'OperatingSystem', 'Architecture']);
  if (path === '/version') return pick(value, ['Version', 'ApiVersion', 'MinAPIVersion', 'GitCommit', 'Os', 'Arch', 'KernelVersion', 'BuildTime']);
  if (path === '/containers/json') return value.map(c => ({
    ...pick(c, ['Id', 'Names', 'Image', 'ImageID', 'Created', 'State', 'Status', 'Ports']),
    Labels: labels(c.Labels), NetworkSettings: c.NetworkSettings,
  }));
  if (/\/containers\/[^/]+\/json$/.test(path)) return {
    ...pick(value, ['Id', 'Name', 'Created', 'Image', 'RestartCount']),
    State: {...pick(value.State, ['Status', 'Running', 'Paused', 'Restarting', 'Dead', 'StartedAt', 'FinishedAt']),
      ...(value.State?.Health ? {Health: {Status: value.State.Health.Status}} : {})},
    Config: {...pick(value.Config, ['Image', 'Tty']), Labels: labels(value.Config?.Labels)},
    HostConfig: {LogConfig: pick(value.HostConfig?.LogConfig, ['Type'])},
    NetworkSettings: value.NetworkSettings,
  };
  if (path === '/networks' || path.startsWith('/networks/')) {
    const clean = n => pick(n, ['Id', 'Name', 'Scope', 'Driver', 'Internal', 'Ingress']);
    return Array.isArray(value) ? value.map(clean) : clean(value);
  }
  return value;
}

function createProxy(socketPath = '/var/run/docker.sock') {
  return http.createServer((req, res) => {
    const canonical = authorize(req.method, req.url);
    if (!canonical) { res.writeHead(403); res.end('Docker observer endpoint denied'); return; }
    if (req.headers['content-length'] && req.headers['content-length'] !== '0' || req.headers['transfer-encoding']) {
      res.writeHead(400); res.end(); return;
    }
    const upstream = http.request({socketPath, path: req.url, method: req.method, headers: {Host: 'docker'}}, incoming => {
      const filtered = ['/containers/json', '/info', '/version', '/networks'].includes(canonical)
        || /\/containers\/[^/]+\/json$/.test(canonical) || canonical.startsWith('/networks/');
      if (!filtered) {
        // Logs/stats/events stream unchanged. Their contents are already visible to the authorized observer.
        const headers = pick(incoming.headers, ['content-type', 'api-version', 'docker-experimental', 'ostype']);
        res.writeHead(incoming.statusCode, headers);
        incoming.pipe(res);
        incoming.on('error', () => res.destroy());
        return;
      }
      const chunks = []; let size = 0;
      incoming.on('data', chunk => {
        size += chunk.length;
        if (size > 8 * 1024 * 1024) { upstream.destroy(); res.destroy(); }
        else chunks.push(chunk);
      });
      incoming.on('end', () => {
        try {
          if (incoming.statusCode !== 200) { res.writeHead(incoming.statusCode); res.end(); return; }
          const body = JSON.stringify(sanitize(canonical, JSON.parse(Buffer.concat(chunks))));
          res.writeHead(200, {'content-type': 'application/json', 'api-version': incoming.headers['api-version'] || '1.45'});
          res.end(body);
        } catch { res.writeHead(502); res.end(); }
      });
      incoming.on('error', () => res.destroy());
    });
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
    res.on('close', () => upstream.destroy());
    upstream.end();
  });
}

// Container state metrics for Prometheus. A separate listener on a separate network:
// Prometheus never reaches the proxy above, and this one answers only GET /metrics.
function dockerJson(socketPath, path) {
  return new Promise((resolve, reject) => {
    const request = http.get({socketPath, path, headers: {Host: 'docker'}, timeout: 5000}, incoming => {
      const chunks = []; let size = 0;
      incoming.on('data', chunk => {
        size += chunk.length;
        if (size > 8 * 1024 * 1024) request.destroy(new Error('Docker response too large'));
        else chunks.push(chunk);
      });
      incoming.on('end', () => {
        if (incoming.statusCode !== 200) { reject(new Error('Docker status ' + incoming.statusCode)); return; }
        try { resolve(JSON.parse(Buffer.concat(chunks))); } catch (error) { reject(error); }
      });
      incoming.on('error', reject);
    });
    request.on('timeout', () => request.destroy(new Error('Docker request timed out')));
    request.on('error', reject);
  });
}

const metricLabel = value => String(value ?? '').slice(0, 128).replace(/[\\"\n]/g, c => (c === '\n' ? '\\n' : '\\' + c));
const healthStates = ['healthy', 'unhealthy', 'starting'];

// The observer's own Compose project; Docker sets the hostname to the short container id.
// Unknown (no such container or no label) means every project on the host is reported.
async function ownProject(socketPath, selfId) {
  if (!/^[a-f0-9]{12,64}$/.test(selfId || '')) return null;
  try {
    return (await dockerJson(socketPath, `/containers/${selfId}/json`)).Config?.Labels?.['com.docker.compose.project'] || null;
  } catch { return null; }
}

async function containerStates(socketPath, project = null) {
  const listed = await dockerJson(socketPath, '/containers/json?all=1');
  const states = [];
  for (const summary of Array.isArray(listed) ? listed : []) {
    const labels = summary.Labels || {};
    // Compose services only: one-off runs and disposable deploy probes are not long-running services.
    if (!labels['com.docker.compose.service'] || String(labels['com.docker.compose.oneoff']).toLowerCase() === 'true') continue;
    // Other stacks on the same host are not this deployment's services.
    if (project && labels['com.docker.compose.project'] !== project) continue;
    if (!/^[a-f0-9]{12,64}$/.test(summary.Id || '')) continue;
    let inspected;
    try { inspected = await dockerJson(socketPath, `/containers/${summary.Id}/json`); } catch { continue; } // removed meanwhile
    const state = inspected.State || {};
    const started = Date.parse(state.StartedAt);
    states.push({
      project: labels['com.docker.compose.project'], service: labels['com.docker.compose.service'],
      container: String(inspected.Name || (summary.Names || [])[0] || '').replace(/^\//, ''),
      restarts: Number.isInteger(inspected.RestartCount) ? inspected.RestartCount : 0,
      running: state.Running === true, restarting: state.Restarting === true, oomKilled: state.OOMKilled === true,
      // A stopped container keeps its last health status; it is not failing a check any more.
      health: state.Running === true && healthStates.includes(state.Health?.Status) ? state.Health.Status : null,
      startedAt: Number.isFinite(started) && started > 0 ? started / 1000 : null,
    });
  }
  return states;
}

function renderMetrics(states) {
  const lines = [];
  const family = (name, type, help, rows) => {
    lines.push(`# HELP ${name} ${help}`, `# TYPE ${name} ${type}`);
    for (const [labels, value] of rows) {
      lines.push(`${name}{${Object.entries(labels).map(([k, v]) => `${k}="${metricLabel(v)}"`).join(',')}} ${value}`);
    }
  };
  const id = s => ({project: s.project, service: s.service, container: s.container});
  family('otziv_container_restarts_total', 'counter', 'Restarts made by the Docker restart policy since the container was created.',
    states.map(s => [id(s), s.restarts]));
  family('otziv_container_running', 'gauge', 'Whether the container is running.', states.map(s => [id(s), s.running ? 1 : 0]));
  family('otziv_container_restarting', 'gauge', 'Whether Docker is restarting the container.', states.map(s => [id(s), s.restarting ? 1 : 0]));
  family('otziv_container_oom_killed', 'gauge', 'Whether the last exit was an out-of-memory kill.', states.map(s => [id(s), s.oomKilled ? 1 : 0]));
  family('otziv_container_health_status', 'gauge', 'Current healthcheck status; containers without a healthcheck are omitted.',
    states.filter(s => s.health).flatMap(s => healthStates.map(status => [{...id(s), status}, s.health === status ? 1 : 0])));
  family('otziv_container_started_time_seconds', 'gauge', 'Start time of the current container process.',
    states.filter(s => s.startedAt).map(s => [id(s), s.startedAt]));
  return lines.join('\n') + '\n';
}

function createMetricsServer(socketPath = '/var/run/docker.sock', selfId = require('node:os').hostname()) {
  let inFlight = null;
  let project = null;
  const collect = async () => {
    project ??= await ownProject(socketPath, selfId);
    return containerStates(socketPath, project);
  };
  return http.createServer((req, res) => {
    if (req.url !== '/metrics') { res.writeHead(404); res.end(); return; }
    if (req.method !== 'GET') { res.writeHead(405, {allow: 'GET'}); res.end(); return; }
    // Concurrent scrapes share one Docker read.
    inFlight ??= collect().finally(() => { inFlight = null; });
    inFlight.then(states => {
      res.writeHead(200, {'content-type': 'text/plain; version=0.0.4; charset=utf-8'});
      res.end(renderMetrics(states));
    }, () => { res.writeHead(503); res.end('Docker state unavailable\n'); });
  });
}

module.exports = {authorize, sanitize, createProxy, containerStates, renderMetrics, createMetricsServer};
if (require.main === module) {
  const server = createProxy().listen(2375, '0.0.0.0');
  const metrics = createMetricsServer().listen(9323, '0.0.0.0');
  process.on('SIGTERM', () => {
    for (const s of [server, metrics]) { s.close(); s.closeAllConnections(); }
  });
}
