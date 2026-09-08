import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const replacements={'auth-username-password-form':'otziv-generation-browser-password','direct-grant-validate-password':'otziv-generation-direct-password'};
export const generationMapper={name:'otziv immutable session generation',protocol:'openid-connect',protocolMapper:'oidc-usersessionmodel-note-mapper',consentRequired:false,config:{'user.session.note':'otziv.security.generation.v1','claim.name':'otziv_session_generation','jsonType.label':'String','access.token.claim':'true','id.token.claim':'false','introspection.token.claim':'true','userinfo.token.claim':'false'}};
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'
  ?Object.fromEntries(Object.keys(value).sort().filter(key=>value[key]!==undefined).map(key=>[key,canonical(value[key])])):value;
const hash=value=>createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
const pathPart=value=>encodeURIComponent(value);
const omit=(value,keys)=>Object.fromEntries(Object.entries(value).filter(([key])=>!keys.includes(key)));
const normalizeProvider=id=>replacements[id]||id||null;
const shape=rows=>rows.map(row=>({provider:normalizeProvider(row.providerId),level:row.level,priority:row.priority,requirement:row.requirement,flow:!!row.authenticationFlow}));
const ordered=values=>[...values].sort((x,y)=>String(x.id).localeCompare(String(y.id),'en'));

// Exact input hashes retain IDs and configuration aliases. Semantic hashes discard
// only identities which Keycloak assigns during copy; no configuration value leaves
// this function in the plan or an assertion diagnostic.
async function inspectFlow(request,a,flow) {
  const metadata=await request(a+'/authentication/flows/'+pathPart(flow.id));
  assert.equal(metadata.id,flow.id,'flow_metadata_id_mismatch');
  const rows=await request(a+'/authentication/flows/'+pathPart(flow.alias)+'/executions');
  const executions=new Map(),configs=new Map(),nested=new Map();
  for(const row of rows) {
    const execution=await request(a+'/authentication/executions/'+pathPart(row.id));
    assert.equal(execution.id,row.id,'execution_id_mismatch');executions.set(row.id,execution);
    if(execution.authenticatorConfig) {
      const config=await request(a+'/authentication/config/'+pathPart(execution.authenticatorConfig));
      assert.equal(config.id,execution.authenticatorConfig,'authenticator_config_id_mismatch');
      configs.set(row.id,config);
    }
    if(execution.authenticatorFlow) {
      assert.ok(execution.flowId,'nested_flow_id_missing');
      const child=await request(a+'/authentication/flows/'+pathPart(execution.flowId));
      assert.equal(child.id,execution.flowId,'nested_flow_id_mismatch');nested.set(row.id,child);
    }
  }
  const paths=new Map([[flow.id,'root']]);
  rows.forEach((row,index)=>{const execution=executions.get(row.id);if(execution.authenticatorFlow) {
    assert.ok(!paths.has(execution.flowId),'nested_flow_cycle_or_reuse_requires_review');paths.set(execution.flowId,'nested:'+index);
  }});
  const flowBehavior=value=>omit(value,['id','alias','builtIn','authenticationExecutions']);
  const behavior={flow:flowBehavior(metadata),executions:rows.map((row,index)=>{
    const execution=executions.get(row.id),config=configs.get(row.id);
    assert.ok(paths.has(execution.parentFlow),'execution_parent_outside_copied_flow');
    const semantic=omit(execution,['id','parentFlow','flowId','authenticatorConfig']);
    if('authenticator' in semantic)semantic.authenticator=normalizeProvider(semantic.authenticator);
    return {position:index,...shape([row])[0],execution:semantic,parent:paths.get(execution.parentFlow),
      child:execution.authenticatorFlow?paths.get(execution.flowId):null,
      nested:nested.has(row.id)?flowBehavior(nested.get(row.id)):null,
      configurationHash:config?hash(omit(config,['id','alias'])):null};
  })};
  return {rows,executions,behaviorHash:hash(behavior),input:{metadataHash:hash(metadata),rowsHash:hash(rows),
    executions:rows.map(row=>({id:row.id,executionHash:hash(executions.get(row.id)),
      configId:configs.get(row.id)?.id||null,configurationHash:configs.has(row.id)?hash(configs.get(row.id)):null,
      nestedFlowId:nested.get(row.id)?.id||null,nestedMetadataHash:nested.has(row.id)?hash(nested.get(row.id)):null}))}};
}

