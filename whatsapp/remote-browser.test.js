"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  configuredRemoteBrowserUrl,
  installRemoteBrowserLifecycle,
  normalizeRemoteBrowserUrl,
  requirePeoplesBrowserProfile,
  resolveRemoteBrowserUrl,
} = require("./remote-browser");

test("remote browser URL accepts only a private endpoint shape", () => {
  assert.equal(
    normalizeRemoteBrowserUrl(" http://browser_profile_42:9223/ "),
    "http://browser_profile_42:9223"
  );
  assert.throws(
    () => normalizeRemoteBrowserUrl("http://user:secret@browser:9223/json/version"),
    /scheme, host and port/u
  );
});

test("peoples profile mode fails closed without a remote browser URL", () => {
  assert.throws(
    () => configuredRemoteBrowserUrl("", true),
    /required in peoples profile mode/u
  );
  assert.equal(configuredRemoteBrowserUrl("", false), "");
  assert.equal(configuredRemoteBrowserUrl("http://browser_profile_42:9223", true), "http://browser_profile_42:9223");
});

test("peoples profile id must match the remote browser hostname", () => {
  assert.equal(
    requirePeoplesBrowserProfile("http://browser_profile_42:9223", "42"),
    42
  );
  assert.throws(
    () => requirePeoplesBrowserProfile("http://browser_profile_43:9223", "42"),
    /does not match/u
  );
  assert.throws(
    () => requirePeoplesBrowserProfile("http://browser_profile_42:9223", ""),
    /must identify/u
  );
  assert.throws(
    () => requirePeoplesBrowserProfile("http://browser_profile_42:9223", "0"),
    /must identify/u
  );
});

test("remote browser hostname is resolved before Puppeteer connects", async () => {
  const resolved = await resolveRemoteBrowserUrl(
    "http://browser_profile_42:9223",
    async (hostname, options) => {
      assert.equal(hostname, "browser_profile_42");
      assert.deepEqual(options, { family: 4 });
      return { address: "172.30.0.42", family: 4 };
    }
  );

  assert.equal(resolved, "http://172.30.0.42:9223");
});

test("remote client shutdown disconnects without closing the shared browser", async () => {
  let pageClosed = 0;
  let disconnected = 0;
  let authDestroyed = 0;
  const client = {
    pupPage: {
      isClosed: () => false,
      close: async () => { pageClosed += 1; },
    },
    pupBrowser: {
      isConnected: () => true,
      close: async () => assert.fail("shared browser must not be closed"),
      disconnect: () => { disconnected += 1; },
    },
    authStrategy: {
      destroy: async () => { authDestroyed += 1; },
    },
  };

  installRemoteBrowserLifecycle(client);
  await client.destroy();

  assert.equal(pageClosed, 1);
  assert.equal(disconnected, 1);
  assert.equal(authDestroyed, 1);
});
