import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
import test from 'node:test';
import {validatePostgresActivationScan} from './postgres-activation-proof.mjs';
import {validateActivation} from './reviewed-image-defaults.mjs';
import {summarizeReport} from './scan.mjs';
import {buildTriage} from './triage-report.mjs';

const root=fileURLToPath(new URL('../../',import.meta.url));
const prefix='infrastructure/runtime-security/proofs/c14-postgres-published/';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const encode=value=>Buffer.from(JSON.stringify(value));
const baseline=await readFile(resolve(root,'infrastructure/runtime-security/reviewed-images.json'));
const image=JSON.parse(baseline).images.find(value=>value.component==='postgres');

async function fixture(){
  const overrides=new Map(),calls=[];
  const read=async path=>{
    calls.push(path);
    if(overrides.has(path)){
      assert.notEqual(overrides.get(path),null,'deliberately_missing_postgres_evidence');
      return overrides.get(path);
    }
    return readFile(resolve(root,path));
  };
  const json=async path=>JSON.parse(await read(path));
  const entry=await json(prefix+'publication-candidate.json');
  const paths={publication:entry.publication.path,raw:prefix+'publication/vulnerabilities.json',
    receipt:prefix+'publication/vulnerabilities.adjudications.json',triage:prefix+'publication/vulnerabilities.triage.json',
    anchor:prefix+'review.json',inspection:prefix+'published-image-verification.json',replay:prefix+'repeated-runtime-adjudication.json'};
  const publication=await json(paths.publication),raw=await json(paths.raw),receipt=await json(paths.receipt),triage=await json(paths.triage);
  function resealScan(){
    const bytes=encode(raw);overrides.set(paths.raw,bytes);
    receipt.rawReportSha256=hash(bytes);receipt.alloy.rawReportSha256=hash(bytes);receipt.postgres.rawReportSha256=hash(bytes);
    Object.assign(triage,buildTriage(raw,bytes));overrides.set(paths.receipt,encode(receipt));overrides.set(paths.triage,encode(triage));
  }
  async function resealPublication(){
    const bytes=encode(publication);overrides.set(paths.publication,bytes);entry.publication.sha256=hash(bytes);
    const anonymous=await json(entry.anonymous.path);anonymous.sourcePublicationSha256=hash(bytes);
    const anonBytes=encode(anonymous);overrides.set(entry.anonymous.path,anonBytes);entry.anonymous.sha256=hash(anonBytes);
  }
  const validate=()=>validatePostgresActivationScan(publication,entry.publication.path,read);
  calls.length=0;
  return {read,json,overrides,calls,entry,publication,raw,receipt,triage,paths,resealScan,resealPublication,validate};
}

test('actual published PostgreSQL passes complete static raw and payload gates without Docker',async()=>{
  const f=await fixture(),result=await f.validate();
  assert.equal(result.result,'PASS');assert.equal(result.reference,f.publication.reference);
  assert.equal(result.imageConfigId,f.publication.imageId);assert.equal(result.effectiveBlockingFixedHighOrCritical,0);
  assert.equal(result.effectiveUnfixedHighOrCritical,0);assert.equal(result.databaseTransitionAuthorized,false);
  for(const path of Object.values(f.paths))assert.ok(f.calls.includes(path),path);
  assert.equal(new Set(f.calls).size,f.calls.length,'evidence is read once per validation');
});

test('actual new versioned manifest and anonymous pair also pass the existing full OCI validator',async()=>{
  const f=await fixture();
  assert.equal(f.entry.manifest.path,'infrastructure/runtime-security/reviewed-images-c14-postgres.json');
  assert.equal(await validateActivation(image,f.entry,baseline,f.read),f.publication.reference);
  assert.equal((await f.validate()).result,'PASS');
});

for(const name of ['raw','receipt','triage','anchor','inspection','replay'])test('missing '+name+' cannot reuse a successful publication',async()=>{
  const f=await fixture();f.overrides.set(f.paths[name],null);
  await assert.rejects(f.validate(),/deliberately_missing_postgres_evidence/);
});

test('raw bytes cannot change under the retained scanner hash',async()=>{
  const f=await fixture();f.overrides.set(f.paths.raw,Buffer.concat([await f.read(f.paths.raw),Buffer.from('\n')]));
  await assert.rejects(f.validate(),/postgres_activation_raw_hash/);
});

