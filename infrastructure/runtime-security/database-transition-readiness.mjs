import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {publishedImage,publishedConfig,vpsBaselineApp,vpsBaselineLocalImage,vpsCandidateApp,targetImage,requireUpgradeCheck,requireSourceSemanticConfiguration,snapshotPolicy} from './mysql-upgrade-rehearsal.mjs';
import {TRIVY_IMAGE,summarizeReport} from './scan.mjs';

const sha256=bytes=>createHash('sha256').update(bytes).digest('hex');
const ROOT='infrastructure/runtime-security/proofs/c14-mysql-vps/';
const SOURCE='mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383';
const SOURCE_CONFIG='sha256:31ebb0b19998d2c1b8bcbb8fc0f0e676dcc9a758b2fea940f8e6aa27b4c916cf';
const SNAPSHOT_KEYS=['tableCount','rows','countsSha256','historySha256','checksumsSha256','jsonSha256','jsonDataSha256'];
const GATES=[
  'clone_9_0_authenticated','source_semantic_configuration_reproduced_9_0','explicit_standalone_policy_9_0',
  'captured_flyway_history_restored','configured_local_account_grants_and_login_preserved',
  'app90_health_up','actual_app_validates_expected_schema','preupgrade_backup_created_with_application_stopped',
  'mandatory_upgrade_checker_passed','same_named_data_volume_upgraded','target_9_7_3_authenticated',
  'source_semantic_configuration_reproduced_9_7_3','explicit_standalone_policy_9_7_3','app973_health_up',
  'flyway_still_expected_schema','candidate_app_append_only_287_to_310','preexisting_unknown_rows_and_envelopes_unchanged',
  'published_candidate_accepts_new_canary_write','published_restart_keeps_postupgrade_canary',
  'source_semantic_configuration_reproduced_rollback','explicit_standalone_policy_rollback',
  'rollback_uses_separate_fresh_old_version_volume','rollback_database_was_initially_empty',
  'postupgrade_canary_absent_after_backup_restore','app90-rollback_health_up',
  'rollback_application_keeps_flyway_history','rollback_preserves_existing_unknown',
  'baseline_old_app_database_read_only_enforced','baseline_old_app_database_write_fence_released',
  'rollback_old_app_database_read_only_enforced','rollback_old_app_database_write_fence_released',
  ...['baseline_app_preserves_capture_','unchanged_','published_restart_unchanged_','rollback_restores_preupgrade_','rollback_app_preserves_restored_'].flatMap(prefix=>SNAPSHOT_KEYS.map(key=>prefix+key)),
];

function sameSnapshot(before,after){
  for(const key of SNAPSHOT_KEYS)assert.equal(after?.[key],before?.[key],'database_transition_snapshot_'+key);
  assert.ok(before.tableCount>0&&before.rows>0,'database_transition_snapshot_empty');
  for(const key of SNAPSHOT_KEYS.filter(key=>key.endsWith('Sha256')))assert.match(before[key],/^[a-f0-9]{64}$/,'database_transition_snapshot_hash');
}

/** Candidate preparation only. The caller must independently validate the full
 * publication/anonymous OCI pair. This function cannot authorize a VPS cutover,
 * an ordinary deployment, or opening an old binary on an upgraded data volume. */
