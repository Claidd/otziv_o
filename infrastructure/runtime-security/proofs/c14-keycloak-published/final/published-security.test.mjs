import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile,readdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
import {summarizeReport,TRIVY_IMAGE} from '../../../scan.mjs';
import {combinedScanSummary} from '../../../scan-verdict.mjs';
import {checkKeycloakRuntimeDependencies} from '../../../keycloak-runtime-dependencies.mjs';
import {verifyPublicationEvidencePair} from './verify-publication-pair.mjs';
const dir=fileURLToPath(new URL('./',import.meta.url)),root=fileURLToPath(new URL('../../../../../',import.meta.url));
const read=path=>readFile(resolve(dir,path)),json=async path=>JSON.parse(await read(path)),hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const review=await json('review.json');

test('the complete final proof and nine frozen source inputs remain byte-bound',async()=>{
 async function list(path,prefix=''){let rows=[];for(const item of await readdir(path,{withFileTypes:true})){const name=prefix+item.name;rows.push(...(item.isDirectory()?await list(resolve(path,item.name),name+'/'):[name]));}return rows;}
 assert.equal(review.schema,'otziv-keycloak-published-independent-security-review-v1');assert.equal(review.result,'PASS');
 assert.deepEqual((await list(dir)).filter(path=>path!=='review.json').sort(),Object.keys(review.files).sort());
 for(const[path,sha]of Object.entries(review.files))assert.equal(hash(await read(path)),sha,path);
 assert.equal(Object.keys(review.sourceFiles).length,9);
 for(const[path,sha]of Object.entries(review.sourceFiles))assert.equal(hash(await readFile(resolve(root,path))),sha,path);
});

test('both immutable GitHub archives match exact successful run and attempt receipts',async()=>{
 const run=await json('hosted-run.json'),jobs=await json('hosted-jobs.json');
 assert.equal(run.id,34249337258);assert.equal(run.head_sha,'ca1dcaa0b0f8c063f8e541c1623a3f61d68e749e');assert.equal(run.run_attempt,1);assert.equal(run.event,'workflow_dispatch');
 for(const[name,folder]of [['reviewed-publication-keycloak-attempt-1','publication'],['anonymous-download-keycloak-attempt-1','anonymous']]){
  const receipt=await json(folder+'-artifact-verification.json');assert.equal(receipt.name,name);assert.equal(receipt.run,String(run.id));assert.equal(receipt.attempt,'1');assert.equal(receipt.commit,run.head_sha);
  assert.equal(receipt.apiDigest,'sha256:'+hash(await read(name+'.zip')));assert.equal(receipt.actualDigest,receipt.apiDigest);assert.equal(receipt.verified,true);
  assert.equal(jobs.find(j=>j.id===receipt.jobId)?.conclusion,'success');
  for(const[path,sha]of Object.entries(receipt.files))assert.equal(hash(await read(folder+'/'+path)),sha,path);
 }
});

test('the exact publication and anonymous pair passes existing fail-closed registry validation',async()=>{
 const entry=await json('activation-candidate.json');
 assert.equal(entry.commit,'ca1dcaa0b0f8c063f8e541c1623a3f61d68e749e');assert.equal(entry.run,'34249337258');assert.equal(entry.attempt,'1');
 assert.equal(await verifyPublicationEvidencePair({entry,root}),review.image);
 const paired=await json('paired-publication-verification.json');assert.equal(paired.result,'PASS');assert.equal(paired.config,review.config);assert.equal(paired.reference,review.image);
});

