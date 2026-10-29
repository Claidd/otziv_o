import assert from 'node:assert/strict';
import { test } from 'node:test';
import { selectKeycloakFixture } from './ci-keycloak-fixture.mjs';

const reference = 'ghcr.io/claidd/otziv-security@sha256:' + 'a'.repeat(64);
const acceptance = { path: 'infrastructure/runtime-security/proofs/c45-keycloak/acceptance.json',
  sha256: 'b'.repeat(64) };
const activation = () => ({ schema: 'otziv-reviewed-image-activations-v1', images: [{
  component: 'keycloak', reference,
  manifest: { path: 'infrastructure/runtime-security/reviewed-images-c45-keycloak.json' },
  brotliRefreshAcceptance: acceptance, migrationAcceptance: { ...acceptance },
}] });
const inventory = () => [{ image: reference, references: [
  { path: 'compose.yaml', service: 'keycloak' },
  { path: 'compose.prod-local.yaml', service: 'keycloak' },
  { path: 'docker-compose.yaml', service: 'keycloak' },
] }];

test('issuer CI scans the accepted current Keycloak digest used by every Compose file', () => {
  assert.equal(selectKeycloakFixture(activation(), inventory()), reference);
});

test('issuer CI refuses an unregistered image or an unaccepted transition', () => {
  const old = activation();
  old.images[0].manifest.path = 'infrastructure/runtime-security/reviewed-images-unregistered-keycloak.json';
  assert.throws(() => selectKeycloakFixture(old, inventory()), /manifest_component/);
  const unaccepted = activation();
  delete unaccepted.images[0].migrationAcceptance;
  assert.throws(() => selectKeycloakFixture(unaccepted, inventory()), /ci_keycloak_acceptance_missing/);
  const unbound = activation();
  unbound.images[0].migrationAcceptance.sha256 = 'c'.repeat(64);
  assert.throws(() => selectKeycloakFixture(unbound, inventory()), /ci_keycloak_transition_unbound/);
});

test('issuer CI refuses a mismatched or incomplete production Compose reference', () => {
  const wrong = inventory();
  wrong[0].image = 'ghcr.io/claidd/otziv-security@sha256:' + 'c'.repeat(64);
  assert.throws(() => selectKeycloakFixture(activation(), wrong), /ci_keycloak_compose_activation_mismatch/);
  const incomplete = inventory();
  incomplete[0].references.pop();
  assert.throws(() => selectKeycloakFixture(activation(), incomplete), /ci_keycloak_compose_coverage/);
});
