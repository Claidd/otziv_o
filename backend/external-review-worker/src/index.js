import "dotenv/config";
import express from "express";
import { chromium } from "playwright";
import { createWorker } from "tesseract.js";
import crypto from "node:crypto";

const app = express();
app.use(express.json({ limit: "2mb" }));

const port = Number(process.env.PORT || 3097);
const checkTimeoutMs = Number(process.env.CHECK_TIMEOUT_MS || 60000);
const maxReviewScrolls = Number(process.env.MAX_REVIEW_SCROLLS || 6);
const confirmedThreshold = Number(process.env.MATCH_CONFIRMED_THRESHOLD || 0.92);
const needsReviewThreshold = Number(process.env.MATCH_NEEDS_REVIEW_THRESHOLD || 0.75);

app.get("/health", (_req, res) => {
  res.json({ ok: true });
});

app.post("/api/external-review-checks/verify", async (req, res) => {
  const traceId = crypto.randomUUID();
  const started = Date.now();
  const payload = req.body || {};

  if (!payload.checkId || !payload.reviewId || !payload.filialUrl || !payload.expectedText) {
    return res.status(400).json({
      checkId: payload.checkId ?? null,
      status: "ERROR",
      confidence: 0,
      errorMessage: "checkId, reviewId, filialUrl and expectedText are required",
      traceId
    });
  }

  let browser;
  try {
    const executablePath = process.env.CHROMIUM_EXECUTABLE_PATH || undefined;
    browser = await chromium.launch({
      headless: true,
      executablePath,
      args: ["--no-sandbox", "--disable-dev-shm-usage"],
      proxy: proxyConfig()
    });

    const context = await browser.newContext({
      viewport: { width: 1440, height: 1200 },
      locale: "ru-RU",
      timezoneId: "Asia/Irkutsk"
    });
    const page = await context.newPage();
    page.setDefaultTimeout(Math.min(checkTimeoutMs, 30000));

    await page.goto(payload.filialUrl, {
      waitUntil: "domcontentloaded",
      timeout: checkTimeoutMs
    });
    await page.waitForTimeout(2500);

    if (await looksBlocked(page)) {
      const screenshot = await page.screenshot({ type: "png", fullPage: false });
      return res.json(response(payload.checkId, "BLOCKED", 0, "", screenshot, "Captcha or block page detected", traceId));
    }

    await openReviewsIfPossible(page);

    if (await looksBlocked(page)) {
      const screenshot = await page.screenshot({ type: "png", fullPage: false });
      return res.json(response(payload.checkId, "BLOCKED", 0, "", screenshot, "Captcha or block page detected", traceId));
    }

    const expected = normalizeText(payload.expectedText);
    let best = { confidence: 0, excerpt: "", screenshot: null };

    for (let i = 0; i < maxReviewScrolls; i++) {
      await page.waitForTimeout(1200);
      const screenshot = await page.screenshot({ type: "png", fullPage: false });
      if (await looksBlocked(page)) {
        return res.json(response(payload.checkId, "BLOCKED", 0, "", screenshot, "Captcha or block page detected", traceId));
      }
      const text = await ocr(screenshot);
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
    return res.json({
      checkId: payload.checkId,
      status: "ERROR",
      confidence: 0,
      matchedTextExcerpt: "",
      screenshotBase64: null,
      screenshotContentType: null,
      errorMessage: String(error?.message || error),
      traceId
    });
  } finally {
    if (browser) {
      await browser.close().catch(() => {});
    }
  }
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

async function ocr(buffer) {
  const worker = await createWorker("rus+eng");
  try {
    const result = await worker.recognize(buffer);
    return result?.data?.text || "";
  } finally {
    await worker.terminate();
  }
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
