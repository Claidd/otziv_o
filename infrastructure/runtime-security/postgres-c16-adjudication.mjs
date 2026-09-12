// PCRE2 source replacement. Historical C14/C15 evidence stays immutable.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
import {loadPostgresProof, runtimeIdentity, inspectPostgresRuntime,
  matchingPostgresFindings} from './postgres-c14-adjudication.mjs';
import {adjudicatePostgresImage as legacyAdjudicate, effectivePostgresSummary as legacySummary,
  loadPostgresLibxmlProof, POSTGRES_LIBXML_PYTHON_RULE, matchRules} from './postgres-c15-adjudication.mjs';

export const POSTGRES_C16_REVIEW_SHA256='c52ea7697261a4a1d3eed1540f14aa9048f44a63ff0631dd349443905e09fd67';
const PARENT_SHA='8a78fb92b557c7557568a39ce2c31ab31b2c1361558840e77459f2072397c264';
const RUNTIME_SHA='69bbe834a8f96cbe5ac9c746bd5a67833d71ea517eb0917da9340a2745a78897';
const SOURCE_SHA='b6c68fdf6f3ac31388b50aa89ff0fc49c00c987c16e7b5146491d12003f2c8ed';
const root=new URL('./proofs/c16-postgres/',import.meta.url);
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const safePath=path=>typeof path==='string'&&path.length>0&&!path.startsWith('/')&&!/[\\:]/.test(path)&&
  path.split('/').every(part=>part&&part!=='.'&&part!=='..');
export const POSTGRES_C16_RULES=['CVE-2026-86145','CVE-2026-89161'].map(cve=>({
  cve,package:'libpcre2-8-0',source:'pcre2',version:'10.48+otziv.1',reason:'fixed_upstream_source'}));
const generated=new Set(['etc/hosts','etc/hostname','etc/resolv.conf']);
// These four text build logs contain timing/parallel-make ordering. They cannot
// hide new executable bytes: runtimeIdentity includes every ELF, script and path.
const buildLogs=new Set(['build','configure','install','tests'].map(x=>'usr/local/share/otziv/build/pcre2-'+x+'.log'));
const changedPaths=new Set(['etc/ld.so.cache','usr/lib/x86_64-linux-gnu/libpcre2-8.so.0',
  'usr/lib/x86_64-linux-gnu/libpcre2-8.so.0.14.0','usr/lib/x86_64-linux-gnu/libpcre2-8.so.0.16.0',
  ...buildLogs,'usr/local/share/otziv/build/pcre2-component.json','usr/local/share/otziv/build/pcre2-features.txt',
  ...['debian-components.json','runtime-files.json','upstream-components.json','upstream.cdx.json'].map(x=>'usr/local/share/otziv/'+x),
  'usr/share/doc/libpcre2-8-0/copyright','var/lib/dpkg/info/libpcre2-8-0:amd64.list',
  'var/lib/dpkg/info/libpcre2-8-0:amd64.md5sums','var/lib/dpkg/status']);
const inventoryWithout=(inventory,exclusions)=>Object.fromEntries(Object.entries(inventory).filter(([path])=>!exclusions.has(path)));

export function validatePostgresC16Derivation(original,candidate,parent,review){
  assert.deepEqual(runtimeIdentity(original),parent.runtime,'postgres_c16_parent_runtime_changed');
  assert.deepEqual(runtimeIdentity(candidate),review.runtime,'postgres_c16_candidate_runtime_changed');
  const exclusions=new Set([...generated,...changedPaths]);
  assert.deepEqual(inventoryWithout(candidate.inventory,exclusions),inventoryWithout(original.inventory,exclusions),
    'postgres_c16_unrelated_file_changed');
  assert.deepEqual(review.containerContract,parent.containerContract,'postgres_c16_launch_contract_changed');
  const previous=parent.runtime,current=review.runtime;
  assert.deepEqual(current.sources.filter(x=>x.name!=='pcre2'),previous.sources,'postgres_c16_unrelated_source_changed');
  assert.deepEqual(current.packages.filter(x=>x.package!=='libpcre2-8-0'),previous.packages.filter(x=>x.package!=='libpcre2-8-0'),
    'postgres_c16_unrelated_package_changed');
  assert.deepEqual(current.gzipPatch,previous.gzipPatch,'postgres_c16_gzip_proof_changed');
  assert.equal(current.pgConfig,previous.pgConfig,'postgres_c16_postgres_build_changed');
  const source=current.sources.filter(x=>x.name==='pcre2');assert.equal(source.length,1,'postgres_c16_source_missing');
  assert.equal(source[0].sha256,SOURCE_SHA,'postgres_c16_source_changed');
  assert.equal(source[0].version,'10.48','postgres_c16_source_version');
  assert.equal(source[0].packageVersion,'10.48+otziv.1','postgres_c16_package_version');
}

