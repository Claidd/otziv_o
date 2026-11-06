function normalizeInviteCode(raw) {
  const value = String(raw || "").trim();
  if (!value) {
    return "";
  }

  const direct = value.match(/^[A-Za-z0-9_-]{10,}$/);
  if (direct) {
    return direct[0];
  }

  const url = value.match(/^(?:https?:\/\/)?chat\.whatsapp\.com\/([A-Za-z0-9_-]{10,})(?:[/?#].*)?$/i);
  return url ? url[1] : "";
}

function serializedGroupId(value) {
  if (!value) {
    return "";
  }
  if (typeof value === "string") {
    const candidate = value.trim();
    if (/^(?=.{8,80}@g\.us$)[0-9]+(?:-[0-9]+)?@g\.us$/u.test(candidate)) {
      return candidate;
    }
    return /^(?=.{8,80}$)[0-9]+(?:-[0-9]+)?$/u.test(candidate) ? `${candidate}@g.us` : "";
  }
  if (typeof value !== "object") {
    return "";
  }

  for (const field of ["_serialized", "serialized"]) {
    const candidate = serializedGroupId(value[field]);
    if (candidate) {
      return candidate;
    }
  }

  const user = String(value.user || "").trim();
  const server = String(value.server || "").trim();
  if (server === "g.us") {
    return serializedGroupId(user);
  }

  return "";
}

function firstText(values) {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) {
      return value.trim();
    }
  }
  return "";
}

function groupFromInviteInfo(info, inviteCode) {
  if (!info || typeof info !== "object") {
    return null;
  }

  const metadata = info.groupMetadata && typeof info.groupMetadata === "object"
    ? info.groupMetadata
    : {};
  const group = info.group && typeof info.group === "object" ? info.group : {};
  const idCandidates = [
    info.groupId,
    info.gid,
    info.id,
    metadata.groupId,
    metadata.id,
    group.groupId,
    group.gid,
    group.id,
  ];
  let groupId = "";
  for (const candidate of idCandidates) {
    groupId = serializedGroupId(candidate);
    if (groupId) {
      break;
    }
  }
  if (!groupId) {
    return null;
  }

  const code = normalizeInviteCode(inviteCode);
  return {
    groupId,
    id: groupId,
    chatId: groupId,
    name: firstText([
      info.subject,
      info.title,
      info.name,
      info.groupSubject,
      metadata.subject,
      metadata.title,
      metadata.name,
      group.subject,
      group.title,
      group.name,
    ]),
    inviteCode: code,
    inviteLink: code ? `https://chat.whatsapp.com/${code}` : null,
  };
}

function groupFromSnapshot(group, inviteCode) {
  if (!group || typeof group !== "object") {
    return null;
  }

  const expectedCode = normalizeInviteCode(inviteCode);
  const actualCode = normalizeInviteCode(
    group.inviteCode || group.inviteLink || group.invite || group.link || group.url
  );
  if (!expectedCode || actualCode !== expectedCode) {
    return null;
  }

  const groupId = serializedGroupId(group.groupId || group.id || group.chatId);
  if (!groupId) {
    return null;
  }

  return {
    groupId,
    id: groupId,
    chatId: groupId,
    name: firstText([group.name, group.title, group.subject]),
    inviteCode: expectedCode,
    inviteLink: `https://chat.whatsapp.com/${expectedCode}`,
  };
}

function findGroupByInviteCode(snapshot, inviteCode) {
  const groups = snapshot && Array.isArray(snapshot.groups) ? snapshot.groups : [];
  const matches = new Map();
  for (const candidate of groups) {
    const group = groupFromSnapshot(candidate, inviteCode);
    if (group) {
      matches.set(group.groupId, group);
    }
  }
  return matches.size === 1 ? matches.values().next().value : null;
}

// Every group in a snapshot carries exactly one invite status, so a refresh that reads no invite
// says why instead of silently counting zero.
const INVITE_STATUS = Object.freeze({
  OK: "ok",
  // The account is no longer a participant: it cannot read the invite and will not deliver either.
  NOT_MEMBER: "not_member",
  // A participant without admin rights: WhatsApp does not show it the group's invite.
  NOT_ADMIN: "not_admin",
  // WhatsApp answered the query with an error status (rights unknown locally).
  REFUSED: "refused",
  TIMEOUT: "timeout",
  // This WhatsApp Web build has no invite-code query.
  UNSUPPORTED: "unsupported",
  // The query answered without a recognizable code.
  EMPTY: "empty",
  // Anything else: a gateway bug or a WhatsApp Web API change to investigate.
  ERROR: "error",
});

/**
 * Runs inside WhatsApp Web (self-contained, never throws). WhatsApp Web ships
 * WAWebMexFetchGroupInviteCodeJob only in the on-demand bundle of the group "Invite via link"
 * drawer, so a session that never opened that drawer has no such module and window.require returns
 * undefined (whatsapp-web.js 1.34.7 getInviteCode fails with "reading 'fetchMexGroupInviteCode'").
 * This loads the drawer's bundle without rendering it, trying the loaders known to work: the one in
 * the pending whatsapp-web.js fix (JSResourceForInteraction), wa-js (Bootloader.loadModules) and the
 * drawer's own Loadable. One load is shared by concurrent lookups; a failed load is retried after
 * retryAfterMs so a refresh does not repeat it for every group.
 */
async function pageEnsureGroupInviteModule(timeoutMs, retryAfterMs) {
  const moduleName = "WAWebMexFetchGroupInviteCodeJob";
  const component = "WAWebGroupInviteLinkDrawer.react";
  const registered = () => {
    try {
      const module = window.require(moduleName);
      return Boolean(module && typeof module.fetchMexGroupInviteCode === "function");
    } catch (error) {
      return false;
    }
  };
  const report = (state, reused) => ({
    registered: registered(),
    loadedBy: state ? state.loadedBy : null,
    errors: state ? state.errors.slice() : [],
    reused,
  });
  if (registered()) {
    return report(null, false);
  }

  const previous = window.__otzivGroupInviteModuleLoad;
  if (previous && (previous.pending || Date.now() - previous.finishedAt < retryAfterMs)) {
    try {
      await previous.promise;
    } catch (error) {
      // The load records its own errors.
    }
    return report(previous, true);
  }

  const message = (error) => {
    const text = error && error.message ? String(error.message) : String(error);
    return text.length > 160 ? text.slice(0, 160) : text;
  };
  const bounded = (promise, label) => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${label} timed out after ${timeoutMs}ms`)), timeoutMs);
    Promise.resolve(promise).then(
      (value) => { clearTimeout(timer); resolve(value); },
      (error) => { clearTimeout(timer); reject(error); }
    );
  });
  const loaders = [
    ["JSResourceForInteraction", () => window.require("JSResourceForInteraction")(component).load()],
    ["Bootloader", () => new Promise((resolve, reject) => {
      const exported = window.require("Bootloader");
      const bootloader = exported && typeof exported.loadModules === "function" ? exported : exported && exported.default;
      if (!bootloader || typeof bootloader.loadModules !== "function") {
        reject(new Error("Bootloader.loadModules is not available"));
        return;
      }
      bootloader.loadModules([component], () => resolve(), "otziv-whatsapp-gateway");
    })],
    ["WAWebGroupInviteLinkDrawerLoadable", () => window.require("WAWebGroupInviteLinkDrawerLoadable").requireBundle()],
  ];

  const state = { pending: true, loadedBy: null, errors: [], finishedAt: 0, promise: null };
  state.promise = (async () => {
    for (const [name, load] of loaders) {
      try {
        await bounded(load(), name);
      } catch (error) {
        state.errors.push(`${name}: ${message(error)}`);
        continue;
      }
      if (registered()) {
        state.loadedBy = name;
        return;
      }
      state.errors.push(`${name}: loaded, but ${moduleName} is still not registered`);
    }
  })().finally(() => {
    state.pending = false;
    state.finishedAt = Date.now();
  });
  window.__otzivGroupInviteModuleLoad = state;
  await state.promise;
  return report(state, false);
}

/**
 * Runs inside WhatsApp Web: puppeteer serializes it, so it must not reference anything outside
 * itself. It never throws; every outcome, including WhatsApp's own minified errors, is returned as
 * plain data for classifyInviteLookup. whatsapp-web.js only recognizes a refusal by the error name
 * "ServerStatusCodeError" and rethrows everything else, which hid every reason behind a null code.
 */
async function pageLookupGroupInvite(chatId) {
  const clip = (value, max) => {
    const text = typeof value === "string" ? value : value === undefined || value === null ? "" : String(value);
    return text.length > max ? text.slice(0, max) : text;
  };
  const describe = (error) => {
    if (!error || typeof error !== "object") {
      return { message: clip(error, 160) };
    }
    const described = {
      name: clip(error.name, 60),
      constructorName: clip(error.constructor && error.constructor.name, 60),
      message: clip(error.message, 160),
    };
    for (const field of ["statusCode", "status"]) {
      if (Number.isInteger(error[field])) {
        described[field] = error[field];
      }
    }
    return described;
  };
  const refused = (described) => described.name === "ServerStatusCodeError"
    || described.constructorName === "ServerStatusCodeError"
    || Number.isInteger(described.statusCode);
  const pick = (value) => {
    if (value === undefined || value === null) {
      return { kind: String(value) };
    }
    if (typeof value === "string") {
      return { kind: "string", value: clip(value, 200) };
    }
    if (typeof value !== "object") {
      return { kind: typeof value };
    }
    const picked = { kind: "object", keys: Object.keys(value).slice(0, 12) };
    for (const field of ["code", "inviteCode", "invite_code", "inviteLink", "link"]) {
      if (typeof value[field] === "string" && value[field]) {
        picked.field = field;
        picked.value = clip(value[field], 200);
        break;
      }
    }
    return picked;
  };

  const result = { attempts: [], membership: { known: false } };
  let wid = null;
  try {
    wid = window.require("WAWebWidFactory").createWid(chatId);
  } catch (error) {
    result.widError = describe(error);
  }

  try {
    const collections = window.require("WAWebCollections");
    const chat = wid && collections.Chat && typeof collections.Chat.get === "function"
      ? collections.Chat.get(wid)
      : null;
    const metadataCollection = collections.GroupMetadata || collections.WAWebGroupMetadataCollection;
    const metadata = (chat && chat.groupMetadata)
      || (wid && metadataCollection && typeof metadataCollection.get === "function"
        ? metadataCollection.get(wid)
        : null);
    const participants = metadata && metadata.participants;
    if (participants) {
      const models = typeof participants.getModelsArray === "function"
        ? participants.getModelsArray()
        : Array.isArray(participants.models) ? participants.models : [];
      const count = typeof participants.length === "number" ? participants.length : models.length;
      let member = typeof participants.iAmMember === "function" ? Boolean(participants.iAmMember()) : null;
      let admin = typeof participants.iAmAdmin === "function" ? Boolean(participants.iAmAdmin()) : null;
      if (admin === false && typeof participants.iAmSuperAdmin === "function" && participants.iAmSuperAdmin()) {
        admin = true;
      }
      if (member === null || admin === null) {
        const meUser = window.require("WAWebUserPrefsMeUser");
        const me = [meUser.getMaybeMePnUser, meUser.getMaybeMeLidUser]
          .map((getter) => (typeof getter === "function" ? getter() : null))
          .filter(Boolean);
        const self = typeof participants.get === "function"
          ? me.map((id) => participants.get(id)).find(Boolean)
          : null;
        if (member === null && count > 0) {
          member = Boolean(self);
        }
        if (admin === null && self) {
          admin = Boolean(self.isAdmin || self.isSuperAdmin);
        }
      }
      // An empty participant list means the metadata is not loaded, not that the account left.
      result.membership = {
        known: count > 0 && member !== null,
        member,
        admin,
        participantCount: count,
        memberAddMode: clip(metadata.memberAddMode, 40) || null,
      };
    }
  } catch (error) {
    result.membershipError = describe(error);
  }

  // The job ships in the lazily loaded "Invite via link" bundle (see pageEnsureGroupInviteModule):
  // without it window.require returns undefined and every group used to fail silently.
  let requireError = null;
  const requireJob = () => {
    try {
      const module = window.require("WAWebMexFetchGroupInviteCodeJob");
      return module && typeof module.fetchMexGroupInviteCode === "function" ? module : null;
    } catch (error) {
      requireError = describe(error);
      return null;
    }
  };
  let job = requireJob();
  const bundle = window.__otzivGroupInviteModuleLoad;
  if (!job && bundle && bundle.promise) {
    try {
      await bundle.promise;
    } catch (error) {
      // The load records its own errors.
    }
    job = requireJob();
  }
  if (!job) {
    const loadErrors = bundle && Array.isArray(bundle.errors) && bundle.errors.length
      ? `; bundle load: ${bundle.errors.join("; ")}`
      : bundle ? "" : "; bundle load was not attempted";
    const thrown = requireError && requireError.message ? ` (${requireError.message})` : "";
    result.unsupported = {
      name: requireError ? requireError.name : "",
      message: clip(`WAWebMexFetchGroupInviteCodeJob is not registered${thrown}${loadErrors}`, 400),
    };
    return result;
  }

  // whatsapp-web.js passes the serialized id, WhatsApp Web's own callers pass a Wid. A form this
  // build rejects locally is retried in the other form; a server answer is final.
  const forms = [["string", chatId]];
  if (wid) {
    forms.push(["wid", wid]);
  }
  for (const [form, argument] of forms) {
    try {
      const picked = pick(await job.fetchMexGroupInviteCode(argument));
      result.attempts.push({ form, ok: true, result: picked });
      if (picked.value) {
        break;
      }
    } catch (error) {
      const described = describe(error);
      result.attempts.push({ form, ok: false, error: described });
      if (refused(described)) {
        break;
      }
    }
  }
  return result;
}

function clipText(value, max = 200) {
  const text = String(value === undefined || value === null ? "" : value).replace(/\s+/g, " ").trim();
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

function isServerRefusal(error) {
  return Boolean(error) && (
    error.name === "ServerStatusCodeError"
    || error.constructorName === "ServerStatusCodeError"
    || Number.isInteger(error.statusCode)
  );
}

function describeLookupError(error) {
  if (!error || typeof error !== "object") {
    return clipText(error);
  }
  const name = error.name && error.name !== "Error"
    ? error.name
    : error.constructorName || error.name || "";
  const status = Number.isInteger(error.statusCode)
    ? `status ${error.statusCode}`
    : Number.isInteger(error.status) ? `status ${error.status}` : "";
  const head = [name, status].filter(Boolean).join(" ");
  return clipText([head, error.message].filter(Boolean).join(": "));
}

function describeEmptyResult(attempt) {
  const result = attempt && attempt.result ? attempt.result : {};
  if (result.kind === "object") {
    const keys = Array.isArray(result.keys) ? result.keys.join(",") : "";
    return clipText(`${attempt.form} form returned an object without a code (keys: ${keys || "none"})`);
  }
  return clipText(`${attempt.form} form returned ${result.kind || "nothing"}`);
}

function inviteOutcome(status, code, detail) {
  const inviteCode = normalizeInviteCode(code);
  return {
    inviteCode: inviteCode || null,
    inviteLink: inviteCode ? `https://chat.whatsapp.com/${inviteCode}` : null,
    inviteStatus: status,
    inviteError: detail ? clipText(detail) : null,
  };
}

