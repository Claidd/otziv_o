const express = require("express");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const QRCode = require("qrcode");
const qrcodeTerminal = require("qrcode-terminal");
const { Client, LocalAuth } = require("whatsapp-web.js");
const {
  DeliveredMessageCache,
  FileDeliveryIdempotencyStore,
  ParticipantPhoneResolver,
  RecentOutboundRegistry,
  createMessageHandler,
  generatedOutboundKey,
  reconciliationPayloads,
} = require("./message-webhook");
const {
  findGroupByInviteCode,
  groupFromInviteInfo,
  normalizeInviteCode,
  serializedGroupId,
} = require("./group-invite");
const { selectGroupsCache } = require("./groups-cache");
const {
  boundedBodyBytes,
  createConcurrencyMiddleware,
  createInternalAuthMiddleware,
} = require("./internal-auth");
const { chromiumLaunchArgs } = require("./chromium-launch");
const { fetchRecentMessagesFromRawChat } = require("./raw-chat-reconciliation");

const CLIENT_ID = process.env.CLIENT_ID || "whatsapp_default";
const PORT = Number.parseInt(process.env.PORT || "3000", 10);
const SERVER_URL = trimTrailingSlash(process.env.SERVER_URL || "http://app:8080");
const AUTH_PATH = process.env.AUTH_PATH || "/auth";
const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET || "";
const GATEWAY_SHARED_SECRET = process.env.WHATSAPP_GATEWAY_SHARED_SECRET || "";
const GATEWAY_AUTH_REQUIRED = parseBoolean(process.env.WHATSAPP_GATEWAY_AUTH_REQUIRED);
const WHATSAPP_QR_LOG_ENABLED = parseBoolean(process.env.WHATSAPP_QR_LOG_ENABLED);
const CHROMIUM_PATH = process.env.PUPPETEER_EXECUTABLE_PATH || "/usr/bin/chromium";
const WHATSAPP_PROXY_ENABLED = parseBoolean(process.env.WHATSAPP_PROXY_ENABLED);
const WHATSAPP_PROXY_HOST = String(process.env.WHATSAPP_PROXY_HOST || "").trim();
const WHATSAPP_PROXY_PORT = String(process.env.WHATSAPP_PROXY_PORT || "8888").trim();
const WHATSAPP_PROXY_TYPE = String(process.env.WHATSAPP_PROXY_TYPE || "http").trim().toLowerCase();
const WHATSAPP_GROUPS_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_GROUPS_TIMEOUT_MS, 10000);
const WHATSAPP_GROUPS_RESPONSE_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_GROUPS_RESPONSE_TIMEOUT_MS, 25000);
const WHATSAPP_GROUPS_CACHE_TTL_MS = parsePositiveInt(process.env.WHATSAPP_GROUPS_CACHE_TTL_MS, 600000);
const WHATSAPP_GROUP_INVITE_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_GROUP_INVITE_TIMEOUT_MS, 2000);
const WHATSAPP_GROUP_INVITE_CONCURRENCY = Math.min(parsePositiveInt(process.env.WHATSAPP_GROUP_INVITE_CONCURRENCY, 16), 32);
const WHATSAPP_RECONCILE_CONCURRENCY = Math.min(parsePositiveInt(process.env.WHATSAPP_RECONCILE_CONCURRENCY, 4), 8);
const WHATSAPP_PUPPETEER_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_PUPPETEER_TIMEOUT_MS, 300000);
const WHATSAPP_READY_AFTER_AUTH_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_READY_AFTER_AUTH_TIMEOUT_MS, 300000);
const WHATSAPP_STARTUP_READY_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_STARTUP_READY_TIMEOUT_MS, 600000);
const WHATSAPP_READY_WATCHDOG_INTERVAL_MS = parsePositiveInt(process.env.WHATSAPP_READY_WATCHDOG_INTERVAL_MS, 15000);
const WHATSAPP_WEBHOOK_ATTEMPTS = parsePositiveInt(process.env.WHATSAPP_WEBHOOK_ATTEMPTS, 3);
const WHATSAPP_WEBHOOK_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_WEBHOOK_TIMEOUT_MS, 10000);
const WHATSAPP_WEBHOOK_RETRY_DELAY_MS = parsePositiveInt(process.env.WHATSAPP_WEBHOOK_RETRY_DELAY_MS, 500);
const WHATSAPP_MESSAGE_DEDUP_TTL_MS = parsePositiveInt(process.env.WHATSAPP_MESSAGE_DEDUP_TTL_MS, 86400000);
const WHATSAPP_OUTBOUND_MARK_TTL_MS = parsePositiveInt(process.env.WHATSAPP_OUTBOUND_MARK_TTL_MS, 600000);
const WHATSAPP_OUTBOUND_DURABLE_TTL_MS = parsePositiveInt(
  process.env.WHATSAPP_OUTBOUND_DURABLE_TTL_MS,
  2592000000
);
const WHATSAPP_LID_PHONE_CACHE_TTL_MS = parsePositiveInt(
  process.env.WHATSAPP_LID_PHONE_CACHE_TTL_MS,
  604800000
);
const WHATSAPP_LID_PHONE_LOOKUP_TIMEOUT_MS = parsePositiveInt(
  process.env.WHATSAPP_LID_PHONE_LOOKUP_TIMEOUT_MS,
  5000
);
const WHATSAPP_HTTP_BODY_LIMIT = boundedBodyBytes(process.env.WHATSAPP_HTTP_BODY_LIMIT);
const WHATSAPP_HTTP_MAX_CONCURRENCY = parsePositiveInt(process.env.WHATSAPP_HTTP_MAX_CONCURRENCY, 16);
const WHATSAPP_MAX_MESSAGE_CHARS = Math.min(
  parsePositiveInt(process.env.WHATSAPP_MAX_MESSAGE_CHARS, 20000),
  50000
);

