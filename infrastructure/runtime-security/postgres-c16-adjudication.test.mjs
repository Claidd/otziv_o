import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
import {loadPostgresC16Proof,validatePostgresC16Observed,adjudicatePostgresC16Observed,
  effectivePostgresSummary,POSTGRES_C16_RULES} from './postgres-c16-adjudication.mjs';
import {matchRules} from './postgres-c15-adjudication.mjs';
import {summarizeReport} from './scan.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
const root=new URL('./proofs/c16-postgres/',import.meta.url);
const rawBytes=gunzipSync(await readFile(new URL('scanner-report.json.gz',root)));
const raw=JSON.parse(rawBytes);
const captured=JSON.parse(gunzipSync(await readFile(new URL('observed-runtime.json.gz',root))));
const {review,observed}=await loadPostgresC16Proof();
const replay=()=>adjudicatePostgresC16Observed(raw,rawBytes,captured.id,captured.inspected,captured.observed);

test('source fixes, upstream tests and unchanged parent bytes bind the actual runtime',async()=>{
  const receipt=await replay();
  assert.equal(receipt.status,'EXACT_RUNTIME_VERIFIED');
  assert.equal(receipt.inspectedBinaryExecuted,false);
  assert.equal(receipt.decisions.filter(x=>x.package==='libpcre2-8-0').length,2);
  const summary=combinedScanSummary(summarizeReport(raw),{postgres:receipt});
  assert.equal(summary.result,'PASS');assert.equal(summary.effectiveBlockingFixedHighOrCritical,0);
  assert.equal(summary.effectiveUnfixedHighOrCritical,0);
  assert.equal(raw.Results.flatMap(x=>x.Vulnerabilities||[]).length,JSON.parse(rawBytes).Results.flatMap(x=>x.Vulnerabilities||[]).length);
});
for(const [name,mutate]of [
  ['changed PCRE2 executable',x=>x.inventory['usr/lib/x86_64-linux-gnu/libpcre2-8.so.0.16.0'].sha256='0'.repeat(64)],
  ['changed PostgreSQL executable',x=>x.inventory['usr/local/pgsql/bin/postgres'].sha256='0'.repeat(64)],
  ['unrelated non-executable bytes',x=>x.inventory['usr/share/doc/libpcre2-8-0/copyright'].sha256='0'.repeat(64)],
  ['new executable disguised as a build log',x=>{x.inventory['usr/local/share/otziv/build/pcre2-tests.log'].elf=true;}],
])test(name+' is rejected',()=>{
  const changed=structuredClone(observed);mutate(changed);
  assert.throws(()=>validatePostgresC16Observed(raw,captured.inspected,changed,review,observed));
});
test('changing launcher or image layer binding is rejected',()=>{
  const changed=structuredClone(captured.inspected);changed.Config.Entrypoint=['/different'];
  assert.throws(()=>validatePostgresC16Observed(raw,changed,observed,review,observed));
  const report=structuredClone(raw);report.Metadata.ImageConfig.rootfs.diff_ids=['sha256:'+'0'.repeat(64)];
  assert.throws(()=>validatePostgresC16Observed(report,captured.inspected,observed,review,observed));
});
test('changed package/version/vendor identity receives no exemption',()=>{
  for(const mutate of [f=>f.InstalledVersion='10.46-1~deb13u1',f=>f.DataSource.ID='unknown',f=>f.VulnerabilityID='CVE-2099-12345']){
    const report=structuredClone(raw),finding=report.Results.flatMap(x=>x.Vulnerabilities||[]).find(x=>x.VulnerabilityID===POSTGRES_C16_RULES[0].cve);
    mutate(finding);assert.equal(matchRules(report,review,POSTGRES_C16_RULES).length,1);
  }
});
test('a new unknown HIGH finding still blocks deployment',async()=>{
  const receipt=await replay(),summary=summarizeReport(raw);summary.unfixedHighOrCritical++;summary.high++;
  const verdict=combinedScanSummary(summary,{postgres:receipt});
  assert.equal(verdict.result,'FAIL');assert.equal(verdict.effectiveUnfixedHighOrCritical,1);
});
test('invented reviewed runtime receipt is rejected',async()=>{
  const receipt=await replay();receipt.reviewSha256='0'.repeat(64);
  assert.throws(()=>effectivePostgresSummary(summarizeReport(raw),receipt));
});
test('review expires and cannot be replayed before it was established',async()=>{
  await assert.rejects(loadPostgresC16Proof(new Date('2027-01-01T00:00:00Z')));
  await assert.rejects(loadPostgresC16Proof(new Date('2026-09-11T00:00:00Z')));
});
