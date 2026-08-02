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
    Join-Path $mobileDirectory "builds\otziv-prod-release-v1.0.61-code61.apk"
} else {
    $ReleaseApkPath
}
$legacyDebugApk = if ([string]::IsNullOrWhiteSpace($LegacyDebugApkPath)) {
    Join-Path $mobileDirectory "builds\otziv-prod-debug-v1.0.53-code53.apk"
} else {
    $LegacyDebugApkPath
}

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
        -ExpectedVersionCode 61 `
        -ExpectedVersionName "1.0.61" `
        -PassThru `
        -Quiet
if ($verified.PackageName -cne "com.hunt.otziv" -or
        $verified.VersionCode -ne 61 -or
        $verified.VersionName -cne "1.0.61") {
    throw "Positive Android release contract returned unexpected metadata."
}
Write-Host "PASS: known code61 release is valid"

Assert-VerifierRejects -Name "wrong signer" -Arguments @{
    ApkPath = $releaseApk
    ExpectedVersionCode = 61
    ExpectedVersionName = "1.0.61"
    ExpectedSignerSha256 = ('00' * 32)
}
Assert-VerifierRejects -Name "wrong package" -Arguments @{
    ApkPath = $releaseApk
    ExpectedVersionCode = 61
    ExpectedVersionName = "1.0.61"
    ExpectedPackage = "com.hunt.not-otziv"
}
Assert-VerifierRejects -Name "wrong versionCode" -Arguments @{
    ApkPath = $releaseApk
    ExpectedVersionCode = 62
    ExpectedVersionName = "1.0.61"
}
Assert-VerifierRejects -Name "wrong versionName" -Arguments @{
    ApkPath = $releaseApk
    ExpectedVersionCode = 61
    ExpectedVersionName = "1.0.62"
}

if (Test-Path -LiteralPath $legacyDebugApk -PathType Leaf) {
    Assert-VerifierRejects -Name "debuggable APK" -Arguments @{
        ApkPath = $legacyDebugApk
        ExpectedVersionCode = 53
        ExpectedVersionName = "1.0.53"
    }
}

Write-Host "Android release verifier contract checks passed."
