// Fail-closed activation of the C51 zlib-only PostgreSQL derivative. Publication
// alone does not authorize replacing the database in Compose: the candidate needs
// its own same-volume C36 -> C51 -> C36 rehearsal with the active C48 Keycloak,
// and the frozen C36 activation keeps its complete earlier transition lineage.
import assert from 'node:assert/strict';
import {dirname} from 'node:path';
import {gunzipSync} from 'node:zlib';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
import {buildTriage} from './triage-report.mjs';
import {loadPostgresC16Proof} from './postgres-c16-adjudication.mjs';
import {hash,loadC36PostgresParent,loadC51ZlibPackage,validatePostgresC51Runtime,validateC51ScannerPackages,
  deriveC51Decisions,effectivePostgresC51Summary,PARENT_REFERENCE,PARENT_CONFIG_ID} from './postgres-c51-adjudication.mjs';
import {validatePostgresC36Transition,C35_KEYCLOAK_REFERENCE,C35_KEYCLOAK_CONFIG} from './postgres-c36-activation.mjs';
import {validateAptC48Activation} from './apt-c48-activation.mjs';

export const C48_KEYCLOAK_REFERENCE='ghcr.io/claidd/otziv-security@sha256:2e935efdb594ee4a3f0bc8343b1e6a76def33d70a4981cbd730ce5942ff47009';
export const C48_KEYCLOAK_CONFIG='sha256:09efb3a918669b335b30966deeea3027591e03c2e048e653c0cb7cad8a8ba773';
export const C51_MANIFEST='infrastructure/runtime-security/reviewed-images-c51-postgres.json';
export const C51_PROOF='infrastructure/runtime-security/proofs/c51-postgres/';
export const C51_ACCEPTANCE=C51_PROOF+'acceptance.json';
const C48_KEYCLOAK_MANIFEST='infrastructure/runtime-security/reviewed-images-c48-keycloak.json';
const C36_MANIFEST='infrastructure/runtime-security/reviewed-images-c36-postgres.json';
// Exact bytes of the C36 activation entry that C51 replaces.
export const C36_ACTIVATION_SHA256='e817ee13121794b59975bc4d5a25b715dcd4f76abbeac4e8f0d80dd2176b4367';
const SOURCE='infrastructure/runtime-security/';
const EXECUTED=['postgres-c51-inspection.py','ssl-refresh-inspection.py','postgres-c51-adjudication.mjs',
  'postgres-c51-rehearsal.py','postgres-c51-capture-window.py'].map(name=>SOURCE+name);
// The C36 protocol, plus the explicit no-schema-change check of a library-only refresh.
export const C51_REQUIRED_CHECKS=['restored_critical_records_exact','capture_stable_tables_restored_exact',
  'same_volume_all_tables_exact','existing_admin_password_login','existing_roles_and_credentials_preserved',
  'unauthenticated_provider_denied','same_volume_rollback_preserves_all_post_upgrade_writes',
  'same_volume_old_issuer_login','same_volume_old_issuer_credentials_preserved',
  'fresh_rollback_restore_preserves_all_post_upgrade_writes','restored_existing_password_login',
  'restored_roles_and_credentials_preserved','library_patch_no_schema_migration',
  'source_existing_password_login','source_roles_and_credentials_preserved'];
const CAPTURE_CHECKS=['complete_custom_dump','source_runtime_unchanged','source_critical_identity_stable_during_capture'];
const DATABASE_FIELDS=['serverVersion','serverVersionNumber','encoding','locale','clusterIdentifier','extensions',
  'configSha256','hbaSha256','identSha256'];
const safe=path=>typeof path==='string'&&path.startsWith(SOURCE)&&!/[\\:]/.test(path)&&
  path.split('/').every(part=>part&&part!=='.'&&part!=='..');
const sameId=(id,config,reference)=>assert.ok([config,reference.split('@')[1]].includes(id),
  'postgres_c51_activation_inspected_id');

