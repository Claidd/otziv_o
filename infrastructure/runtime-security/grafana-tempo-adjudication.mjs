import { createHash, randomUUID } from 'node:crypto';
import { readFile, mkdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { run } from '../recovery/process.mjs';

export const BUILD_INFO_READER = 'golang:1.27.1-bookworm@sha256:648f440f42a0958804efb24df176f806f9d353b41f1c0627f666428e40310f6b';
const proofRoot = new URL('./adjudications/grafana-tempo/', import.meta.url);
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export const canonicalBuildInfo = text => text.replace(/\r\n/g, '\n').split('\n').slice(1).join('\n').trimEnd() + '\n';
const labelsOf = report => report.Metadata?.ImageConfig?.config?.Labels || {};
const exactProduct = (labels, review) => ['source', 'revision', 'version'].every(key => labels[`org.opencontainers.image.${key}`] === review.product[key]);

export async function loadReviewedProof() {
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

export function validateObservedBinary(report, inspect, binarySha256, buildInfo, review) {
  // Docker's OCI index ID may differ from Trivy's image-config ID. Bind the
  // exported scan to the immutable local image through its rootfs and labels,
  // then require exact actual executable bytes and the entire Go build info.
  const reportLayers = report.Metadata?.ImageConfig?.rootfs?.diff_ids;
  if (!exactProduct(inspect.Config?.Labels || {}, review) || !exactProduct(labelsOf(report), review) ||
      !Array.isArray(reportLayers) || !reportLayers.length ||
      JSON.stringify(reportLayers) !== JSON.stringify(inspect.RootFS?.Layers)) throw new Error('adjudication_image_mismatch');
  if (binarySha256 !== review.binary.sha256) throw new Error('adjudication_binary_mismatch');
  if (sha256(canonicalBuildInfo(buildInfo)) !== review.binary.canonicalBuildInfoSha256 ||
      !buildInfo.split(/\r?\n/)[0].endsWith(': go1.27.1') ||
      !buildInfo.includes(`\tdep\t${review.module.name}\t${review.module.version}\t${review.module.sum}`)) throw new Error('adjudication_build_info_mismatch');
}

export async function adjudicateGrafanaImage(report, reportBytes, immutableImageId, scratch) {
  const base = { schema: 'otziv-image-adjudication-v1', rawReportSha256: sha256(reportBytes),
    imageConfigId: report.Metadata?.ImageID, immutableImageId, rawReportModified: false, decisions: [] };
  let container;
  const directory = join(scratch, `binary-proof-${randomUUID()}`);
  try {
    const { review, reviewSha256 } = await loadReviewedProof();
    const decisions = matchingFindings(report, review);
    if (!decisions.length) return { ...base, status: 'NOT_APPLICABLE' };
    if (!/^sha256:[a-f0-9]{64}$/.test(immutableImageId)) throw new Error('adjudication_immutable_image_required');
    const inspect = JSON.parse(await run('docker', ['image', 'inspect', immutableImageId]))[0];
    await mkdir(directory);
    container = `otziv-adjudicate-${randomUUID()}`;
    // Never execute the inspected image. A read-only, bounded official Go
    // parser inspects copied bytes without network access or Docker socket.
    await run('docker', ['create', '--name', container, '--network', 'none', '--entrypoint', '/not-executed', immutableImageId]);
    await run('docker', ['cp', `${container}:/${review.binary.path}`, join(directory, 'grafana')], { timeoutMs: 120_000 });
    const binarySha256 = sha256(await readFile(join(directory, 'grafana')));
    // Fail before pulling/running a parser when a different binary is observed.
    if (binarySha256 !== review.binary.sha256) throw new Error('adjudication_binary_mismatch');
    const buildInfo = await run('docker', ['run', '--rm', '--network', 'none', '--read-only', '--cap-drop=ALL',
      '--security-opt=no-new-privileges:true', '--memory', '512m', '--pids-limit', '64', '-e', 'GOTOOLCHAIN=local',
      '--mount', `type=bind,source=${directory},target=/input,readonly`, BUILD_INFO_READER,
      'go', 'version', '-m', '/input/grafana'], { timeoutMs: 120_000 });
    validateObservedBinary(report, inspect, binarySha256, buildInfo, review);
    return { ...base, status: 'EXACT_BINARY_FIXED_CODE_PROVEN', reviewSha256, binarySha256,
      canonicalBuildInfoSha256: sha256(canonicalBuildInfo(buildInfo)), buildInfoReader: BUILD_INFO_READER,
      inspectedBinaryExecuted: false, module: review.module, decisions };
  } catch (error) {
    return { ...base, status: 'REJECTED', reason: /^adjudication_[a-z_]+$/.test(error.message) ? error.message : 'adjudication_inspection_failed' };
  } finally {
    if (container) await run('docker', ['rm', '-f', container]).catch(() => {});
    await rm(directory, { recursive: true, force: true });
  }
}

export function effectiveScanSummary(summary, adjudication) {
  const count = (adjudication?.decisions || []).filter(item => item.fixedHighOrCritical).length;
  const effectiveBlockingFixedHighOrCritical = summary.blockingFixedHighOrCritical - count;
  if (effectiveBlockingFixedHighOrCritical < 0) throw new Error('adjudication_count_invalid');
  return { ...summary, adjudicatedFixedHighOrCritical: count, effectiveBlockingFixedHighOrCritical,
    result: effectiveBlockingFixedHighOrCritical ? 'FAIL' : 'PASS' };
}
