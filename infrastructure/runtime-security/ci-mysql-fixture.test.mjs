import assert from 'node:assert/strict';
import test from 'node:test';
import { selectMysqlFixture } from './ci-mysql-fixture.mjs';

const reference = 'ghcr.io/claidd/otziv-security@sha256:' + 'a'.repeat(64);
const activation = () => ({ schema: 'otziv-reviewed-image-activations-v1', images: [{
  component: 'mysql', reference, manifest: { path: 'infrastructure/runtime-security/reviewed-images-c46-mysql.json' },
  databaseTransition: { path: 'infrastructure/runtime-security/proofs/c46-mysql/rehearsal.json', sha256: 'b'.repeat(64) },
}] });
const inventory = () => [{ image: reference, references: [
  { path: 'compose.yaml', service: 'mysql' }, { path: 'compose.prod-local.yaml', service: 'mysql' },
  { path: 'docker-compose.yaml', service: 'mysql' },
] }];

test('issuer CI selects the exact active MySQL used by all production Compose files', () => {
  assert.equal(selectMysqlFixture(activation(), inventory()), reference);
});

test('unregistered, ambiguous or unaccepted MySQL cannot enter the issuer fixture', () => {
  const unknown = activation(); unknown.images[0].manifest.path = 'unregistered';
  assert.throws(() => selectMysqlFixture(unknown, inventory()), /manifest_component/);
  const duplicate = activation(); duplicate.images.push(duplicate.images[0]);
  assert.throws(() => selectMysqlFixture(duplicate, inventory()), /activation_ambiguous/);
  const missing = activation(); delete missing.images[0].databaseTransition;
  assert.throws(() => selectMysqlFixture(missing, inventory()), /rehearsal_missing/);
  const tag = activation(); tag.images[0].reference = 'mysql:latest';
  assert.throws(() => selectMysqlFixture(tag, inventory()), /immutable_reference/);
});

test('a rebuilt or mismatched MySQL and incomplete Compose coverage are refused', () => {
  const wrong = inventory(); wrong[0].image = 'mysql:rebuilt';
  assert.throws(() => selectMysqlFixture(activation(), wrong), /compose_activation_mismatch/);
  const incomplete = inventory(); incomplete[0].references.pop();
  assert.throws(() => selectMysqlFixture(activation(), incomplete), /compose_coverage/);
});
