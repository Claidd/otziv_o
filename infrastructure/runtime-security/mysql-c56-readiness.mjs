import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validateDatabaseTransitionReadiness as previous} from './mysql-c46-readiness.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {buildTriage} from './triage-report.mjs';

export const MYSQL_C56_REFERENCE='ghcr.io/claidd/otziv-security@sha256:445e15dc40f18a25990558d95e8918b66938c87a7b6a27626f88f7d1e74f83d4';
export const MYSQL_C56_CONFIG='sha256:1baed98e1985fac938355424219a2e85a34f0ed1ed7fd650c657b2eada202179';
export const MYSQL_C56_REHEARSAL_SHA256='283e32d4c947753c5f3144758847c344fa4a7ef1408caeeb3e72eb2a1f8f4228';
const PARENT_SHA256='ed7606767c76cce17449fd06d9afca30210e71c3f89a2b2e3a1f51cbc119e668';
const ROOT='infrastructure/runtime-security/proofs/c56-mysql/';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');

export async function validateDatabaseTransitionReadiness(entry,image,read){
  if(entry?.reference!==MYSQL_C56_REFERENCE)return previous(entry,image,read);
  assert.equal(entry.component,'mysql','mysql_c56_component');
  const parentBytes=await read(ROOT+'parent-activation.json');
  assert.equal(hash(parentBytes),PARENT_SHA256,'mysql_c56_parent_changed');
  const prior=await previous(JSON.parse(parentBytes),image,read);
  const ref=entry.databaseTransition;
  assert.equal(ref?.path,ROOT+'rehearsal.json','mysql_c56_rehearsal_path');
  const bytes=await read(ref.path);
  assert.equal(hash(bytes),ref.sha256,'mysql_c56_rehearsal_hash');
  assert.equal(ref.sha256,MYSQL_C56_REHEARSAL_SHA256,'mysql_c56_rehearsal_anchor');
  const rehearsal=JSON.parse(bytes);
  assert.equal(rehearsal.schema,'otziv-mysql-same-engine-refresh-v1','mysql_c56_schema');
  assert.equal(rehearsal.result,'PASS','mysql_c56_failed');
  assert.equal(rehearsal.preliminary,false,'mysql_c56_preliminary');
  assert.equal(rehearsal.source,prior.reference,'mysql_c56_source');
  assert.equal(rehearsal.candidate,MYSQL_C56_REFERENCE,'mysql_c56_candidate');
  assert.ok([MYSQL_C56_CONFIG,MYSQL_C56_REFERENCE.split('@')[1]].includes(rehearsal.candidateLocalId),'mysql_c56_config');
  for(const key of ['production','sourceWrites'])assert.equal(rehearsal[key],false,'mysql_c56_production');
  assert.equal(rehearsal.publishedPorts,0,'mysql_c56_ports');
  assert.equal(rehearsal.network,'none','mysql_c56_network');
  assert.match(rehearsal.sourceDumpSha256,/^[a-f0-9]{64}$/,'mysql_c56_dump_hash');
  assert.equal(rehearsal.executedSourceSha256,hash(await read('infrastructure/runtime-security/mysql-c56-rehearsal.py')),'mysql_c56_executed_source');
  assert.equal(rehearsal.sourceMysqldSha256,'1a928190ad8166bc329b01d4adfba328f4d52083f81569f46c8435710c5e36eb','mysql_c56_engine_binary');
  assert.equal(rehearsal.sourceMysqldSha256,rehearsal.candidateMysqldSha256,'mysql_c56_engine_changed');
  assert.equal(rehearsal.sourceOpenSSL,'openssl-libs-3.5.8-2.0.1.el9_8.x86_64','mysql_c56_source_openssl');
  assert.equal(rehearsal.candidateOpenSSL,'openssl-libs-3.5.8-2.0.1.el9_8.x86_64','mysql_c56_fixed_openssl');
  assert.equal(rehearsal.sourceLibbrotli,'libbrotli-1.0.9-10.0.1.el9_8.x86_64','mysql_c56_source_brotli');
  assert.equal(rehearsal.candidateLibbrotli,'libbrotli-1.0.9-10.0.1.el9_8.x86_64','mysql_c56_fixed_brotli');
  for(const key of ['Libevent','Libxml2','Pcre2','Pcre2Syntax'])assert.equal(rehearsal['source'+key],rehearsal['candidate'+key],`mysql_c56_unrelated_${key}`);
  assert.deepEqual([...rehearsal.sourceCurl].sort(),['curl-7.76.1-40.el9_8.9.x86_64','libcurl-7.76.1-40.el9_8.9.x86_64'],'mysql_c56_source_curl');
  assert.deepEqual([...rehearsal.candidateCurl].sort(),['curl-7.76.1-40.el9_8.9.x86_64','libcurl-7.76.1-40.el9_8.9.x86_64'],'mysql_c56_fixed_curl');
  for (const [key,version] of [['source','2.37.4-25.0.1.el9'],['candidate','2.37.4-28.0.1.el9_9']])
    assert.deepEqual([...rehearsal[key+'UtilLinux']].sort(),['libblkid','libmount','libsmartcols','libuuid'].map(pkg=>`${pkg}-${version}.x86_64`),'mysql_c56_util_linux_'+key);
  assert.match(rehearsal.sourceOtherPackagesSha256,/^[a-f0-9]{64}$/,'mysql_c56_package_hash');
  assert.equal(rehearsal.sourceOtherPackagesSha256,rehearsal.candidateOtherPackagesSha256,'mysql_c56_unrelated_packages');
  assert.deepEqual(rehearsal.before,rehearsal.after,'mysql_c56_snapshot_changed');
  assert.ok(rehearsal.before.tableCount>100&&rehearsal.before.rows>0,'mysql_c56_empty_snapshot');
  assert.deepEqual(rehearsal.ownedResourcesRemaining,{containers:0,volumes:0},'mysql_c56_cleanup');
  assert.equal(rehearsal.checks.length,26,'mysql_c56_check_count');
  assert.ok(rehearsal.checks.every(check=>check.passed===true),'mysql_c56_failed_check');
  for(const name of ['engine_version_source','engine_version_candidate','server_binary_unchanged','util_linux_source_vulnerable','brotli_preserved','openssl_preserved',
    'only_util_linux_changed','util_linux_libraries_fixed','pcre2_preserved','all_tables_schema_history_accounts_and_settings_exact','existing_account_login',
    'restart_preserves_new_writes','same_volume_rollback_preserves_all_new_writes','fresh_restore_preserves_all_data_and_schema'])
    assert.equal(rehearsal.checks.filter(check=>check.name===name).length,1,'mysql_c56_missing_check');
  const publication=JSON.parse(await read(ROOT+'publication/publication.json'));
  assert.equal(publication.reference,MYSQL_C56_REFERENCE,'mysql_c56_publication_reference');
  assert.equal(publication.imageId,MYSQL_C56_CONFIG,'mysql_c56_publication_config');
  assert.equal(publication.manifestSet,'c56-mysql','mysql_c56_publication_set');
  assert.equal(entry.publication.path,ROOT+'publication/publication.json','mysql_c56_publication_path');
  const rawBytes=await read(ROOT+'publication/vulnerabilities.json'),raw=JSON.parse(rawBytes);
  const receipt=JSON.parse(await read(ROOT+'publication/vulnerabilities.adjudications.json'));
  assert.equal(raw.Metadata.ImageID,MYSQL_C56_CONFIG,'mysql_c56_scan_image');
  assert.ok(raw.Results.some(result=>result.Class==='os-pkgs'&&result.Packages?.length>0),'mysql_c56_scan_coverage');
  for (const name of ['libblkid','libmount','libsmartcols','libuuid']) {
    const packages=raw.Results.filter(result=>result.Class==='os-pkgs').flatMap(result=>result.Packages||[]).filter(pkg=>pkg.Name===name);
    assert.equal(packages.length,1,'mysql_c56_library_coverage');
    assert.equal(packages[0].Version+'-'+packages[0].Release,'2.37.4-28.0.1.el9_9','mysql_c56_library_version');
  }
  for(const part of [receipt,receipt.alloy,...(receipt.postgres?[receipt.postgres]:[])]){
    assert.equal(part.status,'NOT_APPLICABLE','mysql_c56_scan_adjudication');
    assert.deepEqual(part.decisions,[],'mysql_c56_scan_exemptions');
    assert.equal(part.rawReportSha256,hash(rawBytes),'mysql_c56_scan_hash');
    assert.equal(part.imageConfigId,MYSQL_C56_CONFIG,'mysql_c56_receipt_image');
    assert.equal(part.rawReportModified,false,'mysql_c56_raw_modified');
  }
  const summary=summarizeReport(raw);
  assert.equal(summary.high+summary.critical,0,'mysql_c56_raw_findings');
  for(const [key,value]of Object.entries(summary))assert.equal(publication.security[key],value,'mysql_c56_scan_summary');
  assert.equal(publication.security.result,'PASS','mysql_c56_scan_failed');
  assert.equal(publication.security.scannerImage,TRIVY_IMAGE,'mysql_c56_scanner');
  assert.deepEqual(JSON.parse(await read(ROOT+'publication/vulnerabilities.triage.json')),buildTriage(raw,rawBytes),'mysql_c56_triage');
  return {component:'mysql',reference:MYSQL_C56_REFERENCE,proofSha256:ref.sha256,mode:'COORDINATED_CANDIDATE_PREPARATION',refreshType:'SAME_ENGINE_UTIL_LINUX_ONLY',ordinaryDeploymentUpgradeAuthorized:false};
}
