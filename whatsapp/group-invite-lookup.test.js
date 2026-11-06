const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const {
  INVITE_STATUS,
  classifyInviteLookup,
  failedInviteLookup,
  pageEnsureGroupInviteModule,
  pageLookupGroupInvite,
  summarizeInviteStatuses,
} = require("./group-invite");

const GROUP = "120363160089865886@g.us";
const CODE = "LcXNWVfU4RpHayV7wJOFZw";

class FakeWid {
  constructor(serialized) {
    this._serialized = serialized;
  }

  toString() {
    return this._serialized;
  }
}

// WhatsApp Web minifies its error classes: a refusal no longer reliably says "ServerStatusCodeError".
class MinifiedServerError extends Error {
  constructor(statusCode) {
    super("t");
    this.name = "t";
    this.statusCode = statusCode;
  }
}

function participantCollection({ member = true, admin = false, count = 3, helpers = true } = {}) {
  const me = { id: "me", isAdmin: admin, isSuperAdmin: false };
  const collection = {
    length: count,
    get: (wid) => (member && wid && wid.toString() === "79990000000@c.us" ? me : undefined),
  };
  if (helpers) {
    collection.iAmMember = () => member;
    collection.iAmAdmin = () => member && admin;
    collection.iAmSuperAdmin = () => false;
  }
  return collection;
}

// Runs the page function against a stubbed WhatsApp Web module registry.
async function lookupInPage({ fetch, participants = participantCollection(), modules = {} } = {}) {
  const calls = [];
  const previous = globalThis.window;
  globalThis.window = {
    require(name) {
      if (Object.prototype.hasOwnProperty.call(modules, name)) {
        if (modules[name] instanceof Error) {
          throw modules[name];
        }
        return modules[name];
      }
      switch (name) {
        case "WAWebWidFactory":
          return { createWid: (id) => new FakeWid(id) };
        case "WAWebCollections":
          return {
            Chat: { get: (wid) => (wid.toString() === GROUP ? { groupMetadata: { participants } } : undefined) },
          };
        case "WAWebUserPrefsMeUser":
          return {
            getMaybeMePnUser: () => new FakeWid("79990000000@c.us"),
            getMaybeMeLidUser: () => null,
          };
        case "WAWebMexFetchGroupInviteCodeJob":
          return {
            fetchMexGroupInviteCode: async (argument) => {
              calls.push(argument instanceof FakeWid ? "wid" : typeof argument);
              return fetch(argument);
            },
          };
        default:
          throw new Error(`Requiring unknown module "${name}"`);
      }
    },
  };
  try {
    const raw = await pageLookupGroupInvite(GROUP);
    // The page result crosses the puppeteer boundary as JSON.
    return { raw: JSON.parse(JSON.stringify(raw)), calls };
  } finally {
    globalThis.window = previous;
  }
}

test("the page function runs from its source alone, as puppeteer serializes it", async () => {
  // A fresh context has no module scope: any reference outside the function would throw here.
  const context = vm.createContext({
    window: {
      require: (name) => {
        if (name === "WAWebMexFetchGroupInviteCodeJob") {
          return { fetchMexGroupInviteCode: async (id) => (typeof id === "string" ? { code: CODE } : null) };
        }
        throw new Error(`Requiring unknown module "${name}"`);
      },
    },
  });
  const isolated = vm.runInContext(`(${pageLookupGroupInvite.toString()})`, context);
  const raw = JSON.parse(JSON.stringify(await isolated(GROUP)));
  assert.equal(raw.widError.message, 'Requiring unknown module "WAWebWidFactory"');
  assert.equal(classifyInviteLookup(raw).inviteCode, CODE);
});

test("reads the invite code an administrator sees, in any result shape", async () => {
  for (const answer of [
    { code: CODE },
    { inviteCode: CODE },
    CODE,
    { link: `https://chat.whatsapp.com/${CODE}` },
  ]) {
    const { raw, calls } = await lookupInPage({
      fetch: async () => answer,
      participants: participantCollection({ admin: true }),
    });
    assert.deepEqual(calls, ["string"]);
    assert.deepEqual(classifyInviteLookup(raw), {
      inviteCode: CODE,
      inviteLink: `https://chat.whatsapp.com/${CODE}`,
      inviteStatus: INVITE_STATUS.OK,
      inviteError: null,
    });
  }
});

