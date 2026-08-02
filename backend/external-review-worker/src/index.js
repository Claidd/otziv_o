import "dotenv/config";
import express from "express";
import { chromium } from "playwright";
import { createWorker } from "tesseract.js";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  boundedBodyBytes,
  createConcurrencyMiddleware,
  createInternalAuthMiddleware,
  parseBoolean,
} from "./internal-auth.js";
import {
  assertPublicHttpUrl,
  createPublicRequestGuard,
  UnsafeTargetError,
} from "./url-security.js";
import { chromiumLaunchArgs } from "./chromium-security.js";

const app = express();

const port = Number(process.env.PORT || 3097);
const checkTimeoutMs = boundedInteger(process.env.CHECK_TIMEOUT_MS, 60_000, 5_000, 120_000);
const maxReviewScrolls = boundedInteger(process.env.MAX_REVIEW_SCROLLS, 6, 1, 10);
const confirmedThreshold = boundedNumber(process.env.MATCH_CONFIRMED_THRESHOLD, 0.92, 0.5, 1);
const needsReviewThreshold = boundedNumber(process.env.MATCH_NEEDS_REVIEW_THRESHOLD, 0.75, 0.25, confirmedThreshold);
const maxExpectedTextChars = boundedInteger(process.env.MAX_EXPECTED_TEXT_CHARS, 20_000, 100, 50_000);
const maxMainNavigations = boundedInteger(process.env.MAX_MAIN_NAVIGATIONS, 10, 1, 20);
const maxCheckDurationMs = boundedInteger(process.env.MAX_CHECK_DURATION_MS, 120_000, 30_000, 300_000);
const ocrTimeoutMs = boundedInteger(process.env.OCR_TIMEOUT_MS, 30_000, 5_000, 60_000);
const requestBodyLimit = boundedBodyBytes(process.env.REQUEST_BODY_LIMIT);
const tesseractCachePath = process.env.TESSERACT_CACHE_PATH
  || path.join(os.tmpdir(), "otziv-tesseract-cache");

for (const writablePath of [
  process.env.HOME || os.tmpdir(),
  process.env.XDG_CACHE_HOME || os.tmpdir(),
  tesseractCachePath,
]) {
  fs.mkdirSync(writablePath, { recursive: true });
}

app.get("/health", (_req, res) => {
  res.json({ ok: true });
});

app.get("/ready", (_req, res) => {
  res.json({ ok: true });
});

app.use("/api", createInternalAuthMiddleware({
  secret: process.env.EXTERNAL_REVIEW_WORKER_SHARED_SECRET || "",
  required: parseBoolean(process.env.EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED),
}));
app.use("/api", express.json({ limit: requestBodyLimit, strict: true }));

