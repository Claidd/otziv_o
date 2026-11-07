// Replay of the published C51 zlib-only PostgreSQL scan for staging. Publication
// alone does not authorize replacing the database in Compose: the same-volume
// C36 -> C51 -> C36 acceptance and issuer coupling are separate, later proofs.
import assert from 'node:assert/strict';
import {dirname} from 'node:path';
import {gunzipSync} from 'node:zlib';
import {summarizeReport,TRIVY_IMAGE} from './scan.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
import {buildTriage} from './triage-report.mjs';
import {loadPostgresC16Proof} from './postgres-c16-adjudication.mjs';
import {hash,loadC36PostgresParent,loadC51ZlibPackage,validatePostgresC51Runtime,validateC51ScannerPackages,
  deriveC51Decisions,effectivePostgresC51Summary} from './postgres-c51-adjudication.mjs';

const SOURCE='infrastructure/runtime-security/';
const safe=path=>typeof path==='string'&&path.startsWith(SOURCE)&&!/[\\:]/.test(path)&&
  path.split('/').every(part=>part&&part!=='.'&&part!=='..');
const sameId=(id,config,reference)=>assert.ok([config,reference.split('@')[1]].includes(id),
  'postgres_c51_activation_inspected_id');

export async function validatePostgresC51ActivationScan(publication,publicationPath,read){
  assert.equal(publication.component,'postgres','postgres_c51_activation_component');
  assert.equal(publication.manifestSet,'c51-postgres','postgres_c51_activation_set');
  assert.match(publication.reference||'',/^ghcr\.io\/claidd\/otziv-security@sha256:[a-f0-9]{64}$/,
    'postgres_c51_activation_reference');
  assert.match(publication.imageId||'',/^sha256:[a-f0-9]{64}$/,'postgres_c51_activation_image');
  assert.ok(safe(publicationPath),'postgres_c51_activation_path');
  const directory=dirname(publicationPath).replaceAll('\\','/');
  const [rawBytes,receiptBytes,runtimeBytes,configBytes,triageBytes]=await Promise.all([
    read(directory+'/vulnerabilities.json'),read(directory+'/vulnerabilities.adjudications.json'),
    read(directory+'/vulnerabilities.runtime-c51.json.gz'),read(directory+'/registry-amd64-config.json'),
    read(directory+'/vulnerabilities.triage.json')]);
  const raw=JSON.parse(rawBytes),receipt=JSON.parse(receiptBytes),runtime=JSON.parse(gunzipSync(runtimeBytes)),
    config=JSON.parse(configBytes),triage=JSON.parse(triageBytes);
  assert.equal('sha256:'+hash(configBytes),publication.imageId,'postgres_c51_activation_registry_config');
  assert.equal(raw.Metadata?.ImageID,publication.imageId,'postgres_c51_activation_report_image');
  assert.deepEqual(raw.Metadata?.ImageConfig?.rootfs,config.rootfs,'postgres_c51_activation_report_layers');
  assert.equal(receipt.schema,'otziv-image-adjudication-v1','postgres_c51_activation_receipt_schema');
  assert.equal(receipt.status,'NOT_APPLICABLE','postgres_c51_activation_foreign_status');
  assert.deepEqual(receipt.decisions,[],'postgres_c51_activation_foreign_decisions');
  assert.equal(receipt.alloy?.status,'NOT_APPLICABLE','postgres_c51_activation_alloy_status');
  assert.deepEqual(receipt.alloy?.decisions,[],'postgres_c51_activation_alloy_decisions');
  assert.equal(receipt.gosu,undefined,'postgres_c51_activation_separate_gosu');
  for(const item of [receipt,receipt.alloy,receipt.postgres]){
    assert.equal(item.rawReportSha256,hash(rawBytes),'postgres_c51_activation_raw_binding');
    assert.equal(item.imageConfigId,publication.imageId,'postgres_c51_activation_receipt_image');
    assert.equal(item.rawReportModified,false,'postgres_c51_activation_raw_modified');
  }
  assert.deepEqual(triage,buildTriage(raw,rawBytes),'postgres_c51_activation_triage');
  const postgres=receipt.postgres;
  assert.equal(postgres.schema,'otziv-postgres-c51-adjudication-v1','postgres_c51_activation_postgres_schema');
  assert.equal(postgres.status,'EXACT_RUNTIME_VERIFIED','postgres_c51_activation_postgres_unverified');
  sameId(postgres.immutableImageId,publication.imageId,publication.reference);
  assert.equal(postgres.runtimeProofSha256,hash(runtimeBytes),'postgres_c51_activation_runtime_hash');
  assert.equal(runtime.executedScriptSha256,postgres.inspectorSha256,'postgres_c51_activation_inspector_binding');
  assert.equal(runtime.candidate.reference,postgres.immutableImageId,'postgres_c51_activation_inspected_reference');
  const {parent}=await loadC36PostgresParent(),zlib=await loadC51ZlibPackage();
  const delta=validatePostgresC51Runtime(runtime,parent,config,raw,publication.commit,zlib);
  assert.deepEqual(postgres.changedFiles,delta.changedFiles,'postgres_c51_activation_delta');
  const {review,reviewSha256}=await loadPostgresC16Proof();
  assert.equal(postgres.reviewSha256,reviewSha256,'postgres_c51_activation_review');
  assert.deepEqual(postgres.zlib,validateC51ScannerPackages(raw,review),'postgres_c51_activation_zlib');
  const {september,decisions}=await deriveC51Decisions(raw,review);
  assert.deepEqual(postgres.libxmlSeptember,september,'postgres_c51_activation_libxml');
  assert.deepEqual(postgres.decisions,decisions,'postgres_c51_activation_decisions');
  effectivePostgresC51Summary(summarizeReport(raw),postgres);
  const summary={...combinedScanSummary(summarizeReport(raw),
    {grafana:receipt,alloy:receipt.alloy,postgres}),scannerImage:TRIVY_IMAGE};
  assert.deepEqual(publication.security,summary,'postgres_c51_activation_security');
  assert.equal(summary.result,'PASS','postgres_c51_activation_scan_failed');
  assert.equal(summary.effectiveBlockingFixedHighOrCritical,0,'postgres_c51_activation_fixed_risk');
  assert.equal(summary.unresolvedRiskReview,'NONE','postgres_c51_activation_unresolved_risk');
  return {publication,postgres,raw,runtime,config};
}
