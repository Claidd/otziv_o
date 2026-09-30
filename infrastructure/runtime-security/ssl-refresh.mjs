// Reuse source adjudications only after proving a parent-layer-preserving,
// OpenSSL-only overlay. Historical reviews and their scan reports stay immutable.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {run} from '../recovery/process.mjs';
import {loadAlloyProof,matchingAlloyFindings,adjudicateAlloyImage as previousAlloy} from './alloy-daemon-adjudication.mjs';
import {inspectReviewedGoBinary} from './go-binary-inspection.mjs';
import {loadPostgresC16Proof,POSTGRES_C16_REVIEW_SHA256,POSTGRES_C16_RULES} from './postgres-c16-adjudication.mjs';
import {POSTGRES_C14_RULES,inspectPostgresRuntime,runtimeIdentity,matchingPostgresFindings} from './postgres-c14-adjudication.mjs';
import {POSTGRES_LIBXML_PYTHON_RULE,loadPostgresLibxmlProof,matchRules} from './postgres-c15-adjudication.mjs';
import {LIBXML_SEPTEMBER_RULES,reviewLibxmlSeptemberFindings,validateLibxmlSeptemberReceipt} from './postgres-libxml-september-review.mjs';
import {adjudicatePostgresImage as previousPostgres,effectivePostgresSummary as previousSummary} from './postgres-c17-adjudication.mjs';

export const hash=x=>createHash('sha256').update(x).digest('hex');
export const REFRESH_PACKAGES={postgres:{libssl3t64:'3.5.7-1~deb13u3',openssl:'3.5.7-1~deb13u3','openssl-provider-legacy':'3.5.7-1~deb13u3'},
  keycloak:{libssl3:'3.0.2-0ubuntu1.30',openssl:'3.0.2-0ubuntu1.30'},alloy:{libssl3t64:'3.0.13-0ubuntu3.16',openssl:'3.0.13-0ubuntu3.16'}};
const metadata=new Set(['etc/ld.so.cache','var/cache/ldconfig/aux-cache','var/lib/apt/extended_states','var/lib/dpkg/status',
  'var/lib/dpkg/status-old','var/log/apt/history.log','var/log/apt/term.log','var/log/dpkg.log','var/log/alternatives.log',
  'var/lib/dpkg/lock','var/lib/dpkg/lock-frontend','var/cache/debconf/templates.dat-old','var/log/apt/eipp.log.xz']);
