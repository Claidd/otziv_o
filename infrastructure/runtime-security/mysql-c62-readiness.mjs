import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validateDatabaseTransitionReadiness as previous} from './mysql-c60-readiness.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {buildTriage} from './triage-report.mjs';

export const MYSQL_C62_REFERENCE='ghcr.io/claidd/otziv-security@sha256:370d1bbed24bb5f1178ebe2a332f018f8224cf8ac48bc61103bc4d470e9030b2';
export const MYSQL_C62_CONFIG='sha256:07dad1bdc3291cfece40d5c82f4bdf1f9696233c9da11c64ce7f4efc0f3c9104';
export const MYSQL_C62_REHEARSAL_SHA256='705d87f041847e7faef55fe6e5a94fd6fd53123dbd133e6d05689e5e36c7747e';
const PARENT_SHA256='fd22159d1030f4967673be7e288b607ca815329ea6ba8d7ee7e17afd44a8b691';
const ROOT='infrastructure/runtime-security/proofs/c62-mysql/';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');

export async function validateDatabaseTransitionReadiness(entry,image,read){
  if(entry?.reference!==MYSQL_C62_REFERENCE)return previous(entry,image,read);
  assert.equal(entry.component,'mysql','mysql_c62_component');
  const parentBytes=await read(ROOT+'parent-activation.json');
  assert.equal(hash(parentBytes),PARENT_SHA256,'mysql_c62_parent_changed');
  const prior=await previous(JSON.parse(parentBytes),image,read);
  const ref=entry.databaseTransition;
  assert.equal(ref?.path,ROOT+'rehearsal.json','mysql_c62_rehearsal_path');
  const bytes=await read(ref.path);
  assert.equal(hash(bytes),ref.sha256,'mysql_c62_rehearsal_hash');
  assert.equal(ref.sha256,MYSQL_C62_REHEARSAL_SHA256,'mysql_c62_rehearsal_anchor');
  const rehearsal=JSON.parse(bytes);
  assert.equal(rehearsal.schema,'otziv-mysql-same-engine-refresh-v1','mysql_c62_schema');
  assert.equal(rehearsal.result,'PASS','mysql_c62_failed');
  assert.equal(rehearsal.preliminary,false,'mysql_c62_preliminary');
  assert.equal(rehearsal.source,prior.reference,'mysql_c62_source');
  assert.equal(rehearsal.candidate,MYSQL_C62_REFERENCE,'mysql_c62_candidate');
  assert.ok([MYSQL_C62_CONFIG,MYSQL_C62_REFERENCE.split('@')[1]].includes(rehearsal.candidateLocalId),'mysql_c62_config');
  for(const key of ['production','sourceWrites'])assert.equal(rehearsal[key],false,'mysql_c62_production');
  assert.equal(rehearsal.publishedPorts,0,'mysql_c62_ports');
  assert.equal(rehearsal.network,'none','mysql_c62_network');
  assert.match(rehearsal.sourceDumpSha256,/^[a-f0-9]{64}$/,'mysql_c62_dump_hash');
  assert.equal(rehearsal.executedSourceSha256,hash(await read('infrastructure/runtime-security/mysql-c62-rehearsal.py')),'mysql_c62_executed_source');
  assert.equal(rehearsal.sourceMysqldSha256,'1a928190ad8166bc329b01d4adfba328f4d52083f81569f46c8435710c5e36eb','mysql_c62_engine_binary');
  assert.equal(rehearsal.sourceMysqldSha256,rehearsal.candidateMysqldSha256,'mysql_c62_engine_changed');
  assert.equal(rehearsal.sourceOpenSSL,'openssl-libs-3.5.8-2.0.1.el9_8.x86_64','mysql_c62_source_openssl');
  assert.equal(rehearsal.candidateOpenSSL,'openssl-libs-3.5.8-2.0.1.el9_8.x86_64','mysql_c62_fixed_openssl');
  assert.equal(rehearsal.sourceLibbrotli,'libbrotli-1.0.9-10.0.1.el9_8.x86_64','mysql_c62_source_brotli');
  assert.equal(rehearsal.candidateLibbrotli,'libbrotli-1.0.9-10.0.1.el9_8.x86_64','mysql_c62_fixed_brotli');
  assert.equal(rehearsal.sourceLibxml2,'libxml2-2.9.13-14.el9_8.5.x86_64','mysql_c62_source_libxml2');
  assert.equal(rehearsal.candidateLibxml2,'libxml2-2.9.13-18.el9_9.1.x86_64','mysql_c62_fixed_libxml2');
  for(const key of ['Libevent','Pcre2','Pcre2Syntax'])assert.equal(rehearsal['source'+key],rehearsal['candidate'+key],`mysql_c62_unrelated_${key}`);
  assert.deepEqual([...rehearsal.sourceCurl].sort(),['curl-7.76.1-40.el9_8.9.x86_64','libcurl-7.76.1-40.el9_8.9.x86_64'],'mysql_c62_source_curl');
  assert.deepEqual([...rehearsal.candidateCurl].sort(),['curl-7.76.1-40.el9_8.9.x86_64','libcurl-7.76.1-40.el9_8.9.x86_64'],'mysql_c62_fixed_curl');
  for (const [key,version] of [['source','2.37.4-28.0.1.el9_9.1'],['candidate','2.37.4-28.0.1.el9_9.1']])
    assert.deepEqual([...rehearsal[key+'UtilLinux']].sort(),['libblkid','libmount','libsmartcols','libuuid'].map(pkg=>`${pkg}-${version}.x86_64`),'mysql_c62_util_linux_'+key);
  assert.equal(rehearsal.sourceKerberos,'krb5-libs-1.21.1-10.0.1.el9_9.2.x86_64','mysql_c62_source_kerberos');
  assert.equal(rehearsal.candidateKerberos,'krb5-libs-1.21.1-10.0.1.el9_9.2.x86_64','mysql_c62_fixed_kerberos');
  assert.match(rehearsal.sourceOtherPackagesSha256,/^[a-f0-9]{64}$/,'mysql_c62_package_hash');
  assert.equal(rehearsal.sourceOtherPackagesSha256,rehearsal.candidateOtherPackagesSha256,'mysql_c62_unrelated_packages');
  assert.deepEqual(rehearsal.before,rehearsal.after,'mysql_c62_snapshot_changed');
  assert.ok(rehearsal.before.tableCount>100&&rehearsal.before.rows>0,'mysql_c62_empty_snapshot');
  assert.deepEqual(rehearsal.ownedResourcesRemaining,{containers:0,volumes:0},'mysql_c62_cleanup');
  assert.equal(rehearsal.checks.length,28,'mysql_c62_check_count');
  assert.ok(rehearsal.checks.every(check=>check.passed===true),'mysql_c62_failed_check');
  for(const name of ['engine_version_source','engine_version_candidate','server_binary_unchanged','libxml2_source_vulnerable','brotli_preserved','openssl_preserved',
    'kerberos_preserved','kerberos_library_fixed','only_libxml2_changed','libxml2_fixed','util_linux_preserved','pcre2_preserved','all_tables_schema_history_accounts_and_settings_exact','existing_account_login',
    'restart_preserves_new_writes','same_volume_rollback_preserves_all_new_writes','fresh_restore_preserves_all_data_and_schema'])
    assert.equal(rehearsal.checks.filter(check=>check.name===name).length,1,'mysql_c62_missing_check');
  const publication=JSON.parse(await read(ROOT+'publication/publication.json'));
  assert.equal(publication.reference,MYSQL_C62_REFERENCE,'mysql_c62_publication_reference');
  assert.equal(publication.imageId,MYSQL_C62_CONFIG,'mysql_c62_publication_config');
  assert.equal(publication.manifestSet,'c62-mysql','mysql_c62_publication_set');
  assert.equal(entry.publication.path,ROOT+'publication/publication.json','mysql_c62_publication_path');
  const rawBytes=await read(ROOT+'publication/vulnerabilities.json'),raw=JSON.parse(rawBytes);
  const receipt=JSON.parse(await read(ROOT+'publication/vulnerabilities.adjudications.json'));
  assert.equal(raw.Metadata.ImageID,MYSQL_C62_CONFIG,'mysql_c62_scan_image');
  assert.ok(raw.Results.some(result=>result.Class==='os-pkgs'&&result.Packages?.length>0),'mysql_c62_scan_coverage');
  for (const name of ['libblkid','libmount','libsmartcols','libuuid']) {
    const packages=raw.Results.filter(result=>result.Class==='os-pkgs').flatMap(result=>result.Packages||[]).filter(pkg=>pkg.Name===name);
    assert.equal(packages.length,1,'mysql_c62_library_coverage');
    assert.equal(packages[0].Version+'-'+packages[0].Release,'2.37.4-28.0.1.el9_9.1','mysql_c62_library_version');
  }
  const xmlPackages=raw.Results.filter(result=>result.Class==='os-pkgs').flatMap(result=>result.Packages||[]).filter(pkg=>pkg.Name==='libxml2');
  assert.equal(xmlPackages.length,1,'mysql_c62_libxml2_coverage');
  assert.equal(xmlPackages[0].Version+'-'+xmlPackages[0].Release,'2.9.13-18.el9_9.1','mysql_c62_libxml2_scan_version');
  const kerberos=raw.Results.filter(result=>result.Class==='os-pkgs').flatMap(result=>result.Packages||[]).filter(pkg=>pkg.Name==='krb5-libs');
  assert.equal(kerberos.length,1,'mysql_c62_kerberos_coverage');
  assert.equal(kerberos[0].Version+'-'+kerberos[0].Release,'1.21.1-10.0.1.el9_9.2','mysql_c62_kerberos_scan_version');
  for(const part of [receipt,receipt.alloy,...(receipt.postgres?[receipt.postgres]:[])]){
    assert.equal(part.status,'NOT_APPLICABLE','mysql_c62_scan_adjudication');
    assert.deepEqual(part.decisions,[],'mysql_c62_scan_exemptions');
    assert.equal(part.rawReportSha256,hash(rawBytes),'mysql_c62_scan_hash');
    assert.equal(part.imageConfigId,MYSQL_C62_CONFIG,'mysql_c62_receipt_image');
    assert.equal(part.rawReportModified,false,'mysql_c62_raw_modified');
  }
  const summary=summarizeReport(raw);
  assert.equal(summary.high+summary.critical,0,'mysql_c62_raw_findings');
  for(const [key,value]of Object.entries(summary))assert.equal(publication.security[key],value,'mysql_c62_scan_summary');
  assert.equal(publication.security.result,'PASS','mysql_c62_scan_failed');
  assert.equal(publication.security.scannerImage,TRIVY_IMAGE,'mysql_c62_scanner');
  assert.deepEqual(JSON.parse(await read(ROOT+'publication/vulnerabilities.triage.json')),buildTriage(raw,rawBytes),'mysql_c62_triage');
  return {component:'mysql',reference:MYSQL_C62_REFERENCE,proofSha256:ref.sha256,mode:'COORDINATED_CANDIDATE_PREPARATION',refreshType:'SAME_ENGINE_LIBXML2_SECURITY_FIX',ordinaryDeploymentUpgradeAuthorized:false};
}
