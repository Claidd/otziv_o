import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validateDatabaseTransitionReadiness as previous} from './mysql-c62-readiness.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {buildTriage} from './triage-report.mjs';

export const MYSQL_C63_REFERENCE='ghcr.io/claidd/otziv-security@sha256:200c94f0b78df0916d165b039f56aff795889aec1f0db1fbaf667074730e1bb8';
export const MYSQL_C63_CONFIG='sha256:74720c95898ecde511ed76f307992f92065a215b2d5878e3db50109cb9867e3a';
export const MYSQL_C63_REHEARSAL_SHA256='7e912189faf603250f6b0fc4fe9434ae323f7b2f6a6e37e33d31c68e253674de';
const PARENT_SHA256='c51072565d7ef3c19c94265d37b291b33fccf5317d482de3a88f1107827fc844';
const ROOT='infrastructure/runtime-security/proofs/c63-mysql/';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
// Signed Oracle Linux 9 builds replacing the C62 base packages; everything else stays byte-identical.
export const MYSQL_C63_SOURCE_REFRESHED=['curl-7.76.1-40.el9_8.9.x86_64','libcurl-7.76.1-40.el9_8.9.x86_64','glibc-2.34-275.0.1.el9_8.x86_64',
  'glibc-common-2.34-275.0.1.el9_8.x86_64','glibc-minimal-langpack-2.34-275.0.1.el9_8.x86_64','openssl-libs-3.5.8-2.0.1.el9_8.x86_64',
  'rpm-4.16.1.3-40.el9.x86_64','rpm-libs-4.16.1.3-40.el9.x86_64','xz-libs-5.2.5-8.el9_0.x86_64','gawk-5.1.0-6.el9.x86_64','glib2-2.68.4-19.el9_8.10.x86_64'];
export const MYSQL_C63_REFRESHED=['curl-7.76.1-44.el9_9.1.x86_64','libcurl-7.76.1-44.el9_9.1.x86_64','glibc-2.34-290.0.1.el9_9.x86_64',
  'glibc-common-2.34-290.0.1.el9_9.x86_64','glibc-minimal-langpack-2.34-290.0.1.el9_9.x86_64','openssl-libs-3.5.9-1.0.1.el9_9.x86_64',
  'rpm-4.16.1.3-43.el9_9.x86_64','rpm-libs-4.16.1.3-43.el9_9.x86_64','xz-libs-5.2.5-8.el9_9.1.x86_64','gawk-5.1.0-6.el9_8.1.x86_64','glib2-2.68.4-29.el9_9.1.x86_64'];
export const MYSQL_C63_PRESERVED=['libxml2-2.9.13-18.el9_9.1.x86_64','libbrotli-1.0.9-10.0.1.el9_8.x86_64','pcre2-10.40-7.0.1.el9_8.x86_64',
  'pcre2-syntax-10.40-7.0.1.el9_8.noarch','libevent-2.1.13-1.el9_8.x86_64','krb5-libs-1.21.1-10.0.1.el9_9.2.x86_64',
  'libblkid-2.37.4-28.0.1.el9_9.1.x86_64','libmount-2.37.4-28.0.1.el9_9.1.x86_64','libsmartcols-2.37.4-28.0.1.el9_9.1.x86_64',
  'libuuid-2.37.4-28.0.1.el9_9.1.x86_64'];
const REQUIRED_CHECKS=['engine_version_source','engine_version_candidate','server_binary_unchanged','refreshed_packages_source_vulnerable',
  'refreshed_packages_fixed','libxml2_brotli_pcre2_libevent_kerberos_util_linux_preserved','only_reviewed_packages_changed',
  'restored_nonempty_production_schema','all_tables_schema_history_accounts_and_settings_exact','existing_account_login',
  'restart_preserves_new_writes','same_volume_rollback_preserves_all_new_writes','fresh_restore_preserves_all_data_and_schema'];

