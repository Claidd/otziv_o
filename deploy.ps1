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
    [ValidateSet('Direct', 'Local')][string]$ImageTransport = 'Direct',
    [switch]$FullRollout,
    [switch]$CheckOnly,
    [switch]$PreflightOnly,
    [string]$ReleaseSessionToken = '',
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$env:PYTHONDONTWRITEBYTECODE = '1'
if ($Help) {
    Write-Host 'Normal release: .\deploy.ps1 -Tag 30.00'
    Write-Host 'Read-only CI verification: .\deploy.ps1 -CheckOnly'
    Write-Host 'Prepare and verify the source bundle and read VPS readiness: .\deploy.ps1 -PreflightOnly'
    Write-Host 'Requires clean, updated main, successful main CI, Git/Python 3/SSH and PowerShell 7.'
    Write-Host 'Images download directly on the VPS. -ImageTransport Local uses local Docker and an SSH tunnel.'
    Write-Host '-FullRollout recreates all application services; database continuity guards still apply.'
    Write-Host 'Production configuration and the existing mobile APK are retained unless an APK path is supplied.'
    return
}
if ($PSVersionTable.PSVersion.Major -lt 7) {
    $runtime = Get-Command pwsh -ErrorAction SilentlyContinue
    $runtimePath = if ($runtime) { $runtime.Source } else { '' }
    $filesRoot = if ($ProjectFilesRoot) { $ProjectFilesRoot } else { Split-Path -Parent $PSScriptRoot }
    $runtimeConfig = Join-Path $filesRoot 'otziv-deploy/powershell.path'
    if (-not $runtimePath -and (Test-Path -LiteralPath $runtimeConfig -PathType Leaf)) {
        $runtimePath = ([IO.File]::ReadAllText($runtimeConfig)).Trim()
    }
    if (-not $runtimePath) { $runtimePath = Join-Path $env:ProgramFiles 'PowerShell/7/pwsh.exe' }
    if (-not (Test-Path -LiteralPath $runtimePath -PathType Leaf)) {
        throw 'PowerShell 7 was not found. Install it or save its full path in otziv-deploy/powershell.path beside the project.'
    }
    $forward = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath)
    foreach ($name in $PSBoundParameters.Keys) {
        $value = $PSBoundParameters[$name]
        if ($value -is [Management.Automation.SwitchParameter]) {
            if ($value.IsPresent) { $forward += "-$name" }
        } else { $forward += @("-$name", [string]$value) }
    }
    & $runtimePath @forward
    exit $LASTEXITCODE
}
if ($VpsHost -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$' -or $VpsUser -notmatch '^[a-z_][a-z0-9_-]*$') {
    throw 'Invalid SSH destination.'
}
foreach ($tool in @('git', 'python')) { [void](Get-Command $tool -ErrorAction Stop) }
$repoRoot = $PSScriptRoot
$helpers = Join-Path $repoRoot 'infrastructure/scripts/prod'
. (Join-Path $helpers 'DeploySnapshot.ps1')
. (Join-Path $helpers 'ReleaseMetrics.ps1')
$ciStartedAt = [DateTimeOffset]::UtcNow
if (-not $ProjectFilesRoot) { $ProjectFilesRoot = Split-Path -Parent $repoRoot }
$ProjectFilesRoot = [IO.Path]::GetFullPath($ProjectFilesRoot)
$canonicalWorkspace = Join-Path $ProjectFilesRoot 'otziv'
# This is the first external service action. CI failures must not reach Docker or SSH.
$ciOutput = @(& python (Join-Path $helpers 'release_ci.py') --repo $repoRoot --workspace $canonicalWorkspace)
if ($LASTEXITCODE -ne 0) { throw ($ciOutput -join [Environment]::NewLine) }
$ci = ($ciOutput -join '') | ConvertFrom-Json
if ($ci.result -ne 'PASS' -or $ci.revision -notmatch '^[0-9a-f]{40}$') { throw 'Invalid CI verification result.' }
Write-Host "CI passed for main $($ci.revision)."
if ($CheckOnly) {
    Write-Host 'Check complete. No Docker or VPS changes were made.'
    return
}
[void](Get-Command ssh -ErrorAction Stop)
if ($ImageTransport -eq 'Local') { [void](Get-Command docker -ErrorAction Stop) }
if (-not $SshKey) { $SshKey = Join-Path $ProjectFilesRoot '.ssh/otziv_vps_ed25519' }
if (-not $SshKnownHostsFile) { $SshKnownHostsFile = Join-Path $ProjectFilesRoot '.ssh/known_hosts' }
if (-not $EnvFile) { $EnvFile = Join-Path $ProjectFilesRoot '.otziv/env/prod.env' }
foreach ($path in @($SshKey, $SshKnownHostsFile, $EnvFile)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required local file missing: $path" }
}
# Docker Desktop can bind files on the system drive reliably. Keep private
# operator evidence outside the source snapshot and independently of SSH files.
$directory = Join-Path ([Environment]::GetFolderPath('UserProfile')) ('otziv-deploy/releases/' + $Tag + '-' + [guid]::NewGuid().ToString('N'))
$record = Join-Path $directory 'registry.json'
$registryHelper = Join-Path $helpers 'release_registry.py'
$tunnel = $null
$registryStarted = $false
$releaseCompleted = $false
$directHelper = Join-Path $helpers 'remote_release_transport.py'
$sessionHelper = Join-Path $helpers 'release_session.py'
$session = $null
New-Item -ItemType Directory -Path $directory -Force | Out-Null
if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
    $operatorSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $directory '/inheritance:r' '/grant:r' "*${operatorSid}:(OI)(CI)F" '/grant:r' '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot protect the release evidence directory.' }
} else {
    & chmod 700 -- $directory
    if ($LASTEXITCODE -ne 0) { throw 'Cannot protect the release evidence directory.' }
}
$ci | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $directory 'ci.json') -Encoding utf8
$timing = Join-Path $directory 'timing.json'
@{ schema = 'otziv-release-timing-v1'; startedAt = $ciStartedAt.ToString('o');
    stages = @(@{ name = 'exact-main-ci-verification'; startedAt = $ciStartedAt.ToString('o') })
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $timing -Encoding utf8
$manifest = Join-Path $directory 'ci-release.json'
$capacity = Join-Path $directory 'capacity.json'
$artifactHelper = Join-Path $helpers 'ci_release.py'
$probeArguments = @('--host', $VpsHost, '--user', $VpsUser, '--port', "$VpsPort", '--key', $SshKey,
    '--known-hosts', $SshKnownHostsFile, '--path', $VpsPath)
try {
    $sessionArguments = @('-B', $sessionHelper, 'begin', '--repo', $repoRoot, '--pid', "$PID")
    if ($ReleaseSessionToken) { $sessionArguments += @('--token', $ReleaseSessionToken) }
    $sessionOutput = @(& python @sessionArguments)
    if ($LASTEXITCODE -ne 0) { throw 'Another release owns the main checkout; inspect release_session.py status.' }
    $session = ($sessionOutput -join '') | ConvertFrom-Json
    Set-OtzivReleaseStage -Path $timing -Stage 'committed-source-preparation'
    [void](Assert-OtzivPreparedDeploySnapshotState -Repository $repoRoot -ExpectedRevision $ci.revision -CanonicalWorkspace $canonicalWorkspace)
    $preparedSource = New-OtzivPreparedDeployArchive -Repository $repoRoot -Revision $ci.revision -Directory $directory
    & (Join-Path $repoRoot 'infrastructure/scripts/security/check-backup-readiness.ps1') -EnvFile $EnvFile
    if ($LASTEXITCODE -ne 0) { throw 'Local backup readiness failed before image transport.' }
    Set-OtzivReleaseStage -Path $timing -Stage 'manifest-and-server-preflight'
    & python -B $artifactHelper fetch --repo $repoRoot --input (Join-Path $directory 'ci.json') --output $manifest
    if ($LASTEXITCODE -ne 0) { throw 'Verified main CI image manifest is unavailable.' }
    $qualityRun = @($ci.runs | Where-Object { $_.workflow -eq '.github/workflows/quality-gates.yml' })[0].runId
    & python -B (Join-Path $helpers 'release_metrics.py') --repo $repoRoot --run-id $qualityRun --output (Join-Path $directory 'ci-timing.json')
    if ($LASTEXITCODE -ne 0) { Write-Warning 'CI timing collection failed; mandatory CI verification already passed.' }
    $earlyArguments = @('-B', (Join-Path $helpers 'release_preflight.py'), '--manifest', $manifest,
        '--output', (Join-Path $directory 'early-preflight.json')) + $probeArguments
    if ($MobileApkPath) { $earlyArguments += @('--apk', $MobileApkPath) }
    & python @earlyArguments
    if ($LASTEXITCODE -ne 0) { throw 'Early server preflight failed before large image downloads; production was not changed.' }
    if ($PreflightOnly) {
        $releaseCompleted = $true
        Write-Host "Source bundle and VPS readiness verified. No image transfer or deployment performed. Evidence: $directory"
        return
    }
    Set-OtzivReleaseStage -Path $timing -Stage 'verified-image-transport'
    if ($ImageTransport -eq 'Direct') {
        $directArguments = @('-B', $directHelper, 'prepare', '--record', $record,
            '--manifest', $manifest, '--output', $capacity,
            '--preflight', (Join-Path $directory 'early-preflight.json')) + $probeArguments
        $registryStarted = $true
        & python @directArguments
        if ($LASTEXITCODE -ne 0) { throw 'Direct image transport failed; production services were not changed.' }
        $registry = Get-Content -Raw -Encoding UTF8 -LiteralPath $record | ConvertFrom-Json
    } else {
        $created = @(& python $registryHelper create $record)
        if ($LASTEXITCODE -ne 0) { throw 'Could not prepare private image transport.' }
        $registryStarted = $true
        $registry = ($created -join '') | ConvertFrom-Json
        & python -B $artifactHelper import --repo $repoRoot --input $manifest --registry $record --output $capacity
        if ($LASTEXITCODE -ne 0) { throw 'CI image verification or private transport failed; production was not changed.' }
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
    }
    $parameters = @{
        DockerHubNamespace = $registry.namespace; Tag = $Tag
        VpsHost = $VpsHost; VpsUser = $VpsUser; VpsPort = $VpsPort; VpsPath = $VpsPath
        ProjectFilesRoot = $ProjectFilesRoot; SshKey = $SshKey; SshKnownHostsFile = $SshKnownHostsFile
        EnvFile = $EnvFile; RemoteEnvFile = '.env'; SkipEnvUpload = $true
        PreparedDeploySnapshot = $true; DeploySnapshotRevision = $ci.revision
        DeploySnapshotBaseRevision = $ci.revision; DeployProtectedMainRevision = $ci.revision
        PrivateRegistryControlFile = $record; RebuildWhatsApp = $true; RequireMainCi = $true; SkipBuildPush = $true
        CiReleaseManifest = $manifest; CiCapacityPlan = $capacity; FullRollout = $FullRollout
        PreDeployBackupDirectory = (Join-Path $directory 'database-backup')
        PreparedSourceArchive = $preparedSource.archive; PreparedSourceSha256 = $preparedSource.sha256
        ReleaseTimingFile = $timing
    }
    if ($MobileApkPath) { $parameters.MobileApkPath = $MobileApkPath }
    else { $parameters.SkipMobileApkUpload = $true }
    Set-OtzivReleaseStage -Path $timing -Stage 'installation-preparation'
    & (Join-Path $helpers 'deploy-prod.ps1') @parameters
    if ($LASTEXITCODE -ne 0) { throw 'Deployment did not complete; inspect its verification output.' }
    Set-OtzivReleaseStage -Path $timing -Stage 'installed-image-verification'
    & python -B (Join-Path $helpers 'production_images.py') record --manifest $manifest --output (Join-Path $directory 'production-images.json') @probeArguments
    if ($LASTEXITCODE -ne 0) { throw 'Installation completed, but daily-scan inventory registration failed. Preserve the release evidence and retry registration.' }
    $releaseCompleted = $true
    Write-Host "Deployment and production checks completed. Evidence: $directory"
} finally {
    if ($null -ne $tunnel) {
        if (-not $tunnel.HasExited) { $tunnel.Kill(); $tunnel.WaitForExit(10000) | Out-Null }
        $tunnel.Dispose()
    }
    if ($registryStarted) {
        if ($ImageTransport -eq 'Direct' -and (Test-Path -LiteralPath $record)) {
            $stopArguments = @('-B', $directHelper, 'stop', '--record', $record)
            if ($releaseCompleted) { $stopArguments += '--completed' }
            & python @stopArguments | Out-Null
        } elseif ($ImageTransport -eq 'Local') {
            & python $registryHelper stop $record | Out-Null
        }
        if ($LASTEXITCODE -ne 0) { Write-Warning "Inspect owned registry state: $record" }
    }
    if ($null -ne $session) {
        & python -B $sessionHelper finish --repo $repoRoot --token $session.token | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Could not release the local coordination lease; inspect release_session.py status.' }
    }
    Set-OtzivReleaseStage -Path $timing -Stage 'complete' -Result $(if ($releaseCompleted) { 'success' } else { 'failure' })
}
