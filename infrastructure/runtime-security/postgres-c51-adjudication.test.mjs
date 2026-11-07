import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import {loadPostgresC16Proof,POSTGRES_C16_REVIEW_SHA256} from './postgres-c16-adjudication.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
import {summarizeReport} from './scan.mjs';
import {loadC36PostgresParent,loadC51ZlibPackage,expectedC51Files,validatePostgresC51Runtime,validateC51ScannerPackages,
  deriveC51Decisions,effectivePostgresC51Summary,hash,C51_CHANGED_FILES,C51_POSTGRES_PROOF_LABEL,PARENT_REFERENCE,
  PARENT_CONFIG_ID,PARENT_ACCEPTANCE_SHA256,PARENT_RUNTIME_SHA256,ZLIB_VERSION,ZLIB_PREVIOUS_VERSION,ZLIB_DEB_SHA256,
  ZLIB_PACKAGE_SHA256,ZLIB_LIBRARY,ZLIB_LIBRARY_SHA256,ZLIB_ADVISORIES} from './postgres-c51-adjudication.mjs';

const image='sha256:'+'a'.repeat(64);
const revision='c'.repeat(40);
const STATUS='var/lib/dpkg/status';
const c36=new URL('./proofs/c36-postgres/publication/',import.meta.url);
const c36Raw=await readFile(new URL('vulnerabilities.json',c36));
const c36Receipt=JSON.parse(await readFile(new URL('vulnerabilities.adjudications.json',c36))).postgres;

function capture(candidate,path,text){
  candidate.captured[path]=text;candidate.inventory[path]={...candidate.inventory[path],sha256:hash(text)};
}

async function fixture(){
  const {parent}=await loadC36PostgresParent(),zlib=await loadC51ZlibPackage();
  const candidate=structuredClone(parent);
  candidate.reference=image;candidate.imageId=image;candidate.rootfs=[...parent.rootfs,image];
  candidate.configuration.Labels={...parent.configuration.Labels,'com.otziv.publication.revision':revision,
    'com.otziv.zlib.package':'zlib1g='+ZLIB_VERSION,'com.otziv.zlib.deb-sha256':ZLIB_DEB_SHA256,
    'com.otziv.security.proof':C51_POSTGRES_PROOF_LABEL};
  for(const [path,row]of expectedC51Files(zlib))candidate.inventory[path]=structuredClone(row);
  capture(candidate,STATUS,parent.captured[STATUS].trimEnd().split('\n\n')
    .map(x=>x.startsWith('Package: zlib1g\n')?zlib.dpkgStatusRecord:x).join('\n\n')+'\n');
  capture(candidate,'var/lib/dpkg/info/zlib1g:amd64.list',zlib.dpkgList);
  const record={schema:'otziv-postgres-c51-inspection-v1',result:'PASS',component:'postgres',
    productionAccess:false,ownedContainersRemaining:0,
    parentInspectorSha256:'4969ebbd33456ba2fa10e377d2ff6877dca7651334c33536ca03362f83af1e1d',candidate};
  const config={rootfs:{diff_ids:candidate.rootfs},config:candidate.configuration};
  const report={Metadata:{ImageID:image,ImageConfig:config}};
  return {parent,zlib,record,config,report};
}

// The C36 scan with the dpkg-recorded zlib1g identity moved to the fixed
// release and the advisory it fixes no longer reported.
function fixedReport(){
  const report=JSON.parse(c36Raw);
  const zlib=report.Results[0].Packages.find(x=>x.Name==='zlib1g');
  Object.assign(zlib,{ID:'zlib1g@'+ZLIB_VERSION,Release:'1+deb13u1'});
  report.Results[0].Vulnerabilities=report.Results[0].Vulnerabilities.filter(x=>!ZLIB_ADVISORIES.includes(x.VulnerabilityID));
  return report;
}

test('C51 accepts exactly the official zlib1g files and records over frozen C36',async()=>{
  const {parent,zlib,record,config,report}=await fixture();
  assert.deepEqual(validatePostgresC51Runtime(record,parent,config,report,revision,zlib),
    {librarySha256:ZLIB_LIBRARY_SHA256,changedFiles:C51_CHANGED_FILES});
});