export async function validateDatabaseTransitionReadiness(entry,image,read){
  if(entry?.reference!==MYSQL_C63_REFERENCE)return previous(entry,image,read);
  assert.equal(entry.component,'mysql','mysql_c63_component');
  const parentBytes=await read(ROOT+'parent-activation.json');
  assert.equal(hash(parentBytes),PARENT_SHA256,'mysql_c63_parent_changed');
  const prior=await previous(JSON.parse(parentBytes),image,read);
  const ref=entry.databaseTransition;
  assert.equal(ref?.path,ROOT+'rehearsal.json','mysql_c63_rehearsal_path');
  const bytes=await read(ref.path);
  assert.equal(hash(bytes),ref.sha256,'mysql_c63_rehearsal_hash');
  assert.equal(ref.sha256,MYSQL_C63_REHEARSAL_SHA256,'mysql_c63_rehearsal_anchor');
  const rehearsal=JSON.parse(bytes);
  assert.equal(rehearsal.schema,'otziv-mysql-same-engine-refresh-v1','mysql_c63_schema');
  assert.equal(rehearsal.result,'PASS','mysql_c63_failed');
  assert.equal(rehearsal.preliminary,false,'mysql_c63_preliminary');
  assert.equal(rehearsal.source,prior.reference,'mysql_c63_source');
  assert.equal(rehearsal.candidate,MYSQL_C63_REFERENCE,'mysql_c63_candidate');
  assert.ok([MYSQL_C63_CONFIG,MYSQL_C63_REFERENCE.split('@')[1]].includes(rehearsal.candidateLocalId),'mysql_c63_config');
  for(const key of ['production','sourceWrites'])assert.equal(rehearsal[key],false,'mysql_c63_production');
  assert.equal(rehearsal.publishedPorts,0,'mysql_c63_ports');
  assert.equal(rehearsal.network,'none','mysql_c63_network');
  assert.match(rehearsal.sourceDumpSha256,/^[a-f0-9]{64}$/,'mysql_c63_dump_hash');
  assert.equal(rehearsal.executedSourceSha256,hash(await read('infrastructure/runtime-security/mysql-c63-rehearsal.py')),'mysql_c63_executed_source');
  assert.equal(rehearsal.sourceMysqldSha256,'1a928190ad8166bc329b01d4adfba328f4d52083f81569f46c8435710c5e36eb','mysql_c63_engine_binary');
  assert.equal(rehearsal.sourceMysqldSha256,rehearsal.candidateMysqldSha256,'mysql_c63_engine_changed');
  assert.deepEqual(rehearsal.sourceRefreshed,MYSQL_C63_SOURCE_REFRESHED,'mysql_c63_source_packages');
  assert.deepEqual(rehearsal.candidateRefreshed,MYSQL_C63_REFRESHED,'mysql_c63_fixed_packages');
  assert.deepEqual(rehearsal.sourcePreserved,MYSQL_C63_PRESERVED,'mysql_c63_source_preserved');
  assert.deepEqual(rehearsal.candidatePreserved,MYSQL_C63_PRESERVED,'mysql_c63_candidate_preserved');
  assert.match(rehearsal.sourceOtherPackagesSha256,/^[a-f0-9]{64}$/,'mysql_c63_package_hash');
  assert.equal(rehearsal.sourceOtherPackagesSha256,rehearsal.candidateOtherPackagesSha256,'mysql_c63_unrelated_packages');
  assert.deepEqual(rehearsal.before,rehearsal.after,'mysql_c63_snapshot_changed');
  assert.ok(rehearsal.before.tableCount>100&&rehearsal.before.rows>0,'mysql_c63_empty_snapshot');
  assert.deepEqual(rehearsal.ownedResourcesRemaining,{containers:0,volumes:0},'mysql_c63_cleanup');
  assert.equal(rehearsal.checks.length,22,'mysql_c63_check_count');
  assert.ok(rehearsal.checks.every(check=>check.passed===true),'mysql_c63_failed_check');
  for(const name of REQUIRED_CHECKS)
    assert.equal(rehearsal.checks.filter(check=>check.name===name).length,1,'mysql_c63_missing_check');
  const publication=JSON.parse(await read(ROOT+'publication/publication.json'));
  assert.equal(publication.reference,MYSQL_C63_REFERENCE,'mysql_c63_publication_reference');
  assert.equal(publication.imageId,MYSQL_C63_CONFIG,'mysql_c63_publication_config');
  assert.equal(publication.manifestSet,'c63-mysql','mysql_c63_publication_set');
  assert.equal(entry.publication.path,ROOT+'publication/publication.json','mysql_c63_publication_path');
  const rawBytes=await read(ROOT+'publication/vulnerabilities.json'),raw=JSON.parse(rawBytes);
  const receipt=JSON.parse(await read(ROOT+'publication/vulnerabilities.adjudications.json'));
  assert.equal(raw.Metadata.ImageID,MYSQL_C63_CONFIG,'mysql_c63_scan_image');
  const osPackages=raw.Results.filter(result=>result.Class==='os-pkgs').flatMap(result=>result.Packages||[]);
  assert.ok(osPackages.length>0,'mysql_c63_scan_coverage');
  // The scanner must have seen every refreshed and preserved package at exactly the reviewed build.
  for(const nevra of [...MYSQL_C63_REFRESHED,...MYSQL_C63_PRESERVED]){
    const name=nevra.replace(/-[^-]+-[^-]+\.(?:x86_64|noarch)$/,'');
    const found=osPackages.filter(pkg=>pkg.Name===name);
    assert.equal(found.length,1,'mysql_c63_package_coverage_'+name);
    assert.equal(name+'-'+found[0].Version+'-'+found[0].Release,nevra.replace(/\.(?:x86_64|noarch)$/,''),'mysql_c63_package_scan_version_'+name);
  }
  for(const part of [receipt,receipt.alloy,...(receipt.postgres?[receipt.postgres]:[])]){
    assert.equal(part.status,'NOT_APPLICABLE','mysql_c63_scan_adjudication');
    assert.deepEqual(part.decisions,[],'mysql_c63_scan_exemptions');
    assert.equal(part.rawReportSha256,hash(rawBytes),'mysql_c63_scan_hash');
    assert.equal(part.imageConfigId,MYSQL_C63_CONFIG,'mysql_c63_receipt_image');
    assert.equal(part.rawReportModified,false,'mysql_c63_raw_modified');
  }
  const summary=summarizeReport(raw);
  assert.equal(summary.high+summary.critical,0,'mysql_c63_raw_findings');
  for(const [key,value]of Object.entries(summary))assert.equal(publication.security[key],value,'mysql_c63_scan_summary');
  assert.equal(publication.security.result,'PASS','mysql_c63_scan_failed');
  assert.equal(publication.security.scannerImage,TRIVY_IMAGE,'mysql_c63_scanner');
  assert.deepEqual(JSON.parse(await read(ROOT+'publication/vulnerabilities.triage.json')),buildTriage(raw,rawBytes),'mysql_c63_triage');
  return {component:'mysql',reference:MYSQL_C63_REFERENCE,proofSha256:ref.sha256,mode:'COORDINATED_CANDIDATE_PREPARATION',refreshType:'SAME_ENGINE_BASE_PACKAGES_SECURITY_FIX',ordinaryDeploymentUpgradeAuthorized:false};
}
