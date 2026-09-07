[CmdletBinding()]
param(
    [string]$Directory = (Join-Path $PSScriptRoot '../builds'),
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [string]$AndroidSdkPath = (Join-Path $env:LOCALAPPDATA 'Android/Sdk'),
    [string]$JavaHome = $env:JAVA_HOME
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $Directory).Path
$sdk = (Resolve-Path -LiteralPath $AndroidSdkPath).Path
if ($JavaHome) { $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$buildTools) { throw 'Installed Android build tools are required.' }
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$signer = Join-Path $buildTools.FullName 'apksigner.bat'
if (!(Test-Path -LiteralPath $aapt) -or !(Test-Path -LiteralPath $signer)) { throw 'aapt and apksigner are required.' }
$entries = @()
foreach ($apk in (Get-ChildItem -LiteralPath $source -File -Filter '*.apk' | Sort-Object Name)) {
    $badging = (& $aapt dump badging $apk.FullName 2>&1 | Out-String)
    $manifestVerified = $LASTEXITCODE -eq 0
    $signature = (& $signer verify --print-certs $apk.FullName 2>&1 | Out-String)
    $signatureVerified = $LASTEXITCODE -eq 0
    $package = [regex]::Match($badging, "package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'")
    $certificate = [regex]::Match($signature, '(?m)^(?:Signer #1|V[0-9]+ Signer):? certificate SHA-256 digest:\s*([a-fA-F0-9]{64})')
    $versionCode = if ($package.Success) { [long]$package.Groups[2].Value } else { $null }
    $filenameVersion = [regex]::Match($apk.Name, '-code([0-9]+)\.apk$')
    $entries += [ordered]@{
        file = $apk.Name
        bytes = $apk.Length
        sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        packageId = $(if ($package.Success) { $package.Groups[1].Value } else { $null })
        versionCode = $versionCode
        versionName = $(if ($package.Success) { $package.Groups[3].Value } else { $null })
        manifestVerified = $manifestVerified -and $package.Success
        signatureVerified = $signatureVerified -and $certificate.Success
        signerSha256 = $(if ($certificate.Success) { $certificate.Groups[1].Value.ToLowerInvariant() } else { $null })
        debuggable = $badging -match '(?m)^application-debuggable'
        filenameVersionMatches = $filenameVersion.Success -and $null -ne $versionCode -and [long]$filenameVersion.Groups[1].Value -eq $versionCode
    }
}
$report = [ordered]@{
    observedAt = [DateTime]::UtcNow.ToString('o')
    scope = 'Local artifact manifest/signature/hash inventory; file presence does not establish publication or a support policy.'
    sourceDirectory = $source
    buildToolsVersion = $buildTools.Name
    count = $entries.Count
    minimumSupported = $null
    entries = $entries
}
$destination = [IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $destination) { throw 'Refusing to overwrite existing inventory evidence.' }
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $destination -Encoding utf8
Write-Output "Inventoried $($entries.Count) local APK artifacts. No support minimum assigned."
if (@($entries | Where-Object { !$_.manifestVerified -or !$_.signatureVerified }).Count) {
    throw 'Some APK artifacts failed manifest/signature verification; inspect the inventory flags.'
}
