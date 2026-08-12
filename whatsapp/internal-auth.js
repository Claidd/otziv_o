"use strict";

const crypto = require("node:crypto");

const INTERNAL_AUTH_HEADER = "x-otziv-internal-token";

function parseBoolean(value) {
  return ["1", "true", "yes", "on"].includes(String(value || "").trim().toLowerCase());
}

function boundedBodyBytes(value, fallback = 256 * 1024, maximum = 1024 * 1024) {
  const match = String(value || "").trim().toLowerCase().match(/^(\d+)(b|kb|mb)?$/u);
  if (!match) {
    return fallback;
  }
  const multiplier = match[2] === "mb" ? 1024 * 1024 : match[2] === "kb" ? 1024 : 1;
  const parsed = Number(match[1]) * multiplier;
  return Number.isSafeInteger(parsed) ? Math.max(1024, Math.min(parsed, maximum)) : fallback;
}

function constantTimeMatches(provided, expected) {
  const providedDigest = crypto.createHash("sha256").update(String(provided || ""), "utf8").digest();
  const expectedDigest = crypto.createHash("sha256").update(String(expected || ""), "utf8").digest();
  return crypto.timingSafeEqual(providedDigest, expectedDigest);
}

function createInternalAuthMiddleware({
  secret = "",
  required = false,
  minimumSecretLength = 1,
} = {}) {
  const normalizedSecret = String(secret).trim();
  const configured = normalizedSecret.length > 0;
  const enforced = parseBoolean(required) || required === true || configured;
  const parsedMinimum = Number.parseInt(String(minimumSecretLength), 10);
  const minimum = Number.isSafeInteger(parsedMinimum) ? Math.max(1, parsedMinimum) : 1;

  if (enforced && !configured) {
    throw new Error("WhatsApp gateway authentication is required but no shared secret is configured");
  }
  if (enforced && normalizedSecret.length < minimum) {
    throw new Error(`WhatsApp gateway shared secret must contain at least ${minimum} characters`);
  }

  return (req, res, next) => {
    if (!enforced) {
      next();
      return;
    }

    const provided = req.get(INTERNAL_AUTH_HEADER) || "";
    if (!constantTimeMatches(provided, normalizedSecret)) {
      res.set("Cache-Control", "no-store");
      res.status(401).json({ status: "error", code: "unauthorized" });
      return;
    }
    next();
  };
}

function createConcurrencyMiddleware(configuredLimit, slotTimeoutMs = 600000) {
  const parsed = Number.parseInt(String(configuredLimit || ""), 10);
  const limit = Number.isFinite(parsed) ? Math.max(1, Math.min(parsed, 64)) : 16;
  let active = 0;

  return (req, res, next) => {
    if (active >= limit) {
      res.set("Retry-After", "1");
      res.status(429).json({ status: "error", code: "gateway_busy" });
      return;
    }
    active += 1;
    let released = false;
    let timer;
    const release = () => {
      if (!released) {
        released = true;
        if (timer) {
          clearTimeout(timer);
        }
        active -= 1;
      }
    };
    timer = setTimeout(release, Math.max(30000, Number(slotTimeoutMs) || 600000));
    timer.unref();
    res.once("finish", release);
    res.once("close", release);
    next();
  };
}

module.exports = {
  INTERNAL_AUTH_HEADER,
  boundedBodyBytes,
  constantTimeMatches,
  createConcurrencyMiddleware,
  createInternalAuthMiddleware,
  parseBoolean,
};
