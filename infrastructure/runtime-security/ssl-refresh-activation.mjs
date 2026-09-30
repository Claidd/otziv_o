import assert from 'node:assert/strict';
import {dirname} from 'node:path';
import {gunzipSync} from 'node:zlib';
import {hash,parentActivation,parentConfig,validateSslInspection,effectivePostgresSummary} from './ssl-refresh.mjs';
import {loadPostgresC16Proof,POSTGRES_C16_RULES} from './postgres-c16-adjudication.mjs';
import {matchingPostgresFindings} from './postgres-c14-adjudication.mjs';
import {POSTGRES_LIBXML_PYTHON_RULE,matchRules} from './postgres-c15-adjudication.mjs';
import {reviewLibxmlSeptemberFindings} from './postgres-libxml-september-review.mjs';
import {loadAlloyProof,matchingAlloyFindings} from './alloy-daemon-adjudication.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {buildTriage} from './triage-report.mjs';
import {validatePostgresTransitionReadiness as parentTransition} from './postgres-c16-activation.mjs';
import {coupleKeycloakC22} from './keycloak-c22-acceptance.mjs';

const PARENT_INDEX_SHA='7df6f0ad65a8e1b7466afc226bf2db32abd34864f082fe3607701eead286ef92';
const BASELINE='infrastructure/runtime-security/reviewed-images.json';
const safe=p=>typeof p==='string'&&p.startsWith('infrastructure/')&&!/[\\:]/.test(p)&&p.split('/').every(x=>x&&x!=='.'&&x!=='..');
const sameId=(id,config,ref)=>assert.ok([config,ref.split('@')[1]].includes(id),'ssl_refresh_acceptance_inspected_id');
const checks=(record,names)=>{
  assert.equal(record.result,'PASS','ssl_refresh_acceptance_rehearsal_failed');
  assert.ok(record.checks?.length&&record.checks.every(x=>x.passed===true),'ssl_refresh_acceptance_checks_failed');
  for(const name of names)assert.equal(record.checks.filter(x=>x.name===name&&x.passed).length,1,'ssl_refresh_acceptance_missing_check:'+name);
};

async function acceptance(entry,read){
  assert.equal(hash(await read('infrastructure/runtime-security/c23-parent-activations.json')),PARENT_INDEX_SHA,'ssl_refresh_parent_index_changed');
  const bound=entry.sslRefreshAcceptance;assert.ok(bound&&safe(bound.path),'ssl_refresh_acceptance_missing');
  const raw=await read(bound.path);assert.equal(hash(raw),bound.sha256,'ssl_refresh_acceptance_hash');const value=JSON.parse(raw);
  assert.equal(value.schema,'otziv-ssl-refresh-acceptance-v1','ssl_refresh_acceptance_schema');assert.equal(value.result,'PASS','ssl_refresh_acceptance_failed');
  assert.equal(value.component,entry.component,'ssl_refresh_acceptance_component');assert.equal(value.reference,entry.reference,'ssl_refresh_acceptance_reference');
  assert.equal(value.parentIndexSha256,PARENT_INDEX_SHA,'ssl_refresh_acceptance_parent_index');
  for(const key of ['commit','run','attempt'])assert.equal(value[key],entry[key],'ssl_refresh_acceptance_publication_identity');
  assert.ok(Object.keys(value.files||{}).length>=6&&Object.keys(value.executedSources||{}).length>=2,'ssl_refresh_acceptance_evidence_missing');
  for(const [path,sha]of Object.entries({...value.files,...value.executedSources})){
    assert.ok(safe(path),'ssl_refresh_acceptance_path');assert.equal(hash(await read(path)),sha,'ssl_refresh_acceptance_source_or_evidence_changed');
  }
  return value;
}

async function inspection(value,entry,read){
  const {entry:parent,config:parentCfg,imageId:parentId}=await parentConfig(entry.component);
  assert.equal(value.parentReference,parent.reference,'ssl_refresh_acceptance_parent_reference');
  const childBytes=await read(dirname(entry.publication.path)+'/registry-amd64-config.json'),child=JSON.parse(childBytes);
  assert.equal('sha256:'+hash(childBytes),value.imageConfigId,'ssl_refresh_acceptance_registry_config');
  const runtimeBytes=await read(value.runtimePath),runtime=JSON.parse(gunzipSync(runtimeBytes));
  assert.ok(value.files[value.runtimePath],'ssl_refresh_acceptance_runtime_unbound');
  assert.equal(runtime.executedScriptSha256,value.executedSources['infrastructure/runtime-security/ssl-refresh-inspection.py'],'ssl_refresh_acceptance_inspector');
  const inspected=validateSslInspection(runtime,entry.component,parentCfg,child,parentId,value.imageConfigId,
    {parentReference:parent.reference,candidateReference:entry.reference});
  return {...inspected,parent,parentId,runtimeBytes,child};
}

