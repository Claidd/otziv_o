param(
    [string]$DockerHubNamespace = "claid38",
    [string]$DockerLoginUsername = "",
    [string]$AppRepository = "otziv-app",
    [string]$WebRepository = "otziv-web",
    [string]$Tag = (Get-Date -Format "yyyyMMdd-HHmmss"),
    [string]$VpsHost = "",
    [string]$VpsUser = "hunt",
    [int]$VpsPort = 22022,
    [string]$VpsPath = "/opt/otziv",
    [string]$SshKey = "",
    [string]$EnvFile = ".env.prod",
    [string]$RemoteEnvFile = ".env.prod",
    [switch]$DockerLogin,
    [switch]$SkipBuildPush,
    [switch]$SkipEnvUpload,
    [string]$MobileApkPath = "",
    [switch]$SkipMobileApkUpload,
    [switch]$NoBuildCache,
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
  -SkipBuildPush                 Skip local docker build/push and deploy already pushed APP_IMAGE/WEB_IMAGE tag.
  -SkipEnvUpload                 Keep VPS env file and only update APP_IMAGE/WEB_IMAGE in it.
  -MobileApkPath <path>          Publish this signed release APK. By default uses the highest code from mobile/builds.
  -SkipMobileApkUpload           Do not include a mobile APK in this deployment.
  -NoBuildCache                  Build images without Docker cache.
'@ | Write-Host
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $FilePath $($Arguments -join ' ')"
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
            Write-Warning "Command failed on attempt ${attempt}/${Attempts}: $FilePath $($Arguments -join ' '). Retrying in ${DelaySeconds}s..."
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

    return $parsed | Sort-Object VersionCode, @{ Expression = { $_.File.LastWriteTimeUtc }; Descending = $true } -Descending | Select-Object -First 1
}

if ($Help) {
    Show-Help
    exit 0
}

if ([string]::IsNullOrWhiteSpace($VpsHost)) {
    throw "Pass -VpsHost with your VPS IP address or hostname."
}

if ($Tag -notmatch "^[A-Za-z0-9_.-]+$") {
    throw "Docker tag may contain only letters, digits, underscore, dot, and dash."
}

if ([string]::IsNullOrWhiteSpace($DockerLoginUsername)) {
    $DockerLoginUsername = $DockerHubNamespace
}

