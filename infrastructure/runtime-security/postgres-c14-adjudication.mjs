import assert from 'node:assert/strict';
import { createHash,randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir,readFile,rm } from 'node:fs/promises';
import { join } from 'node:path';
import { run } from '../recovery/process.mjs';

const proofRoot=new URL('./proofs/c14-postgres/',import.meta.url);
const REVIEW_SHA256='8a78fb92b557c7557568a39ce2c31ab31b2c1361558840e77459f2072397c264';
const hash=x=>createHash('sha256').update(x).digest('hex');
const labels=report=>report.Metadata?.ImageConfig?.config?.Labels||{};
const postgresCves=['CVE-2026-14662','CVE-2026-14664','CVE-2026-14668','CVE-2026-14669','CVE-2026-14670',
  'CVE-2026-14671','CVE-2026-14677','CVE-2026-14679','CVE-2026-14680','CVE-2026-15741','CVE-2026-15742',
  'CVE-2026-16239','CVE-2026-18408','CVE-2026-19385','CVE-2026-6464','CVE-2026-6471','CVE-2026-6473',
  'CVE-2026-14663','CVE-2026-14666','CVE-2026-14672','CVE-2026-14678','CVE-2026-14681','CVE-2026-18024',
  'CVE-2026-6470','CVE-2026-14673','CVE-2026-16241','CVE-2026-6469'];
export const POSTGRES_C14_RULES=[
  ...postgresCves.map(cve=>({cve,package:'otziv-postgresql-17',source:'postgresql-17',version:'17.11',reason:'fixed_upstream_source'})),
  ...['CVE-2026-6653','CVE-2026-86140'].map(cve=>({cve,package:'libxml2-16',source:'libxml2',version:'2.15.4',reason:'fixed_upstream_source'})),
  {cve:'CVE-2026-41992',package:'gzip',source:'gzip',version:'1.14+otziv.cve202641992.1',reason:'recorded_upstream_patches'},
  {cve:'CVE-2026-16742',package:'libsystemd0',source:'systemd',version:'257.13-1~deb13u1',reason:'vulnerable_implementation_absent'},
  ...['libtinfo6','ncurses-base'].map(packageName=>({cve:'CVE-2025-69720',package:packageName,source:'ncurses',version:'6.5+20250216-2',reason:'vulnerable_implementation_absent'})),
  ...['CVE-2026-76642','CVE-2026-78408','CVE-2026-78409','CVE-2026-78410'].map(cve=>({cve,package:'libuuid1',source:'util-linux',version:'2.41.5-0+deb13u1',reason:'vulnerable_implementation_absent'})),
];
const key=x=>x.package+'|'+x.version+'|'+x.cve;
const configValue=(config,field)=>['User','WorkingDir'].includes(field)?config?.[field]||'':config?.[field]??null;
const capturedFiles=new Set(['var/lib/dpkg/status','usr/local/share/otziv/upstream-components.json',
  'usr/local/share/otziv/build/pg-config.txt','usr/local/share/otziv/build/gzip-patch-result.json']);
const absent=[/\/(?:infocmp|mount|nsenter|systemd-homed|systemd-homework|perl|sed)$/, /\/lib(?:mount|acl|LLVM)[^/]*\.so/,
  /\/libxml2\.so\.2(?:\.|$)/];
const safePath=value=>typeof value==='string'&&value.length<4096&&!value.startsWith('/')&&!value.includes('\\')&&!value.split('/').includes('..');