let client = null;
let ready = false;
let authenticated = false;
let currentQr = null;
let currentQrDataUrl = null;
let clientStartedAt = null;
let authenticatedAt = null;
let lastQrAt = null;
let lastReadyAt = null;
let lastState = "starting";
let lastError = null;
let restartTimer = null;
let readyWatchdogTimer = null;
let groupsCache = null;
let groupsCacheAt = null;
let groupsRefreshPromise = null;
let lastGroupsSuccessAt = null;
let lastGroupsError = null;
let lastGroupsErrorAt = null;
const outboundRegistry = new RecentOutboundRegistry(WHATSAPP_OUTBOUND_MARK_TTL_MS);
const deliveryIdempotencyStore = new FileDeliveryIdempotencyStore(
  process.env.WHATSAPP_DELIVERY_IDEMPOTENCY_PATH || path.join(AUTH_PATH, "delivery-idempotency.json")
);
const deliveredMessageCache = new DeliveredMessageCache(
  WHATSAPP_MESSAGE_DEDUP_TTL_MS,
  () => Date.now(),
  deliveryIdempotencyStore
);
const participantPhoneResolver = new ParticipantPhoneResolver({
  ttlMs: WHATSAPP_LID_PHONE_CACHE_TTL_MS,
  log,
  lookup: (participantIds) => {
    if (!client || typeof client.getContactLidAndPhone !== "function") {
      return [];
    }
    return withTimeout(
      () => client.getContactLidAndPhone(participantIds),
      WHATSAPP_LID_PHONE_LOOKUP_TIMEOUT_MS,
      "WhatsApp participant LID lookup"
    );
  },
});

const app = express();

function log(level, message, extra = {}) {
  const entry = {
    ts: new Date().toISOString(),
    level,
    clientId: CLIENT_ID,
    message,
    ...extra,
  };
  const line = JSON.stringify(entry);
  if (level === "error") {
    console.error(line);
  } else if (level === "warn") {
    console.warn(line);
  } else {
    console.log(line);
  }
}

function trimTrailingSlash(value) {
  return String(value || "").replace(/\/+$/, "");
}

function parseBoolean(value) {
  return ["1", "true", "yes", "on"].includes(String(value || "").trim().toLowerCase());
}

function parsePositiveInt(value, fallback) {
  const parsed = Number.parseInt(String(value || ""), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function errorMessage(error) {
  if (error && error.stack) {
    return String(error.stack);
  }
  if (error && error.message) {
    return String(error.message);
  }
  return String(error || "Unknown error");
}

function isRecoverableBrowserError(error) {
  const message = errorMessage(error);
  return /ProtocolError|Runtime\.callFunctionOn timed out|Network\.getResponseBody|Target closed|Session closed|Execution context was destroyed|Navigation timeout/i
    .test(message);
}

function handleProcessError(kind, error) {
  const message = errorMessage(error);
  lastError = message.split(/\r?\n/, 1)[0] || message;
  log("error", `Unhandled ${kind}`, { error: message });

  if (isRecoverableBrowserError(error)) {
    lastState = `recovering_${kind}`;
    scheduleRestart();
    return;
  }

  setTimeout(() => process.exit(1), 100);
}

async function withTimeout(promiseFactory, timeoutMs, description) {
  let timer = null;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => {
      reject(new Error(`${description} timed out after ${timeoutMs}ms`));
    }, timeoutMs);
  });

  try {
    return await Promise.race([Promise.resolve().then(promiseFactory), timeout]);
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
  }
}

async function mapWithConcurrency(items, concurrency, mapper) {
  if (!items.length) {
    return [];
  }

  const limit = Math.max(1, Math.min(concurrency, items.length));
  const results = new Array(items.length);
  let nextIndex = 0;

  async function worker() {
    while (nextIndex < items.length) {
      const index = nextIndex;
      nextIndex += 1;
      results[index] = await mapper(items[index], index);
    }
  }

  await Promise.all(Array.from({ length: limit }, worker));
  return results;
}

function proxyServerArg() {
  if (!WHATSAPP_PROXY_ENABLED || !WHATSAPP_PROXY_HOST) {
    return null;
  }

  const scheme = ["socks4", "socks5", "http", "https"].includes(WHATSAPP_PROXY_TYPE)
    ? WHATSAPP_PROXY_TYPE
    : "http";
  const host = WHATSAPP_PROXY_HOST.includes(":") && !WHATSAPP_PROXY_HOST.startsWith("[")
    ? `[${WHATSAPP_PROXY_HOST}]`
    : WHATSAPP_PROXY_HOST;
  return `${scheme}://${host}:${WHATSAPP_PROXY_PORT}`;
}

