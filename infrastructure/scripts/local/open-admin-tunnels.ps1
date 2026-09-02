param(
    [string]$VpsHost = "95.213.248.152",
    [string]$VpsUser = "hunt",
    [int]$VpsPort = 22022,
    [string]$SshKey = "",
    [int]$LocalGrafanaPort = 3001,
    [int]$RemoteGrafanaPort = 3001,
    [int]$LocalDozzlePort = 8081,
    [int]$RemoteDozzlePort = 8081
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$envResolverPath = Join-Path $repoRoot "infrastructure\scripts\Resolve-OtzivEnvFile.ps1"
if (-not (Test-Path -LiteralPath $envResolverPath -PathType Leaf)) {
    throw "Project path resolver script not found: $envResolverPath"
}
. $envResolverPath
if ([string]::IsNullOrWhiteSpace($SshKey)) {
    $SshKey = Join-Path (Get-OtzivSshDirectory -RepoRoot $repoRoot) "otziv_vps_ed25519"
}

if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}

$sshArgs = @(
    "-N",
    "-o", "ExitOnForwardFailure=yes",
    "-o", "ServerAliveInterval=30",
    "-o", "ServerAliveCountMax=3",
    "-p", "$VpsPort",
    "-i", $SshKey,
    "-L", "$LocalGrafanaPort`:127.0.0.1:$RemoteGrafanaPort",
    "-L", "$LocalDozzlePort`:127.0.0.1:$RemoteDozzlePort",
    "$VpsUser@$VpsHost"
)

Write-Host "Opening admin tunnels. Keep this window open." -ForegroundColor Cyan
Write-Host "Grafana: http://localhost:$LocalGrafanaPort/grafana/" -ForegroundColor Green
Write-Host "Dozzle:  http://localhost:$LocalDozzlePort/" -ForegroundColor Green
Write-Host ""
Write-Host "Press Ctrl+C to close tunnels." -ForegroundColor Yellow

& ssh @sshArgs
