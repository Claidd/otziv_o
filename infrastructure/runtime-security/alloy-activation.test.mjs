import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { validateRepositoryDefaults } from './reviewed-image-defaults.mjs';

const root = new URL('../../', import.meta.url);
const index = JSON.parse(await readFile(new URL('./c23-parent-activations.json', import.meta.url)));
const actualEntry = index.images.find(item => item.component === 'alloy');
const publication = JSON.parse(await readFile(new URL(actualEntry.publication.path, root)));

test('all current activations validate with retained registry bytes; DB preparation never authorizes ordinary upgrade', async () => {
  const result = await validateRepositoryDefaults(fileURLToPath(root));
  assert.equal(result.length, 35);
  for (const row of result.filter(row => ['mysql', 'postgres'].includes(row.component))) {
    if (row.mode === 'EXACT_ORIGINAL_SOURCE') continue;
    assert.equal(row.mode, 'PAIRED_PUBLICATION_AND_ANONYMOUS_EVIDENCE');
    assert.equal(row.databaseTransition, 'COORDINATED_CANDIDATE_PREPARATION');
    assert.equal(row.ordinaryDeploymentUpgradeAuthorized, false);
  }
  assert.ok(result.filter(row => row.component === 'alloy').every(row => row.mode === 'PAIRED_PUBLICATION_AND_ANONYMOUS_EVIDENCE'));
  assert.equal(publication.security.unresolvedRiskReview, 'REQUIRED'); // C7 history was not rewritten.
});
