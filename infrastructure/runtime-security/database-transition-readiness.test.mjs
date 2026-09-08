import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateDatabaseTransitionReadiness} from './database-transition-readiness.mjs';
import {publishedImage,vpsCandidateApp} from './mysql-upgrade-rehearsal.mjs';
const root=fileURLToPath(new URL('../../',import.meta.url));
const proofPath='infrastructure/runtime-security/proofs/c14-mysql-vps/actual-result.json';
const hash=value=>createHash('sha256').update(value).digest('hex');
const encoded=value=>Buffer.from(JSON.stringify(value));
async function fixture(){
  const map=new Map(),proof=JSON.parse(await readFile(resolve(root,proofPath)));
  const image={component:'mysql',sourceBeforeRef:'mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383'};
  const entry={component:'mysql',reference:publishedImage,databaseTransition:{path:proofPath,sha256:''}};
  const read=async path=>map.has(path)?map.get(path):readFile(resolve(root,path));
  const seal=()=>{const bytes=encoded(proof);map.set(proofPath,bytes);entry.databaseTransition.sha256=hash(bytes);};
  const change=async(key,fn)=>{const ref=proof.evidence[key],value=JSON.parse(await read(ref.path));fn(value);const bytes=encoded(value);map.set(ref.path,bytes);ref.sha256=hash(bytes);if(key==='rawSecurity'){const securityRef=proof.evidence.security,security=JSON.parse(await read(securityRef.path));security.rawReportSha256=ref.sha256;const securityBytes=encoded(security);map.set(securityRef.path,securityBytes);securityRef.sha256=hash(securityBytes);}seal();};
  seal();return {proof,image,entry,map,read,change,seal,validate:()=>validateDatabaseTransitionReadiness(entry,image,read)};
}
test('actual VPS rehearsal is candidate preparation only, with no cutover or ordinary-deploy authorization',async()=>{
  const f=await fixture(),result=await f.validate();assert.equal(result.mode,'COORDINATED_CANDIDATE_PREPARATION');assert.equal(result.vpsCutoverExecuted,false);assert.equal(result.ordinaryDeploymentUpgradeAuthorized,false);
});
test('database hold has no implicit approval, alternate proof path, or unreviewed component',async()=>{
  const f=await fixture();delete f.entry.databaseTransition;await assert.rejects(f.validate(),/proof_required/);
  const p=await fixture();p.entry.databaseTransition.path=p.entry.databaseTransition.path.replace('c14-mysql-vps','c14-mysql');await assert.rejects(p.validate(),/proof_path/);
  const pg=await fixture();pg.image.component='postgres';await assert.rejects(pg.validate(),/requires_review/);
});
for(const [name,key,mutate] of [
  ['historical local310 is not an actual VPS287 replay','runtime',v=>{v.mode='published-rollback';v.sourceSchemaAtCapture='1.10.310';}],
  ['failed runtime cannot be promoted by a PASS wrapper','runtime',v=>{v.result='FAIL';}],
  ['a changed current app is not covered','runtime',v=>{v.images.app='sha256:'+'1'.repeat(64);}],
  ['old configuration cannot be replaced with new app','runtime',v=>{v.images.baselineAppConfigurationDigest=vpsCandidateApp;}],
  ['manifest versus config copy requires reexport evidence','oldAppCopy',v=>{v.configurationReexportHashMatches=false;}],
  ['target digest mismatch is rejected','runtime',v=>{v.images.targetReference=v.images.source;}],
  ['actual target uid must match reviewed source ownership','targetContainerPolicy',v=>{v.actualUid=27;}],
  ['a public fixture network is not accepted','targetContainerPolicy',v=>{v.networkInternal=false;}],
  ['GTID default drift is rejected','runtime',v=>{v.standalonePolicy.gtid_mode='ON';}],
  ['an enabled event scheduler is not quiesced','runtime',v=>{v.standalonePolicy.event_scheduler='ON';}],
  ['old app startup fence must be effective','runtime',v=>{v.oldApplicationReadOnly.baseline.duringValidation.superReadOnly=false;}],
  ['old app fence must be released before writable upgrade phases','runtime',v=>{v.oldApplicationReadOnly.baseline.releasedAfterGracefulStop.readOnly=true;}],
  ['a missing app health gate is not a full replay','runtime',v=>{v.checks=v.checks.filter(c=>c.name!=='app973_health_up');}],
  ['UNKNOWN envelope change is rejected','runtime',v=>{v.applicationMigration.unknownSha256='0'.repeat(64);}],
  ['rewritten source Flyway history is rejected','runtime',v=>{v.applicationMigration.history.existingHistorySha256='0'.repeat(64);}],
  ['new identity tables cannot disappear from310','runtime',v=>{v.afterApplicationMigration.checksumTables=v.afterApplicationMigration.checksumTables.filter(t=>t!=='order_publication_client_updates');}],
  ['fresh rollback must use a different volume','runtime',v=>{v.rollbackProof.restoredVolume=v.rollbackProof.originalVolume;}],
  ['rollback must use original app','runtime',v=>{v.rollbackProof.applicationImage=vpsCandidateApp;}],
  ['rollback must preserve original rows','runtime',v=>{v.rollbackProof.snapshot.rows++;}],
  ['binary downgrade cannot be hidden','runtime',v=>{v.rollbackProof.binaryDowngradeAttempted=true;}],
  ['incomplete cleanup cannot pass','runtime',v=>{v.cleanup='RETAINED';}],
  ['source schema change requires a new rehearsal','sourceContinuity',v=>{v.checks.flywayHistoryUnchanged=false;}],
  ['concurrent source business writes are not claimed frozen','sourceContinuity',v=>{v.businessDataUnchangedClaimed=true;}],
  ['a stale source observation cannot close the run','sourceContinuity',v=>{v.checkedAt='2000-01-01T00:00:00Z';}],
  ['nonempty high finding cannot be hidden by zero summary','rawSecurity',v=>{v.Results[0].Vulnerabilities=[{Severity:'HIGH',FixedVersion:'1'}];}],
  ['empty scanner inventory is not a clean scan','rawSecurity',v=>{v.Results=[];}],
  ['unchecked upgrade result cannot be promoted','upgradeChecker',v=>{v.errorCount=1;}],
  ['full checker cannot be reduced to a passing subset','upgradeChecker',v=>{v.checksPerformed=v.checksPerformed.slice(0,1);}],
  ['new manual checker work is not implicitly reviewed','upgradeChecker',v=>{v.manualChecks=[{id:'new-review'}];}],
  ['shell PID1 is not silently accepted for candidate app','runtime',v=>{v.stops.find(stop=>stop.container.endsWith('-app973')).pid1='sh';}],
])test(name,async()=>{const f=await fixture();await f.change(key,mutate);await assert.rejects(f.validate());});
test('proof and individual artifact content hashes are mandatory',async()=>{
  const f=await fixture();f.map.set(proofPath,Buffer.from('{}'));await assert.rejects(f.validate(),/evidence_changed/);
  const p=await fixture();p.map.set(p.proof.evidence.runtime.path,Buffer.from('{}'));await assert.rejects(p.validate(),/evidence_changed/);
});
test('a synthetic VPS cutover claim is explicitly refused',async()=>{
  const f=await fixture();f.proof.vpsCutoverExecuted=true;f.seal();await assert.rejects(f.validate(),/no_cutover_claim/);
});
