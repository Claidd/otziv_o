import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir,readFile,writeFile } from 'node:fs/promises';
import { resolve,join } from 'node:path';
import { execFileSync } from 'node:child_process';

// Primary-source capture and exact release-file binding; no exploit payloads.
const [scratchArg]=process.argv.slice(2);assert.ok(scratchArg);
const scratch=resolve(scratchArg),output=join(scratch,'primary');await mkdir(output,{recursive:true});
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const result={schema:'otziv-c14-postgres-primary-binding-v1',capturedAt:new Date().toISOString(),sources:[],checks:[]};
async function capture(name,url){
  const response=await fetch(url,{signal:AbortSignal.timeout(30000),headers:{'User-Agent':'otziv-runtime-source-review'}});
  assert.equal(response.status,200,url);const bytes=Buffer.from(await response.arrayBuffer());
  await writeFile(join(output,name),bytes);result.sources.push({file:name,url,sha256:sha(bytes)});return bytes;
}
for(const id of ['CVE-2026-6653','CVE-2026-86140','CVE-2026-41992','CVE-2025-69720','CVE-2026-16742',
  'CVE-2026-76642','CVE-2026-78408','CVE-2026-78409','CVE-2026-78410'])
  await capture(id+'.html','https://security-tracker.debian.org/tracker/'+id);
await capture('postgresql-17.11-release.html','https://www.postgresql.org/docs/17/release-17-11.html');
await capture('postgresql-CVE-2026-6473.html','https://www.postgresql.org/support/security/CVE-2026-6473/');
const xmlArchive=await readFile(join(scratch,'libxml2-2.15.4.tar.xz'));
assert.equal(sha(xmlArchive),'98087fd181d9070724f3fbc65c7377db03038eb92bd882374daff44940138821');
const commit='96498992efa48d52b0e8b83058bd88dbdaf153c1';
for(const file of ['parser.c','valid.c']){
  const original=execFileSync('tar',['-xOf',join(scratch,'libxml2-2.15.4.tar.xz'),'libxml2-2.15.4/'+file],{maxBuffer:4000000});
  const immutable=await capture('libxml2-'+file,'https://raw.githubusercontent.com/GNOME/libxml2/'+commit+'/'+file);
  assert.deepEqual(original,immutable,'release_tar_differs_from_immutable_fixed_source:'+file);
  result.checks.push({name:'libxml2_release_matches_fixed_tag_'+file,result:'PASS',sha256:sha(original),commit});
}
const gzipArchive=await readFile(join(scratch,'gzip_1.14.orig.tar.xz'));
assert.equal(sha(gzipArchive),'01a7b881bd220bfdf615f97b8718f80bdfd3f6add385b993dcf6efd14e8c0ac6');
const unlzh=execFileSync('tar',['-xOf',join(scratch,'gzip_1.14.orig.tar.xz'),'gzip-1.14/unlzh.c'],{maxBuffer:2000000});
await writeFile(join(output,'gzip-1.14-unlzh.c'),unlzh);
const start=unlzh.toString('utf8').match(/static void\s+huf_decode_start\s*\([^)]*\)\s*\{[\s\S]*?\n\}/)?.[0];assert.ok(start);
assert.ok(!start.includes('memzero'),'unexpected_original_gzip_state');
result.checks.push({name:'gzip_original_release_lacks_fix_and_requires_recorded_upstream_patches',result:'PASS',sha256:sha(unlzh)});
result.result='PASS';await writeFile(join(output,'source-binding.json'),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify({result:result.result,sources:result.sources.length,checks:result.checks.length}));
