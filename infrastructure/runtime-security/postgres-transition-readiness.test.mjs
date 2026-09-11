import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
import {createEvidenceReader,validateActivation,validateReviewedDefaults} from './reviewed-image-defaults.mjs';
import {inventory} from './upstream-images.mjs';
import {validatePostgresTransitionFoundation,checkKeycloakMigrationRuntime,checkPostgresSourceContinuity,
  checkPublishedKeycloakRuntimeComparison,validatePostgresTransitionReadiness,validatePublishedKeycloakMigrationAcceptance,assertPostgresKeycloakCoupling} from './postgres-transition-readiness.mjs';

const ROOT=fileURLToPath(new URL('../../',import.meta.url));
const CONTEXT='infrastructure/keycloak/security-generation/c14-migration-fix/';
const foundationPath=CONTEXT+'proofs/review.json';
const acceptancePath=CONTEXT+'proofs/publication-acceptance.json';
const read=path=>readFile(resolve(ROOT,path));
const json=async path=>JSON.parse(await read(path));
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const foundation=await json(foundationPath),e=foundation.evidence;
const [actual,issuer,before,after,continuity]=await Promise.all([e.actualMigration,e.issuerProtocol,e.sourceInventoryBefore,e.sourceInventoryAfter,e.sourceContinuityCleanup].map(ref=>json(ref.path)));
const expected={keycloakReference:foundation.testedCandidate.keycloakReference,keycloakImageId:foundation.testedCandidate.keycloakManifestDigest,
  dumpSha256:foundation.source.dumpSha256,scriptSha256:e.executedReplay.sha256};
const continuityExpected={beforeSha256:e.sourceInventoryBefore.sha256,afterSha256:e.sourceInventoryAfter.sha256,completedAt:actual.completedAt};