test('a new unknown fixed CVE remains blocking even after recounting and resealing all scan hashes',async()=>{
  const f=await fixture();
  f.raw.Results[0].Vulnerabilities.push({VulnerabilityID:'CVE-2099-99999',PkgName:'synthetic-unreviewed',InstalledVersion:'1',Severity:'CRITICAL',FixedVersion:'2'});
  Object.assign(f.publication.security,summarizeReport(f.raw));f.resealScan();await f.resealPublication();
  await assert.rejects(f.validate(),/postgres_activation_unresolved_findings/);
});

test('an unknown unfixed CVE cannot hide behind the previous zero effective verdict',async()=>{
  const f=await fixture();
  f.raw.Results[0].Vulnerabilities.push({VulnerabilityID:'CVE-2099-99998',PkgName:'synthetic-unreviewed',InstalledVersion:'1',Severity:'HIGH'});
  Object.assign(f.publication.security,summarizeReport(f.raw));f.resealScan();
  await assert.rejects(f.validate(),/postgres_activation_unresolved_findings/);
});

test('a raw count mismatch is rejected even when all report hash receipts agree',async()=>{
  const f=await fixture();f.publication.security.high=0;await f.resealPublication();
  await assert.rejects(f.validate(),/postgres_activation_raw_count_high/);
});

test('effective counters are independently derived and cannot be replaced by another PASS summary',async()=>{
  const f=await fixture();f.publication.security.adjudicatedPostgresFixedHighOrCritical=16;await f.resealPublication();
  await assert.rejects(f.validate(),/postgres_activation_effective_summary/);
});

test('wrong raw image config is rejected with internally consistent report and triage hashes',async()=>{
  const f=await fixture();f.raw.Metadata.ImageID='sha256:'+'a'.repeat(64);f.resealScan();
  await assert.rejects(f.validate(),/postgres_activation_raw_image/);
});

for(const [name,mutate,error]of [
  ['receipt image',f=>{f.receipt.imageConfigId='sha256:'+'a'.repeat(64);},/postgres_activation_receipt_image/],
  ['receipt inspected image',f=>{f.receipt.immutableImageId='sha256:'+'b'.repeat(64);},/postgres_activation_inspected_image/],
  ['PostgreSQL inspected image',f=>{f.receipt.postgres.immutableImageId='sha256:'+'c'.repeat(64);},/postgres_activation_adjudication_inspected_image/],
  ['PostgreSQL image',f=>{f.receipt.postgres.imageConfigId='sha256:'+'d'.repeat(64);},/postgres_activation_adjudication_image/],
  ['rejected PostgreSQL inspection',f=>{f.receipt.postgres.status='REJECTED';f.receipt.postgres.decisions=[];},/postgres_activation_adjudication_rejected/],
  ['unverified PostgreSQL inspection',f=>{f.receipt.postgres.status='NOT_APPLICABLE';f.receipt.postgres.decisions=[];},/postgres_activation_adjudication_rejected/],
  ['extended expiry',f=>{f.receipt.postgres.validUntil='2030-01-01T00:00:00Z';},/postgres_activation_review_expiry/],
  ['different source review',f=>{f.receipt.postgres.reviewSha256='0'.repeat(64);},/postgres_activation_source_review/],
  ['raw modification assertion',f=>{f.receipt.postgres.rawReportModified=true;},/postgres_activation_adjudication_modified/],
  ['foreign subtraction',f=>{f.receipt.alloy.status='EXACT_BINARY_AFFECTED_CODE_ABSENT';},/postgres_activation_foreign_adjudication/],
])test(name+' cannot be substituted into a PG activation',async()=>{
  const f=await fixture();mutate(f);f.overrides.set(f.paths.receipt,encode(f.receipt));
  await assert.rejects(f.validate(),error);
});

test('expired frozen review cannot authorize an otherwise unchanged published image',async t=>{
  t.mock.timers.enable({apis:['Date'],now:new Date('2027-01-01T00:00:00Z')});
  const f=await fixture();await assert.rejects(f.validate(),/postgres_review_expired/);
});

test('invented, dropped, duplicated or repositioned decisions fail exact recomputation',async()=>{
  for(const mutate of [
    decisions=>decisions.push({...decisions[0],cve:'CVE-2099-99999'}),
    decisions=>decisions.pop(),
    decisions=>decisions.push({...decisions[0]}),
    decisions=>{decisions[0].findingIndex+=1;},
    decisions=>{decisions[0].fixedHighOrCritical=true;decisions[0].unfixedHighOrCritical=false;},
  ]){
    const f=await fixture();mutate(f.receipt.postgres.decisions);f.overrides.set(f.paths.receipt,encode(f.receipt));
    await assert.rejects(f.validate(),/postgres_activation_exact_decisions/);
  }
});

