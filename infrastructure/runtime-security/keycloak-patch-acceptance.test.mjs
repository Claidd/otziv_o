import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {createEvidenceReader,validateActivation} from './reviewed-image-defaults.mjs';
import {validatePostgresTransitionReadiness,assertPostgresKeycloakCoupling} from './postgres-transition-readiness.mjs';
const root=fileURLToPath(new URL('../../',import.meta.url));
const read=await createEvidenceReader(root),hash=b=>createHash('sha256').update(b).digest('hex');
const base=await read('infrastructure/runtime-security/reviewed-images.json');
const images=JSON.parse(base).images;
const path='infrastructure/keycloak/security-generation/c15-netty/proofs/publication-acceptance.json';
const proof=JSON.parse(await read(path));
const bound=async path=>({path,sha256:hash(await read(path))});
const publication=JSON.parse(await read(proof.evidence.keycloakPublication.path));
const entry={component:'keycloak',reference:publication.reference,commit:publication.commit,run:publication.run,attempt:publication.attempt,
  manifest:await bound('infrastructure/runtime-security/reviewed-images-c15-keycloak.json'),publication:proof.evidence.keycloakPublication,
  anonymous:proof.evidence.keycloakAnonymous,migrationAcceptance:await bound(path)};
const pg={component:'postgres',reference:proof.published.postgresReference,databaseTransition:entry.migrationAcceptance};
const pgImage=images.find(x=>x.component==='postgres'),kcImage=images.find(x=>x.component==='keycloak');
const refs=['compose.yaml','compose.prod-local.yaml','docker-compose.yaml'];
const rows=refs.map(path=>({image:entry.reference,references:[{path,service:'keycloak'}]}));
test('new Netty image passes exact published OCI, actual migration, issuer and unchanged PostgreSQL coupling',async()=>{
  assert.equal(await validateActivation(kcImage,entry,base,read),entry.reference);
  const ready=await validatePostgresTransitionReadiness(pg,pgImage,read);
  assert.equal(ready.requiredKeycloakReference,entry.reference);
  assert.equal(ready.acceptancePath,path);
  assertPostgresKeycloakCoupling(ready,entry,rows,kcImage);
  assert.throws(()=>assertPostgresKeycloakCoupling(ready,{...entry,reference:kcImage.sourceBeforeRef},rows,kcImage),/coupling_registry_reference/);
});
for(const [name,key,change,error]of [
  ['old candidate receipt','actualMigration',x=>x.candidate='historical-image','actual_candidate'],
  ['lost old credential','actualMigration',x=>x.criticalCounts.clean_migration.credential.current--,'critical_counts'],
  ['issuer mutation rollback failed','issuerProtocol',x=>x.checks.find(c=>c.name==='failed_journal_rolls_back_actual_user_state').passed=false,'issuer_failed'],
  ['changed custom provider','runtimePatch',x=>x.images[1].files[x.preservedFiles[0]]='a'.repeat(64),'patch_provider'],
  ['new scan findings','rawSecurityScan',x=>{x.Results[0].Vulnerabilities=[{VulnerabilityID:'CVE-fixture',PkgName:'fixture',InstalledVersion:'1',FixedVersion:'2',Severity:'HIGH'}];},'patch_scan_findings'],
])test('patch rejects rehashed evidence with '+name,async()=>{
  const changed=structuredClone(proof),ref=changed.evidence[key],value=JSON.parse(await read(ref.path));change(value);
  const bytes=Buffer.from(JSON.stringify(value));ref.sha256=hash(bytes);
  const outer=Buffer.from(JSON.stringify(changed));
  const reader=p=>p===path?outer:p===ref.path?bytes:read(p);
  await assert.rejects(validatePostgresTransitionReadiness({...pg,databaseTransition:{path,sha256:hash(outer)}},pgImage,reader),new RegExp(error));
});
