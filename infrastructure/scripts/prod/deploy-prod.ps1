param(
    [string]$DockerHubNamespace = "claid38",
    [string]$DockerLoginUsername = "",
    [string]$AppRepository = "otziv-app",
    [string]$WebRepository = "otziv-web",
    [string]$ExternalReviewWorkerRepository = "otziv-external-review-worker",
    [string]$Tag = (Get-Date -Format "yyyyMMdd-HHmmss"),
    [string]$VpsHost = "",
    [string]$VpsUser = "hunt",
    [ValidateRange(1, 65535)][int]$VpsPort = 22022,
    [string]$VpsPath = "/opt/otziv",
    [string]$SshKey = "",
    [string]$EnvFile = ".env.prod",
    [string]$RemoteEnvFile = ".env.prod",
    [switch]$DockerLogin,
    [switch]$SkipBuildPush,
    [switch]$SkipEnvUpload,
    [switch]$EnableExternalReviewWorker,
    [string]$MobileApkPath = "",
    [switch]$SkipMobileApkUpload,
    [string]$PreDeployBackupDirectory = "",
    [switch]$NoBuildCache,
    [switch]$AllowDirtyWorktree,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Show-Help {
    @'
Deploy Otziv production stack from this local computer.

Example:
  .\infrastructure\scripts\prod\deploy-prod.ps1 -VpsHost 95.213.248.152 -VpsUser hunt -VpsPort 22022 -VpsPath /docker -SshKey C:\Users\Hunt\.ssh\otziv_vps_ed25519 -RemoteEnvFile .env -SkipEnvUpload

Useful options:
  -DockerHubNamespace claid38     Docker Hub namespace or username.
  -DockerLoginUsername claid38    Docker Hub login username. Defaults to DockerHubNamespace.
  -Tag 20260507-1                Image tag. Defaults to current date/time.
  -EnvFile .env.prod             Local env file uploaded to VPS as .env.prod.
  -RemoteEnvFile .env            Env file name used on the VPS.
  -DockerLogin                   Run local docker login before build and push.
  -SkipBuildPush                 Skip build/push and deploy already published images for enabled services.
  -SkipEnvUpload                 Keep VPS env and update app/web/external-worker image tags in it.
  -EnableExternalReviewWorker    Opt in to building and running the external review checker (disabled by default).
  -MobileApkPath <path>          Publish this signed release APK. By default uses the highest code from mobile/builds.
  -SkipMobileApkUpload           Do not include a mobile APK in this deployment.
  -PreDeployBackupDirectory      Local directory for the mandatory encrypted pre-migration DB backup.
  -NoBuildCache                  Build images without Docker cache.
  -AllowDirtyWorktree            Deploy from modified inputs (unsafe emergency override).
'@ | Write-Host
}

function Format-RedactedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $redacted = [System.Collections.Generic.List[string]]::new()
    $redactNext = $false
    foreach ($argument in $Arguments) {
        if ($redactNext) {
            $redacted.Add('[REDACTED]')
            $redactNext = $false
            continue
        }
        if ($argument -match '(?i)^(--password|--client-secret|--secret|--token|-p)$') {
            $redacted.Add($argument)
            $redactNext = $true
        } elseif ($argument -match '(?i)^([^=]*(?:password|passwd|pwd|secret|token|api[_-]?key)[^=]*)=(.*)$') {
            $redacted.Add("$($Matches[1])=[REDACTED]")
        } elseif ($argument -match '(?i)^-p.+$') {
            $redacted.Add('-p[REDACTED]')
        } else {
            $redacted.Add($argument)
        }
    }
    return ((@($FilePath) + @($redacted)) -join ' ')
}

function Protect-SensitiveLocalPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
        $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $grant = if (Test-Path -LiteralPath $Path -PathType Container) { "*${sid}:(OI)(CI)F" } else { "*${sid}:F" }
        & icacls.exe $Path '/inheritance:r' '/grant:r' $grant '/grant:r' '*S-1-5-18:F' | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to restrict ACL on sensitive path: $Path"
        }
        return
    }

    $mode = if (Test-Path -LiteralPath $Path -PathType Container) { '700' } else { '600' }
    & chmod $mode -- $Path
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restrict permissions on sensitive path: $Path"
    }
}

function Assert-NoReparsePointInExistingPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    $cursor = [IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrWhiteSpace($cursor)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Sensitive backup path contains a reparse-point component: $cursor"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if ([string]::IsNullOrWhiteSpace($parent) -or
            $parent.Equals($cursor, [StringComparison]::OrdinalIgnoreCase)) {
            break
        }
        $cursor = $parent
    }
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $(Format-RedactedCommand -FilePath $FilePath -Arguments $Arguments)"
    }
}

function Invoke-ExternalWithRetry {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [int]$Attempts = 3,
        [int]$DelaySeconds = 5
    )

    $lastError = $null
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            Invoke-External -FilePath $FilePath -Arguments $Arguments
            return
        } catch {
            $lastError = $_
            if ($attempt -ge $Attempts) {
                break
            }
            $redactedCommand = Format-RedactedCommand -FilePath $FilePath -Arguments $Arguments
            Write-Warning "Command failed on attempt ${attempt}/${Attempts}: ${redactedCommand}. Retrying in ${DelaySeconds}s..."
            Start-Sleep -Seconds $DelaySeconds
        }
    }

    throw $lastError
}

function Copy-DeployBundle {
    param(
        [Parameter(Mandatory = $true)][string[]]$ScpArgs,
        [Parameter(Mandatory = $true)][string]$BundlePath,
        [Parameter(Mandatory = $true)][string]$Remote,
        [Parameter(Mandatory = $true)][string]$RemoteBundle
    )

    try {
        Invoke-ExternalWithRetry -FilePath "scp" -Arguments ($ScpArgs + @($BundlePath, "${Remote}:$RemoteBundle")) -Attempts 3 -DelaySeconds 10
        return
    } catch {
        Write-Warning "Regular scp upload failed. Trying legacy scp protocol (-O)."
    }

    Invoke-ExternalWithRetry -FilePath "scp" -Arguments (@("-O") + $ScpArgs + @($BundlePath, "${Remote}:$RemoteBundle")) -Attempts 2 -DelaySeconds 10
}

function ConvertTo-BashSingleQuoted {
    param([Parameter(Mandatory = $true)][string]$Value)
    $singleQuote = [string][char]39
    $doubleQuote = [string][char]34
    $escapedSingleQuote = $singleQuote + $doubleQuote + $singleQuote + $doubleQuote + $singleQuote
    return $singleQuote + $Value.Replace($singleQuote, $escapedSingleQuote) + $singleQuote
}

function Copy-DeployPath {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$StageRoot,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )

    $source = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Required deploy path is missing: $RelativePath"
    }

    $destination = Join-Path $StageRoot $RelativePath
    $destinationParent = Split-Path -Parent $destination
    New-Item -ItemType Directory -Path $destinationParent -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
}

function Set-EnvFileValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()]
        [Parameter(Mandatory = $true)][string]$Value
    )

    $line = "$Name=$Value"
    $pattern = "^\s*$([regex]::Escape($Name))="
    $found = $false
    $updated = foreach ($existingLine in Get-Content -LiteralPath $Path) {
        if ($existingLine -match $pattern) {
            $found = $true
            $line
        } else {
            $existingLine
        }
    }

    if (-not $found) {
        $updated += $line
    }

    Set-Content -LiteralPath $Path -Value $updated -Encoding utf8
}

function Get-EnvFileValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$DefaultValue = ""
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $DefaultValue
    }

    foreach ($existingLine in Get-Content -LiteralPath $Path) {
        $trimmed = $existingLine.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) {
            continue
        }

        if ($trimmed.Substring(0, $separator).Trim() -eq $Name) {
            $value = $trimmed.Substring($separator + 1).Trim()
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                return $value
            }
        }
    }

    return $DefaultValue
}

function Assert-ProductionCredentialEncryptionConfig {
    param([Parameter(Mandatory = $true)][string]$Path)

    $required = Get-EnvFileValue -Path $Path -Name 'OTZIV_CREDENTIAL_ENCRYPTION_REQUIRED'
    if ($required.ToLowerInvariant() -ne 'true') {
        throw 'OTZIV_CREDENTIAL_ENCRYPTION_REQUIRED=true is mandatory for production deployment.'
    }

    $activeKeyId = Get-EnvFileValue -Path $Path -Name 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID'
    if ($activeKeyId -notmatch '^[A-Za-z0-9._-]{1,64}$') {
        throw 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID must match [A-Za-z0-9._-]{1,64}.'
    }

    $encodedKey = Get-EnvFileValue -Path $Path -Name 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64'
    if ([string]::IsNullOrWhiteSpace($encodedKey)) {
        throw 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 is required for production deployment.'
    }
    if ($encodedKey -notmatch '^[A-Za-z0-9+/]{43}=?$') {
        throw 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 must be valid Base64.'
    }

    $decodedKey = $null
    try {
        $normalizedKey = if ($encodedKey.Length -eq 43) { $encodedKey + '=' } else { $encodedKey }
        $decodedKey = [Convert]::FromBase64String($normalizedKey)
        if ($decodedKey.Length -ne 32) {
            throw 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 must decode to exactly 32 bytes.'
        }
    } catch [System.FormatException] {
        throw 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 must be valid Base64.'
    } finally {
        if ($null -ne $decodedKey) {
            [Array]::Clear($decodedKey, 0, $decodedKey.Length)
        }
        $encodedKey = $null
        $normalizedKey = $null
    }
}

function Get-DeployDatabaseBackupKey {
    param([Parameter(Mandatory = $true)][string]$Path)

    $encodedKey = Get-EnvFileValue -Path $Path -Name 'DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64'
    if ([string]::IsNullOrWhiteSpace($encodedKey)) {
        throw 'DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 is required for the mandatory pre-migration database backup.'
    }

    $decodedKey = $null
    try {
        $decodedKey = [Convert]::FromBase64String($encodedKey)
        if ($decodedKey.Length -ne 32) {
            throw 'DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 must decode to exactly 32 bytes.'
        }
    } catch [System.FormatException] {
        throw 'DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 must be valid Base64.'
    } finally {
        if ($null -ne $decodedKey) {
            [Array]::Clear($decodedKey, 0, $decodedKey.Length)
        }
    }

    return $encodedKey
}

function Test-Base64SecretsEqual {
    param(
        [Parameter(Mandatory = $true)][string]$Left,
        [Parameter(Mandatory = $true)][string]$Right
    )

    $leftBytes = $null
    $rightBytes = $null
    try {
        $normalizedLeft = $Left.PadRight($Left.Length + ((4 - ($Left.Length % 4)) % 4), '=')
        $normalizedRight = $Right.PadRight($Right.Length + ((4 - ($Right.Length % 4)) % 4), '=')
        $leftBytes = [Convert]::FromBase64String($normalizedLeft)
        $rightBytes = [Convert]::FromBase64String($normalizedRight)
        if ($leftBytes.Length -ne 32 -or $rightBytes.Length -ne 32) {
            throw 'Compared production encryption keys must each decode to exactly 32 bytes.'
        }
        $difference = 0
        for ($index = 0; $index -lt $leftBytes.Length; $index++) {
            $difference = $difference -bor ($leftBytes[$index] -bxor $rightBytes[$index])
        }
        return $difference -eq 0
    } catch [System.FormatException] {
        throw 'Compared production encryption keys must be valid Base64.'
    } finally {
        foreach ($buffer in @($leftBytes, $rightBytes)) {
            if ($null -ne $buffer) { [Array]::Clear($buffer, 0, $buffer.Length) }
        }
        $normalizedLeft = $null
        $normalizedRight = $null
    }
}

function Assert-ProductionAppMemoryConfig {
    param([Parameter(Mandatory = $true)][string]$Path)

    $value = Get-EnvFileValue -Path $Path -Name 'APP_MEMORY_LIMIT'
    if ($value -notmatch '^(?<amount>[1-9][0-9]*)(?<unit>[mMgG])$') {
        throw 'APP_MEMORY_LIMIT must be explicit in production and use Docker m/g syntax, for example 2304m.'
    }
    $amount = [long]$Matches.amount
    $memoryMiB = if ($Matches.unit.ToLowerInvariant() -eq 'g') { $amount * 1024L } else { $amount }
    if ($memoryMiB -lt 2304L) {
        throw "APP_MEMORY_LIMIT=$value is below the audited 5.50 production minimum of 2304 MiB."
    }
}

function Assert-DownloadedPreDeployBackup {
    param(
        [Parameter(Mandatory = $true)][string]$ArtifactPath,
        [Parameter(Mandatory = $true)][string]$ExpectedSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedHmacSha256,
        [AllowNull()][string]$EncryptionKeyBase64
    )

    if ($ExpectedSha256 -notmatch '^[0-9A-F]{64}$' -or $ExpectedHmacSha256 -notmatch '^[0-9A-F]{64}$') {
        throw 'Remote pre-deploy backup returned invalid integrity metadata.'
    }
    $actualSha256 = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualSha256 -cne $ExpectedSha256) {
        throw "Downloaded pre-deploy backup SHA-256 mismatch: $ArtifactPath"
    }

    if ([string]::IsNullOrWhiteSpace($EncryptionKeyBase64)) {
        Write-Warning 'The remote encrypted backup passed HMAC/decrypt/gzip verification, but local HMAC verification was skipped because the local env file was not available with -SkipEnvUpload.'
        return
    }

    $masterKey = $null
    $labelBytes = $null
    $macKey = $null
    $expectedHmac = $null
    $actualHmac = $null
    $deriver = $null
    $verifier = $null
    $stream = $null
    try {
        $masterKey = [Convert]::FromBase64String($EncryptionKeyBase64)
        if ($masterKey.Length -ne 32) {
            throw 'DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 must decode to exactly 32 bytes.'
        }
        $labelBytes = [Text.Encoding]::UTF8.GetBytes('otziv-predeploy-backup-authentication-v1')
        $deriver = [Security.Cryptography.HMACSHA256]::new($masterKey)
        $macKey = $deriver.ComputeHash($labelBytes)
        $verifier = [Security.Cryptography.HMACSHA256]::new($macKey)
        $stream = [IO.File]::OpenRead($ArtifactPath)
        $actualHmac = $verifier.ComputeHash($stream)
        $expectedHmac = [byte[]]::new(32)
        for ($index = 0; $index -lt $expectedHmac.Length; $index++) {
            $expectedHmac[$index] = [Convert]::ToByte($ExpectedHmacSha256.Substring($index * 2, 2), 16)
        }
        $difference = 0
        for ($index = 0; $index -lt $actualHmac.Length; $index++) {
            $difference = $difference -bor ($actualHmac[$index] -bxor $expectedHmac[$index])
        }
        if ($difference -ne 0) {
            throw "Downloaded pre-deploy backup HMAC verification failed: $ArtifactPath"
        }
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
        if ($null -ne $verifier) { $verifier.Dispose() }
        if ($null -ne $deriver) { $deriver.Dispose() }
        foreach ($buffer in @($masterKey, $labelBytes, $macKey, $expectedHmac, $actualHmac)) {
            if ($null -ne $buffer) { [Array]::Clear($buffer, 0, $buffer.Length) }
        }
    }
}

