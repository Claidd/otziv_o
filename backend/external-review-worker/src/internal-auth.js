import crypto from "node:crypto";

export const INTERNAL_AUTH_HEADER = "x-otziv-internal-token";

export function parseBoolean(value) {
  return ["1", "true", "yes", "on"].includes(String(value || "").trim().toLowerCase());
}

export function boundedBodyBytes(value, fallback = 256 * 1_024, maximum = 512 * 1_024) {
  const match = String(value || "").trim().toLowerCase().match(/^(\d+)(b|kb|mb)?$/u);
  if (!match) {
    return fallback;
  }
  const multiplier = match[2] === "mb" ? 1_024 * 1_024 : match[2] === "kb" ? 1_024 : 1;
  const parsed = Number(match[1]) * multiplier;
  return Number.isSafeInteger(parsed) ? Math.max(1_024, Math.min(parsed, maximum)) : fallback;
}

export function constantTimeMatches(provided, expected) {
  const providedDigest = crypto.createHash("sha256").update(String(provided || ""), "utf8").digest();
  const expectedDigest = crypto.createHash("sha256").update(String(expected || ""), "utf8").digest();
  return crypto.timingSafeEqual(providedDigest, expectedDigest);
}

export function createInternalAuthMiddleware({ secret = "", required = false } = {}) {
  const configured = String(secret).trim().length > 0;
  const enforced = parseBoolean(required) || required === true || configured;

  if (enforced && !configured) {
    throw new Error("External review worker authentication is required but no shared secret is configured");
  }

  return (req, res, next) => {
    if (!enforced) {
      next();
      return;
    }

    const provided = req.get(INTERNAL_AUTH_HEADER) || "";
    if (!constantTimeMatches(provided, secret)) {
      res.set("Cache-Control", "no-store");
      res.status(401).json({ status: "ERROR", code: "unauthorized" });
      return;
    }
    next();
  };
}

export function createConcurrencyMiddleware(configuredLimit, slotTimeoutMs = 300_000) {
  const parsed = Number.parseInt(String(configuredLimit || ""), 10);
  const limit = Number.isFinite(parsed) ? Math.max(1, Math.min(parsed, 8)) : 1;
  let active = 0;

  return (req, res, next) => {
    if (active >= limit) {
      res.set("Retry-After", "1");
      res.status(429).json({ status: "ERROR", code: "worker_busy" });
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
    timer = setTimeout(release, Math.max(30_000, Number(slotTimeoutMs) || 300_000));
    timer.unref();
    res.once("finish", release);
    res.once("close", release);
    next();
  };
}
