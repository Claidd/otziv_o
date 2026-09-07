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

module.exports = {authorize, sanitize, createProxy};
if (require.main === module) {
  const server = createProxy().listen(2375, '0.0.0.0');
  process.on('SIGTERM', () => { server.close(); server.closeAllConnections(); });
}