export async function validatePostgresC51ActivationScan(publication,publicationPath,read){
  assert.equal(publication.component,'postgres','postgres_c51_activation_component');
  assert.equal(publication.manifestSet,'c51-postgres','postgres_c51_activation_set');
  assert.match(publication.reference||'',/^ghcr\.io\/claidd\/otziv-security@sha256:[a-f0-9]{64}$/,
    'postgres_c51_activation_reference');
  assert.match(publication.imageId||'',/^sha256:[a-f0-9]{64}$/,'postgres_c51_activation_image');
  assert.ok(safe(publicationPath),'postgres_c51_activation_path');
  const directory=dirname(publicationPath).replaceAll('\\','/');
  const [rawBytes,receiptBytes,runtimeBytes,configBytes,triageBytes]=await Promise.all([
    read(directory+'/vulnerabilities.json'),read(directory+'/vulnerabilities.adjudications.json'),
    read(directory+'/vulnerabilities.runtime-c51.json.gz'),read(directory+'/registry-amd64-config.json'),
    read(directory+'/vulnerabilities.triage.json')]);
  const raw=JSON.parse(rawBytes),receipt=JSON.parse(receiptBytes),runtime=JSON.parse(gunzipSync(runtimeBytes)),
    config=JSON.parse(configBytes),triage=JSON.parse(triageBytes);
  assert.equal('sha256:'+hash(configBytes),publication.imageId,'postgres_c51_activation_registry_config');
  assert.equal(raw.Metadata?.ImageID,publication.imageId,'postgres_c51_activation_report_image');
  assert.deepEqual(raw.Metadata?.ImageConfig?.rootfs,config.rootfs,'postgres_c51_activation_report_layers');
  assert.equal(receipt.schema,'otziv-image-adjudication-v1','postgres_c51_activation_receipt_schema');
  assert.equal(receipt.status,'NOT_APPLICABLE','postgres_c51_activation_foreign_status');
  assert.deepEqual(receipt.decisions,[],'postgres_c51_activation_foreign_decisions');
  assert.equal(receipt.alloy?.status,'NOT_APPLICABLE','postgres_c51_activation_alloy_status');
  assert.deepEqual(receipt.alloy?.decisions,[],'postgres_c51_activation_alloy_decisions');
  assert.equal(receipt.gosu,undefined,'postgres_c51_activation_separate_gosu');
  for(const item of [receipt,receipt.alloy,receipt.postgres]){
    assert.equal(item.rawReportSha256,hash(rawBytes),'postgres_c51_activation_raw_binding');
    assert.equal(item.imageConfigId,publication.imageId,'postgres_c51_activation_receipt_image');
    assert.equal(item.rawReportModified,false,'postgres_c51_activation_raw_modified');
  }
  assert.deepEqual(triage,buildTriage(raw,rawBytes),'postgres_c51_activation_triage');
  const postgres=receipt.postgres;
  assert.equal(postgres.schema,'otziv-postgres-c51-adjudication-v1','postgres_c51_activation_postgres_schema');
  assert.equal(postgres.status,'EXACT_RUNTIME_VERIFIED','postgres_c51_activation_postgres_unverified');
  sameId(postgres.immutableImageId,publication.imageId,publication.reference);
  assert.equal(postgres.runtimeProofSha256,hash(runtimeBytes),'postgres_c51_activation_runtime_hash');
  assert.equal(runtime.executedScriptSha256,postgres.inspectorSha256,'postgres_c51_activation_inspector_binding');
  assert.equal(runtime.candidate.reference,postgres.immutableImageId,'postgres_c51_activation_inspected_reference');
  const {parent}=await loadC36PostgresParent(),zlib=await loadC51ZlibPackage();
  const delta=validatePostgresC51Runtime(runtime,parent,config,raw,publication.commit,zlib);
  assert.deepEqual(postgres.changedFiles,delta.changedFiles,'postgres_c51_activation_delta');
  const {review,reviewSha256}=await loadPostgresC16Proof();
  assert.equal(postgres.reviewSha256,reviewSha256,'postgres_c51_activation_review');
  assert.deepEqual(postgres.zlib,validateC51ScannerPackages(raw,review),'postgres_c51_activation_zlib');
  const {september,decisions}=await deriveC51Decisions(raw,review);
  assert.deepEqual(postgres.libxmlSeptember,september,'postgres_c51_activation_libxml');
  assert.deepEqual(postgres.decisions,decisions,'postgres_c51_activation_decisions');
  effectivePostgresC51Summary(summarizeReport(raw),postgres);
  const summary={...combinedScanSummary(summarizeReport(raw),
    {grafana:receipt,alloy:receipt.alloy,postgres}),scannerImage:TRIVY_IMAGE};
  assert.deepEqual(publication.security,summary,'postgres_c51_activation_security');
  assert.equal(summary.result,'PASS','postgres_c51_activation_scan_failed');
  assert.equal(summary.effectiveBlockingFixedHighOrCritical,0,'postgres_c51_activation_fixed_risk');
  assert.equal(summary.unresolvedRiskReview,'NONE','postgres_c51_activation_unresolved_risk');
  return {publication,postgres,raw,runtime,config};
}

