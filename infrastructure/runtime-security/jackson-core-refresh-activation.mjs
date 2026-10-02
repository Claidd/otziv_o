import assert from 'node:assert/strict';
import {gunzipSync} from 'node:zlib';
import {dirname} from 'node:path';
import {hash} from './ssl-refresh.mjs';
import {coupleSslRefresh} from './ssl-refresh-activation.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
import {buildTriage} from './triage-report.mjs';
import {C26_POLICY} from './keycloak-runtime-dependencies.mjs';
const PARENT_SHA='fa733265fbc587e0a3d8da4d33dc039f8455ea792ed0a200e4d3a465a06b52a0';
const SERVER='opt/keycloak/lib/lib/main/com.fasterxml.jackson.core.jackson-core-2.21.5.jar';
const CLI='opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar';
const OLD='36111c3a4372cd5c2be6f4ec44a050382487920f3fc38ca3015c9c1678bd7c56';
const NEW='8c5623b98f32d5f7e1287ff61ed50e08ab7b11381e991fff89327faabba42261';
const selected=p=>/^(?:META-INF\/versions\/[0-9]+\/)?com\/fasterxml\/jackson\/core\//.test(p)||p.startsWith('META-INF/maven/com.fasterxml.jackson.core/jackson-core/')||p==='META-INF/services/com.fasterxml.jackson.core.JsonFactory';
export function validateJacksonCoreInspection(record,parent,child,parentId,childId,parentRef,childRef,commit){
 assert.equal(record.schema,'otziv-jackson-core-refresh-inspection-v1');assert.equal(record.result,'PASS');assert.equal(record.productionAccess,false);assert.equal(record.ownedContainersRemaining,0);assert.equal(record.publicationCommit,commit);
 assert.equal(record.images.length,2);const [before,after]=record.images;
 for(const [value,config,id,ref] of [[before,parent,parentId,parentRef],[after,child,childId,childRef]]){
  assert.equal(value.reference,ref);assert.ok([id,ref.split('@')[1]].includes(value.imageId));assert.equal(value.containerExecuted,false);
  assert.deepEqual(value.rootfs,config.rootfs.diff_ids);
  const expected=Object.fromEntries(Object.keys(value.configuration).map(k=>[k,config.config[k]??null]));assert.deepEqual(value.configuration,expected);
  assert.deepEqual(Object.keys(value.configuration).sort(),['User','WorkingDir','Entrypoint','Cmd','Env','Healthcheck','ExposedPorts','Volumes','StopSignal','Labels'].sort());
  for(const path of [SERVER,CLI])assert.equal(value.jars[path].sha256,value.inventory[path].sha256);
  const reduced=entries=>Object.fromEntries(Object.entries(entries).filter(([p])=>selected(p)).map(([p,v])=>[p,{sha256:v.sha256,size:v.size}]));
  assert.ok(Object.keys(reduced(value.jars[CLI].entries)).length,'jackson_shaded_payload_missing');
  assert.deepEqual(reduced(value.jars[CLI].entries),reduced(value.jars[SERVER].entries),'jackson_shaded_payload');
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
export async function validateJacksonCoreRefreshActivation(publication,entry,read,validateParent){
 const parentBytes=await read('infrastructure/runtime-security/c26-parent-keycloak.json');assert.equal(hash(parentBytes),PARENT_SHA);const parent=JSON.parse(parentBytes);
 const original=JSON.parse(await read('infrastructure/runtime-security/reviewed-images.json')).images.find(x=>x.component==='keycloak');
 await validateParent(original,parent,await read('infrastructure/runtime-security/reviewed-images.json'),read);
 const value=JSON.parse(await read(entry.sslRefreshAcceptance.path));
 await coupleSslRefresh({postgresReference:value.pair.postgres.reference,postgresConfigId:value.pair.postgres.imageConfigId,requiredKeycloakReference:entry.reference,requiredKeycloakConfigId:value.imageConfigId},entry,read);
 const dir=dirname(entry.publication.path),prior=dirname(parent.publication.path);
 const pBytes=await read(prior+'/registry-amd64-config.json'),cBytes=await read(dir+'/registry-amd64-config.json');
 const raw=await read(value.runtimePath),record=JSON.parse(gunzipSync(raw));assert.ok(value.files[value.runtimePath]);
 for(const [field,path] of [['executedScriptSha256','infrastructure/runtime-security/jackson-core-refresh-inspection.py'],
  ['underlyingInspectorSha256','infrastructure/runtime-security/ssl-refresh-inspection.py'],
  ['patchScriptSha256','infrastructure/runtime-security/builds/c26-keycloak/patch_cli.py']]){
  assert.match(record[field]||'',/^[a-f0-9]{64}$/,'jackson_core_source_missing:'+field);
  assert.match(value.executedSources[path]||'',/^[a-f0-9]{64}$/,'jackson_core_source_binding_missing:'+field);
  assert.equal(record[field],value.executedSources[path],'jackson_core_source_binding_changed:'+field);
 }
 validateJacksonCoreInspection(record,JSON.parse(pBytes),JSON.parse(cBytes),'sha256:'+hash(pBytes),'sha256:'+hash(cBytes),parent.reference,entry.reference,entry.commit);
 assert.equal(publication.imageId,value.imageConfigId);assert.equal(publication.imageId,'sha256:'+hash(cBytes));
 const bytes=await read(dir+'/vulnerabilities.json'),scan=JSON.parse(bytes);assert.equal(scan.Metadata.ImageID,publication.imageId);assert.deepEqual(scan.Metadata.ImageConfig.rootfs,JSON.parse(cBytes).rootfs);
 assert.ok(value.files[dir+'/vulnerabilities.json']);
 const receiptPath=dir+'/vulnerabilities.adjudications.json',triagePath=dir+'/vulnerabilities.triage.json';
 assert.ok(value.files[receiptPath]&&value.files[triagePath]);
 const receipt=JSON.parse(await read(receiptPath));
 assert.equal(receipt.rawReportSha256,hash(bytes));assert.equal(receipt.imageConfigId,publication.imageId);
 assert.equal(receipt.rawReportModified,false);assert.equal(receipt.status,'NOT_APPLICABLE');assert.deepEqual(receipt.decisions,[]);
 assert.deepEqual(JSON.parse(await read(triagePath)),buildTriage(scan,bytes));
 const summary=summarizeReport(scan);assert.equal(summary.high+summary.critical,0,'jackson_raw_security_findings');
 assert.deepEqual(publication.security,{...combinedScanSummary(summary,{grafana:receipt,alloy:receipt.alloy,postgres:receipt.postgres}),scannerImage:TRIVY_IMAGE});
 assert.equal(publication.knownRuntimeDependencies.policy,C26_POLICY);return value;
}