async function inspectMappers(request,a,client) {
  const prefix=a+'/clients/'+pathPart(client.id);
  const direct=await request(prefix+'/protocol-mappers/models');
  const scopes=[];
  for(const kind of ['default','optional']) {
    for(const scope of ordered(await request(prefix+'/'+kind+'-client-scopes'))) {
      const mappers=await request(a+'/client-scopes/'+pathPart(scope.id)+'/protocol-mappers/models');
      // A dedicated client mapper is always installed. An inherited writer, even an
      // identical optional one, would make claim ownership depend on requested scopes.
      assert.ok(!mappers.some(mapper=>mapper.config?.['claim.name']==='otziv_session_generation'),
        'inherited_generation_mapper_requires_review');
      scopes.push({kind,id:scope.id,scopeHash:hash(scope),mappersHash:hash(ordered(mappers))});
    }
  }
  const writers=direct.filter(mapper=>mapper.config?.['claim.name']==='otziv_session_generation');
  assert.ok(writers.length<=1,'generation_claim_has_multiple_writers');
  if(writers.length)assert.equal(hash(omit(writers[0],['id'])),hash(generationMapper),'existing_generation_mapper_requires_review');
  return {hasGeneration:!!writers.length,input:{directMappersHash:hash(ordered(direct)),scopes}};
}

/** REST adapter returns response body, rejects any non-2xx without printing credentials.
 * Copies the real nested flow and preserves OTP/conditions/configuration. No active
 * execution is deleted. Activation is deliberately separate from backend enforcement.
 */
export async function provision(request,realm,{apply=false,expectedPlanHash,clientIds}={}) {
  const a='/admin/realms/'+pathPart(realm);
  const state=await request(a),flows=await request(a+'/authentication/flows');
  assert.ok(Array.isArray(clientIds)&&clientIds.length&&new Set(clientIds).size===clientIds.length,'protected_client_ids_required');
  const clients=ordered((await request(a+'/clients')).filter(c=>clientIds.includes(c.clientId)&&c.enabled&&c.protocol==='openid-connect'&&(c.standardFlowEnabled||c.directAccessGrantsEnabled)));
  assert.equal(clients.length,clientIds.length,'protected_client_missing_disabled_or_not_interactive');
  assert.ok(clients.length,'interactive_oidc_clients_missing');
  const sources=new Map();
  const include=alias=>{const flow=flows.find(f=>f.alias===alias);assert.ok(flow,'bound_flow_missing');sources.set(flow.id,flow);return flow;};
  include(state.browserFlow);include(state.directGrantFlow);
  for(const client of clients)for(const key of ['browser','direct_grant']) {
    const id=client.authenticationFlowBindingOverrides?.[key];
    if(id){const flow=flows.find(f=>f.id===id);assert.ok(flow,'client_override_flow_missing');sources.set(id,flow);}
  }
  const plans=[],snapshots=new Map(),mapperPlans=new Map();
  // All inherited/direct claim collisions are checked before the first mutation.
  for(const client of clients)mapperPlans.set(client.id,await inspectMappers(request,a,client));
  for(const flow of sources.values()) {
    const snapshot=await inspectFlow(request,a,flow);snapshots.set(flow.id,snapshot);
    const rows=snapshot.rows;
    const legacy=rows.filter(r=>Object.hasOwn(replacements,r.providerId));
    const anchored=rows.filter(r=>Object.values(replacements).includes(r.providerId));
    assert.ok(legacy.length||anchored.length,'password_authenticator_missing_requires_review');
    const target=legacy.length?'otziv-generation-v1-'+flow.id:flow.alias;
    plans.push({source:flow.alias,sourceId:flow.id,target,sourceShape:shape(rows),replaceCount:legacy.length,
      input:snapshot.input,behaviorHash:snapshot.behaviorHash});
  }
  // Full configuration hashes detect changed bindings, policies and mapper inputs
  // between a reviewed plan and apply. Only digests and IDs are written to evidence.
  const plan={schema:'otziv-issuer-provision-v2',realm,realmHash:hash(state),clients:clients.map(c=>({id:c.id,clientId:c.clientId,configurationHash:hash(c),mapperInputs:mapperPlans.get(c.id).input})),flows:plans};
  const planHash=hash(plan);
  if(!apply)return {...plan,planHash,applied:false};
  assert.equal(expectedPlanHash,planHash,'issuer_plan_changed_or_not_reviewed');
  const mapped=new Map();
  for(const p of plans) {
    if(!p.replaceCount){mapped.set(p.sourceId,{id:p.sourceId,alias:p.source});continue;}
    // Existing incomplete/unowned copies require operator inspection, never overwrite.
    assert.ok(!flows.some(f=>f.alias===p.target),'target_flow_exists_requires_review');
    await request(a+'/authentication/flows/'+pathPart(p.source)+'/copy',{method:'POST',body:{newName:p.target}});
    const copied=(await request(a+'/authentication/flows')).find(f=>f.alias===p.target);
    assert.ok(copied,'copied_flow_missing');
    const copiedSnapshot=await inspectFlow(request,a,copied);
    assert.equal(copiedSnapshot.behaviorHash,p.behaviorHash,'copied_flow_configuration_not_preserved');
    for(const row of copiedSnapshot.rows.filter(r=>Object.hasOwn(replacements,r.providerId))) {
      const execution=copiedSnapshot.executions.get(row.id);
      assert.equal(execution.authenticator,row.providerId,'copied_execution_changed');
      const {id,...copy}=execution;
      await request(a+'/authentication/executions',{method:'POST',body:{...copy,authenticator:replacements[row.providerId]}});
      await request(a+'/authentication/executions/'+row.id,{method:'DELETE'});
    }
    const actual=await inspectFlow(request,a,copied);
    assert.equal(actual.behaviorHash,p.behaviorHash,'flow_structure_or_configuration_not_preserved');
    mapped.set(p.sourceId,copied);
  }
  for(const client of clients) {
    const m='/clients/'+client.id+'/protocol-mappers/models';
    const current=await inspectMappers(request,a,client);
    assert.equal(hash(current.input),hash(mapperPlans.get(client.id).input),'concurrent_client_mapper_or_scope_change');
    if(!current.hasGeneration)await request(a+m,{method:'POST',body:generationMapper});
    const bindings={...(client.authenticationFlowBindingOverrides||{})};
    for(const key of ['browser','direct_grant'])if(bindings[key])bindings[key]=mapped.get(bindings[key]).id;
    if(JSON.stringify(bindings)!==JSON.stringify(client.authenticationFlowBindingOverrides||{}))
      await request(a+'/clients/'+client.id,{method:'PUT',body:{authenticationFlowBindingOverrides:bindings}});
  }
  // Realm binding switch is last. Preserve every other realm attribute, including
  // application policies. Old sessions are never decorated with a current generation.
  const latest=await request(a);
  assert.equal(latest.browserFlow,state.browserFlow,'concurrent_browser_binding_change');
  assert.equal(latest.directGrantFlow,state.directGrantFlow,'concurrent_direct_binding_change');
  const browserFlow=mapped.get(include(state.browserFlow).id).alias,directGrantFlow=mapped.get(include(state.directGrantFlow).id).alias;
  if(browserFlow!==latest.browserFlow||directGrantFlow!==latest.directGrantFlow||latest.attributes?.['otziv.security.generation.enabled']!=='true')
    await request(a,{method:'PUT',body:{browserFlow,directGrantFlow,attributes:{...latest.attributes,'otziv.security.generation.enabled':'true'}}});
  return {...plan,planHash,applied:true,backendEnforcementChanged:false,oldSessionsBackfilled:false};
}