test("retries with a Wid when this WhatsApp Web build rejects the serialized id locally", async () => {
  const { raw, calls } = await lookupInPage({
    fetch: async (argument) => {
      if (!(argument instanceof FakeWid)) {
        throw new TypeError("e.isGroup is not a function");
      }
      return { code: CODE };
    },
    participants: participantCollection({ admin: true }),
  });
  assert.deepEqual(calls, ["string", "wid"]);
  assert.equal(classifyInviteLookup(raw).inviteStatus, INVITE_STATUS.OK);
});

test("a minified server refusal of a non-admin participant is reported as not_admin", async () => {
  const { raw, calls } = await lookupInPage({ fetch: async () => { throw new MinifiedServerError(403); } });
  // A server answer is final: the other argument form is not tried.
  assert.deepEqual(calls, ["string"]);
  const outcome = classifyInviteLookup(raw);
  assert.equal(outcome.inviteStatus, INVITE_STATUS.NOT_ADMIN);
  assert.equal(outcome.inviteCode, null);
  assert.equal(outcome.inviteLink, null);
  assert.match(outcome.inviteError, /admin rights; t status 403: t$/);
});

test("the membership is derived from the participant list when the helper methods are missing", async () => {
  const { raw } = await lookupInPage({
    fetch: async () => undefined,
    participants: participantCollection({ helpers: false }),
  });
  assert.deepEqual(
    { known: raw.membership.known, member: raw.membership.member, admin: raw.membership.admin },
    { known: true, member: true, admin: false }
  );
  assert.equal(classifyInviteLookup(raw).inviteStatus, INVITE_STATUS.NOT_ADMIN);
});

test("an account removed from the group is reported as not_member", async () => {
  const { raw } = await lookupInPage({
    fetch: async () => { throw new MinifiedServerError(401); },
    participants: participantCollection({ member: false, count: 5 }),
  });
  const outcome = classifyInviteLookup(raw);
  assert.equal(outcome.inviteStatus, INVITE_STATUS.NOT_MEMBER);
  assert.match(outcome.inviteError, /not among 5 participants/);
});

test("an empty participant list never claims the account left the group", async () => {
  const { raw } = await lookupInPage({
    fetch: async () => { throw new MinifiedServerError(403); },
    participants: participantCollection({ member: false, count: 0 }),
  });
  assert.equal(raw.membership.known, false);
  const outcome = classifyInviteLookup(raw);
  assert.equal(outcome.inviteStatus, INVITE_STATUS.REFUSED);
  assert.equal(outcome.inviteError, "t status 403: t");
});

test("an answer without a code says which shape came back", async () => {
  const { raw, calls } = await lookupInPage({
    fetch: async () => ({ expiration: 0 }),
    participants: participantCollection({ admin: true }),
  });
  assert.deepEqual(calls, ["string", "wid"]);
  const outcome = classifyInviteLookup(raw);
  assert.equal(outcome.inviteStatus, INVITE_STATUS.EMPTY);
  assert.match(outcome.inviteError, /object without a code \(keys: expiration\)/);
});

test("unexpected failures of both argument forms are an error, never a silent null", async () => {
  const { raw } = await lookupInPage({
    fetch: async () => { throw new TypeError("Cannot read properties of undefined (reading 'xwa2')"); },
    participants: participantCollection({ admin: true }),
  });
  const outcome = classifyInviteLookup(raw);
  assert.equal(outcome.inviteStatus, INVITE_STATUS.ERROR);
  assert.match(outcome.inviteError, /^string form TypeError: Cannot read.*; wid form TypeError: Cannot read/);
});

test("a WhatsApp Web build without the invite query is unsupported", async () => {
  const { raw } = await lookupInPage({
    modules: {
      WAWebMexFetchGroupInviteCodeJob: new Error('Requiring unknown module "WAWebMexFetchGroupInviteCodeJob"'),
    },
  });
  const outcome = classifyInviteLookup(raw);
  assert.equal(outcome.inviteStatus, INVITE_STATUS.UNSUPPORTED);
  assert.match(outcome.inviteError, /Requiring unknown module/);
});

