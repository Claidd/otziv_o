[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [int]$VersionCode,

    [Parameter(Mandatory = $true)]
    [string]$VersionName,

    [string]$KeystorePropertiesPath = "",

    [string]$OutputDirectory = "",

    [switch]$Push
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory
    )

    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code ${LASTEXITCODE}: $FilePath"
        }
    } finally {
        Pop-Location
    }
}

function Get-JavaMajorVersion {
    param([string]$JavaHome)

    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        return $null
    }
    $javaExecutable = Join-Path $JavaHome "bin\java.exe"
    if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        return $null
    }

    $versionOutput = & $javaExecutable -version 2>&1
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    $versionText = $versionOutput -join "`n"
    if ($versionText -notmatch 'version\s+"([0-9]+)(?:\.([0-9]+))?') {
        return $null
    }

    $major = [int]$Matches[1]
    if ($major -eq 1 -and -not [string]::IsNullOrWhiteSpace($Matches[2])) {
        return [int]$Matches[2]
    }
    return $major
}

if ($VersionCode -le 0) {
    throw "VersionCode must be positive."
}
if ($VersionName -notmatch '^[0-9A-Za-z._-]{1,40}$') {
    throw "VersionName may contain only letters, digits, dot, underscore and dash."
}

$mobileDirectory = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$androidDirectory = Join-Path $mobileDirectory "android"
$defaultKeystoreProperties = Join-Path $androidDirectory "keystore.properties"
$selectedKeystoreProperties = if ([string]::IsNullOrWhiteSpace($KeystorePropertiesPath)) {
    $defaultKeystoreProperties
} elseif ([System.IO.Path]::IsPathRooted($KeystorePropertiesPath)) {
    $KeystorePropertiesPath
} else {
    Join-Path $mobileDirectory $KeystorePropertiesPath
}
if (-not (Test-Path -LiteralPath $selectedKeystoreProperties -PathType Leaf)) {
    throw "Android release keystore properties file is missing."
}
$resolvedKeystoreProperties = (Resolve-Path -LiteralPath $selectedKeystoreProperties).Path

$selectedOutputDirectory = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Join-Path $mobileDirectory "builds"
} elseif ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
} else {
    Join-Path $mobileDirectory $OutputDirectory
}

$javaMajorVersion = Get-JavaMajorVersion -JavaHome $env:JAVA_HOME
if ($null -eq $javaMajorVersion -or $javaMajorVersion -lt 17 -or $javaMajorVersion -gt 24) {
    . (Join-Path $PSScriptRoot "android-env.ps1")
    $javaMajorVersion = Get-JavaMajorVersion -JavaHome $env:JAVA_HOME
}
if ($null -eq $javaMajorVersion -or $javaMajorVersion -lt 17 -or $javaMajorVersion -gt 24) {
    throw "Android release build requires a supported JDK (17 through 24; project default is JDK 21)."
}

$npmCommand = Get-Command "npm.cmd" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $npmCommand) {
    $npmCommand = Get-Command "npm" -ErrorAction Stop | Select-Object -First 1
}
$syncScript = if ($Push) { "cap:sync:prod:push" } else { "cap:sync:prod" }
Invoke-CheckedCommand `
        -FilePath $npmCommand.Source `
        -Arguments @('run', $syncScript) `
        -WorkingDirectory $mobileDirectory

$isWindows = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
$gradleWrapper = Join-Path $androidDirectory $(if ($isWindows) { "gradlew.bat" } else { "gradlew" })
Invoke-CheckedCommand `
        -FilePath $gradleWrapper `
        -Arguments @(
            'clean',
            'assembleRelease',
            "-PotzivVersionCode=$VersionCode",
            "-PotzivVersionName=$VersionName",
            "-PotzivKeystorePropertiesFile=$resolvedKeystoreProperties"
        ) `
        -WorkingDirectory $androidDirectory

$builtApk = Join-Path $androidDirectory "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $builtApk -PathType Leaf)) {
    throw "Gradle completed without the expected signed release APK."
}

$verifier = Join-Path $PSScriptRoot "verify-android-release.ps1"
$verifiedBuild = & $verifier `
        -ApkPath $builtApk `
        -ExpectedVersionCode $VersionCode `
        -ExpectedVersionName $VersionName `
        -PassThru `
        -Quiet

New-Item -ItemType Directory -Path $selectedOutputDirectory -Force | Out-Null
$resolvedOutputDirectory = (Resolve-Path -LiteralPath $selectedOutputDirectory).Path
$releaseFileName = "otziv-prod-release-v$VersionName-code$VersionCode.apk"
$releasePath = Join-Path $resolvedOutputDirectory $releaseFileName

if (Test-Path -LiteralPath $releasePath -PathType Leaf) {
    $existingRelease = & $verifier `
            -ApkPath $releasePath `
            -ExpectedVersionCode $VersionCode `
            -ExpectedVersionName $VersionName `
            -PassThru `
            -Quiet
    if ($existingRelease.ArtifactSha256 -ceq $verifiedBuild.ArtifactSha256) {
        Write-Host "Identical verified Android release already exists: $releasePath"
        return
    }
    throw "A different artifact already exists for this Android versionCode; version artifacts are immutable."
}

$temporaryReleasePath = Join-Path $resolvedOutputDirectory (
    ".incoming-android-release-" + [System.Guid]::NewGuid().ToString("N") + ".apk"
)
try {
    Copy-Item -LiteralPath $builtApk -Destination $temporaryReleasePath
    $verifiedCopy = & $verifier `
            -ApkPath $temporaryReleasePath `
            -ExpectedVersionCode $VersionCode `
            -ExpectedVersionName $VersionName `
            -PassThru `
            -Quiet
    if ($verifiedCopy.ArtifactSha256 -cne $verifiedBuild.ArtifactSha256) {
        throw "Copied Android release hash does not match the verified build output."
    }
    Move-Item -LiteralPath $temporaryReleasePath -Destination $releasePath
} finally {
    Remove-Item -LiteralPath $temporaryReleasePath -Force -ErrorAction SilentlyContinue
}

Write-Host "Verified signed Android release created: $releasePath"
