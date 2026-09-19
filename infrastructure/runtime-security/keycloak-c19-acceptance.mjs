import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validatePublishedKeycloakMigrationAcceptance} from './postgres-transition-readiness.mjs';
import {POSTGRES_C16_REFERENCE} from './postgres-c16-activation.mjs';
import {summarizeReport} from './scan.mjs';

export const KEYCLOAK_C19_REFERENCE = 'ghcr.io/claidd/otziv-security@sha256:6d129626434475b9c0984cc0f766ac28aced19fb7fa430a2c261d18419be1caf';
export const KEYCLOAK_C19_CONFIG = 'sha256:dc2d5a08e5e81af395d21d2ed383875c0fff0c769d397a9e9ca67d7ea677f3eb';
export const KEYCLOAK_C19_ACCEPTANCE_SHA256 = '0169de7481fee03874f0377a545dd53b79bc7bffe550063ebac68d175cd5f374';
export const KEYCLOAK_C19_ROOT = 'infrastructure/runtime-security/proofs/c19-keycloak-published/';
const PARENT = 'ghcr.io/claidd/otziv-security@sha256:bd5687843becb0cdb232c2b864cc3786fafb8fc91f30f531fa2db7fc4d1fc59d';
const PARENT_CONFIG = 'sha256:34e7587586d4d0a8d8330845f43cc58db9196d4dc6c4f120ac70519541453e5e';
const PG_CONFIG = 'sha256:9aff3268aa60897853557e45444da2f6c1d45e311eb543e9eb8e0f49339c5f6e';
const FIELDS = ['User','WorkingDir','Entrypoint','Cmd','Env','Healthcheck','ExposedPorts','Volumes'];
const JARS = {
  'opt/keycloak/bin/client/lib/bcprov-jdk18on-1.84.jar': '20af26bf6060bb8005cc2389916812c1e0e998dc48d2ced7131b89461b54cff7',
  'opt/keycloak/lib/lib/main/org.bouncycastle.bcprov-jdk18on-1.84.jar': '20af26bf6060bb8005cc2389916812c1e0e998dc48d2ced7131b89461b54cff7',
  'opt/keycloak/lib/lib/main/org.bouncycastle.bcutil-jdk18on-1.84.jar': '590f55ed5d68529239898a4a5c4f730b6e37f45d1cfa3fbe51f8485abe32c42d',
  'opt/keycloak/lib/lib/main/org.bouncycastle.bcpkix-jdk18on-1.84.jar': 'c9f82b2d4e99c4bbdfccf684e52cc06ea06a0b567bfd0d08f9c5a3f417055996',
};
const GENERATED = ['generated-bytecode.jar','quarkus-application.dat','transformed-bytecode.jar'].map(x=>'opt/keycloak/lib/quarkus/'+x);
const hash = x => createHash('sha256').update(x).digest('hex');
const equal = (a,b,message) => assert.deepEqual(a,b,'keycloak_c19_'+message);
const check = (condition,message) => assert.ok(condition,'keycloak_c19_'+message);
const checks = (report,names) => {
  equal(report.result,'PASS','proof_failed');
  check(report.checks.length>0&&report.checks.every(x=>x.passed===true),'failed_check');
  for(const name of names) equal(report.checks.filter(x=>x.name===name&&x.passed).length,1,'missing_'+name);
};
const imageId = (id,ref,config) => check([ref.split('@')[1],config].includes(id),'inspected_image');

