import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { gunzipSync } from 'node:zlib';
import { exactProduct, labelsOf, inspectReviewedGoBinary } from './go-binary-inspection.mjs';
export { BUILD_INFO_READER, canonicalBuildInfo, validateObservedBinary } from './go-binary-inspection.mjs';

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');


export async function loadReviewedProof(generation = 'original') {
  if (!['original', 'c15', 'c29'].includes(generation)) throw new Error('adjudication_review_unknown');
  if (generation === 'c29') {
    // Reuse the byte-checked fixed Tempo source and ancestry review from C15.
    // Only the exact Grafana executable and its Go toolchain changed.
    const base = await loadReviewedProof('c15');
    const identityBytes = await readFile(new URL('./adjudications/grafana-tempo-c29/identity.json', import.meta.url));
    const identity = JSON.parse(identityBytes);
    const identitySha256 = sha256(identityBytes);
    assert.equal(identitySha256, '96040cec15743c05e1550331821a358a390dc0c87051654ff5fe688bff273167', 'adjudication_c29_identity_changed');
    assert.equal(identity.schema, 'otziv-grafana-tempo-c29-identity-v1', 'adjudication_c29_schema');
    assert.equal(identity.sourceReviewSha256, base.reviewSha256, 'adjudication_c29_source_changed');
    assert.equal(identity.productVersion, '12.4.10+otziv.2', 'adjudication_c29_version');
    assert.equal(identity.binarySha256, '5d7687c55e709e8ac3ccde640ac3f814cac65ce974623ef0cb612f24c13bf7d9', 'adjudication_c29_binary');
    assert.equal(identity.canonicalBuildInfoSha256, '6794e488a8cc58c21fbea13b138e6b3102f0419c09c0c237ac4abc1337793688', 'adjudication_c29_build_info');
    assert.equal(identity.goVersion, 'go1.27.2', 'adjudication_c29_compiler');
    assert.equal(identity.buildInfoReader, 'golang:1.27.2-bookworm@sha256:5cf287a799e6b94384bad13d16b14904c531f51ba65792237e122ce42b392f61', 'adjudication_c29_reader');
    assert.equal(identity.moduleSum, base.review.module.sum, 'adjudication_c29_module_changed');
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
    const candidates = await Promise.all(['original', 'c15', 'c29'].map(loadReviewedProof));
    const matching = candidates.filter(candidate => matchingFindings(report, candidate.review).length);
    if (!matching.length) return { ...base, status: 'NOT_APPLICABLE' };
    const observed = await inspectReviewedGoBinary(report, immutableImageId, scratch, matching.map(candidate => candidate.review));
    const { review, reviewSha256 } = matching.find(candidate => candidate.review.binary.sha256 === observed.binarySha256);
    const decisions = matchingFindings(report, review);
    return { ...base, status: 'EXACT_BINARY_FIXED_CODE_PROVEN', reviewSha256, ...observed, module: review.module, decisions };
  } catch (error) {
    return { ...base, status: 'REJECTED', reason: /^adjudication_[a-z_]+$/.test(error.message) ? error.message : 'adjudication_inspection_failed' };
  }
}

export function effectiveScanSummary(summary, adjudication) {
  const count = (adjudication?.decisions || []).filter(item => item.fixedHighOrCritical).length;
  const effectiveBlockingFixedHighOrCritical = summary.blockingFixedHighOrCritical - count;
  if (effectiveBlockingFixedHighOrCritical < 0) throw new Error('adjudication_count_invalid');
  return { ...summary, adjudicatedFixedHighOrCritical: count, effectiveBlockingFixedHighOrCritical,
    result: effectiveBlockingFixedHighOrCritical ? 'FAIL' : 'PASS' };
}
