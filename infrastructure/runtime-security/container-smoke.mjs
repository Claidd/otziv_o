import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { run } from '../recovery/process.mjs';
import { assertLocalDocker } from '../recovery/drill.mjs';

export function containerBounds(profile) {
  return ['--network', 'none', '--read-only', '--tmpfs', '/tmp:rw,noexec,nosuid,size=512m',
    '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--security-opt', `seccomp=${profile}`,
    '--shm-size', '256m', '--memory', '2g', '--pids-limit', '256'];
}

export async function containerSmoke(component, image) {
  if (!['worker', 'whatsapp'].includes(component)) throw new Error('runtime_component_invalid');
  await assertLocalDocker();
  const profile = fileURLToPath(new URL('./chromium-seccomp.json', import.meta.url));
  const bounds = containerBounds(profile);
  const command = component === 'worker' ? 'src/runtime-smoke.js' : 'compatibility-smoke.js';
  const owner = randomUUID();
  const label = 'com.otziv.runtime-smoke.owner';
  const fixture = `otziv-${component}-fixture-${owner}`;
  await run('docker', ['create', '--name', fixture, '--label', `${label}=${owner}`, ...bounds, image, 'node', command]);
  try {
    await run('docker', ['start', '--attach', fixture], { timeoutMs: 120_000 });
    if ((await run('docker', ['inspect', '--format', '{{.State.ExitCode}}', fixture])).trim() !== '0') throw new Error('runtime_fixture_failed');
  } finally {
    const actual = (await run('docker', ['inspect', '--format', `{{index .Config.Labels "${label}"}}`, fixture])).trim();
    if (actual !== owner) throw new Error('runtime_cleanup_ownership_mismatch');
    await run('docker', ['rm', '--force', fixture]);
  }
  if (component === 'whatsapp') {
    const name=`otziv-whatsapp-metrics-${owner}`;
    await run('docker',['create','--name',name,'--label',`${label}=${owner}`,...bounds,
      '--env','CLIENT_ID=local_metrics_fixture','--env','AUTH_PATH=/tmp/fixture-auth',
      '--env','WHATSAPP_GATEWAY_SHARED_SECRET=fixture-only-token','--env','WHATSAPP_GATEWAY_AUTH_REQUIRED=true',
      '--env','SERVER_URL=http://127.0.0.1:1',image]);
    try {
      await run('docker',['start',name]);
      const probe=`Promise.all([false,true].map(async authenticated=>{const r=await fetch('http://127.0.0.1:3000/internal/task-metrics',{headers:authenticated?{'x-otziv-internal-token':'fixture-only-token'}:{}});return {status:r.status,body:authenticated?await r.json():null};})).then(x=>console.log(JSON.stringify(x))).catch(()=>process.exit(1))`;
      let observed;const until=Date.now()+30000;
      while(Date.now()<until){try{observed=JSON.parse(await run('docker',['exec',name,'node','-e',probe],{timeoutMs:5000}));break;}catch{await new Promise(resolve=>setTimeout(resolve,250));}}
      if(observed?.[0].status!==401 || observed?.[1].status!==200 || observed[1].body.schema!=='otziv-task-metrics-v1')throw new Error('whatsapp_metrics_auth_failed');
      await run('docker',['stop','--time','330',name],{timeoutMs:340000});
      if((await run('docker',['inspect','--format','{{.State.ExitCode}}',name])).trim()!=='0')throw new Error('whatsapp_metrics_graceful_stop_failed');
    } finally {
      const actual=(await run('docker',['inspect','--format',`{{index .Config.Labels "${label}"}}`,name])).trim();
      if(actual!==owner)throw new Error('runtime_cleanup_ownership_mismatch');
      await run('docker',['rm','--force',name]);
    }
    return { result:'PASS',sandboxFixture:true,localAndRemoteLifecycle:true,authenticatedTaskMetrics:true,liveProviderLogin:'NOT_RUN' };
  }
  for (const brokenBrowser of [false, true]) {
    const name = `otziv-worker-ready-${owner}-${brokenBrowser ? 'broken' : 'good'}`;
    let allocated = false;
    try {
      await run('docker', ['run', '--detach', '--name', name, '--label', `${label}=${owner}`, ...bounds,
        '--env', 'EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED=true', '--env', 'EXTERNAL_REVIEW_WORKER_SHARED_SECRET=fixture-only-token',
        ...(brokenBrowser ? ['--env', 'CHROMIUM_EXECUTABLE_PATH=/missing-fixture-chromium'] : []), image]);
      allocated = true;
      const probe = `Promise.all(['/health','/ready'].map(async path=>{const r=await fetch('http://127.0.0.1:3097'+path);return {status:r.status,body:await r.json()};})).then(x=>console.log(JSON.stringify(x))).catch(()=>process.exit(1))`;
      const deadline = Date.now() + 90_000;
      let verified = false;
      while (Date.now() < deadline) {
        try {
          const values = JSON.parse(await run('docker', ['exec', name, 'node', '-e', probe], { timeoutMs: 10_000 }));
          if (values[0].status === 200 && values[1].status === (brokenBrowser ? 503 : 200) &&
              (!brokenBrowser || values[1].body.state === 'failed')) { verified = true; break; }
        } catch { /* Startup readiness remains false until the actual fixture completes. */ }
        await new Promise(resolve => setTimeout(resolve, 500));
      }
      if (!verified) throw new Error('runtime_readiness_contract_failed');
      const apiProbe = `Promise.all([false,true].map(async authenticated=>{const r=await fetch('http://127.0.0.1:3097/api/external-review-checks/verify',{method:'POST',headers:{'Content-Type':'application/json',...(authenticated?{'x-otziv-internal-token':'fixture-only-token'}:{})},body:'{}'});return r.status;})).then(x=>console.log(JSON.stringify(x))).catch(()=>process.exit(1))`;
      const statuses = JSON.parse(await run('docker', ['exec', name, 'node', '-e', apiProbe]));
      if (statuses[0] !== 401 || statuses[1] !== (brokenBrowser ? 503 : 400)) throw new Error('runtime_auth_or_admission_failed');
      const metricsProbe=`Promise.all([false,true].map(async authenticated=>{const r=await fetch('http://127.0.0.1:3097/api/internal/task-metrics',{headers:authenticated?{'x-otziv-internal-token':'fixture-only-token'}:{}});return {status:r.status,body:authenticated?await r.json():null};})).then(x=>console.log(JSON.stringify(x))).catch(()=>process.exit(1))`;
      const observed=JSON.parse(await run('docker',['exec',name,'node','-e',metricsProbe]));
      if(observed[0].status!==401 || observed[1].status!==200 || observed[1].body.schema!=='otziv-task-metrics-v1'
        || observed[1].body.active!==0 || observed[1].body.cleanup!==0
        || (brokenBrowser && observed[1].body.rejectedTotal.not_ready!==1))throw new Error('runtime_metrics_or_readiness_rejection_failed');
      await run('docker', ['stop', '--time', '400', name], { timeoutMs: 410_000 });
      const exitCode = (await run('docker', ['inspect', '--format', '{{.State.ExitCode}}', name])).trim();
      if (exitCode !== '0') throw new Error('runtime_graceful_drain_failed');
    } finally {
      if (allocated) {
        const actual = (await run('docker', ['inspect', '--format', `{{index .Config.Labels "${label}"}}`, name])).trim();
        if (actual !== owner) throw new Error('runtime_cleanup_ownership_mismatch');
        await run('docker', ['rm', '--force', name]);
      }
    }
  }
  return { result: 'PASS', sandboxAndOfflineOcr: true, readinessSuccessAndFailure: true, gracefulDrain: true };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    if (process.argv.length !== 4) throw new Error('usage_runtime_smoke_component_image');
    console.log(JSON.stringify(await containerSmoke(...process.argv.slice(2))));
  } catch (error) {
    console.error(JSON.stringify({ result: 'FAIL', code: /^[a-z_]+$/.test(error.message || '') ? error.message : 'runtime_smoke_failed' }));
    process.exitCode = 1;
  }
}
