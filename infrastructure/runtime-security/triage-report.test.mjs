import test from 'node:test';
import assert from 'node:assert/strict';
import { buildTriage } from './triage-report.mjs';

test('triage retains fixed/unfixed findings and never turns context into an exemption', () => {
  const report = { Results: [{ Type: 'debian', Vulnerabilities: [
    { PkgName: 'chromium', Severity: 'HIGH', VulnerabilityID: 'CVE-fixture-1' },
    { PkgName: 'perl-base', Severity: 'CRITICAL', VulnerabilityID: 'CVE-fixture-2', FixedVersion: '2' }
  ] }] };
  const triage = buildTriage(report, Buffer.from(JSON.stringify(report)));
  assert.equal(triage.findings.length, 2);
  assert.equal(triage.findings[0].context, 'BROWSER_UNTRUSTED_CONTENT_SURFACE');
  assert.equal(triage.findings[1].disposition, 'FIX_REQUIRED');
  for (const finding of triage.findings) {
    assert.equal(finding.riskAcceptance, null); assert.equal(finding.acceptanceExpiry, null);
    assert.equal(finding.reachability, 'NOT_PROVEN');
  }
  assert.throws(() => buildTriage({ Results: [] }, Buffer.from('')), /empty/);
});

test('non-Debian operating systems retain OS provenance and are not labelled application libraries', () => {
  const results = ['ubuntu', 'alpine', 'future-distro'].map(Type => ({ Type, Class: 'os-pkgs',
    Vulnerabilities: [{ PkgName: 'libc', Severity: 'HIGH', VulnerabilityID: 'CVE-fixture' }] }));
  results.push({ Type: 'jar', Class: 'lang-pkgs',
    Vulnerabilities: [{ PkgName: 'transport', Severity: 'HIGH', VulnerabilityID: 'CVE-java' }] });
  const findings = buildTriage({ Results: results }, Buffer.from('fixture')).findings;
  for (const item of findings.slice(0, 3)) {
    assert.equal(item.context, 'NATIVE_LIBRARY_REACHABILITY_UNPROVEN');
    assert.equal(item.sourceClass, 'os-pkgs');
    assert.ok(item.sourceType);
  }
  assert.equal(findings[3].context, 'APPLICATION_LIBRARY_REVIEW_REQUIRED');
});
