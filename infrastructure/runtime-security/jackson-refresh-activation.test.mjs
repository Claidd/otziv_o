import test from 'node:test';
import assert from 'node:assert/strict';
import {gunzipSync} from 'node:zlib';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {hash} from './ssl-refresh.mjs';
import {createEvidenceReader,validateActivation} from './reviewed-image-defaults.mjs';
import {validateJacksonInspection,validateJacksonRefreshActivation} from './jackson-refresh-activation.mjs';
import {buildTriage} from './triage-report.mjs';
const read=await createEvidenceReader(resolve(dirname(fileURLToPath(import.meta.url)),'../..'));
const entry=JSON.parse(await read('infrastructure/runtime-security/c26-parent-keycloak.json'));
const parent=JSON.parse(await read('infrastructure/runtime-security/c24-parent-keycloak.json'));
const value=JSON.parse(await read(entry.sslRefreshAcceptance.path));
const publication=JSON.parse(await read(entry.publication.path));
const directory=dirname(entry.publication.path);
const pBytes=await read(dirname(parent.publication.path)+'/registry-amd64-config.json');
const cBytes=await read(directory+'/registry-amd64-config.json');
const runtime=JSON.parse(gunzipSync(await read(value.runtimePath)));
const inspect=r=>validateJacksonInspection(r,JSON.parse(pBytes),JSON.parse(cBytes),'sha256:'+hash(pBytes),'sha256:'+hash(cBytes),parent.reference,entry.reference,entry.commit);
test('published Jackson patch retains all unrelated files and binds real restore, login and rollback proofs',async()=>{
 assert.equal(inspect(runtime).result,'PASS');
 await validateJacksonRefreshActivation(publication,entry,read,validateActivation);
});
for(const [name,mutate,pattern] of [
 ['application file replacement',r=>{r.images[1].inventory['opt/keycloak/conf/keycloak.conf'].sha256='0'.repeat(64)},/jackson_unrelated_file_changed/],
 ['launch argument change',r=>{r.images[1].configuration.Cmd=['start-dev']},/jackson_launch_changed|deep-equal/],
 ['unrelated CLI permissions change',r=>{r.images[1].jars['opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar'].entries['META-INF/MANIFEST.MF'].mode=123},/jackson_cli_unrelated_entry_changed/],
 ['old shaded Jackson class',r=>{const entries=r.images[1].jars['opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar'].entries;const name=Object.keys(entries).find(x=>x.startsWith('com/fasterxml/jackson/databind/')&&x.endsWith('.class'));entries[name].sha256='0'.repeat(64)},/jackson_shaded_payload/]
])test('patch inspection rejects '+name,()=>{const changed=structuredClone(runtime);mutate(changed);assert.throws(()=>inspect(changed),pattern);});
test('recomputed evidence hashes and a claimed zero summary cannot hide a new HIGH finding',async()=>{
 const changedEntry=structuredClone(entry),accepted=structuredClone(value),map=new Map();
 const scan=JSON.parse(await read(directory+'/vulnerabilities.json'));
 scan.Results[0].Vulnerabilities=[{VulnerabilityID:'CVE-2099-99999',PkgName:'proof-fixture',InstalledVersion:'1',FixedVersion:'2',Severity:'HIGH'}];
 const raw=Buffer.from(JSON.stringify(scan));map.set(directory+'/vulnerabilities.json',raw);
 const receipt=JSON.parse(await read(directory+'/vulnerabilities.adjudications.json'));receipt.rawReportSha256=hash(raw);
 map.set(directory+'/vulnerabilities.adjudications.json',Buffer.from(JSON.stringify(receipt)));
 map.set(directory+'/vulnerabilities.triage.json',Buffer.from(JSON.stringify(buildTriage(scan,raw))));
 for(const [path,bytes] of map)accepted.files[path]=hash(bytes);
 const bytes=Buffer.from(JSON.stringify(accepted));map.set(entry.sslRefreshAcceptance.path,bytes);
 changedEntry.sslRefreshAcceptance.sha256=hash(bytes);changedEntry.migrationAcceptance=changedEntry.sslRefreshAcceptance;
 await assert.rejects(validateJacksonRefreshActivation(publication,changedEntry,p=>map.get(p)||read(p),validateActivation),/jackson_raw_security_findings/);
});
