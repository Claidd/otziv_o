import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { gunzipSync } from 'node:zlib';
import { exactProduct, labelsOf, inspectReviewedGoBinary } from './go-binary-inspection.mjs';
export { BUILD_INFO_READER, canonicalBuildInfo, validateObservedBinary } from './go-binary-inspection.mjs';

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');


export async function loadReviewedProof(generation = 'original') {
  if (!['original', 'c15'].includes(generation)) throw new Error('adjudication_review_unknown');
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
    const candidates = await Promise.all(['original', 'c15'].map(loadReviewedProof));
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