function Get-MobileReleaseArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [string]$RequestedPath = ""
    )

    $candidateFiles = @()
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        $resolvedRequestedPath = if ([System.IO.Path]::IsPathRooted($RequestedPath)) {
            $RequestedPath
        } else {
            Join-Path $RepoRoot $RequestedPath
        }
        if (-not (Test-Path -LiteralPath $resolvedRequestedPath -PathType Leaf)) {
            throw "Mobile APK not found: $resolvedRequestedPath"
        }
        $candidateFiles = @(Get-Item -LiteralPath $resolvedRequestedPath)
    } else {
        $buildsDirectory = Join-Path $RepoRoot "mobile\builds"
        if (-not (Test-Path -LiteralPath $buildsDirectory -PathType Container)) {
            return $null
        }
        $candidateFiles = @(Get-ChildItem -LiteralPath $buildsDirectory -File -Filter "otziv-prod-release-v*-code*.apk")
    }

    $parsed = foreach ($file in $candidateFiles) {
        if ($file.Name -notmatch '^otziv-prod-release-v(?<versionName>[0-9A-Za-z._-]+)-code(?<versionCode>[0-9]+)\.apk$') {
            if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
                throw "Mobile APK name must match otziv-prod-release-v<version>-code<code>.apk: $($file.Name)"
            }
            continue
        }
        [pscustomobject]@{
            File = $file
            VersionName = $Matches.versionName
            VersionCode = [int]$Matches.versionCode
        }
    }

    $selected = $parsed |
        Sort-Object VersionCode, @{ Expression = { $_.File.LastWriteTimeUtc }; Descending = $true } -Descending |
        Select-Object -First 1
    if ($null -eq $selected) {
        return $null
    }

    $verifierPath = Join-Path $RepoRoot "mobile\scripts\verify-android-release.ps1"
    if (-not (Test-Path -LiteralPath $verifierPath -PathType Leaf)) {
        throw "Android release verifier not found: $verifierPath"
    }
    try {
        $verified = & $verifierPath `
            -ApkPath $selected.File.FullName `
            -ExpectedVersionCode $selected.VersionCode `
            -ExpectedVersionName $selected.VersionName `
            -PassThru `
            -Quiet
    } catch {
        throw "Mobile APK verification failed for $($selected.File.Name): $($_.Exception.Message)"
    }
    if ($null -eq $verified) {
        throw "Mobile APK verifier returned no release metadata for $($selected.File.Name)."
    }

    return [pscustomobject]@{
        File = $selected.File
        VersionName = $verified.VersionName
        VersionCode = [int]$verified.VersionCode
        PackageName = $verified.PackageName
        SignerSha256 = $verified.SignerSha256
        ArtifactSha256 = $verified.ArtifactSha256
    }
}

if ($Help) {
    Show-Help
    exit 0
}

if ([string]::IsNullOrWhiteSpace($VpsHost)) {
    throw "Pass -VpsHost with your VPS IP address or hostname."
}

if ([string]::IsNullOrWhiteSpace($DockerLoginUsername)) {
    $DockerLoginUsername = $DockerHubNamespace
}

if ($RemoteEnvFile -in @('.', '..') -or $RemoteEnvFile -notmatch '^[A-Za-z0-9._-]+$') {
    throw "RemoteEnvFile must be a file name in the VPS deploy directory, for example .env or .env.prod."
}
if ($VpsHost -notmatch '^[A-Za-z0-9.-]+$' -or $VpsUser -notmatch '^[A-Za-z0-9._-]+$') {
    throw 'VpsHost/VpsUser contain unsupported characters.'
}
if ($VpsPath -eq '/' -or $VpsPath -notmatch '^/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+/?$' -or $VpsPath -match '(^|/)\.\.(/|$)') {
    throw 'VpsPath must be a specific absolute directory (never /) without traversal segments.'
}
$VpsPath = $VpsPath.TrimEnd('/')

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path

if ($Tag -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$') {
    throw 'Tag must be a valid, bounded Docker tag (letters, digits, dot, underscore and dash only).'
}

if (-not $AllowDirtyWorktree) {
    $deployInputPaths = @(
        'backend', 'frontend', 'whatsapp', 'infrastructure',
        'docker-compose.yaml', 'docker-compose.build.yaml',
        'Dockerfile.whatsapp', '.dockerignore'
    )
    $dirtyDeployInputs = @(& git -C $repoRoot status --porcelain --untracked-files=all -- @deployInputPaths)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to verify that deployment inputs are clean. Use -AllowDirtyWorktree only for an audited emergency deployment.'
    }
    if ($dirtyDeployInputs.Count -gt 0) {
        throw "Deployment inputs contain uncommitted changes. Commit/review them first, or use -AllowDirtyWorktree for an audited emergency deployment:`n$($dirtyDeployInputs -join [Environment]::NewLine)"
    }
} else {
    Write-Warning 'Deploying from a dirty worktree by explicit override. The resulting images may not be reproducible from Git.'
}

$envResolverPath = Join-Path $repoRoot "infrastructure\scripts\Resolve-OtzivEnvFile.ps1"
if (-not (Test-Path -LiteralPath $envResolverPath)) {
    throw "Env resolver script not found: $envResolverPath"
}
. $envResolverPath
$buildCompose = Join-Path $repoRoot "docker-compose.build.yaml"
$appImage = "${DockerHubNamespace}/${AppRepository}:${Tag}"
$webImage = "${DockerHubNamespace}/${WebRepository}:${Tag}"
$externalReviewWorkerImage = "${DockerHubNamespace}/${ExternalReviewWorkerRepository}:${Tag}"
$deployBundlePaths = @(
    "docker-compose.yaml",
    ".dockerignore",
    "Dockerfile.whatsapp",
    "whatsapp\package.json",
    "whatsapp\package-lock.json",
    "whatsapp\index.js",
    "whatsapp\chromium-launch.js",
    "whatsapp\chromium-smoke.js",
    "whatsapp\internal-auth.js",
    "whatsapp\message-webhook.js",
    "whatsapp\group-invite.js",
    "whatsapp\groups-cache.js",
    "infrastructure\nginx",
    "infrastructure\keycloak",
    "infrastructure\prometheus",
    "infrastructure\loki",
    "infrastructure\tempo",
    "infrastructure\alloy",
    "infrastructure\grafana",
    "infrastructure\systemd\otziv-prod-up.timer",
    "infrastructure\systemd\otziv-prod-up.service.in",
    "infrastructure\scripts\prod\apply-keycloak-prod-settings.sh",
    "infrastructure\scripts\prod\validate-flyway-migrations.sh",
    "infrastructure\scripts\prod\create-pre-deploy-db-backup.sh",
    "infrastructure\scripts\prod\otziv-prod-up.sh",
    "infrastructure\scripts\prod\register-max-webhook.sh",
    "infrastructure\scripts\prod\init-letsencrypt.sh",
    "infrastructure\scripts\prod\renew-letsencrypt.sh",
    "infrastructure\scripts\prod\register-max-webhook.ps1"
)
$remote = "${VpsUser}@${VpsHost}"
$remoteDeployLockToken = [System.Guid]::NewGuid().ToString('N')
$remoteUploadDirectory = "$VpsPath/.deploy-upload-$remoteDeployLockToken"
$remoteBundle = "$remoteUploadDirectory/bundle.tar.gz"
$remoteRolloutScript = "$remoteUploadDirectory/rollout.sh"
$stageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("otziv-deploy-" + [System.Guid]::NewGuid().ToString("N"))
$bundlePath = Join-Path ([System.IO.Path]::GetTempPath()) ("otziv-deploy-" + [System.Guid]::NewGuid().ToString("N") + ".tar.gz")
$remoteBundleUploaded = $false
$remoteDeployLockAcquired = $false
$remotePreBackupInvocationStarted = $false
$remoteRolloutStarted = $false
$mobileRelease = if ($SkipMobileApkUpload) { $null } else { Get-MobileReleaseArtifact -RepoRoot $repoRoot -RequestedPath $MobileApkPath }
$sshArgs = @()
$scpArgs = @()
if (-not [string]::IsNullOrWhiteSpace($SshKey)) {
    $sshArgs += @("-i", $SshKey)
    $scpArgs += @("-i", $SshKey)
}
$sshKeepAliveArgs = @(
    "-o", "BatchMode=yes",
    "-o", "ConnectTimeout=15",
    "-o", "ConnectionAttempts=2",
    "-o", "StrictHostKeyChecking=accept-new",
    "-o", "ServerAliveInterval=20",
    "-o", "ServerAliveCountMax=12",
    "-o", "TCPKeepAlive=yes"
)
$sshArgs += @("-p", "$VpsPort") + $sshKeepAliveArgs
$scpArgs += @("-P", "$VpsPort") + $sshKeepAliveArgs

if (-not (Test-Path -LiteralPath $buildCompose)) {
    throw "Missing build compose file: $buildCompose"
}
foreach ($relativePath in $deployBundlePaths) {
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $relativePath))) {
        throw "Required deploy path is missing: $relativePath"
    }
}

$envFilePath = Resolve-OtzivEnvFile -EnvFile $EnvFile -RepoRoot $repoRoot -AllowMissing:$SkipEnvUpload
$localDeployBackupKeyBase64 = $null

if (-not $SkipEnvUpload -and -not (Test-Path -LiteralPath $envFilePath)) {
    throw "Env file not found: $envFilePath. Create it or pass -SkipEnvUpload."
}

if (-not $SkipEnvUpload) {
    # Validate the externally managed production key before building, pushing, uploading,
    # or changing any remote service. The key value is never written to the console.
    Assert-ProductionCredentialEncryptionConfig -Path $envFilePath
    Assert-ProductionAppMemoryConfig -Path $envFilePath
    $localDeployBackupKeyBase64 = Get-DeployDatabaseBackupKey -Path $envFilePath
    Write-Host "Using env file: $envFilePath"
} elseif (Test-Path -LiteralPath $envFilePath -PathType Leaf) {
    # With -SkipEnvUpload the VPS env remains authoritative. If a matching local
    # env is available, use its dedicated key for independent HMAC verification
    # of the downloaded encrypted backup without uploading or printing the key.
    $localDeployBackupKeyBase64 = Get-DeployDatabaseBackupKey -Path $envFilePath
}
if ($null -ne $localDeployBackupKeyBase64) {
    $localCredentialKeyBase64 = Get-EnvFileValue -Path $envFilePath -Name 'OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64'
    if (-not [string]::IsNullOrWhiteSpace($localCredentialKeyBase64) -and
        (Test-Base64SecretsEqual -Left $localCredentialKeyBase64 -Right $localDeployBackupKeyBase64)) {
        throw 'Deploy DB-backup encryption and credential-field encryption must use different keys.'
    }
    $localScheduledBackupKeyBase64 = Get-EnvFileValue -Path $envFilePath -Name 'BACKUP_ENCRYPTION_KEY_BASE64'
    if (-not [string]::IsNullOrWhiteSpace($localScheduledBackupKeyBase64) -and
        -not [string]::IsNullOrWhiteSpace($localCredentialKeyBase64) -and
        (Test-Base64SecretsEqual -Left $localScheduledBackupKeyBase64 -Right $localCredentialKeyBase64)) {
        throw 'Scheduled DB-backup encryption and credential-field encryption must use different keys.'
    }
    if (-not [string]::IsNullOrWhiteSpace($localScheduledBackupKeyBase64) -and
        (Test-Base64SecretsEqual -Left $localScheduledBackupKeyBase64 -Right $localDeployBackupKeyBase64)) {
        throw 'Pre-deploy and scheduled DB backups must use different encryption keys.'
    }
    $localCredentialKeyBase64 = $null
    $localScheduledBackupKeyBase64 = $null
}

Write-Host "Building and pushing:"
Write-Host "  APP_IMAGE=$appImage"
Write-Host "  WEB_IMAGE=$webImage"
if ($EnableExternalReviewWorker) {
    Write-Host "  EXTERNAL_REVIEW_WORKER_IMAGE=$externalReviewWorkerImage (enabled)"
} else {
    Write-Host "  EXTERNAL_REVIEW_WORKER=disabled (use -EnableExternalReviewWorker to opt in)"
}
if ($null -ne $mobileRelease) {
    Write-Host "  MOBILE_APK=$($mobileRelease.File.FullName) (version $($mobileRelease.VersionName), code $($mobileRelease.VersionCode))"
} elseif (-not $SkipMobileApkUpload) {
    Write-Warning "No signed release APK found in mobile/builds. Mobile publication will be skipped."
}

if ($DockerLogin) {
    Invoke-External -FilePath "docker" -Arguments @("login", "-u", $DockerLoginUsername)
}

$env:APP_IMAGE = $appImage
$env:WEB_IMAGE = $webImage
$env:EXTERNAL_REVIEW_WORKER_IMAGE = $externalReviewWorkerImage

if (-not $SkipBuildPush) {
    $buildArgs = @("compose", "-f", $buildCompose, "build")
    if ($NoBuildCache) {
        $buildArgs += "--no-cache"
    }
    $buildArgs += @("app", "nginx")
    if ($EnableExternalReviewWorker) {
        $buildArgs += "external-review-worker"
    }
    Invoke-External -FilePath "docker" -Arguments $buildArgs
    Write-Host "Pushing application image..."
    Invoke-External -FilePath "docker" -Arguments @("push", $appImage)
    Write-Host "Pushing web image..."
    Invoke-External -FilePath "docker" -Arguments @("push", $webImage)
    if ($EnableExternalReviewWorker) {
        Write-Host "Pushing external review worker image..."
        Invoke-External -FilePath "docker" -Arguments @("push", $externalReviewWorkerImage)
    }
    Write-Host "Docker images pushed successfully."
} else {
    Write-Host "Skipping docker build/push; deploying already published images."
}

if ($null -ne $mobileRelease) {
    Write-Host "Checking mobile APK state on VPS..."
    $remotePathForCheck = ConvertTo-BashSingleQuoted $VpsPath
    $mobileCodeForCheck = $mobileRelease.VersionCode
    $mobileShaForCheck = ConvertTo-BashSingleQuoted $mobileRelease.ArtifactSha256
    $remoteMobileCheck = @"
set -eu
remote_path=$remotePathForCheck
incoming_code=$mobileCodeForCheck
incoming_sha=$mobileShaForCheck
metadata="`$remote_path/data/mobile-releases/release.json"
if [ -f "`$metadata" ]; then
  code="`$(grep -o '"versionCode":[[:space:]]*[0-9]*' "`$metadata" | grep -o '[0-9]*' | head -n 1 || true)"
  file_name="`$(grep -o '"fileName":"[^"]*"' "`$metadata" | cut -d '"' -f 4 | head -n 1 || true)"
  metadata_sha="`$(grep -o '"sha256":"[0-9A-Fa-f]*"' "`$metadata" | cut -d '"' -f 4 | head -n 1 || true)"
  if [ -n "`$code" ] && [ "`$code" -ge "`$incoming_code" ]; then
    if ! printf '%s' "`$file_name" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$'; then
      echo "Published mobile metadata has an unsafe APK file name." >&2
      exit 1
    fi
    if ! printf '%s' "`$metadata_sha" | grep -Eq '^[0-9A-Fa-f]{64}$' \
        || [ ! -f "`$remote_path/data/mobile-releases/`$file_name" ]; then
      echo "Published mobile release metadata or APK is incomplete." >&2
      exit 1
    fi
    metadata_sha="`$(printf '%s' "`$metadata_sha" | tr '[:lower:]' '[:upper:]')"
    actual_sha="`$(sha256sum "`$remote_path/data/mobile-releases/`$file_name" | awk '{print toupper(`$1)}')"
    if [ "`$actual_sha" != "`$metadata_sha" ]; then
      echo "Published mobile APK does not match release.json SHA-256." >&2
      exit 1
    fi
    if [ "`$code" -eq "`$incoming_code" ] && [ "`$metadata_sha" != "`$incoming_sha" ]; then
      echo "Published mobile APK reuses the requested versionCode with a different SHA-256." >&2
      exit 1
    fi
    printf 'PRESENT'
  else
    printf 'MISSING'
  fi
else
  printf 'MISSING'
fi
"@
    $remoteMobileState = ($remoteMobileCheck | & ssh @sshArgs $remote "tr -d '\r' | bash -s").Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to check the mobile release on VPS."
    }
    if ($remoteMobileState -eq "PRESENT") {
        Write-Host "Mobile APK code $($mobileRelease.VersionCode) or newer is already present on VPS; excluding it from the deploy bundle."
        $mobileRelease = $null
    } elseif ($remoteMobileState -ne "MISSING") {
        throw "Unexpected mobile release check response from VPS: $remoteMobileState"
    }
}

