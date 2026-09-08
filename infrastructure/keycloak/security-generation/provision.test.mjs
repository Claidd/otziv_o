import test from 'node:test';
import assert from 'node:assert/strict';
import {provision as provisionWithClients,generationMapper,createAdminRequest} from './provision.mjs';
const provision=(request,realm,options={})=>provisionWithClients(request,realm,{clientIds:['fixture-mobile'],...options});

const copy=value=>structuredClone(value);

/** Stateful Keycloak REST fixture, including real nested flow/config identity changes. */
function fixture() {
  const a='/admin/realms/fixture',writes=[];
  let serial=0,corruptCopy=false,corruptReplacement=false;
  const state={realm:'fixture',browserFlow:'browser',directGrantFlow:'direct',attributes:{retained:'yes'}};
  const clients=[{id:'client',clientId:'fixture-mobile',enabled:true,protocol:'openid-connect',standardFlowEnabled:true,directAccessGrantsEnabled:true}];
  const flows=new Map(),executions=new Map(),configs=new Map(),mappers=new Map([['client',[]]]);
  const scopes={default:[{id:'roles',name:'roles',protocol:'openid-connect'}],optional:[{id:'offline',name:'offline_access',protocol:'openid-connect'}]};
  const scopeMappers=new Map([['roles',[]],['offline',[]]]);
  const addFlow=(id,alias,topLevel)=>flows.set(id,{id,alias,description:'Preserved flow description',providerId:'basic-flow',topLevel,builtIn:true});
  const addExecution=(id,parentFlow,priority,values)=>executions.set(id,{id,parentFlow,priority,requirement:'REQUIRED',authenticatorFlow:false,...values});
  addFlow('browser','browser',true);addFlow('forms','browser forms',false);addFlow('direct','direct',true);
  addExecution('cookies','browser',10,{authenticator:'auth-cookie',requirement:'ALTERNATIVE'});
  addExecution('nested','browser',20,{authenticatorFlow:true,flowId:'forms',requirement:'ALTERNATIVE'});
  addExecution('password','forms',10,{authenticator:'auth-username-password-form',authenticatorConfig:'password-config'});
  addExecution('otp','forms',20,{authenticator:'auth-otp-form',authenticatorConfig:'otp-config'});
  addExecution('username','direct',10,{authenticator:'direct-grant-validate-username'});
  addExecution('direct-password','direct',20,{authenticator:'direct-grant-validate-password'});
  configs.set('password-config',{id:'password-config',alias:'password policy',config:{first:'one',second:'two'}});
  configs.set('otp-config',{id:'otp-config',alias:'OTP policy',config:{allowed:'totp',fixtureOpaqueValue:'synthetic-sensitive-config'}});
  const children=id=>[...executions.values()].filter(e=>e.parentFlow===id).sort((x,y)=>x.priority-y.priority);
  const rows=(id,level=0)=>children(id).flatMap((execution,index)=>[
    {id:execution.id,providerId:execution.authenticator,authenticationFlow:execution.authenticatorFlow,
      flowId:execution.flowId,authenticationConfig:execution.authenticatorConfig,level,index,priority:execution.priority,
      requirement:execution.requirement,displayName:execution.authenticator||flows.get(execution.flowId).alias},
    ...(execution.authenticatorFlow?rows(execution.flowId,level+1):[])
  ]);
  function cloneFlow(id,alias) {
    const source=flows.get(id),next='flow-'+(++serial);
    flows.set(next,{...copy(source),id:next,alias,builtIn:false});
    for(const original of children(id)) {
      const execution=copy(original);execution.id='execution-'+(++serial);execution.parentFlow=next;
      if(original.flowId)execution.flowId=cloneFlow(original.flowId,alias+' nested');
      if(original.authenticatorConfig) {
        const config=copy(configs.get(original.authenticatorConfig));config.id='config-'+(++serial);config.alias=alias+' copied configuration';
        if(corruptCopy&&original.authenticator==='auth-otp-form')config.config.allowed='changed-by-copy';
        configs.set(config.id,config);execution.authenticatorConfig=config.id;
      }
      executions.set(execution.id,execution);
    }
    return next;
  }
  const request=async(path,{method='GET',body}={})=>{
    assert.ok(path.startsWith(a));const url=path.slice(a.length);
    if(method!=='GET')writes.push({method,path});
    if(!url) {if(method==='PUT')Object.assign(state,copy(body));return copy(state);}
    if(url==='/clients')return copy(clients);
    if(url==='/authentication/flows')return copy([...flows.values()]);
    let match;
    if((match=url.match(/^\/authentication\/flows\/([^/]+)\/executions$/))) {
      const flow=[...flows.values()].find(f=>f.alias===decodeURIComponent(match[1]));assert.ok(flow);return copy(rows(flow.id));
    }
    if((match=url.match(/^\/authentication\/flows\/([^/]+)\/copy$/))) {
      const flow=[...flows.values()].find(f=>f.alias===decodeURIComponent(match[1]));assert.ok(flow);cloneFlow(flow.id,body.newName);return null;
    }
    if((match=url.match(/^\/authentication\/flows\/([^/]+)$/)))return copy(flows.get(decodeURIComponent(match[1])));
    if((match=url.match(/^\/authentication\/executions\/([^/]+)$/))) {
      if(method==='DELETE'){executions.delete(match[1]);return null;}return copy(executions.get(match[1]));
    }
    if(url==='/authentication/executions'&&method==='POST') {
      const execution={...copy(body),id:'execution-'+(++serial)};
      if(corruptReplacement&&execution.authenticator==='otziv-generation-browser-password')configs.get(execution.authenticatorConfig).config.first='changed-by-replacement';
      executions.set(execution.id,execution);return null;
    }
    if((match=url.match(/^\/authentication\/config\/([^/]+)$/)))return copy(configs.get(decodeURIComponent(match[1])));
    if((match=url.match(/^\/clients\/([^/]+)\/(default|optional)-client-scopes$/)))return copy(scopes[match[2]]);
    if((match=url.match(/^\/client-scopes\/([^/]+)\/protocol-mappers\/models$/)))return copy(scopeMappers.get(match[1]));
    if((match=url.match(/^\/clients\/([^/]+)\/protocol-mappers\/models$/))) {
      if(method==='POST'){mappers.get(match[1]).push({...copy(body),id:'mapper-'+(++serial)});return null;}return copy(mappers.get(match[1]));
    }
    if((match=url.match(/^\/clients\/([^/]+)$/))&&method==='PUT'){Object.assign(clients.find(c=>c.id===match[1]),copy(body));return null;}
    throw Error('Unexpected fixture request '+method+' '+url);
  };
  return {request,state,clients,flows,executions,configs,mappers,scopeMappers,scopes,writes,
    corruptCopy:()=>{corruptCopy=true;},corruptReplacement:()=>{corruptReplacement=true;},
    activate:async()=>{const plan=await provision(request,'fixture');return provision(request,'fixture',{apply:true,expectedPlanHash:plan.planHash});}};
}

