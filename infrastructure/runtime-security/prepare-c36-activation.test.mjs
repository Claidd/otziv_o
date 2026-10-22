import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { prepareActivationPair, readArtifactDirectory, stageActivationPair } from './prepare-c36-activation.mjs';

const root = resolve(fileURLToPath(new URL('../../', import.meta.url)));
const proof = resolve(root, 'infrastructure/runtime-security/proofs/c29-dozzle');
const publicationDirectory = resolve(proof, 'publication');
const anonymousDirectory = resolve(proof, 'anonymous');
const publication = JSON.parse(await readFile(resolve(publicationDirectory, 'publication.json')));
const identity = { commit: publication.commit, run: publication.run, attempt: publication.attempt };
const inputs = async () => ({
  manifestSet: 'c29-dozzle',
  publicationFiles: await readArtifactDirectory(publicationDirectory, 'publication'),
  anonymousFiles: await readArtifactDirectory(anonymousDirectory, 'anonymous'),
  expectedIdentity: identity,
  root,
});

test('existing C29 proof pair passes the exact validation used for C36 staging', async () => {
  const prepared = await prepareActivationPair(await inputs());
  assert.equal(prepared.activationReady, true);
  assert.equal(prepared.entry.reference, publication.reference);
  assert.equal(prepared.entry.run, publication.run);
  assert.match(prepared.entry.publication.sha256, /^[a-f0-9]{64}$/);
  const dry = await stageActivationPair({ manifestSet: 'c29-dozzle', publicationDirectory,
    anonymousDirectory, expectedIdentity: identity, root });
  assert.equal(dry.mode, 'DRY_RUN');
  assert.equal(dry.proofDirectory, null);
});

test('rejects a different CI attempt even when local artifacts agree with each other', async () => {
  const input = await inputs();
  input.expectedIdentity = { ...identity, attempt: String(Number(identity.attempt) + 1) };
  await assert.rejects(prepareActivationPair(input), /c36_expected_github_run_mismatch/);
});

test('rejects a changed anonymous proof paired with an unchanged publication', async () => {
  const input = await inputs();
  const altered = JSON.parse(input.anonymousFiles.get('anonymous-download.json'));
  altered.sourcePublicationSha256 = '0'.repeat(64);
  input.anonymousFiles.set('anonymous-download.json', Buffer.from(JSON.stringify(altered)));
  await assert.rejects(prepareActivationPair(input), /c36_pair_publication_hash/);
});

test('rejects changed raw scanner bytes even if summary still says PASS', async () => {
  const input = await inputs();
  const raw = JSON.parse(input.publicationFiles.get('vulnerabilities.json'));
  raw.CreatedAt = '2000-01-01T00:00:00Z';
  input.publicationFiles.set('vulnerabilities.json', Buffer.from(JSON.stringify(raw)));
  await assert.rejects(prepareActivationPair(input), /c36_scan_receipt_raw_hash/);
});

test('C37 MySQL publication is strictly verified and staged only as an incomplete database activation', async () => {
  const c37 = resolve(root, 'infrastructure/runtime-security/proofs/c37-mysql');
  const publicationDirectory = resolve(c37, 'publication');
  const anonymousDirectory = resolve(c37, 'anonymous');
  const published = JSON.parse(await readFile(resolve(publicationDirectory, 'publication.json')));
  const prepared = await prepareActivationPair({
    manifestSet: 'c37-mysql',
    publicationFiles: await readArtifactDirectory(publicationDirectory, 'publication'),
    anonymousFiles: await readArtifactDirectory(anonymousDirectory, 'anonymous'),
    expectedIdentity: { commit: published.commit, run: published.run, attempt: published.attempt },
    root,
  });
  assert.equal(prepared.entry.reference, published.reference);
  assert.equal(prepared.activationReady, false);
  assert.match(prepared.remaining, /published-digest MySQL rehearsal/);
});