New-Item -ItemType Directory -Path $stageRoot -Force | Out-Null
Protect-SensitiveLocalPath -Path $stageRoot
try {
    Write-Host "Preparing deployment bundle..."
    foreach ($deployBundlePath in $deployBundlePaths) {
        Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath $deployBundlePath
    }

    $uploadedMobileRelease = "0"
    if ($null -ne $mobileRelease) {
        $mobileStageDirectory = Join-Path $stageRoot ".deploy-mobile-update"
        New-Item -ItemType Directory -Path $mobileStageDirectory -Force | Out-Null
        $mobileFileName = "otziv-v$($mobileRelease.VersionName)-code$($mobileRelease.VersionCode).apk"
        $mobileStageApk = Join-Path $mobileStageDirectory $mobileFileName
        Copy-Item -LiteralPath $mobileRelease.File.FullName -Destination $mobileStageApk -Force
        $mobileSha256 = (Get-FileHash -LiteralPath $mobileStageApk -Algorithm SHA256).Hash.ToUpperInvariant()
        if ($mobileSha256 -cne $mobileRelease.ArtifactSha256) {
            throw "Mobile APK hash changed after verification and before bundle creation."
        }
        $mobileMetadata = [ordered]@{
            versionCode = $mobileRelease.VersionCode
            versionName = $mobileRelease.VersionName
            minSupportedVersionCode = 0
            required = $false
            notes = "Версия $($mobileRelease.VersionName) опубликована автоматически при deploy $Tag."
            fileName = $mobileFileName
            fileSize = (Get-Item -LiteralPath $mobileStageApk).Length
            sha256 = $mobileSha256
            publishedAt = [DateTimeOffset]::UtcNow.ToString("o")
        }
        $mobileMetadataJson = $mobileMetadata | ConvertTo-Json -Compress
        [System.IO.File]::WriteAllText(
            (Join-Path $mobileStageDirectory "release.json"),
            $mobileMetadataJson,
            [System.Text.UTF8Encoding]::new($false)
        )
        $uploadedMobileRelease = "1"
    }

    if (-not $SkipEnvUpload) {
        $stageEnv = Join-Path $stageRoot $RemoteEnvFile
        Copy-Item -LiteralPath $envFilePath -Destination $stageEnv -Force
        Set-EnvFileValue -Path $stageEnv -Name "APP_IMAGE" -Value $appImage
        Set-EnvFileValue -Path $stageEnv -Name "WEB_IMAGE" -Value $webImage
        Set-EnvFileValue -Path $stageEnv -Name "EXTERNAL_REVIEW_WORKER_IMAGE" -Value $externalReviewWorkerImage
        Set-EnvFileValue -Path $stageEnv -Name "EXTERNAL_REVIEW_CHECK_ENABLED" -Value $(if ($EnableExternalReviewWorker) { "true" } else { "false" })
        Set-EnvFileValue -Path $stageEnv -Name "WHATSAPP_IMAGE" -Value "otziv-whatsapp:$Tag"
        Set-EnvFileValue -Path $stageEnv -Name "OTZIV_APP_BASE_URL" -Value "https://o-ogo.ru"
        Set-EnvFileValue -Path $stageEnv -Name "OTZIV_AUTH_LEGACY_MIGRATION_ENABLED" -Value "false"
        Set-EnvFileValue -Path $stageEnv -Name "KEYCLOAK_PUBLIC_URL" -Value "https://o-ogo.ru/keycloak"
        Set-EnvFileValue -Path $stageEnv -Name "KEYCLOAK_ISSUER_URI" -Value "https://o-ogo.ru/keycloak/realms/otziv"
        Set-EnvFileValue -Path $stageEnv -Name "KEYCLOAK_JWK_SET_URI" -Value "http://keycloak:8080/keycloak/realms/otziv/protocol/openid-connect/certs"
        Set-EnvFileValue -Path $stageEnv -Name "KEYCLOAK_ADMIN_SERVER_URL" -Value "http://keycloak:8080/keycloak"
        Set-EnvFileValue -Path $stageEnv -Name "KC_PROXY_TRUSTED_ADDRESSES" -Value "172.16.0.0/12,10.0.0.0/8,192.168.0.0/16,127.0.0.0/8"
        Set-EnvFileValue -Path $stageEnv -Name "TELEGRAM_BOT_REGISTRATION_ENABLED" -Value "true"
        Set-EnvFileValue -Path $stageEnv -Name "TELEGRAM_BOT_SENDING_ENABLED" -Value "true"
        $outboundProxyHost = Get-EnvFileValue -Path $stageEnv -Name "OPENAI_PROXY_HOST" -DefaultValue $VpsHost
        $outboundProxyPort = Get-EnvFileValue -Path $stageEnv -Name "OPENAI_PROXY_PORT" -DefaultValue "8888"
        Set-EnvFileValue -Path $stageEnv -Name "TELEGRAM_PROXY_ENABLED" -Value "true"
        Set-EnvFileValue -Path $stageEnv -Name "TELEGRAM_PROXY_HOST" -Value $outboundProxyHost
        Set-EnvFileValue -Path $stageEnv -Name "TELEGRAM_PROXY_PORT" -Value $outboundProxyPort
        Set-EnvFileValue -Path $stageEnv -Name "MAX_PROXY_ENABLED" -Value "false"
        Set-EnvFileValue -Path $stageEnv -Name "MAX_PROXY_HOST" -Value ""
    }

    if (Test-Path -LiteralPath $bundlePath) {
        Remove-Item -LiteralPath $bundlePath -Force
    }
    Invoke-External -FilePath "tar" -Arguments @("-czf", $bundlePath, "-C", $stageRoot, ".")
    Protect-SensitiveLocalPath -Path $bundlePath

    $remotePathForUploadQuoted = ConvertTo-BashSingleQuoted $VpsPath
    $remoteUploadDirectoryQuoted = ConvertTo-BashSingleQuoted $remoteUploadDirectory
    $remoteBundleForUploadQuoted = ConvertTo-BashSingleQuoted $remoteBundle
    $mkdirScript = @"
set -eu
umask 077
mkdir -p $remotePathForUploadQuoted
mkdir $remoteUploadDirectoryQuoted
chmod 700 $remoteUploadDirectoryQuoted
: > $remoteBundleForUploadQuoted
chmod 600 $remoteBundleForUploadQuoted
"@
    $mkdirScript = $mkdirScript -replace "`r`n", "`n" -replace "`r", "`n"
    Write-Host "Uploading deployment bundle to VPS..."
    Invoke-External -FilePath "ssh" -Arguments ($sshArgs + @($remote, $mkdirScript))
    # The 0700 directory protects a partially uploaded file even if an scp/SFTP
    # implementation applies a permissive mode while replacing its contents.
    $remoteBundleUploaded = $true
    Copy-DeployBundle -ScpArgs $scpArgs -BundlePath $bundlePath -Remote $remote -RemoteBundle $remoteBundle

    $remotePathQuoted = ConvertTo-BashSingleQuoted $VpsPath
    $remoteBundleQuoted = ConvertTo-BashSingleQuoted $remoteBundle
    $remoteRolloutScriptQuoted = ConvertTo-BashSingleQuoted $remoteRolloutScript
    $appRepoQuoted = ConvertTo-BashSingleQuoted "${DockerHubNamespace}/${AppRepository}"
    $webRepoQuoted = ConvertTo-BashSingleQuoted "${DockerHubNamespace}/${WebRepository}"
    $appImageQuoted = ConvertTo-BashSingleQuoted $appImage
    $webImageQuoted = ConvertTo-BashSingleQuoted $webImage
    $externalReviewWorkerImageQuoted = ConvertTo-BashSingleQuoted $externalReviewWorkerImage
    $remoteEnvFileQuoted = ConvertTo-BashSingleQuoted $RemoteEnvFile
    $deployTagQuoted = ConvertTo-BashSingleQuoted $Tag
    $vpsHostQuoted = ConvertTo-BashSingleQuoted $VpsHost
    $remoteDeployLockTokenQuoted = ConvertTo-BashSingleQuoted $remoteDeployLockToken
    $uploadedEnv = if ($SkipEnvUpload) { "0" } else { "1" }
    $deployExternalReviewWorker = if ($EnableExternalReviewWorker) { "1" } else { "0" }

    # Create and independently download a verified encrypted DB backup before
    # the remote rollout can start Flyway. The deploy bundle is only read here;
    # production compose/env files and containers are not replaced by this step.
    $preBackupRemoteScript = @"
set -Eeuo pipefail
umask 077
remote_path=$remotePathQuoted
bundle_path=$remoteBundleQuoted
deploy_bundle_dir="`$(dirname "`$bundle_path")"
env_file=$remoteEnvFileQuoted
deploy_tag=$deployTagQuoted
uploaded_env=$uploadedEnv
deploy_lock_token=$remoteDeployLockTokenQuoted
deploy_lock_dir="`$remote_path/.deploy.lock.d"
retain_deploy_lock="0"
self_heal_timer="otziv-prod-up.timer"
self_heal_service="otziv-prod-up.service"
self_heal_state_file="`$deploy_lock_dir/self-heal-timer-was-active"
self_heal_enabled_state_file="`$deploy_lock_dir/self-heal-timer-was-enabled"
self_heal_was_active="0"
self_heal_was_enabled="0"
self_heal_paused="0"

assert_self_heal_stopped() {
  for unit in "`$self_heal_timer" "`$self_heal_service"; do
    unit_state="`$(sudo -n systemctl show "`$unit" --property=ActiveState --value 2>/dev/null)" || {
      echo "Unable to verify production self-heal unit state: `$unit" >&2
      return 1
    }
    case "`$unit_state" in
      inactive|failed)
        ;;
      *)
        echo "Production self-heal unit did not stop: `$unit (`$unit_state)." >&2
        return 1
        ;;
    esac
  done
}

assert_self_heal_timer_scheduled() {
  timer_active_state="`$(sudo -n systemctl show "`$self_heal_timer" --property=ActiveState --value 2>/dev/null)" || return 1
  timer_sub_state="`$(sudo -n systemctl show "`$self_heal_timer" --property=SubState --value 2>/dev/null)" || return 1
  timer_next_elapse="`$(sudo -n systemctl show "`$self_heal_timer" --property=NextElapseUSecMonotonic --value 2>/dev/null)" || return 1
  if [ "`$timer_active_state" != "active" ] || [ "`$timer_sub_state" != "waiting" ] \
      || [ -z "`$timer_next_elapse" ] || [ "`$timer_next_elapse" = "infinity" ] || [ "`$timer_next_elapse" = "0" ]; then
    echo "Production self-heal timer has no finite next run (`$timer_active_state/`$timer_sub_state, next=`$timer_next_elapse)." >&2
    return 1
  fi
}

pause_self_heal() {
  if ! command -v systemctl >/dev/null 2>&1; then
    echo "systemctl is required to pause production self-heal before backup." >&2
    return 1
  fi
  timer_state="`$(sudo -n systemctl show "`$self_heal_timer" --property=ActiveState --value 2>/dev/null)" || {
    echo "Unable to read production self-heal timer state." >&2
    return 1
  }
  case "`$timer_state" in
    active|activating|reloading|deactivating)
      self_heal_was_active="1"
      ;;
    inactive|failed)
      self_heal_was_active="0"
      ;;
    *)
      echo "Unexpected production self-heal timer state: `$timer_state" >&2
      return 1
      ;;
  esac
  timer_enabled_state="`$(sudo -n systemctl is-enabled "`$self_heal_timer" 2>/dev/null || true)"
  case "`$timer_enabled_state" in
    enabled)
      self_heal_was_enabled="1"
      ;;
    disabled)
      self_heal_was_enabled="0"
      ;;
    *)
      echo "Unexpected production self-heal timer enablement state: `$timer_enabled_state" >&2
      return 1
      ;;
  esac
  echo "Pausing production self-heal before the mandatory database backup..."
  self_heal_paused="1"
  if [ "`$self_heal_was_enabled" = "1" ]; then
    sudo -n systemctl disable "`$self_heal_timer"
  fi
  sudo -n systemctl stop "`$self_heal_timer" "`$self_heal_service"
  assert_self_heal_stopped
  printf '%s\n' "`$self_heal_was_active" > "`$self_heal_state_file"
  printf '%s\n' "`$self_heal_was_enabled" > "`$self_heal_enabled_state_file"
  chmod 600 "`$self_heal_state_file" "`$self_heal_enabled_state_file"
}

mkdir -p "`$remote_path"
cd "`$remote_path"
if ! mkdir "`$deploy_lock_dir" 2>/dev/null; then
  echo "Another production deployment is already running in `$remote_path." >&2
  echo "If no deploy is active, inspect and remove stale lock directory: `$deploy_lock_dir" >&2
  exit 75
fi
chmod 700 "`$deploy_lock_dir"
printf '%s\n' "`$deploy_lock_token" > "`$deploy_lock_dir/owner"
chmod 600 "`$deploy_lock_dir/owner"