test('unchanged nested password/OTP flow copies configuration and a second apply is a no-op',async()=>{
  const f=fixture();const first=await provision(f.request,'fixture');assert.equal(f.writes.length,0);
  assert.ok(!JSON.stringify(first).includes('synthetic-sensitive-config'));
  const applied=await provision(f.request,'fixture',{apply:true,expectedPlanHash:first.planHash});
  assert.equal(applied.applied,true);assert.equal(f.state.attributes.retained,'yes');
  assert.equal(f.state.attributes['otziv.security.generation.enabled'],'true');
  assert.notEqual(f.state.browserFlow,'browser');
  const count=f.writes.length,next=await provision(f.request,'fixture');
  assert.ok(next.flows.every(flow=>flow.replaceCount===0));
  await provision(f.request,'fixture',{apply:true,expectedPlanHash:next.planHash});assert.equal(f.writes.length,count);
});

for(const [name,change] of [
  ['configuration value',f=>{f.configs.get('otp-config').config.allowed='hotp';}],
  ['configuration binding',f=>{f.configs.set('alternate',{...copy(f.configs.get('otp-config')),id:'alternate'});f.executions.get('otp').authenticatorConfig='alternate';}],
  ['full execution DTO',f=>{f.executions.get('otp').requirement='DISABLED';}],
  ['nested flow metadata',f=>{f.flows.get('forms').providerId='different-provider';}],
  ['default scope mapper configuration',f=>{f.scopeMappers.get('roles').push({id:'role-map',protocolMapper:'oidc-usermodel-realm-role-mapper',config:{prefix:'changed'}});}]
])test(name+' drift rejects the reviewed plan before all writes',async()=>{
  const f=fixture(),plan=await provision(f.request,'fixture');change(f);
  await assert.rejects(provision(f.request,'fixture',{apply:true,expectedPlanHash:plan.planHash}),/issuer_plan_changed_or_not_reviewed/);
  assert.equal(f.writes.length,0);
});

