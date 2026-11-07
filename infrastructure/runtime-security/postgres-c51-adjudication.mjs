// C51 is an official Debian zlib1g-only overlay of the already accepted C36
// PostgreSQL image. The complete stopped-container filesystem is compared with
// the frozen C36 publication before any of its source-level security decisions
// are inherited. The zlib findings are fixed by version and never adjudicated.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {isDeepStrictEqual} from 'node:util';
import {run} from '../recovery/process.mjs';
import {loadPostgresC16Proof,POSTGRES_C16_RULES,POSTGRES_C16_REVIEW_SHA256} from './postgres-c16-adjudication.mjs';
import {matchingPostgresFindings,POSTGRES_C14_RULES} from './postgres-c14-adjudication.mjs';
import {POSTGRES_LIBXML_PYTHON_RULE,matchRules} from './postgres-c15-adjudication.mjs';
import {LIBXML_SEPTEMBER_RULES,reviewLibxmlSeptemberFindings,validateLibxmlSeptemberReceipt} from './postgres-libxml-september-review.mjs';
import {effectivePostgresSummary as c23Summary} from './ssl-refresh.mjs';

export const PARENT_REFERENCE='ghcr.io/claidd/otziv-security@sha256:26663e414ba75586834873d6a43d8c1a105c12f58804e7fb961581d61a6dbdd5';
export const PARENT_CONFIG_ID='sha256:6cca407023398603e7122cb588dbed2078730b34774c9b8366191526a47149fc';
export const PARENT_ACCEPTANCE_SHA256='f8597176266ada3446e4d28267b13de1413b02b10fc38f50dedbc1eb35722393';
export const PARENT_RUNTIME_SHA256='907eb2a314e560068d7f2443961d55a800cf9f071d2afc526011e4ccae2ceea4';
export const PARENT_INSPECTOR_SHA256='4969ebbd33456ba2fa10e377d2ff6877dca7651334c33536ca03362f83af1e1d';
export const C51_POSTGRES_PROOF_LABEL='postgres-c51-zlib-deb13u1';
export const ZLIB_PACKAGE_SHA256='c9fe8df881852bdc983349843b4203712984c94a66a24b1d077b9c380d0ad1fa';
export const ZLIB_VERSION='1:1.3.dfsg+really1.3.1-1+deb13u1';
export const ZLIB_PREVIOUS_VERSION='1:1.3.dfsg+really1.3.1-1+b1';
export const ZLIB_DEB_SHA256='8c27c342230503b73dfde629642c3b352e87b554e06d60cf58772f236a305ceb';
export const ZLIB_ADVISORIES=['CVE-2026-76844','CVE-2026-85091'];
export const ZLIB_LIBRARY='usr/lib/x86_64-linux-gnu/libz.so.1.3.1';
export const ZLIB_LIBRARY_SHA256='6c13931760210e8b391b63a337dfe4071346c4ad6e3827694edb7b6989bb16dd';
export const OLD_ZLIB_LIBRARY_SHA256='85590dd58edf5445e18bc7193e5ebc01ac5841f1ae187e97705a662e90c6421e';
// The official package files absent from or different in C36, its complete dpkg
// control set (C36 kept only list/md5sums) and the shared status database.
export const C51_CHANGED_FILES=[
  'usr/lib/x86_64-linux-gnu/libz.so.1.3.1',
  'usr/share/doc/zlib1g/changelog.Debian.gz',
  'usr/share/doc/zlib1g/changelog.gz',
  'var/lib/dpkg/info/zlib1g:amd64.list',
  'var/lib/dpkg/info/zlib1g:amd64.md5sums',
  'var/lib/dpkg/info/zlib1g:amd64.shlibs',
  'var/lib/dpkg/info/zlib1g:amd64.symbols',
  'var/lib/dpkg/info/zlib1g:amd64.triggers',
  'var/lib/dpkg/status',
];
export const hash=value=>createHash('sha256').update(value).digest('hex');
const root=new URL('./',import.meta.url);
const sha=/^[a-f0-9]{64}$/;
const STATUS='var/lib/dpkg/status';
const PARENT_PROOF='infrastructure/runtime-security/proofs/c36-postgres/';
const PARENT_RUNTIME_PATH=PARENT_PROOF+'publication/vulnerabilities.runtime-c36.json.gz';
const fields=['User','WorkingDir','Entrypoint','Cmd','Env','Healthcheck','ExposedPorts','Volumes','StopSignal'];
const configValue=(configuration,field)=>['User','WorkingDir'].includes(field)?configuration?.[field]||'':configuration?.[field]??null;
const key=x=>x.package+'|'+x.version+'|'+x.cve;
const records=text=>text.trimEnd().split('\n\n');
const versionOf=pkg=>(pkg.Epoch?pkg.Epoch+':':'')+pkg.Version+(pkg.Release?'-'+pkg.Release:'');

