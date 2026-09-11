import test from 'node:test';
import assert from 'node:assert/strict';
import { inventory, repositoryInventory } from './upstream-images.mjs';
import { validateRepositoryDefaults } from './reviewed-image-defaults.mjs';

const digest = 'a'.repeat(64);
const doc = text => ({ path: 'fixture.yaml', text: 'services:\n' + text });

test('same tagged and untagged digest is scanned once with every deployment reference', () => {
  const rows = inventory([doc(`  first:\n    image: postgres:17@sha256:${digest}\n  second:\n    image: postgres@sha256:${digest}\n`)]);
  assert.equal(rows.length, 1); assert.equal(rows[0].references.length, 2);
  assert.equal(rows[0].image, `postgres@sha256:${digest}`);
});
test('an overridable issuer still scans its real release fallback', () => {
  const rows = inventory([doc('  issuer:\n    image: ${ISSUER_IMAGE:-quay.io/keycloak/keycloak@sha256:' + digest + '}\n')]);
  assert.equal(rows[0].image, 'quay.io/keycloak/keycloak@sha256:' + digest);
});
for (const image of ['postgres:latest', '${DATABASE_IMAGE}', '${DATABASE_IMAGE:-postgres:17}'])
  test('new unpinned upstream fails: ' + image, () => {
    assert.throws(() => inventory([doc(`  database:\n    image: ${image}\n`)]), /requires_digest/);
  });
test('only explicit separately built application variables are excluded', () => {
  const rows = inventory([doc('  app:\n    image: ${APP_IMAGE:?release required}\n  db:\n    image: postgres@sha256:' + digest + '\n')]);
  assert.equal(rows.length, 1); assert.equal(rows[0].references[0].service, 'db');
});
test('empty inventory cannot silently pass a release scan', () => {
  assert.throws(() => inventory([doc('  app:\n    image: ${APP_IMAGE:-app:local}\n')]), /inventory_empty/);
});
test('actual Compose models cover database, issuer, monitoring and optional upstreams', async () => {
  const rows = await repositoryInventory();
  const reviewed = await validateRepositoryDefaults(process.cwd(), rows);
  assert.equal(new Set(reviewed.map(item => item.component)).size, 14);
  for (const [component, service] of [['minio', 'minio'], ['mc', 'minio-init']]) {
    const checks = reviewed.filter(item => item.component === component);
    assert.deepEqual(checks.map(item => [item.path, item.service]), [['compose.prod-local.yaml', service]]);
    assert.ok(rows.some(row => row.image === checks[0].reference && row.references.some(reference =>
      reference.path === 'compose.prod-local.yaml' && reference.service === service)), component);
    assert.ok(['EXACT_ORIGINAL_SOURCE', 'PAIRED_PUBLICATION_AND_ANONYMOUS_EVIDENCE'].includes(checks[0].mode));
  }
  assert.ok(rows.some(row => row.image.startsWith('amir20/dozzle@')), 'amir20/dozzle');
  assert.equal(new Set(rows.map(row => row.id)).size, rows.length);
});
