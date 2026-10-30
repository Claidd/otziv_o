import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { repositoryInventory, composeFiles } from './upstream-images.mjs';
import { createEvidenceReader, validateActivation } from './reviewed-image-defaults.mjs';
import { reviewedImageSetForComponent } from './reviewed-image-sets.mjs';
import { validateDatabaseTransitionReadiness } from './mysql-c46-readiness.mjs';

export function selectMysqlFixture(activations, inventory) {
  assert.equal(activations?.schema, 'otziv-reviewed-image-activations-v1', 'ci_mysql_activation_schema');
  const entries = (activations.images || []).filter(entry => entry.component === 'mysql');
  assert.equal(entries.length, 1, 'ci_mysql_activation_ambiguous');
  const entry = entries[0];
  const selected = reviewedImageSetForComponent('mysql', entry.manifest?.path);
  assert.match(entry.reference || '', /^ghcr\.io\/claidd\/otziv-security@sha256:[a-f0-9]{64}$/, 'ci_mysql_immutable_reference');
  assert.equal(entry.databaseTransition?.path,
    'infrastructure/runtime-security/proofs/' + selected.name + '/rehearsal.json', 'ci_mysql_rehearsal_missing');
  assert.match(entry.databaseTransition?.sha256 || '', /^[a-f0-9]{64}$/, 'ci_mysql_rehearsal_hash_missing');
  const references = inventory.filter(row => row.references.some(ref => ref.service === 'mysql'));
  assert.equal(references.length, 1, 'ci_mysql_compose_image_ambiguous');
  assert.equal(references[0].image, entry.reference, 'ci_mysql_compose_activation_mismatch');
  assert.deepEqual(references[0].references.filter(ref => ref.service === 'mysql').map(ref => ref.path).sort(),
    [...composeFiles].sort(), 'ci_mysql_compose_coverage');
  return entry.reference;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  assert.equal(process.argv.length, 2, 'ci_mysql_arguments_invalid');
  const activations = JSON.parse(await readFile('infrastructure/runtime-security/reviewed-image-activations.json', 'utf8'));
  const reference = selectMysqlFixture(activations, await repositoryInventory());
  const read = await createEvidenceReader(process.cwd());
  const baseline = await read('infrastructure/runtime-security/reviewed-images.json');
  const image = JSON.parse(baseline).images.find(item => item.component === 'mysql');
  const entry = activations.images.find(item => item.component === 'mysql');
  await validateActivation(image, entry, baseline, read);
  await validateDatabaseTransitionReadiness(entry, image, read);
  console.log(reference);
}