function assertCapturedBound(image,label){
  assert.ok(image.captured?.[STATUS],label+'_status_missing');
  for(const [path,text]of Object.entries(image.captured)){
    assert.equal(image.inventory[path]?.kind,'file',label+'_capture_not_file');
    assert.equal(image.inventory[path]?.sha256,hash(text),label+'_capture_hash_mismatch');
  }
}

export async function loadC36PostgresParent(){
  const [acceptanceBytes,runtimeBytes,configBytes]=await Promise.all([
    readFile(new URL('proofs/c36-postgres/acceptance.json',root)),
    readFile(new URL('proofs/c36-postgres/publication/vulnerabilities.runtime-c36.json.gz',root)),
    readFile(new URL('proofs/c36-postgres/publication/registry-amd64-config.json',root)),
  ]);
  assert.equal(hash(acceptanceBytes),PARENT_ACCEPTANCE_SHA256,'postgres_c51_parent_acceptance_changed');
  assert.equal(hash(runtimeBytes),PARENT_RUNTIME_SHA256,'postgres_c51_parent_runtime_changed');
  assert.equal('sha256:'+hash(configBytes),PARENT_CONFIG_ID,'postgres_c51_parent_config_changed');
  const acceptance=JSON.parse(acceptanceBytes),runtime=JSON.parse(gunzipSync(runtimeBytes)),config=JSON.parse(configBytes);
  assert.equal(acceptance.schema,'otziv-postgres-c36-acceptance-v1','postgres_c51_parent_acceptance_schema');
  assert.equal(acceptance.result,'PASS','postgres_c51_parent_unaccepted');
  assert.equal(acceptance.reference,PARENT_REFERENCE,'postgres_c51_parent_reference');
  assert.equal(acceptance.imageConfigId,PARENT_CONFIG_ID,'postgres_c51_parent_image');
  assert.equal(acceptance.runtimePath,PARENT_RUNTIME_PATH,'postgres_c51_parent_runtime_path');
  assert.equal(acceptance.files?.[PARENT_RUNTIME_PATH],PARENT_RUNTIME_SHA256,'postgres_c51_parent_proof_unbound');
  assert.equal(acceptance.files?.[PARENT_PROOF+'publication/registry-amd64-config.json'],PARENT_CONFIG_ID.slice(7),
    'postgres_c51_parent_config_unbound');
  assert.equal(runtime.schema,'otziv-postgres-c36-inspection-v1','postgres_c51_parent_runtime_schema');
  assert.equal(runtime.result,'PASS','postgres_c51_parent_runtime_failed');
  assert.equal(runtime.component,'postgres','postgres_c51_parent_runtime_component');
  assert.equal(runtime.productionAccess,false,'postgres_c51_parent_runtime_production');
  assert.equal(runtime.parentInspectorSha256,PARENT_INSPECTOR_SHA256,'postgres_c51_parent_runtime_inspector');
  const parent=runtime.candidate;
  assert.equal(parent?.imageId,PARENT_CONFIG_ID,'postgres_c51_parent_runtime_image');
  assert.equal(parent.containerExecuted,false,'postgres_c51_parent_runtime_executed');
  assert.deepEqual(parent.rootfs,config.rootfs.diff_ids,'postgres_c51_parent_layers');
  assert.equal(parent.inventory[ZLIB_LIBRARY]?.sha256,OLD_ZLIB_LIBRARY_SHA256,'postgres_c51_old_zlib_changed');
  assertCapturedBound(parent,'postgres_c51_parent');
  const zlib=records(parent.captured[STATUS]).filter(x=>x.startsWith('Package: zlib1g\n'));
  assert.equal(zlib.length,1,'postgres_c51_parent_zlib_missing');
  assert.match(zlib[0],/^Version: 1:1\.3\.dfsg\+really1\.3\.1-1\+b1$/m,'postgres_c51_parent_zlib_version');
  return {acceptance,runtime,config,parent};
}

