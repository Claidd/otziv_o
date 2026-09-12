// Supplemental C15 review. The byte-bound C14 adjudicator and publication remain unchanged.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {POSTGRES_C14_RULES,loadPostgresProof,validatePostgresObserved,matchingPostgresFindings,inspectPostgresRuntime} from './postgres-c14-adjudication.mjs';
export {loadPostgresProof,matchingPostgresFindings,inspectPostgresRuntime};
const hash=x=>createHash('sha256').update(x).digest('hex');
const labels=report=>report.Metadata?.ImageConfig?.config?.Labels||{};
const key=x=>x.package+'|'+x.version+'|'+x.cve;
const safePath=value=>typeof value==='string'&&value.length<4096&&!value.startsWith('/')&&!value.includes('\\')&&!value.split('/').includes('..');
const REVIEW_SHA256='8a78fb92b557c7557568a39ce2c31ab31b2c1361558840e77459f2072397c264';
// A later CVE review supplements, but never rewrites, the C14 publication proof.
const LIBXML_REVIEW_SHA256='ef3d2b811cb6db96215318467d6a5defed8bd6ac1e211f491405fc30cc2dc983';
export const POSTGRES_LIBXML_PYTHON_RULE={cve:'CVE-2026-74860',package:'libxml2-16',source:'libxml2',version:'2.15.4',reason:'vulnerable_python_bindings_absent'};
export function matchRules(report,review,rules){
  const decisions=[];
  for(const [resultIndex,result]of(report.Results||[]).entries()){
    if(result.Class!=='os-pkgs'||result.Type!=='debian')continue;
    for(const [findingIndex,finding]of(result.Vulnerabilities||[]).entries()){
      const rule=rules.find(x=>x.cve===finding.VulnerabilityID&&x.package===finding.PkgName&&x.version===finding.InstalledVersion);
      if(!rule||finding.DataSource?.ID!=='debian')continue;
      const packages=(result.Packages||[]).filter(x=>x.Name===rule.package);
      const pkg=packages[0];if(packages.length!==1||pkg.SrcName!==rule.source||pkg.AnalyzedBy!=='dpkg')continue;
      const version=(pkg.Epoch?pkg.Epoch+':':'')+pkg.Version+(pkg.Release?'-'+pkg.Release:'');
      const sourceVersion=(pkg.SrcEpoch?pkg.SrcEpoch+':':'')+pkg.SrcVersion+(pkg.SrcRelease?'-'+pkg.SrcRelease:'');
      if(version!==rule.version||sourceVersion!==rule.version)continue;
      const purl=pkg.Identifier?.PURL||'',parts=purl.split('?'),qualifiers=new URLSearchParams(parts[1]||'');
      const architecture=rule.package==='ncurses-base'?'all':'amd64';
      if(finding.PkgIdentifier?.PURL!==purl||decodeURIComponent(parts[0])!==`pkg:deb/debian/${rule.package}@${rule.version}`||
        qualifiers.get('arch')!==architecture||qualifiers.get('distro')!=='debian-13.6'||pkg.Arch!==architecture)continue;
      const approved=review.runtime.packages.find(x=>x.package===rule.package);
      if(!approved||approved.version!==rule.version||approved.source!==rule.source)continue;
      decisions.push({resultIndex,findingIndex,cve:rule.cve,package:rule.package,version:rule.version,state:'not_affected',
        justification:rule.reason,fixedHighOrCritical:['HIGH','CRITICAL'].includes(finding.Severity)&&Boolean(finding.FixedVersion),
        unfixedHighOrCritical:['HIGH','CRITICAL'].includes(finding.Severity)&&!finding.FixedVersion});
    }
  }
  assert.equal(new Set(decisions.map(key)).size,decisions.length,'postgres_ambiguous_findings');return decisions;
}
export async function loadPostgresLibxmlProof(now=new Date()){
  const root=new URL('./proofs/c15-postgres-libxml/',import.meta.url),bytes=await readFile(new URL('review.json',root));
  assert.equal(hash(bytes),LIBXML_REVIEW_SHA256,'postgres_libxml_review_changed');
  const review=JSON.parse(bytes);
  assert.equal(review.schema,'otziv-postgres-libxml-python-review-v1');
  assert.equal(review.parentReviewSha256,REVIEW_SHA256,'postgres_libxml_parent_changed');
  assert.deepEqual({cve:review.cve,...review.finding},POSTGRES_LIBXML_PYTHON_RULE,'postgres_libxml_scope_changed');
  assert.ok(Number.isFinite(now.getTime())&&now>=new Date(review.reviewedAt)&&now<new Date(review.validUntil),'postgres_libxml_review_expired');
  for(const [path,item]of Object.entries(review.files)){
    assert.ok(safePath(path),'postgres_libxml_proof_path');
    assert.equal(hash(await readFile(new URL(path,root))),item.sha256,'postgres_libxml_proof_changed');
  }
  const source=await readFile(new URL('upstream-python.c',root),'utf8');
  assert.match(source,/PyList_SetItem\(nameList, count, newName\);\s*count\+\+;/,'postgres_libxml_source_fix_missing');
  return {review,reviewSha256:hash(bytes)};
}
export async function adjudicatePostgresLibxml(report,inspected,observed,parent,now=new Date()){
  // This includes all runtime bytes, paths, packages, sources and entrypoint;
  // absence inferred from a package label alone is never sufficient.
  validatePostgresObserved(report,inspected,observed,parent);
  const {review,reviewSha256}=await loadPostgresLibxmlProof(now);
  assert.ok(!Object.keys(observed.inventory).some(path=>/python|libxml2mod/i.test(path)),'postgres_libxml_python_present');
  const source=parent.runtime.sources.find(item=>item.name==='libxml2');
  assert.equal(source?.sha256,review.sourceArchiveSha256,'postgres_libxml_source_changed');
  assert.equal(source.version,review.sourceVersion,'postgres_libxml_version_changed');
  return {schema:'otziv-postgres-libxml-python-adjudication-v1',status:'EXACT_RUNTIME_VERIFIED',reviewSha256,
    parentReviewSha256:REVIEW_SHA256,imageConfigId:report.Metadata.ImageID,validUntil:review.validUntil,
    decisions:matchRules(report,parent,[POSTGRES_LIBXML_PYTHON_RULE])};
}
export async function adjudicatePostgresImage(report,reportBytes,immutableImageId,scratch){
  const base={schema:'otziv-postgres-c14-adjudication-v1',rawReportSha256:hash(reportBytes),imageConfigId:report.Metadata?.ImageID,
    immutableImageId,rawReportModified:false,decisions:[]};
  if(labels(report)['com.otziv.reviewed-component']!=='postgres')return {...base,status:'NOT_APPLICABLE'};
  try{
    const {review,reviewSha256}=await loadPostgresProof();
    const {inspected,observed}=await inspectPostgresRuntime(immutableImageId,scratch);
    const binding=validatePostgresObserved(report,inspected,observed,review);
    const decisions=matchingPostgresFindings(report,review);
    const hasLibxmlFinding=(report.Results||[]).some(result=>(result.Vulnerabilities||[]).some(finding=>finding.VulnerabilityID===POSTGRES_LIBXML_PYTHON_RULE.cve));
    const libxml=hasLibxmlFinding?await adjudicatePostgresLibxml(report,inspected,observed,review):null;
    return {...base,status:'EXACT_RUNTIME_VERIFIED',reviewSha256,validUntil:review.validUntil,...binding,
      ...(libxml?{libxml}:{}),decisions:[...decisions,...(libxml?.decisions||[])]};
  }catch(error){return {...base,status:'REJECTED',reason:error.message?.match(/^(postgres_[a-z_]+)(?:\n|$)/)?.[1]||'postgres_proof_validation_failed'};}
}
export function effectivePostgresSummary(summary,adjudication){
  const decisions=adjudication?.status==='EXACT_RUNTIME_VERIFIED'?adjudication.decisions:[];
  const libxml=adjudication?.libxml;
  if(libxml){
    assert.equal(libxml.schema,'otziv-postgres-libxml-python-adjudication-v1','postgres_libxml_receipt_schema');
    assert.equal(libxml.status,'EXACT_RUNTIME_VERIFIED','postgres_libxml_receipt_status');
    assert.equal(libxml.reviewSha256,LIBXML_REVIEW_SHA256,'postgres_libxml_receipt_review');
    assert.equal(libxml.parentReviewSha256,REVIEW_SHA256,'postgres_libxml_receipt_parent');
    assert.equal(libxml.imageConfigId,adjudication.imageConfigId,'postgres_libxml_receipt_image');
    assert.deepEqual(libxml.decisions,decisions.filter(x=>x.cve===POSTGRES_LIBXML_PYTHON_RULE.cve),'postgres_libxml_receipt_decisions');
  }
  const allowedRules=libxml?[...POSTGRES_C14_RULES,POSTGRES_LIBXML_PYTHON_RULE]:POSTGRES_C14_RULES;
  assert.ok(Array.isArray(decisions)&&new Set(decisions.map(key)).size===decisions.length&&decisions.every(x=>
    x.state==='not_affected'&&allowedRules.some(y=>key(x)===key(y)&&x.justification===y.reason)),'postgres_summary_invalid_decisions');
  const fixed=decisions.filter(x=>x.fixedHighOrCritical).length,unfixed=decisions.filter(x=>x.unfixedHighOrCritical).length;
  const effectiveFixed=(summary.effectiveBlockingFixedHighOrCritical??summary.blockingFixedHighOrCritical)-fixed;
  const effectiveUnfixed=(summary.effectiveUnfixedHighOrCritical??summary.unfixedHighOrCritical)-unfixed;
  assert.ok(effectiveFixed>=0&&effectiveUnfixed>=0,'postgres_summary_invalid_counts');
  return {...summary,adjudicatedPostgresFixedHighOrCritical:fixed,adjudicatedPostgresUnfixedHighOrCritical:unfixed,
    effectiveBlockingFixedHighOrCritical:effectiveFixed,effectiveUnfixedHighOrCritical:effectiveUnfixed,
    unresolvedRiskReview:effectiveUnfixed?'REQUIRED':'NONE',
    result:effectiveFixed||effectiveUnfixed||adjudication?.status==='REJECTED'?'FAIL':(summary.result==='FAIL'?'FAIL':'PASS')};
}
