import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile,readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import test from 'node:test';
import {createEvidenceReader,validateActivation} from '../../reviewed-image-defaults.mjs';
import {supplementalReviewedSources} from '../../reviewed-image-sets.mjs';
import {summarizeReport} from '../../scan.mjs';
const dir=fileURLToPath(new URL('./',import.meta.url)),root=fileURLToPath(new URL('../../../../',import.meta.url));
const hash=b=>createHash('sha256').update(b).digest('hex'),json=async p=>JSON.parse(await readFile(resolve(dir,p)));
const review=await json('review.json');
test('all retained publication, raw scan and runtime bytes match the complete explicit proof graph',async()=>{
 assert.equal(review.schema,'otziv-published-versity-runtime-v1');assert.equal(review.result,'PASS');
 async function list(path,prefix=''){let out=[];for(const e of await readdir(path,{withFileTypes:true})){const relative=prefix+e.name;out.push(...(e.isDirectory()?await list(resolve(path,e.name),relative+'/'):[relative]));}return out;}
 assert.deepEqual((await list(dir)).filter(p=>p!=='review.json').sort(),Object.keys(review.files).sort());
 for(const [path,sha]of Object.entries(review.files))assert.equal(hash(await readFile(resolve(dir,path))),sha,path);
 for(const [path,sha]of Object.entries(review.sourceFiles))assert.equal(hash(await readFile(resolve(root,path))),sha,path);
 assert.equal(hash(await readFile(resolve(dir,'runtime/executed-runner.mjs.txt'))),review.executedRunnerSha256);
});
test('actual exact-head local-S3 publication validates its scanner hash, full raw counts and anonymous OCI pair',async()=>{
 const entry=await json('activation-candidate.json');assert.equal(entry.commit,'135747397491454711da71155d50a440fb4d7c97');assert.equal(entry.run,'34238888111');assert.equal(entry.attempt,'1');
 assert.equal(await validateActivation(supplementalReviewedSources().find(i=>i.component==='minio'),entry,
  await readFile(resolve(root,'infrastructure/runtime-security/reviewed-images.json')),await createEvidenceReader(root)),review.reference);
 const raw=await json('publication/vulnerabilities.json');assert.equal(raw.Metadata.ImageID,review.imageConfigDigest);
 const counts=summarizeReport(raw);assert.equal(counts.high,0);assert.equal(counts.critical,0);
 for(const a of await json('artifact-verification.json')){assert.equal(a.apiDigest,a.actualDigest);assert.equal(a.verified,true);assert.equal(a.commit,entry.commit);assert.equal(a.run,entry.run);}
});
test('the actual published gateway and MC complete all36 bounded S3 checks on new storage and clean up',async()=>{
 const result=await json('runtime/result.json');assert.equal(result.result,'PASS');assert.equal(result.publishedServer,review.reference);
 assert.equal(result.image,'ghcr.io/claidd/otziv-security@sha256:d163502c0d23dd3d76ec9fab63d9697317c9a2aaa39d1a3d783a70044bb803c0');
 assert.equal(result.checks.length,36);assert.equal(new Set(result.checks).size,36);
 for(const name of ['compose_init_idempotent_0','compose_init_idempotent_1','public_anonymous_http_bytes','private_anonymous_http_403','two_distinct_object_versions','each_version_readable','object_copy_restore_bytes','same_version_ids_and_bytes_after_restart','copied_object_durable_after_restart','server_graceful_stop'])assert.ok(result.checks.includes(name),name);
 assert.equal(result.limitsApplied.clientMemoryMiB,384);assert.equal(result.limitsApplied.clientGOMEMLIMIT,'256MiB');
 assert.equal(result.cleanup.length,3);assert.ok(result.cleanup.every(c=>c.result==='PASS'));
 const inspected=await json('published-image-verification.json');assert.equal(inspected.result,'PASS');assert.equal(inspected.binarySha256,review.binarySha256);assert.equal(inspected.reference,review.reference);
 assert.equal(inspected.binarySha256,'a44bb13582e9f62da5cf6f0c24285b7f575c68cab2959e12e547d3841c172bc5');
});