for(const kind of ['default','optional'])test(kind+' inherited generation writer is refused before writes',async()=>{
  const f=fixture(),plan=await provision(f.request,'fixture');
  f.scopeMappers.get(kind==='default'?'roles':'offline').push({id:'conflict',protocolMapper:'oidc-usermodel-attribute-mapper',config:{'claim.name':'otziv_session_generation','user.attribute':'mutable'}});
  await assert.rejects(provision(f.request,'fixture',{apply:true,expectedPlanHash:plan.planHash}),/inherited_generation_mapper_requires_review/);
  assert.equal(f.writes.length,0);
});

test('an inherited identical optional mapper still cannot create conditional duplicate claim ownership',async()=>{
  const f=fixture();f.scopeMappers.get('offline').push({id:'inherited',...copy(generationMapper)});
  await assert.rejects(provision(f.request,'fixture'),/inherited_generation_mapper_requires_review/);assert.equal(f.writes.length,0);
});

test('direct conflicting mapper is detected during planning, before copying any flow',async()=>{
  const f=fixture();f.mappers.get('client').push({id:'conflict',protocolMapper:'oidc-usermodel-attribute-mapper',config:{'claim.name':'otziv_session_generation'}});
  await assert.rejects(provision(f.request,'fixture'),/existing_generation_mapper_requires_review/);assert.equal(f.writes.length,0);
});

for(const phase of ['copy','replacement'])test('configuration corruption during '+phase+' never activates a changed flow',async()=>{
  const f=fixture(),plan=await provision(f.request,'fixture');phase==='copy'?f.corruptCopy():f.corruptReplacement();
  await assert.rejects(provision(f.request,'fixture',{apply:true,expectedPlanHash:plan.planHash}),/configuration_not_preserved/);
  assert.equal(f.state.browserFlow,'browser');assert.equal(f.state.directGrantFlow,'direct');
  assert.equal(f.state.attributes['otziv.security.generation.enabled'],undefined);
  assert.equal(f.mappers.get('client').length,0);
});

test('canonical object key order does not manufacture configuration drift',async()=>{
  const f=fixture(),plan=await provision(f.request,'fixture');f.configs.get('password-config').config={second:'two',first:'one'};
  const same=await provision(f.request,'fixture');assert.equal(same.planHash,plan.planHash);assert.equal(f.writes.length,0);
});

test('copied-but-incomplete setup is retained for operator review instead of overwritten on retry',async()=>{
  const f=fixture(),plan=await provision(f.request,'fixture');f.corruptCopy();
  await assert.rejects(provision(f.request,'fixture',{apply:true,expectedPlanHash:plan.planHash}),/configuration_not_preserved/);
  const next=await provision(f.request,'fixture'),count=f.writes.length;
  await assert.rejects(provision(f.request,'fixture',{apply:true,expectedPlanHash:next.planHash}),/target_flow_exists_requires_review/);
  assert.equal(f.writes.length,count);
});

