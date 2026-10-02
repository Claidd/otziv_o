import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {hash} from './ssl-refresh.mjs';
import {createEvidenceReader} from './reviewed-image-defaults.mjs';
import {validateSslRefreshTransition,coupleSslRefresh} from './ssl-refresh-activation.mjs';
const root=resolve(new URL('../../',import.meta.url).pathname.replace(/^\/([A-Z]:)/i,'$1'));
const read=await createEvidenceReader(root);
const entries=JSON.parse(await read('infrastructure/runtime-security/reviewed-image-activations.json')).images;
const pg=entries.find(x=>x.component==='postgres'),kc=entries.find(x=>x.component==='keycloak');
const image=JSON.parse(await read('infrastructure/runtime-security/reviewed-images.json')).images.find(x=>x.component==='postgres');
test('retained C23 rehearsal pairs the exact issuer and database while ordinary upgrade remains forbidden',async()=>{
 const ready=await validateSslRefreshTransition(pg,image,read),paired=await coupleSslRefresh(ready,kc,read);
 assert.equal(paired.mode,'COORDINATED_CANDIDATE_PREPARATION');
 assert.equal(paired.ordinaryDeploymentUpgradeAuthorized,false);
 assert.equal(paired.postgresReference,pg.reference);assert.equal(paired.requiredKeycloakReference,kc.reference);
 assert.equal(paired.acceptancePath,kc.migrationAcceptance.path);assert.equal(paired.proofSha256,kc.migrationAcceptance.sha256);
 await assert.rejects(coupleSslRefresh({...ready,postgresReference:kc.reference},kc,read),/ssl_refresh_coupling_postgres/);
});
for(const [name,mutate,error] of [
 ['role preservation failure',r=>{r.checks.find(x=>x.name==='existing_roles_and_credentials_preserved').passed=false},/ssl_refresh_acceptance_checks_failed/],
 ['production writes',r=>{r.productionWrites=true},/ssl_refresh_acceptance_production_write/],
 ['reduced table coverage',r=>{r.tableCount=101},/ssl_refresh_acceptance_table_coverage/],
 ['another candidate database',r=>{r.candidatePostgres=r.candidate},/ssl_refresh_acceptance_pair_postgres/]
])test('even recomputed presentation hashes cannot accept '+name,async()=>{
 const entry=structuredClone(pg),value=JSON.parse(await read(entry.sslRefreshAcceptance.path));
 const replay=JSON.parse(await read(value.replayPath));mutate(replay);const bytes=Buffer.from(JSON.stringify(replay));
 value.files[value.replayPath]=hash(bytes);const accepted=Buffer.from(JSON.stringify(value));
 entry.sslRefreshAcceptance.sha256=hash(accepted);entry.databaseTransition=entry.sslRefreshAcceptance;
 const modified=async p=>p===entry.sslRefreshAcceptance.path?accepted:p===value.replayPath?bytes:read(p);
 await assert.rejects(validateSslRefreshTransition(entry,image,modified),error);
});

for(const [name,mutate,error] of [
 ['new issuer digest with the retained C24 replay', (entry,value)=>{
   entry.reference='ghcr.io/claidd/otziv-security@sha256:'+'a'.repeat(64);
   value.reference=entry.reference;
 }, /ssl_refresh_coupling_candidate_reference/],
 ['new issuer config with the retained C24 replay', (entry,value)=>{
   value.imageConfigId='sha256:'+'b'.repeat(64);
 }, /ssl_refresh_coupling_candidate_config/]
])test('recomputed acceptance hashes cannot claim '+name,async()=>{
 const entry=JSON.parse(await read('infrastructure/runtime-security/c26-parent-keycloak.json'));
 const value=JSON.parse(await read(entry.sslRefreshAcceptance.path));
 const readiness=()=>({postgresReference:value.pair.postgres.reference,
   postgresConfigId:value.pair.postgres.imageConfigId,
   requiredKeycloakReference:entry.reference,requiredKeycloakConfigId:value.imageConfigId});
 // The unchanged C24 capture, replay, issuer and startup proofs remain valid.
 await coupleSslRefresh(readiness(),entry,read);
 mutate(entry,value);
 const accepted=Buffer.from(JSON.stringify(value));
 entry.sslRefreshAcceptance={...entry.sslRefreshAcceptance,sha256:hash(accepted)};
 entry.migrationAcceptance=entry.sslRefreshAcceptance;
 const modified=async path=>path===entry.sslRefreshAcceptance.path?accepted:read(path);
 await assert.rejects(coupleSslRefresh(readiness(),entry,modified),error);
});
