import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

// Review context is not an exclusion or proof of reachability.
export function reviewContext(packageName, type, classification) {
  const operatingSystem = classification === 'os-pkgs'
    || ['debian', 'ubuntu', 'alpine', 'redhat', 'rocky', 'alma', 'amazon', 'oracle', 'suse', 'photon', 'wolfi', 'chainguard', 'mariner', 'azurelinux'].includes(type);
  if (!operatingSystem) return 'APPLICATION_LIBRARY_REVIEW_REQUIRED';
  if (/^chromium/.test(packageName)) return 'BROWSER_UNTRUSTED_CONTENT_SURFACE';
  if (/^(perl-base|util-linux(?:-extra)?|mount|bsdutils|gzip|ncurses-(base|bin)|xdg-utils|systemd(?:-sysv)?)$/.test(packageName))
    return 'SYSTEM_TOOL_PRECONDITIONS_REQUIRE_REVIEW';
  return 'NATIVE_LIBRARY_REACHABILITY_UNPROVEN';
}

export function buildTriage(report, bytes) {
  if (!report.Results?.length) throw new Error('triage_report_empty');
  const findings = report.Results.flatMap(result => (result.Vulnerabilities || [])
    .filter(finding => ['HIGH', 'CRITICAL'].includes(finding.Severity))
    .map(finding => ({ package: finding.PkgName, path: finding.PkgPath || null,
      installed: finding.InstalledVersion, cve: finding.VulnerabilityID, severity: finding.Severity,
      vendorStatus: finding.Status || 'unknown', fixedVersion: finding.FixedVersion || null,
      title: finding.Title || null, primaryURL: finding.PrimaryURL || null,
      sourceType: result.Type || null, sourceClass: result.Class || null,
      context: reviewContext(finding.PkgName, result.Type, result.Class),
      disposition: finding.FixedVersion ? 'FIX_REQUIRED' : 'OPEN_VENDOR_UNFIXED_REVIEW',
      reachability: 'NOT_PROVEN', riskAcceptance: null, acceptanceExpiry: null })));
  return { schema: 'otziv-runtime-triage-v1', scanCreatedAt: report.CreatedAt,
    imageId: report.Metadata?.ImageID || null, reportSHA256: createHash('sha256').update(bytes).digest('hex'),
    policy: 'Context only. No finding is excluded or accepted by this file.', findings };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  if (process.argv.length !== 4) throw new Error('usage_triage_report_output');
  const bytes = await readFile(process.argv[2]);
  await writeFile(process.argv[3], JSON.stringify(buildTriage(JSON.parse(bytes), bytes), null, 2) + '\n');
}
