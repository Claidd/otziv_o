import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {dirname} from 'node:path';
import {validatePostgresActivationScan} from './postgres-activation-proof.mjs';
import {checkKeycloakRuntimeDependencies} from './keycloak-runtime-dependencies.mjs';
import {summarizeReport} from './scan.mjs';

const CONTEXT='infrastructure/keycloak/security-generation/c14-migration-fix/';
const FOUNDATION_PATH=CONTEXT+'proofs/review.json';
const FOUNDATION_SHA256='494f8290dbd87f96f6dcf09480ad82c2fcc250e953d4bce0582efa5835c53faa';
const ACCEPTANCE_PATH=CONTEXT+'proofs/publication-acceptance.json';
// Exact c6ee publication: independent full file graph review, actual-source 52
// checks, issuer 20 checks and a fresh post-run source/cleanup observation.
// A new build must repeat those checks; rehashing an outer PASS is insufficient.
const REVIEWED_PUBLISHED_ACCEPTANCE_SHA256='b4f1ce533dd116b4546d8fa6cd6e27731e357865d08cb100e9f33332511b1dfd';
const PG_ROOT='infrastructure/runtime-security/proofs/c14-postgres-published/';
// Failed/cancelled publication attempts remain separate historical evidence.
// The accepted pair also binds exact run/attempt/commit in both OCI receipts.
const KC_ROOT='infrastructure/runtime-security/proofs/c14-keycloak-published/final/';
const PG_REFERENCE='ghcr.io/claidd/otziv-security@sha256:07834abbfd80ed7183afa9096db99afc4d93b99fd51aae726e550d07ade65dbf';
const PG_CONFIG='sha256:d257683e1d6febdfb869b77c60683bc598b272788d60e65893a109adaf0a2db4';
const SOURCE_PG='postgres@sha256:a426e44bac0b759c95894d68e1a0ac03ecc20b619f498a91aae373bf06d8508d';
const SOURCE_KC='quay.io/keycloak/keycloak@sha256:4883630ef9db14031cde3e60700c9a9a8eaf1b5c24db1589d6a2d43de38ba2a9';
const HASH=/^[a-f0-9]{64}$/;
const IMAGE=/^ghcr\.io\/claidd\/otziv-security@sha256:[a-f0-9]{64}$/;
const CONFIG=/^sha256:[a-f0-9]{64}$/;
const sha256=bytes=>createHash('sha256').update(bytes).digest('hex');
const digest=reference=>reference.split('@')[1];
const FIELDS=['configSha256','hbaSha256','identSha256','clusterIdentifier','roleCounts','schemaCounts','applicationObjects','encoding','locale','extensions'];
const REFERENCES=['compose.yaml','compose.prod-local.yaml','docker-compose.yaml'].map(path=>({path,service:'keycloak'}));
const ACTUAL_GATES=[
  'private_acl_exclusive','local_docker_only','internal_network','original_dump_critical_hashes_exact',
  'fixed-kc_oidc_discovery','fixed-kc_health_ready','fixed-kc_existing_admin_read','existing_password_login_without_reset',
  ...['clean_migration','recovered_migration'].flatMap(prefix=>[
    ...['realm','user_entity','client','credential'].map(table=>prefix+'_'+table+'_unchanged'),
    ...['keycloak_role','user_role_mapping'].map(table=>prefix+'_'+table+'_existing_records_preserved'),
    ...['only_expected_organization_role_additions','all_organization_roles','admin_role_composites','view_implies_query'].map(name=>prefix+'_'+name)]),
  'provider_unauthenticated_endpoint_denied','provider_schema_present','second_start_critical_records_idempotent',
  'second_start_all_organization_roles','second_start_admin_role_composites','second_start_view_implies_query','second_start_organization_roles_idempotent',
  'clean_stop_fixed-kc','clean_stop_clean-pg','injected_role_write_failure_stops_startup',
  'failure_after_at_least_three_actual_role_inserts','fresh_transaction_no_partial_critical_model_changes',
  'recovered-kc_oidc_discovery','recovered-kc_health_ready','recovered-kc_existing_admin_read','clean_stop_recovered-kc','clean_stop_fault-pg',
  'fresh_17_10_rollback_all_88_tables','rollback-kc_oidc_discovery','rollback-kc_health_ready','rollback-kc_existing_admin_read',
  'clean_stop_rollback-kc','fresh_rollback_old_keycloak_critical_hashes','clean_stop_rollback-pg',
];
const ISSUER_GATES=[
  'provision_plan_is_read_only','production_provisioner_preserves_nested_otp_flow_and_realm_policy',
  'provision_reentry_does_not_clone_another_authenticator','actual_password_login_captures_current_generation',
  'browser_password_pkce_login_captures_immutable_generation','ordinary_user_cannot_read_generation_authority',
  'remove_then_restore_role_does_not_revive_old_generation','refresh_preserves_original_generation',
  'new_login_after_role_change_is_usable','disable_then_enable_does_not_revive_old_generation',
  'offline_login_has_immutable_generation','offline_refresh_cannot_acquire_current_generation',
  'late_exact_session_delete_does_not_revoke_fresh_login','issuer_restart_preserves_generation_tombstone',
  'fresh_login_after_restart_works','listener_disable_and_reenable_cannot_erase_security_history',
  'disabled_authority_fails_closed_and_reactivation_fences_existing_sessions','failed_journal_rejects_admin_security_mutation',
  'failed_journal_rolls_back_actual_user_state','failed_mutation_does_not_advance_generation',
];