test('frozen foundation validates the actual local 52 cases while keeping publication acceptance closed',async()=>{
  assert.deepEqual(await validatePostgresTransitionFoundation(read),{result:'PASS',scope:'EXACT_LOCAL_CANDIDATE_PREPARATION',
    foundationSha256:'494f8290dbd87f96f6dcf09480ad82c2fcc250e953d4bce0582efa5835c53faa',publishedKeycloakAccepted:false,ordinaryDeploymentUpgradeAuthorized:false});
});
for(const [name,path]of [
  ['foundation',foundationPath],['historical failure',e.sourceCapture.path],['actual source capture inventory',e.sourceInventoryBefore.path],
  ['fault and rollback runtime',e.actualMigration.path],['executed migration harness',e.executedReplay.path],
  ['executed runtime helper',e.executedReplayHelper.path],['reviewed recipe',CONTEXT+'Dockerfile'],
  ['independent security scan',CONTEXT+'proofs/security-v3/vulnerabilities.json'],
])test('foundation rejects changed '+name,async()=>{
  await assert.rejects(validatePostgresTransitionFoundation(async candidate=>candidate===path?Buffer.concat([await read(candidate),Buffer.from(' ')]):read(candidate)),/postgres_transition_evidence_changed/);
});
test('rehashing the whole editable foundation cannot erase the historical FAIL',async()=>{
  const changed=structuredClone(foundation);changed.composition.sameVolumePostgresUpgrade.recordResult='PASS';
  await assert.rejects(validatePostgresTransitionFoundation(path=>path===foundationPath?Buffer.from(JSON.stringify(changed)):read(path)),/postgres_transition_evidence_changed/);
});
test('actual runtime evidence reproduces the reviewed local migration and protocol',()=>checkKeycloakMigrationRuntime(actual,issuer,expected));
for(const [name,change,error]of [
  ['wrong candidate',x=>x.candidate='ghcr.io/claidd/otziv-security@sha256:'+'a'.repeat(64),'actual_candidate'],
  ['wrong manifest',x=>x.imageId='sha256:'+'a'.repeat(64),'actual_image'],
  ['wrong PostgreSQL',x=>x.postgresImageId='sha256:'+'a'.repeat(64),'actual_postgres'],
  ['another dump',x=>x.sourceDumpSha256='a'.repeat(64),'actual_dump'],
  ['different harness',x=>x.scriptSha256='a'.repeat(64),'actual_script'],
  ['production access',x=>x.productionAccess=true,'actual_production'],
  ['private rows published',x=>x.privateRowsPublished=true,'private_rows'],
  ['runtime failed',x=>x.result='FAIL','actual_result'],
  ['rollback failed',x=>x.freshRollbackResult='FAIL','actual_freshRollbackResult'],
  ['missing fault rollback',x=>{x.checks=x.checks.filter(c=>c.name!=='fresh_transaction_no_partial_critical_model_changes');},'actual_coverage'],
  ['failed fault rollback',x=>{x.checks.find(c=>c.name==='fresh_transaction_no_partial_critical_model_changes').passed=false;},'actual_failed'],
  ['duplicate PASS hides missing check',x=>{x.checks[0]=x.checks[1];},'actual_duplicate_check'],
  ['early fault before actual insert',x=>x.injectedRoleInsertAttempts=1,'fault_too_early'],
  ['fault never reached',x=>x.injectedFailureCode='connection_failed','fault_not_injected'],
  ['missing composites',x=>x.organizationRoles.clean_migration.adminComposites=8,'organization_roles'],
  ['missing view to query',x=>x.organizationRoles.recovered_migration.viewQueryComposites=2,'organization_roles'],
  ['second start loses role',x=>x.organizationRoles.second_start.rolesPresent=8,'organization_roles'],
  ['lost credential',x=>x.criticalCounts.clean_migration.credential.current=30,'critical_counts'],
  ['unexpected role addition',x=>x.criticalCounts.recovered_migration.keycloak_role.current=99,'critical_counts'],
  ['lost role mapping',x=>x.criticalCounts.recovered_migration.user_role_mapping.current=66,'critical_counts'],
  ['rollback login skipped',x=>x.keycloakChecks['rollback-kc'].adminRead=false,'existing_login'],
  ['provider migration absent',x=>x.providerTableCount=0,'provider_missing'],
  ['retained resources',x=>x.cleanupErrors=['volume_busy'],'actual_cleanup'],
  ['invalid clock',x=>x.completedAt='not-a-date','actual_completed_time'],
])test('runtime rejects '+name,()=>{
  const changed=structuredClone(actual);change(changed);assert.throws(()=>checkKeycloakMigrationRuntime(changed,issuer,expected),new RegExp('postgres_transition_'+error));
});
for(const [name,change,error]of [
  ['local protocol transferred to published image',x=>x.imageId='sha256:'+'a'.repeat(64),'issuer_image'],
  ['protocol on wrong PostgreSQL',x=>x.postgresImageId='sha256:'+'a'.repeat(64),'issuer_postgres'],
  ['fake login',x=>x.realProviderLogin=false,'issuer_login'],
  ['missing session refresh gate',x=>{x.checks=x.checks.filter(c=>c.name!=='refresh_preserves_original_generation');},'issuer_coverage'],
  ['failed security rollback',x=>{x.checks.find(c=>c.name==='failed_journal_rolls_back_actual_user_state').passed=false;},'issuer_failed'],
])test('issuer rejects '+name,()=>{
  const changed=structuredClone(issuer);change(changed);assert.throws(()=>checkKeycloakMigrationRuntime(actual,changed,expected),new RegExp('postgres_transition_'+error));
});
test('source observation proves metadata continuity without claiming equality of live rows',()=>checkPostgresSourceContinuity(before,after,continuity,continuityExpected));
for(const [name,change,error]of [
  ['stale observation',x=>x.after.observedAt='2026-09-08T15:00:00Z','inventory_stale'],
  ['stale continuity receipt',x=>x.continuity.observedAt='2026-09-08T15:00:00Z','continuity_stale'],
  ['different source container',x=>x.after.source.keycloak.containerId='a'.repeat(64),'source_container_changed'],
  ['different source volume',x=>x.after.volume.name='replacement','source_volume_changed'],
  ['changed config',x=>x.after.postgres.configSha256='a'.repeat(64),'source_configSha256'],
  ['changed roles',x=>x.after.postgres.roleCounts={altered:1},'source_roleCounts'],
  ['changed locale',x=>x.after.postgres.locale={collate:'C'},'source_locale'],
  ['live-row equality claim',x=>x.continuity.sourceRowSnapshotEqualityAfterRehearsalClaimed=true,'live_rows_claim'],
  ['unobserved cleanup',x=>delete x.continuity.ownedRehearsalResourcesRemaining,'resources_retained'],
  ['owned volume remains',x=>x.continuity.ownedRehearsalResourcesRemaining.volumes=1,'resources_retained'],
  ['receipt references another source',x=>x.continuity.sourceBeforeSha256='a'.repeat(64),'continuity_before'],
  ['omitted catalog coverage',x=>x.continuity.comparedPostgresFields.pop(),'continuity_coverage'],
  ['source write',x=>x.continuity.productionWrites=true,'continuity_writes'],
])test('continuity rejects '+name,()=>{
  const changed=structuredClone({before,after,continuity});change(changed);
  assert.throws(()=>checkPostgresSourceContinuity(changed.before,changed.after,changed.continuity,continuityExpected),new RegExp('postgres_transition_'+error));
});

