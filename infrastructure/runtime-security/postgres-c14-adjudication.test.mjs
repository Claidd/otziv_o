import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { gunzipSync } from 'node:zlib';
import { Readable } from 'node:stream';
import test from 'node:test';
import {loadPostgresProof,matchingPostgresFindings,validatePostgresObserved,runtimeIdentity,
  effectivePostgresSummary,inventoryTar} from './postgres-c14-adjudication.mjs';

const root=new URL('./proofs/c14-postgres/',import.meta.url);
const zipped=async path=>JSON.parse(gunzipSync(await readFile(new URL(path,root))));
const clone=x=>structuredClone(x);
const fixtures=async()=>({review:(await loadPostgresProof()).review,report:await zipped('raw/candidate.json.gz'),
  observed:await zipped('observed-runtime.json.gz')});
const summary=report=>{
  const rows=report.Results.flatMap(x=>x.Vulnerabilities||[]);
  return {high:rows.filter(x=>x.Severity==='HIGH').length,critical:rows.filter(x=>x.Severity==='CRITICAL').length,
    blockingFixedHighOrCritical:rows.filter(x=>['HIGH','CRITICAL'].includes(x.Severity)&&x.FixedVersion).length,
    unfixedHighOrCritical:rows.filter(x=>['HIGH','CRITICAL'].includes(x.Severity)&&!x.FixedVersion).length};
};
const verdict=(report,review)=>effectivePostgresSummary(summary(report),{status:'EXACT_RUNTIME_VERIFIED',decisions:matchingPostgresFindings(report,review)});

