import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {gunzipSync} from 'node:zlib';
import {validatePostgresActivationScan as legacyScan} from './postgres-activation-proof.mjs';
import {validatePostgresTransitionReadiness as legacyTransition} from './postgres-transition-readiness.mjs';
import {adjudicatePostgresC16Observed,POSTGRES_C16_REVIEW_SHA256} from './postgres-c16-adjudication.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {buildTriage} from './triage-report.mjs';

export const POSTGRES_C16_REFERENCE='ghcr.io/claidd/otziv-security@sha256:a30a580feb45fce65b87d21a104d407d3d1c5b255ae0e9a05ce9903e10adb270';
const CONFIG='sha256:9aff3268aa60897853557e45444da2f6c1d45e311eb543e9eb8e0f49339c5f6e';
const PARENT='ghcr.io/claidd/otziv-security@sha256:07834abbfd80ed7183afa9096db99afc4d93b99fd51aae726e550d07ade65dbf';
const ROOT='infrastructure/runtime-security/proofs/c16-postgres-published/';
export const POSTGRES_C16_ACCEPTANCE_SHA256='01e5746ed52eb5f52a0313df7e785047898870ef0c4ffd052f2d016c29fd41d8';
const hash=x=>createHash('sha256').update(x).digest('hex');
const safePath=x=>typeof x==='string'&&x.length>0&&!x.startsWith('/')&&!/[\\:]/.test(x)&&x.split('/').every(p=>p&&p!=='.'&&p!=='..');
const withoutLocalId=x=>{const {immutableImageId,...rest}=x;return rest;};

async function accepted(read){
  const cache=new Map();
  const bytes=async path=>{assert.ok(safePath(path),'postgres_c16_activation_path');
    if(!cache.has(path))cache.set(path,Promise.resolve(read(path)).then(Buffer.from));return cache.get(path);};
  const json=async path=>JSON.parse(await bytes(path));
  const reviewBytes=await bytes(ROOT+'acceptance.json');
  assert.equal(hash(reviewBytes),POSTGRES_C16_ACCEPTANCE_SHA256,'postgres_c16_activation_anchor');
  const review=JSON.parse(reviewBytes);
  assert.equal(review.schema,'otziv-postgres-c16-published-acceptance-v1','postgres_c16_activation_schema');
  assert.equal(review.result,'PASS','postgres_c16_activation_failed');
  assert.equal(review.reference,POSTGRES_C16_REFERENCE,'postgres_c16_activation_reference');
  assert.equal(review.imageConfigId,CONFIG,'postgres_c16_activation_config');
  assert.equal(review.sourceReviewSha256,POSTGRES_C16_REVIEW_SHA256,'postgres_c16_activation_source');
  for(const [path,sha]of Object.entries(review.files)){
    assert.ok(safePath(path),'postgres_c16_activation_evidence_path');
    assert.equal(hash(await bytes(ROOT+path)),sha,'postgres_c16_activation_evidence_changed');
  }
  for(const [path,sha]of Object.entries(review.executedSources))
    assert.equal(hash(await bytes(path)),sha,'postgres_c16_activation_executed_source_changed');
  return {review,bytes,json};
}