function evidenceReader(read){
  assert.equal(typeof read,'function','postgres_transition_reader');
  const cache=new Map();
  async function bytes(path){
    assert.ok(typeof path==='string'&&(path.startsWith(CONTEXT)||path.startsWith('infrastructure/runtime-security/'))&&
      !path.includes('\\')&&!path.includes(':')&&path.split('/').every(part=>part&&part!=='.'&&part!=='..'),'postgres_transition_evidence_path');
    if(!cache.has(path))cache.set(path,Promise.resolve(read(path)).then(value=>Buffer.from(value)));
    return cache.get(path);
  }
  async function bound(ref){
    assert.deepEqual(Object.keys(ref||{}).sort(),['path','sha256'],'postgres_transition_evidence_fields');
    assert.match(ref.sha256||'',HASH,'postgres_transition_evidence_hash');
    const value=await bytes(ref.path);assert.equal(sha256(value),ref.sha256,'postgres_transition_evidence_changed_'+ref.path);return value;
  }
  return {bytes,bound,json:async ref=>JSON.parse(await bound(ref))};
}
function passed(checks,required,label,exact=true){
  assert.ok(Array.isArray(checks),'postgres_transition_'+label+'_checks');
  const names=checks.map(check=>check.name);
  assert.equal(new Set(names).size,names.length,'postgres_transition_'+label+'_duplicate_check');
  if(exact){assert.deepEqual([...names].sort(),[...required].sort(),'postgres_transition_'+label+'_coverage');assert.ok(checks.every(check=>check.passed===true),'postgres_transition_'+label+'_failed');}
  else for(const name of required)assert.equal(checks.find(check=>check.name===name)?.passed,true,'postgres_transition_'+label+'_'+name);
}
function time(value,label){const n=Date.parse(value);assert.ok(Number.isFinite(n),'postgres_transition_'+label+'_time');return n;}