function removeStaleChromiumLocks(rootPath) {
  const lockNames = new Set(["SingletonCookie", "SingletonLock", "SingletonSocket"]);

  function visit(currentPath) {
    let entries = [];
    try {
      entries = fs.readdirSync(currentPath, { withFileTypes: true });
    } catch (error) {
      return;
    }

    for (const entry of entries) {
      const entryPath = path.join(currentPath, entry.name);
      if (entry.isDirectory()) {
        visit(entryPath);
        continue;
      }
      if (!lockNames.has(entry.name)) {
        continue;
      }

      try {
        fs.rmSync(entryPath, { force: true });
        log("info", "Removed stale Chromium profile lock", { lockFile: entryPath });
      } catch (error) {
        log("warn", "Failed to remove stale Chromium profile lock", {
          lockFile: entryPath,
          error: error.message,
        });
      }
    }
  }

  visit(rootPath);
}

function normalizePhone(raw) {
  const digits = String(raw || "").replace(/\D+/g, "");
  if (!digits) {
    return "";
  }
  const normalized = digits.startsWith("8") && digits.length === 11
    ? `7${digits.slice(1)}`
    : digits;
  return `${normalized}@c.us`;
}

function normalizeGroupId(raw) {
  return serializedGroupId(raw);
}

function statusPayload() {
  return {
    status: "ok",
    clientId: CLIENT_ID,
    ready,
    authenticated,
    state: lastState,
    lastQrAt,
    lastReadyAt,
    clientStartedAt,
    authenticatedAt,
    lastError,
    hasQr: Boolean(currentQr),
    proxyEnabled: WHATSAPP_PROXY_ENABLED,
    proxyConfigured: Boolean(proxyServerArg()),
    puppeteerTimeoutMs: WHATSAPP_PUPPETEER_TIMEOUT_MS,
    readyAfterAuthTimeoutMs: WHATSAPP_READY_AFTER_AUTH_TIMEOUT_MS,
    startupReadyTimeoutMs: WHATSAPP_STARTUP_READY_TIMEOUT_MS,
    groupsTimeoutMs: WHATSAPP_GROUPS_TIMEOUT_MS,
    groupsResponseTimeoutMs: WHATSAPP_GROUPS_RESPONSE_TIMEOUT_MS,
    groupsCacheTtlMs: WHATSAPP_GROUPS_CACHE_TTL_MS,
    groupInviteTimeoutMs: WHATSAPP_GROUP_INVITE_TIMEOUT_MS,
    groupInviteConcurrency: WHATSAPP_GROUP_INVITE_CONCURRENCY,
    groupsCachedAt: groupsCacheAt,
    lastGroupsSuccessAt,
    lastGroupsError,
    lastGroupsErrorAt,
  };
}

function minimalStatusPayload() {
  return {
    status: ready && authenticated ? "ok" : "not_ready",
    ready,
    authenticated,
    state: lastState,
    hasQr: Boolean(currentQr),
  };
}

function requireReady(res) {
  if (ready && client) {
    return true;
  }
  res.status(503).json({
    ...statusPayload(),
    status: "not_ready",
    message: "WhatsApp client is not ready",
  });
  return false;
}

function asyncRoute(handler) {
  return (req, res, next) => {
    Promise.resolve(handler(req, res, next)).catch(next);
  };
}

function createClient() {
  removeStaleChromiumLocks(AUTH_PATH);

  const launchArgs = chromiumLaunchArgs(proxyServerArg());

  return new Client({
    authStrategy: new LocalAuth({
      clientId: CLIENT_ID,
      dataPath: AUTH_PATH,
    }),
    webVersionCache: {
      // The production container is intentionally read-only. The library's
      // default local cache writes to /app before it injects WWebJS, which
      // leaves an authenticated session permanently stuck before READY.
      type: "none",
    },
    puppeteer: {
      executablePath: CHROMIUM_PATH,
      headless: true,
      timeout: WHATSAPP_PUPPETEER_TIMEOUT_MS,
      protocolTimeout: WHATSAPP_PUPPETEER_TIMEOUT_MS,
      args: launchArgs,
    },
  });
}

function wireClientEvents(instance) {
  instance.on("qr", async (qr) => {
    currentQr = qr;
    lastQrAt = new Date().toISOString();
    lastState = "qr";
    ready = false;
    authenticated = false;
    authenticatedAt = null;
    try {
      currentQrDataUrl = await QRCode.toDataURL(qr);
    } catch (error) {
      currentQrDataUrl = null;
      lastError = error.message;
      log("warn", "QR data URL generation failed", { error: error.message });
    }
    log("info", "QR received; retrieve it through authenticated GET /qr");
    if (WHATSAPP_QR_LOG_ENABLED) {
      qrcodeTerminal.generate(qr, { small: true });
    }
  });

  instance.on("authenticated", () => {
    authenticated = true;
    authenticatedAt = new Date().toISOString();
    lastState = "authenticated";
    log("info", "Authenticated");
  });

  instance.on("auth_failure", (message) => {
    authenticated = false;
    ready = false;
    authenticatedAt = null;
    lastState = "auth_failure";
    lastError = String(message || "Authentication failure");
    log("error", "Authentication failure", { error: lastError });
    scheduleRestart();
  });

  instance.on("ready", () => {
    ready = true;
    authenticated = true;
    currentQr = null;
    currentQrDataUrl = null;
    authenticatedAt = authenticatedAt || new Date().toISOString();
    lastReadyAt = new Date().toISOString();
    lastState = "ready";
    lastError = null;
    log("info", "WhatsApp client ready");
  });

  instance.on("change_state", (state) => {
    lastState = String(state || "unknown");
    log("info", "State changed", { state: lastState });
  });

  instance.on("disconnected", (reason) => {
    ready = false;
    authenticated = false;
    authenticatedAt = null;
    lastState = "disconnected";
    lastError = String(reason || "Disconnected");
    log("warn", "Client disconnected", { reason: lastError });
    scheduleRestart();
  });

  instance.on("message_create", (message) => {
    handleIncomingMessage(message).catch((error) => {
      log("warn", "Message webhook failed", {
        stage: error.stage || "delivery",
        path: error.path,
        status: error.status,
        attempt: error.attempt,
        error: error.message,
      });
    });
  });
}

