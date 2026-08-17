"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { createLastSeenLookup, digitsOnly } = require("./last-seen");

test("normalizes a WhatsApp id before last-seen navigation", () => {
  assert.equal(digitsOnly("+7 (999) 111-22-33@c.us"), "79991112233");
});

test("extracts localized last-seen text and always closes the isolated page", async () => {
  let closed = false;
  let navigatedTo = null;
  const page = {
    setUserAgent: async () => undefined,
    goto: async (url) => { navigatedTo = url; },
    $$: async () => [],
    waitForSelector: async () => undefined,
    evaluate: async (callback, ...args) => {
      if (args.length === 2) {
        return "был(-а): вчера в 18:42";
      }
      return "";
    },
    isClosed: () => closed,
    close: async () => { closed = true; },
  };
  const browser = {
    newPage: async () => page,
    userAgent: async () => "test-agent",
  };
  const lookup = createLastSeenLookup({
    clientProvider: () => ({ pupPage: { browser: async () => browser } }),
    settleMs: 0,
  });

  const result = await lookup("79991112233@c.us");

  assert.match(navigatedTo, /phone=79991112233/u);
  assert.equal(result.lastSeen, "был(-а): вчера в 18:42");
  assert.equal(result.stage, "status-found");
  assert.equal(closed, true);
});

test("closes the isolated page after a lookup failure", async () => {
  let closed = false;
  const page = {
    setUserAgent: async () => undefined,
    goto: async () => { throw new Error("navigation failed"); },
    isClosed: () => closed,
    close: async () => { closed = true; },
  };
  const browser = { newPage: async () => page, userAgent: async () => "test-agent" };
  const lookup = createLastSeenLookup({
    clientProvider: () => ({ pupPage: { browser: async () => browser } }),
    settleMs: 0,
  });

  await assert.rejects(() => lookup("79991112233"), /navigation failed/u);
  assert.equal(closed, true);
});
