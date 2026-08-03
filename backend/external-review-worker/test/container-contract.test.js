import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(testDirectory, "../../..");
const node22Base = "node:22-bookworm-slim";
const node22Digest = "sha256:f32b81066cde10a75dbac96646099533316d94bac4150c55da1636e1f0ffdc46";

test("external worker image is Node 22, lockfile based and non-root with writable-path check", () => {
  const rawDockerfile = read("backend/external-review-worker/Dockerfile");
  assert.equal(rawDockerfile.split(/\r?\n/u)[0], `FROM ${node22Base}@${node22Digest}`);
  const dockerfile = rawDockerfile.replace(`@${node22Digest}`, "");
  assert.match(dockerfile, /^FROM node:22-bookworm-slim$/mu);
  assert.match(dockerfile, /npm ci --omit=dev/u);
  assert.match(dockerfile, /TESSERACT_CACHE_PATH=\/tmp\/tesseract-cache/u);
  assert.match(dockerfile, /^USER node$/mu);
  assert.match(dockerfile, /fs\.accessSync\(p, fs\.constants\.W_OK\)/u);
  assert.match(dockerfile, /CHROMIUM_EXECUTABLE_PATH.*fs\.constants\.X_OK/u);
});

test("WhatsApp image upgrades reproducibly and runs as the non-root Node user", () => {
  const rawDockerfile = read("Dockerfile.whatsapp");
  assert.equal(rawDockerfile.split(/\r?\n/u)[0], `FROM ${node22Base}@${node22Digest}`);
  const dockerfile = rawDockerfile.replace(`@${node22Digest}`, "");
  const deployScript = read("infrastructure/scripts/prod/deploy-prod.ps1");
  const legacyDeployScript = read("infrastructure/scripts/prod/deploy-prod-ssh-images.ps1");
  assert.match(dockerfile, /^FROM node:22-bookworm-slim$/mu);
  assert.match(dockerfile, /^\s*chromium-sandbox \\/mu);
  assert.match(dockerfile, /COPY whatsapp\/package\.json whatsapp\/package-lock\.json/u);
  assert.match(dockerfile, /npm ci --omit=dev/u);
  assert.match(dockerfile, /^USER node$/mu);
  assert.match(dockerfile, /chown -R node:node \/app \/auth/u);
  for (const script of [deployScript, legacyDeployScript]) {
    assert.match(script, /--entrypoint sh whatsapp_lika -c 'node_uid=.*id -u node.*node_gid=.*id -g node.*chown -R .*\/auth'/u);
    assert.match(script, /--entrypoint sh whatsapp_vika -c 'node_uid=.*id -u node.*node_gid=.*id -g node.*chown -R .*\/auth'/u);
  }
  const stop = deployScript.indexOf("compose stop whatsapp_lika whatsapp_vika");
  const sandboxPreflight = deployScript.indexOf("WhatsApp Chromium sandbox preflight failed");
  const ownershipMigration = deployScript.indexOf("--entrypoint sh whatsapp_lika", stop);
  const restart = deployScript.indexOf("recreate_service_with_retry whatsapp_lika", ownershipMigration);
  assert.ok(sandboxPreflight >= 0 && sandboxPreflight < stop);
  assert.ok(stop >= 0 && stop < ownershipMigration && ownershipMigration < restart);
});

