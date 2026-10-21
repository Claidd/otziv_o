// C36 is a binary-only derivative of the already accepted C23 PostgreSQL image.
// The complete stopped-container filesystem is compared with the frozen C23
// publication before any of its source-level security decisions are inherited.
import assert from 'node:assert/strict';
import {createHash,randomUUID} from 'node:crypto';
import {readFile,mkdir,rm} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {run} from '../recovery/process.mjs';
import {loadPostgresC16Proof,POSTGRES_C16_RULES,POSTGRES_C16_REVIEW_SHA256} from './postgres-c16-adjudication.mjs';
import {matchingPostgresFindings,POSTGRES_C14_RULES} from './postgres-c14-adjudication.mjs';
import {POSTGRES_LIBXML_PYTHON_RULE,matchRules} from './postgres-c15-adjudication.mjs';
import {LIBXML_SEPTEMBER_RULES,reviewLibxmlSeptemberFindings,validateLibxmlSeptemberReceipt} from './postgres-libxml-september-review.mjs';
import {effectivePostgresSummary as c23Summary} from './ssl-refresh.mjs';

export const PARENT_REFERENCE='ghcr.io/claidd/otziv-security@sha256:c974c12a1107e314b1ba8cf65bd1fc5b6964645ced420c2151d336ee8a9a6534';
export const PARENT_CONFIG_ID='sha256:1f5c2590fadde6b449908569285f1ed91a10831d36535d5c4ed647e9a0afca6a';
export const PARENT_ACCEPTANCE_SHA256='fbcd6c86ccbff553ecc637ca80668b50d0982172c00dae9337c6041ba59cd967';
export const PARENT_RUNTIME_SHA256='60e3960cd635ddba0f33efc9c10169f9b2b9595dc49d66999bb40fa7ec6423ff';
export const GOSU_READER='golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132';
export const GOSU_SOURCE='https://github.com/tianon/gosu/tree/6456aaa0f3c854d199d0f037f068eb97515b7513';
export const GOSU_SOURCE_SHA256='33d7537d588ea49458b9509bcf4554bdf5ceacc66da71e5caa1058ea3b689c3b';
export const OLD_GOSU_SHA256='1c8ba2a839b4bf9048ebb035f7e4a682c982120d10feed65dd70e0ba250f7401';
export const OLD_GOSU_LICENSE_SHA256='cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30';
export const hash=value=>createHash('sha256').update(value).digest('hex');
const root=new URL('./',import.meta.url);
const sha=/^[a-f0-9]{64}$/;
const fields=['User','WorkingDir','Entrypoint','Cmd','Env','Healthcheck','ExposedPorts','Volumes','StopSignal'];
const configValue=(configuration,field)=>['User','WorkingDir'].includes(field)?configuration?.[field]||'':configuration?.[field]??null;
const key=x=>x.package+'|'+x.version+'|'+x.cve;

export async function loadC23PostgresParent(){
  const [acceptanceBytes,runtimeBytes,configBytes]=await Promise.all([
    readFile(new URL('proofs/c23-postgres/acceptance.json',root)),
    readFile(new URL('proofs/c23-postgres/publication/vulnerabilities.runtime-refresh.json.gz',root)),
    readFile(new URL('proofs/c23-postgres/publication/registry-amd64-config.json',root)),
  ]);
  assert.equal(hash(acceptanceBytes),PARENT_ACCEPTANCE_SHA256,'postgres_c36_parent_acceptance_changed');
  assert.equal(hash(runtimeBytes),PARENT_RUNTIME_SHA256,'postgres_c36_parent_runtime_changed');
  assert.equal('sha256:'+hash(configBytes),PARENT_CONFIG_ID,'postgres_c36_parent_config_changed');
  const acceptance=JSON.parse(acceptanceBytes),runtime=JSON.parse(gunzipSync(runtimeBytes)),config=JSON.parse(configBytes);
  assert.equal(acceptance.result,'PASS','postgres_c36_parent_unaccepted');
  assert.equal(acceptance.component,'postgres','postgres_c36_parent_component');
  assert.equal(acceptance.reference,PARENT_REFERENCE,'postgres_c36_parent_reference');
  assert.equal(acceptance.imageConfigId,PARENT_CONFIG_ID,'postgres_c36_parent_image');
  assert.equal(acceptance.files?.['infrastructure/runtime-security/proofs/c23-postgres/publication/vulnerabilities.runtime-refresh.json.gz'],PARENT_RUNTIME_SHA256,
    'postgres_c36_parent_proof_unbound');
  assert.equal(runtime.result,'PASS','postgres_c36_parent_runtime_failed');
  assert.equal(runtime.component,'postgres','postgres_c36_parent_runtime_component');
  assert.equal(runtime.images?.[1]?.imageId,PARENT_CONFIG_ID,'postgres_c36_parent_runtime_image');
  assert.deepEqual(runtime.images[1].rootfs,config.rootfs.diff_ids,'postgres_c36_parent_layers');
  assert.equal(runtime.images[1].inventory['usr/local/bin/gosu']?.sha256,OLD_GOSU_SHA256,'postgres_c36_old_gosu_changed');
  return {acceptance,runtime,config,parent:runtime.images[1]};
}

