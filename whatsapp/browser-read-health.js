"use strict";

// WhatsApp Web may stop answering browser reads while still reporting READY.
// Consecutive failures trigger the existing safe client lifecycle restart.
class BrowserReadHealth {
  constructor({ threshold = 2, cooldownMs = 10 * 60 * 1000, now = Date.now, onUnhealthy }) {
    if (!Number.isSafeInteger(threshold) || threshold < 1 || typeof onUnhealthy !== "function") {
      throw new Error("Invalid browser read health configuration");
    }
    this.threshold = threshold;
    this.cooldownMs = cooldownMs;
    this.now = now;
    this.onUnhealthy = onUnhealthy;
    this.failures = 0;
    this.lastRestartAt = -Infinity;
  }

  success() {
    this.failures = 0;
  }

  failure(reason) {
    this.failures += 1;
    const now = this.now();
    if (this.failures < this.threshold || now - this.lastRestartAt < this.cooldownMs) {
      return false;
    }
    this.failures = 0;
    this.lastRestartAt = now;
    this.onUnhealthy(reason);
    return true;
  }
}

// A failed history or reconciliation request does not prove that Chromium is
// unresponsive: WhatsApp can stall while loading one chat's remote metadata.
// Probe the page independently before spending an authenticated session on a
// restart. The probe never reads chat contents or changes browser state.
async function probeBrowserRead(client, timeoutMs = 5000) {
  const page = client && client.pupPage;
  if (!page || typeof page.evaluate !== "function" || page.isClosed?.()) return false;
  let timer;
  try {
    return Boolean(await Promise.race([
      Promise.resolve().then(() => page.evaluate(() => Boolean(
        window.WWebJS && typeof window.require === "function"
          && window.require("WAWebCollections")?.Chat?.getModelsArray
      ))),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error("browser_read_probe_timeout")), timeoutMs);
      }),
    ]));
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

async function assessBrowserReadFailure({ client, health, reason, current = () => true, timeoutMs = 5000 }) {
  const responsive = await probeBrowserRead(client, timeoutMs);
  if (!current()) return { responsive, restartScheduled: false };
  if (responsive) {
    health.success();
    return { responsive: true, restartScheduled: false };
  }
  return { responsive: false, restartScheduled: health.failure(reason) };
}

module.exports = { BrowserReadHealth, probeBrowserRead, assessBrowserReadFailure };
