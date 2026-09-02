import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (relativeUrl) => readFileSync(new URL(relativeUrl, import.meta.url), 'utf8');

const gradle = read('../android/app/build.gradle');
const verifier = read('../scripts/verify-android-release.ps1');
const releaseBuilder = read('../scripts/build-android-release.ps1');
const deploy = read('../../infrastructure/scripts/prod/deploy-prod.ps1');

test('release-producing Gradle graphs fail closed without complete signing material', () => {
  assert.match(gradle, /requiredReleaseSigningProperties/);
  assert.match(gradle, /configured storeFile is missing or is not a file/);
  assert.match(gradle, /startParameter\.taskNames/);
  assert.match(gradle, /releaseArtifactRequested/);
  assert.match(gradle, /Android release signing is not configured/);
  assert.match(gradle, /assemble\|bundle\|package\|install\|publish\|sign\|zipalign/);
});

test('release verifier pins identity, signer and internal APK metadata', () => {
  assert.match(verifier, /com\.hunt\.otziv/);
  assert.match(verifier, /A15A162AFE1F808F9586DD3F129F9E61F4BE49CCFF708CA99C6A0714004251D5/);
  assert.match(verifier, /'verify', '--verbose', '--print-certs'/);
  assert.match(verifier, /Number of signers/);
  assert.match(verifier, /Verified using v2 scheme/);
  assert.match(verifier, /'dump', 'badging'/);
  assert.match(verifier, /actualVersionCode -ne \$ExpectedVersionCode/);
  assert.match(verifier, /actualVersionName, \$ExpectedVersionName/);
  assert.match(verifier, /application-debuggable/);
  assert.match(verifier, /Join-Path \$resolvedCandidate "build-tools"/);
  assert.match(verifier, /Test-Path -LiteralPath \$buildToolsDirectory -PathType Container/);
  assert.match(verifier, /New-AsciiApkVerificationStage/);
  assert.match(verifier, /\[System\.IO\.Path\]::GetTempPath\(\)/);
  assert.match(verifier, /\[\^\\u0000-\\u007F\]/);
  assert.match(verifier, /Copy-Item -LiteralPath \$SourceApk -Destination \$stageApk/);
  assert.match(verifier, /Staged APK hash does not match the source artifact/);
  assert.match(verifier, /Source APK changed during Android build-tool verification/);
  assert.match(verifier, /Set-PrivateApkStagePermissions/);
  assert.match(verifier, /SetAccessRuleProtection\(\$true, \$false\)/);
  assert.match(verifier, /FileAttributes\]::ReparsePoint/);
  assert.match(verifier, /APK source must not be a symbolic link or another reparse point/);
  assert.match(verifier, /Remove-Item -LiteralPath \$stageApk -Force/);
  assert.match(verifier, /Remove-Item -LiteralPath \$stageDirectory -Force/);
  assert.doesNotMatch(verifier, /Remove-Item[^\r\n]+-Recurse/);
  assert.match(verifier, /Find-AndroidBuildTool -BaseNames @\('aapt2', 'aapt'\)/);
  assert.match(verifier, /\$runningOnWindows -and \$resolvedApk -match/);
  assert.match(verifier, /'--print-certs', \$verificationApk/);
  assert.match(verifier, /'dump', 'badging', \$verificationApk/);
  assert.doesNotMatch(verifier, /'--print-certs', \$resolvedApk/);
  assert.doesNotMatch(verifier, /'dump', 'badging', \$resolvedApk/);
});

test('release build verifies both Gradle output and immutable copied artifact', () => {
  assert.match(releaseBuilder, /assembleRelease/);
  assert.match(releaseBuilder, /-PotzivVersionCode=/);
  assert.match(releaseBuilder, /-PotzivVersionName=/);
  assert.match(releaseBuilder, /javaMajorVersion -gt 24/);
  assert.equal((releaseBuilder.match(/verify-android-release\.ps1/g) ?? []).length, 1);
  assert.match(releaseBuilder, /verifiedCopy\.ArtifactSha256 -cne \$verifiedBuild\.ArtifactSha256/);
  assert.match(releaseBuilder, /version artifacts are immutable/);
});

test('production deploy verifies selected APK before build and VPS access', () => {
  const verifierCall = deploy.indexOf('$mobileRelease = Confirm-MobileReleaseArtifact');
  const buildStart = deploy.indexOf('Write-Host "Building and pushing:"');
  const sshPreflight = deploy.indexOf('Write-Host "Checking VPS SSH access before build/push..."');
  const dockerBuild = deploy.indexOf('Invoke-External -FilePath "docker" -Arguments $buildArgs');

  assert.ok(verifierCall >= 0);
  assert.equal((deploy.match(/\$mobileRelease = Confirm-MobileReleaseArtifact/g) ?? []).length, 1);
  assert.ok(verifierCall < buildStart);
  assert.ok(verifierCall < sshPreflight);
  assert.ok(verifierCall < dockerBuild);
  assert.match(deploy, /VersionName = \$verified\.VersionName/);
  assert.match(deploy, /VersionCode = \[int\]\$verified\.VersionCode/);
  assert.match(deploy, /Mobile APK hash changed after verification and before bundle creation/);
});
