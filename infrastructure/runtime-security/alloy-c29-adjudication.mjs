import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { run } from '../recovery/process.mjs';
import { labelsOf } from './go-binary-inspection.mjs';

export const C29_ALLOY_PROOF_LABEL = 'alloy-daemon-c29';
export const C29_ALLOY_BUILD_INFO_READER = 'golang:1.27.2-bookworm@sha256:5cf287a799e6b94384bad13d16b14904c531f51ba65792237e122ce42b392f61';
const READER = C29_ALLOY_BUILD_INFO_READER;
const SOURCE = 'https://github.com/grafana/alloy';
const REVISION = 'becfd489a7bb459c0496893b555fb87a003296b1';
const VERSION = '1.19.2-otziv.2';
const MODULE = 'github.com/docker/docker';
const MODULE_VERSION = 'v28.5.2+incompatible';
const CVES = new Set(['CVE-2026-41567', 'CVE-2026-42306']);
const DAEMON = ['github.com/docker/docker/daemon', 'github.com/moby/moby/daemon', 'github.com/moby/moby/v2/daemon'];
const isDaemon = name => DAEMON.some(prefix => name === prefix || name.startsWith(prefix + '/') || name.startsWith(prefix + '.'));
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

function bounded(buffer, offset, count = 1) {
  assert.ok(Number.isSafeInteger(offset) && Number.isSafeInteger(count) && offset >= 0 &&
    count >= 0 && offset + count <= buffer.length, 'alloy_c29_elf_bounds');
  return offset;
}
function u64(buffer, offset) {
  bounded(buffer, offset, 8);
  const value = buffer.readBigUInt64LE(offset);
  assert.ok(value <= BigInt(Number.MAX_SAFE_INTEGER), 'alloy_c29_elf_integer');
  return Number(value);
}
function section(buffer, offset) {
  bounded(buffer, offset, 64);
  return { name: buffer.readUInt32LE(offset), offset: u64(buffer, offset + 24),
    size: u64(buffer, offset + 32) };
}
function nulString(buffer, start, limit) {
  bounded(buffer, start);
  assert.ok(limit <= buffer.length && start < limit, 'alloy_c29_string_bounds');
  const end = buffer.indexOf(0, start);
  assert.ok(end >= start && end < limit, 'alloy_c29_string_end');
  return buffer.toString('utf8', start, end);
}

// Go's stripped ELF still retains a complete pclntab. Examine every linked
// function, including package names, without executing the inspected binary.
export function inspectAlloyC29Functions(buffer) {
  assert.ok(Buffer.isBuffer(buffer) && buffer.subarray(0, 6).equals(Buffer.from([127, 69, 76, 70, 2, 1])),
    'alloy_c29_elf_format');
  const shoff = u64(buffer, 40), entrySize = buffer.readUInt16LE(58);
  const count = buffer.readUInt16LE(60), namesIndex = buffer.readUInt16LE(62);
  assert.ok(entrySize === 64 && count > 0 && count < 1024 && namesIndex < count, 'alloy_c29_elf_sections');
  bounded(buffer, shoff, entrySize * count);
  const sections = Array.from({ length: count }, (_, i) => section(buffer, shoff + i * entrySize));
  const names = sections[namesIndex];
  bounded(buffer, names.offset, names.size);
  const pcln = sections.filter(value => {
    assert.ok(value.name < names.size, 'alloy_c29_section_name');
    return nulString(buffer, names.offset + value.name, names.offset + names.size) === '.gopclntab';
  });
  assert.equal(pcln.length, 1, 'alloy_c29_pclntab_missing');
  const { offset: start, size } = pcln[0];
  bounded(buffer, start, size);
  assert.equal(buffer.subarray(start, start + 8).toString('hex'), 'f1ffffff00000108', 'alloy_c29_pclntab_format');
  const nfunc = u64(buffer, start + 8);
  const namesoff = u64(buffer, start + 32), cuoff = u64(buffer, start + 40);
  const fileoff = u64(buffer, start + 48), pcoff = u64(buffer, start + 56);
  const pclnoff = u64(buffer, start + 64);
  assert.ok(nfunc > 1000 && nfunc < 1_000_000 &&
    namesoff === 72 && namesoff < cuoff && cuoff < fileoff && fileoff < pcoff &&
    pcoff < pclnoff && pclnoff + (nfunc + 1) * 8 <= size, 'alloy_c29_pclntab_offsets');
  let daemonFunctions = 0, clientFunctions = 0;
  const namesHash = createHash('sha256');
  for (let i = 0; i < nfunc; i++) {
    const record = start + pclnoff + i * 8;
    const funcoff = buffer.readUInt32LE(record + 4);
    assert.ok(funcoff < size - pclnoff - 8, 'alloy_c29_function_offset');
    const nameoff = buffer.readInt32LE(start + pclnoff + funcoff + 4);
    assert.ok(nameoff >= 0, 'alloy_c29_function_name_offset');
    const name = nulString(buffer, start + namesoff + nameoff, start + cuoff);
    namesHash.update(name).update('\n');
    if (isDaemon(name)) daemonFunctions++;
    if (name.startsWith(MODULE + '/client')) clientFunctions++;
  }
  assert.equal(daemonFunctions, 0, 'alloy_c29_daemon_function_present');
  assert.ok(clientFunctions > 0, 'alloy_c29_client_function_missing');
  return { functionCount: nfunc, daemonFunctions, clientFunctions, functionNamesSha256: namesHash.digest('hex') };
}