test("production compose isolates integration workers and applies compatible bounds", () => {
  const compose = read("docker-compose.yaml");
  const app = serviceBlock(compose, "app");
  const worker = serviceBlock(compose, "external-review-worker");
  const whatsAppLika = serviceBlock(compose, "whatsapp_lika");
  const whatsAppVika = serviceBlock(compose, "whatsapp_vika");
  const mysql = serviceBlock(compose, "mysql");
  const keycloak = serviceBlock(compose, "keycloak");

  assert.match(app, /- internal_net\s+- external_review_net\s+- messaging_net/u);
  assert.match(app, /EXTERNAL_REVIEW_CHECK_WORKER_SHARED_SECRET:/u);
  assert.match(app, /WHATSAPP_GATEWAY_SHARED_SECRET:/u);
  assert.match(worker, /read_only: true/u);
  assert.match(worker, /no-new-privileges:true/u);
  assert.match(worker, /cap_drop:\s+- ALL/u);
  assert.match(worker, /pids_limit:/u);
  assert.match(worker, /mem_limit:/u);
  assert.match(worker, /cpus:/u);
  assert.match(worker, /networks:\s+- external_review_net/u);
  assert.match(worker, /EXTERNAL_REVIEW_WORKER_SHARED_SECRET:/u);
  assert.match(worker, /EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED:/u);
  assert.match(whatsAppLika, /no-new-privileges:true/u);
  assert.match(whatsAppLika, /cap_drop:\s+- ALL/u);
  assert.match(whatsAppLika, /cap_add:\s+- SYS_ADMIN\s+- SYS_CHROOT/u);
  assert.match(whatsAppVika, /no-new-privileges:true/u);
  assert.match(whatsAppVika, /cap_drop:\s+- ALL/u);
  assert.match(whatsAppVika, /cap_add:\s+- SYS_ADMIN\s+- SYS_CHROOT/u);
  assert.match(whatsAppLika, /networks:\s+- messaging_net/u);
  assert.doesNotMatch(worker, /messaging_net/u);
  assert.doesNotMatch(whatsAppLika, /external_review_net/u);
  assert.match(whatsAppLika, /WHATSAPP_GATEWAY_SHARED_SECRET:/u);
  assert.match(whatsAppLika, /WHATSAPP_GATEWAY_AUTH_REQUIRED:/u);
  assert.doesNotMatch(mysql, /external_review_net|messaging_net/u);
  assert.doesNotMatch(keycloak, /external_review_net|messaging_net/u);
});

test("prod-like compose and smoke preserve external-review DNS without data-network reachability", () => {
  const compose = read("compose.prod-local.yaml");
  const app = serviceBlock(compose, "app");
  const worker = serviceBlock(compose, "external-review-worker");
  const mysql = serviceBlock(compose, "mysql");
  const smoke = read("infrastructure/scripts/local/prod-like-smoke.ps1");

  assert.match(app, /- internal_net\s+- external_review_net\s+- messaging_net/u);
  assert.match(worker, /networks:\s+- external_review_net/u);
  assert.doesNotMatch(worker, /internal_net|messaging_net/u);
  assert.doesNotMatch(mysql, /external_review_net|messaging_net/u);
  assert.match(smoke, /getent", "hosts", "external-review-worker"/u);
  assert.match(smoke, /lookup\('mysql'.*process\.exit\(error \? 0 : 1\)/u);
});

test("production example fails closed until both integration secrets are provisioned", () => {
  const env = read(".env.prod.example");
  assert.match(env, /^EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED=true$/mu);
  assert.match(env, /^WHATSAPP_GATEWAY_AUTH_REQUIRED=true$/mu);
  assert.match(env, /^EXTERNAL_REVIEW_WORKER_SHARED_SECRET=$/mu);
  assert.match(env, /^WHATSAPP_GATEWAY_SHARED_SECRET=$/mu);
});

test("production deploy bundle contains every local WhatsApp runtime require", () => {
  const deployScript = read("infrastructure/scripts/prod/deploy-prod.ps1");
  const runtimeFiles = localRequireClosure("whatsapp/index.js");

  for (const runtimeFile of runtimeFiles) {
    const windowsPath = runtimeFile.replaceAll("/", "\\");
    assert.match(
      deployScript,
      new RegExp(`"${escapeRegExp(windowsPath)}"`, "u"),
      `${runtimeFile} is missing from deployBundlePaths`,
    );
  }
});

function read(relativePath) {
  return fs.readFileSync(path.join(root, relativePath), "utf8");
}

function serviceBlock(compose, serviceName) {
  const marker = new RegExp(`^  ${escapeRegExp(serviceName)}:`, "mu").exec(compose);
  assert.notEqual(marker, null, `missing service ${serviceName}`);
  const rest = compose.slice(marker.index + marker[0].length);
  const nextService = rest.search(/^  [a-zA-Z0-9_-]+:/mu);
  return nextService < 0 ? rest : rest.slice(0, nextService);
}

function localRequireClosure(entry) {
  const pending = [entry];
  const visited = new Set();
  while (pending.length > 0) {
    const relativePath = pending.pop();
    if (visited.has(relativePath)) {
      continue;
    }
    visited.add(relativePath);
    const source = read(relativePath);
    const directory = path.posix.dirname(relativePath);
    for (const match of source.matchAll(/require\(["'](\.[^"']+)["']\)/gu)) {
      const child = path.posix.normalize(path.posix.join(directory, match[1]));
      const childWithExtension = path.posix.extname(child) ? child : `${child}.js`;
      assert.equal(fs.existsSync(path.join(root, childWithExtension)), true, `missing ${childWithExtension}`);
      pending.push(childWithExtension);
    }
  }
  return [...visited].sort();
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
}
