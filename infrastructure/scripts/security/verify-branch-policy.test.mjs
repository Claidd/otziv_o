import test from 'node:test';
import assert from 'node:assert/strict';
import {inspectPolicy} from './verify-branch-policy.mjs';
const config={schema:'otziv-required-check-policy-v1',repository:'fixture/repository',branch:'main',expectedCheckNames:['Fixture gate'],proposedPolicy:{enforce_admins:true}};
const sha='1'.repeat(40);
const now=Date.parse('2026-09-07T12:00:00Z');
const week=7*24*60*60*1000;
const success=(overrides={})=>({name:'Fixture gate',id:123,head_sha:sha,status:'completed',conclusion:'success',completed_at:new Date(now-60000).toISOString(),app:{slug:'github-actions',id:42},...overrides});
const policy=()=>({required_status_checks:{strict:true,checks:[{context:'Fixture gate',app_id:42}]},enforce_admins:{enabled:true},allow_force_pushes:{enabled:false},allow_deletions:{enabled:false}});
const inspect=get=>inspectPolicy(config,{get,now:()=>now});
function fixture({protectedBranch=false,published=false,foreignApp=false,staleHead=false,rulesStatus=200,rows,protection={status:401,body:null},classicSummary=true}={}){
  const checkRuns=rows??(published?[success({head_sha:staleHead?'2'.repeat(40):sha,app:{slug:foreignApp?'unknown-app':'github-actions',id:42}})]:[]);
  return async path=>path.endsWith('/protection')?protection:path.includes('/check-runs?')?{status:200,body:{total_count:checkRuns.length,check_runs:checkRuns}}
    :path.includes('/rules/branches/')?{status:rulesStatus,body:protectedBranch?[{type:'required_status_checks',parameters:{required_status_checks:[{context:'Fixture gate',integration_id:42}]}}]:[]}
    :path.includes('/rulesets?')?{status:200,body:[]}:{status:200,body:{protected:protectedBranch,commit:{sha},...(protectedBranch&&classicSummary?{protection:{required_status_checks:{contexts:['Fixture gate']}}}:{})}};
}
test('unprotected remote main and unpublished check names remain explicit; no applicable request is invented',async()=>{
  const result=await inspect(fixture());assert.equal(result.status,'UNPROTECTED');assert.equal(result.proposedRequestBody,null);assert.equal(result.remoteMutations,0);
  assert.deepEqual(result.missingRequiredCheckNames,['Fixture gate']);
});
test('proposal binds only successful exact-head check runs from GitHub Actions, never guessed app IDs',async()=>{
  for(const option of [{foreignApp:true},{staleHead:true}])assert.equal((await inspect(fixture({published:true,...option}))).readyForPolicyReview,false);
  const result=await inspect(fixture({published:true,protectedBranch:true}));
  assert.equal(result.status,'REQUIRED_CHECK_NAMES_OBSERVED_ONLY');assert.deepEqual(result.proposedRequestBody.required_status_checks.checks,[{context:'Fixture gate',app_id:42}]);
  assert.equal((await inspect(fixture({protectedBranch:true,published:true,rulesStatus:403}))).status,'POLICY_UNVERIFIED');
});
test('verified enforcement requires strict checks with observed app binding and administrative/force-push protection',async()=>{
  const check=body=>inspect(fixture({published:true,protectedBranch:true,protection:{status:200,body},classicSummary:false}));
  assert.equal((await check(policy())).status,'ENFORCEMENT_VERIFIED');
  for(const mutate of [p=>p.required_status_checks.strict=false,p=>p.required_status_checks.checks[0].app_id=99,p=>p.enforce_admins.enabled=false,p=>p.allow_force_pushes.enabled=true,p=>p.allow_deletions.enabled=true]){
    const current=policy();mutate(current);assert.equal((await check(current)).status,'REQUIRED_CHECK_NAMES_OBSERVED_ONLY');
  }
});