// The first published image's inspected bytes are useful parser evidence only:
// its failed anonymous publication cannot authorize activation.
const comparison=await json('infrastructure/runtime-security/proofs/c14-keycloak-published/runtime/comparison.json');
const comparisonExpected={reference:comparison.image,config:comparison.config,localImage:foundation.testedCandidate.keycloakManifestDigest,
  localConfig:foundation.testedCandidate.keycloakConfigId,sourceReviewSha256:e.securityReview.sha256,inventorySha256:comparison.runtimeInventorySha256,
  buildInputs:(await json(e.sourceInputs.path)).buildInputs};
test('actual first-publication rootfs comparison parses without conferring migration or publication acceptance',()=>checkPublishedKeycloakRuntimeComparison(comparison,comparisonExpected));
for(const [name,change,error]of [
  ['unexpected file',x=>x.unexpectedPaths=['opt/keycloak/lib/changed.jar'],'unexpected_paths'],
  ['changed vendor JAR',x=>x.patchedVendorJarsByteEqual['opt/keycloak/lib/lib/main/org.keycloak.keycloak-model-infinispan-26.7.3.jar']=false,'vendor_jars'],
  ['different provenance',x=>x.actualProvenanceByteEqual=false,'actualProvenanceByteEqual'],
  ['another rootfs',x=>x.actualPulledRootfsMatchesRegistry=false,'actualPulledRootfsMatchesRegistry'],
  ['changed entrypoint',x=>x.runtimeConfigurationChangedKeys.push('Entrypoint'),'launch'],
  ['expanded exclusion',x=>x.dockerGeneratedFilesExcluded.push('opt/keycloak/lib/changed.jar'),'exclusions'],
  ['source-only migration transfer',x=>x.localV3RuntimeReceiptTransferAllowed=true,'local_transfer'],
  ['missing published replay',x=>x.publishedRuntimeReplayRequired=false,'publishedRuntimeReplayRequired'],
  ['changed recipe',x=>x.sourceBuildInputsSha256.Dockerfile='a'.repeat(64),'recipe'],
  ['different inventory',x=>x.runtimeInventorySha256='a'.repeat(64),'inventory'],
])test('runtime comparison rejects '+name,()=>{
  const changed=structuredClone(comparison);change(changed);
  assert.throws(()=>checkPublishedKeycloakRuntimeComparison(changed,comparisonExpected),new RegExp('postgres_transition_comparison_'+error));
});

