import {spawnSync} from 'node:child_process';
import {randomUUID,createHash} from 'node:crypto';
import {writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
import {provision} from './provision.mjs';

const image=process.argv[2],output=resolve(process.argv[3]||'issuer-proof.json');
if(!image||!/^[-a-zA-Z0-9_./:@]+$/.test(image))throw Error('Expected local issuer image');
const owner='otziv-issuer-proof-'+randomUUID(),network=owner+'-net',pg=owner+'-pg',kc=owner+'-kc',runner=owner+'-http';
const password='Fixture-'+randomUUID()+'!aA9',realm='proof-'+randomUUID(),username='fixture-user';
const pgImage=process.env.OTZIV_ISSUER_PROOF_POSTGRES_IMAGE||'postgres@sha256:a426e44bac0b759c95894d68e1a0ac03ecc20b619f498a91aae373bf06d8508d';
const results={schema:'otziv-issuer-generation-proof-v1',startedAt:new Date().toISOString(),image,production:false,realProviderLogin:true,checks:[]};
let base,admin,backendToken;
function docker(args,{input,allowFailure=false,timeout=120000}={}){
  const run=spawnSync('docker',args,{input,encoding:'utf8',timeout,windowsHide:true,maxBuffer:4*1024*1024});
  if(run.error||run.status!==0){if(allowFailure)return null;throw Error('docker_command_failed_'+String(args[0]));}
  return run.stdout.trim();
}
function check(name,body){assert.ok(body,name);results.checks.push({name,passed:true});console.log('PASS '+name);}
function jwt(token){return JSON.parse(Buffer.from(token.split('.')[1],'base64url').toString());}
function isolatedHttp(options){
  const bridge="let input='';for await(const chunk of process.stdin)input+=chunk;const q=JSON.parse(input);const r=await fetch(q.url,{...q.options,redirect:'manual',signal:AbortSignal.timeout(20000)});const text=await r.text();if(text.length>1048576)throw Error('response_too_large');console.log(JSON.stringify({status:r.status,text,cookies:r.headers.getSetCookie(),location:r.headers.get('location')}));";
  return JSON.parse(docker(['exec','-i',runner,'node','--input-type=module','-e',bridge],{input:JSON.stringify(options),timeout:30000}));
}
async function request(path,{method='GET',body,token=admin,form,expected=[200,201,204],retryAdmin=true}={}){
  const headers={...(token?{Authorization:'Bearer '+token}:{})};
  if(body!==undefined)headers['Content-Type']='application/json';
  if(form)headers['Content-Type']='application/x-www-form-urlencoded';
  const response=isolatedHttp({url:base+path,options:{method,headers,body:form?new URLSearchParams(form).toString():body===undefined?undefined:JSON.stringify(body)}});
  const text=response.text;
  if(response.status===401&&retryAdmin&&token===admin&&admin){
    admin=(await request('/realms/master/protocol/openid-connect/token',{method:'POST',token:null,form:{grant_type:'password',client_id:'admin-cli',username:'proof-admin',password}})).value.access_token;
    return request(path,{method,body,token:admin,form,expected,retryAdmin:false});
  }
  if(!expected.includes(response.status))throw Error('issuer_http_'+response.status+'_'+path.replace(/proof-[a-f0-9-]+/g,'proof'));
  let value=null;try{value=text?JSON.parse(text):null;}catch{}
  return {status:response.status,value,location:response.location};
}
async function token(client,userPassword=password,offline=false,refresh){
  return (await request('/realms/'+realm+'/protocol/openid-connect/token',{method:'POST',token:null,
    form:refresh?{grant_type:'refresh_token',client_id:client,refresh_token:refresh}:{grant_type:'password',client_id:client,username,password:userPassword,scope:offline?'openid offline_access':'openid'}})).value;
}
async function current(subject){return (await request('/realms/'+realm+'/otziv-security/generation/'+subject,{token:backendToken})).value.generation;}
async function waitIssuer(){for(let i=0;i<100;i++){try{const r=isolatedHttp({url:base+'/realms/master/.well-known/openid-configuration',options:{}});if(r.status===200)return;}catch{}await new Promise(r=>setTimeout(r,500));}throw Error('issuer_startup_timeout');}
const captured=t=>jwt(t.access_token).otziv_session_generation;
try{
  const endpoint=docker(['context','inspect','--format','{{.Endpoints.docker.Host}}']);
  assert.match(endpoint,/^(npipe|unix):\/\//,'local Docker only');
  results.postgresImageId=docker(['image','inspect','--format','{{.Id}}',pgImage]);
  docker(['network','create','--internal','--label','otziv.proof='+owner,network]);
  docker(['run','-d','--name',pg,'--label','otziv.proof='+owner,'--network',network,'--memory','768m','--pids-limit','160','-e','POSTGRES_DB=keycloak','-e','POSTGRES_USER=keycloak','-e','POSTGRES_PASSWORD='+password,pgImage]);
  for(let i=0;i<60;i++){if(docker(['exec',pg,'pg_isready','-U','keycloak'],{allowFailure:true})!==null)break;await new Promise(r=>setTimeout(r,500));}
  docker(['run','-d','--name',kc,'--label','otziv.proof='+owner,'--network',network,'--memory','1536m','--pids-limit','300',
    '-e','KC_DB=postgres','-e','KC_DB_URL=jdbc:postgresql://'+pg+':5432/keycloak','-e','KC_DB_USERNAME=keycloak','-e','KC_DB_PASSWORD='+password,
    '-e','KC_BOOTSTRAP_ADMIN_USERNAME=proof-admin','-e','KC_BOOTSTRAP_ADMIN_PASSWORD='+password,
    image,'start','--optimized','--http-enabled=true','--hostname-strict=false']);
  const httpImage=process.env.OTZIV_ISSUER_PROOF_NODE_IMAGE||'otziv-external-review-worker:prod-local';
  docker(['run','-d','--name',runner,'--label','otziv.proof='+owner,'--network',network,'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','256m','--pids-limit','64','--entrypoint','node',httpImage,'-e','setInterval(()=>{},100000)']);
  base='http://'+kc+':8080';
  await waitIssuer();
  admin=(await request('/realms/master/protocol/openid-connect/token',{method:'POST',token:null,form:{grant_type:'password',client_id:'admin-cli',username:'proof-admin',password}})).value.access_token;
  await request('/admin/realms',{method:'POST',body:{realm,enabled:true,attributes:{'fixture.policy.preserved':'true'},eventsListeners:['jboss-logging'],adminEventsEnabled:true}});
  const a='/admin/realms/'+realm;
  const mapper={name:'otziv immutable session generation',protocol:'openid-connect',protocolMapper:'oidc-usersessionmodel-note-mapper',config:{'user.session.note':'otziv.security.generation.v1','claim.name':'otziv_session_generation','jsonType.label':'String','access.token.claim':'true','id.token.claim':'false','introspection.token.claim':'true'}};
  await request(a+'/clients',{method:'POST',body:{clientId:'fixture-mobile',enabled:true,publicClient:true,directAccessGrantsEnabled:true,standardFlowEnabled:true,redirectUris:['http://127.0.0.1/callback'],defaultClientScopes:['basic','profile','email','roles'],optionalClientScopes:['offline_access'],protocolMappers:[mapper]}});
  await request(a+'/clients',{method:'POST',body:{clientId:'fixture-backend',enabled:true,publicClient:false,secret:password,serviceAccountsEnabled:true,standardFlowEnabled:false,directAccessGrantsEnabled:false}});
  const clients=(await request(a+'/clients')).value;
  const reader=clients.find(c=>c.clientId==='fixture-backend'),management=clients.find(c=>c.clientId==='realm-management');
  const sa=(await request(a+'/clients/'+reader.id+'/service-account-user')).value;
  const viewUsers=(await request(a+'/clients/'+management.id+'/roles/view-users')).value;
  await request(a+'/users/'+sa.id+'/role-mappings/clients/'+management.id,{method:'POST',body:[viewUsers]});
  backendToken=(await request('/realms/'+realm+'/protocol/openid-connect/token',{method:'POST',token:null,form:{grant_type:'client_credentials',client_id:'fixture-backend',client_secret:password}})).value.access_token;
  const created=await request(a+'/users',{method:'POST',body:{username,enabled:true,emailVerified:true,email:'fixture@example.invalid',firstName:'Fixture',lastName:'Proof',credentials:[{type:'password',value:password,temporary:false}]}});
  const subject=created.location.split('/').pop();
  await request(a+'/roles',{method:'POST',body:{name:'fixture-role'}});
  const role=(await request(a+'/roles/fixture-role')).value;
  await request(a+'/users/'+subject+'/role-mappings/realm',{method:'POST',body:[role]});
  const provisioningRequest=async(path,options)=>(await request(path,options)).value;
  const protectedClients={clientIds:['fixture-mobile']};
  const plan=await provision(provisioningRequest,realm,protectedClients);
  check('provision_plan_is_read_only',!(await request(a)).value.attributes?.['otziv.security.generation.enabled']);
  const applied=await provision(provisioningRequest,realm,{...protectedClients,apply:true,expectedPlanHash:plan.planHash});
  check('production_provisioner_preserves_nested_otp_flow_and_realm_policy',applied.applied&&(await request(a)).value.attributes['fixture.policy.preserved']==='true');
  const noChanges=await provision(provisioningRequest,realm,protectedClients);
  check('provision_reentry_does_not_clone_another_authenticator',noChanges.flows.every(f=>f.replaceCount===0));
  const first=await token('fixture-mobile');
  check('actual_password_login_captures_current_generation',captured(first)===await current(subject));
  // Exercise the real browser password authenticator and PKCE code exchange; no
  // native device, external IdP or production account participates in this fixture.
  const verifier=randomUUID()+randomUUID(),challenge=createHash('sha256').update(verifier).digest('base64url');
  const authUrl=base+'/realms/'+realm+'/protocol/openid-connect/auth?'+new URLSearchParams({client_id:'fixture-mobile',redirect_uri:'http://127.0.0.1/callback',response_type:'code',scope:'openid',state:owner,code_challenge:challenge,code_challenge_method:'S256'});
  const browserForm=isolatedHttp({url:authUrl,options:{}});
  assert.equal(browserForm.status,200);
  const action=browserForm.text.match(/<form[^>]+action="([^"]+)"/i)?.[1]?.replaceAll('&amp;','&');
  assert.ok(action&&action.startsWith(base+'/'),'fixture login action must remain inside the issuer');
  const browserResult=isolatedHttp({url:action,options:{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',Cookie:browserForm.cookies.map(c=>c.split(';')[0]).join('; ')},body:new URLSearchParams({username,password}).toString()}});
  assert.equal(browserResult.status,302);
  const redirect=new URL(browserResult.location);assert.equal(redirect.origin,'http://127.0.0.1');assert.equal(redirect.searchParams.get('state'),owner);
  const browserTokens=(await request('/realms/'+realm+'/protocol/openid-connect/token',{method:'POST',token:null,form:{grant_type:'authorization_code',client_id:'fixture-mobile',code:redirect.searchParams.get('code'),redirect_uri:'http://127.0.0.1/callback',code_verifier:verifier}})).value;
  check('browser_password_pkce_login_captures_immutable_generation',captured(browserTokens)===await current(subject));
  const denied=await request('/realms/'+realm+'/otziv-security/generation/'+subject,{token:first.access_token,expected:[403]});
  check('ordinary_user_cannot_read_generation_authority',denied.status===403);
  await request(a+'/users/'+subject+'/role-mappings/realm',{method:'DELETE',body:[role]});
  await request(a+'/users/'+subject+'/role-mappings/realm',{method:'POST',body:[role]});
  check('remove_then_restore_role_does_not_revive_old_generation',captured(first)!==await current(subject));
  const refreshed=await token('fixture-mobile',password,false,first.refresh_token);
  check('refresh_preserves_original_generation',captured(refreshed)===captured(first));
  let fresh=await token('fixture-mobile');
  check('new_login_after_role_change_is_usable',captured(fresh)===await current(subject));
  await request(a+'/users/'+subject,{method:'PUT',body:{enabled:false}});
  await request(a+'/users/'+subject,{method:'PUT',body:{enabled:true}});
  check('disable_then_enable_does_not_revive_old_generation',captured(fresh)!==await current(subject));
  const offline=await token('fixture-mobile',password,true);
  check('offline_login_has_immutable_generation',captured(offline)===await current(subject));
  await request(a+'/users/'+subject+'/role-mappings/realm',{method:'DELETE',body:[role]});
  await request(a+'/users/'+subject+'/role-mappings/realm',{method:'POST',body:[role]});
  const offlineRefreshed=await token('fixture-mobile',password,true,offline.refresh_token);
  check('offline_refresh_cannot_acquire_current_generation',captured(offlineRefreshed)===captured(offline)&&captured(offline)!==await current(subject));
  fresh=await token('fixture-mobile');
  const freshSid=jwt(fresh.access_token).sid;
  await request(a+'/sessions/'+jwt(first.access_token).sid,{method:'DELETE',expected:[204,404]});
  check('late_exact_session_delete_does_not_revoke_fresh_login',captured(fresh)===await current(subject)&&freshSid!==jwt(first.access_token).sid);
  const beforeRestart=await current(subject);
  docker(['restart',kc]);
  await waitIssuer();
  check('issuer_restart_preserves_generation_tombstone',await current(subject)===beforeRestart&&captured(first)!==await current(subject));
  const finalLogin=await token('fixture-mobile');
  check('fresh_login_after_restart_works',captured(finalLogin)===await current(subject));
  const beforeListenerDisable=await current(subject);
  await request(a+'/events/config',{method:'PUT',body:{eventsListeners:[],adminEventsEnabled:false}});
  await request(a+'/users/'+subject+'/role-mappings/realm',{method:'DELETE',body:[role]});
  await request(a+'/users/'+subject+'/role-mappings/realm',{method:'POST',body:[role]});
  await request(a+'/events/config',{method:'PUT',body:{eventsListeners:['jboss-logging'],adminEventsEnabled:true}});
  check('listener_disable_and_reenable_cannot_erase_security_history',await current(subject)!==beforeListenerDisable);
  await request(a,{method:'PUT',body:{attributes:{'otziv.security.generation.enabled':'false'}}});
  await request('/realms/'+realm+'/otziv-security/generation/'+subject,{token:backendToken,expected:[503]});
  await request(a,{method:'PUT',body:{attributes:{'otziv.security.generation.enabled':'true'}}});
  check('disabled_authority_fails_closed_and_reactivation_fences_existing_sessions',captured(finalLogin)!==await current(subject));
  const beforeFailedMutation=await current(subject);
  const sql=statement=>docker(['exec','-i',pg,'psql','-U','keycloak','-d','keycloak','-v','ON_ERROR_STOP=1','-At'],{input:statement});
  sql("CREATE FUNCTION proof_reject_fence() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture journal unavailable'; END $$; CREATE TRIGGER proof_reject_fence BEFORE UPDATE ON otziv_sec_generation FOR EACH ROW EXECUTE FUNCTION proof_reject_fence();");
  try {
    const failure=await request(a+'/users/'+subject,{method:'PUT',body:{enabled:false},expected:[400,500,503]});
    check('failed_journal_rejects_admin_security_mutation',failure.status>=400);
    check('failed_journal_rolls_back_actual_user_state',(await request(a+'/users/'+subject)).value.enabled===true);
  } finally { sql('DROP TRIGGER proof_reject_fence ON otziv_sec_generation; DROP FUNCTION proof_reject_fence();'); }
  check('failed_mutation_does_not_advance_generation',await current(subject)===beforeFailedMutation);
  results.imageId=docker(['image','inspect','--format','{{.Id}}',image]);
  results.postgresImage=pgImage;
  results.result='PASS';
}catch(error){results.result='FAIL';results.error=String(error.message).slice(0,400);}
finally{
  if(results.result!=='PASS'){
    const diagnostic=docker(['logs',kc],{allowFailure:true})||'';
    await mkdir(resolve(output,'..'),{recursive:true});
    await writeFile(output+'.container.log',diagnostic.replaceAll(password,'[REDACTED]').slice(-40000),{mode:0o600});
  }
  for(const name of [runner,kc,pg]){
    const label=docker(['inspect','--format','{{index .Config.Labels "otziv.proof"}}',name],{allowFailure:true});
    if(label===owner)docker(['rm','-f','-v',name],{allowFailure:true});
  }
  const label=docker(['network','inspect','--format','{{index .Labels "otziv.proof"}}',network],{allowFailure:true});
  if(label===owner)docker(['network','rm',network],{allowFailure:true});
  results.finishedAt=new Date().toISOString();await mkdir(resolve(output,'..'),{recursive:true});
  await writeFile(output,JSON.stringify(results,null,2)+'\n',{mode:0o600});
  console.log(JSON.stringify({result:results.result,checks:results.checks.length,error:results.error}));
  if(results.result!=='PASS')process.exitCode=1;
}
