import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, mkdir, mkdtemp, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASELINE_MANIFEST_SHA256, reviewedImageSet, selectedReviewedImageSet, validateReviewedImageSet, readReviewedImageSet, publicationSetFields } from './reviewed-image-sets.mjs';
import { BUILDKIT, REPOSITORY, SBOM_GENERATOR, validateManifest } from './publish-reviewed-images.mjs';
import { validatePublication, verifyAnonymousDownload } from './verify-anonymous-download.mjs';
import { validateActivation, validateRepositoryDefaults } from './reviewed-image-defaults.mjs';
import { SOURCE_REPOSITORY, verifyRegistryEvidence } from './registry-evidence.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const baselineBytes = await readFile(new URL('./reviewed-images.json', import.meta.url));
const baseline = JSON.parse(baselineBytes);
const original = baseline.images.find(item => item.component === 'phpmyadmin');
const selected = reviewedImageSet('c12-phpmyadmin');
const context = 'infrastructure/runtime-security/builds/phpmyadmin-alpine';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const bytes = value => Buffer.from(JSON.stringify(value));

function candidate() {
  return { schema: baseline.schema, repository: baseline.repository, publicationSet: selected.name,
    baselineManifestSha256: BASELINE_MANIFEST_SHA256, images: [{ component: 'phpmyadmin', context,
      dockerfile: context + '/Dockerfile', dockerfileSha256: 'a'.repeat(64), platform: 'linux/amd64',
      sourceBeforeRef: original.sourceBeforeRef, defaultReferencesBefore: structuredClone(original.defaultReferencesBefore),
      candidateBaseRef: 'alpine@sha256:' + 'b'.repeat(64), buildArgs: {} }] };
}

test('omitted selector preserves exact C7 path, bytes and twelve-component inventory', async () => {
  assert.deepEqual(selectedReviewedImageSet({}), reviewedImageSet('baseline'));
  assert.deepEqual(publicationSetFields(), {});
  const loaded = await readReviewedImageSet(root);
  assert.deepEqual(loaded.bytes, baselineBytes);
  assert.equal(loaded.sha256, BASELINE_MANIFEST_SHA256);
  assert.equal(validateManifest(loaded.manifest).length, 12);
  assert.equal((await validateRepositoryDefaults(root)).length, 32);
});

for (const name of ['', '../baseline', '../reviewed-images.json', 'infrastructure/runtime-security/reviewed-images.json',
  'C:/private.json', '/private.json', 'c12-phpmyadmin/../baseline', 'C12-PHPMYADMIN', 'unknown', null]) {
  test('unrecognised selector fails closed: ' + JSON.stringify(name), () => {
    assert.throws(() => reviewedImageSet(name), /reviewed_image_set_unknown/);
    assert.throws(() => selectedReviewedImageSet({ OTZIV_REVIEWED_IMAGE_SET: name }), /reviewed_image_set_unknown/);
  });
}

test('the only alternate set has one PMA image and the unchanged historical mapping', () => {
  const value = validateReviewedImageSet(selected.name, bytes(candidate()), baselineBytes);
  assert.deepEqual(validateManifest(value).map(item => item.component), ['phpmyadmin']);
  assert.deepEqual(value.images[0].defaultReferencesBefore, original.defaultReferencesBefore);
  assert.equal(value.images[0].sourceBeforeRef, original.sourceBeforeRef);
});

for (const [name, mutate] of Object.entries({
  'baseline bytes': value => { value.baseline = Buffer.concat([baselineBytes, Buffer.from('\n')]); },
  'set discriminator': value => { value.manifest.publicationSet = 'baseline'; },
  'source baseline hash': value => { value.manifest.baselineManifestSha256 = '0'.repeat(64); },
  'multiple images': value => { value.manifest.images.push(structuredClone(value.manifest.images[0])); },
  'foreign component': value => { value.manifest.images[0].component = 'postgres'; },
  'context traversal': value => { value.manifest.images[0].context += '/../ordinary'; },
  'old Dockerfile': value => { value.manifest.images[0].dockerfile = original.dockerfile; },
  'missing Dockerfile hash': value => { delete value.manifest.images[0].dockerfileSha256; },
  'platform': value => { value.manifest.images[0].platform = 'linux/arm64'; },
  'prepare command': value => { value.manifest.images[0].prepare = { kind: 'run' }; },
  'original source pin': value => { value.manifest.images[0].sourceBeforeRef = value.manifest.images[0].candidateBaseRef; },
  'service coverage': value => { value.manifest.images[0].defaultReferencesBefore.pop(); },
})) test('named set rejects changed ' + name, () => {
  const value = { manifest: candidate(), baseline: baselineBytes }; mutate(value);
  assert.throws(() => validateReviewedImageSet(selected.name, bytes(value.manifest), value.baseline), /reviewed_image_set_/);
});

