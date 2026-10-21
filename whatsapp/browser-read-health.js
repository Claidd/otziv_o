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

module.exports = { BrowserReadHealth };
