import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {validatePostgresActivationScan,validatePostgresTransitionReadiness,POSTGRES_C16_REFERENCE} from './postgres-c16-activation.mjs';
import {validateActivation,createEvidenceReader,validateRepositoryDefaults} from './reviewed-image-defaults.mjs';
const root=new URL('../../',import.meta.url),R='infrastructure/runtime-security/',P=R+'proofs/c16-postgres-published/';
const read=await createEvidenceReader(root.pathname.replace(/^\/(\w:)/,'$1'));
const pub=JSON.parse(await read(P+'publication/publication.json'));
const manifest=await read(R+'reviewed-images.json'),image=JSON.parse(manifest).images.find(x=>x.component==='postgres');
const entry=JSON.parse(await read(R+'reviewed-image-activations.json')).images.find(x=>x.component==='postgres');
const changedReader=(path,mutate)=>async key=>{
  const bytes=await read(key);if(key!==path)return bytes;
  const value=JSON.parse(bytes);mutate(value);return Buffer.from(JSON.stringify(value));
};

test('published runtime, all OCI evidence, restore and rollback bind the activated digest',async()=>{
  assert.equal(await validateActivation(image,entry,manifest,read),POSTGRES_C16_REFERENCE);
  const readiness=await validatePostgresTransitionReadiness(entry,image,read);
  assert.equal(readiness.ordinaryDeploymentUpgradeAuthorized,false);
  assert.equal(readiness.postgresReference,POSTGRES_C16_REFERENCE);
  await validateRepositoryDefaults(root.pathname.replace(/^\/(\w:)/,'$1'));
});
for(const [name,path,mutate]of [
  ['altered raw scanner findings',P+'publication/vulnerabilities.json',x=>x.Results[0].Vulnerabilities.pop()],
  ['invented PASS receipt',P+'publication/vulnerabilities.adjudications.json',x=>x.postgres.decisions=[]],
  ['substituted image',P+'verification.json',x=>x.reference='ghcr.io/claidd/otziv-security@sha256:'+'0'.repeat(64)],
  ['missing rollback check',P+'rehearsal/replay.json',x=>x.checks=x.checks.filter(c=>!c.name.startsWith('same_volume_rollback'))],
  ['retained rehearsal volume',P+'rehearsal/replay.json',x=>x.ownedResourcesRemaining.volume=1],
  ['different source capture',P+'rehearsal/capture.json',x=>x.dumpSha256='0'.repeat(64)],
  ['different issuer',P+'rehearsal/replay.json',x=>x.keycloak='ghcr.io/claidd/otziv-security@sha256:'+'0'.repeat(64)],
  ['rewritten outer review',P+'acceptance.json',x=>x.files={}],
])test(name+' cannot authorize activation',async()=>{
  await assert.rejects(validatePostgresTransitionReadiness(entry,image,changedReader(path,mutate)));
  await assert.rejects(validatePostgresActivationScan(pub,P+'publication/publication.json',changedReader(path,mutate)));
});
test('caller-supplied publication or transition references cannot bypass retained evidence',async()=>{
  await assert.rejects(validatePostgresActivationScan({...pub,imageId:'sha256:'+'0'.repeat(64)},P+'publication/publication.json',read));
  await assert.rejects(validatePostgresTransitionReadiness({...entry,databaseTransition:{...entry.databaseTransition,sha256:'0'.repeat(64)}},image,read));
});
