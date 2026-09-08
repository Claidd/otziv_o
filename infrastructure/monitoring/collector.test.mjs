import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,writeFile,readFile,rm,chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync,execFile } from 'node:child_process';
import { promisify } from 'node:util';
import https from 'node:https';
import { collectSignals,validateCollector } from './collect.mjs';
import { createPublisher } from './publisher.mjs';
import { evaluateSignals } from './watch.mjs';

const now=Date.now(), time=new Date(now).toISOString();
const env={BACKEND_MONITOR_TOKEN:'fixture-backend-'.repeat(4),WORKER_MONITOR_TOKEN:'fixture-worker-'.repeat(4),MONITOR_SIGNALS_TOKEN:'fixture-publisher-'.repeat(4)};
const config=()=>({schema:'otziv-signals-publisher-v1',intervalMs:30000,sourceTimeoutMs:1000,maxSourceAgeSeconds:120,
  backend:{alias:'backend',enabled:true,url:'https://backend.test/api/internal/monitoring/runtime',tokenEnv:'BACKEND_MONITOR_TOKEN'},
  taskSources:[{alias:'worker',kind:'worker',enabled:true,url:'https://worker.test/api/internal/task-metrics',tokenEnv:'WORKER_MONITOR_TOKEN'}],
  mysqlReceiptsFile:'mysql-fixture',postgresReceiptsFile:'pg-fixture',bindAddress:'127.0.0.1',port:9444});
const backend=()=>({schema:'otziv-runtime-observed-v1',observedAt:time,requests:{state:'NO_TRAFFIC',observedAt:time,samples:0,errorRate:null,latencyP95Ms:null},
  saturation:{observedAt:time,ratio:.4},queues:['lead','performer','session_revocation','integration_outbox','workload'].map(name=>({name,state:'AVAILABLE',observedAt:time,dispatchEnabled:false,backlog:4,dead:name==='session_revocation'?null:1,unknown:['integration_outbox','workload'].includes(name)?null:2,oldestDueSeconds:10}))});
const task=()=>({schema:'otziv-task-metrics-v1',measuredAt:time,active:1,running:0,cleanup:1,limit:1,oldestCleanupSeconds:12,accepting:true,timedOutTotal:1,cancelledTotal:0,rejectedTotal:{busy:2,draining:0,not_ready:0}});
const options=(b=backend(),t=task())=>({now,readRecords:async()=>[],request:async(url,request)=>{
  if(url.includes('backend')){assert.equal(request.authHeader,'X-Otziv-Monitor-Token');return b;}
  assert.equal(request.authHeader,'X-Otziv-Internal-Token');return t;
}});
test('collector consumes observed sources, retains queue occupancy despite disabled dispatch and never invents backup success',async()=>{
  const result=await collectSignals(config(),env,options());
  assert.equal(result.metrics.deadCount,4);assert.equal(result.metrics.unknownCount,6);assert.equal(result.metrics.oldestCleanupSeconds,12);
  assert.equal(result.metrics.errorRate,null);assert.equal(result.metricStates.errorRate,'NO_TRAFFIC');
  assert.equal(result.backups.mysql.remoteVerified,false);
  assert.equal(result.queues[0].dispatchEnabled,false);assert.equal(result.queues[0].backlog,4);
});
test('missing, stale or unknown queue source fails the whole collection instead of freshening a healthy timestamp',async()=>{
  for(const mutate of [b=>b.queues.pop(),b=>b.queues[0].state='UNAVAILABLE',b=>b.queues[0].observedAt='2000-01-01T00:00:00Z',b=>b.saturation.ratio=null,b=>b.queues[0].dead=null,b=>b.queues[2].dead=0,b=>b.queues[4].unknown=0]){
    const b=backend();mutate(b);await assert.rejects(collectSignals(config(),env,options(b)));
  }
});
test('NO_TRAFFIC is explicit and accepted only with zero observed requests and null latency/rate',async()=>{
  const b=backend();b.requests.samples=1;await assert.rejects(collectSignals(config(),env,options(b)),/request_window/);
  const signals=await collectSignals(config(),env,options());
  const thresholds={heartbeatMaxAgeSeconds:120,backupMysqlMaxAgeSeconds:100,backupPostgresMaxAgeSeconds:100};
  assert.ok(!evaluateSignals(signals,thresholds,now).includes('errorRate_signal_missing'));
  signals.observedRequests=1;assert.ok(evaluateSignals(signals,thresholds,now).includes('errorRate_signal_missing'));
});
test('config refuses unknown endpoint paths, missing secrets, unauthorised plaintext and unbounded source inventory',()=>{
  for(const change of [c=>c.backend.url='http://backend.test/api/internal/monitoring/runtime',c=>c.backend.url='https://backend.test/arbitrary',c=>c.taskSources=[],c=>c.intervalMs=0]){
    const c=config();change(c);assert.throws(()=>validateCollector(c,env));
  }
  assert.throws(()=>validateCollector(config(),{}));
});
test('explicitly disabled task contour remains metadata, never fabricated zero cleanup',async()=>{
  const c=config();c.taskSources[0].enabled=false;
  const signals=await collectSignals(c,env,options());assert.equal(signals.metrics.oldestCleanupSeconds,null);
  assert.equal(signals.metricStates.oldestCleanupSeconds,'DISABLED');assert.equal(signals.taskSources[0].state,'DISABLED');
});