for(const [name,clientIds,code] of [
  ['missing',undefined,'protected_client_ids_required'],
  ['empty',[],'protected_client_ids_required'],
  ['duplicate',['fixture-mobile','fixture-mobile'],'protected_client_ids_required'],
  ['unmatched',['fixture-mobile','missing-client'],'protected_client_missing_disabled_or_not_interactive']
])test(name+' protected client list fails before any apply write',async()=>{
  const f=fixture(),plan=await provision(f.request,'fixture');
  await assert.rejects(provisionWithClients(f.request,'fixture',{apply:true,expectedPlanHash:plan.planHash,clientIds}),new RegExp(code));
  assert.equal(f.writes.length,0);
});

for(const [name,change] of [
  ['disabled',client=>{client.enabled=false;}],
  ['non-interactive',client=>{client.standardFlowEnabled=false;client.directAccessGrantsEnabled=false;}],
  ['non-OIDC',client=>{client.protocol='saml';}]
])test(name+' explicitly protected client cannot silently disappear from an apply plan',async()=>{
  const f=fixture(),plan=await provision(f.request,'fixture');change(f.clients[0]);
  await assert.rejects(provision(f.request,'fixture',{apply:true,expectedPlanHash:plan.planHash}),/protected_client_missing_disabled_or_not_interactive/);
  assert.equal(f.writes.length,0);
});

test('unlisted builtin account and admin clients retain their own configuration and mappers',async()=>{
  const f=fixture();
  for(const [id,clientId] of [['builtin-account','account'],['builtin-admin','security-admin-console']]) {
    f.clients.push({id,clientId,enabled:true,protocol:'openid-connect',standardFlowEnabled:true,
      authenticationFlowBindingOverrides:{browser:'unrelated-flow'},attributes:{retained:'yes'}});
    // These conflicting writers would fail planning if an unlisted client were inspected.
    f.mappers.set(id,[{id:'retained-'+id,protocolMapper:'oidc-usermodel-attribute-mapper',config:{'claim.name':'otziv_session_generation'}}]);
  }
  const beforeClients=copy(f.clients.slice(1)),beforeMappers=copy([...f.mappers.entries()].slice(1)),requests=[];
  const request=async(path,options)=>{requests.push(path);return f.request(path,options);};
  const plan=await provision(request,'fixture');
  assert.deepEqual(plan.clients.map(client=>client.clientId),['fixture-mobile']);
  await provision(request,'fixture',{apply:true,expectedPlanHash:plan.planHash});
  assert.deepEqual(f.clients.slice(1),beforeClients);
  assert.deepEqual([...f.mappers.entries()].slice(1),beforeMappers);
  assert.ok(!requests.some(path=>path.includes('/clients/builtin-')));
  assert.ok(f.writes.some(row=>row.path.includes('/clients/client/protocol-mappers/')));
  // Realm-level flow activation remains intentional; this asserts client-level isolation.
  assert.notEqual(f.state.browserFlow,'browser');
});

