import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { repositoryInventory, composeFiles } from './upstream-images.mjs';
import { createEvidenceReader, validateActivation } from './reviewed-image-defaults.mjs';

const ACTIVATION_PATH = 'infrastructure/runtime-security/reviewed-image-activations.json';
const C43_MANIFEST = 'infrastructure/runtime-security/reviewed-images-c43-keycloak.json';
const C43_ACCEPTANCE = 'infrastructure/runtime-security/proofs/c43-keycloak/acceptance.json';

export function selectKeycloakC43Fixture(activations, inventory) {
  assert.equal(activations?.schema, 'otziv-reviewed-image-activations-v1',
    'ci_keycloak_activation_schema');
  const entries = (activations.images || []).filter(entry => entry.component === 'keycloak');
  assert.equal(entries.length, 1, 'ci_keycloak_activation_ambiguous');
  const entry = entries[0];
  assert.equal(entry.manifest?.path, C43_MANIFEST, 'ci_keycloak_c43_not_activated');
  assert.match(entry.reference || '', /^ghcr\.io\/claidd\/otziv-security@sha256:[a-f0-9]{64}$/,
    'ci_keycloak_immutable_reference');
  assert.equal(entry.guavaRefreshAcceptance?.path, C43_ACCEPTANCE,
    'ci_keycloak_c43_acceptance_missing');
  assert.match(entry.guavaRefreshAcceptance?.sha256 || '', /^[a-f0-9]{64}$/,
    'ci_keycloak_c43_acceptance_hash_missing');
  assert.deepEqual(entry.migrationAcceptance, entry.guavaRefreshAcceptance,
    'ci_keycloak_c43_transition_unbound');
  const references = inventory.filter(row => row.references.some(ref => ref.service === 'keycloak'));
  assert.equal(references.length, 1, 'ci_keycloak_compose_image_ambiguous');
  assert.equal(references[0].image, entry.reference, 'ci_keycloak_compose_activation_mismatch');
  const services = references[0].references
    .filter(ref => ref.service === 'keycloak').map(ref => ref.path).sort();
  assert.deepEqual(services, [...composeFiles].sort(), 'ci_keycloak_compose_coverage');
  return entry.reference;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  assert.equal(process.argv.length, 2, 'ci_keycloak_arguments_invalid');
  const activations = JSON.parse(await readFile(ACTIVATION_PATH, 'utf8'));
  const reference = selectKeycloakC43Fixture(activations, await repositoryInventory());
  const read = await createEvidenceReader(process.cwd());
  const baseline = await read('infrastructure/runtime-security/reviewed-images.json');
  const image = JSON.parse(baseline).images.find(item => item.component === 'keycloak');
  const entry = activations.images.find(item => item.component === 'keycloak');
  await validateActivation(image, entry, baseline, read);
  console.log(reference);
}
