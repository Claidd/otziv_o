import test from 'node:test';
import assert from 'node:assert/strict';
import {validateJacksonCoreInspection} from './jackson-core-refresh-activation.mjs';

const server='opt/keycloak/lib/lib/main/com.fasterxml.jackson.core.jackson-core-2.21.5.jar';
const cli='opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar';
const oldSha='36111c3a4372cd5c2be6f4ec44a050382487920f3fc38ca3015c9c1678bd7c56';
const newSha='8c5623b98f32d5f7e1287ff61ed50e08ab7b11381e991fff89327faabba42261';
const commit='c'.repeat(40), parentId='sha256:'+'a'.repeat(64), childId='sha256:'+'b'.repeat(64);
const parentRef='example.test/issuer@'+parentId, childRef='example.test/issuer@'+childId;
const entry=(sha,size=100)=>({sha256:sha,size,mode:33188,compression:8});
const file=sha=>({sha256:sha,mode:420,uid:1000,gid:0,kind:'file'});
function fixture(){
  const config={User:'1000',WorkingDir:'/opt/keycloak',Entrypoint:['/opt/keycloak/bin/kc.sh'],Cmd:null,
    Env:['PATH=/usr/bin'],Healthcheck:null,ExposedPorts:null,Volumes:null,StopSignal:null,
    Labels:{'com.otziv.publication.revision':'d'.repeat(40)}};
  const images=[oldSha,newSha].map((sha,i)=>{
    const payload={
      'com/fasterxml/jackson/core/JsonFactory.class':entry(String(i).repeat(64)),
      ['META-INF/versions/17/com/fasterxml/jackson/core/io/doubleparser/v2_21_'+(i?'7':'6')+'/FastDoubleParser.class']:entry(String(i+2).repeat(64)),
      'META-INF/maven/com.fasterxml.jackson.core/jackson-core/pom.properties':entry(String(i+4).repeat(64)),
      'META-INF/services/com.fasterxml.jackson.core.JsonFactory':entry(String(i+6).repeat(64))};
    const configuration=structuredClone(config);if(i)configuration.Labels['com.otziv.publication.revision']=commit;
    const cliSha=String(i+8).repeat(64);
    return {reference:i?childRef:parentRef,imageId:i?childId:parentId,containerExecuted:false,
      rootfs:i?['base','patch']:['base'],configuration,
      inventory:{[server]:file(sha),[cli]:file(cliSha),'opt/keycloak/providers/security-generation.jar':file('e'.repeat(64))},
      jars:{[server]:{sha256:sha,entries:structuredClone(payload)},[cli]:{sha256:cliSha,entries:{...structuredClone(payload),
        'META-INF/MANIFEST.MF':entry('f'.repeat(64)),
        'org/keycloak/client/admin/cli/KcAdmMain.class':entry('e'.repeat(64)),
        'META-INF/versions/9/module-info.class':entry('d'.repeat(64))}}}};
  });
  return {schema:'otziv-jackson-core-refresh-inspection-v1',result:'PASS',productionAccess:false,
    ownedContainersRemaining:0,publicationCommit:commit,images,changedFiles:[cli,server].sort()};
}
function verify(record){
  const valid=fixture();
  const config=image=>({config:image.configuration,rootfs:{diff_ids:image.rootfs}});
  return validateJacksonCoreInspection(record,config(valid.images[0]),config(valid.images[1]),parentId,childId,parentRef,childRef,commit);
}
test('Core-only inspection accepts the official artifact and preserved provider, CLI and launch settings',()=>{
  const record=fixture();assert.equal(verify(record),record);
});
for(const [name,change] of [
  ['empty core inventories in both compared JARs',r=>{for(const image of r.images)for(const jar of [server,cli])image.jars[jar].entries={};}],
  ['old ordinary core class',r=>{r.images[1].jars[cli].entries['com/fasterxml/jackson/core/JsonFactory.class']=entry('0'.repeat(64));}],
  ['leftover old multi-release parser',r=>{r.images[1].jars[cli].entries['META-INF/versions/17/com/fasterxml/jackson/core/io/doubleparser/v2_21_6/FastDoubleParser.class']=entry('2'.repeat(64));}],
  ['stale JsonFactory service descriptor',r=>{r.images[1].jars[cli].entries['META-INF/services/com.fasterxml.jackson.core.JsonFactory']=entry('6'.repeat(64));}],
  ['changed unrelated CLI entry permissions',r=>{r.images[1].jars[cli].entries['META-INF/MANIFEST.MF'].mode=123;}],
  ['changed module descriptor',r=>{r.images[1].jars[cli].entries['META-INF/versions/9/module-info.class'].sha256='0'.repeat(64);}],
  ['changed security-generation provider',r=>{r.images[1].inventory['opt/keycloak/providers/security-generation.jar'].sha256='0'.repeat(64);}],
  ['different launch environment',r=>{r.images[1].configuration.Env=['JAVA_OPTS=unsafe'];}],
  ['unreviewed server core bytes',r=>{r.images[1].inventory[server].sha256='0'.repeat(64);r.images[1].jars[server].sha256='0'.repeat(64);}],
  ['unremoved owned container',r=>{r.ownedContainersRemaining=1;}],
  ['executed inspected container',r=>{r.images[1].containerExecuted=true;}]
])test('Core-only inspection rejects '+name,()=>{const record=fixture();change(record);assert.throws(()=>verify(record));});

// Use the published proof to exercise source binding after all presentation
// hashes are recomputed; missing values must not compare equal as undefined.
for(const [field,path] of [
 ['executedScriptSha256','infrastructure/runtime-security/jackson-core-refresh-inspection.py'],
 ['underlyingInspectorSha256','infrastructure/runtime-security/ssl-refresh-inspection.py'],
 ['patchScriptSha256','infrastructure/runtime-security/builds/c26-keycloak/patch_cli.py']
])test('activation rejects double-missing source binding '+field,async()=>{
 const {createEvidenceReader,validateActivation}=await import('./reviewed-image-defaults.mjs');
 const {validateJacksonCoreRefreshActivation}=await import('./jackson-core-refresh-activation.mjs');
 const {hash}=await import('./ssl-refresh.mjs');
 const {gzipSync,gunzipSync}=await import('node:zlib');
 const {fileURLToPath}=await import('node:url');
 const read=await createEvidenceReader(fileURLToPath(new URL('../../',import.meta.url)));
 const entry=JSON.parse(await read('infrastructure/runtime-security/reviewed-image-activations.json')).images.find(x=>x.component==='keycloak');
 const accepted=JSON.parse(await read(entry.sslRefreshAcceptance.path));
 const publication=JSON.parse(await read(entry.publication.path));
 const record=JSON.parse(gunzipSync(await read(accepted.runtimePath)));
 delete record[field];delete accepted.executedSources[path];
 const raw=gzipSync(Buffer.from(JSON.stringify(record)));accepted.files[accepted.runtimePath]=hash(raw);
 const value=Buffer.from(JSON.stringify(accepted));entry.sslRefreshAcceptance.sha256=hash(value);entry.migrationAcceptance=entry.sslRefreshAcceptance;
 const changed=async p=>p===accepted.runtimePath?raw:p===entry.sslRefreshAcceptance.path?value:read(p);
 await assert.rejects(validateJacksonCoreRefreshActivation(publication,entry,changed,validateActivation),/jackson_core_source_missing/);
});