export async function validateSslRefreshActivation(publication,entry,read,validateParent){
  const value=await acceptance(entry,read),observed=await inspection(value,entry,read);
  const original=JSON.parse(await read(BASELINE)).images.find(x=>x.component===entry.component);
  await validateParent(original,observed.parent,await read(BASELINE),read);
  assert.equal(publication.imageId,value.imageConfigId,'ssl_refresh_acceptance_publication_config');
  const directory=dirname(entry.publication.path),rawBytes=await read(directory+'/vulnerabilities.json'),raw=JSON.parse(rawBytes);
  assert.equal(raw.Metadata?.ImageID,value.imageConfigId,'ssl_refresh_acceptance_scan_image');
  assert.deepEqual(raw.Metadata.ImageConfig.rootfs,observed.child.rootfs,'ssl_refresh_acceptance_scan_rootfs');
  const receipt=JSON.parse(await read(directory+'/vulnerabilities.adjudications.json'));
  assert.equal(receipt.rawReportSha256,hash(rawBytes),'ssl_refresh_acceptance_scan_hash');
  assert.equal(receipt.imageConfigId,value.imageConfigId,'ssl_refresh_acceptance_receipt_image');
  assert.equal(receipt.rawReportModified,false,'ssl_refresh_acceptance_scan_modified');
  assert.equal(receipt.status,'NOT_APPLICABLE','ssl_refresh_acceptance_foreign_adjudication');assert.deepEqual(receipt.decisions,[],'ssl_refresh_acceptance_foreign_decisions');
  assert.deepEqual(JSON.parse(await read(directory+'/vulnerabilities.triage.json')),buildTriage(raw,rawBytes),'ssl_refresh_acceptance_triage');
  const derived=entry.component==='postgres'?receipt.postgres:entry.component==='alloy'?receipt.alloy:null;
  if(derived){
    assert.equal(derived.imageConfigId,value.imageConfigId,'ssl_refresh_acceptance_derived_image');assert.equal(derived.rawReportSha256,hash(rawBytes),'ssl_refresh_acceptance_derived_scan');
    assert.equal(derived.rawReportModified,false,'ssl_refresh_acceptance_derived_modified');sameId(derived.immutableImageId,value.imageConfigId,entry.reference);
    assert.equal(derived.sslRefresh?.runtimeProofSha256,hash(observed.runtimeBytes),'ssl_refresh_acceptance_derived_runtime');
    assert.equal(derived.sslRefresh?.parentConfigId,observed.parentId,'ssl_refresh_acceptance_derived_parent');
    if(entry.component==='postgres'){
      assert.equal(derived.schema,'otziv-postgres-c23-adjudication-v1','ssl_refresh_acceptance_postgres_schema');
      const {review,reviewSha256}=await loadPostgresC16Proof();assert.equal(derived.reviewSha256,reviewSha256,'ssl_refresh_acceptance_postgres_source');
      const september=await reviewLibxmlSeptemberFindings(raw,review);
      const python=matchRules(raw,review,[POSTGRES_LIBXML_PYTHON_RULE]);
      if(python.length){assert.ok(!Object.keys(observed.after.inventory).some(path=>/python|libxml2mod/i.test(path)),'ssl_refresh_acceptance_python_present');
        assert.deepEqual(derived.libxml?.decisions,python,'ssl_refresh_acceptance_python_decisions');}
      assert.deepEqual(derived.libxmlSeptember??null,september,'ssl_refresh_acceptance_september_review');
      assert.deepEqual(derived.decisions,[...matchingPostgresFindings(raw,review),...matchRules(raw,review,POSTGRES_C16_RULES),...python,...(september?.decisions||[])],
        'ssl_refresh_acceptance_postgres_decisions');effectivePostgresSummary(summarizeReport(raw),derived);
    }else{
      const {review,reviewSha256,closure}=await loadAlloyProof(new Date(),observed.parentId);
      assert.equal(derived.reviewSha256,reviewSha256,'ssl_refresh_acceptance_alloy_review');assert.equal(derived.validUntil,review.validUntil,'ssl_refresh_acceptance_alloy_expiry');
      for(const image of [observed.before,observed.after])assert.equal(image.inventory[review.binary.path]?.sha256,review.binary.sha256,'ssl_refresh_acceptance_alloy_payload');
      assert.equal(derived.binarySha256,review.binary.sha256,'ssl_refresh_acceptance_alloy_binary');
      assert.equal(derived.canonicalBuildInfoSha256,review.binary.canonicalBuildInfoSha256,'ssl_refresh_acceptance_alloy_build_info');
      assert.equal(derived.inspectedBinaryExecuted,false,'ssl_refresh_acceptance_alloy_executed');
      assert.deepEqual(derived.closure,closure,'ssl_refresh_acceptance_alloy_closure');assert.deepEqual(derived.module,review.module,'ssl_refresh_acceptance_alloy_module');
      assert.deepEqual(derived.decisions,matchingAlloyFindings(raw,{...review,imageConfigId:value.imageConfigId}),'ssl_refresh_acceptance_alloy_decisions');
    }
  }else assert.equal(summarizeReport(raw).high+summarizeReport(raw).critical,0,'ssl_refresh_acceptance_raw_security_findings');
  const summary={...combinedScanSummary(summarizeReport(raw),{grafana:receipt,alloy:receipt.alloy,postgres:receipt.postgres}),scannerImage:TRIVY_IMAGE};
  assert.deepEqual(publication.security,summary,'ssl_refresh_acceptance_security_summary');
  assert.equal(summary.result,'PASS','ssl_refresh_acceptance_security_failed');assert.equal(summary.unresolvedRiskReview,'NONE','ssl_refresh_acceptance_unresolved_risk');
  if(['postgres','keycloak'].includes(entry.component))await validatePairRehearsal(value,entry,read);
  return value;
}

