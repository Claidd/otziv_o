import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { createEvidenceReader, validateReviewedDefaults } from './reviewed-image-defaults.mjs';
import { repositoryInventory } from './upstream-images.mjs';

const manifest = await readFile(new URL('./reviewed-images.json', import.meta.url));
const original = JSON.parse(manifest).images.find(image => image.component === 'mysql');
const activations = JSON.parse(await readFile(new URL('./reviewed-image-activations.json', import.meta.url)));
const base = 'infrastructure/runtime-security/proofs/c7-published/mysql';
const underlyingRead = await createEvidenceReader(process.cwd()), cache = new Map();
const read = async path => {
  if (!cache.has(path)) cache.set(path, await underlyingRead(path));
  return cache.get(path);
};
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const proof = async path => ({ path, sha256: hash(await read(path)) });
const publication = JSON.parse(await read(base + '/publication/publication.json'));
const entry = { component: 'mysql', reference: publication.reference, commit: publication.commit,
  run: publication.run, attempt: publication.attempt,
  publication: await proof(base + '/publication/publication.json'),
  anonymous: await proof(base + '/anonymous/anonymous-download.json'),
  databaseTransition: await proof('infrastructure/runtime-security/proofs/c14-mysql-vps/actual-result.json') };
const rows = (await repositoryInventory()).map(row => row.image === original.sourceBeforeRef ? { ...row, image: entry.reference } : row);
const index = candidate => ({ ...activations, images: [...activations.images.filter(image => image.component !== 'mysql'), candidate] });

test('actual VPS replay plus full C7 OCI/anonymous pair admits only coordinated MySQL candidate preparation', async () => {
  const checks = await validateReviewedDefaults(rows, manifest, index(entry), read, true);
  const databases = checks.filter(check => check.component === 'mysql');
  assert.equal(databases.length, original.defaultReferencesBefore.length);
  assert.ok(databases.every(check => check.reference === entry.reference &&
    check.databaseTransition === 'COORDINATED_CANDIDATE_PREPARATION' && check.ordinaryDeploymentUpgradeAuthorized === false));
  assert.ok(checks.filter(check => check.component === 'postgres').every(check => check.mode === 'EXACT_ORIGINAL_SOURCE'));
});
test('publication alone, a typed approval or a transition for another image cannot release the database hold', async () => {
  const missing = { ...entry }; delete missing.databaseTransition;
  for (const candidate of [missing, { ...entry, databaseTransition: { approved: true } },
    { ...entry, reference: 'ghcr.io/claidd/otziv-security@sha256:' + 'b'.repeat(64) }])
    await assert.rejects(validateReviewedDefaults(rows, manifest, index(candidate), read, true), /transition/);
});
test('a valid replay never substitutes for the independently verified anonymous publication pair', async () => {
  const wrongBytes = Buffer.from(JSON.stringify({ ...JSON.parse(await read(entry.anonymous.path)), sourcePublicationSha256: 'a'.repeat(64) }));
  const changed = { ...entry, anonymous: { ...entry.anonymous, sha256: hash(wrongBytes) } };
  await assert.rejects(validateReviewedDefaults(rows, manifest, index(changed),
    path => path === entry.anonymous.path ? wrongBytes : read(path), true), /publication_pair_mismatch/);
});