test("a broken membership read does not stop the invite lookup", async () => {
  const { raw } = await lookupInPage({
    fetch: async () => ({ code: CODE }),
    modules: { WAWebCollections: new Error("collections unavailable") },
  });
  assert.equal(raw.membership.known, false);
  assert.equal(raw.membershipError.message, "collections unavailable");
  assert.equal(classifyInviteLookup(raw).inviteStatus, INVITE_STATUS.OK);
});

test("gateway-side timeouts and failures keep their reason", () => {
  assert.deepEqual(failedInviteLookup(new Error("Invite code lookup timed out after 4000ms")), {
    inviteCode: null,
    inviteLink: null,
    inviteStatus: INVITE_STATUS.TIMEOUT,
    inviteError: "Invite code lookup timed out after 4000ms",
  });
  assert.equal(failedInviteLookup(new Error("Execution context was destroyed")).inviteStatus, INVITE_STATUS.ERROR);
  assert.equal(classifyInviteLookup(undefined).inviteStatus, INVITE_STATUS.ERROR);
});

test("a snapshot summary counts every status and ranks the failure reasons", () => {
  const summary = summarizeInviteStatuses([
    { inviteStatus: "ok" },
    { inviteStatus: "not_admin", inviteError: "participant without admin rights; t status 403: t" },
    { inviteStatus: "not_admin", inviteError: "participant without admin rights; t status 403: t" },
    { inviteStatus: "timeout", inviteError: "Invite code lookup timed out after 4000ms" },
    {},
  ]);
  assert.deepEqual(summary.counts, { ok: 1, not_admin: 2, timeout: 1, unknown: 1 });
  assert.deepEqual(summary.topFailures[0], {
    detail: "not_admin: participant without admin rights; t status 403: t",
    count: 2,
  });
});

// WhatsApp Web registers the invite-code job only once the "Invite via link" drawer bundle loads.
function lazyWhatsApp({ loaders = {}, fetch = async () => ({ code: CODE }) } = {}) {
  const page = { registered: false, loads: [] };
  const job = { fetchMexGroupInviteCode: async () => fetch() };
  const register = (name) => {
    page.loads.push(name);
    page.registered = true;
  };
  const defaults = {
    JSResourceForInteraction: (component) => ({
      load: async () => {
        assert.equal(component, "WAWebGroupInviteLinkDrawer.react");
        // A real bundle arrives over the network, after other lookups have started waiting.
        await new Promise((resolve) => setTimeout(resolve, 5));
        register("JSResourceForInteraction");
      },
    }),
  };
  const modules = { ...defaults, ...loaders };
  page.window = {
    require(name) {
      if (name === "WAWebMexFetchGroupInviteCodeJob") {
        // The current build answers an unknown module with undefined, not an exception.
        return page.registered ? job : undefined;
      }
      if (name === "WAWebWidFactory") {
        return { createWid: (id) => new FakeWid(id) };
      }
      if (Object.prototype.hasOwnProperty.call(modules, name)) {
        const module = modules[name];
        return typeof module === "function" && module.length === 1 && name !== "JSResourceForInteraction"
          ? module(register)
          : module;
      }
      return undefined;
    },
  };
  return page;
}

async function inPage(page, run) {
  const previous = globalThis.window;
  globalThis.window = page.window;
  try {
    return await run();
  } finally {
    globalThis.window = previous;
  }
}

test("an already registered invite job needs no bundle load", async () => {
  const page = lazyWhatsApp();
  page.registered = true;
  const state = await inPage(page, () => pageEnsureGroupInviteModule(1000, 60000));
  assert.deepEqual(state, { registered: true, loadedBy: null, errors: [], reused: false });
  assert.deepEqual(page.loads, []);
});

test("the invite drawer bundle is loaded once, then every group's invite is readable", async () => {
  const page = lazyWhatsApp();
  // Before the load the old code path failed for every group; now the reason is explicit.
  const before = await inPage(page, () => pageLookupGroupInvite(GROUP));
  assert.equal(classifyInviteLookup(before).inviteStatus, INVITE_STATUS.UNSUPPORTED);
  assert.match(classifyInviteLookup(before).inviteError, /not registered; bundle load was not attempted/);

  const [first, second] = await inPage(page, () => Promise.all([
    pageEnsureGroupInviteModule(1000, 60000),
    pageEnsureGroupInviteModule(1000, 60000),
  ]));
  assert.equal(first.registered, true);
  assert.equal(first.loadedBy, "JSResourceForInteraction");
  assert.equal(second.reused, true);
  assert.deepEqual(page.loads, ["JSResourceForInteraction"]);

  const raw = await inPage(page, () => pageLookupGroupInvite(GROUP));
  assert.equal(classifyInviteLookup(JSON.parse(JSON.stringify(raw))).inviteCode, CODE);
});

