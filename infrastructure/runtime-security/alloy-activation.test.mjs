import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { validateAlloyReassessment, validateRepositoryDefaults } from './reviewed-image-defaults.mjs';
import { effectiveAlloySummary } from './alloy-daemon-adjudication.mjs';
import { effectiveScanSummary } from './grafana-tempo-adjudication.mjs';
import { summarizeReport, TRIVY_IMAGE } from './scan.mjs';

const root = new URL('../../', import.meta.url);
const manifest = JSON.parse(await readFile(new URL('./reviewed-images.json', import.meta.url)));
const index = JSON.parse(await readFile(new URL('./reviewed-image-activations.json', import.meta.url)));
const image = manifest.images.find(item => item.component === 'alloy');
const actualEntry = index.images.find(item => item.component === 'alloy');
const publication = JSON.parse(await readFile(new URL(actualEntry.publication.path, root)));
const baseline = new Map(await Promise.all(Object.values(actualEntry.securityReassessment).map(async item => [item.path, await readFile(new URL(item.path, root))])));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');

function fixture() {
  const entry = structuredClone(actualEntry), files = new Map(baseline), pub = structuredClone(publication);
  const get = key => JSON.parse(files.get(entry.securityReassessment[key].path));
  const set = (key, value) => {
    const item = entry.securityReassessment[key], bytes = Buffer.from(JSON.stringify(value));
    files.set(item.path, bytes); item.sha256 = hash(bytes);
  };
  const proof = async item => {
    const bytes = files.get(item?.path);
    assert.ok(bytes && hash(bytes) === item.sha256, 'activation_proof_hash_mismatch');
    return { bytes, value: JSON.parse(bytes) };
  };
  return { entry, files, pub, get, set, proof };
}

test('all current activations validate with retained registry bytes; DB preparation never authorizes ordinary upgrade', async () => {
  const result = await validateRepositoryDefaults(fileURLToPath(root));
  assert.equal(result.length, 32);
  for (const row of result.filter(row => ['mysql', 'postgres'].includes(row.component))) {
    if (row.mode === 'EXACT_ORIGINAL_SOURCE') continue;
    assert.equal(row.mode, 'PAIRED_PUBLICATION_AND_ANONYMOUS_EVIDENCE');
    assert.equal(row.databaseTransition, 'COORDINATED_CANDIDATE_PREPARATION');
    assert.equal(row.ordinaryDeploymentUpgradeAuthorized, false);
  }
  assert.ok(result.filter(row => row.component === 'alloy').every(row => row.mode === 'PAIRED_PUBLICATION_AND_ANONYMOUS_EVIDENCE'));
  assert.equal(publication.security.unresolvedRiskReview, 'REQUIRED'); // C7 history was not rewritten.
});

for (const [name, mutate] of Object.entries({
  'missing reassessment': f => delete f.entry.securityReassessment,
  'different published image': f => f.pub.imageId = 'sha256:' + 'f'.repeat(64),
  'different published reference': f => f.pub.reference = f.pub.reference.replace(/.$/, '0'),
  'raw bytes': f => { const r = f.get('report'); r.Metadata.ImageID = 'sha256:' + 'f'.repeat(64); f.set('report', r); },
  'raw rootfs': f => { const r = f.get('report'); r.Metadata.ImageConfig.rootfs.diff_ids = []; f.set('report', r);
    const receipt = f.get('adjudication'); receipt.alloy.rawReportSha256 = f.entry.securityReassessment.report.sha256; f.set('adjudication', receipt); },
  'raw labels': f => { const r = f.get('report'); r.Metadata.ImageConfig.config.Labels['org.opencontainers.image.source'] += '-changed'; f.set('report', r);
    const receipt = f.get('adjudication'); receipt.alloy.rawReportSha256 = f.entry.securityReassessment.report.sha256; f.set('adjudication', receipt); },
  'unpaired raw report': f => { const r = f.get('report'); r.ArtifactName += '-changed'; f.set('report', r); },
  'binary SHA': f => { const r = f.get('adjudication'); r.alloy.binarySha256 = '0'.repeat(64); f.set('adjudication', r); },
  'different review': f => { const r = f.get('adjudication'); r.alloy.reviewSha256 = '0'.repeat(64); f.set('adjudication', r); },
  'expired receipt': f => { const r = f.get('adjudication'); r.alloy.validUntil = '2028-01-01T00:00:00Z'; f.set('adjudication', r); },
  'rejected inspection': f => { const r = f.get('adjudication'); r.alloy.status = 'REJECTED'; f.set('adjudication', r); },
  'broader CVE decision': f => { const r = f.get('adjudication'); r.alloy.decisions[0].cve = 'CVE-2099-00001'; f.set('adjudication', r); },
  'forged summary': f => { const r = f.get('summary'); r.high = 0; f.set('summary', r); }
})) test(`${name} cannot authorize activation even after outer file hashes are refreshed`, async () => {
  const f = fixture(); mutate(f);
  const expected = name === 'raw rootfs' ? /activation_reassessment_report_rootfs/ : name === 'raw labels' ? /activation_reassessment_report_labels/ : /activation_/;
  await assert.rejects(validateAlloyReassessment(image, f.entry, f.pub, f.proof), expected);
});

test('the Alloy review never authorizes another component', async () => {
  const f = fixture();
  await assert.rejects(validateAlloyReassessment({ ...image, component: 'postgres' }, f.entry, f.pub, f.proof), /activation_unresolved/);
});

for (const fixed of [false, true]) test(`new ${fixed ? 'fixed' : 'vendor-unfixed'} finding stays ineligible with internally consistent receipts`, async () => {
  const f = fixture(), r = f.get('report'), receipt = f.get('adjudication');
  r.Results[0].Vulnerabilities.push({ VulnerabilityID: 'CVE-2099-00001', PkgName: 'another-library', InstalledVersion: '1', Severity: 'CRITICAL', ...(fixed ? { FixedVersion: '2' } : {}) });
  f.set('report', r);
  receipt.alloy.rawReportSha256 = f.entry.securityReassessment.report.sha256;
  f.set('adjudication', receipt);
  f.set('summary', { ...effectiveAlloySummary(effectiveScanSummary(summarizeReport(r), null), receipt.alloy), scannerImage: TRIVY_IMAGE });
  await assert.rejects(validateAlloyReassessment(image, f.entry, f.pub, f.proof), /activation_reassessment_security_failed|activation_unresolved_security_review/);
});
