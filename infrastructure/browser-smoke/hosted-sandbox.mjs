import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { promises as fs } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const sha256 = text => createHash('sha256').update(text).digest('hex');

export function hostedPaths(env, revision) {
  assert.equal(env.GITHUB_ACTIONS, 'true', 'Only the disposable GitHub runner is supported');
  assert.equal(env.RUNNER_OS, 'Linux', 'Only the Linux runner is supported');
  for (const key of ['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT']) assert.match(env[key] ?? '', /^[1-9][0-9]*$/);
  assert.match(revision, /^[0-9]+$/);
  for (const key of ['HOME', 'RUNNER_TEMP']) assert.match(env[key] ?? '', /^\/[A-Za-z0-9_./-]+$/);
  for (const key of ['HOME', 'RUNNER_TEMP']) assert.equal(path.posix.normalize(env[key]), env[key], 'Canonical paths are required');
  assert.ok(!env.PLAYWRIGHT_BROWSERS_PATH, 'Custom browser cache paths are not allowed');
  const name = `otziv-playwright-ci-${env.GITHUB_RUN_ID}-${env.GITHUB_RUN_ATTEMPT}`;
  return {
    name,
    executable: `${env.HOME}/.cache/ms-playwright/chromium_headless_shell-${revision}/chrome-headless-shell-linux64/chrome-headless-shell`,
    profile: `/etc/apparmor.d/${name}`,
    state: `${env.RUNNER_TEMP}/${name}.json`,
    input: `${env.RUNNER_TEMP}/${name}.profile`,
  };
}

export function profileText(paths) {
  assert.match(paths.name, /^otziv-playwright-ci-[1-9][0-9]*-[1-9][0-9]*$/);
  assert.match(paths.executable, /^\/[A-Za-z0-9_./-]+\/chromium_headless_shell-[0-9]+\/chrome-headless-shell-linux64\/chrome-headless-shell$/);
  // Canonical's per-application userns permission; no host-wide sysctl change.
  // https://ubuntu.com/blog/ubuntu-23-10-restricted-unprivileged-user-namespaces
  return `abi <abi/4.0>,\ninclude <tunables/global>\nprofile ${paths.name} "${paths.executable}" flags=(default_allow) {\n  userns,\n}\n`;
}

export function assertRendererSandbox({ arguments: args, status, uidMap, profile }, expectedName) {
  assert.ok(Array.isArray(args) && args.length > 0);
  assert.ok(!args.some(value => /^--(?:no-sandbox|disable-(?:setuid|seccomp-filter|namespace)-sandbox)(?:=|$)/.test(value)), 'Chromium sandbox must remain enabled');
  assert.match(status, /^NoNewPrivs:\s+1$/m);
  assert.match(status, /^Seccomp:\s+2$/m);
  const ranges = uidMap.trim().split('\n').map(line => line.trim().split(/\s+/).map(Number));
  assert.ok(ranges.length > 0 && ranges.every(row => row.length === 3 && row.every(Number.isSafeInteger)));
  assert.ok(ranges.every(row => row[2] > 0 && row[2] < 4294967295), 'Renderer must use a restricted user namespace');
  assert.ok(profile.startsWith(`${expectedName} `) || profile.trim() === expectedName, 'Exact executable AppArmor profile must be applied');
}

function sudo(args, input) {
  const result = spawnSync('sudo', ['-n', ...args], { input, encoding: 'utf8', timeout: 30_000, maxBuffer: 256 * 1024 });
  if (result.error || result.status !== 0) throw new Error(`Sandbox setup command failed: ${args[0]} (exit ${result.status ?? 'unavailable'})`);
}

