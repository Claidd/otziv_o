import dns from "node:dns/promises";
import net from "node:net";

const MAX_URL_LENGTH = 2_048;
const BLOCKED_HOST_SUFFIXES = [
  ".localhost",
  ".local",
  ".internal",
  ".home.arpa",
  ".test",
  ".invalid",
  ".example",
];

const blockedAddresses = new net.BlockList();
for (const [network, prefix] of [
  ["0.0.0.0", 8],
  ["10.0.0.0", 8],
  ["100.64.0.0", 10],
  ["127.0.0.0", 8],
  ["169.254.0.0", 16],
  ["172.16.0.0", 12],
  ["192.0.0.0", 24],
  ["192.0.2.0", 24],
  ["192.88.99.0", 24],
  ["192.168.0.0", 16],
  ["198.18.0.0", 15],
  ["198.51.100.0", 24],
  ["203.0.113.0", 24],
  ["224.0.0.0", 4],
  ["240.0.0.0", 4],
]) {
  blockedAddresses.addSubnet(network, prefix, "ipv4");
}
for (const [network, prefix] of [
  ["::", 128],
  ["::1", 128],
  ["64:ff9b::", 96],
  ["64:ff9b:1::", 48],
  ["100::", 64],
  ["2001::", 23],
  ["2001:db8::", 32],
  ["2002::", 16],
  ["fc00::", 7],
  ["fe80::", 10],
  ["fec0::", 10],
  ["ff00::", 8],
]) {
  blockedAddresses.addSubnet(network, prefix, "ipv6");
}

export class UnsafeTargetError extends Error {
  constructor(code) {
    super("External review URL is not an allowed public HTTP(S) target");
    this.name = "UnsafeTargetError";
    this.code = code;
  }
}

export function parseTopLevelHttpUrl(raw) {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > MAX_URL_LENGTH) {
    throw new UnsafeTargetError("invalid_length");
  }
  if(/[\u0000-\u001f\u007f]/u.test(raw)) {
    throw new UnsafeTargetError("control_character");
  }

  let parsed;
  try {
    parsed = new URL(raw);
  } catch {
    throw new UnsafeTargetError("invalid_url");
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new UnsafeTargetError("invalid_scheme");
  }
  if (parsed.username || parsed.password) {
    throw new UnsafeTargetError("credentials_not_allowed");
  }
  if (!parsed.hostname || parsed.hostname.length > 253) {
    throw new UnsafeTargetError("invalid_hostname");
  }
  return parsed;
}

export function isPublicAddress(address) {
  const normalized = stripIpv6Brackets(String(address || "").trim());
  const family = net.isIP(normalized);
  if (family === 4) {
    return !blockedAddresses.check(normalized, "ipv4");
  }
  if (family === 6) {
    return !blockedAddresses.check(normalized, "ipv6");
  }
  return false;
}

export async function assertPublicHttpUrl(raw, { lookup = dns.lookup, lookupTimeoutMs = 3_000 } = {}) {
  return (await resolvePublicHttpUrl(raw, { lookup, lookupTimeoutMs })).url;
}

export async function resolvePublicHttpUrl(raw, { lookup = dns.lookup, lookupTimeoutMs = 3_000 } = {}) {
  const parsed = parseTopLevelHttpUrl(raw);
  const hostname = stripIpv6Brackets(parsed.hostname).toLowerCase().replace(/\.$/u, "");
  if (isBlockedHostname(hostname)) {
    throw new UnsafeTargetError("blocked_hostname");
  }

  const directFamily = net.isIP(hostname);
  const resolved = directFamily
    ? [{ address: hostname, family: directFamily }]
    : await lookupAll(lookup, hostname, lookupTimeoutMs);
  if (resolved.length === 0 || resolved.some(({ address }) => !isPublicAddress(address))) {
    throw new UnsafeTargetError("blocked_address");
  }
  return {
    url: parsed,
    addresses: resolved.map((entry) => ({
      address: stripIpv6Brackets(entry.address),
      family: Number(entry.family) || net.isIP(stripIpv6Brackets(entry.address)),
    })),
  };
}

export function createPublicRequestGuard({
  lookup = dns.lookup,
  lookupTimeoutMs = 3_000,
  isMainNavigation = () => false,
  maxMainNavigations = 10,
  onBlocked = () => {},
} = {}) {
  let mainNavigations = 0;
  const redirectLimit = Math.max(1, Math.min(Number(maxMainNavigations) || 10, 20));

  return async (route) => {
    const request = route.request();
    const rawUrl = request.url();
    try {
      const parsed = new URL(rawUrl);
      if (["about:", "blob:", "data:"].includes(parsed.protocol)) {
        await route.continue();
        return;
      }
      if (parsed.protocol === "ws:" || parsed.protocol === "wss:") {
        throw new UnsafeTargetError("websocket_blocked");
      }
      if (isMainNavigation(request)) {
        mainNavigations += 1;
        if (mainNavigations > redirectLimit) {
          throw new UnsafeTargetError("redirect_limit");
        }
      }

      // Resolve every network request immediately before Chromium receives it.
      // This checks redirects and makes a DNS-rebinding race materially smaller.
      await assertPublicHttpUrl(rawUrl, { lookup, lookupTimeoutMs });
      await route.continue();
    } catch (error) {
      const code = error instanceof UnsafeTargetError ? error.code : "resolution_failed";
      onBlocked(code);
      await route.abort("blockedbyclient");
    }
  };
}

function isBlockedHostname(hostname) {
  return hostname === "localhost"
    || hostname.length === 0
    || BLOCKED_HOST_SUFFIXES.some((suffix) => hostname.endsWith(suffix));
}

async function lookupAll(lookup, hostname, timeoutMs) {
  let timer;
  let result;
  try {
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(
        () => reject(new UnsafeTargetError("resolution_timeout")),
        Math.max(250, Math.min(Number(timeoutMs) || 3_000, 10_000)),
      );
      timer.unref();
    });
    result = await Promise.race([
      lookup(hostname, { all: true, verbatim: true }),
      timeout,
    ]);
  } catch (error) {
    if (error instanceof UnsafeTargetError) {
      throw error;
    }
    throw new UnsafeTargetError("resolution_failed");
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
  }
  const entries = Array.isArray(result) ? result : [result];
  return entries
    .map((entry) => typeof entry === "string" ? { address: entry } : entry)
    .filter((entry) => entry && typeof entry.address === "string");
}

function stripIpv6Brackets(value) {
  return value.startsWith("[") && value.endsWith("]") ? value.slice(1, -1) : value;
}
