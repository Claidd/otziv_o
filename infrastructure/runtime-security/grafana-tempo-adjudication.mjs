import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { gunzipSync } from 'node:zlib';
import { exactProduct, labelsOf, inspectReviewedGoBinary } from './go-binary-inspection.mjs';
export { BUILD_INFO_READER, canonicalBuildInfo, validateObservedBinary } from './go-binary-inspection.mjs';

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const C30_PRODUCT_VERSION = '12.4.10+otziv.3';
// The separately published C30 image is bound to its inspected executable.
// It cannot inherit C29's exact-binary Tempo adjudication.
const C30_IDENTITY_SHA256 = '66d2387e49ef930bfa2002ba1a10c38368d23cbdda5d2030175b67cc4d27d0e3';
const C30_BINARY_SHA256 = 'da43653c2fdd936dac451ffda63ddebd2899758672376daf73087740dc35528c';
const C30_BUILD_INFO_SHA256 = '03d0697eff5ee1f06df6bc721dcb17c7398544287a9cf8f8cad79ea1c1d6de3f';
const C43_PRODUCT_VERSION = '12.4.10+otziv.5';
const C43_IDENTITY_SHA256 = 'e58d1175e7cfb0fe9014fa4547ee20720fc91d96ffefd9dc4924f6e0a37e6d0c';
const C43_BINARY_SHA256 = 'b1d95ccc9b85d9cffaa1f9d99b89618a37f043354619a6395521d226969bec19';
const C43_BUILD_INFO_SHA256 = 'a2e85fd734ff3c45a3fa29158e17963fc2ef2a02c08e03ef8aca1dcecc39965c';
const C36_PRODUCT_VERSION = '12.4.10+otziv.4';
// Rebuilt with the fixed Go toolchain. Its exact executable was inspected
// independently; the old C30 binary identity cannot adjudicate this image.
const C36_IDENTITY_SHA256 = 'c1c3fcc329c25e39196036fee53969d69965f78b7a4cb025660fc04eb9952d70';
const C36_BINARY_SHA256 = '8b8981ecfb6dd1123831116f6428c531d33f43d7cda839677a6335f73895b32e';
const C36_BUILD_INFO_SHA256 = '03d0697eff5ee1f06df6bc721dcb17c7398544287a9cf8f8cad79ea1c1d6de3f';


export async function loadReviewedProof(generation = 'original') {
  if (!['original', 'c15', 'c29', 'c30', 'c36', 'c43'].includes(generation)) throw new Error('adjudication_review_unknown');
  if (['c29', 'c30', 'c36', 'c43'].includes(generation)) {
    // Reuse the byte-checked fixed Tempo source and ancestry review from C15.
    // Only the exact Grafana executable and its Go toolchain changed.
    if (generation === 'c30' && (!C30_IDENTITY_SHA256 || !C30_BINARY_SHA256 || !C30_BUILD_INFO_SHA256))
      throw new Error('adjudication_c30_identity_pending');
    if (generation === 'c36' && (!C36_IDENTITY_SHA256 || !C36_BINARY_SHA256 || !C36_BUILD_INFO_SHA256))
      throw new Error('adjudication_c36_identity_pending');
    const base = await loadReviewedProof('c15');
    const identityBytes = await readFile(new URL(`./adjudications/grafana-tempo-${generation}/identity.json`, import.meta.url));
    const identity = JSON.parse(identityBytes);
    const identitySha256 = sha256(identityBytes);
    assert.equal(identitySha256, generation === 'c43' ? C43_IDENTITY_SHA256 : generation === 'c36' ? C36_IDENTITY_SHA256 : generation === 'c30' ? C30_IDENTITY_SHA256 : '96040cec15743c05e1550331821a358a390dc0c87051654ff5fe688bff273167', `adjudication_${generation}_identity_changed`);
    assert.equal(identity.schema, `otziv-grafana-tempo-${generation}-identity-v1`, `adjudication_${generation}_schema`);
    assert.equal(identity.sourceReviewSha256, base.reviewSha256, `adjudication_${generation}_source_changed`);
    assert.equal(identity.productVersion, generation === 'c43' ? C43_PRODUCT_VERSION : generation === 'c36' ? C36_PRODUCT_VERSION : generation === 'c30' ? C30_PRODUCT_VERSION : '12.4.10+otziv.2', `adjudication_${generation}_version`);
    assert.equal(identity.binarySha256, generation === 'c43' ? C43_BINARY_SHA256 : generation === 'c36' ? C36_BINARY_SHA256 : generation === 'c30' ? C30_BINARY_SHA256 : '5d7687c55e709e8ac3ccde640ac3f814cac65ce974623ef0cb612f24c13bf7d9', `adjudication_${generation}_binary`);
    assert.equal(identity.canonicalBuildInfoSha256, generation === 'c43' ? C43_BUILD_INFO_SHA256 : generation === 'c36' ? C36_BUILD_INFO_SHA256 : generation === 'c30' ? C30_BUILD_INFO_SHA256 : '6794e488a8cc58c21fbea13b138e6b3102f0419c09c0c237ac4abc1337793688', `adjudication_${generation}_build_info`);
    assert.equal(identity.goVersion, ['c36', 'c43'].includes(generation) ? 'go1.27.3' : 'go1.27.2', `adjudication_${generation}_compiler`);
    assert.equal(identity.buildInfoReader, ['c36', 'c43'].includes(generation)
      ? 'golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132'
      : 'golang:1.27.2-bookworm@sha256:5cf287a799e6b94384bad13d16b14904c531f51ba65792237e122ce42b392f61', `adjudication_${generation}_reader`);
    if (generation === 'c43') assert.equal(identity.goJoseVersion, 'v4.1.6', 'adjudication_c43_go_jose');
    assert.equal(identity.moduleSum, base.review.module.sum, `adjudication_${generation}_module_changed`);
    const review = structuredClone(base.review);
    review.product.version = identity.productVersion;
    Object.assign(review.binary, { sha256: identity.binarySha256,
      canonicalBuildInfoSha256: identity.canonicalBuildInfoSha256,
      goVersion: identity.goVersion, buildInfoReader: identity.buildInfoReader });
    return { review, reviewSha256: sha256(Buffer.from(base.reviewSha256 + ':' + identitySha256)) };
  }
  const proofRoot = new URL(generation === 'original' ? './adjudications/grafana-tempo/' : './adjudications/grafana-tempo-c15/', import.meta.url);
  const bytes = await readFile(new URL('review.json', proofRoot));
  const review = JSON.parse(bytes);
  const sources = {};
  for (const file of review.proofFiles) {
    const content = await readFile(new URL(file.path, proofRoot));
    if (sha256(content) !== file.sha256) throw new Error('adjudication_source_hash_mismatch');
    sources[file.path] = content.toString('utf8');
  }
  // Verify the reviewed fixed code as well as its hash. The original complete
  // official comparison replies are retained compressed, not replaced by a flag.
  if (!sources['installed-frontend-config.go'].includes('MaxLimit:') ||
      !/MaxLimit:\s+256\s*\*\s*1024/.test(sources['installed-frontend-config.go']) ||
      !/CustomerEncryptionKey\s+flagext.Secret/.test(sources['installed-s3-config.go']) ||
      !sources['installed-s3.go'].includes('cfg.SSE.CustomerEncryptionKey.String()')) throw new Error('adjudication_fixed_source_missing');
  for (const decision of review.decisions) {
    const comparisonBytes = gunzipSync(await readFile(new URL(decision.comparisonFile, proofRoot)));
    const comparison = JSON.parse(comparisonBytes);
    if (sha256(comparisonBytes) !== decision.comparisonResponseSha256 || comparison.behind_by !== 0 ||
        comparison.merge_base_commit?.sha !== decision.fixCommit ||
        !comparison.commits?.some(commit => commit.sha.startsWith('525d1bab07e0'))) throw new Error('adjudication_ancestry_invalid');
    if (sha256(await readFile(new URL(decision.fixPatchFile, proofRoot))) !== decision.fixPatchSha256) throw new Error('adjudication_patch_hash_mismatch');
  }
  return { review, reviewSha256: sha256(bytes) };
}