const checksPassed=(record,names,code)=>{
  assert.ok(record.checks?.length&&record.checks.every(check=>check.passed===true),code+'_incomplete');
  for(const name of names)assert.equal(record.checks.filter(check=>check.name===name&&check.passed===true).length,1,
    code+'_missing_check:'+name);
};

export async function validatePostgresC51Acceptance(entry,read){
  assert.equal(entry.component,'postgres','postgres_c51_acceptance_component');
  assert.equal(entry.manifest?.path,C51_MANIFEST,'postgres_c51_acceptance_manifest');
  const bound=entry.postgresC51Acceptance;
  assert.ok(bound&&bound.path===C51_ACCEPTANCE,'postgres_c51_acceptance_missing');
  assert.match(bound.sha256||'',/^[a-f0-9]{64}$/,'postgres_c51_acceptance_hash_missing');
  assert.deepEqual(entry.databaseTransition,bound,'postgres_c51_transition_not_bound');
  const bytes=await read(bound.path);
  assert.equal(hash(bytes),bound.sha256,'postgres_c51_acceptance_hash');
  const value=JSON.parse(bytes);
  assert.equal(value.schema,'otziv-postgres-c51-acceptance-v1','postgres_c51_acceptance_schema');
  assert.equal(value.result,'PASS','postgres_c51_acceptance_failed');
  assert.equal(value.reference,entry.reference,'postgres_c51_acceptance_reference');
  assert.equal(value.parentReference,PARENT_REFERENCE,'postgres_c51_acceptance_parent');
  assert.equal(value.parentConfigId,PARENT_CONFIG_ID,'postgres_c51_acceptance_parent_config');
  for(const field of ['commit','run','attempt'])assert.equal(value[field],entry[field],
    'postgres_c51_acceptance_identity:'+field);
  assert.match(value.imageConfigId||'',/^sha256:[a-f0-9]{64}$/,'postgres_c51_acceptance_image');
  assert.deepEqual(value.pair,{postgres:{reference:entry.reference,imageConfigId:value.imageConfigId},
    keycloak:{reference:C48_KEYCLOAK_REFERENCE,imageConfigId:C48_KEYCLOAK_CONFIG}},'postgres_c51_acceptance_pair');
  const directory=dirname(entry.publication.path).replaceAll('\\','/');
  assert.equal(directory,C51_PROOF+'publication','postgres_c51_acceptance_publication_path');
  assert.equal(value.runtimePath,directory+'/vulnerabilities.runtime-c51.json.gz','postgres_c51_acceptance_runtime_path');
  assert.equal(value.capturePath,C51_PROOF+'rehearsal/capture.json','postgres_c51_acceptance_capture_path');
  assert.equal(value.replayPath,C51_PROOF+'rehearsal/replay.json','postgres_c51_acceptance_replay_path');
  assert.equal(value.captureWindowPath,C51_PROOF+'rehearsal/capture-window.json','postgres_c51_acceptance_window_path');
  const required=[entry.publication.path,value.runtimePath,value.capturePath,value.replayPath,value.captureWindowPath,
    directory+'/vulnerabilities.json',directory+'/vulnerabilities.adjudications.json',
    directory+'/vulnerabilities.triage.json',directory+'/registry-amd64-config.json'];
  for(const path of required)assert.ok(value.files?.[path],'postgres_c51_acceptance_file_missing:'+path);
  for(const path of EXECUTED)assert.ok(value.executedSources?.[path],'postgres_c51_acceptance_source_missing:'+path);
  for(const [path,expected]of Object.entries({...value.files,...value.executedSources})){
    assert.ok(safe(path),'postgres_c51_acceptance_unsafe_path');
    assert.match(expected||'',/^[a-f0-9]{64}$/,'postgres_c51_acceptance_invalid_hash');
    assert.equal(hash(await read(path)),expected,'postgres_c51_acceptance_evidence_changed:'+path);
  }
  const publication=JSON.parse(await read(entry.publication.path));
  assert.equal(publication.reference,entry.reference,'postgres_c51_acceptance_publication_reference');
  assert.equal(publication.imageId,value.imageConfigId,'postgres_c51_acceptance_publication_image');
  for(const field of ['commit','run','attempt'])assert.equal(publication[field],entry[field],
    'postgres_c51_acceptance_publication_identity');
  const {runtime}=await validatePostgresC51ActivationScan(publication,entry.publication.path,read);
  // The inspector that produced the bound runtime proof is the bound executed source.
  assert.equal(runtime.executedScriptSha256,value.executedSources[SOURCE+'postgres-c51-inspection.py'],
    'postgres_c51_acceptance_inspector');
  assert.equal(runtime.parentInspectorSha256,value.executedSources[SOURCE+'ssl-refresh-inspection.py'],
    'postgres_c51_acceptance_parent_inspector');
  return value;
}