test('loader selects the fixed C12 file only and does not fall back when it is absent', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'otziv-reviewed-set-'));
  try {
    await mkdir(join(directory, 'infrastructure/runtime-security'), { recursive: true });
    await writeFile(join(directory, reviewedImageSet().path), baselineBytes);
    await assert.rejects(readReviewedImageSet(directory, selected.name), /ENOENT/);
    const input = bytes(candidate()); await writeFile(join(directory, selected.path), input);
    const loaded = await readReviewedImageSet(directory, selected.name);
    assert.deepEqual(loaded.bytes, input); assert.equal(loaded.sha256, hash(input));
    assert.deepEqual((await readReviewedImageSet(directory)).bytes, baselineBytes);
  } finally {
    const actual = await realpath(directory), inside = relative(await realpath(tmpdir()), actual);
    assert.ok(inside.startsWith('otziv-reviewed-set-') && !inside.includes(sep));
    await rm(actual, { recursive: true, force: true });
  }
});

// Synthetic re-binding of the existing real BuildKit scratch fixture exercises
// the full OCI/SLSA parsers. It is not a published C12 artifact or manifest.
async function pair() {
  const directory = new URL('./fixtures/provenance-scratch/', import.meta.url);
  const fixture = JSON.parse(await readFile(new URL('fixture.json', directory)));
  const files = new Map(await Promise.all(fixture.result.artifacts.map(async a => [a.file, await readFile(new URL(a.file, directory))])));
  const expected = { ...fixture.expected, source: SOURCE_REPOSITORY, context, dockerfile: context + '/Dockerfile' };
  const provenance = JSON.parse(files.get('registry-attestation-0-payload-1.json'));
  const vcs = provenance.predicate.runDetails.metadata.buildkit_metadata.vcs;
  Object.assign(vcs, { source: SOURCE_REPOSITORY, 'localdir:context': context, 'localdir:dockerfile': context });
  for (const [key,value] of Object.entries(vcs)) provenance.predicate.buildDefinition.externalParameters.request.root.request.args['vcs:' + key] = value;
  const attestation = JSON.parse(files.get('registry-attestation-0.json'));
  const index = JSON.parse(files.get('registry-index.json'));
  function pack(name, value, descriptor) { const b = bytes(value); files.set(name,b); Object.assign(descriptor,{digest:'sha256:'+hash(b),size:b.length}); }
  pack('registry-attestation-0-payload-1.json',provenance,attestation.layers.find(a => a.annotations['in-toto.io/predicate-type']==='https://slsa.dev/provenance/v1'));
  pack('registry-attestation-0.json',attestation,index.manifests.find(a => a.platform.os==='unknown'));
  const descriptor={}; pack('registry-index.json',index,descriptor);
  const blobs=new Map([...files.values()].map(b=>['sha256:'+hash(b),b]));
  const evidence=await verifyRegistryEvidence({digest:descriptor.digest,expected,read:async(kind,digest)=>blobs.get(digest),retain:async()=>{}});
  const manifest=candidate();manifest.images[0].dockerfileSha256=expected.dockerfileSha256;
  const manifestBytes=bytes(manifest), image=manifest.images[0], identity={commit:expected.commit,run:'123',attempt:'1'};
  const publication={schema:'otziv-reviewed-image-publication-v1',...identity,...publicationSetFields(selected.name),component:'phpmyadmin',platform:'linux/amd64',productionActivated:false,
    sourceBeforeRef:image.sourceBeforeRef,dockerfileSha256:image.dockerfileSha256,manifestSha256:hash(manifestBytes),builder:BUILDKIT,sbomGenerator:SBOM_GENERATOR,
    imageId:evidence.artifacts.find(a=>a.file==='registry-amd64-config.json').digest,tag:`${REPOSITORY}:phpmyadmin-${identity.commit}-123-1`,reference:REPOSITORY+'@'+descriptor.digest,
    result:'PASS',security:{result:'PASS',effectiveBlockingFixedHighOrCritical:0,unresolvedRiskReview:'NONE'},attestationEvidence:evidence};
  const inspected=[{Id:publication.imageId,Os:'linux',Architecture:'amd64',RepoDigests:[publication.reference],Config:{Labels:{'com.otziv.publication.source':SOURCE_REPOSITORY,'com.otziv.publication.revision':identity.commit,'com.otziv.reviewed-component':'phpmyadmin'}}}];
  const retained=new Map(), calls=[];
  let inspections=0;
  const input={publicationBytes:bytes(publication),identity,image,manifestSha256:hash(manifestBytes),manifestSet:selected.name,expected,
    readPublicationArtifact:async name=>files.get(name),readAnonymous:async(kind,digest)=>{calls.push('registry');return blobs.get(digest);},retain:async(name,b)=>retained.set(name,b),
    docker:async args=>{calls.push(args);if(args[0]==='info')return{code:0,stdout:'fixture',stderr:''};if(args[0]==='pull')return{code:0,stdout:'fixture',stderr:''};
      if(args[1]==='ls')return{code:0,stdout:'',stderr:''};if(args[1]==='inspect')return++inspections===1?{code:1,stdout:'',stderr:'fixture absent'}:{code:0,stdout:JSON.stringify(inspected),stderr:''};throw Error('unexpected_fixture_call');}};
  return{manifest,manifestBytes,image,identity,publication,files,retained,calls,input,inspected};
}