export async function validateDatabaseTransitionReadiness(entry,image,read) {
  assert.equal(image.component,'mysql','database_transition_component_requires_review');
  assert.equal(entry.component,'mysql','database_transition_entry_component');
  assert.equal(image.sourceBeforeRef,SOURCE,'database_transition_source_reference');
  assert.equal(entry.reference,publishedImage,'database_transition_target_reference');
  const ref=entry.databaseTransition;
  assert.ok(ref&&Object.keys(ref).sort().join(',')==='path,sha256','database_transition_proof_required');
  assert.equal(ref.path,ROOT+'actual-result.json','database_transition_proof_path');
  async function bytes(reference){
    assert.ok(reference&&typeof reference.path==='string'&&reference.path.startsWith('infrastructure/runtime-security/'),'database_transition_evidence_scope');
    assert.ok(!reference.path.includes('\\')&&!reference.path.includes(':')&&reference.path.split('/').every(part=>part&&part!=='.'&&part!=='..'),'database_transition_evidence_path');
    assert.match(reference.sha256||'',/^[a-f0-9]{64}$/,'database_transition_evidence_hash');
    const value=await read(reference.path);assert.equal(sha256(value),reference.sha256,'database_transition_evidence_changed');return value;
  }
  const json=async reference=>JSON.parse(await bytes(reference));
  const proof=await json(ref);
  assert.equal(proof.schema,'otziv-mysql-actual-vps-release-readiness-v1','database_transition_schema');
  assert.equal(proof.result,'PASS','database_transition_not_passed');
  assert.equal(proof.role,'COORDINATED_CANDIDATE_PREPARATION','database_transition_role');
  for(const key of ['production','vpsCutoverExecuted','sourceWrites'])assert.equal(proof[key],false,'database_transition_no_cutover_claim');
  assert.equal(proof.sourceSchema,'1.10.287','database_transition_source_schema');
  assert.equal(proof.targetSchema,'1.10.310','database_transition_target_schema');
  const e=proof.evidence;assert.ok(e,'database_transition_evidence_required');
  const [runtime,capture,copy,continuity,checker,security,raw,binding,targetPolicy]=await Promise.all([
    json(e.runtime),json(e.sourceCapture),json(e.oldAppCopy),json(e.sourceContinuity),json(e.upgradeChecker),json(e.security),json(e.rawSecurity),json(e.sourceBinding),json(e.targetContainerPolicy)]);
  assert.equal(runtime.schema,'otziv-mysql-upgrade-rehearsal-v1','database_transition_runtime_schema');
  assert.equal(runtime.result,'PASS','database_transition_runtime_not_passed');
  assert.equal(runtime.mode,'vps-release-upgrade','database_transition_runtime_mode');
  assert.equal(runtime.sourceContainer,'my-mysql','database_transition_runtime_source');
  assert.equal(runtime.sourceSchemaAtCapture,'1.10.287','database_transition_runtime_source_schema');
  assert.equal(runtime.expectedApplicationSchema,'1.10.310','database_transition_runtime_target_schema');
  assert.equal(runtime.runtimeVersion,'9.7.3','database_transition_runtime_version');
  assert.equal(runtime.production,false,'database_transition_runtime_not_local');
  assert.equal(runtime.sourceWrites,false,'database_transition_runtime_source_write');
  assert.equal(runtime.cleanup,'OWNED_CONTAINERS_NETWORK_VOLUME_AND_PROTECTED_DUMP_REMOVED','database_transition_cleanup_incomplete');
  assert.equal(runtime.failurePhase,undefined,'database_transition_runtime_failure');
  assert.equal(runtime.preparationFailurePreserved,undefined,'database_transition_partial_resume');
  const images=runtime.images;
  assert.equal(images.source,SOURCE.split('@')[1],'database_transition_runtime_source_image');
  assert.equal(images.targetReference,publishedImage,'database_transition_runtime_target_image');
  assert.equal(images.targetConfigurationDigest,publishedConfig,'database_transition_runtime_target_config');
  assert.equal(images.upgradeCheckerReference,targetImage,'database_transition_checker_image');
  assert.equal(images.app,vpsCandidateApp,'database_transition_candidate_app');
  assert.equal(images.baselineApp,vpsBaselineLocalImage,'database_transition_old_local_app');
  assert.equal(images.baselineAppConfigurationDigest,vpsBaselineApp,'database_transition_old_configuration');
  assert.deepEqual(images.published,{reference:publishedImage,configurationDigest:publishedConfig,localId:publishedImage.split('@')[1]},'database_transition_published_identity');
  assert.equal(targetPolicy.schema,'otziv-mysql-vps-target-container-policy-v1','database_transition_target_policy_schema');assert.equal(targetPolicy.result,'PASS','database_transition_target_policy_failed');
  assert.equal(targetPolicy.owner,runtime.owner,'database_transition_target_policy_owner');assert.equal(targetPolicy.imageId,publishedImage.split('@')[1],'database_transition_target_policy_image');
  assert.equal(targetPolicy.configuredUser,'999:999','database_transition_target_configured_uid');assert.equal(targetPolicy.actualUid,999,'database_transition_target_effective_uid');
  assert.equal(targetPolicy.networkInternal,true,'database_transition_external_network');assert.equal(targetPolicy.publishedPorts,0,'database_transition_published_ports');
  assert.equal(targetPolicy.sourceVolume,runtime.rollbackProof.originalVolume,'database_transition_target_volume_binding');assert.equal(targetPolicy.sourceContacted,false,'database_transition_policy_source_contact');
  assert.equal(capture.schema,'otziv-actual-vps-readonly-capture-v1','database_transition_capture_schema');
  assert.equal(capture.sourceWrites,false,'database_transition_capture_writes');
  assert.equal(capture.productionQuiesced,false,'database_transition_capture_not_quiesced');
  for(const key of ['flywayHistoryBeforeEqualsAfter','localDownloadVerified','temporaryRemoteDumpRemoved'])assert.equal(capture[key],true,'database_transition_capture_incomplete');
  assert.equal(capture.sourceDatabaseVersion,'9.0.0','database_transition_capture_version');
  assert.equal(capture.sourceSchemaVersion,'1.10.287','database_transition_capture_version');
  assert.equal(capture.sourceProject,'otziv-prod','database_transition_capture_project');
  assert.equal(capture.sourceContainer.imageId,SOURCE_CONFIG,'database_transition_capture_mysql_configuration');
  assert.equal(capture.sourceContainer.imageReference,SOURCE,'database_transition_capture_mysql_reference');
  assert.equal(capture.sourceContainer.mount.Name,'docker_mysql_data','database_transition_capture_volume');
  assert.equal(capture.sourceApplication.imageId,vpsBaselineApp,'database_transition_capture_old_app');
  assert.equal(runtime.originalVpsCaptureSha256,e.sourceCapture.sha256,'database_transition_capture_binding');
  assert.equal(runtime.sourceCaptureSha256,capture.dump.sha256,'database_transition_dump_binding');
  assert.equal(copy.schema,'otziv-exact-running-vps-app-copy-v1','database_transition_copy_schema');
  assert.equal(copy.imageConfigurationDigest,vpsBaselineApp,'database_transition_copy_configuration');
  assert.equal(copy.localImageId,vpsBaselineLocalImage,'database_transition_copy_manifest');
  for(const key of ['configurationReexportHashMatches','rootFilesystemMatches','platformMatches','sourceContainerUnchanged'])assert.equal(copy[key],true,'database_transition_copy_incomplete');
  assert.equal(copy.sourceWrites,false,'database_transition_copy_writes');
  assert.equal(runtime.baselineAppCopy.receiptSha256,e.oldAppCopy.sha256,'database_transition_copy_binding');
  assert.equal(continuity.schema,'otziv-mysql-vps-source-continuity-v1','database_transition_continuity_schema');
  assert.equal(continuity.result,'PASS','database_transition_source_changed');
  assert.equal(continuity.sourceCaptureSha256,e.sourceCapture.sha256,'database_transition_continuity_binding');
  for(const key of ['databaseContainerUnchanged','applicationContainerUnchanged','databaseImageUnchanged','applicationImageUnchanged','volumeUnchanged','semanticConfigurationUnchanged','flywayHistoryUnchanged'])assert.equal(continuity.checks?.[key],true,'database_transition_source_changed');
  assert.equal(continuity.databaseVersion,'9.0.0','database_transition_source_continuity_version');assert.equal(continuity.sourceSchemaVersion,'1.10.287','database_transition_source_continuity_schema');
  assert.equal(continuity.sourceWrites,false,'database_transition_continuity_writes');
  assert.equal(continuity.businessDataUnchangedClaimed,false,'database_transition_live_dml_claim');
  assert.ok(Date.parse(continuity.checkedAt)>=Date.parse(runtime.completedAt),'database_transition_continuity_stale');
  assert.deepEqual(runtime.upgradeChecker,requireUpgradeCheck(checker),'database_transition_checker_summary');
  assert.equal(checker.targetVersion,'9.7.3','database_transition_checker_target');
  assert.match(checker.serverVersion,/^9\.0\.0 /,'database_transition_checker_source');
  assert.deepEqual(checker.checksPerformed.map(check=>check.id).sort(),['sysVars','checkTableCommand','syntax','foreignKeyReferences','authMethodUsage','pluginUsage','spatialIndex'].sort(),'database_transition_checker_coverage');
  assert.deepEqual(checker.manualChecks||[],[],'database_transition_checker_manual_review');
  assert.ok(checker.checksPerformed.every(check=>(check.detectedProblems||[]).every(problem=>check.id==='sysVars'&&problem.level==='Warning')),'database_transition_checker_new_problem');
  const semantic=requireSourceSemanticConfiguration(runtime.semanticConfiguration.source);
  assert.equal(semantic.collation_server,'utf8mb4_unicode_ci','database_transition_collation');
  assert.equal(semantic.time_zone,'+08:00','database_transition_timezone');
  assert.deepEqual(runtime.standalonePolicy,{gtid_mode:'OFF',enforce_gtid_consistency:'OFF',log_bin:'ON',binlog_format:'ROW',event_scheduler:'OFF'},'database_transition_standalone_policy');
  assert.ok(Array.isArray(runtime.checks)&&runtime.checks.every(item=>item.passed===true),'database_transition_failed_check');
  const names=runtime.checks.map(item=>item.name);
  for(const gate of GATES)assert.ok(names.includes(gate),'database_transition_missing_gate_'+gate);
  for(const role of ['app90','app973','rollback'])assert.ok(names.includes('completed_spring_jpa_hikari_shutdown_'+role),'database_transition_graceful_app_stop');
  for(const role of ['app90','app973','app90-rollback']){
    const stops=runtime.stops?.filter(stop=>stop.container.endsWith('-'+role));assert.equal(stops?.length,1,'database_transition_app_stop_missing');const stop=stops[0];
    assert.ok([0,143].includes(stop.exitCode)&&stop.oomKilled===false&&stop.elapsedMs<120000,'database_transition_app_stop_not_graceful');
    assert.ok(stop.graceful?.springComplete===true&&stop.graceful.entityManagerClosed===true&&stop.graceful.poolsClosed>0,'database_transition_app_close_evidence');
    if(role==='app973')assert.equal(stop.pid1,'java','database_transition_candidate_pid1');
    else {assert.equal(stop.pid1,'sh','database_transition_old_pid1');assert.equal(stop.controlledLegacyStop?.method,'EXACT_OLD_APP_SINGLE_JVM_CHILD_TERM','database_transition_old_signal_method');assert.equal(stop.controlledLegacyStop.image,vpsBaselineLocalImage,'database_transition_old_signal_image');assert.equal(stop.controlledLegacyStop.childComm,'java','database_transition_old_signal_process');}
  }
  for(const role of ['baseline','rollback'])assert.deepEqual(runtime.oldApplicationReadOnly?.[role],{duringValidation:{readOnly:true,superReadOnly:true},releasedAfterGracefulStop:{readOnly:false,superReadOnly:false}},'database_transition_old_app_fence');
  sameSnapshot(runtime.before,runtime.after);sameSnapshot(runtime.before,runtime.rollbackProof.snapshot);
  snapshotPolicy('1.10.287',runtime.before.checksumTables);snapshotPolicy('1.10.310',runtime.afterApplicationMigration.checksumTables);
  const migration=runtime.applicationMigration;
  assert.equal(migration.sourceSchema,'1.10.287','database_transition_migration_source');assert.equal(migration.targetSchema,'1.10.310','database_transition_migration_target');
  assert.equal(migration.baselineImage,vpsBaselineLocalImage,'database_transition_migration_old_app');assert.equal(migration.candidateImage,vpsCandidateApp,'database_transition_migration_new_app');
  assert.equal(migration.existingUnknownUnchanged,true,'database_transition_unknown_changed');
  assert.equal(migration.unknownSha256,runtime.legacyUnknown.sha256,'database_transition_unknown_identity');
  assert.equal(migration.history.existingHistorySha256,capture.flywayHistorySha256,'database_transition_existing_history');
  assert.equal(migration.history.completeHistorySha256,runtime.afterApplicationMigration.historySha256,'database_transition_migrated_history');
  assert.ok(migration.history.appendedRows>0&&migration.history.sourceRows+migration.history.appendedRows===migration.history.targetRows,'database_transition_migration_append');
  assert.ok(Number.isInteger(runtime.legacyUnknown.rows)&&runtime.legacyUnknown.rows>=0&&runtime.legacyUnknown.queryCount>0,'database_transition_unknown_inventory');
  for(const key of ['sha256','queriesSha256'])assert.match(runtime.legacyUnknown[key],/^[a-f0-9]{64}$/,'database_transition_unknown_hash');
  const rollback=runtime.rollbackProof;
  assert.equal(rollback.result,'PASS','database_transition_rollback_failed');assert.equal(rollback.sourceImage,SOURCE.split('@')[1],'database_transition_rollback_image');
  assert.equal(rollback.restoredVersion,'9.0.0','database_transition_rollback_version');assert.equal(rollback.applicationImage,vpsBaselineLocalImage,'database_transition_rollback_app');
  assert.equal(rollback.binaryDowngradeAttempted,false,'database_transition_binary_downgrade');assert.notEqual(rollback.originalVolume,rollback.restoredVolume,'database_transition_rollback_volume');
  for(const key of ['postUpgradeWriteAbsent','configuredAccountGrantsPreserved'])assert.equal(rollback[key],true,'database_transition_rollback_incomplete');
  assert.deepEqual(rollback.backup,runtime.preupgradeBackup,'database_transition_rollback_backup');
  assert.equal(security.result,'PASS','database_transition_security_failed');assert.equal(security.target,publishedImage,'database_transition_security_image');
  assert.equal(security.imageConfigurationDigest,publishedConfig,'database_transition_security_config');assert.equal(security.scannerImage,TRIVY_IMAGE,'database_transition_scanner');
  assert.equal(security.rawReportSha256,e.rawSecurity.sha256,'database_transition_scan_binding');assert.equal(raw.Metadata.ImageID,publishedConfig,'database_transition_raw_scan_image');
  for(const key of ['high','critical','blockingFixedHighOrCritical','unfixedHighOrCritical'])assert.equal(security[key],0,'database_transition_security_findings');
  assert.deepEqual(security.exclusions,[],'database_transition_security_exclusions');assert.deepEqual(security.adjudications,[],'database_transition_security_adjudications');
  const actualScan=summarizeReport(raw);for(const key of ['high','critical','blockingFixedHighOrCritical','unfixedHighOrCritical'])assert.equal(actualScan[key],0,'database_transition_raw_findings');
  assert.equal(binding.schema,'otziv-mysql-vps-executed-source-binding-v1','database_transition_binding_schema');
  const required=['mysql-upgrade-rehearsal.mjs','mysql-upgrade-rehearsal.test.mjs','mysql-vps-release-rehearsal.test.mjs'];
  for(const name of required){const file=binding.files?.find(file=>file.path==='infrastructure/runtime-security/'+name);assert.ok(file,'database_transition_source_binding_missing');await bytes(file);}
  assert.equal(binding.targetPolicyObserver?.path,ROOT+'target-policy-observer-source.txt','database_transition_observer_source');await bytes(binding.targetPolicyObserver);
  // These logs are evidence, never a substitute for the structured runtime gates.
  const tests=(await bytes(e.unitTests)).toString();assert.match(tests,/tests 60/,'database_transition_unit_count');assert.match(tests,/pass 60/,'database_transition_unit_pass');assert.match(tests,/fail 0/,'database_transition_unit_fail');
  const log=(await bytes(e.executionLog)).toString();assert.ok(!log.includes('"result":"FAIL"')&&log.includes('"result":"PASS"'),'database_transition_execution_log');
  return {component:'mysql',reference:publishedImage,proofSha256:ref.sha256,mode:'COORDINATED_CANDIDATE_PREPARATION',sourceSchema:'1.10.287',targetSchema:'1.10.310',vpsCutoverExecuted:false,ordinaryDeploymentUpgradeAuthorized:false};
}
