import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

// Mutates synthetic auth state only on the explicitly named disposable emulator/debug fixture.
// No real-device support, install, provider login, token output, or production endpoint access.
const args = Object.fromEntries(process.argv.slice(2).map((value) => {
  const split = value.indexOf('=');
  if (!value.startsWith('--') || split < 0) throw new Error('Use --name=value arguments');
  return [value.slice(2, split), value.slice(split + 1)];
}));
if (!args.adb || !/^emulator-\d+$/u.test(args.serial ?? '') || args.avd !== 'OtzivDisposable'
    || !/^[a-f0-9]{64}$/u.test(args['apk-sha256'] ?? '') || !args.evidence)
  throw new Error('Explicit adb, owned emulator serial, OtzivDisposable AVD, APK hash and evidence directory required');
const evidence = path.resolve(args.evidence);
await mkdir(evidence, { recursive: true });
const adb = (...parameters) => execFileSync(args.adb, ['-s', args.serial, ...parameters], { encoding: 'utf8', timeout: 30_000, windowsHide: true });
const avdName = adb('emu', 'avd', 'name').split(/\r?\n/u).map((line) => line.trim()).filter(Boolean);
if (avdName.join('\n') !== 'OtzivDisposable\nOK') throw new Error('Owned AVD identity mismatch');
const installedPath = adb('shell', 'pm', 'path', 'com.hunt.otziv').trim().replace(/^package:/u, '');
if (!/^\/data\/app\/[^\r\n]+\/base\.apk$/u.test(installedPath)) throw new Error('Unexpected fixture APK path');
const installedApk = path.join(evidence, 'installed-auth-fixture.apk');
adb('pull', installedPath, installedApk);
if (createHash('sha256').update(await readFile(installedApk)).digest('hex') !== args['apk-sha256']) throw new Error('Installed fixture APK hash mismatch');
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const checks = [];
let port, socket, sequence = 0;
const pending = new Map();
async function connect() {
  for (let attempt = 0; attempt < 60; attempt++) {
    try {
      const processId = adb('shell', 'pidof', 'com.hunt.otziv').trim();
      if (!/^\d+$/u.test(processId)) throw new Error('Process not ready');
      port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${processId}`).trim();
      const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(2000) })).json();
      const page = pages.find((value) => value.type === 'page' && value.url.startsWith('https://localhost'));
      if (!page) throw new Error('WebView not ready');
      socket = new WebSocket(page.webSocketDebuggerUrl);
      await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
      socket.onmessage = (event) => {
        const response = JSON.parse(event.data), request = pending.get(response.id);
        if (!request) return;
        pending.delete(response.id); clearTimeout(request.timer);
        response.error ? request.reject(new Error('CDP command failed')) : request.resolve(response.result);
      };
      return;
    } catch {
      if (port) { try { adb('forward', '--remove', `tcp:${port}`); } catch {} port = undefined; }
      await pause(300);
    }
  }
  throw new Error('Owned native WebView not available');
}
async function evaluate(expression) {
  const result = await new Promise((resolve, reject) => {
    const id = ++sequence, timer = setTimeout(() => { pending.delete(id); reject(new Error('Native evaluation timeout')); }, 20_000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }));
  });
  // Do not print exception descriptions: a provider/plugin might include storage contents.
  if (result.exceptionDetails) throw new Error('Native fixture evaluation failed');
  return result.result.value;
}
const authExpression = `ng.getComponent(document.querySelector('app-root')).auth`;
async function awaitAuth() {
  for (let attempt = 0; attempt < 60; attempt++) {
    try {
      if (await evaluate(`typeof window.ng?.getComponent==='function' && typeof ${authExpression}?.storage?.clearTokens==='function' && ${authExpression}.status()!=='initializing'`)) return;
    } catch {}
    await pause(200);
  }
  throw new Error('Actual Angular auth service not initialized');
}
function kill() {
  socket?.close(); socket = undefined;
  adb('shell', 'am', 'force-stop', 'com.hunt.otziv');
  if (port) { try { adb('forward', '--remove', `tcp:${port}`); } catch {} port = undefined; }
}
async function restart() {
  adb('shell', 'am', 'start', '-n', 'com.hunt.otziv/.MainActivity');
  await connect(); await awaitAuth();
}
async function check(name, expression, killImmediately = false) {
  if (await evaluate(expression) !== true) throw new Error(`Native check failed: ${name}`);
  if (killImmediately) kill();
  checks.push({ name, passed: true, observedAt: new Date().toISOString() });
  await writeFile(path.join(evidence, 'auth-durability-checks.json'), JSON.stringify(checks, null, 2));
}
const fixtureKey = `codex-native-durability-${randomUUID()}`;
const fixtureValue = 'public-synthetic-storage-value';
const fixtureTokens = { accessToken: `public-native-fixture-${randomUUID()}`, refreshToken: `public-native-fixture-${randomUUID()}`, tokenType: 'Bearer', expiresAt: Date.now() + 3600_000 };
try {
  await connect(); await awaitAuth();
  await check('owned_debug_fixture_has_no_session', `(async()=>{const info=await Capacitor.nativePromise('App','getInfo',{});return info.id==='com.hunt.otziv' && info.version.endsWith('-native-fixture') && Capacitor.isPluginAvailable('AuthStorageDurability') && !${authExpression}.tokens() && await ${authExpression}.storage.readTokens()===null;})()`);
  await check('write_and_sync_commit', `(async()=>{await Capacitor.nativePromise('SecureStorage','internalSetItem',{prefixedKey:${JSON.stringify(fixtureKey)},data:${JSON.stringify(fixtureValue)}});await Capacitor.nativePromise('AuthStorageDurability','flush',{store:'secure'});return true;})()`, true); // Kill before writing host evidence; no delay after commit.
  await restart();
  await check('committed_value_survives_immediate_process_death', `(async()=>{const value=await Capacitor.nativePromise('SecureStorage','internalGetItem',{prefixedKey:${JSON.stringify(fixtureKey)}});await Capacitor.nativePromise('SecureStorage','internalRemoveItem',{prefixedKey:${JSON.stringify(fixtureKey)}});await Capacitor.nativePromise('AuthStorageDurability','flush',{store:'secure'});return value.data===${JSON.stringify(fixtureValue)};})()`);
  await check('actual_auth_session_clear_commits', `(async()=>{const auth=${authExpression};await auth.storage.writeTokens(${JSON.stringify(fixtureTokens)});if(!(await auth.storage.readTokens())?.refreshToken)return false;await auth.clearSession('anonymous','native-durability-fixture');return !auth.tokens() && await auth.storage.readTokens()===null;})()`, true);
  await restart();
  await check('completed_local_logout_does_not_restore_tokens', `(async()=>!${authExpression}.tokens() && await ${authExpression}.storage.readTokens()===null)()`);
  await check('arm_interrupted_actual_logout', `(async()=>{const auth=${authExpression};await auth.storage.writeTokens(${JSON.stringify(fixtureTokens)});const original=Capacitor.nativePromise.bind(Capacitor);window.__otzivNativeFixture={stage:'starting'};Capacitor.nativePromise=(plugin,method,options)=>{if(plugin==='SecureStorage' && method==='internalSetItem'){window.__otzivNativeFixture.stage='secure_overwrite_paused';return new Promise(()=>{});}return original(plugin,method,options);};void auth.clearSession('anonymous','native-interrupted-logout-fixture').then(()=>{window.__otzivNativeFixture.stage='unexpected_completion';},()=>{window.__otzivNativeFixture.stage='failed';});return true;})()`);
  let paused = false;
  for (let attempt = 0; attempt < 50; attempt++) {
    const stage = await evaluate(`window.__otzivNativeFixture.stage`);
    if (stage === 'secure_overwrite_paused') { paused = true; break; }
    if (stage === 'failed' || stage === 'unexpected_completion') break;
    await pause(100);
  }
  if (!paused) throw new Error('Could not stop actual logout after the marker commit');
  kill();
  const preferences = adb('exec-out', 'run-as', 'com.hunt.otziv', 'cat', 'shared_prefs/CapacitorStorage.xml');
  const secure = adb('exec-out', 'run-as', 'com.hunt.otziv', 'cat', 'shared_prefs/WSSecureStorageSharedPreferences.xml');
  if (!preferences.includes('name="otziv.mobile.tokens.revoked">revoked-v1</string>')
      || !secure.includes('otziv.mobile.tokens') || secure.includes(fixtureTokens.accessToken))
    throw new Error('Stopped-process disk state did not retain the marker and encrypted old fixture');
  checks.push({ name: 'interrupted_logout_marker_is_on_disk_before_secure_overwrite', passed: true, observedAt: new Date().toISOString() });
  await restart();
  await check('actual_cold_start_consumes_marker_without_token_resurrection', `(async()=>{const auth=${authExpression};const value=await auth.storage.readTokens();return value===null && !auth.tokens() && auth.status()!=='authenticated';})()`);
  await check('arbitrary_preference_files_are_rejected', `(async()=>{try{await Capacitor.nativePromise('AuthStorageDurability','flush',{store:'../other'});return false;}catch(error){return error.code==='INVALID_AUTH_STORE';}})()`);
  await writeFile(path.join(evidence, 'auth-durability-summary.json'), JSON.stringify({ passed: true, installedApkSha256: args['apk-sha256'], serial: args.serial, avd: args.avd, actualLocalAuthStorage: true, authenticatedProviderLogin: false, checks }, null, 2));
  console.log(JSON.stringify({ passed: true, checks: checks.length, actualLocalAuthStorage: true, authenticatedProviderLogin: false }));
} finally {
  socket?.close();
  if (port) { try { adb('forward', '--remove', `tcp:${port}`); } catch {} }
}