async function startClient() {
  ready = false;
  authenticated = false;
  authenticatedAt = null;
  currentQr = null;
  currentQrDataUrl = null;
  lastQrAt = null;
  clientStartedAt = new Date().toISOString();
  lastState = "starting";
  client = createClient();
  wireClientEvents(client);
  log("info", "Initializing WhatsApp client", {
    authPath: AUTH_PATH,
    proxyEnabled: WHATSAPP_PROXY_ENABLED,
    proxyConfigured: Boolean(proxyServerArg()),
    proxyHost: WHATSAPP_PROXY_ENABLED && WHATSAPP_PROXY_HOST ? WHATSAPP_PROXY_HOST : undefined,
    proxyPort: WHATSAPP_PROXY_ENABLED && WHATSAPP_PROXY_HOST ? WHATSAPP_PROXY_PORT : undefined,
    proxyType: WHATSAPP_PROXY_ENABLED && WHATSAPP_PROXY_HOST ? WHATSAPP_PROXY_TYPE : undefined,
  });
  await client.initialize();
}

function startReadyWatchdog() {
  if (readyWatchdogTimer) {
    return;
  }

  readyWatchdogTimer = setInterval(() => {
    if (ready || restartTimer) {
      return;
    }

    const now = Date.now();
    if (authenticated && authenticatedAt) {
      const authenticatedAgeMs = now - Date.parse(authenticatedAt);
      if (authenticatedAgeMs >= WHATSAPP_READY_AFTER_AUTH_TIMEOUT_MS) {
        lastError = `Ready event was not received ${authenticatedAgeMs}ms after authentication`;
        log("warn", "WhatsApp ready watchdog restarting authenticated client", {
          authenticatedAgeMs,
          readyAfterAuthTimeoutMs: WHATSAPP_READY_AFTER_AUTH_TIMEOUT_MS,
          state: lastState,
        });
        scheduleRestart();
      }
      return;
    }

    if (currentQr || !clientStartedAt) {
      return;
    }

    const startupAgeMs = now - Date.parse(clientStartedAt);
    if (startupAgeMs >= WHATSAPP_STARTUP_READY_TIMEOUT_MS) {
      lastError = `Ready event was not received ${startupAgeMs}ms after startup`;
      log("warn", "WhatsApp ready watchdog restarting startup-stuck client", {
        startupAgeMs,
        startupReadyTimeoutMs: WHATSAPP_STARTUP_READY_TIMEOUT_MS,
        state: lastState,
      });
      scheduleRestart();
    }
  }, WHATSAPP_READY_WATCHDOG_INTERVAL_MS);
}

function scheduleRestart() {
  if (restartTimer) {
    return;
  }
  restartTimer = setTimeout(async () => {
    restartTimer = null;
    try {
      if (client) {
        await client.destroy();
      }
    } catch (error) {
      log("warn", "Destroy before restart failed", { error: error.message });
    }
    try {
      await startClient();
    } catch (error) {
      lastError = error.message;
      log("error", "Restart failed", { error: error.message });
      scheduleRestart();
    }
  }, 5000);
}

const handleIncomingMessage = createMessageHandler({
  clientId: CLIENT_ID,
  outboundRegistry,
  participantResolver: participantPhoneResolver,
  postWebhook: postBackendWebhook,
  log,
});

async function postBackendWebhook(path, payload) {
  const body = JSON.stringify(payload);
  const headers = { "Content-Type": "application/json" };
  if (WEBHOOK_SECRET) {
    headers["X-WhatsApp-Webhook-Secret"] = WEBHOOK_SECRET;
    headers["X-WhatsApp-Webhook-Signature"] = `sha256=${crypto
      .createHmac("sha256", WEBHOOK_SECRET)
      .update(body, "utf8")
      .digest("hex")}`;
  }

  return deliveredMessageCache.deliver(path, payload, async () => {
    let lastError = null;
    for (let attempt = 1; attempt <= WHATSAPP_WEBHOOK_ATTEMPTS; attempt += 1) {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), WHATSAPP_WEBHOOK_TIMEOUT_MS);
      try {
        const response = await fetch(`${SERVER_URL}${path}`, {
          method: "POST",
          headers,
          body,
          signal: controller.signal,
        });
        if (response.ok) {
          if (attempt > 1) {
            log("info", "Backend webhook delivered after retry", {
              path,
              attempt,
              groupId: payload.groupId,
              messageId: payload.messageId,
            });
          }
          return { status: response.status, attempt };
        }
        const responseText = await response.text().catch(() => "");
        const error = new Error(`Backend webhook returned ${response.status}${responseText ? `: ${responseText}` : ""}`);
        error.status = response.status;
        error.retryable = response.status === 408 || response.status === 429 || response.status >= 500;
        throw error;
      } catch (error) {
        error.path = path;
        error.stage = error.name === "AbortError" ? "timeout" : "post_webhook";
        error.attempt = attempt;
        lastError = error;
        const retryable = error.retryable !== false && (!error.status || error.status === 408 || error.status === 429 || error.status >= 500);
        if (!retryable || attempt >= WHATSAPP_WEBHOOK_ATTEMPTS) {
          throw error;
        }
        log("warn", "Backend webhook attempt failed; retry scheduled", {
          stage: error.stage,
          path,
          status: error.status,
          attempt,
          nextAttempt: attempt + 1,
          groupId: payload.groupId,
          messageId: payload.messageId,
          error: error.message,
        });
        await new Promise((resolve) => setTimeout(resolve, WHATSAPP_WEBHOOK_RETRY_DELAY_MS * attempt));
      } finally {
        clearTimeout(timeout);
      }
    }
    throw lastError || new Error(`Backend webhook ${path} failed`);
  });
}

