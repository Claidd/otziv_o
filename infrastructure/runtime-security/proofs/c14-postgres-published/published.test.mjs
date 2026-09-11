import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile,readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import test from 'node:test';
import {createEvidenceReader,validateActivation} from '../../reviewed-image-defaults.mjs';
import {loadPostgresProof,matchingPostgresFindings} from '../../postgres-c14-adjudication.mjs';
import {summarizeReport,TRIVY_IMAGE} from '../../scan.mjs';
import {combinedScanSummary} from '../../scan-verdict.mjs';

const directory=fileURLToPath(new URL('./',import.meta.url));
const root=fileURLToPath(new URL('../../../../',import.meta.url));
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const json=async path=>JSON.parse(await readFile(resolve(directory,path)));
const review=await json('review.json');

test('all retained publication, archive, source and runtime bytes match the explicit proof graph',async()=>{
  assert.equal(review.schema,'otziv-published-postgres-runtime-v1');assert.equal(review.result,'PASS');
  async function list(path,prefix=''){
    let result=[];
    for(const entry of await readdir(path,{withFileTypes:true})){
      const relative=prefix+entry.name;
      result.push(...(entry.isDirectory()?await list(resolve(path,entry.name),relative+'/'):[relative]));
    }
    return result;
  }
  assert.deepEqual((await list(directory)).filter(p=>p!=='review.json').sort(),Object.keys(review.files).sort());
  for(const [path,sha]of Object.entries(review.files))assert.equal(hash(await readFile(resolve(directory,path))),sha,path);
  for(const [path,sha]of Object.entries(review.sourceFiles))assert.equal(hash(await readFile(resolve(root,path))),sha,path);
});

test('the exact two successful jobs and API archive hashes bind the retained publication',async()=>{
  const run=await json('hosted-run.json'),jobs=await json('hosted-jobs.json'),receipts=await json('artifact-verification.json');
  assert.equal(run.id,34239698507);assert.equal(run.head_sha,'e3d798c423eaa7ee1afab9f77c041286e408fefa');
  assert.equal(run.run_attempt,1);assert.equal(run.event,'workflow_dispatch');assert.equal(receipts.length,2);
  for(const [id,name]of [[102106707216,'Publish reviewed candidate (postgres)'],[102112100425,'Prove anonymous candidate download (postgres)']]){
    const rows=jobs.jobs.filter(job=>job.id===id);assert.equal(rows.length,1);
    assert.equal(rows[0].name,name);assert.equal(rows[0].conclusion,'success');assert.equal(rows[0].run_id,run.id);
  }
  for(const receipt of receipts){
    assert.equal(receipt.verified,true);assert.equal(receipt.apiDigest,receipt.actualDigest);
    assert.equal(receipt.commit,run.head_sha);assert.equal(receipt.run,String(run.id));assert.equal(receipt.attempt,'1');
    assert.equal('sha256:'+hash(await readFile(resolve(directory,receipt.name+'.zip'))),receipt.apiDigest);
  }
});

test('existing validator accepts the versioned PostgreSQL publication and full anonymous OCI chain',async()=>{
  const entry=await json('publication-candidate.json');
  const manifest=await readFile(resolve(root,'infrastructure/runtime-security/reviewed-images.json'));
  const image=JSON.parse(manifest).images.find(value=>value.component==='postgres');
  assert.equal(await validateActivation(image,entry,manifest,await createEvidenceReader(root)),review.reference);
  assert.equal(entry.commit,review.commit);assert.equal(entry.run,review.run);assert.equal(entry.attempt,review.attempt);
});