const http=(status,value)=>new Response(value===undefined?null:JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
function transport(responses,options={}) {
  const calls=[];
  const request=createAdminRequest({url:'https://issuer.invalid/keycloak',accessToken:'access-one',refreshToken:'refresh-one',...options,
    fetchImpl:async(url,init)=>{
      calls.push({url,...init});const response=responses.shift();
      assert.ok(response,'Unexpected HTTP request');
      return typeof response==='function'?response(url,init):response;
    }});
  return {request,calls,responses};
}
const renewed=(access,refresh)=>http(200,{access_token:access,token_type:'Bearer',...(refresh===undefined?{}:{refresh_token:refresh})});

test('401 renews the configured admin session, replays only the rejected mutation and retains rotated refresh tokens',async()=>{
  let accepted=0;
  const t=transport([http(401),renewed('access-two','refresh-two'),()=>{accepted++;return http(204);},
    http(401),renewed('access-three','refresh-three'),()=>{accepted++;return http(204);}],{adminRealm:'operators',adminClientId:'provision-cli'});
  const body={newName:'reviewed-flow',nested:{retained:true}};
  await t.request('/admin/realms/fixture/authentication/flows/browser/copy',{method:'POST',body});
  await t.request('/admin/realms/fixture/authentication/executions/old',{method:'DELETE'});
  assert.equal(accepted,2);assert.equal(t.calls.length,6);
  assert.deepEqual(t.calls.map(call=>call.method),['POST','POST','POST','DELETE','POST','DELETE']);
  assert.deepEqual([0,2,3,5].map(index=>t.calls[index].headers.Authorization),['Bearer access-one','Bearer access-two','Bearer access-two','Bearer access-three']);
  assert.equal(t.calls[0].url,t.calls[2].url);assert.equal(t.calls[0].body,t.calls[2].body);
  assert.equal(t.calls[3].url,t.calls[5].url);assert.equal(t.calls[3].body,t.calls[5].body);
  for(const [index,refresh] of [[1,'refresh-one'],[4,'refresh-two']]) {
    assert.equal(t.calls[index].url,'https://issuer.invalid/keycloak/realms/operators/protocol/openid-connect/token');
    assert.deepEqual(Object.fromEntries(new URLSearchParams(t.calls[index].body)),{grant_type:'refresh_token',client_id:'provision-cli',refresh_token:refresh});
    assert.equal(t.calls[index].headers.Authorization,undefined);
  }
  assert.ok(t.calls.every(call=>call.redirect==='error'&&call.signal instanceof AbortSignal));
});

test('static access tokens work and a 401 without explicit refresh credentials is never replayed',async()=>{
  const t=transport([http(200,{realm:'fixture'}),http(401,{error:'untrusted-response'})],{refreshToken:undefined});
  assert.deepEqual(await t.request('/admin/realms/fixture'),{realm:'fixture'});
  await assert.rejects(t.request('/admin/realms/fixture',{method:'PUT',body:{enabled:true}}),{message:'issuer_http_401'});
  assert.equal(t.calls.length,2);assert.ok(t.calls.every(call=>call.url.includes('/admin/')));
});

test('a second 401 stops after one refresh and one replay, then fails closed without another request',async()=>{
  const t=transport([http(401),renewed('access-two','refresh-two'),http(401,{error:'untrusted-response'})]);
  await assert.rejects(t.request('/admin/realms/fixture',{method:'PUT',body:{enabled:true}}),{message:'issuer_http_401'});
  assert.equal(t.calls.length,3);
  await assert.rejects(t.request('/admin/realms/fixture'),{message:'issuer_admin_authentication_failed'});
  assert.equal(t.calls.length,3);
});

for(const status of [403,409,429,500,503])test(status+' never triggers a refresh or a mutation replay',async()=>{
  const t=transport([http(status,{error_description:'untrusted-response'})]);
  await assert.rejects(t.request('/admin/realms/fixture',{method:'POST',body:{enabled:true}}),{message:'issuer_http_'+status});
  assert.equal(t.calls.length,1);
});

for(const [name,response] of [
  ['HTTP rejection',()=>http(401,{error_description:'untrusted-refresh-detail'})],
  ['server error',()=>http(503,{error_description:'untrusted-refresh-detail'})],
  ['transport failure',()=>{throw Error('untrusted-refresh-detail');}],
  ['invalid JSON',()=>new Response('untrusted-refresh-detail',{status:200})],
  ['missing access token',()=>http(200,{refresh_token:'refresh-two',token_type:'Bearer'})],
  ['non-bearer token',()=>http(200,{access_token:'access-two',token_type:'Other'})],
  ['invalid rotated refresh token',()=>http(200,{access_token:'access-two',token_type:'Bearer',refresh_token:''})]
])test('refresh '+name+' fails closed without exposing provider details or retrying the mutation',async()=>{
  const t=transport([http(401),response]);
  await assert.rejects(t.request('/admin/realms/fixture',{method:'DELETE'}),{message:'issuer_admin_refresh_failed'});
  assert.equal(t.calls.length,2);
  await assert.rejects(t.request('/admin/realms/fixture'),{message:'issuer_admin_authentication_failed'});
  assert.equal(t.calls.length,2);
});

test('an ambiguous transport failure never retries a mutation or exposes its error text',async()=>{
  const t=transport([()=>{throw Error('untrusted-transport-detail');}]);
  await assert.rejects(t.request('/admin/realms/fixture',{method:'POST',body:{enabled:true}}),{message:'issuer_transport_failed'});
  assert.equal(t.calls.length,1);
});

test('a successful mutation with an invalid response cannot be silently repeated',async()=>{
  const t=transport([new Response('untrusted-response-detail',{status:200})]);
  await assert.rejects(t.request('/admin/realms/fixture',{method:'POST',body:{enabled:true}}),{message:'issuer_response_invalid'});
  assert.equal(t.calls.length,1);
});

test('refresh responses without rotation preserve the existing refresh credential',async()=>{
  const t=transport([http(401),renewed('access-two'),http(204),http(401),renewed('access-three'),http(204)]);
  await t.request('/admin/realms/fixture');await t.request('/admin/realms/fixture');
  assert.deepEqual([1,4].map(index=>new URLSearchParams(t.calls[index].body).get('refresh_token')),['refresh-one','refresh-one']);
  assert.equal(t.calls[1].url,'https://issuer.invalid/keycloak/realms/master/protocol/openid-connect/token');
  assert.equal(new URLSearchParams(t.calls[1].body).get('client_id'),'admin-cli');
});

test('concurrent stale 401 responses share one refresh and replay each rejected request once',async()=>{
  const calls=[];let releaseRefresh;
  const refreshing=new Promise(resolve=>{releaseRefresh=resolve;});
  const request=createAdminRequest({url:'https://issuer.invalid/keycloak',accessToken:'access-one',refreshToken:'refresh-one',fetchImpl:async(url,init)=>{
    calls.push({url,...init});
    if(url.includes('/protocol/openid-connect/token')) {await refreshing;return renewed('access-two','refresh-two');}
    return init.headers.Authorization==='Bearer access-one'?http(401):http(200,{ok:true});
  }});
  const a=request('/admin/realms/first'),b=request('/admin/realms/second');
  // The first renewal is held open while both 401 branches reach the shared promise.
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(calls.filter(call=>call.url.includes('/protocol/openid-connect/token')).length,1);
  releaseRefresh();assert.deepEqual(await Promise.all([a,b]),[{ok:true},{ok:true}]);
  assert.equal(calls.length,5);
  assert.equal(calls.filter(call=>call.headers.Authorization==='Bearer access-two').length,2);
});

for(const path of ['https://other.invalid/admin/realms/fixture','//other.invalid/admin/realms/fixture','/admin/../../realms/master','/admin/realms/fixture?secret=fixture','/admin/realms/fixture#fragment'])
  test('admin adapter rejects unsafe target '+path+' before exposing credentials',async()=>{
    const t=transport([]);await assert.rejects(t.request(path),{message:'issuer_admin_path_invalid'});assert.equal(t.calls.length,0);
  });

for(const url of ['http://issuer.invalid/keycloak','ftp://localhost/keycloak','https://name:fixture@issuer.invalid/keycloak','https://issuer.invalid/keycloak?query=yes'])
  test('admin adapter rejects unsafe base URL '+url,()=>{
    assert.throws(()=>transport([],{url}),/issuer_(https_required|url_invalid)/);
  });
