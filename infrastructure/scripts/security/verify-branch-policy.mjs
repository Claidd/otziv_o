import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

// Read-only by construction: fixed GitHub API origin, GET only, no credentials required for this public repository.
export async function githubGet(path){
  const response=await fetch('https://api.github.com/'+path,{method:'GET',redirect:'error',
    headers:{Accept:'application/vnd.github+json','User-Agent':'otziv-readonly-required-check-verifier',
      ...(process.env.OTZIV_GITHUB_READ_TOKEN?{Authorization:`Bearer ${process.env.OTZIV_GITHUB_READ_TOKEN}`}:{})},signal:AbortSignal.timeout(10000)});
  const chunks=[];let bytes=0;
  for await(const chunk of response.body){bytes+=chunk.length;if(bytes>1024*1024)throw new Error('github_response_too_large');chunks.push(chunk);}
  return {status:response.status,body:response.ok?JSON.parse(Buffer.concat(chunks).toString('utf8')):null};
}
const CHECK_MAX_AGE_MS=7*24*60*60*1000;

export async function inspectPolicy(config,{get=githubGet,now=()=>Date.now()}={}){
  if(config.schema!=='otziv-required-check-policy-v1'|| !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(config.repository)
      || typeof config.branch!=='string' || !config.branch || !Array.isArray(config.expectedCheckNames)
      || new Set(config.expectedCheckNames).size!==config.expectedCheckNames.length || config.expectedCheckNames.length<1
      || config.expectedCheckNames.some(name=>typeof name!=='string'||!name.trim()))
    throw new Error('branch_policy_config_invalid');
  const observedAt=now();
  if(!Number.isFinite(observedAt))throw new Error('branch_policy_clock_invalid');
  const prefix=`repos/${config.repository}`,branchPath=encodeURIComponent(config.branch);
  const [branch,rules,rulesets,protection]=await Promise.all([get(`${prefix}/branches/${branchPath}`),get(`${prefix}/rules/branches/${branchPath}`),get(`${prefix}/rulesets?includes_parents=true&per_page=100`),get(`${prefix}/branches/${branchPath}/protection`)]);
  if(branch.status!==200 || !/^[a-f0-9]{40}$/.test(branch.body?.commit?.sha))throw new Error('branch_read_unavailable');
  const sha=branch.body.commit.sha;
  const checks=await get(`${prefix}/commits/${sha}/check-runs?filter=latest&per_page=100`);
  const rows=checks.status===200 && checks.body?.total_count<=100 && Array.isArray(checks.body.check_runs)?checks.body.check_runs:[];
  const candidates=rows.filter(row=>row.head_sha===sha && row.app?.slug==='github-actions'
    && Number.isSafeInteger(row.app.id) && row.app.id>0);
  const observations=config.expectedCheckNames.map(name=>{
    const matching=candidates.filter(row=>row.name===name);
    // The API is already requested with filter=latest. Multiple matching results
    // remain ambiguous: never let a successful row conceal failure or active work.
    let state=matching.length===0?'MISSING':matching.length!==1?'AMBIGUOUS':null;
    const row=matching[0];
    if(!state&&row.status!=='completed')state='NOT_COMPLETED';
    if(!state&&row.conclusion!=='success')state='NOT_SUCCESSFUL';
    const completedAt=typeof row?.completed_at==='string'?Date.parse(row.completed_at):NaN;
    if(!state&&!Number.isFinite(completedAt))state='COMPLETION_TIME_UNVERIFIED';
    if(!state&&completedAt>observedAt)state='COMPLETION_TIME_IN_FUTURE';
    if(!state&&observedAt-completedAt>CHECK_MAX_AGE_MS)state='SUCCESS_TOO_OLD';
    return {name,state:state||'ELIGIBLE',checkRunIds:matching.map(value=>value.id)};
  });
  const missing=observations.filter(row=>row.state!=='ELIGIBLE').map(row=>row.name);
  const observedChecks=observations.filter(row=>row.state==='ELIGIBLE').map(({name})=>{
    const row=candidates.find(value=>value.name===name);return {context:name,app_id:row.app.id,checkRunId:row.id};});
  const effective=rules.status===200 && Array.isArray(rules.body)?rules.body.filter(rule=>rule.type==='required_status_checks')
    .flatMap(rule=>rule.parameters?.required_status_checks||[]):[];
  // proposedPolicy is the classic /branches/{branch}/protection request model.
  // Ruleset names are observed separately; their strictness/bypasses are not
  // evaluated by this verifier and cannot establish classic enforcement.
  const classicChecks=protection.status===200?protection.body?.required_status_checks:branch.body.protection?.required_status_checks;
  const required=new Set([...(classicChecks?.contexts||[]),...(classicChecks?.checks||[]).map(c=>c.context)]);
  const notRequired=config.expectedCheckNames.filter(name=>!required.has(name));
  const protectedChecks=protection.body?.required_status_checks?.checks||[];
  const appBindingsVerified=missing.length===0 && observedChecks.every(expected=>protectedChecks.some(actual=>actual.context===expected.context&&actual.app_id===expected.app_id));
  const enforced=protection.status===200 && protection.body.required_status_checks?.strict===true && protection.body.enforce_admins?.enabled===true
    && protection.body.allow_force_pushes?.enabled===false && protection.body.allow_deletions?.enabled===false && appBindingsVerified;
  const status=branch.body.protected!==true?'UNPROTECTED':rules.status!==200?'POLICY_UNVERIFIED'
    :protection.status===404&&effective.length?'RULESET_ONLY_NOT_EVALUATED'
    :notRequired.length?'MISSING_REQUIRED_CHECKS':enforced?'ENFORCEMENT_VERIFIED':'REQUIRED_CHECK_NAMES_OBSERVED_ONLY';
  return {schema:'otziv-required-check-observation-v1',policyModel:'CLASSIC_BRANCH_PROTECTION',rulesetEnforcement:'NOT_EVALUATED',
    observedAt:new Date(observedAt).toISOString(),repository:config.repository,branch:config.branch,revision:sha,
    readOnly:true,remoteMutations:0,status,protected:branch.body.protected===true,
    rulesHttpStatus:rules.status,rulesetsHttpStatus:rulesets.status,protectionHttpStatus:protection.status,rules:rules.body,rulesets:rulesets.body,protection:protection.body,
    requiredCheckNames:[...required],observedRulesetRequiredChecks:effective,missingRequiredCheckNames:notRequired,unpublishedOrUnsuccessfulCheckNames:missing,
    checkEligibility:observations,checkFreshness:{maximumAgeSeconds:CHECK_MAX_AGE_MS/1000,referenceTime:new Date(observedAt).toISOString()},
    successfulCheckRuns:observedChecks,readyForPolicyReview:missing.length===0,
    proposedRequestBody:missing.length?null:{...config.proposedPolicy,required_status_checks:{strict:true,checks:observedChecks.map(({context,app_id})=>({context,app_id}))}}};
}
if(process.argv[1]===fileURLToPath(import.meta.url)){
  try{
    if(process.argv.length!==4)throw new Error('usage_expected_policy_json_output_json');
    const result=await inspectPolicy(JSON.parse(await readFile(process.argv[2],'utf8')));
    await writeFile(process.argv[3],JSON.stringify(result,null,2)+'\n',{mode:0o600});
    console.log(JSON.stringify({status:result.status,readOnly:true,remoteMutations:0,readyForPolicyReview:result.readyForPolicyReview,missingRequiredChecks:result.missingRequiredCheckNames.length}));
    if(result.status!=='ENFORCEMENT_VERIFIED')process.exitCode=2;
  }catch(error){console.error(JSON.stringify({result:'FAIL',code:/^[a-z_]+$/.test(error.message||'')?error.message:'branch_policy_read_failed'}));process.exitCode=1;}
}
