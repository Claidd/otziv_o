[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [long]$ExpectedVersionCode,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedVersionName,

    [string]$ExpectedPackage = "com.hunt.otziv",

    [string]$ExpectedSignerSha256 = "A15A162AFE1F808F9586DD3F129F9E61F4BE49CCFF708CA99C6A0714004251D5",

    [string]$AndroidSdkPath = "",

    [switch]$PassThru,

    [switch]$Quiet
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Normalize-CertificateFingerprint {
    param([Parameter(Mandatory = $true)][string]$Value)

    return ($Value -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
}

function Read-AndroidSdkFromLocalProperties {
    $localPropertiesPath = Join-Path $PSScriptRoot "..\android\local.properties"
    if (-not (Test-Path -LiteralPath $localPropertiesPath -PathType Leaf)) {
        return ""
    }

    foreach ($line in (Get-Content -LiteralPath $localPropertiesPath)) {
        if ($line -match '^\s*sdk\.dir\s*=\s*(.+?)\s*$') {
            return $Matches[1].Replace('\:', ':').Replace('\\', '\')
        }
    }
    return ""
}

function Resolve-AndroidSdkDirectory {
    param([string]$ConfiguredPath)

    $candidates = @(
        $ConfiguredPath,
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Read-AndroidSdkFromLocalProperties),
        $(if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { Join-Path $env:LOCALAPPDATA "Android\Sdk" } else { "" }),
        $(if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) { Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk" } else { "" }),
        $(if (-not [string]::IsNullOrWhiteSpace($env:HOME)) { Join-Path $env:HOME "Android\Sdk" } else { "" }),
        $(if (-not [string]::IsNullOrWhiteSpace($env:HOME)) { Join-Path $env:HOME "Library\Android\sdk" } else { "" })
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return ""
}

function Get-BuildToolDirectories {
    param([string]$SdkDirectory)

    if ([string]::IsNullOrWhiteSpace($SdkDirectory)) {
        return @()
    }
    $buildToolsDirectory = Join-Path $SdkDirectory "build-tools"
    if (-not (Test-Path -LiteralPath $buildToolsDirectory -PathType Container)) {
        return @()
    }

    return @(Get-ChildItem -LiteralPath $buildToolsDirectory -Directory | Sort-Object @{
        Expression = {
            $numericVersion = $_.Name -replace '[^0-9.].*$', ''
            try {
                [version]$numericVersion
            } catch {
                [version]'0.0'
            }
        }
        Descending = $true
    })
}

function Find-AndroidBuildTool {
    param(
        [Parameter(Mandatory = $true)][string[]]$BaseNames,
        [string]$SdkDirectory
    )

    $fileNames = foreach ($baseName in $BaseNames) {
        "$baseName.exe"
        "$baseName.bat"
        $baseName
    }

    foreach ($directory in Get-BuildToolDirectories -SdkDirectory $SdkDirectory) {
        foreach ($fileName in $fileNames) {
            $candidate = Join-Path $directory.FullName $fileName
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                return $candidate
            }
        }
    }

    foreach ($fileName in $fileNames) {
        $command = Get-Command $fileName -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $command) {
            return $command.Source
        }
    }

    throw "Android build tool not found: $($BaseNames -join ' or '). Install Android SDK Build-Tools or set ANDROID_HOME."
}

function Invoke-AndroidBuildTool {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$DisplayName
    )

    $output = @(& $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $summary = ($output | Select-Object -First 8) -join " | "
        throw "$DisplayName rejected the APK (exit code $exitCode). $summary"
    }
    return $output
}

if ($ExpectedVersionCode -le 0) {
    throw "ExpectedVersionCode must be positive."
}
if ([string]::IsNullOrWhiteSpace($ExpectedVersionName)) {
    throw "ExpectedVersionName is required."
}
if ([string]::IsNullOrWhiteSpace($ExpectedPackage)) {
    throw "ExpectedPackage is required."
}

$normalizedExpectedSigner = Normalize-CertificateFingerprint -Value $ExpectedSignerSha256
if ($normalizedExpectedSigner -notmatch '^[0-9A-F]{64}$') {
    throw "ExpectedSignerSha256 must contain exactly 64 hexadecimal characters."
}

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedApk -PathType Leaf)) {
    throw "APK file was not found."
}
if ([System.IO.Path]::GetExtension($resolvedApk) -ine '.apk') {
    throw "Release artifact must have the .apk extension."
}

