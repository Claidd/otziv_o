import assert from 'node:assert/strict';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {gzipSync} from 'node:zlib';
import {fileURLToPath} from 'node:url';
import {inspectPostgresRuntime} from '../interactive-latency-secure-worktree/infrastructure/runtime-security/postgres-c14-adjudication.mjs';
import {adjudicatePostgresC16Observed,POSTGRES_C16_REVIEW_SHA256} from '../interactive-latency-secure-worktree/infrastructure/runtime-security/postgres-c16-adjudication.mjs';
import {readReviewedImageSet} from '../interactive-latency-secure-worktree/infrastructure/runtime-security/reviewed-image-sets.mjs';
import {validatePublication,assertPulledImage} from '../interactive-latency-secure-worktree/infrastructure/runtime-security/verify-anonymous-download.mjs';
import {verifyRegistryEvidence,SOURCE_REPOSITORY} from '../interactive-latency-secure-worktree/infrastructure/runtime-security/registry-evidence.mjs';
import {run} from '../interactive-latency-secure-worktree/infrastructure/recovery/process.mjs';
import {combinedScanSummary} from '../interactive-latency-secure-worktree/infrastructure/runtime-security/scan-verdict.mjs';
import {summarizeReport,TRIVY_IMAGE} from '../interactive-latency-secure-worktree/infrastructure/runtime-security/scan.mjs';
const D=new URL('./',import.meta.url),P=new URL('c16-publication/',D),W=new URL('../interactive-latency-secure-worktree/',D);
const hash=b=>createHash('sha256').update(b).digest('hex');
const json=async path=>JSON.parse(await readFile(new URL(path,P)));
const publication=await json('publication/publication.json'),anonymous=await json('anonymous/anonymous-download.json');
const set=await readReviewedImageSet(fileURLToPath(W),'c16-postgres'),image=set.manifest.images[0];
const identity=Object.fromEntries(['commit','run','attempt'].map(k=>[k,publication[k]]));
const digest=validatePublication(publication,identity,image,set.sha256,'c16-postgres');
const expected={source:SOURCE_REPOSITORY,commit:identity.commit,context:image.context,dockerfile:image.dockerfile,dockerfileSha256:image.dockerfileSha256};
for(const [document,directory]of [[publication,'publication'],[anonymous,'anonymous']]){
 const blobs=new Map();
 for(const item of document.attestationEvidence.artifacts){
  const b=await readFile(new URL(directory+'/'+item.file,P));assert.equal('sha256:'+hash(b),item.digest);blobs.set(item.digest,b);
 }
 const result=await verifyRegistryEvidence({digest,expected,read:async(kind,id)=>{assert.ok(blobs.has(id));return blobs.get(id);},retain:async()=>{}});
 assert.deepEqual(result,document.attestationEvidence);
}
assert.equal(anonymous.sourcePublicationSha256,hash(await readFile(new URL('publication/publication.json',P))));
assert.deepEqual(anonymous.attestationEvidence,publication.attestationEvidence);
assertPulledImage(await json('anonymous/anonymous-image-inspect.json'),publication);
const id=(await run('docker',['image','inspect','--format','{{.Id}}',publication.reference])).trim();
const scratch=fileURLToPath(new URL('published-inspection-scratch',D));await mkdir(scratch,{recursive:true});
const inspected=await inspectPostgresRuntime(id,scratch);
const config=await json('publication/registry-amd64-config.json');
assert.equal('sha256:'+hash(await readFile(new URL('publication/registry-amd64-config.json',P))),publication.imageId);
assert.deepEqual(inspected.inspected.Config,config.config);
assert.deepEqual(inspected.inspected.RootFS.Layers,config.rootfs.diff_ids);
const raw=await readFile(new URL('publication/vulnerabilities.json',P));
const receipt=await adjudicatePostgresC16Observed(JSON.parse(raw),raw,id,inspected.inspected,inspected.observed);
const fromCI=(await json('publication/vulnerabilities.adjudications.json')).postgres;
const withoutLocalId=x=>{const {immutableImageId,...rest}=x;return rest;};
assert.deepEqual(withoutLocalId(receipt),withoutLocalId(fromCI));
const summary={...combinedScanSummary(summarizeReport(JSON.parse(raw)),{postgres:receipt}),scannerImage:TRIVY_IMAGE};
assert.deepEqual(summary,publication.security);assert.equal(summary.result,'PASS');
await writeFile(new URL('published-observed-runtime.json.gz',P),gzipSync(JSON.stringify(inspected)));
await writeFile(new URL('replayed-adjudication.json',P),JSON.stringify(receipt,null,2)+'\n');
const verification={schema:'otziv-postgres-c16-published-verification-v1',result:'PASS',...identity,reference:publication.reference,
 imageConfigId:publication.imageId,actualLocalId:id,sourceReviewSha256:POSTGRES_C16_REVIEW_SHA256,
 rawReportSha256:hash(raw),runtimeIdentitySha256:receipt.runtimeIdentitySha256,
 registryAndAnonymousEvidenceVerified:true,independentStoppedExportVerified:true,inspectedBinaryExecuted:false,
 security:summary,observedAt:new Date().toISOString()};
await writeFile(new URL('verification.json',P),JSON.stringify(verification,null,2)+'\n');
console.log(JSON.stringify(verification));