// Read Docker's stopped-container export as a stream. No archive extraction and
// no execution of the inspected image. PAX paths are validated before use.
export async function inventoryTar(stream){
  const iterator=stream[Symbol.asyncIterator]();let buffer=Buffer.alloc(0),eof=false;
  async function bytes(n){
    while(buffer.length<n&&!eof){const next=await iterator.next();if(next.done)eof=true;else buffer=Buffer.concat([buffer,Buffer.from(next.value)]);}
    assert.ok(buffer.length>=n,'postgres_tar_truncated');const part=buffer.subarray(0,n);buffer=buffer.subarray(n);return part;
  }
  const inventory={},captured={};let pax={},globalPax={},longName=null,longLink=null,zeroBlocks=0;
  const string=part=>part.toString('utf8').replace(/\0.*$/s,'');
  const number=part=>{assert.ok(!(part[0]&0x80),'postgres_tar_numeric_encoding');const value=string(part).trim();
    assert.match(value||'0',/^[0-7]+$/,'postgres_tar_invalid_number');return parseInt(value||'0',8);};
  while(true){
    const header=await bytes(512);
    if(header.every(x=>x===0)){if(++zeroBlocks===2)break;continue;}
    assert.equal(zeroBlocks,0,'postgres_tar_embedded_end');
    const checksum=number(header.subarray(148,156));let sum=0;for(let i=0;i<512;i++)sum+=(i>=148&&i<156)?32:header[i];
    assert.equal(sum,checksum,'postgres_tar_checksum');
    const type=String.fromCharCode(header[156]||48),size=number(header.subarray(124,136));
    assert.ok(Number.isSafeInteger(size)&&size>=0&&size<=1024*1024*1024,'postgres_tar_size');
    const prefix=string(header.subarray(345,500)),name=string(header.subarray(0,100));
    const path=(longName||pax.path||globalPax.path||(prefix?prefix+'/':'')+name).replace(/^\.\//,'').replace(/\/$/,'');
    const link=longLink||pax.linkpath||globalPax.linkpath||string(header.subarray(157,257));
    assert.ok(!path||safePath(path),'postgres_tar_path');
    const extended=['x','g','L','K'].includes(type),saved=[];let remaining=size,first=Buffer.alloc(0),digest=createHash('sha256');
    while(remaining){const chunk=await bytes(Math.min(remaining,65536));remaining-=chunk.length;
      if(first.length<4)first=Buffer.concat([first,chunk.subarray(0,4-first.length)]);digest.update(chunk);
      if(extended||capturedFiles.has(path)){assert.ok(size<=2*1024*1024,'postgres_tar_metadata_large');saved.push(chunk);}}
    if(size%512)await bytes(512-size%512);
    if(extended){
      const content=Buffer.concat(saved);
      if(type==='L')longName=string(content).replace(/\n$/,'');else if(type==='K')longLink=string(content).replace(/\n$/,'');
      else{const values={};let offset=0;while(offset<content.length){const space=content.indexOf(32,offset);assert.ok(space>offset,'postgres_tar_pax');
        const length=Number(content.subarray(offset,space).toString());assert.ok(Number.isSafeInteger(length)&&length>space-offset+2&&offset+length<=content.length,'postgres_tar_pax');
        const record=content.subarray(space+1,offset+length-1).toString('utf8'),equals=record.indexOf('=');assert.ok(equals>0,'postgres_tar_pax');
        values[record.slice(0,equals)]=record.slice(equals+1);offset+=length;}
        if(type==='g')globalPax={...globalPax,...values};else pax=values;}
      continue;
    }
    pax={};longName=null;longLink=null;
    if(type==='5'||!path)continue;
    assert.ok(['0','1','2'].includes(type),'postgres_tar_unsupported_entry');assert.ok(!Object.hasOwn(inventory,path),'postgres_tar_duplicate_path');
    const mode=number(header.subarray(100,108));
    if(type==='0')inventory[path]={kind:'file',mode,sha256:digest.digest('hex'),elf:first.equals(Buffer.from([0x7f,69,76,70])),script:first.subarray(0,2).toString()==='#!'};
    else{assert.ok(link.length>0&&link.length<4096&&!link.includes('\\')&&!link.includes('\0'),'postgres_tar_link');
      // Relative ../ aliases are normal in Debian. They are recorded literally,
      // never followed or extracted, and must match the reviewed target exactly.
      inventory[path]={kind:type==='1'?'hardlink':'symlink',mode,target:link};}
    if(capturedFiles.has(path)){assert.equal(type,'0','postgres_tar_metadata_not_file');captured[path]=Buffer.concat(saved).toString('utf8');}
  }
  assert.ok(Object.keys(inventory).length>100,'postgres_tar_empty');return {inventory,captured};
}

const canonicalSources=components=>components.map(({name,package:packageName,sourcePackage,version,packageVersion,purl,cpe,url,sha256,patches=[]})=>
  ({name,package:packageName,sourcePackage:sourcePackage||name,version,packageVersion:packageVersion||version,purl,cpe,url,sha256,patches}));
export function runtimeIdentity(observed){
  const {inventory,captured}=observed;
  for(const path of Object.keys(inventory))assert.ok(!absent.some(pattern=>pattern.test('/'+path)),'postgres_affected_implementation_present');
  const payload=Object.fromEntries(Object.entries(inventory).filter(([path,item])=>item.elf||item.script||item.kind!=='file'||
    path.startsWith('usr/local/pgsql/')||path.startsWith('usr/local/lib/')||
    path.startsWith('etc/')&&!['etc/hostname','etc/hosts','etc/resolv.conf'].includes(path)));
  for(const path of ['usr/local/pgsql/bin/postgres','usr/local/pgsql/bin/psql','usr/local/bin/gzip','usr/local/lib/libxml2.so.16.1.4',
    'usr/local/bin/docker-entrypoint.sh'])assert.ok(payload[path],'postgres_runtime_component_missing');
  const sources=canonicalSources(JSON.parse(captured['usr/local/share/otziv/upstream-components.json']||'null'));
  const status=(captured['var/lib/dpkg/status']||'').split(/\n\n/).filter(Boolean).map(record=>Object.fromEntries(
    [...record.matchAll(/^([A-Za-z-]+): (.*)$/gm)].map(([,name,value])=>[name,value])));
  const packages=status.map(x=>({package:x.Package,source:(x.Source||x.Package).replace(/ \(.*\)$/,''),version:x.Version,architecture:x.Architecture})).sort((a,b)=>a.package.localeCompare(b.package));
  assert.equal(new Set(packages.map(x=>x.package)).size,packages.length,'postgres_package_duplicate');
  const patch=JSON.parse(captured['usr/local/share/otziv/build/gzip-patch-result.json']||'null');
  assert.equal(patch?.result,'PASS','postgres_gzip_patch_missing');
  assert.equal(patch.originalInBoundsStateResetExit,42,'postgres_gzip_control_missing');assert.equal(patch.fixedInBoundsStateResetExit,0,'postgres_gzip_fix_failed');
  return {paths:Object.keys(inventory).sort(),payload,sources,packages,pgConfig:captured['usr/local/share/otziv/build/pg-config.txt'],gzipPatch:patch};
}
export function validatePostgresObserved(report,inspected,observed,review){
  assert.equal(report.ArtifactType,'container_image','postgres_report_type');
  assert.ok(/^sha256:[a-f0-9]{64}$/.test(report.Metadata?.ImageID||''),'postgres_report_config_missing');
  assert.deepEqual(report.Metadata.ImageConfig?.rootfs?.diff_ids,inspected.RootFS?.Layers,'postgres_image_rootfs_mismatch');
  assert.ok(inspected.RootFS?.Layers?.length,'postgres_image_rootfs_missing');
  assert.equal(labels(report)['com.otziv.reviewed-component'],'postgres','postgres_report_product');
  assert.equal(inspected.Config?.Labels?.['com.otziv.reviewed-component'],'postgres','postgres_image_product');
  for(const field of ['Env','Entrypoint','Cmd','User','WorkingDir']){
    assert.deepEqual(configValue(report.Metadata.ImageConfig?.config,field),configValue(inspected.Config,field),'postgres_config_report_mismatch');
    assert.deepEqual(configValue(inspected.Config,field),configValue(review.containerContract,field),'postgres_container_contract_changed');
  }
  assert.deepEqual(runtimeIdentity(observed),review.runtime,'postgres_runtime_bytes_or_sources_changed');
  const sections=(report.Results||[]).filter(x=>x.Type==='debian'&&x.Class==='os-pkgs');
  assert.equal(sections.length,1,'postgres_scanner_coverage_missing');
  const targets={'postgresql':'/usr/local/pgsql/bin/postgres','gzip':'/usr/local/bin/gzip',
    'libxml2':'/usr/local/lib/libxml2.so.16.1.4','libxslt':'/usr/local/lib/libxslt.so.1.1.45'};
  for(const source of review.runtime.sources){
    const packages=(sections[0].Packages||[]).filter(x=>x.Name===source.package);
    assert.equal(packages.length,1,'postgres_scanner_coverage_missing');const pkg=packages[0];
    assert.equal(pkg.Version+(pkg.Release?'-'+pkg.Release:''),source.packageVersion,'postgres_scanner_version_mismatch');
    assert.equal(pkg.SrcName,source.sourcePackage,'postgres_scanner_source_mismatch');
    assert.equal(pkg.AnalyzedBy,'dpkg','postgres_scanner_coverage_missing');
    assert.ok(pkg.InstalledFiles?.includes(targets[source.name]),'postgres_scanner_file_binding_missing');
  }
  return {imageConfigId:report.Metadata.ImageID,verifiedPayloadFiles:Object.keys(review.runtime.payload).length,
    runtimeIdentitySha256:hash(JSON.stringify(review.runtime)),inspectedBinaryExecuted:false};
}

export function matchingPostgresFindings(report,review){
  const decisions=[];
  for(const [resultIndex,result]of(report.Results||[]).entries()){
    if(result.Class!=='os-pkgs'||result.Type!=='debian')continue;
    for(const [findingIndex,finding]of(result.Vulnerabilities||[]).entries()){
      const rule=POSTGRES_C14_RULES.find(x=>x.cve===finding.VulnerabilityID&&x.package===finding.PkgName&&x.version===finding.InstalledVersion);
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
export async function loadPostgresProof(now=new Date()){
  const bytes=await readFile(new URL('review.json',proofRoot));assert.equal(hash(bytes),REVIEW_SHA256,'postgres_review_changed');
  const review=JSON.parse(bytes);assert.equal(review.schema,'otziv-postgres-c14-review-v1');
  assert.equal(review.status,'REVIEWED_NOT_AFFECTED');assert.equal(review.validUntil,'2027-01-01T00:00:00Z');
  assert.ok(Number.isFinite(now.getTime())&&now>=new Date(review.reviewedAt)&&now<new Date(review.validUntil),'postgres_review_expired');
  assert.deepEqual(review.rules,POSTGRES_C14_RULES,'postgres_review_scope_changed');
  for(const [path,expected]of Object.entries(review.files)){
    assert.ok(safePath(path),'postgres_proof_path');assert.equal(hash(await readFile(new URL(path,proofRoot))),expected,'postgres_proof_changed');
  }
  return {review,reviewSha256:hash(bytes)};
}
export async function inspectPostgresRuntime(immutableImageId,scratch){
  assert.match(immutableImageId,/^sha256:[a-f0-9]{64}$/,'postgres_immutable_image_required');
  const inspected=JSON.parse(await run('docker',['image','inspect',immutableImageId]))[0];
  const owner='otziv-postgres-adjudicate-'+randomUUID(),directory=join(scratch,owner),label='com.otziv.adjudication.owner';
  await mkdir(directory);let container;
  try{
    container=(await run('docker',['create','--name',owner,'--label',`${label}=${owner}`,'--network','none','--entrypoint','/not-executed',immutableImageId])).trim();
    assert.match(container,/^[a-f0-9]{64}$/,'postgres_container_identity');
    const archive=join(directory,'rootfs.tar');await run('docker',['export','--output',archive,container],{timeoutMs:300000});
    return {inspected,observed:await inventoryTar(createReadStream(archive))};
  }finally{
    if(container){assert.equal((await run('docker',['inspect','--format',`{{index .Config.Labels "${label}"}}`,container])).trim(),owner);
      await run('docker',['rm','-v',container]);}
    // Exact fresh child directory; no configured source/volume deletion.
    await rm(directory,{recursive:true,force:true});
  }
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
    return {...base,status:'EXACT_RUNTIME_VERIFIED',reviewSha256,validUntil:review.validUntil,...binding,decisions};
  }catch(error){return {...base,status:'REJECTED',reason:error.message?.match(/^(postgres_[a-z_]+)(?:\n|$)/)?.[1]||'postgres_proof_validation_failed'};}
}
export function effectivePostgresSummary(summary,adjudication){
  const decisions=adjudication?.status==='EXACT_RUNTIME_VERIFIED'?adjudication.decisions:[];
  assert.ok(Array.isArray(decisions)&&new Set(decisions.map(key)).size===decisions.length&&decisions.every(x=>
    x.state==='not_affected'&&POSTGRES_C14_RULES.some(y=>key(x)===key(y)&&x.justification===y.reason)),'postgres_summary_invalid_decisions');
  const fixed=decisions.filter(x=>x.fixedHighOrCritical).length,unfixed=decisions.filter(x=>x.unfixedHighOrCritical).length;
  const effectiveFixed=(summary.effectiveBlockingFixedHighOrCritical??summary.blockingFixedHighOrCritical)-fixed;
  const effectiveUnfixed=(summary.effectiveUnfixedHighOrCritical??summary.unfixedHighOrCritical)-unfixed;
  assert.ok(effectiveFixed>=0&&effectiveUnfixed>=0,'postgres_summary_invalid_counts');
  return {...summary,adjudicatedPostgresFixedHighOrCritical:fixed,adjudicatedPostgresUnfixedHighOrCritical:unfixed,
    effectiveBlockingFixedHighOrCritical:effectiveFixed,effectiveUnfixedHighOrCritical:effectiveUnfixed,
    unresolvedRiskReview:effectiveUnfixed?'REQUIRED':'NONE',
    result:effectiveFixed||effectiveUnfixed||adjudication?.status==='REJECTED'?'FAIL':(summary.result==='FAIL'?'FAIL':'PASS')};
}