export function inspectAlloyC29Closure(bytes) {
  const content = bytes.toString('utf8');
  assert.ok(content.endsWith('\n') && !content.includes('\r'), 'alloy_c29_closure_format');
  const packages = content.trimEnd().split('\n');
  assert.ok(packages.length > 1000 && packages.every(name => /^[a-zA-Z0-9._/+~-]+$/.test(name)),
    'alloy_c29_closure_packages');
  assert.deepEqual(packages, [...new Set(packages)].sort(), 'alloy_c29_closure_order');
  assert.ok(packages.includes('github.com/grafana/alloy/otel_engine') &&
    packages.includes(MODULE + '/client'), 'alloy_c29_closure_positive_control');
  assert.ok(!packages.some(isDaemon), 'alloy_c29_daemon_package_present');
  return { packageCount: packages.length, daemonPackages: 0, closureSha256: sha256(bytes) };
}

export async function adjudicateAlloyC29Image(report, reportBytes, immutableImageId, scratch) {
  const base = { schema: 'otziv-alloy-c29-adjudication-v1', rawReportSha256: sha256(reportBytes),
    imageConfigId: report.Metadata?.ImageID, immutableImageId, rawReportModified: false, decisions: [] };
  if (labelsOf(report)['com.otziv.security.proof'] !== C29_ALLOY_PROOF_LABEL) {
    return { ...base, status: 'NOT_APPLICABLE' };
  }
  try {
    const labels = labelsOf(report);
    assert.equal(labels['org.opencontainers.image.source'], SOURCE, 'alloy_c29_source');
    assert.equal(labels['org.opencontainers.image.revision'], REVISION, 'alloy_c29_revision');
    assert.equal(labels['org.opencontainers.image.version'], VERSION, 'alloy_c29_version');
    assert.match(immutableImageId, /^sha256:[a-f0-9]{64}$/, 'alloy_c29_image_id');
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
      new Set(decisions.map(item => item.cve)).size === decisions.length, 'alloy_c29_findings');
    const inspect = JSON.parse(await run('docker', ['image', 'inspect', immutableImageId]))[0];
    assert.equal(inspect.Id, immutableImageId, 'alloy_c29_immutable_image_identity');
    assert.match(report.Metadata.ImageID || '', /^sha256:[a-f0-9]{64}$/, 'alloy_c29_image_config_id');
    assert.deepEqual(inspect.Config.Labels, labels, 'alloy_c29_image_labels');
    assert.deepEqual(inspect.RootFS.Layers, report.Metadata.ImageConfig.rootfs.diff_ids, 'alloy_c29_image_rootfs');
    const directory = join(scratch, 'alloy-c29-' + randomUUID());
    await mkdir(directory);
    let container;
    try {
      container = (await run('docker', ['create', '--network', 'none', '--entrypoint', '/not-executed',
        immutableImageId])).trim();
      assert.match(container, /^[a-f0-9]{64}$/, 'alloy_c29_container_identity');
      const binary = join(directory, 'alloy'), closure = join(directory, 'alloy.packages.txt');
      await run('docker', ['cp', container + ':/usr/bin/alloy', binary], { timeoutMs: 120_000 });
      await run('docker', ['cp', container + ':/usr/share/otziv-build/alloy.packages.txt', closure],
        { timeoutMs: 120_000 });
      const binaryBytes = await readFile(binary);
      const functions = inspectAlloyC29Functions(binaryBytes);
      const packages = inspectAlloyC29Closure(await readFile(closure));
      const buildInfo = await run('docker', ['run', '--rm', '--network', 'none', '--read-only',
        '--cap-drop=ALL', '--security-opt=no-new-privileges:true', '--memory', '512m', '--pids-limit', '64',
        '--mount', `type=bind,source=${directory},target=/input,readonly`, READER,
        'go', 'version', '-m', '/input/alloy'], { timeoutMs: 120_000 });
      assert.ok(buildInfo.split(/\r?\n/)[0].endsWith(': go1.27.2') &&
        buildInfo.includes(`\tdep\t${MODULE}\t${MODULE_VERSION}\t`), 'alloy_c29_build_info');
      return { ...base, status: 'EXACT_BINARY_AFFECTED_CODE_ABSENT', binarySha256: sha256(binaryBytes),
        buildInfoSha256: sha256(Buffer.from(buildInfo)), buildInfoReader: READER,
        inspectedBinaryExecuted: false, ...functions, ...packages, decisions };
    } finally {
      if (container) await run('docker', ['rm', '-f', container]).catch(() => {});
      await rm(directory, { recursive: true, force: true });
    }
  } catch (error) {
    return { ...base, status: 'REJECTED',
      reason: /^alloy_c29_[a-z_]+$/.test(error.message) ? error.message : 'alloy_c29_proof_failed' };
  }
}
