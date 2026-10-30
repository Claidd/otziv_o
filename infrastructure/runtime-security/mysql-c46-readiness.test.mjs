import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {validateDatabaseTransitionReadiness,MYSQL_C46_REFERENCE,MYSQL_C46_REHEARSAL_SHA256} from './mysql-c46-readiness.mjs';

const root=new URL('../../',import.meta.url),read=path=>readFile(new URL(path,root));
const entry=JSON.parse(await read('infrastructure/runtime-security/reviewed-image-activations.json')).images.find(item=>item.component==='mysql');
const image=JSON.parse(await read('infrastructure/runtime-security/reviewed-images.json')).images.find(item=>item.component==='mysql');
const proofRoot='infrastructure/runtime-security/proofs/c46-mysql/';

test('published curl-only MySQL refresh replays C42 parent and exact same-volume rehearsal without authorizing ordinary cutover',async()=>{
  const result=await validateDatabaseTransitionReadiness(entry,image,read);
  assert.equal(result.reference,MYSQL_C46_REFERENCE);
  assert.equal(result.proofSha256,MYSQL_C46_REHEARSAL_SHA256);
  assert.equal(result.refreshType,'SAME_ENGINE_CURL_ONLY');
  assert.equal(result.ordinaryDeploymentUpgradeAuthorized,false);
});

for(const [name,path,mutate]of [
  ['changed parent','parent-activation.json',value=>value.reference='ghcr.io/claidd/otziv-security@sha256:'+'0'.repeat(64)],
  ['missing rollback','rehearsal.json',value=>value.checks.pop()],
  ['unpatched library','rehearsal.json',value=>value.candidateCurl=value.sourceCurl],
  ['preliminary run','rehearsal.json',value=>value.preliminary=true],
  ['unrelated package change','rehearsal.json',value=>value.candidateOtherPackagesSha256='0'.repeat(64)],
  ['changed server binary','rehearsal.json',value=>value.candidateMysqldSha256='0'.repeat(64)],
  ['different data','rehearsal.json',value=>value.after.rows++],
  ['failed rehearsal','rehearsal.json',value=>value.result='FAIL'],
  ['wrong publication','publication/publication.json',value=>value.imageId='sha256:'+'0'.repeat(64)],
  ['invented summary','publication/publication.json',value=>value.security.high=1],
  ['hidden CVE','publication/vulnerabilities.json',value=>value.Results[0].Vulnerabilities=[{VulnerabilityID:'CVE-2099-12345',Severity:'HIGH'}]],
  ['false exemption','publication/vulnerabilities.adjudications.json',value=>value.decisions=[{cve:'CVE-2099-12345'}]],
])test(`${name} is rejected`,async()=>{
  const changedPath=proofRoot+path,value=JSON.parse(await read(changedPath));mutate(value);
  const bytes=Buffer.from(JSON.stringify(value)),changedEntry=structuredClone(entry);
  if(path==='rehearsal.json')changedEntry.databaseTransition.sha256=createHash('sha256').update(bytes).digest('hex');
  await assert.rejects(validateDatabaseTransitionReadiness(changedEntry,image,key=>key===changedPath?bytes:read(key)));
});