export async function loadC51ZlibPackage(){
  const bytes=await readFile(new URL('c51-postgres-zlib-package.json',root));
  assert.equal(hash(bytes),ZLIB_PACKAGE_SHA256,'postgres_c51_zlib_inventory_changed');
  const value=JSON.parse(bytes);
  assert.equal(value.schema,'otziv-official-debian-package-v1','postgres_c51_zlib_inventory_schema');
  assert.equal(value.component,'postgres','postgres_c51_zlib_inventory_component');
  assert.equal(value.parent,PARENT_REFERENCE,'postgres_c51_zlib_inventory_parent');
  assert.deepEqual(value.advisories,ZLIB_ADVISORIES,'postgres_c51_zlib_inventory_advisories');
  assert.equal(value.package,'zlib1g','postgres_c51_zlib_inventory_package');
  assert.equal(value.version,ZLIB_VERSION,'postgres_c51_zlib_inventory_version');
  assert.equal(value.previousVersion,ZLIB_PREVIOUS_VERSION,'postgres_c51_zlib_inventory_previous');
  assert.equal(value.architecture,'amd64','postgres_c51_zlib_inventory_architecture');
  assert.equal(value.dpkgInfoName,'zlib1g:amd64','postgres_c51_zlib_inventory_info');
  assert.equal(value.sha256,ZLIB_DEB_SHA256,'postgres_c51_zlib_inventory_deb');
  assert.equal(value.signedArchiveIndex?.sha256,ZLIB_DEB_SHA256,'postgres_c51_zlib_inventory_archive');
  assert.equal(value.signedArchiveIndex.size,value.bytes,'postgres_c51_zlib_inventory_size');
  assert.deepEqual(value.conffiles,[],'postgres_c51_zlib_inventory_conffiles');
  assert.equal(value.payload?.[ZLIB_LIBRARY]?.sha256,ZLIB_LIBRARY_SHA256,'postgres_c51_zlib_inventory_library');
  for(const [path,row]of Object.entries(value.payload))
    assert.ok(['directory','file','symlink'].includes(row.kind)&&(path==='usr'||/^usr\/(?:lib|share)(?:\/|$)/.test(path)),
      'postgres_c51_zlib_inventory_payload');
  const info='var/lib/dpkg/info/'+value.dpkgInfoName+'.';
  const control=Object.fromEntries(Object.entries(value.control).filter(([name])=>name!=='control')
    .map(([name,row])=>[info+name,row.sha256]));
  assert.deepEqual(value.dpkgInfoFiles,{...control,[info+'list']:hash(value.dpkgList)},'postgres_c51_zlib_inventory_dpkg');
  assert.ok(value.dpkgStatusRecord.startsWith('Package: zlib1g\n')&&
    value.dpkgStatusRecord.endsWith('\nStatus: install ok installed')&&
    value.dpkgStatusRecord.includes('\nVersion: '+ZLIB_VERSION+'\n'),'postgres_c51_zlib_inventory_status');
  return value;
}

// Every C36 path stays byte-identical except the official zlib1g files and its
// own dpkg records; each of those must equal the authenticated Debian package.
export function expectedC51Files(zlib){
  const owned=new Map(Object.entries(zlib.payload).filter(([,row])=>row.kind!=='directory'));
  for(const [path,digest]of Object.entries(zlib.dpkgInfoFiles))
    owned.set(path,{mode:0o644,uid:0,gid:0,kind:'file',sha256:digest});
  return owned;
}

