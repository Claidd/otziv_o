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
  const verifierCall = deploy.indexOf('verify-android-release.ps1');
  const buildStart = deploy.indexOf('Write-Host "Building and pushing:"');
  const vpsCheck = deploy.indexOf('Write-Host "Checking mobile APK state on VPS..."');

  assert.ok(verifierCall >= 0);
  assert.ok(verifierCall < buildStart);
  assert.ok(verifierCall < vpsCheck);
  assert.match(deploy, /VersionName = \$verified\.VersionName/);
  assert.match(deploy, /VersionCode = \[int\]\$verified\.VersionCode/);
  assert.match(deploy, /Mobile APK hash changed after verification and before bundle creation/);
});
