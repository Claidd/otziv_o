[CmdletBinding()]
param(
    [string]$ReleaseApkPath = "",
    [string]$LegacyDebugApkPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$mobileDirectory = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$verifier = Join-Path $PSScriptRoot "verify-android-release.ps1"
$releaseApk = if ([string]::IsNullOrWhiteSpace($ReleaseApkPath)) {
    Join-Path $mobileDirectory "builds\otziv-prod-release-v1.0.62-code62.apk"
} else {
    $ReleaseApkPath
}
$legacyDebugApk = if ([string]::IsNullOrWhiteSpace($LegacyDebugApkPath)) {
    Join-Path $mobileDirectory "builds\otziv-prod-debug-v1.0.53-code53.apk"
} else {
    $LegacyDebugApkPath
}
$verificationTemporaryRoot = [System.IO.Directory]::GetParent(
    (Join-Path ([System.IO.Path]::GetTempPath()) 'otziv-temp-path-probe')
).FullName
$stageDirectoriesBefore = @(
    Get-ChildItem -LiteralPath $verificationTemporaryRoot -Directory -Filter 'otziv-apk-verify-*' |
        ForEach-Object { $_.FullName }
)

function Assert-VerifierRejects {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][hashtable]$Arguments
    )

    $rejected = $false
    try {
        & $verifier @Arguments -Quiet | Out-Null
    } catch {
        $rejected = $true
    }
    if (-not $rejected) {
        throw "Negative Android release contract did not fail: $Name"
    }
    Write-Host "PASS: verifier rejected $Name"
}

$verified = & $verifier `
        -ApkPath $releaseApk `
        -ExpectedVersionCode 62 `
        -ExpectedVersionName "1.0.62" `
        -PassThru `
        -Quiet
if ($verified.PackageName -cne "com.hunt.otziv" -or
        $verified.VersionCode -ne 62 -or
        $verified.VersionName -cne "1.0.62") {
    throw "Positive Android release contract returned unexpected metadata."
}
Write-Host "PASS: release code62 is valid"

$unicodeTestDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
    "otziv-apk-unicode-путь-" + [System.Guid]::NewGuid().ToString("N")
)
$unicodeReleaseApk = Join-Path $unicodeTestDirectory "проверяемый-релиз.apk"
try {
    New-Item -ItemType Directory -Path $unicodeTestDirectory | Out-Null
    Copy-Item -LiteralPath $releaseApk -Destination $unicodeReleaseApk
    $unicodeSourceHash = (Get-FileHash -LiteralPath $unicodeReleaseApk -Algorithm SHA256).Hash.ToUpperInvariant()

    $unicodeVerified = & $verifier `
            -ApkPath $unicodeReleaseApk `
            -ExpectedVersionCode 62 `
            -ExpectedVersionName "1.0.62" `
            -PassThru `
            -Quiet
    $unicodeHashAfterVerification = (Get-FileHash -LiteralPath $unicodeReleaseApk -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($unicodeVerified.ArtifactSha256 -cne $unicodeSourceHash -or
            $unicodeHashAfterVerification -cne $unicodeSourceHash) {
        throw "Unicode-path verification changed the APK or returned an unexpected artifact hash."
    }
    if ($unicodeVerified.ApkPath -cne (Resolve-Path -LiteralPath $unicodeReleaseApk).Path) {
        throw "Unicode-path verification did not preserve the original artifact path in its result."
    }
    Write-Host "PASS: release APK verifies from a Unicode source path"
} finally {
    if (Test-Path -LiteralPath $unicodeReleaseApk -PathType Leaf) {
        Remove-Item -LiteralPath $unicodeReleaseApk -Force
    }
    if (Test-Path -LiteralPath $unicodeTestDirectory -PathType Container) {
        $unicodeTestDirectoryItem = Get-Item -LiteralPath $unicodeTestDirectory -Force
        $unicodeTestParent = [System.IO.Directory]::GetParent($unicodeTestDirectoryItem.FullName)
        $isExpectedTestDirectory = $null -ne $unicodeTestParent -and
            [string]::Equals(
                $unicodeTestParent.FullName,
                $verificationTemporaryRoot,
                [System.StringComparison]::OrdinalIgnoreCase
            ) -and
            $unicodeTestDirectoryItem.Name -match '^otziv-apk-unicode-путь-[0-9a-f]{32}$'
        if (-not $isExpectedTestDirectory -or
                ($unicodeTestDirectoryItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing to clean an unexpected or reparse-point Unicode APK test directory."
        }
        Remove-Item -LiteralPath $unicodeTestDirectory -Force
    }
}

Assert-VerifierRejects -Name "wrong signer" -Arguments @{
    ApkPath = $releaseApk
    ExpectedVersionCode = 62
    ExpectedVersionName = "1.0.62"
    ExpectedSignerSha256 = ('00' * 32)
}
Assert-VerifierRejects -Name "wrong package" -Arguments @{
    ApkPath = $releaseApk
    ExpectedVersionCode = 62
    ExpectedVersionName = "1.0.62"
    ExpectedPackage = "com.hunt.not-otziv"
}
Assert-VerifierRejects -Name "wrong versionCode" -Arguments @{
    ApkPath = $releaseApk
    ExpectedVersionCode = 63
    ExpectedVersionName = "1.0.62"
}
Assert-VerifierRejects -Name "wrong versionName" -Arguments @{
    ApkPath = $releaseApk
    ExpectedVersionCode = 62
    ExpectedVersionName = "1.0.63"
}

if (Test-Path -LiteralPath $legacyDebugApk -PathType Leaf) {
    Assert-VerifierRejects -Name "debuggable APK" -Arguments @{
        ApkPath = $legacyDebugApk
        ExpectedVersionCode = 53
        ExpectedVersionName = "1.0.53"
    }
}

$newStageDirectories = @(
    Get-ChildItem -LiteralPath $verificationTemporaryRoot -Directory -Filter 'otziv-apk-verify-*' |
        Where-Object { $stageDirectoriesBefore -cnotcontains $_.FullName }
)
if ($newStageDirectories.Count -ne 0) {
    throw "Android release verifier left a temporary staging directory after success or failure."
}
Write-Host "PASS: verifier cleans ASCII staging after successful and rejected APK checks"

Write-Host "Android release verifier contract checks passed."