async function validatePairRehearsal(value,entry,read){
  for(const path of [value.capturePath,value.replayPath,value.issuerPath,value.startupPath])assert.ok(value.files[path],'ssl_refresh_acceptance_pair_unbound');
  const capture=JSON.parse(await read(value.capturePath)),replay=JSON.parse(await read(value.replayPath));
  const parents={postgres:await parentConfig('postgres'),keycloak:await parentConfig('keycloak')};
  for(const record of [capture,replay]){
    assert.equal(record.schema,'otziv-ssl-c23-rehearsal-v1','ssl_refresh_acceptance_rehearsal_schema');
    assert.equal(record.sourceScope,'LOCAL_PROD_LIKE_KEYCLOAK','ssl_refresh_acceptance_source_scope');
    assert.equal(record.productionWrites,false,'ssl_refresh_acceptance_production_write');assert.equal(record.privateRowsPublished,false,'ssl_refresh_acceptance_private_rows');
    assert.equal(record.publishedPorts,0,'ssl_refresh_acceptance_open_ports');assert.equal(record.sourcePostgres,parents.postgres.entry.reference,'ssl_refresh_acceptance_parent_postgres');
    assert.equal(record.sourcePostgresConfig,parents.postgres.imageId,'ssl_refresh_acceptance_parent_postgres_config');
    assert.equal(record.keycloak,parents.keycloak.entry.reference,'ssl_refresh_acceptance_parent_keycloak');assert.equal(record.keycloakConfig,parents.keycloak.imageId,'ssl_refresh_acceptance_parent_keycloak_config');
    assert.equal(record.executedScriptSha256,value.executedSources['infrastructure/runtime-security/ssl-refresh-rehearsal.py'],'ssl_refresh_acceptance_rehearsal_source');
  }
  checks(capture,['complete_custom_dump','source_runtime_unchanged','source_critical_identity_stable_during_capture']);
  checks(replay,['restored_critical_records_exact','capture_stable_tables_restored_exact','source_existing_password_login','source_roles_and_credentials_preserved',
    'same_volume_all_tables_exact','existing_admin_password_login','existing_roles_and_credentials_preserved','unauthenticated_provider_denied',
    'library_patch_no_schema_migration','same_volume_rollback_preserves_all_post_upgrade_writes','same_volume_old_issuer_login',
    'same_volume_old_issuer_credentials_preserved','fresh_rollback_restore_preserves_all_post_upgrade_writes','restored_existing_password_login','restored_roles_and_credentials_preserved']);
  assert.equal(replay.dumpSha256,capture.dumpSha256,'ssl_refresh_acceptance_dump_binding');
  assert.equal(replay.candidate,value.pair.keycloak.reference,'ssl_refresh_acceptance_pair_keycloak');sameId(replay.candidateLocalId,value.pair.keycloak.imageConfigId,replay.candidate);
  assert.equal(replay.candidatePostgres,value.pair.postgres.reference,'ssl_refresh_acceptance_pair_postgres');sameId(replay.candidatePostgresLocalId,value.pair.postgres.imageConfigId,replay.candidatePostgres);
  assert.deepEqual(replay.ownedResourcesRemaining,{container:0,volume:0,network:0},'ssl_refresh_acceptance_rehearsal_cleanup');assert.ok(!replay.cleanupErrors?.length,'ssl_refresh_acceptance_rehearsal_cleanup_errors');
  assert.equal(replay.tableCount,102,'ssl_refresh_acceptance_table_coverage');
  for(const key of ['serverVersion','encoding','locale','clusterIdentifier','extensions','configSha256','hbaSha256','identSha256'])
    assert.deepEqual(replay.candidateDatabase[key],replay.sourceDatabase[key],'ssl_refresh_acceptance_database_changed');
  const issuer=JSON.parse(await read(value.issuerPath)),startup=JSON.parse(await read(value.startupPath));
  for(const record of [issuer,startup]){assert.equal(record.production,false,'ssl_refresh_acceptance_fixture_production');
    assert.equal(record.image,value.pair.keycloak.reference,'ssl_refresh_acceptance_fixture_keycloak');sameId(record.imageId,value.pair.keycloak.imageConfigId,record.image);checks(record,[]);}
  assert.equal(issuer.realProviderLogin,true,'ssl_refresh_acceptance_provider_login');assert.ok(issuer.checks.length>=20,'ssl_refresh_acceptance_issuer_coverage');
  assert.equal(issuer.postgresImage,value.pair.postgres.reference,'ssl_refresh_acceptance_issuer_postgres');sameId(issuer.postgresImageId,value.pair.postgres.imageConfigId,issuer.postgresImage);
  assert.equal(startup.postgresImage.reference,value.pair.postgres.reference,'ssl_refresh_acceptance_startup_postgres');assert.equal(startup.cleanup,'PASS','ssl_refresh_acceptance_startup_cleanup');
  checks(startup,['start_imports_and_serves_real_postgres_realm','start_has_no_missing_classpath_error','start-dev_imports_and_serves_real_postgres_realm','start-dev_has_no_missing_classpath_error']);
}

