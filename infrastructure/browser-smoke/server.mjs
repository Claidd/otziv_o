import { createServer } from 'node:http';
import { stat, readFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const repository = fileURLToPath(new URL('../../', import.meta.url));
const contentTypes = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const servers = [];
for (const [application, port] of [['frontend', 43171], ['mobile', 43172]]) {
  const root = resolve(repository, application, 'dist', application, 'browser');
  await stat(resolve(root, 'index.html')); // Refuse an absent build; never substitute a mock application.
  const server = createServer(async (request, response) => {
    try {
      if (request.method !== 'GET' && request.method !== 'HEAD') {
        response.writeHead(405).end(); return;
      }
      const pathname = decodeURIComponent(new URL(request.url, `http://127.0.0.1:${port}`).pathname);
      const requested = resolve(root, `.${pathname}`);
      if (requested !== root && !requested.startsWith(root + sep)) {
        response.writeHead(403).end(); return;
      }
      if (pathname.startsWith('/api/') || pathname.startsWith('/keycloak/')) {
        // APIs exist only in explicit per-test browser routes. No proxy fallback.
        response.writeHead(501, { 'content-type': 'application/json' }).end('{"error":"unmocked_fixture_request"}'); return;
      }
      let file = requested;
      try { if (!(await stat(file)).isFile()) file = resolve(root, 'index.html'); }
      catch { file = extname(pathname) ? null : resolve(root, 'index.html'); }
      if (!file) { response.writeHead(404).end(); return; }
      response.writeHead(200, { 'content-type': contentTypes[extname(file)] || 'application/octet-stream', 'cache-control': 'no-store' });
      response.end(request.method === 'HEAD' ? undefined : await readFile(file));
    } catch { response.writeHead(500).end(); }
  });
  await new Promise((accept, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', accept); });
  servers.push(server);
}
console.log('Built web/mobile fixture servers ready on loopback only');
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  for (const server of servers) server.close();
  setTimeout(() => process.exit(0), 500).unref();
});
