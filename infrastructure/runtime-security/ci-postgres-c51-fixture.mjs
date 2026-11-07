import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { repositoryInventory, composeFiles } from './upstream-images.mjs';

const ACTIVATION_PATH = 'infrastructure/runtime-security/reviewed-image-activations.json';
const C51_MANIFEST = 'infrastructure/runtime-security/reviewed-images-c51-postgres.json';
const C51_ACCEPTANCE = 'infrastructure/runtime-security/proofs/c51-postgres/acceptance.json';

// The issuer CI job scans and exercises the exact activated C51 PostgreSQL digest.
export function selectPostgresC51Fixture(activations, inventory) {
  assert.equal(activations?.schema, 'otziv-reviewed-image-activations-v1',
    'ci_postgres_activation_schema');
  const entries = (activations.images || []).filter(entry => entry.component === 'postgres');
  assert.equal(entries.length, 1, 'ci_postgres_activation_ambiguous');
  const entry = entries[0];
  assert.equal(entry.manifest?.path, C51_MANIFEST, 'ci_postgres_c51_not_activated');
  assert.match(entry.reference || '', /^ghcr\.io\/claidd\/otziv-security@sha256:[a-f0-9]{64}$/,
    'ci_postgres_immutable_reference');
  assert.equal(entry.postgresC51Acceptance?.path, C51_ACCEPTANCE,
    'ci_postgres_c51_acceptance_missing');
  assert.match(entry.postgresC51Acceptance?.sha256 || '', /^[a-f0-9]{64}$/,
    'ci_postgres_c51_acceptance_hash_missing');
  assert.deepEqual(entry.databaseTransition, entry.postgresC51Acceptance,
    'ci_postgres_c51_transition_unbound');
  const references = inventory.filter(row => row.references.some(ref => ref.service === 'keycloak-postgres'));
  assert.equal(references.length, 1, 'ci_postgres_compose_image_ambiguous');
  assert.equal(references[0].image, entry.reference, 'ci_postgres_compose_activation_mismatch');
  const services = references[0].references
    .filter(ref => ref.service === 'keycloak-postgres').map(ref => ref.path).sort();
  assert.deepEqual(services, [...composeFiles].sort(), 'ci_postgres_compose_coverage');
  return entry.reference;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  assert.equal(process.argv.length, 2, 'ci_postgres_arguments_invalid');
  const activations = JSON.parse(await readFile(ACTIVATION_PATH, 'utf8'));
  console.log(selectPostgresC51Fixture(activations, await repositoryInventory()));
}
