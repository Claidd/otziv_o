param(
    [string]$DockerHubNamespace = "claid38",
    [string]$DockerLoginUsername = "",
    [string]$AppRepository = "otziv-app",
    [string]$WebRepository = "otziv-web",
    [string]$Tag = (Get-Date -Format "yyyyMMdd-HHmmss"),
    [string]$VpsHost = "",
    [string]$VpsUser = "root",
    [int]$VpsPort = 22,
    [string]$VpsPath = "/opt/otziv",
    [string]$SshKey = "",
    [string]$EnvFile = ".env.prod",
    [string]$RemoteEnvFile = ".env.prod",
    [switch]$DockerLogin,
    [switch]$SkipEnvUpload,
    [switch]$NoBuildCache,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Show-Help {
    @'
Deploy Otziv production stack from this local computer.

Example:
  .\infrastructure\scripts\prod\deploy-prod.ps1 -VpsHost 95.213.248.152 -VpsUser root -VpsPath /docker -SshKey C:\Users\Hunt\.ssh\otziv_vps_ed25519 -RemoteEnvFile .env -SkipEnvUpload

Useful options:
  -DockerHubNamespace claid38     Docker Hub namespace or username.
  -DockerLoginUsername claid38    Docker Hub login username. Defaults to DockerHubNamespace.
  -Tag 20260507-1                Image tag. Defaults to current date/time.
  -EnvFile .env.prod             Local env file uploaded to VPS as .env.prod.
  -RemoteEnvFile .env            Env file name used on the VPS.
  -DockerLogin                   Run local docker login before build and push.
  -SkipEnvUpload                 Keep VPS env file and only update APP_IMAGE/WEB_IMAGE in it.
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
$buildCompose = Join-Path $repoRoot "docker-compose.build.yaml"
$appImage = "${DockerHubNamespace}/${AppRepository}:${Tag}"
$webImage = "${DockerHubNamespace}/${WebRepository}:${Tag}"
$remote = "${VpsUser}@${VpsHost}"
$remoteBundle = "/tmp/otziv-deploy-${Tag}.tar.gz"
$stageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("otziv-deploy-" + [System.Guid]::NewGuid().ToString("N"))
$bundlePath = Join-Path ([System.IO.Path]::GetTempPath()) ("otziv-deploy-${Tag}.tar.gz")

if (-not (Test-Path -LiteralPath $buildCompose)) {
    throw "Missing build compose file: $buildCompose"
}

$envFilePath = if ([System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile
} else {
    Join-Path $repoRoot $EnvFile
}

if (-not $SkipEnvUpload -and -not (Test-Path -LiteralPath $envFilePath)) {
    throw "Env file not found: $envFilePath. Create it or pass -SkipEnvUpload."
}

Write-Host "Building and pushing:"
Write-Host "  APP_IMAGE=$appImage"
Write-Host "  WEB_IMAGE=$webImage"

if ($DockerLogin) {
    Invoke-External -FilePath "docker" -Arguments @("login", "-u", $DockerLoginUsername)
}

$env:APP_IMAGE = $appImage
$env:WEB_IMAGE = $webImage

$buildArgs = @("compose", "-f", $buildCompose, "build")
if ($NoBuildCache) {
    $buildArgs += "--no-cache"
}
Invoke-External -FilePath "docker" -Arguments $buildArgs
Invoke-External -FilePath "docker" -Arguments @("compose", "-f", $buildCompose, "push")

New-Item -ItemType Directory -Path $stageRoot -Force | Out-Null
try {
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "docker-compose.yaml"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath ".dockerignore"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "Dockerfile.whatsapp"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "whatsapp"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\nginx"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\keycloak"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\prometheus"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\loki"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\alloy"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\grafana"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\apply-keycloak-prod-settings.sh"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\validate-flyway-migrations.sh"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\init-letsencrypt.sh"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\renew-letsencrypt.sh"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\register-max-webhook.ps1"

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

    $sshArgs = @()
    $scpArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($SshKey)) {
        $sshArgs += @("-i", $SshKey)
        $scpArgs += @("-i", $SshKey)
    }
    $sshArgs += @("-p", "$VpsPort", "-o", "StrictHostKeyChecking=accept-new")
    $scpArgs += @("-P", "$VpsPort", "-o", "StrictHostKeyChecking=accept-new")

    $mkdirScript = "mkdir -p $(ConvertTo-BashSingleQuoted $VpsPath)"
    Invoke-External -FilePath "ssh" -Arguments ($sshArgs + @($remote, $mkdirScript))
    Invoke-External -FilePath "scp" -Arguments ($scpArgs + @($bundlePath, "${remote}:$remoteBundle"))

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
if [ -f docker-compose.yaml ]; then
  cp docker-compose.yaml "`$backup_dir/docker-compose.yaml"
fi
if [ -f "`$env_file" ]; then
  cp "`$env_file" "`$backup_dir/`$env_file"
fi

tar -xzf "`$bundle_path" -C "`$remote_path"
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

set_env OTZIV_APP_BASE_URL "https://o-ogo.ru"
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
if docker ps --format '{{.Names}}' | grep -Fxq my-mysql; then
  bash infrastructure/scripts/prod/validate-flyway-migrations.sh "`$app_image" my-mysql
else
  echo "MySQL container is not running yet; skipping pre-deploy Flyway validation."
fi
compose down --remove-orphans
compose build whatsapp_lika whatsapp_vika
compose up -d --remove-orphans
sh infrastructure/scripts/prod/apply-keycloak-prod-settings.sh "`$env_file"
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