test('all raw published findings remain and exact reviewed decisions reproduce the effective counts',async()=>{
  const bytes=await readFile(resolve(directory,'publication/vulnerabilities.json')),report=JSON.parse(bytes);
  const publication=await json('publication/publication.json'),saved=await json('publication/vulnerabilities.adjudications.json');
  const repeated=await json('repeated-runtime-adjudication.json'),frozen=await loadPostgresProof();
  assert.equal(hash(bytes),saved.rawReportSha256);assert.equal(report.Metadata.ImageID,review.imageConfigDigest);
  assert.equal(saved.rawReportModified,false);assert.equal(repeated.status,'EXACT_RUNTIME_VERIFIED');
  assert.equal(repeated.reviewSha256,frozen.reviewSha256);assert.equal(repeated.verifiedPayloadFiles,1358);
  assert.deepEqual(matchingPostgresFindings(report,frozen.review),repeated.decisions);
  assert.deepEqual(repeated.decisions,saved.postgres.decisions);assert.equal(repeated.decisions.length,37);
  const summary={...combinedScanSummary(summarizeReport(report),{grafana:saved,alloy:saved.alloy,postgres:repeated}),scannerImage:TRIVY_IMAGE};
  assert.deepEqual(summary,publication.security);assert.equal(summary.high,26);assert.equal(summary.critical,1);
  assert.equal(summary.effectiveBlockingFixedHighOrCritical,0);assert.equal(summary.effectiveUnfixedHighOrCritical,0);
  assert.equal(report.Results.flatMap(r=>r.Vulnerabilities??[]).length,152);
  const changed=structuredClone(report);
  changed.Results[0].Vulnerabilities.push({VulnerabilityID:'CVE-2099-99999',PkgName:'unreviewed',InstalledVersion:'1',Severity:'CRITICAL',FixedVersion:'2'});
  const blocked=combinedScanSummary(summarizeReport(changed),{postgres:{...repeated,decisions:matchingPostgresFindings(changed,frozen.review)}});
  assert.equal(blocked.result,'FAIL');assert.equal(blocked.effectiveBlockingFixedHighOrCritical,1);
});

test('the published build reproduces the frozen source context and inspected runtime payload',async()=>{
  const context=await json('publication-source-context.json'),actual=await json('published-image-verification.json');
  assert.equal(context.result,'PASS');assert.equal(context.commit,review.commit);assert.equal(Object.keys(context.files).length,11);
  for(const [path,entry]of Object.entries(context.files)){
    assert.equal(entry.publicationCommitBytesMatch,true);assert.equal(hash(await readFile(resolve(root,path))),entry.sha256,path);
  }
  assert.equal(actual.result,'PASS');assert.equal(actual.reference,review.reference);
  assert.equal(actual.rootfsMatchesRegistryConfig,true);assert.equal(actual.imageLaunchConfigurationMatchesRegistry,true);
  assert.equal(actual.reviewProofFilesValidated,43);assert.equal(actual.reviewedPayloadFiles,1358);
  assert.equal(actual.independentlyReviewedRuntimePayloadIdentical,true);assert.equal(actual.inspectedBinaryExecuted,false);
});

test('actual published PostgreSQL passes bounded continuity and TCP checks with fresh rollback storage',async()=>{
  const continuity=await json('runtime/continuity.json'),tcp=await json('runtime/tcp.json');
  assert.equal(continuity.result,'PASS');assert.equal(continuity.images.candidate.reference,review.reference);
  assert.equal(continuity.checks.length,21);assert.equal(new Set(continuity.checks.map(c=>c.name)).size,21);
  assert.ok(continuity.checks.every(c=>c.passed===true));assert.equal(continuity.cleanup,'PASS');
  assert.equal(continuity.resources.memoryMiB,512);assert.equal(continuity.resources.publishedPorts,0);
  for(const name of ['same_volume_data_schema_privileges_and_xml_preserved','all_41_unicode_order_case_and_820_equality_controls_identical',
    'candidate_restart_preserves_old_and_new_rows','rollback_uses_separate_fresh_volume','original_backup_restores_exact_baseline',
    'new_candidate_backup_restores_to_original_17_10'])assert.ok(continuity.checks.some(c=>c.name===name),name);
  assert.equal(tcp.result,'PASS');assert.equal(tcp.reference,review.reference);assert.equal(tcp.cleanup,'PASS');
  assert.equal(tcp.checks.length,5);assert.ok(tcp.checks.includes('external_tcp_rejects_incorrect_password'));
  assert.equal(tcp.resources.databaseMemoryMiB,512);assert.equal(tcp.resources.clientMemoryMiB,128);assert.equal(tcp.resources.hostPorts,0);
  assert.equal(review.productionChanged,false);assert.equal(review.activationApplied,false);assert.equal(review.vpsMigrationReadinessClaimed,false);
});