/** Checks evidence semantics only; this does not authenticate the receipt or authorize deployment. */
export function checkKeycloakMigrationRuntime(actual,issuer,expected){
  assert.equal(actual.schema,'otziv-keycloak-actual-migration-fix-v1','postgres_transition_actual_schema');
  for(const key of ['result','cleanMigrationResult','freshRollbackResult'])assert.equal(actual[key],'PASS','postgres_transition_actual_'+key);
  assert.equal(actual.productionAccess,false,'postgres_transition_actual_production');assert.equal(actual.privateRowsPublished,false,'postgres_transition_private_rows');
  assert.equal(actual.candidate,expected.keycloakReference,'postgres_transition_actual_candidate');
  assert.equal(actual.imageId,expected.keycloakImageId,'postgres_transition_actual_image');
  assert.equal(actual.postgresImageId,digest(PG_REFERENCE),'postgres_transition_actual_postgres');
  assert.equal(actual.sourceDumpSha256,expected.dumpSha256,'postgres_transition_actual_dump');
  assert.equal(actual.scriptSha256,expected.scriptSha256,'postgres_transition_actual_script');
  assert.equal(actual.cleanupErrors,undefined,'postgres_transition_actual_cleanup');
  assert.equal(actual.errorCode,undefined,'postgres_transition_actual_error');
  passed(actual.checks,ACTUAL_GATES,'actual');
  assert.equal(actual.injectedFailureCode,'keycloak_early_exit_fault-kc','postgres_transition_fault_not_injected');
  assert.ok(Number.isInteger(actual.injectedRoleInsertAttempts)&&actual.injectedRoleInsertAttempts>=4,'postgres_transition_fault_too_early');
  assert.equal(actual.providerTableCount,1,'postgres_transition_provider_missing');
  for(const role of ['fixed-kc','recovered-kc','rollback-kc'])assert.deepEqual(actual.keycloakChecks?.[role],{adminCredentialsAvailable:true,adminRead:true,realmCount:2},'postgres_transition_existing_login_'+role);
  for(const phase of ['clean_migration','second_start','recovered_migration'])assert.deepEqual(actual.organizationRoles?.[phase],
    {adminClients:3,rolesPresent:9,expectedRoles:9,adminComposites:9,viewQueryComposites:3},'postgres_transition_organization_roles_'+phase);
  const counts={realm:2,user_entity:32,client:16,credential:31,keycloak_role:89,user_role_mapping:67};
  for(const phase of ['clean_migration','recovered_migration'])assert.deepEqual(actual.criticalCounts?.[phase],Object.fromEntries(
    Object.entries(counts).map(([table,count])=>[table,{source:count,current:count+(table==='keycloak_role'?9:0)}])),'postgres_transition_critical_counts_'+phase);
  assert.ok(time(actual.completedAt,'actual_completed')>=time(actual.startedAt,'actual_started'),'postgres_transition_actual_chronology');
  assert.equal(issuer.schema,'otziv-issuer-generation-proof-v1','postgres_transition_issuer_schema');assert.equal(issuer.result,'PASS','postgres_transition_issuer_result');
  assert.equal(issuer.production,false,'postgres_transition_issuer_production');assert.equal(issuer.realProviderLogin,true,'postgres_transition_issuer_login');
  assert.equal(issuer.image,expected.keycloakReference,'postgres_transition_issuer_candidate');assert.equal(issuer.imageId,expected.keycloakImageId,'postgres_transition_issuer_image');
  assert.equal(issuer.postgresImage,PG_REFERENCE,'postgres_transition_issuer_postgres_reference');assert.equal(issuer.postgresImageId,digest(PG_REFERENCE),'postgres_transition_issuer_postgres');
  assert.equal(issuer.cleanupErrors,undefined,'postgres_transition_issuer_cleanup');
  passed(issuer.checks,ISSUER_GATES,'issuer');
  assert.ok(time(issuer.finishedAt,'issuer_finished')>=time(issuer.startedAt,'issuer_started'),'postgres_transition_issuer_chronology');
}

