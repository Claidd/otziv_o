import { createServer } from 'node:http';
import { readFile, stat, mkdir, writeFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { resolve, dirname, extname, sep, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

// Optional release-artifact gate. It never contacts a backend/provider. Extract
// assets/public from the hash/signature-verified APK, preserving every asset byte.
const args = Object.fromEntries(process.argv.slice(2).reduce((all, value, index, input) => value.startsWith('--') ? [...all, [value.slice(2), input[index + 1]]] : all, []));
if (!args.assets || !args.report || (!args['apk-sha256'] && args['client-kind'] !== 'candidate-build')) throw new Error('Required: --assets <client assets> --apk-sha256 <verified APK hash> (or --client-kind candidate-build) --report <new evidence file>');
const root = resolve(fileURLToPath(new URL('../', import.meta.url)));
const assets = resolve(args.assets);
const reportFile = resolve(args.report);
const { chromium } = createRequire(new URL('../infrastructure/browser-smoke/package.json', import.meta.url))('@playwright/test');
const fixtureFile = args.fixture ? resolve(args.fixture) : join(root, 'contracts/fixtures/public-payment-current.json');
const fixture = JSON.parse(await readFile(fixtureFile, 'utf8'));
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2' };
const report = { startedAt: new Date().toISOString(), status: 'running', apkSha256: args['apk-sha256']?.toUpperCase() ?? null,
  clientKind: args['client-kind'] === 'candidate-build' ? 'candidate-build' : 'archived-apk',
  backendLabel: args['backend-label'] ?? 'candidate',
  scope: 'Unchanged client web assets versus selected backend DTO serialization fixtures; no backend application, native/issuer/provider proof',
  fixtureFile,
  fixtureSha256: createHash('sha256').update(await readFile(fixtureFile)).digest('hex'),
  reads: 0, writes: [], denied: [], unexpectedMutations: [], checks: [], assets: [] };
for (const name of (await readdir(assets)).filter(name => /\.(html|js|css)$/.test(name)).sort()) {
  report.assets.push({ name, sha256: createHash('sha256').update(await readFile(join(assets, name))).digest('hex') });
}
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
  const page = await context.newPage();
  let payment = { ...fixture, token: 'release-fixture', companyTitle: 'Release contract fixture',
    expiresAt: '2099-09-08T12:30:00', paymentPageMode: 'BANK_ONLY', sbpBankSelectionSupported: false, tpayEnabled: false };
  await context.routeWebSocket('**/*', socket => socket.close());
  await context.route('**/*', async route => {
    const request = route.request(); const url = new URL(request.url());
    if (url.pathname === '/api/payments/public/release-fixture' && request.method() === 'GET') {
      report.reads++; return route.fulfill({ json: payment });
    }
    if (url.pathname === '/api/payments/public/release-fixture/init' && request.method() === 'POST') {
      report.writes.push(request.postDataJSON());
      return route.fulfill({ status: 503, json: { message: 'Release fixture: outcome unknown' } });
    }
    if (url.origin === origin && !url.pathname.startsWith('/api/') && !url.pathname.startsWith('/keycloak/')) return route.continue();
    report.denied.push({ method: request.method(), origin: url.origin, path: url.pathname, type: request.resourceType() });
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) report.unexpectedMutations.push(url.pathname);
    return route.abort('blockedbyclient');
  });
  await page.goto(origin + '/pay/release-fixture');
  await page.getByText('Release contract fixture', { exact: true }).waitFor({ timeout: 15_000 });
  const form = page.locator('form.pay-form'); await form.waitFor();
  assert.equal(await form.locator('button[type="submit"]').isDisabled(), true);
  report.checks.push('selected backend DTO renders, payment remains consent-gated');
  await form.locator('input[name="email"]').fill('release-fixture@example.invalid');
  const boxes = await form.locator('input[type="checkbox"]').all(); assert.equal(boxes.length, 3);
  for (const box of boxes) await box.check();
  await form.locator('button[type="submit"]').click();
  await page.getByText('Release fixture: outcome unknown', { exact: true }).waitFor();
  assert.equal(report.writes.length, 1);
  assert.deepEqual(report.writes[0], { email: 'release-fixture@example.invalid', offerConsent: true, privacyConsent: true, receiptConsent: true });
  report.checks.push('client sends the expected consent body once; unknown outcome shows no success');
  payment = { ...payment, status: 'CONFIRMED', payable: false };
  await page.reload();
  await page.getByRole('heading', { name: 'Оплата прошла успешно', exact: true }).waitFor();
  assert.equal(report.writes.length, 1); assert.deepEqual(report.unexpectedMutations, []);
  report.checks.push('confirmed selected backend DTO renders success after a read, without repeating the mutation');
  report.status = 'passed';
} catch (error) { report.status = 'failed'; report.error = String(error); process.exitCode = 1; }
finally {
  await browser?.close(); await new Promise(resolve => server.close(resolve));
  report.completedAt = new Date().toISOString();
  await mkdir(dirname(reportFile), { recursive: true }); await writeFile(reportFile, JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ status: report.status, checks: report.checks, report: reportFile }));
}