export async function validatePostgresActivationScan(publication,path,read){
  if(publication?.manifestSet!=='c16-postgres')return legacyScan(publication,path,read);
  const {review,bytes,json}=await accepted(read);
  assert.equal(path,ROOT+'publication/publication.json','postgres_c16_activation_publication_path');
  assert.deepEqual(publication,await json(path),'postgres_c16_activation_publication_bytes');
  assert.equal(publication.reference,POSTGRES_C16_REFERENCE,'postgres_c16_activation_publication_reference');
  assert.equal(publication.imageId,CONFIG,'postgres_c16_activation_publication_config');
  for(const key of ['commit','run','attempt'])assert.equal(publication[key],review[key],'postgres_c16_activation_publication_identity');
  const rawBytes=await bytes(ROOT+'publication/vulnerabilities.json'),raw=JSON.parse(rawBytes);
  const receipt=await json(ROOT+'publication/vulnerabilities.adjudications.json');
  for(const item of [receipt,receipt.alloy]){
    assert.equal(item.status,'NOT_APPLICABLE','postgres_c16_activation_foreign_adjudication');
    assert.deepEqual(item.decisions,[],'postgres_c16_activation_foreign_decisions');
    assert.equal(item.rawReportSha256,hash(rawBytes),'postgres_c16_activation_report_binding');
    assert.equal(item.imageConfigId,CONFIG,'postgres_c16_activation_report_image');
    assert.equal(item.rawReportModified,false,'postgres_c16_activation_report_modified');
  }
  assert.deepEqual(await json(ROOT+'publication/vulnerabilities.triage.json'),buildTriage(raw,rawBytes),'postgres_c16_activation_triage');
  const configBytes=await bytes(ROOT+'publication/registry-amd64-config.json'),config=JSON.parse(configBytes);
  assert.equal('sha256:'+hash(configBytes),CONFIG,'postgres_c16_activation_registry_config');
  const captured=JSON.parse(gunzipSync(await bytes(ROOT+'published-observed-runtime.json.gz')));
  assert.deepEqual(captured.inspected.Config,config.config,'postgres_c16_activation_observed_config');
  assert.deepEqual(captured.inspected.RootFS.Layers,config.rootfs.diff_ids,'postgres_c16_activation_observed_layers');
  assert.ok([CONFIG,POSTGRES_C16_REFERENCE.split('@')[1]].includes(receipt.postgres?.immutableImageId),'postgres_c16_activation_inspected_image');
  const replay=await adjudicatePostgresC16Observed(raw,rawBytes,receipt.postgres.immutableImageId,captured.inspected,captured.observed);
  assert.deepEqual(replay,receipt.postgres,'postgres_c16_activation_exact_adjudication');
  assert.deepEqual(withoutLocalId(await json(ROOT+'replayed-adjudication.json')),withoutLocalId(replay),'postgres_c16_activation_independent_replay');
  const verification=await json(ROOT+'verification.json');
  for(const key of ['reference','imageConfigId','sourceReviewSha256','commit','run','attempt'])
    assert.equal(verification[key],review[key],'postgres_c16_activation_verification_binding');
  assert.equal(verification.result,'PASS','postgres_c16_activation_verification_failed');
  assert.equal(verification.registryAndAnonymousEvidenceVerified,true,'postgres_c16_activation_registry_missing');
  assert.equal(verification.independentStoppedExportVerified,true,'postgres_c16_activation_inspection_missing');
  assert.equal(verification.inspectedBinaryExecuted,false,'postgres_c16_activation_inspection_method');
  assert.equal(verification.runtimeIdentitySha256,replay.runtimeIdentitySha256,'postgres_c16_activation_runtime_binding');
  const summary={...combinedScanSummary(summarizeReport(raw),{grafana:receipt,alloy:receipt.alloy,postgres:replay}),scannerImage:TRIVY_IMAGE};
  assert.equal(summary.result,'PASS','postgres_c16_activation_unresolved_findings');
  assert.deepEqual(summary,publication.security,'postgres_c16_activation_security_summary');
  assert.deepEqual(summary,verification.security,'postgres_c16_activation_independent_summary');
  return {schema:'otziv-postgres-c16-activation-scan-v1',result:'PASS',reference:POSTGRES_C16_REFERENCE,imageConfigId:CONFIG,
    sourceReviewSha256:POSTGRES_C16_REVIEW_SHA256,publishedProofSha256:POSTGRES_C16_ACCEPTANCE_SHA256,
    effectiveBlockingFixedHighOrCritical:0,effectiveUnfixedHighOrCritical:0,databaseTransitionAuthorized:false};
}

