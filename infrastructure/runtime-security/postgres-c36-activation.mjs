// Fail-closed activation of the C36 gosu-only PostgreSQL derivative. Publication
// alone does not authorize replacing the database in Compose: the candidate
// must have its own same-volume C23 -> C36 -> C23 rehearsal with active C35 KC.
import assert from 'node:assert/strict';
import {dirname} from 'node:path';
import {gunzipSync} from 'node:zlib';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
import {buildTriage} from './triage-report.mjs';
import {loadPostgresC16Proof,POSTGRES_C16_RULES} from './postgres-c16-adjudication.mjs';
import {matchingPostgresFindings} from './postgres-c14-adjudication.mjs';
import {POSTGRES_LIBXML_PYTHON_RULE,matchRules} from './postgres-c15-adjudication.mjs';
import {reviewLibxmlSeptemberFindings} from './postgres-libxml-september-review.mjs';
import {validateSslRefreshTransition} from './ssl-refresh-activation.mjs';
import {C26_REFERENCE,C26_CONFIG,validateVertxC35Activation} from './vertx-c35-activation.mjs';
import {PARENT_REFERENCE,PARENT_CONFIG_ID,PARENT_RUNTIME_SHA256,hash,loadC23PostgresParent,
  validatePostgresC36Runtime,validateC36GosuBuildInfo,effectivePostgresC36Summary}
  from './postgres-c36-adjudication.mjs';

export const C35_KEYCLOAK_REFERENCE='ghcr.io/claidd/otziv-security@sha256:c28364544835f5fbef6d4e0b0b3e63154127f7fb440b05432760f11235b5f4a9';
export const C35_KEYCLOAK_CONFIG='sha256:60a8dd92cf43b8cf909a9e13f231da1f59103f9259cf482185c54856dcfd6a1b';
const SOURCE='infrastructure/runtime-security/';
const C23_ACTIVATION_SHA256='fcc50b0b792637c08a9a74cf51057354d138b6a5a741a344ed6979a3c7a611b3';
const names=['restored_critical_records_exact','capture_stable_tables_restored_exact','same_volume_all_tables_exact',
  'existing_admin_password_login','existing_roles_and_credentials_preserved','unauthenticated_provider_denied',
  'same_volume_rollback_preserves_all_post_upgrade_writes','same_volume_old_issuer_login',
  'same_volume_old_issuer_credentials_preserved','fresh_rollback_restore_preserves_all_post_upgrade_writes',
  'restored_existing_password_login','restored_roles_and_credentials_preserved'];
const safe=path=>typeof path==='string'&&path.startsWith(SOURCE)&&!/[\\:]/.test(path)&&
  path.split('/').every(part=>part&&part!=='.'&&part!=='..');
const sameId=(id,config,reference)=>assert.ok([config,reference.split('@')[1]].includes(id),
  'postgres_c36_activation_inspected_id');