/** Turns the page lookup into the invite fields of one group. */
function classifyInviteLookup(raw) {
  const lookup = raw && typeof raw === "object" ? raw : {};
  const attempts = Array.isArray(lookup.attempts) ? lookup.attempts.filter(Boolean) : [];
  for (const attempt of attempts) {
    const code = attempt.ok && attempt.result ? normalizeInviteCode(attempt.result.value) : "";
    if (code) {
      return inviteOutcome(INVITE_STATUS.OK, code, null);
    }
  }

  const membership = lookup.membership && typeof lookup.membership === "object" ? lookup.membership : {};
  if (membership.known && membership.member === false) {
    return inviteOutcome(INVITE_STATUS.NOT_MEMBER, null,
      `the account is not among ${membership.participantCount || "the"} participants of the group`);
  }
  if (lookup.unsupported) {
    return inviteOutcome(INVITE_STATUS.UNSUPPORTED, null, describeLookupError(lookup.unsupported));
  }

  const refusal = attempts.find((attempt) => !attempt.ok && isServerRefusal(attempt.error));
  const answered = attempts.find((attempt) => attempt.ok);
  if (refusal || answered) {
    const detail = refusal ? describeLookupError(refusal.error) : describeEmptyResult(answered);
    if (membership.known && membership.member && membership.admin === false) {
      const mode = membership.memberAddMode ? `, member add mode ${membership.memberAddMode}` : "";
      return inviteOutcome(INVITE_STATUS.NOT_ADMIN, null, `participant without admin rights${mode}; ${detail}`);
    }
    return inviteOutcome(refusal ? INVITE_STATUS.REFUSED : INVITE_STATUS.EMPTY, null, detail);
  }

  const failure = attempts.find((attempt) => !attempt.ok);
  if (failure) {
    return inviteOutcome(INVITE_STATUS.ERROR, null,
      attempts.map((attempt) => `${attempt.form} form ${describeLookupError(attempt.error)}`).join("; "));
  }
  return inviteOutcome(INVITE_STATUS.ERROR, null,
    lookup.widError ? `invalid group id: ${describeLookupError(lookup.widError)}` : "no invite lookup was attempted");
}