/** A post-run metadata observation, not a claim that live production rows stopped changing. */
export function checkPostgresSourceContinuity(before,after,continuity,expected){
  for(const item of [before,after]){
    assert.equal(item.schema,'otziv-vps-postgres-readonly-inventory-v1','postgres_transition_inventory_schema');
    assert.equal(item.result,'PASS','postgres_transition_inventory_failed');assert.equal(item.readOnly,true,'postgres_transition_inventory_writes');
    assert.equal(item.dumpCreated,false,'postgres_transition_inventory_dump');assert.equal(item.keycloakDatabaseVendor,'postgres','postgres_transition_inventory_vendor');
  }
  assert.equal(continuity.schema,'otziv-keycloak-migration-source-continuity-v1','postgres_transition_continuity_schema');
  assert.equal(continuity.result,'PASS','postgres_transition_continuity_failed');
  assert.equal(continuity.productionWrites,false,'postgres_transition_continuity_writes');
  assert.equal(continuity.sourceRowSnapshotEqualityAfterRehearsalClaimed,false,'postgres_transition_live_rows_claim');
  assert.equal(continuity.sourceContainerImageConfigVolumeAndCatalogFootprintUnchanged,true,'postgres_transition_continuity_changed');
  assert.deepEqual(continuity.comparedPostgresFields,FIELDS,'postgres_transition_continuity_coverage');
  assert.equal(continuity.sourceBeforeSha256,expected.beforeSha256,'postgres_transition_continuity_before');
  assert.equal(continuity.sourceAfterSha256,expected.afterSha256,'postgres_transition_continuity_after');
  assert.deepEqual(continuity.ownedRehearsalResourcesRemaining,{containers:0,volumes:0,networks:0},'postgres_transition_resources_retained');
  assert.deepEqual(after.source,before.source,'postgres_transition_source_container_changed');
  assert.deepEqual(after.volume,before.volume,'postgres_transition_source_volume_changed');
  for(const key of FIELDS)assert.deepEqual(after.postgres?.[key],before.postgres?.[key],'postgres_transition_source_'+key);
  assert.ok(time(after.observedAt,'inventory_observed')>=time(expected.completedAt,'replay_completed'),'postgres_transition_inventory_stale');
  assert.ok(time(continuity.observedAt,'continuity_observed')>=time(after.observedAt,'inventory_observed'),'postgres_transition_continuity_stale');
}

/** Runtime file review is necessary but expressly insufficient to transfer a migration result. */
export function checkPublishedKeycloakRuntimeComparison(comparison,expected){
  assert.equal(comparison.schema,'otziv-published-keycloak-v3-rootfs-comparison-v1','postgres_transition_comparison_schema');
  assert.equal(comparison.result,'PASS','postgres_transition_comparison_failed');
  assert.equal(comparison.image,expected.reference,'postgres_transition_comparison_image');assert.equal(comparison.config,expected.config,'postgres_transition_comparison_config');
  assert.equal(comparison.localV3Image,expected.localImage,'postgres_transition_comparison_local_image');
  assert.equal(comparison.localV3Config,expected.localConfig,'postgres_transition_comparison_local_config');
  assert.equal(comparison.sourceReviewSha256,expected.sourceReviewSha256,'postgres_transition_comparison_source_review');
  assert.equal(comparison.runtimeInventorySha256,expected.inventorySha256,'postgres_transition_comparison_inventory');
  assert.deepEqual(comparison.sourceBuildInputsSha256,expected.buildInputs,'postgres_transition_comparison_recipe');
  for(const key of ['actualProvenanceByteEqual','actualPulledConfigurationMatchesRegistry','actualPulledRootfsMatchesRegistry','publishedRuntimeReplayRequired'])
    assert.equal(comparison[key],true,'postgres_transition_comparison_'+key);
  assert.equal(comparison.localV3RuntimeReceiptTransferAllowed,false,'postgres_transition_comparison_local_transfer');
  assert.deepEqual(comparison.unexpectedPaths,[],'postgres_transition_comparison_unexpected_paths');
  assert.deepEqual(comparison.dockerGeneratedFilesExcluded,['etc/hosts','etc/hostname','etc/resolv.conf'],'postgres_transition_comparison_exclusions');
  assert.deepEqual(comparison.runtimeConfigurationChangedKeys,['Labels'],'postgres_transition_comparison_launch');
  assert.deepEqual(comparison.publicationLabelChangedKeys,['com.otziv.publication.revision'],'postgres_transition_comparison_labels');
  assert.deepEqual(comparison.patchedVendorJarsByteEqual,{
    'opt/keycloak/lib/lib/main/org.keycloak.keycloak-model-infinispan-26.7.3.jar':true,
    'opt/keycloak/lib/lib/main/org.keycloak.keycloak-model-storage-private-26.7.3.jar':true,
  },'postgres_transition_comparison_vendor_jars');
}

