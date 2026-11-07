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
      const status = response.type === "opaqueredirect" ? 0 : response.status;
      // A receiver that is shedding load says when to come back; the inbox pauses for that long.
      const retryAfter = status === 429 || status === 503
        ? retryAfterMs(response.headers?.get?.("retry-after"), Date.now()) : undefined;
      return retryAfter === undefined ? { status } : { status, retryAfterMs: retryAfter };
    } finally { clearTimeout(timeout); }
  };
}

// Retry-After is either delay seconds or an HTTP date; anything else is ignored.
function retryAfterMs(value, now) {
  if (typeof value !== "string" || !value.trim()) return undefined;
  const text = value.trim();
  if (/^\d{1,9}$/u.test(text)) return Number(text) * 1000;
  // IMF-fixdate only ("Sun, 06 Nov 1994 08:49:37 GMT"): Date.parse accepts far too much else.
  if (!/^[A-Za-z]{3}, \d{2} [A-Za-z]{3} \d{4} \d{2}:\d{2}:\d{2} GMT$/u.test(text)) return undefined;
  const at = Date.parse(text);
  return Number.isFinite(at) ? Math.max(0, at - now) : undefined;
}

module.exports = { createSignedWebhookPoster, retryAfterMs };
