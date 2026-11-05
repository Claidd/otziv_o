"use strict";

const crypto = require("node:crypto");

// Backend delivery must reach exactly the configured URL. A redirect is never
// followed, so the secret and signature headers cannot reach its target, and
// a 3xx response is returned as-is: the durable inbox keeps the record pending
// because only a 2xx response acknowledges delivery.
function createSignedWebhookPoster({ timeoutMs = 10000, fetchImpl = fetch } = {}) {
  return async function postSignedWebhook(serverUrl, webhookSecret, path, payload) {
    const body = JSON.stringify(payload);
    const headers = { "Content-Type": "application/json" };
    if (webhookSecret) {
      headers["X-WhatsApp-Webhook-Secret"] = webhookSecret;
      headers["X-WhatsApp-Webhook-Signature"] = `sha256=${crypto
        .createHmac("sha256", webhookSecret)
        .update(body, "utf8")
        .digest("hex")}`;
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetchImpl(`${serverUrl}${path}`, {
        method: "POST", headers, body, signal: controller.signal, redirect: "manual",
      });
      await response.body?.cancel();
      // A browser-style opaque redirect has status 0, which is also a retry.
      return { status: response.type === "opaqueredirect" ? 0 : response.status };
    } finally { clearTimeout(timeout); }
  };
}

module.exports = { createSignedWebhookPoster };
