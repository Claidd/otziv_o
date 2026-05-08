param(
    [string]$DockerHubNamespace = "claid38",
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
    [switch]$SkipEnvUpload,
    [switch]$NoBuildCache,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Show-Help {
    @'
Deploy Otziv production stack without Docker Hub.

The script builds backend/frontend images locally, saves them to a Docker tar,
copies the tar directly to the VPS over SSH, loads images there, updates the
remote env image tags, and starts docker compose without pulling from registry.

Example:
  .\infrastructure\scripts\prod\deploy-prod-ssh-images.ps1 -VpsHost 95.213.248.152 -VpsUser root -VpsPath /docker -SshKey C:\Users\Hunt\.ssh\otziv_vps_ed25519 -RemoteEnvFile .env -Tag 2.3 -SkipEnvUpload
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
$remoteImagesTar = "/tmp/otziv-images-${Tag}.tar"
$stageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("otziv-deploy-" + [System.Guid]::NewGuid().ToString("N"))
$bundlePath = Join-Path ([System.IO.Path]::GetTempPath()) ("otziv-deploy-${Tag}.tar.gz")
$imagesTarPath = Join-Path ([System.IO.Path]::GetTempPath()) ("otziv-images-${Tag}.tar")

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

Write-Host "Building local images for direct SSH deploy:"
Write-Host "  APP_IMAGE=$appImage"
Write-Host "  WEB_IMAGE=$webImage"

$env:APP_IMAGE = $appImage
$env:WEB_IMAGE = $webImage

$buildArgs = @("compose", "-f", $buildCompose, "build")
if ($NoBuildCache) {
    $buildArgs += "--no-cache"
}
Invoke-External -FilePath "docker" -Arguments $buildArgs

if (Test-Path -LiteralPath $imagesTarPath) {
    Remove-Item -LiteralPath $imagesTarPath -Force
}
Invoke-External -FilePath "docker" -Arguments @("save", "-o", $imagesTarPath, $appImage, $webImage)

New-Item -ItemType Directory -Path $stageRoot -Force | Out-Null
try {
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "docker-compose.yaml"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\nginx"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\keycloak"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\prometheus"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\loki"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\alloy"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\grafana"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\init-letsencrypt.sh"
    Copy-DeployPath -RepoRoot $repoRoot -StageRoot $stageRoot -RelativePath "infrastructure\scripts\prod\renew-letsencrypt.sh"

    if (-not $SkipEnvUpload) {
        $stageEnv = Join-Path $stageRoot $RemoteEnvFile
        Copy-Item -LiteralPath $envFilePath -Destination $stageEnv -Force
        Set-EnvFileValue -Path $stageEnv -Name "APP_IMAGE" -Value $appImage
        Set-EnvFileValue -Path $stageEnv -Name "WEB_IMAGE" -Value $webImage
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
    Invoke-External -FilePath "scp" -Arguments ($scpArgs + @($imagesTarPath, "${remote}:$remoteImagesTar"))

    $remotePathQuoted = ConvertTo-BashSingleQuoted $VpsPath
    $remoteBundleQuoted = ConvertTo-BashSingleQuoted $remoteBundle
    $remoteImagesTarQuoted = ConvertTo-BashSingleQuoted $remoteImagesTar
    $appImageQuoted = ConvertTo-BashSingleQuoted $appImage
    $webImageQuoted = ConvertTo-BashSingleQuoted $webImage
    $remoteEnvFileQuoted = ConvertTo-BashSingleQuoted $RemoteEnvFile
    $deployTagQuoted = ConvertTo-BashSingleQuoted $Tag
    $uploadedEnv = if ($SkipEnvUpload) { "0" } else { "1" }

    $remoteScript = @"
set -Eeuo pipefail

remote_path=$remotePathQuoted
bundle_path=$remoteBundleQuoted
images_tar=$remoteImagesTarQuoted
app_image=$appImageQuoted
web_image=$webImageQuoted
env_file=$remoteEnvFileQuoted
deploy_tag=$deployTagQuoted
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

ensure_nginx_certs
docker load -i "`$images_tar"
rm -f "`$images_tar"

compose down --remove-orphans || true
compose up -d --remove-orphans
compose ps
"@
    $remoteScript = $remoteScript -replace "`r`n", "`n" -replace "`r", "`n"

    Write-Host "Deploying loaded images on VPS: ${remote}:$VpsPath"
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
    if (Test-Path -LiteralPath $imagesTarPath) {
        Remove-Item -LiteralPath $imagesTarPath -Force
    }
}

Write-Host "Deploy complete."
