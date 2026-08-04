"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { EventEmitter } = require("node:events");
const test = require("node:test");
const {
  INTERNAL_AUTH_HEADER,
  boundedBodyBytes,
  constantTimeMatches,
  createConcurrencyMiddleware,
  createInternalAuthMiddleware,
} = require("./internal-auth");
const {
  BASE_CHROMIUM_LAUNCH_ARGS,
  chromiumLaunchArgs,
} = require("./chromium-launch");

test("gateway request-body configuration has a hard one-megabyte ceiling", () => {
  assert.equal(boundedBodyBytes("128kb"), 128 * 1024);
  assert.equal(boundedBodyBytes("invalid"), 256 * 1024);
  assert.equal(boundedBodyBytes("20mb"), 1024 * 1024);
});

test("gateway auth remains optional while rollout secret is absent", () => {
  let accepted = false;
  createInternalAuthMiddleware()(
    requestWithHeader(),
    responseStub(),
    () => { accepted = true; }
  );
  assert.equal(accepted, true);
});

test("gateway auth fails startup when required without a secret", () => {
  assert.throws(
    () => createInternalAuthMiddleware({ required: true }),
    /required but no shared secret/u
  );
});

test("gateway auth uses a constant-time digest comparison and never reflects token", () => {
  const secret = "test-only-c2fa4199"; // gitleaks:allow -- synthetic test credential
  const middleware = createInternalAuthMiddleware({ secret });
  let accepted = false;
  middleware(requestWithHeader(secret), responseStub(), () => { accepted = true; });
  assert.equal(accepted, true);
  assert.equal(constantTimeMatches("wrong", secret), false);

  const response = responseStub();
  middleware(requestWithHeader("wrong"), response, () => assert.fail("must reject"));
  assert.equal(response.statusCode, 401);
  assert.deepEqual(response.body, { status: "error", code: "unauthorized" });
  assert.equal(JSON.stringify(response.body).includes(secret), false);
});

test("gateway concurrency bound releases slots on both close and finish", () => {
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

test("WhatsApp Chromium keeps its Linux sandbox enabled", () => {
  const source = fs.readFileSync(path.join(__dirname, "index.js"), "utf8");
  const args = chromiumLaunchArgs("http://127.0.0.1:3128");

  assert.match(source, /chromiumLaunchArgs\(proxyServerArg\(\)\)/u);
  assert.doesNotMatch(source, /--no-sandbox|--disable-setuid-sandbox|--no-zygote/u);
  assert.doesNotMatch(args.join(" "), /--no-sandbox|--disable-setuid-sandbox|--no-zygote/u);
  assert.deepEqual(args.slice(0, BASE_CHROMIUM_LAUNCH_ARGS.length), BASE_CHROMIUM_LAUNCH_ARGS);
  assert.equal(args.at(-1), "--proxy-server=http://127.0.0.1:3128");
});

test("WhatsApp Web cache never writes into the read-only application filesystem", () => {
  const source = fs.readFileSync(path.join(__dirname, "index.js"), "utf8");

  assert.match(source, /webVersionCache:\s*\{[\s\S]{0,300}type:\s*"none"/u);
  assert.doesNotMatch(source, /webVersionCache:\s*\{[\s\S]{0,300}type:\s*"local"/u);
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
