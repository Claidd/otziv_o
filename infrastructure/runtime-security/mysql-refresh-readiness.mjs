import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validateDatabaseTransitionReadiness as original} from './database-transition-readiness.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {buildTriage} from './triage-report.mjs';
export const MYSQL_REFRESH_REFERENCE='ghcr.io/claidd/otziv-security@sha256:d626770288635060a3b150872e75888c2890d7a580392f04c8924a8071bd1a2d';
export const MYSQL_REFRESH_CONFIG='sha256:fda36a52c4993d8ddf65678923f45101db4fa64c7786475bf0ae5b8219345b51';
export const MYSQL_REFRESH_REHEARSAL_SHA256='b008a2de91b4e898c5aab37214c58620bf2115a0f0aaa1542c0d94f609443670';
const ROOT='infrastructure/runtime-security/proofs/c17-mysql/';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');

export async function validateDatabaseTransitionReadiness(entry,image,read){
  if(entry?.reference!==MYSQL_REFRESH_REFERENCE)return original(entry,image,read);
  assert.equal(entry.component,'mysql','mysql_refresh_component');
  const parent=JSON.parse(await read(ROOT+'parent-activation.json'));
  const prior=await original(parent,image,read);
  const ref=entry.databaseTransition;
  assert.equal(ref?.path,ROOT+'rehearsal.json','mysql_refresh_rehearsal_path');
  const bytes=await read(ref.path);
  assert.equal(hash(bytes),ref.sha256,'mysql_refresh_rehearsal_hash');
  assert.equal(ref.sha256,MYSQL_REFRESH_REHEARSAL_SHA256,'mysql_refresh_rehearsal_anchor');
  const rehearsal=JSON.parse(bytes);
  assert.equal(rehearsal.schema,'otziv-mysql-same-engine-refresh-v1','mysql_refresh_schema');
  assert.equal(rehearsal.result,'PASS','mysql_refresh_failed');
  assert.equal(rehearsal.source,prior.reference,'mysql_refresh_source');
  assert.equal(rehearsal.candidate,MYSQL_REFRESH_REFERENCE,'mysql_refresh_candidate');
  assert.ok([MYSQL_REFRESH_CONFIG,MYSQL_REFRESH_REFERENCE.split('@')[1]].includes(rehearsal.candidateLocalId),'mysql_refresh_config');
  for(const key of ['production','sourceWrites'])assert.equal(rehearsal[key],false,'mysql_refresh_production');
  assert.equal(rehearsal.publishedPorts,0,'mysql_refresh_ports');
  assert.equal(rehearsal.network,'none','mysql_refresh_network');
  assert.match(rehearsal.sourceDumpSha256,/^[a-f0-9]{64}$/,'mysql_refresh_dump_hash');
  assert.equal(rehearsal.executedSourceSha256,hash(await read('infrastructure/runtime-security/mysql-refresh-rehearsal.py')),'mysql_refresh_executed_source');
  assert.match(rehearsal.sourceMysqldSha256,/^[a-f0-9]{64}$/,'mysql_refresh_binary_hash');
  assert.equal(rehearsal.sourceMysqldSha256,rehearsal.candidateMysqldSha256,'mysql_refresh_engine_changed');
  assert.equal(rehearsal.candidateOpenSSL,'openssl-libs-3.5.8-1.0.1.el9_8.x86_64','mysql_refresh_openssl');
  assert.deepEqual(rehearsal.before,rehearsal.after,'mysql_refresh_snapshot_changed');
  assert.ok(rehearsal.before.tableCount>100&&rehearsal.before.rows>0,'mysql_refresh_empty_snapshot');
  assert.deepEqual(rehearsal.ownedResourcesRemaining,{containers:0,volumes:0},'mysql_refresh_cleanup');
  assert.ok(rehearsal.checks.every(x=>x.passed===true),'mysql_refresh_failed_check');
  for(const name of ['engine_version_source','engine_version_candidate','server_binary_unchanged','openssl_fixed',
    'all_tables_schema_history_accounts_and_settings_exact','existing_account_login','restart_preserves_new_writes',
    'same_volume_rollback_preserves_all_new_writes','fresh_restore_preserves_all_data_and_schema'])
    assert.equal(rehearsal.checks.filter(x=>x.name===name).length,1,'mysql_refresh_missing_check');
  const publication=JSON.parse(await read(ROOT+'publication/publication.json'));
  assert.equal(publication.reference,MYSQL_REFRESH_REFERENCE,'mysql_refresh_publication_reference');
  assert.equal(publication.imageId,MYSQL_REFRESH_CONFIG,'mysql_refresh_publication_config');
  assert.equal(publication.manifestSet,'c17-mysql','mysql_refresh_publication_set');
  assert.equal(entry.publication.path,ROOT+'publication/publication.json','mysql_refresh_publication_path');
  const rawBytes=await read(ROOT+'publication/vulnerabilities.json'),raw=JSON.parse(rawBytes);
  const receipt=JSON.parse(await read(ROOT+'publication/vulnerabilities.adjudications.json'));
  assert.equal(raw.Metadata.ImageID,MYSQL_REFRESH_CONFIG,'mysql_refresh_scan_image');
  assert.ok(raw.Results.some(x=>x.Class==='os-pkgs'&&x.Packages?.length>0),'mysql_refresh_scan_coverage');
  for(const part of [receipt,receipt.alloy,...(receipt.postgres?[receipt.postgres]:[])]){
    assert.equal(part.status,'NOT_APPLICABLE','mysql_refresh_scan_adjudication');
    assert.deepEqual(part.decisions,[],'mysql_refresh_scan_exemptions');
    assert.equal(part.rawReportSha256,hash(rawBytes),'mysql_refresh_scan_hash');
    assert.equal(part.imageConfigId,MYSQL_REFRESH_CONFIG,'mysql_refresh_receipt_image');
    assert.equal(part.rawReportModified,false,'mysql_refresh_raw_modified');
  }
  const summary=summarizeReport(raw);
  assert.equal(summary.high+summary.critical,0,'mysql_refresh_raw_findings');
  for(const [key,value] of Object.entries(summary))assert.equal(publication.security[key],value,'mysql_refresh_scan_summary');
  assert.equal(publication.security.result,'PASS','mysql_refresh_scan_failed');
  assert.equal(publication.security.scannerImage,TRIVY_IMAGE,'mysql_refresh_scanner');
  assert.deepEqual(JSON.parse(await read(ROOT+'publication/vulnerabilities.triage.json')),buildTriage(raw,rawBytes),'mysql_refresh_triage');
  return {component:'mysql',reference:MYSQL_REFRESH_REFERENCE,proofSha256:ref.sha256,mode:'COORDINATED_CANDIDATE_PREPARATION',refreshType:'SAME_ENGINE_OS_PACKAGES',ordinaryDeploymentUpgradeAuthorized:false};
}
