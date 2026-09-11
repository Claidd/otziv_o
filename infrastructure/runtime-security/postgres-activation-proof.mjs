import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {gunzipSync} from 'node:zlib';
import {loadPostgresProof,matchingPostgresFindings,validatePostgresObserved} from './postgres-c14-adjudication.mjs';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
import {buildTriage} from './triage-report.mjs';
import {assertPublicationSet,validateReviewedImageSet} from './reviewed-image-sets.mjs';

const ROOT='infrastructure/runtime-security/proofs/c14-postgres-published/';
const SOURCE_ROOT='infrastructure/runtime-security/proofs/c14-postgres/';
// This anchor covers the independently replayed export of the actual published
// image. A new publication needs its own reviewed inspection evidence. Rehashing
// a caller-supplied PASS receipt cannot establish executable payload identity.
const PUBLISHED_PROOF_SHA256='56e062f6a199fa655fcc631f220c2da903acfcaaa1fe9991ecf2bf2054cef5af';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const safePath=path=>typeof path==='string'&&path.length>0&&!path.startsWith('/')&&!path.includes('\\')&&
  !path.includes(':')&&path.split('/').every(part=>part&&part!=='.'&&part!=='..');

export async function validatePostgresActivationScan(publication,publicationPath,read){
  assert.equal(typeof read,'function','postgres_activation_reader');
  assert.equal(publication?.component,'postgres','postgres_activation_component');
  assert.equal(publicationPath,ROOT+'publication/publication.json','postgres_activation_publication_path');
  assertPublicationSet(publication,'c14-postgres');
  assert.match(publication.imageId||'',/^sha256:[a-f0-9]{64}$/,'postgres_activation_publication_image');
  assert.match(publication.reference||'',/^ghcr\.io\/claidd\/otziv-security@sha256:[a-f0-9]{64}$/,'postgres_activation_reference');
  const input=structuredClone(publication),cache=new Map();
  // All comparisons use the same bytes even if a file changes between reads.
  async function bytes(path){
    assert.ok(safePath(path),'postgres_activation_evidence_path');
    if(!cache.has(path))cache.set(path,Promise.resolve(read(path)).then(value=>Buffer.from(value)));
    return cache.get(path);
  }
  const json=async path=>JSON.parse(await bytes(path));
  const directory=ROOT+'publication/';
  const [rawBytes,receipt,triage]=await Promise.all([
    bytes(directory+'vulnerabilities.json'),json(directory+'vulnerabilities.adjudications.json'),json(directory+'vulnerabilities.triage.json')]);
  const raw=JSON.parse(rawBytes),rawHash=hash(rawBytes),allowedIds=[input.imageId,input.reference.split('@')[1]];
  assert.equal(receipt.schema,'otziv-image-adjudication-v1','postgres_activation_receipt_schema');
  assert.equal(receipt.rawReportSha256,rawHash,'postgres_activation_raw_hash');
  assert.equal(receipt.imageConfigId,input.imageId,'postgres_activation_receipt_image');
  assert.ok(allowedIds.includes(receipt.immutableImageId),'postgres_activation_inspected_image');
  assert.equal(receipt.rawReportModified,false,'postgres_activation_raw_modified');
  assert.equal(receipt.status,'NOT_APPLICABLE','postgres_activation_foreign_adjudication');
  assert.deepEqual(receipt.decisions,[],'postgres_activation_foreign_adjudication');
  assert.equal(receipt.alloy?.schema,'otziv-alloy-adjudication-v1','postgres_activation_foreign_schema');
  assert.equal(receipt.alloy.status,'NOT_APPLICABLE','postgres_activation_foreign_adjudication');
  assert.deepEqual(receipt.alloy.decisions,[],'postgres_activation_foreign_adjudication');
  assert.equal(receipt.alloy.rawReportSha256,rawHash,'postgres_activation_foreign_hash');
  assert.equal(receipt.alloy.imageConfigId,input.imageId,'postgres_activation_foreign_image');
  assert.ok(allowedIds.includes(receipt.alloy.immutableImageId),'postgres_activation_foreign_image');
  assert.equal(receipt.alloy.rawReportModified,false,'postgres_activation_foreign_modified');
  assert.equal(raw.Metadata?.ImageID,input.imageId,'postgres_activation_raw_image');
  assert.equal(triage.schema,'otziv-runtime-triage-v1','postgres_activation_triage_schema');
  assert.equal(triage.reportSHA256,rawHash,'postgres_activation_triage_hash');
  assert.equal(triage.imageId,input.imageId,'postgres_activation_triage_image');
  assert.deepEqual(triage,buildTriage(raw,rawBytes),'postgres_activation_triage_findings');

  const {review,reviewSha256}=await loadPostgresProof();
  const pg=receipt.postgres;
  assert.equal(pg?.schema,'otziv-postgres-c14-adjudication-v1','postgres_activation_adjudication_schema');
  assert.equal(pg.status,'EXACT_RUNTIME_VERIFIED','postgres_activation_adjudication_rejected');
  assert.equal(pg.rawReportSha256,rawHash,'postgres_activation_adjudication_hash');
  assert.equal(pg.imageConfigId,input.imageId,'postgres_activation_adjudication_image');
  assert.ok(allowedIds.includes(pg.immutableImageId),'postgres_activation_adjudication_inspected_image');
  assert.equal(pg.rawReportModified,false,'postgres_activation_adjudication_modified');
  assert.equal(pg.reviewSha256,reviewSha256,'postgres_activation_source_review');
  assert.equal(pg.validUntil,review.validUntil,'postgres_activation_review_expiry');
  const decisions=matchingPostgresFindings(raw,review);
  assert.deepEqual(pg.decisions,decisions,'postgres_activation_exact_decisions');
  const actual=summarizeReport(raw);
  for(const [key,value]of Object.entries(actual))assert.equal(input.security?.[key],value,'postgres_activation_raw_count_'+key);
  const summary={...combinedScanSummary(actual,{grafana:receipt,alloy:receipt.alloy,postgres:{...pg,decisions}}),scannerImage:TRIVY_IMAGE};
  assert.equal(summary.result,'PASS','postgres_activation_unresolved_findings');
  assert.equal(summary.effectiveBlockingFixedHighOrCritical,0,'postgres_activation_unresolved_findings');
  assert.equal(summary.effectiveUnfixedHighOrCritical,0,'postgres_activation_unresolved_findings');
  assert.deepEqual(input.security,summary,'postgres_activation_effective_summary');

  const publishedBytes=await bytes(ROOT+'review.json');
  assert.equal(hash(publishedBytes),PUBLISHED_PROOF_SHA256,'postgres_activation_published_proof_anchor');
  const published=JSON.parse(publishedBytes);
  assert.equal(published.schema,'otziv-published-postgres-runtime-v1','postgres_activation_published_proof_schema');
  assert.equal(published.result,'PASS','postgres_activation_published_proof_failed');
  for(const key of ['reference','commit','run','attempt'])assert.equal(input[key],published[key],'postgres_activation_published_identity');
  assert.equal(input.imageId,published.imageConfigDigest,'postgres_activation_published_image');
  assert.equal(published.sourceReviewSha256,reviewSha256,'postgres_activation_published_review');
  for(const [path,sha]of Object.entries(published.files)){
    assert.ok(safePath(path),'postgres_activation_evidence_path');
    assert.equal(hash(await bytes(ROOT+path)),sha,'postgres_activation_published_bytes_'+path);
  }
  for(const [path,sha]of Object.entries(published.sourceFiles))
    assert.equal(hash(await bytes(path)),sha,'postgres_activation_source_bytes_'+path);
  assert.deepEqual(input,await json(publicationPath),'postgres_activation_publication_bytes');

  const manifestPath='infrastructure/runtime-security/reviewed-images-c14-postgres.json';
  const manifestBytes=await bytes(manifestPath);
  const manifest=validateReviewedImageSet('c14-postgres',manifestBytes,await bytes('infrastructure/runtime-security/reviewed-images.json'));
  assert.equal(hash(manifestBytes),input.manifestSha256,'postgres_activation_manifest_hash');
  assert.equal(manifest.images[0].dockerfileSha256,input.dockerfileSha256,'postgres_activation_dockerfile');
  assert.equal(manifest.images[0].sourceBeforeRef,input.sourceBeforeRef,'postgres_activation_source_before');

  const configBytes=await bytes(directory+'registry-amd64-config.json'),config=JSON.parse(configBytes);
  assert.equal('sha256:'+hash(configBytes),input.imageId,'postgres_activation_oci_config');
  assert.deepEqual(raw.Metadata.ImageConfig.rootfs,config.rootfs,'postgres_activation_report_rootfs');
  // Reuse the frozen parser/coverage checks with the known reviewed inventory.
  // The code-pinned published export proof below establishes that this complete
  // inventory also belongs to the published image; no Docker runs in this gate.
  const observedBytes=await bytes(SOURCE_ROOT+'observed-runtime.json.gz');
  assert.equal(hash(observedBytes),review.files['observed-runtime.json.gz'],'postgres_activation_observed_review');
  const observed=JSON.parse(gunzipSync(observedBytes));
  const inspected={Config:config.config,RootFS:{Layers:config.rootfs.diff_ids}};
  const binding=validatePostgresObserved(raw,inspected,observed.observed,review);
  for(const [key,value]of Object.entries(binding))assert.equal(pg[key],value,'postgres_activation_payload_binding_'+key);

  const replay=await json(ROOT+'repeated-runtime-adjudication.json');
  const withoutLocalId=value=>{const {immutableImageId,...rest}=value;return rest;};
  assert.ok(allowedIds.includes(replay.immutableImageId),'postgres_activation_replayed_image');
  assert.deepEqual(withoutLocalId(replay),withoutLocalId(pg),'postgres_activation_replayed_adjudication');
  const verification=await json(ROOT+'published-image-verification.json');
  assert.equal(verification.schema,'otziv-published-postgres-image-verification-v1','postgres_activation_inspection_schema');
  assert.equal(verification.result,'PASS','postgres_activation_inspection_failed');
  assert.equal(verification.reference,input.reference,'postgres_activation_inspection_reference');
  assert.equal(verification.imageConfigDigest,input.imageId,'postgres_activation_inspection_image');
  assert.ok(allowedIds.includes(verification.actualLocalId),'postgres_activation_inspection_local_image');
  assert.equal(verification.rawReportSha256,rawHash,'postgres_activation_inspection_raw_hash');
  assert.equal(verification.sourceReviewSha256,reviewSha256,'postgres_activation_inspection_review');
  assert.equal(verification.runtimeIdentitySha256,binding.runtimeIdentitySha256,'postgres_activation_inspection_payload');
  assert.equal(verification.reviewedPayloadFiles,binding.verifiedPayloadFiles,'postgres_activation_inspection_payload_count');
  assert.equal(verification.reviewProofFilesValidated,Object.keys(review.files).length,'postgres_activation_inspection_proof_count');
  for(const key of ['rootfsMatchesRegistryConfig','imageLaunchConfigurationMatchesRegistry','publicationAndAnonymousFullValidation',
    'versionedManifestValidated','independentlyReviewedRuntimePayloadIdentical'])assert.equal(verification[key],true,'postgres_activation_inspection_'+key);
  assert.equal(verification.inspectedBinaryExecuted,false,'postgres_activation_inspection_method');

  const context=await json(ROOT+'publication-source-context.json'),sourceManifest=await json(SOURCE_ROOT+'source-manifest.json');
  assert.equal(context.result,'PASS','postgres_activation_context_failed');assert.equal(context.commit,input.commit,'postgres_activation_context_commit');
  assert.deepEqual(Object.keys(context.files).sort(),Object.keys(sourceManifest.files).sort(),'postgres_activation_context_inventory');
  for(const [path,record]of Object.entries(context.files)){
    const source=await bytes(path),expected=sourceManifest.files[path];
    assert.equal(record.publicationCommitBytesMatch,true,'postgres_activation_context_commit_bytes');
    assert.equal(hash(source),record.sha256,'postgres_activation_context_hash');
    assert.equal(record.sha256,expected.sha256,'postgres_activation_context_review_hash');
    assert.equal(source.length,record.bytes,'postgres_activation_context_size');
    assert.equal(createHash('sha1').update(Buffer.from('blob '+source.length+'\0')).update(source).digest('hex'),record.gitBlob,'postgres_activation_context_git_blob');
    assert.equal(record.gitBlob,expected.gitBlob,'postgres_activation_context_review_blob');
  }
  return {schema:'otziv-postgres-activation-scan-v1',result:'PASS',reference:input.reference,imageConfigId:input.imageId,
    rawReportSha256:rawHash,sourceReviewSha256:reviewSha256,publishedProofSha256:PUBLISHED_PROOF_SHA256,validUntil:review.validUntil,
    effectiveBlockingFixedHighOrCritical:0,effectiveUnfixedHighOrCritical:0,databaseTransitionAuthorized:false};
}