if ([string]::IsNullOrWhiteSpace($RemoteEnvFile) -or $RemoteEnvFile.Contains("/") -or $RemoteEnvFile.Contains("\")) {
    throw "RemoteEnvFile must be a file name in the VPS deploy directory, for example .env or .env.prod."
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
$envResolverPath = Join-Path $repoRoot "infrastructure\scripts\Resolve-OtzivEnvFile.ps1"
if (-not (Test-Path -LiteralPath $envResolverPath)) {
    throw "Env resolver script not found: $envResolverPath"
}
. $envResolverPath
$buildCompose = Join-Path $repoRoot "docker-compose.build.yaml"
$appImage = "${DockerHubNamespace}/${AppRepository}:${Tag}"
$webImage = "${DockerHubNamespace}/${WebRepository}:${Tag}"
$remote = "${VpsUser}@${VpsHost}"
$remoteBundle = "/tmp/otziv-deploy-${Tag}.tar.gz"
$stageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("otziv-deploy-" + [System.Guid]::NewGuid().ToString("N"))
$bundlePath = Join-Path ([System.IO.Path]::GetTempPath()) ("otziv-deploy-${Tag}.tar.gz")
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

$envFilePath = Resolve-OtzivEnvFile -EnvFile $EnvFile -RepoRoot $repoRoot -AllowMissing:$SkipEnvUpload

if (-not $SkipEnvUpload -and -not (Test-Path -LiteralPath $envFilePath)) {
    throw "Env file not found: $envFilePath. Create it or pass -SkipEnvUpload."
}

if (-not $SkipEnvUpload) {
    Write-Host "Using env file: $envFilePath"
}

Write-Host "Building and pushing:"
Write-Host "  APP_IMAGE=$appImage"
Write-Host "  WEB_IMAGE=$webImage"
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

if (-not $SkipBuildPush) {
    $buildArgs = @("compose", "-f", $buildCompose, "build")
    if ($NoBuildCache) {
        $buildArgs += "--no-cache"
    }
    Invoke-External -FilePath "docker" -Arguments $buildArgs
    Write-Host "Pushing application image..."
    Invoke-External -FilePath "docker" -Arguments @("push", $appImage)
    Write-Host "Pushing web image..."
    Invoke-External -FilePath "docker" -Arguments @("push", $webImage)
    Write-Host "Docker images pushed successfully."
} else {
    Write-Host "Skipping docker build/push; deploying already published images."
}

if ($null -ne $mobileRelease) {
    Write-Host "Checking mobile APK state on VPS..."
    $remotePathForCheck = ConvertTo-BashSingleQuoted $VpsPath
    $mobileCodeForCheck = $mobileRelease.VersionCode
    $remoteMobileCheck = @"
remote_path=$remotePathForCheck
metadata="`$remote_path/data/mobile-releases/release.json"
if [ -f "`$metadata" ]; then
  code="`$(grep -o '"versionCode":[[:space:]]*[0-9]*' "`$metadata" | grep -o '[0-9]*' | head -n 1 || true)"
  file_name="`$(grep -o '"fileName":"[^"]*"' "`$metadata" | cut -d '"' -f 4 | head -n 1 || true)"
  if [ -n "`$code" ] && [ "`$code" -ge "$mobileCodeForCheck" ] && [ -n "`$file_name" ] && [ -f "`$remote_path/data/mobile-releases/`$file_name" ]; then
    find "`$remote_path/data/mobile-releases" -maxdepth 1 -type f -name '*.apk' ! -name "`$file_name" -delete
    printf 'PRESENT'
  else
    printf 'MISSING'
  fi
else
  printf 'MISSING'
fi
"@
    $remoteMobileState = (& ssh @sshArgs $remote $remoteMobileCheck).Trim()
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
try {
    Write-Host "Preparing deployment bundle..."
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "docker-compose.yaml"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath ".dockerignore"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "Dockerfile.whatsapp"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "whatsapp"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\nginx"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\keycloak"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\prometheus"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\loki"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\tempo"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\alloy"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\grafana"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\apply-keycloak-prod-settings.sh"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\validate-flyway-migrations.sh"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\init-letsencrypt.sh"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\renew-letsencrypt.sh"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\register-max-webhook.ps1"

    $uploadedMobileRelease = "0"
    if ($null -ne $mobileRelease) {
        $mobileStageDirectory = Join-Path $stageRoot ".deploy-mobile-update"
        New-Item -ItemType Directory -Path $mobileStageDirectory -Force | Out-Null
        $mobileFileName = "otziv-v$($mobileRelease.VersionName)-code$($mobileRelease.VersionCode).apk"
        $mobileStageApk = Join-Path $mobileStageDirectory $mobileFileName
        Copy-Item -LiteralPath $mobileRelease.File.FullName -Destination $mobileStageApk -Force
        $mobileSha256 = (Get-FileHash -LiteralPath $mobileStageApk -Algorithm SHA256).Hash.ToUpperInvariant()
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
        Set-EnvFileValue -Path $stageEnv -Name "OTZIV_APP_BASE_URL" -Value "https://o-ogo.ru"
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

    $mkdirScript = "mkdir -p $(ConvertTo-BashSingleQuoted $VpsPath)"
    Write-Host "Uploading deployment bundle to VPS..."
    Invoke-External -FilePath "ssh" -Arguments ($sshArgs + @($remote, $mkdirScript))
    Copy-DeployBundle -ScpArgs $scpArgs -BundlePath $bundlePath -Remote $remote -RemoteBundle $remoteBundle

    $remotePathQuoted = ConvertTo-BashSingleQuoted $VpsPath
    $remoteBundleQuoted = ConvertTo-BashSingleQuoted $remoteBundle
    $appRepoQuoted = ConvertTo-BashSingleQuoted "${DockerHubNamespace}/${AppRepository}"
    $webRepoQuoted = ConvertTo-BashSingleQuoted "${DockerHubNamespace}/${WebRepository}"
    $appImageQuoted = ConvertTo-BashSingleQuoted $appImage
    $webImageQuoted = ConvertTo-BashSingleQuoted $webImage
    $remoteEnvFileQuoted = ConvertTo-BashSingleQuoted $RemoteEnvFile
    $deployTagQuoted = ConvertTo-BashSingleQuoted $Tag
    $vpsHostQuoted = ConvertTo-BashSingleQuoted $VpsHost
    $uploadedEnv = if ($SkipEnvUpload) { "0" } else { "1" }

    $remoteScript = @"
set -Eeuo pipefail

remote_path=$remotePathQuoted
bundle_path=$remoteBundleQuoted
app_repo=$appRepoQuoted
web_repo=$webRepoQuoted
app_image=$appImageQuoted
web_image=$webImageQuoted
env_file=$remoteEnvFileQuoted
deploy_tag=$deployTagQuoted
vps_host=$vpsHostQuoted
uploaded_env=$uploadedEnv
uploaded_mobile_release=$uploadedMobileRelease
self_heal_timer="otziv-prod-up.timer"
self_heal_service="otziv-prod-up.service"
self_heal_was_active="0"

resume_self_heal() {
  if [ "`$self_heal_was_active" = "1" ]; then
    echo "Resuming production self-heal timer..."
    sudo -n systemctl start "`$self_heal_timer" || true
  fi
}

trap resume_self_heal EXIT

if command -v systemctl >/dev/null 2>&1 \
    && sudo -n systemctl is-active --quiet "`$self_heal_timer"; then
  echo "Pausing production self-heal timer during deploy..."
  sudo -n systemctl stop "`$self_heal_timer"
  sudo -n systemctl stop "`$self_heal_service" || true
  self_heal_was_active="1"
fi

compose() {
  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose -f docker-compose.yaml --env-file "`$env_file" "`$@"
  elif docker compose version >/dev/null 2>&1; then
    docker compose -f docker-compose.yaml --env-file "`$env_file" "`$@"
  else
    echo "Docker Compose is not installed. Install docker-compose or the Docker Compose plugin." >&2
    exit 1
  fi
}

require_compose_service() {
  service_name="`$1"
  services="`$(compose config --services 2>&1)" || {
    printf '%s\n' "`$services" >&2
    echo "Failed to evaluate production compose services." >&2
    exit 1
  }

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
  tmp_file="`$(mktemp)"

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

  mv "`$tmp_file" "`$file"
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
    printf '%s' "`$value" | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//"
  fi
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

  if ! mkdir -p "`$target_dir" 2>/dev/null || [ ! -w "`$target_dir" ]; then
    deploy_uid="`$(id -u)"
    deploy_gid="`$(id -g)"
    if sudo -n true >/dev/null 2>&1; then
      sudo mkdir -p "`$target_dir"
      sudo chown "`$deploy_uid:`$deploy_gid" "`$target_dir"
    else
      docker run --rm --user 0 \
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
  if [ -f "`$target_dir/release.json" ]; then
    current_code="`$(grep -o '"versionCode":[[:space:]]*[0-9]*' "`$target_dir/release.json" | grep -o '[0-9]*' | head -n 1 || true)"
    current_file_name="`$(grep -o '"fileName":"[^"]*"' "`$target_dir/release.json" | cut -d '"' -f 4 | head -n 1 || true)"
    [ -n "`$current_code" ] || current_code="0"
  fi

  if [ "`$current_code" -gt "`$incoming_code" ]; then
    echo "Mobile APK code `$incoming_code is older than published code `$current_code; skipping."
    rm -rf "`$bundle_dir"
    return 0
  fi
  if [ "`$current_code" -eq "`$incoming_code" ] && [ -n "`$current_file_name" ] && [ -f "`$target_dir/`$current_file_name" ]; then
    echo "Mobile APK code `$incoming_code is already published; skipping upload."
    find "`$target_dir" -maxdepth 1 -type f -name '*.apk' ! -name "`$current_file_name" -delete
    rm -rf "`$bundle_dir"
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
  echo "Published mobile APK code `$incoming_code and removed older APK files."
}

remove_repo_images() {
  repo="`$1"
  images="`$(docker image ls "`$repo" --format '{{.Repository}}:{{.Tag}}' | sort -u || true)"
  if [ -z "`$images" ]; then
    echo "No old images for `$repo"
    return 0
  fi

  echo "`$images" | while IFS= read -r image; do
    if [ -n "`$image" ] && [ "`$image" != "<none>:<none>" ]; then
      docker image rm "`$image" || true
    fi
  done
}

wait_service_healthy() {
  service_name="`$1"
  timeout_seconds="`$2"
  started_at="`$(date +%s)"

  echo "Waiting for `$service_name to become healthy..."
  while true; do
    container_id="`$(compose ps -q "`$service_name" | head -n 1 || true)"
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
  attempts=4
  attempt=1
  output_file="`$(mktemp)"

  while [ "`$attempt" -le "`$attempts" ]; do
    if compose up -d --no-deps --force-recreate "`$service_name" >"`$output_file" 2>&1; then
      cat "`$output_file"
      rm -f "`$output_file"
      return 0
    fi

    status="`$?"
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

mkdir -p "`$remote_path"
cd "`$remote_path"

backup_dir=".deploy-backups/`$deploy_tag"
mkdir -p "`$backup_dir"
chmod 700 .deploy-backups "`$backup_dir" || true
if [ -f docker-compose.yaml ]; then
  cp docker-compose.yaml "`$backup_dir/docker-compose.yaml"
fi
if [ -f "`$env_file" ]; then
  cp "`$env_file" "`$backup_dir/`$env_file"
  chmod 600 "`$backup_dir/`$env_file" || true
fi

rm -rf .deploy-mobile-update
tar --warning=no-timestamp -xzf "`$bundle_path" -C "`$remote_path"
rm -f "`$bundle_path"

if [ ! -f docker-compose.yaml ]; then
  echo "docker-compose.yaml was not uploaded to `$remote_path" >&2
  exit 1
fi

if [ "`$uploaded_env" != "1" ]; then
  if [ ! -f "`$env_file" ]; then
    echo "`$env_file does not exist on VPS. Remove -SkipEnvUpload for the first deploy." >&2
    exit 1
  fi
  set_env APP_IMAGE "`$app_image"
  set_env WEB_IMAGE "`$web_image"
fi

chmod 600 "`$env_file" || true

set_env OTZIV_APP_BASE_URL "https://o-ogo.ru"
set_env OTZIV_WORKER_CELLULAR_ACCESS_MODE "ENFORCE"
set_env OTZIV_WORKER_CELLULAR_ALLOWED_CIDRS "178.177.216.0/22,178.177.220.0/22,91.78.236.0/22,91.78.216.0/21,91.78.224.0/21,91.79.216.0/21,91.79.224.0/21,91.79.232.0/22,89.113.30.0/23"
set_env MAX_BOT_WEBHOOK_AUTO_REGISTER_ENABLED "true"
set_env MAX_BOT_WEBHOOK_UPDATE_TYPES "bot_started,bot_added,message_created"
set_env MAX_BOT_LONG_POLLING_ENABLED "false"
set_env WHATSAPP_HEALTH_MONITOR_ENABLED "true"
set_env WHATSAPP_HEALTH_MONITOR_RESTART_ENABLED "false"
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

ensure_nginx_certs
find infrastructure/scripts/prod -type f -name '*.sh' -exec sed -i 's/\r$//' {} +
chmod +x infrastructure/scripts/prod/apply-keycloak-prod-settings.sh || true
chmod +x infrastructure/scripts/prod/validate-flyway-migrations.sh || true
require_compose_service whatsapp_lika
require_compose_service whatsapp_vika
remove_repo_images "`$app_repo"
remove_repo_images "`$web_repo"
compose pull app nginx
publish_bundled_mobile_release
if docker ps --format '{{.Names}}' | grep -Fxq my-mysql; then
  bash infrastructure/scripts/prod/validate-flyway-migrations.sh "`$app_image" my-mysql
else
  echo "MySQL container is not running yet; skipping pre-deploy Flyway validation."
fi
compose build whatsapp_lika whatsapp_vika
compose up -d --remove-orphans --no-deps mysql keycloak-postgres loki tempo
wait_service_healthy mysql 600
wait_service_healthy keycloak-postgres 600
compose up -d --no-deps keycloak
wait_service_healthy keycloak 900
remove_service_containers app
recreate_service_with_retry app
wait_service_healthy app 1200
compose up -d --no-deps prometheus
wait_service_healthy loki 600
wait_service_healthy tempo 600
wait_service_healthy prometheus 600
compose up -d --no-deps grafana
wait_service_healthy grafana 600
remove_service_containers whatsapp_lika
recreate_service_with_retry whatsapp_lika
remove_service_containers whatsapp_vika
recreate_service_with_retry whatsapp_vika
compose up -d --remove-orphans --no-deps phpmyadmin dozzle alloy
wait_service_healthy app 300
wait_service_healthy keycloak 300
wait_service_healthy grafana 300
remove_service_containers nginx
recreate_service_with_retry nginx
wait_service_healthy nginx 300
wait_service_healthy whatsapp_lika 300
keycloak_settings_applied=0
for attempt in 1 2 3; do
  wait_service_healthy keycloak 300

  if sh infrastructure/scripts/prod/apply-keycloak-prod-settings.sh "`$env_file"; then
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
wait_service_healthy whatsapp_vika 720
compose ps
"@
    $remoteScript = $remoteScript -replace "`r`n", "`n" -replace "`r", "`n"

    Write-Host "Deploying on VPS: ${remote}:$VpsPath"
    $remoteScript | & ssh @sshArgs $remote "tr -d '\r' | bash -s"
    if ($LASTEXITCODE -ne 0) {
        throw "Remote deploy failed."
    }
} finally {
    if (Test-Path -LiteralPath $stageRoot) {
        Remove-Item -LiteralPath $stageRoot -Recurse -Force
    }
    if (Test-Path -LiteralPath $bundlePath) {
        Remove-Item -LiteralPath $bundlePath -Force
    }
}

Write-Host "Deploy complete."
