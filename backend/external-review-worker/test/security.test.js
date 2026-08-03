import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import {
  boundedBodyBytes,
  constantTimeMatches,
  createConcurrencyMiddleware,
  createInternalAuthMiddleware,
  INTERNAL_AUTH_HEADER,
} from "../src/internal-auth.js";
import {
  assertPublicHttpUrl,
  createPublicRequestGuard,
  isPublicAddress,
  parseTopLevelHttpUrl,
  UnsafeTargetError,
} from "../src/url-security.js";
import { chromiumLaunchArgs } from "../src/chromium-security.js";
import { resolvePinnedTarget } from "../src/dns-pinning-proxy.js";

test("Chromium sandbox cannot be disabled by worker configuration", () => {
  const args = chromiumLaunchArgs();
  assert.equal(args.includes("--no-sandbox"), false);
  assert.equal(args.includes("--disable-setuid-sandbox"), false);
  assert.equal(args.includes("--disable-quic"), true);
  assert.equal(args.includes("--force-webrtc-ip-handling-policy=disable_non_proxied_udp"), true);
});

test("request-body configuration cannot exceed the hard worker ceiling", () => {
  assert.equal(boundedBodyBytes("128kb"), 128 * 1_024);
  assert.equal(boundedBodyBytes("100gb"), 256 * 1_024);
  assert.equal(boundedBodyBytes("20mb"), 512 * 1_024);
});

test("internal auth is optional only while rollout secret is absent", () => {
  let called = false;
  createInternalAuthMiddleware()(
    requestWithHeader(),
    responseStub(),
    () => { called = true; },
  );
  assert.equal(called, true);
});

test("internal auth fails startup when enforcement has no secret", () => {
  assert.throws(
    () => createInternalAuthMiddleware({ required: true }),
    /required but no shared secret/u,
  );
});

test("internal auth accepts only the configured token without reflecting it", () => {
  const secret = "test-only-9f14f5c2"; // gitleaks:allow -- synthetic test credential
  const middleware = createInternalAuthMiddleware({ secret });

  let accepted = false;
  middleware(
    requestWithHeader(secret),
    responseStub(),
    () => { accepted = true; },
  );
  assert.equal(accepted, true);
  assert.equal(constantTimeMatches("wrong", secret), false);

  const rejected = responseStub();
  middleware(requestWithHeader("wrong"), rejected, () => assert.fail("must reject"));
  assert.equal(rejected.statusCode, 401);
  assert.deepEqual(rejected.body, { status: "ERROR", code: "unauthorized" });
  assert.equal(JSON.stringify(rejected.body).includes(secret), false);
});

test("concurrency middleware releases slots on both close and finish", () => {
  const middleware = createConcurrencyMiddleware(1);
  const first = responseStub();
  const second = responseStub();
  const third = responseStub();
  const fourth = responseStub();
  let firstAccepted = false;
  let thirdAccepted = false;
  let fifthAccepted = false;

  middleware({}, first, () => { firstAccepted = true; });
  middleware({}, second, () => assert.fail("second request must be rejected"));
  assert.equal(firstAccepted, true);
  assert.equal(second.statusCode, 429);

  first.emit("close");
  middleware({}, third, () => { thirdAccepted = true; });
  assert.equal(thirdAccepted, true);

  middleware({}, fourth, () => assert.fail("fourth request must be rejected"));
  assert.equal(fourth.statusCode, 429);
  third.emit("finish");
  middleware({}, responseStub(), () => { fifthAccepted = true; });
  assert.equal(fifthAccepted, true);
});

test("top-level parser allows only credential-free HTTP(S) URLs", () => {
  assert.equal(parseTopLevelHttpUrl("https://maps.example.org/reviews").protocol, "https:");
  for (const target of [
    "file:///etc/passwd",
    "http://user:password@example.org/",
    "http://example.org/\nnext",
  ]) {
    assert.throws(() => parseTopLevelHttpUrl(target), UnsafeTargetError);
  }
});

