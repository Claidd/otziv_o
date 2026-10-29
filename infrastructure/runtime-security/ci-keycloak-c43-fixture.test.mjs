import assert from 'node:assert/strict';
import { test } from 'node:test';
import { selectKeycloakC43Fixture } from './ci-keycloak-c43-fixture.mjs';

const reference = 'ghcr.io/claidd/otziv-security@sha256:' + 'a'.repeat(64);
const acceptance = { path: 'infrastructure/runtime-security/proofs/c43-keycloak/acceptance.json',
  sha256: 'b'.repeat(64) };
const activation = () => ({ schema: 'otziv-reviewed-image-activations-v1', images: [{
  component: 'keycloak', reference,
  manifest: { path: 'infrastructure/runtime-security/reviewed-images-c43-keycloak.json' },
  guavaRefreshAcceptance: acceptance, migrationAcceptance: { ...acceptance },
}] });
const inventory = () => [{ image: reference, references: [
  { path: 'compose.yaml', service: 'keycloak' },
  { path: 'compose.prod-local.yaml', service: 'keycloak' },
  { path: 'docker-compose.yaml', service: 'keycloak' },
] }];

test('issuer CI scans the exact activated C43 Keycloak digest used by every Compose file', () => {
  assert.equal(selectKeycloakC43Fixture(activation(), inventory()), reference);
});

test('issuer CI refuses the old Keycloak image or an unaccepted transition', () => {
  const old = activation();
  old.images[0].manifest.path = 'infrastructure/runtime-security/reviewed-images-c35-keycloak.json';
  assert.throws(() => selectKeycloakC43Fixture(old, inventory()), /ci_keycloak_c43_not_activated/);
  const unaccepted = activation();
  delete unaccepted.images[0].guavaRefreshAcceptance;
  assert.throws(() => selectKeycloakC43Fixture(unaccepted, inventory()), /ci_keycloak_c43_acceptance_missing/);
  const unbound = activation();
  unbound.images[0].migrationAcceptance.sha256 = 'c'.repeat(64);
  assert.throws(() => selectKeycloakC43Fixture(unbound, inventory()), /ci_keycloak_c43_transition_unbound/);
});

test('issuer CI refuses a mismatched or incomplete production Compose reference', () => {
  const wrong = inventory();
  wrong[0].image = 'ghcr.io/claidd/otziv-security@sha256:' + 'c'.repeat(64);
  assert.throws(() => selectKeycloakC43Fixture(activation(), wrong), /ci_keycloak_compose_activation_mismatch/);
  const incomplete = inventory();
  incomplete[0].references.pop();
  assert.throws(() => selectKeycloakC43Fixture(activation(), incomplete), /ci_keycloak_compose_coverage/);
});
