import { copyFile, mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { run, startProcess } from '../recovery/process.mjs';
import { assertLocalDocker } from '../recovery/drill.mjs';
import { buildTriage } from './triage-report.mjs';
import { adjudicateGrafanaImage, effectiveScanSummary } from './grafana-tempo-adjudication.mjs';
import { adjudicateAlloyImage, effectiveAlloySummary } from './alloy-daemon-adjudication.mjs';

export const TRIVY_IMAGE = 'aquasec/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969'; // 0.74.0

export function scannerUserArguments(platform = process.platform, uid = process.getuid?.(), gid = process.getgid?.()) {
  // A root process without DAC_OVERRIDE cannot write a runner-owned 0755 bind.
  // Match the owner of mkdtemp on POSIX; Windows Docker mounts use their own ACL mapping.
  if (platform === 'win32') return [];
  if (!Number.isSafeInteger(uid) || uid < 0 || !Number.isSafeInteger(gid) || gid < 0) {
    throw new Error('scanner_local_identity_missing');
  }
  return ['--user', `${uid}:${gid}`];
}

export function summarizeReport(report, requireJava = false) {
  if (!Array.isArray(report.Results) || !report.Results.length) throw new Error('scanner_no_supported_artifacts');
  if (requireJava && !report.Results.some(result => ['jar', 'pom'].includes(result.Type))) throw new Error('scanner_java_artifact_missing');
  const findings = report.Results.flatMap(result => result.Vulnerabilities || []);
  return { schema: 'otziv-vulnerability-scan-v1', artifactCount: report.Results.length,
    high: findings.filter(item => item.Severity === 'HIGH').length,
    critical: findings.filter(item => item.Severity === 'CRITICAL').length,
    unfixedHighOrCritical: findings.filter(item => ['HIGH', 'CRITICAL'].includes(item.Severity) && !item.FixedVersion).length,
    blockingFixedHighOrCritical: findings.filter(item => ['HIGH', 'CRITICAL'].includes(item.Severity) && item.FixedVersion).length };
}

export async function scan(kind, source, output) {
  if (!['image', 'java'].includes(kind)) throw new Error('scanner_mode_invalid');
  await assertLocalDocker();
  const destination = resolve(output);
  const userArguments = scannerUserArguments();
  await mkdir(dirname(destination), { recursive: true });
  const local = await mkdtemp(join(tmpdir(), 'otziv-scan-'));
  try {
    const input = join(local, 'input'), cache = join(local, 'cache'), results = join(local, 'results'), scratch = join(local, 'scratch');
    await Promise.all([input, cache, results, scratch].map(path => mkdir(path)));
    let immutableImageId;
    if (kind === 'image') {
      const inspected = JSON.parse(await run('docker', ['image', 'inspect', source]));
      immutableImageId = inspected[0]?.Id;
      if (!/^sha256:[a-f0-9]{64}$/.test(immutableImageId || '')) throw new Error('scanner_image_identity_missing');
      await run('docker', ['save', '--output', join(input, 'image.tar'), immutableImageId], { timeoutMs: 300_000 });
    }
    else {
      const jars = (await readdir(source)).filter(name => name.endsWith('.jar'));
      if (!jars.length) throw new Error('packaged_maven_jar_missing');
      for (const jar of jars) await copyFile(join(source, jar), join(input, basename(jar)));
    }
    // Scan an exported image, never expose the Docker socket to a third-party scanner.
    const scanner = startProcess('docker', ['run', '--rm', ...userArguments, '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges:true',
      '--tmpfs', '/tmp:rw,nosuid,size=512m', '--memory', '2g', '--pids-limit', '256',
      '--mount', `type=bind,source=${input},target=/input,readonly`,
      '--mount', `type=bind,source=${cache},target=/cache`,
      // Java DB alone exceeds 900 MiB compressed; keep bounded-lifetime scratch on disk,
      // not in a small /tmp tmpfs that can fail before any JAR is analyzed.
      '--mount', `type=bind,source=${scratch},target=/scratch`, '--env', 'TMPDIR=/scratch',
      '--mount', `type=bind,source=${results},target=/results`, TRIVY_IMAGE,
      // JAR analysis is intentionally enabled only in Trivy's post-build rootfs/image mode.
      kind === 'image' ? 'image' : 'rootfs', '--cache-dir', '/cache', '--timeout', '20m',
      '--scanners', 'vuln', '--ignorefile', '/dev/null', '--list-all-pkgs', '--format', 'json', '--output', '/results/report.json',
      ...(kind === 'image' ? ['--input', '/input/image.tar'] : ['--pkg-types', 'library', '/input'])], { timeoutMs: 1_300_000 });
    // Only this scanner receives no secrets or host environment. Keep bounded diagnostic
    // evidence for DB, archive and resource failures; recovery subprocess logging stays private.
    let diagnostic = '';
    scanner.child.stderr.on('data', chunk => { diagnostic = (diagnostic + chunk.toString('utf8')).slice(-512_000); });
    scanner.collect(); scanner.child.stdin.end();
    try { await scanner.completed; }
    finally { await writeFile(destination.replace(/\.json$/, '') + '.scanner.log', diagnostic); }
    const reportBytes = await readFile(join(results, 'report.json'));
    const report = JSON.parse(reportBytes);
    await copyFile(join(results, 'report.json'), destination);
    const summary = summarizeReport(report, kind === 'java');
    await writeFile(destination.replace(/\.json$/, '') + '.triage.json', JSON.stringify(buildTriage(report, reportBytes), null, 2) + '\n');
    await run('docker', ['run', '--rm', ...userArguments, '--network', 'none', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges:true',
      '--tmpfs', '/tmp:rw,nosuid,size=128m', '--memory', '1g', '--pids-limit', '128',
      '--mount', `type=bind,source=${results},target=/results`, TRIVY_IMAGE,
      'convert', '--format', 'cyclonedx', '--output', '/results/sbom.cdx.json', '/results/report.json'], { timeoutMs: 120_000 });
    const sbom = JSON.parse(await readFile(join(results, 'sbom.cdx.json'), 'utf8'));
    if (sbom.bomFormat !== 'CycloneDX' || !sbom.components?.length) throw new Error('scanner_sbom_missing_components');
    await copyFile(join(results, 'sbom.cdx.json'), destination.replace(/\.json$/, '') + '.sbom.cdx.json');
    // Preserve ALL findings above. The automatic remediation gate blocks actionable fixes;
    // unresolved vendor findings remain explicit release risks, never implicit exemptions.
    const adjudication = kind === 'image' ? await adjudicateGrafanaImage(report, reportBytes, immutableImageId, scratch) : null;
    const alloy = kind === 'image' ? await adjudicateAlloyImage(report, reportBytes, immutableImageId, scratch) : null;
    if (adjudication) await writeFile(destination.replace(/\.json$/, '') + '.adjudications.json', JSON.stringify({ ...adjudication, alloy }, null, 2) + '\n');
    return { ...effectiveAlloySummary(effectiveScanSummary(summary, adjudication), alloy), scannerImage: TRIVY_IMAGE };
  } finally {
    // local is the exact fresh directory returned by mkdtemp, never a configured parent.
    await rm(local, { recursive: true, force: true });
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    if (process.argv.length !== 5) throw new Error('usage_scan_kind_source_output');
    const result = await scan(...process.argv.slice(2));
    console.log(JSON.stringify(result)); if (result.result !== 'PASS') process.exitCode = 1;
  } catch (error) {
    console.error(JSON.stringify({ result: 'FAIL', code: /^[a-z_]+$/.test(error.message || '') ? error.message : 'scan_failed' }));
    process.exitCode = 1;
  }
}