export async function validatePostgresC36ActivationScan(publication,publicationPath,read){
  assert.equal(publication.component,'postgres','postgres_c36_activation_component');
  assert.equal(publication.manifestSet,'c36-postgres','postgres_c36_activation_set');
  assert.match(publication.reference||'',/^ghcr\.io\/claidd\/otziv-security@sha256:[a-f0-9]{64}$/,
    'postgres_c36_activation_reference');
  assert.match(publication.imageId||'',/^sha256:[a-f0-9]{64}$/,'postgres_c36_activation_image');
  assert.ok(safe(publicationPath),'postgres_c36_activation_path');
  const directory=dirname(publicationPath).replaceAll('\\','/');
  const [rawBytes,receiptBytes,runtimeBytes,configBytes,triageBytes]=await Promise.all([
    read(directory+'/vulnerabilities.json'),read(directory+'/vulnerabilities.adjudications.json'),
    read(directory+'/vulnerabilities.runtime-c36.json.gz'),read(directory+'/registry-amd64-config.json'),
    read(directory+'/vulnerabilities.triage.json')]);
  const raw=JSON.parse(rawBytes),receipt=JSON.parse(receiptBytes),runtime=JSON.parse(gunzipSync(runtimeBytes)),
    config=JSON.parse(configBytes),triage=JSON.parse(triageBytes);
  assert.equal('sha256:'+hash(configBytes),publication.imageId,'postgres_c36_activation_registry_config');
  assert.equal(raw.Metadata?.ImageID,publication.imageId,'postgres_c36_activation_report_image');
  assert.deepEqual(raw.Metadata?.ImageConfig?.rootfs,config.rootfs,'postgres_c36_activation_report_layers');
  assert.equal(receipt.schema,'otziv-image-adjudication-v1','postgres_c36_activation_receipt_schema');
  assert.equal(receipt.status,'NOT_APPLICABLE','postgres_c36_activation_foreign_status');
  assert.deepEqual(receipt.decisions,[],'postgres_c36_activation_foreign_decisions');
  assert.equal(receipt.alloy?.status,'NOT_APPLICABLE','postgres_c36_activation_alloy_status');
  assert.deepEqual(receipt.alloy?.decisions,[],'postgres_c36_activation_alloy_decisions');
  for(const item of [receipt,receipt.alloy,receipt.postgres]){
    assert.equal(item.rawReportSha256,hash(rawBytes),'postgres_c36_activation_raw_binding');
    assert.equal(item.imageConfigId,publication.imageId,'postgres_c36_activation_receipt_image');
    assert.equal(item.rawReportModified,false,'postgres_c36_activation_raw_modified');
  }
  assert.deepEqual(triage,buildTriage(raw,rawBytes),'postgres_c36_activation_triage');
  const postgres=receipt.postgres;
  assert.equal(postgres.schema,'otziv-postgres-c36-adjudication-v1','postgres_c36_activation_postgres_schema');
  assert.equal(postgres.status,'EXACT_RUNTIME_VERIFIED','postgres_c36_activation_postgres_unverified');
  sameId(postgres.immutableImageId,publication.imageId,publication.reference);
  assert.equal(postgres.runtimeProofSha256,hash(runtimeBytes),'postgres_c36_activation_runtime_hash');
  assert.equal(runtime.executedScriptSha256,postgres.inspectorSha256,'postgres_c36_activation_inspector_binding');
  assert.equal(runtime.candidate.reference,postgres.immutableImageId,'postgres_c36_activation_inspected_reference');
  const {parent}=await loadC23PostgresParent();
  const delta=validatePostgresC36Runtime(runtime,parent,config,raw,publication.commit);
  assert.equal(postgres.gosu.binarySha256,delta.binarySha256,'postgres_c36_activation_binary');
  assert.deepEqual(postgres.changedFiles,delta.changedFiles,'postgres_c36_activation_delta');
  assert.equal(validateC36GosuBuildInfo(postgres.gosu.buildInfoOutput).reader,postgres.gosu.reader,
    'postgres_c36_activation_build_info');
  const {review,reviewSha256}=await loadPostgresC16Proof();
  assert.equal(postgres.reviewSha256,reviewSha256,'postgres_c36_activation_review');
  const september=await reviewLibxmlSeptemberFindings(raw,review);
  assert.deepEqual(postgres.libxmlSeptember,september??null,'postgres_c36_activation_libxml');
  assert.deepEqual(postgres.decisions,[...matchingPostgresFindings(raw,review),...matchRules(raw,review,POSTGRES_C16_RULES),
    ...matchRules(raw,review,[POSTGRES_LIBXML_PYTHON_RULE]),...(september?.decisions||[])],
  'postgres_c36_activation_decisions');
  effectivePostgresC36Summary(summarizeReport(raw),postgres);
  const summary={...combinedScanSummary(summarizeReport(raw),
    {grafana:receipt,alloy:receipt.alloy,postgres}),scannerImage:TRIVY_IMAGE};
  assert.deepEqual(publication.security,summary,'postgres_c36_activation_security');
  assert.equal(summary.result,'PASS','postgres_c36_activation_scan_failed');
  assert.equal(summary.effectiveBlockingFixedHighOrCritical,0,'postgres_c36_activation_fixed_risk');
  assert.equal(summary.unresolvedRiskReview,'NONE','postgres_c36_activation_unresolved_risk');
  return {publication,postgres,raw,runtime,config};
}