test('full raw OS and Java findings independently reproduce the zero-HIGH/CRITICAL verdict',async()=>{
 const raw=await read('publication/vulnerabilities.json'),report=JSON.parse(raw),pub=await json('publication/publication.json');
 assert.equal(hash(raw),review.rawReportSha256);assert.equal(report.Metadata.ImageID,review.config);
 assert.equal('sha256:'+hash(await read('publication/registry-amd64-config.json')),review.config);
 const summary={...combinedScanSummary(summarizeReport(report,true),{}),scannerImage:TRIVY_IMAGE};assert.deepEqual(summary,pub.security);
 assert.equal(summary.high,0);assert.equal(summary.critical,0);assert.equal(summary.result,'PASS');
 assert.equal(report.Results.flatMap(r=>r.Vulnerabilities||[]).length,49);
 assert.equal(report.Results.find(r=>r.Type==='ubuntu').Packages.length,143);assert.equal(report.Results.find(r=>r.Type==='jar').Packages.length,512);
 assert.deepEqual(checkKeycloakRuntimeDependencies(raw,review.config),pub.knownRuntimeDependencies);
 const sbom=await json('publication/vulnerabilities.sbom.cdx.json');assert.equal(sbom.bomFormat,'CycloneDX');assert.equal(sbom.components.length,656);
});

test('complete stopped rootfs and vendor payload comparison binds this published OCI config',async()=>{
 const comparison=await json('runtime/comparison.json'),inventory=await json('runtime/published-rootfs-inventory.json');
 const localDir=resolve(root,'infrastructure/keycloak/security-generation/c14-migration-fix/proofs/security-v3');
 const local=JSON.parse(await readFile(resolve(localDir,'target-rootfs-inventory.json')));
 assert.equal(hash(await readFile(resolve(localDir,'review.json'))),review.sourceReviewSha256);
 assert.equal(hash(await read('runtime/published-rootfs-inventory.json')),review.runtimeInventorySha256);
 assert.equal(hash(await read('runtime/comparison.json')),review.runtimeComparisonSha256);
 assert.equal(comparison.image,review.image);assert.equal(comparison.config,review.config);assert.equal(comparison.result,'PASS');
 assert.equal(comparison.actualPulledRootfsMatchesRegistry,true);assert.equal(comparison.actualPulledConfigurationMatchesRegistry,true);
 const excluded=['etc/hosts','etc/hostname','etc/resolv.conf'];assert.deepEqual(comparison.dockerGeneratedFilesExcluded,excluded);
 const changed=[...new Set([...Object.keys(local),...Object.keys(inventory)])].sort().filter(p=>!excluded.includes(p)&&JSON.stringify(local[p])!==JSON.stringify(inventory[p]));
 assert.deepEqual(changed,comparison.differences.map(r=>r.path));assert.deepEqual(comparison.unexpectedPaths,[]);
 assert.ok(changed.every(p=>['opt/keycloak/lib/quarkus/generated-bytecode.jar','opt/keycloak/lib/quarkus/transformed-bytecode.jar','opt/keycloak/lib/quarkus/quarkus-application.dat'].includes(p)));
 assert.equal(comparison.totalJarFiles,476);assert.equal(Object.keys(comparison.patchedVendorJarsByteEqual).length,2);assert.ok(Object.values(comparison.patchedVendorJarsByteEqual).every(v=>v===true));
 assert.deepEqual(await read('runtime/otziv-realm-migration-provenance.json'),await readFile(resolve(localDir,'otziv-realm-migration-provenance.json')));
 assert.equal(hash(await read('executed-compare-payload.py.txt')),comparison.executedScriptSha256);
});

test('changed augmentation requires a separate actual-published runtime replay',async()=>{
 const comparison=await json('runtime/comparison.json');
 assert.equal(comparison.localV3RuntimeReceiptTransferAllowed,false);assert.equal(comparison.publishedRuntimeReplayRequired,true);
 assert.equal(review.runtimeTestsIncluded,false);assert.equal(review.productionActivated,false);
 assert.equal(review.runtimeInventory,'runtime/published-rootfs-inventory.json');assert.equal(review.runtimeComparison,'runtime/comparison.json');
 assert.equal(review.anonymousPublicationPairVerified,true);
});