async function inviteInfo(groupChat) {
  const groupId = groupChat.id && groupChat.id._serialized ? groupChat.id._serialized : null;
  if (typeof groupChat.getInviteCode !== "function") {
    return { inviteCode: null, inviteLink: null };
  }

  try {
    const inviteCode = await withTimeout(
      () => groupChat.getInviteCode(),
      WHATSAPP_GROUP_INVITE_TIMEOUT_MS,
      "Invite code lookup"
    );
    return {
      inviteCode,
      inviteLink: inviteCode ? `https://chat.whatsapp.com/${inviteCode}` : null,
    };
  } catch (error) {
    const level = error.message.includes("timed out") ? "warn" : "debug";
    log(level, "Invite code unavailable", {
      groupId,
      error: error.message,
    });
    return { inviteCode: null, inviteLink: null };
  }
}

async function inviteInfoByGroupId(groupId) {
  if (!groupId) {
    return { inviteCode: null, inviteLink: null };
  }

  try {
    const inviteCode = await withTimeout(
      () => client.pupPage.evaluate(async (chatId) => {
        try {
          const result = await window
            .require("WAWebMexFetchGroupInviteCodeJob")
            .fetchMexGroupInviteCode(chatId);
          return result && result.code ? result.code : result;
        } catch (error) {
          if (error && error.name === "ServerStatusCodeError") {
            return null;
          }
          throw error;
        }
      }, groupId),
      WHATSAPP_GROUP_INVITE_TIMEOUT_MS,
      "Invite code lookup"
    );
    return {
      inviteCode,
      inviteLink: inviteCode ? `https://chat.whatsapp.com/${inviteCode}` : null,
    };
  } catch (error) {
    const level = error.message.includes("timed out") ? "warn" : "debug";
    log(level, "Invite code unavailable through minimal group fallback", {
      groupId,
      error: error.message,
    });
    return { inviteCode: null, inviteLink: null };
  }
}

async function loadMinimalGroupsSnapshot() {
  const snapshot = await withTimeout(
    () => client.pupPage.evaluate(() => {
      const chatCollection = window.require("WAWebCollections").Chat;
      const chats = typeof chatCollection.getModelsArray === "function"
        ? chatCollection.getModelsArray()
        : Array.isArray(chatCollection.models)
          ? chatCollection.models
          : Object.values(chatCollection.models || {});

      const groups = chats
        .map((chat) => {
          const id = chat && chat.id;
          const groupId = id && (
            id._serialized
            || (typeof id.toString === "function" ? id.toString() : "")
          );
          const name = chat && (
            chat.name
            || chat.formattedTitle
            || (chat.groupMetadata && chat.groupMetadata.subject)
          );
          return {
            groupId: typeof groupId === "string" ? groupId : "",
            name: typeof name === "string" ? name : "",
          };
        })
        .filter((chat) => chat.groupId.endsWith("@g.us"));

      return {
        groups,
        totalChats: chats.length,
      };
    }),
    WHATSAPP_GROUPS_TIMEOUT_MS,
    "Minimal group list lookup"
  );

  const groups = await mapWithConcurrency(
    snapshot.groups,
    WHATSAPP_GROUP_INVITE_CONCURRENCY,
    async (chat) => {
      const invite = await inviteInfoByGroupId(chat.groupId);
      return {
        groupId: chat.groupId,
        id: chat.groupId,
        chatId: chat.groupId,
        name: chat.name,
        title: chat.name,
        subject: chat.name,
        inviteCode: invite.inviteCode,
        inviteLink: invite.inviteLink,
        link: invite.inviteLink,
      };
    }
  );

  return {
    groups,
    totalChats: snapshot.totalChats,
  };
}

function groupsPayload(snapshot, extra = {}) {
  const groups = snapshot && Array.isArray(snapshot.groups) ? snapshot.groups : [];
  return {
    status: "ok",
    clientId: CLIENT_ID,
    groups,
    totalChats: snapshot ? snapshot.totalChats : 0,
    groupCount: groups.length,
    inviteCount: groups.filter((group) => Boolean(group.inviteLink)).length,
    groupsCachedAt: groupsCacheAt,
    ...extra,
  };
}

