import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validateDatabaseTransitionReadiness as previous} from './mysql-c56-readiness.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {buildTriage} from './triage-report.mjs';

export const MYSQL_C57_REFERENCE='ghcr.io/claidd/otziv-security@sha256:52be6ed6c0039196dca88202ffd9714520d509bdde9b2f575238d1ea2d81028a';
export const MYSQL_C57_CONFIG='sha256:f538c100de0adc3851ce7ec254a008cd03caea4ceb8b620331433bb5dc15c7e7';
export const MYSQL_C57_REHEARSAL_SHA256='de3caa72360f065886ddb645372229dfab7adae45bf05cd6468ea1e570c1f076';
const PARENT_SHA256='ed7606767c76cce17449fd06d9afca30210e71c3f89a2b2e3a1f51cbc119e668';
const ROOT='infrastructure/runtime-security/proofs/c57-mysql/';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');

export async function validateDatabaseTransitionReadiness(entry,image,read){
  if(entry?.reference!==MYSQL_C57_REFERENCE)return previous(entry,image,read);
  assert.equal(entry.component,'mysql','mysql_c57_component');
  const parentBytes=await read(ROOT+'parent-activation.json');
  assert.equal(hash(parentBytes),PARENT_SHA256,'mysql_c57_parent_changed');
  const prior=await previous(JSON.parse(parentBytes),image,read);
  const ref=entry.databaseTransition;
  assert.equal(ref?.path,ROOT+'rehearsal.json','mysql_c57_rehearsal_path');
  const bytes=await read(ref.path);
  assert.equal(hash(bytes),ref.sha256,'mysql_c57_rehearsal_hash');
  assert.equal(ref.sha256,MYSQL_C57_REHEARSAL_SHA256,'mysql_c57_rehearsal_anchor');
  const rehearsal=JSON.parse(bytes);
  assert.equal(rehearsal.schema,'otziv-mysql-same-engine-refresh-v1','mysql_c57_schema');
  assert.equal(rehearsal.result,'PASS','mysql_c57_failed');
  assert.equal(rehearsal.preliminary,false,'mysql_c57_preliminary');
  assert.equal(rehearsal.source,prior.reference,'mysql_c57_source');
  assert.equal(rehearsal.candidate,MYSQL_C57_REFERENCE,'mysql_c57_candidate');
  assert.ok([MYSQL_C57_CONFIG,MYSQL_C57_REFERENCE.split('@')[1]].includes(rehearsal.candidateLocalId),'mysql_c57_config');
  for(const key of ['production','sourceWrites'])assert.equal(rehearsal[key],false,'mysql_c57_production');
  assert.equal(rehearsal.publishedPorts,0,'mysql_c57_ports');
  assert.equal(rehearsal.network,'none','mysql_c57_network');
  assert.match(rehearsal.sourceDumpSha256,/^[a-f0-9]{64}$/,'mysql_c57_dump_hash');
  assert.equal(rehearsal.executedSourceSha256,hash(await read('infrastructure/runtime-security/mysql-c57-rehearsal.py')),'mysql_c57_executed_source');
  assert.equal(rehearsal.sourceMysqldSha256,'1a928190ad8166bc329b01d4adfba328f4d52083f81569f46c8435710c5e36eb','mysql_c57_engine_binary');
  assert.equal(rehearsal.sourceMysqldSha256,rehearsal.candidateMysqldSha256,'mysql_c57_engine_changed');
  assert.equal(rehearsal.sourceOpenSSL,'openssl-libs-3.5.8-2.0.1.el9_8.x86_64','mysql_c57_source_openssl');
  assert.equal(rehearsal.candidateOpenSSL,'openssl-libs-3.5.8-2.0.1.el9_8.x86_64','mysql_c57_fixed_openssl');
  assert.equal(rehearsal.sourceLibbrotli,'libbrotli-1.0.9-10.0.1.el9_8.x86_64','mysql_c57_source_brotli');
  assert.equal(rehearsal.candidateLibbrotli,'libbrotli-1.0.9-10.0.1.el9_8.x86_64','mysql_c57_fixed_brotli');
  for(const key of ['Libevent','Libxml2','Pcre2','Pcre2Syntax'])assert.equal(rehearsal['source'+key],rehearsal['candidate'+key],`mysql_c57_unrelated_${key}`);
  assert.deepEqual([...rehearsal.sourceCurl].sort(),['curl-7.76.1-40.el9_8.9.x86_64','libcurl-7.76.1-40.el9_8.9.x86_64'],'mysql_c57_source_curl');
  assert.deepEqual([...rehearsal.candidateCurl].sort(),['curl-7.76.1-40.el9_8.9.x86_64','libcurl-7.76.1-40.el9_8.9.x86_64'],'mysql_c57_fixed_curl');
  for (const [key,version] of [['source','2.37.4-25.0.1.el9'],['candidate','2.37.4-28.0.1.el9_9']])
    assert.deepEqual([...rehearsal[key+'UtilLinux']].sort(),['libblkid','libmount','libsmartcols','libuuid'].map(pkg=>`${pkg}-${version}.x86_64`),'mysql_c57_util_linux_'+key);
  assert.equal(rehearsal.sourceKerberos,'krb5-libs-1.21.1-10.0.1.el9_8.x86_64','mysql_c57_source_kerberos');
  assert.equal(rehearsal.candidateKerberos,'krb5-libs-1.21.1-10.0.1.el9_9.2.x86_64','mysql_c57_fixed_kerberos');
  assert.match(rehearsal.sourceOtherPackagesSha256,/^[a-f0-9]{64}$/,'mysql_c57_package_hash');
  assert.equal(rehearsal.sourceOtherPackagesSha256,rehearsal.candidateOtherPackagesSha256,'mysql_c57_unrelated_packages');
  assert.deepEqual(rehearsal.before,rehearsal.after,'mysql_c57_snapshot_changed');
  assert.ok(rehearsal.before.tableCount>100&&rehearsal.before.rows>0,'mysql_c57_empty_snapshot');
  assert.deepEqual(rehearsal.ownedResourcesRemaining,{containers:0,volumes:0},'mysql_c57_cleanup');
  assert.equal(rehearsal.checks.length,28,'mysql_c57_check_count');
  assert.ok(rehearsal.checks.every(check=>check.passed===true),'mysql_c57_failed_check');
  for(const name of ['engine_version_source','engine_version_candidate','server_binary_unchanged','util_linux_source_vulnerable','brotli_preserved','openssl_preserved',
    'kerberos_source_vulnerable','kerberos_library_fixed','only_util_linux_and_kerberos_changed','util_linux_libraries_fixed','pcre2_preserved','all_tables_schema_history_accounts_and_settings_exact','existing_account_login',
    'restart_preserves_new_writes','same_volume_rollback_preserves_all_new_writes','fresh_restore_preserves_all_data_and_schema'])
    assert.equal(rehearsal.checks.filter(check=>check.name===name).length,1,'mysql_c57_missing_check');
  const publication=JSON.parse(await read(ROOT+'publication/publication.json'));
  assert.equal(publication.reference,MYSQL_C57_REFERENCE,'mysql_c57_publication_reference');
  assert.equal(publication.imageId,MYSQL_C57_CONFIG,'mysql_c57_publication_config');
  assert.equal(publication.manifestSet,'c57-mysql','mysql_c57_publication_set');
  assert.equal(entry.publication.path,ROOT+'publication/publication.json','mysql_c57_publication_path');
  const rawBytes=await read(ROOT+'publication/vulnerabilities.json'),raw=JSON.parse(rawBytes);
  const receipt=JSON.parse(await read(ROOT+'publication/vulnerabilities.adjudications.json'));
  assert.equal(raw.Metadata.ImageID,MYSQL_C57_CONFIG,'mysql_c57_scan_image');
  assert.ok(raw.Results.some(result=>result.Class==='os-pkgs'&&result.Packages?.length>0),'mysql_c57_scan_coverage');
  for (const name of ['libblkid','libmount','libsmartcols','libuuid']) {
    const packages=raw.Results.filter(result=>result.Class==='os-pkgs').flatMap(result=>result.Packages||[]).filter(pkg=>pkg.Name===name);
    assert.equal(packages.length,1,'mysql_c57_library_coverage');
    assert.equal(packages[0].Version+'-'+packages[0].Release,'2.37.4-28.0.1.el9_9','mysql_c57_library_version');
  }
  const kerberos=raw.Results.filter(result=>result.Class==='os-pkgs').flatMap(result=>result.Packages||[]).filter(pkg=>pkg.Name==='krb5-libs');
  assert.equal(kerberos.length,1,'mysql_c57_kerberos_coverage');
  assert.equal(kerberos[0].Version+'-'+kerberos[0].Release,'1.21.1-10.0.1.el9_9.2','mysql_c57_kerberos_scan_version');
  for(const part of [receipt,receipt.alloy,...(receipt.postgres?[receipt.postgres]:[])]){
    assert.equal(part.status,'NOT_APPLICABLE','mysql_c57_scan_adjudication');
    assert.deepEqual(part.decisions,[],'mysql_c57_scan_exemptions');
    assert.equal(part.rawReportSha256,hash(rawBytes),'mysql_c57_scan_hash');
    assert.equal(part.imageConfigId,MYSQL_C57_CONFIG,'mysql_c57_receipt_image');
    assert.equal(part.rawReportModified,false,'mysql_c57_raw_modified');
  }
  const summary=summarizeReport(raw);
  assert.equal(summary.high+summary.critical,0,'mysql_c57_raw_findings');
  for(const [key,value]of Object.entries(summary))assert.equal(publication.security[key],value,'mysql_c57_scan_summary');
  assert.equal(publication.security.result,'PASS','mysql_c57_scan_failed');
  assert.equal(publication.security.scannerImage,TRIVY_IMAGE,'mysql_c57_scanner');
  assert.deepEqual(JSON.parse(await read(ROOT+'publication/vulnerabilities.triage.json')),buildTriage(raw,rawBytes),'mysql_c57_triage');
  return {component:'mysql',reference:MYSQL_C57_REFERENCE,proofSha256:ref.sha256,mode:'COORDINATED_CANDIDATE_PREPARATION',refreshType:'SAME_ENGINE_UTIL_LINUX_AND_KERBEROS',ordinaryDeploymentUpgradeAuthorized:false};
}
