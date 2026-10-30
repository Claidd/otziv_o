import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validateDatabaseTransitionReadiness as previous} from './mysql-c42-readiness.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {buildTriage} from './triage-report.mjs';

export const MYSQL_C46_REFERENCE='ghcr.io/claidd/otziv-security@sha256:0ad40a668c292ebe70297cd4df66b2e9c5067fad3bfdb54179347a597e1ac48f';
export const MYSQL_C46_CONFIG='sha256:d6202b12659d22367dfcf315852aaf87a967492ed7601c5f17e6425880c5a069';
export const MYSQL_C46_REHEARSAL_SHA256='677a9cb24dbf1ccadc3cdee49c1de3d2a3593f0284b15a1205d7c38c364743f1';
const PARENT_SHA256='6ca701ab78702f26c04a89e41d888932d4ff2fbf94800c12d2402aba492b8839';
const ROOT='infrastructure/runtime-security/proofs/c46-mysql/';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');

export async function validateDatabaseTransitionReadiness(entry,image,read){
  if(entry?.reference!==MYSQL_C46_REFERENCE)return previous(entry,image,read);
  assert.equal(entry.component,'mysql','mysql_c46_component');
  const parentBytes=await read(ROOT+'parent-activation.json');
  assert.equal(hash(parentBytes),PARENT_SHA256,'mysql_c46_parent_changed');
  const prior=await previous(JSON.parse(parentBytes),image,read);
  const ref=entry.databaseTransition;
  assert.equal(ref?.path,ROOT+'rehearsal.json','mysql_c46_rehearsal_path');
  const bytes=await read(ref.path);
  assert.equal(hash(bytes),ref.sha256,'mysql_c46_rehearsal_hash');
  assert.equal(ref.sha256,MYSQL_C46_REHEARSAL_SHA256,'mysql_c46_rehearsal_anchor');
  const rehearsal=JSON.parse(bytes);
  assert.equal(rehearsal.schema,'otziv-mysql-same-engine-refresh-v1','mysql_c46_schema');
  assert.equal(rehearsal.result,'PASS','mysql_c46_failed');
  assert.equal(rehearsal.preliminary,false,'mysql_c46_preliminary');
  assert.equal(rehearsal.source,prior.reference,'mysql_c46_source');
  assert.equal(rehearsal.candidate,MYSQL_C46_REFERENCE,'mysql_c46_candidate');
  assert.ok([MYSQL_C46_CONFIG,MYSQL_C46_REFERENCE.split('@')[1]].includes(rehearsal.candidateLocalId),'mysql_c46_config');
  for(const key of ['production','sourceWrites'])assert.equal(rehearsal[key],false,'mysql_c46_production');
  assert.equal(rehearsal.publishedPorts,0,'mysql_c46_ports');
  assert.equal(rehearsal.network,'none','mysql_c46_network');
  assert.match(rehearsal.sourceDumpSha256,/^[a-f0-9]{64}$/,'mysql_c46_dump_hash');
  assert.equal(rehearsal.executedSourceSha256,hash(await read('infrastructure/runtime-security/mysql-c46-rehearsal.py')),'mysql_c46_executed_source');
  assert.equal(rehearsal.sourceMysqldSha256,'1a928190ad8166bc329b01d4adfba328f4d52083f81569f46c8435710c5e36eb','mysql_c46_engine_binary');
  assert.equal(rehearsal.sourceMysqldSha256,rehearsal.candidateMysqldSha256,'mysql_c46_engine_changed');
  assert.equal(rehearsal.sourceOpenSSL,'openssl-libs-3.5.8-2.0.1.el9_8.x86_64','mysql_c46_source_openssl');
  assert.equal(rehearsal.candidateOpenSSL,'openssl-libs-3.5.8-2.0.1.el9_8.x86_64','mysql_c46_fixed_openssl');
  assert.equal(rehearsal.sourceLibbrotli,'libbrotli-1.0.9-10.0.1.el9_8.x86_64','mysql_c46_source_brotli');
  assert.equal(rehearsal.candidateLibbrotli,'libbrotli-1.0.9-10.0.1.el9_8.x86_64','mysql_c46_fixed_brotli');
  for(const key of ['Libevent','Libxml2','Pcre2','Pcre2Syntax'])assert.equal(rehearsal['source'+key],rehearsal['candidate'+key],`mysql_c46_unrelated_${key}`);
  assert.deepEqual([...rehearsal.sourceCurl].sort(),['curl-7.76.1-40.el9_8.7.x86_64','libcurl-7.76.1-40.el9_8.7.x86_64'],'mysql_c46_source_curl');
  assert.deepEqual([...rehearsal.candidateCurl].sort(),['curl-7.76.1-40.el9_8.9.x86_64','libcurl-7.76.1-40.el9_8.9.x86_64'],'mysql_c46_fixed_curl');
  assert.match(rehearsal.sourceOtherPackagesSha256,/^[a-f0-9]{64}$/,'mysql_c46_package_hash');
  assert.equal(rehearsal.sourceOtherPackagesSha256,rehearsal.candidateOtherPackagesSha256,'mysql_c46_unrelated_packages');
  assert.deepEqual(rehearsal.before,rehearsal.after,'mysql_c46_snapshot_changed');
  assert.ok(rehearsal.before.tableCount>100&&rehearsal.before.rows>0,'mysql_c46_empty_snapshot');
  assert.deepEqual(rehearsal.ownedResourcesRemaining,{containers:0,volumes:0},'mysql_c46_cleanup');
  assert.equal(rehearsal.checks.length,26,'mysql_c46_check_count');
  assert.ok(rehearsal.checks.every(check=>check.passed===true),'mysql_c46_failed_check');
  for(const name of ['engine_version_source','engine_version_candidate','server_binary_unchanged','curl_source_vulnerable','brotli_preserved','openssl_preserved',
    'only_curl_changed','pcre2_preserved','all_tables_schema_history_accounts_and_settings_exact','existing_account_login',
    'restart_preserves_new_writes','same_volume_rollback_preserves_all_new_writes','fresh_restore_preserves_all_data_and_schema'])
    assert.equal(rehearsal.checks.filter(check=>check.name===name).length,1,'mysql_c46_missing_check');
  const publication=JSON.parse(await read(ROOT+'publication/publication.json'));
  assert.equal(publication.reference,MYSQL_C46_REFERENCE,'mysql_c46_publication_reference');
  assert.equal(publication.imageId,MYSQL_C46_CONFIG,'mysql_c46_publication_config');
  assert.equal(publication.manifestSet,'c46-mysql','mysql_c46_publication_set');
  assert.equal(entry.publication.path,ROOT+'publication/publication.json','mysql_c46_publication_path');
  const rawBytes=await read(ROOT+'publication/vulnerabilities.json'),raw=JSON.parse(rawBytes);
  const receipt=JSON.parse(await read(ROOT+'publication/vulnerabilities.adjudications.json'));
  assert.equal(raw.Metadata.ImageID,MYSQL_C46_CONFIG,'mysql_c46_scan_image');
  assert.ok(raw.Results.some(result=>result.Class==='os-pkgs'&&result.Packages?.length>0),'mysql_c46_scan_coverage');
  for(const part of [receipt,receipt.alloy,...(receipt.postgres?[receipt.postgres]:[])]){
    assert.equal(part.status,'NOT_APPLICABLE','mysql_c46_scan_adjudication');
    assert.deepEqual(part.decisions,[],'mysql_c46_scan_exemptions');
    assert.equal(part.rawReportSha256,hash(rawBytes),'mysql_c46_scan_hash');
    assert.equal(part.imageConfigId,MYSQL_C46_CONFIG,'mysql_c46_receipt_image');
    assert.equal(part.rawReportModified,false,'mysql_c46_raw_modified');
  }
  const summary=summarizeReport(raw);
  assert.equal(summary.high+summary.critical,0,'mysql_c46_raw_findings');
  for(const [key,value]of Object.entries(summary))assert.equal(publication.security[key],value,'mysql_c46_scan_summary');
  assert.equal(publication.security.result,'PASS','mysql_c46_scan_failed');
  assert.equal(publication.security.scannerImage,TRIVY_IMAGE,'mysql_c46_scanner');
  assert.deepEqual(JSON.parse(await read(ROOT+'publication/vulnerabilities.triage.json')),buildTriage(raw,rawBytes),'mysql_c46_triage');
  return {component:'mysql',reference:MYSQL_C46_REFERENCE,proofSha256:ref.sha256,mode:'COORDINATED_CANDIDATE_PREPARATION',refreshType:'SAME_ENGINE_CURL_ONLY',ordinaryDeploymentUpgradeAuthorized:false};
}
