import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {createEvidenceReader,resolveActivationManifest} from '../../../reviewed-image-defaults.mjs';
import {assertPulledImage,validatePublication} from '../../../verify-anonymous-download.mjs';
import {checkedJson,SOURCE_REPOSITORY,verifyRegistryEvidence} from '../../../registry-evidence.mjs';
import {assertPublicationSet} from '../../../reviewed-image-sets.mjs';
import {checkKeycloakRuntimeDependencies} from '../../../keycloak-runtime-dependencies.mjs';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const evidenceName=/^registry-(?:index|amd64-(?:manifest|config)|attestation-[0-9]+(?:-payload-[0-9]+)?)\.json$/;

// Evidence construction only. Complete activation additionally requires the
// independent published database/issuer acceptance, which consumes this proof.
// This function neither changes nor replaces that final deployment gate.
export async function verifyPublicationEvidencePair({entry,root}) {
 const read=await createEvidenceReader(root),baselineBytes=await readFile(resolve(root,'infrastructure/runtime-security/reviewed-images.json'));
 const image=JSON.parse(baselineBytes).images.find(i=>i.component==='keycloak');
 assert.equal(entry.component,'keycloak');
 async function proof(input){const bytes=await read(input.path);assert.equal(hash(bytes),input.sha256);return {bytes,value:JSON.parse(bytes)};}
 const selected=await resolveActivationManifest(image,entry,baselineBytes,proof);
 assert.equal(selected.manifestSet,'c14-keycloak');
 const pub=await proof(entry.publication),anonymous=await proof(entry.anonymous),identity={commit:entry.commit,run:entry.run,attempt:entry.attempt};
 const digest=validatePublication(pub.value,identity,selected.image,hash(selected.manifestBytes),selected.manifestSet);
 const raw=await read(dirname(entry.publication.path).replaceAll('\\','/')+'/vulnerabilities.json');
 assert.deepEqual(checkKeycloakRuntimeDependencies(raw,pub.value.imageId),pub.value.knownRuntimeDependencies);
 assert.equal(entry.reference,pub.value.reference);
 const downloaded=anonymous.value;
 assertPublicationSet(downloaded,selected.manifestSet);
 assert.equal(downloaded.schema,'otziv-anonymous-download-v1');assert.equal(downloaded.result,'PASS');
 for(const key of ['commit','run','attempt'])assert.equal(downloaded[key],identity[key]);
 assert.equal(downloaded.component,'keycloak');assert.equal(downloaded.reference,entry.reference);assert.equal(downloaded.imageId,pub.value.imageId);
 assert.equal(downloaded.sourcePublicationSha256,hash(pub.bytes));
 assert.equal(downloaded.publicDownloadReadiness,'VERIFIED_ANONYMOUS_DIGEST_PULL');
 assert.equal(downloaded.platform,'linux/amd64');assert.equal(downloaded.imageAbsentBeforePull,true);
 assert.equal(downloaded.accountCredentialsUsed,false);assert.equal(downloaded.dockerCredentialHelpersAvailable,false);
 assert.equal(downloaded.dockerTransport,'unix:///var/run/docker.sock');assert.equal(downloaded.pullExitCode,0);
 const expected={source:SOURCE_REPOSITORY,commit:identity.commit,context:selected.image.context,dockerfile:selected.image.dockerfile,dockerfileSha256:selected.image.dockerfileSha256};
 for(const[document,path]of [[pub.value,entry.publication.path],[downloaded,entry.anonymous.path]]){
  const blobs=new Map(),names=new Set();assert.ok(document.attestationEvidence?.artifacts?.length);
  for(const artifact of document.attestationEvidence.artifacts){
   assert.match(artifact.file,evidenceName);assert.ok(!names.has(artifact.file));names.add(artifact.file);
   const bytes=await read(dirname(path).replaceAll('\\','/')+'/'+artifact.file);checkedJson(bytes,artifact);
   if(artifact.file==='registry-amd64-config.json')assert.equal(artifact.digest,pub.value.imageId);
   blobs.set(artifact.digest,bytes);
  }
  const actual=await verifyRegistryEvidence({digest,expected,read:async(kind,d)=>{assert.ok(blobs.has(d));return blobs.get(d);},retain:async()=>{}});
  assert.deepEqual(actual,document.attestationEvidence);
 }
 assert.deepEqual(downloaded.attestationEvidence,pub.value.attestationEvidence);
 assertPulledImage(JSON.parse(await read(dirname(entry.anonymous.path).replaceAll('\\','/')+'/anonymous-image-inspect.json')),pub.value);
 return entry.reference;
}