preflight_dir=""
cleanup_preflight() {
  status="`$?"
  restore_failed="0"
  trap - EXIT INT TERM
  if [ -n "`$preflight_dir" ]; then
    rm -rf -- "`$preflight_dir" || true
  fi
  if [ "`$retain_deploy_lock" != "1" ] && [ "`$self_heal_paused" = "1" ]; then
    if [ "`$self_heal_was_enabled" = "1" ]; then
      echo "Pre-deploy backup did not complete; restoring production self-heal timer enablement..." >&2
      if ! sudo -n systemctl enable "`$self_heal_timer"; then
        echo "CRITICAL: failed to re-enable production self-heal timer after pre-deploy backup failure." >&2
        status="1"
        restore_failed="1"
      fi
    fi
    if [ "`$restore_failed" = "0" ] && [ "`$self_heal_was_active" = "1" ]; then
      echo "Pre-deploy backup did not complete; restoring production self-heal timer..." >&2
      if ! sudo -n systemctl start "`$self_heal_timer" \
          || ! assert_self_heal_timer_scheduled; then
        echo "CRITICAL: failed to restore production self-heal timer after pre-deploy backup failure." >&2
        status="1"
        restore_failed="1"
      fi
    fi
    if [ "`$restore_failed" = "1" ]; then
      sudo -n systemctl disable "`$self_heal_timer" || true
      sudo -n systemctl stop "`$self_heal_timer" "`$self_heal_service" || true
      retain_deploy_lock="1"
      echo "Self-heal restoration failed; timer remains disabled/stopped and the deploy lock is retained for manual recovery." >&2
    else
      rm -f -- "`$self_heal_state_file" "`$self_heal_enabled_state_file"
    fi
  fi
  if [ "`$retain_deploy_lock" != "1" ] \
      && [ -f "`$deploy_lock_dir/owner" ] \
      && [ "`$(cat "`$deploy_lock_dir/owner")" = "`$deploy_lock_token" ]; then
    rm -f -- "`$deploy_lock_dir/owner"
    rmdir -- "`$deploy_lock_dir" 2>/dev/null || true
  fi
  exit "`$status"
}
trap cleanup_preflight EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
preflight_dir="`$(mktemp -d "`$remote_path/.deploy-preflight.XXXXXXXX")"
pause_self_heal
tar --warning=no-timestamp -xzf "`$bundle_path" -C "`$preflight_dir" ./infrastructure/scripts/prod/create-pre-deploy-db-backup.sh
backup_env="`$remote_path/`$env_file"
if [ "`$uploaded_env" = "1" ]; then
  tar --warning=no-timestamp -xzf "`$bundle_path" -C "`$preflight_dir" "./`$env_file"
  backup_env="`$preflight_dir/`$env_file"
fi
chmod 600 "`$backup_env"
chmod 700 "`$preflight_dir/infrastructure/scripts/prod/create-pre-deploy-db-backup.sh"
bash "`$preflight_dir/infrastructure/scripts/prod/create-pre-deploy-db-backup.sh" create \
  my-mysql "`$remote_path/.deploy-backups/`$deploy_tag" "`$backup_env" "`$deploy_tag"
retain_deploy_lock="1"
"@
    $preBackupRemoteScript = $preBackupRemoteScript -replace "`r`n", "`n" -replace "`r", "`n"
    Write-Host "Creating and verifying mandatory pre-deploy database backup on VPS..."
    $remotePreBackupInvocationStarted = $true
    $preBackupOutput = @($preBackupRemoteScript | & ssh @sshArgs $remote "tr -d '\r' | bash -s")
    if ($LASTEXITCODE -ne 0) {
        throw 'Mandatory pre-deploy database backup failed; no application migration was started.'
    }
    $remoteDeployLockAcquired = $true
    foreach ($line in $preBackupOutput) {
        Write-Host $line
    }

    $preBackupValues = @{}
    foreach ($line in $preBackupOutput) {
        if ($line -match '^(OTZIV_PREDEPLOY_BACKUP_[A-Z0-9_]+)=(.*)$') {
            $preBackupValues[$Matches[1]] = $Matches[2]
        }
    }
    foreach ($requiredMarker in @(
        'OTZIV_PREDEPLOY_BACKUP_ARTIFACT',
        'OTZIV_PREDEPLOY_BACKUP_MANIFEST',
        'OTZIV_PREDEPLOY_BACKUP_SHA256',
        'OTZIV_PREDEPLOY_BACKUP_HMAC_SHA256',
        'OTZIV_PREDEPLOY_BACKUP_FLYWAY_FINGERPRINT'
    )) {
        if (-not $preBackupValues.ContainsKey($requiredMarker) -or
            [string]::IsNullOrWhiteSpace([string]$preBackupValues[$requiredMarker])) {
            throw "Mandatory pre-deploy database backup did not return $requiredMarker."
        }
    }

    $remoteBackupArtifact = [string]$preBackupValues['OTZIV_PREDEPLOY_BACKUP_ARTIFACT']
    $remoteBackupManifest = [string]$preBackupValues['OTZIV_PREDEPLOY_BACKUP_MANIFEST']
    $remoteBackupPrefix = $VpsPath.TrimEnd('/') + "/.deploy-backups/$Tag/"
    $remoteBackupLeaf = [IO.Path]::GetFileName($remoteBackupArtifact)
    if (-not $remoteBackupArtifact.StartsWith($remoteBackupPrefix, [StringComparison]::Ordinal) -or
        $remoteBackupLeaf -notmatch '^pre-deploy-[A-Za-z0-9_.-]+-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{16}\.sql\.gz\.enc$' -or
        $remoteBackupManifest -cne "$remoteBackupArtifact.manifest") {
        throw 'Mandatory pre-deploy database backup returned an unsafe artifact path.'
    }

    $customBackupDirectoryRequested = -not [string]::IsNullOrWhiteSpace($PreDeployBackupDirectory)
    $userProfilePath = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    if ([string]::IsNullOrWhiteSpace($userProfilePath)) {
        throw 'Unable to resolve a protected local directory for the pre-deploy database backup.'
    }
    if (-not $customBackupDirectoryRequested) {
        $PreDeployBackupDirectory = Join-Path $userProfilePath ".otziv\backups\pre-deploy\$Tag"
    } elseif (-not [IO.Path]::IsPathRooted($PreDeployBackupDirectory)) {
        $PreDeployBackupDirectory = Join-Path $repoRoot $PreDeployBackupDirectory
    }
    $PreDeployBackupDirectory = [IO.Path]::GetFullPath($PreDeployBackupDirectory).TrimEnd('\', '/')
    $repoRootForBackupGuard = [IO.Path]::GetFullPath($repoRoot).TrimEnd('\', '/')
    $repoBackupPrefix = $repoRootForBackupGuard + [IO.Path]::DirectorySeparatorChar
    if ($PreDeployBackupDirectory.Equals($repoRootForBackupGuard, [StringComparison]::OrdinalIgnoreCase) -or
        $PreDeployBackupDirectory.StartsWith($repoBackupPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'PreDeployBackupDirectory must stay outside the Git worktree so a production database backup cannot be committed.'
    }
    $backupPathRoot = [IO.Path]::GetFullPath([IO.Path]::GetPathRoot($PreDeployBackupDirectory)).TrimEnd('\', '/')
    $userProfileForBackupGuard = [IO.Path]::GetFullPath($userProfilePath).TrimEnd('\', '/')
    $trustedBackupRoot = [IO.Path]::GetFullPath(
        (Join-Path $userProfileForBackupGuard '.otziv\backups\pre-deploy')
    ).TrimEnd('\', '/')
    $trustedBackupPrefix = $trustedBackupRoot + [IO.Path]::DirectorySeparatorChar
    $profileContainsPrefix = $PreDeployBackupDirectory + [IO.Path]::DirectorySeparatorChar
    $protectedBackupParents = @(
        $backupPathRoot,
        $userProfileForBackupGuard,
        [IO.Path]::GetFullPath((Join-Path $userProfileForBackupGuard '.otziv')).TrimEnd('\', '/'),
        [IO.Path]::GetFullPath((Join-Path $userProfileForBackupGuard '.otziv\backups')).TrimEnd('\', '/'),
        $trustedBackupRoot
    )
    if ($protectedBackupParents -contains $PreDeployBackupDirectory -or
        $userProfileForBackupGuard.StartsWith($profileContainsPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'PreDeployBackupDirectory must be a dedicated release subdirectory, not a filesystem root, user profile, or shared backup parent.'
    }
    $backupDirectoryExists = Test-Path -LiteralPath $PreDeployBackupDirectory
    $insideTrustedBackupRoot = $PreDeployBackupDirectory.StartsWith(
        $trustedBackupPrefix,
        [StringComparison]::OrdinalIgnoreCase
    )
    if ($customBackupDirectoryRequested -and $backupDirectoryExists -and -not $insideTrustedBackupRoot) {
        throw 'An existing custom PreDeployBackupDirectory is not accepted because changing ACLs on a shared directory is unsafe. Pass a new dedicated leaf directory.'
    }
    Assert-NoReparsePointInExistingPath -Path $PreDeployBackupDirectory
    if (Test-Path -LiteralPath $PreDeployBackupDirectory) {
        $backupDirectoryInfo = Get-Item -LiteralPath $PreDeployBackupDirectory -Force
        if (-not $backupDirectoryInfo.PSIsContainer) {
            throw 'PreDeployBackupDirectory must be a local directory.'
        }
    }
    New-Item -ItemType Directory -Path $PreDeployBackupDirectory -Force | Out-Null
    Assert-NoReparsePointInExistingPath -Path $PreDeployBackupDirectory
    Protect-SensitiveLocalPath -Path $PreDeployBackupDirectory
    $localBackupArtifact = Join-Path $PreDeployBackupDirectory $remoteBackupLeaf
    $localBackupManifest = "$localBackupArtifact.manifest"
    if ((Test-Path -LiteralPath $localBackupArtifact) -or (Test-Path -LiteralPath $localBackupManifest)) {
        throw "Refusing to overwrite an existing local pre-deploy backup: $localBackupArtifact"
    }

    $localBackupVerified = $false
    try {
        Write-Host "Downloading encrypted pre-deploy database backup before migrations..."
        Invoke-ExternalWithRetry -FilePath 'scp' -Arguments ($scpArgs + @("${remote}:$remoteBackupArtifact", $localBackupArtifact)) -Attempts 3 -DelaySeconds 10
        Invoke-ExternalWithRetry -FilePath 'scp' -Arguments ($scpArgs + @("${remote}:$remoteBackupManifest", $localBackupManifest)) -Attempts 3 -DelaySeconds 10
        Protect-SensitiveLocalPath -Path $localBackupArtifact
        Protect-SensitiveLocalPath -Path $localBackupManifest
        Assert-DownloadedPreDeployBackup `
            -ArtifactPath $localBackupArtifact `
            -ExpectedSha256 ([string]$preBackupValues['OTZIV_PREDEPLOY_BACKUP_SHA256']) `
            -ExpectedHmacSha256 ([string]$preBackupValues['OTZIV_PREDEPLOY_BACKUP_HMAC_SHA256']) `
            -EncryptionKeyBase64 $localDeployBackupKeyBase64
        $localBackupVerified = $true
    } finally {
        if (-not $localBackupVerified) {
            Remove-Item -LiteralPath $localBackupArtifact -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $localBackupManifest -Force -ErrorAction SilentlyContinue
        }
    }
    Write-Host "Verified local encrypted DB backup: $localBackupArtifact"

    $preDeployFlywayFingerprint = [string]$preBackupValues['OTZIV_PREDEPLOY_BACKUP_FLYWAY_FINGERPRINT']
    if ($preDeployFlywayFingerprint -notmatch '^(?:ABSENT|[0-9A-F]{64})$') {
        throw 'Mandatory pre-deploy database backup returned an invalid Flyway fingerprint.'
    }
    $preDeployFlywayFingerprintQuoted = ConvertTo-BashSingleQuoted $preDeployFlywayFingerprint

    $remoteScript = @"
set -Eeuo pipefail
umask 077

remote_path=$remotePathQuoted
bundle_path=$remoteBundleQuoted
rollout_script_path=$remoteRolloutScriptQuoted
deploy_bundle_dir="`$(dirname "`$bundle_path")"
app_repo=$appRepoQuoted
web_repo=$webRepoQuoted
app_image=$appImageQuoted
web_image=$webImageQuoted
external_review_worker_image=$externalReviewWorkerImageQuoted
deploy_external_review_worker=$deployExternalReviewWorker
env_file=$remoteEnvFileQuoted
deploy_tag=$deployTagQuoted
vps_host=$vpsHostQuoted
uploaded_env=$uploadedEnv
uploaded_mobile_release=$uploadedMobileRelease
expected_flyway_fingerprint=$preDeployFlywayFingerprintQuoted
deploy_lock_token=$remoteDeployLockTokenQuoted
deploy_lock_dir="`$remote_path/.deploy.lock.d"
self_heal_timer="otziv-prod-up.timer"
self_heal_service="otziv-prod-up.service"
self_heal_state_file="`$deploy_lock_dir/self-heal-timer-was-active"
self_heal_enabled_state_file="`$deploy_lock_dir/self-heal-timer-was-enabled"
self_heal_was_active="0"
self_heal_was_enabled="0"
self_heal_guard_engaged="1"
self_heal_timer_resumed="0"
release_payload_complete="0"
mobile_storage_owner_needs_restore="0"
active_env_temp=""
active_systemd_unit_stage=""

assert_self_heal_stopped() {
  for unit in "`$self_heal_timer" "`$self_heal_service"; do
    unit_state="`$(sudo -n systemctl show "`$unit" --property=ActiveState --value 2>/dev/null)" || {
      echo "Unable to verify production self-heal unit state: `$unit" >&2
      return 1
    }
    case "`$unit_state" in
      inactive|failed)
        ;;
      *)
        echo "Production self-heal unit is not stopped: `$unit (`$unit_state)." >&2
        return 1
        ;;
    esac
  done
}

assert_self_heal_timer_scheduled() {
  timer_active_state="`$(sudo -n systemctl show "`$self_heal_timer" --property=ActiveState --value 2>/dev/null)" || return 1
  timer_sub_state="`$(sudo -n systemctl show "`$self_heal_timer" --property=SubState --value 2>/dev/null)" || return 1
  timer_next_elapse="`$(sudo -n systemctl show "`$self_heal_timer" --property=NextElapseUSecMonotonic --value 2>/dev/null)" || return 1
  if [ "`$timer_active_state" != "active" ] || [ "`$timer_sub_state" != "waiting" ] \
      || [ -z "`$timer_next_elapse" ] || [ "`$timer_next_elapse" = "infinity" ] || [ "`$timer_next_elapse" = "0" ]; then
    echo "Production self-heal timer has no finite next run (`$timer_active_state/`$timer_sub_state, next=`$timer_next_elapse)." >&2
    return 1
  fi
}

release_deploy_lock() {
  if [ -f "`$deploy_lock_dir/owner" ] \
      && [ "`$(cat "`$deploy_lock_dir/owner")" = "`$deploy_lock_token" ]; then
    if ! rm -f -- "`$self_heal_state_file" "`$self_heal_enabled_state_file" "`$deploy_lock_dir/owner"; then
      echo "Failed to remove protected deployment lock files." >&2
      return 1
    fi
    if ! rmdir -- "`$deploy_lock_dir"; then
      echo "Failed to remove protected deployment lock directory." >&2
      return 1
    fi
    return 0
  fi
  echo "Deployment lock ownership was lost; refusing to remove another deploy's lock." >&2
  return 1
}

resume_self_heal_timer() {
  if [ "`$self_heal_was_enabled" = "1" ]; then
    echo "Re-enabling production self-heal timer..."
    if ! sudo -n systemctl enable "`$self_heal_timer"; then
      return 1
    fi
  fi
  if [ "`$self_heal_was_active" = "1" ]; then
    echo "Resuming production self-heal timer..."
    if ! sudo -n systemctl start "`$self_heal_timer" \
        || ! assert_self_heal_timer_scheduled; then
      return 1
    fi
    self_heal_timer_resumed="1"
  fi
  return 0
}

deploy_cleanup() {
  status="`$?"
  trap - EXIT INT TERM
  if [ "`$status" -eq 0 ] && [ "`$release_payload_complete" != "1" ]; then
    echo "Deployment script ended before the verified release handoff completed; treating the rollout as failed." >&2
    status="1"
  fi
  rm -f -- "`$bundle_path" "`$rollout_script_path" || true
  rmdir -- "`$deploy_bundle_dir" 2>/dev/null || true
  if [ -n "`$active_env_temp" ]; then
    rm -f -- "`$active_env_temp" || true
  fi
  if [ -n "`$active_systemd_unit_stage" ]; then
    rm -rf -- "`$active_systemd_unit_stage" || true
  fi
  if [ "`$mobile_storage_owner_needs_restore" = "1" ]; then
    if ! restore_backend_mobile_storage_owner; then
      echo "CRITICAL: failed to restore backend ownership of mobile release storage." >&2
      status="1"
    fi
  fi
  rm -rf -- "`$remote_path/.deploy-mobile-update" || true
  if [ "`$status" -ne 0 ] && [ "`$release_payload_complete" != "1" ] \
      && [ "`$self_heal_guard_engaged" = "1" ]; then
    echo "Deployment failed while self-heal was guarded; enforcing a disabled timer and stopped units..." >&2
    if ! sudo -n systemctl disable "`$self_heal_timer"; then
      echo "CRITICAL: production self-heal timer could not be disabled after the failed rollout." >&2
    fi
    if ! sudo -n systemctl stop "`$self_heal_timer" "`$self_heal_service" \
        || ! assert_self_heal_stopped; then
      echo "CRITICAL: production self-heal could not be stopped after the failed rollout." >&2
    fi
    self_heal_timer_resumed="0"
  fi
  if [ "`$status" -eq 0 ]; then
    if ! resume_self_heal_timer; then
      echo "CRITICAL: failed to restore the protected production self-heal state after rollout." >&2
      status="1"
    elif ! release_deploy_lock; then
      status="1"
    else
      printf 'OTZIV_DEPLOY_COMPLETE=%s\n' "`$deploy_lock_token"
    fi
    if [ "`$status" -ne 0 ]; then
      sudo -n systemctl disable "`$self_heal_timer" || true
      sudo -n systemctl stop "`$self_heal_timer" "`$self_heal_service" || true
      self_heal_timer_resumed="0"
    fi
  fi
  if [ "`$status" -ne 0 ]; then
    if [ "`$release_payload_complete" != "1" ]; then
      echo "Deployment failed. Previous compose/env files remain under `$remote_path/.deploy-backups/`$deploy_tag." >&2
      echo "No database rollback was attempted. Review service state and the backup before a manual rollback." >&2
      if [ "`$self_heal_guard_engaged" = "1" ]; then
        echo "The production self-heal timer remains disabled and stopped, and the deploy lock remains at `$deploy_lock_dir." >&2
        echo "After recovery is verified, remove only this deployment's lock and restore the timer state manually." >&2
      fi
    else
      echo "Release payload and health checks completed, but the final self-heal/lock handoff was interrupted." >&2
      echo "Inspect `$deploy_lock_dir and `$self_heal_timer before retrying; the installed self-heal helper respects a retained lock." >&2
    fi
  fi
  exit "`$status"
}

trap deploy_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "`$remote_path"
cd "`$remote_path"
if [ ! -f "`$deploy_lock_dir/owner" ] \
    || [ "`$(cat "`$deploy_lock_dir/owner")" != "`$deploy_lock_token" ]; then
  echo "The deployment lock acquired before backup is missing or belongs to another rollout." >&2
  exit 75
fi
if [ ! -f "`$self_heal_state_file" ] || [ ! -f "`$self_heal_enabled_state_file" ]; then
  echo "The deployment lock is missing its protected self-heal state files." >&2
  exit 75
fi
self_heal_was_active="`$(cat "`$self_heal_state_file")"
self_heal_was_enabled="`$(cat "`$self_heal_enabled_state_file")"
case "`$self_heal_was_active" in
  0|1)
    ;;
  *)
    echo "The protected self-heal state is invalid." >&2
    exit 75
    ;;
esac
case "`$self_heal_was_enabled" in
  0|1)
    ;;
  *)
    echo "The protected self-heal enablement state is invalid." >&2
    exit 75
    ;;
esac
if ! command -v systemctl >/dev/null 2>&1; then
  echo "systemctl is required to guard the production rollout." >&2
  exit 1
fi
sudo -n systemctl disable "`$self_heal_timer"
sudo -n systemctl stop "`$self_heal_timer" "`$self_heal_service"
assert_self_heal_stopped
self_heal_guard_engaged="1"

# Docker Compose gives exported shell variables precedence over --env-file.
# Remove release-critical overrides inherited through SSH and pin the project
# name so every pull/recreate targets the audited production project.
unset APP_IMAGE WEB_IMAGE EXTERNAL_REVIEW_WORKER_IMAGE WHATSAPP_IMAGE
unset EXTERNAL_REVIEW_CHECK_ENABLED COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES
unset COMPOSE_ENV_FILES COMPOSE_DISABLE_ENV_FILE
compose_project_name="otziv-prod"

compose() {
  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose --project-name "`$compose_project_name" --project-directory "`$remote_path" -f "`$remote_path/docker-compose.yaml" --env-file "`$remote_path/`$env_file" "`$@"
  elif docker compose version >/dev/null 2>&1; then
    docker compose --project-name "`$compose_project_name" --project-directory "`$remote_path" -f "`$remote_path/docker-compose.yaml" --env-file "`$remote_path/`$env_file" "`$@"
  else
    echo "Docker Compose is not installed. Install docker-compose or the Docker Compose plugin." >&2
    exit 1
  fi
}

require_compose_service() {
  service_name="`$1"
  profile="`${2:-}"
  if [ -n "`$profile" ]; then
    services="`$(compose --profile "`$profile" config --services 2>&1)" || {
      printf '%s\n' "`$services" >&2
      echo "Failed to evaluate production compose services." >&2
      exit 1
    }
  else
    services="`$(compose config --services 2>&1)" || {
      printf '%s\n' "`$services" >&2
      echo "Failed to evaluate production compose services." >&2
      exit 1
    }
  fi

  if ! printf '%s\n' "`$services" | grep -Fxq "`$service_name"; then
    echo "Required production compose service is missing: `$service_name" >&2
    echo "Available production compose services:" >&2
    printf '%s\n' "`$services" >&2
    exit 1
  fi
}

set_env() {
  key="`$1"
  value="`$2"
  file="`$env_file"
  tmp_file="`$(mktemp "`$file.tmp.XXXXXXXX")"
  active_env_temp="`$tmp_file"

  if grep -q "^`$key=" "`$file"; then
    awk -v key="`$key" -v value="`$value" '
      BEGIN { prefix = key "=" }
      index(`$0, prefix) == 1 { `$0 = prefix value }
      { print }
    ' "`$file" > "`$tmp_file"
  else
    cp "`$file" "`$tmp_file"
    printf "\n%s=%s\n" "`$key" "`$value" >> "`$tmp_file"
  fi

  chmod 600 "`$tmp_file" || true
  mv "`$tmp_file" "`$file"
  active_env_temp=""
}

get_env() {
  key="`$1"
  default_value="`$2"
  file="`$env_file"

  if [ ! -f "`$file" ]; then
    printf '%s' "`$default_value"
    return 0
  fi

  value="`$(awk -F= -v key="`$key" '
    `$0 !~ /^[[:space:]]*#/ && index(`$0, key "=") == 1 {
      sub(/^[^=]*=/, "", `$0)
      print `$0
    }
  ' "`$file" | tail -n 1)"

  if [ -z "`$value" ]; then
    printf '%s' "`$default_value"
  else
    printf '%s' "`$value" | sed -e 's/\r$//' -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//"
  fi
}

require_env() {
  key="`$1"
  value="`$(get_env "`$key" "")"
  if [ -z "`$value" ]; then
    echo "Required production setting `$key is empty." >&2
    exit 1
  fi
}

assert_compose_service_image() {
  service_name="`$1"
  expected_image="`$2"
  profile="`${3:-}"
  if [ -n "`$profile" ]; then
    config_json="`$(compose --profile "`$profile" config --format json)"
  else
    config_json="`$(compose config --format json)"
  fi
  resolved_image="`$(printf '%s' "`$config_json" | python3 -c '
import json
import sys

config = json.load(sys.stdin)
service = config.get("services", {}).get(sys.argv[1], {})
print(service.get("image", ""))
' "`$service_name")"
  if [ "`$resolved_image" != "`$expected_image" ]; then
    echo "Resolved Compose image for `$service_name is '`$resolved_image'; expected '`$expected_image'." >&2
    exit 1
  fi
}

assert_running_service_image() {
  service_name="`$1"
  expected_image="`$2"
  profile="`${3:-}"
  if [ -n "`$profile" ]; then
    container_id="`$(compose --profile "`$profile" ps -q "`$service_name" | head -n 1)"
  else
    container_id="`$(compose ps -q "`$service_name" | head -n 1)"
  fi
  if [ -z "`$container_id" ]; then
    echo "Cannot verify image for missing service container: `$service_name" >&2
    exit 1
  fi
  expected_image_id="`$(docker image inspect "`$expected_image" --format '{{.Id}}')"
  actual_image_id="`$(docker inspect "`$container_id" --format '{{.Image}}')"
  if [ "`$actual_image_id" != "`$expected_image_id" ]; then
    echo "Running `$service_name image ID does not match `$expected_image." >&2
    exit 1
  fi
  echo "Verified `$service_name is running the expected image ID."
}

ensure_generated_link_secret() {
  key="`$1"
  existing="`$(get_env "`$key" "")"
  if [ -n "`$existing" ]; then
    return 0
  fi
  if ! command -v openssl >/dev/null 2>&1; then
    echo "`$key is missing and openssl is unavailable for secure first-deploy generation." >&2
    exit 1
  fi
  generated="`$(openssl rand -hex 32)"
  if [ "`$(printf '%s' "`$generated" | wc -c | tr -d ' ')" -ne 64 ]; then
    echo "Secure generation of `$key returned an unexpected length." >&2
    exit 1
  fi
  set_env "`$key" "`$generated"
  echo "Generated and persisted missing `$key (existing non-empty values are never overwritten)."
}

validate_security_prerequisites() {
  app_memory_limit="`$(get_env APP_MEMORY_LIMIT "")"
  if ! printf '%s' "`$app_memory_limit" | grep -Eq '^[1-9][0-9]*[mMgG]$'; then
    echo "APP_MEMORY_LIMIT must be explicit in production and use Docker m/g syntax, for example 2304m." >&2
    exit 1
  fi
  app_memory_amount="`${app_memory_limit%?}"
  app_memory_unit="`$(printf '%s' "`${app_memory_limit#"`$app_memory_amount"}" | tr '[:upper:]' '[:lower:]')"
  if [ "`$app_memory_unit" = "g" ]; then
    app_memory_mib="`$((app_memory_amount * 1024))"
  else
    app_memory_mib="`$app_memory_amount"
  fi
  if [ "`$app_memory_mib" -lt 2304 ]; then
    echo "APP_MEMORY_LIMIT is below the audited 5.50 production minimum of 2304 MiB." >&2
    exit 1
  fi
  unset app_memory_limit app_memory_amount app_memory_unit app_memory_mib

  if [ "`$(get_env OTZIV_CREDENTIAL_ENCRYPTION_REQUIRED false)" != "true" ]; then
    echo "OTZIV_CREDENTIAL_ENCRYPTION_REQUIRED=true is mandatory for production deployment." >&2
    exit 1
  fi
  require_env OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID
  require_env OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64
  credential_key_id="`$(get_env OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID "")"
  if ! printf '%s' "`$credential_key_id" | grep -Eq '^[A-Za-z0-9._-]{1,64}$'; then
    echo "OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_ID must match [A-Za-z0-9._-]{1,64}." >&2
    exit 1
  fi
  credential_key_value="`$(get_env OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 "")"
  if ! printf '%s' "`$credential_key_value" | grep -Eq '^[A-Za-z0-9+/]{43}=?$'; then
    echo "OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 must be valid Base64." >&2
    exit 1
  fi
  if [ "`$(printf '%s' "`$credential_key_value" | wc -c | tr -d ' ')" -eq 43 ]; then
    credential_key_value="`$credential_key_value="
  fi
  if ! credential_key_bytes="`$(printf '%s' "`$credential_key_value" | base64 --decode 2>/dev/null | wc -c | tr -d ' ')"; then
    echo "OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 must be valid Base64." >&2
    exit 1
  fi
  if [ "`$credential_key_bytes" -ne 32 ]; then
    echo "OTZIV_CREDENTIAL_ENCRYPTION_ACTIVE_KEY_BASE64 must decode to exactly 32 bytes." >&2
    exit 1
  fi
  credential_key_sha="`$(printf '%s' "`$credential_key_value" | base64 --decode | sha256sum | awk '{print `$1}')"
  require_env DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64
  deploy_backup_key_value="`$(get_env DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 "")"
  if ! printf '%s' "`$deploy_backup_key_value" | grep -Eq '^[A-Za-z0-9+/]{43}=?$'; then
    echo "DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 must be valid Base64." >&2
    exit 1
  fi
  if [ "`$(printf '%s' "`$deploy_backup_key_value" | wc -c | tr -d ' ')" -eq 43 ]; then
    deploy_backup_key_value="`$deploy_backup_key_value="
  fi
  if ! deploy_backup_key_bytes="`$(printf '%s' "`$deploy_backup_key_value" | base64 --decode 2>/dev/null | wc -c | tr -d ' ')"; then
    echo "DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 must be valid Base64." >&2
    exit 1
  fi
  if [ "`$deploy_backup_key_bytes" -ne 32 ]; then
    echo "DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64 must decode to exactly 32 bytes." >&2
    exit 1
  fi
  deploy_backup_key_sha="`$(printf '%s' "`$deploy_backup_key_value" | base64 --decode | sha256sum | awk '{print `$1}')"
  if [ "`$deploy_backup_key_sha" = "`$credential_key_sha" ]; then
    echo "Deploy DB-backup encryption and credential-field encryption must use different keys." >&2
    exit 1
  fi
  scheduled_backup_key_value="`$(get_env BACKUP_ENCRYPTION_KEY_BASE64 "")"
  if [ -n "`$scheduled_backup_key_value" ]; then
    if ! printf '%s' "`$scheduled_backup_key_value" | grep -Eq '^[A-Za-z0-9+/]{43}=?`$'; then
      echo "BACKUP_ENCRYPTION_KEY_BASE64 must be valid Base64." >&2
      exit 1
    fi
    if [ "`$(printf '%s' "`$scheduled_backup_key_value" | wc -c | tr -d ' ')" -eq 43 ]; then
      scheduled_backup_key_value="`$scheduled_backup_key_value="
    fi
    scheduled_backup_key_bytes="`$(printf '%s' "`$scheduled_backup_key_value" | base64 --decode 2>/dev/null | wc -c | tr -d ' ')"
    if [ "`$scheduled_backup_key_bytes" -ne 32 ]; then
      echo "BACKUP_ENCRYPTION_KEY_BASE64 must decode to exactly 32 bytes." >&2
      exit 1
    fi
    scheduled_backup_key_sha="`$(printf '%s' "`$scheduled_backup_key_value" | base64 --decode | sha256sum | awk '{print `$1}')"
    if [ "`$deploy_backup_key_sha" = "`$scheduled_backup_key_sha" ]; then
      echo "Pre-deploy and scheduled DB backups must use different encryption keys." >&2
      exit 1
    fi
    if [ "`$credential_key_sha" = "`$scheduled_backup_key_sha" ]; then
      echo "Scheduled DB-backup encryption and credential-field encryption must use different keys." >&2
      exit 1
    fi
  fi
  unset credential_key_id credential_key_value credential_key_bytes credential_key_sha deploy_backup_key_value deploy_backup_key_bytes deploy_backup_key_sha scheduled_backup_key_value scheduled_backup_key_bytes scheduled_backup_key_sha

  if [ "`$(get_env WHATSAPP_GATEWAY_AUTH_REQUIRED true)" = "true" ]; then
    require_env WHATSAPP_GATEWAY_SHARED_SECRET
  fi
  if [ "`$deploy_external_review_worker" = "1" ] \
      && [ "`$(get_env EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED true)" = "true" ]; then
    require_env EXTERNAL_REVIEW_WORKER_SHARED_SECRET
  fi

  telegram_link_value="`$(get_env TELEGRAM_BOT_LINK_SECRET "")"
  max_link_value="`$(get_env MAX_BOT_LINK_SECRET "")"
  telegram_link_bytes="`$(printf '%s' "`$telegram_link_value" | wc -c | tr -d ' ')"
  max_link_bytes="`$(printf '%s' "`$max_link_value" | wc -c | tr -d ' ')"
  if [ "`$telegram_link_bytes" -lt 32 ]; then
    echo "TELEGRAM_BOT_LINK_SECRET must contain at least 32 bytes." >&2
    exit 1
  fi
  if [ "`$max_link_bytes" -lt 32 ]; then
    echo "MAX_BOT_LINK_SECRET must contain at least 32 bytes." >&2
    exit 1
  fi
  if [ "`$telegram_link_value" = "`$max_link_value" ]; then
    echo "TELEGRAM_BOT_LINK_SECRET and MAX_BOT_LINK_SECRET must be different." >&2
    exit 1
  fi

  for backup_boolean_name in BACKUP_ENABLED BACKUP_SCHEDULE_ENABLED BACKUP_SCHEDULE_CATCH_UP_ENABLED BACKUP_RUN_ONCE_ENABLED BACKUP_S3_FORCE_PATH_STYLE BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION BACKUP_S3_INDEPENDENT_CONFIRMED BACKUP_DESTINATION_PRIVATE_CONFIRMED BACKUP_ENCRYPTION_AT_REST_CONFIRMED BACKUP_S3_OBJECT_LOCK_ENABLED BACKUP_MAIL_ENABLED BACKUP_EMAIL_DELIVERY_CONFIRMED; do
    backup_boolean_value="`$(get_env "`$backup_boolean_name" "")"
    if [ -n "`$backup_boolean_value" ] && [ "`$backup_boolean_value" != "true" ] && [ "`$backup_boolean_value" != "false" ]; then
      echo "`$backup_boolean_name must be exactly true or false." >&2
      exit 1
    fi
  done
  unset backup_boolean_name backup_boolean_value

  if [ "`$(get_env BACKUP_RUN_ONCE_ENABLED false)" != "false" ]; then
    echo "BACKUP_RUN_ONCE_ENABLED must remain false in persistent production env; one-shot mode is not a production procedure." >&2
    exit 1
  fi

  if [ "`$(get_env BACKUP_ENABLED false)" = "true" ]; then
    if [ "`$(get_env BACKUP_SCHEDULE_ENABLED true)" != "true" ]; then
      echo "BACKUP_ENABLED=true requires BACKUP_SCHEDULE_ENABLED=true for recurring production backups." >&2
      exit 1
    fi
    if [ "`$(get_env BACKUP_SCHEDULE_CATCH_UP_ENABLED true)" != "true" ]; then
      echo "BACKUP_ENABLED=true requires BACKUP_SCHEDULE_CATCH_UP_ENABLED=true." >&2
      exit 1
    fi
    backup_catch_up_window="`$(get_env BACKUP_SCHEDULE_CATCH_UP_WINDOW PT26H)"
    if ! printf '%s' "`$backup_catch_up_window" | grep -Eq '^PT(2[5-9]|3[0-6])H`$'; then
      echo "BACKUP_SCHEDULE_CATCH_UP_WINDOW must be a whole-hour duration from PT25H through PT36H." >&2
      exit 1
    fi
    backup_schedule_cron="`$(get_env BACKUP_SCHEDULE_CRON "0 0 7 * * *")"
    if ! printf '%s\n' "`$backup_schedule_cron" | awk '
      NF == 6 &&
      `$1 ~ /^[0-9]+`$/ && `$2 ~ /^[0-9]+`$/ && `$3 ~ /^[0-9]+`$/ &&
      `$1 >= 0 && `$1 <= 59 && `$2 >= 0 && `$2 <= 59 && `$3 >= 0 && `$3 <= 23 &&
      `$4 == "*" && `$5 == "*" && `$6 == "*" {
        valid = 1
      }
      END { exit(valid ? 0 : 1) }
    '; then
      echo "BACKUP_SCHEDULE_CRON must contain one numeric seconds/minutes/hours value and '*' for day-of-month/month/day-of-week." >&2
      exit 1
    fi
    backup_schedule_zone="`$(get_env BACKUP_SCHEDULE_ZONE "Asia/Irkutsk")"
    if ! printf '%s' "`$backup_schedule_zone" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+/-]{0,127}$' ||
       printf '%s' "`$backup_schedule_zone" | grep -Fq '..' ||
       [ ! -f "/usr/share/zoneinfo/`$backup_schedule_zone" ]; then
      echo "BACKUP_SCHEDULE_ZONE must identify an installed IANA time zone." >&2
      exit 1
    fi
    unset backup_catch_up_window backup_schedule_cron backup_schedule_zone
    require_env BACKUP_S3_ENDPOINT
    require_env BACKUP_S3_REGION
    require_env BACKUP_S3_BUCKET
    require_env BACKUP_S3_PROJECT
    require_env BACKUP_S3_ACCESS_KEY
    require_env BACKUP_S3_SECRET_KEY
    require_env BACKUP_ENCRYPTION_KEY_BASE64
    require_env BACKUP_RESTORE_DRILL_RTO
    case "`$(get_env BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION true)" in
      true|false) ;;
      *)
        echo "BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION must be true or false." >&2
        exit 1
        ;;
    esac
    backup_endpoint="`$(get_env BACKUP_S3_ENDPOINT "")"
    case "`$backup_endpoint" in
      https://*) ;;
      *)
        echo "BACKUP_S3_ENDPOINT must use HTTPS." >&2
        exit 1
        ;;
    esac
    case "`$backup_endpoint" in
      *'`$'*|*'#'*|*'"'*|*"'"*)
        echo "BACKUP_S3_ENDPOINT must be a literal env-safe HTTPS URI." >&2
        exit 1
        ;;
    esac
    unset backup_endpoint
    if [ "`$(get_env BACKUP_S3_INDEPENDENT_CONFIRMED false)" != "true" ]; then
      echo "BACKUP_ENABLED=true requires BACKUP_S3_INDEPENDENT_CONFIRMED=true." >&2
      exit 1
    fi
    if [ "`$(get_env BACKUP_S3_BUCKET "")" = "`$(get_env S3_BUCKET "")" ]; then
      echo "Backup storage must not use the primary S3 bucket." >&2
      exit 1
    fi
    if [ "`$(get_env BACKUP_S3_ACCESS_KEY "")" = "`$(get_env S3_ACCESS_KEY "")" ]; then
      echo "Backup storage must use credentials distinct from primary S3." >&2
      exit 1
    fi
    backup_key_value="`$(get_env BACKUP_ENCRYPTION_KEY_BASE64 "")"
    if ! backup_key_bytes="`$(printf '%s' "`$backup_key_value" | base64 --decode 2>/dev/null | wc -c | tr -d ' ')"; then
      echo "BACKUP_ENCRYPTION_KEY_BASE64 must be valid Base64." >&2
      exit 1
    fi
    if [ "`$backup_key_bytes" -ne 32 ]; then
      echo "BACKUP_ENCRYPTION_KEY_BASE64 must decode to exactly 32 bytes." >&2
      exit 1
    fi
    unset backup_key_value backup_key_bytes
    if [ "`$(get_env BACKUP_DESTINATION_PRIVATE_CONFIRMED false)" != "true" ]; then
      echo "BACKUP_ENABLED=true requires BACKUP_DESTINATION_PRIVATE_CONFIRMED=true." >&2
      exit 1
    fi
    if [ "`$(get_env BACKUP_ENCRYPTION_AT_REST_CONFIRMED false)" != "true" ]; then
      echo "BACKUP_ENABLED=true requires BACKUP_ENCRYPTION_AT_REST_CONFIRMED=true." >&2
      exit 1
    fi
    backup_retention_days="`$(get_env BACKUP_S3_RETENTION_DAYS 0)"
    case "`$backup_retention_days" in
      ''|*[!0-9]*)
        echo "BACKUP_S3_RETENTION_DAYS must be an integer from 0 to 36500." >&2
        exit 1
        ;;
    esac
    if [ "`$backup_retention_days" -gt 36500 ]; then
      echo "BACKUP_S3_RETENTION_DAYS must be an integer from 0 to 36500." >&2
      exit 1
    fi
    if [ "`$(get_env BACKUP_S3_OBJECT_LOCK_ENABLED false)" = "true" ]; then
      if [ "`$backup_retention_days" -lt 1 ]; then
        echo "Object Lock requires BACKUP_S3_RETENTION_DAYS to be positive." >&2
        exit 1
      fi
      case "`$(get_env BACKUP_S3_OBJECT_LOCK_MODE GOVERNANCE)" in
        GOVERNANCE|COMPLIANCE) ;;
        *)
          echo "BACKUP_S3_OBJECT_LOCK_MODE must be GOVERNANCE or COMPLIANCE." >&2
          exit 1
          ;;
      esac
    elif [ "`$backup_retention_days" -ne 0 ]; then
      echo "Non-zero BACKUP_S3_RETENTION_DAYS requires BACKUP_S3_OBJECT_LOCK_ENABLED=true." >&2
      exit 1
    fi
    unset backup_retention_days
    if [ "`$(get_env BACKUP_MAIL_ENABLED false)" = "true" ]; then
      require_env BACKUP_MAIL_TO
      require_env BACKUP_MAIL_FROM
      require_env MAIL_HOST
      require_env MAIL_USERNAME
      require_env MAIL_PASSWORD
      if [ "`$(get_env BACKUP_EMAIL_DELIVERY_CONFIRMED false)" != "true" ]; then
        echo "BACKUP_MAIL_ENABLED=true requires BACKUP_EMAIL_DELIVERY_CONFIRMED=true." >&2
        exit 1
      fi
      mail_port="`$(get_env MAIL_PORT 587)"
      case "`$mail_port" in
        ''|*[!0-9]*) echo "MAIL_PORT must be an integer from 1 to 65535." >&2; exit 1 ;;
      esac
      if [ "`$mail_port" -lt 1 ] || [ "`$mail_port" -gt 65535 ]; then
        echo "MAIL_PORT must be an integer from 1 to 65535." >&2
        exit 1
      fi
      mail_connection_timeout="`$(get_env MAIL_SMTP_CONNECTION_TIMEOUT_MS 10000)"
      mail_read_timeout="`$(get_env MAIL_SMTP_READ_TIMEOUT_MS 60000)"
      mail_write_timeout="`$(get_env MAIL_SMTP_WRITE_TIMEOUT_MS 60000)"
      for mail_timeout in "`$mail_connection_timeout" "`$mail_read_timeout" "`$mail_write_timeout"; do
        case "`$mail_timeout" in
          ''|*[!0-9]*) echo "SMTP timeouts must be positive integers no greater than 600000 ms." >&2; exit 1 ;;
        esac
        if [ "`$mail_timeout" -lt 1 ] || [ "`$mail_timeout" -gt 600000 ]; then
          echo "SMTP timeouts must be positive integers no greater than 600000 ms." >&2
          exit 1
        fi
      done
      for mail_security_name in MAIL_SMTP_AUTH MAIL_STARTTLS_ENABLE MAIL_STARTTLS_REQUIRED MAIL_SMTP_SSL_CHECK_SERVER_IDENTITY; do
        if [ "`$(get_env "`$mail_security_name" true)" != "true" ]; then
          echo "`$mail_security_name=true is required for encrypted authenticated backup email." >&2
          exit 1
        fi
      done
      mail_subject="`$(get_env BACKUP_MAIL_SUBJECT "Otziv database backup")"
      mail_body="`$(get_env BACKUP_MAIL_BODY "Daily database backup")"
      mail_to="`$(get_env BACKUP_MAIL_TO "")"
      mail_from="`$(get_env BACKUP_MAIL_FROM "")"
      for mail_address in "`$mail_to" "`$mail_from"; do
        if ! printf '%s' "`$mail_address" | grep -Eq '^[A-Za-z0-9._%+-]+@[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?\.[A-Za-z]{2,63}`$'; then
          echo "BACKUP_MAIL_TO and BACKUP_MAIL_FROM must each contain one canonical email address." >&2
          exit 1
        fi
        mail_local_part="`$`{mail_address%@*}"
        mail_domain_part="`$`{mail_address#*@}"
        case "`$mail_local_part" in .*|*.|*..*)
          echo "BACKUP_MAIL_TO and BACKUP_MAIL_FROM must each contain one canonical email address." >&2
          exit 1
          ;;
        esac
        case "`$mail_domain_part" in .*|*.|*..*)
          echo "BACKUP_MAIL_TO and BACKUP_MAIL_FROM must each contain one canonical email address." >&2
          exit 1
          ;;
        esac
      done
      case "`$mail_to`$mail_from`$mail_subject`$mail_body" in
        *'`$'*|*'#'*|*'"'*|*"'"*)
          echo "Backup mail addresses, subject and body must be literal single-line env-safe text." >&2
          exit 1
          ;;
      esac
      unset mail_port mail_connection_timeout mail_read_timeout mail_write_timeout mail_timeout mail_security_name mail_address mail_local_part mail_domain_part mail_to mail_from mail_subject mail_body
    fi
    require_env BACKUP_RESTORE_DRILL_DATE
    drill_date="`$(get_env BACKUP_RESTORE_DRILL_DATE "")"
    if ! drill_epoch="`$(date -d "`$drill_date" +%s 2>/dev/null)"; then
      echo "BACKUP_RESTORE_DRILL_DATE must be a valid date." >&2
      exit 1
    fi
    now_epoch="`$(date +%s)"
    if [ "`$drill_epoch" -gt "`$((now_epoch + 86400))" ]; then
      echo "BACKUP_RESTORE_DRILL_DATE cannot be in the future." >&2
      exit 1
    fi
    if [ "`$((now_epoch - drill_epoch))" -gt 7776000 ]; then
      echo "The last recorded backup restore drill is older than 90 days." >&2
      exit 1
    fi
  fi
}

