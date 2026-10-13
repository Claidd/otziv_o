// The C23 PostgreSQL runtime contains a gosu executable built with Go 1.27.1.
// Inspect the exact stopped-image binary before dismissing CVEs in unlinked
// net/http and crypto/tls packages.
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { run } from '../recovery/process.mjs';

export const GOSU_READER = 'golang:1.27.2-bookworm@sha256:5cf287a799e6b94384bad13d16b14904c531f51ba65792237e122ce42b392f61';
export const GOSU_SHA256 = '1c8ba2a839b4bf9048ebb035f7e4a682c982120d10feed65dd70e0ba250f7401';
const ROOTFS = [
  'sha256:3412f6e84bf5df2d548131d5a86650e84d37ed1758ea532826a7a25477fc8945',
  'sha256:5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef',
  'sha256:e9ea9f91c84d0aef4aa8e972f69267500c22cd0348233f2d30882d9c143ca91e',
];
const CVES = new Set(['CVE-2026-78667', 'CVE-2026-78669', 'CVE-2026-97031']);
const sha256 = value => createHash('sha256').update(value).digest('hex');

export function matchGosuFindings(report, postgres) {
  assert.equal(postgres?.status, 'EXACT_RUNTIME_VERIFIED', 'gosu_postgres_unverified');
  const labels = report.Metadata?.ImageConfig?.config?.Labels || {};
  assert.equal(labels['com.otziv.reviewed-component'], 'postgres', 'gosu_component_changed');
  assert.equal(labels['com.otziv.publication.revision'], 'f2239d547142ad4e73f0b7f4b25799f410a240b9', 'gosu_publication_changed');
  assert.equal(labels['com.otziv.gosu.source'], 'https://github.com/tianon/gosu/tree/6456aaa0f3c854d199d0f037f068eb97515b7513', 'gosu_source_changed');
  assert.deepEqual(report.Metadata.ImageConfig.rootfs.diff_ids, ROOTFS, 'gosu_rootfs_changed');
  const decisions = [];
  for (const [resultIndex, result] of report.Results.entries()) {
    if (result.Target !== 'usr/local/bin/gosu') continue;
    for (const [findingIndex, finding] of (result.Vulnerabilities || []).entries()) {
      if (!CVES.has(finding.VulnerabilityID)) continue;
      assert.equal(finding.PkgName, 'stdlib', 'gosu_package_changed');
      assert.equal(finding.InstalledVersion, 'v1.27.1', 'gosu_version_changed');
      assert.equal(finding.Severity, 'HIGH', 'gosu_severity_changed');
      assert.match(finding.FixedVersion || '', /(?:^|, )1\.27\.2(?:$|,)/, 'gosu_fix_changed');
      decisions.push({ resultIndex, findingIndex, cve: finding.VulnerabilityID,
        package: 'stdlib', version: 'v1.27.1', state: 'not_affected',
        justification: 'vulnerable_net_http_and_crypto_tls_packages_unlinked',
        fixedHighOrCritical: true, unfixedHighOrCritical: false });
    }
  }
  assert.equal(decisions.length, CVES.size, 'gosu_findings_changed');
  assert.deepEqual(new Set(decisions.map(item => item.cve)), CVES, 'gosu_findings_changed');
  return decisions;
}

export function validateGosuInspection(output, binarySha256) {
  assert.equal(binarySha256, GOSU_SHA256, 'gosu_binary_changed');
  assert.match(output, /^\/tmp\/gosu: go1\.27\.1\r?$/m, 'gosu_build_version_changed');
  assert.match(output, /^SYMBOL_COUNT=([2-9][0-9]{3}|[1-9][0-9]{4,})\r?$/m, 'gosu_symbols_unavailable');
  assert.match(output, /^AFFECTED_SYMBOL_COUNT=0\r?$/m, 'gosu_affected_packages_linked');
  return Number(/^SYMBOL_COUNT=(\d+)\r?$/m.exec(output)[1]);
}

