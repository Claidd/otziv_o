import assert from 'node:assert/strict';
import {gunzipSync} from 'node:zlib';
import {dirname} from 'node:path';
import {hash} from './ssl-refresh.mjs';
import {coupleSslRefresh} from './ssl-refresh-activation.mjs';
const PARENT_SHA='9a772fbadbcbcd87454a419f51299a28d6246b0c214baa94c608589febe8c338';
const SERVER='opt/keycloak/lib/lib/main/com.fasterxml.jackson.core.jackson-databind-2.21.5.jar';
const CLI='opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar';
const OLD='62134a98de12d0a421cd2f5b9854c8e63f10f524b0341e66cfd31a4326f6a329';
const NEW='1290c2795e93e8a6861a6c4d9ff0d844d32f5ea178362cb2721edf5561e828b1';
const selected=p=>p.startsWith('com/fasterxml/jackson/databind/')||p.startsWith('META-INF/maven/com.fasterxml.jackson.core/jackson-databind/');
export function validateJacksonInspection(record,parent,child,parentId,childId,parentRef,childRef,commit){
 assert.equal(record.schema,'otziv-jackson-refresh-inspection-v1');assert.equal(record.result,'PASS');assert.equal(record.productionAccess,false);assert.equal(record.ownedContainersRemaining,0);assert.equal(record.publicationCommit,commit);
 assert.equal(record.images.length,2);const [before,after]=record.images;
 for(const [value,config,id,ref] of [[before,parent,parentId,parentRef],[after,child,childId,childRef]]){
  assert.equal(value.reference,ref);assert.ok([id,ref.split('@')[1]].includes(value.imageId));assert.equal(value.containerExecuted,false);
  assert.deepEqual(value.rootfs,config.rootfs.diff_ids);
  const expected=Object.fromEntries(Object.keys(value.configuration).map(k=>[k,config.config[k]??null]));assert.deepEqual(value.configuration,expected);
  assert.deepEqual(Object.keys(value.configuration).sort(),['User','WorkingDir','Entrypoint','Cmd','Env','Healthcheck','ExposedPorts','Volumes','StopSignal','Labels'].sort());
  for(const path of [SERVER,CLI])assert.equal(value.jars[path].sha256,value.inventory[path].sha256);
  const reduced=entries=>Object.fromEntries(Object.entries(entries).filter(([p])=>selected(p)).map(([p,v])=>[p,{sha256:v.sha256,size:v.size}]));
  if(value===after)assert.deepEqual(reduced(value.jars[CLI].entries),reduced(value.jars[SERVER].entries),'jackson_shaded_payload');
 }
 const configuration=structuredClone(before.configuration);configuration.Labels['com.otziv.publication.revision']=commit;assert.deepEqual(after.configuration,configuration,'jackson_launch_changed');
 assert.deepEqual(after.rootfs.slice(0,before.rootfs.length),before.rootfs);assert.ok(after.rootfs.length>before.rootfs.length);
 const changed=[...new Set([...Object.keys(before.inventory),...Object.keys(after.inventory)])].filter(p=>JSON.stringify(before.inventory[p])!==JSON.stringify(after.inventory[p])).sort();
 assert.deepEqual(changed,[CLI,SERVER].sort(),'jackson_unrelated_file_changed');assert.deepEqual(record.changedFiles,changed);
 assert.equal(before.inventory[SERVER].sha256,OLD);assert.equal(after.inventory[SERVER].sha256,NEW);
 for(const path of [SERVER,CLI])for(const key of ['mode','uid','gid','kind'])assert.equal(before.inventory[path][key],after.inventory[path][key]);
 const unrelated=x=>Object.fromEntries(Object.entries(x.jars[CLI].entries).filter(([p])=>!selected(p)));
 assert.deepEqual(unrelated(before),unrelated(after),'jackson_cli_unrelated_entry_changed');return record;
}
export async function validateJacksonRefreshActivation(publication,entry,read,validateParent){
 const parentBytes=await read('infrastructure/runtime-security/c24-parent-keycloak.json');assert.equal(hash(parentBytes),PARENT_SHA);const parent=JSON.parse(parentBytes);
 const original=JSON.parse(await read('infrastructure/runtime-security/reviewed-images.json')).images.find(x=>x.component==='keycloak');
 await validateParent(original,parent,await read('infrastructure/runtime-security/reviewed-images.json'),read);
 const value=JSON.parse(await read(entry.sslRefreshAcceptance.path));
 await coupleSslRefresh({postgresReference:value.pair.postgres.reference,postgresConfigId:value.pair.postgres.imageConfigId,requiredKeycloakReference:entry.reference,requiredKeycloakConfigId:value.imageConfigId},entry,read);
 const dir=dirname(entry.publication.path),prior=dirname(parent.publication.path);
 const pBytes=await read(prior+'/registry-amd64-config.json'),cBytes=await read(dir+'/registry-amd64-config.json');
 const raw=await read(value.runtimePath),record=JSON.parse(gunzipSync(raw));assert.ok(value.files[value.runtimePath]);
 assert.equal(record.executedScriptSha256,value.executedSources['infrastructure/runtime-security/jackson-refresh-inspection.py']);assert.equal(record.underlyingInspectorSha256,value.executedSources['infrastructure/runtime-security/ssl-refresh-inspection.py']);
 validateJacksonInspection(record,JSON.parse(pBytes),JSON.parse(cBytes),'sha256:'+hash(pBytes),'sha256:'+hash(cBytes),parent.reference,entry.reference,entry.commit);
 assert.equal(publication.imageId,value.imageConfigId);assert.equal(publication.imageId,'sha256:'+hash(cBytes));
 const bytes=await read(dir+'/vulnerabilities.json'),scan=JSON.parse(bytes);assert.equal(scan.Metadata.ImageID,publication.imageId);assert.deepEqual(scan.Metadata.ImageConfig.rootfs,JSON.parse(cBytes).rootfs);
 assert.ok(value.files[dir+'/vulnerabilities.json']);assert.equal(publication.security.high,0);assert.equal(publication.security.critical,0);
 assert.ok(publication.knownRuntimeDependencies.policy.includes('databind-2.21.7'));return value;
}