export function validatePostgresC51Runtime(record,parent,candidateConfig,report,publicationCommit,zlib){
  assert.equal(record.schema,'otziv-postgres-c51-inspection-v1','postgres_c51_inspection_schema');
  assert.equal(record.result,'PASS','postgres_c51_inspection_failed');
  assert.equal(record.component,'postgres','postgres_c51_component');
  assert.equal(record.productionAccess,false,'postgres_c51_production_access');
  assert.equal(record.ownedContainersRemaining,0,'postgres_c51_inspection_cleanup');
  assert.equal(record.parentInspectorSha256,PARENT_INSPECTOR_SHA256,'postgres_c51_parent_inspector_changed');
  const child=record.candidate;
  assert.equal(child.containerExecuted,false,'postgres_c51_candidate_executed');
  assert.ok([report.Metadata.ImageID,record.candidate.reference].includes(child.imageId),
    'postgres_c51_observed_image');
  assert.deepEqual(child.rootfs,candidateConfig.rootfs.diff_ids,'postgres_c51_observed_layers');
  assert.deepEqual(report.Metadata.ImageConfig.rootfs.diff_ids,candidateConfig.rootfs.diff_ids,'postgres_c51_scan_layers');
  assert.deepEqual(child.rootfs.slice(0,parent.rootfs.length),parent.rootfs,'postgres_c51_parent_layers_changed');
  assert.ok(child.rootfs.length>parent.rootfs.length,'postgres_c51_overlay_missing');
  for(const field of fields)assert.deepEqual(configValue(child.configuration,field),configValue(parent.configuration,field),
    'postgres_c51_launch_contract_changed:'+field);
  for(const field of [...fields,'Labels'])assert.deepEqual(configValue(child.configuration,field),configValue(candidateConfig.config,field),
    'postgres_c51_config_changed_after_scan:'+field);
  const labels={...parent.configuration.Labels,
    'com.otziv.publication.revision':publicationCommit,
    'com.otziv.zlib.package':'zlib1g='+ZLIB_VERSION,
    'com.otziv.zlib.deb-sha256':ZLIB_DEB_SHA256,
    'com.otziv.security.proof':C51_POSTGRES_PROOF_LABEL};
  assert.deepEqual(child.configuration.Labels,labels,'postgres_c51_labels_changed');
  assertCapturedBound(child,'postgres_c51_candidate');
  assert.deepEqual(Object.keys(child.captured).sort(),Object.keys(parent.captured).sort(),'postgres_c51_package_lists_changed');
  const before=records(parent.captured[STATUS]),after=records(child.captured[STATUS]);
  assert.equal(child.captured[STATUS],after.join('\n\n')+'\n','postgres_c51_status_format');
  assert.equal(after.length,before.length,'postgres_c51_package_set_changed');
  const index=before.findIndex(x=>x.startsWith('Package: zlib1g\n'));
  assert.ok(index>=0&&before.filter(x=>x.startsWith('Package: zlib1g\n')).length===1,'postgres_c51_parent_zlib_missing');
  assert.match(before[index],/^Version: 1:1\.3\.dfsg\+really1\.3\.1-1\+b1$/m,'postgres_c51_parent_zlib_version');
  assert.deepEqual(after.filter((_,i)=>i!==index),before.filter((_,i)=>i!==index),'postgres_c51_unrelated_package_changed');
  assert.equal(after[index],zlib.dpkgStatusRecord,'postgres_c51_zlib_status_record');
  const oldFiles=parent.inventory,newFiles=child.inventory,owned=expectedC51Files(zlib);
  for(const [path,row]of owned)assert.deepEqual(newFiles[path],row,'postgres_c51_zlib_file_mismatch:'+path);
  const changed=[...new Set([...Object.keys(oldFiles),...Object.keys(newFiles)])]
    .filter(path=>!isDeepStrictEqual(oldFiles[path],newFiles[path])).sort();
  for(const path of changed)assert.ok(path===STATUS||owned.has(path),'postgres_c51_unrelated_files_changed:'+path);
  assert.deepEqual(changed,C51_CHANGED_FILES,'postgres_c51_unrelated_files_changed');
  assert.equal(oldFiles[ZLIB_LIBRARY]?.sha256,OLD_ZLIB_LIBRARY_SHA256,'postgres_c51_old_zlib_changed');
  assert.equal(newFiles[ZLIB_LIBRARY]?.sha256,ZLIB_LIBRARY_SHA256,'postgres_c51_zlib_library');
  for(const path of Object.keys(child.captured).filter(path=>path!==STATUS&&path!=='var/lib/dpkg/info/zlib1g:amd64.list'))
    assert.equal(child.captured[path],parent.captured[path],'postgres_c51_package_list_changed');
  assert.equal(child.captured['var/lib/dpkg/info/zlib1g:amd64.list'],zlib.dpkgList,'postgres_c51_zlib_list');
  return {librarySha256:newFiles[ZLIB_LIBRARY].sha256,changedFiles:changed};
}