async function foundation(reader){
  const review=await reader.json({path:FOUNDATION_PATH,sha256:FOUNDATION_SHA256});
  assert.equal(review.schema,'otziv-postgres-keycloak-transition-review-v1','postgres_transition_foundation_schema');
  assert.equal(review.result,'PASS','postgres_transition_foundation_failed');assert.equal(review.scope,'EXACT_LOCAL_CANDIDATE_PREPARATION','postgres_transition_foundation_scope');
  assert.equal(review.source.postgresReference,SOURCE_PG,'postgres_transition_source_pg');assert.equal(review.source.keycloakReference,SOURCE_KC,'postgres_transition_source_kc');
  assert.equal(review.testedCandidate.postgresReference,PG_REFERENCE,'postgres_transition_published_pg');assert.equal(review.testedCandidate.postgresConfigId,PG_CONFIG,'postgres_transition_published_pg_config');
  assert.deepEqual(review.publicationAcceptance.result,'REQUIRED_EXACT_PUBLISHED_RUNTIME_REPLAY','postgres_transition_local_not_published');
  assert.equal(review.publicationAcceptance.publishedKeycloakAccepted,false,'postgres_transition_local_not_published');
  const e=review.evidence;
  await Promise.all(Object.values(e).map(ref=>reader.bound(ref)));
  const [capture,rollback,before,after,continuity,actual,issuer,inputs,security]=await Promise.all([
    e.sourceCapture,e.originalFailureAndRollback,e.sourceInventoryBefore,e.sourceInventoryAfter,e.sourceContinuityCleanup,
    e.actualMigration,e.issuerProtocol,e.sourceInputs,e.securityReview].map(ref=>reader.json(ref)));
  assert.equal(capture.schema,'otziv-actual-vps-postgres-rehearsal-v1','postgres_transition_capture_schema');
  assert.equal(capture.result,'FAIL','postgres_transition_historical_failure_erased');assert.equal(capture.errorCode,'keycloak_early_exit_target-kc','postgres_transition_historical_failure_changed');
  assert.equal(capture.productionWrites,false,'postgres_transition_capture_writes');assert.equal(capture.source.dumpSha256,review.source.dumpSha256,'postgres_transition_capture_dump');
  assert.equal(capture.captureStableTables,88,'postgres_transition_capture_tables');
  assert.equal(capture.images.oldPostgres.reference,SOURCE_PG,'postgres_transition_capture_pg');assert.equal(capture.images.oldKeycloak.reference,SOURCE_KC,'postgres_transition_capture_kc');
  for(const role of ['postgres','keycloak']){
    assert.equal(capture.source.containers[role].id,before.source[role].containerId,'postgres_transition_capture_container');
    assert.equal(capture.source.containers[role].imageId,before.source[role].imageId,'postgres_transition_capture_config');
  }
  const phase=review.composition.sameVolumePostgresUpgrade;
  assert.equal(phase.recordResult,'FAIL','postgres_transition_composition_failure');assert.equal(phase.acceptedPhase,'POSTGRES_ENGINE_AND_OLD_KEYCLOAK_ONLY','postgres_transition_composition_scope');
  assert.equal(capture.images.newPostgres.id,phase.testedPostgresManifestDigest,'postgres_transition_engine_image');
  passed(capture.checks,phase.requiredPassedChecks,'engine',false);
  assert.equal(rollback.result,'PASS','postgres_transition_historical_rollback');assert.equal(rollback.rollbackResult,'PASS','postgres_transition_historical_rollback');
  assert.equal(rollback.targetFailureReproduced,true,'postgres_transition_original_failure_preserved');
  const preparation=review.composition.fixedKeycloakMigration;
  assert.equal(preparation.databasePreparation,'FRESH_PUBLISHED_POSTGRES_RESTORE_OF_ORIGINAL_17_10_DUMP','postgres_transition_composition_restore');
  assert.equal(preparation.sameVolumeEngineUpgradeRepeated,false,'postgres_transition_composition_false_claim');
  checkKeycloakMigrationRuntime(actual,issuer,{keycloakReference:review.testedCandidate.keycloakReference,keycloakImageId:review.testedCandidate.keycloakManifestDigest,
    dumpSha256:review.source.dumpSha256,scriptSha256:e.executedReplay.sha256});
  checkPostgresSourceContinuity(before,after,continuity,{beforeSha256:e.sourceInventoryBefore.sha256,afterSha256:e.sourceInventoryAfter.sha256,completedAt:actual.completedAt});
  assert.equal(inputs.schema,'otziv-keycloak-c14-source-inputs-v1','postgres_transition_inputs_schema');
  for(const [path,hash]of Object.entries({...inputs.buildInputs,...inputs.executedReplay}))await reader.bound({path:CONTEXT+path,sha256:hash});
  assert.equal(security.result,'PASS','postgres_transition_local_security_failed');
  assert.equal(security.config,review.testedCandidate.keycloakConfigId,'postgres_transition_local_security_config');
  assert.equal(security.image,review.testedCandidate.keycloakManifestDigest,'postgres_transition_local_security_image');
  for(const [path,hash]of Object.entries(security.files))await reader.bound({path:dirname(e.securityReview.path).replaceAll('\\','/')+'/'+path,sha256:hash});
  return {review,before};
}