async function loadGroupsSnapshot() {
  let chats;
  try {
    chats = await withTimeout(
      () => client.getChats(),
      WHATSAPP_GROUPS_TIMEOUT_MS,
      "Group list lookup"
    );
  } catch (error) {
    log("warn", "Full WhatsApp chat serialization failed; using minimal group fallback", {
      error: errorMessage(error),
    });
    return loadMinimalGroupsSnapshot();
  }
  const groupChats = chats.filter((item) => item.isGroup);
  const groups = await mapWithConcurrency(groupChats, WHATSAPP_GROUP_INVITE_CONCURRENCY, async (chat) => {
    const groupId = chat.id && chat.id._serialized ? chat.id._serialized : null;
    const invite = await inviteInfo(chat);
    return {
      groupId,
      id: groupId,
      chatId: groupId,
      name: chat.name || "",
      title: chat.name || "",
      subject: chat.name || "",
      inviteCode: invite.inviteCode,
      inviteLink: invite.inviteLink,
      link: invite.inviteLink,
    };
  });

  return {
    groups,
    totalChats: chats.length,
  };
}

function freshGroupsCache() {
  if (!groupsCache || !groupsCacheAt) {
    return null;
  }

  const ageMs = Date.now() - Date.parse(groupsCacheAt);
  return ageMs >= 0 && ageMs <= WHATSAPP_GROUPS_CACHE_TTL_MS ? groupsCache : null;
}

function startGroupsRefresh() {
  if (groupsRefreshPromise) {
    return groupsRefreshPromise;
  }

  groupsRefreshPromise = loadGroupsSnapshot()
    .then((snapshot) => {
      groupsCache = snapshot;
      groupsCacheAt = new Date().toISOString();
      lastGroupsSuccessAt = groupsCacheAt;
      lastGroupsError = null;
      lastGroupsErrorAt = null;
      log("info", "WhatsApp groups cache refreshed", {
        totalChats: snapshot.totalChats,
        groupCount: snapshot.groups.length,
        inviteCount: snapshot.groups.filter((group) => Boolean(group.inviteLink)).length,
      });
      return snapshot;
    })
    .catch((error) => {
      lastGroupsError = errorMessage(error);
      lastGroupsErrorAt = new Date().toISOString();
      log("warn", "WhatsApp groups cache refresh failed", {
        error: lastGroupsError,
      });
      throw error;
    })
    .finally(() => {
      groupsRefreshPromise = null;
    });

  return groupsRefreshPromise;
}

app.get("/health", (req, res) => {
  res.json(minimalStatusPayload());
});

app.get("/ready", (req, res) => {
  if (!ready || !authenticated) {
    res.status(503).json(minimalStatusPayload());
    return;
  }
  res.json(minimalStatusPayload());
});

app.use(createInternalAuthMiddleware({
  secret: GATEWAY_SHARED_SECRET,
  required: GATEWAY_AUTH_REQUIRED,
}));
app.use(express.json({ limit: WHATSAPP_HTTP_BODY_LIMIT, strict: true }));
app.use(createConcurrencyMiddleware(WHATSAPP_HTTP_MAX_CONCURRENCY));

app.get("/qr", asyncRoute(async (req, res) => {
  if (!currentQr) {
    res.status(404).json({
      ...statusPayload(),
      status: "qr_unavailable",
      message: ready ? "Client is already ready" : "QR has not been emitted yet",
    });
    return;
  }

  if (!currentQrDataUrl) {
    currentQrDataUrl = await QRCode.toDataURL(currentQr);
  }

  res.json({
    ...statusPayload(),
    qr: currentQr,
    qrDataUrl: currentQrDataUrl,
  });
}));

app.post("/send", asyncRoute(async (req, res) => {
  if (!requireReady(res)) {
    return;
  }

  const phone = normalizePhone(req.body.phone || req.body.to || req.body.number);
  const message = String(req.body.message || "").trim();
  if (!phone || !message || message.length > WHATSAPP_MAX_MESSAGE_CHARS) {
    res.status(400).json({ status: "error", code: "invalid_request" });
    return;
  }

  const sent = await client.sendMessage(phone, message);
  res.json({
    status: "ok",
    clientId: CLIENT_ID,
    to: phone,
    messageId: sent && sent.id ? sent.id._serialized : null,
  });
}));

app.post("/send-group", asyncRoute(async (req, res) => {
  if (!requireReady(res)) {
    return;
  }

  const groupId = normalizeGroupId(req.body.groupId || req.body.chatId || req.body.to);
  const message = String(req.body.message || "").trim();
  if (!groupId || !message || message.length > WHATSAPP_MAX_MESSAGE_CHARS) {
    res.status(400).json({ status: "error", code: "invalid_request" });
    return;
  }

  const outboundToken = outboundRegistry.begin(groupId, message);
  let sent;
  try {
    sent = await client.sendMessage(groupId, message);
    const sentMessageId = sent && sent.id ? sent.id._serialized : null;
    outboundRegistry.complete(outboundToken, sentMessageId);
    if (sentMessageId) {
      try {
        await deliveryIdempotencyStore.mark(
          generatedOutboundKey(sentMessageId),
          Date.now() + WHATSAPP_OUTBOUND_DURABLE_TTL_MS
        );
      } catch (error) {
        log("warn", "Generated outbound identity persistence failed", {
          messageId: sentMessageId,
          error: errorMessage(error),
        });
      }
    }
  } catch (error) {
    outboundRegistry.cancel(outboundToken);
    throw error;
  }
  res.json({
    status: "ok",
    clientId: CLIENT_ID,
    groupId,
    messageId: sent && sent.id ? sent.id._serialized : null,
  });
}));