current_flyway_fingerprint() {
  table_present="`$(docker exec my-mysql sh -lc \
    'exec mysql -u"`$MYSQL_USER" -p"`$MYSQL_PASSWORD" "`$MYSQL_DATABASE" -N -B -e "SHOW TABLES LIKE '\''flyway_schema_history'\''"' 2>/dev/null || true)"
  if [ -z "`$table_present" ]; then
    printf 'ABSENT'
    return 0
  fi

  docker exec my-mysql sh -lc \
    'exec mysql -u"`$MYSQL_USER" -p"`$MYSQL_PASSWORD" "`$MYSQL_DATABASE" -N -B -e "SELECT installed_rank, COALESCE(version, '\'''\''), success, COALESCE(checksum, 0) FROM flyway_schema_history ORDER BY installed_rank"' \
    | sha256sum | awk '{print toupper(`$1)}'
}

restore_backend_mobile_storage_owner() {
  mobile_target_dir="data/mobile-releases"
  if [ ! -d "`$mobile_target_dir" ]; then
    mobile_storage_owner_needs_restore="0"
    return 0
  fi
  if sudo -n true >/dev/null 2>&1; then
    if ! sudo -n chown -R 10001:10001 "`$mobile_target_dir"; then
      return 1
    fi
  else
    if ! docker run --rm --user 0 --cap-drop ALL --cap-add CHOWN --cap-add DAC_OVERRIDE --security-opt no-new-privileges \
        -v "`$PWD/data:/host-data" \
        --entrypoint chown "`$app_image" \
        -R 10001:10001 /host-data/mobile-releases; then
      return 1
    fi
  fi
  mobile_storage_owner_needs_restore="0"
}

publish_bundled_mobile_release() {
  bundle_dir=".deploy-mobile-update"
  metadata_file="`$bundle_dir/release.json"
  target_dir="data/mobile-releases"

  if [ "`$uploaded_mobile_release" != "1" ]; then
    rm -rf "`$bundle_dir"
    return 0
  fi
  if [ ! -f "`$metadata_file" ]; then
    echo "Mobile release metadata is missing from the deploy bundle." >&2
    exit 1
  fi

  incoming_apk="`$(find "`$bundle_dir" -maxdepth 1 -type f -name '*.apk' | head -n 1)"
  incoming_code="`$(grep -o '"versionCode":[[:space:]]*[0-9]*' "`$metadata_file" | grep -o '[0-9]*' | head -n 1)"
  expected_sha="`$(grep -o '"sha256":"[0-9A-Fa-f]*"' "`$metadata_file" | cut -d '"' -f 4 | head -n 1)"
  if [ -z "`$incoming_apk" ] || [ -z "`$incoming_code" ] || [ -z "`$expected_sha" ]; then
    echo "Mobile release bundle is incomplete." >&2
    exit 1
  fi

  incoming_file_name="`$(basename "`$incoming_apk")"
  actual_sha="`$(sha256sum "`$incoming_apk" | awk '{print toupper(`$1)}')"
  expected_sha="`$(printf '%s' "`$expected_sha" | tr '[:lower:]' '[:upper:]')"
  if [ "`$actual_sha" != "`$expected_sha" ]; then
    echo "Mobile APK SHA-256 verification failed before publication." >&2
    exit 1
  fi

  # Publication may need temporary ownership for the SSH deploy user. Every
  # success/skip/failure path restores UID/GID 10001 so MobileUpdateService can
  # continue publishing future releases through the authenticated admin API.
  mobile_storage_owner_needs_restore="1"
  if ! mkdir -p "`$target_dir" 2>/dev/null || [ ! -w "`$target_dir" ]; then
    deploy_uid="`$(id -u)"
    deploy_gid="`$(id -g)"
    if sudo -n true >/dev/null 2>&1; then
      sudo -n mkdir -p "`$target_dir"
      sudo -n chown "`$deploy_uid:`$deploy_gid" "`$target_dir"
    else
      docker run --rm --user 0 --cap-drop ALL --cap-add CHOWN --cap-add DAC_OVERRIDE --security-opt no-new-privileges \
        -v "`$PWD/data:/host-data" \
        --entrypoint sh "`$app_image" \
        -c "mkdir -p /host-data/mobile-releases && chown `$deploy_uid:`$deploy_gid /host-data/mobile-releases"
    fi
  fi
  if [ ! -w "`$target_dir" ]; then
    echo "Mobile release storage is not writable after permission repair: `$target_dir" >&2
    exit 1
  fi
  current_code="0"
  current_file_name=""
  current_metadata_sha=""
  if [ -f "`$target_dir/release.json" ]; then
    current_code="`$(grep -o '"versionCode":[[:space:]]*[0-9]*' "`$target_dir/release.json" | grep -o '[0-9]*' | head -n 1 || true)"
    current_file_name="`$(grep -o '"fileName":"[^"]*"' "`$target_dir/release.json" | cut -d '"' -f 4 | head -n 1 || true)"
    current_metadata_sha="`$(grep -o '"sha256":"[0-9A-Fa-f]*"' "`$target_dir/release.json" | cut -d '"' -f 4 | head -n 1 || true)"
    [ -n "`$current_code" ] || current_code="0"
  fi

  if [ "`$current_code" -ge "`$incoming_code" ]; then
    if ! printf '%s' "`$current_file_name" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$'; then
      echo "Published mobile metadata has an unsafe APK file name." >&2
      exit 1
    fi
    if ! printf '%s' "`$current_metadata_sha" | grep -Eq '^[0-9A-Fa-f]{64}$' \
        || [ ! -f "`$target_dir/`$current_file_name" ]; then
      echo "Published mobile release metadata or APK is incomplete." >&2
      exit 1
    fi
    current_metadata_sha="`$(printf '%s' "`$current_metadata_sha" | tr '[:lower:]' '[:upper:]')"
    current_actual_sha="`$(sha256sum "`$target_dir/`$current_file_name" | awk '{print toupper(`$1)}')"
    if [ "`$current_actual_sha" != "`$current_metadata_sha" ]; then
      echo "Published mobile APK does not match release.json SHA-256." >&2
      exit 1
    fi
    if [ "`$current_code" -eq "`$incoming_code" ] && [ "`$current_metadata_sha" != "`$expected_sha" ]; then
      echo "Refusing to reuse mobile versionCode `$incoming_code for a different APK SHA-256." >&2
      exit 1
    fi
    if [ "`$current_code" -gt "`$incoming_code" ]; then
      echo "Mobile APK code `$incoming_code is older than verified published code `$current_code; skipping."
    else
      echo "Mobile APK code `$incoming_code with the same SHA-256 is already published; skipping upload."
      find "`$target_dir" -maxdepth 1 -type f -name '*.apk' ! -name "`$current_file_name" -delete
    fi
    rm -rf "`$bundle_dir"
    restore_backend_mobile_storage_owner
    return 0
  fi

  apk_temp="`$target_dir/.`$incoming_file_name.tmp"
  metadata_temp="`$target_dir/.release.json.tmp"
  cp "`$incoming_apk" "`$apk_temp"
  copied_sha="`$(sha256sum "`$apk_temp" | awk '{print toupper(`$1)}')"
  if [ "`$copied_sha" != "`$expected_sha" ]; then
    rm -f "`$apk_temp"
    echo "Mobile APK SHA-256 verification failed after copying to storage." >&2
    exit 1
  fi
  mv -f "`$apk_temp" "`$target_dir/`$incoming_file_name"
  cp "`$metadata_file" "`$metadata_temp"
  mv -f "`$metadata_temp" "`$target_dir/release.json"
  chmod 644 "`$target_dir/`$incoming_file_name" "`$target_dir/release.json" || true

  find "`$target_dir" -maxdepth 1 -type f -name '*.apk' ! -name "`$incoming_file_name" -delete
  rm -rf "`$bundle_dir"
  restore_backend_mobile_storage_owner
  echo "Published mobile APK code `$incoming_code and removed older APK files."
}

wait_service_healthy() {
  service_name="`$1"
  timeout_seconds="`$2"
  profile="`${3:-}"
  started_at="`$(date +%s)"

  echo "Waiting for `$service_name to become healthy..."
  while true; do
    if [ -n "`$profile" ]; then
      container_id="`$(compose --profile "`$profile" ps -q "`$service_name" | head -n 1 || true)"
    else
      container_id="`$(compose ps -q "`$service_name" | head -n 1 || true)"
    fi
    if [ -n "`$container_id" ]; then
      state="`$(docker inspect -f '{{.State.Status}}' "`$container_id" 2>/dev/null || true)"
      health="`$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "`$container_id" 2>/dev/null || true)"

      if [ "`$health" = "healthy" ] || { [ "`$health" = "none" ] && [ "`$state" = "running" ]; }; then
        echo "`$service_name is ready (`$state/`$health)."
        return 0
      fi

      if [ "`$state" = "exited" ] || [ "`$state" = "dead" ]; then
        echo "`$service_name stopped while waiting (`$state/`$health)." >&2
        docker logs --tail 120 "`$container_id" >&2 || true
        return 1
      fi
    fi

    now="`$(date +%s)"
    if [ "`$((now - started_at))" -ge "`$timeout_seconds" ]; then
      echo "Timed out waiting for `$service_name to become healthy." >&2
      if [ -n "`$container_id" ]; then
        docker inspect -f '{{.Name}} state={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restarts={{.RestartCount}}' "`$container_id" >&2 || true
        docker logs --tail 120 "`$container_id" >&2 || true
      fi
      return 1
    fi

    sleep 5
  done
}

remove_service_containers() {
  service_name="`$1"
  project_name="`$(awk '/^name:[[:space:]]*/ { print `$2; exit }' docker-compose.yaml 2>/dev/null || true)"
  if [ -z "`$project_name" ]; then
    project_name="otziv-prod"
  fi
  name_filter="`$service_name"
  if [ "`$service_name" = "nginx" ]; then
    name_filter="frontend"
  elif [ "`$service_name" = "app" ]; then
    name_filter="`$project_name-app-1"
  fi

  container_ids="`$({
    docker ps -aq \
      --filter "label=com.docker.compose.project=`$project_name" \
      --filter "label=com.docker.compose.service=`$service_name" || true
    docker ps -aq --filter "name=^/`$name_filter`$" || true
    docker ps -aq --filter "name=_`$name_filter`$" || true
  } | sort -u)"

  if [ -n "`$container_ids" ]; then
    echo "Removing stale `$service_name containers before recreate..."
    docker rm -f `$container_ids >/dev/null 2>&1 || true
  fi
}

recreate_service_with_retry() {
  service_name="`$1"
  profile="`${2:-}"
  attempts=4
  attempt=1
  output_file="`$(mktemp)"

  while [ "`$attempt" -le "`$attempts" ]; do
    if { [ -n "`$profile" ] && compose --profile "`$profile" up -d --no-deps --force-recreate "`$service_name" >"`$output_file" 2>&1; } \
        || { [ -z "`$profile" ] && compose up -d --no-deps --force-recreate "`$service_name" >"`$output_file" 2>&1; }; then
      cat "`$output_file"
      rm -f "`$output_file"
      return 0
    else
      status="`$?"
    fi
    cat "`$output_file" >&2

    if grep -qi 'removal .* already in progress\|already in progress\|No such container\|already in use' "`$output_file"; then
      echo "Docker is still removing an old `$service_name container; retrying recreate (`$attempt/`$attempts)..." >&2
      sleep "`$((attempt * 5))"
      remove_service_containers "`$service_name"
      attempt="`$((attempt + 1))"
      continue
    fi

    rm -f "`$output_file"
    return "`$status"
  done

  rm -f "`$output_file"
  echo "Failed to recreate `$service_name after `$attempts attempts." >&2
  return 1
}

normalize_public_bind_mount_permissions() {
  local relative_path target unsafe_entry

  # The deploy archive also contains the production env, so extraction keeps
  # the restrictive global umask. Windows tar metadata plus umask 077 can make
  # public bind-mounted configs 0600/0700; normalize only the audited public
  # paths required by non-root containers, never the env, backups, or scripts.
  for relative_path in infrastructure infrastructure/keycloak; do
    target="`$remote_path/`$relative_path"
    if [ ! -d "`$target" ] || [ -L "`$target" ]; then
      echo "Public bind-mount parent is missing or unsafe: `$target" >&2
      return 1
    fi
  done

  for relative_path in \
      infrastructure/keycloak/themes \
      infrastructure/prometheus \
      infrastructure/loki \
      infrastructure/tempo \
      infrastructure/alloy \
      infrastructure/grafana; do
    target="`$remote_path/`$relative_path"
    if [ ! -d "`$target" ] || [ -L "`$target" ]; then
      echo "Public bind-mount tree is missing or unsafe: `$target" >&2
      return 1
    fi
    unsafe_entry="`$(find -P "`$target" ! -type d ! -type f -print -quit)"
    if [ -n "`$unsafe_entry" ]; then
      echo "Public bind-mount tree contains an unsafe entry: `$unsafe_entry" >&2
      return 1
    fi
  done

  for relative_path in infrastructure/keycloak/realm-config.prod.json; do
    target="`$remote_path/`$relative_path"
    if [ ! -f "`$target" ] || [ -L "`$target" ]; then
      echo "Public bind-mount file is missing or unsafe: `$target" >&2
      return 1
    fi
  done

  chmod 0755 -- "`$remote_path/infrastructure" "`$remote_path/infrastructure/keycloak"
  for relative_path in \
      infrastructure/keycloak/themes \
      infrastructure/prometheus \
      infrastructure/loki \
      infrastructure/tempo \
      infrastructure/alloy \
      infrastructure/grafana; do
    target="`$remote_path/`$relative_path"
    find -P "`$target" -type d -exec chmod 0755 -- {} +
    find -P "`$target" -type f -exec chmod 0644 -- {} +
  done
  chmod 0644 -- "`$remote_path/infrastructure/keycloak/realm-config.prod.json"
}

ensure_nginx_certs() {
  mkdir -p data/nginx/certs data/nginx/www data/nginx/logs

  if [ ! -f data/nginx/certs/fullchain.pem ] && [ -f data/nginx/o-ogo.crt ]; then
    cp data/nginx/o-ogo.crt data/nginx/certs/fullchain.pem
  fi

  if [ ! -f data/nginx/certs/privkey.pem ] && [ -f data/nginx/o-ogo.key ]; then
    cp data/nginx/o-ogo.key data/nginx/certs/privkey.pem
  fi

  if [ ! -f data/nginx/certs/fullchain.pem ] || [ ! -f data/nginx/certs/privkey.pem ]; then
    echo "Missing nginx TLS files. Expected data/nginx/certs/fullchain.pem and data/nginx/certs/privkey.pem." >&2
    exit 1
  fi
}

backup_dir=".deploy-backups/`$deploy_tag/rollout-`$deploy_lock_token"
mkdir -p "`$backup_dir"
chmod 700 .deploy-backups ".deploy-backups/`$deploy_tag" "`$backup_dir" || true
if [ -f docker-compose.yaml ]; then
  cp docker-compose.yaml "`$backup_dir/docker-compose.yaml"
fi
if [ -f "`$env_file" ]; then
  cp "`$env_file" "`$backup_dir/`$env_file"
  chmod 600 "`$backup_dir/`$env_file" || true
fi
if [ -f /usr/local/sbin/otziv-prod-up.sh ]; then
  cp /usr/local/sbin/otziv-prod-up.sh "`$backup_dir/otziv-prod-up.sh"
  chmod 700 "`$backup_dir/otziv-prod-up.sh" || true
fi
for systemd_unit in otziv-prod-up.timer otziv-prod-up.service; do
  systemd_unit_source="`$(sudo -n systemctl show "`$systemd_unit" --property=FragmentPath --value)"
  if [ -z "`$systemd_unit_source" ] || [ ! -f "`$systemd_unit_source" ] || [ -L "`$systemd_unit_source" ]; then
    echo "Cannot safely preserve existing systemd unit: `$systemd_unit" >&2
    exit 1
  fi
  cp "`$systemd_unit_source" "`$backup_dir/`$systemd_unit"
  chmod 600 "`$backup_dir/`$systemd_unit" || true
done
unset systemd_unit systemd_unit_source
if [ -L .self-heal-env-file ]; then
  echo "Refusing to replace symlinked production self-heal env selector." >&2
  exit 1
fi
if [ -f .self-heal-env-file ]; then
  cp .self-heal-env-file "`$backup_dir/self-heal-env-file"
  chmod 600 "`$backup_dir/self-heal-env-file" || true
fi
printf '%s\n' \
  "Rollback scaffold only (review before use):" \
  "  bash infrastructure/scripts/prod/create-pre-deploy-db-backup.sh verify <backup.sql.gz.enc> <backup.sql.gz.enc.manifest> `$env_file" \
  "  sudo systemctl disable --now otziv-prod-up.timer" \
  "  sudo systemctl stop otziv-prod-up.service" \
  "  docker compose -f docker-compose.yaml --env-file `$env_file --profile external-review stop nginx app whatsapp_lika whatsapp_vika external-review-worker" \
  "  bash infrastructure/scripts/prod/create-pre-deploy-db-backup.sh restore-clean <backup.sql.gz.enc> <backup.sql.gz.enc.manifest> `$env_file my-mysql I_UNDERSTAND_THIS_REPLACES_PRODUCTION_DATABASE" \
  "  cp `$backup_dir/docker-compose.yaml docker-compose.yaml" \
  "  cp `$backup_dir/`$env_file `$env_file" \
  "  sudo install -o root -g root -m 0755 `$backup_dir/otziv-prod-up.sh /usr/local/sbin/otziv-prod-up.sh" \
  "  sudo install -o root -g root -m 0644 `$backup_dir/otziv-prod-up.timer /etc/systemd/system/otziv-prod-up.timer" \
  "  sudo install -o root -g root -m 0644 `$backup_dir/otziv-prod-up.service /etc/systemd/system/otziv-prod-up.service" \
  "  sudo systemctl daemon-reload" \
  "  if [ -f `$backup_dir/self-heal-env-file ]; then cp `$backup_dir/self-heal-env-file .self-heal-env-file; else rm -f .self-heal-env-file; fi" \
  "  docker compose -f docker-compose.yaml --env-file `$env_file pull app nginx" \
  "  docker compose -f docker-compose.yaml --env-file `$env_file up -d --no-deps app nginx" \
  "  # Re-enable otziv-prod-up.timer only after application/database health is verified." \
  "Encrypted DB backup format: OTZIV-PREDEPLOY-DB-V2 (AES-256-CBC/PBKDF2-SHA256 + derived HMAC-SHA256 + authenticated schema defaults)." \
  "Clean restore always drops/recreates the schema and refuses to run while self-heal autostart, units, or writers are active." > "`$backup_dir/ROLLBACK.txt"
chmod 600 "`$backup_dir/ROLLBACK.txt" || true

rm -rf .deploy-mobile-update
tar --warning=no-timestamp -xzf "`$bundle_path" -C "`$remote_path"
rm -f "`$bundle_path"

if [ ! -f docker-compose.yaml ]; then
  echo "docker-compose.yaml was not uploaded to `$remote_path" >&2
  exit 1
fi
normalize_public_bind_mount_permissions

if [ "`$uploaded_env" != "1" ]; then
  if [ ! -f "`$env_file" ]; then
    echo "`$env_file does not exist on VPS. Remove -SkipEnvUpload for the first deploy." >&2
    exit 1
  fi
  set_env APP_IMAGE "`$app_image"
  set_env WEB_IMAGE "`$web_image"
fi
set_env EXTERNAL_REVIEW_WORKER_IMAGE "`$external_review_worker_image"
if [ "`$deploy_external_review_worker" = "1" ]; then
  set_env EXTERNAL_REVIEW_CHECK_ENABLED "true"
else
  set_env EXTERNAL_REVIEW_CHECK_ENABLED "false"
fi
set_env WHATSAPP_IMAGE "otziv-whatsapp:`$deploy_tag"

chmod 600 "`$env_file" || true

set_env OTZIV_APP_BASE_URL "https://o-ogo.ru"
set_env OTZIV_AUTH_LEGACY_MIGRATION_ENABLED "false"
set_env OTZIV_WORKER_CELLULAR_ACCESS_MODE "ENFORCE"
set_env OTZIV_WORKER_CELLULAR_ALLOWED_CIDRS "178.177.216.0/22,178.177.220.0/22,91.78.236.0/22,91.78.216.0/21,91.78.224.0/21,91.79.216.0/21,91.79.224.0/21,91.79.232.0/22,89.113.30.0/23"
set_env MAX_BOT_WEBHOOK_AUTO_REGISTER_ENABLED "true"
set_env MAX_BOT_WEBHOOK_UPDATE_TYPES "bot_started,bot_added,message_created"
set_env MAX_BOT_LONG_POLLING_ENABLED "false"
set_env MAX_BOT_API_BASE_URL "https://platform-api2.max.ru"
set_env WHATSAPP_HEALTH_MONITOR_ENABLED "true"
set_env WHATSAPP_HEALTH_MONITOR_RESTART_ENABLED "false"
set_env WHATSAPP_GATEWAY_AUTH_REQUIRED "true"
set_env EXTERNAL_REVIEW_WORKER_AUTH_REQUIRED "true"
set_env KEYCLOAK_PUBLIC_URL "https://o-ogo.ru/keycloak"
set_env KEYCLOAK_ISSUER_URI "https://o-ogo.ru/keycloak/realms/otziv"
set_env KEYCLOAK_JWK_SET_URI "http://keycloak:8080/keycloak/realms/otziv/protocol/openid-connect/certs"
set_env KEYCLOAK_ADMIN_SERVER_URL "http://keycloak:8080/keycloak"
set_env KC_PROXY_TRUSTED_ADDRESSES "172.16.0.0/12,10.0.0.0/8,192.168.0.0/16,127.0.0.0/8"
set_env TELEGRAM_BOT_REGISTRATION_ENABLED "true"
set_env TELEGRAM_BOT_SENDING_ENABLED "true"
outbound_proxy_host="`$(get_env OPENAI_PROXY_HOST "`$vps_host")"
outbound_proxy_port="`$(get_env OPENAI_PROXY_PORT "8888")"
set_env TELEGRAM_PROXY_ENABLED "true"
set_env TELEGRAM_PROXY_HOST "`$outbound_proxy_host"
set_env TELEGRAM_PROXY_PORT "`$outbound_proxy_port"
set_env MAX_PROXY_ENABLED "false"
set_env MAX_PROXY_HOST ""

ensure_generated_link_secret TELEGRAM_BOT_LINK_SECRET
ensure_generated_link_secret MAX_BOT_LINK_SECRET
validate_security_prerequisites

ensure_nginx_certs
find infrastructure/scripts/prod -type f -name '*.sh' -exec sed -i 's/\r$//' {} +
find infrastructure/systemd -type f -name 'otziv-prod-up.*' -exec sed -i 's/\r$//' {} +
self_heal_selector_temp="`$(mktemp "`$remote_path/.self-heal-env-file.XXXXXXXX")"
active_env_temp="`$self_heal_selector_temp"
printf '%s\n' "`$env_file" > "`$self_heal_selector_temp"
chmod 600 "`$self_heal_selector_temp"
mv "`$self_heal_selector_temp" "`$remote_path/.self-heal-env-file"
active_env_temp=""
sudo -n install -o root -g root -m 0755 \
  infrastructure/scripts/prod/otziv-prod-up.sh /usr/local/sbin/otziv-prod-up.sh
if [ "`$(grep -o '@@OTZIV_DEPLOY_PATH@@' infrastructure/systemd/otziv-prod-up.service.in | wc -l | tr -d ' ')" -ne 2 ]; then
  echo "Production self-heal service template has an unexpected deploy-path placeholder count." >&2
  exit 1
fi
active_systemd_unit_stage="`$(mktemp -d "`$remote_path/.systemd-units.XXXXXXXX")"
chmod 700 "`$active_systemd_unit_stage"
sed "s|@@OTZIV_DEPLOY_PATH@@|`$remote_path|g" \
  infrastructure/systemd/otziv-prod-up.service.in > "`$active_systemd_unit_stage/otziv-prod-up.service"
cp infrastructure/systemd/otziv-prod-up.timer "`$active_systemd_unit_stage/otziv-prod-up.timer"
chmod 644 "`$active_systemd_unit_stage/otziv-prod-up.service" "`$active_systemd_unit_stage/otziv-prod-up.timer"
if grep -R -Fq '@@OTZIV_DEPLOY_PATH@@' "`$active_systemd_unit_stage"; then
  echo "Production self-heal systemd unit rendering left an unresolved placeholder." >&2
  exit 1
fi
systemd-analyze verify \
  "`$active_systemd_unit_stage/otziv-prod-up.service" \
  "`$active_systemd_unit_stage/otziv-prod-up.timer"
for systemd_unit_target in /etc/systemd/system/otziv-prod-up.timer /etc/systemd/system/otziv-prod-up.service; do
  if [ -L "`$systemd_unit_target" ]; then
    echo "Refusing to replace symlinked production systemd unit: `$systemd_unit_target" >&2
    exit 1
  fi
done
sudo -n install -o root -g root -m 0644 \
  "`$active_systemd_unit_stage/otziv-prod-up.timer" /etc/systemd/system/otziv-prod-up.timer
sudo -n install -o root -g root -m 0644 \
  "`$active_systemd_unit_stage/otziv-prod-up.service" /etc/systemd/system/otziv-prod-up.service
sudo -n systemctl daemon-reload
rm -rf -- "`$active_systemd_unit_stage"
active_systemd_unit_stage=""
unset systemd_unit_target
chmod +x infrastructure/scripts/prod/apply-keycloak-prod-settings.sh || true
chmod +x infrastructure/scripts/prod/validate-flyway-migrations.sh || true
chmod +x infrastructure/scripts/prod/create-pre-deploy-db-backup.sh || true
chmod +x infrastructure/scripts/prod/register-max-webhook.sh || true
require_compose_service whatsapp_lika
require_compose_service whatsapp_vika
assert_compose_service_image app "`$app_image"
assert_compose_service_image nginx "`$web_image"
if [ "`$deploy_external_review_worker" = "1" ]; then
  require_compose_service external-review-worker external-review
  assert_compose_service_image external-review-worker "`$external_review_worker_image" external-review
  compose --profile external-review pull app nginx external-review-worker
else
  compose pull app nginx
fi
if ! docker ps --format '{{.Names}}' | grep -Fxq my-mysql; then
  echo "MySQL stopped after the verified pre-deploy backup; refusing to start Flyway." >&2
  exit 1
fi
current_flyway_sha="`$(current_flyway_fingerprint)"
if [ "`$current_flyway_sha" != "`$expected_flyway_fingerprint" ]; then
  echo "Flyway history changed after the verified pre-deploy backup; refusing to migrate a different database state." >&2
  exit 1
fi
bash infrastructure/scripts/prod/validate-flyway-migrations.sh "`$app_image" my-mysql
compose build whatsapp_lika whatsapp_vika
if ! compose run --rm --no-deps --interactive=false -T --entrypoint node whatsapp_lika chromium-smoke.js </dev/null >/dev/null 2>&1; then
  echo "WhatsApp Chromium sandbox preflight failed; existing gateway containers were not stopped." >&2
  exit 1
fi
compose run --rm --no-deps --interactive=false -T --cap-add CHOWN --user 0 --entrypoint chown app -R 10001:10001 /app/logs /app/backup /app/mobile-releases /app/sent-hashes </dev/null
compose up -d --no-deps mysql keycloak-postgres loki tempo
wait_service_healthy mysql 600
wait_service_healthy keycloak-postgres 600
compose up -d --no-deps keycloak
wait_service_healthy keycloak 900
if [ "`$deploy_external_review_worker" = "1" ]; then
  recreate_service_with_retry external-review-worker external-review
  wait_service_healthy external-review-worker 300 external-review
  assert_running_service_image external-review-worker "`$external_review_worker_image" external-review
fi
recreate_service_with_retry app
wait_service_healthy app 1200
assert_running_service_image app "`$app_image"
if [ "`$deploy_external_review_worker" != "1" ]; then
  # Keep an old worker available until the replacement backend has started
  # with the hard switch disabled. A failed app rollout then preserves the
  # complete previous release instead of removing one of its dependencies.
  compose --profile external-review stop external-review-worker
fi
compose up -d --no-deps prometheus
wait_service_healthy loki 600
wait_service_healthy tempo 600
wait_service_healthy prometheus 600
compose up -d --no-deps grafana
wait_service_healthy grafana 600
compose stop whatsapp_lika whatsapp_vika
compose run --rm --no-deps --interactive=false -T --cap-add CHOWN --user 0 --entrypoint sh whatsapp_lika -c 'node_uid="`$(id -u node)"; node_gid="`$(id -g node)"; chown -R "`$node_uid:`$node_gid" /auth' </dev/null
compose run --rm --no-deps --interactive=false -T --cap-add CHOWN --user 0 --entrypoint sh whatsapp_vika -c 'node_uid="`$(id -u node)"; node_gid="`$(id -g node)"; chown -R "`$node_uid:`$node_gid" /auth' </dev/null
recreate_service_with_retry whatsapp_lika
recreate_service_with_retry whatsapp_vika
if [ "`$deploy_external_review_worker" = "1" ]; then
  compose --profile external-review up -d --remove-orphans --no-deps dozzle alloy
else
  compose up -d --remove-orphans --no-deps dozzle alloy
fi
if [ "`$(get_env PHPMYADMIN_ENABLED false)" = "true" ]; then
  echo "Starting loopback-only phpMyAdmin by explicit production opt-in."
  compose --profile db-admin up -d --no-deps phpmyadmin
else
  compose --profile db-admin stop phpmyadmin >/dev/null 2>&1 || true
fi
wait_service_healthy app 300
wait_service_healthy keycloak 300
wait_service_healthy grafana 300
recreate_service_with_retry nginx
wait_service_healthy nginx 300
assert_running_service_image nginx "`$web_image"
wait_service_healthy whatsapp_lika 300
keycloak_settings_applied=0
for attempt in 1 2 3; do
  wait_service_healthy keycloak 300

  if sh infrastructure/scripts/prod/apply-keycloak-prod-settings.sh "`$env_file" </dev/null; then
    keycloak_settings_applied=1
    break
  fi

  echo "Keycloak production settings failed on attempt `$attempt; retrying in 10 seconds..." >&2
  sleep 10
done

if [ "`$keycloak_settings_applied" != "1" ]; then
  echo "Failed to apply Keycloak production settings after retries." >&2
  exit 1
fi
# The WhatsApp gateway may need its 10-minute startup watchdog followed by a
# fresh initialization. Finalize Keycloak first and leave recovery headroom
# instead of reporting a partial deployment at the watchdog deadline.
if ! wait_service_healthy whatsapp_vika 720; then
  echo "whatsapp_vika stayed authenticated without becoming ready; restarting its container once..."
  compose restart whatsapp_vika
  wait_service_healthy whatsapp_vika 300
fi
wait_service_healthy app 1200
wait_service_healthy keycloak 900
wait_service_healthy nginx 300
assert_running_service_image app "`$app_image"
assert_running_service_image nginx "`$web_image"
wait_service_healthy whatsapp_lika 300
wait_service_healthy whatsapp_vika 300
if [ "`$deploy_external_review_worker" = "1" ]; then
  wait_service_healthy external-review-worker 300 external-review
  assert_running_service_image external-review-worker "`$external_review_worker_image" external-review
fi
app_container_id="`$(compose ps -q app | head -n 1)"
bash infrastructure/scripts/prod/register-max-webhook.sh "`$env_file" "`$app_container_id"
# Publish the APK only after the new backend and every required production
# dependency have passed their final health checks. A failed rollout therefore
# cannot advertise a client version that expects an unavailable backend.
if [ "`$deploy_external_review_worker" = "1" ]; then
  compose --profile external-review ps
else
  compose ps
fi
publish_bundled_mobile_release
release_payload_complete="1"
"@
    $remoteScript = $remoteScript -replace "`r`n", "`n" -replace "`r", "`n"

    # Never execute the rollout directly from SSH stdin. Commands such as
    # `docker compose run` attach stdin by default and can otherwise consume
    # the unread tail of a `bash -s` script while still returning exit code 0.
    $localRolloutScriptPath = Join-Path $stageRoot '.deploy-rollout.sh'
    [System.IO.File]::WriteAllText(
        $localRolloutScriptPath,
        $remoteScript,
        [System.Text.UTF8Encoding]::new($false)
    )
    Protect-SensitiveLocalPath -Path $localRolloutScriptPath
    $remoteRolloutScriptSha256 = (Get-FileHash -LiteralPath $localRolloutScriptPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $remoteRolloutScriptSha256Quoted = ConvertTo-BashSingleQuoted $remoteRolloutScriptSha256

    Write-Host "Uploading verified rollout script to VPS..."
    Copy-DeployBundle `
        -ScpArgs $scpArgs `
        -BundlePath $localRolloutScriptPath `
        -Remote $remote `
        -RemoteBundle $remoteRolloutScript

    $remoteRolloutRunner = @"
set -Eeuo pipefail
umask 077
rollout_script=$remoteRolloutScriptQuoted
rollout_dir=$remoteUploadDirectoryQuoted
expected_sha256=$remoteRolloutScriptSha256Quoted
if [ ! -d "`$rollout_dir" ] || [ -L "`$rollout_dir" ]; then
  echo "Protected rollout directory is missing or unsafe." >&2
  exit 1
fi
if [ ! -f "`$rollout_script" ] || [ -L "`$rollout_script" ]; then
  echo "Uploaded rollout script is missing or unsafe." >&2
  exit 1
fi
chmod 700 "`$rollout_script"
actual_sha256="`$(sha256sum -- "`$rollout_script" | awk '{print `$1}')"
if [ "`$actual_sha256" != "`$expected_sha256" ]; then
  echo "Uploaded rollout script SHA-256 mismatch." >&2
  exit 1
fi
exec bash "`$rollout_script" </dev/null
"@
    $remoteRolloutRunner = $remoteRolloutRunner -replace "`r`n", "`n" -replace "`r", "`n"

    Write-Host "Deploying on VPS: ${remote}:$VpsPath"
    $remoteRolloutStarted = $true
    $remoteDeployOutput = @()
    & ssh @sshArgs $remote $remoteRolloutRunner | Tee-Object -Variable remoteDeployOutput
    $remoteDeployExitCode = $LASTEXITCODE
    if ($remoteDeployExitCode -ne 0) {
        throw "Remote deploy failed."
    }
    $expectedRemoteDeployMarker = "OTZIV_DEPLOY_COMPLETE=$remoteDeployLockToken"
    $matchingRemoteDeployMarkers = @($remoteDeployOutput | Where-Object {
        ([string]$_) -ceq $expectedRemoteDeployMarker
    })
    if ($matchingRemoteDeployMarkers.Count -ne 1) {
        throw "Remote deploy exited without exactly one authenticated completion marker."
    }
    $remoteDeployLockAcquired = $false
    $remoteBundleUploaded = $false
} finally {
    if ($remoteDeployLockAcquired -and -not $remoteRolloutStarted) {
        $remoteLockDirectory = $VpsPath.TrimEnd('/') + '/.deploy.lock.d'
        $remoteLockDirectoryQuoted = ConvertTo-BashSingleQuoted $remoteLockDirectory
        $remoteLockTokenQuoted = ConvertTo-BashSingleQuoted $remoteDeployLockToken
        $remoteLockCleanupCommand = @"
lock_dir=$remoteLockDirectoryQuoted
token=$remoteLockTokenQuoted
assert_self_heal_timer_scheduled() {
  timer_active_state="`$(sudo -n systemctl show otziv-prod-up.timer --property=ActiveState --value 2>/dev/null)" || return 1
  timer_sub_state="`$(sudo -n systemctl show otziv-prod-up.timer --property=SubState --value 2>/dev/null)" || return 1
  timer_next_elapse="`$(sudo -n systemctl show otziv-prod-up.timer --property=NextElapseUSecMonotonic --value 2>/dev/null)" || return 1
  [ "`$timer_active_state" = "active" ] && [ "`$timer_sub_state" = "waiting" ] \
    && [ -n "`$timer_next_elapse" ] && [ "`$timer_next_elapse" != "infinity" ] && [ "`$timer_next_elapse" != "0" ]
}
if [ ! -d "`$lock_dir" ]; then
  echo "Protected deploy lock disappeared before local pre-rollout cleanup." >&2
  exit 75
fi
if [ ! -f "`$lock_dir/owner" ] || [ "`$(cat "`$lock_dir/owner")" != "`$token" ]; then
  echo "Protected deploy lock ownership changed; refusing to remove it." >&2
  exit 75
fi
state_file="`$lock_dir/self-heal-timer-was-active"
enabled_state_file="`$lock_dir/self-heal-timer-was-enabled"
if [ ! -f "`$state_file" ] || [ ! -f "`$enabled_state_file" ]; then
  echo "Protected self-heal state is missing; leaving deploy lock for manual recovery." >&2
  exit 75
fi
timer_was_active="`$(cat "`$state_file")"
timer_was_enabled="`$(cat "`$enabled_state_file")"
case "`$timer_was_active" in
  0|1) ;;
  *)
    echo "Protected self-heal state is invalid; refusing automatic lock cleanup." >&2
    exit 75
    ;;
esac
case "`$timer_was_enabled" in
  0|1) ;;
  *)
    echo "Protected self-heal enablement state is invalid; refusing automatic lock cleanup." >&2
    exit 75
    ;;
esac
if [ "`$timer_was_enabled" = "1" ]; then
  if ! sudo -n systemctl enable otziv-prod-up.timer; then
    sudo -n systemctl disable otziv-prod-up.timer || true
    sudo -n systemctl stop otziv-prod-up.timer otziv-prod-up.service || true
    exit 1
  fi
fi
if [ "`$timer_was_active" = "1" ]; then
  if ! sudo -n systemctl start otziv-prod-up.timer \
      || ! assert_self_heal_timer_scheduled; then
    sudo -n systemctl disable otziv-prod-up.timer || true
    sudo -n systemctl stop otziv-prod-up.timer otziv-prod-up.service || true
    exit 1
  fi
fi
rm -f -- "`$state_file" "`$enabled_state_file" "`$lock_dir/owner"
rmdir -- "`$lock_dir"
"@
        $remoteLockCleanupCommand = $remoteLockCleanupCommand -replace "`r`n", "`n" -replace "`r", "`n"
        & ssh @sshArgs $remote $remoteLockCleanupCommand 2>$null
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not release the protected production deploy lock: $remoteLockDirectory"
        }
    } elseif ($remoteDeployLockAcquired) {
        Write-Warning 'The remote rollout started but did not confirm completion. Its durable lock was left for remote cleanup or manual inspection.'
    } elseif ($remotePreBackupInvocationStarted -and -not $remoteRolloutStarted) {
        Write-Warning "The pre-deploy backup SSH session did not confirm completion. For safety no ambiguous remote lock was removed; inspect $VpsPath/.deploy.lock.d and otziv-prod-up.timer before retrying."
    }
    if ($remoteBundleUploaded) {
        $remoteBundleCleanupCommand = @"
bundle=$(ConvertTo-BashSingleQuoted $remoteBundle)
rollout_script=$(ConvertTo-BashSingleQuoted $remoteRolloutScript)
bundle_dir=$(ConvertTo-BashSingleQuoted $remoteUploadDirectory)
rm -f -- "`$bundle" "`$rollout_script"
rmdir -- "`$bundle_dir"
"@
        $remoteBundleCleanupCommand = $remoteBundleCleanupCommand -replace "`r`n", "`n" -replace "`r", "`n"
        & ssh @sshArgs $remote $remoteBundleCleanupCommand 2>$null
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not remove the protected temporary deploy bundle from VPS: $remoteBundle"
        }
    }
    if (Test-Path -LiteralPath $stageRoot) {
        Remove-Item -LiteralPath $stageRoot -Recurse -Force
    }
    if (Test-Path -LiteralPath $bundlePath) {
        Remove-Item -LiteralPath $bundlePath -Force
    }
}

Write-Host "Deploy complete."