// A library patch cannot reuse a migration PASS for an unrelated issuer. Check
// the immutable parent, every shipped Keycloak file, fresh database replay,
// actual authentication/rollback and the raw scan of the published candidate.
export function checkKeycloakC19Evidence({runtime,capture,replay,issuer,startup,raw,parentConfig,candidateConfig}) {
  equal(runtime.schema,'otziv-keycloak-c19-runtime-v1','runtime_schema');
  equal(runtime.result,'PASS','runtime_failed'); equal(runtime.productionAccess,false,'production_access');
  equal(runtime.ownedContainersRemaining,0,'inspection_cleanup'); equal(runtime.preservedConfigFields,FIELDS,'runtime_fields');
  equal(runtime.images.length,2,'runtime_images');
  const [before,after]=runtime.images;
  for(const [image,ref,configId,config] of [[before,PARENT,PARENT_CONFIG,parentConfig],[after,KEYCLOAK_C19_REFERENCE,KEYCLOAK_C19_CONFIG,candidateConfig]]) {
    equal(image.reference,ref,'runtime_reference'); equal(image.configId,configId,'runtime_config'); imageId(image.inspectedId,ref,configId);
    equal(image.containerExecuted,false,'inspection_executed'); equal(image.registryRootfsAndConfigMatch,true,'inspection_registry');
    equal(image.rootfs,config.rootfs.diff_ids,'registry_rootfs');
    equal(image.configuration,Object.fromEntries(FIELDS.map(k=>[k,config.config[k]??null])),'registry_configuration');
    check(Object.keys(image.files).length>100,'runtime_inventory_incomplete');
  }
  equal(after.rootfs.slice(0,before.rootfs.length),before.rootfs,'parent_layers');
  equal(after.configuration,before.configuration,'configuration_changed');
  const changed=[...new Set([...Object.keys(before.files),...Object.keys(after.files)])]
    .filter(p=>JSON.stringify(before.files[p])!==JSON.stringify(after.files[p])).sort();
  equal(changed,[...Object.keys(JARS),...GENERATED].sort(),'unexpected_runtime_changes');
  equal(runtime.changedFiles,changed,'runtime_changed_files');
  for(const [path,sha] of Object.entries(JARS)) equal(after.files[path],{mode:path.includes('/bin/client/')?384:420,uid:1000,gid:0,kind:'file',sha256:sha},'library_hash_or_permissions');
  for(const record of [capture,replay]) {
    equal(record.schema,'otziv-keycloak-c19-rehearsal-v1','rehearsal_schema'); checks(record,[]);
    equal(record.productionWrites,false,'production_write'); equal(record.privateRowsPublished,false,'private_rows'); equal(record.publishedPorts,0,'published_ports');
    equal(record.sourcePostgres,POSTGRES_C16_REFERENCE,'postgres_reference'); equal(record.sourcePostgresConfig,PG_CONFIG,'postgres_config');
    equal(record.keycloak,PARENT,'parent_reference'); equal(record.keycloakConfig,PARENT_CONFIG,'parent_config');
  }
  checks(capture,['complete_custom_dump','source_runtime_unchanged','source_critical_identity_stable_during_capture']);
  equal(replay.dumpSha256,capture.dumpSha256,'capture_replay_binding'); equal(replay.candidate,KEYCLOAK_C19_REFERENCE,'rehearsal_candidate');
  imageId(replay.candidateLocalId,KEYCLOAK_C19_REFERENCE,KEYCLOAK_C19_CONFIG);
  equal(replay.ownedResourcesRemaining,{container:0,volume:0,network:0},'rehearsal_cleanup'); check(!replay.cleanupErrors?.length,'rehearsal_cleanup_errors');
  checks(replay,['restored_critical_records_exact','capture_stable_tables_restored_exact','source_existing_password_login',
    'source_roles_and_credentials_preserved','same_volume_all_tables_exact','existing_admin_password_login',
    'existing_roles_and_credentials_preserved','unauthenticated_provider_denied','crypto_patch_no_schema_migration',
    'same_volume_rollback_preserves_all_post_upgrade_writes','same_volume_old_issuer_login','same_volume_old_issuer_credentials_preserved',
    'fresh_rollback_restore_preserves_all_post_upgrade_writes','restored_existing_password_login','restored_roles_and_credentials_preserved']);
  equal(replay.tableCount,102,'restored_table_coverage');
  for(const key of ['serverVersion','encoding','locale','clusterIdentifier','extensions','configSha256','hbaSha256','identSha256'])
    equal(replay.candidateDatabase[key],replay.sourceDatabase[key],'database_changed');
  equal(issuer.schema,'otziv-issuer-generation-proof-v1','issuer_schema'); equal(startup.schema,'otziv-issuer-image-startup-v1','startup_schema');
  for(const proof of [issuer,startup]) {
    equal(proof.production,false,'fixture_production'); equal(proof.image,KEYCLOAK_C19_REFERENCE,'fixture_image');
    imageId(proof.imageId,KEYCLOAK_C19_REFERENCE,KEYCLOAK_C19_CONFIG); checks(proof,[]);
  }
  equal(issuer.realProviderLogin,true,'provider_login'); check(issuer.checks.length>=20,'issuer_coverage');
  equal(issuer.postgresImage,POSTGRES_C16_REFERENCE,'issuer_postgres'); imageId(issuer.postgresImageId,POSTGRES_C16_REFERENCE,PG_CONFIG);
  equal(startup.postgresImage.reference,POSTGRES_C16_REFERENCE,'startup_postgres'); equal(startup.cleanup,'PASS','startup_cleanup');
  checks(startup,['start_imports_and_serves_real_postgres_realm','start_has_no_missing_classpath_error',
    'start-dev_imports_and_serves_real_postgres_realm','start-dev_has_no_missing_classpath_error']);
  equal(raw.Metadata.ImageID,KEYCLOAK_C19_CONFIG,'scan_image'); equal(raw.Metadata.ImageConfig.rootfs,candidateConfig.rootfs,'scan_rootfs');
  const summary=summarizeReport(raw); equal(summary.high+summary.critical,0,'raw_security_findings');
  const packages=raw.Results.flatMap(r=>r.Packages||[]).filter(p=>p.Name?.startsWith('org.bouncycastle:'));
  check(packages.length>=3&&packages.every(p=>p.Version==='1.85'),'bouncycastle_version');
  for(const artifact of ['bcprov','bcutil','bcpkix']) check(packages.some(p=>p.Name==='org.bouncycastle:'+artifact+'-jdk18on'),'bouncycastle_coverage');
}