test('review validates immutable primary evidence, patch control, full upstream tests and expiry',async()=>{
  const {review}=await loadPostgresProof();assert.ok(Object.keys(review.files).length>25);
  assert.equal(review.runtime.gzipPatch.originalInBoundsStateResetExit,42);assert.equal(review.runtime.gzipPatch.fixedInBoundsStateResetExit,0);
  assert.equal(review.runtime.sources.length,4);assert.ok(Object.keys(review.runtime.payload).length>500);
  await assert.rejects(loadPostgresProof(new Date('2027-01-01T00:00:00Z')),/expired/);
  await assert.rejects(loadPostgresProof(new Date('2025-01-01T00:00:00Z')),/expired/);
});
test('actual final report retains 27 raw HIGH/CRITICAL rows and exact reviewed classifications account for each',async()=>{
  const {review,report,observed}=await fixtures(),before=JSON.stringify(report);
  const binding=validatePostgresObserved(report,observed.inspected,observed.observed,review);
  assert.equal(binding.inspectedBinaryExecuted,false);
  const result=verdict(report,review);assert.equal(result.high+result.critical,27);
  assert.equal(result.effectiveBlockingFixedHighOrCritical,0);assert.equal(result.effectiveUnfixedHighOrCritical,0);
  assert.equal(result.result,'PASS');assert.equal(JSON.stringify(report),before);
});
test('actual prior PostgreSQL17.10 scan detects the old binary and is never eligible for this runtime identity',async()=>{
  const {review}=await loadPostgresProof(),old=await zipped('raw/source.json.gz');
  const rows=old.Results.flatMap(x=>x.Vulnerabilities||[]).filter(x=>x.PkgName==='postgresql-17');
  for(const cve of ['CVE-2026-14662','CVE-2026-6464'])
    assert.ok(rows.some(x=>x.VulnerabilityID===cve&&x.InstalledVersion.startsWith('17.10')&&x.Severity==='HIGH'));
  assert.equal(matchingPostgresFindings(old,review).filter(x=>x.package.includes('postgresql')).length,0);
  assert.ok(verdict(old,review).effectiveBlockingFixedHighOrCritical>0);
});
test('actual v5 archive with unpatched gzip fails identity despite identical upstream release version and product label',async()=>{
  const old=await zipped('controls/observed-unpatched-v5.json.gz');
  assert.equal(old.inspected.Config.Labels['com.otziv.reviewed-component'],'postgres');
  assert.throws(()=>runtimeIdentity(old.observed),/gzip_patch_missing/);
});
test('modified PostgreSQL or XML library bytes, extra executable and absent affected CLI all reject',async()=>{
  const {review,report,observed}=await fixtures();
  for(const path of ['usr/local/pgsql/bin/postgres','usr/local/lib/libxml2.so.16.1.4','usr/local/bin/gzip']){
    const changed=clone(observed.observed);changed.inventory[path].sha256='0'.repeat(64);
    assert.throws(()=>validatePostgresObserved(report,observed.inspected,changed,review),/runtime_bytes/);
  }
  for(const path of ['usr/bin/infocmp','usr/bin/mount','usr/lib/systemd/systemd-homed','usr/lib/libmount.so.1','usr/bin/new-code']){
    const changed=clone(observed.observed);changed.inventory[path]={kind:'file',sha256:'0'.repeat(64),elf:true};
    assert.throws(()=>validatePostgresObserved(report,observed.inspected,changed,review),/implementation_present|runtime_bytes/);
  }
});
test('changed source archive hash or missing GNU security revision rejects without subtracting findings',async()=>{
  const {review,report,observed}=await fixtures();
  for(const mode of ['hash','revision']){
    const changed=clone(observed.observed),path='usr/local/share/otziv/upstream-components.json',sources=JSON.parse(changed.captured[path]);
    if(mode==='hash')sources[0].sha256='0'.repeat(64);else delete sources.find(x=>x.name==='gzip').packageVersion;
    changed.captured[path]=JSON.stringify(sources);
    assert.throws(()=>validatePostgresObserved(report,observed.inspected,changed,review),/runtime_bytes/);
  }
  const result=effectivePostgresSummary(summary(report),{status:'REJECTED',decisions:[]});assert.equal(result.result,'FAIL');
  assert.equal(result.effectiveBlockingFixedHighOrCritical,17);assert.equal(result.effectiveUnfixedHighOrCritical,10);
});
test('rebuild config identity may change only when rootfs/config behavior and all actual runtime bytes remain bound',async()=>{
  const {review,report,observed}=await fixtures(),repackaged=clone(report);
  repackaged.Metadata.ImageID='sha256:'+'a'.repeat(64);
  assert.doesNotThrow(()=>validatePostgresObserved(repackaged,observed.inspected,observed.observed,review));
  const wrongRoot=clone(observed.inspected);wrongRoot.RootFS.Layers=['sha256:'+'b'.repeat(64)];
  assert.throws(()=>validatePostgresObserved(report,wrongRoot,observed.observed,review),/rootfs_mismatch/);
  const changedConfig=clone(observed.inspected);changedConfig.Config.Cmd=['postgres','-c','listen_addresses=localhost'];
  assert.throws(()=>validatePostgresObserved(report,changedConfig,observed.observed,review),/config_report_mismatch/);
});
test('unknown CVE, altered package version/PURL/source, absent package, or scanner datasource never inherits an exemption',async()=>{
  const {review,report}=await fixtures(),section=report.Results.find(x=>x.Type==='debian');
  const index=section.Vulnerabilities.findIndex(x=>x.PkgName==='otziv-postgresql-17'&&x.Severity==='HIGH');
  for(const mode of ['newCve','version','purl','source','missing','datasource']){
    const changed=clone(report),target=changed.Results.find(x=>x.Type==='debian'),finding=target.Vulnerabilities[index];
    if(mode==='newCve')finding.VulnerabilityID='CVE-2099-12345';
    if(mode==='version')finding.InstalledVersion='17.10';
    if(mode==='purl')finding.PkgIdentifier.PURL='pkg:deb/debian/other@17.11?arch=amd64&distro=debian-13.6';
    if(mode==='source')target.Packages.find(x=>x.Name==='otziv-postgresql-17').SrcName='other';
    if(mode==='missing')target.Packages=target.Packages.filter(x=>x.Name!=='otziv-postgresql-17');
    if(mode==='datasource')finding.DataSource.ID='other';
    assert.ok(verdict(changed,review).effectiveBlockingFixedHighOrCritical>0,mode);
  }
});
test('duplicate rows cannot produce duplicate subtraction and arbitrary forged decisions reject',async()=>{
  const {review,report}=await fixtures(),changed=clone(report),section=changed.Results.find(x=>x.Type==='debian');
  section.Vulnerabilities.push(clone(section.Vulnerabilities.find(x=>x.PkgName==='otziv-postgresql-17')));
  assert.throws(()=>matchingPostgresFindings(changed,review),/ambiguous/);
  assert.throws(()=>effectivePostgresSummary(summary(report),{status:'EXACT_RUNTIME_VERIFIED',decisions:[{
    cve:'CVE-2099-12345',package:'libc6',version:'2.41',state:'not_affected',justification:'vulnerable_implementation_absent'}]}),/invalid_decisions/);
});
test('streaming stopped-export reader rejects malformed checksum and truncated archives before identity matching',async()=>{
  const invalid=Buffer.alloc(512);invalid.write('usr/bin/postgres');
  await assert.rejects(inventoryTar(Readable.from([invalid])),/checksum/);
  await assert.rejects(inventoryTar(Readable.from([Buffer.alloc(64)])),/truncated/);
});
test('scanner must detect all four actual custom packages, including the component with no HIGH findings',async()=>{
  const {review,report,observed}=await fixtures();
  for(const packageName of ['otziv-postgresql-17','libxml2-16','libxslt1.1','gzip']){
    const changed=clone(report),section=changed.Results.find(x=>x.Type==='debian');
    section.Packages=section.Packages.filter(x=>x.Name!==packageName);
    assert.throws(()=>validatePostgresObserved(changed,observed.inspected,observed.observed,review),/scanner_coverage_missing/);
  }
});
test('source context bytes equal the frozen physical LF build inputs',async()=>{
  const manifest=JSON.parse(await readFile(new URL('source-manifest.json',root)));
  for(const [path,item]of Object.entries(manifest.files)){
    const bytes=await readFile(new URL('../../'+path.slice('infrastructure/runtime-security/'.length),root));
    assert.equal(bytes.includes(13),false,path);assert.equal(createHash('sha256').update(bytes).digest('hex'),item.sha256,path);
  }
});