export async function adjudicatePostgresGosu(report, bytes, imageId, scratch, postgres) {
  if (postgres?.status !== 'EXACT_RUNTIME_VERIFIED') return null;
  let sourceContainer, readerContainer;
  let stage = 'match';
  const directory = join(scratch, `gosu-proof-${randomUUID()}`);
  try {
    const decisions = matchGosuFindings(report, postgres);
    stage = 'inspect';
    assert.match(imageId, /^sha256:[a-f0-9]{64}$/, 'gosu_image_id_invalid');
    const inspected = JSON.parse(await run('docker', ['image', 'inspect', imageId]))[0];
    assert.deepEqual(inspected.RootFS.Layers, ROOTFS, 'gosu_inspected_rootfs_changed');
    await mkdir(directory);
    stage = 'source_create';
    const sourceName = `otziv-gosu-source-${randomUUID()}`;
    sourceContainer = (await run('docker', ['create', '--name', sourceName, '--network', 'none',
      '--entrypoint', '/not-executed', imageId])).trim();
    assert.match(sourceContainer, /^[a-f0-9]{64}$/, 'gosu_source_container_invalid');
    const binary = join(directory, 'gosu');
    stage = 'source_copy';
    await run('docker', ['cp', `${sourceContainer}:/usr/local/bin/gosu`, binary]);
    const binarySha256 = sha256(await readFile(binary));
    assert.equal(binarySha256, GOSU_SHA256, 'gosu_binary_changed');
    const script = 'set -eu; go version -m /tmp/gosu; go tool nm /tmp/gosu > /tmp/symbols; ' +
      'echo SYMBOL_COUNT=$(wc -l < /tmp/symbols); ' +
      "echo AFFECTED_SYMBOL_COUNT=$(grep -Ec '(^|[[:space:]])(net/http|crypto/tls)[./]' /tmp/symbols || :)";
    const readerName = `otziv-gosu-reader-${randomUUID()}`;
    stage = 'reader_create';
    readerContainer = (await run('docker', ['create', '--name', readerName, '--network', 'none',
      '--cap-drop=ALL', '--security-opt=no-new-privileges:true', '--memory', '512m', '--pids-limit', '128',
      '--env', 'GOMAXPROCS=2', '--env', 'GOFLAGS=-p=2', '--env', 'GOTOOLCHAIN=local',
      '--entrypoint', '/bin/sh', GOSU_READER, '-ec', script])).trim();
    assert.match(readerContainer, /^[a-f0-9]{64}$/, 'gosu_reader_container_invalid');
    stage = 'reader_copy';
    await run('docker', ['cp', binary, `${readerContainer}:/tmp/gosu`]);
    stage = 'reader_start';
    const output = await run('docker', ['start', '-a', readerContainer], { timeoutMs: 120_000 });
    const symbolCount = validateGosuInspection(output, binarySha256);
    return { schema: 'otziv-postgres-gosu-adjudication-v1', status: 'EXACT_BINARY_AFFECTED_CODE_ABSENT',
      rawReportSha256: sha256(bytes), immutableImageId: imageId, binarySha256,
      reader: GOSU_READER, inspectedBinaryExecuted: false, symbolCount, affectedSymbolCount: 0, decisions };
  } catch (error) {
    return { schema: 'otziv-postgres-gosu-adjudication-v1', status: 'REJECTED',
      rawReportSha256: sha256(bytes), immutableImageId: imageId, decisions: [],
      reason: `${stage}:${error.message || 'gosu_inspection_failed'}` };
  } finally {
    if (readerContainer) await run('docker', ['rm', '-f', readerContainer]).catch(() => {});
    if (sourceContainer) await run('docker', ['rm', '-f', sourceContainer]).catch(() => {});
    await rm(directory, { recursive: true, force: true });
  }
}

export function effectiveGosuSummary(summary, receipt) {
  if (!receipt || receipt.status === 'REJECTED') return receipt ? { ...summary, result: 'FAIL' } : summary;
  assert.equal(receipt.schema, 'otziv-postgres-gosu-adjudication-v1', 'gosu_receipt_schema');
  assert.equal(receipt.status, 'EXACT_BINARY_AFFECTED_CODE_ABSENT', 'gosu_receipt_status');
  assert.equal(receipt.binarySha256, GOSU_SHA256, 'gosu_receipt_binary');
  assert.equal(receipt.reader, GOSU_READER, 'gosu_receipt_reader');
  assert.equal(receipt.inspectedBinaryExecuted, false, 'gosu_receipt_execution');
  assert.ok(Number.isSafeInteger(receipt.symbolCount) && receipt.symbolCount >= 2000 &&
    receipt.affectedSymbolCount === 0, 'gosu_receipt_symbols');
  assert.deepEqual(new Set(receipt.decisions.map(item => item.cve)), CVES, 'gosu_receipt_findings');
  assert.equal(receipt.decisions.length, CVES.size, 'gosu_receipt_findings');
  assert.ok(receipt.decisions.every(item => item.state === 'not_affected' &&
    item.justification === 'vulnerable_net_http_and_crypto_tls_packages_unlinked' &&
    item.package === 'stdlib' && item.version === 'v1.27.1' &&
    item.fixedHighOrCritical === true && item.unfixedHighOrCritical === false), 'gosu_receipt_decisions');
  const effectiveBlockingFixedHighOrCritical = summary.effectiveBlockingFixedHighOrCritical - CVES.size;
  assert.ok(effectiveBlockingFixedHighOrCritical >= 0, 'gosu_receipt_counts');
  return { ...summary, adjudicatedGosuFixedHighOrCritical: CVES.size,
    effectiveBlockingFixedHighOrCritical,
    result: effectiveBlockingFixedHighOrCritical ? 'FAIL' : 'PASS' };
}