export async function validateKeycloakC19Acceptance(publication,entry,read) {
  equal(entry.component,'keycloak','component'); equal(entry.reference,KEYCLOAK_C19_REFERENCE,'registered_reference');
  equal(entry.migrationAcceptance,{path:KEYCLOAK_C19_ROOT+'acceptance.json',sha256:KEYCLOAK_C19_ACCEPTANCE_SHA256},'registered_acceptance');
  const anchor=await read(KEYCLOAK_C19_ROOT+'acceptance.json'); equal(hash(anchor),KEYCLOAK_C19_ACCEPTANCE_SHA256,'acceptance_anchor');
  const accepted=JSON.parse(anchor),json=async p=>JSON.parse(await read(p));
  equal(accepted.schema,'otziv-keycloak-c19-acceptance-v1','acceptance_schema'); equal(accepted.result,'PASS','acceptance_failed');
  equal(accepted.reference,KEYCLOAK_C19_REFERENCE,'accepted_reference'); equal(accepted.imageConfigId,KEYCLOAK_C19_CONFIG,'accepted_config');
  for(const [path,sha] of Object.entries({...accepted.files,...accepted.executedSources})) equal(hash(await read(path)),sha,'evidence_hash');
  const parent=await json(KEYCLOAK_C19_ROOT+'parent-activation.json'); equal(parent.reference,PARENT,'registered_parent');
  const prior=await validatePublishedKeycloakMigrationAcceptance(await json(parent.publication.path),parent,read);
  equal(prior.requiredKeycloakConfigId,PARENT_CONFIG,'accepted_parent_config');
  equal(publication,await json(KEYCLOAK_C19_ROOT+'publication/publication.json'),'publication_bytes');
  equal(publication.manifestSet,'c19-keycloak','publication_set'); equal(publication.reference,KEYCLOAK_C19_REFERENCE,'publication_reference');
  equal(publication.imageId,KEYCLOAK_C19_CONFIG,'publication_config');
  for(const key of ['commit','run','attempt']) equal(publication[key],accepted[key],'publication_identity');
  const bound=p=>({path:p,sha256:accepted.files[p]});
  equal(entry.publication,bound(KEYCLOAK_C19_ROOT+'publication/publication.json'),'publication_entry');
  equal(entry.anonymous,bound(KEYCLOAK_C19_ROOT+'anonymous/anonymous-download.json'),'anonymous_entry');
  const evidence={};
  for(const [key,path] of Object.entries({runtime:'runtime.json',capture:'rehearsal/capture.json',replay:'rehearsal/replay.json',
    issuer:'issuer.json',startup:'startup.json',raw:'publication/vulnerabilities.json',candidateConfig:'publication/registry-amd64-config.json'})) evidence[key]=await json(KEYCLOAK_C19_ROOT+path);
  evidence.parentConfig=await json('infrastructure/runtime-security/proofs/c15-keycloak-published/publication/registry-amd64-config.json');
  checkKeycloakC19Evidence(evidence);
  for(const record of [evidence.capture,evidence.replay]) equal(record.executedScriptSha256,accepted.executedSources['infrastructure/runtime-security/keycloak-c19-rehearsal.py'],'executed_rehearsal');
  equal(evidence.runtime.executedScriptSha256,accepted.executedSources['infrastructure/runtime-security/keycloak-c19-runtime.py'],'executed_inspection');
  return {parentReference:PARENT,parentConfigId:PARENT_CONFIG,postgresReference:POSTGRES_C16_REFERENCE,postgresConfigId:PG_CONFIG,
    requiredKeycloakReference:KEYCLOAK_C19_REFERENCE,requiredKeycloakConfigId:KEYCLOAK_C19_CONFIG,
    acceptancePath:KEYCLOAK_C19_ROOT+'acceptance.json',proofSha256:KEYCLOAK_C19_ACCEPTANCE_SHA256};
}

export async function coupleKeycloakC19(readiness,entry,read) {
  if(entry?.reference!==KEYCLOAK_C19_REFERENCE) return readiness;
  const patch=await validateKeycloakC19Acceptance(JSON.parse(await read(entry.publication.path)),entry,read);
  equal(readiness.requiredKeycloakReference,patch.parentReference,'coupling_parent_reference');
  equal(readiness.requiredKeycloakConfigId,patch.parentConfigId,'coupling_parent_config');
  equal(readiness.postgresReference,patch.postgresReference,'coupling_postgres_reference');
  equal(readiness.postgresConfigId,patch.postgresConfigId,'coupling_postgres_config');
  return {...readiness,requiredKeycloakReference:patch.requiredKeycloakReference,requiredKeycloakConfigId:patch.requiredKeycloakConfigId,
    acceptancePath:patch.acceptancePath,proofSha256:patch.proofSha256};
}