for(const [name,tamper,error] of [
  ['another executable',x=>{x.record.candidate.inventory['usr/local/pgsql/bin/postgres'].sha256='d'.repeat(64);},'unrelated_files_changed'],
  ['the gosu binary',x=>{x.record.candidate.inventory['usr/local/bin/gosu'].sha256='d'.repeat(64);},'unrelated_files_changed'],
  ['the linker cache',x=>{x.record.candidate.inventory['etc/ld.so.cache'].sha256='d'.repeat(64);},'unrelated_files_changed'],
  ['an extra file beside the package',x=>{x.record.candidate.inventory['usr/share/doc/zlib1g/extra']={mode:0o644,uid:0,gid:0,kind:'file',sha256:'d'.repeat(64)};},'unrelated_files_changed'],
  ['a library other than the Debian payload',x=>{x.record.candidate.inventory[ZLIB_LIBRARY].sha256='d'.repeat(64);},'zlib_file_mismatch'],
  ['a writable library',x=>{x.record.candidate.inventory[ZLIB_LIBRARY].mode=0o666;},'zlib_file_mismatch'],
  ['another package record',x=>{capture(x.record.candidate,STATUS,x.record.candidate.captured[STATUS].replace('Package: libc6\n','Package: libc6\nEssential: yes\n'));},'unrelated_package_changed'],
  ['a dropped package record',x=>{capture(x.record.candidate,STATUS,x.record.candidate.captured[STATUS].replace(/\n\nPackage: libc6\n[\s\S]*?(?=\n\n)/,''));},'package_set_changed'],
  ['the old zlib record',x=>{capture(x.record.candidate,STATUS,x.parent.captured[STATUS]);},'zlib_status_record'],
  ['an unbound status capture',x=>{x.record.candidate.captured[STATUS]+='\n';},'candidate_capture_hash_mismatch'],
  ['another package file list',x=>{capture(x.record.candidate,'var/lib/dpkg/info/libc6:amd64.list','/.\n');},'unrelated_files_changed'],
  ['runtime command',x=>{x.record.candidate.configuration.Cmd=['postgres','--single'];x.config.config.Cmd=['postgres','--single'];},'launch_contract_changed'],
  ['missing parent layer',x=>{x.record.candidate.rootfs[0]='sha256:'+'e'.repeat(64);x.config.rootfs.diff_ids[0]=x.record.candidate.rootfs[0];},'parent_layers_changed'],
  ['unreviewed label',x=>{x.record.candidate.configuration.Labels['com.otziv.other']='yes';},'labels_changed'],
  ['the C36 proof label',x=>{x.record.candidate.configuration.Labels['com.otziv.security.proof']='postgres-c36-gosu-go1273';},'labels_changed'],
  ['an executed candidate',x=>{x.record.candidate.containerExecuted=true;},'candidate_executed'],
])test('C51 rejects '+name,async()=>{
  const x=await fixture();tamper(x);
  assert.throws(()=>validatePostgresC51Runtime(x.record,x.parent,x.config,x.report,revision,x.zlib),new RegExp('postgres_c51_'+error));
});

test('C51 inventory binds the pinned official deb, its payload and the exact dpkg records',async()=>{
  const zlib=await loadC51ZlibPackage();
  assert.equal(zlib.url,'https://deb.debian.org/debian-security/pool/updates/main/z/zlib/zlib1g_1.3.dfsg%2breally1.3.1-1%2bdeb13u1_amd64.deb');
  assert.equal(zlib.signedArchiveIndex.filename,'pool/updates/main/z/zlib/zlib1g_1.3.dfsg+really1.3.1-1+deb13u1_amd64.deb');
  assert.equal(zlib.signedArchiveIndex.suite,'trixie-security');
  assert.deepEqual(C51_CHANGED_FILES,[...expectedC51Files(zlib).keys(),STATUS]
    .filter(path=>path!=='usr/lib/x86_64-linux-gnu/libz.so.1'&&path!=='usr/share/doc/zlib1g/copyright').sort());
});