// Capture reads the local prod-like C36/C48 pair; replay restores it, upgrades the
// same volume to C51 under C48, then proves both rollback paths back to C36.
export async function validatePostgresC51Rehearsal(value,read){
  const capture=JSON.parse(await read(value.capturePath)),replay=JSON.parse(await read(value.replayPath));
  for(const record of [capture,replay]){
    assert.equal(record.schema,'otziv-postgres-c51-rehearsal-v1','postgres_c51_rehearsal_schema');
    assert.equal(record.result,'PASS','postgres_c51_rehearsal_failed');
    assert.equal(record.productionWrites,false,'postgres_c51_rehearsal_production_write');
    assert.equal(record.privateRowsPublished,false,'postgres_c51_rehearsal_private_rows');
    assert.equal(record.publishedPorts,0,'postgres_c51_rehearsal_published_ports');
    assert.equal(record.sourceScope,'LOCAL_PROD_LIKE_KEYCLOAK','postgres_c51_rehearsal_scope');
    assert.equal(record.sourcePostgres,PARENT_REFERENCE,'postgres_c51_rehearsal_parent');
    assert.equal(record.sourcePostgresConfig,PARENT_CONFIG_ID,'postgres_c51_rehearsal_parent_config');
    assert.equal(record.keycloak,C48_KEYCLOAK_REFERENCE,'postgres_c51_rehearsal_issuer');
    assert.equal(record.keycloakConfig,C48_KEYCLOAK_CONFIG,'postgres_c51_rehearsal_issuer_config');
    assert.equal(record.executedScriptSha256,value.executedSources?.[SOURCE+'postgres-c51-rehearsal.py'],
      'postgres_c51_rehearsal_script');
  }
  checksPassed(capture,CAPTURE_CHECKS,'postgres_c51_rehearsal');
  checksPassed(replay,C51_REQUIRED_CHECKS,'postgres_c51_rehearsal');
  assert.equal(replay.candidatePostgres,value.reference,'postgres_c51_rehearsal_candidate');
  sameId(replay.candidatePostgresLocalId,value.imageConfigId,value.reference);
  assert.equal(replay.candidate,C48_KEYCLOAK_REFERENCE,'postgres_c51_rehearsal_candidate_keycloak');
  sameId(replay.candidateLocalId,C48_KEYCLOAK_CONFIG,C48_KEYCLOAK_REFERENCE);
  assert.match(capture.dumpSha256||'',/^[a-f0-9]{64}$/,'postgres_c51_rehearsal_dump_missing');
  assert.equal(replay.dumpSha256,capture.dumpSha256,'postgres_c51_rehearsal_dump');
  assert.deepEqual(replay.ownedResourcesRemaining,{container:0,volume:0,network:0},'postgres_c51_rehearsal_cleanup');
  assert.ok(!replay.cleanupErrors?.length,'postgres_c51_rehearsal_cleanup_errors');
  assert.equal(replay.tableCount,102,'postgres_c51_rehearsal_table_coverage');
  assert.equal(replay.stableTableCount,102,'postgres_c51_rehearsal_stable_coverage');
  assert.equal(replay.sourceDatabase?.serverVersion,'17.11','postgres_c51_rehearsal_server_version');
  for(const field of DATABASE_FIELDS)
    assert.deepEqual(replay.candidateDatabase?.[field],replay.sourceDatabase?.[field],
      'postgres_c51_rehearsal_database_changed:'+field);
  const windowBytes=await read(value.captureWindowPath);
  validatePostgresC51CaptureWindow(JSON.parse(windowBytes),capture,value.files?.[value.capturePath],
    value.executedSources?.[SOURCE+'postgres-c51-capture-window.py']);
  return replay;
}