const pgEntry={component:'postgres',reference:foundation.testedCandidate.postgresReference};
const pgImage={component:'postgres',sourceBeforeRef:foundation.source.postgresReference};
test('PG registration without exact published runtime acceptance fails closed',async()=>assert.rejects(validatePostgresTransitionReadiness(pgEntry,pgImage,read),/published_acceptance_required/));
test('fixed Keycloak activation also requires migration even before PG is selected',async()=>assert.rejects(validatePublishedKeycloakMigrationAcceptance({component:'keycloak'},{component:'keycloak'},read),/published_acceptance_required/));
test('local proof cannot serve as published acceptance',async()=>assert.rejects(validatePostgresTransitionReadiness({...pgEntry,databaseTransition:{path:foundationPath,sha256:hash(await read(foundationPath))}},pgImage,read),/published_acceptance_required/));
test('forged outer PASS with another foundation is rejected before trusting its evidence',async()=>{
  const value={schema:'otziv-postgres-keycloak-published-acceptance-v1',result:'PASS',role:'COORDINATED_CANDIDATE_PREPARATION',sourceWrites:false,production:false,vpsCutoverExecuted:false,
    localReview:{path:foundationPath,sha256:'a'.repeat(64)}};
  const bytes=Buffer.from(JSON.stringify(value));
  await assert.rejects(validatePostgresTransitionReadiness({...pgEntry,databaseTransition:{path:acceptancePath,sha256:hash(bytes)}},pgImage,path=>path===acceptancePath?bytes:read(path)),/foundation_binding/);
});
test('a rehashed published envelope cannot transplant the local v3 runtime by source parity',async()=>{
  const value={schema:'otziv-postgres-keycloak-published-acceptance-v1',result:'PASS',role:'COORDINATED_CANDIDATE_PREPARATION',sourceWrites:false,production:false,vpsCutoverExecuted:false,
    localReview:{path:foundationPath,sha256:hash(await read(foundationPath))},
    published:{postgresReference:foundation.testedCandidate.postgresReference,postgresConfigId:foundation.testedCandidate.postgresConfigId,
      keycloakReference:'ghcr.io/claidd/otziv-security@sha256:'+'e'.repeat(64),keycloakConfigId:'sha256:'+'f'.repeat(64)},
    evidence:{keycloakPublication:e.actualMigration,keycloakAnonymous:e.actualMigration,actualMigration:e.actualMigration,
      issuerProtocol:e.issuerProtocol,securityReview:e.securityReview,runtimeInventory:e.runtimeInventory,
      sourceInventoryAfter:e.sourceInventoryAfter,sourceContinuityCleanup:e.sourceContinuityCleanup}};
  const bytes=Buffer.from(JSON.stringify(value));
  await assert.rejects(validatePostgresTransitionReadiness({...pgEntry,databaseTransition:{path:acceptancePath,sha256:hash(bytes)}},pgImage,path=>path===acceptancePath?bytes:read(path)),/published_replay_missing/);
});

const kcReference='ghcr.io/claidd/otziv-security@sha256:'+'e'.repeat(64);
const readiness={component:'postgres',mode:'COORDINATED_CANDIDATE_PREPARATION',ordinaryDeploymentUpgradeAuthorized:false,
  requiredKeycloakReference:kcReference,proofSha256:'a'.repeat(64)};
const kcEntry={component:'keycloak',reference:kcReference,migrationAcceptance:{path:acceptancePath,sha256:readiness.proofSha256}};
const refs=['compose.yaml','compose.prod-local.yaml','docker-compose.yaml'].map(path=>({path,service:'keycloak'}));
const rows=refs.map(ref=>({image:kcReference,references:[ref]}));
const kcImage={component:'keycloak',sourceBeforeRef:foundation.source.keycloakReference,defaultReferencesBefore:refs};
test('coupling covers all three exact Compose service/path references and registered acceptance',()=>assertPostgresKeycloakCoupling(readiness,kcEntry,rows,kcImage));
for(const [name,change,error]of [
  ['registry absent',x=>x.entry=undefined,'coupling_registry_missing'],
  ['wrong registered image',x=>x.entry.reference=foundation.source.keycloakReference,'coupling_registry_reference'],
  ['another acceptance',x=>x.entry.migrationAcceptance.sha256='b'.repeat(64),'coupling_acceptance'],
  ['old KC only in prod-local',x=>x.rows[1].image=foundation.source.keycloakReference,'coupling_compose_reference'],
  ['missing prod-local',x=>x.rows.splice(1,1),'coupling_missing_or_duplicate'],
  ['duplicate production service',x=>x.rows.push(structuredClone(x.rows[0])),'coupling_missing_or_duplicate'],
  ['reduced manifest coverage',x=>x.image.defaultReferencesBefore.pop(),'coupling_coverage'],
  ['automatic upgrade authority',x=>x.ready.ordinaryDeploymentUpgradeAuthorized=true,'coupling_authority'],
])test('coupling rejects '+name,()=>{
  const x=structuredClone({ready:readiness,entry:kcEntry,rows,image:kcImage});change(x);
  assert.throws(()=>assertPostgresKeycloakCoupling(x.ready,x.entry,x.rows,x.image),new RegExp('postgres_transition_'+error));
});