export function matchingFindings(report, review) {
  if (report.ArtifactType !== 'container_image' || !exactProduct(labelsOf(report), review)) return [];
  const decisions = [];
  for (const [resultIndex, result] of (report.Results || []).entries()) {
    if (result.Type !== 'gobinary' || result.Target !== review.binary.path) continue;
    for (const [findingIndex, finding] of (result.Vulnerabilities || []).entries()) {
      const decision = review.decisions.find(item => item.cve === finding.VulnerabilityID);
      if (decision && finding.PkgName === review.module.name && finding.InstalledVersion === review.module.version &&
          finding.PkgIdentifier?.PURL === `pkg:golang/${review.module.name}@${review.module.version}`) {
        decisions.push({ resultIndex, findingIndex, cve: decision.cve, target: result.Target,
          package: review.module.name, version: review.module.version, state: decision.state,
          justification: decision.justification, fixCommit: decision.fixCommit,
          fixedHighOrCritical: ['HIGH', 'CRITICAL'].includes(finding.Severity) && Boolean(finding.FixedVersion) });
      }
    }
  }
  return decisions;
}

export async function adjudicateGrafanaImage(report, reportBytes, immutableImageId, scratch) {
  const base = { schema: 'otziv-image-adjudication-v1', rawReportSha256: sha256(reportBytes),
    imageConfigId: report.Metadata?.ImageID, immutableImageId, rawReportModified: false, decisions: [] };
  try {
    const version = labelsOf(report)['org.opencontainers.image.version'];
    const generations = version === C43_PRODUCT_VERSION ? ['c43'] :
      version === C36_PRODUCT_VERSION ? ['c36'] :
      version === C30_PRODUCT_VERSION ? ['c30'] : ['original', 'c15', 'c29'];
    const candidates = await Promise.all(generations.map(loadReviewedProof));
    const matching = candidates.filter(candidate => matchingFindings(report, candidate.review).length);
    if (!matching.length) return { ...base, status: 'NOT_APPLICABLE' };
    const observed = await inspectReviewedGoBinary(report, immutableImageId, scratch, matching.map(candidate => candidate.review));
    const { review, reviewSha256 } = matching.find(candidate => candidate.review.binary.sha256 === observed.binarySha256);
    const decisions = matchingFindings(report, review);
    return { ...base, status: 'EXACT_BINARY_FIXED_CODE_PROVEN', reviewSha256, ...observed, module: review.module, decisions };
  } catch (error) {
    return { ...base, status: 'REJECTED', reason: /^adjudication_[a-z0-9_]+$/.test(error.message) ? error.message : 'adjudication_inspection_failed' };
  }
}

export function effectiveScanSummary(summary, adjudication) {
  const count = (adjudication?.decisions || []).filter(item => item.fixedHighOrCritical).length;
  const effectiveBlockingFixedHighOrCritical = summary.blockingFixedHighOrCritical - count;
  if (effectiveBlockingFixedHighOrCritical < 0) throw new Error('adjudication_count_invalid');
  return { ...summary, adjudicatedFixedHighOrCritical: count, effectiveBlockingFixedHighOrCritical,
    result: effectiveBlockingFixedHighOrCritical ? 'FAIL' : 'PASS' };
}