app.get("/groups", asyncRoute(async (req, res) => {
  if (!requireReady(res)) {
    return;
  }

  const forceRefresh = String(req.query.refresh || "") === "1";
  const cacheSelection = selectGroupsCache(forceRefresh, freshGroupsCache(), groupsCache);
  if (cacheSelection.snapshot) {
    if (cacheSelection.stale && !groupsRefreshPromise) {
      void startGroupsRefresh().catch((error) => {
        log("warn", "Background groups cache refresh failed", { error: error.message });
      });
    }
    res.json(groupsPayload(cacheSelection.snapshot, {
      cached: true,
      stale: cacheSelection.stale,
      refreshInProgress: Boolean(groupsRefreshPromise),
    }));
    return;
  }

  try {
    const snapshot = await withTimeout(
      () => startGroupsRefresh(),
      WHATSAPP_GROUPS_RESPONSE_TIMEOUT_MS,
      "Groups cache refresh"
    );
    res.json(groupsPayload(snapshot, { cached: false, refreshInProgress: false }));
  } catch (error) {
    const detail = errorMessage(error);
    log("warn", "Groups cache refresh unavailable", { error: detail });
    const payload = groupsPayload(groupsCache, {
      status: groupsCache ? "ok" : "groups_unavailable",
      cached: Boolean(groupsCache),
      refreshInProgress: Boolean(groupsRefreshPromise),
      message: groupsCache ? "Returning stale groups cache" : "WhatsApp group metadata is unavailable",
      error: groupsCache ? undefined : detail,
    });
    res.status(groupsCache ? 200 : 503).json(payload);
  }
}));

app.post("/groups/reconcile-messages", asyncRoute(async (req, res) => {
  if (!requireReady(res)) {
    return;
  }

  const cursorsByGroup = new Map();
  for (const cursor of Array.isArray(req.body && req.body.chats) ? req.body.chats : []) {
    const groupId = normalizeGroupId(cursor && (cursor.groupId || cursor.chatId));
    const timestamp = Number(cursor && cursor.afterTimestamp);
    if (!groupId || !Number.isFinite(timestamp)) {
      continue;
    }
    const afterTimestamp = Math.max(0, Math.floor(timestamp));
    const previous = cursorsByGroup.get(groupId);
    if (previous == null && cursorsByGroup.size >= 50) {
      continue;
    }
    cursorsByGroup.set(groupId, previous == null ? afterTimestamp : Math.min(previous, afterTimestamp));
  }
  const cursors = Array.from(cursorsByGroup, ([groupId, afterTimestamp]) => ({ groupId, afterTimestamp }));
  if (cursors.length === 0) {
    res.json({ status: "ok", clientId: CLIENT_ID, messages: [] });
    return;
  }

  const batches = await mapWithConcurrency(cursors, WHATSAPP_RECONCILE_CONCURRENCY, async (cursor) => {
    const { groupId, afterTimestamp } = cursor;
    try {
      const direct = await withTimeout(
        async () => {
          const chat = await client.getChatById(groupId);
          if (!chat) {
            throw new Error(`WhatsApp chat ${groupId} was not found`);
          }
          return {
            groupName: chat.name || "",
            messages: await chat.fetchMessages({ limit: 100 }),
          };
        },
        WHATSAPP_GROUPS_TIMEOUT_MS,
        `Recent messages for ${groupId}`
      );
      return reconciliationPayloads({
        clientId: CLIENT_ID,
        groupId,
        groupName: direct.groupName,
        afterTimestamp,
        messages: direct.messages,
        outboundRegistry,
        generatedMessageStore: deliveryIdempotencyStore,
        participantResolver: participantPhoneResolver,
        log,
      });
    } catch (primaryError) {
      try {
        const raw = await withTimeout(
          () => fetchRecentMessagesFromRawChat(client, groupId, 100),
          WHATSAPP_GROUPS_TIMEOUT_MS,
          `Raw recent messages for ${groupId}`
        );
        if (!raw.found) {
          log("warn", "WhatsApp message reconciliation group is unavailable", {
            groupId,
            primaryError: errorMessage(primaryError),
          });
          return [];
        }
        const cachedGroup = groupsCache && Array.isArray(groupsCache.groups)
          ? groupsCache.groups.find((group) => group && group.groupId === groupId)
          : null;
        log("info", "WhatsApp message reconciliation used raw chat fallback", {
          groupId,
          messageCount: raw.messages.length,
          primaryError: errorMessage(primaryError).split(/\r?\n/, 1)[0],
        });
        return reconciliationPayloads({
          clientId: CLIENT_ID,
          groupId,
          groupName: raw.groupName || cachedGroup && cachedGroup.name || "",
          afterTimestamp,
          messages: raw.messages,
          outboundRegistry,
          generatedMessageStore: deliveryIdempotencyStore,
          participantResolver: participantPhoneResolver,
          log,
        });
      } catch (fallbackError) {
        log("warn", "WhatsApp message reconciliation skipped for group", {
          groupId,
          primaryError: errorMessage(primaryError),
          fallbackError: errorMessage(fallbackError),
        });
        return [];
      }
    }
  });

  const uniqueMessages = new Map();
  for (const message of batches.flat()) {
    const key = `${message.groupId}|${message.messageId}`;
    if (!uniqueMessages.has(key)) {
      uniqueMessages.set(key, message);
    }
  }
  const messages = Array.from(uniqueMessages.values());

  messages.sort((left, right) =>
    Number(left.timestamp || 0) - Number(right.timestamp || 0)
      || String(left.messageId || "").localeCompare(String(right.messageId || ""))
  );
  res.json({
    status: "ok",
    clientId: CLIENT_ID,
    messages,
  });
}));

