import assert from 'node:assert/strict';
import { randomUUID,createHash } from 'node:crypto';
import { mkdir,readFile,readdir,writeFile } from 'node:fs/promises';
import { resolve,join } from 'node:path';
import { run } from '../../../recovery/process.mjs';
import { assertLocalDocker } from '../../../recovery/drill.mjs';

const [image,outputArg]=process.argv.slice(2);assert.match(image||'',/^[-a-zA-Z0-9_./:@]+$/);assert.ok(outputArg);
const output=resolve(outputArg);await mkdir(output,{recursive:true});assert.equal((await readdir(output)).length,0);
await assertLocalDocker();const docker=(args,options={})=>run('docker',args,options);
const owner='otziv-c14-postgres-inventory-'+randomUUID(),label='com.otziv.c14-postgres.owner';
const inspected=JSON.parse(await docker(['image','inspect',image]))[0];
const result={schema:'otziv-c14-postgres-runtime-inventory-v1',image:inspected.Id,reference:image,
  capturedAt:new Date().toISOString(),checks:[],artifacts:[]};
let created=false;
try{
  await docker(['create','--name',owner,'--label',`${label}=${owner}`,'--network','none','--read-only',
    '--cap-drop=ALL','--security-opt=no-new-privileges:true','--memory','128m','--cpus','1','--pids-limit','64',
    '--entrypoint','/bin/true',inspected.Id]);created=true;
  await docker(['cp',owner+':/usr/local/share/otziv',join(output,'runtime')]);
  await docker(['cp',owner+':/var/lib/dpkg/status',join(output,'dpkg-status')]);
  const script='set -eu; postgres --version; gzip --version; locale -a; '+
    'ldd /usr/local/pgsql/bin/postgres /usr/local/lib/libxml2.so.16 /usr/local/lib/libxslt.so.1 /usr/local/lib/libexslt.so.0; '+
    'test "$(gosu nobody id -u)" = 65534; awk "BEGIN { exit 0 }"; '+
    'find /usr /etc /var/lib/dpkg -type f -o -type l';
  const captured=await docker(['run','--rm','--name',owner+'-checks','--label',`${label}=${owner}`,
    '--network','none','--read-only','--cap-drop=ALL','--cap-add=SETUID','--cap-add=SETGID','--security-opt=no-new-privileges:true',
    '--memory','128m','--cpus','1','--pids-limit','64','--entrypoint','/bin/bash',inspected.Id,'-c',script]);
  await writeFile(join(output,'runtime-checks.txt'),captured);
  assert.ok(!captured.includes('not found'),'unresolved_runtime_library');
  assert.match(captured,/libgcrypt\.so\.20/,'original_exslt_crypto_capability_missing');
  const paths=captured.split(/\r?\n/).filter(x=>x.startsWith('/'));
  for(const pattern of [/\/(?:infocmp|mount|nsenter|systemd-homed|perl|sed)$/, /\/lib(?:mount|acl|LLVM)[^/]*\.so/,
    /\/libxml2\.so\.2(?:\.|$)/])assert.ok(!paths.some(x=>pattern.test(x)),'omitted_implementation_present:'+pattern);
  result.checks.push('no_unresolved_libraries','exslt_crypto_preserved','gosu_nonroot_works','awk_alias_works',
    'infocmp_mount_nsenter_systemd_homed_perl_sed_absent','obsolete_xml_abi_and_llvm_libmount_libacl_absent');
  const components=JSON.parse(await readFile(join(output,'runtime','upstream-components.json')));
  assert.equal(components.length,4);const status=await readFile(join(output,'dpkg-status'),'utf8');
  for(const item of components){
    const record=status.split(/\n\n/).find(x=>x.includes('Package: '+item.package+'\n'));
    assert.ok(record,'upstream_package_missing:'+item.name);assert.ok(record.includes('Version: '+(item.packageVersion||item.version)+'\n'));
    assert.ok(record.includes('Source: '+(item.sourcePackage||item.name)+'\n'));
  }
  result.checks.push('four_actual_upstream_packages_with_honest_versions_and_source_names');
  async function files(dir,prefix=''){
    for(const item of await readdir(dir,{withFileTypes:true})){
      const relative=prefix+item.name;if(item.isDirectory())await files(join(dir,item.name),relative+'/');
      else if(item.isFile()){const bytes=await readFile(join(dir,item.name));result.artifacts.push({file:relative,
        sha256:createHash('sha256').update(bytes).digest('hex'),bytes:bytes.length});}
    }
  }
  await files(output);result.result='PASS';
}catch(error){result.result='FAIL';result.error=error.message;process.exitCode=1;}
finally{
  if(created){assert.equal((await docker(['inspect','--format',`{{index .Config.Labels "${label}"}}`,owner])).trim(),owner);
    await docker(['rm','-v',owner]);}
  result.cleanup='PASS';await writeFile(join(output,'result.json'),JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify({result:result.result,checks:result.checks.length,error:result.error}));
}