// The unchanged capture guard requires one shared Internal network. The local C36
// database and C48 issuer were moved to a labelled temporary --internal network only
// for the capture and restored to their Compose network, aliases and addresses.
export function validatePostgresC51CaptureWindow(window,capture,captureSha256,sourceSha256){
  assert.equal(window.schema,'otziv-postgres-c51-capture-window-v1','postgres_c51_window_schema');
  assert.equal(window.result,'PASS','postgres_c51_window_failed');
  assert.match(sourceSha256||'',/^[a-f0-9]{64}$/,'postgres_c51_window_source_missing');
  assert.equal(window.executedScriptSha256,sourceSha256,'postgres_c51_window_executed_script');
  assert.deepEqual([window.productionAccess,window.dataWrites,window.containersRestarted],[false,false,false],
    'postgres_c51_window_scope');
  assert.ok(['npipe','unix'].includes(window.dockerEndpoint),'postgres_c51_window_local_docker');
  assert.equal(window.originalNetwork,'otziv-prod-local_internal_net','postgres_c51_window_original_network');
  const temporary=window.temporaryNetwork?.name;
  assert.match(temporary||'',/^otziv-postgres-c51-capture-[a-f0-9]{12}$/,'postgres_c51_window_temporary_network');
  assert.equal(window.temporaryNetwork.label,'com.otziv.postgres-c51-capture.owner='+temporary.slice(-12),
    'postgres_c51_window_ownership');
  assert.equal(window.temporaryNetwork.internal,true,'postgres_c51_window_not_internal');
  assert.equal(window.temporaryNetworkRemaining,0,'postgres_c51_window_network_leak');
  const images={keycloak:C48_KEYCLOAK_REFERENCE,'keycloak-postgres':PARENT_REFERENCE};
  assert.deepEqual(Object.keys(window.before||{}).sort(),Object.keys(images).sort(),'postgres_c51_window_services');
  const keys=['id','imageId','startedAt','restarts','running','networks','portBindings'];
  for(const [service,reference]of Object.entries(images)){
    const before=window.before[service],after=window.after?.[service],isolated=window.isolated?.[service];
    const config=service==='keycloak'?C48_KEYCLOAK_CONFIG:PARENT_CONFIG_ID;
    assert.ok([config,'sha256:'+reference.split('@sha256:')[1]].includes(before.imageId),'postgres_c51_window_image:'+service);
    assert.deepEqual([before.running,before.networks,before.portBindings],[true,[window.originalNetwork],{}],
      'postgres_c51_window_source_state:'+service);
    assert.deepEqual(isolated,{networks:[temporary],aliases:before.attachment?.aliases},'postgres_c51_window_isolation:'+service);
    assert.deepEqual(Object.fromEntries(keys.map(key=>[key,after?.[key]])),Object.fromEntries(keys.map(key=>[key,before[key]])),
      'postgres_c51_window_runtime_changed:'+service);
    assert.deepEqual(after.attachment,before.attachment,'postgres_c51_window_not_restored:'+service);
    const source=capture.sourceBefore?.containers?.[service==='keycloak'?'keycloak':'postgres'];
    assert.deepEqual({id:source?.id,startedAt:source?.startedAt,imageId:source?.imageId},
      {id:before.id,startedAt:before.startedAt,imageId:before.imageId},'postgres_c51_window_capture_source:'+service);
  }
  assert.deepEqual([window.restoredExactly,window.addressesPreserved,window.stackStatus],[true,true,200],
    'postgres_c51_window_restore');
  assert.deepEqual([window.captureExitCode,window.captureResult,window.captureSha256],[0,'PASS',captureSha256],
    'postgres_c51_window_capture');
  const times=[window.windowOpenedAt,window.captureStartedAt,capture.startedAt,capture.completedAt,
    window.captureCompletedAt,window.windowClosedAt].map(value=>Date.parse(value));
  assert.ok(times.every(Number.isFinite)&&times.every((value,index)=>index===0||times[index-1]<=value),
    'postgres_c51_window_order');
}