test('actual anonymous verifier binds the selected manifest and the complete immutable OCI pair',async()=>{
  const p=await pair(), result=await verifyAnonymousDownload(p.input);
  assert.equal(result.result,'PASS'); assert.equal(result.manifestSet,selected.name);assert.equal(result.manifestPath,selected.path);
  assert.equal(result.sourcePublicationSha256,hash(p.input.publicationBytes));assert.ok(p.calls.some(c=>Array.isArray(c)&&c[0]==='pull'));
});

for(const[name,mutate]of Object.entries({
  'other selected set':p=>{p.input.manifestSet='baseline';},
  'missing record set':p=>{delete p.publication.manifestSet;},
  'null record set':p=>{p.publication.manifestSet=null;},
  'record path traversal':p=>{p.publication.manifestPath='../reviewed-images.json';},
  'different manifest bytes':p=>{p.input.manifestSha256=hash(Buffer.concat([p.manifestBytes,Buffer.from('\n')]));},
  'foreign component':p=>{p.publication.component='nginx';},
}))test('anonymous rejects '+name+' before transport',async()=>{
  const p=await pair();mutate(p);p.input.publicationBytes=bytes(p.publication);
  await assert.rejects(verifyAnonymousDownload(p.input),/anonymous_publication_/);assert.deepEqual(p.calls,[]);
});

test('legacy baseline validation cannot silently accept a C12 publication',async()=>{
  const p=await pair();assert.throws(()=>validatePublication(p.publication,p.identity,p.image,hash(p.manifestBytes)),/manifest_set_mismatch/);
});

async function activation() {
  const p=await pair(), anonymous=await verifyAnonymousDownload(p.input), store=new Map();
  const pp='infrastructure/runtime-security/proofs/test-c12/publication/publication.json',ap='infrastructure/runtime-security/proofs/test-c12/anonymous/anonymous-download.json';
  const entry={component:'phpmyadmin',reference:p.publication.reference,...p.identity,manifest:{path:selected.path,sha256:hash(p.manifestBytes)}};
  function refresh(){const pb=bytes(p.publication);store.set(pp,pb);anonymous.sourcePublicationSha256=hash(pb);store.set(ap,bytes(anonymous));entry.publication={path:pp,sha256:hash(pb)};entry.anonymous={path:ap,sha256:hash(store.get(ap))};}
  store.set(selected.path,p.manifestBytes);refresh();
  for(const[name,b]of p.files)for(const group of ['publication','anonymous'])store.set(`infrastructure/runtime-security/proofs/test-c12/${group}/${name}`,b);
  store.set('infrastructure/runtime-security/proofs/test-c12/anonymous/anonymous-image-inspect.json',bytes(p.inspected));
  return{...p,entry,anonymous,refresh,store,read:async path=>{assert.ok(store.has(path),'fixture_path_missing');return store.get(path);}};
}

test('versioned PMA activation uses the C12 context while preserving C7 service coverage',async()=>{
  const p=await activation();assert.equal(await validateActivation(original,p.entry,baselineBytes,p.read),p.publication.reference);
});

for(const[name,mutate]of Object.entries({
  'missing manifest proof':p=>{delete p.entry.manifest;},
  'arbitrary manifest path':p=>{p.entry.manifest.path='infrastructure/runtime-security/unreviewed.json';},
  'extra proof selector':p=>{p.entry.manifest.other='unreviewed';},
  'manifest hash':p=>{p.entry.manifest.sha256='0'.repeat(64);},
  'different authenticated manifest bytes':p=>{const b=Buffer.concat([p.manifestBytes,Buffer.from('\n')]);p.store.set(selected.path,b);p.entry.manifest.sha256=hash(b);},
  'anonymous selected set':p=>{p.anonymous.manifestSet='baseline';p.refresh();},
}))test('versioned activation rejects '+name,async()=>{
  const p=await activation();mutate(p);await assert.rejects(validateActivation(original,p.entry,baselineBytes,p.read),/activation_|anonymous_publication_/);
});

test('versioned manifest never authorizes another component',async()=>{
  const p=await activation();p.entry.component='alloy';await assert.rejects(validateActivation({...original,component:'alloy'},p.entry,baselineBytes,p.read),/activation_versioned_manifest_component/);
});

test('workflow uses the same fixed choice in exactly inventory, publication and anonymous jobs',async()=>{
  const workflow=await readFile(new URL('../../.github/workflows/quality-gates.yml',import.meta.url),'utf8');
  assert.match(workflow,/reviewed-image-set:\s+description:[^\n]+\s+type: choice\s+required: false\s+default: baseline\s+options:\s+- baseline\s+- c12-phpmyadmin/);
  assert.equal((workflow.match(/OTZIV_REVIEWED_IMAGE_SET:/g)||[]).length,3);
  for(const name of ['reviewed-image-inventory','reviewed-image-publication','reviewed-image-anonymous-download']){
    const job=workflow.slice(workflow.indexOf('  '+name+':')).split(/\n  [a-z][a-z-]+:/)[0];
    assert.match(job,/OTZIV_REVIEWED_IMAGE_SET: \$\{\{ inputs\.reviewed-image-set \|\| 'baseline' \}\}/);
  }
});