app.post(
  "/api/external-review-checks/verify",
  createConcurrencyMiddleware(
    process.env.MAX_CONCURRENT_CHECKS || 1,
    maxCheckDurationMs + ocrTimeoutMs + 10_000,
  ),
  async (req, res) => {
  const traceId = crypto.randomUUID();
  const started = Date.now();
  const payload = req.body || {};

  const validationError = validatePayload(payload);
  if (validationError) {
    return res.status(400).json({
      checkId: payload.checkId ?? null,
      status: "ERROR",
      confidence: 0,
      errorMessage: validationError,
      traceId
    });
  }

  let targetUrl;
  try {
    targetUrl = await assertPublicHttpUrl(payload.filialUrl);
  } catch {
    return res.status(400).json({
      checkId: payload.checkId,
      status: "ERROR",
      confidence: 0,
      errorMessage: "filialUrl must be an allowed public HTTP(S) URL",
      traceId
    });
  }

  let browser;
  let ocrWorker;
  let deadlineExceeded = false;
  const deadlineTimer = setTimeout(() => {
    deadlineExceeded = true;
    if (browser) {
      void browser.close().catch(() => {});
    }
  }, maxCheckDurationMs);
  deadlineTimer.unref();
  try {
    const executablePath = process.env.CHROMIUM_EXECUTABLE_PATH || undefined;
    browser = await chromium.launch({
      headless: true,
      executablePath,
      timeout: Math.min(checkTimeoutMs, 30_000),
      args: chromiumLaunchArgs(),
      proxy: proxyConfig()
    });

    const context = await browser.newContext({
      viewport: { width: 1440, height: 1200 },
      locale: "ru-RU",
      timezoneId: "Asia/Irkutsk",
      acceptDownloads: false,
      serviceWorkers: "block"
    });
    await context.clearPermissions();
    const page = await context.newPage();
    page.setDefaultTimeout(Math.min(checkTimeoutMs, 30000));
    page.on("dialog", (dialog) => dialog.dismiss().catch(() => {}));

    let blockedRequestCode = null;
    await page.route("**/*", createPublicRequestGuard({
      maxMainNavigations,
      isMainNavigation: (request) => request.isNavigationRequest()
        && request.frame() === page.mainFrame(),
      onBlocked: (code) => {
        blockedRequestCode = blockedRequestCode || code;
      },
    }));

    await page.goto(targetUrl.href, {
      waitUntil: "domcontentloaded",
      timeout: checkTimeoutMs
    });
    await page.waitForTimeout(2500);
    assertWithinDeadline(deadlineExceeded);
    assertNoBlockedRequest(blockedRequestCode);

    if (await looksBlocked(page)) {
      const screenshot = await page.screenshot({ type: "png", fullPage: false });
      return res.json(response(payload.checkId, "BLOCKED", 0, "", screenshot, "Captcha or block page detected", traceId));
    }

    await openReviewsIfPossible(page);
    assertWithinDeadline(deadlineExceeded);

    if (await looksBlocked(page)) {
      const screenshot = await page.screenshot({ type: "png", fullPage: false });
      return res.json(response(payload.checkId, "BLOCKED", 0, "", screenshot, "Captcha or block page detected", traceId));
    }

    const expected = normalizeText(payload.expectedText);
    let best = { confidence: 0, excerpt: "", screenshot: null };

    for (let i = 0; i < maxReviewScrolls; i++) {
      await page.waitForTimeout(1200);
      assertWithinDeadline(deadlineExceeded);
      assertNoBlockedRequest(blockedRequestCode);
      const screenshot = await page.screenshot({ type: "png", fullPage: false });
      if (await looksBlocked(page)) {
        return res.json(response(payload.checkId, "BLOCKED", 0, "", screenshot, "Captcha or block page detected", traceId));
      }
      if (!ocrWorker) {
        ocrWorker = await createOcrWorker();
      }
      const text = await recognizeScreenshot(ocrWorker, screenshot);
      assertWithinDeadline(deadlineExceeded);
      const match = scoreMatch(expected, normalizeText(text), text);

      if (match.confidence > best.confidence) {
        best = { ...match, screenshot };
      }
      if (best.confidence >= confirmedThreshold) {
        break;
      }

      await page.mouse.wheel(0, 900);
    }

    const status = best.confidence >= confirmedThreshold
      ? "CONFIRMED"
      : best.confidence >= needsReviewThreshold
        ? "NEEDS_REVIEW"
        : "NOT_FOUND";

    const elapsed = Date.now() - started;
    return res.json(response(
      payload.checkId,
      status,
      best.confidence,
      best.excerpt,
      best.screenshot,
      `elapsedMs=${elapsed}`,
      traceId
    ));
  } catch (error) {
    console.warn(JSON.stringify({
      level: "warn",
      message: "external review check failed",
      traceId,
      errorType: error?.name || "Error",
      errorCode: error?.code || undefined,
    }));
    return res.json({
      checkId: payload.checkId,
      status: "ERROR",
      confidence: 0,
      matchedTextExcerpt: "",
      screenshotBase64: null,
      screenshotContentType: null,
      errorMessage: safeWorkerError(error, deadlineExceeded),
      traceId
    });
  } finally {
    clearTimeout(deadlineTimer);
    if (browser) {
      await browser.close().catch(() => {});
    }
    if (ocrWorker) {
      await ocrWorker.terminate().catch(() => {});
    }
  }
});

app.use((error, _req, res, _next) => {
  if (error?.type === "entity.too.large") {
    res.status(413).json({ status: "ERROR", code: "payload_too_large" });
    return;
  }
  if (error instanceof SyntaxError) {
    res.status(400).json({ status: "ERROR", code: "invalid_json" });
    return;
  }
  res.status(500).json({ status: "ERROR", code: "internal_error" });
});

app.listen(port, () => {
  console.log(`external-review-worker listening on ${port}`);
});

function proxyConfig() {
  if (String(process.env.EXTERNAL_REVIEW_PROXY_ENABLED || "false").toLowerCase() !== "true") {
    return undefined;
  }
  const host = process.env.EXTERNAL_REVIEW_PROXY_HOST;
  const port = process.env.EXTERNAL_REVIEW_PROXY_PORT;
  if (!host || !port) {
    return undefined;
  }
  const proxy = { server: `http://${host}:${port}` };
  if (process.env.EXTERNAL_REVIEW_PROXY_USERNAME) {
    proxy.username = process.env.EXTERNAL_REVIEW_PROXY_USERNAME;
    proxy.password = process.env.EXTERNAL_REVIEW_PROXY_PASSWORD || "";
  }
  return proxy;
}

async function openReviewsIfPossible(page) {
  const candidates = [
    "text=/Отзывы/i",
    "text=/отзыв/i",
    "[aria-label*='Отзывы']",
    "[href*='reviews']"
  ];

  for (const selector of candidates) {
    const locator = page.locator(selector).first();
    if (await locator.count().catch(() => 0)) {
      await locator.click({ timeout: 3000 }).catch(() => {});
      await page.waitForTimeout(1500);
      return;
    }
  }
}

async function looksBlocked(page) {
  const text = normalizeText(await page.locator("body").innerText({ timeout: 3000 }).catch(() => ""));
  const url = normalizeText(page.url());
  return text.includes("captcha")
    || text.includes("капча")
    || text.includes("recaptcha")
    || text.includes("я не робот")
    || text.includes("не робот")
    || text.includes("подтвердите что вы не робот")
    || text.includes("подтвердить что вы не робот")
    || text.includes("подозрительную активность")
    || text.includes("подозрительная активность")
    || text.includes("доступ ограничен")
    || text.includes("too many requests")
    || url.includes("captcha")
    || url.includes("recaptcha");
}