export function validateC51ScannerPackages(report,review){
  const os=(report.Results||[]).filter(x=>x.Class==='os-pkgs'&&x.Type==='debian');
  assert.equal(os.length,1,'postgres_c51_scanner_coverage');
  for(const source of review.runtime.sources){
    const packages=(os[0].Packages||[]).filter(x=>x.Name===source.package);
    assert.equal(packages.length,1,'postgres_c51_scanner_package_missing');
    assert.equal(packages[0].Version+(packages[0].Release?'-'+packages[0].Release:''),source.packageVersion,
      'postgres_c51_scanner_version_changed');
    assert.equal(packages[0].SrcName,source.sourcePackage,'postgres_c51_scanner_source_changed');
    assert.equal(packages[0].AnalyzedBy,'dpkg','postgres_c51_scanner_analyzer_changed');
  }
  const zlib=(os[0].Packages||[]).filter(x=>x.Name==='zlib1g');
  assert.equal(zlib.length,1,'postgres_c51_scanner_zlib_missing');
  assert.equal(versionOf(zlib[0]),ZLIB_VERSION,'postgres_c51_scanner_zlib_version');
  assert.equal(zlib[0].SrcName,'zlib','postgres_c51_scanner_zlib_source');
  assert.equal(zlib[0].AnalyzedBy,'dpkg','postgres_c51_scanner_zlib_analyzer');
  // The fixed version must clear both advisories in the scanner itself. Any
  // other zlib1g finding stays unadjudicated and is counted by the verdict.
  const reported=(report.Results||[]).flatMap(x=>x.Vulnerabilities||[])
    .filter(x=>ZLIB_ADVISORIES.includes(x.VulnerabilityID));
  assert.equal(reported.length,0,'postgres_c51_zlib_advisory_reported');
  return {package:'zlib1g',version:ZLIB_VERSION,previousVersion:ZLIB_PREVIOUS_VERSION,debSha256:ZLIB_DEB_SHA256,
    inventorySha256:ZLIB_PACKAGE_SHA256,librarySha256:ZLIB_LIBRARY_SHA256,advisories:ZLIB_ADVISORIES,
    scannerVersion:versionOf(zlib[0]),advisoryFindings:0};
}

// The exact C36 derivation of the inherited PostgreSQL source decisions.
export async function deriveC51Decisions(report,review){
  const python=matchRules(report,review,[POSTGRES_LIBXML_PYTHON_RULE]);
  const september=await reviewLibxmlSeptemberFindings(report,review);
  return {september:september??null,decisions:[...matchingPostgresFindings(report,review),
    ...matchRules(report,review,POSTGRES_C16_RULES),...python,...(september?.decisions||[])]};
}

