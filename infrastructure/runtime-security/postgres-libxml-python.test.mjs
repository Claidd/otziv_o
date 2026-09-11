import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
import test from 'node:test';
import {loadPostgresProof,loadPostgresLibxmlProof,adjudicatePostgresLibxml,matchingPostgresFindings,
  effectivePostgresSummary} from './postgres-c15-adjudication.mjs';
import {summarizeReport} from './scan.mjs';

const zipped=async path=>JSON.parse(gunzipSync(await readFile(new URL(path,import.meta.url))));
async function fixtures(){
  return {parent:(await loadPostgresProof()).review,report:await zipped('./proofs/c15-postgres-libxml/ci-report.json.gz'),
    runtime:await zipped('./proofs/c15-postgres-libxml/observed-runtime.json.gz')};
}
const classify=({parent,report,runtime})=>adjudicatePostgresLibxml(report,runtime.inspected,runtime.observed,parent);

test('fresh CI finding has one supplemental decision; original raw report and C14 scope stay unchanged',async()=>{
  const f=await fixtures(),before=JSON.stringify(f.report),raw=summarizeReport(f.report),libxml=await classify(f);
  assert.equal(raw.high+raw.critical,28);
  assert.equal(libxml.decisions.length,1);assert.equal(libxml.decisions[0].cve,'CVE-2026-74860');
  const prior=matchingPostgresFindings(f.report,f.parent);
  assert.equal(effectivePostgresSummary(raw,{status:'EXACT_RUNTIME_VERIFIED',decisions:prior}).result,'FAIL');
  const receipt={status:'EXACT_RUNTIME_VERIFIED',imageConfigId:f.report.Metadata.ImageID,libxml,decisions:[...prior,...libxml.decisions]};
  const result=effectivePostgresSummary(raw,receipt);
  assert.equal(result.result,'PASS');assert.equal(result.effectiveUnfixedHighOrCritical,0);
  assert.equal(JSON.stringify(f.report),before);
  delete receipt.libxml;
  assert.throws(()=>effectivePostgresSummary(raw,receipt),/invalid_decisions/);
});

test('expired or future supplemental evidence fails closed',async()=>{
  await assert.rejects(loadPostgresLibxmlProof(new Date('2027-01-01')),/expired/);
  await assert.rejects(loadPostgresLibxmlProof(new Date('2026-09-01')),/expired/);
});

test('Python bindings or interpreter, modified libxml binary, source or image rootfs invalidate the proof',async()=>{
  for(const mode of ['bindings','python','binary','source','rootfs']){
    const f=await fixtures();
    if(mode==='bindings'||mode==='python')f.runtime.observed.inventory[mode==='python'?'usr/bin/python3':'usr/lib/python3/libxml2mod.so']={kind:'file',elf:true,sha256:'a'.repeat(64)};
    if(mode==='binary')f.runtime.observed.inventory['usr/local/lib/libxml2.so.16.1.4'].sha256='a'.repeat(64);
    if(mode==='source'){
      const path='usr/local/share/otziv/upstream-components.json',data=JSON.parse(f.runtime.observed.captured[path]);
      data.find(x=>x.name==='libxml2').sha256='a'.repeat(64);f.runtime.observed.captured[path]=JSON.stringify(data);
    }
    if(mode==='rootfs')f.runtime.inspected.RootFS.Layers=['sha256:'+'a'.repeat(64)];
    await assert.rejects(classify(f),/runtime_bytes|rootfs_mismatch/,mode);
  }
});

test('new CVE, different package/version/PURL or datasource never inherit the Python decision',async()=>{
  for(const mode of ['cve','package','version','purl','datasource']){
    const f=await fixtures();const finding=f.report.Results.flatMap(x=>x.Vulnerabilities||[]).find(x=>x.VulnerabilityID==='CVE-2026-74860');
    if(mode==='cve')finding.VulnerabilityID='CVE-2099-12345';
    if(mode==='package')finding.PkgName='libxslt1.1';
    if(mode==='version')finding.InstalledVersion='2.15.2';
    if(mode==='purl')finding.PkgIdentifier.PURL='pkg:deb/debian/libxml2-16@2.15.2';
    if(mode==='datasource')finding.DataSource.ID='other';
    assert.equal((await classify(f)).decisions.length,0,mode);
  }
});

test('copied supplemental receipt for another image, proof, or decisions is rejected',async()=>{
  const f=await fixtures(),libxml=await classify(f),decisions=[...matchingPostgresFindings(f.report,f.parent),...libxml.decisions];
  for(const mode of ['imageConfigId','reviewSha256','parentReviewSha256','decisions']){
    const changed=structuredClone(libxml);changed[mode]=mode==='decisions'?[]:'a'.repeat(64);
    assert.throws(()=>effectivePostgresSummary(summarizeReport(f.report),{status:'EXACT_RUNTIME_VERIFIED',imageConfigId:f.report.Metadata.ImageID,libxml:changed,decisions}),/postgres_libxml_receipt/);
  }
});
