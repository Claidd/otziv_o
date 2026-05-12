param(
    [string]$EnvFile = ".env.prod-local",
    [string]$ComposeFile = "compose.prod-local.yaml",
    [string]$BaseUrl = "http://localhost:8088",
    [int]$TimeoutSeconds = 1200,
    [switch]$OfflineAppBuild,
    [switch]$NoOfflineFallback,
    [switch]$NoBuild,
    [switch]$NoUp,
    [switch]$NoLogs,
    [switch]$SkipOpenAiProxyIpSync,
    [switch]$WithDbAdmin
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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

function Stop-DbAdminServices {
    param([Parameter(Mandatory = $true)][string[]]$ComposeArguments)

    Write-Host "Stopping db-admin profile services for default smoke run."
    & docker @($ComposeArguments + @("--profile", "db-admin", "stop", "phpmyadmin")) | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: docker $($ComposeArguments + @("--profile", "db-admin", "stop", "phpmyadmin") -join ' ')"
    }
}

function Get-EnvValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $found = $null
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) {
            continue
        }

        if ($trimmed.Substring(0, $separator).Trim() -eq $Name) {
            $value = $trimmed.Substring($separator + 1).Trim()
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                $found = $value
            }
        }
    }

    return $found
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][datetime]$Deadline
    )

    Write-Host "Waiting for ${Name}: $Url"
    while ((Get-Date) -lt $Deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                Write-Host "$Name is OK ($($response.StatusCode))."
                return
            }
        } catch {
            Start-Sleep -Seconds 5
            continue
        }

        Start-Sleep -Seconds 5
    }

    throw "Timed out waiting for $Name at $Url"
}

function Test-RegistryBuildFailure {
    param([string]$Output)

    if ([string]::IsNullOrWhiteSpace($Output)) {
        return $false
    }

    return $Output -match "registry-1\.docker\.io|docker/dockerfile|failed to resolve source metadata|Docker Desktop has no HTTPS proxy|lookup .* no such host|no such host|network is unreachable|i/o timeout|TLS handshake timeout"
}

function Invoke-DockerComposeUp {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string[]]$UpArguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & docker @($ComposeArguments + $UpArguments) 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    if (-not [string]::IsNullOrWhiteSpace($text)) {
        Write-Host $text
    }

    [pscustomobject]@{
        ExitCode = $exitCode
        Output = $text
    }
}

function Invoke-OfflineAppBuild {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$EnvPath
    )

    $backendDir = Join-Path $RepoRoot "backend"
    $runtimeDockerfile = Join-Path $backendDir "Dockerfile.runtime-local"
    $appImage = Get-EnvValue -Path $EnvPath -Name "APP_IMAGE"
    if ([string]::IsNullOrWhiteSpace($appImage)) {
        $appImage = "otziv-app:prod-local"
    }

    Write-Host "Building backend jar locally for offline app image..."
    Push-Location $backendDir
    try {
        Invoke-External -FilePath (Join-Path $backendDir "mvnw.cmd") -Arguments @("-B", "-ntp", "clean", "package", "-DskipTests")
    } finally {
        Pop-Location
    }

    $runtimeBaseImage = ($appImage -replace ":", "-") + ":runtime-base"
    Invoke-External -FilePath "docker" -Arguments @("image", "inspect", "--format", "{{.Id}}", $appImage)
    Invoke-External -FilePath "docker" -Arguments @("tag", $appImage, $runtimeBaseImage)

    Write-Host "Building offline app image $appImage from existing runtime image..."
    $tempRoot = [System.IO.Path]::GetTempPath()
    $tempBuildDir = Join-Path $tempRoot ("otziv-app-runtime-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $tempBuildDir | Out-Null
    try {
        Copy-Item -LiteralPath $runtimeDockerfile -Destination (Join-Path $tempBuildDir "Dockerfile")
        Copy-Item -LiteralPath (Join-Path $backendDir "target\otziv-1.jar") -Destination (Join-Path $tempBuildDir "otziv-1.jar")

        Invoke-External -FilePath "docker" -Arguments @(
            "build",
            "-f", (Join-Path $tempBuildDir "Dockerfile"),
            "--build-arg", "RUNTIME_IMAGE=$runtimeBaseImage",
            "-t", $appImage,
            $tempBuildDir
        )
    } finally {
        $resolvedTempRoot = (Resolve-Path $tempRoot).Path
        $resolvedBuildDir = if (Test-Path -LiteralPath $tempBuildDir) { (Resolve-Path $tempBuildDir).Path } else { $null }
        if ($resolvedBuildDir -and $resolvedBuildDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedBuildDir -Recurse -Force
        }
    }
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
$composePath = if ([System.IO.Path]::IsPathRooted($ComposeFile)) { $ComposeFile } else { Join-Path $repoRoot $ComposeFile }
$envPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $repoRoot $EnvFile }
$envExamplePath = Join-Path $repoRoot ".env.prod-local.example"

