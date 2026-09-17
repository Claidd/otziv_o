// Extend current scans without modifying the source bound into C16 activation evidence.
import assert from 'node:assert/strict';
import {adjudicatePostgresImage as previousImage, adjudicatePostgresC16Observed,
  effectivePostgresSummary as previousSummary, loadPostgresC16Proof} from './postgres-c16-adjudication.mjs';
import {LIBXML_SEPTEMBER_RULES, reviewLibxmlSeptemberFindings,
  validateLibxmlSeptemberReceipt} from './postgres-libxml-september-review.mjs';

async function supplement(report, receipt, now) {
  if(receipt.schema!=='otziv-postgres-c16-adjudication-v1'||receipt.status!=='EXACT_RUNTIME_VERIFIED')return receipt;
  const {review}=await loadPostgresC16Proof(now);
  const libxmlSeptember=await reviewLibxmlSeptemberFindings(report,review,now);
  return libxmlSeptember?{...receipt,libxmlSeptember,decisions:[...receipt.decisions,...libxmlSeptember.decisions]}:receipt;
}

export async function adjudicatePostgresC17Observed(report,bytes,id,inspected,observed,now=new Date()) {
  return supplement(report,await adjudicatePostgresC16Observed(report,bytes,id,inspected,observed,now),now);
}

export async function adjudicatePostgresImage(report,bytes,id,scratch) {
  const receipt=await previousImage(report,bytes,id,scratch);
  try{return await supplement(report,receipt,new Date());}
  catch(error){return {...receipt,status:'REJECTED',decisions:[],reason:error.message?.match(/^(postgres_[a-z_]+)(?:\n|$)/)?.[1]||'postgres_c17_proof_validation_failed'};}
}

export function effectivePostgresSummary(summary,receipt) {
  if(receipt?.schema!=='otziv-postgres-c16-adjudication-v1'||receipt.status!=='EXACT_RUNTIME_VERIFIED')
    return previousSummary(summary,receipt);
  const extra=receipt.decisions.filter(x=>LIBXML_SEPTEMBER_RULES.some(rule=>rule.cve===x.cve));
  validateLibxmlSeptemberReceipt(receipt,extra);
  const key=x=>x.package+'|'+x.version+'|'+x.cve;
  assert.ok(new Set(extra.map(key)).size===extra.length&&extra.every(x=>x.state==='not_affected'&&
    LIBXML_SEPTEMBER_RULES.some(y=>key(x)===key(y)&&x.justification===y.reason)),'postgres_c17_receipt_decisions');
  const fixed=extra.filter(x=>x.fixedHighOrCritical).length,unfixed=extra.filter(x=>x.unfixedHighOrCritical).length;
  const counters={...summary,
    effectiveBlockingFixedHighOrCritical:(summary.effectiveBlockingFixedHighOrCritical??summary.blockingFixedHighOrCritical)-fixed,
    effectiveUnfixedHighOrCritical:(summary.effectiveUnfixedHighOrCritical??summary.unfixedHighOrCritical)-unfixed};
  assert.ok(counters.effectiveBlockingFixedHighOrCritical>=0&&counters.effectiveUnfixedHighOrCritical>=0,'postgres_c17_summary_counts');
  const {libxmlSeptember,...parent}=receipt;
  const result=previousSummary(counters,{...parent,decisions:receipt.decisions.filter(x=>!extra.includes(x))});
  return {...result,adjudicatedPostgresFixedHighOrCritical:result.adjudicatedPostgresFixedHighOrCritical+fixed,
    adjudicatedPostgresUnfixedHighOrCritical:result.adjudicatedPostgresUnfixedHighOrCritical+unfixed};
}
