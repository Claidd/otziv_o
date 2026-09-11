param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]{0,100}$')][string]$Tag = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [string]$ProjectFilesRoot = '',
    [string]$VpsHost = '95.213.248.152',
    [string]$VpsUser = 'hunt',
    [ValidateRange(1, 65535)][int]$VpsPort = 22022,
    [string]$VpsPath = '/docker',
    [string]$SshKey = '',
    [string]$SshKnownHostsFile = '',
    [string]$EnvFile = '',
    [string]$MobileApkPath = '',
    [switch]$CheckOnly,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Help) {
    Write-Host 'Normal release: .\deploy.ps1 -Tag 30.00'
    Write-Host 'Read-only CI verification: .\deploy.ps1 -CheckOnly'
    Write-Host 'Requires clean, updated main, successful main CI, Git/Python 3/Docker/SSH and PowerShell 7.'
    Write-Host 'Production configuration and the existing mobile APK are retained unless an APK path is supplied.'
    return
}
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'Open PowerShell 7 and run this command again. Windows PowerShell 5 is not supported for deployment.'
}
if ($VpsHost -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$' -or $VpsUser -notmatch '^[a-z_][a-z0-9_-]*$') {
    throw 'Invalid SSH destination.'
}
foreach ($tool in @('git', 'python')) { [void](Get-Command $tool -ErrorAction Stop) }
$repoRoot = $PSScriptRoot
$helpers = Join-Path $repoRoot 'infrastructure/scripts/prod'
# This is the first external service action. CI failures must not reach Docker or SSH.
$ciOutput = @(& python (Join-Path $helpers 'release_ci.py') --repo $repoRoot)
if ($LASTEXITCODE -ne 0) { throw ($ciOutput -join [Environment]::NewLine) }
$ci = ($ciOutput -join '') | ConvertFrom-Json
if ($ci.result -ne 'PASS' -or $ci.revision -notmatch '^[0-9a-f]{40}$') { throw 'Invalid CI verification result.' }
Write-Host "CI passed for main $($ci.revision)."
if ($CheckOnly) {
    Write-Host 'Check complete. No Docker or VPS changes were made.'
    return
}
foreach ($tool in @('docker', 'ssh')) { [void](Get-Command $tool -ErrorAction Stop) }
if (-not $ProjectFilesRoot) { $ProjectFilesRoot = Split-Path -Parent $repoRoot }
$ProjectFilesRoot = [IO.Path]::GetFullPath($ProjectFilesRoot)
if (-not $SshKey) { $SshKey = Join-Path $ProjectFilesRoot '.ssh/otziv_vps_ed25519' }
if (-not $SshKnownHostsFile) { $SshKnownHostsFile = Join-Path $ProjectFilesRoot '.ssh/known_hosts' }
if (-not $EnvFile) { $EnvFile = Join-Path $ProjectFilesRoot '.otziv/env/prod.env' }
foreach ($path in @($SshKey, $SshKnownHostsFile, $EnvFile)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required local file missing: $path" }
}
$directory = Join-Path $ProjectFilesRoot ('.otziv/releases/' + $Tag + '-' + [guid]::NewGuid().ToString('N'))
$record = Join-Path $directory 'registry.json'
$registryHelper = Join-Path $helpers 'release_registry.py'
$tunnel = $null
$registryStarted = $false
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$ci | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $directory 'ci.json') -Encoding utf8
try {
    $created = @(& python $registryHelper create $record)
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare private image transport.' }
    $registryStarted = $true
    $registry = ($created -join '') | ConvertFrom-Json
    # ProcessStartInfo keeps arguments separate, including paths containing spaces.
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = (Get-Command ssh).Source
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    foreach ($argument in @('-N', '-T', '-p', "$VpsPort", '-i', $SshKey,
        '-o', "UserKnownHostsFile=$($SshKnownHostsFile.Replace('\','/'))", '-o', 'StrictHostKeyChecking=yes',
        '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes',
        '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
        '-R', "127.0.0.1:$($registry.port):127.0.0.1:$($registry.port)", "$VpsUser@$VpsHost")) {
        $start.ArgumentList.Add($argument)
    }
    $tunnel = [Diagnostics.Process]::Start($start)
    Start-Sleep -Seconds 2
    if ($tunnel.HasExited) { throw 'Private SSH tunnel could not start; production was not changed.' }
    $parameters = @{
        DockerHubNamespace = $registry.namespace; Tag = $Tag
        VpsHost = $VpsHost; VpsUser = $VpsUser; VpsPort = $VpsPort; VpsPath = $VpsPath
        ProjectFilesRoot = $ProjectFilesRoot; SshKey = $SshKey; SshKnownHostsFile = $SshKnownHostsFile
        EnvFile = $EnvFile; RemoteEnvFile = '.env'; SkipEnvUpload = $true
        PreparedDeploySnapshot = $true; DeploySnapshotRevision = $ci.revision
        DeploySnapshotBaseRevision = $ci.revision; DeployProtectedMainRevision = $ci.revision
        PrivateRegistryControlFile = $record; RebuildWhatsApp = $true; RequireMainCi = $true
        PreDeployBackupDirectory = (Join-Path $directory 'database-backup')
    }
    if ($MobileApkPath) { $parameters.MobileApkPath = $MobileApkPath }
    else { $parameters.SkipMobileApkUpload = $true }
    & (Join-Path $helpers 'deploy-prod.ps1') @parameters
    if ($LASTEXITCODE -ne 0) { throw 'Deployment did not complete; inspect its verification output.' }
    Write-Host "Deployment and production checks completed. Evidence: $directory"
} finally {
    if ($null -ne $tunnel) {
        if (-not $tunnel.HasExited) { $tunnel.Kill(); $tunnel.WaitForExit(10000) | Out-Null }
        $tunnel.Dispose()
    }
    if ($registryStarted) {
        & python $registryHelper stop $record | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Inspect owned registry state: $record" }
    }
}