export async function validatePostgresC51Transition(entry,image,read){
  const value=await validatePostgresC51Acceptance(entry,read);
  await validatePostgresC51Rehearsal(value,read);
  const parentBytes=await read(SOURCE+'c51-parent-postgres.json');
  assert.equal(hash(parentBytes),C36_ACTIVATION_SHA256,'postgres_c51_parent_activation_changed');
  const parent=JSON.parse(parentBytes);
  assert.equal(parent.reference,PARENT_REFERENCE,'postgres_c51_parent_activation_reference');
  assert.equal(parent.manifest?.path,C36_MANIFEST,'postgres_c51_parent_activation_manifest');
  // The frozen C36 entry still replays its own C23 -> C36 acceptance and lineage.
  const prior=await validatePostgresC36Transition(parent,image,read);
  assert.equal(prior.postgresReference,PARENT_REFERENCE,'postgres_c51_transition_parent');
  assert.equal(prior.postgresConfigId,PARENT_CONFIG_ID,'postgres_c51_transition_parent_config');
  assert.deepEqual(prior.postgresC36Pair,{keycloakReference:C35_KEYCLOAK_REFERENCE,keycloakConfigId:C35_KEYCLOAK_CONFIG},
    'postgres_c51_transition_parent_pair');
  return {...prior,postgresReference:entry.reference,postgresConfigId:value.imageConfigId,
    postgresC51Acceptance:entry.postgresC51Acceptance,
    postgresC51Pair:{keycloakReference:C48_KEYCLOAK_REFERENCE,keycloakConfigId:C48_KEYCLOAK_CONFIG},
    ordinaryDeploymentUpgradeAuthorized:false};
}

export async function couplePostgresC51WithKeycloakC48(readiness,entry,read,validateParent){
  assert.ok(readiness?.postgresC51Acceptance,'postgres_c51_coupling_acceptance_missing');
  assert.deepEqual(readiness.postgresC51Pair,{keycloakReference:C48_KEYCLOAK_REFERENCE,keycloakConfigId:C48_KEYCLOAK_CONFIG},
    'postgres_c51_coupling_issuer_pair');
  assert.equal(entry?.reference,C48_KEYCLOAK_REFERENCE,'postgres_c51_coupling_registry_issuer');
  assert.equal(entry.manifest?.path,C48_KEYCLOAK_MANIFEST,'postgres_c51_coupling_registry_manifest');
  const publication=JSON.parse(await read(entry.publication.path));
  const accepted=await validateAptC48Activation('keycloak',publication,entry,read,validateParent);
  assert.equal(accepted.imageConfigId,C48_KEYCLOAK_CONFIG,'postgres_c51_coupling_accepted_issuer');
  assert.deepEqual(entry.migrationAcceptance,entry.aptRefreshAcceptance,'postgres_c51_coupling_issuer_acceptance');
  // The C48 proof independently covers its own pairing with C36. The C51 proof
  // additionally covers the exact active C48 issuer on the upgraded C51 volume.
  return {...readiness,requiredKeycloakReference:entry.reference,requiredKeycloakConfigId:C48_KEYCLOAK_CONFIG,
    acceptancePath:entry.aptRefreshAcceptance.path,proofSha256:entry.aptRefreshAcceptance.sha256};
}