/** Admin transport: only an explicitly rejected 401 may cause one authenticated replay.
 * Credentials and rotated refresh tokens stay in this closure, never in plan evidence.
 */
export function createAdminRequest({url,accessToken,refreshToken,adminRealm='master',adminClientId='admin-cli',fetchImpl=globalThis.fetch}) {
  let base;
  try {base=new URL(url);}catch {throw Error('issuer_url_invalid');}
  assert.ok(base.protocol==='https:'||(base.protocol==='http:'&&['localhost','127.0.0.1'].includes(base.hostname)),'issuer_https_required');
  assert.ok(!base.username&&!base.password&&!base.search&&!base.hash,'issuer_url_invalid');
  const token=value=>typeof value==='string'&&value.length>0&&!/[\s\x00-\x1f\x7f]/.test(value);
  assert.ok(token(accessToken),'admin_token_environment_missing');
  assert.ok(refreshToken===undefined||token(refreshToken),'admin_refresh_token_invalid');
  assert.ok(typeof adminRealm==='string'&&adminRealm.trim()===adminRealm&&adminRealm.length>0&&!['.','..'].includes(adminRealm)&&!/[\x00-\x1f\x7f]/.test(adminRealm),'admin_realm_invalid');
  assert.ok(typeof adminClientId==='string'&&adminClientId.trim()===adminClientId&&adminClientId.length>0&&!/[\x00-\x1f\x7f]/.test(adminClientId),'admin_client_id_invalid');
  assert.ok(typeof fetchImpl==='function','issuer_fetch_missing');
  const prefix=base.href.replace(/\/$/,''),adminPath=base.pathname.replace(/\/$/,'')+'/admin/';
  let revision=0,renewal,authenticationFailed=false;
  const discard=async response=>{try {await response.body?.cancel();}catch {/* No response content is exposed. */}};
  const send=async(target,options)=>{
    try {return await fetchImpl(target,{...options,redirect:'error',signal:AbortSignal.timeout(30000)});}
    catch {throw Error('issuer_transport_failed');}
  };
  const json=async response=>{
    let text;
    try {text=await response.text();}catch {throw Error('issuer_response_failed');}
    try {return text?JSON.parse(text):null;}catch {throw Error('issuer_response_invalid');}
  };
  const renew=async()=>{
    try {
      const response=await send(prefix+'/realms/'+encodeURIComponent(adminRealm)+'/protocol/openid-connect/token',{
        method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
        body:new URLSearchParams({grant_type:'refresh_token',client_id:adminClientId,refresh_token:refreshToken}).toString()
      });
      if(!response.ok){await discard(response);throw Error('issuer_admin_refresh_failed');}
      const next=await json(response);
      assert.ok(next&&token(next.access_token)&&typeof next.token_type==='string'&&next.token_type.toLowerCase()==='bearer'
        &&(next.refresh_token===undefined||token(next.refresh_token)),'issuer_admin_refresh_invalid');
      accessToken=next.access_token;
      if(next.refresh_token!==undefined)refreshToken=next.refresh_token;
      revision++;
    }catch {
      authenticationFailed=true;
      throw Error('issuer_admin_refresh_failed');
    }
  };
  return async(path,{method='GET',body}={})=>{
    let target;
    try {target=new URL(prefix+path);}catch {throw Error('issuer_admin_path_invalid');}
    assert.ok(typeof path==='string'&&path.startsWith('/admin/')&&target.origin===base.origin&&target.pathname.startsWith(adminPath)
      &&!target.search&&!target.hash,'issuer_admin_path_invalid');
    if(authenticationFailed)throw Error('issuer_admin_authentication_failed');
    let serialized;
    try {serialized=body===undefined?undefined:JSON.stringify(body);}catch {throw Error('issuer_request_invalid');}
    const execute=()=>send(target.href,{method,headers:{Authorization:'Bearer '+accessToken,'Content-Type':'application/json'},body:serialized});
    const sentRevision=revision;
    let response=await execute();
    if(response.status===401&&refreshToken!==undefined) {
      await discard(response);
      if(authenticationFailed)throw Error('issuer_admin_authentication_failed');
      // Concurrent stale requests share one renewal, including rotating refresh tokens.
      if(sentRevision===revision) {
        if(!renewal)renewal=renew();
        const pending=renewal;
        try {await pending;}finally {if(renewal===pending)renewal=undefined;}
      }
      if(authenticationFailed)throw Error('issuer_admin_authentication_failed');
      response=await execute();
      if(response.status===401)authenticationFailed=true;
    }
    if(!response.ok) {
      await discard(response);
      throw Error(Number.isInteger(response.status)?'issuer_http_'+response.status:'issuer_http_error');
    }
    return json(response);
  };
}

