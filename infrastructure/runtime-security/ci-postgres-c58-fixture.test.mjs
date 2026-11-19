import assert from 'node:assert/strict';
import { test } from 'node:test';
import { selectPostgresC58Fixture } from './ci-postgres-c58-fixture.mjs';

const reference = 'ghcr.io/claidd/otziv-security@sha256:' + 'a'.repeat(64);
const acceptance = { path: 'infrastructure/runtime-security/proofs/c58-postgres/acceptance.json',
  sha256: 'b'.repeat(64) };
const activation = () => ({ schema: 'otziv-reviewed-image-activations-v1', images: [{
  component: 'postgres', reference,
  manifest: { path: 'infrastructure/runtime-security/reviewed-images-c58-postgres.json' },
  postgresC58Acceptance: acceptance, databaseTransition: { ...acceptance },
}] });
const inventory = () => [{ image: reference, references: [
  { path: 'compose.yaml', service: 'keycloak-postgres' },
  { path: 'compose.prod-local.yaml', service: 'keycloak-postgres' },
  { path: 'docker-compose.yaml', service: 'keycloak-postgres' },
] }];

test('issuer CI scans the exact activated C58 PostgreSQL digest used by every Compose file', () => {
  assert.equal(selectPostgresC58Fixture(activation(), inventory()), reference);
});

test('issuer CI refuses the replaced C54 image or an unaccepted transition', () => {
  const old = activation();
  old.images[0].manifest.path = 'infrastructure/runtime-security/reviewed-images-c54-postgres.json';
  assert.throws(() => selectPostgresC58Fixture(old, inventory()), /ci_postgres_c58_not_activated/);
  const previous = activation();
  previous.images[0].postgresC54Acceptance = { ...acceptance,
    path: 'infrastructure/runtime-security/proofs/c54-postgres/acceptance.json' };
  delete previous.images[0].postgresC58Acceptance;
  assert.throws(() => selectPostgresC58Fixture(previous, inventory()), /ci_postgres_c58_acceptance_missing/);
  const unbound = activation();
  unbound.images[0].databaseTransition.sha256 = 'c'.repeat(64);
  assert.throws(() => selectPostgresC58Fixture(unbound, inventory()), /ci_postgres_c58_transition_unbound/);
});

test('issuer CI refuses a mismatched or incomplete production Compose reference', () => {
  const wrong = inventory();
  wrong[0].image = 'ghcr.io/claidd/otziv-security@sha256:' + 'c'.repeat(64);
  assert.throws(() => selectPostgresC58Fixture(activation(), wrong), /ci_postgres_compose_activation_mismatch/);
  const incomplete = inventory();
  incomplete[0].references.pop();
  assert.throws(() => selectPostgresC58Fixture(activation(), incomplete), /ci_postgres_compose_coverage/);
});
