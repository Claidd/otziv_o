"use strict";

const dns = require("node:dns").promises;
const net = require("node:net");

function normalizeRemoteBrowserUrl(value) {
  const raw = String(value || "").trim();
  if (!raw) return "";
  let parsed;
  try {
    parsed = new URL(raw);
  } catch (error) {
    throw new Error("WHATSAPP_BROWSER_URL must be a valid HTTP URL");
  }
  if (!["http:", "https:"].includes(parsed.protocol)
      || !parsed.hostname
      || parsed.username
      || parsed.password
      || parsed.search
      || parsed.hash
      || (parsed.pathname && parsed.pathname !== "/")) {
    throw new Error("WHATSAPP_BROWSER_URL must contain only scheme, host and port");
  }
  return parsed.toString().replace(/\/$/u, "");
}

function configuredRemoteBrowserUrl(value, required = false) {
  const normalized = normalizeRemoteBrowserUrl(value);
  if (required && !normalized) {
    throw new Error("WHATSAPP_BROWSER_URL is required in peoples profile mode");
  }
  return normalized;
}

function requirePeoplesBrowserProfile(browserUrl, value) {
  const normalized = normalizeRemoteBrowserUrl(browserUrl);
  const rawProfileId = String(value || "").trim();
  if (!normalized || !/^[1-9]\d*$/u.test(rawProfileId)) {
    throw new Error("WHATSAPP_BROWSER_PROFILE_ID must identify a peoples browser profile");
  }
  const profileId = Number(rawProfileId);
  if (!Number.isSafeInteger(profileId)
      || new URL(normalized).hostname !== `browser_profile_${profileId}`) {
    throw new Error("WHATSAPP_BROWSER_URL does not match WHATSAPP_BROWSER_PROFILE_ID");
  }
  return profileId;
}

async function resolveRemoteBrowserUrl(value, lookup = dns.lookup) {
  const normalized = normalizeRemoteBrowserUrl(value);
  if (!normalized) return "";
  const parsed = new URL(normalized);
  if (net.isIP(parsed.hostname) || parsed.hostname === "localhost") return normalized;
  const result = await lookup(parsed.hostname, { family: 4 });
  if (!result || !net.isIP(result.address)) {
    throw new Error("WHATSAPP_BROWSER_URL host did not resolve to an IP address");
  }
  // Chromium rejects non-IP Host headers on its DevTools endpoint.
  parsed.hostname = result.address;
  return parsed.toString().replace(/\/$/u, "");
}

function installRemoteBrowserLifecycle(client, { beforeDestroy, afterDestroy } = {}) {
  if (!client || typeof client !== "object") {
    throw new Error("WhatsApp client is required");
  }
  client.destroy = async () => {
    const page = client.pupPage;
    const browser = client.pupBrowser;
    let cleanupFailure;
    try { await beforeDestroy?.(client); } catch (error) { cleanupFailure = error; }
    try {
      if (page && typeof page.isClosed === "function" && !page.isClosed()) {
        await page.close();
      }
    } catch (error) {
      // Detach even on failure, but never certify that an unknown owned page
      // was closed: a replacement client could otherwise run alongside it.
      cleanupFailure ||= error;
    }
    try {
      if (browser && browser.isConnected?.()) await browser.disconnect();
    } catch (error) {
      cleanupFailure ||= error;
    }
    try {
      if (client.authStrategy && typeof client.authStrategy.destroy === "function") {
        await client.authStrategy.destroy();
      }
    } catch (error) {
      cleanupFailure ||= error;
    }
    if (cleanupFailure) throw cleanupFailure;
    await afterDestroy?.();
  };
  return client;
}

module.exports = {
  configuredRemoteBrowserUrl,
  requirePeoplesBrowserProfile,
  installRemoteBrowserLifecycle,
  normalizeRemoteBrowserUrl,
  resolveRemoteBrowserUrl,
};
