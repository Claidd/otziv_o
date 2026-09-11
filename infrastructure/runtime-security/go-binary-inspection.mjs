import { createHash, randomUUID } from 'node:crypto';
import { readFile, mkdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { run } from '../recovery/process.mjs';

export const BUILD_INFO_READER = 'golang:1.27.1-bookworm@sha256:648f440f42a0958804efb24df176f806f9d353b41f1c0627f666428e40310f6b';
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export const canonicalBuildInfo = text => text.replace(/\r\n/g, '\n').split('\n').slice(1).join('\n').trimEnd() + '\n';
export const labelsOf = report => report.Metadata?.ImageConfig?.config?.Labels || {};
export const exactProduct = (labels, review) => ['source', 'revision', 'version'].every(key => labels[`org.opencontainers.image.${key}`] === review.product[key]);

export function validateObservedBinary(report, inspect, binarySha256, buildInfo, review) {
  // Bind Docker's OCI index to the scan's config through the complete rootfs,
  // product labels, exact executable bytes and full canonical Go build info.
  const reportLayers = report.Metadata?.ImageConfig?.rootfs?.diff_ids;
  if (!exactProduct(inspect.Config?.Labels || {}, review) || !exactProduct(labelsOf(report), review) ||
      !Array.isArray(reportLayers) || !reportLayers.length ||
      JSON.stringify(reportLayers) !== JSON.stringify(inspect.RootFS?.Layers)) throw new Error('adjudication_image_mismatch');
  if (binarySha256 !== review.binary.sha256) throw new Error('adjudication_binary_mismatch');
  if (sha256(canonicalBuildInfo(buildInfo)) !== review.binary.canonicalBuildInfoSha256 ||
      !buildInfo.split(/\r?\n/)[0].endsWith(': go1.27.1') ||
      !buildInfo.includes(`\tdep\t${review.module.name}\t${review.module.version}\t${review.module.sum}`)) throw new Error('adjudication_build_info_mismatch');
}

export async function inspectReviewedGoBinary(report, immutableImageId, scratch, review) {
  const candidates = Array.isArray(review) ? review : [review];
  if (!candidates.length || candidates.some(candidate => candidate.binary.path !== candidates[0].binary.path))
    throw new Error('adjudication_target_invalid');
  review = candidates[0];
  if (!/^sha256:[a-f0-9]{64}$/.test(immutableImageId)) throw new Error('adjudication_immutable_image_required');
  if (!/^[a-zA-Z0-9_.-]+(?:\/[a-zA-Z0-9_.-]+)*$/.test(review.binary.path) ||
      review.binary.path.split('/').some(part => part === '.' || part === '..')) throw new Error('adjudication_target_invalid');
  const inspect = JSON.parse(await run('docker', ['image', 'inspect', immutableImageId]))[0];
  const directory = join(scratch, `binary-proof-${randomUUID()}`);
  let container;
  try {
    await mkdir(directory);
    const owner = `otziv-adjudicate-${randomUUID()}`;
    // Create a stopped container only. Never execute the inspected image.
    const created = (await run('docker', ['create', '--name', owner, '--label', `com.otziv.adjudication.owner=${owner}`,
      '--network', 'none', '--entrypoint', '/not-executed', immutableImageId])).trim();
    if (!/^[a-f0-9]{64}$/.test(created)) throw new Error('adjudication_container_identity_invalid');
    container = created;
    await run('docker', ['cp', `${container}:/${review.binary.path}`, join(directory, 'executable')], { timeoutMs: 120_000 });
    const binarySha256 = sha256(await readFile(join(directory, 'executable')));
    review = candidates.find(candidate => candidate.binary.sha256 === binarySha256);
    if (!review) throw new Error('adjudication_binary_mismatch');
    const buildInfo = await run('docker', ['run', '--rm', '--network', 'none', '--read-only', '--cap-drop=ALL',
      '--security-opt=no-new-privileges:true', '--memory', '512m', '--pids-limit', '64', '-e', 'GOTOOLCHAIN=local',
      '--mount', `type=bind,source=${directory},target=/input,readonly`, BUILD_INFO_READER,
      'go', 'version', '-m', '/input/executable'], { timeoutMs: 120_000 });
    validateObservedBinary(report, inspect, binarySha256, buildInfo, review);
    return { binarySha256, canonicalBuildInfoSha256: sha256(canonicalBuildInfo(buildInfo)),
      buildInfoReader: BUILD_INFO_READER, inspectedBinaryExecuted: false };
  } finally {
    if (container) await run('docker', ['rm', '-f', container]).catch(() => {});
    // Only the fresh owned directory above is removed, never a configured parent.
    await rm(directory, { recursive: true, force: true });
  }
}