export async function adjudicatePostgresC51Image(report,bytes,id,scratch){
  const base={schema:'otziv-postgres-c51-adjudication-v1',rawReportSha256:hash(bytes),
    imageConfigId:report.Metadata?.ImageID,immutableImageId:id,rawReportModified:false,decisions:[]};
  try{
    assert.equal(report.Metadata?.ImageConfig?.config?.Labels?.['com.otziv.security.proof'],C51_POSTGRES_PROOF_LABEL,
      'postgres_c51_proof_label');
    const {parent}=await loadC36PostgresParent(),zlib=await loadC51ZlibPackage();
    const candidate=report.Metadata.ImageConfig,revision=candidate.config.Labels['com.otziv.publication.revision'];
    assert.match(revision||'',/^[a-f0-9]{40}$/,'postgres_c51_publication_revision');
    assert.match(id||'',/^sha256:[a-f0-9]{64}$/,'postgres_c51_immutable_image');
    const output=join(scratch,'postgres-runtime-c51.json.gz');
    await run(process.platform==='win32'?'python':'python3',['-B',fileURLToPath(new URL('postgres-c51-inspection.py',root)),
      '--candidate',id,'--output',output,'--temporary-directory',scratch],{timeoutMs:600000});
    const runtimeBytes=await readFile(output),record=JSON.parse(gunzipSync(runtimeBytes));
    assert.equal(record.candidate?.reference,id,'postgres_c51_inspected_reference');
    assert.equal(record.executedScriptSha256,hash(await readFile(new URL('postgres-c51-inspection.py',root))),
      'postgres_c51_inspector_changed');
    const delta=validatePostgresC51Runtime(record,parent,candidate,report,revision,zlib);
    const {review,reviewSha256}=await loadPostgresC16Proof();
    const zlibReceipt=validateC51ScannerPackages(report,review);
    const {september,decisions}=await deriveC51Decisions(report,review);
    return {...base,status:'EXACT_RUNTIME_VERIFIED',reviewSha256,validUntil:review.validUntil,
      parentReference:PARENT_REFERENCE,parentConfigId:PARENT_CONFIG_ID,parentAcceptanceSha256:PARENT_ACCEPTANCE_SHA256,
      parentRuntimeSha256:PARENT_RUNTIME_SHA256,runtimeProofSha256:hash(runtimeBytes),
      inspectorSha256:record.executedScriptSha256,changedFiles:delta.changedFiles,zlib:zlibReceipt,
      inspectedBinaryExecuted:false,libxmlSeptember:september,decisions};
  }catch(error){return {...base,status:'REJECTED',reason:error.message?.match(/^(postgres_c51_[a-z_]+)/)?.[1]||
    'postgres_c51_validation_failed'};}
}

export function effectivePostgresC51Summary(summary,receipt){
  if(receipt?.status!=='EXACT_RUNTIME_VERIFIED')return {...summary,result:'FAIL'};
  assert.equal(receipt.schema,'otziv-postgres-c51-adjudication-v1','postgres_c51_summary_schema');
  assert.equal(receipt.parentReference,PARENT_REFERENCE,'postgres_c51_summary_parent');
  assert.equal(receipt.parentConfigId,PARENT_CONFIG_ID,'postgres_c51_summary_parent_id');
  assert.equal(receipt.parentAcceptanceSha256,PARENT_ACCEPTANCE_SHA256,'postgres_c51_summary_parent_acceptance');
  assert.equal(receipt.parentRuntimeSha256,PARENT_RUNTIME_SHA256,'postgres_c51_summary_parent_runtime');
  assert.equal(receipt.reviewSha256,POSTGRES_C16_REVIEW_SHA256,'postgres_c51_summary_review');
  assert.equal(receipt.validUntil,'2027-01-01T00:00:00Z','postgres_c51_summary_expiry');
  assert.match(receipt.runtimeProofSha256||'',sha,'postgres_c51_summary_proof');
  assert.deepEqual(receipt.changedFiles,C51_CHANGED_FILES,'postgres_c51_summary_scope');
  assert.deepEqual(receipt.zlib,{package:'zlib1g',version:ZLIB_VERSION,previousVersion:ZLIB_PREVIOUS_VERSION,
    debSha256:ZLIB_DEB_SHA256,inventorySha256:ZLIB_PACKAGE_SHA256,librarySha256:ZLIB_LIBRARY_SHA256,
    advisories:ZLIB_ADVISORIES,scannerVersion:ZLIB_VERSION,advisoryFindings:0},'postgres_c51_summary_zlib');
  assert.equal(receipt.inspectedBinaryExecuted,false,'postgres_c51_summary_image_executed');
  const rules=[...POSTGRES_C14_RULES,...POSTGRES_C16_RULES,POSTGRES_LIBXML_PYTHON_RULE,...LIBXML_SEPTEMBER_RULES];
  assert.ok(new Set(receipt.decisions.map(key)).size===receipt.decisions.length&&receipt.decisions.every(x=>
    x.state==='not_affected'&&rules.some(rule=>key(rule)===key(x)&&rule.reason===x.justification)),
  'postgres_c51_summary_decisions_changed');
  validateLibxmlSeptemberReceipt(receipt,receipt.decisions.filter(x=>LIBXML_SEPTEMBER_RULES.some(rule=>rule.cve===x.cve)));
  return c23Summary(summary,{...receipt,schema:'otziv-postgres-c23-adjudication-v1',
    sslRefresh:{runtimeProofSha256:PARENT_RUNTIME_SHA256}});
}
