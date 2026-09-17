import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
import {adjudicatePostgresC17Observed,effectivePostgresSummary} from './postgres-c17-adjudication.mjs';
import {loadLibxmlSeptemberReview,LIBXML_SEPTEMBER_RULES} from './postgres-libxml-september-review.mjs';
import {loadPostgresC16Proof} from './postgres-c16-adjudication.mjs';
import {matchRules} from './postgres-c15-adjudication.mjs';
import {summarizeReport} from './scan.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
const bytes=gunzipSync(await readFile(new URL('./proofs/c17-postgres-libxml/scanner-report.json.gz',import.meta.url)));
const raw=JSON.parse(bytes);
const captured=JSON.parse(gunzipSync(await readFile(new URL('./proofs/c16-postgres-published/published-observed-runtime.json.gz',import.meta.url))));
const replay=(observed=captured.observed)=>adjudicatePostgresC17Observed(raw,bytes,raw.Metadata.ImageID,captured.inspected,observed);

test('actual September scan is resolved only after exact published runtime and five source fixes are verified',async()=>{
  const receipt=await replay();
  assert.equal(receipt.libxmlSeptember.decisions.length,5);
  assert.equal(receipt.rawReportModified,false);
  assert.deepEqual(JSON.parse(bytes),raw);
  const summary=combinedScanSummary(summarizeReport(raw),{postgres:receipt});
  assert.equal(summary.result,'PASS');
  assert.equal(summary.effectiveBlockingFixedHighOrCritical,0);
  assert.equal(summary.effectiveUnfixedHighOrCritical,0);
});
test('altered installed libxml bytes are rejected',async()=>{
  const changed=structuredClone(captured.observed);
  const path=Object.keys(changed.inventory).find(x=>/libxml2\.so/.test(x)&&changed.inventory[x].sha256);
  assert.ok(path);changed.inventory[path].sha256='0'.repeat(64);
  await assert.rejects(replay(changed));
});
test('scope cannot match unknown CVEs, vendors, packages or versions',async()=>{
  const {review}=await loadPostgresC16Proof();
  for(const mutate of [f=>f.VulnerabilityID='CVE-2099-99999',f=>f.DataSource.ID='unknown',f=>f.PkgName='libxml2',f=>f.InstalledVersion='2.15.3']){
    const changed=structuredClone(raw),f=changed.Results.flatMap(x=>x.Vulnerabilities||[]).find(x=>x.VulnerabilityID===LIBXML_SEPTEMBER_RULES[0].cve);
    mutate(f);assert.equal(matchRules(changed,review,LIBXML_SEPTEMBER_RULES).length,4);
  }
});
test('missing, invented or expanded supplemental receipt is rejected',async()=>{
  for(const mutate of [x=>delete x.libxmlSeptember,x=>x.libxmlSeptember.reviewSha256='0'.repeat(64),x=>x.libxmlSeptember.imageConfigId='sha256:'+'0'.repeat(64),x=>x.decisions.find(d=>d.cve===LIBXML_SEPTEMBER_RULES[0].cve).version='2.15.3']){
    const receipt=await replay();mutate(receipt);
    assert.throws(()=>effectivePostgresSummary(summarizeReport(raw),receipt));
  }
});
test('unrelated high finding remains blocking',async()=>{
  const summary=summarizeReport(raw);summary.high++;summary.unfixedHighOrCritical++;
  const verdict=combinedScanSummary(summary,{postgres:await replay()});
  assert.equal(verdict.result,'FAIL');assert.equal(verdict.effectiveUnfixedHighOrCritical,1);
});
test('supplemental review is time bounded',async()=>{
  await loadLibxmlSeptemberReview();
  await assert.rejects(loadLibxmlSeptemberReview(new Date('2026-09-16T00:00:00Z')));
  await assert.rejects(loadLibxmlSeptemberReview(new Date('2027-01-01T00:00:00Z')));
});