/** Audits the frozen local foundation. The return value expressly cannot activate an image. */
export async function validatePostgresTransitionFoundation(read){
  const {review}=await foundation(evidenceReader(read));
  return {result:'PASS',scope:review.scope,foundationSha256:FOUNDATION_SHA256,publishedKeycloakAccepted:false,ordinaryDeploymentUpgradeAuthorized:false};
}

async function acceptance(ref,read){
  assert.equal(ref?.path,ACCEPTANCE_PATH,'postgres_transition_published_acceptance_required');
  const reader=evidenceReader(read),proof=await reader.json(ref);
  assert.equal(proof.schema,'otziv-postgres-keycloak-published-acceptance-v1','postgres_transition_acceptance_schema');
  assert.equal(proof.result,'PASS','postgres_transition_published_not_passed');assert.equal(proof.role,'COORDINATED_CANDIDATE_PREPARATION','postgres_transition_acceptance_role');
  for(const key of ['sourceWrites','production','vpsCutoverExecuted'])assert.equal(proof[key],false,'postgres_transition_cutover_claim');
  assert.deepEqual(proof.localReview,{path:FOUNDATION_PATH,sha256:FOUNDATION_SHA256},'postgres_transition_foundation_binding');
  const {review,before}=await foundation(reader),p=proof.published,e=proof.evidence;
  assert.equal(p.postgresReference,PG_REFERENCE,'postgres_transition_acceptance_pg');assert.equal(p.postgresConfigId,PG_CONFIG,'postgres_transition_acceptance_pg_config');
  assert.match(p.keycloakReference||'',IMAGE,'postgres_transition_acceptance_kc');assert.match(p.keycloakConfigId||'',CONFIG,'postgres_transition_acceptance_kc_config');
  assert.notEqual(digest(p.keycloakReference),review.testedCandidate.keycloakManifestDigest,'postgres_transition_local_image_substituted');
  assert.deepEqual(Object.keys(e||{}).sort(),['keycloakPublication','keycloakAnonymous','actualMigration','issuerProtocol','securityReview','runtimeInventory','sourceInventoryAfter','sourceContinuityCleanup'].sort(),'postgres_transition_published_evidence_coverage');
  assert.notEqual(e.actualMigration.sha256,review.evidence.actualMigration.sha256,'postgres_transition_published_replay_missing');
  const [publication,anonymous,actual,issuer,security,after,continuity]=await Promise.all([
    e.keycloakPublication,e.keycloakAnonymous,e.actualMigration,e.issuerProtocol,e.securityReview,e.sourceInventoryAfter,e.sourceContinuityCleanup].map(ref=>reader.json(ref)));
  assert.equal(e.keycloakPublication.path,KC_ROOT+'publication/publication.json','postgres_transition_publication_path');
  assert.equal(e.keycloakAnonymous.path,KC_ROOT+'anonymous/anonymous-download.json','postgres_transition_anonymous_path');
  assert.equal(publication.component,'keycloak','postgres_transition_publication_component');
  assert.equal(publication.reference,p.keycloakReference,'postgres_transition_publication_reference');assert.equal(publication.imageId,p.keycloakConfigId,'postgres_transition_publication_config');
  assert.equal(anonymous.result,'PASS','postgres_transition_anonymous_failed');
  for(const key of ['reference','imageId','component','commit','run','attempt'])assert.equal(anonymous[key],publication[key],'postgres_transition_anonymous_identity');
  assert.equal(anonymous.sourcePublicationSha256,e.keycloakPublication.sha256,'postgres_transition_publication_pair');
  assert.equal(anonymous.publicDownloadReadiness,'VERIFIED_ANONYMOUS_DIGEST_PULL','postgres_transition_anonymous_pull');
  checkKeycloakMigrationRuntime(actual,issuer,{keycloakReference:p.keycloakReference,keycloakImageId:digest(p.keycloakReference),dumpSha256:review.source.dumpSha256,scriptSha256:review.evidence.executedReplay.sha256});
  checkPostgresSourceContinuity(before,after,continuity,{beforeSha256:review.evidence.sourceInventoryBefore.sha256,afterSha256:e.sourceInventoryAfter.sha256,completedAt:actual.completedAt});
  assert.equal(e.securityReview.path,KC_ROOT+'review.json','postgres_transition_security_path');
  assert.equal(security.schema,'otziv-keycloak-published-independent-security-review-v1','postgres_transition_security_schema');assert.equal(security.result,'PASS','postgres_transition_security_failed');
  for(const key of ['image','reference'])assert.equal(security[key],p.keycloakReference,'postgres_transition_security_reference');
  assert.equal(security.imageDigest,digest(p.keycloakReference),'postgres_transition_security_digest');assert.equal(security.config,p.keycloakConfigId,'postgres_transition_security_config');
  assert.equal(security.sourceReviewSha256,review.evidence.securityReview.sha256,'postgres_transition_security_source');
  assert.equal(security.runtimeDependencyResult,'PASS','postgres_transition_dependencies_failed');
  assert.deepEqual(e.runtimeInventory,{path:KC_ROOT+security.runtimeInventory,sha256:security.runtimeInventorySha256},'postgres_transition_runtime_inventory_binding');
  assert.equal(security.files?.[security.runtimeInventory],security.runtimeInventorySha256,'postgres_transition_inventory_review_binding');
  assert.equal(security.files?.[security.runtimeComparison],security.runtimeComparisonSha256,'postgres_transition_runtime_comparison_binding');
  await reader.bound(e.runtimeInventory);
  for(const [path,hash]of Object.entries(security.files))await reader.bound({path:KC_ROOT+path,sha256:hash});
  const [comparison,inputs]=await Promise.all([
    reader.json({path:KC_ROOT+security.runtimeComparison,sha256:security.runtimeComparisonSha256}),reader.json(review.evidence.sourceInputs)]);
  checkPublishedKeycloakRuntimeComparison(comparison,{reference:p.keycloakReference,config:p.keycloakConfigId,
    localImage:review.testedCandidate.keycloakManifestDigest,localConfig:review.testedCandidate.keycloakConfigId,
    sourceReviewSha256:review.evidence.securityReview.sha256,inventorySha256:e.runtimeInventory.sha256,buildInputs:inputs.buildInputs});
  const rawBytes=await reader.bytes(KC_ROOT+'publication/vulnerabilities.json'),raw=JSON.parse(rawBytes);
  assert.equal(sha256(rawBytes),security.rawReportSha256,'postgres_transition_raw_hash');assert.equal(raw.Metadata?.ImageID,p.keycloakConfigId,'postgres_transition_raw_image');
  const summary=summarizeReport(raw);
  for(const key of ['high','critical']){assert.equal(summary[key],0,'postgres_transition_raw_findings');assert.equal(security[key],summary[key],'postgres_transition_security_count');}
  assert.deepEqual(checkKeycloakRuntimeDependencies(rawBytes,p.keycloakConfigId),publication.knownRuntimeDependencies,'postgres_transition_dependencies_changed');
  const pg=JSON.parse(await reader.bytes(PG_ROOT+'publication/publication.json'));
  await validatePostgresActivationScan(pg,PG_ROOT+'publication/publication.json',reader.bytes);
  assert.ok(REVIEWED_PUBLISHED_ACCEPTANCE_SHA256,'postgres_transition_exact_published_replay_not_reviewed');
  assert.equal(ref.sha256,REVIEWED_PUBLISHED_ACCEPTANCE_SHA256,'postgres_transition_published_acceptance_anchor');
  return {proof,publication,reference:PG_REFERENCE,proofSha256:ref.sha256,requiredKeycloakReference:p.keycloakReference,requiredKeycloakConfigId:p.keycloakConfigId,
    mode:'COORDINATED_CANDIDATE_PREPARATION',sourcePostgresVersion:'17.10',targetPostgresVersion:'17.11',sourceKeycloakVersion:'26.2.5',
    vpsCutoverExecuted:false,ordinaryDeploymentUpgradeAuthorized:false};
}

