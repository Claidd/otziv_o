import assert from 'node:assert/strict';
import { randomUUID,randomBytes } from 'node:crypto';
import { mkdir,readdir,writeFile } from 'node:fs/promises';
import { resolve,join } from 'node:path';
import { run } from '../../../recovery/process.mjs';
import { assertLocalDocker } from '../../../recovery/drill.mjs';

const [image,outputArg]=process.argv.slice(2);assert.match(image||'',/^[-a-zA-Z0-9_./:@]+$/);assert.ok(outputArg);
const output=resolve(outputArg);await mkdir(output,{recursive:true});assert.equal((await readdir(output)).length,0);
await assertLocalDocker();const docker=(args,options={})=>run('docker',args,options);
const owner='otziv-c14-postgres-tcp-'+randomUUID(),label='com.otziv.c14-postgres.owner',password=randomBytes(32).toString('hex');
const network=owner+'-net',volume=owner+'-data',database=owner+'-db';
const resources=[];const result={schema:'otziv-c14-postgres-tcp-v1',reference:image,
  startedAt:new Date().toISOString(),resources:{databaseCpu:1,databaseMemoryMiB:512,clientMemoryMiB:128,network:'internal bridge',hostPorts:0},checks:[]};
const check=(name,condition)=>{assert.ok(condition,name);result.checks.push(name);};
async function client(statement,credential=password){
  return docker(['run','--rm','--name',owner+'-client','--label',`${label}=${owner}`,
    '--network',network,'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true',
    '--memory','128m','--cpus','1','--pids-limit','64','--env','PGPASSWORD='+credential,
    '--env','PGCONNECT_TIMEOUT=5','--entrypoint','psql',result.image,
    '-h','pg','-U','postgres','-d','fixture','-X','-A','-t','-v','ON_ERROR_STOP=1','-c',statement]);
}
try{
  result.image=JSON.parse(await docker(['image','inspect',image]))[0].Id;
  await docker(['network','create','--internal','--label',`${label}=${owner}`,network]);resources.push(['network',network]);
  await docker(['volume','create','--label',`${label}=${owner}`,volume]);resources.push(['volume',volume]);
  await docker(['create','--name',database,'--label',`${label}=${owner}`,'--network',network,'--network-alias','pg',
    '--memory','512m','--cpus','1','--pids-limit','128','--security-opt=no-new-privileges:true',
    '--mount',`type=volume,source=${volume},target=/var/lib/postgresql/data`,'--env','POSTGRES_PASSWORD='+password,
    '--env','POSTGRES_DB=fixture',result.image]);resources.push(['container',database]);await docker(['start',database]);
  const deadline=Date.now()+120000;let ready=false;
  while(Date.now()<deadline){
    assert.equal((await docker(['inspect','--format','{{.State.Running}}',database])).trim(),'true','database_exited');
    if((await docker(['exec',database,'cat','/proc/1/comm'])).trim()==='postgres'){
      try{ready=(await client('SELECT 1')).trim()==='1';if(ready)break;}catch{}
    }
    await new Promise(resolve=>setTimeout(resolve,250));
  }
  check('final_pid1_postgres_accepts_authenticated_external_tcp',ready);
  check('official_container_listen_addresses_default_preserved',(await client('SHOW listen_addresses')).trim()==='*');
  let rejected=false;try{await client('SELECT 1','incorrect-synthetic-password');}catch{rejected=true;}
  check('external_tcp_rejects_incorrect_password',rejected);
  check('external_tcp_transaction_commits',(await client("CREATE TABLE tcp_fixture(id int PRIMARY KEY); BEGIN; INSERT INTO tcp_fixture VALUES(1); COMMIT; SELECT count(*) FROM tcp_fixture;")).trim().endsWith('1'));
  await docker(['stop','--time','30',database]);await docker(['start',database]);
  const restartDeadline=Date.now()+60000;let persisted=false;
  while(Date.now()<restartDeadline){try{persisted=(await client('SELECT count(*) FROM tcp_fixture')).trim()==='1';if(persisted)break;}catch{}
    await new Promise(resolve=>setTimeout(resolve,250));}
  check('external_tcp_restart_preserves_committed_row',persisted);result.result='PASS';
}catch(error){result.result='FAIL';result.error=error.message;process.exitCode=1;}
finally{
  try{
    for(const [kind,name]of resources.reverse()){
      const args=kind==='container'?['inspect','--format',`{{index .Config.Labels "${label}"}}`,name]:[kind,'inspect','--format',`{{index .Labels "${label}"}}`,name];
      assert.equal((await docker(args)).trim(),owner);
      await docker(kind==='container'?['rm','-f','-v',name]:[kind,'rm',name]);
    }
    result.cleanup='PASS';
  }catch(error){result.result='FAIL';result.cleanup='FAIL';result.error=error.message;process.exitCode=1;}
  result.finishedAt=new Date().toISOString();await writeFile(join(output,'result.json'),JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify({result:result.result,checks:result.checks.length,cleanup:result.cleanup,error:result.error}));
}