test("the Bootloader and the drawer Loadable are tried when the first loader is missing", async () => {
  const viaBootloader = lazyWhatsApp({
    loaders: {
      JSResourceForInteraction: undefined,
      Bootloader: {
        default: {
          loadModules(components, callback, reason) {
            assert.deepEqual(components, ["WAWebGroupInviteLinkDrawer.react"]);
            assert.equal(reason, "otziv-whatsapp-gateway");
            viaBootloader.registered = true;
            viaBootloader.loads.push("Bootloader");
            setTimeout(callback, 1);
          },
        },
      },
    },
  });
  const state = await inPage(viaBootloader, () => pageEnsureGroupInviteModule(1000, 60000));
  assert.equal(state.loadedBy, "Bootloader");
  assert.match(state.errors[0], /^JSResourceForInteraction: /);

  const viaLoadable = lazyWhatsApp({
    loaders: {
      JSResourceForInteraction: () => ({ load: () => new Promise(() => {}) }),
      WAWebGroupInviteLinkDrawerLoadable: {
        requireBundle: async () => {
          viaLoadable.registered = true;
        },
      },
    },
  });
  const loadable = await inPage(viaLoadable, () => pageEnsureGroupInviteModule(20, 60000));
  assert.equal(loadable.loadedBy, "WAWebGroupInviteLinkDrawerLoadable");
  assert.deepEqual(loadable.errors, [
    "JSResourceForInteraction: JSResourceForInteraction timed out after 20ms",
    "Bootloader: Bootloader.loadModules is not available",
  ]);
});

test("a bundle that cannot be loaded is reported with every loader's reason and retried later", async () => {
  const page = lazyWhatsApp({
    loaders: { JSResourceForInteraction: () => ({ load: async () => {} }) },
  });
  const state = await inPage(page, () => pageEnsureGroupInviteModule(1000, 60000));
  assert.equal(state.registered, false);
  assert.equal(state.loadedBy, null);
  assert.deepEqual(state.errors.map((error) => error.split(":")[0]), [
    "JSResourceForInteraction",
    "Bootloader",
    "WAWebGroupInviteLinkDrawerLoadable",
  ]);
  assert.match(state.errors[0], /loaded, but WAWebMexFetchGroupInviteCodeJob is still not registered/);

  const outcome = classifyInviteLookup(await inPage(page, () => pageLookupGroupInvite(GROUP)));
  assert.equal(outcome.inviteStatus, INVITE_STATUS.UNSUPPORTED);
  assert.match(outcome.inviteError, /not registered; bundle load: JSResourceForInteraction: loaded, but/);

  // Within the retry window the failed load is reused instead of repeated for every group.
  const reused = await inPage(page, () => pageEnsureGroupInviteModule(1000, 60000));
  assert.equal(reused.reused, true);
  const retried = await inPage(page, () => pageEnsureGroupInviteModule(1000, 0));
  assert.equal(retried.reused, false);
});

test("the bundle loader runs from its source alone, as puppeteer serializes it", async () => {
  let registered = false;
  const context = vm.createContext({
    setTimeout,
    clearTimeout,
    window: {
      require: (name) => {
        if (name === "WAWebMexFetchGroupInviteCodeJob") {
          return registered ? { fetchMexGroupInviteCode: async () => ({ code: CODE }) } : undefined;
        }
        if (name === "JSResourceForInteraction") {
          return () => ({ load: async () => { registered = true; } });
        }
        return undefined;
      },
    },
  });
  const isolated = vm.runInContext(`(${pageEnsureGroupInviteModule.toString()})`, context);
  const state = JSON.parse(JSON.stringify(await isolated(1000, 60000)));
  assert.deepEqual(state, { registered: true, loadedBy: "JSResourceForInteraction", errors: [], reused: false });
});