$sdkDirectory = Resolve-AndroidSdkDirectory -ConfiguredPath $AndroidSdkPath
$apkSigner = Find-AndroidBuildTool -BaseNames @('apksigner') -SdkDirectory $sdkDirectory
$aapt = Find-AndroidBuildTool -BaseNames @('aapt', 'aapt2') -SdkDirectory $sdkDirectory

$signatureOutput = Invoke-AndroidBuildTool `
        -FilePath $apkSigner `
        -Arguments @('verify', '--verbose', '--print-certs', $resolvedApk) `
        -DisplayName 'apksigner'
$signatureText = $signatureOutput -join "`n"

$signerCountMatch = [regex]::Match($signatureText, '(?im)^Number of signers:\s*(\d+)\s*$')
if (-not $signerCountMatch.Success -or [int]$signerCountMatch.Groups[1].Value -ne 1) {
    throw "APK must contain exactly one verified signer."
}
if ($signatureText -notmatch '(?im)^Verified using v2 scheme .*:\s*true\s*$') {
    throw "APK Signature Scheme v2 verification is required for the supported Android 7+ fleet."
}

$certificateMatches = [regex]::Matches(
    $signatureText,
    '(?im)certificate SHA-256 digest:\s*([0-9A-Fa-f]{64})\s*$'
)
$actualSigners = @(
    $certificateMatches |
        ForEach-Object { Normalize-CertificateFingerprint -Value $_.Groups[1].Value } |
        Sort-Object -Unique
)
if ($actualSigners.Count -ne 1) {
    throw "Unable to determine one unambiguous APK signing certificate."
}
if ($actualSigners[0] -cne $normalizedExpectedSigner) {
    throw "APK signer certificate does not match the approved production signer."
}

$badgingOutput = Invoke-AndroidBuildTool `
        -FilePath $aapt `
        -Arguments @('dump', 'badging', $resolvedApk) `
        -DisplayName 'aapt/aapt2'
$packageLine = $badgingOutput | Where-Object { $_ -match '^package:' } | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($packageLine)) {
    throw "aapt/aapt2 did not return APK package metadata."
}

$packageMatch = [regex]::Match($packageLine, "(?:^|\s)name='([^']+)'")
$versionCodeMatch = [regex]::Match($packageLine, "(?:^|\s)versionCode='([0-9]+)'")
$versionNameMatch = [regex]::Match($packageLine, "(?:^|\s)versionName='([^']*)'")
if (-not $packageMatch.Success -or -not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw "APK package metadata is incomplete."
}

$actualPackage = $packageMatch.Groups[1].Value
$actualVersionCode = [long]$versionCodeMatch.Groups[1].Value
$actualVersionName = $versionNameMatch.Groups[1].Value
if (-not [string]::Equals($actualPackage, $ExpectedPackage, [System.StringComparison]::Ordinal)) {
    throw "APK package name does not match the expected application."
}
if ($actualVersionCode -ne $ExpectedVersionCode) {
    throw "APK versionCode does not match the expected release metadata."
}
if (-not [string]::Equals($actualVersionName, $ExpectedVersionName, [System.StringComparison]::Ordinal)) {
    throw "APK versionName does not match the expected release metadata."
}
if ($badgingOutput | Where-Object { $_ -match '^application-debuggable' }) {
    throw "Debuggable APK cannot be used as a production release."
}

$artifactSha256 = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToUpperInvariant()
$result = [pscustomobject]@{
    ApkPath = $resolvedApk
    PackageName = $actualPackage
    VersionCode = $actualVersionCode
    VersionName = $actualVersionName
    SignerSha256 = $actualSigners[0]
    ArtifactSha256 = $artifactSha256
}

if (-not $Quiet) {
    Write-Host "Android release APK verified: package=$actualPackage, version=$actualVersionName, code=$actualVersionCode, signer=$($actualSigners[0])."
}
if ($PassThru) {
    $result
}
