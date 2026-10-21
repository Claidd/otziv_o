import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { run } from '../recovery/process.mjs';
import { labelsOf } from './go-binary-inspection.mjs';
import { inspectAlloyC29Closure, inspectAlloyC29Functions } from './alloy-c29-adjudication.mjs';

export const C36_ALLOY_PROOF_LABEL = 'alloy-daemon-c36';
export const C36_ALLOY_BUILD_INFO_READER = 'golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132';
const SOURCE = 'https://github.com/grafana/alloy';
const REVISION = 'becfd489a7bb459c0496893b555fb87a003296b1';
const VERSION = '1.19.2-otziv.3';
const MODULE = 'github.com/docker/docker';
const MODULE_VERSION = 'v28.5.2+incompatible';
const CVES = new Set(['CVE-2026-41567', 'CVE-2026-42306']);
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

// This proof is scoped to the separately published C36 binary. It does not
// inherit the C29 verdict merely because the Go module graph is unchanged.
export async function adjudicateAlloyC36Image(report, reportBytes, immutableImageId, scratch) {
  const base = { schema: 'otziv-alloy-c36-adjudication-v1', rawReportSha256: sha256(reportBytes),
    imageConfigId: report.Metadata?.ImageID, immutableImageId, rawReportModified: false, decisions: [] };
  if (labelsOf(report)['com.otziv.security.proof'] !== C36_ALLOY_PROOF_LABEL)
    return { ...base, status: 'NOT_APPLICABLE' };
  try {
    const labels = labelsOf(report);
    assert.equal(labels['org.opencontainers.image.source'], SOURCE, 'alloy_c36_source');
    assert.equal(labels['org.opencontainers.image.revision'], REVISION, 'alloy_c36_revision');
    assert.equal(labels['org.opencontainers.image.version'], VERSION, 'alloy_c36_version');
    assert.match(immutableImageId, /^sha256:[a-f0-9]{64}$/, 'alloy_c36_image_id');
    const decisions = [];
    for (const [resultIndex, result] of (report.Results || []).entries()) {
      if (result.Target !== 'usr/bin/alloy' || result.Type !== 'gobinary') continue;
      for (const [findingIndex, finding] of (result.Vulnerabilities || []).entries()) {
        if (!CVES.has(finding.VulnerabilityID) || finding.PkgName !== MODULE ||
            finding.InstalledVersion !== MODULE_VERSION) continue;
        decisions.push({ resultIndex, findingIndex, cve: finding.VulnerabilityID, target: result.Target,
          package: finding.PkgName, version: finding.InstalledVersion, state: 'not_affected',
          justification: 'vulnerable_code_not_present',
          fixedHighOrCritical: ['HIGH', 'CRITICAL'].includes(finding.Severity) && Boolean(finding.FixedVersion),
          unfixedHighOrCritical: ['HIGH', 'CRITICAL'].includes(finding.Severity) && !finding.FixedVersion });
      }
    }
    assert.ok(decisions.length > 0 && decisions.length <= 2 &&
      new Set(decisions.map(item => item.cve)).size === decisions.length, 'alloy_c36_findings');
    const inspect = JSON.parse(await run('docker', ['image', 'inspect', immutableImageId]))[0];
    assert.equal(inspect.Id, immutableImageId, 'alloy_c36_immutable_image_identity');
    assert.match(report.Metadata.ImageID || '', /^sha256:[a-f0-9]{64}$/, 'alloy_c36_image_config_id');
    assert.deepEqual(inspect.Config.Labels, labels, 'alloy_c36_image_labels');
    assert.deepEqual(inspect.RootFS.Layers, report.Metadata.ImageConfig.rootfs.diff_ids, 'alloy_c36_image_rootfs');
    const directory = join(scratch, 'alloy-c36-' + randomUUID());
    await mkdir(directory);
    let container;
    try {
      container = (await run('docker', ['create', '--network', 'none', '--entrypoint', '/not-executed',
        immutableImageId])).trim();
      assert.match(container, /^[a-f0-9]{64}$/, 'alloy_c36_container_identity');
      const binary = join(directory, 'alloy'), closure = join(directory, 'alloy.packages.txt');
      await run('docker', ['cp', container + ':/usr/bin/alloy', binary], { timeoutMs: 120_000 });
      await run('docker', ['cp', container + ':/usr/share/otziv-build/alloy.packages.txt', closure],
        { timeoutMs: 120_000 });
      const binaryBytes = await readFile(binary);
      const functions = inspectAlloyC29Functions(binaryBytes);
      const packages = inspectAlloyC29Closure(await readFile(closure));
      const buildInfo = await run('docker', ['run', '--rm', '--network', 'none', '--read-only',
        '--cap-drop=ALL', '--security-opt=no-new-privileges:true', '--memory', '512m', '--pids-limit', '64',
        '-e', 'GOTOOLCHAIN=local', '--mount', `type=bind,source=${directory},target=/input,readonly`,
        C36_ALLOY_BUILD_INFO_READER, 'go', 'version', '-m', '/input/alloy'], { timeoutMs: 120_000 });
      assert.ok(buildInfo.split(/\r?\n/)[0].endsWith(': go1.27.3') &&
        buildInfo.includes(`\tdep\t${MODULE}\t${MODULE_VERSION}\t`), 'alloy_c36_build_info');
      return { ...base, status: 'EXACT_BINARY_AFFECTED_CODE_ABSENT', binarySha256: sha256(binaryBytes),
        buildInfoSha256: sha256(Buffer.from(buildInfo)), buildInfoReader: C36_ALLOY_BUILD_INFO_READER,
        inspectedBinaryExecuted: false, ...functions, ...packages, decisions };
    } finally {
      if (container) await run('docker', ['rm', '-f', container]).catch(() => {});
      await rm(directory, { recursive: true, force: true });
    }
  } catch (error) {
    return { ...base, status: 'REJECTED',
      reason: /^alloy_c(29|36)_[a-z_]+$/.test(error.message?.split('\n')[0])
        ? error.message.split('\n')[0] : 'alloy_c36_proof_failed' };
  }
}
