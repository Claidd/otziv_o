import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validateDatabaseTransitionReadiness as previous} from './mysql-c30-readiness.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {buildTriage} from './triage-report.mjs';

export const MYSQL_C37_REFERENCE='ghcr.io/claidd/otziv-security@sha256:eda70c6cb0a835e788a5ec96539610e9ef6a574ffc4129c9c5717acd647e159a';
export const MYSQL_C37_CONFIG='sha256:dcde12d588294e67f7de3a037ec69fd00d0e50c0c4b5d81dc3400545abe298dc';
export const MYSQL_C37_REHEARSAL_SHA256='2058c0cfa4c127d62f2c3d700300a3ecbe5ca6758ceaaf006fbf1bed43264571';
const PARENT_SHA256='fb89c3279005d18b01f24d565cf75094216a484ed246f52fc5662ae795930a53';
const ROOT='infrastructure/runtime-security/proofs/c37-mysql/';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');

export async function validateDatabaseTransitionReadiness(entry,image,read){
  if(entry?.reference!==MYSQL_C37_REFERENCE)return previous(entry,image,read);
  assert.equal(entry.component,'mysql','mysql_c37_component');
  const parentBytes=await read(ROOT+'parent-activation.json');
  assert.equal(hash(parentBytes),PARENT_SHA256,'mysql_c37_parent_changed');
  const prior=await previous(JSON.parse(parentBytes),image,read);
  const ref=entry.databaseTransition;
  assert.equal(ref?.path,ROOT+'rehearsal.json','mysql_c37_rehearsal_path');
  const bytes=await read(ref.path);
  assert.equal(hash(bytes),ref.sha256,'mysql_c37_rehearsal_hash');
  assert.equal(ref.sha256,MYSQL_C37_REHEARSAL_SHA256,'mysql_c37_rehearsal_anchor');
  const rehearsal=JSON.parse(bytes);
  assert.equal(rehearsal.schema,'otziv-mysql-same-engine-refresh-v1','mysql_c37_schema');
  assert.equal(rehearsal.result,'PASS','mysql_c37_failed');
  assert.equal(rehearsal.preliminary,false,'mysql_c37_preliminary');
  assert.equal(rehearsal.source,prior.reference,'mysql_c37_source');
  assert.equal(rehearsal.candidate,MYSQL_C37_REFERENCE,'mysql_c37_candidate');
  assert.ok([MYSQL_C37_CONFIG,MYSQL_C37_REFERENCE.split('@')[1]].includes(rehearsal.candidateLocalId),'mysql_c37_config');
  for(const key of ['production','sourceWrites'])assert.equal(rehearsal[key],false,'mysql_c37_production');
  assert.equal(rehearsal.publishedPorts,0,'mysql_c37_ports');
  assert.equal(rehearsal.network,'none','mysql_c37_network');
  assert.match(rehearsal.sourceDumpSha256,/^[a-f0-9]{64}$/,'mysql_c37_dump_hash');
  assert.equal(rehearsal.executedSourceSha256,hash(await read('infrastructure/runtime-security/mysql-c37-rehearsal.py')),'mysql_c37_executed_source');
  assert.equal(rehearsal.sourceMysqldSha256,'1a928190ad8166bc329b01d4adfba328f4d52083f81569f46c8435710c5e36eb','mysql_c37_engine_binary');
  assert.equal(rehearsal.sourceMysqldSha256,rehearsal.candidateMysqldSha256,'mysql_c37_engine_changed');
  assert.equal(rehearsal.sourceOpenSSL,'openssl-libs-3.5.8-1.0.1.el9_8.x86_64','mysql_c37_source_openssl');
  assert.equal(rehearsal.candidateOpenSSL,'openssl-libs-3.5.8-2.0.1.el9_8.x86_64','mysql_c37_fixed_openssl');
  for(const key of ['Libevent','Libxml2','Pcre2','Pcre2Syntax'])assert.equal(rehearsal['source'+key],rehearsal['candidate'+key],`mysql_c37_unrelated_${key}`);
  assert.deepEqual(rehearsal.sourceCurl,rehearsal.candidateCurl,'mysql_c37_unrelated_curl');
  assert.match(rehearsal.sourceOtherPackagesSha256,/^[a-f0-9]{64}$/,'mysql_c37_package_hash');
  assert.equal(rehearsal.sourceOtherPackagesSha256,rehearsal.candidateOtherPackagesSha256,'mysql_c37_unrelated_packages');
  assert.deepEqual(rehearsal.before,rehearsal.after,'mysql_c37_snapshot_changed');
  assert.ok(rehearsal.before.tableCount>100&&rehearsal.before.rows>0,'mysql_c37_empty_snapshot');
  assert.deepEqual(rehearsal.ownedResourcesRemaining,{containers:0,volumes:0},'mysql_c37_cleanup');
  assert.equal(rehearsal.checks.length,25,'mysql_c37_check_count');
  assert.ok(rehearsal.checks.every(check=>check.passed===true),'mysql_c37_failed_check');
  for(const name of ['engine_version_source','engine_version_candidate','server_binary_unchanged','openssl_source_vulnerable','openssl_fixed',
    'only_openssl_changed','pcre2_preserved','all_tables_schema_history_accounts_and_settings_exact','existing_account_login',
    'restart_preserves_new_writes','same_volume_rollback_preserves_all_new_writes','fresh_restore_preserves_all_data_and_schema'])
    assert.equal(rehearsal.checks.filter(check=>check.name===name).length,1,'mysql_c37_missing_check');
  const publication=JSON.parse(await read(ROOT+'publication/publication.json'));
  assert.equal(publication.reference,MYSQL_C37_REFERENCE,'mysql_c37_publication_reference');
  assert.equal(publication.imageId,MYSQL_C37_CONFIG,'mysql_c37_publication_config');
  assert.equal(publication.manifestSet,'c37-mysql','mysql_c37_publication_set');
  assert.equal(entry.publication.path,ROOT+'publication/publication.json','mysql_c37_publication_path');
  const rawBytes=await read(ROOT+'publication/vulnerabilities.json'),raw=JSON.parse(rawBytes);
  const receipt=JSON.parse(await read(ROOT+'publication/vulnerabilities.adjudications.json'));
  assert.equal(raw.Metadata.ImageID,MYSQL_C37_CONFIG,'mysql_c37_scan_image');
  assert.ok(raw.Results.some(result=>result.Class==='os-pkgs'&&result.Packages?.length>0),'mysql_c37_scan_coverage');
  for(const part of [receipt,receipt.alloy,...(receipt.postgres?[receipt.postgres]:[])]){
    assert.equal(part.status,'NOT_APPLICABLE','mysql_c37_scan_adjudication');
    assert.deepEqual(part.decisions,[],'mysql_c37_scan_exemptions');
    assert.equal(part.rawReportSha256,hash(rawBytes),'mysql_c37_scan_hash');
    assert.equal(part.imageConfigId,MYSQL_C37_CONFIG,'mysql_c37_receipt_image');
    assert.equal(part.rawReportModified,false,'mysql_c37_raw_modified');
  }
  const summary=summarizeReport(raw);
  assert.equal(summary.high+summary.critical,0,'mysql_c37_raw_findings');
  for(const [key,value]of Object.entries(summary))assert.equal(publication.security[key],value,'mysql_c37_scan_summary');
  assert.equal(publication.security.result,'PASS','mysql_c37_scan_failed');
  assert.equal(publication.security.scannerImage,TRIVY_IMAGE,'mysql_c37_scanner');
  assert.deepEqual(JSON.parse(await read(ROOT+'publication/vulnerabilities.triage.json')),buildTriage(raw,rawBytes),'mysql_c37_triage');
  return {component:'mysql',reference:MYSQL_C37_REFERENCE,proofSha256:ref.sha256,mode:'COORDINATED_CANDIDATE_PREPARATION',refreshType:'SAME_ENGINE_OPENSSL_ONLY',ordinaryDeploymentUpgradeAuthorized:false};
}