test('real TLS publication requires auth, serves only cached bounded signals and fails closed after a source failure',async t=>{
  const directory=await mkdtemp(join(tmpdir(),'otziv-monitor-tls-'));t.after(()=>rm(directory,{recursive:true,force:true}));
  const key=join(directory,'key.pem'),cert=join(directory,'cert.pem');
  const openssl=process.env.OTZIV_TEST_OPENSSL || 'openssl';
  if(process.env.OTZIV_TEST_TLS_DIRECTORY){
    await writeFile(key,await readFile(join(process.env.OTZIV_TEST_TLS_DIRECTORY,'key.pem')),{mode:0o600});
    await writeFile(cert,await readFile(join(process.env.OTZIV_TEST_TLS_DIRECTORY,'cert.pem')));
  } else execFileSync(openssl,['req','-x509','-newkey','rsa:2048','-nodes','-keyout',key,'-out',cert,'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1'],{stdio:'ignore'});
  await chmod(key,0o600);
  let count=0,fail=false,hang=false,release;const signals=await collectSignals(config(),env,options());
  const publisher=await createPublisher({...config(),tlsKeyFile:key,tlsCertFile:cert},env,{collect:async()=>{count++;if(fail)throw new Error('fixture');if(hang)return new Promise(resolve=>{release=resolve;});return signals;}});
  await publisher.sample();await new Promise(resolve=>publisher.server.listen(0,'127.0.0.1',resolve));t.after(()=>publisher.close());
  const ca=await readFile(cert),port=publisher.server.address().port;
  const get=(authorization,path='/private/monitor-signals')=>new Promise((resolve,reject)=>{
    const req=https.get({hostname:'127.0.0.1',port,path,ca,headers:authorization?{Authorization:authorization}:{}},res=>{
      let text='';res.on('data',chunk=>text+=chunk);res.on('end',()=>resolve({status:res.statusCode,text}));});req.on('error',reject);
  });
  assert.equal((await get()).status,401);assert.equal((await get('Bearer wrong')).status,401);
  const ok=await get(`Bearer ${env.MONITOR_SIGNALS_TOKEN}`);assert.equal(ok.status,200);assert.equal(JSON.parse(ok.text).schema,'otziv-monitor-signals-v1');
  assert.ok(!ok.text.includes(env.MONITOR_SIGNALS_TOKEN));
  assert.equal((await get(`Bearer ${env.MONITOR_SIGNALS_TOKEN}`,'/other')).status,404);
  const previous=count;await get(`Bearer ${env.MONITOR_SIGNALS_TOKEN}`);assert.equal(count,previous);
  fail=true;await publisher.sample();assert.equal((await get(`Bearer ${env.MONITOR_SIGNALS_TOKEN}`)).status,503);
  fail=false;hang=true;const pending=publisher.sample();
  await new Promise(resolve=>setTimeout(resolve,2100));
  const singleFlight=count;await publisher.sample();assert.equal(count,singleFlight);
  release(signals);await pending;
  assert.equal((await get(`Bearer ${env.MONITOR_SIGNALS_TOKEN}`)).status,503,'late collection may not freshen the publisher');
  hang=false;await publisher.sample();assert.equal((await get(`Bearer ${env.MONITOR_SIGNALS_TOKEN}`)).status,200);

  // Exercise the production collector and production HTTPS request function in
  // a separate process with an explicitly trusted ephemeral CA, not mock fetch.
  const source=https.createServer({key:await readFile(key),cert:ca},(req,res)=>{
    const isBackend=req.url==='/api/internal/monitoring/runtime';
    const expected=isBackend?env.BACKEND_MONITOR_TOKEN:env.WORKER_MONITOR_TOKEN;
    if(req.headers[isBackend?'x-otziv-monitor-token':'x-otziv-internal-token']!==expected){res.writeHead(401).end();return;}
    res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify(isBackend?backend():task()));
  });
  await new Promise(resolve=>source.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>source.close(resolve)));
  const observed=config(),origin=`https://127.0.0.1:${source.address().port}`;
  observed.backend.url=origin+'/api/internal/monitoring/runtime';observed.taskSources[0].url=origin+'/api/internal/task-metrics';
  observed.mysqlReceiptsFile=join(directory,'mysql.json');observed.postgresReceiptsFile=join(directory,'postgres.json');
  await writeFile(observed.mysqlReceiptsFile,'[]');await writeFile(observed.postgresReceiptsFile,'[]');
  const input=join(directory,'publisher.json');await writeFile(input,JSON.stringify(observed));
  const code=`import {readFile} from 'node:fs/promises';const {collectSignals}=await import(${JSON.stringify(new URL('./collect.mjs',import.meta.url).href)});console.log(JSON.stringify(await collectSignals(JSON.parse(await readFile(process.argv[1],'utf8')))));`;
  const output=await promisify(execFile)(process.execPath,['--input-type=module','-e',code,input],{env:{...process.env,...env,NODE_EXTRA_CA_CERTS:cert},timeout:10000});
  assert.equal(JSON.parse(output.stdout).metrics.unknownCount,6);
  assert.equal(JSON.parse(output.stdout).metrics.oldestCleanupSeconds,12);
});