const FINAL_ROOT='infrastructure/runtime-security/proofs/c14-keycloak-published/final/';
const baselineBytes=await read('infrastructure/runtime-security/reviewed-images.json'),baseline=JSON.parse(baselineBytes);
const realPgImage=baseline.images.find(x=>x.component==='postgres'),realKcImage=baseline.images.find(x=>x.component==='keycloak');
const physicalRead=await createEvidenceReader(ROOT);
async function publishedFixture(){
  const files=new Map();
  const reader=async path=>{
    if(files.has(path)){assert.notEqual(files.get(path),null,'deliberately_missing_transition_evidence');return files.get(path);}
    return physicalRead(path);
  };
  const readJson=async path=>JSON.parse(await reader(path));
  const proof=await readJson(acceptancePath),proofHash=hash(await reader(acceptancePath));
  const pgEntry=await readJson('infrastructure/runtime-security/proofs/c14-postgres-published/publication-candidate.json');
  const kcEntry=await readJson(FINAL_ROOT+'activation-candidate.json');
  pgEntry.databaseTransition={path:acceptancePath,sha256:proofHash};kcEntry.migrationAcceptance={...pgEntry.databaseTransition};
  const publication=await readJson(kcEntry.publication.path);
  function seal(){const bytes=Buffer.from(JSON.stringify(proof));files.set(acceptancePath,bytes);pgEntry.databaseTransition.sha256=hash(bytes);kcEntry.migrationAcceptance.sha256=hash(bytes);}
  async function rewrite(key,change){const ref=proof.evidence[key],value=await readJson(ref.path);change(value);const bytes=Buffer.from(JSON.stringify(value));files.set(ref.path,bytes);ref.sha256=hash(bytes);seal();}
  const validate=()=>validatePostgresTransitionReadiness(pgEntry,realPgImage,reader);
  return {files,read:reader,json:readJson,proof,pgEntry,kcEntry,publication,seal,rewrite,validate};
}
test('exact published c6ee graph passes through physical evidence reader with all 52 migration and 20 issuer checks',async()=>{
  const f=await publishedFixture(),result=await f.validate();
  assert.equal(result.component,'postgres');assert.equal(result.reference,f.proof.published.postgresReference);
  assert.equal(result.requiredKeycloakReference,f.proof.published.keycloakReference);assert.equal(result.requiredKeycloakConfigId,f.proof.published.keycloakConfigId);
  assert.equal(result.proofSha256,'b4f1ce533dd116b4546d8fa6cd6e27731e357865d08cb100e9f33332511b1dfd');
  assert.equal(result.vpsCutoverExecuted,false);assert.equal(result.ordinaryDeploymentUpgradeAuthorized,false);
});
test('exact fixed Keycloak passes mandatory migration plus full publication and anonymous OCI verification',async()=>{
  const f=await publishedFixture();
  assert.equal((await validatePublishedKeycloakMigrationAcceptance(f.publication,f.kcEntry,f.read)).reference,f.kcEntry.reference);
  assert.equal(await validateActivation(realKcImage,f.kcEntry,baselineBytes,f.read),f.kcEntry.reference);
});
function selectedRows(pg,kc){
  return baseline.images.map(image=>({image:image.component==='postgres'?pg:image.component==='keycloak'?kc:
    inventory([{path:'source',text:'services:\n  source:\n    image: '+image.sourceBeforeRef+'\n'}])[0].image,
  references:image.defaultReferencesBefore.map(({path,service})=>({path,service}))}));
}
test('actual PG plus fixed Keycloak registrations pass shared defaults with all three coupled services',async()=>{
  const f=await publishedFixture();
  const checks=await validateReviewedDefaults(selectedRows(f.pgEntry.reference,f.kcEntry.reference),baselineBytes,
    {schema:'otziv-reviewed-image-activations-v1',images:[f.pgEntry,f.kcEntry]},f.read);
  const pg=checks.filter(x=>x.component==='postgres');assert.equal(pg.length,3);
  assert.ok(pg.every(x=>x.databaseTransition==='COORDINATED_CANDIDATE_PREPARATION'&&x.ordinaryDeploymentUpgradeAuthorized===false));
});
test('shared fixed Keycloak activation cannot omit migration proof even when every PG default remains old',async()=>{
  const f=await publishedFixture();delete f.kcEntry.migrationAcceptance;
  await assert.rejects(validateReviewedDefaults(selectedRows(realPgImage.sourceBeforeRef,f.kcEntry.reference),baselineBytes,
    {schema:'otziv-reviewed-image-activations-v1',images:[f.kcEntry]},f.read),/published_acceptance_required/);
});
test('shared PG registration rejects one Compose still selecting historical Keycloak',async()=>{
  const f=await publishedFixture(),rows=selectedRows(f.pgEntry.reference,f.kcEntry.reference);
  const row=rows.find(x=>x.references.some(ref=>ref.service==='keycloak'));
  const ref=row.references.pop();rows.push({image:realKcImage.sourceBeforeRef,references:[ref]});
  await assert.rejects(validateReviewedDefaults(rows,baselineBytes,{schema:'otziv-reviewed-image-activations-v1',images:[f.pgEntry,f.kcEntry]},f.read),/coupling_compose_reference/);
});
for(const key of ['keycloakPublication','keycloakAnonymous','actualMigration','issuerProtocol','securityReview','runtimeInventory','sourceInventoryAfter','sourceContinuityCleanup'])
  test('complete acceptance cannot hide missing '+key,async()=>{const f=await publishedFixture();f.files.set(f.proof.evidence[key].path,null);await assert.rejects(f.validate(),/deliberately_missing_transition_evidence/);});
