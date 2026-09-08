"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { installPuppeteerCompatibility } = require("./puppeteer-compatibility");

test("Puppeteer 25 connection adapter follows live state and preserves auth hook", async () => {
  let called = 0;
  const browser = { connected: true };
  const client = { pupBrowser: browser, authStrategy: { afterBrowserInitialized: async function () { assert.equal(this, client.authStrategy); called++; } } };
  installPuppeteerCompatibility(client);
  await client.authStrategy.afterBrowserInitialized();
  assert.equal(called, 1); assert.equal(browser.isConnected(), true);
  browser.connected = false;
  assert.equal(browser.isConnected(), false);
});
test("adapter preserves a legacy connection method and rejects an unknown browser contract", async () => {
  const method = () => true;
  const client = { pupBrowser: { isConnected: method }, authStrategy: { afterBrowserInitialized: async () => {} } };
  installPuppeteerCompatibility(client); await client.authStrategy.afterBrowserInitialized();
  assert.equal(client.pupBrowser.isConnected, method);
  client.pupBrowser = {};
  await assert.rejects(client.authStrategy.afterBrowserInitialized(), /Unsupported/);
});