export async function validateSslRefreshTransition(entry,image,read){
  const value=await acceptance(entry,read);assert.equal(entry.component,'postgres','ssl_refresh_transition_component');
  assert.deepEqual(entry.databaseTransition,entry.sslRefreshAcceptance,'ssl_refresh_transition_binding');await validatePairRehearsal(value,entry,read);
  const parent=await parentActivation('postgres'),prior=await parentTransition(parent,image,read);
  const paired=await coupleKeycloakC22(prior,await parentActivation('keycloak'),read);
  assert.equal(value.pair.postgres.reference,entry.reference,'ssl_refresh_transition_candidate');
  return {...paired,mode:'PROVED_SSL_REFRESH_WITH_RESTORE_AND_ROLLBACK',postgresReference:entry.reference,postgresConfigId:value.imageConfigId,
    requiredKeycloakReference:value.pair.keycloak.reference,requiredKeycloakConfigId:value.pair.keycloak.imageConfigId,
    acceptancePath:entry.sslRefreshAcceptance.path,proofSha256:entry.sslRefreshAcceptance.sha256,ordinaryDeploymentUpgradeAuthorized:false};
}
export async function coupleSslRefresh(readiness,entry,read){
  const value=await acceptance(entry,read);await validatePairRehearsal(value,entry,read);
  assert.deepEqual(entry.migrationAcceptance,entry.sslRefreshAcceptance,'ssl_refresh_issuer_acceptance_binding');
  assert.equal(readiness.postgresReference,value.pair.postgres.reference,'ssl_refresh_coupling_postgres');assert.equal(readiness.postgresConfigId,value.pair.postgres.imageConfigId,'ssl_refresh_coupling_postgres_config');
  assert.equal(readiness.requiredKeycloakReference,entry.reference,'ssl_refresh_coupling_issuer');assert.equal(readiness.requiredKeycloakConfigId,value.imageConfigId,'ssl_refresh_coupling_issuer_config');
  return readiness;
}