/** The caller still validates the complete OCI publication/anonymous pair. */
export async function validatePostgresTransitionReadiness(entry,image,read){
  assert.equal(entry.component,'postgres','postgres_transition_entry_component');assert.equal(image.component,'postgres','postgres_transition_image_component');
  assert.equal(image.sourceBeforeRef,SOURCE_PG,'postgres_transition_original_reference');assert.equal(entry.reference,PG_REFERENCE,'postgres_transition_registered_reference');
  const {proof,publication,...result}=await acceptance(entry.databaseTransition,read);return {component:'postgres',...result};
}

/** Mandatory for c14-keycloak even while the PostgreSQL defaults still select 17.10. */
export async function validatePublishedKeycloakMigrationAcceptance(publication,entry,read){
  assert.equal(entry.component,'keycloak','postgres_transition_keycloak_entry');assert.equal(publication.component,'keycloak','postgres_transition_keycloak_publication');
  const {proof,publication:bound,...result}=await acceptance(entry.migrationAcceptance,read);
  assert.equal(entry.reference,result.requiredKeycloakReference,'postgres_transition_keycloak_registered_reference');
  assert.deepEqual(publication,bound,'postgres_transition_keycloak_publication_bytes');
  assert.deepEqual(entry.publication,proof.evidence.keycloakPublication,'postgres_transition_keycloak_entry_publication');
  assert.deepEqual(entry.anonymous,proof.evidence.keycloakAnonymous,'postgres_transition_keycloak_entry_anonymous');
  return {component:'keycloak',...result,reference:result.requiredKeycloakReference};
}

