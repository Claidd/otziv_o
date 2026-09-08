"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { spawn, spawnSync } = require("node:child_process");
const puppeteer = require("puppeteer");
const { chromiumLaunchArgs } = require("./chromium-launch");
const { RemoteSessionFence } = require("./remote-session-fence");
const { installRemoteBrowserLifecycle } = require("./remote-browser");

async function crashChild(script) {
  const child = spawn(process.execPath, ['-e', script], { cwd: __dirname, stdio: ['ignore', 'pipe', 'pipe'] });
  let stderr = ''; child.stdout.resume(); child.stderr.on('data', data => { stderr = (stderr + data).slice(-4000); });
  const timer = setTimeout(() => child.kill(), 15000);
  try {
    const status = await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', resolve); });
    return { status, stderr };
  } finally { clearTimeout(timer); }
}

async function main() {
  const directory = fs.mkdtempSync('/tmp/otziv-remote-session-');
  let browser, fence;
  try {
    browser = await puppeteer.launch({ executablePath: process.env.PUPPETEER_EXECUTABLE_PATH || '/usr/bin/chromium',
      headless: true, args: chromiumLaunchArgs(''), timeout: 30000, protocolTimeout: 30000 });
    const endpoint = new URL(browser.wsEndpoint()).origin.replace('ws:', 'http:');
    for (const page of await browser.pages()) await page.close(); // This browser belongs only to the fixture.
    const options = { directory, clientId: 'fixture', profileId: '42', browserUrl: 'http://browser_profile_42:9223' };
    fence = new RemoteSessionFence(options);
    const first = await fence.inspect(endpoint);
    await assert.rejects(fence.begin(endpoint), /bootstrap_required/);
    await fence.reconcile(endpoint, { expectedGeneration: 'absent', expectedBrowserId: first.browserId,
      actor: 'fixture-operator', evidenceReference: 'fixture-bootstrap' });
    const competing = spawnSync(process.execPath, ['-e', `try { new (require('./remote-session-fence').RemoteSessionFence)(${JSON.stringify(options)}); process.exit(3); } catch(e) { process.stdout.write(e.message); }`], { cwd: __dirname, encoding: 'utf8' });
    assert.equal(competing.status, 0); assert.equal(competing.stdout, 'remote_session_writer_active');
    const generation = await fence.begin(endpoint);
    const connection = await puppeteer.connect({ browserWSEndpoint: browser.wsEndpoint() });
    const page = await connection.newPage();
    await fence.capture({ pupPage: page }, generation);
    assert.equal((await fence.inspect(endpoint)).activeTargetIds.includes(fence.read().targetId), true);
    page.close = async () => { throw new Error('fixture rejected close'); };
    const client = installRemoteBrowserLifecycle({ pupPage: page, pupBrowser: connection }, {
      beforeDestroy: current => fence.capture(current, generation), afterDestroy: () => fence.complete(endpoint, generation),
    });
    await assert.rejects(client.destroy(), /fixture rejected close/);
    assert.equal(fence.read().state, 'DIRTY'); assert.equal(browser.connected, true);
    await assert.rejects(fence.reconcile(endpoint, { expectedGeneration: generation, expectedBrowserId: first.browserId,
      actor: 'fixture-operator', evidenceReference: 'must-not-pass' }), /targets_still_active/);
    const ownedTarget = fence.read().targetId;
    const ownerPage = (await browser.pages()).find(p => p.target()._targetId === ownedTarget);
    assert.ok(ownerPage); await ownerPage.close();
    await fence.reconcile(endpoint, { expectedGeneration: generation, expectedBrowserId: first.browserId,
      actor: 'fixture-operator', evidenceReference: 'fixture-observed-closed' });
    fence.release(); fence = null;
    // A real gateway-process death releases flock but leaves a real remote page
    // and the durable generation dirty. The profile-owning browser stays alive.
    const child = await crashChild(`(async()=>{const p=require('puppeteer'); const f=new(require('./remote-session-fence').RemoteSessionFence)(${JSON.stringify(options)});const g=await f.begin(${JSON.stringify(endpoint)});const b=await p.connect({browserWSEndpoint:${JSON.stringify(browser.wsEndpoint())}});const page=await b.newPage();await f.capture({pupPage:page},g);process.exit(51);})().catch(()=>process.exit(52));`);
    assert.equal(child.status, 51, child.stderr);
    fence = new RemoteSessionFence(options);
    await assert.rejects(fence.begin(endpoint), /reconciliation_required/);
    const afterDeath = await fence.inspect(endpoint);
    assert.equal(afterDeath.activeTargetIds.includes(afterDeath.recordedTargetId), true);
    for (const remaining of await browser.pages()) await remaining.close();
    await fence.reconcile(endpoint, { expectedGeneration: afterDeath.generation, expectedBrowserId: afterDeath.browserId,
      actor: 'fixture-operator', evidenceReference: 'fixture-crash-reconciled' });
    const cleanGeneration = await fence.begin(endpoint);
    const cleanConnection = await puppeteer.connect({ browserWSEndpoint: browser.wsEndpoint() });
    const cleanPage = await cleanConnection.newPage();
    await fence.capture({ pupPage: cleanPage }, cleanGeneration);
    await installRemoteBrowserLifecycle({ pupPage: cleanPage, pupBrowser: cleanConnection }, {
      beforeDestroy: current => fence.capture(current, cleanGeneration),
      afterDestroy: () => fence.complete(endpoint, cleanGeneration),
    }).destroy();
    assert.equal(fence.read().state, 'CLEAN'); assert.equal(browser.connected, true);
    console.log(JSON.stringify({ result: 'PASS', actualChromium: true, actualFlock: true, explicitBootstrap: true,
      livePageRejectsReconciliation: true, failedCleanupRetainsFence: true, crashLeavesRemotePageAndDirtyFence: true,
      normalCleanupConfirmed: true, providerLogin: 'NOT_RUN' }));
  } finally {
    fence?.release(); await browser?.close(); fs.rmSync(directory, { recursive: true, force: true });
  }
}
main().catch(error => { console.error('Remote session fixture failed:', error.message); process.exitCode = 1; });
