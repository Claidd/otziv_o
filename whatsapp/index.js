const express = require("express");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const QRCode = require("qrcode");
const qrcodeTerminal = require("qrcode-terminal");
const { Client, LocalAuth } = require("whatsapp-web.js");

const CLIENT_ID = process.env.CLIENT_ID || "whatsapp_default";
const PORT = Number.parseInt(process.env.PORT || "3000", 10);
const SERVER_URL = trimTrailingSlash(process.env.SERVER_URL || "http://app:8080");
const AUTH_PATH = process.env.AUTH_PATH || "/auth";
const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET || "";
const CHROMIUM_PATH = process.env.PUPPETEER_EXECUTABLE_PATH || "/usr/bin/chromium";
const WHATSAPP_PROXY_ENABLED = parseBoolean(process.env.WHATSAPP_PROXY_ENABLED);
const WHATSAPP_PROXY_HOST = String(process.env.WHATSAPP_PROXY_HOST || "").trim();
const WHATSAPP_PROXY_PORT = String(process.env.WHATSAPP_PROXY_PORT || "8888").trim();
const WHATSAPP_PROXY_TYPE = String(process.env.WHATSAPP_PROXY_TYPE || "http").trim().toLowerCase();
const WHATSAPP_GROUPS_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_GROUPS_TIMEOUT_MS, 10000);
const WHATSAPP_GROUPS_RESPONSE_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_GROUPS_RESPONSE_TIMEOUT_MS, 25000);
const WHATSAPP_GROUPS_CACHE_TTL_MS = parsePositiveInt(process.env.WHATSAPP_GROUPS_CACHE_TTL_MS, 600000);
const WHATSAPP_GROUP_INVITE_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_GROUP_INVITE_TIMEOUT_MS, 2000);
const WHATSAPP_GROUP_INVITE_CONCURRENCY = parsePositiveInt(process.env.WHATSAPP_GROUP_INVITE_CONCURRENCY, 16);
const WHATSAPP_PUPPETEER_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_PUPPETEER_TIMEOUT_MS, 300000);
const WHATSAPP_READY_AFTER_AUTH_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_READY_AFTER_AUTH_TIMEOUT_MS, 300000);
const WHATSAPP_STARTUP_READY_TIMEOUT_MS = parsePositiveInt(process.env.WHATSAPP_STARTUP_READY_TIMEOUT_MS, 600000);
const WHATSAPP_READY_WATCHDOG_INTERVAL_MS = parsePositiveInt(process.env.WHATSAPP_READY_WATCHDOG_INTERVAL_MS, 15000);

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

const app = express();
app.use(express.json({ limit: "1mb" }));

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
  const value = String(raw || "").trim();
  if (!value) {
    return "";
  }
  return value.includes("@g.us") ? value : `${value}@g.us`;
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

  const launchArgs = [
    "--no-sandbox",
    "--disable-setuid-sandbox",
    "--disable-dev-shm-usage",
    "--disable-gpu",
    "--no-first-run",
    "--no-zygote",
    "--disable-extensions",
  ];
  const proxy = proxyServerArg();
  if (proxy) {
    launchArgs.push(`--proxy-server=${proxy}`);
  }

  return new Client({
    authStrategy: new LocalAuth({
      clientId: CLIENT_ID,
      dataPath: AUTH_PATH,
    }),
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
    log("info", "QR received; scan it from logs or GET /qr");
    qrcodeTerminal.generate(qr, { small: true });
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
      log("warn", "Message webhook failed", { error: error.message });
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

async function handleIncomingMessage(message) {
  if (!message || message.from === "status@broadcast") {
    return;
  }

  const body = String(message.body || "").trim();
  if (!body) {
    return;
  }

  const chat = await message.getChat();
  if (chat.isGroup) {
    await postBackendWebhook("/webhook/whatsapp-group-reply", {
      clientId: CLIENT_ID,
      groupId: chat.id && chat.id._serialized ? chat.id._serialized : message.from,
      groupName: chat.name || "",
      from: message.author || message.from,
      fromName: message._data && message._data.notifyName ? message._data.notifyName : "",
      messageId: message.id && message.id._serialized ? message.id._serialized : null,
      timestamp: message.timestamp || null,
      fromMe: Boolean(message.fromMe),
      message: body,
    });
    return;
  }

  if (message.fromMe) {
    return;
  }

  await postBackendWebhook("/webhook/whatsapp-reply", {
    clientId: CLIENT_ID,
    from: message.from,
    message: body,
  });
}

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

  const response = await fetch(`${SERVER_URL}${path}`, {
    method: "POST",
    headers,
    body,
  });

  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(`Backend webhook ${path} returned ${response.status}: ${text}`);
  }
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
  const chats = await withTimeout(
    () => client.getChats(),
    WHATSAPP_GROUPS_TIMEOUT_MS,
    "Group list lookup"
  );
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
      log("info", "WhatsApp groups cache refreshed", {
        totalChats: snapshot.totalChats,
        groupCount: snapshot.groups.length,
        inviteCount: snapshot.groups.filter((group) => Boolean(group.inviteLink)).length,
      });
      return snapshot;
    })
    .finally(() => {
      groupsRefreshPromise = null;
    });

  return groupsRefreshPromise;
}

app.get("/health", (req, res) => {
  res.json(statusPayload());
});

app.get("/ready", (req, res) => {
  if (!ready || !authenticated) {
    res.status(503).json(statusPayload());
    return;
  }
  res.json(statusPayload());
});

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
  if (!phone || !message) {
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
  if (!groupId || !message) {
    res.status(400).json({ status: "error", code: "invalid_request" });
    return;
  }

  const sent = await client.sendMessage(groupId, message);
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
  const cached = forceRefresh ? null : freshGroupsCache();
  if (cached) {
    if (!groupsRefreshPromise) {
      void startGroupsRefresh().catch((error) => {
        log("warn", "Background groups cache refresh failed", { error: error.message });
      });
    }
    res.json(groupsPayload(cached, { cached: true, refreshInProgress: Boolean(groupsRefreshPromise) }));
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
    log("warn", "Groups cache refresh is still running", { error: error.message });
    res.json(groupsPayload(groupsCache, {
      cached: Boolean(groupsCache),
      refreshInProgress: Boolean(groupsRefreshPromise),
      message: groupsCache ? "Returning stale groups cache" : "Groups cache is warming up",
    }));
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
  lastError = error.message;
  log("error", "HTTP request failed", {
    path: req.path,
    error: error.message,
  });
  if (res.headersSent) {
    next(error);
    return;
  }
  res.status(500).json({
    status: "error",
    code: "internal_error",
    message: error.message,
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