/** Call after readiness and the registered Keycloak publication have both passed. */
export function assertPostgresKeycloakCoupling(readiness,keycloakEntry,rows,keycloakImage){
  assert.equal(readiness?.component,'postgres','postgres_transition_coupling_component');
  assert.equal(readiness.mode,'COORDINATED_CANDIDATE_PREPARATION','postgres_transition_coupling_mode');
  assert.equal(readiness.ordinaryDeploymentUpgradeAuthorized,false,'postgres_transition_coupling_authority');
  assert.equal(keycloakEntry?.component,'keycloak','postgres_transition_coupling_registry_missing');
  assert.match(readiness.requiredKeycloakReference||'',IMAGE,'postgres_transition_coupling_reference');
  assert.equal(keycloakEntry.reference,readiness.requiredKeycloakReference,'postgres_transition_coupling_registry_reference');
  assert.deepEqual(keycloakEntry.migrationAcceptance,{path:ACCEPTANCE_PATH,sha256:readiness.proofSha256},'postgres_transition_coupling_acceptance');
  assert.equal(keycloakImage?.component,'keycloak','postgres_transition_coupling_manifest');
  assert.equal(keycloakImage.sourceBeforeRef,SOURCE_KC,'postgres_transition_coupling_source');
  const coverage=keycloakImage.defaultReferencesBefore.map(({path,service})=>({path,service}));
  assert.deepEqual(coverage,REFERENCES,'postgres_transition_coupling_coverage');
  for(const ref of REFERENCES){
    const matches=rows.filter(row=>row.references?.some(item=>item.path===ref.path&&item.service===ref.service));
    assert.equal(matches.length,1,'postgres_transition_coupling_missing_or_duplicate_'+ref.path);
    assert.equal(matches[0].image,readiness.requiredKeycloakReference,'postgres_transition_coupling_compose_reference_'+ref.path);
  }
}
