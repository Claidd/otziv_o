import { createServer } from 'node:http';
import { readFile, stat, mkdir, writeFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { resolve, dirname, extname, sep, join } from 'node:path';
import assert from 'node:assert/strict';

// Optional, local acceptance probe. The application stays on an internal Docker
// network. Only three synthetic, read-only HTTP paths are relayed using curl in
// the identified container; response bytes come from its running Spring server.
const args = Object.fromEntries(process.argv.slice(2).reduce((all, value, index, input) => value.startsWith('--') ? [...all, [value.slice(2), input[index + 1]]] : all, []));
if (!args.assets || !args.report || !/^otziv-rollback-[a-f0-9]{10}-recovery$/.test(args.container ?? '') || !/^sha256:[a-f0-9]{64}$/.test(args.image ?? '')) {
  throw new Error('Required: --assets <current bundle> --report <new JSON> --container <owned recovery container> --image <exact sha256 image ID>');
}
const docker = (command) => execFileSync('docker', command, { encoding: 'utf8', timeout: 30_000, windowsHide: true, maxBuffer: 2_000_000, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
const inspection = JSON.parse(docker(['inspect', args.container]))[0];
assert.equal(inspection.Config.Labels['otziv.audit.owner'], 'security_operations');
assert.equal(inspection.Image, args.image);
assert.equal(inspection.State.Running, true);
for (const network of Object.keys(inspection.NetworkSettings.Networks)) {
  assert.equal(JSON.parse(docker(['network', 'inspect', network]))[0].Internal, true);
}
const paths = new Set(['confirmed', 'expired', 'missing'].map(state => `/api/payments/public/rollback-${state}-fixture`));
function applicationGet(path) {
  assert.ok(paths.has(path), 'Only synthetic GET paths are allowed');
  const raw = docker(['exec', args.container, 'curl', '--silent', '--show-error', '--max-time', '15', '--request', 'GET', '--write-out', '\n%{http_code}', `http://127.0.0.1:8080${path}`]);
  const separator = raw.lastIndexOf('\n');
  const body = raw.slice(0, separator); const status = Number(raw.slice(separator + 1));
  const json = JSON.parse(body);
  if (status === 200) assert.equal(json.companyTitle, 'Rollback runtime fixture', 'Never relay customer data');
  return { body, status, json };
}
const assets = resolve(args.assets); const reportFile = resolve(args.report);
const { chromium } = createRequire(new URL('../infrastructure/browser-smoke/package.json', import.meta.url))('@playwright/test');
const report = { startedAt: new Date().toISOString(), status: 'running', container: args.container, image: args.image,
  scope: 'Current unchanged client bundle against actual identified Spring HTTP runtime and synthetic rows on a disposable current-schema clone. GET terminal/error states only; no issuer, provider execution or installed native proof.',
  transport: 'Browser API interception relays allowlisted GET to curl inside the identified container; response bytes are not replaced by fixtures.',
  reads: [], denied: [], mutations: [], checks: [], assets: [] };
for (const name of (await readdir(assets)).filter(name => /\.(html|js|css)$/.test(name)).sort()) {
  report.assets.push({ name, sha256: createHash('sha256').update(await readFile(join(assets, name))).digest('hex') });
}
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2' };
const server = createServer(async (request, response) => {
  try {
    if (request.method !== 'GET') { response.writeHead(405).end(); return; }
    const path = decodeURIComponent(new URL(request.url, 'http://fixture.invalid').pathname);
    let file = resolve(assets, `.${path}`);
    if (file !== assets && !file.startsWith(assets + sep)) { response.writeHead(403).end(); return; }
    try { if (!(await stat(file)).isFile()) file = join(assets, 'index.html'); }
    catch { if (extname(path)) { response.writeHead(404).end(); return; } file = join(assets, 'index.html'); }
    response.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' });
    response.end(await readFile(file));
  } catch { response.writeHead(500).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
let browser;
try {
  browser = await chromium.launch({ headless: true, chromiumSandbox: true });
  report.browser = browser.version();
  const context = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 412, height: 915 }, isMobile: true });
  await context.routeWebSocket('**/*', socket => socket.close());
  await context.route('**/*', async route => {
    const request = route.request(); const url = new URL(request.url());
    if (paths.has(url.pathname) && request.method() === 'GET') {
      const actual = applicationGet(url.pathname);
      report.reads.push({ path: url.pathname, status: actual.status, paymentStatus: actual.json.status ?? null, sha256: createHash('sha256').update(actual.body).digest('hex') });
      return route.fulfill({ status: actual.status, contentType: 'application/json', body: actual.body });
    }
    if (url.origin === origin && !url.pathname.startsWith('/api/') && !url.pathname.startsWith('/keycloak/')) return route.continue();
    report.denied.push({ method: request.method(), origin: url.origin, path: url.pathname });
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) report.mutations.push(url.pathname);
    return route.abort('blockedbyclient');
  });
  const page = await context.newPage();
  await page.goto(origin + '/pay/rollback-confirmed-fixture');
  await page.getByRole('heading', { name: 'Оплата прошла успешно', exact: true }).waitFor({ timeout: 20_000 });
  assert.equal(await page.locator('form.pay-form').count(), 0);
  report.checks.push('actual CONFIRMED response renders success with no payment form');
  await page.goto(origin + '/pay/rollback-expired-fixture');
  await page.getByText('Rollback runtime fixture', { exact: true }).waitFor({ timeout: 20_000 });
  const expiredForm = page.locator('form.pay-form');
  if (await expiredForm.count()) {
    // The web client retains a disabled form for expired links; mobile hides it.
    // Supplying every consent must still leave submission disabled.
    await expiredForm.locator('input[name="email"]').fill('runtime-fixture@example.invalid');
    for (const box of await expiredForm.locator('input[type="checkbox"]').all()) await box.check();
    assert.equal(await expiredForm.locator('button[type="submit"]').isDisabled(), true);
  }
  assert.equal(await page.getByRole('heading', { name: 'Оплата прошла успешно', exact: true }).count(), 0);
  assert.equal(report.reads.at(-1).status, 200);
  assert.equal(report.reads.at(-1).paymentStatus, 'EXPIRED');
  report.checks.push('actual EXPIRED response cannot submit even with consent and shows no false success');
  await page.goto(origin + '/pay/rollback-missing-fixture');
  await page.getByText('Платежная ссылка не найдена', { exact: true }).waitFor({ timeout: 20_000 });
  assert.equal(report.reads.at(-1).status, 404);
  assert.deepEqual(report.mutations, []);
  report.checks.push('actual 404 renders its error; all three paths issue no mutation');
  report.status = 'passed';
} catch (error) { report.status = 'failed'; report.error = String(error); process.exitCode = 1; }
finally {
  await browser?.close(); await new Promise(resolve => server.close(resolve));
  report.completedAt = new Date().toISOString();
  await mkdir(dirname(reportFile), { recursive: true }); await writeFile(reportFile, JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ status: report.status, checks: report.checks, report: reportFile }));
}
