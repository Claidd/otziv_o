import assert from 'node:assert/strict';
import { test } from 'node:test';
import { selectPostgresC36Fixture } from './ci-postgres-c36-fixture.mjs';

const reference = 'ghcr.io/claidd/otziv-security@sha256:' + 'a'.repeat(64);
const acceptance = { path: 'infrastructure/runtime-security/proofs/c36-postgres/acceptance.json',
  sha256: 'b'.repeat(64) };
const activation = () => ({ schema: 'otziv-reviewed-image-activations-v1', images: [{
  component: 'postgres', reference,
  manifest: { path: 'infrastructure/runtime-security/reviewed-images-c36-postgres.json' },
  postgresC36Acceptance: acceptance, databaseTransition: { ...acceptance },
}] });
const inventory = () => [{ image: reference, references: [
  { path: 'compose.yaml', service: 'keycloak-postgres' },
  { path: 'compose.prod-local.yaml', service: 'keycloak-postgres' },
  { path: 'docker-compose.yaml', service: 'keycloak-postgres' },
] }];

test('issuer CI scans the exact activated C36 PostgreSQL digest used by every Compose file', () => {
  assert.equal(selectPostgresC36Fixture(activation(), inventory()), reference);
});

test('issuer CI refuses the old PostgreSQL image or an unaccepted transition', () => {
  const old = activation();
  old.images[0].manifest.path = 'infrastructure/runtime-security/reviewed-images-c23-postgres.json';
  assert.throws(() => selectPostgresC36Fixture(old, inventory()), /ci_postgres_c36_not_activated/);
  const unaccepted = activation();
  delete unaccepted.images[0].postgresC36Acceptance;
  assert.throws(() => selectPostgresC36Fixture(unaccepted, inventory()), /ci_postgres_c36_acceptance_missing/);
  const unbound = activation();
  unbound.images[0].databaseTransition.sha256 = 'c'.repeat(64);
  assert.throws(() => selectPostgresC36Fixture(unbound, inventory()), /ci_postgres_c36_transition_unbound/);
});

test('issuer CI refuses a mismatched or incomplete production Compose reference', () => {
  const wrong = inventory();
  wrong[0].image = 'ghcr.io/claidd/otziv-security@sha256:' + 'c'.repeat(64);
  assert.throws(() => selectPostgresC36Fixture(activation(), wrong), /ci_postgres_compose_activation_mismatch/);
  const incomplete = inventory();
  incomplete[0].references.pop();
  assert.throws(() => selectPostgresC36Fixture(activation(), incomplete), /ci_postgres_compose_coverage/);
});