/** The invite fields of a group whose lookup did not complete (gateway timeout, page gone). */
function failedInviteLookup(error) {
  const message = error && error.message ? error.message : String(error || "unknown failure");
  const status = /timed out/i.test(message) ? INVITE_STATUS.TIMEOUT : INVITE_STATUS.ERROR;
  return inviteOutcome(status, null, message);
}

/** Status counts and the most frequent failure details of a snapshot, for the payload and logs. */
function summarizeInviteStatuses(groups) {
  const counts = {};
  const details = new Map();
  for (const group of Array.isArray(groups) ? groups : []) {
    const status = group && group.inviteStatus ? group.inviteStatus : "unknown";
    counts[status] = (counts[status] || 0) + 1;
    if (status !== INVITE_STATUS.OK && group && group.inviteError) {
      const key = `${status}: ${group.inviteError}`;
      details.set(key, (details.get(key) || 0) + 1);
    }
  }
  const topFailures = [...details.entries()]
    .sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0]))
    .slice(0, 5)
    .map(([detail, count]) => ({ detail, count }));
  return { counts, topFailures };
}

module.exports = {
  INVITE_STATUS,
  classifyInviteLookup,
  failedInviteLookup,
  findGroupByInviteCode,
  groupFromInviteInfo,
  groupFromSnapshot,
  normalizeInviteCode,
  pageEnsureGroupInviteModule,
  pageLookupGroupInvite,
  serializedGroupId,
  summarizeInviteStatuses,
};