export function validatePostgresC36Runtime(record,parent,candidateConfig,report,publicationCommit){
  assert.equal(record.schema,'otziv-postgres-c36-inspection-v1','postgres_c36_inspection_schema');
  assert.equal(record.result,'PASS','postgres_c36_inspection_failed');
  assert.equal(record.component,'postgres','postgres_c36_component');
  assert.equal(record.productionAccess,false,'postgres_c36_production_access');
  assert.equal(record.ownedContainersRemaining,0,'postgres_c36_inspection_cleanup');
  assert.equal(record.parentInspectorSha256,'4969ebbd33456ba2fa10e377d2ff6877dca7651334c33536ca03362f83af1e1d',
    'postgres_c36_parent_inspector_changed');
  const child=record.candidate;
  assert.equal(child.containerExecuted,false,'postgres_c36_candidate_executed');
  assert.ok([report.Metadata.ImageID,record.candidate.reference].includes(child.imageId),
    'postgres_c36_observed_image');
  assert.deepEqual(child.rootfs,candidateConfig.rootfs.diff_ids,'postgres_c36_observed_layers');
  assert.deepEqual(report.Metadata.ImageConfig.rootfs.diff_ids,candidateConfig.rootfs.diff_ids,'postgres_c36_scan_layers');
  assert.deepEqual(child.rootfs.slice(0,parent.rootfs.length),parent.rootfs,'postgres_c36_parent_layers_changed');
  assert.ok(child.rootfs.length>parent.rootfs.length,'postgres_c36_overlay_missing');
  for(const field of fields)assert.deepEqual(configValue(child.configuration,field),configValue(parent.configuration,field),
    'postgres_c36_launch_contract_changed:'+field);
  for(const field of [...fields,'Labels'])assert.deepEqual(configValue(child.configuration,field),configValue(candidateConfig.config,field),
    'postgres_c36_config_changed_after_scan:'+field);
  const labels={...parent.configuration.Labels,
    'com.otziv.publication.revision':publicationCommit,
    'com.otziv.gosu.source-sha256':GOSU_SOURCE_SHA256,
    'com.otziv.gosu.go':'1.27.3',
    'com.otziv.security.proof':'postgres-c36-gosu-go1273'};
  assert.deepEqual(child.configuration.Labels,labels,'postgres_c36_labels_changed');
  assert.equal(child.configuration.Labels['com.otziv.gosu.source'],GOSU_SOURCE,'postgres_c36_gosu_source_changed');
  const oldFiles=parent.inventory,newFiles=child.inventory;
  assert.equal(oldFiles['usr/local/bin/gosu']?.sha256,OLD_GOSU_SHA256,'postgres_c36_old_gosu_changed');
  assert.equal(oldFiles['usr/local/share/doc/gosu/LICENSE']?.sha256,OLD_GOSU_LICENSE_SHA256,'postgres_c36_old_license_changed');
  const changed=[...new Set([...Object.keys(oldFiles),...Object.keys(newFiles)])]
    .filter(path=>JSON.stringify(oldFiles[path])!==JSON.stringify(newFiles[path])).sort();
  assert.deepEqual(changed,['usr/local/bin/gosu'],'postgres_c36_unrelated_files_changed');
  assert.equal(newFiles['usr/local/bin/gosu'].kind,'file','postgres_c36_gosu_missing');
  assert.equal(newFiles['usr/local/bin/gosu'].mode,0o755,'postgres_c36_gosu_mode');
  assert.equal(newFiles['usr/local/bin/gosu'].uid,0,'postgres_c36_gosu_owner');
  assert.equal(newFiles['usr/local/bin/gosu'].gid,0,'postgres_c36_gosu_group');
  assert.match(newFiles['usr/local/bin/gosu'].sha256,sha,'postgres_c36_gosu_hash');
  assert.notEqual(newFiles['usr/local/bin/gosu'].sha256,OLD_GOSU_SHA256,'postgres_c36_gosu_not_rebuilt');
  assert.deepEqual(child.captured,parent.captured,'postgres_c36_package_database_changed');
  return {binarySha256:newFiles['usr/local/bin/gosu'].sha256,changedFiles:changed};
}

