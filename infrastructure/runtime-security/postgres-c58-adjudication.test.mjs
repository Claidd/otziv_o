import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
import {createHash} from 'node:crypto';
import {validateC58Runtime,c58Receipt,effectiveC58Summary} from './postgres-c58-adjudication.mjs';
import {summarizeReport} from './scan.mjs';
const root=new URL('./',import.meta.url),hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const parent=JSON.parse(gunzipSync(await readFile(new URL('proofs/c54-postgres/publication/vulnerabilities.runtime-c54.json.gz',root))));
const original=JSON.parse(await readFile(new URL('proofs/c54-postgres/publication/vulnerabilities.json',root)));
const inspector=hash(await readFile(new URL('postgres-c58-inspection.py',root)));
const revision='a'.repeat(40),id='sha256:'+'b'.repeat(64);
function fixture(){
 const runtime=structuredClone(parent),report=structuredClone(original);
 runtime.schema='otziv-postgres-c58-inspection-v1';runtime.inspectorSha256=inspector;
 runtime.inspectedBinaryExecuted=false;
 runtime.gosuBuildInfo='/tmp/gosu: go1.27.4\n\tpath\tgithub.com/tianon/gosu\n\tbuild\tCGO_ENABLED=0\n\tbuild\tGOOS=linux\n\tbuild\tGOARCH=amd64\n\tbuild\t-trimpath=true\n';
 const child=runtime.candidate;child.reference=id;child.imageId=id;child.rootfs.push('sha256:'+'c'.repeat(64));
 child.inventory['usr/local/bin/gosu'].sha256='d'.repeat(64);
 Object.assign(child.configuration.Labels,{'com.otziv.publication.revision':revision,'com.otziv.security.proof':'postgres-c58-gosu-go1274','com.otziv.gosu.go':'1.27.4'});
 report.Metadata.ImageID=id;report.Metadata.ImageConfig.config=structuredClone(child.configuration);
 report.Metadata.ImageConfig.rootfs.diff_ids=[...child.rootfs];
 report.Results=report.Results.filter(result=>result.Type!=='gobinary');
 report.Results.push({Class:'lang-pkgs',Type:'gobinary',Target:'usr/local/bin/gosu',Packages:[{Name:'stdlib',Version:'v1.27.4'}],Vulnerabilities:[]});
 return {runtime,report};
}
test('binary-only Go refresh retains exact accepted PostgreSQL and Kerberos runtime',async()=>{
 const {runtime,report}=fixture();await validateC58Runtime(runtime,report,revision);
});
for(const [name,change] of [
 ['server file',r=>r.candidate.inventory['usr/local/pgsql/bin/postgres'].sha256='0'.repeat(64)],
 ['removed file',r=>delete r.candidate.inventory['usr/local/bin/gosu']],
 ['package database',r=>r.candidate.captured['var/lib/dpkg/status']+='\n'],
 ['entrypoint',r=>r.candidate.configuration.Entrypoint=['/other']],
 ['unpatched Go',r=>r.gosuBuildInfo=r.gosuBuildInfo.replace('go1.27.4','go1.27.3')],
 ['executed candidate',r=>r.candidate.containerExecuted=true],
 ['executed binary',r=>r.inspectedBinaryExecuted=true],
 ['privileged binary',r=>r.candidate.inventory['usr/local/bin/gosu'].mode=0o4755],
 ['foreign inspector',r=>r.inspectorSha256='0'.repeat(64)],
])test('reject changed '+name,async()=>{
 const {runtime,report}=fixture();change(runtime);await assert.rejects(validateC58Runtime(runtime,report,revision));
});
test('scanner independently must see fixed Go',async()=>{
 const {runtime,report}=fixture();report.Results.at(-1).Packages[0].Version='v1.27.3';
 await assert.rejects(validateC58Runtime(runtime,report,revision));
});
test('new vulnerabilities never inherit a source exception',async()=>{
 const {runtime,report}=fixture();report.Results.at(-1).Vulnerabilities=[{VulnerabilityID:'CVE-2099-12345',PkgName:'stdlib',InstalledVersion:'v1.27.4',FixedVersion:'1.27.5',Severity:'HIGH'}];
 const {gzipSync}=await import('node:zlib');
 const bytes=Buffer.from(JSON.stringify(report));
 const receipt=await c58Receipt(report,bytes,id,gzipSync(Buffer.from(JSON.stringify(runtime))),revision);
 const summary=effectiveC58Summary(summarizeReport(report),receipt);
 assert.equal(summary.result,'FAIL');assert.equal(summary.effectiveBlockingFixedHighOrCritical,1);
});