test("private, link-local, documentation and reserved addresses are blocked", () => {
  for (const address of [
    "0.0.0.1",
    "10.0.0.1",
    "100.64.0.1",
    "127.0.0.1",
    "169.254.169.254",
    "172.16.0.1",
    "192.168.1.1",
    "192.0.2.1",
    "198.18.0.1",
    "203.0.113.1",
    "224.0.0.1",
    "::1",
    "::ffff:10.0.0.1",
    "::ffff:169.254.169.254",
    "64:ff9b::a00:1",
    "64:ff9b::a9fe:a9fe",
    "fc00::1",
    "fe80::1",
    "2001:db8::1",
  ]) {
    assert.equal(isPublicAddress(address), false, address);
  }
  assert.equal(isPublicAddress("8.8.8.8"), true);
  assert.equal(isPublicAddress("2606:4700:4700::1111"), true);
});

test("DNS names are accepted only when every answer is public", async () => {
  const publicLookup = async () => [
    { address: "8.8.8.8", family: 4 },
    { address: "2606:4700:4700::1111", family: 6 },
  ];
  const mixedLookup = async () => [
    { address: "8.8.8.8", family: 4 },
    { address: "127.0.0.1", family: 4 },
  ];

  assert.equal(
    (await assertPublicHttpUrl("https://maps.example.org/card", { lookup: publicLookup })).hostname,
    "maps.example.org",
  );
  await assert.rejects(
    assertPublicHttpUrl("https://maps.example.org/card", { lookup: mixedLookup }),
    UnsafeTargetError,
  );
  await assert.rejects(
    assertPublicHttpUrl("http://localhost/admin", { lookup: publicLookup }),
    UnsafeTargetError,
  );
});

test("request guard re-resolves redirects and aborts a rebinding target", async () => {
  let lookupCount = 0;
  const lookup = async () => {
    lookupCount += 1;
    return [{ address: lookupCount === 1 ? "8.8.8.8" : "127.0.0.1", family: 4 }];
  };
  const blocked = [];
  const guard = createPublicRequestGuard({ lookup, onBlocked: (code) => blocked.push(code) });
  const first = routeStub("https://maps.example.org/start");
  const second = routeStub("https://maps.example.org/redirected");

  await guard(first);
  await guard(second);

  assert.equal(first.continued, true);
  assert.equal(second.aborted, "blockedbyclient");
  assert.deepEqual(blocked, ["blocked_address"]);
});

test("pinning proxy connects to the validated numeric answer and rejects a rebound answer", async () => {
  let lookupCount = 0;
  const lookup = async () => {
    lookupCount += 1;
    return [{ address: lookupCount === 1 ? "8.8.8.8" : "127.0.0.1", family: 4 }];
  };

  const first = await resolvePinnedTarget("https://maps.example.org/reviews", { lookup });
  assert.equal(first.address, "8.8.8.8");
  assert.equal(first.authority, "8.8.8.8:443");
  assert.equal(first.url.hostname, "maps.example.org");

  await assert.rejects(
    resolvePinnedTarget("https://maps.example.org/reviews", { lookup }),
    UnsafeTargetError,
  );
});

function requestWithHeader(value) {
  return {
    get(name) {
      return name.toLowerCase() === INTERNAL_AUTH_HEADER ? value : undefined;
    },
  };
}

function responseStub() {
  const response = new EventEmitter();
  response.headers = {};
  response.set = (name, value) => {
    response.headers[name] = value;
    return response;
  };
  response.status = (statusCode) => {
    response.statusCode = statusCode;
    return response;
  };
  response.json = (body) => {
    response.body = body;
    return response;
  };
  return response;
}

function routeStub(url) {
  return {
    continued: false,
    aborted: null,
    request: () => ({
      url: () => url,
      isNavigationRequest: () => false,
    }),
    continue: async function continueRequest() { this.continued = true; },
    abort: async function abortRequest(reason) { this.aborted = reason; },
  };
}