export function validateC36GosuBuildInfo(output){
  assert.match(output,/^\/tmp\/gosu: go1\.27\.3\r?$/m,'postgres_c36_gosu_go_version');
  assert.match(output,/^\s*path\s+github\.com\/tianon\/gosu\r?$/m,'postgres_c36_gosu_module');
  assert.match(output,/^\s*build\s+CGO_ENABLED=0\r?$/m,'postgres_c36_gosu_cgo');
  assert.match(output,/^\s*build\s+GOARCH=amd64\r?$/m,'postgres_c36_gosu_arch');
  assert.match(output,/^\s*build\s+GOOS=linux\r?$/m,'postgres_c36_gosu_os');
  assert.match(output,/^\s*build\s+-trimpath=true\r?$/m,'postgres_c36_gosu_trimpath');
  return {goVersion:'go1.27.3',module:'github.com/tianon/gosu',reader:GOSU_READER,
    inspectedBinaryExecuted:false,buildInfoOutput:output};
}

async function inspectGosuBuildInfo(id,scratch,expectedHash){
  const owner=randomUUID(),directory=join(scratch,'postgres-c36-gosu-'+owner);
  let source,reader;
  await mkdir(directory);
  try{
    source=(await run('docker',['create','--name','otziv-postgres-c36-source-'+owner,'--network','none',
      '--entrypoint','/not-executed',id])).trim();
    assert.match(source,/^[a-f0-9]{64}$/,'postgres_c36_source_container');
    const binary=join(directory,'gosu');
    await run('docker',['cp',`${source}:/usr/local/bin/gosu`,binary]);
    assert.equal(hash(await readFile(binary)),expectedHash,'postgres_c36_copied_binary_changed');
    reader=(await run('docker',['create','--name','otziv-postgres-c36-reader-'+owner,'--network','none',
      '--cap-drop=ALL','--security-opt=no-new-privileges:true','--memory=512m','--pids-limit=128',
      '--env=GOTOOLCHAIN=local','--entrypoint=/bin/sh',GOSU_READER,'-ec','go version -m /tmp/gosu'])).trim();
    assert.match(reader,/^[a-f0-9]{64}$/,'postgres_c36_reader_container');
    await run('docker',['cp',binary,`${reader}:/tmp/gosu`]);
    return validateC36GosuBuildInfo(await run('docker',['start','-a',reader],{timeoutMs:120000}));
  }finally{
    if(reader)await run('docker',['rm','-f',reader]).catch(()=>{});
    if(source)await run('docker',['rm','-f',source]).catch(()=>{});
    await rm(directory,{recursive:true,force:true});
  }
}

export async function adjudicatePostgresC36Image(report,bytes,id,scratch){
  const base={schema:'otziv-postgres-c36-adjudication-v1',rawReportSha256:hash(bytes),
    imageConfigId:report.Metadata?.ImageID,immutableImageId:id,rawReportModified:false,decisions:[]};
  try{
    assert.equal(report.Metadata?.ImageConfig?.config?.Labels?.['com.otziv.security.proof'],'postgres-c36-gosu-go1273',
      'postgres_c36_proof_label');
    const {parent}=await loadC23PostgresParent();
    const candidate=report.Metadata.ImageConfig,revision=candidate.config.Labels['com.otziv.publication.revision'];
    assert.match(revision||'',/^[a-f0-9]{40}$/,'postgres_c36_publication_revision');
    assert.match(id||'',/^sha256:[a-f0-9]{64}$/,'postgres_c36_immutable_image');
    const output=join(scratch,'postgres-runtime-c36.json.gz');
    await run(process.platform==='win32'?'python':'python3',['-B',fileURLToPath(new URL('postgres-c36-inspection.py',root)),
      '--candidate',id,'--output',output,'--temporary-directory',scratch],{timeoutMs:600000});
    const runtimeBytes=await readFile(output),record=JSON.parse(gunzipSync(runtimeBytes));
    assert.equal(record.candidate?.reference,id,'postgres_c36_inspected_reference');
    assert.equal(record.executedScriptSha256,hash(await readFile(new URL('postgres-c36-inspection.py',root))),
      'postgres_c36_inspector_changed');
    const delta=validatePostgresC36Runtime(record,parent,candidate,report,revision);
    const binary=await inspectGosuBuildInfo(id,scratch,delta.binarySha256);
    const {review,reviewSha256}=await loadPostgresC16Proof();
    const os=(report.Results||[]).filter(x=>x.Class==='os-pkgs'&&x.Type==='debian');
    assert.equal(os.length,1,'postgres_c36_scanner_coverage');
    for(const source of review.runtime.sources){
      const packages=(os[0].Packages||[]).filter(x=>x.Name===source.package);
      assert.equal(packages.length,1,'postgres_c36_scanner_package_missing');
      assert.equal(packages[0].Version+(packages[0].Release?'-'+packages[0].Release:''),source.packageVersion,
        'postgres_c36_scanner_version_changed');
      assert.equal(packages[0].SrcName,source.sourcePackage,'postgres_c36_scanner_source_changed');
      assert.equal(packages[0].AnalyzedBy,'dpkg','postgres_c36_scanner_analyzer_changed');
    }
    const python=matchRules(report,review,[POSTGRES_LIBXML_PYTHON_RULE]);
    const september=await reviewLibxmlSeptemberFindings(report,review);
    const decisions=[...matchingPostgresFindings(report,review),...matchRules(report,review,POSTGRES_C16_RULES),
      ...python,...(september?.decisions||[])];
    return {...base,status:'EXACT_RUNTIME_VERIFIED',reviewSha256,validUntil:review.validUntil,
      parentReference:PARENT_REFERENCE,parentConfigId:PARENT_CONFIG_ID,parentAcceptanceSha256:PARENT_ACCEPTANCE_SHA256,
      parentRuntimeSha256:PARENT_RUNTIME_SHA256,runtimeProofSha256:hash(runtimeBytes),
      inspectorSha256:record.executedScriptSha256,changedFiles:delta.changedFiles,
      gosu:{...binary,binarySha256:delta.binarySha256,source:GOSU_SOURCE,sourceSha256:GOSU_SOURCE_SHA256},
      inspectedBinaryExecuted:false,libxmlSeptember:september??null,decisions};
  }catch(error){return {...base,status:'REJECTED',reason:error.message?.match(/^(postgres_c36_[a-z_]+)/)?.[1]||
    'postgres_c36_validation_failed'};}
}

