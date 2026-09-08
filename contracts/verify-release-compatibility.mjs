import { readSourceFingerprints } from './source-fingerprints.mjs';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { loadTsModule } from '../mobile/test/load-ts-module.mjs';

const json = relative => JSON.parse(fs.readFileSync(new URL(relative, import.meta.url), 'utf8'));
const inventory = json('releases/inventory.json');
const current = json('generated/client-api.openapi.json');
readSourceFingerprints(current);
const fixtures = json('fixtures/client-api-current.json');
const sdk = loadTsModule('../shared/client-common/src/client-api.ts');
assert.equal(inventory.formatVersion, 1);
assert.ok(inventory.entries.includes(inventory.latestPublished));
for (const name of inventory.entries) {
  assert.match(name, /^[a-z0-9-]+$/);
  const previous = json(`releases/${name}/backend.openapi.json`);
  readSourceFingerprints(previous);
  const previousValues = json(`releases/${name}/backend-fixtures.json`);
  const provenance = json(`releases/${name}/provenance.json`);
  const metadata = json(`releases/${name}/published-update.json`);
  assert.equal(metadata.versionCode, provenance.versionCode);
  assert.equal(metadata.sha256.toLowerCase(), provenance.apkSha256.toLowerCase());
  assert.match(provenance.publishedBackend.jarSha256, /^[a-f0-9]{64}$/);
  for (const [path, verbs] of Object.entries(previous.paths)) for (const [method, operation] of Object.entries(verbs)) {
    assert.ok(current.paths[path]?.[method], `Supported operation removed: ${method} ${path}`);
    assert.deepEqual(current.paths[path][method].security, operation.security, `Authentication boundary changed: ${method} ${path}`);
  }
  // New client reading the published backend, and published wire schema reading
  // candidate JSON. This is serialization compatibility, not an installed-native test.
  for (const [type, value] of Object.entries(previousValues.responses)) sdk.validateClientJson(value, { $ref: `#/components/schemas/${type}` }, 'response');
  for (const type of Object.keys(previousValues.responses)) sdk.validateClientJson(fixtures.responses[type], { $ref: `#/components/schemas/${type}` }, 'response', '$', previous.components.schemas);
  for (const [type, value] of Object.entries(previousValues.requests)) sdk.validateClientJson(value, { $ref: `#/components/schemas/${type}` }, 'request');
  for (const [type, oldSchema] of Object.entries(previous.components.schemas)) {
    if (oldSchema.enum) assert.deepEqual(current.components.schemas[type].enum, oldSchema.enum, `Enum change requires old-client runtime evidence: ${type}`);
  }
  const request = json(`releases/${name}/request-public-init.json`);
  sdk.prepareClientOperation('POST /api/payments/public/{token}/init', { path: { token: 'release-fixture' }, query: {}, body: request });
  console.log(`Published ${name}: bidirectional DTO fixtures and observed old-client request PASS`);
}
const minimumVerified = inventory.minimumSupported != null && inventory.entries.includes(inventory.minimumSupported);
console.log(JSON.stringify({ latestPublished: inventory.latestPublished, minimumVerified, declaredMinimumVersionCode: inventory.declaredMinimumVersionCode,
  supportWindowReady: minimumVerified, nativeUpgradeProven: false }));
if (process.argv.includes('--require-supported-releases') && !minimumVerified) {
  throw new Error('Minimum supported release has not been established and tested; 0 is not release evidence.');
}
