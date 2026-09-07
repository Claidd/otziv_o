import {mkdtemp,rm,chmod} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {run} from '../recovery/process.mjs';
import {assertLocalDocker} from '../recovery/drill.mjs';

// Only an owned network-none container and ephemeral public test CA. The six
// tests import the built image's actual collector/publisher and make real TLS requests.
export async function smoke(image){
  await assertLocalDocker();
  const directory=await mkdtemp(join(tmpdir(),'otziv-publisher-fixture-'));
  const name=`otziv-publisher-fixture-${randomUUID()}`,owner=randomUUID();let allocated=false;
  try{
    await run(process.env.OTZIV_TEST_OPENSSL||'openssl',['req','-x509','-newkey','rsa:2048','-nodes','-keyout',join(directory,'key.pem'),'-out',join(directory,'cert.pem'),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1']);
    // This is a disposable fixture key. Production key permission checks remain strict.
    await chmod(directory,0o755);await chmod(join(directory,'key.pem'),0o644);
    await run('docker',['create','--name',name,'--label',`com.otziv.publisher-smoke.owner=${owner}`,
      '--network','none','--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=32m,mode=1777','--cap-drop','ALL',
      '--security-opt','no-new-privileges:true','--memory','256m','--pids-limit','64',
      '--mount',`type=bind,source=${directory},target=/fixture,readonly`,
      '--mount',`type=bind,source=${fileURLToPath(new URL('./collector.test.mjs',import.meta.url))},target=/monitor/collector.test.mjs,readonly`,
      '--env','OTZIV_TEST_TLS_DIRECTORY=/fixture',image,'node','--test','/monitor/collector.test.mjs']);allocated=true;
    await run('docker',['start','--attach',name],{timeoutMs:30000});
    if((await run('docker',['inspect','--format','{{.State.ExitCode}}',name])).trim()!=='0')throw new Error('publisher_container_fixture_failed');
    return {result:'PASS',productionCollectorAndHttps:true,network:'none',nonRoot:true,tests:6};
  } finally {
    if(allocated){
      const actual=(await run('docker',['inspect','--format','{{index .Config.Labels "com.otziv.publisher-smoke.owner"}}',name])).trim();
      if(actual!==owner)throw new Error('publisher_fixture_ownership_mismatch');
      await run('docker',['rm','--force',name]);
    }
    await rm(directory,{recursive:true,force:true});
  }
}
if(process.argv[1]===fileURLToPath(import.meta.url)){
  try{if(process.argv.length!==3)throw new Error('publisher_image_required');console.log(JSON.stringify(await smoke(process.argv[2])));}
  catch(error){console.error(JSON.stringify({result:'FAIL',code:/^[a-z_]+$/.test(error.message||'')?error.message:'publisher_container_smoke_failed'}));process.exitCode=1;}
}