async function createOcrWorker() {
  const workerPromise = createWorker("rus+eng", undefined, { cachePath: tesseractCachePath });
  return withTimeout(
    workerPromise,
    ocrTimeoutMs,
    () => {
      void workerPromise.then((lateWorker) => lateWorker.terminate().catch(() => {})).catch(() => {});
    },
  );
}

async function recognizeScreenshot(worker, buffer) {
  const result = await withTimeout(
    worker.recognize(buffer),
    ocrTimeoutMs,
    () => { void worker.terminate().catch(() => {}); },
  );
  return result?.data?.text || "";
}

function validatePayload(payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    return "JSON object is required";
  }
  if (!isPositiveId(payload.checkId) || !isPositiveId(payload.reviewId)) {
    return "checkId and reviewId must be positive integers";
  }
  if (typeof payload.filialUrl !== "string" || payload.filialUrl.length === 0) {
    return "filialUrl is required";
  }
  if (typeof payload.expectedText !== "string"
      || payload.expectedText.trim().length === 0
      || payload.expectedText.length > maxExpectedTextChars) {
    return `expectedText must contain 1-${maxExpectedTextChars} characters`;
  }
  if (payload.platform != null
      && (typeof payload.platform !== "string" || payload.platform.length > 32)) {
    return "platform is invalid";
  }
  return null;
}

function isPositiveId(value) {
  return typeof value === "number" && Number.isInteger(value) && value > 0;
}

function assertNoBlockedRequest(code) {
  if (code) {
    throw new UnsafeTargetError(code);
  }
}

function assertWithinDeadline(exceeded) {
  if (exceeded) {
    throw new Error("Worker operation timed out");
  }
}

function safeWorkerError(error, deadlineExceeded = false) {
  if (error instanceof UnsafeTargetError) {
    return "Outbound request was blocked by worker policy";
  }
  const message = String(error?.message || "");
  if (deadlineExceeded || /timeout|timed out/iu.test(message)) {
    return "External review check timed out";
  }
  return "External review check failed";
}

async function withTimeout(promise, timeoutMs, onTimeout = () => {}) {
  let timer;
  try {
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(() => {
        onTimeout();
        reject(new Error("Operation timed out"));
      }, timeoutMs);
      timer.unref();
    });
    return await Promise.race([promise, timeout]);
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
  }
}

function boundedInteger(raw, fallback, minimum, maximum) {
  const parsed = Number.parseInt(String(raw || ""), 10);
  return Number.isFinite(parsed) ? Math.max(minimum, Math.min(parsed, maximum)) : fallback;
}

function boundedNumber(raw, fallback, minimum, maximum) {
  const parsed = Number.parseFloat(String(raw || ""));
  return Number.isFinite(parsed) ? Math.max(minimum, Math.min(parsed, maximum)) : fallback;
}

function scoreMatch(expected, actual, rawActual) {
  if (!expected || !actual) {
    return { confidence: 0, excerpt: "" };
  }
  if (actual.includes(expected)) {
    return { confidence: 0.99, excerpt: excerptAround(rawActual, expected) };
  }

  const expectedTokens = tokens(expected);
  const actualTokens = new Set(tokens(actual));
  if (!expectedTokens.length) {
    return { confidence: 0, excerpt: "" };
  }

  const matched = expectedTokens.filter((token) => actualTokens.has(token)).length;
  const tokenScore = matched / expectedTokens.length;
  const lengthScore = Math.min(1, actual.length / Math.max(expected.length, 1));
  const confidence = Math.min(0.98, tokenScore * 0.85 + lengthScore * 0.15);

  return {
    confidence,
    excerpt: confidence >= needsReviewThreshold ? compact(rawActual).slice(0, 700) : ""
  };
}

function tokens(text) {
  return normalizeText(text)
    .split(" ")
    .map((token) => token.trim())
    .filter((token) => token.length >= 3);
}

function normalizeText(text) {
  return String(text || "")
    .toLowerCase()
    .replaceAll("ё", "е")
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function compact(text) {
  return String(text || "").replace(/\s+/g, " ").trim();
}

function excerptAround(rawText, normalizedNeedle) {
  const compacted = compact(rawText);
  const normalizedRaw = normalizeText(compacted);
  const index = normalizedRaw.indexOf(normalizedNeedle);
  if (index < 0) {
    return compacted.slice(0, 700);
  }
  return compacted.slice(Math.max(0, index - 120), index + normalizedNeedle.length + 240);
}

function response(checkId, status, confidence, matchedTextExcerpt, screenshot, errorMessage, traceId) {
  return {
    checkId,
    status,
    confidence,
    matchedTextExcerpt: matchedTextExcerpt || "",
    screenshotBase64: screenshot ? screenshot.toString("base64") : null,
    screenshotContentType: screenshot ? "image/png" : null,
    errorMessage,
    traceId
  };
}