export async function validatePostgresTransitionReadiness(entry,image,read){
  if(entry?.reference!==POSTGRES_C16_REFERENCE)return legacyTransition(entry,image,read);
  const {review,json}=await accepted(read);
  assert.equal(entry.component,'postgres','postgres_c16_transition_component');
  assert.deepEqual(entry.databaseTransition,{path:ROOT+'acceptance.json',sha256:POSTGRES_C16_ACCEPTANCE_SHA256},'postgres_c16_transition_reference');
  const parent=await json(ROOT+'parent-activation.json');
  assert.equal(parent.reference,PARENT,'postgres_c16_transition_parent');
  // Replay the existing Keycloak acceptance unchanged; the PCRE2 patch does not
  // grant authority to substitute another issuer or to bypass its migration proof.
  const previous=await legacyTransition(parent,image,read);
  const capture=await json(ROOT+'rehearsal/capture.json'),replay=await json(ROOT+'rehearsal/replay.json');
  for(const record of [capture,replay]){
    assert.equal(record.schema,'otziv-postgres-c16-rehearsal-v1','postgres_c16_transition_rehearsal_schema');
    assert.equal(record.result,'PASS','postgres_c16_transition_rehearsal_failed');
    assert.equal(record.productionWrites,false,'postgres_c16_transition_production_write');
    assert.equal(record.privateRowsPublished,false,'postgres_c16_transition_private_rows');
    assert.equal(record.publishedPorts,0,'postgres_c16_transition_exposed_rehearsal');
    assert.equal(record.sourcePostgres,PARENT,'postgres_c16_transition_source_image');
    assert.equal(record.keycloak,previous.requiredKeycloakReference,'postgres_c16_transition_issuer_image');
    assert.equal(record.keycloakConfig,previous.requiredKeycloakConfigId,'postgres_c16_transition_issuer_config');
    assert.equal(record.executedScriptSha256,review.executedSources['infrastructure/runtime-security/postgres-c16-rehearsal.py'],
      'postgres_c16_transition_executed_rehearsal');
    assert.ok(record.checks.length>0&&record.checks.every(x=>x.passed===true),'postgres_c16_transition_incomplete_checks');
  }
  assert.equal(replay.candidate,POSTGRES_C16_REFERENCE,'postgres_c16_transition_tested_image');
  assert.ok([CONFIG,POSTGRES_C16_REFERENCE.split('@')[1]].includes(replay.candidateLocalId),'postgres_c16_transition_tested_digest');
  assert.equal(replay.dumpSha256,capture.dumpSha256,'postgres_c16_transition_snapshot_binding');
  assert.deepEqual(replay.ownedResourcesRemaining,{container:0,volume:0,network:0},'postgres_c16_transition_cleanup');
  assert.ok(!replay.cleanupErrors?.length,'postgres_c16_transition_cleanup_errors');
  const required=['restored_critical_records_exact','capture_stable_tables_restored_exact','same_volume_all_tables_exact',
    'existing_admin_password_login','existing_roles_and_credentials_preserved','unauthenticated_provider_denied',
    'same_volume_rollback_preserves_all_post_upgrade_writes','fresh_rollback_restore_preserves_all_post_upgrade_writes',
    'restored_existing_password_login','restored_roles_and_credentials_preserved'];
  for(const name of required)assert.equal(replay.checks.filter(x=>x.name===name&&x.passed).length,1,'postgres_c16_transition_check_missing');
  assert.equal(replay.tableCount,102,'postgres_c16_transition_restored_table_coverage');
  for(const key of ['serverVersion','encoding','locale','clusterIdentifier','extensions','configSha256','hbaSha256','identSha256'])
    assert.deepEqual(replay.candidateDatabase[key],replay.sourceDatabase[key],'postgres_c16_transition_database_changed');
  await validatePostgresActivationScan(await json(ROOT+'publication/publication.json'),ROOT+'publication/publication.json',read);
  return {...previous,postgresReference:POSTGRES_C16_REFERENCE,postgresConfigId:CONFIG,
    c16Acceptance:{path:ROOT+'acceptance.json',sha256:POSTGRES_C16_ACCEPTANCE_SHA256},
    ordinaryDeploymentUpgradeAuthorized:false};
}
