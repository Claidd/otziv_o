import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createEvidenceReader, validateActivation } from './reviewed-image-defaults.mjs';
import { validateManifest } from './publish-reviewed-images.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const prefix = 'infrastructure/runtime-security/';
const publicationPath = prefix + 'proofs/c41-grafana/publication/publication.json';
const rawPath = prefix + 'proofs/c41-grafana/publication/vulnerabilities.json';
const receiptPath = prefix + 'proofs/c41-grafana/publication/vulnerabilities.adjudications.json';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const encode = value => Buffer.from(JSON.stringify(value));

async function fixture() {
  const canonicalRead = await createEvidenceReader(root);
  const baseline = await canonicalRead(prefix + 'reviewed-images.json');
  const image = validateManifest(JSON.parse(baseline)).find(item => item.component === 'grafana');
  const publication = JSON.parse(await canonicalRead(publicationPath));
  assert.equal(publication.manifestSet, 'c41-grafana');
  assert.equal(publication.commit, '632ea46485e75174dbdac0d3b88e100231705212');
  assert.equal(publication.run, '40278086723');
  assert.equal(publication.attempt, '1');
  const bound = async path => ({ path, sha256: hash(await canonicalRead(path)) });
  const entry = { component: 'grafana', reference: publication.reference,
    commit: publication.commit, run: publication.run, attempt: publication.attempt,
    manifest: await bound(prefix + 'reviewed-images-c41-grafana.json'),
    publication: await bound(publicationPath),
    anonymous: await bound(prefix + 'proofs/c41-grafana/anonymous/anonymous-download.json') };
  const overrides = new Map();
  const read = async path => {
    if (!overrides.has(path)) return canonicalRead(path);
    const value = overrides.get(path);
    if (value === null) throw Object.assign(new Error('missing_c41_evidence:' + path), { code: 'ENOENT' });
    return value;
  };
  return { overrides, read, validate: () => validateActivation(image, entry, baseline, read) };
}

test('C41 Grafana activation accepts the actual immutable publication and C36 binary proof', async () => {
  const f = await fixture();
  assert.match(await f.validate(), /^ghcr\.io\/claidd\/otziv-security@sha256:[a-f0-9]{64}$/);
});

for (const [name, path] of [['raw scan', rawPath], ['binary adjudication', receiptPath]]) {
  test(`C41 Grafana activation requires ${name} even when publication summary says PASS`, async () => {
    const f = await fixture();
    f.overrides.set(path, null);
    await assert.rejects(f.validate(), error => error.code === 'ENOENT' && error.message.endsWith(path));
  });
}

test('C41 Grafana rejects a rehashed scan borrowed from another image', async () => {
  const f = await fixture();
  const raw = JSON.parse(await f.read(rawPath));
  const receipt = JSON.parse(await f.read(receiptPath));
  raw.Metadata.ImageID = 'sha256:' + '0'.repeat(64);
  const bytes = encode(raw);
  receipt.rawReportSha256 = hash(bytes);
  f.overrides.set(rawPath, bytes);
  f.overrides.set(receiptPath, encode(receipt));
  await assert.rejects(f.validate(), /activation_grafana_c36_report_image/);
});

test('C41 Grafana rejects a substituted executable hash in the binary proof', async () => {
  const f = await fixture();
  const receipt = JSON.parse(await f.read(receiptPath));
  receipt.binarySha256 = '0'.repeat(64);
  f.overrides.set(receiptPath, encode(receipt));
  await assert.rejects(f.validate(), /activation_grafana_c36_binary/);
});

test('C41 Grafana cannot hide the reviewed findings by rehashing a trimmed scan and receipt', async () => {
  const f = await fixture();
  const raw = JSON.parse(await f.read(rawPath));
  const receipt = JSON.parse(await f.read(receiptPath));
  assert.equal(receipt.decisions.length, 2);
  const reviewed = new Set(receipt.decisions.map(item => item.cve));
  let removed = 0;
  for (const result of raw.Results) {
    result.Vulnerabilities = (result.Vulnerabilities || []).filter(item => {
      if (reviewed.has(item.VulnerabilityID)) { removed++; return false; }
      return true;
    });
  }
  assert.equal(removed, 2);
  const bytes = encode(raw);
  receipt.rawReportSha256 = hash(bytes);
  receipt.decisions = [];
  f.overrides.set(rawPath, bytes);
  f.overrides.set(receiptPath, encode(receipt));
  await assert.rejects(f.validate(), /activation_grafana_c36_scope/);
});
