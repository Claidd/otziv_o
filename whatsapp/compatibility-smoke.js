"use strict";

// Exercises real wwebjs/Puppeteer lifecycle against an intercepted local HTML
// fixture. Every page request is fulfilled or aborted before any real website
// is contacted; no login, QR, provider account, or message delivery occurs.
const assert = require("node:assert/strict");
const puppeteer = require("puppeteer");
const { Client, NoAuth } = require("whatsapp-web.js");
const { chromiumLaunchArgs } = require("./chromium-launch");
const { installPuppeteerCompatibility } = require("./puppeteer-compatibility");
const { installRemoteBrowserLifecycle } = require("./remote-browser");

class FixtureAuth extends NoAuth {
  async afterBrowserInitialized() {
    const page = this.client.pupPage;
    await page.setRequestInterception(true);
    page.on("request", (request) => {
      if (request.isNavigationRequest()) {
        void request.respond({ status: 200, contentType: "text/html", body: "<!doctype html><title>local fixture</title>" });
      } else {
        void request.abort();
      }
    });
  }
}

function fixtureClient(options) {
  const client = installPuppeteerCompatibility(new Client({
    authStrategy: new FixtureAuth(), webVersionCache: { type: "none" }, puppeteer: options,
  }));
  client.inject = async () => client.pupPage.evaluate(() => {
    const records = new Map();
    window.WWebJS = {
      getChat: async (id) => ({ id }), sendSeen: async () => true,
      getChats: async () => [], getMessageModel: (message) => message,
      sendMessage: async (chat, body) => {
        const message = { id: { id: "FIXTURE", _serialized: "true_fixture_FIXTURE", fromMe: true },
          body, to: chat.id, from: "fixture", t: 1, type: "chat", mentionedJidList: [], groupMentions: [] };
        records.set(message.id._serialized, message); return message;
      },
    };
    window.require = () => ({ Msg: { get: (id) => records.get(id) } });
  });
  return client;
}

async function exercise(client) {
  await client.initialize();
  assert.equal(client.pupBrowser.isConnected(), true);
  assert.ok(process.env.OTZIV_BROWSER_VERSION, "Release browser version must be pinned");
  assert.equal(await client.pupBrowser.version(), `Chrome/${process.env.OTZIV_BROWSER_VERSION}`);
  const cdp = await client.pupPage.createCDPSession();
  try {
    const command = await cdp.send("Browser.getBrowserCommandLine");
    assert.equal(command.arguments.some((argument) => /^(--no-sandbox|--disable-setuid-sandbox|--disable-seccomp-filter-sandbox|--disable-namespace-sandbox)(=|$)/u.test(argument)), false);
  } finally { await cdp.detach(); }
  assert.equal(await client.pupPage.title(), "local fixture");
  await client.pupPage.exposeFunction("fixtureEcho", (value) => value);
  assert.equal(await client.pupPage.evaluate(() => window.fixtureEcho("ready")), "ready");
  assert.deepEqual(await client.getChats(), []);
  const sent = await client.sendMessage("fixture@g.us", "local fixture message");
  assert.equal(sent.id._serialized, "true_fixture_FIXTURE");
  assert.equal((await client.getMessageById(sent.id._serialized)).body, "local fixture message");
}

async function main() {
  const launchOptions = { executablePath: process.env.PUPPETEER_EXECUTABLE_PATH || "/usr/bin/chromium",
    headless: true, timeout: 30000, protocolTimeout: 30000, args: chromiumLaunchArgs("") };
  const local = fixtureClient(launchOptions);
  try { await exercise(local); } finally { await local.destroy(); }
  assert.equal(local.pupBrowser.connected, false);
  const owner = await puppeteer.launch(launchOptions);
  let remote;
  try {
    remote = installRemoteBrowserLifecycle(fixtureClient({ browserWSEndpoint: owner.wsEndpoint(), timeout: 30000 }));
    await exercise(remote);
    await remote.destroy();
    assert.equal(owner.connected, true); // Gateway must never close the shared browser.
    assert.equal(remote.pupBrowser.connected, false);
  } finally {
    if (remote?.pupBrowser.connected) await remote.destroy();
    await owner.close();
  }
  console.log("Pinned wwebjs/Puppeteer 25 local and remote fixture lifecycle passed");
  const { spawnSync } = require("node:child_process");
  const fence = spawnSync(process.execPath, [require.resolve("./remote-session-smoke")], {
    encoding: "utf8", timeout: 60000,
  });
  if (fence.status !== 0) throw new Error(`Real remote session ownership/fence fixture failed: ${fence.stderr.slice(-4000)}`);
  console.log(fence.stdout.trim());
}
const watchdog = setTimeout(() => process.exit(1), 90_000);
main().then(() => clearTimeout(watchdog)).catch((error) => {
  clearTimeout(watchdog); console.error(error); process.exitCode = 1;
});