app.post("/groups/resolve-invite", asyncRoute(async (req, res) => {
  if (!requireReady(res)) {
    return;
  }

  const inviteCode = normalizeInviteCode(req.body && (req.body.inviteCode || req.body.inviteLink));
  if (!inviteCode) {
    res.status(400).json({
      status: "error",
      code: "invalid_invite_code",
      message: "A valid WhatsApp invite code is required",
    });
    return;
  }

  const cachedGroup = findGroupByInviteCode(groupsCache, inviteCode);
  if (cachedGroup) {
    log("info", "WhatsApp group resolved by cached invite", {
      groupId: cachedGroup.groupId,
      namePresent: Boolean(cachedGroup.name),
    });
    res.json({ status: "ok", clientId: CLIENT_ID, source: "cache", group: cachedGroup });
    return;
  }

  try {
    const inviteInfo = await withTimeout(
      () => client.getInviteInfo(inviteCode),
      Math.min(WHATSAPP_GROUPS_RESPONSE_TIMEOUT_MS, 10000),
      "Invite group lookup"
    );
    const group = groupFromInviteInfo(inviteInfo, inviteCode);
    if (!group) {
      log("warn", "WhatsApp invite resolved without group id", {
        inviteInfoFields: inviteInfo && typeof inviteInfo === "object"
          ? Object.keys(inviteInfo).slice(0, 20)
          : [],
      });
      res.status(502).json({
        status: "error",
        code: "invite_group_id_missing",
        message: "WhatsApp returned invite information without a group id",
      });
      return;
    }

    log("info", "WhatsApp group resolved directly by invite", {
      groupId: group.groupId,
      namePresent: Boolean(group.name),
    });
    res.json({ status: "ok", clientId: CLIENT_ID, source: "direct", group });
  } catch (error) {
    const detail = errorMessage(error);
    log("warn", "WhatsApp direct invite lookup failed", { error: detail });
    res.status(502).json({
      status: "error",
      code: "invite_lookup_failed",
      message: detail,
    });
  }
}));

app.get("/is-active-user", asyncRoute(async (req, res) => {
  if (!requireReady(res)) {
    return;
  }

  const phone = normalizePhone(req.query.phone);
  if (!phone) {
    res.status(400).json({ status: "error", code: "missing_phone" });
    return;
  }

  const registered = await client.isRegisteredUser(phone);
  res.json({ status: "ok", registered, stage: "registered-check" });
}));

app.get("/lastseen/:phone", asyncRoute(async (req, res) => {
  if (!requireReady(res)) {
    return;
  }

  const phone = normalizePhone(req.params.phone);
  if (!phone) {
    res.status(400).json({ status: "error", code: "missing_phone" });
    return;
  }

  const registered = await client.isRegisteredUser(phone);
  res.json({
    status: "ok",
    registered,
    lastSeen: null,
    stage: "registered-check",
  });
}));

app.use((error, req, res, next) => {
  lastError = errorMessage(error);
  log("error", "HTTP request failed", {
    path: req.path,
    error: lastError,
  });
  if (res.headersSent) {
    next(error);
    return;
  }
  if (error && error.type === "entity.too.large") {
    res.status(413).json({ status: "error", code: "payload_too_large" });
    return;
  }
  if (error instanceof SyntaxError) {
    res.status(400).json({ status: "error", code: "invalid_json" });
    return;
  }
  res.status(500).json({
    status: "error",
    code: "internal_error",
    message: "Internal gateway error",
  });
});

process.on("unhandledRejection", (reason) => {
  handleProcessError("rejection", reason);
});

process.on("uncaughtException", (error) => {
  handleProcessError("exception", error);
});

app.listen(PORT, () => {
  log("info", "WhatsApp gateway HTTP server started", { port: PORT, serverUrl: SERVER_URL });
});

startReadyWatchdog();
startClient().catch((error) => {
  lastError = error.message;
  lastState = "startup_failed";
  log("error", "Initial WhatsApp client startup failed", { error: error.message });
  scheduleRestart();
});

async function shutdown(signal) {
  log("info", "Shutting down WhatsApp gateway", { signal });
  if (restartTimer) {
    clearTimeout(restartTimer);
    restartTimer = null;
  }
  if (readyWatchdogTimer) {
    clearInterval(readyWatchdogTimer);
    readyWatchdogTimer = null;
  }
  try {
    if (client) {
      await client.destroy();
    }
  } catch (error) {
    log("warn", "Client destroy during shutdown failed", { error: error.message });
  } finally {
    process.exit(0);
  }
}

process.on("SIGTERM", () => {
  void shutdown("SIGTERM");
});

process.on("SIGINT", () => {
  void shutdown("SIGINT");
});