export async function validatePostgresC36Acceptance(entry,read){
  assert.equal(entry.component,'postgres','postgres_c36_acceptance_component');
  const bound=entry.postgresC36Acceptance;
  assert.ok(bound&&safe(bound.path),'postgres_c36_acceptance_missing');
  assert.match(bound.sha256||'',/^[a-f0-9]{64}$/,'postgres_c36_acceptance_hash_missing');
  assert.deepEqual(entry.databaseTransition,bound,'postgres_c36_transition_not_bound');
  const bytes=await read(bound.path);
  assert.equal(hash(bytes),bound.sha256,'postgres_c36_acceptance_hash');
  const value=JSON.parse(bytes);
  assert.equal(value.schema,'otziv-postgres-c36-acceptance-v1','postgres_c36_acceptance_schema');
  assert.equal(value.result,'PASS','postgres_c36_acceptance_failed');
  assert.equal(value.reference,entry.reference,'postgres_c36_acceptance_reference');
  assert.equal(value.parentReference,PARENT_REFERENCE,'postgres_c36_acceptance_parent');
  assert.equal(value.parentConfigId,PARENT_CONFIG_ID,'postgres_c36_acceptance_parent_config');
  for(const field of ['commit','run','attempt'])assert.equal(value[field],entry[field],
    'postgres_c36_acceptance_identity:'+field);
  assert.match(value.imageConfigId||'',/^sha256:[a-f0-9]{64}$/,'postgres_c36_acceptance_image');
  assert.equal(value.pair?.postgres?.reference,entry.reference,'postgres_c36_acceptance_pair_postgres');
  assert.equal(value.pair?.postgres?.imageConfigId,value.imageConfigId,'postgres_c36_acceptance_pair_image');
  assert.equal(value.pair?.keycloak?.reference,C35_KEYCLOAK_REFERENCE,'postgres_c36_acceptance_pair_keycloak');
  assert.equal(value.pair?.keycloak?.imageConfigId,C35_KEYCLOAK_CONFIG,'postgres_c36_acceptance_pair_keycloak_config');
  const publicationDirectory=dirname(entry.publication.path).replaceAll('\\','/');
  const required=[entry.publication.path,value.runtimePath,value.capturePath,value.replayPath,
    publicationDirectory+'/vulnerabilities.json',publicationDirectory+'/vulnerabilities.adjudications.json',
    publicationDirectory+'/vulnerabilities.triage.json',publicationDirectory+'/registry-amd64-config.json'];
  for(const path of required)assert.ok(value.files?.[path],'postgres_c36_acceptance_file_missing:'+path);
  assert.ok(Object.keys(value.files||{}).length>=8,'postgres_c36_acceptance_evidence_incomplete');
  for(const [path,expected]of Object.entries({...value.files,...value.executedSources})){
    assert.ok(safe(path),'postgres_c36_acceptance_unsafe_path');
    assert.match(expected||'',/^[a-f0-9]{64}$/,'postgres_c36_acceptance_invalid_hash');
    assert.equal(hash(await read(path)),expected,'postgres_c36_acceptance_evidence_changed:'+path);
  }
  for(const path of [SOURCE+'postgres-c36-inspection.py',SOURCE+'ssl-refresh-inspection.py',
    SOURCE+'postgres-c36-adjudication.mjs',SOURCE+'postgres-c36-rehearsal.py'])
    assert.ok(value.executedSources?.[path],'postgres_c36_acceptance_source_missing:'+path);
  const publication=JSON.parse(await read(entry.publication.path));
  assert.equal(publication.reference,entry.reference,'postgres_c36_acceptance_publication_reference');
  assert.equal(publication.imageId,value.imageConfigId,'postgres_c36_acceptance_publication_image');
  for(const field of ['commit','run','attempt'])assert.equal(publication[field],entry[field],
    'postgres_c36_acceptance_publication_identity');
  const directory=dirname(entry.publication.path).replaceAll('\\','/');
  assert.equal(value.runtimePath,directory+'/vulnerabilities.runtime-c36.json.gz',
    'postgres_c36_acceptance_runtime_path');
  await validatePostgresC36ActivationScan(publication,entry.publication.path,read);
  return value;
}