const fields=['User','WorkingDir','Entrypoint','Cmd','Env','Healthcheck','ExposedPorts','Volumes','StopSignal','Labels'];
const root=new URL('./',import.meta.url);
export async function parentActivation(component){
  const record=JSON.parse(await readFile(new URL('c23-parent-activations.json',root)));
  const entry=record.images.find(x=>x.component===component);assert.ok(entry,'ssl_refresh_parent_missing');return entry;
}
export async function parentConfig(component){
  const entry=await parentActivation(component),path=entry.publication.path.replace(/publication\.json$/,'registry-amd64-config.json');
  const bytes=await readFile(new URL('../../'+path,root));return {entry,config:JSON.parse(bytes),imageId:'sha256:'+hash(bytes)};
}
function packages(status){
  const result={};for(const block of status.trim().split('\n\n')){
    const data={};let key;for(const line of block.split('\n')){
      if(line.startsWith(' ')&&key)data[key]+='\n'+line;
      else if(line.includes(':')){const i=line.indexOf(':');key=line.slice(0,i);data[key]=line.slice(i+1).trimStart();}
    }
    if(data.Package){assert.ok(!result[data.Package],'ssl_refresh_duplicate_package');result[data.Package]=data;}
  }return result;
}
const omit=(object,excluded)=>Object.fromEntries(Object.entries(object).filter(([key])=>!excluded.has(key)));
export function validateSslInspection(record,component,parent,candidate,parentId,candidateId,identity){
  assert.equal(record.schema,'otziv-ssl-refresh-inspection-v1','ssl_refresh_schema');
  assert.equal(record.result,'PASS','ssl_refresh_inspection_failed');assert.equal(record.component,component,'ssl_refresh_component');
  assert.equal(record.productionAccess,false,'ssl_refresh_production_inspection');assert.equal(record.ownedContainersRemaining,0,'ssl_refresh_cleanup');
  assert.deepEqual(record.packages,REFRESH_PACKAGES[component],'ssl_refresh_expected_versions');
  assert.equal(record.images?.length,2,'ssl_refresh_image_pair');const [before,after]=record.images;
  assert.equal(before.reference,identity.parentReference,'ssl_refresh_parent_reference');
  const parentDigest=identity.parentReference.split('@')[1];
  const candidateDigest=identity.candidateReference.includes('@')?identity.candidateReference.split('@')[1]:identity.candidateReference;
  assert.match(parentDigest||'',/^sha256:[a-f0-9]{64}$/,'ssl_refresh_immutable_parent');
  assert.match(candidateDigest||'',/^sha256:[a-f0-9]{64}$/,'ssl_refresh_immutable_candidate');
  for(const [observed,config,id,digest]of [[before,parent,parentId,parentDigest],[after,candidate,candidateId,candidateDigest]]){
    assert.ok([id,digest].includes(observed.imageId),'ssl_refresh_image_id');assert.equal(observed.containerExecuted,false,'ssl_refresh_inspected_image_executed');
    assert.deepEqual(observed.rootfs,config.rootfs.diff_ids,'ssl_refresh_rootfs_binding');
    const value=(data,key)=>['User','WorkingDir'].includes(key)?data[key]||'':data[key]??null;
    for(const field of fields)assert.deepEqual(value(observed.configuration,field),value(config.config,field),'ssl_refresh_config_binding');
  }
  const expectedConfiguration=structuredClone(before.configuration);
  if(identity.publicationCommit){
    assert.match(identity.publicationCommit,/^[a-f0-9]{40}$/,'ssl_refresh_publication_commit');
    assert.equal(record.publicationCommit,identity.publicationCommit,'ssl_refresh_inspected_publication_commit');
    assert.equal(after.configuration.Labels['com.otziv.publication.source'],'https://github.com/Claidd/otziv_o','ssl_refresh_publication_source');
    expectedConfiguration.Labels['com.otziv.publication.revision']=identity.publicationCommit;
  }
  assert.deepEqual(expectedConfiguration,after.configuration,'ssl_refresh_launch_contract_changed');
  assert.deepEqual(after.rootfs.slice(0,before.rootfs.length),before.rootfs,'ssl_refresh_parent_layers_changed');
  assert.ok(after.rootfs.length>before.rootfs.length,'ssl_refresh_missing_overlay');
  const expected=new Set(Object.keys(REFRESH_PACKAGES[component]));
  for(const image of [before,after])for(const [path,text]of Object.entries(image.captured)){
    assert.equal(image.inventory[path]?.kind,'file','ssl_refresh_capture_not_file');
    assert.equal(image.inventory[path]?.sha256,hash(text),'ssl_refresh_capture_hash_mismatch');
  }
  const [oldPkgs,newPkgs]=[before,after].map(x=>packages(x.captured['var/lib/dpkg/status']));
  assert.deepEqual(omit(oldPkgs,expected),omit(newPkgs,expected),'ssl_refresh_unrelated_package_changed');
  for(const pkg of expected){
    assert.equal(newPkgs[pkg]?.Version,REFRESH_PACKAGES[component][pkg],'ssl_refresh_version');
    assert.equal(newPkgs[pkg]?.Status,'install ok installed','ssl_refresh_package_state');
    assert.notEqual(oldPkgs[pkg]?.Version,newPkgs[pkg].Version,'ssl_refresh_package_not_updated');
  }
  const owned=new Set();for(const image of [before,after])for(const [path,text]of Object.entries(image.captured)){
    if([...expected].some(pkg=>path===`var/lib/dpkg/info/${pkg}.list`||path===`var/lib/dpkg/info/${pkg}:amd64.list`))
      for(const member of text.split('\n'))owned.add(member.replace(/^\//,'').replace(/\/$/,''));
  }
  const allowed=path=>metadata.has(path)||[...expected].some(pkg=>path.startsWith(`var/lib/dpkg/info/${pkg}.`)||path.startsWith(`var/lib/dpkg/info/${pkg}:amd64.`))||
    ['var/lib/dpkg/triggers/','var/lib/apt/lists/','var/cache/apt/'].some(prefix=>path.startsWith(prefix))||
    owned.has(path)&&!['usr/local/','opt/','bin/'].some(prefix=>path.startsWith(prefix))&&path!=='usr/bin/alloy';
  const changed=[...new Set([...Object.keys(before.inventory),...Object.keys(after.inventory)])].filter(path=>
    JSON.stringify(before.inventory[path])!==JSON.stringify(after.inventory[path])).sort();
  assert.deepEqual(changed,record.changedFiles,'ssl_refresh_changed_file_receipt');
  for(const path of changed)assert.ok(allowed(path),'ssl_refresh_unrelated_file_changed:'+path);
  return {before,after,changed};
}
export async function inspectSslRefresh(report,id,scratch,component){
  const {entry,config:parent,imageId:parentId}=await parentConfig(component);
  if(report.Metadata?.ImageID===parentId)return null;
  const candidate=report.Metadata?.ImageConfig;
  if(JSON.stringify(candidate?.rootfs?.diff_ids?.slice(0,parent.rootfs.diff_ids.length))!==JSON.stringify(parent.rootfs.diff_ids))return null;
  // Buildx uses a separate image store. A fresh CI runner has pulled the child
  // for scanning, but has not pulled the parent into Docker's inspection store.
  await run('docker',['pull','--platform','linux/amd64',entry.reference],{timeoutMs:300000});
  const output=join(scratch,component+'-runtime-refresh.json.gz');
  await run(process.platform==='win32'?'python':'python3',['-B',fileURLToPath(new URL('ssl-refresh-inspection.py',root)),
    '--component',component,'--parent',entry.reference,'--candidate',id,'--output',output,'--temporary-directory',scratch,
    '--publication-commit',report.Metadata.ImageConfig.config.Labels['com.otziv.publication.revision']],{timeoutMs:600000});
  const bytes=await readFile(output),record=JSON.parse(gunzipSync(bytes));
  const observed=validateSslInspection(record,component,parent,candidate,parentId,report.Metadata.ImageID,
    {parentReference:entry.reference,candidateReference:id,publicationCommit:report.Metadata.ImageConfig.config.Labels['com.otziv.publication.revision']});
  assert.equal(record.executedScriptSha256,hash(await readFile(new URL('ssl-refresh-inspection.py',root))),'ssl_refresh_inspector_changed');
  return {...observed,proof:{component,parentConfigId:parentId,parentReference:entry.reference,runtimeProofSha256:hash(bytes)}};
}
export async function adjudicateAlloyImage(report,bytes,id,scratch){
  if(report.Metadata?.ImageConfig?.config?.Labels?.['org.opencontainers.image.source']!=='https://github.com/grafana/alloy')return previousAlloy(report,bytes,id,scratch);
  try{
    const refresh=await inspectSslRefresh(report,id,scratch,'alloy');if(!refresh)return previousAlloy(report,bytes,id,scratch);
    const {review,reviewSha256,closure}=await loadAlloyProof(new Date(),refresh.proof.parentConfigId);
    for(const image of [refresh.before,refresh.after])assert.equal(image.inventory[review.binary.path]?.sha256,review.binary.sha256,'ssl_refresh_alloy_binary_changed');
    const decisions=matchingAlloyFindings(report,{...review,imageConfigId:report.Metadata.ImageID});
    const observed=await inspectReviewedGoBinary(report,id,scratch,review);
    return {schema:'otziv-alloy-adjudication-v1',status:'EXACT_BINARY_AFFECTED_CODE_ABSENT',rawReportSha256:hash(bytes),
      imageConfigId:report.Metadata.ImageID,immutableImageId:id,rawReportModified:false,reviewSha256,validUntil:review.validUntil,
      ...observed,closure,module:review.module,decisions,sslRefresh:refresh.proof};
  }catch(error){return {schema:'otziv-alloy-adjudication-v1',status:'REJECTED',rawReportSha256:hash(bytes),
    imageConfigId:report.Metadata?.ImageID,immutableImageId:id,rawReportModified:false,decisions:[],reason:'ssl_refresh_alloy_validation_failed'};}
}
export async function adjudicatePostgresImage(report,bytes,id,scratch){
  if(report.Metadata?.ImageConfig?.config?.Labels?.['com.otziv.reviewed-component']!=='postgres')return previousPostgres(report,bytes,id,scratch);
  try{
    const refresh=await inspectSslRefresh(report,id,scratch,'postgres');if(!refresh)return previousPostgres(report,bytes,id,scratch);
    const {review,reviewSha256}=await loadPostgresC16Proof();
    const {observed,inspected}=await inspectPostgresRuntime(id,scratch);
    assert.deepEqual(inspected.RootFS.Layers,report.Metadata.ImageConfig.rootfs.diff_ids,'ssl_refresh_postgres_scan_binding');
    const current=runtimeIdentity(observed),excluded=new Set(Object.keys(REFRESH_PACKAGES.postgres));
    assert.deepEqual(current.sources,review.runtime.sources,'ssl_refresh_postgres_sources_changed');
    for(const key of ['pgConfig','gzipPatch'])assert.deepEqual(current[key],review.runtime[key],'ssl_refresh_postgres_build_changed');
    // Keep complete path validation. Differences are restricted to the exact
    // authenticated OpenSSL-owned changes already checked independently above.
    const approvedPaths=new Set(refresh.changed);
    assert.deepEqual(current.paths.filter(path=>!approvedPaths.has(path)),review.runtime.paths.filter(path=>!approvedPaths.has(path)),
      'ssl_refresh_postgres_unrelated_path_changed');
    assert.deepEqual(current.packages.filter(x=>!excluded.has(x.package)),review.runtime.packages.filter(x=>!excluded.has(x.package)),
      'ssl_refresh_postgres_unrelated_package');
    const hasPython=(report.Results||[]).some(r=>(r.Vulnerabilities||[]).some(x=>x.VulnerabilityID===POSTGRES_LIBXML_PYTHON_RULE.cve));
    let libxml=null;if(hasPython){
      const proof=await loadPostgresLibxmlProof();assert.ok(!Object.keys(observed.inventory).some(path=>/python|libxml2mod/i.test(path)),'ssl_refresh_libxml_python_present');
      assert.equal(current.sources.find(x=>x.name==='libxml2')?.sha256,proof.review.sourceArchiveSha256,'ssl_refresh_libxml_source_changed');
      libxml={reviewSha256:proof.reviewSha256,decisions:matchRules(report,review,[POSTGRES_LIBXML_PYTHON_RULE])};
    }
    const libxmlSeptember=await reviewLibxmlSeptemberFindings(report,review);
    return {schema:'otziv-postgres-c23-adjudication-v1',status:'EXACT_RUNTIME_VERIFIED',rawReportSha256:hash(bytes),
      imageConfigId:report.Metadata.ImageID,immutableImageId:id,rawReportModified:false,reviewSha256,validUntil:review.validUntil,
      inspectedBinaryExecuted:false,sslRefresh:refresh.proof,...(libxml?{libxml}:{}),...(libxmlSeptember?{libxmlSeptember}:{}),
      decisions:[...matchingPostgresFindings(report,review),...matchRules(report,review,POSTGRES_C16_RULES),...(libxml?.decisions||[]),...(libxmlSeptember?.decisions||[])]};
  }catch(error){return {schema:'otziv-postgres-c23-adjudication-v1',status:'REJECTED',rawReportSha256:hash(bytes),
    imageConfigId:report.Metadata?.ImageID,immutableImageId:id,rawReportModified:false,decisions:[],
    reason:error.message?.match(/^(?:ssl_refresh|postgres)_[a-z_]+/)?.[0]||'ssl_refresh_postgres_validation_failed'};}
}
export function effectivePostgresSummary(summary,receipt){
  if(receipt?.schema!=='otziv-postgres-c23-adjudication-v1'||receipt.status!=='EXACT_RUNTIME_VERIFIED')return previousSummary(summary,receipt);
  assert.equal(receipt.reviewSha256,POSTGRES_C16_REVIEW_SHA256,'ssl_refresh_postgres_review');
  assert.equal(receipt.validUntil,'2027-01-01T00:00:00Z','ssl_refresh_postgres_expiry');
  assert.match(receipt.sslRefresh?.runtimeProofSha256||'',/^[a-f0-9]{64}$/,'ssl_refresh_runtime_proof_missing');
  const rules=[...POSTGRES_C14_RULES,...POSTGRES_C16_RULES,POSTGRES_LIBXML_PYTHON_RULE,...LIBXML_SEPTEMBER_RULES],key=x=>x.package+'|'+x.version+'|'+x.cve;
  assert.ok(new Set(receipt.decisions.map(key)).size===receipt.decisions.length&&receipt.decisions.every(x=>x.state==='not_affected'&&
    rules.some(r=>key(r)===key(x)&&r.reason===x.justification)),'ssl_refresh_postgres_scope_changed');
  validateLibxmlSeptemberReceipt(receipt,receipt.decisions.filter(x=>LIBXML_SEPTEMBER_RULES.some(r=>r.cve===x.cve)));
  const fixed=receipt.decisions.filter(x=>x.fixedHighOrCritical).length,unfixed=receipt.decisions.filter(x=>x.unfixedHighOrCritical).length;
  const effectiveFixed=(summary.effectiveBlockingFixedHighOrCritical??summary.blockingFixedHighOrCritical)-fixed;
  const effectiveUnfixed=(summary.effectiveUnfixedHighOrCritical??summary.unfixedHighOrCritical)-unfixed;
  assert.ok(effectiveFixed>=0&&effectiveUnfixed>=0,'ssl_refresh_postgres_count_invalid');
  return {...summary,adjudicatedPostgresFixedHighOrCritical:fixed,adjudicatedPostgresUnfixedHighOrCritical:unfixed,
    effectiveBlockingFixedHighOrCritical:effectiveFixed,effectiveUnfixedHighOrCritical:effectiveUnfixed,
    unresolvedRiskReview:effectiveUnfixed?'REQUIRED':'NONE',result:effectiveFixed?'FAIL':'PASS'};
}
