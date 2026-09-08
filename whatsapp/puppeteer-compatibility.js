"use strict";

// whatsapp-web.js 1.34.7 still invokes Browser.isConnected(), removed in
// Puppeteer 25 in favour of Browser.connected. Adapt each owned connection
// through the public auth-strategy lifecycle; do not patch dependency sources.
function installPuppeteerCompatibility(client) {
  const strategy = client?.authStrategy;
  if (!strategy || typeof strategy.afterBrowserInitialized !== "function") {
    throw new Error("WhatsApp auth lifecycle is unavailable");
  }
  const afterBrowserInitialized = strategy.afterBrowserInitialized.bind(strategy);
  strategy.afterBrowserInitialized = async () => {
    const browser = client.pupBrowser;
    if (browser && typeof browser.isConnected !== "function") {
      if (typeof browser.connected !== "boolean") throw new Error("Unsupported Puppeteer browser lifecycle");
      Object.defineProperty(browser, "isConnected", { value: () => browser.connected });
    }
    await afterBrowserInitialized();
  };
  return client;
}

module.exports = { installPuppeteerCompatibility };