export async function validatePostgresC36Rehearsal(value,read){
  const capture=JSON.parse(await read(value.capturePath)),replay=JSON.parse(await read(value.replayPath));
  for(const record of [capture,replay]){
    assert.equal(record.schema,'otziv-postgres-c36-rehearsal-v1','postgres_c36_rehearsal_schema');
    assert.equal(record.result,'PASS','postgres_c36_rehearsal_failed');
    assert.equal(record.productionWrites,false,'postgres_c36_rehearsal_production_write');
    assert.equal(record.privateRowsPublished,false,'postgres_c36_rehearsal_private_rows');
    assert.equal(record.publishedPorts,0,'postgres_c36_rehearsal_published_ports');
    assert.equal(record.sourceScope,'LOCAL_PROD_LIKE_KEYCLOAK','postgres_c36_rehearsal_scope');
    assert.equal(record.sourcePostgres,PARENT_REFERENCE,'postgres_c36_rehearsal_parent');
    assert.equal(record.sourcePostgresConfig,PARENT_CONFIG_ID,'postgres_c36_rehearsal_parent_config');
    assert.equal(record.keycloak,C35_KEYCLOAK_REFERENCE,'postgres_c36_rehearsal_issuer');
    assert.equal(record.keycloakConfig,C35_KEYCLOAK_CONFIG,'postgres_c36_rehearsal_issuer_config');
    assert.equal(record.executedScriptSha256,value.executedSources[SOURCE+'postgres-c36-rehearsal.py'],
      'postgres_c36_rehearsal_script');
    assert.ok(record.checks?.length&&record.checks.every(check=>check.passed===true),
      'postgres_c36_rehearsal_incomplete');
  }
  for(const name of names)assert.equal(replay.checks.filter(check=>check.name===name&&check.passed).length,1,
    'postgres_c36_rehearsal_missing_check:'+name);
  assert.equal(replay.candidatePostgres,value.reference,'postgres_c36_rehearsal_candidate');
  sameId(replay.candidatePostgresLocalId,value.imageConfigId,value.reference);
  assert.equal(replay.candidate,C35_KEYCLOAK_REFERENCE,'postgres_c36_rehearsal_candidate_keycloak');
  sameId(replay.candidateLocalId,C35_KEYCLOAK_CONFIG,C35_KEYCLOAK_REFERENCE);
  assert.equal(replay.dumpSha256,capture.dumpSha256,'postgres_c36_rehearsal_dump');
  assert.deepEqual(replay.ownedResourcesRemaining,{container:0,volume:0,network:0},
    'postgres_c36_rehearsal_cleanup');
  assert.ok(!replay.cleanupErrors?.length,'postgres_c36_rehearsal_cleanup_errors');
  assert.equal(replay.tableCount,102,'postgres_c36_rehearsal_table_coverage');
  for(const field of ['serverVersion','encoding','locale','clusterIdentifier','extensions','configSha256','hbaSha256','identSha256'])
    assert.deepEqual(replay.candidateDatabase?.[field],replay.sourceDatabase?.[field],
      'postgres_c36_rehearsal_database_changed:'+field);
  return replay;
}

export async function validatePostgresC36Transition(entry,image,read){
  const value=await validatePostgresC36Acceptance(entry,read);
  await validatePostgresC36Rehearsal(value,read);
  const parentBytes=await read(SOURCE+'c36-parent-postgres.json');
  assert.equal(hash(parentBytes),C23_ACTIVATION_SHA256,'postgres_c36_parent_activation_changed');
  const parent=JSON.parse(parentBytes);
  assert.equal(parent.reference,PARENT_REFERENCE,'postgres_c36_parent_activation_reference');
  const prior=await validateSslRefreshTransition(parent,image,read);
  assert.equal(prior.postgresReference,PARENT_REFERENCE,'postgres_c36_transition_parent');
  assert.equal(prior.postgresConfigId,PARENT_CONFIG_ID,'postgres_c36_transition_parent_config');
  assert.equal(prior.requiredKeycloakReference,C26_REFERENCE,'postgres_c36_transition_previous_keycloak');
  assert.equal(prior.requiredKeycloakConfigId,C26_CONFIG,'postgres_c36_transition_previous_keycloak_config');
  return {...prior,postgresReference:entry.reference,postgresConfigId:value.imageConfigId,
    postgresC36Acceptance:entry.postgresC36Acceptance,
    postgresC36Pair:{keycloakReference:C35_KEYCLOAK_REFERENCE,keycloakConfigId:C35_KEYCLOAK_CONFIG},
    ordinaryDeploymentUpgradeAuthorized:false};
}

export async function couplePostgresC36WithKeycloakC35(readiness,entry,read,validateParent){
  assert.ok(readiness.postgresC36Acceptance,'postgres_c36_coupling_acceptance_missing');
  assert.equal(readiness.postgresC36Pair?.keycloakReference,C35_KEYCLOAK_REFERENCE,
    'postgres_c36_coupling_issuer_pair');
  assert.equal(readiness.postgresC36Pair?.keycloakConfigId,C35_KEYCLOAK_CONFIG,
    'postgres_c36_coupling_issuer_config');
  assert.equal(entry?.reference,C35_KEYCLOAK_REFERENCE,'postgres_c36_coupling_registry_issuer');
  const publication=JSON.parse(await read(entry.publication.path));
  const accepted=await validateVertxC35Activation(publication,entry,read,validateParent);
  assert.equal(accepted.imageConfigId,C35_KEYCLOAK_CONFIG,'postgres_c36_coupling_accepted_issuer');
  assert.deepEqual(entry.migrationAcceptance,entry.vertxRefreshAcceptance,
    'postgres_c36_coupling_issuer_acceptance');
  // The C35 proof independently covers its earlier C23 pairing. The new C36
  // proof additionally covers the exact active C35 issuer on the C36 volume.
  return {...readiness,requiredKeycloakReference:entry.reference,
    requiredKeycloakConfigId:C35_KEYCLOAK_CONFIG,
    acceptancePath:entry.vertxRefreshAcceptance.path,proofSha256:entry.vertxRefreshAcceptance.sha256};
}