for(const [name,key,change,error]of [
  ['other published image','actualMigration',x=>x.imageId=foundation.testedCandidate.keycloakManifestDigest,'actual_image'],
  ['failed fault rollback','actualMigration',x=>{x.checks.find(c=>c.name==='fresh_transaction_no_partial_critical_model_changes').passed=false;},'actual_failed'],
  ['lost role mapping','actualMigration',x=>x.criticalCounts.recovered_migration.user_role_mapping.current=66,'critical_counts'],
  ['missing admin composite','actualMigration',x=>x.organizationRoles.clean_migration.adminComposites=8,'organization_roles'],
  ['fresh old-version rollback omitted','actualMigration',x=>{x.checks=x.checks.filter(c=>c.name!=='fresh_17_10_rollback_all_88_tables');},'actual_coverage'],
  ['local issuer transplanted','issuerProtocol',x=>x.imageId=foundation.testedCandidate.keycloakManifestDigest,'issuer_image'],
  ['cleanup still pending','sourceContinuityCleanup',x=>x.ownedRehearsalResourcesRemaining.volumes=1,'resources_retained'],
])test('resealed actual acceptance rejects '+name,async()=>{
  const f=await publishedFixture();await f.rewrite(key,change);await assert.rejects(f.validate(),new RegExp('postgres_transition_'+error));
});
test('resealed source observation from before published migration remains stale',async()=>{
  const f=await publishedFixture();await f.rewrite('sourceInventoryAfter',x=>x.observedAt='2026-09-08T15:00:00Z');
  await f.rewrite('sourceContinuityCleanup',x=>x.sourceAfterSha256=f.proof.evidence.sourceInventoryAfter.sha256);
  await assert.rejects(f.validate(),/postgres_transition_inventory_stale/);
});
test('resealing all report hashes cannot hide a new HIGH finding behind zero presentation counters',async()=>{
  const f=await publishedFixture(),path=FINAL_ROOT+'publication/vulnerabilities.json',raw=await f.json(path);
  raw.Results[0].Vulnerabilities??=[];
  raw.Results[0].Vulnerabilities.push({VulnerabilityID:'CVE-2099-99999',PkgName:'synthetic-new-finding',InstalledVersion:'1',FixedVersion:'2',Severity:'HIGH'});
  const bytes=Buffer.from(JSON.stringify(raw));f.files.set(path,bytes);
  await f.rewrite('securityReview',x=>{x.rawReportSha256=hash(bytes);x.files['publication/vulnerabilities.json']=hash(bytes);});
  await assert.rejects(f.validate(),/postgres_transition_raw_findings/);
});
test('resealed PASS cannot accept an unexpected executable payload path',async()=>{
  const f=await publishedFixture(),path=FINAL_ROOT+'runtime/comparison.json',comparison=await f.json(path);
  comparison.unexpectedPaths=['opt/keycloak/lib/synthetic-unreviewed.jar'];
  const bytes=Buffer.from(JSON.stringify(comparison));f.files.set(path,bytes);
  await f.rewrite('securityReview',x=>{x.runtimeComparisonSha256=hash(bytes);x.files['runtime/comparison.json']=hash(bytes);});
  await assert.rejects(f.validate(),/postgres_transition_comparison_unexpected_paths/);
});
test('even a semantically complete rehashed outer receipt must match the independently reviewed final anchor',async()=>{
  const f=await publishedFixture();f.proof.completedAt='2026-09-08T16:25:27Z';f.seal();
  await assert.rejects(f.validate(),/postgres_transition_published_acceptance_anchor/);
});