export async function removeOwnedProfile(paths, io = fs, run = sudo) {
  let state;
  try { state = JSON.parse(await io.readFile(paths.state, 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') return 'NO_OWNED_PROFILE'; throw error; }
  assert.equal(state.profile, paths.profile, 'Cleanup target must be the exact owned profile');
  assert.equal(state.sha256, sha256(profileText(paths)), 'Cleanup must match the reviewed profile');
  let installed;
  try { installed = await io.readFile(paths.profile, 'utf8'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (installed !== undefined) {
    assert.equal(sha256(installed), state.sha256, 'Changed profile must not be removed');
    // -R also removes a partially loaded profile; failure is retained, never masked.
    run(['apparmor_parser', '-R', paths.profile]);
    run(['rm', '--', paths.profile]);
  }
  await io.rm(paths.input, { force: true });
  await io.rm(paths.state);
  return 'PASS';
}

export function sandboxProbeLaunchOptions(executable) {
  // Browser.getBrowserCommandLine requires explicit automation mode in Chromium.
  return { executablePath: executable, chromiumSandbox: true, headless: true, args: ['--enable-automation'] };
}

async function proveSandbox(paths) {
  const { chromium } = await import('@playwright/test');
  const browser = await chromium.launch(sandboxProbeLaunchOptions(paths.executable));
  try {
    const context = await browser.newContext({ serviceWorkers: 'block' });
    await context.route('**/*', route => route.abort());
    await context.routeWebSocket('**/*', socket => socket.close());
    const page = await context.newPage();
    await page.setContent('<p>Local sandbox proof</p>');
    const cdp = await browser.newBrowserCDPSession();
    const args = await cdp.send('Browser.getBrowserCommandLine');
    const { processInfo } = await cdp.send('SystemInfo.getProcessInfo');
    const renderer = processInfo.find(process => process.type === 'renderer');
    const host = processInfo.find(process => process.type === 'browser');
    assert.ok(Number.isSafeInteger(renderer?.id) && renderer.id > 0);
    assert.ok(Number.isSafeInteger(host?.id) && host.id > 0);
    const [status, uidMap, profile] = await Promise.all([
      fs.readFile(`/proc/${renderer.id}/status`, 'utf8'),
      fs.readFile(`/proc/${renderer.id}/uid_map`, 'utf8'),
      fs.readFile(`/proc/${host.id}/attr/current`, 'utf8'),
    ]);
    assertRendererSandbox({ ...args, status, uidMap, profile }, paths.name);
    return { result: 'PASS', version: browser.version(), rendererSeccomp: 2, rendererNoNewPrivs: 1, isolatedUserNamespace: true, exactAppArmorProfile: true };
  } finally { await browser.close(); }
}

async function main() {
  assert.equal(process.platform, 'linux');
  assert.equal(process.arch, 'x64');
  assert.notEqual(process.getuid(), 0, 'Browser must run as the unprivileged runner');
  const packageFile = require.resolve('playwright-core/package.json');
  const pkg = JSON.parse(await fs.readFile(packageFile, 'utf8'));
  assert.equal(pkg.version, '1.61.1', 'Review sandbox setup when Playwright changes');
  const browsers = JSON.parse(await fs.readFile(path.join(path.dirname(packageFile), 'browsers.json'), 'utf8'));
  const revision = browsers.browsers.find(browser => browser.name === 'chromium-headless-shell')?.revision;
  const paths = hostedPaths(process.env, revision);
  const mode = process.argv[2];
  if (mode === 'cleanup') {
    console.log(JSON.stringify({ cleanup: await removeOwnedProfile(paths) }));
    return;
  }
  assert.equal(mode, 'prepare');
  assert.equal(await fs.realpath(paths.executable), paths.executable, 'Symlinked browser paths are not supported');
  const executable = await fs.stat(paths.executable);
  assert.ok(executable.isFile() && (executable.mode & 0o111) !== 0 && executable.uid === process.getuid());
  assert.equal((await fs.readFile('/sys/module/apparmor/parameters/enabled', 'utf8')).trim(), 'Y');
  assert.equal((await fs.readFile('/proc/sys/kernel/apparmor_restrict_unprivileged_userns', 'utf8')).trim(), '1', 'Host namespace protection must stay enabled');
  try { await fs.lstat(paths.profile); throw new Error('Existing profile is not owned by this preparation'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const text = profileText(paths);
  await fs.writeFile(paths.state, JSON.stringify({ profile: paths.profile, sha256: sha256(text) }), { flag: 'wx', mode: 0o600 });
  await fs.writeFile(paths.input, text, { flag: 'wx', mode: 0o600 });
  sudo(['install', '-o', 'root', '-g', 'root', '-m', '0644', '--', paths.input, paths.profile]);
  sudo(['apparmor_parser', '-r', paths.profile]);
  const proof = await proveSandbox(paths);
  // Playwright clears test-results on startup; retain this preflight independently.
  await fs.writeFile(path.join(process.env.RUNNER_TEMP, 'otziv-hosted-sandbox-proof.json'), JSON.stringify({ ...proof, executableSha256: sha256(await fs.readFile(paths.executable)), profileSha256: sha256(text) }, null, 2) + '\n');
  console.log(JSON.stringify(proof));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(`Hosted sandbox setup failed: ${error.message}`); process.exitCode = 1; });
}
