param(
    [string]$VpsHost = "95.213.248.152",
    [string]$VpsUser = "hunt",
    [int]$VpsPort = 22022,
    [string]$SshKey = "$env:USERPROFILE\.ssh\otziv_vps_ed25519",
    [int]$LocalGrafanaPort = 3001,
    [int]$RemoteGrafanaPort = 3001,
    [int]$LocalDozzlePort = 8081,
    [int]$RemoteDozzlePort = 8081
)

$ErrorActionPreference = "Stop"

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