if(process.argv[1]===fileURLToPath(import.meta.url)) {
  try {
    const [url,realm,output,expectedPlanHash]=process.argv.slice(2);
    const request=createAdminRequest({url,accessToken:process.env.OTZIV_KEYCLOAK_ADMIN_TOKEN,
      refreshToken:process.env.OTZIV_KEYCLOAK_ADMIN_REFRESH_TOKEN,
      adminRealm:process.env.OTZIV_KEYCLOAK_ADMIN_REALM,adminClientId:process.env.OTZIV_KEYCLOAK_ADMIN_CLIENT_ID});
    const clientIds=(process.env.OTZIV_KEYCLOAK_PROTECTED_CLIENT_IDS||'').split(',').map(value=>value.trim()).filter(Boolean);
    const result=await provision(request,realm,{apply:!!expectedPlanHash,expectedPlanHash,clientIds});
    await writeFile(output,JSON.stringify(result,null,2)+'\n',{mode:0o600});
    console.log(JSON.stringify({applied:result.applied,planHash:result.planHash,clients:result.clients.length,flows:result.flows.length}));
  }catch(error){console.error(JSON.stringify({result:'FAIL',code:/^[a-z_0-9]+$/.test(error.message||'')?error.message:'issuer_provision_failed'}));process.exitCode=1;}
}
