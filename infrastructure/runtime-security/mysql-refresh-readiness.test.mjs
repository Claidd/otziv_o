import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {validateDatabaseTransitionReadiness,MYSQL_REFRESH_REFERENCE} from './mysql-refresh-readiness.mjs';
const root=new URL('../../',import.meta.url),read=p=>readFile(new URL(p,root));
const entry=JSON.parse(await read('infrastructure/runtime-security/reviewed-image-activations.json')).images.find(x=>x.component==='mysql');
const image=JSON.parse(await read('infrastructure/runtime-security/reviewed-images.json')).images.find(x=>x.component==='mysql');

test('published OS refresh replays original activation and exact rollback rehearsal without authorizing ordinary cutover',async()=>{
  const result=await validateDatabaseTransitionReadiness(entry,image,read);
  assert.equal(result.reference,MYSQL_REFRESH_REFERENCE);
  assert.equal(result.ordinaryDeploymentUpgradeAuthorized,false);
});
for(const [name,path,mutate]of [
  ['missing rollback','rehearsal.json',v=>v.checks.pop()],
  ['changed engine','rehearsal.json',v=>v.candidateMysqldSha256='0'.repeat(64)],
  ['different data','rehearsal.json',v=>v.after.rows++],
  ['failed rehearsal','rehearsal.json',v=>v.result='FAIL'],
  ['wrong publication','publication/publication.json',v=>v.imageId='sha256:'+'0'.repeat(64)],
  ['invented summary','publication/publication.json',v=>v.security.high=1],
  ['hidden CVE','publication/vulnerabilities.json',v=>v.Results[0].Vulnerabilities=[{VulnerabilityID:'CVE-2099-12345',Severity:'HIGH'}]],
  ['false exemption','publication/vulnerabilities.adjudications.json',v=>v.decisions=[{cve:'CVE-2099-12345'}]],
])test(name+' is rejected',async()=>{
  const p='infrastructure/runtime-security/proofs/c17-mysql/'+path,value=JSON.parse(await read(p));mutate(value);
  const bytes=Buffer.from(JSON.stringify(value)),changed=structuredClone(entry);
  if(path==='rehearsal.json')changed.databaseTransition.sha256=createHash('sha256').update(bytes).digest('hex');
  await assert.rejects(validateDatabaseTransitionReadiness(changed,image,x=>x===p?bytes:read(x)));
});