test('a successful row cannot hide another latest failure, active run or ambiguous success with the same name',async()=>{
  for(const other of [success({id:124,conclusion:'failure'}),success({id:124,status:'in_progress',conclusion:null,completed_at:null}),success({id:124})]){
    for(const rows of [[success(),other],[other,success()]]){
      const result=await inspect(fixture({rows,protectedBranch:true,protection:{status:200,body:policy()}}));
      assert.equal(result.status,'REQUIRED_CHECK_NAMES_OBSERVED_ONLY');
      assert.equal(result.checkEligibility[0].state,'AMBIGUOUS');
      assert.equal(result.readyForPolicyReview,false);assert.equal(result.proposedRequestBody,null);
      assert.deepEqual(result.successfulCheckRuns,[]);
    }
  }
});

test('only a completed success can establish a binding; queued, active, failed and skipped checks do not',async()=>{
  for(const [override,expected] of [[{status:'queued',conclusion:null},'NOT_COMPLETED'],[{status:'in_progress',conclusion:null},'NOT_COMPLETED'],[{conclusion:'failure'},'NOT_SUCCESSFUL'],[{conclusion:'skipped'},'NOT_SUCCESSFUL']]){
    const result=await inspect(fixture({rows:[success(override)]}));
    assert.equal(result.checkEligibility[0].state,expected);assert.equal(result.readyForPolicyReview,false);assert.equal(result.proposedRequestBody,null);
  }
});

test('missing, invalid, future and older-than-seven-days completion times prevent proposal and enforcement',async()=>{
  for(const [completed_at,expected] of [[undefined,'COMPLETION_TIME_UNVERIFIED'],[null,'COMPLETION_TIME_UNVERIFIED'],['invalid','COMPLETION_TIME_UNVERIFIED'],[new Date(now+1).toISOString(),'COMPLETION_TIME_IN_FUTURE'],[new Date(now-week-1).toISOString(),'SUCCESS_TOO_OLD']]){
    const result=await inspect(fixture({rows:[success({completed_at})],protectedBranch:true,protection:{status:200,body:policy()}}));
    assert.equal(result.checkEligibility[0].state,expected);assert.equal(result.readyForPolicyReview,false);
    assert.equal(result.proposedRequestBody,null);assert.notEqual(result.status,'ENFORCEMENT_VERIFIED');
  }
});

test('a success at the seven-day boundary is eligible and records the clock and freshness limit',async()=>{
  const result=await inspect(fixture({rows:[success({completed_at:new Date(now-week).toISOString()})],protectedBranch:true,protection:{status:200,body:policy()}}));
  assert.equal(result.status,'ENFORCEMENT_VERIFIED');assert.equal(result.checkEligibility[0].state,'ELIGIBLE');
  assert.deepEqual(result.checkFreshness,{maximumAgeSeconds:604800,referenceTime:new Date(now).toISOString()});
});

test('ruleset-only protection is explicitly outside this classic verifier and cannot count as classic enforcement',async()=>{
  const result=await inspect(fixture({published:true,protectedBranch:true,classicSummary:false,protection:{status:404,body:null}}));
  assert.equal(result.status,'RULESET_ONLY_NOT_EVALUATED');assert.equal(result.policyModel,'CLASSIC_BRANCH_PROTECTION');
  assert.equal(result.rulesetEnforcement,'NOT_EVALUATED');assert.deepEqual(result.requiredCheckNames,[]);
  assert.deepEqual(result.observedRulesetRequiredChecks,[{context:'Fixture gate',integration_id:42}]);
  assert.deepEqual(result.missingRequiredCheckNames,['Fixture gate']);
  // Recent checks permit a classic proposal for human review, not a claim that the existing ruleset was verified.
  assert.equal(result.readyForPolicyReview,true);assert.equal(result.proposedRequestBody.required_status_checks.strict,true);
  assert.equal(result.remoteMutations,0);
});

test('invalid names or an invalid observation clock fail before remote reads',async()=>{
  let reads=0;const get=async()=>{reads++;throw new Error('unexpected_read');};
  for(const expectedCheckNames of [[''],['Fixture gate','Fixture gate'],[42]]){
    await assert.rejects(inspectPolicy({...config,expectedCheckNames},{get}),/branch_policy_config_invalid/);
  }
  await assert.rejects(inspectPolicy(config,{get,now:()=>NaN}),/branch_policy_clock_invalid/);
  assert.equal(reads,0);
});
