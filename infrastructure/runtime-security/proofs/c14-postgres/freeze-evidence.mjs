import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir,readFile,writeFile,readdir } from 'node:fs/promises';
import { resolve,join } from 'node:path';
import { gzipSync } from 'node:zlib';
import { inspectPostgresRuntime,runtimeIdentity,POSTGRES_C14_RULES } from '../../postgres-c14-adjudication.mjs';

const [imageId,scratchArg]=process.argv.slice(2);assert.match(imageId||'',/^sha256:[a-f0-9]{64}$/);assert.ok(scratchArg);
const scratch=resolve(scratchArg),target=resolve('infrastructure/runtime-security/proofs/c14-postgres');
const sha=x=>createHash('sha256').update(x).digest('hex'),files={};
async function preserve(source,destination,compressed=false){
  const original=await readFile(source),bytes=compressed?gzipSync(original,{level:9}):original;
  await mkdir(join(target,destination,'..'),{recursive:true});await writeFile(join(target,destination),bytes);files[destination]=sha(bytes);
}
const observed=await inspectPostgresRuntime(imageId,scratch),runtime=runtimeIdentity(observed.observed);
await writeFile(join(scratch,'observed-v6.json'),JSON.stringify(observed,null,2)+'\n');
await preserve(join(scratch,'observed-v6.json'),'observed-runtime.json.gz',true);
await preserve(join(scratch,'observed-v5.json'),'controls/observed-unpatched-v5.json.gz',true);
for(const name of ['source','candidate'])await preserve(join(scratch,'scan-v6-pair',name,'results','report.json'),'raw/'+name+'.json.gz',true);
await preserve(join(scratch,'scan-v6-pair','result.json'),'paired-scan.json');
await preserve(join(scratch,'continuity-v6','proof.json'),'continuity.json');
await preserve(join(scratch,'tcp-v6','result.json'),'tcp.json');
await preserve(join(scratch,'build-v6-metadata.json'),'build-metadata.json');
for(const name of await readdir(join(scratch,'runtime-v6','runtime','build')))
  if(name.endsWith('-check.log')||name.endsWith('-configure.log')||name.startsWith('gzip-patch-')||name==='pg-config.txt')
    await preserve(join(scratch,'runtime-v6','runtime','build',name),'upstream-tests/'+name+(name.endsWith('.log')?'.gz':''),name.endsWith('.log'));
for(const name of await readdir(join(scratch,'primary')))
  await preserve(join(scratch,'primary',name),'primary/'+name+(name.endsWith('.html')?'.gz':''),name.endsWith('.html'));
for(const name of ['runtime-files.json','upstream-components.json','upstream.cdx.json','debian-components.json'])
  await preserve(join(scratch,'runtime-v6','runtime',name),'runtime/'+name+(name==='runtime-files.json'?'.gz':''),name==='runtime-files.json');
const contract={};for(const field of ['Env','Entrypoint','Cmd','User','WorkingDir'])contract[field]=observed.inspected.Config[field]??null;
const review={schema:'otziv-postgres-c14-review-v1',status:'REVIEWED_NOT_AFFECTED',reviewedAt:new Date().toISOString(),
  validUntil:'2027-01-01T00:00:00Z',testedImage:imageId,scope:'Only listed package/version/CVE records after stopped-image byte and source verification; all raw findings retained.',
  rules:POSTGRES_C14_RULES,containerContract:contract,runtime,files};
const bytes=Buffer.from(JSON.stringify(review,null,2)+'\n');await writeFile(join(target,'review.json'),bytes);
console.log(JSON.stringify({reviewSha256:sha(bytes),payloadFiles:Object.keys(runtime.payload).length,proofFiles:Object.keys(files).length}));
