import assert from 'node:assert/strict';
import test from 'node:test';
import {readFile} from 'node:fs/promises';
import {validateSslRefreshTransition} from './ssl-refresh-activation.mjs';
import {validatePostgresC36Acceptance,validatePostgresC36Rehearsal,
  C35_KEYCLOAK_REFERENCE,C35_KEYCLOAK_CONFIG} from './postgres-c36-activation.mjs';
import {PARENT_REFERENCE,PARENT_CONFIG_ID,hash} from './postgres-c36-adjudication.mjs';

const reference='ghcr.io/claidd/otziv-security@sha256:'+'a'.repeat(64);
const image='sha256:'+'b'.repeat(64);
const script='c'.repeat(64);
const root=new URL('./proofs/c35-keycloak/rehearsal/',import.meta.url);

async function rehearsal(){
  const records=await Promise.all(['capture','replay'].map(async name=>
    JSON.parse(await readFile(new URL(name+'.json',root)))));
  for(const row of records){
    row.schema='otziv-postgres-c36-rehearsal-v1';
    row.sourcePostgres=PARENT_REFERENCE;row.sourcePostgresConfig=PARENT_CONFIG_ID;
    row.keycloak=C35_KEYCLOAK_REFERENCE;row.keycloakConfig=C35_KEYCLOAK_CONFIG;
    row.executedScriptSha256=script;
  }
  records[1].candidatePostgres=reference;records[1].candidatePostgresLocalId=image;
  records[1].candidate=C35_KEYCLOAK_REFERENCE;records[1].candidateLocalId=C35_KEYCLOAK_CONFIG;
  const value={reference,imageConfigId:image,capturePath:'infrastructure/runtime-security/capture.json',
    replayPath:'infrastructure/runtime-security/replay.json',
    executedSources:{'infrastructure/runtime-security/postgres-c36-rehearsal.py':script}};
  const documents=new Map([[value.capturePath,records[0]],[value.replayPath,records[1]]]);
  const read=async path=>Buffer.from(JSON.stringify(documents.get(path)));
  return {value,records,read};
}

test('C36 transition proof requires a candidate run and exact rollback checks',async()=>{
  const x=await rehearsal();
  assert.equal((await validatePostgresC36Rehearsal(x.value,x.read)).candidatePostgres,reference);
  x.records[1].checks.find(check=>check.name==='same_volume_all_tables_exact').passed=false;
  await assert.rejects(validatePostgresC36Rehearsal(x.value,x.read),/postgres_c36_rehearsal_incomplete/);
});

test('C36 transition cannot activate without a bound acceptance',async()=>{
  const entry={component:'postgres',reference};
  await assert.rejects(validatePostgresC36Acceptance(entry,async()=>Buffer.alloc(0)),
    /postgres_c36_acceptance_missing/);
  entry.postgresC36Acceptance={path:'infrastructure/runtime-security/proofs/c36-postgres/acceptance.json',sha256:'d'.repeat(64)};
  await assert.rejects(validatePostgresC36Acceptance(entry,async()=>Buffer.alloc(0)),
    /postgres_c36_transition_not_bound/);
});

test('frozen C23 parent transition still replays the existing C26 paired proof',async()=>{
  const bytes=await readFile(new URL('./c36-parent-postgres.json',import.meta.url));
  assert.equal(hash(bytes),'fcc50b0b792637c08a9a74cf51057354d138b6a5a741a344ed6979a3c7a611b3');
  const parent=JSON.parse(bytes);
  const baseline=JSON.parse(await readFile(new URL('./reviewed-images.json',import.meta.url)));
  const image=baseline.images.find(value=>value.component==='postgres');
  const readiness=await validateSslRefreshTransition(parent,image,path=>readFile(path));
  assert.equal(readiness.postgresReference,PARENT_REFERENCE);
  assert.equal(readiness.postgresConfigId,PARENT_CONFIG_ID);
  assert.equal(readiness.mode,'COORDINATED_CANDIDATE_PREPARATION');
});
