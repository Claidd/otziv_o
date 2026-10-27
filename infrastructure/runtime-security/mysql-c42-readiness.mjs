import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validateDatabaseTransitionReadiness as previous} from './mysql-c37-readiness.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {buildTriage} from './triage-report.mjs';

export const MYSQL_C42_REFERENCE='ghcr.io/claidd/otziv-security@sha256:d8b2c5d02611016eee74b77caede5c57a16cd8a1f379bf5c74d322bfd1b5f75d';
export const MYSQL_C42_CONFIG='sha256:b8b1aab82af45b783263e514e2f9746959de78d3a963245cf386720a32b8564f';
export const MYSQL_C42_REHEARSAL_SHA256='6b91b594468f820309b0971d1a3854cd8d421c3a83724ba928822d4c0763ece8';
const PARENT_SHA256='e6df75d221fa66b055f19598b5b03855e23396e7f908e09a502f293edb8d493e';
const ROOT='infrastructure/runtime-security/proofs/c42-mysql/';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');

export async function validateDatabaseTransitionReadiness(entry,image,read){
  if(entry?.reference!==MYSQL_C42_REFERENCE)return previous(entry,image,read);
  assert.equal(entry.component,'mysql','mysql_c42_component');
  const parentBytes=await read(ROOT+'parent-activation.json');
  assert.equal(hash(parentBytes),PARENT_SHA256,'mysql_c42_parent_changed');
  const prior=await previous(JSON.parse(parentBytes),image,read);
  const ref=entry.databaseTransition;
  assert.equal(ref?.path,ROOT+'rehearsal.json','mysql_c42_rehearsal_path');
  const bytes=await read(ref.path);
  assert.equal(hash(bytes),ref.sha256,'mysql_c42_rehearsal_hash');
  assert.equal(ref.sha256,MYSQL_C42_REHEARSAL_SHA256,'mysql_c42_rehearsal_anchor');
  const rehearsal=JSON.parse(bytes);
  assert.equal(rehearsal.schema,'otziv-mysql-same-engine-refresh-v1','mysql_c42_schema');
  assert.equal(rehearsal.result,'PASS','mysql_c42_failed');
  assert.equal(rehearsal.preliminary,false,'mysql_c42_preliminary');
  assert.equal(rehearsal.source,prior.reference,'mysql_c42_source');
  assert.equal(rehearsal.candidate,MYSQL_C42_REFERENCE,'mysql_c42_candidate');
  assert.ok([MYSQL_C42_CONFIG,MYSQL_C42_REFERENCE.split('@')[1]].includes(rehearsal.candidateLocalId),'mysql_c42_config');
  for(const key of ['production','sourceWrites'])assert.equal(rehearsal[key],false,'mysql_c42_production');
  assert.equal(rehearsal.publishedPorts,0,'mysql_c42_ports');
  assert.equal(rehearsal.network,'none','mysql_c42_network');
  assert.match(rehearsal.sourceDumpSha256,/^[a-f0-9]{64}$/,'mysql_c42_dump_hash');
  assert.equal(rehearsal.executedSourceSha256,hash(await read('infrastructure/runtime-security/mysql-c42-rehearsal.py')),'mysql_c42_executed_source');
  assert.equal(rehearsal.sourceMysqldSha256,'1a928190ad8166bc329b01d4adfba328f4d52083f81569f46c8435710c5e36eb','mysql_c42_engine_binary');
  assert.equal(rehearsal.sourceMysqldSha256,rehearsal.candidateMysqldSha256,'mysql_c42_engine_changed');
  assert.equal(rehearsal.sourceOpenSSL,'openssl-libs-3.5.8-2.0.1.el9_8.x86_64','mysql_c42_source_openssl');
  assert.equal(rehearsal.candidateOpenSSL,'openssl-libs-3.5.8-2.0.1.el9_8.x86_64','mysql_c42_fixed_openssl');
  assert.equal(rehearsal.sourceLibbrotli,'libbrotli-1.0.9-9.el9_7.x86_64','mysql_c42_source_brotli');
  assert.equal(rehearsal.candidateLibbrotli,'libbrotli-1.0.9-10.0.1.el9_8.x86_64','mysql_c42_fixed_brotli');
  for(const key of ['Libevent','Libxml2','Pcre2','Pcre2Syntax'])assert.equal(rehearsal['source'+key],rehearsal['candidate'+key],`mysql_c42_unrelated_${key}`);
  assert.deepEqual(rehearsal.sourceCurl,rehearsal.candidateCurl,'mysql_c42_unrelated_curl');
  assert.match(rehearsal.sourceOtherPackagesSha256,/^[a-f0-9]{64}$/,'mysql_c42_package_hash');
  assert.equal(rehearsal.sourceOtherPackagesSha256,rehearsal.candidateOtherPackagesSha256,'mysql_c42_unrelated_packages');
  assert.deepEqual(rehearsal.before,rehearsal.after,'mysql_c42_snapshot_changed');
  assert.ok(rehearsal.before.tableCount>100&&rehearsal.before.rows>0,'mysql_c42_empty_snapshot');
  assert.deepEqual(rehearsal.ownedResourcesRemaining,{containers:0,volumes:0},'mysql_c42_cleanup');
  assert.equal(rehearsal.checks.length,26,'mysql_c42_check_count');
  assert.ok(rehearsal.checks.every(check=>check.passed===true),'mysql_c42_failed_check');
  for(const name of ['engine_version_source','engine_version_candidate','server_binary_unchanged','brotli_source_vulnerable','brotli_fixed','openssl_preserved',
    'only_brotli_changed','pcre2_preserved','all_tables_schema_history_accounts_and_settings_exact','existing_account_login',
    'restart_preserves_new_writes','same_volume_rollback_preserves_all_new_writes','fresh_restore_preserves_all_data_and_schema'])
    assert.equal(rehearsal.checks.filter(check=>check.name===name).length,1,'mysql_c42_missing_check');
  const publication=JSON.parse(await read(ROOT+'publication/publication.json'));
  assert.equal(publication.reference,MYSQL_C42_REFERENCE,'mysql_c42_publication_reference');
  assert.equal(publication.imageId,MYSQL_C42_CONFIG,'mysql_c42_publication_config');
  assert.equal(publication.manifestSet,'c42-mysql','mysql_c42_publication_set');
  assert.equal(entry.publication.path,ROOT+'publication/publication.json','mysql_c42_publication_path');
  const rawBytes=await read(ROOT+'publication/vulnerabilities.json'),raw=JSON.parse(rawBytes);
  const receipt=JSON.parse(await read(ROOT+'publication/vulnerabilities.adjudications.json'));
  assert.equal(raw.Metadata.ImageID,MYSQL_C42_CONFIG,'mysql_c42_scan_image');
  assert.ok(raw.Results.some(result=>result.Class==='os-pkgs'&&result.Packages?.length>0),'mysql_c42_scan_coverage');
  for(const part of [receipt,receipt.alloy,...(receipt.postgres?[receipt.postgres]:[])]){
    assert.equal(part.status,'NOT_APPLICABLE','mysql_c42_scan_adjudication');
    assert.deepEqual(part.decisions,[],'mysql_c42_scan_exemptions');
    assert.equal(part.rawReportSha256,hash(rawBytes),'mysql_c42_scan_hash');
    assert.equal(part.imageConfigId,MYSQL_C42_CONFIG,'mysql_c42_receipt_image');
    assert.equal(part.rawReportModified,false,'mysql_c42_raw_modified');
  }
  const summary=summarizeReport(raw);
  assert.equal(summary.high+summary.critical,0,'mysql_c42_raw_findings');
  for(const [key,value]of Object.entries(summary))assert.equal(publication.security[key],value,'mysql_c42_scan_summary');
  assert.equal(publication.security.result,'PASS','mysql_c42_scan_failed');
  assert.equal(publication.security.scannerImage,TRIVY_IMAGE,'mysql_c42_scanner');
  assert.deepEqual(JSON.parse(await read(ROOT+'publication/vulnerabilities.triage.json')),buildTriage(raw,rawBytes),'mysql_c42_triage');
  return {component:'mysql',reference:MYSQL_C42_REFERENCE,proofSha256:ref.sha256,mode:'COORDINATED_CANDIDATE_PREPARATION',refreshType:'SAME_ENGINE_BROTLI_ONLY',ordinaryDeploymentUpgradeAuthorized:false};
}