if (-not (Test-Path -LiteralPath $composePath)) {
    throw "Compose file not found: $composePath"
}

if (-not (Test-Path -LiteralPath $envPath)) {
    if (-not (Test-Path -LiteralPath $envExamplePath)) {
        throw "Env file not found: $envPath"
    }

    Copy-Item -LiteralPath $envExamplePath -Destination $envPath
    Write-Host "Created $envPath from .env.prod-local.example."
}

$openAiProxyEnabled = Get-EnvValue -Path $envPath -Name "OPENAI_PROXY_ENABLED"
$openAiProxySyncLocalIp = Get-EnvValue -Path $envPath -Name "OPENAI_PROXY_SYNC_LOCAL_IP"
if (
    -not $SkipOpenAiProxyIpSync `
    -and $openAiProxyEnabled `
    -and $openAiProxyEnabled.Equals("true", [System.StringComparison]::OrdinalIgnoreCase) `
    -and -not ($openAiProxySyncLocalIp -and $openAiProxySyncLocalIp.Equals("false", [System.StringComparison]::OrdinalIgnoreCase))
) {
    $proxyIpSyncScript = Join-Path $scriptRoot "update-openai-proxy-local-ip.ps1"
    if (-not (Test-Path -LiteralPath $proxyIpSyncScript)) {
        throw "OpenAI proxy IP sync script not found: $proxyIpSyncScript"
    }

    & $proxyIpSyncScript -EnvFile $envPath
}

$composeArgs = @("compose", "-f", $composePath, "--env-file", $envPath)
if ($WithDbAdmin) {
    $composeArgs += @("--profile", "db-admin")
}
Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("config", "--quiet"))

if ($OfflineAppBuild) {
    Invoke-OfflineAppBuild -RepoRoot $repoRoot -EnvPath $envPath
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
try {
    if (-not $NoUp) {
        $upArgs = @("up", "-d", "--remove-orphans")
        if (-not $NoBuild -and -not $OfflineAppBuild) {
            $upArgs += "--build"
        }

        $upResult = Invoke-DockerComposeUp -ComposeArguments $composeArgs -UpArguments $upArgs
        if ($upResult.ExitCode -ne 0) {
            $canFallback = -not $NoOfflineFallback -and -not $OfflineAppBuild -and -not $NoBuild -and (Test-RegistryBuildFailure -Output $upResult.Output)
            if (-not $canFallback) {
                throw "Command failed: docker $($composeArgs + $upArgs -join ' ')"
            }

            Write-Warning "Docker registry is unavailable. Falling back to offline backend image rebuild from local Maven jar."
            Invoke-OfflineAppBuild -RepoRoot $repoRoot -EnvPath $envPath

            $retryArgs = @("up", "-d", "--remove-orphans")
            $retryResult = Invoke-DockerComposeUp -ComposeArguments $composeArgs -UpArguments $retryArgs
            if ($retryResult.ExitCode -ne 0) {
                throw "Command failed after offline fallback: docker $($composeArgs + $retryArgs -join ' ')"
            }
        }
    }

    if (-not $WithDbAdmin -and -not $NoUp) {
        Stop-DbAdminServices -ComposeArguments @("compose", "-f", $composePath, "--env-file", $envPath)
    }

    Wait-HttpOk -Url "$BaseUrl/actuator/health" -Name "backend health" -Deadline $deadline
    Wait-HttpOk -Url "$BaseUrl/keycloak/realms/otziv/.well-known/openid-configuration" -Name "Keycloak realm" -Deadline $deadline
    Wait-HttpOk -Url "$BaseUrl/" -Name "frontend" -Deadline $deadline

    Invoke-External -FilePath "docker" -Arguments ($composeArgs + @("ps"))
    Write-Host "Local prod-like smoke passed: $BaseUrl"
} catch {
    if (-not $NoLogs) {
        Write-Host ""
        Write-Host "Last container logs:"
        & docker @($composeArgs + @("logs", "--tail=200", "nginx", "app", "keycloak"))
    }

    throw
}
