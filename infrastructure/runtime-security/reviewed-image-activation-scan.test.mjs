import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { validateActivation, validateUnadjudicatedActivationScan } from './reviewed-image-defaults.mjs';
import { supplementalReviewedSources } from './reviewed-image-sets.mjs';
import { summarizeReport } from './scan.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const jsonBytes = value => Buffer.from(JSON.stringify(value));
const baseline = await readFile(resolve(root, 'infrastructure/runtime-security/reviewed-images.json'));
const index = JSON.parse(await readFile(resolve(root, 'infrastructure/runtime-security/reviewed-image-activations.json')));
const registered = index.images.find(entry => entry.component === 'mc');
assert.ok(registered, 'actual_mc_activation_required');
const image = supplementalReviewedSources().find(image => image.component === 'mc');

async function fixture() {
  const entry = structuredClone(registered), overrides = new Map(), calls = [];
  const directory = dirname(entry.publication.path).replaceAll('\\', '/');
  const paths = { raw: directory + '/vulnerabilities.json', receipt: directory + '/vulnerabilities.adjudications.json',
    triage: directory + '/vulnerabilities.triage.json' };
  const read = async path => {
    calls.push(path);
    if (overrides.has(path)) {
      assert.notEqual(overrides.get(path), null, 'deliberately_missing_raw_evidence');
      return overrides.get(path);
    }
    return readFile(resolve(root, path));
  };
  const publication = JSON.parse(await read(entry.publication.path));
  const raw = JSON.parse(await read(paths.raw)), receipt = JSON.parse(await read(paths.receipt)), triage = JSON.parse(await read(paths.triage));
  const resealScan = () => {
    overrides.set(paths.raw, jsonBytes(raw));
    receipt.rawReportSha256 = hash(overrides.get(paths.raw)); triage.reportSHA256 = receipt.rawReportSha256;
    overrides.set(paths.receipt, jsonBytes(receipt)); overrides.set(paths.triage, jsonBytes(triage));
  };
  const resealPublication = async () => {
    const bytes = jsonBytes(publication); overrides.set(entry.publication.path, bytes); entry.publication.sha256 = hash(bytes);
    const anonymous = JSON.parse(await read(entry.anonymous.path)); anonymous.sourcePublicationSha256 = hash(bytes);
    const anon = jsonBytes(anonymous); overrides.set(entry.anonymous.path, anon); entry.anonymous.sha256 = hash(anon);
  };
  calls.length = 0;
  return { entry, publication, raw, receipt, triage, paths, read, calls, overrides, resealScan, resealPublication,
    validate: () => validateActivation(image, entry, baseline, read),
    validateScan: () => validateUnadjudicatedActivationScan(publication, entry.publication.path, read) };
}

test('actual immutable MC activation passes with its retained raw report, scanner receipts and OCI pair', async () => {
  const value = await fixture();
  assert.equal(await value.validate(), registered.reference);
  for (const path of Object.values(value.paths)) assert.ok(value.calls.includes(path), path);
});

for (const name of ['raw', 'receipt', 'triage']) test('missing ' + name + ' cannot reuse a PASS publication summary', async () => {
  const value = await fixture(); value.overrides.set(value.paths[name], null);
  await assert.rejects(value.validate(), /deliberately_missing_raw_evidence/);
});

test('raw byte changes fail the retained scanner hash even when parsed findings are unchanged', async () => {
  const value = await fixture();
  value.overrides.set(value.paths.raw, Buffer.concat([await value.read(value.paths.raw), Buffer.from('\n')]));
  await assert.rejects(value.validate(), /activation_raw_scan_hash/);
});

test('changed raw counts fail a zero summary even if both scanner hash receipts are resealed', async () => {
  const value = await fixture();
  value.raw.Results[0].Vulnerabilities.push({ VulnerabilityID: 'CVE-2099-0001', Severity: 'HIGH', PkgName: 'synthetic', FixedVersion: '2' });
  value.resealScan();
  await assert.rejects(value.validate(), /activation_raw_scan_summary_high/);
});

test('a matching recounted HIGH summary still cannot activate a candidate with unadjudicated findings', async () => {
  const value = await fixture();
  value.raw.Results[0].Vulnerabilities.push({ VulnerabilityID: 'CVE-2099-0001', Severity: 'CRITICAL', PkgName: 'synthetic' });
  Object.assign(value.publication.security, summarizeReport(value.raw));
  value.resealScan(); await value.resealPublication();
  await assert.rejects(value.validate(), /activation_raw_scan_findings/);
});

test('swapped raw image config fails even when its scanner hashes are internally consistent', async () => {
  const value = await fixture(); value.raw.Metadata.ImageID = 'sha256:' + 'a'.repeat(64); value.resealScan();
  await assert.rejects(value.validate(), /activation_raw_scan_image/);
});

for (const [name, mutate, expected] of [
  ['scanner config', v => { v.receipt.imageConfigId = 'sha256:' + 'a'.repeat(64); }, /receipt_image/],
  ['inspected image', v => { v.receipt.immutableImageId = 'sha256:' + 'a'.repeat(64); }, /inspected_image/],
  ['triage config', v => { v.triage.imageId = 'sha256:' + 'a'.repeat(64); }, /triage_image/],
  ['adjudication', v => { v.receipt.status = 'EXACT_BINARY_AFFECTED_CODE_ABSENT'; }, /adjudication/],
  ['dropped Go coverage', v => { v.raw.Results = v.raw.Results.filter(r => r.Type !== 'gobinary'); }, /coverage/],
]) test(name + ' cannot be substituted into an existing activation', async () => {
  const value = await fixture(); mutate(value); value.resealScan();
  await assert.rejects(value.validate(), expected);
});

test('local S3 uses the same mandatory raw scanner contract without changing historical publication bytes', async () => {
  const value = await fixture(); value.publication.component = 'minio';
  await value.validateScan();
  value.raw.Metadata.ImageID = 'sha256:' + 'b'.repeat(64); value.resealScan();
  await assert.rejects(value.validateScan(), /activation_raw_scan_image/);
});