export function effectivePostgresC36Summary(summary,receipt){
  if(receipt?.status!=='EXACT_RUNTIME_VERIFIED')return {...summary,result:'FAIL'};
  assert.equal(receipt.schema,'otziv-postgres-c36-adjudication-v1','postgres_c36_summary_schema');
  assert.equal(receipt.parentReference,PARENT_REFERENCE,'postgres_c36_summary_parent');
  assert.equal(receipt.parentConfigId,PARENT_CONFIG_ID,'postgres_c36_summary_parent_id');
  assert.equal(receipt.parentAcceptanceSha256,PARENT_ACCEPTANCE_SHA256,'postgres_c36_summary_parent_acceptance');
  assert.equal(receipt.parentRuntimeSha256,PARENT_RUNTIME_SHA256,'postgres_c36_summary_parent_runtime');
  assert.equal(receipt.reviewSha256,POSTGRES_C16_REVIEW_SHA256,'postgres_c36_summary_review');
  assert.equal(receipt.validUntil,'2027-01-01T00:00:00Z','postgres_c36_summary_expiry');
  assert.match(receipt.runtimeProofSha256||'',sha,'postgres_c36_summary_proof');
  assert.deepEqual(receipt.changedFiles,['usr/local/bin/gosu'],'postgres_c36_summary_scope');
  assert.equal(receipt.gosu?.goVersion,'go1.27.3','postgres_c36_summary_go');
  assert.equal(receipt.gosu?.module,'github.com/tianon/gosu','postgres_c36_summary_module');
  assert.equal(receipt.gosu?.reader,GOSU_READER,'postgres_c36_summary_reader');
  assert.equal(receipt.gosu?.source,GOSU_SOURCE,'postgres_c36_summary_source');
  assert.equal(receipt.gosu?.sourceSha256,GOSU_SOURCE_SHA256,'postgres_c36_summary_source_hash');
  assert.equal(validateC36GosuBuildInfo(receipt.gosu?.buildInfoOutput||'').goVersion,'go1.27.3',
    'postgres_c36_summary_build_info');
  assert.match(receipt.gosu?.binarySha256||'',sha,'postgres_c36_summary_binary');
  assert.notEqual(receipt.gosu.binarySha256,OLD_GOSU_SHA256,'postgres_c36_summary_old_binary');
  assert.equal(receipt.gosu.inspectedBinaryExecuted,false,'postgres_c36_summary_binary_executed');
  assert.equal(receipt.inspectedBinaryExecuted,false,'postgres_c36_summary_image_executed');
  const rules=[...POSTGRES_C14_RULES,...POSTGRES_C16_RULES,POSTGRES_LIBXML_PYTHON_RULE,...LIBXML_SEPTEMBER_RULES];
  assert.ok(new Set(receipt.decisions.map(key)).size===receipt.decisions.length&&receipt.decisions.every(x=>
    x.state==='not_affected'&&rules.some(rule=>key(rule)===key(x)&&rule.reason===x.justification)),
  'postgres_c36_summary_decisions_changed');
  validateLibxmlSeptemberReceipt(receipt,receipt.decisions.filter(x=>LIBXML_SEPTEMBER_RULES.some(rule=>rule.cve===x.cve)));
  return c23Summary(summary,{...receipt,schema:'otziv-postgres-c23-adjudication-v1',
    sslRefresh:{runtimeProofSha256:PARENT_RUNTIME_SHA256}});
}