test('changed package source/version cannot retain decisions by preserving the displayed CVE',async()=>{
  const f=await fixture();const finding=f.raw.Results[0].Vulnerabilities.find(item=>item.PkgName==='otziv-postgresql-17');
  finding.InstalledVersion='17.12';f.resealScan();
  await assert.rejects(f.validate(),/postgres_activation_exact_decisions/);
});

test('triage findings cannot omit a HIGH row or imply accepted risk',async()=>{
  for(const mutate of [triage=>triage.findings.pop(),triage=>{triage.findings[0].riskAcceptance='accepted';}]){
    const f=await fixture();mutate(f.triage);f.overrides.set(f.paths.triage,encode(f.triage));
    await assert.rejects(f.validate(),/postgres_activation_triage_findings/);
  }
});

test('a wrong triage config is not repaired by a correct raw SHA',async()=>{
  const f=await fixture();f.triage.imageId='sha256:'+'e'.repeat(64);f.overrides.set(f.paths.triage,encode(f.triage));
  await assert.rejects(f.validate(),/postgres_activation_triage_image/);
});

test('a forged successful payload-inspection receipt fails its pinned evidence bytes',async()=>{
  const f=await fixture(),inspection=await f.json(f.paths.inspection);
  inspection.result='PASS';inspection.runtimeIdentitySha256='f'.repeat(64);f.overrides.set(f.paths.inspection,encode(inspection));
  await assert.rejects(f.validate(),/postgres_activation_published_bytes_published-image-verification/);
});

test('rehashing an outer proof cannot manufacture a successful inspection or another image',async()=>{
  const f=await fixture(),inspection=await f.json(f.paths.inspection),outer=await f.json(f.paths.anchor);
  inspection.runtimeIdentitySha256='0'.repeat(64);inspection.result='PASS';
  const bytes=encode(inspection);f.overrides.set(f.paths.inspection,bytes);
  outer.files['published-image-verification.json']=hash(bytes);outer.result='PASS';f.overrides.set(f.paths.anchor,encode(outer));
  await assert.rejects(f.validate(),/postgres_activation_published_proof_anchor/);
});

test('another published digest cannot borrow the same runtime PASS and source review',async()=>{
  const f=await fixture();f.publication.reference='ghcr.io/claidd/otziv-security@sha256:'+'a'.repeat(64);
  await f.resealPublication();await assert.rejects(f.validate(),/postgres_activation_published_identity/);
});

test('a resealed publication/anonymous pair cannot borrow an unrelated scanner result',async()=>{
  const f=await fixture();f.publication.security.high=1;await f.resealPublication();
  // Keep this causal check valid both before and after the coordinator inserts
  // the mandatory scan call into validateActivation. Rehashed outer receipts
  // never authorize the inconsistent raw counters at the combined boundary.
  await assert.rejects(async()=>{
    await validateActivation(image,f.entry,baseline,f.read);
    await f.validate();
  },/postgres_activation_raw_count_high/);
});

test('the baseline or another versioned manifest cannot masquerade as the new reviewed PG pair',async()=>{
  for(const mutate of [p=>{delete p.manifestSet;delete p.manifestPath;},p=>{p.manifestSet='c14-mc';},p=>{p.manifestPath='infrastructure/runtime-security/reviewed-images.json';}]){
    const f=await fixture();mutate(f.publication);await assert.rejects(f.validate(),/publication_manifest_/);
  }
});

test('unreviewed publication paths and components are rejected before evidence reads',async()=>{
  const f=await fixture();
  await assert.rejects(validatePostgresActivationScan(f.publication,'../publication.json',f.read),/postgres_activation_publication_path/);
  await assert.rejects(validatePostgresActivationScan({...f.publication,component:'mysql'},f.entry.publication.path,f.read),/postgres_activation_component/);
  assert.equal(f.calls.length,0);
});

test('changed build inputs and frozen verifier source invalidate the reviewed published context',async()=>{
  for(const path of ['infrastructure/runtime-security/builds/postgres-c14/Dockerfile','infrastructure/runtime-security/postgres-c14-adjudication.mjs']){
    const f=await fixture();f.overrides.set(path,Buffer.concat([await f.read(path),Buffer.from('\n')]));
    await assert.rejects(f.validate(),/postgres_activation_source_bytes_/);
  }
});