test('C51 scanner must report the fixed zlib1g and neither zlib advisory',async()=>{
  const {review}=await loadPostgresC16Proof();
  assert.throws(()=>validateC51ScannerPackages(JSON.parse(c36Raw),review),/postgres_c51_scanner_zlib_version/);
  const report=fixedReport();
  assert.deepEqual(validateC51ScannerPackages(report,review),{package:'zlib1g',version:ZLIB_VERSION,
    previousVersion:ZLIB_PREVIOUS_VERSION,debSha256:ZLIB_DEB_SHA256,inventorySha256:ZLIB_PACKAGE_SHA256,
    librarySha256:ZLIB_LIBRARY_SHA256,advisories:ZLIB_ADVISORIES,scannerVersion:ZLIB_VERSION,advisoryFindings:0});
  for(const cve of ZLIB_ADVISORIES){
    const reported=fixedReport();
    reported.Results[0].Vulnerabilities.push({VulnerabilityID:cve,PkgName:'zlib1g',Severity:'HIGH',
      InstalledVersion:ZLIB_VERSION,FixedVersion:ZLIB_VERSION});
    assert.throws(()=>validateC51ScannerPackages(reported,review),/postgres_c51_zlib_advisory_reported/);
  }
});

test('C51 re-derives exactly the C36 PostgreSQL source decisions',async()=>{
  const {review}=await loadPostgresC16Proof();
  const {decisions,september}=await deriveC51Decisions(JSON.parse(c36Raw),review);
  assert.deepEqual(decisions,c36Receipt.decisions);
  assert.deepEqual(september,c36Receipt.libxmlSeptember);
  assert.equal(decisions.filter(x=>x.fixedHighOrCritical).length,17);
  assert.ok(decisions.every(x=>x.package!=='zlib1g'));
});

test('C51 verdict stays fail closed unless all package and parent bindings match',async()=>{
  const report=fixedReport(),summary=summarizeReport(report);
  const {review}=await loadPostgresC16Proof();
  const {decisions,september}=await deriveC51Decisions(report,review);
  const receipt={schema:'otziv-postgres-c51-adjudication-v1',status:'EXACT_RUNTIME_VERIFIED',
    imageConfigId:report.Metadata.ImageID,parentReference:PARENT_REFERENCE,parentConfigId:PARENT_CONFIG_ID,
    parentAcceptanceSha256:PARENT_ACCEPTANCE_SHA256,parentRuntimeSha256:PARENT_RUNTIME_SHA256,
    reviewSha256:POSTGRES_C16_REVIEW_SHA256,validUntil:'2027-01-01T00:00:00Z',runtimeProofSha256:'d'.repeat(64),
    changedFiles:C51_CHANGED_FILES,inspectedBinaryExecuted:false,libxmlSeptember:september,decisions,
    zlib:{package:'zlib1g',version:ZLIB_VERSION,previousVersion:ZLIB_PREVIOUS_VERSION,debSha256:ZLIB_DEB_SHA256,
      inventorySha256:ZLIB_PACKAGE_SHA256,librarySha256:ZLIB_LIBRARY_SHA256,advisories:ZLIB_ADVISORIES,
      scannerVersion:ZLIB_VERSION,advisoryFindings:0}};
  const verdict=effectivePostgresC51Summary(summary,receipt);
  assert.equal(verdict.result,'PASS');
  assert.equal(verdict.adjudicatedPostgresFixedHighOrCritical,17);
  assert.equal(verdict.effectiveBlockingFixedHighOrCritical,0);
  assert.equal(effectivePostgresC51Summary(summary,{...receipt,status:'REJECTED'}).result,'FAIL');
  assert.throws(()=>effectivePostgresC51Summary(summary,{...receipt,changedFiles:[...C51_CHANGED_FILES,'bin/sh']}),
    /postgres_c51_summary_scope/);
  assert.throws(()=>effectivePostgresC51Summary(summary,{...receipt,zlib:{...receipt.zlib,advisoryFindings:1}}),
    /postgres_c51_summary_zlib/);
  const zlibDecision={...decisions[0],package:'zlib1g',version:ZLIB_PREVIOUS_VERSION,cve:ZLIB_ADVISORIES[0]};
  assert.throws(()=>effectivePostgresC51Summary(summary,{...receipt,decisions:[...decisions,zlibDecision]}),
    /postgres_c51_summary_decisions_changed/);
  const empty={schema:'otziv-image-adjudication-v1',status:'NOT_APPLICABLE',decisions:[]};
  assert.equal(combinedScanSummary(summary,{grafana:empty,alloy:empty,postgres:receipt}).result,'PASS');
  assert.throws(()=>combinedScanSummary(summary,{grafana:empty,alloy:empty,postgres:receipt,gosu:empty}),
    /scan_verdict_c51_separate_gosu_forbidden/);
});
