import test from 'node:test';
import assert from 'node:assert/strict';
import {createEvidenceReader,validateRepositoryDefaults} from './reviewed-image-defaults.mjs';
import {checkKeycloakC19Evidence,validateKeycloakC19Acceptance,coupleKeycloakC19,KEYCLOAK_C19_ROOT as root} from './keycloak-c19-acceptance.mjs';
const read=await createEvidenceReader(process.cwd()),json=async p=>JSON.parse(await read(p));
const evidence={};
for(const [key,path] of Object.entries({runtime:'runtime.json',capture:'rehearsal/capture.json',replay:'rehearsal/replay.json',
  issuer:'issuer.json',startup:'startup.json',raw:'publication/vulnerabilities.json',candidateConfig:'publication/registry-amd64-config.json'})) evidence[key]=await json(root+path);
evidence.parentConfig=await json('infrastructure/runtime-security/proofs/c15-keycloak-published/publication/registry-amd64-config.json');
const entry=(await json('infrastructure/runtime-security/reviewed-image-activations.json')).images.find(x=>x.component==='keycloak');
const publication=await json(root+'publication/publication.json');
test('published crypto patch preserves real login, database, providers and both rollback paths',async()=>{
  checkKeycloakC19Evidence(evidence);
  await validateKeycloakC19Acceptance(publication,entry,read);
  await validateRepositoryDefaults();
});
for(const [name,mutate] of [
  ['wrong parent',x=>x.runtime.images[0].reference=x.runtime.images[1].reference],
  ['wrong candidate',x=>x.replay.candidate=x.replay.keycloak],
  ['unexpected provider change',x=>x.runtime.images[1].files['opt/keycloak/providers/rogue.jar']={kind:'file',sha256:'a'.repeat(64)}],
  ['wrong library bytes',x=>x.runtime.images[1].files['opt/keycloak/bin/client/lib/bcprov-jdk18on-1.84.jar'].sha256='a'.repeat(64)],
  ['old library version',x=>x.raw.Results.flatMap(r=>r.Packages||[]).find(p=>p.Name?.startsWith('org.bouncycastle:')).Version='1.84'],
  ['missing credential preservation',x=>x.replay.checks=x.replay.checks.filter(c=>c.name!=='existing_roles_and_credentials_preserved')],
  ['missing rollback',x=>x.replay.checks=x.replay.checks.filter(c=>c.name!=='restored_existing_password_login')],
  ['rehearsal resource leak',x=>x.replay.ownedResourcesRemaining.volume=1],
  ['unbound database capture',x=>x.replay.dumpSha256='a'.repeat(64)],
  ['production mutation',x=>x.capture.productionWrites=true],
  ['different database schema',x=>x.replay.checks.find(c=>c.name==='crypto_patch_no_schema_migration').passed=false],
  ['changed raw scan',x=>x.raw.Metadata.ImageID='sha256:'+'a'.repeat(64)],
  ['missing real auth tests',x=>x.issuer.checks=[]],
]) test('rejects '+name,()=>{const changed=structuredClone(evidence);mutate(changed);assert.throws(()=>checkKeycloakC19Evidence(changed),/keycloak_c19_/);});
test('acceptance hashes bind executed source and captured evidence',async()=>{
  const changed=async p=>p===root+'issuer.json'?Buffer.from('{}'):read(p);
  await assert.rejects(validateKeycloakC19Acceptance(publication,entry,changed),/keycloak_c19_evidence_hash/);
});
test('patch cannot authorize a different PostgreSQL or issuer parent',async()=>{
  const patch=await validateKeycloakC19Acceptance(publication,entry,read);
  const ready={requiredKeycloakReference:patch.parentReference,requiredKeycloakConfigId:patch.parentConfigId,
    postgresReference:patch.postgresReference,postgresConfigId:patch.postgresConfigId,ordinaryDeploymentUpgradeAuthorized:false};
  const coupled=await coupleKeycloakC19(ready,entry,read);
  assert.equal(coupled.requiredKeycloakReference,entry.reference);assert.equal(coupled.ordinaryDeploymentUpgradeAuthorized,false);
  for(const key of ['requiredKeycloakReference','requiredKeycloakConfigId','postgresReference','postgresConfigId'])
    await assert.rejects(coupleKeycloakC19({...ready,[key]:'unreviewed'},entry,read),/keycloak_c19_coupling_/);
});
