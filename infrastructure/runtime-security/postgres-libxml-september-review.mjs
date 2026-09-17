// Supplemental source review; original publication receipts remain immutable.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {matchRules} from './postgres-c15-adjudication.mjs';

export const LIBXML_SEPTEMBER_REVIEW_SHA256='39a4c77f58af36c026e86e32a50d3618cd04bcabf217c3cd7afc5aa502af16e5';
const PARENT='c52ea7697261a4a1d3eed1540f14aa9048f44a63ff0631dd349443905e09fd67';
const SOURCE='98087fd181d9070724f3fbc65c7377db03038eb92bd882374daff44940138821';
const root=new URL('./proofs/c17-postgres-libxml/',import.meta.url);
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
export const LIBXML_SEPTEMBER_RULES=['CVE-2026-86138','CVE-2026-86139','CVE-2026-86142','CVE-2026-86143','CVE-2026-86144']
  .map(cve=>({cve,package:'libxml2-16',source:'libxml2',version:'2.15.4',reason:'fixed_upstream_source'}));

export async function loadLibxmlSeptemberReview(now=new Date()){
  const bytes=await readFile(new URL('review.json',root)),review=JSON.parse(bytes);
  assert.equal(hash(bytes),LIBXML_SEPTEMBER_REVIEW_SHA256,'postgres_libxml_september_review_changed');
  assert.equal(review.schema,'otziv-postgres-libxml-c17-review-v1','postgres_libxml_september_schema');
  assert.equal(review.parentReviewSha256,PARENT,'postgres_libxml_september_parent');
  assert.equal(review.sourceArchiveSha256,SOURCE,'postgres_libxml_september_source');
  assert.equal(review.sourceVersion,'2.15.4','postgres_libxml_september_version');
  assert.deepEqual(review.rules,LIBXML_SEPTEMBER_RULES,'postgres_libxml_september_scope');
  assert.ok(Number.isFinite(now.getTime())&&now>=new Date(review.reviewedAt)&&now<new Date(review.validUntil),
    'postgres_libxml_september_expired');
  const files=new Map();
  for(const [path,item]of Object.entries(review.files)){
    assert.match(path,/^[A-Za-z0-9.-]+$/,'postgres_libxml_september_path');
    const content=await readFile(new URL(path,root));
    assert.equal(hash(content),item.sha256,'postgres_libxml_september_evidence_changed');files.set(path,content.toString('utf8'));
  }
  assert.match(files.get('dict.c'),/size = 4 \* \(\(size_t\) namelen \+ plen \+ 1\)/,'postgres_libxml_september_dict_fix');
  assert.match(files.get('uri.c'),/len = xmlStrlen\(str\);\s*if \(len == 0\)\s*return\(NULL\);/,'postgres_libxml_september_uri_fix');
  assert.match(files.get('xpointer.c'),/if \(len == 0 && ctxt->cur != NULL && \*ctxt->cur != 0\)\s*\{\s*xmlXPathPErrMemory\(ctxt\);\s*xmlFree\(name\);\s*return;/,'postgres_libxml_september_xpointer_fix');
  assert.match(files.get('xmlIO.c'),/if \(nbchars >= INT_MAX\)/,'postgres_libxml_september_write_fix');
  assert.equal((files.get('xmlIO.c').match(/if \(bufsize >= INT_MAX\)/g)||[]).length,2,'postgres_libxml_september_flush_fix');
  assert.match(files.get('xinclude.c'),/xmlCtxtUseOptions\(pctxt, ctxt->parseFlags\);/,'postgres_libxml_september_text_flags');
  assert.match(files.get('xinclude.c'),/xmlXIncludeProcessFlags\(doc, doc \? doc->parseFlags : 0\)/,'postgres_libxml_september_document_flags');
  assert.match(files.get('xinclude.c'),/xmlXIncludeProcessTreeFlags\(tree, \(tree && tree->doc\) \? tree->doc->parseFlags : 0\)/,'postgres_libxml_september_tree_flags');
  return review;
}

// Caller first validates the complete stopped-image C16 runtime, launcher,
// rootfs, package inventory and source closure with validatePostgresC16Observed.
export async function reviewLibxmlSeptemberFindings(report,parent,now=new Date()){
  const decisions=matchRules(report,parent,LIBXML_SEPTEMBER_RULES);
  if(!decisions.length)return null;
  const review=await loadLibxmlSeptemberReview(now);
  const source=parent.runtime.sources.find(x=>x.name==='libxml2');
  assert.equal(source?.sha256,SOURCE,'postgres_libxml_september_runtime_source');
  assert.equal(source.version,'2.15.4','postgres_libxml_september_runtime_version');
  return {schema:'otziv-postgres-libxml-c17-adjudication-v1',status:'EXACT_RUNTIME_VERIFIED',
    reviewSha256:LIBXML_SEPTEMBER_REVIEW_SHA256,parentReviewSha256:PARENT,
    imageConfigId:report.Metadata.ImageID,validUntil:review.validUntil,decisions};
}

export function validateLibxmlSeptemberReceipt(receipt,decisions){
  const extra=receipt.libxmlSeptember;
  if(!extra){assert.equal(decisions.length,0,'postgres_libxml_september_receipt_missing');return;}
  assert.equal(extra.schema,'otziv-postgres-libxml-c17-adjudication-v1','postgres_libxml_september_receipt_schema');
  assert.equal(extra.status,'EXACT_RUNTIME_VERIFIED','postgres_libxml_september_receipt_status');
  assert.equal(extra.reviewSha256,LIBXML_SEPTEMBER_REVIEW_SHA256,'postgres_libxml_september_receipt_review');
  assert.equal(extra.parentReviewSha256,PARENT,'postgres_libxml_september_receipt_parent');
  assert.equal(extra.imageConfigId,receipt.imageConfigId,'postgres_libxml_september_receipt_image');
  assert.equal(extra.validUntil,'2027-01-01T00:00:00Z','postgres_libxml_september_receipt_expiry');
  assert.deepEqual(extra.decisions,decisions,'postgres_libxml_september_receipt_decisions');
}