export async function loadPostgresC16Proof(now=new Date()){
  const bytes=await readFile(new URL('review.json',root));
  assert.equal(hash(bytes),POSTGRES_C16_REVIEW_SHA256,'postgres_c16_review_changed');
  const review=JSON.parse(bytes),{review:parent,reviewSha256}=await loadPostgresProof(now);
  assert.equal(review.schema,'otziv-postgres-c16-review-v1','postgres_c16_review_schema');
  assert.equal(review.parentReviewSha256,reviewSha256,'postgres_c16_parent_review_changed');
  assert.equal(reviewSha256,PARENT_SHA,'postgres_c16_parent_anchor');
  assert.equal(review.status,'REVIEWED_FIXED_UPSTREAM','postgres_c16_review_status');
  assert.equal(review.validUntil,parent.validUntil,'postgres_c16_review_expiry');
  assert.ok(Number.isFinite(now.getTime())&&now>=new Date(review.reviewedAt)&&now<new Date(review.validUntil),
    'postgres_c16_review_expired');
  assert.deepEqual(review.rules,POSTGRES_C16_RULES,'postgres_c16_review_scope');
  assert.equal(hash(JSON.stringify(review.runtime)),RUNTIME_SHA,'postgres_c16_runtime_anchor');
  for(const [path,expected] of Object.entries(review.files)){
    assert.ok(safePath(path),'postgres_c16_proof_path');
    assert.equal(hash(await readFile(new URL(path,root))),expected,'postgres_c16_proof_changed');
  }
  const original=JSON.parse(gunzipSync(await readFile(new URL('parent-published-runtime.json.gz',root))));
  assert.equal(original.ref,'ghcr.io/claidd/otziv-security@sha256:07834abbfd80ed7183afa9096db99afc4d93b99fd51aae726e550d07ade65dbf',
    'postgres_c16_parent_reference');
  const candidate=JSON.parse(gunzipSync(await readFile(new URL('observed-runtime.json.gz',root))));
  validatePostgresC16Derivation(original.observed,candidate.observed,parent,review);
  const dfa=await readFile(new URL('upstream/src/pcre2_dfa_match.c',root),'utf8');
  assert.match(dfa,/if \(rws->next->size < requested\)\s*return PCRE2_ERROR_HEAPLIMIT;/,'postgres_c16_dfa_fix_missing');
  assert.match(dfa,/if \(mb->heap_used >= mb->heap_limit\)/,'postgres_c16_dfa_limit_fix_missing');
  const jit=await readFile(new URL('upstream/src/pcre2_jit_match_inc.h',root),'utf8');
  assert.match(jit,/match_data->memctl\.free\(\(void \*\)match_data->subject,[\s\S]*?match_data->flags &= ~PCRE2_MD_COPIED_SUBJECT;/,
    'postgres_c16_jit_fix_missing');
  const tests=await readFile(new URL('pcre2-tests.log',root),'utf8');
  for(const name of ['pcre2posix_test','pcre2_jit_test','RunTest','RunGrepTest'])
    assert.ok(tests.includes('PASS: '+name),'postgres_c16_upstream_tests_missing');
  assert.match(tests,/# FAIL:  0/,'postgres_c16_upstream_tests_failed');
  assert.match(tests,/# ERROR: 0/,'postgres_c16_upstream_tests_failed');
  return {review,reviewSha256:hash(bytes),observed:candidate.observed};
}

export function validatePostgresC16Observed(report,inspected,observed,review,expected){
  assert.equal(inspected.Config?.Labels?.['com.otziv.security.generation'],'c16-pcre2','postgres_c16_generation');
  assert.equal(report.Metadata?.ImageConfig?.config?.Labels?.['com.otziv.security.generation'],'c16-pcre2','postgres_c16_report_generation');
  // The historical C14 validator is itself part of immutable publication
  // evidence. Keep its contract intact; C16 additionally binds the new source.
  assert.equal(report.ArtifactType,'container_image','postgres_c16_report_type');
  assert.match(report.Metadata?.ImageID||'',/^sha256:[a-f0-9]{64}$/,'postgres_c16_report_config_missing');
  assert.deepEqual(report.Metadata.ImageConfig?.rootfs?.diff_ids,inspected.RootFS?.Layers,'postgres_c16_image_rootfs_mismatch');
  assert.ok(inspected.RootFS?.Layers?.length,'postgres_c16_image_rootfs_missing');
  for(const labels of [report.Metadata.ImageConfig?.config?.Labels,inspected.Config?.Labels])
    assert.equal(labels?.['com.otziv.reviewed-component'],'postgres','postgres_c16_product');
  const value=(config,field)=>['User','WorkingDir'].includes(field)?config?.[field]||'':config?.[field]??null;
  for(const field of ['Env','Entrypoint','Cmd','User','WorkingDir']){
    assert.deepEqual(value(report.Metadata.ImageConfig.config,field),value(inspected.Config,field),'postgres_c16_config_report_mismatch');
    assert.deepEqual(value(inspected.Config,field),value(review.containerContract,field),'postgres_c16_container_contract_changed');
  }
  assert.deepEqual(runtimeIdentity(observed),review.runtime,'postgres_c16_runtime_bytes_or_sources_changed');
  const sections=(report.Results||[]).filter(x=>x.Type==='debian'&&x.Class==='os-pkgs');
  assert.equal(sections.length,1,'postgres_c16_scanner_coverage_missing');
  const targets={postgresql:'/usr/local/pgsql/bin/postgres',gzip:'/usr/local/bin/gzip',
    libxml2:'/usr/local/lib/libxml2.so.16.1.4',libxslt:'/usr/local/lib/libxslt.so.1.1.45',
    pcre2:'/usr/lib/x86_64-linux-gnu/libpcre2-8.so.0.16.0'};
  for(const source of review.runtime.sources){
    const packages=(sections[0].Packages||[]).filter(x=>x.Name===source.package);
    assert.equal(packages.length,1,'postgres_c16_scanner_coverage_missing');const pkg=packages[0];
    assert.equal(pkg.Version+(pkg.Release?'-'+pkg.Release:''),source.packageVersion,'postgres_c16_scanner_version_mismatch');
    assert.equal(pkg.SrcName,source.sourcePackage,'postgres_c16_scanner_source_mismatch');
    assert.equal(pkg.AnalyzedBy,'dpkg','postgres_c16_scanner_coverage_missing');
    assert.ok(pkg.InstalledFiles?.includes(targets[source.name]),'postgres_c16_scanner_file_binding_missing');
  }
  assert.deepEqual(inventoryWithout(observed.inventory,new Set([...generated,...buildLogs])),
    inventoryWithout(expected.inventory,new Set([...generated,...buildLogs])),'postgres_c16_complete_inventory_changed');
  for(const field of ['Volumes','StopSignal','ExposedPorts'])
    assert.deepEqual(inspected.Config[field],review.additionalContainerContract[field],'postgres_c16_container_contract_changed');
  return {imageConfigId:report.Metadata.ImageID,verifiedPayloadFiles:Object.keys(review.runtime.payload).length,
    runtimeIdentitySha256:hash(JSON.stringify(review.runtime)),inspectedBinaryExecuted:false};
}

export async function adjudicatePostgresC16Observed(report,reportBytes,immutableImageId,inspected,observed,now=new Date()){
  const {review,reviewSha256,observed:expected}=await loadPostgresC16Proof(now);
  const binding=validatePostgresC16Observed(report,inspected,observed,review,expected);
  const hasLibxml=(report.Results||[]).some(x=>(x.Vulnerabilities||[]).some(f=>f.VulnerabilityID===POSTGRES_LIBXML_PYTHON_RULE.cve));
  let libxml=null;
  if(hasLibxml){
    const proof=await loadPostgresLibxmlProof(now);
    assert.ok(!Object.keys(observed.inventory).some(path=>/python|libxml2mod/i.test(path)),'postgres_c16_libxml_python_present');
    const source=review.runtime.sources.find(x=>x.name==='libxml2');
    assert.equal(source?.sha256,proof.review.sourceArchiveSha256,'postgres_c16_libxml_source_changed');
    assert.equal(source.version,proof.review.sourceVersion,'postgres_c16_libxml_version_changed');
    libxml={schema:'otziv-postgres-libxml-python-adjudication-v1',status:'EXACT_RUNTIME_VERIFIED',reviewSha256:proof.reviewSha256,
      parentReviewSha256:PARENT_SHA,imageConfigId:report.Metadata.ImageID,validUntil:proof.review.validUntil,
      decisions:matchRules(report,review,[POSTGRES_LIBXML_PYTHON_RULE])};
  }
  return {schema:'otziv-postgres-c16-adjudication-v1',status:'EXACT_RUNTIME_VERIFIED',rawReportSha256:hash(reportBytes),
    imageConfigId:report.Metadata.ImageID,immutableImageId,rawReportModified:false,reviewSha256,parentReviewSha256:PARENT_SHA,
    validUntil:review.validUntil,...binding,...(libxml?{libxml}:{}),
    decisions:[...matchingPostgresFindings(report,review),...(libxml?.decisions||[]),...matchRules(report,review,POSTGRES_C16_RULES)]};
}

export async function adjudicatePostgresImage(report,reportBytes,immutableImageId,scratch){
  if(report.Metadata?.ImageConfig?.config?.Labels?.['com.otziv.security.generation']!=='c16-pcre2')
    return legacyAdjudicate(report,reportBytes,immutableImageId,scratch);
  try{
    const {inspected,observed}=await inspectPostgresRuntime(immutableImageId,scratch);
    return await adjudicatePostgresC16Observed(report,reportBytes,immutableImageId,inspected,observed);
  }catch(error){return {schema:'otziv-postgres-c16-adjudication-v1',status:'REJECTED',rawReportSha256:hash(reportBytes),
    imageConfigId:report.Metadata?.ImageID,immutableImageId,rawReportModified:false,decisions:[],
    reason:error.message?.match(/^(postgres_[a-z_]+)(?:\n|$)/)?.[1]||'postgres_c16_proof_validation_failed'};}
}

export function effectivePostgresSummary(summary,receipt){
  if(receipt?.schema!=='otziv-postgres-c16-adjudication-v1'||receipt.status!=='EXACT_RUNTIME_VERIFIED')
    return legacySummary(summary,receipt);
  assert.equal(receipt.reviewSha256,POSTGRES_C16_REVIEW_SHA256,'postgres_c16_receipt_review');
  assert.equal(receipt.parentReviewSha256,PARENT_SHA,'postgres_c16_receipt_parent');
  assert.equal(receipt.runtimeIdentitySha256,RUNTIME_SHA,'postgres_c16_receipt_runtime');
  assert.equal(receipt.validUntil,'2027-01-01T00:00:00Z','postgres_c16_receipt_expiry');
  const extra=receipt.decisions.filter(x=>x.package==='libpcre2-8-0'),key=x=>x.package+'|'+x.version+'|'+x.cve;
  assert.ok(new Set(extra.map(key)).size===extra.length&&extra.every(x=>x.state==='not_affected'&&
    POSTGRES_C16_RULES.some(y=>key(x)===key(y)&&x.justification===y.reason)),'postgres_c16_receipt_decisions');
  const fixed=extra.filter(x=>x.fixedHighOrCritical).length,unfixed=extra.filter(x=>x.unfixedHighOrCritical).length;
  const counters={...summary,
    effectiveBlockingFixedHighOrCritical:(summary.effectiveBlockingFixedHighOrCritical??summary.blockingFixedHighOrCritical)-fixed,
    effectiveUnfixedHighOrCritical:(summary.effectiveUnfixedHighOrCritical??summary.unfixedHighOrCritical)-unfixed};
  assert.ok(counters.effectiveBlockingFixedHighOrCritical>=0&&counters.effectiveUnfixedHighOrCritical>=0,'postgres_c16_summary_counts');
  const result=legacySummary(counters,{...receipt,decisions:receipt.decisions.filter(x=>x.package!=='libpcre2-8-0')});
  return {...result,adjudicatedPostgresFixedHighOrCritical:result.adjudicatedPostgresFixedHighOrCritical+fixed,
    adjudicatedPostgresUnfixedHighOrCritical:result.adjudicatedPostgresUnfixedHighOrCritical+unfixed};
}
