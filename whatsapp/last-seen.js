"use strict";

const LAST_SEEN_PATTERN = /(^|\s)(в сети|online|был|была|last seen|сегодня в|вчера в|today at|yesterday at|\d{1,2}\s+[^\d\s]+\s+в\s+\d{1,2}:\d{2}|\d{1,2}[./]\d{1,2}[./]\d{4}\s+в\s+\d{1,2}:\d{2})($|\s)/iu;
const MODAL_CONFIRMATIONS = [
  "продолжить", "понятно", "отлично", "далее", "хорошо", "готово",
  "continue", "ok", "next", "done",
];

function digitsOnly(value) {
  return String(value || "").replace(/\D+/gu, "");
}

function createLastSeenLookup({
  clientProvider,
  navigationTimeoutMs = 60000,
  headerTimeoutMs = 20000,
  settleMs = 8000,
  log,
} = {}) {
  if (typeof clientProvider !== "function") {
    throw new Error("clientProvider is required");
  }

  return async function lookupLastSeen(rawPhone) {
    const phone = digitsOnly(rawPhone);
    if (!phone) {
      throw new Error("A normalized phone is required");
    }
    const client = clientProvider();
    if (!client || !client.pupPage) {
      const error = new Error("WhatsApp browser page is not ready");
      error.code = "browser_not_ready";
      throw error;
    }

    const browser = await client.pupPage.browser();
    const page = await browser.newPage();
    try {
      const userAgent = await browser.userAgent();
      if (userAgent) {
        await page.setUserAgent(userAgent);
      }
      await page.goto(`https://web.whatsapp.com/send?phone=${phone}&text&app_absent=0`, {
        waitUntil: "domcontentloaded",
        timeout: navigationTimeoutMs,
      });
      await closeKnownModal(page);
      await page.waitForSelector("header", { timeout: headerTimeoutMs });
      await wait(settleMs);

      const statusText = await page.evaluate((patternSource, patternFlags) => {
        const pattern = new RegExp(patternSource, patternFlags);
        const elements = Array.from(document.querySelectorAll("header span, header div"));
        for (const element of elements) {
          const candidates = [
            element.textContent,
            element.getAttribute && element.getAttribute("aria-label"),
            element.getAttribute && element.getAttribute("title"),
          ];
          for (const candidate of candidates) {
            const text = String(candidate || "").trim();
            if (text && pattern.test(text)) {
              return text;
            }
          }
        }
        return null;
      }, LAST_SEEN_PATTERN.source, LAST_SEEN_PATTERN.flags);

      if (typeof log === "function") {
        log("info", "WhatsApp last-seen lookup completed", {
          stage: statusText ? "status-found" : "status-hidden",
          statusPresent: Boolean(statusText),
        });
      }
      return {
        lastSeen: statusText || null,
        rawLastSeen: statusText || null,
        stage: statusText ? "status-found" : "status-hidden",
      };
    } catch (error) {
      error.code = error.code || "last_seen_lookup_failed";
      throw error;
    } finally {
      if (!page.isClosed()) {
        await page.close().catch(() => undefined);
      }
    }
  };
}

async function closeKnownModal(page) {
  const buttons = await page.$$("div[role='dialog'] button").catch(() => []);
  for (const button of buttons) {
    const label = await page.evaluate((element) => String(element.textContent || "").trim().toLowerCase(), button);
    if (MODAL_CONFIRMATIONS.some((candidate) => label.includes(candidate))) {
      await button.click();
      return;
    }
  }
}

function wait(milliseconds) {
  if (!Number.isFinite(milliseconds) || milliseconds <= 0) {
    return Promise.resolve();
  }
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

module.exports = {
  LAST_SEEN_PATTERN,
  createLastSeenLookup,
  digitsOnly,
};
